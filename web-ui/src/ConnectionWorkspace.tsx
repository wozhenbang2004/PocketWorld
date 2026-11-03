import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Button, CheckboxField, SelectField, SettingsDialog, TextAreaField, TextField } from './forms-library';
import { connectionProfilePayload, wirelessPort } from './forms-library/host-contracts';
import TerminalSurface from './TerminalSurface';

type Data = Record<string, any>;
type Run = (action: string, args?: Data) => Promise<any>;
type Props = { domain: 'remote' | 'ssh'; state: Data; data: Data; supported: (action: string) => boolean; run: Run; registerTransientBack: (handler: (() => boolean) | null) => void };
const object = (value: any): Data => value && typeof value === 'object' && !Array.isArray(value) ? value : {};
const list = (value: any): Data[] => Array.isArray(value) ? value.filter(item => item && typeof item === 'object' && !Array.isArray(item)) : [];
const text = (value: any) => typeof value === 'string' ? value : value == null ? '' : String(value);
const status = (value: any) => ({ completed: '已完成', failed: '未完成', running: '执行中', queued: '等待执行', unknown: '待核对', awaiting_approval: '等待授权', stopped: '已停止', cancelled: '已取消', stopping: '正在停止' } as Data)[text(value)] || text(value);
const originalJob = (job: Data) => ({ profileId: job.profileId, jobId: job.id, jobOperationId: job.operationId, serviceBase: job.serviceBase, _profileBinding: job.profileBinding });
const binding = (profile: Data) => ({ profileId: profile.id, _profileBinding: profile.bindingHash });
const section = (title: string, children: ReactNode, description?: string) => <section className="pw-settings-section"><header className="pw-section-heading"><h2>{title}</h2>{description && <p>{description}</p>}</header>{children}</section>;
function Facts({ values }: { values: Array<[string, any]> }) { return <dl className="pw-facts">{values.filter(([, value]) => value !== undefined && value !== null && value !== '').map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{text(value)}</dd></div>)}</dl>; }
function Receipt({ value, run }: { value: Data | null; run: Run }) {
  if (!value) return null;
  const task = value.hostTaskId || value.taskId;
  return <div className="pw-selected-object"><Facts values={[[ '原操作状态', status(value.status) ], ['退出码', value.exitCode ]]} />
    {value.message && <p>{value.message}</p>}{value.status === 'unknown' && <p>请在原任务核对实际结果；没有自动重做或宣称远端已停止。</p>}
    {['stdout', 'stderr'].map(key => value[key] ? <details key={key}><summary>{key === 'stdout' ? '标准输出' : '错误输出'}</summary><pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{value[key]}</pre></details> : null)}
    {task && <Button variant="secondary" onClick={() => { void run('tasks.open', { id: task }).catch(() => {}); }}>打开原任务与回执</Button>}</div>;
}
function useRequest(run: Run) {
  const waiting = useRef(false), alive = useRef(true);
  const [busy, setBusy] = useState(false), [error, setError] = useState('');
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  const request = async (action: string, args: Data = {}) => {
    if (waiting.current) throw new Error('请等待原操作回执。');
    waiting.current = true; setBusy(true); setError('');
    try { const value = await run(action, args); if (!alive.current) throw new Error('原页面已关闭，请核对原操作。'); return object(value); }
    catch (failure) { if (alive.current) setError(failure instanceof Error ? failure.message : String(failure)); throw failure; }
    finally { waiting.current = false; if (alive.current) setBusy(false); }
  };
  return { request, busy, error, alive };
}

