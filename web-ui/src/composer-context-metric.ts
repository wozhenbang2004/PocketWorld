type Row = Record<string, unknown>;
const row = (value: unknown): Row | null => value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Row : null;
const rows = (value: unknown): Row[] => Array.isArray(value) ? value.map(row).filter((item): item is Row => item !== null) : [];
const id = (value: unknown): value is string => typeof value === 'string' && value.length > 0 && value.length <= 240;
const count = (value: unknown): number | null => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : null;

export type ContextMetric = {
  available: boolean;
  reason: string;
  taskId: string | null;
  at: number | null;
  inputTokens: number | null;
  inputLimit: number | null;
  windowTokens: number | null;
  outputReserve: number | null;
  windowPercent: number | null;
  inputPercent: number | null;
  summaryUsed: boolean;
  attention: boolean;
};
const unavailable = (reason: string, taskId: string | null = null): ContextMetric => ({ available: false, reason, taskId, at: null,
  inputTokens: null, inputLimit: null, windowTokens: null, outputReserve: null, windowPercent: null, inputPercent: null,
  summaryUsed: false, attention: false });

/** Read only native request checks already present in PocketWebState.task.raw.
 * This is a historical display projection, not a tokenizer, admission decision,
 * cumulative-usage calculation or authorization to read another conversation. */
