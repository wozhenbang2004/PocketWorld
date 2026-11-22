import { useEffect, useRef, useState, useSyncExternalStore, type ReactNode } from 'react';
import { bridge, type PocketSnapshot } from './bridge';
import { Accordion, AccordionItem, Button } from './forms-library';
import { taskAuditPage, type AuditPage, type TaskHeader } from './task-history';
import { getTaskOperations, type TaskOperation } from './task-presentation';
import { usePocketSnapshot } from './usePocketSnapshot';

const record = (value: unknown) => value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, any> : undefined;
const nativeId = (value: unknown): value is string => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/.test(value);
const digest = (value: unknown): value is string => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
const deviceId = (value: unknown): value is string => typeof value === 'string' && /^pw1_[a-f0-9]{64}$/.test(value);
type Original = { taskId: string; runId: string; epoch: number; operationId: string; deviceId: string; instanceId: string; fingerprint: string };
type WorkspaceRead = 'workspace.read' | 'workspace.list';
const RECEIVED_TEXT_LIMIT = 8 * 1024;
const RECEIVED_TEXT_PAGE_BYTES = 1024;
type Snapshot = { text: string; bytes: number; recordedAt: number };
type Received = { identity: string; requestDigest: string; content: string; bytes: number; version: string };
const directoryKinds = { directory: '目录', file: '文件', reparse_point: '重解析点，未跟随', unsupported: '其他' };
type DirectoryEntry = { name: string; kind: keyof typeof directoryKinds };
type ListedFile = { entryIndex: number; path: string };
type ReceivedDirectory = { identity: string; returned: number; truncated: boolean; entries: DirectoryEntry[]; linkedFileIndex?: number;
  picker?: { workspaceId: string; choices: ListedFile[]; linkedPath?: string } };

