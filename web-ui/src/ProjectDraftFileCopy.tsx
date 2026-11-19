import {useEffect,useRef,useState} from 'react';
import {bridge} from './bridge';
import {Button} from './forms-library';

const OFFER='projectDraft.reviewFileCopy',SAVE='projectDraft.saveFileCopy';
const destination='Download/PocketWorld/';
type Info={taskId:string;draftId:string;revisionId:string;sha256:string;runId:string;epoch:number;path:string;ordinal:number;bytes:number;
  name:string;destination:string;reviewHandle?:string;status?:string;savedName?:string};
function view(){return bridge.getState().view as {resumeRevision?:number;section?:string;taskId?:string}|undefined;}
function ticket(){const v=view();return JSON.stringify([bridge.getDocumentEpoch(),v?.resumeRevision,v?.section,v?.taskId]);}

export function ProjectDraftFileCopy({task,root,revision,sha,path,ordinal,disabled,lock}:{
  task:string;root:string;revision:string;sha:string;path:string;ordinal:number;disabled:boolean;lock:(held:boolean)=>void
}){
  const [review,offer]=useState<Info|null>(null),[notice,tell]=useState(''),[saved,record]=useState(false),[busy,spin]=useState(false);
  const sequence=useRef(0),held=useRef(''),inFlight=useRef(false);
  const can=()=>!document.hidden && view()?.taskId===task && !view()?.section &&
    [OFFER,SAVE].every(k=>bridge.getState().capabilities.commands.includes(k));
  useEffect(()=>{lock(!!review||busy);return()=>lock(false)},[review,busy,lock]);
  useEffect(()=>{
    const reset=()=>{++sequence.current;inFlight.current=false;held.current='';offer(null);tell('');record(false);spin(false)};
    reset();
    const leave=()=>{if(held.current&&(!can()||ticket()!==held.current))reset()};
    const off=bridge.subscribe(leave);document.addEventListener('visibilitychange',leave);
    return()=>{++sequence.current;inFlight.current=false;off();document.removeEventListener('visibilitychange',leave)};
  },[task,root,revision,sha,path,ordinal]);
  function same(r:Info){return r.taskId===task && r.draftId===root && r.revisionId===revision && r.sha256===sha &&
    r.path===path && r.ordinal===ordinal && typeof r.runId==='string' && !!r.runId && Number.isSafeInteger(r.epoch) && r.epoch>0 &&
    Number.isSafeInteger(r.bytes) && r.bytes>=0 && r.bytes<=24576 && r.destination===destination && r.name===path.split('/').pop()}
  async function run(save:boolean){
    if(disabled||inFlight.current||!can()||save&&(!review||held.current!==ticket()))return;
    const original=review,n=++sequence.current,t=ticket();held.current=t;inFlight.current=true;spin(true);tell('');
    if(save)offer(null);
    const live=()=>sequence.current===n&&can()&&ticket()===t;
    try{
      const r=await bridge.command<Info>(save?SAVE:OFFER,{taskId:task,draftId:root,revisionId:revision,sha256:sha,...(save?{reviewHandle:original!.reviewHandle}:{})});
      if(!live())return;
      if(!same(r))throw Error('没有收到这份稿的准确回执，请核对下载目录，没有重试。');
      if(!save){if(typeof r.reviewHandle!=='string'||!r.reviewHandle)throw Error('无法核对本次保存位置。');offer(r);return}
      if(r.runId!==original!.runId||r.epoch!==original!.epoch||r.bytes!==original!.bytes||r.status!=='saved'||typeof r.savedName!=='string'||!r.savedName)
        throw Error('尚不能确认已经保存，请先核对下载目录，未重试。');
      record(true);tell('已保存到「下载/PocketWorld/'+r.savedName+'」。已回读核对 '+r.bytes+' 字节，可以在系统文件中打开。');
    }catch(e){if(live())tell(e instanceof Error?e.message:'保存未能核对，没有自动重试。');}
    finally{if(sequence.current===n){inFlight.current=false;spin(false)}}
  }
  if(!can())return null;
  const version=ordinal===0?'最初的独立改稿':'你已保留的第 '+ordinal+' 稿';
  return <div className="pw-form-flow">
    {!review&&<Button variant="ghost" size="small" disabled={disabled||busy} onClick={()=>void run(false)}>{busy?'正在核对…':saved?'再存一份':'另存为文件'}</Button>}
    {review&&<div role="group" aria-label="确认另存这一稿"><strong>{review.name}</strong>
      <p className="pw-field-hint">{version} · {review.bytes} 字节 · 下载/PocketWorld。同名时另建文件，不覆盖。只保存这一稿，不附对照原文。文件将离开 App 私有空间，不会自动发送给别人。</p>
      <div className="pw-form-actions"><Button variant="secondary" size="small" disabled={busy||disabled} onClick={()=>void run(true)}>保存到手机下载</Button>
        <Button variant="ghost" size="small" disabled={busy} onClick={()=>offer(null)}>取消</Button></div></div>}
    {notice&&<p role="status" className="pw-field-hint">{notice}</p>}
  </div>;
}
