import { ModelDiagnosticsWorkspace } from './ModelDiagnosticsWorkspace';
import { UsageWorkspace } from './UsageWorkspace';
import { AICenter, type AIServicePreset } from './AICenter';
import SkillsWorkspace from './SkillsWorkspace';
import { CapabilitiesWorkspace } from './CapabilitiesWorkspace';
import { toolServicePresets } from './tool-service-presets';
import type { PermissionLeaveGuard } from './PermissionPolicySettings';
import { BackgroundAudioSettings, BackgroundOwnerSettings, BackgroundChecks } from './BackgroundOwnerSettings';
import ChannelsWorkspace from './ChannelsWorkspace';
import { useCallback, useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { IconAccount, IconAdd, IconArchive, IconArrowLeft, IconCall, IconCheck, IconChevronRight, IconDevices, IconDownload, IconFileSettings, IconFileText, IconGoal, IconKey, IconLearning, IconLink, IconMcp, IconMedia, IconMemory, IconMicrophone, IconMonitor, IconNotification, IconObserve, IconPalette, IconPhone, IconPlugin, IconPluginCatalog, IconPrivacy, IconRefresh, IconReminder, IconReview, IconServer, IconSettings, IconShieldCheck, IconSkill, IconStorage, IconTeam, IconTerminal, IconUpload } from './icons';
import { bridge } from './bridge';
import type {SetupCapability} from './setup-contract';
import ReminderWorkspace from './ReminderWorkspace';
import ConnectionWorkspace from './ConnectionWorkspace';
import { AppearanceSettings, SystemSpeechSettings } from './PresentationSettings';
import { TypographySettings } from './TypographySettings';
import {LocalWorkspace,type LocalWorkspaceDraft} from './LocalWorkspace';
import {MemoryWorkspace} from './MemoryWorkspace';
import {LearningWorkspace, type LearningCandidateTarget} from './LearningWorkspace';
import {TeamsWorkspace} from './TeamsWorkspace';
import {GroupsWorkspace} from './GroupsWorkspace';
import {MediaWorkspace} from './MediaWorkspace';
import {RealtimeWorkspace} from './RealtimeWorkspace';
import {ProxyWorkspace} from './ProxyWorkspace';
import {CommitmentsWorkspace} from './CommitmentsWorkspace';
import {TokenBudgetsWorkspace} from './TokenBudgetsWorkspace';
import {WorkProfilesWorkspace} from './WorkProfilesWorkspace';
import {DevicesWorkspace} from './DevicesWorkspace';
import {PluginsWorkspace} from './PluginsWorkspace';
import { ModelPicker, PressButton } from './library';
import { useSurfaceEnter } from './library/surface-motion';
import { Tabs, TabList, Tab, TabPanel } from './shell-library';
import {
  Accordion, AccordionItem, Button, CheckboxField, HostFileUpload,
  IconButton, RadioOptions, SearchField, SelectField, SettingsDialog,
  StretchSwitch, TextAreaField, TextField, type FieldOption,
} from './forms-library';
import './secondary.css';
import './secondary-refactor.css';
import './model-data-settings.css';
import './phone-workspace.css';
import './settings-depth.css';
import './workspace-navigation.css';
import { normalizeWorkspaceRoute, workspaceForRoute, workspacePageForRoute } from './workspace-directory';
import { DataOperationHeading, DataOperationRow, useDataSettingsJourney } from './DataSettingsJourney';
import { backgroundConnection, modelConnectionCommand, wirelessEndpoints, wirelessPort } from './forms-library/host-contracts';
import { ModelConnectionOptions, ModelConnectionTestFeedback } from './ModelConnectionOptions';
import { compatibilityDraft, compatibilityNeedsSave, compatibilityOptions, connectionTestResult, type CompatibilityDraft, type ConnectionTestDraft } from './model-connection-policy';
import { ACCOUNT_RECEIPT_ACTIONS, accountActionReceipt, accountLoginArguments, accountLoginRecovery, accountStatusText, accountActionFailure } from './account-actions';

/**
 * Product composition of the selected BoardUI SettingsModal/settings rows,
 * actual BoardUI field sources and the approved press/stretch switch.
 * Source originals, per-file hashes and adaptation records are in forms-library.
 * This module only dispatches named capabilities published by the Android host.
 * A command acknowledgement is never treated as a saved setting or approval.
 */
type Data = Record<string, any>;
type Snapshot = { state?: Data; view?: { section?: string | null; resumeRevision?: number; taskId?: string | null }; composer?: Data | null; capabilities?: { commands?: string[] } };
type BackHandlerRegistration = (handler: (() => boolean) | null) => void;
type Props = { teamAttentionFocus?: Data; onTeamAttentionConsumed?:(requestId:string)=>void; registerBeforeLeave?: (guard: PermissionLeaveGuard | null) => void; route: string; onBack: () => void; snapshot: Snapshot; registerBackHandler?: BackHandlerRegistration; navigationTrigger?: ReactNode; navigationRevision?: number; onNavigationPageChange?: (page: string) => void; onOpenSetup?: (capability?: SetupCapability) => void; onReturnToSetup?: () => void; onOpenProjects?: () => void; originGoal?: string; returnLabel?: string; initialProjectId?: string; localDraft?: LocalWorkspaceDraft; onLocalDraftChange?: (draft: LocalWorkspaceDraft) => void };
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
  { id: 'models', title: 'AI 接入', detail: '服务、账号、模型与调度', group: '设置', icon: IconKey },
  { id: 'workProfiles', title: '工作配置', detail: '模型、技能与工具来源，恢复后重新绑定', group: '技能与工具', icon: IconFileSettings },
  { id: 'accounts', title: 'ChatGPT 账号', detail: '授权、模型目录与套餐用量', group: '设置', icon: IconAccount },
  { id: 'devices', title: '我的设备', detail: '手机与电脑、配对、接收和权限', group: '设备连接', icon: IconDevices },
  { id: 'remote', title: '电脑与服务器', detail: 'Companion 环境与原任务', group: '设备连接', icon: IconMonitor },
  { id: 'ssh', title: 'SSH 工作区', detail: '主机公钥、文件与终端', group: '设备连接', icon: IconTerminal },
  { id: 'local', title: '本机开发', detail: '运行时、项目、依赖与执行回执', group: '设备连接', icon: IconPhone },
  { id: 'memory', title: '记忆', detail: '来源、作用域与正式记录', group: '记忆', icon: IconMemory },
  { id: 'memoryReview', title: '待审候选', detail: '核对来源、接受、纠正和遗忘', group: '记忆', icon: IconReview },
  { id: 'learning', title: '学习与经验', detail: '从已审记忆整理方法，审阅后启用', group: '记忆', icon: IconLearning },
  { id: 'automation', title: '提醒', detail: '时间、目标和原发生回执', group: '自动化', icon: IconReminder },
  { id: 'commitments', title: '委托与目标', detail: '期限、静默时段与真实跟进回执', group: '自动化', icon: IconGoal },
  { id: 'channels', title: '消息渠道', detail: 'QQ、微信、主人绑定与接收', group: '团队', icon: IconLink },
  { id: 'skills', title: '技能', detail: '正文、来源、版本与启用状态', group: '技能与工具', icon: IconSkill },
  { id: 'plugins', title: '插件', detail: '已安装工具、范围与运行状态', group: '技能与工具', icon: IconPlugin },
  { id: 'pluginCatalog', title: '我的插件目录', detail: '安装、源码历史与恢复审阅', group: '技能与工具', icon: IconPluginCatalog },
  { id: 'mcp', title: 'MCP 工具', detail: '配置导入、权限预览和授权', group: '技能与工具', icon: IconMcp },
  { id: 'teams', title: '团队', detail: '成员、共同对话和工作分配', group: '团队', icon: IconTeam },
  { id: 'groups', title: '群协作', detail: '参与者、任务、材料与成果投递', group: '团队', icon: IconTeam },
  { id: 'media', title: '生成与文件', detail: '提供方、原提交和结果附件', group: '媒体', icon: IconMedia },
  { id: 'realtime', title: '实时通话', detail: '邀请、当前会话与音视频权限', group: '媒体', icon: IconCall },
  { id: 'proxy', title: '本机 API', detail: '访问地址、客户端连接与近期调用', group: '设置', icon: IconServer },
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
function SourceRecord({ item, titleKeys = ['name', 'title', 'goal', 'label', 'id'], children, formatStatus = statusText }: { item: Data; titleKeys?: string[]; children?: ReactNode; formatStatus?: (value: any) => string }) {
  const title = first(item, titleKeys, '未命名记录');
  const summary = first(item, ['description', 'summary', 'message', 'text']);
  const status = text(item.status || item.state || (typeof item.enabled === 'boolean' ? item.enabled ? 'enabled' : 'disabled' : ''));
  return <article className="pw-source-record"><header><h3>{title}</h3>{status && <span>{formatStatus(status)}</span>}</header>
    {summary && summary !== title && <p>{summary}</p>}
    {children}
  </article>;
}
function CapabilityAction({ action, supported, run, args = {}, children, primary = false, disabled = false, row = false, icon }: { action: string; supported: (action: string) => boolean; run: Run; args?: Data; children: ReactNode; primary?: boolean; disabled?: boolean; row?: boolean; icon?: typeof IconKey }) {
  const [waiting, setWaiting] = useState(false);
  const click = async () => { setWaiting(true); try { await run(action, args); } catch { /* The page keeps the real error visible. */ } finally { setWaiting(false); } };
  const inactive = disabled || waiting || !supported(action);
  return primary ? <PressButton compact disabled={inactive} onClick={click} title={!supported(action) ? '当前页面尚未开放此操作' : undefined}>{waiting ? '处理中…' : children}</PressButton>
    : <Button variant={row ? "ghost" : "secondary"} className={row ? "pw-domain-link h-auto whitespace-normal" : undefined} leadingIcon={icon} trailingIcon={row ? IconChevronRight : undefined} disabled={inactive} onClick={click} title={!supported(action) ? '当前页面尚未开放此操作' : undefined}>{waiting ? '处理中…' : children}</Button>;
}

type ModelDraft = { name: string; url: string; model: string; provider: string; bedrockRegion: string; expiresAt: string; screenshots: boolean; supportsImageInput: boolean; maxOutputTokens: string; compatibility: CompatibilityDraft };
// This explicit allowlist is the only model-edit state retained by Secondary.
// Secret fields, transport consent and review objects never enter this cache.
const ordinaryModelDraft = (value: ModelDraft): ModelDraft => ({
  name: value.name, url: value.url, model: value.model, provider: value.provider, screenshots: value.screenshots,
  bedrockRegion: value.bedrockRegion, expiresAt: value.expiresAt,
  supportsImageInput: value.supportsImageInput, maxOutputTokens: value.maxOutputTokens,
  compatibility: {
    enabled: value.compatibility.enabled, tokenField: value.compatibility.tokenField, streamUsage: value.compatibility.streamUsage,
    toolWireShape: value.compatibility.toolWireShape, schemaSubset: value.compatibility.schemaSubset, maxTools: value.compatibility.maxTools,
    maxRequestBytes: value.compatibility.maxRequestBytes, cacheMode: value.compatibility.cacheMode, cacheSupport: value.compatibility.cacheSupport,
  },
});
function ModelSettings({ state, connection, supported, run, adding = false, preset, draft, onDraftChange, onSaved, onInspect, registerTransientBack }: {
  state: Data; supported: (action: string) => boolean; run: Run; adding?: boolean;
  connection?: Data; preset?: AIServicePreset; draft?: ModelDraft; onDraftChange: (draft: ModelDraft | null) => void; onSaved: (snapshot: Data) => void; onInspect: () => void; registerTransientBack: BackHandlerRegistration;
}) {
  const original = useRef(!adding && connection ? { ...connection } : undefined);
  const config = object(original.current?.configuration);
  const saveAction = adding ? 'models.connections.add' : 'models.connections.update';
  const [form, setForm] = useState(() => ({
    ...(draft || {
      name: adding ? preset?.name || '' : text(connection?.name),
      url: adding ? preset?.url || '' : text(connection?.baseUrl), model: adding ? '' : text(connection?.model),
      provider: adding ? preset?.provider || 'openai_chat' : text(connection?.provider, 'openai_chat'),
      bedrockRegion: adding ? '' : text(config.bedrockRegion), expiresAt: '',
      screenshots: adding ? state.screenshots !== false : config.sendScreenshots !== false, supportsImageInput: adding ? false : Boolean(config.supportsImageInput),
      maxOutputTokens: text(config.maxOutputTokens, '4096'), compatibility: compatibilityDraft(config.compatibility),
    }), apiKey: '', accessKeyId: '', secretAccessKey: '', sessionToken: '',
  }));
  const editRevision = useRef(0);
  const submitted = useRef<{ editRevision: number } | null>(null);
  const pendingOperation = useRef<'save' | 'test' | 'catalog' | null>(null);
  const ownerGeneration = useRef(0);
  const [transport, setTransport] = useState<Data | null>(null);
  const [formError, setFormError] = useState('');
  const [requesting, setRequesting] = useState(false);
  const [requestedAction, setRequestedAction] = useState('');
  const [unknownSave, setUnknownSave] = useState(false);
  const [editorId] = useState(() => crypto.randomUUID());
  const [testResult, setTestResult] = useState<Data | null>(null);
  const [draftModels, setDraftModels] = useState<Data[] | null>(null);
  const testResultView = useRef<HTMLDivElement>(null);
  const [unknownTest, setUnknownTest] = useState(false);
  const outcomeNotice = useRef<HTMLDivElement>(null);
  const [policyReset, setPolicyReset] = useState(false);
  const bedrock = form.provider === 'bedrock_anthropic';
  const bedrockUrl = /^[a-z]{2}-[a-z]+-\d+$/.test(form.bedrockRegion.trim()) ? `https://bedrock-runtime.${form.bedrockRegion.trim()}.amazonaws.com` : '';
  const awsReplacement = Boolean(form.accessKeyId || form.secretAccessKey || form.sessionToken || form.expiresAt);
  const savedAws = original.current?.provider === 'bedrock_anthropic' && Boolean(original.current?.hasAwsSession);
  const savedAwsExpired = savedAws && (!Number.isFinite(Date.parse(text(original.current?.credentialExpiresAt))) || Date.parse(text(original.current?.credentialExpiresAt)) <= Date.now());
  const bedrockNeedsSave = bedrock && (adding || !savedAws || savedAwsExpired || awsReplacement || form.bedrockRegion.trim() !== config.bedrockRegion || form.model.trim() !== original.current?.model || Number(form.maxOutputTokens) !== config.maxOutputTokens || form.screenshots !== config.sendScreenshots || form.supportsImageInput !== Boolean(config.supportsImageInput));
  const stale = !adding && (!connection || connection.id !== original.current?.id || connection.revision !== original.current?.revision);
  const canRetainApiKey = !adding && !stale && original.current?.hasApiKey === true && form.provider === original.current.provider && form.url.trim() === original.current.baseUrl;
  const apiKeyHint = stale ? '原连接已更新，请返回核对后再编辑密钥。' : canRetainApiKey
    ? form.apiKey.trim() ? '保存后更换原密钥；保存前清空输入可取消更换。' : '留空保留原密钥；填写新密钥并保存后更换。'
    : '请填写这项连接的密钥；新增连接不会借用其他连接的密钥。';
  const currentStale = useRef(stale); currentStale.current = stale;
  useEffect(() => {
    if (!unknownSave && !unknownTest && !stale) return;
    const frame = requestAnimationFrame(() => outcomeNotice.current?.scrollIntoView({ block: 'start' }));
    return () => cancelAnimationFrame(frame);
  }, [unknownSave, unknownTest, stale]);
  useEffect(() => {
    if (!testResult || stale) return;
    let cancelled = false;
    const frame = requestAnimationFrame(() => {
      const view = testResultView.current;
      const transitions = view?.closest('.pw-model-editor')?.getAnimations({ subtree: true })
        .filter(animation => (animation as CSSTransition).transitionProperty === 'height') || [];
      void Promise.allSettled(transitions.map(animation => animation.finished)).then(() => {
        if (!cancelled && view?.isConnected) view.scrollIntoView({ block: 'start' });
      });
    });
    return () => { cancelled = true; cancelAnimationFrame(frame); };
  }, [testResult, stale]);
  const testDraft = (): ConnectionTestDraft => ({ editorId, draftRevision: editRevision.current,
    connectionId: adding ? '' : text(original.current?.id), connectionRevision: adding ? 0 : Number(original.current?.revision) });
  let policyNeedsSave = false, policyError = '';
  try { policyNeedsSave = compatibilityNeedsSave(form.compatibility, original.current, form); }
  catch (error) { policyError = error instanceof Error ? error.message : String(error); }
  const [consentTarget, setConsentTarget] = useState(adding ? '' : text(config.cleartextConsent));
  const alive = useRef(true);
  const clearSecret = () => {
    editRevision.current++; ownerGeneration.current++;
    if (pendingOperation.current === 'save') setUnknownSave(true);
    if (pendingOperation.current === 'test') setUnknownTest(true);
    pendingOperation.current = null; submitted.current = null;
    setTransport(null); setTestResult(null); setDraftModels(null);
    setForm(current => ({ ...current, apiKey: '', accessKeyId: '', secretAccessKey: '', sessionToken: '' }));
  };
  useEffect(() => {
    registerTransientBack(transport ? () => { clearSecret(); return true; } : null);
    return () => registerTransientBack(null);
  }, [transport, registerTransientBack]);
  useEffect(() => {
    alive.current = true;
    const hide = () => { if (document.visibilityState !== 'visible') clearSecret(); };
    const ticket = () => { const snapshot = bridge.getState(); const view = object(snapshot.view); return [bridge.getDocumentEpoch(), view.resumeRevision, view.section, view.taskId].join('|'); };
    let originalTicket = ticket();
    const unsubscribe = bridge.subscribe(() => { const next = ticket(); if (next !== originalTicket) { originalTicket = next; clearSecret(); } });
    document.addEventListener('visibilitychange', hide);
    window.addEventListener('pagehide', clearSecret);
    return () => { alive.current = false; ownerGeneration.current++; pendingOperation.current = null; submitted.current = null; unsubscribe(); document.removeEventListener('visibilitychange', hide); window.removeEventListener('pagehide', clearSecret); };
  }, []);
  const change = (key: keyof Omit<typeof form, 'compatibility'>, value: string | boolean) => {
    if (form[key] === value) return;
    editRevision.current++;
    setTestResult(null); setFormError('');
    const next = { ...form, [key]: value };
    if (key === 'provider' || key === 'url' || key === 'bedrockRegion') { setConsentTarget(''); setTransport(null); next.apiKey = ''; next.accessKeyId = ''; next.secretAccessKey = ''; next.sessionToken = ''; next.expiresAt = ''; }
    if (key === 'provider' || key === 'url' || key === 'bedrockRegion' || key === 'apiKey') setDraftModels(null);
    if (key === 'provider') { if (form.provider === 'bedrock_anthropic' || value === 'bedrock_anthropic') next.url = ''; next.bedrockRegion = ''; }
    if (key === 'provider' || key === 'url' || key === 'model' || key === 'bedrockRegion') next.supportsImageInput = false;
    if (key === 'provider' || key === 'url' || key === 'model' || key === 'bedrockRegion' || ['apiKey', 'accessKeyId', 'secretAccessKey', 'sessionToken'].includes(key) && Boolean(value)) {
      if (form.compatibility.enabled) setPolicyReset(true);
      next.compatibility = compatibilityDraft();
    }
    setForm(next); onDraftChange(ordinaryModelDraft(next));
  };
  const changeCompatibility = (compatibility: CompatibilityDraft) => {
    editRevision.current++; setTestResult(null); setFormError(''); setPolicyReset(false);
    const next = { ...form, compatibility }; setForm(next); onDraftChange(ordinaryModelDraft(next));
  };
  const providers = [
    { id: 'openai_chat', label: 'OpenAI Chat Completions' }, { id: 'openai_responses', label: 'OpenAI Responses' },
    { id: 'anthropic', label: 'Anthropic Messages' }, { id: 'gemini', label: 'Gemini' },
    { id: 'bedrock_anthropic', label: 'AWS Bedrock · Claude Messages' },
  ];
  const perform = async (action: string, consent: string) => {
    if (stale || unknownSave) throw new Error('请先返回连接列表核对原保存结果，再明确打开要编辑的连接。');
    const testing = action === 'models.connection.test';
    const reading = action === 'models.catalog.preview';
    if (testing && unknownTest) throw new Error('原测试结果待核对；本表单不会重复发起测试。');
    if (policyError) throw new Error(policyError);
    if (testing && policyNeedsSave) throw new Error('先保存兼容与缓存设置，再测试原连接。');
    if (testing && bedrockNeedsSave) throw new Error('请先保存原 Bedrock 连接与有效短期凭据，再进行测试。');
    const url = bedrock ? bedrockUrl : form.url.trim();
    const output = Number(form.maxOutputTokens);
    if (!form.name.trim() || !url || (testing || bedrock) && !form.model.trim() || !Number.isInteger(output) || output < 128 || output > 32768) {
      setFormError(testing || bedrock ? '请填写服务名称、接口地址、模型 ID，以及 128–32768 的输出上限。' : '请填写服务名称、接口地址，以及 128–32768 的输出上限。'); return;
    }
    if (bedrock && !['anthropic.claude-sonnet-4-6', 'anthropic.claude-opus-4-6-v1'].includes(form.model.trim())) { setFormError('请填写当前支持的 Sonnet 4.6 或 Opus 4.6 普通模型 ID；不支持 ARN 或推理配置文件。'); return; }
    if (bedrock && awsReplacement && (!form.accessKeyId || !form.secretAccessKey || !form.sessionToken || !form.expiresAt.trim().endsWith('Z') || !Number.isFinite(Date.parse(form.expiresAt)) || Date.parse(form.expiresAt) <= Date.now())) {
      setFormError('替换时请整组填写三项 STS 临时凭据及尚未到期的 UTC 时间。'); return;
    }
    if (bedrock && !awsReplacement && (!savedAws || savedAwsExpired || form.bedrockRegion.trim() !== config.bedrockRegion)) { setFormError('请填写这项区域连接的完整 STS 临时凭据与 UTC 到期时间。'); return; }
    const command = modelConnectionCommand(adding, testing, original.current);
    const testedDraft = testing ? testDraft() : null;
    const catalogDraft = reading ? testDraft() : null;
    if (action === saveAction) submitted.current = { editRevision: editRevision.current };
    const originalGeneration = ownerGeneration.current;
    pendingOperation.current = testing ? 'test' : reading ? 'catalog' : 'save';
    if (testing) setTestResult(null);
    try {
      const payload: Data = { ...command.identity, name: form.name.trim(), url, model: form.model.trim(), provider: form.provider,
        apiKey: bedrock ? '' : form.apiKey, screenshots: form.screenshots, supportsImageInput: form.supportsImageInput, maxOutputTokens: output, cleartextConsent: bedrock ? '' : consent };
      if (bedrock) {
        payload.bedrockRegion = form.bedrockRegion.trim();
        if (awsReplacement) payload.awsSession = { accessKeyId: form.accessKeyId, secretAccessKey: form.secretAccessKey, sessionToken: form.sessionToken, expiresAt: form.expiresAt.trim() };
      } else if (!adding) payload.compatibility = compatibilityOptions(form.compatibility, form.provider);
      if (testedDraft) { payload.editorId = testedDraft.editorId; payload.draftRevision = testedDraft.draftRevision; }
      if (catalogDraft) { payload.editorId = catalogDraft.editorId; payload.draftRevision = catalogDraft.draftRevision; }
      const result = await run(reading ? 'models.catalog.preview' : testing ? command.action : saveAction, payload);
      if (!alive.current || originalGeneration !== ownerGeneration.current) return;
      pendingOperation.current = null;
      if (catalogDraft && catalogDraft.draftRevision === editRevision.current && !currentStale.current) {
        if (result?.format !== 'pocketworld.draft-catalog.v1' || result?.status !== 'succeeded' || result?.saved !== false || result?.selectionChanged !== false || result?.editorId !== catalogDraft.editorId || result?.draftRevision !== catalogDraft.draftRevision || !Array.isArray(result?.models)) throw new Error('目录回执不属于当前服务草稿，请重新核对。');
        setDraftModels(list(result.models).filter(row => typeof row.modelId === 'string' && row.modelId));
      }
      if (testedDraft && alive.current) {
        const originalResult = connectionTestResult(result, testedDraft, testedDraft);
        if (originalResult?.connectionTest.outcome === 'unknown' || !originalResult && !currentStale.current) setUnknownTest(true);
        if (!currentStale.current && testedDraft.draftRevision === editRevision.current) {
          const current = connectionTestResult(result, testedDraft, testDraft());
          setTestResult(current);
          if (!current) setFormError('未能核对本次测试与当前草稿的对应关系，请返回核对连接。');
        }
      }
      if (action === saveAction) {
        if (result?.status !== 'saved') throw new Error('保存回执待核对，请刷新原连接后再决定。');
        const submission = submitted.current; submitted.current = null;
        if (alive.current && submission) {
          if (submission.editRevision === editRevision.current) onDraftChange(null);
          setForm(current => ({ ...current, apiKey: '', accessKeyId: '', secretAccessKey: '', sessionToken: '' }));
          onSaved(result);
        }
      }
    } catch (failure) {
      if (!alive.current || originalGeneration !== ownerGeneration.current) return;
      pendingOperation.current = null;
      if (testedDraft && alive.current && !['not_sent', 'not_accepted', 'invalid_request'].includes(text(object(failure).code))) setUnknownTest(true);
      if (action === saveAction) {
        submitted.current = null;
        if (!['not_sent', 'not_accepted', 'invalid_request'].includes(text(object(failure).code))) {
          setUnknownSave(true); setForm(current => ({ ...current, apiKey: '', accessKeyId: '', secretAccessKey: '', sessionToken: '' }));
        }
      }
      throw failure;
    }
  };
  const request = async (action: string) => {
    if (!supported(action) || requesting) return;
    const requestedDraftRevision = editRevision.current;
    setFormError(''); setRequesting(true); setRequestedAction(action);
    try {
      if (!bedrock && supported('settings.reviewModelTransport')) {
        const originalEdit = editRevision.current;
        const review = object(await run('settings.reviewModelTransport', { provider: form.provider, url: form.url }));
        if (!alive.current || originalEdit !== editRevision.current) return;
        if (review.required && review.consentTarget !== consentTarget) {
          setTransport({ ...review, action, provider: form.provider, url: form.url }); return;
        }
        await perform(action, review.required ? text(review.consentTarget) : '');
      } else {
        if (!bedrock && /^http:\/\//i.test(form.url) && !/^http:\/\/(localhost|127\.0\.0\.1|\[::1\])(?::|\/|$)/i.test(form.url)) {
          setFormError('此明文连接需要先核对当前协议与地址。当前页面尚未提供该核对入口。'); return;
        }
        await perform(action, '');
      }
    } catch (error) { if (alive.current && requestedDraftRevision === editRevision.current) setFormError(error instanceof Error ? error.message : String(error)); }
    finally { if (alive.current) { setRequesting(false); setRequestedAction(''); } }
  };
  const awsFields = <div className="pw-form-grid">
    <TextField label="临时 Access Key ID" type="password" value={form.accessKeyId} onChange={value => change('accessKeyId', value)} autoComplete="new-password" />
    <TextField label="临时 Secret Access Key" type="password" value={form.secretAccessKey} onChange={value => change('secretAccessKey', value)} autoComplete="new-password" />
    <TextField label="Session Token" type="password" value={form.sessionToken} onChange={value => change('sessionToken', value)} autoComplete="new-password" />
    <TextField label="UTC 到期时间" value={form.expiresAt} onChange={value => change('expiresAt', value)} placeholder="2026-11-04T12:00:00Z" autoComplete="off" hint="填写这组 STS 凭据原有的到期时间；过期后需要明确替换整组会话。" />
  </div>;
  const apiKeyField = <TextField label="API 密钥" type="password" value={form.apiKey} onChange={value => change('apiKey', value)} autoComplete="new-password"
    placeholder={stale ? '先返回核对连接' : canRetainApiKey ? '已保存 · 留空保留' : '输入 API 密钥'} hint={apiKeyHint} />;
  return <div data-model-layer="edit" className="pw-model-edit-layer">
    <section className="pw-model-editor" aria-label={adding ? '新连接配置' : '当前连接配置'}>
      <div className="pw-model-editor-scroll">
      <section className="pw-model-form-section" aria-label="服务身份"><div className="pw-form-grid">
        <TextField label="服务名称" value={form.name} onChange={value => change('name', value)} maxLength={80} autoComplete="off" placeholder="便于辨认的名称" />
        {bedrock && <TextField label="AWS 区域" value={form.bedrockRegion} onChange={value => change('bedrockRegion', value)} placeholder="例如 us-east-1" autoComplete="off" />}
        <TextField label={bedrock ? '区域端点（自动生成）' : '接口地址'} type="url" value={bedrock ? bedrockUrl : form.url} onChange={value => { if (!bedrock) change('url', value); }} isReadOnly={bedrock} placeholder={bedrock ? '填写区域后显示' : 'https://…'} autoComplete="off" />
        {bedrock && <TextField label="模型 ID" value={form.model} onChange={value => change('model', value)} autoComplete="off" hint="填写当前账号与区域可用的普通模型 ID，保存后单独测试。" />}
      </div></section>
      <section className="pw-model-form-section" aria-label="连接凭据"><h3>访问凭据</h3>{bedrock ? <>
        <p className="pw-field-hint">使用本人已取得的正式 STS 临时会话。手机不会自动登录 AWS、读取 CLI 凭据或刷新身份。</p>
        {savedAws && form.bedrockRegion.trim() === config.bedrockRegion ? <>
          <Facts items={[[ '状态', savedAwsExpired ? '已过期 · 请替换后保存' : '已保存 · 可用性尚未验证' ], ['到期时间', original.current?.credentialExpiresAt]]} />
          <Accordion key={savedAwsExpired ? 'expired' : 'saved'} defaultExpandedKeys={savedAwsExpired ? ['aws-session'] : []} variant="divided"><AccordionItem id="aws-session" title="替换短期凭据" description="整组替换三项凭据与到期时间">{awsFields}</AccordionItem></Accordion>
        </> : awsFields}
      </> : canRetainApiKey ? <><p className="pw-credential-summary"><IconShieldCheck size={16} aria-hidden="true" />密钥已保存，留空保留原密钥。</p><Accordion variant="divided"><AccordionItem id="api-credential" title="替换 API 密钥" description="仅在明确保存后更换">{apiKeyField}</AccordionItem></Accordion></> : <div className="pw-form-grid">{apiKeyField}</div>}</section>
      {!bedrock && <section className="pw-model-form-section pw-ai-draft-catalog" aria-label="草稿模型目录"><div className="pw-ai-section-heading"><h3>模型</h3><Button variant="secondary" disabled={requesting || stale || unknownSave || !form.url.trim() || !supported('models.catalog.preview')} onClick={() => request('models.catalog.preview')}>{requesting && requestedAction === 'models.catalog.preview' ? '读取中…' : '读取模型'}</Button></div>
        {draftModels && (draftModels.length ? <SelectField label="选择模型" options={draftModels.map(row => ({ id: row.modelId, label: row.modelId, ...(row.protocolCompatibility === 'declared_incompatible' ? { disabled: true, description: '目录已声明使用其他协议' } : {}) }))} value={form.model} onChange={value => { if (draftModels.some(row => row.modelId === value && row.protocolCompatibility !== 'declared_incompatible')) change('model', value); }} isDisabled={requesting} placeholder="从这次目录中选择" /> : <p className="pw-field-hint">服务没有返回模型，可在高级设置中手动填写模型 ID。</p>)}
        {!draftModels && <p className="pw-field-hint">可先读取目录，也可以直接保存服务。{form.model ? `当前模型：${form.model}` : ''}</p>}
        {draftModels && <p className="pw-field-hint">目录已读取，服务草稿尚未保存；读取目录不代表推理测试通过。</p>}
      </section>}
      <Accordion className="pw-model-advanced" variant="divided">
        <AccordionItem id="advanced" title="高级设置" description="协议、手动模型与请求选项">
          <div className="pw-form-grid">
            <SelectField label="接口协议" options={providers} value={form.provider} onChange={value => change('provider', value)} />
            {!bedrock && <TextField label="手动模型 ID（可选）" value={form.model} onChange={value => change('model', value)} autoComplete="off" hint="可以先保存并读取模型目录；推理测试需要明确的模型 ID。" />}
            <TextField label="每次回复的输出上限" value={form.maxOutputTokens} onChange={value => change('maxOutputTokens', value.replace(/\D/g, '').slice(0, 5))} inputMode="numeric"
              isDisabled={!adding && Boolean(original.current?.catalogEnabled)} hint={!adding && original.current?.catalogEnabled ? '在原连接的模型目录中核对精确参数和容量。' : '128–32768 tokens'} />
            <CheckboxField isSelected={form.supportsImageInput} onChange={value => change('supportsImageInput', value)} isDisabled={!adding && Boolean(original.current?.catalogEnabled)}>此模型支持图像输入</CheckboxField>
            <StretchSwitch label="允许按需发送截图" description="优先读取结构树；仅在已配置图像能力且确有需要时发送像素。" checked={form.screenshots} onChange={value => change('screenshots', value)} />
          </div>
        </AccordionItem>
        {!bedrock && <AccordionItem id="compatibility" title="请求兼容" description="输出字段、工具格式与发送限制">
          <ModelConnectionOptions section="wire" value={form.compatibility} onChange={changeCompatibility} provider={form.provider} adding={adding} revision={object(config.compatibility).revision} />
          {policyError && <p role="alert" className="pw-field-error">{policyError}</p>}
          {policyReset && <p className="pw-field-hint">地址、协议、模型或替换密钥已变化；旧兼容与缓存档案没有沿用到这份草稿。需要时重新核对并保存。</p>}
        </AccordionItem>}
        {!bedrock && <AccordionItem id="cache" title="前缀缓存" description="服务商支持与实际缓存用量">
          <ModelConnectionOptions section="cache" value={form.compatibility} onChange={changeCompatibility} provider={form.provider} adding={adding} revision={object(config.compatibility).revision} />
        </AccordionItem>}
      </Accordion>
      {(unknownSave || stale) && <div ref={outcomeNotice} className="pw-request-notice" role="status"><p>{unknownSave ? '原连接的保存结果待核对；本表单不会再次保存。请读取连接列表，核对原记录与修订。' : '这项原连接已被更新；旧草稿没有套用新修订。请返回详情核对后重新编辑。'}</p><Button variant="secondary" onClick={onInspect}>返回核对连接</Button></div>}
      {policyNeedsSave && <p className="pw-field-hint" role="status">先保存兼容与缓存设置，再测试原连接。</p>}
      {bedrock && <p className="pw-field-hint" role="status">{bedrockNeedsSave ? '先保存这项连接和有效短期凭据，再测试已保存的原连接。' : '测试将使用已保存的原区域、模型和短期凭据。'} 本连接使用原生 Messages 请求，不添加缓存提示。</p>}
      {testResult && !stale && <div ref={testResultView} onTransitionEnd={event => {
        if (event.propertyName === 'height') testResultView.current?.scrollIntoView({ block: 'start' });
      }}><ModelConnectionTestFeedback value={testResult} />
        {testResult.inferenceReference?.taskId && <CapabilityAction action="tasks.open" supported={supported} run={run} args={{ id: testResult.inferenceReference.taskId }}>查看本次实际调用与用量</CapabilityAction>}
      </div>}
      {unknownTest && <div ref={outcomeNotice} className="pw-request-notice" role="status"><p>原测试结果待核对；本表单不会自动重试或再次发送。请先核对原调用与连接，再明确开始新的测试。</p><Button variant="secondary" onClick={onInspect}>返回核对连接</Button></div>}
      {(formError || state.error) && <p role="alert" className="pw-field-error">{formError || text(state.error)}</p>}
      </div>
      <footer className="pw-model-editor-footer"><p className="pw-field-hint pw-model-test-cost">保存不会切换默认模型。推理测试可能产生费用。</p>
      <div className="pw-form-actions pw-model-save-actions"><Button variant="ghost" disabled={requesting || state.modelSaving || state.modelTesting} onClick={onInspect}>取消</Button><Button variant="secondary" disabled={!form.model.trim() || stale || unknownSave || unknownTest || bedrockNeedsSave || policyNeedsSave || Boolean(policyError) || requesting || state.modelTesting || state.modelSelectionBusy || !supported('models.connection.test')} onClick={() => request('models.connection.test')}>{state.modelTesting ? '测试中…' : '测试推理'}</Button><PressButton compact disabled={stale || unknownSave || Boolean(policyError) || requesting || state.modelSaving || state.modelTesting || state.modelSelectionBusy || !supported(saveAction)} onClick={() => request(saveAction)}>{state.modelSaving ? '保存中…' : '保存服务'}</PressButton></div></footer>
    </section>
    <SettingsDialog isOpen={Boolean(transport)} title="允许这个明文模型连接？" onClose={clearSecret}>
      {transport && <><Facts items={[[ '协议', providers.find(x => x.id === transport.provider)?.label ], ['地址', transport.displayTarget || transport.url]]} />
        <p>凭据、文本和按需截图将通过 HTTP 明文传输。更换协议或地址后需要重新核对。</p>
        <div className="pw-form-actions"><Button variant="secondary" onClick={clearSecret}>取消</Button><PressButton compact disabled={requesting} onClick={async () => { if (requesting) return; const reviewed = transport; const originalEdit = editRevision.current; setTransport(null); if (reviewed.provider !== form.provider || reviewed.url !== form.url) return; setConsentTarget(text(reviewed.consentTarget)); setRequesting(true); try { await perform(reviewed.action, text(reviewed.consentTarget)); } catch (error) { if (alive.current && originalEdit === editRevision.current) setFormError(error instanceof Error ? error.message : String(error)); } finally { if (alive.current) setRequesting(false); } }}>允许此协议与地址</PressButton></div>
      </>}
    </SettingsDialog>
  </div>;
}

type ModelView = { kind: 'list' | 'connections' | 'edit' | 'add' | 'connection' | 'catalog' | 'model'; id?: string };
const modelConnectionRows = (data: Data) => Array.isArray(data.connections)
  ? list(data.connections) : list(object(data.connections).connections);
const MODEL_PROVIDERS: Record<string, string> = { openai_chat: 'OpenAI Chat', openai_responses: 'OpenAI Responses', anthropic: 'Anthropic', gemini: 'Gemini', bedrock_anthropic: 'AWS Bedrock · Claude' };

/** The BoardUI settings rows retain each profile identity across browsing and editing. */
function ModelConnections({ state, data, supported, run, navigate, open }: { state: Data; data: Data; supported: (action: string) => boolean; run: Run; navigate: (route: string) => void; open: (view: ModelView) => void }) {
  const profiles = modelConnectionRows(data);
  const selectedId = text(object(data.connections).selectedConnectionId);
  const selectedConnection = profiles.find(row => recordId(row) === selectedId);
  const account = state.modelRoute === 'account';
  const otherConnections = profiles.filter(row => recordId(row) !== selectedId);
  return <section className="pw-model-connections" data-model-layer="connections" aria-label="连接列表">
      <header className="pw-settings-object-heading"><div><h2>API 连接</h2><p>{profiles.length ? `已保存 ${profiles.length} 项连接` : '添加连接后，可测试并选择模型'}</p></div><Button variant="secondary" leadingIcon={IconAdd} disabled={!supported('models.connection.add')} onClick={() => open({ kind: 'add' })}>添加 API 连接</Button></header>
      {selectedConnection && <section className="pw-connection-directory" aria-label="已选 API 目录">
        <h3>已选 API 目录</h3>
        <div className="pw-settings-links"><Button variant="ghost" aria-label="查看当前 API 连接" leadingIcon={IconLink} trailingIcon={IconChevronRight} className="pw-domain-link pw-connection-selected h-auto whitespace-normal" onClick={() => open({ kind: 'connection', id: selectedId })}>
          <span><span>{first(selectedConnection, ['name', 'label'], 'API 连接设置')}</span><small>{[MODEL_PROVIDERS[text(selectedConnection.provider)] || first(selectedConnection, ['provider', 'protocol'], 'API'), text(selectedConnection.model)].filter(Boolean).join(' · ')}</small><small>{selectedConnection.credentialStatus === 'expired' ? '短期凭据已过期' : selectedConnection.hasApiKey || selectedConnection.hasAwsSession ? '凭据已保存' : '查看凭据与能力'}</small></span>
        </Button></div>
        {account && <p className="pw-connection-route-note">后续任务使用 ChatGPT 账号；此处保留 API 模型目录。</p>}
      </section>}
      {otherConnections.length > 0 && <section className="pw-connection-directory" aria-label="其他 API 连接"><h3>{selectedConnection ? '其他已保存连接' : '已保存连接'}</h3><div className="pw-settings-links">
        {otherConnections.map((row, index) => <Button key={recordId(row, String(index))} variant="ghost" leadingIcon={IconLink} trailingIcon={IconChevronRight} className="pw-domain-link h-auto whitespace-normal" onClick={() => open({ kind: 'connection', id: recordId(row) })}>
          <span><span>{first(row, ['name', 'label', 'id'], '已保存连接')}</span><small>{[MODEL_PROVIDERS[text(row.provider)] || first(row, ['provider', 'protocol'], 'API'), text(row.model), row.enabled === false ? '已停用' : ''].filter(Boolean).join(' · ')}</small></span>
        </Button>)}
      </div></section>}
      {account && selectedConnection && <div className="pw-form-actions"><Button variant="secondary" disabled={Boolean(state.modelSaving || state.modelSelectionBusy)} onClick={() => open({ kind: 'connection', id: selectedId })}>核对并使用这项 API 连接</Button></div>}
      {profiles.length === 0 && <Empty>还没有 API 连接。添加后可以测试并保存。</Empty>}
      <section className="pw-settings-related"><h3>账号连接</h3><Button variant="ghost" leadingIcon={IconAccount} trailingIcon={IconChevronRight} className="pw-domain-link h-auto whitespace-normal" onClick={() => navigate('accounts')}><span><span>ChatGPT 账号</span><small>授权与套餐模型</small></span></Button></section>
  </section>;
}

function ModelConnectionDetail({ connection, data, state, supported, run, open, busy }: { connection: Data | undefined; data: Data; state: Data; supported: (action: string) => boolean; run: Run; open: (view: ModelView) => void; busy: boolean }) {
  if (!connection) return <Empty>这项连接已不在当前目录，请返回后刷新。</Empty>;
  const selected = recordId(connection) === text(object(data.connections).selectedConnectionId);
  const nativeSelection = object(data.nativeSelection);
  const nativeConnectionId = text(object(nativeSelection.capabilityBinding).connectionId);
  const active = !data.selectionUnavailable && state.modelRoute !== 'account' && data.activeRoute !== 'account' && (nativeConnectionId ? nativeConnectionId === recordId(connection) : selected);
  const grouped = Boolean(object(nativeSelection.modelRoutePlan).requestedAlias);
  const config = object(connection.configuration);
  return <section className="pw-model-connection-detail" data-model-layer="connection" aria-label="连接详情">
    <header className="pw-connection-overview"><span className="pw-model-eyebrow">{active ? grouped ? '默认模型组当前连接' : '后续任务的 API 连接' : selected ? '保留的 API 目录连接' : '已保存的 API 连接'}</span><h2>{text(connection.model, '尚未设置模型')}</h2><p>{MODEL_PROVIDERS[text(connection.provider)] || first(connection, ['provider', 'protocol'])} · 修订 {text(connection.revision, '未提供')}</p></header>
    <div className="pw-settings-links pw-connection-destinations">
      <Button variant="ghost" leadingIcon={IconSettings} trailingIcon={IconChevronRight} className="pw-domain-link h-auto whitespace-normal" aria-label="配置与测试" disabled={busy || !supported('models.connection.edit')} onClick={() => open({ kind: 'edit', id: recordId(connection) })}><span><span>配置与测试</span><small>地址、模型、凭据与高级请求选项</small></span></Button>
      {selected && <Button variant="ghost" leadingIcon={IconFileText} trailingIcon={IconChevronRight} className="pw-domain-link h-auto whitespace-normal" onClick={() => open({ kind: 'catalog' })}><span><span>这项连接的模型目录</span><small>{data.catalogAvailable === false ? '使用明确模型 ID，目录暂不可用' : '核对能力与参数，再明确选择模型'}</small></span></Button>}
    </div>
    <section className="pw-settings-object-facts" aria-label="当前连接状态"><Facts items={[[ '地址', first(connection, ['baseUrl', 'endpoint']) ], ['区域', connection.bedrockRegion || config.bedrockRegion], ['状态', connection.enabled === false ? '已停用' : '已启用'], ['凭据', connection.credentialStatus === 'expired' ? '已过期，请替换完整会话' : connection.hasApiKey || connection.hasAwsSession || connection.credentialsConfigured ? '已配置；可用性需另行测试' : '未配置'], ['到期时间', connection.credentialExpiresAt]]} /></section>
    {!selected && <p className="pw-field-hint">模型目录属于当前选定的 API 连接。先明确使用这项连接，再查看它的目录。</p>}
    <div className="pw-connection-selection"><p>{active ? grouped ? '默认模型组当前指向此连接，后续任务仍按原用途和路由规则选择。' : '新任务使用这项 API 连接。' : '明确选择后，仅用于之后发起的工作。'}正在执行的任务与连续补充保留原模型；停用连接或撤销凭据仍会生效。</p><CapabilityAction action="models.connection.select" args={{ id: recordId(connection), expectedRevision: connection.revision }} supported={supported} run={run} disabled={busy || connection.enabled === false || connection.credentialStatus === 'expired' || connection.provider === 'bedrock_anthropic' && !connection.hasAwsSession}>用于后续任务</CapabilityAction></div>
    <Accordion variant="divided"><AccordionItem id="connection-source" title="原连接身份" description={'修订 ' + text(connection.revision, '未提供')}><Facts items={[[ '原连接', recordId(connection) ], ['原修订', connection.revision], ['目录地址', object(connection.binding).modelsEndpoint]]} /><p className="pw-field-hint">保存与测试分别返回结果。已保存凭据不代表连接测试通过，也不代表免费使用。</p></AccordionItem></Accordion>
  </section>;
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
  const catalogConnection = modelConnectionRows(data).find(row => recordId(row) === text(object(data.connections).selectedConnectionId));
  const models = list(data.models);
  const model = models.find(row => first(row, ['modelId', 'id', 'name']) === selectedId);
  const locked = Boolean(state.modelSelectionBusy || state.chatSubmitting || state.phoneSubmitting || state.modelSaving || state.modelTesting) || selecting;
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
    <div className="pw-model-catalog-source" aria-label="目录所属连接"><div><span className="pw-model-eyebrow">API 目录来源</span><strong>{first(catalogConnection || {}, ['name', 'label', 'id'], '尚未读取连接')}</strong><small>{catalogConnection ? `修订 ${text(catalogConnection.revision, '未提供')} · ${MODEL_PROVIDERS[text(catalogConnection.provider)] || text(catalogConnection.provider)}` : '目录未绑定已保存连接'}</small></div>{catalogConnection && <Button variant="ghost" trailingIcon={IconChevronRight} onClick={() => open({ kind: 'connection', id: recordId(catalogConnection) })}>连接详情</Button>}</div>
    <section className="pw-model-catalog" hidden={Boolean(selectedId)} aria-label="API 模型目录">
      {models.length > 0 ? <ModelPicker label="选择工作模型" value={state.modelRoute === 'account' ? undefined : text(state.modelName)} models={models.map(row => {
        const id = first(row, ['modelId', 'id', 'name']);
        const current = state.modelRoute !== 'account' && id === text(state.modelName);
        return { id, name: first(row, ['name', 'label', 'modelId', 'id']), description: current ? '当前使用 · 查看能力' : locked || !canSelect ? '查看能力与参数' : row.catalogFresh ? '查看能力并切换' : '目录待更新 · 选择前核对', disabled: reviewing || selecting };
      })} onChange={id => { if (state.modelRoute !== 'account' && id === text(state.modelName)) open({ kind: 'model', id }); else void prepare(id); }} />
        : <div className="pw-model-directory-empty"><h2>{data.catalogAvailable === false ? '使用明确的模型 ID' : Array.isArray(data.models) ? '目录中还没有模型' : state.baseUrl ? '正在读取模型目录' : '先连接一个模型'}</h2><p>{data.catalogAvailable === false ? text(data.catalogUnavailableReason, '这项连接未提供模型目录；请在管理连接中核对原区域与模型。') : state.baseUrl ? '可以刷新目录，或在管理连接中填写模型名称。' : '在管理连接中添加 API，或授权你的 ChatGPT 账号。'}</p></div>}
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

function ExecutionControls({ state, supported, run }: { state: Data; supported: (action: string) => boolean; run: Run }) {
  const policy = object(state.ownerExecutionPolicy), [review, setReview] = useState<Data | null>(null), [waiting, setWaiting] = useState(false);
  const alive = useRef(true);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  const busy = waiting || state.ownerExecutionSaving;
  const choose = async (enabled: boolean) => { setWaiting(true); try { const value = object(await run('execution.review', { enabled })); if (alive.current) setReview(value); } catch {} finally { if (alive.current) setWaiting(false); } };
  const confirm = async () => { if (!review || busy) return; setWaiting(true); try { await run('execution.confirm', { reviewHandle: review.reviewHandle }); } catch {} finally { if (alive.current) { setWaiting(false); setReview(null); } } };
  const stop = object(policy.lastStop), outcomes = list(stop.tasks), resources = list(stop.resources);
  return <Card className="pw-execution-control">
    <StretchSwitch label="允许执行与自动任务" checked={policy.enabled === true && policy.acceptingNewWork !== false} disabled={busy || !Number.isSafeInteger(policy.revision) || !supported('execution.review')} description={!Number.isSafeInteger(policy.revision) ? '尚未读取运行状态' : policy.enabled === false || policy.acceptingNewWork === false ? '已停用 · 可查看结果与核对未决操作' : '已开启 · 停用会请求停止并阻止新执行'} onChange={choose} />
    {!Number.isSafeInteger(policy.revision) && <CapabilityAction action="execution.read" supported={supported} run={run}>读取运行状态</CapabilityAction>}
    {stop.requestedAt && <Accordion variant="divided"><AccordionItem id="execution-stop-receipts" title="查看上次停止回执" description="停止请求和实际资源收尾分别记录。"><p>已请求取消 {Number(stop.originalControlsCancelled || 0)} 个原执行控制；仍需按各任务回执核对已发生的效果。</p>{outcomes.map(row => <SourceRecord key={text(row.taskId)} item={{ ...row, title: '原任务 ' + text(row.taskId).slice(0, 8), status: row.cleanup }}><CapabilityAction action="tasks.open" args={{ id: row.taskId }} supported={supported} run={run}>核对原任务</CapabilityAction></SourceRecord>)}{resources.map((row, index) => <Facts key={text(row.resource, String(index))} items={[[text(row.resource, '资源'), row.state || object(row.actual).message || '请核对原资源状态']]} />)}<p className="pw-field-hint">这些回执不表示所有远端进程或系统资源已释放。</p></AccordionItem></Accordion>}
    <SettingsDialog isOpen={review !== null} title={review?.nextEnabled ? '恢复新任务准入？' : '全部停止并停用？'} onClose={() => { if (!busy) setReview(null); }}><p>{review?.nextEnabled ? '只允许之后明确发起的新工作和有效自动计划。不会自动继续旧任务或重放结果未知的操作。' : '先持久保存停用，再请求停止原任务、监听、网关、显示和音频资源。未决效果及清理回执会保留，关闭应用不会撤销停用。'}</p><div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => setReview(null)}>取消</Button><PressButton compact disabled={busy || review?.revision !== policy.revision} onClick={confirm}>{busy ? '等待原回执…' : review?.nextEnabled ? '允许后续工作' : '确认全部停止并停用'}</PressButton></div>{review && review.revision !== policy.revision && <p className="pw-field-error">原开关状态已变化，请取消后重新核对。</p>}</SettingsDialog>
  </Card>;
}

function PhoneSettings({ state, supported, run, navigate, registerTransientBack, initialView = 'list', onViewChange, direct = false }: { state: Data; supported: (action: string) => boolean; run: Run; navigate: (route: string) => void; registerTransientBack: BackHandlerRegistration; initialView?: string; onViewChange?: (view: string) => void; direct?: boolean }) {
  const ready = object(state.phoneReadiness), screenshotPolicy = object(state.taskScreenshotPolicy);
  const latestReply = list(state.messages).filter(row => row.role === 'assistant' && typeof row.id === 'string' && row.content).at(-1);
  const appearance = text(object(state.appearance).mode), readout = object(object(state.taskAnnouncements).readout);
  const journey = useDataSettingsJourney(initialView);
  useEffect(() => { onViewChange?.(journey.view); }, [journey.view, onViewChange]);
  useEffect(() => { registerTransientBack(() => direct && journey.view === initialView ? false : journey.back()); return () => registerTransientBack(null); }, [journey.view, initialView, direct, registerTransientBack]);
  const flag = (value: unknown, yes = '已开启', no = '未开启') => value === true ? yes : value === false ? no : '尚未核实';
  const observationKnown = typeof state.localCaptureAllowed === 'boolean' && typeof state.localOcrAllowed === 'boolean';
  const sections: Record<string, { title: string; description: string }> = {
    execution: { title: '执行权限与连接', description: '查看这台手机的实际准备状态，再选择系统授权或后台连接。' },
    observation: { title: '本地观察与截图', description: '分别控制取图、识字和新结果截图的本机留存。' },
    appearance: { title: '外观与字体', description: '选择浅色、深色与中文、英数和代码字体。' },
    speech: { title: '语音与朗读', description: '听写只进入草稿，发送仍由你决定。' },
    browser: { title: '浏览器', description: '按这次目的打开网页研究或普通浏览窗口。' },
  };
  return <div ref={journey.root} tabIndex={-1} className="pw-phone-workspace" data-phone-view={journey.view}>
    {journey.view === 'list' ? <>
      <header className="pw-phone-overview"><span className="pw-phone-status-label">这台手机</span><p role="status">{first(ready, ['message', 'summary', 'status'], '尚未核对执行能力')}</p></header>
      <section className="pw-phone-entry-group" aria-label="设备能力"><h2>设备能力</h2><div className="pw-settings-links pw-phone-entries">
        <DataOperationRow id="execution" title="执行权限与连接" detail={`界面读取 ${flag(state.accessibilityEnabled, '已连接', '未连接')} · 任务通知 ${flag(state.notificationsEnabled)}`} onOpen={journey.open}/>
        <DataOperationRow id="observation" title="本地观察与截图" detail={`取图 ${flag(state.localCaptureAllowed)} · OCR ${flag(state.localOcrAllowed)}`} onOpen={journey.open}/>
        <DataOperationRow id="browser" title="浏览器" detail="网页研究与内置浏览器" onOpen={journey.open}/>
      </div></section>
      <section className="pw-phone-entry-group" aria-label="使用偏好"><h2>使用偏好</h2><div className="pw-settings-links pw-phone-entries">
        <DataOperationRow id="appearance" title="外观与字体" detail={({ system: '跟随系统', light: '浅色', dark: '深色' } as Data)[appearance] || '尚未读取当前外观'} onOpen={journey.open}/>
        <DataOperationRow id="speech" title="语音与朗读" detail={state.speaking ? '正在朗读' : state.speechAvailable === true ? '系统听写可用' : '听写、朗读与任务状态提示'} onOpen={journey.open}/>
      </div></section>
      <div className="pw-phone-app-entry"><CapabilityAction row action="settings.openAppUpdates" supported={supported} run={run}><span><span>应用更新</span><small>检查新版本与管理下载</small></span></CapabilityAction></div>
    </> : !direct && <DataOperationHeading title={sections[journey.view]?.title || '手机能力'} parent="手机能力" description="" onBack={() => journey.open('list')}/>}
    {journey.view !== 'speech' && (state.speaking || readout.active) && <section className="pw-phone-live-audio" aria-label="当前朗读"><span role="status">{state.speaking ? '正在朗读回复' : '正在播报任务状态'}</span><div>{state.speaking && <CapabilityAction action="speech.stop" supported={supported} run={run}>停止朗读</CapabilityAction>}{readout.active && <CapabilityAction action="speech.stopTaskAnnouncement" args={{ readoutId: readout.readoutId }} supported={supported} run={run}>停止这次状态提示</CapabilityAction>}</div></section>}
    {journey.view === 'execution' && <section className="pw-phone-detail pw-phone-permissions" aria-label="执行权限与连接">
      <p className="pw-detail-status" role="status">{first(ready, ['message', 'summary', 'status'], '尚未核对执行能力')}</p>
      <section className="pw-phone-permission-group" aria-label="手机操作"><h2>手机操作</h2><div className="pw-settings-links">
        {[
          ['accessibility', '界面读取与主屏控制', flag(state.accessibilityEnabled, '已连接', '未连接')],
          ['notifications', '任务通知', flag(state.notificationsEnabled)],
        ].map(([section, title, current]) => <CapabilityAction key={section} row icon={section === 'accessibility' ? IconPhone : IconNotification} action="settings.openSystem" args={{ section }} supported={supported} run={run}><span className="pw-phone-permission-copy"><span>{title}</span><small>{current}</small></span></CapabilityAction>)}
      </div></section>
      <section className="pw-phone-permission-group" aria-label="后台连接"><h2>后台连接</h2><div className="pw-settings-links">
        <CapabilityAction row icon={IconLink} action="settings.openSystem" args={{ section: 'wireless' }} supported={supported} run={run}><span className="pw-phone-permission-copy"><span>无线调试</span><small>前往设置</small></span></CapabilityAction>
        <Button variant="ghost" leadingIcon={IconMonitor} trailingIcon={IconChevronRight} className="pw-domain-link h-auto whitespace-normal" disabled={!supported('settings.openSystem')} onClick={() => navigate('background')}><span><span>后台连接与授权</span><small>配对、连接与能力检查</small></span></Button>
      </div></section>
      <Accordion className="pw-phone-permission-help"><AccordionItem id="system-permission-scope" title="授权如何生效"><p className="pw-field-hint">系统授权与连接不会自动开始任务。返回原目标后再核对本次执行范围。</p></AccordionItem></Accordion>
    </section>}
    {journey.view === 'observation' && <section className="pw-phone-detail pw-phone-preferences" aria-label="本地观察与截图">
      <section className="pw-phone-preference-group" aria-label="本地观察"><h3><IconObserve size={22}/>本地观察</h3>
        {!observationKnown && <p className="pw-field-hint" role="status">尚未读取观察设置，请刷新当前状态后再更改。</p>}
        <StretchSwitch label="允许本地按需取图" description="更改用于后续运行；关闭后不为 OCR 采集像素。" checked={state.localCaptureAllowed === true} disabled={!observationKnown || !supported('settings.saveLocalObservation')} onChange={capture => { void run('settings.saveLocalObservation', { capture, ocr: state.localOcrAllowed === true }).catch(() => {}); }}/>
        <StretchSwitch label="允许本地 OCR" description="像素在本地识字；有来源的识别文字可发送给已选推理服务。" checked={state.localOcrAllowed === true} disabled={!observationKnown || !supported('settings.saveLocalObservation')} onChange={ocr => { void run('settings.saveLocalObservation', { capture: state.localCaptureAllowed === true, ocr }).catch(() => {}); }}/>
      </section>
      <section className="pw-phone-preference-group pw-screenshot-retention" aria-label="结果截图留存"><h3><IconPrivacy size={22}/>结果截图</h3>{typeof screenshotPolicy.revision === 'number' ? <SelectField label="本机留存时间" value={text(screenshotPolicy.retentionMillis)} options={[{ id: '0', label: '不保存新截图' }, { id: '86400000', label: '1 天' }, { id: '604800000', label: '7 天' }, { id: '2592000000', label: '30 天' }]} isDisabled={Boolean(state.taskScreenshotSaving) || !supported('privacy.screenshots.save')} onChange={value => { void run('privacy.screenshots.save', { revision: screenshotPolicy.revision, retentionMillis: Number(value) }).catch(() => {}); }}/> : <CapabilityAction action="privacy.screenshots.read" supported={supported} run={run}>读取截图留存设置</CapabilityAction>}
        <div className="pw-phone-policy-copy"><p className="pw-field-hint">新结果截图使用本机密钥加密；留存设置只影响之后生成的截图。</p><p className="pw-field-hint">旧图保留到你在原任务核对中删除，或被同一任务的新结果替换。</p></div>
      </section>
    </section>}
    <section className="pw-phone-detail" hidden={journey.view !== 'appearance'} inert={journey.view !== 'appearance'}><AppearanceSettings state={state} supported={supported} run={run}/><TypographySettings state={state} supported={supported} run={run}/></section>
    <section className="pw-phone-detail pw-phone-preferences" aria-label="语音与朗读" hidden={journey.view !== 'speech'} inert={journey.view !== 'speech'}>
      <SystemSpeechSettings state={state} supported={supported} run={run}>
        <div className="pw-speech-playback">
          {state.speaking ? <><p className="pw-field-hint" role="status">正在朗读回复</p><CapabilityAction primary action="speech.stop" supported={supported} run={run}>停止朗读</CapabilityAction></>
            : latestReply ? <CapabilityAction primary={object(state.systemSpeech).ttsStatus === 'ready'} action="speech.speak" args={{ messageId: latestReply.id }} supported={supported} run={run}>朗读最近回复</CapabilityAction>
              : <p className="pw-field-hint">当前对话暂无可朗读的回复。</p>}
        </div>
      </SystemSpeechSettings>
      <section className="pw-phone-preference-group" aria-label="听写与任务提示"><h3><IconMicrophone size={22}/>听写与任务提示</h3>
        <div className="pw-speech-input-status"><Facts items={[[ '系统听写', flag(state.speechAvailable, '可用', '暂不可用') ]]}/><p className="pw-field-hint">听写只填入草稿，由你决定发送。</p></div>
        <StretchSwitch label="任务状态语音提示" description="仅播报前台私有任务的状态，不读任务正文；默认关闭。" checked={object(state.taskAnnouncements).enabled === true} disabled={Boolean(state.taskAnnouncementSaving) || !supported('speech.taskAnnouncements')} onChange={enabled => { void run('speech.taskAnnouncements', { enabled }).catch(() => {}); }}/>
        {readout.active && <div className="pw-phone-inline-status"><span role="status">正在播报任务状态</span><CapabilityAction action="speech.stopTaskAnnouncement" args={{ readoutId: readout.readoutId }} supported={supported} run={run}>停止这次状态提示</CapabilityAction></div>}
      </section>
    </section>
    {journey.view === 'browser' && <section className="pw-phone-detail pw-settings-links" aria-label="浏览器入口"><CapabilityAction row action="settings.openBrowser" args={{ purpose: 'research' }} supported={supported} run={run}><span><span>网页研究工作区</span><small>打开研究面板，查看任务与保留来源</small></span></CapabilityAction><CapabilityAction row action="settings.openBrowser" args={{ purpose: 'browse' }} supported={supported} run={run}><span><span>内置浏览器</span><small>直接浏览网页，由你操作页面</small></span></CapabilityAction></section>}
  </div>;
}

/** Original BackgroundPhoneSetup flow composed with the selected BoardUI
 * fields, radio options, accordion and SettingsModal. It reads the existing
 * display state; this page does not create a task or a second connection. */
function BackgroundSettings({ state, supported, run, onReturn, returnLabel = '手机能力', registerTransientBack }: { state: Data; supported: (action: string) => boolean; run: Run; onReturn: () => void; returnLabel?: string; registerTransientBack: BackHandlerRegistration }) {
  const display = object(object(state.features).display), adb = object(display.adb), discovery = object(adb.discovery), shizuku = object(display.shizuku);
  const audioRecovery = object(object(object(state.features).phone_setup).audioRecovery), audioPending = audioRecovery.pendingCount;
  const pending = Array.isArray(state.moduleBusy) ? state.moduleBusy.map(String) : [];
  const connection = backgroundConnection(display, state.activeTaskId, pending);
  const [connecting, setConnecting] = useState(() => connection.requiresConnectionCheck || connection.phase === 'paired' || connection.identityPresent);
  const [showWirelessSetup, setShowWirelessSetup] = useState(false);
  const automaticDiscovery = useRef(false);
  const [manual, setManual] = useState(false), [portText, setPortText] = useState(''), [selected, setSelected] = useState('');
  const [code, setCode] = useState(''), [pairPort, setPairPort] = useState<number | undefined>();
  const [panel, setPanel] = useState<'setup' | 'checks'>('setup');
  const [mainScreenConsent, setMainScreenConsent] = useState(false), [requesting, setRequesting] = useState(false);
  const [unknownResult, setUnknownResult] = useState(false);
  const [awaiting, setAwaiting] = useState<{ key: string; before: string; busySeen: boolean } | null>(null);
  const pairGuard = useRef(false);
  const ownerBack = useRef<Record<string, (() => boolean) | null>>({});
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
  const selectPanel = (value: 'setup' | 'checks') => { setCode(''); setPanel(value); };
  useEffect(() => { registerTransientBack(() => { if (Object.values(ownerBack.current).some(handler => handler?.())) return true; if (mainScreenConsent) { setMainScreenConsent(false); return true; } if (panel === 'checks') { selectPanel('setup'); return true; } if (showWirelessSetup) { setCode(''); setShowWirelessSetup(false); return true; } return false; }); return () => registerTransientBack(null); }, [panel, mainScreenConsent, showWirelessSetup, registerTransientBack]);
  useEffect(() => {
    if (state.backgroundSetupOpen === true) ownerOpened.current = true;
    else if (ownerOpened.current) { setCode(''); onReturn(); }
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
  // Discover existing local TLS services once per visit. Discovery is read-only and
  // never pairs a new device or retries a connection with unknown effects.
  useEffect(() => {
    if (automaticDiscovery.current || !connection.loaded || !connection.supported ||
        !connection.identityPresent || connection.backend !== 'wireless_adb' ||
        connection.helperConnected || connection.taskOwned || connection.busy ||
        !supported('background.discover')) return;
    automaticDiscovery.current = true;
    void run('background.discover', {}).catch(() => {});
  }, [connection.loaded, connection.supported, connection.identityPresent, connection.backend,
      connection.helperConnected, connection.taskOwned, connection.busy, supported('background.discover')]);
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
  const wirelessSaved = connection.backend === 'wireless_adb' && connection.identityPresent;
  const savedEndpoints = wirelessEndpoints(discovery, 'connect');
  const shizukuAvailable = shizuku.binderAvailable === true && Number(shizuku.serverVersion) >= 13;
  const shizukuAuthorized = shizukuAvailable && shizuku.permissionGranted === true;
  const errors = Object.entries(object(state.moduleErrors)).filter(([key]) => key.startsWith('display.') || key.startsWith('phone_setup.')).map(([, value]) => text(value)).filter(Boolean);
  const headline = connection.busy || requesting || awaiting ? '正在核对本次连接' : requiresCheck ? '上次连接结果待核对' : connection.screenEstablished ? '独立屏幕已建立' : connection.helperConnected ? '后台助手已连接' : !connection.loaded ? '正在读取后台连接' : !connection.supported ? '后台独立屏需要 Android 14 或以上' : connection.phase === 'paired' ? '配对已确认，还需要连接助手' : '选择手机操作方式';
  const alternate = connection.backend === 'shizuku' || connection.backend === 'adb';
  const permissions = state.accessibilityEnabled === true && state.notificationsEnabled === true;
  const steps = [
    { title: '系统准备', done: connection.loaded && connection.supported && permissions, detail: !connection.loaded ? '先读取真实系统与连接状态' : !connection.supported ? '当前系统不支持后台独立屏，可明确选择主屏模式' : !permissions ? '完成界面读取权限与任务通知后返回' : '权限已读取，继续核对后台连接' },
    { title: alternate ? '已选连接授权' : '本机配对身份', done: !requiresCheck && (alternate ? connection.helperConnected : connection.identityPresent || connection.phase === 'paired' || connection.helperConnected), detail: requiresCheck ? '核对原连接，避免重复提交配对码' : alternate ? '保留已选择的兼容连接方式' : connection.identityPresent || connection.phase === 'paired' || connection.helperConnected ? '已读取本机身份，连接时会核对有效性' : '使用当前系统窗口的配对端口与六位码' },
    { title: '连接核对', done: connection.helperConnected && !connection.busy && !requiresCheck, detail: connection.helperConnected && !requiresCheck ? '助手已连接，正式使用时核对实际独立屏' : '配对后使用无线调试主页的另一连接端口' },
    { title: '返回原目标', done: false, detail: connection.taskOwned ? '原任务持有执行资源，回到原任务查看' : '完成设置后返回，由你确认是否开始原目标' },
  ];
  const locked = connection.taskOwned || connection.busy || requesting || awaiting !== null;
  const canUseExistingShizuku = !locked && !connection.displayBarrier && !connection.recoveryRequired && !requiresCheck;
  const reconnectSaved = async () => {
    if (locked || pairGuard.current || !wirelessSaved || !connection.canConnect || savedEndpoints.length !== 1 ||
        !supported('background.connect')) return;
    const endpoint = savedEndpoints[0], key = 'display.adb.connect';
    pairGuard.current = true; setConnecting(true); setRequesting(true); setCode('');
    setAwaiting({ key, before: resultSignature(key), busySeen: false });
    try { await run('background.connect', { host: endpoint.host, port: endpoint.port }); }
    catch { setUnknownResult(true); setAwaiting(null); pairGuard.current = false; }
    finally { setRequesting(false); }
  };
  const connected = connection.helperConnected && connection.backend === 'wireless_adb' && !display.releasePending && !display.recoveryRequired;
  const activeStep = connection.taskOwned || connection.helperConnected && !requiresCheck ? 3 : !connection.loaded || !connection.supported || !permissions ? 0 : connecting || requiresCheck ? 2 : 1;
  const blockedGroups = list(object(state.phoneReadiness).groups).filter(group => group.state === 'blocked').length;
  return <div className="pw-background-workspace" data-background-panel={panel}>
    <section className="pw-background-flow" aria-label="当前连接准备">
    <header className="pw-background-heading"><h2>{headline}</h2>
      <p className="pw-background-backend">{({ wireless_adb: '手机内无线连接', shizuku: 'Shizuku 兼容连接', adb: '受信任电脑 ADB' } as Data)[connection.backend] || '连接方式尚未读取'}{connection.taskOwned ? ' · 原手机任务持有执行资源' : ''}</p>
    </header>
    {showWirelessSetup && <ol className="pw-background-progress" aria-label="连接准备进度">{steps.map((step, index) => <li key={step.title} data-complete={step.done || undefined} aria-current={activeStep === index ? 'step' : undefined} title={step.title}><span aria-hidden="true">{step.done ? <IconCheck size={13}/> : index + 1}</span><span aria-hidden="true">{['系统准备', alternate ? '连接授权' : '本机配对', '连接核对', '返回目标'][index]}</span><span className="pw-sr-only">{step.title}，{step.done ? '已完成' : activeStep === index ? '当前步骤' : '待核对'}。{step.detail}</span></li>)}</ol>}
    {connection.taskOwned && state.activeTaskId && <div className="pw-background-status-actions"><CapabilityAction action="tasks.open" args={{ id: state.activeTaskId }} supported={supported} run={run} primary>回到原任务</CapabilityAction></div>}
    {(connection.busy || requesting || awaiting || requiresCheck) && text(adb.message) && <p className="pw-background-native-message" role="status">{text(adb.message)}</p>}
    {requiresCheck && <p className="pw-background-attention" role="status">上次连接结果尚未确认。核对原助手，不要重复提交配对码。</p>}
    {awaiting && !connection.busy && <p className="pw-field-hint" role="status">请求已登记，正在等待实际连接回执。可刷新核对状态，请勿重复提交。</p>}
    {errors.map((message, index) => <p className="pw-field-error" role="alert" key={index}>{message}</p>)}
      {Number.isSafeInteger(audioPending) && audioPending > 0 && <div role="alert"><p className="pw-field-error">还有 {audioPending} 项声音记录待处理，原 App 可能仍静音。请在下方处理原声音记录。</p>{audioRecovery.needsOwnerAction === true && <div className="pw-form-actions"><CapabilityAction action="settings.openSystem" args={{ section: 'accessibility' }} supported={supported} run={run}>打开无障碍设置</CapabilityAction></div>}</div>}
      {!connection.taskOwned && connection.loaded && connection.supported && !alternate && !connected && (connection.busy || connection.recoveryRequired || awaiting) && <div className="pw-form-actions"><Button variant="secondary" disabled={!supported('background.cancel') || pending.includes('display.adb.cancel')} onClick={() => { setCode(''); void run('background.cancel', {}).catch(() => {}); }}>停止本次连接与启动</Button></div>}
    <Tabs selectedKey={panel} onSelectionChange={key => selectPanel(key as 'setup' | 'checks')} className="pw-background-tabs">
      <TabList aria-label="后台连接内容"><Tab id="setup">连接设置</Tab><Tab id="checks" count={blockedGroups || undefined}>能力检查</Tab></TabList>
      <TabPanel id="setup">
        {!permissions && <section className="pw-background-preparation"><h3>开启手机操作所需权限</h3><p>完成系统授权后返回，使用右上角刷新核对。</p><div className="pw-background-permission-status"><span>界面读取<b>{state.accessibilityEnabled === true ? '已连接' : '待开启'}</b></span><span>任务通知<b>{state.notificationsEnabled === true ? '已开启' : '待开启'}</b></span></div><div className="pw-form-actions">{state.accessibilityEnabled !== true && <CapabilityAction action="settings.openSystem" args={{ section: 'accessibility' }} supported={supported} run={run} primary>设置界面读取权限</CapabilityAction>}{state.notificationsEnabled !== true && <CapabilityAction action="settings.openSystem" args={{ section: 'notifications' }} supported={supported} run={run} primary={state.accessibilityEnabled === true}>允许任务通知</CapabilityAction>}</div></section>}
    {!connection.taskOwned && connection.loaded && connection.supported && !connection.helperConnected && <Card title="手机控制" description="主屏无需配对；独立屏优先复用已有授权。">
       <div className="pw-form-actions">
         {wirelessSaved && <Button variant="primary" disabled={locked || !connection.canConnect || !supported(savedEndpoints.length === 1 ? 'background.connect' : 'background.discover') || savedEndpoints.length !== 1 && discovery.discovering === true}
           onClick={() => { if (savedEndpoints.length === 1) void reconnectSaved(); else void run('background.discover', {}).catch(() => {}); }}>
           {savedEndpoints.length === 1 ? requiresCheck ? '核对上次无线连接' : '恢复已授权后台助手' : discovery.discovering === true ? '正在发现本机连接…' : '查找已授权的连接'}
         </Button>}
         {shizukuAuthorized && <CapabilityAction action="background.shizuku.start" supported={supported} run={run} disabled={!canUseExistingShizuku} primary={!wirelessSaved}>使用已授权 Shizuku</CapabilityAction>}
         {shizukuAvailable && !shizukuAuthorized && <CapabilityAction action="background.shizuku.permission" supported={supported} run={run} disabled={!canUseExistingShizuku || shizuku.permissionRequestPending === true}>授权已有 Shizuku</CapabilityAction>}
         <Button variant={wirelessSaved || shizukuAuthorized ? 'secondary' : 'primary'} disabled={locked || !supported('background.selectMainScreen')} onClick={() => setMainScreenConsent(true)}>使用主屏模式（免配对）</Button>
       </div>
       {wirelessSaved && savedEndpoints.length > 1 && <p className="pw-field-hint">发现多个本机连接端口，无法安全地猜测。请展开下方连接设置明确选择。</p>}
       {wirelessSaved && savedEndpoints.length === 0 && !discovery.discovering && <><p className="pw-field-hint">已保存授权身份，但尚未找到本机连接服务；不需要重新输入配对码。</p>
         <CapabilityAction action="settings.openSystem" args={{ section: 'wireless' }} supported={supported} run={run}>打开无线调试设置</CapabilityAction></>}
       {!wirelessSaved && !shizukuAuthorized && <p className="pw-field-hint">首次使用独立后台屏需要 Android 授予系统级连接能力。仅靠无障碍可以操作主屏，不能创建隔离后台屏。</p>}
       <Button variant="ghost" disabled={locked} onClick={() => { setCode(''); setShowWirelessSetup(value => !value); }}>{showWirelessSetup ? '收起连接设置' : '首次授权或手动连接'}</Button>
     </Card>}
     {showWirelessSetup && (permissions || connecting || requiresCheck) && !connection.taskOwned && connection.loaded && connection.supported && !alternate && !connected && <Card title={connecting ? '连接后台助手' : '与这台手机配对'} description={connecting ? '回到系统无线调试主页，读取连接端口。它与配对窗口的端口不同。' : '在开发者选项开启无线调试，打开“使用配对码配对设备”，保持当前窗口可见。'}>
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
        {!connecting && <div className="pw-form-actions">{object(object(object(state.features).phone_setup).pairing).active === true ? <CapabilityAction action="background.pairing.cancel" supported={supported} run={run}>停止通知输码</CapabilityAction> : <Button variant="ghost" disabled={locked || !connection.canPair || port == null || host !== '127.0.0.1' || state.notificationsEnabled !== true || !supported('background.pairing.begin')} onClick={() => { setCode(''); void run('background.pairing.begin', { host, port }).catch(() => {}); }}>在系统配对页通过通知输码</Button>}</div>}
        {connecting && port != null && port === pairPort && <p className="pw-background-attention">这是刚才的配对端口，请填写无线调试主页的另一连接端口。</p>}
        <div className="pw-form-actions"><PressButton compact disabled={locked || port == null || !supported(connecting ? 'background.connect' : 'background.pair') || (connecting ? !connection.canConnect || port === pairPort : !connection.canPair || requiresCheck || !/^\d{6}$/.test(code))} onClick={() => { void submit(); }}>{connecting ? requiresCheck ? '恢复并核对原连接' : '完成手机连接' : '确认配对并继续'}</PressButton>
          {!connecting ? <Button variant="ghost" disabled={locked} onClick={() => { setCode(''); setConnecting(true); }}>{connection.identityPresent ? '使用已保存身份连接' : '此前配对过？直接连接'}</Button> : <Button variant="ghost" disabled={locked || !connection.canPair || requiresCheck} onClick={() => { setCode(''); setConnecting(false); }}>重新读取配对窗口</Button>}
        </div>
      </>}
    </Card>}
    {connection.loaded && !connection.supported && <Card title="此系统使用主屏兼容模式" description="后台独立屏需要 Android 14 或以上；执行主屏任务时会切换应用并占用当前屏幕。"><Button variant="secondary" disabled={connection.taskOwned || connection.busy || !supported('background.selectMainScreen')} onClick={() => setMainScreenConsent(true)}>选择主屏兼容模式</Button></Card>}

        {(connected || connection.taskOwned || alternate) && <section className="pw-background-retained"><h3>{connection.taskOwned ? '继续核对原任务' : connected ? '助手连接已保留' : '保留当前兼容连接'}</h3><p>{connection.taskOwned ? '原任务仍持有执行资源，本页不会重建连接或开始另一项任务。' : connected ? '开始任务时还会核对输入、取帧和独立显示能力。' : '当前使用已选的 Shizuku 或电脑 ADB 路线。本页不会自动切换为无线配对。'}</p><Button variant="secondary" onClick={() => selectPanel('checks')}>查看实际能力检查</Button></section>}
      </TabPanel>
      <TabPanel id="checks">    <Card title="本机能力检查" description="逐项来自当前系统检查。已连接仍需核对输入、取帧和输入法隔离能力；从系统设置返回后刷新。">

      {list(object(state.phoneReadiness).groups).length === 0 ? <Empty>尚未取得本机能力检查结果。</Empty> : <Accordion variant="divided">{list(object(state.phoneReadiness).groups).map(group => <AccordionItem key={text(group.id)} id={text(group.id)} title={<span className="pw-readiness-title"><span>{text(group.title)}</span><small>{({ ready: '可用', blocked: '需准备', unsupported: '本机不支持', unknown: '尚未核实' } as Data)[text(group.state)] || '尚未核实'}</small></span>}>
        {list(group.checks).length === 0 ? <p className="pw-field-hint">尚未取得实际检查明细。</p> : list(group.checks).map((check, index) => <div key={text(check.id, String(index))} className="pw-readiness-check"><p>{text(check.message, '尚未取得实际状态')}</p>{check.state !== 'ready' && <div className="pw-form-actions">{['accessibility', 'notifications'].includes(check.action) ? <CapabilityAction action="settings.openSystem" args={{ section: check.action }} supported={supported} run={run}>{check.action === 'accessibility' ? '检查界面权限' : '检查任务通知'}</CapabilityAction> : ['background_setup', 'retry'].includes(check.action) ? <CapabilityAction action="background.refresh" supported={supported} run={run}>重新核对</CapabilityAction> : check.action === 'main_screen_choice' ? <Button variant="secondary" disabled={connection.taskOwned || connection.busy || !supported('background.selectMainScreen')} onClick={() => setMainScreenConsent(true)}>选择主屏兼容模式</Button> : null}</div>}</div>)}
      </AccordionItem>)}</Accordion>}
      <div className="pw-form-actions">{state.accessibilityEnabled !== true && <CapabilityAction action="settings.openSystem" args={{ section: 'accessibility' }} supported={supported} run={run}>设置界面读取权限</CapabilityAction>}{state.notificationsEnabled !== true && <CapabilityAction action="settings.openSystem" args={{ section: 'notifications' }} supported={supported} run={run}>允许任务通知</CapabilityAction>}</div>
    </Card>
    <BackgroundChecks state={state} supported={supported} run={run} registerBack={handler => { ownerBack.current.checks = handler; }} />
</TabPanel>
    </Tabs>
    <p className="pw-background-scope-note">{connection.screenEstablished ? '已核验当前独立显示会话。' : connection.helperConnected ? '开始任务时会核对独立屏幕；这里不会自动开始任务。' : '这里只准备连接，完成后由你决定何时开始任务。'}</p>
    </section>
    <section className="pw-background-options" aria-label="连接与应用设置"><h3>连接与应用设置</h3><div className="pw-background-options-surface"><BackgroundOwnerSettings state={state} supported={supported} run={run} registerBack={handler => { ownerBack.current.advanced = handler; }} />
      {(text(display.message) || text(adb.message)) && <Accordion variant="divided" className="pw-background-native-details"><AccordionItem id="background-native-state" title="连接说明与原状态">{Array.from(new Set([text(display.message), text(adb.message)].filter(Boolean))).map(message => <p key={message} className="pw-background-native-message">{message}</p>)}</AccordionItem></Accordion>}
    </div></section>
    <section className="pw-background-preferences" aria-label="后台偏好"><h3>后台偏好</h3><BackgroundAudioSettings state={state} supported={supported} run={run} registerBack={handler => { ownerBack.current.audio = handler; }}/></section>
    <div className="pw-background-return"><Button variant={connection.helperConnected && !requiresCheck ? 'primary' : 'ghost'} onClick={() => { setCode(''); onReturn(); }}>{connection.helperConnected && !requiresCheck ? `助手已连接，返回${returnLabel}` : `返回${returnLabel}`}</Button></div>
    <SettingsDialog isOpen={mainScreenConsent} title="改用主屏兼容模式？" onClose={() => setMainScreenConsent(false)}><p>助手执行时会切换应用并占用当前屏幕。请先完成界面读取与任务通知授权；此处只选择模式，不会开始原目标。</p><div className="pw-form-actions"><Button variant="secondary" onClick={() => setMainScreenConsent(false)}>暂不使用</Button><CapabilityAction action="background.selectMainScreen" supported={supported} run={run} disabled={connection.taskOwned || connection.busy} primary>明确选择主屏模式</CapabilityAction></div></SettingsDialog>
  </div>;
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
  const groups = items.reduce<Array<{ key: string; label: string; rows: Data[] }>>((result, row) => {
    const timestamp = typeof row.updatedAt === 'number' && Number.isFinite(row.updatedAt) && row.updatedAt > 0 ? row.updatedAt : null;
    const date = timestamp === null ? null : new Date(timestamp);
    const label = date && Number.isFinite(date.getTime()) ? date.toLocaleDateString(undefined, { year: 'numeric', month: 'long', day: 'numeric' }) : '时间未记录';
    const previous = result.at(-1);
    if (previous?.label === label) previous.rows.push(row);
    else result.push({ key: `${label}:${result.length}`, label, rows: [row] });
    return result;
  }, []);
  return <Card className="pw-history-workspace">
    <header className="pw-history-toolbar"><span>{read && items.length ? `最近记录 · ${items.length} 条` : '最近记录'}</span><Button variant="ghost" leadingIcon={IconRefresh} disabled={busy || !supported(action)} onClick={() => load(true)}>{busy ? '读取中…' : error ? '重新读取' : '刷新'}</Button></header>
    {error && <p className="pw-field-error" role="alert">{error}</p>}
    {busy && <p className="pw-field-hint" role="status">正在更新记录…</p>}
    {!read && !busy && !error && <div className="pw-history-empty"><IconFileText aria-hidden="true" /><h2>尚未读取记录</h2><p>{supported(action) ? '刷新后查看保留的记录。' : '当前版本暂不支持读取这些记录。'}</p></div>}
    {read && !busy && !error && items.length === 0 && <div className="pw-history-empty"><IconFileText aria-hidden="true" /><h2>{usage ? '暂无调用记录' : '暂无任务记录'}</h2><p>{usage ? '当前窗口还没有保留的模型调用。' : '任务运行后，可以在这里回看保留的过程与结果。'}</p></div>}
    {error && items.length > 0 && <p className="pw-field-hint">下方保留上次读取的记录。</p>}
    {groups.length > 0 && <div className="pw-settings-record-list">{groups.map(group => <section key={group.key} className="pw-history-date-group" aria-label={group.label}><h2>{group.label}<span>{group.rows.length} 条</span></h2><div className="pw-history-record-group">{group.rows.map(row => <CapabilityAction key={recordId(row)} row action="tasks.open" args={{ id: recordId(row) }} supported={supported} run={run}><span className="pw-history-record-content"><span className="pw-history-record-heading"><strong>{first(row, ['goal', 'title', 'id'], '原任务')}</strong><em className="pw-settings-record-status" data-record-status={text(row.status || row.state)}>{statusText(row.status || row.state)}</em></span><small>{[timeText(row.updatedAt), first(row, ['source', 'executionMode'])].filter(Boolean).join(' · ')}</small></span></CapabilityAction>)}</div></section>)}</div>}
    {more && items.length < 160 && <Button variant="secondary" disabled={busy} onClick={() => load(false)}>读取较早窗口</Button>}
    {items.length >= 160 && <p className="pw-field-hint">已到本次 160 条显示上限；原记录仍保留，刷新可回到最近窗口。</p>}
    <Accordion variant="divided" className="pw-history-help"><AccordionItem id="record-coverage" title="记录说明"><p className="pw-history-context">{usage ? '这里只展示当前窗口保留的调用摘要。完整用量和账单请以服务方为准；没有记录不代表用量为 0。' : '这里只展示当前窗口保留的任务。打开记录可以查看原任务与回执，不会重新执行。'}</p></AccordionItem></Accordion>
  </Card>;
}

function Collection({ data, keys, label, children, titleKeys, formatStatus }: { data: Data; keys: string[]; label: string; children?: (item: Data) => ReactNode; titleKeys?: string[]; formatStatus?: (value: any) => string }) {
  const loaded = hasRows(data, keys), records = list(rowsAt(data, keys));
  return <>{!loaded ? <Empty>{'尚未读取' + label + '。'}</Empty> : records.length === 0 ? <Empty>{'当前读取窗口没有' + label + '。'}</Empty> : records.map((row, index) => <SourceRecord key={recordId(row, String(index))} item={row} titleKeys={titleKeys} formatStatus={formatStatus}>{children?.(row)}</SourceRecord>)}</>;
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
    <SearchField className="pw-secondary-search" data-input-size="medium" aria-label="筛选当前读取的记录" placeholder={domain === 'memory' ? '查找记住的内容' : '查找待核对内容'} value={query} onChange={setQuery} shape="rounded" />
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
        <SearchField className="pw-secondary-search" data-input-size="medium" aria-label={tab === 'runs' ? '搜索共同工作' : '搜索团队与群'} placeholder={tab === 'runs' ? '查找目标或共同工作' : '查找名称或成员'} value={queries[tab]} onChange={value => setQueries(current => ({ ...current, [tab]: value }))} shape="rounded" />
        <div className="pw-object-list">{!hasRows(data, tab === 'runs' ? runKeys : definitionKeys) ? <Empty>尚未读取{tab === 'runs' ? '共同工作' : '团队记录'}。</Empty> : !filtered.length ? <Empty>{queries[tab].trim() ? '当前读取窗口没有匹配项。' : tab === 'runs' ? '当前读取窗口没有共同工作。' : '当前读取窗口没有团队记录。'}</Empty> : filtered.map(row => {
          const position = objects.indexOf(row), id = recordId(row, String(position));
          const context = tab === 'runs' ? sourceOf(row) : membersOf(row) || sourceOf(row);
          return <Button key={id} variant="ghost" trailingIcon={IconChevronRight} className="pw-object-row h-auto whitespace-normal" onClick={() => { remember(); setSelected({ kind: tab, id }); }}>
            <span className="pw-object-row-copy"><span className="pw-object-row-title">{titleOf(row)}</span>{(row.status || row.state) && <small className="pw-object-row-status">{statusText(row.status || row.state)}</small>}{context && <small>{context}</small>}{tab === 'runs' && summaryOf(row) && summaryOf(row) !== titleOf(row) && <span className="pw-object-row-summary">{summaryOf(row)}</span>}</span>
          </Button>;
        })}</div>
      </TabPanel>
    </Tabs>}
  </section>;
}

function AccountsWorkspace({ state, data, supported, run, registerTransientBack, onlyAccountId, startAdding = false }: { state: Data; data: Data; supported: (action: string) => boolean; run: Run; registerTransientBack: BackHandlerRegistration; onlyAccountId?: string; startAdding?: boolean }) {
  const [catalog, setCatalog] = useState<Data | null>(null), [review, setReview] = useState<Data | null>(null);
  const [busy, setBusy] = useState(false), [receipt, setReceipt] = useState<Data | null>(null), [query, setQuery] = useState('');
  const [options, setOptions] = useState({ reasoningEffort: '', outputReserve: '4096', imageTokenReserve: '0' });
  const [adding, setAdding] = useState(startAdding), [accountLabel, setAccountLabel] = useState(''), [needsAccountRead, setNeedsAccountRead] = useState(false);
  const [accountFeedback, setAccountFeedback] = useState<{ message: string; error: boolean } | null>(null);
  const alive = useRef(true), generation = useRef(0), operationPending = useRef(false);
  const closeReview = () => { generation.current++; setReview(null); };
  useEffect(() => { alive.current = true; return () => { alive.current = false; generation.current++; }; }, []);
  useEffect(() => { registerTransientBack(adding ? () => { if (!busy) setAdding(false); return true; } : review ? () => { if (!busy) closeReview(); return true; } : catalog ? () => { if (!busy) { generation.current++; setCatalog(null); } return true; } : null); return () => registerTransientBack(null); }, [adding, review, catalog, busy, registerTransientBack]);
  const pending = Array.isArray(state.ownerPending) ? state.ownerPending : [];
  const locked = busy || Boolean(state.modelSelectionBusy) || pending.some((key: string) => key.startsWith('accounts.'));
  const accounts = list(data.accounts), login = object(data.login), selected = object(data.selectedModelRef);
  const loginRecovery = accountLoginRecovery(login, data.accounts);
  const loginAccount = accounts.find(row => row.id === login.accountId);
  const catalogBody = object(catalog?.catalog), catalogAccount = accounts.find(row => row.id === catalogBody.accountId);
  const catalogCurrent = Boolean(catalog && catalog.selectionRevision === state.modelSelectionRevision && catalogAccount?.version === catalogBody.accountVersion);
  const reviewCurrent = Boolean(review && review.selectionRevision === state.modelSelectionRevision && accounts.some(row => row.id === review.accountId && row.version === review.accountVersion));
  const models = list(catalogBody.models), visibleModels = models.filter(row => [row.id, row.displayName].some(value => text(value).toLocaleLowerCase().includes(query.trim().toLocaleLowerCase())));
  const runAccount: Run = async (action, args = {}) => {
    if (!ACCOUNT_RECEIPT_ACTIONS.has(action)) return run(action, args);
    if (operationPending.current) throw new Error('请先等待本次账号操作的回执。');
    if (needsAccountRead && ['accounts.beginLogin', 'accounts.reauthorize'].includes(action)) throw new Error('请先刷新账号状态，核对原授权后再继续。');
    const original = ++generation.current; operationPending.current = true; setBusy(true); setAccountFeedback(null); setCatalog(null); setReview(null);
    try {
      const value = await run(action, args), message = accountActionReceipt(action, args, value);
      if (alive.current && original === generation.current) {
        setAccountFeedback({ message, error: false });
        if (action === 'accounts.beginLogin') setAdding(false);
        if (action === 'accounts.refresh') setNeedsAccountRead(false);
      }
      return value;
    } catch (failure) {
      if (alive.current && original === generation.current) {
        const feedback = accountActionFailure(failure);
        setAccountFeedback({ message: feedback.message, error: true });
        if (feedback.needsAccountRead && action !== 'accounts.refresh') setNeedsAccountRead(true);
      }
      throw failure;
    } finally { if (alive.current && original === generation.current) { operationPending.current = false; setBusy(false); } }
  };
  const act = (action: string, args: Data, label: string, primary = false) => <CapabilityAction action={action} args={args} supported={supported} run={runAccount} disabled={locked || needsAccountRead && action === 'accounts.reauthorize'} primary={primary}>{label}</CapabilityAction>;
  const readModels = async (row: Data) => {
    if (operationPending.current || locked) return;
    const original = ++generation.current; operationPending.current = true; setBusy(true); setCatalog(null); setReview(null); setReceipt(null); setQuery('');
    try { const result = object(await run('accounts.models', { accountId: row.id, expectedVersion: row.version })); if (alive.current && original === generation.current) setCatalog(result); }
    catch { /* The shared page displays the actual native failure. */ }
    finally { if (alive.current && original === generation.current) { operationPending.current = false; setBusy(false); } }
  };
  const prepare = async (kind: 'model' | 'logout', args: Data) => {
    if (operationPending.current || locked) return;
    const original = ++generation.current; operationPending.current = true; setBusy(true); setReceipt(null);
    try {
      const result = object(await run(kind === 'model' ? 'accounts.model.review' : 'accounts.logout.review', args));
      if (alive.current && original === generation.current) {
        const defaults = object(result.options);
        setOptions({ reasoningEffort: text(defaults.reasoningEffort), outputReserve: text(defaults.outputReserve, '4096'), imageTokenReserve: text(defaults.imageTokenReserve, '0') });
        setReview({ ...result, kind });
      }
    } catch { /* Preserve the current account; failed review is not a selection. */ }
    finally { if (alive.current && original === generation.current) { operationPending.current = false; setBusy(false); } }
  };
  const facts = object(object(review?.catalogFacts).facts), levelsFact = object(facts.REASONING_LEVELS);
  const levels = levelsFact.known === true && Array.isArray(levelsFact.value) ? levelsFact.value.filter((value: any) => typeof value === 'string') as string[] : [];
  const output = Number(options.outputReserve), imageReserve = Number(options.imageTokenReserve);
  const valid = /^\d+$/.test(options.outputReserve) && Number.isSafeInteger(output) && output >= 128 && output <= 32768 && /^\d+$/.test(options.imageTokenReserve) && Number.isSafeInteger(imageReserve) && imageReserve >= 0 && imageReserve <= 2_000_000 && (!options.reasoningEffort || levels.includes(options.reasoningEffort));
  const confirm = async () => {
    if (!review || locked || operationPending.current || !reviewCurrent || review.kind === 'model' && !valid) return;
    const original = review, attempt = ++generation.current; operationPending.current = true; setBusy(true);
    try {
      const result = object(await run(original.kind === 'model' ? 'accounts.model.select' : 'accounts.logout.confirm', { reviewHandle: original.reviewHandle, ...(original.kind === 'model' ? { options: { reasoningEffort: options.reasoningEffort, outputReserve: output, imageTokenReserve: imageReserve } } : {}) }));
      if (alive.current && attempt === generation.current) setReceipt({ kind: original.kind, ...result });
    } catch { if (alive.current && attempt === generation.current) setReceipt({ kind: original.kind, status: 'unknown' }); }
    finally { if (alive.current && attempt === generation.current) { operationPending.current = false; setBusy(false); closeReview(); setCatalog(null); } }
  };
  const known = (key: string) => object(facts[key]).known === true ? object(facts[key]).value : undefined;
  const actualReceipt = object(receipt?.receipt);
  const welcomeAccount = accounts.find(row => row.enabled === true && row.planUsageEnabled === true);
  const loginActive = ['pending', 'exchanging'].includes(text(login.status));
  return <>
    <Card className="pw-accounts-workspace" ><div hidden={Boolean(catalog)} className="pw-accounts-overview">
      <header className="pw-settings-object-heading"><div><h2>{onlyAccountId ? '账号与授权' : '你的账号'}</h2></div><div className="pw-form-actions">{!onlyAccountId && <PressButton compact disabled={locked || loginActive || needsAccountRead || !supported('accounts.beginLogin')} onClick={() => { setAccountFeedback(null); setAdding(true); }}>添加账号</PressButton>}{act('accounts.refresh', {}, '刷新状态')}</div></header>
      {needsAccountRead && <p className="pw-field-hint">请先刷新账号状态，核对上次操作后再添加账号。</p>}
      {accountFeedback && !adding && <p className={accountFeedback.error ? 'pw-field-error' : 'pw-receipt-copy'} role={accountFeedback.error ? 'alert' : 'status'}>{accountFeedback.message}</p>}
      {state.modelRoute === 'account' && data.selectedModelRef && <div className="pw-selected-object"><h3>当前账号模型</h3><Facts items={[[ '模型', selected.modelId ], ['账号', accounts.find(row => row.id === selected.accountId)?.label ], ['可用状态', data.selectedModelUsable ? '当前引用可用' : '需要重新读取目录并选择' ]]} /></div>}
      {data.planWelcomeRequired === true && welcomeAccount && <div className="pw-receipt-copy"><h3>已通过 ChatGPT 登录</h3><p>在本应用选择账号模型后，推理使用你的 ChatGPT 套餐，并受套餐和此应用的使用限制。登录不授予手机操作权限；图片仍需任务授权和目录支持。</p><div className="pw-form-actions">{act('accounts.acknowledgePlanWelcome', {accountId: welcomeAccount.id, expectedVersion: welcomeAccount.version}, '知道了')}{act('accounts.manageUsage', {}, '管理用量')}</div></div>}
      <Collection data={onlyAccountId ? { ...data, accounts: accounts.filter(row => row.id === onlyAccountId) } : data} keys={['accounts']} label="账号" formatStatus={accountStatusText}>{row => <>
        <Facts items={[[ '套餐', row.plan || row.planName ], ['套餐使用权限', row.planUsageEnabled ? '已授予' : '尚未授予'], ['状态', accountStatusText(row.status)], ['冷却至', timeText(row.cooldownUntil)], ['本机选择', data.selectedAccountId === row.id ? '当前账号' : undefined]]} />
        {row.usageLimited && <p className="pw-field-hint">套餐请求已暂停。明确允许再试后，仍需回到原任务继续；不会自动发送请求。</p>}
        {row.error && <p className="pw-field-error" role="alert">{row.error === 'LOGIN_EXPIRED' ? '上次登录授权已过期，请重新登录。' : text(row.error)}</p>}
        <div className="pw-form-actions">
          {act('accounts.select', { accountId: row.id, expectedVersion: row.version }, '选择此账号')}
          <Button variant="secondary" disabled={locked || !supported('accounts.models') || !row.enabled || !row.planUsageEnabled || row.usageLimited} onClick={() => readModels(row)}>读取模型目录</Button>
          {row.usageLimited && act('accounts.retryPlanUsage', { accountId: row.id, expectedVersion: row.version }, '允许手动再试')}
          {row.enabled && !loginActive && !(login.status === 'expired' && login.accountId === row.id) && (['signed_out', 'reauthorization_required'].includes(text(row.status)) || !row.planUsageEnabled) && act('accounts.reauthorize', {accountId: row.id, expectedVersion: row.version}, '重新登录此账号')}
        </div>
        <Accordion variant="divided" className="pw-account-management"><AccordionItem id={'account-management-' + row.id} title="账号管理与授权" description="启停、授权、套餐用量与退出">
          <StretchSwitch label="启用此账号" description="关闭会停止新的账号请求；已发生的远端操作仍需按原回执核对。" checked={row.enabled === true} disabled={locked || !supported('accounts.setEnabled')} onChange={enabled => { void runAccount('accounts.setEnabled', {accountId: row.id, expectedVersion: row.version, enabled}).catch(() => {}); }} />
          <div className="pw-form-actions">
          {row.planUsageEnabled && act('accounts.manageUsage', {}, '管理套餐用量')}
          {row.enabled && !loginActive && row.status === 'connected' && act('accounts.refreshAuthorization', {accountId: row.id, expectedVersion: row.version}, '刷新授权')}
          {row.enabled && !loginActive && ['connected', 'cooldown'].includes(text(row.status)) && act('accounts.reauthorize', {accountId: row.id, expectedVersion: row.version}, '重新授权此账号')}
          <Button variant="secondary" disabled={locked || !supported('accounts.logout.review')} onClick={() => prepare('logout', { accountId: row.id, expectedVersion: row.version })}>核对并退出</Button>
          </div>
        </AccordionItem></Accordion>
      </>}</Collection>
      {login.sessionId && <section className="pw-selected-object" aria-label="本次授权"><h3>本次授权</h3><Facts items={[[ '账号', loginAccount?.label ], [ '状态', accountStatusText(login.status) ], ['到期时间', timeText(login.expiresAt) ]]} />
        {typeof login.error === 'string' && login.error.length > 0 && <p className="pw-field-error" role="alert">本次授权错误码：{text(login.error)}</p>}
        {login.status === 'pending' && <div className="pw-form-actions">{act('accounts.openAuthorization', { sessionId: login.sessionId }, '打开原授权页面')}{act('accounts.cancelLogin', { sessionId: login.sessionId }, '取消本次授权')}</div>}
        {loginRecovery.kind !== 'none' && <>
          <p className="pw-field-hint" role="status">这次登录授权已过期，原授权页面不能继续使用。{loginRecovery.kind === 'existing' ? '重新登录会为此账号准备新的授权。' : loginRecovery.kind === 'new' ? '重新开始登录，完成后再选择账号模型。' : loginRecovery.kind === 'disabled' ? '此账号已停用，请先在上方启用，再重新登录。' : '请刷新账号状态，核对原账号后再继续。'}</p>
          {needsAccountRead && <p className="pw-field-hint">上次操作结果尚未确认，请先刷新账号状态。</p>}
          <div className="pw-form-actions">
            {loginRecovery.kind === 'existing' && act('accounts.reauthorize', { accountId: loginRecovery.accountId, expectedVersion: loginRecovery.expectedVersion }, '重新登录此账号', true)}
            {loginRecovery.kind === 'new' && <PressButton compact disabled={locked || needsAccountRead || !supported('accounts.beginLogin')} onClick={() => { setAccountFeedback(null); setAdding(true); }}>重新开始登录</PressButton>}
            {(needsAccountRead || loginRecovery.kind === 'unavailable') && act('accounts.refresh', {}, '核对原授权状态')}
          </div>
        </>}
      </section>}
      {receipt && <div className="pw-receipt-copy" role="status">{receipt.status !== 'saved' ? '原操作结果待核对。请刷新账号状态；不会自动重试。' : receipt.kind === 'model' ? '模型选择已保存。当前使用状态以上方统一模型状态为准；已有任务保留原模型。' : actualReceipt.signedOutLocally ? '已在本机退出，账号记录保留。' + (actualReceipt.credentialCleanupRequired ? ' 本机凭据仍需清理。' : '') + (actualReceipt.remoteRevocationConfirmed ? ' 服务商确认撤销授权。' : ' 远端撤销尚未确认。') : '收到原退出回执，请刷新并核对本机状态。'}</div>}
    </div></Card>
    <SettingsDialog isOpen={adding} title="添加 ChatGPT 账号" onClose={() => { if (!busy) setAdding(false); }}>
      <TextField label="账号名称（可选）" placeholder="我的 ChatGPT 账号" value={accountLabel} maxLength={80} isDisabled={busy} onChange={setAccountLabel} hint="名称只用于本机辨认。登录会打开系统浏览器。" />
      {accountFeedback && <p className={accountFeedback.error ? 'pw-field-error' : 'pw-receipt-copy'} role={accountFeedback.error ? 'alert' : 'status'}>{accountFeedback.message}</p>}
      {needsAccountRead && <p className="pw-field-hint">请先返回并刷新账号状态，核对原授权后再继续。</p>}
      <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => setAdding(false)}>返回</Button><PressButton compact disabled={locked || needsAccountRead || loginActive} onClick={() => {
        try { void runAccount('accounts.beginLogin', accountLoginArguments(accountLabel)).catch(() => {}); }
        catch (failure) { setAccountFeedback({ message: (failure as Error).message, error: true }); }
      }}>{busy ? '正在准备授权…' : '用 ChatGPT 登录'}</PressButton></div>
    </SettingsDialog>
    {catalog && <Card className="pw-account-catalog" title="此账号的模型目录" description="能力以这次实际目录为准；选择不会自动授权手机操作。">
      <Button variant="ghost" leadingIcon={IconArrowLeft} onClick={() => { generation.current++; setCatalog(null); }}>返回账号</Button>
      {!catalogCurrent && <p role="alert" className="pw-field-error">账号或模型设置已变化，请重新读取目录。</p>}
      {models.length > 8 && <SearchField label="筛选账号模型" value={query} onChange={setQuery} placeholder="模型名称" />}
      {visibleModels.length === 0 ? <Empty>{models.length ? '没有匹配的模型。' : '服务商没有返回可用模型。'}</Empty> : visibleModels.map(row => <SourceRecord key={text(row.id)} item={row} titleKeys={['displayName', 'id']}><Button variant="secondary" disabled={locked || !catalogCurrent || !supported('accounts.model.review')} onClick={() => prepare('model', { accountId: catalogBody.accountId, modelId: row.id })}>核对能力与选项</Button></SourceRecord>)}
    </Card>}
    <SettingsDialog isOpen={review !== null} title={review?.kind === 'logout' ? '退出这个账号？' : text(review?.displayName, '核对账号模型')} onClose={() => { if (!busy) closeReview(); }}>{review && <>
      <Facts items={[[ '账号', review.accountLabel || review.accountId ], ['模型', review.modelId ]]} />
      {!reviewCurrent && <p role="alert" className="pw-field-error">原账号或模型设置已变化，请返回后重新核对。</p>}
      {review.kind === 'logout' ? <p>{text(review.message)}</p> : <div className="pw-form-grid">
        <Facts items={[[ '目录上下文', known('CONTEXT_WINDOW') ? text(object(known('CONTEXT_WINDOW')).tokens) + ' tokens' : '未声明' ], ['目录最大输出', known('OUTPUT_TOKENS') === undefined ? '未声明' : text(known('OUTPUT_TOKENS')) + ' tokens'], ['图片输入', known('IMAGE_INPUT') === undefined ? '未声明' : known('IMAGE_INPUT') ? '支持，仍需任务授权' : '不支持' ]]} />
        <SelectField label="推理设置" value={options.reasoningEffort} options={[{ id: '', label: '服务默认' }, ...levels.map(level => ({ id: level, label: level }))]} onChange={value => setOptions(current => ({ ...current, reasoningEffort: value }))} />
        <TextField label="本地输出预留" inputMode="numeric" value={options.outputReserve} onChange={value => setOptions(current => ({ ...current, outputReserve: value.replace(/\D/g, '').slice(0, 5) }))} hint="128–32768 tokens；用于输入准入，不是服务端输出限制。" />
        <TextField label="每张图片本地预留" inputMode="numeric" value={options.imageTokenReserve} onChange={value => setOptions(current => ({ ...current, imageTokenReserve: value.replace(/\D/g, '').slice(0, 7) }))} hint="0–2000000；0 表示未配置。已知上下文时需配置后才可发送图片。" />
        <p className="pw-field-hint">选择用于之后开始的任务。当前任务继续使用原模型；账号登录、目录能力和工具授权分别核对。</p>
      </div>}
      <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={closeReview}>返回</Button><PressButton compact disabled={locked || !reviewCurrent || review.kind === 'model' && !valid} onClick={confirm}>{busy ? '等待原回执…' : review.kind === 'logout' ? '确认退出此账号' : '选择此模型和选项'}</PressButton></div>
    </>}</SettingsDialog>
  </>;
}

