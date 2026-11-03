'use strict';
// Production emitted JavaScript in a deterministic MutationObserver/timer fixture.
// This checks observer ownership and cleanup, not Android/WebView or browser rendering.
const assert = require('node:assert/strict');
const vm = require('node:vm');
let input = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', chunk => { input += chunk; if (input.length > 24000) throw Error('fixture input limit'); });
process.stdin.on('end', () => {
  const code = JSON.parse(input);
  let cases = 0;
  function world() {
    let now = 0, nextTimer = 0, reads = 0;
    const timers = new Map(), observers = [];
    const document = {documentElement: {isConnected: true}, readyState: 'complete', title: 'Fixture'};
    Object.defineProperty(document, 'body', {get() { reads++; throw Error('private DOM values must not be read'); }});
    class MutationObserver {
      constructor(callback) { this.callback = callback; this.disconnected = false; observers.push(this); }
      observe(root, options) { this.root = root; this.options = options; }
      disconnect() { this.disconnected = true; }
    }
    const context = vm.createContext({document, MutationObserver, performance: {now: () => now},
      setTimeout(fn, ms) { assert.equal(ms, 6500); timers.set(++nextTimer, fn); return nextTimer; },
      clearTimeout(id) { timers.delete(id); }, location: {href: 'https://fixture.test/'}, closed: false});
    Object.defineProperty(context, 'fetch', {get() { throw Error('barrier must not send requests'); }});
    return {context, document, observers, timers, advance(value) { now = value; },
      run(source) { return vm.runInContext(source, context, {timeout: 500}); },
      probe() { return JSON.parse(this.run(code.probe)); }, reads: () => reads};
  }
  {
    const w = world();assert.equal(w.probe().quietForMs, 0);assert.equal(w.observers.length, 1);
    const observer = w.observers[0];assert.equal(observer.root, w.document.documentElement);
    assert.deepEqual(JSON.parse(JSON.stringify(observer.options)), {subtree: true, childList: true, attributes: true, characterData: true});
    w.advance(150);assert.equal(w.probe().quietForMs, 150);assert.equal(w.observers.length, 1);
    w.advance(200);observer.callback();observer.callback();observer.callback();assert.equal(w.probe().mutations, 3);assert.equal(w.probe().quietForMs, 0);
    w.advance(700);assert.equal(w.probe().quietForMs, 500);w.document.readyState = 'loading';assert.equal(w.probe().loading, true);
    w.run(code.foreignCleanup);assert.equal(observer.disconnected, false);w.run(code.cleanup);assert.equal(observer.disconnected, true);assert.equal(w.timers.size, 0);assert.equal(w.reads(), 0);cases++;
  }
  {
    const w = world();w.probe();w.document.documentElement = {isConnected: true};assert.equal(w.probe().available, false);assert.equal(w.observers.length, 1);w.run(code.cleanup);assert.equal(w.observers[0].disconnected, true);cases++;
  }
  {
    const w = world();w.probe();assert.equal(JSON.parse(w.run(code.foreignProbe)).available, false);assert.equal(w.observers.length, 1);assert.equal(w.observers[0].disconnected, false);w.run(code.cleanup);cases++;
  }
  {
    const w = world();w.probe();for (let i = 0; i < 4097; i++) w.observers[0].callback();assert.equal(w.observers[0].disconnected, true);assert.equal(w.probe().available, false);w.run(code.cleanup);cases++;
  }
  {
    const w = world();w.probe();const cleanup = [...w.timers.values()][0];cleanup();assert.equal(w.observers[0].disconnected, true);assert.equal(w.context[code.key], undefined);cases++;
  }
  {
    const w = world();w.context[code.documentKey] = code.documentToken;assert.equal(JSON.parse(w.run(code.guarded)).available, true);w.context[code.documentKey] = 'replaced-document';const rejected = JSON.parse(w.run(code.guarded));assert.equal(rejected.status, 'unavailable');assert.equal(rejected.code, 'frame_document_changed');assert.equal(w.observers.length, 1);w.run(code.cleanup);cases++;
  }
  process.stdout.write(JSON.stringify({status: 'passed', cases, execution: 'production_js_mutation_observer_fixture', browserRenderingVerified: false}) + '\n');
});
