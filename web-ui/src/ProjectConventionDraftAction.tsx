import {useEffect,useRef,useState} from 'react';
import {bridge} from './bridge';
import {Button,SettingsDialog} from './forms-library';

type Data=Record<string,any>;
type Props={row:Data;readHandle:string;expires:number;project:string;disabled:boolean;run:(cmd:string,args:Data)=>Promise<any>;back:{current:(()=>boolean)|null}};
export const manualConvention=(row:Data|undefined,scope:string)=>!!row&&row.ownerId==='owner'&&row.spaceId===scope&&row.active===true&&
 row.source==='主人在本项目手写的约定'&&row.formation==='explicit_save'&&row.layer==='procedural'&&!row.archiveOnly&&!row.learningCandidateId&&!row.assertion&&
 Array.isArray(row.sourceRefs)&&!row.sourceRefs.length&&Array.isArray(row.sourceIds)&&!row.sourceIds.length&&typeof row.text==='string'&&row.text.length>0&&row.text.length<=4000;

/** The owner is already reading this one formal note. Neither entering nor leaving reads a project directory. */
export function ProjectConventionDraftAction({row,readHandle,expires,project,disabled,run,back}:Props){
 const [open,setOpen]=useState(false),[review,setReview]=useState<Data|null>(null),[pending,setPending]=useState(false),[error,setError]=useState(''),[outcome,setOutcome]=useState('');
 const live=useRef(false),seq=useRef(0),locked=useRef(false),pin=useRef(''),op=useRef(run);op.current=run;
 const release=(h:string)=>{if(h)void op.current('knowledge.close',{handle:h}).catch(()=>{});};
 const leave=()=>{seq.current++;locked.current=false;release(pin.current);pin.current='';setOpen(false);setReview(null);setPending(false);setError('');};
 useEffect(()=>{live.current=true;const doc=bridge.getDocumentEpoch();
   const changed=()=>{if(document.hidden||doc!==bridge.getDocumentEpoch()){leave();setOutcome('');}};
   const off=bridge.subscribe(changed);document.addEventListener('visibilitychange',changed);const gone=()=>{leave();setOutcome('');};window.addEventListener('pagehide',gone);
   return()=>{live.current=false;seq.current++;release(pin.current);pin.current='';off();document.removeEventListener('visibilitychange',changed);window.removeEventListener('pagehide',gone);};
 },[readHandle,expires]);
 useEffect(()=>{if(!open)return;const end=Math.min(expires,review?.expiresAt??expires);
   const timer=window.setTimeout(()=>{leave();setOutcome('原记忆审阅已过期，请重新打开这条记录。');},Math.max(0,end-Date.now()));
   back.current=()=>{if(!locked.current)leave();return true;};return()=>{clearTimeout(timer);back.current=null;};
 },[open,expires,review,back]);
 const checked=(v:Data)=>v?.projectId===project&&typeof v.checkoutId==='string'&&v.checkoutId.length>0&&v.revision===row.revision&&v.modelCalled===false&&v.taskStarted===false;
 const take=async(confirm:boolean)=>{
   if(!live.current||locked.current||document.hidden||Date.now()>=expires||confirm&&(!review||review.expiresAt<=Date.now()||!pin.current))return;
   const n=++seq.current;locked.current=true;setPending(true);setError('');setOutcome('');setOpen(true);
   const h=pin.current;if(confirm)pin.current='';
   try{const v=await run(confirm?'knowledge.project.draft.confirm':'knowledge.project.draft.review',confirm?{review:h}:{readHandle});
     if(!live.current||seq.current!==n){if(!confirm)release(v?.review);return;}
     if(!checked(v)||confirm&&(v.checkoutId!==review?.checkoutId||v.written!==true)||!confirm&&(v.written!==false||typeof v.review!=='string'||!v.review||!Number.isSafeInteger(v.expiresAt)||v.expiresAt<=Date.now())){if(!confirm)release(v?.review);throw new Error('回执与这条原项目约定不一致');}
     if(confirm){leave();setOutcome('已放入这个项目的「新项目工作 → 默认模型 → 交付要求」。还没有提交，开始前可修改或移除。');}
     else{pin.current=v.review;setReview(v);}
   }catch(e){if(live.current&&seq.current===n){if(confirm){leave();setOutcome('放入结果未确认，请回原项目核对草稿，不要重复放入。');}
      else setError(e instanceof Error?e.message:String(e));}}
   finally{if(live.current&&seq.current===n){locked.current=false;setPending(false);}}
 };
 return <><Button variant="secondary" size="small" disabled={disabled||pending||Date.now()>=expires} onClick={()=>void take(false)}>用在一次项目委托</Button>
   {outcome&&<p role="status" className="pw-muted">{outcome}</p>}
   <SettingsDialog className="pw-memory-modal" isOpen={open} title="只用在一次项目委托" closeLabel="返回这条约定" onClose={()=>{if(!locked.current)leave();}}>
    {review&&<><p>{review.name||'原项目'} · 原私人副本 {review.checkoutId.slice(0,8)} · 约定修订 {review.revision}</p>
      <p className="pw-reading-copy" dir="auto">{row.text}</p><p className="pw-muted">只将上面这份文字复制到原项目的未发送草稿。不读取其他记忆；原记忆的修改或遗忘不会改写已复制的文字。回项目核对并开始后，它才会成为这次交办的限制。</p>
      <Button disabled={pending} onClick={()=>void take(true)}>放入原项目草稿</Button></>}
    {pending&&<p role="status">正在核对原记录与草稿…</p>}{error&&<p role="alert" className="pw-error">{error}</p>}
    <Button variant="ghost" disabled={pending} onClick={leave}>返回这条约定</Button>
   </SettingsDialog></>;
}
