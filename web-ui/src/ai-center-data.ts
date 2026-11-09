import { accountStatusText } from './account-actions.ts';

export type AIRecord = Record<string, any>;
export const aiObject = (value: any): AIRecord => value && typeof value === 'object' && !Array.isArray(value) ? value : {};
export const aiRows = (value: any): AIRecord[] => Array.isArray(value) ? value.filter(row => row && typeof row === 'object' && !Array.isArray(row)) : [];
export const aiText = (value: any): string => typeof value === 'string' ? value : '';
export const aiConnections = (value: AIRecord): AIRecord[] => aiRows(Array.isArray(value.connections) ? value.connections : aiObject(value.connections).connections);
export type AISourceKind = 'official_api' | 'third_party_gateway' | 'local_gateway' | 'cloud_provider' | 'subscription_account';
export type AISource = { kind: AISourceKind; providerId: string; authentication: string; externalRelayEligible: boolean };
export const aiSourceGroups: { kind: AISourceKind; label: string }[] = [
  { kind: 'subscription_account', label: '账号登录' },
  { kind: 'official_api', label: '官方 API' },
  { kind: 'third_party_gateway', label: '第三方网关' },
  { kind: 'local_gateway', label: '本地服务' },
  { kind: 'cloud_provider', label: '云平台' },
];
/** Display only. Native credentials, grants and request admission remain authoritative. */
export function aiConnectionSource(connection: AIRecord): AISource {
  const source = aiObject(connection.source);
  if (aiSourceGroups.some(group => group.kind === source.kind) && aiText(source.providerId) &&
    aiText(source.authentication) && typeof source.externalRelayEligible === 'boolean') return source as AISource;
  // Older hosts have no source descriptor. A compatible protocol alone is not an official service.
  if (connection.provider === 'bedrock_anthropic') return { kind: 'cloud_provider', providerId: 'aws_bedrock', authentication: 'aws_sts_session', externalRelayEligible: false };
  let url: URL | undefined;
  try { url = new URL(connection.baseUrl); } catch {}
  const host = url?.hostname || '', official = url?.protocol === 'https:' && !url.port && !url.username && !url.password && !url.hash;
  const providerId = official && host === 'api.openai.com' && ['openai_chat', 'openai_responses'].includes(connection.provider) ? 'openai'
    : official && host === 'api.anthropic.com' && connection.provider === 'anthropic' ? 'anthropic'
    : official && host === 'generativelanguage.googleapis.com' && connection.provider === 'gemini' ? 'gemini' : 'custom';
  const local = ['http:', 'https:'].includes(url?.protocol || '') && (host === 'localhost' || host === '[::1]' || /^127(?:\.\d{1,3}){3}$/.test(host));
  return { kind: providerId !== 'custom' ? 'official_api' : local ? 'local_gateway' : 'third_party_gateway', providerId, authentication: 'api_key', externalRelayEligible: true };
}
export function aiServiceBrand(connection: AIRecord): string {
  const source = aiConnectionSource(connection);
  const brands: Record<string, string> = { openai: 'OpenAI', anthropic: 'Anthropic', gemini: 'Google Gemini', aws_bedrock: 'AWS Bedrock', chatgpt: 'ChatGPT' };
  return brands[source.providerId] || (source.kind === 'local_gateway' ? '本地服务' : '自定义服务');
}
export function aiSourceLabel(connection: AIRecord): string {
  return aiSourceGroups.find(group => group.kind === aiConnectionSource(connection).kind)?.label || '来源待核对';
}
export function aiAccountStatus(account: AIRecord, now = Date.now()): string {
  if (account.enabled === false) return '已停用';
  if (!['connected', 'cooldown'].includes(account.status)) return accountStatusText(account.status);
  if (account.usageLimited === true || aiObject(aiObject(account.source).quota).state === 'limited') return '用量受限';
  if (typeof account.cooldownUntil === 'number' && account.cooldownUntil > now) return '暂时限流';
  if (account.error === 'REFRESH_VERIFICATION_PENDING') return '授权待核对';
  if (account.planUsageEnabled === false) return '待授权模型使用';
  if (typeof account.expiresAt === 'number' && account.expiresAt > 0 && account.expiresAt <= now) return '授权待续期';
  return account.status === 'connected' ? '已登录' : '状态待刷新';
}
export function aiCredentialStatus(connection: AIRecord): string {
  if (connection.enabled === false) return '已停用';
  if (connection.credentialStatus === 'expired') return '凭据已过期';
  if (connection.credentialStatus === 'not_configured' || !connection.hasApiKey && !connection.hasAwsSession) return '待配置凭据';
  return '凭据已保存';
}
export function aiCatalogCurrent(value: any, connection: AIRecord): boolean {
  const snapshot = aiObject(value), original = aiObject(snapshot.connection);
  return snapshot.format === 'pocketworld.connection-catalog.v1' && original.id === connection.id && original.revision === connection.revision && Array.isArray(snapshot.models);
}
export function aiApiCandidates(value: any, connections: AIRecord[]): AIRecord[] {
  return aiRows(value).filter(row => row.available === true && typeof row.connectionRevision === 'number' && row.connectionRevision > 0 &&
    aiText(row.connectionId) && !row.connectionId.startsWith('account:') && aiText(row.modelId) &&
    connections.some(connection => connection.id === row.connectionId && connection.revision === row.connectionRevision && connection.enabled !== false && connection.provider !== 'bedrock_anthropic'));
}
