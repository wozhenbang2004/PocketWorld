import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { Accordion, AccordionItem, Button, CheckboxField, SearchField, SelectField, SettingsDialog, TextAreaField, TextField } from './forms-library';
import { bridge } from './bridge';
import { connectionProfilePayload, wirelessPort } from './forms-library/host-contracts';
import TerminalSurface from './TerminalSurface';
import { resumeSshFile } from './workspace-resume';
import { useDataSettingsJourney } from './DataSettingsJourney';
import { HarnessEvidence } from './HarnessEvidence';
import { RemoteProjectBaseline } from './RemoteProjectBaseline';
import { RemoteInputFlow } from './RemoteTaskInputs';
import { harnessOptions, harnessProbeReady, harnessSelection, harnessWorkerLabel, isConfiguredHarness } from './harness-capabilities';
import { IconAdd, IconArrowLeft, IconArrowUp, IconChevronRight, IconFileText, IconFolder, IconHistory, IconMonitor, IconServer, IconShieldCheck, IconTerminal, IconTool } from './icons';
import './connection-workspace.css';

type Data = Record<string, any>;
type Run = (action: string, args?: Data) => Promise<any>;
type Props = { domain: 'remote' | 'ssh'; state: Data; data: Data; supported: (action: string) => boolean; run: Run; registerTransientBack: (handler: (() => boolean) | null) => void };
const object = (value: any): Data => value && typeof value === 'object' && !Array.isArray(value) ? value : {};
const list = (value: any): Data[] => Array.isArray(value) ? value.filter(item => item && typeof item === 'object' && !Array.isArray(item)) : [];
const text = (value: any) => typeof value === 'string' ? value : value == null ? '' : String(value);
const status = (value: any) => ({ completed: '已完成', failed: '未完成', running: '执行中', queued: '等待执行', unknown: '待核对', submitting: '提交结果待核对', awaiting_approval: '等待授权', awaiting_input: '等待补充', paused: '已暂停', interrupted: '已中断', stopped: '已停止', cancelled: '已取消', stopping: '正在停止' } as Data)[text(value)] || text(value);
const originalJob = (job: Data) => ({ profileId: job.profileId, jobId: job.id, jobOperationId: job.operationId, serviceBase: job.serviceBase, _profileBinding: job.profileBinding });
const binding = (profile: Data) => ({ profileId: profile.id, _profileBinding: profile.bindingHash });
const connectionWindow = () => { const view = bridge.getState().view as Data | undefined; return JSON.stringify([view?.section, view?.taskId, view?.resumeRevision, bridge.getDocumentEpoch()]); };
function Facts({ values }: { values: Array<[string, any]> }) { return <dl className="pw-facts">{values.filter(([, value]) => value !== undefined && value !== null && value !== '').map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{text(value)}</dd></div>)}</dl>; }
function Receipt({ value, run }: { value: Data | null; run: Run }) {
  if (!value) return null;
  const task = value.hostTaskId || value.taskId;
  return <div className="pw-selected-object"><Facts values={[[ '原操作状态', status(value.status) ], ['退出码', value.exitCode ]]} />
    {value.message && <p>{value.message}</p>}{value.status === 'unknown' && <p>请在原任务核对实际结果；没有自动重做或宣称远端已停止。</p>}
    {(value.sessionId || value.threadId) && <Accordion><AccordionItem id="remote-receipt-session" title="原会话与实际模型">
      <Facts values={[[ '执行器', value.worker ? harnessWorkerLabel(undefined, value.worker) : undefined ], ['原会话', value.sessionId || value.threadId ], ['请求模型', value.model ], ['执行端报告模型', value.reportedModel ], ['执行模式', value.modeId ]]} />
      {(value.resumeSupported === false || value.sessionResumeSupported === false) && <p className="pw-field-hint">执行端报告本会话不支持恢复。可用的补充、停止和审批操作请在原任务核对。</p>}
      {object(value.capabilities).teamToolsOnly === true && object(value.capabilities).codingToolsSupported === false && <p className="pw-field-hint">此会话仅开放团队协作工具，未开放代码、命令与文件工具。</p>}
    </AccordionItem></Accordion>}
    <Accordion variant="divided">{['stdout', 'stderr'].map(key => value[key] ? <AccordionItem id={'connection-' + key} key={key} title={key === 'stdout' ? '标准输出' : '错误输出'}><pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{value[key]}</pre></AccordionItem> : null)}</Accordion>
    {task && <Button variant="secondary" onClick={() => { void run('tasks.open', { id: task }).catch(() => {}); }}>打开原任务与回执</Button>}</div>;
}
function useRequest(run: Run, state: Data, visible = true) {
  const scope = `${state.webWorkspaceResumeRevision || 0}:${!!state.webWorkspaceTaskOpen}:${visible}`;
  const visibleNow = useRef(visible); visibleNow.current = visible;
  const currentScope = useRef(scope), generation = useRef(0), waiting = useRef(false), alive = useRef(true);
  currentScope.current = scope;
  const [busy, setBusy] = useState(false), [error, setError] = useState('');
  useEffect(() => { alive.current = true; return () => { alive.current = false; generation.current++; }; }, []);
  useLayoutEffect(() => { generation.current++; waiting.current = false; setBusy(false); setError(''); }, [scope]);
  useEffect(() => {
    let owner = connectionWindow();
    const invalidate = () => { generation.current++; waiting.current = false; setBusy(false); };
    const hidden = () => { if (document.visibilityState !== 'visible') invalidate(); };
    const unsubscribe = bridge.subscribe(() => { const incoming = connectionWindow(); if (incoming !== owner) { owner = incoming; invalidate(); } });
    document.addEventListener('visibilitychange', hidden); window.addEventListener('pagehide', invalidate);
    return () => { unsubscribe(); document.removeEventListener('visibilitychange', hidden); window.removeEventListener('pagehide', invalidate); };
  }, []);
  const request = async (action: string, args: Data = {}) => {
    if (!alive.current || !visibleNow.current || currentScope.current !== scope || state.webWorkspaceTaskOpen || document.visibilityState !== 'visible') throw new Error('请先返回原连接页面，再读取或操作。');
    if (waiting.current) throw new Error('请等待原操作回执。');
    const original = ++generation.current, owner = connectionWindow();
    const current = () => alive.current && original === generation.current && currentScope.current === scope && owner === connectionWindow() && document.visibilityState === 'visible';
    waiting.current = true; setBusy(true); setError('');
    try { const value = await run(action, args); if (!current()) throw new Error('原读取窗口已变化，请核对原操作。'); return object(value); }
    catch (failure) { if (current()) setError(failure instanceof Error ? failure.message : String(failure)); throw failure; }
    finally { if (current()) { waiting.current = false; setBusy(false); } }
  };
  return { request, busy, error, alive, waiting };
}

/** BoardUI controls retain their interaction contract. The compact object rows
 * and task-specific disclosure follow the inspected OpenMuse mobile LinkRow
 * (jerelvelarde/openmuse 61796eb2, apps/mobile/src/ui.tsx). */
function ConnectionRow({ title, detail, icon: Icon, onOpen, disabled = false, entry, children }: { title: string; detail: string; icon: typeof IconMonitor; onOpen: () => void; disabled?: boolean; entry?: string; children?: ReactNode }) {
  return <Button variant="ghost" className="pw-connection-row" disabled={disabled} data-operation-entry={entry} onClick={onOpen}>
    <span className="pw-connection-row-icon"><Icon size={19} aria-hidden /></span>
    <span className="pw-connection-row-copy"><strong>{title}</strong><small>{detail}</small>{children}</span>
    <IconChevronRight size={16} className="pw-connection-chevron" aria-hidden />
  </Button>;
}
const profileAddress = (profile: Data) => text(profile.baseUrl || `${profile.username}@${profile.host}:${profile.port}`);
const profileReadiness = (domain: 'remote' | 'ssh', profile: Data) => domain === 'remote'
  ? profile.hasToken ? '凭据已配置 · 能力待核对' : '待配置连接令牌'
  : !profile.credentialsConfigured ? '待配置凭据' : profile.hostKeyTrusted ? '已保存主机信任' : '待核对主机指纹';

