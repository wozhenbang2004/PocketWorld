import { useCallback, useId, useLayoutEffect, useRef, useState, useSyncExternalStore } from 'react';
import { bridge } from './bridge';
import { Button } from './forms-library';
import { IconCollapse, IconExpand } from './icons';
import { isPrivateLocalPhone } from './PhonePauseInstruction';
import { VerifiedRetainedPhoneImage } from './RetainedPhoneImage';
import { currentImageWindow, subscribeImageWindow, type ImageProof } from './retained-phone-image';
import { registerOverlayBack } from './overlay-back';

type Row=Record<string,any>;
type Held={key:string; proof:ImageProof; input:Row; bytes:number; mime:string};
function original(task:Row,identity:string):Held|null {
  if(!identity||!isPrivateLocalPhone(task))return null;
  const state=bridge.getState(), view=state.view as Row|undefined, m=view?.heldPrivatePhoneImage;
  const raw=task.raw, ev=raw.evidence;
  if(!m||!state.capabilities.commands.includes('phone.resultImage.held')||m.taskId!==task.id||m.runId!==raw.runId||m.epoch!==raw.coordinatorEpoch||
     !Number.isSafeInteger(m.epoch)||m.epoch<1||!Number.isSafeInteger(m.resultRevision)||m.resultRevision<1||m.resultRevision!==raw.resultRevision||
     typeof m.resultImageDigest!=='string'||m.resultImageDigest!==raw.resultImageDigest||!/^[a-f0-9]{64}$/.test(m.resultImageDigest)||
     typeof m.imageSha256!=='string'||m.imageSha256!==ev?.imageSha256||!/^[a-f0-9]{64}$/.test(m.imageSha256)||typeof ev.imageFile!=='string'||!ev.imageFile||
     typeof m.imageId!=='string'||!/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(m.imageId)||!Number.isSafeInteger(m.expiresAt)||m.expiresAt<=Date.now()||
     !Number.isSafeInteger(m.bytes)||m.bytes<1||m.bytes>4_000_000||!['image/jpeg','image/png','image/webp'].includes(m.mimeType))return null;
  const proof:ImageProof={taskId:m.taskId,run:m.runId,epoch:m.epoch,id:m.imageId,revision:m.resultRevision,digest:m.resultImageDigest,
    file:ev.imageFile,hash:m.imageSha256,expires:m.expiresAt};
  const stamp=currentImageWindow(proof); if(!stamp)return null;
  const input={taskId:m.taskId,runId:m.runId,epoch:m.epoch,resultRevision:m.resultRevision,resultImageDigest:m.resultImageDigest,imageId:m.imageId,imageSha256:m.imageSha256};
  return {key:JSON.stringify([identity,stamp,proof,m.bytes,m.mimeType]),proof,input,bytes:m.bytes,mime:m.mimeType};
}
function exactImage(result:Row|undefined,target:Held):string|null {
  if(!result||result.readOnly!==true||result.liveScreen!==false||result.executionAuthorized!==false||result.bytes!==target.bytes||
    result.mimeType!==target.mime||result.expiresAt!==target.proof.expires||Object.keys(target.input).some(k=>result[k]!==target.input[k]))return null;
  const url=result.imageDataUrl, prefix='data:'+target.mime+';base64,';
  return typeof url==='string'&&url.length===prefix.length+4*Math.ceil(target.bytes/3)&&url.startsWith(prefix)?url:null;
}

