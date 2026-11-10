import { RiBarChartGroupedFill } from '@remixicon/react';
import { IconControls, IconError, IconHelp, IconLoading, IconSparkles } from './icons';
import { nativeReasoning, type ReasoningValue } from './conversation-model-controls';

const orderedLevels = ['minimal', 'low', 'medium', 'high', 'xhigh', 'max'];

/** A native level's ordinal position, never a cost or performance estimate. */
export function ReasoningIndicator({ reasoning, loading = false, error = false }: {
  reasoning: ReasoningValue | null;
  loading?: boolean;
  error?: boolean;
}) {
  const value = nativeReasoning(reasoning), level = value ? orderedLevels.indexOf(value.level) + 1 : 0;
  const state = loading ? 'loading' : error ? 'error' : !value ? 'unknown' : value.mode === 'DEFAULT' ? 'default'
    : value.mode.endsWith('_BUDGET') ? 'budget' : level ? 'level' : 'custom';
  const Icon = state === 'loading' ? IconLoading : state === 'error' ? IconError : state === 'unknown' ? IconHelp
    : state === 'default' ? IconSparkles : IconControls;
  return <i className="pw-reasoning-indicator" data-reasoning-state={state} data-reasoning-level={state === 'level' ? level : undefined} aria-hidden="true">
    {state === 'level' ? <>
      <RiBarChartGroupedFill size={20} className="pw-reasoning-level-base" />
      <RiBarChartGroupedFill size={20} className="pw-reasoning-level-active" />
    </> : <Icon size={20} />}
  </i>;
}
