import test from 'node:test';
import assert from 'node:assert/strict';
import { DesktopViewerConnection, type DesktopScope } from '../src/desktop-viewer.ts';

const START = 1_000_000;
const scope: DesktopScope = {
  key: 'grant:1:primary:42', grantId: 'grant', label: 'Window', available: true, reason: '',
  resourceBinding: { kind: 'desktop', sessionId: 1, displayId: 'primary', windowId: 42 }, expiresAt: START + 600_000,
};

class VideoTrack extends EventTarget {
  kind = 'video'; readyState = 'live'; muted = false; stopped = false;
  stop() { this.stopped = true; this.readyState = 'ended'; }
  end() { this.readyState = 'ended'; this.dispatchEvent(new Event('ended')); }
  mute(value: boolean) { this.muted = value; this.dispatchEvent(new Event(value ? 'mute' : 'unmute')); }
}
class Peer extends EventTarget {
  iceGatheringState = 'complete'; connectionState = 'new'; localDescription: any;
  ontrack: any; onconnectionstatechange: any; closed = false;
  channel: any = { label: 'pocketworld.desktop.frames', readyState: 'open', onmessage: null, onclose: null, onerror: null, close() { this.readyState = 'closed'; } };
  autoConnect = true;
  addTransceiver() {}
  createDataChannel() { return this.channel; }
  async createOffer() { return { type: 'offer', sdp: 'v=0\r\ntest-offer' }; }
  async setLocalDescription(value: any) { this.localDescription = value; }
  async setRemoteDescription() { if (this.autoConnect) this.connect('connected'); }
  connect(state: string) { this.connectionState = state; this.onconnectionstatechange?.(); }
  close() { this.closed = true; this.connectionState = 'closed'; }
}

function fixture(autoConnect = true) {
  let now = START, sequence = 0;
  const calls: { action: string; args: any }[] = [], peers: Peer[] = [], streams: unknown[] = [];
  const sessions = new Map<string, any>();
  const delayed = new Set<string>();
  const pending: { action: string; resolve: () => void }[] = [];
  const response = (action: string, args: any) => {
    if (action === 'devices.desktop.open') {
      const sessionId = 'view-' + ++sequence;
      const value = { kind: 'desktopSession', deviceId: 'pc', sessionId, resourceBinding: scope.resourceBinding,
        expiresAt: START + 600_000, width: 1280, height: 800, control: { owner: 'none', revision: 0 } };
      sessions.set(sessionId, value); return { value };
    }
    const value = sessions.get(args.sessionId);
    assert.ok(value, 'Every request targets a real fixture session');
    if (action === 'devices.desktop.close') return { value: { closed: true, sessionId: args.sessionId } };
    if (action === 'devices.desktop.negotiate') {
      value.streamId = 'stream-' + args.sessionId;
      return { value: { ...value, answer: { type: 'answer', sdp: 'v=0\r\ntest-answer' } } };
    }
    if (action === 'devices.desktop.control') {
      value.control = { owner: args.action === 'take' ? 'human' : 'none', revision: value.control.revision + 1,
        ...(args.action === 'take' ? { lease: { id: 'lease-' + args.sessionId } } : {}) };
      return { value };
    }
    if (action === 'devices.desktop.input') return { value: { ...value, inputReceipt: { status: 'completed', inputDispatched: true } } };
    if (action === 'devices.desktop.status') return { value };
    throw new Error(action);
  };
  const owner = new DesktopViewerConnection({
    run: (action, args = {}) => {
      calls.push({ action, args: structuredClone(args) });
      if (delayed.has(action)) return new Promise(resolve => pending.push({ action, resolve: () => resolve(structuredClone(response(action, args))) }));
      return Promise.resolve(structuredClone(response(action, args)));
    },
    clock: () => now, onChange: () => {}, onStream: stream => streams.push(stream),
    createPeer: () => { const peer = new Peer(); peer.autoConnect = autoConnect; peers.push(peer); return peer as any; },
  });
  const metadata = (geometryRevision = 'geometry-1') => ({ type: 'frame', streamId: owner.state.session!.streamId,
    frameId: 'frame-' + (++now), capturedAt: now, expiresAt: now + 2000, width: 1280, height: 800, geometryRevision });
  const fresh = () => { const value = metadata(); owner.receiveFrame(value); owner.decodedFrame(1280, 800); return value; };
  const attach = () => {
    const track = new VideoTrack();
    peers.at(-1)!.ontrack({ track, streams: [{ getTracks: () => [track], getVideoTracks: () => [track] }] });
    return track;
  };
  const settle = (action: string) => {
    const index = pending.findIndex(item => item.action === action); assert.notEqual(index, -1);
    pending.splice(index, 1)[0].resolve();
  };
  const inputCount = () => calls.filter(c => c.action === 'devices.desktop.input').length;
  const closeIds = () => calls.filter(c => c.action === 'devices.desktop.close').map(c => c.args.sessionId);
  return { owner, peers, streams, calls, delayed, pending, metadata, fresh, attach, settle, inputCount, closeIds, advance: (ms: number) => { now += ms; } };
}

