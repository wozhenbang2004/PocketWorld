/** Owner desktop viewer. These receipts are UI projections; the native owner remains authoritative. */
export type DesktopRecord = Record<string, any>;
export type DesktopRun = (action: string, args?: DesktopRecord) => Promise<unknown>;
export type DesktopResource = { kind: 'desktop'; sessionId: number; displayId: string; windowId?: number };
export type DesktopScope = { key: string; grantId: string; resourceBinding: DesktopResource; label: string; available: boolean; reason: string; expiresAt: number };
export type DesktopControl = { owner: 'none' | 'human' | 'other_human' | 'agent'; revision: number; lease?: DesktopRecord; needsReconcile?: boolean };
export type DesktopSession = {
  kind: 'desktopSession'; deviceId: string; sessionId: string; resourceBinding: DesktopResource; expiresAt: number;
  width: number; height: number; control: DesktopControl; iceServers: RTCIceServer[]; streamId?: string;
};
export type DesktopFrame = { streamId: string; frameId: string; width: number; height: number; geometryRevision: string; capturedAt: number; expiresAt: number; rtpTimestamp?: number };
export type DesktopInput =
  | { kind: 'click'; x: number; y: number; button: 'left' | 'right'; count: 1 | 2 }
  | { kind: 'scroll'; direction: 'up' | 'down' | 'left' | 'right'; amount: number; by: 'line' }
  | { kind: 'insert_text'; text: string }
  | { kind: 'hotkey'; keys: string[] }
  | { kind: 'press_key'; key: 'enter' | 'escape' | 'tab' };
export type DesktopViewerState = {
  phase: 'idle' | 'opening' | 'connecting' | 'live' | 'disconnected' | 'failed';
  session: DesktopSession | null; frameReady: boolean; frameIssue: 'unavailable' | 'unmatched' | null; controlPending: boolean; inputPending: boolean;
  inputUncertain: boolean; controlExpired: boolean; error: string;
};

const record = (v: unknown): v is DesktopRecord => !!v && typeof v === 'object' && !Array.isArray(v);
const id = (v: unknown): v is string => typeof v === 'string' && v.length > 0 && v.length <= 512 && !/[\x00-\x1f]/.test(v);
const integer = (v: unknown, min = 1, max = Number.MAX_SAFE_INTEGER): v is number => Number.isSafeInteger(v) && (v as number) >= min && (v as number) <= max;
const copy = <T>(v: T): T => JSON.parse(JSON.stringify(v));
const RTP_TIMESTAMP_MAX = 0xffffffff;
const FRAME_MATCH_TIMEOUT_MS = 5000;
type ReceivedFrame = { value: DesktopFrame; received: number };

function resource(v: unknown): v is DesktopResource {
  return record(v) && v.kind === 'desktop' && integer(v.sessionId) && id(v.displayId)
    && (v.windowId === undefined || integer(v.windowId))
    && Object.keys(v).every(key => ['kind', 'sessionId', 'displayId', 'windowId'].includes(key));
}
function sameResource(a: DesktopResource, b: DesktopResource): boolean {
  return a.kind === b.kind && a.sessionId === b.sessionId && a.displayId === b.displayId && a.windowId === b.windowId;
}

/** Old grants stay visible with a renewal explanation; no client-side widening of a grant. */
export function desktopScopes(peer: DesktopRecord, now = Date.now()): DesktopScope[] {
  const result: DesktopScope[] = [];
  const grants = Array.isArray(peer.receivedGrantScopes) ? peer.receivedGrantScopes : [];
  for (const grant of grants) {
    if (!record(grant) || !id(grant.grantId) || !Array.isArray(grant.resources)) continue;
    for (const binding of grant.resources) {
      if (!resource(binding)) continue;
      const authorized = Array.isArray(grant.capabilities) && grant.capabilities.includes('desktop.viewer');
      const current = integer(grant.expiresAt) && grant.expiresAt > now;
      const trusted = peer.revoked !== true;
      const label = binding.windowId ? '已授权窗口' : '已授权桌面';
      result.push({ key: `${grant.grantId}:${binding.sessionId}:${binding.displayId}:${binding.windowId || ''}`, grantId: grant.grantId,
        resourceBinding: copy(binding), label, available: authorized && current && trusted,
        reason: !trusted ? '设备信任已撤销，请重新配对后授权' : !authorized ? '请在电脑上更新桌面观看授权' : !current ? '桌面授权已到期，请在电脑上重新授权' : '',
        expiresAt: integer(grant.expiresAt) ? grant.expiresAt : 0 });
    }
  }
  return result;
}

