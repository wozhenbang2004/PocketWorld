import { useEffect, useRef, useState } from 'react';
import { IconArrowLeft, IconCheck, IconChevronRight, IconInfo, IconRefresh, IconStar } from './icons';
import { Accordion, AccordionItem, Button, IconButton, SearchField, SelectField, TextField } from './forms-library';
import { Tabs, Tab, TabList, TabPanel } from './shell-library';
import { ShimmerText } from './library';
import { apiCapability, apiModelFavorite, apiModelKey, apiModelProjectionCurrent, apiModelReviewCurrent, apiModelReviewRequest, apiModelRows, apiModelSelection, apiModelUnavailable, type ApiModelFilter } from './composer-api-models';
import { accountModelAccountRows, accountModelCatalog, accountModelProjectionCurrent, accountModelReadRequest, accountModelReasoningLevels, accountModelReviewCurrent, accountModelReviewRequest, accountModelSelection, type AccountModelOptions } from './composer-account-models';
import { conversationModelName, conversationModelStamp, conversationSelectionReceipt, modelPickerSelected, modelPickerSourceChanged, type PickerModelIdentity } from './conversation-model-selection';
import './ai-center.css';
import './conversation-model-controls.css';

type Data = Record<string, any>;
type Run = (action: string, args?: Data) => Promise<any>;
type ModelReview = { kind: 'api' | 'account'; value: Data; stamp: string; expiresAt: number };
export type AIModelTarget = { kind: 'api' | 'account' | 'group'; args: Data; selectionRevision: number; modelId: string; sourceLabel: string };
export type AIModelPickerPresentation = { query: string; filter: ApiModelFilter };
export type AIModelPickerProps = {
  state: Data; can: (action: string) => boolean; act: Run; scope?: 'conversation' | 'default';
  onSelectTarget?: (target: AIModelTarget) => Promise<any>; onSelected?: () => void;
  onSelectionPending: (pending: boolean) => void; onManage?: () => void;
  presentationState?: AIModelPickerPresentation; onPresentationChange?: (value: AIModelPickerPresentation) => void;
};
const busy = (state: Data) => !!(state.modelSelectionBusy || state.chatSubmitting || state.phoneSubmitting || state.modelSaving || state.modelTesting);
const accountOptions = (value: Data): AccountModelOptions => ({ reasoningEffort: String(value.options?.reasoningEffort || ''), outputReserve: String(value.options?.outputReserve ?? 4096), imageTokenReserve: String(value.options?.imageTokenReserve ?? 0) });

