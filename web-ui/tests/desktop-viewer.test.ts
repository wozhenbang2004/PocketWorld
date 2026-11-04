import test from 'node:test';
import assert from 'node:assert/strict';
import { DesktopViewerConnection, desktopFrame, desktopPoint, desktopScopes, desktopSession, desktopText, desktopValue, waitForDesktopIce } from '../src/desktop-viewer.ts';

const now = 1_000_000;
const binding = { kind: 'desktop' as const, sessionId: 1, displayId: 'primary', windowId: 456 };
const scope = () => ({ key: 'grant:1:primary:456', grantId: 'grant', resourceBinding: { ...binding }, label: '已授权窗口', available: true, reason: '', expiresAt: now + 600_000 });
const session = (sessionId = 'view-1', streamId?: string) => ({ kind: 'desktopSession', deviceId: 'pc-1', sessionId, resourceBinding: { ...binding }, expiresAt: now + 60_000,
  width: 1280, height: 800, control: { owner: 'none', revision: 0 }, ...(streamId ? { streamId } : {}) });
const frame = (streamId = 'stream-view-1') => ({ type: 'frame', streamId, frameId: 'frame-1', width: 1280, height: 800, geometryRevision: 'geometry-1', capturedAt: now, expiresAt: now + 3000 });

test('viewer receipts require a native result and the same device, session, resource, control and stream', () => {
  assert.throws(() => desktopValue({ accepted: true, requestId: 'queued' }));
  const original = session();
  assert.equal(desktopSession(original, { deviceId: 'pc-1', resourceBinding: binding }, now).sessionId, 'view-1');
  for (const change of [{ kind: 'accepted' }, { deviceId: 'pc-2' }, { expiresAt: now }, { width: 0 }, { height: '800' }, { resourceBinding: { ...binding, windowId: 789 } }, { control: { owner: 'human', revision: 1 } }, { control: { owner: 'none', revision: '0' } }]) {
    assert.throws(() => desktopSession({ ...original, ...change }, { deviceId: 'pc-1', resourceBinding: binding }, now));
  }
  assert.throws(() => desktopSession({ ...original, sessionId: 'successor' }, { deviceId: 'pc-1', sessionId: 'view-1', resourceBinding: binding }, now));
  assert.throws(() => desktopSession({ ...original, streamId: 'other' }, { deviceId: 'pc-1', streamId: 'stream-1', resourceBinding: binding }, now));
  assert.throws(() => desktopSession({ ...original, iceServers: [{ urls: 'https://unexpected.example' }] }, { deviceId: 'pc-1', resourceBinding: binding }, now));
  assert.deepEqual(desktopSession({ ...original, iceServers: [{ urls: ['turn:relay.example:3478'], username: 'bounded', credential: 'ephemeral' }] }, { deviceId: 'pc-1', resourceBinding: binding }, now).iceServers, [{ urls: ['turn:relay.example:3478'], username: 'bounded', credential: 'ephemeral' }]);
  const result = desktopSession(original, { deviceId: 'pc-1', resourceBinding: binding }, now);
  result.resourceBinding.windowId = 999;
  assert.equal(original.resourceBinding.windowId, 456, 'A returned projection cannot mutate a native receipt');
});

test('only explicit desktop.viewer grants connect; old and expired grants never gain authority from the UI', () => {
  const peer = { receivedGrantScopes: [
    { grantId: 'old', capabilities: ['tool.invoke'], toolNames: ['desktop.observe'], resources: [binding], expiresAt: now + 1000 },
    { grantId: 'current', capabilities: ['desktop.viewer'], resources: [binding], expiresAt: now + 1000 },
    { grantId: 'expired', capabilities: ['desktop.viewer'], resources: [binding], expiresAt: now - 1 },
    { grantId: 'phone', capabilities: ['desktop.viewer'], resources: [{ kind: 'phone' }], expiresAt: now + 1000 },
    { grantId: 'extra', capabilities: ['desktop.viewer'], resources: [{ ...binding, allowedAnything: true }], expiresAt: now + 1000 },
  ] };
  const options = desktopScopes(peer, now);
  assert.deepEqual(options.map(item => [item.grantId, item.available]), [['old', false], ['current', true], ['expired', false]]);
  options[1].resourceBinding.windowId = 222;
  assert.equal(peer.receivedGrantScopes[1].resources[0].windowId, 456);
  assert.equal(desktopScopes({ ...peer, revoked: true }, now).some(option => option.available), false);
});

