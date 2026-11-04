/**
 * Test-only owner host. Default: MOCK RTC + MOCK video-frame callbacks, with
 * canvas pixels only for layout/playback. Synthetic RTP pairs exercise the UI
 * contract; they are not evidence of a real encoded/decoded frame association.
 * ?transport=rtc leaves the browser transport/callbacks real. Its canvas sender
 * has no RTP-to-capture mapping, so that mode intentionally remains watch-only.
 */
import { useState } from 'react';
import { createRoot } from 'react-dom/client';
import { DevicesWorkspace } from '../src/DevicesWorkspace';
import '../src/fonts.css';
import '../src/app.css';
import '../src/library.css';
import '../src/secondary.css';
import '../src/product.css';
import '../src/workspace-refactor.css';
import '../src/presentation-theme.css';

const resourceBinding = { kind: 'desktop', sessionId: 1, displayId: 'primary', windowId: 456 };
const expiresAt = Date.now() + 900_000;
const peer = { deviceId: 'fixture-computer', label: '书桌上的电脑', platform: 'Windows · 测试画面', connection: { state: 'ONLINE', path: 'DIRECT_TLS' },
  receivedGrantScopes: [{ grantId: 'fixture-grant', capabilities: ['desktop.viewer'], resources: [resourceBinding], expiresAt }], grantScopes: [], actions: {} };
const snapshot = () => ({ format: 'pocketworld.device-workspace.v1', capturedAt: Date.now(), local: { receiverEnabled: false }, peers: [peer], pendingPairings: [], taskLinks: { links: [] }, actions: {} });
const sessions = new Map<string, any>(), calls: { action: string; args: any }[] = [];
let sequence = 0, unknownInput = false, freeze = false, incorrectFrame = false, delayedOpen = false;
type FixtureIdentity = 'exact' | 'mismatch' | 'missing-metadata' | 'missing-decoded';
let frameIdentity: FixtureIdentity = 'exact';
let pendingOpen: (() => void) | undefined;
const clone = (value: any) => JSON.parse(JSON.stringify(value));
const receipt = (value: any) => ({ value: clone(value) });
const realRtc = new URLSearchParams(location.search).get('transport') === 'rtc';
const peers = new Map<string, FixturePeer>();
let peerSequence = 0;
let callbackSequence = 0;
const decodedCallbacks = new Map<number, { element: HTMLVideoElement; callback: VideoFrameRequestCallback }>();
const frameTrace: { kind: 'metadata' | 'decoded'; sessionId: string; frameId: string; rtpTimestamp?: number }[] = [];
const trace = (entry: typeof frameTrace[number]) => { frameTrace.push(entry); if (frameTrace.length > 128) frameTrace.shift(); };

/** Only installed in this test entrypoint. It never falls back inside production code. */
class FixturePeer extends EventTarget {
  id = String(++peerSequence); iceGatheringState = 'complete'; connectionState = 'new';
  localDescription: any = null; ontrack: any = null; onconnectionstatechange: any = null;
  media?: MediaStream;
  channel: any = null;
  constructor(_config: unknown) { super(); peers.set(this.id, this); }
  addTransceiver() {}
  createDataChannel(label: string) { this.channel = { label, readyState: 'connecting', onmessage: null }; return this.channel; }
  async createOffer() { return { type: 'offer', sdp: 'v=0\r\na=fixture-peer:' + this.id }; }
  async setLocalDescription(description: any) { this.localDescription = description; }
  async setRemoteDescription() {
    this.connectionState = 'connected'; this.channel.readyState = 'open'; this.onconnectionstatechange?.();
    if (this.media) this.ontrack?.({ track: this.media.getVideoTracks()[0], streams: [this.media] });
  }
  close() { this.connectionState = 'closed'; if (this.channel) this.channel.readyState = 'closed'; peers.delete(this.id); }
}
if (!realRtc) {
  (window as any).RTCPeerConnection = FixturePeer;
  // Deliberately synthetic callbacks, isolated to this test entrypoint. Never
  // invent a timestamp on a real browser callback or install this in production.
  HTMLVideoElement.prototype.requestVideoFrameCallback = function(callback) {
    const handle = ++callbackSequence; decodedCallbacks.set(handle, { element: this, callback }); return handle;
  };
  HTMLVideoElement.prototype.cancelVideoFrameCallback = handle => { decodedCallbacks.delete(handle); };
}

