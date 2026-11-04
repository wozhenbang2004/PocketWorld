import test from 'node:test';
import assert from 'node:assert/strict';
import { newPlanStep, planStepInput, planCreateInput, planStepDraft, planMutationReceipt } from '../src/plan-contract.ts';

test('only distinct earlier steps can control success or failure branches', () => {
  const first = { ...newPlanStep(), goal: '读取原文件' }, second = { ...newPlanStep([{ step: 0, outcome: 'failed' }]), goal: '请主人核对' };
  const input = planCreateInput('交付已核对成果', '0', [first, second]);
  assert.deepEqual(input.steps[1].dependencies, [{ step: 0, outcome: 'failed' }]);
  assert.equal(input.settings, undefined); assert.equal(input.start, undefined);
  for (const dependencies of [[{ step: 1, outcome: 'completed' }], [{ step: 0, outcome: 'unknown' }], [{ step: 0, outcome: 'completed' }, { step: 0, outcome: 'failed' }]]) assert.throws(() => planStepInput({ ...second, dependencies }, 1));
});

test('workspace steps require explicit file evidence and preserve original dependencies', () => {
  const plan = { steps: [{ id: 'a' }, { id: 'b' }] }, step = { id: 'b', goal: '写原成果', executor: 'workspace', verification: { projectId: 'p', path: 'answer.md', requiredHeading: '# 核对结果', minBytes: 256 }, dependencies: [{ stepId: 'a', outcome: 'settled' }] };
  const draft = planStepDraft(plan, step, {}), input = planStepInput(draft, 1);
  assert.equal(input.minimumBytes, 256); assert.equal(input.path, 'answer.md'); assert.deepEqual(input.dependencies, [{ step: 0, outcome: 'settled' }]);
  assert.throws(() => planStepInput({ ...draft, heading: '' }, 1));
  assert.throws(() => planStepDraft(plan, { ...step, dependencies: [{ stepId: 'missing', outcome: 'completed' }] }, {}));
});

test('candidate selections carry exact frozen native references, never a moving list index', () => {
  const original = { taskId: 'old-task', runId: 'old-run', coordinatorEpoch: 4 }, sources = { remoteWaitCandidates: [{ reference: original }] };
  const draft = { ...newPlanStep(), goal: '等原电脑任务', remoteWaitIndex: '0' };
  const input = planStepInput(draft, 0, sources);
  assert.deepEqual(input.remoteWait, original); assert.equal(input.remoteWaitIndex, undefined);
  sources.remoteWaitCandidates[0].reference.runId = 'successor'; assert.equal(input.remoteWait.runId, 'old-run');
  assert.throws(() => planStepInput(draft, 0, {}));
});

test('unlisted original external dependencies remain explicit and cannot be silently removed', () => {
  const step = { id: 'a', goal: '保持原条件', dependencies: [], remoteWait: { taskId: 'original' } }, plan = { steps: [step] };
  const draft = planStepDraft(plan, step, {});
  assert.equal(draft.remoteWaitIndex, '-2'); assert.equal(planStepInput(draft, 0).remoteWait, 'preserve');
  assert.throws(() => planCreateInput('新目标', '0', [draft]));
});

test('only a matching native plan receipt and current snapshot close a write', () => {
  assert.throws(() => planMutationReceipt({ accepted: true }, 'start'));
  assert.throws(() => planMutationReceipt({ confirmed: true, command: 'start', result: { command: 'resume' }, snapshot: { plans: [] } }, 'start'));
  assert.throws(() => planMutationReceipt({ confirmed: true, command: 'start', result: { command: 'start' } }, 'start'));
  const receipt = { confirmed: true, command: 'start', result: { command: 'start' }, snapshot: { plans: [] } };
  assert.equal(planMutationReceipt(receipt, 'start'), receipt);
});