function McpWorkspace({ state, data, supported, run, registerTransientBack, error }: { state: Data; data: Data; supported: (action: string) => boolean; run: Run; registerTransientBack: BackHandlerRegistration; error?: string }) {
  const [review, setReview] = useState<Data | null>(null), [selected, setSelected] = useState<string[]>([]);
  const [detail, setDetail] = useState<Data | null>(null), [busy, setBusy] = useState(false);
  const [adding, setAdding] = useState(false);
  const [entryMode, setEntryMode] = useState('guided');
  const [servicePreset, setServicePreset] = useState('exa');
  const [direct, setDirect] = useState({ name: '', endpoint: '', protocolVersion: '2026-07-28' });
  const alive = useRef(true), view = useRef(0);
  const closeReview = () => { view.current++; setReview(null); };
  useEffect(() => { alive.current = true; return () => { alive.current = false; view.current++; }; }, []);
  useEffect(() => { registerTransientBack(review ? () => { if (!busy) closeReview(); return true; } : detail ? () => { setDetail(null); return true; } : adding ? () => { if (!busy) setAdding(false); return true; } : null); return () => registerTransientBack(null); }, [review, detail, adding, busy, registerTransientBack]);
  const responses = object(object(state.ownerResponses).mcp), picked = object(responses.pickConfig), config = object(object(picked.result).config);
  const progress = object(state.mcpImport), phase = text(progress.phase), pending = Array.isArray(state.ownerPending) ? state.ownerPending : [];
  const writing = busy || ['mcp.installMcp', 'mcp.setEnabledReviewed', 'mcp.setToolGrantReviewed'].some(key => pending.includes(key));
  const preparing = pending.includes('mcp.discoverMcp');
  const choosing = pending.includes('mcp.pickConfig');
  const activeConfig = object(progress.configuration), inputConfig: Data = entryMode === 'address' ? direct : config;
  const preset = toolServicePresets.find(item => item.id === servicePreset) || toolServicePresets[0];
  const previewMatchesInput = entryMode !== 'guided' && ['name', 'endpoint', 'protocolVersion'].every(key => text(activeConfig[key]) === text(inputConfig[key], key === 'protocolVersion' ? '2026-07-28' : '').trim()) &&
    (entryMode === 'address' || activeConfig.id === config.id);
  const discoverDirect = async () => {
    if (writing || preparing || choosing || phase === 'unknown') return;
    closeReview(); setBusy(true);
    try { await run('mcp.discoverDirect', direct); }
    catch { /* The shared failure is also rendered inside the active dialog. */ }
    finally { if (alive.current) setBusy(false); }
  };
  const openReview = async (action: string, args: Data, kind: string) => {
    const original = ++view.current; setBusy(true);
    try { const receipt = object(await run(action, args)); if (alive.current && original === view.current) { setSelected([]); setReview({ ...receipt, kind }); } }
    catch { /* The shared failure is also rendered inside the active dialog. */ }
    finally { if (alive.current) setBusy(false); }
  };
  const confirm = async () => {
    if (!review || writing) return;
    const original = review; setBusy(true);
    try {
      await run(original.kind === 'install' ? 'mcp.installImport' : original.kind === 'grant' ? 'mcp.confirmGrant' : 'mcp.confirmEnabled', { reviewHandle: original.reviewHandle, ...(original.kind === 'install' ? { tools: selected } : {}) });
      if (alive.current) { closeReview(); setDetail(null); if (original.kind === 'install') setAdding(false); }
    } catch { if (alive.current) closeReview(); }
    finally { if (alive.current) setBusy(false); }
  };
  const installed = list(data.plugins), currentDetail = detail && installed.find(item => item.id === detail.id && item.installationId === detail.installationId);
  const loaded = hasRows(data, ['plugins']);
  const hasDraft = !(phase === 'installed' && previewMatchesInput) && !!(direct.name || direct.endpoint || picked.ok === true || phase && !['idle', 'installed'].includes(phase));
  const progressCopy: Record<string, string> = { discovering: '正在读取服务声明，等待原发现回执…', discovered: '工具发现完成，可以打开预览。', installing: '原安装正在保存，等待实际回执…', installed: '安装已保存，保持停用；工具权限需要逐项核对。', unknown: '安装结果待核对，请刷新已安装列表；不会自动重新安装。', stale: '原发现已变化或过期，请重新核对配置。', failed: '原发现未完成，请查看回执后核对服务。' };
  return <>
    <Card className="pw-mcp-workspace">
      <header className="pw-settings-object-heading"><div><h2>MCP 服务</h2><p>接入外部工具，按需核对每项权限。</p></div>{installed.length > 0 && !hasDraft && <Button leadingIcon={IconAdd} disabled={writing} onClick={() => setAdding(true)}>添加服务</Button>}</header>
      {!loaded ? <p className="pw-secondary-empty" role="status">尚未取得服务清单，请使用右上角刷新重新读取。</p> : installed.length === 0 ? <div className="pw-mcp-empty"><IconMcp size={30} aria-hidden /><h3>连接你的工具服务</h3><p>填写服务地址或导入配置，先查看提供的工具，再决定安装与授权。</p>{!hasDraft && <Button leadingIcon={IconAdd} disabled={writing} onClick={() => setAdding(true)}>添加 MCP 服务</Button>}</div> : <Collection data={data} keys={['plugins']} label="已安装服务">{row => <>
        <Facts items={[[ '版本', object(row.manifest).version ], ['状态', row.enabled ? '已启用' : '已停用'], ['服务状态', row.mcpStatus]]} />
        <div className="pw-form-actions"><Button variant="secondary" disabled={writing || !supported('mcp.read')} onClick={async () => { const original = ++view.current; try { const receipt = object(await run('mcp.read', { id: row.id, installationId: row.installationId })); if (alive.current && original === view.current) setDetail(receipt); } catch {} }}>查看工具与权限</Button><Button variant="secondary" disabled={writing || !supported('mcp.reviewEnabled')} onClick={() => openReview('mcp.reviewEnabled', { id: row.id, installationId: row.installationId, enabled: !row.enabled }, 'enable')}>{row.enabled ? '核对并停用' : '核对并启用'}</Button></div>
      </>}</Collection>}
      {hasDraft && <div className="pw-mcp-draft"><IconMcp size={22} aria-hidden /><div><strong>{text(activeConfig.name) || direct.name || text(config.name) || '待添加的 MCP 服务'}</strong><p>{phase === 'discovered' ? '已读取工具声明，等待核对' : phase === 'unknown' ? '原安装结果尚未确认' : '已保留本次输入与发现记录'}</p></div><Button variant="secondary" trailingIcon={IconChevronRight} disabled={writing} onClick={() => setAdding(true)}>继续添加</Button></div>}
      {phase && phase !== 'idle' && <p className="pw-receipt-copy" role="status">{progressCopy[phase] || text(progress.message)}</p>}
      {preparing && <CapabilityAction action="mcp.cancel" args={{ operationId: progress.operationId }} supported={supported} run={run}>取消原发现</CapabilityAction>}
      {[['启停', object(progress.enableReceipt)], ['工具权限', object(progress.grantReceipt)]].map(([label, raw]) => { const receipt = object(raw); return receipt.requestId ? <p key={text(label)} className="pw-receipt-copy" role="status">{text(label)}：{receipt.status === 'saved' ? '原安装的更改已保存。' : receipt.status === 'pending' ? '等待原操作回执…' : '结果待核对，请刷新原安装。'}</p> : null; })}
    </Card>
    <SettingsDialog isOpen={adding && !review} title="添加 MCP 服务" closeLabel="返回服务列表" onClose={() => { if (!writing) setAdding(false); }}>
      <div className="pw-mcp-editor">
      <RadioOptions className="pw-mcp-entry-mode" label="添加方式" value={entryMode} onChange={value => { closeReview(); setEntryMode(value); }} isDisabled={writing || preparing || choosing} options={[{ id: 'guided', label: '搜索与邮件' }, { id: 'address', label: '填写地址' }, { id: 'file', label: '导入配置' }]} />
      {entryMode === 'guided' ? <>
        <SelectField label="想接入的能力" value={servicePreset} onChange={setServicePreset} isDisabled={writing || preparing || choosing} options={toolServicePresets.map(item => ({ id: item.id, label: item.name }))} />
        <div className="pw-selected-object"><h3>{preset.purpose}</h3><p>{preset.status}</p><p className="pw-field-hint">{preset.preparation}</p><p className="pw-field-hint">{preset.destination}</p>
          <p><a href={preset.source} target="_blank" rel="noopener noreferrer">{preset.sourceLabel}</a></p>
          {preset.endpoint ? <Button variant="secondary" disabled={writing || preparing || choosing} onClick={() => { closeReview(); setDirect({ name: preset.name, endpoint: preset.endpoint, protocolVersion: preset.protocolVersion }); setEntryMode('address'); }}>填入此服务，继续核对</Button> : <p className="pw-field-hint">准备完成后，从原项目的本机 MCP 入口发现这份准确源码；本页不会把未准备的邮箱显示为已连接。</p>}
        </div>
      </> : entryMode === 'address' ? <>
        <div className="pw-form-grid">
          <TextField label="服务名称" placeholder="给这个服务起个名字" value={direct.name} onChange={name => setDirect(value => ({ ...value, name }))} maxLength={80} isDisabled={writing || preparing || choosing} />
          <TextField label="HTTPS 服务地址" placeholder="https://example.com/mcp" value={direct.endpoint} onChange={endpoint => setDirect(value => ({ ...value, endpoint }))} maxLength={2000} autoComplete="off" isDisabled={writing || preparing || choosing} hint="填写服务提供的 MCP 端点，不要把令牌或密码放进地址。" />
        </div>
        <Accordion variant="divided"><AccordionItem id="mcp-connection-options" title="连接选项" description={`协议 ${direct.protocolVersion}`}>
          <SelectField label="协议版本" value={direct.protocolVersion} onChange={protocolVersion => setDirect(value => ({ ...value, protocolVersion }))} isDisabled={writing || preparing || choosing} options={[{ id: '2026-07-28', label: '2026-07-28' }, { id: '2025-11-25', label: '2025-11-25' }]} />
        </AccordionItem></Accordion>
        <div className="pw-form-actions"><Button disabled={writing || preparing || choosing || phase === 'unknown' || !supported('mcp.discoverDirect') || !direct.name.trim() || !direct.endpoint.trim()} onClick={discoverDirect}>发现服务工具</Button></div>
      </> : <>
        <HostFileUpload label="选择 MCP 配置" onChoose={() => { closeReview(); void run('mcp.pickConfig', {}).catch(() => {}); }} disabled={writing || preparing || !supported('mcp.pickConfig') || choosing} status={choosing ? 'importing' : picked.ok === false ? 'error' : 'idle'} statusDescription="系统选择器读取不含凭据的 JSON 配置，上限 64 KB" error={picked.ok === false ? text(picked.error) : undefined} />
        {picked.ok === true && <div className="pw-selected-object"><h3>已读取的配置</h3><Facts items={[[ '名称', config.name ], ['端点', config.endpoint ], ['协议版本', config.protocolVersion ]]} /><CapabilityAction action="mcp.discoverImport" args={{ importRequestId: picked.requestId }} supported={supported} run={run} disabled={writing || preparing || choosing || phase === 'unknown'}>发现并预览服务</CapabilityAction></div>}
      </>}
      <p className="pw-field-hint">发现只读取服务声明；安装不会调用工具，也不会自动启用或授权。</p>
      {phase === 'discovered' && <div className="pw-selected-object"><h3>本次发现</h3><Facts items={[[ '服务', activeConfig.name ], ['地址', activeConfig.endpoint ], ['协议版本', activeConfig.protocolVersion ]]} />{!previewMatchesInput && <p className="pw-field-hint">输入已变化，请重新发现后再安装。</p>}<Button variant="secondary" disabled={writing || preparing || choosing || !previewMatchesInput || !supported('mcp.reviewImport')} onClick={() => openReview('mcp.reviewImport', {}, 'install')}>核对工具并安装</Button></div>}
      {phase && phase !== 'idle' && <p className="pw-receipt-copy" role="status">{progressCopy[phase] || text(progress.message)}</p>}
      {preparing && <CapabilityAction action="mcp.cancel" args={{ operationId: progress.operationId }} supported={supported} run={run}>取消原发现</CapabilityAction>}
      {error && <p className="pw-field-error" role="alert">{error}</p>}
      <Button variant="ghost" leadingIcon={IconArrowLeft} disabled={writing} onClick={() => setAdding(false)}>保留输入并返回</Button>
      </div>
    </SettingsDialog>
    <SettingsDialog isOpen={detail !== null} title="当前安装的工具与权限" onClose={() => setDetail(null)}>{detail && <>
      {error && <p className="pw-field-error" role="alert">{error}</p>}
      <Facts items={[[ '服务', object(detail.manifest).name || detail.id ], ['版本', object(detail.manifest).version ]]} />
      {!currentDetail && <p role="alert" className="pw-field-error">原安装已变化，请关闭后刷新。</p>}
      {list(object(detail.manifest).tools).map(tool => { const granted = Array.isArray(currentDetail?.grants) && currentDetail.grants.includes('tool:' + tool.name); return <SourceRecord key={text(tool.name)} item={tool}><Facts items={[[ '地址', tool.endpoint ], ['权限', granted ? '已允许' : '未允许']]} /><Button variant="secondary" disabled={!currentDetail || writing || !supported('mcp.reviewGrant')} onClick={() => openReview('mcp.reviewGrant', { id: detail.id, installationId: detail.installationId, grantsDigest: currentDetail?.grantsDigest, tool: tool.name, granted: !granted }, 'grant')}>{granted ? '核对并撤销权限' : '核对并允许工具'}</Button></SourceRecord>; })}
      <p className="pw-field-hint">启用服务不代替逐项工具授权；敏感执行仍需原任务按具体参数批准。</p>
    </>}</SettingsDialog>
    <SettingsDialog isOpen={review !== null} title={review?.kind === 'install' ? '核对这次发现并安装' : review?.kind === 'grant' ? review.granted ? '允许这个工具？' : '撤销工具权限？' : review?.enabled ? '启用这项安装？' : '停用这项安装？'} onClose={() => { if (!busy) closeReview(); }}>{review && <>
      <Facts items={[[ '服务', review.name || object(review.manifest).name || review.id ], ['工具', object(review.selectedTool).name ], ['地址', review.endpoint || object(review.selectedTool).endpoint ], ['协议', review.protocolVersion ], ['预览到期', timeText(review.expiresAt) ]]} />
      {review.kind === 'install' ? <><p>选择要安装的工具（{selected.length}/12）。服务说明和参数仅供核对。</p>{list(review.tools).map(tool => <div key={text(tool.name)}><CheckboxField isSelected={selected.includes(tool.name)} isDisabled={busy || !tool.supported || selected.length >= 12 && !selected.includes(tool.name)} onChange={value => setSelected(current => value ? [...current, tool.name] : current.filter(name => name !== tool.name))}>{text(tool.name)}</CheckboxField><p className="pw-field-hint">{text(tool.description || object(tool.declaration).description)}{!tool.supported ? ` · 当前不支持：${text(tool.unsupportedReason) || '参数或协议超出当前支持范围'}` : ''}</p></div>)}{review.mcpSource && <p>包含资源或提示来源；不选工具可只安装参考来源。</p>}{review.expectedInstallationId && <p>将替换同标识的旧安装、清空旧工具授权；已发生的远端效果不会撤回。</p>}<p>保存后保持停用且不授予工具权限。</p></> : review.kind === 'grant' ? <><p>{text(object(review.selectedTool).description)}</p><Accordion variant="divided"><AccordionItem id="mcp-tool-schema" title="工具参数结构"><pre className="pw-source-code">{JSON.stringify(object(object(review.selectedTool).schema), null, 2)}</pre></AccordionItem></Accordion><p>仅改变这项安装的一个工具权限。原任务仍需按实际参数批准敏感动作。</p></> : <p>{review.enabled ? '仅启用这项安装。工具仍需逐项授权。' : '停止继续使用这项安装；已发生的远端效果不会撤回。'}</p>}
      <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={closeReview}>取消</Button><PressButton compact disabled={busy || review.kind === 'install' && selected.length === 0 && !review.mcpSource} onClick={confirm}>{busy ? '等待受理…' : review.kind === 'install' ? '确认安装' : '确认更改'}</PressButton></div>
    </>}</SettingsDialog>
  </>;
}