function iceServers(value: unknown): RTCIceServer[] {
  if (value === undefined) return [];
  if (!Array.isArray(value) || value.length > 8) throw new Error('电脑返回的连接配置无效。');
  return value.map(server => {
    if (!record(server)) throw new Error('电脑返回的连接配置无效。');
    const urls = typeof server.urls === 'string' ? [server.urls] : server.urls;
    if (!Array.isArray(urls) || !urls.length || urls.length > 8 || !urls.every(url => typeof url === 'string' && /^(stun|stuns|turn|turns):[^\s]+$/.test(url) && url.length <= 2048)
      || server.username !== undefined && (typeof server.username !== 'string' || server.username.length > 4096)
      || server.credential !== undefined && (typeof server.credential !== 'string' || server.credential.length > 4096)) throw new Error('电脑返回的连接配置无效。');
    return { urls: [...urls], ...(server.username === undefined ? {} : { username: server.username }), ...(server.credential === undefined ? {} : { credential: server.credential }) };
  });
}

export function desktopValue(response: unknown): DesktopRecord {
  if (!record(response) || !record(response.value)) throw new Error('桌面操作尚未返回完整回执。');
  return response.value;
}

export function desktopSession(value: unknown, expected: { deviceId: string; resourceBinding: DesktopResource; sessionId?: string; streamId?: string }, now = Date.now()): DesktopSession {
  if (!record(value) || value.kind !== 'desktopSession' || value.deviceId !== expected.deviceId || !id(value.sessionId)
    || expected.sessionId !== undefined && value.sessionId !== expected.sessionId
    || !resource(value.resourceBinding) || !sameResource(value.resourceBinding, expected.resourceBinding)
    || !integer(value.expiresAt) || value.expiresAt <= now || !integer(value.width, 1, 16384) || !integer(value.height, 1, 16384)
    || !record(value.control) || !['none', 'human', 'other_human', 'agent'].includes(value.control.owner) || !integer(value.control.revision, 0)
    || value.control.owner === 'other_human' && Object.keys(value.control).some(key => !['owner', 'revision', 'needsReconcile'].includes(key))
    || value.control.needsReconcile !== undefined && typeof value.control.needsReconcile !== 'boolean'
    || value.control.owner === 'human' && (!record(value.control.lease) || !id(value.control.lease.id)
      || !integer(value.control.lease.fence) || !integer(value.control.lease.expiresAt)
      || value.control.lease.resource !== `windows-session:${expected.resourceBinding.sessionId}:${expected.resourceBinding.displayId}`)
    || value.streamId !== undefined && !id(value.streamId)
    || expected.streamId !== undefined && value.streamId !== expected.streamId) throw new Error('桌面会话已变化或回执不完整，请重新连接。');
  return { kind: 'desktopSession', deviceId: value.deviceId, sessionId: value.sessionId, resourceBinding: copy(value.resourceBinding), expiresAt: value.expiresAt,
    width: value.width, height: value.height, control: copy(value.control) as DesktopControl, iceServers: iceServers(value.iceServers), ...(value.streamId ? { streamId: value.streamId } : {}) };
}

export function desktopFrame(value: unknown, streamId: string): DesktopFrame {
  if (!record(value) || value.type !== undefined && value.type !== 'frame' || value.streamId !== streamId || !id(value.frameId)
    || !id(value.geometryRevision) || !integer(value.width, 1, 16384) || !integer(value.height, 1, 16384)
    || !integer(value.capturedAt) || !integer(value.expiresAt) || value.expiresAt <= value.capturedAt || value.expiresAt - value.capturedAt > 30_000) throw new Error('桌面画面标识无效。');
  return { streamId: value.streamId, frameId: value.frameId, width: value.width, height: value.height, geometryRevision: value.geometryRevision,
    capturedAt: value.capturedAt, expiresAt: value.expiresAt,
    // Older or unverifiable media remains watchable, but can never authorize input.
    ...(integer(value.rtpTimestamp, 0, RTP_TIMESTAMP_MAX) ? { rtpTimestamp: value.rtpTimestamp } : {}) };
}

