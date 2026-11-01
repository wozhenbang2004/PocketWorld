'use strict';
((root) => {
  const policy=Object.freeze({create:(TerminalClass) => {
    const terminal=new TerminalClass({cols:80, rows:24, scrollback:1000, fontSize:14,
      fontFamily:'monospace', disableStdin:true, allowProposedApi:true, allowTransparency:false,
      convertEol:false, cursorBlink:false, logLevel:'off', windowOptions:{},
      linkHandler:{activate:() => {}, hover:() => {}, leave:() => {}},
      theme:{background:'#ffffff',foreground:'#17202b',cursor:'#17202b'}});
    // These terminal-origin requests are data, not Android/clipboard/navigation authority.
    for (const id of [0,1,2,8,9,52,1337]) terminal.parser.registerOscHandler(id, () => true);
    return terminal;
  }});
  root.PocketTerminalPolicy=policy;
  if(typeof module==='object'&&module.exports)module.exports=policy;
})(globalThis);
