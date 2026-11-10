import { useEffect, useRef, useState } from 'react';
import { IconCheck, IconChevronDown, IconClose, IconModel } from './icons';
import { Button, IconButton } from './forms-library';
import { Radio, RadioGroup } from './forms-library/boardui/components/base/radio/radio';
import { Popover } from './shell-library';
import { ShimmerText } from './library';
import { accountModelCatalog, accountModelReadRequest, accountModelReviewRequest } from './composer-account-models';
import { accountReasoningReview, apiReasoningReview, currentReasoningTarget, modelRevision,
  reasoningAvailability, reasoningLabel, reasoningSavedSnapshot, reasoningSelection, sameAccountTarget,
  type ModelControlRecord, type ReasoningReview, type ReasoningTarget } from './conversation-model-controls';
import './conversation-model-controls.css';
import { ScopedConversationModelControls } from './ScopedConversationModelControls';
import { ReasoningIndicator } from './ReasoningIndicator';

export type ConversationModelControlsProps = {
  state: ModelControlRecord;
  can: (action: string) => boolean;
  act: (action: string, args?: ModelControlRecord) => Promise<any>;
  onOpenModel: (section?: 'models' | 'reasoning', anchor?: HTMLButtonElement) => void;
  pickerSection?: 'models' | 'reasoning' | null;
  disabled?: boolean;
  registerBackHandler?: (handler: (() => boolean) | null) => void;
};
const selectionStamp = (state: ModelControlRecord) => JSON.stringify([state.modelSelectionRevision, state.modelRoute, state.modelName, state.modelProvider]);
const nativeBusy = (state: ModelControlRecord) => !!(state.modelSelectionBusy || state.chatSubmitting || state.phoneSubmitting || state.modelSaving || state.modelTesting);
const message = (failure: unknown, fallback: string) => failure instanceof Error ? failure.message : fallback;

