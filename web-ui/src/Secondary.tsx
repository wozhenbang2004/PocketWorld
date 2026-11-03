import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import {
  RiAddLine, RiArrowLeftLine, RiArrowRightSLine, RiCheckLine, RiComputerLine, RiDatabase2Line,
  RiFileList3Line, RiKey2Line, RiLink, RiMicLine, RiPuzzle2Line, RiRefreshLine,
  RiSettings3Line, RiShieldCheckLine, RiSmartphoneLine, RiTeamLine,
} from '@remixicon/react';
import { bridge } from './bridge';
import { ModelPicker, PressButton } from './library';
import { Tabs, TabList, Tab, TabPanel } from './shell-library';
import {
  Accordion, AccordionItem, Button, CheckboxField, HostFileUpload,
  IconButton, RadioOptions, SearchField, SelectField, SettingsDialog,
  StretchSwitch, TextAreaField, TextField, type FieldOption,
} from './forms-library';
import './secondary.css';
import './secondary-refactor.css';
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
type BackHandlerRegistration = (handler: (() => boolean) | null) => void;
type Props = { route: string; onBack: () => void; snapshot: Snapshot; registerBackHandler?: BackHandlerRegistration };
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
  const title = first(item, titleKeys, '未命名记录');
  const summary = first(item, ['description', 'summary', 'message', 'text']);
  const status = text(item.status || item.state || (typeof item.enabled === 'boolean' ? item.enabled ? 'enabled' : 'disabled' : ''));
  return <article className="pw-source-record"><header><h3>{title}</h3>{status && <span>{statusText(status)}</span>}</header>
    {summary && summary !== title && <p>{summary}</p>}
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

