import { useSyncExternalStore } from 'react';

export type PocketSnapshot = {
  revision?: number;
  state: Record<string, any>;
  composer: Record<string, any> | null;
  capabilities: { commands: string[] };
  host?: { connected?: boolean; [key: string]: unknown };
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
let documentBound = false;
let advertisedCommands: string[] = [];
let backHandler: (() => boolean) | null = null;
const listeners = new Set<() => void>();
const pending = new Map<string, { action: string; resolve(value: any): void; reject(error: Error): void; timeout: ReturnType<typeof setTimeout> }>();

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
      request.reject(new Error(typeof value === 'string' ? value : value && typeof value === 'object' && 'message' in value ? String(value.message) : '操作未能受理，请保留当前内容并核对原回执。'));
    }
  }
}

export const bridge = {
  getState: () => current,
  subscribe(listener: () => void) { listeners.add(listener); return () => { listeners.delete(listener); }; },
  setBackHandler(handler: (() => boolean) | null) {
    backHandler = handler;
    return () => { if (backHandler === handler) backHandler = null; };
  },
  command<T = any>(action: string, args: Record<string, unknown> = {}): Promise<T> {
    const native = window.PocketNative;
    if (!native || typeof native.postMessage !== 'function') return Promise.reject(new Error('界面尚未连接应用，请保留当前输入。'));
    if (action !== 'app.ready' && action !== 'app.refresh' && !current.capabilities.commands.includes(action)) {
      return Promise.reject(new Error('当前窗口不可执行此操作，请刷新原对象后核对。'));
    }
    const id = `${Date.now().toString(36)}-${++sequence}`;
    return new Promise<T>((resolve, reject) => {
      const timeout = setTimeout(() => {
        pending.delete(id);
        reject(new Error('尚未收到本次请求的受理回执；内容仍保留，请先核对原动作。'));
      }, 30_000);
      pending.set(id, { action, resolve, reject, timeout });
      try {
        const encoded = JSON.stringify({ v: 1, id, documentId, action, args: action === 'app.ready' ? { ...args, documentId } : args });
        if (encoded.length > 16 * 1024 * 1024) throw new Error('当前请求过长，请保留原文件后缩小本次内容。');
        native.postMessage(encoded);
      } catch (error) {
        clearTimeout(timeout);
        pending.delete(id);
        reject(error instanceof Error ? error : new Error('请求未能登记，当前输入仍保留。'));
      }
    });
  },
};

window.PocketWeb = {
  receive,
  handleBack() { return Boolean(backHandler?.()); },
  hostConfiguration(value) {
    current = { ...current, host: { ...current.host, ...value } };
    publish();
  },
};

export function usePocketSnapshot() {
  return useSyncExternalStore(bridge.subscribe, bridge.getState, bridge.getState);
}
