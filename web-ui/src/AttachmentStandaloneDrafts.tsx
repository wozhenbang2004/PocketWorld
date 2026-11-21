import {useEffect,useLayoutEffect,useRef,useState} from 'react';
import {AttachmentWordPdfFields} from './AttachmentDocumentEditor';
import {Button,SettingsDialog,TextAreaField,TextField} from './forms-library';
import {fileObject as obj,type FileJson as Json} from './attachment-workspace';
import {registerOverlayBack} from './overlay-back';

type Fields={format:'docx'|'pdf';name:string;title:string;body:string;fontPoints:number};
type Editor={handle:string;version?:string;original:Fields;input:Fields;uncertain?:'first'|'update'};
type Row={rowHandle:string;name:string;format:Fields['format'];savedAt:number};
type Invoke=(command:string,args?:Json)=>Promise<Json>;
const isHandle=(value:unknown):value is string=>typeof value==='string'&&/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/.test(value);
const fields=(value:unknown):Fields|undefined=>{
 const row=obj(value);
 if((row.format!=='docx'&&row.format!=='pdf')||typeof row.name!=='string'||row.name.length>80||
    typeof row.title!=='string'||row.title.length>200||typeof row.body!=='string'||row.body.length>16000||
    ![10,11,12,14,16].includes(row.fontPoints))return;
 return {format:row.format,name:row.name,title:row.title,body:row.body,fontPoints:row.fontPoints};
};
const rowList=(reply:Json|undefined):Row[]=>Array.isArray(reply?.rows)&&reply.rows.length<=8?reply.rows.filter((row:Json)=>
 isHandle(row?.rowHandle)&&typeof row.name==='string'&&row.name.length<=80&&(row.format==='docx'||row.format==='pdf')&&
 Number.isSafeInteger(row.savedAt)&&row.savedAt>0):[];
const dateOf=(instant:number)=>{
 const time=new Date(instant);
 return Number.isFinite(time.getTime())?time.toLocaleString():undefined;
};