type ModelDraft = { url: string; model: string; provider: string; screenshots: boolean; supportsImageInput: boolean; maxOutputTokens: string };
// This explicit allowlist is the only model-edit state retained by Secondary.
// The API key, transport consent and review objects never enter this cache.
const ordinaryModelDraft = (value: ModelDraft): ModelDraft => ({
  url: value.url, model: value.model, provider: value.provider, screenshots: value.screenshots,
  supportsImageInput: value.supportsImageInput, maxOutputTokens: value.maxOutputTokens,
});
function ModelSettings({ state, supported, run, adding = false, draft, onDraftChange, registerTransientBack }: {
  state: Data; supported: (action: string) => boolean; run: Run; adding?: boolean;
  draft?: ModelDraft; onDraftChange: (draft: ModelDraft | null) => void; registerTransientBack: BackHandlerRegistration;
}) {
  const [form, setForm] = useState(() => ({
    ...(draft || {
      url: adding ? '' : text(state.baseUrl), model: adding || state.modelRoute === 'account' ? '' : text(state.modelName),
      provider: adding || state.modelRoute === 'account' ? 'openai_chat' : text(state.modelProvider, 'openai_chat'),
      screenshots: state.screenshots !== false, supportsImageInput: Boolean(state.supportsImageInput),
      maxOutputTokens: text(state.maxOutputTokens, '4096'),
    }), apiKey: '',
  }));
  const [dirty, setDirty] = useState(Boolean(draft));
  const savedRevision = useRef(Number(state.modelSavedRevision) || 0);
  const editRevision = useRef(0);
  const submitted = useRef<{ editRevision: number; apiKey: string } | null>(null);
  const [transport, setTransport] = useState<Data | null>(null);
  const [formError, setFormError] = useState('');
  const [requesting, setRequesting] = useState(false);
  const [consentTarget, setConsentTarget] = useState(adding ? '' : text(state.cleartextConsent));
  const alive = useRef(true);
  const clearSecret = () => { editRevision.current++; submitted.current = null; setTransport(null); setForm(current => ({ ...current, apiKey: '' })); };
  useEffect(() => {
    registerTransientBack(transport ? () => { clearSecret(); return true; } : null);
    return () => registerTransientBack(null);
  }, [transport, registerTransientBack]);
  useEffect(() => {
    alive.current = true;
    const hide = () => { if (document.visibilityState !== 'visible') clearSecret(); };
    document.addEventListener('visibilitychange', hide);
    window.addEventListener('pagehide', clearSecret);
    return () => { alive.current = false; submitted.current = null; document.removeEventListener('visibilitychange', hide); window.removeEventListener('pagehide', clearSecret); };
  }, []);
  useEffect(() => {
    const revision = Number(state.modelSavedRevision) || 0;
    if (revision > savedRevision.current) {
      savedRevision.current = revision;
      const original = submitted.current;
      submitted.current = null;
      if (original) {
        if (original.editRevision === editRevision.current) { setDirty(false); onDraftChange(null); }
        setForm(current => current.apiKey === original.apiKey ? { ...current, apiKey: '' } : current);
      }
    }
    if (!adding && (!dirty || revision > savedRevision.current)) setForm(current => ({
      ...current, url: text(state.baseUrl), model: state.modelRoute === 'account' ? '' : text(state.modelName),
      provider: state.modelRoute === 'account' ? 'openai_chat' : text(state.modelProvider, 'openai_chat'),
      screenshots: state.screenshots !== false, supportsImageInput: Boolean(state.supportsImageInput), maxOutputTokens: text(state.maxOutputTokens, '4096'),
    }));
  }, [state.baseUrl, state.modelName, state.modelProvider, state.modelRoute, state.modelSavedRevision, dirty]);
  const change = (key: keyof typeof form, value: string | boolean) => {
    editRevision.current++;
    const next = { ...form, [key]: value };
    if (key === 'provider' || key === 'url') { setConsentTarget(''); setTransport(null); next.apiKey = ''; }
    if (key === 'provider' || key === 'url' || key === 'model') next.supportsImageInput = false;
    setDirty(true); setForm(next); onDraftChange(ordinaryModelDraft(next));
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
        const originalEdit = editRevision.current;
        const review = object(await run('settings.reviewModelTransport', { provider: form.provider, url: form.url }));
        if (!alive.current || originalEdit !== editRevision.current) return;
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
    } catch (error) { if (alive.current) setFormError(error instanceof Error ? error.message : String(error)); }
    finally { if (alive.current) setRequesting(false); }
  };
  return <div data-model-layer="edit" className="pw-model-edit-layer">
    <section className="pw-model-editor" aria-label={adding ? '新连接配置' : '当前连接配置'}>
      <p className="pw-model-editor-context">{adding ? '保存后使用这项 API 配置。已有工作保留原连接。' : '修改当前 API 配置；测试连接不会切换模型。'}</p>
      <section className="pw-model-form-section" aria-label="连接身份"><h2>连接</h2><div className="pw-form-grid">
        <SelectField label="接口协议" options={providers} value={form.provider} onChange={value => change('provider', value)} />
        <TextField label="接口地址" type="url" value={form.url} onChange={value => change('url', value)} placeholder="https://…" autoComplete="off" />
        <TextField label="模型名称" value={form.model} onChange={value => change('model', value)} autoComplete="off" />
      </div></section>
      <section className="pw-model-form-section" aria-label="连接凭据"><h2>凭据</h2><div className="pw-form-grid">
        <TextField label="API 密钥" type="password" value={form.apiKey} onChange={value => change('apiKey', value)} autoComplete="new-password"
          hint={!adding && state.hasApiKey && form.provider === state.modelProvider && form.url.trim() === state.baseUrl ? '此协议与地址已有密钥；留空继续使用原密钥。' : '更换协议或地址时，请填写对应的密钥。'} />
      </div></section>
      <Accordion className="pw-model-advanced" variant="divided">
        <AccordionItem id="advanced" title="高级设置" description="输出上限、图像输入与截图">
          <div className="pw-form-grid">
            <TextField label="每次回复的输出上限" value={form.maxOutputTokens} onChange={value => change('maxOutputTokens', value.replace(/\D/g, '').slice(0, 5))} inputMode="numeric"
              isDisabled={form.provider === 'openai_chat' || Boolean(state.modelCatalogEnabled)} hint={state.modelCatalogEnabled ? '在模型目录中核对精确参数和容量。' : form.provider === 'openai_chat' ? 'Chat 兼容协议的输出限制沿用服务端配置。' : '128–32768 tokens'} />
            <CheckboxField isSelected={form.supportsImageInput} onChange={value => change('supportsImageInput', value)} isDisabled={Boolean(state.modelCatalogEnabled) || state.modelRoute === 'account'}>此模型支持图像输入</CheckboxField>
            <StretchSwitch label="允许按需发送截图" description="优先读取结构树；仅在已配置图像能力且确有需要时发送像素。" checked={form.screenshots} onChange={value => change('screenshots', value)} />
          </div>
        </AccordionItem>
      </Accordion>
      {formError && <p role="alert" className="pw-field-error">{formError}</p>}
      {state.modelTest && <p className="pw-receipt-copy" aria-live="polite">{text(state.modelTest)}</p>}
      {state.modelTestTaskId && <CapabilityAction action="tasks.open" supported={supported} run={run} args={{ id: state.modelTestTaskId }}>查看本次实际调用与用量</CapabilityAction>}
      {state.error && <p className="pw-field-error" role="alert">{text(state.error)}</p>}
      <div className="pw-form-actions pw-model-save-actions"><Button variant="secondary" disabled={requesting || state.modelTesting || !supported('settings.testModel')} onClick={() => request('settings.testModel')}>{state.modelTesting ? '测试中…' : '测试连接'}</Button><PressButton compact disabled={requesting || state.modelSaving || !supported('settings.saveModel')} onClick={() => request('settings.saveModel')}>{state.modelSaving ? '保存中…' : state.modelRoute === 'account' ? '保存并切换 API 模型' : '保存连接'}</PressButton></div>
    </section>
    <SettingsDialog isOpen={Boolean(transport)} title="允许这个明文模型连接？" onClose={clearSecret}>
      {transport && <><Facts items={[[ '协议', providers.find(x => x.id === transport.provider)?.label ], ['地址', transport.displayTarget || transport.url]]} />
        <p>凭据、文本和按需截图将通过 HTTP 明文传输。更换协议或地址后需要重新核对。</p>
        <div className="pw-form-actions"><Button variant="secondary" onClick={clearSecret}>取消</Button><PressButton compact onClick={async () => { const reviewed = transport; setTransport(null); if (reviewed.provider !== form.provider || reviewed.url !== form.url) return; setConsentTarget(text(reviewed.consentTarget)); try { await perform(reviewed.action, text(reviewed.consentTarget)); } catch (error) { setFormError(error instanceof Error ? error.message : String(error)); } }}>允许此协议与地址</PressButton></div>
      </>}
    </SettingsDialog>
  </div>;
}

type ModelView = { kind: 'list' | 'connections' | 'edit' | 'add' | 'connection' | 'catalog' | 'model'; id?: string };
const modelConnectionRows = (data: Data) => Array.isArray(data.connections)
  ? list(data.connections) : list(object(data.connections).connections);
const MODEL_PROVIDERS: Record<string, string> = { openai_chat: 'OpenAI Chat', openai_responses: 'OpenAI Responses', anthropic: 'Anthropic', gemini: 'Gemini' };

/** The BoardUI SettingsModal row recipe keeps browsing separate from editing.
 * Only the current API configuration has a Web write capability. Saved profile
 * rows remain readable; they must never be submitted as the current connection. */
