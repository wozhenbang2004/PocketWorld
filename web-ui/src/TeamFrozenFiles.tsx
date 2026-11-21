import { useEffect, useLayoutEffect, useRef, useState, type MutableRefObject } from 'react';
import { Button, CheckboxField, SettingsDialog } from './forms-library';
import { bridge } from './bridge';
import {TeamRevisionFileComparison,originalRevision,type RevisionComparison} from './TeamRevisionFileComparison';
import {sameProjectFileDecision,sameProjectTeamReview,type ProjectTeamReviewOrigin} from './project-team-review-origin';
import type {WorkFileSource} from './team-navigation';

type Data = Record<string, any>;
type FileRow = {artifactId:string;assignmentId:string;memberId:string;path:string;kind:'text'|'deletion'|'binary_or_large';sha256:string;retainedChars?:number;currentVersion:number;currentExists:boolean;matchesOwnerBase:boolean;retainedApplied:boolean;truncated:boolean;beforeBytes?:number;afterBytes?:number};
export type FrozenMemberFile = Pick<FileRow,'artifactId'|'assignmentId'|'memberId'|'path'|'sha256'> & {projectId:string};
type MemberEntry = {requestId:string; documentEpoch:number; file:FrozenMemberFile; consume:(requestId:string)=>void};
type Approval = {teamRunId:string;planHash:string;projectId:string;files:{path:string;existing:boolean;version?:number;sha256?:string}[];approvalHandle:string;startsIntegration:false;firstProject?:boolean;parentCheckoutId?:string;sourceDigest?:string;snapshotIdentity?:string;reviewRouteCaptured?:boolean;reviewerTaskId?:string;reviewerRunId?:string;startsChecks?:false;packagesProject?:false;originalReviewerFindings?:OriginalReviewerFinding};
type OriginalReviewerFinding={teamRunId:string;planHash:string;taskId:string;runId:string;epoch:number;role:'reviewer';verdict:'no_blocking_findings';findings:string;readOnly:true;executionAuthorized:false;businessVerified:false};
function originalReviewerFinding(approval:Approval|null):OriginalReviewerFinding|null{
  const row=approval?.originalReviewerFindings;
  return approval&&!approval.firstProject&&row?.role==='reviewer'&&row.verdict==='no_blocking_findings'&&row.readOnly===true&&
    row.executionAuthorized===false&&row.businessVerified===false&&row.teamRunId===approval.teamRunId&&row.planHash===approval.planHash&&
    !!row.taskId&&row.taskId===approval.reviewerTaskId&&!!row.runId&&row.runId===approval.reviewerRunId&&
    Number.isSafeInteger(row.epoch)&&row.epoch>0&&typeof row.findings==='string'&&!!row.findings.trim()&&row.findings.length<=4000?row:null;
}
type IntegrationCapture={integrationTaskId:string;reviewId:string;planHash:string;matchingFiles:number;total:number;active:boolean;unresolved:boolean;status:string;businessVerified:false};
type Finding={verdict:'changes_requested'|'inconclusive';findings:string;businessVerified:false;executionAuthorized:false;originalReviewer:{teamRunId:string;taskId:string;runId:string;epoch:number;role:'reviewer';planHash:string;canOpen:true}};
type IndependentReview = {reviewHandle:string;fileHandle:string;teamRunId:string;planHash:string;projectId:string;model:string;provider:string;
  remainingModelRequests:number;remainingToolOperations:number;files:{path:string;sha256:string}[]};
type Decision={state:string;reviewId?:string;registrationKind?:string;separateIntegratorStartRequired?:boolean;canStartIndependentReview?:true}&Partial<Finding>;
function blockedFinding(decision:Decision|undefined,team:string,hash:string):Finding|null{
  const r=decision?.originalReviewer;
  return decision?.state==='independent_finding'&&decision.businessVerified===false&&decision.executionAuthorized===false&&
    (decision.verdict==='changes_requested'||decision.verdict==='inconclusive')&&typeof decision.findings==='string'&&!!decision.findings.trim()&&decision.findings.length<=4000&&
    r?.role==='reviewer'&&r.teamRunId===team&&r.planHash===hash&&r.canOpen===true&&!!r.taskId&&!!r.runId&&Number.isSafeInteger(r.epoch)&&r.epoch>0?decision as Finding:null;
}
type Reading = {projectReviewOrigin?:ProjectTeamReviewOrigin;fullProject?:boolean;revisionComparison?:RevisionComparison;revisionWarning?:string;projectId:string;integrationCapture?:IntegrationCapture;ownerDecision?:Decision;fileHandle:string;teamRunId:string;planHash:string;files:FileRow[];incompleteCount:number;capturedAt:number;readOnly:true};
type Page = {id:string;body:string;end:number;size:number};

