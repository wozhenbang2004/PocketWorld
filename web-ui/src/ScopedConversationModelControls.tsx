import { useEffect, useRef, useState } from 'react';
import { RiArrowDownSLine, RiBrainLine, RiCloseLine, RiCpuLine } from '@remixicon/react';
import { Button, IconButton } from './forms-library';
import { Radio, RadioGroup } from './forms-library/boardui/components/base/radio/radio';
import { Drawer } from './shell-library';
import { ShimmerText } from './library';
import { nativeReasoning, reasoningChoices, reasoningLabel } from './conversation-model-controls';
import { conversationModelName, conversationSelectionReceipt } from './conversation-model-selection';
import type { ConversationModelControlsProps } from './ConversationModelControls';

type Row = Record<string, any>;
const record = (value: any): Row => value && typeof value === 'object' && !Array.isArray(value) ? value : {};
const stamp = (state: Row) => JSON.stringify([state.modelSelectionRevision, state.conversationModel?.scopeKey, state.conversationModel?.revision]);
const canonical = (value: any): string => JSON.stringify(value && typeof value === 'object' ? Array.isArray(value) ? value.map(item => JSON.parse(canonical(item))) : Object.fromEntries(Object.keys(value).sort().map(key => [key, JSON.parse(canonical(value[key]))])) : value ?? null);
function reviewCurrent(value: Row, state: Row): boolean {
  const preference = record(state.conversationModel), target = record(preference.target), native = record(value.nativeSelection);
  if (value.scopeKey !== preference.scopeKey || value.revision !== preference.revision || value.selectionRevision !== state.modelSelectionRevision || value.inherited !== preference.inherited || canonical(value.target) !== canonical(preference.target)) return false;
  if (target.kind === 'api' && (native.modelRoute !== 'api_key' || native.model !== target.modelId || record(native.capabilityBinding).connectionId !== target.connectionId || target.binding && canonical(native.capabilityBinding) !== canonical(target.binding))) return false;
  if (target.kind === 'account' && (native.modelRoute !== 'account' || native.model !== record(target.nativeRef).modelId || canonical(native.nativeRef) !== canonical(target.nativeRef))) return false;
  if (target.kind === 'group') return record(native.modelRoutePlan).requestedAlias === target.groupId && record(native.modelRoutePlan).groupRevision === target.groupRevision;
  return !target.kind || canonical(native.reasoning) === canonical(target.reasoning) && native.maxOutputTokens === target.outputTokens && native.imageTokenReserve === target.imageTokenReserve;
}
export function ScopedConversationModelControls({ state, can, act, onOpenModel, disabled = false, registerBackHandler }: ConversationModelControlsProps) {
  const [open, setOpen] = useState(false), [loading, setLoading] = useState(false), [saving, setSaving] = useState(false);
  const [review, setReview] = useState<Row | null>(null), [choice, setChoice] = useState(''), [error, setError] = useState('');
  const [defaultReasoning, setDefaultReasoning] = useState<Row | null>(null);
  const [needsRead, setNeedsRead] = useState(false);
  const alive = useRef(false), visible = useRef(false), pending = useRef(false), reads = useRef(0);
  const latest = useRef({ state, act }); latest.current = { state, act };
  const preference = record(state.conversationModel), target = record(preference.target);
  const current = stamp(state), previous = useRef(current), modelName = conversationModelName(state) || '选择模型';
  const previousScope = useRef(preference.scopeKey);
  const locked = disabled || saving || !!state.modelSelectionBusy || !!state.chatSubmitting || !!state.phoneSubmitting || !!state.modelSaving || !!state.modelTesting;
  const reasoning = nativeReasoning(target.reasoning || defaultReasoning), effort = reasoningLabel(reasoning, true);
  const retire = () => {
    visible.current = false; reads.current++; setOpen(false); setReview(null); setLoading(false);
    void latest.current.act('models.conversation.close').catch(() => {});
  };
  const close = () => { if (!pending.current) retire(); };
  useEffect(() => { alive.current = true; return () => { alive.current = false; reads.current++; if (visible.current) void latest.current.act('models.conversation.close').catch(() => {}); }; }, []);
  useEffect(() => {
    if (previousScope.current !== preference.scopeKey) {
      if (visible.current || pending.current) retire();
      setError(''); setNeedsRead(false);
    } else if (previous.current !== current && visible.current && !pending.current) { reads.current++; setLoading(false); setReview(null); setError('此对话模型已变化，请重新读取。'); }
    previousScope.current = preference.scopeKey;
    previous.current = current;
    if (preference.inherited !== true || !state.modelName || state.modelSelectionBusy || !can('models.snapshot')) { setDefaultReasoning(null); return; }
    let active = true;
    void act('models.snapshot').then(value => { if (active) setDefaultReasoning(record(value?.nativeSelection).reasoning || null); }).catch(() => { if (active) setDefaultReasoning(null); });
    return () => { active = false; };
  }, [current, preference.inherited, state.modelName, state.modelSelectionBusy]);
  useEffect(() => { registerBackHandler?.(() => { if (!visible.current) return false; close(); return true; }); return () => registerBackHandler?.(null); }, [registerBackHandler]);
  useEffect(() => {
    if (!review || saving) return;
    const timer = window.setTimeout(() => { setReview(null); setError('原推理审阅已过期，请重新读取。'); }, Math.max(0, review.expiresAt - Date.now()));
    return () => window.clearTimeout(timer);
  }, [review, saving]);
  const read = async () => {
    if (pending.current) return;
    const request = ++reads.current, original = stamp(latest.current.state);
    setLoading(true); setReview(null); setError('');
    try {
      const value = await act('models.conversation.review');
      if (!alive.current || !visible.current || request !== reads.current) return;
      if (original !== stamp(latest.current.state) || !reviewCurrent(record(value), latest.current.state) ||
          typeof value?.reviewHandle !== 'string' || !value.reviewHandle.trim() || !Number.isSafeInteger(value.expiresInMs) || value.expiresInMs <= 0 || value.expiresInMs > 120_000)
        throw new Error('原对话模型审阅已变化，请重新读取。');
      if (value.restriction || record(value.target).kind === 'group') {
        setReview({ ...value, restriction: value.restriction || '模型组按各候选的原生参数执行，请在 AI 中心管理候选。', choices: [], originalStamp: original, expiresAt: Date.now() + value.expiresInMs });
        setChoice(''); setNeedsRead(false); return;
      }
      const selected = nativeReasoning(value.nativeSelection?.reasoning);
      if (!selected) throw new Error('原模型参数尚未读取。');
      const choices = reasoningChoices(record(value.facts), String(value.nativeSelection?.provider || ''), selected);
      setReview({ ...value, choices, originalStamp: original, expiresAt: Date.now() + value.expiresInMs }); setNeedsRead(false);
      setChoice(choices.find(item => JSON.stringify(item.value) === JSON.stringify(selected))?.id || choices[0]?.id || '');
    } catch (failure) { if (alive.current && request === reads.current) setError(failure instanceof Error ? failure.message : '推理设置暂不可读取。'); }
    finally { if (alive.current && request === reads.current) setLoading(false); }
  };
  const begin = () => { if (locked || !can('models.conversation.review')) return; visible.current = true; setOpen(true); void read(); };
  const apply = async () => {
    const selected = review?.choices.find((item: Row) => item.id === choice && !item.readOnly);
    if (pending.current || locked || loading || needsRead || !review || review.restriction || !selected || Date.now() >= review.expiresAt || !can('models.conversation.reasoning')) return;
    if (review.originalStamp !== stamp(latest.current.state)) { setReview(null); setError('此对话模型已变化，请重新读取。'); return; }
    const originalScope = review.scopeKey, originalRevision = review.revision;
    pending.current = true; setSaving(true); setError('');
    try {
      const receipt = await act('models.conversation.reasoning', { reviewHandle: review.reviewHandle, reasoning: selected.value });
      const saved = conversationSelectionReceipt(receipt, originalScope, originalRevision), latestPreference = record(latest.current.state.conversationModel);
      if (!alive.current || latestPreference.scopeKey !== originalScope) return;
      if (![originalRevision, saved.revision].includes(latestPreference.revision)) throw new Error('此对话模型已变化，请重新读取。');
      retire();
    } catch (failure) { if (alive.current && record(latest.current.state.conversationModel).scopeKey === originalScope) { setReview(null); setNeedsRead(true); setError(`${failure instanceof Error ? failure.message : '保存结果待核对。'} 没有自动重试。`); } }
    finally { pending.current = false; if (alive.current) setSaving(false); }
  };
  const reset = async () => {
    if (pending.current || locked || needsRead || preference.inherited === true || !can('models.conversation.reset')) return;
    if (current !== stamp(latest.current.state)) return;
    const originalScope = preference.scopeKey, originalRevision = preference.revision;
    pending.current = true; setSaving(true); setError('');
    try {
      const value = await act('models.conversation.reset', { expectedPreferenceRevision: preference.revision, expectedSelectionRevision: state.modelSelectionRevision });
      const saved = conversationSelectionReceipt(value, originalScope, originalRevision), latestPreference = record(latest.current.state.conversationModel);
      if (!alive.current || latestPreference.scopeKey !== originalScope) return;
      if (![originalRevision, saved.revision].includes(latestPreference.revision)) throw new Error('此对话模型已变化，请重新读取。');
      retire();
    } catch (failure) { if (alive.current && record(latest.current.state.conversationModel).scopeKey === originalScope) { setReview(null); setNeedsRead(true); setError(`${failure instanceof Error ? failure.message : '恢复默认的结果待核对。'} 没有自动重试。`); } }
    finally { pending.current = false; if (alive.current) setSaving(false); }
  };
  const changed = review && choice && review.choices.find((item: Row) => item.id === choice && !item.readOnly) &&
    JSON.stringify(review.choices.find((item: Row) => item.id === choice)?.value) !== JSON.stringify(nativeReasoning(review.nativeSelection?.reasoning));
  return <div className="pw-conversation-model-controls" aria-label="此对话的模型与推理">
    <Button variant="ghost" className="pw-conversation-model-chip" onClick={onOpenModel} disabled={locked} aria-label={`切换此对话模型：${modelName}`} title={modelName}>
      <RiCpuLine size={16} aria-hidden="true" /><span className="pw-conversation-model-chip-name">{modelName}</span><RiArrowDownSLine size={14} aria-hidden="true" />
    </Button>
    <Drawer.Root open={open} onOpenChange={value => value ? begin() : close()} dismissible={!saving} shouldScaleBackground={false}>
      <Drawer.Trigger asChild><Button variant="ghost" className="pw-conversation-reasoning-chip" disabled={locked || !conversationModelName(state)} aria-label={`推理强度${effort ? `：${effort}` : ''}`}>
        <RiBrainLine size={17} aria-hidden="true" />{effort && <span>{effort}</span>}
      </Button></Drawer.Trigger>
      <Drawer.Portal><Drawer.Overlay className="pw-overlay" /><Drawer.Content className="pw-drawer pw-conversation-reasoning-drawer" aria-describedby="pw-conversation-reasoning-model">
        <div className="pw-drawer-handle" /><header className="pw-conversation-reasoning-header"><div><Drawer.Title>推理强度</Drawer.Title><Drawer.Description id="pw-conversation-reasoning-model">{modelName} · 此对话</Drawer.Description></div><IconButton icon={RiCloseLine} aria-label="关闭推理设置" onClick={close} disabled={saving} /></header>
        <div className="pw-conversation-reasoning-body">
          {loading ? <div role="status"><ShimmerText>正在读取模型参数…</ShimmerText></div> : saving ? <p role="status">正在保存…</p> : review?.restriction ? <p className="pw-conversation-reasoning-note">{review.restriction}</p> : review && <RadioGroup aria-label="可用推理强度" value={choice} onChange={setChoice} isDisabled={locked} className="pw-conversation-reasoning-options">{review.choices.map((item: Row) => <Radio key={item.id} value={item.id} isDisabled={item.readOnly} className="pw-conversation-reasoning-option"><span><b>{item.label}</b>{item.detail && <small>{item.detail}</small>}</span></Radio>)}</RadioGroup>}
          <p className="pw-conversation-reasoning-note">{state.chatBusy ? '正在进行的任务沿用原模型；修改用于此对话的下一项新任务。' : '用于此对话之后开始的新任务。'}</p>
          {error && <p role="alert" className="pw-conversation-reasoning-error">{error}</p>}
          {preference.inherited === false && can('models.conversation.reset') && <Button variant="ghost" onClick={() => void reset()} disabled={locked || needsRead}>恢复使用默认模型</Button>}
        </div>
        <footer className="pw-conversation-reasoning-actions"><Button variant="ghost" onClick={close} disabled={saving}>关闭</Button>{error ? <Button variant="secondary" onClick={() => void read()} disabled={locked || loading}>重新读取</Button> : <Button onClick={() => void apply()} disabled={locked || loading || needsRead || !changed || !!review?.restriction || !can('models.conversation.reasoning')}>应用到此对话</Button>}</footer>
      </Drawer.Content></Drawer.Portal>
    </Drawer.Root>
  </div>;
}
