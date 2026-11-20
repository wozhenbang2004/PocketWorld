type Data = Record<string, unknown>;

export type TeamResearchRecord = {
  taskId: string;
  round: number;
  historical: boolean;
  version: number;
  recordedStatus: string;
};

const object = (value: unknown): Data => value && typeof value === 'object' && !Array.isArray(value) ? value as Data : {};
const rows = (value: unknown): Data[] => Array.isArray(value) ? value.map(object) : [];
const taskIdPattern = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const modelLabel = (value: unknown) => typeof value === 'string' && value.length <= 200 && value.trim() &&
  !/[\p{Cc}\p{Cf}]/u.test(value) ? value.trim() : '';

/** Owner-only proof for one retained answer, not the slot's configured model or an entire run. */
export function teamResearchReplyExecution(runId: string, memberValue: unknown, observations: unknown) {
  const member = object(memberValue), candidates = rows(observations).filter(row => row.teamRunId === runId && row.memberId === member.id);
  if (candidates.length !== 1) return null;
  const proof = candidates[0], round = proof.round;
  if (proof.format !== 'pocketworld.team-research-reply-execution.v1' || proof.readOnly !== true ||
      typeof round !== 'number' || !Number.isSafeInteger(round) || round < 1 || round > 6 ||
      typeof proof.taskId !== 'string' || !taskIdPattern.test(proof.taskId) || typeof proof.runId !== 'string' || !taskIdPattern.test(proof.runId) ||
      typeof proof.epoch !== 'number' || !Number.isSafeInteger(proof.epoch) || proof.epoch < 1 ||
      typeof proof.answerSha256 !== 'string' || !/^[a-f0-9]{64}$/.test(proof.answerSha256) ||
      proof.routeKind !== 'api_key' && proof.routeKind !== 'account') return null;
  const phases = rows(member.roundStates).filter(phase => phase.round === round);
  if (phases.length !== 1 || phases[0].status !== 'completed' || phases[0].researchTaskId !== proof.taskId) return null;
  const result = object(phases[0].researchTask), ref = object(result.outputReference);
  if (result.status !== 'completed' || result.executionAuthorized !== false || result.taskId !== proof.taskId || result.teamRunId !== runId ||
      result.memberId !== member.id || result.runId !== proof.runId || result.coordinatorEpoch !== proof.epoch ||
      ref.format !== 'pocketworld.team-research-original.v1' || ref.taskId !== proof.taskId || ref.runId !== proof.runId ||
      ref.coordinatorEpoch !== proof.epoch || ref.sha256 !== proof.answerSha256) return null;
  return {round, reportedModel: modelLabel(proof.reportedModel), requestedModel: modelLabel(proof.requestedModel),
    location: proof.routeKind === 'account' ? '本机发起 · 账号模型服务' : '本机发起 · API 模型服务'};
}

/** Navigation only. Native reading handles and owner task reads remain authoritative. */
export function teamResearchRecords(runId: string, memberValue: unknown): TeamResearchRecord[] {
  const member = object(memberValue), records: TeamResearchRecord[] = [];
  for (const phase of rows(member.roundStates)) {
    const round = phase.round;
    if (typeof round !== 'number' || !Number.isInteger(round) || round < 1 || round > 6) continue;
    const history = rows(phase.researchHistory);
    const append = (id: unknown, receiptValue: unknown, historical: boolean, version: number) => {
      if (typeof id !== 'string' || !taskIdPattern.test(id)) return;
      const receipt = object(receiptValue);
      if (receipt.taskId !== undefined && receipt.taskId !== id
        || receipt.teamRunId !== undefined && receipt.teamRunId !== runId
        || receipt.memberId !== undefined && receipt.memberId !== member.id) return;
      records.push({ taskId: id, round, historical, version,
        recordedStatus: typeof receipt.status === 'string' ? receipt.status : '' });
    };
    // A persisted native ID may precede the first progress receipt. Opening it
    // queries that exact task and may report unavailable; it never creates one.
    append(phase.researchTaskId, phase.researchTask, false, history.length + 1);
    for (let index = history.length - 1; index >= 0; index--) {
      append(history[index].taskId, history[index].result, true, index + 1);
    }
  }
  return records.sort((a, b) => b.round - a.round || b.version - a.version);
}
