/** Scoped, read-only phone preview transport. The task's execution port owns the display. */
export type PhoneViewerIdentity = { taskId: string; runId: string; epoch: number };
export type PhoneViewerTask = PhoneViewerIdentity & {
  title?: string;
  status: 'running' | 'paused' | 'waiting' | 'stopping' | 'ended' | 'error';
  statusLabel?: string;
  detail?: string;
  /** Display-only state, bound to the original native run by the task projection. */
  preparation?: 'pending' | 'blocked';
};
export type PhoneViewerSession = PhoneViewerIdentity & { viewerId: string };
export type PhoneDisplayIdentity = { sessionId: string; displayId: number };
export type PhoneFrameReceipt = PhoneViewerIdentity & PhoneDisplayIdentity & {
  state: 'ready' | 'pending' | 'unchanged';
  frameGeneration: number;
  capturedAt: number;
  sourceWidth: number;
  sourceHeight: number;
  previewWidth: number;
  previewHeight: number;
  mime: string;
  imageBase64?: string;
};
export type PhonePreviewFrame = PhoneFrameReceipt & { src: string; receivedAt: number };
export type PhoneViewerState = {
  phase: 'idle' | 'opening' | 'waiting' | 'live' | 'hidden' | 'ended' | 'error';
  frame: PhonePreviewFrame | null;
  error: string;
  /** Local wall time of the last ready/unchanged receipt; native capturedAt is uptime. */
  verifiedAt: number;
};
export type PhoneViewerCommand = (action: string, args: Record<string, unknown>) => Promise<unknown>;

const staleMessage = '原任务或独立屏已变化，请重新核对这次任务。';
const integer = (value: unknown, minimum = 0): value is number => Number.isSafeInteger(value) && (value as number) >= minimum;
const record = (value: unknown): Record<string, unknown> => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new PhoneViewerProtocolError();
  return value as Record<string, unknown>;
};
const identifier = (value: unknown): value is string => typeof value === 'string' && value.length > 0 && value.length <= 4096 && !/[\u0000-\u001f]/.test(value);

export class PhoneViewerProtocolError extends Error {
  code = 'phone_viewer_stale';
  constructor(message = staleMessage) { super(message); this.name = 'PhoneViewerProtocolError'; }
}

export function phoneViewerKey(identity: PhoneViewerIdentity): string {
  return JSON.stringify([identity.taskId, identity.runId, identity.epoch]);
}

export function phoneViewerIdentity(value: unknown, expected?: PhoneViewerIdentity): PhoneViewerIdentity {
  const data = record(value);
  if (!identifier(data.taskId) || !identifier(data.runId) || !integer(data.epoch, 1)) throw new PhoneViewerProtocolError();
  const identity = { taskId: data.taskId, runId: data.runId, epoch: data.epoch };
  if (expected && phoneViewerKey(identity) !== phoneViewerKey(expected)) throw new PhoneViewerProtocolError();
  return identity;
}

export function phoneViewerSession(value: unknown, expected: PhoneViewerIdentity): PhoneViewerSession {
  const data = record(value), identity = phoneViewerIdentity(data, expected);
  if (!identifier(data.viewerId)) throw new PhoneViewerProtocolError();
  return { ...identity, viewerId: data.viewerId };
}

