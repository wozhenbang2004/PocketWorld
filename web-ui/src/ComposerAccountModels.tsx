import { useEffect, useRef, useState } from 'react';
import { ModelPicker, ShimmerText } from './library';
import { Button, SelectField, TextField } from './forms-library';
import { PermissionReview } from './shell-library';
import { accountModelAccountRows, accountModelCatalog, accountModelProjectionCurrent, accountModelReadRequest,
  accountModelReasoningLevels, accountModelReviewCurrent, accountModelReviewRequest, accountModelSelection,
  type AccountModelOptions, type AccountModelRecord } from './composer-account-models';

type Props = { state: AccountModelRecord; can: (action: string) => boolean; act: (action: string, args?: AccountModelRecord) => Promise<any>;
  onSelected: () => void; onSelectionPending: (pending: boolean) => void };
/** Uses the same saved ModelPicker, review and form components as the API and account settings pages. */
export function ComposerAccountModels({ state, can, act, onSelected, onSelectionPending }: Props) {
  const [projection, setProjection] = useState<AccountModelRecord | null>(null), [review, setReview] = useState<AccountModelRecord | null>(null);
  const [loading, setLoading] = useState(false), [selecting, setSelecting] = useState(false), [error, setError] = useState(''), [needsRead, setNeedsRead] = useState(false);
  const [options, setOptions] = useState<AccountModelOptions>({ reasoningEffort: '', outputReserve: '4096', imageTokenReserve: '0' });
  const mounted = useRef(false), request = useRef(0), windowId = useRef(''), pending = useRef(false);
  const callbacks = useRef({ act, onSelected, onSelectionPending }); callbacks.current = { act, onSelected, onSelectionPending };
  const observedRevision = useRef(state.modelSelectionRevision);
  const supported = ['open', 'close', 'models', 'review', 'select'].every(command => can(`models.accounts.${command}`));
  const locked = loading || selecting || !!state.modelSelectionBusy || !!state.chatSubmitting || !!state.phoneSubmitting || !!state.modelSaving || !!state.modelTesting;
  const closeWindow = (id: string) => { if (id) void callbacks.current.act('models.accounts.close', { windowId: id }).catch(() => {}); };
  const readAccounts = async () => {
    if (pending.current) return;
    const original = ++request.current; setLoading(true); setError(''); setReview(null); setProjection(null); setNeedsRead(false);
    const retiring = windowId.current; windowId.current = ''; closeWindow(retiring);
    try {
      const value = await callbacks.current.act('models.accounts.open');
      if (!mounted.current || original !== request.current) { closeWindow(value?.windowId); return; }
      if (typeof value?.windowId !== 'string') throw new Error('原聊天模型窗口未能读取，请重新打开。');
      windowId.current = value.windowId; setProjection(value);
    } catch (failure) { if (mounted.current && original === request.current) { setError(failure instanceof Error ? failure.message : '账号状态暂不可读取。'); setNeedsRead(true); } }
    finally { if (mounted.current && original === request.current) setLoading(false); }
  };
  useEffect(() => {
    mounted.current = true;
    if (supported) void readAccounts();
    return () => { mounted.current = false; request.current++; const retiring = windowId.current; windowId.current = ''; closeWindow(retiring); };
  }, [supported]);
  useEffect(() => {
    if (observedRevision.current !== state.modelSelectionRevision && !pending.current) {
      request.current++; setReview(null); setProjection(null); setLoading(false); setNeedsRead(true); setError('模型设置已在其他页面变化，请重新读取账号状态。');
    }
    observedRevision.current = state.modelSelectionRevision;
  }, [state.modelSelectionRevision]);
  const readModels = async (accountId: string) => {
    if (locked || !projection || needsRead) return;
    let args: AccountModelRecord;
    try { args = accountModelReadRequest(projection, state.modelSelectionRevision, accountId); }
    catch (failure) { setError((failure as Error).message); setNeedsRead(true); return; }
    const original = ++request.current; setLoading(true); setError(''); setReview(null);
    try { const value = await act('models.accounts.models', args); if (mounted.current && original === request.current) setProjection(value); }
    catch (failure) { if (mounted.current && original === request.current) { setProjection(null); setNeedsRead(true); setError(failure instanceof Error ? failure.message : '原模型目录读取结果待核对。'); } }
    finally { if (mounted.current && original === request.current) setLoading(false); }
  };
  const reviewModel = async (modelId: string) => {
    if (locked || !projection || needsRead) return;
    let args: AccountModelRecord;
    try { args = accountModelReviewRequest(projection, state.modelSelectionRevision, modelId); }
    catch (failure) { setError((failure as Error).message); setNeedsRead(true); return; }
    const original = ++request.current; setLoading(true); setError('');
    try {
      const value = await act('models.accounts.review', args);
      if (mounted.current && original === request.current) {
        setReview(value); setOptions({ reasoningEffort: String(value.options?.reasoningEffort || ''), outputReserve: String(value.options?.outputReserve ?? 4096), imageTokenReserve: String(value.options?.imageTokenReserve ?? 0) });
      }
    } catch (failure) { if (mounted.current && original === request.current) { setReview(null); setNeedsRead(true); setError(failure instanceof Error ? failure.message : '原模型审核未能读取。'); } }
    finally { if (mounted.current && original === request.current) setLoading(false); }
  };
  let selection: AccountModelRecord | null = null;
  try { selection = accountModelSelection(review, state.modelSelectionRevision, options); } catch { /* The button remains unavailable until the original review and options are valid. */ }
  const confirm = async () => {
    if (locked || !selection || needsRead || pending.current) return;
    pending.current = true; setSelecting(true); callbacks.current.onSelectionPending(true); setError('');
    try {
      const result = await act('models.accounts.select', selection);
      if (result?.status !== 'saved') throw new Error('原模型选择尚未确认，请重新读取账号状态；不会自动重试。');
      if (mounted.current) callbacks.current.onSelected();
    } catch (failure) {
      if (mounted.current) { setReview(null); setProjection(null); setNeedsRead(true); setError((failure instanceof Error ? failure.message : '原模型选择结果待核对。') + ' 请重新读取当前选择后核对，没有自动重选。'); }
    } finally { pending.current = false; if (mounted.current) setSelecting(false); callbacks.current.onSelectionPending(false); }
  };
  const current = accountModelProjectionCurrent(projection, state.modelSelectionRevision), catalog = accountModelCatalog(projection, state.modelSelectionRevision);
  const accounts = accountModelAccountRows(projection, state.modelSelectionRevision), selected = projection?.accountSnapshot?.selectedModelRef;
  const levels = accountModelReasoningLevels(review), facts = review?.catalogFacts?.facts || {};
  const known = (key: string) => facts[key]?.known === true ? facts[key].value : undefined;
  if (!supported) return <p className="pw-muted">当前界面尚未接通聊天内账号模型选择，请先在账号管理中选择。</p>;
  return <>
    {loading ? <div className="pw-loading-models" aria-busy="true"><ShimmerText>正在读取原账号模型记录</ShimmerText></div> : review ? <PermissionReview
      title={String(review.displayName || review.modelId)} context={`ChatGPT 账号：${review.accountLabel || '原账号'}`}
      reason="此选择用于之后开始的任务，已有任务保留原模型。模型目录与账号授权仍以实际状态为准。"
      fields={[{ label: '目录上下文', value: known('CONTEXT_WINDOW')?.tokens ? `${known('CONTEXT_WINDOW').tokens} tokens` : '未声明' },
        { label: '图片输入', value: known('IMAGE_INPUT') === undefined ? '未声明' : known('IMAGE_INPUT') ? '支持，仍需任务授权' : '不支持' }]}
      pending={selecting || !!state.modelSelectionBusy} onApprove={!locked && selection && !needsRead ? confirm : undefined}
      onReject={() => { if (!selecting) setReview(null); }} approveLabel="确认使用此账号模型" rejectLabel="返回模型目录">
      {!accountModelReviewCurrent(review, state.modelSelectionRevision) && <p role="alert" className="pw-error">原账号或模型设置已变化，请重新读取。</p>}
      <div className="pw-form-grid">
        <SelectField label="推理设置" value={options.reasoningEffort} options={[{ id: '', label: '服务默认' }, ...levels.map(level => ({ id: level, label: level }))]} onChange={value => setOptions(old => ({ ...old, reasoningEffort: value }))} isDisabled={selecting} />
        <TextField label="本地输出预留" inputMode="numeric" value={options.outputReserve} onChange={value => setOptions(old => ({ ...old, outputReserve: value.replace(/\D/g, '').slice(0, 5) }))} hint="128–32768 tokens，用于输入准入，不是服务端输出限制。" isDisabled={selecting} />
        <TextField label="每张图片本地预留" inputMode="numeric" value={options.imageTokenReserve} onChange={value => setOptions(old => ({ ...old, imageTokenReserve: value.replace(/\D/g, '').slice(0, 7) }))} hint="0–2000000；已知上下文时需配置后才可发送图片。" isDisabled={selecting} />
      </div>
    </PermissionReview> : current && !needsRead ? catalog ? <>
      <ModelPicker label={`${catalog.accountLabel}的模型`} models={catalog.models.map(model => ({ id: model.id, name: String(model.displayName || model.id), description: '来自此账号的实际模型目录', disabled: locked }))}
        value={state.modelRoute === 'account' && selected?.accountId === catalog.accountId ? selected.modelId : undefined} onChange={reviewModel} />
      <Button variant="ghost" disabled={locked} onClick={() => setProjection(old => old ? { ...old, catalog: undefined } : old)}>返回账号列表</Button>
    </> : <>
      <ModelPicker label="选择 ChatGPT 账号" models={accounts.map(row => ({ ...row, disabled: locked || row.disabled }))} value={state.modelRoute === 'account' ? selected?.accountId : undefined} onChange={readModels} />
      {accounts.length === 0 && <p className="pw-muted">尚无已连接账号。请先在账号管理中登录，再回来选择模型。</p>}
    </> : null}
    {error && <p className="pw-error" role="alert">{error}</p>}
    {!review && <div className="pw-action-row"><Button variant="secondary" disabled={locked} onClick={() => void readAccounts()}>重新读取账号状态</Button></div>}
  </>;
}
