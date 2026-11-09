import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { ChevronRight, KeyRound, Plus, RefreshCw, Server, Settings2, UserRound } from 'lucide-react';
import { Accordion, AccordionItem, Button, SearchField, SettingsDialog, StretchSwitch, TextField } from './forms-library';
import { Tabs, Tab, TabList, TabPanel } from './shell-library';
import { AIModelPicker } from './AIModelPicker';
import { AIModelGroups } from './AIModelGroups';
import { accountStatusText } from './account-actions';
import { aiCatalogCurrent, aiConnections, aiCredentialStatus, aiObject as object, aiRows as rows, aiServiceBrand, aiText as text } from './ai-center-data';
import './ai-center.css';

type Data = Record<string, any>;
type Run = (action: string, args?: Data) => Promise<any>;
type Back = (handler: (() => boolean) | null) => void;
export type AIServicePreset = { name: string; provider: string; url: string };
type View = { kind: 'main' | 'add' | 'openai' | 'connection' | 'edit' | 'account' | 'tools'; id?: string; preset?: AIServicePreset; adding?: boolean };
type Props = {
  state: Data; data: Data; supported: (action: string) => boolean; run: Run; registerTransientBack: Back;
  navigate: (route: string) => void; onHostSectionChange: (section: 'models' | 'accounts') => void;
  renderEditor: (connection: Data | undefined, preset: AIServicePreset | undefined, onClose: () => void, onSaved: (value: Data) => void, registerBack: Back) => ReactNode;
  renderAccounts: (accountId: string | undefined, adding: boolean, registerBack: Back) => ReactNode;
  renderTools: (registerBack: Back) => ReactNode;
};
const presets: AIServicePreset[] = [
  { name: 'OpenAI API', provider: 'openai_responses', url: 'https://api.openai.com/v1' },
  { name: 'Anthropic API', provider: 'anthropic', url: 'https://api.anthropic.com/v1' },
  { name: 'Gemini API', provider: 'gemini', url: 'https://generativelanguage.googleapis.com/v1beta' },
  { name: '自定义服务', provider: 'openai_chat', url: '' },
  { name: 'AWS Bedrock', provider: 'bedrock_anthropic', url: '' },
];

