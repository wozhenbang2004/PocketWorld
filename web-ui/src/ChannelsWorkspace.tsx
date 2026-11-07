import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { ChevronRight, MessageSquare, RefreshCw, Settings } from 'lucide-react';
import { Accordion, AccordionItem, Button, CheckboxField, SelectField, SettingsDialog, TextField } from './forms-library';
import { Tabs, TabList, Tab, TabPanel } from './shell-library';
import './channels-workspace.css';
import './collaboration-depth.css';
import { channelCanStop, channelConnectionStatus, channelIdentity, channelObjects as rows, channelOriginalCurrent, channelOwnerCandidates, channelOwnerIdentity, channelProfilePayload, channelReceivingStatus, channelText as text, type ChannelData as Data } from './channel-contracts';

type Run = (action: string, args?: Data) => Promise<any>;
type Back = (handler: (() => boolean) | null) => void;
type Props = { state: Data; data: Data; supported: (action: string) => boolean; run: Run; registerTransientBack: Back };
type Modal = { kind: 'profile' | 'login' | 'owner' | 'history' | 'media' | 'receiving' | 'remove'; original?: Data; operation?: string; event?: Data; index?: number; review?: Data };
const object = (value: any): Data => value && typeof value === 'object' && !Array.isArray(value) ? value : {};
const clone = (value: Data) => JSON.parse(JSON.stringify(value)) as Data;
const deliveryState = (state: any) => ({ queued: '等待发送', sending: '正在发送，结果待核对', sent: '平台已接受，不代表已读', delivered: '平台已接受，不代表已读', unknown: '投递结果未知', cancelled: '已取消待发回复', failed: '发送未完成' } as Data)[text(state)] || text(state);
function Facts({ values }: { values: Array<[string, any]> }) { return <dl className="pw-facts">{values.filter(([, value]) => value !== '' && value !== undefined && value !== null).map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{text(value)}</dd></div>)}</dl>; }
function useRequest(run: Run) {
  const waiting = useRef(false), alive = useRef(true);
  const [busy, setBusy] = useState(false), [error, setError] = useState('');
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  const request = async (action: string, args: Data = {}) => {
    if (waiting.current) throw new Error('请等待这次原操作的回执。');
    waiting.current = true; setBusy(true); setError('');
    try { const value = await run(action, args); if (!alive.current) throw new Error('原页面已关闭，请在原连接核对。'); return object(value); }
    catch (failure) { if (alive.current) setError(failure instanceof Error ? failure.message : String(failure)); throw failure; }
    finally { waiting.current = false; if (alive.current) setBusy(false); }
  };
  return { request, busy, error, setError, alive };
}
function useBack(register: Back, busy: boolean, close: () => void) {
  useEffect(() => { register(() => { if (!busy) close(); return true; }); return () => register(null); }, [register, busy, close]);
}