function DomainPage({ domain, state, data, supported, run, registerTransientBack, candidateTarget, onReviewCandidate, onOpenModels, onNavigate, onPrepareTools, onOpenProjects, initialProjectId, localDraft, onLocalDraftChange, alreadyShownError, teamAttentionFocus, onTeamAttentionConsumed }: { teamAttentionFocus?:Data;onTeamAttentionConsumed?:(requestId:string)=>void; domain: string; state: Data; data: Data; supported: (action: string) => boolean; run: Run; registerTransientBack: BackHandlerRegistration; candidateTarget: LearningCandidateTarget | null; onReviewCandidate: (target: LearningCandidateTarget) => void; onOpenModels: () => void; onNavigate: (domain: 'plugins' | 'pluginCatalog' | 'mcp' | 'skills') => void; onPrepareTools?: () => void; onOpenProjects?: () => void; initialProjectId?: string; localDraft?: LocalWorkspaceDraft; onLocalDraftChange?: (draft: LocalWorkspaceDraft) => void; alreadyShownError?: string }) {
  const title = SECONDARY_DOMAINS.find(x => x.id === domain)?.title || '连接与能力';
  if (domain === 'accounts') return <AccountsWorkspace state={state} data={data} supported={supported} run={run} registerTransientBack={registerTransientBack} />;
  if (domain === 'devices') return <DevicesWorkspace state={state} data={data} supported={supported} run={run} registerTransientBack={registerTransientBack} openModels={onOpenModels} />;
  if (domain === 'remote' || domain === 'ssh') return <ConnectionWorkspace domain={domain} state={state} data={data} supported={supported} run={run} registerTransientBack={registerTransientBack} />;
  if (domain === 'memory' || domain === 'memoryReview') return <MemoryWorkspace domain={domain} run={run} registerTransientBack={registerTransientBack} initialCandidate={candidateTarget} alreadyShownError={alreadyShownError} />;
  if (domain === 'learning') return <LearningWorkspace state={state} data={data} run={run} registerTransientBack={registerTransientBack} onReviewCandidate={onReviewCandidate} />;
  if (domain === 'automation') return <ReminderWorkspace state={state} data={data} supported={supported} run={run} registerTransientBack={registerTransientBack} />;
  if (domain === 'workProfiles') return <WorkProfilesWorkspace state={state} supported={supported} run={run} registerTransientBack={registerTransientBack} />;
  if (domain === 'commitments') return <CommitmentsWorkspace state={state} supported={supported} run={run} registerTransientBack={registerTransientBack} />;
  if (domain === 'channels') return <ChannelsWorkspace state={state} data={data} supported={supported} run={run} registerTransientBack={registerTransientBack} />;
  if (domain === 'mcp') return <McpWorkspace state={state} data={data} supported={supported} run={run} registerTransientBack={registerTransientBack} error={alreadyShownError} />;
  if (domain === 'skills') return <SkillsWorkspace state={state} run={run} registerTransientBack={registerTransientBack} onPrepareTools={onPrepareTools} />;
  if (domain === 'plugins' || domain === 'pluginCatalog') return <PluginsWorkspace mode={domain} state={state} supported={supported} run={run} registerTransientBack={registerTransientBack} onNavigate={onNavigate} />;
  if (domain === 'teams') return <TeamsWorkspace onAttentionConsumed={onTeamAttentionConsumed} attentionFocus={teamAttentionFocus} state={state} run={run} registerTransientBack={registerTransientBack} alreadyShownError={alreadyShownError} />;
  if (domain === 'groups') return <GroupsWorkspace state={state} data={data} supported={supported} run={run} registerTransientBack={registerTransientBack} />;
  if (domain === 'media') return <MediaWorkspace state={state} run={run} registerTransientBack={registerTransientBack} />;
  if (domain === 'realtime') return <RealtimeWorkspace state={state} run={run} registerTransientBack={registerTransientBack} />;
  if (domain === 'proxy') return <ProxyWorkspace state={state} run={run} registerTransientBack={registerTransientBack} />;
  if (domain === 'local') return <LocalWorkspace state={state} data={data} initialProjectId={initialProjectId} draft={localDraft} onDraftChange={onLocalDraftChange} run={run} supported={supported} registerTransientBack={registerTransientBack} onPrepareTools={onPrepareTools} onOpenProjects={onOpenProjects} />;
  return <Card title={title}><Empty>尚未读取此能力的当前记录。</Empty></Card>;
}

