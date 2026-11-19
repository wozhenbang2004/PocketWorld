import { useState } from 'react';
import { Button } from './forms-library';
type Data=Record<string,any>;
const local=(m:Data|undefined)=>!!m&&(m.runner||'model')==='model'&&m.device==='phone'&&!m.remoteProduction;
function frozen(i:Data){
  return i.status==='completed'&&Array.isArray(i.artifacts)&&i.artifacts.length>0&&i.artifacts.every((r:Data)=>r?.artifactId&&r.runId&&
    r.taskId===i.assignmentId&&r.assignmentId===i.assignmentId&&r.memberId===i.memberId);
}
export function TeamFailedWorkHandoff({team,item,members,disabled,review}:{team:Data;item:Data;members:Data[];disabled:boolean;review:(id:string)=>void}){
  const [choosing,setChoosing]=useState(false),child=item.assignmentTask;
  if(team.status!=='awaiting_user'||team.phase!=='waiting_work_item'||team.productionScope?.projectSelection||item.projectSelection||item.remoteSelection||
    !local(members.find(m=>m.id===item.memberId))||item.status!=='failed'||child?.status!=='failed'||!child.runId||
    child.memberId!==item.memberId||child.assignmentId!==item.assignmentId||child.taskId!==item.assignmentId||
    !Array.isArray(team.workPlan?.items)||!team.workPlan.items.every((i:Data)=>i.workItemId===item.workItemId||frozen(i)))return null;
  const targets=members.filter(m=>m.id!==item.memberId&&local(m)&&!m.paused&&!m.exited);
  if(!targets.length)return null;
  return <div className="pw-team-action-list">
    <Button size="small" variant="ghost" aria-expanded={choosing} disabled={disabled} onClick={()=>setChoosing(!choosing)}>{choosing?'收起接手成员':'换一位队友接手这个分工'}</Button>
    {choosing&&<><p className="pw-field-hint">从原团队选择接手者，先核对旧草稿与剩余额度。此时不会开始工作。</p>
      <div className="pw-action-row">{targets.map(m=><Button key={m.id} variant="secondary" size="small" disabled={disabled} onClick={()=>review(m.id)}>交给{m.name||m.id}</Button>)}</div></>}
  </div>;
}
export function FailedHandoffReview({facts:f}:{facts:Data}){
  return <><h3>{f.title}</h3><p>{f.oldMemberName}在这个分工已确定失败。确认后由{f.nextMemberName}接手；只重新排队这一项，其他已冻结交付不重跑。</p>
    <ul aria-label="接手的原隔离草稿范围">{f.files.map((p:string)=>{const v=f.preserved.find((x:Data)=>x.path===p);
      return <li key={p}><bdi>{p}</bdi>{v?<small> · {v.exists?'保留旧草稿版本 '+v.version:'保留旧草稿中不存在的状态'}</small>:<small> · 没有已保存的旧草稿</small>}</li>;})}</ul>
    <p className="pw-field-hint">原写入类成功回执 {f.effectCount} 笔，均保留，不重发。接手者只可在列出的隔离文件范围内{f.canDelete?'增改删':'增改'}，不直接写回原项目。</p>
    <p className="pw-field-hint">沿用原工作项剩余的 {f.carried} 次草稿操作；团队原池尚余 {f.remainingModels} 次模型请求、{f.remainingTools} 次工具操作。不增加额度，不退还旧用量。</p>
  </>;
}
