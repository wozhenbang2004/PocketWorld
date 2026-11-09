import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync } from 'node:fs';
import React from 'react';
import ts from 'typescript';
import * as remix from '@remixicon/react';
import { accountReasoningReview, apiReasoningReview, currentReasoningTarget, nativeReasoning, reasoningChoices,
  reasoningId, reasoningLabel, reasoningSavedSnapshot, reasoningSelection, sameAccountTarget } from '../src/conversation-model-controls.ts';

// This pure render exercises state semantics; the browser checks the final pixels.
const indicatorAst = ts.createSourceFile('ReasoningIndicator.tsx', readFileSync(new URL('../src/ReasoningIndicator.tsx', import.meta.url), 'utf8'), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
const indicatorSource = indicatorAst.statements.filter(node => !ts.isImportDeclaration(node)).map(node => node.getText(indicatorAst).replace(/^export /, '')).join('\n');
const indicatorCode = ts.transpileModule(indicatorSource, { compilerOptions: { target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React } }).outputText;
const indicatorImports = { React, nativeReasoning, RiBarChartGroupedFill: remix.RiBarChartGroupedFill, RiEqualizerLine: remix.RiEqualizerLine,
  RiErrorWarningLine: remix.RiErrorWarningLine, RiLoader4Line: remix.RiLoader4Line, RiQuestionLine: remix.RiQuestionLine, RiSparklingLine: remix.RiSparklingLine };
const indicator = new Function(...Object.keys(indicatorImports), indicatorCode + '\nreturn ReasoningIndicator;')(...Object.values(indicatorImports));

const defaults = { mode: 'DEFAULT', level: '', budgetTokens: 0 };
const medium = { mode: 'OPENAI_EFFORT', level: 'medium', budgetTokens: 0 };
const high = { mode: 'OPENAI_EFFORT', level: 'high', budgetTokens: 0 };
const binding = { connectionId: 'original-api', credentialRevision: 3, providerId: 'openai', protocol: 'OPENAI_CHAT' };
const facts = { REASONING: { known: true, value: true }, REASONING_LEVELS: { known: true, value: ['medium', 'high'] } };
function apiSnapshot() {
  const selection = { modelRoute: 'api_key', provider: 'openai_chat', model: 'same-model', catalogEnforced: true,
    capabilityBinding: binding, reasoning: medium, maxOutputTokens: 7168, imageTokenReserve: 2800 };
  return { activeRoute: 'api_key', catalogEnabled: true, binding, api: structuredClone(selection), nativeSelection: structuredClone(selection), selected: { modelId: 'same-model', binding, facts } };
}
const apiReview = () => ({ reviewHandle: 'one-use-api-review', modelId: 'same-model', reasoning: medium, outputTokens: 7168, imageTokenReserve: 2800, expiresInMs: 120000 });
const nativeRef = { provider: 'chatgpt_plan', accountId: 'account-a', accountVersion: 4, modelId: 'same-model' };
function accountSnapshot() {
  return { activeRoute: 'account', nativeSelection: { modelRoute: 'account', model: 'same-model', provider: 'chatgpt_plan', nativeRef,
    reasoning: medium, maxOutputTokens: 5120, imageTokenReserve: 3600 } };
}
function accountReview() {
  return { windowId: 'original-account-window', reviewHandle: 'one-use-account-review', selectionRevision: 7, viewGeneration: 1,
    accountId: 'account-a', accountVersion: 4, modelId: 'same-model', expiresInMs: 120000,
    accountSnapshot: { accounts: [{ id: 'account-a', version: 4 }], selectedModelRef: nativeRef,
      selectedModelOptions: accountSnapshot().nativeSelection },
    catalogFacts: { modelId: 'same-model', facts }, options: { reasoningEffort: 'medium', outputReserve: 5120, imageTokenReserve: 3600 } };
}

test('the actual active model is distinct from the API profile last opened in settings', () => {
  const snapshot = apiSnapshot();
  assert.equal(currentReasoningTarget(snapshot).restriction, '');
  snapshot.nativeSelection.capabilityBinding = { ...binding, connectionId: 'same-name-other-api' };
  assert.match(currentReasoningTarget(snapshot).restriction, /不同/);
  assert.throws(() => currentReasoningTarget({ api: apiSnapshot().api }), /尚未读取/);
  assert.throws(() => currentReasoningTarget({ ...apiSnapshot(), selectionUnavailable: true }), /暂不可用/);
});
test('a reasoning-only edit cannot clear a model-group or MAIN purpose selection', () => {
  const snapshot = { ...apiSnapshot(), nativeSelection: { ...apiSnapshot().nativeSelection, modelRoutePlan: { format: 'pocketworld.model-route-plan.v1', groupRevision: 2 } } };
  const target = currentReasoningTarget(snapshot);
  assert.match(target.restriction, /用途配置/);
  assert.throws(() => apiReasoningReview(target, apiReview(), 7));
  assert.match(currentReasoningTarget({ ...apiSnapshot(), catalogEnabled: false }).restriction, /尚未启用/);
});
test('only declared levels become choices; DEFAULT is not advertised as thinking off', () => {
  assert.deepEqual(reasoningChoices(facts, 'openai_chat', medium).map(choice => choice.value.level), ['', 'medium', 'high']);
  assert.deepEqual(reasoningChoices({ ...facts, REASONING: { known: false, value: true } }, 'openai_chat', defaults).map(choice => choice.value.level), ['']);
  assert.deepEqual(reasoningChoices({ ...facts, REASONING_LEVELS: { known: false, value: ['high'] } }, 'openai_chat', defaults).map(choice => choice.value.level), ['']);
  assert.equal(reasoningLabel(defaults), '服务默认');
  assert.equal(reasoningLabel(null), '');
  assert.equal(nativeReasoning({ mode: 'DEFAULT', level: 'high', budgetTokens: 0 }), null);
});
test('the indicator distinguishes native defaults, ranked levels, budgets and unread states without inventing strength', () => {
  for (const [reasoning, expected] of [[null, 'unknown'], [defaults, 'default'], [{ ...defaults, level: 'high' }, 'unknown'],
    [{ ...high, mode: 'UNDECLARED' }, 'unknown'], [{ ...high, level: 'future-level' }, 'custom'], [{ mode: 'ANTHROPIC_BUDGET', level: '', budgetTokens: 2048 }, 'budget']] as const) {
    const result = indicator({ reasoning });
    assert.equal(result.props['data-reasoning-state'], expected);
    assert.equal(result.props['data-reasoning-level'], undefined);
    assert.equal(result.props['aria-hidden'], 'true');
  }
  assert.equal(indicator({ reasoning: defaults, loading: true }).props['data-reasoning-state'], 'loading');
  assert.equal(indicator({ reasoning: defaults, error: true }).props['data-reasoning-state'], 'error');
  for (const [index, level] of ['minimal', 'low', 'medium', 'high', 'xhigh', 'max'].entries()) {
    const result = indicator({ reasoning: { ...high, level } });
    assert.equal(result.props['data-reasoning-state'], 'level');
    assert.equal(result.props['data-reasoning-level'], index + 1);
  }
});
test('each ordinal reveals whole existing Remix bars in left-to-right geometric order', () => {
  const path = (remix.RiBarChartGroupedFill({}) as any).props.children.props.d as string;
  const bars = [...path.matchAll(/M(\d+) (\d+)H(\d+)V(\d+)H(\d+)V(\d+)Z/g)]
    .map(match => ({ left: Number(match[1]), right: Number(match[3]) })).sort((a, b) => a.left - b.left);
  assert.equal(bars.length, 6, 'an upstream icon change requires rechecking the reveal geometry');
  const css = readFileSync(new URL('../src/conversation-model-controls.css', import.meta.url), 'utf8');
  for (let level = 1; level <= 6; level++) {
    const rule = css.match(new RegExp(`data-reasoning-level="${level}"\\] \\{ --pw-reasoning-hidden: ([\\d.]+)%`));
    assert.ok(rule || level === 6, 'all partial reveals need an explicit boundary');
    const cutoff = 24 * (1 - Number(rule?.[1] || 0) / 100);
    assert.equal(bars.filter(bar => bar.right < cutoff).length, level);
    assert.equal(bars.some(bar => bar.left < cutoff && bar.right > cutoff), false, 'no cutoff may cross an original bar');
  }
});
test('a retained budget is displayed accurately but is never converted to an invented strength', () => {
  const budget = { mode: 'ANTHROPIC_BUDGET', level: '', budgetTokens: 2048 };
  const choices = reasoningChoices({ REASONING: { known: true, value: true } }, 'anthropic', budget);
  assert.equal(choices.length, 2);
  assert.equal(choices[1].readOnly, true);
  assert.deepEqual(choices[1].value, budget);
  assert.equal(reasoningLabel(budget, true), '预算');
});
test('API strength applies only the original one-use review and exact new reasoning, retaining reserves natively', () => {
  const target = currentReasoningTarget(apiSnapshot()), review = apiReview();
  const session = apiReasoningReview(target, review, 7, 1000);
  assert.deepEqual(reasoningSelection(session, 7, reasoningId(high), 1001), { reviewHandle: 'one-use-api-review', reasoning: high });
  assert.throws(() => reasoningSelection(session, 8, reasoningId(high), 1001), /已变化/);
  assert.throws(() => reasoningSelection(session, 7, reasoningId(high), 121000), /已变化/);
  assert.throws(() => reasoningSelection(session, 7, reasoningId({ ...high, level: 'ultra' }), 1001));
  assert.throws(() => apiReasoningReview(target, { ...review, outputTokens: 4096 }, 7), /已变化/);
  assert.throws(() => apiReasoningReview(target, { ...review, reasoning: defaults }, 7), /已变化/);
});
test('account strength retains the exact current account version, original opaque review and local reserves', () => {
  const target = currentReasoningTarget(accountSnapshot()), review = accountReview();
  const session = accountReasoningReview(target, review, 7, 1000);
  assert.deepEqual(reasoningSelection(session, 7, reasoningId(high), 1001), {
    windowId: 'original-account-window', reviewHandle: 'one-use-account-review',
    options: { reasoningEffort: 'high', outputReserve: 5120, imageTokenReserve: 3600 },
  });
  assert.equal(sameAccountTarget(review, target), true);
  const another = { ...review, accountSnapshot: { ...review.accountSnapshot, selectedModelRef: { ...nativeRef, accountId: 'same-name-other-account' } } };
  assert.equal(sameAccountTarget(another, target), false);
  assert.throws(() => accountReasoningReview(target, another, 7));
  assert.throws(() => accountReasoningReview(target, { ...review, accountVersion: 5 }, 7));
  assert.throws(() => accountReasoningReview(target, { ...review, options: { ...review.options, imageTokenReserve: 0 } }, 7));
});
test('account service default clears only the effort and keeps output and image reserves', () => {
  const session = accountReasoningReview(currentReasoningTarget(accountSnapshot()), accountReview(), 7, 1000);
  assert.deepEqual(reasoningSelection(session, 7, reasoningId(defaults), 1001).options, { reasoningEffort: '', outputReserve: 5120, imageTokenReserve: 3600 });
});
test('save confirmation requires the real selected identity, intended strength and unchanged reserves', () => {
  const before = apiSnapshot(), target = currentReasoningTarget(before), after = apiSnapshot();
  after.api.reasoning = high; after.nativeSelection.reasoning = high;
  assert.deepEqual(reasoningSavedSnapshot(after, target, high).reasoning, high);
  assert.throws(() => reasoningSavedSnapshot(before, target, high), /待核对/);
  after.api.imageTokenReserve = 0; after.nativeSelection.imageTokenReserve = 0;
  assert.throws(() => reasoningSavedSnapshot(after, target, high), /待核对/);
});
