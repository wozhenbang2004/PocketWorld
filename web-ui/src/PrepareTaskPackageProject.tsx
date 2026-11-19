import {createContext,useContext,useEffect,useRef,useState} from 'react';
import {bridge} from './bridge';
import {Button} from './forms-library';
import type {ProjectPackageOutcome} from './project-preview';

type Pin={id:string;sha:string};
export const TaskPackageProjectContext=createContext<((task:string,pin:Pin)=>void)|null>(null);
const prepare='tasks.prepareProjectSource',close='tasks.closeProjectSourcePreparation';
function token(){
  const a=crypto.getRandomValues(new Uint8Array(16));a[6]=(a[6]&15)|64;a[8]=(a[8]&63)|128;
  const h=Array.from(a,b=>b.toString(16).padStart(2,'0')).join('');
  return [h.slice(0,8),h.slice(8,12),h.slice(12,16),h.slice(16,20),h.slice(20)].join('-');
}
/** Only mounted inside an explicitly opened original private package listing. */
export function PrepareTaskPackageProject({item,files,current}:{item:ProjectPackageOutcome;files:number;current:()=>boolean}){
  const open=useContext(TaskPackageProjectContext),[busy,setBusy]=useState(false),[error,setError]=useState(''),[ready,setReady]=useState<Pin|null>(null);
  const request=useRef(''),alive=useRef(false);
  useEffect(()=>{alive.current=true;return()=>{alive.current=false;const id=request.current;request.current='';
    if(id)void bridge.command(close,{taskId:item.taskId,token:id}).catch(()=>{});};},[item.taskId]);
  const supported=bridge.getState().capabilities.commands;
  if(!open||item.mode!=='source_project'||!supported.includes(prepare)||!supported.includes(close)||!supported.includes('projects.localSource.openPrepared'))return null;
  async function stage(){
    if(!current()||request.current||ready)return;
    const id=token();request.current=id;setBusy(true);setError('');
    const valid=()=>alive.current&&request.current===id&&current();
    try{
      const v=await bridge.command(prepare,{token:id,taskId:item.taskId,operationId:item.operationId,runId:item.runId,epoch:item.epoch,
        projectId:item.projectId,checkoutId:item.checkoutId,artifactId:item.artifactId,archiveSha256:item.archiveSha256});
      if(!valid())return;
      if(!v||v.taskId!==item.taskId||v.operationId!==item.operationId||v.artifactId!==item.artifactId||v.sha!==item.archiveSha256||
          v.files!==files||v.created!==false||typeof v.id!=='string'||!/^[a-f0-9-]{36}$/.test(v.id))throw Error('没有核对到这版源码的准备回执。');
      setReady({id:v.id,sha:v.sha});
    }catch(e){if(valid())setError(e instanceof Error?e.message:'没有收到原包回执；重查同一份不会再建项目。');}
    finally{if(request.current===id){request.current='';if(alive.current)setBusy(false);}}
  }
  return <div className="pw-action-row" aria-busy={busy}>
    {ready?<><span className="pw-field-hint" role="status">这版源码已准备在手机上，尚未创建项目。</span>
      <Button size="small" variant="secondary" onClick={()=>{if(current())open(item.taskId,ready);}}>核对新项目与名称</Button></>
    :<Button size="small" variant="secondary" disabled={busy} onClick={()=>void stage()}>{busy?'正在核对原版本…':'用这版源码准备新项目'}</Button>}
    {error&&<span role="alert" className="pw-error">{error}</span>}
  </div>;
}
