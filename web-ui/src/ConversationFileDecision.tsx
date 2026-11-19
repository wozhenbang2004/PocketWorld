import {useEffect,useRef,useState} from 'react';
import {bridge} from './bridge';
import {Button} from './forms-library';
import {IconChevronRight} from './icons';
import type {TeamDecisionOrigin} from './team-navigation';
import type {TeamFileDecisionMark} from './team-file-decision-mark';

export function ConversationFileDecision({mark,source,onOpened}:{mark:TeamFileDecisionMark;source:TeamDecisionOrigin;onOpened:()=>void}){
  const [busy,setBusy]=useState(false),[error,setError]=useState(''),[expired,setExpired]=useState(false);
  const sequence=useRef(0),pending=useRef(false);
  useEffect(()=>{
    const update=()=>setExpired(Date.now()>=mark.expiresAt);
    update();const expiry=window.setTimeout(update,Math.min(2147483647,Math.max(0,mark.expiresAt-Date.now())));
    const visibility=()=>{update();if(document.hidden){sequence.current++;pending.current=false;setBusy(false);setError('')}};
    document.addEventListener('visibilitychange',visibility);
    return()=>{sequence.current++;pending.current=false;clearTimeout(expiry);document.removeEventListener('visibilitychange',visibility)};
  },[mark.approvalId,mark.expiresAt]);
  if(expired||!bridge.getState().capabilities.commands.includes('teams.openWorkFileDecision'))return null;
  const open=async()=>{
    if(pending.current||document.hidden||Date.now()>=mark.expiresAt||bridge.getState().state.conversationId!==source.conversationId)return;
    pending.current=true;setBusy(true);setError('');const token=++sequence.current;
    const current=()=>token===sequence.current&&!document.hidden&&bridge.getState().state.conversationId===source.conversationId;
    try{
      const result=await bridge.command('teams.openWorkFileDecision',{...source,pendingFileOperation:mark});
      if(!current())return;
      if(result.taskId!==mark.taskId||result.readOnly!==true||result.executionAuthorized!==false)throw new Error('没有取得这笔原文件请求的只读打开回执。');
      onOpened();
    }catch(e){if(current())setError(e instanceof Error?e.message:'这笔写回已变化，没有打开另一个任务。')}
    finally{if(current()){pending.current=false;setBusy(false)}}
  };
  return <><Button size="small" variant="ghost" disabled={busy} trailingIcon={IconChevronRight} onClick={()=>void open()}>
    {busy?'正在核对原请求…':'核对这笔文件写回'}
  </Button>{error&&<p role="alert">{error}</p>}</>;
}
