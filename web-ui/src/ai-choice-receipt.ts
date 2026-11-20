import { aiObject as object, aiRows as rows, aiText as text } from './ai-center-data';

type Row = Record<string, any>;
type ChoiceTarget = { kind: 'api'; binding: string } | { kind: 'account' | 'group'; version: number };
export type AIChoice = { source: string; sourceId: string; model: string; accountOnly?: boolean; target?: ChoiceTarget };
type Origin = { id: string; model: string; version?: number; label?: string; binding?: Row };

function apiBinding(value: unknown, id: string): string {
  const row = object(value), connection = [row.connectionId, row.providerId, row.protocol, row.format, row.credentialId, row.installationId];
  if (row.connectionId !== id || !connection.every(field => typeof field === 'string' && field) ||
      typeof row.modelsEndpoint !== 'string' || typeof row.cleartextApproved !== 'boolean' ||
      ![row.credentialRevision, row.installationRevision].every(n => Number.isSafeInteger(n) && n > 0)) return '';
  // Only public identifiers and generations, never the credential or full configuration.
  return JSON.stringify([...connection, row.modelsEndpoint, row.credentialRevision, row.installationRevision, row.cleartextApproved]);
}

/** A saved service, login and model-catalog read are not a default choice. Only
 * the native selection receipt can return work to the original conversation. */
export function apiChoice(value: Row, connections: Row[], origin?: Origin): AIChoice | null {
  const id = text(value?.selectedConnectionId), model = text(value?.selectedModel);
  if (value?.status !== 'saved' || !id || !model || origin && (id !== origin.id || model !== origin.model)) return null;
  const row = connections.find(row => row.id === id);
  const binding = apiBinding(origin?.binding ?? row?.binding, id);
  return { sourceId: id, source: origin?.label || text(row?.name) || id, model, ...(binding ? { target: { kind: 'api', binding } } : {}) };
}
export function accountChoice(value: Row, accounts: Row[], origin?: Origin): AIChoice | null {
  const receipt = object(value?.receipt), ref = object(receipt.nativeRef);
  const id = text(ref.accountId), model = text(ref.modelId);
  if (value?.status !== 'saved' || receipt.selected !== true || typeof receipt.mainInferenceReady !== 'boolean' ||
      ref.provider !== 'chatgpt_plan' || !id || !model || !Number.isSafeInteger(ref.accountVersion) || ref.accountVersion < 1 ||
      origin && (id !== origin.id || model !== origin.model || ref.accountVersion !== origin.version)) return null;
  const row = accounts.find(row => row.id === id);
  return { sourceId: id, source: origin?.label || text(row?.label) || id, model, accountOnly: !receipt.mainInferenceReady, target: { kind: 'account', version: ref.accountVersion } };
}
export function groupChoice(value: Row, args: Row): AIChoice | null {
  const groups = object(object(value?.snapshot).modelGroups), binding = object(object(groups.purposeBindings).MAIN);
  if (value?.status !== 'saved' || !text(args.groupId) || !Number.isSafeInteger(args.expectedRevision) || args.expectedRevision < 1 ||
      binding.kind !== 'group' || binding.groupId !== args.groupId || binding.groupRevision !== args.expectedRevision) return null;
  const group = rows(groups.groups).find(row => row.groupId === args.groupId && row.revision === args.expectedRevision);
  return { sourceId: args.groupId, source: '模型组', model: text(group?.name) || args.groupId, target: { kind: 'group', version: args.expectedRevision } };
}

/** undefined means the current configuration could not be identified, not a different source. */
export function matchesChoicePreference(choice: AIChoice, row: Row): boolean | undefined {
  const selected = choice.target;
  if (!selected || !['api', 'account', 'group'].includes(row.kind)) return undefined;
  if (row.kind !== selected.kind) return false;
  if (selected.kind === 'api') {
    const binding = typeof row.connectionId === 'string' ? apiBinding(row.binding, row.connectionId) : '';
    return binding && text(row.modelId) ? row.connectionId === choice.sourceId && row.modelId === choice.model && binding === selected.binding : undefined;
  }
  if (selected.kind === 'group') {
    return text(row.groupId) && Number.isSafeInteger(row.groupRevision) ? row.groupId === choice.sourceId && row.groupRevision === selected.version : undefined;
  }
  const ref = object(row.nativeRef);
  return ref.provider === 'chatgpt_plan' && text(ref.accountId) && text(ref.modelId) && Number.isSafeInteger(ref.accountVersion)
    ? ref.accountId === choice.sourceId && ref.accountVersion === selected.version && ref.modelId === choice.model : undefined;
}

export function matchesChoiceDefault(choice: AIChoice, value: unknown): boolean | undefined {
  const snapshot = object(value), selection = object(snapshot.nativeSelection), plan = object(selection.modelRoutePlan);
  if (snapshot.selectionUnavailable === true || !text(selection.model) || snapshot.activeRoute !== selection.modelRoute) return undefined;
  if (Number.isSafeInteger(plan.groupRevision) && plan.groupRevision > 0) {
    return matchesChoicePreference(choice, { kind: 'group', groupId: plan.requestedAlias, groupRevision: plan.groupRevision });
  }
  if (selection.modelRoute === 'account') {
    if (selection.nativeRef?.modelId !== selection.model) return undefined;
    return matchesChoicePreference(choice, { kind: 'account', nativeRef: selection.nativeRef });
  }
  if (selection.modelRoute !== 'api_key') return undefined;
  const binding = object(selection.capabilityBinding);
  return matchesChoicePreference(choice, { kind: 'api', connectionId: binding.connectionId, modelId: selection.model, binding });
}