function ModelConnections({ state, data, supported, run, navigate, open }: { state: Data; data: Data; supported: (action: string) => boolean; run: Run; navigate: (route: string) => void; open: (view: ModelView) => void }) {
  const profiles = modelConnectionRows(data);
  const selectedId = text(object(data.connections).selectedConnectionId);
  const selectedConnection = profiles.find(row => recordId(row) === selectedId);
  const account = state.modelRoute === 'account';
  const configured = Boolean(text(state.baseUrl).trim());
  return <section className="pw-model-connections" data-model-layer="connections" aria-label="连接列表">
      <p className="pw-reading-lead">连接提供模型。选择一项，查看账号或修改 API 配置。</p>
      <div className="pw-settings-links">
        {configured && <Button variant="ghost" aria-label="编辑当前 API 连接" leadingIcon={RiLink} trailingIcon={RiArrowRightSLine} className="pw-domain-link h-auto whitespace-normal" onClick={() => open({ kind: 'edit' })}>
          <span><span>{first(selectedConnection || {}, ['name', 'label'], 'API 连接设置')}</span><small>{account ? '保留的连接' : '当前使用'}{' · '}{state.hasApiKey ? '凭据已保存' : '地址、凭据与能力'}</small></span>
        </Button>}
        {profiles.filter(row => recordId(row) !== selectedId || !configured).map((row, index) => <Button key={recordId(row, String(index))} variant="ghost" leadingIcon={RiLink} trailingIcon={RiArrowRightSLine} className="pw-domain-link h-auto whitespace-normal" onClick={() => open({ kind: 'connection', id: recordId(row) })}>
          <span><span>{first(row, ['name', 'label', 'id'], '已保存连接')}</span><small>{MODEL_PROVIDERS[text(row.provider)] || first(row, ['provider', 'protocol'], 'API')}{row.enabled === false ? ' · 已停用' : ' · 已保存'}</small></span>
        </Button>)}
        <Button variant="ghost" leadingIcon={RiKey2Line} trailingIcon={RiArrowRightSLine} className="pw-domain-link h-auto whitespace-normal" onClick={() => navigate('accounts')}><span><span>ChatGPT 账号</span><small>授权、账号模型与套餐用量</small></span></Button>
      </div>
      {account && configured && <div className="pw-form-actions"><CapabilityAction action="settings.useApiKeyModel" supported={supported} run={run} disabled={Boolean(state.modelSaving)}>使用保留的 API 模型</CapabilityAction></div>}
      {!configured && profiles.length === 0 && <Empty>还没有 API 连接。添加后可以测试并保存。</Empty>}
      <div className="pw-connection-add"><Button variant="secondary" leadingIcon={RiAddLine} disabled={!supported('settings.saveModel')} onClick={() => open({ kind: 'add' })}>添加 API 连接</Button></div>
  </section>;
}

function ModelConnectionDetail({ connection }: { connection: Data | undefined }) {
  return connection ? <section className="pw-model-connection-detail" aria-label="连接详情">
    <Facts items={[[ '名称', first(connection, ['name', 'label', 'id']) ], ['协议', first(connection, ['provider', 'protocol']) ], ['模型', connection.model], ['地址', first(connection, ['baseUrl', 'endpoint']) ], ['状态', connection.enabled === false ? '已停用' : '已启用'], ['凭据', connection.hasApiKey || connection.credentialsConfigured ? '已配置' : '未配置']]} />
    <p className="pw-field-hint">此页面可查看已保存连接。当前版本尚未接入这项连接的独立编辑与切换。</p>
  </section> : <Empty>这项连接已不在当前目录，请返回后刷新。</Empty>;
}

const modelFactText = (value: any) => {
  const fact = object(value);
  if (fact.known !== true) return '未知';
  if (typeof fact.value === 'boolean') return fact.value ? '支持' : '不支持';
  if (Array.isArray(fact.value)) return fact.value.join('、');
  if (fact.value && typeof fact.value === 'object') return text(fact.value.tokens, '未知') + (fact.value.tokens ? ' tokens' : '');
  return text(fact.value, '未知');
};

