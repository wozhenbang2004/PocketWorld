import { useCallback, useLayoutEffect, useRef, useState } from 'react';
import { bridge, type PocketSnapshot } from './bridge';
import { checkedCheckpointComparison, recoveryHere, sameVersion, type Catalog, type Comparison, type PendingCheckpointReview,
  type ProjectSourceCheckpointVisit, type Recovery, type Review, type Save, type Scope, type Version } from './ProjectSourceCheckpointScreen';
import { comparisonBase, selectedVersion, singleSelection, targetFor, type CheckpointContinuationSource,
  type Observation, type Published, type Review as ContinueReview, type SourceCheckpointContinuation, type Target }
  from './ProjectSourceCheckpointContinue';
import { checkedSiblingFiles, checkpointContinuePrefix, hasCheckpointContinue, type CheckpointContinueAction,
  type CheckpointSiblingFiles, type ReturnedCheckpointFiles } from './project-source-checkpoint-continue-files';

const prefix = 'projects.development.files.checkpoints.';
const actions = ['open', 'list', 'review', 'save', 'dismiss', 'read', 'compare', 'close'] as const;
const pendingActions = ['pending.review', 'pending.clear', 'pending.dismiss'] as const;
type Action = typeof actions[number] | typeof pendingActions[number];
const PENDING_REVIEW_MS = 120_000, MAX_PENDING_BYTES = 20 * 1024 * 1024 + 2048;
type Data = Record<string, unknown>;
export type CheckpointFilesOrigin = {
  projectId: string; checkoutId: string; viewHandle: string; filesHandle: string; current: () => boolean;
  handoff?: Readonly<{
    clean: () => boolean;
    adopt: (arrival: CheckpointSiblingFiles, before: () => void) => boolean;
    uncertain: () => void;
  }>;
};
type Origin = { viewHandle: string; filesHandle: string; visitId: string };
type Pin = Origin & { shelfHandle: string; scope: Scope };
type Visit = {
  source: CheckpointFilesOrigin; origin: Origin; document: number; resume: number;
  projectRevision: number; taskRevision: number; connectionRevision: number; trigger: () => HTMLButtonElement | null;
  scroll: number; parent: HTMLElement | null; listeners: Set<() => void>;
  pin?: Pin; recovery?: Recovery | null; binding?: ProjectSourceCheckpointVisit;
  notice?: string; wait?: ReturnType<typeof setTimeout>; closing?: Promise<boolean>; closed?: true;
  opening?: Promise<void>; admissionSeen?: true; openRefused?: true;
  entered?: true; revoked?: true; exiting?: true;
  moving?: 'opening' | 'unknown';
  restored?: { comparison: Comparison | null };
};

const data = (v: unknown): Data => v && typeof v === 'object' && !Array.isArray(v) ? v as Data : {};
const uuid = (v: unknown): v is string => typeof v === 'string' && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(v);
const hash = (v: unknown): v is string => typeof v === 'string' && /^[a-f0-9]{64}$/.test(v);
const clock = (v: unknown): v is number => typeof v === 'number' && Number.isSafeInteger(v) && v >= 0;
const supports = (snapshot: PocketSnapshot) => actions.every(action => snapshot.capabilities.commands.includes(prefix + action));
const supportsPending = (snapshot = bridge.getState()) => pendingActions.every(action => snapshot.capabilities.commands.includes(prefix + action));
const available = (snapshot = bridge.getState()) => supports(snapshot) && snapshot.state.sourceCheckpointVisit === null;
const sameOrigin = (o: Origin, v: Data) => v.viewHandle === o.viewHandle && v.filesHandle === o.filesHandle && v.visitId === o.visitId;
const sameScope = (scope: Scope, v: unknown) => {
  const got = data(v);
  return got.projectId === scope.projectId && got.checkoutId === scope.checkoutId && got.projectName === scope.projectName;
};
const samePin = (pin: Pin, v: unknown) => {
  const got = data(v);
  return sameOrigin(pin, got) && got.shelfHandle === pin.shelfHandle && sameScope(pin.scope, got.scope);
};
function revisionMatches(v: Visit, snapshot: PocketSnapshot) {
  const view = data(snapshot.view), s = snapshot.state;
  return bridge.getDocumentEpoch() === v.document && !document.hidden && supports(snapshot) &&
    view.section === null && view.taskId === null && view.resumeRevision === v.resume &&
    s.projectId === v.source.projectId && s.filePath == null && s.requestedProjectRevision === v.projectRevision &&
    s.requestedTaskRevision === v.taskRevision && s.requestedConnectionRevision === v.connectionRevision;
}
function parseRecovery(scope: Scope, raw: unknown): Recovery | null {
  if (raw === null) return null;
  const candidate = raw as Recovery | undefined;
  if (!recoveryHere(scope, candidate)) throw Error();
  return candidate;
}
const versionPin = (v: Version) => ({ checkpointId: v.checkpointId, manifestIdentity: v.manifestIdentity, archiveSha256: v.archiveSha256 });
const reviewPin = (r: Review) => ({ reviewId: r.id, manifestIdentity: r.manifestIdentity });

