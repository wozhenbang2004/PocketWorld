type Data = Record<string, unknown>;

const record = (value: unknown): Data | undefined =>
  value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Data : undefined;
const id = (value: unknown): value is string => typeof value === 'string' &&
  /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(value);
const digest = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{64}$/.test(value);
const key = (value: unknown): value is string => typeof value === 'string' && value.length > 0 &&
  value.length <= 160 && !/[\x00-\x1f\x7f]/.test(value);

const scopeFormat = 'pocketworld.owner-python-script.v1', resultFormat = 'pocketworld.owner-python-script-result.v1';
export const savedPythonPath = (value: unknown): value is string => typeof value === 'string' &&
  /^[A-Za-z0-9_][A-Za-z0-9._-]{0,119}\.py$/.test(value);
export const ownerPythonFiles = 'projects.development.files.';
export const ownerPythonRead = 'projects.pythonRun.read';

export type SavedPython = {projectId: string; checkoutId: string; path: string; sha256: string};
type PythonSource = SavedPython & {requestId: string; bytes: number; runtimeIdentity: string; interpreterSha256: string};
export type PythonReview = PythonSource & {reviewHandle: string};
export type OriginalPythonRun = PythonSource & {taskId: string; runId: string; operationId: string; epoch: 1; scriptInputDigest: string; planDigest: string};
export type PythonExit = {exitKind: 'exit_code' | 'signal' | 'no_child'; value: number; timedOut: boolean; cancelRequested: boolean};
export type PythonRead = {state: 'pending' | 'not_started'} | {state: 'settled'; exit: PythonExit; output?: {stdout: string; stderr: string; truncated: boolean}};

const sameSource = (a: SavedPython, b: SavedPython) =>
  a.projectId === b.projectId && a.checkoutId === b.checkoutId && a.path === b.path && a.sha256 === b.sha256;

function source(row: Data | undefined): PythonSource | undefined {
  if (!row || row.format !== scopeFormat || !id(row.requestId) || !key(row.projectId) || !key(row.checkoutId) ||
      !savedPythonPath(row.path) || !digest(row.sha256) || typeof row.bytes !== 'number' || !Number.isSafeInteger(row.bytes) ||
      row.bytes < 0 || row.bytes > 262144 || !key(row.runtimeIdentity) || !digest(row.interpreterSha256)) return undefined;
  const {requestId, projectId, checkoutId, path, sha256, bytes, runtimeIdentity, interpreterSha256} = row;
  return {requestId, projectId, checkoutId, path, sha256, bytes, runtimeIdentity, interpreterSha256};
}

export function pythonReview(value: unknown, saved: SavedPython): PythonReview | undefined {
  const row = record(value), input = source(row);
  if (!row || !input || !sameSource(input, saved) || !id(row.reviewHandle) || row.timeoutMillis !== 60000 ||
      row.originalCheckoutMounted !== false || row.dependenciesInstalledByAction !== false || row.outputFilesReturned !== false ||
      row.scopeEnforced !== false || row.executionAuthorized !== false || row.networkMayBeUsed !== true) return undefined;
  return {...input, reviewHandle: row.reviewHandle};
}

export function originalPythonRun(value: unknown, reviewed?: PythonReview): OriginalPythonRun | undefined {
  const row = record(value), input = source(row);
  if (!row || !input || !id(row.taskId) || !id(row.runId) || !id(row.operationId) || row.epoch !== 1 ||
      !digest(row.scriptInputDigest) || !digest(row.planDigest) || row.modelDispatchAllowed !== false || row.replayAllowed !== false) return undefined;
  if (reviewed && (!sameSource(input, reviewed) || input.requestId !== reviewed.requestId || input.bytes !== reviewed.bytes ||
      input.runtimeIdentity !== reviewed.runtimeIdentity || input.interpreterSha256 !== reviewed.interpreterSha256)) return undefined;
  const {taskId, runId, operationId, epoch, scriptInputDigest, planDigest} = row;
  return {...input, taskId, runId, operationId, epoch, scriptInputDigest, planDigest};
}

export function samePythonRun(a: OriginalPythonRun, b?: OriginalPythonRun) {
  return !!b && a.taskId === b.taskId && a.runId === b.runId && a.operationId === b.operationId &&
    a.epoch === b.epoch && a.requestId === b.requestId && sameSource(a, b) &&
    a.scriptInputDigest === b.scriptInputDigest && a.planDigest === b.planDigest &&
    a.runtimeIdentity === b.runtimeIdentity && a.interpreterSha256 === b.interpreterSha256;
}

function exactRun(row: Data, original: OriginalPythonRun) {
  return row.taskId === original.taskId && row.runId === original.runId && row.operationId === original.operationId &&
    row.epoch === original.epoch && row.projectId === original.projectId && row.checkoutId === original.checkoutId &&
    row.path === original.path && row.scriptSha256 === original.sha256 && row.scriptInputDigest === original.scriptInputDigest;
}

export function originalPythonExit(value: unknown, original: OriginalPythonRun): PythonExit | undefined {
  const row = record(value);
  if (!row || row.format !== resultFormat || !exactRun(row, original) || row.resourcesReleased !== true ||
      row.outputFilesReturned !== false || row.replayAllowed !== false || typeof row.timedOut !== 'boolean' ||
      typeof row.cancelRequested !== 'boolean' || typeof row.value !== 'number' || !Number.isSafeInteger(row.value) ||
      !key(row.nativeStatus) || (row.exitKind !== 'exit_code' && row.exitKind !== 'signal' && row.exitKind !== 'no_child')) return undefined;
  const {exitKind, value: number, timedOut, cancelRequested} = row;
  return {exitKind, value: number, timedOut, cancelRequested};
}

export function originalPythonNotStarted(value: unknown, original: OriginalPythonRun): boolean {
  const row = record(value);
  return !!row && row.format === 'pocketworld.owner-python-script-not-started.v1' && exactRun(row, original) &&
    row.planDigest === original.planDigest && row.reason === 'stage_preparation_failed' &&
    row.providerInvoked === false && row.replayAllowed === false;
}

export function pythonReadResult(value: unknown, original: OriginalPythonRun): PythonRead | undefined {
  const row = record(value);
  if (!row || !exactRun(row, original)) return undefined;
  if (row.state === 'pending') return {state: 'pending'};
  if (row.state === 'not_started') return row.status === 'failed' &&
    originalPythonNotStarted(row.notStarted, original) ? {state: 'not_started'} : undefined;
  if (row.state !== 'settled') return undefined;
  const exit = originalPythonExit(row.exit, original);
  if (!exit) return undefined;
  if (row.outputState === 'unavailable') return {state: 'settled', exit};
  if (row.outputState !== 'available' || typeof row.stdout !== 'string' || typeof row.stderr !== 'string' ||
      typeof row.stdoutBytesShown !== 'number' || !Number.isSafeInteger(row.stdoutBytesShown) || row.stdoutBytesShown < 0 ||
      typeof row.stderrBytesShown !== 'number' || !Number.isSafeInteger(row.stderrBytesShown) || row.stderrBytesShown < 0 ||
      row.stdoutBytesShown + row.stderrBytesShown > 65536 || row.stdout.length > row.stdoutBytesShown ||
      row.stderr.length > row.stderrBytesShown || typeof row.truncated !== 'boolean') return undefined;
  return {state: 'settled', exit, output: {stdout: row.stdout, stderr: row.stderr, truncated: row.truncated}};
}