function ModelCatalog({ state, data, selectedId, active = true, supported, run, open, registerTransientBack }: { state: Data; data: Data; selectedId?: string; active?: boolean; supported: (action: string) => boolean; run: Run; open: (view: ModelView) => void; registerTransientBack: BackHandlerRegistration }) {
  const [modelReview, setModelReview] = useState<Data | null>(null);
  const [reviewing, setReviewing] = useState(false), [selecting, setSelecting] = useState(false);
  const alive = useRef(true), request = useRef(0);
  useEffect(() => {
    if (!active) return;
    registerTransientBack(modelReview ? () => { setModelReview(null); return true; } : null);
    return () => registerTransientBack(null);
  }, [modelReview, registerTransientBack, active]);
  useEffect(() => { if (!active) { request.current++; setModelReview(null); setReviewing(false); } }, [active]);
  useEffect(() => { alive.current = true; return () => { alive.current = false; request.current++; }; }, []);
  const models = list(data.models);
  const model = models.find(row => first(row, ['modelId', 'id', 'name']) === selectedId);
  const locked = Boolean(state.modelSelectionBusy || state.chatBusy || state.chatSubmitting || state.phoneSubmitting || state.modelSaving || state.modelTesting) || state.activeTaskId != null || selecting;
  const canSelect = supported('models.review') && supported('models.select') && Boolean(data.binding);
  const prepare = async (id: string) => {
    if (reviewing || selecting) return;
    if (locked || !canSelect) { open({ kind: 'model', id }); return; }
    const attempt = ++request.current;
    setReviewing(true);
    try {
      const review = object(await run('models.review', { modelId: id }));
      if (alive.current && attempt === request.current && typeof review.reviewHandle === 'string') setModelReview(review);
    } catch { /* The page retains the host error; no choice has been committed. */ }
    finally { if (alive.current && attempt === request.current) setReviewing(false); }
  };
  const reviewedModel = models.find(row => first(row, ['modelId', 'id', 'name']) === text(modelReview?.modelId));
  const modelFacts = (record: Data) => <Facts items={[[ '上下文容量', modelFactText(object(record.facts).CONTEXT_WINDOW) ], ['输出上限', modelFactText(object(record.facts).OUTPUT_TOKENS) ], ['图像输入', modelFactText(object(record.facts).IMAGE_INPUT) ], ['工具调用', modelFactText(object(record.facts).TOOL_CALLS) ], ['目录状态', record.catalogFresh ? '已读取' : '未读取或已过期']]} />;
  return <>
    <section className="pw-model-catalog" hidden={Boolean(selectedId)} aria-label="API 模型目录">
      {models.length > 0 ? <ModelPicker label="选择工作模型" value={state.modelRoute === 'account' ? undefined : text(state.modelName)} models={models.map(row => {
        const id = first(row, ['modelId', 'id', 'name']);
        const current = state.modelRoute !== 'account' && id === text(state.modelName);
        return { id, name: first(row, ['name', 'label', 'modelId', 'id']), description: current ? '当前使用 · 查看能力' : locked || !canSelect ? '查看能力与参数' : row.catalogFresh ? '查看能力并切换' : '目录待更新 · 选择前核对', disabled: reviewing || selecting };
      })} onChange={id => { if (state.modelRoute !== 'account' && id === text(state.modelName)) open({ kind: 'model', id }); else void prepare(id); }} />
        : <div className="pw-model-directory-empty"><h2>{Array.isArray(data.models) ? '目录中还没有模型' : state.baseUrl ? '正在读取模型目录' : '先连接一个模型'}</h2><p>{state.baseUrl ? '可以刷新目录，或在管理连接中填写模型名称。' : '在管理连接中添加 API，或授权你的 ChatGPT 账号。'}</p></div>}
      {(reviewing || selecting) && <p className="pw-field-hint" role="status">{selecting ? '正在提交模型选择…' : '正在核对模型能力与连接…'}</p>}
      {locked && <p className="pw-field-hint">当前工作结束或设置保存后可切换模型。你仍可查看模型能力。</p>}
      {!canSelect && models.length > 0 && <p className="pw-field-hint">当前目录可供查看；完成连接配置后再切换模型。</p>}
    </section>
    {selectedId && (model ? <section className="pw-model-detail" aria-label="模型能力">
      {modelFacts(model)}
      <div className="pw-form-actions"><Button variant="secondary" disabled={!canSelect || reviewing || locked || (state.modelRoute !== 'account' && selectedId === text(state.modelName))} onClick={() => { void prepare(selectedId); }}>{state.modelRoute !== 'account' && selectedId === text(state.modelName) ? '正在使用此模型' : reviewing ? '核对中…' : '选择此模型'}</Button></div>
      {locked && <p className="pw-field-hint">当前工作结束或设置保存后可切换模型。</p>}
    </section> : <Empty>这个模型已不在当前目录，请返回重新选择。</Empty>)}
    <SettingsDialog isOpen={active && modelReview !== null} title="切换工作模型" onClose={() => setModelReview(null)}>{modelReview && <>
      <div className="pw-model-switch-summary"><span className="pw-field-hint">{text(state.modelName, '尚未选择')} →</span><h3>{text(modelReview.modelId)}</h3></div>
      {reviewedModel && modelFacts(reviewedModel)}
      <Facts items={[[ '本次输出上限', modelReview.outputTokens ], ['图像预算预留', modelReview.imageTokenReserve ]]} />
      <p className="pw-field-hint">确认后切换模型；连接凭据保持不变。</p>
      <div className="pw-form-actions"><Button variant="secondary" onClick={() => setModelReview(null)}>保留当前模型</Button><PressButton compact disabled={!supported('models.select') || locked} onClick={async () => {
        const review = modelReview;
        setModelReview(null); setSelecting(true);
        try { await run('models.select', { reviewHandle: review.reviewHandle }); }
        catch { /* Consumed or unknown reviews must not be replayed. */ }
        finally { if (alive.current) setSelecting(false); }
      }}>确认切换</PressButton></div>
    </>}</SettingsDialog>
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

/** BoardUI disclosures keep the record itself primary and its review actions local. */
function MemoryRecords({ domain, data, supported, run }: { domain: string; data: Data; supported: (action: string) => boolean; run: Run }) {
  const [query, setQuery] = useState('');
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const keys = domain === 'memory' ? ['records'] : ['records', 'memories', 'candidates', 'jobs'];
  const loaded = hasRows(data, keys);
  const bodyOf = (row: Data) => first(row, ['text', 'content', 'body', 'summary', 'description', 'message']);
  const titleOf = (row: Data) => first(row, ['title', 'name']) || bodyOf(row).split('\n')[0].slice(0, 72) || '未命名记录';
  const layerOf = (row: Data) => ({ fact: '事实', preference: '偏好', episodic: '经历', procedural: '方法' } as Record<string, string>)[text(row.layer)] || text(row.layer) || '其他记录';
  const sourceOf = (row: Data) => first(row, ['source', 'sourceLabel', 'sourceKind']);
  const contextOf = (row: Data) => [sourceOf(row), row.core === true ? '核心记忆' : '', row.status || row.state ? statusText(row.status || row.state) : row.active === false ? '已停用' : ''].filter(Boolean).join(' · ');
  const records = list(rowsAt(data, keys)).filter(row => [titleOf(row), bodyOf(row), layerOf(row), sourceOf(row), recordId(row)].join(' ').toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()));
  const layers = [...new Set(records.map(layerOf))];
  return <section className="pw-memory-collection" aria-label={domain === 'memory' ? '记住的内容' : '待核对内容'}>
    <SearchField aria-label="筛选当前读取的记录" placeholder={domain === 'memory' ? '查找记住的内容' : '查找待核对内容'} value={query} onChange={setQuery} shape="rounded" />
    <p className="pw-reading-lead">{domain === 'memory' ? '按内容类型阅读。展开一条，核对内容和来源。' : '先阅读内容与来源，再决定是否采用。'}</p>
    {!loaded ? <Empty>尚未读取当前记录。</Empty> : !records.length ? <Empty>{query.trim() ? '当前读取窗口没有匹配的内容。' : '当前读取窗口没有记录。'}</Empty> : layers.map(layer => <section className="pw-memory-category" key={layer} aria-label={layer}>
      <h2>{layer}</h2>
      <Accordion variant="divided" expandedKeys={expanded} onExpandedChange={values => setExpanded(new Set([...values].map(String)))} allowsMultipleExpanded>
        {records.filter(row => layerOf(row) === layer).map((row, index) => <AccordionItem key={recordId(row, String(index))} id={`${layer}:${recordId(row, String(index))}`} className="pw-memory-record" headingLevel={3}
          title={titleOf(row)} description={contextOf(row) ? <span className="pw-memory-source">{contextOf(row)}</span> : undefined}>
          <div className="pw-memory-reading">
            {bodyOf(row) && <p className="pw-reading-copy">{bodyOf(row)}</p>}
            <Facts items={[[ '内容类型', layerOf(row) ], ['来源', sourceOf(row) ], ['所属用户', row.ownerId ], ['记忆空间', row.spaceId ], ['版本', row.revision]]} />
            <div className="pw-record-decisions"><CapabilityAction action={domain + '.review'} args={{ id: recordId(row), expectedRevision: row.revision }} supported={supported} run={run}>{domain === 'memory' ? '核对与纠正' : '核对这条内容'}</CapabilityAction><CapabilityAction action={domain + '.openSource'} args={{ id: recordId(row), expectedRevision: row.revision }} supported={supported} run={run}>查看来源</CapabilityAction></div>
          </div>
        </AccordionItem>)}
      </Accordion>
    </section>)}
    {domain === 'learning' && <p className="pw-field-hint">经验导入后保持停用；完整阅读当前 SKILL.md 并取得真实阅读回执后，才允许启用。</p>}
  </section>;
}

