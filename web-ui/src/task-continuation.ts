/** Presentation checks for the original native review. Public metadata never grants continuation. */
type Data = Record<string, any>;
const object = (value: unknown): Data => value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Data : {};
const text = (value: unknown): value is string => typeof value === 'string' && value.trim().length > 0;
const epoch = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) > 0 && Number(value) <= 2147483647;

export type FixedProjectDraft = { taskId:string; runId:string; epoch:number; draftId:string; projectId:string; checkoutId:string };
export type ProjectContinuation = { feedbackRequired: true; conversationId: string; reason?: string; fixedDraft?: FixedProjectDraft };
export type ContinuationOriginal = { taskId: string; conversationId: string; runId: string; epoch: number; reviewHandle: string };
export type ContinuationSubmission = { original: ContinuationOriginal; status: 'pending' | 'unknown' | 'continued' | 'rejected'; message: string };

/** Lookup/display hint only; tasks.review still decides which actions are available. */
export function privateProjectTask(value: unknown): boolean {
  const task = object(value), raw = object(task.raw), context = object(raw.developmentContext);
  return text(task.id) && raw.kind === 'assistant' && raw.actorId === 'owner' && raw.spaceId === 'private' && raw.origin == null && text(context.projectId) && text(context.checkoutId);
}

/** A saved decision plus a positive pre-dispatch receipt, not usable approval or live runtime status. */
export function savedUnsentProfileProjectApproval(value: unknown): string | undefined {
  const task = object(value), raw = object(task.raw), context = object(raw.developmentContext);
  const profile = object(raw.workProfileRef), audit = object(raw.historyOperationPage);
  const sha = (v: unknown) => typeof v === 'string' && /^[a-f0-9]{64}$/.test(v);
  const id = (v: unknown) => typeof v === 'string' && /^[a-f0-9-]{36}$/.test(v);
  if (!privateProjectTask(task) || raw.id !== task.id || task.status !== 'awaiting_user' || raw.status !== task.status ||
      raw.executionMode !== 'capabilities' || raw.cancelRequested !== false || raw.contextAvailable === false ||
      raw.contextWithdrawn === true || raw.sourceCurrentReadRequired === true || raw.hasUnresolvedOperations !== false ||
      raw.historyHasUnconfirmedOperations !== false || raw.taskProjection !== 'detail_window' || raw.taskCoreReadLimited !== false ||
      raw.auditBodyIncluded !== true || context.format !== 'pocketworld.development-context.v1' || !sha(context.initialSourceDigest) ||
      !id(raw.runId) || !epoch(raw.coordinatorEpoch) || profile.runId !== raw.runId || profile.epoch !== raw.coordinatorEpoch ||
      !text(profile.profileId) || !Number.isSafeInteger(profile.revision) || profile.revision < 1 ||
      !sha(profile.profileDigest) || !sha(profile.resolutionDigest)) return;
  // Require the existing complete page to rule out a competing project call in this run. Do not fetch more history.
  if (audit.format !== 'pocketworld.task-audit-page.v1' || audit.taskId !== task.id || audit.section !== 'operations' ||
      audit.executionAuthorized !== false || audit.completeHistory !== true || audit.hasMore !== false || audit.readLimited !== false ||
      !Array.isArray(audit.entries) || audit.entries.length > 24 || !Array.isArray(raw.approvals)) return;
  const calls = audit.entries.map(object).filter(row => row.tool === 'project_execute' && row.runId === raw.runId);
  if (calls.length !== 1) return;
  const call = calls[0], receipt = object(call.receipt), args = object(call.arguments);
  if (call.status !== 'available' || call.state !== 'receipt' || !id(call.operationId) ||
      receipt.code !== 'approval_required' || receipt.status !== 'not_dispatched' || receipt.accepted !== false ||
      receipt.projectExecution !== undefined || receipt.executionReceipt !== undefined || !id(receipt.approvalId) ||
      !id(args.planRef) || !sha(args.planDigest) || !text(args.stepId) || Object.keys(args).length !== 3) return;
  const approvals = raw.approvals.map(object), matching = approvals.filter((row: Data) => row.id === receipt.approvalId);
  if (matching.length !== 1) return;
  const approval = matching[0], reviewed = object(approval.arguments), capability = object(approval.capability);
  const plan = object(capability.nativeProjectPlan), step = object(plan.step), request = object(step.request);
  if (approval.runId !== raw.runId || approval.tool !== call.tool || approval.state !== 'approved' ||
      approval.continuedFromRun != null || approval.policyDecision != null || !sha(approval.argumentsHash) ||
      approvals.some((row: Data) => row !== approval && row.runId === raw.runId && ['pending', 'approved'].includes(row.state)) ||
      Object.keys(reviewed).length !== 3 || reviewed.planRef !== args.planRef || reviewed.planDigest !== args.planDigest || reviewed.stepId !== args.stepId ||
      capability.feature !== 'local' || capability.checkoutId !== context.checkoutId || capability.sourceDigest !== context.initialSourceDigest ||
      plan.fullPlanStoredInOriginalTaskStore !== true || plan.executionAuthorized !== false || plan.planDigest !== args.planDigest ||
      step.id !== args.stepId || !text(step.localCommand) || step.localCommand !== capability.command || request.checkoutId !== context.checkoutId ||
      (request.expectedSourceDigest != null && request.expectedSourceDigest !== capability.sourceDigest)) return;
  return approval.id;
}

