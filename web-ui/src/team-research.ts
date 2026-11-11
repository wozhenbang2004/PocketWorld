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
