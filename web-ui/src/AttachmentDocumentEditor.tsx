import {useEffect,useRef,useState} from 'react';
import {bridge} from './bridge';
import {registerOverlayBack} from './overlay-back';
import {Accordion,AccordionItem,Button,CheckboxField,SelectField,SettingsDialog,TextAreaField,TextField} from './forms-library';
import {attachmentFrame,editSlideText,fileRows as rows,processingDefaults,type AttachmentAction,type AttachmentFrame,type FileJson as Json} from './attachment-workspace';
import {FileDetails} from './AttachmentOriginalReader';

const validDraft=(draft:Json)=>!!String(draft.name||'').trim()&&String(draft.name).length<=80&&new TextEncoder().encode(JSON.stringify(draft)).length<=256*1024&&
 (draft.format==='pptx'?!!draft.parentArtifactId&&Array.isArray(draft.presentation?.slides):['docx','pdf'].includes(draft.format)&&typeof draft.body==='string'&&draft.body.length>0&&draft.body.length<=16000);

/** Adapted from the document editor's serialized draft/CAS interaction. Only
 * the selected task and immutable file version receive this text. */
export type SavedFileNote={taskId:string;fileId:string;sha256:string;revision:number;text:string};
export function AttachmentVersionContinuation({original,foregroundReady,busy,act,beforeBack,onReturn,onAdopt}:{original:Json;foregroundReady:boolean;busy:boolean;act:AttachmentAction;beforeBack:(handler:(()=>Promise<AttachmentFrame|undefined>)|null)=>void;onReturn?:(frame:AttachmentFrame)=>Promise<void>;onAdopt?:(note:SavedFileNote)=>Promise<void>}){
 const [text,setText]=useState(String(original.text||'')),[error,setError]=useState(''),[saving,setSaving]=useState(false),[applying,setApplying]=useState(false),[saved,setSaved]=useState(original.saved===true);
 const [checked,setChecked]=useState(false),[competing,setCompeting]=useState<Json|null>(null);
 const current=useRef(text),baseline=useRef(original),alive=useRef(true),paused=useRef(false),flight=useRef<Promise<AttachmentFrame|undefined>|null>(null),action=useRef(act),ready=useRef(foregroundReady);action.current=act;ready.current=foregroundReady;
 const finishFlight=useRef(false);
 const accepts=(value:Json)=>value.taskId===original.taskId&&value.fileId===original.fileId&&value.sha256===original.sha256&&Number.isSafeInteger(value.revision)&&value.revision>0&&typeof value.text==='string'&&value.text.length<=2000;
 const dirty=()=>current.current!==baseline.current.text||baseline.current.saved!==true;
 async function flush(explicit=false):Promise<AttachmentFrame|undefined>{
  if(flight.current)return flight.current;
  if(!dirty())return;
  if(!ready.current||paused.current&&!explicit)throw new Error('原版本草稿尚未确认保存，请保留本页文字并重新核对。');
  paused.current=false;
  const operation=(async()=>{setSaving(true);let after:AttachmentFrame|undefined;try{
   while(alive.current&&dirty()){
    const next=current.current,revision=baseline.current.revision;
    const result=await action.current('editContinuationDraft',{change:next,draftRevision:revision},after),draft=result.continuationDraft;
    if(!draft||!accepts(draft)||draft.text!==next||draft.saved!==true)throw new Error('原成果版本的草稿保存回执不一致，请保留输入。');
    baseline.current=draft;after=attachmentFrame(result.workspace);
    if(!after)throw new Error('原文件窗口已变化，请重新核对这份保留草稿。');
    if(alive.current){setSaved(!dirty());setError('')}
   }
   return after;
  }catch(failure){
   paused.current=true;if(alive.current){setSaved(false);setError(failure instanceof Error?failure.message:'原版本补充草稿尚未保存。')}
   try{
    const result=await action.current('readContinuationDraft',{},after),actual=result.continuationDraft,frame=attachmentFrame(result.workspace);
    if(alive.current&&actual?.saved===true&&accepts(actual)&&frame){
     if(actual.text===current.current){baseline.current=actual;paused.current=false;setCompeting(null);setSaved(true);setError('');return frame}
     // Do not move the original CAS base past a different save. This keeps
     // both the unsaved input and the already saved version intact.
     if(actual.revision>baseline.current.revision)setCompeting(actual);
    }
   }catch{}
   throw failure;
  }finally{flight.current=null;if(alive.current)setSaving(false)}})();
  flight.current=operation;return operation;
 }
 useEffect(()=>{alive.current=true;beforeBack(()=>flush());return()=>{alive.current=false;beforeBack(null)}},[]);
 useEffect(()=>{
  if(!accepts(original)||original.revision<baseline.current.revision)return;
  const changed=current.current!==baseline.current.text;
  if(changed&&original.text!==current.current&&original.revision>baseline.current.revision){
   if(!flight.current&&original.saved===true){paused.current=true;setCompeting(original);setSaved(false);setChecked(false)}
   return;
  }
  if(original.text===current.current&&original.saved===true){paused.current=false;setCompeting(null)}
  baseline.current=original;if(!changed){current.current=original.text;setText(original.text)}setSaved(!dirty());
 },[original.revision,original.text,original.saved]);
 const edit=(next:string)=>{current.current=next;setText(next);setSaved(false);setChecked(false);if(!paused.current)void flush().catch(()=>{})};
 const finish=async()=>{
  if(busy||finishFlight.current||competing||!ready.current||!current.current.trim())return;finishFlight.current=true;setApplying(true);setError('');setChecked(false);
  try{
   const after=await flush(true),desired=current.current,revision=baseline.current.revision;
   const result=await action.current('readContinuationDraft',{},after),draft=result.continuationDraft,frame=attachmentFrame(result.workspace);
   if(!alive.current||!ready.current)return;
   if(!draft||!accepts(draft)||draft.revision!==revision||draft.text!==desired||current.current!==desired||draft.saved!==true||!frame)
    throw new Error('本机补充已有变化，没有覆盖其他草稿。请重新打开这份原件核对。');
   setChecked(true);
   if(onReturn)await onReturn(frame);
  }
  catch(failure){if(alive.current)setError(failure instanceof Error?failure.message:'没有核对到本机保存结果，未离开这份成果。')}
  finally{finishFlight.current=false;if(alive.current)setApplying(false)}
 };
 // Only the already projected saved note may be adopted. Do not flush or read again.
 const canAdopt=!!onAdopt&&foregroundReady&&saved&&!competing&&!paused.current&&!dirty()&&original.saved===true&&original.revision>1&&
  original.revision===baseline.current.revision&&original.text===text&&typeof text==='string'&&!!text.trim();
 const adopt=async()=>{
  if(!onAdopt||!canAdopt||busy||finishFlight.current||flight.current||paused.current||!ready.current||dirty()||current.current!==original.text)return;
  finishFlight.current=true;setApplying(true);setError('');
  try{await onAdopt({taskId:original.taskId,fileId:original.fileId,sha256:original.sha256,revision:original.revision,text:original.text})}
  catch(failure){if(alive.current)setError(failure instanceof Error?failure.message:'没有确认放入原话题草稿，原备注仍保留。')}
  finally{finishFlight.current=false;if(alive.current)setApplying(false)}
 };
 if(original.available!==true||!accepts(original))return <p className="pw-error" role="alert">{original.error||'这个原版本的补充草稿暂不可读取。'}</p>;
 return <section className="pw-file-version-continuation" aria-label="这份成果的补充草稿">
  <h3>成果补充草稿</h3><p className="pw-muted">只保存在此文件的固定版本旁，不发送给管家，也不会停止或启动任务。</p>
  <TextAreaField label="给原工作的补充" value={text} onChange={edit} placeholder="基于这份成果，希望接下来调整什么？" rows={3} autoResize maxRows={7} maxLength={2000} isDisabled={!foregroundReady||applying}/>
  {error&&<p className="pw-error" role="alert">{error}</p>}
  {competing&&<Accordion variant="divided" className="pw-compact-disclosures"><AccordionItem id="original-save-conflict" title="这份补充已有另一版" description="没有覆盖本机保存版；本页文字仍可复制">
   <p className="pw-reading-copy">{competing.text||'（本机保存版没有文字）'}</p>
   <Button variant="ghost" disabled={!foregroundReady||busy||saving||applying} onClick={()=>{baseline.current=competing;current.current=competing.text;setText(competing.text);paused.current=false;setCompeting(null);setSaved(true);setError('')}}>放弃本页改动，使用本机这版</Button>
  </AccordionItem></Accordion>}
  <div className="pw-work-supplement-footer"><span role="status">{applying?'正在核对本机草稿':saving?'正在保存原版本草稿':checked?'已核对原版本的本机草稿':saved?(text?'原版本草稿已保存':'这个版本还没有补充'):'本页文字尚未确认保存'}</span><div className="pw-action-row">
   <Button variant="secondary" disabled={!foregroundReady||busy||saving||applying||!!competing||!text.trim()} onClick={()=>void finish()}>{onReturn?'保存并回原工作':saved?'核对本机草稿':'保存补充草稿'}</Button>
   {canAdopt&&<Button variant="ghost" disabled={busy||saving||applying} aria-description="只带入这段备注，不附加文件，也不发送" onClick={()=>void adopt()}>放入原话题草稿</Button>}
  </div></div>
 </section>;
}