/** Object-fit: contain. Letterbox taps are rejected, never clamped into desktop clicks. */
export function desktopPoint(clientX: number, clientY: number, bounds: { left: number; top: number; width: number; height: number }, width: number, height: number): { x: number; y: number } | null {
  if (![clientX, clientY, bounds.left, bounds.top, bounds.width, bounds.height, width, height].every(Number.isFinite)
    || bounds.width <= 0 || bounds.height <= 0 || width <= 0 || height <= 0) return null;
  const scale = Math.min(bounds.width / width, bounds.height / height);
  const left = bounds.left + (bounds.width - width * scale) / 2, top = bounds.top + (bounds.height - height * scale) / 2;
  const x = (clientX - left) / scale, y = (clientY - top) / scale;
  if (x < 0 || y < 0 || x >= width || y >= height) return null;
  return { x: Math.floor(x), y: Math.floor(y) };
}

export function desktopText(value: string): string {
  if (!value || value.includes('\0') || new TextEncoder().encode(value).length > 12000) throw new Error('输入内容需为 1–12000 字节。');
  return value;
}

export function waitForDesktopIce(peer: RTCPeerConnection, signal: AbortSignal, timeoutMs = 15_000): Promise<void> {
  return new Promise((resolve, reject) => {
    let timer: ReturnType<typeof setTimeout> | undefined;
    const clean = () => { clearTimeout(timer); peer.removeEventListener('icegatheringstatechange', changed); signal.removeEventListener('abort', aborted); };
    const changed = () => { if (peer.iceGatheringState === 'complete') { clean(); resolve(); } };
    const aborted = () => { clean(); reject(new Error('桌面连接已关闭。')); };
    if (signal.aborted) { aborted(); return; }
    peer.addEventListener('icegatheringstatechange', changed); signal.addEventListener('abort', aborted, { once: true });
    timer = setTimeout(() => { clean(); reject(new Error('连接协商超时，请检查电脑连接后重试。')); }, timeoutMs);
    changed();
  });
}

const initialState = (): DesktopViewerState => ({ phase: 'idle', session: null, frameReady: false, frameIssue: null, controlPending: false, inputPending: false, inputUncertain: false, controlExpired: false, error: '' });

/** One viewer owns one native handle. Late replies only close their own handle. */
export class DesktopViewerConnection {
  state: DesktopViewerState = initialState();
  private generation = 0;
  private disposed = false;
  private peer: RTCPeerConnection | null = null;
  private abort: AbortController | null = null;
  private metadataChannel: RTCDataChannel | null = null;
  private videoTrack: MediaStreamTrack | null = null;
  private removeTrackListeners: (() => void) | null = null;
  private startup: ReturnType<typeof setTimeout> | null = null;
  private hasPlayed = false;
  private playbackAt: number | null = null;
  private stalledAt: number | null = null;
  private frame: ReceivedFrame | null = null;
  private frames = new Map<number, ReceivedFrame>();
  private seenFrames = new Set<string>();
  private seenTimestamps = new Map<number, string>();
  private lastFrameAt = 0;
  private frameFloor = 0;
  private decoded: { width: number; height: number; at: number; rtpTimestamp?: number } | null = null;
  private geometry: string | null = null;
  private geometryWidth = 0;
  private geometryHeight = 0;
  private metadataIdentity: boolean | null = null;
  private videoIdentity: boolean | null = null;
  private identityConflict = false;
  private unmatchedSince: number | null = null;
  private timer: ReturnType<typeof setInterval> | null = null;
  private disconnected: ReturnType<typeof setTimeout> | null = null;
  private statusPending = false;
  private statusSettled: Promise<void> | null = null;
  private lastStatusAt = 0;
  private bridgePending: Promise<unknown> | null = null;
  private run: DesktopRun;
  private change: (state: DesktopViewerState) => void;
  private stream: (stream: MediaStream | null) => void;
  private createPeer: (config: RTCConfiguration) => RTCPeerConnection;
  private clock: () => number;

