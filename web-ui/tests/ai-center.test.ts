import test from 'node:test';
import assert from 'node:assert/strict';
import { aiAccountStatus, aiApiCandidates, aiCatalogCurrent, aiConnections, aiConnectionSource, aiCredentialStatus, aiServiceBrand, aiSourceLabel } from '../src/ai-center-data.ts';

test('service brands describe the actual endpoint rather than mistaking a compatible protocol for OpenAI', () => {
  assert.equal(aiServiceBrand({ provider: 'openai_chat', baseUrl: 'https://relay.example/v1' }), '自定义服务');
  assert.equal(aiServiceBrand({ provider: 'openai_responses', baseUrl: 'https://api.openai.com/v1' }), 'OpenAI');
  assert.equal(aiServiceBrand({ provider: 'anthropic', baseUrl: 'https://api.anthropic.com/v1' }), 'Anthropic');
  assert.equal(aiServiceBrand({ provider: 'bedrock_anthropic' }), 'AWS Bedrock');
});
test('saved credentials are never displayed as a successful inference test', () => {
  assert.equal(aiCredentialStatus({ hasApiKey: true, credentialStatus: 'saved_unverified' }), '凭据已保存');
  assert.equal(aiCredentialStatus({ hasAwsSession: true, credentialStatus: 'expired' }), '凭据已过期');
  assert.equal(aiCredentialStatus({ enabled: false, hasApiKey: true }), '已停用');
  assert.equal(aiCredentialStatus({ hasApiKey: false }), '待配置凭据');
});
test('source catalog remains bound to its exact connection and revision', () => {
  const connection = { id: 'source-a', revision: 8 };
  const catalog = { format: 'pocketworld.connection-catalog.v1', connection, models: [] };
  assert.equal(aiCatalogCurrent(catalog, connection), true);
  assert.equal(aiCatalogCurrent(catalog, { ...connection, revision: 9 }), false);
  assert.equal(aiCatalogCurrent(catalog, { ...connection, id: 'source-b' }), false);
  assert.equal(aiCatalogCurrent({ ...catalog, models: null }, connection), false);
});
test('group candidates exclude accounts, Bedrock, stale revisions and unavailable models', () => {
  const connections = [{ id: 'api-a', revision: 3, provider: 'openai_chat', enabled: true }, { id: 'bedrock-a', revision: 1, provider: 'bedrock_anthropic', enabled: true }];
  const valid = { connectionId: 'api-a', connectionRevision: 3, modelId: 'configured-model', available: true };
  const candidates = aiApiCandidates([valid, { ...valid, connectionRevision: 2 }, { ...valid, available: false }, { ...valid, connectionId: 'account:owner' }, { ...valid, connectionId: 'bedrock-a', connectionRevision: 1 }, { ...valid, connectionId: 'unknown' }], connections);
  assert.deepEqual(candidates, [valid]);
});
test('empty service snapshots stay empty and never create demonstration connections', () => {
  assert.deepEqual(aiConnections({}), []);
  assert.deepEqual(aiConnections({ connections: { connections: [] } }), []);
  const record = { id: 'actual', revision: 1 };
  assert.deepEqual(aiConnections({ connections: { connections: [record] } }), [record]);
});

test('official API identity requires the exact HTTPS endpoint and matching protocol', () => {
  const source = { provider: 'anthropic', baseUrl: 'https://api.anthropic.com/v1' };
  assert.equal(aiConnectionSource(source).kind, 'official_api');
  for (const baseUrl of ['http://api.anthropic.com/v1', 'https://api.anthropic.com.attacker.example/v1', 'https://api.anthropic.com:8443/v1', 'https://api.anthropic.com@relay.example/v1', 'https://api.anthropic.com/v1#fragment']) {
    assert.equal(aiConnectionSource({ ...source, baseUrl }).kind, 'third_party_gateway', baseUrl);
  }
  assert.equal(aiConnectionSource({ ...source, provider: 'openai_chat' }).kind, 'third_party_gateway');
  assert.equal(aiServiceBrand({ ...source, provider: 'openai_chat' }), '自定义服务');
});

test('native source identity distinguishes local, cloud and subscription scope without granting a route', () => {
  for (const baseUrl of ['http://127.0.0.1:8080/v1', 'http://localhost:11434/v1', 'http://[::1]:8080/v1']) {
    assert.equal(aiSourceLabel({ provider: 'openai_chat', baseUrl }), '本地服务');
  }
  assert.equal(aiSourceLabel({ provider: 'openai_chat', baseUrl: 'http://localhost.example/v1' }), '第三方网关');
  assert.equal(aiConnectionSource({ provider: 'bedrock_anthropic' }).externalRelayEligible, false);
  const source = { kind: 'subscription_account', providerId: 'chatgpt', authentication: 'oauth', externalRelayEligible: false, appInference: true };
  assert.equal(aiSourceLabel({ source }), '账号登录');
  assert.equal(aiConnectionSource({ source }).externalRelayEligible, false);
});

test('account summary keeps disabled, login, observed limits and refresh states distinct', () => {
  const account = { enabled: true, status: 'connected', planUsageEnabled: true, expiresAt: 2000 };
  assert.equal(aiAccountStatus(account, 1000), '已登录');
  assert.equal(aiAccountStatus({ ...account, enabled: false, usageLimited: true }, 1000), '已停用');
  assert.equal(aiAccountStatus({ ...account, status: 'reauthorization_required', usageLimited: true }, 1000), '需要重新登录');
  assert.equal(aiAccountStatus({ ...account, usageLimited: true }, 1000), '用量受限');
  assert.equal(aiAccountStatus({ ...account, cooldownUntil: 1500 }, 1000), '暂时限流');
  assert.equal(aiAccountStatus({ ...account, error: 'REFRESH_VERIFICATION_PENDING' }, 1000), '授权待核对');
  assert.equal(aiAccountStatus({ ...account, planUsageEnabled: false }, 1000), '待授权模型使用');
  assert.equal(aiAccountStatus(account, 2000), '授权待续期');
  assert.equal(aiAccountStatus({ ...account, status: 'cooldown', cooldownUntil: 500 }, 1000), '状态待刷新');
  assert.equal(aiAccountStatus({}, 1000), '状态待核对');
});
