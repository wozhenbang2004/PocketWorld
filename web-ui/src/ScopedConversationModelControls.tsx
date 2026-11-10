import { useEffect, useRef, useState } from 'react';
import { RiArrowDownSLine, RiBox3Line } from '@remixicon/react';
import { Button } from './forms-library';
import { Radio, RadioGroup } from './forms-library/boardui/components/base/radio/radio';
import { ShimmerText } from './library';
import { geminiBudgetModel, nativeReasoning, reasoningAvailability, reasoningChoices, reasoningLabel, type ReasoningValue } from './conversation-model-controls';
import { conversationModelName, conversationModelStamp, conversationSelectionReceipt } from './conversation-model-selection';
import type { ConversationModelControlsProps } from './ConversationModelControls';
import { ReasoningIndicator } from './ReasoningIndicator';

type Row = Record<string, any>;
const record = (value: any): Row => value && typeof value === 'object' && !Array.isArray(value) ? value : {};
const busy = (state: Row) => !!(state.modelSelectionBusy || state.chatSubmitting || state.phoneSubmitting || state.modelSaving || state.modelTesting);
const reasoningCandidates: Record<string, string[]> = {
  openai_chat: ['none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max'],
  openai_responses: ['none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max'],
  anthropic: ['low', 'medium', 'high', 'xhigh', 'max'],
  gemini: ['minimal', 'low', 'medium', 'high'],
};
const effortNames: Record<string, string> = { none: '关闭', minimal: '最小', low: '低', medium: '中', high: '高', xhigh: '极高', max: '最高' };
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

/** Each composer chip opens its own focused sheet. Only native values are summarized. */
export function ScopedConversationModelControls({ state, can, act, onOpenModel, disabled = false }: ConversationModelControlsProps) {
  const [summary, setSummary] = useState<{ stamp: string; reasoning: ReasoningValue | null; status: 'loading' | 'ready' | 'error' } | null>(null);
  const [summaryAttempt, setSummaryAttempt] = useState(0);
  const preference = record(state.conversationModel), target = record(preference.target), stamp = conversationModelStamp(state);
  const modelName = conversationModelName(state) || '选择模型', isBusy = busy(state), locked = disabled || isBusy;
  const inherited = preference.inherited === true && !target.kind, canRead = can('models.snapshot');
  const summaryStamp = JSON.stringify([stamp, inherited, state.modelName, state.modelRoute, state.modelProvider]);
  useEffect(() => {
    if (!inherited || !state.modelName || !canRead) { setSummary(null); return; }
    if (isBusy) { setSummary(current => current?.stamp === summaryStamp && current.status === 'ready' ? current : null); return; }
    let active = true;
    setSummary({ stamp: summaryStamp, reasoning: null, status: 'loading' });
    void act('models.snapshot').then(value => {
      if (!active) return;
      const selection = record(value?.nativeSelection), reasoning = nativeReasoning(selection.reasoning);
      if (value?.selectionUnavailable === true || selection.model !== state.modelName ||
          state.modelRoute && selection.modelRoute !== state.modelRoute || state.modelProvider && selection.provider !== state.modelProvider || !reasoning)
        throw new Error('当前推理设置暂不可读取。');
      setSummary({ stamp: summaryStamp, reasoning, status: 'ready' });
    }).catch(() => { if (active) setSummary({ stamp: summaryStamp, reasoning: null, status: 'error' }); });
    return () => { active = false; };
  }, [summaryStamp, canRead, isBusy, summaryAttempt]);
  const currentSummary = summary?.stamp === summaryStamp ? summary : null;
  const reasoning = inherited ? currentSummary?.reasoning || null
    : preference.inherited === false && ['api', 'account'].includes(target.kind) ? nativeReasoning(target.reasoning) : null;
  const effort = reasoningLabel(reasoning, true), loading = inherited && currentSummary?.status === 'loading', failed = inherited && currentSummary?.status === 'error';
  const strengthLabel = loading ? '推理强度：读取中' : failed ? '推理强度：重试'
    : reasoning ? `推理强度：${reasoningLabel(reasoning)}` : target.kind === 'group' ? '推理强度：由模型组设置' : '推理强度：自动';
  return <div className="pw-conversation-model-controls" aria-label="此对话的模型与推理">
    <Button variant="ghost" className="pw-conversation-model-chip" onClick={() => onOpenModel('models')} disabled={locked} aria-label={`切换此对话模型：${modelName}`} title={modelName}>
      <RiBox3Line size={19} aria-hidden="true" /><span className="pw-conversation-model-chip-name">{modelName}</span><RiArrowDownSLine size={14} aria-hidden="true" />
    </Button>
    <Button variant="ghost" className="pw-conversation-reasoning-chip" onClick={() => { if (failed) setSummaryAttempt(attempt => attempt + 1); onOpenModel('reasoning'); }} disabled={locked || !conversationModelName(state)} aria-label={strengthLabel} aria-busy={loading || undefined} title={strengthLabel}>
      <ReasoningIndicator reasoning={reasoning} loading={loading} error={failed} /><span>推理</span>{effort && <><span aria-hidden="true">·</span><b>{effort}</b></>}
    </Button>
  </div>;
}

