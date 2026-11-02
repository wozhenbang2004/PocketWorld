import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import {
  RiArrowLeftLine, RiArrowRightSLine, RiCheckLine, RiComputerLine, RiDatabase2Line,
  RiFileList3Line, RiKey2Line, RiLink, RiMicLine, RiPuzzle2Line, RiRefreshLine,
  RiSettings3Line, RiShieldCheckLine, RiSmartphoneLine, RiTeamLine,
} from '@remixicon/react';
import { bridge } from './bridge';
import { PressButton } from './library';
import {
  Accordion, AccordionItem, Button, CheckboxField, ComboBoxField, HostFileUpload,
  IconButton, ObjectTable, RadioOptions, SearchField, SelectField, SettingsDialog,
  StretchSwitch, TextAreaField, TextField, type FieldOption,
} from './forms-library';
import './secondary.css';
import { backgroundConnection, connectionProfilePayload, wirelessEndpoints, wirelessPort } from './forms-library/host-contracts';

/**
 * Product composition of the selected BoardUI SettingsModal/settings rows,
 * actual BoardUI field sources and the approved press/stretch switch.
 * Source originals, per-file hashes and adaptation records are in forms-library.
 * This module only dispatches named capabilities published by the Android host.
 * A command acknowledgement is never treated as a saved setting or approval.
 */
type Data = Record<string, any>;
type Snapshot = { state?: Data; composer?: Data | null; capabilities?: { commands?: string[] } };
type Props = { route: string; onBack: () => void; snapshot: Snapshot };
type Run = (action: string, args?: Data) => Promise<any>;
const object = (value: any): Data => value && typeof value === 'object' && !Array.isArray(value) ? value : {};
const list = (value: any): Data[] => Array.isArray(value) ? value.filter(x => x && typeof x === 'object' && !Array.isArray(x)) : [];
const text = (value: any, fallback = ''): string => typeof value === 'string' ? value : typeof value === 'number' || typeof value === 'boolean' ? String(value) : fallback;
const first = (value: Data, keys: string[], fallback = '') => keys.map(key => text(value[key])).find(Boolean) || fallback;
const rowsAt = (value: Data, keys: string[]) => keys.map(key => value[key]).find(Array.isArray);
const hasRows = (value: Data, keys: string[]) => keys.some(key => Array.isArray(value[key]));
const recordId = (row: Data, fallback = '') => first(row, ['id', 'taskId', 'profileId', 'accountId', 'peerId', 'ruleId', 'receiptId', 'name'], fallback);
const statusText = (value: any) => ({
  running: '执行中', preparing: '准备中', queued: '等待执行', completed: '已完成', failed: '未完成',
  stopped: '已停止', interrupted: '已中断', unknown: '待核对', pending: '等待处理',
  connected: '已连接', disconnected: '未连接', enabled: '已启用', disabled: '已停用',
  waiting_approval: '等待授权', awaiting_approval: '等待授权', awaiting_input: '等待补充',
  recording: '正在记录', ready: '已就绪', paused: '已暂停',
} as Record<string, string>)[text(value)] || text(value, '状态未提供');
const timeText = (value: any) => {
  if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0) return '';
  return new Date(value).toLocaleString(undefined, { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
};

export const SECONDARY_DOMAINS = [
  { id: 'models', title: 'API 模型目录', detail: '连接、能力、模型组与用途', group: '模型与连接', icon: RiKey2Line },
  { id: 'accounts', title: 'ChatGPT 账号', detail: '授权、模型目录与套餐用量', group: '模型与连接', icon: RiKey2Line },
  { id: 'devices', title: '我的设备', detail: '手机与电脑、配对、接收和权限', group: '模型与连接', icon: RiComputerLine },
  { id: 'remote', title: '电脑与服务器', detail: 'Companion 环境与原任务', group: '模型与连接', icon: RiComputerLine },
  { id: 'ssh', title: 'SSH 工作区', detail: '主机公钥、文件与终端', group: '模型与连接', icon: RiComputerLine },
  { id: 'local', title: '本机开发', detail: '运行时、项目、依赖与执行回执', group: '模型与连接', icon: RiSmartphoneLine },
  { id: 'memory', title: '私人记忆', detail: '来源、作用域与正式记录', group: '记忆与主动任务', icon: RiDatabase2Line },
  { id: 'memoryReview', title: '学习候选审核', detail: '核对来源、接受、纠正和遗忘', group: '记忆与主动任务', icon: RiShieldCheckLine },
  { id: 'learning', title: '经验技能审核', detail: '从已审记忆整理方法，审阅后启用', group: '记忆与主动任务', icon: RiShieldCheckLine },
  { id: 'automation', title: '提醒与主动任务', detail: '时间、目标和原发生回执', group: '记忆与主动任务', icon: RiFileList3Line },
  { id: 'commitments', title: '承诺与跟进', detail: '期限、静默时段与真实跟进回执', group: '记忆与主动任务', icon: RiFileList3Line },
  { id: 'channels', title: '消息渠道', detail: 'QQ、微信、主人绑定与接收', group: '协作与扩展', icon: RiLink },
  { id: 'skills', title: '技能', detail: '正文、来源、版本与启用状态', group: '协作与扩展', icon: RiFileList3Line },
  { id: 'plugins', title: '插件', detail: '已安装工具、范围与运行状态', group: '协作与扩展', icon: RiPuzzle2Line },
  { id: 'pluginCatalog', title: '我的插件目录', detail: '安装、源码历史与恢复审阅', group: '协作与扩展', icon: RiPuzzle2Line },
  { id: 'mcp', title: 'MCP 工具', detail: '配置导入、权限预览和授权', group: '协作与扩展', icon: RiPuzzle2Line },
  { id: 'teams', title: '团队', detail: '成员、共同对话和工作分配', group: '协作与扩展', icon: RiTeamLine },
  { id: 'groups', title: '群协作', detail: '参与者、任务、材料与成果投递', group: '协作与扩展', icon: RiTeamLine },
  { id: 'media', title: '生图与语音文件', detail: '提供方、原提交和结果附件', group: '语音与媒体', icon: RiMicLine },
  { id: 'realtime', title: '实时通话', detail: '邀请、当前会话与音视频权限', group: '语音与媒体', icon: RiMicLine },
  { id: 'proxy', title: '本机模型服务', detail: '运行状态、端点与近期调用', group: '语音与媒体', icon: RiComputerLine },
] as const;

function Card({ title, description, children, className = '' }: { title?: string; description?: string; children: ReactNode; className?: string }) {
  return <section className={'pw-settings-section ' + className}>{title && <header className="pw-section-heading"><h2>{title}</h2>{description && <p>{description}</p>}</header>}{children}</section>;
}
function Empty({ children }: { children: ReactNode }) { return <p className="pw-secondary-empty">{children}</p>; }
function Facts({ items }: { items: Array<[string, any]> }) {
  const visible = items.filter(([, value]) => value !== undefined && value !== null && value !== '');
  if (!visible.length) return null;
  return <dl className="pw-facts">{visible.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{text(value)}</dd></div>)}</dl>;
}
function SourceRecord({ item, titleKeys = ['name', 'title', 'goal', 'label', 'id'], children }: { item: Data; titleKeys?: string[]; children?: ReactNode }) {
  return <article className="pw-source-record"><header><h3>{first(item, titleKeys, '未命名记录')}</h3><span>{statusText(item.status || item.state || (typeof item.enabled === 'boolean' ? item.enabled ? 'enabled' : 'disabled' : ''))}</span></header>
    {first(item, ['description', 'summary', 'message', 'text']) && <p>{first(item, ['description', 'summary', 'message', 'text'])}</p>}
    {children}
  </article>;
}
function CapabilityAction({ action, supported, run, args = {}, children, primary = false, disabled = false }: { action: string; supported: (action: string) => boolean; run: Run; args?: Data; children: ReactNode; primary?: boolean; disabled?: boolean }) {
  const [waiting, setWaiting] = useState(false);
  const click = async () => { setWaiting(true); try { await run(action, args); } catch { /* The page keeps the real error visible. */ } finally { setWaiting(false); } };
  const inactive = disabled || waiting || !supported(action);
  return primary ? <PressButton compact disabled={inactive} onClick={click} title={!supported(action) ? '当前页面尚未开放此操作' : undefined}>{waiting ? '处理中…' : children}</PressButton>
    : <Button variant="secondary" disabled={inactive} onClick={click} title={!supported(action) ? '当前页面尚未开放此操作' : undefined}>{waiting ? '处理中…' : children}</Button>;
}