export function composerContextMetric(snapshot: unknown, composerKey: string | undefined): ContextMetric {
  const root = row(snapshot), state = row(root?.state), composer = row(root?.composer);
  if (row(root?.host)?.connected !== true) return unavailable('尚未连接原生状态。');
  if (!state || !composer || !composerKey || composerKey !== `${composer.key || ''}:${composer.epoch ?? ''}`)
    return unavailable('当前输入身份尚未核对。');
  if (!id(state.conversationId)) return unavailable('此对话尚无请求检查记录。');
  const conversation = state.conversationId, tasks = rows(state.tasks);
  const foreground = row(Object.hasOwn(state, 'foregroundTurn') ? state.foregroundTurn : state.chatActiveTurn);
  let selected: Row | undefined;
  if (foreground && foreground.conversationId === conversation && foreground.executionRole !== 'owner_work' && id(foreground.taskId)) {
    selected = tasks.find(task => task.id === foreground.taskId);
    const raw = row(selected?.raw);
    if (!raw || !id(foreground.runId) || count(foreground.epoch) === null || Number(foreground.epoch) <= 0 ||
        raw.runId !== foreground.runId || raw.coordinatorEpoch !== foreground.epoch)
      return unavailable('当前回复的原执行记录尚未核对。', foreground.taskId);
  } else {
    const messages = rows(state.messages);
    for (let index = messages.length - 1; index >= 0; index--) {
      const message = messages[index];
      if (!id(message.taskId) || message.taskResult != null || message.phoneResult != null) continue;
      selected = tasks.find(task => task.id === message.taskId);
      // Do not substitute an older task when the latest linked task is missing.
      if (!selected) return unavailable('最近回复的原任务尚未载入。', message.taskId);
      break;
    }
  }
  const raw = row(selected?.raw);
  if (!selected || !raw || !id(selected.id) || raw.id !== selected.id || raw.conversationId !== conversation ||
      raw.actorId !== 'owner' || raw.spaceId !== 'private' || raw.kind !== 'assistant' || raw.executionRole === 'owner_work')
    return unavailable('此对话尚无可核对的主请求记录。');
  const taskId = selected.id, evidence = row(raw.evidence);
  if (raw.status === 'awaiting_user' && row(evidence?.contextCompaction))
    return { ...unavailable('上下文整理等待核对，未将旧请求比例当作当前结果。', taskId), attention: true };
  let receipt: Row | null = null, at: number | null = null;
  const projected = row(raw.contextBudgetCheck);
  if (raw.status === 'awaiting_user' && row(evidence?.contextBudget)) {
    receipt = row(evidence?.contextBudget);
  } else if (raw.status === 'awaiting_user' && raw.taskProjection === 'summary') {
    return { ...unavailable('原请求等待处理，需读取原任务核对最新上下文状态。', taskId), attention: true };
  } else if (Object.hasOwn(raw, 'contextBudgetCheck')) {
    if (!projected) return unavailable('尚无可核对的本次执行上下文检查，不使用较旧事件代替。', taskId);
    if (projected.format !== 'pocketworld.context-budget-check.v1' || projected.taskId !== taskId ||
        !id(projected.runId) || projected.runId !== raw.runId || count(projected.epoch) === null ||
        Number(projected.epoch) <= 0 || projected.epoch !== raw.coordinatorEpoch)
      return unavailable('最近上下文检查的原执行身份尚未核对。', taskId);
    receipt = row(projected.receipt);
    const observed = count(projected.at) ?? count(projected.timestamp);
    at = observed !== null && observed > 0 && observed <= 8_640_000_000_000_000 ? observed : null;
  } else {
    const events = rows(raw.events);
    for (let index = events.length - 1; index >= 0; index--) {
      const event = events[index];
      if (event.type !== 'context_budget' || event.taskId !== taskId) continue;
      // A superseded attempt is not the current task's request check.
      if (!id(event.runId) || event.runId !== raw.runId || count(event.epoch) === null ||
          Number(event.epoch) <= 0 || event.epoch !== raw.coordinatorEpoch)
        return unavailable('最近上下文记录缺少可核对的原执行身份。', taskId);
      receipt = row(event.receipt);
      const observed = count(event.at) ?? count(event.timestamp);
      at = observed !== null && observed > 0 && observed <= 8_640_000_000_000_000 ? observed : null;
      break;
    }
  }
  if (!receipt) return unavailable('尚无保留的上下文检查记录，不从累计用量推算。', taskId);
  const inputTokens = count(row(receipt.after)?.estimatedInputTokens), inputLimit = count(receipt.inputLimitTokens);
  const windowTokens = count(receipt.modelContextTokens), outputReserve = count(receipt.outputReserveTokens);
  if (receipt.modelWindowKnown !== true || inputTokens === null || inputLimit === null || inputLimit <= 0 ||
      windowTokens === null || windowTokens <= 0 || outputReserve === null || inputLimit > windowTokens || outputReserve > windowTokens - inputLimit)
    return { ...unavailable('模型窗口、图像预算或原检查数据未能完整核对。', taskId), at };
  return { available: true, reason: '最近一次请求的本地保守估算，不含尚未发送的草稿；不是服务实际计数。', taskId, at,
    inputTokens, inputLimit, windowTokens, outputReserve,
    windowPercent: inputTokens / windowTokens * 100, inputPercent: inputTokens / inputLimit * 100,
    summaryUsed: receipt.summaryProjectionUsed === true,
    attention: inputTokens > inputLimit || typeof receipt.status === 'string' && receipt.status !== '' && receipt.status !== 'ready' };
}

/** A live, text-only draft estimate, not an input-token billing value or an authorization decision.
 * Attachments and visual payloads cannot safely be priced on the browser side. */
export function composerDraftPreview(text: string, attachmentCount: number): {tokens: number | null; reason: string} {
  if (!Number.isSafeInteger(attachmentCount) || attachmentCount < 0) return {tokens:null,reason:'草稿附件状态未核对。'};
  if (attachmentCount > 0) return {tokens:null,reason:'草稿含附件，图像与文档预算须由原生请求检查。'};
  if (!text.trim()) return {tokens:0,reason:'草稿为空。'};
  // UTF-8 approximation deliberately displayed as an estimate and not added to the last request.
  const bytes = new TextEncoder().encode(text).length;
  return {tokens:Math.max(1,Math.ceil(bytes/3)),reason:'文本字节估算；模型分词、工具定义和历史回复尚未重新核对。'};
}
export function contextPercentLabel(value: number | null): string {
  if (value === null || !Number.isFinite(value) || value < 0) return '未测得';
  if (value > 0 && value < 1) return '<1%';
  if (value < 100 && value > 99) return '99%';
  return `${Math.round(value)}%`;
}
