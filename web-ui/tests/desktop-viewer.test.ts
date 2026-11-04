import test from 'node:test';
import assert from 'node:assert/strict';
import { DesktopViewerConnection, desktopFrame, desktopPoint, desktopScopes, desktopSession, desktopText, desktopValue, waitForDesktopIce } from '../src/desktop-viewer.ts';

const now = 1_000_000;
const binding = { kind: 'desktop' as const, sessionId: 1, displayId: 'primary', windowId: 456 };
const scope = () => ({ key: 'grant:1:primary:456', grantId: 'grant', resourceBinding: { ...binding }, label: '已授权窗口', available: true, reason: '', expiresAt: now + 600_000 });
const session = (sessionId = 'view-1', streamId?: string) => ({ kind: 'desktopSession', deviceId: 'pc-1', sessionId, resourceBinding: { ...binding }, expiresAt: now + 60_000,
  width: 1280, height: 800, control: { owner: 'none', revision: 0 }, ...(streamId ? { streamId } : {}) });
const frame = (streamId = 'stream-view-1') => ({ type: 'frame', streamId, frameId: 'initial-frame', width: 1280, height: 800, geometryRevision: 'geometry-1', capturedAt: now, expiresAt: now + 3000, rtpTimestamp: 0x10203040 });
const decode = (owner: DesktopViewerConnection, value: { width: number; height: number; streamId: string; rtpTimestamp?: number }) => owner.decodedFrame(value.width, value.height, value.streamId, value.rtpTimestamp);

