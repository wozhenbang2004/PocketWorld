import { useState } from 'react';
import { createRoot } from 'react-dom/client';
import { MemoryWorkspace } from '../src/MemoryWorkspace';
import { MemoryDraftReview } from '../src/MemoryDraftReview';
import { MemoryHistoryWorkspace } from '../src/MemoryHistoryWorkspace';
import { LearningWorkspace } from '../src/LearningWorkspace';
import { PlansWorkspace } from '../src/PlansWorkspace';
import { CommitmentsWorkspace } from '../src/CommitmentsWorkspace';
import ReminderWorkspace from '../src/ReminderWorkspace';
import { bridge } from '../src/bridge';
import { usePocketTypography } from '../src/usePocketTypography';
import '../src/fonts.css';
import '../src/app.css';
import '../src/library.css';
import '../src/secondary.css';
import '../src/product.css';
import '../src/workspace-refactor.css';
import '../src/presentation-theme.css';

type Data = Record<string, any>;
const query = new URLSearchParams(location.search), area = query.get('area') || 'memory';
const now = Date.UTC(2026, 10, 8, 9);
const memory = { id: 'memory-a', text: '先复现，再判断原因。\n排查问题时保留原始报错与环境信息；修复后使用相同输入再次验证。', layer: 'procedural', revision: 4, active: true, core: true, ownerId: 'owner', spaceId: 'private', consolidationBlocked: false, reason: '来自主人已确认的任务复盘', applicability: '需要定位故障并验证修复时', sourceRefs: [{ sourceId: 'source-a', revision: 2 }] };
const candidate = { ...memory, id: 'candidate-a', core: false, status: 'pending', text: '每周整理一次项目进展。\n把本周已完成、遇到的问题与下一步写在一起，保留可核对的任务来源。', recordRefs: [{ id: 'memory-a', revision: 4 }] };
const method = { ...memory, id: 'procedure-a', validationStatus: 'verified', appKnowledge: { stage: 'verified', packageName: 'dev.fixture.notes', appVersion: '42' } };
const skill = { id: 'skill-a', name: '项目周报整理', description: '按已确认来源整理进展和待办', revision: 3, digest: 'fixture-skill-digest', origin: { recordId: 'procedure-a' }, originStatus: 'current', enabled: false, resources: ['SKILL.md', 'references/checklist.md'] };
const job = { taskId: 'learning-a', runId: 'run-a', epoch: 1, taskStatus: 'awaiting_user', jobStatus: 'awaiting_user', canContinue: true, canSkipBatch: true, job: { nextIndex: 1, inputs: [{ title: '排查项目预览空白', sourceFingerprint: 'fp-a' }, { title: '补齐周报的来源与结论', sourceFingerprint: 'fp-b' }, { title: '核对发布前的构建记录', sourceFingerprint: 'fp-c' }], results: [{ candidateRef: { id: 'candidate-a', revision: 4 } }] } };
const plan = { id: 'plan-a', revision: 3, title: '整理本周项目交付', goal: '把本周变更整理为一份可核验周报，并在结果失败时保留诊断记录。', state: 'awaiting_approval', approvalValid: false, steps: [{ id: 'step-a', title: '整理已完成内容', goal: '读取当前项目的变更，整理周报。', executor: 'workspace', dueAt: now, status: 'pending', verification: { kind: 'workspace_artifact', projectId: 'project-a', path: 'reports/week.md', requiredHeading: '# 本周项目进展', minBytes: 64 } }, { id: 'step-b', title: '核对并准备交付', goal: '核对周报来源和相关任务。', executor: 'phone', dueAt: now, status: 'waiting', dependsOn: [{ stepId: 'step-a', outcome: 'completed' }] }], execution: { mode: 'manual', phoneMode: 'background', admissions: 0, maxTaskAdmissions: 4, started: false, state: 'awaiting_approval', policy: { expiresAt: now + 604800000, requiresNetwork: true, requiresCharging: false, minBatteryPercent: 20, quietHours: { start: '22:00', end: '08:00' }, zoneId: 'Asia/Shanghai' } } };
const commitment = { id: 'commitment-a', revision: 2, title: '确认项目发布结果', details: '检查发布任务的原回执，确认安装包与变更说明是否齐全。', status: 'pending', dueAt: now + 3600000, followUpAt: now, followUpDue: true, taskId: 'task-a', taskState: 'completed' };
const policy = { revision: 2, enabled: true, zoneId: 'Asia/Shanghai', quietHours: { start: '22:00', end: '08:00' }, cooldownMillis: 3600000, maxNotificationsPerDay: 4, pausedUntil: null };
const rules = [{ id: 'reminder-a', revision: 3, title: '晚间检查项目进度', enabled: true, nextAt: now + 3600000, scheduledAt: now + 3600000, manualOriginVersion: 1, kind: 'notification', schedule: { type: 'daily', time: '18:30', zone: 'Asia/Shanghai' } }, { id: 'reminder-b', revision: 2, title: '周五整理项目周报', enabled: false, nextAt: 0, manualOriginVersion: 1, kind: 'phone_goal', goal: '整理本周项目变更并核对来源', schedule: { type: 'weekly', time: '17:00', daysOfWeek: [5], zone: 'Asia/Shanghai' } }];
const receipts = [{ id: 'occurrence-a', title: '晚间检查项目进度', status: 'delivery_unknown', kind: 'notification', occurrenceAt: now, claimedAt: now + 2000, message: '通知提交结果尚未核实，请核对本机通知。' }, { id: 'occurrence-b', title: '周五整理项目周报', status: 'awaiting_user', kind: 'phone_goal', occurrenceAt: now, claimedAt: now + 1000, message: '目标已提醒，等待你查看并明确开始。' }];
const baseState = { webWorkspaceForeground: true, webWorkspaceTaskOpen: false, webWorkspaceResumeRevision: 1, tasks: [{ id: 'task-a', goal: '排查项目预览空白', status: 'completed', raw: { kind: 'assistant', runId: 'original-run', coordinatorEpoch: 1 } }], features: { workspace: { projects: [{ id: 'project-a', name: '周报项目' }] } }, moduleResults: { 'automation.receipts': { receipts } }, moduleBusy: [], moduleErrors: {} };
const calls: Data[] = [];
(bridge as any).getState = () => ({ view: { section: 'learning', resumeRevision: 1 }, state: {}, capabilities: { commands: [] }, composer: null });
(bridge as any).getDocumentEpoch = () => 1;
(bridge as any).subscribe = () => () => {};

