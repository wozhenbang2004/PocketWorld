import test from 'node:test';
import assert from 'node:assert/strict';
import { modelConnectionCommand, connectionProfilePayload } from '../src/forms-library/host-contracts.ts';

test('adding and testing a new connection never reuse the current profile identity', () => {
  const existing = { id: 'saved-profile', revision: 7 };
  assert.deepEqual(modelConnectionCommand(true, false, existing), { action: 'models.connection.add', identity: { id: '', expectedRevision: 0 } });
  assert.deepEqual(modelConnectionCommand(true, true, existing), { action: 'models.connection.test', identity: { id: '', expectedRevision: 0 } });
});

test('editing and testing retain the exact original profile revision', () => {
  const existing = { id: 'profile-a', revision: 7 };
  assert.deepEqual(modelConnectionCommand(false, false, existing), { action: 'models.connection.edit', identity: { id: 'profile-a', expectedRevision: 7 } });
  assert.deepEqual(modelConnectionCommand(false, true, existing).identity, { id: 'profile-a', expectedRevision: 7 });
  assert.throws(() => modelConnectionCommand(false, false, undefined), /原连接/);
  assert.throws(() => modelConnectionCommand(false, true, { id: 'profile-a', revision: 0 }), /原连接/);
});

test('Companion editing pins the original native binding and never spreads returned credentials', () => {
  const original = { id: 'computer', bindingHash: 'a'.repeat(64), token: 'must-not-copy', revision: 9 };
  const draft = { name: ' Desk ', baseUrl: 'https://desk.test', token: '', id: 'other', bindingHash: 'b'.repeat(64), hasToken: true };
  assert.deepEqual(connectionProfilePayload('remote', draft, original), { id: 'computer', name: 'Desk', expectedBindingHash: 'a'.repeat(64), baseUrl: 'https://desk.test' });
  assert.equal(connectionProfilePayload('remote', draft, {}).expectedBindingHash, '');
  assert.equal(connectionProfilePayload('remote', draft, {}).id, undefined);
});

test('SSH editing retains the original binding and only sends the selected credential kind', () => {
  const payload = connectionProfilePayload('ssh', { name: 'Host', host: 'host.test', port: '22', username: 'owner', workspace: '/work', authType: 'password', password: 'replacement', privateKey: 'old-draft', passphrase: 'old-draft' }, { id: 'ssh-a', bindingHash: 'c'.repeat(64) });
  assert.equal(payload.expectedBindingHash, 'c'.repeat(64));
  assert.equal(payload.password, 'replacement');
  assert.equal(payload.privateKey, undefined); assert.equal(payload.passphrase, undefined);
  assert.equal(payload.port, 22);
});
