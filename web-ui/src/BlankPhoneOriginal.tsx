import { useLayoutEffect, useRef, useState } from 'react';
import { bridge, type PocketSnapshot } from './bridge';
import { Button } from './forms-library';

type PhoneMode = 'main_screen'|'background';
type Input = {key:string;epoch:number;revision:number;phoneEpoch:number;phoneRevision:number};
type Saved = {handle:string;conversation:string;message:string;visit:string};
const uuid=(id:unknown):id is string=>typeof id==='string'&&/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(id);
const number=(value:unknown):value is number=>typeof value==='number'&&Number.isSafeInteger(value)&&value>=0;
const obj=(value:unknown):Record<string,any>=>value&&typeof value==='object'?value as Record<string,any>:{};

function input(snapshot:PocketSnapshot,text:string,mode:PhoneMode|null):Input|null{
  const {state,composer}=snapshot,view=obj(snapshot.view),phone=obj(snapshot.phoneDraft);
  if(!mode||view.section!==null||view.taskId!==null||state.conversationId||state.conversationReader||
     !composer||composer.ready!==true||typeof composer.key!=='string'||!composer.key.startsWith('new:')||composer.conversationId!==''||
     phone.saveFirstOriginalReady!==true||phone.phone!==true||phone.mode!==mode||phone.text!==text||
     !text.trim()||text.length>4000||!number(composer.epoch)||!number(composer.revision)||
     !number(phone.epoch)||!number(phone.revision)||!snapshot.capabilities.commands.includes('phone.draft.saveOriginal'))return null;
  return {key:composer.key,epoch:composer.epoch,revision:composer.revision,phoneEpoch:phone.epoch,phoneRevision:phone.revision};
}

export function BlankPhoneOriginal({enabled,mode,text,editorText,navigation,navigationCurrent,arrived}:{
  enabled:boolean;mode:PhoneMode|null;text:string;editorText:()=>string;navigation:number;navigationCurrent:(navigation:number)=>boolean;
  arrived:(stillHere:()=>boolean)=>void;
}){
  const alive=useRef(false),flight=useRef(false),visible=useRef(enabled);
  visible.current=enabled;
  const selection=useRef({mode,revision:0});
  if(selection.current.mode!==mode)selection.current={mode,revision:selection.current.revision+1};
  const choice=selection.current;
  const [pending,setPending]=useState(false),[saved,setSaved]=useState<Saved|null>(null);
  const [feedback,setFeedback]=useState<{visit:string;text:string}|null>(null);
  useLayoutEffect(()=>{alive.current=true;return()=>{alive.current=false}},[]);
  const snapshot=bridge.getState(),parent=enabled?input(snapshot,text,mode):null,documentId=bridge.getDocumentEpoch();
  const visitOf=(value:Input|null)=>value?JSON.stringify([documentId,navigation,choice,value]):'';
  const visit=visitOf(parent);
  const original=saved?.visit===visit&&visit?saved:null;
  const here=()=>alive.current&&visible.current&&selection.current===choice&&!document.hidden&&bridge.getDocumentEpoch()===documentId&&navigationCurrent(navigation);
  const sameInput=()=>here()&&visit!==''&&visit===visitOf(input(bridge.getState(),editorText(),mode));
  const commands=snapshot.capabilities.commands;
  const save=async()=>{
    if(!parent||!sameInput()||flight.current)return;
    flight.current=true;setPending(true);setFeedback(null);
    try{
      const r=obj(await bridge.command('phone.draft.saveOriginal',parent));
      if(!sameInput())return;
      if(r.status!=='saved_local'||r.revision!==1||r.dispatched!==false||r.modelCalled!==false||r.networkUsed!==false||
         r.executionAuthorized!==false||r.taskCreated!==false||!uuid(r.conversationId)||!uuid(r.messageId)||!uuid(r.viewSavedPhoneHandle))
        throw new Error('本机保存未核对');
      setSaved({visit,handle:r.viewSavedPhoneHandle,conversation:r.conversationId,message:r.messageId});
    }catch{if(sameInput())setFeedback({visit,text:'本机保存尚未核对，文字仍保留。若刚才已保存，可在私聊列表核对原话。不会发送。'});}
    finally{flight.current=false;if(alive.current)setPending(false);}
  };
  const open=async()=>{
    if(!original||!sameInput()||flight.current||!bridge.getState().capabilities.commands.includes('conversation.select'))return;
    flight.current=true;setPending(true);setFeedback(null);
    const sameOriginal=()=>{
      const now=bridge.getState(),view=obj(now.view),messages=now.state.messages,phone=obj(now.phoneDraft);
      return here()&&phone.phone===true&&phone.mode===mode&&phone.sourceState==='current'&&
        view.section===null&&view.taskId===null&&!now.state.conversationReader&&
        now.state.conversationId===original.conversation&&now.composer?.conversationId===original.conversation&&
        Array.isArray(messages)&&messages.filter(row=>row?.id===original.message&&row.role==='user'&&row.sourceRevision===1&&row.quoteAvailable===true).length===1;
    };
    try{
      const r=obj(await bridge.command('conversation.select',{viewSavedPhoneHandle:original.handle}));
      if(!here())return;
      if(r.opened!==true||r.conversationId!==original.conversation||r.messageId!==original.message||r.revision!==1)
        throw new Error('原话未核对');
      // Wait for the existing selected-Chat publication; no refresh or transcript request.
      const inOriginal=await new Promise<boolean>(resolve=>{
        let unsubscribe=()=>{};
        const finish=(ok:boolean)=>{window.clearTimeout(timeout);unsubscribe();resolve(ok);};
        const timeout=window.setTimeout(()=>finish(false),5000);
        const check=()=>{if(!here()||sameOriginal())finish(sameOriginal());};
        unsubscribe=bridge.subscribe(check);check();
      });
      if(inOriginal&&sameOriginal())arrived(sameOriginal);
      else if(sameInput())throw new Error('原话未到达');
    }catch{if(sameInput())setFeedback({visit,text:'暂未打开这段原话。可在私聊列表核对已保存的记录。'});}
    finally{flight.current=false;if(alive.current)setPending(false);}
  };
  if(!parent)return null;
  return <div className="pw-action-row" aria-label="本机保存手机目标">
    {original?<>
      <p className="pw-field-hint" role="status">这次只保存了原话，没有创建任务。</p>
      {commands.includes('conversation.select')&&<Button variant="secondary" size="small" disabled={pending} onClick={()=>void open()}>打开这段原话</Button>}
    </>:<>
      <Button variant="secondary" size="small" disabled={pending} onClick={()=>void save()}>{pending?'正在保存原话…':'保存为新私聊'}</Button>
      <p className="pw-field-hint">只在这台手机保存目标，不发送、不调用模型，也不开始任务。</p>
    </>}
    {feedback?.visit===visit&&<p className="pw-field-hint" role="status">{feedback.text}</p>}
  </div>;
}
