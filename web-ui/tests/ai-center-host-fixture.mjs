/**
 * SYNTHETIC native host for production-renderer checks only.
 * No account login, provider HTTP, key storage, listener, clipboard or inference is performed.
 * Every credential/receipt below is test data. Android admission needs its own native tests.
 */
export function installAiCenterHost({ dark = false, empty = false } = {}) {
  const documentToken = 'synthetic-ai-center-document';
  window.__pocketDocumentToken = documentToken;
  const clone = value => structuredClone(value);
  const calls = [], errors = [], apiWindows = new Set(), accountWindows = new Set();
  let stateRevision = 0, selectionRevision = 8, sequence = 0, section = null, proxyReview = null;
  const reasoning = { mode: 'OPENAI_EFFORT', level: 'medium', budgetTokens: 0 };
  const connections = empty ? [] : [
    { id: 'fixture-openai', revision: 3, name: '工作 OpenAI · 测试', provider: 'openai_responses', baseUrl: 'https://api.openai.com/v1', model: 'fixture-reasoner', enabled: true, hasApiKey: true, credentialStatus: 'saved_unverified', catalogEnabled: true,
      configuration: { sendScreenshots: false, supportsImageInput: true, maxOutputTokens: 4096 } },
    { id: 'fixture-anthropic', revision: 2, name: '写作 Anthropic · 测试', provider: 'anthropic', baseUrl: 'https://api.anthropic.com/v1', model: 'fixture-writer', enabled: true, hasApiKey: true, credentialStatus: 'saved_unverified', catalogEnabled: false,
      configuration: { sendScreenshots: false, supportsImageInput: false, maxOutputTokens: 4096 } },
  ];
  const accounts = empty ? [] : [{ id: 'fixture-chatgpt', version: 4, label: '个人 ChatGPT · 测试', provider: 'chatgpt_plan', enabled: true, planUsageEnabled: true, usageLimited: false, status: 'connected', cooldownUntil: 0 }];
  const groups = empty ? [] : [{ groupId: 'fixture-daily', revision: 2, name: '日常任务 · 测试',
    allowedAccounts: { mode: 'EXPLICIT', refs: ['fixture-openai', 'fixture-anthropic'] },
    candidates: connections.map((row, i) => ({ candidateId: 'fixture-candidate-' + i, target: { kind: 'exact', connectionId: row.id, modelId: row.model }, enabled: true, allowedPurposes: ['MAIN'], allowedAudiences: ['APP_OWNER'] })),
    attemptPolicy: { maxAttempts: 2, maxRouteSwitches: 1 } }];
  const groupDocument = () => ({ format: 'pocketworld.model-groups.v1', groups: clone(groups), purposeBindings: {} });
  const modelRows = () => connections.filter(row => row.model).map((row, i) => ({ connectionId: row.id, connectionRevision: row.revision,
    connectionName: row.name, modelId: row.model, provider: row.provider, selected: i === 0, available: row.enabled, favorite: i === 0,
    lastUsedAt: i === 0 ? 1794222000000 : 0, catalogFresh: true, imageInput: { known: true, value: i === 0 }, toolCalls: { known: true, value: true } }));
  const commands = [
    'app.ready', 'app.refresh', 'composer.reading', 'composer.edit', 'connections.enter', 'connections.leave',
    'models.snapshot', 'models.conversation.review', 'models.conversation.close',
    'models.api.open', 'models.api.close', 'models.api.review', 'models.api.select', 'models.api.favorite',
    'models.accounts.open', 'models.accounts.close', 'models.accounts.models', 'models.accounts.review', 'models.accounts.select',
    'models.groups.snapshot', 'models.groups.save', 'models.groups.select',
    'models.connections.add', 'models.catalog.snapshot', 'models.catalog.refresh', 'models.catalog.preview',
    'settings.reviewModelTransport',
    'proxy.snapshot', 'proxy.generateToken', 'proxy.copyToken', 'proxy.reviewStart', 'proxy.closeReview', 'proxy.confirmStart', 'proxy.stop',
  ];
  const snapshot = {
    host: { connected: true, ready: true, insetsHandledByNative: true, fixture: 'synthetic-ai-center' },
    capabilities: { commands }, view: { section: null },
    composer: { ready: true, key: 'fixture-draft', epoch: 1, revision: 1, text: '保留这条测试草稿', attachments: [], sourceRefs: [], previewRefs: [], pending: [], mode: 'queue', conversationId: '', saving: false },
    phoneDraft: { text: '', revision: 0, epoch: 1, phone: false, mode: 'background' },
    state: {
      appearance: { dark, mode: dark ? 'dark' : 'light' }, modelName: empty ? '' : 'fixture-reasoner', modelRoute: 'api_key', modelProvider: 'openai_responses',
      modelSelectionRevision: selectionRevision, modelSelectionBusy: false, modelSaving: false, modelTesting: false, modelMaxSteps: 20,
      conversationModel: { scopeKey: 'draft:fixture-draft', revision: 0, inherited: true, target: null, modelName: empty ? '' : 'fixture-reasoner' },
      messages: [], tasks: [], ownerWork: [], projects: [], files: [], conversations: [], conversationId: null, activeTaskId: null, activePhoneTaskIds: [],
      chatBusy: false, chatSubmitting: false, phoneSubmitting: false, chatActiveTurn: null, foregroundTurn: null, chatReceipts: {}, chatSendMode: 'queue',
      selectedChatAttachments: [], chatAttachmentPickerOpen: false, attachmentImporting: false,
      features: {}, ownerSnapshots: { accounts: { accounts: clone(accounts), login: {} } }, ownerResponses: {}, ownerPending: [], ownerErrors: {},
      moduleResults: {}, moduleBusy: [], moduleErrors: {}, taskActionErrors: {}, phoneReadiness: { groups: [] }, modelDrafts: {},
      requestedConnectionSection: null, requestedConnectionRevision: 0, backgroundSetupOpen: false, webWorkspaceTaskOpen: false, webWorkspaceResumeRevision: 0,
      error: null, notice: null, screenshots: false, supportsImageInput: !empty, newTaskScopeMinutes: 0, newTaskToolBudget: 20, newTaskModelBudget: 32,
    },
  };
  const nativeSelection = () => ({ provider: 'openai_responses', model: snapshot.state.modelName, modelRoute: 'api_key', catalogEnforced: true,
    capabilityBinding: { connectionId: 'fixture-openai' }, reasoning: clone(reasoning), maxOutputTokens: 4096, imageTokenReserve: 0 });
  const modelsSnapshot = () => ({ connections: { format: 'pocketworld.api-connections.v1', connections: clone(connections), selectedConnectionId: empty ? '' : 'fixture-openai' },
    modelGroups: groupDocument(), nativeSelection: nativeSelection(), activeRoute: 'api_key', models: modelRows() });
  const publish = () => {
    snapshot.view.section = section;
    snapshot.state.modelSelectionRevision = selectionRevision;
    snapshot.state.modelConnections = modelsSnapshot();
    window.PocketWeb.receive({ v: 1, kind: 'state', revision: ++stateRevision, snapshot: clone(snapshot) });
  };
  const reply = (request, value, error) => window.PocketWeb.receive({ v: 1, kind: 'result', id: request.id, ok: !error, ...(error ? { error } : { value: clone(value) }) });
  const apiProjection = windowId => ({ format: 'pocketworld.model-picker.v1', windowId, viewGeneration: 1, selectionRevision,
    preferencesAvailable: true, recentUseSaveFailed: false, selectedConnectionId: empty ? '' : 'fixture-openai', models: modelRows() });
  const accountProjection = windowId => ({ windowId, selectionRevision, accountSnapshot: { accounts: clone(accounts), closed: false } });
  const accountCatalog = () => ({ accountId: 'fixture-chatgpt', accountVersion: 4, models: [{ id: 'fixture-plan-model', displayName: '套餐模型 · 测试',
    nativeRef: { provider: 'chatgpt_plan', accountId: 'fixture-chatgpt', accountVersion: 4, modelId: 'fixture-plan-model' } }] });
  const requireConnection = args => {
    const row = connections.find(item => item.id === args.connectionId && item.revision === args.expectedRevision);
    if (!row) throw new Error('Synthetic source identity changed');
    return row;
  };
  const catalog = row => ({ format: 'pocketworld.connection-catalog.v1', connection: clone(row), catalogAvailable: true,
    catalogFetchedAtMillis: row.model ? 1794222000000 : 0, models: row.model ? [{ modelId: row.model, catalogFresh: true }] : [] });
  const proxy = { running: false, hasToken: false, tokenRevision: 'fixture-token-revision-0', sessionId: '', rootEndpoint: '',
    model: empty ? '' : 'fixture-reasoner', protocol: 'openai_responses', externalRelayAllowed: !empty, selectionRevision,
    blockedReason: empty ? 'MODEL_NOT_CONFIGURED' : '', routeCurrent: true, scope: 'loopback_only', phoneExecution: false,
    recentInferenceReferences: [], fixture: 'synthetic-no-listener-no-clipboard' };
  const proxySnapshot = () => ({ ...clone(proxy), clientConfigurations: [
    { protocol: 'openai_chat', label: 'OpenAI Chat Completions', native: false, baseUrl: proxy.running ? proxy.rootEndpoint + '/v1' : '', model: proxy.model,
      authHeader: 'Authorization', authScheme: 'Bearer', paths: ['/v1/chat/completions'], requestUrls: proxy.running ? [proxy.rootEndpoint + '/v1/chat/completions'] : [] },
    { protocol: 'openai_responses', label: 'OpenAI Responses', native: true, baseUrl: proxy.running ? proxy.rootEndpoint + '/v1' : '', model: proxy.model,
      authHeader: 'Authorization', authScheme: 'Bearer', paths: ['/v1/responses'], requestUrls: proxy.running ? [proxy.rootEndpoint + '/v1/responses'] : [] },
  ] });
  const requireKeys = (args, keys) => { if (Object.keys(args).sort().join(',') !== [...keys].sort().join(',')) throw new Error('Unexpected synthetic command fields'); };
  const requireTokenRevision = args => { if (typeof args.tokenRevision !== 'string' || args.tokenRevision !== proxy.tokenRevision) throw new Error('Synthetic token revision mismatch'); };
  const execute = request => {
    try {
      if (request.hostDocumentToken !== documentToken) throw new Error('Synthetic document mismatch');
      const { action, args = {} } = request;
      let value = {};
      switch (action) {
        case 'app.ready': case 'app.refresh': publish(); value = { ready: true }; break;
        case 'composer.reading': break;
        case 'composer.edit': snapshot.composer.text = args.text; snapshot.composer.revision++; publish(); break;
        case 'connections.enter': section = args.section; publish(); break;
        case 'connections.leave': if (section === args.section) { section = null; publish(); } break;
        case 'models.snapshot': value = modelsSnapshot(); break;
        case 'models.conversation.close': value = { closed: true }; break;
        case 'models.conversation.review': value = { ...clone(snapshot.state.conversationModel), selectionRevision, nativeSelection: nativeSelection(),
          reviewHandle: 'fixture-conversation-review', expiresInMs: 120000, facts: { REASONING: { known: true, value: true }, REASONING_LEVELS: { known: true, value: ['low', 'medium', 'high'] } } }; break;
        case 'models.api.open': { const id = 'fixture-api-window-' + ++sequence; apiWindows.add(id); value = apiProjection(id); break; }
        case 'models.api.close': apiWindows.delete(args.windowId); value = { closed: true }; break;
        case 'models.api.review': {
          if (!apiWindows.has(args.windowId)) throw new Error('Synthetic API window expired');
          requireConnection(args);
          const row = modelRows().find(item => item.connectionId === args.connectionId && item.modelId === args.modelId);
          if (!row) throw new Error('Synthetic model missing');
          value = { windowId: args.windowId, viewGeneration: 1, selectionRevision, model: row, reviewHandle: 'fixture-review-' + row.connectionId, expiresInMs: 120000 }; break;
        }
        case 'models.api.select': case 'models.api.favorite': throw new Error('This renderer journey must not change defaults or favorites');
        case 'models.accounts.open': { const id = 'fixture-account-window-' + ++sequence; accountWindows.add(id); value = accountProjection(id); break; }
        case 'models.accounts.close': accountWindows.delete(args.windowId); value = { closed: true }; break;
        case 'models.accounts.models':
          if (!accountWindows.has(args.windowId) || args.accountId !== 'fixture-chatgpt' || args.expectedVersion !== 4) throw new Error('Synthetic account window/version mismatch');
          value = { ...accountProjection(args.windowId), catalog: accountCatalog() }; break;
        case 'models.accounts.review': case 'models.accounts.select': throw new Error('This renderer journey does not authorize a synthetic account selection');
        case 'models.groups.snapshot': value = { modelGroups: groupDocument() }; break;
        case 'models.groups.save': case 'models.groups.select': throw new Error('This renderer journey only inspects scheduling');
        case 'settings.reviewModelTransport': value = { required: false, consentTarget: '' }; break;
        case 'models.catalog.snapshot': case 'models.catalog.refresh': value = catalog(requireConnection(args)); break;
        case 'models.catalog.preview': value = { format: 'pocketworld.draft-catalog.v1', status: 'succeeded', saved: false, selectionChanged: false,
          editorId: args.editorId, draftRevision: args.draftRevision, models: [{ modelId: 'fixture-preview-model', protocolCompatibility: 'compatible' }] }; break;
        case 'models.connections.add': {
          if (section !== 'models' || args.provider !== 'openai_responses' || args.url !== 'https://api.openai.com/v1' || !args.name || !args.apiKey) throw new Error('Unexpected synthetic provider save');
          const row = { id: 'fixture-added-' + ++sequence, revision: 1, name: args.name, provider: args.provider, baseUrl: args.url, model: args.model,
            enabled: true, hasApiKey: true, credentialStatus: 'saved_unverified', catalogEnabled: false,
            configuration: { sendScreenshots: args.screenshots, supportsImageInput: args.supportsImageInput, maxOutputTokens: args.maxOutputTokens } };
          connections.push(row); selectionRevision++; publish(); value = { status: 'saved', savedConnection: clone(row), snapshot: modelsSnapshot() }; break;
        }
        case 'proxy.snapshot': value = proxySnapshot(); break;
        case 'proxy.generateToken':
          requireKeys(args, ['tokenRevision']); requireTokenRevision(args);
          if (proxy.running) throw new Error('Synthetic running token cannot rotate');
          proxy.hasToken = true; proxy.tokenRevision = 'fixture-token-revision-1'; value = proxySnapshot(); break;
        case 'proxy.copyToken':
          requireKeys(args, proxy.running ? ['tokenRevision', 'sessionId'] : ['tokenRevision']); requireTokenRevision(args);
          if (!proxy.hasToken || proxy.running && args.sessionId !== proxy.sessionId) throw new Error('Synthetic original token/session mismatch');
          value = { ...proxySnapshot(), tokenCopied: true }; break;
        case 'proxy.reviewStart':
          requireKeys(args, ['port']);
          if (!proxy.hasToken || proxy.running || !proxy.externalRelayAllowed || !Number.isSafeInteger(args.port) || args.port !== 0 && (args.port < 1024 || args.port > 65535)) throw new Error('Invalid synthetic start review');
          proxyReview = { reviewHandle: 'fixture-proxy-review-' + ++sequence, scope: 'loopback_only', phoneExecution: false, port: args.port,
            model: proxy.model, protocol: proxy.protocol, selectionRevision, expiresInMs: 120000 };
          value = clone(proxyReview); break;
        case 'proxy.closeReview': if (proxyReview?.reviewHandle === args.reviewHandle) proxyReview = null; value = { closed: true }; break;
        case 'proxy.confirmStart':
          requireKeys(args, ['reviewHandle']);
          if (!proxyReview || args.reviewHandle !== proxyReview.reviewHandle) throw new Error('Synthetic original service review mismatch');
          proxy.running = true; proxy.sessionId = 'fixture-proxy-session-1'; proxy.rootEndpoint = 'http://127.0.0.1:' + (proxyReview.port || 18743);
          proxyReview = null; value = proxySnapshot(); break;
        case 'proxy.stop':
          requireKeys(args, ['sessionId']);
          if (!proxy.running || args.sessionId !== proxy.sessionId) throw new Error('Synthetic original service session mismatch');
          proxy.running = false; proxy.sessionId = ''; proxy.rootEndpoint = ''; value = proxySnapshot(); break;
        default: throw new Error('Unhandled synthetic action: ' + action);
      }
      reply(request, value, null);
    } catch (failure) {
      const message = String(failure.message || failure); errors.push(message);
      reply(request, null, { code: 'not_accepted', message });
    }
  };
  window.PocketNative = { postMessage(raw) { const request = JSON.parse(raw); calls.push(clone(request)); queueMicrotask(() => execute(request)); } };
  window.aiCenterFixture = {
    synthetic: true, calls, errors,
    summary() { return { synthetic: true, nativeInsetsHandled: true, section, errors: [...errors],
      connections: clone(connections), proxy: proxySnapshot(),
      calls: calls.map(request => ({ action: request.action, args: { ...clone(request.args), ...(request.args?.apiKey ? { apiKey: '[synthetic credential omitted]' } : {}) } })) }; },
  };
}
