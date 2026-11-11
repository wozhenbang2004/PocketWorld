type Data = Record<string, unknown>;
const object = (value: unknown): Data => value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Data : {};
const text = (value: unknown) => typeof value === 'string' ? value.trim() : '';
const states = ['none', 'pending', 'running', 'reported', 'needs_review', 'unavailable'] as const;
export type ButlerHandlingState = typeof states[number] | 'unread';
export type ReportReconciliationTarget = { taskId: string; runId: string; epoch: number };
export type OwnerWorkTeam = {
  teamRunId: string; taskId: string; runId: string; epoch: number;
  parentTaskId: string; parentRunId: string; parentEpoch: number; conversationId: string;
  goal: string; status: string; taskStatus: string; phase: string; updatedAt: number;
  needsAttention: boolean; active: boolean; cancelRequested: boolean; readOnly: true;
  memberCount?: number; completedMemberCount?: number; pendingMemberCount?: number;
  pendingQuestionCount?: number; attentionCount?: number;
};
const count = (value: unknown): number | undefined => Number.isSafeInteger(value) && Number(value) >= 0 ? Number(value) : undefined;

function workTeams(raw: Data, available: boolean): OwnerWorkTeam[] {
  if (!available || raw.executionRole !== 'owner_work' || !Array.isArray(raw.ownerWorkTeams) || raw.ownerWorkTeams.length > 32) return [];
  const teams: OwnerWorkTeam[] = [], seen = new Set<string>();
  for (const value of raw.ownerWorkTeams) {
    const row = object(value);
    if (row.readOnly !== true || !text(row.teamRunId) || !text(row.taskId) || row.runId !== row.teamRunId
      || row.parentTaskId !== raw.id || row.parentRunId !== raw.runId || row.parentEpoch !== raw.coordinatorEpoch
      || !text(row.conversationId) || row.conversationId !== raw.conversationId || count(row.epoch) === undefined
      || !Number.isSafeInteger(row.parentEpoch) || Number(row.parentEpoch) < 1 || count(row.updatedAt) === undefined
      || typeof row.needsAttention !== 'boolean' || typeof row.active !== 'boolean' || seen.has(text(row.teamRunId))) continue;
    seen.add(text(row.teamRunId));
    teams.push({ teamRunId: text(row.teamRunId), taskId: text(row.taskId), runId: text(row.runId), epoch: Number(row.epoch),
      parentTaskId: text(row.parentTaskId), parentRunId: text(row.parentRunId), parentEpoch: Number(row.parentEpoch), conversationId: text(row.conversationId),
      goal: text(row.goal), status: text(row.status), taskStatus: text(row.taskStatus), phase: text(row.phase), updatedAt: Number(row.updatedAt),
      needsAttention: row.needsAttention, active: row.active, cancelRequested: row.cancelRequested === true, readOnly: true,
      memberCount: count(row.memberCount), completedMemberCount: count(row.completedMemberCount), pendingMemberCount: count(row.pendingMemberCount),
      pendingQuestionCount: count(row.pendingQuestionCount), attentionCount: count(row.attentionCount) });
  }
  return teams;
}

/** Presentation of native work facts. Delivery is not proof that the butler
 * consumed a result, and this projection never creates an execution authority. */
