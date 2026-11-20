import {useCallback,useEffect,useRef,useState} from 'react';
import {bridge} from './bridge';

/** A command's acknowledgement may arrive before Android publishes the new editor. */
export function useConversationDraftSwitch(report:(message:string)=>void){
 const active=useRef<{cancel():void}|null>(null);
 const [switching,setSwitching]=useState(false);
 useEffect(()=>()=>active.current?.cancel(),[]);
 const pending=useCallback(()=>active.current!==null,[]);
 const open=useCallback((target:string|null,stillHere:()=>boolean)=>{
  if(active.current)return;
  const before=bridge.getState(),documentEpoch=bridge.getDocumentEpoch();
  const previousEpoch=before.composer?.epoch,previousReader=before.state.conversationReader?.window;
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
   // Selecting during submission can intentionally open a read-only window.
   if(target!==null&&reader?.conversation===target&&Number.isSafeInteger(reader.window)&&reader.window!==previousReader){finish();return}
   const conversation=String(state.conversationId||'');
   if(composer?.ready===true&&Number.isSafeInteger(composer.epoch)&&composer.epoch!==previousEpoch&&
      composer.conversationId===conversation&&(target===null?conversation===''&&typeof composer.key==='string'&&composer.key.startsWith('new:'):
       conversation===target&&composer.key===`conversation:${target}`))finish();
  };
  active.current=handoff;setSwitching(true);
  unsubscribe=bridge.subscribe(check);unsubscribeDocument=bridge.subscribeDocumentEpoch(check);
  const unconfirmed='尚未确认已切换对话，请核对当前草稿后再编辑。';
  void bridge.command(target===null?'conversation.new':'conversation.select',target===null?{}:{id:target}).then(()=>{
   if(active.current!==handoff)return;
   check();
   if(active.current===handoff)timer=window.setTimeout(()=>finish(unconfirmed),5000);
  },error=>finish(error instanceof Error?error.message:unconfirmed));
 },[report]);
 return {switching,pending,open};
}
