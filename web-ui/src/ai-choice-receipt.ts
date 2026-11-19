import { aiObject as object, aiRows as rows, aiText as text } from './ai-center-data';

type Row = Record<string, any>;
export type AIChoice = { source: string; sourceId: string; model: string; accountOnly?: boolean };
type Origin = { id: string; model: string; version?: number; label?: string };

/** A saved service, login and model-catalog read are not a default choice. Only
 * the native selection receipt can return work to the original conversation. */
export function apiChoice(value: Row, connections: Row[], origin?: Origin): AIChoice | null {
  const id = text(value?.selectedConnectionId), model = text(value?.selectedModel);
  if (value?.status !== 'saved' || !id || !model || origin && (id !== origin.id || model !== origin.model)) return null;
  const row = connections.find(row => row.id === id);
  return { sourceId: id, source: origin?.label || text(row?.name) || id, model };
}
export function accountChoice(value: Row, accounts: Row[], origin?: Origin): AIChoice | null {
  const receipt = object(value?.receipt), ref = object(receipt.nativeRef);
  const id = text(ref.accountId), model = text(ref.modelId);
  if (value?.status !== 'saved' || receipt.selected !== true || typeof receipt.mainInferenceReady !== 'boolean' ||
      ref.provider !== 'chatgpt_plan' || !id || !model || !Number.isSafeInteger(ref.accountVersion) || ref.accountVersion < 1 ||
      origin && (id !== origin.id || model !== origin.model || ref.accountVersion !== origin.version)) return null;
  const row = accounts.find(row => row.id === id);
  return { sourceId: id, source: origin?.label || text(row?.label) || id, model, accountOnly: !receipt.mainInferenceReady };
}
export function groupChoice(value: Row, args: Row): AIChoice | null {
  const groups = object(object(value?.snapshot).modelGroups), binding = object(object(groups.purposeBindings).MAIN);
  if (value?.status !== 'saved' || !text(args.groupId) || !Number.isSafeInteger(args.expectedRevision) || args.expectedRevision < 1 ||
      binding.kind !== 'group' || binding.groupId !== args.groupId || binding.groupRevision !== args.expectedRevision) return null;
  const group = rows(groups.groups).find(row => row.groupId === args.groupId && row.revision === args.expectedRevision);
  return { sourceId: args.groupId, source: '模型组', model: text(group?.name) || args.groupId };
}
