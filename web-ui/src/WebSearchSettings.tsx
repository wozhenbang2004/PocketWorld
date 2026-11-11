import { useEffect, useRef, useState } from 'react';
import { Button, SelectField, TextField } from './forms-library';

type Provider = 'duckduckgo' | 'searxng' | 'brave';
type Config = {
  provider: Provider;
  baseUrl: string;
  apiKeyConfigured: boolean;
  configured: boolean;
  revision: number;
  networkVerified: boolean;
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
  };
}

export function WebSearchSettings({ run, supported }: Props) {
  const active = useRef(true), action = useRef(run);
  action.current = run;
  const [saved, setSaved] = useState<Config | null>(null);
  const [provider, setProvider] = useState<Provider>('duckduckgo');
  const [baseUrl, setBaseUrl] = useState('');
  const [key, setKey] = useState('');
  const [busy, setBusy] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const reload = async () => {
    const result = configFrom(await action.current('webSearch.snapshot', {}));
    if (!active.current) return;
    setSaved(result); setProvider(result.provider); setBaseUrl(result.baseUrl);
    setKey(''); setUncertain(false); setError('');
  };
  useEffect(() => {
    active.current = true;
    void reload().catch(e => { if (active.current) setError(e instanceof Error ? e.message : String(e)); });
    return () => { active.current = false; };
  }, []);

  const changed = Boolean(saved && (provider !== saved.provider ||
    (provider === 'searxng' ? baseUrl.trim() : '') !== saved.baseUrl ||
    key.trim()));
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
        ...(key.trim() ? { apiKey: key.trim() } : {}),
      });
      const next = configFrom(response);
      if (!active.current) return;
      setSaved(next); setProvider(next.provider); setBaseUrl(next.baseUrl);
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
      <p className="pw-field-hint">服务状态：{saved.configured ? '已配置' : '配置不完整'} · 网络连通性尚未验证。搜索只提供标题和摘要，实际网页正文由 web_fetch 独立读取。</p>
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

