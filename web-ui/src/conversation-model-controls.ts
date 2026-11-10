import { accountModelReasoningLevels, accountModelReviewCurrent, accountModelSelection } from './composer-account-models.ts';

export type ModelControlRecord = Record<string, any>;
export type ReasoningValue = { mode: string; level: string; budgetTokens: number };
export type ReasoningChoice = { id: string; label: string; detail?: string; value: ReasoningValue; readOnly?: boolean };
export type ReasoningTarget = {
  route: 'api_key' | 'account'; modelId: string; provider: string; identity: string;
  selection: ModelControlRecord; reasoning: ReasoningValue | null; restriction: string; facts: ModelControlRecord;
};
export type ReasoningReview = {
  route: 'api_key' | 'account'; revision: number; target: ReasoningTarget; review: ModelControlRecord;
  choices: ReasoningChoice[]; currentId: string; expiresAt: number;
};
const record = (value: any): ModelControlRecord => value && typeof value === 'object' && !Array.isArray(value) ? value : {};
const text = (value: any): string => typeof value === 'string' ? value : '';
export const modelRevision = (value: any): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
const canonical = (value: any): string => JSON.stringify(value && typeof value === 'object'
  ? Array.isArray(value) ? value.map(item => JSON.parse(canonical(item))) : Object.fromEntries(Object.keys(value).sort().map(key => [key, JSON.parse(canonical(value[key]))]))
  : value ?? null);
const same = (left: any, right: any) => canonical(left) === canonical(right);
export const reasoningId = (value: ReasoningValue): string => JSON.stringify([value.mode, value.level, value.budgetTokens]);

/** DEFAULT omits the request field; it never means that reasoning was switched off. */
export function nativeReasoning(value: any): ReasoningValue | null {
  const item = record(value), mode = text(item.mode), level = text(item.level), budget = item.budgetTokens;
  if (typeof budget !== 'number' || !Number.isSafeInteger(budget) || budget < -1 || budget > 2_147_483_647) return null;
  if (mode === 'DEFAULT') return level === '' && budget === 0 ? { mode, level, budgetTokens: budget } : null;
  if (['OPENAI_EFFORT', 'ANTHROPIC_ADAPTIVE', 'GEMINI_LEVEL'].includes(mode)) return /^[A-Za-z0-9_-]{1,64}$/.test(level) && budget === 0 ? { mode, level, budgetTokens: budget } : null;
  if (mode === 'ANTHROPIC_BUDGET' || mode === 'GEMINI_BUDGET') return !level && budget >= (mode === 'ANTHROPIC_BUDGET' ? 1024 : -1) ? { mode, level, budgetTokens: budget } : null;
  return null;
}
const defaultReasoning: ReasoningValue = { mode: 'DEFAULT', level: '', budgetTokens: 0 };
const levelLabels: Record<string, string> = { none: '关闭', minimal: '最小', low: '低', medium: '中', high: '高', xhigh: '极高', max: '最高' };
export function reasoningLabel(value: ReasoningValue | null, compact = false): string {
  if (!value) return '';
  if (value.mode === 'DEFAULT') return '自动';
  if (value.mode.endsWith('_BUDGET')) {
    if (value.budgetTokens === -1) return '动态';
    if (value.budgetTokens === 0) return '关闭';
    return compact ? `${value.budgetTokens.toLocaleString()}` : `思考预算 ${value.budgetTokens.toLocaleString()} tokens`;
  }
  return levelLabels[value.level] || value.level;
}
function namedMode(provider: string): string {
  if (provider === 'openai_chat' || provider === 'openai_responses' || provider === 'chatgpt_plan') return 'OPENAI_EFFORT';
  if (provider === 'anthropic') return 'ANTHROPIC_ADAPTIVE';
  if (provider === 'gemini') return 'GEMINI_LEVEL';
  return '';
}