/** Product composition of the existing BoardUI forms and settings dialog. */
export default function ConnectionWorkspace({ domain, state, data, supported, run, registerTransientBack }: Props) {
  const profiles = list(data.profiles), [editing, setEditing] = useState<Data | null>(null), [selected, setSelected] = useState<Data | null>(null), [removing, setRemoving] = useState<Data | null>(null);
  const [probe, setProbe] = useState<Data | null>(null), [trust, setTrust] = useState<Data | null>(null), [checked, setChecked] = useState(false), [receipt, setReceipt] = useState<Data | null>(null);
  const { request, busy, error } = useRequest(run);
  useEffect(() => { registerTransientBack(editing || trust || removing ? () => { if (!busy) { setEditing(null); setTrust(null); setRemoving(null); } return true; } : null); return () => registerTransientBack(null); }, [editing, trust, removing, busy, registerTransientBack]);
  const stale = selected && !profiles.some(profile => profile.id === selected.id && profile.bindingHash === selected.bindingHash);
  const act = (action: string, args: Data, completed?: (value: Data) => void) => { void request(action, args).then(value => { setReceipt(value); completed?.(value); }).catch(() => {}); };
  const available = (action: string) => supported(domain + '.' + action) && !busy;
  return <>
    {section(domain === 'remote' ? 'Companion 环境' : 'SSH 主机', <>
      {profiles.length === 0 && <p className="pw-secondary-empty">尚未添加连接。保存后核对实际连接与能力。</p>}
      {profiles.map(profile => <article className="pw-source-record" key={profile.id}><header><h3>{profile.name}</h3></header><Facts values={[[ '地址', profile.baseUrl || `${profile.username}@${profile.host}:${profile.port}` ], ['工作区', profile.workspace ], ['凭据', profile.hasToken || profile.credentialsConfigured ? '已配置' : '未配置' ], ['公钥指纹', profile.hostFingerprint ]]} />
        <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => { setSelected({ ...profile }); setProbe(null); setReceipt(null); }}>使用此连接</Button>
          <Button variant="secondary" disabled={!available(domain === 'remote' ? 'probe' : 'probeHostKey')} onClick={() => act(domain === 'remote' ? 'remote.probe' : 'ssh.probeHostKey', binding(profile), value => setProbe({ ...value, originalProfile: { ...profile } }))}>{domain === 'remote' ? '探测能力' : '查看主机指纹'}</Button>
          {domain === 'remote' && <Button variant="secondary" disabled={!available('reconnect')} onClick={() => act('remote.reconnect', binding(profile))}>同步原任务</Button>}
          <Button variant="secondary" disabled={busy} onClick={() => setEditing({ ...profile })}>编辑连接</Button><Button variant="secondary" disabled={busy} onClick={() => setRemoving({ ...profile })}>移除</Button></div></article>)}
      <Button variant="secondary" disabled={busy} onClick={() => setEditing({})}>添加{domain === 'remote' ? '电脑环境' : 'SSH 主机'}</Button>
    </>, domain === 'remote' ? '使用原 Companion 服务、工作环境与任务回执。' : '先核对主机身份。普通 SSH 不保证断线后任务继续。')}
    {error && <p className="pw-secondary-error" role="alert">{error}</p>}
    {probe && domain === 'ssh' && section('本次实际主机指纹', <><Facts values={[[ '主机', probe.originalProfile?.name ], ['指纹', probe.fingerprint ], ['密钥类型', probe.keyType ]]} /><p>请与这台主机已有可信渠道提供的指纹核对。探测没有发送登录凭据。</p>
      {probe.changed ? <><p role="alert">主机密钥已变化，当前连接不会执行。核对原因后，可先移除原信任，再重新探测。</p><Button variant="secondary" disabled={busy} onClick={() => { setTrust({ kind: 'forget', profile: probe.originalProfile }); setChecked(false); }}>核对并移除原信任</Button></> : <Button variant="secondary" disabled={busy || !probe.probeId} onClick={() => { setTrust({ kind: 'approve', ...probe }); setChecked(false); }}>核对并信任这个指纹</Button>}</>)}
    <Receipt value={receipt} run={run} />
    {selected && section('当前连接 · ' + selected.name, stale ? <><p role="alert">连接已在另一页面修改。旧目标仍显示在这里，尚未沿用它开始新工作。</p><Button variant="secondary" onClick={() => { setSelected(profiles.find(profile => profile.id === selected.id) || null); }}>重新读取当前连接</Button></> : domain === 'remote' ? <RemoteWork key={selected.id + selected.bindingHash} profile={selected} state={state} data={data} run={run} supported={supported} /> : <SshWork key={selected.id + selected.bindingHash} profile={selected} state={state} data={data} run={run} supported={supported} />)}
    {domain === 'remote' && list(data.jobs).length > 0 && section('电脑上的原任务', list(data.jobs).map(job => <article className="pw-source-record" key={job.id}><header><h3>{job.worker || '电脑任务'}</h3><span>{status(job.status)}</span></header>{job.message && <p>{job.message}</p>}<div className="pw-form-actions">
      <Button variant="secondary" disabled={busy} onClick={() => act('remote.getJob', originalJob(job))}>读取原回执</Button>
      {['queued', 'running', 'stopping', 'awaiting_approval', 'awaiting_input'].includes(job.status) && <Button variant="secondary" disabled={busy} onClick={() => act('remote.cancelJob', originalJob(job))}>请求停止原任务</Button>}
      {list(state.tasks).find(task => object(task.raw).externalId === job.id && object(object(task.raw).externalRefs).profileId === job.profileId)?.id && <Button variant="secondary" onClick={() => { const task = list(state.tasks).find(task => object(task.raw).externalId === job.id && object(object(task.raw).externalRefs).profileId === job.profileId); void run('tasks.open', { id: task!.id }).catch(() => {}); }}>打开本机原任务</Button>}
    </div></article>))}
    {editing && <ProfileEditor key={editing.id || 'new'} domain={domain} original={editing} current={profiles.find(profile => profile.id === editing.id)} run={run} close={() => setEditing(null)} />}
    <SettingsDialog isOpen={!!removing} title="移除这个连接？" onClose={() => { if (!busy) setRemoving(null); }}><p>只移除本机连接配置。已发生的远端操作请在原任务核对。</p><Facts values={[[ '连接', removing?.name ], ['地址', removing?.baseUrl || removing?.host ]]} /><div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => setRemoving(null)}>取消</Button><Button disabled={busy} onClick={() => removing && act(domain + '.removeProfile', { profileId: removing.id, expectedBindingHash: removing.bindingHash }, () => { setRemoving(null); setSelected(null); })}>移除这项原连接</Button></div></SettingsDialog>
    <SettingsDialog isOpen={!!trust} title={trust?.kind === 'forget' ? '移除原主机信任' : '确认主机身份'} onClose={() => { if (!busy) setTrust(null); }}><p>请先通过可信渠道核对这台主机，不仅依赖当前网络探测。</p><pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{trust?.kind === 'forget' ? trust?.profile?.hostFingerprint : trust?.fingerprint}</pre><CheckboxField isSelected={checked} onChange={setChecked}>{trust?.kind === 'forget' ? '已核对原因，移除这项原信任' : '已通过可信渠道核对一致'}</CheckboxField><div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => setTrust(null)}>取消</Button><Button disabled={busy || !checked} onClick={() => {
      if (!trust) return; const input = trust.kind === 'forget' ? { profileId: trust.profile.id, expectedBindingHash: trust.profile.bindingHash, fingerprint: trust.profile.hostFingerprint } : { profileId: trust.profileId, expectedBindingHash: trust.bindingHash, probeId: trust.probeId, fingerprint: trust.fingerprint };
      act(trust.kind === 'forget' ? 'ssh.forgetHostKey' : 'ssh.approveHostKey', input, () => { setTrust(null); setProbe(null); setSelected(null); });
    }}>确认这项操作</Button></div></SettingsDialog>
  </>;
}

