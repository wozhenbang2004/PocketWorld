/** Test-only production component mount. No Android, OAuth, settings or task I/O. */
import { createRoot } from 'react-dom/client';
import { useSyncExternalStore } from 'react';
import App from '../src/App';
import Secondary from '../src/Secondary';
import { bridge } from '../src/bridge';
import '../src/fonts.css';
import '../src/app.css';
import '../src/library.css';
import '../src/secondary.css';
import '../src/product.css';
import '../src/workspace-refactor.css';
import '../src/presentation-theme.css';

const commands = ['connections.enter', 'connections.leave', 'accounts.beginLogin', 'accounts.refresh', 'accounts.openAuthorization', 'accounts.cancelLogin', 'models.snapshot', 'models.review', 'models.select', 'projects.create', 'projects.newFile', 'projects.refresh', 'projects.select', 'projects.open', 'models.api.open', 'models.api.close', 'models.api.review', 'models.api.select', 'models.api.favorite', 'models.accounts.open', 'models.accounts.close', 'models.accounts.models', 'models.accounts.review', 'models.accounts.select', 'attachments.toggle', 'attachments.closePicker', 'settings.openSystem'];
const listeners = new Set<() => void>();
const calls: Array<{ action: string; args: any }> = [];
const pending: Array<{ action: string; resolve: (value: any) => void; reject: (error: Error) => void }> = [];
let modelRevision = 8;
let windowSequence = 0;
let apiWindow = '';
const models = [
  { connectionId: 'api-a', connectionRevision: 3, connectionName: '工作连接', modelId: 'same-model', provider: 'openai_chat', selected: true, available: true, favorite: false, lastUsedAt: 0, toolCalls: { known: false }, imageInput: { known: true, value: false } },
  { connectionId: 'api-b', connectionRevision: 5, connectionName: '备用连接', modelId: 'same-model', provider: 'openai_chat', selected: false, available: true, favorite: true, lastUsedAt: 1730000000000, toolCalls: { known: true, value: true }, imageInput: { known: true, value: true } },
  { connectionId: 'api-c', connectionRevision: 2, connectionName: '已停用连接', modelId: 'old-model', provider: 'openai_chat', selected: false, available: false, favorite: true, lastUsedAt: 0, unavailableReason: 'connection_disabled', toolCalls: { known: false }, imageInput: { known: false } },
];
let snapshot: any = { revision: 1, capabilities: { commands }, composer: { key: 'draft-1', revision: 1, text: '保留这条草稿', attachments: [], sourceRefs: [] }, host: { connected: true, ready: true }, state: {
  modelName: 'same-model', modelRoute: 'api_key', modelSelectionRevision: modelRevision, modelSelectionBusy: false,
  messages: [], tasks: [], projects: [], conversations: [], selectedChatAttachments: [], features: {}, ownerPending: [], ownerErrors: {}, ownerResponses: {}, ownerSnapshots: { accounts: { accounts: [], login: {} } },
} };
const publish = (patch: any = {}) => { snapshot = { ...snapshot, revision: snapshot.revision + 1, state: { ...snapshot.state, ...patch } }; listeners.forEach(listener => listener()); };
const projection = () => ({ windowId: apiWindow, viewGeneration: 1, selectionRevision: modelRevision, format: 'pocketworld.model-picker.v1', models: structuredClone(models), preferencesAvailable: true, recentUseSaveFailed: false, selectedConnectionId: models.find(row => row.selected)?.connectionId, selectedModel: 'same-model', recentMeaning: 'successful_persisted_inference_only', selectionScope: 'future_tasks_only' });
let delay = new Set(['projects.create', 'projects.newFile']);
const command = async (action: string, args: any = {}) => {
  calls.push({ action, args: structuredClone(args) });
  if (delay.has(action)) return new Promise((resolve, reject) => pending.push({ action, resolve, reject }));
  if (action === 'accounts.beginLogin') {
    if (typeof args.label !== 'string' || !args.label.trim()) throw new Error('INVALID_ACCOUNT_LABEL');
    const receipt = { sessionId: 'session-a', accountId: 'account-a', status: 'pending' };
    publish({ ownerSnapshots: { accounts: { accounts: [{ id: 'account-a', label: args.label, version: 1, enabled: true, status: 'disconnected' }], login: receipt } } });
    return { status: 'completed', action, command: 'beginLogin', receipt };
  }
  if (action === 'accounts.refresh') return { status: 'completed', action, command: 'snapshot', receipt: {} };
  if (action === 'models.snapshot') return { binding: { connectionId: 'api-a' }, models: [{ modelId: 'same-model', catalogFresh: true }] };
  if (action === 'models.api.open') { apiWindow = 'api-window-' + ++windowSequence; return projection(); }
  if (action === 'models.api.close') return { closed: true };
  if (action === 'models.api.review') {
    const row = models.find(row => row.connectionId === args.connectionId && row.modelId === args.modelId && row.connectionRevision === args.expectedRevision);
    if (!row || args.windowId !== apiWindow) throw new Error('原连接已变化');
    return { windowId: apiWindow, viewGeneration: 1, selectionRevision: modelRevision, ...(row.available ? { reviewHandle: 'review-' + row.connectionId } : {}), model: structuredClone(row), expiresInMs: 120000 };
  }
  if (action === 'models.api.favorite') {
    const row = models.find(row => row.connectionId === args.connectionId && row.modelId === args.modelId);
    if (!row || args.windowId !== apiWindow) throw new Error('原连接已变化');
    row.favorite = args.favorite; modelRevision += 3; publish({ modelSelectionRevision: modelRevision });
    return { status: 'saved' };
  }
  if (action === 'models.api.select') {
    const row = models.find(row => args.reviewHandle === 'review-' + row.connectionId);
    if (!row || args.windowId !== apiWindow) throw new Error('原审核已变化');
    models.forEach(item => { item.selected = item === row; }); modelRevision += 3; publish({ modelSelectionRevision: modelRevision });
    return { status: 'saved' };
  }
  if (action === 'models.accounts.open') return { windowId: 'account-window-' + ++windowSequence, selectionRevision: modelRevision, accountSnapshot: { accounts: [] } };
  return { accepted: true };
};
Object.assign(bridge, { command, getState: () => snapshot, subscribe: (listener: () => void) => { listeners.add(listener); return () => listeners.delete(listener); } });
Object.assign(window, { modelFixture: { calls, pending, publish, getModels: () => structuredClone(models), setDelay: (actions: string[]) => { delay = new Set(actions); }, settle: (action: string, value: any, error?: string) => { const index = pending.findIndex(item => item.action === action); if (index < 0) throw new Error('No pending ' + action); const [item] = pending.splice(index, 1); if (error) item.reject(Object.assign(new Error(error), { code: 'native_failure' })); else item.resolve(value); } } });
function Fixture() {
  const current = useSyncExternalStore(bridge.subscribe, bridge.getState);
  const view = new URLSearchParams(location.search).get('view');
  return view === 'accounts' || view === 'background' ? <Secondary route={view} snapshot={current} onBack={() => {}} /> : <App />;
}
createRoot(document.getElementById('root')!).render(<Fixture />);
