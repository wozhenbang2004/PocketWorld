import test from 'node:test';
import assert from 'node:assert/strict';
import { aiApiCandidates, aiCatalogCurrent, aiConnections, aiCredentialStatus, aiServiceBrand } from '../src/ai-center-data.ts';

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