const ARCHIVE_SECTIONS: Record<string, string> = { projects: '项目与私人工作副本', artifacts: '固定成果与私人原件', memory: '记忆档案', experiences: '聊天、任务与工具回执快照', remoteConnections: '电脑连接配置', sshConnections: 'SSH 配置', settings: '模型连接与工作配置', skills: '技能正文与来源', schedules: '提醒与排程规则' };
const bytesText = (value: any) => typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value >= 1024 ** 3 ? (value / 1024 ** 3).toFixed(2) + ' GiB' : value >= 1024 ** 2 ? (value / 1024 ** 2).toFixed(1) + ' MiB' : value >= 1024 ? (value / 1024).toFixed(1) + ' KiB' : value + ' B' : '未完整读取';
type DataPageProps = { data: Data; supported: (action: string) => boolean; run: Run; registerTransientBack: BackHandlerRegistration; navigate: (page: string) => void };

function ArchivePassword({ data, supported, run }: Pick<DataPageProps, 'data' | 'supported' | 'run'>) {
  const [password, setPassword] = useState(''), [repeated, setRepeated] = useState('');
  const exporting = ['export', 'webdav_upload'].includes(data.passwordPrompt);
  const cancel = () => { setPassword(''); setRepeated(''); void run('archives.password.cancel', { promptId: data.passwordPromptId }).catch(() => {}); };
  useEffect(() => { const hide = () => { if (document.visibilityState !== 'visible') { setPassword(''); setRepeated(''); } }; document.addEventListener('visibilitychange', hide); return () => document.removeEventListener('visibilitychange', hide); }, []);
  return <SettingsDialog isOpen title={exporting ? '设置备份口令' : '解密所选备份'} onClose={cancel}>
    <p>至少 12 个字符。请独立保管口令，应用不会保存；遗失后无法恢复。</p>
    <TextField label="备份口令" type="password" autoComplete="new-password" value={password} onChange={value => setPassword(value.slice(0, 1024))} />
    {exporting && <TextField label="再次输入口令" type="password" autoComplete="new-password" value={repeated} onChange={value => setRepeated(value.slice(0, 1024))} />}
    <div className="pw-form-actions"><Button variant="secondary" onClick={cancel}>取消</Button><CapabilityAction action="archives.password.use" args={{ promptId: data.passwordPromptId, password }} supported={supported} run={async (action, args) => { setPassword(''); setRepeated(''); return run(action, args); }} disabled={data.busy || password.length < 12 || exporting && password !== repeated} primary>{data.passwordPrompt === 'webdav_upload' ? '准备密文，继续审阅' : exporting ? '加密并导出' : '解密并只读核对'}</CapabilityAction></div>
  </SettingsDialog>;
}