export default function ConnectionWorkspace({ domain, state, data, supported, run, registerTransientBack }: Props) {
  const profiles = list(data.profiles), [editing, setEditing] = useState<Data | null>(null), [selected, setSelected] = useState<Data | null>(null), [removing, setRemoving] = useState<Data | null>(null);
  const editorTrigger = useRef<HTMLElement | null>(null);
  const workBack = useRef<(() => boolean) | null>(null), editorBack = useRef<(() => boolean) | null>(null);
  const journey = useDataSettingsJourney();
  const [workOpened, setWorkOpened] = useState(false), [query, setQuery] = useState('');
  const [probe, setProbe] = useState<Data | null>(null), [trust, setTrust] = useState<Data | null>(null), [checked, setChecked] = useState(false), [receipt, setReceipt] = useState<Data | null>(null);
  const { request, busy, error } = useRequest(run, state);
  const resume = Number(state.webWorkspaceResumeRevision || 0), previousResume = useRef(resume);
  const overview = !!selected && journey.view === 'profile:' + selected.id;
  const working = !!selected && journey.view === 'work:' + selected.id;
  const showingJobs = journey.view === 'all-jobs' || journey.view.startsWith('jobs:');
  const stale = selected && !profiles.some(profile => profile.id === selected.id && profile.bindingHash === selected.bindingHash);
  const jobs = list(data.jobs).filter(job => journey.view === 'all-jobs' || job.profileId === selected?.id);
  const unresolvedOperations = list(data.operations).filter(item => item.profileId === selected?.id && ['unknown', 'submitting'].includes(item.status));
  const sshTasks = list(state.tasks).filter(task => object(task.raw).feature === 'ssh' && object(object(task.raw).externalRefs).profileId === selected?.id);
  const goBack = () => {
    if (busy) return true;
    if (working && workBack.current?.()) return true;
    if (selected && (working || showingJobs && journey.view !== 'all-jobs')) { journey.open('profile:' + selected.id); return true; }
    return journey.back();
  };
  useEffect(() => { if (previousResume.current === resume) return; previousResume.current = resume; setProbe(null); setTrust(null); setRemoving(null); setChecked(false); }, [resume]);
  useEffect(() => {
    registerTransientBack(() => {
      if (editing) return editorBack.current?.() ?? true;
      if (trust || removing) { if (!busy) { setTrust(null); setRemoving(null); } return true; }
      return goBack();
    });
    return () => registerTransientBack(null);
  }, [editing, trust, removing, busy, journey.view, selected, registerTransientBack]);
  const act = (action: string, args: Data, completed?: (value: Data) => void) => { void request(action, args).then(value => { setReceipt(value); completed?.(value); }).catch(() => {}); };
  const available = (action: string) => supported(domain + '.' + action) && !busy;
  const openProfile = (profile: Data) => {
    if (selected?.id !== profile.id || selected?.bindingHash !== profile.bindingHash) { setSelected({ ...profile }); setWorkOpened(false); setProbe(null); setReceipt(null); }
    journey.open('profile:' + profile.id);
  };
  const openWork = () => { setWorkOpened(true); journey.open('work:' + selected!.id); };
  const collectionName = domain === 'remote' ? '电脑环境' : 'SSH 主机';
  const filteredProfiles = profiles.filter(profile => `${profile.name || ''} ${profileAddress(profile)} ${profile.workspace || ''}`.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()));
  const needsConfiguration = (profile: Data) => domain === 'remote' ? !profile.hasToken : !profile.credentialsConfigured || !profile.hostKeyTrusted;
  const profileSections = [
    { id: 'attention', title: '需要配置或核对', profiles: filteredProfiles.filter(needsConfiguration) },
    { id: 'configured', title: '已保存配置', profiles: filteredProfiles.filter(profile => !needsConfiguration(profile)) },
  ];
  const openEditor = (profile: Data) => { editorTrigger.current = document.activeElement as HTMLElement; setEditing(profile); };
  const closeEditor = () => { setEditing(null); requestAnimationFrame(() => { const trigger = editorTrigger.current; (trigger?.isConnected && trigger.getClientRects().length ? trigger : journey.root.current)?.focus({ preventScroll: true }); }); };
  return <div className="pw-connection-workspace" ref={journey.root} tabIndex={-1} data-connection-view={journey.view}>
    <div hidden={journey.view !== 'list'}>
      <header className="pw-connection-collection-heading"><p className="pw-connection-directory-count">{profiles.length} 个{collectionName}</p><Button variant="secondary" leadingIcon={IconAdd} aria-label={'添加' + collectionName} disabled={busy} onClick={() => openEditor({})}>添加连接</Button></header>
      {profiles.length > 0 && <SearchField className="pw-connection-directory-search" fieldClassName="pw-connection-search" shape="rounded" aria-label={'搜索' + collectionName} placeholder="搜索名称、地址或工作区" value={query} onChange={setQuery} />}
      {profiles.length === 0 ? <div className="pw-connection-empty"><IconMonitor size={25} aria-hidden /><h3>{domain === 'remote' ? '连接你的第一台电脑' : '添加你的第一台主机'}</h3><p>{domain === 'remote' ? '填写 Companion 地址与令牌，再读取实际工作环境。' : '保存地址与凭据后，通过可信渠道核对主机指纹。'}</p><Button disabled={busy} onClick={() => openEditor({})}>添加{collectionName}</Button></div> : <div className="pw-connection-directory">{profileSections.filter(section => section.profiles.length > 0).map(section => <section className="pw-connection-directory-group" key={section.id} aria-label={section.title}><header><h3>{section.title}</h3><span>{section.profiles.length}</span></header><div className="pw-connection-list">{section.profiles.map(profile => <ConnectionRow key={profile.id} title={profile.name} detail={profileAddress(profile)} icon={domain === 'remote' ? IconMonitor : IconServer} disabled={busy} entry={'profile:' + profile.id} onOpen={() => openProfile(profile)}><small className="pw-connection-readiness">{profileReadiness(domain, profile)}</small></ConnectionRow>)}</div></section>)}{query && !filteredProfiles.length && <p className="pw-secondary-empty">没有匹配的连接。</p>}</div>}
      {domain === 'remote' && list(data.jobs).length > 0 && <div className="pw-connection-history-entry"><ConnectionRow title="电脑上的原任务" detail="按原连接读取回执，保留已经发生的工作" icon={IconHistory} entry="all-jobs" disabled={busy} onOpen={() => journey.open('all-jobs')} /></div>}
    </div>
    {journey.view !== 'list' && <header className="pw-connection-detail-heading">
      <Button variant="ghost" leadingIcon={IconArrowLeft} disabled={busy} onClick={goBack}>{overview || journey.view === 'all-jobs' ? '返回' + collectionName : working ? '返回' : '返回连接概览'}</Button>
      <div tabIndex={-1} data-operation-heading><h2>{journey.view === 'all-jobs' ? '电脑上的原任务' : selected?.name}</h2>{selected && journey.view !== 'all-jobs' && <p className="pw-connection-address">{profileAddress(selected)}</p>}</div>
    </header>}
    {error && <p className="pw-secondary-error" role="alert">{error}</p>}
    {selected && journey.view !== 'list' && journey.view !== 'all-jobs' && stale && <div className="pw-connection-attention"><p role="alert">连接已在另一页面修改。旧目标仍显示在这里，尚未沿用它开始新工作。</p><Button variant="secondary" onClick={() => { const current = profiles.find(profile => profile.id === selected.id); setSelected(current ? { ...current } : null); setWorkOpened(false); setProbe(null); setReceipt(null); if (!current) journey.back(); else journey.open('profile:' + current.id); }}>重新读取当前连接</Button></div>}
    {selected && overview && !stale && <>
      <div className="pw-connection-summary"><span className="pw-connection-summary-icon">{domain === 'remote' ? <IconMonitor size={22} aria-hidden /> : <IconServer size={22} aria-hidden />}</span><div><h3>{profileReadiness(domain, selected)}</h3><p>{domain === 'remote' ? '能力与账号状态以每次实际核对为准。' : '当前工作区 · ' + (selected.workspace || '尚未设置')}</p></div></div>
      {domain === 'remote' && unresolvedOperations.length > 0 && <section className="pw-connection-attention" aria-label="待核对的原提交"><h3>有原提交待核对</h3><p>先读取原任务的实际结果，不会重新发送这次工作。</p>{unresolvedOperations.map(item => <ConnectionRow key={item.operationId} title={item.worker || '电脑原操作'} detail={status(item.status) + ' · ' + text(item.operationId)} icon={IconHistory} onOpen={() => { void run('tasks.open', { id: item.operationId }).catch(() => {}); }} />)}</section>}
      <div className="pw-connection-list">
        <ConnectionRow title={domain === 'remote' ? '进入电脑工作区' : '进入主机工作区'} detail={domain === 'remote' ? '继续原工作，或核对后开始下一项' : '任务、文件、命令和终端'} icon={domain === 'remote' ? IconMonitor : IconTerminal} disabled={busy} onOpen={openWork} />
        <ConnectionRow title="查看原任务" detail={domain === 'remote' ? '读取回执、处理审批，继续同一项工作' : '回到原任务，查看执行、审批与结果'} icon={IconHistory} disabled={busy} onOpen={() => journey.open('jobs:' + selected.id)} />
      </div>

      <section className="pw-connection-detail-section"><h3>{domain === 'remote' ? '连接能力' : '主机身份'}</h3>
        {domain === 'ssh' && <><Facts values={[[ '已保存指纹', selected.hostFingerprint || '尚未保存' ]]} /><p className="pw-field-hint">指纹须与这台主机的可信来源一致。普通 SSH 不保证断线后任务继续。</p></>}
        <div className="pw-form-actions"><Button variant="secondary" disabled={!available(domain === 'remote' ? 'probe' : 'probeHostKey')} onClick={() => act(domain === 'remote' ? 'remote.probe' : 'ssh.probeHostKey', binding(selected), value => setProbe({ ...value, originalProfile: { ...selected } }))}>{domain === 'remote' ? '探测能力' : '查看主机指纹'}</Button>{domain === 'remote' && <Button variant="ghost" disabled={!available('reconnect')} onClick={() => act('remote.reconnect', binding(selected))}>同步原任务</Button>}</div>
      </section>
      {probe && domain === 'ssh' && <section className="pw-connection-attention"><h3>本次实际主机指纹</h3><Facts values={[[ '主机', probe.originalProfile?.name ], ['指纹', probe.fingerprint ], ['密钥类型', probe.keyType ]]} /><p>请与这台主机已有可信渠道提供的指纹核对。探测没有发送登录凭据。</p>
        {probe.changed ? <><p role="alert">主机密钥已变化，当前连接不会执行。核对原因后，可先移除原信任，再重新探测。</p><Button variant="secondary" disabled={busy} onClick={() => { setTrust({ kind: 'forget', profile: probe.originalProfile }); setChecked(false); }}>核对并移除原信任</Button></> : <Button variant="secondary" disabled={busy || !probe.probeId} onClick={() => { setTrust({ kind: 'approve', ...probe }); setChecked(false); }}>核对并信任这个指纹</Button>}</section>}
      <Receipt value={receipt} run={run} />
      <Accordion><AccordionItem id="connection-settings" title="连接设置" description="地址、凭据与本机配置"><Facts values={[[ '工作区', selected.workspace ], ['凭据', selected.hasToken || selected.credentialsConfigured ? '已配置' : '未配置' ]]} /><div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => openEditor({ ...selected })}>编辑连接</Button><Button variant="ghost" disabled={busy} onClick={() => setRemoving({ ...selected })}>移除</Button></div></AccordionItem></Accordion>
    </>}
    {selected && workOpened && !stale && <div hidden={!working} className="pw-connection-work-body">{domain === 'remote' ? <RemoteWork key={selected.id + selected.bindingHash} profile={selected} state={state} data={data} run={run} supported={supported} back={workBack} visible={working} /> : <SshWork key={selected.id + selected.bindingHash} profile={selected} state={state} data={data} run={run} supported={supported} back={workBack} visible={working} />}</div>}
    {showingJobs && (domain === 'remote' ? <div className="pw-connection-jobs">{jobs.length === 0 && <p className="pw-secondary-empty">这项连接暂时没有已读取的原任务。可返回概览同步。</p>}{jobs.map(job => <article className="pw-connection-job" key={job.id}><header><div><h3>{job.worker || '电脑任务'}</h3><p>{profiles.find(profile => profile.id === job.profileId)?.name || job.serviceBase || '原连接'}</p></div><span>{status(job.status)}</span></header>{job.message && <p>{job.message}</p>}<div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => act('remote.getJob', originalJob(job))}>读取原回执</Button>{list(state.tasks).find(task => object(task.raw).externalId === job.id && object(object(task.raw).externalRefs).profileId === job.profileId)?.id && <Button variant="ghost" onClick={() => { const task = list(state.tasks).find(task => object(task.raw).externalId === job.id && object(object(task.raw).externalRefs).profileId === job.profileId); void run('tasks.open', { id: task!.id }).catch(() => {}); }}>打开本机原任务</Button>}{['queued', 'running', 'stopping', 'awaiting_approval', 'awaiting_input'].includes(job.status) && <Button variant="ghost" disabled={busy} onClick={() => act('remote.cancelJob', originalJob(job))}>请求停止原任务</Button>}</div></article>)}<Receipt value={receipt} run={run} /></div> : <div className="pw-connection-jobs">{sshTasks.length === 0 && <p className="pw-secondary-empty">这项主机暂时没有已记录的任务。</p>}{sshTasks.map(task => <ConnectionRow key={task.id} title={task.title || task.goal || object(task.raw).command || 'SSH 任务'} detail={status(task.status)} icon={IconHistory} onOpen={() => { void run('tasks.open', { id: task.id }).catch(() => {}); }} />)}</div>)}
    {editing && <ProfileEditor key={editing.id || 'new'} domain={domain} original={editing} current={profiles.find(profile => profile.id === editing.id)} state={state} run={run} back={editorBack} close={closeEditor} />}
    <SettingsDialog isOpen={!!removing} title="移除这个连接？" onClose={() => { if (!busy) setRemoving(null); }}><p>只移除本机连接配置。已发生的远端操作请在原任务核对。</p><Facts values={[[ '连接', removing?.name ], ['地址', removing?.baseUrl || removing?.host ]]} /><div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => setRemoving(null)}>取消</Button><Button disabled={busy} onClick={() => removing && act(domain + '.removeProfile', { profileId: removing.id, expectedBindingHash: removing.bindingHash }, () => { setRemoving(null); setSelected(null); setWorkOpened(false); journey.back(); })}>移除这项原连接</Button></div></SettingsDialog>
    <SettingsDialog isOpen={!!trust} title={trust?.kind === 'forget' ? '移除原主机信任' : '确认主机身份'} onClose={() => { if (!busy) setTrust(null); }}><p>请先通过可信渠道核对这台主机，不仅依赖当前网络探测。</p><pre className="pw-connection-code">{trust?.kind === 'forget' ? trust?.profile?.hostFingerprint : trust?.fingerprint}</pre><CheckboxField isSelected={checked} onChange={setChecked}>{trust?.kind === 'forget' ? '已核对原因，移除这项原信任' : '已通过可信渠道核对一致'}</CheckboxField><div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => setTrust(null)}>取消</Button><Button disabled={busy || !checked} onClick={() => {
      if (!trust) return; const input = trust.kind === 'forget' ? { profileId: trust.profile.id, expectedBindingHash: trust.profile.bindingHash, fingerprint: trust.profile.hostFingerprint } : { profileId: trust.profileId, expectedBindingHash: trust.bindingHash, probeId: trust.probeId, fingerprint: trust.fingerprint };
      act(trust.kind === 'forget' ? 'ssh.forgetHostKey' : 'ssh.approveHostKey', input, () => { setTrust(null); setProbe(null); setSelected(null); setWorkOpened(false); journey.back(); });
    }}>确认这项操作</Button></div></SettingsDialog>
  </div>;
}

