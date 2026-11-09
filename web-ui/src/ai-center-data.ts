export type AIRecord = Record<string, any>;
export const aiObject = (value: any): AIRecord => value && typeof value === 'object' && !Array.isArray(value) ? value : {};
export const aiRows = (value: any): AIRecord[] => Array.isArray(value) ? value.filter(row => row && typeof row === 'object' && !Array.isArray(row)) : [];
export const aiText = (value: any): string => typeof value === 'string' ? value : '';
export const aiConnections = (value: AIRecord): AIRecord[] => aiRows(Array.isArray(value.connections) ? value.connections : aiObject(value.connections).connections);
export function aiServiceBrand(connection: AIRecord): string {
  if (connection.provider === 'bedrock_anthropic') return 'AWS Bedrock';
  let host = '';
  try { host = new URL(connection.baseUrl).hostname; } catch {}
  if (host === 'api.openai.com') return 'OpenAI';
  if (host === 'api.anthropic.com') return 'Anthropic';
  if (host === 'generativelanguage.googleapis.com') return 'Google Gemini';
  return '自定义服务';
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