type ReasoningPanelProps = Pick<ConversationModelControlsProps, 'state' | 'can' | 'act' | 'disabled'> & {
  onSelectionPending: (pending: boolean) => void;
};
/** A changed reasoning choice consumes one original scoped review. */
export function ScopedConversationReasoningPanel({ state, can, act, disabled = false, onSelectionPending }: ReasoningPanelProps) {
  const [loading, setLoading] = useState(false), [saving, setSaving] = useState(false);
  const [review, setReview] = useState<Row | null>(null), [choice, setChoice] = useState(''), [error, setError] = useState(''), [notice, setNotice] = useState('');
  const [needsRead, setNeedsRead] = useState(false), [savedReasoning, setSavedReasoning] = useState<Row | null>(null);
  const [editingLevels, setEditingLevels] = useState(false), [declaredLevels, setDeclaredLevels] = useState<string[]>([]);
  const [refreshAfterDeclaration, setRefreshAfterDeclaration] = useState(false);
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
      const provider = String(value.nativeSelection?.provider || ''), modelId = String(value.nativeSelection?.model || '');
      const choices = reasoningChoices(record(value.facts), provider, selected, modelId, Number(value.nativeSelection?.maxOutputTokens));
      const fact = record(record(value.facts).REASONING_LEVELS), available = provider === 'gemini' && geminiBudgetModel(modelId)
        ? ['low', 'medium', 'high'] : reasoningCandidates[provider] || [];
      setDeclaredLevels(fact.known === true && Array.isArray(fact.value)
        ? fact.value.filter((level: any) => available.includes(level)) : ['low', 'medium', 'high'].filter(level => available.includes(level)));
      setEditingLevels(false);
      setReview({ ...value, choices, originalStamp: original, expiresAt: Date.now() + value.expiresInMs }); setNeedsRead(false);
      setChoice(choices.find(item => canonical(item.value) === canonical(selected))?.id || '');
    } catch (failure) { if (alive.current && request === reads.current) { setNeedsRead(true); setError(failure instanceof Error ? failure.message : '推理设置暂不可读取。'); } }
    finally { if (alive.current && request === reads.current) setLoading(false); }
  };
  useEffect(() => {
    if (previousScope.current !== preference.scopeKey) {
      retire(); confirmed.current = null; setSaving(false); setNotice(''); setSavedReasoning(null); setNeedsRead(true); setError('对话已切换，请重新读取模型。');
    } else if (refreshAfterDeclaration && !pending.current && !busy(state)) {
      setRefreshAfterDeclaration(false); void read(true);
    } else if (!pending.current && !busy(state) && confirmed.current && preference.revision === confirmed.current.revision) {
      void read(true);
    } else if (!started.current && !busy(state)) {
      void read();
    } else if (previous.current !== current && !pending.current && !confirmed.current) {
      retire(); setNeedsRead(true); setError(old => old || '此对话模型已变化，请重新读取。');
    }
    previousScope.current = preference.scopeKey; previous.current = current;
  }, [current, state.modelSelectionBusy, saving, refreshAfterDeclaration]);
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
  const manualProvider = String(review?.nativeSelection?.provider || '');
  const availableLevels = manualProvider === 'gemini' && geminiBudgetModel(String(review?.nativeSelection?.model || ''))
    ? ['low', 'medium', 'high'] : reasoningCandidates[manualProvider] || [];
  const mayDeclare = !!review && !review.restriction && availableLevels.length > 0 &&
    Number.isSafeInteger(review.reasoningOwnerRevision) && review.reasoningOwnerRevision >= 0 &&
    review.nativeSelection?.modelRoute === 'api_key' && can('models.conversation.reasoning.declare');
  const customLevelsSaved = record(record(review?.facts).REASONING_LEVELS).source === 'OWNER_OVERRIDE';
  const declare = async (levels: string[]) => {
    if (!mayDeclare || locked || loading || needsRead || pending.current || !review) return;
    if (Date.now() >= review.expiresAt || review.originalStamp !== conversationModelStamp(latest.current.state)) {
      setReview(null); setNeedsRead(true); setError('此对话模型已变化，请重新读取。'); return;
    }
    if (levels.length && (!levels.every(level => availableLevels.includes(level)) || new Set(levels).size !== levels.length)) {
      setError('请核对所选档位。'); return;
    }
    const original = review, request = ++reads.current;
    pending.current = true; setSaving(true); setError(''); setNotice(''); latest.current.onSelectionPending(true);
    try {
      const receipt = await latest.current.act('models.conversation.reasoning.declare', {
        reviewHandle: original.reviewHandle, levels,
      });
      if (!alive.current || request !== reads.current || record(latest.current.state.conversationModel).scopeKey !== original.scopeKey) return;
      if (receipt?.status !== 'saved' || receipt?.modelId !== original.nativeSelection?.model ||
          !Number.isSafeInteger(receipt?.ownerRevision)) throw new Error('模型的可用档位保存回执不可核对。');
      setReview(null); setEditingLevels(false); setNeedsRead(true); setNotice('档位已保存');
      setRefreshAfterDeclaration(true);
    } catch (failure) {
      if (alive.current && request === reads.current) {
        setReview(null); setNeedsRead(true);
        setError(`档位保存结果待核对：${failure instanceof Error ? failure.message : '请重新读取。'}`);
      }
    } finally {
      pending.current = false; latest.current.onSelectionPending(false);
      if (alive.current && request === reads.current) setSaving(false);
    }
  };
  const onlyServiceDefault = review?.choices.length === 1 && review.choices[0].value.mode === 'DEFAULT';
  return <section className="pw-scoped-reasoning-panel" aria-label="此对话推理设置">
    <p className="pw-model-selection-scope">{preference.inherited === true ? '沿用默认 · ' : '此对话 · '}{conversationModelName(state) || '尚未选择模型'}</p>
    {loading ? <div role="status" aria-busy="true"><ShimmerText>正在读取推理设置…</ShimmerText></div> : review?.restriction ? <p className="pw-conversation-reasoning-note">{review.restriction}</p> : review && <>
      {onlyServiceDefault ? <div className="pw-conversation-reasoning-default"><strong>自动</strong><p>{mayDeclare ? '可为当前模型设置低、中、高等档位。' : reasoningAvailability(record(review.facts))}</p></div>
        : <RadioGroup aria-label="可用推理强度" value={choice} onChange={id => void apply(id)} isDisabled={locked || needsRead} className="pw-conversation-reasoning-options">{review.choices.map((item: Row) => <Radio key={item.id} value={item.id} isDisabled={item.readOnly} className="pw-conversation-reasoning-option"><span><b>{item.label}</b>{item.detail && <small>{item.detail}</small>}</span></Radio>)}</RadioGroup>}
      {mayDeclare && !editingLevels && <Button variant="ghost" size="small" className="pw-reasoning-levels-trigger" disabled={locked || needsRead} onClick={() => setEditingLevels(true)}>{customLevelsSaved ? '调整自定义档位' : '自定义档位'}</Button>}
      {mayDeclare && editingLevels && <section className="pw-reasoning-levels-editor" aria-label="手动声明推理档位">
        <p>选择这个模型实际支持的档位。保存后可在上方切换。</p>
        <div className="pw-reasoning-levels-choices">{availableLevels.map(level =>
          <Button key={level} size="small" variant={declaredLevels.includes(level) ? 'secondary' : 'ghost'} aria-pressed={declaredLevels.includes(level)} disabled={locked || needsRead}
            onClick={() => setDeclaredLevels(old => old.includes(level) ? old.filter(item => item !== level) : availableLevels.filter(item => item === level || old.includes(item)))}>
            {effortNames[level] || level}
          </Button>)}</div>
        <p>仅保存用户声明，服务端是否接受取决于实际模型。不会替你测试或改换模型。</p>
        <div className="pw-reasoning-levels-actions">
          <Button size="small" variant="ghost" disabled={locked} onClick={() => setEditingLevels(false)}>取消</Button>
          {customLevelsSaved && <Button size="small" variant="ghost" disabled={locked || needsRead} onClick={() => void declare([])}>恢复自动</Button>}
          <Button size="small" disabled={locked || needsRead || declaredLevels.length === 0} onClick={() => void declare(declaredLevels)}>保存档位</Button>
        </div>
      </section>}
    </>}
    {saving && <p role="status" aria-busy="true" className="pw-model-selection-status">正在保存推理…</p>}
    {notice && <p role="status" className="pw-model-selection-status">{notice}</p>}
    {savedReasoning && !review && !loading && <p className="pw-conversation-reasoning-current">当前推理 <b>{reasoningLabel(nativeReasoning(savedReasoning))}</b></p>}
    {error && <p role="alert" className="pw-conversation-reasoning-error">{error}</p>}
    <div className="pw-scoped-reasoning-actions">
      {(error || needsRead) && <Button variant="secondary" onClick={() => void read()} disabled={locked || loading}>重新读取</Button>}
    </div>
  </section>;
}
