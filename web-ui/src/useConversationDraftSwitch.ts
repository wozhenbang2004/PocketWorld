import {useCallback,useEffect,useRef,useState} from 'react';
import {bridge} from './bridge';

/** A command's acknowledgement may arrive before Android publishes the new editor. */
export function useConversationDraftSwitch(report:(message:string)=>void){
 const active=useRef<{cancel():void}|null>(null);
 const [switching,setSwitching]=useState(false);
 useEffect(()=>()=>active.current?.cancel(),[]);
 const pending=useCallback(()=>active.current!==null,[]);
 const open=useCallback((stillHere:()=>boolean,retainedDraftProjection?:number)=>{
  if(active.current)return;
  const before=bridge.getState(),documentEpoch=bridge.getDocumentEpoch();
  const previousEpoch=before.composer?.epoch;
  const resume=retainedDraftProjection!==undefined;
  let retained:{key:string;epoch:number;revision:number}|undefined;
  let unsubscribe=()=>{},unsubscribeDocument=()=>{},timer:number|undefined;
  const handoff={cancel(){
   unsubscribe();unsubscribeDocument();window.clearTimeout(timer);
   if(active.current===handoff)active.current=null;
  }};
  const finish=(reason?:string)=>{
   if(active.current!==handoff)return;
   handoff.cancel();setSwitching(false);
   if(reason&&stillHere()&&!document.hidden&&bridge.getDocumentEpoch()===documentEpoch)report(reason);
  };
  const check=()=>{
   if(bridge.getDocumentEpoch()!==documentEpoch){finish();return}
   const {state,composer}=bridge.getState(),reader=state.conversationReader;
   if(resume){
    if(!stillHere()||document.hidden){finish();return;}
    if(retained&&!reader&&!state.conversationId&&composer?.ready===true&&composer.ordinaryNewAttachmentDraft===true&&
       composer.conversationId===''&&composer.key===retained.key&&composer.epoch===retained.epoch&&
       composer.revision===retained.revision)finish();
    return;
   }
   if(!state.conversationId&&composer?.ready===true&&Number.isSafeInteger(composer.epoch)&&composer.epoch!==previousEpoch&&
      composer.conversationId===''&&typeof composer.key==='string'&&composer.key.startsWith('new:'))finish();
  };
  active.current=handoff;setSwitching(true);
  unsubscribe=bridge.subscribe(check);unsubscribeDocument=bridge.subscribeDocumentEpoch(check);
  const unconfirmed=resume?'尚未确认已回到原未发草稿，请核对当前输入。':'尚未确认已切换对话，请核对当前草稿后再编辑。';
  void bridge.command('conversation.new',resume?{retainedDraftProjection}:{}).then(value=>{
   if(active.current!==handoff)return;
   if(resume){
    if(value?.resumed!==true||typeof value.key!=='string'||!value.key.startsWith('new:')||
       !Number.isSafeInteger(value.epoch)||value.epoch===previousEpoch||!Number.isSafeInteger(value.revision)||value.revision<1){
     finish(unconfirmed);return;
    }
    retained={key:value.key,epoch:value.epoch,revision:value.revision};
   }
   check();
   if(active.current===handoff)timer=window.setTimeout(()=>finish(unconfirmed),5000);
  },error=>finish(error instanceof Error?error.message:unconfirmed));
 },[report]);
 return {switching,pending,open};
}
