/**
 * Conversation-only extension of installAiCenterHost, installed immediately after it.
 * All data and receipts are synthetic. This wraps the public native bridge; it does
 * not render UI, replace production components, or access React state.
 */
export function installAiConversationHost({ representative = false, phoneComposer = null } = {}) {
  if (!window.aiCenterFixture || !window.PocketNative) throw new Error('Install the AI center host first');
  const clone = value => structuredClone(value);
  const documentToken = window.__pocketDocumentToken;
  const originalPost = window.PocketNative.postMessage;
  const calls = [], receipts = [], errors = [], injectedOutcomes = [];
  const requests = new Map(), windows = new Map(), apiReviews = new Map(), reasoningReviews = new Map();
  let bridge, lastSnapshot, stateRevision = 0, selectionRevision = 8, sequence = 0, pendingWrite = null, holdNext = false;
  const conversationId = phoneComposer?.freshConversation ? '' : 'fixture-conversation-1';
  const phoneDraft = { text: '', revision: 0, epoch: 1, phone: false, mode: 'background' };
  let phoneSubmitting = false;
  const facts = { REASONING: { known: true, value: true }, REASONING_LEVELS: { known: true, value: ['low', 'medium', 'high'] } };
  let preference = {
    scopeKey: conversationId ? 'conversation:' + conversationId : 'draft:fixture-draft', revision: 4, inherited: false, modelName: 'fixture-reasoner-B',
    target: { kind: 'api', connectionId: 'fixture-openai', connectionRevision: 3,
      binding: { connectionId: 'fixture-openai', connectionRevision: 3, catalogRevision: 11 }, modelId: 'fixture-reasoner-B',
      outputTokens: 2048, imageTokenReserve: 256, reasoning: { mode: 'OPENAI_EFFORT', level: 'low', budgetTokens: 0 } },
  };
  const conversations = [
    { id: conversationId, title: '继续整理上次的设计反馈', pinOrder: 1, updatedAt: 1794204000000 },
    { id: 'fixture-conversation-2', title: '周末短途旅行：交通、住宿和可步行路线', pinOrder: 0, updatedAt: 1794203000000 },
    { id: 'fixture-conversation-3', title: '读完接口文档后的三个问题', pinOrder: 0, updatedAt: 1794202000000 },
    { id: 'fixture-conversation-4', title: '整理本周待办', pinOrder: 0, updatedAt: 1794201000000 },
    { id: 'fixture-conversation-5', title: '继续昨天的阅读', pinOrder: 0, updatedAt: 1794200000000 },
  ];
  const commands = ['conversation.select', 'conversation.new', 'models.conversation.snapshot',
    'models.conversation.api.select', 'models.conversation.reasoning', 'models.conversation.reset'];
  if (phoneComposer) commands.push('composer.intent', 'phone.draft.edit', 'phone.prepare');
  const task = (id, goal, status, summary) => ({ id, goal, status, summary, updatedAt: 1794205000000,
    raw: { id, goal, status, summary, kind: 'assistant', actorId: 'owner', spaceId: 'private', conversationId,
      executionRole: 'foreground', runId: id + '-run', coordinatorEpoch: 2, operations: [], events: [] } });
  const taskRows = [
    task('fixture-completed', '整理本周的设计反馈', 'completed', '反馈已按问题、依据和下一步整理。'),
    task('fixture-failed', '读取缺失的参考资料', 'failed', '这个来源无法读取，已有材料仍保留。'),
    task('fixture-unknown', '核对第一个更新回执', 'unknown', '更新是否发生尚未确认，不能自动重放。'),
    task('fixture-attention', '核对第二个更新回执', 'unknown', '请在原任务核对这次动作的实际结果。'),
    task('fixture-progress', '比较已经读取的两份资料', 'running', '正在比较已经取得的证据。'),
  ];
  taskRows.at(-1).raw.events = [{ id: 'fixture-progress-event', runId: 'fixture-progress-run', type: 'observation',
    status: 'running', at: 1794205001000, message: '正在比对两份已读取的资料' }];
  const messages = [
    { id: 'fixture-request', role: 'user', content: '先把本周的设计反馈整理成可以行动的结论。没有读到的资料和结果不确定的动作，保留原来的状态。' },
    { id: 'fixture-answer-completed', role: 'assistant', taskId: 'fixture-completed', content:
      '已经把反馈按用户任务整理好了。\n\n### 先解决入口与返回\n模型和推理设置留在对话现场；管理服务时继续保留这段草稿。返回后，用户应看到刚才的筛选条件和正在处理的对象。\n\n### 让结果成为阅读重点\n正常完成的任务只需要一个轻量的完成提示与详情入口。长回复按段落和小标题展开，读者可以先理解结论，再检查依据，不必反复越过相同的任务卡。\n\n### 保留不确定性\n失败和未确认的动作继续显示原因与原任务入口。一次页面返回、一次点击或一个加载动画结束，都不能当作执行成功的证据。\n\n如果来源缺失，先保留缺口，再决定下一步。' },
    { id: 'fixture-answer-failed', role: 'assistant', taskId: 'fixture-failed', content: '其中一份参考资料暂时无法读取。已经读到的两份资料仍可继续比较，失败原因保留在原任务里。' },
    { id: 'fixture-answer-unknown', role: 'assistant', taskId: 'fixture-unknown', content: '第一个更新动作的结果尚未确认。我保留了原回执，等待核对，没有重新执行。' },
    { id: 'fixture-answer-attention', role: 'assistant', taskId: 'fixture-attention', content: '第二个更新动作也需要核对。先继续整理已有材料，未决动作仍保留明确入口。' },
  ];
  const requireThat = (condition, message) => { if (!condition) throw new Error(message); };
  const requireKeys = (args, keys) => requireThat(Object.keys(args).sort().join(',') === [...keys].sort().join(','), 'Unexpected conversation command fields');
  const withModels = value => {
    const data = clone(value);
    if (Array.isArray(data.models)) {
      const global = data.models.find(row => row.modelId === 'fixture-reasoner');
      if (global && !data.models.some(row => row.modelId === 'fixture-reasoner-B')) {
        data.models.push({ ...clone(global), modelId: 'fixture-reasoner-B', selected: false, favorite: false, lastUsedAt: 0 });
      }
      // Keep A marked globally selected. The renderer must independently mark conversation B.
    }
    if (Array.isArray(data.connections?.connections)) {
      const base = data.connections.connections.find(row => row.id === 'fixture-openai');
      for (let index = 1; index <= 3; index++) {
        const id = 'fixture-archive-' + index;
        if (base && !data.connections.connections.some(row => row.id === id)) data.connections.connections.push({
          ...clone(base), id, revision: 1, name: '归档 API · 测试 ' + index,
          baseUrl: 'https://archive.example.invalid/v1', model: '', catalogEnabled: false,
        });
      }
    }
    if (Object.hasOwn(data, 'selectionRevision')) data.selectionRevision = selectionRevision;
    return data;
  };
  const nativeSelection = () => {
    const target = preference.target;
    if (!target) return clone(lastSnapshot.state.modelConnections.nativeSelection);
    return { provider: 'openai_responses', modelRoute: 'api_key', model: target.modelId, catalogEnforced: true,
      capabilityBinding: clone(target.binding), reasoning: clone(target.reasoning),
      maxOutputTokens: target.outputTokens, imageTokenReserve: target.imageTokenReserve };
  };
  const patchState = snapshot => {
    const next = clone(snapshot);
    next.capabilities.commands = [...new Set([...next.capabilities.commands, ...commands])];
    next.state.conversationModel = clone(preference);
    next.state.modelSelectionRevision = selectionRevision;
    next.state.modelSelectionBusy = !!pendingWrite;
    next.state.conversationId = conversationId;
    next.state.conversations = clone(conversations);
    next.composer.conversationId = conversationId;
    next.state.modelConnections = withModels(next.state.modelConnections);
    if (phoneComposer) {
      next.state.modelName = phoneComposer.defaultModel;
      next.state.phoneSubmitting = phoneSubmitting;
      next.phoneDraft = clone(phoneDraft);
    }
    if (representative) {
      // Display fixtures only; task execution, approval and retries remain outside this suite.
      next.capabilities.commands.push('tasks.open', 'tasks.close', 'tasks.review');
      next.state.messages = clone(messages); next.state.tasks = clone(taskRows);
      const turn = { conversationId, taskId: 'fixture-progress', runId: 'fixture-progress-run', epoch: 2,
        executionRole: 'foreground', resourceState: 'running' };
      next.state.chatActiveTurn = clone(turn); next.state.foregroundTurn = clone(turn); next.state.chatBusy = true;
    }
    return next;
  };
  // Production assigns PocketWeb once during bridge initialization. Observe native
  // envelopes there so the original host's snapshots and our receipts share one clock.
  Object.defineProperty(window, 'PocketWeb', {
    configurable: true,
    get: () => bridge,
    set(value) {
      const receive = value.receive;
      value.receive = input => {
        const message = typeof input === 'string' ? JSON.parse(input) : clone(input);
        if (message.kind === 'state') {
          lastSnapshot = patchState(message.snapshot);
          message.snapshot = clone(lastSnapshot); message.revision = ++stateRevision;
        } else if (message.kind === 'result') {
          const request = requests.get(message.id);
          if (message.ok && request) {
            if (request.action === 'models.snapshot' || request.action === 'models.api.open') message.value = withModels(message.value);
            if (request.action.startsWith('models.accounts.') && Object.hasOwn(message.value || {}, 'selectionRevision')) message.value.selectionRevision = selectionRevision;
            if (request.action === 'models.api.open') windows.set(message.value.windowId, clone(message.value));
            if (request.action === 'models.api.close') windows.delete(request.args.windowId);
          }
          receipts.push({ action: request?.action, id: message.id, ok: message.ok, value: clone(message.value), error: clone(message.error) });
        }
        receive(message);
      };
      bridge = value;
    },
  });
  const publish = () => bridge.receive({ v: 1, kind: 'state', revision: 0, snapshot: clone(lastSnapshot) });
  const reply = (request, value, error) => bridge.receive({ v: 1, kind: 'result', id: request.id, ok: !error,
    ...(error ? { error } : { value: clone(value) }) });
  const finishWrite = outcome => {
    requireThat(pendingWrite, 'No synthetic write is pending');
    requireThat(outcome === 'saved' || outcome === 'unknown', 'Unsupported synthetic receipt');
    const write = pendingWrite;
    if (outcome === 'saved') preference = { ...clone(write.preference), revision: write.previousRevision + 1 };
    pendingWrite = null; selectionRevision++;
    publish();
    if (outcome === 'saved') reply(write.request, { status: 'saved', scope: 'conversation', preference: clone(preference) });
    else {
      injectedOutcomes.push({ action: write.request.action, outcome, requestId: write.request.id });
      reply(write.request, null, { code: 'outcome_unknown', message: '保存结果待核对：测试宿主未确认本次写入。' });
    }
  };
  const beginWrite = (request, next) => {
    requireThat(!pendingWrite, 'A second synthetic write was sent while one is pending');
    pendingWrite = { request, preference: clone(next), previousRevision: preference.revision };
    // Match native ModelSelectionCoordinator: reserve and completion both invalidate projections.
    selectionRevision++; publish();
    const hold = holdNext; holdNext = false;
    if (!hold) queueMicrotask(() => finishWrite('saved'));
  };
  const handle = request => {
    const { action, args = {} } = request;
    requireThat(request.hostDocumentToken === documentToken, 'Conversation document identity changed');
    switch (action) {
      case 'composer.intent':
        requireThat(phoneComposer, 'Phone composer fixture is not enabled');
        phoneDraft.phone = args.phone;
        if (args.mode) phoneDraft.mode = args.mode;
        publish(); reply(request, { changed: true }); return true;
      case 'phone.draft.edit':
        requireThat(phoneComposer && args.epoch === phoneDraft.epoch, 'Original phone draft changed');
        phoneDraft.text = args.text; phoneDraft.revision++;
        publish(); reply(request, { saved: true }); return true;
      case 'phone.prepare':
        requireKeys(args, ['goal', 'mode', 'epoch']);
        requireThat(phoneComposer && !phoneSubmitting && phoneDraft.phone && args.epoch === phoneDraft.epoch &&
          args.goal === phoneDraft.text && args.mode === phoneDraft.mode, 'Original phone goal changed');
        reply(request, { reviewHandle: 'fixture-phone-review-' + ++sequence, goal: args.goal, mode: args.mode,
          modelName: preference.modelName, toolLimit: 20, modelCallLimit: 32, scopeMinutes: 0,
          screenDisclosure: '测试宿主：未发送屏幕或调用模型。', message: '测试宿主：仅验证手机任务审阅入口。' });
        return true;
      case 'models.conversation.snapshot': reply(request, clone(preference)); return true;
      case 'models.conversation.review': {
        requireThat(!pendingWrite, 'Read attempted during a pending model mutation');
        const value = { ...clone(preference), selectionRevision, reviewHandle: 'fixture-conversation-review-' + ++sequence,
          expiresInMs: 120000, nativeSelection: nativeSelection(), facts: clone(facts) };
        reasoningReviews.set(value.reviewHandle, clone(value)); reply(request, value); return true;
      }
      case 'models.conversation.close': reasoningReviews.clear(); reply(request, { closed: true }); return true;
      case 'models.api.review': {
        requireKeys(args, ['windowId', 'connectionId', 'expectedRevision', 'modelId']);
        const projection = windows.get(args.windowId);
        requireThat(projection && projection.selectionRevision === selectionRevision && !pendingWrite, 'Original API projection expired');
        const model = projection.models.find(row => row.connectionId === args.connectionId && row.connectionRevision === args.expectedRevision && row.modelId === args.modelId);
        requireThat(model, 'Original API model identity changed');
        const value = { windowId: args.windowId, viewGeneration: projection.viewGeneration, selectionRevision,
          model: clone(model), reviewHandle: 'fixture-api-review-' + ++sequence, expiresInMs: 120000 };
        apiReviews.set(value.reviewHandle, clone(value)); reply(request, value); return true;
      }
      case 'models.conversation.api.select': {
        requireKeys(args, ['windowId', 'reviewHandle', 'expectedPreferenceRevision']);
        const review = apiReviews.get(args.reviewHandle);
        requireThat(review && review.windowId === args.windowId && windows.has(args.windowId) && review.selectionRevision === selectionRevision, 'Original API review/window was not preserved');
        requireThat(args.expectedPreferenceRevision === preference.revision, 'Original conversation preference revision was not preserved');
        requireThat(review.model.connectionId === 'fixture-openai', 'This fixture only selects its two OpenAI models');
        apiReviews.delete(args.reviewHandle);
        const model = review.model;
        beginWrite(request, { ...clone(preference), inherited: false, modelName: model.modelId,
          target: { kind: 'api', connectionId: model.connectionId, connectionRevision: model.connectionRevision,
            binding: { connectionId: model.connectionId, connectionRevision: model.connectionRevision, catalogRevision: 11 },
            modelId: model.modelId, outputTokens: 4096, imageTokenReserve: 0,
            reasoning: { mode: 'OPENAI_EFFORT', level: 'medium', budgetTokens: 0 } } });
        return true;
      }
      case 'models.conversation.reasoning': {
        requireKeys(args, ['reviewHandle', 'reasoning']);
        const review = reasoningReviews.get(args.reviewHandle);
        requireThat(review && review.scopeKey === preference.scopeKey && review.revision === preference.revision && review.selectionRevision === selectionRevision, 'Original reasoning review was not preserved');
        requireKeys(args.reasoning, ['mode', 'level', 'budgetTokens']);
        requireThat(args.reasoning.mode === 'OPENAI_EFFORT' && ['low', 'medium', 'high'].includes(args.reasoning.level) && args.reasoning.budgetTokens === 0, 'Unsupported synthetic reasoning choice');
        reasoningReviews.delete(args.reviewHandle);
        beginWrite(request, { ...clone(preference), target: { ...clone(preference.target), reasoning: clone(args.reasoning) } });
        return true;
      }
      case 'models.conversation.reset': {
        requireKeys(args, ['expectedPreferenceRevision', 'expectedSelectionRevision']);
        requireThat(args.expectedPreferenceRevision === preference.revision && args.expectedSelectionRevision === selectionRevision, 'Reset changed the original preference identity');
        beginWrite(request, { ...clone(preference), inherited: true, target: null, modelName: 'fixture-reasoner' }); return true;
      }
      case 'conversation.select':
        requireKeys(args, ['id']); requireThat(args.id === conversationId, 'Unexpected conversation selected in this bounded journey');
        publish(); reply(request, { selected: true, conversationId }); return true;
      case 'conversation.new': throw new Error('Journey must preserve the original conversation and draft');
      default: return false;
    }
  };
  window.PocketNative.postMessage = raw => {
    const request = JSON.parse(raw); calls.push(clone(request)); requests.set(request.id, clone(request));
    if (request.action === 'connections.leave') { windows.clear(); apiReviews.clear(); }
    if (!request.action.startsWith('models.conversation.') && request.action !== 'models.api.review' && !request.action.startsWith('conversation.') &&
      !(phoneComposer && ['composer.intent', 'phone.draft.edit', 'phone.prepare'].includes(request.action))) return originalPost(raw);
    queueMicrotask(() => {
      try { if (!handle(request)) originalPost(raw); }
      catch (error) { errors.push(String(error.message || error)); reply(request, null, { code: 'not_accepted', message: String(error.message || error) }); }
    });
  };
  window.aiConversationFixture = {
    synthetic: true, calls, receipts,
    setPhoneSubmitting(value) { requireThat(phoneComposer, 'Phone composer fixture is not enabled'); phoneSubmitting = value; publish(); },
    holdNextWrite() { requireThat(!pendingWrite && !holdNext, 'A synthetic write is already held'); holdNext = true; },
    resolveWrite: finishWrite,
    summary() { return { synthetic: true, preference: clone(preference), selectionRevision,
      globalModel: lastSnapshot?.state.modelName, globalNativeSelection: clone(lastSnapshot?.state.modelConnections?.nativeSelection),
      composerText: lastSnapshot?.composer.text, phoneDraft: clone(phoneDraft), pending: pendingWrite?.request.action || null,
      injectedOutcomes: clone(injectedOutcomes), errors: [...errors, ...window.aiCenterFixture.errors],
      calls: calls.map(({ action, args }) => ({ action, args: clone(args) })) }; },
  };
}
