export type RevisionPin={projectId:string;path:string;version:number;revisionId:string;kind:'text'|'binary'|'tombstone';bytes:number;sha256?:string};
type Row=Record<string,unknown>;
const uuid=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/,sha=/^[a-f0-9]{64}$/;
export function originalHistoryPins(rows:unknown,project:string,path:string):RevisionPin[]{
  if(!Array.isArray(rows)||rows.length<2||rows.length>8||!uuid.test(project)||!path)return [];
  const pins:RevisionPin[]=[];for(const x of rows){const r=x as Row,v=r?.original as RevisionPin|undefined;
    if(!v||v.projectId!==project||v.path!==path||v.version!==r.version||!Number.isSafeInteger(v.version)||v.version<1||typeof v.revisionId!=='string'||!uuid.test(v.revisionId)||
      !Number.isSafeInteger(v.bytes)||v.bytes<0||v.bytes>2097152||typeof r.deleted!=='boolean'||r.deleted!==(v.kind==='tombstone')||
      (r.deleted?v.bytes!==0||v.sha256!==undefined:!['binary','text'].includes(v.kind)||typeof v.sha256!=='string'||!sha.test(v.sha256)||v.bytes===0&&v.sha256!=='e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855')||
      pins.some(p=>p.version===v.version||p.revisionId===v.revisionId))return [];
    pins.push(v);
  }return pins.sort((a,b)=>a.version-b.version);
}
export function originalHistoryDifference(a:RevisionPin,b:RevisionPin):string{
  if(a.projectId!==b.projectId||a.path!==b.path||a.version>=b.version||a.revisionId===b.revisionId)return '';
  if(a.kind==='tombstone')return b.kind==='tombstone'?'两版都是删除记录，没有文件内容。':'文件从删除记录恢复为'+b.bytes.toLocaleString('zh-CN')+' 字节的正式文件。';
  if(b.kind==='tombstone')return '较晚版本已删除文件；不是一个零字节文件。';
  if(a.sha256===b.sha256)return a.bytes===b.bytes?'两版文件的大小和 SHA-256 相同。':'';
  const d=b.bytes-a.bytes;
  return '文件字节已变化，'+(d===0?'大小相同。':(d>0?'增加 ':'减少 ')+Math.abs(d).toLocaleString('zh-CN')+' 字节。');
}
