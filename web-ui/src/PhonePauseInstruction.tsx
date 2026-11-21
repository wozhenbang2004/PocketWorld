import { useEffect, useRef, useState } from 'react';
import { Button, TextAreaField } from './forms-library';
import { IconEdit, IconRefresh } from './icons';
import { bridge } from './bridge';
import { phoneRunIdentity } from './phone-start';

type Row = Record<string, any>;
type Original = { taskId: string; runId: string; epoch: number };
type Pause = Original & { pauseRevision: number; scopeRevision: number };
type Review = Pause & { reviewHandle: string; noteRecorded: boolean };
type Draft = { target: Original; pause?: Pause; text: string };
export type MainPauseResume = {key:string;current:()=>boolean;submit:(trigger:HTMLButtonElement)=>Promise<unknown>|undefined;
  leavePage?:(trigger:HTMLButtonElement)=>Promise<unknown>|undefined};
type ResumeFeedback = {current:()=>boolean;phase:'pending'|'accepted'|'unknown'};
const same = (a?: Original | null, b?: Original | null) => !!a && !!b && a.taskId === b.taskId && a.runId === b.runId && a.epoch === b.epoch;
const samePause = (a: Pause, b: Pause) => same(a,b) && a.pauseRevision === b.pauseRevision && a.scopeRevision === b.scopeRevision;
export function isPrivateLocalPhone(task: Row) {
  const row=task.raw;
  return !!row && row.id===task.id && row.kind==='phone' && row.actorId==='owner' && row.spaceId==='private' &&
    row.origin==null && row.parentTaskId==null && row.ownerPhoneControl==null && row.ownerDeviceWork==null &&
    ['main_screen','background'].includes(task.executionMode || row.executionMode) && !!phoneRunIdentity(task);
}

/** Journal proof is independent of a missing live row. Never infer the OS cause or reuse a stale source. */
export function interruptedPrivatePhone(task:Row, liveRows:unknown):boolean {
  const id=phoneRunIdentity(task), raw=task.raw, record=raw?.originalInterruptedPhoneAttempt;
  if(!id||!isPrivateLocalPhone(task)||task.status!=='paused'||raw.status!=='paused'||raw.cancelRequested===true||
     raw.taskProjection!=='detail_window'||raw.taskCoreReadLimited!==false||raw.contextAvailable===false||
     raw.mainPhoneOwnerStep!=null||raw.ownerPhoneQuestionHold!=null||!Array.isArray(liveRows)||liveRows.some(row=>row?.taskId===id.taskId))return false;
  return record?.format==='pocketworld.private-phone-interrupted.v1'&&record.runStatus==='interrupted'&&
    same(id,record)&&record.mode===raw.executionMode&&record.mode===task.executionMode;
}

export function InterruptedPhoneRecord({reviewed,canStart,onHistory,onReview}:{reviewed:boolean;canStart:boolean;onHistory?:()=>void;onReview?:()=>void}) {
  return <section className="pw-work-supplement" aria-label="原手机执行中断">
    <h4>上一轮已中断，原任务还在</h4>
    <p>可以核对之前做过什么。旧屏幕现场不能接回；这份记录没有说明中断原因。</p>
    {reviewed&&<p className="pw-field-hint">{canStart?'若要继续，可以在原任务另开一轮。仍须满足原执行条件，不会接回旧屏幕。':'当前没有可另开一轮的操作。'}</p>}
    <div className="pw-action-row">
      {onHistory&&<Button variant="secondary" onClick={onHistory}>查看这件工作的过程</Button>}
      {!reviewed&&onReview&&<Button variant="ghost" onClick={onReview}>核对能否另开一轮</Button>}
    </div>
  </section>;
}