export function TeamFrozenFiles({team, view, active, disabled, run, onOpen, back, memberName, entry, memberEntry, hasRevision=false}:{
  team:string;view:string;hasRevision?:boolean;active:boolean;disabled:boolean;run:(action:string,args:Data)=>Promise<any>;
  entry?:{requestId:string;source:WorkFileSource|ProjectTeamReviewOrigin;returnToWork?:(reason?:string)=>void};memberEntry?:MemberEntry;memberName:(id:string)=>string;onOpen:(shown:boolean)=>void;back:MutableRefObject<(()=>boolean)|null>;
}) {
  const [shown,setShown]=useState(false),[source,setSource]=useState<Reading|null>(null),[selected,setSelected]=useState(''),[page,setPage]=useState<Page|null>(null);
  const [busy,setBusy]=useState(false),[error,setError]=useState(''),[memberFirst,setMemberFirst]=useState('');
  const [allLoaded,setAllLoaded]=useState(false),[approval,setApproval]=useState<Approval|null>(null),[ack,setAck]=useState(false);
  const [independent,setIndependent]=useState<IndependentReview|null>(null);
  const [independentState,setIndependentState]=useState<'ready'|'started'|'unknown'>('ready');
  const [completeFiles,setCompleteFiles]=useState<{fileHandle:string;ids:ReadonlySet<string>}|null>(null);
  const nextFocus=useRef<{fileHandle:string;artifactId:string;origin:Element|null}|null>(null), fileSection=useRef<HTMLElement>(null);
  const [projectView,setProjectView]=useState(false);
  const [registered,setRegistered]=useState(''),[unknown,setUnknown]=useState(false);
  const generation=useRef(0),current=useRef<Reading|null>(null),pending=useRef(false),opened=useRef(false);
  const release=(handle?:string)=>{if(handle)void bridge.command('teams.closeReading',{fileHandle:handle}).catch(()=>{});};
  const close=()=>{generation.current++;pending.current=false;opened.current=false;release(current.current?.fileHandle);current.current=null;
    setProjectView(false);setAllLoaded(false);setCompleteFiles(null);nextFocus.current=null;setApproval(null);setAck(false);setIndependent(null);setIndependentState('ready');setRegistered('');setUnknown(false);setBusy(false);setShown(false);setSource(null);setSelected('');setPage(null);setMemberFirst('');setError('');onOpen(false);};
  const returnToRevision=()=>{generation.current++;pending.current=false;setBusy(false);setError('');setProjectView(false);};
  const dismiss=()=>{close();if(entry&&!document.hidden)entry.returnToWork?.()};
  useEffect(()=>()=>{generation.current++;opened.current=false;pending.current=false;release(current.current?.fileHandle);current.current=null;onOpen(false);},[view,team,onOpen]);
  useEffect(()=>{if(!active&&opened.current)close();},[active]);
  useEffect(()=>{
    if(!shown)return;const handler=()=>{if(independent&&!pending.current)cancelIndependent();else if(projectView)returnToRevision();else dismiss();return true;};back.current=handler;
    const hide=()=>{if(document.visibilityState!=='visible')close();};
    document.addEventListener('visibilitychange',hide);
    return()=>{if(back.current===handler)back.current=null;document.removeEventListener('visibilitychange',hide);};
  },[shown,back,projectView,independent]);
  const open=async(origin?:WorkFileSource|ProjectTeamReviewOrigin, member?:FrozenMemberFile)=>{
    const fromWork=origin&&'fileReview' in origin?origin:undefined,fromProject=origin&&'selectionHash' in origin?origin:undefined;
    if(disabled||!active||opened.current||!view)return;
    const seq=++generation.current;opened.current=true;pending.current=true;onOpen(true);setShown(true);setBusy(true);setError('');
    let selectedFile:FileRow|undefined;
    try{
      const result=await run('teams.readFrozenOutputs',{viewHandle:view,...(fromWork?{fromWork}:fromProject?{fromProject}:{})}) as Reading;
      if(seq!==generation.current){release(result?.fileHandle);return;}
      if(!result?.fileHandle||result.readOnly!==true||result.teamRunId!==team||!Array.isArray(result.files)||!/^[a-f0-9]{64}$/.test(result.planHash)){
        release(result?.fileHandle);throw new Error('这份目录不属于原协作，请返回重新读取。');
      }
      if(result.ownerDecision?.state==='independent_finding'&&!blockedFinding(result.ownerDecision,team,result.planHash)){
        release(result.fileHandle);throw new Error('审阅意见与这版冻结文件不匹配，没有显示其他轮次。');
      }
      if(result.revisionComparison&&(!originalRevision(result.revisionComparison,team,result.planHash,result.projectId)||result.ownerDecision?.state!=='read_only'||
          result.revisionComparison.files.some(row=>row.newArtifactId&&!result.files.some(f=>f.artifactId===row.newArtifactId&&f.assignmentId===row.assignmentId&&f.path===row.path)))){
        release(result.fileHandle);throw new Error('修订前后的原件目录不匹配，没有展示其他版本。');
      }
      const integration=result.integrationCapture;
      if(integration&&(integration.businessVerified!==false||!integration.integrationTaskId||!integration.reviewId||!/^[a-f0-9]{64}$/.test(integration.planHash)
          ||integration.total!==result.files.length||integration.matchingFiles!==result.files.filter(file=>file.retainedApplied).length
          ||typeof integration.active!=='boolean'||typeof integration.unresolved!=='boolean')){release(result.fileHandle);throw new Error('原整合回执与这份目录不匹配，没有把它算作已写回。');}
      if(fromWork&&(result.planHash!==fromWork.fileReview.planHash||result.files.length!==fromWork.fileReview.fileCount||result.ownerDecision?.state!=='eligible')){
        release(result.fileHandle);throw new Error('原文件不再等待这次核对，没有展示其他版本。');
      }
      if(fromProject&&(!sameProjectTeamReview(fromProject,result.projectReviewOrigin)||result.fullProject!==true||result.projectId!==fromProject.projectId||
          result.planHash!==fromProject.planHash||result.files.length!==fromProject.fileCount||!sameProjectFileDecision(fromProject,result.ownerDecision))){
        release(result.fileHandle);throw new Error('这份原项目首版独审或冻结目录已变，没有改到另一项。');
      }
      if(member){
        const matches=result.files.filter(file=>file.artifactId===member.artifactId);
        const file=matches[0];
        if(result.fullProject!==false||result.projectId!==member.projectId||result.revisionComparison||
           matches.length!==1||file.kind!=='text'||file.assignmentId!==member.assignmentId||file.memberId!==member.memberId||
           file.path!==member.path||file.sha256!==member.sha256){
          release(result.fileHandle);throw new Error('这份原分工的冻结文本已变化，未打开其他版本。');
        }
        selectedFile=file;
        setMemberFirst(file.artifactId);
      }
      current.current=result;setSource(result);
    }catch(e){if(seq===generation.current){const reason=e instanceof Error?e.message:String(e);if(origin&&entry?.returnToWork&&!document.hidden){close();entry.returnToWork(reason)}else setError(reason);}}
    finally{if(seq===generation.current){pending.current=false;setBusy(false);}}
    // This is the exact file selected by the owner's original button, not a prefetch.
    if(seq===generation.current&&selectedFile)void read(selectedFile,0);
  };
  const usedEntry=useRef('');
  useEffect(()=>{
    const target=entry??memberEntry;
    if(!target||usedEntry.current===target.requestId||disabled||!active||!view||opened.current)return;
    usedEntry.current=target.requestId;
    if(memberEntry)memberEntry.consume(memberEntry.requestId);
    if(entry)void open(entry.source);
    else if(memberEntry&&!document.hidden&&bridge.getDocumentEpoch()===memberEntry.documentEpoch){
      void open(undefined,memberEntry.file);
    }
  },[entry?.requestId,memberEntry?.requestId,disabled,active,view]);
  const read=async(row:FileRow,offset:number,reveal=false)=>{
    const original=current.current;if(!original||pending.current||!opened.current||
      reveal&&(!active||document.hidden||original!==source||!original.files.includes(row)))return;
    const seq=++generation.current,origin=document.activeElement;nextFocus.current=null;
    pending.current=true;setBusy(true);setError('');setSelected(row.artifactId);if(!offset)setPage(null);
    try{
      const part=await run('teams.readFrozenDiff',{viewHandle:view,fileHandle:original.fileHandle,artifactId:row.artifactId,offset});
      if(seq!==generation.current)return;
      if(part?.readOnly!==true||part.fileHandle!==original.fileHandle||part.teamRunId!==team||part.planHash!==original.planHash||part.artifactId!==row.artifactId||part.assignmentId!==row.assignmentId
         ||part.offset!==offset||part.retainedChars!==row.retainedChars||typeof part.diff!=='string'||part.endOffset!==offset+part.diff.length
         ||part.endOffset>part.retainedChars||(part.endOffset===offset&&offset<part.retainedChars))throw new Error('差异片段身份或范围不符，没有替换成其他文件。');
      if(part.allTextRangesReturned===true)setAllLoaded(true);
      if(original.fullProject===false&&original.ownerDecision?.state==='eligible'&&!original.revisionComparison&&
         part.nativeTruncated===false&&row.truncated===false&&part.endOffset===part.retainedChars&&
         (offset===0||page?.id===row.artifactId&&page.end===offset&&page.body.length===offset)){
        setCompleteFiles(old=>({fileHandle:original.fileHandle,
          ids:new Set([...(old&&old.fileHandle===original.fileHandle?old.ids:[]),row.artifactId])}));
      }
      if(reveal)nextFocus.current={fileHandle:original.fileHandle,artifactId:row.artifactId,origin};
      setPage(old=>({id:row.artifactId,body:(offset&&old?.id===row.artifactId&&old.end===offset?old.body:'')+part.diff,end:part.endOffset,size:part.retainedChars}));
    }catch(e){if(seq===generation.current)setError(e instanceof Error?e.message:String(e));}
    finally{if(seq===generation.current){pending.current=false;setBusy(false);}}
  };

  useLayoutEffect(()=>{
    const next=nextFocus.current;
    if(!next||page?.id!==next.artifactId)return;
    nextFocus.current=null;
    if(!active||document.hidden||current.current?.fileHandle!==next.fileHandle||selected!==next.artifactId||
       document.activeElement!==next.origin&&document.activeElement!==document.body)return;
    const section=fileSection.current;
    if(section?.isConnected){section.focus({preventScroll:true});section.scrollIntoView({block:'start'});}
  },[active,page,selected]);

  const openOriginalReviewer=async()=>{
    const original=current.current;
    if(!original||!opened.current||pending.current||document.hidden||!active||!blockedFinding(original.ownerDecision,team,original.planHash))return;
    const at=++generation.current;pending.current=true;setBusy(true);setError('');
    try{await run('teams.openDeliveryWorker',{viewHandle:view,fileHandle:original.fileHandle});
      if(generation.current===at&&current.current===original)close();
    }catch(e){if(generation.current===at&&current.current===original)setError(e instanceof Error?e.message:'无法核对这次原审阅任务，没有打开其他任务。');}
    finally{if(generation.current===at){pending.current=false;setBusy(false);}}
  };
  const reviewApproval=async()=>{
    const original=current.current,project=original?.ownerDecision?.state==='eligible_project_first';if(!original||(!project&&original.ownerDecision?.state!=='eligible')||!allLoaded||pending.current||!opened.current||unknown||registered)return;
    const seq=++generation.current;pending.current=true;setBusy(true);setError('');setAck(false);
    try{
      const next=await run(project?'teams.reviewFirstProjectText':'teams.reviewFrozenTextApproval',{viewHandle:view,fileHandle:original.fileHandle});
      if(seq!==generation.current){if(next?.approvalHandle)void bridge.command('teams.closeReading',{approvalHandle:next.approvalHandle}).catch(()=>{});return;}
      if(next?.teamRunId!==team||next.fileHandle!==original.fileHandle||next.planHash!==original.planHash||!next.approvalHandle||!next.projectId||next.startsIntegration!==false
        ||project!==!!next.firstProject||project&&(next.startsChecks!==false||next.packagesProject!==false||next.projectId!==original.projectId||!next.parentCheckoutId
          ||typeof next.sourceDigest!=='string'||typeof next.snapshotIdentity!=='string'||!(/^[a-f0-9]{64}$/.test(next.sourceDigest)&&/^[a-f0-9]{64}$/.test(next.snapshotIdentity))||!next.reviewerTaskId||!next.reviewerRunId)
        ||!Array.isArray(next.files)||next.files.length!==original.files.length||next.files.some((f:{path?:string;existing?:boolean;sha256?:string;version?:number},i:number)=>f.path!==original.files[i].path
          ||f.existing!==original.files[i].currentExists||project&&(f.version!==original.files[i].currentVersion||f.sha256!==original.files[i].sha256)))
        throw new Error('写回许可的文件或项目身份待核对，没有提交。');
      setApproval(next);
    }catch(e){if(seq===generation.current)setError(e instanceof Error?e.message:String(e));}
    finally{if(seq===generation.current){pending.current=false;setBusy(false);}}
  };
  const cancelApproval=()=>{if(approval)void bridge.command('teams.closeReading',{approvalHandle:approval.approvalHandle}).catch(()=>{});setApproval(null);setAck(false);setError('');};
  const confirmApproval=async()=>{
    const original=current.current,exact=approval;if(!original||!exact||!ack||pending.current||!opened.current||unknown||registered)return;
    const seq=++generation.current;pending.current=true;setBusy(true);setError('');setApproval(null);setAck(false);
    try{
      const receipt=await run(exact.firstProject?'teams.approveFirstProjectText':'teams.approveFrozenText',{viewHandle:view,fileHandle:original.fileHandle,approvalHandle:exact.approvalHandle});
      if(seq!==generation.current)return;
      if(receipt?.fileHandle!==original.fileHandle||receipt.teamRunId!==team||receipt.planHash!==original.planHash||receipt.state!=='approved_current'||!receipt.reviewId||receipt.startedByThisAction!==false||exact.firstProject&&(receipt.registrationKind!=='first_local_project_text_v1'||receipt.separateIntegratorStartRequired!==true))throw new Error('写回许可的结果未确认。');
      setRegistered(receipt.reviewId);
    }catch(e){if(seq===generation.current){setUnknown(true);setError((e instanceof Error?e.message:String(e))+' 请关闭并重新读取这份原文件目录核对登记，不重复提交。');}}
    finally{if(seq===generation.current){pending.current=false;setBusy(false);}}
  };

  const releaseIndependent=(handle?:string)=>{if(handle)void bridge.command('teams.closeReading',{reviewHandle:handle}).catch(()=>{});};
  const cancelIndependent=()=>{releaseIndependent(independent?.reviewHandle);setIndependent(null);setError('');};
  const readIndependent=async()=>{
    const original=current.current;
    if(!original||original.fullProject!==false||original.revisionComparison||original.ownerDecision?.canStartIndependentReview!==true||
        original.ownerDecision.state!=='independent_review_required'||pending.current||!opened.current||document.hidden||!active||independentState!=='ready')return;
    const seq=++generation.current;pending.current=true;setBusy(true);setError('');
    try{
      const next=await run('teams.reviewIndependentFiles',{viewHandle:view,fileHandle:original.fileHandle});
      if(seq!==generation.current){releaseIndependent(next?.reviewHandle);return;}
      if(next?.format!=='pocketworld.owner-first-local-team-review.v1'||next.teamRunId!==team||next.fileHandle!==original.fileHandle||
          next.planHash!==original.planHash||next.projectId!==original.projectId||next.startsReview!==false||next.startsWriteback!==false||
          typeof next.reviewHandle!=='string'||!next.reviewHandle||typeof next.model!=='string'||!next.model||typeof next.provider!=='string'||!next.provider||
          !Number.isSafeInteger(next.remainingModelRequests)||next.remainingModelRequests<1||!Number.isSafeInteger(next.remainingToolOperations)||
          next.remainingToolOperations<original.files.length+2||!Array.isArray(next.files)||next.files.length!==original.files.length||
          next.files.some((f:{path?:string;sha256?:string},i:number)=>!f||f.path!==original.files[i].path||f.sha256!==original.files[i].sha256)){
        releaseIndependent(next?.reviewHandle);throw new Error('原独审文件或模型待核对，未启动。');
      }
      setIndependent(next);
    }catch(e){if(seq===generation.current)setError(e instanceof Error?e.message:String(e));}
    finally{if(seq===generation.current){pending.current=false;setBusy(false);}}
  };
  const startIndependent=async()=>{
    const original=current.current,confirmed=independent;
    if(!original||!confirmed||pending.current||!opened.current||document.hidden||!active||independentState!=='ready')return;
    const seq=++generation.current;pending.current=true;setBusy(true);setError('');
    try{
      const receipt=await run('teams.startIndependentFiles',{viewHandle:view,fileHandle:original.fileHandle,reviewHandle:confirmed.reviewHandle});
      if(seq!==generation.current)return;
      if(receipt?.state!=='review_started'||receipt.teamRunId!==team||receipt.fileHandle!==original.fileHandle||receipt.planHash!==original.planHash||
          receipt.startsWriteback!==false||!receipt.taskId||!receipt.runId||!Number.isSafeInteger(receipt.epoch)||receipt.epoch<1)
        throw new Error('未收到可验证的原独审启动回执。');
      setIndependentState('started');
    }catch(e){if(seq===generation.current){setIndependentState('unknown');setError((e instanceof Error?e.message:String(e))+' 请刷新原协作核对，不重复启动。');}}
    finally{if(seq===generation.current){pending.current=false;setBusy(false);setIndependent(null);}}
  };

  const finding=source?blockedFinding(source.ownerDecision,team,source.planHash):null;
  const priorReview=originalReviewerFinding(approval);
  const files=source?.files??[];
  const orderedFiles=memberFirst?[...files.filter(file=>file.artifactId===memberFirst),...files.filter(file=>file.artifactId!==memberFirst)]:files;
  const coverage=source&&completeFiles&&completeFiles.fileHandle===source.fileHandle?completeFiles.ids:undefined;
  const currentIndex=orderedFiles.findIndex(file=>file.artifactId===selected);
  const nextFile=source?.ownerDecision?.state==='eligible'&&source.fullProject===false&&!source.revisionComparison&&
    !unknown&&!registered&&!allLoaded&&page?.id===selected&&page.end===page.size&&coverage&&coverage.has(selected)&&currentIndex>=0?
    [...orderedFiles.slice(currentIndex+1),...orderedFiles.slice(0,currentIndex)].find(file=>file.kind==='text'&&!file.truncated&&!coverage.has(file.artifactId)):undefined;
  return <><Button variant="secondary" size="small" disabled={disabled||!active||!view} onClick={()=>void open()}>{hasRevision?'核对上版写回与本次冻结':'查看冻结文件与原项目差异'}</Button>
    <SettingsDialog isOpen={shown} title={approval?approval.firstProject?"核对完整项目的首版文件许可":"核对这份写回许可":independent?"核对独立审查":source?.revisionComparison?"核对原分工修订":"原协作的冻结文件"} onClose={dismiss} className="pw-team-modal">
      <div className="pw-form-stack">{approval?<><p>{approval.firstProject?'你正在给下列原冻结文本登记首版许可。这份登记不可用于发起整合。':'这份许可只适用于下列原冻结文本。批准后，管家可在原授权内请求唯一整合者将它们写回原项目；本次不会启动整合或写入文件。'}</p>
        <p className="pw-field-hint">原项目：<code>{approval.projectId}</code> · 计划 {approval.planHash.slice(0,12)}</p>
        {priorReview&&<details><summary>查看这版文件的原独审意见</summary>
          <p className="pw-field-hint">独审没有报告阻断项。这是原审阅意见，不代表项目验收或写回许可。</p>
          <p dir="auto" style={{whiteSpace:'pre-wrap'}}>{priorReview.findings}</p>
        </details>}
        {approval.firstProject&&<><p>仅登记这版已独审的原文件。不会启动整合者、执行检查或打包，每项后续写回仍须原操作单独批准。</p>
          <details><summary>核对原项目固定基线与独审记录</summary>
            <p>原项目副本：<code>{approval.parentCheckoutId}</code></p><p>源码摘要：<code>{approval.sourceDigest}</code></p>
            <p>完整目录与字节身份：<code>{approval.snapshotIdentity}</code></p><p>独审任务：<code>{approval.reviewerTaskId}</code> · 本次执行 <code>{approval.reviewerRunId}</code></p>
            <p>{approval.reviewRouteCaptured===true?'本次独审有原生模型线路记录，不代表每次都用了同一家服务。':'这次独审没有可核对的历史模型线路，不用当前默认模型补填。'}</p>
          </details></>}
        <ul>{approval.files.map(file=><li key={file.path}>{file.existing?'修改':'新增'}：<code>{file.path}</code>{approval.firstProject&&<> · 基线 v{file.version}<details><summary>冻结文件摘要</summary><code>{file.sha256}</code></details></>}</li>)}</ul>
        <CheckboxField isSelected={ack} onChange={setAck} isDisabled={busy}>我已核对这些文件的完整差异和写回目标</CheckboxField>
        <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={cancelApproval}>返回文件，不批准</Button><Button disabled={busy||!ack} onClick={()=>void confirmApproval()}>仅批准这份写回许可</Button></div></>:
        independent?<><p>将本次协作的原目标、分工、选件范围和验收要求，以及下列冻结文本与它们在原项目中的差异，交给 <bdi>{independent.provider} · {independent.model}</bdi> 独立审查。只记录这版审查意见，不会写回项目。</p>
          <p className="pw-field-hint">使用原工作剩余额度：{independent.remainingModelRequests} 次模型请求、{independent.remainingToolOperations} 次工具操作。不增加额度。</p>
          <ul>{independent.files.map(file=><li key={file.path}><bdi>{file.path}</bdi> · <code>{file.sha256.slice(0,12)}</code></li>)}</ul>
          <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={cancelIndependent}>返回文件</Button>
            <Button disabled={busy||!active} onClick={()=>void startIndependent()}>{busy?'正在核对原件…':'用此模型开始独审'}</Button></div></>:
        (!source?.revisionComparison||projectView)&&<p className="pw-field-hint">对比打开时的项目版本和成员交回的文件。打开或读取文件不会批准、写回或启动工作。</p>}
        {error&&<p role="alert">{error}</p>}{busy&&!source&&<p role="status">正在读取原项目与冻结成果…</p>}
        {source?.revisionWarning&&<p role="status">{source.revisionWarning}</p>}
        {source?.revisionComparison&&!approval&&<div hidden={projectView}><TeamRevisionFileComparison key={source.fileHandle} source={source.revisionComparison} view={view} handle={source.fileHandle}
          run={run} memberName={memberName} openProject={id=>{const f=source.files.find(row=>row.artifactId===id);if(!f)return;setProjectView(true);if(f.kind==='text')void read(f,0);else{setSelected(f.artifactId);setPage(null);}}}/></div>}
        {source?.revisionComparison&&projectView&&<Button size="small" variant="ghost" onClick={returnToRevision}>返回所选文件的修订对照</Button>}
        {source&&!approval&&!independent&&(!source.revisionComparison||projectView)&&<><p>{source.files.length} 份冻结文件{source.incompleteCount? ` · ${source.incompleteCount} 项分工在原审阅中仍未交齐`:''}</p>
          {independentState==='started'?<p role="status">已登记对这版文件的独立审查，尚无结论，也没有写回。返回协作后可刷新查看原结果。</p>:
            independentState==='ready'&&source.ownerDecision?.canStartIndependentReview===true&&source.fullProject===false&&
              bridge.getState().capabilities.commands.includes('teams.reviewIndependentFiles')&&<Button variant="secondary" disabled={busy||!active} onClick={()=>void readIndependent()}>核对并发起独立审查</Button>}
          {source.integrationCapture&&<p role="status">打开时，{source.integrationCapture.matchingFiles}/{source.integrationCapture.total} 份与原写回回执及当前项目版本一致。
            {source.integrationCapture.unresolved?'原整合仍有未确定效果，不要重复写入。':source.integrationCapture.active?'原整合者仍在处理。':source.integrationCapture.total===0?'没有可逐份对照的文件，不判定写回。':source.integrationCapture.matchingFiles<source.integrationCapture.total?'未匹配的文件不一定从未写过，请逐份核对。':'文件版本相符不等于项目包交付或业务验收。'}</p>}
          {finding&&<section aria-label="这版文件的原独审意见"><h3>{finding.verdict==='changes_requested'?'独审要求修改':'独审未能判断'}</h3>
            <p dir="auto" style={{whiteSpace:'pre-wrap'}}>{finding.findings}</p>
            <p className="pw-field-hint">这是审阅者对下列冻结文件的原意见，不是你的批准，也没有启动修订。</p>
            {bridge.getState().capabilities.commands.includes('teams.openDeliveryWorker')&&<Button variant="secondary" size="small" disabled={busy} onClick={()=>void openOriginalReviewer()}>查看这次原审阅任务</Button>}
          </section>}
          {!source.files.length&&<p>没有读到这次协作的冻结文件。</p>}
          {orderedFiles.filter(file=>!source.revisionComparison||file.artifactId===selected).map(file=><section className="pw-team-expanded-copy" key={file.artifactId}
            ref={selected===file.artifactId?fileSection:undefined} tabIndex={selected===file.artifactId?-1:undefined}><h3>{file.path}</h3>
            <p className="pw-field-hint">贡献成员：{memberName(file.memberId) || file.memberId} · {file.retainedApplied?'与原整合回执及当前项目一致':file.matchesOwnerBase?'项目基线仍与原分工一致':'项目基线已改变'} · {file.currentExists?`项目版本 ${file.currentVersion}`:'打开时项目中无此文件'}</p>
            {file.kind!=='text'?<p>{file.kind==='deletion'?'这是删除记录。':'这是二进制或超出文本比较范围的文件。'}没有文本差异可展示。{typeof file.beforeBytes==='number'&&typeof file.afterBytes==='number'?` 原 ${file.beforeBytes} 字节，成果 ${file.afterBytes} 字节。`:''}</p>:
              <>{selected!==file.artifactId&&<Button variant="ghost" disabled={busy} onClick={()=>void read(file,0)}>查看这份差异</Button>}
                {selected===file.artifactId&&<>{busy&&!page&&<p role="status">正在读取差异…</p>}
                  {page?.id===file.artifactId&&<>{page.body?<pre className="pw-team-file-diff" tabIndex={0} aria-label={`${file.path} 的原始文本差异`}>{page.body}</pre>:<p>打开时两份文本没有行差异。</p>}
                    {page.end<page.size&&<Button variant="ghost" disabled={busy} onClick={()=>void read(file,page.end)}>继续读取差异（{page.end}/{page.size}）</Button>}
                    {nextFile&&<Button variant="secondary" disabled={busy||!active} onClick={()=>void read(nextFile,0,true)}>核对下一份：<bdi>{nextFile.path}</bdi></Button>}</>}
                  {file.truncated&&<p className="pw-field-hint">原生比较只保留了一段差异，不能当作完整文件审阅。</p>}
                  {!source.revisionComparison&&<Button variant="ghost" disabled={busy} onClick={()=>{setSelected('');setPage(null);setError('');}}>收起差异</Button>}</>}</>}
          </section>)}
          {registered||source.ownerDecision?.state==='approved_current'?<p role="status">{source.ownerDecision?.state==='eligible_project_first'||source.ownerDecision?.registrationKind==='first_local_project_text_v1'&&source.ownerDecision.separateIntegratorStartRequired===true
            ?(source.projectReviewOrigin?.registeredReviewId&&source.projectReviewOrigin.registeredAt?<>{new Date(source.projectReviewOrigin.registeredAt).toLocaleString('zh-CN')} 已登记这版文件许可。这笔登记没有启动整合、写文件或打包。</>:'这版原项目文本已登记。这笔登记没有启动整合、写文件、运行检查或打包。'):'这份计划的写回许可已登记。批准记录不是写入回执；实际整合请看原任务。'}</p>
            :(source.ownerDecision?.state==='eligible'||source.ownerDecision?.state==='eligible_project_first')&&!unknown?<><p className="pw-field-hint">{allLoaded?'所有文件的原始文本差异已加载，当前独审没有阻断项。':'完整加载每份原始差异后，才可单独核对写回许可。'}</p>
              <Button variant="secondary" disabled={!allLoaded||busy} onClick={()=>void reviewApproval()}>{source.ownerDecision?.state==='eligible_project_first'?'核对首版项目文件许可':'核对写回许可'}</Button></>
              :source.fullProject&&source.ownerDecision?.state==='read_only'&&!source.revisionComparison?<p className="pw-field-hint">这份完整项目保留待核对。只有首版、全本机作者、完整原文独审且基线未变的文本才可在此登记；没有为其他版本、二进制或删除项批准写回。</p>
              :source.ownerDecision?.state==='independent_review_required'&&independentState==='ready'?<p className="pw-field-hint">没有与这版文件匹配的完整无阻断独审，此处不能批准。</p>:null}</>}
      </div>
    </SettingsDialog></>;
}