/** This editor owns only the current Web document's input. Native versions are opaque handles. */
export function AttachmentStandaloneDrafts({active,recoverable,loading,initial,invoke,registerExit,onExit}:{
 active:boolean;recoverable:boolean;loading:boolean;initial?:Json;invoke:Invoke;
 registerExit:(handle:((close:boolean)=>void)|null)=>void;onExit:(close:boolean)=>Promise<void>;
}){
 const [catalog,setCatalog]=useState<Json>(),[editor,setEditor]=useState<Editor>(),[busy,setBusy]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('');
 const [exit,setExit]=useState<{close:boolean}>();
 const flight=useRef(false),mounted=useRef(true),heading=useRef<HTMLHeadingElement>(null),current=useRef(active);
 current.current=active;
 const dirty=!!editor&&JSON.stringify(editor.input)!==JSON.stringify(editor.original);
 // A reopened saved draft brought native text into the page; never reveal that body after revocation.
 const typed=(!active&&recoverable&&!busy&&editor&&!editor.version&&!editor.uncertain&&editor.original.title===''&&editor.original.body===''&&
  (editor.input.name!==editor.original.name||!!editor.input.title||!!editor.input.body))?editor.input:undefined;
 useEffect(()=>{mounted.current=true;return()=>{mounted.current=false}},[]);
 useLayoutEffect(()=>{heading.current?.focus({preventScroll:true})},[editor?.handle,loading,!!typed]);
 const perform=(work:()=>Promise<void>)=>{
  if(flight.current)return;flight.current=true;setBusy(true);setError('');
  void work().catch(()=>{if(mounted.current)setError('本机草稿操作还未核对。请保留当前内容。')})
   .finally(()=>{flight.current=false;if(mounted.current)setBusy(false)});
 };
 const leave=(close:boolean)=>perform(async()=>{
  try{await onExit(close)}catch{if(mounted.current)setError(close?'没有确认关闭原文件库。本页还在。':'还未确认退出当前草稿。本页还在。')}
 });
 useLayoutEffect(()=>{
  registerExit(close=>{
   if(flight.current||loading)return;
   if(dirty||editor?.uncertain)setExit({close});else leave(close);
  });
  return()=>registerExit(null);
 });
 useEffect(()=>{if(exit)return registerOverlayBack(()=>setExit(undefined));},[exit]);
 const start=(row?:Row)=>perform(async()=>{
  if(!active)return;
  const result=await invoke(row?'drafts.open':'drafts.new',row?{rowHandle:row.rowHandle}:undefined),input=fields(result.fields);
  if(!mounted.current||!current.current)return;
  if(!input||!isHandle(result.editorHandle)||(row&&(result.status!=='VERIFIED'||!isHandle(result.versionHandle)))){
   setError('没有核对到所选的本机草稿。请重新读取清单，没有打开同名稿件。');return;
  }
  setNotice(row?'已打开本机保留版':'');
  setEditor({handle:result.editorHandle,version:row?result.versionHandle:undefined,original:input,input});
 });
 const save=(checking=false)=>perform(async()=>{
  if(!active||!editor||(!checking&&(!dirty||editor.uncertain))||(checking&&editor.uncertain!=='first'))return;
  const original=editor,first=!original.version;
  const hold=()=>{if(mounted.current){
   setEditor(value=>value===original?{...value,uncertain:first?'first':'update'}:value);
   setNotice(first?'这次保留结果尚未核对，没有重复写入。':'保存结果尚未核对。请重新打开原稿核对本机版本。');
  }};
  setNotice('');
  try{
   const result=await invoke(checking?'drafts.checkFirst':first?'drafts.saveFirst':'drafts.save',
    {editorHandle:original.handle,...(first?{}:{versionHandle:original.version}),...(checking?{}:{fields:original.input})});
   if(!mounted.current)return;
   if(!current.current){hold();return}
   if(!checking&&result.status==='QUOTA'){setError('本机草稿已达到保留上限。本页内容仍在，没有替换原稿。');return}
   if(!checking&&result.status==='NO_EDIT'){setNotice('这份稿子没有改动，无需保留。');return}
   if(result.editorHandle!==original.handle||!isHandle(result.versionHandle)||
      (checking?result.status!=='VERIFIED':result.status!=='SAVED'&&result.status!=='VERIFIED')||
      (!first&&(result.status==='VERIFIED')!==(result.versionHandle===original.version))){hold();return}
   setEditor(value=>value===original?{handle:original.handle,version:result.versionHandle,original:original.input,input:original.input}:value);
   setNotice(checking?'已核对这份本机草稿':'草稿已保留在本机');
  }catch{hold()}
 });
 const edit=(next:Json)=>{
  if(flight.current||!current.current||editor?.uncertain)return;
  const input=fields(next);
  if(!input){setError('草稿文字超出可保留范围。');return}
  setEditor(value=>value?{...value,input}:value);setNotice('');setError('');
 };
 const shown=catalog||initial,rows=rowList(shown);
 const retry=()=>perform(async()=>{if(active){const value=await invoke('drafts.list');if(mounted.current&&current.current)setCatalog(value)}});
 const editable=active&&!busy&&!exit&&!editor?.uncertain;
 return <section className="pw-document-editor" aria-label="本机文档草稿">
  <h3 ref={heading} tabIndex={-1}>{typed?'本页还在的输入':active&&editor?'编辑本机草稿':'已保留的草稿'}</h3>
  {active&&<p className="pw-muted">只保存在本机。草稿不是已生成的文件。</p>}
  {error&&<p className="pw-error" role="alert">{error}</p>}
  {!active?typed?<><p role="status">这些文字仍在本页，可以选中复制。原编辑窗口已关闭，不能从这里保存。</p>
    {typed.name!==editor?.original.name&&<TextField label="文件名" value={typed.name} isReadOnly/>}
    {typed.title!==''&&<TextField label="标题" value={typed.title} isReadOnly/>}
    {typed.body!==''&&<TextAreaField className="pw-document-body" label="正文" value={typed.body} isReadOnly/>}
   </>:<p role="status">原草稿窗口已关闭。重新打开「成果」后，可以查看已保留的稿件。</p>:
   editor?<><TextField label="文件名" value={editor.input.name} isDisabled={!editable} onChange={name=>edit({...editor.input,name})}/>
    <AttachmentWordPdfFields draft={editor.input} editable={editable} edit={edit}
     text={(field,value,limit)=>{if(value.length<=limit)edit({...editor.input,[field]:value});else setError(`此项最多 ${limit} 字，未改变输入。`)}} draftOnly/>
    <footer className="pw-document-footer"><span role="status">{busy?'正在核对本机草稿':notice||
     (editor.uncertain==='update'?'请重新打开这份原稿核对本机版本。':dirty?'本页修改尚未保留':editor.version?'已打开本机保留版':'还没有保留本页')}</span>
     {editor.uncertain==='first'?<Button variant="secondary" disabled={busy} onClick={()=>save(true)}>核对本次草稿</Button>:
      !editor.uncertain&&<Button variant="primary" disabled={busy||!dirty} onClick={()=>save()}>保留草稿</Button>}
    </footer></>:
   loading?<p role="status">正在读取本机草稿清单</p>:<>{shown?.metadataComplete===false&&<p role="status">草稿清单尚未核对齐全，可以打开已经列出的原稿。</p>}
    {shown?.metadataComplete===true&&!rows.length&&<p>还没有已保留的草稿。</p>}
    {!shown&&<p role="status">草稿清单尚未核对。</p>}
    <div className="pw-action-row">
     {shown&&<Button variant="secondary" disabled={busy} onClick={()=>start()}>新建草稿</Button>}
     {(shown?.metadataComplete!==true||error)&&<Button variant="ghost" disabled={busy} onClick={retry}>核对草稿清单</Button>}
    </div>
    {rows.map((row,index)=>{
     const name=row.name.trim()||'未命名草稿',time=dateOf(row.savedAt),details=[row.format==='pdf'?'PDF':'Word',time].filter(Boolean).join(' · ');
     const duplicate=rows.filter(other=>(other.name.trim()||'未命名草稿')===name&&other.format===row.format&&dateOf(other.savedAt)===time).length>1;
     const caption=duplicate?`${details} · 第 ${index+1} 项`:details;
     return <div className="pw-file-action-record" key={row.rowHandle}><div><strong>{name}</strong><small>{caption}</small></div>
      <Button variant="ghost" disabled={busy} aria-label={`继续编辑 ${name} · ${caption}`} onClick={()=>start(row)}>继续编辑</Button></div>;
    })}</>}
  <SettingsDialog isOpen={!!exit} title="离开本机草稿？" closeLabel="留在这里" onClose={()=>setExit(undefined)}>
   <p>本页还有未确认保留的修改。离开不会删除已保留的版本，也不会发起新的保存。已经提交但没有回执的保存，请回本机草稿核对。</p>
   <div className="pw-action-row"><Button variant="primary" onClick={()=>setExit(undefined)}>留在这里</Button>
    <Button variant="danger" onClick={()=>{if(exit){const close=exit.close;setExit(undefined);leave(close)}}}>离开本页</Button></div>
  </SettingsDialog>
 </section>;
}