function Fixture() {
  usePocketTypography({});
  const [state] = useState<Data>(baseState);
  const controls = (window as any).knowledgeFixture ||= { calls, failNext: query.get('fail') || '', back: null };
  const registerBack = (handler: (() => boolean) | null) => { controls.back = handler; };
  async function run(action: string, args: Data = {}): Promise<Data> {
    calls.push({ action, args });
    if (controls.failNext === action) { controls.failNext = ''; throw new Error('模拟宿主：原版本已变化，请重新核对。'); }
    switch (action) {
      case 'knowledge.assertion.prepare': return { identities: [{ choiceHandle: 'person-a', label: '项目同事', recordPreview: '独立人物，未与主人合并' }], hasMoreIdentities: false };
      case 'knowledge.assertion.review': return { reviewHandle: 'assertion-review', operation: 'assertion', before: candidate, assertion: { ...args, subject: { label: args.subject?.mode === 'owner' ? '主人' : '项目同事' } } };
      case 'knowledge.snapshot': return { records: [memory, { ...memory, id: 'memory-b', core: false, text: '项目变更说明要有来源。\n保留实际验证范围，未知结果不要当作成功。' }], candidates: [candidate], scopeName: '主人全局 · 私人', cognitionRevision: 1 };
      case 'knowledge.get': return { readHandle: 'memory-read', candidateSelected: args.candidate, ...(args.candidate ? { candidate } : { record: memory }) };
      case 'knowledge.namespaces': return { choices: [{ choiceHandle: 'scope-project', name: '周报项目' }] };
      case 'knowledge.capacity': return { candidatesUsed: 3, candidateLimit: 100 };
      case 'knowledge.history': return { entries: [{ id: 'history-candidate', text: '旧的项目整理建议', status: 'accepted' }] };
      case 'knowledge.source': return { title: '原复盘记录', text: '主人：先复现问题，记录原始报错。修复之后，用原来的输入再验证一次。', index: 0, hasMore: false };
      case 'knowledge.parent': return { record: memory };
      case 'knowledge.review': return { reviewHandle: 'memory-review', operation: args.operation, before: args.operation === 'accept' || args.operation === 'edit' ? candidate : memory, afterText: args.text || args.draft?.text, reason: args.reason, core: args.core };
      case 'memoryHistory.open': return { sessionHandle: 'history-session' };
      case 'memoryHistory.search': return { matches: [{ id: 'old-a', recordHandle: 'old-handle', textPreview: '曾采用逐项核对的排查方式', temporalStatus: 'historical' }], totalMatches: 1, unverifiedArchiveRecords: 0 };
      case 'memoryHistory.record': return { record: { ...memory, temporalStatus: 'historical', assertion: { subjectLabel: '主人', kind: 'fact', predicate: '工作方式', object: '先复现再验证' } }, readHandle: 'history-read' };
      case 'memoryHistory.cognition': return { current: [{ ...memory, readHandle: 'cognition-read', assertionStatus: 'current' }], history: [], pendingConflicts: [candidate], conflictGroups: [{ conflictHandle: 'conflict-a', records: [memory, candidate] }] };
      case 'memoryHistory.source': return { title: '原复盘记录', text: '先复现，再修复；使用相同输入验证。', verifiedOriginalSpan: true };
      case 'memoryHistory.review': return { reviewHandle: 'history-review', operation: 'correct', before: memory, afterText: args.text };
      case 'taskMemory.search': return { tasks: [{ taskId: 'task-a', goal: '排查项目预览空白', status: 'completed' }], coverage: 'full_task_store' };
      case 'taskMemory.task': return { taskHandle: 'task-read', taskId: 'task-a', goal: '排查项目预览空白', status: 'completed', referenceAvailable: true };
      case 'taskMemory.receipt': return { phase: 'provided_to_request', status: 'current', records: [{ ...memory, readHandle: 'task-memory-read', status: 'current' }] };
      case 'learning.jobs': return { jobs: [job], hasMore: false };
      case 'learning.job': return job;
      case 'learning.episode.inspect': return { title: '排查项目预览空白', text: '目标：定位预览空白问题。\n\n经过：保留原始错误，核对页面入口和构建结果；修正后使用相同输入重试。\n\n结果：项目预览恢复显示。业务成果仍须回到原任务核对。', reviewHandle: 'episode-review' };
      case 'learning.episode.retain': return { title: '排查项目预览空白', sourceHandle: 'learning-source', sourceRef: { taskId: 'task-a', revision: 1 }, learningAllowed: true };
      case 'learning.create': return job;
      case 'learning.run.review': case 'learning.skip.review': return { ...job, reviewHandle: 'learning-review', reviewMaxBatches: args.maxBatches || 3 };
      case 'experience.snapshot': return { procedures: [method], skills: [skill] };
      case 'experience.procedure': return { procedureHandle: 'procedure-read', record: method };
      case 'experience.skill': return { skillHandle: 'skill-read', skill };
      case 'experience.read': { const content = '# 项目周报整理\n\n只使用已确认的任务来源。\n\n1. 读取变更。\n2. 核对实际结果。\n3. 列出待办与未知。'; return { ...skill, path: args.path, content, nextOffset: content.length, hasMore: false }; }
      case 'experience.draft.review': return { reviewHandle: 'experience-review', operation: 'draftExperience', before: method };
      case 'experience.draft.start': return { operationHandle: 'draft-operation', settled: true };
      case 'experience.operation.query': return { settled: true, result: { draftHandle: 'draft-read', draft: { manifest: '# 项目周报整理\n\n按真实来源整理进展，保留待核对结果。', resources: { 'references/checklist.md': '核对来源与未知结果。' } }, inspection: { policy: { requiredCapabilities: ['read_file'] } } } };
      case 'experience.import.review': return { reviewHandle: 'experience-import', operation: 'importExperience', before: method };
      case 'experience.approve.review': return { reviewHandle: 'experience-approve', operation: 'approveExperience', before: skill };
      case 'experience.apps': return { apps: [{ appHandle: 'app-a', packageName: 'dev.fixture.notes', appVersion: '42', versionName: '1.0' }] };
      case 'experience.quality.list': return { applications: [{ applicationHandle: 'application-a', taskId: 'task-a', applicationState: 'completed' }], hasMore: false };
      case 'experience.policy.get': return { policyHandle: 'policy-read', policy: { enabled: false, revision: 1, packages: [] }, jobs: [] };
      case 'plans.snapshot': return { plans: [{ ...plan, totalSteps: 2, completedSteps: 0, nextStep: plan.steps[0] }], remoteWaitCandidates: [], businessWaitCandidates: [] };
      case 'plans.read': case 'plans.inspect': return { viewHandle: 'plan-read', plan };
      case 'plans.reviewApproval': return { reviewHandle: 'plan-review', command: 'approve', plan, input: { settings: { ...args.settings, expiresAt: now + 604800000 } } };
      case 'plans.reviewCreate': return { reviewHandle: 'plan-review-create', command: 'create', plan: args.plan || { ...plan, goal: args.goal }, input: args };
      case 'plans.reviewAction': return { reviewHandle: 'plan-review-action', command: args.operation, plan, input: args };
      case 'commitments.snapshot': return { commitments: [commitment], policy, completionEvidenceAvailable: true };
      case 'commitments.read': return { viewHandle: 'commitment-read', commitment };
      case 'commitments.review': { const command = ({ create: 'createCommitment', update: 'updateCommitment', complete: 'completeCommitment', snooze: 'snoozeCommitment', cancel: 'cancelCommitment' } as Data)[args.operation] || 'updateCommitment'; return { reviewHandle: 'commitment-review', command, input: { ...args, commitment: args.commitment || commitment }, details: commitment }; }
      case 'commitments.reviewPolicy': return { reviewHandle: 'policy-review', command: 'setFollowUpPolicy', input: args };
      case 'commitments.receipts': return { receipts: [{ claimId: 'claim-a', status: 'delivery_unknown', createdAt: now }] };
      case 'memoryDraft.open': case 'memoryDraft.query': {
        const intent = query.get('intent') || 'update';
        const quote = '先复现问题，再使用相同输入验证。';
        const sourceRef = { kind: 'message', sourceId: 'message-a', revision: 2, ownerId: 'owner', spaceId: 'private' };
        const evidence = { sourceRef, offset: 0, chars: quote.length, sha256: 'a'.repeat(64) };
        const after = { ...candidate, assertion: { subjectId: intent === 'create' ? 'person-a' : 'owner', subjectLabel: intent === 'create' ? '项目同事' : '主人', kind: 'inferred', predicate: '排查问题的方法', object: '先复现，再验证', condition: '需要定位故障时', eventTime: now, evidenceSpans: [evidence] }, assertionEvidence: [{ ...evidence, text: quote, verifiedOriginalSpan: true, assertionTruthVerified: false }] };
        return { handle: action === 'memoryDraft.open' ? 'draft-memory-handle' : '', proposal: { action: intent, reason: '根据本轮主人消息整理，等待你核对' }, apply: { status: action === 'memoryDraft.query' ? 'unknown' : 'pending_owner' }, review: action === 'memoryDraft.query' ? null : { before: intent !== 'create' ? { ...memory, assertion: after.assertion, assertionEvidence: after.assertionEvidence } : null, after: intent !== 'forget' ? after : null, sourceRefs: [sourceRef] } };
      }
      case 'memoryDraft.source': { const text = '先复现问题，再使用相同输入验证。'; return { title: '原主人消息', text, offset: 0, nextOffset: text.length, totalChars: text.length, hasMore: false, index: 0 }; }
      case 'memoryDraft.confirm': return { status: 'unknown', message: '模拟宿主：原修改结果尚未核实。' };
      case 'memoryDraft.cancel': return { status: 'cancelled', message: '模拟宿主：已放弃本次原提案。' };
      case 'automation.reviewSchedule': return { reviewHandle: 'reminder-review', command: 'save', intent: args.intent, rule: { title: args.draft?.title || '新提醒', kind: args.draft?.phone ? 'phone_goal' : 'notification', goal: args.draft?.goal, revision: args.revision }, preview: { occurrences: [{ local: '2026-11-09T18:30', zone: 'Asia/Shanghai', offset: '+08:00' }] } };
      case 'automation.reviewRule': return { reviewHandle: 'reminder-rule-review', command: args.command, rule: rules.find(row => row.id === args.id), enabled: args.enabled };
      default: return {};
    }
  }
  (bridge as any).command = run;
  const common = { state, supported: () => true, run, registerTransientBack: registerBack };
  return <div className="pw-app pw-redesign" style={{ height: 'auto', minHeight: '100dvh', overflow: 'visible' }}><main className="pw-secondary-scroll" style={{ width: '100%', maxWidth: 1100, margin: '0 auto', padding: 20 }}>
    {area === 'draft' ? <MemoryDraftReview target={{ taskId: 'task-a', runId: 'run-a', epoch: 1, operationId: 'proposal-a', proposalDigest: 'original-proposal-digest' }} onClose={() => calls.push({ action: 'fixture.draft.close' })}/> : area === 'memory' ? <MemoryWorkspace domain="memory" run={run} registerTransientBack={registerBack}/> : area === 'history' ? <MemoryHistoryWorkspace run={run} scopeKey="private" registerTransientBack={registerBack}/> : area === 'learning' ? <LearningWorkspace state={state} data={{}} run={run} registerTransientBack={registerBack} onReviewCandidate={target => calls.push({ action: 'fixture.candidate.navigate', args: target })}/> : area === 'plans' ? <PlansWorkspace {...common} onEditingChange={() => {}}/> : area === 'commitments' ? <CommitmentsWorkspace {...common}/> : <ReminderWorkspace {...common} data={{ rules }}/>}
  </main></div>;
}
createRoot(document.getElementById('root')!).render(<Fixture/>);