/** BoardUI Tabs and object rows separate shared work from team administration. */
function TeamWorkspace({ domain, data, supported, run, registerTransientBack }: { domain: string; data: Data; supported: (action: string) => boolean; run: Run; registerTransientBack: BackHandlerRegistration }) {
  const [tab, setTab] = useState<'runs' | 'definitions'>('runs');
  const [queries, setQueries] = useState({ runs: '', definitions: '' });
  const [selected, setSelected] = useState<{ kind: 'runs' | 'definitions'; id: string } | null>(null);
  const root = useRef<HTMLElement>(null), listPositions = useRef({ runs: 0, definitions: 0 });
  const runKeys = ['runs', 'taskRequests'], definitionKeys = domain === 'teams' ? ['teams', 'definitions'] : ['groups'];
  const runs = list(rowsAt(data, runKeys)), definitions = list(rowsAt(data, definitionKeys));
  const titleOf = (row: Data, kind = tab) => first(row, kind === 'runs' ? ['goal', 'title', 'name', 'label'] : ['name', 'title', 'label', 'goal'], '未命名记录');
  const summaryOf = (row: Data) => first(row, ['summary', 'message', 'description', 'text']);
  const membersOf = (row: Data) => list(row.members).map(member => first(member, ['name', 'label', 'role'])).filter(Boolean).join('、');
  const sourceOf = (row: Data) => first(row, ['teamName', 'groupName', 'sourceLabel', 'originKind']);
  const objects = tab === 'runs' ? runs : definitions;
  const filtered = objects.filter(row => [titleOf(row), summaryOf(row), sourceOf(row), membersOf(row)].join(' ').toLocaleLowerCase().includes(queries[tab].trim().toLocaleLowerCase()));
  const current = selected ? (selected.kind === 'runs' ? runs : definitions).find((row, index) => recordId(row, String(index)) === selected.id) : undefined;
  const remember = () => { listPositions.current[tab] = root.current?.closest<HTMLElement>('.pw-secondary-scroll')?.scrollTop || 0; };
  useEffect(() => {
    registerTransientBack(selected ? () => { setSelected(null); return true; } : null);
    return () => registerTransientBack(null);
  }, [selected, registerTransientBack]);
  useEffect(() => {
    const scroll = root.current?.closest<HTMLElement>('.pw-secondary-scroll');
    if (scroll) scroll.scrollTop = selected ? 0 : listPositions.current[tab];
  }, [selected, tab]);
  const act = (action: string, args: Data, label: string, primary = false) => <CapabilityAction action={action} args={args} supported={supported} run={run} primary={primary}>{label}</CapabilityAction>;
  return <section ref={root} className="pw-team-workspace" data-team-layer={selected ? 'detail' : 'list'}>
    {selected ? current ? <article className="pw-team-reading">
      <header><p className="pw-detail-location">{selected.kind === 'runs' ? '共同工作' : domain === 'teams' ? '团队' : '群'}{sourceOf(current) ? ` · ${sourceOf(current)}` : ''}</p><h2>{titleOf(current, selected.kind)}</h2>{(current.status || current.state) && <p className="pw-detail-status">{statusText(current.status || current.state)}</p>}</header>
      {summaryOf(current) && summaryOf(current) !== titleOf(current, selected.kind) && <p className="pw-reading-copy">{summaryOf(current)}</p>}
      {selected.kind === 'definitions' && <section className="pw-team-members" aria-label="当前已读取成员"><h3>成员</h3>{list(current.members).length ? <ul>{list(current.members).map((member, index) => <li key={recordId(member, String(index))}><span>{first(member, ['name', 'label', 'id'], '未命名成员')}</span>{member.role && <small>{text(member.role)}</small>}</li>)}</ul> : <Empty>当前记录没有提供成员详情。</Empty>}</section>}
      <Facts items={[[ '版本', current.revision ], ['工作代次', selected.kind === 'runs' ? current.generation : undefined ]]} />
      <div className="pw-object-decisions">{selected.kind === 'runs' ? <>{act(domain + '.openRun', { runId: current.runId || recordId(current), expectedGeneration: current.generation, expectedRevision: current.revision }, '查看消息与成果', true)}{current.taskId && act('tasks.open', { id: current.taskId }, '打开原任务')}</> : <>{act(domain + '.open', { id: recordId(current), expectedRevision: current.revision }, '打开共同工作', true)}{act(domain + '.reviewDefinition', { id: recordId(current), expectedRevision: current.revision }, '查看成员与范围')}</>}</div>
    </article> : <Empty>这项记录已不在当前读取窗口，请返回后刷新。</Empty> : <Tabs selectedKey={tab} onSelectionChange={value => { if (value === 'runs' || value === 'definitions') { remember(); setTab(value); } }} className="pw-team-tabs">
      <TabList aria-label={domain === 'teams' ? '团队视图' : '群协作视图'}><Tab id="runs">共同工作</Tab><Tab id="definitions">{domain === 'teams' ? '团队' : '群'}</Tab></TabList>
      <TabPanel id={tab}>
        <SearchField aria-label={tab === 'runs' ? '搜索共同工作' : '搜索团队与群'} placeholder={tab === 'runs' ? '查找目标或共同工作' : '查找名称或成员'} value={queries[tab]} onChange={value => setQueries(current => ({ ...current, [tab]: value }))} shape="rounded" />
        <div className="pw-object-list">{!hasRows(data, tab === 'runs' ? runKeys : definitionKeys) ? <Empty>尚未读取{tab === 'runs' ? '共同工作' : '团队记录'}。</Empty> : !filtered.length ? <Empty>{queries[tab].trim() ? '当前读取窗口没有匹配项。' : tab === 'runs' ? '当前读取窗口没有共同工作。' : '当前读取窗口没有团队记录。'}</Empty> : filtered.map(row => {
          const position = objects.indexOf(row), id = recordId(row, String(position));
          const context = tab === 'runs' ? sourceOf(row) : membersOf(row) || sourceOf(row);
          return <Button key={id} variant="ghost" trailingIcon={RiArrowRightSLine} className="pw-object-row h-auto whitespace-normal" onClick={() => { remember(); setSelected({ kind: tab, id }); }}>
            <span className="pw-object-row-copy"><span className="pw-object-row-title">{titleOf(row)}</span>{(row.status || row.state) && <small className="pw-object-row-status">{statusText(row.status || row.state)}</small>}{context && <small>{context}</small>}{tab === 'runs' && summaryOf(row) && summaryOf(row) !== titleOf(row) && <span className="pw-object-row-summary">{summaryOf(row)}</span>}</span>
          </Button>;
        })}</div>
      </TabPanel>
    </Tabs>}
  </section>;
}

