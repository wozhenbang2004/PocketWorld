export type PocketSnapshot = {
  revision?: number;
  state: Record<string, any>;
  composer: Record<string, any> | null;
  capabilities: { commands: string[] };
  host?: { connected?: boolean; keyboardVisible?: boolean; [key: string]: unknown };
  [key: string]: unknown;
};

type NativeMessage = {
  v: number;
  kind: string;
  revision?: number;
  snapshot?: PocketSnapshot;
  id?: string;
  ok?: boolean;
  value?: unknown;
  error?: unknown;
};

declare global {
  interface Window {
    PocketNative?: { postMessage(value: string): void };
    __pocketDocumentToken?: string;
    PocketWeb: {
      receive(message: NativeMessage | string): void;
      handleBack(): boolean;
      hostConfiguration?(value: Record<string, unknown>): void;
    };
  }
}

let current: PocketSnapshot = {
  revision: 0,
  state: { messages: [], tasks: [], projects: [], conversations: [] },
  composer: null,
  capabilities: { commands: [] },
  host: { connected: false },
};
let sequence = 0;
const documentId = Array.from(crypto.getRandomValues(new Uint8Array(16)), value => value.toString(16).padStart(2, '0')).join('');
class HostHandshakeTimeout extends Error {}
class NativeCommandError extends Error {
  code: string;
  constructor(message: string, code: string) { super(message); this.code = code; }
}
let documentBound = false;
let hostDocumentToken: string | null = null;
let hostDocumentEpoch = 0;
const tokenListeners = new Set<() => void>();
let hostDocumentResolve: (() => void) | null = null;
const hostDocumentReady = new Promise<void>(resolve => { hostDocumentResolve = resolve; });
function acceptHostDocumentToken(value: unknown) {
  if (typeof value !== 'string' || !value) return;
  if (hostDocumentToken !== value) hostDocumentEpoch++;
  if (hostDocumentToken !== null && hostDocumentToken !== value) {
    for (const [id, request] of pending) {
      if (request.action === 'app.ready') {
        clearTimeout(request.timeout); pending.delete(id);
        request.reject(new Error('工作台已恢复，正在重新绑定当前页面。'));
      }
    }
  }
  hostDocumentToken = value;
  tokenListeners.forEach(listener => listener());
  delete window.__pocketDocumentToken;
  hostDocumentResolve?.(); hostDocumentResolve = null;
}
let advertisedCommands: string[] = [];
let backHandler: (() => boolean) | null = null;
const listeners = new Set<() => void>();
const pending = new Map<string, { action: string; resolve(value: any): void; reject(error: Error): void; timeout: ReturnType<typeof setTimeout> }>();
// React page cleanup and the next entry can occur in one turn. Native changes its
// window ticket during navigation, so only send the next command after that receipt.
// Ordinary reads/writes remain concurrent once the navigation has settled.
const navigationActions = new Set(['connections.enter', 'connections.leave', 'channels.open', 'channels.close', 'skills.open', 'skills.close', 'routeReference.open', 'routeReference.close', 'tasks.open', 'groups.openTask', 'devices.openTask', 'tasks.close', 'projects.select', 'projects.preview', 'projects.package.preview.inspect', 'projects.package.preview.open', 'projects.preview.return', 'projects.preview.close', 'conversation.new', 'conversation.select', 'archives.open', 'archives.close', 'storage.open', 'storage.close']);
let navigationTail: Promise<void> | null = null;
acceptHostDocumentToken(window.__pocketDocumentToken);

function publish() { listeners.forEach(listener => listener()); }

function receive(input: NativeMessage | string) {
  let message: NativeMessage;
  try { message = typeof input === 'string' ? JSON.parse(input) : input; }
  catch { return; }
  if (!message || message.v !== 1) return;
  if (message.kind === 'state' && message.snapshot && Number.isFinite(message.revision)) {
    if ((message.revision as number) <= (current.revision || 0)) return;
    const next = message.snapshot;
    advertisedCommands = Array.isArray(next.capabilities?.commands) ? next.capabilities.commands : [];
    current = {
      ...next,
      revision: message.revision,
      state: next.state || {},
      composer: next.composer || null,
      capabilities: { commands: documentBound ? advertisedCommands : [] },
      host: { ...current.host, ...next.host, connected: true, ready: documentBound },
    };
    publish();
    return;
  }
  if (message.kind === 'result' && typeof message.id === 'string') {
    const request = pending.get(message.id);
    if (!request) return;
    clearTimeout(request.timeout);
    pending.delete(message.id);
    if (message.ok) {
      if (request.action === 'app.ready') {
        documentBound = true;
        current = { ...current, capabilities: { commands: advertisedCommands }, host: { ...current.host, ready: true } };
        publish();
      }
      request.resolve(message.value);
    }
    else {
      const value = message.error;
      const detail = typeof value === 'string' ? value : value && typeof value === 'object' && 'message' in value ? String(value.message) : '操作未能受理，请保留当前内容并核对原回执。';
      const code = value && typeof value === 'object' && 'code' in value ? String(value.code) : 'unknown';
      request.reject(new NativeCommandError(detail, code));
    }
  }
}

