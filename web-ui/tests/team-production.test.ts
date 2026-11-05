import assert from 'node:assert/strict';
import test from 'node:test';
import { resetTeamProductionAcknowledgments, teamDefinitionPayload, teamProductionEditorMember, teamProductionFiles,
  teamProductionHarnessReady, teamProductionSelection, TEAM_PROJECT_PRODUCTION_MODE } from '../src/team-production.ts';

const snapshot = { sourceTaskId: '11111111-1111-4111-8111-111111111111', snapshotId: '22222222-2222-4222-8222-222222222222', profileId: 'computer-a', environmentId: 'workspace-a', files: ['src/main.ts'] };
const sourcePackage = { id: '33333333-3333-4333-8333-333333333333', sha256: 'a'.repeat(64), manifestIdentity: 'b'.repeat(64) };
const sources = { snapshots: [snapshot], packages: [sourcePackage] };
const catalog = { writeAssignmentsConfigured: true, selectedFileRemoteProductionConfigured: true, productionSources: sources };
const limits = { maxWallTimeSeconds: 120, maxOutputBytes: 65536, maxContributionBytes: 131072 };
const original = { id: 'author', name: '作者', role: '修改代码', runner: 'codex_app_server', model: 'real-model', device: { profileId: 'computer-a', environmentId: 'workspace-a' },
  writeFiles: ['src/main.ts'], remoteProduction: { sourceTaskId: snapshot.sourceTaskId, snapshotId: snapshot.snapshotId, limits, internalInferenceBillingUnboundedAcknowledged: true } };
const editor = (member = teamProductionEditorMember(original)) => ({ name: '项目作者', mode: 'produce', leaderId: member.id, members: [member] });

test('editing an existing production definition preserves exact source, file range and original limits without sharing mutable drafts', () => {
  const member = teamProductionEditorMember(original);
  assert.equal(member.writeFiles, 'src/main.ts');
  const value = teamDefinitionPayload(editor(member), catalog).members[0];
  assert.deepEqual(value.remoteProduction, original.remoteProduction);
  assert.deepEqual(value.writeFiles, original.writeFiles);
  assert.equal(value.production, undefined);
  member.production.wallSeconds = '300'; member.device.environmentId = 'other';
  assert.equal(original.remoteProduction.limits.maxWallTimeSeconds, 120);
  assert.equal(original.device.environmentId, 'workspace-a');
});

test('a source snapshot can only bind its original computer, environment and task', () => {
  const member = teamProductionEditorMember(original);
  for (const device of [{ profileId: 'computer-b', environmentId: 'workspace-a' }, { profileId: 'computer-a', environmentId: 'workspace-b' }])
    assert.throws(() => teamProductionSelection({ ...member, device }, sources), /原任务文件快照/);
  assert.throws(() => teamProductionSelection({ ...member, production: { ...member.production, sourceTaskId: 'other' } }, sources), /原任务文件快照/);
  assert.throws(() => teamProductionSelection(member, { snapshots: [] }), /原任务文件快照/);
});

test('full project configuration retains the exact package pin and requires all independent acknowledgments', () => {
  const remoteProduction = { mode: TEAM_PROJECT_PRODUCTION_MODE, packageReference: sourcePackage, limits,
    allowCommands: true, sameUserExecutionRiskAcknowledged: true, privateSourceTransferAcknowledged: true, internalInferenceBillingUnboundedAcknowledged: true };
  const member = teamProductionEditorMember({ ...original, remoteProduction });
  assert.deepEqual(teamProductionSelection(member, sources), remoteProduction);
  for (const key of ['billingAcknowledged', 'commandsAcknowledged', 'sourceTransferAcknowledged'])
    assert.throws(() => teamProductionSelection({ ...member, production: { ...member.production, [key]: false } }, sources), /确认/);
  for (const key of ['packageId', 'packageSha', 'packageManifest'])
    assert.throws(() => teamProductionSelection({ ...member, production: { ...member.production, [key]: 'changed' } }, sources), /准确完整源项目包/);
  const reset = resetTeamProductionAcknowledgments(member.production);
  assert.equal(reset.billingAcknowledged, false); assert.equal(reset.commandsAcknowledged, false); assert.equal(reset.sourceTransferAcknowledged, false);
  assert.equal(reset.packageSha, sourcePackage.sha256);
});

