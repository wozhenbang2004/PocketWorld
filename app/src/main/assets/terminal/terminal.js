/* Product transport for the pinned MIT xterm.js. Never evaluates terminal data,
 * opens links, handles clipboard OSC, loads remote scripts, or creates a socket. */
(() => {
  'use strict';
  let term = null, token = '', enabled = false, held = '', timer = 0, pending = 0;
  const send = value => {
    if (!window.NativeTerminal || !token) return;
    NativeTerminal.postMessage(JSON.stringify(Object.assign({token}, value)));
  };
  const flush = () => {
    clearTimeout(timer); timer = 0;
    if (!held) return;
    const value = held; held = '';
    if (!enabled) return;
    send({type: 'input', text: value});
  };
  window.C2Terminal = Object.freeze({
    boot(value, columns, rows) {
      if (term || !/^[a-f0-9]{32}$/.test(value)) return;
      token = value;
      term = new Terminal({cols: columns, rows, scrollback: 1000, convertEol: false,
        disableStdin: true, cursorBlink: false, fontSize: 14, fontFamily: 'monospace',
        allowProposedApi: true, linkHandler: {activate() {}, hover() {}, leave() {}},
        theme: {background:'#171717',foreground:'#eeeeee',cursor:'#eeeeee',selectionBackground:'#555555'}});
      term.open(document.getElementById('terminal'));
      term.parser.registerOscHandler(52, () => true); // no terminal clipboard access
      term.parser.registerOscHandler(8, () => true);  // no clickable terminal URI
      term.onData(data => {
        if (!enabled) return;
        if (new TextEncoder().encode(held + data).length > 4000) flush();
        if (new TextEncoder().encode(data).length > 4000) { send({type:'blocked',reason:'input_chunk_too_large'}); return; }
        held += data; if (!timer) timer = setTimeout(flush, 45);
      });
      // Binary mouse protocols are deliberately not offered as arbitrary bytes.
      term.onBinary(() => send({type:'blocked',reason:'binary_mouse_input_not_supported'}));
      send({type:'ready'});
    },
    inputEnabled(value) {
      enabled = value === true;
      if (!enabled) { clearTimeout(timer); held = ''; timer = 0; }
      if (term) { term.options.disableStdin = !enabled; if (enabled) term.focus(); }
    },
    resize(columns, rows) { if (term) term.resize(columns, rows); },
    bytes(encoded, id) {
      if (!term || !Number.isSafeInteger(id) || pending > 262144 || encoded.length > 45000) return;
      const binary = atob(encoded), bytes = new Uint8Array(binary.length);
      for (let i=0;i<binary.length;i++) bytes[i]=binary.charCodeAt(i);
      pending += bytes.length;
      term.write(bytes, () => { pending -= bytes.length; send({type:'written',id}); });
    },
    dispose() { enabled=false; clearTimeout(timer); held=''; if(term)term.dispose(); term=null; token=''; }
  });
})();
