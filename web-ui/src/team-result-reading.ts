import type { TeamAttentionLocator } from './NotificationTeamAttention';
import type { OwnerWorkTeam } from './owner-work-presentation';
import { teamGoalTitle, teamStatusLabel } from './team-presentation';

type Data = Record<string, any>;
const rows = (value: unknown): Data[] => Array.isArray(value) ? value : [];
const text = (v: unknown): string => typeof v === 'string' ? v : '';
const roundId = (v: unknown) => Number.isInteger(v) && Number(v) >= 1 && Number(v) <= 6;

/** Run.summary doubles as a coordinator progress line. The durable assistant
 * summary message survives an intervention/stop which overwrites that field. */
export function teamResultReading(run: Data | null, messages: unknown, observed?: Data | null) {
  // A native read-through can be newer than the coordinator's saved progress sentence after a crash.
  // It is never an assistant summary, a completion, or a grant. An old generation cannot replace this note.
  const currentReceipt = run?.mode==='produce'&&run?.status==='awaiting_review'&&run?.phase==='review'&&run?.summaryStale!==true
    &&observed?.source==='recovered_original_file_operation'&&observed?.teamRunId===run.id&&observed.generation===run.generation
    &&observed.workPlanId===run.workPlan?.planId&&observed.workPlanRevision===run.workPlan?.revision&&observed.planHash===run.independentReview?.planHash
    &&observed.businessVerified===false&&observed.workerStatusChanged===false&&observed.executionAuthorized===false&&typeof observed.message==='string'
    &&Number.isSafeInteger(observed.observedAt)&&observed.observedAt>0&&observed.observedAt<=8.64e15;
  const saved = currentReceipt ? observed.message+'（'+new Date(observed.observedAt).toLocaleString()+' 核对）' : text(run?.summary);
  // Earlier production runs persisted this pre-integration prompt even after real writes.
  const raw = run?.mode==='produce'&&run?.phase==='review'&&saved==='实际成员草稿成果已保留，请查看真实文件差异并逐项审查；原项目尚未整合'
    ? '作者已交回冻结文件。请在原成果页核对实际差异与原项目写回情况。':saved;
  const sources = rows(messages).filter(row => row?.kind === 'summary' && row.role === 'assistant' &&
    text(row.messageId) && text(row.content).trim());
  const source = sources.at(-1);
  const finished = (run?.status === 'completed' || run?.status === 'partial') &&
    (!run?.phase || run.phase === 'completed' || run.phase === 'partial');
  const current = finished && run?.summaryStale !== true && !!raw.trim() &&
    (source ? source.content === raw && (source.generation == null || run?.generation == null || source.generation === run.generation)
      : run?.summaryProducer === 'host_model');
  const body = current ? raw : text(source?.content);
  // A retained status string or a prior report still belongs in the history,
  // but only this matched native transcript row can lead the original read.
  const original = source && current && run?.phase === run?.status && run?.summaryProducer === 'host_model'
    && Number.isSafeInteger(run.generation) && source.generation === run.generation && source.truncated !== true ? source : null;
  const author = original?.memberId === '*' ? '协调助手'
    : original?.memberSnapshot?.id && original.memberSnapshot.id === original.memberId ? text(original.memberSnapshot.name).trim() : '';
  return { body, previous: !!body && !current, messageId: text(source?.messageId),
    statusNote: raw && raw !== body ? raw : '', excerpt: source?.truncated === true, original: !!original, author };
}

/** Choose an entry, not a new work state. Call only on an explicit, exact run read. */
export function teamRunEntrySection(reading: Data): 'progress' | 'result' {
  const run = reading.run;
  return (run?.mode === 'collaborate' || run?.mode === 'debate') && !run.workPlan && !run.productionScope &&
    Array.isArray(reading.attention) && reading.attention.length === 0 && teamResultReading(run, reading.messages).original ? 'result' : 'progress';
}

// The parent TaskStore row may still require reconciliation even when the
// coordinator saved a summary. Don't redirect its decision entry to reading.
export const settledTeamResultEntry = (team: OwnerWorkTeam) => !team.active && !team.needsAttention && !team.cancelRequested &&
  (team.status === 'completed' || team.status === 'partial');

export type TeamFollowUp = { key: string; title: string; detail: string; status: string; locator: TeamAttentionLocator };
const optional = ['identityRevision', 'workItemId', 'assignmentId', 'questionId', 'childKind', 'round', 'taskId'] as const;
export function originalAttentionLocator(row: Data): TeamAttentionLocator | null {
  if (!text(row.runId) || !text(row.memberId) || !['question','member_attention'].includes(row.kind)
      || !Number.isSafeInteger(row.generation) || row.generation < 0 || !Number.isSafeInteger(row.memberGeneration) || row.memberGeneration < 0) return null;
  if (row.identityRevision != null && (!Number.isInteger(row.identityRevision) || row.identityRevision < 1) ||
        ['workItemId', 'assignmentId', 'questionId', 'taskId'].some(k => row[k] != null && !text(row[k]).trim()) ||
        (row.kind === 'question') !== !!row.questionId || row.questionId && !row.assignmentId ||
        row.childKind && (!['research', 'phone'].includes(row.childKind) || !roundId(row.round) || !text(row.taskId)) || row.round != null && !row.childKind) return null;
  return { runId: row.runId, generation: row.generation, memberId: row.memberId, memberGeneration: row.memberGeneration,
      ...Object.fromEntries(optional.filter(k => row[k] != null).map(k => [k, row[k]])) };
}
export function teamFollowUps(run: Data | null, attention: unknown): TeamFollowUp[] {
  if (!run?.id || run.status === 'cancelled') return [];
  const result: TeamFollowUp[] = [], seen = new Set<string>();
  for (const row of rows(attention)) {
    if (!row || row.runId !== run.id || row.source !== 'retained_team_snapshot' || row.readOnly !== true || row.executionAuthorized !== false ||
        !text(row.memberId)) continue;
    const member = rows(run.members).find(m => m.id === row.memberId);
    if (!member || (run.generation != null && run.generation !== row.generation) || (member.generation != null && member.generation !== row.memberGeneration)) continue;
    const locator = originalAttentionLocator(row);
    if (!locator) continue;
    const key = JSON.stringify(locator);
    if (seen.has(key)) continue;
    seen.add(key);
    result.push({ key, locator, status: row.questionId ? 'awaiting_input' : text(row.status),
      title: teamGoalTitle(row.title, row.questionId ? '查看原问题' : '查看原分工'),
      detail: [text(row.memberName || member.name), row.questionId ? '等待回答' : teamStatusLabel(row.status)].filter(Boolean).join(' · ') });
  }
  return result.sort((a, b) => Number(!!b.locator.questionId) - Number(!!a.locator.questionId));
}