function fixedDraftPin(value:unknown):FixedProjectDraft|undefined {
  const draft=object(value);
  if(text(draft.taskId)&&text(draft.runId)&&epoch(draft.epoch)&&text(draft.draftId)&&/^[a-f0-9]{64}$/.test(draft.draftId)&&text(draft.projectId)&&text(draft.checkoutId))
    return {taskId:draft.taskId,runId:draft.runId,epoch:draft.epoch,draftId:draft.draftId,projectId:draft.projectId,checkoutId:draft.checkoutId};
}

/** An already verified, navigation-only return can recheck this identity after the detail closes. */
export function sameFixedProjectDraftIdentity(value:unknown,pin:FixedProjectDraft):boolean {
  const task=object(value),raw=object(task.raw),scope=object(raw.developmentContext),source=object(raw.ownerWorkPreparation);
  return privateProjectTask(task)&&task.id===pin.taskId&&raw.id===pin.taskId&&raw.runId===pin.runId&&raw.coordinatorEpoch===pin.epoch&&
    raw.projectEditDraftId===pin.draftId&&scope.projectId===pin.projectId&&scope.checkoutId===pin.checkoutId&&scope.draftOnly===true&&
    scope.format==='pocketworld.development-context.v1'&&scope.runtime==='managed_process_v1'&&raw.executionMode==='capabilities'&&
    source.format==='pocketworld.owner-work-preparation.v1'&&source.targetTaskId===pin.taskId&&
    raw.contextAvailable!==false&&raw.contextWithdrawn!==true&&raw.sourceCurrentReadRequired!==true&&raw.taskProjection!=='source_unavailable'&&raw.hasUnresolvedOperations!==true;
}

/** Route hints require the current full native view, not a retained review or a task-list pointer. */
export function observedFixedProjectDraft(value:unknown):FixedProjectDraft|undefined {
  const raw=object(object(value).raw),pin=fixedDraftPin(raw.originalFixedDraftFollowup);
  if(pin&&raw.taskProjection==='detail_window'&&raw.taskCoreReadLimited===false&&raw.hasUnresolvedOperations===false&&sameFixedProjectDraftIdentity(value,pin))return pin;
}

export function matchesFixedProjectDraft(value:unknown,pin:FixedProjectDraft):boolean {
  return !!observedFixedProjectDraft(value)&&sameFixedProjectDraftIdentity(value,pin);
}

