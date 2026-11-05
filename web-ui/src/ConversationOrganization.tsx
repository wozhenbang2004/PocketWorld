import {useEffect,useRef,useState} from 'react';
import {bridge} from './bridge';
import {registerOverlayBack} from './overlay-back';
import {Button,CheckboxField,SettingsDialog,TextField} from './forms-library';

type Data=Record<string,any>;
export function orderConversations<T extends {id:string;pinOrder?:number;updatedAt?:number}>(items:T[]):T[]{
 return [...items].sort((a,b)=>Number((b.pinOrder||0)>0)-Number((a.pinOrder||0)>0)||(Number(b.pinOrder||0)-Number(a.pinOrder||0))||Number(b.updatedAt||0)-Number(a.updatedAt||0)||a.id.localeCompare(b.id));
}
export function ConversationOrganization({conversationId,onClose}:{conversationId:string;onClose:()=>void}) {
 const [selection,setSelection]=useState<Data|null>(null),[title,setTitle]=useState(''),[pinned,setPinned]=useState(false),[busy,setBusy]=useState(false),[error,setError]=useState('');
 const current=useRef(selection),generation=useRef(0),alive=useRef(true),writing=useRef(false),epoch=bridge.getDocumentEpoch();current.current=selection;
 async function read(){
  if(writing.current||document.visibilityState==='hidden')return;const request=++generation.current,documentEpoch=bridge.getDocumentEpoch();setBusy(true);setSelection(null);setError('');
  try{const value=await bridge.command<Data>('conversation.organization.read',{conversationId});if(alive.current&&request===generation.current&&documentEpoch===bridge.getDocumentEpoch()){setSelection(value);setTitle(value.title);setPinned(value.pinned===true)}}
  catch(cause){if(alive.current&&request===generation.current)setError(cause instanceof Error?cause.message:'对话资料尚未读取')}
  finally{if(alive.current&&request===generation.current)setBusy(false)}
 }
 const close=()=>{if(writing.current)return;const handle=current.current?.selectionHandle;if(handle)void bridge.command('conversation.organization.close',{selectionHandle:handle}).catch(()=>{});onClose()};
 useEffect(()=>{alive.current=true;void read();const visibility=()=>{if(document.visibilityState==='hidden'){generation.current++;setSelection(null);setBusy(false)}else void read()};document.addEventListener('visibilitychange',visibility);return()=>{alive.current=false;generation.current++;document.removeEventListener('visibilitychange',visibility)}},[conversationId,epoch]);
 useEffect(()=>registerOverlayBack(close),[]);
 async function save(){
  if(writing.current||!selection||!title.trim()||title.trim().length>120)return;const selected=selection,documentEpoch=bridge.getDocumentEpoch();writing.current=true;setBusy(true);setError('');
  try{await bridge.command('conversation.organization.save',{selectionHandle:selected.selectionHandle,title:title.trim(),pinned});if(alive.current&&documentEpoch===bridge.getDocumentEpoch())onClose()}
  catch(cause){if(alive.current){setSelection(null);setError(cause instanceof Error?cause.message:'保存回执未确认，请重新读取原对话')}}
  finally{writing.current=false;if(alive.current)setBusy(false)}
 }
 return <SettingsDialog isOpen title="整理对话" onClose={close}><div className="pw-conversation-organization">
 {error&&<p className="pw-error" role="alert">{error}</p>}{busy&&!selection&&<p role="status">正在读取原对话…</p>}
 <TextField label="对话名称" value={title} onChange={value=>setTitle(value.slice(0,120))} isDisabled={busy||!selection}/><CheckboxField isSelected={pinned} onChange={setPinned} isDisabled={busy||!selection}>置顶到对话列表前面</CheckboxField>
 <div className="pw-action-row"><Button variant="secondary" disabled={busy} onClick={close}>取消</Button>{!selection?<Button variant="primary" disabled={busy} onClick={()=>void read()}>重新读取原对话</Button>:<Button variant="primary" disabled={busy||!title.trim()} onClick={()=>void save()}>保存修改</Button>}</div>
 </div></SettingsDialog>;
}