/** This row is an explicit visual read of what the original task already holds, not a review action or a request for new pixels. */
export function HeldPhoneResultImage({task,readIdentity}:{task:Row;readIdentity:string}) {
  const latest=useRef({task,readIdentity}); latest.current={task,readIdentity};
  const read=useCallback(()=>original(latest.current.task,latest.current.readIdentity),[]);
  const readKey=useCallback(()=>read()?.key||'',[read]);
  const scope=useSyncExternalStore(subscribeImageWindow,readKey), target=scope?read():null;
  const [data,setData]=useState<{key:string;url:string}|null>(null),[busy,setBusy]=useState(''),[issue,setIssue]=useState<{key:string;text:string}|null>(null);
  const [,tick]=useState(0),held=useRef(''),sequence=useRef(0),trigger=useRef<HTMLButtonElement>(null);
  const returnAt=useRef<null|{key:string;rows:Array<{el:Element;y:number;x:number}>}>(null),panel=useId(), detail=useRef<HTMLDivElement>(null);
  const close=useCallback((back=false)=>{
    const mark=returnAt.current;
    held.current='';sequence.current++;setData(null);setBusy('');setIssue(null);
    if(!back||!mark||readKey()!==mark.key)returnAt.current=null;
  },[readKey]);
  const shown=!!target&&data?.key===target.key, pending=!!scope&&busy===scope;
  useLayoutEffect(()=>{
    const reconcile=()=>{ if(held.current && readKey()!==held.current) close(); };
    const off=subscribeImageWindow(reconcile);reconcile();
    let timer=0;
    const deadline=(expires:number)=>{ if(Date.now()>=expires){reconcile();tick(n=>n+1);return;}
      timer=window.setTimeout(()=>deadline(expires),Math.min(2_147_483_647,expires-Date.now()+1)); };
    if(target) deadline(target.proof.expires);
    return ()=>{off();clearTimeout(timer);};
  },[scope,target?.proof.expires,readKey,close]);
  useLayoutEffect(()=>{
    if(!shown) {
      if(!held.current && returnAt.current){
        const mark=returnAt.current;returnAt.current=null;
        if(readKey()===mark.key&&trigger.current?.isConnected){for(const r of mark.rows)if(r.el.isConnected){r.el.scrollTop=r.y;r.el.scrollLeft=r.x;}trigger.current.focus({preventScroll:true});}
      }
      return;
    }
    detail.current?.scrollIntoView({block:'nearest',inline:'nearest',behavior:'instant'}); detail.current?.focus({preventScroll:true});
    return registerOverlayBack(()=>close(true));
  },[shown,readKey,close]);
  useLayoutEffect(()=>()=>{held.current='';sequence.current++;returnAt.current=null;},[]);
  async function show(){
    const t=read(),button=trigger.current;
    if(!t||!button||held.current||t.key!==scope)return;
    const rows=[];for(let el:Element|null=button.parentElement;el;el=el.parentElement)if(el===document.scrollingElement||/auto|scroll/.test(getComputedStyle(el).overflowY+' '+getComputedStyle(el).overflowX))rows.push({el,y:el.scrollTop,x:el.scrollLeft});
    const req=++sequence.current;held.current=t.key;returnAt.current={key:t.key,rows};setBusy(t.key);setIssue(null);
    const current=()=>req===sequence.current&&held.current===t.key&&readKey()===t.key;
    try{
      const result=await bridge.command('phone.resultImage.held',t.input);
      if(!current())return;
      const url=exactImage(result,t);if(!url)throw new Error('未取得这轮已读取的原截图，没有展示替代图。');
      setData({key:t.key,url});
    }catch(error){if(current()){held.current='';returnAt.current=null;setIssue({key:t.key,text:error instanceof Error?error.message:'这份原图暂未核实。'});}}
    finally{if(req===sequence.current)setBusy('');}
  }
  if(!target||target.key!==scope)return null;
  return <div className="pw-outcome-document" onKeyDown={e=>{if(shown&&e.key==='Escape'&&!e.defaultPrevented){e.preventDefault();e.stopPropagation();close(true);}}}>
    <Button ref={trigger} type="button" variant="secondary" size="small" leadingIcon={shown?IconCollapse:IconExpand} aria-busy={pending||undefined} aria-controls={panel} aria-expanded={shown}
      disabled={pending} onClick={shown?()=>close(true):()=>void show()}>{shown?'收起原截图':pending?'正在核对原截图':'查看已保留截图'}</Button>
    {issue?.key===scope&&<p role="status" className="pw-reading-note">{issue.text}</p>}
    <div ref={detail} tabIndex={shown?-1:undefined} id={panel} role="region" aria-label="本轮已保留截图" hidden={!shown}>
      {shown&&data&&<VerifiedRetainedPhoneImage key={scope} proof={target.proof} src={data.url} label="本轮结果截图"/>}
    </div>
  </div>;
}
