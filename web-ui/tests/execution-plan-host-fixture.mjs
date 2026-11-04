/** Isolated native-transport fixture for the real production task drawer, never a TaskStore substitute. */
export function installExecutionPlanHost() {
  const token = 'execution-plan-fixture-document'; window.__pocketDocumentToken = token;
  const taskId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', runId = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
  const storageKey = 'execution-plan-host-fixture';
  const initial = { id: taskId, goal: '整理原项目资料并给出可核对的修改计划', status: 'paused', summary: '原任务已暂停，可先核对本次范围。', executionMode: 'capabilities', updatedAt: 1730000000000,
    raw: { id: taskId, goal: '整理原项目资料并给出可核对的修改计划', kind: 'assistant', actorId: 'owner', spaceId: 'private', status: 'paused', runId, coordinatorEpoch: 3,
      approvals: [], executionScope: { format: 'pocketworld.task-execution-scope.v1', taskId, principalId: 'owner', spaceId: 'private', state: 'active', revision: 8,
        toolLimit: 20, toolsUsed: 7, modelCallLimit: 12, modelCallsUsed: 4,
        deadlineAt: 0, lifetime: 'until_complete_or_stopped_or_budget', autoRefill: false, tools: ['read_document'], toolsPinned: true } } };
  let task = initial, plan = null;
  try { const saved = JSON.parse(localStorage.getItem(storageKey)); if (saved?.task?.id === taskId) { task = saved.task; plan = saved.plan; } } catch {}
  const commands = ['app.ready', 'app.refresh', 'composer.reading', 'tasks.refresh', 'tasks.open', 'tasks.close', 'tasks.review', 'tasks.scope', 'tasks.plan.enter', 'tasks.approve'];
  const calls = [], held = [], errors = [], handles = new Map();
  let revision = 0, serial = 0, generation = 1, windowTask = null, taskLoaded = false, readFailure = false, delay = new Set();
  const snapshot = { host: { connected: true, ready: true }, view: { section: null, taskId: null }, capabilities: { commands },
    composer: { ready: true, key: 'execution-plan-draft', epoch: 1, revision: 1, text: '', attachments: [], sourceRefs: [], pending: [], mode: 'queue', conversationId: '' },
    phoneDraft: { text: '', revision: 0, epoch: 1, phone: false, mode: 'background' },
    state: { modelName: 'fixture-model', tasks: [], messages: [], projects: [], conversations: [], selectedChatAttachments: [], features: {},
      ownerSnapshots: {}, ownerPending: [], ownerErrors: {}, ownerResponses: {}, taskActionErrors: {}, phoneReadiness: { groups: [] },
      requestedTaskId: taskId, requestedTaskRevision: 1, requestedProjectRevision: 0, requestedConnectionRevision: 0, activeTaskId: null } };
  const persist = () => localStorage.setItem(storageKey, JSON.stringify({ task, plan }));
  const publish = () => {
    snapshot.state.tasks = taskLoaded ? [structuredClone(task)] : []; persist();
    window.PocketWeb.receive({ v: 1, kind: 'state', revision: ++revision, snapshot: structuredClone(snapshot) });
  };
  const reply = (request, value, error) => window.PocketWeb.receive({ v: 1, kind: 'result', id: request.id, ok: !error, ...(error ? { error } : { value }) });
  const currentApproval = () => task.raw.approvals.find(row => row.state === 'pending');
  const reviewed = () => {
    const approval = currentApproval(), scope = task.raw.executionScope;
    const actions = [{ action: 'tasks.scope', label: '调整原任务范围' }];
    if (scope.executionPhase !== 'planning' && !approval) actions.push({ action: 'tasks.plan.enter', label: '进入只读规划' });
    if (approval) actions.push({ action: 'tasks.approve', label: '审阅操作授权' });
    const views = approval ? [{ ...structuredClone(approval), webReview: { kind: 'execution_plan', canApprove: !readFailure,
      ...(readFailure ? { readError: '未取得这版计划的完整原文，请重新核对；仍可拒绝这次申请' } : { executionPlan: { taskId, runId, epoch: 3,
        scopeRevision: scope.revision, planRevision: scope.planRevision, planArtifactRef: scope.planArtifactRef, planHash: scope.planHash, plan: structuredClone(plan) } }) } }] : [];
    const reviewHandle = 'plan-review-' + ++serial;
    handles.set(reviewHandle, { identity: JSON.stringify(task), generation, canApprove: !readFailure });
    return { reviewHandle, expiresInMs: 120000, task: structuredClone(task), approvals: views, actions, unresolvedOperations: [] };
  };
  const mutate = request => {
    const { action, args } = request;
    const captured = handles.get(args.reviewHandle); handles.delete(args.reviewHandle);
    if (!captured || captured.generation !== generation || windowTask !== taskId || captured.identity !== JSON.stringify(task)) throw new Error('原计划窗口或版本已变化');
    if (action === 'tasks.plan.enter') {
      if (Object.keys(args).join() !== 'reviewHandle') throw new Error('规划入场不能提交预算或替代身份');
      Object.assign(task.raw.executionScope, { executionPhase: 'planning', planRevision: 0, planArtifactRef: '', planHash: '' }); task.raw.executionScope.revision++;
      // Owner mutations return the original TaskStore task, not its UiTask display wrapper.
      publish(); return { ...structuredClone(task.raw), executionStarted: false, newGrant: false };
    }
    const approval = currentApproval(), scope = task.raw.executionScope;
    if (!approval || args.approvalId !== approval.id || typeof args.approved !== 'boolean') throw new Error('原审批不在当前计划中');
    if (args.approved) {
      if (!captured.canApprove || args.scopeReviewed !== true || args.planRevision !== scope.planRevision || args.planArtifactRef !== scope.planArtifactRef || args.planHash !== scope.planHash) throw new Error('未核对准确原计划');
      approval.state = 'consumed'; scope.executionPhase = 'executing'; scope.revision++;
    } else approval.state = 'denied';
    task.status = task.raw.status = 'paused'; publish();
    return { ...structuredClone(task.raw), approvalDecisionSaved: true, continuationScheduled: args.approved, continuedOriginalRun: false };
  };
  const execute = request => {
    try {
      if (request.hostDocumentToken !== token) throw new Error('原文档不匹配');
      const { action, args } = request; let value = { accepted: true };
      if (action === 'app.ready' || action === 'app.refresh' || action === 'tasks.refresh') publish();
      else if (action === 'tasks.open') {
        if (!taskLoaded || args.id !== taskId) throw new Error('请先重新读取这个原任务的历史窗口');
        windowTask = args.id; snapshot.view.taskId = args.id; generation++; handles.clear();
        value = { taskId: args.id, readOnly: true, executionAuthorized: false }; publish();
      }
      else if (action === 'tasks.close') { windowTask = null; snapshot.view.taskId = null; generation++; handles.clear(); publish(); }
      else if (action === 'tasks.review') { if (windowTask !== args.id) throw new Error('原任务窗口已关闭'); value = reviewed(); }
      else if (action === 'tasks.plan.enter' || action === 'tasks.approve') {
        if (delay.has(action)) { held.push({ request, generation }); return; }
        value = mutate(request);
      } else if (action !== 'composer.reading') throw new Error('未接入的 fixture 操作 ' + action);
      reply(request, value, null);
    } catch (failure) { reply(request, null, { code: 'outcome_unknown', message: String(failure.message || failure) }); }
  };
  window.PocketNative = { postMessage(raw) { const request = JSON.parse(raw); calls.push(structuredClone(request)); queueMicrotask(() => execute(request)); } };
  window.executionPlanFixture = {
    calls, held, errors,
    state: () => structuredClone(task),
    loadRequestedTask() { taskLoaded = true; publish(); },
    async propose(number, reopen = true) {
      plan = { format: 'pocketworld.execution-plan.v1', goal: '先核对资料，再在原授权范围内修改。', steps: [
        { id: 'read', title: '读取原资料', detail: '逐项读取已授权的原项目资料，记录现状与缺口；不写入项目文件。' },
        { id: 'verify', title: '核对修改与验收', detail: `计划版本 ${number}：列出所需修改及对应验收，确认后仍逐步核对原授权。` } ], sources: ['原任务中的项目资料与已读记录'] };
      const hash = [...new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(JSON.stringify(plan))))].map(value => value.toString(16).padStart(2, '0')).join('');
      const scope = task.raw.executionScope;
      Object.assign(scope, { revision: scope.revision + 1, executionPhase: 'planning', planRevision: number,
        planArtifactRef: 'cccccccc-cccc-4ccc-8ccc-' + String(number).padStart(12, '0'), planHash: hash });
      task.raw.approvals = [{ id: 'approval-' + number, runId, state: 'pending', tool: 'execution_plan_review', arguments: { planRevision: number, planArtifactRef: scope.planArtifactRef, planHash: hash },
        capability: { kind: 'execution_plan', scopeRevision: scope.revision, planRevision: number, planArtifactRef: scope.planArtifactRef, planHash: hash } }];
      task.status = task.raw.status = 'waiting_approval'; task.summary = '请核对准确计划后再决定是否继续。';
      if (reopen) { windowTask = null; snapshot.view.taskId = null; generation++; handles.clear(); snapshot.state.requestedTaskRevision++; }
      publish();
    },
    failBody(value) { readFailure = value; },
    hold(actions) { delay = new Set(actions); },
    reopen(nativeWindowAlreadyOpen = false) { windowTask = nativeWindowAlreadyOpen ? taskId : null; snapshot.view.taskId = windowTask; generation++; handles.clear(); snapshot.state.requestedTaskRevision++; publish(); },
    settle() {
      const item = held.shift(); if (!item) throw new Error('没有待核对的原请求');
      delay.clear();
      if (item.generation !== generation) reply(item.request, null, { code: 'outcome_unknown', message: '原计划窗口已关闭，请核对原记录；没有重复提交' });
      else execute(item.request);
    },
  };
}
