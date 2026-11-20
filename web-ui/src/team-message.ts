type Data = Record<string, any>;
const rows = (value: unknown): Data[] => Array.isArray(value) ? value : [];

/** Delivery is read from the original queue/assignment, never inferred from a send animation. */
export function teamMessageDelivery(run: Data, row: Data): string {
  if (row.kind === 'assignment_question') return ({ pending: '等待回答原问题', answered: '回答已保存，尚未确认消费',
    consumed: '原执行已确认消费回答', answered_historical: '回答仅保留历史，没有唤醒旧任务' } as Record<string, string>)[row.state] || '原问题状态待核对';
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

export type TeamDirectedSupplement = {
  key: string; memberId: string; round: number; text: string; status: string;
};

const messageId = (value: unknown): value is string => typeof value === 'string' &&
  /^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/.test(value);

/** One visible parent note joined to its exact native recipient, planned phase
 * and input receipt. No new inbox read, child selection or dispatch. */
export function teamDirectedSupplement(run: Data, messages: unknown, memberId: string, parentTaskId: string): TeamDirectedSupplement | null {
  if (run.researchExecutionFormat !== 'pocketworld.team-research-agent.v1' || run.workPlan ||
      !['collaborate','debate'].includes(run.mode) || !messageId(parentTaskId)) return null;
  const member = rows(run.members).filter(row => row?.id === memberId);
  if (member.length !== 1 || member[0].exited) return null;
  const original = rows(messages);
  const latest = [...original].reverse().find(row => row?.kind === 'conversation' && row.role === 'parent_model' &&
    row.senderParentTaskId === parentTaskId && row.recipientMemberId === memberId && row.mode === 'queue');
  // No replacement with an older, superficially similar note.
  if (!latest || !messageId(latest.messageId) || latest.deliveryId !== latest.messageId ||
      !Number.isSafeInteger(latest.researchRound) || latest.researchRound < 1 || latest.researchRound > 6 ||
      typeof latest.content !== 'string' || !latest.content.trim() || latest.truncated === true ||
      original.filter(row => row?.messageId === latest.messageId).length !== 1) return null;
  const round = latest.researchRound;
  const phase = rows(member[0].roundStates).filter(row => row?.round === round);
  const matches = rows(run.inbox).filter(row => row?.id === latest.messageId);
  if (phase.length !== 1 || matches.length !== 1) return null;
  const letter = matches[0], child = letter.researchTaskId;
  if (letter.targetMemberId !== memberId || letter.senderParentTaskId !== parentTaskId ||
      letter.mode !== 'queue' || letter.researchRound !== round || letter.text !== latest.content ||
      (child != null && (!messageId(child) || child !== phase[0].researchTaskId))) return null;
  const selected = child ? rows(letter.deliveries).filter(row => row?.memberId === memberId && row.round === round &&
    row.researchTaskId === child && messageId(row.turnId)) : [];
  const consumed = Array.isArray(letter.consumedBy) && letter.consumedBy.includes(memberId) && selected.some(row => row.state === 'consumed');
  const status = consumed ? '原执行已读到，是否采用看答复' :
    letter.status === 'historical' ? '仅留原记录，没有重新安排' :
    selected.some(row => row.state === 'claimed') ? '已纳入这阶段输入，尚未确认收到' :
    ['completed','cancelled','failed','interrupted'].includes(phase[0].status) ? '这阶段已结束，未核对到收到' :
    !child && ['queued','waiting_dependency'].includes(phase[0].status) ? '已保存，等待这阶段开始' :
    '已保存，是否收到待核对';
  return { key: JSON.stringify([run.id, memberId, round, latest.messageId]), memberId, round, text: latest.content, status };
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
