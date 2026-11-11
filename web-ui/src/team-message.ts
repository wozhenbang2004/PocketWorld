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
  if (letter?.status === 'historical') return letter.notDeliveredReason === 'member_exited' ? '收件成员已退出，消息仅保留历史，没有转给其他成员' : '消息仅保留历史，没有唤醒旧任务';
  return ({ consumed: '已确认消费', delivering: '已纳入本轮输入，尚未确认消费', partially_consumed: '部分指定成员已消费',
    pending: '已定向排队，尚未进入模型', unread: '已定向排队，尚未进入模型' } as Record<string, string>)[letter?.status] || '';
}

export function teamMessageConfirmed(receipt: Data, original: { id: string; memberId: string; questionId?: string }): boolean {
  if (receipt.confirmed !== true || receipt.messageId !== original.id || !receipt.result) return false;
  if (original.questionId) return receipt.command === 'answerAssignment' && receipt.result.answerId === original.id && receipt.result.questionId === original.questionId;
  const saved = receipt.result.messageReceipt;
  return receipt.command === 'sendMessage' && saved?.messageId === original.id && saved?.recipientMemberId === original.memberId
    && ['queued', 'pending', 'unread', 'delivering', 'consumed', 'partially_consumed', 'historical'].includes(saved?.status);
}

export function teamMessageRecorded(reading: Data, original: { id: string; memberId: string }): boolean {
  const run = reading.run;
  if (!run) return false;
  const letter = rows(run.inbox).find(item => item.id === original.id);
  if (letter) return letter.targetMemberId === original.memberId;
  return rows(reading.messages).some(row => row.messageId === original.id && row.recipientMemberId === original.memberId && row.role === 'user');
}