async function human(h: ReturnType<typeof fixture>) {
  await h.owner.open('pc', scope); h.fresh(); await h.owner.control('take'); h.fresh();
  assert.equal(h.owner.canInput(), true);
}

test('control handoff needs a decoded frame after the new metadata, not the pre-handoff picture', async () => {
  const h = fixture();
  try {
    await h.owner.open('pc', scope); h.fresh(); await h.owner.control('take');
    h.owner.receiveFrame(h.metadata());
    assert.equal(h.owner.canInput(), false);
    assert.equal(await h.owner.input({ kind: 'press_key', key: 'enter' }), false);
    assert.equal(h.inputCount(), 0);
    h.owner.decodedFrame(1280, 800); assert.equal(h.owner.canInput(), true);
  } finally { h.owner.dispose(); }
});

test('a same-size geometry change cannot inherit the previous decoded picture', async () => {
  const h = fixture();
  try {
    await human(h); h.owner.receiveFrame(h.metadata('window-moved'));
    assert.equal(h.owner.canInput(), false);
    h.owner.decodedFrame(1280, 800); assert.equal(h.owner.canInput(), true);
  } finally { h.owner.dispose(); }
});

test('duplicate metadata cannot renew an expired frame by its arrival time', async () => {
  const h = fixture();
  try {
    await human(h); const value = h.fresh(); h.advance(2001);
    h.owner.decodedFrame(1280, 800); h.owner.receiveFrame(value);
    assert.equal(h.owner.canInput(), false);
    assert.equal(await h.owner.input({ kind: 'press_key', key: 'enter' }), false);
    assert.equal(h.inputCount(), 0);
    h.fresh(); assert.equal(h.owner.canInput(), true);
  } finally { h.owner.dispose(); }
});

test('frames delivered during a disconnect never unlock input after transport recovery', async () => {
  const h = fixture();
  try {
    await human(h); h.peers[0].connect('disconnected');
    const duringOutage = h.fresh(); h.peers[0].connect('connected');
    assert.equal(h.owner.canInput(), false);
    h.owner.receiveFrame(duringOutage); h.owner.decodedFrame(1280, 800);
    assert.equal(h.owner.canInput(), false, 'Disconnected metadata is retired, not reaccepted after reconnect');
    h.fresh(); assert.equal(h.owner.canInput(), true);
  } finally { h.owner.dispose(); }
});

test('a missing native open response stops the spinner and closes its late exact session', async t => {
  t.mock.timers.enable({ apis: ['setTimeout', 'setInterval'] });
  const h = fixture(); h.delayed.add('devices.desktop.open');
  const opening = h.owner.open('pc', scope);
  try {
    t.mock.timers.tick(45_000);
    assert.equal(h.owner.state.phase, 'failed');
    assert.match(h.owner.state.error, /超时/);
    h.settle('devices.desktop.open'); await opening;
    assert.deepEqual(h.closeIds(), ['view-1']);
    assert.equal(h.peers.length, 0);
  } finally {
    h.owner.dispose(); if (h.pending.length) h.settle('devices.desktop.open'); await opening;
  }
});

test('SDP completion without a usable video frame has a bounded startup deadline', async t => {
  t.mock.timers.enable({ apis: ['setTimeout', 'setInterval'] });
  const h = fixture(false);
  try {
    await h.owner.open('pc', scope); assert.equal(h.owner.state.phase, 'connecting');
    t.mock.timers.tick(45_000);
    assert.equal(h.owner.state.phase, 'failed');
    assert.deepEqual(h.closeIds(), ['view-1']); assert.equal(h.peers[0].closed, true);
  } finally { h.owner.dispose(); }
});

test('closed metadata and ended video retire the viewer rather than leave a live frozen surface', async () => {
  for (const ending of ['channel', 'track']) {
    const h = fixture();
    try {
      await human(h); const track = h.attach(); h.fresh();
      if (ending === 'channel') h.peers[0].channel.onclose?.(); else track.end();
      assert.equal(h.owner.state.phase, 'failed', ending);
      assert.equal(h.owner.canInput(), false); assert.deepEqual(h.closeIds(), ['view-1']);
      assert.equal(track.stopped, true); assert.equal(h.streams.at(-1), null);
    } finally { h.owner.dispose(); }
  }
});