test('pixel mapping rejects letterbox margins and correctly handles portrait, zoom and fractional device scaling', () => {
  const bounds = { left: 20, top: 50, width: 360, height: 320 };
  assert.deepEqual(desktopPoint(200, 210, bounds, 1280, 800), { x: 640, y: 400 });
  assert.equal(desktopPoint(200, 80, bounds, 1280, 800), null);
  assert.equal(desktopPoint(19, 200, bounds, 1280, 800), null);
  assert.equal(desktopPoint(380, 200, bounds, 1280, 800), null);
  assert.deepEqual(desktopPoint(0, 225, { left: -180, top: 0, width: 720, height: 450 }, 1280, 800), { x: 320, y: 400 });
  assert.deepEqual(desktopPoint(100.25, 200.5, { left: .25, top: .5, width: 200, height: 400 }, 400, 800), { x: 200, y: 400 });
  assert.equal(desktopPoint(1, 1, { left: 0, top: 0, width: 0, height: 20 }, 1280, 800), null);
  assert.equal(desktopPoint(NaN, 1, bounds, 1280, 800), null);
});

test('frame identities are bounded and matched to the negotiated stream; UTF-8 text is bounded before sending', () => {
  const wire = desktopFrame(frame(), 'stream-view-1');
  assert.equal('type' in wire, false);
  for (const change of [{ streamId: 'other' }, { width: -1 }, { geometryRevision: '' }, { expiresAt: now + 60_000 }, { frameId: 'line\nbreak' }]) assert.throws(() => desktopFrame({ ...frame(), ...change }, 'stream-view-1'));
  assert.equal(desktopText('中文与 emoji 😀'), '中文与 emoji 😀');
  assert.equal(desktopText('中'.repeat(4000)).length, 4000);
  assert.throws(() => desktopText('中'.repeat(4001)));
  assert.throws(() => desktopText(''));
  assert.throws(() => desktopText('invalid\0text'));
});

class Peer extends EventTarget {
  iceGatheringState = 'complete'; connectionState = 'new'; localDescription: { type: string; sdp: string } | null = null;
  ontrack: any = null; onconnectionstatechange: any = null;
  channel: { label: string; onmessage: any } | null = null; closed = false;
  addTransceiver(kind: string, options: any) { assert.equal(kind, 'video'); assert.equal(options.direction, 'recvonly'); }
  createDataChannel(label: string, options: any) { assert.equal(options.ordered, true); this.channel = { label, onmessage: null }; return this.channel; }
  async createOffer() { return { type: 'offer', sdp: 'v=0\r\nfixture-offer' }; }
  async setLocalDescription(description: any) { this.localDescription = description; }
  async setRemoteDescription(description: any) { assert.equal(description.type, 'answer'); this.connectionState = 'connected'; this.onconnectionstatechange?.(); }
  close() { this.closed = true; this.connectionState = 'closed'; }
}

test('ICE gathering is bounded and cancellation does not leave pending listeners', async () => {
  const peer = new Peer(); const abort = new AbortController();
  await waitForDesktopIce(peer as any, abort.signal, 100);
  peer.iceGatheringState = 'gathering';
  const pending = waitForDesktopIce(peer as any, abort.signal, 100);
  abort.abort(); await assert.rejects(pending, /关闭/);
  await assert.rejects(waitForDesktopIce(peer as any, new AbortController().signal, 5), /超时/);
  const late = waitForDesktopIce(peer as any, new AbortController().signal, 100);
  peer.iceGatheringState = 'complete'; peer.dispatchEvent(new Event('icegatheringstatechange')); await late;
});

