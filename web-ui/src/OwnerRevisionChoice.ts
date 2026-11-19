type Row=Record<string,unknown>;
export type RevisionPin={taskId:string;draftId:string;revisionId:string;sha256:string};
export type RevisionChoice={ownerRevision:RevisionPin;path:string;ordinal:number;bytes:number;projectId:string;checkoutId:string;goal?:string;selectedText?:string};
const sha=/^[0-9a-f]{64}$/;
export function pinKey(v:unknown):string {
  if(!v||typeof v!=='object')return '';
  const p=v as Row;
  if(typeof p.taskId!=='string'||!/^[A-Za-z0-9_-]{1,80}$/.test(p.taskId)||[p.draftId,p.revisionId,p.sha256].some(x=>typeof x!=='string'||!sha.test(x))||p.draftId===p.revisionId)return '';
  return JSON.stringify([p.taskId,p.draftId,p.revisionId,p.sha256]);
}
export function revisionChoice(v:unknown):v is RevisionChoice {
  if(!v||typeof v!=='object')return false;const x=v as Row;
  return !!pinKey(x.ownerRevision)&&typeof x.path==='string'&&!!x.path&&typeof x.projectId==='string'&&!!x.projectId&&typeof x.checkoutId==='string'&&!!x.checkoutId&&
    typeof x.ordinal==='number'&&Number.isInteger(x.ordinal)&&x.ordinal>0&&x.ordinal<=8&&typeof x.bytes==='number'&&Number.isInteger(x.bytes)&&x.bytes>=0&&x.bytes<=24576;
}
