/** Read-only Web adaptation of BackgroundConnectionState.kt. The Android
 * display snapshot and original Controller remain the connection authority. */
type HostObject = Record<string, any>;
const row = (value: any): HostObject => value && typeof value === 'object' && !Array.isArray(value) ? value : {};

export function backgroundConnection(display: HostObject, activeTaskId: unknown, pending: string[] = []) {
  const adb = row(display.adb);
  const busy = Boolean(display.bootstrapBusy || adb.busy || adb.processTerminationPending || pending.some(key => [
    'display.adb.pair', 'display.adb.connect', 'display.adb.cancel', 'display.adb.forget',
    'display.shizuku.start', 'display.shizuku.stop', 'display.setup', 'display.release',
  ].includes(key)));
  const taskOwned = Boolean(display.taskOwned || activeTaskId != null);
  const barrier = Boolean(display.sessionActive || display.releasePending || display.recoveryRequired);
  const recovery = Boolean(adb.recoveryRequired);
  const supported = display.supportedAndroid === true;
  const wirelessAvailable = display.adb != null && typeof display.adb === 'object' && !Array.isArray(display.adb);
  const backend = typeof display.authorizationBackend === 'string' ? display.authorizationBackend : '';
  const helperConnected = display.helperConnected === true;
  return {
    loaded: Object.hasOwn(display, 'supportedAndroid'), supported, wirelessAvailable, backend, taskOwned, busy,
    displayBarrier: barrier, recoveryRequired: recovery,
    requiresConnectionCheck: recovery || Boolean(display.recoveryRequired || display.releasePending),
    helperConnected,
    screenEstablished: helperConnected && display.sessionActive === true && display.readyForTask === true && Number(display.displayId) > 0 && typeof display.sessionId === 'string' && display.sessionId.trim() !== '',
    phase: typeof adb.phase === 'string' ? adb.phase : '', identityPresent: adb.identityPresent === true,
    canPair: supported && wirelessAvailable && !taskOwned && !busy && !barrier && !recovery,
    canConnect: supported && wirelessAvailable && !taskOwned && !busy && (!barrier || backend === 'wireless_adb'),
    canChangeBackend: !taskOwned && !busy && !barrier && !recovery,
  };
}
export function wirelessPort(value: string): number | undefined {
  if (!/^\d+$/.test(value)) return undefined;
  const port = Number(value);
  return Number.isInteger(port) && port >= 1 && port <= 65535 ? port : undefined;
}
export function wirelessEndpoints(discovery: HostObject, kind: 'pairing' | 'connect', now = Date.now()): Array<{ id: string; host: string; port: number }> {
  const seen = new Set<string>();
  return (Array.isArray(discovery[kind]) ? discovery[kind] : []).flatMap((value: any) => {
    const item = row(value), host = typeof item.host === 'string' ? item.host : '';
    const port = Number(item.port), resolvedAt = Number(item.resolvedAt), id = host + ':' + port;
    if (item.kind !== kind || !host.trim() || !Number.isInteger(port) || port < 1 || port > 65535 || !Number.isFinite(resolvedAt) || resolvedAt < now - 180_000 || resolvedAt > now + 30_000 || seen.has(id)) return [];
    seen.add(id);
    return [{ id, host, port }];
  });
}

/** Original connection forms use a whitelist and preserve stored credentials
 * when a replacement is empty. Never spread a sanitized snapshot into writes. */
export function connectionProfilePayload(domain: 'remote' | 'ssh', draft: HostObject, entry: HostObject | null): HostObject {
  const string = (key: string) => typeof draft[key] === 'string' ? draft[key] : draft[key] == null ? '' : String(draft[key]);
  const payload: HostObject = { name: string('name').trim() };
  if (typeof entry?.id === 'string' && entry.id) payload.id = entry.id;
  if (entry?.revision != null) payload.expectedRevision = entry.revision;
  if (domain === 'remote') {
    payload.baseUrl = string('baseUrl').trim();
    if (string('token') !== '') payload.token = string('token');
  } else {
    Object.assign(payload, { host: string('host').trim(), port: wirelessPort(string('port')) ?? 0,
      username: string('username').trim(), workspace: string('workspace').trim(), authType: draft.authType === 'password' ? 'password' : 'key' });
    const secret = payload.authType === 'key' ? 'privateKey' : 'password';
    if (string(secret) !== '') payload[secret] = string(secret);
    if (payload.authType === 'key' && string('passphrase') !== '') payload.passphrase = string('passphrase');
  }
  return payload;
}