const ARCHIVE_COUNT_LABELS: Record<string, string> = { apiConnections: '模型连接', modelGroups: '模型组', workProfiles: '工作配置', preferences: '偏好', skills: '技能', schedules: '排程', requiresProjectRebinding: '待重选项目' };
function ArchiveConfigurationDetails({ value, result = false }: { value: Data; result?: boolean }) {
  const counts = object(value.counts), rows = result ? list(value.rows) : list(value.entries).length ? list(value.entries) : list(value.identities);
  const pending = list(value.requiresRecreation), conflicts = list(value.sameNameConflicts), omitted = list(value.omitted);
  return <>
    <Facts items={Object.entries(counts).map(([key, count]) => [ARCHIVE_COUNT_LABELS[key] || key, count])} />
    {rows.length > 0 && <Accordion><AccordionItem title={result ? '逐项核对原恢复写入' : '查看包含条目'}>{rows.map((row, index) => <SourceRecord key={first(row, ['id', 'sourceId'], String(index))} item={{ title: first(row, ['name', 'title'], row.kind === 'preferences' ? '回答与操作偏好' : '条目 ' + (index + 1)) }}><Facts items={[[ '模型', row.model ], ['接口', row.baseUrl ], ['原来源', row.sourceId ], ['新身份', row.id ], ['结果', result ? row.found === true ? '已核对原恢复写入' : '尚未确认写入' : undefined ], ['工作配置', row.restoredPendingReview === true ? '待重新选择来源并审核' : undefined ]]} /></SourceRecord>)}</AccordionItem></Accordion>}
    {pending.length > 0 && <div className="pw-selected-object"><h3>以下连接仍需在本机重新创建</h3><p>这些条目没有恢复为可用连接，请在模型连接页重新填写并确认本机传输设置。</p>{pending.map((row, index) => <SourceRecord key={first(row, ['sourceId', 'id'], String(index))} item={{ title: first(row, ['name'], '待重建连接') }}><Facts items={[[ '接口', object(row.configuration).baseUrl ], ['模型', object(row.configuration).model ], ['原因', row.reason ]]} /></SourceRecord>)}</div>}
    {conflicts.length > 0 && <p>发现 {conflicts.length} 个同名条目：{conflicts.map(row => text(row.name)).join('、')}。此次创建新身份，保留已有条目。</p>}
    {omitted.length > 0 && <Accordion><AccordionItem title={'未纳入项目 · ' + omitted.length}>{omitted.map((row, index) => <p key={index}>{text(row.id)} · {text(row.reason)}</p>)}</AccordionItem></Accordion>}
    {value.preferences && <Facts items={[[ '回答详略', object(value.preferences).response_detail ], ['操作节奏', object(value.preferences).operation_pace ]]} />}
  </>;
}

