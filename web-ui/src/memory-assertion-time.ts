/** The Book stores nonnegative Java longs. A WebView may not round one back into a write. */
export const assertionTimes = [['eventTime', '发生或计划时间'], ['validFrom', '生效时间'], ['validTo', '结束时间'], ['reviewAfter', '下次核对时间']] as const;
export type TimeKey = (typeof assertionTimes)[number][0];
export type AssertionTime = { kind: 'unknown' | 'outside' | 'invalid' } | { kind: 'date'; ms: number };
export function assertionTime(value: unknown): AssertionTime {
  if (value === undefined || value === null) return { kind: 'unknown' };
  if (typeof value === 'number' && Number.isFinite(value) && value > 8.64e15) return { kind: 'outside' };
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? { kind: 'date', ms: value } : { kind: 'invalid' };
}
export function displayAssertionTime(value: AssertionTime): string {
  if (value.kind !== 'date') return value.kind === 'unknown' ? '未知' : value.kind === 'outside' ? '原记录有数值，超出手机可显示的日期范围' : '原时间无法识别';
  return new Date(value.ms).toLocaleString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', ...(value.ms % 1000 ? { fractionalSecondDigits: 3 as const } : {}), timeZoneName: 'short' });
}
