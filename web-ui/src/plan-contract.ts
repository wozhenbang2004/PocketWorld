type Data = Record<string, any>;
export type PlanStepDraft = { goal: string; executor: string; projectId: string; path: string; heading: string; minimumBytes: string; dependencies: Array<{ step: number; outcome: string }>; remoteWaitIndex: string; businessWaitIndex: string };
export const newPlanStep = (dependencies: PlanStepDraft['dependencies'] = []): PlanStepDraft => ({ goal: '', executor: 'phone', projectId: '', path: '', heading: '', minimumBytes: '16', dependencies, remoteWaitIndex: '-1', businessWaitIndex: '-1' });
function integer(value: string, min: number, max: number, label: string): number { const n = Number(value); if (value.trim() === '' || !Number.isSafeInteger(n) || n < min || n > max) throw new Error(label); return n; }
export function planStepInput(draft: PlanStepDraft, index: number, sources: Data = {}): Data {
  if (!draft.goal.trim() || draft.goal.length > 4000) throw new Error('请填写 1–4000 字的步骤目标。');
  if (!['phone', 'workspace'].includes(draft.executor)) throw new Error('请选择真实执行位置。');
  if (new Set(draft.dependencies.map(ref => ref.step)).size !== draft.dependencies.length || draft.dependencies.some(ref => !Number.isInteger(ref.step) || ref.step < 0 || ref.step >= index || !['completed', 'failed', 'cancelled', 'settled'].includes(ref.outcome))) throw new Error('前置条件必须唯一地引用同一目标的更早步骤。');
  if (draft.executor === 'workspace' && (!draft.projectId || !draft.path.trim() || !draft.heading.trim())) throw new Error('请指定原项目、文件路径和用于核验的完整首行。');
  const reference = (value: string, collection: string): Data | 'preserve' | null => {
    const at = integer(value, -2, 100, '请重新选择原前置来源。');
    if (at === -1) return null;
    if (at === -2) return 'preserve';
    const original = sources[collection]?.[at]?.reference;
    if (!original || typeof original !== 'object') throw new Error('原前置来源已不可核对，没有换成其它任务。');
    return structuredClone(original);
  };
  const { remoteWaitIndex, businessWaitIndex, ...input } = draft;
  return { ...input, minimumBytes: integer(draft.minimumBytes, 1, 1000000, '文件最小大小应为 1–1000000 字节。'), remoteWait: reference(remoteWaitIndex, 'remoteWaitCandidates'), businessWait: reference(businessWaitIndex, 'businessWaitCandidates'), dependencies: draft.dependencies.map(ref => ({ ...ref })) };
}
export function planCreateInput(goal: string, delay: string, steps: PlanStepDraft[], sources: Data = {}): Data {
  if (!goal.trim() || goal.length > 4000 || steps.length < 1 || steps.length > 50) throw new Error('填写完整目标，并保留 1–50 个步骤。');
  if (steps.some(step => step.remoteWaitIndex === '-2' || step.businessWaitIndex === '-2')) throw new Error('新目标必须明确选择现有来源。');
  return { goal, delayMinutes: integer(delay, 0, 525600, '开始时间应为 0–525600 分钟之后。'), steps: steps.map((step, index) => planStepInput(step, index, sources)) };
}
export function planStepDraft(plan: Data, step: Data, snapshot: Data): PlanStepDraft {
  const rows: Data[] = plan.steps || [], index = rows.findIndex(row => row.id === step.id);
  if (index < 0 || !Array.isArray(step.dependencies)) throw new Error('原步骤与依赖不可核对，没有删除任何条件。');
  const dependencies = step.dependencies.map((ref: Data) => {
    const earlier = rows.findIndex(row => row.id === ref.stepId);
    if (earlier < 0 || earlier >= index || !['completed', 'failed', 'cancelled', 'settled'].includes(ref.outcome)) throw new Error('原前置条件不可核对，不能省略后保存。');
    return { step: earlier, outcome: ref.outcome };
  });
  const candidate = (field: string, collection: string): string => {
    if (!step[field]) return '-1';
    const found = (snapshot[collection] || []).findIndex((row: Data) => JSON.stringify(row.reference) === JSON.stringify(step[field]));
    return String(found < 0 ? -2 : found);
  };
  return { goal: step.goal || '', executor: step.executor || 'phone', projectId: step.verification?.projectId || '', path: step.verification?.path || '', heading: step.verification?.requiredHeading || '', minimumBytes: String(step.verification?.minBytes || 16), dependencies, remoteWaitIndex: candidate('remoteWait', 'remoteWaitCandidates'), businessWaitIndex: candidate('businessWait', 'businessWaitCandidates') };
}
export function planMutationReceipt(value: Data, command: string): Data {
  if (value?.confirmed !== true || value.command !== command || value.result?.command !== command || !Array.isArray(value.snapshot?.plans)) throw new Error('尚未取得这次计划操作的完整原生回执，请核对原目标。');
  return value;
}
export const planState = (value: string) => ({ completed: '目标已按标准完成', finished_with_failures: '本轮结束，有步骤失败', paused: '计划已暂停', cancelled: '计划已取消', review_required: '修改后待审阅', waiting_for_owner_start: '等你开始', waiting_for_original_task: '工作正在处理或等待核对', scheduled: '等待计划时间', ready_for_host_check: '等待启动条件满足', blocked: '需要处理阻塞' } as Data)[value] || '尚未授权执行';