/** Reads the actual native selection, never the last API profile viewed in settings. */
export function currentReasoningTarget(value: any): ReasoningTarget {
  const snapshot = record(value), selection = record(snapshot.nativeSelection);
  if (snapshot.selectionUnavailable === true) throw new Error(text(snapshot.selectionMessage) || '当前模型暂不可用，请重新选择模型。');
  const route = selection.modelRoute, provider = text(selection.provider), modelId = text(selection.model);
  if (!modelId || !provider || !['account', 'api_key'].includes(route) || snapshot.activeRoute !== route) throw new Error('当前模型状态尚未读取，请重新读取。');
  const reasoning = nativeReasoning(selection.reasoning);
  let restriction = selection.modelRoutePlan ? '当前模型由用途配置指定，请在模型配置中调整推理设置。' : '';
  let identity: string, facts: ModelControlRecord = {};
  if (route === 'account') {
    const ref = record(selection.nativeRef);
    if (provider !== 'chatgpt_plan' || ref.provider !== 'chatgpt_plan' || !text(ref.accountId) || !modelRevision(ref.accountVersion) || ref.modelId !== modelId) throw new Error('当前账号模型身份不完整，请重新读取。');
    identity = canonical([route, ref]);
  } else {
    const api = record(snapshot.api), binding = record(snapshot.binding), activeBinding = record(selection.capabilityBinding);
    identity = canonical([route, provider, modelId, activeBinding]);
    if (!restriction && (selection.catalogEnforced !== true || snapshot.catalogEnabled !== true || !text(binding.connectionId))) restriction = '此连接尚未启用模型能力目录，暂不能调整推理强度。';
    if (!restriction && (api.model !== modelId || api.provider !== provider || !same(binding, activeBinding) || !same(api.capabilityBinding, activeBinding) ||
      !same(api.reasoning, selection.reasoning) || api.maxOutputTokens !== selection.maxOutputTokens || api.imageTokenReserve !== selection.imageTokenReserve)) restriction = '当前模型与正在管理的 API 连接不同，请在模型配置中核对。';
    const selected = record(snapshot.selected);
    if (selected.modelId === modelId && same(selected.binding, activeBinding)) facts = record(selected.facts);
  }
  if (!restriction && !reasoning) restriction = '当前推理设置暂不可读取，请重新读取模型状态。';
  return { route, modelId, provider, identity, selection, reasoning, restriction, facts };
}

/** Gemini 2.5 uses thinkingBudget, not Gemini 3's thinkingLevel. */
export function geminiBudgetModel(model: string): boolean {
  return /^(?:models[/])?gemini-2[.]5-(?:pro|flash|flash-lite)(?:-(?:[0-9]{4}-[0-9]{2}-[0-9]{2}|[0-9]{8}))?$/.test(model);
}

/** Choices are attached to exact native facts and never guessed from the API URL.
 * Gemini 2.5's qualitative labels are PocketWorld budget presets, not wire enum values. */
