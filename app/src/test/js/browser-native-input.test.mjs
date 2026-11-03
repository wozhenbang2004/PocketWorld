import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';

// Execute the production Java text block; these are page-world DOM fixtures, not native trust proof.
const path = new URL('../../main/java/dev/pocketengine/browser/BrowserNativeInput.java', import.meta.url);
const source = fs.readFileSync(path, 'utf8');
const geometry = source.match(/private static final String GEOMETRY="""\n([\s\S]*?)\n""";/)?.[1];
assert.ok(geometry, 'production geometry program is present');
const javaString = name => JSON.parse(source.match(new RegExp(`private static final String ${name}=("(?:\\\\.|[^"\\\\])*");`))[1]);
const viewport = javaString('VIEWPORT'), focus = javaString('FOCUS'), editState = javaString('EDIT_STATE');
function productionMethod(name, bindings) {
  const method = source.match(new RegExp(`public static String ${name}\\([^\\n]*?\\)\\{return ([^\\n]+);\\}`));
  assert.ok(method, `production ${name} exists`);
  return Function(...Object.keys(bindings), 'JSONObject', `return ${method[1]}`)(...Object.values(bindings), {quote: JSON.stringify});
}
function fixture(action = 'tap') {
  const document = {activeElement: null, body: {innerText: 'original text'}};
  class Element {
    constructor(key, left, tag = 'BUTTON') {this.key = key; this.left = left; this.tagName = tag; this.type = tag === 'BUTTON' ? 'button' : ''; this.id = ''; this.name = ''; this.isConnected = true; this.ownerDocument = document; this.attrs = {}; this.listeners = new Map(); this.value = ''; this.rectWidth = 80; this.rectTop = 20; this.rectHeight = 40;}
    getBoundingClientRect() {return {left: this.left, top: this.rectTop, width: this.rectWidth, height: this.rectHeight};}
    getClientRects() {return [this.getBoundingClientRect()];}
    getAttribute(name) {return this.attrs[name] ?? null;}
    hasAttribute(name) {return name in this.attrs;}
    matches(selector) {return selector === ':disabled' && this.disabled === true;}
    closest() {return null;}
    getRootNode() {return document;}
    addEventListener(name, listener) {if (!this.listeners.has(name)) this.listeners.set(name, new Set()); this.listeners.get(name).add(listener);}
    removeEventListener(name, listener) {this.listeners.get(name)?.delete(listener);}
    event(name, isTrusted) {for (const listener of this.listeners.get(name) ?? []) listener({isTrusted});}
  }
  const firstNode = new Element('k1', 10), lastNode = new Element('k2', 120, 'CANVAS');
  document.activeElement = action === 'key' ? firstNode : null;
  const state = {id: 'observation', nodes: [firstNode, lastNode], dirty: false, nodeKeys: new WeakMap([[firstNode, 'k1'], [lastNode, 'k2']]), observer: {takeRecords: () => []}};
  const context = vm.createContext({document, window: {page: state}, location: {href: 'https://example.test/one'}, innerWidth: 400, innerHeight: 600, devicePixelRatio: 2, scrollX: 0, scrollY: 0, visualViewport: {width: 400, height: 600, scale: 1, offsetLeft: 0, offsetTop: 0}, getComputedStyle: () => ({visibility: 'visible', display: 'block', pointerEvents: 'auto', opacity: '1'}), setTimeout: () => 1, clearTimeout: () => {}});
  document.elementFromPoint = (x) => x < 100 ? firstNode : lastNode;
  state.inputViewport = vm.runInContext(viewport, context);
  state.inputRects = state.nodes.map(e => {const r = e.getBoundingClientRect(); return [r.left, r.top, r.width, r.height];});
  state.inputFocus = document.activeElement;
  state.inputValues = state.nodes.map(vm.runInContext(editState, context));
  const preview = (e, index) => ({ref: 'n' + index, nodeKey: e.key, tag: e.tagName, type: e.type, role: '', id: '', name: ''});
  const request = {action, ref: 'n0', ...(action === 'drag' ? {toRef: 'n1'} : {}), ...(action === 'key' ? {key: 'Enter'} : {})};
  const run = () => vm.runInContext(`(()=>{const s=window.page,expectedUrl='https://example.test/one',observation='observation',request=${JSON.stringify(request)},first=${JSON.stringify(preview(firstNode, 0))},last=${JSON.stringify(preview(action === 'drag' ? lastNode : firstNode, action === 'drag' ? 1 : 0))},token='original-token',viewport=()=>${viewport},focus=()=>${focus},editState=${editState};${geometry}})()`, context);
  const script = name => productionMethod(name, {pageKey: 'page', url: 'https://example.test/one', observation: 'observation', token: 'original-token'});
  return {run, context, state, document, firstNode, lastNode, request, continuation: () => JSON.parse(vm.runInContext(script('continuationScript'), context)), readback: () => JSON.parse(vm.runInContext(script('readbackScript'), context))};
}
test('fresh original observed node resolves element-relative position and exact drag destination', () => {
  const f = fixture('drag'); f.request.x = 250; f.request.y = 750;
  const result = f.run(); assert.equal(result.status, 'geometry_observed'); assert.equal(result.from.x, 30); assert.equal(result.from.y, 50); assert.equal(result.to.nodeKey, 'k2'); assert.equal(result.to.x, 160);
});
test('scroll, pinch zoom, layout, navigation and active input changes reject the old observation', () => {
  for (const change of [f => {f.context.scrollY = 1;}, f => {f.context.visualViewport.scale = 1.1;}, f => {f.firstNode.left++;}, f => {f.context.location.href = 'https://other.test/';}, f => {f.document.activeElement = f.lastNode;}]) {
    const f = fixture(); change(f); assert.equal(f.run().accepted, false);
  }
});
test('missing or incomplete viewport stays unavailable evidence without breaking ordinary observation', () => {
  const snapshot = productionMethod('observationStateScript', {VIEWPORT: viewport, FOCUS: focus, EDIT_STATE: editState});
  for (const omit of [c => {delete c.visualViewport;}, c => {c.visualViewport = null;}, c => {delete c.visualViewport.width;}, c => {c.visualViewport.height = NaN;}, c => {c.visualViewport.scale = 0;}, c => {c.visualViewport.offsetLeft = Infinity;}, c => {delete c.visualViewport.offsetTop;}, ...['innerWidth', 'innerHeight', 'devicePixelRatio', 'scrollX', 'scrollY'].map(name => c => {delete c[name];})]) {
    const f = fixture(); omit(f.context); f.context.state = f.state; f.context.nodes = f.state.nodes;
    assert.doesNotThrow(() => vm.runInContext(snapshot, f.context));
    assert.equal(f.state.inputViewport, null);
    assert.equal(f.state.inputRects.length, 2);
    assert.equal(f.run().accepted, false);
    assert.equal(f.state.nativeInput, undefined);
    assert.equal(f.firstNode.listeners.size, 0);
  }
});
test('occluded, disabled, private, removed and replaced target never becomes a coordinate', () => {
  for (const change of [f => {f.document.elementFromPoint = () => f.lastNode;}, f => {f.firstNode.disabled = true;}, f => {f.firstNode.type = 'password';}, f => {f.firstNode.isConnected = false;}, f => {f.state.nodeKeys.set(f.firstNode, 'k99');}]) {
    const f = fixture(); change(f); assert.equal(f.run().accepted, false);
  }
});
test('native key needs the exact input focus and a fresh observation of that focus', () => {
  const f = fixture('key'); assert.equal(f.run().status, 'geometry_observed');
  const g = fixture('key'); g.state.inputFocus = null; assert.equal(g.run().code, 'input_viewport_or_focus_changed');
  const h = fixture('key'); h.document.activeElement = null; h.state.inputFocus = null; assert.equal(h.run().code, 'input_target_not_focused');
  const i = fixture('key'); i.firstNode.value = 'owner typed after observe'; assert.equal(i.run().code, 'input_target_state_changed');
});
test('continuation rejects geometry or document change after pointer down without new target lookup', () => {
  const f = fixture('drag'); assert.equal(f.run().status, 'geometry_observed'); assert.equal(f.continuation().status, 'input_current'); f.lastNode.left += 2; assert.equal(f.continuation().status, 'unavailable');
  const g = fixture(); g.run(); g.context.location.href = 'https://example.test/new'; assert.equal(g.continuation().status, 'unavailable');
});
test('readback reports bounded original-node changes and keeps isTrusted as separate page evidence', () => {
  const f = fixture('key'); f.run(); f.firstNode.value = 'changed'; f.firstNode.event('keydown', false); const result = f.readback(); assert.equal(result.status, 'observed'); assert.equal(result.sameNode, true); assert.equal(result.changed.valueChanged, true); assert.equal(result.trustedEventObserved, false); assert.equal(result.eventCount, 1); assert.equal(f.readback().status, 'unavailable');
});
test('lost original node or token yields unavailable rather than a replacement readback', () => {
  const f = fixture(); f.run(); f.firstNode.isConnected = false; assert.equal(f.readback().status, 'unavailable');
  const g = fixture(); g.run(); g.state.nativeInput.token = 'other-operation'; assert.equal(g.readback().status, 'unavailable');
});