function sendCommand<T>(action: string, args: Record<string, unknown>): Promise<T> {
  const native = window.PocketNative;
  if (!native || typeof native.postMessage !== 'function') return Promise.reject(new NativeCommandError('界面尚未连接应用，请保留当前输入。', 'not_sent'));
  if (action !== 'app.ready' && action !== 'app.refresh' && !current.capabilities.commands.includes(action)) {
    return Promise.reject(new NativeCommandError('当前窗口不可执行此操作，请刷新原对象后核对。', 'not_sent'));
  }
  const id = `${Date.now().toString(36)}-${++sequence}`;
  return new Promise<T>((resolve, reject) => {
    const timeout = setTimeout(() => {
      pending.delete(id);
      reject(action === 'app.ready' ? new HostHandshakeTimeout('尚未收到页面握手回执，请保留当前内容。') : new NativeCommandError('尚未收到本次请求的受理回执；内容仍保留，请先核对原动作。', 'outcome_unknown'));
    }, 30_000);
    pending.set(id, { action, resolve, reject, timeout });
    try {
      const encoded = JSON.stringify({ v: 1, id, documentId, hostDocumentToken, action, args: action === 'app.ready' ? { ...args, documentId } : args });
      if (encoded.length > 16 * 1024 * 1024) throw new Error('当前请求过长，请保留原文件后缩小本次内容。');
      native.postMessage(encoded);
    } catch (error) {
      clearTimeout(timeout); pending.delete(id);
      reject(error instanceof Error ? error : new NativeCommandError('请求未能登记，当前输入仍保留。', 'not_sent'));
    }
  });
}

export const bridge = {
  getState: () => current,
  // UI request tickets can observe a changed native document without reading its secret token.
  getDocumentEpoch: () => hostDocumentEpoch,
  waitForHostDocument: () => hostDocumentReady,
  async bindHostDocument() {
    await hostDocumentReady;
    while (true) {
      const token = hostDocumentToken;
      try { return await bridge.command('app.ready'); }
      catch (error) {
        if (token === hostDocumentToken) {
          if (!(error instanceof HostHandshakeTimeout)) throw error;
          // Do not replay a handshake against the same window after an unknown outcome.
          // A later native foreground token can safely start one new handshake.
          await new Promise<void>(resolve => {
            const changed = () => { if (token !== hostDocumentToken) { tokenListeners.delete(changed); resolve(); } };
            tokenListeners.add(changed); changed();
          });
        }
      }
    }
  },
  subscribe(listener: () => void) { listeners.add(listener); return () => { listeners.delete(listener); }; },
  setBackHandler(handler: (() => boolean) | null) {
    backHandler = handler;
    return () => { if (backHandler === handler) backHandler = null; };
  },
  command<T = any>(action: string, args: Record<string, unknown> = {}): Promise<T> {
    const prior = action === 'app.ready' ? null : navigationTail;
    const originalToken = hostDocumentToken;
    let operation: Promise<T>;
    if (prior) {
      let frozen: string;
      try { frozen = JSON.stringify(args); if (frozen.length > 16 * 1024 * 1024) throw new Error('请求过长'); }
      catch { return Promise.reject(new NativeCommandError('请求未能登记，请保留原内容。', 'not_sent')); }
      operation = prior.then(() => {
        if (hostDocumentToken !== originalToken) throw new NativeCommandError('原页面已恢复或变化，排队中的操作没有发送，请重新核对。', 'not_sent');
        return sendCommand<T>(action, JSON.parse(frozen));
      });
    } else operation = sendCommand<T>(action, args);
    if (navigationActions.has(action)) {
      const settled = operation.then(() => {}, () => {});
      navigationTail = settled;
      void settled.then(() => { if (navigationTail === settled) navigationTail = null; });
    }
    return operation;
  },
};

window.PocketWeb = {
  receive,
  handleBack() { return Boolean(backHandler?.()); },
  hostConfiguration(value) {
    acceptHostDocumentToken(value.documentToken);
    const { documentToken: _documentToken, ...presentation } = value;
    current = { ...current, host: { ...current.host, ...presentation } };
    if (typeof value.keyboardVisible === 'boolean') {
      document.documentElement.dataset.pocketKeyboard = String(value.keyboardVisible);
    }
    publish();
  },
};