function harness() {
  let clock = now, sequence = 0, unknown = false;
  const sessions = new Map<string, any>(), calls: { action: string; args: any }[] = [], peers: Peer[] = [];
  const pending: { action: string; args: any; resolve: (value: any) => void }[] = [];
  const delayed = new Set<string>();
  const response = (action: string, args: any): any => {
    if (action === 'devices.desktop.open') { const value = { ...session('view-' + ++sequence), resourceBinding: structuredClone(args.resourceBinding) }; sessions.set(value.sessionId, value); return { value }; }
    const value = sessions.get(args.sessionId); assert.ok(value, 'Only the exact native session handle is used');
    if (action === 'devices.desktop.close') return { value: { closed: true, sessionId: args.sessionId } };
    if (action === 'devices.desktop.negotiate') { value.streamId = 'stream-' + value.sessionId; return { value: { ...value, answer: { type: 'answer', sdp: 'v=0\r\nfixture-answer' } } }; }
    if (action === 'devices.desktop.control') {
      assert.equal(args.expectedRevision, value.control.revision);
      value.control = { owner: args.action === 'take' ? 'human' : 'none', revision: value.control.revision + 1,
        ...(args.action === 'take' ? { lease: { id: 'lease-' + value.sessionId, fence: value.control.revision + 1 } } : {}) };
      return { value };
    }
    if (action === 'devices.desktop.input') return { value: { ...value, inputReceipt: { status: unknown ? 'unknown' : 'completed', inputDispatched: true } } };
    if (action === 'devices.desktop.status') return { value };
    throw new Error(action);
  };
  const owner = new DesktopViewerConnection({ run: async (action, args = {}) => {
    calls.push({ action, args: structuredClone(args) });
    if (delayed.has(action)) return new Promise(resolve => pending.push({ action, args, resolve }));
    return structuredClone(response(action, args));
  }, onChange: () => {}, onStream: () => {}, createPeer: () => { const peer = new Peer(); peers.push(peer); return peer as any; }, clock: () => clock });
  const fresh = (geometryRevision = 'geometry-1') => { clock++; owner.receiveFrame({ ...frame(owner.state.session!.streamId), frameId: 'frame-' + clock, geometryRevision, capturedAt: clock, expiresAt: clock + 3000 }); owner.decodedFrame(1280, 800); };
  const settle = (action: string, override?: any) => { const index = pending.findIndex(item => item.action === action); assert.notEqual(index, -1); const [item] = pending.splice(index, 1); item.resolve(structuredClone(override || response(item.action, item.args))); };
  return { owner, calls, peers, pending, delayed, sessions, fresh, settle, advance: (ms: number) => { clock += ms; }, uncertain: () => { unknown = true; } };
}

test('no input is sent before a decoded fresh frame and a confirmed human lease; no input goes over datachannel', async () => {
  const h = harness();
  try {
    await h.owner.open('pc-1', scope());
    assert.equal(h.owner.state.phase, 'live');
    assert.equal(h.peers[0].channel?.label, 'pocketworld.desktop.frames');
    await h.owner.control('take');
    assert.equal(h.calls.filter(call => call.action === 'devices.desktop.control').length, 0);
    h.owner.receiveFrame(frame()); assert.equal(h.owner.canInput(), false);
    h.owner.decodedFrame(800, 1280); assert.equal(h.owner.canInput(), false);
    h.fresh(); await h.owner.control('take'); assert.equal(h.owner.canInput(), false, 'Take consumes earlier frame references'); h.fresh(); assert.equal(h.owner.canInput(), true);
    assert.equal(await h.owner.input({ kind: 'click', x: 640, y: 400, button: 'left', count: 1 }), true);
    const input = h.calls.find(call => call.action === 'devices.desktop.input')!;
    assert.equal(input.args.sessionId, 'view-1'); assert.equal(input.args.controlLease.id, 'lease-view-1');
    assert.equal(input.args.frameRef.streamId, 'stream-view-1'); assert.equal('type' in input.args.frameRef, false);
    h.advance(2001); assert.equal(h.owner.canInput(), false);
    const count = h.calls.length;
    assert.equal(await h.owner.input({ kind: 'press_key', key: 'enter' }), false); assert.equal(h.calls.length, count);
  } finally { h.owner.dispose(); }
});

test('an unknown input result is not replayed and keeps subsequent input disabled until explicit control recovery', async () => {
  const h = harness();
  try {
    await h.owner.open('pc-1', scope()); h.fresh(); await h.owner.control('take'); h.fresh(); h.uncertain();
    assert.equal(await h.owner.input({ kind: 'insert_text', text: '请核对已输入的中文' }), false);
    assert.equal(h.owner.state.inputUncertain, true); assert.equal(h.owner.state.phase, 'live');
    assert.equal(await h.owner.input({ kind: 'insert_text', text: '请核对已输入的中文' }), false);
    await h.owner.status(); assert.equal(h.owner.canInput(), false, 'Status polling cannot certify an unknown input');
    assert.equal(h.calls.filter(call => call.action === 'devices.desktop.input').length, 1);
    await h.owner.control('release'); assert.equal(h.owner.state.session?.control.owner, 'human', 'Unknown native results require close/reopen, never guessed lease release');
    assert.equal(h.calls.filter(call => call.action === 'devices.desktop.control').length, 1);
  } finally { h.owner.dispose(); }
});

