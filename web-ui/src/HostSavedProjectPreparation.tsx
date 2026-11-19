import {useEffect,useRef,useState} from 'react';
import {bridge} from './bridge';
import {remoteObject} from './remote-task-workspace';
import {Button,CheckboxField,SettingsDialog} from './forms-library';

type Claim={taskId:string;attachmentId:string;snapshotId:string;snapshotVersion:string;path:string;fileVersion:string;sha256:string};
export type HostSourceChoice={claim:Claim;bytes:number};
export type HostSourcePin={id:string;sha:string};
const key=(s:HostSourceChoice)=>JSON.stringify([s.claim.snapshotId,s.claim.snapshotVersion,s.claim.path,s.claim.fileVersion,s.claim.attachmentId,s.claim.sha256,s.bytes]);
function tokenForChoice(){
  const bytes=crypto.getRandomValues(new Uint8Array(16));bytes[6]=(bytes[6]&15)|64;bytes[8]=(bytes[8]&63)|128;
  const n=Array.from(bytes,b=>b.toString(16).padStart(2,'0')).join('');
  return [n.slice(0,8),n.slice(8,12),n.slice(12,16),n.slice(16,20),n.slice(20)].join('-');
}
const group=(s:HostSourceChoice)=>JSON.stringify([s.claim.snapshotId,s.claim.snapshotVersion]);
const supported=(task:string)=>document.visibilityState==='visible'&&remoteObject(bridge.getState().view).taskId===task&&
  bridge.getState().capabilities.commands.includes('remoteTask.stageSavedSources');
export function HostSavedProjectPreparation({task,files,openProject}:{task:string;files:HostSourceChoice[];openProject:(pin:HostSourcePin)=>void}){
  const [open,setOpen]=useState(false),[version,setVersion]=useState(''),[picks,setPicks]=useState<string[]>([]);
  const [busy,setBusy]=useState(false),[error,setError]=useState(''),[ready,setReady]=useState<HostSourcePin|null>(null);
  const live=useRef(true),inflight=useRef(false),view=useRef(0),original=useRef('');
  function stop(){const token=original.current;original.current='';if(token)void bridge.command('remoteTask.cancelSourcePreparation',{taskId:task,token}).catch(()=>{});}
  useEffect(()=>{live.current=true;return()=>{live.current=false;view.current++;stop();};},[]);
  const groups=[...new Map(files.map(f=>[group(f),f])).values()],rows=files.filter(f=>group(f)===version);
  const selected=rows.filter(f=>picks.includes(key(f))),total=selected.reduce((sum,row)=>sum+row.bytes,0);
  const exact=selected.length===picks.length&&selected.length>0&&selected.length<=8&&total<=512*1024;
  function choose(id:string){setVersion(id);setPicks([]);setError('');setReady(null);}
  function begin(){view.current++;choose(groups.length===1?group(groups[0]):'');setOpen(true);}
  function close(){view.current++;stop();setOpen(false);choose('');}
  async function stage(){
    if(inflight.current||!supported(task)||!exact)return;
    const turn=view.current,first=selected[0].claim;
    inflight.current=true;setBusy(true);setError('');
    try{
      const token=tokenForChoice();original.current=token;
      const r=await bridge.command<{taskId:string;id:string;sha:string;files:number;snapshotVersion:string}>('remoteTask.stageSavedSources',{taskId:task,token,claims:selected.map(f=>f.claim)});
      if(!live.current||view.current!==turn||!supported(task))return;
      if(r.taskId!==task||r.files!==selected.length||r.snapshotVersion!==first.snapshotVersion||!/^([a-f0-9-]{36})$/.test(r.id)||!/^[a-f0-9]{64}$/.test(r.sha))throw Error('没有核对到这份原临时包，请返回原任务。');
      setReady({id:r.id,sha:r.sha});
    }catch(e){if(live.current&&view.current===turn)setError(e instanceof Error?e.message:'没有准备完整的本机源码。');}
    finally{original.current='';inflight.current=false;if(live.current)setBusy(false);}
  }
  if(!files.length)return null;
  return <><Button variant="ghost" disabled={!supported(task)} onClick={begin}>选文件在手机上继续开发</Button>
    <SettingsDialog isOpen={open} title="把已存原件作为本机源码" closeLabel="回到原主机任务" onClose={close}>
      <div className="pw-project-git-body" aria-busy={busy}>
        {error&&<p role="alert" className="pw-error">{error}</p>}
        {ready?<><h3>这份源码已准备在本机</h3><p>{picks.length} 份已存手机原件，无需连接主机。下一步核对清单和名称后才创建新项目。</p>
          <Button variant="primary" disabled={busy} onClick={()=>{if(supported(task))openProject(ready);}}>去项目核对</Button></>
        :!version?<><p>先选一个冻结版本，不混用不同主机或版本的文件。</p>
          {groups.map(f=><Button key={group(f)} variant="ghost" onClick={()=>choose(group(f))}>版本 {f.claim.snapshotVersion.slice(0,16)} · {files.filter(s=>group(s)===group(f)).length} 份手机原件</Button>)}</>
        :<><p>只复制选中的 UTF-8 文件和原相对路径，不是整份仓库；不连接主机、不运行代码。</p>
          {groups.length>1&&<Button variant="ghost" disabled={busy} onClick={()=>choose('')}>更换冻结版本</Button>}
          {rows.map(f=>{const id=key(f),on=picks.includes(id);return <CheckboxField key={id} isSelected={on}
            isDisabled={busy||f.bytes>256*1024||(!on&&(picks.length>=8||total+f.bytes>512*1024))}
            onChange={v=>{setError('');setPicks(p=>v?[...new Set([...p,id])]:p.filter(s=>s!==id));}}>
              <bdi>{f.claim.path}</bdi> · {f.bytes.toLocaleString()} 字节{f.bytes>256*1024?' · 超出单份上限':''}</CheckboxField>;})}
          <small>最多 8 份，每份 256 KiB，合计 512 KiB。已选 {selected.length} 份。</small>
          {picks.length!==selected.length&&<p role="alert">原文件清单已变化，请重新选择。</p>}
          <Button variant="primary" disabled={busy||!exact} onClick={()=>void stage()}>{busy?'正在核对实际原件…':'准备所选源码'}</Button></>}
      </div>
    </SettingsDialog></>;
}
