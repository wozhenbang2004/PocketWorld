import { useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { Button } from './forms-library';
import type { Catalog, Comparison, Scope, Version } from './ProjectSourceCheckpointScreen';

export type Target = Readonly<{
  projectId: string; originatingCheckoutId: string; checkpointId: string; checkoutId: string; name: string;
  manifestIdentity: string; archiveSha256: string; filteredSourceDigest: string;
}>;
export type Observation = Readonly<{ scope: Scope; state: 'NOT_OBSERVED' | 'UNCONFIRMED' | 'CAPACITY' | 'PUBLISHED'; target: Target }>;
export type Published = Observation & Readonly<{ state: 'PUBLISHED' }>;
export type Review = Readonly<{ scope: Scope; id: string; selected: Version; target: Target }>;
export type Offer = Readonly<{ observed: Observation; review: Review | null }>;

/** Only future native admission can supply this adapter. It owns the same actual
 * native comparison/catalog and selected after-version, and maps the offered
 * objects back to their exact native one-use Review and Observation. No wire
 * format, derived target ID, current-checkout selection, or execution authority.
 * Opening is separately optional: the existing native private Files path must
 * revalidate the published sibling, retire the source Files visit as needed, and
 * check authority through publication without persisting a new draft selection.
 */
export type SourceCheckpointContinuation = Readonly<{
  scope: Scope;
  comparisonId: (catalog: Catalog, comparison: Comparison) => string | undefined;
  review: (catalog: Catalog, comparison: Comparison) => Promise<Offer>;
  discard: (review: Review) => Promise<void>;
  confirm: (review: Review) => Promise<Observation>;
  recheck: (catalog: Catalog, comparison: Comparison, target: Target) => Promise<Observation>;
  canRecheck: (catalog: Catalog, comparison: Comparison, target: Target) => boolean;
  files?: Readonly<{
    canOpen: (scope: Scope, published: Published) => boolean;
    open: (scope: Scope, published: Published) => Promise<void>;
  }>;
}>;

type Attempt = { comparisonId: string; before: Version; selected: Version; target: Target; observed?: Observation; rechecked?: true };
/** Body-free attempt facts across comparisons in this one original Files visit. */
export type SourceCheckpointContinuationMemory = Map<string, Attempt>;
type Selection = { authority: SourceCheckpointContinuation; scope: Scope; catalog: Catalog; comparison: Comparison };
type Binding = Selection & { comparisonId: string };
type Layer = { kind: 'check' } | { kind: 'review'; review: Review } | { kind: 'result'; attempt: Attempt };
type Action = 'review' | 'confirm' | 'recheck' | 'dismiss' | 'files';
type Panel = { binding: Binding; layer?: Layer; error?: string; busy?: Action };

const uuid = (id: unknown) => typeof id === 'string' && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(id);
function sameVersion(a: Version, b: Version | undefined) {
  return !!b && a.projectId === b.projectId && a.checkoutId === b.checkoutId && a.checkpointId === b.checkpointId &&
    a.storageSlot === b.storageSlot && a.manifestIdentity === b.manifestIdentity && a.filteredSourceDigest === b.filteredSourceDigest &&
    a.archiveSha256 === b.archiveSha256 && a.archiveBytes === b.archiveBytes && a.capturedAt === b.capturedAt &&
    a.fileCount === b.fileCount && a.omittedCount === b.omittedCount && a.expandedBytes === b.expandedBytes;
}
export function targetFor(selected: Version, target: Target | undefined): target is Target {
  return !!target && target.projectId === selected.projectId && target.originatingCheckoutId === selected.checkoutId &&
    target.checkpointId === selected.checkpointId && uuid(target.checkoutId) && target.checkoutId !== selected.checkoutId &&
    target.manifestIdentity === selected.manifestIdentity && target.archiveSha256 === selected.archiveSha256 &&
    target.filteredSourceDigest === selected.filteredSourceDigest && typeof target.name === 'string' && !!target.name.trim() && target.name.length <= 80;
}
function observedHere(binding: Binding, value: Observation | undefined, expected?: Target): value is Observation {
  return !!value && value.scope === binding.scope && targetFor(binding.comparison.after, value.target) &&
    (!expected || value.target.checkoutId === expected.checkoutId && value.target.name === expected.name) &&
    ['NOT_OBSERVED', 'UNCONFIRMED', 'CAPACITY', 'PUBLISHED'].includes(value.state);
}
function nativeComparison({ authority, scope, catalog, comparison }: Selection) {
  if (document.hidden || authority.scope !== scope || catalog.scope !== scope || comparison.scope !== scope ||
      !uuid(scope.projectId) || comparison.after.projectId !== scope.projectId || comparison.after.checkoutId !== scope.checkoutId ||
      comparison.before.checkpointId === comparison.after.checkpointId ||
      !catalog.versions.some(row => sameVersion(comparison.before, row)) || !catalog.versions.some(row => sameVersion(comparison.after, row))) return;
  try {
    const id = authority.comparisonId(catalog, comparison);
    return uuid(id) ? id : undefined;
  } catch { return; }
}
const current = (binding: Binding) => nativeComparison(binding) === binding.comparisonId;
function release(binding: Binding, review: Review | null | undefined) {
  if (!review || review.scope !== binding.scope || !uuid(review.id) || !sameVersion(binding.comparison.after, review.selected)) return;
  void binding.authority.discard(review).catch(() => {}); // Native expiry/retirement remains final.
}
function published(value: Observation | undefined): value is Published { return value?.state === 'PUBLISHED'; }

export function ProjectSourceCheckpointContinue({ authority, scope, catalog, comparison, versionLabel, memory, registerBack, comparisonBack, children }: {
  authority?: SourceCheckpointContinuation; scope: Scope; catalog: Catalog; comparison: Comparison;
  versionLabel: string; memory: SourceCheckpointContinuationMemory; registerBack: (go: (() => boolean) | null) => void;
  comparisonBack: ReactNode; children: ReactNode;
}) {
  const [panel, setPanel] = useState<Panel>();
  const owner = useRef<Binding | undefined>(undefined);
  const held = useRef<Review | undefined>(undefined);
  const flight = useRef<{ kind: Action } | undefined>(undefined);
  const title = useRef<HTMLHeadingElement>(null), action = useRef<HTMLButtonElement>(null), returnFocus = useRef(false);
  useLayoutEffect(() => {
    if (!authority) { setPanel(undefined); return; }
    const selection = { authority, scope, catalog, comparison }, comparisonId = nativeComparison(selection);
    if (!comparisonId) { setPanel(undefined); return; }
    const binding = { ...selection, comparisonId };
    owner.current = binding; setPanel({ binding });
    return () => {
      if (owner.current !== binding) return;
      owner.current = undefined; flight.current = undefined;
      const old = held.current; held.current = undefined; release(binding, old);
    };
  }, [authority, scope, catalog, comparison]);

  const here = panel && owner.current === panel.binding && panel.binding.authority === authority &&
    panel.binding.catalog === catalog && panel.binding.comparison === comparison && current(panel.binding) ? panel : undefined;
  const layer = here?.layer;

  function back() {
    if (!here || !layer) return false;
    // Once affirmative creation or the separate exact file open entered, do not
    // make Back impersonate cancellation. Native visit retirement still fences it.
    if (flight.current) return true;
    const old = held.current; held.current = undefined;
    if (!old) { returnFocus.current = true; setPanel({ binding: here.binding }); return true; }
    const leaving = { kind: 'dismiss' } as const; flight.current = leaving;
    setPanel({ ...here, busy: 'dismiss' });
    const belongs = () => owner.current === here.binding && current(here.binding) && flight.current === leaving;
    void here.binding.authority.discard(old).then(() => {
      if (belongs()) { returnFocus.current = true; setPanel({ binding: here.binding }); }
    }, () => {
      if (belongs()) { returnFocus.current = true; setPanel({ binding: here.binding, error: '尚未确认退出原核对。没有提交创建。' }); }
    }).finally(() => { if (flight.current === leaving) flight.current = undefined; });
    return true;
  }
  useLayoutEffect(() => {
    if (!here || !layer) return;
    registerBack(back);
    return () => registerBack(null);
  }, [here, layer, registerBack]);
  useLayoutEffect(() => {
    const target = layer ? title.current : returnFocus.current ? action.current : null;
    if (target?.isConnected) {
      target.focus({ preventScroll: true });
      if (!layer) target.scrollIntoView({ block: 'nearest' });
    }
    if (!layer) returnFocus.current = false;
  }, [layer?.kind, here?.busy]);

  const original = <>{children}<div className="pw-form-actions">{comparisonBack}</div></>;
  if (!here) return original;
  const binding = here.binding, selected = comparison.after;
  const sameVisit = () => owner.current === binding && current(binding);
  const prior = memory.get(selected.checkpointId);
  if (prior && (!sameVersion(selected, prior.selected) || !sameVersion(comparison.before, prior.before))) return original;
  const record = prior?.comparisonId === binding.comparisonId ? prior : undefined;
  const result = layer?.kind === 'result' ? layer.attempt : undefined;
  const receipt = published(result?.observed) && observedHere(binding, result.observed, result.target) ? result.observed : undefined;
  const files = binding.authority.files;
  const fileReady = () => {
    if (!files || !receipt || !sameVisit()) return false;
    try { return files.canOpen(scope, receipt) === true; } catch { return false; }
  };

  async function run<T>(kind: Action, active: Layer, work: () => Promise<T>,
                        accept: (value: T) => Layer, failure: string, releaseLate?: (value: T) => void) {
    if (!sameVisit() || flight.current) return;
    const call = { kind }; flight.current = call;
    setPanel({ binding, layer: active, busy: kind });
    const still = () => flight.current === call && sameVisit();
    try {
      const value = await work();
      if (!still()) { releaseLate?.(value); return; }
      setPanel({ binding, layer: accept(value) });
    } catch {
      if (still()) setPanel({ binding, layer: kind === 'review' ? undefined : active, error: failure });
    } finally { if (flight.current === call) flight.current = undefined; }
  }

  function enter() {
    if (!sameVisit() || flight.current) return;
    if (record) { setPanel({ binding, layer: { kind: 'result', attempt: record } }); return; }
    void run('review', { kind: 'check' }, () => binding.authority.review(catalog, comparison), offer => {
      const { observed, review } = offer;
      if (!observedHere(binding, observed, prior?.target) ||
          (observed.state === 'NOT_OBSERVED' ? !review || review.scope !== scope || !uuid(review.id) ||
            !sameVersion(selected, review.selected) || !targetFor(selected, review.target) ||
            review.target.checkoutId !== observed.target.checkoutId || review.target.name !== observed.target.name : review !== null)) {
        release(binding, review); throw Error();
      }
      if (review) {
        // A prior affirmative/result can only be re-verified, even if this
        // fresh comparison cannot find its previous native registration.
        if (prior) { release(binding, review); throw Error(); }
        held.current = review; return { kind: 'review', review };
      }
      const attempt = { comparisonId: binding.comparisonId, before: comparison.before, selected, target: observed.target, observed };
      memory.set(selected.checkpointId, attempt); return { kind: 'result', attempt };
    }, prior ? '没有核对到此前同一对版本的副本登记。没有重新创建。' :
      '没有核对到所选版和原副本。本次没有另建副本。', offer => release(binding, offer?.review));
  }

  function confirm(review: Review) {
    if (!sameVisit() || flight.current || held.current !== review || memory.has(selected.checkpointId)) return;
    held.current = undefined; // One actual affirmative request, never remint or replay its review.
    const attempt: Attempt = { comparisonId: binding.comparisonId, before: comparison.before, selected, target: review.target };
    memory.set(selected.checkpointId, attempt);
    const next = { kind: 'result', attempt } as const;
    void run('confirm', next, () => binding.authority.confirm(review), observed => {
      if (!observedHere(binding, observed, attempt.target)) throw Error();
      attempt.observed = observed; return next;
    }, '');
  }

  function recheck(attempt: Attempt) {
    if (!sameVisit() || flight.current || attempt.rechecked || !binding.authority.canRecheck(catalog, comparison, attempt.target) ||
        attempt.observed && attempt.observed.state !== 'UNCONFIRMED') return;
    attempt.rechecked = true;
    const next = { kind: 'result', attempt } as const;
    void run('recheck', next, () => binding.authority.recheck(catalog, comparison, attempt.target), observed => {
      if (!observedHere(binding, observed, attempt.target)) throw Error();
      attempt.observed = observed; return next;
    }, '这次仍无法核对原登记。没有再次创建。');
  }

  function openFiles() {
    if (!files || !receipt || !result || !fileReady()) return;
    const next = { kind: 'result', attempt: result } as const;
    // The parent adopts the verified new Files ACK. The original comparison
    // must not still be current after that native swap.
    void run('files', next, () => files.open(scope, receipt), () => next,
      '没有确认打开这份新副本的文件。不会改开其他副本。');
  }

  const state = result?.observed?.state;
  const mayRecheck = !!result && !result.rechecked && (!state || state === 'UNCONFIRMED') &&
    binding.authority.canRecheck(catalog, comparison, result.target);
  const opening = here.busy === 'confirm' || here.busy === 'files';
  const canOpenFiles = !!receipt && fileReady();
  return <>
    <div hidden={!!layer} inert={!!layer}>
      {children}
      {!layer && <div className="pw-form-actions">
        {comparisonBack}
        <Button ref={action} onClick={enter}>{record ? published(record.observed) ? '查看已登记的副本' : '查看这次副本核对' :
          prior ? '核对原版本的副本登记' :
          `从 ${versionLabel} 这版另建副本…`}</Button>
      </div>}
      {!layer && here.error && <p role="status">{here.error}</p>}
    </div>
    {layer && <section className="pw-form-flow" aria-busy={!!here.busy}>
      <h4 ref={title} tabIndex={-1}>{layer.kind === 'check' ? '核对所选版' : layer.kind === 'review' ? '另建私人副本？' : '这版的副本登记'}</h4>
      <p>所选版：{versionLabel}</p>
      {layer.kind === 'check' && <p role="status">正在核对这版的原记录，没有创建副本。</p>}
      {layer.kind === 'review' && <>
        <p>只复制这版收录的源码到同一项目的新私人副本。原副本仍选中，草稿不变，不会构建或运行。</p>
        <div className="pw-form-actions"><Button disabled={!!here.busy} onClick={() => confirm(layer.review)}>确认另建副本</Button></div>
      </>}
      {result && <>
        {here.busy === 'confirm' ? <p role="status">正在核对新副本登记。不会再次提交。</p> :
          state === 'PUBLISHED' ? <p role="status">已核对这版另建的私人副本登记。原副本和草稿保持原样；此处不判断新副本后来是否被编辑。</p> :
          state === 'CAPACITY' ? <p role="status">私人副本数量已达上限。这次没有另建副本。</p> :
          state === 'NOT_OBSERVED' ? <p role="status">没有核对到这次的新副本登记。不会再次创建。</p> :
          <p role="status">这份新副本是否已登记尚未确认。不会再次创建。</p>}
        {here.busy === 'recheck' && <p role="status">正在核对这次本机原登记…</p>}
        {here.error && <p role="status">{here.error}</p>}
        <div className="pw-form-actions">
          {(canOpenFiles || here.busy === 'files') && <Button disabled={!!here.busy} onClick={openFiles}>{here.busy === 'files' ? '正在打开这份新副本…' : '打开新副本文件'}</Button>}
          {receipt && !canOpenFiles && here.busy !== 'files' && <p className="pw-field-hint">本窗口暂不能打开这份新副本的文件。</p>}
          {mayRecheck && !opening && <Button disabled={!!here.busy} onClick={() => recheck(result)}>核对这次原登记</Button>}
        </div>
      </>}
      {!opening && <div className="pw-form-actions"><Button variant="ghost" disabled={!!here.busy} onClick={back}>返回这两版比较</Button></div>}
    </section>}
  </>;
}
