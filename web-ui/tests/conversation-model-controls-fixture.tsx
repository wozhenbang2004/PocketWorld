/** Isolated native responses around the production component. Never connects to a provider. */
import { createRoot } from 'react-dom/client';
import { useSyncExternalStore } from 'react';
import { ConversationModelControls } from '../src/ConversationModelControls';
import '../src/fonts.css';
import '../src/app.css';
import '../src/library.css';
import '../src/product.css';
import '../src/presentation-theme.css';

const params = new URLSearchParams(location.search), account = params.get('route') === 'account';
const calls: Array<{ action: string; args: any }> = [], listeners = new Set<() => void>();
const pending: Array<{ action: string; args: any; resolve: (value: any) => void; reject: (reason: Error) => void }> = [];
let delay = new Set(params.has('delaySnapshot') ? ['models.snapshot'] : []), back: (() => boolean) | null = null, modelOpened = 0;
const facts = { REASONING: { known: true, value: true }, REASONING_LEVELS: { known: true, value: ['medium', 'high'] } };
const binding = { connectionId: 'fixture-api', protocol: 'OPENAI_CHAT', credentialRevision: 3 };
const nativeRef = { provider: 'chatgpt_plan', accountId: 'fixture-account', accountVersion: 4, modelId: 'Fixture Model' };
let selection: any = { modelRoute: account ? 'account' : 'api_key', provider: account ? 'chatgpt_plan' : 'openai_chat', model: 'Fixture Model',
  reasoning: { mode: 'OPENAI_EFFORT', level: 'medium', budgetTokens: 0 }, maxOutputTokens: 7168, imageTokenReserve: 2800,
  ...(account ? { nativeRef } : { catalogEnforced: true, capabilityBinding: binding }) };
let state: any = { modelName: 'Fixture Model', modelRoute: selection.modelRoute, modelProvider: selection.provider, modelSelectionRevision: 7 };
let windowId = '', sequence = 0, reviewHandle = '';
const publish = (patch: any = {}) => { state = { ...state, ...patch }; listeners.forEach(listener => listener()); };
const snapshot = () => structuredClone({ activeRoute: selection.modelRoute, nativeSelection: selection, catalogEnabled: true, binding,
  api: selection, selected: { modelId: selection.model, binding, facts } });
const projection = () => ({ windowId, selectionRevision: state.modelSelectionRevision, viewGeneration: 1,
  accountSnapshot: { accounts: [{ id: nativeRef.accountId, version: nativeRef.accountVersion, provider: 'chatgpt_plan', label: '测试账号', enabled: true, planUsageEnabled: true, status: 'connected', cooldownUntil: 0, usageLimited: false }],
    selectedModelRef: nativeRef, selectedModelOptions: structuredClone(selection) } });
const check = (condition: boolean) => { if (!condition) throw new Error('Fixture rejected a changed original identity'); };
function execute(action: string, args: any) {
  if (action === 'models.snapshot') return snapshot();
  if (action === 'models.review') { check(args.modelId === selection.model); reviewHandle = `api-review-${++sequence}`; return { reviewHandle, modelId: selection.model, reasoning: structuredClone(selection.reasoning), outputTokens: selection.maxOutputTokens, imageTokenReserve: selection.imageTokenReserve, expiresInMs: 120000 }; }
  if (action === 'models.select') {
    check(args.reviewHandle === reviewHandle && Object.keys(args).sort().join(',') === 'reasoning,reviewHandle');
    reviewHandle = ''; selection.reasoning = structuredClone(args.reasoning); publish({ modelSelectionRevision: state.modelSelectionRevision + 1 });
    return { accepted: true, modelId: selection.model, snapshot: snapshot() };
  }
  if (action === 'models.accounts.open') { windowId = `account-window-${++sequence}`; return projection(); }
  if (action === 'models.accounts.close') { if (windowId === args.windowId) windowId = ''; return { closed: true }; }
  check(args.windowId === windowId && !!windowId);
  if (action === 'models.accounts.models') {
    check(args.accountId === nativeRef.accountId && args.expectedVersion === nativeRef.accountVersion);
    return { ...projection(), catalog: { accountId: nativeRef.accountId, accountVersion: nativeRef.accountVersion, models: [{ id: selection.model, displayName: selection.model, nativeRef, catalogFacts: { modelId: selection.model, facts } }] } };
  }
  if (action === 'models.accounts.review') {
    check(args.accountId === nativeRef.accountId && args.modelId === selection.model); reviewHandle = `account-review-${++sequence}`;
    return { ...projection(), reviewHandle, accountId: nativeRef.accountId, accountVersion: nativeRef.accountVersion, modelId: selection.model, catalogFacts: { modelId: selection.model, facts }, expiresInMs: 120000,
      options: { reasoningEffort: selection.reasoning.level, outputReserve: selection.maxOutputTokens, imageTokenReserve: selection.imageTokenReserve } };
  }
  if (action === 'models.accounts.select') {
    check(args.reviewHandle === reviewHandle && Object.keys(args).sort().join(',') === 'options,reviewHandle,windowId');
    check(args.options.outputReserve === selection.maxOutputTokens && args.options.imageTokenReserve === selection.imageTokenReserve);
    reviewHandle = ''; selection.reasoning = { mode: args.options.reasoningEffort ? 'OPENAI_EFFORT' : 'DEFAULT', level: args.options.reasoningEffort, budgetTokens: 0 };
    publish({ modelSelectionRevision: state.modelSelectionRevision + 1 }); return { status: 'saved', receipt: { modelId: selection.model } };
  }
  throw new Error('Unexpected fixture command ' + action);
}
async function act(action: string, args: any = {}) {
  calls.push({ action, args: structuredClone(args) });
  if (delay.has(action)) return new Promise((resolve, reject) => pending.push({ action, args: structuredClone(args), resolve, reject }));
  return execute(action, args);
}
const fixture = { calls, pending, snapshot, getState: () => state, back: () => back?.() || false, modelOpened: () => modelOpened,
  setDelay: (actions: string[]) => { delay = new Set(actions); },
  settle: (action: string, outcome = 'success') => {
    const index = pending.findIndex(item => item.action === action); if (index < 0) throw new Error('Missing pending request');
    const item = pending.splice(index, 1)[0];
    if (outcome === 'unknown') item.resolve({ status: 'unknown' });
    else if (outcome === 'error') item.reject(new Error('原操作结果待核对'));
    else { try { item.resolve(execute(item.action, item.args)); } catch (failure) { item.reject(failure as Error); } }
  },
  changeRevision: () => publish({ modelSelectionRevision: state.modelSelectionRevision + 1 }),
  useGroup: () => { selection = { ...selection, modelRoutePlan: { format: 'pocketworld.model-route-plan.v1', groupRevision: 2 } }; publish({ modelSelectionRevision: state.modelSelectionRevision + 1 }); },
};
Object.assign(window, { reasoningFixture: fixture });
function Fixture() {
  const current = useSyncExternalStore(listener => { listeners.add(listener); return () => listeners.delete(listener); }, () => state);
  return <main className="pw-app" style={{ padding: 24 }}><p style={{ marginBottom: 20 }}>隔离组件测试</p><div className="pw-library" style={{ width: 180 }}>
    <ConversationModelControls state={current} can={() => true} act={act} onOpenModel={() => { modelOpened++; }} registerBackHandler={handler => { back = handler; }} />
  </div></main>;
}
createRoot(document.getElementById('app')!).render(<Fixture />);
