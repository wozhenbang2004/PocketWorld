import {useEffect,useRef,useState} from 'react';
import {Button,CheckboxField,SelectField,SettingsDialog,TextAreaField,TextField} from './forms-library';
import {attachmentFrame,editSlideText,fileRows as rows,processingDefaults,type AttachmentAction,type AttachmentFrame,type FileJson as Json} from './attachment-workspace';
import {FileDetails} from './AttachmentOriginalReader';

const validDraft=(draft:Json)=>!!String(draft.name||'').trim()&&String(draft.name).length<=80&&new TextEncoder().encode(JSON.stringify(draft)).length<=256*1024&&
 (draft.format==='pptx'?!!draft.parentArtifactId&&Array.isArray(draft.presentation?.slides):['docx','pdf'].includes(draft.format)&&typeof draft.body==='string'&&draft.body.length>0&&draft.body.length<=16000);

export function AttachmentDocumentEditor({original,windowId,foregroundReady,pendingTask,busy,act,beforeBack}:{original:Json;windowId:string;foregroundReady:boolean;pendingTask?:string;busy:boolean;act:AttachmentAction;beforeBack:(handler:(()=>Promise<AttachmentFrame|undefined>)|null)=>void}) {
 const [draft,setDraft]=useState<Json>(()=>structuredClone(original)),[slide,setSlide]=useState('0'),[error,setError]=useState(''),[saving,setSaving]=useState(false),[saved,setSaved]=useState(false),[review,setReview]=useState<Json|null>(null),[ack,setAck]=useState(false);
 const current=useRef(draft),committed=useRef(JSON.stringify(original)),savingPromise=useRef<Promise<AttachmentFrame|undefined>|null>(null),alive=useRef(true),revision=useRef(0),action=useRef(act);current.current=draft;action.current=act;
 const context=useRef({windowId,foregroundReady,generation:0});
 if(context.current.windowId!==windowId||context.current.foregroundReady!==foregroundReady)context.current={windowId,foregroundReady,generation:context.current.generation+1};
 const presentation=draft.format==='pptx',supported=['docx','pdf'].includes(draft.format)||presentation&&!!draft.parentArtifactId,slides=rows(draft.presentation?.slides),selected=slides[Number(slide)];
 async function flush():Promise<AttachmentFrame|undefined> {
  if(savingPromise.current)return savingPromise.current;
  if(!context.current.foregroundReady)throw new Error('回到当前文件窗口后可保存保留的输入');
  if(!validDraft(current.current)||JSON.stringify(current.current)===committed.current)return;
  const generation=context.current.generation;
  const operation=(async()=>{setSaving(true);let after:AttachmentFrame|undefined;try{
   while(alive.current&&validDraft(current.current)&&JSON.stringify(current.current)!==committed.current){
    if(!context.current.foregroundReady||context.current.generation!==generation)throw new Error('原文件窗口已变化，当前输入保留，请重新保存');
    const next=structuredClone(current.current),encoded=JSON.stringify(next),result=await action.current('updateDocument',{draft:next},after);
    if(!alive.current||context.current.generation!==generation)throw new Error('原文件窗口已变化，当前输入保留，请重新保存');
    after=attachmentFrame(result.workspace);if(!after)throw new Error('原草稿回执尚未核对，请保留当前输入');
    committed.current=encoded;setSaved(JSON.stringify(current.current)===encoded);setError('');
   }
   return after;
  }catch(cause){if(alive.current)setError(cause instanceof Error?cause.message:'草稿尚未保存');throw cause}finally{savingPromise.current=null;if(alive.current)setSaving(false)}})();
  savingPromise.current=operation;return operation;
 }
 useEffect(()=>{alive.current=true;beforeBack(flush);return()=>{alive.current=false;revision.current++;beforeBack(null)}},[]);
 useEffect(()=>{setReview(null);setAck(false);setSaved(false);revision.current++},[windowId,foregroundReady]);
 useEffect(()=>{const actual=JSON.stringify(original);if(actual===JSON.stringify(current.current)){committed.current=actual;setSaved(validDraft(current.current))}},[original]);
 const editable=foregroundReady&&!pendingTask&&!review;
 const edit=(next:Json)=>{if(!editable)return;setSaved(false);setError('');revision.current++;current.current=next;setDraft(next);if(validDraft(next))void flush().catch(()=>{})};
 const text=(field:string,value:string,limit:number)=>{if(value.length<=limit)edit({...draft,[field]:value});else setError(`此项最多 ${limit} 字，超出部分未写入`)};
 const slideText=(field:'title'|'notes'|'paragraph'|'shape',value:string,item?:number)=>{const limit=field==='title'?200:field==='notes'?8000:field==='shape'?4000:2000;if(value.length>limit){setError(`此项最多 ${limit} 字`);return}try{edit(editSlideText(draft,Number(slide),field,value,item))}catch(cause){setError(String(cause))}};
 const prepare=async()=>{const request=revision.current,generation=context.current.generation;try{const after=await flush();if(!alive.current||request!==revision.current||context.current.generation!==generation)return;const result=await act('reviewDocument',{},after);if(alive.current&&request===revision.current&&context.current.generation===generation){setReview(result);setAck(false)}}catch(cause){setError(cause instanceof Error?cause.message:'文档审阅未完成')}};
 const closeReview=()=>{setReview(null);setAck(false);void act('discardReview').catch(()=>{})};
 return <section className="pw-document-editor"><h3>{presentation?'编辑原演示文稿':'编辑文档'}</h3><p className="pw-muted">{draft.parentArtifactId?'以选定原版本为起点，保存为新的独立文件。':'生成 Word 或 PDF，并保留可以继续修改的内容。'}</p>
 {error&&<p className="pw-error" role="alert">{error}</p>}{!validDraft(draft)&&supported&&<p className="pw-muted">填写完整的文件名和正文后会保存可恢复草稿。当前不完整输入仍留在编辑页。</p>}
 {pendingTask&&<div className="pw-banner"><p>原保存动作待核，编辑与再次生成已暂停。</p><Button variant="secondary" disabled={busy} onClick={()=>void act('queryDocument').catch(()=>{})}>核对原文档结果</Button></div>}
 {supported?<><TextField label="文件名" value={draft.name||''} onChange={value=>text('name',value,80)} isDisabled={!editable}/>
 {presentation?<><SelectField label="幻灯片" value={slide} options={slides.map((item,i)=>({id:String(i),label:`第 ${i+1} 页 · ${item.title||'未命名'}`}))} onChange={setSlide} isDisabled={!editable}/>{selected&&<><TextField label="当前页标题" value={selected.title||''} onChange={value=>slideText('title',value)} isDisabled={!editable}/>{Array.isArray(selected.paragraphs)&&selected.paragraphs.map((value:string,i:number)=><TextAreaField key={i} label={`第 ${i+1} 段正文`} value={value} onChange={text=>slideText('paragraph',text,i)} isDisabled={!editable}/>)}<TextAreaField label="讲稿备注" value={selected.notes||''} onChange={text=>slideText('notes',text)} isDisabled={!editable}/>{rows(selected.shapes).map((shape,i)=><TextAreaField key={shape.id||i} label={`文本对象 ${shape.id||i+1}`} value={shape.text||''} onChange={text=>slideText('shape',text,i)} isDisabled={!editable}/>)}</>}<p className="pw-muted">保留页面顺序、对象位置和大小。修改已有文字与备注后，请打开新原件核对完整排版。</p></>:<><TextField label="标题（可选）" value={draft.title||''} onChange={value=>text('title',value,200)} isDisabled={!editable}/><div className="pw-file-form-grid"><SelectField label="文件格式" value={draft.format} options={[{id:'docx',label:'Word · 可编辑'},{id:'pdf',label:'PDF · 固定页面'}]} onChange={format=>edit({...draft,format})} isDisabled={!editable}/><SelectField label="字号" value={String(draft.fontPoints||12)} options={[10,11,12,14,16].map(n=>({id:String(n),label:`${n} pt`}))} onChange={font=>edit({...draft,fontPoints:Number(font)})} isDisabled={!editable}/></div><TextAreaField label={`正文 · ${String(draft.body||'').length}/16000`} value={draft.body||''} onChange={value=>text('body',value,16000)} isDisabled={!editable}/><p className="pw-muted">用 # 标记标题、空行分段、制表符分隔简单表格。生成后可直接查看原件。</p></>}
 <p className="pw-muted" role="status">{saving?'正在保存草稿…':saved?'可恢复草稿已保存':''}</p>{!saving&&!saved&&validDraft(draft)&&<Button variant="secondary" disabled={busy||!editable} onClick={()=>void flush().catch(()=>{})}>保存保留的修改</Button>}<Button variant="primary" disabled={busy||saving||!!pendingTask||!validDraft(draft)} onClick={()=>void prepare()}>核对并生成新文件</Button></>:<p>此文件没有可在这里编辑的固定结构源，可以返回后打开或导出原件。</p>}
 <SettingsDialog isOpen={!!review} title={`生成新的 ${draft.format?.toUpperCase()} 文件`} onClose={closeReview}>{review&&<><p>将保存当前可编辑内容和一个新的独立原件。生成完成后可查看排版，再选择导出或分享。</p><p><strong>{review.draft?.name}</strong></p><CheckboxField isSelected={ack} onChange={setAck}>确认生成这个新版本</CheckboxField><div className="pw-action-row"><Button variant="secondary" onClick={closeReview}>继续编辑</Button><Button variant="primary" disabled={!ack||busy} onClick={()=>{const id=review.reviewId;setReview(null);void act('createDocument',{reviewId:id,acknowledge:true}).catch(()=>{})}}>生成新文件</Button></div></>}</SettingsDialog>
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
 <div className="pw-file-form-grid">{Object.entries(fields).map(([key,label])=><TextField key={key} label={label} value={String(options[key])} onChange={value=>change(key,value===''?'':Number(value))} isDisabled={locked}/>)}{image&&<><SelectField label="旋转" value={String(options.rotation)} options={[0,90,180,270].map(n=>({id:String(n),label:`${n}°`}))} onChange={value=>change('rotation',Number(value))} isDisabled={locked}/><SelectField label="输出格式" value={options.format} options={[{id:'png',label:'PNG'},{id:'jpeg',label:'JPEG'}]} onChange={value=>change('format',value)} isDisabled={locked}/>{options.format==='jpeg'&&<TextField label="JPEG 质量（50—95）" value={String(options.quality)} onChange={value=>change('quality',Number(value))} isDisabled={locked}/>}</>}</div>
 <Button variant="secondary" disabled={locked||!info.editable||!valid} onClick={()=>{setDirty(false);setAck(false);void act('reviewProcessing',{options}).catch(()=>{})}}>核对实际加工范围</Button>
 {!dirty&&Object.keys(review).length>0&&<div className="pw-file-processing-review"><h4>本次加工</h4>{image?<p>裁剪 ({review.left}, {review.top}) 到 ({review.right}, {review.bottom})，输出 {review.width} × {review.height}，旋转 {review.rotation}°，{review.outputFormat?.toUpperCase()}。</p>:<p>请求从 {review.requestedStartUs/1000000} 秒到 {review.requestedEndUs/1000000} 秒，实际起点为 {review.actualStartUs/1000000} 秒。起点对齐：{review.startAlignmentLabel||'原样本边界待核对'}。保留原编码，按原媒体样本边界截取。</p>}<FileDetails value={review} title="原文件与加工详情"/><CheckboxField isSelected={ack} onChange={setAck} isDisabled={locked}>确认按已核对范围保存新原件</CheckboxField><Button variant="primary" disabled={locked||!ack||!reviewId} onClick={()=>{const originalReviewId=reviewId;setAck(false);void act('applyProcessing',{reviewId:originalReviewId,acknowledge:true}).catch(()=>{})}}>保存加工后的新原件</Button></div>}
 </section>;
}