test('late open and input replies cannot attach a retired session or clear successor input ownership', async () => {
  const h = harness();
  try {
    h.delayed.add('devices.desktop.open');
    const old = h.owner.open('pc-1', scope()); h.owner.close();
    h.delayed.delete('devices.desktop.open'); const replacement = h.owner.open('pc-1', scope());
    assert.equal(h.calls.filter(call => call.action === 'devices.desktop.open').length, 1, 'Replacement waits for the original native lane');
    h.settle('devices.desktop.open'); await old; await replacement;
    assert.equal(h.owner.state.session?.sessionId, 'view-2');
    assert.ok(h.calls.some(call => call.action === 'devices.desktop.close' && call.args.sessionId === 'view-1'));
    h.fresh(); await h.owner.control('take'); h.fresh();
    h.delayed.add('devices.desktop.input'); const input = h.owner.input({ kind: 'press_key', key: 'enter' });
    assert.equal(await h.owner.input({ kind: 'press_key', key: 'enter' }), false);
    const reopened = h.owner.open('pc-1', scope());
    h.settle('devices.desktop.input'); assert.equal(await input, false); await reopened;
    h.fresh(); await h.owner.control('take'); h.fresh();
    const nextInput = h.owner.input({ kind: 'press_key', key: 'tab' });
    assert.equal(h.owner.state.session?.sessionId, 'view-3'); assert.equal(h.owner.state.inputPending, true);
    h.settle('devices.desktop.input'); assert.equal(await nextInput, true);
    assert.equal(h.owner.state.inputPending, false);
  } finally { h.owner.dispose(); }
});

test('queued acknowledgements never turn into a human lease or a successful keyboard action', async () => {
  const h = harness();
  try {
    await h.owner.open('pc-1', scope()); h.fresh();
    h.delayed.add('devices.desktop.control'); const take = h.owner.control('take');
    h.settle('devices.desktop.control', { accepted: true, requestId: 'queued' }); await take;
    assert.equal(h.owner.state.session?.control.owner, 'none'); assert.equal(h.owner.canInput(), false);
    assert.equal(h.owner.state.inputUncertain, true);
  } finally { h.owner.dispose(); }
});

test('whole desktop scroll requires an explicit point or the same lease and geometry last successful click', async () => {
  const h = harness();
  const desktopScope = { ...scope(), resourceBinding: { kind: 'desktop' as const, sessionId: 1, displayId: 'primary' } };
  const scroll = { kind: 'scroll' as const, direction: 'down' as const, amount: 3, by: 'line' as const };
  try {
    await h.owner.open('pc-1', desktopScope); h.fresh(); await h.owner.control('take'); h.fresh();
    assert.equal(h.owner.canScroll(), false);
    assert.equal(await h.owner.input(scroll), false);
    assert.equal(h.calls.filter(call => call.action === 'devices.desktop.input').length, 0);
    assert.equal(await h.owner.input({ kind: 'click', x: 120, y: 250, button: 'left', count: 1 }), true);
    assert.equal(h.owner.canInput(), false, 'A completed input consumes its frame reference');
    h.fresh(); assert.equal(h.owner.canScroll(), true);
    assert.equal(await h.owner.input(scroll), true);
    assert.deepEqual(h.calls.filter(call => call.action === 'devices.desktop.input').at(-1)!.args.input, { ...scroll, x: 120, y: 250 });
    h.fresh('geometry-2'); assert.equal(h.owner.canScroll(), false);
    assert.equal(await h.owner.input(scroll), false, 'Moved windows never reuse a stale click point');
    assert.equal(await h.owner.input({ ...scroll, x: 700, y: 200 }), true, 'Wheel/swipe supplies its current exact visible pointer');
    h.fresh(); await h.owner.control('release'); h.fresh(); await h.owner.control('take'); h.fresh();
    assert.equal(h.owner.canScroll(), false, 'A new human lease never inherits the previous lease click');
  } finally { h.owner.dispose(); }
});

test('user input waits for a pending status read and rechecks the same session before dispatch', async () => {
  const h = harness();
  try {
    await h.owner.open('pc-1', scope()); h.fresh(); await h.owner.control('take'); h.fresh();
    h.delayed.add('devices.desktop.status');
    const status = h.owner.status(); const input = h.owner.input({ kind: 'press_key', key: 'tab' });
    assert.equal(h.calls.filter(call => call.action === 'devices.desktop.input').length, 0);
    h.settle('devices.desktop.status'); await status; assert.equal(await input, true);
    h.fresh(); const oldStatus = h.owner.status(); const staleInput = h.owner.input({ kind: 'press_key', key: 'enter' });
    h.owner.close(); h.settle('devices.desktop.status'); await oldStatus;
    assert.equal(await staleInput, false, 'Closing while waiting never dispatches an input afterwards');
    assert.equal(h.calls.filter(call => call.action === 'devices.desktop.input').length, 1);
  } finally { h.owner.dispose(); }
});
