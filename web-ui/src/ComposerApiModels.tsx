import { useEffect, useRef, useState } from 'react';
import { ModelPicker, ShimmerText } from './library';
import { Button, RadioOptions } from './forms-library';
import { PermissionReview } from './shell-library';
import { apiCapability, apiModelFavorite, apiModelKey, apiModelProjectionCurrent, apiModelReviewCurrent, apiModelReviewRequest, apiModelRows, apiModelSelection, apiModelUnavailable, type ApiModelFilter, type ApiModelRecord } from './composer-api-models';
import './composer-models.css';
import './model-picker-depth.css';

type Props = { state: ApiModelRecord; can: (action: string) => boolean; act: (action: string, args?: ApiModelRecord) => Promise<any>;
  onSelected: () => void; onSelectionPending: (pending: boolean) => void };
/** The saved picker and review surfaces present the native cross-connection catalog. */
export function ComposerApiModels({ state, can, act, onSelected, onSelectionPending }: Props) {
  const [projection, setProjection] = useState<ApiModelRecord | null>(null), [review, setReview] = useState<ApiModelRecord | null>(null);
  const [loading, setLoading] = useState(false), [writing, setWriting] = useState(false), [error, setError] = useState(''), [needsRead, setNeedsRead] = useState(false);
  const [filter, setFilter] = useState<ApiModelFilter>('all');
  const mounted = useRef(false), request = useRef(0), windowId = useRef(''), pending = useRef(false), observedRevision = useRef(state.modelSelectionRevision);
  const callbacks = useRef({ act, onSelected, onSelectionPending }); callbacks.current = { act, onSelected, onSelectionPending };
  const supported = ['open', 'close', 'review', 'select', 'favorite'].every(command => can(`models.api.${command}`));
  const locked = loading || writing || !!state.modelSelectionBusy || !!state.chatSubmitting || !!state.phoneSubmitting || !!state.modelSaving || !!state.modelTesting;
  const closeWindow = (id: string) => { if (id) void callbacks.current.act('models.api.close', { windowId: id }).catch(() => {}); };
  const readModels = async () => {
    if (pending.current) return;
    const original = ++request.current; setLoading(true); setError(''); setReview(null); setProjection(null); setNeedsRead(false);
    const retiring = windowId.current; windowId.current = ''; closeWindow(retiring);
    try {
      const value = await callbacks.current.act('models.api.open');
      if (!mounted.current || original !== request.current) { closeWindow(value?.windowId); return; }
      if (!apiModelProjectionCurrent(value, value?.selectionRevision)) throw new Error('原模型列表未能完整读取，请重新打开。');
      windowId.current = value.windowId; setProjection(value);
    } catch (failure) { if (mounted.current && original === request.current) { setError(failure instanceof Error ? failure.message : '模型列表暂不可读取。'); setNeedsRead(true); } }
    finally { if (mounted.current && original === request.current) setLoading(false); }
  };
  useEffect(() => {
    mounted.current = true;
    if (supported) void readModels();
    return () => { mounted.current = false; request.current++; const retiring = windowId.current; windowId.current = ''; closeWindow(retiring); };
  }, [supported]);
  useEffect(() => {
    if (observedRevision.current !== state.modelSelectionRevision && !pending.current) {
      request.current++; setReview(null); setProjection(null); setLoading(false); setNeedsRead(true); setError('模型设置已变化，请重新读取连接与模型。');
    }
    observedRevision.current = state.modelSelectionRevision;
  }, [state.modelSelectionRevision]);
  const reviewModel = async (key: string) => {
    if (locked || pending.current || !projection || needsRead) return;
    let args: ApiModelRecord;
    try { args = apiModelReviewRequest(projection, state.modelSelectionRevision, key); }
    catch (failure) { setError((failure as Error).message); setNeedsRead(true); return; }
    const original = ++request.current; setLoading(true); setError('');
    try {
      const value = await act('models.api.review', args);
      if (mounted.current && original === request.current) {
        if (!apiModelReviewCurrent(value, projection, state.modelSelectionRevision)) throw new Error('原连接审核已变化，请重新读取。');
        setReview(value);
      }
    } catch (failure) { if (mounted.current && original === request.current) { setReview(null); setNeedsRead(true); setError(failure instanceof Error ? failure.message : '原模型审核暂不可读取。'); } }
    finally { if (mounted.current && original === request.current) setLoading(false); }
  };
  let selection: ApiModelRecord | null = null, favorite: ApiModelRecord | null = null;
  try { selection = apiModelSelection(review, projection, state.modelSelectionRevision); } catch { /* Original review and availability are required. */ }
  try { favorite = apiModelFavorite(review, projection, state.modelSelectionRevision); } catch { /* Unavailable favorites may only be removed. */ }
  const write = async (kind: 'select' | 'favorite') => {
    const args = kind === 'select' ? selection : favorite;
    if (locked || needsRead || pending.current || !args) return;
    const original = ++request.current; pending.current = true; setWriting(true); setError(''); callbacks.current.onSelectionPending(true);
    let reread = false;
    try {
      const result = await act(`models.api.${kind}`, args);
      if (result?.status !== 'saved') throw new Error('原模型操作尚未确认，请重新读取；不会自动重试。');
      if (mounted.current && original === request.current) {
        if (kind === 'select') callbacks.current.onSelected(); else reread = true;
      }
    } catch (failure) {
      if (mounted.current && original === request.current) { setReview(null); setProjection(null); setNeedsRead(true); setError((failure instanceof Error ? failure.message : '原模型操作结果待核对。') + ' 请重新读取当前选择或收藏后核对，没有自动重试。'); }
    } finally {
      pending.current = false;
      if (mounted.current && original === request.current) {
        setWriting(false); callbacks.current.onSelectionPending(false);
        // A fresh stable read obtains its own revision; the old mutation result is never re-stamped.
        if (reread) void readModels();
      }
    }
  };
  const current = apiModelProjectionCurrent(projection, state.modelSelectionRevision), row = review?.model;
  const models = apiModelRows(projection, state.modelSelectionRevision, filter), selected = apiModelRows(projection, state.modelSelectionRevision).find(row => row.selected === true);
  const recent = (row: ApiModelRecord) => typeof row.lastUsedAt === 'number' && Number.isFinite(row.lastUsedAt) && row.lastUsedAt > 0 ? new Date(row.lastUsedAt).toLocaleString('zh-CN') : '';
  if (!supported) return <p className="pw-muted">当前界面尚未接通跨连接模型选择，请在模型管理中核对。</p>;
  return <section className="pw-model-picker-depth pw-api-picker-depth" aria-label="API模型选择">
    {loading ? <div className="pw-loading-models" aria-busy="true"><ShimmerText>正在读取连接与模型</ShimmerText></div> : review && row ? <PermissionReview
      title={String(row.modelId)} context={`API 连接：${row.connectionName || row.connectionId}`}
      reason={row.available ? '此选择用于之后开始的任务，已有任务保留原模型。模型能力与工具授权分别核对。' : apiModelUnavailable(row)}
      fields={[{ label: '图片输入', value: apiCapability(row.imageInput) }, { label: '工具调用', value: apiCapability(row.toolCalls) },
        { label: '目录状态', value: row.catalogFresh === true ? '已读取' : '尚未获取或已过期，保留原能力记录' }, { label: '最近成功使用', value: recent(row) || '尚无成功使用记录' }]}
      pending={writing || !!state.modelSelectionBusy} onApprove={!locked && selection && !needsRead ? () => void write('select') : undefined}
      onReject={() => { if (!writing) setReview(null); }} approveLabel="确认切换主模型" rejectLabel="返回模型目录">
      {!apiModelReviewCurrent(review, projection, state.modelSelectionRevision) && <p className="pw-error" role="alert">原模型记录已变化，请重新读取。</p>}
      <div className="pw-action-row"><Button variant="secondary" disabled={locked || !favorite || needsRead} onClick={() => void write('favorite')}>{row.favorite ? '取消收藏' : '收藏此模型'}</Button></div>
    </PermissionReview> : current && !needsRead ? <>
      <RadioOptions label="模型列表" value={filter} isDisabled={locked} options={[{ id: 'all', label: '全部' }, { id: 'favorites', label: '收藏' }, { id: 'recent', label: '最近使用' }]} onChange={value => setFilter(value as ApiModelFilter)} />
      <ModelPicker label="API 连接与模型" models={models.map(row => ({ id: apiModelKey(row), name: String(row.modelId), disabled: locked,
        description: [row.connectionName || row.connectionId, [row.selected ? '当前使用' : '', row.favorite ? '已收藏' : '', recent(row) ? '最近使用' : ''].filter(Boolean).join(' · '), apiModelUnavailable(row), `图片：${apiCapability(row.imageInput)} · 工具：${apiCapability(row.toolCalls)}`].filter(Boolean).join('\n') }))}
        value={selected ? apiModelKey(selected) : undefined} onChange={reviewModel} />
      {models.length === 0 && <p className="pw-muted">{filter === 'favorites' ? '尚无收藏模型。可在全部列表中打开模型并收藏。' : filter === 'recent' ? '尚无成功使用记录。选择模型不会新增使用记录。' : '尚无 API 连接，请先在连接管理中添加。'}</p>}
      {projection?.preferencesAvailable !== true && <p className="pw-muted">收藏与最近使用记录暂不可读取，请重新读取后核对。</p>}
      {projection?.recentUseSaveFailed === true && <p className="pw-muted">最近一次成功使用的记录未能保存，列表可能尚未更新。</p>}
    </> : null}
    {error && <p className="pw-error" role="alert">{error}</p>}
    {!review && <div className="pw-action-row"><Button variant="secondary" disabled={locked} onClick={() => void readModels()}>重新读取模型</Button></div>}
  </section>;
}
