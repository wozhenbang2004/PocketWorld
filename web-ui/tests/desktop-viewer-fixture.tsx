/** Test-only owner host. ?transport=rtc uses WebRTC; default explicitly mocks RTC and carries a real canvas MediaStream. */
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
let pendingOpen: (() => void) | undefined;
const clone = (value: any) => JSON.parse(JSON.stringify(value));
const receipt = (value: any) => ({ value: clone(value) });
const realRtc = new URLSearchParams(location.search).get('transport') === 'rtc';
const peers = new Map<string, FixturePeer>();
let peerSequence = 0;

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
if (!realRtc) (window as any).RTCPeerConnection = FixturePeer;

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
  c.fillStyle = '#dce2eb'; c.fillRect(0, 750, 1280, 50); c.fillStyle = '#697891'; c.font = '17px monospace'; c.fillText(realRtc ? 'Fixture desktop — browser WebRTC transport' : 'Fixture desktop — MOCK RTC / real canvas MediaStream', 36, 782);
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
    sessions.set(sessionId, { core, pc: null, timer: 0, closed: false, frames: 0, inputs: 0, text: '' });
    return receipt(core);
  }
  const state = sessions.get(args.sessionId); if (!state || state.closed && action !== 'devices.desktop.close') throw new Error('测试会话已关闭');
  if (action === 'devices.desktop.close') {
    state.closed = true; clearInterval(state.timer); state.pc?.close(); state.track?.stop(); return receipt({ closed: true, sessionId: args.sessionId });
  }
  if (action === 'devices.desktop.negotiate') {
    const canvas = document.createElement('canvas'); canvas.width = 1280; canvas.height = 800;
    draw(canvas, state); const media = canvas.captureStream(12); state.track = media.getVideoTracks()[0];
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
        const data = JSON.stringify({ type: 'frame', streamId: incorrectFrame ? 'another-stream' : state.core.streamId,
          frameId: 'frame-' + state.frames, width: 1280, height: 800, geometryRevision: 'fixture-geometry', capturedAt: Date.now(), expiresAt: Date.now() + 3000 });
        if (realRtc) state.channel.send(data); else state.channel.onmessage?.({ data });
      }
    }, 80);
    if (realRtc) { const rtc = pc as RTCPeerConnection; await rtc.setRemoteDescription(args.offer); await rtc.setLocalDescription(await rtc.createAnswer()); await gathered(rtc); }
    return receipt({ ...state.core, answer: { type: 'answer', sdp: realRtc ? pc.localDescription!.sdp : 'v=0\r\na=explicit-mock-rtc' } });
  }
  if (action === 'devices.desktop.status') return receipt(state.core);
  if (action === 'devices.desktop.control') {
    if (args.expectedRevision !== state.core.control.revision) throw new Error('测试控制版本已变化');
    state.core.control = { owner: args.action === 'take' ? 'human' : 'none', revision: state.core.control.revision + 1,
      ...(args.action === 'take' ? { lease: { id: 'lease-' + args.sessionId, fence: state.core.control.revision + 1 } } : {}) };
    return receipt(state.core);
  }
  if (action === 'devices.desktop.input') {
    if (state.core.control.owner !== 'human' || args.controlLease?.id !== state.core.control.lease?.id || args.frameRef?.streamId !== state.core.streamId) throw new Error('测试输入缺少准确控制权');
    state.inputs++; if (args.input.kind === 'insert_text') state.text = args.input.text;
    return receipt({ ...state.core, inputReceipt: { status: unknownInput ? 'unknown' : 'completed', inputDispatched: true } });
  }
  throw new Error('Unknown fixture command: ' + action);
};

Object.assign(window, { desktopFixture: { calls, active: () => [...sessions.values()].filter(s => !s.closed).length,
  setUnknown: (value: boolean) => { unknownInput = value; }, freeze: (value: boolean) => { freeze = value; }, badFrame: () => { incorrectFrame = true; },
  delayOpen: (value: boolean) => { delayedOpen = value; }, settleOpen: () => { pendingOpen?.(); pendingOpen = undefined; }, back: () => false } });

function Fixture() {
  const [show, setShow] = useState(true);
  Object.assign((window as any).desktopFixture, { unmount: () => setShow(false), remount: () => setShow(true) });
  return <main style={{ maxWidth: 880, margin: '0 auto', padding: '18px 16px', minHeight: '100vh', background: '#fafafa' }}>
    <p style={{ fontSize: 10, letterSpacing: '.06em', color: '#8a8c92', margin: '0 0 12px' }}>{realRtc ? 'BROWSER WEBRTC FIXTURE' : 'MOCK RTC · REAL CANVAS MEDIASTREAM'} · 非 Windows 实机</p>
    {show && <DevicesWorkspace state={{ tasks: [], webWorkspaceResumeRevision: 0, deviceWorkspaceRevision: 0 }} data={snapshot()} supported={() => true} run={run}
      registerTransientBack={handler => { (window as any).desktopFixture.back = handler || (() => false); }} />}
  </main>;
}
createRoot(document.getElementById('root')!).render(<Fixture />);