export const validWindowsWorkspaceFilePath = (path: string) => path.length > 0 && path.length <= 2048 &&
  !/[\\<>:"|?*\p{Cc}\p{Cf}\p{Cs}\p{Zl}\p{Zp}]/u.test(path) && path.split('/').every(part =>
    !!part && !/[. ]$/.test(part) && !/^(?:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/i.test(part));
type ReceivedFile = { original: Original; received: Received; path: string; position: number };

/** Visible local tool row only. Native rechecks the actual Work, wire, claim and current peer. */
function originalWindowsReadAt(task: TaskHeader, op: TaskOperation, tool: WorkspaceRead): Original | undefined {
  const raw = task.raw, work = raw?.ownerDeviceWork, pin = work?.directWorkspace, args = record(op.arguments);
  const send = record(record(op.receipt)?.nativeDeviceSend);
  if (!raw || raw.id !== task.id || raw.actorId !== 'owner' || raw.spaceId !== 'private' || raw.origin != null || raw.contextWithdrawn === true
    || work?.format !== 'pocketworld.owner-device-work.v1' || work.requestId !== task.id || work.directPhone != null
    || !nativeId(task.id) || !nativeId(raw.runId) || op.runId !== raw.runId || !Number.isSafeInteger(raw.coordinatorEpoch) || raw.coordinatorEpoch <= 0
    || !nativeId(op.operationId) || op.tool !== 'device_invoke_tool' || !op.bodyAvailable || !['unknown', 'receipt'].includes(op.state)
    || send?.format !== 'pocketworld.native-device-send.v1' || send.state !== 'write_claimed' || args?.toolName !== tool
    || typeof pin?.deviceId !== 'string' || !/^pw1_[a-f0-9]{64}$/.test(pin.deviceId) || args.targetDeviceId !== pin.deviceId
    || !nativeId(pin.instanceId) || typeof pin.certificateFingerprint !== 'string' || !/^[a-f0-9]{64}$/.test(pin.certificateFingerprint)) return;
  return { taskId: task.id, runId: raw.runId, epoch: raw.coordinatorEpoch, operationId: op.operationId,
    deviceId: pin.deviceId, instanceId: pin.instanceId, fingerprint: pin.certificateFingerprint };
}

export function originalWindowsTextAt(task: TaskHeader, op: TaskOperation): Original | undefined {
  return originalWindowsReadAt(task, op, 'workspace.read');
}

function currentReceivedWorkDigest(raw: TaskHeader['raw'], original: Omit<Original, 'operationId'>): string | undefined {
  const work = raw?.ownerDeviceWork, pin = work?.directWorkspace;
  return raw?.id === original.taskId && raw.runId === original.runId && raw.coordinatorEpoch === original.epoch &&
    raw.actorId === 'owner' && raw.spaceId === 'private' && raw.kind === 'assistant' && raw.executionMode === 'capabilities' &&
    raw.conversationId === '' && raw.origin == null && raw.ownerPhoneControl == null && raw.parentTaskId == null &&
    raw.contextAvailable !== false && raw.contextWithdrawn !== true && raw.sourceCurrentReadRequired !== true &&
    raw.taskProjection === 'detail_window' && raw.auditBodyIncluded === true && raw.taskCoreReadLimited === false &&
    work?.format === 'pocketworld.owner-device-work.v1' && work.requestId === original.taskId && digest(work.requestDigest) && work.directPhone == null &&
    pin?.deviceId === original.deviceId && pin.instanceId === original.instanceId && pin.certificateFingerprint === original.fingerprint
    ? work.requestDigest : undefined;
}

/** Consume only the already-loaded original SQL row, never the Agent's result wrapper. */
function receivedOriginalWindowsReadAt(task: TaskHeader, op: TaskOperation, original: Original, page?: AuditPage) {
  const requestDigest = currentReceivedWorkDigest(task.raw, original);
  if (!requestDigest || op.state !== 'receipt' || page?.taskId !== original.taskId ||
    page.section !== 'operations' || page.executionAuthorized !== false || !page.entries.some(row =>
      row.operationId === original.operationId && row.runId === original.runId && row.status === 'available' && row.state === 'receipt' &&
      row.tool === 'device_invoke_tool' && row.arguments === op.arguments && row.receipt === op.receipt)) return;
  const journal = record(op.receipt), args = record(record(op.arguments)?.arguments);
  const send = record(journal?.nativeDeviceSend), accepted = record(journal?.nativeDeviceAcceptedBinding);
  const result = record(accepted?.verifiedReceipt), audience = record(result?.audience), resource = record(audience?.resourceBinding);
  const job = accepted?.jobId, wireDigest = accepted?.operationArgumentsDigest;
  if (!result || !Number.isSafeInteger(send?.claimedAt) || send?.claimedAt <= 0 || !digest(send?.wireSha256) ||
    typeof send?.allocationId !== 'string' || !/^ba1_[a-f0-9]{64}$/.test(send.allocationId) ||
    accepted?.accepted !== true || accepted.acceptedObjectKind !== 'job' || typeof job !== 'string' ||
    !/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(job) || accepted.objectId !== job ||
    accepted.parentTaskId !== original.taskId || accepted.sourceOperationId !== original.operationId || accepted.targetOperationId !== original.operationId ||
    accepted.executorDeviceId !== original.deviceId || accepted.instanceId !== original.instanceId || !digest(wireDigest) ||
    !Number.isSafeInteger(accepted.acceptedAt) || accepted.acceptedAt <= 0 || result.acceptedAt !== accepted.acceptedAt ||
    result.protocolVersion !== '1.0' || result.messageType !== 'OperationReceipt' || result.accepted !== true ||
    result.status !== 'succeeded' || result.effectState !== 'reported' || result.operationId !== original.operationId ||
    result.executorDeviceId !== original.deviceId || result.instanceId !== original.instanceId || result.argumentsDigest !== wireDigest || result.jobId !== job ||
    result.localTaskId != null || result.runId != null || result.executorEpoch != null || result.cancellation != null ||
    audience?.kind !== 'operation' || audience.objectId !== original.operationId || audience.rootTaskId !== original.taskId ||
    audience.localTaskId !== send.allocationId || audience.requestRunId !== original.runId || audience.requestExecutorEpoch !== original.epoch ||
    audience.targetDeviceId !== original.deviceId || audience.instanceId !== original.instanceId || audience.argumentsDigest !== wireDigest ||
    !deviceId(audience.callerDeviceId) || audience.originDeviceId !== audience.callerDeviceId || audience.sourceShareId != null ||
    resource?.kind !== 'workspace' || !nativeId(resource.environmentId) || !digest(resource.binding)) return;

  const proof = [original, requestDigest, job, wireDigest, send.wireSha256, send.allocationId,
    accepted.acceptedAt, resource.environmentId, resource.binding];
  // A relationship between two receipts also needs their actual same original grant,
  // not just the same device name or two reported relative paths.
  const workspace = nativeId(audience.grantId) && Number.isSafeInteger(audience.authorizationEpoch) && audience.authorizationEpoch > 0
    ? JSON.stringify([requestDigest, audience.callerDeviceId, audience.grantId, audience.authorizationEpoch,
      resource.environmentId, resource.binding, send.allocationId]) : undefined;
  return { proof, requestDigest, data: record(result.payload), args, workspace, workspaceId: resource.environmentId, job, acceptedAt: accepted.acceptedAt };
}
type OriginalRead = NonNullable<ReturnType<typeof receivedOriginalWindowsReadAt>>;

export function receivedOriginalWindowsTextAt(task: TaskHeader, op: TaskOperation, original: Original, page?: AuditPage): Received | undefined {
  const held = receivedOriginalWindowsReadAt(task, op, original, page);
  if (held) return receivedWindowsText(held);
}

function receivedWindowsText(held: OriginalRead): Received | undefined {
  // Exact complete initial Windows WorkspaceFiles payload, already held in the
  // current original audit page. This is never the remote history excerpt.
  const { proof, requestDigest, data, args } = held, path = args?.path;
  if (!data || Object.keys(data).length !== 5 || typeof data.revisionRetained !== 'boolean' || typeof path !== 'string' ||
    !path || path.length > 2048 || data.path !== path || typeof data.content !== 'string' || data.content.length > RECEIVED_TEXT_LIMIT ||
    !Number.isSafeInteger(data.bytes) || data.bytes < 0 || data.bytes > RECEIVED_TEXT_LIMIT || typeof data.version !== 'string' ||
    !/^sha256:[a-f0-9]{64}$/.test(data.version) || new TextEncoder().encode(data.content).length !== data.bytes) return;
  // Native publishes fresh JSON objects even when the original receipt has not changed.
  const identity = JSON.stringify([...proof, path, data.version, data.bytes]);
  return { identity, requestDigest, content: data.content, bytes: data.bytes, version: data.version };
}

function receivedOriginalWindowsDirectoryAt(task: TaskHeader, op: TaskOperation, page: AuditPage,
  file?: { read: OriginalRead; operation: TaskOperation; bytes: number }, rootPicker = false): ReceivedDirectory | undefined {
  const original = originalWindowsReadAt(task, op, 'workspace.list');
  const held = original && receivedOriginalWindowsReadAt(task, op, original, page);
  if (!held?.data || !held.args || Object.keys(held.args).some(key => key !== 'path')) return;
  const { data, args, proof } = held;
  const path: unknown = Object.hasOwn(args, 'path') ? args.path : '';
  // The initial Windows workspace.list has its own checked directory identity.
  // A locally generated summary, later query wrapper, or POSIX list is not it.
  if (typeof path !== 'string' || path.length > 2048 || data.path !== path || Object.keys(data).length !== 4 ||
    rootPicker && (path !== '' || args.path !== '' || !held.workspace) ||
    !Array.isArray(data.directoryIdentity) || data.directoryIdentity.length !== 3 ||
    !data.directoryIdentity.every((part: unknown) => typeof part === 'number' && Number.isInteger(part) && part >= 0 && part <= 0xffffffff) ||
    typeof data.truncated !== 'boolean' || !Array.isArray(data.files) || data.files.length > 200) return;
  const prefix = path ? path + '/' : '', names = new Set<string>(), entries: DirectoryEntry[] = [], choices: ListedFile[] = [];
  const linkedPath = file && held.workspace && file.read.workspace === held.workspace && file.read.job !== held.job &&
    held.acceptedAt <= file.read.acceptedAt && typeof op.position === 'number' && typeof file.operation.position === 'number' &&
    op.position < file.operation.position ? file.read.args?.path : undefined;
  let linkedFileIndex: number | undefined;
  for (const [entryIndex, raw] of data.files.entries()) {
    const entry = record(raw), name: unknown = entry?.name, kind: unknown = entry?.kind;
    if (!entry || Object.keys(entry).length !== 5 || typeof name !== 'string' || !name || [...name].length > 255 ||
      name === '.' || name === '..' || /[/\\]/.test(name) || names.has(name) || entry.path !== prefix + name ||
      !Number.isSafeInteger(entry.bytes) || entry.bytes < 0 || !Number.isSafeInteger(entry.modifiedAt) ||
      rootPicker && entry.modifiedAt < 0 || typeof kind !== 'string' || !Object.hasOwn(directoryKinds, kind)) return;
    names.add(name);
    // Preserve the raw index, even if earlier directory entries were not selectable.
    if (rootPicker && choices.length < 8 && kind === 'file' && entry.bytes <= RECEIVED_TEXT_LIMIT && validWindowsWorkspaceFilePath(name))
      choices.push({ entryIndex, path: name });
    if (entries.length < 8) {
      if (file && typeof linkedPath === 'string' && entry.path === linkedPath && kind === 'file' && entry.bytes === file.bytes)
        linkedFileIndex = entries.length;
      entries.push({ name: name.replace(/\p{C}/gu, '�'), kind: kind as DirectoryEntry['kind'] });
    }
  }
  const picker = rootPicker ? { workspaceId: held.workspaceId, choices,
    linkedPath: choices.find(row => row.path === linkedPath)?.path } : undefined;
  return { identity: JSON.stringify([...proof, op.position, data.directoryIdentity, data.truncated, data.files.length, linkedFileIndex, ...(picker ? [picker] : [])]),
    returned: data.files.length, truncated: data.truncated, entries, linkedFileIndex, picker };
}

const displayText = (content: string) => content.replace(/\r\n?/g, '\n').replace(/\p{C}/gu, ch => ch === '\n' || ch === '\t' ? ch : '�');
const sameReceived = (a: Received, b: Received) => a.identity === b.identity && a.content === b.content;

/** Several reads in one original grant/allocation can be distinct authenticated Windows jobs. */
function receivedWindowsFileChoices(task: TaskHeader, reads: TaskOperation[], page: AuditPage): ReceivedFile[] | undefined {
  const files: ReceivedFile[] = [], jobs = new Set<string>();
  let workspace: string | undefined;
  for (const operation of reads) {
    const original = originalWindowsTextAt(task, operation);
    const read = original && receivedOriginalWindowsReadAt(task, operation, original, page);
    const received = read && receivedWindowsText(read);
    const path: unknown = read?.args?.path, position = operation.position;
    if (!original || !read?.workspace || !received || typeof path !== 'string' ||
      typeof position !== 'number' || !Number.isSafeInteger(position) || position < 1 || jobs.has(read.job) ||
      (workspace !== undefined && read.workspace !== workspace)) return;
    workspace = read.workspace;
    jobs.add(read.job);
    files.push({ original, received, path, position });
  }
  return files.sort((a, b) => a.position - b.position);
}

/** Presentation only, after the complete original byte/SHA check. Never split a Unicode scalar. */
function receivedTextPageEnds(text: string): number[] {
  const ends: number[] = [];
  let end = 0, bytes = 0;
  for (const point of text) {
    const code = point.codePointAt(0)!;
    const size = code <= 0x7f ? 1 : code <= 0x7ff ? 2 : code <= 0xffff ? 3 : 4;
    if (bytes + size > RECEIVED_TEXT_PAGE_BYTES) { ends.push(end); bytes = 0; }
    end += point.length; bytes += size;
  }
  ends.push(end);
  return ends;
}

function originalWindowsOverviewTask(snapshot: PocketSnapshot, taskId: string) {
  const view = record(snapshot.view), tasks: unknown = snapshot.state.tasks;
  const resumeRevision: unknown = view?.resumeRevision;
  if (view?.section !== null || view.taskId !== taskId || typeof resumeRevision !== 'number' || !Number.isSafeInteger(resumeRevision) || resumeRevision < 0 ||
    !snapshot.capabilities.commands.includes('history.read') || !Array.isArray(tasks)) return;
  const rows: TaskHeader[] = tasks.filter(row => record(row)?.id === taskId);
  if (rows.length !== 1) return;
  const task = rows[0], raw = task.raw;
  if (!raw?.ownerDeviceWork?.directWorkspace || raw.status !== task.status || !nativeId(raw.runId)) return;
  return { task, raw, resumeRevision };
}

function originalWindowsOverviewAudit(task: TaskHeader) {
  let page: AuditPage;
  try { page = taskAuditPage(task.raw?.historyOperationPage, task.id, 'operations'); } catch { return; }
  // Bounded/partial pages still belong in the existing per-operation view.
  if (page.completeHistory !== true || page.readLimited !== false || page.hasMore !== false || page.entries[0]?.position !== page.upperPosition) return;
  return { page, calls: getTaskOperations({ id: task.id, raw: { historyOperationPage: page } }).filter(op => op.tool === 'device_invoke_tool') };
}

function ownerWindowsPickerMarker(task: TaskHeader) {
  const raw = task.raw, pin = record(raw?.ownerDeviceWork?.directWorkspace), marker = record(raw?.ownerWindowsWorkspacePicker);
  if (!raw || !pin || raw.ownerWindowsWorkspaceRead !== true || raw.coordinatorEpoch !== 1 || !nativeId(raw.runId) ||
    !deviceId(pin.deviceId) || !nativeId(pin.instanceId) || !digest(pin.certificateFingerprint) ||
    !currentReceivedWorkDigest(raw, { taskId: task.id, runId: raw.runId, epoch: 1, deviceId: pin.deviceId,
      instanceId: pin.instanceId, fingerprint: pin.certificateFingerprint }) ||
    marker?.format !== 'pocketworld.owner-windows-workspace-picker.v1' ||
    !Number.isSafeInteger(marker.expiresAt) || marker.expiresAt <= 0 ||
    !['listing', 'waiting_for_file', 'reading', 'ended'].includes(marker.phase)) return;
  return { runId: raw.runId, phase: marker.phase as 'listing' | 'waiting_for_file' | 'reading' | 'ended', expiresAt: marker.expiresAt as number,
    directoryOperationId: nativeId(marker.directoryOperationId) ? marker.directoryOperationId : undefined,
    liveChoice: marker.canChooseListedFile === true };
}

function originalPickerDirectory(task: TaskHeader, marker: NonNullable<ReturnType<typeof ownerWindowsPickerMarker>>,
  audit: NonNullable<ReturnType<typeof originalWindowsOverviewAudit>>) {
  if (!marker.directoryOperationId) return;
  const operations = audit.calls.filter(op => op.runId === marker.runId);
  const rows = operations.filter(op => op.operationId === marker.directoryOperationId);
  if (rows.length !== 1) return;
  const operation = rows[0], folder = receivedOriginalWindowsDirectoryAt(task, operation, audit.page, undefined, true);
  if (folder?.picker) return { operation, operations, folder, choices: folder.picker.choices, workspaceId: folder.picker.workspaceId };
}

function originalPickerText(task: TaskHeader, directory: NonNullable<ReturnType<typeof originalPickerDirectory>>, page: AuditPage) {
  if (task.status !== 'completed' || directory.operations.length !== 2) return;
  const operation = directory.operations.find(row => row !== directory.operation);
  const original = operation && originalWindowsTextAt(task, operation);
  const read = operation && original && receivedOriginalWindowsReadAt(task, operation, original, page);
  const received = read && receivedWindowsText(read);
  const linked = operation && read && received && receivedOriginalWindowsDirectoryAt(task, directory.operation, page,
    { read, operation, bytes: received.bytes }, true);
  // This is the later verified read of the listed path, not the old listing's bytes.
  if (original && received && linked?.picker?.linkedPath) return { original, received, exactPath: linked.picker.linkedPath };
}

/** Local projection only, from the exact Work's authenticated root-list receipt. No history command. */
export function originalWindowsWorkspacePickerAt(snapshot: PocketSnapshot, taskId: string) {
  const shown = originalWindowsOverviewTask(snapshot, taskId);
  const marker = shown && ownerWindowsPickerMarker(shown.task);
  if (!shown || !marker) return;
  const audit = marker.directoryOperationId && originalWindowsOverviewAudit(shown.task);
  const data = audit ? originalPickerDirectory(shown.task, marker, audit) : undefined;
  const directory = data && marker.directoryOperationId ? { operationId: marker.directoryOperationId,
    received: data.folder, choices: data.choices, workspaceId: data.workspaceId } : undefined;
  const fileReceived = marker.phase === 'ended' && !!(audit && data && originalPickerText(shown.task, data, audit.page));
  return { ...marker, directory, fileReceived, canChoose: !!directory && data?.operations.length === 1 && marker.phase === 'waiting_for_file' &&
    marker.liveChoice && shown.raw.status === 'running' && shown.raw.cancelRequested === false && shown.raw.hasUnresolvedOperations === false &&
    Date.now() < marker.expiresAt };
}
type WindowsOverviewRead = { resumeRevision: number } & (
  { kind: 'files'; files: ReceivedFile[] } |
  { kind: 'directory'; received: ReceivedDirectory; linked?: {original: Original; received: Received}; managedPicker?: true } |
  { kind: 'text'; original: Original; received: Received; exactPath?: string }
);

function overviewWindowsRead(snapshot: PocketSnapshot, taskId: string): WindowsOverviewRead | undefined {
  const shown = originalWindowsOverviewTask(snapshot, taskId), audit = shown && originalWindowsOverviewAudit(shown.task);
  if (!shown || !audit) return;
  const { task, raw, resumeRevision } = shown, { page, calls } = audit;
  const marker = ownerWindowsPickerMarker(task);
  if (marker) {
    const directory = originalPickerDirectory(task, marker, audit);
    if (!directory) return;
    const text = marker.phase === 'ended' ? originalPickerText(task, directory, page) : undefined;
    if (text) return { kind: 'text', ...text, resumeRevision };
    return { kind: 'directory' as const, received: directory.folder, resumeRevision, managedPicker: true };
  }
  if (calls.some(op => {
    const name = record(op.arguments)?.toolName;
    return !nativeId(op.runId) || op.runId === raw.runId && (!op.bodyAvailable || typeof name !== 'string' || !name);
  })) return;
  const current = calls.filter(op => op.runId === raw.runId);
  const reads = current.filter(op => record(op.arguments)?.toolName === 'workspace.read');
  if (reads.length > 1) {
    const files = receivedWindowsFileChoices(task, reads, page);
    if (files) return { kind: 'files' as const, files, resumeRevision };
  }
  if (reads.length === 1) {
    const operation = reads[0], original = originalWindowsTextAt(task, operation);
    const read = original && receivedOriginalWindowsReadAt(task, operation, original, page);
    const received = read && receivedWindowsText(read);
    if (original && read && received) {
      // Only two original invocations, no guesses among files, windows or later runs.
      const other = current.length === 2 ? current.find(op => op !== operation) : undefined;
      const folder = other && receivedOriginalWindowsDirectoryAt(task, other, page, { read, operation, bytes: received.bytes });
      if (folder?.linkedFileIndex !== undefined)
        return { kind: 'directory' as const, received: folder, linked: { original, received }, resumeRevision };
      const path: unknown = read.args?.path;
      // The dedicated completed Work accepted only one literal workspace path.
      // The existing receipt parser already matched this operation's path to the returned file.
      const exactPath = raw.ownerWindowsWorkspaceRead === true && raw.status === 'completed' && original.epoch === 1 && current.length === 1 &&
        typeof path === 'string' && !/[:\\\p{Cc}\p{Cf}\p{Cs}\p{Zl}\p{Zp}]/u.test(path) &&
        path.split('/').every(part => part !== '' && part !== '.' && part !== '..') ? path : undefined;
      return { kind: 'text' as const, original, received, resumeRevision, exactPath };
    }
  }
  // Do not choose a directory from several results or put a second action beside the text result.
  if (current.length !== 1 || reads.length !== 0) return;
  const received = receivedOriginalWindowsDirectoryAt(task, current[0], page);
  if (received) return { kind: 'directory' as const, received, resumeRevision };
}

/** Mount only in the active Work overview. Explicitly disclose an already-received
 * result; never open Process, ask the Windows peer, or start a new audit read. */
export function OriginalWindowsWorkspaceOverview({ taskId, managePicker = false }: { taskId: string; managePicker?: boolean }) {
  const snapshot = usePocketSnapshot();
  const documentEpoch = useSyncExternalStore(bridge.subscribeDocumentEpoch, bridge.getDocumentEpoch, bridge.getDocumentEpoch);
  const source = documentEpoch > 0 ? overviewWindowsRead(snapshot, taskId) : undefined;
  if (!source || source.kind === 'directory' && source.managedPicker && managePicker) return null;
  const { resumeRevision } = source, sourceRevision = String(snapshot.state.traceSourceRevision ?? 0);
  if (source.kind === 'files') {
    const key = JSON.stringify([documentEpoch, resumeRevision, sourceRevision, source.files.map(file => [file.position, file.received.identity])]);
    return <OriginalWindowsFileChoices key={key} files={source.files} documentEpoch={documentEpoch} sourceRevision={sourceRevision} checkOverview={live => {
      if (live === snapshot) return true;
      const actual = overviewWindowsRead(live, taskId);
      return actual?.kind === 'files' && actual.resumeRevision === resumeRevision && actual.files.length === source.files.length &&
        actual.files.every((file, index) => file.position === source.files[index].position && file.path === source.files[index].path &&
          sameReceived(file.received, source.files[index].received));
    }}/>;
  }
  const key = JSON.stringify([documentEpoch, resumeRevision, sourceRevision, source.received.identity,
    source.kind === 'directory' ? source.linked?.received.identity : undefined]);
  if (source.kind === 'directory') {
    const linked = source.linked;
    const sameDirectory = (live: PocketSnapshot) => {
      if (live === snapshot) return true;
      const actual = overviewWindowsRead(live, taskId);
      return actual?.kind === 'directory' && actual.resumeRevision === resumeRevision &&
        String(live.state.traceSourceRevision ?? 0) === sourceRevision &&
        actual.received.identity === source.received.identity && actual.received.entries.length === source.received.entries.length &&
        actual.received.entries.every((row, index) => row.name === source.received.entries[index]?.name && row.kind === source.received.entries[index]?.kind) &&
        (linked ? !!actual.linked && sameReceived(actual.linked.received, linked.received) : !actual.linked);
    };
    return <OriginalWindowsDirectory key={key} received={source.received}
      current={() => !document.hidden && bridge.getDocumentEpoch() === documentEpoch && sameDirectory(bridge.getState())}
      linked={linked && <ReceivedOriginalWindowsText original={linked.original} received={linked.received}
        documentEpoch={documentEpoch} sourceRevision={sourceRevision} fallback={null} checkOverview={sameDirectory}/>}/>;
  }
  return <ReceivedOriginalWindowsText key={key}
    original={source.original} received={source.received} documentEpoch={documentEpoch} sourceRevision={sourceRevision} fallback={null} singleFilePath={source.exactPath} checkOverview={live => {
      if (live === snapshot) return true;
      const current = overviewWindowsRead(live, taskId);
      return current?.kind === 'text' && current.resumeRevision === resumeRevision && current.exactPath === source.exactPath &&
        sameReceived(current.received, source.received);
    }}/>;
}

function OriginalWindowsFileChoices({ files, documentEpoch, sourceRevision, checkOverview }: {
  files: ReceivedFile[]; documentEpoch: number; sourceRevision: string; checkOverview: (live: PocketSnapshot) => boolean;
}) {
  const [selected, setSelected] = useState('');
  const current = useRef('');
  return <section className="pw-operation-entry" aria-label="这次从电脑收到的文件">
    <h4>这次从电脑收到的文件</h4>
    <p className="pw-muted">只看本机当时收到的文本，不会重新联系电脑。</p>
    {files.map(({ original, received, path }, index) => {
      const label = <><span>{index + 1}. </span><bdi style={{overflowWrap:'anywhere'}}>{path.replace(/\p{C}/gu, '�')}</bdi></>;
      const id = original.operationId;
      return <ReceivedOriginalWindowsText key={id} original={original} received={received} documentEpoch={documentEpoch}
        sourceRevision={sourceRevision} fallback={null} checkOverview={checkOverview} choice={{
          label, selected: selected === id, isCurrent: () => current.current === id,
          choose: () => { current.current = id; setSelected(id); },
        }}/>;
    })}
  </section>;
}

function OriginalWindowsDirectory({ received, current, linked }: { received: ReceivedDirectory; current: () => boolean; linked?: ReactNode }) {
  const [open, setOpen] = useState(false);
  useEffect(() => {
    const retire = () => setOpen(false);
    document.addEventListener('visibilitychange', retire);
    window.addEventListener('pagehide', retire);
    return () => {
      document.removeEventListener('visibilitychange', retire);
      window.removeEventListener('pagehide', retire);
    };
  }, []);
  if (!current()) return null;
  return <section className="pw-operation-entry" aria-label="本机收到的原电脑目录">
    <Accordion variant="divided" className="pw-compact-disclosures" expandedKeys={open ? ['original-directory'] : []}
      onExpandedChange={keys => setOpen(keys.has('original-directory') && current())}>
      <AccordionItem id="original-directory" headingLevel={4}
        title={linked ? '查看当时的目录和已读文件' : '查看当时列出的目录'}>
        {open && <>
          <p className="pw-muted">本机留存：当时返回 {received.returned} 项，不是当前目录。</p>
          {received.entries.length > 0 && <ul>{received.entries.map((row, index) =>
            <li key={index}><bdi>{row.name}</bdi> · {directoryKinds[row.kind]}
              {index === received.linkedFileIndex && linked}</li>)}</ul>}
          {received.entries.length < received.returned && <p>这里只显示留存的前 8 项。</p>}
          {received.truncated && <p>电脑当时只返回了部分目录项。</p>}
        </>}
      </AccordionItem>
    </Accordion>
  </section>;
}

export function ReceivedOriginalWindowsText({ original, received, documentEpoch, sourceRevision, fallback, checkOverview, choice, singleFilePath }: {
  original: Original; received: Received; documentEpoch: number; sourceRevision: string; fallback: ReactNode;
  singleFilePath?: string;
  checkOverview?: (live: PocketSnapshot) => boolean;
  choice?: { label: ReactNode; selected: boolean; isCurrent: () => boolean; choose: () => void };
}) {
  const [check, setCheck] = useState<{ generation: number; verified: boolean } | null>(null);
  const [busy, setBusy] = useState(false), [page, setPage] = useState(0);
  const latest = useRef({ source: received, generation: 0 }), alive = useRef(true), sequence = useRef(0), reading = useRef(false);
  // Keep no copy of the verified text: the display stamp follows only this loaded row.
  const generation = latest.current.generation + (sameReceived(latest.current.source, received) ? 0 : 1);
  latest.current = { source: received, generation };
  const clear = () => { sequence.current++; reading.current = false; setCheck(null); setBusy(false); setPage(0); };
  useEffect(() => {
    alive.current = true;
    const visibility = () => { if (document.hidden) clear(); };
    document.addEventListener('visibilitychange', visibility);
    return () => { alive.current = false; sequence.current++; document.removeEventListener('visibilitychange', visibility); };
  }, []);
  const current = () => {
    const live = bridge.getState(), view = record(live.view);
    const raw = live.state.tasks?.find((row: {id: string}) => row.id === original.taskId)?.raw;
    return alive.current && !document.hidden && bridge.getDocumentEpoch() === documentEpoch &&
      String(live.state.traceSourceRevision ?? 0) === sourceRevision && live.capabilities.commands.includes('history.read') &&
      !view?.section && view?.taskId === original.taskId && currentReceivedWorkDigest(raw, original) === received.requestDigest &&
      (!checkOverview || checkOverview(live));
  };
  const read = async () => {
    if (reading.current || !current()) return;
    choice?.choose();
    reading.current = true; const attempt = ++sequence.current;
    setBusy(true); setCheck(null); setPage(0);
    const eligible = () => sequence.current === attempt && latest.current.generation === generation && current() && (!choice || choice.isCurrent());
    try {
      const bytes = new TextEncoder().encode(received.content);
      const hash = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
      const version = 'sha256:' + Array.from(hash, b => b.toString(16).padStart(2, '0')).join('');
      if (eligible()) setCheck({ generation, verified: bytes.length === received.bytes && bytes.length <= RECEIVED_TEXT_LIMIT &&
        version === received.version && new TextEncoder().encode(displayText(received.content)).length <= RECEIVED_TEXT_LIMIT });
    } catch {
      if (eligible()) setCheck({ generation, verified: false });
    } finally {
      if (sequence.current === attempt && alive.current) { reading.current = false; setBusy(false); }
    }
  };
  const inWindow = current();
  if (checkOverview && !inWindow) return null;
  const checked = inWindow && (!choice || choice.selected && choice.isCurrent()) && check?.generation === generation ? check : null;
  if (checked?.verified === false) return <><p role="status">本机收到的原文本无法核对或超过显示范围，未显示正文。</p>{fallback}</>;
  // Derive the displayed pages only while the original already-loaded receipt is
  // still current. No extra body cache, history read, peer call or file operation.
  const text = checked ? displayText(received.content) : '';
  const ends = receivedTextPageEnds(text), pageCount = ends.length, index = Math.min(page, pageCount - 1);
  const stamp = sequence.current;
  const go = (step: -1 | 1) => {
    if (checked && stamp === sequence.current && latest.current.generation === generation && current() && (!choice || choice.isCurrent()))
      setPage(value => Math.max(0, Math.min(pageCount - 1, value + step)));
  };
  const filePath = singleFilePath ? <bdi style={{ overflowWrap: 'anywhere' }}>{singleFilePath}</bdi> : null;
  return <section className="pw-operation-entry" aria-label="本机收到的原电脑文本">
    {checked ? <>
      <h4>{choice?.label ?? (filePath ? <>当时读到的文本 · {filePath}</> : '当时读到的文本')}</h4>
      <p className="pw-muted">本机当时已收到完整文本 · 原文件 {received.bytes} 字节。不重新读取电脑。</p>
      {received.bytes === 0 ? <p>原回执已核对：当时文件为 0 字节。</p> :
        <pre className="pw-source-copy" dir="auto" style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{text.slice(index > 0 ? ends[index - 1] : 0, ends[index])}</pre>}
      {pageCount > 1 && <nav className="pw-action-row" aria-label="本机原文本翻页">
        <Button variant="ghost" disabled={index === 0} onClick={() => go(-1)}>上一段</Button>
        <span role="status">第 {index + 1} / {pageCount} 段</span>
        <Button variant="ghost" disabled={index === pageCount - 1} onClick={() => go(1)}>下一段</Button>
      </nav>}
      <Button variant="ghost" onClick={clear}>关闭原文本</Button>
    </> : <Button variant="secondary" disabled={busy || !inWindow} onClick={() => void read()}>
      {busy ? '正在核对本机原文本…' : choice ? <>查看 {choice.label}</> : filePath ? <>查看原文本 · {filePath}</> : checkOverview ? '查看这次收到的原文本' : '查看本机收到的原文本'}
    </Button>}
  </section>;
}

function snapshotOf(value: unknown, original: Original): Snapshot {
  const item = record(value);
  if (item?.format !== 'pocketworld.original-windows-text.v1' || item.readOnly !== true || item.taskId !== original.taskId
    || item.runId !== original.runId || item.epoch !== original.epoch || item.operationId !== original.operationId
    || item.deviceId !== original.deviceId || item.instanceId !== original.instanceId || typeof item.text !== 'string'
    || !Number.isSafeInteger(item.bytes) || item.bytes < 0 || item.bytes > 1024 || new TextEncoder().encode(item.text).length > 1024
    || (item.bytes === 0) !== (item.text.length === 0) || !Number.isSafeInteger(item.recordedAt) || item.recordedAt <= 0
    || typeof item.version !== 'string' || !/^sha256:[a-f0-9]{64}$/.test(item.version))
    throw new Error('原文本回执与这条工作记录无法核对。');
  return { text: item.text, bytes: item.bytes, recordedAt: item.recordedAt };
}

export function OriginalWindowsTextRead({ original, documentEpoch, sourceRevision }: { original: Original; documentEpoch: number; sourceRevision: string }) {
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const [busy, setBusy] = useState(false), [error, setError] = useState('');
  const alive = useRef(true), sequence = useRef(0), reading = useRef(false);
  const clear = () => { sequence.current++; reading.current = false; setBusy(false); setSnapshot(null); setError(''); };
  useEffect(() => {
    alive.current = true;
    const visibility = () => { if (document.hidden) clear(); };
    document.addEventListener('visibilitychange', visibility);
    return () => { alive.current = false; sequence.current++; document.removeEventListener('visibilitychange', visibility); };
  }, []);
  const current = () => {
    const live = bridge.getState(), view = record(live.view);
    const raw = live.state.tasks?.find((row: {id: string}) => row.id === original.taskId)?.raw, pin = raw?.ownerDeviceWork?.directWorkspace;
    return alive.current && !document.hidden && bridge.getDocumentEpoch() === documentEpoch
      && String(live.state.traceSourceRevision ?? 0) === sourceRevision && !view?.section && view?.taskId === original.taskId
      && raw?.runId === original.runId && raw?.coordinatorEpoch === original.epoch && raw?.contextAvailable !== false
      && raw?.contextWithdrawn !== true && raw?.sourceCurrentReadRequired !== true && raw?.taskProjection !== 'source_unavailable'
      && pin?.deviceId === original.deviceId && pin?.instanceId === original.instanceId && pin?.certificateFingerprint === original.fingerprint;
  };
  const read = async () => {
    if (reading.current || !current()) return;
    reading.current = true; const attempt = ++sequence.current;
    setBusy(true); setSnapshot(null); setError('');
    const eligible = () => sequence.current === attempt && current();
    try {
      const {taskId, runId, epoch, operationId} = original;
      const reply = await bridge.command('devices.queryHistory', { originalWindowsText: true, taskId, runId, epoch, operationId });
      if (eligible()) setSnapshot(snapshotOf(reply, original));
    } catch (failure) {
      if (eligible()) setError(failure instanceof Error ? failure.message : '没有取得可核对的原文本。');
    } finally {
      if (sequence.current === attempt && alive.current) { reading.current = false; setBusy(false); }
    }
  };
  const shown = current() ? snapshot : null;
  return <section className="pw-operation-entry" aria-label="原电脑保存的文本">
    {shown ? <>
      <h4>当时读到的文本</h4>
      <p className="pw-muted">原文件 {shown.bytes} 字节 · 当时保存于 {new Date(shown.recordedAt).toLocaleString('zh-CN')}。不是当前文件。</p>
      {shown.bytes === 0 ? <p>原回执已核对：当时文件为 0 字节。</p> : <pre className="pw-source-copy" dir="auto">{shown.text}</pre>}
      <Button variant="ghost" onClick={clear}>关闭原文本</Button>
    </> : <>
      <Button variant="secondary" disabled={busy} onClick={() => void read()}>{busy ? '正在核对原文本…' : '向原电脑查看当时的文本'}</Button>
      <p className="pw-muted">只查这次留存的回执，不重新读取文件。</p>
      {error && <p role="status">{error}</p>}
    </>}
  </section>;
}
