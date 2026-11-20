import {useEffect,useId,useRef,useState} from 'react';
import {Accordion,AccordionItem,Button} from './forms-library';

type Data=Record<string,any>;
export type PendingInput=Data&{clientMessageId:string;draftKey:string;current:boolean;work:boolean;otherEditor:boolean;sameDraft:boolean;preview:string;origin:string;receipt:Data};
const label=(value:unknown)=>typeof value==='string'?value.trim().replace(/\s+/g,' '):'';
const preview=(value:unknown)=>{const chars=Array.from(label(value));return chars.slice(0,64).join('')+(chars.length>64?'…':'')};

/** Only native pending identities survive a remount. Never infer delivery from
 * a bridge command ACK or attribute the current editor's new text to an older send. */
export function conversationPending(composer:Data,receipts:Data,messages:readonly Data[],conversation:string,conversations:readonly Data[],editorText:string):PendingInput[]{
 const rows=Array.isArray(composer.pending)?composer.pending.filter((p:Data)=>p&&typeof p.clientMessageId==='string'&&p.clientMessageId&&typeof p.draftKey==='string'&&p.draftKey):[];
 if(!rows.length)return [];
 const wanted=new Set(rows.filter((row:Data)=>row.draftKey===composer.key&&row.conversationId===conversation).map((row:Data)=>row.clientMessageId));
 const byClient=new Map<string,Data|null>();
 if(wanted.size)for(const message of messages){const id=message.submission?.clientMessageId;if(message.role==='user'&&wanted.has(id))byClient.set(id,byClient.has(id)?null:message)}
 return rows.map((p:Data)=>{
  const key=p.draftKey,work=key.startsWith('work:'),current=!work&&key===composer.key&&p.conversationId===conversation&&composer.conversationId===conversation;
  const sameRevision=current&&Number.isSafeInteger(p.draftRevision)&&p.draftRevision>0&&p.draftRevision===composer.revision;
  const receipt=p.receipt||receipts[p.clientMessageId]||{};
  const ownReceipt=!receipt.clientMessageId||receipt.clientMessageId===p.clientMessageId?receipt:{};
  const message=current?byClient.get(p.clientMessageId):undefined;
  const oldText=message?.content||(sameRevision?composer.text:'');
  const origin=work?'原工作的补充':current?(sameRevision?'当前输入':'本话题较早的输入'):label(conversations.find(item=>item.id===p.conversationId)?.title)||'其他对话的输入';
  return {...p,clientMessageId:p.clientMessageId,draftKey:key,current,work,otherEditor:key!==composer.key,sameDraft:sameRevision&&editorText===composer.text,
   receipt:ownReceipt,preview:preview(oldText),origin};
 });
}

type Action='query'|'retry'|'return'|'release-prepared';
type Props={rows:PendingInput[];can:(action:string)=>boolean;submitting:boolean;ordinaryComposer:boolean;readWindow:string;
 onAction:(action:Action,row:PendingInput)=>void;onReadSavedFirst:(row:PendingInput)=>Promise<string>};

function SavedFirstChatExcerpt({read}:{read:()=>Promise<string>}){
 const region=useId();
 const serial=useRef(0),inFlight=useRef(false);
 const [body,setBody]=useState<string|null>(null),[error,setError]=useState(''),[busy,setBusy]=useState(false);
 useEffect(()=>{
  const hide=()=>{if(document.hidden){serial.current++;inFlight.current=false;setBody(null);setError('');setBusy(false)}};
  document.addEventListener('visibilitychange',hide);
  return()=>{serial.current++;document.removeEventListener('visibilitychange',hide)};
 },[]);
 const toggle=async()=>{
  if(document.hidden||inFlight.current)return;
  if(body!==null){setBody(null);return}
  const key=++serial.current;inFlight.current=true;setBusy(true);setError('');
  try{const original=await read();if(serial.current===key&&!document.hidden)setBody(original)}
  catch(failure){if(serial.current===key&&!document.hidden)setError(failure instanceof Error?failure.message:'没有读到原保存记录。')}
  finally{if(serial.current===key){inFlight.current=false;setBusy(false)}}
 };
 return <div>
  <Button variant="secondary" size="small" disabled={busy} aria-expanded={body!==null} aria-controls={region} onClick={()=>void toggle()}>
   {busy?'正在读取原话…':body!==null?'收起原话':'查看已保存原话'}
  </Button>
  <div id={region}>
   {error&&<p role="alert">{error}</p>}
   {body!==null&&<p dir="auto" className="whitespace-pre-wrap break-words">{body}</p>}
  </div>
 </div>;
}