const factLabels: Record<string, string> = { CONTEXT_WINDOW: '上下文容量', OUTPUT_TOKENS: '目录输出上限', IMAGE_INPUT: '图片输入', TOOL_CALLS: '工具调用' };
/** Product composition of the native connection, account and catalog owners. */
export function AICenter({ state, data, supported, run, registerTransientBack, navigate, onHostSectionChange, renderEditor, renderAccounts, renderTools }: Props) {
  const [tab, setTab] = useState('services'), [view, setView] = useState<View>({ kind: 'main' });
  const [accountSnapshot, setAccountSnapshot] = useState<Data>({}), [localSnapshot, setLocalSnapshot] = useState<Data | null>(null);
  const [catalog, setCatalog] = useState<Data | null>(null), [query, setQuery] = useState('');
  const [modelReview, setModelReview] = useState<Data | null>(null), [selectionUnknown, setSelectionUnknown] = useState(false);
  const [reviewOutput, setReviewOutput] = useState(''), [reviewImageReserve, setReviewImageReserve] = useState('0');
  const [serviceChange, setServiceChange] = useState<Data | null>(null), [serviceUnknown, setServiceUnknown] = useState(false);
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState('');
  const childBack = useRef<(() => boolean) | null>(null), trail = useRef<View[]>([]), alive = useRef(true), generation = useRef(0), pending = useRef(false);
  const registerChildBack = useCallback<Back>(handler => { childBack.current = handler; }, []);
  const latest = useRef({ run, supported }); latest.current = { run, supported };
  const live = localSnapshot || data, connections = aiConnections(live), accounts = rows(accountSnapshot.accounts);
  const connection = connections.find(row => row.id === view.id);
  useEffect(() => { setLocalSnapshot(null); }, [data.connections]);
  const loadAccounts = async () => {
    if (!latest.current.supported('models.accounts.open')) return;
    const request = generation.current;
    let id = '';
    try {
      const value = await latest.current.run('models.accounts.open'); id = text(value?.windowId);
      if (alive.current && request === generation.current) setAccountSnapshot(object(value?.accountSnapshot));
    } catch (failure) { if (alive.current && request === generation.current) setError(failure instanceof Error ? failure.message : '账号状态暂不可读取。'); }
    finally { if (id) void latest.current.run('models.accounts.close', { windowId: id }).catch(() => {}); }
  };
  useEffect(() => { alive.current = true; return () => { alive.current = false; generation.current++; }; }, []);
  useEffect(() => { onHostSectionChange(view.kind === 'account' ? 'accounts' : 'models'); }, [view.kind, onHostSectionChange]);
  useEffect(() => { if (view.kind === 'main' && tab === 'services') void loadAccounts(); }, [view.kind, tab, state.modelSelectionRevision]);
  const open = (next: View) => { if (pending.current) return; generation.current++; trail.current.push(view); setError(''); setNotice(''); setCatalog(null); setModelReview(null); setView(next); };
  const back = () => {
    if (childBack.current?.()) return true;
    if (pending.current) return true;
    if (serviceChange) { setServiceChange(null); return true; }
    if (modelReview) { setModelReview(null); return true; }
    if (view.kind === 'main') return false;
    generation.current++; setCatalog(null); setError(''); setView(trail.current.pop() || { kind: 'main' }); return true;
  };
  const latestBack = useRef(back); latestBack.current = back;
  useEffect(() => { registerTransientBack(() => latestBack.current()); return () => registerTransientBack(null); }, [registerTransientBack]);
  const request = async (action: string, args: Data = {}, accept?: (value: Data) => void) => {
    if (pending.current || !supported(action)) return;
    const current = ++generation.current; pending.current = true; setBusy(true); setError('');
    try { const value = object(await run(action, args)); if (alive.current && current === generation.current) accept?.(value); }
    catch (failure) { if (alive.current && current === generation.current) setError(failure instanceof Error ? failure.message : '操作结果待核对。'); }
    finally { if (current === generation.current) { pending.current = false; if (alive.current) setBusy(false); } }
  };
  const readCatalog = (refresh: boolean) => {
    if (!connection) return;
    const original = connection;
    void request(refresh ? 'models.catalog.refresh' : 'models.catalog.snapshot', { connectionId: original.id, expectedRevision: original.revision }, value => {
      if (!aiCatalogCurrent(value, original)) throw new Error('服务版本已变化，请返回重新读取。');
      setCatalog(value);
      setModelReview(null);
      if (refresh) setNotice(value.refreshAttempt?.status === 'failed' ? '目录更新失败，保留上一次成功读取的模型。' : '已收到目录读取回执。实际推理需单独测试。');
    });
  };
  const reviewModel = (modelId: string) => {
    if (!connection) return;
    const original = connection;
    void request('models.catalog.review', { connectionId: original.id, expectedRevision: original.revision, modelId }, value => {
      if (!text(value.reviewHandle) || value.selectionRevision !== state.modelSelectionRevision || !Number.isFinite(value.expiresInMs) || value.expiresInMs <= 0) throw new Error('模型核对记录已变化，请重新读取。');
      setModelReview({ ...value, requestedModelId: modelId, originalConnectionId: original.id, originalConnectionRevision: original.revision, expiresAt: Date.now() + value.expiresInMs });
      setReviewOutput(String(object(value.options).maxOutputTokens ?? '')); setReviewImageReserve(String(object(value.options).imageTokenReserve ?? 0));
    });
  };
  const selectReviewedModel = async () => {
    const original = modelReview;
    const strictSource = object(original?.connection).catalogEnabled === true;
    const output = Number(reviewOutput), imageReserve = strictSource ? Number(reviewImageReserve) : 0;
    if (!original || pending.current || selectionUnknown) return;
    if (!connection || connection.id !== original.originalConnectionId || connection.revision !== original.originalConnectionRevision || original.selectionRevision !== state.modelSelectionRevision || Date.now() >= original.expiresAt) { setError('这次模型核对已过期或服务已变化，请取消后重新选择。'); return; }
    if (!/^\d+$/.test(reviewOutput) || !Number.isSafeInteger(output) || output < 128 || output > 32768 || strictSource && !/^\d+$/.test(reviewImageReserve) || !Number.isSafeInteger(imageReserve) || imageReserve < 0 || imageReserve > 2_000_000) { setError('请核对输出上限和图片预留范围。'); return; }
    const current = ++generation.current; pending.current = true; setBusy(true); setError(''); setModelReview(null);
    try {
      const value = await run('models.catalog.select', { reviewHandle: original.reviewHandle, options: { maxOutputTokens: output, reasoning: object(original.options).reasoning, imageTokenReserve: imageReserve } });
      if (value?.status !== 'saved') throw new Error('默认模型的保存结果尚未确认。');
      if (alive.current && current === generation.current) setNotice('默认模型已保存，后续新任务将使用这项选择。');
    } catch (failure) { if (alive.current && current === generation.current) { setSelectionUnknown(true); setError(`${failure instanceof Error ? failure.message : '选择结果待核对。'} 请重新读取当前状态，没有自动重试。`); } }
    finally { if (current === generation.current) { pending.current = false; if (alive.current) setBusy(false); } }
  };
  const recoverSelection = () => void request('models.snapshot', {}, value => {
    const selection = object(value.nativeSelection);
    if (!text(selection.model) || !['api_key', 'account'].includes(selection.modelRoute)) throw new Error('当前默认模型尚不可核对，请重新读取。');
    setLocalSnapshot(value); setSelectionUnknown(false); setNotice(`当前默认模型：${selection.model}。已读取实际状态，请核对后再选择。`);
  });
  const changeServiceEnabled = () => {
    const original = serviceChange;
    if (!original || pending.current || serviceUnknown) return;
    setServiceChange(null); setServiceUnknown(true);
    void request('models.connections.setEnabled', { id: original.id, expectedRevision: original.revision, enabled: original.enabled }, value => {
      if (value.status !== 'saved' || object(value.savedConnection).id !== original.id) throw new Error('服务启停回执待核对，请读取当前状态。');
      setLocalSnapshot(object(value.snapshot)); setServiceUnknown(false); setNotice(original.enabled ? '服务已启用，模型可用性仍以实际目录和调用为准。' : '服务已停用，不再接收新的模型请求。已有任务仍保留原记录。');
    });
  };
  const recoverService = () => void request('models.snapshot', {}, value => {
    if (!Array.isArray(value.connections) && !Array.isArray(object(value.connections).connections) || !aiConnections(value).every(row => text(row.id) && Number.isSafeInteger(row.revision))) throw new Error('服务列表尚未完整读取。');
    setLocalSnapshot(value); setServiceUnknown(false); setNotice('已读取服务当前状态，请核对后再操作。');
  });
  useEffect(() => { if (view.kind === 'connection' && connection && supported('models.catalog.snapshot')) readCatalog(false); }, [view.kind, view.id]);
  const saved = (value: Data) => {
    const row = object(value.savedConnection);
    if (!text(row.id) || typeof row.revision !== 'number') { setError('保存回执缺少服务身份，请返回读取服务列表核对。'); return; }
    setLocalSnapshot(old => ({ ...(old || data), connections: { ...object((old || data).connections), connections: [...aiConnections(old || data).filter(item => item.id !== row.id), row] } }));
    trail.current = [{ kind: 'main' }]; setView({ kind: 'connection', id: row.id }); setCatalog(null); setNotice('服务已保存。可读取模型目录，再单独选择默认模型或测试推理。');
  };
  const filtered = connections.filter(row => `${row.name || ''} ${row.baseUrl || ''}`.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()));
  const filteredAccounts = accounts.filter(row => `${row.label || ''} ChatGPT OpenAI`.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()));
  const brands = [...new Set([...filtered.map(aiServiceBrand), ...(filteredAccounts.length ? ['OpenAI'] : [])])].sort((a, b) => a === 'OpenAI' ? -1 : b === 'OpenAI' ? 1 : a.localeCompare(b));
  const topLevel = view.kind === 'main';
  const accountName = accounts.find(row => row.id === view.id)?.label;
  return <section className="pw-ai-center" data-ai-view={view.kind}>
    {topLevel ? <Tabs selectedKey={tab} onSelectionChange={key => { setTab(String(key)); setError(''); setNotice(''); }} className="pw-ai-main-tabs">
      <div className="pw-ai-toolbar"><div className="pw-ai-tabs"><TabList aria-label="AI 接入管理"><Tab id="services">服务</Tab><Tab id="models">模型</Tab><Tab id="routing">调度</Tab></TabList></div><Button variant="ghost" leadingIcon={Server} className="pw-ai-proxy-link" onClick={() => navigate('proxy')}>本机 API</Button></div>
      <TabPanel id={tab} className="pw-ai-tab-panel">
      {tab === 'services' && <>
        <header className="pw-ai-section-heading"><h2>你的服务</h2><Button leadingIcon={Plus} disabled={!supported('models.connections.add') && !supported('accounts.beginLogin')} onClick={() => open({ kind: 'add' })}>添加服务</Button></header>
        {(connections.length + accounts.length > 5) && <SearchField aria-label="搜索服务或账号" placeholder="搜索服务或账号" value={query} onChange={setQuery} className="pw-ai-search" />}
        {brands.length ? <div className="pw-ai-service-list">{brands.map(brand => <section className="pw-ai-service-group" key={brand} aria-label={brand}><h3>{brand}</h3>{brand === 'OpenAI' && filteredAccounts.map(row => <Button key={`account:${row.id}`} variant="ghost" className="pw-ai-service-row" onClick={() => open({ kind: 'account', id: row.id })}><UserRound size={19} aria-hidden /><span className="pw-ai-row-copy"><strong>{row.label || 'ChatGPT 账号'}</strong><small>ChatGPT · {accountStatusText(row.status)}</small></span><ChevronRight size={17} aria-hidden /></Button>)}{filtered.filter(row => aiServiceBrand(row) === brand).map(row => <Button key={row.id} variant="ghost" className="pw-ai-service-row" onClick={() => open({ kind: 'connection', id: row.id })}><KeyRound size={19} aria-hidden /><span className="pw-ai-row-copy"><strong>{row.name || 'API 服务'}</strong><small>{aiCredentialStatus(row)}{row.model ? ` · ${row.model}` : ' · 待读取模型'}</small></span><ChevronRight size={17} aria-hidden /></Button>)}</section>)}</div>
          : <div className="pw-ai-empty"><KeyRound size={26} aria-hidden /><h3>{query ? '没有匹配的服务' : '连接你的第一个 AI 服务'}</h3><p>{query ? '试试服务或账号名称。' : '添加 API 密钥，或登录你的 ChatGPT 账号。'}</p>{!query && <Button onClick={() => open({ kind: 'add' })}>添加服务</Button>}</div>}
        <footer className="pw-ai-service-footer"><span>默认模型</span><Button variant="ghost" trailingIcon={ChevronRight} onClick={() => setTab('models')}>{text(state.modelName) || '尚未选择'}</Button></footer>
        <Button variant="ghost" className="pw-ai-related-link" trailingIcon={ChevronRight} onClick={() => open({ kind: 'tools' })}>用量与诊断</Button>
      </>}
      {tab === 'models' && <AIModelPicker state={state} can={supported} act={run} scope="default" onSelected={() => { setNotice('默认模型已保存，新对话将使用这项选择。'); setTab('services'); }} onSelectionPending={setBusy} />}
      {tab === 'routing' && <AIModelGroups state={state} data={live} supported={supported} run={run} registerTransientBack={registerChildBack} />}
      </TabPanel>
    </Tabs> : <>
      <header className="pw-ai-detail-heading"><h2>{view.kind === 'add' ? '添加服务' : view.kind === 'openai' ? '连接 OpenAI' : view.kind === 'account' ? accountName || 'ChatGPT 账号' : view.kind === 'tools' ? '用量与诊断' : view.kind === 'edit' ? connection?.name || view.preset?.name || '服务配置' : connection?.name || '服务详情'}</h2></header>
      {view.kind === 'add' && <div className="pw-ai-brand-directory">
        <ServiceOption title="OpenAI" detail="ChatGPT 账号或 API 密钥" onClick={() => open({ kind: 'openai' })} />
        {presets.slice(1).map(preset => <ServiceOption key={preset.provider} title={preset.name.replace(' API', '')} detail={preset.provider === 'bedrock_anthropic' ? '区域与 STS 临时凭据' : preset.provider === 'openai_chat' ? 'OpenAI 等兼容协议' : '使用 API 密钥'} onClick={() => open({ kind: 'edit', preset })} />)}
      </div>}
      {view.kind === 'openai' && <div className="pw-ai-brand-directory"><ServiceOption title="登录 ChatGPT" detail="使用账号可用的套餐模型" icon="account" onClick={() => open({ kind: 'account', adding: true })} /><ServiceOption title="添加 API 密钥" detail="连接自己的 OpenAI API 服务" onClick={() => open({ kind: 'edit', preset: presets[0] })} /></div>}
      {view.kind === 'edit' && (view.id && !connection ? <p role="alert">这项服务已变化，请返回服务列表重新核对。</p> : renderEditor(connection, view.preset, back, saved, registerChildBack))}
      {view.kind === 'account' && renderAccounts(view.id, view.adding === true, registerChildBack)}
      {view.kind === 'tools' && renderTools(registerChildBack)}
      {view.kind === 'connection' && (connection ? <>
        <div className="pw-ai-service-summary"><p>{aiServiceBrand(connection)} · {aiCredentialStatus(connection)}</p><small>{connection.baseUrl}</small></div>
        <div className="pw-ai-detail-actions"><Button leadingIcon={RefreshCw} disabled={busy || !supported('models.catalog.refresh')} onClick={() => readCatalog(true)}>{busy ? '读取中…' : '读取模型'}</Button><Button variant="ghost" leadingIcon={Settings2} disabled={busy} onClick={() => open({ kind: 'edit', id: connection.id })}>配置与测试</Button></div>
        {catalog && aiCatalogCurrent(catalog, connection) && <section className="pw-ai-source-catalog"><header className="pw-ai-section-heading"><h3>模型目录</h3>{catalog.catalogFetchedAtMillis > 0 && <span>{new Date(catalog.catalogFetchedAtMillis).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })}</span>}</header>
          {catalog.refreshAttempt?.status === 'failed' && <p className="pw-ai-feedback" role="status">{catalog.refreshAttempt.message || '这次目录读取失败。你仍可查看上次成功读取的记录，或重新读取。'}</p>}
          {catalog.catalogAvailable === false && <p className="pw-ai-feedback">{catalog.catalogUnavailableReason || '此服务没有可读取的模型目录，请在配置中填写模型 ID。'}</p>}
          {rows(catalog.models).length ? <div className="pw-ai-model-rows">{rows(catalog.models).map(row => <Button key={row.modelId} variant="ghost" className="pw-ai-model-row" disabled={busy || selectionUnknown || !supported('models.catalog.review')} onClick={() => reviewModel(row.modelId)}><span className="pw-ai-row-copy"><strong>{row.modelId}</strong><small>{row.catalogFresh === true ? '目录已读取' : '保留的模型记录'}</small></span><ChevronRight size={17} aria-hidden /></Button>)}</div> : <p className="pw-ai-muted">尚未读到模型。可重新读取，或在配置中填写模型 ID。</p>}
        </section>}
        <Accordion><AccordionItem id="service-details" title="服务信息与管理"><dl className="pw-ai-model-facts"><div><dt>连接 ID</dt><dd>{connection.id}</dd></div><div><dt>修订</dt><dd>{connection.revision}</dd></div><div><dt>接口协议</dt><dd>{connection.provider}</dd></div></dl><StretchSwitch label="启用这项服务" checked={connection.enabled === true} disabled={busy || serviceUnknown || !supported('models.connections.setEnabled')} onChange={enabled => setServiceChange({ id: connection.id, revision: connection.revision, name: connection.name, enabled })} /></AccordionItem></Accordion>
      </> : <div className="pw-ai-empty"><h3>这项服务已变化</h3><p>返回服务列表重新核对。</p><Button onClick={back}>返回服务</Button></div>)}
    </>}
    {notice && <p className="pw-ai-feedback" role="status">{notice}</p>}{error && <p className="pw-field-error" role="alert">{error}</p>}
    {selectionUnknown && <Button variant="secondary" disabled={busy} onClick={recoverSelection}>读取当前状态，核对原选择</Button>}
    {serviceUnknown && <p className="pw-ai-feedback" role="status">服务启停结果待核对，没有自动重试。<Button variant="ghost" disabled={busy} onClick={recoverService}>读取服务状态</Button></p>}
    <SettingsDialog isOpen={!!serviceChange} title={serviceChange?.enabled ? '启用这项服务？' : '停用这项服务？'} onClose={() => { if (!busy) setServiceChange(null); }}>{serviceChange && <><p>{serviceChange.name}</p><p>{serviceChange.enabled ? '允许后续模型请求使用这项服务；不会更改默认模型。' : '停止此来源的新模型请求。默认模型仍保留原选择，正在执行的任务不会自动改用其他服务。'}</p><div className="pw-ai-picker-actions"><Button variant="secondary" disabled={busy} onClick={() => setServiceChange(null)}>取消</Button><Button disabled={busy || serviceUnknown} onClick={changeServiceEnabled}>{serviceChange.enabled ? '启用服务' : '停用服务'}</Button></div></>}</SettingsDialog>
    <SettingsDialog isOpen={!!modelReview} title="选择默认模型" onClose={() => { if (!busy) setModelReview(null); }}>{modelReview && <div className="pw-ai-model-review">
      <header><h3>{modelReview.requestedModelId}</h3><p>{connection?.name}</p></header>
      <p>用于后续新任务；已有对话的单独选择和运行中的任务保持原样。</p>
      <dl className="pw-ai-model-facts">{Object.keys(factLabels).map(key => {
        const fact = object(object(modelReview.facts || object(modelReview.model).facts)[key]);
        const value = fact.known === true ? typeof fact.value === 'boolean' ? fact.value ? '支持' : '不支持' : typeof fact.value === 'object' ? fact.value?.tokens || '未声明' : fact.value : '未声明';
        return <div key={key}><dt>{factLabels[key]}</dt><dd>{String(value)}</dd></div>;
      })}</dl>
      <TextField label="本次输出上限" value={reviewOutput} onChange={value => setReviewOutput(value.replace(/\D/g, '').slice(0, 5))} inputMode="numeric" hint="128–32768 tokens，且不能超出该模型声明的容量。" isDisabled={busy} />
      {object(modelReview.connection).catalogEnabled === true ? <Accordion><AccordionItem id="model-input-reserve" title="图片输入预留"><TextField label="每张图片本地预留" value={reviewImageReserve} onChange={value => setReviewImageReserve(value.replace(/\D/g, '').slice(0, 7))} inputMode="numeric" hint="0–2000000 tokens；不会改写服务商声明的容量。" isDisabled={busy} /></AccordionItem></Accordion> : <p className="pw-ai-muted">推理参数可在当前对话单独设置；服务默认高级参数需先明确启用来源能力目录。</p>}
      <p className="pw-ai-muted">仅选择模型不会发起推理；推理强度先使用服务默认。</p>{error && <p role="alert" className="pw-field-error">{error}</p>}
      <div className="pw-ai-picker-actions"><Button variant="secondary" disabled={busy} onClick={() => setModelReview(null)}>取消</Button><Button disabled={busy || modelReview.selectionRevision !== state.modelSelectionRevision || !supported('models.catalog.select')} onClick={() => void selectReviewedModel()}>设为默认模型</Button></div>
    </div>}</SettingsDialog>
  </section>;
}
function ServiceOption({ title, detail, icon = 'key', onClick }: { title: string; detail: string; icon?: 'key' | 'account'; onClick: () => void }) {
  const Icon = icon === 'account' ? UserRound : KeyRound;
  return <Button variant="ghost" className="pw-ai-service-row" onClick={onClick}><Icon size={20} aria-hidden /><span className="pw-ai-row-copy"><strong>{title}</strong><small>{detail}</small></span><ChevronRight size={18} aria-hidden /></Button>;
}
