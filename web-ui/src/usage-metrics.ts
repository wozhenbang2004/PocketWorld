export type UsageMetric = 'total' | 'input' | 'output' | 'cacheRead' | 'cacheWrite' | 'reasoning';
export type UsageCount = { knownSubtotal: number | null; reportedCalls: number; unknownCalls: number; partialCalls: number; overflow: boolean };
export type UsageSummary = {
  attempts: number;
  normalized: Record<UsageMetric, UsageCount>;
  cache: { rate: number | null; readTokens: number | null; inputTokens: number | null; eligibleCalls: number; unknownCalls: number; partialCalls: number };
  outcomes: Record<string, number>;
  money: { currencies: Array<{ currency: string; knownTokenSubtotal: string; calls: number; partlyPricedCalls: number; nonfinalCalls: number }>; unknownCalls: number };
  day: string; provider: string; model: string; purpose: string; conversationId: string; title: string; latestTaskLabel: string;
};
export type UsageQuery = { fromInclusive: number; toExclusive: number; zoneId: string };
export type UsageReport = {
  query: UsageQuery; totals: UsageSummary; daily: UsageSummary[]; byModel: UsageSummary[]; byPurpose: UsageSummary[]; byConversation: UsageSummary[];
  sourceReadAt: number; queryLimited: boolean; conversationsLimited: boolean; taskCount: number; invalidReceipts: number; conflictingCalls: number;
  invalidTimestamps: number; oversizedRows: number; rowsExamined: number;
};
type Data = Record<string, unknown>;
const object = (value: unknown): Data => value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Data : {};
const items = (value: unknown): Data[] => Array.isArray(value) ? value.map(object) : [];
const text = (value: unknown) => typeof value === 'string' ? value : '';
const count = (value: unknown): number | null => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : null;
const integer = (value: unknown) => count(value) ?? 0;
const metrics: UsageMetric[] = ['total', 'input', 'output', 'cacheRead', 'cacheWrite', 'reasoning'];

function usageCount(value: unknown, attempts: number): UsageCount {
  const row = object(value), subtotal = count(row.knownSubtotal);
  const overflow = row.overflow === true || row.knownSubtotal != null && subtotal === null;
  return { knownSubtotal: overflow ? null : subtotal, reportedCalls: integer(row.reportedCalls),
    unknownCalls: count(row.unknownCalls) ?? attempts, partialCalls: integer(row.partialCalls), overflow };
}

function summary(value: unknown): UsageSummary {
  const row = object(value), normalized = object(row.normalized), cache = object(row.cache), money = object(row.money), identity = object(row.identity);
  const attempts = integer(row.attempts), rate = cache.rate;
  const outcomes: Record<string, number> = {};
  for (const [key, value] of Object.entries(object(row.outcomes))) outcomes[key] = integer(value);
  return {
    attempts, normalized: Object.fromEntries(metrics.map(key => [key, usageCount(normalized[key], attempts)])) as Record<UsageMetric, UsageCount>,
    cache: { rate: typeof rate === 'number' && Number.isFinite(rate) && rate >= 0 && rate <= 1 ? rate : null,
      readTokens: count(cache.readTokens), inputTokens: count(cache.inputTokens), eligibleCalls: integer(cache.eligibleCalls),
      unknownCalls: count(cache.unknownCalls) ?? attempts, partialCalls: integer(cache.partialCalls) },
    outcomes,
    money: { currencies: items(money.currencies).filter(item => /^[A-Z]{3}$/.test(text(item.currency)) && /^\d+(?:\.\d+)?$/.test(text(item.knownTokenSubtotal))).map(item => ({
      currency: text(item.currency), knownTokenSubtotal: text(item.knownTokenSubtotal), calls: integer(item.calls),
      partlyPricedCalls: integer(item.partlyPricedCalls), nonfinalCalls: integer(item.nonfinalCalls),
    })), unknownCalls: count(money.unknownCalls) ?? attempts },
    day: text(row.day || identity.day), provider: text(row.provider || identity.provider), model: text(row.model || identity.model),
    purpose: text(row.purpose || identity.purpose), conversationId: text(row.conversationId || identity.conversationId), title: text(row.title || identity.title), latestTaskLabel: text(row.latestTaskLabel || identity.latestTaskLabel),
  };
}

