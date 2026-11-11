type Data = Record<string, unknown>;
const object = (value: unknown): Data => value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Data : {};
const text = (value: unknown) => typeof value === 'string' ? value.trim() : '';
const states = ['none', 'pending', 'running', 'reported', 'needs_review', 'unavailable'] as const;
export type ButlerHandlingState = typeof states[number] | 'unread';

/** Presentation of native work facts. Delivery is not proof that the butler
 * consumed a result, and this projection never creates an execution authority. */
export function ownerWorkPresentation(value: unknown) {
  const task = object(value), raw = object(task.raw), work = object(raw.ownerWork);
  if (!text(task.id) || raw.id !== task.id || raw.actorId !== 'owner' || raw.spaceId !== 'private' || raw.executionRole !== 'owner_work') return null;
  const available = raw.contextAvailable !== false, handling = object(work.handling), contract = object(work.contract);
  const state: ButlerHandlingState = states.includes(handling.state as typeof states[number]) ? handling.state as typeof states[number] : 'unread';
  const entries = Array.isArray(work.deliveries) ? work.deliveries.map(object) : [];
  const delivery = entries.find(row => row.deliveryId === handling.deliveryId);
  const reportMessageId = available && state === 'reported' ? text(handling.reportMessageId) : '';
  const labels: Record<ButlerHandlingState, string> = {
    none: '尚无执行结果', pending: '结果已到，等待管家整理', running: '管家正在核对结果',
    reported: '管家已汇报', needs_review: '汇报结果待核对', unavailable: '管家接手已停止', unread: '管家处理状态待读取',
  };
  const handlingLabel = !available ? '原工作来源不可用' : labels[state];
  const reason = !available ? '原来源已删除、停用或暂不可核对。保留工作身份与允许的控制。' : text(handling.reason);
  const resultAvailable = available && entries.some(row => row.available === true && !row.noticeKind);
  return {
    state, handlingLabel, reason, reportMessageId, resultAvailable,
    waitingForButler: available && ['pending', 'running'].includes(state),
    needsAttention: available && (state === 'needs_review' || state === 'unavailable' && resultAvailable && raw.cancelRequested !== true && !['cancelled', 'canceled', 'stopped'].includes(text(task.status))),
    delivered: available && state === 'reported' && !!reportMessageId,
    deliveryId: available ? text(handling.deliveryId) : '',
    resultState: !available ? '来源不可用' : resultAvailable ? '执行结果已保存' : delivery?.available === false ? '原结果不可用' : !Array.isArray(work.deliveries) ? '结果详情尚未读取' : '尚无可核对的成果',
    contract: available ? {
      goal: text(contract.goal) || text(task.goal), deliverable: text(contract.deliverable),
      completionCriteria: text(contract.completionCriteria), constraints: text(contract.constraints),
    } : { goal: text(task.goal), deliverable: '', completionCriteria: '', constraints: '' },
  };
}
