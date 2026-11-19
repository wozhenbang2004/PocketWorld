import type { TeamAttentionLocator } from './NotificationTeamAttention';
import { teamGoalTitle, teamStatusLabel } from './team-presentation';

type Data = Record<string, any>;
const rows = (value: unknown): Data[] => Array.isArray(value) ? value : [];
const text = (v: unknown): string => typeof v === 'string' ? v : '';
const roundId = (v: unknown) => Number.isInteger(v) && Number(v) >= 1 && Number(v) <= 6;

/** Run.summary doubles as a coordinator progress line. The durable assistant
 * summary message survives an intervention/stop which overwrites that field. */
export function teamResultReading(run: Data | null, messages: unknown) {
  const saved = text(run?.summary);
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
  return { body, previous: !!body && !current, messageId: text(source?.messageId),
    statusNote: raw && raw !== body ? raw : '', excerpt: source?.truncated === true };
}

export type TeamFollowUp = { key: string; title: string; detail: string; locator: TeamAttentionLocator };
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
    result.push({ key, locator, title: teamGoalTitle(row.title, row.questionId ? '查看原问题' : '查看原分工'),
      detail: [text(row.memberName || member.name), row.questionId ? '等待回答' : teamStatusLabel(row.status)].filter(Boolean).join(' · ') });
  }
  return result.sort((a, b) => Number(!!b.locator.questionId) - Number(!!a.locator.questionId));
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