function ModelSettings({ state, supported, run, navigate }: { state: Data; supported: (action: string) => boolean; run: Run; navigate: (route: string) => void }) {
  const [form, setForm] = useState(() => ({
    url: text(state.baseUrl), model: state.modelRoute === 'account' ? '' : text(state.modelName),
    apiKey: '', provider: state.modelRoute === 'account' ? 'openai_chat' : text(state.modelProvider, 'openai_chat'),
    screenshots: state.screenshots !== false, supportsImageInput: Boolean(state.supportsImageInput),
    maxOutputTokens: text(state.maxOutputTokens, '4096'),
  }));
  const [dirty, setDirty] = useState(false);
  const savedRevision = useRef(Number(state.modelSavedRevision) || 0);
  const editRevision = useRef(0);
  const submitted = useRef<{ editRevision: number; apiKey: string } | null>(null);
  const [transport, setTransport] = useState<Data | null>(null);
  const [formError, setFormError] = useState('');
  const [requesting, setRequesting] = useState(false);
  const [consentTarget, setConsentTarget] = useState(text(state.cleartextConsent));
  useEffect(() => {
    const revision = Number(state.modelSavedRevision) || 0;
    if (revision > savedRevision.current) {
      savedRevision.current = revision;
      const original = submitted.current;
      submitted.current = null;
      if (original) {
        if (original.editRevision === editRevision.current) setDirty(false);
        setForm(current => current.apiKey === original.apiKey ? { ...current, apiKey: '' } : current);
      }
    }
    if (!dirty || revision > savedRevision.current) setForm(current => ({
      ...current, url: text(state.baseUrl), model: state.modelRoute === 'account' ? '' : text(state.modelName),
      provider: state.modelRoute === 'account' ? 'openai_chat' : text(state.modelProvider, 'openai_chat'),
      screenshots: state.screenshots !== false, supportsImageInput: Boolean(state.supportsImageInput), maxOutputTokens: text(state.maxOutputTokens, '4096'),
    }));
  }, [state.baseUrl, state.modelName, state.modelProvider, state.modelRoute, state.modelSavedRevision, dirty]);
  const change = (key: keyof typeof form, value: string | boolean) => {
    editRevision.current++;
    setDirty(true); setForm(current => ({ ...current, [key]: value }));
    if (key === 'provider' || key === 'url') { setConsentTarget(''); setTransport(null); }
    if (key === 'provider' || key === 'url' || key === 'model') setForm(current => ({ ...current, supportsImageInput: false }));
  };
  const providers = [
    { id: 'openai_chat', label: 'OpenAI Chat Completions' }, { id: 'openai_responses', label: 'OpenAI Responses' },
    { id: 'anthropic', label: 'Anthropic Messages' }, { id: 'gemini', label: 'Gemini' },
  ];
  const perform = async (action: string, consent: string) => {
    const output = form.provider === 'openai_chat' ? Number(state.maxOutputTokens || 4096) : Number(form.maxOutputTokens);
    if (!form.url.trim() || !form.model.trim() || !Number.isInteger(output) || output < 128 || output > 32768) {
      setFormError('请填写接口地址、模型名称，以及 128–32768 的输出上限。'); return;
    }
    if (action === 'settings.saveModel') submitted.current = { editRevision: editRevision.current, apiKey: form.apiKey };
    try { await run(action, { ...form, url: form.url.trim(), model: form.model.trim(), maxOutputTokens: output, cleartextConsent: consent }); }
    catch (failure) { if (action === 'settings.saveModel') submitted.current = null; throw failure; }
  };
  const request = async (action: string) => {
    if (!supported(action)) return;
    setFormError(''); setRequesting(true);
    try {
      if (supported('settings.reviewModelTransport')) {
        const review = object(await run('settings.reviewModelTransport', { provider: form.provider, url: form.url }));
        if (review.required && review.consentTarget !== consentTarget && review.consentTarget !== state.cleartextConsent) {
          setTransport({ ...review, action, provider: form.provider, url: form.url }); return;
        }
        await perform(action, review.required ? text(review.consentTarget) : '');
      } else {
        if (/^http:\/\//i.test(form.url) && !/^http:\/\/(localhost|127\.0\.0\.1|\[::1\])(?::|\/|$)/i.test(form.url)) {
          setFormError('此明文连接需要先核对当前协议与地址。当前页面尚未提供该核对入口。'); return;
        }
        await perform(action, '');
      }
    } catch (error) { setFormError(error instanceof Error ? error.message : String(error)); }
    finally { setRequesting(false); }
  };
  return <>
    <Card title={text(state.modelName, '模型设置')} description={state.modelRoute === 'account' ? '当前使用 ChatGPT 账号模型' : 'API 连接与推理能力'}>
      <div className="pw-settings-links"><Button variant="ghost" trailingIcon={RiArrowRightSLine} onClick={() => navigate('accounts')}>ChatGPT 账号与模型</Button><Button variant="ghost" trailingIcon={RiArrowRightSLine} onClick={() => navigate('models')}>API 模型目录</Button></div>
      {state.modelRoute === 'account' && <><p className="pw-field-hint">保存 API 配置会明确切换到该连接；测试连接不会改变当前账号模型。</p><CapabilityAction action="settings.useApiKeyModel" supported={supported} run={run} disabled={Boolean(state.modelSaving)}>切回已保存的 API 模型</CapabilityAction></>}
    </Card>
    <Card title="API 连接" description="密钥只提交给手机宿主，不从保存配置中回填。">
      <div className="pw-form-grid">
        <SelectField label="接口协议" options={providers} value={form.provider} onChange={value => change('provider', value)} />
        <TextField label="接口地址" type="url" value={form.url} onChange={value => change('url', value)} placeholder="https://…" autoComplete="off" />
        <TextField label="模型名称" value={form.model} onChange={value => change('model', value)} autoComplete="off" />
        <TextField label="API 密钥" type="password" value={form.apiKey} onChange={value => change('apiKey', value)} autoComplete="new-password"
          hint={state.hasApiKey && form.provider === state.modelProvider && form.url.trim() === state.baseUrl ? '此协议与地址已有密钥；留空继续使用原密钥。' : '更换协议或地址时，请填写对应的密钥。'} />
        <TextField label="每次回复的输出上限" value={form.maxOutputTokens} onChange={value => change('maxOutputTokens', value.replace(/\D/g, '').slice(0, 5))} inputMode="numeric"
          isDisabled={form.provider === 'openai_chat' || Boolean(state.modelCatalogEnabled)} hint={state.modelCatalogEnabled ? '在模型目录中核对精确参数和容量。' : form.provider === 'openai_chat' ? 'Chat 兼容协议的输出限制沿用服务端配置。' : '128–32768 tokens'} />
      </div>
      <div className="pw-settings-divided">
        <CheckboxField isSelected={form.supportsImageInput} onChange={value => change('supportsImageInput', value)} isDisabled={Boolean(state.modelCatalogEnabled) || state.modelRoute === 'account'}>此模型支持图像输入</CheckboxField>
        <StretchSwitch label="允许按需发送截图" description="优先读取结构树；仅在已配置图像能力且确有需要时发送像素。" checked={form.screenshots} onChange={value => change('screenshots', value)} />
      </div>
      {formError && <p role="alert" className="pw-field-error">{formError}</p>}
      <div className="pw-form-actions"><Button variant="secondary" disabled={requesting || state.modelTesting || !supported('settings.testModel')} onClick={() => request('settings.testModel')}>{state.modelTesting ? '测试中…' : '测试连接'}</Button><PressButton compact disabled={requesting || state.modelSaving || !supported('settings.saveModel')} onClick={() => request('settings.saveModel')}>{state.modelSaving ? '保存中…' : state.modelRoute === 'account' ? '保存并切换 API 模型' : '保存模型'}</PressButton></div>
      {state.modelTest && <p className="pw-receipt-copy" aria-live="polite">{text(state.modelTest)}</p>}
      {state.modelTestTaskId && <CapabilityAction action="tasks.open" supported={supported} run={run} args={{ id: state.modelTestTaskId }}>查看本次实际调用与用量</CapabilityAction>}
      {state.error && <p className="pw-field-error" role="alert">{text(state.error)}</p>}
    </Card>
    <SettingsDialog isOpen={Boolean(transport)} title="允许这个明文模型连接？" onClose={() => setTransport(null)}>
      {transport && <><Facts items={[[ '协议', providers.find(x => x.id === transport.provider)?.label ], ['地址', transport.displayTarget || transport.url]]} />
        <p>凭据、文本和按需截图将通过 HTTP 明文传输。更换协议或地址后需要重新核对。</p>
        <div className="pw-form-actions"><Button variant="secondary" onClick={() => setTransport(null)}>取消</Button><PressButton compact onClick={async () => { const reviewed = transport; setTransport(null); if (reviewed.provider !== form.provider || reviewed.url !== form.url) return; setConsentTarget(text(reviewed.consentTarget)); try { await perform(reviewed.action, text(reviewed.consentTarget)); } catch (error) { setFormError(error instanceof Error ? error.message : String(error)); } }}>允许此协议与地址</PressButton></div>
      </>}
    </SettingsDialog>
  </>;
}

function PhoneSettings({ state, supported, run, navigate }: { state: Data; supported: (action: string) => boolean; run: Run; navigate: (route: string) => void }) {
  const ready = object(state.phoneReadiness);
  return <>
    <Card title="手机观察与执行" description="本地取图、OCR 和云端发送分别设置。">
      <StretchSwitch label="允许本地按需取图" description="更改用于后续运行；关闭后不为 OCR 采集像素。" checked={state.localCaptureAllowed !== false} disabled={!supported('settings.saveLocalObservation')} onChange={capture => { void run('settings.saveLocalObservation', { capture, ocr: state.localOcrAllowed !== false }).catch(() => {}); }} />
      <StretchSwitch label="允许本地 OCR" description="像素在本地识字；有来源的识别文字可发送给已选推理服务。" checked={state.localOcrAllowed !== false} disabled={!supported('settings.saveLocalObservation')} onChange={ocr => { void run('settings.saveLocalObservation', { capture: state.localCaptureAllowed !== false, ocr }).catch(() => {}); }} />
      <Facts items={[[ '当前执行能力', first(ready, ['message', 'summary', 'status']) ], ['无障碍服务', state.accessibilityEnabled ? '已连接' : '未连接'], ['任务通知', state.notificationsEnabled ? '已开启' : '未开启']]} />
      <div className="pw-form-actions">{[['accessibility', '界面读取与主屏控制'], ['notifications', '任务通知'], ['wireless', '无线调试设置']].map(([section, title]) => <CapabilityAction key={section} action="settings.openSystem" supported={supported} run={run} args={{ section }}>{title}</CapabilityAction>)}<Button variant="secondary" disabled={!supported('settings.openSystem')} onClick={() => navigate('background')}>后台连接与授权</Button></div>
    </Card>
    <Card title="浏览器与语音" description="语音识别只填入草稿，仍由你决定发送。">
      <Facts items={[[ '系统听写', state.speechAvailable ? '可用' : '暂不可用'], ['朗读', state.speaking ? '正在朗读' : '未在朗读']]} />
      <div className="pw-form-actions"><CapabilityAction action="settings.openBrowser" args={{ purpose: 'research' }} supported={supported} run={run}>网页研究工作区</CapabilityAction><CapabilityAction action="settings.openBrowser" args={{ purpose: 'browse' }} supported={supported} run={run}>内置浏览器</CapabilityAction><CapabilityAction action={state.speaking ? 'voice.stopSpeaking' : 'voice.speakLatest'} supported={supported} run={run}>{state.speaking ? '停止朗读' : '朗读最近回复'}</CapabilityAction></div>
    </Card>
  </>;
}

