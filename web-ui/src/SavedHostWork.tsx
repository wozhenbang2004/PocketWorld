import {useEffect,useRef,useState} from 'react';
import {Button,SettingsDialog} from './forms-library';
import {bridge} from './bridge';
import {cloudChoice,hostWorkspaceAnchor,type SavedCloudWorkspace} from './CloudWorkspaceChoice';
import {IconChevronRight,IconFolder,IconHistory,IconServer} from './icons';
import {usePocketSnapshot} from './usePocketSnapshot';
import {originalSeenHostEnd} from './original-seen-host-end';
import {exactSavedHostMessage,type CloudGoal} from './cloud-goal';
import './connection-workspace.css';

type Data=Record<string,any>;
type Profile={id:string;name?:string;bindingHash:string;serviceBase:string;hasToken:true};
type Target=NonNullable<CloudGoal['savedTarget']>;
type Workspace={id:string;label:string;bindingHash:string;workspaceAnchor:string};
type Review={workspace:Workspace;operationId:string;reviewHandle:string;prompt:string};
type Submission={operationId:string;waiting:boolean};
type OriginalWork={id:string;label:string;detail:string;unfinished:boolean;updatedAt:number};
const worker='codex_app_server';
const rows=(value:unknown):Data[]=>Array.isArray(value)?value.filter(item=>item&&typeof item==='object'&&!Array.isArray(item)):[];
const id=(value:unknown):value is string=>typeof value==='string'&&/^[A-Za-z0-9_-]{1,80}$/.test(value);
const sha=(value:unknown):value is string=>typeof value==='string'&&/^[a-f0-9]{64}$/.test(value);
const operation=(value:unknown):value is string=>typeof value==='string'&&/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(value);
const label=(value:unknown,fallback:string)=>typeof value==='string'&&value.trim()?value:fallback;
const uniqueRows=(value:unknown)=>{
  const all=rows(value),seen=new Set<unknown>(),duplicates=new Set<unknown>();
  for(const row of all){if(seen.has(row.id))duplicates.add(row.id);seen.add(row.id);}
  return all.filter(row=>!duplicates.has(row.id));
};
export const savedHostProfiles=(value:unknown):Profile[]=>uniqueRows(value).filter((p):p is Profile=>
  id(p.id)&&sha(p.bindingHash)&&p.hasToken===true&&typeof p.serviceBase==='string'&&/^https:\/\//i.test(p.serviceBase));
const unambiguousHosts=(profiles:Profile[])=>{
  const key=(p:Profile)=>JSON.stringify([label(p.name,'主机').trim(),p.serviceBase]);
  return profiles.filter(p=>profiles.filter(other=>key(other)===key(p)).length===1);
};
export const currentSavedHostProfile=(value:unknown,target:Pick<Target,'profileId'|'profileBinding'>)=>unambiguousHosts(savedHostProfiles(value))
  .find(p=>p.id===target.profileId&&p.bindingHash===target.profileBinding);
function nativeWorkspaceChoice(current=bridge.getState()){
  const value=current.state.features?.remote?.cloudWorkspace;
  return value?.choiceUnreadable===true?null:cloudChoice(value);
}
// The guided first task may fill an untouched local slot, never replace or restore an old choice.
const unusedWorkspaceChoice=(value:ReturnType<typeof cloudChoice>)=>value?.revision===0&&!value.target;

function HostRow({title,detail,icon:Icon,onOpen,disabled}:{
  title:string;detail:string;icon:typeof IconServer;onOpen:()=>void;disabled?:boolean;
}){
  return <Button variant="ghost" className="pw-connection-row" disabled={disabled} onClick={onOpen}>
    <span className="pw-connection-row-icon"><Icon size={19} aria-hidden /></span>
    <span className="pw-connection-row-copy"><strong><bdi>{title}</bdi></strong><small>{detail}</small></span>
    <IconChevronRight size={16} className="pw-connection-chevron" aria-hidden />
  </Button>;
}

// Only the native projection of this exact private host work may name prior work.
// The connection catalog is not a task list and absence from this snapshot is not proof of absence.
function originalHostWork(value:unknown,profile:Profile):OriginalWork[]{
  const result:OriginalWork[]=[];
  for(const row of uniqueRows(value)){
    const raw=row.raw,receipt=row.hostReceipt,refs=raw?.externalRefs,host=refs?.hostWorkspace;
    if(!operation(row.id)||
      receipt?.statusOnRequest!==true||receipt.taskId!==row.id||receipt.runId!==row.id||receipt.epoch!==0||
      raw?.id!==row.id||raw.runId!==row.id||raw.operationId!==row.id||raw.coordinatorEpoch!==0||
      raw.actorId!=='owner'||raw.spaceId!=='private'||raw.kind!=='remote'||raw.feature!=='remote'||raw.command!=='submitJob'||
      raw.origin!=null||raw.parentTaskId!=null||raw.teamRunId!=null||raw.memberId!=null||
      raw.metadataLimited||raw.contextAvailable===false||raw.contextWithdrawn||raw.sourceCurrentReadRequired||raw.taskProjection==='source_unavailable'||
      host?.profileId!==profile.id||host.profileBinding!==profile.bindingHash||host.serviceBase!==profile.serviceBase||
      !id(host.environmentId)||!sha(host.workspaceAnchor)||host.worker!==worker||refs?.readOnly!==true)continue;
    const registered=receipt.stage==='phone_registered';
    if(!registered&&(receipt.stage!=='host_acknowledged'||!id(receipt.jobId)))continue;
    const end=originalSeenHostEnd(receipt);
    const status=end?(receipt.executorStatus==='completed'?end+' 这不代表成果已取回。':end):
      receipt.detailRetired===true?'原主机曾接单，详情已归档。':
      receipt.executorStatus==='running'?'上次回执：执行中。':
      receipt.executorStatus==='queued'?'上次回执：排队中。':'原主机曾接单，当前状态未核对。';
    const goal=label(row.goal,'原主机工作');
    result.push({id:row.id,label:goal.length>100?Array.from(goal).slice(0,100).join('')+'…':goal,
      detail:registered?'手机已登记，主机是否接单待核对。':status,
      unfinished:!end&&receipt.detailRetired!==true,updatedAt:Number.isFinite(row.updatedAt)?row.updatedAt:0});
  }
  return result.sort((a,b)=>Number(b.unfinished)-Number(a.unfinished)||b.updatedAt-a.updatedAt);
}

export function SavedHostPicker({open,profiles,select,configure,close,busy,error}:{
  open:boolean;profiles:Profile[];select:(p:Profile)=>void;configure?:()=>void;close:()=>void;busy:boolean;error:string;
}){
  const choices=unambiguousHosts(profiles);
  return <SettingsDialog isOpen={open} title="选择主机" closeLabel="返回原话" onClose={close}>
    <div className="pw-connection-workspace">
      {choices.length?<><p>从已配置的 Companion 连接选择。这一步不联系主机，也不发送原话。</p>
        <div className="pw-connection-list">{choices.map(p=><HostRow key={p.id} icon={IconServer} title={label(p.name,'主机')}
          detail={p.serviceBase} disabled={busy} onOpen={()=>select(p)}/>)}</div></>:
        !profiles.length&&<p role="status">原话仍只在手机。这项工作需要已运行的 Companion 及其 HTTPS 地址和令牌；设备配对和 SSH 不适用。</p>}
      {choices.length<profiles.length&&<p role="status">同名同地址的连接未列出。先给它们不同的名称，才能选对主机。</p>}
      {!choices.length&&configure&&<>
        <Button variant="secondary" leadingIcon={IconServer} disabled={busy} onClick={configure}>打开主机连接设置</Button>
        <p className="pw-field-hint">只保存手机上的连接配置。返回原话后再选择，不会自动连接或派发。</p>
      </>}
      {error&&<p role="alert">{error}</p>}
    </div>
  </SettingsDialog>;
}

/** An isolated editor for this saved message, not the ordinary host draft. */
export function SavedHostWork({origin,target,profiles,run,supported,registerTransientBack,onReturn}:{
  origin:string;target:Target;profiles:unknown;run:(command:string,args?:Data)=>Promise<any>;supported:(command:string)=>boolean;
  registerTransientBack:(handler:(()=>boolean)|null)=>void;onReturn?:()=>void;
}){
  const snapshot=usePocketSnapshot();
  const profile=currentSavedHostProfile(profiles,target);
  const choice={profileId:target.profileId,bindingHash:target.profileBinding};
  const [catalog,setCatalog]=useState<Workspace[]|null>(null),[review,setReview]=useState<Review|null>(null);
  const [submission,setSubmission]=useState<Submission|null>(null),[error,setError]=useState(''),[busy,setBusy]=useState(false);
  const [remembered,setRemembered]=useState<{operationId:string;saved:boolean}|null>(null);
  const gate=useRef(0),live=useRef(true),pending=useRef(false),selectedProfile=useRef(profile);
  const reviewOperation=useRef<string|null>(null),rememberAttempt=useRef<string|null>(null);
  const retired=useRef(false),[windowRetired,setWindowRetired]=useState(false);
  selectedProfile.current=profile;
  const showReview=(next:Review|null)=>{reviewOperation.current=next?.operationId??null;setReview(next);};
  const sameBookmark=(saved:SavedCloudWorkspace|undefined,workspace:Workspace)=>saved?.profileId===choice.profileId&&
    saved.profileBinding===choice.bindingHash&&saved.environmentId===workspace.id&&saved.workspaceAnchor===workspace.workspaceAnchor&&saved.worker===worker;
  const hasBookmarkRoute=supported('remote.rememberWorkspace')&&supported('remote.workspaceJobs')&&supported('remote.openWorkspaceJob');
  const nativeHost=(current=bridge.getState())=>currentSavedHostProfile(current.state.features?.remote?.profiles,target);
  const sourceCurrent=(current=bridge.getState())=>bridge.getDocumentEpoch()===target.source.document&&
    Number((current.view as Data|undefined)?.resumeRevision||0)===target.resumeRevision&&current.state.conversationId===target.source.conversation&&
    !current.state.conversationReader&&!!exactSavedHostMessage(rows(current.state.messages),target.source);
  const nativeWindow=()=> {
    const current=bridge.getState(),view=current.view as Data|undefined,here=nativeHost(current);
    return !retired.current&&view?.section==='remote'&&!view.taskId&&!document.hidden&&sourceCurrent(current)&&
      !!here&&here.serviceBase===selectedProfile.current?.serviceBase;
  };
  const stateKey=()=> {
    const view=bridge.getState().view as Data|undefined;
    return JSON.stringify([bridge.getDocumentEpoch(),view?.section,view?.taskId,view?.resumeRevision,nativeHost()?.serviceBase,sourceCurrent()]);
  };
  useEffect(()=>{
    live.current=true;
    let scope=stateKey();
    const invalidate=()=>{
      gate.current++;pending.current=false;setBusy(false);showReview(null);setCatalog(null);
      setSubmission(old=>old?.waiting?{...old,waiting:false}:old);
    };
    // The native host retires this saved origin when Android backgrounds it.
    // An empty catalog after resume cannot make the old origin usable again.
    const retire=()=>{if(retired.current)return;retired.current=true;setWindowRetired(true);invalidate();};
    const inHostWindow=()=>{const view=bridge.getState().view as Data|undefined;return view?.section==='remote'&&!view.taskId;};
    let entered=inHostWindow();
    const change=()=>{const inHost=inHostWindow();if(entered&&!inHost){retire();return;}entered||=inHost;
      const next=stateKey();if(next!==scope){scope=next;invalidate();}};
    const background=()=>{if(document.hidden)retire();};
    const unsubscribe=bridge.subscribe(change);
    document.addEventListener('visibilitychange',background);
    window.addEventListener('pagehide',retire);
    return ()=>{live.current=false;gate.current++;unsubscribe();document.removeEventListener('visibilitychange',background);window.removeEventListener('pagehide',retire);};
  },[]);
  const dismiss=()=>{
    const checked=review;
    showReview(null);
    if(checked&&nativeWindow()&&supported('remote.dismissSavedHostReview'))
      void run('remote.dismissSavedHostReview',{reviewHandle:checked.reviewHandle}).catch(()=>{});
  };
  useEffect(()=>{
    registerTransientBack(()=>{if(review&&!pending.current){dismiss();return true;}return false;});
    return ()=>registerTransientBack(null);
  },[review,registerTransientBack]);
  const perform=(work:(current:()=>boolean)=>Promise<void>)=>{
    if(!profile||!live.current||!nativeWindow()||pending.current)return;
    const sequence=++gate.current,scope=stateKey();
    const current=()=>live.current&&sequence===gate.current&&nativeWindow()&&scope===stateKey();
    pending.current=true;setBusy(true);setError('');
    void work(current).catch(e=>{if(current())setError(e instanceof Error?e.message:'未核对到原回执');})
      .finally(()=>{if(live.current&&sequence===gate.current){pending.current=false;setBusy(false);}});
  };
  const read=()=>perform(async current=>{
    setCatalog(null);
    const actual=await run('remote.environments',{profileId:choice.profileId,_profileBinding:choice.bindingHash});
    if(!current())return;
    if(actual?.profileBinding!==choice.bindingHash||actual?.serviceBase!==profile?.serviceBase||!Array.isArray(actual.environments))
      throw new Error('读取不属于所选主机，原话没有发送。');
    const workspaces:Workspace[]=[];
    for(const e of uniqueRows(actual.environments)){
      const workers=rows(e.workers).filter(w=>w.id===worker);
      const workspaceAnchor=hostWorkspaceAnchor(e);
      if(id(e.id)&&sha(e.bindingHash)&&workspaceAnchor&&workers.length===1&&workers[0].available!==false)
        workspaces.push({id:e.id,bindingHash:e.bindingHash,workspaceAnchor,label:label(e.workspace,e.id)});
    }
    setCatalog(workspaces);
  });
  const prepare=(exact:Workspace)=>perform(async current=>{
    if(!catalog?.includes(exact)||submission||review)return;
    const targetInput={...choice,environmentId:exact.id,environmentBinding:exact.bindingHash,worker};
    const actual=await run('remote.probeWorker',{profileId:choice.profileId,_profileBinding:choice.bindingHash,
      environmentId:exact.id,environmentBinding:exact.bindingHash,worker});
    if(!current())return;
    if(actual?.available!==true)throw new Error('这台主机没有核实到所选目录的只读执行器；原话未发送。');
    const captured=await run('remote.reviewTask',{...targetInput,readOnly:true,timeoutSeconds:300,hostGoalOrigin:origin});
    if(!current())return;
    if(captured?.savedOriginalHostReview!==true||captured.profileId!==choice.profileId||captured._profileBinding!==choice.bindingHash||
      captured.environmentId!==exact.id||captured.environmentBinding!==exact.bindingHash||captured.readOnly!==true||
      captured.worker!==worker||typeof captured.prompt!=='string'||typeof captured.reviewHandle!=='string'||!operation(captured.operationId))
      throw new Error('未取得原话与所选目录的同一次审阅，不发送。');
    showReview({workspace:exact,prompt:captured.prompt,reviewHandle:captured.reviewHandle,operationId:captured.operationId});
  });
  const remember=()=>perform(async current=>{
    const checked=review;
    if(!checked||submission||reviewOperation.current!==checked.operationId||rememberAttempt.current===checked.operationId||
       !catalog?.includes(checked.workspace)||!hasBookmarkRoute||!unusedWorkspaceChoice(nativeWorkspaceChoice()))return;
    rememberAttempt.current=checked.operationId;
    const place=checked.workspace;
    let saved=false;
    try{
      // Already observed by the same native catalog. The local compare-and-set does not contact the host or confirm the task.
      const actual=await run('remote.rememberWorkspace',{profileId:choice.profileId,_profileBinding:choice.bindingHash,expectedRevision:0,
        environmentId:place.id,environmentBinding:place.bindingHash,workspaceAnchor:place.workspaceAnchor,worker});
      const next=cloudChoice(actual);
      saved=next?.revision===1&&next.ownerDeclaredByos===false&&sameBookmark(next.target,place);
    }catch{/* An unknown reply never confirms the bookmark or triggers a retry. */}
    if(current()&&reviewOperation.current===checked.operationId)setRemembered({operationId:checked.operationId,saved});
  });
  const confirm=()=>perform(async current=>{
    if(!review||submission)return;
    const checked=review;showReview(null);setSubmission({operationId:checked.operationId,waiting:true});
    try{await run('remote.confirmTask',{reviewHandle:checked.reviewHandle});}
    catch{/* Missing acknowledgement does not mean it is safe to send again. */}
    finally{if(current())setSubmission({operationId:checked.operationId,waiting:false});}
  });
  const originals=profile?originalHostWork(snapshot.state.tasks,profile):[];
  const shown=submission?originals.find(row=>row.id===submission.operationId):originals[0];
  const held=windowRetired||!sourceCurrent(snapshot)||!profile;
  const canPrepare=nativeWindow();
  const canReview=supported('remote.probeWorker')&&supported('remote.reviewTask')&&supported('remote.confirmTask');
  const savedChoice=nativeWorkspaceChoice(snapshot),firstChoice=unusedWorkspaceChoice(savedChoice);
  const rememberReply=review&&remembered?.operationId===review.operationId?remembered:null;
  const showBookmark=hasBookmarkRoute&&!!review&&(sameBookmark(savedChoice?.target,review.workspace)||firstChoice&&rememberReply?.saved===true);
  const showRemember=hasBookmarkRoute&&!!review&&firstChoice&&!rememberReply&&!showBookmark;
  return <section className="pw-connection-workspace" aria-label="所选主机的原话交办">
    <header className="pw-connection-detail-heading"><div><h2>{label(profile?.name,'原选择的主机')}</h2>{profile&&<p className="pw-connection-address"><bdi>{profile.serviceBase}</bdi></p>}</div></header>
    {submission?<p role="status">{submission.waiting?'正在核对这次交办的回执。':shown?'这次交办已在手机登记。可返回原话打开原工作。':'暂未核对到这次交办的确切记录。回原话查看，不要重复提交。'}</p>:
      held?<><p role="status">{!profile?'选定的主机不能再核对。':'原准备窗口或原话已变化。'}不会再次联系主机，也不会改在手机上执行。</p>
        {onReturn&&<Button variant="secondary" onClick={onReturn}>返回原话核对</Button>}</>:
      !canPrepare?<p role="status">原准备窗口尚未核对。核对前不会读取主机。</p>:<>
      {!catalog?<><p>原话留在手机。点此只读取所选主机开放的工作区，这一步不发送原话。</p>
        <Button disabled={busy||!supported('remote.environments')} onClick={read}>读取可用工作区</Button></>:
        catalog.length?<div className="pw-connection-directory-group">
          <header><h3>选择工作区</h3></header>
          <p className="pw-field-hint">点选工作区可核对只读执行器并审阅，确认前不会发送原话。</p>
          <div className="pw-connection-list">{catalog.map(e=><HostRow key={e.id} icon={IconFolder} title={e.label}
            detail={e.label===e.id?'核对并审阅':e.id+' · 核对并审阅'} disabled={busy||!!review||!canReview} onOpen={()=>prepare(e)}/>)}</div>
        </div>:<p role="status">没有核对到适用的工作区。原话还在手机，未派发。</p>}
    </>}
    {shown&&profile&&!submission?.waiting&&(!held||submission)&&<section className="pw-connection-detail-section" aria-label={submission?'这次原工作':'本机最近的原工作'}>
      <h3>{submission?'这次原工作':'这台主机的最近工作'}</h3>
      <div className="pw-connection-summary"><span className="pw-connection-summary-icon"><IconHistory size={19} aria-hidden /></span>
        <div><p><strong><bdi>{shown.label}</bdi></strong></p><p>{shown.detail} 仅显示手机留存的回执。</p></div></div>
    </section>}
    {error&&!held&&<p role="alert">{error}</p>}
    <SettingsDialog isOpen={!!review&&!!profile&&canPrepare} title="只把这条原话发给所选主机？" closeLabel="返回工作区" onClose={dismiss}>
      <p><strong>{label(profile?.name,'所选主机')}</strong><br/><bdi>{profile?.serviceBase}</bdi><br/>
        工作区：<bdi>{review?.workspace.label}</bdi>{review&&review.workspace.label!==review.workspace.id&&<>（<bdi>{review.workspace.id}</bdi>）</>}</p>
      <pre style={{whiteSpace:'pre-wrap',overflowWrap:'anywhere'}}>{review?.prompt}</pre>
      <p>使用主机已有的 Codex 只读执行器（5 分钟）。不附其他对话、当前草稿或附件。</p>
      <div className="pw-form-actions">
        <Button disabled={busy||!supported('remote.confirmTask')} onClick={confirm}>确认只发这一次</Button>
        {showRemember&&<Button variant="secondary" disabled={busy||rememberAttempt.current===review?.operationId} onClick={remember}>记住这个主机工作区</Button>}
      </div>
      {showRemember&&<p className="pw-field-hint">只记在手机，方便下次从这台主机查看本机保存的原任务。不联系主机，也不发送原话。</p>}
      {showBookmark?<p role="status" className="pw-field-hint">手机已记住这个工作区，可从这台主机查看本机保存的原任务。不代表主机在线。</p>:
        rememberReply?.saved===false?<p role="status" className="pw-field-hint">手机尚未核实是否记住工作区。这一步没有发送原话。</p>:null}
    </SettingsDialog>
  </section>;
}