function ProfileEditor({ domain, original, current, run, close }: { domain: 'remote' | 'ssh'; original: Data; current?: Data; run: Run; close: () => void }) {
  const [form, setForm] = useState<Data>({ name: text(original.name), baseUrl: text(original.baseUrl), host: text(original.host), port: text(original.port || 22), username: text(original.username), workspace: text(original.workspace), authType: original.authType === 'password' ? 'password' : 'key', token: '', privateKey: '', password: '', passphrase: '' });
  const [attempted, setAttempted] = useState(false), { request, busy, error } = useRequest(run);
  const clear = () => setForm(previous => ({ ...previous, token: '', privateKey: '', password: '', passphrase: '' }));
  useEffect(() => { const hide = () => { if (document.visibilityState !== 'visible') clear(); }; document.addEventListener('visibilitychange', hide); window.addEventListener('pagehide', clear); return () => { document.removeEventListener('visibilitychange', hide); window.removeEventListener('pagehide', clear); }; }, []);
  const stale = !!original.id && (!current || current.bindingHash !== original.bindingHash);
  const field = (key: string, value: any) => setForm(previous => ({ ...previous, [key]: value }));
  const canSave = !busy && !attempted && !stale && text(form.name).trim() && (domain === 'remote' ? text(form.baseUrl).trim() : text(form.host).trim() && text(form.username).trim() && text(form.workspace).startsWith('/') && wirelessPort(form.port));
  return <SettingsDialog isOpen title={domain === 'remote' ? '电脑环境连接' : 'SSH 连接'} onClose={() => { if (!busy) { clear(); close(); } }}><div className="pw-form-grid">
    <TextField label="名称" value={form.name} onChange={value => field('name', value)} />
    {domain === 'remote' ? <><TextField label="Companion 地址" type="url" value={form.baseUrl} onChange={value => field('baseUrl', value)} /><TextField label="连接令牌" type="password" autoComplete="new-password" value={form.token} onChange={value => field('token', value)} hint="相同服务可留空保留凭据；更换服务需重新填写。" /></> : <>
      <TextField label="主机" value={form.host} onChange={value => field('host', value)} /><TextField label="端口" inputMode="numeric" value={form.port} onChange={value => field('port', value.replace(/\D/g, '').slice(0, 5))} /><TextField label="用户名" value={form.username} onChange={value => field('username', value)} /><TextField label="工作区" value={form.workspace} onChange={value => field('workspace', value)} />
      <SelectField label="认证方式" value={form.authType} options={[{ id: 'key', label: '私钥' }, { id: 'password', label: '密码' }]} onChange={value => { clear(); field('authType', value); }} />
      {form.authType === 'key' ? <><TextAreaField label="私钥内容" value={form.privateKey} onChange={value => field('privateKey', value)} autoComplete="off" /><TextField label="私钥口令（如有）" type="password" value={form.passphrase} onChange={value => field('passphrase', value)} autoComplete="new-password" /></> : <TextField label="密码" type="password" value={form.password} onChange={value => field('password', value)} autoComplete="new-password" />}
      <p className="pw-field-hint">凭据只写入手机加密存储，不回填。更换主机或用户时请重新填写。</p>
    </>}</div>{stale && <p role="alert">连接已在其他页面修改，请关闭后重新读取。</p>}{error && <p role="alert">{error} 请先核对当前连接列表，不重复这次保存。</p>}<div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => { clear(); close(); }}>关闭</Button><Button disabled={!canSave} onClick={() => { const payload = connectionProfilePayload(domain, form, original); setAttempted(true); clear(); void request(domain + '.saveProfile', payload).then(close).catch(() => {}); }}>{busy ? '等待保存回执…' : '保存这项连接'}</Button></div></SettingsDialog>;
}