/** Original BackgroundPhoneSetup flow composed with the selected BoardUI
 * fields, radio options, accordion and SettingsModal. It reads the existing
 * display state; this page does not create a task or a second connection. */
function BackgroundSettings({ state, supported, run, navigate }: { state: Data; supported: (action: string) => boolean; run: Run; navigate: (route: string) => void }) {
  const display = object(object(state.features).display), adb = object(display.adb), discovery = object(adb.discovery);
  const pending = Array.isArray(state.moduleBusy) ? state.moduleBusy.map(String) : [];
  const connection = backgroundConnection(display, state.activeTaskId, pending);
  const [connecting, setConnecting] = useState(() => connection.requiresConnectionCheck || connection.phase === 'paired' || connection.identityPresent);
  const [manual, setManual] = useState(false), [portText, setPortText] = useState(''), [selected, setSelected] = useState('');
  const [code, setCode] = useState(''), [pairPort, setPairPort] = useState<number | undefined>();
  const [mainScreenConsent, setMainScreenConsent] = useState(false), [requesting, setRequesting] = useState(false);
  const [unknownResult, setUnknownResult] = useState(false);
  const [awaiting, setAwaiting] = useState<{ key: string; before: string; busySeen: boolean } | null>(null);
  const pairGuard = useRef(false);
  const ownerOpened = useRef(state.backgroundSetupOpen === true);
  const kind = connecting ? 'connect' : 'pairing';
  const endpoints = wirelessEndpoints(discovery, kind);
  const endpointKeys = endpoints.map(item => item.id).join('|');
  const endpoint = endpoints.find(item => item.id === selected);
  const port = manual ? wirelessPort(portText) : endpoint?.port;
  const host = manual ? '127.0.0.1' : endpoint?.host || '127.0.0.1';
  const requiresCheck = connection.requiresConnectionCheck || unknownResult;
  const resultSignature = (key: string) => JSON.stringify([object(state.moduleResults)[key] ?? null, object(state.moduleErrors)[key] ?? null]);
  const closeSecrets = () => setCode('');
  useEffect(() => {
    if (state.backgroundSetupOpen === true) ownerOpened.current = true;
    else if (ownerOpened.current) { setCode(''); navigate('phone'); }
  }, [state.backgroundSetupOpen]);
  useEffect(() => {
    if (!connection.busy) {
      if (connection.requiresConnectionCheck || connection.phase === 'paired' || connection.identityPresent) setConnecting(true);
      else if (['needs_pairing', 'identity_forgotten'].includes(connection.phase)) { setConnecting(false); setPairPort(undefined); }
    }
    if (connection.requiresConnectionCheck || ['paired', 'needs_pairing', 'identity_forgotten'].includes(connection.phase)) setCode('');
    if (connection.helperConnected && !connection.requiresConnectionCheck) setUnknownResult(false);
  }, [connection.phase, connection.requiresConnectionCheck, connection.busy, connection.identityPresent, connection.helperConnected]);
  useEffect(() => { if (connection.taskOwned) { setCode(''); setMainScreenConsent(false); } }, [connection.taskOwned]);
  useEffect(() => { setCode(''); setPortText(''); setManual(false); setSelected(''); }, [kind]);
  useEffect(() => { setCode(''); }, [host, port]);
  useEffect(() => {
    if (!endpoints.some(item => item.id === selected)) setSelected(endpoints.length === 1 ? endpoints[0].id : '');
  }, [endpointKeys, selected]);
  useEffect(() => {
    const hide = () => { if (document.visibilityState !== 'visible') closeSecrets(); };
    document.addEventListener('visibilitychange', hide);
    window.addEventListener('pagehide', closeSecrets);
    return () => { document.removeEventListener('visibilitychange', hide); window.removeEventListener('pagehide', closeSecrets); };
  }, []);
  useEffect(() => {
    if (!awaiting) return;
    if (pending.includes(awaiting.key)) {
      if (!awaiting.busySeen) setAwaiting({ ...awaiting, busySeen: true });
    } else if (awaiting.busySeen || resultSignature(awaiting.key) !== awaiting.before) {
      setAwaiting(null); pairGuard.current = false;
    }
  }, [pending.join('|'), state.moduleResults, state.moduleErrors, awaiting]);
  const submit = async () => {
    if (requesting || awaiting || pairGuard.current || port == null) return;
    if (connecting ? !connection.canConnect || port === pairPort : !connection.canPair || requiresCheck || !/^\d{6}$/.test(code)) return;
    const action = connecting ? 'background.connect' : 'background.pair';
    if (!supported(action)) return;
    const key = connecting ? 'display.adb.connect' : 'display.adb.pair';
    const args = connecting ? { host, port, ...(pairPort != null ? { pairingPort: pairPort } : {}) } : { host, port, pairingCode: code };
    pairGuard.current = true; setRequesting(true); setCode('');
    setAwaiting({ key, before: resultSignature(key), busySeen: false });
    if (!connecting) setPairPort(port);
    try { await run(action, args); }
    catch { setUnknownResult(true); setConnecting(true); setAwaiting(null); pairGuard.current = false; }
    finally { setRequesting(false); }
  };
  const errors = Object.entries(object(state.moduleErrors)).filter(([key]) => key.startsWith('display.')).map(([, value]) => text(value)).filter(Boolean);
  const headline = connection.busy || requesting || awaiting ? '正在核对本次连接' : requiresCheck ? '上次连接结果待核对' : connection.screenEstablished ? '独立屏幕已建立' : connection.helperConnected ? '后台助手已连接' : !connection.loaded ? '正在读取后台连接' : !connection.supported ? '后台独立屏需要 Android 14 或以上' : connection.phase === 'paired' ? '配对已确认，还需要连接助手' : '连接手机后台助手';
  const alternate = connection.backend === 'shizuku' || connection.backend === 'adb';
  const permissions = state.accessibilityEnabled === true && state.notificationsEnabled === true;
  const steps = [
    { title: '系统准备', done: connection.loaded && connection.supported && permissions, detail: !connection.loaded ? '先读取真实系统与连接状态' : !connection.supported ? '当前系统不支持后台独立屏，可明确选择主屏模式' : !permissions ? '完成界面读取权限与任务通知后返回' : '权限已读取，继续核对后台连接' },
    { title: alternate ? '已选连接授权' : '本机配对身份', done: !requiresCheck && (alternate ? connection.helperConnected : connection.identityPresent || connection.phase === 'paired' || connection.helperConnected), detail: requiresCheck ? '核对原连接，避免重复提交配对码' : alternate ? '保留已选择的兼容连接方式' : connection.identityPresent || connection.phase === 'paired' || connection.helperConnected ? '已读取本机身份，连接时会核对有效性' : '使用当前系统窗口的配对端口与六位码' },
    { title: '连接核对', done: connection.helperConnected && !connection.busy && !requiresCheck, detail: connection.helperConnected && !requiresCheck ? '助手已连接，正式使用时核对实际独立屏' : '配对后使用无线调试主页的另一连接端口' },
    { title: '返回原目标', done: false, detail: connection.taskOwned ? '原任务持有执行资源，回到原任务查看' : '完成设置后返回，由你确认是否开始原目标' },
  ];
  const locked = connection.taskOwned || connection.busy || requesting || awaiting !== null;
  const connected = connection.helperConnected && connection.backend === 'wireless_adb' && !display.releasePending && !display.recoveryRequired;
  return <>
    <Card title={headline} description={connection.screenEstablished ? '宿主已核验当前独立显示会话。' : connection.helperConnected ? '正式开始任务时才建立或核验独立显示会话。' : '完成系统授权与连接后返回原目标；本页不会自动开始任务。'}>
      <ol className="pw-background-steps">{steps.map((step, index) => <li key={step.title} data-complete={step.done}><span className="pw-background-step-marker" aria-label={step.done ? '已完成' : '步骤 ' + (index + 1)}>{step.done ? <RiCheckLine size={15} aria-hidden /> : index + 1}</span><div><h3>{step.title}</h3><p>{step.detail}</p></div></li>)}</ol>
      <Facts items={[[ '当前连接方式', ({ wireless_adb: '手机内无线连接', shizuku: 'Shizuku 兼容连接', adb: '受信任电脑 ADB' } as Data)[connection.backend] ], ['连接消息', adb.message ], ['当前执行资源', connection.taskOwned ? '由原手机任务持有' : undefined ]]} />
      <div className="pw-form-actions"><CapabilityAction action="background.refresh" supported={supported} run={run}>刷新核对状态</CapabilityAction>{connection.taskOwned && state.activeTaskId && <CapabilityAction action="tasks.open" args={{ id: state.activeTaskId }} supported={supported} run={run}>回到原任务</CapabilityAction>}</div>
      {awaiting && !connection.busy && <p className="pw-field-hint" role="status">请求已登记，正在等待实际连接回执。可刷新核对状态，请勿重复提交。</p>}
      {errors.map((message, index) => <p className="pw-field-error" role="alert" key={index}>{message}</p>)}
    </Card>
    {!connection.taskOwned && connection.loaded && connection.supported && !alternate && !connected && <Card title={connecting ? '连接后台助手' : '与这台手机配对'} description={connecting ? '回到系统无线调试主页，读取连接端口。它与配对窗口的端口不同。' : '在开发者选项开启无线调试，打开“使用配对码配对设备”，保持当前窗口可见。'}>
      {requiresCheck && <p className="pw-background-attention" role="status">上次请求的结果尚未确认。请连接并核对原助手，不要重复提交配对码。</p>}
      {!connection.wirelessAvailable ? <Empty>此安装包尚未提供手机内无线配对能力。</Empty> : <>
        <div className="pw-form-actions"><Button variant="secondary" disabled={locked || !supported('settings.openSystem')} onClick={() => { setCode(''); void run('settings.openSystem', { section: 'wireless' }).catch(() => {}); }}>前往无线调试设置</Button><CapabilityAction action="background.discover" supported={supported} run={run} disabled={connection.taskOwned || pending.includes('display.adb.discover')}>查找本机服务</CapabilityAction></div>
        <div className="pw-background-endpoints">
          {!manual && endpoints.length > 0 && <RadioOptions label={connecting ? '本机连接服务' : '当前配对窗口'} value={selected} isDisabled={locked} options={endpoints.map((item, index) => ({ id: item.id, label: '本机服务 ' + (index + 1) + ' · 端口 ' + item.port, description: item.host }))} onChange={value => { setCode(''); setSelected(value); }} />}
          {!manual && endpoints.length === 0 && <Empty>{discovery.discovering ? '正在查找本机服务，请保持系统无线调试打开。' : '暂未发现本机服务。打开系统页面后再查找，或填写此手机页面上的端口。'}</Empty>}
          {!manual && endpoints.length === 0 && text(discovery.errorCode) && <p className="pw-field-hint">{text(discovery.message)}</p>}
          {manual && <TextField label={connecting ? '无线调试主页的连接端口' : '当前配对窗口的端口'} inputMode="numeric" value={portText} isDisabled={locked} onChange={value => { setCode(''); setPortText(value.replace(/\D/g, '').slice(0, 5)); }} isInvalid={Boolean(portText && port == null)} hint="仅连接这台手机的 127.0.0.1，不填写其他设备地址。" />}
          <Button variant="ghost" disabled={locked} onClick={() => { setCode(''); setPortText(''); setManual(current => !current); }}>{manual ? '改用自动发现' : '未找到？填写本机端口'}</Button>
        </div>
        {!connecting && <TextField label="系统窗口当前的六位配对码" type="password" inputMode="numeric" autoComplete="off" value={code} isDisabled={locked || !connection.canPair || requiresCheck} onChange={value => setCode(value.replace(/\D/g, '').slice(0, 6))} hint="仅用于本次配对，提交或离开后清空。" />}
        {connecting && port != null && port === pairPort && <p className="pw-background-attention">这是刚才的配对端口，请填写无线调试主页的另一连接端口。</p>}
        <div className="pw-form-actions"><PressButton compact disabled={locked || port == null || !supported(connecting ? 'background.connect' : 'background.pair') || (connecting ? !connection.canConnect || port === pairPort : !connection.canPair || requiresCheck || !/^\d{6}$/.test(code))} onClick={() => { void submit(); }}>{connecting ? requiresCheck ? '恢复并核对原连接' : '完成手机连接' : '确认配对并继续'}</PressButton>
          {!connecting ? <Button variant="ghost" disabled={locked} onClick={() => { setCode(''); setConnecting(true); }}>{connection.identityPresent ? '使用已保存身份连接' : '此前配对过？直接连接'}</Button> : <Button variant="ghost" disabled={locked || !connection.canPair || requiresCheck} onClick={() => { setCode(''); setConnecting(false); }}>重新读取配对窗口</Button>}
        </div>
      </>}
      {(connection.busy || connection.recoveryRequired || awaiting) && <div className="pw-form-actions"><Button variant="secondary" disabled={!supported('background.cancel') || pending.includes('display.adb.cancel')} onClick={() => { setCode(''); void run('background.cancel', {}).catch(() => {}); }}>停止本次连接与启动</Button></div>}
    </Card>}
    {connection.loaded && !connection.supported && <Card title="此系统使用主屏兼容模式" description="后台独立屏需要 Android 14 或以上；执行主屏任务时会切换应用并占用当前屏幕。"><Button variant="secondary" disabled={connection.taskOwned || connection.busy || !supported('background.selectMainScreen')} onClick={() => setMainScreenConsent(true)}>选择主屏兼容模式</Button></Card>}
    <Card title="本机能力检查" description="逐项来自当前系统检查。已连接仍需核对输入、取帧和输入法隔离能力；从系统设置返回后刷新。">
      {list(object(state.phoneReadiness).groups).length === 0 ? <Empty>尚未取得本机能力检查结果。</Empty> : <Accordion variant="divided">{list(object(state.phoneReadiness).groups).map(group => <AccordionItem key={text(group.id)} id={text(group.id)} title={<span className="pw-readiness-title"><span>{text(group.title)}</span><small>{({ ready: '可用', blocked: '需准备', unsupported: '本机不支持', unknown: '尚未核实' } as Data)[text(group.state)] || '尚未核实'}</small></span>}>
        {list(group.checks).length === 0 ? <p className="pw-field-hint">尚未取得实际检查明细。</p> : list(group.checks).map((check, index) => <div key={text(check.id, String(index))} className="pw-readiness-check"><p>{text(check.message, '尚未取得实际状态')}</p>{check.state !== 'ready' && <div className="pw-form-actions">{['accessibility', 'notifications'].includes(check.action) ? <CapabilityAction action="settings.openSystem" args={{ section: check.action }} supported={supported} run={run}>{check.action === 'accessibility' ? '检查界面权限' : '检查任务通知'}</CapabilityAction> : ['background_setup', 'retry'].includes(check.action) ? <CapabilityAction action="background.refresh" supported={supported} run={run}>重新核对</CapabilityAction> : check.action === 'main_screen_choice' ? <Button variant="secondary" disabled={connection.taskOwned || connection.busy || !supported('background.selectMainScreen')} onClick={() => setMainScreenConsent(true)}>选择主屏兼容模式</Button> : null}</div>}</div>)}
      </AccordionItem>)}</Accordion>}
      <div className="pw-form-actions">{state.accessibilityEnabled !== true && <CapabilityAction action="settings.openSystem" args={{ section: 'accessibility' }} supported={supported} run={run}>设置界面读取权限</CapabilityAction>}{state.notificationsEnabled !== true && <CapabilityAction action="settings.openSystem" args={{ section: 'notifications' }} supported={supported} run={run}>允许任务通知</CapabilityAction>}</div>
    </Card>
    <Card><Accordion variant="divided"><AccordionItem id="background-advanced" title="高级兼容与原连接状态"><Facts items={[[ '已选方式', connection.backend || '尚未读取' ], ['Shizuku', object(display.shizuku).message ], ['助手连接', connection.loaded ? connection.helperConnected ? '已连接' : '未连接' : '尚未核实' ], ['独立屏会话', connection.screenEstablished ? '宿主已核验' : '尚未建立或核验'], ['会话标识', display.sessionId ], ['连接恢复', requiresCheck ? '需要核对原连接' : undefined ]]} /><p className="pw-field-hint">保留已选择的连接方式。Shizuku、电脑 ADB 的配置及释放、遗忘操作尚未接入此页面。</p></AccordionItem></Accordion></Card>
    <div className="pw-background-return"><Button variant="secondary" onClick={() => { setCode(''); navigate('phone'); }}>{connection.helperConnected && !requiresCheck ? '连接已完成，返回手机能力' : '返回手机能力'}</Button></div>
    <SettingsDialog isOpen={mainScreenConsent} title="改用主屏兼容模式？" onClose={() => setMainScreenConsent(false)}><p>助手执行时会切换应用并占用当前屏幕。请先完成界面读取与任务通知授权；此处只选择模式，不会开始原目标。</p><div className="pw-form-actions"><Button variant="secondary" onClick={() => setMainScreenConsent(false)}>暂不使用</Button><CapabilityAction action="background.selectMainScreen" supported={supported} run={run} disabled={connection.taskOwned || connection.busy} primary>明确选择主屏模式</CapabilityAction></div></SettingsDialog>
  </>;
}

