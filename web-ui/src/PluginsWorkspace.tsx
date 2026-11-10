import { useEffect, useRef, useState } from 'react';
import { IconHistory, IconRefresh } from './icons';
import { Accordion, AccordionItem, Button, CheckboxField, IconButton, SearchField, SelectField, SettingsDialog, TextAreaField, TextField } from './forms-library';
import { Tab, TabList, TabPanel, Tabs } from './shell-library';
import { FunctionalPacksWorkspace } from './FunctionalPacksWorkspace';
import { acceptPluginReview, acceptPluginView, catalogReceiptResult, catalogReview, catalogUnknown, confirmedPluginResult, confirmedSourceConfiguration, jsPluginIdentity, pluginIdentity, pluginRows, samePluginIdentity, sourceConfiguration, sourceDraft, type PluginData as Data } from './plugins-workspace';
import './plugins-workspace.css';
import './ecosystem-depth.css';

type Run = (action: string, args?: Data) => Promise<any>;
type Props = { mode: 'plugins' | 'pluginCatalog'; state: Data; run: Run; supported: (action: string) => boolean; registerTransientBack: (handler: (() => boolean) | null) => void; onNavigate?: (domain: 'plugins' | 'pluginCatalog' | 'mcp' | 'skills') => void };
const json = (value: unknown) => typeof value === 'string' ? value : JSON.stringify(value, null, 2);
const copy = (value: Data) => JSON.parse(JSON.stringify(value));
const strings = (value: unknown): string[] => Array.isArray(value) ? value.filter(item => typeof item === 'string') : [];
const operationTitle = (review: Data) => ({ install: '核对安装差异', restore: '核对历史版本恢复', enabled: review.data.nextEnabled ? '启用这个安装' : '停用这个安装', grant: review.data.nextGranted ? '授予工具权限' : '收回工具权限', remove: '移除这个安装', declaration_install: '核对目录安装差异', js_install: review.data.restoring ? '阅读历史源码' : '阅读待安装源码', js_permission: '源码与运行权限' } as Record<string, string>)[review.kind] || '核对插件操作';

function Facts({ rows }: { rows: [string, unknown][] }) {
  return <dl className="pw-facts pw-eco-facts">{rows.filter(([, value]) => value !== undefined && value !== null && value !== '').map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{String(value)}</dd></div>)}</dl>;
}
function Definition({ title, value }: { title: string; value: unknown }) {
  return <Accordion><AccordionItem title={title}><pre className="pw-plugin-definition">{json(value)}</pre></AccordionItem></Accordion>;
}
function DeclarationReview({ data }: { data: Data }) {
  const preview = data.packageReview?.preview || data;
  return <div className="pw-plugin-review-copy">
    <h3>{preview.manifest?.name || preview.id}</h3>
    <Facts rows={[["插件", preview.id], ["当前版本", preview.currentVersion || '尚未安装'], ["本次版本", preview.targetVersion], ["正在使用", Number(preview.activeCallsToCancel || 0) > 0 ? `${preview.activeCallsToCancel} 项调用会停止沿用旧安装` : undefined], ["包摘要", data.packageDigest || data.packageReview?.packageDigest]]} />
    <p className="pw-muted">{data.packageReview?.requiresMcpRediscovery ? '这个包包含 MCP 声明。确认后仍需到 MCP 服务重新发现当前工具，再单独核对安装。' : '安装或恢复会产生新的安装身份，保持停用，并清空工具授权。旧凭据不会带入。'}</p>
    {preview.sameManifest && <p className="pw-field-hint">本次声明内容与当前版本相同，重新安装仍会更换安装身份。</p>}
    <div className="pw-plugin-diff">
      <p><span>新增工具</span>{strings(preview.addedTools).join('、') || '无'}</p>
      <p><span>移除工具</span>{strings(preview.removedTools).join('、') || '无'}</p>
      <p><span>变更工具</span>{pluginRows(preview.changedTools).map(row => row.name).join('、') || '无'}</p>
    </div>
    {pluginRows(preview.changedTools).length > 0 && <Definition title="查看变更前后的完整定义" value={preview.changesText || preview.changedTools} />}
    <Definition title="查看本次完整声明" value={preview.manifestText || preview.manifest} />
  </div>;
}
function SourceReview({ data }: { data: Data }) {
  return <div className="pw-plugin-review-copy">
    <h3>{data.name || data.id}</h3>
    <Facts rows={[["扩展", data.id], ["本次版本", data.version], ["当前版本", data.current?.version], ["当前批准", data.installationId ? (data.approved ? '已批准' : '未批准') : undefined], ["当前启用", data.installationId ? (data.enabled ? '已启用' : '已停用') : undefined]]} />
    <p className="pw-muted">批准后，这份准确源码可在选用它的任务中执行初始化、工具和钩子。运行环境不提供 Node、网络、文件、Android 或直接宿主 API。</p>
    {data.restoring && <p className="pw-field-hint">恢复使用本机保留并重新核验的原包。新安装保持未批准、停用，旧任务许可不会恢复。</p>}
    <Definition title="源码摘要与安装身份" value={{sourceSha256:data.sourceSha256,packageSha256:data.packageSha256,installationId:data.installationId}} />
    <h4>完整源码</h4>
    <pre className="pw-plugin-source" tabIndex={0} aria-label={`${data.name || data.id} 的完整源码`}>{data.source}</pre>
  </div>;
}

