import {useEffect,useRef,useState} from 'react';
import {bridge} from './bridge';
import {registerOverlayBack} from './overlay-back';
import {Button,SettingsDialog} from './forms-library';

type Result={text:string;sourceRevision:number;projectId:string;checkoutId:string};
type Data=Record<string,any>;
const nativeId=(v:unknown):v is string=>typeof v==='string'&&/^[A-Za-z0-9_-]{1,80}$/.test(v);

/** Only opened by the owner from one native saved reply. Never searches memory or another project. */
export function ProjectConventionProvided({messageId,onClose}:{messageId:string;onClose:()=>void}){
 const [result,setResult]=useState<Result|null>(null),[error,setError]=useState('');
 const close=useRef(onClose);close.current=onClose;
 useEffect(()=>{let live=true;const read=()=>{const current=bridge.getState(),state=current.state,view=current.view as Data|undefined;
   const chosen=(Array.isArray(state.messages)?state.messages:[]).find((m:Data)=>m.id===messageId&&m.role==='assistant'&&m.projectConventionRecorded===true);
   if(!chosen||document.hidden||view?.taskId||view?.section)return null;
   const task=(Array.isArray(state.tasks)?state.tasks:[]).find((t:Data)=>t.id===chosen.taskId);
   return {task:chosen.taskId,stamp:JSON.stringify([bridge.getDocumentEpoch(),state.conversationId,view?.resumeRevision,chosen.id,chosen.sourceRevision,chosen.taskId,task?.raw?.runId,task?.raw?.coordinatorEpoch,task?.raw?.cancelRequested])};
 };
 const origin=read();const leave=()=>{if(live){live=false;setResult(null);close.current();}};
 const check=()=>{if(!origin||read()?.stamp!==origin.stamp)leave();};const back=registerOverlayBack(leave),off=bridge.subscribe(check);document.addEventListener('visibilitychange',check);window.addEventListener('pagehide',leave);
 const timer=window.setTimeout(leave,120000);check();setError('');setResult(null);
 if(live)void bridge.command('conversation.projectConvention.read',{messageId}).then(v=>{
    if(!live||!origin||read()?.stamp!==origin.stamp)return;
    if(v?.messageId!==messageId||v.taskId!==origin.task||v.providedToThisAnswer!==true||v.modelUseVerified!==false||v.currentSourceRead!==false||typeof v.text!=='string'||!v.text||v.text.length>4000||
       !Number.isSafeInteger(v.sourceRevision)||v.sourceRevision<1||!nativeId(v.projectId)||!nativeId(v.checkoutId))throw new Error('原回答与实际请求的提供回执不完整。');
    setResult(v);
 }).catch(e=>{if(live){setResult(null);setError(e instanceof Error?e.message:String(e));}});
 return()=>{live=false;back();off();document.removeEventListener('visibilitychange',check);window.removeEventListener('pagehide',leave);clearTimeout(timer);};
 },[messageId]);
 return <SettingsDialog isOpen title={result?"这条回答收到的项目约定":"核对本次项目约定"} closeLabel="返回原回答" onClose={onClose}>
  {result?<><p className="pw-muted">你为这次交办挑选的手写约定 · 原修订 {result.sourceRevision}</p><p className="pw-reading-copy" dir="auto">{result.text}</p>
    <p>这段原文完整出现于产生这条答复的实际请求中。收到不等于模型采用，也不证明结果符合约定。</p>
    <details><summary>原项目与工作副本</summary><p className="pw-muted" style={{overflowWrap:'anywhere'}}>{result.projectId}<br/>{result.checkoutId}</p></details>
    <p className="pw-muted">这是交办时的那一版，没有读取今天的项目记忆。以后要改约定，在记忆中选原项目并纠正；旧答复不会改写。</p></>:error?<p className="pw-error" role="alert">{error}</p>:<p role="status">正在核对原答复、交办及实际模型请求…</p>}
  <Button variant="ghost" onClick={onClose}>返回原回答</Button>
 </SettingsDialog>;
}
