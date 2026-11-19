import { Accordion, AccordionItem } from './forms-library';
import type { DeviceData } from './devices-workspace';

const record=(v:unknown):v is DeviceData=>!!v&&typeof v==='object'&&!Array.isArray(v);
const integer=(v:unknown,min:number)=>typeof v==='number'&&Number.isSafeInteger(v)&&v>=min;

// The received request is untrusted data. Render plain React text only: no linkification, path opening, or data fetch.
export function originalDeviceInputReady(view:DeviceData | undefined) {
  const i=view?.originalInput;
  if(!record(i)||!view||typeof view.goal!=='string'||!view.goal.trim()||i.format!=='pocketworld.original-goal-input.v1'
    ||typeof i.sourceOperationId!=='string'||!i.sourceOperationId||i.sourceOperationId!==view.origin?.remoteOperationId
    ||typeof i.originDeviceId!=='string'||!/^pw1_[a-f0-9]{64}$/.test(i.originDeviceId)||i.originDeviceId!==view.origin?.originDeviceId)return false;
  if(i.mode==='goal_text_only'||i.mode==='no_selected_context')return i.snapshot===undefined;
  const x=i.snapshot;
  return i.mode==='selected_context'&&record(x)&&x.format==='pocketworld.device-context.v1'&&x.originDeviceId===i.originDeviceId
    &&x.retention==='task_only'&&x.untrusted===true&&x.authorityTransferred===false;
}
export function ReceivedGoalInputReview({view}:{view:DeviceData}) {
  if(!originalDeviceInputReady(view))return <p role="alert">原目标和附带资料未能核对，请返回本机接单记录重新打开。</p>;
  const i=view.originalInput;
  if(i.mode==='goal_text_only')return <p>对方提供给这次模型工作的只有上面的目标文字，不带屏幕、文件引用或附加资料。</p>;
  if(i.mode==='no_selected_context')return <p>发起设备没有为这项目标附带额外资料。</p>;
  const x=i.snapshot, artifacts=x.artifacts, constraints=x.constraints??[];
  const structured=typeof x.snapshotId==='string'&&integer(x.revision,1)&&(x.summary===undefined||typeof x.summary==='string')
    &&Array.isArray(constraints)&&constraints.length<=32&&constraints.every((v:unknown)=>typeof v==='string')
    &&Array.isArray(artifacts)&&artifacts.length<=32&&artifacts.every((v:unknown)=>record(v)&&v.deviceId===i.originDeviceId
      &&typeof v.taskId==='string'&&typeof v.artifactId==='string'&&typeof v.sha256==='string'&&/^[a-f0-9]{64}$/.test(v.sha256)&&integer(v.size,0)&&integer(v.version,1));
  const summary=structured&&typeof x.summary==='string'?x.summary:'';
  return <section className="pw-device-result" aria-label="发起设备选择的参考资料">
    <h4>对方附带的参考</h4>
    <p>这些资料可供所选模型参考，不是本机指令或额外权限；文件引用不含正文。</p>
    <Accordion><AccordionItem id="received-goal-selected-input" title={structured?'核对摘要、限制和文件引用':'查看原参考记录'}
      description={structured?[summary?'有摘要':null,constraints.length?constraints.length+' 条限制':null,artifacts.length?artifacts.length+' 个文件引用':null].filter(Boolean).join(' · ')||'没有摘要、限制或文件引用':'以原设备提交的数据为准'}>
      {structured?<><p>原参考版本 {x.revision}</p>
        {summary&&<><h5>对方选定的摘要</h5><p dir="auto" style={{whiteSpace:'pre-wrap',overflowWrap:'anywhere'}}>{summary}</p></>}
        {!!constraints.length&&<><h5>对方写的限制</h5><ol>{constraints.map((v:string,index:number)=><li key={index} dir="auto" style={{whiteSpace:'pre-wrap',overflowWrap:'anywhere'}}>{v}</li>)}</ol></>}
        {!!artifacts.length&&<><h5>文件引用，不会自动打开</h5><ol>{artifacts.map((v:DeviceData,index:number)=><li key={index} style={{overflowWrap:'anywhere'}}>
          <p>版本 {v.version} · {v.size.toLocaleString()} 字节</p><p>原文件 <bdi>{v.artifactId}</bdi><br/>来源任务 <bdi>{v.taskId}</bdi><br/>SHA-256 <code>{v.sha256}</code></p></li>)}</ol></>}
      </>:<pre dir="auto" style={{whiteSpace:'pre-wrap',overflowWrap:'anywhere'}}>{JSON.stringify(x,null,2)}</pre>}
    </AccordionItem></Accordion>
  </section>;
}
