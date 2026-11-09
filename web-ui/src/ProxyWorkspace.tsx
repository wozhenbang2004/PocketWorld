import { useEffect, useRef, useState } from 'react';
import { ArrowUpRight, ChevronRight, Copy, History, RefreshCw, Server } from 'lucide-react';
import { Accordion, AccordionItem, Button, CheckboxField, SelectField, SettingsDialog, TextField } from './forms-library';
import './proxy-workspace.css';
import { ProgressBar } from './library';

type Data = Record<string, any>;
type Run = (action: string, args?: Data) => Promise<any>;
const object = (value: any): Data => value && typeof value === 'object' && !Array.isArray(value) ? value : {};

/** Adopted BoardUI forms around the native loopback-only, fixed-route service. */
export function ProxyWorkspace({ state, run, registerTransientBack }: { state: Data; run: Run; registerTransientBack: (handler: (() => boolean) | null) => void }) {
  const [snapshot, setSnapshot] = useState<Data>({}), [review, setReview] = useState<Data | null>(null);
  const [settings, setSettings] = useState(false), [port, setPort] = useState('0'), [token, setToken] = useState(''), [showToken, setShowToken] = useState(false);
  const [clientProtocol, setClientProtocol] = useState('');
  const [confirmed, setConfirmed] = useState(false), [busy, setBusy] = useState(false), [unknown, setUnknown] = useState(false), [readAfterUnknown, setReadAfterUnknown] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState('');
  const alive = useRef(true), pending = useRef(false), generation = useRef(0), reviewRef = useRef<Data | null>(null), backgrounded = useRef(document.visibilityState !== 'visible'), resumed = useRef(state.webWorkspaceResumeRevision);
  const pendingMutation = useRef(false);
  reviewRef.current = review;
  const closeReview = () => { const original = reviewRef.current; reviewRef.current = null; setReview(null); setConfirmed(false); if (original?.reviewHandle) void run('proxy.closeReview', { reviewHandle: original.reviewHandle }).catch(() => {}); };
  const invoke = async (action: string, args: Data = {}, accept?: (value: Data) => void, effectful = false) => {
    if (pending.current || backgrounded.current || state.webWorkspaceTaskOpen) return;
    pending.current = true; pendingMutation.current = effectful; const request = ++generation.current; setBusy(true); setError('');
    try { const value = object(await run(action, args)); if (alive.current && request === generation.current) accept?.(value); }
    catch (failure) { if (alive.current && request === generation.current) { setError(failure instanceof Error ? failure.message : String(failure)); if (effectful) { setUnknown(true); setReadAfterUnknown(false); } } }
    finally { if (request === generation.current) { pending.current = false; pendingMutation.current = false; if (alive.current) setBusy(false); } }
  };
  const refresh = () => { void invoke('proxy.snapshot', {}, value => { setSnapshot(value); setReadAfterUnknown(true); }); };
  const invalidate = () => { if (pendingMutation.current) { setUnknown(true); setReadAfterUnknown(false); setNotice('页面离开时原服务操作尚未返回，请核对实际状态，没有重复提交。'); } generation.current++; pending.current = false; pendingMutation.current = false; setBusy(false); closeReview(); setToken(''); setShowToken(false); };
  useEffect(() => { alive.current = true; refresh(); return () => { alive.current = false; generation.current++; const original = reviewRef.current; if (original?.reviewHandle) void run('proxy.closeReview', { reviewHandle: original.reviewHandle }).catch(() => {}); }; }, []);
  useEffect(() => {
    const hide = () => { backgrounded.current = true; invalidate(); };
    const visibility = () => { if (document.visibilityState !== 'visible') hide(); else if (backgrounded.current) { backgrounded.current = false; refresh(); } };
    document.addEventListener('visibilitychange', visibility); window.addEventListener('pagehide', hide);
    return () => { document.removeEventListener('visibilitychange', visibility); window.removeEventListener('pagehide', hide); };
  });
  useEffect(() => { if (resumed.current !== state.webWorkspaceResumeRevision) { resumed.current = state.webWorkspaceResumeRevision; invalidate(); if (!backgrounded.current) refresh(); } }, [state.webWorkspaceResumeRevision]);
  useEffect(() => { if (state.webWorkspaceTaskOpen) invalidate(); }, [state.webWorkspaceTaskOpen]);
  useEffect(() => { registerTransientBack(() => { if (review) { closeReview(); setToken(''); return true; } if (settings) { setSettings(false); setToken(''); setShowToken(false); return true; } return busy; }); return () => registerTransientBack(null); }, [review, settings, busy, registerTransientBack]);
  const preparing = Array.isArray(state.moduleBusy) && state.moduleBusy.some((key: string) => ['proxy.start', 'proxy.stop'].includes(key));
  const locked = busy || preparing;
  const rows: Data[] = Array.isArray(snapshot.recentInferenceReferences) ? snapshot.recentInferenceReferences : [];
  const clients: Data[] = Array.isArray(snapshot.clientConfigurations) ? snapshot.clientConfigurations : [];
  const client = clients.find(item => item.protocol === clientProtocol) || clients[0];
  const blockedReason = ({ CHATGPT_PLAN_APP_ONLY: 'ChatGPT 套餐账号仅供本应用使用，请先在 AI 接入中选择 API 来源。', MODEL_NOT_CONFIGURED: '先在 AI 接入中选择明确的 API 模型。', PROTOCOL_NOT_SUPPORTED: '当前来源协议暂不支持本机 API。', MODEL_PATH_NOT_SUPPORTED: '当前模型 ID 不适用于本机 API 路径。', RELAY_AUDIENCE_NOT_ALLOWED: '当前模型组未允许本机 API 用途，请先核对调度范围。' } as Data)[snapshot.blockedReason];
  const portNumber = /^\d{1,5}$/.test(port) ? Number(port) : -1;
  const tokenRevisionKnown = typeof snapshot.tokenRevision === 'string' && snapshot.tokenRevision.length > 0;
  const validPort = portNumber === 0 || portNumber >= 1024 && portNumber <= 65535;
  const canReview = !locked && !unknown && !snapshot.running && snapshot.externalRelayAllowed === true && Number.isSafeInteger(snapshot.selectionRevision) && validPort && (token ? /^[A-Za-z0-9_-]{24,256}$/.test(token) : snapshot.hasToken === true);
  const prepare = () => { if (!canReview) return; void invoke('proxy.reviewStart', { port: portNumber, ...(token ? { token } : {}) }, value => { if (!value.reviewHandle || value.scope !== 'loopback_only' || value.phoneExecution !== false) throw new Error('原启动审阅不完整，请刷新。'); setToken(''); setShowToken(false); setReview(value); setConfirmed(false); }); };
  const generateToken = () => { void invoke('proxy.generateToken', { tokenRevision: snapshot.tokenRevision }, value => { setSnapshot(value); setToken(''); setShowToken(false); setNotice(value.hasToken === true ? '访问令牌已保存在本机。复制后粘贴到客户端。' : '请刷新核对访问令牌状态。'); }, true); };
  const copyToken = () => { void invoke('proxy.copyToken', { tokenRevision: snapshot.tokenRevision, ...(snapshot.running ? { sessionId: snapshot.sessionId } : {}) }, value => { setSnapshot(value); setNotice(value.tokenCopied === true ? '访问令牌已复制，可粘贴到客户端。' : '尚未确认复制，请重新核对。'); }); };
  const start = () => {
    if (!review || !confirmed || locked) return;
    const original = review; reviewRef.current = null; setReview(null); setConfirmed(false);
    void invoke('proxy.confirmStart', { reviewHandle: original.reviewHandle }, value => {
      setSnapshot(value); setSettings(false); setUnknown(false);
      setNotice(value.running === true ? '本机服务已启动，端点与调用记录以当前回执为准。' : '已收到原操作回执，请核对服务状态。');
    }, true);
  };
  return <section className="pw-proxy-workspace pw-settings-section">
    <header className="pw-proxy-heading"><div><h2>把模型接入本机工具</h2><p>统一管理访问地址、令牌和客户端协议。</p></div><Button variant="ghost" iconOnly leadingIcon={RefreshCw} aria-label="刷新服务与调用" disabled={locked} onClick={refresh} /></header>
    {locked && <ProgressBar label="等待原本机服务回执" isIndeterminate showValueLabel={false} size="sm" />}{error && <p className="pw-proxy-feedback pw-field-error" role="alert">{error}</p>}{notice && <p className="pw-proxy-feedback" role="status">{notice}</p>}
    {unknown && <div className="pw-proxy-feedback" role="status"><p>上次操作结果待核对。请先刷新实际状态；没有重复启动或自动更换模型。</p><Button variant="secondary" disabled={!readAfterUnknown || locked} onClick={() => { setUnknown(false); setReadAfterUnknown(false); }}>我已核对服务状态，允许重新准备</Button></div>}
    <section className="pw-proxy-service" aria-label="当前本机服务">
      <div className="pw-proxy-service-title"><span className="pw-proxy-service-icon"><Server size={22} aria-hidden /></span><div><p className="pw-proxy-eyebrow">仅本机回环地址</p><h3>{typeof snapshot.running !== 'boolean' ? '尚未读取服务状态' : snapshot.running ? '服务正在运行' : '服务尚未运行'}</h3>{snapshot.model && <p className="pw-proxy-model">{snapshot.model}</p>}</div></div>
      {snapshot.rootEndpoint && <div className="pw-proxy-endpoint"><span>本机服务地址</span><p>{snapshot.rootEndpoint}</p></div>}
      {blockedReason && <p className="pw-proxy-inline-note">{blockedReason}</p>}
      {snapshot.running && snapshot.routeCurrent === false && <p className="pw-proxy-inline-note" role="alert">原模型连接已经变化，旧服务不再接受新的推理。请先停止，再审阅新的模型选择。</p>}
      <div className="pw-proxy-primary-action">{snapshot.running ? <Button variant="secondary" disabled={locked || !snapshot.sessionId} onClick={() => void invoke('proxy.stop', { sessionId: snapshot.sessionId }, value => { setSnapshot(value); setUnknown(false); setNotice('已收到这次原服务的停止回执。'); }, true)}>停止这次本机服务</Button>
        : <Button disabled={locked || unknown || snapshot.externalRelayAllowed !== true} trailingIcon={ChevronRight} onClick={() => setSettings(true)}>配置并启动</Button>}<p>只监听这台手机，不开放到局域网或公网。</p></div>

      {clients.length > 0 && <section className="pw-proxy-client-config" aria-label="客户端连接配置"><h3>连接客户端</h3><SelectField label="客户端使用的协议" value={client?.protocol || ''} options={clients.map(item => ({ id: item.protocol, label: item.label }))} onChange={setClientProtocol} />
        {client && <><dl className="pw-ai-model-facts"><div><dt>Base URL</dt><dd>{client.baseUrl || '启动后显示'}</dd></div><div><dt>模型 ID</dt><dd>{client.model}</dd></div><div><dt>认证</dt><dd>{client.authHeader}{client.authScheme ? ` · ${client.authScheme}` : ''}</dd></div></dl><Button variant="secondary" leadingIcon={Copy} disabled={locked || !snapshot.hasToken || !tokenRevisionKnown} onClick={copyToken}>复制访问令牌</Button>
          <Accordion className="pw-proxy-endpoints"><AccordionItem id="endpoints" title="请求地址与兼容范围" description={client.native ? '使用上游原协议' : '使用本机协议转换'}>{(Array.isArray(client.requestUrls) && client.requestUrls.length ? client.requestUrls : client.paths || []).map((path: string) => <p key={path} className="pw-reading-copy">{path}</p>)}<p className="pw-proxy-inline-note">访问模型不授予手机操作或工具执行权限。</p></AccordionItem></Accordion></>}
      </section>}
    </section>
    <section className="pw-proxy-calls" aria-label="近期模型调用"><header><div><h3>近期调用</h3><p>只保留本次服务进程最近 8 条引用，不代表累计用量。</p></div><History size={18} aria-hidden /></header>
      {rows.length ? <div className="pw-proxy-call-list">{[...rows].reverse().map(row => <Button key={row.taskId} variant="ghost" className="pw-proxy-call-row" trailingIcon={ArrowUpRight} onClick={() => void run('tasks.open', { id: row.taskId }).catch(failure => setError(failure.message))}><span><strong>{row.model || '原模型调用'}</strong><small>查看原任务 · {row.taskId}</small></span></Button>)}</div> : <div className="pw-proxy-empty"><p>还没有返回调用记录</p><span>{snapshot.running === true ? '在客户端调用后，刷新查看这次服务的原记录。' : '服务启动并被客户端实际调用后，可在这里查看原记录。'}</span></div>}
    </section>
    <SettingsDialog isOpen={settings && !review} title="启动本机 API" onClose={() => { if (!locked) { closeReview(); setSettings(false); setToken(''); setShowToken(false); } }}>
      <div className="pw-proxy-setup"><p className="pw-proxy-setup-context">本次固定使用 <strong>{snapshot.model||'当前 API 模型'}</strong>。更换连接或模型前先停止服务。</p><div className="pw-proxy-setup-fields">
      <TextField label="监听端口" value={port} onChange={setPort} inputMode="numeric" isDisabled={locked} hint="0 为自动选择；固定端口使用 1024–65535。" />
      {port && !validPort && <p role="alert" className="pw-field-error">端口应为 0 或 1024–65535。</p>}
      <section className="pw-proxy-token-actions"><h3>访问令牌</h3><p>{snapshot.hasToken ? '已保存在本机，复制到客户端即可使用。' : '生成独立令牌，供本机客户端访问。'}</p><div className="pw-form-actions"><Button variant="secondary" disabled={locked || unknown || snapshot.running || !tokenRevisionKnown} onClick={generateToken}>{snapshot.hasToken ? '生成新令牌' : '生成访问令牌'}</Button><Button variant="ghost" leadingIcon={Copy} disabled={locked || !snapshot.hasToken || !tokenRevisionKnown} onClick={copyToken}>复制令牌</Button></div></section>
      <Accordion><AccordionItem id="custom-token" title="自定义访问令牌"><TextField label="替换为自己的令牌" value={token} onChange={setToken} type={showToken ? 'text' : 'password'} isDisabled={locked} hint="24–256 位字母、数字、下划线或短横线；留空使用本机已保存令牌。" /><CheckboxField isSelected={showToken} onChange={setShowToken} isDisabled={locked}>显示本次输入</CheckboxField></AccordionItem></Accordion>
      </div>{unknown && <><p role="status">原启动结果待核对，草稿已保留。当前读取：{typeof snapshot.running !== 'boolean' ? '尚未读取服务状态' : snapshot.running ? `服务正在运行 · ${snapshot.endpoint}` : '服务未运行'}。请核对后再决定是否重新准备。</p><div className="pw-form-actions"><Button variant="secondary" disabled={locked} onClick={refresh}>读取原服务状态</Button><Button variant="secondary" disabled={!readAfterUnknown || locked} onClick={() => { setUnknown(false); setReadAfterUnknown(false); }}>我已核对，允许重新准备</Button></div></>}
      {error && <p className="pw-field-error" role="alert">{error}</p>}
      <div className="pw-proxy-setup-footer"><Button disabled={!canReview} onClick={prepare}>核对启动配置</Button></div></div>
    </SettingsDialog>
    <SettingsDialog isOpen={!!review} title="核对本次本机服务" onClose={closeReview}>{review && <>
      <p>模型：{review.model}</p><p>协议：{review.protocol}</p><p>端口：{review.port === 0 ? '由系统选择空闲端口' : review.port}</p>
      <p>仅供本机工具连接，访问模型可能产生服务费用；不开放到局域网或公网。</p>
      <CheckboxField isSelected={confirmed} onChange={setConfirmed} isDisabled={locked}>确认本次模型与本机访问范围</CheckboxField>
      <Button disabled={locked || !confirmed} onClick={start}>启动本机 API</Button>
    </>}</SettingsDialog>
  </section>;
}