test('remote limits remain integer bounds and distinguish selected files from complete projects', () => {
  const member = teamProductionEditorMember(original);
  for (const change of [{ wallSeconds: '9' }, { wallSeconds: '901' }, { wallSeconds: '1.2' }, { outputBytes: '196609' }, { outputBytes: '1e4' }, { contributionBytes: '524289' }])
    assert.throws(() => teamProductionSelection({ ...member, production: { ...member.production, ...change } }, sources), /整数/);
  const full = { ...member, production: { ...member.production, sourceMode: 'project', packageId: sourcePackage.id, packageSha: sourcePackage.sha256,
    packageManifest: sourcePackage.manifestIdentity, commandsAcknowledged: true, sourceTransferAcknowledged: true, contributionBytes: '16777216' } };
  assert.equal(teamProductionSelection(full, sources).limits.maxContributionBytes, 16777216);
  assert.throws(() => teamProductionSelection({ ...full, production: { ...full.production, contributionBytes: '16777217' } }, sources), /整数/);
});

test('file assignments are bounded, relative and exclusive across all team members', () => {
  for (const paths of ['', '../escape', '/absolute', 'a\\b', 'a:b', './a', 'a//b', 'a\u0000b', 'a\na', Array.from({ length: 9 }, (_, i) => `file${i}`).join('\n')])
    assert.throws(() => teamProductionFiles(paths));
  const local = teamProductionEditorMember({ id: 'local', name: '检查', role: '检查代码', runner: 'model', writeFiles: ['src/main.ts'] });
  assert.throws(() => teamDefinitionPayload({ ...editor(), members: [teamProductionEditorMember(original), local] }, catalog), /同时负责/);
  local.writeFiles = 'README.md';
  assert.equal(teamDefinitionPayload({ ...editor(), members: [teamProductionEditorMember(original), local] }, catalog).members[1].remoteProduction, undefined);
});

test('ordinary teams carry no file-production fields and unsupported hosts or workers cannot review production', () => {
  const member = teamProductionEditorMember(original);
  const ordinary = teamDefinitionPayload({ ...editor(member), mode: 'collaborate' }, {}).members[0];
  assert.equal(ordinary.writeFiles, undefined); assert.equal(ordinary.remoteProduction, undefined); assert.equal(ordinary.production, undefined);
  assert.throws(() => teamDefinitionPayload(editor(), { ...catalog, writeAssignmentsConfigured: false }), /宿主/);
  assert.throws(() => teamDefinitionPayload(editor(), { ...catalog, selectedFileRemoteProductionConfigured: false }), /宿主/);
  assert.throws(() => teamDefinitionPayload(editor({ ...member, runner: 'pi' }), catalog), /实际执行器/);
  assert.throws(() => teamDefinitionPayload(editor({ ...member, runner: 'phone_agent' }), catalog), /手机/);
  assert.equal(teamProductionHarnessReady({ id: 'pi', capabilities: { assignmentWorkspace: true } }, 'snapshot'), false);
  assert.equal(teamProductionHarnessReady({ id: 'claude', capabilities: { assignmentWorkspace: true } }, 'snapshot'), true);
  assert.equal(teamProductionHarnessReady({ id: 'claude', capabilities: { assignmentWorkspace: true } }, 'project'), false);
  assert.equal(teamProductionHarnessReady({ id: 'acp_configured', capabilities: { projectProduction: true } }, 'project'), true);
  assert.equal(teamProductionHarnessReady({ id: 'muse_msp' }, 'project'), false);
});