function RemoteWork({ profile, state, data, run, supported }: { profile: Data; state: Data; data: Data; run: Run; supported: (action: string) => boolean }) {
  const [environments, setEnvironments] = useState<Data[] | null>(null), [environmentId, setEnvironment] = useState(''), [worker, setWorker] = useState('codex_app_server'), [probe, setProbe] = useState<Data | null>(null);
  const [goal, setGoal] = useState(''), [argv, setArgv] = useState('["git","status","--short"]'), [model, setModel] = useState(''), [effort, setEffort] = useState(''), [readOnly, setReadOnly] = useState(true), [timeout, setTimeoutValue] = useState('300');
  const [draftError, setDraftError] = useState(''), [review, setReview] = useState<Data | null>(null), [operation, setOperation] = useState<Data | null>(null), [receipt, setReceipt] = useState<Data | null>(null), { request, busy, error } = useRequest(run);
  const environment = environments?.find(item => item.id === environmentId), workers = list(environment?.workers).filter(item => ['codex_app_server', 'claude', 'pi', 'command'].includes(item.id));
  const unresolved = list(data.operations).filter(item => item.profileId === profile.id && ['unknown', 'submitting'].includes(item.status));
  const nativeTask = list(state.tasks).find(task => task.id === operation?.operationId);
  const locked = busy || !!operation;
  const target = () => ({ ...binding(profile), environmentId, environmentBinding: environment?.bindingHash, worker, ...(model.trim() ? { model: model.trim() } : {}), ...(worker === 'codex_app_server' && effort.trim() ? { effort: effort.trim() } : {}) });
  const changed = (change: () => void) => { setProbe(null); setReview(null); change(); };
  const prepare = async () => {
    setDraftError('');
    const input: Data = { profileId: profile.id, bindingHash: profile.bindingHash, environmentId, environmentBinding: environment?.bindingHash, worker, timeoutSeconds: Number(timeout) };
    try {
      if (worker === 'command') input.argv = JSON.parse(argv); else Object.assign(input, { prompt: goal, readOnly: worker === 'codex_app_server' && readOnly }, model.trim() ? { model: model.trim() } : {}, worker === 'codex_app_server' && effort.trim() ? { effort: effort.trim() } : {}, worker === 'codex_app_server' && model.trim() && probe?.modelOptions?.digest ? { modelOptionsDigest: probe.modelOptions.digest } : {});
      setReview(await request('remote.reviewTask', input));
    } catch (failure) { setDraftError(failure instanceof Error ? failure.message : '请核对这次任务参数。'); }
  };
  return <>
    <p>先读取实际工作环境，再选择执行器。每项新工作保留唯一原操作，未知提交不会重发。</p>
    {unresolved.map(item => <div className="pw-selected-object" key={item.operationId}><p>此连接有原提交待核对。</p><Button variant="secondary" disabled={busy} onClick={() => { void request('remote.reconcileOperation', { profileId: profile.id, operationId: item.operationId }).then(setReceipt).catch(() => {}); }}>只读核对原提交</Button><Button variant="secondary" onClick={() => { void run('tasks.open', { id: item.operationId }).catch(() => {}); }}>打开原任务</Button></div>)}
    <Button variant="secondary" disabled={locked || !profile.hasToken} onClick={() => { changed(() => { setEnvironment(''); }); void request('remote.environments', binding(profile)).then(value => setEnvironments(list(value.environments))).catch(() => {}); }}>读取当前工作环境</Button>
    {environments?.length === 0 && <p>未取得可用工作环境；不会自动创建电脑目录或安装执行器。</p>}
    {!!environments?.length && <SelectField label="工作环境" value={environmentId} options={environments.map(item => ({ id: item.id, label: `${item.label || item.id} · ${item.kind || ''}` }))} onChange={value => changed(() => setEnvironment(value))} />}
    {environment && <><Facts values={[[ '实际工作区', environment.workspace ], ['允许写入', environment.writeAllowed ? '是' : '未启用' ]]} /><SelectField label="执行器" value={worker} options={workers.map(item => ({ id: item.id, label: `${item.id === 'pi' ? 'Pi · 无工具对话' : item.id === 'command' ? '明确命令' : item.id === 'codex_app_server' ? 'Codex' : 'Claude'}${item.available === false ? ' · 未安装' : ''}`, disabled: item.available === false }))} onChange={value => changed(() => { setWorker(value); setEffort(''); })} />
      {worker === 'command' ? <TextAreaField label="明确参数 JSON 数组" value={argv} onChange={setArgv} hint="只执行电脑 allowlist 中的参数数组，不将拼接文本当 Shell。" /> : <>
        <TextAreaField label="这次要完成什么" value={goal} onChange={setGoal} /><TextField label="请求模型 ID（可留空）" value={model} onChange={value => changed(() => { setModel(value); setEffort(''); })} />
        {worker === 'codex_app_server' && <><CheckboxField isSelected={readOnly} onChange={setReadOnly}>只读分析，不授权修改文件</CheckboxField>{model.trim() && <TextField label="请求推理档位（可留空）" value={effort} onChange={value => changed(() => setEffort(value))} hint="须来自实际 Codex 模型目录；选择变化后重新核对。" />}</>}
        {worker === 'pi' && <p className="pw-field-hint">仅无工具对话，不提供项目读写、命令、扩展或 MCP；执行器配置可能重试或压缩，费用与内部调用次数尚未核实。</p>}
        {worker === 'claude' && <p className="pw-field-hint">使用执行端的受限工具配置；此处不声明操作系统只读沙箱。原任务仍需实际验证账号和推理。</p>}
        <Button variant="secondary" disabled={locked} onClick={() => { void request('remote.probeWorker', target()).then(setProbe).catch(() => {}); }}>核对执行器与模型能力</Button>
        {probe && <><p>{probe.message || (probe.available ? '执行器已返回可用声明，尚未发起模型任务。' : '尚未核实可用。')}</p>{list(probe.models).length > 0 && <details><summary>已返回的模型目录</summary>{list(probe.models).map(item => <Button key={item.id} variant="secondary" onClick={() => changed(() => { setModel(item.id); setEffort(''); })}>{item.name || item.id}</Button>)}</details>}</>}
      </>}<TextField label="任务时限（10–3600 秒）" value={timeout} inputMode="numeric" onChange={value => setTimeoutValue(value.replace(/\D/g, '').slice(0, 4))} />
      <Button disabled={locked || !supported('remote.reviewTask') || unresolved.length > 0 || (worker !== 'command' && (!goal.trim() || probe?.available !== true)) || Number(timeout) < 10 || Number(timeout) > 3600} onClick={() => { void prepare(); }}>审阅这次新工作</Button>
    </>}
    {(error || draftError) && <p role="alert">{error || draftError}</p>}
    {operation && <div className="pw-selected-object"><p>已保留这次原操作。离开页面不会重提或停止电脑任务。</p><Button variant="secondary" disabled={busy} onClick={() => { void request('remote.reconcileOperation', { profileId: profile.id, operationId: operation.operationId }).then(setReceipt).catch(() => {}); }}>查询原操作</Button><Button variant="secondary" onClick={() => { void run('tasks.open', { id: operation.operationId }).catch(() => {}); }}>打开原任务 · 审批、成果与继续</Button>{nativeTask && ['completed', 'failed', 'cancelled'].includes(nativeTask.status) && list(data.operations).some(item => item.operationId === operation.operationId && ['settled', 'rejected'].includes(item.status)) && <Button variant="secondary" onClick={() => { setOperation(null); setReceipt(null); setGoal(''); }}>新建另一项工作</Button>}</div>}
    <Receipt value={receipt} run={run} />
    <SettingsDialog isOpen={!!review} title="确认在所选电脑创建工作" onClose={() => { if (!busy) setReview(null); }}><Facts values={[[ '连接', profile.name ], ['地址', profile.baseUrl ], ['工作区', environment?.workspace ], ['执行器', review?.worker ], ['请求模型', review?.model || '执行端默认' ], ['时限', review?.timeoutSeconds ]]} /><pre style={{ whiteSpace: 'pre-wrap' }}>{review?.prompt || JSON.stringify(review?.argv)}</pre><p>{review?.worker === 'pi' ? '仅进行无工具对话。' : review?.readOnly ? '本次为 Codex 只读分析。' : '本次可能修改所选工作区；工具审批在原任务逐次核对。'}</p><div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => setReview(null)}>返回修改</Button><Button disabled={busy || !!operation} onClick={() => { if (!review) return; const captured = review; setOperation(captured); setReview(null); void request('remote.confirmTask', { reviewHandle: captured.reviewHandle }).then(setReceipt).catch(() => {}); }}>确认创建一次</Button></div></SettingsDialog>
  </>;
}

