'use strict';
const assert=require('node:assert/strict');
const {Terminal}=require('../../main/assets/ssh-terminal/xterm.js');
const policy=require('../../main/assets/ssh-terminal/terminal-policy.js');
const write=(terminal,value)=>new Promise(resolve=>terminal.write(value,resolve));
const line=(terminal,n=0)=>terminal.buffer.active.getLine(n).translateToString(true);
(async()=>{
  const t=policy.create(Terminal);const emitted=[];t.onData(value=>emitted.push(value));
  await write(t,'progress 000');await write(t,'\rprogress 100\x1b[K');assert.equal(line(t),'progress 100');
  await write(t,'\x1b[2J\x1b[Habc\x1b[2DX');assert.equal(line(t),'aXc');
  await write(t,'\x1b[?1049h\x1b[H\x1b[31mTUI\x1b[0m');assert.equal(t.buffer.active.type,'alternate');assert.equal(line(t),'TUI');assert.notEqual(t.buffer.active.getLine(0).getCell(0).getFgColor(),0);
  await write(t,'\x1b[?1049l');assert.equal(t.buffer.active.type,'normal');assert.equal(line(t),'aXc');
  await write(t,'\x1b[2J\x1b[H');const bytes=Buffer.from('中文🙂');await write(t,bytes.subarray(0,2));await write(t,bytes.subarray(2,7));await write(t,bytes.subarray(7));assert.equal(line(t),'中文🙂');
  await write(t,'\x1b]52;c;?\x07\x1b]8;;javascript:alert(1)\x1b\\link\x1b]8;;\x1b\\');assert.equal(line(t),'中文🙂link');assert.deepEqual(emitted,[]);assert.equal(t.options.disableStdin,true);
  t.resize(100,30);assert.equal(t.cols,100);assert.equal(t.rows,30);t.dispose();
  console.log('PASS: official xterm 6.0.0 cursor/erase, alternate screen, color, split UTF-8, blocked OSC and resize; not a WebView/device rendering assertion');
})().catch(error=>{console.error(error);process.exitCode=1;});
