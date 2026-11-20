import {useEffect,useRef,useState} from 'react';
import {Button,SelectField,SettingsDialog} from './forms-library';
import {bridge} from './bridge';
import {hostWorkspaceAnchor} from './CloudWorkspaceChoice';
import type {CloudGoal} from './cloud-goal';

type Data=Record<string,any>;
type Profile={id:string;name?:string;bindingHash:string;serviceBase:string;hasToken:true};
type Target=NonNullable<CloudGoal['savedTarget']>;
const worker='codex_app_server';
const rows=(value:unknown):Data[]=>Array.isArray(value)?value.filter(item=>item&&typeof item==='object'&&!Array.isArray(item)):[];
const id=(value:unknown):value is string=>typeof value==='string'&&/^[A-Za-z0-9_-]{1,80}$/.test(value);
const sha=(value:unknown):value is string=>typeof value==='string'&&/^[a-f0-9]{64}$/.test(value);
export const savedHostProfiles=(value:unknown):Profile[]=>rows(value).filter((p):p is Profile=>
  id(p.id)&&sha(p.bindingHash)&&p.hasToken===true&&typeof p.serviceBase==='string'&&/^https:\/\//i.test(p.serviceBase));

export function SavedHostPicker({open,profiles,select,close,busy,error}:{
  open:boolean;profiles:Profile[];select:(p:Profile)=>void;close:()=>void;busy:boolean;error:string;
}){
  return <SettingsDialog isOpen={open} title="选择主机" closeLabel="返回原话" onClose={close}>
    {profiles.length?<><p>选择已有的 Companion 连接。这一步不联系主机，也不发送原话。</p>
      <div className="pw-connection-list">{profiles.map(p=><Button key={p.id+':'+p.bindingHash} variant="secondary" disabled={busy} onClick={()=>select(p)}>
        {p.name||'主机'} · <bdi>{p.serviceBase}</bdi>
      </Button>)}</div></>:<p role="status">原话仍只在手机。此交办需要已有的 HTTPS Companion 连接，仅在「我的设备」里配对或保存 SSH 凭据还不能接这类工作。</p>}
    {error&&<p role="alert">{error}</p>}
  </SettingsDialog>;
}

/** An isolated editor for this saved message, not the ordinary host draft. */
export function SavedHostWork({origin,target,profiles,run,supported,registerTransientBack}:{
  origin:string;target:Target;profiles:unknown;run:(command:string,args?:Data)=>Promise<any>;supported:(command:string)=>boolean;
  registerTransientBack:(handler:(()=>boolean)|null)=>void;
}){
  const profile=savedHostProfiles(profiles).find(p=>p.id===target.profileId&&p.bindingHash===target.profileBinding);
  const choice={profileId:target.profileId,bindingHash:target.profileBinding};
  const [catalog,setCatalog]=useState<Data[]|null>(null),[environment,setEnvironment]=useState(''),[review,setReview]=useState<Data|null>(null);
  const [submission,setSubmission]=useState<'waiting'|'observed'|'unknown'|null>(null),[error,setError]=useState(''),[busy,setBusy]=useState(false);
  const gate=useRef(0),live=useRef(true),pending=useRef(false),selectedProfile=useRef(profile);
  selectedProfile.current=profile;
  const nativeWindow=()=> {
    const view=bridge.getState().view as Data|undefined;
    return view?.section==='remote'&&!view.taskId&&!document.hidden&&
      savedHostProfiles(bridge.getState().state.features?.remote?.profiles).some(p=>p.id===target.profileId&&p.bindingHash===target.profileBinding);
  };
  const stateKey=()=> {
    const view=bridge.getState().view as Data|undefined;
    return JSON.stringify([bridge.getDocumentEpoch(),view?.section,view?.taskId,view?.resumeRevision,!!selectedProfile.current]);
  };
  useEffect(()=>{
    live.current=true;
    let scope=stateKey();
    const invalidate=()=>{
      gate.current++;pending.current=false;setBusy(false);setReview(null);setCatalog(null);setEnvironment('');
      setSubmission(old=>old==='waiting'?'unknown':old);
    };
    const change=()=>{const next=stateKey();if(next!==scope){scope=next;invalidate();}};
    const background=()=>{if(document.hidden)invalidate();};
    const unsubscribe=bridge.subscribe(change);
    document.addEventListener('visibilitychange',background);
    window.addEventListener('pagehide',invalidate);
    return ()=>{live.current=false;gate.current++;unsubscribe();document.removeEventListener('visibilitychange',background);window.removeEventListener('pagehide',invalidate);};
  },[]);
  const dismiss=()=>{
    const checked=review;
    setReview(null);
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
    setCatalog(null);setEnvironment('');
    const actual=await run('remote.environments',{profileId:choice.profileId,_profileBinding:choice.bindingHash});
    if(!current())return;
    if(actual?.profileBinding!==choice.bindingHash||actual?.serviceBase!==profile?.serviceBase)throw new Error('读取不属于所选主机，原话没有发送。');
    const all=rows(actual.environments);
    setCatalog(all.filter(e=>id(e.id)&&sha(e.bindingHash)&&hostWorkspaceAnchor(e)&&all.filter(other=>other.id===e.id).length===1&&
      rows(e.workers).filter(w=>w.id===worker).length===1&&rows(e.workers).some(w=>w.id===worker&&w.available!==false)));
  });
  const exact=catalog?.find(e=>e.id===environment);
  const prepare=()=>perform(async current=>{
    if(!exact||submission)return;
    const targetInput={...choice,environmentId:exact.id,environmentBinding:exact.bindingHash,worker};
    const actual=await run('remote.probeWorker',{profileId:choice.profileId,_profileBinding:choice.bindingHash,
      environmentId:exact.id,environmentBinding:exact.bindingHash,worker});
    if(!current())return;
    if(actual?.available!==true)throw new Error('这台主机没有核实到所选目录的只读执行器；原话未发送。');
    const captured=await run('remote.reviewTask',{...targetInput,readOnly:true,timeoutSeconds:300,hostGoalOrigin:origin});
    if(!current())return;
    if(captured?.savedOriginalHostReview!==true||captured.profileId!==choice.profileId||captured._profileBinding!==choice.bindingHash||
      captured.environmentId!==exact.id||captured.environmentBinding!==exact.bindingHash||captured.readOnly!==true||
      captured.worker!==worker||typeof captured.prompt!=='string'||typeof captured.reviewHandle!=='string')
      throw new Error('未取得原话与所选目录的同一次审阅，不发送。');
    setReview(captured);
  });
  const confirm=()=>perform(async current=>{
    if(!review||submission)return;
    const checked=review;setReview(null);setSubmission('waiting');
    try{
      const actual=await run('remote.confirmTask',{reviewHandle:checked.reviewHandle});
      if(current())setSubmission(actual?.hostTaskId===checked.operationId&&actual?.operationId===checked.operationId&&actual?.status!=='unknown'?'observed':'unknown');
    }catch(e){if(current())setSubmission('unknown');}
  });
  return <section className="pw-connection-workspace" aria-label="所选主机的原话交办">
    <header className="pw-connection-detail-heading"><div><h2>{profile?.name||'原选择的主机'}</h2>{profile&&<p className="pw-connection-address">{profile.serviceBase}</p>}</div></header>
    {!profile?<p role="status">这台主机的连接或凭据已变化。回原话重新选择；不会换到另一台主机。</p>:submission?
      <p role="status">{submission==='waiting'?'正在核对这次交办回执。':submission==='observed'?'请回原话查看同一主机工作和之后的结果。':'暂未核对到确切回执。回原话看本机已保存的记录，不要重复提交。'}</p>:<>
      {!catalog?<><p>先读取这台主机开放的只读工作目录，不会附上原话。</p>
        <Button disabled={busy||!supported('remote.environments')} onClick={read}>读取可用工作区</Button></>:
       catalog.length?<>
        <SelectField label="这次的工作区" value={environment} isDisabled={busy||!!review} options={catalog.map(e=>({id:e.id,label:e.workspace||e.id}))} onChange={setEnvironment}/>
        <p className="pw-field-hint">核对主机已配置的执行器，不发送原话或开始任务。</p>
        <Button disabled={busy||!exact||!!review||!supported('remote.probeWorker')||!supported('remote.reviewTask')||!supported('remote.confirmTask')} onClick={prepare}>核对主机并审阅</Button>
       </>:<p role="status">没有核对到此功能支持的工作区。原话还在手机，未派发。</p>}
    </>}
    {error&&<p role="alert">{error}</p>}
    <SettingsDialog isOpen={!!review&&!!profile} title="只把这条原话发给所选主机？" closeLabel="返回审阅" onClose={dismiss}>
      <p><bdi>{profile?.name||profile?.serviceBase}</bdi> · <bdi>{exact?.workspace||review?.environmentId}</bdi></p>
      <pre style={{whiteSpace:'pre-wrap',overflowWrap:'anywhere'}}>{review?.prompt}</pre>
      <p>使用主机已有的 Codex 只读执行器（5 分钟）。不附其他对话、当前草稿或附件。</p>
      <Button disabled={busy||!supported('remote.confirmTask')} onClick={confirm}>确认只发这一次</Button>
    </SettingsDialog>
  </section>;
}
