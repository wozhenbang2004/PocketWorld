import { useEffect, useRef, useState } from 'react';
import { ArrowLeft, Check, ChevronRight, RefreshCw, Star } from 'lucide-react';
import { Accordion, AccordionItem, Button, SearchField, SelectField, TextField } from './forms-library';
import { Tabs, Tab, TabList, TabPanel } from './shell-library';
import { ShimmerText } from './library';
import { apiCapability, apiModelFavorite, apiModelKey, apiModelProjectionCurrent, apiModelReviewCurrent, apiModelReviewRequest, apiModelRows, apiModelSelection, apiModelUnavailable, type ApiModelFilter } from './composer-api-models';
import { accountModelAccountRows, accountModelCatalog, accountModelProjectionCurrent, accountModelReadRequest, accountModelReasoningLevels, accountModelReviewCurrent, accountModelReviewRequest, accountModelSelection, type AccountModelOptions } from './composer-account-models';
import './ai-center.css';

type Data = Record<string, any>;
type Run = (action: string, args?: Data) => Promise<any>;
export type AIModelTarget = { kind: 'api' | 'account' | 'group'; args: Data; selectionRevision: number; modelId: string; sourceLabel: string };
export type AIModelPickerProps = {
  state: Data; can: (action: string) => boolean; act: Run; scope?: 'conversation' | 'default';
  onSelectTarget?: (target: AIModelTarget) => Promise<any>; onSelected: () => void;
  onSelectionPending: (pending: boolean) => void; onManage?: () => void;
};

