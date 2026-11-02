'use strict';
// Explicit page/node references; only production JS and actual data-URL fetch are exercised.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const programs = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
let checks = 0;
const bytes = Buffer.from('列,值\n真实字节,17\n'.repeat(4096), 'utf8');
function fixture(change = {}) {
  const document = {};
  const element = { isConnected: true, ownerDocument: document, tagName: 'A', href: 'data:text/csv;base64,' + bytes.toString('base64') };
  Object.assign(element, change.element || {});
  const keys = new WeakMap([[element, change.nodeKey || 'k1']]);
  const state = { id: 'actual-observation', dirty: false, observer: { takeRecords: () => [] }, nodes: [element], nodeKeys: keys };
  Object.assign(state, change.state || {});
  let requests = 0;
  const context = vm.createContext({ document, location: { href: change.url || 'https://document.example/export', origin: 'https://document.example' }, URL, AbortController, Uint8Array,
    btoa: value => Buffer.from(value, 'binary').toString('base64'),
    fetch: (...args) => { requests++; return fetch(...args); } });
  context.window = context;
  context['page-state'] = state;
  return { context, element, requests: () => requests };
}
function run(f, script) { return JSON.parse(vm.runInContext(script, f.context, { timeout: 1000 })); }
async function ready(f) {
  const until = Date.now() + 5000;
  while (Date.now() < until) {
    const row = run(f, programs.poll);
    if (row.status === 'ready') return row;
    assert.equal(row.status, 'reading');
    await new Promise(resolve => setTimeout(resolve, 5));
  }
  throw new Error('Actual data-URL fetch did not complete');
}
(async () => {
  const actual = fixture();
  const opened = run(actual, programs.open);
  assert.equal(opened.status, 'reading');
  const metadata = await ready(actual);
  assert.equal(metadata.size, bytes.length);
  assert.equal(metadata.mime, 'text/csv');
  assert.equal(actual.requests(), 1);
  const first = run(actual, programs.chunk);
  assert.equal(first.offset, 0);assert.equal(first.size, bytes.length);
  assert.deepEqual(Buffer.from(first.data, 'base64'), bytes.subarray(0, 32768));checks++;
  assert.equal(run(actual, programs.badOffset).code, 'offset_invalid');checks++;
  const owned = actual.context['document-bytes'];
  vm.runInContext(programs.close, actual.context);
  assert.equal(actual.context['document-bytes'], undefined);assert.equal(owned.abort.signal.aborted, true);
  assert.ok(owned.bytes.every(value => value === 0));assert.equal(run(actual, programs.poll).code, 'document_file_changed');checks++;
  for (const href of ['https://document.example/private.csv', 'blob:https://other.example/not-owned']) {
    const refused = fixture({ element: { href } });
    assert.equal(run(refused, programs.open).code, href.startsWith('blob:') ? 'blob_origin_mismatch' : 'only_document_blob_data_supported');
    assert.equal(refused.requests(), 0);
  }
  checks++;
  for (const change of [{ url: 'https://document.example/changed' }, { state: { dirty: true } }, { state: { id: 'obsolete-observation' } }]) {
    const refused = fixture(change);assert.equal(run(refused, programs.open).status, 'unavailable');assert.equal(refused.requests(), 0);
  }
  checks++;
  for (const change of [{ nodeKey: 'k2' }, { element: { isConnected: false } }, { element: { tagName: 'BUTTON' } }]) {
    const refused = fixture(change);assert.equal(run(refused, programs.open).code, 'link_changed');assert.equal(refused.requests(), 0);
  }
  checks++;
  const changed = fixture();run(changed, programs.open);await ready(changed);changed.element.href='data:text/plain,changed';
  assert.equal(run(changed, programs.chunk).code, 'document_file_changed');vm.runInContext(programs.close, changed.context);checks++;
  process.stdout.write(JSON.stringify({ status: 'passed', checks, rendererVerified: false }) + '\n');
})().catch(error => { console.error(error);process.exitCode = 1; });
