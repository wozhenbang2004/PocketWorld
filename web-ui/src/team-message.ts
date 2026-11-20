type Data = Record<string, any>;
const rows = (value: unknown): Data[] => Array.isArray(value) ? value : [];

/** Delivery is read from the original queue/assignment, never inferred from a send animation. */
export function teamMessageDelivery(run: Data, row: Data): string {
  if (row.kind === 'assignment_question') return ({ pending: '等待回答原问题', answered: '回答已保存 · 尚未确认成员收到',
    consumed: '原执行已确认收到回答 · 不代表采用', answered_historical: '回答仅保留历史，没有唤醒旧任务' } as Record<string, string>)[row.state] || '原问题状态待核对';
  if (run.mode === 'collaborate' && researchConversation(run) && isDirectedNote(row) && rows(run.members)
    .some(member => member?.id === row.recipientMemberId && (member.runner || 'model') === 'model')) {
    const receipt = teamSupplementReceipt(run, row);
    return receipt ? (receipt.round ? `第 ${receipt.round} 阶段 · ` : '') + receipt.status : '原收件回执待核对';
  }
  const id = row.deliveryId || row.peerMessageId || row.messageId;
  if (!id) return '';
  const assignments = run.workPlan ? rows(run.workPlan.items).map(item => item.assignmentTask) : rows(run.members).map(member => member.assignmentTask);
  const native = assignments.flatMap(assignment => rows(assignment?.messages)).find(message => message.messageId === id);
  if (native) return ({ consumed: '原执行已确认消费', included: '已选入原输入，尚未确认消费', historical: '仅保留历史，没有唤醒旧任务' } as Record<string, string>)[native.state] || '已到原分工队列，等待安全工作点';
  const letter = [...rows(run.inbox), ...rows(run.mailbox)].find(item => item.id === id);
  // The stored title is the destination at send time, not the sender's work item or a current namesake.
  if (letter?.workItemTarget && letter.fromMemberId && letter.targetMemberId === letter.workItemTarget.memberId
    && typeof letter.peerWorkTitle === 'string' && letter.peerWorkTitle) {
    const delivery = ({ unread: '已排队，尚未进入该分工', delivering: '已选入该分工输入，尚未确认送达', consumed: '含此资料的原分工已结束，不代表采用', delivered: '已随原分工的模型请求送达，不代表采用',
      historical: '仅留原记录，未转给其他分工' } as Record<string,string>)[letter.status];
    if (delivery) return `发给「${letter.peerWorkTitle}」：${delivery}`;
  }
  if (letter?.status === 'historical') return letter.notDeliveredReason === 'member_exited' ? '收件成员已退出，消息仅保留历史，没有转给其他成员' : '消息仅保留历史，没有唤醒旧任务';
  return ({ consumed: '已确认消费', delivering: '已纳入本轮输入，尚未确认消费', partially_consumed: '部分指定成员已消费',
    pending: '已定向排队，尚未进入模型', unread: '已定向排队，尚未进入模型' } as Record<string, string>)[letter?.status] || '';
}

export type TeamDirectedSupplement = { key: string; messageId: string; memberId: string; round?: number; title: string; status: string };

const messageId = (value: unknown): value is string => typeof value === 'string' &&
  /^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/.test(value);
const researchRound = (n: unknown): n is number => Number.isSafeInteger(n) && Number(n) >= 1 && Number(n) <= 6;
const researchConversation = (run: Data) => ['collaborate','debate'].includes(run.mode) &&
  run.researchExecutionFormat === 'pocketworld.team-research-agent.v1' && !run.workPlan && !run.productionScope;
const isDirectedNote = (row: Data) => row?.kind === 'conversation' && ['parent_model','user'].includes(row.role) &&
  ['discussion','queue'].includes(row.mode) && typeof row.recipientMemberId === 'string' && !!row.recipientMemberId && row.recipientMemberId !== '*';

/** Join the visible message to the original read-only mailbox. A queue's
 * aggregate status alone is not a receipt for this member or model input. */