/** One visual catalog, with separate native API and account review identities. */
export function AIModelPicker({ state, can, act, scope = 'conversation', onSelectTarget, onSelected, onSelectionPending, onManage }: AIModelPickerProps) {
  const [api, setApi] = useState<Data | null>(null), [account, setAccount] = useState<Data | null>(null);
  const [groups, setGroups] = useState<Data[]>([]), [groupReview, setGroupReview] = useState<Data | null>(null);
  const [review, setReview] = useState<{ kind: 'api' | 'account'; value: Data } | null>(null);
  const [options, setOptions] = useState<AccountModelOptions>({ reasoningEffort: '', outputReserve: '4096', imageTokenReserve: '0' });
  const [query, setQuery] = useState(''), [filter, setFilter] = useState<ApiModelFilter>('all');
  const [loading, setLoading] = useState(false), [writing, setWriting] = useState(false), [needsRead, setNeedsRead] = useState(false);
  const [error, setError] = useState(''), [sourceErrors, setSourceErrors] = useState<string[]>([]);
  const alive = useRef(false), generation = useRef(0), pending = useRef(false), windows = useRef({ api: '', account: '' });
  const revision = useRef(state.modelSelectionRevision), latest = useRef({ act, onSelected, onSelectionPending });
  latest.current = { act, onSelected, onSelectionPending };
  const apiSupported = ['open', 'close', 'review', 'select', 'favorite'].every(name => can(`models.api.${name}`));
  const accountSupported = ['open', 'close', 'models', 'review', 'select'].every(name => can(`models.accounts.${name}`));
  const locked = loading || writing || !!state.modelSelectionBusy || !!state.chatSubmitting || !!state.phoneSubmitting || !!state.modelSaving || !!state.modelTesting;
  const close = (kind: 'api' | 'account', id: string) => {
    if (id) void latest.current.act(kind === 'api' ? 'models.api.close' : 'models.accounts.close', { windowId: id }).catch(() => {});
  };
  const refresh = async () => {
    if (pending.current) return;
    const request = ++generation.current;
    setLoading(true); setError(''); setSourceErrors([]); setNeedsRead(false); setReview(null); setGroupReview(null); setGroups([]); setApi(null); setAccount(null);
    const old = windows.current; windows.current = { api: '', account: '' }; close('api', old.api); close('account', old.account);
    const failures: string[] = [];
    for (const kind of ['api', 'account'] as const) {
      if (!alive.current || request !== generation.current) return;
      if (kind === 'api' ? !apiSupported : !accountSupported) continue;
      try {
        const value = await latest.current.act(kind === 'api' ? 'models.api.open' : 'models.accounts.open');
        if (!alive.current || request !== generation.current) { close(kind, value?.windowId); continue; }
        const valid = kind === 'api' ? apiModelProjectionCurrent(value, value?.selectionRevision) : accountModelProjectionCurrent(value, value?.selectionRevision);
        if (!valid) throw new Error('来源记录不完整，请重新读取。');
        windows.current[kind] = value.windowId;
        if (kind === 'api') setApi(value); else setAccount(value);
      } catch (failure) { failures.push(`${kind === 'api' ? 'API 服务' : 'ChatGPT 账号'}：${failure instanceof Error ? failure.message : '暂不可读取'}`); }
    }
    if (!alive.current || request !== generation.current) return;
    if (can('models.groups.snapshot')) try {
      const value = await latest.current.act('models.groups.snapshot');
      const snapshot = value?.modelGroups || value;
      if (alive.current && request === generation.current && snapshot?.format === 'pocketworld.model-groups.v1' && Array.isArray(snapshot.groups)) setGroups(snapshot.groups);
    } catch (failure) { failures.push(`模型组：${failure instanceof Error ? failure.message : '暂不可读取'}`); }
    if (alive.current && request === generation.current) { setSourceErrors(failures); setLoading(false); }
  };
  useEffect(() => {
    alive.current = true; void refresh();
    return () => { alive.current = false; generation.current++; close('api', windows.current.api); close('account', windows.current.account); windows.current = { api: '', account: '' }; };
  }, [apiSupported, accountSupported]);
  useEffect(() => {
    if (revision.current !== state.modelSelectionRevision && !pending.current) {
      generation.current++; setReview(null); setGroupReview(null); setGroups([]); setApi(null); setAccount(null); setLoading(false); setNeedsRead(true); setError('模型配置已变化，请重新读取后选择。');
    }
    revision.current = state.modelSelectionRevision;
  }, [state.modelSelectionRevision]);
  const readAccount = async (id: string) => {
    if (locked || needsRead || !account) return;
    let args: Data;
    try { args = accountModelReadRequest(account, state.modelSelectionRevision, id); }
    catch (failure) { setError((failure as Error).message); return; }
    const request = ++generation.current; setLoading(true); setError(''); setReview(null);
    try {
      const value = await act('models.accounts.models', args);
      if (alive.current && generation.current === request) {
        if (!accountModelProjectionCurrent(value, state.modelSelectionRevision)) throw new Error('账号版本已变化，请重新读取。');
        setAccount(value);
      }
    } catch (failure) { if (alive.current && request === generation.current) setError(failure instanceof Error ? failure.message : '账号模型目录读取失败，请重新读取。'); }
    finally { if (alive.current && request === generation.current) setLoading(false); }
  };
  const inspect = async (kind: 'api' | 'account', id: string) => {
    if (locked || needsRead) return;
    let args: Data;
    try { args = kind === 'api' ? apiModelReviewRequest(api, state.modelSelectionRevision, id) : accountModelReviewRequest(account, state.modelSelectionRevision, id); }
    catch (failure) { setError((failure as Error).message); setNeedsRead(true); return; }
    const request = ++generation.current; setLoading(true); setError('');
    try {
      const value = await act(kind === 'api' ? 'models.api.review' : 'models.accounts.review', args);
      if (!alive.current || request !== generation.current) return;
      if (kind === 'api' ? !apiModelReviewCurrent(value, api, state.modelSelectionRevision) : !accountModelReviewCurrent(value, state.modelSelectionRevision)) throw new Error('模型审核已变化，请重新读取。');
      setReview({ kind, value });
      if (kind === 'account') setOptions({ reasoningEffort: String(value.options?.reasoningEffort || ''), outputReserve: String(value.options?.outputReserve ?? 4096), imageTokenReserve: String(value.options?.imageTokenReserve ?? 0) });
    } catch (failure) { if (alive.current && request === generation.current) { setReview(null); setError(failure instanceof Error ? failure.message : '模型审核读取失败。'); } }
    finally { if (alive.current && request === generation.current) setLoading(false); }
  };
  const write = async (favorite = false) => {
    if (!review || locked || needsRead || pending.current) return;
    let args: Data;
    try { args = review.kind === 'api' ? favorite ? apiModelFavorite(review.value, api, state.modelSelectionRevision) : apiModelSelection(review.value, api, state.modelSelectionRevision) : accountModelSelection(review.value, state.modelSelectionRevision, options); }
    catch (failure) { setError((failure as Error).message); return; }
    if (!favorite && scope === 'conversation' && !onSelectTarget) { setError('本对话模型选择尚未接通，请更新应用后重试。'); return; }
    const request = ++generation.current; pending.current = true; setWriting(true); setError(''); latest.current.onSelectionPending(true);
    let reread = false;
    try {
      const value = review.value, model = value.model || {};
      const result = favorite ? await act('models.api.favorite', args) : onSelectTarget ? await onSelectTarget({ kind: review.kind, args, selectionRevision: state.modelSelectionRevision,
        modelId: String(review.kind === 'api' ? model.modelId : value.modelId), sourceLabel: String(review.kind === 'api' ? model.connectionName || model.connectionId : value.accountLabel || value.accountId) })
        : await act(review.kind === 'api' ? 'models.api.select' : 'models.accounts.select', args);
      if (result?.status !== 'saved') throw new Error('操作回执尚未确认，请重新读取核对。');
      if (alive.current && request === generation.current) { if (favorite) reread = true; else latest.current.onSelected(); }
    } catch (failure) {
      if (alive.current && request === generation.current) { setReview(null); setApi(null); setAccount(null); setNeedsRead(true); setError(`${failure instanceof Error ? failure.message : '操作结果待核对。'} 没有自动重试，请读取当前选择后核对。`); }
    } finally {
      pending.current = false; latest.current.onSelectionPending(false);
      if (alive.current && request === generation.current) { setWriting(false); if (reread) void refresh(); }
    }
  };
  const currentRevision = state.modelSelectionRevision, q = query.trim().toLocaleLowerCase();
  const visibleGroups = filter === 'all' ? groups.filter(group => [group.name, group.groupId].some(value => String(value || '').toLocaleLowerCase().includes(q))) : [];
  const selectGroup = async () => {
    if (!groupReview || locked || needsRead || pending.current || scope === 'conversation' && !onSelectTarget) return;
    const original = groupReview, request = ++generation.current;
    pending.current = true; setWriting(true); setError(''); latest.current.onSelectionPending(true);
    try {
      const args = { groupId: original.groupId, expectedRevision: original.revision };
      const result = onSelectTarget ? await onSelectTarget({ kind: 'group', args, selectionRevision: state.modelSelectionRevision, modelId: original.name, sourceLabel: '模型组' }) : await act('models.groups.select', args);
      if (result?.status !== 'saved') throw new Error('模型组选择结果待核对，请重新读取。');
      if (alive.current && request === generation.current) latest.current.onSelected();
    } catch (failure) { if (alive.current && request === generation.current) { setGroupReview(null); setNeedsRead(true); setError(`${failure instanceof Error ? failure.message : '模型组选择结果待核对。'} 没有自动重试。`); } }
    finally { pending.current = false; latest.current.onSelectionPending(false); if (alive.current && request === generation.current) setWriting(false); }
  };
  const apiRows = apiModelRows(api, currentRevision, filter).filter(row => [row.modelId, row.connectionName, row.connectionId].some(value => String(value || '').toLocaleLowerCase().includes(q)));
  const accounts = accountModelAccountRows(account, currentRevision), catalog = accountModelCatalog(account, currentRevision);
  const accountRows = filter === 'all' ? (catalog?.models || []).filter(row => [row.id, row.displayName, catalog?.accountLabel].some(value => String(value || '').toLocaleLowerCase().includes(q))) : [];
  const accountSources = filter === 'all' ? accounts.filter(row => row.id !== catalog?.accountId && [row.name, 'ChatGPT'].some(value => value.toLocaleLowerCase().includes(q))) : [];
  const selectedAccount = account?.accountSnapshot?.selectedModelRef;
  const model = review?.value.model || {}, facts = review?.value.catalogFacts?.facts || {};
  const levels = accountModelReasoningLevels(review?.value), known = (key: string) => facts[key]?.known === true ? facts[key].value : undefined;
  let selectable = false, favoritable = false;
  try { if (review?.kind === 'api') apiModelSelection(review.value, api, currentRevision); else if (review) accountModelSelection(review.value, currentRevision, options); else throw new Error(); selectable = true; } catch {}
  try { if (review?.kind !== 'api') throw new Error(); apiModelFavorite(review.value, api, currentRevision); favoritable = true; } catch {}
  return <section className="pw-ai-picker" aria-label="统一模型目录">
    {groupReview ? <div className="pw-ai-model-review"><Button variant="ghost" leadingIcon={ArrowLeft} disabled={writing} onClick={() => setGroupReview(null)}>返回模型</Button><header><h3>{groupReview.name}</h3><p>模型组 · 修订 {groupReview.revision}</p></header><p className="pw-ai-scope">{scope === 'conversation' ? '用于本对话的新任务；正在执行的任务与连续补充保留原模型。' : '设为后续新任务的 MAIN 默认模型组。'}</p><div className="pw-ai-model-rows">{(Array.isArray(groupReview.candidates) ? groupReview.candidates : []).map((candidate: Data, index: number) => <div className="pw-ai-group-candidate-preview" key={candidate.candidateId}><span>{index + 1}</span><span className="pw-ai-row-copy"><strong>{candidate.target?.modelId || '原候选'}</strong><small>{candidate.target?.connectionId}{candidate.enabled === false ? ' · 已停用' : ''}</small></span></div>)}</div><p className="pw-ai-muted">{groupReview.attemptPolicy?.maxRouteSwitches === 1 ? '失败切换已开启，能力与当前请求逐次核对。' : '失败切换未开启。'}</p><div className="pw-ai-picker-actions"><Button disabled={locked || scope === 'conversation' && !onSelectTarget} onClick={() => void selectGroup()}>{writing ? '保存中…' : scope === 'conversation' ? '用于本对话' : '设为默认模型组'}</Button></div></div> : review ? <div className="pw-ai-model-review">
      <Button variant="ghost" leadingIcon={ArrowLeft} disabled={writing} onClick={() => setReview(null)}>返回模型</Button>
      <header><h3>{review.kind === 'api' ? model.modelId : review.value.displayName || review.value.modelId}</h3><p>{review.kind === 'api' ? model.connectionName || model.connectionId : `ChatGPT · ${review.value.accountLabel || review.value.accountId}`}</p></header>
      <p className="pw-ai-scope">{scope === 'conversation' ? '用于本对话的新任务。正在执行的任务与连续补充保留原模型。' : '设为新对话的默认模型。已有对话的单独选择与运行中的任务保持原样。'}</p>
      <dl className="pw-ai-model-facts"><div><dt>图片输入</dt><dd>{review.kind === 'api' ? apiCapability(model.imageInput) : known('IMAGE_INPUT') === undefined ? '未声明' : known('IMAGE_INPUT') ? '支持，仍需任务授权' : '不支持'}</dd></div>{review.kind === 'api' && <div><dt>工具调用</dt><dd>{apiCapability(model.toolCalls)}</dd></div>}</dl>
      {review.kind === 'api' && !model.available && <p role="alert">{apiModelUnavailable(model)}</p>}
      {review.kind === 'account' && <>
        {levels.length > 0 && <SelectField label="推理设置" value={options.reasoningEffort || 'service-default'} options={[{ id: 'service-default', label: '服务默认' }, ...levels.map(level => ({ id: level, label: level }))]} onChange={value => setOptions(old => ({ ...old, reasoningEffort: value === 'service-default' ? '' : value }))} isDisabled={writing} />}
        <Accordion><AccordionItem id="local-reserve" title="本地输入预留"><TextField label="输出预留" inputMode="numeric" value={options.outputReserve} onChange={value => setOptions(old => ({ ...old, outputReserve: value.replace(/\D/g, '').slice(0, 5) }))} hint="128–32768 tokens，用于本地输入准入。" isDisabled={writing} /><TextField label="每张图片预留" inputMode="numeric" value={options.imageTokenReserve} onChange={value => setOptions(old => ({ ...old, imageTokenReserve: value.replace(/\D/g, '').slice(0, 7) }))} hint="0–2000000 tokens。" isDisabled={writing} /></AccordionItem></Accordion>
      </>}
      <div className="pw-ai-picker-actions">{review.kind === 'api' && <Button variant="ghost" leadingIcon={Star} disabled={locked || !favoritable} onClick={() => void write(true)}>{model.favorite ? '取消收藏' : '收藏'}</Button>}<Button disabled={locked || !selectable || scope === 'conversation' && !onSelectTarget} onClick={() => void write()}>{writing ? '保存中…' : scope === 'conversation' ? '用于本对话' : '设为默认模型'}</Button></div>
    </div> : <>
      <SearchField aria-label="搜索模型或来源" placeholder="搜索模型或来源" value={query} onChange={setQuery} className="pw-ai-search" />
      <Tabs selectedKey={filter} onSelectionChange={key => setFilter(String(key) as ApiModelFilter)} className="pw-ai-filter-tabs"><div className="pw-ai-filter"><TabList aria-label="模型筛选"><Tab id="all">全部</Tab><Tab id="favorites">收藏</Tab><Tab id="recent">最近使用</Tab></TabList></div><TabPanel id={filter} className="pw-ai-tab-panel">
      {loading && <p className="pw-ai-loading" aria-busy="true"><ShimmerText>正在读取来源与模型</ShimmerText></p>}
      {!needsRead && <div className="pw-ai-model-rows">
        {visibleGroups.map(group => <Button key={`group:${group.groupId}`} variant="ghost" className="pw-ai-model-row" disabled={locked} onClick={() => setGroupReview({ ...group })}><span className="pw-ai-row-copy"><strong>{group.name}</strong><small>模型组 · {Array.isArray(group.candidates) ? group.candidates.length : 0} 个候选</small></span><ChevronRight size={17} aria-hidden /></Button>)}
        {apiRows.map(row => <Button key={apiModelKey(row)} variant="ghost" className="pw-ai-model-row" disabled={locked} onClick={() => void inspect('api', apiModelKey(row))}><span className="pw-ai-row-copy"><strong>{row.modelId}</strong><small>{row.connectionName || row.connectionId}{row.available ? '' : ` · ${apiModelUnavailable(row)}`}</small></span><span className="pw-ai-row-end">{row.favorite && <Star size={15} aria-label="已收藏" />}{row.selected ? <Check size={18} aria-label="当前默认模型" /> : <ChevronRight size={17} aria-hidden />}</span></Button>)}
        {accountRows.map(row => <Button key={`account:${catalog!.accountId}:${row.id}`} variant="ghost" className="pw-ai-model-row" disabled={locked} onClick={() => void inspect('account', row.id)}><span className="pw-ai-row-copy"><strong>{row.displayName || row.id}</strong><small>ChatGPT · {catalog!.accountLabel}</small></span><span className="pw-ai-row-end">{state.modelRoute === 'account' && selectedAccount?.accountId === catalog!.accountId && selectedAccount?.modelId === row.id ? <Check size={18} aria-label="当前默认模型" /> : <ChevronRight size={17} aria-hidden />}</span></Button>)}
        {accountSources.map(row => <Button key={`account-source:${row.id}`} variant="ghost" className="pw-ai-model-row" disabled={locked || row.disabled} onClick={() => void readAccount(row.id)}><span className="pw-ai-row-copy"><strong>{row.name}</strong><small>{row.reason || 'ChatGPT · 读取可用模型'}</small></span><ChevronRight size={17} aria-hidden /></Button>)}
        {!loading && !apiRows.length && !accountRows.length && !accountSources.length && !visibleGroups.length && <div className="pw-ai-empty"><h3>{q ? '没有匹配的模型' : filter === 'favorites' ? '还没有收藏模型' : filter === 'recent' ? '还没有成功使用记录' : '添加你的第一个 AI 服务'}</h3><p>{q ? '试试模型名称或服务名称。' : filter === 'all' ? '连接 API 或登录账号后，模型会显示在这里。' : filter === 'favorites' ? '打开 API 模型后可收藏。' : '成功调用后会记录最近使用。'}</p>{filter === 'all' && !q && onManage && <Button onClick={onManage}>添加服务</Button>}</div>}
      </div>}
      {!!sourceErrors.length && <div role="status" className="pw-ai-feedback">{sourceErrors.map(message => <p key={message}>{message}</p>)}</div>}
      </TabPanel></Tabs>
    </>}
    {error && <p className="pw-field-error" role="alert">{error}</p>}
    {!review && !groupReview && <footer className="pw-ai-picker-footer"><Button variant="ghost" leadingIcon={RefreshCw} disabled={locked} onClick={() => void refresh()}>重新读取</Button>{onManage && <Button variant="ghost" trailingIcon={ChevronRight} disabled={writing} onClick={onManage}>管理 AI 接入</Button>}</footer>}
  </section>;
}
