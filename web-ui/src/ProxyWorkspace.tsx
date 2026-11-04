import { useEffect, useRef, useState } from 'react';
import { Button, CheckboxField, SettingsDialog, TextField } from './forms-library';
import { ProgressBar } from './library';

type Data = Record<string, any>;
type Run = (action: string, args?: Data) => Promise<any>;
const object = (value: any): Data => value && typeof value === 'object' && !Array.isArray(value) ? value : {};

/** Adopted BoardUI forms around the native loopback-only, fixed-route service. */
export function ProxyWorkspace({ state, run, registerTransientBack }: { state: Data; run: Run; registerTransientBack: (handler: (() => boolean) | null) => void }) {
  const [snapshot, setSnapshot] = useState<Data>({}), [review, setReview] = useState<Data | null>(null);
  const [settings, setSettings] = useState(false), [port, setPort] = useState('0'), [token, setToken] = useState(''), [showToken, setShowToken] = useState(false);
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
  const portNumber = /^\d{1,5}$/.test(port) ? Number(port) : -1;
  const canReview = !locked && !unknown && !snapshot.running && snapshot.externalRelayAllowed === true && Number.isSafeInteger(snapshot.selectionRevision) && portNumber >= 0 && portNumber <= 65535 && /^[A-Za-z0-9_-]{24,256}$/.test(token);
  const prepare = () => { if (!canReview) return; void invoke('proxy.reviewStart', { port: portNumber, token }, value => { if (!value.reviewHandle || value.scope !== 'loopback_only' || value.phoneExecution !== false) throw new Error('原启动审阅不完整，请刷新。'); setToken(''); setShowToken(false); setReview(value); setConfirmed(false); }); };
  const start = () => {
    if (!review || !confirmed || locked) return;
    const original = review; reviewRef.current = null; setReview(null); setConfirmed(false);
    void invoke('proxy.confirmStart', { reviewHandle: original.reviewHandle }, value => {
      setSnapshot(value); setSettings(false); setUnknown(false);
      setNotice(value.running === true ? '本机服务已启动，端点与调用记录以当前回执为准。' : '已收到原操作回执，请核对服务状态。');
    }, true);
  };
  return <section className="pw-settings-section">
    <header className="pw-section-heading"><div><h2>本机模型服务</h2><p>供这台手机的工具使用你明确选择的 API 模型，仅监听本机回环地址。</p></div></header>
    <div className="pw-form-actions"><Button variant="secondary" disabled={locked} onClick={refresh}>刷新服务与调用</Button>{!snapshot.running && <Button variant="ghost" disabled={locked || unknown || snapshot.externalRelayAllowed !== true} onClick={() => setSettings(true)}>启动设置</Button>}{snapshot.running && <Button variant="secondary" disabled={locked || !snapshot.sessionId} onClick={() => void invoke('proxy.stop', { sessionId: snapshot.sessionId }, value => { setSnapshot(value); setUnknown(false); setNotice('已收到这次原服务的停止回执。'); }, true)}>停止这次本机服务</Button>}</div>
    {locked && <ProgressBar label="等待原本机服务回执" isIndeterminate showValueLabel={false} size="sm" />}{error && <p role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}
    {unknown && <><p role="status">上次操作结果待核对。请先刷新实际状态；没有重复启动或自动更换模型。</p><Button variant="secondary" disabled={!readAfterUnknown || locked} onClick={() => { setUnknown(false); setReadAfterUnknown(false); }}>我已核对服务状态，允许重新准备</Button></>}
    <p>{typeof snapshot.running !== 'boolean' ? '尚未读取服务状态' : snapshot.running ? '服务正在运行' : '服务尚未运行'}{snapshot.model ? ` · ${snapshot.model}` : ''}</p>
    {snapshot.modelRoute === 'account' && <p>当前是 ChatGPT 账号模型，仅供本应用使用。启动本机服务前，请在 API 模型目录明确选择自己的 API-key 连接。</p>}
    {snapshot.endpoint && <p className="pw-reading-copy">{snapshot.endpoint}</p>}
    {snapshot.running && snapshot.routeCurrent === false && <p role="alert">原模型连接已经变化，旧服务不再接受新的推理。请先停止，再审阅新的模型选择。</p>}
    {snapshot.protocol && <p className="pw-muted">客户端协议：{snapshot.protocol}。不转换协议，也不授予手机或工具执行权限。</p>}
    {Array.isArray(snapshot.endpoints) && snapshot.endpoints.length > 0 && <details><summary>此模型支持的端点</summary>{snapshot.endpoints.map((path: string) => <p key={path} className="pw-reading-copy">{path}</p>)}</details>}
    <h3>本次服务近期调用</h3><p className="pw-muted">最多保留当前服务进程最近 8 条引用，不代表累计用量。</p>
    {rows.length ? [...rows].reverse().map(row => <Button key={row.taskId} variant="ghost" onClick={() => void run('tasks.open', { id: row.taskId }).catch(failure => setError(failure.message))}>查看实际模型调用 · {row.model || row.taskId}</Button>) : <p className="pw-secondary-empty">当前没有返回模型调用记录。</p>}
    <SettingsDialog isOpen={settings && !review} title="启动本机模型服务" onClose={() => { if (!locked) { closeReview(); setSettings(false); setToken(''); setShowToken(false); } }}>
      <p>启动后固定本次 API 模型选择。更换连接或模型前先停止服务，不会自动切换计费身份。</p>
      <TextField label="监听端口，0 为自动选择" value={port} onChange={setPort} inputMode="numeric" isDisabled={locked} />
      <TextField label="独立访问令牌" value={token} onChange={setToken} type={showToken ? 'text' : 'password'} isDisabled={locked} hint="24–256 位字母、数字、下划线或短横线；与上游 API 密钥分开。" />
      <div className="pw-form-actions"><Button variant="secondary" disabled={locked} onClick={() => { const bytes = crypto.getRandomValues(new Uint8Array(32)); setToken(Array.from(bytes, value => value.toString(16).padStart(2, '0')).join('')); }}>生成随机访问令牌</Button><CheckboxField isSelected={showToken} onChange={setShowToken} isDisabled={locked}>显示令牌</CheckboxField></div>
      <p className="pw-muted">请在审阅前为客户端保存这项令牌。审阅后、进入后台或离开页面会清除输入框中的令牌；已保存的服务凭据留在原生加密存储中。</p>
      {unknown && <><p role="status">原启动结果待核对，草稿已保留。当前读取：{snapshot.running ? `服务正在运行 · ${snapshot.endpoint}` : '服务未运行'}。请核对后再决定是否重新准备。</p><div className="pw-form-actions"><Button variant="secondary" disabled={locked} onClick={refresh}>读取原服务状态</Button><Button variant="secondary" disabled={!readAfterUnknown || locked} onClick={() => { setUnknown(false); setReadAfterUnknown(false); }}>我已核对，允许重新准备</Button></div></>}
      {error && <p className="pw-field-error" role="alert">{error}</p>}
      <Button disabled={!canReview} onClick={prepare}>核对启动配置</Button>
    </SettingsDialog>
    <SettingsDialog isOpen={!!review} title="核对本次本机服务" onClose={closeReview}>{review && <>
      <p>模型：{review.model}</p><p>协议：{review.protocol}</p><p>端口：{review.port === 0 ? '由系统选择空闲端口' : review.port}</p>
      <p>仅供本机工具连接，访问模型可能产生服务费用；不开放到局域网或公网。</p>
      <CheckboxField isSelected={confirmed} onChange={setConfirmed} isDisabled={locked}>我已保存访问令牌，并确认本次模型和范围</CheckboxField>
      <Button disabled={locked || !confirmed} onClick={start}>确认并启动本机服务</Button>
    </>}</SettingsDialog>
  </section>;
}
