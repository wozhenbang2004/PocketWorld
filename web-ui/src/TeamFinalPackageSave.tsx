import { useRef, useState } from 'react';
import { bridge } from './bridge';
import { Button } from './forms-library';
import type { FinalPackage } from './TeamFinalPackageReading';

const choose='teams.chooseFinalPackageTarget', save='teams.saveFinalPackageTarget', discard='teams.discardFinalPackageTarget';
type Receipt={requestId:string; status:string; message:string};
const phases=['checking','document_requested','checking_target','target_selected','selection_returned','writing','cancelled','rejected','written_unverified','unknown','exported'];
function sameReceipt(value:unknown, item:FinalPackage):Receipt|null {
  if(!value||typeof value!=='object')return null;
  const r=value as Record<string,unknown>;
  if(r.kind!=='project_package_export'||r.teamRunId!==item.teamRunId||r.taskId!==item.taskId||r.operationId!==item.operationId||
    r.runId!==item.runId||r.epoch!==item.epoch||r.projectId!==item.projectId||r.checkoutId!==item.checkoutId||
    r.artifactId!==item.artifactId||r.artifactSha256!==item.archiveSha256||typeof r.requestId!=='string'||
    !/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/.test(r.requestId)||typeof r.message!=='string'||r.message.length>1000||
    typeof r.status!=='string'||!phases.includes(r.status))return null;
  if(r.status==='exported') {
    const proof=r.targetReadback as Record<string,unknown>|undefined;
    if(r.exported!==true||r.bytes!==item.archiveBytes||proof?.protocol!=='pocketworld.package.target-sha256.v1'||
      proof.status!=='verified'||typeof proof.provider!=='string'||!proof.provider||proof.provider.length>160||proof.bytes!==item.archiveBytes||proof.sha256!==item.archiveSha256)
      return {requestId:r.requestId,status:'written_unverified',message:'写入结果缺少所选文件的完整回读依据。请先核对原位置，没有重复写入。'};
  }
  return {requestId:r.requestId,status:r.status,message:r.message};
}
export function TeamFinalPackageSave({item,handle,live,status,show,disabled}:{item:FinalPackage;handle:string;live:()=>boolean;status:unknown;show:boolean;disabled:boolean}){
  const [busy,setBusy]=useState(false),[error,setError]=useState(''),entered=useRef(false);
  const original=sameReceipt(status,item), available=(cmd:string)=>bridge.getState().capabilities.commands.includes(cmd);
  if(!available(choose)||!available(save)||!available(discard)||(!show&&!original&&!error))return null;
  const act=async(command:typeof choose|typeof save|typeof discard)=>{
    if(disabled||entered.current||document.hidden||!live()||!available(command))return;
    if(command!==choose&&original?.status!=='target_selected')return;
    entered.current=true;setBusy(true);setError('');
    try{
      const data=await bridge.command(command,{viewHandle:handle,operationId:item.operationId,artifactId:item.artifactId,
        artifactSha256:item.archiveSha256,...(command===choose?{}:{requestId:original!.requestId})});
      if(!document.hidden&&live()&&!sameReceipt(data,item))throw new Error('未能核对这次原整包的另存回执；没有重试。');
    }catch(e){if(!document.hidden&&live())setError(e instanceof Error?e.message:'另存状态未确认，请回原团队核对。');}
    finally{entered.current=false;if(live())setBusy(false);}
  };
  const locked=busy||disabled, awaiting=original?.status==='target_selected';
  const pending=original&&['checking','document_requested','checking_target','selection_returned','writing'].includes(original.status);
  return <div className="pw-outcome-file-copy" aria-label="另存这份原团队完整包">
    {original&&<p className="pw-field-hint" role="status">{original.message}</p>}
    {error&&<p role="alert" className="pw-error">{error}</p>}
    {awaiting&&<p className="pw-field-hint"><bdi>{item.name}</bdi> · {item.fileCount} 个文件 · {item.archiveBytes.toLocaleString()} 字节</p>}
    {awaiting?<div className="pw-action-row">
      <Button size="small" variant="secondary" disabled={locked} onClick={()=>void act(save)}>确认写入所选文件</Button>
      <Button size="small" variant="ghost" disabled={locked} onClick={()=>void act(discard)}>放弃这次另存</Button>
    </div>:!pending&&<Button size="small" variant="secondary" disabled={locked} onClick={()=>void act(choose)}>
      {original?.status==='exported'?'另存一份到新位置':original?'重新选择保存位置':'另存完整 ZIP 到…'}</Button>}
  </div>;
}
