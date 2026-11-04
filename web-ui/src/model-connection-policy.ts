type Row = Record<string, any>;
const row = (value: unknown): Row => value && typeof value === 'object' && !Array.isArray(value) ? value as Row : {};

export const COMPAT_VALUES = {
  tokenField: ['NATIVE', 'MAX_TOKENS', 'MAX_COMPLETION_TOKENS'],
  streamUsage: ['NATIVE', 'OMIT'], toolWireShape: ['NATIVE', 'OPENAI_FUNCTION', 'ANTHROPIC_INPUT_SCHEMA'],
  schemaSubset: ['NATIVE', 'CORE_JSON'], cacheMode: ['OFF', 'EPHEMERAL'], cacheSupport: ['UNKNOWN', 'SUPPORTED', 'UNSUPPORTED'],
} as const;
export type CompatibilityDraft = {
  enabled: boolean; tokenField: string; streamUsage: string; toolWireShape: string; schemaSubset: string;
  maxTools: string; maxRequestBytes: string; cacheMode: string; cacheSupport: string;
};
export type CompatibilityOptions = Omit<CompatibilityDraft, 'enabled' | 'maxTools' | 'maxRequestBytes'> & { maxTools: number; maxRequestBytes: number };

export function compatibilityDraft(profile?: unknown): CompatibilityDraft {
  const saved = row(profile), enabled = saved.format === 'pocketworld.provider-compat.v1';
  const option = (key: keyof typeof COMPAT_VALUES, fallback: string) => enabled && (COMPAT_VALUES[key] as readonly string[]).includes(saved[key]) ? saved[key] as string : fallback;
  return {
    enabled, tokenField: option('tokenField', 'NATIVE'), streamUsage: option('streamUsage', 'NATIVE'), toolWireShape: option('toolWireShape', 'NATIVE'),
    schemaSubset: option('schemaSubset', 'NATIVE'), maxTools: String(enabled ? saved.maxTools ?? 256 : 256),
    maxRequestBytes: String(enabled ? saved.maxRequestBytes ?? 25165824 : 25165824),
    cacheMode: option('cacheMode', 'OFF'), cacheSupport: option('cacheSupport', 'UNKNOWN'),
  };
}

/** Only definition fields cross the bridge; native SettingsStore owns profile identity and revision. */
export function compatibilityOptions(draft: CompatibilityDraft, provider: string): CompatibilityOptions | null {
  if (!draft.enabled) return null;
  for (const key of Object.keys(COMPAT_VALUES) as Array<keyof typeof COMPAT_VALUES>) {
    if (!(COMPAT_VALUES[key] as readonly string[]).includes(draft[key])) throw new Error('兼容或缓存选项不在当前支持范围。');
  }
  const maxTools = Number(draft.maxTools), maxRequestBytes = Number(draft.maxRequestBytes);
  if (!/^\d+$/.test(draft.maxTools) || !Number.isInteger(maxTools) || maxTools < 1 || maxTools > 512) throw new Error('工具数量上限应为 1–512。');
  if (!/^\d+$/.test(draft.maxRequestBytes) || !Number.isInteger(maxRequestBytes) || maxRequestBytes < 256 || maxRequestBytes > 25165824) throw new Error('请求大小上限应为 256–25165824 字节。');
  if (provider !== 'openai_chat' && (draft.tokenField !== 'NATIVE' || draft.streamUsage !== 'NATIVE')) throw new Error('输出字段与流式用量选项只支持 OpenAI Chat 协议。');
  if (!['openai_chat', 'anthropic'].includes(provider) && draft.toolWireShape !== 'NATIVE') throw new Error('当前协议不支持工具声明格式变体。');
  if (provider !== 'anthropic' && draft.cacheMode !== 'OFF') throw new Error('当前协议尚未接入显式缓存提示。');
  return { tokenField: draft.tokenField, streamUsage: draft.streamUsage, toolWireShape: draft.toolWireShape, schemaSubset: draft.schemaSubset,
    maxTools, maxRequestBytes, cacheMode: draft.cacheMode, cacheSupport: draft.cacheSupport };
}

