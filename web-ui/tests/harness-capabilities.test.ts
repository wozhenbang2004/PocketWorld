import assert from 'node:assert/strict';
import test from 'node:test';
import { harnessCapabilityItems, harnessOptions, harnessProbeReady, harnessSelection, harnessWorkerLabel, isConfiguredHarness, isTeamHarness } from '../src/harness-capabilities.ts';
import { teamEditorMember } from '../src/team-phone.ts';

const worker = { id: 'acp_owner_tool', label: 'Owner tool', configurationRevision: '1'.repeat(64) };
const probe = { worker: worker.id, available: true, protocolVerified: true, configurationRevision: worker.configurationRevision, capabilityRevision: '2'.repeat(64) };
test('configured identities are independent of a fixed list of brands, and labels come from the directory', () => {
  assert.ok(isConfiguredHarness(worker.id)); assert.ok(isTeamHarness(worker.id)); assert.ok(isTeamHarness('muse_msp'));
  assert.equal(isConfiguredHarness('acp_../../command'), false);
  assert.equal(isConfiguredHarness('acp_7bad'), false); assert.equal(isConfiguredHarness('acp_' + 'a'.repeat(66)), false);
  assert.equal(isTeamHarness('command'), false); assert.equal(isTeamHarness('gemini_acp'), false);
  assert.equal(harnessWorkerLabel(worker), 'Owner tool');
});
test('selection cannot reuse a probe for a changed config, different worker or unverified protocol', () => {
  assert.ok(harnessProbeReady(probe, worker));
  for (const changed of [{ ...probe, protocolVerified: false }, { ...probe, available: false }, { ...probe, worker: 'acp_other' }, { ...probe, configurationRevision: '3'.repeat(64) }, { ...probe, capabilityRevision: '' }]) {
    assert.equal(harnessProbeReady(changed, worker), false); assert.throws(() => harnessSelection(changed, worker));
  }
  assert.equal(harnessProbeReady(probe, { ...worker, configurationRevision: '4'.repeat(64) }), false);
});
test('model and mode choices use reported session options and preserve unavailable capabilities', () => {
  assert.deepEqual(harnessOptions({ models: { availableModels: [{ modelId: 'actual', name: 'Actual model' }] } }, 'models'), [{ id: 'actual', label: 'Actual model' }]);
  assert.deepEqual(harnessOptions({ modes: { availableModes: [{ id: 'normal', name: 'Normal' }] } }, 'modes'), [{ id: 'normal', label: 'Normal' }]);
  assert.deepEqual(harnessOptions({ sessionOptions: { configOptions: [{ category: 'model', options: [{ group: 'Group', options: [{ value: 'reported', name: 'Reported' }] }] }] } }, 'models'), [{ id: 'reported', label: 'Reported' }]);
  assert.deepEqual(harnessOptions({}, 'models'), []);
  assert.deepEqual(harnessCapabilityItems({ capabilities: { supportsResume: false } }), [{ label: '恢复原会话', state: '当前不支持' }]);
  assert.deepEqual(harnessCapabilityItems({ capabilities: { turnInterrupt: true, interactiveApprovals: true, structuredUserInput: false, teamTools: true } }).map(item => item.state), ['本次已报告支持', '本次已报告支持', '当前不支持', '本次已报告支持']);
});
test('editing an external member keeps its exact binding but never forwards runtime authority', () => {
  const original = { id: 'a', name: 'Author', role: 'Review', model: 'actual', modeId: 'normal', runner: worker.id, device: { profileId: 'computer-a', environmentId: 'workspace-a', authority: 'not a config field' }, externalExecutionRiskAcknowledged: true, harnessSelection: { token: 'not forwarded' }, externalSession: { sessionId: 'not forwarded' } };
  const draft = teamEditorMember(original);
  assert.deepEqual(draft.device, { profileId: 'computer-a', environmentId: 'workspace-a' });
  assert.equal(draft.model, 'actual'); assert.equal(draft.modeId, 'normal'); assert.equal(draft.externalExecutionRiskAcknowledged, true);
  assert.equal(draft.harnessSelection, undefined); assert.equal(draft.externalSession, undefined);
  draft.device.environmentId = 'workspace-b'; assert.equal(original.device.environmentId, 'workspace-a');
});