/** Compact conversation controls; every edit retains the original native model review. */
export function ConversationModelControls({ state, can, act, onOpenModel, pickerSection = null, disabled = false, registerBackHandler }: ConversationModelControlsProps) {
  return can('models.conversation.review')
    ? <ScopedConversationModelControls state={state} can={can} act={act} onOpenModel={onOpenModel} pickerSection={pickerSection} disabled={disabled} registerBackHandler={registerBackHandler} />
    : <LegacyConversationModelControls state={state} can={can} act={act} onOpenModel={onOpenModel} pickerSection={pickerSection} disabled={disabled} registerBackHandler={registerBackHandler} />;
}
function LegacyConversationModelControls({ state, can, act, onOpenModel, pickerSection = null, disabled = false, registerBackHandler }: ConversationModelControlsProps) {
  const [open, setOpen] = useState(false), [loading, setLoading] = useState(false), [saving, setSaving] = useState(false);
  const [error, setError] = useState(''), [notice, setNotice] = useState('');
  const [session, setSession] = useState<ReasoningReview | null>(null), [choiceId, setChoiceId] = useState('');
  const [panelTarget, setPanelTarget] = useState<ReasoningTarget | null>(null);
  const [summary, setSummary] = useState<{ stamp: string; target: ReasoningTarget | null; error: string; loading: boolean }>({ stamp: '', target: null, error: '', loading: false });
  const mounted = useRef(false), isOpen = useRef(false), pending = useRef(false), reads = useRef(0), summaries = useRef(0), accountWindow = useRef('');
  const modelTrigger = useRef<HTMLButtonElement>(null), reasoningTrigger = useRef<HTMLButtonElement>(null), reasoningBody = useRef<HTMLDivElement>(null);
  const latest = useRef({ state, can, act, onOpenModel, disabled }); latest.current = { state, can, act, onOpenModel, disabled };
  const stamp = selectionStamp(state), observedStamp = useRef(stamp), canRead = can('models.snapshot'), busy = nativeBusy(state);
  const locked = disabled || busy || saving;
  const currentSummary = summary.stamp === stamp ? summary : null;
  const currentTarget = currentSummary?.target;
  const modelName = String(state.modelName || currentTarget?.modelId || '选择模型');
  const effort = reasoningLabel(currentTarget?.reasoning || null, true);

  const closeAccount = (id: string) => { if (id) void latest.current.act('models.accounts.close', { windowId: id }).catch(() => {}); };
  const retireAccount = () => { const id = accountWindow.current; accountWindow.current = ''; closeAccount(id); };
  const closePanel = () => {
    if (pending.current || latest.current.state.modelSelectionBusy) return;
    isOpen.current = false; reads.current++; retireAccount(); setOpen(false); setLoading(false); setSession(null);
  };
  const refreshSummary = async () => {
    if (!latest.current.can('models.snapshot') || nativeBusy(latest.current.state) || pending.current) return;
    const original = ++summaries.current, originalStamp = selectionStamp(latest.current.state);
    setSummary({ stamp: originalStamp, target: null, error: '', loading: true });
    try {
      const snapshot = await latest.current.act('models.snapshot');
      if (!mounted.current || original !== summaries.current || originalStamp !== selectionStamp(latest.current.state) || nativeBusy(latest.current.state)) return;
      setSummary({ stamp: originalStamp, target: currentReasoningTarget(snapshot), error: '', loading: false });
    } catch (failure) {
      if (mounted.current && original === summaries.current && originalStamp === selectionStamp(latest.current.state)) setSummary({ stamp: originalStamp, target: null, error: message(failure, '推理设置暂不可读取。'), loading: false });
    }
  };
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; isOpen.current = false; reads.current++; summaries.current++; retireAccount(); };
  }, []);
  useEffect(() => { if (canRead && !busy) void refreshSummary(); }, [canRead, stamp, busy]);
  useEffect(() => {
    if (isOpen.current && !pending.current && (observedStamp.current !== stamp || state.modelSelectionBusy)) {
      reads.current++; retireAccount(); setSession(null); setLoading(false); setError('模型设置已变化，请重新读取。');
    }
    observedStamp.current = stamp;
  }, [stamp, state.modelSelectionBusy]);
  useEffect(() => {
    registerBackHandler?.(() => { if (!isOpen.current) return false; closePanel(); return true; });
    return () => registerBackHandler?.(null);
  }, [registerBackHandler]);
  useEffect(() => {
    if (!session || !open || saving) return;
    const timer = window.setTimeout(() => {
      setSession(null); retireAccount(); setError('推理设置窗口已过期，请重新读取。');
    }, Math.max(0, session.expiresAt - Date.now()));
    return () => window.clearTimeout(timer);
  }, [session, open, saving]);

  const readReasoning = async () => {
    if (pending.current || nativeBusy(latest.current.state)) return;
    const original = ++reads.current, originalStamp = selectionStamp(latest.current.state), revision = latest.current.state.modelSelectionRevision;
    summaries.current++; retireAccount(); setSession(null); setPanelTarget(null); setError(''); setNotice(''); setLoading(true);
    let readTarget: ReasoningTarget | null = null;
    const originalRead = () => {
      if (!mounted.current || !isOpen.current || original !== reads.current) return false;
      if (originalStamp !== selectionStamp(latest.current.state) || nativeBusy(latest.current.state)) throw new Error('模型设置已变化，请重新读取。');
      return true;
    };
    try {
      if (!latest.current.can('models.snapshot')) { setNotice('当前版本尚未接通对话内推理设置。'); setSummary({ stamp: originalStamp, target: null, error: '', loading: false }); return; }
      if (!modelRevision(revision)) throw new Error('当前模型状态尚未准备，请重新读取。');
      const snapshot = await latest.current.act('models.snapshot');
      if (!originalRead()) return;
      const target = currentReasoningTarget(snapshot);
      readTarget = target;
      setSummary({ stamp: originalStamp, target, error: '', loading: false }); setPanelTarget(target);
      if (target.restriction) { setNotice(target.restriction); return; }
      let prepared: ReasoningReview;
      if (target.route === 'api_key') {
        if (!['models.review', 'models.select'].every(command => latest.current.can(command))) { setNotice('当前版本尚未接通此连接的推理设置。'); return; }
        const review = await latest.current.act('models.review', { modelId: target.modelId });
        if (!originalRead()) return;
        prepared = apiReasoningReview(target, review, revision);
      } else {
        if (!['open', 'close', 'models', 'review', 'select'].every(command => latest.current.can(`models.accounts.${command}`))) { setNotice('当前版本尚未接通账号模型的推理设置。'); return; }
        const projection = await latest.current.act('models.accounts.open');
        if (!mounted.current || !isOpen.current || original !== reads.current) { closeAccount(projection?.windowId); return; }
        if (typeof projection?.windowId !== 'string' || !projection.windowId) throw new Error('原账号模型窗口未能读取。');
        accountWindow.current = projection.windowId;
        if (!originalRead()) return;
        if (!sameAccountTarget(projection, target)) throw new Error('当前账号模型已变化，请重新读取。');
        const args = accountModelReadRequest(projection, revision, target.selection.nativeRef.accountId);
        if (args.expectedVersion !== target.selection.nativeRef.accountVersion) throw new Error('原账号版本已变化，请重新读取。');
        const catalog = await latest.current.act('models.accounts.models', args);
        if (!originalRead()) return;
        if (!sameAccountTarget(catalog, target) || !accountModelCatalog(catalog, revision)?.models.some(model => model.id === target.modelId)) throw new Error('当前账号模型已不在原目录中，请重新读取。');
        const review = await latest.current.act('models.accounts.review', accountModelReviewRequest(catalog, revision, target.modelId));
        if (!originalRead()) return;
        prepared = accountReasoningReview(target, review, revision);
      }
      if (originalRead()) { setSession(prepared); setChoiceId(prepared.currentId); }
    } catch (failure) {
      if (mounted.current && original === reads.current) {
        retireAccount(); setSession(null); setError(message(failure, '推理设置暂不可读取，请重新读取。'));
        if (!readTarget) setSummary({ stamp: originalStamp, target: null, error: message(failure, '推理设置暂不可读取。'), loading: false });
      }
    } finally { if (mounted.current && original === reads.current) setLoading(false); }
  };
  const openPanel = (anchor?: HTMLButtonElement) => {
    if (latest.current.disabled || nativeBusy(latest.current.state) || pending.current || isOpen.current) return;
    if (anchor) reasoningTrigger.current = anchor;
    reasoningTrigger.current?.focus({ preventScroll: true });
    isOpen.current = true; setOpen(true); void readReasoning();
  };
  const selectModel = (anchor?: HTMLButtonElement) => {
    if (locked || pending.current) return;
    closePanel(); latest.current.onOpenModel('models', anchor || modelTrigger.current || undefined);
  };
  const applyReasoning = async () => {
    if (locked || loading || pending.current || !session) return;
    let args: ModelControlRecord;
    try { args = reasoningSelection(session, latest.current.state.modelSelectionRevision, choiceId); }
    catch (failure) { setSession(null); setError(message(failure, '推理设置窗口已变化，请重新读取。')); return; }
    const original = ++reads.current, reviewed = session, choice = session.choices.find(item => item.id === choiceId)!;
    pending.current = true; summaries.current++; setSaving(true); setSession(null); setError('');
    let saved = false;
    try {
      const result = await latest.current.act(reviewed.route === 'api_key' ? 'models.select' : 'models.accounts.select', args);
      let snapshot: ModelControlRecord;
      if (reviewed.route === 'api_key') {
        if (result?.accepted !== true || result?.modelId !== reviewed.target.modelId || !result?.snapshot) throw new Error('保存回执尚未确认，请重新读取当前设置。');
        snapshot = result.snapshot;
      } else {
        if (result?.status !== 'saved') throw new Error('保存回执尚未确认，请重新读取当前设置。');
        snapshot = await latest.current.act('models.snapshot');
      }
      const target = reasoningSavedSnapshot(snapshot, reviewed.target, choice.value);
      if (mounted.current && original === reads.current) {
        setSummary({ stamp: selectionStamp(latest.current.state), target, error: '', loading: false }); saved = true;
      }
    } catch (failure) {
      if (mounted.current && original === reads.current) {
        retireAccount(); setSummary({ stamp: selectionStamp(latest.current.state), target: null, error: '保存结果待核对', loading: false });
        setError(`${message(failure, '保存结果待核对，请重新读取当前设置。')} 没有自动重试。`);
      }
    } finally {
      pending.current = false;
      if (mounted.current && original === reads.current) {
        setSaving(false);
        if (saved) { isOpen.current = false; setOpen(false); retireAccount(); }
      }
    }
  };
  const selectedChoice = session?.choices.find(item => item.id === choiceId);
  const canApply = !!session && !!selectedChoice && !selectedChoice.readOnly && choiceId !== session.currentId && session.revision === state.modelSelectionRevision && Date.now() < session.expiresAt;
  const strengthReading = !!currentSummary?.loading || loading;
  const strengthLabel = saving ? '推理强度：正在保存' : strengthReading ? '推理强度：正在读取'
    : currentSummary?.error ? '推理强度：读取失败，点按重新读取'
    : effort ? `推理强度：${reasoningLabel(currentTarget?.reasoning || null)}` : '推理强度：尚未读取';

  return <div className="pw-conversation-model-controls" aria-label="模型与推理">
    <Button ref={modelTrigger} variant="ghost" className="pw-conversation-model-chip" onClick={event => selectModel(event.currentTarget)} disabled={locked || loading} aria-label={`切换模型：${modelName}`} aria-haspopup="dialog" aria-expanded={pickerSection === 'models'} title={modelName}>
      <IconModel size={19} aria-hidden="true" /><span className="pw-conversation-model-chip-name">{modelName}</span><IconChevronDown size={14} aria-hidden="true" />
    </Button>
    <Button ref={reasoningTrigger} variant="ghost" className="pw-conversation-reasoning-chip" onClick={event => open ? closePanel() : openPanel(event.currentTarget)} disabled={locked || !state.modelName} aria-label={strengthLabel} aria-haspopup="dialog" aria-expanded={open} aria-busy={strengthReading || saving || undefined} title={strengthLabel}>
      <ReasoningIndicator reasoning={currentTarget?.reasoning || null} loading={strengthReading || saving} error={!!currentSummary?.error} /><span>推理</span>{effort && <><span aria-hidden="true">·</span><b>{effort}</b></>}
    </Button>
    <Popover triggerRef={reasoningTrigger} isOpen={open} onOpenChange={next => next ? openPanel() : closePanel()}
      placement="top start" offset={8} shouldFlip containerPadding={16} maxHeight={480} scrollRef={reasoningBody}
      isKeyboardDismissDisabled={saving || !!state.modelSelectionBusy} aria-label="推理强度" motionProfile="quick"
      className="pw-conversation-picker-popover pw-conversation-reasoning-popover" dialogClassName="pw-conversation-picker-dialog">
        <header className="pw-conversation-picker-header"><h2>推理强度</h2><IconButton icon={IconClose} aria-label="关闭推理设置" onClick={closePanel} disabled={saving || !!state.modelSelectionBusy} /></header>
        <div ref={reasoningBody} className="pw-conversation-picker-body pw-conversation-reasoning-body">
          <p className="pw-model-selection-scope">{panelTarget?.modelId || modelName}</p>
          {loading ? <div className="pw-conversation-reasoning-loading" role="status" aria-busy="true"><ShimmerText>正在读取推理设置…</ShimmerText></div> : saving ? <p className="pw-conversation-reasoning-note" role="status">正在保存推理设置…</p> : session ? <>
            <RadioGroup aria-label="可用推理强度" value={choiceId} onChange={setChoiceId} isDisabled={locked} className="pw-conversation-reasoning-options">
              {session.choices.map(choice => {
                const detail = choice.value.mode === 'DEFAULT' ? '服务默认' : choice.detail !== choice.value.level ? choice.detail : '';
                return <Radio key={choice.id} value={choice.id} aria-label={`${choice.label}${choice.detail ? `，${choice.detail}` : ''}`} isDisabled={choice.readOnly} className="pw-conversation-reasoning-option pw-conversation-reasoning-draft">
                  <span className="pw-conversation-reasoning-copy"><b>{choice.label}</b>{detail && <small>{detail}</small>}</span>
                  <IconCheck className="pw-conversation-reasoning-check" size={18} aria-hidden="true" data-selected={choiceId === choice.id || undefined} />
                </Radio>;
              })}
            </RadioGroup>
            {session.choices.filter(choice => !choice.readOnly).length < 2 && <p className="pw-conversation-reasoning-note">{reasoningAvailability(session.route === 'account' ? session.review.catalogFacts?.facts || {} : session.target.facts)}</p>}
          </> : notice ? <><p className="pw-conversation-reasoning-current">当前推理 <b>{reasoningLabel(panelTarget?.reasoning || null) || '尚未读取'}</b></p><p className="pw-conversation-reasoning-note">{notice}</p></> : null}
          {error && <p className="pw-conversation-reasoning-error" role="alert">{error}</p>}
        </div>
        <footer className="pw-conversation-reasoning-actions">
          <Button variant="ghost" onClick={closePanel} disabled={saving || !!state.modelSelectionBusy}>{session ? '取消' : '关闭'}</Button>
          {error ? <Button variant="secondary" onClick={() => void readReasoning()} disabled={locked || loading}>重新读取</Button> : notice ? <Button variant="secondary" onClick={() => selectModel()} disabled={locked}>选择模型</Button> : <Button onClick={() => void applyReasoning()} disabled={locked || loading || !canApply}>{saving ? '正在保存…' : '应用'}</Button>}
        </footer>
    </Popover>
  </div>;
}