/** UI handles remain presentation-only; native module and Page session own every write. */
export function PluginsWorkspace({ mode, state, run, supported, registerTransientBack, onNavigate }: Props) {
  const [snapshot, setSnapshot] = useState<Data | null>(null), [tab, setTab] = useState(mode === 'plugins' ? 'declaration' : 'catalog');
  const [selectedId, setSelectedId] = useState(''), [history, setHistory] = useState<Data | null>(null), [jsHistory, setJsHistory] = useState<Data | null>(null);
  const [sourceId, setSourceId] = useState(''), [catalogKind, setCatalogKind] = useState('declaration');
  const [installedQuery, setInstalledQuery] = useState(''), [jsQuery, setJsQuery] = useState(''), [catalogQuery, setCatalogQuery] = useState(''), [installedFilter, setInstalledFilter] = useState('all');
  const [editor, setEditor] = useState<'manifest' | 'source' | null>(null), [manifest, setManifest] = useState('');
  const [sourceOriginal, setSourceOriginal] = useState<Data | null>(null), [editingSource, setEditingSource] = useState<string | null>(null), [draft, setDraft] = useState<Data>(sourceDraft());
  const [review, setReview] = useState<Data | null>(null), [acknowledged, setAcknowledged] = useState(false), [token, setToken] = useState('');
  const [followup, setFollowup] = useState<'plugins' | 'mcp' | 'js' | null>(null);
  const [runtime, setRuntime] = useState<Data>({}), [busy, setBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState(''), [needsQuery, setNeedsQuery] = useState(false), [foreground, setForeground] = useState(true);
  const alive = useRef(true), entered = useRef(false), generation = useRef(0), latest = useRef<Data | null>(null), activeReview = useRef<Data | null>(null), resumed = useRef(state.webWorkspaceResumeRevision), backgrounded = useRef(false);
  const functionalPackBack = useRef<(() => boolean) | null>(null);
  const installed = pluginRows(snapshot?.installed?.plugins), extensions = pluginRows(snapshot?.jsInstalled?.plugins), sources = pluginRows(snapshot?.config?.sources);
  const selected = installed.find(row => row.id === selectedId), source = sources.find(row => row.id === sourceId);
  const unresolved = catalogUnknown(snapshot), locked = busy || needsQuery || unresolved || !foreground;
  const sourceStale = !!sourceOriginal && sourceOriginal.revision !== snapshot?.config?.revision;
  const candidates = snapshot?.catalog?.source?.id === sourceId && (snapshot?.catalog?.kind === 'executable_js') === (catalogKind === 'js') ? pluginRows(snapshot?.catalog?.entries) : [];
  const matches = (row: Data, query: string) => [row.id, row.name, row.manifest?.name, ...pluginRows(row.manifest?.tools || row.capabilities).map(tool => `${tool.name} ${tool.description || ''}`)].join(' ').toLocaleLowerCase().includes(query.trim().toLocaleLowerCase());
  const visibleInstalled = installed.filter(row => matches(row, installedQuery) && (installedFilter === 'all' || (installedFilter === 'enabled' ? row.enabled === true : row.enabled !== true)));
  const installedGroups = [{ id: 'disabled', label: '已停用', enabled: false }, { id: 'enabled', label: '已启用', enabled: true }].map(group => ({ ...group, records: visibleInstalled.filter(row => (row.enabled === true) === group.enabled) })).filter(group => group.records.length);
  const jsGroup = (row: Data) => row.approved !== true ? 'unapproved' : row.enabled === true ? 'enabled' : 'disabled';
  const jsGroups = [{ id: 'unapproved', label: '待阅读与批准' }, { id: 'disabled', label: '已批准 · 停用' }, { id: 'enabled', label: '已启用' }].map(group => ({ ...group, records: extensions.filter(row => matches(row, jsQuery) && jsGroup(row) === group.id) })).filter(group => group.records.length);
  const catalogGroups = [{ id: 'installed', label: '已安装项目的可选版本' }, { id: 'available', label: '可添加的版本' }, { id: 'compatibility', label: '兼容性需核对' }].map(group => ({ ...group, records: candidates.filter(row => matches(row, catalogQuery) && (catalogKind === 'declaration' && row.compatibilityResult?.compatible !== true ? 'compatibility' : row.currentVersion ? 'installed' : 'available') === group.id) })).filter(group => group.records.length);

  const invoke = async (action: string, args: Data = {}, accept?: (value: Data) => void | Promise<void>) => {
    if (entered.current) return; entered.current = true; const ticket = ++generation.current; setBusy(true); setError('');
    try {
      if (!supported(action)) throw new Error('当前宿主尚未接通这项插件操作。');
      const value = await run(action, args);
      if (alive.current && generation.current === ticket) await accept?.(value);
    } catch (failure) { if (alive.current && generation.current === ticket) setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { if (generation.current === ticket) { entered.current = false; if (alive.current) setBusy(false); } }
  };
  const attempt = (work: () => void) => { try { work(); } catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)); } };
  const receive = (value: Data, fresh = false) => {
    const actual = acceptPluginView(latest.current, value, fresh); if (!actual) throw new Error('原插件窗口尚未取得完整回执，请重新读取。');
    latest.current = actual; setSnapshot(actual); if (actual.runtime) setRuntime(actual.runtime);
    if (actual.status === 'failed' || actual.status === 'cancelled') setError(actual.message || '本次操作未完成，请重新读取。');
    if (catalogUnknown(actual)) { setNotice('原目录操作的结果仍待核对。请查询当前安装，不要重复提交。'); setNeedsQuery(true); }
    return actual;
  };
  const discardReview = () => {
    const previous = activeReview.current; activeReview.current = null; setReview(null); setAcknowledged(false); setToken('');
    if (previous?.reviewHandle) void run(previous.catalog ? 'pluginCatalog.cancel' : 'plugins.closeReview', { reviewHandle: previous.reviewHandle }).catch(() => {});
  };
  const receiveReview = (value: Data, kind: string, original?: Data) => {
    const actual = acceptPluginReview(value, kind, original);
    const next = { ...actual, draftText: kind === 'install' ? manifest : undefined }; activeReview.current = next; setReview(next); setAcknowledged(false); setToken('');
  };
  const receiveCatalogReview = (value: Data) => {
    const actual = receive(value), selectedReview = catalogReview(actual);
    if (!selectedReview) { if (actual.status !== 'failed' && !catalogUnknown(actual)) throw new Error('本次目录读取没有形成可确认的审阅。'); return; }
    const next = { ...selectedReview, catalog: true }; activeReview.current = next; setReview(next); setAcknowledged(false); setToken('');
  };
  const open = () => {
    if (document.visibilityState !== 'visible') { backgrounded.current = true; setForeground(false); return; }
    backgrounded.current = false; setForeground(true);
    discardReview(); setHistory(null); setJsHistory(null); setFollowup(null); latest.current = null; setSnapshot(null); setNeedsQuery(false);
    void invoke(mode === 'plugins' ? 'plugins.open' : 'pluginCatalog.open', {}, value => { const actual = receive(value, true); setSourceId(old => pluginRows(actual.config?.sources).some(row => row.id === old) ? old : pluginRows(actual.config?.sources).find(row => row.enabled)?.id || ''); });
  };
  const queryInstalled = () => {
    discardReview(); setHistory(null); setJsHistory(null);
    void invoke(mode === 'plugins' ? 'plugins.snapshot' : 'pluginCatalog.queryInstalled', {}, value => {
      const actual = receive(value); if (!catalogUnknown(actual)) setNeedsQuery(false);
      setNotice(catalogUnknown(actual) ? '已读取当前安装。原操作的记录仍未确认，本次只查询，没有重新提交。' : '已读取当前实际安装。');
    });
  };

  useEffect(() => {
    alive.current = true; generation.current++; entered.current = false; setTab(mode === 'plugins' ? 'declaration' : 'catalog'); setSelectedId(''); open();
    return () => {
      alive.current = false; generation.current++; entered.current = false;
      const original = latest.current?.pageRequestId, previous = activeReview.current; activeReview.current = null;
      if (previous?.reviewHandle) void run(previous.catalog ? 'pluginCatalog.cancel' : 'plugins.closeReview', { reviewHandle: previous.reviewHandle }).catch(() => {});
      if (original) void run('plugins.close', { pageRequestId: original }).catch(() => {});
    };
  }, [mode]);
  useEffect(() => {
    if (resumed.current === state.webWorkspaceResumeRevision) return; resumed.current = state.webWorkspaceResumeRevision;
    generation.current++; entered.current = false; open();
  }, [state.webWorkspaceResumeRevision]);
  useEffect(() => {
    const suspend = () => {
      backgrounded.current = true; generation.current++; entered.current = false; setBusy(false); setForeground(false);
      discardReview(); setHistory(null); setJsHistory(null); setSnapshot(null); setNeedsQuery(true);
    };
    const resume = () => {
      if (!backgrounded.current || document.visibilityState !== 'visible') return;
      generation.current++; entered.current = false; open();
    };
    const visibility = () => { if (document.visibilityState === 'visible') resume(); else suspend(); };
    document.addEventListener('visibilitychange', visibility); window.addEventListener('pagehide', suspend); window.addEventListener('pageshow', resume);
    return () => { document.removeEventListener('visibilitychange', visibility); window.removeEventListener('pagehide', suspend); window.removeEventListener('pageshow', resume); };
  }, [mode]);
  useEffect(() => {
    registerTransientBack(() => {
      if (tab === 'packs') return functionalPackBack.current?.() ?? false;
      if (busy) return true;
      if (review) { discardReview(); return true; }
      if (editor) { setEditor(null); setToken(''); return true; }
      if (history || jsHistory) { setHistory(null); setJsHistory(null); return true; }
      if (selectedId) { setSelectedId(''); return true; }
      return false;
    });
    return () => registerTransientBack(null);
  }, [busy, review, editor, history, jsHistory, selectedId, tab, registerTransientBack]);
  useEffect(() => {
    if (review && !review.catalog && ['enabled', 'grant', 'remove'].includes(review.kind) && !samePluginIdentity(review.data, installed.find(row => row.id === review.data.id))) {
      discardReview(); setError('这个安装的启停或授权已变化，请重新审阅。');
    }
  }, [snapshot, review]);

  const readHistory = (row: Data) => attempt(() => { void invoke('plugins.history', pluginIdentity(row), value => {
    if (value.id !== row.id || value.currentInstallationId !== row.installationId || value.currentStateRevision !== row.stateRevision || !Array.isArray(value.versions)) throw new Error('版本历史没有对应当前安装。');
    setHistory(value); setSelectedId(row.id);
  }); });
  const changePlugin = (row: Data, kind: string, input: Data) => attempt(() => { void invoke('plugins.reviewChange', { ...pluginIdentity(row), kind, ...input }, value => receiveReview(value, kind, row)); });
  const restorePlugin = (row: Data, version: Data) => attempt(() => { void invoke('plugins.reviewRestore', { ...pluginIdentity(row), targetInstallationId: version.installationId }, value => receiveReview(value, 'restore', row)); });
  const readJs = (row: Data, historyOnly = false) => attempt(() => { void invoke(historyOnly ? 'pluginCatalog.history' : 'pluginCatalog.readInstalled', jsPluginIdentity(row), value => {
    if (!historyOnly) receiveCatalogReview(value);
    else { const actual = receive(value); if (actual.status === 'ready' && actual.jsHistory?.id === row.id && Array.isArray(actual.jsHistory.versions)) setJsHistory(actual.jsHistory); }
  }); });
  const restoreJs = (row: Data, version: Data) => attempt(() => { void invoke('pluginCatalog.reviewRestore', { ...jsPluginIdentity(row), targetInstallationId: version.installationId }, receiveCatalogReview); });
  const openSource = (selectedSource?: Data) => { setEditingSource(selectedSource?.id || null); setSourceOriginal(copy(snapshot?.config || {})); setDraft(sourceDraft(selectedSource)); setEditor('source'); setError(''); };
  const saveSource = (remove = false) => attempt(() => {
    if (!sourceOriginal || sourceStale) throw new Error('来源配置已经变化。保留了输入，请关闭后重新核对当前来源。');
    const input = sourceConfiguration(sourceOriginal, draft, editingSource, remove), submitted = JSON.stringify(draft);
    void invoke('pluginCatalog.configure', input, value => {
      const actual = receive(value);
      if (!confirmedSourceConfiguration(input, actual)) throw new Error('来源保存尚未取得匹配回执，输入已保留。请重新读取来源核对。');
      if (JSON.stringify(draft) === submitted) { setEditor(null); setSourceOriginal(null); }
      setSourceId(remove ? '' : draft.id.trim()); setNotice(remove ? '已移除这个目录来源。已安装插件保留。' : '目录来源已保存。可以读取明确版本。');
    });
  });
  const confirmDeclaration = () => {
    if (!review || review.catalog || !acknowledged || entered.current) return;
    const selectedReview = review, secret = token; activeReview.current = null; setReview(null); setToken(''); setNeedsQuery(true);
    void invoke('plugins.confirm', { reviewHandle: selectedReview.reviewHandle, ...(secret ? { token: secret } : {}) }, async value => {
      if (value.status === 'unknown') { setNotice(value.message || '原变更结果待核对，请查询当前安装。'); return; }
      confirmedPluginResult(selectedReview, value);
      if (selectedReview.kind === 'install' && manifest === selectedReview.draftText) { setManifest(''); setEditor(null); }
      setNotice(selectedReview.kind === 'remove' ? '这个安装已移除。' : ['install', 'restore'].includes(selectedReview.kind) ? '新版本已安装，保持停用且未授予工具权限。' : selectedReview.kind === 'grant' ? '这个安装的工具权限已更新。' : '这个安装的启用状态已更新。');
      setHistory(null);
      const ticket = generation.current, reading = await run('plugins.snapshot');
      if (alive.current && generation.current === ticket) { receive(reading); setNeedsQuery(false); }
    });
  };
  const confirmCatalog = (operation: string) => {
    if (!review?.catalog || entered.current) return;
    const selectedReview = review; activeReview.current = null; setReview(null); setNeedsQuery(true);
    void invoke('pluginCatalog.confirm', { reviewHandle: selectedReview.reviewHandle, operation }, value => {
      const actual = receive(value); if (catalogUnknown(actual)) return;
      const result = catalogReceiptResult(actual, selectedReview, operation);
      setNeedsQuery(false); setJsHistory(null);
      setFollowup(result.status === 'rediscovery_required' ? 'mcp' : result.status === 'installed_disabled' ? 'plugins' : result.status === 'installed_unapproved' ? 'js' : null);
      setNotice(result.status === 'rediscovery_required' ? '尚未安装。请到「MCP 服务」重新发现当前工具，再核对安装。' : result.status === 'installed_unapproved' ? '源码已安装，保持未批准、停用。可在已安装扩展中继续审阅。' : result.status === 'installed_disabled' ? '声明包已安装，保持停用。请到插件管理重新启用并授权。' : operation === 'remove' ? '这个源码安装已卸载。' : operation === 'approve' ? '已批准这份源码。仍需单独启用，并在任务中选择。' : operation === 'revoke' ? '已撤销源码批准并停用扩展。' : operation === 'enable' ? '扩展已启用。任务仍需明确选择这份安装。' : '扩展已停用。');
    });
  };

  const installedContent = <div className="pw-eco-record-list" aria-label="已安装声明插件">
    <SearchField aria-label="查找已安装插件" placeholder="名称或工具" value={installedQuery} onChange={setInstalledQuery} />
    <div className="pw-eco-filters" role="group" aria-label="插件启用状态">{[{ id: 'all', label: '全部' }, { id: 'enabled', label: '已启用' }, { id: 'disabled', label: '已停用' }].map(item => <Button key={item.id} variant="ghost" aria-pressed={installedFilter === item.id} onClick={() => setInstalledFilter(item.id)}>{item.label}</Button>)}</div>
    {installed.length > 0 && !visibleInstalled.length && <p className="pw-eco-directory-empty" role="status">没有匹配的安装。可以换个关键词或状态。</p>}
    {!installed.length && <p className="pw-secondary-empty">尚未安装声明插件。可添加声明，或从「我的插件目录」选择版本。</p>}
    {installedGroups.map(group => <section key={group.id} className="pw-eco-directory-group" aria-label={group.label}><header className="pw-eco-group-heading"><h3>{group.label}</h3><span>{group.records.length}</span></header>{group.records.map(row => <article className="pw-source-record pw-eco-record" key={row.installationId}>
      <header><h3>{row.manifest?.name || row.id}</h3><span>{row.enabled ? '已启用' : '已停用'} · {row.manifest?.version}</span></header>
      <p className="pw-eco-record-meta">{pluginRows(row.manifest?.tools).length} 个工具 · {strings(row.grants).length} 项授权</p>
      <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => { setSelectedId(selectedId === row.id ? '' : row.id); setHistory(null); }}>{selectedId === row.id ? '收起工具' : '管理工具'}</Button><Button variant="ghost" disabled={locked} onClick={() => changePlugin(row, 'enabled', { enabled: !row.enabled })}>{row.enabled ? '停用' : '启用'}</Button><IconButton icon={IconHistory} aria-label="版本历史" title="版本历史" disabled={busy} onClick={() => readHistory(row)}/></div>
      {selectedId === row.id && <div className="pw-selected-object pw-eco-plugin-detail"><div className="pw-eco-install-identity"><h4>当前安装</h4><Facts rows={[["安装身份", row.installationId], ["状态版本", row.stateRevision], ["工具凭据", row.hasToken ? '已配置' : '未配置'], ["MCP 状态", row.mcpStatus]]} />
        <Definition title="安装声明与原始定义" value={row.manifestText || row.manifest} /></div><div className="pw-eco-tools"><div className="pw-eco-section-heading"><h4>工具权限</h4></div>{pluginRows(row.manifest?.tools).map(tool => <div className="pw-source-record pw-eco-tool" key={tool.name}>
          <header><h3>{tool.name}</h3><span>{tool.effect === 'read' ? '读取' : '可能修改'}</span></header><p>{tool.description}</p>
          <Definition title="工具类型与服务地址" value={{kind:tool.kind,endpoint:tool.endpoint}} />
          <div className="pw-form-actions"><Button variant="secondary" disabled={locked} onClick={() => changePlugin(row, 'grant', { tool: tool.name, granted: !strings(row.grants).includes(`tool:${tool.name}`) })}>{strings(row.grants).includes(`tool:${tool.name}`) ? '收回这个工具的权限' : '核对并授予权限'}</Button></div>
        </div>)}
        </div><div className="pw-form-actions pw-eco-danger-actions"><Button variant="ghost" disabled={locked} onClick={() => changePlugin(row, 'remove', {})}>移除这个安装</Button></div>
      </div>}
    </article>)}</section>)}
    {history && selected && history.id === selected.id && <section className="pw-selected-object"><h3>{selected.manifest?.name || selected.id} · 版本历史</h3><p className="pw-field-hint">保留最近三次被替换的声明。恢复创建新安装，保持停用，不带回旧凭据和授权。</p>{!pluginRows(history.versions).length && <p className="pw-secondary-empty">没有保留的历史版本。</p>}{pluginRows(history.versions).map(row => <article className="pw-source-record pw-eco-record" key={row.installationId}><header><h3>{row.manifest?.version}</h3><span>历史安装</span></header><p>{row.installationId}</p><div className="pw-form-actions"><Button variant="secondary" disabled={locked || history.currentInstallationId !== selected.installationId || history.currentStateRevision !== selected.stateRevision} onClick={() => restorePlugin(selected, row)}>核对恢复差异</Button></div></article>)}</section>}
  </div>;
  const jsContent = <>
    <div className="pw-plugin-runtime pw-eco-status-strip"><div><h3>JS 运行环境</h3><p className="pw-field-hint">{runtime.message || '先核对本机 WebView 与隔离能力。准备环境不会执行插件源码。'}</p></div><div className="pw-form-actions"><Button variant="secondary" disabled={busy || runtime.status === 'preparing'} onClick={() => { void invoke('pluginCatalog.prepareRuntime', {}, value => setRuntime(value)); }}>{runtime.prepared ? '重新核对环境' : runtime.status === 'preparing' ? '正在准备' : '准备运行环境'}</Button>{runtime.status === 'preparing' && <Button variant="ghost" disabled={busy} onClick={() => { void invoke('pluginCatalog.runtime', {}, value => setRuntime(value)); }}>查询准备结果</Button>}</div></div>
    <SearchField aria-label="查找已安装扩展" placeholder="扩展名称或标识" value={jsQuery} onChange={setJsQuery} />
    {extensions.length > 0 && !jsGroups.length && <p className="pw-eco-directory-empty" role="status">没有匹配的扩展。</p>}
    {!extensions.length && <p className="pw-secondary-empty">尚未安装 JS 扩展。先在目录中阅读完整源码，再选择安装。</p>}
    {jsGroups.map(group => <section key={group.id} className="pw-eco-directory-group" aria-label={group.label}><header className="pw-eco-group-heading"><h3>{group.label}</h3><span>{group.records.length}</span></header>{group.records.map(row => <article className="pw-source-record pw-eco-record" key={row.installationId}><header><h3>{row.name || row.id}</h3><span>{row.enabled ? '已启用' : row.approved ? '已批准 · 未启用' : '未批准'} · {row.version}</span></header><p>{row.id}</p><div className="pw-form-actions"><Button variant="secondary" disabled={locked} onClick={() => readJs(row)}>阅读源码与权限</Button><Button variant="ghost" disabled={busy || unresolved} onClick={() => readJs(row, true)}>源码历史</Button></div>
      {jsHistory && jsHistory.id === row.id && <div className="pw-selected-object"><h4>保留的源码版本</h4><p className="pw-field-hint">只恢复可核验的原包，不恢复旧批准、启用状态或任务许可。</p>{!pluginRows(jsHistory.versions).length && <p className="pw-secondary-empty">没有保留的源码版本。</p>}{pluginRows(jsHistory.versions).map(version => <article className="pw-source-record" key={version.installationId}><header><h3>{version.version}</h3><span>{version.restorable ? '原包可核验' : '无法恢复'}</span></header><p>{version.message}</p><Facts rows={[["原安装", version.installationId], ["源码摘要", version.sourceSha256]]} /><div className="pw-form-actions"><Button variant="secondary" disabled={locked || version.restorable !== true} onClick={() => restoreJs(row, version)}>阅读并核对恢复</Button></div></article>)}</div>}
    </article>)}</section>)}
  </>;
  const catalogContent = <>
    <section className="pw-eco-catalog-source" aria-label="当前目录来源"><div className="pw-form-grid"><SelectField label="目录来源" options={sources.map(row => ({ id: row.id, label: row.label || row.id, description: row.enabled ? row.catalogUrl : '已停用' }))} value={sourceId} onChange={value => { discardReview(); setSourceId(value); }} isDisabled={busy} /><SelectField label="包类型" value={catalogKind} options={[{ id: 'declaration', label: '声明插件', description: '核对实际工具定义与版本差异' }, { id: 'js', label: 'JS 扩展', description: '下载并阅读完整源码' }]} onChange={value => { discardReview(); setCatalogKind(value); }} isDisabled={busy} /></div>
    {source && <p className="pw-field-hint pw-plugin-url">{source.catalogUrl}{source.pinnedCatalogSha256 ? ' · 已固定目录摘要' : ''}</p>}
    {!sources.length && <p className="pw-secondary-empty">添加你选择的目录来源，然后读取其中的明确版本。</p>}
    <div className="pw-form-actions"><Button disabled={locked || !source?.enabled} onClick={() => { void invoke('pluginCatalog.refresh', { kind: catalogKind, sourceId }, value => { receive(value); }); }}>读取可选版本</Button><Button variant="secondary" disabled={locked || !snapshot?.config?.revision} onClick={() => openSource()}>添加来源</Button>{source && <Button variant="ghost" disabled={locked} onClick={() => openSource(source)}>编辑来源</Button>}<Button variant="ghost" disabled={busy} onClick={() => { discardReview(); void invoke('pluginCatalog.read', {}, value => { receive(value); }); }}>重读来源配置</Button></div>
    </section><section className="pw-eco-record-list" aria-label="可选版本">{candidates.length > 0 && <p className="pw-field-hint">目录说明来自所选来源。下载后会核对准确包内容；版本由你选择。</p>}
    {candidates.length > 0 && <SearchField aria-label="查找已读取的插件版本" placeholder="名称、标识或工具" value={catalogQuery} onChange={setCatalogQuery} />}
    {candidates.length > 0 && !catalogGroups.length && <p className="pw-eco-directory-empty" role="status">当前来源中没有匹配的版本。</p>}
    {catalogGroups.map(group => <section key={group.id} className="pw-eco-directory-group" aria-label={group.label}><header className="pw-eco-group-heading"><h3>{group.label}</h3><span>{group.records.length}</span></header>{group.records.map(entry => <article className="pw-source-record pw-eco-record" key={`${entry.id}:${entry.version}`}><header><h3>{entry.id}</h3><span>{entry.version}</span></header>{entry.currentVersion && <p>目录读取时已安装：{entry.currentVersion}</p>}{catalogKind === 'declaration' && <p>{pluginRows(entry.capabilities).map(tool => `${tool.name} · ${tool.effect === 'read' ? '读取' : '可能修改'}`).join('；')}</p>}{catalogKind === 'declaration' && entry.compatibilityResult?.compatible !== true && <p>此版本与当前设备或支持格式不兼容。</p>}<div className="pw-form-actions"><Button variant="secondary" disabled={locked || catalogKind === 'declaration' && entry.compatibilityResult?.compatible !== true} onClick={() => { void invoke('pluginCatalog.prepare', { kind: catalogKind, sourceId, id: entry.id, version: entry.version }, receiveCatalogReview); }}>{catalogKind === 'js' ? '下载并阅读源码' : '查看安装差异'}</Button></div></article>)}</section>)}
    {snapshot?.status === 'ready' && snapshot.catalog?.source?.id === sourceId && !candidates.length && <p className="pw-secondary-empty">本次目录没有匹配的可选版本。可切换包类型后重新读取。</p>}</section>
  </>;

  return <div className="pw-plugins-workspace pw-ecosystem-depth">
    {error && <p className="pw-field-error" role="alert">{error}</p>}{notice && <div className="pw-request-notice" role="status"><p>{notice}</p>{followup && <div className="pw-form-actions">{followup === 'js' ? <Button variant="secondary" disabled={busy} onClick={() => setTab('js')}>查看已安装源码</Button> : onNavigate && <Button variant="secondary" disabled={busy} onClick={() => onNavigate(followup)}>{followup === 'mcp' ? '打开 MCP 工具' : '前往插件管理'}</Button>}</div>}</div>}
    <section className="pw-settings-section">
      <div className="pw-section-heading pw-eco-workspace-heading"><div><h2>{mode === 'plugins' ? '已安装插件' : '我的插件目录'}</h2></div>
      <div className="pw-form-actions"><IconButton icon={IconRefresh} aria-label={needsQuery || unresolved ? '查询当前实际安装' : '刷新安装状态'} title={needsQuery || unresolved ? '查询当前实际安装' : '刷新安装状态'} disabled={busy || !snapshot} onClick={queryInstalled}/>{mode === 'plugins' && tab === 'declaration' && <Button disabled={locked || !snapshot} onClick={() => { setEditor('manifest'); setError(''); }}>添加插件声明</Button>}{onNavigate && <Button variant="ghost" aria-label={mode === 'plugins' ? '打开我的插件目录' : '前往插件管理'} disabled={busy} onClick={() => onNavigate(mode === 'plugins' ? 'pluginCatalog' : 'plugins')}>{mode === 'plugins' ? '插件目录' : '已安装'}</Button>}{!snapshot && <Button variant="ghost" disabled={busy || !foreground} onClick={open}>重新读取</Button>}</div></div>
      {busy && <p className="pw-field-hint" role="status">正在读取原生结果，请稍候…</p>}
      {needsQuery && !unresolved && <p className="pw-field-hint">上次操作已提交或审阅已失效。先读取当前安装，再继续新的操作。</p>}
      {snapshot && <Tabs selectedKey={tab} onSelectionChange={key => { if (!busy) { discardReview(); setTab(String(key)); } }}><TabList aria-label="插件工作区">{mode === 'plugins' ? <Tab id="declaration">声明插件 · {installed.length}</Tab> : <Tab id="catalog">选择版本</Tab>}<Tab id="js">JS 扩展 · {extensions.length}</Tab><Tab id="packs">完整功能包</Tab></TabList>{mode === 'plugins' ? <TabPanel id="declaration">{installedContent}</TabPanel> : <TabPanel id="catalog">{catalogContent}</TabPanel>}<TabPanel id="js">{jsContent}</TabPanel><TabPanel id="packs">{tab === 'packs' && <FunctionalPacksWorkspace state={state} run={run} supported={supported} registerTransientBack={handler => { functionalPackBack.current = handler; }} onNavigate={onNavigate} />}</TabPanel></Tabs>}
    </section>
    <SettingsDialog isOpen={!!review || !!editor} title={review ? operationTitle(review) : editor === 'source' ? editingSource ? '编辑目录来源' : '添加目录来源' : '添加插件声明'} onClose={() => { if (busy) return; if (review) discardReview(); else { setEditor(null); setToken(''); } }}>
      <div className="pw-plugins-workspace pw-plugin-dialog-body pw-ecosystem-depth pw-eco-review-body">
        {error && <p className="pw-field-error" role="alert">{error}</p>}
        {review ? <>
          {['install', 'restore', 'declaration_install'].includes(review.kind) ? <DeclarationReview data={review.data} /> : review.catalog ? <SourceReview data={review.data} /> : <>
            <h3>{review.data.manifest?.name || review.data.id}</h3><Facts rows={[["插件", review.data.id], ["版本", review.data.manifest?.version], ["安装身份", review.data.installationId], ["当前启用", review.data.enabled ? '已启用' : '已停用'], ["本次工具", review.data.selectedTool?.name]]} />
            <p className="pw-muted">{review.kind === 'remove' ? '移除这份安装会收回工具授权并停止旧调用。此插件保留的版本历史也会一起移除。' : review.kind === 'grant' ? review.data.nextGranted ? '这个安装可以提供所选工具。实际任务仍按工具效果与任务许可核对。' : '收回所选工具权限后，旧调用不能继续沿用这项授权。' : review.data.nextEnabled ? '启用当前安装。工具仍需各自授权后才能使用。' : '停用当前安装，并停止旧调用沿用它。'} </p>
            {review.data.selectedTool && <Definition title="查看所选工具的完整定义" value={review.data.selectedToolText || review.data.selectedTool} />}
          </>}
          <CheckboxField isSelected={acknowledged} onChange={setAcknowledged} isDisabled={busy}>{review.catalog && review.kind !== 'declaration_install' ? '我已阅读这份完整源码及运行范围' : '我已核对本次安装、工具与权限变化'}</CheckboxField>
          {!review.catalog && ['install', 'restore'].includes(review.kind) && <TextField label="本次工具凭据（可选）" type="password" autoComplete="off" value={token} onChange={setToken} maxLength={4096} isDisabled={busy} hint="只用于本次新安装；不会带回旧凭据。关闭审阅后清空。" />}
          <div className="pw-form-actions pw-eco-review-confirm">
            {!review.catalog && <Button disabled={busy || !acknowledged} onClick={confirmDeclaration}>{review.kind === 'remove' ? '确认移除这个安装' : ['install', 'restore'].includes(review.kind) ? '确认安装并保持停用' : '确认这项变更'}</Button>}
            {review.catalog && review.kind !== 'js_permission' && <Button disabled={busy || !acknowledged || unresolved} onClick={() => confirmCatalog('install')}>{review.kind === 'js_install' ? '安装并保持未批准' : review.data.packageReview?.requiresMcpRediscovery ? '继续到 MCP 重新发现' : '确认安装并保持停用'}</Button>}
            {review.catalog && review.kind === 'js_permission' && <><Button disabled={busy || unresolved || !review.data.approved && !acknowledged} onClick={() => confirmCatalog(review.data.approved ? 'revoke' : 'approve')}>{review.data.approved ? '撤销源码批准' : '批准任务中执行'}</Button><Button variant="secondary" disabled={busy || unresolved || !review.data.enabled && (!review.data.approved || !acknowledged)} onClick={() => confirmCatalog(review.data.enabled ? 'disable' : 'enable')}>{review.data.enabled ? '停用扩展' : '启用扩展'}</Button><Button variant="ghost" disabled={busy || unresolved} onClick={() => confirmCatalog('remove')}>卸载这个安装</Button></>}
            <Button variant="ghost" disabled={busy} onClick={discardReview}>返回</Button>
          </div>
        </> : editor === 'manifest' ? <>
          <p className="pw-muted">粘贴声明后，先核对真实安装差异。MCP 服务从「MCP 服务」重新发现工具。</p>
          <TextAreaField label="插件声明 JSON" value={manifest} onChange={setManifest} rows={13} maxLength={131072} isDisabled={busy} hint="包含 id、name、version 和 tools。支持声明式 echo、HTTP 工具。" />
          <div className="pw-form-actions"><Button disabled={locked || !manifest.trim()} onClick={() => { void invoke('plugins.reviewInstall', { manifestText: manifest }, value => receiveReview(value, 'install')); }}>核对安装差异</Button>{needsQuery && <Button variant="secondary" disabled={busy} onClick={queryInstalled}>查询当前实际安装</Button>}<Button variant="ghost" disabled={busy} onClick={() => setEditor(null)}>返回</Button></div>
        </> : editor === 'source' ? <>
          <div className="pw-form-grid"><TextField label="来源标识" value={draft.id} onChange={value => setDraft(old => ({ ...old, id: value }))} isDisabled={busy || !!editingSource} maxLength={80} /><TextField label="显示名称" value={draft.label} onChange={value => setDraft(old => ({ ...old, label: value }))} isDisabled={busy} maxLength={240} /><TextField label="目录地址" value={draft.catalogUrl} onChange={value => setDraft(old => ({ ...old, catalogUrl: value }))} isDisabled={busy} maxLength={2000} /><CheckboxField isSelected={draft.enabled} onChange={value => setDraft(old => ({ ...old, enabled: value }))} isDisabled={busy}>启用这个来源</CheckboxField></div>
          <Accordion><AccordionItem title="固定目录内容（可选）"><TextField label="目录 SHA-256" value={draft.pinnedCatalogSha256} onChange={value => setDraft(old => ({ ...old, pinnedCatalogSha256: value }))} isDisabled={busy} maxLength={64} hint="使用从可信渠道取得的摘要。目录更新后，需要由你更换摘要。" /></AccordionItem></Accordion>
          {sourceStale && <p className="pw-field-error" role="alert">来源配置已经变化，输入仍保留。请关闭后重新核对当前来源。</p>}
          <div className="pw-form-actions"><Button disabled={locked || sourceStale || !draft.id.trim() || !draft.label.trim() || !draft.catalogUrl.trim()} onClick={() => saveSource()}>保存来源</Button>{editingSource && <Button variant="ghost" disabled={locked || sourceStale} onClick={() => saveSource(true)}>移除此来源</Button>}<Button variant="ghost" disabled={busy} onClick={() => setEditor(null)}>返回</Button></div>
        </> : null}
      </div>
    </SettingsDialog>
  </div>;
}