/** The original checkout keeps its existing native Files page and exact editor
 * while this second *mode*, not a second dialog, is visible. No soft Back/Close
 * can expose its ordinary controls until this native checkpoint visit retires.
 */
export function useSourceCheckpointFiles() {
  const active = useRef<Visit | undefined>(undefined), mounted = useRef(false);
  const innerBack = useRef<(() => boolean) | null>(null);
  const refusedFiles = useRef<string | undefined>(undefined);
  const advertised = useRef(available()), lostDocument = useRef(false);
  const registerBack = useCallback((back: (() => boolean) | null) => { innerBack.current = back; }, []);
  const [, render] = useState(0);
  const redraw = () => { if (mounted.current) render(n => n + 1); };
  const stopTimer = (v: Visit) => { if (v.wait !== undefined) clearTimeout(v.wait); v.wait = undefined; };
  const dead = (v: Visit) => {
    if (!v.binding) return;
    v.binding = undefined;
    for (const retire of [...v.listeners]) retire();
    v.listeners.clear();
  };
  const liveSource = (v: Visit) => active.current === v && revisionMatches(v, bridge.getState()) && v.source.current();
  const livePin = (v: Visit, pin: Pin) => liveSource(v) && !!v.binding && !v.revoked && !v.exiting &&
    samePin(pin, bridge.getState().state.sourceCheckpointVisit);
  function finish(v: Visit, restore: boolean) {
    if (active.current !== v) return;
    stopTimer(v); dead(v); active.current = undefined; innerBack.current = null; redraw();
    if (!restore || !mounted.current) return;
    const previousFocus = document.activeElement, until = performance.now() + 500;
    const reveal = () => {
      if (active.current || !mounted.current || !revisionMatches(v, bridge.getState()) || !v.source.current()) return;
      const trigger = v.trigger();
      if (!trigger?.isConnected || trigger.disabled || trigger.closest('[hidden],[inert]')) {
        if (performance.now() < until) requestAnimationFrame(reveal);
        return;
      }
      if (document.activeElement !== document.body && document.activeElement !== previousFocus && document.activeElement !== trigger) return;
      if (v.parent?.isConnected) v.parent.scrollTop = v.scroll;
      trigger.focus({ preventScroll: true });
    };
    requestAnimationFrame(reveal);
  }
  function adapter(v: Visit, pin: Pin): ProjectSourceCheckpointVisit {
    const scope = pin.scope;
    const held = { ...v.origin, shelfHandle: pin.shelfHandle };
    let catalog: { id: string; value: Catalog } | undefined;
    let review: Review | undefined;
    let pendingReview: { catalogId: string; value: PendingCheckpointReview } | undefined;
    let consumed = false;
    let recovery = v.recovery;
    type Selected = { catalog: Catalog; catalogId: string; id: string; source: CheckpointContinuationSource };
    let admitted: Selected | undefined, selectionEpoch = 0;
    let continuationReview: { selection: Selected; offered: ContinueReview } | undefined;
    type Attempt = { id: string; target: Target; selection: Selected; checked?: true };
    let attempt: Attempt | undefined;
    let publication: { value: Observation; id: string; selection: Selected } | undefined;
    const current = () => livePin(v, pin);
    const clearSelection = () => {
      admitted = undefined; continuationReview = undefined; attempt = undefined; publication = undefined;
      return ++selectionEpoch;
    };
    const call = async (action: Exclude<Action, 'open' | 'close'>, extra: Data = {}) => {
      if (!current()) throw Error();
      clearSelection();
      const val = data(await bridge.command(prefix + action, { ...held, ...extra }));
      if (!current() || !samePin(pin, val)) throw Error();
      return val;
    };
    const shown = (value: Catalog, row: Version) => {
      if (!catalog || catalog.value !== value || pendingReview || !value.versions.includes(row)) throw Error();
      return catalog.id;
    };
    const pendingCurrent = () => current() && supportsPending() && !recovery && !review;
    const canReviewPending = (value: Catalog) => pendingCurrent() && !pendingReview && catalog?.value === value && value.pending === true;
    const settlePending = async (offer: PendingCheckpointReview, clear: boolean) => {
      const heldReview = pendingReview;
      if (!pendingCurrent() || !heldReview || heldReview.value !== offer || catalog?.id !== heldReview.catalogId) throw Error();
      pendingReview = undefined;
      if (clear) catalog = undefined; // Native consumes the Catalog even when Clear is unconfirmed.
      try {
        const val = await call(clear ? 'pending.clear' : 'pending.dismiss', { reviewId: offer.id, ...(clear ? { confirmed: true } : {}) });
        if (val.catalogId !== heldReview.catalogId || val.reviewId !== offer.id) throw Error();
        return val;
      } catch (failure) {
        catalog = undefined;
        throw failure;
      }
    };
    const sameTarget = (a: Target, b: Target) => a.checkoutId === b.checkoutId && a.name === b.name &&
      a.projectId === b.projectId && a.checkpointId === b.checkpointId && a.originatingCheckoutId === b.originatingCheckoutId &&
      a.manifestIdentity === b.manifestIdentity && a.archiveSha256 === b.archiveSha256 && a.filteredSourceDigest === b.filteredSourceDigest;
    const handoff = v.source.handoff;
    const mayContinue = () => current() && hasCheckpointContinue() && handoff?.clean() === true && !review && !recovery && !pendingReview;
    const selected = (list: Catalog, source: CheckpointContinuationSource) => mayContinue() && admitted && catalog && admitted.catalog === list &&
      admitted.source === source && catalog.id === admitted.catalogId && catalog.value === list ? admitted : undefined;
    const ticketField = (s: Selected) => singleSelection(s.source) ? 'selectionId' : 'comparisonId';
    const continuationWire = (s: Selected) => ({ ...held, catalogId: s.catalogId, [ticketField(s)]: s.id });
    const continueCall = async (s: Selected, action: Exclude<CheckpointContinueAction, 'openFiles' | 'returnFiles'>, extra: Data = {}) => {
      if (v.moving || selected(s.catalog, s.source) !== s) throw Error();
      const val = data(await bridge.command(checkpointContinuePrefix + action, { ...continuationWire(s), ...extra }));
      if (selected(s.catalog, s.source) !== s || !samePin(pin, val) || val.catalogId !== s.catalogId || val[ticketField(s)] !== s.id) throw Error();
      return val;
    };
    const observation = (s: Selected, raw: unknown, original?: Attempt) => {
      const row = data(raw), t = row.target as Target | undefined, state = row.state;
      if (!targetFor(selectedVersion(s.source), t) || original && (!sameTarget(original.target, t) || row.attemptId !== original.id) ||
          (state !== 'PUBLISHED' && state !== 'UNCONFIRMED' && state !== 'NOT_OBSERVED' && state !== 'CAPACITY') ||
          (row.attemptId !== null && !uuid(row.attemptId)) || (state === 'PUBLISHED' ? !uuid(row.publicationHandle) : row.publicationHandle !== null)) throw Error();
      const value: Observation = { scope, state, target: t };
      return { value, publicationId: row.publicationHandle as string | null, attemptId: row.attemptId as string | null };
    };
    const showObservation = (s: Selected, item: ReturnType<typeof observation>) => {
      publication = item.publicationId === null ? undefined : { value: item.value, id: item.publicationId, selection: s };
      return item.value;
    };
    const activeAttempt = (list: Catalog, source: CheckpointContinuationSource, target: Target) =>
      attempt && !attempt.checked && selected(list, source) === attempt.selection && sameTarget(attempt.target, target) ? attempt : undefined;
    const canOpenSibling = (place: Scope, observed: Published) => !!handoff && !v.moving && place === scope && !!publication &&
      publication.value === observed && selected(publication.selection.catalog, publication.selection.source) === publication.selection && handoff.clean();
    const continuation: SourceCheckpointContinuation | undefined = handoff && hasCheckpointContinue() ? {
      scope,
      selectionId: (list, source) => selected(list, source)?.id,
      async review(list, result) {
        const selection = selected(list, result);
        if (!selection || continuationReview) throw Error();
        publication = undefined;
        const reply = await continueCall(selection, 'review');
        const received = observation(selection, reply.observed), row = data(reply.review), chosen = row.selected as Version | undefined;
        if (received.value.state === 'NOT_OBSERVED') {
          const after = selectedVersion(selection.source), target = row.target as Target | undefined;
          if (received.attemptId !== null || !uuid(row.id) || !chosen || !sameVersion(after, chosen) ||
              !targetFor(after, target) || !sameTarget(received.value.target, target)) throw Error();
          const offered: ContinueReview = { scope, selected: after, id: row.id, target };
          continuationReview = { selection, offered };
          return { observed: received.value, review: offered };
        }
        if (reply.review !== null) throw Error();
        if (received.attemptId !== null) attempt = { id: received.attemptId, selection, target: received.value.target };
        return { observed: showObservation(selection, received), review: null };
      },
      async discard(offered) {
        if (continuationReview?.offered !== offered) throw Error();
        const { selection } = continuationReview;
        continuationReview = undefined;
        if ((await continueCall(selection, 'dismiss', { reviewId: offered.id })).dismissed !== true) throw Error();
      },
      async confirm(offered) {
        if (continuationReview?.offered !== offered) throw Error();
        const { selection } = continuationReview;
        continuationReview = undefined;
        const original = { id: offered.id, selection, target: offered.target };
        attempt = original; // This locator allows only a later exact native observation.
        try {
          return showObservation(selection, observation(selection, await continueCall(selection, 'confirm', { reviewId: offered.id, confirmed: true }), original));
        } catch (failure) {
          if ((failure as { code?: unknown } | undefined)?.code === 'not_sent' && attempt === original) attempt = undefined;
          throw failure;
        }
      },
      canRecheck: (list, result, target) => !!activeAttempt(list, result, target),
      async recheck(list, result, target) {
        const original = activeAttempt(list, result, target);
        if (!original) throw Error();
        original.checked = true;
        return showObservation(original.selection, observation(original.selection,
          await continueCall(original.selection, 'query', { attemptId: original.id }), original));
      },
      files: handoff ? {
        canOpen: canOpenSibling,
        async open(place, observed) {
          if (!canOpenSibling(place, observed) || !publication) throw Error();
          const original = publication;
          publication = undefined; v.moving = 'opening';
          try {
            const reply = data(await bridge.command(checkpointContinuePrefix + 'openFiles',
              { ...continuationWire(original.selection), publicationHandle: original.id }));
            const source = original.selection.source;
            const arrival = checkedSiblingFiles(reply, pin.viewHandle, pin.filesHandle, observed.target.checkoutId,
              { scope, before: comparisonBase(source), selected: selectedVersion(source) });
            if (!liveSource(v) || v.revoked || v.moving !== 'opening' || !handoff.clean() ||
                !handoff.adopt(arrival, () => finish(v, false))) throw Error();
          } catch (failure) {
            if (liveSource(v) && !v.revoked) {
              const code = (failure as { code?: unknown } | undefined)?.code;
              if ((code === 'not_sent' || code === 'not_accepted') && samePin(pin, bridge.getState().state.sourceCheckpointVisit)) v.moving = undefined;
              else { v.moving = 'unknown'; dead(v); handoff.uncertain(); }
            }
            throw failure;
          }
        },
      } : undefined,
    } : undefined;
    const supportsSelect = () => bridge.getState().capabilities.commands.includes(checkpointContinuePrefix + 'select');
    const canSelect = () => mayContinue() && !v.moving && supportsSelect();
    return {
      scope, recovery: v.recovery ?? null, isCurrent: current, canReviewSavedSource: () => current() && !recovery && !review && !pendingReview,
      onRetired(listener) { v.listeners.add(listener); return () => { v.listeners.delete(listener); }; },
      continuation,
      selectForContinue: continuation && supportsSelect() ? async (value, row) => {
        if (value.pending || !canSelect() || continuationReview) throw Error();
        const id = shown(value, row), epoch = clearSelection();
        const val = data(await bridge.command(checkpointContinuePrefix + 'select', { ...held, catalogId: id, version: versionPin(row) }));
        const verified = val.version as Version | undefined;
        if (selectionEpoch !== epoch || !canSelect() || !samePin(pin, val) || val.catalogId !== id || !uuid(val.selectionId) ||
            val.verified !== true || !verified || !sameVersion(row, verified) || !catalog || catalog.value !== value || catalog.id !== id) throw Error();
        const source = { kind: 'single', version: row } as const;
        admitted = { catalog: value, catalogId: id, id: val.selectionId, source };
        return source;
      } : undefined,
      pending: supportsPending() ? {
        canReview: canReviewPending,
        async review(value) {
          if (!canReviewPending(value) || !catalog) throw Error();
          const original = catalog, deadline = performance.now() + PENDING_REVIEW_MS;
          const val = await call('pending.review', { catalogId: original.id });
          if (!pendingCurrent() || catalog !== original || val.catalogId !== original.id) throw Error();
          if (val.status === 'UNCONFIRMED' && val.reviewId === null && val.leafCount === 0 && val.storedBytes === 0) return null;
          if (val.status !== 'READY' || !uuid(val.reviewId) || !clock(val.leafCount) || val.leafCount > 2 ||
              !clock(val.storedBytes) || val.storedBytes > MAX_PENDING_BYTES || val.leafCount === 0 && val.storedBytes !== 0) throw Error();
          const offer = Object.freeze({ scope, id: val.reviewId, deadline });
          pendingReview = { catalogId: original.id, value: offer };
          return offer;
        },
        async dismiss(offer) {
          const val = await settlePending(offer, false);
          if (val.dismissed !== true) throw Error();
        },
        async clear(offer) {
          const val = await settlePending(offer, true);
          const status = val.status;
          if (status !== 'CLEARED' && status !== 'NOT_CLEARED' && status !== 'UNKNOWN') throw Error();
          return status;
        },
      } : undefined,
      async catalogLocal() {
        if (review || pendingReview) throw Error();
        const val = await call('list');
        if (!uuid(val.catalogId) || !Array.isArray(val.versions) || typeof val.pending !== 'boolean' ||
            !clock(val.storedBytes) || !Object.hasOwn(val, 'recovery')) throw Error();
        const observed = parseRecovery(scope, val.recovery);
        if (recovery && observed && !sameVersion(recovery.expected, observed.expected)) throw Error();
        if (observed) recovery = observed;
        const value: Catalog = { scope, pending: val.pending, storedBytes: val.storedBytes, versions: val.versions, recovery: observed };
        catalog = { id: val.catalogId, value }; return value;
      },
      async reviewSavedSource() {
        if (review || recovery || pendingReview) throw Error();
        const val = await call('review');
        if (!uuid(val.id) || !hash(val.manifestIdentity) || !hash(val.filteredSourceDigest) || !clock(val.expandedBytes) ||
            !Array.isArray(val.files) || !Array.isArray(val.directories) || !Array.isArray(val.omitted)) throw Error();
        review = { scope, id: val.id, manifestIdentity: val.manifestIdentity, filteredSourceDigest: val.filteredSourceDigest,
          expandedBytes: val.expandedBytes, files: val.files, directories: val.directories, omitted: val.omitted };
        consumed = false; return review;
      },
      async discardReview(original) {
        if (review !== original || consumed) throw Error();
        consumed = true;
        const val = await call('dismiss', reviewPin(original));
        if (val.dismissed !== true || val.reviewId !== original.id || (val.saveStatus !== 'NOT_SAVED' && val.saveStatus !== 'UNKNOWN')) throw Error();
        if (review === original) review = undefined;
        return val.saveStatus;
      },
      async saveReviewedSource(original) {
        if (review !== original || consumed || recovery) throw Error();
        consumed = true;
        try {
          const val = await call('save', { ...reviewPin(original), confirmed: true });
          if (val.reviewId !== original.id || val.manifestIdentity !== original.manifestIdentity) throw Error();
          const status = val.status;
          if (status !== 'SAVED' && status !== 'CAPACITY' && status !== 'PENDING' && status !== 'NOT_SAVED' && status !== 'UNKNOWN') throw Error();
          const version = val.version as Save['version'];
          if (status === 'SAVED') {
            const expected = { ...reviewPin(original), expected: version };
            if (!recoveryHere(scope, expected as Recovery)) throw Error();
            recovery = expected as Recovery;
          } else if (version !== null) throw Error();
          return { scope, status, version };
        } finally { if (review === original) review = undefined; }
      },
      async readSaved(value, row) {
        const id = shown(value, row);
        if (recovery && !sameVersion(recovery.expected, row)) throw Error();
        const val = await call('read', { catalogId: id, version: versionPin(row) });
        const verified = val.version as Version | undefined;
        if (val.verified !== true || !verified || !sameVersion(row, verified)) throw Error();
        if (recovery && sameVersion(recovery.expected, verified)) recovery = null;
        return verified;
      },
      async compareSaved(value, before, after) {
        const id = shown(value, before); shown(value, after);
        if (before.checkpointId === after.checkpointId) throw Error();
        const read = call('compare', { catalogId: id, before: versionPin(before), after: versionPin(after) }), epoch = selectionEpoch;
        const val = await read;
        const result = checkedCheckpointComparison(scope, before, after, { scope, before: val.before, after: val.after, paths: val.paths } as Comparison);
        if (continuation && selectionEpoch === epoch && !value.pending && !recovery && uuid(val.comparisonId) && catalog?.value === value && catalog.id === id)
          admitted = { catalog: value, catalogId: id, id: val.comparisonId, source: result };
        return result;
      },
    };
  }
  function reconcile(v: Visit) {
    if (active.current !== v) return;
    const marker = bridge.getState().state.sourceCheckpointVisit;
    if (sameOrigin(v.origin, data(marker))) v.admissionSeen = true;
    if (v.closed) { if (marker === null) finish(v, liveSource(v)); return; }
    if (v.exiting || v.moving) return;
    if (v.revoked) { if (v.entered && marker === null) finish(v, liveSource(v)); return; }
    if (!liveSource(v)) { v.revoked = true; dead(v); v.notice = '原源码版本窗口已变化。请返回原文件。'; redraw(); return; }
    if (!v.pin) return;
    if (!samePin(v.pin, marker)) {
      if (v.binding || v.restored && v.entered) {
        v.revoked = true; dead(v);
        if (marker === null) finish(v, true);
        else { v.notice = '原源码版本窗口无法核对。请先返回原文件。'; redraw(); }
      }
      return;
    }
    if (v.restored && !v.entered) {
      stopTimer(v); v.entered = true; v.notice = '已返回原副本。原来的两版比较暂时无法核对。'; redraw();
    } else if (!v.binding && !v.notice && v.recovery !== undefined) {
      stopTimer(v); v.binding = adapter(v, v.pin); v.entered = true; redraw();
    }
  }
  useLayoutEffect(() => {
    mounted.current = true;
    const changed = () => {
      const v = active.current; if (v) reconcile(v);
      const canEnter = available();
      if (advertised.current !== canEnter) { advertised.current = canEnter; redraw(); }
    };
    const documentChanged = () => {
      const v = active.current;
      if (v && v.document !== bridge.getDocumentEpoch()) { lostDocument.current = true; finish(v, false); }
    };
    const pagehide = () => { const v = active.current; if (v) { v.revoked = true; dead(v); redraw(); } };
    const hidden = () => { if (document.hidden) pagehide(); };
    const stop = bridge.subscribe(changed), stopDocument = bridge.subscribeDocumentEpoch(documentChanged);
    document.addEventListener('visibilitychange', hidden); window.addEventListener('pagehide', pagehide);
    return () => {
      mounted.current = false; stop(); stopDocument();
      document.removeEventListener('visibilitychange', hidden); window.removeEventListener('pagehide', pagehide);
      const v = active.current; if (v) { stopTimer(v); dead(v); }
    };
  }, []);

  function capture(source: CheckpointFilesOrigin, visitId: string, trigger: () => HTMLButtonElement | null): Visit | undefined {
    const snapshot = bridge.getState(), view = data(snapshot.view), s = snapshot.state;
    if (active.current || !supports(snapshot) || !uuid(source.viewHandle) || !uuid(source.filesHandle) || !source.current() ||
        !clock(view.resumeRevision) || !clock(s.requestedProjectRevision) || !clock(s.requestedTaskRevision) || !clock(s.requestedConnectionRevision) ||
        !Array.isArray(s.projects) || s.projects.filter(row => data(row).id === source.projectId).length !== 1) return;
    const parent = trigger()?.closest<HTMLElement>('.pw-settings-dialog-content') ?? null;
    const v: Visit = { source, origin: { viewHandle: source.viewHandle, filesHandle: source.filesHandle, visitId },
      document: bridge.getDocumentEpoch(), resume: view.resumeRevision, projectRevision: s.requestedProjectRevision,
      taskRevision: s.requestedTaskRevision, connectionRevision: s.requestedConnectionRevision, trigger,
      parent, scroll: parent?.scrollTop ?? 0, listeners: new Set() };
    return revisionMatches(v, snapshot) ? v : undefined;
  }
  function resumeComparison(source: CheckpointFilesOrigin, arrival: ReturnedCheckpointFiles, trigger: () => HTMLButtonElement | null) {
    const resume = arrival.resume;
    if (!resume || !hasCheckpointContinue() || source.projectId !== arrival.projectId || source.checkoutId !== arrival.checkoutId ||
        source.viewHandle !== arrival.viewHandle || source.filesHandle !== arrival.filesHandle) return false;
    const v = capture(source, resume.visitId, trigger);
    if (!v) return false;
    v.restored = { comparison: resume.comparison }; v.pin = { ...v.origin, shelfHandle: resume.shelfHandle, scope: resume.scope };
    v.wait = setTimeout(() => {
      if (active.current === v && !v.entered && !v.closing) {
        v.notice = '未能核对原来的两版比较，可返回原副本的文件。'; redraw();
      }
    }, 2_000);
    active.current = v; lostDocument.current = false;
    reconcile(v); redraw(); return true;
  }
  function open(source: CheckpointFilesOrigin, trigger: HTMLButtonElement) {
    if (!available() || !trigger.isConnected || trigger.disabled || trigger.closest('[hidden],[inert]')) return;
    const v = capture(source, crypto.randomUUID(), () => trigger);
    if (!v) return;
    active.current = v; refusedFiles.current = undefined; lostDocument.current = false; redraw();
    v.opening = bridge.command(prefix + 'open', v.origin).then(value => {
      if (active.current !== v) return;
      // An ACK can arrive during Back, before the corresponding native state.
      v.admissionSeen = true;
      if (!liveSource(v) || v.revoked || v.exiting) return;
      const val = data(value), name = data(val.scope).projectName;
      if (val.opened !== true || !sameOrigin(v.origin, val) || !uuid(val.shelfHandle) ||
          typeof name !== 'string' || !name.trim() || name.length > 80 ||
          !sameScope({ projectId: source.projectId, checkoutId: source.checkoutId, projectName: name }, val.scope)) throw Error();
      const scope = Object.freeze({ projectId: source.projectId, checkoutId: source.checkoutId, projectName: name });
      v.pin = { ...v.origin, shelfHandle: val.shelfHandle, scope };
      // Older host source cannot prove warm save disposition. No review on that wire.
      if (!Object.hasOwn(val, 'recovery')) throw Error();
      v.recovery = parseRecovery(scope, val.recovery);
      v.wait = setTimeout(() => {
        if (active.current === v && !v.binding && !v.closing && !v.closed) {
          v.notice = '尚未核对到原源码版本窗口。请返回原文件。'; redraw();
        }
      }, 2_000);
      reconcile(v);
    }).catch(error => {
      if (active.current !== v) return;
      const code = (error as { code?: unknown } | null)?.code;
      if (!v.admissionSeen && !v.revoked && (code === 'not_sent' || code === 'not_accepted') &&
          bridge.getState().state.sourceCheckpointVisit === null && liveSource(v)) {
        v.openRefused = true; refusedFiles.current = v.origin.filesHandle;
        finish(v, true); return;
      }
      if (v.closing || v.closed) return;
      v.notice = '没有确认打开原源码版本。请返回原文件，不会打开其他副本。'; redraw();
    });
  }
  function close(v: Visit): Promise<boolean> {
    if (v.closed || v.openRefused) return Promise.resolve(true);
    if (v.closing) return v.closing;
    if (v.moving) return Promise.resolve(false);
    if (bridge.getDocumentEpoch() !== v.document || !supports(bridge.getState())) return Promise.resolve(false);
    stopTimer(v); v.exiting = true; dead(v); v.notice = undefined;
    const request = bridge.command(prefix + 'close', { ...v.origin, ...(v.pin ? { shelfHandle: v.pin.shelfHandle } : {}) }).then(value => {
      if (v.openRefused) return true;
      const val = data(value);
      if (bridge.getDocumentEpoch() !== v.document || val.closed !== true || !sameOrigin(v.origin, val) || !uuid(val.shelfHandle) ||
          (v.pin && v.pin.shelfHandle !== val.shelfHandle)) throw Error();
      v.closed = true; reconcile(v);
      if (active.current === v && mounted.current) {
        v.wait = setTimeout(() => {
          if (active.current === v && v.closed) {
            v.notice = '原窗口已结束，仍在等待原文件界面确认。'; redraw();
          }
        }, 2_000);
      }
      return true;
    }).catch(async () => {
      // A never-admitted visit has no CLOSE receipt. Keep this one Back pending
      // until its OPEN result is classified rather than issuing another CLOSE.
      if (!v.admissionSeen) await v.opening;
      if (v.openRefused) return true;
      if (active.current === v) v.notice = '尚未确认关闭原源码版本。可以再返回一次，不会再次保存。';
      return false;
    }).finally(() => { if (v.closing === request) { v.closing = undefined; redraw(); } });
    v.closing = request; redraw(); return request;
  }
  const restored = active.current;
  const awaitingSwap = restored?.moving === 'opening' && restored.pin &&
    !samePin(restored.pin, bridge.getState().state.sourceCheckpointVisit);
  return {
    active: !!active.current,
    occupied: () => !!active.current,
    available: available(),
    visit: awaitingSwap ? undefined : active.current?.binding,
    closing: !!active.current?.closing || !!active.current?.closed,
    notice: awaitingSwap ? '正在核对新副本的文件窗口…' : active.current?.notice,
    refusedFilesHandle: refusedFiles.current,
    returnedComparison: restored?.restored && restored.entered && restored.pin && !restored.revoked && !restored.exiting &&
      liveSource(restored) && samePin(restored.pin, bridge.getState().state.sourceCheckpointVisit) ?
      restored.restored.comparison : null,
    registerBack,
    open,
    resumeComparison,
    back: () => {
      const v = active.current;
      if (!v) return false;
      if (v.moving) return true;
      if (v.binding && innerBack.current?.()) return true;
      if (v.closed) reconcile(v);
      else void close(v);
      return true;
    },
    beforeFilesExit: () => {
      const v = active.current;
      return v ? close(v) : lostDocument.current ? Promise.resolve(false) : undefined;
    },
  };
}
