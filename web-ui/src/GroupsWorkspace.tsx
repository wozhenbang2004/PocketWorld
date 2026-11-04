import { useEffect, useRef, useState } from 'react';
import { Accordion, AccordionItem, Button, CheckboxField, SelectField, SettingsDialog, TextField } from './forms-library';
import { Tabs, TabList, Tab, TabPanel } from './shell-library';
import { acceptGroupDelivery, acceptGroupSnapshot, acceptGroupTask, confirmedGroupResult, groupCapabilities, groupDeliveryLabel, groupPolicyDraft, groupPolicyPayload, groupPresentation, groupQuotaFields, groupRows, groupTaskLabel, sameGroupVersion, type GroupData as Data } from './groups-workspace';

type Run = (action: string, args?: Data) => Promise<any>;
const roles = [{ id: 'member', label: '普通成员' }, { id: 'admin', label: '群管理员', description: '可管理普通成员，不能扩大能力授权' }, { id: 'viewer', label: '只读成员' }];
const capabilityName = (value: string) => groupCapabilities.find(([id]) => id === value)?.[1] || value;
const time = (value: any) => typeof value === 'number' && value > 0 ? new Date(value).toLocaleString() : '尚未提供';
const copy = (value: Data) => JSON.parse(JSON.stringify(value));