export function teamSupplementReceipt(run: Data, row: Data): TeamDirectedSupplement | null {
  if (!researchConversation(run) || !isDirectedNote(row) || !messageId(row.messageId) || row.deliveryId !== row.messageId) return null;
  const id = row.messageId, memberId = row.recipientMemberId, parent = row.senderParentTaskId || '';
  if (row.memberId !== memberId || (row.role === 'parent_model' ? !messageId(parent) : !!parent)) return null;
  const members = rows(run.members).filter(m => m?.id === memberId), letters = rows(run.inbox).filter(m => m?.id === id);
  if (members.length !== 1 || (members[0].runner || 'model') !== 'model' || letters.length !== 1 ||
    rows(run.mailbox).some(m => m?.id === id)) return null;
  const [member] = members, [letter] = letters, round = row.researchRound, child = letter.researchTaskId;
  if (letter.targetMemberId !== memberId || letter.mode !== row.mode || (letter.senderParentTaskId || '') !== parent ||
      (letter.quoteMessageId || '') !== (row.quoteMessageId || '') || letter.researchRound !== round ||
      (round != null && (!parent || !researchRound(round))) || (child != null && (!researchRound(round) || !messageId(child))) ||
      typeof row.content !== 'string' || typeof letter.text !== 'string' || row.content !== letter.text) return null;
  const receipt = (status: string, atRound: number | undefined = round): TeamDirectedSupplement => ({
    key: JSON.stringify([run.id, memberId, id, parent, row.mode, round ?? null]), messageId: id, memberId, round: atRound,
    title: parent ? '管家补充' : row.mode === 'discussion' ? '你的讨论' : '你的补充', status,
  });
  const attempts = rows(letter.deliveries).filter(d => d?.memberId === memberId && researchRound(d.round) &&
    (round == null || d.round === round) && (!child || d.researchTaskId === child) && messageId(d.turnId) &&
    messageId(d.researchTaskId) && Number.isSafeInteger(d.generation) && d.generation >= 0);
  const acknowledged = attempts.filter(d => d.state === 'consumed');
  const byMember = Array.isArray(letter.consumedBy) && letter.consumedBy.includes(memberId);
  // A later pause, exit or revision cannot undo this original receipt. It
  // proves receipt at that turn, not that a replacement used it or adopted it.
  if (acknowledged.length === 1 && byMember)
    return receipt('原成员已确认收到 · 不代表采用', acknowledged[0].round);
  if (letter.status === 'historical')
    return receipt(letter.notDeliveredReason === 'member_exited' ? '成员已退出 · 仅留原记录' : '仅留原记录 · 未核对到收到');
  if (run.status === 'cancelled') return receipt('协作已停止 · 保留原消息，未核对到收到');
  if (member.exited) return receipt('成员已退出 · 保留原消息，未核对到收到');
  const stages = rows(member.roundStates);
  const phase = (r: number) => stages.find(s => s?.round === r && stages.filter(other => other?.round === r).length === 1);
  const pinned = researchRound(round) ? phase(round) : undefined;
  if (pinned && child && pinned.researchTaskId !== child) return receipt('原阶段已换版 · 不算新版收到');
  if (pinned && ['completed','cancelled','failed','exited'].includes(pinned.status))
    return receipt('原阶段已结束 · 未核对到收到');
  if (['completed','failed'].includes(run.status)) return receipt('原协作已结束 · 未核对到收到');
  if (run.status === 'partial') return receipt('保留原消息 · 当前未核对到收到');
  const selected = attempts.filter(d => {
    const stage = phase(d.round);
    return d.state === 'claimed' && stage?.researchTaskId === d.researchTaskId && stage?.turnId === d.turnId &&
      stage?.generation === d.generation && member.generation === d.generation &&
      stage?.status === 'running' && !member.paused && run.status === 'running';
  });
  if (selected.length === 1 && !byMember) return receipt('已选入原输入 · 尚未确认收到', selected[0].round);
  if (!byMember && !acknowledged.length && ['pending','queued','unread'].includes(letter.status)) {
    if (member.paused === true || member.status === 'paused') return receipt('已保存 · 原成员已暂停，尚未确认收到');
    if (run.status === 'paused') return receipt('已保存 · 协作当前暂停，尚未确认收到');
    if (member.status === 'interrupted' || run.status === 'interrupted') return receipt('已保存 · 原执行已中断，尚未确认收到');
    return receipt(researchRound(round) ? '已排队 · 等原阶段处理' : '已排队 · 等原成员处理');
  }
  return receipt('已保存 · 是否收到待核对');
}

