'use strict';
// Official pinned same-version terminal-core only. No project JavaScript, DOM
// substitute or Android/WebView rendering is claimed by this host check.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { Terminal } = require(process.env.C2_XTERM_HEADLESS);
const create = () => new Terminal({cols:80,rows:24,scrollback:1000,allowProposedApi:true});
const write = (term,data) => new Promise(resolve => term.write(data,resolve));
test('official VT cursor, erase, RGB and wide text remain actual screen state', async () => {
  const t=create(); try {
    await write(t,'\x1b[2J\x1b[HAB\r\n中文\x1b[1;4HZ');
    assert.equal(t.buffer.active.getLine(0).translateToString(true),'AB Z');
    assert.equal(t.buffer.active.getLine(1).translateToString(true),'中文');
    assert.equal(t.buffer.active.cursorX,4);assert.equal(t.buffer.active.cursorY,0);
    await write(t,'\x1b[38;2;255;0;0mR\x1b[0m');
    const cell=t.buffer.active.getLine(0).getCell(4);
    assert.equal(cell.getChars(),'R');assert.equal(cell.getFgColor(),0xff0000);assert.ok(cell.isFgRGB());
    await write(t,'\x1b[1;2H\x1b[K');assert.equal(t.buffer.active.getLine(0).translateToString(true),'A');
  } finally {t.dispose();}
});
test('actual alternate-screen TUI resize and return preserve primary screen', async () => {
  const t=create();try {
    await write(t,'BASE\x1b[?1049h\x1b[2J\x1b[HSCREEN');assert.equal(t.buffer.active.type,'alternate');
    assert.equal(t.buffer.active.getLine(0).translateToString(true),'SCREEN');t.resize(96,30);assert.equal(t.cols,96);assert.equal(t.rows,30);
    await write(t,'\x1b[5;8HX');assert.equal(t.buffer.active.getLine(4).getCell(7).getChars(),'X');
    await write(t,'\x1b[?1049l');assert.equal(t.buffer.active.type,'normal');assert.equal(t.buffer.active.getLine(0).translateToString(true),'BASE');
  }finally{t.dispose();}
});
test('split UTF8 and literal script stay data; OSC clipboard/link are swallowed', async () => {
  const t=create();try {
    let clipboard=0,links=0;t.parser.registerOscHandler(52,()=>{clipboard++;return true;});t.parser.registerOscHandler(8,()=>{links++;return true;});
    await write(t,new Uint8Array([0xe4]));await write(t,new Uint8Array([0xb8,0xad]));assert.equal(t.buffer.active.getLine(0).translateToString(true),'中');
    await write(t,'\r\n<script>globalThis.__c2_untrusted=1</script>');assert.equal(globalThis.__c2_untrusted,undefined);
    assert.equal(t.buffer.active.getLine(1).translateToString(true),'<script>globalThis.__c2_untrusted=1</script>');
    await write(t,'\x1b]52;c;c2VjcmV0\x07\x1b]8;;https://not-opened.invalid\x07x\x1b]8;;\x07');assert.equal(clipboard,1);assert.equal(links,2);
  }finally{t.dispose();}
});
