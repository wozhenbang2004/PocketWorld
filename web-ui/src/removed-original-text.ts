import type {RevisionPin} from './formal-history-compare';
type Data=Record<string,any>;export type RemovedPosition={index:number;path:string;version:number;revisionId:string;at:number};
export async function verifiedRemovedText(r:Data|undefined,p:RevisionPin,removed:RemovedPosition,choice:string,handle:string):Promise<{state:'complete'|'limited';text?:string}|null>{
  const sel=r?.selected;if(!r||r.format!=='removed_formal_original_text_v1'||r.projectId!==p.projectId||r.path!==p.path||r.handle!==handle||r.choice!==choice||
    !sel||!['index','path','version','revisionId','at'].every(k=>sel[k]===(removed as Data)[k])||p.kind!=='text'||p.version>=removed.version||
    r.version!==p.version||r.revisionId!==p.revisionId||r.sha256!==p.sha256||r.bytes!==p.bytes)return null;
  if(r.state==='over_text_limit')return p.bytes>65536&&!('text'in r)?{state:'limited'}:null;
  if(r.state!=='complete_text'||typeof r.text!=='string'||r.text.length>65536||r.text.includes('\0'))return null;
  const bytes=new TextEncoder().encode(r.text);try{
    if(bytes.byteLength!==p.bytes)return null;
    const digest=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes)),x=>x.toString(16).padStart(2,'0')).join('');
    return digest===p.sha256?{state:'complete',text:r.text}:null;
  }finally{bytes.fill(0)}
}
