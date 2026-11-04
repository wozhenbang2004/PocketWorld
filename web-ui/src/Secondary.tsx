import SkillsWorkspace from './SkillsWorkspace';
import ChannelsWorkspace from './ChannelsWorkspace';
import { useCallback, useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import {
  RiAddLine, RiArrowLeftLine, RiArrowRightSLine, RiCheckLine, RiComputerLine, RiDatabase2Line,
  RiFileList3Line, RiKey2Line, RiLink, RiMicLine, RiPuzzle2Line, RiRefreshLine,
  RiSettings3Line, RiShieldCheckLine, RiSmartphoneLine, RiTeamLine,
} from '@remixicon/react';
import { bridge } from './bridge';
import ReminderWorkspace from './ReminderWorkspace';
import ConnectionWorkspace from './ConnectionWorkspace';
import { AppearanceSettings, SystemSpeechSettings } from './PresentationSettings';
import {LocalWorkspace} from './LocalWorkspace';
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
import { Tabs, TabList, Tab, TabPanel } from './shell-library';
import {
  Accordion, AccordionItem, Button, CheckboxField, HostFileUpload,
  IconButton, RadioOptions, SearchField, SelectField, SettingsDialog,
  StretchSwitch, TextAreaField, TextField, type FieldOption,
} from './forms-library';
import './secondary.css';
import './secondary-refactor.css';
import { backgroundConnection, modelConnectionCommand, wirelessEndpoints, wirelessPort } from './forms-library/host-contracts';
import { ModelConnectionOptions, ModelConnectionTestFeedback } from './ModelConnectionOptions';
import { compatibilityDraft, compatibilityNeedsSave, compatibilityOptions, connectionTestResult, type CompatibilityDraft, type ConnectionTestDraft } from './model-connection-policy';
import { ACCOUNT_RECEIPT_ACTIONS, accountActionReceipt, accountLoginArguments } from './account-actions';

/**
 * Product composition of the selected BoardUI SettingsModal/settings rows,
 * actual BoardUI field sources and the approved press/stretch switch.
 * Source originals, per-file hashes and adaptation records are in forms-library.
 * This module only dispatches named capabilities published by the Android host.
 * A command acknowledgement is never treated as a saved setting or approval.
 */
type Data = Record<string, any>;
type Snapshot = { state?: Data; view?: { resumeRevision?: number; taskId?: string | null }; composer?: Data | null; capabilities?: { commands?: string[] } };
type BackHandlerRegistration = (handler: (() => boolean) | null) => void;
type Props = { route: string; onBack: () => void; snapshot: Snapshot; registerBackHandler?: BackHandlerRegistration; navigationTrigger?: ReactNode; navigationRevision?: number; onNavigationPageChange?: (page: string) => void };
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
  { id: 'workProfiles', title: '工作配置', detail: '模型、技能与工具来源，恢复后重新绑定', group: '模型与连接', icon: RiFileList3Line },
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
function CapabilityAction({ action, supported, run, args = {}, children, primary = false, disabled = false, row = false }: { action: string; supported: (action: string) => boolean; run: Run; args?: Data; children: ReactNode; primary?: boolean; disabled?: boolean; row?: boolean }) {
  const [waiting, setWaiting] = useState(false);
  const click = async () => { setWaiting(true); try { await run(action, args); } catch { /* The page keeps the real error visible. */ } finally { setWaiting(false); } };
  const inactive = disabled || waiting || !supported(action);
  return primary ? <PressButton compact disabled={inactive} onClick={click} title={!supported(action) ? '当前页面尚未开放此操作' : undefined}>{waiting ? '处理中…' : children}</PressButton>
    : <Button variant={row ? "ghost" : "secondary"} className={row ? "pw-domain-link h-auto whitespace-normal" : undefined} trailingIcon={row ? RiArrowRightSLine : undefined} disabled={inactive} onClick={click} title={!supported(action) ? '当前页面尚未开放此操作' : undefined}>{waiting ? '处理中…' : children}</Button>;
}

type ModelDraft = { url: string; model: string; provider: string; screenshots: boolean; supportsImageInput: boolean; maxOutputTokens: string; compatibility: CompatibilityDraft };
// This explicit allowlist is the only model-edit state retained by Secondary.
// The API key, transport consent and review objects never enter this cache.
const ordinaryModelDraft = (value: ModelDraft): ModelDraft => ({
  url: value.url, model: value.model, provider: value.provider, screenshots: value.screenshots,
  supportsImageInput: value.supportsImageInput, maxOutputTokens: value.maxOutputTokens,
  compatibility: {
    enabled: value.compatibility.enabled, tokenField: value.compatibility.tokenField, streamUsage: value.compatibility.streamUsage,
    toolWireShape: value.compatibility.toolWireShape, schemaSubset: value.compatibility.schemaSubset, maxTools: value.compatibility.maxTools,
    maxRequestBytes: value.compatibility.maxRequestBytes, cacheMode: value.compatibility.cacheMode, cacheSupport: value.compatibility.cacheSupport,
  },
});
function ModelSettings({ state, connection, supported, run, adding = false, draft, onDraftChange, onSaved, onInspect, registerTransientBack }: {
  state: Data; supported: (action: string) => boolean; run: Run; adding?: boolean;
  connection?: Data; draft?: ModelDraft; onDraftChange: (draft: ModelDraft | null) => void; onSaved: (snapshot: Data) => void; onInspect: () => void; registerTransientBack: BackHandlerRegistration;
}) {
  const original = useRef(!adding && connection ? { ...connection } : undefined);
  const config = object(original.current?.configuration);
  const saveAction = adding ? 'models.connection.add' : 'models.connection.edit';
  const [form, setForm] = useState(() => ({
    ...(draft || {
      url: adding ? '' : text(connection?.baseUrl), model: adding ? '' : text(connection?.model),
      provider: adding ? 'openai_chat' : text(connection?.provider, 'openai_chat'),
      screenshots: adding ? state.screenshots !== false : config.sendScreenshots !== false, supportsImageInput: adding ? false : Boolean(config.supportsImageInput),
      maxOutputTokens: text(config.maxOutputTokens, '4096'), compatibility: compatibilityDraft(config.compatibility),
    }), apiKey: '',
  }));
  const editRevision = useRef(0);
  const submitted = useRef<{ editRevision: number; apiKey: string } | null>(null);
  const [transport, setTransport] = useState<Data | null>(null);
  const [formError, setFormError] = useState('');
  const [requesting, setRequesting] = useState(false);
  const [unknownAdd, setUnknownAdd] = useState(false);
  const [editorId] = useState(() => crypto.randomUUID());
  const [testResult, setTestResult] = useState<Data | null>(null);
  const [unknownTest, setUnknownTest] = useState(false);
  const [policyReset, setPolicyReset] = useState(false);
  const stale = !adding && (!connection || connection.id !== original.current?.id || connection.revision !== original.current?.revision);
  const canRetainApiKey = !adding && !stale && original.current?.hasApiKey === true && form.provider === original.current.provider && form.url.trim() === original.current.baseUrl;
  const apiKeyHint = stale ? '原连接已更新，请返回核对后再编辑密钥。' : canRetainApiKey
    ? form.apiKey.trim() ? '保存后更换原密钥；保存前清空输入可取消更换。' : '留空保留原密钥；填写新密钥并保存后更换。'
    : '请填写这项连接的密钥；新增连接不会借用其他连接的密钥。';
  const currentStale = useRef(stale); currentStale.current = stale;
  const testDraft = (): ConnectionTestDraft => ({ editorId, draftRevision: editRevision.current,
    connectionId: adding ? '' : text(original.current?.id), connectionRevision: adding ? 0 : Number(original.current?.revision) });
  let policyNeedsSave = false, policyError = '';
  try { policyNeedsSave = compatibilityNeedsSave(form.compatibility, original.current, form); }
  catch (error) { policyError = error instanceof Error ? error.message : String(error); }
  const [consentTarget, setConsentTarget] = useState(adding ? '' : text(config.cleartextConsent));
  const alive = useRef(true);
  const clearSecret = () => { editRevision.current++; submitted.current = null; setTransport(null); setTestResult(null); setForm(current => ({ ...current, apiKey: '' })); };
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
  const change = (key: keyof Omit<typeof form, 'compatibility'>, value: string | boolean) => {
    editRevision.current++;
    setTestResult(null); setFormError('');
    const next = { ...form, [key]: value };
    if (key === 'provider' || key === 'url') { setConsentTarget(''); setTransport(null); next.apiKey = ''; }
    if (key === 'provider' || key === 'url' || key === 'model') next.supportsImageInput = false;
    if (key === 'provider' || key === 'url' || key === 'model' || key === 'apiKey' && Boolean(value)) {
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
  ];
  const perform = async (action: string, consent: string) => {
    if (stale || unknownAdd) throw new Error('请先返回连接列表核对原保存结果，再明确打开要编辑的连接。');
    const testing = action === 'models.connection.test';
    if (testing && unknownTest) throw new Error('原测试结果待核对；本表单不会重复发起测试。');
    if (policyError) throw new Error(policyError);
    if (testing && policyNeedsSave) throw new Error('先保存兼容与缓存设置，再测试原连接。');
    const output = Number(form.maxOutputTokens);
    if (!form.url.trim() || !form.model.trim() || !Number.isInteger(output) || output < 128 || output > 32768) {
      setFormError('请填写接口地址、模型名称，以及 128–32768 的输出上限。'); return;
    }
    const command = modelConnectionCommand(adding, testing, original.current);
    const testedDraft = testing ? testDraft() : null;
    if (action === saveAction) submitted.current = { editRevision: editRevision.current, apiKey: form.apiKey };
    if (testing) setTestResult(null);
    try {
      const { compatibility, ...connectionFields } = form;
      const payload: Data = { ...connectionFields, ...command.identity, name: text(original.current?.name, form.model.trim()), url: form.url.trim(), model: form.model.trim(), maxOutputTokens: output, cleartextConsent: consent };
      if (!adding) payload.compatibility = compatibilityOptions(compatibility, form.provider);
      if (testedDraft) { payload.editorId = testedDraft.editorId; payload.draftRevision = testedDraft.draftRevision; }
      const result = await run(command.action, payload);
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
          setForm(current => current.apiKey === submission.apiKey ? { ...current, apiKey: '' } : current);
          onSaved(object(result.snapshot));
        }
      }
    } catch (failure) {
      if (testedDraft && alive.current && !['not_sent', 'not_accepted', 'invalid_request'].includes(text(object(failure).code))) setUnknownTest(true);
      if (action === saveAction) {
        submitted.current = null;
        if (adding && !['not_sent', 'not_accepted', 'invalid_request'].includes(text(object(failure).code))) {
          setUnknownAdd(true); setForm(current => ({ ...current, apiKey: '' }));
        }
      }
      throw failure;
    }
  };
  const request = async (action: string) => {
    if (!supported(action) || requesting) return;
    const requestedDraftRevision = editRevision.current;
    setFormError(''); setRequesting(true);
    try {
      if (supported('settings.reviewModelTransport')) {
        const originalEdit = editRevision.current;
        const review = object(await run('settings.reviewModelTransport', { provider: form.provider, url: form.url }));
        if (!alive.current || originalEdit !== editRevision.current) return;
        if (review.required && review.consentTarget !== consentTarget) {
          setTransport({ ...review, action, provider: form.provider, url: form.url }); return;
        }
        await perform(action, review.required ? text(review.consentTarget) : '');
      } else {
        if (/^http:\/\//i.test(form.url) && !/^http:\/\/(localhost|127\.0\.0\.1|\[::1\])(?::|\/|$)/i.test(form.url)) {
          setFormError('此明文连接需要先核对当前协议与地址。当前页面尚未提供该核对入口。'); return;
        }
        await perform(action, '');
      }
    } catch (error) { if (alive.current && requestedDraftRevision === editRevision.current) setFormError(error instanceof Error ? error.message : String(error)); }
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
          placeholder={stale ? '先返回核对连接' : canRetainApiKey ? '已保存 · 留空保留' : '输入 API 密钥'} hint={apiKeyHint} />
      </div></section>
      <Accordion className="pw-model-advanced" variant="divided">
        <AccordionItem id="advanced" title="高级设置" description="输出上限、图像输入与截图">
          <div className="pw-form-grid">
            <TextField label="每次回复的输出上限" value={form.maxOutputTokens} onChange={value => change('maxOutputTokens', value.replace(/\D/g, '').slice(0, 5))} inputMode="numeric"
              isDisabled={!adding && Boolean(original.current?.catalogEnabled)} hint={!adding && original.current?.catalogEnabled ? '在原连接的模型目录中核对精确参数和容量。' : '128–32768 tokens'} />
            <CheckboxField isSelected={form.supportsImageInput} onChange={value => change('supportsImageInput', value)} isDisabled={!adding && Boolean(original.current?.catalogEnabled)}>此模型支持图像输入</CheckboxField>
            <StretchSwitch label="允许按需发送截图" description="优先读取结构树；仅在已配置图像能力且确有需要时发送像素。" checked={form.screenshots} onChange={value => change('screenshots', value)} />
          </div>
        </AccordionItem>
        <AccordionItem id="compatibility" title="兼容与缓存" description="请求格式、发送限制与缓存支持">
          <ModelConnectionOptions value={form.compatibility} onChange={changeCompatibility} provider={form.provider} adding={adding} revision={object(config.compatibility).revision} />
          {policyError && <p role="alert" className="pw-field-error">{policyError}</p>}
          {policyReset && <p className="pw-field-hint">地址、协议、模型或替换密钥已变化；旧兼容与缓存档案没有沿用到这份草稿。需要时重新核对并保存。</p>}
        </AccordionItem>
      </Accordion>
      {formError && <p role="alert" className="pw-field-error">{formError}</p>}
      {(unknownAdd || stale) && <div className="pw-request-notice" role="status"><p>{unknownAdd ? '原新增连接的保存结果待核对；本表单不会再次新增。请读取连接列表，核对是否已保存。' : '这项原连接已被更新；旧草稿没有套用新修订。请返回详情核对后重新编辑。'}</p><Button variant="secondary" onClick={onInspect}>返回核对连接</Button></div>}
      {policyNeedsSave && <p className="pw-field-hint" role="status">先保存兼容与缓存设置，再测试原连接。</p>}
      {testResult && !stale && <><ModelConnectionTestFeedback value={testResult} />
        {testResult.inferenceReference?.taskId && <CapabilityAction action="tasks.open" supported={supported} run={run} args={{ id: testResult.inferenceReference.taskId }}>查看本次实际调用与用量</CapabilityAction>}
      </>}
      {unknownTest && <div className="pw-request-notice" role="status"><p>原测试结果待核对；本表单不会自动重试或再次发送。请先核对原调用与连接，再明确开始新的测试。</p><Button variant="secondary" onClick={onInspect}>返回核对连接</Button></div>}
      {state.error && <p className="pw-field-error" role="alert">{text(state.error)}</p>}
      <div className="pw-form-actions pw-model-save-actions"><Button variant="secondary" disabled={stale || unknownAdd || unknownTest || policyNeedsSave || Boolean(policyError) || requesting || state.modelTesting || state.modelSelectionBusy || !supported('models.connection.test')} onClick={() => request('models.connection.test')}>{state.modelTesting ? '测试中…' : '测试连接'}</Button><PressButton compact disabled={stale || unknownAdd || Boolean(policyError) || requesting || state.modelSaving || state.modelTesting || state.modelSelectionBusy || !supported(saveAction)} onClick={() => request(saveAction)}>{state.modelSaving ? '保存中…' : adding ? '添加并用于后续任务' : '保存原连接'}</PressButton></div>
    </section>
    <SettingsDialog isOpen={Boolean(transport)} title="允许这个明文模型连接？" onClose={clearSecret}>
      {transport && <><Facts items={[[ '协议', providers.find(x => x.id === transport.provider)?.label ], ['地址', transport.displayTarget || transport.url]]} />
        <p>凭据、文本和按需截图将通过 HTTP 明文传输。更换协议或地址后需要重新核对。</p>
        <div className="pw-form-actions"><Button variant="secondary" onClick={clearSecret}>取消</Button><PressButton compact disabled={requesting} onClick={async () => { if (requesting) return; const reviewed = transport; setTransport(null); if (reviewed.provider !== form.provider || reviewed.url !== form.url) return; setConsentTarget(text(reviewed.consentTarget)); setRequesting(true); try { await perform(reviewed.action, text(reviewed.consentTarget)); } catch (error) { if (alive.current) setFormError(error instanceof Error ? error.message : String(error)); } finally { if (alive.current) setRequesting(false); } }}>允许此协议与地址</PressButton></div>
      </>}
    </SettingsDialog>
  </div>;
}

type ModelView = { kind: 'list' | 'connections' | 'edit' | 'add' | 'connection' | 'catalog' | 'model'; id?: string };
const modelConnectionRows = (data: Data) => Array.isArray(data.connections)
  ? list(data.connections) : list(object(data.connections).connections);
const MODEL_PROVIDERS: Record<string, string> = { openai_chat: 'OpenAI Chat', openai_responses: 'OpenAI Responses', anthropic: 'Anthropic', gemini: 'Gemini' };

/** The BoardUI settings rows retain each profile identity across browsing and editing. */
function ModelConnections({ state, data, supported, run, navigate, open }: { state: Data; data: Data; supported: (action: string) => boolean; run: Run; navigate: (route: string) => void; open: (view: ModelView) => void }) {
  const profiles = modelConnectionRows(data);
  const selectedId = text(object(data.connections).selectedConnectionId);
  const selectedConnection = profiles.find(row => recordId(row) === selectedId);
  const account = state.modelRoute === 'account';
  const configured = Boolean(text(state.baseUrl).trim());
  return <section className="pw-model-connections" data-model-layer="connections" aria-label="连接列表">
      <div className="pw-settings-links">
        {configured && selectedConnection && <Button variant="ghost" aria-label="编辑当前 API 连接" leadingIcon={RiLink} trailingIcon={RiArrowRightSLine} className="pw-domain-link h-auto whitespace-normal" onClick={() => open({ kind: 'edit', id: selectedId })}>
          <span><span>{first(selectedConnection || {}, ['name', 'label'], 'API 连接设置')}</span><small>{account ? '保留的连接' : '当前使用'}{' · '}{state.hasApiKey ? '凭据已保存' : '地址、凭据与能力'}</small></span>
        </Button>}
        {profiles.filter(row => recordId(row) !== selectedId || !configured).map((row, index) => <Button key={recordId(row, String(index))} variant="ghost" leadingIcon={RiLink} trailingIcon={RiArrowRightSLine} className="pw-domain-link h-auto whitespace-normal" onClick={() => open({ kind: 'connection', id: recordId(row) })}>
          <span><span>{first(row, ['name', 'label', 'id'], '已保存连接')}</span><small>{MODEL_PROVIDERS[text(row.provider)] || first(row, ['provider', 'protocol'], 'API')}{row.enabled === false ? ' · 已停用' : ' · 已保存'}</small></span>
        </Button>)}
        <Button variant="ghost" leadingIcon={RiKey2Line} trailingIcon={RiArrowRightSLine} className="pw-domain-link h-auto whitespace-normal" onClick={() => navigate('accounts')}><span><span>ChatGPT 账号</span></span></Button>
      </div>
      {account && selectedConnection && <div className="pw-form-actions"><Button variant="secondary" disabled={Boolean(state.modelSaving || state.modelSelectionBusy)} onClick={() => open({ kind: 'connection', id: selectedId })}>核对并使用这项 API 连接</Button></div>}
      {!configured && profiles.length === 0 && <Empty>还没有 API 连接。添加后可以测试并保存。</Empty>}
      <div className="pw-connection-add"><Button variant="secondary" leadingIcon={RiAddLine} disabled={!supported('models.connection.add')} onClick={() => open({ kind: 'add' })}>添加 API 连接</Button></div>
  </section>;
}

function ModelConnectionDetail({ connection, supported, run, open, busy }: { connection: Data | undefined; supported: (action: string) => boolean; run: Run; open: (view: ModelView) => void; busy: boolean }) {
  return connection ? <section className="pw-model-connection-detail" aria-label="连接详情">
    <Facts items={[[ '名称', first(connection, ['name', 'label', 'id']) ], ['协议', first(connection, ['provider', 'protocol']) ], ['模型', connection.model], ['地址', first(connection, ['baseUrl', 'endpoint']) ], ['状态', connection.enabled === false ? '已停用' : '已启用'], ['凭据', connection.hasApiKey || connection.credentialsConfigured ? '已配置' : '未配置']]} />
    <div className="pw-form-actions"><Button variant="secondary" disabled={busy || !supported('models.connection.edit')} onClick={() => open({ kind: 'edit', id: recordId(connection) })}>编辑这项连接</Button><CapabilityAction action="models.connection.select" args={{ id: recordId(connection), expectedRevision: connection.revision }} supported={supported} run={run} disabled={busy || connection.enabled === false}>用于后续任务</CapabilityAction></div>
    <p className="pw-field-hint">正在执行的任务与连续补充保留原模型。停用连接或撤销凭据仍会生效。</p>
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

function PhoneSettings({ state, supported, run, navigate }: { state: Data; supported: (action: string) => boolean; run: Run; navigate: (route: string) => void }) {
  const ready = object(state.phoneReadiness);
  const screenshotPolicy = object(state.taskScreenshotPolicy);
  const latestReply = list(state.messages).filter(row => row.role === 'assistant' && typeof row.id === 'string' && row.content).at(-1);
  const appearance = text(object(state.appearance).mode);
  return <>
    <Card title="手机执行">
      <p className="pw-detail-status">{first(ready, ['message', 'summary', 'status'], '尚未核对执行能力')}</p>
      <div className="pw-settings-links">
        {[
          ['accessibility', '界面读取与主屏控制', state.accessibilityEnabled ? '已连接' : '未连接'],
          ['notifications', '任务通知', state.notificationsEnabled ? '已开启' : '未开启'],
          ['wireless', '无线调试', ''],
        ].map(([section, title, current]) => <CapabilityAction key={section} row action="settings.openSystem" args={{ section }} supported={supported} run={run}><span><span>{title}</span>{current && <small>{current}</small>}</span></CapabilityAction>)}
        <Button variant="ghost" trailingIcon={RiArrowRightSLine} className="pw-domain-link h-auto whitespace-normal" disabled={!supported('settings.openSystem')} onClick={() => navigate('background')}><span><span>后台连接与授权</span></span></Button>
        <CapabilityAction row action="settings.openAppUpdates" supported={supported} run={run}><span><span>应用更新</span><small>检查新版本与管理下载</small></span></CapabilityAction>
      </div>
    </Card>
    <Accordion variant="divided" className="pw-settings-groups pw-phone-groups" allowsMultipleExpanded>
      <AccordionItem id="observation" title="本地观察与截图" description={`取图${state.localCaptureAllowed !== false ? '开启' : '关闭'} · OCR ${state.localOcrAllowed !== false ? '开启' : '关闭'}`} icon={RiShieldCheckLine} headingLevel={2} className="pw-settings-group">
      <StretchSwitch label="允许本地按需取图" description="更改用于后续运行；关闭后不为 OCR 采集像素。" checked={state.localCaptureAllowed !== false} disabled={!supported('settings.saveLocalObservation')} onChange={capture => { void run('settings.saveLocalObservation', { capture, ocr: state.localOcrAllowed !== false }).catch(() => {}); }} />
      <StretchSwitch label="允许本地 OCR" description="像素在本地识字；有来源的识别文字可发送给已选推理服务。" checked={state.localOcrAllowed !== false} disabled={!supported('settings.saveLocalObservation')} onChange={ocr => { void run('settings.saveLocalObservation', { capture: state.localCaptureAllowed !== false, ocr }).catch(() => {}); }} />
      {typeof screenshotPolicy.revision === 'number' ? <RadioOptions label="新结果截图的本机留存" value={text(screenshotPolicy.retentionMillis)} options={[{ id: '0', label: '不保存新截图' }, { id: '86400000', label: '1 天' }, { id: '604800000', label: '7 天' }, { id: '2592000000', label: '30 天' }]} isDisabled={Boolean(state.taskScreenshotSaving) || !supported('privacy.screenshots.save')} onChange={value => { void run('privacy.screenshots.save', { revision: screenshotPolicy.revision, retentionMillis: Number(value) }).catch(() => {}); }} /> : <CapabilityAction action="privacy.screenshots.read" supported={supported} run={run}>读取截图留存设置</CapabilityAction>}
      <p className="pw-field-hint">结果截图使用本机密钥加密。设置只影响之后生成的截图；旧图保留到你主动删除或被同一任务的新结果替换。可从原任务核对中删除本机截图。</p>
      </AccordionItem>
      <AccordionItem id="appearance" title="界面外观" description={{ system: '跟随系统', light: '浅色', dark: '深色' }[appearance] || undefined} icon={RiSmartphoneLine} headingLevel={2} className="pw-settings-group"><AppearanceSettings state={state} supported={supported} run={run} /></AccordionItem>
      <AccordionItem id="speech" title="浏览器与语音" description={state.speaking ? '正在朗读' : undefined} icon={RiMicLine} headingLevel={2} className="pw-settings-group">
        <p className="pw-field-hint">语音识别只填入草稿，由你决定发送。</p>
      <SystemSpeechSettings state={state} supported={supported} run={run} />
      <StretchSwitch label="任务状态语音提示" description="仅前台私有任务的状态提示，不读任务正文；系统语音引擎可能联网。默认关闭。" checked={object(state.taskAnnouncements).enabled === true} disabled={Boolean(state.taskAnnouncementSaving) || !supported('speech.taskAnnouncements')} onChange={enabled => { void run('speech.taskAnnouncements', { enabled }).catch(() => {}); }} />
      {object(object(state.taskAnnouncements).readout).active && <CapabilityAction action="speech.stopTaskAnnouncement" args={{ readoutId: object(object(state.taskAnnouncements).readout).readoutId }} supported={supported} run={run}>停止这次状态提示</CapabilityAction>}

      <Facts items={[[ '系统听写', state.speechAvailable ? '可用' : '暂不可用'], ['朗读', state.speaking ? '正在朗读' : '未在朗读']]} />
      <div className="pw-form-actions"><CapabilityAction action="settings.openBrowser" args={{ purpose: 'research' }} supported={supported} run={run}>网页研究工作区</CapabilityAction><CapabilityAction action="settings.openBrowser" args={{ purpose: 'browse' }} supported={supported} run={run}>内置浏览器</CapabilityAction><CapabilityAction action={state.speaking ? 'speech.stop' : 'speech.speak'} args={state.speaking ? {} : { messageId: latestReply?.id }} disabled={!state.speaking && !latestReply} supported={supported} run={run}>{state.speaking ? '停止朗读' : '朗读最近回复'}</CapabilityAction></div>
      </AccordionItem>
    </Accordion>
  </>;
}

/** Original BackgroundPhoneSetup flow composed with the selected BoardUI
 * fields, radio options, accordion and SettingsModal. It reads the existing
 * display state; this page does not create a task or a second connection. */
function BackgroundSettings({ state, supported, run, navigate }: { state: Data; supported: (action: string) => boolean; run: Run; navigate: (route: string) => void }) {
  const display = object(object(state.features).display), adb = object(display.adb), discovery = object(adb.discovery);
  const audioRecovery = object(object(object(state.features).phone_setup).audioRecovery), audioPending = audioRecovery.pendingCount;
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
      {Number.isSafeInteger(audioPending) && audioPending > 0 && <div role="alert"><p className="pw-field-error">还有 {audioPending} 项声音记录待处理，原 App 可能仍静音。请切换到原生工作台，在后台连接设置中处理原声音记录。</p>{audioRecovery.needsOwnerAction === true && <div className="pw-form-actions"><CapabilityAction action="settings.openSystem" args={{ section: 'accessibility' }} supported={supported} run={run}>打开无障碍设置</CapabilityAction></div>}</div>}
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
          return <Button key={id} variant="ghost" trailingIcon={RiArrowRightSLine} className="pw-object-row h-auto whitespace-normal" onClick={() => { remember(); setSelected({ kind: tab, id }); }}>
            <span className="pw-object-row-copy"><span className="pw-object-row-title">{titleOf(row)}</span>{(row.status || row.state) && <small className="pw-object-row-status">{statusText(row.status || row.state)}</small>}{context && <small>{context}</small>}{tab === 'runs' && summaryOf(row) && summaryOf(row) !== titleOf(row) && <span className="pw-object-row-summary">{summaryOf(row)}</span>}</span>
          </Button>;
        })}</div>
      </TabPanel>
    </Tabs>}
  </section>;
}

