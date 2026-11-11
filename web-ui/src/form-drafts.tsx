import { useCallback, useEffect, useRef, useState } from 'react';
import { Accordion, AccordionItem, Button, TextAreaField } from './forms-library';

type Data = Record<string, any>;
type Run = (action: string, args?: Data) => Promise<any>;
export type FormDraftKind = 'memory' | 'groups' | 'teams' | 'reminders' | 'commitments' | 'plans' | 'models';
export type RegisterBeforeLeave = (guard: ((leave: () => void) => void) | null) => void;
type Session = { identity: Data; revision: string; baseRevision: string; currentBase: string; value: Data; saved: string; savedBase: string; queue: Promise<void>; error: string; stale: boolean; savedAt: number; unverified: boolean; removing: boolean; conflict: Data | null };
const clone = <T,>(value: T): T => JSON.parse(JSON.stringify(value));
// Queues outlive a page, but only the app-private native receipt establishes durability.
const sessions = new Map<string, Session>();
const canonical = (value: any): string => JSON.stringify(value, (_key, item) => item && typeof item === 'object' && !Array.isArray(item) ? Object.fromEntries(Object.keys(item).sort().map(key => [key, item[key]])) : item);
const sameSaved = (value: Data | null, serialized: string) => { try { return canonical(value) === canonical(JSON.parse(serialized)); } catch { return false; } };
const failureText = (failure: unknown) => failure instanceof Error ? failure.message : String(failure);

