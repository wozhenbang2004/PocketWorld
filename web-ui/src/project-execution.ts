export type ProjectExecutionData = Record<string, any>;
export type ProjectExecutionRow = ProjectExecutionData & { operationId: string; interactive: boolean };
const object = (value: any): ProjectExecutionData => value && typeof value === 'object' && !Array.isArray(value) ? value : {};

/** A locator for the original native journal. This projection grants no input or stop authority. */
export function projectExecutionRows(task: ProjectExecutionData, operation?: ProjectExecutionData): ProjectExecutionRow[] {
  const raw = object(task.raw);
  if (raw.actorId !== 'owner' || raw.spaceId !== 'private' || raw.origin || !raw.developmentContext && !raw.ownerLocalTerminal) return [];
  const source: ProjectExecutionData[] = operation ? [operation] : Array.isArray(raw.operations) ? raw.operations : [];
  return source.filter(row => row.tool === 'project_execute' && typeof row.operationId === 'string' && /^[A-Za-z0-9_-]{1,80}$/.test(row.operationId))
    .map(row => ({ ...row, operationId: row.operationId, interactive: row.runId === raw.runId && object(object(row.receipt).executionReceipt).terminalMode === 'pty' }));
}

export function projectExecutionCanInput(metadata: ProjectExecutionData, output: ProjectExecutionData, accepted: boolean, uncertain: boolean, stopping: boolean) {
  return accepted && metadata.observingOnly === false && metadata.terminalMode === 'pty' && output.nativeStatus === 'running' &&
    !output.terminal && !output.outputTruncated && !output.logIncompleteTail && !output.screen?.gap && !output.inputUncertain && !metadata.inputUncertain &&
    !Number(metadata.pendingInputCount || 0) && !uncertain && !stopping;
}

export function projectExecutionShouldRead(output: ProjectExecutionData) {
  // A writer can still be completing the last record. Keep observing the same
  // cursor while its process is live; an incomplete tail is not an end marker.
  return output.terminal === true ? output.hasMore === true : ['queued', 'starting', 'preparing', 'running', 'stopping'].includes(output.nativeStatus);
}

/** Same display-only rule as native LocalWorkbenchGuard.pollable. Cold unknown receipts never recover execution or a polling loop. */
export const localReceiptPollable = (status: string, termux = false) => termux ? !['completed', 'failed'].includes(status) : ['queued', 'running', 'stopping'].includes(status);

/** Original runtime activity and original observation-window phases only. */
export function projectExecutionActivity(activity: ProjectExecutionData, execution: string) {
  if (activity.format !== 'pocketworld.runtime-activity.v1' || activity.operationId !== execution) return '';
  const state = ({ running: '原进程运行中', active_output: '原执行有输出更新', inferred_idle: '原执行暂时静默；仅为静默推测，不证明正在等输入或已经完成', starting: '等待原进程启动确认', preparing: '原工具仍在准备', stopping: '原执行已请求停止，仍需真实清场回执', awaiting_resource_release: '已观察原进程退出，仍在核对资源释放', exited: '原进程退出与资源释放已核对；业务结果仍需验收', not_launched: '原回执确认未创建子进程' } as Record<string, string>)[activity.state] || '原进程状态待核；只读日志，不自动重放';
  const phase = activity.observationWindow?.phase === 'foreground_wait' ? '原批准的前台观察时窗内。' : activity.observationWindow?.phase === 'background_observation' ? '前台时窗已到，仅观察同一个原执行，总期限未延长。' : '';
  const input = ({ delivered: ' 原输入已取得字节 ACK，未证明程序已经处理。', not_dispatched: ' 上次原输入确认未发送。', unknown: ' 原输入结果待核，只查询原操作，不重发。' } as Record<string, string>)[activity.input?.state] || '';
  return phase + state + input;
}

