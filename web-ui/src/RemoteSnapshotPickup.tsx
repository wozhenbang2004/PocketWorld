import { useEffect, useRef, useState } from 'react';
import { Button, CheckboxField, SettingsDialog } from './forms-library';
import { bridge } from './bridge';
import { registerOverlayBack } from './overlay-back';
import { holdRemoteReads, remoteRows, type RemoteData as Data } from './remote-task-workspace';

const sha=(v:unknown):v is string=>typeof v==='string'&&/^[a-f0-9]{64}$/.test(v);
const code=(s:Data)=>JSON.stringify([s.windowHandle,s.reviewId,s.taskId]);
type File={path:string;bytes:number;sha256:string;fileVersion:string};
function members(v:Data):File[] {
  const items=remoteRows(v.files), paths=new Set<string>();
  if(!sha(v.snapshotVersion)||v.immutableArtifact!==true||!v.snapshotId||items.length<2||items.length>8)return [];
  if(!items.every(f=>{if(typeof f.path!=='string'||!f.path||paths.has(f.path)||!sha(f.sha256)||!sha(f.fileVersion)||!Number.isSafeInteger(f.bytes)||f.bytes<0||f.bytes>262144)return false;paths.add(f.path);return true;}))return [];
  return items.reduce((n,f)=>n+f.bytes,0)<=524288?items as File[]:[];
}
type Props={snapshot:Data;version:Data;enabled:boolean;run:(action:string,args:Data)=>Promise<any>};
export function RemoteSnapshotPickup({snapshot,version,enabled,run}:Props) {
  const files=members(version),existing=remoteRows(snapshot.snapshotAttachments);
  const already=(f:File)=>existing.some(r=>r.snapshotId===version.snapshotId&&r.snapshotVersion===version.snapshotVersion&&r.taskId===snapshot.taskId&&
    r.path===f.path&&r.fileVersion===f.fileVersion&&r.sha256===f.sha256&&r.bytes===f.bytes&&typeof r.attachment?.id==='string');
  const [window,setWindow]=useState(''),[picks,setPicks]=useState<string[]>([]),[message,setMessage]=useState(''),[pending,setPending]=useState(false);
  const alive=useRef(true),busy=useRef(false),current=useRef({snapshot,version,enabled});current.current={snapshot,version,enabled};
  const open=!!window&&window===code(snapshot),chosen=files.filter(f=>picks.includes(f.path)),total=chosen.reduce((n,f)=>n+f.bytes,0);
  const close=()=>{if(!busy.current){setWindow('');setPicks([])}};
  useEffect(()=>{alive.current=true;return()=>{alive.current=false}},[]);
  useEffect(()=>{if(!open)return;const release=holdRemoteReads(),back=registerOverlayBack(close);return()=>{back();release()}},[open]);
  if(!files.length||!bridge.getState().capabilities.commands.includes('remoteTask.pickupSnapshotFiles'))return null;
  const collect=async()=>{
    const owner=current.current,plan=chosen.map(({path,bytes,sha256,fileVersion})=>({path,bytes,sha256,fileVersion}));
    if(!open||!owner.enabled||document.hidden||busy.current||!plan.length||code(owner.snapshot)!==window||owner.version.snapshotVersion!==version.snapshotVersion)return;
    busy.current=true;setPending(true);setMessage('');
    try{
      const result=await run('remoteTask.pickupSnapshotFiles',{snapshotId:version.snapshotId,snapshotVersion:version.snapshotVersion,files:plan});
      if(!alive.current||current.current.snapshot.windowHandle!==owner.snapshot.windowHandle||document.hidden)return;
      const r=result?.operation,saved=remoteRows(r?.saved);
      if(r?.kind!=='frozen_file_pickup'||r.taskId!==owner.snapshot.taskId||r.snapshotId!==version.snapshotId||r.snapshotVersion!==version.snapshotVersion||
        r.executionAuthorized!==false||r.requested!==plan.length||saved.length>plan.length||!saved.every((v,i)=>v.path===plan[i].path&&v.sha256===plan[i].sha256&&v.bytes===plan[i].bytes&&
        typeof v.attachmentId==='string'&&!!v.attachmentId&&['host','verified_phone_copy'].includes(v.from)))throw new Error('领取回执不完整。请先核对原任务内已存到手机的文件，不会自动重领。');
      if(r.status==='complete'&&saved.length===plan.length&&r.notAttempted===0){
        const reused=saved.filter(v=>v.from==='verified_phone_copy').length;
        setMessage('手机已核对 '+saved.length+' 份原件。'+(reused?reused+' 份复用了相同本机原件，没有重复下载。':''));
      }else if((r.status==='stopped'||r.status==='partial')&&saved.length<plan.length&&r.stoppedPath===plan[saved.length].path&&r.notAttempted===plan.length-saved.length-1){
        setMessage('手机已核对 '+saved.length+'/'+plan.length+' 份。'+r.stoppedPath+' 未确认，后面 '+r.notAttempted+' 份未尝试。已保存的保留，请核对原主机后再选未到手机的文件。');
      }else throw new Error('不能确认全部领取，请以原工作里的逐文件手机回执为准。');
    }catch(e){if(alive.current)setMessage(e instanceof Error?e.message:'领取状态待核对，请先查看已存原件。');}
    finally{busy.current=false;if(alive.current){setPending(false);setWindow('');setPicks([])}}
  };
  return <div className="pw-remote-file-pickup">
    {files.some(f=>!already(f))&&<Button variant="secondary" size="small" disabled={!enabled||pending} onClick={()=>{if(enabled&&!document.hidden&&!busy.current){setPicks([]);setWindow(code(snapshot))}}}>选择多份存到手机</Button>}
    {message&&<p className="pw-field-hint" role="status">{message}</p>}
    {open&&<SettingsDialog isOpen={open} title="选择要存到手机的原件" onClose={close}><p>只取这个冻结版本。已存记录单列；中途停止不会清除已保存文件。</p>
      {files.map(f=>already(f)?<p key={f.path} className="pw-field-hint">{f.path} · 已在手机登记</p>:
        <CheckboxField key={f.path} isSelected={picks.includes(f.path)} isDisabled={pending} onChange={checked=>setPicks(v=>checked?[...new Set([...v,f.path])]:v.filter(x=>x!==f.path))}>{f.path} · {f.bytes} 字节</CheckboxField>)}
      <p className="pw-field-hint">原主机作业 {version.jobId} · 版本 {String(version.snapshotVersion).slice(0,12)} · 保存到本机的原工作</p>
      <Button variant="ghost" disabled={pending} onClick={close}>返回</Button><Button disabled={!enabled||pending||!chosen.length} onClick={()=>void collect()}>{pending?'逐份核对中…':'领取 '+chosen.length+' 份 · '+total+' 字节'}</Button>
    </SettingsDialog>}
  </div>;
}