export function teamDirectedSupplement(run: Data, messages: unknown, memberId: string, parentTaskId?: string): TeamDirectedSupplement | null {
  if (!researchConversation(run)) return null;
  const original = rows(messages);
  const latest = original.filter(row => isDirectedNote(row) && row.recipientMemberId === memberId && (run.mode === 'collaborate' ||
    row.role === 'parent_model' && row.mode === 'queue' && researchRound(row.researchRound))).at(-1);
  // Never substitute an older note when the latest one cannot be verified.
  if (!latest || latest.role === 'parent_model' && latest.senderParentTaskId !== parentTaskId ||
      original.filter(row => row?.messageId === latest.messageId).length !== 1) return null;
  return teamSupplementReceipt(run, latest);
}

export type AnswerDelivery = 'save' | 'continue' | 'continue_saved';
type MessageIdentity = { id: string; runId: string; memberId: string; assignmentId?: string; questionId?: string };

export function teamAnswerOutcome(result: Data, delivery: AnswerDelivery): string {
  if (result.state === 'answered_historical') return '回答仅留在原问题的历史记录，没有启动旧分工。';
  if (result.state === 'consumed') return '原执行已经确认收到这条回答。';
  switch (result.continuationState) {
    case 'saved': return delivery === 'save' ? '回答已保存到原问题；这次没有请求继续。' : '回答已保存，尚无继续原分工的回执。';
    case 'queued': return '原分工已排队继续，尚未确认采用回答。';
    case 'already_claimed': return '原回答已有续接登记，本次没有重复安排。';
    case 'waiting': return '回答已保存，尚未确认继续。' + (typeof result.continuationReason === 'string' ? result.continuationReason : '');
    default: return '回答已保存；后续状态请在原分工中核对。';
  }
}

export function teamMessageConfirmed(receipt: Data, original: MessageIdentity): boolean {
  if (receipt.confirmed !== true || receipt.messageId !== original.id || !receipt.result) return false;
  if (original.questionId) return receipt.command === 'answerAssignment' && receipt.runId === original.runId && receipt.memberId === original.memberId
    && !!original.assignmentId && receipt.result.assignmentId === original.assignmentId && receipt.result.answerId === original.id && receipt.result.questionId === original.questionId;
  const saved = receipt.result.messageReceipt;
  return receipt.command === 'sendMessage' && saved?.messageId === original.id && saved?.recipientMemberId === original.memberId
    && ['queued', 'pending', 'unread', 'delivering', 'consumed', 'partially_consumed', 'historical'].includes(saved?.status);
}

export function teamMessageRecorded(reading: Data, original: MessageIdentity): boolean {
  const run = reading.run;
  if (!run || run.id !== original.runId) return false;
  if (original.questionId) return !!original.assignmentId && rows(reading.messages).some(row =>
    row.kind === 'assignment_question' && row.questionId === original.questionId && row.assignmentId === original.assignmentId
    && row.memberId === original.memberId && row.answerId === original.id && ['answered', 'consumed', 'answered_historical'].includes(row.state)
    || row.kind === 'assignment_answer' && row.role === 'user' && row.messageId === original.id && row.questionId === original.questionId
    && row.assignmentId === original.assignmentId && row.recipientMemberId === original.memberId);
  const letter = rows(run.inbox).find(item => item.id === original.id);
  if (letter) return letter.targetMemberId === original.memberId;
  return rows(reading.messages).some(row => row.messageId === original.id && row.recipientMemberId === original.memberId && row.role === 'user');
}