function ArchiveWorkspace({ data, supported, run, registerTransientBack, navigate }: DataPageProps) {
  const [selected, setSelected] = useState(['projects', 'artifacts', 'memory', 'remoteConnections', 'sshConnections', 'settings', 'skills', 'schedules']);
  const [discard, setDiscard] = useState<Data | null>(null), [configDraft, setConfigDraft] = useState<Data | null>(null);
  const [originalId, setOriginalId] = useState(''), [uploadId, setUploadId] = useState(''), [fileName, setFileName] = useState('');
  const [confirmed, setConfirmed] = useState(false);
  const journey = useDataSettingsJourney(data.selection?.selectionId ? 'snapshot' : 'list');
  const [snapshotTab, setSnapshotTab] = useState('export');
  const lastSelection = useRef(text(data.selection?.selectionId)), lastAction = useRef(text(data.originalAction));
  const selectionIntent = useRef('export');
  const selection = object(data.selection), pin = { selectionId: selection.selectionId, sha256: selection.archiveSha256 };
  const preview = object(object(data.preview).review), config = object(data.webDavConfig), review = object(data.review), uploadReview = object(data.webDavReview);
  const original = object(data.originalPhase), upload = object(data.webDavUpload), busy = Boolean(data.busy), phasePending = Boolean(data.originalAction && !['completed', 'failed'].includes(original.status));
  const nativeResult = object(object(data.result).nativeResult), nativeScope = object(review.nativeScope);
  const hasReview = Boolean(data.review || data.webDavReview), locked = busy || hasReview || Boolean(data.passwordPromptId);
  const command: Run = (action, args) => {
    if (action === 'archives.create') selectionIntent.current = 'export';
    if (action === 'archives.chooseImport' || action === 'archives.webdav.offerImport') selectionIntent.current = 'restore';
    return run(action, args);
  };
  useEffect(() => {
    if (selection.selectionId && selection.selectionId !== lastSelection.current) { setSnapshotTab(selectionIntent.current); journey.open('snapshot'); }
    lastSelection.current = text(selection.selectionId);
  }, [selection.selectionId]);
  useEffect(() => {
    if (data.originalAction && data.originalAction !== lastAction.current) journey.open('result');
    lastAction.current = text(data.originalAction);
  }, [data.originalAction]);
  const act = (action: string, args: Data, label: string, disabled = false, primary = false) => <CapabilityAction action={action} args={args} supported={supported} run={command} disabled={busy || disabled} primary={primary}>{label}</CapabilityAction>;
  const cancelReview = () => { const current = data.review ? review : uploadReview; void run(data.review ? 'archives.cancelReview' : 'archives.webdav.cancelReview', { actionId: current.actionId }).catch(() => {}); };
  useEffect(() => setConfirmed(false), [review.actionId, uploadReview.actionId]);
  useEffect(() => { const clear = () => setConfigDraft(value => value ? { ...value, password: '' } : null); const hide = () => { if (document.visibilityState !== 'visible') clear(); }; document.addEventListener('visibilitychange', hide); window.addEventListener('pagehide', clear); return () => { document.removeEventListener('visibilitychange', hide); window.removeEventListener('pagehide', clear); }; }, []);
  useEffect(() => { registerTransientBack(data.passwordPromptId ? () => { void run('archives.password.cancel', { promptId: data.passwordPromptId }).catch(() => {}); return true; } : configDraft ? () => { setConfigDraft(null); return true; } : discard ? () => { setDiscard(null); return true; } : hasReview ? () => { if (!busy) cancelReview(); return true; } : journey.view !== 'list' ? journey.back : null); return () => registerTransientBack(null); }, [data.passwordPromptId, configDraft, discard, review.actionId, uploadReview.actionId, busy, journey.view, registerTransientBack]);
  const extra = object(object(data.inventory).extraSectionsAvailable), hasInventory = Boolean(data.inventory);
  const sectionReady = (key: string) => hasInventory && (['projects', 'artifacts'].includes(key) || extra[key] === true);
  const visibleSections = data.projectMemoryName ? ['projects', 'memory'] : selected.filter(sectionReady);
  const operationNames: Record<string, [string, string]> = {
    create: ['创建备份', '选择本机数据范围，生成固定副本后再导出。'],
    import: ['导入备份', '先读取并核对内容，再逐个分区审阅恢复。'],
    snapshot: ['当前固定备份', '以下内容和操作始终对应这份原备份。'],
    webdav: ['WebDAV 备份', '每次上传独立版本，明确核对目的地。'],
    result: ['恢复结果', '读取原动作，不重放结果未知的恢复。'],
  };
  return <div className="pw-data-workspace pw-archive-workspace" ref={journey.root} tabIndex={-1} data-data-view={journey.view}>
    <div className="pw-data-collection" hidden={journey.view !== 'list'}>
      <section className="pw-data-operation-group" aria-label="备份操作"><h2>保存与恢复</h2><div className="pw-settings-links pw-data-purpose-list">
        <DataOperationRow id="create" title="创建备份" detail="选择内容，加密导出到自己的文件" icon={IconUpload} onOpen={journey.open} />
        <DataOperationRow id="import" title="导入备份" detail="先预览内容，再选择要恢复的分区" icon={IconDownload} onOpen={journey.open} />
      </div></section>
      {selection.selectionId ? <section className="pw-data-operation-group" aria-label="当前备份"><h2>当前备份</h2><div className="pw-settings-links pw-data-purpose-list">
        <DataOperationRow id="snapshot" title="继续查看这份备份" detail={bytesText(selection.archiveBytes) + ' · 本机缓存未加密'} icon={IconArchive} onOpen={journey.open} />
      </div></section> : <p className="pw-data-empty-note">尚未读取或创建备份。完成后会在这里保留继续入口。</p>}
      <section className="pw-data-operation-group pw-data-secondary" aria-label="其他备份与记录"><h2>其他备份与记录</h2><Accordion variant="divided" className="pw-data-disclosure-group"><AccordionItem id="archive-more" title="WebDAV 与恢复记录"><div className="pw-settings-links">
        <DataOperationRow id="webdav" title="加密 WebDAV 备份" detail={text(config.collection, '连接目录与独立远端版本')} icon={IconLink} onOpen={journey.open} />
        <DataOperationRow id="result" title="查看恢复记录" detail="继续核对已有恢复，不重复执行" icon={IconFileText} onOpen={journey.open} />
      </div></AccordionItem></Accordion></section>
    </div>
    {journey.view !== 'list' && <DataOperationHeading title={operationNames[journey.view][0]} parent="备份操作" description={operationNames[journey.view][1]} onBack={journey.back} />}
    {data.notice && <p className="pw-receipt-copy" role="status">{text(data.notice)}</p>}{data.error && <p className="pw-field-error" role="alert">{text(data.error)}</p>}
    {busy && <p className="pw-data-progress" role="status">正在等待原数据操作回执，请勿重复提交。</p>}
    {data.originalAction && journey.view !== 'result' && <section className="pw-data-action-notice" aria-label="原恢复状态"><p>原恢复 · {statusText(original.status || 'unknown')}</p><p className="pw-field-hint">{phasePending ? '原恢复尚未结束。先核对原动作，不重复恢复。' : '恢复结果保留在原动作中，可继续核对。'}</p><Button variant="secondary" onClick={() => journey.open('result')}>查看原恢复结果</Button></section>}
      {data.webDavAction && <section className="pw-data-action-notice" aria-label="原上传状态"><h3>原上传结果</h3><Facts items={[[ '原上传', data.webDavAction ], ['目标', object(upload.scope).destination ], ['状态', statusText(object(upload.receipt).status || upload.status) ], ['远端字节', object(upload.receipt).remoteBytesVerified ? '已核对与本次密文一致' : '尚未确认一致' ]]} /><div className="pw-form-actions">{act('archives.webdav.query', { actionId: data.webDavAction }, '只读核对原版本')}<CapabilityAction action="archives.webdav.stop" args={{ actionId: data.webDavAction }} supported={supported} run={run}>请求停止原上传</CapabilityAction></div><p className="pw-field-hint">结果未知时不会自动重传。已发送的字节不会因停止而撤回。</p></section>}
    {journey.view === 'create' && <Card className="pw-data-operation-body">
      {data.projectMemoryName ? <p>范围：{text(data.projectMemoryName)}及其记忆。</p> : <div className="pw-backup-scope-groups">{[{ title: '项目与资料', keys: ['projects', 'artifacts', 'memory'] }, { title: '设置与规则', keys: ['settings', 'skills', 'schedules'] }, { title: '连接与经历', keys: ['remoteConnections', 'sshConnections', 'experiences'] }].map(group => <section key={group.title}><h3>{group.title}</h3><div className="pw-selection-list">{group.keys.map(key => <CheckboxField key={key} isSelected={selected.includes(key)} isDisabled={locked || phasePending || !sectionReady(key)} onChange={yes => setSelected(value => yes ? [...value, key] : value.filter(item => item !== key))}>{ARCHIVE_SECTIONS[key]}{hasInventory && !sectionReady(key) ? ' · 当前不可用' : ''}</CheckboxField>)}</div></section>)}</div>}
      <p className="pw-data-caution">用户文本、图片和项目文件可能包含敏感内容。生成后的本机审阅缓存未加密，导出时默认使用口令加密。</p>
      {selected.includes('experiences') && <p className="pw-data-caution">已包含聊天、任务与工具回执正文，请确认这些私人内容确实需要随备份带走。</p>}
      <div className="pw-form-actions">{act('archives.create', { sections: visibleSections }, '生成所选范围的备份', locked || phasePending || visibleSections.length === 0 || Boolean(data.projectMemoryName && !data.projectMemoryCurrent), true)}{act('archives.refresh', {}, '刷新清单', locked)}</div>
      <Accordion><AccordionItem title="不会迁移的内容"><p>模型连接、工作配置、技能和排程可分别备份；恢复后保持停用并重新核对来源。登录凭据、密钥、SSH 信任、执行授权、设备配对、插件安装、运行环境和未保存草稿不会迁移。</p></AccordionItem></Accordion>
    </Card>}
    {journey.view === 'import' && <Card className="pw-data-operation-body">
      <div className="pw-data-step"><span>1</span><div><h3>选择备份文件</h3><p>解密和读取仅用于预览，不会恢复任何分区。</p></div></div>
      <div className="pw-form-actions">{act('archives.chooseImport', { encrypted: true }, '选择加密备份…', locked || phasePending, true)}</div>
      <div className="pw-data-step"><span>2</span><div><h3>核对内容与来源</h3><p>检查固定备份的 SHA、包含分区和明文缓存，再进入单个恢复审阅。</p></div></div>
      <p className="pw-data-caution">本机预览缓存未加密。恢复创建新身份，不恢复旧登录、执行授权或设备信任。</p>
      <Accordion><AccordionItem id="legacy-import" title="导入旧版 ZIP" description="未加密文件，仅先读取预览">{act('archives.chooseImport', { encrypted: false }, '只读核对旧版 ZIP…', locked || phasePending)}</AccordionItem></Accordion>
    </Card>}
    {journey.view === 'snapshot' && !selection.selectionId && <Empty>还没有固定备份。返回选择创建或导入。</Empty>}
    {journey.view === 'snapshot' && selection.selectionId && <Card className="pw-data-operation-body">
      <div className="pw-backup-object-summary"><strong>{bytesText(selection.archiveBytes)}</strong><span>固定备份 · 本机缓存未加密</span><p>{Object.keys(preview).map(key => ARCHIVE_SECTIONS[key] || key).join('、') || '尚未返回分区清单'}</p></div>
      <p className="pw-data-caution">这份本机缓存未加密。正文、图片与项目文件可能包含敏感内容。</p>
      <Tabs selectedKey={snapshotTab} onSelectionChange={key => setSnapshotTab(String(key))} className="pw-data-tabs">
        <TabList aria-label="固定备份操作"><Tab id="export">导出备份</Tab><Tab id="restore">恢复分区</Tab></TabList>
        <TabPanel id="export"><p>加密导出整份固定备份；口令由你独立保管。</p>
      <div className="pw-form-actions">{act('archives.offerExport', { ...pin, encrypted: true }, '设置口令并导出…', locked, true)}<Button variant="secondary" disabled={locked} onClick={() => setDiscard({ ...pin })}>移除本机缓存…</Button></div>
          <Accordion><AccordionItem id="plain-export" title="导出未加密 ZIP" description="文件中的私人内容不受备份口令保护"><p className="pw-data-caution">任何能读取文件的人都可能看到包含的内容，请先确认保存位置。</p>{act('archives.offerExport', { ...pin, encrypted: false }, '导出未加密 ZIP…', locked)}</AccordionItem></Accordion>
        </TabPanel>
        <TabPanel id="restore"><p className="pw-field-hint">一次只审阅一个分区。已存在的数据保留，身份与权限不会自动恢复。</p>
          <Accordion variant="divided" className="pw-archive-part-list">{Object.entries(preview).map(([key, value]) => { const part = object(value); return <AccordionItem id={key} key={key} title={ARCHIVE_SECTIONS[key] || key} description="查看包含内容，再审阅恢复">
            <Facts items={[[ '项目', part.projects ], ['私人副本', part.checkouts ], ['原件', part.attachments ], ['成果', part.artifacts ], ['配置', Array.isArray(part.profiles) ? part.profiles.length : undefined ], ['快照时间', timeText(part.snapshotAt) ]]} />
            <ArchiveConfigurationDetails value={part} />
            <div className="pw-form-actions">{act('archives.reviewPhase', { ...pin, phase: key }, '审阅恢复此分区', locked || phasePending)}{key === 'experiences' && act('archives.experiences', { ...pin, page: 0 }, '查看只读经历', locked)}</div>
          </AccordionItem>; })}</Accordion>
        </TabPanel>
      </Tabs>
      <Accordion variant="divided"><AccordionItem id="backup-identity" title="备份身份与内容校验" description={timeText(selection.createdAt)}><Facts items={[[ '原备份编号', selection.selectionId ], ['内容 SHA-256', selection.archiveSha256 ], ['创建时间', timeText(selection.createdAt) ]]} /></AccordionItem></Accordion>
    </Card>}
    {journey.view === 'webdav' && <Card className="pw-data-operation-body">
      <div className="pw-form-actions">{act('archives.webdav.load', {}, '读取连接设置', locked)}{data.webDavConfig && <Button variant="secondary" disabled={locked} onClick={() => setConfigDraft({ revision: config.revision, collection: text(config.collection), username: text(config.username), enabled: config.enabled === true, password: '' })}>编辑连接</Button>}</div>
      {data.webDavConfig && <><Facts items={[[ '目录', config.collection ], ['状态', config.enabled ? '已启用，逐次确认上传' : '未启用'], ['专用口令', config.credentialConfigured ? '已保存' : '未配置' ]]} /><div className="pw-form-actions">{selection.selectionId && act('archives.webdav.offerUpload', { ...pin, revision: config.revision }, '为当前备份设置口令并准备上传…', locked || !config.enabled)}{act('archives.webdav.list', { revision: config.revision }, '读取远端版本', locked || !config.enabled)}</div></>}
      {Array.isArray(data.webDavVersions) && (data.webDavVersions.length ? list(data.webDavVersions).map(version => <SourceRecord key={text(version.name)} item={version}><Facts items={[[ '加密后大小', bytesText(version.encryptedBytes) ], ['修改时间', version.modified ]]} />{act('archives.webdav.offerImport', { revision: config.revision, name: version.name }, '下载、解密并核对…', locked || phasePending || !config.enabled)}</SourceRecord>) : <Empty>这个目录尚未返回可读取的备份版本。</Empty>)}
      <Accordion><AccordionItem title="按原编号或文件名核对">
        <TextField label="当前目录中的备份文件名" value={fileName} onChange={value => setFileName(value.slice(0, 100))} /><div className="pw-form-actions">{act('archives.webdav.offerImport', { revision: config.revision, name: fileName }, '下载并只读核对…', locked || phasePending || !config.enabled || !fileName)}</div>
        <TextField label="已有上传编号" value={uploadId} onChange={value => setUploadId(value.slice(0, 36))} /><div className="pw-form-actions">{act('archives.webdav.readOriginal', { actionId: uploadId }, '读取原上传登记', !/^[a-f0-9-]{36}$/.test(uploadId))}</div>
      </AccordionItem></Accordion>
    </Card>}
    {journey.view === 'snapshot' && snapshotTab === 'restore' && data.experiencePage && <Card title="归档中的只读经历" description="这是固定时间的快照，不恢复旧授权，也不证明之后的状态。"><Facts items={[[ '快照时间', timeText(data.experiencePage.snapshotAt) ], ['页码', text(data.experiencePage.pageIndex + 1) + ' / ' + text(data.experiencePage.pageCount) ]]} /><p>{first(object(data.experiencePage.data), ['title', 'goal', 'text', 'summary'])}</p><Accordion><AccordionItem title="查看这页完整原记录"><pre className="pw-source-code">{JSON.stringify(object(data.experiencePage.data), null, 2)}</pre></AccordionItem></Accordion><div className="pw-form-actions">{act('archives.experiences', { ...pin, page: data.experiencePage.pageIndex - 1 }, '上一页', data.experiencePage.pageIndex <= 0)}{act('archives.experiences', { ...pin, page: data.experiencePage.pageIndex + 1 }, '下一页', !data.experiencePage.hasMore)}</div></Card>}
    {journey.view === 'result' && <Card className="pw-data-operation-body">
      {data.originalAction && <><Facts items={[[ '状态', statusText(original.status || 'unknown') ], ['原动作', data.originalAction ]]} /><div className="pw-form-actions">{act('archives.query', { actionId: data.originalAction }, '只读核对原恢复结果')}{['running', 'queued', 'stopping'].includes(original.status) && act('archives.stop', { actionId: data.originalAction }, '请求停止后续恢复')}</div></>}
      {Object.keys(nativeResult).length > 0 && <><ArchiveConfigurationDetails value={nativeResult} result />{['settings', 'skills', 'schedules'].includes(object(original.scope).phase) && <p>恢复写入与启用分开核对：连接和排程保持停用，技能须重新批准，工作配置须重选本机来源；当前模型选择保留。</p>}<div className="pw-form-actions">{object(original.scope).phase === 'settings' && <><Button variant="secondary" onClick={() => navigate('models')}>管理模型连接</Button><Button variant="secondary" onClick={() => navigate('workProfiles')}>核对工作配置</Button></>}{object(original.scope).phase === 'skills' && <Button variant="secondary" onClick={() => navigate('skills')}>审核恢复的技能</Button>}{object(original.scope).phase === 'schedules' && <Button variant="secondary" onClick={() => navigate('automation')}>核对恢复的排程</Button>}</div></>}
      {list(object(data.result).checkouts).filter(row => row.found).map(row => <div className="pw-form-actions" key={text(row.checkoutId)}>{act('archives.openCheckout', { actionId: data.originalAction, id: row.checkoutId }, '打开恢复项目：' + text(row.name, '项目'))}</div>)}
      {list(object(data.result).attachments).filter(row => row.id).map(row => <div className="pw-form-actions" key={text(row.id)}>{act('archives.openAttachment', { actionId: data.originalAction, id: row.id }, '查看原件：' + text(row.name, '恢复的文件'))}</div>)}
      <Accordion><AccordionItem title="按已有恢复编号读取"><TextField label="原恢复任务编号" value={originalId} onChange={value => setOriginalId(value.slice(0, 36))} /><div className="pw-form-actions">{act('archives.readOriginal', { actionId: originalId }, '读取原恢复定位', !/^[a-f0-9-]{36}$/.test(originalId))}</div></AccordionItem></Accordion>
    </Card>}
    {data.passwordPromptId && <ArchivePassword key={data.passwordPromptId} data={data} supported={supported} run={run} />}
    <SettingsDialog isOpen={hasReview} title={data.review ? '确认恢复这个分区？' : '确认上传这个新版本？'} onClose={() => { if (!busy) cancelReview(); }}>
      {data.review ? <><Facts items={[[ '分区', ARCHIVE_SECTIONS[review.phase] || review.phase ], ['原动作', review.actionId ], ['原备份编号', review.selectionId ], ['内容 SHA-256', review.archiveSha256 ]]} /><p>仅从这份固定备份创建新数据。现有项目保留；原登录、信任和执行授权不会恢复。</p><ArchiveConfigurationDetails value={nativeScope} />{['settings', 'skills', 'schedules'].includes(review.phase) && <p>连接和排程恢复为停用状态，技能需要重新批准，工作配置需要重新绑定本机来源。恢复不会切换当前模型或自动开始任务。</p>}</> : <><Facts items={[[ '原动作', uploadReview.actionId ], ['原备份编号', uploadReview.selectionId ], ['内容 SHA-256', uploadReview.archiveSha256 ], ['目标', uploadReview.destination ], ['加密后大小', bytesText(uploadReview.encryptedBytes) ], ['分区', Object.keys(object(uploadReview.sections)).map(key => ARCHIVE_SECTIONS[key] || key).join('、') ]]} /><p>仅发送这份密文到上述目录；不会自动重传或覆盖已有版本。</p></>}
      <CheckboxField isSelected={confirmed} isDisabled={busy} onChange={setConfirmed}>{data.review ? '我已核对此分区，确认恢复到这台设备' : '我已核对这份备份和上传目录'}</CheckboxField>
      <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={cancelReview}>取消</Button>{act(data.review ? 'archives.confirmPhase' : 'archives.webdav.confirm', { actionId: data.review ? review.actionId : uploadReview.actionId }, data.review ? '仅恢复这个分区' : '仅上传这个新版本', !confirmed, true)}</div>
    </SettingsDialog>
    <SettingsDialog isOpen={discard !== null} title="移除本机备份缓存？" onClose={() => setDiscard(null)}><p>只移除这份临时副本。项目、原件、固定成果和已导出文件保留；仍被原恢复依赖的缓存会拒绝删除。</p><div className="pw-form-actions"><Button variant="secondary" onClick={() => setDiscard(null)}>保留</Button><CapabilityAction action="archives.discardCache" args={discard || {}} supported={supported} run={async (action, args) => { setDiscard(null); return run(action, args); }} disabled={busy} primary>仅移除这个缓存</CapabilityAction></div></SettingsDialog>
    <SettingsDialog isOpen={configDraft !== null} title="WebDAV 备份目录" onClose={() => setConfigDraft(null)}>{configDraft && <><div className="pw-form-grid">
      <TextField label="HTTPS 目录，以 / 结尾" type="url" value={configDraft.collection} onChange={value => setConfigDraft(current => current && { ...current, collection: value.slice(0, 2048) })} />
      <TextField label="专用用户名" value={configDraft.username} onChange={value => setConfigDraft(current => current && { ...current, username: value.slice(0, 256) })} />
      <TextField label="专用连接口令" type="password" autoComplete="new-password" value={configDraft.password} onChange={value => setConfigDraft(current => current && { ...current, password: value.slice(0, 4096) })} hint="相同目录及用户名可留空保留。更换目标需重新填写；与每份备份的解密口令分开。" />
      <StretchSwitch label="启用此目录" description="仍需逐次选择备份并确认上传" checked={configDraft.enabled} onChange={value => setConfigDraft(current => current && { ...current, enabled: value })} />
      {configDraft.revision !== config.revision && <p role="alert" className="pw-field-error">连接已在另一窗口变化，请返回重新读取。</p>}
    </div><div className="pw-form-actions"><Button variant="secondary" onClick={() => setConfigDraft(null)}>取消</Button><CapabilityAction action="archives.webdav.save" args={configDraft} supported={supported} run={async (action, args) => { setConfigDraft(null); return run(action, args); }} disabled={busy || configDraft.revision !== config.revision || !configDraft.collection} primary>保存连接设置</CapabilityAction></div></>}</SettingsDialog>
  </div>;
}