test('closing stops the received track and old media callbacks cannot close its successor', async () => {
  const h = fixture();
  try {
    await human(h); const track = h.attach(); const oldChannelClosed = h.peers[0].channel.onclose;
    await h.owner.open('pc', scope);
    assert.equal(track.stopped, true);
    track.end(); oldChannelClosed?.(); h.fresh();
    assert.equal(h.owner.state.session?.sessionId, 'view-2'); assert.equal(h.owner.state.phase, 'live');
    assert.deepEqual(h.closeIds(), ['view-1']);
  } finally { h.owner.dispose(); }
});

test('muted remote track disables input until unmuted and freshly decoded', async () => {
  const h = fixture();
  try {
    await human(h); const track = h.attach(); h.fresh(); track.mute(true);
    h.fresh(); assert.equal(h.owner.canInput(), false);
    track.mute(false); assert.equal(h.owner.canInput(), false);
    h.owner.receiveFrame(h.metadata()); assert.equal(h.owner.canInput(), false);
    h.owner.decodedFrame(1280, 800); assert.equal(h.owner.canInput(), true);
  } finally { h.owner.dispose(); }
});

test('a tap waiting for a status read is not rebound to a newer frame', async () => {
  const h = fixture();
  try {
    await human(h); h.delayed.add('devices.desktop.status');
    const status = h.owner.status();
    const click = h.owner.input({ kind: 'click', x: 500, y: 300, button: 'left', count: 1 });
    h.fresh(); h.settle('devices.desktop.status'); await status;
    assert.equal(await click, false);
    assert.equal(h.inputCount(), 0);
    assert.equal(h.owner.state.inputUncertain, false, 'A command never dispatched is not an unknown remote result');
    assert.match(h.owner.state.error, /变化/);
    assert.equal(await h.owner.input({ kind: 'click', x: 500, y: 300, button: 'left', count: 1 }), true);
  } finally { h.owner.dispose(); }
});

test('a healthy viewer cancels the startup deadline instead of closing at 45 seconds', async t => {
  t.mock.timers.enable({ apis: ['setTimeout', 'setInterval'] });
  const h = fixture();
  try {
    await human(h); t.mock.timers.tick(45_000);
    assert.equal(h.owner.state.phase, 'live'); assert.deepEqual(h.closeIds(), []);
  } finally { h.owner.dispose(); }
});

test('a persistent decode stall closes the exact session after a bounded recovery interval', async t => {
  t.mock.timers.enable({ apis: ['setTimeout', 'setInterval'] });
  const h = fixture();
  try {
    await human(h);
    for (let i = 0; i < 14 && h.owner.state.phase !== 'failed'; i++) {
      h.advance(1000); h.owner.receiveFrame(h.metadata()); t.mock.timers.tick(1000); await Promise.resolve();
    }
    assert.equal(h.owner.state.phase, 'failed'); assert.match(h.owner.state.error, /持续未更新/);
    assert.deepEqual(h.closeIds(), ['view-1']); assert.equal(h.owner.canInput(), false);
  } finally { h.owner.dispose(); }
});

test('a brief stall can recover without reopening or taking a new lease', async t => {
  t.mock.timers.enable({ apis: ['setTimeout', 'setInterval'] });
  const h = fixture();
  try {
    await human(h); h.advance(2001); t.mock.timers.tick(1000);
    assert.equal(h.owner.state.frameReady, false);
    h.advance(4000); h.fresh(); t.mock.timers.tick(1000);
    assert.equal(h.owner.canInput(), true); assert.deepEqual(h.closeIds(), []);
    assert.equal(h.calls.filter(c => c.action === 'devices.desktop.open').length, 1);
    assert.equal(h.calls.filter(c => c.action === 'devices.desktop.control').length, 1);
  } finally { h.owner.dispose(); }
});

test('freshness rejects the exact expiry boundary and a clock moving backwards', async () => {
  const h = fixture();
  try {
    await human(h); h.advance(2000); assert.equal(h.owner.canInput(), false);
    h.fresh(); h.advance(-1); assert.equal(h.owner.canInput(), false);
  } finally { h.owner.dispose(); }
});

test('a metadata channel error closes only its own generation', async () => {
  const h = fixture();
  try {
    await human(h); const oldError = h.peers[0].channel.onerror;
    await h.owner.open('pc', scope); h.fresh(); oldError?.();
    assert.equal(h.owner.state.phase, 'live'); assert.deepEqual(h.closeIds(), ['view-1']);
    h.peers[1].channel.onerror?.();
    assert.equal(h.owner.state.phase, 'failed'); assert.deepEqual(h.closeIds(), ['view-1', 'view-2']);
  } finally { h.owner.dispose(); }
});
