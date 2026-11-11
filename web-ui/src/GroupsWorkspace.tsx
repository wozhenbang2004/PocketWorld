import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import './collaboration-depth.css';
import { IconAdd, IconArrowLeft, IconArrowUpRight, IconChevronRight, IconControls, IconFileText, IconRefresh, IconShieldCheck, IconTeam } from './icons';
import { Accordion, AccordionItem, Button, CheckboxField, SearchField, SelectField, SettingsDialog, TextField } from './forms-library';
import { Tabs, TabList, Tab, TabPanel } from './shell-library';
import { acceptGroupDelivery, acceptGroupSnapshot, acceptGroupTask, confirmedGroupResult, groupCapabilities, groupDeliveryLabel, groupPolicyDraft, groupPolicyPayload, groupPresentation, groupQuotaFields, groupRows, groupTaskLabel, sameGroupVersion, type GroupData as Data } from './groups-workspace';
import './groups-workspace.css';
import { useSurfaceEnter } from './library/surface-motion';
import { MotionFeedback, WorkingStatus, useCollectionMotion } from './library/journey-motion';

type Run = (action: string, args?: Data) => Promise<any>;
type Editor = 'create' | 'member' | 'policy';
type ReadState = 'idle' | 'reading' | 'known' | 'unknown' | 'error';
const roles = [{ id: 'member', label: '普通成员' }, { id: 'admin', label: '群管理员', description: '可管理普通成员，不能扩大能力授权' }, { id: 'viewer', label: '只读成员' }];
const capabilityName = (value: string) => groupCapabilities.find(([id]) => id === value)?.[1] || value;
const time = (value: any) => typeof value === 'number' && value > 0 ? new Date(value).toLocaleString() : '尚未提供';
const copy = (value: Data) => JSON.parse(JSON.stringify(value));
const identity = (reference: Data | null) => reference ? JSON.stringify([reference.groupId, reference.spaceId, reference.requestId, reference.taskId]) : '';
const providerName = (group: Data) => group.provider === 'qq' ? group.conversationKind === 'qq_guild_channel' ? 'QQ 频道' : 'QQ 群' : '可信 Webhook 群';
const audienceName = (audience: any) => audience === 'group' ? '原群成员' : audience === 'requester' ? '原请求发起人' : '原受众待核对';

/** BoardUI controls retain their source behavior. The object-row hierarchy follows
 * OpenMuse 61796eb2 apps/mobile/src/ui.tsx LinkRow (MIT), with product-specific
 * group/request identities and reading states. Native owners remain authoritative. */
