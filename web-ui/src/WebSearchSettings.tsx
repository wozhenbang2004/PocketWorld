import { useEffect, useRef, useState } from 'react';
import { Button, CheckboxField, SelectField, TextField } from './forms-library';

type Provider = 'duckduckgo' | 'searxng' | 'brave';
type Config = {
  provider: Provider;
  baseUrl: string;
  apiKeyConfigured: boolean;
  configured: boolean;
  revision: number;
  networkVerified: boolean;
  providerHostedEnabled: boolean;
  groupSearchEnabled: boolean;
  diagnostics: Record<string, unknown>;
};
type Props = {
  run: (action: string, args?: Record<string, unknown>) => Promise<unknown>;
  supported: (action: string) => boolean;
};
const providers = [
  { id: 'duckduckgo', label: 'DuckDuckGo · 无需密钥' },
  { id: 'searxng', label: 'SearXNG · 自托管或可信实例' },
  { id: 'brave', label: 'Brave Search · 官方 API' },
];

function configFrom(value: unknown): Config {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('搜索配置格式无效');
  const result = value as Record<string, unknown>;
  if (!['duckduckgo', 'searxng', 'brave'].includes(String(result.provider))
      || typeof result.revision !== 'number' || !Number.isSafeInteger(result.revision))
    throw new Error('搜索配置修订不可核对');
  return {
    provider: result.provider as Provider,
    baseUrl: typeof result.baseUrl === 'string' ? result.baseUrl : '',
    apiKeyConfigured: result.apiKeyConfigured === true,
    configured: result.configured === true,
    revision: result.revision,
    networkVerified: result.networkVerified === true,
    providerHostedEnabled: result.providerHostedEnabled === true,
    groupSearchEnabled: result.groupSearchEnabled === true,
    diagnostics: result.diagnostics && typeof result.diagnostics === 'object' && !Array.isArray(result.diagnostics) ? result.diagnostics as Record<string, unknown> : {},
  };
}