function decodedFixtureFrame(state: any, reference: any, timestamp: number) {
  if (realRtc) return;
  const rtpTimestamp = frameIdentity === 'missing-decoded' ? undefined : frameIdentity === 'mismatch' ? (timestamp + 1) >>> 0 : timestamp;
  for (const [handle, { element, callback }] of [...decodedCallbacks]) {
    if (element.srcObject !== state.media || element.paused || element.readyState < 2) continue;
    decodedCallbacks.delete(handle);
    trace({ kind: 'decoded', sessionId: state.core.sessionId, frameId: reference.frameId, rtpTimestamp });
    callback(performance.now(), { width: 1280, height: 800, mediaTime: state.frames / 10, presentedFrames: state.frames,
      presentationTime: performance.now(), expectedDisplayTime: performance.now(), ...(rtpTimestamp === undefined ? {} : { rtpTimestamp }) });
  }
}

function draw(canvas: HTMLCanvasElement, state: any) {
  const c = canvas.getContext('2d')!;
  c.fillStyle = '#e9edf4'; c.fillRect(0, 0, 1280, 800);
  c.fillStyle = '#737d90'; c.font = '16px sans-serif'; c.fillText('SYNTHETIC MEDIA FIXTURE · NOT A REAL WINDOWS DESKTOP', 36, 36);
  c.fillStyle = '#fff'; c.beginPath(); c.roundRect(136, 105, 1008, 570, 18); c.fill();
  c.fillStyle = '#f4f5f7'; c.beginPath(); c.roundRect(136, 105, 1008, 52, [18, 18, 0, 0]); c.fill();
  c.fillStyle = '#303746'; c.font = '19px sans-serif'; c.fillText('PocketWorld · 桌面连接测试', 163, 139);
  c.font = '32px sans-serif'; c.fillText('在手机上看见同一个工作区', 183, 240);
  c.fillStyle = '#747e8c'; c.font = '20px sans-serif'; c.fillText('图像来自浏览器画布，仅用于验证播放器与控制接线。', 184, 284);
  c.fillStyle = '#f1f3f6'; c.beginPath(); c.roundRect(181, 325, 913, 190, 12); c.fill();
  c.fillStyle = '#414c5d'; c.font = '23px sans-serif'; c.fillText(state.text || '在手机键盘输入中文，核对发送结果…', 205, 382);
  c.fillStyle = '#98a5b7'; c.fillRect(185, 566, 600, 5); c.fillStyle = '#596b86'; c.fillRect(185, 566, (Date.now() / 25) % 600, 5);
  c.fillStyle = '#748198'; c.font = '17px monospace'; c.fillText(`FRAME ${state.frames}    INPUTS ${state.inputs}`, 185, 615);
  c.fillStyle = '#dce2eb'; c.fillRect(0, 750, 1280, 50); c.fillStyle = '#697891'; c.font = '17px monospace'; c.fillText(realRtc ? 'Browser WebRTC — WATCH ONLY / no sender RTP identity' : 'MOCK RTC + MOCK frame identity / canvas layout fixture', 36, 782);
}

async function gathered(pc: RTCPeerConnection) {
  if (pc.iceGatheringState === 'complete') return;
  await new Promise<void>((resolve, reject) => {
    const done = () => { if (pc.iceGatheringState === 'complete') { clearTimeout(timer); pc.removeEventListener('icegatheringstatechange', done); resolve(); } };
    const timer = setTimeout(() => { pc.removeEventListener('icegatheringstatechange', done); reject(new Error('Fixture ICE timeout')); }, 6000);
    pc.addEventListener('icegatheringstatechange', done); done();
  });
}

