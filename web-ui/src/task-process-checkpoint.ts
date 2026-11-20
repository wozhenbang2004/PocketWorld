// A reading aid for the original process window, never a plan, retry or decision.
import type { TaskHeader } from './task-history';
import type { PhoneStopReceipt } from './phone-stop-receipt';
import { presentOperation, type TaskEventRecord, type TaskOperation } from './task-presentation';
import { workCollectionPresentation } from './work-collection';

export type ProcessRecord = { section: 'events' | 'operations'; id: string; runId: string; locatable: boolean; label: string; body: string };
type Checkpoint = { state: string; note: string; record?: ProcessRecord; lines?: string[] };
const copy = (value: unknown) => typeof value === 'string' ? value.trim() : '';
// Positions are ordered within one native journal, not across events and operations.
function newest<T>(rows: T[], position: (row: T) => number | undefined, at: (row: T) => number | undefined) {
  return rows.reduce<T | undefined>((best, row) => {
    if (!best) return row;
    const a = position(row), b = position(best);
    if (a !== undefined && b !== undefined) return a > b ? row : best;
    return (at(row) ?? -1) > (at(best) ?? -1) ? row : best;
  }, undefined);
}

export function processCheckpoint(task: TaskHeader, events: TaskEventRecord[], operations: TaskOperation[], phoneStop?: PhoneStopReceipt): Checkpoint {
  const run = copy(task.raw?.runId);
  const currentEvents = events.filter(event => event.isCurrentRun && event.bodyAvailable);
  const currentOps = operations.filter(op => !!run && op.runId === run);
  const eventRecord = (rows: TaskEventRecord[], label: string): ProcessRecord | undefined => {
    const row = newest(rows, entry => entry.position, entry => entry.at);
    return row && { section: 'events', id: row.id, runId: run, label,
      body: row.ownerGoalVerdict === undefined ? row.detail : row.stateLabel,
      locatable: row.hasRecordedId && currentEvents.filter(event => event.id === row.id).length === 1 };
  };
  const opRecord = (rows: TaskOperation[], label: string): ProcessRecord | undefined => {
    const row = newest(rows, entry => entry.position, entry => entry.createdAt);
    if (!row) return;
    const op = presentOperation(row);
    return { section: 'operations', id: row.operationId, runId: run, locatable: true, label, body: op.name + '：' + op.stateLabel };
  };
  const view = workCollectionPresentation(task);
  const summary = Array.from(copy(task.summary)).slice(0, 360).join('');
  const status = task.status;
  const recentRecord = () => eventRecord(currentEvents.filter(event => event.position !== undefined || event.at !== undefined), '本轮最近的进展记录')
    ?? opRecord(currentOps.filter(op => op.bodyAvailable && (op.position !== undefined || op.createdAt !== undefined)), '本轮最近的操作记录');
  if (view.reconciliation) {
    const record = opRecord(currentOps.filter(op => presentOperation(op).status === 'unknown' &&
      (op.bodyAvailable || op.requiresReconciliation || op.state.includes('unknown'))), '本轮待核对的操作');
    if (phoneStop) {
      const phone = phoneStop.record, same = phone && record?.section === phone.section && record.id === phone.id;
      return { state:'手机执行已停止，有操作待核对', note:'请先核对原操作，不要重做。停止不会撤销已发生的动作。',
        record:same ? phone : record ?? phone,
        lines:phone && record && !same ? [phone.label+'：'+phone.body, ...phoneStop.lines] : phoneStop.lines };
    }
    return { state: '操作结果待核对', note: '有操作可能已经发生。请在概览核对，不要重复执行。', record };
  }
  if (phoneStop) return {state:'这次手机执行已停止', note:'停止不会撤销已发生的动作。系统接纳请求、屏幕有变化，都不等于目标办成。',
    record:phoneStop.record, lines:phoneStop.lines};
  const ownerReview = eventRecord(currentEvents.filter(event => event.ownerGoalVerdict !== undefined), '你的验收');
  const matchingEvent = (...states: string[]) => currentEvents.filter(event => states.includes(event.status));
  if (view.pendingApproval || ['waiting_approval','awaiting_approval','waiting_permission','awaiting_permission'].includes(status)) {
    const record = opRecord(currentOps.filter(op => presentOperation(op).status === 'needs_approval'), '本轮授权请求');
    return { state: '等你授权', note: '在概览查看操作内容，再决定是否允许。', record };
  }
  if (['awaiting_user','waiting_user','needs_confirmation'].includes(status)) {
    const confirm = status === 'needs_confirmation';
    return { state: confirm ? '结果等你确认' : '需要你处理', note: confirm ? '在概览核对结果，再决定是否接受。' : '回到概览查看待办事项。',
      record: ownerReview ?? eventRecord(matchingEvent(status), '本轮待处理记录') };
  }
  if (status === 'failed' || status === 'interrupted' || status === 'paused') {
    const failed = status === 'failed';
    const record = eventRecord(matchingEvent(status), failed ? '本轮错误记录' : '本轮停点记录')
      ?? (failed ? opRecord(currentOps.filter(op => presentOperation(op).status === 'error'), '本轮出错的调用') : undefined);
    return { state: failed ? '这次执行未完成' : status === 'paused' ? '执行已暂停' : '执行已中断',
      note: record && (!summary || record.body === summary) ? '' : summary || '当前读到的记录里没有这次停下的具体原因。', record: record ?? recentRecord() };
  }
  const recent = ownerReview ?? recentRecord();
  if (view.handling?.needsAttention || view.handling?.waitingForButler || view.handling?.waitingForChildren)
    return { state: view.handling.handlingLabel, note: view.handling.reason || '', record: recent };
  if (status === 'queued') return { state: '已排队', note: '原工作已登记，还未开始执行。' };
  if (['pausing','stopping','cancelling'].includes(status))
    return { state: status === 'pausing' ? '已请求暂停' : '已请求停止', note: '仍在等待原任务确认。', record: recent };
  if (['cancelled','canceled','stopped'].includes(status)) return { state: '这次执行已停止', note: '', record: recent };
  if (status === 'completed') return { state: '本轮执行已结束', note: '可用成果以概览中实际保存的内容为准。', record: recent };
  return { state: view.label, note: recent ? '' : summary || '还没有读到本轮的具体进展。', record: recent };
}