export function WebSearchSettings({ run, supported }: Props) {
  const active = useRef(true), action = useRef(run);
  action.current = run;
  const [saved, setSaved] = useState<Config | null>(null);
  const [provider, setProvider] = useState<Provider>('duckduckgo');
  const [baseUrl, setBaseUrl] = useState('');
  const [key, setKey] = useState('');
  const [hosted, setHosted] = useState(false);
  const [groupSearch, setGroupSearch] = useState(false);
  const [busy, setBusy] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const reload = async () => {
    const result = configFrom(await action.current('webSearch.snapshot', {}));
    if (!active.current) return;
    setSaved(result); setProvider(result.provider); setBaseUrl(result.baseUrl);
    setHosted(result.providerHostedEnabled); setGroupSearch(result.groupSearchEnabled);
    setKey(''); setUncertain(false); setError('');
  };
  useEffect(() => {
    active.current = true;
    void reload().catch(e => { if (active.current) setError(e instanceof Error ? e.message : String(e)); });
    return () => { active.current = false; };
  }, []);

  const changed = Boolean(saved && (provider !== saved.provider ||
    (provider === 'searxng' ? baseUrl.trim() : '') !== saved.baseUrl ||
    hosted !== saved.providerHostedEnabled || groupSearch !== saved.groupSearchEnabled || key.trim()));
  const canSave = saved && changed && !busy && !uncertain && supported('webSearch.save') &&
    (provider !== 'searxng' || /^https:\/\/[^/?#\s]+/.test(baseUrl.trim())) &&
    (provider !== 'brave' || Boolean(key.trim()) || provider === saved.provider && saved.apiKeyConfigured);

  const save = async () => {
    if (!canSave || !saved) return;
    const revision = saved.revision;
    setBusy(true); setError(''); setNotice('');
    try {
      const response = await action.current('webSearch.save', {
        provider,
        baseUrl: provider === 'searxng' ? baseUrl.trim() : '',
        expectedRevision: revision,
        providerHostedEnabled: hosted,
        groupSearchEnabled: groupSearch,
        ...(key.trim() ? { apiKey: key.trim() } : {}),
      });
      const next = configFrom(response);
      if (!active.current) return;
      setSaved(next); setProvider(next.provider); setBaseUrl(next.baseUrl);
      setHosted(next.providerHostedEnabled); setGroupSearch(next.groupSearchEnabled);
      setKey(''); setUncertain(false); setNotice('本机配置已保存。联网是否成功仍以实际搜索回执为准。');
    } catch (failure) {
      if (!active.current) return;
      setUncertain(true); setKey('');
      setError('保存结果未确认，请先重新读取原配置，不自动重试。' +
        (failure instanceof Error ? ' ' + failure.message : ''));
    } finally { if (active.current) setBusy(false); }
  };

  return <section className="pw-settings-section pw-model-editor" aria-label="联网搜索设置">
    <header className="pw-section-heading">
      <h2>联网搜索</h2>
      <p>模型通过 PocketWorld 的原生工具搜索网页，不依赖所选模型厂商是否提供搜索 API。</p>
    </header>
    {saved ? <div className="pw-form-grid">
      <SelectField label="搜索服务" value={provider} options={providers} isDisabled={busy || uncertain}
        onChange={value => { if (providers.some(item => item.id === value)) {
          setProvider(value as Provider); setBaseUrl(value === 'searxng' && saved.provider === 'searxng' ? saved.baseUrl : '');
          setKey(''); setNotice(''); setError('');
        } }} />
      {provider === 'searxng' && <TextField label="SearXNG HTTPS 地址" type="url" value={baseUrl}
        placeholder="https://search.example.org" isDisabled={busy || uncertain}
        onChange={setBaseUrl} autoComplete="off"
        hint="所选实例需要启用 JSON 搜索响应。仅接受 HTTPS，不能访问本机或内网服务。" />}
      {provider !== 'duckduckgo' && <TextField label={provider === 'brave' ? 'Brave Search API Key' : '可选 Bearer Token'}
        type="password" value={key} isDisabled={busy || uncertain} onChange={setKey}
        placeholder={provider === saved.provider && saved.apiKeyConfigured ? '已保存，留空保留' : provider === 'brave' ? '填写 API Key' : '无凭据时留空'}
        autoComplete="new-password"
        hint="凭据只保存于本机加密存储，不发送给模型。" />}
      <div className="pw-form-grid">
        <CheckboxField isSelected={hosted} isDisabled={busy || uncertain} onChange={setHosted}>允许厂商内置搜索</CheckboxField>
        <p className="pw-field-hint">仅当本任务选择官方 OpenAI Responses、Claude 或 Gemini API 连接时挂载。单次搜索仍按原权限审批；可能产生额外的模型 Token 和搜索服务费用，默认关闭。</p>
        <CheckboxField isSelected={groupSearch} isDisabled={busy || uncertain} onChange={setGroupSearch}>允许群任务公共搜索</CheckboxField>
        <p className="pw-field-hint">只给明确选择 public.search 能力的群任务使用公共搜索，不共享私人历史和浏览器账号；默认关闭。</p>
      </div>
      <p className="pw-field-hint">服务状态：{saved.configured ? '已配置' : '配置不完整'} · 网络连通性尚未验证。搜索只提供标题和摘要，实际网页正文由 web_fetch 独立读取。</p>
      <section aria-label="联网诊断" className="pw-settings-object-facts">
        <h3>本次运行的联网诊断</h3>
        <p className="pw-field-hint">公共搜索 {String(saved.diagnostics.searchRequests ?? 0)} 次 · 网页读取 {String(saved.diagnostics.fetchRequests ?? 0)} 次 · 实际正文 {String(saved.diagnostics.pagesRead ?? 0)} 页</p>
        <p className="pw-field-hint">厂商搜索调用 {String(saved.diagnostics.hostedModelRequests ?? 0)} 次 · 厂商返回输入/输出 {String(saved.diagnostics.hostedInputTokens ?? 0)} / {String(saved.diagnostics.hostedOutputTokens ?? 0)} Token</p>
        <p className="pw-field-hint">失败或未完成 {String(saved.diagnostics.failedCalls ?? 0)} 次 · 最后结果 {String(saved.diagnostics.lastStatus ?? '未检查')}</p>
        <p className="pw-field-hint">统计仅覆盖当前应用进程。历史来源与回执保存在原任务中；外部搜索费用未知，不混入模型推理费用。</p>
      </section>
      {uncertain && <p role="alert" className="pw-field-error">上次保存回执不确定，需要重新核对原配置版本。</p>}
      {error && <p role="alert" className="pw-field-error">{error}</p>}
      {notice && <p role="status" className="pw-field-hint">{notice}</p>}
      <div className="pw-form-actions">
        <Button variant="secondary" disabled={busy || !supported('webSearch.snapshot')}
          onClick={() => { setBusy(true); void reload().catch(e => { if (active.current) setError(e instanceof Error ? e.message : String(e)); })
            .finally(() => { if (active.current) setBusy(false); }); }}>重新读取</Button>
        <Button disabled={!canSave} onClick={() => { void save(); }}>{busy ? '保存中…' : '保存搜索服务'}</Button>
      </div>
    </div> : <div>
      <p className="pw-field-hint">正在读取本机联网搜索配置。</p>
      {error && <p role="alert" className="pw-field-error">{error}</p>}
      <Button variant="secondary" disabled={busy || !supported('webSearch.snapshot')}
        onClick={() => { setBusy(true); void reload().catch(e => { if (active.current) setError(e instanceof Error ? e.message : String(e)); })
          .finally(() => { if (active.current) setBusy(false); }); }}>重新读取</Button>
    </div>}
  </section>;
}

