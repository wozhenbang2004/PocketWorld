import {useEffect,useRef,useState} from 'react';
import {bridge} from './bridge';
import {Button} from './forms-library';

type Data=Record<string,any>;
type Locator={taskId:string;runId:string;epoch:number;approvalId:string;operationId:string;path:string;role:string;workTitle:string;projectId:string;expectedVersion:number;draftVersion:number;expiresAt:number;proof:string};
type Reading={text:string;end:number;total:number;hash:string;truncated:boolean;exists:boolean};
const uuid=(v:unknown):v is string=>typeof v==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(v);
const hash=(v:unknown):v is string=>typeof v==='string'&&/^[a-f0-9]{64}$/.test(v);
const count=(v:unknown):v is number=>typeof v==='number'&&Number.isSafeInteger(v)&&v>=0;
export function originalTeamApprovalFile(approval:Data):Locator|null{
  const m=approval.webReview?.originalTeamFile,cap=approval.capability,file=cap?.file,args=approval.arguments;
  if(!m||approval.tool!=='team_integrate_file'||m.readOnly!==true||cap?.memberId!=='integrator'||!uuid(m.taskId)||cap?.assignmentId!==m.taskId||
    !uuid(m.runId)||approval.runId!==m.runId||!count(m.epoch)||m.epoch<1||!uuid(m.approvalId)||(approval.id||approval.approvalId)!==m.approvalId||
    !uuid(m.operationId)||!hash(m.proof)||!count(m.expiresAt)||m.expiresAt!==approval.expiresAt||m.expiresAt<=Date.now()||
    !file||file.artifactKind!=='text_file'||!args||file.path!==args.path||file.artifactId!==args.artifactId||m.path!==args.path||m.role!==file.memberId||
    typeof m.path!=='string'||!m.path||typeof m.workTitle!=='string'||!m.workTitle||m.workTitle.length>120||
    m.projectId!==cap.targetProjectId||m.projectId!==file.targetProjectId||!count(m.expectedVersion)||m.expectedVersion!==file.expectedVersion||
    !count(m.draftVersion)||m.draftVersion!==file.draftVersion)return null;
  return m;
}

/** One original pending file only. Its text lives in this private reader, never the chat, draft or a continuation. */
export function TeamApprovalFileComparison({source,disabled}:{source:Locator;disabled:boolean}){
  const [read,setRead]=useState<Reading|null>(null),[busy,setBusy]=useState(false),[error,setError]=useState('');
  const [expired,setExpired]=useState(false),seq=useRef(0),pending=useRef(false);
  const clear=()=>{seq.current++;pending.current=false;setRead(null);setBusy(false);setError('')};
  useEffect(()=>{
    const reset=()=>{if(document.hidden)clear()};
    const expires=()=>{if(Date.now()>=source.expiresAt){clear();setExpired(true)}};
    setExpired(false);expires();const timer=window.setTimeout(expires,Math.min(2147483647,Math.max(0,source.expiresAt-Date.now())));
    document.addEventListener('visibilitychange',reset);
    return()=>{seq.current++;pending.current=false;clearTimeout(timer);document.removeEventListener('visibilitychange',reset)};
  },[source.proof,source.expiresAt]);
  useEffect(()=>{if(disabled)clear()},[disabled]);
  const original=()=>{const view=bridge.getState().view;return !!view&&typeof view==='object'&&(view as Data).taskId===source.taskId};
  const load=async(prior:Reading|null)=>{
    if(disabled||pending.current||document.hidden||Date.now()>=source.expiresAt||!original())return;
    pending.current=true;setBusy(true);setError('');const n=++seq.current,doc=bridge.getDocumentEpoch(),off=prior?.end??0;
    const current=()=>n===seq.current&&!document.hidden&&doc===bridge.getDocumentEpoch()&&original();
    try{
      const p=await bridge.command('tasks.readOriginalTeamFileDiff',{taskId:source.taskId,runId:source.runId,epoch:source.epoch,approvalId:source.approvalId,proof:source.proof,offset:off,diffHash:prior?.hash??''});
      if(!current())return;
      if(Date.now()>=source.expiresAt||p.readOnly!==true||p.executionAuthorized!==false||p.approvalId!==source.approvalId||p.operationId!==source.operationId||
        p.proof!==source.proof||!hash(p.diffHash)||typeof p.diff!=='string'||p.offset!==off||!count(p.endOffset)||!count(p.retainedChars)||p.retainedChars>65536||
        p.endOffset>p.retainedChars||p.endOffset-off!==p.diff.length||p.diff.length>6000||(!p.diff.length&&p.retainedChars>0)||
        typeof p.nativeTruncated!=='boolean'||typeof p.baseExists!=='boolean'||prior&&(prior.hash!==p.diffHash||prior.total!==p.retainedChars||prior.truncated!==p.nativeTruncated||prior.exists!==p.baseExists)){
        throw new Error('没有取得同一笔原审批的连续差异，已收起旧内容。');
      }
      setRead({text:(prior?.text??'')+p.diff,end:p.endOffset,total:p.retainedChars,hash:p.diffHash,truncated:p.nativeTruncated,exists:p.baseExists});
    }catch(e){if(current()){setRead(null);setError(e instanceof Error?e.message:'当前基线或原件不可核对；未读取其他文件。')}}
    finally{if(current()){pending.current=false;setBusy(false)}}
  };
  if(expired||!bridge.getState().capabilities.commands.includes('tasks.readOriginalTeamFileDiff'))return null;
  return <section className="pw-decision-section" aria-label="这次文件写回">
    <h5>本次写回 · <bdi>{source.path}</bdi></h5>
    <p>原分工「{source.workTitle}」交付 v{source.draftVersion}，对应目标基线 v{source.expectedVersion}。只核对这一份，不启动整合。</p>
    {!read&&<Button variant="ghost" size="small" disabled={busy||disabled} onClick={()=>void load(null)}>{busy?'正在核对原文件…':'查看这份文件的实际差异'}</Button>}
    {read&&<><p>{read.exists?'基线中已有这个文件。':'基线中没有这个文件，本次是新增。'} {read.truncated?'原生差异已截断，不是全文。':read.end<read.total?'下方仍有未读差异。':'已显示这份文件的完整文本差异。'}</p>
      {read.text?<pre className="pw-approval-json" tabIndex={0} aria-label="原项目基线与冻结交付的文本差异">{read.text}</pre>:<p>两份文本均无不同；这不是已写回的回执。</p>}
      {read.end<read.total&&<Button variant="secondary" size="small" disabled={busy||disabled} onClick={()=>void load(read)}>{busy?'核对中…':'继续读取下一段差异'}</Button>}
      <Button variant="ghost" size="small" onClick={clear}>收起差异</Button>
    </>}
    {error&&<p role="alert">{error}</p>}
  </section>;
}
