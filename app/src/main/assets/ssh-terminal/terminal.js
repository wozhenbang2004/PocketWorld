'use strict';
(() => {
  // This page is a display only. No onData/onBinary/native bridge or network attach addon.
  const terminal = PocketTerminalPolicy.create(Terminal);
  terminal.open(document.getElementById('terminal'));
  let revision=-1, generation=-1, consumed=0, pending=null, busy=false;
  async function drain() {
    if (busy) return;
    busy=true;
    try {
      while (pending) {
        const value=pending; pending=null;
        if (value.revision < revision) continue;
        terminal.resize(value.cols,value.rows);
        if (value.generation !== generation) {terminal.reset();generation=value.generation;consumed=0;}
        for (let p=consumed;p<value.packets.length;p++) {
          const binary=atob(value.packets[p]),bytes=new Uint8Array(binary.length);
          for (let i=0;i<binary.length;i++) bytes[i]=binary.charCodeAt(i);
          await new Promise(resolve => terminal.write(bytes,resolve));
          consumed=p+1;
        }
        revision=value.revision;
      }
    } finally {busy=false;}
  }
  window.PocketTerminal=Object.freeze({display:(value) => {
    if (!value || !Number.isSafeInteger(value.revision) || value.revision < revision ||
        !Number.isSafeInteger(value.generation) || value.generation < generation ||
        !Number.isInteger(value.cols) || value.cols < 20 || value.cols > 300 ||
        !Number.isInteger(value.rows) || value.rows < 5 || value.rows > 120 ||
        !Array.isArray(value.packets) || value.packets.length > 128 ||
        value.packets.some(packet => typeof packet !== 'string' || packet.length > 87384 || !/^[A-Za-z0-9+/]*={0,2}$/.test(packet))) return false;
    // At most one current and one latest state. No unbounded JS write/callback backlog.
    pending=value; void drain(); return true;
  }});
})();