export function ownerWorkPresentation(value: unknown) {
  const task = object(value), raw = object(task.raw), result = object(raw.resultHandling);
  if (!text(task.id) || raw.id !== task.id || raw.actorId !== 'owner' || raw.spaceId !== 'private') return null;
  const nativeResult = raw.nativeResultHandling === true && result.format === 'pocketworld.owner-result-handling.v1'
    && result.taskId === task.id && !!text(result.runId) && result.runId === raw.runId
    && Number.isSafeInteger(result.epoch) && Number(result.epoch) > 0 && result.epoch === raw.coordinatorEpoch;
  if (raw.executionRole !== 'owner_work' && !nativeResult) return null;
  const work = nativeResult ? result : object(raw.ownerWork);
  const available = raw.contextAvailable !== false, handling = object(work.handling), contract = object(work.contract);
  const state: ButlerHandlingState = states.includes(handling.state as typeof states[number]) ? handling.state as typeof states[number] : 'unread';
  const entries = Array.isArray(work.deliveries) ? work.deliveries.map(object) : [];
  const delivery = entries.find(row => row.deliveryId === handling.deliveryId);
  const reportMessageId = available && state === 'reported' ? text(handling.reportMessageId) : '';
  const labels: Record<ButlerHandlingState, string> = {
    none: '尚无执行结果', pending: '结果已到，等待管家整理', running: '管家正在核对结果',
    reported: '管家已汇报', needs_review: '汇报结果待核对', unavailable: '管家接手已停止', unread: '管家处理状态待读取',
  };
  const teams = workTeams(raw, available), teamActive = teams.some(team => team.active);
  const stopped = raw.cancelRequested === true || ['cancelled', 'canceled', 'stopped'].includes(text(task.status));
  const teamNeedsAttention = !stopped && teams.some(team => team.needsAttention);
  const relatedCurrent = !text(work.relatedRunId) || work.relatedRunId === raw.runId;
  const pendingChildren = !relatedCurrent ? [] : Array.isArray(handling.pendingChildren) ? handling.pendingChildren.map(object)
    : Array.isArray(work.relatedPendingChildren) ? work.relatedPendingChildren.map(object) : [];
  const waitingForChildren = available && (teamActive || pendingChildren.length > 0);
  const childNeedsAttention = available && !stopped && pendingChildren.some(child => child.hasUnresolvedOperations === true
    || [text(child.executionStatus), text(child.status)].some(status => /^(awaiting_approval|awaiting_input|awaiting_user|awaiting_review|needs_confirmation|reconciling|interrupted|unknown|not_found)$/.test(status)));
  const teamsUnavailable = available && raw.ownerWorkTeamsUnavailable === true;
  const teamLabel = teams.some(team => (team.pendingQuestionCount || 0) > 0) ? '团队有问题等你回答'
    : teams.some(team => team.status === 'awaiting_approval') ? '团队等待授权'
    : teams.some(team => team.status === 'awaiting_review') ? '团队成果等你核对' : '团队需要你处理';
  const childLabel = stopped ? '关联任务的停止状态待核对' : childNeedsAttention ? '关联任务需要处理' : '等待关联任务完成';
  const handlingLabel = !available ? '原工作来源不可用' : teamNeedsAttention ? teamLabel
    : teamActive ? stopped ? '团队停止状态待核对' : teams.filter(team => team.active).every(team => team.status === 'paused') ? '团队协作已暂停' : '团队正在推进'
    : waitingForChildren ? childLabel : labels[state];
  const reason = !available ? '原来源已删除、停用或暂不可核对。保留工作身份与允许的控制。'
    : teamNeedsAttention ? '打开原协作处理当前问题，已有结果仍保留在原工作。'
    : waitingForChildren ? stopped ? '原工作已要求停止，关联任务尚未确认结束。请查看原记录的当前状态。'
      : '关联任务尚未结束，当前是阶段进展，管家还没有形成完整汇报。'
    : text(handling.reason);
  const resultAvailable = available && (nativeResult ? work.resultAvailable === true : entries.some(row => row.available === true && !row.noticeKind));
  const reconciliation = object(handling.receiptReconciliation);
  const receiptReconciliation: ReportReconciliationTarget | null = available && state === 'needs_review'
    && reconciliation.state === 'unknown' && reconciliation.automaticReplayAllowed === false
    && !!text(reconciliation.taskId) && !!text(reconciliation.runId) && Number.isSafeInteger(reconciliation.epoch) && Number(reconciliation.epoch) > 0
    ? { taskId: text(reconciliation.taskId), runId: text(reconciliation.runId), epoch: Number(reconciliation.epoch) } : null;
  const needsAttention = available && (teamNeedsAttention || childNeedsAttention || state === 'needs_review'
    || state === 'unavailable' && resultAvailable && !stopped);
  const actionLabel = teamNeedsAttention ? '处理团队问题' : teamActive ? stopped ? '查看停止进展' : '查看团队进展'
    : waitingForChildren ? childNeedsAttention ? '处理关联任务' : '查看关联进展' : needsAttention ? '核对管家汇报' : resultAvailable || reportMessageId ? '查看结果' : '查看详情';
  return {
    state, handlingLabel, actionLabel, reason, reportMessageId, resultAvailable, receiptReconciliation,
    teams, teamActive, teamNeedsAttention, teamsUnavailable, waitingForChildren,
    conversationId: text(work.conversationId) || text(raw.conversationId),
    waitingForButler: available && !waitingForChildren && ['pending', 'running'].includes(state),
    needsAttention,
    delivered: available && state === 'reported' && !!reportMessageId,
    deliveryId: available ? text(handling.deliveryId) : '',
    resultState: !available ? '来源不可用' : resultAvailable ? '执行结果已保存' : delivery?.available === false ? '原结果不可用' : nativeResult && typeof work.resultAvailable === 'boolean' ? '尚无可核对的成果' : !Array.isArray(work.deliveries) ? '结果详情尚未读取' : '尚无可核对的成果',
    contract: available ? {
      goal: text(contract.goal) || text(task.goal), deliverable: text(contract.deliverable),
      completionCriteria: text(contract.completionCriteria), constraints: text(contract.constraints),
    } : { goal: text(task.goal), deliverable: '', completionCriteria: '', constraints: '' },
  };
}
