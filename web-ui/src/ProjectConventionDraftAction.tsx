import {useEffect,useRef,useState} from 'react';
import {bridge} from './bridge';
import {Button,SettingsDialog} from './forms-library';

type Data=Record<string,any>;
export type ProjectDraftReturn={projectId:string;checkoutId:string;handle:string;expiresAt:number};
export type OpenProjectDraft=(target:ProjectDraftReturn)=>void;
type Props={onOpenProjectDraft?:OpenProjectDraft;row:Data;readHandle:string;expires:number;project:string;disabled:boolean;run:(cmd:string,args:Data)=>Promise<any>;back:{current:(()=>boolean)|null}};
export const manualConvention=(row:Data|undefined,scope:string)=>!!row&&row.ownerId==='owner'&&row.spaceId===scope&&row.active===true&&
 row.source==='主人在本项目手写的约定'&&row.formation==='explicit_save'&&row.layer==='procedural'&&!row.archiveOnly&&!row.learningCandidateId&&!row.assertion&&
 Array.isArray(row.sourceRefs)&&!row.sourceRefs.length&&Array.isArray(row.sourceIds)&&!row.sourceIds.length&&typeof row.text==='string'&&row.text.length>0&&row.text.length<=4000;

/** The owner is already reading this one formal note. Neither entering nor leaving reads a project directory. */
export function ProjectConventionDraftAction({onOpenProjectDraft,row,readHandle,expires,project,disabled,run,back}:Props){
 const [open,setOpen]=useState(false),[review,setReview]=useState<Data|null>(null),[pending,setPending]=useState(false),[error,setError]=useState(''),[outcome,setOutcome]=useState('');
 const [destination,setDestination]=useState<ProjectDraftReturn|null>(null);
 const live=useRef(false),seq=useRef(0),locked=useRef(false),pin=useRef(''),op=useRef(run);op.current=run;
 const release=(h:string)=>{if(h)void op.current('knowledge.close',{handle:h}).catch(()=>{});};
 const leave=()=>{seq.current++;locked.current=false;release(pin.current);pin.current='';setOpen(false);setReview(null);setPending(false);setError('');};
 useEffect(()=>{live.current=true;locked.current=false;pin.current='';setOpen(false);setReview(null);setPending(false);setError('');setOutcome('');setDestination(null);const doc=bridge.getDocumentEpoch();
   const changed=()=>{if(document.hidden||doc!==bridge.getDocumentEpoch()){leave();setOutcome('');setDestination(null);}};
   const off=bridge.subscribe(changed);document.addEventListener('visibilitychange',changed);const gone=()=>{leave();setOutcome('');setDestination(null);};window.addEventListener('pagehide',gone);
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
     const earlier=Number.isSafeInteger(v?.fromRevision)&&v.fromRevision>0&&v.fromRevision<row.revision&&Number.isSafeInteger(v.oldChars)&&v.oldChars>0&&v.oldChars<=4000;
     if(checked(v)&&!confirm&&v.transfer==='same_text'&&v.written===false&&!v.review&&Number.isSafeInteger(v.fromRevision)&&v.fromRevision>0&&v.fromRevision<=row.revision&&v.oldChars===row.text.length){
       leave();setOutcome(v.fromRevision===row.revision?'原输入框已是这条约定的同一版。这次没有写入或提交。':'原草稿正文与这版相同，仍保留当时挑选的修订 '+v.fromRevision+'。这次没有改写来源或提交。');return;
     }
     if(!checked(v)||!(v.transfer==='place'||v.transfer==='replace_older'&&earlier)||confirm&&(v.checkoutId!==review?.checkoutId||v.transfer!==review?.transfer||v.fromRevision!==review?.fromRevision||v.written!==true)||!confirm&&(v.written!==false||typeof v.review!=='string'||!v.review||!Number.isSafeInteger(v.expiresAt)||v.expiresAt<=Date.now())){if(!confirm)release(v?.review);throw new Error('回执与这条原项目约定不一致');}
     if(confirm){leave();
       if(typeof v.draftReturnHandle==='string'&&/^[a-f0-9-]{36}$/.test(v.draftReturnHandle)&&Number.isSafeInteger(v.draftReturnExpiresAt)&&v.draftReturnExpiresAt>Date.now())
         setDestination({projectId:project,checkoutId:v.checkoutId,handle:v.draftReturnHandle,expiresAt:v.draftReturnExpiresAt});
       setOutcome(v.transfer==='replace_older'?'已将原项目未发送草稿中的这条约定从修订 '+v.fromRevision+' 更新为 '+v.revision+'。已经提交的任务不变。':'已放入这个项目的「新项目工作 → 默认模型 → 交付要求」。还没有提交，开始前可修改或移除。');}
     else{pin.current=v.review;setReview(v);}
   }catch(e){if(live.current&&seq.current===n){if(confirm){leave();setOutcome('放入结果未确认，请回原项目核对草稿，不要重复放入。');}
      else setError(e instanceof Error?e.message:String(e));}}
   finally{if(live.current&&seq.current===n){locked.current=false;setPending(false);}}
 };
 return <><Button variant="secondary" size="small" disabled={disabled||pending||Date.now()>=expires} onClick={()=>void take(false)}>用在一次项目委托</Button>
   {outcome&&<p role="status" className="pw-muted">{outcome}</p>}
   {destination&&onOpenProjectDraft&&bridge.getState().state.projectId===destination.projectId&&!bridge.getState().state.filePath&&
     <Button variant="secondary" size="small" disabled={disabled||pending} onClick={()=>{
       if(document.hidden||Date.now()>=destination.expiresAt){setDestination(null);setOutcome('定位已过期。草稿没有因此改变，可以回原项目手动核对。');return}
       onOpenProjectDraft(destination);
     }}>打开刚放入的原项目草稿</Button>}
   <SettingsDialog className="pw-memory-modal" isOpen={open} title={review?.transfer==='replace_older'?'替换原草稿中的旧版约定':'只用在一次项目委托'} closeLabel="返回这条约定" onClose={()=>{if(!locked.current)leave();}}>
    {review&&<><p>{review.name||'原项目'} · 原私人副本 {review.checkoutId.slice(0,8)} · 约定修订 {review.revision}</p>
      {review.transfer==='replace_older'&&<p>原草稿仍是这条约定未手改的修订 {review.fromRevision}（{review.oldChars} 字符）。确认后只把这一份换成下方新版，不展示或替换其他草稿内容。</p>}
      <p className="pw-reading-copy" dir="auto">{row.text}</p><p className="pw-muted">{review.transfer==='replace_older'?'不会改动已经交办的任务。是否用新版开始新任务，仍由你回项目核对。':'只将上面这份文字复制到原项目的未发送草稿。不读取其他记忆；原记忆的修改或遗忘不会改写已复制的文字。回项目核对并开始后，它才会成为这次交办的限制。'}</p>
      <Button disabled={pending} onClick={()=>void take(true)}>{review.transfer==='replace_older'?'用这版替换原草稿里的旧约定':'放入原项目草稿'}</Button></>}
    {pending&&<p role="status">正在核对原记录与草稿…</p>}{error&&<p role="alert" className="pw-error">{error}</p>}
    <Button variant="ghost" disabled={pending} onClick={leave}>返回这条约定</Button>
   </SettingsDialog></>;
}