export function GroupsWorkspace({ state, data, run, supported, registerTransientBack }: { state: Data; data: Data; run: Run; supported: (action: string) => boolean; registerTransientBack: (handler: (() => boolean) | null) => void }) {
  const [snapshot, setSnapshot] = useState<Data | null>(() => acceptGroupSnapshot(null, data));
  const [groupId, setGroupId] = useState(''), [tab, setTab] = useState('tasks');
  const [queries, setQueries] = useState<Record<string, string>>({});
  const [editor, setEditor] = useState<Editor | null>(null), [original, setOriginal] = useState<Data | null>(null), [draft, setDraft] = useState<Data>({});
  const [selectedRequest, setSelectedRequest] = useState<Data | null>(null), [taskReading, setTaskReading] = useState<Data | null>(null), [readState, setReadState] = useState<ReadState>('idle');
  const [deliveries, setDeliveries] = useState<Record<string, Data>>({}), [deliveryAttempts, setDeliveryAttempts] = useState<Record<string, boolean>>({});
  const [review, setReview] = useState<Data | null>(null), [acknowledged, setAcknowledged] = useState(false);
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState('');
  const alive = useRef(true), entered = useRef(false), generation = useRef(0), reviewHandle = useRef(''), resumed = useRef(state.webWorkspaceResumeRevision);
  const backgrounded = useRef(document.visibilityState !== 'visible');
  const returnFocus = useRef('');
  const root = useRef<HTMLElement>(null), positions = useRef<Record<string, number>>({}), groupViews = useRef<Record<string, { tab: string; request: Data | null }>>({});
  const readings = useRef<Record<string, Data>>({}), drafts = useRef<Record<string, { original: Data | null; draft: Data }>>({}), draftKey = useRef('');
  const group = groupRows(snapshot?.groups).find(row => row.id === groupId);
  const requests = groupRows(snapshot?.requests).filter(row => row.groupId === groupId);
  const resources = snapshot?.nativeResources || {}, connections = groupRows(snapshot?.nativeConnections);
  const stale = !!original && !sameGroupVersion(original, groupRows(snapshot?.groups).find(row => row.id === original.id));
  const viewKey = editor ? `edit:${draftKey.current}` : !groupId ? 'groups' : `${groupId}:${tab}:${tab === 'tasks' ? identity(selectedRequest) || 'list' : ''}`;
  useSurfaceEnter(root, viewKey, { selector: '', depth: !groupId ? editor ? 1 : 0 : editor || selectedRequest && tab === 'tasks' ? 2 : 1, active: !state.webWorkspaceTaskOpen });
  const rememberPosition = () => { positions.current[viewKey] = root.current?.closest<HTMLElement>('.pw-secondary-scroll')?.scrollTop || 0; };
  useLayoutEffect(() => {
    const scroll = root.current?.closest<HTMLElement>('.pw-secondary-scroll');
    if (scroll) scroll.scrollTop = positions.current[viewKey] || 0;
    if (returnFocus.current) {
      const row = Array.from(root.current?.querySelectorAll<HTMLButtonElement>('[data-group-object]') || []).find(element => element.dataset.groupObject === returnFocus.current);
      (row || root.current)?.focus({ preventScroll: true }); returnFocus.current = '';
    }
  }, [viewKey]);
  useEffect(() => {
    const scroll = root.current?.closest<HTMLElement>('.pw-secondary-scroll');
    if (!scroll) return;
    const remember = () => { positions.current[viewKey] = scroll.scrollTop; };
    scroll.addEventListener('scroll', remember, { passive: true });
    return () => scroll.removeEventListener('scroll', remember);
  }, [viewKey]);
  const invoke = async (action: string, args: Data = {}, accept?: (result: Data) => void | Promise<void>, failed?: () => void) => {
    if (entered.current || backgrounded.current) return;
    entered.current = true; const ticket = ++generation.current; setBusy(true); setError('');
    try {
      if (!supported(action)) throw new Error('当前宿主尚未接通这项群操作。');
      const value = await run(action, args);
      if (alive.current && ticket === generation.current) await accept?.(value);
      else if (action.startsWith('groups.review') && value?.reviewHandle) void run('groups.discard', { reviewHandle: value.reviewHandle }).catch(() => {});
    } catch (failure) {
      if (alive.current && ticket === generation.current) { setError(failure instanceof Error ? failure.message : String(failure)); failed?.(); }
    } finally { if (ticket === generation.current) { entered.current = false; if (alive.current) setBusy(false); } }
  };
  const refresh = () => { void invoke('groups.snapshot', {}, value => {
    const accepted = acceptGroupSnapshot(null, value); if (!accepted) throw new Error('群列表尚未取得完整回执。');
    setSnapshot(current => acceptGroupSnapshot(current, accepted));
  }); };
  const closeReview = () => {
    const handle = reviewHandle.current; reviewHandle.current = ''; setReview(null); setAcknowledged(false);
    if (handle) void run('groups.discard', { reviewHandle: handle }).catch(() => {});
  };
  const receiveReview = (value: Data) => {
    if (typeof value.reviewHandle !== 'string' || !value.reviewHandle || !value.input || typeof value.command !== 'string') throw new Error('原操作没有形成可核对的审阅。');
    reviewHandle.current = value.reviewHandle; setReview(value); setAcknowledged(false);
  };
  const acceptReading = (reference: Data, value: Data) => {
    const key = identity(reference), actual = acceptGroupTask(reference, readings.current[key] || null, value);
    readings.current[key] = actual; setTaskReading(actual); setReadState(value.known === true ? 'known' : 'unknown');
  };
  const inspectTask = (reference: Data) => {
    if (entered.current || backgrounded.current) return;
    rememberPosition(); closeReview(); setSelectedRequest(reference); setTaskReading(readings.current[identity(reference)] || null); setReadState('reading'); setNotice('');
    void invoke('groups.inspectTask', { requestId: reference.requestId }, value => acceptReading(reference, value), () => setReadState('error'));
  };
  useEffect(() => { setSnapshot(current => acceptGroupSnapshot(current, data)); }, [data]);
  useEffect(() => {
    alive.current = true; refresh();
    return () => { alive.current = false; generation.current++; const handle = reviewHandle.current; if (handle) void run('groups.discard', { reviewHandle: handle }).catch(() => {}); };
  }, []);
  const restoreWorkspace = () => {
    closeReview(); setReadState(selectedRequest ? 'reading' : 'idle');
    // A fresh owner window is required. Ordinary input and last-read content are not authority.
    generation.current++; entered.current = false;
    const previous = selectedRequest;
    void invoke('groups.snapshot', {}, async value => {
      const accepted = acceptGroupSnapshot(null, value); if (!accepted) throw new Error('原群窗口尚未重新读取。');
      setSnapshot(current => acceptGroupSnapshot(current, accepted));
      if (previous) {
        const reference = groupRows(accepted.requests).find(row => identity(row) === identity(previous));
        if (!reference) { setSelectedRequest(null); setTaskReading(null); setReadState('idle'); setNotice('原请求不在当前群记录中，请重新核对。'); return; }
        if (!supported('groups.inspectTask')) throw new Error('当前宿主尚未接通原任务读取。');
        const ticket = generation.current, reading = await run('groups.inspectTask', { requestId: reference.requestId });
        if (alive.current && generation.current === ticket) { setSelectedRequest(reference); acceptReading(reference, reading); }
      }
    }, () => setReadState(previous ? 'error' : 'idle'));
  };
  useEffect(() => {
    if (resumed.current === state.webWorkspaceResumeRevision) return;
    resumed.current = state.webWorkspaceResumeRevision;
    if (!backgrounded.current) restoreWorkspace();
  }, [state.webWorkspaceResumeRevision]);
  useEffect(() => {
    const hide = () => { rememberPosition(); backgrounded.current = true; generation.current++; entered.current = false; setBusy(false); closeReview(); setReadState(selectedRequest ? 'unknown' : 'idle'); };
    const visibility = () => { if (document.visibilityState !== 'visible') hide(); else if (backgrounded.current) { backgrounded.current = false; restoreWorkspace(); } };
    document.addEventListener('visibilitychange', visibility); window.addEventListener('pagehide', hide);
    return () => { document.removeEventListener('visibilitychange', visibility); window.removeEventListener('pagehide', hide); };
  });
  const leaveEditor = () => {
    rememberPosition(); closeReview();
    if (editor) drafts.current[draftKey.current] = { original, draft: copy(draft) };
    setEditor(null); setOriginal(null); setError('');
  };
  const back = () => {
    if (entered.current) return true;
    if (review) { closeReview(); return true; }
    if (editor) { leaveEditor(); return true; }
    rememberPosition(); closeReview();
    if (selectedRequest && tab === 'tasks') { returnFocus.current = identity(selectedRequest); setSelectedRequest(null); setTaskReading(null); setReadState('idle'); return true; }
    if (groupId) { returnFocus.current = groupId; groupViews.current[groupId] = { tab, request: selectedRequest }; setGroupId(''); setSelectedRequest(null); setTaskReading(null); setReadState('idle'); return true; }
    return false;
  };
  useEffect(() => { registerTransientBack(back); return () => registerTransientBack(null); }, [busy, review, editor, selectedRequest, groupId, tab, draft, original, registerTransientBack]);
  useEffect(() => {
    if (review?.group && !sameGroupVersion(review.group, groupRows(snapshot?.groups).find(row => row.id === review.group.id))) { closeReview(); setError('群设置或成员已变化，请重新核对后提交。'); }
    if (selectedRequest && snapshot && !groupRows(snapshot.requests).some(row => identity(row) === identity(selectedRequest))) {
      generation.current++; entered.current = false; setBusy(false); closeReview(); setSelectedRequest(null); setTaskReading(null); setReadState('idle'); setNotice('原请求不在当前群记录中，请重新核对。');
    }
  }, [snapshot, review]);
  const chooseGroup = (row: Data) => {
    if (entered.current) return;
    rememberPosition(); closeReview(); const saved = groupViews.current[row.id];
    const reference = saved?.request && groupRows(snapshot?.requests).find(item => identity(item) === identity(saved.request));
    setGroupId(row.id); setTab(saved?.tab || 'tasks'); setSelectedRequest(reference || null); setTaskReading(reference ? readings.current[identity(reference)] || null : null); setReadState(reference ? 'reading' : 'idle'); setError(''); setNotice('');
    if (reference) void invoke('groups.inspectTask', { requestId: reference.requestId }, value => acceptReading(reference, value), () => setReadState('error'));
  };
  const changeTab = (key: string) => { if (entered.current || editor) return; rememberPosition(); closeReview(); setTab(key); setError(''); setNotice(''); };
  const openEditor = (kind: Editor, source: Data | null, value: Data, key: string) => {
    if (entered.current) return;
    rememberPosition(); closeReview(); draftKey.current = key; const saved = drafts.current[key];
    setOriginal(saved?.original || source); setDraft(saved ? copy(saved.draft) : value); setEditor(kind); setError(''); setNotice('');
  };
  const openCreate = () => openEditor('create', null, { name: '', accountId: '', roomId: '', conversationKind: 'group', conversationParentId: '' }, 'create');
  const openMember = (member?: Data) => { if (group) openEditor('member', copy(group), member ? { ...member, editingSenderId: member.senderId } : { senderId: '', displayName: '', role: 'member', enabled: true }, `${group.id}:member:${member?.senderId || 'new'}`); };
  const openPolicy = () => { if (group) openEditor('policy', copy(group), groupPolicyDraft(group), `${group.id}:policy`); };
  const resetDraft = () => {
    closeReview(); delete drafts.current[draftKey.current];
    if (editor === 'create') setDraft({ name: '', accountId: '', roomId: '', conversationKind: 'group', conversationParentId: '' });
    else if (group) {
      setOriginal(copy(group));
      if (editor === 'policy') setDraft(groupPolicyDraft(group));
      else { const member = groupRows(group.members).find(row => row.senderId === draft.editingSenderId); setDraft(member ? { ...member, editingSenderId: member.senderId } : { senderId: '', displayName: '', role: 'member', enabled: true }); }
    }
    setError(''); setNotice('已重新读取当前设置，旧草稿已清除。');
  };
  const openTask = () => { if (selectedRequest) { rememberPosition(); closeReview(); void invoke('groups.openTask', { requestId: selectedRequest.requestId }); } };
  const requestReview = () => {
    if (entered.current || review) return;
    try {
      if (stale) throw new Error('群设置已变化，请重新读取当前设置后编辑。');
      if (editor === 'create') void invoke('groups.reviewCreate', draft, receiveReview);
      if (editor === 'member' && original) void invoke('groups.reviewMember', { id: original.id, revision: original.revision, policyEpoch: original.policyEpoch, senderId: draft.senderId, displayName: draft.displayName, role: draft.role, enabled: draft.enabled }, receiveReview);
      if (editor === 'policy' && original) void invoke('groups.reviewPolicy', groupPolicyPayload(original, draft), receiveReview);
    } catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)); }
  };
  const confirm = () => {
    const frozen = review; if (!frozen || !acknowledged || entered.current || backgrounded.current) return;
    if (frozen.group && !sameGroupVersion(frozen.group, groupRows(snapshot?.groups).find(row => row.id === frozen.group.id))) { closeReview(); setError('群设置或成员已变化，请重新核对后提交。'); return; }
    reviewHandle.current = ''; setReview(null); setAcknowledged(false);
    if (frozen.command === 'delivery.send') setDeliveryAttempts(old => ({ ...old, [`${identity(frozen.reference)}:${frozen.input.outputId}`]: true }));
    void invoke('groups.confirm', { reviewHandle: frozen.reviewHandle, confirmed: true }, value => {
      const result = confirmedGroupResult(value, frozen.command);
      if (['group.create', 'member.set', 'policy.set'].includes(frozen.command)) {
        if (typeof result.id !== 'string' || !Number.isSafeInteger(result.revision) || (frozen.command === 'group.create' ? result.enabled !== false : result.id !== frozen.input.id || result.revision <= frozen.input.revision)) throw new Error('群设置保存尚未取得正确版本，请刷新核对。');
        delete drafts.current[draftKey.current]; rememberPosition(); setGroupId(result.id); setEditor(null); setOriginal(null);
        setNotice(frozen.command === 'group.create' ? '群空间已创建，当前保持关闭。请核对成员与能力后再启用。' : '原群设置已保存。旧授权的任务需按原记录重新核对。');
      } else if (frozen.command.startsWith('task.')) {
        acceptReading(frozen.reference, result); setNotice(result.known === false ? '这次原操作的状态仍待核对，请查询原任务，不要重新提交。' : '已取得原任务回执。');
      } else if (frozen.command === 'delivery.send') {
        const actual = acceptGroupDelivery(frozen.reference, result, undefined, frozen.input.outputId); setDeliveries(old => ({ ...old, [actual.deliveryId]: actual })); setNotice(groupDeliveryLabel(actual.state));
      }
    });
  };
  const task = identity(taskReading?.reference || null) === identity(selectedRequest) && taskReading?.known === true ? taskReading.task : undefined;
  const presentation = selectedRequest ? groupPresentation(selectedRequest, task, taskReading?.presentation || snapshot?.taskPresentations?.[selectedRequest.requestId]) : null;
  // The native snapshot nests output identity; inspect receipts omit the output body.
  const snapshotDeliveries: Data[] = groupRows(snapshot?.deliveries).map(row => ({ ...row, outputId: row.outputId || row.output?.outputId }));
  const mergedDeliveries = new Map<string, Data>(snapshotDeliveries.map(row => [row.deliveryId, row]));
  Object.values(deliveries).forEach(row => { if (mergedDeliveries.get(row.deliveryId)?.state !== 'delivered') mergedDeliveries.set(row.deliveryId, row); });
  const requestDeliveries = selectedRequest ? Array.from(mergedDeliveries.values()).filter(row => row.requestId === selectedRequest.requestId && row.taskId === selectedRequest.taskId) : [];
  const change = (key: string, value: any) => { closeReview(); setDraft(old => ({ ...old, [key]: value })); };
  const selectedConnection = connections.find(row => row.id === draft.accountId);
  const field = (key: string, label: string, hint?: string, disabled = false) => <TextField name={key} label={label} value={String(draft[key] ?? '')} onChange={value => change(key, value)} hint={hint} isRequired isDisabled={busy || disabled} />;
  const editorTitle = editor === 'create' ? '新建群空间' : editor === 'member' ? draft.editingSenderId ? '编辑群成员' : '添加群成员' : '编辑能力与额度';
  const formReady = !busy && !stale && (editor === 'create' ? !!draft.accountId && !!draft.name?.trim() && !!draft.roomId?.trim() && (draft.conversationKind !== 'qq_guild_channel' || !!draft.conversationParentId?.trim()) : editor === 'member' ? !!draft.senderId?.trim() && !!draft.displayName?.trim() : true);
  const canActOnTask = readState === 'known' && !!task && !busy;
  const requestSender = selectedRequest && (groupRows(group?.members).find(member => member.senderId === selectedRequest.senderId)?.displayName || selectedRequest.senderId || '原群请求');
  const searchKey = groupId || 'groups', query = queries[searchKey] || '';
  const setQuery = (value: string) => setQueries(previous => ({ ...previous, [searchKey]: value }));
  const matches = (values: any[]) => values.filter(Boolean).join(' ').toLocaleLowerCase().includes(query.trim().toLocaleLowerCase());
  const groups = groupRows(snapshot?.groups);
  const visibleGroups = groups.filter(row => matches([row.name, providerName(row), row.roomId]));
  const firstGroup = !!snapshot && !groups.length && !query;
  const connectionRequired = !!snapshot && !connections.some(row => row.enabled === true);
  const groupSections = [
    { id: 'enabled', title: '已启用的群', rows: visibleGroups.filter(row => row.enabled === true) },
    { id: 'disabled', title: '尚未启用', rows: visibleGroups.filter(row => row.enabled !== true) },
  ];
  const visibleRequests = requests.filter(reference => matches([reference.goal, reference.senderId, capabilityName(reference.capability), groupRows(group?.members).find(member => member.senderId === reference.senderId)?.displayName]));

  useCollectionMotion(root, JSON.stringify([viewKey, query.trim(), state.webWorkspaceResumeRevision]), JSON.stringify([
    visibleGroups.map(row => [row.id, row.enabled, row.revision]),
    visibleRequests.map(row => [identity(row), readings.current[identity(row)]?.task?.status]),
    groupRows(group?.members).map(member => [member.senderId, member.enabled, member.role]),
    groupRows(task?.outputs).map(output => output.outputId),
    requestDeliveries.map(delivery => [delivery.deliveryId, delivery.state]), readState,
  ]), { active: !!snapshot && (!selectedRequest || readState === 'known') && !editor && !state.webWorkspaceTaskOpen });
  return <section ref={root} className="pw-group-workspace pw-depth-collaboration" tabIndex={-1} data-group-view={viewKey}>
    <header className="pw-group-header" data-request-open={!!selectedRequest || undefined}>
      <div className="pw-group-heading">
        {(groupId || editor) && <Button type="button" variant="ghost" iconOnly leadingIcon={IconArrowLeft} disabled={busy} aria-label={editor ? '返回并保留草稿' : selectedRequest && tab === 'tasks' ? '返回请求列表' : '返回群列表'} onClick={back} />}
        <div>{group ? <><p className="pw-group-eyebrow">{providerName(group)}</p><h2>{group.name}</h2></> : <h2>群空间</h2>}<WorkingStatus active={busy}>正在读取原操作回执…</WorkingStatus></div>
        {group && <span className="pw-group-state" data-enabled={group.enabled === true}>{group.enabled ? '服务已启用' : '服务已关闭'}</span>}
      </div>
      <div className="pw-group-tools">
        <Button type="button" variant="ghost" iconOnly leadingIcon={IconRefresh} disabled={busy} aria-label="刷新原群记录" onClick={refresh} />
        {!editor && !groupId && !firstGroup && <Button type="button" leadingIcon={IconAdd} disabled={busy} onClick={openCreate}>新建群空间</Button>}
      </div>
    </header>
    {group && <p className="pw-group-context">{groupRows(group.members).filter(member => member.enabled).length} 位已启用成员 · {requests.length} 项请求 · {group.visibility === 'members' ? '进度可按任务开放给群成员' : '任务进度仅发起人可见'}</p>}
    <MotionFeedback identity={`${error}:${notice}`} initial={false} className="pw-group-feedback" aria-live="polite">
      {error && <p className="pw-group-warning" role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}
    </MotionFeedback>
    {!snapshot && !busy && <div className="pw-group-empty"><IconTeam aria-hidden /><h3>群记录尚未读取</h3><p>刷新群记录后继续。</p></div>}
    {!editor && !groupId && <div className="pw-collaboration-directory" aria-label="群空间列表">
      {(groups.length > 0 || !!query) && <SearchField fieldClassName="pw-collaboration-search" shape="rounded" aria-label="搜索群空间" placeholder="搜索群名称或渠道" value={query} onChange={setQuery} />}
      {groupSections.filter(section => section.rows.length > 0).map(section => <section className="pw-collaboration-group" key={section.id} aria-label={section.title}><header className="pw-collaboration-group-heading"><h3>{section.title}</h3><span>{section.rows.length}</span></header><div className="pw-group-collection">
      {section.rows.map(row => <Button key={row.id} data-group-object={row.id} data-motion-key={`group:${row.id}`} type="button" variant="ghost" className="pw-group-object-row" disabled={busy} aria-label={`打开群空间：${row.name}`} onClick={() => chooseGroup(row)}>
        <span className="pw-group-row-icon"><IconTeam size={20} aria-hidden /></span>
        <span className="pw-group-row-copy"><strong>{row.name}</strong><span>{providerName(row)} · {groupRows(row.members).filter(member => member.enabled).length} 位成员</span></span>
        <span className="pw-group-row-end"><span>{groupRows(snapshot?.requests).filter(request => request.groupId === row.id).length} 项请求</span><IconChevronRight size={17} aria-hidden /></span>
      </Button>)}</div></section>)}
      {snapshot && query && !visibleGroups.length && <div className="pw-group-empty"><h3>没有匹配的群空间</h3><p>换个名称或渠道试试，也可以清除搜索。</p><Button type="button" variant="ghost" onClick={() => setQuery('')}>清除搜索</Button></div>}
      {firstGroup && <div className="pw-group-directory-empty"><span className="pw-group-directory-icon"><IconTeam size={26} aria-hidden /></span><div><h3>还没有群空间</h3><p>把群消息、成员和协作任务放在一起，按群设置能力与授权。</p></div><Button type="button" leadingIcon={IconAdd} disabled={busy} onClick={openCreate}>新建群空间</Button></div>}
    </div>}
    {groupId && !group && !editor && <p className="pw-group-warning" role="alert">这个群空间当前未读取到，请刷新核对。</p>}

    {editor && <form className="pw-group-editor" aria-label={editorTitle} onSubmit={event => { event.preventDefault(); if (formReady) requestReview(); }}>
      <header className="pw-group-section-heading"><div><h3>{editorTitle}</h3></div></header>
      {editor === 'create' && <>
        {connectionRequired && <div className="pw-group-create-prerequisite"><IconTeam size={24} aria-hidden /><div><h4>{connections.length ? '先启用一个机器人连接' : '先添加一个机器人连接'}</h4><p>{connections.length ? '当前机器人连接均未启用。' : '当前尚未配置 QQ 或可信 Webhook 连接。'}请在上方「消息渠道」的「连接与接收」中{connections.length ? '启用连接' : '添加连接'}，再选择要关联的群。</p></div></div>}
        <div className="pw-group-form-grid">{field('name', '群空间名称')}
          <SelectField label="机器人连接" value={draft.accountId} onChange={value => { closeReview(); setDraft(old => ({ ...old, accountId: value, conversationKind: 'group', conversationParentId: '', roomId: '' })); }} options={connections.map(row => ({ id: row.id, label: `${row.name || row.id} · ${row.provider}`, disabled: row.enabled !== true }))} isDisabled={busy || connectionRequired} hint="使用已配置的 QQ 或可信 Webhook 连接。" />
          <SelectField label="群场景" value={draft.conversationKind} onChange={value => { closeReview(); setDraft(old => ({ ...old, conversationKind: value, conversationParentId: '', roomId: '' })); }} options={[{ id: 'group', label: '普通群' }, { id: 'qq_guild_channel', label: 'QQ 频道子频道', disabled: selectedConnection?.provider !== 'qq' || selectedConnection?.qqGuildEnabled !== true }]} isDisabled={busy || !draft.accountId} />
          {draft.conversationKind === 'qq_guild_channel' && field('conversationParentId', '原频道 guild_id')}
          {field('roomId', draft.conversationKind === 'qq_guild_channel' ? '原子频道 channel_id' : selectedConnection?.provider === 'qq' ? '原群 group_openid' : '原群 roomId', '填写原平台身份，群号或显示名称不能替代。')}
        </div>
        <p className="pw-group-scope"><IconShieldCheck size={18} aria-hidden />新空间默认关闭。普通群与频道的成员、能力和授权范围相互独立。</p>
      </>}
      {editor === 'member' && original && <>
        <div className="pw-group-form-grid">{field('displayName', '成员显示名称')}{field('senderId', '原平台成员身份', '使用原群事件中的 senderId。', !!draft.editingSenderId)}<SelectField label="成员角色" value={draft.role} onChange={value => change('role', value)} options={roles} isDisabled={busy} /></div>
        <CheckboxField isSelected={draft.enabled === true} onChange={value => change('enabled', value)} isDisabled={busy}>启用这位成员</CheckboxField>
        <p className="pw-group-scope"><IconShieldCheck size={18} aria-hidden />群管理员不能扩大工具权限。更改角色或启用状态会更新成员授权代次，旧任务需按原记录重新核对。</p>
      </>}
      {editor === 'policy' && original && <>
        <div className="pw-group-policy-access"><CheckboxField isSelected={draft.enabled === true} onChange={value => change('enabled', value)} isDisabled={busy}>启用这个群空间的服务</CheckboxField><SelectField label="任务进度可见范围" value={draft.visibility} onChange={value => change('visibility', value)} options={[{ id: 'requester', label: '仅发起人' }, { id: 'members', label: '允许任务选择群成员可见' }]} isDisabled={busy} /></div>
        <p className="pw-group-scope"><IconShieldCheck size={18} aria-hidden />结果能否发到群里需逐项授权。当前渠道仅支持原群被动回复，未开放给群受众的结果不会改投私聊。</p>
        <div className="pw-group-capabilities">{groupCapabilities.map(([capability, label]) => {
          const grant = groupRows(draft.grants).find(row => row.capability === capability), available = resources[capability] === true;
          return <section className="pw-group-capability" key={capability} aria-label={label}>
            <CheckboxField isSelected={!!grant} isDisabled={busy || !available && !grant} onChange={value => change('grants', value ? [...groupRows(draft.grants), { capability, resource: capability.startsWith('public.') ? 'public' : capability === 'group.files' ? original.spaceId : resources.mediaResource, allowGroupAudience: false }] : groupRows(draft.grants).filter(row => row.capability !== capability))}>{label}</CheckboxField>
            {!available && <p className="pw-group-warning">当前服务不可用</p>}
            {grant && <div className="pw-group-grant-detail"><CheckboxField isSelected={grant.allowGroupAudience === true} onChange={value => change('grants', groupRows(draft.grants).map(row => row.capability === capability ? { ...row, allowGroupAudience: value } : row))} isDisabled={busy}>允许把这项能力的结果发到群里</CheckboxField>
              {capability.startsWith('media.') && <><p className="pw-muted">当前服务：{resources.mediaProvider || '尚未提供'} · {capability === 'media.image' ? resources.imageModel : capability === 'media.audio' ? resources.ttsModel : '视频服务'}</p>{grant.resource !== resources.mediaResource && <p className="pw-group-warning" role="alert">媒体配置已经变化，旧授权不能迁移到新服务。</p>}<Accordion><AccordionItem id={`service-${capability}`} title="核对媒体服务地址"><p className="pw-group-identifier">{resources.mediaBaseUrl || '尚未提供'}</p><Button type="button" variant="secondary" disabled={busy || !available} onClick={() => change('grants', groupRows(draft.grants).map(row => row.capability === capability ? { ...row, resource: resources.mediaResource } : row))}>明确选择当前媒体服务</Button></AccordionItem></Accordion></>}
            </div>}
          </section>;
        })}</div>
        {field('hours', '本次所选能力授权时长（1–744 小时）')}
        <Accordion><AccordionItem id="quota" title="任务与媒体额度" description={`全群每日 ${draft.quota?.dailyTasks || '未设置'} 项任务 · 同时运行 ${draft.quota?.maxConcurrent || '未设置'} 项`}><div className="pw-group-form-grid">{groupQuotaFields.map(([key, label, min, max]) => <TextField key={key} name={key} label={label} value={draft.quota?.[key] ?? ''} onChange={value => change('quota', { ...draft.quota, [key]: value })} hint={`${min}–${max}`} inputMode="numeric" isRequired isDisabled={busy} />)}</div></AccordionItem></Accordion>
        <p className="pw-group-scope">模型额度是本地累计保守预留，不是服务商账单 token。媒体能力需要至少 32000 的单次与每日预留，额度不会自动提高。保存会使旧群授权失效，已完成的外部操作无法撤回。</p>
      </>}
      {stale && <p className="pw-group-warning" role="alert">群设置已变化。这份草稿仍保留，请重新读取当前设置后编辑，旧审阅不能继续使用。</p>}
      <footer className="pw-group-editor-actions"><Button type="submit" disabled={!formReady}>{editor === 'create' ? '核对并创建' : editor === 'member' ? '核对成员变更' : '核对能力与额度'}</Button><Button type="button" variant="ghost" disabled={busy} onClick={leaveEditor}>返回并保留草稿</Button><Button type="button" variant="ghost" disabled={busy} onClick={resetDraft}>{stale ? '重新读取当前设置' : '清除这份草稿'}</Button></footer>
    </form>}

    {!editor && group && <Tabs className="pw-group-tabs" selectedKey={tab} onSelectionChange={key => changeTab(String(key))}>
      <TabList aria-label="群工作区"><Tab id="tasks" isDisabled={busy}>群请求</Tab><Tab id="members" isDisabled={busy}>成员</Tab><Tab id="policy" isDisabled={busy}>能力与额度</Tab></TabList>
      <TabPanel id="tasks">
        {!selectedRequest && <div className="pw-group-request-directory" aria-label="群请求列表">
          {requests.length > 0 && <><SearchField fieldClassName="pw-collaboration-search" shape="rounded" aria-label="搜索群请求" placeholder="搜索目标或发起人" value={query} onChange={setQuery} /><header className="pw-collaboration-group-heading"><h3>这个群的请求</h3><span>{visibleRequests.length} 项</span></header></>}
          <div className="pw-group-collection">{visibleRequests.map(reference => {
            const read = readings.current[identity(reference)], member = groupRows(group.members).find(row => row.senderId === reference.senderId);
            return <Button key={identity(reference)} data-group-object={identity(reference)} data-motion-key={`request:${identity(reference)}`} type="button" variant="ghost" className="pw-group-object-row pw-group-request-row" disabled={busy} aria-label={`读取群请求：${reference.goal || '原群请求'}`} onClick={() => inspectTask(reference)}>
              <span className="pw-group-row-icon"><IconFileText size={19} aria-hidden /></span><span className="pw-group-row-copy"><strong>{reference.goal || '原群请求'}</strong><span>{member?.displayName || reference.senderId || '原群请求'} · {capabilityName(reference.capability)}</span><span>结果受众：{audienceName(reference.audience)}</span></span>
              <span className="pw-group-row-end">{read?.known === true && <span className="pw-group-row-status">上次读取：{groupTaskLabel(read.task?.status)}</span>}<IconChevronRight size={17} aria-hidden /></span>
            </Button>;
          })}</div>
          {query && requests.length > 0 && !visibleRequests.length && <p className="pw-secondary-empty">没有匹配的群请求。</p>}
          {requests.length === 0 && <div className="pw-group-empty"><IconFileText aria-hidden /><h3>群里的请求会出现在这里</h3><p>启用成员与能力后，从已认证的原群渠道发起。任务、审批和结果都沿用原记录。</p><div className="pw-group-actions"><Button type="button" variant="secondary" onClick={() => changeTab('members')}>查看群成员</Button><Button type="button" variant="ghost" onClick={() => changeTab('policy')}>查看已授权能力</Button></div></div>}
        </div>}
        {selectedRequest && <article className="pw-group-reading" aria-label="群请求详情">
          <header className="pw-group-reading-heading"><p className="pw-group-eyebrow">{capabilityName(selectedRequest.capability)} · {requestSender}</p><h3>{selectedRequest.goal || '原群任务'}</h3><p className="pw-group-task-status">{readState !== 'known' && task ? '上次读取：' : ''}{groupTaskLabel(task?.status)}</p></header>
          <MotionFeedback identity={JSON.stringify([readState, task?.status, presentation?.progress?.message, presentation?.approval?.message, presentation?.recovery?.message])} initial={false} className="pw-group-reading-status">          {(readState === 'unknown' || readState === 'error') && <p className="pw-group-warning" role="status">原任务当前状态{readState === 'error' ? '读取失败' : '尚未知'}。{task ? '下方保留上次读取的内容，不能据此继续、停止或发回。' : ''}这里只查询原引用，不重新提交任务。</p>}
          {readState === 'reading' && <p className="pw-muted" role="status">正在核对原任务，已读内容保留在下方。</p>}
          {presentation?.progress?.message && <p>{presentation.progress.message}</p>}
          {(presentation?.approval?.message || task?.status === 'awaiting_user') && <p className="pw-group-warning" role="status">{presentation?.approval?.message || '这项任务等待主人处理，请进入原任务核对审批。'}</p>}
          {(presentation?.recovery?.message || ['reconciling', 'needs_confirmation'].includes(task?.status)) && <p className="pw-group-warning" role="status">{presentation?.recovery?.message || '执行效果待核对，请在原任务确认已有结果，不要重新提交。'}</p>}
</MotionFeedback>
          <section className="pw-group-request-controls" aria-label="原请求范围与操作">          <p className="pw-group-scope"><IconShieldCheck size={18} aria-hidden />结果受众：{audienceName(selectedRequest.audience)}。{task?.resultPrivate === true ? '这份结果保持原私人范围，不提供群投递。' : '不会改投其他群或私聊。'}</p>
          <div className="pw-group-actions"><Button type="button" trailingIcon={IconArrowUpRight} disabled={busy} onClick={openTask}>打开原任务与审批</Button><Button type="button" variant="ghost" leadingIcon={IconRefresh} disabled={busy} onClick={() => inspectTask(selectedRequest)}>重新读取原状态</Button></div>
          <div className="pw-group-actions pw-group-run-actions">{task && ['paused', 'interrupted'].includes(task.status) && <Button type="button" variant="secondary" disabled={!canActOnTask} onClick={() => { void invoke('groups.reviewTask', { requestId: selectedRequest.requestId, intent: 'resume' }, receiveReview); }}>核对并继续原任务</Button>}{task && typeof task.runId === 'string' && task.coordinatorEpoch > 0 && !['completed', 'failed', 'cancelled'].includes(task.status) && <Button type="button" variant="ghost" disabled={!canActOnTask} onClick={() => { void invoke('groups.reviewTask', { requestId: selectedRequest.requestId, intent: 'cancel' }, receiveReview); }}>核对并停止原运行</Button>}</div>
          </section>
          <div className="pw-group-reading-layout"><div className="pw-group-reading-main">          {task?.summary && <section className="pw-group-result"><h4>任务摘要</h4><p className="pw-group-copy">{task.summary}</p></section>}
          {task?.resultPrivate !== true && groupRows(task?.outputs).map((output, index) => {
            const previous = requestDeliveries.find(row => row.outputId === output.outputId && row.audience === output.audience);
            const attempted = deliveryAttempts[`${identity(selectedRequest)}:${output.outputId}`];
            return <section className="pw-group-result" key={output.outputId} data-motion-key={`output:${output.outputId}`} aria-label={`任务产物 ${index + 1}`}><header><h4>{output.mimeType?.startsWith('text/') ? '文本结果' : '任务产物'}{groupRows(task?.outputs).length > 1 ? ` ${index + 1}` : ''}</h4><span>受众：{audienceName(output.audience)}</span></header>
              {output.text && <p className="pw-group-copy">{output.text}</p>}<p className="pw-muted">{output.mimeType}{typeof output.bytes === 'number' ? ` · ${output.bytes} 字节` : ''}</p>
              <div className="pw-group-actions">{output.artifactId && <Button type="button" variant="secondary" disabled={busy} onClick={openTask}>在原任务查看文件</Button>}{previous ? <p className={previous.state === 'unknown' ? 'pw-group-warning' : 'pw-group-delivery-state'}>{groupDeliveryLabel(previous.state)}</p> : attempted ? <p className="pw-group-warning" role="status">这份产物的投递结果待核对，请刷新原群记录；不会再次投递。</p> : <Button type="button" variant="secondary" disabled={!canActOnTask || task?.status !== 'completed'} onClick={() => { void invoke('groups.reviewDelivery', { requestId: selectedRequest.requestId, outputId: output.outputId }, receiveReview); }}>核对原受众并发回</Button>}</div>
            </section>;
          })}
          {task && !task.summary && groupRows(task.outputs).length === 0 && <p className="pw-group-reading-empty">{task.resultPrivate ? '请在原任务继续阅读私人结果。' : '尚未形成可阅读的结果，后续进展仍在这项原任务中。'}</p>}
          {requestDeliveries.length > 0 && <section className="pw-group-deliveries"><h4>投递记录</h4>{requestDeliveries.map(delivery => <div className="pw-group-delivery" key={delivery.deliveryId} data-motion-key={`delivery:${delivery.deliveryId}`}>
            <div><strong>{groupDeliveryLabel(delivery.state)}</strong><p>受众：{audienceName(delivery.audience)}</p>{(delivery.message || delivery.error) && <p className="pw-group-copy">{delivery.message || delivery.error}</p>}</div>
            <Button type="button" variant="ghost" disabled={busy} onClick={() => { void invoke('groups.inspectDelivery', { deliveryId: delivery.deliveryId }, value => { const actual = acceptGroupDelivery(selectedRequest, value, delivery.deliveryId); setDeliveries(old => ({ ...old, [actual.deliveryId]: { ...delivery, ...actual, outputId: delivery.outputId } })); }); }}>查询原投递，不重新发送</Button>
          </div>)}</section>}
</div><aside className="pw-group-reading-context" aria-label="原请求记录">          <Accordion className="pw-group-diagnostics"><AccordionItem id="request-identity" title="原始引用与运行信息" description="请求、任务、执行代次和投递编号"><dl className="pw-facts"><div><dt>原请求</dt><dd>{selectedRequest.requestId}</dd></div><div><dt>原任务</dt><dd>{selectedRequest.taskId}</dd></div><div><dt>原空间</dt><dd>{selectedRequest.spaceId}</dd></div><div><dt>原运行</dt><dd>{task?.runId || '尚未提供'}</dd></div><div><dt>执行代次</dt><dd>{task?.coordinatorEpoch ?? '尚未提供'}</dd></div><div><dt>任务版本</dt><dd>{task?.revision ?? '尚未提供'}</dd></div></dl>{requestDeliveries.map(delivery => <p className="pw-group-identifier" key={delivery.deliveryId}>投递 {delivery.deliveryId} · 产物 {delivery.outputId}{delivery.platformMessageId ? ` · 平台消息 ${delivery.platformMessageId}` : ''}</p>)}</AccordionItem></Accordion></aside></div>
        </article>}
      </TabPanel>
      <TabPanel id="members"><section className="pw-group-members"><header className="pw-group-section-heading"><div><h3>谁能在群里发起请求</h3><p>成员角色与启用状态仅作用于这个群空间。</p></div><Button type="button" leadingIcon={IconAdd} disabled={busy} onClick={() => openMember()}>添加群成员</Button></header>
        <div className="pw-group-collection">{groupRows(group.members).map(member => <Button key={member.senderId} data-motion-key={`member:${member.senderId}`} type="button" variant="ghost" className="pw-group-object-row" disabled={busy} aria-label={`编辑群成员：${member.displayName}`} onClick={() => openMember(member)}><span className="pw-group-avatar" aria-hidden>{String(member.displayName || '?').slice(0, 1)}</span><span className="pw-group-row-copy"><strong>{member.displayName}</strong><span>{roles.find(role => role.id === member.role)?.label || '角色待核对'}</span></span><span className="pw-group-row-end"><span>{member.enabled ? '已启用' : '已停用'}</span><IconChevronRight size={17} aria-hidden /></span></Button>)}</div>
        {groupRows(group.members).length === 0 && <p className="pw-group-reading-empty">还没有授权成员。添加成员后，对方才能通过原群渠道发起请求。</p>}
        <p className="pw-group-scope"><IconShieldCheck size={18} aria-hidden />群管理员可管理普通成员，不能扩大工具权限。</p>
      </section></TabPanel>
      <TabPanel id="policy"><section className="pw-group-policy"><header className="pw-group-section-heading"><div><h3>群里可以做什么</h3><p>{group.enabled ? '群服务已启用' : '群服务已关闭'} · 进度{group.visibility === 'members' ? '可按任务对群成员可见' : '仅发起人可见'}</p></div><Button type="button" variant="secondary" leadingIcon={IconControls} disabled={busy} onClick={openPolicy}>编辑能力与额度</Button></header>
        <div className="pw-group-policy-list">{groupRows(group.grants).map(grant => <div className="pw-group-policy-row" key={grant.capability}><div><strong>{capabilityName(grant.capability)}</strong><p>{grant.allowGroupAudience ? '允许发回原群成员' : '仅原请求发起人受众'}</p></div><span>到期 {time(grant.expiresAt)}</span></div>)}</div>
        {groupRows(group.grants).length === 0 && <p className="pw-group-reading-empty">尚未授权任何能力。服务开启前，需要明确选择能力与结果受众。</p>}
        <p className="pw-group-scope"><IconShieldCheck size={18} aria-hidden />能力授权不代替每项原任务的审批。结果保持原受众，私人结果不会发回群里。</p>
        <Accordion><AccordionItem id="saved-quota" title="任务与媒体额度" description={`全群每日 ${group.quota?.dailyTasks ?? '未设置'} 项任务 · 同时运行 ${group.quota?.maxConcurrent ?? '未设置'} 项`}><dl className="pw-facts">{groupQuotaFields.map(([key, label]) => <div key={key}><dt>{label}</dt><dd>{group.quota?.[key] ?? '尚未提供'}</dd></div>)}</dl><p className="pw-muted">模型额度是本地累计保守预留，不是服务商账单 token。</p></AccordionItem></Accordion>
      </section></TabPanel>
    </Tabs>}
    <SettingsDialog isOpen={!!review} onClose={() => { if (!busy) closeReview(); }} title={review?.command === 'group.create' ? '创建这个群空间' : review?.command === 'delivery.send' ? '发回这份原产物' : review?.command?.startsWith('task.') ? '核对原任务操作' : '保存这份群设置'}><div className="pw-collaboration-dialog" data-collaboration-kind="GroupsWorkspace">
      {review && <div className="pw-group-review">
        <p className="pw-group-review-context">{review.group?.name || review.input.name}</p>
        {review.command === 'group.create' && <><p>连接 {review.connection?.name || review.input.accountId} · {review.input.conversationKind === 'qq_guild_channel' ? `频道 ${review.input.conversationParentId} / ` : ''}{review.input.roomId}</p><p className="pw-group-scope">空间创建后保持关闭，继续添加成员与授权。</p></>}
        {review.command === 'member.set' && <><p>{review.input.displayName} · {review.input.senderId} · {roles.find(role => role.id === review.input.role)?.label} · {review.input.enabled ? '启用' : '停用'}</p><p>保存会更新原成员授权代次，群管理员不能扩大工具权限。</p></>}
        {review.command === 'policy.set' && <><p>{review.input.enabled ? '启用' : '关闭'}群服务 · {review.input.visibility === 'members' ? '允许群成员可见进度' : '仅发起人可见进度'}</p>{groupRows(review.input.grants).map(grant => <p key={grant.capability}>{capabilityName(grant.capability)} · {grant.allowGroupAudience ? '允许群结果受众' : '原发起人受众'} · 到期 {time(grant.expiresAt)}</p>)}<dl className="pw-facts">{groupQuotaFields.map(([key, label]) => <div key={key}><dt>{label}</dt><dd>{review.input.quota[key]}</dd></div>)}</dl><p className="pw-group-warning">本次保存会更新群策略版本，旧任务授权不自动迁移。已经完成的外部操作无法撤回。</p></>}
        {review.command?.startsWith('task.') && <><p>{review.reference?.goal} · {groupTaskLabel(review.task?.status)}</p><p className="pw-group-warning">{review.command === 'task.cancel' ? '停止这项原任务当前读取到的执行代次。已完成的外部操作无法撤回。' : '在原任务上继续或恢复，仍需通过原能力与审批核验。'}</p><p className="pw-muted">原运行 {review.input.runId || '尚未派发'} · 代次 {review.input.coordinatorEpoch} · 原版本 {review.task?.revision}</p></>}
        {review.command === 'delivery.send' && <>{groupRows(review.task?.outputs).filter(output => output.outputId === review.input.outputId).map(output => <div key={output.outputId}><p className="pw-group-scope">{output.audience === 'group' ? `发往原群：${review.group?.name}` : '受众保持原请求发起人'}</p><p className="pw-group-copy">{output.text || `${output.mimeType} · 原产物 ${output.outputId}`}</p></div>)}<p>使用这项原请求的原渠道与受众。平台接受与实际送达分别记录；未知结果只查询，不重复投递。</p></>}
        <CheckboxField isSelected={acknowledged} onChange={setAcknowledged} isDisabled={busy}>我已核对对象、范围与影响，确认这一次操作</CheckboxField>
        <div className="pw-group-actions"><Button type="button" variant="secondary" disabled={busy} onClick={closeReview}>返回核对</Button><Button type="button" disabled={busy || !acknowledged} onClick={confirm}>{review.command === 'delivery.send' ? '确认发回原产物' : review.command === 'task.cancel' ? '确认停止原运行' : review.command === 'task.resume' ? '确认继续原任务' : '确认保存'}</Button></div>
      </div>}
    </div></SettingsDialog>
  </section>;
}