function HistoryPage({ usage, supported, run }: { usage: boolean; supported: (action: string) => boolean; run: Run }) {
  const [items, setItems] = useState<Data[]>([]), [cursor, setCursor] = useState(''), [more, setMore] = useState(false);
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [read, setRead] = useState(false);
  const attempt = useRef(0), mounted = useRef(true);
  const action = usage ? 'usage.read' : 'history.read';
  const load = async (reset: boolean) => {
    if (busy || !supported(action)) return;
    const version = ++attempt.current; setBusy(true); setError('');
    try {
      const result = object(await run(action, usage ? { cursor: reset ? '' : cursor } : { section: 'tasks', cursor: reset ? '' : cursor }));
      if (!mounted.current || attempt.current !== version) return;
      if (usage && (result.accountingOnly !== true || result.executionAuthorized !== false)) throw new Error('原调用记录身份未核对，未显示替代数据。');
      const page = list(result.tasks || result.items);
      if (page.length > 40) throw new Error('返回超出原记录窗口，未展开该页。');
      setItems(current => reset ? page : Array.from(new Map(current.concat(page).map(row => [recordId(row), row])).values()).slice(0, 160));
      setCursor(text(result.nextCursor)); setMore(result.hasMore === true); setRead(true);
    } catch (failure) { if (mounted.current && attempt.current === version) setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { if (mounted.current && attempt.current === version) setBusy(false); }
  };
  useEffect(() => { mounted.current = true; void load(true); return () => { mounted.current = false; attempt.current++; if (usage && supported('usage.close')) void bridge.command('usage.close', {}).catch(() => {}); }; }, [usage, supported(action)]);
  return <Card title={usage ? '模型调用记录' : '任务历史'} description={usage ? '保留的实际调用摘要；不是全部账单或余额，也不产生执行权限。' : '原任务与原回执；打开历史不会重放执行。'}>
    <div className="pw-form-actions"><Button variant="secondary" leadingIcon={RiRefreshLine} disabled={busy || !supported(action)} onClick={() => load(true)}>刷新记录</Button></div>
    {error && <p className="pw-field-error" role="alert">{error}</p>}
    {busy && <p className="pw-field-hint" aria-live="polite">正在读取原记录…</p>}
    {!read && !busy && <Empty>尚未读取原记录。</Empty>}
    {read && items.length === 0 && <Empty>{usage ? '当前窗口没有保留的独立调用记录；不代表服务用量为 0。' : '当前窗口没有任务记录。'}</Empty>}
    {items.map(row => <SourceRecord key={recordId(row)} item={row} titleKeys={['goal', 'title', 'id']}><Facts items={[[ '更新时间', timeText(row.updatedAt) ], ['来源', first(row, ['source', 'executionMode']) ]]} /><CapabilityAction action="tasks.open" args={{ id: recordId(row) }} supported={supported} run={run}>打开原任务与回执</CapabilityAction></SourceRecord>)}
    {more && items.length < 160 && <Button variant="secondary" disabled={busy} onClick={() => load(false)}>读取较早窗口</Button>}
    {items.length >= 160 && <p className="pw-field-hint">已到本次 160 条显示上限；原记录仍保留，刷新可回到最近窗口。</p>}
  </Card>;
}

function Collection({ data, keys, label, children, titleKeys }: { data: Data; keys: string[]; label: string; children?: (item: Data) => ReactNode; titleKeys?: string[] }) {
  const loaded = hasRows(data, keys), records = list(rowsAt(data, keys));
  return <>{!loaded ? <Empty>{'尚未读取' + label + '。'}</Empty> : records.length === 0 ? <Empty>{'当前读取窗口没有' + label + '。'}</Empty> : records.map((row, index) => <SourceRecord key={recordId(row, String(index))} item={row} titleKeys={titleKeys}>{children?.(row)}</SourceRecord>)}</>;
}

function DomainPage({ domain, state, data, supported, run }: { domain: string; state: Data; data: Data; supported: (action: string) => boolean; run: Run }) {
  const [query, setQuery] = useState(''), [selected, setSelected] = useState(''), [selectedModel, setSelectedModel] = useState(''), [entry, setEntry] = useState<Data | null>(null);
  const [draft, setDraft] = useState<Data>({});
  const [modelReview, setModelReview] = useState<Data | null>(null), [reviewingModel, setReviewingModel] = useState(false);
  const clearProfileSecrets = () => setDraft(current => ({ ...current, token: '', password: '', privateKey: '', passphrase: '' }));
  const closeProfile = () => { clearProfileSecrets(); setEntry(null); };
  useEffect(() => {
    if (domain !== 'ssh' && domain !== 'remote') return;
    const hide = () => { if (document.visibilityState !== 'visible') clearProfileSecrets(); };
    document.addEventListener('visibilitychange', hide);
    window.addEventListener('pagehide', clearProfileSecrets);
    return () => { document.removeEventListener('visibilitychange', hide); window.removeEventListener('pagehide', clearProfileSecrets); };
  }, [domain]);
  useEffect(() => { setModelReview(null); }, [selectedModel]);
  const update = (key: string, value: any) => setDraft(current => ({ ...current, [key]: value }));
  const act = (action: string, args: Data, label: string, primary = false) => <CapabilityAction action={action} args={args} supported={supported} run={run} primary={primary}>{label}</CapabilityAction>;
  const readable = (label: string, value: Data, fields: Array<[string, string[]]>) => <Facts items={fields.map(([title, keys]) => [title, first(value, keys)])} />;
  const title = SECONDARY_DOMAINS.find(x => x.id === domain)?.title || '连接与能力';
  const mcpPick = object(object(object(state.ownerResponses).mcp).pickConfig);
  const pickedMcpConfig = object(object(mcpPick.result).config);
  const mcpPickPending = Array.isArray(state.ownerPending) && state.ownerPending.includes('mcp.pickConfig');
  if (domain === 'models') {
    const connections = list(rowsAt(data, ['connections', 'profiles']));
    const models = list(data.models);
    const connection = connections.find(row => recordId(row) === selected);
    const model = models.find(row => first(row, ['modelId', 'id', 'name']) === selectedModel);
    return <Card title="API 连接与模型目录" description="能力来源、人工覆盖和真实参数属于各自连接，不自动继承其他协议的密钥。">
      {!hasRows(data, ['connections', 'profiles']) ? <Empty>尚未读取 API 连接。</Empty> :
        <ObjectTable label="API 连接" rows={connections} getRowId={recordId} onRowOpen={row => setSelected(recordId(row))}
          columns={[
            { id: 'name', label: '连接', render: row => <span className="pw-object-title">{first(row, ['name', 'label', 'id'])}<small>{first(row, ['provider', 'protocol'])}</small></span>, sortValue: row => first(row, ['name', 'label', 'id']) },
            { id: 'state', label: '状态', render: row => typeof row.enabled === 'boolean' ? row.enabled ? '已启用' : '已停用' : statusText(row.status) },
            { id: 'endpoint', label: '地址', render: row => first(row, ['baseUrl', 'endpoint']), secondary: true },
          ]} empty="当前读取窗口没有 API 连接。" />}
      {connection && <div className="pw-selected-object"><h3>{first(connection, ['name', 'label', 'id'])}</h3><Facts items={[[ '协议', first(connection, ['provider', 'protocol']) ], ['地址', first(connection, ['baseUrl', 'endpoint']) ], ['凭据', connection.hasApiKey || connection.credentialsConfigured ? '已配置' : '未配置'], ['版本', connection.revision]]} />
        <div className="pw-form-actions">{act('models.selectConnection', { connectionId: recordId(connection), expectedRevision: connection.revision }, '使用此连接')}{act('models.reviewConnection', { connectionId: recordId(connection), expectedRevision: connection.revision }, '核对连接与能力')}{act('models.refresh', { connectionId: recordId(connection) }, '读取模型目录')}</div>
      </div>}
      {models.length > 0 ? <ComboBoxField label="模型目录" options={models.map(row => ({ id: first(row, ['modelId', 'id', 'name']), label: first(row, ['name', 'label', 'modelId', 'id']), description: first(row, ['provider', 'sourceLabel']) }))} value={selectedModel} onChange={setSelectedModel} /> : <Empty>{Array.isArray(data.models) ? '当前目录没有可显示的模型。' : '尚未读取此连接的模型目录。'}</Empty>}
      {model && <div className="pw-selected-object">{readable('模型能力', model, [['来源', ['source', 'capabilitySource']], ['输入能力', ['inputDescription', 'inputModalities']], ['核对时间', ['checkedAt', 'updatedAt']]])}<div className="pw-form-actions"><Button variant="secondary" disabled={!supported('models.review') || reviewingModel || Boolean(state.modelSelectionBusy) || state.activeTaskId != null || Boolean(state.chatBusy || state.chatSubmitting || state.phoneSubmitting || state.modelSaving || state.modelTesting)} onClick={async () => { setReviewingModel(true); try { const review = object(await run('models.review', { modelId: first(model, ['modelId', 'id', 'name']) })); if (typeof review.reviewHandle === 'string') setModelReview(review); } catch { /* Original error stays on the page. */ } finally { setReviewingModel(false); } }}>核对并选择此模型</Button></div></div>}
      <SettingsDialog isOpen={modelReview !== null} title="确认原模型选择" onClose={() => setModelReview(null)}>{modelReview && <><Facts items={[[ '模型', modelReview.modelId ], ['输出上限', modelReview.outputTokens ], ['图像预算预留', modelReview.imageTokenReserve ]]} /><p className="pw-field-hint">使用宿主刚核对的原连接、模型能力与参数。原窗口变化或审阅过期后需要重新核对。</p><div className="pw-form-actions"><Button variant="secondary" onClick={() => setModelReview(null)}>取消</Button><Button variant="primary" disabled={!supported('models.select') || Boolean(state.modelSelectionBusy) || state.activeTaskId != null || Boolean(state.chatBusy || state.chatSubmitting || state.phoneSubmitting || state.modelSaving || state.modelTesting)} onClick={async () => { const review = modelReview; setModelReview(null); try { await run('models.select', { reviewHandle: review.reviewHandle }); } catch { /* Never replay a consumed or unknown review. */ } }}>确认选择</Button></div></>}</SettingsDialog>
    </Card>;
  }
  if (domain === 'accounts') return <Card title="ChatGPT 账号" description="只显示授权状态、可用模型与用量；不回填通话或登录凭据。">
    <div className="pw-form-actions">{act('accounts.beginLogin', {}, '添加账号', true)}{act('accounts.refresh', {}, '刷新账号状态')}</div>
    <Collection data={data} keys={['accounts']} label="账号">{row => <>
      <Facts items={[[ '套餐', row.plan || row.planName ], ['凭据', row.credentialsConfigured ? '已配置' : undefined], ['版本', row.version], ['冷却状态', row.cooldownStatus]]} />
      <div className="pw-form-actions">{act('accounts.select', { accountId: recordId(row), expectedVersion: row.version }, '使用此账号')}{act('accounts.models', { accountId: recordId(row), expectedVersion: row.version }, '读取可用模型')}{act('accounts.reviewLogout', { accountId: recordId(row), expectedVersion: row.version }, '退出前核对')}</div>
    </>}</Collection>
    {Object.keys(object(data.login)).length > 0 && <Card title="当前授权"><Facts items={[[ '状态', statusText(data.login.status) ], ['到期时间', timeText(data.login.expiresAt) ]]} /><div className="pw-form-actions">{act('accounts.openAuthorization', { sessionId: data.login.sessionId }, '打开原授权页面')}{act('accounts.cancelLogin', { sessionId: data.login.sessionId }, '取消本次授权')}</div></Card>}
  </Card>;
  if (domain === 'devices') return <Card title="我的设备" description="配对与能力授予保留双方完整身份和原审阅版本。">
    <Facts items={[[ '本机名称', first(object(data.localDevice), ['name', 'label']) ], ['本机完整指纹', first(object(data.localDevice), ['fingerprint', 'identityFingerprint']) ], ['接收', typeof data.receiverEnabled === 'boolean' ? data.receiverEnabled ? '已开启' : '已关闭' : undefined]]} />
    <div className="pw-form-actions">{act('devices.createInvitation', {}, '创建配对邀请', true)}{act('devices.reviewReceiver', { enabled: data.receiverEnabled !== true }, data.receiverEnabled ? '核对并停用接收' : '核对并启用接收')}</div>
    <Collection data={data} keys={['peers', 'devices']} label="已配对设备">{row => <>
      <Facts items={[[ '设备类型', row.kind ], ['完整指纹', row.fingerprint || row.identityFingerprint ], ['授权版本', row.grantRevision ], ['连接状态', row.connectionStatus]]} />
      <div className="pw-form-actions">{act('devices.reviewPeer', { peerId: recordId(row), expectedRevision: row.revision }, '核对连接与授权')}{act('devices.connectPeer', { peerId: recordId(row), expectedRevision: row.revision }, '连接')}{act('devices.disconnectPeer', { peerId: recordId(row), expectedRevision: row.revision }, '断开')}</div>
    </>}</Collection>
    <Collection data={data} keys={['pendingPairings', 'recoverablePairings']} label="待核对配对">{row => <div className="pw-form-actions">{act('devices.reviewPairing', { pairingId: recordId(row), expectedRevision: row.revision }, '打开原配对审阅')}{act('devices.openRecovery', { pairingId: recordId(row) }, '核对恢复地址')}</div>}</Collection>
  </Card>;
  if (domain === 'remote' || domain === 'ssh') return <>
    <Card title={domain === 'remote' ? 'Companion 环境' : 'SSH 主机'} description={domain === 'remote' ? '连接你信任的电脑环境，查看其真实能力与原任务。' : '主机公钥核对、文件与终端；SSH 不等于可恢复的 Companion 任务。'}>
      <Collection data={data} keys={['profiles', 'hosts']} label={domain === 'remote' ? '电脑环境' : 'SSH 主机'}>{row => <>
        <Facts items={[[ '地址', first(row, ['baseUrl', 'host']) ], ['用户名', row.username ], ['工作区', row.workspace ], ['凭据', row.hasToken || row.credentialsConfigured ? '已配置' : '未配置'], ['公钥指纹', row.hostKeyFingerprint]]} />
        <div className="pw-form-actions">{act(domain === 'remote' ? 'remote.probe' : 'ssh.probeHostKey', { profileId: recordId(row) }, domain === 'remote' ? '探测能力' : '核对主机公钥')}{act(domain === 'remote' ? 'remote.reconnect' : 'ssh.listFiles', { profileId: recordId(row), path: row.workspace || '' }, domain === 'remote' ? '同步原任务' : '查看工作区')}<Button variant="secondary" onClick={() => { setEntry(row); setDraft({ name: text(row.name), baseUrl: text(row.baseUrl), host: text(row.host), port: text(row.port, '22'), username: text(row.username), workspace: text(row.workspace), authType: row.authType === 'password' ? 'password' : 'key', token: '', password: '', privateKey: '', passphrase: '' }); }}>编辑连接</Button></div>
      </>}</Collection>
      <Button variant="secondary" onClick={() => { setEntry({}); setDraft(domain === 'remote' ? { name: '', baseUrl: '', token: '' } : { name: '', host: '', port: '22', username: '', workspace: '', authType: 'key', password: '', privateKey: '', passphrase: '' }); }}>添加{domain === 'remote' ? '电脑环境' : 'SSH 主机'}</Button>
    </Card>
    <Collection data={data} keys={['jobs']} label="原任务">{row => <div className="pw-form-actions">{row.taskId && act('tasks.open', { id: row.taskId }, '打开原任务与回执')}{act(domain + '.cancelJob', { profileId: row.profileId, jobId: recordId(row) }, '请求停止')}</div>}</Collection>
    <SettingsDialog isOpen={entry !== null} title={domain === 'remote' ? '电脑环境连接' : 'SSH 连接'} onClose={closeProfile}>
      <div className="pw-form-grid">
        <TextField label="名称" value={text(draft.name)} onChange={value => update('name', value)} />
        {domain === 'remote' ? <><TextField label="Companion 地址" type="url" value={text(draft.baseUrl)} onChange={value => update('baseUrl', value)} /><TextField label="连接令牌" type="password" autoComplete="new-password" value={text(draft.token)} onChange={value => update('token', value)} hint="相同服务可留空保留已存令牌；更换服务需重新填写。令牌不回填。" /></> : <>
          <TextField label="主机" value={text(draft.host)} onChange={value => update('host', value)} /><TextField label="端口" inputMode="numeric" value={text(draft.port, '22')} onChange={value => update('port', value.replace(/\D/g, '').slice(0, 5))} /><TextField label="用户名" value={text(draft.username)} onChange={value => update('username', value)} /><TextField label="工作区" value={text(draft.workspace)} onChange={value => update('workspace', value)} /><SelectField label="认证方式" value={text(draft.authType, 'key')} options={[{ id: 'password', label: '密码' }, { id: 'key', label: '私钥' }]} onChange={value => { clearProfileSecrets(); update('authType', value); }} />{draft.authType === 'key' ? <><TextAreaField label="私钥" value={text(draft.privateKey)} onChange={value => update('privateKey', value)} autoComplete="off" /><TextField label="私钥口令（如有）" type="password" value={text(draft.passphrase)} onChange={value => update('passphrase', value)} autoComplete="new-password" /></> : <TextField label="密码" type="password" value={text(draft.password)} onChange={value => update('password', value)} autoComplete="new-password" />}
        </>}
      </div><div className="pw-form-actions"><Button variant="secondary" onClick={closeProfile}>取消</Button><CapabilityAction action={domain + '.saveProfile'} args={connectionProfilePayload(domain, draft, entry)} supported={supported} run={async (action, args) => { clearProfileSecrets(); return run(action, args); }} disabled={(Array.isArray(state.moduleBusy) && state.moduleBusy.includes(domain + '.saveProfile')) || !text(draft.name).trim() || (domain === 'remote' ? !text(draft.baseUrl).trim() : !text(draft.host).trim() || !text(draft.username).trim() || !text(draft.workspace).trim().startsWith('/') || wirelessPort(text(draft.port)) == null)} primary>保存连接</CapabilityAction></div>
    </SettingsDialog>
  </>;
  if (domain === 'memory' || domain === 'memoryReview' || domain === 'learning') return <Card title={title} description={domain === 'memory' ? '正式记忆、来源和作用域；候选接受与可执行技能启用分开。' : '核对原记录、来源与版本后再决定；进行中的操作保留原回执。'}>
    <SearchField aria-label="筛选当前读取的记录" placeholder="筛选当前读取的记录" value={query} onChange={setQuery} shape="rounded" />
    <Collection data={{ ...data, ...(hasRows(data, ['records', 'memories', 'candidates', 'jobs']) ? { displayRecords: list(rowsAt(data, ['records', 'memories', 'candidates', 'jobs'])).filter(row => (first(row, ['title', 'name', 'text', 'summary']) + recordId(row)).toLocaleLowerCase().includes(query.toLocaleLowerCase())) } : {}) }} keys={['displayRecords']} label="记录">{row => <>
      <Facts items={[[ '作用域', first(row, ['scope', 'layer']) ], ['版本', row.revision ], ['来源', first(row, ['sourceLabel', 'sourceKind']) ], ['核心记忆', typeof row.core === 'boolean' ? row.core ? '是' : '否' : undefined]]} />
      <div className="pw-form-actions">{act(domain + '.openSource', { id: recordId(row), expectedRevision: row.revision }, '查看原来源')}{act(domain + '.review', { id: recordId(row), expectedRevision: row.revision }, '核对详情与可用决定')}</div>
    </>}</Collection>
    {domain === 'learning' && <p className="pw-field-hint">经验导入后保持停用；完整阅读当前 SKILL.md 并取得真实阅读回执后，才允许启用。</p>}
  </Card>;
  if (domain === 'automation' || domain === 'commitments') return <>
    <Card title={title} description="时间、时区、原目标与发生回执绑定；打开目标不自动执行。">
      <Collection data={data} keys={domain === 'automation' ? ['rules', 'reminders'] : ['commitments']} label={domain === 'automation' ? '提醒规则' : '承诺'}>{row => <>
        <Facts items={[[ '计划', first(object(row.schedule), ['type', 'frequency'], text(row.scheduleType)) ], ['时区', first(object(row.schedule), ['timezone'], text(row.timezone)) ], ['期限', timeText(row.dueAt) ], ['版本', row.revision]]} />
        <div className="pw-form-actions">{act(domain + '.review', { id: recordId(row), expectedRevision: row.revision }, '查看原目标与操作')}{row.taskId && act('tasks.open', { id: row.taskId }, '打开关联任务')}</div>
      </>}</Collection>
      {domain === 'automation' && <Button variant="secondary" onClick={() => { setEntry({}); setDraft({ goal: '', frequency: 'once', date: '', time: '', timezone: Intl.DateTimeFormat().resolvedOptions().timeZone }); }}>新建提醒</Button>}
    </Card>
    <Collection data={data} keys={['receipts', 'followUpReceipts']} label="发生回执">{row => <div className="pw-form-actions">{act(domain + '.openReceipt', { receiptId: recordId(row), claimId: row.claimId }, '打开原发生记录')}</div>}</Collection>
    <SettingsDialog isOpen={entry !== null} title="新建提醒" onClose={() => setEntry(null)}><div className="pw-form-grid"><TextAreaField label="提醒目标" value={text(draft.goal)} onChange={value => update('goal', value)} /><RadioOptions label="重复方式" value={text(draft.frequency, 'once')} options={[{ id: 'once', label: '仅一次' }, { id: 'daily', label: '每天' }]} onChange={value => update('frequency', value)} /><TextField label={draft.frequency === 'daily' ? '每日时刻（HH:mm）' : '时间（YYYY-MM-DD HH:mm）'} value={text(draft.time)} onChange={value => update('time', value)} /><TextField label="时区" value={text(draft.timezone)} onChange={value => update('timezone', value)} /></div><div className="pw-form-actions">{act('automation.reviewSchedule', { goal: draft.goal, frequency: draft.frequency, time: draft.time, timezone: draft.timezone }, '核对提醒计划', true)}</div></SettingsDialog>
  </>;
  if (domain === 'channels') return <Card title="消息渠道与主人绑定" description="保留真实私聊证据、绑定版本、接收状态、原任务和投递回执。">
    <Collection data={data} keys={['profiles', 'channels']} label="渠道">{row => <>
      <Facts items={[[ '渠道', first(row, ['platform', 'channel', 'kind']) ], ['主人绑定', first(row, ['ownerLabel', 'ownerId']) ], ['绑定版本', row.bindingEpoch ], ['接收', row.receiving === true ? '运行中' : row.receiving === false ? '已停止' : undefined], ['凭据', row.credentialsConfigured ? '已配置' : undefined]]} />
      <div className="pw-form-actions">{act('channels.inspect', { profileId: recordId(row) }, '查看连接与真实绑定')}{act(row.receiving ? 'channels.stopReceiving' : 'channels.startReceiving', { profileId: recordId(row) }, row.receiving ? '停止接收' : '开始接收')}{act('channels.reviewOwnerBinding', { profileId: recordId(row), expectedEpoch: row.bindingEpoch }, '核对主人绑定')}</div>
    </>}</Collection>
    <Collection data={data} keys={['taskRequests', 'outbox']} label="待处理渠道记录">{row => <div className="pw-form-actions">{row.taskId && act('tasks.open', { id: row.taskId }, '打开原任务')}{act('channels.inspectRecord', { id: recordId(row) }, '核对原记录')}</div>}</Collection>
  </Card>;
  if (domain === 'skills' || domain === 'plugins' || domain === 'pluginCatalog' || domain === 'mcp') return <>
    <Card title={title} description="安装、正文、权限和启用状态分开；每项决定对应原版本与宿主审阅。">
      <Collection data={data} keys={domain === 'skills' ? ['skills'] : domain === 'mcp' ? ['servers', 'installed', 'connections'] : ['plugins', 'installed']} label={title}>{row => <>
        <Facts items={[[ '版本', row.revision || row.version ], ['摘要', row.digest ], ['来源', first(row, ['source', 'sourceKind']) ], ['凭据', row.credentialsConfigured ? '已配置' : undefined]]} />
        <div className="pw-form-actions">{act(domain + '.read', { id: recordId(row), expectedRevision: row.revision, expectedDigest: row.digest }, domain === 'skills' ? '阅读当前正文' : '查看工具与权限')}{act(domain + '.reviewEnabled', { id: recordId(row), enabled: row.enabled !== true, expectedRevision: row.revision, expectedDigest: row.digest }, row.enabled ? '核对并停用' : '核对并启用')}</div>
      </>}</Collection>
      {domain === 'mcp' ? <><HostFileUpload label="选择 MCP 配置" onChoose={() => { void run('mcp.pickConfig', {}).catch(() => {}); }} disabled={!supported('mcp.pickConfig') || mcpPickPending} status={mcpPickPending ? 'importing' : mcpPick.ok === false ? 'error' : 'idle'} statusDescription={mcpPickPending ? '等待系统文件选择与原读取回执…' : mcpPick.ok === true ? '配置已读取，待核对保存' : '系统选择器读取不含凭据的 JSON 配置，上限 64 KB'} error={mcpPick.ok === false ? text(mcpPick.error) : undefined} />{mcpPick.ok === true && <div className="pw-selected-object"><h3>已读取的配置</h3><Facts items={[[ '配置标识', pickedMcpConfig.id ], ['名称', pickedMcpConfig.name ], ['端点', pickedMcpConfig.endpoint ], ['协议版本', pickedMcpConfig.protocolVersion ]]} /><p className="pw-field-hint">配置只完成读取，尚未保存、安装或授予工具权限。此页面的后续核对与保存操作待接入。</p></div>}</> : <p className="pw-field-hint">{domain === 'skills' ? '技能包' : '插件配置'}文件选择与导入尚未接入此页面。</p>}
    </Card>
    <Collection data={data} keys={['pendingOperations', 'previews']} label="当前准备与审阅记录">{row => <div className="pw-form-actions">{act(domain + '.openReview', { id: recordId(row), expectedRevision: row.revision }, '打开原审阅')}{act(domain + '.cancel', { operationId: row.operationId || recordId(row) }, '取消本次准备')}</div>}</Collection>
  </>;
  if (domain === 'teams' || domain === 'groups') return <Card title={title} description={domain === 'teams' ? '团队定义、共同消息、成员任务和交接来自同一运行；成员返回不等于共同交付。' : '群、参与者、任务、材料与完成投递各自保留真实来源。'}>
    <Collection data={data} keys={domain === 'teams' ? ['teams', 'definitions'] : ['groups']} label={domain === 'teams' ? '团队' : '群'}>{row => <>
      <Facts items={[[ '成员数', Array.isArray(row.members) ? row.members.length : undefined ], ['版本', row.revision ], ['来源', first(row, ['sourceLabel', 'originKind']) ]]} />
      <div className="pw-form-actions">{act(domain + '.open', { id: recordId(row), expectedRevision: row.revision }, '打开共同工作')}{act(domain + '.reviewDefinition', { id: recordId(row), expectedRevision: row.revision }, '查看成员与范围')}</div>
    </>}</Collection>
    <Collection data={data} keys={['runs', 'taskRequests']} label="当前工作">{row => <div className="pw-form-actions">{row.taskId && act('tasks.open', { id: row.taskId }, '打开原任务')}{act(domain + '.openRun', { runId: row.runId || recordId(row), expectedGeneration: row.generation, expectedRevision: row.revision }, '查看真实消息与成果')}</div>}</Collection>
  </Card>;
  if (domain === 'media' || domain === 'realtime') return <Card title={title} description="请求准备、实际提交、等待结果与未知提交分开；不会因界面动效宣称已完成。">
    <Facts items={[[ '提供方', first(data, ['provider', 'providerName']) ], ['凭据', typeof data.configured === 'boolean' ? data.configured ? '已配置' : '未配置' : undefined], ['当前会话', first(object(data.session), ['state', 'stage']) ]]} />
    {domain === 'realtime' && <div className="pw-form-actions">{act('realtime.requestMicrophonePermission', {}, '麦克风权限')}{act('realtime.requestCameraPermission', {}, '摄像头权限')}{act('realtime.prepareCall', {}, '准备通话', true)}{act('realtime.disconnect', { sessionId: data.session?.id }, '结束当前通话')}</div>}
    <Collection data={data} keys={domain === 'media' ? ['jobs', 'operations'] : ['invitations', 'sessions', 'voiceTasks']} label={domain === 'media' ? '原媒体任务' : '通话与语音任务'}>{row => <>
      <Facts items={[[ '原提交', row.operationId ], ['阶段', row.stage ], ['关联任务', row.taskId ]]} />
      <div className="pw-form-actions">{row.taskId && act('tasks.open', { id: row.taskId }, '打开原任务')}{act(domain === 'media' ? 'media.fetchResults' : 'realtime.inspect', { operationId: row.operationId || recordId(row), sessionId: row.sessionId || row.id }, domain === 'media' ? '核对原结果' : '核对当前会话')}{act(domain === 'media' ? 'media.reconcileOperation' : 'realtime.reconcile', { operationId: row.operationId || recordId(row), sessionId: row.sessionId || row.id }, '核对未决状态')}</div>
    </>}</Collection>
  </Card>;
  if (domain === 'proxy') return <Card title="本机模型服务" description="本机端点、路由和近期调用；服务状态由手机实际运行回执提供。">
    <Facts items={[[ '服务', typeof data.running === 'boolean' ? data.running ? '运行中' : '未运行' : undefined ], ['端点', data.endpoint ], ['协议', data.protocol ], ['模型', data.model ], ['令牌', data.tokenConfigured ? '已配置' : undefined]]} />
    <div className="pw-form-actions">{act('proxy.status', {}, '刷新状态')}{act('proxy.reviewStart', {}, '核对启动配置')}{act('proxy.stop', {}, '停止服务')}</div>
    <Collection data={data} keys={['recentInferenceReferences']} label="近期实际调用">{row => <div className="pw-form-actions">{act('tasks.open', { id: row.taskId || row.id }, '查看实际调用')}</div>}</Collection>
  </Card>;
  if (domain === 'local') return <Card title="本机开发" description="运行时、项目与构建沿原手机工作区；准备中、失败和可用分别保留。">
    <Facts items={[[ '运行时', first(object(data.runtime), ['status', 'message']) ], ['工作区', first(data, ['workspace', 'path']) ], ['环境', first(data, ['backend', 'executionMode']) ]]} />
    <Collection data={data} keys={['projects', 'environments']} label="项目与环境">{row => <div className="pw-form-actions">{act('projects.select', { id: recordId(row) }, '打开原项目')}</div>}</Collection>
    <div className="pw-form-actions">{act('local.inspectRuntime', {}, '核对运行时')}{act('local.readReceipts', {}, '读取原运行回执')}</div>
  </Card>;
  return <Card title={title}><Empty>尚未读取此能力的当前记录。</Empty></Card>;
}

function DataManagement({ kind, data, supported, run }: { kind: 'archives' | 'storage'; data: Data; supported: (action: string) => boolean; run: Run }) {
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const rows = list(rowsAt(data, ['groups', 'items', 'inventory']));
  const prefix = kind === 'archives' ? 'archives' : 'storage';
  const toggle = (id: string, checked: boolean) => setSelected(current => { const next = new Set(current); if (checked) next.add(id); else next.delete(id); return next; });
  return <Card title={kind === 'archives' ? '备份与迁移' : '项目存储'} description={kind === 'archives' ? '选择原数据分区，先核对归档与恢复影响，再确认当前阶段。' : '读取真实占用，选择清理对象；保留策略只生成建议，不自动删除。'}>
    <div className="pw-form-actions"><CapabilityAction action={prefix + '.refresh'} supported={supported} run={run}>刷新清单</CapabilityAction>{kind === 'archives' && <CapabilityAction action="archives.chooseImport" supported={supported} run={run}>选择备份文件</CapabilityAction>}</div>
    {!hasRows(data, ['groups', 'items', 'inventory']) ? <Empty>尚未读取原数据清单。</Empty> : rows.length === 0 ? <Empty>当前清单没有可显示的分区。</Empty> : <div className="pw-selection-list">{rows.map(row => <div key={recordId(row)} className="pw-selection-object"><CheckboxField isSelected={selected.has(recordId(row))} onChange={checked => toggle(recordId(row), checked)} isDisabled={row.cleanable === false && kind === 'storage'}>{first(row, ['name', 'title', 'id'])}</CheckboxField><Facts items={[[ '占用', first(row, ['displayUsage', 'usageLabel']) ], ['记录数', row.count ], ['完整性', first(row, ['integrity', 'status']) ]]} /></div>)}</div>}
    <div className="pw-form-actions"><CapabilityAction action={prefix + '.reviewSelected'} args={{ selection: Array.from(selected), expectedRevision: data.revision }} supported={supported} run={run} disabled={selected.size === 0} primary>{kind === 'archives' ? '核对备份选择' : '核对清理影响'}</CapabilityAction></div>
    {data.action && <Facts items={[[ '原操作', data.action.id ], ['状态', statusText(data.action.status) ], ['结果', data.action.message ]]} />}
    <p className="pw-field-hint">勾选不是确认执行；结果未知时只能核对原操作，不自动重新提交。</p>
  </Card>;
}

export default function Secondary({ route, onBack, snapshot }: Props) {
  const state = object(snapshot.state);
  const commands = snapshot.capabilities?.commands || [];
  const supported = (action: string) => commands.includes(action);
  const normalize = (next: string) => (next || 'settings').replace(/^connections[:/]/, '').replace(/^settings[:/]/, '');
  const [page, setPage] = useState(normalize(route));
  const [query, setQuery] = useState(''), [notice, setNotice] = useState(''), [error, setError] = useState('');
  const mounted = useRef(true), generation = useRef(0);
  useEffect(() => { setPage(normalize(route)); }, [route]);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; generation.current++; }; }, []);
  const run: Run = async (action, args = {}) => {
    if (!supported(action)) throw new Error('当前页面尚未开放此操作。');
    const current = generation.current; setError('');
    try {
      const value = await bridge.command(action, args);
      if (mounted.current && generation.current === current && object(value).accepted === true) setNotice('请求已登记，等待原操作回执。');
      return value;
    } catch (failure) {
      if (mounted.current && generation.current === current) setError(failure instanceof Error ? failure.message : String(failure));
      throw failure;
    }
  };
  const navigate = (next: string) => { generation.current++; setNotice(''); setError(''); setPage(next); };
  const domain = SECONDARY_DOMAINS.find(x => x.id === page);
  const canEnter = supported('connections.enter'), canLeave = supported('connections.leave');
  useEffect(() => {
    if (!domain || !canEnter) return;
    void bridge.command('connections.enter', { section: page }).catch(failure => { if (mounted.current) setError(failure instanceof Error ? failure.message : String(failure)); });
    return () => { if (canLeave) void bridge.command('connections.leave', { section: page }).catch(() => {}); };
  }, [page, canEnter, canLeave]);
  useEffect(() => {
    if ((page === 'archives' || page === 'storage') && supported(page + '.open')) {
      void bridge.command(page + '.open', {}).catch(() => {});
      return () => { if (supported(page + '.close')) void bridge.command(page + '.close', {}).catch(() => {}); };
    }
  }, [page, commands.join('|')]);
  const canOpenBackground = supported('settings.openSystem'), canCloseBackground = supported('background.close');
  useEffect(() => {
    if (page !== 'background' || !canOpenBackground) return;
    void bridge.command('settings.openSystem', { section: 'background' }).catch(failure => { if (mounted.current) setError(failure instanceof Error ? failure.message : String(failure)); });
    return () => { if (canCloseBackground) void bridge.command('background.close', {}).catch(() => {}); };
  }, [page, canOpenBackground, canCloseBackground]);
  const feature = object(object(state.features)[page === 'commitments' ? 'automation' : page]);
  const owner = object(object(state.ownerSnapshots)[page]);
  const specialized = page === 'models' ? object(state.modelConnections) : page === 'devices' ? object(state.deviceWorkspace) : {};
  const data = { ...feature, ...owner, ...specialized };
  const domainError = text(object(state.ownerErrors)[page]) || Object.entries(object(state.moduleErrors)).filter(([key]) => key.startsWith(page + '.')).map(([, value]) => text(value)).filter(Boolean).join('\n');
  const pageTitle = page === 'settings' || page === 'connections' ? '设置与连接' : page === 'model-settings' ? '模型设置' : page === 'phone' ? '手机能力' : page === 'background' ? '后台运行设置' : page === 'history' ? '任务历史' : page === 'usage' ? '模型调用记录' : page === 'archives' ? '备份与迁移' : page === 'storage' ? '项目存储' : domain?.title || '设置与连接';
  const filtered = useMemo(() => SECONDARY_DOMAINS.filter(item => (item.title + item.detail).includes(query.trim())), [query]);
  const back = () => page === 'settings' || page === 'connections' ? onBack() : navigate(page === 'background' ? 'phone' : 'settings');
  return <section className="pw-secondary" aria-label={pageTitle}>
    <header className="pw-secondary-header"><IconButton icon={RiArrowLeftLine} aria-label="返回" onClick={back} /><div><h1>{pageTitle}</h1><p>{domain?.detail || '同一工作引擎，清楚的对象与真实回执'}</p></div><IconButton icon={RiRefreshLine} aria-label="刷新当前状态" disabled={!supported(page === 'background' ? 'background.refresh' : 'app.refresh')} onClick={() => { void run(page === 'background' ? 'background.refresh' : 'app.refresh', {}).catch(() => {}); }} /></header>
    <div className="pw-secondary-scroll">
      {notice && <p className="pw-request-notice" role="status">{notice}</p>}
      {(error || domainError) && <p className="pw-field-error" role="alert">{error || domainError}</p>}
      {(page === 'settings' || page === 'connections') ? <>
        <Card title="常用设置"><div className="pw-settings-links">{[['model-settings', '模型设置', '连接参数、输入能力与回复预算', RiKey2Line], ['phone', '手机能力', '观察、执行、后台、通知与语音', RiSmartphoneLine], ['history', '任务历史', '原任务、操作与结果记录', RiFileList3Line], ['usage', '模型调用记录', '实际调用、统计未知项与用量', RiFileList3Line], ['archives', '备份与迁移', '选择、核对、导出与恢复原操作', RiDatabase2Line], ['storage', '项目存储', '真实占用、保留建议与清理审阅', RiDatabase2Line]].map(([id, title, detail, Icon]) => {
          const Glyph = Icon as typeof RiKey2Line;
          return <Button key={String(id)} variant="ghost" leadingIcon={Glyph} trailingIcon={RiArrowRightSLine} className="pw-domain-link h-auto whitespace-normal" onClick={() => navigate(String(id))}><span><span>{String(title)}</span><small>{String(detail)}</small></span></Button>;
        })}</div></Card>
        <Card title="连接与能力"><SearchField aria-label="筛选连接与能力" placeholder="筛选连接与能力" value={query} onChange={setQuery} shape="rounded" />
          <Accordion defaultExpandedKeys={['模型与连接', '记忆与主动任务']} variant="divided">{Array.from(new Set(SECONDARY_DOMAINS.map(item => item.group))).map(group => <AccordionItem key={group} id={group} title={group}>
            <div className="pw-settings-links">{filtered.filter(item => item.group === group).map(item => <Button key={item.id} variant="ghost" leadingIcon={item.icon} trailingIcon={RiArrowRightSLine} className="pw-domain-link h-auto whitespace-normal" onClick={() => navigate(item.id)}><span><span>{item.title}</span><small>{item.detail}</small></span></Button>)}</div>
          </AccordionItem>)}</Accordion>{filtered.length === 0 && <Empty>没有匹配的入口。</Empty>}
        </Card>
      </> : page === 'model-settings' ? <ModelSettings state={state} supported={supported} run={run} navigate={navigate} />
        : page === 'phone' ? <PhoneSettings state={state} supported={supported} run={run} navigate={navigate} />
        : page === 'background' ? <BackgroundSettings state={state} supported={supported} run={run} navigate={navigate} />
        : page === 'history' || page === 'usage' ? <HistoryPage key={page} usage={page === 'usage'} supported={supported} run={run} />
        : page === 'archives' || page === 'storage' ? <DataManagement key={page} kind={page} data={object(page === 'archives' ? state.ownerArchives : state.projectStorage)} supported={supported} run={run} />
        : domain ? <DomainPage key={page} domain={page} state={state} data={data} supported={supported} run={run} />
        : <Empty>此入口未找到，可返回连接与能力。</Empty>}
    </div>
  </section>;
}