export function useFormDrafts(run: Run, kind: FormDraftKind, registerBeforeLeave?: RegisterBeforeLeave) {
  const runner = useRef(run); runner.current = run;
  const leaving = useRef(false);
  const current = useRef<Session | null>(null), alive = useRef(true), generation = useRef(0);
  const [state, setState] = useState({ loading: false, saving: false, savedAt: 0, restored: false, stale: false, requiresRecheck: false, conflict: null as Data | null, error: '' });
  const publish = useCallback(() => { const value = current.current; if (alive.current) setState(old => ({ ...old, saving: !!value && (JSON.stringify({ ...value.value, outcomeUnverified: value.unverified }) !== value.saved || value.baseRevision !== value.savedBase) && !value.error && !value.conflict, savedAt: value?.savedAt || 0, stale: value?.stale || false, requiresRecheck: value?.unverified || false, conflict: value?.conflict || null, error: value?.error || '' })); }, []);
  const persist = useCallback(async (session: Session) => {
    const execute = async () => {
      if (session.removing) return;
      if (session.conflict) throw new Error('本机已有另一份较新的草稿，请先核对并选择保留内容。');
      const serialized = JSON.stringify({ ...session.value, outcomeUnverified: session.unverified }), baseRevision = session.baseRevision;
      if (serialized === session.saved && baseRevision === session.savedBase) return;
      try {
        const receipt = await runner.current('formDraft.save', { ...session.identity, revision: session.revision, baseRevision, draft: JSON.parse(serialized) });
        if (!receipt?.revision || !Number.isFinite(receipt.savedAt)) throw new Error('草稿尚未取得本机保存回执。');
        session.revision = receipt.revision; session.saved = serialized; session.savedBase = baseRevision; session.savedAt = receipt.savedAt; session.error = '';
      } catch (failure) {
        // A lost acknowledgement can follow a completed disk write. Read the exact
        // content back before recovering its revision; a conflict is never overwritten.
        try {
          const stored = await runner.current('formDraft.read', session.identity);
          if (stored?.revision && stored.baseRevision === baseRevision && sameSaved(stored.draft, serialized)) { session.revision = stored.revision; session.saved = serialized; session.savedBase = baseRevision; session.savedAt = stored.savedAt; session.error = ''; return; }
        } catch { /* Preserve the original save error. */ }
        session.error = failureText(failure); throw failure;
      } finally { publish(); }
    };
    const pending = session.queue.catch(() => {}).then(execute); session.queue = pending;
    await pending;
  }, [publish]);
  const flush = useCallback(async () => { const session = current.current; if (session) { await session.queue.catch(() => {}); await persist(session); } }, [persist]);
  const guard = useCallback((leave: () => void) => { if (leaving.current) return; leaving.current = true; void flush().then(() => { if (alive.current) leave(); }).catch(() => publish()).finally(() => { leaving.current = false; }); }, [flush, publish]);
  useEffect(() => { registerBeforeLeave?.(guard); return () => registerBeforeLeave?.(null); }, [guard, registerBeforeLeave]);
  useEffect(() => { alive.current = true; return () => { alive.current = false; generation.current++; const session = current.current; if (session) void persist(session).catch(() => {}); }; }, [persist]);
  const open = useCallback(async <T extends Data>(scope: string, objectId: string, baseRevision: string, initial: T, saveInitial = false): Promise<T | null> => {
    const ticket = ++generation.current, identity = { kind, scope, objectId }, key = JSON.stringify(identity);
    if (alive.current) setState({ loading: true, saving: false, savedAt: 0, restored: false, stale: false, requiresRecheck: false, conflict: null, error: '' });
    try {
      const active = current.current;
      try { await flush(); } catch (failure) {
        // A failed old window may retry the same object after reading its native
        // revision. Opening another object must not hide these unsaved inputs.
        if (!active || JSON.stringify(active.identity) !== key) throw failure;
      }
      if (active && JSON.stringify(active.identity) !== key) current.current = null;
      const previous = sessions.get(key) || (active && JSON.stringify(active.identity) === key ? active : null);
      if (previous) await previous.queue.catch(() => {});
      const receipt = await runner.current('formDraft.read', identity);
      if (!receipt || typeof receipt.revision !== 'string') throw new Error('尚未取得本机草稿，请重试。');
      if (!alive.current || ticket !== generation.current) return null;
      const storedDraft: Data | null = receipt.draft && typeof receipt.draft === 'object' && !Array.isArray(receipt.draft) ? clone(receipt.draft) : null;
      const stored = storedDraft ? clone(storedDraft) : {}, nativeUnverified = stored.outcomeUnverified === true; delete stored.outcomeUnverified;
      const localSerialized = previous ? JSON.stringify({ ...previous.value, outcomeUnverified: previous.unverified }) : '';
      const localUnsaved = !!previous && !previous.removing && (localSerialized !== previous.saved || previous.baseRevision !== previous.savedBase);
      const nativeHasLocal = localUnsaved && receipt.baseRevision === previous!.baseRevision && sameSaved(storedDraft, localSerialized);
      const nativeUnchanged = !!previous && (receipt.revision === previous.revision || receipt.baseRevision === previous.savedBase && sameSaved(storedDraft, previous.saved));
      const keepLocal = localUnsaved && !nativeHasLocal;
      const conflict = keepLocal && (!!previous?.conflict || !nativeUnchanged) ? (storedDraft || {}) : null;
      const value = keepLocal ? clone(previous!.value) : { ...clone(initial), ...stored };
      const unverified = keepLocal ? previous!.unverified || nativeUnverified : nativeUnverified;
      const sourceBase = keepLocal ? previous!.baseRevision : storedDraft ? String(receipt.baseRevision || '') : baseRevision;
      const baseline = keepLocal ? (storedDraft ? JSON.stringify(storedDraft) : previous!.saved) : JSON.stringify({ ...value, outcomeUnverified: unverified });
      const session: Session = { identity, revision: receipt.revision, baseRevision: sourceBase, currentBase: baseRevision, value,
        saved: saveInitial && !storedDraft ? '' : baseline, savedBase: storedDraft ? String(receipt.baseRevision || '') : previous?.savedBase || baseRevision,
        queue: Promise.resolve(), error: '', stale: (!!storedDraft || keepLocal) && sourceBase !== baseRevision, savedAt: storedDraft ? receipt.savedAt || 0 : 0, unverified, removing: false, conflict };
      sessions.set(key, session); current.current = session;
      setState({ loading: false, saving: keepLocal && !conflict, savedAt: session.savedAt, restored: !!storedDraft || keepLocal, stale: session.stale, requiresRecheck: session.unverified, conflict, error: '' });
      return value as T;
    } catch (failure) { if (alive.current && ticket === generation.current) setState(old => ({ ...old, loading: false, error: failureText(failure) })); return null; }
  }, [kind, flush]);
  const update = useCallback((value: Data) => { const session = current.current; if (!session || session.removing) return; session.value = clone(value); if (JSON.stringify({ ...session.value, outcomeUnverified: session.unverified }) === session.saved && session.baseRevision === session.savedBase) return; session.error = ''; publish(); if (!session.conflict) void persist(session).catch(() => {}); }, [persist, publish]);
  const discard = useCallback((leave?: () => void) => {
    const session = current.current; if (!session) { leave?.(); return; }
    session.removing = true;
    // Removal follows prior writes and advances the native revision. A late save cannot resurrect it.
    const pending = session.queue.catch(() => {}).then(async () => {
      let receipt: Data;
      if (session.conflict) { if (current.current === session) current.current = null; sessions.delete(JSON.stringify(session.identity)); if (alive.current) setState({ loading: false, saving: false, savedAt: 0, restored: false, stale: false, requiresRecheck: false, conflict: null, error: '' }); leave?.(); return; }
      try { receipt = await runner.current('formDraft.remove', { ...session.identity, revision: session.revision }); } catch (failure) { const stored = await runner.current('formDraft.read', session.identity); if (stored?.draft != null) throw failure; receipt = stored; }
      if (typeof receipt?.revision !== 'string' || receipt.draft != null) throw new Error('草稿清除尚未确认。');
      session.revision = receipt.revision; session.saved = JSON.stringify({ ...session.value, outcomeUnverified: session.unverified }); session.savedAt = 0; session.error = '';
      if (current.current === session) current.current = null;
      sessions.delete(JSON.stringify(session.identity));
      if (alive.current) setState({ loading: false, saving: false, savedAt: 0, restored: false, stale: false, requiresRecheck: false, conflict: null as Data | null, error: '' });
      leave?.();
    });
    session.queue = pending; void pending.catch(failure => { session.removing = false; session.error = failureText(failure); publish(); });
  }, [publish]);
  const rebase = useCallback(() => { const session = current.current; if (!session) return; session.baseRevision = session.currentBase; session.stale = false; session.saved = ''; update(session.value); }, [update]);
  const retry = useCallback(() => { const session = current.current; if (session) { session.error = ''; void persist(session).catch(() => {}); publish(); } }, [persist, publish]);
  const keepCurrent = useCallback(() => { const session = current.current; if (!session) return; session.conflict = null; session.saved = ''; update(session.value); }, [update]);
  const markSubmitted = useCallback(async () => { const session = current.current; if (session) { session.unverified = true; publish(); await persist(session); } }, [persist, publish]);
  const clearSubmitted = useCallback(() => { const session = current.current; if (session) { session.unverified = false; publish(); void persist(session).catch(() => {}); } }, [persist, publish]);
  return { ...state, hasSession: !!current.current, open, update, guard, discard, rebase, retry, flush, markSubmitted, clearSubmitted, keepCurrent };
}