function StorageWorkspace({ data, supported, run, registerTransientBack, navigate }: DataPageProps) {
  const [showProtected, setShowProtected] = useState(false), [confirmed, setConfirmed] = useState(false), [originalId, setOriginalId] = useState('');
  const [policyDraft, setPolicyDraft] = useState<Data | null>(null);
  const journey = useDataSettingsJourney();
  const lastAction = useRef(text(data.originalAction));
  const inventory = object(data.inventory), policy = object(inventory.retentionPolicy), review = object(data.review), original = object(data.original), result = object(data.result);
  const selected: string[] = Array.isArray(data.selected) ? data.selected : [], busy = Boolean(data.busy), locked = busy || Boolean(data.review);
  const rows = list(inventory.groups).filter(row => showProtected || row.cleanable && object(row.usage).logicalBytes !== 0 || object(row.usage).complete !== true);
  const cacheRows = list(data.caches), cachesKnown = Array.isArray(data.caches) && !data.backupError;
  const cacheSummary = data.backupError ? '读取失败，数量未知' : !cachesKnown ? '尚未读取本机备份缓存' : cacheRows.length ? `${cacheRows.length} 份 · 先核对内容` : '尚无可读取的本机缓存';
  const act = (action: string, args: Data, label: string, disabled = false, primary = false) => <CapabilityAction action={action} args={args} supported={supported} run={run} disabled={busy || disabled} primary={primary}>{label}</CapabilityAction>;
  const cancel = () => { void run('storage.cancelReview', { actionId: review.actionId }).catch(() => {}); };
  useEffect(() => setConfirmed(false), [review.actionId]);
  useEffect(() => { if (data.originalAction && data.originalAction !== lastAction.current) journey.open('result'); lastAction.current = text(data.originalAction); }, [data.originalAction]);
  useEffect(() => { registerTransientBack(policyDraft ? () => { setPolicyDraft(null); return true; } : data.review ? () => { if (!busy) cancel(); return true; } : journey.view !== 'list' ? journey.back : null); return () => registerTransientBack(null); }, [policyDraft, review.actionId, busy, journey.view, registerTransientBack]);
  const operationNames: Record<string, [string, string]> = {
    cleanup: ['选择清理范围', '实际计量、逐项选择，再核对本次清理影响。'],
    caches: ['本机备份缓存', '先核对或导出原备份，再决定是否移除缓存。'],
    diagnostics: ['导出诊断快照', '只读预览固定内容，核对后保存到自己选择的位置。'],
    result: ['原清理结果', '只读核对原动作，结果未知时不重复删除。'],
  };
  return <div className="pw-data-workspace pw-storage-workspace" ref={journey.root} tabIndex={-1} data-data-view={journey.view}>
    <div className="pw-data-collection" hidden={journey.view !== 'list'}>
      <section className="pw-storage-overview" aria-label="空间与清理">
        <div className="pw-storage-capacity"><span>系统可用空间</span><strong>{bytesText(inventory.filesystemUsableBytes)}</strong><small>可用空间不等于可清理容量。</small></div>
        <p>{data.inventory ? '查看各目录占用，选择后再核对删除范围。' : '目录占用尚未读取，可进入后查看。'}</p>
        <Button className="pw-storage-primary" trailingIcon={IconChevronRight} data-operation-entry="cleanup" onClick={() => journey.open('cleanup')}>查看占用与清理</Button>
      </section>
      <section className="pw-data-operation-group pw-data-secondary" aria-label="缓存与记录"><h2>缓存与记录</h2><div className="pw-settings-links pw-data-purpose-list">
        <DataOperationRow id="caches" title="本机备份缓存" detail={cacheSummary} icon={IconArchive} onOpen={journey.open} />
        <DataOperationRow id="diagnostics" title="导出诊断快照" detail="先预览，再保存；不会自动上传" icon={IconFileText} onOpen={journey.open} />
        <Accordion variant="divided" className="pw-data-records"><AccordionItem id="storage-records" title="原清理记录"><DataOperationRow id="result" title="核对原清理结果" detail="结果未知时不重复删除" onOpen={journey.open} /></AccordionItem></Accordion>
      </div></section>
    </div>
    {journey.view !== 'list' && <DataOperationHeading title={operationNames[journey.view][0]} parent="存储操作" description={operationNames[journey.view][1]} onBack={journey.back} />}
    {data.notice && <p className="pw-receipt-copy" role="status">{text(data.notice)}</p>}{data.error && <p className="pw-field-error" role="alert">{text(data.error)}</p>}
    {busy && <p className="pw-data-progress" role="status">正在等待原数据操作回执，请勿重复提交。</p>}
    {data.backupError && <p className="pw-field-error" role="alert">{text(data.backupError)}</p>}
    {data.originalAction && journey.view !== 'result' && <section className="pw-data-action-notice" aria-label="原清理状态"><p>原清理 · {statusText(original.status || 'unknown')}</p><p className="pw-field-hint">已删除的缓存不能回滚。结果不明时先核对原动作，不重复清理。</p><Button variant="secondary" onClick={() => journey.open('result')}>查看原清理结果</Button></section>}
    {journey.view === 'cleanup' && <Card className="pw-data-operation-body">
      <Facts items={[[ '当前文件系统可用', bytesText(inventory.filesystemUsableBytes) ]]} />
      <div className="pw-form-actions">{act('storage.refresh', {}, '重新计量', locked)}{act('storage.suggest', {}, '按保留规则建议勾选', locked)}{data.inventory && <Button variant="secondary" disabled={locked} onClick={() => setPolicyDraft({ revision: policy.revision, generations: policy.keepOldRuntimeGenerations, backups: policy.keepRecentBackupCopies, generated: policy.suggestGeneratedCleanup === true })}>编辑保留建议</Button>}</div>
      <CheckboxField isSelected={showProtected} onChange={setShowProtected}>显示受保护或空的范围</CheckboxField>
      {!data.inventory ? <Empty>尚未读取实际目录占用。</Empty> : <div className="pw-storage-range-list">{rows.map(row => {
        const usage = object(row.usage), cleanable = row.cleanable && usage.complete === true;
        return <div key={text(row.id)} className="pw-storage-range-row" data-storage-range={text(row.id)}>
          <div className="pw-storage-range-main"><CheckboxField isSelected={selected.includes(row.id)} isDisabled={!cleanable || locked} onChange={yes => { void run('storage.select', { id: row.id, selected: yes }).catch(() => {}); }}>{text(row.label || row.id)}</CheckboxField><strong>{usage.complete === true ? bytesText(usage.logicalBytes) : '未完整读取'}</strong></div>
          <p className="pw-storage-range-meta">{usage.complete === true ? `${text(usage.files, '未提供')} 个文件 · ${cleanable ? '可供审阅' : '受保护，保留'}` : '不能据此清理，请重新计量'}{row.note ? ' · ' + text(row.note) : ''}</p>
        </div>;
      })}</div>}
      <p className="pw-data-caution">清理会删除缓存或未固定输出，已删除内容不能回滚。分组可能重叠，不将逻辑大小相加为可回收空间。</p>
      <p className="pw-field-hint">已选 {selected.length} 个范围，下一步仅审阅影响。</p>
      <div className="pw-form-actions">{act('storage.reviewSelected', { selection: selected }, '核对清理影响', locked || selected.length === 0, true)}</div>
      <Accordion><AccordionItem title="计量和保留规则说明"><p>分组可能重叠，不能把逻辑大小直接相加为可回收空间。清理会重新核对原运行和固定目录；当前运行环境、原项目、用户 HOME 和固定成果保留。</p><p>规则只生成建议，不会定时或自动删除。中断后的原动作只能核对，已删除缓存不能回滚。</p></AccordionItem></Accordion>
    </Card>}
    {journey.view === 'caches' && <Card className="pw-data-operation-body">{!data.backupError && cacheRows.length === 0 && <Empty>{cachesKnown ? '尚无可读取的本机备份缓存。' : '尚未读取本机备份缓存，请重新计量。'}</Empty>}{cachesKnown && cacheRows.map(row => <div key={text(row.selectionId)} className="pw-storage-cache-row"><Facts items={[[ '原备份编号', row.selectionId ], ['大小', bytesText(row.archiveBytes)]]} /><CapabilityAction action="storage.backupCache" args={{ selectionId: row.selectionId }} supported={supported} run={async (action, args) => { const result = await run(action, args); if (result?.navigate === 'archives') navigate('archives'); return result; }} disabled={locked}>核对备份 · {bytesText(row.archiveBytes)}</CapabilityAction></div>)}</Card>}
    {journey.view === 'result' && <Card className="pw-data-operation-body">
      {data.originalAction && <><Facts items={[[ '原动作', data.originalAction ], ['状态', statusText(original.status || 'unknown') ], ['结果', result.message || result.notice ]]} /><div className="pw-form-actions">{act('storage.query', { actionId: data.originalAction }, '只读核对原结果')}{['running', 'queued', 'stopping'].includes(original.status) && act('storage.stop', { actionId: data.originalAction }, '请求停止后续删除')}</div></>}
      <Accordion><AccordionItem title="按原编号恢复查看"><TextField label="原清理任务编号" value={originalId} onChange={value => setOriginalId(value.slice(0, 36))} /><div className="pw-form-actions">{act('storage.readOriginal', { actionId: originalId }, '读取原清理记录', !/^[a-f0-9-]{36}$/.test(originalId))}</div></AccordionItem></Accordion>
    </Card>}
    {journey.view === 'diagnostics' && <Card className="pw-data-operation-body">
      <div className="pw-form-actions">{act('storage.diagnostics.read', {}, '读取只读诊断快照', locked || data.diagnosticPickerPending)}</div>
      {data.diagnosticText && <><pre className="pw-source-code">{text(data.diagnosticText)}</pre><Facts items={[[ '大小', bytesText(data.diagnosticBytes) ], ['内容 SHA-256', data.diagnosticSha ]]} /><CheckboxField isSelected={Boolean(data.diagnosticReviewed)} isDisabled={busy || data.diagnosticPickerPending || data.diagnosticExportEntered} onChange={accepted => { void run('storage.diagnostics.review', { sha256: data.diagnosticSha, accepted }).catch(() => {}); }}>我已核对这份内容，允许写入自己选择的文档</CheckboxField><div className="pw-form-actions">{act('storage.diagnostics.export', { sha256: data.diagnosticSha }, '选择位置并导出…', !data.diagnosticReviewed || data.diagnosticPickerPending || data.diagnosticExportEntered)}</div></>}
    </Card>}
    <SettingsDialog isOpen={Boolean(data.review)} title="仅清理这些固定范围？" onClose={() => { if (!busy) cancel(); }}><Facts items={[['原动作', review.actionId]]} /><p>依赖可能需要重新安装，未保存的生成内容会丢失。原项目和固定成果保留；已经删除的缓存不会回滚。</p>{list(review.targets).map(target => <SourceRecord key={text(target.id)} item={{ title: list(inventory.groups).find(row => row.id === target.id)?.label || target.id }}><Facts items={[[ '逻辑大小', bytesText(target.logicalBytes) ]]} /></SourceRecord>)}<CheckboxField isSelected={confirmed} isDisabled={busy} onChange={setConfirmed}>我已核对所选范围，接受这些缓存或未固定输出被移除</CheckboxField><div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={cancel}>保留</Button>{act('storage.confirm', { actionId: review.actionId }, '仅清理这些范围', !confirmed, true)}</div></SettingsDialog>
    <SettingsDialog isOpen={policyDraft !== null} title="保留建议" onClose={() => setPolicyDraft(null)}>{policyDraft && <><div className="pw-form-grid"><SelectField label="建议保留旧环境" value={text(policyDraft.generations)} options={[0, 1, 2, 3, 4, 5].map(n => ({ id: String(n), label: n + ' 版' }))} onChange={value => setPolicyDraft(current => current && { ...current, generations: Number(value) })} /><SelectField label="建议保留本机备份" value={text(policyDraft.backups)} options={[0, 1, 2, 3, 4, 5].map(n => ({ id: String(n), label: n + ' 份' }))} onChange={value => setPolicyDraft(current => current && { ...current, backups: Number(value) })} /><CheckboxField isSelected={policyDraft.generated} onChange={value => setPolicyDraft(current => current && { ...current, generated: value })}>建议清理未固定的生成目录</CheckboxField><p>仅保存建议，不启用自动删除。</p>{policyDraft.revision !== policy.revision && <p role="alert" className="pw-field-error">原保留规则已变化，请重新打开。</p>}</div><div className="pw-form-actions"><Button variant="secondary" onClick={() => setPolicyDraft(null)}>取消</Button><CapabilityAction action="storage.savePolicy" args={policyDraft} supported={supported} run={async (action, args) => { setPolicyDraft(null); return run(action, args); }} disabled={busy || policyDraft.revision !== policy.revision} primary>保存建议规则</CapabilityAction></div></>}</SettingsDialog>
  </div>;
}

function DataManagement({ kind, ...props }: DataPageProps & { kind: 'archives' | 'storage' }) {
  const { data, supported, run } = props;
  const current = object(kind === 'archives' ? data.originalPhase : data.original), actionId = data.originalAction;
  useEffect(() => {
    if (!data.open || data.busy || !actionId || !['queued', 'running'].includes(current.status) || !supported(kind + '.poll')) return;
    const timer = window.setTimeout(() => { void run(kind + '.poll', { actionId }).catch(() => {}); }, 1200);
    return () => window.clearTimeout(timer);
  }, [kind, data.open, data.busy, actionId, current.status, supported, run]);
  return kind === 'archives' ? <ArchiveWorkspace {...props} /> : <StorageWorkspace {...props} />;
}

