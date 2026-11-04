import test from 'node:test';
import assert from 'node:assert/strict';
import { acceptPluginReview, acceptPluginView, catalogReceiptResult, catalogReview, catalogUnknown, confirmedPluginResult, confirmedSourceConfiguration, jsPluginIdentity, pluginIdentity, sameJsIdentity, samePluginIdentity, sourceConfiguration, sourceDraft } from '../src/plugins-workspace.ts';

const plugin = (extra: any = {}) => ({ id: 'weather', installationId: 'install-a', stateRevision: 5, grantsDigest: 'a'.repeat(64), manifest: { id: 'weather', name: '天气', version: '1', tools: [{ name: 'read', kind: 'http', effect: 'read' }] }, enabled: false, grants: [], ...extra });
const js = (extra: any = {}) => ({ id: 'notes', installationId: 'js-a', approvalRevision: 8, version: '1', sourceSha256: 'b'.repeat(64), approved: false, enabled: false, ...extra });
const view = (extra: any = {}) => ({ pageRequestId: 'page-a', revision: 4, requestId: 'read-a', status: 'ready', busy: false, unresolved: false, config: { revision: 'config-a', sources: [] }, catalog: {}, installed: { plugins: [plugin()] }, jsInstalled: { plugins: [js()] }, review: {}, result: {}, ...extra });
const install = () => ({ kind: 'install', reviewHandle: 'review-a', data: { id: 'weather', expectedInstallationId: 'install-a', expectedStateRevision: 5, targetVersion: '2', manifest: { id: 'weather', version: '2', tools: [] } } });

test('another native page, an old revision or a queued acknowledgement cannot become an installed snapshot', () => {
  const current = view();
  for (const value of [{ accepted: true }, view({ pageRequestId: 'page-b' }), view({ revision: 3 }), view({ revision: '5' }), view({ unresolved: undefined }), view({ jsInstalled: null }), view({ status: 'superseded' })]) assert.equal(acceptPluginView(current, value), null);
  assert.equal(acceptPluginView(current, view({ revision: 5 }))?.revision, 5);
  assert.equal(acceptPluginView(current, view({ pageRequestId: 'page-b' }), true)?.pageRequestId, 'page-b');
});

test('reviews bind the original installation, monotonic state and exact grants rather than a plugin name', () => {
  const original = plugin();
  assert.deepEqual(pluginIdentity(original), { id: 'weather', installationId: 'install-a', stateRevision: 5 });
  for (const changed of [plugin({ installationId: 'install-b' }), plugin({ stateRevision: 6 }), plugin({ stateRevision: '5' }), plugin({ grantsDigest: 'c'.repeat(64) }), plugin({ manifest: { version: '2' } })]) {
    assert.equal(samePluginIdentity(original, changed), false);
    assert.throws(() => acceptPluginReview({ kind: 'enabled', reviewHandle: 'r', data: changed }, 'enabled', original));
  }
  assert.throws(() => pluginIdentity(plugin({ stateRevision: Number.MAX_SAFE_INTEGER + 1 })));
  assert.equal(acceptPluginReview({ kind: 'enabled', reviewHandle: 'r', data: { ...original, nextEnabled: true } }, 'enabled', original).kind, 'enabled');
  assert.throws(() => acceptPluginReview({ ...install(), data: { ...install().data, expectedInstallationId: 'other', restoration: true } }, 'restore', original));
});

test('a new install clears an editor only after its actual new identity, version, disabled state and empty grants match', () => {
  const reviewed = install(), actual = { confirmed: true, status: 'confirmed', replayAllowed: false, kind: 'install', id: 'weather', previousInstallationId: 'install-a', previousStateRevision: 5, result: plugin({ installationId: 'install-b', stateRevision: 0, manifest: { version: '2' } }) };
  assert.equal(confirmedPluginResult(reviewed, actual).installationId, 'install-b');
  for (const bad of [{ accepted: true }, { ...actual, status: 'unknown' }, { ...actual, confirmed: false }, { ...actual, previousInstallationId: 'other' }, { ...actual, previousStateRevision: 4 }, { ...actual, result: { ...actual.result, installationId: 'install-a' } }, { ...actual, result: { ...actual.result, enabled: true } }, { ...actual, result: { ...actual.result, grants: ['tool:read'] } }, { ...actual, result: { ...actual.result, manifest: { version: '3' } } }]) assert.throws(() => confirmedPluginResult(reviewed, bad));
});

test('stop, grant and remove receipts must describe the specifically reviewed change', () => {
  const original = plugin(), base = { confirmed: true, status: 'confirmed', replayAllowed: false, id: 'weather', previousInstallationId: 'install-a', previousStateRevision: 5 };
  assert.equal(confirmedPluginResult({ kind: 'enabled', data: { ...original, nextEnabled: false } }, { ...base, kind: 'enabled', result: original }).enabled, false);
  const grant = { kind: 'grant', data: { ...original, selectedTool: { name: 'read' }, nextGranted: true } };
  assert.throws(() => confirmedPluginResult(grant, { ...base, kind: 'grant', result: plugin({ stateRevision: 6, grants: ['tool:write'] }) }));
  assert.deepEqual(confirmedPluginResult(grant, { ...base, kind: 'grant', result: plugin({ stateRevision: 6, grants: ['tool:read'] }) }).grants, ['tool:read']);
  assert.throws(() => confirmedPluginResult({ kind: 'remove', data: original }, { ...base, kind: 'remove', result: { removed: false } }));
});

