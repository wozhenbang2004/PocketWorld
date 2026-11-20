import type { TeamAttentionLocator } from './NotificationTeamAttention';
import type { OwnerWorkTeam } from './owner-work-presentation';
import { teamGoalTitle, teamStatusLabel } from './team-presentation';

type Data = Record<string, any>;
const rows = (value: unknown): Data[] => Array.isArray(value) ? value : [];
const text = (v: unknown): string => typeof v === 'string' ? v : '';
const roundId = (v: unknown) => Number.isInteger(v) && Number(v) >= 1 && Number(v) <= 6;
const activeProgress = (status: string) => ['queued','running','awaiting_user','awaiting_input','awaiting_approval','awaiting_review','needs_confirmation'].includes(status);
const retainedProgress = (status: string) => ['paused','interrupted','failed'].includes(status);

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
  return { body, previous: !!body && !current, partial: current && run?.status === 'partial', messageId: text(source?.messageId),
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
export function teamMemberProgress(run: Data, member: Data, followUps: TeamFollowUp[], readRetained = false) {
  const active = activeProgress(run.status);
  if ((!active && run.status !== 'completed' && run.status !== 'partial' && !(readRetained && retainedProgress(run.status))) ||
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

const stoppedMemberStage = (value: unknown) => value === 'failed' || value === 'interrupted';

/** A scoped choice on the existing owner reading, not a new resume grant.
 * Evidence debate is excluded: redoing an earlier debate turn can reopen later turns of other members. */
export function teamRemainingMember(reading: Data | null, delivered: readonly { memberId: string; round: number }[], ownerTasks: unknown) {
  const run = reading?.run, link = reading?.ownerWorkLink;
  if (reading?.kind !== 'run' || !run?.id || run.mode !== 'collaborate' || run.workPlan || run.productionScope ||
      run.researchExecutionFormat !== 'pocketworld.team-research-agent.v1' || run.researchBudgetAutoRefill !== false ||
      (run.discussionFormat != null && run.discussionFormat !== 'classic') ||
      !((run.status === run.phase && (run.status === 'partial' || stoppedMemberStage(run.status))) ||
        run.status === 'paused' && run.phase === 'waiting_member') ||
      link?.teamRunId !== run.id || link.readOnly !== true || link.currentRun !== true || link.stopping !== false ||
      !Number.isSafeInteger(run.revision) || run.revision < 1 || !roundId(run.rounds) ||
      !Array.isArray(reading.attention) || reading.attention.length !== 1) return null;
  const roster = rows(run.members), [item] = teamFollowUps(run, reading.attention);
  if (roster.length < 2 || !item || !stoppedMemberStage(item.status) ||
      item.locator.questionId || item.locator.workItemId || item.locator.childKind || item.locator.assignmentId ||
      new Set(roster.map(member => member?.id)).size !== roster.length) return null;
  let remaining: Data[] = [], assignment = '';
  for (const member of roster) {
    if (!text(member?.id) || member.runner !== 'model' || member.exited || member.paused ||
        (member.device != null && member.device !== 'phone')) return null;
    const stages = rows(member.roundStates).slice().sort((a, b) => (a?.round || 0) - (b?.round || 0));
    if (stages.length !== run.rounds || !stages.every((stage, i) => stage?.round === i + 1)) return null;
    if (member.id !== item.locator.memberId) {
      if (member.status !== 'completed' || !stages.every(stage => stage.status === 'completed' &&
          delivered.some(receipt => receipt.memberId === member.id && receipt.round === stage.round))) return null;
      continue;
    }
    remaining = stages.filter(stage => stage.status !== 'completed');
    if (member.status !== item.status || !stoppedMemberStage(remaining[0]?.status) ||
        member.memberModelSelection?.format !== 'pocketworld.team-member-model.v1' ||
        member.memberModelSelection.identityRevision !== item.locator.identityRevision ||
        remaining.some(stage => {
          const child = stage.researchTask;
          return stage.status === 'queued' ? !!stage.researchTaskId || !!child :
            !stoppedMemberStage(stage.status) || (stage.researchTaskId ? child?.taskId !== stage.researchTaskId ||
              child?.executionAuthorized !== false || child?.teamRunId !== run.id || child?.memberId !== member.id ||
              child?.round !== stage.round || child?.identityRevision !== item.locator.identityRevision ||
              !stoppedMemberStage(child?.status) : !!child);
        })) return null;
    assignment = text(member.assignment || member.role).trim();
  }
  if (!remaining.length || !assignment) return null;
  const pool = run.parentBudget, left = pool?.remainingModelRequests;
  const parent = rows(ownerTasks).find(task => task?.id === link.taskId && task.raw?.executionRole === 'owner_work' &&
    task.raw?.runId === link.runId && task.raw?.coordinatorEpoch === link.epoch &&
    task.raw?.conversationId === link.conversationId && task.raw?.actorId === 'owner' && task.raw?.spaceId === 'private');
  // A retained link can still be the latest link after its parent execution ends.
  const parentStatus = text(parent?.raw?.status);
  const parentPresent = !!parentStatus && parent?.raw?.cancelRequested !== true &&
    !['completed', 'stopping', 'cancelled', 'interrupted', 'paused', 'pausing'].includes(parentStatus);
  return {
    key: JSON.stringify([item.key, run.revision]), item, assignment,
    rounds: remaining.map(row => row.round as number), revision: run.revision as number,
    canReview: parentPresent && pool?.parentTaskId === link.taskId && pool?.parentRunId === link.runId &&
      Number.isSafeInteger(left) && left > 0 && Number.isSafeInteger(run.callsUsed) &&
      Number.isSafeInteger(run.callLimit) && run.callsUsed < run.callLimit,
  };
}

/** Use previously saved originals as the reading priority, not as a team outcome.
 * Pending stages are from the same roster snapshot, never inferred from missing answers. */
export function teamPartialReading(run: Data | null, followUps: TeamFollowUp[]) {
  if (!run?.id || !['collaborate','debate'].includes(run.mode) || run.workPlan || run.productionScope ||
      !(activeProgress(run.status) || retainedProgress(run.status) || run.status === 'partial')) return null;
  const roster = rows(run.members);
  if (!roster.length || roster.some(member => member?.runner !== 'model')) return null;
  return {
    synthesizing: run.status === 'running' && run.phase === 'synthesis',
    remaining: roster.flatMap(member => {
      const stages = teamMemberProgress(run, member, followUps, true);
      const unfinished = stages.filter(stage => stage.state !== 'completed');
      if (!unfinished.length) return [];
      return [{ memberId: text(member.id), assignment: teamGoalTitle(member.assignment || member.role, ''),
        states: unfinished.map(stage => (stages.length > 1 ? stage.title + '：' : '') +
          (stage.state === 'queued' ? '未开始' : stage.state === 'preparing' ? '正在准备' : teamStatusLabel(stage.state))).join('；') }];
    }),
  };
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