/** Exact progress from the open team's existing roster, plan and decisions. */
export function teamMemberProgress(run: Data, member: Data, followUps: TeamFollowUp[]) {
  const active = ['queued','running','awaiting_user','awaiting_input','awaiting_approval','awaiting_review','needs_confirmation'].includes(run.status);
  if ((!active && run.status !== 'completed' && run.status !== 'partial') ||
      member.exited || !text(member.id) || rows(run.members).filter(row => row?.id === member.id).length !== 1) return [];
  const pending = followUps.filter(({ locator }) => locator.runId === run.id && locator.generation === run.generation &&
    locator.memberId === member.id && locator.memberGeneration === member.generation);
  const state = (row: Data, matches: (locator: TeamAttentionLocator) => boolean) => {
    const saved = text(row.status) || 'unknown';
    if (['completed','cancelled','paused','interrupted','failed','exited'].includes(saved)) return saved;
    // A native question or approval can arrive while the coordinator still says "running".
    const same = pending.filter(item => matches(item.locator));
    if (same.some(item => item.locator.questionId)) return 'awaiting_input';
    const statuses = [...new Set(same.map(item => item.status).filter(Boolean))];
    if (statuses.length) return statuses.length === 1 ? statuses[0] : 'unknown';
    return saved === 'running' && (!active || member.paused === true || !Number.isSafeInteger(row.generation) || row.generation !== member.generation) ? 'unknown' : saved;
  };
  if (run.workPlan) {
    const items = rows(run.workPlan.items);
    return items.filter(item => item?.memberId === member.id && text(item.title).trim() && text(item.workItemId) &&
      items.filter(row => row?.workItemId === item.workItemId).length === 1).map(item => {
      const refs = rows(item.artifacts), assignment = text(item.assignmentId);
      const verified = assignment && refs.length > 0 && refs.every(ref => text(ref?.artifactId) && text(ref.runId) &&
        ref.taskId === assignment && ref.assignmentId === assignment && ref.memberId === member.id);
      return { key: text(item.workItemId), title: text(item.title), state: item.status === 'completed' && !verified ? 'blocked_unknown' :
        state(item, locator => !!assignment && locator.workItemId === item.workItemId && locator.assignmentId === assignment) };
    });
  }
  const phases = rows(member.roundStates);
  return phases.filter(phase => roundId(phase?.round) && phases.filter(row => row?.round === phase.round).length === 1)
    .sort((a, b) => a.round - b.round).map(phase => ({
      key: String(phase.round), title: '第 ' + phase.round + ' 阶段', state: state(phase, locator => !locator.workItemId &&
        (locator.round != null ? locator.round === phase.round :
          member.round === phase.round && (!locator.assignmentId || locator.assignmentId === member.assignmentTask?.assignmentId))),
    }));
}

export type AttentionFocus = { kind: 'question' | 'member' | 'workItem' | 'changed'; message?: string; memberId?: string; workItemId?: string; messageId?: string; taskId?: string };
/** Native re-reads this exact locator. A newer generation, a different child or
 * a settled question goes to no replacement target, even under the same name. */
export function teamFollowUpFocus(reading: Data, value: Data, locator: TeamAttentionLocator): AttentionFocus {
  const changed: AttentionFocus = { kind: 'changed', message: text(value?.message) || '这项原待处理已变化，没有转到其他分工。' };
  if (value?.kind === 'changed' || reading?.kind !== 'run' || reading.run?.id !== locator.runId) return changed;
  const same = teamFollowUps(reading.run, reading.attention).some(({ locator: actual }) =>
    actual.memberId === locator.memberId && actual.generation === locator.generation && actual.memberGeneration === locator.memberGeneration &&
    optional.every(k => actual[k] === locator[k]));
  if (!same || value.memberId !== locator.memberId) return changed;
  if (locator.questionId) {
    const message = rows(reading.messages).find(m => m.messageId === value.messageId && m.kind === 'assignment_question' && m.state === 'pending' &&
      m.memberId === locator.memberId && m.questionId === locator.questionId && m.assignmentId === locator.assignmentId && m.workItemId === locator.workItemId);
    return value.kind === 'question' && message ? { kind: 'question', messageId: message.messageId } : changed;
  }
  const kind = locator.workItemId ? 'workItem' : 'member';
  if (value.kind !== kind || value.workItemId !== locator.workItemId || value.taskId !== locator.taskId ||
      value.childKind !== locator.childKind || value.round !== locator.round) return changed;
  return { kind, memberId: locator.memberId, workItemId: locator.workItemId, taskId: locator.taskId };
}
