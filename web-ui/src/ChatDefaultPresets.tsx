import {useEffect,useRef,useState} from 'react';
import {bridge} from './bridge';
import {Button,SelectField,StretchSwitch} from './forms-library';
import {ShimmerText} from './library';

export const chatDefaultPresetsCommand='profile.conversation.defaults';
export type ChatDefaultOrigin={
 conversationId:string;draftEpoch:number;draftRevision:number;documentEpoch:number;current:()=>boolean;
};
type Receipt={
 visitId?:unknown;conversationId?:unknown;conversationAllowsDefaults?:unknown;defaults?:unknown;saved?:unknown;persisted?:unknown;
};
type TextField='language'|'response_detail'|'operation_pace'|'initiative';
type Flag='conclusionFirst'|'includeExamples';
type Field=TextField|Flag;
type Values=Record<TextField,string>&Record<Flag,boolean>;
type Reading={revision:number;values:Values;allows:boolean};
type Section='communication'|'behavior';
const options={
 language:[{id:'auto',label:'跟随对话'},{id:'zh-CN',label:'中文'},{id:'en',label:'English'},{id:'ja',label:'日本語'}],
 response_detail:[{id:'concise',label:'简洁'},{id:'standard',label:'适中'},{id:'detailed',label:'详细'}],
 operation_pace:[{id:'fast',label:'快速'},{id:'standard',label:'适中'},{id:'careful',label:'仔细'}],
 initiative:[{id:'requested',label:'按需'},{id:'balanced',label:'适度'},{id:'proactive',label:'主动建议'}],
};
const labels:Record<Field,string>={language:'回答语言',response_detail:'回答详略',conclusionFirst:'先给结论',
 includeExamples:'需要时举例',operation_pace:'做事节奏',initiative:'主动建议'};
const sections:Record<Section,readonly Field[]>={communication:['language','response_detail','conclusionFirst','includeExamples'],behavior:['operation_pace','initiative']};
const names:Record<Section,string>={communication:'交流偏好',behavior:'做事偏好'};
const fields=[...sections.communication,...sections.behavior];
const isText=(field:Field):field is TextField=>Object.prototype.hasOwnProperty.call(options,field);
const display=(field:Field,value:string|boolean)=>isText(field)?options[field].find(row=>row.id===value)?.label||'':value?'开启':'关闭';

export type ChatDefaultVisit={id:string;origin:ChatDefaultOrigin;opened:Promise<Receipt>;retiring?:Promise<boolean>};
export type ChatDefaultHolder={current:ChatDefaultVisit|null};
export function beginChatDefaultPresets(holder:ChatDefaultHolder,origin:ChatDefaultOrigin):ChatDefaultVisit|null {
 if(holder.current||!origin.current())return null;
 const {conversationId,draftEpoch,draftRevision}=origin,id=crypto.randomUUID();
 // Register before the first native read; ordinary Chat navigation must retire even a pending open.
 const visit:ChatDefaultVisit={id,origin,opened:Promise.resolve({})};
 holder.current=visit;
 visit.opened=bridge.command<Receipt>(chatDefaultPresetsCommand,{operation:'open',visitId:id,conversationId,draftEpoch,draftRevision});
 void visit.opened.catch(()=>{}); // The mounted leaf handles the same request; no second read on mount.
 return visit;
}
export function retireChatDefaultPresets(holder:ChatDefaultHolder):Promise<boolean>{
 const visit=holder.current;if(!visit)return Promise.resolve(true);
 if(visit.retiring)return visit.retiring;
 // A new native document has already invalidated this old visit.
 if(bridge.getDocumentEpoch()!==visit.origin.documentEpoch){holder.current=null;return Promise.resolve(true);}
 visit.retiring=(async()=>{
  try{
   const receipt=await bridge.command<{closed?:boolean;visitId?:string}>(chatDefaultPresetsCommand,{operation:'close',visitId:visit.id});
   if(holder.current!==visit||receipt?.closed!==true||receipt.visitId!==visit.id)return false;
   holder.current=null;return true;
  }catch{return false}
  finally{visit.retiring=undefined}
 })();
 return visit.retiring;
}
function reading(receipt:Receipt,visit:ChatDefaultVisit):Reading{
 const raw=receipt?.defaults as Record<string,unknown>|undefined;
 if(receipt?.visitId!==visit.id||receipt.conversationId!==visit.origin.conversationId||typeof receipt.conversationAllowsDefaults!=='boolean'||
    !raw||typeof raw!=='object'||Object.keys(raw).length!==7||typeof raw.revision!=='number'||!Number.isSafeInteger(raw.revision)||raw.revision<1)
  throw new Error('默认偏好尚未核对');
 for(const field of fields){
  if(isText(field)?!options[field].some(option=>option.id===raw[field]):typeof raw[field]!=='boolean')throw new Error('默认偏好尚未核对');
 }
 return {revision:raw.revision,allows:receipt.conversationAllowsDefaults,values:Object.fromEntries(fields.map(field=>[field,raw[field]])) as Values};
}