export function reasoningChoices(facts: ModelControlRecord, provider: string, current: ReasoningValue, modelId = '', maxOutputTokens = 0): ReasoningChoice[] {
  const choices: ReasoningChoice[] = [{ id: reasoningId(defaultReasoning), label: '自动', detail: '沿用接口默认设置', value: defaultReasoning }];
  const mode = namedMode(provider), levels = record(facts.REASONING_LEVELS);
  const budget = provider === 'gemini' && geminiBudgetModel(modelId);
  const budgets: Record<string, number> = { low: 512, medium: 2048, high: 4096 };
  if (mode && facts.REASONING?.known === true && facts.REASONING?.value === true && levels.known === true && Array.isArray(levels.value)) {
    for (const level of new Set(levels.value.filter((item: any): item is string => typeof item === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(item)))) {
      if (budget) {
        const tokens = budgets[level];
        // Keep room for the visible answer. The native policy checks the budget again.
        if (!tokens || !Number.isSafeInteger(maxOutputTokens) || tokens >= maxOutputTokens) continue;
        const value: ReasoningValue = { mode: 'GEMINI_BUDGET', level: '', budgetTokens: tokens };
        choices.push({ id: reasoningId(value), label: levelLabels[level] || level, detail: `预算 ${tokens.toLocaleString()} tokens`, value });
      } else {
        const value: ReasoningValue = { mode, level: String(level), budgetTokens: 0 };
        choices.push({ id: reasoningId(value), label: levelLabels[String(level)] || String(level), detail: levelLabels[String(level)] ? String(level) : undefined, value });
      }
    }
  }
  const currentId = reasoningId(current);
  if (!choices.some(choice => choice.id === currentId)) choices.push({ id: currentId, label: reasoningLabel(current), detail: '当前设置', value: current, readOnly: true });
  return choices;
}
export function reasoningAvailability(facts: ModelControlRecord): string {
  return facts.REASONING?.known === true && facts.REASONING.value === false ? '此模型不支持调整思考强度。' : '暂时没有可直接使用的档位。';
}
export function sameAccountTarget(value: any, target: ReasoningTarget): boolean {
  const snapshot = record(record(value).accountSnapshot), ref = target.selection.nativeRef;
  return target.route === 'account' && same(snapshot.selectedModelRef, ref) && same(record(snapshot.selectedModelOptions).nativeRef, ref);
}
function expiry(review: ModelControlRecord, now: number): number {
  if (typeof review.expiresInMs !== 'number' || !Number.isSafeInteger(review.expiresInMs) || review.expiresInMs <= 0 || review.expiresInMs > 120_000) throw new Error('推理设置窗口已失效，请重新读取。');
  return now + review.expiresInMs;
}
export function apiReasoningReview(target: ReasoningTarget, review: ModelControlRecord, revision: any, now = Date.now()): ReasoningReview {
  if (target.route !== 'api_key' || target.restriction || !target.reasoning || !modelRevision(revision) || !text(review.reviewHandle) || review.modelId !== target.modelId ||
    !same(nativeReasoning(review.reasoning), target.reasoning) || review.outputTokens !== target.selection.maxOutputTokens || review.imageTokenReserve !== target.selection.imageTokenReserve) throw new Error('原模型或推理设置已变化，请重新读取。');
  return { route: 'api_key', revision, target, review, choices: reasoningChoices(target.facts, target.provider, target.reasoning, target.modelId, Number(target.selection.maxOutputTokens)), currentId: reasoningId(target.reasoning), expiresAt: expiry(review, now) };
}
export function accountReasoningReview(target: ReasoningTarget, review: ModelControlRecord, revision: any, now = Date.now()): ReasoningReview {
  const options = record(review.options), ref = record(target.selection.nativeRef);
  if (target.route !== 'account' || target.restriction || !target.reasoning || !modelRevision(revision) || !accountModelReviewCurrent(review, revision) || !sameAccountTarget(review, target) ||
    review.accountId !== ref.accountId || review.accountVersion !== ref.accountVersion || review.modelId !== target.modelId ||
    options.reasoningEffort !== target.reasoning.level || options.outputReserve !== target.selection.maxOutputTokens || options.imageTokenReserve !== target.selection.imageTokenReserve) throw new Error('原账号模型或推理设置已变化，请重新读取。');
  const facts = record(record(review.catalogFacts).facts);
  // The existing account helper is the source of exact supported level strings.
  const exactFacts = { ...facts, REASONING_LEVELS: { ...record(facts.REASONING_LEVELS), value: accountModelReasoningLevels(review) } };
  return { route: 'account', revision, target, review, choices: reasoningChoices(exactFacts, target.provider, target.reasoning), currentId: reasoningId(target.reasoning), expiresAt: expiry(review, now) };
}
export function reasoningSelection(session: ReasoningReview, revision: any, choiceId: string, now = Date.now()): ModelControlRecord {
  const choice = session.choices.find(item => item.id === choiceId && !item.readOnly);
  if (!modelRevision(revision) || session.revision !== revision || now >= session.expiresAt || !choice || choiceId === session.currentId) throw new Error('原推理设置窗口已变化，请重新读取后选择。');
  if (session.route === 'api_key') return { reviewHandle: session.review.reviewHandle, reasoning: { ...choice.value } };
  return accountModelSelection(session.review, revision, { reasoningEffort: choice.value.mode === 'DEFAULT' ? '' : choice.value.level,
    outputReserve: String(session.review.options.outputReserve), imageTokenReserve: String(session.review.options.imageTokenReserve) });
}
export function reasoningSavedSnapshot(value: any, original: ReasoningTarget, intended: ReasoningValue): ReasoningTarget {
  const current = currentReasoningTarget(value);
  if (current.restriction || current.identity !== original.identity || !same(current.reasoning, intended) ||
    current.selection.maxOutputTokens !== original.selection.maxOutputTokens || current.selection.imageTokenReserve !== original.selection.imageTokenReserve) throw new Error('保存后的当前模型状态待核对，请重新读取。');
  return current;
}