const run = async (action: string, args: any = {}) => {
  calls.push({ action, args: clone(args) });
  if (action === 'devices.snapshot') return { snapshot: snapshot(), value: {} };
  if (action === 'devices.discard') return { discarded: true };
  if (action === 'devices.desktop.open') {
    if (delayedOpen) await new Promise<void>(resolve => { pendingOpen = resolve; });
    const sessionId = 'fixture-view-' + ++sequence;
    const core = { kind: 'desktopSession', sessionId, deviceId: args.deviceId, resourceBinding: args.resourceBinding, expiresAt, width: 1280, height: 800, control: { owner: 'none', revision: 0 }, iceServers: [] };
    sessions.set(sessionId, { core, pc: null, timer: 0, closed: false, frames: 0, inputs: 0, text: '', references: new Map<string, any>() });
    return receipt(core);
  }
  const state = sessions.get(args.sessionId); if (!state || state.closed && action !== 'devices.desktop.close') throw new Error('测试会话已关闭');
  if (action === 'devices.desktop.close') {
    state.closed = true; state.references.clear(); clearInterval(state.timer); state.pc?.close(); state.track?.stop(); return receipt({ closed: true, sessionId: args.sessionId });
  }
  if (action === 'devices.desktop.negotiate') {
    const canvas = document.createElement('canvas'); canvas.width = 1280; canvas.height = 800;
    draw(canvas, state); const media = canvas.captureStream(10); state.media = media; state.track = media.getVideoTracks()[0];
    const pc = realRtc ? new RTCPeerConnection({ iceServers: [] }) : peers.get(args.offer.sdp.split('a=fixture-peer:')[1])!;
    state.pc = pc;
    if (realRtc) (pc as RTCPeerConnection).addTrack(state.track, media);
    else (pc as FixturePeer).media = media;
    state.core.streamId = 'fixture-stream-' + args.sessionId;
    if (realRtc) (pc as RTCPeerConnection).ondatachannel = event => {
      state.channel = event.channel;
      event.channel.onmessage = () => { throw new Error('Inputs must never use the datachannel'); };
    };
    else state.channel = (pc as FixturePeer).channel;
    state.timer = window.setInterval(() => {
      if (freeze || state.closed) return;
      state.frames++; draw(canvas, state);
      if (state.channel?.readyState === 'open') {
        const capturedAt = Date.now(), rtpTimestamp = (0x10203040 + state.frames * 9000) >>> 0;
        const reference = { streamId: incorrectFrame ? 'another-stream' : state.core.streamId,
          frameId: state.core.sessionId + '-frame-' + state.frames, width: 1280, height: 800, geometryRevision: 'fixture-geometry', capturedAt, expiresAt: capturedAt + 2000,
          ...(!realRtc && frameIdentity !== 'missing-metadata' ? { rtpTimestamp } : {}) };
        state.references.set(reference.frameId, reference);
        while (state.references.size > 32) state.references.delete(state.references.keys().next().value);
        trace({ kind: 'metadata', sessionId: state.core.sessionId, frameId: reference.frameId, rtpTimestamp: reference.rtpTimestamp });
        const data = JSON.stringify({ type: 'frame', ...reference });
        if (realRtc) state.channel.send(data); else state.channel.onmessage?.({ data });
        decodedFixtureFrame(state, reference, rtpTimestamp);
      }
    }, 100);
    if (realRtc) { const rtc = pc as RTCPeerConnection; await rtc.setRemoteDescription(args.offer); await rtc.setLocalDescription(await rtc.createAnswer()); await gathered(rtc); }
    return receipt({ ...state.core, answer: { type: 'answer', sdp: realRtc ? pc.localDescription!.sdp : 'v=0\r\na=explicit-mock-rtc' } });
  }
  if (action === 'devices.desktop.status') return receipt(state.core);
  if (action === 'devices.desktop.control') {
    try {
      if (realRtc || state.core.control.owner === 'other_human' || args.action === 'take' && state.core.control.needsReconcile) throw new Error('此测试状态仅可观看');
      if (args.expectedRevision !== state.core.control.revision) throw new Error('测试控制版本已变化');
      state.core.control = { owner: args.action === 'take' ? 'human' : 'none', revision: state.core.control.revision + 1,
        ...(state.core.control.needsReconcile ? { needsReconcile: true } : {}),
        ...(args.action === 'take' ? { lease: { id: 'lease-' + args.sessionId, fence: state.core.control.revision + 1,
          expiresAt: Math.min(Date.now() + 60_000, expiresAt), resource: `windows-session:${state.core.resourceBinding.sessionId}:${state.core.resourceBinding.displayId}` } } : {}) };
      return receipt(state.core);
    } finally { state.references.clear(); }
  }
  if (action === 'devices.desktop.input') {
    try {
      const reference = state.references.get(args.frameRef?.frameId), lease = state.core.control.lease;
      if (realRtc || state.core.control.owner !== 'human' || state.core.control.needsReconcile || !lease || lease.expiresAt <= Date.now()
        || !['id', 'fence', 'expiresAt', 'resource'].every(key => args.controlLease?.[key] === lease[key])) throw new Error('测试输入缺少准确控制权');
      if (!reference || reference.expiresAt <= Date.now() || !Number.isInteger(reference.rtpTimestamp)
        || Object.keys(args.frameRef).length !== Object.keys(reference).length
        || !Object.entries(reference).every(([key, value]) => args.frameRef[key] === value)) throw new Error('测试输入缺少精确 RTP 帧标识');
      if (args.input.kind === 'scroll' && ('x' in args.input || 'y' in args.input)) throw new Error('滚动前台窗口由原生端核对，不接受旧网页坐标');
      state.inputs++; if (args.input.kind === 'insert_text') state.text = args.input.text;
      if (unknownInput) state.core.control.needsReconcile = true;
      return receipt({ ...state.core, inputReceipt: { status: unknownInput ? 'unknown' : 'completed', inputDispatched: true } });
    } finally { state.references.clear(); }
  }
  throw new Error('Unknown fixture command: ' + action);
};

