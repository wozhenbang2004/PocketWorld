import {useEffect,useLayoutEffect,useRef,useState} from 'react';
import {AttachmentDocumentGenerationReview,AttachmentWordPdfFields} from './AttachmentDocumentEditor';
import {Button,SelectField,SettingsDialog,TextAreaField,TextField} from './forms-library';
import {fileObject as obj,type FileJson as Json} from './attachment-workspace';
import {registerOverlayBack} from './overlay-back';

type Fields={format:'docx'|'pdf';name:string;title:string;body:string;fontPoints:number};
type Editor={handle:string;version?:string;original:Fields;input:Fields;uncertain?:'first'|'update';sourceName?:string};
type Row={rowHandle:string;name:string;format:Fields['format'];savedAt:number;linkedRevision?:true;justCopiedMarkdown?:true;justCopiedPdfPage?:true};
type OutputChoice={editorHandle:string;versionHandle:string;format:Fields['format']};
type GenerationReview={id:string;editorHandle:string;versionHandle:string;fileName:string;format:Fields['format']};
type RemovalReview={editorHandle:string;versionHandle:string;name:string;format:Fields['format']};
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
 (row.linkedRevision===undefined||row.linkedRevision===true)&&Number.isSafeInteger(row.savedAt)&&row.savedAt>0):[];
const dateOf=(instant:number)=>{
 const time=new Date(instant);
 return Number.isFinite(time.getTime())?time.toLocaleString():undefined;
};

function acknowledgedBlank(value:Json|undefined):Editor|undefined{
 const input=fields(value?.fields),handle=value?.editorHandle;
 if(!input||!isHandle(handle)||value?.versionHandle!=null||input.title!==''||input.body!=='')return;
 return {handle,original:input,input};
}

function acknowledgedResume(value:Json|undefined):Editor|undefined{
 if(value?.returnedSource!==true||value.status!=='VERIFIED'||!isHandle(value.editorHandle)||!isHandle(value.versionHandle))return;
 const input=fields(value.fields),sourceName=value.sourceName;
 if(!input||sourceName!=null&&(typeof sourceName!=='string'||!sourceName.trim()||sourceName.length>160))return;
 return {handle:value.editorHandle,version:value.versionHandle,original:input,input,sourceName:sourceName??undefined};
}

