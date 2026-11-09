import { useEffect, useRef, useState } from 'react';
import { RiArrowDownSLine, RiBrainLine, RiCpuLine } from '@remixicon/react';
import { Button } from './forms-library';
import { Radio, RadioGroup } from './forms-library/boardui/components/base/radio/radio';
import { ShimmerText } from './library';
import { nativeReasoning, reasoningChoices, reasoningLabel } from './conversation-model-controls';
import { conversationModelName, conversationModelStamp, conversationSelectionReceipt } from './conversation-model-selection';
import type { ConversationModelControlsProps } from './ConversationModelControls';

type Row = Record<string, any>;
const record = (value: any): Row => value && typeof value === 'object' && !Array.isArray(value) ? value : {};
const busy = (state: Row) => !!(state.modelSelectionBusy || state.chatSubmitting || state.phoneSubmitting || state.modelSaving || state.modelTesting);
const canonical = (value: any): string => JSON.stringify(value && typeof value === 'object' ? Array.isArray(value) ? value.map(item => JSON.parse(canonical(item))) : Object.fromEntries(Object.keys(value).sort().map(key => [key, JSON.parse(canonical(value[key]))])) : value ?? null);
function reviewCurrent(value: Row, state: Row): boolean {
  const preference = record(state.conversationModel), target = record(preference.target), native = record(value.nativeSelection);
  if (value.scopeKey !== preference.scopeKey || value.revision !== preference.revision || value.selectionRevision !== state.modelSelectionRevision || value.inherited !== preference.inherited || canonical(value.target) !== canonical(preference.target)) return false;
  if (target.kind === 'api' && (native.modelRoute !== 'api_key' || native.model !== target.modelId || record(native.capabilityBinding).connectionId !== target.connectionId || target.binding && canonical(native.capabilityBinding) !== canonical(target.binding))) return false;
  if (target.kind === 'account' && (native.modelRoute !== 'account' || native.model !== record(target.nativeRef).modelId || canonical(native.nativeRef) !== canonical(target.nativeRef))) return false;
  if (target.kind === 'group') return record(native.modelRoutePlan).requestedAlias === target.groupId && record(native.modelRoutePlan).groupRevision === target.groupRevision;
  return !target.kind || canonical(native.reasoning) === canonical(target.reasoning) && native.maxOutputTokens === target.outputTokens && native.imageTokenReserve === target.imageTokenReserve;
}
function savedReasoningCurrent(preference: Row, reviewed: Row, intended: Row): boolean {
  const target = record(preference.target), native = record(reviewed.nativeSelection);
  if (preference.inherited !== false || canonical(target.reasoning) !== canonical(intended) || target.outputTokens !== native.maxOutputTokens || target.imageTokenReserve !== native.imageTokenReserve) return false;
  if (native.modelRoute === 'api_key') return target.kind === 'api' && target.modelId === native.model && target.connectionId === native.capabilityBinding?.connectionId && canonical(target.binding) === canonical(native.capabilityBinding);
  return native.modelRoute === 'account' && target.kind === 'account' && canonical(target.nativeRef) === canonical(native.nativeRef);
}

/** Both composer chips enter the same sheet. Only native values are summarized. */
export function ScopedConversationModelControls({ state, can, act, onOpenModel, disabled = false }: ConversationModelControlsProps) {
  const [defaultReasoning, setDefaultReasoning] = useState<Row | null>(null);
  const preference = record(state.conversationModel), target = record(preference.target), stamp = conversationModelStamp(state);
  const modelName = conversationModelName(state) || '选择模型', locked = disabled || busy(state);
  useEffect(() => {
    setDefaultReasoning(null);
    if (preference.inherited !== true || !state.modelName || busy(state) || !can('models.snapshot')) return;
    let active = true;
    void act('models.snapshot').then(value => { if (active) setDefaultReasoning(record(value?.nativeSelection).reasoning || null); }).catch(() => {});
    return () => { active = false; };
  }, [stamp, preference.inherited, state.modelName, state.modelSelectionBusy]);
  const reasoning = nativeReasoning(target.reasoning || defaultReasoning), effort = reasoningLabel(reasoning, true);
  return <div className="pw-conversation-model-controls" aria-label="此对话的模型与推理">
    <Button variant="ghost" className="pw-conversation-model-chip" onClick={() => onOpenModel('models')} disabled={locked} aria-label={`切换此对话模型：${modelName}`} title={modelName}>
      <RiCpuLine size={16} aria-hidden="true" /><span className="pw-conversation-model-chip-name">{modelName}</span><RiArrowDownSLine size={14} aria-hidden="true" />
    </Button>
    <Button variant="ghost" className="pw-conversation-reasoning-chip" onClick={() => onOpenModel('reasoning')} disabled={locked || !conversationModelName(state)} aria-label={`推理强度${effort ? `：${effort}` : ''}`}>
      <RiBrainLine size={17} aria-hidden="true" /><span>推理</span>{effort && <><span aria-hidden="true">·</span><b>{effort}</b></>}
    </Button>
  </div>;
}