export function ChatDefaultPresets({visit,holder,retireError}:{visit:ChatDefaultVisit;holder:ChatDefaultHolder;retireError:string}){
 const mounted=useRef(false),flight=useRef(true),heading=useRef<HTMLDivElement>(null);
 const [baseline,setBaseline]=useState<Reading>(),[draft,setDraft]=useState<Values>(),[section,setSection]=useState<Section>();
 const [busy,setBusy]=useState('正在核对已保存的默认偏好'),[error,setError]=useState(''),[notice,setNotice]=useState('');
 const [needsRead,setNeedsRead]=useState(false);
 const current=()=>mounted.current&&holder.current===visit&&visit.origin.current();
 useEffect(()=>{
  mounted.current=true;let alive=true;heading.current?.focus({preventScroll:true});
  void visit.opened.then(result=>{
   if(alive&&current())setBaseline(reading(result,visit));
  }).catch(()=>{if(alive&&current())setError('暂未读到已保存的默认偏好。');})
   .finally(()=>{if(alive){flight.current=false;setBusy('');}});
  return()=>{alive=false;mounted.current=false;};
 },[visit]);
 const changed:Record<string,string|boolean>={};
 if(section&&draft&&baseline)for(const field of sections[section])if(draft[field]!==baseline.values[field])changed[field]=draft[field];
 const refresh=async()=>{
  if(!current()||flight.current||visit.retiring)return;
  flight.current=true;setBusy('正在核对已保存的默认偏好');setError('');setNotice('');
  try{
   const found=reading(await bridge.command<Receipt>(chatDefaultPresetsCommand,{operation:'read',visitId:visit.id}),visit);
   if(!current())return;
   setBaseline(found);setDraft(section?{...found.values,...changed}:undefined);setNeedsRead(false);
  }catch{if(current())setError('暂未读到已保存的默认偏好。');}
  finally{flight.current=false;if(current())setBusy('');}
 };
 const save=async()=>{
  if(!current()||flight.current||visit.retiring||needsRead||!section||!baseline||!Object.keys(changed).length)return;
  flight.current=true;setBusy('正在核对这次保存');setError('');setNotice('');
  try{
   const result=await bridge.command<Receipt>(chatDefaultPresetsCommand,{operation:'save',visitId:visit.id,section,changes:changed,expectedRevision:baseline.revision});
   if(!current())return;
   const found=reading(result,visit);
   if(result.saved!==true||result.persisted!==true||found.revision!==baseline.revision+1||
      fields.some(field=>found.values[field]!==(changed[field]??baseline.values[field])))throw new Error('默认偏好尚未核对');
   setBaseline(found);setSection(undefined);setDraft(undefined);setNotice('已保存全局默认偏好。本对话的参考开关未改变。');
  }catch{
   if(current()){setNeedsRead(true);setError('保存结果尚未核对。请先读取已保存的设置，不会自动重发。');}
  }finally{flight.current=false;if(current())setBusy('');}
 };
 const available=holder.current===visit&&visit.origin.current();
 const blocked=!available||!!busy||needsRead;
 const scope=baseline?.allows===true?'本对话可在后续请求中参考这些设置。':baseline?.allows===false?
  '本对话已暂停参考。修改默认设置不会解除暂停。':'暂未核对本对话是否允许参考。';
 return <div ref={heading} tabIndex={-1}>
  <p className="pw-muted">这里修改全局默认值，不表示某条回答采用了它们。{available?scope:'原私人对话窗口已变化。'}</p>
  {available&&busy&&<ShimmerText>{busy}…</ShimmerText>}
  {(retireError||error)&&<p className="pw-error" role="alert">{retireError||error}</p>}
  {available&&notice&&<p role="status">{notice}</p>}
  {available&&(!baseline&&!busy||needsRead)&&<Button variant="secondary" disabled={!!busy} onClick={()=>void refresh()}>读取已保存的默认偏好</Button>}
  {available&&baseline&&(!section||!draft?Object.entries(sections).map(([key,list])=>
   <section key={key} className="pw-profile-form" aria-label={names[key as Section]}>
    <p>{list.map(field=>labels[field]+'：'+display(field,baseline.values[field])).join(' · ')}</p>
    <Button variant="secondary" disabled={blocked} onClick={()=>{setDraft(baseline.values);setSection(key as Section);setNotice('');}}>编辑{names[key as Section]}</Button>
   </section>):<section className="pw-profile-form" aria-label={names[section]}>
    {sections[section].map(field=>isText(field)?
     <SelectField key={field} label={labels[field]} value={draft[field]} options={options[field]} isDisabled={blocked}
      onChange={value=>setDraft(prior=>prior?{...prior,[field]:value}:prior)}/>:
     <StretchSwitch key={field} label={labels[field]} checked={draft[field]} disabled={blocked}
      onChange={value=>setDraft(prior=>prior?{...prior,[field]:value}:prior)}/>)}
    <div className="pw-action-row">
     <Button disabled={blocked||!Object.keys(changed).length} onClick={()=>void save()}>保存全局{names[section]}</Button>
     <Button variant="ghost" disabled={!!busy} onClick={()=>{setSection(undefined);setDraft(undefined);}}>取消编辑</Button>
    </div>
   </section>)}
 </div>;
}