/** This editor owns only the current Web document's input. Native versions are opaque handles. */
export function AttachmentStandaloneDrafts({active,recoverable,loading,initial,initialBlank=false,initialResume=false,invoke,registerExit,onExit}:{
 active:boolean;recoverable:boolean;loading:boolean;initial?:Json;initialBlank?:boolean;initialResume?:boolean;invoke:Invoke;
 registerExit:(handle:((close:boolean)=>void)|null)=>void;onExit:(close:boolean)=>Promise<void>;
}){
 const [catalog,setCatalog]=useState<Json|null>(),[editor,setEditor]=useState<Editor|undefined>(()=>!active?undefined:initialBlank?acknowledgedBlank(initial):initialResume?acknowledgedResume(initial):undefined);
 const [busy,setBusy]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('');
 const [exit,setExit]=useState<{close:boolean}>();
 const [outputChoice,setOutputChoice]=useState<OutputChoice>();
 const [review,setReview]=useState<GenerationReview>(),[ack,setAck]=useState(false),[generation,setGeneration]=useState<'submitting'|'accepted'|'unknown'>();
 const [removalReview,setRemovalReview]=useState<RemovalReview>(),[removal,setRemoval]=useState<'removing'|'changed'|'unknown'>();
 const [unavailable,setUnavailable]=useState<RemovalReview>();
 const flight=useRef(false),mounted=useRef(true),heading=useRef<HTMLHeadingElement>(null),current=useRef(active),focusList=useRef(false);
 current.current=active;
 const dirty=!!editor&&JSON.stringify(editor.input)!==JSON.stringify(editor.original);
 // A reopened saved draft brought native text into the page; never reveal that body after revocation.
 const typed=(!active&&recoverable&&!busy&&editor&&!editor.version&&!editor.uncertain&&editor.original.title===''&&editor.original.body===''&&
  (editor.input.name!==editor.original.name||!!editor.input.title||!!editor.input.body))?editor.input:undefined;
 useEffect(()=>{mounted.current=true;return()=>{mounted.current=false}},[]);
 useLayoutEffect(()=>{
  if(!editor&&focusList.current){focusList.current=false;heading.current?.scrollIntoView({block:'start'});}
  heading.current?.focus({preventScroll:true});
 },[editor?.handle,unavailable?.editorHandle,loading,!!typed,!!removal]);
 const perform=(work:()=>Promise<void>)=>{
  if(flight.current)return;flight.current=true;setBusy(true);setError('');
  void work().catch(()=>{if(mounted.current)setError('本机草稿操作还未核对。请保留当前内容。')})
   .finally(()=>{flight.current=false;if(mounted.current)setBusy(false)});
 };
 const leave=(close:boolean)=>perform(async()=>{
  try{await onExit(close)}catch{if(mounted.current)setError(close?'没有确认关闭原文件库。本页还在。':'还未确认退出当前草稿。本页还在。')}
 });
 const closeGenerationReview=()=>{
  if(!review)return;
  setReview(undefined);setAck(false);
  if(current.current)void invoke('drafts.dismissDocument',{reviewId:review.id}).catch(()=>{});
 };
 useLayoutEffect(()=>{
  registerExit(close=>{
   if(flight.current||loading)return;
   if(review){closeGenerationReview();return}
   if(removalReview){setRemovalReview(undefined);return}
   if(dirty||editor?.uncertain)setExit({close});else leave(close);
  });
  return()=>registerExit(null);
 });
 useEffect(()=>{
  if(exit)return registerOverlayBack(()=>setExit(undefined));
  if(active&&review)return registerOverlayBack(closeGenerationReview);
  if(active&&removalReview)return registerOverlayBack(()=>setRemovalReview(undefined));
 },[exit,active,review,removalReview]);
 useEffect(()=>{if(!active){
  setReview(undefined);setAck(false);setRemovalReview(undefined);setUnavailable(undefined);
  setEditor(value=>value?.sourceName?undefined:value);
 }},[active]);
 const sourceUnavailable=(selection:RemovalReview)=>{
  focusList.current=true;setEditor(undefined);setUnavailable(selection);setGeneration(undefined);setReview(undefined);setAck(false);setNotice('');
 };
 const exactSourceLost=(result:Json,original:Editor)=>{
  if(!original.sourceName||!original.version||result.sourceAvailable!==false||
     result.editorHandle!==original.handle||result.versionHandle!==original.version)return false;
  sourceUnavailable({editorHandle:original.handle,versionHandle:original.version,name:original.original.name,format:original.original.format});
  return true;
 };
 const start=(row?:Row)=>perform(async()=>{
  if(!active)return;
  const result=await invoke(row?'drafts.open':'drafts.new',row?{rowHandle:row.rowHandle}:undefined),input=fields(result.fields);
  if(!mounted.current||!current.current)return;
  if(row?.linkedRevision===true&&result.status==='VERIFIED'&&result.sourceAvailable===false&&isHandle(result.editorHandle)&&isHandle(result.versionHandle)&&!input){
   sourceUnavailable({editorHandle:result.editorHandle,versionHandle:result.versionHandle,name:row.name,format:row.format});return;
  }
  const sourceName=typeof result.sourceName==='string'&&result.sourceName.trim()&&result.sourceName.length<=160?result.sourceName:undefined;
  if(!input||!isHandle(result.editorHandle)||(row&&(result.status!=='VERIFIED'||!isHandle(result.versionHandle)||
     Boolean(row.linkedRevision)!==Boolean(sourceName)||input.name!==row.name||input.format!==row.format))){
   setError('没有核对到所选的本机草稿。请重新读取清单，没有打开同名稿件。');return;
  }
  setNotice(row?'已打开本机保留版':'');
  setEditor({handle:result.editorHandle,version:row?result.versionHandle:undefined,original:input,input,sourceName});
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
   if(exactSourceLost(result,original)){
    if(result.status==='UNKNOWN')setError('这次修改未保留，没有改写已保存版本。');
    return;
   }
   if(!checking&&result.status==='QUOTA'){setError('本机草稿已达到保留上限。本页内容仍在，没有替换原稿。');return}
   if(!checking&&result.status==='NO_EDIT'){setNotice('这份稿子没有改动，无需保留。');return}
   if(result.editorHandle!==original.handle||!isHandle(result.versionHandle)||
      (checking?result.status!=='VERIFIED':result.status!=='SAVED'&&result.status!=='VERIFIED')||
      (!first&&(result.status==='VERIFIED')!==(result.versionHandle===original.version))){hold();return}
   setEditor(value=>value===original?{...original,version:result.versionHandle,original:original.input,input:original.input,uncertain:undefined}:value);
   setNotice(checking?'已核对这份本机草稿':'草稿已保留在本机');
  }catch{hold()}
 });
 const edit=(next:Json)=>{
  if(flight.current||!current.current||editor?.uncertain)return;
  const input=fields(next);
  if(!input){setError('草稿文字超出可保留范围。');return}
  setEditor(value=>value?{...value,input}:value);setNotice('');setError('');
 };
 const shown=catalog===undefined?(initialBlank||initialResume?undefined:initial):catalog??undefined,rows=rowList(shown);
 const blankUnconfirmed=initialBlank&&!editor&&catalog===undefined,resumeUnconfirmed=initialResume&&!editor&&catalog===undefined;
 const retry=()=>perform(async()=>{if(active){const value=await invoke('drafts.list');if(mounted.current&&current.current)setCatalog(value)}});
 const editable=active&&!busy&&!exit&&!review&&!editor?.uncertain&&!generation&&!removalReview&&!removal;
 const canReviewGeneration=!!editor?.version&&!dirty&&!!editor.original.name.trim()&&!!editor.original.body.trim();
 const outputFormat=editor&&outputChoice?.editorHandle===editor.handle&&outputChoice.versionHandle===editor.version?
  outputChoice.format:editor?.original.format;
 const chooseOutputFormat=(format:string)=>{
  if(!editable||flight.current||!canReviewGeneration||!editor?.version||(format!=='docx'&&format!=='pdf'))return;
  setOutputChoice({editorHandle:editor.handle,versionHandle:editor.version,format});setError('');
 };
 const prepareGeneration=()=>perform(async()=>{
  if(!active||review||generation||removalReview||removal||!canReviewGeneration||!outputFormat||!editor?.version||editor.uncertain)return;
  const result=await invoke('drafts.reviewDocument',{editorHandle:editor.handle,versionHandle:editor.version,outputFormat});
  if(!mounted.current||!current.current)return;
  if(exactSourceLost(result,editor))return;
  if(result.status==='invalid'){
   setError(typeof result.reason==='string'?result.reason:'请补全本机草稿后再核对生成。');return;
  }
  if(result.status!=='ready'||!isHandle(result.reviewId)||result.editorHandle!==editor.handle||result.versionHandle!==editor.version||
    result.format!==outputFormat||typeof result.fileName!=='string'||!result.fileName.endsWith(`.${result.format}`)||result.fileName.length>85){
   setError('没有核对到这份已保留的草稿。原稿没有改动。');return;
  }
  setReview({id:result.reviewId,editorHandle:editor.handle,versionHandle:editor.version,fileName:result.fileName,format:result.format});setAck(false);
 });
 const confirmGeneration=()=>{
  if(!active||!ack||!review||generation||removalReview||removal||dirty||!editor||editor.uncertain||editor.handle!==review.editorHandle||editor.version!==review.versionHandle)return;
  const selected=review,original=editor;
  perform(async()=>{
   setReview(undefined);setAck(false);setGeneration('submitting');setNotice('');
   try{
    const result=await invoke('drafts.createDocument',{reviewId:selected.id,editorHandle:selected.editorHandle,versionHandle:selected.versionHandle,acknowledge:true});
    if(!mounted.current)return;
    if(current.current&&exactSourceLost(result,original))return;
    if(result.started===false){
     setGeneration(undefined);
     setError(typeof result.reason==='string'?result.reason:'此次没有开始生成。原草稿不变。');
    }else if(result.started!==true)throw new Error('未收到原生成动作回执');
    else setGeneration('accepted');
   }catch{
    if(mounted.current){setGeneration('unknown');setError('请返回成果核对原生成记录。没有改写草稿，也不会自动重试。')}
   }
  });
 };
 const removalTarget=unavailable??(editor?.version&&!dirty&&!editor.uncertain?{
  editorHandle:editor.handle,versionHandle:editor.version,name:editor.original.name,format:editor.original.format}:undefined);
 const reviewRemoval=()=>{
  if(!editable||!removalTarget||flight.current)return;
  setRemovalReview(removalTarget);
 };
 const readListAfterRemoval=async(message:string)=>{
  // Old catalogue handles were retired natively. Don't momentarily show the deleted row.
  setCatalog(null);focusList.current=true;setEditor(undefined);setUnavailable(undefined);setRemoval(undefined);setNotice(message);
  try{const next=await invoke('drafts.list');if(mounted.current&&current.current)setCatalog(next)}
  catch{if(mounted.current&&current.current)setError('当前草稿清单尚未核对，可以重新读取。')}
 };
 const remove=()=>{
  const selected=removalReview;
  if(!active||!selected||dirty||review||generation||removal||!removalTarget||
     removalTarget.editorHandle!==selected.editorHandle||removalTarget.versionHandle!==selected.versionHandle)return;
  perform(async()=>{
   setRemovalReview(undefined);focusList.current=true;setEditor(undefined);setUnavailable(undefined);setRemoval('removing');setNotice('');
   let receipt:Json|undefined;
   try{receipt=await invoke('drafts.remove',{editorHandle:selected.editorHandle,versionHandle:selected.versionHandle,acknowledge:true})}catch{}
   if(!mounted.current||!current.current)return;
   if(receipt?.editorHandle===selected.editorHandle&&receipt.versionHandle===selected.versionHandle){
    if(receipt.status==='REMOVED'){
     await readListAfterRemoval('本机草稿已删除。已生成的文件不变。');return;
    }
    if(receipt.status==='MISSING'||receipt.status==='CONFLICT'){
     setRemoval('changed');setError('原草稿版本已变化，没有删除其他草稿。');return;
    }
   }
   setRemoval('unknown');setError('删除结果尚未核对，可查看当前草稿清单。没有自动重试。');
  });
 };
 const returnAfterUnconfirmedRemoval=()=>perform(async()=>{
  if(!active||removal!=='changed'&&removal!=='unknown')return;
  if((await invoke('drafts.leave')).left!==true||!mounted.current||!current.current)throw new Error('原草稿窗口已变化');
  await readListAfterRemoval(removal==='unknown'?'本次删除没有收到确认；这里只显示当前可核对的草稿。':'请在当前清单核对原草稿。');
 });
 return <section className="pw-document-editor" aria-label="本机文档草稿">
  <h3 ref={heading} tabIndex={-1}>{typed?'本页还在的输入':active&&removal?'删除本机草稿':active&&unavailable?'原文件暂不能核对':active&&editor?'编辑本机草稿':blankUnconfirmed?'新建文档':resumeUnconfirmed?'回到原草稿':'已保留的草稿'}</h3>
  {active&&<p className="pw-muted">{editor?.sourceName?<>修改草稿源自固定文件「{editor.sourceName}」，原件不变。</>:'只保存在本机。草稿不是已生成的文件。'}</p>}
  {active&&!editor&&!unavailable&&!removal&&notice&&<p role="status">{notice}</p>}
  {error&&<p className="pw-error" role="alert">{error}</p>}
  {!active?typed?<><p role="status">这些文字仍在本页，可以选中复制。原编辑窗口已关闭，不能从这里保存。</p>
    {typed.name!==editor?.original.name&&<TextField label="文件名" value={typed.name} isReadOnly/>}
    {typed.title!==''&&<TextField label="标题" value={typed.title} isReadOnly/>}
    {typed.body!==''&&<TextAreaField className="pw-document-body" label="正文" value={typed.body} isReadOnly/>}
   </>:<p role="status">原草稿窗口已关闭。重新打开「成果」后，可以查看已保留的稿件。</p>:
   removal?<div className="pw-action-row">
    {removal==='removing'?<p role="status">正在核对删除结果</p>:
     <Button variant="secondary" disabled={busy} onClick={returnAfterUnconfirmedRemoval}>返回草稿清单核对</Button>}
   </div>:
   unavailable?<><p><strong>{unavailable.name.trim()||'未命名草稿'}</strong></p>
    <p role="status">原文件或可编辑源暂不能核对。保留了草稿记录，不显示正文或生成替代文件。</p>
    <Button variant="ghost" disabled={!editable} onClick={reviewRemoval}>删除草稿</Button></>:
   editor?<><TextField label="文件名" value={editor.input.name} isDisabled={!editable} onChange={name=>edit({...editor.input,name})}/>
    <AttachmentWordPdfFields draft={editor.input} editable={editable} edit={edit}
     text={(field,value,limit)=>{if(value.length<=limit)edit({...editor.input,[field]:value});else setError(`此项最多 ${limit} 字，未改变输入。`)}} draftOnly/>
    {canReviewGeneration&&!editor.uncertain&&!generation&&outputFormat&&
     <SelectField label="本次生成格式" value={outputFormat} options={[{id:'docx',label:'Word'},{id:'pdf',label:'PDF'}]}
      onChange={chooseOutputFormat} isDisabled={!editable} compactValue/>}
    <footer className="pw-document-footer"><span role="status">{generation==='unknown'?'生成是否受理尚未核对':generation==='accepted'?'已受理生成，等待新文件回执':generation?'正在核对已保留版本':busy?'正在核对本机草稿':notice||
     (editor.uncertain==='update'?'请重新打开这份原稿核对本机版本。':dirty?'本页修改尚未保留':editor.version?'已打开本机保留版':'还没有保留本页')}</span>
     {!generation&&(editor.uncertain==='first'?<Button variant="secondary" disabled={busy} onClick={()=>save(true)}>核对本次草稿</Button>:
      !editor.uncertain&&(canReviewGeneration?<Button variant="primary" disabled={!editable} onClick={prepareGeneration}>核对并生成{outputFormat==='pdf'?' PDF':' Word'}</Button>:
      <Button variant="primary" disabled={!editable||!dirty} onClick={()=>save()}>保留草稿</Button>))}
     {removalTarget&&!generation&&!removal&&<Button variant="ghost" disabled={!editable} onClick={reviewRemoval}>删除草稿</Button>}
    </footer></>:
   blankUnconfirmed?<p role="status">没有核对到空白草稿，未保存任何内容。请返回文件库后再试。</p>:
   resumeUnconfirmed?<p role="status">尚未确认打开生成前的原草稿。没有打开其他版本。返回成果后可查看本机文档草稿。</p>:
   loading||busy&&catalog===null?<p role="status">正在读取本机草稿清单</p>:<>{shown?.metadataComplete===false&&<p role="status">草稿清单尚未核对齐全，可以打开已经列出的原稿。</p>}
    {shown?.metadataComplete===true&&!rows.length&&<p>还没有已保留的草稿。</p>}
    {!shown&&<p role="status">草稿清单尚未核对。</p>}
    <div className="pw-action-row">
     {shown&&<Button variant="secondary" disabled={busy} onClick={()=>start()}>新建草稿</Button>}
     {(shown?.metadataComplete!==true||error)&&<Button variant="ghost" disabled={busy} onClick={retry}>核对草稿清单</Button>}
    </div>
    {rows.map((row,index)=>{
     const name=row.name.trim()||'未命名草稿',time=dateOf(row.savedAt),details=[row.format==='pdf'?'PDF':'Word',
      row.linkedRevision?'已有文件的修改草稿':row.justCopiedMarkdown===true?'刚从原文保留':row.justCopiedPdfPage===true?'刚从 PDF 原页保留':'',time].filter(Boolean).join(' · ');
     const duplicate=rows.filter(other=>(other.name.trim()||'未命名草稿')===name&&other.format===row.format&&dateOf(other.savedAt)===time).length>1;
     const caption=duplicate?`${details} · 第 ${index+1} 项`:details;
     return <div className="pw-file-action-record" key={row.rowHandle}><div><strong>{name}</strong><small>{caption}</small></div>
      <Button variant="ghost" disabled={busy} aria-label={`继续编辑 ${name} · ${caption}`} onClick={()=>start(row)}>继续编辑</Button></div>;
    })}</>}
  <AttachmentDocumentGenerationReview open={active&&!!review} format={review?.format||''} name={review?.fileName}
   ack={ack} busy={busy} retainedDraft onAck={setAck} onClose={closeGenerationReview} onConfirm={confirmGeneration}/>
  <SettingsDialog isOpen={active&&!!removalReview} title="删除这份本机草稿？" closeLabel="取消删除" onClose={()=>setRemovalReview(undefined)}>
   {removalReview&&<><p><strong>{removalReview.name.trim()||'未命名草稿'}</strong> · {removalReview.format==='pdf'?'PDF':'Word'}</p>
    <p>删除后不能继续编辑这份草稿。已经生成的文件和另存的副本不受影响。</p>
    <div className="pw-action-row"><Button variant="secondary" onClick={()=>setRemovalReview(undefined)}>保留草稿</Button>
     <Button variant="danger" disabled={busy} onClick={remove}>删除此草稿</Button></div></>}
  </SettingsDialog>
  <SettingsDialog isOpen={!!exit} title="离开本机草稿？" closeLabel="留在这里" onClose={()=>setExit(undefined)}>
   <p>本页还有未确认保留的修改。离开不会删除已保留的版本，也不会发起新的保存。已经提交但没有回执的保存，请回本机草稿核对。</p>
   <div className="pw-action-row"><Button variant="primary" onClick={()=>setExit(undefined)}>留在这里</Button>
    <Button variant="danger" onClick={()=>{if(exit){const close=exit.close;setExit(undefined);leave(close)}}}>离开本页</Button></div>
  </SettingsDialog>
 </section>;
}