function AccountsWorkspace({ state, data, supported, run, registerTransientBack }: { state: Data; data: Data; supported: (action: string) => boolean; run: Run; registerTransientBack: BackHandlerRegistration }) {
  const [catalog, setCatalog] = useState<Data | null>(null), [review, setReview] = useState<Data | null>(null);
  const [busy, setBusy] = useState(false), [receipt, setReceipt] = useState<Data | null>(null), [query, setQuery] = useState('');
  const [options, setOptions] = useState({ reasoningEffort: '', outputReserve: '4096', imageTokenReserve: '0' });
  const [adding, setAdding] = useState(false), [accountLabel, setAccountLabel] = useState(''), [needsAccountRead, setNeedsAccountRead] = useState(false);
  const [accountFeedback, setAccountFeedback] = useState<{ message: string; error: boolean } | null>(null);
  const alive = useRef(true), generation = useRef(0), operationPending = useRef(false);
  const closeReview = () => { generation.current++; setReview(null); };
  useEffect(() => { alive.current = true; return () => { alive.current = false; generation.current++; }; }, []);
  useEffect(() => { registerTransientBack(adding ? () => { if (!busy) setAdding(false); return true; } : review ? () => { if (!busy) closeReview(); return true; } : catalog ? () => { if (!busy) { generation.current++; setCatalog(null); } return true; } : null); return () => registerTransientBack(null); }, [adding, review, catalog, busy, registerTransientBack]);
  const pending = Array.isArray(state.ownerPending) ? state.ownerPending : [];
  const locked = busy || Boolean(state.modelSelectionBusy) || pending.some((key: string) => key.startsWith('accounts.'));
  const accounts = list(data.accounts), login = object(data.login), selected = object(data.selectedModelRef);
  const catalogBody = object(catalog?.catalog), catalogAccount = accounts.find(row => row.id === catalogBody.accountId);
  const catalogCurrent = Boolean(catalog && catalog.selectionRevision === state.modelSelectionRevision && catalogAccount?.version === catalogBody.accountVersion);
  const reviewCurrent = Boolean(review && review.selectionRevision === state.modelSelectionRevision && accounts.some(row => row.id === review.accountId && row.version === review.accountVersion));
  const models = list(catalogBody.models), visibleModels = models.filter(row => [row.id, row.displayName].some(value => text(value).toLocaleLowerCase().includes(query.trim().toLocaleLowerCase())));
  const runAccount: Run = async (action, args = {}) => {
    if (!ACCOUNT_RECEIPT_ACTIONS.has(action)) return run(action, args);
    if (operationPending.current) throw new Error('请先等待本次账号操作的回执。');
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
        setAccountFeedback({ message: failure instanceof Error ? failure.message : String(failure), error: true });
        if ((failure as { code?: string })?.code !== 'native_failure' && action !== 'accounts.refresh') setNeedsAccountRead(true);
      }
      throw failure;
    } finally { if (alive.current && original === generation.current) { operationPending.current = false; setBusy(false); } }
  };
  const act = (action: string, args: Data, label: string, primary = false) => <CapabilityAction action={action} args={args} supported={supported} run={runAccount} disabled={locked} primary={primary}>{label}</CapabilityAction>;
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
    <Card title="ChatGPT 账号" description="登录后读取这个账号的可用模型，再确认用于新任务的模型。">
      <div className="pw-form-actions"><PressButton compact disabled={locked || loginActive || needsAccountRead || !supported('accounts.beginLogin')} onClick={() => { setAccountFeedback(null); setAdding(true); }}>添加账号</PressButton>{act('accounts.refresh', {}, '刷新账号状态')}</div>
      {needsAccountRead && <p className="pw-field-hint">请先刷新账号状态，核对上次操作后再添加账号。</p>}
      {accountFeedback && !adding && <p className={accountFeedback.error ? 'pw-field-error' : 'pw-receipt-copy'} role={accountFeedback.error ? 'alert' : 'status'}>{accountFeedback.message}</p>}
      {state.modelRoute === 'account' && data.selectedModelRef && <div className="pw-selected-object"><h3>当前账号模型</h3><Facts items={[[ '模型', selected.modelId ], ['账号', accounts.find(row => row.id === selected.accountId)?.label ], ['可用状态', data.selectedModelUsable ? '当前引用可用' : '需要重新读取目录并选择' ]]} /></div>}
      {data.planWelcomeRequired === true && welcomeAccount && <div className="pw-receipt-copy"><h3>已通过 ChatGPT 登录</h3><p>在本应用选择账号模型后，推理使用你的 ChatGPT 套餐，并受套餐和此应用的使用限制。登录不授予手机操作权限；图片仍需任务授权和目录支持。</p><div className="pw-form-actions">{act('accounts.acknowledgePlanWelcome', {accountId: welcomeAccount.id, expectedVersion: welcomeAccount.version}, '知道了')}{act('accounts.manageUsage', {}, '管理用量')}</div></div>}
      <Collection data={data} keys={['accounts']} label="账号">{row => <>
        <Facts items={[[ '套餐', row.plan || row.planName ], ['套餐使用权限', row.planUsageEnabled ? '已授予' : '尚未授予'], ['状态', statusText(row.status)], ['冷却至', timeText(row.cooldownUntil)], ['本机选择', data.selectedAccountId === row.id ? '当前账号' : undefined]]} />
        {row.usageLimited && <p className="pw-field-hint">套餐请求已暂停。明确允许再试后，仍需回到原任务继续；不会自动发送请求。</p>}
        <StretchSwitch label="启用此账号" description="关闭会停止新的账号请求；已发生的远端操作仍需按原回执核对。" checked={row.enabled === true} disabled={locked || !supported('accounts.setEnabled')} onChange={enabled => { void runAccount('accounts.setEnabled', {accountId: row.id, expectedVersion: row.version, enabled}).catch(() => {}); }} />
        {row.error && <p className="pw-field-error" role="alert">{text(row.error)}</p>}
        <div className="pw-form-actions">
          {act('accounts.select', { accountId: row.id, expectedVersion: row.version }, '选择此账号')}
          <Button variant="secondary" disabled={locked || !supported('accounts.models') || !row.enabled || !row.planUsageEnabled || row.usageLimited} onClick={() => readModels(row)}>读取模型目录</Button>
          {row.usageLimited && act('accounts.retryPlanUsage', { accountId: row.id, expectedVersion: row.version }, '允许手动再试')}
          {row.planUsageEnabled && act('accounts.manageUsage', {}, '管理套餐用量')}
          {row.enabled && !loginActive && row.status === 'connected' && act('accounts.refreshAuthorization', {accountId: row.id, expectedVersion: row.version}, '刷新授权')}
          {row.enabled && !loginActive && (['signed_out', 'reauthorization_required'].includes(text(row.status)) || !row.planUsageEnabled) && act('accounts.reauthorize', {accountId: row.id, expectedVersion: row.version}, '重新登录此账号')}
          <Button variant="secondary" disabled={locked || !supported('accounts.logout.review')} onClick={() => prepare('logout', { accountId: row.id, expectedVersion: row.version })}>核对并退出</Button>
        </div>
      </>}</Collection>
      {login.sessionId && <div className="pw-selected-object"><h3>本次授权</h3><Facts items={[[ '状态', statusText(login.status) ], ['到期时间', timeText(login.expiresAt) ]]} />{login.status === 'pending' && <div className="pw-form-actions">{act('accounts.openAuthorization', { sessionId: login.sessionId }, '打开原授权页面')}{act('accounts.cancelLogin', { sessionId: login.sessionId }, '取消本次授权')}</div>}</div>}
      {receipt && <div className="pw-receipt-copy" role="status">{receipt.status !== 'saved' ? '原操作结果待核对。请刷新账号状态；不会自动重试。' : receipt.kind === 'model' ? '模型选择已保存。当前使用状态以上方统一模型状态为准；已有任务保留原模型。' : actualReceipt.signedOutLocally ? '已在本机退出，账号记录保留。' + (actualReceipt.credentialCleanupRequired ? ' 本机凭据仍需清理。' : '') + (actualReceipt.remoteRevocationConfirmed ? ' 服务商确认撤销授权。' : ' 远端撤销尚未确认。') : '收到原退出回执，请刷新并核对本机状态。'}</div>}
    </Card>
    <SettingsDialog isOpen={adding} title="添加 ChatGPT 账号" onClose={() => { if (!busy) setAdding(false); }}>
      <TextField label="账号名称（可选）" placeholder="我的 ChatGPT 账号" value={accountLabel} maxLength={80} isDisabled={busy} onChange={setAccountLabel} hint="名称只用于本机辨认。登录会打开系统浏览器。" />
      {accountFeedback && <p className={accountFeedback.error ? 'pw-field-error' : 'pw-receipt-copy'} role={accountFeedback.error ? 'alert' : 'status'}>{accountFeedback.message}</p>}
      {needsAccountRead && <p className="pw-field-hint">请先返回并刷新账号状态，核对原授权后再继续。</p>}
      <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => setAdding(false)}>返回</Button><PressButton compact disabled={locked || needsAccountRead || loginActive} onClick={() => {
        try { void runAccount('accounts.beginLogin', accountLoginArguments(accountLabel)).catch(() => {}); }
        catch (failure) { setAccountFeedback({ message: (failure as Error).message, error: true }); }
      }}>{busy ? '正在准备授权…' : '用 ChatGPT 登录'}</PressButton></div>
    </SettingsDialog>
    {catalog && <Card title="此账号的模型目录" description="能力以这次实际目录为准；选择不会自动授权手机操作。">
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

function McpWorkspace({ state, data, supported, run, registerTransientBack }: { state: Data; data: Data; supported: (action: string) => boolean; run: Run; registerTransientBack: BackHandlerRegistration }) {
  const [review, setReview] = useState<Data | null>(null), [selected, setSelected] = useState<string[]>([]);
  const [detail, setDetail] = useState<Data | null>(null), [busy, setBusy] = useState(false);
  const alive = useRef(true), view = useRef(0);
  const closeReview = () => { view.current++; setReview(null); };
  useEffect(() => { alive.current = true; return () => { alive.current = false; view.current++; }; }, []);
  useEffect(() => { registerTransientBack(review ? () => { if (!busy) closeReview(); return true; } : detail ? () => { setDetail(null); return true; } : null); return () => registerTransientBack(null); }, [review, detail, busy, registerTransientBack]);
  const responses = object(object(state.ownerResponses).mcp), picked = object(responses.pickConfig), config = object(object(picked.result).config);
  const progress = object(state.mcpImport), phase = text(progress.phase), pending = Array.isArray(state.ownerPending) ? state.ownerPending : [];
  const writing = busy || ['mcp.installMcp', 'mcp.setEnabledReviewed', 'mcp.setToolGrantReviewed'].some(key => pending.includes(key));
  const preparing = pending.includes('mcp.discoverMcp');
  const openReview = async (action: string, args: Data, kind: string) => {
    const original = ++view.current; setBusy(true);
    try { const receipt = object(await run(action, args)); if (alive.current && original === view.current) { setSelected([]); setReview({ ...receipt, kind }); } }
    catch { /* The shared page displays the native failure. */ }
    finally { if (alive.current) setBusy(false); }
  };
  const confirm = async () => {
    if (!review || writing) return;
    const original = review; setBusy(true);
    try {
      await run(original.kind === 'install' ? 'mcp.installImport' : original.kind === 'grant' ? 'mcp.confirmGrant' : 'mcp.confirmEnabled', { reviewHandle: original.reviewHandle, ...(original.kind === 'install' ? { tools: selected } : {}) });
      if (alive.current) { closeReview(); setDetail(null); }
    } catch { if (alive.current) closeReview(); }
    finally { if (alive.current) setBusy(false); }
  };
  const installed = list(data.plugins), currentDetail = detail && installed.find(item => item.id === detail.id && item.installationId === detail.installationId);
  const progressCopy: Record<string, string> = { discovering: '正在读取服务声明，等待原发现回执…', discovered: '工具发现完成，可以打开预览。', installing: '原安装正在保存，等待实际回执…', installed: '安装已保存，保持停用；工具权限需要逐项核对。', unknown: '安装结果待核对，请刷新已安装列表；不会自动重新安装。', stale: '原发现已变化或过期，请重新核对配置。', failed: '原发现未完成，请查看回执后核对服务。' };
  return <>
    <Card title="MCP 服务" description="读取服务声明、安装与工具授权分别确认。">
      <Collection data={data} keys={['plugins']} label="已安装服务">{row => <>
        <Facts items={[[ '版本', object(row.manifest).version ], ['状态', row.enabled ? '已启用' : '已停用'], ['服务状态', row.mcpStatus]]} />
        <div className="pw-form-actions"><Button variant="secondary" disabled={writing || !supported('mcp.read')} onClick={async () => { const original = ++view.current; try { const receipt = object(await run('mcp.read', { id: row.id, installationId: row.installationId })); if (alive.current && original === view.current) setDetail(receipt); } catch {} }}>查看工具与权限</Button><Button variant="secondary" disabled={writing || !supported('mcp.reviewEnabled')} onClick={() => openReview('mcp.reviewEnabled', { id: row.id, installationId: row.installationId, enabled: !row.enabled }, 'enable')}>{row.enabled ? '核对并停用' : '核对并启用'}</Button></div>
      </>}</Collection>
      <HostFileUpload label="选择 MCP 配置" onChoose={() => { closeReview(); void run('mcp.pickConfig', {}).catch(() => {}); }} disabled={writing || preparing || !supported('mcp.pickConfig') || pending.includes('mcp.pickConfig')} status={pending.includes('mcp.pickConfig') ? 'importing' : picked.ok === false ? 'error' : 'idle'} statusDescription="系统选择器读取不含凭据的 JSON 配置，上限 64 KB" error={picked.ok === false ? text(picked.error) : undefined} />
      {picked.ok === true && <div className="pw-selected-object"><h3>已读取的配置</h3><Facts items={[[ '名称', config.name ], ['端点', config.endpoint ], ['协议版本', config.protocolVersion ]]} /><p className="pw-field-hint">发现只读取服务声明；安装不会调用工具，也不会自动启用或授权。</p><CapabilityAction action="mcp.discoverImport" args={{ importRequestId: picked.requestId }} supported={supported} run={run} disabled={writing || preparing || phase === 'unknown'}>发现并预览服务</CapabilityAction></div>}
      {phase !== 'idle' && <p className="pw-receipt-copy" role="status">{progressCopy[phase] || text(progress.message)}</p>}
      {phase === 'discovered' && <Button variant="secondary" disabled={writing || !supported('mcp.reviewImport')} onClick={() => openReview('mcp.reviewImport', {}, 'install')}>核对工具并安装</Button>}
      {preparing && <CapabilityAction action="mcp.cancel" args={{ operationId: progress.operationId }} supported={supported} run={run}>取消原发现</CapabilityAction>}
      {[['启停', object(progress.enableReceipt)], ['工具权限', object(progress.grantReceipt)]].map(([label, raw]) => { const receipt = object(raw); return receipt.requestId ? <p key={text(label)} className="pw-receipt-copy" role="status">{text(label)}：{receipt.status === 'saved' ? '原安装的更改已保存。' : receipt.status === 'pending' ? '等待原操作回执…' : '结果待核对，请刷新原安装。'}</p> : null; })}
    </Card>
    <SettingsDialog isOpen={detail !== null} title="当前安装的工具与权限" onClose={() => setDetail(null)}>{detail && <>
      <Facts items={[[ '服务', object(detail.manifest).name || detail.id ], ['版本', object(detail.manifest).version ]]} />
      {!currentDetail && <p role="alert" className="pw-field-error">原安装已变化，请关闭后刷新。</p>}
      {list(object(detail.manifest).tools).map(tool => { const granted = Array.isArray(currentDetail?.grants) && currentDetail.grants.includes('tool:' + tool.name); return <SourceRecord key={text(tool.name)} item={tool}><Facts items={[[ '地址', tool.endpoint ], ['权限', granted ? '已允许' : '未允许']]} /><Button variant="secondary" disabled={!currentDetail || writing || !supported('mcp.reviewGrant')} onClick={() => openReview('mcp.reviewGrant', { id: detail.id, installationId: detail.installationId, grantsDigest: currentDetail?.grantsDigest, tool: tool.name, granted: !granted }, 'grant')}>{granted ? '核对并撤销权限' : '核对并允许工具'}</Button></SourceRecord>; })}
      <p className="pw-field-hint">启用服务不代替逐项工具授权；敏感执行仍需原任务按具体参数批准。</p>
    </>}</SettingsDialog>
    <SettingsDialog isOpen={review !== null} title={review?.kind === 'install' ? '核对这次发现并安装' : review?.kind === 'grant' ? review.granted ? '允许这个工具？' : '撤销工具权限？' : review?.enabled ? '启用这项安装？' : '停用这项安装？'} onClose={() => { if (!busy) closeReview(); }}>{review && <>
      <Facts items={[[ '服务', review.name || object(review.manifest).name || review.id ], ['地址', review.endpoint || object(review.selectedTool).endpoint ], ['协议', review.protocolVersion ], ['预览到期', timeText(review.expiresAt) ]]} />
      {review.kind === 'install' ? <><p>选择要安装的工具（{selected.length}/12）。服务说明和参数仅供核对。</p>{list(review.tools).map(tool => <div key={text(tool.name)}><CheckboxField isSelected={selected.includes(tool.name)} isDisabled={busy || !tool.supported || selected.length >= 12 && !selected.includes(tool.name)} onChange={value => setSelected(current => value ? [...current, tool.name] : current.filter(name => name !== tool.name))}>{text(tool.name)}</CheckboxField><p className="pw-field-hint">{text(tool.description || object(tool.declaration).description)}{!tool.supported ? ' · 当前不支持' : ''}</p></div>)}{review.mcpSource && <p>包含资源或提示来源；不选工具可只安装参考来源。</p>}{review.expectedInstallationId && <p>将替换同标识的旧安装、清空旧工具授权；已发生的远端效果不会撤回。</p>}<p>保存后保持停用且不授予工具权限。</p></> : review.kind === 'grant' ? <><p>{text(object(review.selectedTool).description)}</p><pre className="pw-source-code">{JSON.stringify(object(object(review.selectedTool).schema), null, 2)}</pre><p>仅改变这项安装的一个工具权限。原任务仍需按实际参数批准敏感动作。</p></> : <p>{review.enabled ? '仅启用这项安装。工具仍需逐项授权。' : '停止继续使用这项安装；已发生的远端效果不会撤回。'}</p>}
      <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={closeReview}>取消</Button><PressButton compact disabled={busy || review.kind === 'install' && selected.length === 0 && !review.mcpSource} onClick={confirm}>{busy ? '等待受理…' : review.kind === 'install' ? '确认安装' : '确认更改'}</PressButton></div>
    </>}</SettingsDialog>
  </>;
}

function DomainPage({ domain, state, data, supported, run, registerTransientBack, candidateTarget, onReviewCandidate, onOpenModels, onNavigate, alreadyShownError }: { domain: string; state: Data; data: Data; supported: (action: string) => boolean; run: Run; registerTransientBack: BackHandlerRegistration; candidateTarget: LearningCandidateTarget | null; onReviewCandidate: (target: LearningCandidateTarget) => void; onOpenModels: () => void; onNavigate: (domain: 'plugins' | 'pluginCatalog' | 'mcp') => void; alreadyShownError?: string }) {
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
  if (domain === 'mcp') return <McpWorkspace state={state} data={data} supported={supported} run={run} registerTransientBack={registerTransientBack} />;
  if (domain === 'skills') return <SkillsWorkspace state={state} run={run} registerTransientBack={registerTransientBack} />;
  if (domain === 'plugins' || domain === 'pluginCatalog') return <PluginsWorkspace mode={domain} state={state} supported={supported} run={run} registerTransientBack={registerTransientBack} onNavigate={onNavigate} />;
  if (domain === 'teams') return <TeamsWorkspace state={state} run={run} registerTransientBack={registerTransientBack} alreadyShownError={alreadyShownError} />;
  if (domain === 'groups') return <GroupsWorkspace state={state} data={data} supported={supported} run={run} registerTransientBack={registerTransientBack} />;
  if (domain === 'media') return <MediaWorkspace state={state} run={run} registerTransientBack={registerTransientBack} />;
  if (domain === 'realtime') return <RealtimeWorkspace state={state} run={run} registerTransientBack={registerTransientBack} />;
  if (domain === 'proxy') return <ProxyWorkspace state={state} run={run} registerTransientBack={registerTransientBack} />;
  if (domain === 'local') return <LocalWorkspace state={state} data={data} run={run} supported={supported} registerTransientBack={registerTransientBack} />;
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
  const selection = object(data.selection), pin = { selectionId: selection.selectionId, sha256: selection.archiveSha256 };
  const preview = object(object(data.preview).review), config = object(data.webDavConfig), review = object(data.review), uploadReview = object(data.webDavReview);
  const original = object(data.originalPhase), upload = object(data.webDavUpload), busy = Boolean(data.busy), phasePending = Boolean(data.originalAction && !['completed', 'failed'].includes(original.status));
  const nativeResult = object(object(data.result).nativeResult), nativeScope = object(review.nativeScope);
  const hasReview = Boolean(data.review || data.webDavReview), locked = busy || hasReview || Boolean(data.passwordPromptId);
  const command: Run = (action, args) => run(action, args);
  const act = (action: string, args: Data, label: string, disabled = false, primary = false) => <CapabilityAction action={action} args={args} supported={supported} run={command} disabled={busy || disabled} primary={primary}>{label}</CapabilityAction>;
  const cancelReview = () => { const current = data.review ? review : uploadReview; void run(data.review ? 'archives.cancelReview' : 'archives.webdav.cancelReview', { actionId: current.actionId }).catch(() => {}); };
  useEffect(() => setConfirmed(false), [review.actionId, uploadReview.actionId]);
  useEffect(() => { const clear = () => setConfigDraft(value => value ? { ...value, password: '' } : null); const hide = () => { if (document.visibilityState !== 'visible') clear(); }; document.addEventListener('visibilitychange', hide); window.addEventListener('pagehide', clear); return () => { document.removeEventListener('visibilitychange', hide); window.removeEventListener('pagehide', clear); }; }, []);
  useEffect(() => { registerTransientBack(data.passwordPromptId ? () => { void run('archives.password.cancel', { promptId: data.passwordPromptId }).catch(() => {}); return true; } : configDraft ? () => { setConfigDraft(null); return true; } : discard ? () => { setDiscard(null); return true; } : hasReview ? () => { if (!busy) cancelReview(); return true; } : null); return () => registerTransientBack(null); }, [data.passwordPromptId, configDraft, discard, review.actionId, uploadReview.actionId, busy, registerTransientBack]);
  const extra = object(object(data.inventory).extraSectionsAvailable), hasInventory = Boolean(data.inventory);
  const sectionReady = (key: string) => hasInventory && (['projects', 'artifacts'].includes(key) || extra[key] === true);
  const visibleSections = data.projectMemoryName ? ['projects', 'memory'] : selected.filter(sectionReady);
  return <>
    <Card title="备份与迁移" description="生成固定备份、加密导出，或逐分区恢复到新的身份。">
      {data.projectMemoryName ? <p>范围：{text(data.projectMemoryName)}及其记忆。</p> : <div className="pw-selection-list">{Object.entries(ARCHIVE_SECTIONS).map(([key, label]) => <CheckboxField key={key} isSelected={selected.includes(key)} isDisabled={locked || phasePending || !sectionReady(key)} onChange={yes => setSelected(value => yes ? [...value, key] : value.filter(item => item !== key))}>{label}{hasInventory && !sectionReady(key) ? ' · 当前不可用' : ''}</CheckboxField>)}</div>}
      <div className="pw-form-actions">{act('archives.create', { sections: visibleSections }, '生成所选范围的备份', locked || phasePending || visibleSections.length === 0 || Boolean(data.projectMemoryName && !data.projectMemoryCurrent), true)}{act('archives.chooseImport', { encrypted: true }, '选择加密备份…', locked || phasePending)}{act('archives.refresh', {}, '刷新清单', locked)}</div>
      <Accordion><AccordionItem title="包含范围与旧版 ZIP">
        <p>备份中的用户文本、图片和项目文件可能包含敏感内容。本机审阅缓存未加密；默认导出为口令加密文件。</p>
        <p>模型连接、工作配置、技能和排程可分别备份；恢复后保持停用并重新核对来源。登录凭据、密钥、SSH 信任、执行授权、设备配对、插件安装、运行环境和未保存草稿不会迁移。</p>
        <div className="pw-form-actions">{act('archives.chooseImport', { encrypted: false }, '只读核对旧版 ZIP…', locked || phasePending)}{selection.selectionId && act('archives.offerExport', { ...pin, encrypted: false }, '导出未加密 ZIP…', locked)}</div>
      </AccordionItem></Accordion>
    </Card>
    {selection.selectionId && <Card title="当前固定备份" description="核对实际内容后选择导出或恢复分区。">
      <Facts items={[[ '备份大小', bytesText(selection.archiveBytes) ], ['本机缓存', '未加密'], ['创建时间', timeText(selection.createdAt) ]]} />
      <div className="pw-form-actions">{act('archives.offerExport', { ...pin, encrypted: true }, '设置口令并导出…', locked, true)}<Button variant="secondary" disabled={locked} onClick={() => setDiscard({ ...pin })}>移除本机缓存…</Button></div>
      {Object.entries(preview).map(([key, value]) => { const part = object(value); return <SourceRecord key={key} item={{ title: ARCHIVE_SECTIONS[key] || key }}><Facts items={[[ '项目', part.projects ], ['私人副本', part.checkouts ], ['原件', part.attachments ], ['成果', part.artifacts ], ['配置', Array.isArray(part.profiles) ? part.profiles.length : undefined ], ['快照时间', timeText(part.snapshotAt) ]]} /><ArchiveConfigurationDetails value={part} /><div className="pw-form-actions">{act('archives.reviewPhase', { ...pin, phase: key }, '审阅恢复此分区', locked || phasePending)}{key === 'experiences' && act('archives.experiences', { ...pin, page: 0 }, '查看只读经历', locked)}</div></SourceRecord>; })}
    </Card>}
    <Card title="加密 WebDAV 备份" description="每次上传一个新版本，发送前核对固定备份和目标目录。">
      <div className="pw-form-actions">{act('archives.webdav.load', {}, '读取连接设置', locked)}{data.webDavConfig && <Button variant="secondary" disabled={locked} onClick={() => setConfigDraft({ revision: config.revision, collection: text(config.collection), username: text(config.username), enabled: config.enabled === true, password: '' })}>编辑连接</Button>}</div>
      {data.webDavConfig && <><Facts items={[[ '目录', config.collection ], ['状态', config.enabled ? '已启用，逐次确认上传' : '未启用'], ['专用口令', config.credentialConfigured ? '已保存' : '未配置' ]]} /><div className="pw-form-actions">{selection.selectionId && act('archives.webdav.offerUpload', { ...pin, revision: config.revision }, '为当前备份设置口令并准备上传…', locked || !config.enabled)}{act('archives.webdav.list', { revision: config.revision }, '读取远端版本', locked || !config.enabled)}</div></>}
      {Array.isArray(data.webDavVersions) && (data.webDavVersions.length ? list(data.webDavVersions).map(version => <SourceRecord key={text(version.name)} item={version}><Facts items={[[ '加密后大小', bytesText(version.encryptedBytes) ], ['修改时间', version.modified ]]} />{act('archives.webdav.offerImport', { revision: config.revision, name: version.name }, '下载、解密并核对…', locked || phasePending || !config.enabled)}</SourceRecord>) : <Empty>这个目录尚未返回可读取的备份版本。</Empty>)}
      {data.webDavAction && <div className="pw-selected-object"><h3>原上传结果</h3><Facts items={[[ '状态', statusText(object(upload.receipt).status || upload.status) ], ['远端字节', object(upload.receipt).remoteBytesVerified ? '已核对与本次密文一致' : '尚未确认一致' ]]} /><div className="pw-form-actions">{act('archives.webdav.query', { actionId: data.webDavAction }, '只读核对原版本')}<CapabilityAction action="archives.webdav.stop" args={{ actionId: data.webDavAction }} supported={supported} run={run}>请求停止原上传</CapabilityAction></div><p className="pw-field-hint">结果未知时不会自动重传。已发送的字节不会因停止而撤回。</p></div>}
      <Accordion><AccordionItem title="按原编号或文件名核对">
        <TextField label="当前目录中的备份文件名" value={fileName} onChange={value => setFileName(value.slice(0, 100))} /><div className="pw-form-actions">{act('archives.webdav.offerImport', { revision: config.revision, name: fileName }, '下载并只读核对…', locked || phasePending || !config.enabled || !fileName)}</div>
        <TextField label="已有上传编号" value={uploadId} onChange={value => setUploadId(value.slice(0, 36))} /><div className="pw-form-actions">{act('archives.webdav.readOriginal', { actionId: uploadId }, '读取原上传登记', !/^[a-f0-9-]{36}$/.test(uploadId))}</div>
      </AccordionItem></Accordion>
    </Card>
    {data.experiencePage && <Card title="归档中的只读经历" description="这是固定时间的快照，不恢复旧授权，也不证明之后的状态。"><Facts items={[[ '快照时间', timeText(data.experiencePage.snapshotAt) ], ['页码', text(data.experiencePage.pageIndex + 1) + ' / ' + text(data.experiencePage.pageCount) ]]} /><p>{first(object(data.experiencePage.data), ['title', 'goal', 'text', 'summary'])}</p><Accordion><AccordionItem title="查看这页完整原记录"><pre className="pw-source-code">{JSON.stringify(object(data.experiencePage.data), null, 2)}</pre></AccordionItem></Accordion><div className="pw-form-actions">{act('archives.experiences', { ...pin, page: data.experiencePage.pageIndex - 1 }, '上一页', data.experiencePage.pageIndex <= 0)}{act('archives.experiences', { ...pin, page: data.experiencePage.pageIndex + 1 }, '下一页', !data.experiencePage.hasMore)}</div></Card>}
    <Card title="恢复结果与原动作">
      {data.originalAction && <><Facts items={[[ '状态', statusText(original.status || 'unknown') ], ['原动作', data.originalAction ]]} /><div className="pw-form-actions">{act('archives.query', { actionId: data.originalAction }, '只读核对原恢复结果')}{['running', 'queued', 'stopping'].includes(original.status) && act('archives.stop', { actionId: data.originalAction }, '请求停止后续恢复')}</div></>}
      {Object.keys(nativeResult).length > 0 && <><ArchiveConfigurationDetails value={nativeResult} result />{['settings', 'skills', 'schedules'].includes(object(original.scope).phase) && <p>恢复写入与启用分开核对：连接和排程保持停用，技能须重新批准，工作配置须重选本机来源；当前模型选择保留。</p>}<div className="pw-form-actions">{object(original.scope).phase === 'settings' && <><Button variant="secondary" onClick={() => navigate('models')}>管理模型连接</Button><Button variant="secondary" onClick={() => navigate('workProfiles')}>核对工作配置</Button></>}{object(original.scope).phase === 'skills' && <Button variant="secondary" onClick={() => navigate('skills')}>审核恢复的技能</Button>}{object(original.scope).phase === 'schedules' && <Button variant="secondary" onClick={() => navigate('automation')}>核对恢复的排程</Button>}</div></>}
      {list(object(data.result).checkouts).filter(row => row.found).map(row => <div className="pw-form-actions" key={text(row.checkoutId)}>{act('archives.openCheckout', { actionId: data.originalAction, id: row.checkoutId }, '打开恢复项目：' + text(row.name, '项目'))}</div>)}
      {list(object(data.result).attachments).filter(row => row.id).map(row => <div className="pw-form-actions" key={text(row.id)}>{act('archives.openAttachment', { actionId: data.originalAction, id: row.id }, '查看原件：' + text(row.name, '恢复的文件'))}</div>)}
      <Accordion><AccordionItem title="按已有恢复编号读取"><TextField label="原恢复任务编号" value={originalId} onChange={value => setOriginalId(value.slice(0, 36))} /><div className="pw-form-actions">{act('archives.readOriginal', { actionId: originalId }, '读取原恢复定位', !/^[a-f0-9-]{36}$/.test(originalId))}</div></AccordionItem></Accordion>
      {data.notice && <p className="pw-receipt-copy" role="status">{text(data.notice)}</p>}{data.error && <p className="pw-field-error" role="alert">{text(data.error)}</p>}
    </Card>
    {data.passwordPromptId && <ArchivePassword key={data.passwordPromptId} data={data} supported={supported} run={run} />}
    <SettingsDialog isOpen={hasReview} title={data.review ? '确认恢复这个分区？' : '确认上传这个新版本？'} onClose={() => { if (!busy) cancelReview(); }}>
      {data.review ? <><Facts items={[[ '分区', ARCHIVE_SECTIONS[review.phase] || review.phase ]]} /><p>仅从这份固定备份创建新数据。现有项目保留；原登录、信任和执行授权不会恢复。</p><ArchiveConfigurationDetails value={nativeScope} />{['settings', 'skills', 'schedules'].includes(review.phase) && <p>连接和排程恢复为停用状态，技能需要重新批准，工作配置需要重新绑定本机来源。恢复不会切换当前模型或自动开始任务。</p>}</> : <><Facts items={[[ '目标', uploadReview.destination ], ['加密后大小', bytesText(uploadReview.encryptedBytes) ], ['分区', Object.keys(object(uploadReview.sections)).map(key => ARCHIVE_SECTIONS[key] || key).join('、') ]]} /><p>仅发送这份密文到上述目录；不会自动重传或覆盖已有版本。</p></>}
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
  </>;
}

function StorageWorkspace({ data, supported, run, registerTransientBack, navigate }: DataPageProps) {
  const [showProtected, setShowProtected] = useState(false), [confirmed, setConfirmed] = useState(false), [originalId, setOriginalId] = useState('');
  const [policyDraft, setPolicyDraft] = useState<Data | null>(null);
  const inventory = object(data.inventory), policy = object(inventory.retentionPolicy), review = object(data.review), original = object(data.original), result = object(data.result);
  const selected: string[] = Array.isArray(data.selected) ? data.selected : [], busy = Boolean(data.busy), locked = busy || Boolean(data.review);
  const rows = list(inventory.groups).filter(row => showProtected || row.cleanable && object(row.usage).logicalBytes !== 0 || object(row.usage).complete !== true);
  const act = (action: string, args: Data, label: string, disabled = false, primary = false) => <CapabilityAction action={action} args={args} supported={supported} run={run} disabled={busy || disabled} primary={primary}>{label}</CapabilityAction>;
  const cancel = () => { void run('storage.cancelReview', { actionId: review.actionId }).catch(() => {}); };
  useEffect(() => setConfirmed(false), [review.actionId]);
  useEffect(() => { registerTransientBack(policyDraft ? () => { setPolicyDraft(null); return true; } : data.review ? () => { if (!busy) cancel(); return true; } : null); return () => registerTransientBack(null); }, [policyDraft, review.actionId, busy, registerTransientBack]);
  return <>
    <Card title="项目存储" description="按实际目录计量，先核对所选范围，再确认清理。">
      <Facts items={[[ '当前文件系统可用', bytesText(inventory.filesystemUsableBytes) ]]} />
      <div className="pw-form-actions">{act('storage.refresh', {}, '重新计量', locked)}{act('storage.suggest', {}, '按保留规则建议勾选', locked)}{data.inventory && <Button variant="secondary" disabled={locked} onClick={() => setPolicyDraft({ revision: policy.revision, generations: policy.keepOldRuntimeGenerations, backups: policy.keepRecentBackupCopies, generated: policy.suggestGeneratedCleanup === true })}>编辑保留建议</Button>}</div>
      <CheckboxField isSelected={showProtected} onChange={setShowProtected}>显示受保护或空的范围</CheckboxField>
      {!data.inventory ? <Empty>尚未读取实际目录占用。</Empty> : rows.map(row => { const usage = object(row.usage), cleanable = row.cleanable && usage.complete === true; return <div key={text(row.id)} className="pw-selection-object"><CheckboxField isSelected={selected.includes(row.id)} isDisabled={!cleanable || locked} onChange={yes => { void run('storage.select', { id: row.id, selected: yes }).catch(() => {}); }}>{text(row.label || row.id)}</CheckboxField><Facts items={[[ '逻辑大小', usage.complete === true ? bytesText(usage.logicalBytes) : '未完整读取，不能据此清理' ], ['文件', usage.complete === true ? usage.files : undefined ], ['范围', cleanable ? '可供审阅' : '保留' ]]} />{row.note && <p className="pw-field-hint">{text(row.note)}</p>}</div>; })}
      <div className="pw-form-actions">{act('storage.reviewSelected', { selection: selected }, '核对清理影响', locked || selected.length === 0, true)}</div>
      <Accordion><AccordionItem title="计量和保留规则说明"><p>分组可能重叠，不能把逻辑大小直接相加为可回收空间。清理会重新核对原运行和固定目录；当前运行环境、原项目、用户 HOME 和固定成果保留。</p><p>规则只生成建议，不会定时或自动删除。中断后的原动作只能核对，已删除缓存不能回滚。</p></AccordionItem></Accordion>
    </Card>
    {list(data.caches).length > 0 && <Card title="本机备份缓存" description="逐份核对或导出后，再从原备份页决定是否移除。">{list(data.caches).map(row => <div key={text(row.selectionId)} className="pw-form-actions"><CapabilityAction action="storage.backupCache" args={{ selectionId: row.selectionId, sha256: row.archiveSha256 }} supported={supported} run={async (action, args) => { const result = await run(action, args); if (result?.navigate === 'archives') navigate('archives'); return result; }} disabled={locked}>核对备份 · {bytesText(row.archiveBytes)}</CapabilityAction></div>)}</Card>}
    <Card title="原清理结果">
      {data.originalAction && <><Facts items={[[ '原动作', data.originalAction ], ['状态', statusText(original.status || 'unknown') ], ['结果', result.message || result.notice ]]} /><div className="pw-form-actions">{act('storage.query', { actionId: data.originalAction }, '只读核对原结果')}{['running', 'queued', 'stopping'].includes(original.status) && act('storage.stop', { actionId: data.originalAction }, '请求停止后续删除')}</div></>}
      <Accordion><AccordionItem title="按原编号恢复查看"><TextField label="原清理任务编号" value={originalId} onChange={value => setOriginalId(value.slice(0, 36))} /><div className="pw-form-actions">{act('storage.readOriginal', { actionId: originalId }, '读取原清理记录', !/^[a-f0-9-]{36}$/.test(originalId))}</div></AccordionItem></Accordion>
      {data.notice && <p className="pw-receipt-copy" role="status">{text(data.notice)}</p>}{data.error && <p className="pw-field-error" role="alert">{text(data.error)}</p>}
    </Card>
    <Card title="本机诊断元数据" description="先预览固定内容，再选择是否导出；不会自动上传。">
      <div className="pw-form-actions">{act('storage.diagnostics.read', {}, '读取只读诊断快照', locked || data.diagnosticPickerPending)}</div>
      {data.diagnosticText && <><pre className="pw-source-code">{text(data.diagnosticText)}</pre><Facts items={[[ '大小', bytesText(data.diagnosticBytes) ]]} /><CheckboxField isSelected={Boolean(data.diagnosticReviewed)} isDisabled={busy || data.diagnosticPickerPending || data.diagnosticExportEntered} onChange={accepted => { void run('storage.diagnostics.review', { sha256: data.diagnosticSha, accepted }).catch(() => {}); }}>我已核对这份内容，允许写入自己选择的文档</CheckboxField><div className="pw-form-actions">{act('storage.diagnostics.export', { sha256: data.diagnosticSha }, '选择位置并导出…', !data.diagnosticReviewed || data.diagnosticPickerPending || data.diagnosticExportEntered)}</div></>}
    </Card>
    <SettingsDialog isOpen={Boolean(data.review)} title="仅清理这些固定范围？" onClose={() => { if (!busy) cancel(); }}><p>依赖可能需要重新安装，未保存的生成内容会丢失。原项目和固定成果保留；已经删除的缓存不会回滚。</p>{list(review.targets).map(target => <SourceRecord key={text(target.id)} item={{ title: list(inventory.groups).find(row => row.id === target.id)?.label || target.id }}><Facts items={[[ '逻辑大小', bytesText(target.logicalBytes) ]]} /></SourceRecord>)}<CheckboxField isSelected={confirmed} isDisabled={busy} onChange={setConfirmed}>我已核对所选范围，接受这些缓存或未固定输出被移除</CheckboxField><div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={cancel}>保留</Button>{act('storage.confirm', { actionId: review.actionId }, '仅清理这些范围', !confirmed, true)}</div></SettingsDialog>
    <SettingsDialog isOpen={policyDraft !== null} title="保留建议" onClose={() => setPolicyDraft(null)}>{policyDraft && <><div className="pw-form-grid"><SelectField label="建议保留旧环境" value={text(policyDraft.generations)} options={[0, 1, 2, 3, 4, 5].map(n => ({ id: String(n), label: n + ' 版' }))} onChange={value => setPolicyDraft(current => current && { ...current, generations: Number(value) })} /><SelectField label="建议保留本机备份" value={text(policyDraft.backups)} options={[0, 1, 2, 3, 4, 5].map(n => ({ id: String(n), label: n + ' 份' }))} onChange={value => setPolicyDraft(current => current && { ...current, backups: Number(value) })} /><CheckboxField isSelected={policyDraft.generated} onChange={value => setPolicyDraft(current => current && { ...current, generated: value })}>建议清理未固定的生成目录</CheckboxField><p>仅保存建议，不启用自动删除。</p>{policyDraft.revision !== policy.revision && <p role="alert" className="pw-field-error">原保留规则已变化，请重新打开。</p>}</div><div className="pw-form-actions"><Button variant="secondary" onClick={() => setPolicyDraft(null)}>取消</Button><CapabilityAction action="storage.savePolicy" args={policyDraft} supported={supported} run={async (action, args) => { setPolicyDraft(null); return run(action, args); }} disabled={busy || policyDraft.revision !== policy.revision} primary>保存建议规则</CapabilityAction></div></>}</SettingsDialog>
  </>;
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
  { id: 'models', title: '模型与连接', detail: '当前模型、API 与 ChatGPT 账号', icon: RiKey2Line, entries: ['model-settings', 'accounts', 'workProfiles'] },
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

export default function Secondary({ route, onBack, snapshot, registerBackHandler, navigationTrigger, navigationRevision, onNavigationPageChange }: Props) {
  const state: Data = { ...object(snapshot.state), webWorkspaceResumeRevision: Number(snapshot.view?.resumeRevision || 0), webWorkspaceTaskOpen: !!snapshot.view?.taskId };
  const commands = snapshot.capabilities?.commands || [];
  const supported = (action: string) => commands.includes(action);
  const normalize = (next: string) => (next || 'settings').replace(/^connections[:/]/, '').replace(/^settings[:/]/, '');
  const [page, setPage] = useState(normalize(route));
  const [candidateTarget, setCandidateTarget] = useState<LearningCandidateTarget | null>(null);
  const [modelView, setModelView] = useState<ModelView>({ kind: 'list' });
  const modelTrail = useRef<ModelView[]>([]);
  const modelDrafts = useRef<Partial<Record<string, ModelDraft>>>({});
  const transientBack = useRef<(() => boolean) | null>(null);
  const registerTransientBack = useCallback<BackHandlerRegistration>(handler => { transientBack.current = handler; }, []);
  const [query, setQuery] = useState(''), [notice, setNotice] = useState(''), [error, setError] = useState('');
  const [expandedSettings, setExpandedSettings] = useState<Set<string>>(new Set());
  const trail = useRef<Array<{ page: string; query: string; modelView: ModelView; modelTrail: ModelView[]; candidateTarget: LearningCandidateTarget | null }>>([]), scroll = useRef<HTMLDivElement>(null), scrollPositions = useRef(new Map<string, number>());
  const mounted = useRef(true), generation = useRef(0);
  useEffect(() => { generation.current++; trail.current = []; modelTrail.current = []; setPage(normalize(route)); setCandidateTarget(null); setModelView({ kind: 'list' }); setNotice(''); setError(''); }, [route, navigationRevision]);
  useEffect(() => { onNavigationPageChange?.(page); }, [page, onNavigationPageChange]);
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
  const navigate = (next: string, candidate: LearningCandidateTarget | null = null) => { rememberScroll(); generation.current++; trail.current.push({ page, query, modelView, modelTrail: [...modelTrail.current], candidateTarget }); modelTrail.current = []; setNotice(''); setError(''); setQuery(''); setModelView({ kind: 'list' }); setCandidateTarget(candidate); setPage(next); };
  const openModel = (view: ModelView) => { rememberScroll(); generation.current++; modelTrail.current.push(modelView); setNotice(''); setError(''); setModelView(view); };
  const isModelPage = page === 'models' || page === 'model-settings';
  const domain = SECONDARY_DOMAINS.find(x => x.id === page);
  const hostSection = isModelPage ? 'models' : domain?.id;
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
  const dataPage = isModelPage ? 'models' : page;
  const feature = object(object(state.features)[dataPage === 'commitments' ? 'automation' : dataPage]);
  const owner = object(object(state.ownerSnapshots)[dataPage]);
  const specialized = isModelPage ? object(state.modelConnections) : page === 'devices' ? object(state.deviceWorkspace) : {};
  // MemoryModule.snapshot is summary-only. Its actual list reply lives in
  // moduleResults; an in-flight or failed reread must not present an old list.
  const memoryListReady = !object(state.moduleErrors)['memory.list'] && !(Array.isArray(state.moduleBusy) && state.moduleBusy.includes('memory.list'));
  const memoryList = memoryListReady ? object(object(state.moduleResults)['memory.list']) : {};
  const data: Data = { ...feature, ...owner, ...specialized, ...(page === 'memory' ? { records: memoryList.records } : {}) };
  const domainError = text(object(state.ownerErrors)[dataPage]) || Object.entries(object(state.moduleErrors)).filter(([key]) => key.startsWith(dataPage + '.')).map(([, value]) => text(value)).filter(Boolean).join('\n');
  const settingsGroup = SETTINGS_GROUPS.find(group => page === 'group:' + group.id);
  const connection = modelConnectionRows(data).find(row => recordId(row) === modelView.id);
  const editConnection = modelView.id ? connection : modelConnectionRows(data).find(row => recordId(row) === text(object(data.connections).selectedConnectionId));
  const modelDraftKey = modelView.kind + ':' + (modelView.kind === 'add' ? 'new' : recordId(editConnection || {}));
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
    if (previous) { generation.current++; setNotice(''); setError(''); setPage(previous.page); setCandidateTarget(previous.candidateTarget); setQuery(previous.query); modelTrail.current = previous.modelTrail; setModelView(previous.modelView); return true; }
    if (page === normalize(route)) return false;
    if (page === 'settings' || page === 'connections') return false;
    generation.current++; setCandidateTarget(null); setPage(page === 'background' ? 'phone' : 'settings'); setModelView({ kind: 'list' }); return true;
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
  const settingRows = (entries: Array<{ id: string; title: string; detail: string; icon: typeof RiKey2Line }>, showCurrentModel = false) => <div className="pw-settings-links">{entries.map(item => <Button key={item.id} variant="ghost" leadingIcon={item.icon} trailingIcon={RiArrowRightSLine} className="pw-domain-link h-auto whitespace-normal" onClick={() => navigate(item.id)}><span><span>{item.title}</span>{showCurrentModel && item.id === 'model-settings' && <small>{text(state.modelName) || '尚未连接'}</small>}</span></Button>)}</div>;
  const refreshAction = page === 'background' ? 'background.refresh' : isModelPage ? 'models.snapshot' : page === 'memory' ? 'memory.list' : 'app.refresh';
  return <section className="pw-secondary pw-secondary-refactor" data-secondary-page={page} aria-label={pageTitle}>
    <header className="pw-secondary-header"><IconButton icon={RiArrowLeftLine} aria-label={isModelPage && modelView.kind !== 'list' ? modelBackLabel : '返回'} onClick={back} /><div><h1>{pageTitle}</h1></div>{!['memory', 'memoryReview', 'teams'].includes(page) && <IconButton icon={RiRefreshLine} aria-label="刷新当前状态" title="刷新当前状态" disabled={!supported(refreshAction)} onClick={() => { void run(refreshAction, {}).catch(() => {}); }} />}{navigationTrigger}</header>
    <div className="pw-secondary-scroll" ref={scroll}>
      {notice && <p className="pw-request-notice" role="status">{notice}</p>}
      {(error || domainError) && <p className="pw-field-error" role="alert">{error || domainError}</p>}
      <div className="pw-secondary-page" data-secondary-view={viewKey}>
        {(page === 'settings' || page === 'connections') ? <>
          <SearchField className="pw-secondary-search" data-input-size="medium" aria-label="搜索设置" placeholder="搜索设置" value={query} onChange={setQuery} shape="rounded" />
          {!query.trim() && <ExecutionControls state={state} supported={supported} run={run} />}
          {query.trim() ? <section className="pw-settings-results" aria-label="匹配的设置">{settingRows(filtered)}{filtered.length === 0 && <Empty>没有匹配的设置。</Empty>}</section>
            : <><section className="pw-settings-frequent" aria-label="常用设置">{settingRows(frequentSettings, true)}</section><Accordion variant="divided" className="pw-settings-groups" aria-label="按目的浏览设置" expandedKeys={expandedSettings} onExpandedChange={values => setExpandedSettings(new Set([...values].map(String)))} allowsMultipleExpanded>{SETTINGS_GROUPS.map(group => <AccordionItem id={group.id} key={group.id} title={group.title} icon={group.icon} headingLevel={2} className="pw-settings-group">{settingRows(group.entries.map(id => settingsEntries.find(item => item.id === id)!).filter(Boolean))}</AccordionItem>)}</Accordion></>}
        </> : settingsGroup ? <section className="pw-settings-group-page">{settingRows(settingsGroup.entries.map(id => settingsEntries.find(item => item.id === id)!).filter(Boolean))}</section>
          : isModelPage ? <>
            <div className="pw-model-list" data-model-layer={modelView.kind === 'model' ? 'model' : 'list'} hidden={!modelBrowseActive}>
              {modelView.kind !== 'model' && <header className="pw-model-current"><div><span className="pw-model-eyebrow">当前模型</span><strong>{text(state.modelName) || '尚未连接'}</strong><span className="pw-model-origin">{state.modelRoute === 'account' ? 'ChatGPT 账号' : MODEL_PROVIDERS[text(state.modelProvider)] || 'API 连接'}</span></div><IconButton className="pw-utility-icon" icon={RiSettings3Line} aria-label="管理连接" title="管理连接" onClick={() => openModel({ kind: 'connections' })} /></header>}
              {modelView.kind !== 'model' && state.modelRoute === 'account' && <div className="pw-account-model-entry"><Button variant="secondary" trailingIcon={RiArrowRightSLine} onClick={() => navigate('accounts')}>打开账号模型</Button><p className="pw-field-hint">下面是保留的 API 模型目录。</p></div>}
              <ModelCatalog key={page} state={state} data={data} active={modelBrowseActive} selectedId={modelView.kind === 'model' ? modelView.id : undefined} supported={supported} run={run} open={openModel} registerTransientBack={registerTransientBack} />
            </div>
            {modelView.kind === 'connections' ? <><ModelConnections state={state} data={data} supported={supported} run={run} navigate={navigate} open={openModel} /><TokenBudgetsWorkspace supported={supported} registerTransientBack={registerTransientBack} /></>
              : modelView.kind === 'edit' || modelView.kind === 'add' ? <ModelSettings key={modelDraftKey} state={state} connection={editConnection} supported={supported} run={run} adding={modelView.kind === 'add'} draft={modelDrafts.current[modelDraftKey]} onDraftChange={value => { if (value) modelDrafts.current[modelDraftKey] = value; else delete modelDrafts.current[modelDraftKey]; }} onInspect={() => { void run('models.snapshot', {}).catch(() => {}); setModelView(modelView.kind === 'add' ? { kind: 'connections' } : { kind: 'connection', id: recordId(editConnection || {}) }); }} onSaved={saved => { const id = modelView.kind === 'add' ? text(object(saved.connections).selectedConnectionId) : recordId(editConnection || {}); setModelView(id ? { kind: 'connection', id } : { kind: 'connections' }); }} registerTransientBack={registerTransientBack} />
              : modelView.kind === 'connection' ? <ModelConnectionDetail connection={connection} supported={supported} run={run} open={openModel} busy={Boolean(state.modelSelectionBusy || state.modelSaving || state.modelTesting || state.chatSubmitting || state.phoneSubmitting)} /> : null}
          </>
          : page === 'phone' ? <PhoneSettings state={state} supported={supported} run={run} navigate={navigate} />
          : page === 'background' ? <BackgroundSettings state={state} supported={supported} run={run} navigate={navigate} />
          : page === 'history' || page === 'usage' ? <HistoryPage key={page} usage={page === 'usage'} supported={supported} run={run} />
          : page === 'archives' || page === 'storage' ? <DataManagement key={page} kind={page} data={object(page === 'archives' ? state.ownerArchives : state.projectStorage)} supported={supported} run={run} registerTransientBack={registerTransientBack} navigate={navigate} />
          : domain ? <DomainPage key={page} domain={page} state={state} data={data} supported={supported} run={run} registerTransientBack={registerTransientBack} candidateTarget={candidateTarget} onReviewCandidate={target => navigate('memoryReview', target)} onOpenModels={() => navigate('model-settings')} onNavigate={navigate} alreadyShownError={error || domainError} />
          : <Empty>此入口未找到，可返回设置。</Empty>}
      </div>
    </div>
  </section>;
}