function ProfileEditor({ domain, original, current, state, run, close, back }: { domain: 'remote' | 'ssh'; original: Data; current?: Data; state: Data; run: Run; close: () => void; back: { current: (() => boolean) | null } }) {
  const [form, setForm] = useState<Data>({ name: text(original.name), baseUrl: text(original.baseUrl), host: text(original.host), port: text(original.port || 22), username: text(original.username), workspace: text(original.workspace), authType: original.authType === 'password' ? 'password' : 'key', token: '', privateKey: '', password: '', passphrase: '' });
  const [attempted, setAttempted] = useState(false), { request, busy, error } = useRequest(run, state);
  const clear = () => setForm(previous => ({ ...previous, token: '', privateKey: '', password: '', passphrase: '' }));
  useEffect(() => { const hide = () => { if (document.visibilityState !== 'visible') clear(); }; document.addEventListener('visibilitychange', hide); window.addEventListener('pagehide', clear); return () => { document.removeEventListener('visibilitychange', hide); window.removeEventListener('pagehide', clear); }; }, []);
  useEffect(() => { back.current = () => { if (!busy) { clear(); close(); } return true; }; return () => { back.current = null; }; }, [busy, back, close]);
  const stale = !!original.id && (!current || current.bindingHash !== original.bindingHash);
  const field = (key: string, value: any) => setForm(previous => ({ ...previous, [key]: value }));
  const canSave = !busy && !attempted && !stale && text(form.name).trim() && (domain === 'remote' ? text(form.baseUrl).trim() : text(form.host).trim() && text(form.username).trim() && text(form.workspace).startsWith('/') && wirelessPort(form.port));
  return <SettingsDialog isOpen title={domain === 'remote' ? '电脑环境连接' : 'SSH 连接'} onClose={() => { if (!busy) { clear(); close(); } }}><div className="pw-connection-profile-editor"><div className="pw-connection-profile-editor-scroll">
    <section><h3>连接位置</h3><div className="pw-form-grid">
      <TextField label="名称" value={form.name} onChange={value => field('name', value)} />
      {domain === 'remote' ? <TextField label="Companion 地址" type="url" value={form.baseUrl} onChange={value => field('baseUrl', value)} /> : <>
        <TextField label="主机" value={form.host} onChange={value => field('host', value)} /><TextField label="端口" inputMode="numeric" value={form.port} onChange={value => field('port', value.replace(/\D/g, '').slice(0, 5))} /><TextField label="用户名" value={form.username} onChange={value => field('username', value)} /><TextField label="工作区" value={form.workspace} onChange={value => field('workspace', value)} />
      </>}
    </div></section>
    <section><h3>访问凭据</h3><div className="pw-form-grid">
      {domain === 'remote' ? <TextField label="连接令牌" type="password" autoComplete="new-password" value={form.token} onChange={value => field('token', value)} hint="相同服务可留空保留凭据；更换服务需重新填写。" /> : <>
        <SelectField label="认证方式" value={form.authType} options={[{ id: 'key', label: '私钥' }, { id: 'password', label: '密码' }]} onChange={value => { clear(); field('authType', value); }} />
        {form.authType === 'key' ? <><TextAreaField label="私钥内容" value={form.privateKey} onChange={value => field('privateKey', value)} autoComplete="off" /><TextField label="私钥口令（如有）" type="password" value={form.passphrase} onChange={value => field('passphrase', value)} autoComplete="new-password" /></> : <TextField label="密码" type="password" value={form.password} onChange={value => field('password', value)} autoComplete="new-password" />}
        <p className="pw-field-hint">凭据只写入手机加密存储，不回填。更换主机或用户时请重新填写。</p>
      </>}
    </div></section>
    </div><footer className="pw-connection-profile-editor-footer">{stale && <p role="alert">连接已在其他页面修改，请关闭后重新读取。</p>}{error && <p role="alert">{error} 请先核对当前连接列表，不重复这次保存。</p>}<div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => { clear(); close(); }}>关闭</Button><Button disabled={!canSave} onClick={() => { const payload = connectionProfilePayload(domain, form, original); setAttempted(true); clear(); void request(domain + '.saveProfile', payload).then(close).catch(() => {}); }}>{busy ? '等待保存回执…' : '保存连接'}</Button></div></footer>
  </div></SettingsDialog>;
}

