import {useEffect,useRef,useState} from 'react';
import {Accordion,AccordionItem,Button} from './forms-library';

type Review={planHash:string;status:string;verdict?:string;completeOriginalFilesRead?:boolean;findings?:string};
type File={path:string;workItemId:string;assignmentId:string;memberId:string;workStatus:string;headState:string;state:string;priorArtifactId?:string;writtenVersion?:number;writtenHash?:string;canReadWrittenOriginal?:true;frozenVersion?:number;frozenHash?:string;newArtifactId?:string;retainedChars?:number;truncated?:boolean};
export type RevisionComparison={teamRunId:string;projectId:string;planHash:string;previousPlanHash:string;revisedPlanHash:string;revisionRequestId:string;readOnly:true;files:File[];previousReview:Review;revisedReview:Review};
type Data=Record<string,any>;
const hash=(v:unknown)=>typeof v==='string'&&/^[a-f0-9]{64}$/.test(v);
const priorText=(f:File)=>f.canReadWrittenOriginal===true&&typeof f.priorArtifactId==='string'&&
  /^[a-f0-9-]{36}$/.test(f.priorArtifactId)&&hash(f.writtenHash)&&Number.isSafeInteger(f.writtenVersion)&&Number(f.writtenVersion)>0;
type Basis='written_to_frozen'|'written_original';
type Part={path:string;basis:Basis;text:string;end:number;total:number};
export function originalRevision(v:RevisionComparison,team:string,plan:string,project:string):boolean {
  return !!v&&v.readOnly===true&&v.teamRunId===team&&v.projectId===project&&v.planHash===plan&&hash(v.previousPlanHash)&&hash(v.revisedPlanHash)&&v.previousPlanHash!==v.revisedPlanHash&&
    !!v.revisionRequestId&&v.previousReview?.planHash===v.previousPlanHash&&v.revisedReview?.planHash===v.revisedPlanHash&&Array.isArray(v.files)&&v.files.length>0&&
    v.files.length<=32&&new Set(v.files.map(f=>f.path)).size===v.files.length&&v.files.every(f=>!!f?.path&&!!f.workItemId&&!!f.memberId&&!!f.assignmentId&&
      (f.canReadWrittenOriginal!==true||priorText(f))&&
      (f.state!=='text_changed'||!!f.priorArtifactId&&!!f.newArtifactId&&typeof f.retainedChars==='number'&&Number.isInteger(f.retainedChars)&&f.retainedChars>0));
}
const explanation=(file:File):string=>{
  switch(file.state){
    case 'text_changed':return '文本有修改';
    case 'same_bytes':return '本次重新冻结，内容相同';
    case 'retained_artifact':return '沿用上版同一份冻结原件';
    case 'no_new_freeze':return file.workStatus==='completed'?'上版有写回，本次未交回该路径':'上版有写回，本次尚无该路径的冻结件';
    case 'no_prior_write':return '本次有冻结件，未找到该路径的上版写回';
    case 'no_either_receipt':return '原分工选中了此路径，两版均无可对照的文件回执';
    case 'non_text_changed':return '原件字节有变化，无文本差异';
    case 'deletion_record':return '含删除记录，不将删除当作空文本';
    case 'write_not_verified':return '上版写回回执不足，不能用当前项目代替';
    default:return '未能完整取得对应原件，不能判断是否修改';
  }
};
function reviewState(review:Review):string {
  if(review.status==='not_all_frozen')return '原作者还未交齐，暂无完整文件组';
  if(review.status==='not_recorded')return '没有这版计划的独审记录';
  if(review.status==='completed'&&review.completeOriginalFilesRead&&review.verdict){
    return ({no_blocking_findings:'已读完整冻结件，未报阻断',changes_requested:'已读完整冻结件，要求修改',inconclusive:'已读完整冻结件，未能判断'} as Record<string,string>)[review.verdict]||'独审结论待核对';
  }
  if(review.status==='running'||review.status==='preparing'||review.status==='queued')return '已登记独审，尚未结算';
  if(review.status==='failed'||review.status==='cancelled'||review.status==='interrupted')return '独审已停下，未取得完整结论';
  return '独审没有可确认的完整结论';
}
export function TeamRevisionFileComparison({source,view,handle,run,memberName,openProject}:{
  source:RevisionComparison;view:string;handle:string;run:(action:string,args:Data)=>Promise<any>;memberName:(id:string)=>string;openProject:(id:string)=>void;
}){
  const [selected,choose]=useState(''),[part,setPart]=useState<Part|null>(null);
  const [busy,setBusy]=useState(false),[error,setError]=useState('');const seq=useRef(0),loading=useRef(false);
  useEffect(()=>()=>{seq.current++;},[source,handle,view]);
  const read=async(f:File,offset:number,basis:Basis='written_to_frozen')=>{
    if(loading.current)return;
    if(!offset){choose(f.path);setPart(null);}
    const original=basis==='written_original';
    setError('');if(original?!priorText(f):f.state!=='text_changed')return;
    if(offset&&(!part||part.path!==f.path||part.basis!==basis||part.end!==offset))return;
    const n=++seq.current;loading.current=true;setBusy(true);
    try{
      const v=await run('teams.readFrozenDiff',{viewHandle:view,fileHandle:handle,basis,path:f.path,offset,
        ...(original?{artifactId:f.priorArtifactId}:{})});
      if(n!==seq.current)return;
      const body=original?v.text:v.diff;
      if(v.readOnly!==true||v.basis!==basis||v.fileHandle!==handle||v.teamRunId!==source.teamRunId||v.planHash!==source.planHash||
        v.previousPlanHash!==source.previousPlanHash||v.revisionRequestId!==source.revisionRequestId||
        v.path!==f.path||v.assignmentId!==f.assignmentId||v.priorArtifactId!==f.priorArtifactId||
        (original?v.executionAuthorized!==false||v.writtenHash!==f.writtenHash||v.writtenVersion!==f.writtenVersion||!Number.isSafeInteger(v.retainedChars)||
          v.retainedChars<0||v.retainedChars>65536||offset>0&&v.retainedChars!==part?.total:
          v.revisedPlanHash!==source.revisedPlanHash||v.newArtifactId!==f.newArtifactId||v.retainedChars!==f.retainedChars)||
        typeof body!=='string'||v.offset!==offset||v.endOffset!==offset+body.length||
        v.endOffset>v.retainedChars||v.endOffset===offset&&v.endOffset<v.retainedChars)
        throw new Error('返回的内容不属于所选文件的原版本，没有替换成其他原件。');
      setPart({path:f.path,basis,text:(offset?part?.text||'':'')+body,end:v.endOffset,total:v.retainedChars});
    }catch(e){if(n===seq.current)setError(e instanceof Error?e.message:String(e));}
    finally{if(n===seq.current){loading.current=false;setBusy(false);}}
  };
  return <section className="pw-form-stack" aria-label="上版写回与本次冻结">
    <p>上版写回与本次冻结 · {source.files.length} 个原选路径</p>
    <p className="pw-field-hint">按文件核对两次原件。项目后来改过的内容不混入本次修订；阅读不批准或写回。</p>
    <Accordion variant="divided"><AccordionItem id="version-reviews" title="两版独审" description={'本次：'+reviewState(source.revisedReview)}>
      {([['上版',source.previousReview],['本次',source.revisedReview]] as const).map(([title,r])=><section key={title}><h3>{title} · {reviewState(r)}</h3>
        <p className="pw-field-hint">文件计划 <code>{r.planHash.slice(0,12)}</code>{title==='上版'?' · 该意见不沿用到新稿':''}</p>
        {r.findings&&<p className="pw-team-expanded-copy">独审原意见：{r.findings}</p>}</section>)}
      <p className="pw-field-hint">独审评价对应的冻结文件，不代表项目验收或主人的写回许可。</p>
    </AccordionItem></Accordion>
    {source.files.map(f=><section className="pw-team-expanded-copy" key={f.path}><h3>{f.path}</h3><p>{explanation(f)}</p>
      {selected!==f.path?<Button size="small" variant="ghost" disabled={busy}
        onClick={()=>void read(f,0,f.state!=='text_changed'&&priorText(f)?'written_original':'written_to_frozen')}>
        查看{f.state==='text_changed'?'两版差异':priorText(f)?'上版原文':'文件依据'}</Button>:<>
        <p className="pw-field-hint">{memberName(f.memberId)||f.memberId}{typeof f.writtenVersion==='number'?` · 上版写回项目 v${f.writtenVersion}`:''}
          {f.headState==='matches_written'?' · 打开时项目仍是该写回版本':f.headState==='changed'?' · 打开时项目已不是上版写回现场':' · 没有可核对的上版项目现场'}</p>
        {busy&&!part&&<p role="status">正在读取所选原件…</p>}{error&&<p role="alert">{error}</p>}
        {part?.path===f.path&&<>
          {part.basis==='written_original'&&<p className="pw-field-hint">上版实际写回的原文，不是项目当前内容。</p>}
          {part.text?<pre className="pw-team-file-diff" tabIndex={0}
            aria-label={f.path+(part.basis==='written_original'?' 的上版原文':' 从上版写回到本次冻结的差异')}>{part.text}</pre>:<p>上版写回文件为空。</p>}
          {part.end<part.total&&<Button variant="ghost" disabled={busy} onClick={()=>void read(f,part.end,part.basis)}>继续读取（{part.end}/{part.total}）</Button>}
          {part.basis==='written_to_frozen'&&f.truncated&&<p className="pw-field-hint">原生比较只保留部分差异，不能算完整阅读。</p>}</>}
        {(f.writtenHash||f.frozenHash)&&<Accordion variant="divided"><AccordionItem id="fingerprints" title="核对文件版本与指纹">
          {f.writtenHash&&<p className="pw-field-hint">上版写回 · 项目 v{f.writtenVersion}<br/><code style={{overflowWrap:'anywhere'}}>{f.writtenHash}</code></p>}
          {f.frozenHash&&<p className="pw-field-hint">本次冻结 · 草稿 v{f.frozenVersion}<br/><code style={{overflowWrap:'anywhere'}}>{f.frozenHash}</code></p>}
        </AccordionItem></Accordion>}
        <div className="pw-form-actions">
          {priorText(f)&&part?.basis!=='written_original'&&<Button variant="ghost" disabled={busy} onClick={()=>void read(f,0,'written_original')}>查看上版原文</Button>}
          {f.state==='text_changed'&&part?.basis!=='written_to_frozen'&&<Button variant="ghost" disabled={busy} onClick={()=>void read(f,0)}>查看两版差异</Button>}
          {f.newArtifactId&&<Button variant="ghost" disabled={busy} onClick={()=>openProject(f.newArtifactId!)}>另看打开时的项目对照</Button>}
          <Button variant="ghost" disabled={busy} onClick={()=>{choose('');setPart(null);setError('');}}>收起</Button></div>
      </>}</section>)}
  </section>;
}