/** One catalog. A row selects through its original native review; details are optional. */
export function AIModelPicker({ state, can, act, scope = 'conversation', onSelectTarget, onSelected, onSelectionPending, onManage, presentationState, onPresentationChange }: AIModelPickerProps) {
  const [api, setApi] = useState<Data | null>(null), [account, setAccount] = useState<Data | null>(null);
  const [groups, setGroups] = useState<Data[]>([]), [defaultGroup, setDefaultGroup] = useState<Data | null>(null), [groupReview, setGroupReview] = useState<Data | null>(null);
  const [review, setReview] = useState<ModelReview | null>(null);
  const [options, setOptions] = useState<AccountModelOptions>({ reasoningEffort: '', outputReserve: '4096', imageTokenReserve: '0' });
  const [localPresentation, setLocalPresentation] = useState<AIModelPickerPresentation>({ query: '', filter: 'all' });
  const [expandedTools, setExpandedTools] = useState({ search: false, filters: false });
  const { query, filter } = presentationState || localPresentation;
  const present = (patch: Partial<AIModelPickerPresentation>) => { const next = { query, filter, ...patch }; setLocalPresentation(next); onPresentationChange?.(next); };
  const [loading, setLoading] = useState(false), [writing, setWriting] = useState(false), [needsRead, setNeedsRead] = useState(false);
  const [error, setError] = useState(''), [notice, setNotice] = useState(''), [sourceErrors, setSourceErrors] = useState<string[]>([]);
  const alive = useRef(false), generation = useRef(0), pending = useRef(false), windows = useRef({ api: '', account: '' });
  const revision = useRef(state.modelSelectionRevision), originalScope = useRef(state.conversationModel?.scopeKey);
  const confirmed = useRef<Data | null>(null), started = useRef(false);
  const latest = useRef({ state, act, onSelectTarget, onSelected, onSelectionPending });
  latest.current = { state, act, onSelectTarget, onSelected, onSelectionPending };
  const apiSupported = ['open', 'close', 'review', 'select', 'favorite'].every(name => can(`models.api.${name}`));
  const accountSupported = ['open', 'close', 'models', 'review', 'select'].every(name => can(`models.accounts.${name}`));
  const locked = loading || writing || busy(state);
  const context = (value: Data) => scope === 'conversation' ? conversationModelStamp(value) : JSON.stringify([value.modelSelectionRevision]);
  const close = (kind: 'api' | 'account', id: string) => {
    if (id) void latest.current.act(kind === 'api' ? 'models.api.close' : 'models.accounts.close', { windowId: id }).catch(() => {});
  };
  const refresh = async (keepNotice = false) => {
    if (pending.current || busy(latest.current.state)) return;
    started.current = true;
    const request = ++generation.current;
    confirmed.current = null;
    setLoading(true); setError(''); if (!keepNotice) setNotice(''); setSourceErrors([]); setNeedsRead(false); setReview(null); setGroupReview(null); setGroups([]); setDefaultGroup(null); setApi(null); setAccount(null);
    const old = windows.current; windows.current = { api: '', account: '' }; close('api', old.api); close('account', old.account);
    const failures: string[] = [];
    for (const kind of ['api', 'account'] as const) {
      if (!alive.current || request !== generation.current) return;
      if (kind === 'api' ? !apiSupported : !accountSupported) continue;
      try {
        const value = await latest.current.act(kind === 'api' ? 'models.api.open' : 'models.accounts.open');
        if (!alive.current || request !== generation.current) { close(kind, value?.windowId); continue; }
        const valid = kind === 'api' ? apiModelProjectionCurrent(value, latest.current.state.modelSelectionRevision) : accountModelProjectionCurrent(value, latest.current.state.modelSelectionRevision);
        if (!valid) { close(kind, value?.windowId); throw new Error('来源记录已变化，请重新读取。'); }
        windows.current[kind] = value.windowId;
        if (kind === 'api') setApi(value); else setAccount(value);
      } catch (failure) { failures.push(`${kind === 'api' ? 'API 服务' : 'ChatGPT 账号'}：${failure instanceof Error ? failure.message : '暂不可读取'}`); }
    }
    if (!alive.current || request !== generation.current) return;
    if (can('models.groups.snapshot')) try {
      const value = await latest.current.act('models.groups.snapshot'), snapshot = value?.modelGroups || value;
      if (alive.current && request === generation.current && snapshot?.format === 'pocketworld.model-groups.v1' && Array.isArray(snapshot.groups)) { setGroups(snapshot.groups); setDefaultGroup(snapshot.purposeBindings?.MAIN || null); }
    } catch (failure) { failures.push(`模型组：${failure instanceof Error ? failure.message : '暂不可读取'}`); }
    if (alive.current && request === generation.current) { setSourceErrors(failures); setLoading(false); }
  };
  useEffect(() => {
    alive.current = true; void refresh();
    return () => { alive.current = false; generation.current++; close('api', windows.current.api); close('account', windows.current.account); windows.current = { api: '', account: '' }; };
  }, [apiSupported, accountSupported]);
  useEffect(() => {
    const scopeChanged = scope === 'conversation' && originalScope.current !== state.conversationModel?.scopeKey;
    if (scopeChanged) {
      generation.current++; confirmed.current = null; setReview(null); setGroupReview(null); setLoading(false); setWriting(false); setNotice(''); setNeedsRead(true); setError('对话已切换，请重新读取模型。');
    } else if (!pending.current && !busy(state) && confirmed.current && (scope === 'default' || confirmed.current.favorite && state.modelSelectionRevision !== confirmed.current.sourceRevision || !confirmed.current.favorite && state.conversationModel?.revision === confirmed.current.revision)) {
      void refresh(true);
    } else if (!started.current && !busy(state)) {
      void refresh();
    } else if (revision.current !== state.modelSelectionRevision && !pending.current && !confirmed.current) {
      generation.current++; setReview(null); setGroupReview(null); setGroups([]); setApi(null); setAccount(null); setLoading(false); setNeedsRead(true); setError(old => old || '模型配置已变化，请重新读取后选择。');
    }
    originalScope.current = state.conversationModel?.scopeKey; revision.current = state.modelSelectionRevision;
  }, [state.modelSelectionRevision, state.modelSelectionBusy, state.conversationModel?.scopeKey, state.conversationModel?.revision, writing]);
  const currentRequest = (request: number, stamp: string) => alive.current && request === generation.current && stamp === context(latest.current.state);
  const readAccount = async (id: string) => {
    if (locked || needsRead || pending.current || !account) return;
    let args: Data;
    try { args = accountModelReadRequest(account, state.modelSelectionRevision, id); }
    catch (failure) { setError((failure as Error).message); return; }
    const request = ++generation.current, stamp = context(state); setLoading(true); setError(''); setReview(null);
    try {
      const value = await act('models.accounts.models', args);
      if (!currentRequest(request, stamp)) return;
      if (!accountModelProjectionCurrent(value, latest.current.state.modelSelectionRevision)) throw new Error('账号版本已变化，请重新读取。');
      setAccount(value);
    } catch (failure) { if (alive.current && request === generation.current) setError(failure instanceof Error ? failure.message : '账号模型目录读取失败，请重新读取。'); }
    finally { if (alive.current && request === generation.current) setLoading(false); }
  };
  const prepare = async (kind: 'api' | 'account', id: string, request: number, stamp: string): Promise<ModelReview | null> => {
    const sourceRevision = latest.current.state.modelSelectionRevision;
    const args: Data = kind === 'api' ? apiModelReviewRequest(api, sourceRevision, id) : accountModelReviewRequest(account, sourceRevision, id);
    const value = await latest.current.act(kind === 'api' ? 'models.api.review' : 'models.accounts.review', args);
    if (!alive.current || request !== generation.current) return null;
    if (!currentRequest(request, stamp)) throw new Error('此对话模型已变化，请重新读取。');
    if (kind === 'api' ? !apiModelReviewCurrent(value, api, sourceRevision) : !accountModelReviewCurrent(value, sourceRevision) || value.windowId !== account?.windowId || value.accountId !== args.accountId || value.modelId !== id) throw new Error('模型审核已变化，请重新读取。');
    if (value.expiresInMs !== undefined && (!Number.isSafeInteger(value.expiresInMs) || value.expiresInMs <= 0 || value.expiresInMs > 120_000)) throw new Error('模型审核已过期，请重新读取。');
    return { kind, value, stamp, expiresAt: value.expiresInMs === undefined ? Infinity : Date.now() + value.expiresInMs };
  };
  const inspect = async (kind: 'api' | 'account', id: string) => {
    if (locked || needsRead || pending.current) return;
    const request = ++generation.current, stamp = context(state); setLoading(true); setError('');
    try {
      const prepared = await prepare(kind, id, request, stamp);
      if (prepared) { setReview(prepared); if (kind === 'account') setOptions(accountOptions(prepared.value)); }
    } catch (failure) { if (alive.current && request === generation.current) { setReview(null); setError(failure instanceof Error ? failure.message : '模型详情暂不可读取。'); } }
    finally { if (alive.current && request === generation.current) setLoading(false); }
  };
  const selection = (prepared: ModelReview, draft: AccountModelOptions, favorite = false) => {
    if (prepared.stamp !== context(latest.current.state) || Date.now() >= prepared.expiresAt) throw new Error('这次模型选择已过期或发生变化，请重新读取。');
    return prepared.kind === 'api' ? favorite ? apiModelFavorite(prepared.value, api, latest.current.state.modelSelectionRevision) : apiModelSelection(prepared.value, api, latest.current.state.modelSelectionRevision)
      : accountModelSelection(prepared.value, latest.current.state.modelSelectionRevision, draft);
  };
  const saved = (result: Data, request: number, favorite: boolean): boolean => {
    if (result?.status !== 'saved') throw new Error('保存结果待核对，请重新读取。');
    if (!alive.current || request !== generation.current) return false;
    if (scope === 'conversation' && !favorite && result.preference) {
      const current = latest.current.state.conversationModel;
      if (current?.scopeKey !== result.preference.scopeKey || ![result.preference.revision - 1, result.preference.revision].includes(current?.revision)) throw new Error('此对话的选择已变化，请重新读取。');
    }
    confirmed.current = favorite ? { favorite: true, sourceRevision: state.modelSelectionRevision } : result.preference || {};
    setReview(null); setGroupReview(null); setApi(null); setAccount(null); setGroups([]); setNeedsRead(true);
    setNotice(favorite ? '收藏已更新' : '模型已保存');
    return !favorite;
  };
  const settleSelection = (request: number, selected: boolean) => {
    pending.current = false;
    latest.current.onSelectionPending(false);
    if (!alive.current || request !== generation.current) return;
    setWriting(false);
    // Only a confirmed selection may close the caller, after its write lock clears.
    if (selected) latest.current.onSelected?.();
  };
  const unknown = (failure: unknown, request: number) => {
    if (!alive.current || request !== generation.current) return;
    confirmed.current = null; setReview(null); setGroupReview(null); setApi(null); setAccount(null); setNeedsRead(true); setNotice('');
    setError(`保存结果待核对。${failure instanceof Error ? failure.message : ''} 没有自动重试，请重新读取当前选择。`);
  };
  const selectReviewed = async (prepared: ModelReview, draft: AccountModelOptions, request: number, favorite = false) => {
    const args = selection(prepared, draft, favorite), value = prepared.value, model = value.model || {};
    if (!favorite && scope === 'conversation' && !latest.current.onSelectTarget) throw new Error('本对话模型选择尚未接通，请更新应用后重试。');
    const result = favorite ? await latest.current.act('models.api.favorite', args) : latest.current.onSelectTarget ? await latest.current.onSelectTarget({ kind: prepared.kind, args, selectionRevision: latest.current.state.modelSelectionRevision,
      modelId: String(prepared.kind === 'api' ? model.modelId : value.modelId), sourceLabel: String(prepared.kind === 'api' ? model.connectionName || model.connectionId : value.accountLabel || value.accountId) })
      : await latest.current.act(prepared.kind === 'api' ? 'models.api.select' : 'models.accounts.select', args);
    return saved(result, request, favorite);
  };
  const choose = async (kind: 'api' | 'account', id: string) => {
    if (locked || needsRead || pending.current || scope === 'conversation' && !onSelectTarget) return;
    const request = ++generation.current, stamp = context(state);
    pending.current = true; setWriting(true); setError(''); setNotice(''); latest.current.onSelectionPending(true);
    let dispatched = false, selected = false;
    try {
      const prepared = await prepare(kind, id, request, stamp);
      if (!prepared || !currentRequest(request, stamp)) return;
      const draft = accountOptions(prepared.value);
      selection(prepared, draft); dispatched = true;
      selected = await selectReviewed(prepared, draft, request);
    } catch (failure) { if (dispatched) unknown(failure, request); else if (alive.current && request === generation.current) { setError(failure instanceof Error ? failure.message : '模型暂不可选择，请重新读取。'); setNeedsRead(true); } }
    finally { settleSelection(request, selected); }
  };
  const write = async (favorite = false) => {
    if (!review || locked || needsRead || pending.current) return;
    try { selection(review, options, favorite); } catch (failure) { setReview(null); setNeedsRead(true); setError((failure as Error).message); return; }
    const request = ++generation.current; pending.current = true; setWriting(true); setError(''); setNotice(''); latest.current.onSelectionPending(true);
    let selected = false;
    try { selected = await selectReviewed(review, options, request, favorite); }
    catch (failure) { unknown(failure, request); }
    finally { settleSelection(request, selected); }
  };
  const selectGroup = async (original: Data) => {
    if (locked || needsRead || pending.current || scope === 'conversation' && !onSelectTarget) return;
    if (!original.groupId || !Number.isSafeInteger(original.revision) || original.revision < 1) { setError('模型组已变化，请重新读取。'); return; }
    const request = ++generation.current;
    pending.current = true; setWriting(true); setError(''); setNotice(''); latest.current.onSelectionPending(true);
    let selected = false;
    try {
      const args = { groupId: original.groupId, expectedRevision: original.revision };
      const result = onSelectTarget ? await onSelectTarget({ kind: 'group', args, selectionRevision: state.modelSelectionRevision, modelId: original.name, sourceLabel: '模型组' }) : await act('models.groups.select', args);
      selected = saved(result, request, false);
    } catch (failure) { unknown(failure, request); }
    finally { settleSelection(request, selected); }
  };
  const reset = async () => {
    const preference = state.conversationModel;
    if (scope !== 'conversation' || pending.current || locked || needsRead || preference?.inherited !== false || !can('models.conversation.reset')) return;
    if (context(state) !== context(latest.current.state)) return;
    const originalScope = preference.scopeKey, originalRevision = preference.revision, request = ++generation.current;
    pending.current = true; setWriting(true); setError(''); setNotice(''); latest.current.onSelectionPending(true);
    let selected = false;
    try {
      const value = await latest.current.act('models.conversation.reset', { expectedPreferenceRevision: originalRevision, expectedSelectionRevision: state.modelSelectionRevision });
      const cleared = conversationSelectionReceipt(value, originalScope, originalRevision), current = latest.current.state.conversationModel;
      if (!alive.current || request !== generation.current || current?.scopeKey !== originalScope) return;
      if (![originalRevision, cleared.revision].includes(current.revision) || cleared.inherited !== true || cleared.target != null) throw new Error('恢复默认的结果待核对，请重新读取。');
      confirmed.current = cleared; setReview(null); setGroupReview(null); setApi(null); setAccount(null); setGroups([]); setNeedsRead(true);
      setNotice('已恢复使用默认模型');
      selected = true;
    } catch (failure) { if (latest.current.state.conversationModel?.scopeKey === originalScope) unknown(failure, request); }
    finally { settleSelection(request, selected); }
  };
  const currentRevision = state.modelSelectionRevision, q = query.trim().toLocaleLowerCase();
  const visibleGroups = filter === 'all' ? groups.filter(group => [group.name, group.groupId].some(value => String(value || '').toLocaleLowerCase().includes(q))) : [];
  const apiRows = apiModelRows(api, currentRevision, filter).filter(row => [row.modelId, row.connectionName, row.connectionId].some(value => String(value || '').toLocaleLowerCase().includes(q)));
  const accounts = accountModelAccountRows(account, currentRevision), catalog = accountModelCatalog(account, currentRevision);
  const accountRows = filter === 'all' ? (catalog?.models || []).filter(row => [row.id, row.displayName, catalog?.accountLabel].some(value => String(value || '').toLocaleLowerCase().includes(q))) : [];
  const accountSources = filter === 'all' ? accounts.filter(row => row.id !== catalog?.accountId && [row.name, 'ChatGPT'].some(value => value.toLocaleLowerCase().includes(q))) : [];
  const selectedAccount = account?.accountSnapshot?.selectedModelRef;
  const inherited = scope === 'conversation' && state.conversationModel?.inherited === true;
  const selectionLabel = scope === 'default' ? '当前默认模型' : inherited ? '沿用默认模型' : '此对话已选模型';
  const isSelected = (candidate: PickerModelIdentity, defaultSelected = false) => modelPickerSelected(state, scope, candidate, defaultSelected);
  const currentSelectionVisible = !needsRead && !review && !groupReview && (
    visibleGroups.some(group => isSelected({ kind: 'group', groupId: group.groupId, groupRevision: group.revision }, defaultGroup?.groupId === group.groupId && defaultGroup?.groupRevision === group.revision)) ||
    apiRows.some(row => isSelected({ kind: 'api', connectionId: row.connectionId, connectionRevision: row.connectionRevision, modelId: row.modelId }, !defaultGroup?.groupId && row.selected === true)) ||
    accountRows.some(row => isSelected({ kind: 'account', nativeRef: row.nativeRef }, !defaultGroup?.groupId && state.modelRoute === 'account' && selectedAccount?.accountId === catalog!.accountId && selectedAccount?.accountVersion === catalog!.accountVersion && selectedAccount?.modelId === row.id))
  );
  const model = review?.value.model || {}, facts = review?.value.catalogFacts?.facts || {};
  const levels = accountModelReasoningLevels(review?.value), known = (key: string) => facts[key]?.known === true ? facts[key].value : undefined;
  let selectable = false, favoritable = false;
  try { if (!review) throw new Error(); selection(review, options); selectable = true; } catch {}
  try { if (review?.kind !== 'api') throw new Error(); selection(review, options, true); favoritable = true; } catch {}
  const allApiRows = apiModelRows(api, currentRevision);
  const catalogSize = allApiRows.length + groups.length + (catalog?.models.length || 0) + accounts.filter(row => row.id !== catalog?.accountId).length;
  const filterTabs: { id: ApiModelFilter; label: string }[] = [
    { id: 'all', label: '全部' },
    ...(scope === 'default' || filter === 'favorites' || allApiRows.some(row => row.favorite) ? [{ id: 'favorites' as const, label: '收藏' }] : []),
    ...(scope === 'default' || filter === 'recent' || apiModelRows(api, currentRevision, 'recent').length > 0 ? [{ id: 'recent' as const, label: '最近使用' }] : []),
  ];
  const showSearch = expandedTools.search || scope === 'default' || catalogSize > 6 || query.length > 0;
  const showFilters = expandedTools.filters || scope === 'default' || filter !== 'all' || catalogSize > 6 && filterTabs.length > 1;
  useEffect(() => {
    // Once disclosed, keep a focused search or tab in place for this opening.
    if (showSearch && !expandedTools.search || showFilters && !expandedTools.filters)
      setExpandedTools({ search: showSearch, filters: showFilters });
  }, [showSearch, showFilters, expandedTools.search, expandedTools.filters]);
  const emptyAccountCatalog = !!catalog && catalog.models.length === 0;
  const showRecovery = scope === 'default' || needsRead || !!error || sourceErrors.length > 0 || emptyAccountCatalog;
  const showManage = !!onManage && (catalogSize > 0 || !!q || filter !== 'all' || showRecovery);
  const catalogContent = <>
      {loading && <p className="pw-ai-loading" aria-busy="true"><ShimmerText>正在读取模型</ShimmerText></p>}
      {!needsRead && <div className="pw-ai-model-rows">
        {visibleGroups.map(group => { const selected = isSelected({ kind: 'group', groupId: group.groupId, groupRevision: group.revision }, defaultGroup?.groupId === group.groupId && defaultGroup?.groupRevision === group.revision); return <div className="pw-ai-model-option" data-selected={selected || undefined} key={`group:${group.groupId}`}><Button variant="ghost" className="pw-ai-model-row" aria-label={`选择模型组：${group.name}`} aria-pressed={selected} disabled={locked || scope === 'conversation' && !onSelectTarget} onClick={() => { if (!selected || inherited) void selectGroup(group); }}><span className="pw-ai-row-copy"><strong>{group.name}</strong><small>模型组 · {Array.isArray(group.candidates) ? group.candidates.length : 0} 个候选</small></span>{selected && <IconCheck size={18} aria-label={selectionLabel} />}</Button><IconButton icon={IconInfo} aria-label={`查看模型组详情：${group.name}`} disabled={locked} onClick={() => setGroupReview({ ...group })} /></div>; })}
        {apiRows.map(row => { const candidate: PickerModelIdentity = { kind: 'api', connectionId: row.connectionId, connectionRevision: row.connectionRevision, modelId: row.modelId }, selected = isSelected(candidate, !defaultGroup?.groupId && row.selected === true), sourceChanged = modelPickerSourceChanged(state, scope, candidate), source = row.connectionName || row.connectionId; return <div className="pw-ai-model-option" data-selected={selected || undefined} key={apiModelKey(row)}><Button variant="ghost" className="pw-ai-model-row" aria-label={`选择模型：${row.modelId}，${source}`} aria-pressed={selected} disabled={locked || !row.available || scope === 'conversation' && !onSelectTarget} onClick={() => { if (!selected || inherited || sourceChanged) void choose('api', apiModelKey(row)); }}><span className="pw-ai-row-copy"><strong>{row.modelId}</strong><small>{source}{row.available ? '' : ` · ${apiModelUnavailable(row)}`}</small>{sourceChanged && <small>来源信息已更新，可重新核对</small>}</span><span className="pw-ai-row-end">{row.favorite && <IconStar size={15} aria-label="已收藏" />}{selected && <IconCheck size={18} aria-label={selectionLabel} />}</span></Button><IconButton icon={IconInfo} aria-label={`查看模型详情：${row.modelId}，${source}`} disabled={locked} onClick={() => void inspect('api', apiModelKey(row))} /></div>; })}
        {accountRows.map(row => { const selected = isSelected({ kind: 'account', nativeRef: row.nativeRef }, !defaultGroup?.groupId && state.modelRoute === 'account' && selectedAccount?.accountId === catalog!.accountId && selectedAccount?.accountVersion === catalog!.accountVersion && selectedAccount?.modelId === row.id), name = row.displayName || row.id, source = `ChatGPT · ${catalog!.accountLabel}`; return <div className="pw-ai-model-option" data-selected={selected || undefined} key={`account:${catalog!.accountId}:${row.id}`}><Button variant="ghost" className="pw-ai-model-row" aria-label={`选择模型：${name}，${source}`} aria-pressed={selected} disabled={locked || scope === 'conversation' && !onSelectTarget} onClick={() => { if (!selected || inherited) void choose('account', row.id); }}><span className="pw-ai-row-copy"><strong>{name}</strong><small>{source}</small></span>{selected && <IconCheck size={18} aria-label={selectionLabel} />}</Button><IconButton icon={IconInfo} aria-label={`查看模型详情：${name}，${source}`} disabled={locked} onClick={() => void inspect('account', row.id)} /></div>; })}
        {accountSources.map(row => <Button key={`account-source:${row.id}`} variant="ghost" className="pw-ai-model-row" aria-label={`读取 ${row.name} 的可用模型${row.reason ? `，${row.reason}` : ''}`} disabled={locked || row.disabled} onClick={() => void readAccount(row.id)}><span className="pw-ai-row-copy"><strong>{row.name}</strong>{(scope === 'default' || row.reason) && <small>{row.reason || 'ChatGPT · 读取可用模型'}</small>}</span><IconChevronRight size={17} aria-hidden /></Button>)}
        {!loading && !sourceErrors.length && !apiRows.length && !accountRows.length && !accountSources.length && !visibleGroups.length && <div className="pw-ai-empty"><h3>{q ? '没有匹配的模型' : filter === 'favorites' ? '还没有收藏模型' : filter === 'recent' ? '还没有成功使用记录' : emptyAccountCatalog ? '此账号暂无可用模型' : '添加你的第一个 AI 服务'}</h3><p>{q ? '试试模型名称或服务名称。' : filter === 'all' ? emptyAccountCatalog ? '重新读取目录，或在管理模型中查看账号状态。' : '连接 API 或登录账号后，模型会显示在这里。' : filter === 'favorites' ? '在 API 模型详情中收藏。' : '成功调用后会记录最近使用。'}</p>{filter === 'all' && !q && !emptyAccountCatalog && onManage && <Button onClick={onManage}>添加服务</Button>}</div>}
      </div>}
      {!!sourceErrors.length && <div role="status" className="pw-ai-feedback">{sourceErrors.map(message => <p key={message}>{message}</p>)}</div>}
  </>;
  return <section className={`pw-ai-picker pw-model-selection-picker${scope === 'conversation' ? ' pw-conversation-model-picker' : ''}`} aria-label="统一模型目录">
    {scope === 'conversation' && (inherited || !currentSelectionVisible) && <p className="pw-model-selection-scope">{inherited ? currentSelectionVisible ? '沿用默认模型' : `沿用默认 · ${conversationModelName(state) || '尚未设置'}` : `当前：${conversationModelName(state) || '尚未选择模型'}`}</p>}
    {groupReview ? <div className="pw-ai-model-review"><Button variant="ghost" leadingIcon={IconArrowLeft} disabled={writing} onClick={() => setGroupReview(null)}>返回模型</Button><header><h3>{groupReview.name}</h3><p>模型组 · {Array.isArray(groupReview.candidates) ? groupReview.candidates.length : 0} 个候选</p></header><div className="pw-ai-model-rows">{(Array.isArray(groupReview.candidates) ? groupReview.candidates : []).map((candidate: Data, index: number) => <div className="pw-ai-group-candidate-preview" key={candidate.candidateId}><span>{index + 1}</span><span className="pw-ai-row-copy"><strong>{candidate.target?.modelId || '原候选'}</strong><small>{candidate.target?.connectionId}{candidate.enabled === false ? ' · 已停用' : ''}</small></span></div>)}</div><p className="pw-ai-muted">{groupReview.attemptPolicy?.maxRouteSwitches === 1 ? '失败时可切换至下一候选。' : '失败切换未开启。'}</p><div className="pw-ai-picker-actions"><Button disabled={locked || scope === 'conversation' && !onSelectTarget} onClick={() => void selectGroup(groupReview)}>{scope === 'conversation' ? '用于本对话' : '设为默认模型组'}</Button></div></div> : review ? <div className="pw-ai-model-review">
      <Button variant="ghost" leadingIcon={IconArrowLeft} disabled={writing} onClick={() => setReview(null)}>返回模型</Button>
      <header><h3>{review.kind === 'api' ? model.modelId : review.value.displayName || review.value.modelId}</h3><p>{review.kind === 'api' ? model.connectionName || model.connectionId : `ChatGPT · ${review.value.accountLabel || review.value.accountId}`}</p></header>
      <dl className="pw-ai-model-facts"><div><dt>图片输入</dt><dd>{review.kind === 'api' ? apiCapability(model.imageInput) : known('IMAGE_INPUT') === undefined ? '未声明' : known('IMAGE_INPUT') ? '支持，仍需任务授权' : '不支持'}</dd></div>{review.kind === 'api' && <div><dt>工具调用</dt><dd>{apiCapability(model.toolCalls)}</dd></div>}</dl>
      {review.kind === 'api' && !model.available && <p role="alert">{apiModelUnavailable(model)}</p>}
      {review.kind === 'account' && <>
        {scope === 'default' && levels.length > 0 && <SelectField label="推理设置" value={options.reasoningEffort || 'service-default'} options={[{ id: 'service-default', label: '服务默认' }, ...levels.map(level => ({ id: level, label: level }))]} onChange={value => setOptions(old => ({ ...old, reasoningEffort: value === 'service-default' ? '' : value }))} isDisabled={writing} />}
        <Accordion><AccordionItem id="local-reserve" title="输入与输出预算"><TextField label="输出预留" inputMode="numeric" value={options.outputReserve} onChange={value => setOptions(old => ({ ...old, outputReserve: value.replace(/\D/g, '').slice(0, 5) }))} hint="128–32768 tokens，用于本地输入准入。" isDisabled={writing} /><TextField label="每张图片预留" inputMode="numeric" value={options.imageTokenReserve} onChange={value => setOptions(old => ({ ...old, imageTokenReserve: value.replace(/\D/g, '').slice(0, 7) }))} hint="0–2000000 tokens。" isDisabled={writing} /></AccordionItem></Accordion>
      </>}
      <div className="pw-ai-picker-actions">{review.kind === 'api' && <Button variant="ghost" leadingIcon={IconStar} disabled={locked || !favoritable} onClick={() => void write(true)}>{model.favorite ? '取消收藏' : '收藏'}</Button>}<Button disabled={locked || !selectable || scope === 'conversation' && !onSelectTarget} onClick={() => void write()}>{scope === 'conversation' ? '用于本对话' : '设为默认模型'}</Button></div>
    </div> : <>
      {showSearch && <SearchField aria-label="搜索模型或来源" placeholder="搜索模型或来源" value={query} onChange={value => present({ query: value })} className="pw-ai-search" shape={scope === 'conversation' ? 'rounded' : 'pill'} fieldClassName={scope === 'conversation' ? 'pw-conversation-model-search-field' : undefined} />}
      {showFilters ? <Tabs selectedKey={filter} onSelectionChange={key => present({ filter: String(key) as ApiModelFilter })} className="pw-ai-filter-tabs">
        <div className="pw-ai-filter"><TabList aria-label="模型筛选">{filterTabs.map(tab => <Tab key={tab.id} id={tab.id}>{tab.label}</Tab>)}</TabList></div>
        <TabPanel id={filter} className="pw-ai-tab-panel">{catalogContent}</TabPanel>
      </Tabs> : <div className="pw-ai-tab-panel">{catalogContent}</div>}
    </>}
    {writing && <p role="status" aria-busy="true" className="pw-model-selection-status">正在保存模型…</p>}
    {notice && <p role="status" className="pw-model-selection-status">{notice}</p>}
    {error && <p className="pw-field-error" role="alert">{error}</p>}
    {!review && !groupReview && <footer className="pw-ai-picker-footer">
      {scope === 'conversation' && state.conversationModel?.inherited === false && can('models.conversation.reset') && <Button variant="ghost" className="pw-conversation-model-reset" aria-label="恢复使用默认模型" disabled={locked || needsRead} onClick={() => void reset()}>使用默认</Button>}
      {showRecovery && <Button variant="ghost" leadingIcon={IconRefresh} disabled={locked} onClick={() => void refresh()}>重新读取</Button>}
      {showManage && <Button variant="ghost" trailingIcon={IconChevronRight} disabled={locked} onClick={onManage}>{scope === 'conversation' ? '管理模型' : '管理 AI 接入'}</Button>}
    </footer>}
  </section>;
}