type RemoteDraft = { environmentId: string; worker: string; goal: string; argv: string; model: string; modeId: string; effort: string; readOnly: boolean; timeout: string; operation: Data | null; uncertain: boolean };
const remoteDrafts = new Map<string, RemoteDraft>();
function keepRemoteDraft(key: string, draft: RemoteDraft) {
  remoteDrafts.delete(key); remoteDrafts.set(key, { ...draft, operation: draft.operation ? { ...draft.operation, reviewHandle: undefined } : null });
  while (remoteDrafts.size > 8) remoteDrafts.delete(remoteDrafts.keys().next().value!);
}
const geminiId = 'gemini_cli_acp_v1';
function currentGeminiProbe(probe: Data | null, environment: Data | undefined, model: string): boolean {
  const value = object(probe?.backendObservation);
  return probe?.available === true && /^[a-f0-9]{64}$/.test(text(probe.capabilityRevision))
    && value.format === 'pocketworld.remote-backend-observation.v1' && value.backendId === geminiId
    && value.worker === 'gemini_acp' && value.backendVersion === '0.66.0' && value.protocolVersion === 1
    && value.available === true && value.modeId === 'default' && value.environmentId === environment?.id
    && value.environmentBinding === environment?.bindingHash && value.requestedModel === model.trim()
    && Number.isSafeInteger(value.expiresAt) && value.expiresAt > Date.now()
    && object(value.installObservation).state === 'observed' && object(value.protocol).state === 'verified';
}
function GeminiEvidence({ probe }: { probe: Data }) {
  const value = object(probe.backendObservation), install = object(value.installObservation), protocol = object(value.protocol), auth = object(value.authentication);
  const installation = ({ observed: '已核对固定版本 ' + text(install.packageVersion), missing: '未找到固定安装', unsupported: '当前平台不支持', changed: '原安装来源已变化', unavailable: '安装来源尚不可核验' } as Data)[text(install.state)] || '尚未核实安装';
  const handshake = ({ verified: '已核对 ACP 1 初始化握手', mismatch: '协议或执行器版本不匹配', unknown: '握手结果未知', not_checked: '尚未检查握手' } as Data)[text(protocol.state)] || '尚未核实握手';
  const authentication = auth.state === 'credential_missing' ? '未检测到预配置凭据'
    : auth.state === 'credential_present' ? '检测到凭据配置；登录与认证尚未验证' : '凭据与认证状态未知';
  return <div aria-label="Gemini 本次能力证据">
    <p role="status">{value.available === true ? '本次安装与初始化核对可用，尚未开始模型工作。' : '当前不能创建 Gemini 工作，请按本次核对原因处理。'}</p>
    <Facts values={[[ '安装', installation ], ['协议握手', handshake ], ['登录与认证', authentication ]]} />
    {object(value.reason).message && <p>{object(value.reason).message}</p>}
    <Accordion><AccordionItem id="gemini-evidence" title="模型、模式与本次证据范围">
      <Facts values={[[ '请求模型', value.requestedModel || '执行端默认' ], ['模型目录与实际推理', '尚未核实；首次原任务再核对实际模型' ], ['请求模式', '默认审批模式；实际会话尚未核实' ], ['后端', 'Gemini CLI 0.66.0 · ACP 1' ]]} />
      <p>这次探测没有创建会话、发送任务或验证登录。凭据存在不代表认证成功；安装来源核对也不代表整个执行链或操作系统沙箱已验证。</p>
      <p>首个版本仅用于本机 Linux 环境里的私人可写工作；不提供只读隔离、SSH、团队、群组、设备或工作配置接入。</p>
    </AccordionItem></Accordion>
  </div>;
}
function RemoteWork({ profile, state, data, run, supported, back, visible }: { profile: Data; state: Data; data: Data; run: Run; supported: (action: string) => boolean; back: { current: (() => boolean) | null }; visible: boolean }) {
  const key = profile.id + ':' + profile.bindingHash, initial = useRef(remoteDrafts.get(key));
  const [environments, setEnvironments] = useState<Data[] | null>(null), [environmentFresh, setEnvironmentFresh] = useState(false);
  const [environmentId, setEnvironment] = useState(initial.current?.environmentId || ''), [worker, setWorker] = useState(initial.current?.worker || 'codex_app_server'), [probe, setProbe] = useState<Data | null>(null);
  const [modeId, setModeId] = useState(initial.current?.modeId || ''), [optionCatalog, setOptionCatalog] = useState<Data | null>(null);
  const [goal, setGoal] = useState(initial.current?.goal || ''), [argv, setArgv] = useState(initial.current?.argv || '["git","status","--short"]'), [model, setModel] = useState(initial.current?.model || ''), [effort, setEffort] = useState(initial.current?.effort || ''), [readOnly, setReadOnly] = useState(initial.current?.readOnly ?? true), [timeout, setTimeoutValue] = useState(initial.current?.timeout || '300');
  const [writeIntent, setWriteIntent] = useState(false), [draftError, setDraftError] = useState(''), [review, setReview] = useState<Data | null>(null);
  const [baseline, setBaseline] = useState<Data | null>(null);
  const [inputRequest, setInputRequest] = useState<Data | null>(null);
  const [operation, setOperation] = useState<Data | null>(initial.current?.operation || null), [uncertain, setUncertain] = useState(initial.current?.uncertain || false), [receipt, setReceipt] = useState<Data | null>(null), { request, busy, error, alive, waiting } = useRequest(run, state, visible);
  const visibleNow = useRef(visible); visibleNow.current = visible;
  const pendingConfirm = useRef<Data | null>(null), currentState = useRef(state);
  const formVersion = useRef(0), shownVersion = formVersion.current;
  const dismissReview = () => { formVersion.current++; setReview(null); };
  currentState.current = state;
  const nativeActive = () => { const view = bridge.getState().view as Data | undefined; return alive.current && visibleNow.current && document.visibilityState === 'visible' && view?.section === 'remote' && !view.taskId && !currentState.current.webWorkspaceTaskOpen; };
  const [active, setActive] = useState(nativeActive);
  useEffect(() => { setActive(nativeActive()); if (!visible) { formVersion.current++; setReview(null); setWriteIntent(false); } }, [visible]);
  const draft = useRef<RemoteDraft>({ environmentId, worker, goal, argv, model, modeId, effort, readOnly, timeout, operation, uncertain });
  draft.current = { environmentId, worker, goal, argv, model, modeId, effort, readOnly, timeout, operation, uncertain };
  useEffect(() => { back.current = () => { if (waiting.current) return true; if (review) { dismissReview(); return true; } return false; }; return () => { back.current = null; }; }, [busy, review, back]);
  useEffect(() => () => { const pending = pendingConfirm.current; keepRemoteDraft(key, { ...draft.current, operation: pending || draft.current.operation, uncertain: draft.current.uncertain || !!pending }); }, [key]);
  useEffect(() => {
    let owner = connectionWindow();
    const invalidate = () => {
      formVersion.current++;
      setActive(nativeActive()); setReview(null); setProbe(null); setOptionCatalog(null); setWriteIntent(false); setEnvironmentFresh(false);
      if (pendingConfirm.current) { draft.current = { ...draft.current, operation: pendingConfirm.current, uncertain: true }; setUncertain(true); setReceipt(null); pendingConfirm.current = null; }
    };
    const unsubscribe = bridge.subscribe(() => { const incoming = connectionWindow(); if (incoming !== owner) { owner = incoming; invalidate(); } });
    const visibility = () => { if (document.visibilityState !== 'visible') invalidate(); else setActive(nativeActive()); };
    document.addEventListener('visibilitychange', visibility); window.addEventListener('pagehide', invalidate);
    return () => { unsubscribe(); document.removeEventListener('visibilitychange', visibility); window.removeEventListener('pagehide', invalidate); };
  }, []);
  useEffect(() => {
    const expiresAt = Number(object(probe?.backendObservation).expiresAt);
    if (worker !== 'gemini_acp' || !Number.isSafeInteger(expiresAt)) return;
    const timer = setTimeout(() => { formVersion.current++; setProbe(null); setReview(null); setDraftError('本次 Gemini 能力核对已过期，请按原目标重新核对。'); }, Math.max(0, expiresAt - Date.now()));
    return () => clearTimeout(timer);
  }, [probe, worker]);
  const environment = environments?.find(item => item.id === environmentId), workers = list(environment?.workers).filter(item => typeof item.id === 'string' && item.id);
  const selectedWorker = workers.find(item => item.id === worker), configuredHarness = isConfiguredHarness(worker);
  const harnessReady = harnessProbeReady(probe, selectedWorker), modelOptions = harnessOptions(optionCatalog, 'models'), modeOptions = harnessOptions(optionCatalog, 'modes');
  const unresolved = list(data.operations).filter(item => item.profileId === profile.id && ['unknown', 'submitting'].includes(item.status));
  const nativeTask = list(state.tasks).find(task => task.id === operation?.operationId), gemini = worker === 'gemini_acp';
  const locked = busy || !!operation || !!baseline || !!inputRequest || !active || !visible, geminiReady = currentGeminiProbe(probe, environment, model);
  const target = () => ({ ...binding(profile), environmentId, environmentBinding: environment?.bindingHash, worker, ...(model.trim() ? { model: model.trim() } : {}), ...(configuredHarness && modeId ? { modeId } : {}), ...(worker === 'codex_app_server' && effort.trim() ? { effort: effort.trim() } : {}) });
  const formCurrent = () => nativeActive() && shownVersion === formVersion.current;
  const changed = (change: () => void) => { if (locked || waiting.current) return; formVersion.current++; setProbe(null); setReview(null); setWriteIntent(false); setDraftError(''); change(); };
  const edited = (change: () => void) => { if (locked || waiting.current) return; formVersion.current++; setReview(null); change(); };
  const prepare = async (mode: 'ordinary' | 'baseline' | 'inputs' = 'ordinary') => {
    if (!formCurrent() || waiting.current) return;
    if (locked || !nativeActive() || !environmentFresh) return;
    setDraftError('');
    const input: Data = { profileId: profile.id, bindingHash: profile.bindingHash, environmentId, environmentBinding: environment?.bindingHash, worker, timeoutSeconds: Number(timeout) };
    try {
      if (gemini && (environment?.writeAllowed !== true || !writeIntent || !geminiReady)) throw new Error('请核对当前 Gemini 能力，并明确确认这次私人工作的写入意图。此后端不支持只读分析。');
      if (configuredHarness && (environment?.writeAllowed !== true || !writeIntent || !harnessReady)) throw new Error('请核对当前执行器，并确认使用这台电脑已配置的工具权限。');
      if (worker === 'command') input.argv = JSON.parse(argv);
      else Object.assign(input, { prompt: goal, readOnly: worker === 'codex_app_server' && readOnly }, model.trim() ? { model: model.trim() } : {}, worker === 'codex_app_server' && effort.trim() ? { effort: effort.trim() } : {}, worker === 'codex_app_server' && model.trim() && probe?.modelOptions?.digest ? { modelOptionsDigest: probe.modelOptions.digest } : {});
      if (gemini) Object.assign(input, { backendId: geminiId, capabilityRevision: probe!.capabilityRevision, modeId: 'default', readOnly: false });
      if (configuredHarness) Object.assign(input, { harnessSelection: harnessSelection(probe, selectedWorker), externalExecutionRiskAcknowledged: true, readOnly: false, ...(modeId ? { modeId } : {}) });
      const prepared = await request('remote.reviewTask', input);
      if (!formCurrent()) return;
      if (mode === 'baseline') setBaseline(prepared); else if (mode === 'inputs') setInputRequest(prepared); else setReview(prepared);
    } catch (failure) { if (alive.current) setDraftError(failure instanceof Error ? failure.message : '请核对这次任务参数。'); }
  };
  const readEnvironments = () => {
    if (locked || !formCurrent() || waiting.current) return;
    const selected = ++formVersion.current;
    setReview(null); setProbe(null); setOptionCatalog(null); setWriteIntent(false); setEnvironmentFresh(false); setDraftError('');
    void request('remote.environments', binding(profile)).then(value => { if (formVersion.current === selected && nativeActive()) { setEnvironments(list(value.environments)); setEnvironmentFresh(true); } }).catch(() => {});
  };
  const probeWorker = () => {
    if (locked || !formCurrent() || waiting.current || !selectedWorker || !environmentFresh) return;
    const selected = ++formVersion.current;
    setProbe(null); setReview(null);
    void request('remote.probeWorker', target()).then(value => { if (formVersion.current === selected && nativeActive()) { setProbe(value); setOptionCatalog(value); } }).catch(() => {});
  };
  const confirm = () => {
    if (!formCurrent() || waiting.current) return;
    if (!review || locked || !nativeActive() || pendingConfirm.current || !environmentFresh || gemini && (!writeIntent || !geminiReady) || configuredHarness && (!writeIntent || !harnessReady)) return;
    const captured = review; pendingConfirm.current = captured; draft.current = { ...draft.current, operation: captured, uncertain: false }; setOperation(captured); setReview(null); setUncertain(false); setReceipt(null);
    void request('remote.confirmTask', { reviewHandle: captured.reviewHandle }).then(value => {
      if (pendingConfirm.current !== captured) return;
      setReceipt(value); setUncertain(value.status === 'unknown');
    }).catch(() => { if (alive.current && draft.current.operation?.operationId === captured.operationId) { draft.current = { ...draft.current, uncertain: true }; setUncertain(true); } }).finally(() => { if (pendingConfirm.current === captured) pendingConfirm.current = null; });
  };
  return <div className="pw-remote-work">
    <header className="pw-connection-work-title"><h3>{operation ? '这次电脑工作' : '开始新工作'}</h3><p>{operation ? '结果与后续操作都保留在同一项原任务中。' : '先选择实际环境，再说明这次要完成的事情。'}</p></header>
    {unresolved.map(item => <div className="pw-selected-object" key={item.operationId}><p>此连接有原提交待核对。</p><Button variant="secondary" disabled={busy || !active} onClick={() => { void request('remote.reconcileOperation', { profileId: profile.id, operationId: item.operationId }).then(setReceipt).catch(() => {}); }}>只读核对原提交</Button><Button variant="secondary" onClick={() => { void run('tasks.open', { id: item.operationId }).catch(() => {}); }}>打开原任务</Button></div>)}
    <div hidden={!!operation} className="pw-remote-composer">
    <section className="pw-connection-environment"><div className="pw-connection-section-toolbar"><h3>工作环境</h3><Button variant="ghost" disabled={locked || !profile.hasToken} onClick={readEnvironments}>读取当前工作环境</Button></div>
    {!environments && <p className="pw-field-hint">{profile.hasToken ? '读取这台电脑已提供的目录与执行器，不会自动创建或安装。' : '请返回连接概览，在连接设置中补充令牌。'}</p>}
    {!environmentFresh && (environmentId || operation) && <p className="pw-field-hint">草稿与原操作保留；开始另一项工作前，需要重新读取环境与核对执行器。</p>}
    {environments?.length === 0 && <p>未取得可用工作环境；不会自动创建电脑目录或安装执行器。</p>}
    {!!environments?.length && <SelectField label="工作环境" value={environmentId} isDisabled={locked} options={environments.map(item => ({ id: item.id, label: String(item.label || item.id) + ' · ' + String(item.kind || '') }))} onChange={value => changed(() => { setEnvironment(value); setWorker(''); setModel(''); setModeId(''); setOptionCatalog(null); })} />}
    {environment && <Facts values={[[ '实际工作区', environment.workspace ], ['允许写入', environment.writeAllowed === true ? '是' : '未启用' ]]} />}
    </section>
    {environment && <section className="pw-connection-task-composer"><SelectField label="执行器" value={worker} isDisabled={locked} options={[{ id: '', label: '选择这台电脑已配置的执行器' }, ...workers.map(item => ({ id: item.id, label: harnessWorkerLabel(item) + (item.available === false ? ' · 当前不可用' : '') }))]} onChange={value => changed(() => { setWorker(value); setModel(''); setModeId(''); setEffort(''); setReadOnly(true); setOptionCatalog(null); })} />
      {!workers.length && <p role="status">此环境没有返回执行器，请在电脑端核对配置后刷新。</p>}
      {worker && !selectedWorker && <p role="status">原执行器不在本次环境目录中；请选择当前配置的执行器。</p>}
      {selectedWorker && <p className="pw-field-hint">执行位置：{profile.name}。{harnessWorkerLabel(selectedWorker)}调用自己的模型与工具，模型选项在下方核对。</p>}
      {worker === 'command' ? <TextAreaField label="明确参数 JSON 数组" value={argv} isDisabled={locked} onChange={value => edited(() => setArgv(value))} hint="只执行电脑 allowlist 中的参数数组，不将拼接文本当 Shell。" /> : <>
        <TextAreaField label="这次要完成什么" value={goal} isDisabled={locked} onChange={value => edited(() => setGoal(value))} />
        {worker === 'codex_app_server' && <CheckboxField isSelected={readOnly} isDisabled={locked} onChange={value => edited(() => setReadOnly(value))}>只读分析，不授权修改文件</CheckboxField>}
        {worker === 'pi' && <p className="pw-field-hint">仅无工具对话，不提供项目读写、命令、扩展或 MCP；执行器配置可能重试或压缩，费用与内部调用次数尚未核实。</p>}
        {worker === 'claude' && <p className="pw-field-hint">使用执行端的受限工具配置；此处不声明操作系统只读沙箱。原任务仍需实际验证账号和推理。</p>}
        {gemini && <><p className="pw-field-hint">Gemini ACP 仅支持私人可写工作，不支持只读分析。使用固定版本与默认审批模式，实际工具权限仍在原任务逐次审阅；不会自动安装或登录。</p>
          {environment.writeAllowed !== true && <p role="alert">所选环境尚未允许写入，不能创建 Gemini 工作。可核对能力，或选择支持只读的执行器。</p>}
          <CheckboxField isSelected={writeIntent} isDisabled={locked || environment.writeAllowed !== true} onChange={value => edited(() => setWriteIntent(value))}>我明确要在此环境开展可写工作，已知 Gemini 不提供只读隔离</CheckboxField>
        </>}
        {configuredHarness && <>
          <p className="pw-field-hint">此执行器使用电脑端已有的工具和权限配置。工作目录不是操作系统沙箱；运行中补充、恢复与工具审批以本次返回的能力为准。</p>
          {environment.writeAllowed !== true && <p role="alert">此环境尚未允许可写工作，不能启动使用电脑端权限的执行器。</p>}
          <CheckboxField isSelected={writeIntent} isDisabled={locked || environment.writeAllowed !== true} onChange={value => edited(() => setWriteIntent(value))}>我确认使用这台电脑已配置的工具权限，了解可能修改文件，内部模型费用不能硬限</CheckboxField>
        </>}
        <Accordion><AccordionItem id="remote-task-options" title="模型与运行选项" description={model.trim() || '使用执行端默认模型'}>
          {configuredHarness && modelOptions.length > 0 ? <SelectField label="请求模型" value={model} isDisabled={locked}
            options={[{ id: '', label: '使用执行器默认模型' }, ...(model && !modelOptions.some(item => item.id === model) ? [{ id: model, label: model + ' · 待核对' }] : []), ...modelOptions]}
            onChange={value => changed(() => setModel(value))} hint="目录来自执行器。更换模型后重新核对实际会话。" />
            : <TextField label="请求模型 ID（可留空）" value={model} isDisabled={locked} onChange={value => changed(() => { setModel(value); setEffort(''); })} hint={configuredHarness ? '尚未取得模型目录；填写后须让执行器实际核对，留空使用它的默认模型。' : gemini ? '留空使用执行端默认模型；本次探测不证明该模型已登录或已完成推理。' : undefined} />}
          {configuredHarness && (modeOptions.length > 0 || modeId) && <SelectField label="执行模式" value={modeId} isDisabled={locked}
            options={[{ id: '', label: '使用执行器默认模式' }, ...(modeId && !modeOptions.some(item => item.id === modeId) ? [{ id: modeId, label: modeId + ' · 待核对' }] : []), ...modeOptions]}
            onChange={value => changed(() => setModeId(value))} hint="只使用执行端返回的模式；选择后重新核对。" />}
          {worker === 'codex_app_server' && model.trim() && <TextField label="请求推理档位（可留空）" value={effort} isDisabled={locked} onChange={value => changed(() => setEffort(value))} hint="须来自实际 Codex 模型目录；选择变化后重新核对。" />}
          <TextField label="任务时限（10–3600 秒）" value={timeout} isDisabled={locked} inputMode="numeric" onChange={value => edited(() => setTimeoutValue(value.replace(/\D/g, '').slice(0, 4)))} />
        </AccordionItem></Accordion>
        <Button variant="secondary" disabled={locked || !selectedWorker || !environmentFresh || !supported('remote.probeWorker')} onClick={probeWorker}>核对执行器与模型能力</Button>
        {probe && (gemini ? <GeminiEvidence probe={probe} /> : configuredHarness ? <HarnessEvidence probe={probe} /> : <><p>{probe.message || (probe.available ? '执行器已返回可用声明，尚未发起模型任务。' : '尚未核实可用。')}</p>{list(probe.models).length > 0 && <details><summary>已返回的模型目录</summary>{list(probe.models).map(item => <Button key={item.id} variant="secondary" onClick={() => changed(() => { setModel(item.id); setEffort(''); })}>{item.name || item.id}</Button>)}</details>}</>)}
      </>}{worker === 'command' && <TextField label="任务时限（10–3600 秒）" value={timeout} isDisabled={locked} inputMode="numeric" onChange={value => edited(() => setTimeoutValue(value.replace(/\D/g, '').slice(0, 4)))} />}
      <Button disabled={locked || !environmentFresh || !supported('remote.reviewTask') || unresolved.length > 0 || !workers.some(item => item.id === worker && item.available !== false) || (worker !== 'command' && (!goal.trim() || probe?.available !== true)) || gemini && (!geminiReady || !writeIntent || environment.writeAllowed !== true) || configuredHarness && (!harnessReady || !writeIntent || environment.writeAllowed !== true) || Number(timeout) < 10 || Number(timeout) > 3600} onClick={() => { void prepare(); }}>审阅这次新工作</Button>
      {['codex_app_server', 'claude'].includes(worker) && environment.projectHistoryCapabilities?.supported === true && supported('remote.project.open') && <Button variant="secondary" disabled={locked || !environmentFresh || unresolved.length > 0 || !goal.trim() || probe?.available !== true || !workers.some(item => item.id === worker && item.available !== false) || Number(timeout) < 10 || Number(timeout) > 3600} onClick={() => void prepare('baseline')}>先保留项目基线，再创建这次工作</Button>}
      {['codex_app_server', 'claude'].includes(worker) && environment?.privateInputCapabilities?.supported === true && supported('remote.inputs.open') && <Button variant="secondary" disabled={locked || !environmentFresh || unresolved.length > 0 || !goal.trim() || goal.length > 8000 || probe?.available !== true || !workers.some(item => item.id === worker && item.available !== false) || Number(timeout) < 10 || Number(timeout) > 3600} onClick={() => void prepare('inputs')}>选择私人原件，再审阅这次工作</Button>}
    </section>}
    </div>
    {(error || draftError) && <p role="alert">{error || draftError}</p>}
    {operation && <div className="pw-selected-object"><p role="status">{uncertain ? '这次原提交的结果待核对。草稿与原操作已保留，不会再次确认或重发。' : '已保留这次原操作。离开页面不会重提或停止电脑任务。'}</p><Facts values={[[ '原操作', operation.operationId ]]} />
      <Accordion><AccordionItem id="original-remote-work" title="保留的原工作草稿"><Facts values={[[ '原环境', operation.environmentId ], ['原执行器', operation.worker ], ['请求模型', operation.model || '执行端默认' ]]} /><pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{operation.prompt || JSON.stringify(operation.argv)}</pre></AccordionItem></Accordion>
      <Button variant="secondary" disabled={busy || !active} onClick={() => { void request('remote.reconcileOperation', { profileId: profile.id, operationId: operation.operationId }).then(setReceipt).catch(() => {}); }}>查询原操作</Button><Button variant="secondary" onClick={() => { void run('tasks.open', { id: operation.operationId }).catch(() => {}); }}>打开原任务 · 审批、成果与继续</Button>{nativeTask && ['completed', 'failed', 'cancelled'].includes(nativeTask.status) && list(data.operations).some(item => item.operationId === operation.operationId && ['settled', 'rejected'].includes(item.status)) && <Button variant="secondary" disabled={busy || !active} onClick={() => { setOperation(null); setUncertain(false); setReceipt(null); setGoal(''); setProbe(null); setWriteIntent(false); setEnvironmentFresh(false); }}>已核对原任务，新建另一项工作</Button>}</div>}
    <Receipt value={receipt} run={run} />
    {baseline && <RemoteProjectBaseline prepared={baseline} visible={visible && active} onClose={() => setBaseline(null)} onRegistered={captured => { draft.current = { ...draft.current, operation: captured, uncertain: true }; setOperation(captured); setUncertain(true); setReceipt(null); }} />}
    {inputRequest && <RemoteInputFlow prepared={inputRequest} visible={visible && active} close={() => setInputRequest(null)} onRegistered={captured => { draft.current = { ...draft.current, operation: captured, uncertain: true }; setOperation(captured); setUncertain(true); setReceipt(null); }} />}
    <SettingsDialog isOpen={visible && !!review} title="确认在所选电脑创建工作" onClose={() => { if (!waiting.current) dismissReview(); }}>
      <Facts values={[[ '电脑', profile.name ], ['地址', profile.baseUrl ], ['工作区', environment?.workspace ], ['执行器', review?.worker === 'gemini_acp' ? 'Gemini CLI 0.66.0 · ACP 1' : harnessWorkerLabel(selectedWorker, review?.worker) ], ['请求模型', review?.model || '执行端默认' ], ['推理强度', review?.effort || null ], ['执行模式', review?.worker === 'gemini_acp' ? '默认审批 · 仅本次私人可写工作' : review?.modeId || null ], ['时限', review?.timeoutSeconds ]]} />
      <pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{review?.prompt || JSON.stringify(review?.argv)}</pre><p>{review?.worker === 'pi' ? '仅进行无工具对话。' : review?.readOnly ? '本次为 Codex 只读分析。' : '本次可能修改所选工作区；工具审批在原任务逐次核对。'}</p>
      {review?.worker === 'gemini_acp' && <p>已明确选择可写工作；登录、实际模型与会话模式仍须在原任务核对。不支持只读隔离，不自动登录或更换后端。</p>}
      {configuredHarness && <p>沿本次核对的执行器配置与能力启动。使用电脑端已有权限，工作目录不是操作系统沙箱；实际模型、会话和工具结果保留在原任务。</p>}
      <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={dismissReview}>返回修改</Button><Button disabled={locked || !environmentFresh || gemini && (!writeIntent || !geminiReady) || configuredHarness && (!writeIntent || !harnessReady)} onClick={confirm}>确认创建一次</Button></div>
    </SettingsDialog>
  </div>;
}
type SshDraft = { mode: string; goal: string; client: string | null; command: string; directory: string; files: Data | null; file: Data | null; content: string; fileConflict: boolean; uncertain: boolean; receipt: Data | null; agentReceipt: Data | null };
const sshDrafts = new Map<string, SshDraft>();
function SshWork({ profile, state, data, run, supported, back, visible }: { profile: Data; state: Data; data: Data; run: Run; supported: (action: string) => boolean; back: { current: (() => boolean) | null }; visible: boolean }) {
  const visibleNow = useRef(visible); visibleNow.current = visible;
  const key = profile.id + ':' + profile.bindingHash, initial = useRef(sshDrafts.get(key));
  const journey = useDataSettingsJourney(initial.current?.mode || 'list'), mode = journey.view;
  const [goal, setGoal] = useState(initial.current?.goal || ''), [client, setClient] = useState<string | null>(initial.current?.client || null), [command, setCommand] = useState(initial.current?.command || '');
  const [directory, setDirectory] = useState(initial.current?.directory || ''), [files, setFiles] = useState<Data | null>(initial.current?.files || null), [file, setFile] = useState<Data | null>(initial.current?.file || null), [content, setContent] = useState(initial.current?.content || '');
  const [agentReceipt, setAgentReceipt] = useState<Data | null>(initial.current?.agentReceipt || null);
  const [review, setReview] = useState<Data | null>(null), [receipt, setReceipt] = useState<Data | null>(initial.current?.receipt || null), [uncertain, setUncertain] = useState(initial.current?.uncertain || false), [cooperative, setCooperative] = useState(false);
  const [terminal, setTerminal] = useState<Data | null>(null), [doctor, setDoctor] = useState<Data | null>(null), { request, busy, error } = useRequest(run, state, visible);
  const [fileNeedsRead, setFileNeedsRead] = useState(!!initial.current?.file), [fileConflict, setFileConflict] = useState(initial.current?.fileConflict || false);
  const fileReturn = useRef({ scroll: 0, path: '' }), previousFile = useRef(text(initial.current?.file?.path));
  const pendingConfirm = useRef(false), currentContent = useRef(content); currentContent.current = content;
  const draft = useRef<SshDraft>({ mode, goal, client, command, directory, files, file, content, fileConflict, uncertain, receipt, agentReceipt });
  draft.current = { mode, goal, client, command, directory, files, file, content, fileConflict, uncertain, receipt, agentReceipt };
  useEffect(() => () => {
    sshDrafts.delete(key); sshDrafts.set(key, { ...draft.current, uncertain: draft.current.uncertain || pendingConfirm.current });
    while (sshDrafts.size > 8) sshDrafts.delete(sshDrafts.keys().next().value!);
  }, [key]);
  useLayoutEffect(() => {
    const path = text(file?.path);
    if (path === previousFile.current) return;
    previousFile.current = path;
    const scroller = journey.root.current?.closest('.pw-secondary-scroll');
    if (scroller) scroller.scrollTop = path ? 0 : fileReturn.current.scroll;
    const target = path ? journey.root.current?.querySelector<HTMLElement>('[data-file-heading]') : Array.from(journey.root.current?.querySelectorAll<HTMLElement>('[data-operation-entry]') || []).find(row => row.dataset.operationEntry === fileReturn.current.path);
    target?.focus({ preventScroll: true });
  }, [file?.path]);
  const closeFile = () => { if (content === file?.content) setFile(null); else setReview({ operation: 'discardFile' }); };
  useEffect(() => {
    back.current = () => {
      if (busy) return true;
      if (terminal) { setTerminal(null); return true; }
      if (review) { setReview(null); return true; }
      if (mode === 'files' && file) { closeFile(); return true; }
      return journey.back();
    };
    return () => { back.current = null; };
  }, [busy, terminal, review, mode, file, content, back]);
  useEffect(() => { if (!visible) { setReview(null); setCooperative(false); } }, [visible]);
  const resume = Number(state.webWorkspaceResumeRevision || 0), previousResume = useRef(resume);
  useEffect(() => {
    if (previousResume.current === resume) return; previousResume.current = resume;
    let current = true; const originalFile = file;
    setReview(null); setCooperative(false); setDoctor(null);
    if (originalFile) setFileNeedsRead(true);
    void (async () => {
      if (originalFile) try {
        const value = await request('ssh.readFile', { ...binding(profile), path: originalFile.path });
        if (current) { const restored = resumeSshFile(originalFile, currentContent.current, value, profile.id, fileConflict); setFile(restored.file); setContent(restored.content); setFileConflict(restored.conflict); setFileNeedsRead(false); }
      } catch {}
      if (current && files) try { const value = await request('ssh.listFiles', { ...binding(profile), path: files.path || '' }); if (current) setFiles(value); } catch {}
    })();
    return () => { current = false; };
  }, [resume]);
  const trusted = profile.hostKeyTrusted === true && profile.credentialsConfigured === true;
  const prepare = (operation: string, arguments_: Data) => { if (!visibleNow.current || busy) return; void request('ssh.reviewOperation', { command: operation, arguments: { ...binding(profile), ...arguments_ } }).then(value => { setCooperative(false); setReview(value); }).catch(() => {}); };
  const selectedTasks = list(state.tasks).filter(task => object(task.raw).feature === 'ssh' && object(object(task.raw).externalRefs).profileId === profile.id);
  const unknown = uncertain || selectedTasks.some(task => ['reconciling', 'unknown'].includes(task.status));
  const readDirectory = (path: string) => { void request('ssh.listFiles', { ...binding(profile), path }).then(value => { setFiles(value); setDirectory(text(value.path)); }).catch(() => {}); };
  const readFile = (path: string) => { if (!file) fileReturn.current = { scroll: journey.root.current?.closest('.pw-secondary-scroll')?.scrollTop || 0, path }; void request('ssh.readFile', { ...binding(profile), path }).then(value => { setReceipt(value); if (value.status === 'completed' && typeof value.content === 'string' && /^sha256:[a-f0-9]{64}$/.test(text(value.version))) { setFile(value); setContent(value.content); setFileNeedsRead(false); setFileConflict(false); } }).catch(() => {}); };
  const verifyFile = () => { if (!file) return; const original = file; void request('ssh.readFile', { ...binding(profile), path: original.path }).then(value => { const restored = resumeSshFile(original, currentContent.current, value, profile.id, fileConflict); setFile(restored.file); setContent(restored.content); setFileConflict(restored.conflict); setFileNeedsRead(false); }).catch(() => {}); };
  const tools = [
    { id: 'agent', title: '让 Agent 办事', detail: '说明目标，在原任务中审阅与继续', icon: IconMonitor },
    { id: 'files', title: '工作区文件', detail: '浏览目录、读取文本，审阅后保存修改', icon: IconFolder },
    { id: 'command', title: '运行一条命令', detail: '核对准确命令与工作目录后执行', icon: IconTerminal },
    { id: 'terminal', title: '交互式终端', detail: '连接远端 Shell，保留原终端与回执', icon: IconServer },
    { id: 'doctor', title: '环境诊断', detail: '只读检查系统、目录与工具状态', icon: IconTool },
  ];
  const tool = tools.find(item => item.id === mode);
  return <div className="pw-ssh-work" ref={journey.root} tabIndex={-1} data-ssh-view={mode}>
    {!trusted && <div className="pw-connection-attention"><IconShieldCheck size={18} aria-hidden /><p>先返回连接概览，核对主机指纹并保存凭据，才可使用此连接。</p></div>}
    <div className="pw-connection-tool-directory" hidden={mode !== 'list'}>{[{ title: '任务与文件', ids: ['agent', 'files'] }, { title: '命令与终端', ids: ['command', 'terminal'] }, { title: '检查环境', ids: ['doctor'] }].map(section => <section className="pw-connection-directory-group" key={section.title} aria-label={section.title}><header><h3>{section.title}</h3></header><div className="pw-connection-list">{tools.filter(item => section.ids.includes(item.id)).map(item => <ConnectionRow key={item.id} title={item.title} detail={item.detail} icon={item.icon} disabled={busy} entry={item.id} onOpen={() => journey.open(item.id)} />)}</div></section>)}</div>
    {tool && <header className="pw-connection-work-title" tabIndex={-1} data-operation-heading><h3>{tool.title}</h3><p>{tool.detail}</p></header>}
    {mode === 'agent' && <section className="pw-connection-task-composer"><TextAreaField label="这次要完成什么" value={goal} isDisabled={busy || !!client} onChange={value => { if (!client) setGoal(value); }} hint="目标绑定当前主机与目录，写入授权仍在原任务核对。" /><Button disabled={!trusted || busy || !goal.trim() || goal.length > 12000 || !!agentReceipt?.taskId} onClick={() => { const id = client || crypto.randomUUID(); draft.current = { ...draft.current, client: id }; setClient(id); void request('ssh.startTask', { profileId: profile.id, bindingHash: profile.bindingHash, clientMessageId: id, goal }).then(value => { setAgentReceipt(value); setReceipt(value); }).catch(() => {}); }}>{client ? '核对同一输入回执' : '开始这次 SSH 任务'}</Button>{agentReceipt?.taskId && <Button variant="ghost" onClick={() => { setClient(null); setAgentReceipt(null); setReceipt(null); setGoal(''); }}>新建另一项工作</Button>}</section>}
    {mode === 'command' && <section className="pw-connection-task-composer"><TextAreaField label="POSIX 命令" value={command} isDisabled={busy} onChange={setCommand} hint="从所选工作目录执行，使用此账号的远端权限；工作目录不是沙箱。" /><Button disabled={!trusted || busy || unknown || !command.trim() || command.length > 12000} onClick={() => prepare('exec', { command, timeoutSeconds: 60 })}>核对并执行这条命令</Button></section>}
    {mode === 'files' && <section className="pw-ssh-files">
      {!file && <><div className="pw-connection-directory-bar"><TextField label="相对目录，留空为工作区" value={directory} isDisabled={busy} onChange={setDirectory} /><Button variant="secondary" disabled={!trusted || busy} onClick={() => readDirectory(directory.trim())}>读取目录</Button></div>
        {files ? <><div className="pw-connection-file-location"><IconFolder size={17} aria-hidden /><span>{files.path || '工作区根目录'}</span>{files.path && <Button variant="ghost" iconOnly leadingIcon={IconArrowUp} aria-label="返回上级目录" disabled={busy} onClick={() => readDirectory(text(files.path).split('/').slice(0, -1).join('/'))} />}</div><p className="pw-field-hint">已读取的目录内容，不跟随符号链接。</p><div className="pw-connection-file-list">{list(files.files).length === 0 && <p className="pw-secondary-empty">这个目录没有可显示的文件。</p>}{list(files.files).map(item => <ConnectionRow key={item.name} entry={safeChild(files.path || '', item.name) || undefined} title={item.name} detail={item.symlink ? '符号链接 · 不跟随' : item.directory ? '目录' : `${item.bytes} 字节 · 文本读取`} icon={item.directory ? IconFolder : IconFileText} disabled={busy || item.symlink || !safeChild(files.path || '', item.name)} onOpen={() => { const path = safeChild(files.path || '', item.name); if (!path) return; if (item.directory) readDirectory(path); else readFile(path); }} />)}</div></> : <p className="pw-secondary-empty">读取目录后，在这里查看文件与子目录。</p>}
      </>}
      {file && <div className="pw-connection-file-editor"><div className="pw-connection-file-location" tabIndex={-1} data-file-heading><IconFileText size={18} aria-hidden /><span>{file.path}</span><small>{content === file.content ? '读取版本' : '未保存修改'}</small></div><TextAreaField label="原文件内容" value={content} isDisabled={busy} onChange={setContent} /><p className="pw-field-hint">按刚读取的文件版本校验。另一写入者仍可能在最后检查后改变文件。</p>{fileNeedsRead && <div className="pw-connection-attention"><p role="status">尚未重新读到原文件，草稿保留，先核对原保存版本。</p><Button variant="secondary" disabled={busy} onClick={verifyFile}>核对原文件，保留草稿</Button></div>}{fileConflict && <p role="alert">离开期间原文件已变化，未保存内容保留，没有自动合并或覆盖。请保留需要的草稿，再关闭文件并核对当前版本。</p>}<div className="pw-form-actions"><Button disabled={busy || unknown || fileNeedsRead || fileConflict || content === file.content} onClick={() => prepare('writeFile', { path: file.path, content, expectedVersion: file.version, acceptCooperativeWrite: true })}>审阅本次文件修改</Button><Button variant="ghost" disabled={busy || content !== file.content} onClick={() => readFile(file.path)}>重新读取已保存版本</Button><Button variant="ghost" disabled={busy} onClick={closeFile}>关闭文件</Button></div></div>}
    </section>}
    {mode === 'terminal' && <section className="pw-connection-terminal-entry"><p>交互式 Shell 使用账号的实际远端权限。收起不会关闭连接；关闭连接也不保证远端进程终止。</p><Button disabled={!trusted || busy || unknown} onClick={() => prepare('openPty', { cols: 80, rows: 24, timeoutSeconds: 600, allowInteractiveShell: true })}>核对并打开终端</Button><div className="pw-connection-list">{list(data.terminals).filter(item => item.profileId === profile.id).map(item => <ConnectionRow key={item.terminalId} title="原终端" detail={status(item.status)} icon={IconTerminal} onOpen={() => setTerminal({ ...item, hostTaskId: item.hostTaskId || item.taskId })} />)}</div></section>}
    {mode === 'doctor' && <section className="pw-connection-diagnostics"><p>只读诊断原连接的实际环境。详细报告仅在当前私人页面展示。</p><Button disabled={!trusted || busy || !supported('ssh.doctor')} onClick={() => { void request('ssh.doctor', binding(profile)).then(setDoctor).catch(() => {}); }}>开始一次只读诊断</Button>{doctor && <><Receipt value={doctor} run={run} /><Facts values={[[ '系统', doctor.os || object(doctor.doctor).os ], ['架构', doctor.architecture || object(doctor.doctor).architecture ], ['工作区', doctor.workspace || profile.workspace ]]} />{Object.entries(object(object(doctor.doctor).tools)).map(([tool, value]) => <article className="pw-source-record" key={tool}><header><h3>{tool}</h3><span>{status(object(value).status)}</span></header><p>{object(value).version || object(value).message}</p></article>)}</>}</section>}
    {error && <p role="alert">{error}</p>}{unknown && <div className="pw-connection-attention"><p>有原操作结果尚待核对，当前不会重放命令、写入或新建终端。</p></div>}
    <Receipt value={receipt} run={run} />
    {selectedTasks.filter(task => ['running', 'reconciling', 'unknown', 'awaiting_approval'].includes(task.status)).map(task => <ConnectionRow key={task.id} title={object(task.raw).command || 'SSH 原任务'} detail={status(task.status) + ' · 打开原任务核对'} icon={IconHistory} onOpen={() => { void run('tasks.open', { id: task.id }).catch(() => {}); }} />)}
    <SettingsDialog isOpen={visible && !!review} title={review?.operation === 'discardFile' ? '放弃未保存的文件修改？' : '核对这次 SSH 操作'} onClose={() => { if (!busy) setReview(null); }}><Facts values={[[ '连接', profile.name ], ['主机', `${profile.username}@${profile.host}:${profile.port}` ], ['工作区', profile.workspace ], ['文件路径', review?.operation === 'writeFile' ? review.path : null ], ['原版本', review?.operation === 'writeFile' ? review.expectedVersion : null ]]} /><pre className="pw-connection-code">{review?.operation === 'exec' ? review.command : review?.operation === 'writeFile' ? review.content : ''}</pre>
      {review?.operation === 'openPty' && <p>将打开拥有此远端账号权限的交互式 Shell，时限 10 分钟。工作目录不是权限沙箱。</p>}
      {review?.operation === 'writeFile' && <><p>只按原路径与版本写入以上内容。合作式校验不能阻止最后检查后的外部写入竞争。</p><CheckboxField isSelected={cooperative} onChange={setCooperative}>已核对修改，接受本次合作式版本校验</CheckboxField></>}
      <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => setReview(null)}>取消</Button><Button disabled={busy || review?.operation === 'writeFile' && !cooperative} onClick={() => {
        if (!review || !visibleNow.current || busy) return; const captured = review; if (captured.operation === 'discardFile') { setFile(null); setReview(null); return; }
        pendingConfirm.current = true; setReview(null); void request('ssh.confirmOperation', { reviewHandle: captured.reviewHandle }).then(value => { setReceipt(value); setUncertain(value.status === 'unknown'); if (captured.operation === 'writeFile' && value.contentVerifiedForReview === true) { setFile({ ...value, content: captured.content }); setContent(captured.content); } if (captured.operation === 'openPty' && value.ptyConfirmed && value.terminalId) setTerminal({ ...value, hostTaskId: value.hostTaskId || value.taskId }); }).catch(() => setUncertain(true)).finally(() => { pendingConfirm.current = false; });
      }}>{review?.operation === 'discardFile' ? '放弃这些修改' : '确认执行一次'}</Button></div></SettingsDialog>
    {terminal && visible && <SshTerminal key={terminal.terminalId} original={terminal} state={state} run={run} close={() => setTerminal(null)} />}
  </div>;
}

