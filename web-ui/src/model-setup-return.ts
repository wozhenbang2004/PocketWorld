import type { PocketSnapshot } from './bridge';
import { aiObject as object } from './ai-center-data';
import { matchesChoicePreference, type AIChoice } from './ai-choice-receipt';

export type ModelSetupInput = { conversation: string; key: string; epoch: number; document: number };
export type ModelSetupUse = 'inherited' | 'explicit' | 'different' | 'unknown' | 'running' | 'unavailable' | 'changed';

/** Navigation only: no text, credential, model grant, or draft write. */
export function modelSetupInput(value: PocketSnapshot, document: number): ModelSetupInput | null {
  const c = value.composer, conversation = String(value.state.conversationId || '');
  if (c?.ready !== true || typeof c.key !== 'string' || !c.key || c.conversationId !== conversation ||
      !Number.isSafeInteger(c.epoch) || c.epoch < 0 || value.state.conversationReader || object(value.view).taskId) return null;
  return { conversation, key: c.key, epoch: c.epoch, document };
}

export function sameModelSetupInput(input: ModelSetupInput, value: PocketSnapshot, document: number): boolean {
  const live = modelSetupInput(value, document);
  return !!live && live.document === input.document && live.conversation === input.conversation && live.key === input.key && live.epoch === input.epoch;
}

export function modelSetupUse(input: ModelSetupInput, choice: AIChoice, state: PocketSnapshot['state'], matchesDefault: boolean | undefined): ModelSetupUse {
  if (choice.accountOnly) return 'unavailable';
  if (state.chatBusy || state.chatSubmitting) return 'running';
  const preference = object(state.conversationModel);
  const scope = input.conversation ? `conversation:${input.conversation}` : `draft:${input.key}`;
  if (preference.scopeKey !== scope || !Number.isSafeInteger(preference.revision) || preference.revision < 0 ||
      state.modelSelectionBusy !== false || !choice.target) return 'unknown';
  if (preference.inherited === false) {
    const match = matchesChoicePreference(choice, object(preference.target));
    if (match === false) return 'different';
    return match === true ? 'explicit' : 'unknown';
  }
  if (preference.inherited !== true || preference.target != null) return 'unknown';
  return matchesDefault === true ? 'inherited' : matchesDefault === false ? 'different' : 'unknown';
}

export function modelSetupUseMessage(choice: AIChoice, use: ModelSetupUse): string {
  const label = `${choice.model} · ${choice.source}`;
  switch (use) {
    case 'inherited': return `核对时，原输入跟随默认模型：${label}。草稿未自动发送。`;
    case 'explicit': return `原输入已单独选择：${label}。发送时仍会核对连接。`;
    case 'different': return `已记录 ${label}，但原输入当前未选择它。原选择未改。`;
    case 'running': return `已选择 ${label}。进行中的回复保持启动时的模型。`;
    case 'unavailable': return `${label} 尚不能用于管家，原选择未改。`;
    case 'changed': return '模型设置在上次核对后已变化。原输入没有自动绑定，请核对接收模型。';
    case 'unknown': return `已记录 ${label}，尚未核对原输入是否使用它。草稿未自动发送。`;
  }
}