function DomainPage({ domain, state, data, supported, run, registerTransientBack }: { domain: string; state: Data; data: Data; supported: (action: string) => boolean; run: Run; registerTransientBack: BackHandlerRegistration }) {
  const [entry, setEntry] = useState<Data | null>(null);
  const [draft, setDraft] = useState<Data>({});
  const clearProfileSecrets = () => setDraft(current => ({ ...current, token: '', password: '', privateKey: '', passphrase: '' }));
  const closeProfile = () => { clearProfileSecrets(); setEntry(null); };
  useEffect(() => {
    if (domain === 'teams' || domain === 'groups') return;
    registerTransientBack(entry ? () => { closeProfile(); return true; } : null);
    return () => registerTransientBack(null);
  }, [entry, registerTransientBack, domain]);
  useEffect(() => {
    if (domain !== 'ssh' && domain !== 'remote') return;
    const hide = () => { if (document.visibilityState !== 'visible') clearProfileSecrets(); };
    document.addEventListener('visibilitychange', hide);
    window.addEventListener('pagehide', clearProfileSecrets);
    return () => { document.removeEventListener('visibilitychange', hide); window.removeEventListener('pagehide', clearProfileSecrets); };
  }, [domain]);
  const update = (key: string, value: any) => setDraft(current => ({ ...current, [key]: value }));
  const act = (action: string, args: Data, label: string, primary = false) => <CapabilityAction action={action} args={args} supported={supported} run={run} primary={primary}>{label}</CapabilityAction>;
  const title = SECONDARY_DOMAINS.find(x => x.id === domain)?.title || '连接与能力';
  const mcpPick = object(object(object(state.ownerResponses).mcp).pickConfig);
  const pickedMcpConfig = object(object(mcpPick.result).config);
  const mcpPickPending = Array.isArray(state.ownerPending) && state.ownerPending.includes('mcp.pickConfig');
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
  if (domain === 'memory' || domain === 'memoryReview' || domain === 'learning') return <MemoryRecords domain={domain} data={data} supported={supported} run={run} />;
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
  if (domain === 'teams' || domain === 'groups') return <TeamWorkspace domain={domain} data={data} supported={supported} run={run} registerTransientBack={registerTransientBack} />;
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

const SETTINGS_GROUPS = [
  { id: 'models', title: '模型与连接', detail: '当前模型、API 与 ChatGPT 账号', icon: RiKey2Line, entries: ['model-settings', 'accounts'] },
  { id: 'devices', title: '设备与执行', detail: '手机、电脑、服务器与本机环境', icon: RiComputerLine, entries: ['phone', 'devices', 'remote', 'ssh', 'local'] },
  { id: 'work', title: '工作与数据', detail: '历史、调用记录、存储与备份', icon: RiFileList3Line, entries: ['history', 'usage', 'storage', 'archives'] },
  { id: 'memory', title: '记忆与主动任务', detail: '私人记忆、学习、提醒与跟进', icon: RiDatabase2Line, entries: ['memory', 'memoryReview', 'learning', 'automation', 'commitments'] },
  { id: 'extensions', title: '协作与扩展', detail: '渠道、团队、技能、插件与工具', icon: RiPuzzle2Line, entries: ['channels', 'teams', 'groups', 'skills', 'plugins', 'pluginCatalog', 'mcp'] },
  { id: 'media', title: '语音与媒体', detail: '语音文件、实时通话与本机服务', icon: RiMicLine, entries: ['media', 'realtime', 'proxy'] },
];
const COMMON_SETTINGS = [
  { id: 'model-settings', title: '选择模型', detail: '模型能力、API 与账号连接', icon: RiKey2Line },
  { id: 'phone', title: '手机能力', detail: '观察、执行、后台与通知', icon: RiSmartphoneLine },
  { id: 'history', title: '任务历史', detail: '查看原任务与结果', icon: RiFileList3Line },
  { id: 'usage', title: '模型调用记录', detail: '查看实际调用与用量', icon: RiFileList3Line },
  { id: 'archives', title: '备份与迁移', detail: '导出或恢复原数据', icon: RiDatabase2Line },
  { id: 'storage', title: '项目存储', detail: '占用与清理审阅', icon: RiDatabase2Line },
];

export default function Secondary({ route, onBack, snapshot, registerBackHandler }: Props) {
  const state = object(snapshot.state);
  const commands = snapshot.capabilities?.commands || [];
  const supported = (action: string) => commands.includes(action);
  const normalize = (next: string) => (next || 'settings').replace(/^connections[:/]/, '').replace(/^settings[:/]/, '');
  const [page, setPage] = useState(normalize(route));
  const [modelView, setModelView] = useState<ModelView>({ kind: 'list' });
  const modelTrail = useRef<ModelView[]>([]);
  const modelDrafts = useRef<Partial<Record<'edit' | 'add', ModelDraft>>>({});
  const transientBack = useRef<(() => boolean) | null>(null);
  const registerTransientBack = useCallback<BackHandlerRegistration>(handler => { transientBack.current = handler; }, []);
  const [query, setQuery] = useState(''), [notice, setNotice] = useState(''), [error, setError] = useState('');
  const [expandedSettings, setExpandedSettings] = useState<Set<string>>(new Set());
  const trail = useRef<Array<{ page: string; query: string; modelView: ModelView; modelTrail: ModelView[] }>>([]), scroll = useRef<HTMLDivElement>(null), scrollPositions = useRef(new Map<string, number>());
  const mounted = useRef(true), generation = useRef(0);
  useEffect(() => { generation.current++; trail.current = []; modelTrail.current = []; setPage(normalize(route)); setModelView({ kind: 'list' }); setNotice(''); setError(''); }, [route]);
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
  const rememberScroll = () => scrollPositions.current.set(page + ((page === 'models' || page === 'model-settings') ? ':' + modelView.kind + ':' + (modelView.id || '') : ''), scroll.current?.scrollTop || 0);
  const navigate = (next: string) => { rememberScroll(); generation.current++; trail.current.push({ page, query, modelView, modelTrail: [...modelTrail.current] }); modelTrail.current = []; setNotice(''); setError(''); setQuery(''); setModelView({ kind: 'list' }); setPage(next); };
  const openModel = (view: ModelView) => { rememberScroll(); generation.current++; modelTrail.current.push(modelView); setNotice(''); setError(''); setModelView(view); };
  const isModelPage = page === 'models' || page === 'model-settings';
  const domain = SECONDARY_DOMAINS.find(x => x.id === page);
  const hostSection = isModelPage ? 'models' : domain?.id;
  const canEnter = supported('connections.enter'), canLeave = supported('connections.leave');
  useEffect(() => {
    if (!hostSection || !canEnter) return;
    let current = true;
    void bridge.command('connections.enter', { section: hostSection }).catch(failure => { if (mounted.current && current) setError(failure instanceof Error ? failure.message : String(failure)); });
    return () => { current = false; if (canLeave) void bridge.command('connections.leave', { section: hostSection }).catch(() => {}); };
  }, [hostSection, canEnter, canLeave]);
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
  const dataPage = isModelPage ? 'models' : page;
  const feature = object(object(state.features)[dataPage === 'commitments' ? 'automation' : dataPage]);
  const owner = object(object(state.ownerSnapshots)[dataPage]);
  const specialized = isModelPage ? object(state.modelConnections) : page === 'devices' ? object(state.deviceWorkspace) : {};
  // MemoryModule.snapshot is summary-only. Its actual list reply lives in
  // moduleResults; an in-flight or failed reread must not present an old list.
  const memoryListReady = !object(state.moduleErrors)['memory.list'] && !(Array.isArray(state.moduleBusy) && state.moduleBusy.includes('memory.list'));
  const memoryList = memoryListReady ? object(object(state.moduleResults)['memory.list']) : {};
  const data = { ...feature, ...owner, ...specialized, ...(page === 'memory' ? { records: memoryList.records } : {}) };
  const domainError = text(object(state.ownerErrors)[dataPage]) || Object.entries(object(state.moduleErrors)).filter(([key]) => key.startsWith(dataPage + '.')).map(([, value]) => text(value)).filter(Boolean).join('\n');
  const settingsGroup = SETTINGS_GROUPS.find(group => page === 'group:' + group.id);
  const connection = modelConnectionRows(data).find(row => recordId(row) === modelView.id);
  const pageTitle = isModelPage ? ({ list: '模型与连接', connections: '管理连接', edit: '编辑 API 连接', add: '添加 API 连接', connection: first(connection || {}, ['name', 'label'], '已保存连接'), catalog: '模型目录', model: modelView.id || '模型详情' }[modelView.kind])
    : settingsGroup?.title || (page === 'settings' || page === 'connections' ? '设置' : page === 'phone' ? '手机能力' : page === 'background' ? '后台运行设置' : page === 'history' ? '任务历史' : page === 'usage' ? '模型调用记录' : page === 'archives' ? '备份与迁移' : page === 'storage' ? '项目存储' : domain?.title || '设置');
  const settingsEntries = [...COMMON_SETTINGS, ...SECONDARY_DOMAINS.filter(item => item.id !== 'models')];
  const filtered = settingsEntries.filter(item => (item.title + item.detail).toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()));
  const frequentSettings = [
    { id: 'model-settings', title: '当前模型', detail: text(state.modelName) || '连接模型，开始工作', icon: RiKey2Line },
    { id: 'phone', title: '手机能力', detail: '权限、后台连接与执行方式', icon: RiSmartphoneLine },
  ];
  const viewKey = page + (isModelPage ? ':' + modelView.kind + ':' + (modelView.id || '') : '');
  const modelBrowseActive = ['list', 'catalog', 'model'].includes(modelView.kind);
  const modelParent = modelTrail.current.at(-1)?.kind;
  const modelBackLabel = modelParent === 'connections' ? '返回连接管理' : modelParent === 'catalog' ? '返回模型目录' : '返回模型与连接';
  useEffect(() => {
    const element = scroll.current;
    if (!element) return;
    element.scrollTop = scrollPositions.current.get(viewKey) || 0;
  }, [viewKey]);
  const consumeBack = () => {
    if (transientBack.current?.()) return true;
    rememberScroll();
    if (isModelPage && modelView.kind !== 'list') { generation.current++; setNotice(''); setError(''); setModelView(modelTrail.current.pop() || { kind: 'list' }); return true; }
    const previous = trail.current.pop();
    if (previous) { generation.current++; setNotice(''); setError(''); setPage(previous.page); setQuery(previous.query); modelTrail.current = previous.modelTrail; setModelView(previous.modelView); return true; }
    if (page === normalize(route)) return false;
    if (page === 'settings' || page === 'connections') return false;
    generation.current++; setPage(page === 'background' ? 'phone' : 'settings'); setModelView({ kind: 'list' }); return true;
  };
  // Both the Android back event and the visible back button consume the same
  // current layer. Only the settings root delegates to the enclosing app.
  const latestBack = useRef(consumeBack);
  latestBack.current = consumeBack;
  useEffect(() => {
    registerBackHandler?.(() => latestBack.current());
    return () => registerBackHandler?.(null);
  }, [registerBackHandler]);
  const back = () => { if (!consumeBack()) onBack(); };
  const settingRows = (entries: Array<{ id: string; title: string; detail: string; icon: typeof RiKey2Line }>) => <div className="pw-settings-links">{entries.map(item => <Button key={item.id} variant="ghost" leadingIcon={item.icon} trailingIcon={RiArrowRightSLine} className="pw-domain-link h-auto whitespace-normal" onClick={() => navigate(item.id)}><span><span>{item.title}</span><small>{item.detail}</small></span></Button>)}</div>;
  const refreshAction = page === 'background' ? 'background.refresh' : isModelPage ? 'models.snapshot' : page === 'memory' ? 'memory.list' : 'app.refresh';
  return <section className="pw-secondary pw-secondary-refactor" data-secondary-page={page} aria-label={pageTitle}>
    <header className="pw-secondary-header"><IconButton icon={RiArrowLeftLine} aria-label={isModelPage && modelView.kind !== 'list' ? modelBackLabel : '返回'} onClick={back} /><div><h1>{pageTitle}</h1></div><IconButton icon={RiRefreshLine} aria-label="刷新当前状态" disabled={!supported(refreshAction)} onClick={() => { void run(refreshAction, {}).catch(() => {}); }} /></header>
    <div className="pw-secondary-scroll" ref={scroll}>
      {notice && <p className="pw-request-notice" role="status">{notice}</p>}
      {(error || domainError) && <p className="pw-field-error" role="alert">{error || domainError}</p>}
      <div className="pw-secondary-page" data-secondary-view={viewKey}>
        {(page === 'settings' || page === 'connections') ? <>
          <SearchField aria-label="搜索设置" placeholder="搜索设置" value={query} onChange={setQuery} shape="rounded" />
          {query.trim() ? <section className="pw-settings-results" aria-label="匹配的设置">{settingRows(filtered)}{filtered.length === 0 && <Empty>没有匹配的设置。</Empty>}</section>
            : <><section className="pw-settings-frequent" aria-label="常用设置">{settingRows(frequentSettings)}</section><Accordion variant="divided" className="pw-settings-groups" aria-label="按目的浏览设置" expandedKeys={expandedSettings} onExpandedChange={values => setExpandedSettings(new Set([...values].map(String)))} allowsMultipleExpanded>{SETTINGS_GROUPS.map(group => <AccordionItem id={group.id} key={group.id} title={group.title} description={group.detail} icon={group.icon} headingLevel={2} className="pw-settings-group">{settingRows(group.entries.map(id => settingsEntries.find(item => item.id === id)!).filter(Boolean))}</AccordionItem>)}</Accordion></>}
        </> : settingsGroup ? <section className="pw-settings-group-page">{settingRows(settingsGroup.entries.map(id => settingsEntries.find(item => item.id === id)!).filter(Boolean))}</section>
          : isModelPage ? <>
            <div className="pw-model-list" data-model-layer={modelView.kind === 'model' ? 'model' : 'list'} hidden={!modelBrowseActive}>
              {modelView.kind !== 'model' && <header className="pw-model-current"><div><span className="pw-model-eyebrow">当前模型</span><strong>{text(state.modelName) || '尚未连接'}</strong><span className="pw-model-origin">{state.modelRoute === 'account' ? 'ChatGPT 账号' : MODEL_PROVIDERS[text(state.modelProvider)] || 'API 连接'}</span></div><Button variant="ghost" leadingIcon={RiSettings3Line} onClick={() => openModel({ kind: 'connections' })}>管理连接</Button></header>}
              {modelView.kind !== 'model' && state.modelRoute === 'account' && <div className="pw-account-model-entry"><Button variant="secondary" trailingIcon={RiArrowRightSLine} onClick={() => navigate('accounts')}>打开账号模型</Button><p className="pw-field-hint">下面是保留的 API 模型目录。</p></div>}
              <ModelCatalog key={page} state={state} data={data} active={modelBrowseActive} selectedId={modelView.kind === 'model' ? modelView.id : undefined} supported={supported} run={run} open={openModel} registerTransientBack={registerTransientBack} />
            </div>
            {modelView.kind === 'connections' ? <ModelConnections state={state} data={data} supported={supported} run={run} navigate={navigate} open={openModel} />
              : modelView.kind === 'edit' || modelView.kind === 'add' ? <ModelSettings key={modelView.kind} state={state} supported={supported} run={run} adding={modelView.kind === 'add'} draft={modelDrafts.current[modelView.kind]} onDraftChange={value => { if (value) modelDrafts.current[modelView.kind as 'edit' | 'add'] = value; else delete modelDrafts.current[modelView.kind as 'edit' | 'add']; }} registerTransientBack={registerTransientBack} />
              : modelView.kind === 'connection' ? <ModelConnectionDetail connection={connection} /> : null}
          </>
          : page === 'phone' ? <PhoneSettings state={state} supported={supported} run={run} navigate={navigate} />
          : page === 'background' ? <BackgroundSettings state={state} supported={supported} run={run} navigate={navigate} />
          : page === 'history' || page === 'usage' ? <HistoryPage key={page} usage={page === 'usage'} supported={supported} run={run} />
          : page === 'archives' || page === 'storage' ? <DataManagement key={page} kind={page} data={object(page === 'archives' ? state.ownerArchives : state.projectStorage)} supported={supported} run={run} />
          : domain ? <DomainPage key={page} domain={page} state={state} data={data} supported={supported} run={run} registerTransientBack={registerTransientBack} />
          : <Empty>此入口未找到，可返回设置。</Empty>}
      </div>
    </div>
  </section>;
}