export function projectContinuation(value: unknown): ProjectContinuation | null {
  const review = object(value), candidate = object(review.projectContinuation), task = object(review.task), raw = object(task.raw);
  if (!privateProjectTask(task) || candidate.feedbackRequired !== true || typeof candidate.conversationId !== 'string' ||
      candidate.conversationId !== (typeof raw.conversationId === 'string' ? raw.conversationId : '') ||
      candidate.reason !== undefined && !text(candidate.reason)) return null;
  const fixedDraft=text(candidate.reason)&&Array.isArray(review.actions)&&
    review.actions.every((action:unknown)=>['tasks.confirm','tasks.scope','tasks.plan.enter','tasks.deleteScreenshot'].includes(object(action).action))&&
    review.ownerQuestion==null&&Array.isArray(review.approvals)&&!review.approvals.length&&Array.isArray(review.unresolvedOperations)&&!review.unresolvedOperations.length
    ? fixedDraftPin(candidate.fixedDraft):undefined;
  return { feedbackRequired: true, conversationId: candidate.conversationId, ...(candidate.reason ? { reason: candidate.reason } : {}),
    ...(fixedDraft&&matchesFixedProjectDraft(task,fixedDraft)?{fixedDraft}:{}) };
}

export function continuationRequest(value: unknown, taskId: string, feedback: string) {
  const review = object(value), task = object(review.task), raw = object(task.raw), metadata = projectContinuation(review);
  const note = feedback.trim();
  if (!metadata || metadata.reason || !text(metadata.conversationId) || task.id !== taskId || raw.id !== taskId ||
      !text(review.reviewHandle) || !text(raw.runId) || !epoch(raw.coordinatorEpoch) ||
      !Array.isArray(review.actions) || !review.actions.some((action: unknown) => object(action).action === 'tasks.resume'))
    throw new Error('请先读取这个原项目任务的继续审阅。');
  if (!note || note.length > 8000) throw new Error('请填写 1–8000 字的本次修改要求。');
  const original: ContinuationOriginal = { taskId, conversationId: metadata.conversationId, runId: raw.runId, epoch: raw.coordinatorEpoch, reviewHandle: review.reviewHandle };
  return { original, args: { reviewHandle: original.reviewHandle, confirmed: true as const, feedback: note } };
}

export function continuationReceiptMatches(value: unknown, original: ContinuationOriginal): boolean {
  const result = object(value);
  return result.continued === true && result.taskId === original.taskId && result.conversationId === original.conversationId &&
    text(result.runId) && result.runId !== original.runId && epoch(result.epoch) && result.epoch > original.epoch;
}

export function continuationUnknown(current: ContinuationSubmission): ContinuationSubmission {
  return current.status === 'pending' ? { ...current, status: 'unknown', message: '尚未确认这次续接结果。请核对原任务状态；反馈和对话草稿均保留，没有再次提交。' } : current;
}

export function continuationSettled(current: ContinuationSubmission, result: unknown, windowCurrent: boolean): ContinuationSubmission {
  if (!windowCurrent || current.status !== 'pending') return continuationUnknown(current);
  if (!continuationReceiptMatches(result, current.original)) return continuationUnknown(current);
  return { ...current, status: 'continued', message: '原项目任务已续接。对话草稿尚未发送，请返回原对话后单独发送；实际进展以原任务记录为准。' };
}

export function continuationFailed(current: ContinuationSubmission, error: unknown, windowCurrent: boolean): ContinuationSubmission {
  if (!windowCurrent || current.status !== 'pending') return continuationUnknown(current);
  const failure = object(error);
  if (failure.code === 'not_sent' || failure.code === 'not_accepted') return { ...current, status: 'rejected', message: typeof failure.message === 'string' ? failure.message : '这次续接未受理，反馈和草稿仍保留。' };
  return continuationUnknown(current);
}

export function continuationBlocks(value?: ContinuationSubmission): boolean { return value?.status === 'pending' || value?.status === 'unknown'; }