export function phoneViewerFrame(value: unknown, expected: PhoneViewerIdentity, display: PhoneDisplayIdentity | null, previous: PhonePreviewFrame | null, latest?: { frameGeneration: number; capturedAt: number } | null): PhoneFrameReceipt {
  const data = record(value), identity = phoneViewerIdentity(data, expected);
  if (!identifier(data.sessionId) || !integer(data.displayId, 1) || !integer(data.frameGeneration) || !integer(data.capturedAt)) throw new PhoneViewerProtocolError();
  if (display && (data.sessionId !== display.sessionId || data.displayId !== display.displayId)) throw new PhoneViewerProtocolError();
  if (data.state !== 'pending' && data.state !== 'ready' && data.state !== 'unchanged') throw new PhoneViewerProtocolError();
  const dimensions = ['sourceWidth', 'sourceHeight', 'previewWidth', 'previewHeight'] as const;
  for (const dimension of dimensions) if (!integer(data[dimension], data.state === 'ready' ? 1 : 0) || (data[dimension] as number) > (dimension.startsWith('preview') ? 960 : 16384)) throw new PhoneViewerProtocolError('独立屏画面尺寸无效，请重新读取。');
  if (data.frameGeneration < (latest?.frameGeneration ?? previous?.frameGeneration ?? -1)) throw new PhoneViewerProtocolError();
  // Rotation can invalidate the old pixels: pending carries a new generation and
  // capturedAt=0. Preserve the positive uptime watermark until a valid frame arrives.
  if (data.state !== 'pending' && data.capturedAt > 0 && data.capturedAt < (latest?.capturedAt || previous?.capturedAt || 0)) throw new PhoneViewerProtocolError();
  if (typeof data.mime !== 'string' || (data.mime && data.mime !== 'image/jpeg')) throw new PhoneViewerProtocolError('独立屏画面格式无法显示。');
  if (data.state === 'unchanged' && (!previous || data.frameGeneration !== previous.frameGeneration)) throw new PhoneViewerProtocolError();
  if (data.state === 'ready' && (data.mime !== 'image/jpeg' || !integer(data.frameGeneration, 1) || !integer(data.capturedAt, 1) || typeof data.imageBase64 !== 'string' || !data.imageBase64.length || data.imageBase64.length > 2 * 1024 * 1024 || data.imageBase64.length % 4 !== 0 || !/^[A-Za-z0-9+/]+={0,2}$/.test(data.imageBase64))) throw new PhoneViewerProtocolError('独立屏画面未能完整读取，请重试。');
  if (previous && data.frameGeneration === previous.frameGeneration && data.state !== 'pending' && dimensions.some(key => (data[key] as number) > 0 && data[key] !== previous[key])) throw new PhoneViewerProtocolError();
  return {
    ...identity, sessionId: data.sessionId, displayId: data.displayId, state: data.state,
    frameGeneration: data.frameGeneration, capturedAt: data.capturedAt,
    sourceWidth: data.sourceWidth as number, sourceHeight: data.sourceHeight as number,
    previewWidth: data.previewWidth as number, previewHeight: data.previewHeight as number,
    mime: data.mime, ...(data.state === 'ready' ? { imageBase64: data.imageBase64 as string } : {}),
  };
}

const emptyState = (): PhoneViewerState => ({ phase: 'idle', frame: null, error: '', verifiedAt: 0 });
const fatalCodes = new Set(['phone_viewer_stale', 'phone_viewer_ended', 'phone_viewer_hidden']);
const errorCode = (error: unknown) => error && typeof error === 'object' && 'code' in error ? String(error.code) : '';
const errorText = (error: unknown) => error instanceof Error ? error.message : '预览暂时中断，正在重新读取。';

/** One connection for one exact run. A single serial timer serves card and sheet. */
export class PhoneViewerConnection {
  private readonly identity: PhoneViewerIdentity;
  private readonly command: PhoneViewerCommand;
  private readonly publish: (state: PhoneViewerState) => void;
  private readonly documentEpoch: () => number;
  private readonly now: () => number;
  private readonly intervalMs: number;
  private state = emptyState();
  private session: PhoneViewerSession | null = null;
  private display: PhoneDisplayIdentity | null = null;
  private latest: { frameGeneration: number; capturedAt: number } | null = null;
  private cursor = -1;
  private token = 0;
  private document = 0;
  private visible = false;
  private active = false;
  private disposed = false;
  private terminal = false;
  private failures = 0;
  private blocked: Pick<PhoneViewerState, 'phase' | 'error'> | null = null;
  private timer: ReturnType<typeof setTimeout> | undefined;

  constructor(identity: PhoneViewerIdentity, command: PhoneViewerCommand, publish: (state: PhoneViewerState) => void,
    options: { documentEpoch?: () => number; now?: () => number; intervalMs?: number } = {}) {
    this.identity = phoneViewerIdentity(identity);
    this.command = command;
    this.publish = publish;
    this.documentEpoch = options.documentEpoch ?? (() => 0);
    this.now = options.now ?? Date.now;
    this.intervalMs = Math.max(200, options.intervalMs ?? 200);
    this.document = this.documentEpoch();
  }

  getState(): PhoneViewerState { return this.state; }

  setVisible(visible: boolean, terminal = false): void {
    if (this.disposed) return;
    const changed = this.visible !== visible || this.terminal !== terminal;
    this.visible = visible; this.terminal = terminal;
    if (terminal) { this.stop(); this.emit({ phase: 'ended', error: '' }); return; }
    if (!visible) { this.stop(); this.emit({ phase: 'hidden', error: '' }); return; }
    if (this.document !== this.documentEpoch()) { this.fail(new PhoneViewerProtocolError('页面已恢复，请重新打开这次任务的画面。')); return; }
    if (this.blocked) { this.emit(this.blocked); return; }
    if (changed && !this.active) this.start();
  }