test('viewer receipts require a native result and the same device, session, resource, control and stream', () => {
  assert.throws(() => desktopValue({ accepted: true, requestId: 'queued' }));
  const original = session();
  assert.equal(desktopSession(original, { deviceId: 'pc-1', resourceBinding: binding }, now).sessionId, 'view-1');
  for (const change of [{ kind: 'accepted' }, { deviceId: 'pc-2' }, { expiresAt: now }, { width: 0 }, { height: '800' }, { resourceBinding: { ...binding, windowId: 789 } }, { control: { owner: 'human', revision: 1 } }, { control: { owner: 'none', revision: '0' } }, { control: { owner: 'none', revision: 1, needsReconcile: 'true' } }]) {
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

test('another human window is represented without a foreign lease or controller identity', () => {
  const value = { ...session(), control: { owner: 'other_human', revision: 3 } };
  assert.deepEqual(desktopSession(value, { deviceId: 'pc-1', resourceBinding: binding }, now).control, { owner: 'other_human', revision: 3 });
  assert.equal(desktopSession({ ...value, control: { ...value.control, needsReconcile: true } }, { deviceId: 'pc-1', resourceBinding: binding }, now).control.needsReconcile, true);
  for (const extra of [{ lease: { id: 'foreign-lease' } }, { controllerId: 'foreign-viewer' }]) {
    assert.throws(() => desktopSession({ ...value, control: { ...value.control, ...extra } }, { deviceId: 'pc-1', resourceBinding: binding }, now));
  }
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
  for (const rtpTimestamp of [0, 0xffffffff]) assert.equal(desktopFrame({ ...frame(), rtpTimestamp }, 'stream-view-1').rtpTimestamp, rtpTimestamp);
  for (const rtpTimestamp of [undefined, null, -1, 0x100000000, .5, Infinity, NaN, '0', true]) {
    assert.equal(desktopFrame({ ...frame(), rtpTimestamp }, 'stream-view-1').rtpTimestamp, undefined, 'Unverifiable media may be watched but cannot become an input reference');
  }
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
        ...(value.control.needsReconcile ? { needsReconcile: true } : {}),
        ...(args.action === 'take' ? { lease: { id: 'lease-' + value.sessionId, fence: value.control.revision + 1, expiresAt: clock + 30_000, resource: 'windows-session:1:primary' } } : {}) };
      return { value };
    }
    if (action === 'devices.desktop.input') {
      if (unknown) value.control.needsReconcile = true;
      return { value: { ...value, inputReceipt: { status: unknown ? 'unknown' : 'completed', inputDispatched: true } } };
    }
    if (action === 'devices.desktop.status') return { value };
    throw new Error(action);
  };
  const owner = new DesktopViewerConnection({ run: async (action, args = {}) => {
    calls.push({ action, args: structuredClone(args) });
    if (delayed.has(action)) return new Promise(resolve => pending.push({ action, args, resolve }));
    return structuredClone(response(action, args));
  }, onChange: () => {}, onStream: () => {}, createPeer: () => { const peer = new Peer(); peers.push(peer); return peer as any; }, clock: () => clock });
  let frameSerial = 0;
  const nextFrame = (patch: Partial<ReturnType<typeof frame>> = {}) => { clock += 1; const serial = ++frameSerial; return { ...frame(owner.state.session!.streamId), frameId: `frame-${serial}`, rtpTimestamp: 0x10203040 + serial * 9000, capturedAt: clock, expiresAt: clock + 3000, ...patch }; };
  const fresh = () => { const value = nextFrame(); owner.receiveFrame(value); decode(owner, value); return value; };
  const settle = (action: string, override?: any) => { const index = pending.findIndex(item => item.action === action); assert.notEqual(index, -1); const [item] = pending.splice(index, 1); item.resolve(structuredClone(override || response(item.action, item.args))); };
  return { owner, calls, peers, pending, delayed, sessions, nextFrame, fresh, settle, advance: (ms: number) => { clock += ms; }, uncertain: () => { unknown = true; } };
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
    decode(h.owner, { ...frame(), width: 800, height: 1280 }); assert.equal(h.owner.canInput(), false);
    h.fresh(); await h.owner.control('take'); assert.equal(h.owner.canInput(), false); h.fresh(); assert.equal(h.owner.canInput(), true);
    assert.equal(await h.owner.input({ kind: 'click', x: 640, y: 400, button: 'left', count: 1 }), true);
    const input = h.calls.find(call => call.action === 'devices.desktop.input')!;
    assert.equal(input.args.sessionId, 'view-1'); assert.equal(input.args.controlLease.id, 'lease-view-1');
    assert.equal(input.args.frameRef.streamId, 'stream-view-1'); assert.equal('type' in input.args.frameRef, false);
    assert.equal(input.args.frameRef.rtpTimestamp, 0x10203040 + 2 * 9000);
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
    await h.owner.control('release'); assert.equal(h.owner.state.session?.control.owner, 'none');
    assert.equal(h.owner.state.session?.control.needsReconcile, true); assert.equal(h.owner.state.inputUncertain, true);
    h.fresh(); await h.owner.control('take'); assert.equal(h.owner.state.session?.control.owner, 'none', 'An explicit release exits control but does not reconcile an unknown operation');
  } finally { h.owner.dispose(); }
});

