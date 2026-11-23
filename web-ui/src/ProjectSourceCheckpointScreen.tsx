import { useCallback, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { Accordion, AccordionItem, Button, SelectField } from './forms-library';
import { ProjectSourceCheckpointContinue, type SourceCheckpointContinuation,
  type SourceCheckpointContinuationMemory } from './ProjectSourceCheckpointContinue';

export type Scope = Readonly<{ projectId: string; checkoutId: string; projectName: string }>;
export type Version = Readonly<{
  projectId: string; checkoutId: string; checkpointId: string; storageSlot: number;
  manifestIdentity: string; filteredSourceDigest: string; archiveSha256: string; archiveBytes: number;
  capturedAt: number; fileCount: number; omittedCount: number; expandedBytes: number;
}>;
export type SavePin = Readonly<{ reviewId: string; manifestIdentity: string }>;
export type Recovery = SavePin & Readonly<{ expected: Version }>;
export type Catalog = Readonly<{ scope: Scope; versions: readonly Version[]; pending: boolean; storedBytes: number; recovery: Recovery | null }>;
export type PendingCheckpointReview = Readonly<{ scope: Scope; id: string; deadline: number }>;
type OmissionKind = 'DEPENDENCY_ENVIRONMENT_OR_PRIVATE_DIRECTORY' | 'GENERATED_ROOT_NOT_SELECTED' | 'CREDENTIAL_OR_PRIVATE_CONFIGURATION';
export type Review = Readonly<{
  scope: Scope; id: string; manifestIdentity: string; filteredSourceDigest: string; expandedBytes: number;
  files: readonly Readonly<{ path: string; sha256: string; bytes: number }>[];
  directories: readonly string[];
  omitted: readonly Readonly<{ path: string; kind: OmissionKind }>[];
}>;
export type Save = Readonly<{ scope: Scope; status: 'SAVED' | 'CAPACITY' | 'PENDING' | 'NOT_SAVED' | 'UNKNOWN'; version: Version | null }>;
type Entry = Readonly<{ kind: 'FILE' | 'DIRECTORY'; sha256: string | null; bytes: number; executable: boolean }>;
type Change = Readonly<{
  path: string; kind: 'ADDED_TO_CHECKPOINT' | 'REMOVED_FROM_CHECKPOINT' | 'CHANGED_IN_CHECKPOINT' | 'EXCLUDED_IN_BEFORE' | 'EXCLUDED_IN_AFTER';
  before: Entry | null; after: Entry | null;
  exclusion: Readonly<{ omittedPath: string; kind: OmissionKind; exact: boolean }> | null;
}>;
export type Comparison = Readonly<{ scope: Scope; before: Version; after: Version; paths: readonly Change[] }>;

/** Dormant presentation adapter, not bridge DTOs. The same native owner Window
 * owns this immutable scope, opaque Catalog and the one displayed SaveWindow
 * Review. Bind the exact offered objects to those native values; do not reconstruct
 * them from renderer input. The owner must explicitly enter from the selected,
 * clean private Files view. Call onRetired at actual window/selection/authority
 * loss. Discard only that review, without recapturing or publishing. A failed
 * Save is UNKNOWN unless native can prove a definite outcome before publication.
 * Comparison uses only included paths and positive omissions. A future owner host
 * may supply the independent Continue capability; absent it nothing is shown.
 */
export type ProjectSourceCheckpointVisit = Readonly<{
  scope: Scope;
  isCurrent: () => boolean;
  recovery: Recovery | null;
  canReviewSavedSource: () => boolean;
  onRetired: (retire: () => void) => () => void;
  catalogLocal: () => Promise<Catalog>;
  reviewSavedSource: () => Promise<Review>;
  discardReview: (review: Review) => Promise<'NOT_SAVED' | 'UNKNOWN'>;
  saveReviewedSource: (review: Review) => Promise<Save>;
  readSaved: (catalog: Catalog, version: Version) => Promise<Version>;
  compareSaved: (catalog: Catalog, before: Version, after: Version) => Promise<Comparison>;
  pending?: Readonly<{
    canReview: (catalog: Catalog) => boolean;
    review: (catalog: Catalog) => Promise<PendingCheckpointReview | null>;
    clear: (review: PendingCheckpointReview) => Promise<'CLEARED' | 'NOT_CLEARED' | 'UNKNOWN'>;
    dismiss: (review: PendingCheckpointReview) => Promise<void>;
  }>;
  continuation?: SourceCheckpointContinuation;
}>;

const MIB = 1024 * 1024;
const MAX_VERSIONS = 4, MAX_STORED = 64 * MIB, MAX_FILES = 2000;
const range = (v: unknown, min: number, max: number) => typeof v === 'number' && Number.isSafeInteger(v) && v >= min && v <= max;
const sha = (v: unknown) => typeof v === 'string' && /^[a-f0-9]{64}$/.test(v);
const uuid = (v: unknown) => typeof v === 'string' && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(v);
const relative = (v: unknown) => typeof v === 'string' && v.length <= 240 && !/[:\\\u0000-\u001f]/.test(v) &&
  v.split('/').every(part => part && part !== '.' && part !== '..');
const reasons: Record<OmissionKind, string> = {
  DEPENDENCY_ENVIRONMENT_OR_PRIVATE_DIRECTORY: '依赖环境或私有目录',
  GENERATED_ROOT_NOT_SELECTED: '生成产物目录',
  CREDENTIAL_OR_PRIVATE_CONFIGURATION: '凭据或私有配置',
};
const reasonKnown = (v: unknown) => typeof v === 'string' && Object.hasOwn(reasons, v);
function pathsInOrder<T>(values: readonly T[], max: number, path: (row: T) => string) {
  if (!Array.isArray(values) || values.length > max) return false;
  let previous = '';
  for (const row of values) {
    const next = path(row);
    if (!relative(next) || next <= previous) return false;
    previous = next;
  }
  return true;
}
function versionHere(scope: Scope, v: Version | null | undefined): v is Version {
  return !!v && v.projectId === scope.projectId && v.checkoutId === scope.checkoutId && uuid(v.checkpointId) &&
    range(v.storageSlot, 1, MAX_VERSIONS) && sha(v.manifestIdentity) && sha(v.filteredSourceDigest) &&
    sha(v.archiveSha256) && range(v.archiveBytes, 22, 20 * MIB) && range(v.expandedBytes, 0, 16 * MIB) &&
    range(v.capturedAt, 1, 8_640_000_000_000_000) && range(v.fileCount, 1, MAX_FILES) && range(v.omittedCount, 0, MAX_FILES);
}
export function sameVersion(a: Version, b: Version) {
  return a.projectId === b.projectId && a.checkoutId === b.checkoutId && a.checkpointId === b.checkpointId &&
    a.storageSlot === b.storageSlot && a.manifestIdentity === b.manifestIdentity && a.filteredSourceDigest === b.filteredSourceDigest &&
    a.archiveSha256 === b.archiveSha256 && a.archiveBytes === b.archiveBytes && a.capturedAt === b.capturedAt &&
    a.fileCount === b.fileCount && a.omittedCount === b.omittedCount && a.expandedBytes === b.expandedBytes;
}
export function recoveryHere(scope: Scope, value: Recovery | null | undefined): value is Recovery {
  return !!value && uuid(value.reviewId) && sha(value.manifestIdentity) && versionHere(scope, value.expected) &&
    value.expected.checkpointId === value.reviewId && value.expected.manifestIdentity === value.manifestIdentity;
}
function acceptedCatalog(scope: Scope, catalog: Catalog) {
  if (catalog?.scope !== scope || typeof catalog.pending !== 'boolean' || !range(catalog.storedBytes, 0, MAX_STORED) ||
      !Array.isArray(catalog.versions) || catalog.versions.length > MAX_VERSIONS) return false;
  const ids = new Set<string>(), slots = new Set<number>();
  let bytes = 0;
  for (const row of catalog.versions) {
    if (!versionHere(scope, row) || ids.has(row.checkpointId) || slots.has(row.storageSlot)) return false;
    ids.add(row.checkpointId); slots.add(row.storageSlot); bytes += row.archiveBytes;
  }
  return bytes <= catalog.storedBytes && (catalog.recovery === null || recoveryHere(scope, catalog.recovery));
}
function acceptedReview(scope: Scope, review: Review) {
  return review?.scope === scope && uuid(review.id) && sha(review.manifestIdentity) && sha(review.filteredSourceDigest) &&
    range(review.expandedBytes, 0, 16 * MIB) &&
    pathsInOrder(review.files, MAX_FILES, row => row?.path) && review.files.length > 0 &&
    review.files.every(row => sha(row.sha256) && range(row.bytes, 0, 2 * MIB)) &&
    review.files.reduce((bytes, row) => bytes + row.bytes, 0) === review.expandedBytes &&
    pathsInOrder(review.directories, MAX_FILES, path => path) &&
    pathsInOrder(review.omitted, MAX_FILES, row => row?.path) && review.omitted.every(row => reasonKnown(row.kind));
}
function savedVersion(scope: Scope, review: Review, v: Version | null): v is Version {
  return versionHere(scope, v) && v.checkpointId === review.id && v.manifestIdentity === review.manifestIdentity &&
    v.filteredSourceDigest === review.filteredSourceDigest && v.expandedBytes === review.expandedBytes &&
    v.fileCount === review.files.length && v.omittedCount === review.omitted.length;
}
function validEntry(value: Entry | null): value is Entry {
  return !!value && (value.kind === 'DIRECTORY'
    ? value.sha256 === null && value.bytes === 0 && value.executable === false
    : value.kind === 'FILE' && sha(value.sha256) && range(value.bytes, 0, 2 * MIB) && typeof value.executable === 'boolean');
}
function validChange(row: Change) {
  const a = row.before, b = row.after, omitted = row.exclusion;
  switch (row.kind) {
    case 'EXCLUDED_IN_BEFORE':
    case 'EXCLUDED_IN_AFTER':
      return !!omitted && reasonKnown(omitted.kind) && relative(omitted.omittedPath) &&
        (omitted.exact === true ? row.path === omitted.omittedPath :
          omitted.exact === false && omitted.kind !== 'CREDENTIAL_OR_PRIVATE_CONFIGURATION' && row.path.startsWith(omitted.omittedPath + '/')) &&
        (row.kind === 'EXCLUDED_IN_BEFORE' ? a === null && validEntry(b) : validEntry(a) && b === null);
    case 'ADDED_TO_CHECKPOINT': return omitted === null && a === null && validEntry(b);
    case 'REMOVED_FROM_CHECKPOINT': return omitted === null && validEntry(a) && b === null;
    case 'CHANGED_IN_CHECKPOINT': return omitted === null && validEntry(a) && validEntry(b) &&
      (a.kind !== b.kind || a.sha256 !== b.sha256 || a.executable !== b.executable || a.bytes !== b.bytes);
    default: return false;
  }
}
export function checkedCheckpointComparison(scope: Scope, before: Version, after: Version, result: Comparison | undefined): Comparison {
  if (result?.scope !== scope || !versionHere(scope, result.before) || !versionHere(scope, result.after) ||
      !sameVersion(before, result.before) || !sameVersion(after, result.after) ||
      !pathsInOrder(result.paths, 4 * MAX_FILES, row => row?.path) || !result.paths.every(validChange)) throw Error();
  return result;
}
function versionName(version: Version, catalog?: Catalog) {
  const stamp = new Date(version.capturedAt).toLocaleString();
  const peers = catalog?.versions.filter(row => new Date(row.capturedAt).toLocaleString() === stamp) ?? [];
  const seen = peers.findIndex(row => sameVersion(row, version));
  return peers.length > 1 && seen >= 0 ? stamp + ' · 第 ' + (seen + 1) + ' 版' : stamp;
}

function detail(row: Change) {
  switch (row.kind) {
    case 'EXCLUDED_IN_BEFORE': return '对照版按规则未收录，所选版有收录';
    case 'EXCLUDED_IN_AFTER': return '所选版按规则未收录';
    case 'ADDED_TO_CHECKPOINT': return '所选版新增收录';
    case 'REMOVED_FROM_CHECKPOINT': return '所选版不再收录';
    case 'CHANGED_IN_CHECKPOINT': {
      const a = row.before!, b = row.after!;
      if (a.kind !== b.kind) return a.kind === 'FILE' ? '文件变为目录' : '目录变为文件';
      const bytesChanged = a.sha256 !== b.sha256 || a.bytes !== b.bytes;
      return bytesChanged ? '文件内容有变化' + (a.executable !== b.executable ? '，可执行标记也有变化' : '')
        : '仅可执行标记有变化';
    }
  }
}
function changeSummary(paths: readonly Change[]) {
  const counts = { added: 0, missing: 0, changed: 0, excluded: 0 };
  for (const row of paths) switch (row.kind) {
    case 'ADDED_TO_CHECKPOINT': counts.added++; break;
    case 'REMOVED_FROM_CHECKPOINT': counts.missing++; break;
    case 'CHANGED_IN_CHECKPOINT': counts.changed++; break;
    default: counts.excluded++;
  }
  return [counts.added && `新增收录 ${counts.added} 项`, counts.missing && `不再收录 ${counts.missing} 项`,
    counts.changed && `收录内容或类型变化 ${counts.changed} 项`, counts.excluded && `因规则排除 ${counts.excluded} 项`]
    .filter(Boolean).join(' · ');
}
function PathRows<T>({ rows, path, render, empty }: { rows: readonly T[]; path: (row: T) => string; render: (row: T) => ReactNode; empty: string }) {
  const [count, setCount] = useState(30);
  if (!rows.length) return <p className="pw-field-hint">{empty}</p>;
  return <><ul className="pw-host-file-comparison">{rows.slice(0, count).map(row =>
    <li key={path(row)}><bdi>{path(row)}</bdi>{render(row)}</li>)}</ul>
    {rows.length > count && <Button size="small" variant="ghost" onClick={() => setCount(count + 30)}>
      展开其余 {Math.min(30, rows.length - count)} 项
    </Button>}</>;
}

function ComparisonRows({ comparison, catalog }: { comparison: Comparison; catalog?: Catalog }) {
  const { before, after, paths } = comparison;
  return <>
    <p>对照版 {versionName(before, catalog)} → 所选版 {versionName(after, catalog)}</p>
    {paths.length > 0 && <p>{changeSummary(paths)}</p>}
    <p className="pw-field-hint">只比较当时收录的路径和版本标记，不读取当前文件或被排除的内容。不收录不等于从硬盘删除。</p>
    <PathRows key={before.checkpointId + ':' + after.checkpointId} rows={paths}
      path={row => row.path} empty="两版收录范围内未见路径差异。" render={row => <>
        <span>{detail(row)}</span>
        {row.before?.kind === 'FILE' && row.after?.kind === 'FILE' && row.before.bytes !== row.after.bytes &&
          <small>{row.before.bytes.toLocaleString()} → {row.after.bytes.toLocaleString()} 字节</small>}
        {row.exclusion && <small>{reasons[row.exclusion.kind]} · {row.exclusion.exact ? '此路径' : '上级路径'}
          {' '}<bdi>{row.exclusion.omittedPath}</bdi></small>}
      </>} />
  </>;
}

/** Native Back provides a fresh result, not a new Catalog or a Continue grant. */
export function ReturnedSourceCheckpointComparison({ comparison, onExit }: { comparison: Comparison; onExit: () => void }) {
  const heading = useRef<HTMLHeadingElement>(null);
  useLayoutEffect(() => { heading.current?.focus({ preventScroll: true }); }, [comparison]);
  return <section className="pw-form-flow">
    <h3 ref={heading} tabIndex={-1}>两版源码的收录差异</h3>
    <p className="pw-field-hint" dir="auto">{comparison.scope.projectName} · 原私人副本</p>
    <ComparisonRows comparison={comparison} />
    <div className="pw-form-actions"><Button variant="ghost" onClick={onExit}>返回原副本的文件</Button></div>
  </section>;
}

type Staging = { kind: 'review'; offer: PendingCheckpointReview } | { kind: 'refresh'; retired?: true } | { kind: 'exit' };
type Shelf = { kind: 'shelf'; catalog?: Catalog; knownSaved?: Version; restore?: string; notice?: string; staging?: Staging };
type Pick = { kind: 'pick'; shelf: Shelf; catalog: Catalog; after: Version; before?: Version; notice?: string };
type ReviewPage = { kind: 'review'; shelf: Shelf; review: Review; leaveUnknown?: true };
type Page = Shelf | ReviewPage | Pick | { kind: 'comparison'; pick: Pick; comparison: Comparison };
type Operation = 'catalog' | 'review' | 'save' | 'dismiss' | 'read' | 'compare' | 'pendingReview' | 'pendingClear' | 'pendingDismiss';
type Session = {
  visit: ProjectSourceCheckpointVisit; scope: Scope; projectId: string; checkoutId: string;
  stage?: Review; flight?: { kind: Operation }; unconfirmed?: SavePin; expected?: Version; saveBlocked?: 'capacity' | 'pending';
  continuations: SourceCheckpointContinuationMemory;
};
type View = { session: Session; page: Page; busy?: Operation };
const uncertain = '本次保留是否成功还未确认。可查看同一副本的本机记录，不会再保存一次。';

function isCurrent(session: Session) {
  if (document.hidden || session.visit.scope !== session.scope || session.scope.projectId !== session.projectId ||
      session.scope.checkoutId !== session.checkoutId) return false;
  try { return session.visit.isCurrent() === true; } catch { return false; }
}
function release(session: Session, review: Review | undefined) {
  if (!review || review.scope !== session.scope || !uuid(review.id)) return;
  void session.visit.discardReview(review).catch(() => {}); // Native ownership/expiry is final.
}

/** Unmounted. Future host uses this in its existing private Files body, not a second dialog. */
export function ProjectSourceCheckpointScreen({ visit, registerBack, onExit }: {
  visit?: ProjectSourceCheckpointVisit;
  registerBack?: (back: (() => boolean) | null) => void;
  onExit?: () => void;
}) {
  const [view, setView] = useState<View>();
  const [pendingExpired, setPendingExpired] = useState(false);
  const owner = useRef<Session | undefined>(undefined);
  const heading = useRef<HTMLHeadingElement>(null), rowFocus = useRef<HTMLButtonElement>(null);
  const pendingHeading = useRef<HTMLHeadingElement>(null), pendingButton = useRef<HTMLButtonElement>(null), catalogButton = useRef<HTMLButtonElement>(null);
  const pendingFocus = useRef<{ session: Session; target: 'pending' | 'catalog' } | undefined>(undefined);
  const continuationBack = useRef<(() => boolean) | null>(null);
  const registerContinuationBack = useCallback((back: (() => boolean) | null) => { continuationBack.current = back; }, []);

  useLayoutEffect(() => {
    const scope = visit?.scope;
    if (!visit || !scope || typeof scope.projectId !== 'string' || !/^[A-Za-z0-9_-]{1,80}$/.test(scope.projectId) ||
        !uuid(scope.checkoutId) || typeof scope.projectName !== 'string' || !scope.projectName.trim() || scope.projectName.length > 80) {
      setView(undefined); return;
    }
    if (visit.recovery !== null && !recoveryHere(scope, visit.recovery)) { setView(undefined); return; }
    const session: Session = { visit, scope, projectId: scope.projectId, checkoutId: scope.checkoutId, continuations: new Map(),
      unconfirmed: visit.recovery ?? undefined, expected: visit.recovery?.expected };
    if (!isCurrent(session)) { setView(undefined); return; }
    owner.current = session;
    setView({ session, page: { kind: 'shelf' } });
    const forget = () => {
      if (owner.current !== session) return;
      owner.current = undefined;
      const stage = ownerStage(session);
      release(session, stage);
    };
    const retire = () => { if (owner.current === session) { forget(); setView(undefined); } };
    const hidden = () => { if (document.hidden) retire(); };
    let unsubscribe: () => void;
    try { unsubscribe = visit.onRetired(retire); }
    catch { retire(); return; }
    if (!isCurrent(session)) retire();
    document.addEventListener('visibilitychange', hidden);
    window.addEventListener('pagehide', retire);
    return () => {
      forget(); unsubscribe();
      document.removeEventListener('visibilitychange', hidden);
      window.removeEventListener('pagehide', retire);
    };
  }, [visit]);

  const showing = view && view.session === owner.current && view.session.visit === visit && isCurrent(view.session) ? view : undefined;
  const phase = showing?.page.kind;
  const restore = showing?.page.kind === 'shelf' ? showing.page.restore : undefined;
  const pendingOffer = showing?.page.kind === 'shelf' && showing.page.staging?.kind === 'review' ? showing.page.staging.offer : undefined;
  useLayoutEffect(() => {
    const row = phase === 'shelf' && rowFocus.current;
    const target = row || heading.current;
    if (phase && target?.isConnected) {
      target.focus({ preventScroll: true });
      if (row) row.scrollIntoView({ block: 'nearest' });
    }
  }, [phase, restore]);
  useLayoutEffect(() => {
    setPendingExpired(false);
    if (!pendingOffer) return;
    pendingHeading.current?.focus({ preventScroll: true });
    pendingHeading.current?.scrollIntoView({ block: 'nearest' });
    const timer = window.setTimeout(() => setPendingExpired(true), Math.max(0, pendingOffer.deadline - performance.now()));
    return () => window.clearTimeout(timer);
  }, [pendingOffer]);
  useLayoutEffect(() => {
    const desired = pendingFocus.current;
    if (!desired || !showing || showing.busy) return;
    pendingFocus.current = undefined;
    const focused = document.activeElement;
    if (desired.session !== showing.session || focused && focused !== document.body && focused.isConnected) return;
    const target = desired.target === 'pending' ? pendingButton.current : catalogButton.current;
    if (target?.isConnected && !target.disabled) target.focus({ preventScroll: true });
  }, [showing]);

  function show(session: Session, page: Page, busy?: Operation) {
    if (owner.current === session && isCurrent(session)) setView({ session, page, busy });
  }

  function back() {
    if (!showing || owner.current !== showing.session || !isCurrent(showing.session)) return false;
    const { page, session } = showing;
    if (page.kind === 'comparison' && continuationBack.current?.()) return true;
    if (session.flight?.kind === 'dismiss' || session.flight?.kind === 'pendingDismiss') return true;
    if (session.flight?.kind === 'save' || session.flight?.kind === 'pendingClear') return false; // Close the exact native visit first.
    if (page.kind === 'shelf' && page.staging?.kind === 'review') { settlePending(page, false); return true; }
    if (page.kind === 'review') {
      if (page.leaveUnknown || session.stage !== page.review) return false;
      const flight = { kind: 'dismiss' } as const;
      session.stage = undefined; session.flight = flight;
      show(session, page, 'dismiss');
      void session.visit.discardReview(page.review).then(status => {
        if (session.flight !== flight) return;
        session.flight = undefined;
        if (status === 'UNKNOWN') session.unconfirmed = { reviewId: page.review.id, manifestIdentity: page.review.manifestIdentity };
        show(session, status === 'UNKNOWN' ? { kind: 'shelf', notice: uncertain } : page.shelf);
      }, () => {
        if (session.flight !== flight) return;
        session.flight = undefined; show(session, { ...page, leaveUnknown: true });
      });
    } else {
      session.flight = undefined;
      if (page.kind === 'comparison') show(session, page.pick);
      else if (page.kind === 'pick') show(session, { ...page.shelf, restore: page.after.checkpointId });
      else return false;
    }
    return true;
  }
  useLayoutEffect(() => {
    if (!registerBack || !showing) return;
    registerBack(back);
    return () => registerBack(null);
  }, [registerBack, showing]);

  if (!showing) return null;
  const { session, page, busy } = showing, { scope } = session;

  async function inspect<T>(kind: Operation, read: () => Promise<T>, accepted: (value: T) => Page, failed: () => Page,
                            retired?: (value: T) => void) {
    if (owner.current !== session || !isCurrent(session) || session.flight) return;
    const flight = { kind };
    session.flight = flight;
    show(session, page, kind);
    const still = () => owner.current === session && session.flight === flight && isCurrent(session);
    try {
      const value = await read();
      if (!still()) { retired?.(value); return; }
      show(session, accepted(value));
    } catch {
      if (still()) show(session, failed());
    } finally { if (session.flight === flight) session.flight = undefined; }
  }

  const readCatalog = (shelf: Shelf) => {
    if (shelf.staging?.kind === 'review' || shelf.staging?.kind === 'exit') return;
    void inspect('catalog', () => session.visit.catalogLocal(), catalog => {
      if (!acceptedCatalog(scope, catalog)) throw Error();
      if (shelf.knownSaved && !catalog.versions.some(row => sameVersion(row, shelf.knownSaved!))) throw Error();
      if (catalog.recovery) {
        if (session.unconfirmed && (session.unconfirmed.reviewId !== catalog.recovery.reviewId ||
            session.unconfirmed.manifestIdentity !== catalog.recovery.manifestIdentity ||
            session.expected && !sameVersion(session.expected, catalog.recovery.expected))) throw Error();
        session.unconfirmed = catalog.recovery; session.expected = catalog.recovery.expected;
      }
      if (session.saveBlocked === 'pending' && !catalog.pending) session.saveBlocked = undefined;
      return { kind: 'shelf', catalog, restore: shelf.knownSaved?.checkpointId ?? shelf.restore,
        notice: session.unconfirmed ? '还有一次原保留需要核对。没有再次提交。' :
          shelf.staging?.kind === 'refresh' && !catalog.pending ? '现在未发现未完成暂存。' : undefined };
    }, () => ({ ...shelf, notice: '暂未核对到这份副本的版本记录。没有改读其他副本。' }));
  };

  const canCheckPending = (shelf: Shelf) => {
    try { return !!shelf.catalog?.pending && !shelf.staging && !session.unconfirmed &&
      session.visit.pending?.canReview(shelf.catalog) === true; } catch { return false; }
  };
  const beginPending = (shelf: Shelf) => {
    const pending = session.visit.pending, catalog = shelf.catalog;
    if (!pending || !catalog || !canCheckPending(shelf)) return;
    void inspect('pendingReview', () => pending.review(catalog), offer => {
      if (offer === null) {
        pendingFocus.current = { session, target: 'catalog' };
        return { ...shelf, staging: { kind: 'refresh' }, notice: '未核对到可清除的暂存。可以核对本机版本列表。' };
      }
      if (offer?.scope !== scope || !uuid(offer.id) || !Number.isFinite(offer.deadline)) throw Error();
      return { ...shelf, staging: { kind: 'review', offer }, notice: undefined };
    }, () => ({ ...shelf, staging: { kind: 'exit' }, notice: '未确认这次暂存核对。请返回原文件。这次没有提交清除。' }));
  };
  function settlePending(shelf: Shelf, clear: boolean) {
    const pending = session.visit.pending, state = shelf.staging;
    if (!pending || state?.kind !== 'review' || session.unconfirmed) return;
    const { offer } = state;
    if (clear && performance.now() >= offer.deadline) { setPendingExpired(true); return; }
    if (!clear) {
      void inspect('pendingDismiss', () => pending.dismiss(offer), () => {
        pendingFocus.current = { session, target: 'pending' };
        return { ...shelf, staging: undefined, notice: '没有清除暂存。' };
      }, () => ({ ...shelf, staging: { kind: 'exit' }, notice: '尚未确认退出核对。请返回原文件。这次没有提交清除。' }));
      return;
    }
    const needList = (notice: string): Shelf => {
      pendingFocus.current = { session, target: 'catalog' };
      return { ...shelf, staging: { kind: 'refresh', retired: true }, notice };
    };
    const unknown = () => needList('清除结果未确认。请核对本机版本列表，不会重复清除。');
    void inspect('pendingClear', () => pending.clear(offer), status => status === 'CLEARED' ?
      needList('已清除这次未完成的暂存。已保留版本和当前文件没有改变。') : status === 'NOT_CLEARED' ?
      needList('没有清除这次暂存。可以核对本机版本列表。') : unknown(), unknown);
  }

  const mayReview = () => {
    try { return !session.unconfirmed && !session.saveBlocked && session.visit.canReviewSavedSource() === true; } catch { return false; }
  };
  const reviewHere = (shelf: Shelf) => {
    if (shelf.catalog?.pending || shelf.staging || !mayReview()) return;
    void inspect('review', () => session.visit.reviewSavedSource(), review => {
      if (!acceptedReview(scope, review)) { release(session, review); throw Error(); }
      session.stage = review;
      return { kind: 'review', shelf, review };
    }, () => ({ ...shelf, notice: '未能核对当前已保存源码。请完成正在编辑或运行的工作后，再从原副本核对。' }),
    review => release(session, review));
  };

  const keep = (layer: Extract<Page, { kind: 'review' }>) => {
    if (session.stage !== layer.review || session.flight || !isCurrent(session)) return;
    session.stage = undefined; // Do not offer this one-use review again, including on a missing ACK.
    const pin = { reviewId: layer.review.id, manifestIdentity: layer.review.manifestIdentity };
    const unknown = (): Shelf => { session.unconfirmed = pin; return { kind: 'shelf', notice: uncertain }; };
    void inspect('save', async (): Promise<{ result: Save; catalog?: Catalog; checked?: true }> => {
      const result = await session.visit.saveReviewedSource(layer.review);
      let catalog: Catalog | undefined;
      // The explicit Save covers one local verification of this exact positive
      // receipt. Never run this chain on unknown or on a different Files visit.
      const still = () => owner.current === session && isCurrent(session) && session.flight?.kind === 'save';
      if (result?.scope === scope && result.status === 'SAVED' && savedVersion(scope, layer.review, result.version) && still()) {
        const expected = result.version;
        try {
          const observed = await session.visit.catalogLocal();
          if (!still() || !acceptedCatalog(scope, observed) || !observed.recovery || !sameVersion(expected, observed.recovery.expected))
            return { result };
          const row = observed.versions.find(row => sameVersion(expected, row));
          if (!row) return { result };
          catalog = observed;
          const verified = await session.visit.readSaved(observed, row);
          if (still() && versionHere(scope, verified) && sameVersion(expected, verified)) return { result, catalog, checked: true };
        } catch { /* The original acknowledged Save remains; only explicit same-row verification is available. */ }
      }
      return { result, catalog };
    }, ({ result, catalog, checked }) => {
      if (result?.scope !== scope) return unknown();
      if (result.status === 'SAVED' && savedVersion(scope, layer.review, result.version)) {
        if (checked && catalog) return { kind: 'shelf', catalog, restore: result.version.checkpointId,
          notice: '已在本机保留并核对这版源码。原副本没有改变。' };
        session.unconfirmed = pin; session.expected = result.version;
        return { kind: 'shelf', catalog, knownSaved: result.version, notice: '已在本机保留这版源码。还需核对原版本，不会再次保存。' };
      }
      if (result.version !== null) return unknown();
      switch (result.status) {
        case 'CAPACITY': session.saveBlocked = 'capacity'; return { ...layer.shelf, notice: '这份副本的保留空间不足。已有版本和当前文件不变。' };
        case 'PENDING': session.saveBlocked = 'pending'; return { ...layer.shelf, catalog: undefined, notice: '这份副本已有一次保留待核对，没有另存或替换已有版本。' };
        case 'NOT_SAVED': return { ...layer.shelf, notice: '这次没有保留新版本。当前文件和已保留版本不变。' };
        default: return unknown();
      }
    }, unknown);
  };
  const catalogRetired = page.kind === 'shelf' && page.staging?.kind === 'refresh' && page.staging.retired === true;
  const stagingHeld = page.kind === 'shelf' && (page.staging?.kind === 'review' || page.staging?.kind === 'exit');
  const originalRecovery = page.kind === 'shelf' && !stagingHeld && !catalogRetired && page.catalog && session.unconfirmed ? page.catalog.versions.find(row =>
    row.checkpointId === session.unconfirmed!.reviewId && row.manifestIdentity === session.unconfirmed!.manifestIdentity &&
    (!session.expected || sameVersion(row, session.expected))) : undefined;
  const readOriginal = (shelf: Shelf, version: Version) => {
    const catalog = shelf.catalog;
    if (!catalog || originalRecovery !== version) return;
    void inspect('read', () => session.visit.readSaved(catalog, version), received => {
      if (!versionHere(scope, received) || !sameVersion(received, version)) throw Error();
      session.unconfirmed = undefined; session.expected = undefined;
      return { ...shelf, knownSaved: undefined, restore: version.checkpointId, notice: '已核对这次原保留。没有再次保存。' };
    }, () => ({ ...shelf, notice: '这次仍未核对到原版本。没有改开其他版本或再次保存。' }));
  };

  const compare = (pick: Pick) => {
    const { before, after, catalog } = pick;
    if (!before || before.checkpointId === after.checkpointId || !catalog.versions.includes(before) ||
        !catalog.versions.includes(after)) return;
    void inspect('compare', () => session.visit.compareSaved(catalog, before, after), result => {
      return { kind: 'comparison', pick, comparison: checkedCheckpointComparison(scope, before, after, result) };
    }, () => ({ ...pick, notice: '没有核对到所选两版的原记录。没有改用最新版本或当前文件。' }));
  };

  const label = page.kind === 'review' ? '核对当前已保存源码' : page.kind === 'pick' ? '选择对照版' :
    page.kind === 'comparison' ? '两版源码的收录差异' : '源码版本';
  const canReview = page.kind === 'shelf' && !page.staging && !page.knownSaved && !page.catalog?.pending &&
    (page.catalog?.versions.length ?? 0) < MAX_VERSIONS && (page.catalog?.storedBytes ?? 0) < MAX_STORED && mayReview();
  return <div className="pw-form-flow" aria-busy={!!busy}>
    <h3 ref={heading} tabIndex={-1}>{label}</h3>
    <p className="pw-field-hint" dir="auto">{scope.projectName} · 当前私人副本</p>
    {page.kind === 'shelf' && <>
      <p>只保留这份副本已保存的源码。不替换当前文件，也不保留工具环境或执行权限。</p>
      {page.notice && <p role="status">{page.notice}</p>}
      {session.unconfirmed && !page.notice && <p role="status">原保留需要核对，请查看同一副本的本机记录。不会再次保存。</p>}
      {!canReview && !busy && !page.notice && !page.knownSaved && !page.catalog?.pending && !session.unconfirmed &&
        <p className="pw-field-hint">当前不能保留新版本。仍可查看已有记录。</p>}
      {page.knownSaved && <p>{versionName(page.knownSaved)} · {page.knownSaved.fileCount} 个收录文件</p>}
      {page.catalog && <>
        {page.catalog.pending && !page.staging && !session.unconfirmed && <>
          <p role="status">有未完成的暂存待核对。已有版本仍可查看，不会继续保存。</p>
          {canCheckPending(page) && <Button ref={pendingButton} size="small" variant="ghost" disabled={!!busy}
            onClick={() => beginPending(page)}>核对未完成暂存</Button>}
        </>}
        {pendingOffer && busy !== 'pendingClear' && <section className="pw-form-flow" aria-label="核对未完成暂存">
          <h4 ref={pendingHeading} tabIndex={-1}>清除这次暂存？</h4>
          <p>只清除这次未完成的暂存。当前源码和已保留版本不会改变，也不会重新保存。</p>
          {pendingExpired && <p role="status">这次核对已到期。请先退出，没有提交清除。</p>}
          <div className="pw-form-actions">
            <Button variant="secondary" disabled={!!busy} onClick={() => settlePending(page, false)}>先不清除</Button>
            {!pendingExpired && <Button disabled={!!busy} onClick={() => settlePending(page, true)}>仅清除未完成暂存</Button>}
          </div>
        </section>}
        {catalogRetired && <p className="pw-field-hint">下方是上次核对的版本。请先核对本机版本列表，再打开这些版本。</p>}
        {page.catalog.versions.length === 0 ? <p>没有核对到这份副本已登记的版本。</p> :
          <ul className="pw-form-flow" aria-label="这份副本的本机版本">{page.catalog.versions.map(version => <li key={version.checkpointId}>
            <Button ref={page.restore === version.checkpointId ? rowFocus : undefined} variant="secondary" disabled={!!busy || stagingHeld || catalogRetired}
              onClick={() => { if (!session.flight && isCurrent(session) && !stagingHeld && !catalogRetired && page.catalog) show(session,
                { kind: 'pick', shelf: page, catalog: page.catalog, after: version }); }}>
              {versionName(version, page.catalog)}
            </Button>
            <p className="pw-field-hint">{version.fileCount} 个收录文件 · {version.omittedCount} 项排除记录</p>
          </li>)}</ul>}
        <p className="pw-field-hint">每份副本最多保留 {MAX_VERSIONS} 版、合计 {MAX_STORED / MIB} MiB，不自动替换。</p>
      </>}
      <div className="pw-form-actions">
        {canReview && <Button disabled={!!busy} onClick={() => reviewHere(page)}>核对当前已保存源码</Button>}
        {originalRecovery && <Button disabled={!!busy} onClick={() => readOriginal(page, originalRecovery)}>核对这次原保留</Button>}
        {!stagingHeld && <Button ref={catalogButton} variant={canReview || originalRecovery ? 'ghost' : 'primary'} disabled={!!busy}
          onClick={() => readCatalog(page)}>
          {page.catalog ? '核对本机版本列表' : '查看已保留版本'}
        </Button>}
        {onExit && (page.staging?.kind !== 'review' || busy === 'pendingClear') && <Button variant="ghost" onClick={onExit}>返回原文件</Button>}
      </div>
    </>}
    {page.kind === 'review' && <>
      {busy === 'save' ? <p role="status">正在核对这次保留。现在返回不代表撤销已经发生的保存。</p> : <>
        {page.leaveUnknown && <p role="status">尚未确认原核对已退出。请关闭源码版本返回原文件，不会再次保存。</p>}
        <p>收录 {page.review.files.length} 个已保存文件，{page.review.directories.length} 个目录；排除 {page.review.omitted.length} 项。</p>
        <p className="pw-field-hint">只保留下面这份固定清单，不带未保存草稿、依赖环境、构建产物或凭据。被排除的整个目录只记一项，不读取其内容。</p>
        <Accordion key={page.review.id} variant="divided">
          <AccordionItem id="checkpoint-included" title="收录文件与目录">
            <PathRows rows={page.review.files} path={row => row.path} render={row => <small>{row.bytes.toLocaleString()} 字节</small>} empty="没有收录文件。" />
            {page.review.directories.length > 0 && <><h4>收录目录</h4><PathRows rows={page.review.directories} path={path => path}
              render={() => null} empty="没有收录目录。" /></>}
          </AccordionItem>
          <AccordionItem id="checkpoint-omitted" title="排除项与原因">
            <PathRows rows={page.review.omitted} path={row => row.path} render={row => <small>{reasons[row.kind]}</small>}
              empty="本次没有具体的排除项。" />
          </AccordionItem>
        </Accordion>
        {!page.leaveUnknown && <div className="pw-form-actions"><Button disabled={!!busy} onClick={() => keep(page)}>保留这版源码</Button></div>}
      </>}
    </>}
    {page.kind === 'pick' && <>
      <p>所选版：{versionName(page.after, page.catalog)}</p>
      {page.catalog.versions.length >= 2 ? <>
        <SelectField label="对照版" value={page.before?.checkpointId ?? ''} placeholder="选择另一份已保留版本"
          isDisabled={!!busy} options={page.catalog.versions.filter(row => row !== page.after).map(row =>
            ({ id: row.checkpointId, label: versionName(row, page.catalog) }))}
          onChange={id => { if (!session.flight) show(session, { ...page,
            before: page.catalog.versions.find(row => row !== page.after && row.checkpointId === id), notice: undefined }); }} />
        {page.notice && <p role="status">{page.notice}</p>}
        <div className="pw-form-actions"><Button disabled={!page.before || !!busy} onClick={() => compare(page)}>比较两版</Button></div>
      </> : <p className="pw-field-hint">目前只有一版，暂无对照版。</p>}
    </>}
    {page.kind === 'comparison' && <ProjectSourceCheckpointContinue authority={session.visit.continuation} scope={scope}
      catalog={page.pick.catalog} comparison={page.comparison} versionLabel={versionName(page.comparison.after, page.pick.catalog)}
      memory={session.continuations} registerBack={registerContinuationBack}
      comparisonBack={<Button variant="ghost" onClick={back}>返回两版选择</Button>}>
      <ComparisonRows comparison={page.comparison} catalog={page.pick.catalog} />
    </ProjectSourceCheckpointContinue>}
    {!!busy && busy !== 'save' && <p role="status">{({catalog:'正在核对本机版本…',review:'正在核对这份副本的已保存源码…',
      dismiss:'正在结束这次原核对…',read:'正在核对本机原记录…',compare:'正在核对选中的两版…',
      pendingReview:'正在核对未完成暂存…',pendingDismiss:'正在退出暂存核对…',
      pendingClear:'正在核对这次清除。返回不代表撤销已发生的清除。'})[busy]}</p>}
    {page.kind !== 'shelf' && page.kind !== 'comparison' && busy !== 'save' &&
      !(page.kind === 'review' && page.leaveUnknown) && <div className="pw-form-actions">
      <Button variant="ghost" onClick={back}>返回版本列表</Button>
    </div>}
  </div>;
}

function ownerStage(session: Session) {
  const stage = session.stage;
  session.stage = undefined;
  session.flight = undefined;
  return stage;
}