export function ConversationDelivery({rows,can,submitting,ordinaryComposer,readWindow,onAction,onReadSavedFirst}:Props){
 const current=rows.filter(row=>row.current),others=rows.filter(row=>!row.current);
 if(!rows.length)return null;
 const entry=(row:PendingInput,index:number)=>{
  const knownUnsent=row.canRetryLocallyUnsent===true;
  const canKeepDraft=ordinaryComposer&&row.current&&row.sameDraft&&row.canReleasePreparedFirstChat===true&&can('composer.releasePreparedFirst');
  const state=String(row.receipt.status||row.status||'');
  const savedFirst=ordinaryComposer&&!knownUnsent&&row.current&&row.sameDraft&&state==='unconfirmed'&&row.canReadSavedFirstChatSource===true&&can('composer.readSavedFirst');
  const heading=knownUnsent||state==='not_sent'?'这条输入未发出':state==='rejected'?'这条输入未被接收':state==='withdrawn'?'这条输入已撤回':
   ['accepted','queued','consumed'].includes(state)?'原消息已有回执':savedFirst?'原话已保存，任务受理待确认':canKeepDraft?'原草稿待核对':'等待原发送回执';
  const original=row.preview&&!savedFirst?('「'+row.preview+'」'):row.origin+(row.current&&current.length>1?' · 第 '+(index+1)+' 条':'');
  const detail=label(row.receipt.message||row.receipt.reason);
  return <div key={row.clientMessageId} className="pw-pending-input">
   <strong role={row.current?'status':undefined}>{heading}</strong><small>{original}{detail?' · '+detail:''}</small>
   <div className="pw-action-row">
    {canKeepDraft&&<Button variant="secondary" size="small" disabled={submitting} onClick={()=>onAction('release-prepared',row)}>保留草稿，解除等待</Button>}
    {can('composer.query')&&<Button variant="ghost" size="small" onClick={()=>onAction('query',row)}>查询原回执</Button>}
    {row.otherEditor&&can('composer.returnPending')&&<Button variant="ghost" size="small" disabled={submitting} onClick={()=>onAction('return',row)}>{row.work?'返回原工作补充':'返回原输入'}</Button>}
    {knownUnsent&&can('composer.retry')&&<Button variant="secondary" size="small" disabled={submitting} onClick={()=>onAction('retry',row)}>重新保存并发送</Button>}
   </div>
   {savedFirst&&<SavedFirstChatExcerpt key={readWindow+':'+row.clientMessageId+':'+row.draftRevision} read={()=>onReadSavedFirst(row)}/>}
  </div>;
 };
 return <section className="pw-pending-inputs" aria-label="待确认的消息">
  {current.map(entry)}
  {!!others.length&&<Accordion variant="divided"><AccordionItem id="other-inputs" title={'其他待核对输入 · '+others.length}>{others.map(entry)}</AccordionItem></Accordion>}
 </section>;
}

type Ticket={owner:string;revision:unknown;text:string};
const matches=(ticket:Ticket|null,owner:string,revision:unknown,text:string)=>!!ticket&&ticket.owner===owner&&ticket.revision===revision&&ticket.text===text;
/** Short-lived UI state for the command round trip, not a second pending ledger.
 * Another native editor/revision or newly typed text does not inherit a send lock. */
export function useConversationRegistration(owner:string,revision:unknown,text:string){
 const current=useRef<Ticket|null>(null),[shown,setShown]=useState<Ticket|null>(null);
 return {waiting:matches(shown,owner,revision,text),begin(){
  if(matches(current.current,owner,revision,text))return null;
  const ticket={owner,revision,text};current.current=ticket;setShown(ticket);
  return ()=>{if(current.current===ticket){current.current=null;setShown(null)}};
 }};
}