test('a lost journal receipt stays unresolved when querying current installations', () => {
  const pending = view({ operationReceipt: { requestId: 'write-a', command: 'submit', recorded: true, receipt: { status: 'unknown', replayAllowed: false } } });
  assert.equal(catalogUnknown(pending), true);
  assert.equal(catalogUnknown(view({ operationReceipt: { recorded: false, receipt: { status: 'installed_unapproved' } } })), true);
  assert.equal(catalogReview({ ...pending, reviewHandle: 'r', reviewKind: 'js_install', review: { ...js(), source: 'export const x = 1' } }), null);
  assert.equal(catalogUnknown(view()), false);
});

test('JS approvals retain exact source, install and approval revision, and approval never implies enabling', () => {
  const source = js(), reviewed = { kind: 'js_permission', pageRequestId: 'page-a', revision: 4, data: source };
  assert.deepEqual(jsPluginIdentity(source), { id: 'notes', installationId: 'js-a', approvalRevision: 8 });
  assert.equal(sameJsIdentity(source, js({ approvalRevision: 9 })), false);
  assert.equal(sameJsIdentity(source, js({ sourceSha256: 'c'.repeat(64) })), false);
  const saved = js({ approvalRevision: 9, approved: true }), receipt = { status: 'saved', plugin: saved };
  const result = view({ revision: 5, requestId: 'write-a', status: 'completed', operationReceipt: { requestId: 'write-a', command: 'jsChange', recorded: true, receipt } });
  assert.equal(catalogReceiptResult(result, reviewed, 'approve').plugin.enabled, false);
  assert.throws(() => catalogReceiptResult(result, reviewed, 'enable'));
  assert.throws(() => catalogReceiptResult(result, reviewed, 'install'));
  for (const changed of [js({ installationId: 'js-b', approvalRevision: 9, approved: true }), js({ approvalRevision: 8, approved: true }), js({ version: '2', approvalRevision: 9, approved: true })]) assert.throws(() => catalogReceiptResult({ ...result, operationReceipt: { ...result.operationReceipt, receipt: { ...receipt, plugin: changed } } }, reviewed, 'approve'));
});

test('catalog installation receipts cannot cross pages, requests, source bytes or new installations', () => {
  const source = { ...js(), current: js(), source: 'export const name = "notes"' }, reviewed = { kind: 'js_install', pageRequestId: 'page-a', revision: 4, data: source };
  const installed = { status: 'installed_unapproved', installed: true, plugin: js({ installationId: 'js-new', approvalRevision: 9 }) };
  const result = view({ revision: 5, requestId: 'write-a', status: 'completed', operationReceipt: { requestId: 'write-a', command: 'submit', recorded: true, receipt: installed } });
  assert.equal(catalogReceiptResult(result, reviewed, 'install').plugin.installationId, 'js-new');
  for (const changed of [{ ...result, pageRequestId: 'page-b' }, { ...result, revision: 4 }, { ...result, requestId: 'write-b' }, { ...result, operationReceipt: { ...result.operationReceipt, recorded: false } }, { ...result, operationReceipt: { ...result.operationReceipt, receipt: { ...installed, plugin: js() } } }, { ...result, operationReceipt: { ...result.operationReceipt, receipt: { ...installed, plugin: { ...installed.plugin, sourceSha256: 'c'.repeat(64) } } } }]) assert.throws(() => catalogReceiptResult(changed, reviewed, 'install'));
});

test('source editing keeps the original list and revision, while queued or foreign saves preserve the draft', () => {
  const source = { id: 'mine', label: '自己的目录', catalogUrl: 'https://example.test/catalog.json', enabled: true };
  const config = { revision: 'config-a', sources: [source] }, before = JSON.stringify(config), draft = { ...sourceDraft(source), label: '新名称' };
  const input = sourceConfiguration(config, draft, 'mine');
  assert.equal(JSON.stringify(config), before); assert.equal(input.expectedRevision, 'config-a'); assert.equal(input.sources[0].label, '新名称');
  const result = view({ requestId: 'source-a', config: { revision: 'config-b', sources: input.sources }, operationReceipt: { requestId: 'source-a', command: 'configure', recorded: true, receipt: { revision: 'config-b', sources: input.sources } } });
  assert.equal(confirmedSourceConfiguration(input, result), true);
  assert.equal(confirmedSourceConfiguration(input, { accepted: true }), false);
  assert.equal(confirmedSourceConfiguration(input, { ...result, requestId: 'another' }), false);
  assert.equal(confirmedSourceConfiguration(input, { ...result, config: { revision: 'config-b', sources: [source] } }), false);
  for (const changed of [{ ...draft, id: 'different' }, { ...draft, catalogUrl: 'https://user:secret@example.test/catalog' }, { ...draft, catalogUrl: 'http://example.test/catalog' }, { ...draft, catalogUrl: 'https://example.test/catalog?token=secret' }, { ...draft, pinnedCatalogSha256: 'bad' }]) assert.throws(() => sourceConfiguration(config, changed, 'mine'));
  assert.deepEqual(sourceConfiguration(config, draft, 'mine', true).sources, []);
});