type ReasoningPanelProps = Pick<ConversationModelControlsProps, 'state' | 'can' | 'act' | 'disabled'> & {
  onSelectionPending: (pending: boolean) => void;
};
/** Embedded in the model sheet. A changed choice consumes one original scoped review. */
export function ScopedConversationReasoningPanel({ state, can, act, disabled = false, onSelectionPending }: ReasoningPanelProps) {
  const [loading, setLoading] = useState(false), [saving, setSaving] = useState(false);
  const [review, setReview] = useState<Row | null>(null), [choice, setChoice] = useState(''), [error, setError] = useState(''), [notice, setNotice] = useState('');
  const [needsRead, setNeedsRead] = useState(false), [savedReasoning, setSavedReasoning] = useState<Row | null>(null);
  const alive = useRef(false), pending = useRef(false), reads = useRef(0), started = useRef(false), hasWindow = useRef(false);
  const confirmed = useRef<Row | null>(null);
  const latest = useRef({ state, act, onSelectionPending }); latest.current = { state, act, onSelectionPending };
  const preference = record(state.conversationModel), current = conversationModelStamp(state), previous = useRef(current), previousScope = useRef(preference.scopeKey);
  const locked = disabled || saving || busy(state);
  const retire = () => { reads.current++; setReview(null); setLoading(false); if (hasWindow.current) { hasWindow.current = false; void latest.current.act('models.conversation.close').catch(() => {}); } };
  useEffect(() => { alive.current = true; return () => { alive.current = false; reads.current++; if (hasWindow.current) void latest.current.act('models.conversation.close').catch(() => {}); }; }, []);
  const read = async (keepNotice = false) => {
    if (pending.current || busy(latest.current.state)) return;
    const request = ++reads.current, original = conversationModelStamp(latest.current.state);
    started.current = true; confirmed.current = null; setLoading(true); setReview(null); setError(''); if (!keepNotice) { setNotice(''); setSavedReasoning(null); }
    try {
      if (hasWindow.current) await latest.current.act('models.conversation.close');
      if (!alive.current || request !== reads.current) return;
      hasWindow.current = true;
      const value = await latest.current.act('models.conversation.review');
      if (!alive.current || request !== reads.current) return;
      if (original !== conversationModelStamp(latest.current.state) || !reviewCurrent(record(value), latest.current.state) ||
          typeof value?.reviewHandle !== 'string' || !value.reviewHandle.trim() || !Number.isSafeInteger(value.expiresInMs) || value.expiresInMs <= 0 || value.expiresInMs > 120_000)
        throw new Error('原对话模型审阅已变化，请重新读取。');
      if (value.restriction || record(value.target).kind === 'group') {
        setReview({ ...value, restriction: value.restriction || '模型组按各候选的参数执行，请在 AI 中心管理。', choices: [], originalStamp: original, expiresAt: Date.now() + value.expiresInMs });
        setChoice(''); setNeedsRead(false); return;
      }
      const selected = nativeReasoning(value.nativeSelection?.reasoning);
      if (!selected) throw new Error('原模型参数尚未读取。');
      const choices = reasoningChoices(record(value.facts), String(value.nativeSelection?.provider || ''), selected);
      setReview({ ...value, choices, originalStamp: original, expiresAt: Date.now() + value.expiresInMs }); setNeedsRead(false);
      setChoice(choices.find(item => canonical(item.value) === canonical(selected))?.id || '');
    } catch (failure) { if (alive.current && request === reads.current) { setNeedsRead(true); setError(failure instanceof Error ? failure.message : '推理设置暂不可读取。'); } }
    finally { if (alive.current && request === reads.current) setLoading(false); }
  };
  useEffect(() => {
    if (previousScope.current !== preference.scopeKey) {
      retire(); confirmed.current = null; setSaving(false); setNotice(''); setSavedReasoning(null); setNeedsRead(true); setError('对话已切换，请重新读取模型。');
    } else if (!pending.current && !busy(state) && confirmed.current && preference.revision === confirmed.current.revision) {
      void read(true);
    } else if (!started.current && !busy(state)) {
      void read();
    } else if (previous.current !== current && !pending.current && !confirmed.current) {
      retire(); setNeedsRead(true); setError(old => old || '此对话模型已变化，请重新读取。');
    }
    previousScope.current = preference.scopeKey; previous.current = current;
  }, [current, state.modelSelectionBusy, saving]);
  useEffect(() => {
    if (!review || saving) return;
    const timer = window.setTimeout(() => { setReview(null); setNeedsRead(true); setError('原推理审阅已过期，请重新读取。'); }, Math.max(0, review.expiresAt - Date.now()));
    return () => window.clearTimeout(timer);
  }, [review, saving]);
  const changedChoice = (id: string) => review?.choices.find((item: Row) => item.id === id && !item.readOnly && canonical(item.value) !== canonical(nativeReasoning(review.nativeSelection?.reasoning)));
  const apply = async (id: string) => {
    const selected = changedChoice(id);
    if (pending.current || locked || loading || needsRead || !review || review.restriction || !selected || !can('models.conversation.reasoning')) return;
    if (Date.now() >= review.expiresAt || review.originalStamp !== conversationModelStamp(latest.current.state)) { setReview(null); setNeedsRead(true); setError('此对话模型已变化或审阅已过期，请重新读取。'); return; }
    const originalScope = review.scopeKey, originalRevision = review.revision, request = ++reads.current, original = review;
    pending.current = true; setSaving(true); setError(''); setNotice(''); latest.current.onSelectionPending(true);
    try {
      const receipt = await latest.current.act('models.conversation.reasoning', { reviewHandle: original.reviewHandle, reasoning: selected.value });
      const saved = conversationSelectionReceipt(receipt, originalScope, originalRevision), latestPreference = record(latest.current.state.conversationModel);
      if (!alive.current || request !== reads.current || latestPreference.scopeKey !== originalScope) return;
      if (![originalRevision, saved.revision].includes(latestPreference.revision) || !savedReasoningCurrent(saved, original, selected.value)) throw new Error('此对话的模型参数保存结果待核对，请重新读取。');
      confirmed.current = saved; setReview(null); setNeedsRead(true); setChoice(id); setSavedReasoning(record(saved.target).reasoning); setNotice('推理已保存');
    } catch (failure) { if (alive.current && request === reads.current && record(latest.current.state.conversationModel).scopeKey === originalScope) { confirmed.current = null; setReview(null); setNeedsRead(true); setError(`保存结果待核对。${failure instanceof Error ? failure.message : ''} 没有自动重试，请重新读取。`); } }
    finally { pending.current = false; latest.current.onSelectionPending(false); if (alive.current && request === reads.current) setSaving(false); }
  };
  const reset = async () => {
    if (pending.current || locked || loading || needsRead || preference.inherited === true || !can('models.conversation.reset')) return;
    if (current !== conversationModelStamp(latest.current.state)) return;
    const originalScope = preference.scopeKey, originalRevision = preference.revision, request = ++reads.current;
    pending.current = true; setSaving(true); setError(''); setNotice(''); latest.current.onSelectionPending(true);
    try {
      const value = await latest.current.act('models.conversation.reset', { expectedPreferenceRevision: originalRevision, expectedSelectionRevision: state.modelSelectionRevision });
      const saved = conversationSelectionReceipt(value, originalScope, originalRevision), latestPreference = record(latest.current.state.conversationModel);
      if (!alive.current || request !== reads.current || latestPreference.scopeKey !== originalScope) return;
      if (![originalRevision, saved.revision].includes(latestPreference.revision) || saved.inherited !== true || saved.target != null) throw new Error('恢复默认的结果待核对，请重新读取。');
      confirmed.current = saved; setReview(null); setNeedsRead(true); setSavedReasoning(null); setNotice('已恢复使用默认模型');
    } catch (failure) { if (alive.current && request === reads.current && record(latest.current.state.conversationModel).scopeKey === originalScope) { confirmed.current = null; setReview(null); setNeedsRead(true); setError(`保存结果待核对。${failure instanceof Error ? failure.message : ''} 没有自动重试，请重新读取。`); } }
    finally { pending.current = false; latest.current.onSelectionPending(false); if (alive.current && request === reads.current) setSaving(false); }
  };
  return <section className="pw-scoped-reasoning-panel" aria-label="此对话推理设置">
    <p className="pw-model-selection-scope">{preference.inherited === true ? '沿用默认 · ' : '此对话 · '}{conversationModelName(state) || '尚未选择模型'}</p>
    {loading ? <div role="status" aria-busy="true"><ShimmerText>正在读取推理设置…</ShimmerText></div> : review?.restriction ? <p className="pw-conversation-reasoning-note">{review.restriction}</p> : review && <RadioGroup aria-label="可用推理强度" value={choice} onChange={id => void apply(id)} isDisabled={locked || needsRead} className="pw-conversation-reasoning-options">{review.choices.map((item: Row) => <Radio key={item.id} value={item.id} isDisabled={item.readOnly} className="pw-conversation-reasoning-option"><span><b>{item.label}</b>{item.detail && <small>{item.detail}</small>}</span></Radio>)}</RadioGroup>}
    {saving && <p role="status" aria-busy="true" className="pw-model-selection-status">正在保存推理…</p>}
    {notice && <p role="status" className="pw-model-selection-status">{notice}</p>}
    {savedReasoning && !review && !loading && <p className="pw-conversation-reasoning-current">当前推理 <b>{reasoningLabel(nativeReasoning(savedReasoning))}</b></p>}
    {error && <p role="alert" className="pw-conversation-reasoning-error">{error}</p>}
    <div className="pw-scoped-reasoning-actions">
      {(error || needsRead) && <Button variant="secondary" onClick={() => void read()} disabled={locked || loading}>重新读取</Button>}
      {preference.inherited === false && can('models.conversation.reset') && <Button variant="ghost" onClick={() => void reset()} disabled={locked || loading || needsRead}>恢复使用默认模型</Button>}
    </div>
  </section>;
}
