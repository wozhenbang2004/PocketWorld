import {useState} from 'react';
import {Button} from './forms-library';
import {fileBytes} from './attachment-workspace';

type Data=Record<string,any>;
type Origin={sourceRecorded:false}|{sourceRecorded:true;sourceName:string;archiveSha256:string;archiveBytes:number;files:number;evidenceDigest:string;taskId?:string;operationId?:string};
const digest=(v:unknown):v is string=>typeof v==='string'&&/^[a-f0-9]{64}$/.test(v);
const id=(v:unknown):v is string=>typeof v==='string'&&v.length>0&&v.length<=160&&!/[\u0000-\u001f]/.test(v);
const inspect='projects.development.taskPackageSource',resolve='projects.development.resolveTaskPackageSource';
export function ProjectTaskPackageOrigin({project,checkout,view,disabled,invoke,can,open}:{project:string;checkout:string;view:string;disabled:boolean;
  invoke:(action:string,args:Data,accept:(value:Data)=>void)=>Promise<unknown>;can:(action:string)=>boolean;open?:((id:string)=>void)}){
  const [seen,setSeen]=useState<Origin|null>(null);
  if(!can(inspect))return null;
  const read=(jump:boolean)=>{
    if(disabled||document.hidden||!view||jump&&(!seen?.sourceRecorded||!seen.taskId||!open||!can(resolve)))return;
    void invoke(jump?resolve:inspect,{viewHandle:view,checkoutId:checkout,...(jump&&seen?.sourceRecorded?{evidenceDigest:seen.evidenceDigest}:{})},raw=>{
      if(raw?.projectId!==project||raw.checkoutId!==checkout||typeof raw.sourceRecorded!=='boolean'||typeof raw.taskVerified!=='boolean')throw Error('未取得所选副本的原来源。');
      if(!raw.sourceRecorded){if(jump||raw.taskVerified)throw Error('不能核对原工作。');setSeen({sourceRecorded:false});return;}
      if(!digest(raw.evidenceDigest)||!digest(raw.archiveSha256)||typeof raw.sourceName!=='string'||!Number.isSafeInteger(raw.files)||raw.files<0||raw.files>2000||
          !Number.isSafeInteger(raw.archiveBytes)||raw.archiveBytes<=0||raw.archiveBytes>20*1024*1024||
          raw.taskVerified&&(!id(raw.taskId)||!id(raw.operationId)))throw Error('原任务源码回执不完整。');
      if(jump){if(!seen?.sourceRecorded||!seen.taskId||!raw.taskVerified||seen.evidenceDigest!==raw.evidenceDigest||seen.taskId!==raw.taskId||seen.operationId!==raw.operationId)
        throw Error('原工作或固定操作已不同，未打开其他任务。');open!(raw.taskId);return;}
      setSeen({sourceRecorded:true,sourceName:raw.sourceName,archiveSha256:raw.archiveSha256,archiveBytes:raw.archiveBytes,files:raw.files,evidenceDigest:raw.evidenceDigest,
        ...(raw.taskVerified?{taskId:raw.taskId,operationId:raw.operationId}:{})});
    });
  };
  return <section className="pw-form-actions" aria-label="新项目的固定源码来源">
    {!seen?<Button size="small" variant="ghost" disabled={disabled} onClick={()=>read(false)}>核对来源工作</Button>
    :seen.sourceRecorded?<><p><bdi>{seen.sourceName}</bdi> · 从原工作保存的 {seen.files} 个文件另建，不代表现在的修改。</p>
      <details><summary>固定源码包</summary><p>{fileBytes(seen.archiveBytes)} · SHA-256 <bdi>{seen.archiveSha256}</bdi></p></details>
      {seen.taskId&&open&&can(resolve)&&can('tasks.open')?<Button size="small" variant="secondary" disabled={disabled} onClick={()=>read(true)}>打开保存这版的原工作</Button>:
        <p className="pw-field-hint">保留了导入时的来源。目前不能打开对应的原私人工作。</p>}</>
    :<p className="pw-field-hint">这份旧副本没有可核对的固定包来源记录，未按文件名猜测原任务。</p>}
    {seen&&<Button size="small" variant="ghost" disabled={disabled} onClick={()=>read(false)}>重新核对</Button>}
  </section>;
}