const SETTINGS_GROUPS = [
  { id: 'models', title: 'AI 接入', entries: ['model-settings', 'proxy'] },
  { id: 'preferences', title: '使用偏好', entries: ['setup', 'phone:appearance', 'phone:speech'] },
  { id: 'privacy', title: '权限与运行', entries: ['phone:execution', 'phone:observation', 'background'] },
  { id: 'data', title: '数据与应用', entries: ['usage', 'storage', 'archives', 'updates'] },
];
const COMMON_SETTINGS = [
  { id: 'setup', title: '准备中心', detail: '模型、手机权限、开发工具与首次使用', icon: IconCheck },
  { id: 'model-settings', title: 'AI 接入', detail: '服务、账号、模型与调度', icon: IconKey },
  { id: 'proxy', title: '本机 API', detail: '访问地址、客户端连接与近期调用', icon: IconServer },
  { id: 'accounts', title: 'ChatGPT 账号', detail: '登录、授权与套餐用量', icon: IconAccount },
  { id: 'phone:appearance', title: '外观与字体', detail: '主题、中文、英数与代码字体', icon: IconPalette },
  { id: 'phone:speech', title: '语音与朗读', detail: '系统听写、声音与任务播报', icon: IconMicrophone },
  { id: 'phone:execution', title: '系统权限', detail: '界面读取、任务通知与无线调试', icon: IconShieldCheck },
  { id: 'phone:observation', title: '观察与隐私', detail: '本地取图、OCR 与截图留存', icon: IconPrivacy },
  { id: 'background', title: '后台运行', detail: '助手连接、独立屏幕与音频', icon: IconPhone },
  { id: 'usage', title: '用量统计', detail: '每日 Token、缓存与调用明细', icon: IconFileText },
  { id: 'archives', title: '备份与迁移', detail: '导出或恢复原数据', icon: IconArchive },
  { id: 'storage', title: '项目存储', detail: '占用与清理审阅', icon: IconStorage },
  { id: 'updates', title: '应用更新', detail: '检查新版本与管理下载', icon: IconRefresh },
];

/** Summaries describe observed host state; search keeps the original keywords. */
function settingsEntryDetail(id: string, state: Data): string {
  const flag = (value: unknown) => value === true ? '已开' : value === false ? '未开' : '待核实';
  if (id === 'model-settings') {
    if (typeof state.modelName !== 'string') return '默认模型尚未读取';
    return state.modelName.trim() ? `默认模型 · ${state.modelName}` : '尚未选择默认模型';
  }
  if (id === 'proxy') {
    const proxy = { ...object(object(state.features).proxy), ...object(object(state.ownerSnapshots).proxy) };
    const unresolved = Object.entries(object(state.moduleErrors)).some(([action, value]) => action.startsWith('proxy.') && value) || object(state.ownerErrors).proxy;
    if (unresolved) return '服务状态待核对';
    if (Array.isArray(state.moduleBusy) && state.moduleBusy.some((action: unknown) => typeof action === 'string' && action.startsWith('proxy.'))) return '服务操作处理中';
    return proxy.running === true ? '正在运行 · 仅本机访问' : proxy.running === false ? '尚未运行' : '尚未读取服务状态';
  }
  if (id === 'accounts') {
    const accounts = object(object(state.ownerSnapshots).accounts).accounts;
    return Array.isArray(accounts) ? accounts.length ? `已保存 ${accounts.length} 个账号` : '尚未添加账号' : '账号状态尚未读取';
  }
  if (id === 'phone:appearance') return ({ system: '跟随系统', light: '浅色', dark: '深色' } as Data)[text(object(state.appearance).mode)] || '尚未读取当前外观';
  if (id === 'phone:speech') {
    if (state.speaking === true) return '正在朗读';
    if (object(object(state.taskAnnouncements).readout).active === true) return '正在播报任务状态';
    const speech = ({ ready: '中文朗读已就绪', initializing: '正在准备中文朗读', language_data_missing: '需安装中文语音资源', language_unsupported: '系统引擎不支持中文朗读', unavailable: '朗读引擎暂不可用', closed: '朗读窗口已关闭' } as Data)[text(object(state.systemSpeech).ttsStatus)];
    return speech || (state.speechAvailable === true ? '系统听写可用' : state.speechAvailable === false ? '系统听写暂不可用' : '语音状态尚未读取');
  }
  if (id === 'phone:execution') return `界面读取${flag(state.accessibilityEnabled)} · 通知${flag(state.notificationsEnabled)}`;
  if (id === 'phone:observation') return `取图${flag(state.localCaptureAllowed)} · OCR ${flag(state.localOcrAllowed)}`;
  if (id === 'background') {
    const display = object(object(state.features).display), connection = backgroundConnection(display, state.activeTaskId, Array.isArray(state.moduleBusy) ? state.moduleBusy : []);
    if (connection.requiresConnectionCheck) return '后台连接需要核对';
    if (connection.busy) return '正在处理后台连接';
    return display.helperConnected === true ? '助手已连接' : display.helperConnected === false ? '助手未连接' : '后台连接状态尚未读取';
  }
  return '';
}

export default function Secondary({ teamAttentionFocus, onTeamAttentionConsumed, registerBeforeLeave, route, onBack, snapshot, registerBackHandler, navigationTrigger, navigationRevision, onNavigationPageChange, onOpenSetup, onReturnToSetup, onOpenProjects, originGoal, returnLabel, initialProjectId, localDraft, onLocalDraftChange }: Props) {
  const phonePresentation = useRef('list');
  const state: Data = { ...object(snapshot.state), webWorkspaceResumeRevision: Number(snapshot.view?.resumeRevision || 0), webWorkspaceTaskOpen: !!snapshot.view?.taskId, webWorkspaceSection: snapshot.view?.section };
  const commands = snapshot.capabilities?.commands || [];
  const supported = (action: string) => commands.includes(action);
  const normalize = normalizeWorkspaceRoute;
  const [entry, setEntry] = useState({ route, revision: navigationRevision });
  const [storedPage, setPage] = useState(normalize(route));
  const entryChanged = entry.route !== route || entry.revision !== navigationRevision;
  // A new drawer/deep-link entry must not briefly mount the previous owner's
  // child while the effect below clears its local presentation trail.
  const page = entryChanged ? normalize(route) : storedPage;
  // A retiring child's cleanup still holds its original run callback. Do not let
  // that callback borrow the next page's generation and publish an error there.
  const requestOwner = useRef({ route, revision: navigationRevision, page });
  if (requestOwner.current.route !== route || requestOwner.current.revision !== navigationRevision || requestOwner.current.page !== page) {
    requestOwner.current = { route, revision: navigationRevision, page };
  }
  const renderedOwner = requestOwner.current;
  const [candidateTarget, setCandidateTarget] = useState<LearningCandidateTarget | null>(null);
  const [modelView, setModelView] = useState<ModelView>({ kind: 'list' });
  const [modelHostSection, setModelHostSection] = useState<'models' | 'accounts' | 'proxy'>(normalize(route) === 'proxy' ? 'proxy' : 'models');
  const modelTrail = useRef<ModelView[]>([]);
  const modelDrafts = useRef<Partial<Record<string, ModelDraft>>>({});
  const transientBack = useRef<(() => boolean) | null>(null);
  const registerTransientBack = useCallback<BackHandlerRegistration>(handler => { transientBack.current = handler; }, []);
  const [query, setQuery] = useState(''), [notice, setNotice] = useState(''), [error, setError] = useState('');
  const trail = useRef<Array<{ page: string; query: string; modelView: ModelView; modelTrail: ModelView[]; candidateTarget: LearningCandidateTarget | null }>>([]), scroll = useRef<HTMLDivElement>(null), scrollPositions = useRef(new Map<string, number>());
  const mounted = useRef(true), generation = useRef(0);
  useLayoutEffect(() => { generation.current++; trail.current = []; modelTrail.current = []; setEntry({ route, revision: navigationRevision }); setPage(normalize(route)); setCandidateTarget(null); setModelView({ kind: 'list' }); setModelHostSection(normalize(route) === 'proxy' ? 'proxy' : 'models'); setNotice(''); setError(''); }, [route, navigationRevision]);
  useEffect(() => { onNavigationPageChange?.(page); }, [page, onNavigationPageChange]);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; generation.current++; }; }, []);
  const run: Run = async (action, args = {}) => {
    if (!supported(action)) throw new Error('当前页面尚未开放此操作。');
    const current = generation.current;
    const teamWindow = action.startsWith('teams.') || action.startsWith('teamEvidence.') ? {
      documentEpoch: bridge.getDocumentEpoch(), resumeRevision: Number(object(bridge.getState().view).resumeRevision || 0),
    } : null;
    const ownsPresentation = () => {
      if (!mounted.current || requestOwner.current !== renderedOwner || generation.current !== current) return false;
      if (!teamWindow) return true;
      const view = object(bridge.getState().view);
      return view.section === 'teams' && !view.taskId && teamWindow.documentEpoch === bridge.getDocumentEpoch()
        && teamWindow.resumeRevision === Number(view.resumeRevision || 0);
    };
    if (ownsPresentation()) setError('');
    try {
      const value = await bridge.command(action, args);
      if (ownsPresentation() && object(value).accepted === true) setNotice('已提交，结果尚未确认。');
      return value;
    } catch (failure) {
      if (ownsPresentation()) setError(failure instanceof Error ? failure.message : String(failure));
      throw failure;
    }
  };
  const rememberScroll = () => scrollPositions.current.set(page + ((page === 'models' || page === 'model-settings') ? ':' + modelView.kind + ':' + (modelView.id || '') : ''), scroll.current?.scrollTop || 0);
  const navigate = (next: string, candidate: LearningCandidateTarget | null = null) => { rememberScroll(); generation.current++; trail.current.push({ page, query, modelView, modelTrail: [...modelTrail.current], candidateTarget }); modelTrail.current = []; setNotice(''); setError(''); setQuery(''); setModelView({ kind: 'list' }); setModelHostSection(next === 'proxy' ? 'proxy' : 'models'); setCandidateTarget(candidate); setPage(next); };
  const switchWorkspacePage = (next: string) => {
    if (next === workspacePageForRoute(page)) return;
    rememberScroll(); generation.current++;
    const family = workspaceForRoute(page)?.key;
    // Peer pages do not build a false hierarchy or return through Settings.
    // Keep the real entry parent and each page's own scroll position.
    trail.current = trail.current.filter(item => workspaceForRoute(item.page)?.key !== family);
    modelTrail.current = []; setNotice(''); setError(''); setQuery('');
    setModelView({ kind: 'list' }); setCandidateTarget(null); setPage(next);
  };
  const returnToPhone = () => {
    rememberScroll(); generation.current++;
    // Returning from setup consumes its original phone entry, rather than
    // pushing background again and reopening it on the next Back.
    let index = trail.current.length - 1;
    while (index >= 0 && trail.current[index].page !== 'phone' && !trail.current[index].page.startsWith('phone:')) index--;
    const previous = index >= 0 ? trail.current.splice(index)[0] : trail.current.pop();
    modelTrail.current = previous?.modelTrail || [];
    setNotice(''); setError(''); setQuery(previous?.query || '');
    setModelView(previous?.modelView || { kind: 'list' });
    setCandidateTarget(previous?.candidateTarget || null); setPage(previous?.page || 'phone');
  };
  const openModel = (view: ModelView) => { rememberScroll(); generation.current++; modelTrail.current.push(modelView); setNotice(''); setError(''); setModelView(view); };
  const finishModelEdit = (view: ModelView) => {
    if (modelTrail.current.at(-1)?.kind === view.kind && modelTrail.current.at(-1)?.id === view.id) modelTrail.current.pop();
    generation.current++; setError(''); setModelView(view);
  };
  const isModelPage = page === 'models' || page === 'model-settings';
  const isAIPage = isModelPage || page === 'proxy';
  const isPhonePage = page === 'phone' || page.startsWith('phone:');
  const phoneView = page.startsWith('phone:') ? page.slice('phone:'.length) : phonePresentation.current;
  const workspace = workspaceForRoute(page);
  const domain = SECONDARY_DOMAINS.find(x => x.id === page);
  const hostSection = page === 'proxy' ? 'proxy' : isModelPage ? entryChanged ? 'models' : modelHostSection : domain?.id;
  const canEnter = supported('connections.enter'), canLeave = supported('connections.leave');
  useLayoutEffect(() => {
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
  const dataPage = isAIPage ? 'models' : page;
  const feature = object(object(state.features)[dataPage === 'commitments' ? 'automation' : dataPage]);
  const owner = object(object(state.ownerSnapshots)[dataPage]);
  const specialized = isAIPage ? object(state.modelConnections) : page === 'devices' ? object(state.deviceWorkspace) : {};
  // MemoryModule.snapshot is summary-only. Its actual list reply lives in
  // moduleResults; an in-flight or failed reread must not present an old list.
  const memoryListReady = !object(state.moduleErrors)['memory.list'] && !(Array.isArray(state.moduleBusy) && state.moduleBusy.includes('memory.list'));
  const memoryList = memoryListReady ? object(object(state.moduleResults)['memory.list']) : {};
  const data: Data = { ...feature, ...owner, ...specialized, ...(page === 'memory' ? { records: memoryList.records } : {}) };
  const errorPage = isAIPage ? hostSection || 'models' : dataPage;
  const domainError = text(object(state.ownerErrors)[errorPage]) || Object.entries(object(state.moduleErrors)).filter(([key]) => key.startsWith(errorPage + '.')).map(([, value]) => text(value)).filter(Boolean).join('\n');
  const settingsGroup = SETTINGS_GROUPS.find(group => page === 'group:' + group.id);
  const connection = modelConnectionRows(data).find(row => recordId(row) === modelView.id);
  const editConnection = modelView.id ? connection : modelConnectionRows(data).find(row => recordId(row) === text(object(data.connections).selectedConnectionId));
  const modelDraftKey = modelView.kind + ':' + (modelView.kind === 'add' ? 'new' : recordId(editConnection || {}));
  const phoneTitle = ({ appearance: '外观与字体', speech: '语音与朗读', execution: '系统权限', observation: '观察与隐私', browser: '浏览器' } as Data)[phoneView];
  const pageTitle = page === 'capabilities' ? '能力与工具' : workspace?.key === 'tools' && domain ? domain.title : isAIPage ? 'AI 接入'
    : workspace?.title || settingsGroup?.title || (page === 'settings' || page === 'connections' ? '设置' : isPhonePage ? phoneTitle || '手机能力' : page === 'background' ? '后台运行' : page === 'history' ? '任务历史' : page === 'usage' ? '用量统计' : page === 'archives' ? '备份与迁移' : page === 'storage' ? '项目存储' : domain?.title || 'PocketWorld');
  const settingsEntries = COMMON_SETTINGS.filter(item => item.id !== 'setup' || !!onOpenSetup).map(item => ({ ...item, searchText: item.detail, detail: settingsEntryDetail(item.id, state) }));
  const filtered = settingsEntries.filter(item => (item.title + item.detail + item.searchText).toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()));
  const viewKey = page + (isModelPage ? ':' + modelView.kind + ':' + (modelView.id || '') : '');
  useLayoutEffect(() => {
    const element = scroll.current;
    if (element) element.scrollTop = scrollPositions.current.get(viewKey) || 0;
  }, [viewKey]);
  useSurfaceEnter(scroll, viewKey, {selector: ':scope > .pw-secondary-page', depth: trail.current.length + modelTrail.current.length, active: page !== 'devices'});
  const modelBrowseActive = ['list', 'catalog', 'model'].includes(modelView.kind);
  const modelParent = modelTrail.current.at(-1)?.kind;
  const modelBackLabel = modelParent === 'connections' ? '返回连接管理' : modelParent === 'connection' ? '返回连接详情' : modelParent === 'catalog' ? '返回模型目录' : '返回模型与连接';
  const consumeBack = () => {
    if (transientBack.current?.()) return true;
    rememberScroll();
    if (isModelPage && modelView.kind !== 'list') { generation.current++; setNotice(''); setError(''); setModelView(modelTrail.current.pop() || { kind: 'list' }); return true; }
    const previous = trail.current.pop();
    if (previous) { generation.current++; setNotice(''); setError(''); setModelHostSection(previous.page === 'proxy' ? 'proxy' : 'models'); setPage(previous.page); setCandidateTarget(previous.candidateTarget); setQuery(previous.query); modelTrail.current = previous.modelTrail; setModelView(previous.modelView); return true; }
    if (page === normalize(route)) return false;
    if (workspace && workspaceForRoute(normalize(route))?.key === workspace.key) return false;
    if (page === 'settings' || page === 'connections') return false;
    generation.current++; setCandidateTarget(null); setPage(page === 'background' ? 'phone' : workspace?.route || 'settings'); setModelView({ kind: 'list' }); return true;
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
  const settingRows = (entries: Array<{ id: string; title: string; detail: string; icon: typeof IconKey }>) => <div className="pw-settings-links">{entries.map(item => <Button key={item.id} variant="ghost" leadingIcon={item.icon} trailingIcon={IconChevronRight} className="pw-domain-link h-auto whitespace-normal" disabled={item.id === 'updates' && !supported('settings.openAppUpdates')} onClick={() => { if (item.id === 'updates') { void run('settings.openAppUpdates', {}).catch(() => {}); } else if (item.id === 'setup') onOpenSetup?.(); else navigate(item.id); }}><span className="pw-settings-row-copy" data-inline-status={!!item.detail && item.detail.length <= 12}><span>{item.title}</span>{item.detail && <small>{item.detail}</small>}</span></Button>)}</div>;
  const refreshAction = page === 'background' ? 'background.refresh' : isModelPage ? 'models.snapshot' : page === 'memory' ? 'memory.list' : 'app.refresh';
  const content = <div className="pw-secondary-scroll" ref={scroll}>
      {notice && <p className="pw-request-notice" role="status">{notice}</p>}
      {(error || domainError) && <p className="pw-field-error" role="alert">{error || domainError}</p>}
      <div className="pw-secondary-page" data-secondary-view={viewKey}>
        {page === 'capabilities' ? <CapabilitiesWorkspace state={state} onNavigate={navigate} onPrepare={onOpenSetup} run={run} supported={supported} registerBack={registerTransientBack} registerBeforeLeave={registerBeforeLeave} /> : (page === 'settings' || page === 'connections') ? <div className="pw-settings-home pw-settings-preferences">
          <SearchField className="pw-secondary-search" data-input-size="medium" aria-label="搜索设置" placeholder="搜索设置" value={query} onChange={setQuery} shape="rounded" />
          {query.trim() ? <section className="pw-settings-results" aria-label="匹配的设置">{settingRows(filtered)}{filtered.length === 0 && <Empty>没有匹配的设置。</Empty>}</section>
            : <div className="pw-settings-categories">{SETTINGS_GROUPS.map(group => <section key={group.id} className="pw-settings-category" data-settings-group={group.id} aria-label={group.title}><h2>{group.title}</h2><div className="pw-settings-category-content">{settingRows(group.entries.map(id => settingsEntries.find(item => item.id === id)!).filter(Boolean))}{group.id === 'privacy' && <div className="pw-settings-execution" aria-label="执行控制"><ExecutionControls state={state} supported={supported} run={run}/></div>}</div></section>)}</div>}
        </div> : settingsGroup ? <section className="pw-settings-group-page">{settingRows(settingsGroup.entries.map(id => settingsEntries.find(item => item.id === id)!).filter(Boolean))}</section>
          : isAIPage ? <AICenter key={page + ':' + (navigationRevision || 0)} state={state} data={data} supported={supported} run={run} registerTransientBack={registerTransientBack} initialView={page === 'proxy' ? 'proxy' : 'main'} onHostSectionChange={setModelHostSection}
              renderEditor={(row, preset, close, saved, registerBack) => {
                const draftKey = 'ai-service:' + (row?.id || preset?.name || 'new');
                return <ModelSettings key={draftKey} state={state} connection={row} preset={preset} supported={supported} run={run} adding={!row} draft={modelDrafts.current[draftKey]} onDraftChange={value => { if (value) modelDrafts.current[draftKey] = value; else delete modelDrafts.current[draftKey]; }} onInspect={close} onSaved={saved} registerTransientBack={registerBack} />;
              }}
              renderAccounts={(accountId, adding, registerBack) => <AccountsWorkspace key={accountId || 'new-account'} state={state} data={{ ...object(object(state.features).accounts), ...object(object(state.ownerSnapshots).accounts) }} supported={supported} run={run} registerTransientBack={registerBack} onlyAccountId={accountId} startAdding={adding} />}
              renderTools={registerBack => <UsageWorkspace supported={supported} registerTransientBack={registerBack} records={<HistoryPage usage supported={supported} run={run}/>} renderBudget={(onEditingChange, registerBudgetBack) => <TokenBudgetsWorkspace supported={supported} registerTransientBack={registerBudgetBack} onEditingChange={onEditingChange}/>} diagnostics={<ModelDiagnosticsWorkspace state={state} supported={supported}/>}/>}
              renderProxy={registerBack => <ProxyWorkspace state={state} run={run} registerTransientBack={registerBack} />} />
          : page === 'phone' ? <PhoneSettings state={state} supported={supported} run={run} navigate={navigate} registerTransientBack={registerTransientBack} initialView={phonePresentation.current} onViewChange={view => { phonePresentation.current = view; }} />
          : isPhonePage ? <PhoneSettings key={page} state={state} supported={supported} run={run} navigate={navigate} registerTransientBack={registerTransientBack} initialView={phoneView} direct />
          : page === 'background' ? <BackgroundSettings state={state} supported={supported} run={run} onReturn={onReturnToSetup || returnToPhone} returnLabel={onReturnToSetup ? '准备中心' : '手机能力'} registerTransientBack={registerTransientBack} />
          : page === 'usage' ? <UsageWorkspace supported={supported} registerTransientBack={registerTransientBack} records={<HistoryPage usage supported={supported} run={run}/>} renderBudget={(onEditingChange, registerBudgetBack) => <TokenBudgetsWorkspace supported={supported} registerTransientBack={registerBudgetBack} onEditingChange={onEditingChange}/>}/>
          : page === 'history' ? <HistoryPage key={page} usage={false} supported={supported} run={run} />
          : page === 'archives' || page === 'storage' ? <DataManagement key={page} kind={page} data={object(page === 'archives' ? state.ownerArchives : state.projectStorage)} supported={supported} run={run} registerTransientBack={registerTransientBack} navigate={navigate} />
          : domain ? <DomainPage onTeamAttentionConsumed={onTeamAttentionConsumed} teamAttentionFocus={teamAttentionFocus} key={page === 'local' ? page + ':' + (navigationRevision || 0) : page} domain={page} state={state} data={data} supported={supported} run={run} registerTransientBack={registerTransientBack} candidateTarget={candidateTarget} onReviewCandidate={target => navigate('memoryReview', target)} onOpenModels={() => navigate('model-settings')} onNavigate={navigate} onPrepareTools={onOpenSetup?()=>onOpenSetup('tools'):undefined} onOpenProjects={onOpenProjects} initialProjectId={initialProjectId} localDraft={localDraft} onLocalDraftChange={onLocalDraftChange} alreadyShownError={error || domainError} />
          : <Empty>此页面暂不可用，请从导航重新打开。</Empty>}
      </div>
    </div>;
  return <section className="pw-secondary pw-secondary-refactor" data-secondary-page={page} data-workspace={workspace?.key} aria-label={pageTitle}>
    <header className="pw-secondary-header"><IconButton icon={IconArrowLeft} aria-label={isModelPage && hostSection === 'proxy' ? '返回 AI 接入' : isModelPage && modelView.kind !== 'list' ? modelBackLabel : '返回'} title={returnLabel} onClick={back} /><div><h1>{pageTitle}</h1>{originGoal && <p className="pw-workspace-origin" title={originGoal}>当前工作：{originGoal}</p>}</div>{!workspace && !['settings', 'connections', 'history', 'usage'].includes(page) && !(isAIPage && hostSection !== 'models') && <IconButton icon={IconRefresh} aria-label="刷新当前状态" title="刷新当前状态" disabled={!supported(refreshAction)} onClick={() => { void run(refreshAction, {}).catch(() => {}); }} />}{navigationTrigger}</header>
    {workspace && workspace.key !== 'tools' ? <Tabs className="pw-workspace-navigation" selectedKey={workspacePageForRoute(page)} onSelectionChange={key => switchWorkspacePage(String(key))}>
      <TabList aria-label={workspace.title + '工作区'}>{workspace.pages.map(item => <Tab key={item.route} id={item.route}>{item.title}</Tab>)}</TabList>
      <TabPanel id={workspacePageForRoute(page) || workspace.route} className="pw-workspace-panel">{content}</TabPanel>
    </Tabs> : content}
  </section>;
}