const providerName = (provider: string) => provider === 'qq' ? 'QQ 官方机器人' : provider === 'weixin' ? '微信 iLink' : '可信中继';
const admissionState = (value: any) => ({ admitting: '正在交给原宿主', admitted: '原宿主已接收', awaiting_owner: '等待本机主人确认', admission_unknown: '宿主接收结果未知', cancelled: '已取消准入', cancellation_unknown: '取消结果未知' } as Data)[text(value)] || text(value) || '原回执待核对';
const cancellationState = (value: any) => ({ requesting: '已请求取消，等待回执', requested: '原宿主已收到取消请求', cancellation_unknown: '取消结果未知' } as Data)[text(value)] || text(value);
const taskNeedsReview = (row: Data) => ['admission_unknown', 'cancellation_unknown'].includes(row.admission) || row.cancellation === 'cancellation_unknown';
const messageTime = (value: any) => typeof value === 'number' && Number.isFinite(value) && value > 0 && Number.isFinite(new Date(value).getTime()) ? new Date(value).toLocaleString([], { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '';

/** BoardUI Tabs, Accordion and SettingsDialog retain the original native workflows.
 * Compact connection rows adapt OpenMuse LinkRow's title/detail/chevron hierarchy:
 * MIT 61796eb2, apps/mobile/src/ui.tsx:368. No upstream artwork is copied. */
export default function ChannelsWorkspace({ state, data, supported, run, registerTransientBack }: Props) {
  const [modal, setModal] = useState<Modal | null>(null), [selected, setSelected] = useState<string[]>([]), [receipt, setReceipt] = useState<Data | null>(null);
  const [panel, setPanel] = useState('inbox'), [connectionsOpen, setConnectionsOpen] = useState(false), [accountId, setAccountId] = useState('');
  const [connectionExpanded, setConnectionExpanded] = useState<Set<string | number>>(new Set()), [accountExpanded, setAccountExpanded] = useState<Set<string | number>>(new Set());
  const origin = useRef<HTMLElement | null>(null), root = useRef<HTMLElement | null>(null), positions = useRef<Record<string, number>>({});
  const { request, busy, error } = useRequest(run), profiles = rows(data.profiles), bindings = rows(data.bindings), background = object(data.background);
  const receiving = channelCanStop(background), candidates = profiles.filter(profile => ['qq', 'weixin'].includes(profile.provider) && profile.enabled === true && profile.hasSecret === true);
  const tasks = rows(data.taskRequests).slice().reverse(), outbox = rows(data.outbox).slice().reverse();
  const unknownTasks = tasks.filter(taskNeedsReview), unknownDeliveries = outbox.filter(row => row.status === 'unknown');
  const account = profiles.find(profile => profile.id === accountId);
  const close = () => { setModal(null); if (!accountId && !connectionsOpen) origin.current?.focus({ preventScroll: true }); };
  const selectPanel = (next: string) => {
    positions.current[panel] = root.current?.closest<HTMLElement>('.pw-secondary-scroll')?.scrollTop || 0;
    setPanel(next);
  };
  useLayoutEffect(() => {
    const scroller = root.current?.closest<HTMLElement>('.pw-secondary-scroll');
    if (scroller) scroller.scrollTop = positions.current[panel] || 0;
  }, [panel]);
  const rememberOrigin = () => { origin.current = document.activeElement instanceof HTMLElement ? document.activeElement : null; };
  const returnToWorkspace = () => { setConnectionsOpen(false); setAccountId(''); origin.current?.focus({ preventScroll: true }); };
  const openConnections = () => { rememberOrigin(); setConnectionsOpen(true); };
  const openAccount = (id: string) => { if (id !== accountId) setAccountExpanded(new Set()); setAccountId(id); };
  const act = (action: string, args: Data = {}) => { void request(action, args).then(value => { if (action !== 'channels.inspect') setReceipt(value); }).catch(() => {}); };
  useEffect(() => {
    if (supported('channels.open')) void run('channels.open').catch(() => {});
    return () => { if (supported('channels.close')) void run('channels.close').catch(() => {}); };
  }, []);
  useEffect(() => { setSelected(previous => previous.filter(id => candidates.some(profile => profile.id === id))); }, [candidates.map(profile => profile.id).join('|')]);
  const owner = (profile: Data, operation: string, enabled?: boolean) => {
    const bound = bindings.find(binding => binding.profileId === profile.id);
    setModal({ kind: 'owner', operation, original: clone({ ...profile, ownerIdentity: channelOwnerIdentity(profile, bound), ownerBinding: bound, requestedEnabled: enabled }) });
  };
  return <section ref={root} className="pw-channels-workspace pw-depth-collaboration pw-settings-section">
    <header className="pw-channels-heading"><div><h2>渠道消息</h2></div><div className="pw-form-actions">
      <Button variant="ghost" leadingIcon={Settings} onClick={openConnections}>连接与接收</Button>
      <Button variant="ghost" iconOnly leadingIcon={RefreshCw} aria-label="刷新真实状态" disabled={busy || !supported('channels.inspect')} onClick={() => act('channels.inspect')} />
    </div></header>

    <div className="pw-channels-receiving" aria-label="当前接收状态">
      <div><p className="pw-channels-status">{channelReceivingStatus(background)}</p>
        {background.foregroundActive && <p className="pw-channels-caption">进程内接收 {Number(background.runningReceivers || 0)} 个 · 已认证或同步 {Number(background.authenticatedReceivers || 0)} 个。服务建立不代表每个账号在线。</p>}
        {background.message && <p className="pw-channels-caption">{background.message}</p>}
      </div>
      {receiving && <Button variant="secondary" disabled={busy || !supported('channels.stopReceiving')} onClick={() => act('channels.stopReceiving', { sessionId: background.sessionId })}>请求停止本次接收</Button>}
    </div>
    {(unknownTasks.length > 0 || unknownDeliveries.length > 0) && <div className="pw-channels-attention" role="status">
      <p>有原操作结果待核对。只查原记录，不重新提交或重发。</p><div className="pw-form-actions">
        {unknownTasks.length > 0 && <Button variant="ghost" onClick={() => selectPanel('tasks')}>{unknownTasks.length} 项任务回执待核对</Button>}
        {unknownDeliveries.length > 0 && <Button variant="ghost" onClick={() => selectPanel('outbox')}>{unknownDeliveries.length} 项投递结果未知</Button>}
      </div>
    </div>}
    {error && <p className="pw-channels-feedback" role="alert">{error}</p>}
    {receipt && <div className="pw-channels-feedback" role="status"><p>这次实际回执</p><Facts values={[[ '状态', receipt.status ], ['已认证', receipt.authenticated === true ? '是' : undefined ], ['实际接收条数', receipt.received ], ['尚未识别的项目', receipt.skippedUnsupported ], ['服务停止信号', receipt.serviceStopSignalAccepted === true ? '已交给系统' : receipt.serviceStopSignalAccepted === false ? '系统未确认' : undefined ]]} />{receipt.message && <p>{receipt.message}</p>}{receipt.connected === false && <p>配置存在，尚未确认平台联通。</p>}{receipt.foregroundEstablished === false && <p>已提交原服务请求；等待上方真实服务和账号状态。</p>}{receipt.removed === true && <p>原连接已移除。</p>}</div>}

    <Tabs selectedKey={panel} onSelectionChange={key => selectPanel(String(key))} className="pw-channels-tabs">
      <TabList aria-label="渠道记录"><Tab id="inbox">最近消息</Tab><Tab id="tasks">原任务</Tab><Tab id="outbox">回复投递</Tab></TabList>
      <TabPanel id="inbox" shouldForceMount hidden={panel !== 'inbox'}>
        <ChannelInbox data={data} supported={supported} run={run} connect={openConnections} account={id => { rememberOrigin(); openAccount(id); }} media={(event, index) => { rememberOrigin(); setModal({ kind: 'media', event: clone(event), index }); }} />
      </TabPanel>
      <TabPanel id="tasks" shouldForceMount hidden={panel !== 'tasks'}>

        {!tasks.length && <div className="pw-channels-empty"><p>还没有主人私聊任务</p><span>核对主人身份并单独允许 /task 后，原任务会留在这里。</span><Button variant="ghost" onClick={openConnections}>查看连接与主人权限</Button></div>}
        <Accordion className="pw-channels-records" allowsMultipleExpanded>{tasks.map(row => <AccordionItem id={row.requestId} key={row.requestId} title={rows(state.tasks).find(task => task.id === row.taskId)?.goal || '原任务等待本机核对'} description={taskNeedsReview(row) ? '上次请求结果未知 · 只核对原宿主任务' : admissionState(row.admission)}>
          <ChannelTask row={row} task={rows(state.tasks).find(task => task.id === row.taskId)} supported={supported} run={run} />
        </AccordionItem>)}</Accordion>
      </TabPanel>
      <TabPanel id="outbox" shouldForceMount hidden={panel !== 'outbox'}>
        <p className="pw-channels-caption">平台接受不代表已读</p>
        {!outbox.length && <div className="pw-channels-empty"><p>还没有回复投递</p><span>回复产生后，可在这里核对原投递状态。</span></div>}
        <Accordion className="pw-channels-records" allowsMultipleExpanded>{outbox.map(row => <AccordionItem id={row.id} key={row.id} title={<span className="pw-channels-preview">{row.text || '原回复正文未保留'}</span>} description={deliveryState(row.status)}>
          <div className="pw-channels-record-detail"><p className="pw-channels-body">{row.text}</p>{row.message && <p className="pw-field-hint">{row.message}</p>}
            {row.nativeQqStream && <p className="pw-field-hint">QQ 原消息已确认 {Number(row.nativeQqStream.lastAckIndex ?? -1) + 1} 帧 · {row.nativeQqStream.complete ? '最终帧已被平台接受' : '完整结束尚未确认，部分草稿可能已显示'}</p>}
            {row.status === 'queued' && <Button variant="secondary" disabled={busy || !supported('channels.cancelOutbox')} onClick={() => act('channels.cancelOutbox', { id: row.id })}>取消这项待发回复</Button>}
            {row.status === 'unknown' && <p>请到原平台核对结果；保留同一条投递记录，不自动重发。</p>}
          </div>
        </AccordionItem>)}</Accordion>
      </TabPanel>
    </Tabs>

    {connectionsOpen && !accountId && !modal && <ChannelConnections data={data} candidates={candidates} selected={selected} select={setSelected} supported={supported} busy={busy} error={error} expanded={connectionExpanded} expand={setConnectionExpanded} openAccount={openAccount} openModal={setModal} request={request} act={act} close={returnToWorkspace} register={registerTransientBack} />}
    {accountId && !modal && <ChannelAccount profile={account} data={data} supported={supported} busy={busy} error={error} receipt={receipt} expanded={accountExpanded} expand={setAccountExpanded} openModal={setModal} owner={owner} request={request} act={act} close={() => { if (connectionsOpen) setAccountId(''); else returnToWorkspace(); }} register={registerTransientBack} />}
    {modal?.kind === 'profile' && <ChannelProfile original={modal.original!} data={data} run={run} close={close} register={registerTransientBack} />}
    {modal?.kind === 'login' && <ChannelLogin run={run} close={close} register={registerTransientBack} />}
    {modal?.kind === 'owner' && <ChannelOwner original={modal.original!} operation={modal.operation!} data={data} run={run} close={close} register={registerTransientBack} />}
    {modal?.kind === 'history' && <ChannelHistory original={modal.original!} data={data} run={run} close={close} register={registerTransientBack} />}
    {modal?.kind === 'media' && <ChannelMedia event={modal.event!} index={modal.index!} run={run} close={close} register={registerTransientBack} />}
    {modal && ['receiving', 'remove'].includes(modal.kind) && <ChannelConfirmation modal={modal} run={run} close={close} register={registerTransientBack} />}
  </section>;
}

type DisclosureControl = { expanded: Set<string | number>; expand: (keys: Set<string | number>) => void };
type ManagerProps = DisclosureControl & { data: Data; supported: (action: string) => boolean; busy: boolean; error: string; openModal: (modal: Modal) => void; request: (action: string, args?: Data) => Promise<Data>; act: (action: string, args?: Data) => void; close: () => void; register: Back };
function ChannelConnections({ data, candidates, selected, select, supported, busy, error, expanded, expand, openAccount, openModal, request, act, close, register }: ManagerProps & { candidates: Data[]; selected: string[]; select: (value: string[] | ((previous: string[]) => string[])) => void; openAccount: (id: string) => void }) {
  useBack(register, busy, close);
  const profiles = rows(data.profiles), background = object(data.background), platform = object(background.platform), receiving = channelCanStop(background);
  return <SettingsDialog isOpen title="连接与接收" onClose={() => { if (!busy) close(); }}><div className="pw-collaboration-dialog" data-collaboration-kind="ChannelsWorkspace"><div className="pw-channels-settings">
    <p className="pw-channels-caption">连接账号、绑定主人、自动回答与持续接收分别确认。</p>
    {receiving && <div className="pw-channels-receiving"><div><p className="pw-channels-status">{channelReceivingStatus(background)}</p><p className="pw-channels-caption">关闭此页不会停止接收，停止接收不会停止已有任务。</p></div><Button variant="secondary" disabled={busy || !supported('channels.stopReceiving')} onClick={() => act('channels.stopReceiving', { sessionId: background.sessionId })}>请求停止本次接收</Button></div>}
    <div className="pw-channels-account-list">{profiles.map(profile => {
      const connection = rows(object(data[profile.provider]).connections).find(row => row.profileId === profile.id);
      return <Button className="pw-channels-link-row" key={profile.id} variant="ghost" disabled={busy} onClick={() => openAccount(profile.id)}><span className="pw-channels-account-icon" aria-hidden><MessageSquare size={20} /></span><span className="pw-channels-link-copy"><strong>{profile.name}</strong><small>{providerName(profile.provider)} · {['qq', 'weixin'].includes(profile.provider) ? channelConnectionStatus(profile.provider, connection) : profile.enabled ? '配置已启用' : '配置已停用'}</small></span><ChevronRight size={16} aria-hidden /></Button>;
    })}</div>
    {!profiles.length && <p>还没有渠道账号。连接后，从自己的账号发送私聊，再在本机核对主人身份。</p>}
    <div className="pw-form-actions"><Button disabled={busy || !supported('channels.loginStart')} onClick={() => openModal({ kind: 'login' })}>连接微信私聊</Button><Button variant="secondary" disabled={busy || !supported('channels.saveProfile')} onClick={() => openModal({ kind: 'profile', original: { id: crypto.randomUUID(), adding: true, provider: 'qq', name: '我的 QQ 机器人', enabled: true } })}>添加 QQ 官方机器人</Button></div>
    <Accordion className="pw-channels-settings-disclosure" expandedKeys={expanded} onExpandedChange={keys => expand(new Set(keys))}><AccordionItem id="receiving-scope" title="接收范围与通知" description={channelReceivingStatus(background)}>
      {background.message && <p>{background.message}</p>}
      {receiving ? <p>原接收会话仍在管理所选账号。先停止这次接收，再更改接收范围。</p> : <>
        {!candidates.length && <p>先配置并启用 QQ 或微信账号，再选择接收范围。</p>}
        {candidates.map(profile => <CheckboxField key={profile.id} isSelected={selected.includes(profile.id)} isDisabled={busy} onChange={checked => select(previous => checked ? [...previous, profile.id] : previous.filter(id => id !== profile.id))}>{profile.name} · {providerName(profile.provider)}</CheckboxField>)}
        <div className="pw-form-actions"><Button disabled={busy || !selected.length || selected.length > 6 || platform.serviceDeclared !== true || !supported('channels.reviewReceiving')} onClick={() => { void request('channels.reviewReceiving', { profileIds: selected }).then(review => openModal({ kind: 'receiving', review })).catch(() => {}); }}>核对所选账号并开启</Button></div>
        {selected.length > 6 && <p role="alert">本次最多选择 6 个账号。</p>}
      </>}
      {platform.serviceDeclared !== true && <p className="pw-field-hint">当前版本未确认已安装接收服务。</p>}
      {platform.notificationsVisible !== true && <Button variant="ghost" disabled={busy || !supported('channels.notifications')} onClick={() => act('channels.notifications')}>允许或检查接收通知</Button>}
      <p className="pw-field-hint">明确开启后会显示常驻通知并增加耗电；系统省电仍可能延迟消息。强停、进程结束或重启后需回到本机重新开启。关闭此页不会停止接收；停止接收不会停止已有任务。</p>
    </AccordionItem></Accordion>
    {error && <p role="alert">{error}</p>}
    <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={close}>返回消息工作区</Button>{supported('channels.advanced') && <Button variant="ghost" disabled={busy} onClick={() => act('channels.advanced')}>可信中继与现有诊断入口</Button>}</div>
  </div></div></SettingsDialog>;
}

function ChannelAccount({ profile, data, supported, busy, error, receipt, expanded, expand, openModal, owner, request, act, close, register }: ManagerProps & { profile?: Data; receipt: Data | null; owner: (profile: Data, operation: string, enabled?: boolean) => void }) {
  useBack(register, busy, close);
  const bound = rows(data.bindings).find(binding => binding.profileId === profile?.id), background = object(data.background);
  const connection = profile && rows(object(data[profile.provider]).connections).find(row => row.profileId === profile.id);
  return <SettingsDialog isOpen title={profile?.name || '原连接已不在本机'} onClose={() => { if (!busy) close(); }}><div className="pw-collaboration-dialog" data-collaboration-kind="ChannelsWorkspace"><div className="pw-channels-settings">
    {profile ? <>
      <p className="pw-channels-caption">{providerName(profile.provider)} · {profile.enabled ? '配置已启用，平台状态单独核对' : '配置已停用'}</p>
      {['qq', 'weixin'].includes(profile.provider) && <p className="pw-channels-status">{channelConnectionStatus(profile.provider, connection)}</p>}{connection?.message && <p>{connection.message}</p>}
      {channelCanStop(background) && rows(background.profiles).some(row => row.id === profile.id) && <p className="pw-field-hint">此账号由原前台接收会话管理；可回工作区停止本次接收。</p>}
      <Facts values={[[ '主人账号', bound?.senderId || '尚未绑定' ], ['私聊创建任务', bound?.ownerTasksEnabled ? '已允许' : '未允许' ], ['自动文字问答', profile.autoReply ? '已配置，仍需实际接收' : '未开启' ]]} />
      <div className="pw-form-actions"><Button variant="secondary" disabled={busy || !supported('channels.reviewOwner')} onClick={() => owner(profile, 'bind')}>{bound ? '重新核对主人' : '核对主人私聊'}</Button><Button variant="ghost" disabled={busy || !supported('channels.history')} onClick={() => openModal({ kind: 'history', original: clone(profile) })}>已读历史范围</Button></div>
      <Accordion className="pw-channels-settings-disclosure" allowsMultipleExpanded expandedKeys={expanded} onExpandedChange={keys => expand(new Set(keys))}>
        <AccordionItem id="account-permissions" title="主人权限与原件" description="任务权限、图片与音视频保存范围">
          {bound ? <><Button variant="secondary" disabled={busy || !supported('channels.reviewOwner') || !bound.ownerTasksEnabled && (!profile.enabled || !object(data.capabilities).ownerTaskHostConfigured)} onClick={() => owner(profile, 'tasks', !bound.ownerTasksEnabled)}>{bound.ownerTasksEnabled ? '核对并关闭任务权限' : '核对并允许 /task'}</Button><p className="pw-field-hint">仅原本人私聊的明确 /task 目标；写入与外发仍须按原参数在本机审批，私聊消息不能批准手机工具。</p></> : <p>先核对原主人私聊，再单独允许创建任务。</p>}
          <Facts values={[[ '本人私聊图片', profile.ownerImagesEnabled ? '已允许下载并提供给所选图像模型' : '未允许' ], ['本人私聊文件、语音与视频', profile.ownerMediaEnabled ? '已允许逐项核对保存' : '未允许' ]]} />
          <p className="pw-field-hint">开关在连接设置中。保存原件不代表已转写、播放或理解，也不会自动上传电脑或回发。</p>
        </AccordionItem>
        <AccordionItem id="account-connection" title="连接设置与核对" description="凭据、启用范围与平台联通状态">
          <div className="pw-form-actions">{['qq', 'weixin'].includes(profile.provider) && <Button variant="secondary" disabled={busy || !supported('channels.saveProfile')} onClick={() => openModal({ kind: 'profile', original: clone(profile) })}>连接设置</Button>}
            {profile.provider === 'weixin' && <Button variant="ghost" disabled={busy || !profile.enabled || !supported('channels.syncOnce')} onClick={() => act('channels.syncOnce', channelIdentity(profile))}>同步一次</Button>}
            {profile.provider === 'qq' && <Button variant="ghost" disabled={busy || !supported('channels.probeProfile')} onClick={() => act('channels.probeProfile', channelIdentity(profile))}>核对平台凭据</Button>}
          </div>
        </AccordionItem>
        <AccordionItem id="account-removal" title="解绑与移除" description="先核对原账号及影响范围">
          <div className="pw-form-actions">{bound && <Button variant="ghost" disabled={busy || !supported('channels.reviewOwner')} onClick={() => owner(profile, 'unbind')}>解除绑定</Button>}
            <Button variant="ghost" disabled={busy || !supported('channels.reviewRemoval')} onClick={() => { void request('channels.reviewRemoval', channelIdentity(profile)).then(review => openModal({ kind: 'remove', original: clone(profile), review })).catch(() => {}); }}>移除连接</Button>
          </div>
        </AccordionItem>
      </Accordion>
      {receipt?.message && <p role="status">{receipt.message}</p>}
    </> : <p>这个账号已移除或不在当前保留范围。不会改选其他账号。</p>}
    {error && <p role="alert">{error}</p>}<div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={close}>返回原列表</Button></div>
  </div></div></SettingsDialog>;
}

function ChannelProfile({ original, data, run, close, register }: { original: Data; data: Data; run: Run; close: () => void; register: Back }) {
  const [form, setForm] = useState<Data>({ name: text(original.name), appId: text(original.appId), secret: '', enabled: original.enabled === true, autoReply: original.autoReply === true, qqGuildEnabled: original.qqGuildEnabled === true, qqStreamRepliesEnabled: original.qqStreamRepliesEnabled === true, ownerImagesEnabled: original.ownerImagesEnabled === true, ownerMediaEnabled: original.ownerMediaEnabled === true });
  const [attempted, setAttempted] = useState(false), { request, busy, error } = useRequest(run);
  const current = rows(data.profiles).find(row => row.id === original.id), stale = original.adding ? !!current : !current || current.transportEpoch !== original.transportEpoch;
  const change = (key: string, value: any) => setForm(previous => ({ ...previous, [key]: value }));
  const clear = () => change('secret', ''), leave = () => { clear(); close(); };
  useBack(register, busy, leave);
  useEffect(() => { const hide = () => { if (document.visibilityState !== 'visible') clear(); }; document.addEventListener('visibilitychange', hide); window.addEventListener('pagehide', clear); return () => { document.removeEventListener('visibilitychange', hide); window.removeEventListener('pagehide', clear); }; }, []);
  const save = () => { const payload = channelProfilePayload(original, form); setAttempted(true); clear(); void request('channels.saveProfile', payload).then(close).catch(() => {}); };
  return <SettingsDialog isOpen title={original.adding ? '添加 QQ 官方机器人' : '连接设置'} onClose={() => { if (!busy) leave(); }}><div className="pw-collaboration-dialog" data-collaboration-kind="ChannelsWorkspace">
    <section className="pw-channel-profile-identity"><h3>连接身份</h3><div className="pw-form-grid"><TextField label="连接名称" value={form.name} onChange={value => change('name', value)} />
      {original.provider === 'qq' ? <><TextField label="QQ 平台 AppID" value={form.appId} isReadOnly={!original.adding} onChange={value => change('appId', value)} /><TextField label={original.adding ? 'QQ 平台 AppSecret' : '新 AppSecret（留空保留）'} type="password" autoComplete="new-password" value={form.secret} onChange={value => change('secret', value)} />
        <p className="pw-field-hint">更换 AppID 请另建连接。保存配置不代表机器人资格或网关已验证。</p>

      </> : <p>微信凭据来自原官方扫码流程；重新登录请使用“连接微信私聊”。</p>}
</div></section>
    <section className="pw-channel-profile-receiving"><h3>接收与回复</h3>      <CheckboxField isSelected={form.enabled} onChange={value => change('enabled', value)}>启用此连接</CheckboxField><CheckboxField isSelected={form.autoReply} onChange={value => change('autoReply', value)}>对新消息自动文字问答</CheckboxField>
{original.provider === 'qq' && <Accordion><AccordionItem title="QQ 频道与生成中的回答" description="分别核对平台范围与实际草稿外发">        <CheckboxField isSelected={form.qqGuildEnabled} onChange={value => change('qqGuildEnabled', value)}>同时接收 QQ 频道 @ 消息与频道私信</CheckboxField><p className="pw-field-hint">需机器人已获官方权限；频道私信与 C2C 的用户身份分别核对。</p>
        <CheckboxField isSelected={form.qqStreamRepliesEnabled} onChange={value => change('qqStreamRepliesEnabled', value)}>向 QQ C2C 显示生成中的回答</CheckboxField><p className="pw-field-hint">仅自动问答已开启、原消息在五分钟内时使用。实际模型草稿会传到同一条 QQ 消息；中断或未知保留原帧，不改发第二条。群和频道不使用此流式接口。</p></AccordionItem></Accordion>}</section>
    <Accordion className="pw-channel-profile-originals"><AccordionItem title="本人私聊原件" description="图片、文件、语音和视频的独立同意">      <CheckboxField isSelected={form.ownerImagesEnabled} onChange={value => change('ownerImagesEnabled', value)}>下载并向所选图像模型提供本人私聊图片</CheckboxField>
      <p className="pw-field-hint">仅原消息接收时已绑定的本人私聊。每条最多 4 张、单张 4 MiB / 12 MP、合计 8 MiB；从官方 CDN 下载，保存实际文件后提供给明确支持图片的模型。自动图片回答还需自动问答和实际接收。此同意不包含语音、视频或群图片。</p>
      <CheckboxField isSelected={form.ownerMediaEnabled} onChange={value => change('ownerMediaEnabled', value)}>允许逐项保存本人私聊文件、语音和视频</CheckboxField>
      <p className="pw-field-hint">每项仍需在本机另行确认，最多 8 MiB。保存原件不会自动转写、分析、上传电脑或回发；关闭不删除已保存原件。</p></AccordionItem></Accordion>

<p className="pw-field-hint">保存会更新原连接代次并停止原接收和生成，请用新消息重新核对。群和非主人私聊仅使用明确配置的 API 模型。</p>
    {stale && <p role="alert">原连接已改变，请返回核对当前配置，未用旧表单覆盖。</p>}{error && <p role="alert">{error} 请先返回核对原连接，不重复这次保存。</p>}
    <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={leave}>返回</Button><Button disabled={busy || attempted || stale || !text(form.name).trim() || original.provider === 'qq' && (!text(form.appId).trim() || original.adding && !text(form.secret))} onClick={save}>{busy ? '等待原保存回执…' : '保存这项连接'}</Button></div>
  </div></SettingsDialog>;
}

function ChannelLogin({ run, close, register }: { run: Run; close: () => void; register: Back }) {
  const [id, setId] = useState(() => crypto.randomUUID()), [name, setName] = useState('我的微信私聊'), [code, setCode] = useState(''), [view, setView] = useState<Data | null>(null), [attempted, setAttempted] = useState(false);
  const { request, busy, error, alive } = useRequest(run), idRef = useRef(id); idRef.current = id;
  const apply = (expected: string, value: Data) => { if (alive.current && idRef.current === expected && value.loginId === expected) setView(value); };
  const read = (action: string, extra: Data = {}) => { const original = id; void request(action, { loginId: original, ...extra }).then(value => apply(original, value)).catch(() => {}); };
  const leave = () => { setCode(''); const original = id; void run('channels.loginCancel', { loginId: original }).catch(() => {}); close(); };
  useBack(register, busy, leave);
  useEffect(() => {
    const visibility = () => { setCode(''); if (document.visibilityState !== 'visible') setView(null); else { const original = idRef.current; void run('channels.loginInspect', { loginId: original }).then(value => apply(original, object(value))).catch(() => {}); } };
    document.addEventListener('visibilitychange', visibility); return () => { document.removeEventListener('visibilitychange', visibility); void run('channels.loginCancel', { loginId: idRef.current }).catch(() => {}); };
  }, []);
  const labels: Data = { starting: '正在请求原官方二维码', awaiting_scan: '请用微信扫描这次二维码', wait: '等待扫描确认', scaned: '已扫描，等待手机确认', need_verifycode: '官方流程需要核对码', confirmed: '账号登录已确认', cancelled: '原本机登录检查已取消', expired: '原二维码已到期', unknown: '原登录状态未知，请先核对已保存账号', failed: '原登录未完成', verify_code_blocked: '官方核对码流程已停止', not_started: '原本机扫码窗口已关闭' };
  const terminal = ['confirmed', 'cancelled', 'expired', 'unknown', 'failed', 'verify_code_blocked', 'not_started'].includes(text(view?.status));
  return <SettingsDialog isOpen title="连接微信私聊" onClose={() => { if (!busy) leave(); }}><div className="pw-collaboration-dialog" data-collaboration-kind="ChannelsWorkspace">
    <p>使用现有官方 iLink 机器人登录。二维码只在本机编码；成功后仍需接收本人私聊，再另行绑定主人。</p>
    {!attempted && <TextField label="连接名称" value={name} maxLength={80} onChange={setName} />}
    {view && <p role="status">{labels[view.status] || '已收到官方流程回执，请继续核对原登录'}</p>}
    {view?.qrDataUrl?.startsWith('data:image/png;base64,') && <img src={view.qrDataUrl} alt="本次微信官方登录二维码" width={280} height={280} style={{ maxWidth: '100%', height: 'auto', imageRendering: 'pixelated' }} />}
    {view?.qrError && <p role="status">{view.qrError}</p>}
    {view?.officialUrl && <p><a href={view.officialUrl} target="_blank" rel="noreferrer">打开这次官方登录页面</a></p>}
    {view?.status === 'need_verifycode' && <TextField label="官方核对码" type="password" autoComplete="one-time-code" value={code} onChange={setCode} />}
    {view?.profile && <p>已保存账号：{view.profile.name}。尚未自动绑定主人、开启接收或自动回复。</p>}{view?.message && <p>{view.message}</p>}
    {error && <p role="alert">{error} 保留原登录编号，可先只读核对；不会另建二维码。</p>}
    <div className="pw-form-actions">
      {!attempted && <Button disabled={busy || !name.trim()} onClick={() => { setAttempted(true); read('channels.loginStart', { name: name.trim() }); }}>请求官方登录二维码</Button>}
      {attempted && <Button variant="secondary" disabled={busy} onClick={() => read('channels.loginInspect')}>只读核对原会话</Button>}
      {attempted && !terminal && <Button disabled={busy} onClick={() => { const accepted = code; setCode(''); read('channels.loginPoll', accepted ? { verifyCode: accepted } : {}); }}>检查这次扫码确认</Button>}
      {attempted && terminal && view?.status !== 'confirmed' && <Button variant="secondary" disabled={busy} onClick={() => { const original = id; void request('channels.loginCancel', { loginId: original }).then(() => { if (!alive.current) return; const next = crypto.randomUUID(); idRef.current = next; setId(next); setView(null); setAttempted(false); setCode(''); }).catch(() => {}); }}>关闭原会话后重新开始</Button>}
      <Button variant="ghost" disabled={busy} onClick={leave}>返回账号列表</Button>
    </div>
  </div></SettingsDialog>;
}

function ChannelOwner({ original, operation, data, run, close, register }: { original: Data; operation: string; data: Data; run: Run; close: () => void; register: Back }) {
  const [eventId, setEvent] = useState(''), [accepted, setAccepted] = useState(false), [review, setReview] = useState<Data | null>(null), [attempted, setAttempted] = useState(false);
  const { request, busy, error } = useRequest(run), source = channelOwnerCandidates(data, original, Date.now()), stale = !channelOriginalCurrent(original.ownerIdentity, data);
  useBack(register, busy, close);
  const prepare = () => { setAccepted(false); void request('channels.reviewOwner', { ...original.ownerIdentity, operation, ...(operation === 'bind' ? { verifiedEventId: eventId } : operation === 'tasks' ? { enabled: original.requestedEnabled } : {}) }).then(setReview).catch(() => {}); };
  const title = operation === 'bind' ? '核对主人私聊' : operation === 'unbind' ? '解除原主人绑定' : original.requestedEnabled ? '允许原主人私聊创建任务' : '关闭原私聊任务权限';
  return <SettingsDialog isOpen title={title} onClose={() => { if (!busy) close(); }}><div className="pw-collaboration-dialog" data-collaboration-kind="ChannelsWorkspace"><p>{original.name}</p>
    {operation === 'bind' && !review && <>
      <p>请选择本人账号最近发来的已认证私聊。昵称、转发内容或“我是主人”等消息正文不能建立身份。</p>
      <SelectField label="原私聊证据" value={eventId} options={source.map(row => ({ id: row.id, label: `${row.senderId} · ${text(row.text).slice(0, 80) || '媒体消息'}`, description: row.authenticatedBy + (row.conversationKind ? ` · ${row.conversationKind}` : '') }))} onChange={setEvent} isDisabled={busy} />
      {!source.length && <p>当前连接代次没有可核对的本人私聊。请先从自己的账号发送新消息并接收。</p>}
    </>}
    {review && <><Facts values={[[ '连接', review.profileName ], ['原账号', review.senderId ], ['认证来源', review.authenticatedBy ], ['原生场景', review.conversationKind ]]} />{review.text && <p style={{ whiteSpace: 'pre-wrap' }}>{review.text}</p>}</>}
    <p>{operation === 'bind' ? '绑定后此原账号私聊可使用私人问答与记忆。创建任务仍须单独开启；写入、外发和手机工具仍按原权限审批。' : operation === 'unbind' ? '后续消息回到外部会话，旧主人任务权限与私人回答不能继续使用；已发生的动作和原记录不会撤回。' : original.requestedEnabled ? '只接收这个已认证账号的明确 /task 目标。私聊中的“同意”不代替本机审批，不自动批准写入、外发或手机控制。' : '旧授权下的任务将不能继续调用模型或工具；实际已发生的动作仍保留在原任务中核对。'}</p>
    {review && <CheckboxField isSelected={accepted} onChange={setAccepted} isDisabled={busy || attempted || stale}>我已核对这个原账号与本次权限范围</CheckboxField>}
    {stale && <p role="alert">原连接、主人或权限已变化，请返回重新核对。</p>}{error && <p role="alert">{error}</p>}
    <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={close}>返回</Button>{!review ? <Button disabled={busy || stale || operation === 'bind' && !eventId} onClick={prepare}>读取原审阅</Button> : <Button disabled={busy || attempted || stale || !accepted} onClick={() => { setAttempted(true); void request('channels.confirmOwner', { reviewHandle: review.reviewHandle }).then(close).catch(() => {}); }}>{busy ? '等待原回执…' : '明确确认此操作'}</Button>}</div>
  </div></SettingsDialog>;
}

function ChannelConfirmation({ modal, run, close, register }: { modal: Modal; run: Run; close: () => void; register: Back }) {
  const [accepted, setAccepted] = useState(false), [attempted, setAttempted] = useState(false), { request, busy, error } = useRequest(run);
  useBack(register, busy, close); const receiving = modal.kind === 'receiving';
  return <SettingsDialog isOpen title={receiving ? '开启所选账号的持续接收' : '移除原渠道连接'} onClose={() => { if (!busy) close(); }}><div className="pw-collaboration-dialog" data-collaboration-kind="ChannelsWorkspace">
    {receiving ? <>{(Array.isArray(modal.review?.names) ? modal.review.names : []).map((name: string) => <p key={name}>{name}</p>)}<p>会建立有停止按钮的常驻通知并增加耗电；省电可能延迟接收。强停、进程结束或重启后不会静默恢复，已有任务须在原任务中停止。</p></> : <><p>{modal.review?.name}</p><p>移除此连接、它的主人绑定和本机渠道收发记录，停止相关接收；不撤回平台上已经发生的动作。</p></>}
    <CheckboxField isSelected={accepted} onChange={setAccepted} isDisabled={busy || attempted}>{receiving ? '我已核对所选账号及接收和耗电范围' : '我已核对这项原连接的移除范围'}</CheckboxField>{error && <p role="alert">{error} 请返回核对原状态，不重复本次确认。</p>}
    <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={close}>返回</Button><Button disabled={busy || attempted || !accepted} onClick={() => { setAttempted(true); void request(receiving ? 'channels.startReceiving' : 'channels.confirmRemoval', { reviewHandle: modal.review?.reviewHandle }).then(close).catch(() => {}); }}>{busy ? '等待实际回执…' : receiving ? '明确开启接收' : '移除这项连接'}</Button></div>
  </div></SettingsDialog>;
}

function ChannelHistory({ original, data, run, close, register }: { original: Data; data: Data; run: Run; close: () => void; register: Back }) {
  const [eventId, setEvent] = useState(''), [history, setHistory] = useState<Data | null>(null), { request, busy, error } = useRequest(run);
  useBack(register, busy, close);
  const candidates = rows(data.inbox).filter(event => event.profileId === original.id && event.transportEpoch === original.transportEpoch).reverse().slice(0, 12), coverage = object(history?.coverage);
  const stale = !rows(data.profiles).some(profile => profile.id === original.id && profile.transportEpoch === original.transportEpoch);
  return <SettingsDialog isOpen title="这次问答可读的历史" onClose={() => { if (!busy) close(); }}><div className="pw-collaboration-dialog" data-collaboration-kind="ChannelsWorkspace"><p>只读取本机已接收、同账号和同会话的部分记录。平台完整历史和离线遗漏仍未知。</p>
    {original.receivingCheckpoint && <p>{original.receivingCheckpoint.persisted ? 'QQ 原同步位置已保存；平台若保留会话，可从原位置请求重放。' : '当前没有可用的 QQ 同步位置。'}</p>}
    <SelectField label="原消息" value={eventId} options={candidates.map(event => ({ id: event.id, label: `${event.kind === 'group' ? '群 ' + event.roomId : '私聊 ' + event.senderId} · ${text(event.text).slice(0, 100) || '媒体消息'}` }))} onChange={value => { setEvent(value); setHistory(null); }} isDisabled={busy || stale} />
    {history && <><Facts values={[[ '本次包含', coverage.includedMessageCount ], ['本机可用', coverage.availableMessageCount ], ['窗口省略', coverage.omittedByLimit ], ['未确认投递未作历史回复', coverage.unconfirmedRepliesOmitted ]]} />{rows(history.messages).map((row, index) => <div className="pw-selected-object" key={index}><strong>{row.role === 'assistant' ? '已确认发出的回复' : '已收到'}</strong><p style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{row.content}</p></div>)}</>}
    {stale && <p role="alert">原连接代次已变化，请返回重新读取。</p>}{error && <p role="alert">{error}</p>}
    <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={close}>返回</Button><Button disabled={busy || stale || !eventId} onClick={() => { void request('channels.history', { ...channelIdentity(original), eventId }).then(setHistory).catch(() => {}); }}>读取原消息之前的记录</Button></div>
  </div></SettingsDialog>;
}

function ChannelInbox({ data, supported, run, connect, account, media }: { data: Data; supported: (action: string) => boolean; run: Run; connect: () => void; account: (id: string) => void; media: (event: Data, index: number) => void }) {
  const { request, busy, error } = useRequest(run), [limit, setLimit] = useState(12), [expanded, setExpanded] = useState<Set<string | number>>(new Set());
  const inbox = rows(data.inbox).slice().reverse(), profiles = rows(data.profiles);
  // Expanded original messages stay mounted when new arrivals move them beyond the initial window.
  const visible = inbox.filter((event, index) => index < limit || expanded.has(event.id));
  const descriptions: Data = { frozen_bytes_not_model_understanding: '实际图片已保存，尚无本次模型响应证明', model_request_prepared: '图片输入已准备，模型结果仍待确认', model_response_received: '模型已返回本次图片输入的响应', model_result_unconfirmed: '模型结果未确认，原文件已保留' };
  return <div className="pw-channels-inbox">
    <p className="pw-channels-caption">仅显示本机已接收的消息</p>
    {!inbox.length && <div className="pw-channels-empty"><MessageSquare size={26} aria-hidden /><p>最近消息会出现在这里</p><span>连接账号并明确开启接收后，从自己的账号发送一条私聊。</span><Button variant="secondary" onClick={connect}>查看连接与接收</Button></div>}
    <Accordion className="pw-channels-records" allowsMultipleExpanded expandedKeys={expanded} onExpandedChange={keys => setExpanded(new Set(keys))}>
      {visible.map(event => <AccordionItem id={event.id} key={event.id} title={<span className="pw-channels-preview">{event.text || '媒体消息'}</span>} description={<span className="pw-channels-message-meta"><span>{profiles.find(profile => profile.id === event.profileId)?.name || '原连接'} · {event.kind === 'group' ? event.roomId : event.senderId}</span>{messageTime(event.receivedAt) && <time dateTime={new Date(event.receivedAt).toISOString()}>{messageTime(event.receivedAt)}</time>}</span>}>
        <div className="pw-channels-record-detail"><p className="pw-channels-body">{event.text || '媒体消息；正文为空不代表已读取媒体内容。'}</p>
          {event.imageInputState && <p className="pw-field-hint">{descriptions[event.imageInputState] || '图片尚未提供给模型'}</p>}
          <div className="pw-form-actions">{event.profileId && <Button variant="ghost" onClick={() => account(event.profileId)}>查看原连接</Button>}
            {object(event.latestInferenceReference).taskId && <Button variant="ghost" disabled={busy || !supported('tasks.open')} onClick={() => { void request('tasks.open', { id: event.latestInferenceReference.taskId }).catch(() => {}); }}>查看本消息最近一次模型用量</Button>}
            {rows(event.ownerImageArtifacts).map(ref => <Button key={ref.artifactId} variant="secondary" disabled={busy || !supported('channels.openImage')} onClick={() => { void request('channels.openImage', { eventId: event.id, id: ref.artifactId, sha256: ref.sha256 }).catch(() => {}); }}>查看固定原图 · 第 {Number(ref.itemIndex) + 1} 项</Button>)}
            {rows(object(event.messageParts).parts).map((part, index) => ['media', 'unsupported_media'].includes(part.kind) && (part.kind === 'media' || part.media) ? <Button key={index} variant="secondary" disabled={!supported('channels.reviewMedia')} onClick={() => media(event, index)}>核对原件 · {part.declaredName || object(part.media).name || (part.mediaKind === 'voice' ? '原语音' : part.mediaKind === 'video' ? '原视频' : '原文件')}</Button> : null)}
          </div><Accordion className="pw-channels-source-details"><AccordionItem title="消息来源与认证"><Facts values={[[ '来源', event.senderId ], ['会话', event.kind === 'group' ? event.roomId : event.conversationKind || '私聊' ], ['认证', event.authenticatedBy ]]} /></AccordionItem></Accordion>{event.message && <p className="pw-field-hint">{event.message}</p>}
        </div>
      </AccordionItem>)}
    </Accordion>
    {visible.length < inbox.length && <Button className="pw-channels-load-more" variant="ghost" onClick={() => setLimit(value => value + 12)}>查看更多本机消息</Button>}
    {error && <p role="alert">{error}</p>}
  </div>;
}

function ChannelMedia({ event, index, run, close, register }: { event: Data; index: number; run: Run; close: () => void; register: Back }) {
  const [viewId] = useState(() => crypto.randomUUID());
  const [review, setReview] = useState<Data | null>(null), [saved, setSaved] = useState<Data | null>(null), [accepted, setAccepted] = useState(false), [attempted, setAttempted] = useState(false);
  const { request, busy, error, alive } = useRequest(run);
  const leave = () => { void run('channels.closeMedia', { viewId }).catch(() => {}); close(); }; useBack(register, busy, leave);
  useEffect(() => { void request('channels.reviewMedia', { viewId, eventId: event.id, itemIndex: index }).then(value => { if (alive.current) setReview(value); }).catch(() => {}); return () => { void run('channels.closeMedia', { viewId }).catch(() => {}); }; }, []);
  return <SettingsDialog isOpen title="核对这份本人私聊原件" onClose={() => { if (!busy) leave(); }}><div className="pw-collaboration-dialog" data-collaboration-kind="ChannelsWorkspace">
    {review && <><Facts values={[[ '原件', review.name ], ['连接', review.profileName ], ['原账号', review.senderId ], ['项目', Number(review.itemIndex) + 1 ], ['平台声明大小', review.declaredBytes === undefined ? '未声明；下载后核对' : `${review.declaredBytes} bytes` ]]} /><p>只从原已认证消息的官方 CDN 保存到本机私人附件，最多 8 MiB。不会自动调用模型、转写音视频、上传电脑或回发。</p></>}
    {saved ? <><Facts values={[[ '实际大小', `${saved.size} bytes` ], ['按文件头识别格式', saved.mimeType ], ['SHA-256', saved.sha256 ]]} /><p>原件已保存；格式标记不证明全部内容可播放或已理解。交给远端任务时仍需另选这份固定原件并确认外传。</p></> : review && <CheckboxField isSelected={accepted} onChange={setAccepted} isDisabled={busy || attempted}>我确认这份原本人私聊的这一项，只允许保存到本机</CheckboxField>}
    {error && <p role="alert">{error} 如保存结果未知，请重新只读核对原件，不重放本次确认。</p>}
    <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={leave}>返回</Button>{saved ? <Button disabled={busy || !review} onClick={() => { void request('channels.openMedia', { digest: review?.digest }).catch(() => {}); }}>打开固定原件</Button> : <Button disabled={busy || !review || !accepted || attempted} onClick={() => { setAttempted(true); void request('channels.freezeMedia', { digest: review?.digest }).then(value => setSaved(object(value.attachment))).catch(() => {}); }}>{busy ? '等待原件回执…' : review?.frozen ? '核对本机固定版本' : '保存这一项'}</Button>}</div>
  </div></SettingsDialog>;
}

function ChannelTask({ row, task, supported, run }: { row: Data; task?: Data; supported: (action: string) => boolean; run: Run }) {
  const [value, setValue] = useState<Data | null>(null), { request, busy, error } = useRequest(run);
  return <article className="pw-channels-record-detail"><Facts values={[[ '入站状态', admissionState(row.admission) ], ['原任务状态', task?.status ], ['取消回执', cancellationState(row.cancellation) ]]} />
    {['admission_unknown', 'cancellation_unknown'].includes(row.admission) || row.cancellation === 'cancellation_unknown' ? <p>上次请求结果未知；只核对原宿主任务，不重新提交或执行。</p> : null}
    {value && <p>{value.found === true ? '已核对同一宿主原任务，未重新执行。' : value.message || '原宿主结果仍待核对。'}</p>}{error && <p role="alert">{error}</p>}
    <div className="pw-form-actions"><Button variant="secondary" disabled={busy || !supported('channels.inspectTask')} onClick={() => { void request('channels.inspectTask', { requestId: row.requestId }).then(setValue).catch(() => {}); }}>核对原宿主回执</Button>{task && <Button variant="ghost" disabled={!supported('tasks.open')} onClick={() => { void run('tasks.open', { id: task.id }).catch(() => {}); }}>打开原任务</Button>}</div>
  </article>;
}
