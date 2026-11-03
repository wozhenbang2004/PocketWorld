'use strict';

// Production Java-generated programs with explicit DOM-reference fixtures, not a renderer.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const programs = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
let checks = 0;

function fixture() {
  const form = { action: 'https://upload.example/submit', method: 'post' };
  const document = { activeElement: null };
  let clicks = 0;
  const element = {
    tagName: 'INPUT', type: 'file', id: 'upload', name: 'file', accept: 'text/plain', multiple: false,
    isConnected: true, ownerDocument: document, disabled: false, form,
    getAttribute: () => null, hasAttribute: () => false,
    closest: selector => selector === 'form' ? form : null,
    getClientRects: () => [{}], getBoundingClientRect: () => ({ left: 20, top: 20, right: 180, bottom: 60 }),
    getRootNode: () => document, contains: other => other === element,
    focus: () => { document.activeElement = element; }, click: () => { clicks++; },
  };
  document.elementFromPoint = () => element;
  const state = { id: 'original-observation', dirty: false, observer: { takeRecords: () => [] },
    nodes: [element], nodeKeys: new WeakMap([[element, 'k1']]) };
  const context = vm.createContext({ location: { href: 'https://upload.example/form' },
    window: { 'native-page': state }, document, innerWidth: 400, innerHeight: 500 });
  return { context, element, state, document, form, clicks: () => clicks,
    run: name => JSON.parse(vm.runInContext(programs[name], context, { timeout: 1000 })) };
}
function check(work) { work(); checks++; }
check(() => {
  const f = fixture(); assert.equal(f.run('open').accepted, true); assert.equal(f.clicks(), 0);
  assert.equal(f.run('verify').accepted, true); assert.equal(f.clicks(), 0);
});
check(() => {
  const f = fixture(); f.run('open'); f.element.accept = 'image/*';
  assert.equal(f.run('verify').accepted, false); assert.equal(f.clicks(), 0);
});
check(() => {
  const f = fixture(); f.run('open'); f.state.artifactChooser.token = 'old-chooser';
  assert.equal(f.run('verify').accepted, false);
});
check(() => {
  const f = fixture(); f.run('open'); f.state.nodes[0] = { ...f.element };
  assert.equal(f.run('verify').accepted, false);
});
check(() => {
  const f = fixture(); f.state.dirty = true;
  assert.equal(f.run('open').accepted, false); assert.equal(f.clicks(), 0);
});
check(() => {
  const f = fixture(); f.state.observer.takeRecords = () => [{}];
  assert.equal(f.run('open').accepted, false); assert.equal(f.clicks(), 0);
});
check(() => {
  const f = fixture(); f.form.action = 'https://different.example/upload';
  assert.equal(f.run('open').accepted, false); assert.equal(f.clicks(), 0);
});
check(() => {
  const f = fixture(); f.document.elementFromPoint = () => ({});
  assert.equal(f.run('open').accepted, false); assert.equal(f.clicks(), 0);
});
check(() => {
  const f = fixture(); f.element.focus = () => {};
  assert.equal(f.run('open').accepted, false); assert.equal(f.clicks(), 0);
});
check(() => {
  const f = fixture(); f.context.location.href += '?new-document=1';
  assert.equal(f.run('open').accepted, false); assert.equal(f.clicks(), 0);
});
check(() => {
  const f = fixture(); f.element.disabled = true;
  assert.equal(f.run('open').accepted, false); assert.equal(f.clicks(), 0);
});
check(() => {
  const f = fixture(); f.run('open'); f.element.multiple = true;
  assert.equal(f.run('verify').accepted, false);
});
check(() => {
  const f = fixture(); f.run('open'); f.document.activeElement = {};
  assert.equal(f.run('verify').accepted, false);
});
process.stdout.write(JSON.stringify({ status: 'passed', checks, rendererVerified: false }) + '\n');