/** Accept only the native, read-only projection for the exact requested range. */
export function usageReport(value: unknown, expected: UsageQuery): UsageReport {
  const row = object(value), query = object(row.query);
  if (row.format !== 'pocketworld.usage-trends.v1' || row.readOnly !== true || row.executionAuthorized !== false ||
      row.scope !== 'owner/private_supported_local_origins_only' || query.fromInclusive !== expected.fromInclusive ||
      query.toExclusive !== expected.toExclusive || query.zoneId !== expected.zoneId ||
      !Array.isArray(row.daily) || !Array.isArray(row.byModel) || !Array.isArray(row.byPurpose) || !object(row.totals).normalized)
    throw new Error('用量范围未能核对，请重新读取。');
  if (row.daily.length > 92 || row.byModel.length > 8192 || row.byPurpose.length > 8192)
    throw new Error('用量结果超出本次读取范围。');
  return { query: expected, totals: summary(row.totals), daily: items(row.daily).map(summary), byModel: items(row.byModel).map(summary),
    byPurpose: items(row.byPurpose).map(summary), byConversation: items(row.byConversation).map(summary),
    sourceReadAt: integer(row.sourceReadAt), queryLimited: row.queryLimited === true, conversationsLimited: row.conversationsLimited === true,
    taskCount: integer(row.taskCount), invalidReceipts: integer(row.invalidReceipts), conflictingCalls: integer(row.conflictingCalls),
    invalidTimestamps: integer(row.invalidTimestamps), oversizedRows: integer(row.oversizedRows), rowsExamined: integer(row.rowsExamined) };
}

export function localDateKey(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}
export function usageRange(days: number, now = new Date()): { query: UsageQuery; days: string[] } {
  const year = now.getFullYear(), month = now.getMonth(), day = now.getDate();
  // Construct each local date independently: a skipped midnight must not carry
  // its corrected 01:00 into every other day of the range.
  const to = new Date(year, month, day + 1), from = new Date(year, month, day - days + 1);
  const dates = Array.from({ length: days }, (_, index) => localDateKey(new Date(year, month, day - days + 1 + index)));
  return { query: { fromInclusive: from.getTime(), toExclusive: to.getTime(), zoneId: Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC' }, days: dates };
}
export const metricLabels: Record<UsageMetric, string> = { total: '总量', input: '输入', output: '输出', cacheRead: '缓存读取', cacheWrite: '缓存写入', reasoning: '推理' };
export const isPartialCount = (value: UsageCount) => value.unknownCalls > 0 || value.partialCalls > 0 || value.overflow;
export function tokenNumber(value: number | null, compact = false): string {
  if (value === null) return '—';
  if (compact && value >= 1_000_000) return `${(value / 1_000_000).toLocaleString('zh-CN', { maximumFractionDigits: 2 })}M`;
  if (compact && value >= 10_000) return `${(value / 1_000).toLocaleString('zh-CN', { maximumFractionDigits: 1 })}K`;
  return value.toLocaleString('zh-CN');
}
export function countLabel(value: UsageCount, compact = false): string {
  return value.knownSubtotal === null ? '未报告' : `${tokenNumber(value.knownSubtotal, compact)}${isPartialCount(value) ? ' · 已知' : ''}`;
}
export function countCoverage(value: UsageCount): string {
  if (value.overflow) return '计数超出可核对范围';
  if (!value.reportedCalls) return '服务未提供可用计数';
  return [`${value.reportedCalls} 次已报告`, value.unknownCalls ? `${value.unknownCalls} 次未报告` : '', value.partialCalls ? `${value.partialCalls} 次尚非最终值` : ''].filter(Boolean).join(' · ');
}
export function rateLabel(rate: number | null): string {
  if (rate === null) return '—';
  if (rate > 0 && rate < .001) return '<0.1%';
  return `${(rate * 100).toLocaleString('zh-CN', { maximumFractionDigits: 1 })}%`;
}
const purposeNames: Record<string, string> = {
  assistant: '对话与执行', foreground: '对话', owner_work: '后台任务', primary: '主请求', main: '主请求',
  chat: '对话', inference: '模型请求', response: '回复', tool_loop: '工具执行', context_compaction: '上下文压缩',
  compaction: '上下文压缩', summarize: '摘要', summary: '摘要', title: '标题生成', title_generation: '标题生成',
  memory: '记忆整理', memory_extraction: '记忆提炼', team: '团队', team_research: '团队研究', research: '研究',
  embedding: '向量检索', connection_test: '连接核对', model_probe: '能力核对', realtime: '实时通话',
  main_inference: '主推理', context_summary: '上下文摘要', conversation_title: '对话标题', memory_reflection: '记忆整理',
};
export function purposeLabel(purpose: string): string {
  return purposeNames[purpose] || purposeNames[purpose.toLowerCase()] || purpose || '未标注用途';
}
