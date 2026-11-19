export type OutputHit={part:'head'|'tail';from:number;to:number};
const maximum=200;
/** Only retained blocks. Never run a regex, search a missing middle, or join two unrelated lines at that gap. */
export function findReceivedOutput(head:string,tail:string|undefined,needle:string):{count:number;hits:OutputHit[]}{
  if(!needle.trim()||needle.length>128||head.length+(tail?.length||0)>65536)return {count:0,hits:[]};
  const hits:OutputHit[]=[];let count=0;
  for(const [part,text] of [['head',head],['tail',tail]] as const){if(!text)continue;let at=0;
    while((at=text.indexOf(needle,at))!==-1){if(hits.length<maximum)hits.push({part,from:at,to:at+needle.length});count++;at+=needle.length;}}
  return {count,hits};
}
export function outputHitContext(text:string,hit:OutputHit,needle:string){
  if(hit.from<0||hit.to>text.length||hit.from>=hit.to||text.slice(hit.from,hit.to)!==needle)return null;
  const lineStart=hit.from?text.lastIndexOf('\n',hit.from-1)+1:0,following=text.indexOf('\n',hit.to),lineEnd=following<0?text.length:following;
  let a=Math.max(lineStart,hit.from-96),b=Math.min(lineEnd,hit.to+120);
  if(a>0&&/^[\uDC00-\uDFFF]$/.test(text[a]))a++;if(b>0&&b<text.length&&/^[\uD800-\uDBFF]$/.test(text[b-1]))b--;
  return {before:(a>lineStart?'…':'')+text.slice(a,hit.from),needle,after:text.slice(hit.to,b)+(b<lineEnd?'…':'')};
}
