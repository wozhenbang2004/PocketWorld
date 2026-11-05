/** Synthetic native transport for the actual production bundle. No account, settings or task I/O. */
export function installModelAccountHost(view) {
  const token = 'model-account-fixture-document';
  window.__pocketDocumentToken = token;
  const commands = ['app.openNativeWorkspace', 'app.ready', 'app.refresh', 'composer.reading', 'connections.enter', 'connections.leave',
    'accounts.beginLogin', 'accounts.refresh', 'accounts.openAuthorization', 'accounts.cancelLogin',
    'models.snapshot', 'models.review', 'models.select', 'projects.create', 'projects.newFile', 'projects.refresh', 'projects.select', 'projects.open',
    'models.api.open', 'models.api.close', 'models.api.review', 'models.api.select', 'models.api.favorite',
    'models.accounts.open', 'models.accounts.close', 'models.accounts.models', 'models.accounts.review', 'models.accounts.select',
    'attachments.toggle', 'attachments.closePicker', 'settings.openSystem'];
  const calls = [], pending = [], errors = [];
  let revision = 0, modelRevision = 8, sequence = 0, apiWindow = '', section = null;
  let delay = new Set(['projects.create', 'projects.newFile']);
  const models = [
    { connectionId: 'api-a', connectionRevision: 3, connectionName: '工作连接', modelId: 'same-model', provider: 'openai_chat', selected: true, available: true, favorite: false, lastUsedAt: 0, toolCalls: { known: false }, imageInput: { known: true, value: false } },
    { connectionId: 'api-b', connectionRevision: 5, connectionName: '备用连接', modelId: 'same-model', provider: 'openai_chat', selected: false, available: true, favorite: true, lastUsedAt: 1730000000000, toolCalls: { known: true, value: true }, imageInput: { known: true, value: true } },
    { connectionId: 'api-c', connectionRevision: 2, connectionName: '已停用连接', modelId: 'old-model', provider: 'openai_chat', selected: false, available: false, favorite: true, lastUsedAt: 0, unavailableReason: 'connection_disabled', toolCalls: { known: false }, imageInput: { known: false } },
  ];
  const snapshot = { host: { connected: true, ready: true }, view: { section }, capabilities: { commands },
    composer: { ready: true, key: 'draft-1', epoch: 1, revision: 1, text: '保留这条草稿', attachments: [], sourceRefs: [], pending: [], mode: 'queue', conversationId: '' },
    phoneDraft: { text: '', revision: 0, epoch: 1, phone: false, mode: 'background' },
    state: { modelName: 'same-model', modelRoute: 'api_key', modelSelectionRevision: modelRevision, modelSelectionBusy: false,
      messages: [], tasks: [], projects: [], conversations: [], selectedChatAttachments: [], features: {}, ownerPending: [], ownerErrors: {}, ownerResponses: {},
      ownerSnapshots: { accounts: { accounts: [], login: {} } }, phoneReadiness: { groups: [] },
      requestedConnectionSection: view || null, requestedConnectionRevision: view ? 1 : 0,
    } };
  const publish = (patch = {}) => {
    Object.assign(snapshot.state, patch); snapshot.view.section = section;
    window.PocketWeb.receive({ v: 1, kind: 'state', revision: ++revision, snapshot: structuredClone(snapshot) });
  };
  const reply = (request, value, error) => window.PocketWeb.receive({ v: 1, kind: 'result', id: request.id, ok: !error,
    ...(error ? { error } : { value }) });
  const projection = () => ({ windowId: apiWindow, viewGeneration: 1, selectionRevision: modelRevision, format: 'pocketworld.model-picker.v1',
    models: structuredClone(models), preferencesAvailable: true, recentUseSaveFailed: false, selectedConnectionId: models.find(row => row.selected)?.connectionId,
    selectedModel: 'same-model', recentMeaning: 'successful_persisted_inference_only', selectionScope: 'future_tasks_only' });
  const execute = request => {
    const { action, args } = request;
    if (delay.has(action)) { pending.push({ action, request }); return; }
    try {
      let result = { accepted: true };
      if (action === 'app.ready' || action === 'app.refresh') { publish(); result = { ready: true }; }
      else if (action === 'connections.enter') { section = args.section; publish(); }
      else if (action === 'connections.leave') { if (section === args.section) section = null; publish(); }
      else if (action === 'accounts.beginLogin') {
        if (typeof args.label !== 'string' || !args.label.trim()) throw new Error('INVALID_ACCOUNT_LABEL');
        const receipt = { sessionId: 'session-a', accountId: 'account-a', status: 'pending' };
        publish({ ownerSnapshots: { accounts: { accounts: [{ id: 'account-a', label: args.label, version: 1, enabled: true, status: 'disconnected' }], login: receipt } } });
        result = { status: 'completed', action, command: 'beginLogin', receipt };
      } else if (action === 'accounts.refresh') result = { status: 'completed', action, command: 'snapshot', receipt: {} };
      else if (action === 'models.api.open') { apiWindow = 'api-window-' + ++sequence; result = projection(); }
      else if (action === 'models.api.close') { if (apiWindow === args.windowId) apiWindow = ''; result = { closed: true }; }
      else if (action === 'models.api.review') {
        const row = models.find(row => row.connectionId === args.connectionId && row.modelId === args.modelId && row.connectionRevision === args.expectedRevision);
        if (!row || args.windowId !== apiWindow) throw new Error('原连接已变化');
        result = { windowId: apiWindow, viewGeneration: 1, selectionRevision: modelRevision,
          ...(row.available ? { reviewHandle: 'review-' + row.connectionId } : {}), model: structuredClone(row), expiresInMs: 120000 };
      } else if (action === 'models.api.favorite') {
        const row = models.find(row => row.connectionId === args.connectionId && row.modelId === args.modelId && row.connectionRevision === args.expectedRevision);
        if (!row || args.windowId !== apiWindow) throw new Error('原连接已变化');
        row.favorite = args.favorite; modelRevision += 3; publish({ modelSelectionRevision: modelRevision }); result = { status: 'saved' };
      } else if (action === 'models.api.select') {
        const row = models.find(row => args.reviewHandle === 'review-' + row.connectionId);
        if (!row || !row.available || args.windowId !== apiWindow) throw new Error('原审核已变化');
        models.forEach(item => { item.selected = item === row; }); modelRevision += 3; publish({ modelSelectionRevision: modelRevision }); result = { status: 'saved' };
      } else if (action === 'models.accounts.open') result = { windowId: 'account-window-' + ++sequence, selectionRevision: modelRevision, accountSnapshot: { accounts: [] } };
      reply(request, result, null);
    } catch (failure) { reply(request, null, { code: 'native_failure', message: String(failure.message || failure) }); }
  };
  window.PocketNative = { postMessage(raw) {
    const request = JSON.parse(raw);
    if (request.hostDocumentToken !== token) { errors.push('fixture document mismatch'); reply(request, null, { code: 'not_accepted', message: 'fixture document mismatch' }); return; }
    calls.push({ action: request.action, args: structuredClone(request.args) }); queueMicrotask(() => execute(request));
  } };
  window.modelFixture = { calls, pending, errors, publish, getModels: () => structuredClone(models),
    setDelay(actions) { delay = new Set(actions); },
    settle(action, value, error) {
      const index = pending.findIndex(item => item.action === action); if (index < 0) throw new Error('No pending ' + action);
      const [item] = pending.splice(index, 1); reply(item.request, value, error ? { code: 'native_failure', message: error } : null);
    },
  };
}