export function AttachmentWordPdfFields({draft,editable,edit,text,draftOnly=false}:{draft:Json;editable:boolean;edit:(next:Json)=>void;text:(field:string,value:string,limit:number)=>void;draftOnly?:boolean}){
 return <><TextField label="标题（可选）" value={draft.title||''} onChange={value=>text('title',value,200)} isDisabled={!editable}/><Accordion className="pw-document-secondary"><AccordionItem id="document-format" title="文件格式与排版" description={`${draft.format?.toUpperCase()} · ${draft.fontPoints||12} pt` }><div className="pw-file-form-grid"><SelectField label="文件格式" value={draft.format} options={[{id:'docx',label:'Word · 可编辑'},{id:'pdf',label:'PDF · 固定页面'}]} onChange={format=>edit({...draft,format})} isDisabled={!editable}/><SelectField label="字号" value={String(draft.fontPoints||12)} options={[10,11,12,14,16].map(n=>({id:String(n),label:`${n} pt`}))} onChange={font=>edit({...draft,fontPoints:Number(font)})} isDisabled={!editable}/></div></AccordionItem></Accordion><TextAreaField className="pw-document-body" label={`正文 · ${String(draft.body||'').length}/16000`} value={draft.body||''} onChange={value=>text('body',value,16000)} isDisabled={!editable}/>{!draftOnly&&<p className="pw-muted">用 # 标记标题、空行分段、制表符分隔简单表格。生成后可直接查看原件。</p>}</>;
}

export function AttachmentDocumentGenerationReview({open,format,name,ack,busy,retainedDraft=false,onAck,onClose,onConfirm}:{
 open:boolean;format:string;name?:string;ack:boolean;busy:boolean;retainedDraft?:boolean;
 onAck:(value:boolean)=>void;onClose:()=>void;onConfirm:()=>void;
}){
 return <SettingsDialog isOpen={open} title={`生成新的 ${format.toUpperCase()} 文件`} closeLabel={retainedDraft?'继续编辑':undefined} onClose={onClose}>
  <p>{retainedDraft?'将用已保留版本生成一个新原件，原草稿不变。生成后可查看或另存，不会自动分享。':
    '将保存当前可编辑内容和一个新的独立原件。生成完成后可查看排版，再选择导出或分享。'}</p>
  <p><strong>{name}</strong></p><CheckboxField isSelected={ack} onChange={onAck}>确认生成这个新版本</CheckboxField>
  <div className="pw-action-row"><Button variant="secondary" onClick={onClose}>继续编辑</Button>
   <Button variant="primary" disabled={!ack||busy} onClick={onConfirm}>生成新文件</Button></div>
 </SettingsDialog>;
}

export function AttachmentDocumentEditor({original,baseVersion,unpublished,windowId,foregroundReady,pendingTask,busy,act,beforeBack,retainLinkedDraft=false}:{original:Json;baseVersion?:string;unpublished:boolean;windowId:string;foregroundReady:boolean;pendingTask?:string;busy:boolean;act:AttachmentAction;beforeBack:(handler:(()=>Promise<AttachmentFrame|undefined>)|null)=>void;retainLinkedDraft?:boolean}) {
 const [draft,setDraft]=useState<Json>(()=>structuredClone(original)),[slide,setSlide]=useState('0'),[error,setError]=useState(''),[saving,setSaving]=useState(false),[saved,setSaved]=useState(false),[review,setReview]=useState<Json|null>(null),[ack,setAck]=useState(false),[discardOpen,setDiscardOpen]=useState(false);
 const current=useRef(draft),committed=useRef(JSON.stringify(original)),savingPromise=useRef<Promise<AttachmentFrame|undefined>|null>(null),alive=useRef(true),revision=useRef(0),action=useRef(act);current.current=draft;action.current=act;
 const context=useRef({windowId,foregroundReady,generation:0});
 if(context.current.windowId!==windowId||context.current.foregroundReady!==foregroundReady)context.current={windowId,foregroundReady,generation:context.current.generation+1};
 const [leaveOpen,setLeaveOpen]=useState(false);
 const [retention,setRetention]=useState<'checking'|'unknown'|'verified'>();
 const retentionFlight=useRef(false);
 const leaving=useRef<{finish:(frame:AttachmentFrame)=>void;stay:()=>void;generation:number;text:string}|null>(null);
 const retainedReceipt=useRef<Json|null>(null);
 const presentation=draft.format==='pptx',supported=['docx','pdf'].includes(draft.format)||presentation&&!!draft.parentArtifactId,slides=rows(draft.presentation?.slides),selected=slides[Number(slide)];
 async function flush():Promise<AttachmentFrame|undefined> {
  if(savingPromise.current)return savingPromise.current;
  if(!context.current.foregroundReady)throw new Error('回到当前文件窗口后可保存保留的输入');
  if(JSON.stringify(current.current)===committed.current)return;
  if(!validDraft(current.current)){
   const message='当前修改还不完整，尚未保存。请补全文件名和正文后返回；输入仍保留在这里。';
   setError(message);throw new Error(message);
  }
  const generation=context.current.generation;
  const operation=(async()=>{setSaving(true);let after:AttachmentFrame|undefined;try{
   while(alive.current&&JSON.stringify(current.current)!==committed.current){
    if(!validDraft(current.current))throw new Error('当前修改还不完整，尚未保存。请补全文件名和正文后返回；输入仍保留在这里。');
    if(!context.current.foregroundReady||context.current.generation!==generation)throw new Error('原文件窗口已变化，当前输入保留，请重新保存');
    const next=structuredClone(current.current),encoded=JSON.stringify(next),result=await action.current('updateDocument',{draft:next},after);
    if(!alive.current||context.current.generation!==generation)throw new Error('原文件窗口已变化，当前输入保留，请重新保存');
    after=attachmentFrame(result.workspace);if(!after||after.windowId!==context.current.windowId||JSON.stringify(result.workspace.documentDraft)!==encoded)
     throw new Error('原编辑页未确认保留这些内容，请保留当前输入。');
    retainedReceipt.current=result.workspace;
    committed.current=encoded;setSaved(JSON.stringify(current.current)===encoded);setError('');
   }
   return after;
  }catch(cause){if(alive.current)setError(cause instanceof Error?cause.message:'草稿尚未保存');throw cause}finally{savingPromise.current=null;if(alive.current)setSaving(false)}})();
  savingPromise.current=operation;return operation;
 }
 const leaveEditor=async():Promise<AttachmentFrame|undefined>=>{
  if(retentionFlight.current)throw new Error('请等待这次草稿核对。');
  if(leaving.current)throw new Error('请先选择是否离开编辑');
  const generation=context.current.generation,after=await flush();
  const receipt=retainedReceipt.current,view=after&&receipt?.frameId===after.frameId?receipt:bridge.getState().state.attachmentWorkspace;
  const frame=attachmentFrame(view),text=JSON.stringify(current.current);
  if(!alive.current||!frame||frame.windowId!==context.current.windowId||generation!==context.current.generation||JSON.stringify(view.documentDraft)!==text){
   if(alive.current)setError('原编辑页尚未确认本页内容，没有离开。请稍后重试。');
   throw new Error('原编辑页尚未确认本页内容');
  }
  // An entered generation is reconciled by its own native task; it is never discarded or submitted here.
  if(view.pendingDocumentTask||view.documentDraftEdited!==true)return after||frame;
  if(leaving.current)throw new Error('请先选择是否离开编辑');
  return new Promise<AttachmentFrame>((finish,reject)=>{
   leaving.current={finish,stay:()=>reject(new Error('继续编辑')),generation,text};setLeaveOpen(true);
  });
 };
 const answerLeave=(discard:boolean)=>{
  const question=leaving.current;if(!question)return;leaving.current=null;setLeaveOpen(false);
  if(!discard){question.stay();return}
  const view=bridge.getState().state.attachmentWorkspace,frame=attachmentFrame(view);
  if(frame?.windowId===context.current.windowId&&!document.hidden&&context.current.foregroundReady&&context.current.generation===question.generation&&
   !view.pendingDocumentTask&&JSON.stringify(view.documentDraft)===question.text&&JSON.stringify(current.current)===question.text)question.finish(frame);
  else {question.stay();setError('原编辑页已变化，没有放弃本页内容。')}
 };
 useEffect(()=>{alive.current=true;beforeBack(leaveEditor);return()=>{alive.current=false;revision.current++;leaving.current?.stay();leaving.current=null;beforeBack(null)}},[]);
 useEffect(()=>{leaving.current?.stay();leaving.current=null;setLeaveOpen(false);setReview(null);setAck(false);setSaved(false);revision.current++},[windowId,foregroundReady]);
 useEffect(()=>{const actual=JSON.stringify(original);if(actual===JSON.stringify(current.current)){committed.current=actual;setSaved(validDraft(current.current))}},[original]);
 const editable=foregroundReady&&!pendingTask&&!review&&!leaveOpen&&!retention;
 const edit=(next:Json)=>{if(!editable)return;setSaved(false);setError('');revision.current++;current.current=next;setDraft(next);if(validDraft(next))void flush().catch(()=>{})};
 const text=(field:string,value:string,limit:number)=>{if(value.length<=limit)edit({...draft,[field]:value});else setError(`此项最多 ${limit} 字，超出部分未写入`)};
 const slideText=(field:'title'|'notes'|'paragraph'|'shape',value:string,item?:number)=>{const limit=field==='title'?200:field==='notes'?8000:field==='shape'?4000:2000;if(value.length>limit){setError(`此项最多 ${limit} 字`);return}try{edit(editSlideText(draft,Number(slide),field,value,item))}catch(cause){setError(String(cause))}};
 const retain=async(checking=false)=>{
  if(!retainLinkedDraft||!foregroundReady||pendingTask||review||retentionFlight.current||
     (checking?retention!=='unknown':!!retention||!validDraft(current.current)))return;
  const generation=context.current.generation,version=revision.current;
  const currentWindow=()=>alive.current&&context.current.foregroundReady&&context.current.generation===generation&&revision.current===version;
  retentionFlight.current=true;setRetention('checking');setError('');
  let dispatched=false;
  try{
   const frame=await flush();
   if(!currentWindow()){if(alive.current)setRetention(checking?'unknown':undefined);return;}
   dispatched=true;
   const result=await action.current(checking?'drafts.checkRevision':'drafts.retainRevision',{},frame);
   if(!currentWindow()){if(alive.current)setRetention('unknown');return;}
   if((checking?result.status==='VERIFIED':result.status==='SAVED')){setRetention('verified');return;}
   if(!checking&&(result.status==='QUOTA'||result.sourceAvailable===false)){
    setRetention(undefined);setError(result.status==='QUOTA'?'本机草稿容量不足。本页修改没有丢弃。':'暂不能核对原文件与可编辑源。这次没有保留草稿，本页修改仍在。');return;
   }
   setRetention('unknown');setError('尚未核对这次保留，可核对原草稿，不会重复创建。');
  }catch(error){
   if(alive.current){
    const unknown=checking||dispatched;
    setRetention(unknown?'unknown':undefined);
    if(currentWindow())setError(unknown?'尚未核对这次保留，可核对原草稿，不会重复创建。':
      error instanceof Error?error.message:'没有提交修改草稿。');
   }
  }finally{retentionFlight.current=false}
 };
 const prepare=async()=>{if(retention)return;const request=revision.current,generation=context.current.generation;try{const after=await flush();if(!alive.current||request!==revision.current||context.current.generation!==generation)return;const result=await act('reviewDocument',{},after);if(alive.current&&request===revision.current&&context.current.generation===generation){setReview(result);setAck(false)}}catch(cause){setError(cause instanceof Error?cause.message:'文档审阅未完成')}};
 const closeReview=()=>{setReview(null);setAck(false);void action.current('discardReview').catch(()=>{})};
 useEffect(()=>{
  if(leaveOpen)return registerOverlayBack(()=>answerLeave(false));
  if(discardOpen)return registerOverlayBack(()=>setDiscardOpen(false));
  if(review)return registerOverlayBack(closeReview);
 },[leaveOpen,discardOpen,review]);
 const draftStatus=retention==='checking'?'正在核对本机修改草稿':retention==='verified'?'修改草稿已保留':retention==='unknown'?'修改草稿待核对':
  pendingTask?'生成结果待核对':saving?'正在保留本页修改':!saved?'本页修改待确认':unpublished?'更改在本页，尚未生成':'未生成新文件';
 return <section className="pw-document-editor"><header className="pw-document-heading"><h3>{presentation?'演示文稿内容':'文档内容'}</h3><p className="pw-muted">{baseVersion?<>从版本 <code>{baseVersion}</code> 继续。生成新文件时保留原版。</>:draft.parentArtifactId?'以选定原版本为起点，保存为新的独立文件。':'生成 Word 或 PDF，并保留可以继续修改的内容。'}</p></header>
 {error&&<p className="pw-error" role="alert">{error}</p>}{!validDraft(draft)&&supported&&<div className="pw-document-incomplete"><p className="pw-muted">补全文件名和正文后，编辑页才能保留本次输入。要在文件库里找到这份稿子，请生成新文件。</p>{JSON.stringify(draft)!==committed.current&&<Button variant="ghost" disabled={busy||saving} onClick={()=>setDiscardOpen(true)}>放弃未保存修改</Button>}</div>}
 {pendingTask&&<div className="pw-banner"><p>原保存动作待核，编辑与再次生成已暂停。</p><Button variant="secondary" disabled={busy} onClick={()=>void act('queryDocument').catch(()=>{})}>核对原文档结果</Button></div>}
 {supported?<><TextField label="文件名" value={draft.name||''} onChange={value=>text('name',value,80)} isDisabled={!editable}/>
 {presentation?<><SelectField label="幻灯片" value={slide} options={slides.map((item,i)=>({id:String(i),label:`第 ${i+1} 页 · ${item.title||'未命名'}`}))} onChange={setSlide} isDisabled={!editable}/>{selected&&<><TextField label="当前页标题" value={selected.title||''} onChange={value=>slideText('title',value)} isDisabled={!editable}/>{Array.isArray(selected.paragraphs)&&selected.paragraphs.map((value:string,i:number)=><TextAreaField key={i} label={`第 ${i+1} 段正文`} value={value} onChange={text=>slideText('paragraph',text,i)} isDisabled={!editable}/>)}<Accordion className="pw-document-secondary"><AccordionItem id="slide-notes" title="讲稿与文本对象" description={`${rows(selected.shapes).length} 个已有文本对象` }><TextAreaField label="讲稿备注" value={selected.notes||''} onChange={text=>slideText('notes',text)} isDisabled={!editable}/>{rows(selected.shapes).map((shape,i)=><TextAreaField key={shape.id||i} label={`文本对象 ${shape.id||i+1}`} value={shape.text||''} onChange={text=>slideText('shape',text,i)} isDisabled={!editable}/>)}</AccordionItem></Accordion></>}<p className="pw-muted">保留页面顺序、对象位置和大小。修改已有文字与备注后，请打开新原件核对完整排版。</p></>:<AttachmentWordPdfFields draft={draft} editable={editable} edit={edit} text={text}/>}
 <footer className="pw-document-footer"><span className="pw-document-save-state" role="status" data-saved={saved&&!saving}>{draftStatus}</span>
  {!retainLinkedDraft&&!retention&&!saving&&!saved&&validDraft(draft)&&<Button variant="secondary" disabled={busy||!editable} onClick={()=>void flush().catch(()=>{})}>保留本页修改</Button>}
  {retainLinkedDraft&&retention==='unknown'&&<Button variant="secondary" disabled={busy||saving} onClick={()=>void retain(true)}>核对本次保留</Button>}
  {retainLinkedDraft&&!retention&&<Button variant="secondary" disabled={busy||saving||!editable||!validDraft(draft)} onClick={()=>void retain()}>保留修改草稿</Button>}
  {!retention&&<Button variant="primary" disabled={busy||saving||!!pendingTask||!validDraft(draft)} onClick={()=>void prepare()}>核对并生成新文件</Button>}
 </footer></>:<p>此文件没有可在这里编辑的固定结构源，可以返回后打开或导出原件。</p>}
 <SettingsDialog isOpen={leaveOpen} title="不生成新版本就离开？" closeLabel="继续编辑" onClose={()=>answerLeave(false)}>
  <p>{retention==='unknown'?'这次保留结果尚待核对。离开不会重新保存，也不会改动原件。如需核对，请在本机文档草稿查看。':'这些更改尚未生成新文档。离开会丢弃本页修改，文件库中的原件不变。'}</p>
  <div className="pw-action-row"><Button variant="primary" onClick={()=>answerLeave(false)}>继续编辑</Button><Button variant="danger" onClick={()=>answerLeave(true)}>放弃修改并离开</Button></div>
 </SettingsDialog>
 <AttachmentDocumentGenerationReview open={!!review} format={String(draft.format||'')} name={review?.draft?.name} ack={ack} busy={busy}
  onAck={setAck} onClose={closeReview} onConfirm={()=>{if(review){const id=review.reviewId;setReview(null);void act('createDocument',{reviewId:id,acknowledge:true}).catch(()=>{})}}}/>
 <SettingsDialog isOpen={discardOpen} title="放弃未保存修改" onClose={()=>setDiscardOpen(false)}><p>恢复到本页最后确认保留的内容。已经生成的文件与版本保持不变。</p><div className="pw-action-row"><Button variant="secondary" onClick={()=>setDiscardOpen(false)}>继续编辑</Button><Button variant="danger" disabled={busy||saving} onClick={()=>{const retained=JSON.parse(committed.current);current.current=retained;setDraft(retained);setError('');setSaved(validDraft(retained));setDiscardOpen(false)}}>放弃未保存修改</Button></div></SettingsDialog>
 </section>;
}

export function AttachmentProcessingEditor({info,review,reviewId,pendingTask,busy,act}:{info:Json;review:Json;reviewId?:string;pendingTask?:string;busy:boolean;act:AttachmentAction}) {
 const image=typeof info.width==='number';const [options,setOptions]=useState<Json>(()=>processingDefaults(info)),[ack,setAck]=useState(false),[dirty,setDirty]=useState(false);
 useEffect(()=>setAck(false),[reviewId]);
 const change=(key:string,value:any)=>{setOptions(old=>({...old,[key]:value}));setAck(false);setDirty(true);if(Object.keys(review).length)void act('discardReview').catch(()=>{})};
 const locked=busy||!!pendingTask;
 const fields=image?{left:'裁剪左边界',top:'裁剪上边界',right:'裁剪右边界',bottom:'裁剪下边界',width:'输出宽度',height:'输出高度'}:{startMillis:'开始（毫秒）',endMillis:'结束（毫秒）'};
 const valid=Object.values(options).every(value=>typeof value!=='number'||Number.isSafeInteger(value));
 return <section className="pw-file-processing"><h3>{image?'裁剪、缩放或转换原图':'截取原媒体片段'}</h3><p className="pw-muted">{image?`原图 ${info.width} × ${info.height}，按原图像素填写。`:`原时长 ${((info.durationMillis||0)/1000).toFixed(2)} 秒。单段最多 2 分钟。`}加工后保存新文件，原件保留。</p>{!image&&<p className="pw-muted">起点对齐：{info.startAlignmentLabel||'原样本边界待核对'}。保留原编码，实际范围以本次核对结果为准。</p>}
 {!info.editable&&<p className="pw-error">这份原件超出当前本机加工范围，可以继续查看或导出。</p>}
 {pendingTask&&<Button variant="secondary" disabled={busy} onClick={()=>void act('queryProcessing').catch(()=>{})}>核对原加工结果</Button>}
 <div className="pw-processing-groups"><section><h4>{image?'裁剪范围':'截取范围'}</h4><div className="pw-file-form-grid">{Object.entries(fields).filter(([key])=>!image||!['width','height'].includes(key)).map(([key,label])=><TextField key={key} label={label} value={String(options[key])} onChange={value=>change(key,value===''?'':Number(value))} isDisabled={locked}/>)}</div></section>{image&&<section><h4>输出尺寸与格式</h4><div className="pw-file-form-grid">{['width','height'].map(key=><TextField key={key} label={key==='width'?'输出宽度':'输出高度'} value={String(options[key])} onChange={value=>change(key,value===''?'':Number(value))} isDisabled={locked}/>)}<SelectField label="旋转" value={String(options.rotation)} options={[0,90,180,270].map(n=>({id:String(n),label:`${n}°`}))} onChange={value=>change('rotation',Number(value))} isDisabled={locked}/><SelectField label="输出格式" value={options.format} options={[{id:'png',label:'PNG'},{id:'jpeg',label:'JPEG'}]} onChange={value=>change('format',value)} isDisabled={locked}/>{options.format==='jpeg'&&<TextField label="JPEG 质量（50—95）" value={String(options.quality)} onChange={value=>change('quality',Number(value))} isDisabled={locked}/>}</div></section>}</div>
 <Button variant="secondary" disabled={locked||!info.editable||!valid} onClick={()=>{setDirty(false);setAck(false);void act('reviewProcessing',{options}).catch(()=>{})}}>核对实际加工范围</Button>
 {!dirty&&Object.keys(review).length>0&&<div className="pw-file-processing-review"><h4>本次加工</h4>{image?<p>裁剪 ({review.left}, {review.top}) 到 ({review.right}, {review.bottom})，输出 {review.width} × {review.height}，旋转 {review.rotation}°，{review.outputFormat?.toUpperCase()}。</p>:<p>请求从 {review.requestedStartUs/1000000} 秒到 {review.requestedEndUs/1000000} 秒，实际起点为 {review.actualStartUs/1000000} 秒。起点对齐：{review.startAlignmentLabel||'原样本边界待核对'}。保留原编码，按原媒体样本边界截取。</p>}<FileDetails value={review} title="原文件与加工详情"/><CheckboxField isSelected={ack} onChange={setAck} isDisabled={locked}>确认按已核对范围保存新原件</CheckboxField><Button variant="primary" disabled={locked||!ack||!reviewId} onClick={()=>{const originalReviewId=reviewId;setAck(false);void act('applyProcessing',{reviewId:originalReviewId,acknowledge:true}).catch(()=>{})}}>保存加工后的新原件</Button></div>}
 </section>;
}