function SshWork({ profile, state, data, run, supported }: { profile: Data; state: Data; data: Data; run: Run; supported: (action: string) => boolean }) {
  const [mode, setMode] = useState('agent'), [goal, setGoal] = useState(''), [client, setClient] = useState<string | null>(null), [command, setCommand] = useState('');
  const [directory, setDirectory] = useState(''), [files, setFiles] = useState<Data | null>(null), [file, setFile] = useState<Data | null>(null), [content, setContent] = useState(''), [review, setReview] = useState<Data | null>(null), [receipt, setReceipt] = useState<Data | null>(null), [uncertain, setUncertain] = useState(false), [cooperative, setCooperative] = useState(false);
  const [terminal, setTerminal] = useState<Data | null>(null), [doctor, setDoctor] = useState<Data | null>(null), { request, busy, error } = useRequest(run);
  const trusted = profile.hostKeyTrusted === true && profile.credentialsConfigured === true;
  const prepare = (operation: string, arguments_: Data) => { void request('ssh.reviewOperation', { command: operation, arguments: { ...binding(profile), ...arguments_ } }).then(value => { setCooperative(false); setReview(value); }).catch(() => {}); };
  const selectedTasks = list(state.tasks).filter(task => object(task.raw).feature === 'ssh' && object(object(task.raw).externalRefs).profileId === profile.id);
  const unknown = uncertain || selectedTasks.some(task => ['reconciling', 'unknown'].includes(task.status));
  const readFile = (path: string) => { void request('ssh.readFile', { ...binding(profile), path }).then(value => { setReceipt(value); if (value.status === 'completed' && typeof value.content === 'string' && /^sha256:[a-f0-9]{64}$/.test(text(value.version))) { setFile(value); setContent(value.content); } }).catch(() => {}); };
  return <>
    {!trusted && <p>先核对主机指纹并保存凭据，才可使用此连接。</p>}
    <SelectField label="工作方式" value={mode} options={[{ id: 'agent', label: '让本 Agent 办事' }, { id: 'command', label: '明确命令' }, { id: 'files', label: '工作区文件' }, { id: 'terminal', label: '交互式终端' }, { id: 'doctor', label: '只读环境诊断' }]} onChange={value => { if (!busy) setMode(value); }} />
    {mode === 'agent' && <><TextAreaField label="这次要完成什么" value={goal} onChange={value => { if (!client) setGoal(value); }} hint="目标绑定当前主机与目录，写入授权仍在原任务核对。" /><Button disabled={!trusted || busy || !goal.trim() || goal.length > 12000 || !!receipt?.taskId} onClick={() => { const id = client || crypto.randomUUID(); setClient(id); void request('ssh.startTask', { profileId: profile.id, bindingHash: profile.bindingHash, clientMessageId: id, goal }).then(setReceipt).catch(() => {}); }}>{client ? '核对同一输入回执' : '开始这次 SSH 任务'}</Button>{receipt?.taskId && <Button variant="secondary" onClick={() => { setClient(null); setReceipt(null); setGoal(''); }}>新建另一项工作</Button>}</>}
    {mode === 'command' && <><TextAreaField label="POSIX 命令" value={command} onChange={setCommand} hint="从所选工作目录执行，使用此账号的远端权限；工作目录不是沙箱。" /><Button disabled={!trusted || busy || unknown || !command.trim() || command.length > 12000} onClick={() => prepare('exec', { command, timeoutSeconds: 60 })}>核对并执行这条命令</Button></>}
    {mode === 'files' && <><TextField label="相对目录，留空为工作区" value={directory} onChange={setDirectory} /><Button variant="secondary" disabled={!trusted || busy} onClick={() => { void request('ssh.listFiles', { ...binding(profile), path: directory.trim() }).then(setFiles).catch(() => {}); }}>读取目录</Button>
      {files && <><p className="pw-field-hint">当前结果：{files.path || '工作区根目录'}。不跟随符号链接。</p>{list(files.files).map(item => <article className="pw-source-record" key={item.name}><header><h3>{item.name}</h3><span>{item.symlink ? '符号链接' : item.directory ? '目录' : `${item.bytes} 字节`}</span></header><Button variant="secondary" disabled={busy || item.symlink || !safeChild(files.path || '', item.name)} onClick={() => { const path = safeChild(files.path || '', item.name); if (!path) return; if (item.directory) { setDirectory(path); void request('ssh.listFiles', { ...binding(profile), path }).then(setFiles).catch(() => {}); } else readFile(path); }}>{item.directory ? '打开目录' : '读取文本'}</Button></article>)}</>}
      {file && <div className="pw-selected-object"><h3>{file.path}</h3><TextAreaField label="原文件内容" value={content} onChange={setContent} /><p className="pw-field-hint">按刚读取的文件版本校验。另一写入者仍可能在最后检查后改变文件。</p><Button disabled={busy || unknown || content === file.content} onClick={() => prepare('writeFile', { path: file.path, content, expectedVersion: file.version, acceptCooperativeWrite: true })}>审阅本次文件修改</Button><Button variant="secondary" disabled={busy || content !== file.content} onClick={() => readFile(file.path)}>重新读取已保存版本</Button><Button variant="secondary" disabled={busy} onClick={() => { if (content === file.content) setFile(null); else setReview({ operation: 'discardFile' }); }}>关闭文件</Button></div>}
    </>}
    {mode === 'terminal' && <><p>交互式 Shell 使用账号的实际远端权限。收起不会关闭连接；关闭连接也不保证远端进程终止。</p><Button disabled={!trusted || busy || unknown} onClick={() => prepare('openPty', { cols: 80, rows: 24, timeoutSeconds: 600, allowInteractiveShell: true })}>核对并打开终端</Button>{list(data.terminals).filter(item => item.profileId === profile.id).map(item => <article className="pw-source-record" key={item.terminalId}><header><h3>原终端</h3><span>{status(item.status)}</span></header><Button variant="secondary" onClick={() => setTerminal({ ...item, hostTaskId: item.hostTaskId || item.taskId })}>查看原终端</Button></article>)}</>}
    {mode === 'doctor' && <><p>只读诊断原连接的实际环境。详细报告仅在当前私人页面展示。</p><Button disabled={!trusted || busy || !supported('ssh.doctor')} onClick={() => { void request('ssh.doctor', binding(profile)).then(setDoctor).catch(() => {}); }}>开始一次只读诊断</Button>{doctor && <><Receipt value={doctor} run={run} /><Facts values={[[ '系统', doctor.os || object(doctor.doctor).os ], ['架构', doctor.architecture || object(doctor.doctor).architecture ], ['工作区', doctor.workspace || profile.workspace ]]} />{Object.entries(object(object(doctor.doctor).tools)).map(([tool, value]) => <article className="pw-source-record" key={tool}><header><h3>{tool}</h3><span>{status(object(value).status)}</span></header><p>{object(value).version || object(value).message}</p></article>)}</>}</>}
    {error && <p role="alert">{error}</p>}{unknown && <p>有原操作结果尚待核对，当前不会重放命令、写入或新建终端。</p>}
    <Receipt value={receipt} run={run} />
    {selectedTasks.filter(task => ['running', 'reconciling', 'unknown', 'awaiting_approval'].includes(task.status)).map(task => <Button key={task.id} variant="secondary" onClick={() => { void run('tasks.open', { id: task.id }).catch(() => {}); }}>打开原 {object(task.raw).command || 'SSH'} 任务 · {status(task.status)}</Button>)}
    <SettingsDialog isOpen={!!review} title={review?.operation === 'discardFile' ? '放弃未保存的文件修改？' : '核对这次 SSH 操作'} onClose={() => { if (!busy) setReview(null); }}><Facts values={[[ '连接', profile.name ], ['主机', `${profile.username}@${profile.host}:${profile.port}` ], ['工作区', profile.workspace ]]} /><pre style={{ whiteSpace: 'pre-wrap' }}>{review?.operation === 'exec' ? review.command : review?.operation === 'writeFile' ? review.content : ''}</pre>
      {review?.operation === 'openPty' && <p>将打开拥有此远端账号权限的交互式 Shell，时限 10 分钟。工作目录不是权限沙箱。</p>}
      {review?.operation === 'writeFile' && <><p>只按原路径与版本写入以上内容。合作式校验不能阻止最后检查后的外部写入竞争。</p><CheckboxField isSelected={cooperative} onChange={setCooperative}>已核对修改，接受本次合作式版本校验</CheckboxField></>}
      <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => setReview(null)}>取消</Button><Button disabled={busy || review?.operation === 'writeFile' && !cooperative} onClick={() => {
        if (!review) return; const captured = review; if (captured.operation === 'discardFile') { setFile(null); setReview(null); return; }
        setReview(null); void request('ssh.confirmOperation', { reviewHandle: captured.reviewHandle }).then(value => { setReceipt(value); setUncertain(value.status === 'unknown'); if (captured.operation === 'writeFile' && value.contentVerifiedForReview === true) { setFile({ ...value, content: captured.content }); setContent(captured.content); } if (captured.operation === 'openPty' && value.ptyConfirmed && value.terminalId) setTerminal({ ...value, hostTaskId: value.hostTaskId || value.taskId }); }).catch(() => setUncertain(true));
      }}>{review?.operation === 'discardFile' ? '放弃这些修改' : '确认执行一次'}</Button></div></SettingsDialog>
    {terminal && <SshTerminal key={terminal.terminalId} original={terminal} state={state} run={run} close={() => setTerminal(null)} />}
  </>;
}
function safeChild(directory: string, name: string) { if (!name || name === '.' || name === '..' || /[\/\\\x00-\x1f\x7f]/.test(name) || directory.startsWith('/') || directory.includes('\\') || directory.split('/').some(part => part === '.' || part === '..')) return null; return directory ? directory + '/' + name : name; }
function SshTerminal({ original, state, run, close }: { original: Data; state: Data; run: Run; close: () => void }) {
  const [frame, setFrame] = useState<any>(null), [input, setInput] = useState(''), [automatic, setAutomatic] = useState(true), [uncertain, setUncertain] = useState(false), [closing, setClosing] = useState(false), [receipt, setReceipt] = useState<Data | null>(null), { request, busy, error } = useRequest(run);
  const target = { terminalId: original.terminalId, hostTaskId: original.hostTaskId, runId: original.runId };
  const task = list(state.tasks).find(item => item.id === original.hostTaskId), operations = list(object(task?.raw).operations);
  const blocked = uncertain || task && ['unknown', 'reconciling'].includes(task.status) || operations.some(operation => ['unknown', 'prepared', 'effect_started'].includes(operation.state));
  const read = () => request('ssh.readPty', target).then(value => { if (value.frame) setFrame(value.frame); if (value.status !== 'running') setAutomatic(false); setReceipt(value); });
  const latest = useRef({ busy, read }); latest.current = { busy, read };
  useEffect(() => { let active = true, timer: ReturnType<typeof setTimeout>; const tick = async () => { if (!active) return; if (document.visibilityState === 'visible' && !latest.current.busy) await latest.current.read().catch(() => {}); if (active) timer = setTimeout(tick, 1200); }; if (automatic) void tick(); return () => { active = false; clearTimeout(timer); }; }, [automatic]);
  const send = (value: string) => { const bytes = new TextEncoder().encode(value); if (bytes.length > 65536) return; setInput(''); const encoded = btoa(Array.from(bytes, byte => String.fromCharCode(byte)).join('')); void request('ssh.writePty', { ...target, dataBase64: encoded }).then(value => { setReceipt(value); if (value.status === 'unknown' || value.accepted !== true) setUncertain(true); }).catch(() => setUncertain(true)); };
  return <SettingsDialog isOpen title="原 SSH 终端" onClose={close}><TerminalSurface frame={frame} /><CheckboxField isSelected={automatic} onChange={setAutomatic}>在前台持续读取输出</CheckboxField><div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => { void read().catch(() => {}); }}>读取原输出</Button><Button variant="secondary" onClick={close}>收起</Button><Button variant="secondary" disabled={busy} onClick={() => setClosing(true)}>关闭本机连接</Button></div>
    <TextAreaField label="发送到原终端的文本" value={input} onChange={setInput} /><div className="pw-form-actions"><Button disabled={busy || blocked || !input || receipt?.status !== 'running' || new TextEncoder().encode(input + '\n').length > 65536} onClick={() => send(input + '\n')}>发送文本与换行</Button><Button variant="secondary" disabled={busy || blocked || receipt?.status !== 'running'} onClick={() => send('\x03')}>Ctrl+C</Button></div>{error && <p role="alert">{error}</p>}{blocked && <p>原输入尚待核对，没有重放。请打开原任务处理。</p>}<Receipt value={receipt} run={run} />
    <SettingsDialog isOpen={closing} title="关闭这项原 SSH 连接？" onClose={() => setClosing(false)}><p>关闭本机 PTY 连接，不保证远端进程已终止，不自动重连或重放输入。</p><div className="pw-form-actions"><Button variant="secondary" onClick={() => setClosing(false)}>取消</Button><Button disabled={busy} onClick={() => { setAutomatic(false); setClosing(false); setInput(''); void request('ssh.closePty', target).then(setReceipt).catch(() => {}); }}>关闭本机连接</Button></div></SettingsDialog>
  </SettingsDialog>;
}