function safeChild(directory: string, name: string) { if (!name || name === '.' || name === '..' || /[\/\\\x00-\x1f\x7f]/.test(name) || directory.startsWith('/') || directory.includes('\\') || directory.split('/').some(part => part === '.' || part === '..')) return null; return directory ? directory + '/' + name : name; }
function SshTerminal({ original, state, run, close }: { original: Data; state: Data; run: Run; close: () => void }) {
  const [frame, setFrame] = useState<any>(null), [input, setInput] = useState(''), [automatic, setAutomatic] = useState(true), [uncertain, setUncertain] = useState(false), [closing, setClosing] = useState(false), [receipt, setReceipt] = useState<Data | null>(null), { request, busy, error } = useRequest(run, state);
  const resume = Number(state.webWorkspaceResumeRevision || 0), taskOpen = !!state.webWorkspaceTaskOpen, previousResume = useRef(resume);
  const target = { terminalId: original.terminalId, hostTaskId: original.hostTaskId, runId: original.runId };
  const task = list(state.tasks).find(item => item.id === original.hostTaskId), operations = list(object(task?.raw).operations);
  const blocked = uncertain || task && ['unknown', 'reconciling'].includes(task.status) || operations.some(operation => ['unknown', 'prepared', 'effect_started'].includes(operation.state));
  const read = () => request('ssh.readPty', target).then(value => { if (value.frame) setFrame(value.frame); if (value.status !== 'running') setAutomatic(false); setReceipt(value); });
  const latest = useRef({ busy, read }); latest.current = { busy, read };
  useEffect(() => { if (previousResume.current === resume) return; previousResume.current = resume; setClosing(false); setFrame(null); setReceipt(null); if (!automatic && !taskOpen) void read().catch(() => {}); }, [resume]);
  useEffect(() => { let active = true, timer: ReturnType<typeof setTimeout>; const tick = async () => { if (!active) return; if (document.visibilityState === 'visible' && !latest.current.busy) await latest.current.read().catch(() => {}); if (active) timer = setTimeout(tick, 1200); }; if (automatic && !taskOpen) void tick(); return () => { active = false; clearTimeout(timer); }; }, [automatic, taskOpen, resume]);
  const send = (value: string) => { const bytes = new TextEncoder().encode(value); if (bytes.length > 65536) return; setInput(''); const encoded = btoa(Array.from(bytes, byte => String.fromCharCode(byte)).join('')); void request('ssh.writePty', { ...target, dataBase64: encoded }).then(value => { setReceipt(value); if (value.status === 'unknown' || value.accepted !== true) setUncertain(true); }).catch(() => setUncertain(true)); };
  return <SettingsDialog isOpen title="原 SSH 终端" onClose={close}><TerminalSurface frame={frame} /><CheckboxField isSelected={automatic} onChange={setAutomatic}>在前台持续读取输出</CheckboxField><div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => { void read().catch(() => {}); }}>读取原输出</Button><Button variant="secondary" onClick={close}>收起</Button><Button variant="secondary" disabled={busy} onClick={() => setClosing(true)}>关闭本机连接</Button></div>
    <TextAreaField label="发送到原终端的文本" value={input} onChange={setInput} /><div className="pw-form-actions"><Button disabled={busy || blocked || !input || receipt?.status !== 'running' || new TextEncoder().encode(input + '\n').length > 65536} onClick={() => send(input + '\n')}>发送文本与换行</Button><Button variant="secondary" disabled={busy || blocked || receipt?.status !== 'running'} onClick={() => send('\x03')}>Ctrl+C</Button></div>{error && <p role="alert">{error}</p>}{blocked && <p>原输入尚待核对，没有重放。请打开原任务处理。</p>}<Receipt value={receipt} run={run} />
    <SettingsDialog isOpen={closing} title="关闭这项原 SSH 连接？" onClose={() => setClosing(false)}><p>关闭本机 PTY 连接，不保证远端进程已终止，不自动重连或重放输入。</p><div className="pw-form-actions"><Button variant="secondary" onClick={() => setClosing(false)}>取消</Button><Button disabled={busy} onClick={() => { setAutomatic(false); setClosing(false); setInput(''); void request('ssh.closePty', target).then(setReceipt).catch(() => {}); }}>关闭本机连接</Button></div></SettingsDialog>
  </SettingsDialog>;
}