  constructor(options: { run: DesktopRun; onChange: (state: DesktopViewerState) => void; onStream: (stream: MediaStream | null) => void; createPeer?: (config: RTCConfiguration) => RTCPeerConnection; clock?: () => number }) {
    this.run = options.run; this.change = options.onChange; this.stream = options.onStream;
    this.createPeer = options.createPeer || (config => new RTCPeerConnection(config)); this.clock = options.clock || Date.now;
  }
  private publish(patch: Partial<DesktopViewerState>): void {
    this.state = { ...this.state, ...patch }; if (!this.disposed) this.change(this.state);
  }
  private current(generation: number): boolean { return !this.disposed && generation === this.generation; }
  private request(action: string, args: DesktopRecord): Promise<unknown> {
    const pending = this.run(action, args); this.bridgePending = pending;
    return pending.finally(() => { if (this.bridgePending === pending) this.bridgePending = null; });
  }
  private closeRemote(sessionId: string): void { void this.run('devices.desktop.close', { sessionId }).catch(() => {}); }
  private stopVideoTrack(): void {
    this.removeTrackListeners?.(); this.removeTrackListeners = null;
    this.videoTrack?.stop(); this.videoTrack = null;
  }
  private detach(): void {
    this.abort?.abort(); this.abort = null;
    if (this.startup) clearTimeout(this.startup); this.startup = null;
    this.hasPlayed = false; this.playbackAt = null; this.stalledAt = null;
    if (this.metadataChannel) {
      this.metadataChannel.onmessage = null; this.metadataChannel.onclose = null; this.metadataChannel.onerror = null;
      this.metadataChannel = null;
    }
    this.stopVideoTrack();
    if (this.timer) clearInterval(this.timer); this.timer = null;
    if (this.disconnected) clearTimeout(this.disconnected); this.disconnected = null;
    if (this.peer) { this.peer.ontrack = null; this.peer.onconnectionstatechange = null; this.peer.close(); this.peer = null; }
    this.frame = null; this.frames.clear(); this.decoded = null; this.lastFrameAt = 0; this.frameFloor = 0; this.seenFrames.clear(); this.seenTimestamps.clear();
    this.geometry = null; this.geometryWidth = 0; this.geometryHeight = 0; this.metadataIdentity = null; this.videoIdentity = null; this.identityConflict = false; this.unmatchedSince = null;
    this.statusPending = false; this.statusSettled = null; this.lastStatusAt = 0; this.stream(null);
  }
  close(): void {
    const old = this.state.session; ++this.generation; this.detach(); this.publish(initialState()); if (old) this.closeRemote(old.sessionId);
  }
  dispose(): void { this.disposed = true; this.close(); }
  private fail(message: string): void {
    const old = this.state.session; ++this.generation; this.detach(); this.publish({ ...initialState(), phase: 'failed', error: message }); if (old) this.closeRemote(old.sessionId);
  }
  private accept(value: DesktopRecord, expected = this.state.session): DesktopSession {
    if (!expected) throw new Error('桌面会话已关闭。');
    const actual = desktopSession(value, expected, this.clock());
    if (actual.control.revision < expected.control.revision) return expected;
    const controlChanged = actual.control.owner !== expected.control.owner || actual.control.revision !== expected.control.revision
      || ['id', 'fence', 'expiresAt', 'resource'].some(key => actual.control.lease?.[key] !== expected.control.lease?.[key]);
    if (actual.control.revision === expected.control.revision && controlChanged) throw new Error('电脑的控制状态回执不一致，请重新核对。');
    if (controlChanged || actual.control.needsReconcile || actual.width !== expected.width || actual.height !== expected.height) this.invalidateFrame();
    this.publish({ session: actual, controlExpired: actual.control.owner === 'human' && actual.control.lease!.expiresAt <= this.clock(),
      ...(actual.control.needsReconcile ? { inputUncertain: true, error: this.state.error || '桌面控制需要核对，请重新连接查看。' } : {}) }); return actual;
  }
  async open(deviceId: string, scope: DesktopScope): Promise<void> {
    const previousRequest = this.bridgePending;
    this.close(); if (this.disposed) return;
    const generation = this.generation;
    if (!scope.available || scope.expiresAt <= this.clock()) { this.publish({ phase: 'failed', error: scope.reason || '桌面授权已到期。' }); return; }
    const binding = copy(scope.resourceBinding);
    this.publish({ phase: 'opening' });
    // Bound the retired native lane, signalling and first playback, including
    // connected transports which never deliver a usable video frame.
    this.startup = setTimeout(() => {
      if (this.current(generation)) this.fail('桌面连接或首帧等待超时，请检查电脑和网络后重新连接。');
    }, 45_000);
    try {
      // Close fences the retired viewer immediately. A new open waits for the
      // native owner's single ordinary request lane to finish its old callback.
      if (previousRequest) { try { await previousRequest; } catch { /* The retired request owns its error. */ } }
      if (!this.current(generation)) return;
      const value = desktopValue(await this.request('devices.desktop.open', { deviceId, grantId: scope.grantId, resourceBinding: binding }));
      if (!this.current(generation)) { if (value.deviceId === deviceId && id(value.sessionId)) this.closeRemote(value.sessionId); return; }
      let session: DesktopSession;
      try { session = desktopSession(value, { deviceId, resourceBinding: binding }, this.clock()); }
      catch (error) { if (value.deviceId === deviceId && id(value.sessionId)) this.closeRemote(value.sessionId); throw error; }
      this.publish({ session, phase: 'connecting' });
      const peer = this.createPeer({ iceServers: session.iceServers }); this.peer = peer;
      const abort = new AbortController(); this.abort = abort;
      peer.addTransceiver('video', { direction: 'recvonly' });
      const channel = this.metadataChannel = peer.createDataChannel('pocketworld.desktop.frames', { ordered: true });
      channel.onclose = () => { if (this.current(generation)) this.fail('画面核对通道已断开，请重新连接。'); };
      channel.onerror = () => { if (this.current(generation)) this.fail('画面核对通道出现错误，请重新连接。'); };
      channel.onmessage = event => {
        if (!this.current(generation) || typeof event.data !== 'string' || event.data.length > 8192 || !this.state.session?.streamId) return;
        try { this.receiveFrame(JSON.parse(event.data)); } catch { this.fail('电脑返回的画面标识无效，已关闭观看。'); }
      };
      peer.ontrack = event => {
        if (!this.current(generation) || event.track.kind !== 'video' || this.videoTrack === event.track) return;
        this.stopVideoTrack(); this.videoIdentity = null; this.playbackAt = null; this.invalidateFrame();
        const track = this.videoTrack = event.track;
        const ended = () => { if (this.current(generation) && this.videoTrack === track) this.fail('电脑视频流已结束，请重新连接。'); };
        const changed = () => { if (this.current(generation) && this.videoTrack === track) { this.playbackAt = null; this.invalidateFrame(); } };
        track.addEventListener('ended', ended); track.addEventListener('mute', changed); track.addEventListener('unmute', changed);
        this.removeTrackListeners = () => {
          track.removeEventListener('ended', ended); track.removeEventListener('mute', changed); track.removeEventListener('unmute', changed);
        };
        if (track.readyState === 'ended') { ended(); return; }
        this.stream(event.streams[0] || new MediaStream([track]));
      };
      peer.onconnectionstatechange = () => {
        if (!this.current(generation)) return;
        if (peer.connectionState === 'connected') {
          if (this.disconnected) clearTimeout(this.disconnected); this.disconnected = null;
          if (this.state.phase !== 'live') { this.playbackAt = null; this.invalidateFrame(); }
          this.publish({ phase: 'live' }); this.updateReadiness();
        } else if (peer.connectionState === 'disconnected') {
          this.playbackAt = null; this.invalidateFrame(); this.publish({ phase: 'disconnected' });
          if (!this.disconnected) this.disconnected = setTimeout(() => { if (this.current(generation)) this.fail('桌面连接已中断，请重新连接。'); }, 10_000);
        } else if (peer.connectionState === 'failed' || peer.connectionState === 'closed') this.fail('桌面连接已中断，请重新连接。');
      };
      await peer.setLocalDescription(await peer.createOffer());
      await waitForDesktopIce(peer, abort.signal);
      if (!this.current(generation)) return;
      if (!peer.localDescription || peer.localDescription.type !== 'offer') throw new Error('桌面连接协商未完成。');
      const negotiated = desktopValue(await this.request('devices.desktop.negotiate', { sessionId: session.sessionId, offer: { type: 'offer', sdp: peer.localDescription.sdp } }));
      if (!this.current(generation)) return;
      const actual = this.accept(negotiated);
      if (!actual.streamId || !record(negotiated.answer) || negotiated.answer.type !== 'answer' || typeof negotiated.answer.sdp !== 'string' || !negotiated.answer.sdp.trim() || negotiated.answer.sdp.length > 1024 * 1024) throw new Error('电脑没有返回有效的视频连接。');
      await peer.setRemoteDescription({ type: 'answer', sdp: negotiated.answer.sdp });
      if (!this.current(generation)) return;
      // Poll ownership and expiry, never replay an input or silently obtain a new grant.
      this.timer = setInterval(() => { this.tick(); }, 1000);
    } catch (error) { if (this.current(generation)) this.fail(error instanceof Error ? error.message : String(error)); }
  }
  receiveFrame(value: unknown): void {
    const streamId = this.state.session?.streamId; if (!streamId) return;
    const actual = desktopFrame(value, streamId);
    if (actual.capturedAt <= this.frameFloor || actual.capturedAt < this.lastFrameAt || this.seenFrames.has(actual.frameId)) return;
    this.lastFrameAt = actual.capturedAt; this.seenFrames.add(actual.frameId);
    if (this.seenFrames.size > 64) this.seenFrames.delete(this.seenFrames.values().next().value!);
    // Retire outage metadata without allowing it to seed a recovered connection.
    if (this.state.phase !== 'live') { this.frameFloor = Math.max(this.frameFloor, actual.capturedAt); return; }
    if (this.geometry !== null && (this.geometry !== actual.geometryRevision || this.geometryWidth !== actual.width || this.geometryHeight !== actual.height)) this.invalidateFrame();
    this.geometry = actual.geometryRevision; this.geometryWidth = actual.width; this.geometryHeight = actual.height;
    this.metadataIdentity = actual.rtpTimestamp !== undefined;
    this.pruneFrames();
    if (actual.rtpTimestamp === undefined) this.invalidateFrame();
    else if (!this.identityConflict) {
      // A single RTP timestamp cannot describe two different frames in this bounded window.
      const existing = this.seenTimestamps.get(actual.rtpTimestamp);
      if (existing && existing !== actual.frameId) {
        this.identityConflict = true; this.invalidateFrame();
      } else {
        this.seenTimestamps.set(actual.rtpTimestamp, actual.frameId);
        while (this.seenTimestamps.size > 64) this.seenTimestamps.delete(this.seenTimestamps.keys().next().value!);
        this.frames.set(actual.rtpTimestamp, { value: actual, received: this.clock() });
        while (this.frames.size > 32) this.frames.delete(this.frames.keys().next().value!);
      }
    }
    this.updateReadiness();
  }
  decodedFrame(width: number, height: number, streamId = this.state.session?.streamId, rtpTimestamp?: number): void {
    if (this.state.phase !== 'live' || !streamId || streamId !== this.state.session?.streamId || this.videoTrack?.muted || this.videoTrack?.readyState === 'ended') return;
    if (!integer(width, 1, 16384) || !integer(height, 1, 16384)) { this.cannotVerifyVideoFrames(); return; }
    this.videoIdentity = integer(rtpTimestamp, 0, RTP_TIMESTAMP_MAX);
    this.decoded = { width, height, at: this.clock(), ...(this.videoIdentity ? { rtpTimestamp } : {}) };
    // Playback health and permission to send input are separate: a browser
    // without an RTP identity may keep watching, but never gains frameReady.
    if (this.startup) clearTimeout(this.startup); this.startup = null;
    this.hasPlayed = true; this.playbackAt = this.decoded.at; this.stalledAt = null;
    this.updateReadiness();
  }
  cannotVerifyVideoFrames(): void { this.videoIdentity = false; this.invalidateFrame(); }
  private invalidateFrame(): void {
    this.frameFloor = Math.max(this.frameFloor, this.lastFrameAt);
    this.frame = null; this.frames.clear(); this.decoded = null;
    this.unmatchedSince = this.state.phase === 'live' ? this.clock() : null;
    this.updateReadiness();
  }
  private metadataFresh(frame: ReceivedFrame, now = this.clock()): boolean {
    return frame.value.expiresAt > now && now >= frame.received
      && now - frame.received < Math.min(2000, frame.value.expiresAt - frame.value.capturedAt);
  }
  private pruneFrames(): void {
    const now = this.clock();
    for (const [timestamp, frame] of this.frames) if (!this.metadataFresh(frame, now)) this.frames.delete(timestamp);
  }
  private updateReadiness(): void {
    this.pruneFrames();
    // Video and metadata can arrive in either order. Only the currently displayed
    // decoded frame can match; an older decode is never kept as an input fallback.
    this.frame = !this.identityConflict && this.metadataIdentity && this.videoIdentity && this.decoded?.rtpTimestamp !== undefined
      ? this.frames.get(this.decoded.rtpTimestamp) || null : null;
    const ready = this.fresh(), now = this.clock();
    let issue: DesktopViewerState['frameIssue'] = null;
    if (ready || this.state.phase !== 'live') this.unmatchedSince = null;
    else {
      if (this.unmatchedSince === null || now < this.unmatchedSince) this.unmatchedSince = now;
      if (this.identityConflict || this.metadataIdentity === false || this.videoIdentity === false) issue = 'unavailable';
      else if (now - this.unmatchedSince >= FRAME_MATCH_TIMEOUT_MS) issue = 'unmatched';
    }
    if (ready !== this.state.frameReady || issue !== this.state.frameIssue) this.publish({ frameReady: ready, frameIssue: issue });
  }
  private mediaFresh(): boolean {
    if (this.state.phase !== 'live' || !this.state.session || this.playbackAt === null || this.videoTrack?.muted || this.videoTrack?.readyState === 'ended'
      || this.metadataChannel?.readyState === 'closed' || this.metadataChannel?.readyState === 'closing') return false;
    const now = this.clock();
    return this.state.session.expiresAt > now && now >= this.playbackAt && now - this.playbackAt < 2000;
  }
  private fresh(): boolean {
    if (!this.mediaFresh() || !this.frame || !this.decoded || this.identityConflict || !this.metadataIdentity || !this.videoIdentity) return false;
    const now = this.clock(), frame = this.frame;
    return this.metadataFresh(frame, now) && now >= this.decoded.at && now - this.decoded.at < 2000
      && frame.value.rtpTimestamp !== undefined && frame.value.rtpTimestamp === this.decoded.rtpTimestamp
      && frame.value.geometryRevision === this.geometry && frame.value.width === this.decoded.width && frame.value.height === this.decoded.height;
  }
  point(clientX: number, clientY: number, bounds: { left: number; top: number; width: number; height: number }, width: number, height: number): { x: number; y: number } | null {
    if (!this.frame || !this.fresh() || this.frame.value.width !== width || this.frame.value.height !== height) return null;
    return desktopPoint(clientX, clientY, bounds, width, height);
  }
  canInput(): boolean {
    return this.fresh() && this.state.session?.control.owner === 'human' && !!this.state.session.control.lease
      && this.state.session.expiresAt > this.clock() && this.state.session.control.lease!.expiresAt > this.clock() && !this.state.session.control.needsReconcile
      && !this.state.inputPending && !this.state.controlPending && !this.state.inputUncertain;
  }
  private tick(): void {
    const session = this.state.session; if (!session) return;
    if (session.expiresAt <= this.clock()) { this.fail('桌面观看已到期，请重新连接。'); return; }
    this.updateReadiness();
    if (this.mediaFresh()) this.stalledAt = null;
    else if (this.hasPlayed && this.state.phase === 'live') {
      const now = this.clock();
      if (this.stalledAt === null || now < this.stalledAt) this.stalledAt = now;
      else if (now - this.stalledAt >= 10_000) { this.fail('桌面画面持续未更新，请重新连接。'); return; }
    }
    const expired = session.control.owner === 'human' && session.control.lease!.expiresAt <= this.clock();
    if (expired !== this.state.controlExpired) this.publish({ controlExpired: expired });
    if (this.clock() - this.lastStatusAt >= 5000 && !this.statusPending && !this.state.controlPending && !this.state.inputPending) void this.status();
  }
  async status(): Promise<void> {
    const expected = this.state.session, generation = this.generation;
    if (!expected || this.statusPending || this.state.inputPending || this.state.controlPending) return;
    this.statusPending = true; this.lastStatusAt = this.clock();
    const pending = (async () => {
      try {
        const value = desktopValue(await this.request('devices.desktop.status', { sessionId: expected.sessionId }));
        if (!this.current(generation)) return;
        this.accept(value);
        // A polled frame is not proof that the video element displayed it. Metadata channel remains required.
      } catch (error) { if (this.current(generation)) this.fail(error instanceof Error ? error.message : String(error)); }
      finally { if (this.current(generation)) this.statusPending = false; }
    })();
    this.statusSettled = pending;
    await pending;
    if (this.current(generation) && this.statusSettled === pending) this.statusSettled = null;
  }
  async control(action: 'take' | 'release'): Promise<void> {
    const generation = this.generation;
    if (this.statusSettled) { await this.statusSettled; if (!this.current(generation)) return; }
    const session = this.state.session;
    if (!session || session.control.owner === 'other_human' || this.state.controlPending || this.state.inputPending
      || action === 'take' && (!this.fresh() || this.state.inputUncertain || session.control.needsReconcile)) return;
    this.publish({ controlPending: true, error: '' });
    try {
      const value = desktopValue(await this.request('devices.desktop.control', { sessionId: session.sessionId, action, expectedRevision: session.control.revision }));
      if (!this.current(generation)) return;
      const receipt = desktopSession(value, session, this.clock());
      if (receipt.control.revision <= session.control.revision || receipt.control.owner !== (action === 'take' ? 'human' : 'none')
        || action === 'take' && receipt.control.lease!.expiresAt <= this.clock()) throw new Error('电脑尚未确认控制权变更，请重新核对。');
      const actual = this.accept(value);
      if (actual.control.revision !== receipt.control.revision) throw new Error('电脑控制权已再次变化，请重新核对。');
      const needsReconcile = session.control.needsReconcile === true || actual.control.needsReconcile === true;
      if (action === 'take' && needsReconcile) throw new Error('桌面控制需要核对，请重新连接查看。');
      this.publish({ inputUncertain: needsReconcile });
    } catch (error) {
      if (this.current(generation)) this.publish({ error: error instanceof Error ? error.message : String(error), inputUncertain: true });
    } finally { if (this.current(generation)) { this.invalidateFrame(); this.publish({ controlPending: false }); } }
  }
  async input(input: DesktopInput): Promise<boolean> {
    const generation = this.generation, intendedSession = this.state.session, intendedFrame = this.frame;
    if (!intendedSession || !intendedFrame || !this.canInput()) return false;
    if (this.statusSettled) { await this.statusSettled; if (!this.current(generation)) return false; }
    const session = this.state.session, frame = this.frame;
    if (!session || !frame || !this.canInput()) return false;
    // Bind the user's gesture before yielding to status. Never retarget queued
    // coordinates, text or keys to a later picture or control generation.
    if (frame !== intendedFrame || session.sessionId !== intendedSession.sessionId || session.control.revision !== intendedSession.control.revision) {
      this.publish({ error: '桌面画面或控制状态已变化，请重新操作。' }); return false;
    }
    if (input.kind === 'click' && (!integer(input.x, 0, frame.value.width - 1) || !integer(input.y, 0, frame.value.height - 1))) return false;
    if (input.kind === 'insert_text') desktopText(input.text);
    this.publish({ inputPending: true, error: '' });
    try {
      const value = desktopValue(await this.request('devices.desktop.input', { sessionId: session.sessionId, controlLease: copy(session.control.lease), frameRef: copy(frame.value), input: copy(input) }));
      if (!this.current(generation)) return false;
      const actual = this.accept(value);
      const receipt = value.inputReceipt;
      if (actual.control.needsReconcile || !record(receipt) || receipt.status !== 'completed' || typeof receipt.inputDispatched !== 'boolean') throw new Error('输入结果尚未确认，请查看桌面后重新连接核对。');
      if (receipt.inputDispatched !== true) throw new Error('电脑未执行这次输入，请核对控制状态。');
      return true;
    } catch (error) {
      if (this.current(generation)) this.publish({ inputUncertain: true, error: error instanceof Error ? error.message : String(error) });
      return false;
    } finally { if (this.current(generation)) { this.invalidateFrame(); this.publish({ inputPending: false }); } }
  }
}