test('late open and input replies cannot attach a retired session or clear successor input ownership', async () => {
  const h = harness();
  try {
    h.delayed.add('devices.desktop.open');
    const old = h.owner.open('pc-1', scope()); h.owner.close();
    h.delayed.delete('devices.desktop.open'); const replacement = h.owner.open('pc-1', scope());
    assert.equal(h.calls.filter(call => call.action === 'devices.desktop.open').length, 1, 'Replacement waits for the retired ordinary native lane');
    h.settle('devices.desktop.open'); await old; await replacement;
    assert.equal(h.owner.state.session?.sessionId, 'view-2');
    assert.ok(h.calls.some(call => call.action === 'devices.desktop.close' && call.args.sessionId === 'view-1'));
    h.fresh(); await h.owner.control('take'); h.fresh();
    h.delayed.add('devices.desktop.input'); const input = h.owner.input({ kind: 'press_key', key: 'enter' });
    assert.equal(await h.owner.input({ kind: 'press_key', key: 'enter' }), false);
    const reopened = h.owner.open('pc-1', scope());
    assert.equal(h.owner.state.session, null, 'Closing immediately fences the old frame while its input callback is pending');
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


test('revoked device grants remain visible but cannot open a viewer', async () => {
  const options = desktopScopes({ revoked: true, receivedGrantScopes: [{ grantId: 'grant', resources: [binding], capabilities: ['desktop.viewer'], expiresAt: now + 60_000 }] }, now);
  assert.equal(options.length, 1); assert.equal(options[0].available, false); assert.match(options[0].reason, /撤销/);
  const h = harness();
  try { await h.owner.open('pc-1', options[0]); assert.equal(h.calls.length, 0); assert.equal(h.owner.state.phase, 'failed'); }
  finally { h.owner.dispose(); }
});

test('human lease identity, expiry and exact desktop resource are required; expired leases cannot send input', async () => {
  const lease = { id: 'lease', fence: 1, expiresAt: now + 500, resource: 'windows-session:1:primary' };
  for (const patch of [{ expiresAt: undefined }, { fence: 0 }, { id: '' }, { resource: 'windows-session:2:primary' }]) {
    assert.throws(() => desktopSession({ ...session(), control: { owner: 'human', revision: 1, lease: { ...lease, ...patch } } }, { deviceId: 'pc-1', resourceBinding: binding }, now));
  }
  const h = harness();
  try {
    await h.owner.open('pc-1', scope()); h.fresh(); await h.owner.control('take'); h.fresh();
    h.advance(30_001); h.fresh();
    assert.equal(h.owner.canInput(), false); const count = h.calls.length;
    assert.equal(await h.owner.input({ kind: 'press_key', key: 'enter' }), false); assert.equal(h.calls.length, count);
    await h.owner.status(); assert.equal(h.owner.state.controlExpired, true); assert.equal(h.owner.state.session?.control.owner, 'human');
    await h.owner.control('release'); assert.equal(h.owner.state.controlExpired, false); assert.equal(h.owner.state.session?.control.owner, 'none');
  } finally { h.owner.dispose(); }
});

test('control changes and each attempted input retire their displayed frame, including metadata that arrived in flight', async () => {
  const h = harness();
  try {
    await h.owner.open('pc-1', scope()); const before = h.fresh(); await h.owner.control('take');
    h.owner.receiveFrame(before); decode(h.owner, before);
    assert.equal(h.owner.canInput(), false, 'A duplicate pre-handoff frame cannot re-arm input');
    h.fresh(); h.delayed.add('devices.desktop.input');
    const input = h.owner.input({ kind: 'click', x: 20, y: 30, button: 'left', count: 1 });
    const during = h.fresh(); h.settle('devices.desktop.input'); assert.equal(await input, true);
    assert.equal(h.owner.canInput(), false);
    h.owner.receiveFrame(during); decode(h.owner, during); assert.equal(h.owner.canInput(), false);
    h.fresh(); assert.equal(h.owner.canInput(), true);
    h.delayed.delete('devices.desktop.input');
    assert.equal(await h.owner.input({ kind: 'click', x: 1280, y: 30, button: 'left', count: 1 }), false);
    assert.equal(h.calls.filter(call => call.action === 'devices.desktop.input').length, 1);
  } finally { h.owner.dispose(); }
});

test('metadata requires the current decoded RTP frame from this stream and changed geometry blocks coordinate mapping', async () => {
  const h = harness();
  const bounds = { left: 0, top: 0, width: 1280, height: 800 };
  try {
    await h.owner.open('pc-1', scope());
    decode(h.owner, { ...frame(), rtpTimestamp: frame().rtpTimestamp - 1 }); h.owner.receiveFrame(frame());
    assert.equal(h.owner.state.frameReady, false, 'Old pixels do not certify newly arrived metadata');
    decode(h.owner, { ...frame(), streamId: 'retired-stream' }); assert.equal(h.owner.state.frameReady, false);
    decode(h.owner, frame()); assert.equal(h.owner.state.frameReady, true);
    assert.deepEqual(h.owner.point(640, 400, bounds, 1280, 800), { x: 640, y: 400 });
    assert.equal(h.owner.point(640, 400, bounds, 800, 1280), null);
    const changed = h.nextFrame({ frameId: 'changed', geometryRevision: 'geometry-2' });
    h.owner.receiveFrame(changed);
    assert.equal(h.owner.state.frameReady, false); assert.equal(h.owner.point(640, 400, bounds, 1280, 800), null);
    decode(h.owner, frame()); assert.equal(h.owner.state.frameReady, false);
    decode(h.owner, changed); assert.equal(h.owner.state.frameReady, true);
    h.advance(2001); h.owner.receiveFrame(changed); decode(h.owner, changed);
    assert.equal(h.owner.state.frameReady, false, 'Duplicate metadata cannot prolong a stale frame');
  } finally { h.owner.dispose(); }
});

test('RTP equality supports either channel arriving first and never substitutes a same-size late video frame', async () => {
  const h = harness(), bounds = { left: 0, top: 0, width: 1280, height: 800 };
  try {
    await h.owner.open('pc-1', scope());
    const metadataFirst = h.nextFrame();
    h.owner.receiveFrame(metadataFirst);
    decode(h.owner, { ...metadataFirst, rtpTimestamp: metadataFirst.rtpTimestamp - 1 });
    assert.equal(h.owner.state.frameReady, false); assert.equal(h.owner.point(20, 30, bounds, 1280, 800), null);
    await h.owner.control('take'); assert.equal(h.calls.some(call => call.action === 'devices.desktop.control'), false);
    decode(h.owner, metadataFirst); assert.equal(h.owner.state.frameReady, true);
    await h.owner.control('take'); h.fresh();

    const earlier = h.nextFrame(), videoFirst = h.nextFrame();
    decode(h.owner, videoFirst);
    assert.equal(h.owner.canInput(), false, 'A new decoded timestamp immediately retires the previously matched pixels');
    h.owner.receiveFrame(earlier);
    assert.equal(h.owner.canInput(), false, 'Late metadata for a different frame cannot certify the current pixels');
    assert.equal(await h.owner.input({ kind: 'click', x: 20, y: 30, button: 'left', count: 1 }), false);
    h.owner.receiveFrame(videoFirst);
    assert.equal(h.owner.canInput(), true, 'Metadata can arrive after the exact frame has been decoded');
    const next = h.nextFrame();
    decode(h.owner, next); h.owner.receiveFrame(videoFirst);
    assert.equal(h.owner.canInput(), false, 'A historical match is not an input fallback after the video advances');
    h.owner.receiveFrame(next); assert.equal(h.owner.canInput(), true);
    assert.equal(await h.owner.input({ kind: 'click', x: 20, y: 30, button: 'left', count: 1 }), true);
    const sent = h.calls.filter(call => call.action === 'devices.desktop.input');
    assert.equal(sent.length, 1); assert.equal(sent[0].args.frameRef.rtpTimestamp, next.rtpTimestamp); assert.equal(sent[0].args.frameRef.frameId, next.frameId);
  } finally { h.owner.dispose(); }
});

test('missing or malformed frame identities leave video live, disable input and explicitly report watch-only', async () => {
  const h = harness();
  try {
    await h.owner.open('pc-1', scope()); h.fresh(); await h.owner.control('take'); h.fresh();
    for (const rtpTimestamp of [undefined, null, -1, 0x100000000, 1.5, '123']) {
      const missing = { ...h.nextFrame(), rtpTimestamp };
      h.owner.receiveFrame(missing);
      h.owner.decodedFrame(1280, 800, missing.streamId, 123);
      assert.equal(h.owner.state.phase, 'live'); assert.equal(h.owner.state.frameReady, false); assert.equal(h.owner.state.frameIssue, 'unavailable');
      assert.equal(await h.owner.input({ kind: 'press_key', key: 'enter' }), false);
      h.fresh(); assert.equal(h.owner.canInput(), true); assert.equal(h.owner.state.frameIssue, null);
    }
    for (const rtpTimestamp of [undefined, -1, 0x100000000, NaN]) {
      const current = h.fresh(); h.owner.decodedFrame(current.width, current.height, current.streamId, rtpTimestamp);
      assert.equal(h.owner.state.phase, 'live'); assert.equal(h.owner.canInput(), false); assert.equal(h.owner.state.frameIssue, 'unavailable');
    }
    h.owner.cannotVerifyVideoFrames(); assert.equal(h.owner.state.frameIssue, 'unavailable');
    assert.equal(h.calls.some(call => call.action === 'devices.desktop.input'), false);
    assert.equal(h.calls.some(call => call.action === 'devices.desktop.close'), false);
  } finally { h.owner.dispose(); }
});

test('a persistent RTP mismatch becomes explicit watch-only after a bounded wait and an exact new pair can recover', async () => {
  const h = harness();
  try {
    await h.owner.open('pc-1', scope());
    const value = h.nextFrame(); h.owner.receiveFrame(value);
    decode(h.owner, { ...value, rtpTimestamp: value.rtpTimestamp + 1 });
    assert.equal(h.owner.state.frameIssue, null); assert.equal(h.owner.state.frameReady, false);
    h.advance(5001); decode(h.owner, { ...value, rtpTimestamp: value.rtpTimestamp + 1 });
    assert.equal(h.owner.state.frameIssue, 'unmatched'); assert.equal(h.owner.state.phase, 'live');
    await h.owner.control('take'); assert.equal(h.calls.some(call => call.action === 'devices.desktop.control'), false);
    h.fresh(); assert.equal(h.owner.state.frameIssue, null); assert.equal(h.owner.state.frameReady, true);
  } finally { h.owner.dispose(); }
});

test('metadata is bounded to 32 recent frames, and neither expired receipts nor an old decode can be reused', async () => {
  const h = harness();
  try {
    await h.owner.open('pc-1', scope());
    const values = Array.from({ length: 34 }, () => h.nextFrame());
    for (const value of values) h.owner.receiveFrame(value);
    decode(h.owner, values[0]); assert.equal(h.owner.state.frameReady, false, 'An evicted RTP identity cannot unlock input');
    decode(h.owner, values[2]); assert.equal(h.owner.state.frameReady, true);
    h.advance(2001); decode(h.owner, values[2]); assert.equal(h.owner.state.frameReady, false, 'Decoding an old timestamp does not extend metadata freshness');
    const expired = h.nextFrame(); h.advance(3001);
    h.owner.receiveFrame(expired); decode(h.owner, expired);
    assert.equal(h.owner.state.frameReady, false, 'A receipt that arrives after its absolute expiry is not fresh');
    const lateMetadata = h.nextFrame(); decode(h.owner, lateMetadata); h.advance(2001); h.owner.receiveFrame(lateMetadata);
    assert.equal(h.owner.state.frameReady, false, 'A newly received receipt cannot extend the age of an old decode');
  } finally { h.owner.dispose(); }
});

test('RTP timestamp zero and uint32 wrap are matched by identity rather than numeric ordering', async () => {
  const h = harness();
  try {
    await h.owner.open('pc-1', scope());
    for (const rtpTimestamp of [0xffffffff, 0, 1]) {
      const value = h.nextFrame({ rtpTimestamp }); h.owner.receiveFrame(value); decode(h.owner, value);
      assert.equal(h.owner.state.frameReady, true); assert.equal(h.owner.state.frameIssue, null);
      decode(h.owner, { ...value, rtpTimestamp: 0x7fffffff });
      assert.equal(h.owner.state.frameReady, false);
    }
  } finally { h.owner.dispose(); }
});

test('conflicting references for the same RTP timestamp stay watch-only across handoffs until reconnect', async () => {
  const h = harness();
  try {
    await h.owner.open('pc-1', scope()); const before = h.fresh(); await h.owner.control('take');
    const conflict = h.nextFrame({ rtpTimestamp: before.rtpTimestamp }); h.owner.receiveFrame(conflict); decode(h.owner, conflict);
    assert.equal(h.owner.state.phase, 'live'); assert.equal(h.owner.state.frameIssue, 'unavailable'); assert.equal(h.owner.canInput(), false);
    h.fresh(); assert.equal(h.owner.canInput(), false, 'A contradictory sender mapping cannot silently regain authority');
    await h.owner.open('pc-1', scope()); h.fresh();
    assert.equal(h.owner.state.frameReady, true); assert.equal(h.owner.state.frameIssue, null);
  } finally { h.owner.dispose(); }
});

test('a replacement video track cannot retain the previous track frame readiness', async () => {
  const h = harness();
  try {
    await h.owner.open('pc-1', scope()); h.fresh(); await h.owner.control('take'); const old = h.fresh();
    h.peers[0].ontrack({ track: Object.assign(new EventTarget(), { kind: 'video', readyState: 'live', muted: false, stop() {} }), streams: [{ id: 'replacement-fixture-stream' }] });
    assert.equal(h.owner.canInput(), false); assert.equal(h.owner.state.frameReady, false);
    h.owner.receiveFrame(old); decode(h.owner, old); assert.equal(h.owner.canInput(), false);
    h.fresh(); assert.equal(h.owner.canInput(), true);
  } finally { h.owner.dispose(); }
});

test('the captured aiortc wire contract keeps RTP identity separate from decoder-local PTS', async () => {
  // Actual metadata and received RTP header from the synthetic-source aiortc
  // loopback. Its decoded AV frame had pts=0. Browser callbacks are injected here;
  // this is a wire-contract regression, not a browser or Windows acceptance test.
  const wire = { type: 'frame', streamId: '4bc1f0d8585540aa80015864994d9087', frameId: '38c38339e7224ca1ba30bb37a68a28b3',
    width: 320, height: 200, geometryRevision: 'b29d479865270310703cd1695b8c50f731734e42ffbfe269a7a05fbd727a7093',
    rtpTimestamp: 3485003512, capturedAt: 1793792620702, expiresAt: 1793792622702 };
  const opened = { ...session('wire-view'), width: wire.width, height: wire.height, expiresAt: wire.expiresAt + 60_000 };
  const peer = new Peer();
  const owner = new DesktopViewerConnection({ clock: () => wire.capturedAt + 100, onChange: () => {}, onStream: () => {}, createPeer: () => peer as any,
    run: async action => {
      if (action === 'devices.desktop.open') return { value: opened };
      if (action === 'devices.desktop.negotiate') return { value: { ...opened, streamId: wire.streamId, answer: { type: 'answer', sdp: 'v=0\r\nfixture-answer' } } };
      if (action === 'devices.desktop.close') return { value: { closed: true, sessionId: opened.sessionId } };
      throw new Error(action);
    },
  });
  try {
    await owner.open('pc-1', { ...scope(), expiresAt: opened.expiresAt });
    owner.receiveFrame(wire);
    decode(owner, { ...wire, rtpTimestamp: 0 }); assert.equal(owner.state.frameReady, false, 'Decoder-local pts=0 is not the on-wire RTP identity');
    decode(owner, wire); assert.equal(owner.state.frameReady, true);
    decode(owner, { ...wire, width: 640 }); assert.equal(owner.state.frameReady, false, 'Equal timestamps still require the actual decoded dimensions');
  } finally { owner.dispose(); }
});

test('a late status response cannot restore ownership after release and a new owner invalidates the old frame', async () => {
  const h = harness();
  try {
    await h.owner.open('pc-1', scope()); h.fresh(); await h.owner.control('take'); h.fresh();
    const prior = structuredClone(h.sessions.get('view-1'));
    h.delayed.add('devices.desktop.status'); const pending = h.owner.status();
    const release = h.owner.control('release');
    assert.equal(h.calls.filter(call => call.action === 'devices.desktop.control' && call.args.action === 'release').length, 0);
    h.settle('devices.desktop.status', { value: prior }); await pending; await release;
    const stale = h.owner.status(); h.settle('devices.desktop.status', { value: prior }); await stale;
    assert.equal(h.owner.state.session?.control.owner, 'none'); assert.equal(h.owner.state.session?.control.revision, 2);
    h.delayed.delete('devices.desktop.status'); h.fresh();
    h.sessions.get('view-1').control = { owner: 'agent', revision: 3 };
    await h.owner.status(); assert.equal(h.owner.state.session?.control.owner, 'agent'); assert.equal(h.owner.state.frameReady, false);
    assert.equal(h.owner.canInput(), false);
  } finally { h.owner.dispose(); }
});

test('another human window blocks local control until released, while an agent remains eligible for takeover', async () => {
  const h = harness();
  try {
    await h.owner.open('pc-1', scope()); h.fresh();
    h.sessions.get('view-1').control = { owner: 'other_human', revision: 1 };
    await h.owner.status();
    assert.equal(h.owner.state.phase, 'live'); assert.equal(h.owner.state.frameReady, false, 'A change of controller retires the prior frame');
    assert.equal(h.owner.state.session?.control.owner, 'other_human'); assert.equal(h.owner.state.session?.control.lease, undefined);
    h.fresh(); assert.equal(h.owner.state.frameReady, true, 'Watching remains available'); assert.equal(h.owner.canInput(), false);
    await h.owner.control('take'); await h.owner.control('release');
    assert.equal(await h.owner.input({ kind: 'press_key', key: 'enter' }), false);
    assert.equal(h.calls.some(call => call.action === 'devices.desktop.control' || call.action === 'devices.desktop.input'), false);

    h.sessions.get('view-1').control = { owner: 'none', revision: 2 };
    await h.owner.status(); assert.equal(h.owner.state.frameReady, false);
    h.fresh(); await h.owner.control('take'); assert.equal(h.owner.state.session?.control.owner, 'human');
    await h.owner.control('release');
    h.sessions.get('view-1').control = { owner: 'agent', revision: 5 };
    await h.owner.status(); h.fresh(); await h.owner.control('take');
    assert.equal(h.owner.state.session?.control.owner, 'human');
    assert.equal(h.calls.filter(call => call.action === 'devices.desktop.control' && call.args.action === 'take').length, 2);
  } finally { h.owner.dispose(); }
});

test('a reconciliation marker blocks takeover and completed-looking input receipts without preventing explicit release', async () => {
  const h = harness();
  try {
    await h.owner.open('pc-1', scope()); h.fresh(); await h.owner.control('take'); h.fresh();
    h.sessions.get('view-1').control.needsReconcile = true;
    assert.equal(await h.owner.input({ kind: 'press_key', key: 'enter' }), false, 'A completed input receipt cannot override the native reconciliation marker');
    assert.equal(h.owner.state.inputUncertain, true);
    await h.owner.control('release'); assert.equal(h.owner.state.session?.control.owner, 'none');
    assert.equal(h.owner.state.session?.control.needsReconcile, true); assert.equal(h.owner.state.inputUncertain, true);
    h.fresh(); await h.owner.control('take');
    assert.equal(h.owner.state.session?.control.owner, 'none');
    assert.equal(h.calls.filter(call => call.action === 'devices.desktop.control').length, 2);
    h.sessions.get('view-1').control.needsReconcile = false;
    await h.owner.status(); h.fresh();
    assert.equal(h.owner.state.inputUncertain, true, 'A later status read is not reconciliation proof');
  } finally { h.owner.dispose(); }
});

test('user input waits for a pending status read and rechecks its caller generation before dispatch', async () => {
  const h = harness();
  try {
    await h.owner.open('pc-1', scope()); h.fresh(); await h.owner.control('take'); h.fresh();
    h.delayed.add('devices.desktop.status');
    const status = h.owner.status(), input = h.owner.input({ kind: 'press_key', key: 'tab' });
    assert.equal(h.calls.filter(call => call.action === 'devices.desktop.input').length, 0);
    h.settle('devices.desktop.status'); await status; assert.equal(await input, true);
    h.fresh(); const oldStatus = h.owner.status(), staleInput = h.owner.input({ kind: 'press_key', key: 'enter' });
    h.owner.close(); h.settle('devices.desktop.status'); await oldStatus;
    assert.equal(await staleInput, false, 'Closing while waiting never dispatches input afterwards');
    assert.equal(h.calls.filter(call => call.action === 'devices.desktop.input').length, 1);
  } finally { h.owner.dispose(); }
});

test('whole desktop scroll keeps foreground-window selection native and still requires the current frame and lease', async () => {
  const h = harness();
  const desktopScope = { ...scope(), resourceBinding: { kind: 'desktop' as const, sessionId: 1, displayId: 'primary' } };
  const scroll = { kind: 'scroll' as const, direction: 'down' as const, amount: 3, by: 'line' as const };
  try {
    await h.owner.open('pc-1', desktopScope); h.fresh(); await h.owner.control('take'); h.fresh();
    assert.equal(await h.owner.input(scroll), true);
    assert.deepEqual(h.calls.filter(call => call.action === 'devices.desktop.input').at(-1)!.args.input, scroll, 'No previous browser click position can replace the native foreground-window target');
    assert.equal(await h.owner.input(scroll), false, 'A consumed frame is not reused for a second scroll');
    h.fresh(); await h.owner.control('release');
    assert.equal(await h.owner.input(scroll), false, 'Releasing control fences scrolling too');
  } finally { h.owner.dispose(); }
});

test('lease field order does not change control identity, but conflicting ownership at the same revision is rejected', async () => {
  const h = harness();
  try {
    await h.owner.open('pc-1', scope()); h.fresh(); await h.owner.control('take'); h.fresh();
    const value = h.sessions.get('view-1'), old = value.control.lease;
    value.control.lease = { resource: old.resource, expiresAt: old.expiresAt, fence: old.fence, id: old.id };
    await h.owner.status(); assert.equal(h.owner.state.phase, 'live'); assert.equal(h.owner.canInput(), true);
    value.control = { owner: 'none', revision: value.control.revision };
    await h.owner.status(); assert.equal(h.owner.state.phase, 'failed'); assert.equal(h.owner.canInput(), false);
  } finally { h.owner.dispose(); }
});

test('a delayed control result cannot unlock a successor session', async () => {
  const h = harness();
  try {
    await h.owner.open('pc-1', scope()); h.fresh();
    h.delayed.add('devices.desktop.control'); const oldTake = h.owner.control('take');
    const reopened = h.owner.open('pc-1', scope());
    assert.equal(h.owner.state.phase, 'opening'); assert.equal(h.owner.state.session, null);
    h.settle('devices.desktop.control'); await oldTake; await reopened;
    h.fresh(); const newTake = h.owner.control('take');
    assert.equal(h.owner.state.session?.sessionId, 'view-2'); assert.equal(h.owner.state.controlPending, true);
    assert.equal(h.owner.state.session?.control.owner, 'none');
    h.settle('devices.desktop.control'); await newTake;
    assert.equal(h.owner.state.controlPending, false); assert.equal(h.owner.state.session?.control.owner, 'human');
    assert.equal(h.owner.state.frameReady, false);
  } finally { h.owner.dispose(); }
});


test('metadata channel closure retires the viewer and permits an explicit reconnect', async () => {
  const h = harness();
  try {
    await h.owner.open('pc-1', scope()); h.fresh(); await h.owner.control('take'); h.fresh();
    (h.peers[0].channel as any).onclose();
    assert.equal(h.owner.state.phase, 'failed'); assert.equal(h.owner.canInput(), false); assert.equal(h.peers[0].closed, true);
    assert.ok(h.calls.some(call => call.action === 'devices.desktop.close' && call.args.sessionId === 'view-1'));
    await h.owner.open('pc-1', scope()); h.fresh(); assert.equal(h.owner.state.session?.sessionId, 'view-2');
    assert.equal(h.owner.state.phase, 'live'); assert.equal(h.owner.state.inputUncertain, false);
  } finally { h.owner.dispose(); }
});


test('an unusable open receipt still closes its allocated native handle', async () => {
  const h = harness();
  try {
    h.delayed.add('devices.desktop.open'); const pending = h.owner.open('pc-1', scope());
    h.settle('devices.desktop.open', { value: { ...session('bad-view'), width: 0 } }); await pending;
    assert.equal(h.owner.state.phase, 'failed'); assert.equal(h.owner.state.session, null);
    assert.ok(h.calls.some(call => call.action === 'devices.desktop.close' && call.args.sessionId === 'bad-view'));
  } finally { h.owner.dispose(); }
});