export function compatibilityNeedsSave(draft: CompatibilityDraft, original: Row | undefined,
  route: { provider: string; url: string; model: string; apiKey: string }): boolean {
  if (!original) return draft.enabled;
  const requested = compatibilityOptions(draft, route.provider);
  const sameRoute = route.provider === original.provider && route.url.trim() === original.baseUrl && route.model.trim() === original.model && !route.apiKey.trim();
  const saved = sameRoute ? row(original.configuration).compatibility : null;
  const expected = saved ? compatibilityOptions(compatibilityDraft(saved), route.provider) : null;
  return JSON.stringify(requested) !== JSON.stringify(expected);
}

export type ConnectionTestDraft = { editorId: string; draftRevision: number; connectionId: string; connectionRevision: number };
const uuid = /^[a-f\d]{8}-[a-f\d]{4}-[a-f\d]{4}-[a-f\d]{4}-[a-f\d]{12}$/i;
const sameDraft = (a: ConnectionTestDraft, b: ConnectionTestDraft) => a.editorId === b.editorId && a.draftRevision === b.draftRevision &&
  a.connectionId === b.connectionId && a.connectionRevision === b.connectionRevision;
const validReference = (value: unknown) => {
  const ref = row(value);
  return uuid.test(ref.taskId) && uuid.test(ref.runId) && Number.isInteger(ref.epoch) && ref.epoch > 0 &&
    ref.purpose === 'CONNECTION_TEST' && ref.accountingOnly === true && ref.executionAuthorized === false;
};

/** This is a presentation check. Native window, saved revision, policy and dispatch remain authoritative. */
export function connectionTestResult(response: unknown, submitted: ConnectionTestDraft, current: ConnectionTestDraft): Row | null {
  if (!sameDraft(submitted, current)) return null;
  const envelope = row(response), result = row(envelope.snapshot), proof = row(result.connectionTest);
  if (envelope.status !== 'tested' || proof.format !== 'pocketworld.connection-test.v1' || proof.presentationCurrent !== true ||
      !uuid.test(proof.testId) || !/^[a-f\d]{64}$/.test(proof.draftFingerprint) || !sameDraft(proof as ConnectionTestDraft, submitted) ||
      !['completed', 'failed', 'not_dispatched', 'unknown'].includes(proof.outcome)) return null;
  const ref = proof.inferenceReference;
  if (ref != null) {
    if (!validReference(ref) || !validReference(result.inferenceReference)) return null;
    for (const key of ['taskId', 'runId', 'epoch', 'purpose', 'accountingOnly', 'executionAuthorized']) if (ref[key] !== result.inferenceReference[key]) return null;
  } else if (['completed', 'failed'].includes(proof.outcome) || result.inferenceReference != null) return null;
  if (proof.outcome === 'completed' && result.ok !== true) return null;
  return result;
}

export function cacheCounterText(counter: unknown): string {
  const count = row(counter);
  return count.source === 'provider_reported' && Number.isSafeInteger(count.value) && count.value >= 0
    ? `${count.value} tokens（服务商报告）` : '未知（未报告，不按 0 计算）';
}

export function cacheHintText(projection: unknown): string {
  const value = row(projection);
  if (!Object.keys(value).length) return '原回执没有缓存提示证据';
  if (value.hintPrepared === true) return value.dispatchAttempted === true
    ? '已准备提示并尝试发送；送达与命中未确认' : '已准备提示，尚无发送尝试';
  return ({ cache_disabled: '已关闭缓存提示', cache_support_not_confirmed: '支持状态未确认，未添加提示',
    no_stable_system_or_tools: '本次没有稳定系统或工具前缀，未添加提示' } as Record<string, string>)[value.reason] || '原回执未记录缓存提示';
}