/** A group owner window: requests, members and policy keep their original native authorities. */
export function GroupsWorkspace({ state, data, run, supported, registerTransientBack }: { state: Data; data: Data; run: Run; supported: (action: string) => boolean; registerTransientBack: (handler: (() => boolean) | null) => void }) {
  const [snapshot, setSnapshot] = useState<Data | null>(() => acceptGroupSnapshot(null, data));
  const [groupId, setGroupId] = useState(''), [tab, setTab] = useState('tasks');
  const [editor, setEditor] = useState<'create' | 'member' | 'policy' | null>(null), [original, setOriginal] = useState<Data | null>(null), [draft, setDraft] = useState<Data>({});
  const [selectedRequest, setSelectedRequest] = useState<Data | null>(null), [taskReading, setTaskReading] = useState<Data | null>(null), [deliveries, setDeliveries] = useState<Record<string, Data>>({});
  const [review, setReview] = useState<Data | null>(null), [acknowledged, setAcknowledged] = useState(false);
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState('');
  const alive = useRef(true), entered = useRef(false), generation = useRef(0), reviewHandle = useRef(''), resumed = useRef(state.webWorkspaceResumeRevision);
  const backgrounded = useRef(document.visibilityState !== 'visible');
  const group = groupRows(snapshot?.groups).find(row => row.id === groupId);
  const resources = snapshot?.nativeResources || {};
  const connections = groupRows(snapshot?.nativeConnections);
  const stale = !!original && !sameGroupVersion(original, groupRows(snapshot?.groups).find(row => row.id === original.id));
  const invoke = async (action: string, args: Data = {}, accept?: (result: Data) => void | Promise<void>) => {
    if (entered.current || backgrounded.current) return; entered.current = true; const ticket = ++generation.current; setBusy(true); setError('');
    try { if (!supported(action)) throw new Error('当前宿主尚未接通这项群操作。'); const value = await run(action, args); if (alive.current && ticket === generation.current) await accept?.(value); }
    catch (failure) { if (alive.current && ticket === generation.current) setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { if (ticket === generation.current) { entered.current = false; if (alive.current) setBusy(false); } }
  };
  const refresh = () => { void invoke('groups.snapshot', {}, value => { const accepted = acceptGroupSnapshot(null, value); if (!accepted) throw new Error('群列表尚未取得完整回执。'); setSnapshot(current => acceptGroupSnapshot(current, accepted)); }); };
  const closeReview = () => { const handle = reviewHandle.current; reviewHandle.current = ''; setReview(null); setAcknowledged(false); if (handle) void run('groups.discard', { reviewHandle: handle }).catch(() => {}); };
  const receiveReview = (value: Data) => { if (typeof value.reviewHandle !== 'string' || !value.reviewHandle || !value.input || typeof value.command !== 'string') throw new Error('原操作没有形成可核对的审阅。'); reviewHandle.current = value.reviewHandle; setReview(value); setAcknowledged(false); };
  useEffect(() => { setSnapshot(current => acceptGroupSnapshot(current, data)); }, [data]);
  useEffect(() => { alive.current = true; refresh(); return () => { alive.current = false; generation.current++; const handle = reviewHandle.current; if (handle) void run('groups.discard', { reviewHandle: handle }).catch(() => {}); }; }, []);
  const restoreWorkspace = () => {
    closeReview(); setTaskReading(null); setDeliveries({});
    // Returning from the original task opens a fresh native group window. Keep unsaved fields.
    generation.current++; entered.current = false;
    const previous = selectedRequest;
    void invoke('groups.snapshot', {}, async value => {
      const accepted = acceptGroupSnapshot(null, value); if (!accepted) throw new Error('原群窗口尚未重新读取。');
      setSnapshot(current => acceptGroupSnapshot(current, accepted));
      if (previous) {
        const reference = groupRows(accepted.requests).find(row => row.requestId === previous.requestId && row.groupId === previous.groupId && row.taskId === previous.taskId);
        if (!reference) { setSelectedRequest(null); setNotice('原请求不在当前群记录中，请重新核对。'); return; }
        const ticket = generation.current; const reading = await run('groups.inspectTask', { requestId: reference.requestId });
        if (alive.current && generation.current === ticket) { const actual = acceptGroupTask(reference, null, reading); setSelectedRequest(reference); setTaskReading(actual); }
      }
    });
  };
  useEffect(() => {
    if (resumed.current === state.webWorkspaceResumeRevision) return;
    resumed.current = state.webWorkspaceResumeRevision;
    if (!backgrounded.current) restoreWorkspace();
  }, [state.webWorkspaceResumeRevision]);
  useEffect(() => {
    const hide = () => {
      backgrounded.current = true; generation.current++; entered.current = false;
      setBusy(false); closeReview(); setTaskReading(null); setDeliveries({});
    };
    const visibility = () => {
      if (document.visibilityState !== 'visible') hide();
      else if (backgrounded.current) { backgrounded.current = false; restoreWorkspace(); }
    };
    document.addEventListener('visibilitychange', visibility); window.addEventListener('pagehide', hide);
    return () => { document.removeEventListener('visibilitychange', visibility); window.removeEventListener('pagehide', hide); };
  });
  useEffect(() => {
    registerTransientBack(() => {
      if (busy) return true;
      if (review) { closeReview(); return true; }
      if (editor) { setEditor(null); setOriginal(null); return true; }
      if (selectedRequest) { setSelectedRequest(null); setTaskReading(null); return true; }
      if (groupId) { setGroupId(''); setTab('tasks'); return true; }
      return false;
    });
    return () => registerTransientBack(null);
  }, [busy, review, editor, selectedRequest, groupId, registerTransientBack]);
  useEffect(() => {
    if (review?.group && !sameGroupVersion(review.group, groupRows(snapshot?.groups).find(row => row.id === review.group.id))) { closeReview(); setError('群设置或成员已变化，请重新核对后提交。'); }
  }, [snapshot, review]);

  const openCreate = () => { setEditor('create'); setOriginal(null); setDraft({ name: '', accountId: '', roomId: '', conversationKind: 'group', conversationParentId: '' }); setError(''); setNotice(''); };
  const openMember = (member?: Data) => { if (!group) return; setOriginal(copy(group)); setDraft(member ? { ...member, editingSenderId: member.senderId } : { senderId: '', displayName: '', role: 'member', enabled: true }); setEditor('member'); setError(''); };
  const openPolicy = () => { if (!group) return; setOriginal(copy(group)); setDraft(groupPolicyDraft(group)); setEditor('policy'); setError(''); };
  const inspectTask = (reference: Data) => { setSelectedRequest(reference); setNotice(''); void invoke('groups.inspectTask', { requestId: reference.requestId }, value => { const actual = acceptGroupTask(reference, null, value); setTaskReading(current => acceptGroupTask(reference, current?.reference?.requestId === reference.requestId ? current : null, actual)); if (value.known === false) setNotice('这次查询未确认原任务状态。保留上次读取结果，请继续查询原引用。'); }); };
  const openTask = () => { if (selectedRequest) void invoke('groups.openTask', { requestId: selectedRequest.requestId }); };
  const requestReview = () => {
    try {
      if (stale) throw new Error('群设置已变化，请返回后重新编辑。');
      if (editor === 'create') void invoke('groups.reviewCreate', draft, receiveReview);
      if (editor === 'member' && original) void invoke('groups.reviewMember', { id: original.id, revision: original.revision, policyEpoch: original.policyEpoch, senderId: draft.senderId, displayName: draft.displayName, role: draft.role, enabled: draft.enabled }, receiveReview);
      if (editor === 'policy' && original) void invoke('groups.reviewPolicy', groupPolicyPayload(original, draft), receiveReview);
    } catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)); }
  };
  const confirm = () => {
    const frozen = review; if (!frozen || !acknowledged || busy) return;
    reviewHandle.current = ''; setReview(null); setAcknowledged(false);
    void invoke('groups.confirm', { reviewHandle: frozen.reviewHandle, confirmed: true }, value => {
      const result = confirmedGroupResult(value, frozen.command);
      if (['group.create', 'member.set', 'policy.set'].includes(frozen.command)) {
        if (typeof result.id !== 'string' || !Number.isSafeInteger(result.revision) || (frozen.command === 'group.create' ? result.enabled !== false : result.id !== frozen.input.id || result.revision <= frozen.input.revision)) throw new Error('群设置保存尚未取得正确版本，请刷新核对。');
        setGroupId(result.id); setEditor(null); setOriginal(null); setNotice(frozen.command === 'group.create' ? '群空间已创建，当前保持关闭。请核对成员与能力后再启用。' : '原群设置已保存。旧授权的任务需按原记录重新核对。');
      } else if (frozen.command.startsWith('task.')) {
        const reference = frozen.reference; const actual = acceptGroupTask(reference, null, result); setTaskReading(current => acceptGroupTask(reference, current?.reference?.requestId === reference.requestId ? current : null, actual)); setNotice(result.known === false ? '这次原操作的状态仍待核对，请查询原任务，不要重新提交。' : '已取得原任务回执。');
      } else if (frozen.command === 'delivery.send') {
        const actual = acceptGroupDelivery(frozen.reference, result, undefined, frozen.input.outputId); setDeliveries(old => ({ ...old, [actual.deliveryId]: actual })); setNotice(groupDeliveryLabel(actual.state));
      }
    });
  };
  const task = taskReading?.reference?.requestId === selectedRequest?.requestId && taskReading?.known === true ? taskReading.task : undefined;
  const presentation = selectedRequest ? groupPresentation(selectedRequest, task, taskReading?.presentation || snapshot?.taskPresentations?.[selectedRequest.requestId]) : null;
  const requestDeliveries = selectedRequest ? Array.from(new Map([...groupRows(snapshot?.deliveries), ...Object.values(deliveries)].filter(row => row.requestId === selectedRequest.requestId && row.taskId === selectedRequest.taskId).map(row => [row.deliveryId, row])).values()) : [];
  const change = (key: string, value: any) => setDraft(old => ({ ...old, [key]: value }));
  const selectedConnection = connections.find(row => row.id === draft.accountId);
  const field = (key: string, label: string, hint?: string, disabled = false) => <TextField label={label} value={String(draft[key] ?? '')} onChange={value => change(key, value)} hint={hint} isDisabled={busy || disabled} />;

  return <>
    <section className="pw-settings-section">
      <header className="pw-section-heading"><div><h2>{editor === 'create' ? '新建群空间' : group?.name || '群协作'}</h2><p>按原群空间管理成员、能力和请求。审批与执行继续使用原任务。</p></div></header>
      <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={refresh}>刷新原群记录</Button>{!editor && !groupId && <Button disabled={busy} onClick={openCreate}>新建群空间</Button>}{(editor || groupId) && <Button variant="ghost" disabled={busy} onClick={() => { if (editor) { setEditor(null); setOriginal(null); } else if (selectedRequest) { setSelectedRequest(null); setTaskReading(null); } else setGroupId(''); }}>返回{editor ? '群空间' : selectedRequest ? '请求列表' : '群列表'}</Button>}</div>
      {error && <p role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}{busy && <p className="pw-muted" role="status">正在读取原操作回执…</p>}
      {!snapshot && !busy && <p className="pw-secondary-empty">群记录尚未读取。请刷新后继续。</p>}
      {!editor && !groupId && groupRows(snapshot?.groups).map(row => <article className="pw-source-record" key={row.id}><header><h3>{row.name}</h3><span>{row.enabled ? '服务已启用' : '服务已关闭'}</span></header><p>{row.provider === 'qq' ? row.conversationKind === 'qq_guild_channel' ? 'QQ 频道' : 'QQ 群' : '可信 Webhook 群'} · {groupRows(row.members).filter(member => member.enabled).length} 位已启用成员 · {groupRows(snapshot?.requests).filter(request => request.groupId === row.id).length} 项原请求</p><Button variant="secondary" disabled={busy} onClick={() => { setGroupId(row.id); setTab('tasks'); setSelectedRequest(null); setTaskReading(null); setError(''); }}>进入群空间</Button></article>)}
      {!editor && !groupId && snapshot && groupRows(snapshot.groups).length === 0 && <p className="pw-secondary-empty">尚未绑定群空间。先选择已有机器人连接，再添加成员与能力授权。</p>}
      {groupId && !group && !editor && <p role="alert">这个群空间当前未读取到，请刷新核对。</p>}
      {editor === 'create' && <>
        {field('name', '群空间名称')}
        <SelectField label="机器人连接" value={draft.accountId} onChange={value => setDraft(old => ({ ...old, accountId: value, conversationKind: 'group', conversationParentId: '', roomId: '' }))} options={connections.map(row => ({ id: row.id, label: `${row.name || row.id} · ${row.provider}`, disabled: row.enabled !== true }))} isDisabled={busy} hint="使用已配置的 QQ 或可信 Webhook 连接。" />
        <SelectField label="群场景" value={draft.conversationKind} onChange={value => setDraft(old => ({ ...old, conversationKind: value, conversationParentId: '', roomId: '' }))} options={[{ id: 'group', label: '普通群' }, { id: 'qq_guild_channel', label: 'QQ 频道子频道', disabled: selectedConnection?.provider !== 'qq' || selectedConnection?.qqGuildEnabled !== true }]} isDisabled={busy || !draft.accountId} />
        {draft.conversationKind === 'qq_guild_channel' && field('conversationParentId', '原频道 guild_id')}
        {field('roomId', draft.conversationKind === 'qq_guild_channel' ? '原子频道 channel_id' : selectedConnection?.provider === 'qq' ? '原群 group_openid' : '原群 roomId', '填写原平台身份，群号或显示名称不能替代。')}
        <p className="pw-muted">新空间默认关闭。普通群与频道有各自的成员、能力和授权范围。</p>
        <Button disabled={busy || !draft.accountId || !draft.name?.trim() || !draft.roomId?.trim()} onClick={requestReview}>核对并创建</Button>
      </>}
      {editor === 'member' && original && <>
        <h3>{groupRows(original.members).some(member => member.senderId === draft.senderId) ? '编辑成员' : '添加成员'}</h3>
        {field('senderId', '原平台成员身份', '使用原群事件中的 senderId。', !!draft.editingSenderId)}
        {field('displayName', '成员显示名称')}
        <SelectField label="成员角色" value={draft.role} onChange={value => change('role', value)} options={roles} isDisabled={busy} />
        <CheckboxField isSelected={draft.enabled === true} onChange={value => change('enabled', value)} isDisabled={busy}>启用这位成员</CheckboxField>
        <p className="pw-muted">更改角色或启用状态会更新成员授权代次，旧任务沿原记录重新核对。群管理员不能扩大工具权限。</p>
        {stale && <p role="alert">群设置已变化，请返回后重新打开成员。</p>}
        <Button disabled={busy || stale || !draft.senderId?.trim() || !draft.displayName?.trim()} onClick={requestReview}>核对成员变更</Button>
      </>}
      {editor === 'policy' && original && <>
        <h3>能力与额度</h3>
        <CheckboxField isSelected={draft.enabled === true} onChange={value => change('enabled', value)} isDisabled={busy}>启用这个群空间的服务</CheckboxField>
        <SelectField label="任务进度可见范围" value={draft.visibility} onChange={value => change('visibility', value)} options={[{ id: 'requester', label: '仅发起人' }, { id: 'members', label: '允许任务选择群成员可见' }]} isDisabled={busy} />
        <p className="pw-muted">结果是否能发到群里按每项能力单独授权。当前渠道仅支持原群被动回复，未开放群受众的结果不会改投到私聊。</p>
        {groupCapabilities.map(([capability, label]) => {
          const grant = groupRows(draft.grants).find(row => row.capability === capability); const available = resources[capability] === true;
          return <article className="pw-source-record" key={capability}>
            <CheckboxField isSelected={!!grant} isDisabled={busy || !available && !grant} onChange={value => change('grants', value ? [...groupRows(draft.grants), { capability, resource: capability.startsWith('public.') ? 'public' : capability === 'group.files' ? original.spaceId : resources.mediaResource, allowGroupAudience: false }] : groupRows(draft.grants).filter(row => row.capability !== capability))}>{label}{available ? '' : ' · 当前服务不可用'}</CheckboxField>
            {grant && <CheckboxField isSelected={grant.allowGroupAudience === true} onChange={value => change('grants', groupRows(draft.grants).map(row => row.capability === capability ? { ...row, allowGroupAudience: value } : row))} isDisabled={busy}>允许把这项能力的结果发到群里</CheckboxField>}
            {grant && capability.startsWith('media.') && <><p>{resources.mediaProvider} · {capability === 'media.image' ? resources.imageModel : resources.ttsModel}</p><p className="pw-muted">{resources.mediaBaseUrl}</p>{grant.resource !== resources.mediaResource && <p role="alert">媒体配置已经变化，旧授权不能迁移到新服务。</p>}<Button variant="secondary" disabled={busy || !available} onClick={() => change('grants', groupRows(draft.grants).map(row => row.capability === capability ? { ...row, resource: resources.mediaResource } : row))}>明确选择当前媒体服务</Button></>}
          </article>;
        })}
        {field('hours', '本次所选能力授权时长（1–744 小时）')}
        <Accordion><AccordionItem id="quota" title="任务与媒体额度">{groupQuotaFields.map(([key, label, min, max]) => <TextField key={key} label={label} value={draft.quota?.[key] || ''} onChange={value => change('quota', { ...draft.quota, [key]: value })} hint={`${min}–${max}`} isDisabled={busy} />)}</AccordionItem></Accordion>
        <p className="pw-muted">模型额度是累计的本地保守预留，不是服务商账单 token。媒体能力需要至少 32000 的单次与每日预留，额度不会自动提高。设置变化会使旧群授权失效，已经完成的操作无法撤回。</p>
        {stale && <p role="alert">群设置已变化，请返回后重新编辑。</p>}<Button disabled={busy || stale} onClick={requestReview}>核对能力与额度</Button>
      </>}
    </section>
    {!editor && group && <Tabs selectedKey={tab} onSelectionChange={key => { if (!busy) { setTab(String(key)); setSelectedRequest(null); setTaskReading(null); } }}>
      <TabList aria-label="群工作区"><Tab id="tasks">群请求</Tab><Tab id="members">成员</Tab><Tab id="policy">能力与额度</Tab></TabList>
      <TabPanel id="tasks"><section className="pw-settings-section">
        {!selectedRequest && groupRows(snapshot?.requests).filter(row => row.groupId === group.id).map(reference => <article className="pw-source-record" key={reference.requestId}><header><h3>{reference.goal || '原群请求'}</h3><span>{capabilityName(reference.capability)}</span></header><p>{reference.senderId} · {reference.audience === 'group' ? '结果受众为原群' : '结果受众为发起人'}</p><p className="pw-muted">请求编号 {reference.requestId}</p><Button variant="secondary" disabled={busy} onClick={() => inspectTask(reference)}>读取原任务</Button></article>)}
        {!selectedRequest && groupRows(snapshot?.requests).filter(row => row.groupId === group.id).length === 0 && <p className="pw-secondary-empty">这个群暂时没有原请求。启用成员与能力后，从已认证的原群渠道发起。</p>}
        {selectedRequest && <>
          <h3>{selectedRequest.goal || '原群任务'}</h3><p>{groupTaskLabel(task?.status)}</p><p className="pw-muted">请求 {selectedRequest.requestId} · 原任务 {selectedRequest.taskId}</p>
          {taskReading?.known === false && <p role="status">原任务状态尚未知。这里只查询原引用，不重新提交任务。</p>}
          {presentation?.progress?.message && <p>{presentation.progress.message}</p>}{presentation?.approval?.message && <p>{presentation.approval.message}</p>}{presentation?.recovery?.message && <p>{presentation.recovery.message}</p>}
          {task?.summary && <p className="pw-source-copy">{task.summary}</p>}{task?.resultPrivate === true && <p>这份结果保持原私人范围。</p>}
          <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => inspectTask(selectedRequest)}>重新读取原状态</Button><Button disabled={busy} onClick={openTask}>打开原任务与审批</Button>{task && ['paused', 'interrupted'].includes(task.status) && <Button variant="secondary" disabled={busy} onClick={() => { void invoke('groups.reviewTask', { requestId: selectedRequest.requestId, intent: 'resume' }, receiveReview); }}>核对并继续原任务</Button>}{task && typeof task.runId === 'string' && task.coordinatorEpoch > 0 && !['completed', 'failed', 'cancelled'].includes(task.status) && <Button variant="secondary" disabled={busy} onClick={() => { void invoke('groups.reviewTask', { requestId: selectedRequest.requestId, intent: 'cancel' }, receiveReview); }}>核对并停止原运行</Button>}</div>
          {task?.resultPrivate !== true && groupRows(task?.outputs).map(output => {
            const previous = requestDeliveries.find(row => row.outputId === output.outputId && row.audience === output.audience);
            return <article className="pw-source-record" key={output.outputId}><header><h3>{output.mimeType?.startsWith('text/') ? '文本结果' : '原任务产物'}</h3><span>{output.audience === 'group' ? '原群受众' : '发起人受众'}</span></header>{output.text && <p className="pw-source-copy">{output.text}</p>}<p className="pw-muted">{output.mimeType}{typeof output.bytes === 'number' ? ` · ${output.bytes} 字节` : ''}</p>{output.artifactId && <Button variant="secondary" disabled={busy} onClick={openTask}>在原任务查看文件</Button>}{previous ? <p>{groupDeliveryLabel(previous.state)}</p> : <Button variant="secondary" disabled={busy || task?.status !== 'completed'} onClick={() => { void invoke('groups.reviewDelivery', { requestId: selectedRequest.requestId, outputId: output.outputId }, receiveReview); }}>核对原受众并发回</Button>}</article>;
          })}
          {requestDeliveries.length > 0 && <h3>原投递记录</h3>}{requestDeliveries.map(delivery => <article className="pw-source-record" key={delivery.deliveryId}><header><h3>{groupDeliveryLabel(delivery.state)}</h3></header><p>{delivery.message || delivery.error || `产物 ${delivery.outputId}`}</p>{delivery.platformMessageId && <p className="pw-muted">原平台消息 {delivery.platformMessageId}</p>}<Button variant="secondary" disabled={busy} onClick={() => { void invoke('groups.inspectDelivery', { deliveryId: delivery.deliveryId }, value => { const actual = acceptGroupDelivery(selectedRequest, value, delivery.deliveryId); setDeliveries(old => ({ ...old, [actual.deliveryId]: actual })); }); }}>查询原投递，不重新发送</Button></article>)}
        </>}
      </section></TabPanel>
      <TabPanel id="members"><section className="pw-settings-section"><div className="pw-form-actions"><Button disabled={busy} onClick={() => openMember()}>添加群成员</Button></div>{groupRows(group.members).map(member => <article className="pw-source-record" key={member.senderId}><header><h3>{member.displayName}</h3><span>{member.enabled ? '已启用' : '已停用'}</span></header><p>{roles.find(role => role.id === member.role)?.label} · {member.senderId}</p><Button variant="secondary" disabled={busy} onClick={() => openMember(member)}>核对成员设置</Button></article>)}{groupRows(group.members).length === 0 && <p className="pw-secondary-empty">尚未授权群成员。</p>}</section></TabPanel>
      <TabPanel id="policy"><section className="pw-settings-section"><h3>{group.enabled ? '群服务已启用' : '群服务已关闭'}</h3><p>进度可见：{group.visibility === 'members' ? '允许任务选择群成员可见' : '仅发起人'}</p>{groupRows(group.grants).map(grant => <article className="pw-source-record" key={grant.capability}><h3>{capabilityName(grant.capability)}</h3><p>{grant.allowGroupAudience ? '允许群结果受众' : '仅原发起人受众'} · 到期 {time(grant.expiresAt)}</p></article>)}<dl className="pw-facts">{groupQuotaFields.map(([key, label]) => <div key={key}><dt>{label}</dt><dd>{group.quota?.[key] ?? '尚未提供'}</dd></div>)}</dl><Button disabled={busy} onClick={openPolicy}>编辑能力与额度</Button></section></TabPanel>
    </Tabs>}
    <SettingsDialog isOpen={!!review} onClose={() => { if (!busy) closeReview(); }} title={review?.command === 'group.create' ? '创建这个群空间' : review?.command === 'delivery.send' ? '发回这份原产物' : review?.command?.startsWith('task.') ? '核对原任务操作' : '保存这份群设置'}>
      {review && <>
        <p>{review.group?.name || review.input.name}</p>
        {review.command === 'group.create' && <><p>连接 {review.connection?.name || review.input.accountId} · {review.input.conversationKind === 'qq_guild_channel' ? `频道 ${review.input.conversationParentId} / ` : ''}{review.input.roomId}</p><p>空间创建后保持关闭，继续添加成员与授权。</p></>}
        {review.command === 'member.set' && <><p>{review.input.displayName} · {review.input.senderId} · {roles.find(role => role.id === review.input.role)?.label} · {review.input.enabled ? '启用' : '停用'}</p><p>保存会更新原成员授权代次。</p></>}
        {review.command === 'policy.set' && <><p>{review.input.enabled ? '启用' : '关闭'}群服务 · {review.input.visibility === 'members' ? '允许群成员可见进度' : '仅发起人可见进度'}</p>{groupRows(review.input.grants).map(grant => <p key={grant.capability}>{capabilityName(grant.capability)} · {grant.allowGroupAudience ? '允许群结果受众' : '原发起人受众'} · 到期 {time(grant.expiresAt)}</p>)}<dl className="pw-facts">{groupQuotaFields.map(([key, label]) => <div key={key}><dt>{label}</dt><dd>{review.input.quota[key]}</dd></div>)}</dl><p>本次保存会更新群策略版本，旧任务授权不自动迁移。</p></>}
        {review.command?.startsWith('task.') && <><p>{review.reference?.goal} · {groupTaskLabel(review.task?.status)}</p><p>{review.command === 'task.cancel' ? '停止这项原任务当前读取到的执行代次。已完成的外部操作无法撤回。' : '在原任务上继续或恢复，仍需通过原能力与审批核验。'}</p><p className="pw-muted">原运行 {review.input.runId || '尚未派发'} · 代次 {review.input.coordinatorEpoch} · 原版本 {review.task?.revision}</p></>}
        {review.command === 'delivery.send' && <>{groupRows(review.task?.outputs).filter(output => output.outputId === review.input.outputId).map(output => <div key={output.outputId}><p>{output.audience === 'group' ? `发往原群：${review.group?.name}` : '受众保持原请求发起人'}</p><p className="pw-source-copy">{output.text || `${output.mimeType} · 原产物 ${output.outputId}`}</p></div>)}<p>使用这项原请求的原渠道与受众。平台接受与实际送达分别记录；未知结果只查询，不重复投递。</p></>}
        <CheckboxField isSelected={acknowledged} onChange={setAcknowledged} isDisabled={busy}>我已核对对象、范围与影响，确认这一次操作</CheckboxField>
        <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={closeReview}>返回核对</Button><Button disabled={busy || !acknowledged} onClick={confirm}>{review.command === 'delivery.send' ? '确认发回原产物' : review.command === 'task.cancel' ? '确认停止原运行' : review.command === 'task.resume' ? '确认继续原任务' : '确认保存'}</Button></div>
      </>}
    </SettingsDialog>
  </>;
}