/** Only the mounted private task holds its unsent draft. Text does not enter the original run until the explicit final click. */
export function PhonePauseInstruction({ task, visible, livePause, interrupted, plainMainResume, run }: {task:Row;visible:boolean;livePause:boolean;interrupted:boolean;plainMainResume?:MainPauseResume;run:(action:string,args:Row)=>Promise<any>}) {
  const id=phoneRunIdentity(task), [draft,setDraft]=useState<Draft|null>(null), [review,setReview]=useState<Review|null>(null);
  const [busy,setBusy]=useState(false), [error,setError]=useState(''), [notice,setNotice]=useState(''), [expanded,setExpanded]=useState(false);
  const [resumeFeedback,setResumeFeedback]=useState<ResumeFeedback>();
  const attemptedPlainPause=useRef('');
  const paused=task.status==='paused'&&livePause&&!interrupted;
  const life=useRef(true), flight=useRef(false), ticket=useRef(0), info=useRef({id,visible,paused,interrupted}); info.current={id,visible,paused,interrupted};
  const input=useRef<Draft|null>(draft);input.current=draft;
  useEffect(()=>{life.current=true;return()=>{life.current=false;ticket.current++}},[]);
  useEffect(()=>{
    const lose=()=>{
      ticket.current++;flight.current=false;setBusy(false);setReview(null);
      setResumeFeedback(old=>old?.phase==='pending'?{...old,phase:'unknown'}:old);
    };
    if(!visible)lose();
    const hide=()=>{if(document.hidden)lose()};
    document.addEventListener('visibilitychange',hide);return()=>document.removeEventListener('visibilitychange',hide);
  },[visible]);
  const origin=(target:Original)=>life.current && info.current.visible && !info.current.interrupted && !document.hidden && same(info.current.id,target);
  const draftElsewhere=!!draft && !same(draft.target,id);
  const ready=!!review && !!draft && !!draft.pause && samePause(review,draft.pause) && !review.noteRecorded && same(review,id) && paused;
  const read=async()=>{
    if(!id || flight.current || !origin(id) || !paused || draftElsewhere)return;
    const original=id, serial=++ticket.current, epoch=bridge.getDocumentEpoch();
    flight.current=true;setBusy(true);setExpanded(true);setReview(null);setError('');setNotice('');
    if(!input.current){const next={target:original,text:''};input.current=next;setDraft(next)}
    const accepts=()=>serial===ticket.current && origin(original) && info.current.paused && bridge.getDocumentEpoch()===epoch;
    try {
      const r=await run('phone.pauseNote.read',original);
      if(!accepts())return;
      if(!same(r,original)||!r.reviewHandle||!Number.isSafeInteger(r.pauseRevision)||r.pauseRevision<1||!Number.isSafeInteger(r.scopeRevision)||r.scopeRevision<1||typeof r.noteRecorded!=='boolean')throw Error('未取得这次暂停和原授权范围的完整记录。');
      const old=input.current;
      if(!old||!same(old.target,original))return;
      if(old.pause&&!samePause(old.pause,r)&&old.text.trim())throw Error('暂停或授权范围已变化。原稿还在这里，没有转交给下一次执行。');
      const next={...old,pause:{...original,pauseRevision:r.pauseRevision,scopeRevision:r.scopeRevision}};
      input.current=next;setDraft(next);setReview(r);
      if(r.noteRecorded)setNotice('这次暂停已记录过补充，不会再次添加。可在原任务核对状态，或使用普通继续。');
    }catch(failure){if(accepts())setError(failure instanceof Error?failure.message:'未取得原暂停，草稿仍在。')}
    finally{if(life.current&&serial===ticket.current){flight.current=false;setBusy(false)}}
  };
  const send=async()=>{
    const original=review, pending=input.current, note=pending?.text.trim()||'';
    if(!original||!pending||!pending.pause||!samePause(original,pending.pause)||!origin(original)||!info.current.paused||!ready||flight.current||!note||note.length>8000)return;
    const serial=++ticket.current, epoch=bridge.getDocumentEpoch();flight.current=true;setBusy(true);setReview(null);setError('');setNotice('');
    const accepts=()=>serial===ticket.current && origin(original) && bridge.getDocumentEpoch()===epoch;
    try {
      const r=await run('phone.pauseNote.continue',{...pending.target,reviewHandle:original.reviewHandle,confirmed:true,note});
      if(!accepts())return;
      if(!samePause(original,r)||r.noteRecorded!==true||r.continuedOriginalRun!==true||r.budgetIncreased!==false||r.modelConsumptionConfirmed!==false)throw Error('没有取得这次补充和原执行的完整回执。');
      input.current=null;setDraft(null);setExpanded(false);
      setNotice('补充已记入，原执行已恢复。模型是否已读到，要等下一轮；这不是任务完成回执。');
    }catch(failure){if(accepts())setError((failure instanceof Error?failure.message:'补充或恢复结果待核对')+'。原稿保留；请先核对这次暂停，不要重复提交。')}
    finally{if(life.current&&serial===ticket.current){flight.current=false;setBusy(false)}}
  };
  const continueWithoutNote=async(trigger:HTMLButtonElement)=>{
    const offered=plainMainResume;
    if(!offered||!id||!origin(id)||!info.current.paused||expanded||flight.current||
       input.current?.text||attemptedPlainPause.current===offered.key)return;
    const receiving=offered.submit(trigger);
    if(!receiving){if(offered.current())setError('原暂停已变化，这次没有提交继续。');return}
    const serial=++ticket.current;
    attemptedPlainPause.current=offered.key;flight.current=true;setBusy(true);setError('');setNotice('');
    setResumeFeedback({current:offered.current,phase:'pending'});
    const accepts=()=>serial===ticket.current&&origin(id)&&offered.current();
    try{
      await receiving;
      if(accepts())setResumeFeedback({current:offered.current,phase:'accepted'});
    }catch{
      if(accepts())setResumeFeedback({current:offered.current,phase:'unknown'});
    }finally{if(life.current&&serial===ticket.current){flight.current=false;setBusy(false)}}
  };
  const leaveBeforeContinuing=async(trigger:HTMLButtonElement)=>{
    const shown=plainMainResume;
    if(!shown?.leavePage||!id||!origin(id)||!info.current.paused||expanded||flight.current||input.current?.text||
       attemptedPlainPause.current===shown.key)return;
    const pending=shown.leavePage(trigger);
    if(!pending){if(shown.current())setError('原暂停或任务控制条已变化，没有切换或继续。');return}
    const serial=++ticket.current,scope=bridge.getDocumentEpoch();
    flight.current=true;setBusy(true);setError('');setNotice('');
    const accepts=()=>serial===ticket.current&&origin(id)&&shown.current()&&bridge.getDocumentEpoch()===scope;
    try {
      const receipt=await pending as Row;
      if(!accepts())return;
      if(!receipt||receipt.taskId!==id.taskId||receipt.runId!==id.runId||receipt.epoch!==id.epoch||
         receipt.leftPocketWorld!==true||receipt.resumeRequested!==false)
        throw Error('未确认页面切换；没有从这里继续原任务。');
    }catch(failure){if(accepts())setError(failure instanceof Error?failure.message:'未确认页面切换；没有从这里继续。')}
    finally{if(life.current&&serial===ticket.current){flight.current=false;setBusy(false)}}
  };
  const discard=()=>{if(busy)return;input.current=null;setDraft(null);setReview(null);setError('');setNotice('');setExpanded(false)};
  const plainFeedback=visible&&resumeFeedback?.current()?resumeFeedback.phase:undefined;
  const offerPlain=paused&&visible&&plainMainResume&&!draft?.text&&attemptedPlainPause.current!==plainMainResume.key;
  if(!id||!isPrivateLocalPhone(task)||(!paused&&!draft&&(!notice||interrupted)&&!plainFeedback))return null;
  return <section className="pw-work-supplement" aria-label={plainMainResume||plainFeedback?'本次手机任务的暂停操作':'本次本机任务补充'}>
    {!expanded?<>
      {offerPlain&&<Button variant="primary" disabled={busy} aria-description="仍使用手机主屏，不会替你打开应用或另开一轮。" onClick={event=>void continueWithoutNote(event.currentTarget)}>继续这次暂停</Button>}
      {offerPlain&&plainMainResume?.leavePage&&<>
        <Button variant="secondary" disabled={busy} onClick={event=>void leaveBeforeContinuing(event.currentTarget)}>先切回手机页面</Button>
        <p className="pw-field-hint">先离开 PocketWorld，不继续执行。到你要用的页面后，再点任务控制条的「继续这次暂停」。</p>
      </>}
      {paused&&<Button variant="secondary" leadingIcon={IconEdit} disabled={!visible||busy} onClick={()=>{setExpanded(true);if(!draftElsewhere)void read()}}>{draft?.text?'打开本次补充草稿':'补充后继续…'}</Button>}
      {!paused&&draft&&<Button variant="ghost" onClick={()=>setExpanded(true)}>查看未确认的补充草稿</Button>}
    </>:<>
      <h4>{paused?'补充后继续':'未确认的补充草稿'}</h4>
      <p className="pw-field-hint">{paused?'只给这次已暂停的本机任务及其模型。本来的可用应用、权限、额度和截止时间不变。写字不会恢复任务。':'原稿仍在本页。没有可提交的原暂停，也没有将它带入其他轮次。'}</p>
      <TextAreaField label="给本次执行补充什么？" value={draft?.text||''} onChange={value=>{const old=input.current;if(!old||busy)return;const next={...old,text:value};input.current=next;setDraft(next)}} maxLength={8000} rows={3} showCount isDisabled={busy||draftElsewhere||!paused||!draft?.pause}/>
      {draftElsewhere&&paused&&<p role="status">原执行已变化，草稿没有转交给新执行。</p>}
      <div className="pw-action-row">
        {ready&&<Button variant="primary" disabled={busy||!visible||!draft?.text.trim()} onClick={()=>void send()}>提交补充并继续原执行</Button>}
        {!ready&&paused&&!draftElsewhere&&<Button variant="secondary" leadingIcon={IconRefresh} disabled={busy||!visible} onClick={()=>void read()}>核对这次暂停</Button>}
        <Button variant="ghost" disabled={busy} onClick={()=>setExpanded(false)}>收起</Button>
        {draft&&<Button variant="ghost" disabled={busy} onClick={discard}>舍弃本页草稿</Button>}
      </div>
    </>}
    {plainFeedback==='accepted'&&<p role="status">已接纳本轮继续。新画面或结果需看后续原回执。</p>}
    {plainFeedback==='unknown'&&<p role="status">未核对到本轮继续的回执。请在原任务核对，不要重复继续。</p>}
    {busy&&<p role="status">{plainFeedback==='pending'?'正在等待这次继续的回执…':'正在等原任务回执…'}</p>}{error&&<p className="pw-error" role="alert">{error}</p>}{notice&&!interrupted&&<p role="status">{notice}</p>}
  </section>;
}
