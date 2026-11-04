/** Synthetic native receipts for the real production Web UI. No Android renderer or source-authority claim. */
export function installProjectPreviewHost(options = {}) {
  let token = 'preview-document-1', revision = 0, serial = 0, heldReturn = null, holdReturnState = false, ended = false;
  let unavailable = false, capture = null;
  let holdInspect = false, heldInspect = null, holdOpen = false, heldOpen = null, holdClose = false, heldClose = null, sourceChanged = false;
  window.__pocketDocumentToken = token;
  const calls = [], errors = [];
  const quote = { sourceRef: { sourceId: 'original-message', revision: 7 }, offset: 2, chars: 9 };
  const attachment = { id: 'original-attachment', name: '原附件.txt', mimeType: 'text/plain', sha256: 'd'.repeat(64), size: 12 };
  const originalDraft = { ready: true, key: 'project-draft', epoch: 3, revision: 7, conversationId: 'project-conversation',
    text: '请检查这个按钮，保留原来说明。', mode: 'queue', attachments: [attachment], sourceRefs: [quote], previewRefs: [], previewRefsAvailable: true, pending: [] };
  const commands = ['app.ready', 'app.refresh', 'composer.reading', 'composer.edit', 'composer.send', 'composer.source.remove', 'composer.preview.remove',
    'projects.refresh', 'projects.select', 'projects.open', 'projects.close', 'projects.preview', 'projects.preview.mode', 'projects.preview.viewport',
    'projects.preview.reload', 'projects.preview.close', 'projects.preview.return', 'attachments.remove', 'tasks.open'];
  const packages = [
    { operationId: '11111111-1111-4111-8111-111111111111', artifactId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', artifactSha256: '1'.repeat(64), mode: 'web_output', entryPath: 'dist/home.html', entryChoices: ['dist/home.html'] },
    { operationId: '22222222-2222-4222-8222-222222222222', artifactId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', artifactSha256: '2'.repeat(64), mode: 'source_project', entryPath: '', entryChoices: ['site/start.html', 'demo/button.html'] },
    { operationId: '33333333-3333-4333-8333-333333333333', artifactId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc', artifactSha256: '3'.repeat(64), mode: 'source_project', entryPath: '', entryChoices: [] },
    { operationId: '44444444-4444-4444-8444-444444444444', artifactId: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd', artifactSha256: '4'.repeat(64), mode: 'source_project', entryPath: '', entryChoices: ['single/real-entry.html'] },
  ];
  const packageChoices = item => ({ kind: 'package_preview_choices', taskId: 'original-project-task', conversationId: originalDraft.conversationId,
    projectId: 'project-a', checkoutId: 'checkout-original', sourceDigest: 'b'.repeat(64), ...structuredClone(item) });
  const packageOperations = packages.map(item => ({ operationId: item.operationId, runId: 'source-run', tool: 'project_execute', state: 'receipt', bodyAvailable: true,
    arguments: { stepId: 'fixed-' + item.operationId }, receipt: { status: 'completed', accepted: true, code: 'project_file_receipt',
      projectExecution: { authority: 'native_host_operation', intent: 'freeze_package', taskId: 'original-project-task', operationId: item.operationId, runId: 'source-run', projectId: 'project-a', checkoutId: 'checkout-original' },
      executionReceipt: { status: 'completed', artifactReady: true, receiptReceived: true, operationId: item.operationId, packageArtifact: { id: item.artifactId, sha256: item.artifactSha256, kind: 'project_bundle' } } } }));
  const snapshot = { host: { connected: true, ready: true }, view: { section: null }, capabilities: { commands },
    composer: structuredClone(originalDraft), phoneDraft: { text: '', epoch: 1, revision: 1, phone: false, mode: 'background' },
    state: { modelName: '当前模型', modelRoute: 'api_key', modelSelectionBusy: false, messages: [], tasks: [],
      projects: [{ id: 'project-a', name: '原项目' }], projectId: 'project-a', files: [{ path: 'index.html', size: 128, language: 'html' }], filePath: null,
      conversations: [{ id: 'project-conversation', title: '原项目讨论' }, { id: 'other-conversation', title: '另一份草稿' }], conversationId: 'project-conversation',
      selectedChatAttachments: [attachment], chatBusy: false, chatSubmitting: false, features: {}, ownerSnapshots: {}, ownerResponses: {}, ownerErrors: {}, ownerPending: [],
      projectPreview: null, requestedProjectRevision: 0, phoneReadiness: { groups: [] }, resumeRevision: 1 } };
  if (options.packages) {
    commands.push('tasks.close', 'tasks.refresh', 'projects.package.preview.inspect', 'projects.package.preview.open');
    snapshot.state.projects.push({ id: 'other-project', name: '另一个项目' }); snapshot.state.projectId = 'other-project';
    snapshot.state.tasks = [{ id: 'original-project-task', goal: '核对原项目中的固定成果', status: 'running', summary: '任务已保存四份固定版本', updatedAt: 1,
      raw: { id: 'original-project-task', kind: 'assistant', actorId: 'owner', spaceId: 'private', runId: 'source-run', coordinatorEpoch: 4, conversationId: originalDraft.conversationId,
        developmentContext: { projectId: 'project-a', checkoutId: 'checkout-original' }, operations: packageOperations, events: [] } }];
  }
  const publish = () => window.PocketWeb.receive({ v: 1, kind: 'state', revision: ++revision, snapshot: structuredClone(snapshot) });
  const reply = (request, value, error) => window.PocketWeb.receive({ v: 1, kind: 'result', id: request.id, ok: !error, ...(error ? { error } : { value }) });
  const saveDraft = () => { if (snapshot.composer.conversationId === originalDraft.conversationId) Object.assign(originalDraft, structuredClone(snapshot.composer)); };
  const restoreDraft = () => { snapshot.composer = structuredClone(originalDraft); snapshot.state.conversationId = originalDraft.conversationId; snapshot.state.selectedChatAttachments = structuredClone(originalDraft.attachments); };
  const requireSession = args => {
    const preview = snapshot.state.projectPreview;
    if (!preview || args.sessionId !== preview.sessionId) throw new Error('原预览文档已变化，未操作新页面');
    return preview;
  };
  const execute = request => {
    const { action, args } = request;
    try {
      if (request.hostDocumentToken !== token) throw new Error('原页面已变化');
      let value = { accepted: true };
      if (action === 'app.ready' || action === 'app.refresh') { publish(); value = { ready: true }; }
      else if (action === 'tasks.open' && options.packages) {
        if (args.id !== 'original-project-task') throw new Error('原任务不同'); snapshot.view.taskId = args.id; value = { taskId: args.id, readOnly: true, executionAuthorized: false }; publish();
      } else if (action === 'tasks.close' && options.packages) {
        if (holdClose) { heldClose = request; return; } snapshot.view.taskId = null; publish();
      } else if (action === 'projects.package.preview.inspect' && options.packages) {
        if (Object.keys(args).sort().join(',') !== 'operationId,taskId' || args.taskId !== 'original-project-task' || snapshot.view.taskId) throw new Error('原任务窗口或读取参数不符');
        const item = packages.find(row => row.operationId === args.operationId); if (!item || item.unavailable) throw new Error('原固定包已不可读取，没有改用当前工作区。');
        value = packageChoices(item); if (holdInspect) { heldInspect = { request, value }; return; }
      } else if (action === 'projects.package.preview.open' && options.packages) {
        if (Object.keys(args).sort().join(',') !== 'entryPath,operationId,taskId' || args.taskId !== 'original-project-task' || snapshot.state.projectId !== 'project-a') throw new Error('固定包入口或原项目参数不符');
        const item = packages.find(row => row.operationId === args.operationId); if (!item || item.unavailable || !item.entryChoices.includes(args.entryPath)) throw new Error('原固定包入口不可读取，未改用当前文件。');
        originalDraft.previewRefsAvailable = originalDraft.previewRefs.length === 0;
        if (snapshot.composer.conversationId === originalDraft.conversationId) snapshot.composer.previewRefsAvailable = originalDraft.previewRefsAvailable;
        snapshot.state.projectPreview = { kind: 'bundle', sessionId: 'package-session-' + ++serial, taskId: 'original-project-task', conversationId: originalDraft.conversationId,
          packageOperationId: item.operationId, documentGeneration: 0, ready: false, selecting: false, visible: true, error: null,
          selectionAvailable: !sourceChanged, selectionUnavailableReason: sourceChanged ? '当前实际源码已变化，不能沿旧版本选择。' : null,
          metadata: { origin: 'fixed_workspace_project_artifact', projectId: 'project-a', previewId: item.artifactSha256, checkoutId: 'checkout-original', sourceDigest: 'b'.repeat(64),
            artifactId: item.artifactId, artifactSha256: item.artifactSha256, entryPath: args.entryPath, persisted: true } };
        value = structuredClone(snapshot.state.projectPreview); publish(); if (holdOpen) { heldOpen = { request, value }; return; }
      }
      else if (action === 'projects.preview') {
        if (unavailable) throw new Error('当前项目没有已登记的预览，请先核对原项目的采集记录。');
        if (args.projectId !== 'project-a') throw new Error('原项目已变化');
        snapshot.state.projectPreview = { kind: 'bundle', sessionId: 'preview-session-' + ++serial, taskId: 'original-project-task', conversationId: originalDraft.conversationId,
          documentGeneration: 0, ready: false, selecting: false, visible: true, error: null, selectionAvailable: true, selectionUnavailableReason: null,
          metadata: { projectId: 'project-a', previewId: 'a'.repeat(64), checkoutId: 'checkout-original', sourceDigest: 'b'.repeat(64), entryPath: 'index.html' } };
        value = structuredClone(snapshot.state.projectPreview); publish();
      } else if (action === 'projects.preview.mode') {
        const view = requireSession(args); if (args.enabled && (!view.ready || !view.visible || !view.selectionAvailable)) throw new Error('请等待原预览显示完成并核对原来源再选择元素');
        if (args.enabled) restoreDraft(); view.selecting = args.enabled; view.error = null; value = structuredClone(view); publish();
      } else if (action === 'projects.preview.viewport') {
        const view = requireSession(args); view.visible = args.visible; if (!args.visible) view.selecting = false; value = structuredClone(view); publish();
      } else if (action === 'projects.preview.reload') {
        const view = requireSession(args); view.documentGeneration++; view.ready = false; view.selecting = false; view.error = null;
        originalDraft.previewRefsAvailable = originalDraft.previewRefs.length === 0; if (snapshot.composer.conversationId === originalDraft.conversationId) snapshot.composer.previewRefsAvailable = originalDraft.previewRefsAvailable;
        value = structuredClone(view); publish();
      } else if (action === 'projects.preview.close') {
        const view = requireSession(args); value = { sessionId: view.sessionId, closed: true }; snapshot.state.projectPreview = null;
        originalDraft.previewRefsAvailable = originalDraft.previewRefs.length === 0; if (snapshot.composer.conversationId === originalDraft.conversationId) snapshot.composer.previewRefsAvailable = originalDraft.previewRefsAvailable;
        publish();
      } else if (action === 'projects.preview.return') {
        const view = requireSession(args); view.visible = false; view.selecting = false;
        value = { sessionId: view.sessionId, conversationId: view.conversationId, returned: true };
        if (holdReturnState) { heldReturn = { request, value }; reply(request, value); return; }
        restoreDraft(); publish();
      } else if (action === 'composer.preview.remove') {
        if (args.key !== snapshot.composer.key || args.epoch !== snapshot.composer.epoch || args.revision !== snapshot.composer.revision) throw new Error('原草稿已变化');
        snapshot.composer.previewRefs.splice(args.index, 1); snapshot.composer.revision++;
        if (!snapshot.composer.previewRefs.length) snapshot.composer.previewRefsAvailable = true;
        saveDraft(); value = { removed: true, draftStaged: true }; publish();
      } else if (action === 'composer.edit') {
        if (args.key !== snapshot.composer.key || args.epoch !== snapshot.composer.epoch) throw new Error('原输入已变化');
        snapshot.composer.text = args.text; snapshot.composer.revision++; saveDraft(); publish();
      } else if (action === 'composer.send') {
        if (ended) throw new Error('原项目任务已结束，无法沿原任务发送；草稿仍保留。');
        if (!snapshot.composer.previewRefsAvailable) throw new Error('原预览已失效');
        if (!options.packages) throw new Error('Fixture does not execute model requests');
        if (!args.text.trim()) throw new Error('需要原主人文字'); value = { accepted: true, status: 'accepted', taskId: 'original-project-task' };
      } else if (action === 'projects.select') { if (!snapshot.state.projects.some(row => row.id === args.id)) throw new Error('未知项目'); snapshot.state.projectId = args.id; snapshot.state.filePath = null; publish(); }
      else if (!['composer.reading', 'projects.refresh', ...(options.packages ? ['tasks.refresh'] : [])].includes(action)) throw new Error('Unexpected fixture command: ' + action);
      reply(request, value);
    } catch (failure) {
      const message = String(failure.message || failure);
      if (message.startsWith('Unexpected') || message.startsWith('Fixture')) errors.push(message);
      reply(request, null, { code: 'not_accepted', message });
    }
  };
  window.PocketNative = { postMessage(raw) { const request = JSON.parse(raw); calls.push(structuredClone(request)); queueMicrotask(() => execute(request)); } };
  window.previewFixture = {
    calls, errors,
    snapshot: () => structuredClone(snapshot),
    ready() { const view = snapshot.state.projectPreview; if (!view) throw new Error('No preview'); view.documentGeneration = Math.max(1, view.documentGeneration); view.ready = true; publish(); },
    unavailable() { unavailable = true; },
    beginCapture() { const view = snapshot.state.projectPreview; if (!view?.selecting) throw new Error('Selection is not enabled'); capture = { sessionId: view.sessionId, generation: view.documentGeneration, key: snapshot.composer.key, epoch: snapshot.composer.epoch, revision: snapshot.composer.revision }; },
    finishCapture() {
      const view = snapshot.state.projectPreview, c = snapshot.composer;
      if (!capture || !view || !view.selecting || capture.sessionId !== view.sessionId || capture.generation !== view.documentGeneration || capture.key !== c.key || capture.epoch !== c.epoch || capture.revision !== c.revision) {
        if (view) { view.error = '原草稿已变化，本次元素选择未加入；请重新选择。'; view.selecting = false; } capture = null; publish(); return;
      }
      const ref = { referenceId: 'native-reference-' + (c.previewRefs.length + 1), taskId: view.taskId, runId: 'source-run', epoch: 4, conversationId: view.conversationId,
        projectId: 'project-a', checkoutId: view.metadata.checkoutId, previewId: view.metadata.previewId, sourceDigest: view.metadata.sourceDigest,
        documentId: 'native-document', documentGeneration: view.documentGeneration, path: view.metadata.entryPath, resourceSha256: 'c'.repeat(64), selectorHint: 'main > button > span',
        text: '保存修改', htmlSnippet: '<button><span>保存修改</span><img src=x onerror="window.untrustedExecuted=true"></button>',
        bounds: { x: 20, y: 160, width: 80, height: 44 }, truncated: { selectorHint: false, text: false, htmlSnippet: false }, untrusted: true, selectorIsSourceLocation: false };
      if (view.packageOperationId) Object.assign(ref, { sourceKind: 'fixed_workspace_project_artifact', artifactId: view.metadata.artifactId, artifactSha256: view.metadata.artifactSha256, packageOperationId: view.packageOperationId });
      c.previewRefs.push(ref); c.previewRefsAvailable = true; c.revision++; saveDraft(); capture = null; view.selecting = false; publish();
    },
    changeDraft() { snapshot.composer.text = '这份新草稿必须保留。'; snapshot.composer.revision++; saveDraft(); publish(); },
    otherDraft() { saveDraft(); snapshot.state.conversationId = 'other-conversation'; snapshot.composer = { ...structuredClone(originalDraft), key: 'other-draft', epoch: 8, revision: 1, conversationId: 'other-conversation', text: '另一份未发送草稿', sourceRefs: [], attachments: [], previewRefs: [], previewRefsAvailable: true }; snapshot.state.selectedChatAttachments = []; publish(); },
    holdReturn() { holdReturnState = true; },
    releaseReturn() { if (!heldReturn) throw new Error('No held return state'); heldReturn = null; holdReturnState = false; restoreDraft(); publish(); },
    rotateDocument() { token += '-next'; snapshot.state.resumeRevision++; snapshot.state.projectPreview = null; originalDraft.previewRefsAvailable = originalDraft.previewRefs.length === 0; if (snapshot.composer.conversationId === originalDraft.conversationId) snapshot.composer.previewRefsAvailable = originalDraft.previewRefsAvailable; window.PocketWeb.hostConfiguration({ documentToken: token }); publish(); },
    endRecipient() { ended = true; },
    coldReference() { if (!originalDraft.previewRefs.length) throw new Error('No original ref'); snapshot.state.projectPreview = null; originalDraft.previewRefsAvailable = false; restoreDraft(); publish(); },
    holdClose() { holdClose = true; },
    releaseClose() { if (!heldClose) throw new Error('No held close'); const request = heldClose; heldClose = null; holdClose = false; snapshot.view.taskId = null; publish(); reply(request, { accepted: true }); },
    holdInspect() { holdInspect = true; },
    releaseInspect(wrongTuple = false) { if (!heldInspect) throw new Error('No held inspect'); const { request, value } = heldInspect; heldInspect = null; holdInspect = false; reply(request, wrongTuple ? { ...value, operationId: packages[1].operationId } : value); },
    holdOpen() { holdOpen = true; },
    releaseOpen(wrongTuple = false) { if (!heldOpen) throw new Error('No held open'); const { request, value } = heldOpen; heldOpen = null; holdOpen = false; reply(request, wrongTuple ? { ...value, packageOperationId: packages[1].operationId } : value); },
    changeSource() { sourceChanged = true; const view = snapshot.state.projectPreview; if (view) { view.selecting = false; view.selectionAvailable = false; view.selectionUnavailableReason = '当前实际源码已变化，不能沿旧版本选择。'; } originalDraft.previewRefsAvailable = originalDraft.previewRefs.length === 0; if (snapshot.composer.conversationId === originalDraft.conversationId) snapshot.composer.previewRefsAvailable = originalDraft.previewRefsAvailable; publish(); },
    retirePackage(index = 0) { packages[index].unavailable = true; },
    clearText() { snapshot.composer.text = ''; snapshot.composer.revision++; saveDraft(); publish(); },
    packageOperation: index => packages[index].operationId,
  };
}