Object.assign(window, { desktopFixture: { calls, active: () => [...sessions.values()].filter(s => !s.closed).length,
  contract: { mode: realRtc ? 'rtc-watch-only' : 'mock-rtc-and-frame-callbacks', realFrameAssociation: false, scrollTarget: 'native-authorized-foreground-window' }, frameTrace,
  setFrameIdentity: (value: FixtureIdentity) => { frameIdentity = value; },
  setOwner: (owner: 'none' | 'agent' | 'other_human') => { for (const state of sessions.values()) if (!state.closed) {
    state.core.control = { owner, revision: state.core.control.revision + 1, ...(state.core.control.needsReconcile ? { needsReconcile: true } : {}) }; state.references.clear();
  } },
  setUnknown: (value: boolean) => { unknownInput = value; }, freeze: (value: boolean) => { freeze = value; }, badFrame: () => { incorrectFrame = true; },
  delayOpen: (value: boolean) => { delayedOpen = value; }, settleOpen: () => { pendingOpen?.(); pendingOpen = undefined; }, back: () => false } });

function Fixture() {
  const [show, setShow] = useState(true);
  Object.assign((window as any).desktopFixture, { unmount: () => setShow(false), remount: () => setShow(true) });
  return <main data-frame-evidence={realRtc ? 'rtc-watch-only' : 'mock-frame-callbacks'} style={{ maxWidth: 880, margin: '0 auto', padding: '18px 16px', minHeight: '100vh', background: '#fafafa' }}>
    <p style={{ fontSize: 10, letterSpacing: '.06em', color: '#8a8c92', margin: '0 0 12px' }}>{realRtc ? 'BROWSER WEBRTC · WATCH ONLY · NO SENDER RTP MAPPING' : 'MOCK RTC + MOCK FRAME CALLBACKS · CANVAS LAYOUT ONLY'} · 非 Windows / Android 实机</p>
    {show && <DevicesWorkspace state={{ tasks: [], webWorkspaceResumeRevision: 0, deviceWorkspaceRevision: 0 }} data={snapshot()} supported={() => true} run={run}
      registerTransientBack={handler => { (window as any).desktopFixture.back = handler || (() => false); }} />}
  </main>;
}
createRoot(document.getElementById('root')!).render(<Fixture />);