export function FormDraftNotice({ draft }: { draft: ReturnType<typeof useFormDrafts> }) {
  return <div className="pw-field-hint" aria-live="polite">
    {draft.conflict && <><p role="alert">本机已有另一份不同的草稿。表单保留了上次未保存的输入，尚未覆盖本机内容。</p><Accordion><AccordionItem id="draft-conflict" title="对照本机已保存的草稿"><TextAreaField label="本机已保存内容（只读）" value={JSON.stringify(draft.conflict, null, 2)} isReadOnly rows={6} /></AccordionItem></Accordion><Button variant="secondary" onClick={draft.keepCurrent}>保留当前输入，替换本机草稿</Button><p>选择表单中的“放弃草稿”会放弃这次未保存输入，并保留本机那份草稿。</p></>}
    {draft.loading ? <p>正在读取本机草稿…</p> : draft.error ? <><p role="alert">{draft.hasSession ? '草稿尚未保存' : '草稿尚未读取'}：{draft.error}。{draft.hasSession ? '请保留此页，重试保存后再离开。' : '请重新打开要编辑的对象。'}</p>{draft.hasSession && <Button variant="secondary" onClick={draft.retry}>重试保存草稿</Button>}</> : draft.conflict ? null : draft.saving ? <p>正在保存草稿…</p> : draft.savedAt ? <p>{draft.restored ? '已恢复本机草稿。' : ''}草稿已保存在此设备，保留 30 天；返回不会提交或执行。</p> : null}
    {draft.requiresRecheck && <p>这份草稿曾提交过，结果尚未在此确认。请先读取原记录核对，避免重复创建或执行。</p>}
    {draft.stale && <><p>原记录已更新。你的输入仍保留，请对照当前记录核对后继续。</p><Button variant="secondary" onClick={draft.rebase}>已核对，继续编辑当前版本</Button></>}
  </div>;
}