  retry(): void {
    if (this.disposed || !this.visible || this.terminal) return;
    this.stop(); this.blocked = null; this.document = this.documentEpoch(); this.start();
  }

  dispose(): void { this.disposed = true; this.visible = false; this.stop(); }

  private emit(patch: Partial<PhoneViewerState>): void {
    this.state = { ...this.state, ...patch };
    if (!this.disposed) this.publish(this.state);
  }

  private release(viewerId: string): void {
    // Native close releases only this viewer handle, even after its task/window ends.
    void this.command('phone.viewer.close', { viewerId }).catch(() => {});
  }

  private stop(): void {
    this.token++; this.active = false;
    clearTimeout(this.timer); this.timer = undefined;
    const session = this.session; this.session = null;
    if (session) this.release(session.viewerId);
  }

  private current(token: number): boolean {
    if (this.disposed || !this.active || token !== this.token || !this.visible || this.terminal) return false;
    if (this.document !== this.documentEpoch()) { this.fail(new PhoneViewerProtocolError('原页面已变化，请重新打开这次任务的画面。')); return false; }
    return true;
  }

  private fail(error: unknown): void {
    this.stop();
    this.blocked = { phase: errorCode(error) === 'phone_viewer_ended' ? 'ended' : errorCode(error) === 'phone_viewer_hidden' ? 'hidden' : 'error', error: errorText(error) };
    this.emit(this.blocked);
  }

  private start(): void {
    this.active = true; this.failures = 0;
    // A new native viewer has not delivered the retained image. Its first request
    // must ask for full pixels even when this run already has a displayed frame.
    this.cursor = -1;
    const token = ++this.token;
    this.emit({ phase: 'opening', error: '' });
    void this.open(token);
  }

  private async open(token: number): Promise<void> {
    try {
      const value = await this.command('phone.viewer.open', { ...this.identity });
      // Even an obsolete successful open must release its own returned handle.
      const opened = record(value);
      if (!this.current(token)) { if (identifier(opened.viewerId)) this.release(opened.viewerId); return; }
      let session: PhoneViewerSession;
      try { session = phoneViewerSession(value, this.identity); }
      catch (error) { if (identifier(opened.viewerId)) this.release(opened.viewerId); throw error; }
      this.session = session;
      await this.poll(token);
    } catch (error) {
      if (!this.current(token)) return;
      // Open has an uncertain handle after a timeout. Do not accumulate hidden opens.
      this.fail(error);
    }
  }

  private async poll(token: number): Promise<void> {
    if (!this.current(token) || !this.session) return;
    let delay = this.intervalMs;
    try {
      const value = await this.command('phone.viewer.frame', { viewerId: this.session.viewerId, afterFrameGeneration: this.cursor });
      if (!this.current(token)) return;
      const receipt = phoneViewerFrame(value, this.identity, this.display, this.state.frame, this.latest);
      this.display ??= { sessionId: receipt.sessionId, displayId: receipt.displayId };
      this.latest = { frameGeneration: receipt.frameGeneration, capturedAt: receipt.state !== 'pending' && receipt.capturedAt > 0 ? receipt.capturedAt : this.latest?.capturedAt ?? 0 };
      this.cursor = receipt.frameGeneration;
      this.failures = 0;
      if (receipt.state === 'ready') {
        const frame: PhonePreviewFrame = { ...receipt, src: `data:${receipt.mime};base64,${receipt.imageBase64}`, receivedAt: this.now() };
        this.emit({ phase: 'live', frame, verifiedAt: this.now(), error: '' });
      } else if (receipt.state === 'unchanged') {
        this.emit({ phase: 'live', verifiedAt: this.now(), error: '' });
      } else this.emit({ phase: 'waiting', error: '' });
    } catch (error) {
      if (!this.current(token)) return;
      if (error instanceof PhoneViewerProtocolError || fatalCodes.has(errorCode(error))) { this.fail(error); return; }
      if (errorCode(error) === 'phone_viewer_starting') {
        this.failures = 0;
        delay = 500;
        this.emit({ phase: 'waiting', error: '' });
      } else {
        this.failures++;
        delay = Math.min(3000, 500 * 2 ** Math.min(this.failures - 1, 3));
        this.emit({ phase: 'error', error: errorText(error) });
      }
    }
    if (this.current(token)) this.timer = setTimeout(() => { this.timer = undefined; void this.poll(token); }, delay);
  }
}
