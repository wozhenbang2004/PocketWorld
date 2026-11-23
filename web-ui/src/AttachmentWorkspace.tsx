import {useCallback,useEffect,useLayoutEffect,useMemo,useRef,useState} from 'react';
import { IconArrowLeft, IconChevronLeft, IconChevronRight, IconClose, IconExternalLink, IconFileAdd, IconFolder, IconInfo, IconMore, IconRefresh, IconSearch, IconShare, IconUpload } from './icons';
import {bridge} from './bridge';
import {Accordion,AccordionItem,Button,IconButton,SelectField,TextField} from './forms-library';
import {Drawer,ContextMenuTrigger,ContextMenu,ContextMenuItem,Popover,PopoverTrigger} from './shell-library';
import {AttachmentOriginalReader,DocumentProjection,FileDetails,capturePdfCopyViewport,type DisplayedOriginalText,type PdfCopyViewportReturn} from './AttachmentOriginalReader';
import {AttachmentDocumentEditor,AttachmentProcessingEditor,AttachmentVersionContinuation,type SavedFileNote} from './AttachmentDocumentEditor';
import {AttachmentStandaloneDrafts} from './AttachmentStandaloneDrafts';
import {OriginalImageComparison} from './workspace-library/OriginalImageComparison';
import {AttachmentFileCollection} from './workspace-library/AttachmentFileCollection';
import {attachmentActionPayload,attachmentDocumentKey,attachmentFileFormat,attachmentFrame,documentVersionKey,exactFileKey,exactFileTarget,fileBytes,fileObject as obj,fileRows as rows,fileRunProvenance,libraryFileOrigins,normalizedFileName,savedDocumentVersions,type AttachmentFrame,type FileJson as Json} from './attachment-workspace';
import './attachment-workspace.css';
import './attachment-page.css';

export type FileDraftTarget={key:string;epoch:number;revision:number;conversationId:string;label:string};
export type FileResultTopic={taskId:string;conversationId:string;label:string};
type StandaloneDocument={taskId:string;mimeType:string;size:number};
export type OriginalAttachmentChoice={fileId:string;sha256:string;name:string;standaloneDocument?:StandaloneDocument};
export type QuotedDraftArrival={windowId:string;library:string;document:number;
 current:()=>boolean;restoreFocus:(event:Event)=>void};
type BookReplyLink={current:(shown:Json)=>boolean;restore:()=>void};
type BookReplyExit={windowId:string;link:BookReplyLink;accepted:boolean;exited:boolean;scheduled?:true};
type Props={
 onOriginalBookReply?:(shown:Json)=>BookReplyLink|undefined;
 state:Json;registerBack:(handler:(()=>boolean)|null)=>void;onReturnToSearch?:()=>void;onReturnFocus?:(event:Event,messagePdfWindow?:string,historicalReply?:{reader:number;visit:string})=>void;returnToDeliveredFiles?:boolean;openTaskId?:string;
 canReturnToOpenWork?:(current:Json)=>boolean;
 draftTarget?:FileDraftTarget;resultTopic?:FileResultTopic;keepResultReadingDraft?:FileDraftTarget;
 onStageFile?:(reference:Json,target:FileDraftTarget,stillCurrent:()=>boolean)=>Promise<boolean>;
 onOpenDraft?:(reference:Json,target:FileDraftTarget,closeOriginal:()=>Promise<unknown>)=>Promise<boolean>;
 onUseResult?:(reference:Json,topic:FileResultTopic,openOnly:boolean,stillCurrent:()=>boolean,closeOriginal:()=>Promise<unknown>,keepReading?:FileDraftTarget)=>Promise<boolean>;
 onChooseResultAttachment?:(file:OriginalAttachmentChoice,topic:FileResultTopic,stillCurrent:()=>boolean,closeOriginal:()=>Promise<unknown>)=>Promise<boolean>;
 onAdoptFileNote?:(note:SavedFileNote,frame:AttachmentFrame,closeOriginal:()=>Promise<unknown>)=>Promise<void>;
 onVerifyReportFileWork?:(taskId:string,windowId:string)=>Promise<void>;
 onChooseAttachment?:(file:OriginalAttachmentChoice,target:FileDraftTarget,stillCurrent:()=>boolean,closeOriginal:()=>Promise<void>)=>Promise<void>;
 standaloneDocumentAttachmentDraft?:FileDraftTarget;replyAttachmentDraft?:FileDraftTarget;
 readWorkTextVisit?:(taskId:string)=>WorkTextVisit|undefined;
 quotedDraftArrival?:QuotedDraftArrival;
};
type DraftHandoff={scope:string;phase:'adding'|'added'|'opening'|'failed';error?:string};
type ExactFile=NonNullable<ReturnType<typeof exactFileTarget>>;
type WorkTextVisit={taskId:string;runId:string;epoch:number;visit:string};
type WorkPdfCopyAttempt=NonNullable<ReturnType<typeof capturePdfCopyViewport>>&{nonce:string;scope:string;windowId:string};
type TextReturn={scope:string;document:number;range:DisplayedOriginalText;top:number;textStart?:number;reopening?:true;start?:{viewerId:string;top:number};work?:WorkTextVisit;libraryList?:'returning'|'listed'};
type TxtDraftSource={receipt:string;fileId:string;sha256:string};
type StandaloneVisit={windowId:string;document:number;page:Document;presentation:string;entry:'catalog'|'blank'|'resume'|'quoted'|'txt';
 pendingDocument?:string;warmReentry?:true;txtSource?:TxtDraftSource};
const standalonePendingDocument=(view:Json)=>typeof view.pendingDocumentTask==='string'?view.pendingDocumentTask:undefined;
function standaloneLibrarySurface(view:Json){
 return view.open===true&&view.taskId==null&&view.group===false&&view.team===false&&view.deviceOriginalOnly===false&&
  view.historyResultOnly===false&&view.reportResultOnly===false&&view.resultFilesOnly===false&&
  view.choosingSource===false&&view.file==null&&!view.documentDraft&&!view.documentOpen&&!view.processingOpen&&!view.comparison;
}
function textBounds(body:HTMLDivElement){
 const text=body.querySelector<HTMLElement>('.pw-original-text[data-find-before] > .pw-file-text')?.getBoundingClientRect();
 return text&&text.height>0?{top:body.scrollTop+text.top-body.getBoundingClientRect().top,height:text.height}:undefined;
}
function readingTextStart(body:HTMLDivElement){
 const bounds=textBounds(body),top=body.scrollTop;
 return bounds&&top>=bounds.top&&top<bounds.top+bounds.height?bounds.top:undefined;
}
function privateLibraryWindow(view:Json){
 return view.open===true&&view.ready===true&&typeof view.windowId==='string'&&!!view.windowId&&
  view.taskId==null&&obj(view.snapshot).taskId===''&&
  view.group===false&&view.team===false&&view.deviceOriginalOnly===false&&view.fromWorkResult===false&&view.resultFilesOnly===false&&
  view.historyResultOnly===false&&view.reportResultOnly===false&&view.choosingSource===false;
}
function workPdfTextWindow(view:Json,taskId:string|undefined){
 const work=obj(view.snapshot),file=obj(view.file);
 return !!taskId&&view.workPdfTextPage===true&&view.open===true&&view.ready===true&&typeof view.windowId==='string'&&!!view.windowId&&
  view.taskId===taskId&&work.taskId===taskId&&work.taskStatus==='completed'&&view.fromWorkResult===true&&view.originalReturn==='entry'&&
  view.restoredWorkReturn===false&&view.resultFilesOnly===false&&view.historyResultOnly===false&&view.reportResultOnly===false&&
  view.group===false&&view.team===false&&view.deviceOriginalOnly===false&&view.choosingSource===false&&
  file.role==='result'&&file.availability==='available'&&file.sourceKind==='created_document'&&file.mimeType==='application/pdf'&&
  !!exactFileTarget(file)&&file.sourceTaskId===taskId&&typeof work.taskRunId==='string'&&!!work.taskRunId&&
  file.sourceTaskRunId===work.taskRunId&&Number.isSafeInteger(work.coordinatorEpoch)&&work.coordinatorEpoch>0;
}
function workPdfCopyScope(view:Json,taskId:string|undefined){
 const work=obj(view.snapshot),file=obj(view.file),original=obj(view.originalFile),selection=exactFileTarget(file);
 if(!taskId||view.open!==true||view.ready!==true||view.taskId!==taskId||work.taskId!==taskId||
    view.fromWorkResult!==true||view.originalReturn!=='entry'||view.restoredWorkReturn!==false||view.resultFilesOnly!==false||
    view.historyResultOnly!==false||view.reportResultOnly!==false||view.group!==false||view.team!==false||view.deviceOriginalOnly!==false||
    view.documentOpen!==false||view.choosingSource!==false||!selection||file.role!=='result'||file.availability!=='available'||
    file.sourceKind!=='created_document'||file.mimeType!=='application/pdf'||file.sourceTaskId!==taskId||
    typeof work.taskRunId!=='string'||!work.taskRunId||file.sourceTaskRunId!==work.taskRunId||
    !Number.isSafeInteger(work.coordinatorEpoch)||work.coordinatorEpoch<1||
    original.kind!=='pdf'||original.sourceUnavailable!==false||original.sha256!==selection.sha256||
    typeof original.identity!=='string'||!/^[a-f0-9]{64}$/.test(original.identity))return '';
 return JSON.stringify([taskId,work.taskRunId,work.coordinatorEpoch,selection.fileId,selection.sha256,original.identity]);
}
function workPdfCopyProof(view:Json,taskId:string|undefined,held:WorkPdfCopyAttempt){
 const returned=obj(view.workPdfCopyViewport),receipt=obj(view.privateCopyReceipt),file=obj(view.file),
  original=obj(view.originalFile),shown=obj(original.information);
 if(workPdfCopyScope(view,taskId)!==held.scope||!attachmentFrame(view)||view.windowId===held.windowId||
    view.busy!==false||view.exportPending!==false||view.canShareWorkPdf!==true||original.busy!==false||
    returned.nonce!==held.nonce||typeof returned.requestId!=='string'||!returned.requestId||
    receipt.requestId!==returned.requestId||receipt.historical===true||!['verified','no_target'].includes(receipt.status)||
    receipt.taskId!==taskId||receipt.fileId!==file.id||receipt.sha256!==file.sha256||
    typeof returned.viewerId!=='string'||returned.viewerId===held.viewerId||returned.viewerId!==original.id||
    typeof returned.rasterId!=='string'||returned.rasterId===held.rasterId||returned.rasterId!==shown.pdfRasterId||
    returned.page!==held.page||shown.page!==held.page)return;
 return {viewerId:returned.viewerId,rasterId:returned.rasterId,page:held.page};
}
function pairedPdfPage(view:Json){
 const pair=obj(view.pdfPageText),reader=obj(view.originalFile),shown=obj(reader.information),file=obj(view.file);
 return view.documentOpen===true&&reader.kind==='pdf'&&reader.sourceUnavailable===false&&
  reader.id===pair.viewerId&&reader.sha256===file.sha256&&typeof pair.rasterId==='string'&&shown.pdfRasterId===pair.rasterId&&
  Number.isSafeInteger(pair.page)&&pair.page>0&&shown.page===pair.page?pair.page as number:undefined;
}
function matchesPdfPageText(view:Json,taskId:string,page:number){
 const read=obj(view.document),file=obj(view.file);
 return read.attachmentId===file.id&&read.sha256===file.sha256&&read.taskId===taskId&&
  read.readContext==='native_owner_preview'&&read.format==='pdf'&&read.extraction==='text'&&read.coverage==='pdf_text_layer'&&
  Array.isArray(read.chunks)&&read.chunks.every((chunk:Json)=>obj(chunk).unit===page);
}
function workPdfTextResult(view:Json,taskId:string|undefined){
 if(!taskId||!workPdfTextWindow(view,taskId)||view.busy!==false||view.exportPending!==false||view.error)return;
 const page=pairedPdfPage(view),read=obj(view.document);
 if(!page)return;
 const file=obj(view.file),scope=obj(view.snapshot),pair=obj(view.pdfPageText);
 const key=JSON.stringify([view.windowId,taskId,scope.taskRunId,scope.coordinatorEpoch,file.id,file.sha256,pair.viewerId,pair.rasterId,page]);
 const unavailable={key,text:'',message:'暂不能核对本页的本机文字层。可返回原 PDF。'};
 if(read.attachmentId!==file.id||read.sha256!==file.sha256||read.taskId!==taskId||read.readContext!=='native_owner_preview')return unavailable;
 if(read.extractionStatus==='limit_exceeded')return {key,text:'',message:'本次文字读取超过限制，没有提供本页文字。可返回原 PDF。'};
 if(!matchesPdfPageText(view,taskId,page)||!['ok','partial'].includes(read.extractionStatus)||
    !Number.isSafeInteger(read.totalUnits)||read.totalUnits<page)return unavailable;
 const chunks=rows(read.chunks);
 if(chunks.length!==read.chunks.length||chunks.length>48||!chunks.every(chunk=>chunk.kind==='pdf_text'&&typeof chunk.text==='string'))return unavailable;
 const text=chunks.map(chunk=>chunk.text).join('\n');
 if(text.length>12048)return unavailable;
 const pageLimited=read.truncated===true&&read.nextUnit===page;
 if(!text.trim())return {key,text:'',message:pageLimited?
  '本页文字达到读取上限，未读到可显示的文字。可返回原 PDF。':
  '没有提取到本页的文字层。这不代表本页空白；未做图片识别。'};
 return {key,text,message:pageLimited?'本页文字达到读取上限，仅显示已读部分。':'仅显示本页的本机文字层，未做图片识别。'};
}
function privateTxtPositionScope(view:Json){
 const file=obj(view.file),version=exactFileTarget(file);
 return privateLibraryWindow(view)&&view.originalReturn==='list'&&typeof file.name==='string'&&/\.txt$/i.test(file.name)&&version?
  JSON.stringify([view.windowId,version.fileId,version.sha256]):'';
}
function privateTxtLibraryList(view:Json){
 return privateLibraryWindow(view)&&!!attachmentFrame(view)&&view.originalReturn==='details'&&view.file==null&&view.originalFile==null&&
  view.documentOpen===false&&view.documentDraft==null&&view.processingOpen===false&&view.comparison==null;
}
function ordinaryLibraryOwner(native:ReturnType<typeof bridge.getState>){
 const current=obj(native.view);
 return current.section===null&&current.taskId===null&&native.state.chatAttachmentPickerOpen===false&&!native.state.conversationReader;
}
function standaloneDraftPresentation(native:ReturnType<typeof bridge.getState>,visit:StandaloneVisit){
 const current=obj(native.state.attachmentWorkspace),frame=attachmentFrame(current);
 if(visit.page!==document||document.hidden||native.host?.ready!==true||!ordinaryLibraryOwner(native)||
    native.state.filePath!=null||!standaloneLibrarySurface(current)||!frame||
    (visit.entry==='quoted')!==(current.focusedStandaloneDraft===true)||
    visit.entry==='txt'&&(!visit.txtSource||current.txtDraftLanding!==visit.txtSource.receipt)||
    current.standaloneDraftLibraryVisit!==visit.presentation||standalonePendingDocument(current)!==visit.pendingDocument||
    typeof current.standaloneDraftLibrary!=='boolean')return;
 return {frame,leased:current.standaloneDraftLibrary};
}
function warmDraftKey(native:ReturnType<typeof bridge.getState>,visit:StandaloneVisit){
 const shown=standaloneDraftPresentation(native,visit),live=obj(native.state.attachmentWorkspace),resume=obj(live.warmSavedDraft);
 // The Android command ticket rotates. The actual Web document and logical Library must remain the same.
 return (visit.entry==='catalog'||visit.entry==='blank')&&shown?.leased===false&&shown.frame.windowId!==visit.windowId&&
  live.busy===false&&live.exportPending===false&&resume.library===visit.presentation&&resume.previousWindow===visit.windowId&&
  typeof resume.key==='string'&&/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/.test(resume.key)?resume.key:undefined;
}
function generatedDraftHandoff(view:Json){
 const marker=obj(view.generatedDraftReturn),file=obj(view.file),target=exactFileTarget(file);
 const token=(value:unknown):value is string=>typeof value==='string'&&/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/.test(value);
 if(!privateLibraryWindow(view)||!target||view.documentDraft||view.documentOpen||view.processingOpen||view.comparison||
    view.restoredWorkReturn!==false||view.busy!==false||view.exportPending!==false||
    file.role!=='result'||file.sourceKind!=='created_document'||marker.fileId!==target.fileId||marker.sha256!==target.sha256||
    !token(marker.handle)||!token(marker.library))return;
 return {handle:marker.handle,library:marker.library,...target};
}
function waitForDraftLibraryFrame(visit:StandaloneVisit):Promise<boolean>{
 // A command completion can reach the page before the ordinary native state publication.
 // Wait for that frame, not a new read, and never reacquire a retired or different window.
 return new Promise(resolve=>{
  const finish=(value:boolean)=>{unsubscribe();window.clearTimeout(timer);document.removeEventListener('visibilitychange',update);resolve(value)};
  const update=()=>{
   const native=bridge.getState(),view=obj(native.state.attachmentWorkspace);
   if(visit.page!==document||document.hidden||bridge.getDocumentEpoch()!==visit.document||!ordinaryLibraryOwner(native)||
      view.open!==true||view.ready===true&&view.windowId!==visit.windowId){finish(false);return}
   const shown=standaloneDraftPresentation(native,visit);
   if(shown?.leased===true&&shown.frame.windowId===visit.windowId&&view.busy===false&&view.exportPending===false)finish(true);
  };
  const unsubscribe=bridge.subscribe(update),timer=window.setTimeout(()=>finish(false),3000);
  document.addEventListener('visibilitychange',update);update();
 });
}
function listedLocalTxt(view:Json,target:ExactFile|null){
 if(!target)return false;
 const sources=rows(obj(view.snapshot).sources).filter(row=>row.id===target.fileId),source=sources[0];
 return sources.length===1&&source.sha256===target.sha256&&source.role==='source'&&source.availability==='available'&&
  source.sourceKind==='content_uri'&&source.sourceTaskId==null&&typeof source.name==='string'&&/\.txt$/i.test(source.name);
}
function wholeTxtDraftOffer(view:Json,range:DisplayedOriginalText|undefined){
 const target=exactFileTarget(obj(view.file)),offer=obj(view.txtDraftOffer),reader=obj(view.originalFile),read=obj(reader.information);
 if(!range||!target||!privateTxtPositionScope(view)||!listedLocalTxt(view,target)||view.file.mimeType!=='text/plain'||
    view.busy!==false||view.exportPending!==false||reader.busy!==false||reader.error||!matchesTextViewer(view,range)||
    offer.viewerId!==range.viewerId||typeof offer.receipt!=='string'||!offer.receipt||typeof offer.library!=='string'||!offer.library||
    range.encoding!=='UTF-8'||range.offset!==0||read.encoding!=='UTF-8'||read.offset!==0||
    read.atEnd!==true||read.nextOffset!==null||read.originalBytesChanged!==false||
    read.sourceIdentity!==range.identity||read.sha256!==target.sha256||typeof read.content!=='string'||!read.content.trim()||
    read.content.length>12000||read.decodedCharacters!==read.content.length)return;
 return {source:{...target,receipt:offer.receipt as string},library:offer.library as string,body:read.content as string};
}
function workOriginalEntryFrame(view:Json,taskId:string){
 return view.taskId===taskId&&view.fromWorkResult===true&&view.originalReturn==='entry'&&view.restoredWorkReturn===false&&
  view.group===false&&view.team===false&&view.deviceOriginalOnly===false&&view.resultFilesOnly===false&&
  view.historyResultOnly===false&&view.reportResultOnly===false&&view.choosingSource===false&&
  !view.documentDraft&&!view.documentOpen&&!view.processingOpen&&!view.comparison;
}
function workTxtPositionScope(view:Json,work?:WorkTextVisit){
 const file=obj(view.file),snapshot=obj(view.snapshot),version=exactFileTarget(file);
 return work&&view.open===true&&view.ready===true&&typeof view.windowId==='string'&&!!view.windowId&&workOriginalEntryFrame(view,work.taskId)&&
  snapshot.taskId===work.taskId&&snapshot.taskRunId===work.runId&&snapshot.coordinatorEpoch===work.epoch&&
  file.role==='result'&&file.availability==='available'&&file.runProvenance==='current'&&
  file.sourceKind==='device_received_file'&&file.sourceRunEvidence==='original_device_file_receive'&&
  file.sourceTaskId===work.taskId&&file.sourceTaskRunId===work.runId&&file.sourceCoordinatorEpoch===work.epoch&&
  typeof file.name==='string'&&/\.txt$/i.test(file.name)&&version?
  JSON.stringify(['work',work.visit,version.fileId,version.sha256]):'';
}
// Only the already-mounted native preview of one current ordinary Work DOCX.
function workWordCopyScope(native:ReturnType<typeof bridge.getState>,view:Json,taskId?:string){
 const state=native.state,window=obj(native.view),catalog=obj(view.snapshot),file=obj(view.file),reader=obj(view.originalFile);
 const {taskRunId:runId,coordinatorEpoch:epoch}=catalog,target=exactFileTarget(file);
 const mime='application/vnd.openxmlformats-officedocument.wordprocessingml.document';
 if(!taskId||document.hidden||native.host?.ready!==true||window.taskId!==taskId||window.section!==null||
    !Number.isSafeInteger(window.resumeRevision)||!Number.isSafeInteger(state.requestedTaskRevision)||
    state.conversationReader||state.chatAttachmentPickerOpen!==false||!workOriginalEntryFrame(view,taskId)||
    view.open!==true||view.ready!==true||view.busy!==false||view.exportPending!==false||view.error||view.canSaveOriginal!==true||
    typeof view.windowId!=='string'||!view.windowId||catalog.taskId!==taskId||catalog.taskStatus!=='completed'||
    typeof runId!=='string'||!runId||!Number.isSafeInteger(epoch)||epoch<1||!target||file.mimeType!==mime||
    reader.kind!=='office'||reader.mimeType!==mime||reader.sourceUnavailable!==false||reader.busy!==false||reader.error||
    reader.sha256!==target.sha256||typeof reader.id!=='string'||!reader.id||
    typeof reader.identity!=='string'||!/^[a-f0-9]{64}$/.test(reader.identity))return '';
 const lists=rows(state.tasks).filter(task=>task.id===taskId),task=lists[0],raw=obj(task?.raw),work=obj(raw.ownerWork);
 if(lists.length!==1||task?.status!=='completed'||raw.id!==taskId||raw.runId!==runId||raw.coordinatorEpoch!==epoch||
    raw.status!=='completed'||raw.actorId!=='owner'||raw.spaceId!=='private'||raw.kind!=='assistant'||
    raw.executionMode!=='capabilities'||raw.executionRole!=='owner_work'||raw.taskProjection!=='detail_window'||
    raw.taskCoreReadLimited!==false||raw.auditBodyIncluded!==true||raw.metadataLimited===true||
    raw.contextAvailable!==true||raw.contextWithdrawn===true||raw.sourceCurrentReadRequired===true||
    raw.cancelRequested!==false||raw.hasUnresolvedOperations!==false||
    typeof raw.conversationId!=='string'||!raw.conversationId.trim()||work.format!=='pocketworld.owner-work.v1'||
    work.workTaskId!==taskId||work.conversationId!==raw.conversationId||work.relatedSourcesUnavailable===true||
    !Array.isArray(raw.ownerWorkTeams)||raw.ownerWorkTeams.length!==0||raw.ownerWorkTeamsUnavailable===true||
    rows(raw.relatedTasks).length>0||(work.relatedTaskCount??0)!==0||
    ['origin','parentTaskId','teamRunId','memberId','teamAssignment','developmentContext','ownerWorkPreparation',
     'ownerWorkTakeover','ownerResultHandling','ownerPhoneControl','ownerDeviceWork','workProfileRef','workProfileIntent','projectMemoryRead','teamWriteScope','sourceLearningJob']
     .some(key=>raw[key]!=null))return '';
 const inRun=(row:Json)=>row.id===target.fileId&&row.sha256===target.sha256&&row.mimeType===mime&&row.role==='result'&&
  row.availability==='available'&&row.sourceKind==='created_document'&&row.runProvenance==='current'&&
  row.sourceTaskId===taskId&&row.sourceTaskRunId===runId&&(row.sourceCoordinatorEpoch==null||row.sourceCoordinatorEpoch===epoch);
 const files=rows(catalog.artifacts).filter(row=>row.id===target.fileId);
 if(!inRun(file)||files.length!==1||!inRun(obj(files[0])))return '';
 return JSON.stringify([bridge.getDocumentEpoch(),window.resumeRevision,state.requestedTaskRevision,view.windowId,
  taskId,runId,epoch,raw.conversationId,target.fileId,target.sha256,reader.id,reader.identity]);
}
function matchesTextViewer(view:Json,range:DisplayedOriginalText){
 const original=obj(view.originalFile);
 return view.windowId===range.windowId&&view.canSaveOriginal===true&&original.kind==='text'&&original.sourceUnavailable===false&&
  typeof range.viewerId==='string'&&!!range.viewerId&&original.id===range.viewerId&&original.identity===range.identity&&
  original.sha256===range.sha256&&range.sha256===view.file?.sha256&&typeof range.identity==='string'&&/^[a-f0-9]{64}$/.test(range.identity);
}
const catalogFormats=[{id:'all',label:'全部类型'},{id:'document',label:'文档'},{id:'table',label:'表格'},
 {id:'text',label:'文本'},{id:'media',label:'图片和音视频'},{id:'other',label:'其他文件'}];
const importMessages:Record<string,string>={
 selecting:'等待系统文件选择。确认前还没有导入。',
 selection_unconfirmed:'无法确认系统选择器是否打开。请在原选择器完成或取消，不会自动重开。',
 registering:'已收到系统选择结果，正在核对并保存到本机资料库。',
 cancelled:'已取消文件选择，没有导入。',
 no_selection:'系统没有返回可接收的文件，没有开始导入。',
 not_started:'系统文件选择器未启动，没有开始导入。',
 busy_other_selection:'另一项文件选择或导入还未结束，本次没有开始。',
 unconfirmed:'这次登记结果尚未完整确认。请先重新读取资料库，不要重复导入。',
};
const compilerOutputRow=(file:Json)=>file.sourceKind==='managed_build_output'&&file.sourceRunEvidence==='original_node_compiler_output';
const pythonOutputRow=(file:Json)=>(file.sourceKind||file.source?.kind)==='owner_python_output_text';
const mayReferenceOriginal=(view:Json)=>!compilerOutputRow(obj(view.file))&&!pythonOutputRow(obj(view.file))&&
 (view.canSaveOriginal===true||view.historyResultOnly===true&&view.canReferenceHistoryOriginal===true);
function draftContainsFile(target:FileDraftTarget,file:{fileId:string;sha256:string}){
 const native=bridge.getState(),draft=obj(native.composer);
 return draft.ready===true&&draft.key===target.key&&draft.epoch===target.epoch&&
  draft.conversationId===target.conversationId&&String(native.state.conversationId||'')===target.conversationId&&
  Number.isSafeInteger(draft.revision)&&draft.revision>=target.revision&&
  rows(draft.objectRefs).some(ref=>ref.kind==='artifact'&&ref.id===file.fileId&&ref.sha256===file.sha256);
}
const fileOrigins:Record<string,string>={
 created_text:'生成的文本',created_document:'生成的文档',document_extract:'文档摘录',generated_media:'生成的媒体',
 device_received_file:'来自这次原设备工作',derived_media:'原件加工成果',managed_build_output:'构建产物',remote_result_snapshot:'已保存的远端成果',
 document_source:'文档编辑源，不是已生成的文档',owner_python_output_text:'本机运行输出',
};
const editableDocumentMimes=new Set([
 'application/pdf','application/vnd.openxmlformats-officedocument.wordprocessingml.document',
 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
]);
export function matchesStandaloneDocumentBook(file:Json,document:StandaloneDocument){
 const source=obj(file.source),publication=obj(source.nativePublication),id=document.taskId;
 return file.principalId==='owner'&&file.spaceId==='private'&&file.outputSourceRetired!==true&&
  file.mimeType===document.mimeType&&file.size===document.size&&source.kind==='created_document'&&!Object.hasOwn(source,'groupSpaceId')&&
  source.taskId===id&&source.runId===id&&source.operationId===id&&publication.role==='document_output'&&
  publication.taskId===id&&publication.runId===id&&publication.operationId===id&&
  typeof source.planDigest==='string'&&/^[a-f0-9]{64}$/.test(source.planDigest)&&publication.planDigest===source.planDigest;
}
function standaloneDocumentSelection(view:Json):StandaloneDocument|undefined{
 const scope=obj(view.snapshot),file=obj(view.file),reader=obj(view.originalFile),id=view.taskId;
 if(view.open!==true||view.ready!==true||view.canSaveOriginal!==true||
    view.group!==false||view.team!==false||view.deviceOriginalOnly!==false||view.historyResultOnly!==false||view.reportResultOnly!==false||
    view.resultFilesOnly!==false||view.fromWorkResult!==false||view.choosingSource!==false||
    typeof id!=='string'||!/^[a-f0-9-]{36}$/.test(id)||scope.taskId!==id||scope.taskRunId!==id||
    scope.coordinatorEpoch!==1||scope.originalDocumentActionId!==id||!exactFileTarget(file))return;
 const kind=file.mimeType==='application/pdf'?'pdf':
    file.mimeType==='application/vnd.openxmlformats-officedocument.wordprocessingml.document'?'office':'';
 if(!kind||reader.kind!==kind||reader.sourceUnavailable!==false||!reader.id||
    reader.sha256!==file.sha256||reader.mimeType!==file.mimeType||reader.size!==file.size||
    !Number.isSafeInteger(file.size)||file.size<=0)return;
 const document={taskId:id,mimeType:file.mimeType,size:file.size};
 const listed=file.role==='result'&&file.availability==='available'&&file.sourceKind==='created_document'&&
    file.sourceTaskId===id&&file.sourceTaskRunId===id&&file.sourceOperationId===id;
 const rechecked=file.found===true&&file.originalPublication===true&&file.taskId===id&&matchesStandaloneDocumentBook(file,document);
 return listed||rechecked?document:undefined;
}
function documentSourceKey(record:Json,source:Json=record){
 const file=exactFileTarget(source),scope=[record.sourceTaskId,record.sourceTaskRunId,record.sourceOperationId];
 return file&&scope.every(key=>typeof key==='string'&&key)?JSON.stringify([...scope,file.fileId,file.sha256]):'';
}
function libraryDocumentDetail(view:Json){
 return privateLibraryWindow(view)&&!!attachmentFrame(view)&&view.restoredWorkReturn===false&&view.originalFile==null&&
  view.documentOpen===false&&view.documentDraft==null&&view.processingOpen===false&&view.comparison==null;
}
function libraryGeneratedDocument(row:Json){
 return row.role==='result'&&row.availability==='available'&&row.sourceKind==='created_document'&&
  typeof row.sourceOperationId==='string'&&!!row.sourceOperationId&&!!documentVersionKey(row)&&
  (row.mimeType==='application/pdf'||row.mimeType==='application/vnd.openxmlformats-officedocument.wordprocessingml.document');
}
function listedLibraryDocument(view:Json,pin:ExactFile){
 const catalog=obj(view.snapshot),matches=rows(catalog.artifacts).filter(row=>row.id===pin.fileId),record=matches[0];
 return matches.length===1&&record.sha256===pin.sha256&&libraryGeneratedDocument(record)&&
  !rows(catalog.sources).some(row=>row.id===pin.fileId)?record:undefined;
}
function listedDocumentParent(view:Json){
 if(!libraryDocumentDetail(view)||view.originalReturn!=='list')return;
 const file=obj(view.file),child=exactFileTarget(file),parent=exactFileTarget(obj(file.parentArtifact));
 if(!child||!parent||parent.fileId===child.fileId||!libraryGeneratedDocument(file))return;
 const listedChild=listedLibraryDocument(view,child),listedParent=listedLibraryDocument(view,parent);
 if(!listedChild||!listedParent||listedChild.sourceOperationId!==file.sourceOperationId||
    exactFileKey(obj(listedChild.parentArtifact))!==exactFileKey(obj(file.parentArtifact)))return;
 return {child,parent,parentName:String(listedParent.name||'原件')};
}
function excerptPdfSource(view:Json,taskId?:string){
 const catalog=obj(view.snapshot),file=obj(view.file),excerpt=exactFileTarget(file);
 if(!attachmentFrame(view)||view.open!==true||catalog.taskId!==(taskId??'')||
    (taskId?view.taskId!==taskId||file.sourceTaskId!==taskId||file.runProvenance!=='unrecorded':
     view.taskId!=null||view.fromWorkResult!==false)||
    view.group!==false||view.team!==false||view.deviceOriginalOnly!==false||
    view.historyResultOnly!==false||view.reportResultOnly!==false||view.resultFilesOnly!==false||view.choosingSource!==false||
    view.originalFile!=null||view.documentOpen!==false||view.documentDraft!=null||view.processingOpen!==false||view.comparison!=null||
    !excerpt||file.role!=='result'||file.availability!=='available'||file.sourceKind!=='document_extract'||file.mimeType!=='text/plain')return;
 // Use the native catalog's saved extract, not a filename, its text, or an archived alias.
 const copies=rows(catalog.artifacts).filter(row=>row?.id===excerpt.fileId);
 const record=copies[0];
 if(copies.length!==1||record.sha256!==excerpt.sha256||record.role!=='result'||record.availability!=='available'||
    record.sourceKind!=='document_extract'||record.mimeType!=='text/plain'||(taskId?record.sourceTaskId!==taskId:record.sourceTaskId!=null)||
    record.sourceTaskRunId!=null||record.sourceOperationId!=null||typeof record.sourceAttachmentId!=='string'||
    record.sourceAttachmentId===excerpt.fileId||record.sourceAttachmentId!==file.sourceAttachmentId||
    record.sourceSha256!==file.sourceSha256)return;
 const parents=rows(catalog.sources).filter(row=>row?.id===record.sourceAttachmentId);
 const source=parents[0];
 if(parents.length!==1||source.role!=='source'||source.availability!=='available'||source.mimeType!=='application/pdf'||
    source.sourceKind!=='content_uri'||source.sourceTaskId!=null||source.sha256!==record.sourceSha256)return;
 const target=exactFileTarget(source);
 return target?{excerpt,target}:undefined;
}
function justSavedWorkPdfSource(native:ReturnType<typeof bridge.getState>,view:Json,taskId?:string){
 const current=obj(native.view),catalog=obj(view.snapshot),file=obj(view.file),receipt=obj(catalog.savedPdfExcerptHere);
 if(!taskId||current.section!==null||current.taskId!==taskId||native.state.chatAttachmentPickerOpen!==false||
    native.state.conversationReader||view.restoredWorkReturn!==false||catalog.taskStatus!=='completed'||
    !exactFileTarget(receipt)||receipt.id!==file.id||receipt.sha256!==file.sha256)return;
 const work=rows(native.state.tasks).filter(row=>row?.id===taskId),original=obj(work[0]?.raw);
 if(work.length!==1||original.id!==taskId||original.actorId!=='owner'||original.spaceId!=='private'||original.kind!=='assistant'||
    original.status!=='completed'||original.taskProjection!=='detail_window'||original.taskCoreReadLimited!==false||original.auditBodyIncluded!==true||
    original.contextAvailable===false||original.executionRole==='owner_work'&&original.contextAvailable!==true||
    original.contextWithdrawn===true||original.sourceCurrentReadRequired===true||
    original.origin!=null||original.parentTaskId!=null||original.teamRunId!=null||original.memberId!=null||original.developmentContext!=null||
    original.ownerDeviceWork!=null||original.ownerPhoneControl!=null||original.directWorkspace!=null||original.directPhone!=null||
    typeof original.runId!=='string'||!original.runId||original.runId!==catalog.taskRunId||
    !Number.isSafeInteger(original.coordinatorEpoch)||original.coordinatorEpoch<1||original.coordinatorEpoch!==catalog.coordinatorEpoch)return;
 return excerptPdfSource(view,taskId);
}
const isLiveMessageOriginal=(view:Json)=>view.messagePdfOnly===true||view.messageInputDocumentOnly===true||view.messageReplyDocumentOnly===true||view.savedChatLinkedOnly===true;

/** One file surface for conversation attachments, task outcomes and the private library. */
export function AttachmentWorkspace({state,registerBack,onReturnToSearch,onReturnFocus,returnToDeliveredFiles,openTaskId,canReturnToOpenWork,draftTarget,resultTopic,keepResultReadingDraft,onStageFile,onOpenDraft,onUseResult,onChooseResultAttachment,onAdoptFileNote,onVerifyReportFileWork,onChooseAttachment,standaloneDocumentAttachmentDraft,replyAttachmentDraft,readWorkTextVisit,quotedDraftArrival,onOriginalBookReply}:Props) {
 const incoming=obj(state.attachmentWorkspace),retained=useRef<Json|null>(null);
 const lastLiveMessageFileWindow=useRef<string|undefined>(undefined);
 const lastHistoricalReply=useRef<{reader:number;visit:string}|undefined>(undefined);
 if(incoming.open===true&&incoming.ready===true){
  lastLiveMessageFileWindow.current=isLiveMessageOriginal(incoming)&&typeof incoming.windowId==='string'?incoming.windowId:undefined;
  const history=obj(incoming.historicalReply);
  lastHistoricalReply.current=Number.isSafeInteger(history.reader)&&history.reader>0&&typeof history.visit==='string'?
   {reader:history.reader,visit:history.visit}:undefined;
 }
 if(!incoming.open)retained.current=null;else if(incoming.ready)retained.current=incoming;
 // Keep the same editor mounted while native window authority is temporarily revoked.
 const view=incoming.open&&!incoming.ready&&retained.current?{...retained.current,ready:false}:incoming,file=obj(view.file),snapshot=obj(view.snapshot);
 const [error,setError]=useState(''),[pending,setPending]=useState(0),[page,setPage]=useState('1');
 const [localDrafts,setLocalDrafts]=useState<{visit:StandaloneVisit;loading:boolean;initial?:Json;initialTxt?:{name:string;body:string}}>();
 const draftVisit=useRef<StandaloneVisit|undefined>(undefined),draftDeskExit=useRef<((close:boolean)=>void)|null>(null);
 const usedQuotedDraft=useRef<QuotedDraftArrival|undefined>(undefined);
 const draftDeskReturn=useRef<{windowId:string;entry:StandaloneVisit['entry']}|undefined>(undefined);
 const pendingDraftGeneration=standalonePendingDocument(view);
 const draftDesk=localDrafts&&standaloneLibrarySurface(view)&&!onReturnToSearch&&state.chatAttachmentPickerOpen===false&&
  pendingDraftGeneration===localDrafts.visit.pendingDocument&&
  (localDrafts.visit.entry!=='resume'&&localDrafts.visit.entry!=='quoted'&&localDrafts.visit.entry!=='txt'||
   incoming.ready===true&&incoming.windowId===localDrafts.visit.windowId&&incoming.standaloneDraftLibrary===true&&
   localDrafts.visit.document===bridge.getDocumentEpoch()&&
   (localDrafts.visit.entry!=='txt'||incoming.txtDraftLanding===localDrafts.visit.txtSource?.receipt))&&
  (incoming.ready!==true||incoming.standaloneDraftLibraryVisit===localDrafts.visit.presentation)?localDrafts:undefined;
 const draftShelfSurface=view.focusedStandaloneDraft!==true&&standaloneLibrarySurface(view)&&view.ready===true&&typeof view.standaloneDraftLibraryVisit==='string'&&
  !!view.standaloneDraftLibraryVisit&&!onReturnToSearch&&state.chatAttachmentPickerOpen===false;
 const offerDraftDesk=draftShelfSurface&&view.standaloneDraftLibrary===true;
 const mayReenterDraftShelf=draftShelfSurface&&view.standaloneDraftLibrary===false&&view.busy===false&&view.exportPending===false;
 const offerDraftShelf=offerDraftDesk||mayReenterDraftShelf;
 const draftPresentation=draftDesk&&standaloneDraftPresentation(bridge.getState(),draftDesk.visit);
 const draftDeskActive=!!draftDesk&&draftPresentation?.leased===true&&draftDesk.visit.windowId===draftPresentation.frame.windowId&&
  draftDesk.visit.document===bridge.getDocumentEpoch();
 const draftDeskReadOnly=!!draftDesk&&draftPresentation?.leased===false&&draftDesk.visit.windowId!==draftPresentation.frame.windowId;
 const suspendedDraftKey=draftDeskReadOnly&&draftDesk?warmDraftKey(bridge.getState(),draftDesk.visit):undefined;
 const [catalogFilter,setCatalogFilter]=useState<{scope:string;name:string;format:string}|null>(null);
 const [draftHandoff,setDraftHandoff]=useState<DraftHandoff|null>(null);
 const draftFlight=useRef<DraftHandoff|null>(null);
 const reportFlight=useRef(false);
 const attachmentFlight=useRef(false);
 const documentParentFlight=useRef(false),generatedDraftFlight=useRef(false),txtDraftFlight=useRef(false);
 const [previewContinuation,setPreviewContinuation]=useState('');
 const [readerActionMenu,setReaderActionMenu]=useState('');
 const [workPdfCopyFeedback,setWorkPdfCopyFeedback]=useState<{key:string;message:string;error?:true}>();
 useEffect(()=>{if(!view.open){setPreviewContinuation('');setCatalogFilter(null)}},[view.open]);
 const scroller=useRef<HTMLDivElement>(null),heading=useRef<HTMLDivElement>(null),positions=useRef(new Map<string,number>()),returningToSearch=useRef(false);
 const heldCopyViewport=useRef<WorkPdfCopyAttempt|null>(null);
 const [, renderCopyViewport]=useState(0);
 const finishCopyViewport=useCallback((held:WorkPdfCopyAttempt)=>{
  if(heldCopyViewport.current!==held)return;
  heldCopyViewport.current=null;renderCopyViewport(count=>count+1);
 },[]);
 useLayoutEffect(()=>{
  // Ignore the chooser itself and synthetic input. Trusted new input in this Web document always wins.
  const steer=(event:Event)=>{
   const held=heldCopyViewport.current;if(event.isTrusted&&held)finishCopyViewport(held);
  };
  const moved=(event:Event)=>{
   const held=heldCopyViewport.current,pane=scroller.current,target=event.target;
   if(!held||!pane||!event.isTrusted||!(target instanceof HTMLElement))return;
   const outer=pane===target;
   if(!outer&&(!pane.contains(target)||!target.classList.contains('pw-original-raster')))return;
   const current=obj(bridge.getState().state.attachmentWorkspace);
   if(current.ready!==true)return;
   // The ordinary new window starts at zero. Old positions may still have a queued scroll notification.
   const old=current.windowId===held.windowId;
   const top=old?(outer?held.position.outerTop:held.position.top):0,left=old&&!outer?held.position.left:0;
   if(Math.abs(target.scrollTop-top)>1||Math.abs(target.scrollLeft-left)>1)finishCopyViewport(held);
  };
  const focus=(event:Event)=>{
   const held=heldCopyViewport.current;
   if(!held||event.target===heading.current||event.target===document.body)return;
   const current=obj(bridge.getState().state.attachmentWorkspace);
   if(current.ready===true&&current.windowId!==held.windowId)steer(event);
  };
  const events=['pointerdown','click','wheel','touchstart','touchmove','keydown','input','change'];
  events.forEach(type=>document.addEventListener(type,steer,{capture:true,passive:true}));
  document.addEventListener('scroll',moved,{capture:true,passive:true});
  document.addEventListener('focusin',focus,true);
  window.addEventListener('pagehide',steer);
  return()=>{
   events.forEach(type=>document.removeEventListener(type,steer,true));document.removeEventListener('scroll',moved,true);
   document.removeEventListener('focusin',focus,true);
   window.removeEventListener('pagehide',steer);heldCopyViewport.current=null;
  };
 },[finishCopyViewport]);
 const copyAttempt=heldCopyViewport.current;
 const copyProof=copyAttempt&&!onReturnToSearch?workPdfCopyProof(incoming,openTaskId,copyAttempt):undefined;
 const copyViewport:PdfCopyViewportReturn|undefined=copyAttempt&&copyProof?{
  ...copyProof,position:copyAttempt.position,
  current:()=>{
   const native=bridge.getState(),owner=obj(native.view);
   if(heldCopyViewport.current!==copyAttempt||document.hidden||native.host?.ready!==true||owner.section!==null||
      owner.taskId!==openTaskId)return false;
   const live=workPdfCopyProof(obj(native.state.attachmentWorkspace),openTaskId,copyAttempt);
   return !!live&&live.viewerId===copyProof.viewerId&&live.rasterId===copyProof.rasterId;
  },
  finish:()=>finishCopyViewport(copyAttempt)
 }:undefined;
 useLayoutEffect(()=>{
  const held=heldCopyViewport.current;
  if(held&&(!incoming.open||onReturnToSearch||!openTaskId||
    incoming.ready===true&&workPdfCopyScope(incoming,openTaskId)!==held.scope))finishCopyViewport(held);
 },[incoming,onReturnToSearch,openTaskId,finishCopyViewport]);
 const continuationHeading=useRef<HTMLDivElement>(null);
 const hasOriginal=Object.keys(obj(view.originalFile)).length>0;
 const passiveGenericPdf=view.passiveGenericPdf===true;
 const nativePdfPage=obj(view.pdfPageText),pairedOriginal=obj(view.originalFile),acknowledgedPage=obj(pairedOriginal.information);
 const workPdfText=workPdfTextWindow(view,openTaskId);
 const pdfPageText=privateLibraryWindow(view)||workPdfText?pairedPdfPage(view):undefined;
 const pdfTextRead=obj(view.document);
 const pdfTextCurrent=!!pdfPageText&&matchesPdfPageText(view,workPdfText?String(view.taskId):'',pdfPageText);
 const nativeWord=obj(view.wordExcerpt),wordRead=obj(view.document);
 const wordExcerpt=privateLibraryWindow(view)&&view.documentOpen===true&&pairedOriginal.kind==='office'&&
  pairedOriginal.sourceUnavailable===false&&pairedOriginal.id===nativeWord.viewerId&&pairedOriginal.sha256===file.sha256&&
  acknowledgedPage.format==='docx'&&acknowledgedPage.unit===nativeWord.unit&&acknowledgedPage.chunk===nativeWord.chunk&&
  Number.isSafeInteger(nativeWord.unit)&&nativeWord.unit>0&&Number.isSafeInteger(nativeWord.chunk)&&nativeWord.chunk>=0&&
  [1,2].includes(nativeWord.parts)?nativeWord:undefined;
 const wordPreview=wordExcerpt&&wordRead.readContext==='native_owner_preview'&&wordRead.taskId===''&&wordRead.format==='docx'&&
  wordRead.attachmentId===file.id&&wordRead.sha256===file.sha256&&wordRead.coverage==='body_paragraphs_and_tables'&&
  Array.isArray(wordRead.chunks)&&wordRead.chunks.length===wordExcerpt.parts&&
  wordRead.chunks.every((block:Json,index:number)=>block.unit===wordExcerpt.unit&&block.chunk===wordExcerpt.chunk+index);
 const mode=draftDesk?'drafts':view.documentDraft?'edit':view.comparison?'compare':view.processingOpen?'process':
  pdfPageText||wordExcerpt?'read':hasOriginal?'original':view.documentOpen?'read':file.id?'detail':'list';
 const replyDocumentOnly=view.messageReplyDocumentOnly===true, liveMessageOriginalOnly=isLiveMessageOriginal(view);
 const searchFileOriginal=liveMessageOriginalOnly&&Number.isSafeInteger(view.searchAnchorReader)&&view.searchAnchorReader>0;
 const replyTextOnly=replyDocumentOnly&&file.mimeType==='text/plain';
 const historicalReplyOriginal=replyDocumentOnly&&Number.isSafeInteger(view.historicalReply?.reader)&&
  view.historicalReply.reader>0&&typeof view.historicalReply.visit==='string';
 const reportOnly=view.reportResultOnly===true,scopedResultOnly=reportOnly||view.historyResultOnly===true||liveMessageOriginalOnly;
 const privateFiles=!view.group&&!view.team&&!view.deviceOriginalOnly;
 const listScope=privateFiles&&!view.choosingSource?JSON.stringify([view.taskId||'',snapshot.taskRunId||'',snapshot.coordinatorEpoch||0,
  view.resultFilesOnly===true,reportOnly,view.historyResultOnly===true]):'';
 const location=JSON.stringify([mode==='list'&&listScope?listScope:view.windowId,view.taskId,file.id,file.sha256,mode,
  ...(view.wordExcerptSourceOpen===true?['word-excerpt-origin']:[])]);
 const readerActionScope=JSON.stringify([location,obj(view.originalFile).id,snapshot.taskRunId,snapshot.coordinatorEpoch]);
 const listOrigin=useRef<{scope:string;collection?:string;inset:number}|null>(null);
 const displayedFile=useRef<{scope:string;target:ExactFile}|null>(null);
 const workOutput=file.role==='result'&&!!view.taskId&&(view.resultFilesOnly===true||view.fromWorkResult===true||view.taskId===openTaskId);
 const topic=workOutput&&mode==='original'&&mayReferenceOriginal(view)&&file.sourceTaskId===view.taskId&&resultTopic?.taskId===view.taskId?resultTopic:undefined;
 // Success remains attached to the same draft after native receipt advances its revision.
 const draftScope=JSON.stringify([view.windowId,view.taskId,file.id,file.sha256,topic?.conversationId,draftTarget?.key,draftTarget?.epoch,draftTarget?.conversationId]);
 const currentDraftScope=useRef(draftScope);currentDraftScope.current=draftScope;
 const currentHandoff=draftHandoff?.scope===draftScope?draftHandoff:null;
 const selection=exactFileTarget(file),draftHasFile=!!(draftTarget&&selection&&draftContainsFile(draftTarget,selection));
 const handoff=!topic&&((currentHandoff?.phase==='added'&&!draftHasFile)||(currentHandoff?.phase==='failed'&&draftHasFile))?null:currentHandoff;
 const draftBusy=draftHandoff?.phase==='adding'||draftHandoff?.phase==='opening';
 const displayedText=useRef<DisplayedOriginalText|null>(null),textReturn=useRef<TextReturn|null>(null);
 const [mountedTxtRead,setMountedTxtRead]=useState<DisplayedOriginalText>();
 const workTextReader=useRef(readWorkTextVisit);workTextReader.current=readWorkTextVisit;
 const mayRestoreText=!onReturnToSearch&&state.chatAttachmentPickerOpen===false;
 const libraryTextScope=mayRestoreText?privateTxtPositionScope(incoming):'';
 const workTextVisit=mayRestoreText&&openTaskId?readWorkTextVisit?.(openTaskId):undefined;
 const workTextScope=workTxtPositionScope(incoming,workTextVisit);
 const textScope=libraryTextScope||workTextScope;
 const textDisplayed=useCallback((range:DisplayedOriginalText)=>{
  displayedText.current=range;
  const liveTxt=obj(bridge.getState().state.attachmentWorkspace);
  if(range.encoding==='UTF-8'&&range.offset===0&&privateTxtPositionScope(liveTxt)&&liveTxt.file?.mimeType==='text/plain')
   setMountedTxtRead(prior=>prior?.viewerId===range.viewerId&&prior.windowId===range.windowId?prior:range);
  const pin=textReturn.current,body=scroller.current;
  if(!pin?.start||pin.start.viewerId!==range.viewerId||!body)return;
  textReturn.current=null; // The newly painted reader gets one chance; later reads never retarget it.
  const native=bridge.getState(),live=obj(native.state.attachmentWorkspace);
  const scope=pin.work?workTxtPositionScope(live,workTextReader.current?.(pin.work.taskId)):privateTxtPositionScope(live);
  if(document.hidden||bridge.getDocumentEpoch()!==pin.document||native.state.chatAttachmentPickerOpen!==false||
     (pin.libraryList&&!ordinaryLibraryOwner(native))||
     scope!==pin.scope||!matchesTextViewer(live,range)||obj(live.originalFile).busy!==false||
     range.encoding!==pin.range.encoding||
     range.offset!==pin.range.offset||range.identity!==pin.range.identity||body.scrollTop!==pin.start.top)return;
  // The text stays put even if the previous reader's disclosure above it was expanded.
  const textStart=pin.textStart!==undefined?textBounds(body)?.top:undefined;
  if(pin.libraryList&&textStart===undefined)return;
  body.scrollTop=textStart!==undefined&&pin.textStart!==undefined?pin.top+textStart-pin.textStart:pin.top;
 },[]);
 const rememberPosition=()=>{
  if(!view.ready||!scroller.current)return;
  const top=scroller.current.scrollTop,start=textReturn.current?.start;
  if(start&&mode==='original'&&obj(view.originalFile).id===start.viewerId&&top!==start.top)textReturn.current=null;
  positions.current.set(location,top);
 };
 useLayoutEffect(()=>{
  const retainedText=textReturn.current;
  // The overlay can be empty while this same Work reads the file selected by
  // the owner. Do not carry its position to any other Work visit or original.
  if(retainedText?.work&&(workTextVisit?.visit!==retainedText.work.visit||bridge.getDocumentEpoch()!==retainedText.document||
     view.open&&view.ready&&(!workOriginalEntryFrame(view,retainedText.work.taskId)||!!file.id&&workTextScope!==retainedText.scope)))
   textReturn.current=null;
 },[view.open,view.ready,location,workTextVisit?.visit,workTextScope]);
 useLayoutEffect(()=>{
  if(!view.open)positions.current.clear();
  const retained=textReturn.current;
  const sameLibrary=retained?.libraryList&&mayRestoreText&&ordinaryLibraryOwner(bridge.getState())&&
   view.windowId===retained.range.windowId&&bridge.getDocumentEpoch()===retained.document&&
   (privateTxtLibraryList(view)||retained.scope===libraryTextScope);
  if(!retained?.work&&(retained?.libraryList?!sameLibrary:!view.open||!view.ready||retained?.scope!==libraryTextScope))textReturn.current=null;
  if(!view.open||!view.ready){displayedText.current=null;return;}
  const pin=textReturn.current,body=scroller.current;
  if(body){
   if(mode==='original'&&pin?.reopening&&(!pin.work||view.windowId!==pin.range.windowId)){
    const now=obj(view.originalFile);
    // Native has admitted a new viewer for the same file, but its text may not have been laid out yet.
    body.scrollTop=0;
    if(typeof now.id==='string'&&now.id!==pin.range.viewerId&&matchesTextViewer(view,{...pin.range,viewerId:now.id}))
     pin.start={viewerId:now.id,top:body.scrollTop};
    else textReturn.current=null;
   }else body.scrollTop=positions.current.get(location)||0;
  }
  heading.current?.focus({preventScroll:true});
 },[view.open,view.ready,location]);
 useEffect(()=>{if(view.open)returningToSearch.current=false},[view.windowId]);
 const alive=useRef(true),tail=useRef<Promise<unknown>>(Promise.resolve()),beforeBack=useRef<(()=>Promise<AttachmentFrame|undefined>)|null>(null),latest=useRef(view);latest.current=view;
 const exactWorkReturn=useRef(canReturnToOpenWork);exactWorkReturn.current=canReturnToOpenWork;
 const backFlight=useRef(false);
 const bookReplyExit=useRef<BookReplyExit|null>(null);
 useLayoutEffect(()=>{
  const exit=bookReplyExit.current;
  if(exit&&incoming.open===true&&incoming.windowId!==exit.windowId)bookReplyExit.current=null;
 },[incoming.open,incoming.windowId]);
 const finishBookReplyExit=(exit:BookReplyExit)=>{
  if(bookReplyExit.current!==exit||!exit.accepted||!exit.exited||exit.scheduled)return;
  exit.scheduled=true;
  requestAnimationFrame(()=>{
   if(bookReplyExit.current!==exit)return;
   bookReplyExit.current=null;exit.link.restore();
  });
 };
 const act=useCallback((command:string,args:Json={},after?:AttachmentFrame,showError=true,stillCurrent?:(shown:Json)=>boolean)=>{
  let payload:Json;
  try{payload=attachmentActionPayload(after??attachmentFrame(view),command,structuredClone(args))}catch(cause){if(showError)setError(cause instanceof Error?cause.message:'原文件页面已变化');return Promise.reject(cause)}
  const windowId=payload.windowId,epoch=bridge.getDocumentEpoch();setPending(x=>x+1);setError('');
  const operation=tail.current.catch(()=>{}).then(async()=>{
   const current=obj(bridge.getState().state.attachmentWorkspace);
   if(!alive.current||!current.open||!current.ready||current.windowId!==windowId||bridge.getDocumentEpoch()!==epoch||document.visibilityState==='hidden'||stillCurrent?.(current)===false)throw new Error('原文件窗口已变化，请重新核对');
   // Return to a Work still directly underneath this exact frame as Back does, not by reopening it.
   const closeOnly=command==='continueTask'&&current.busy===false&&current.exportPending===false&&exactWorkReturn.current?.(current);
   const result=obj(await bridge.command('attachmentWorkspace.action',closeOnly?{...payload,command:'close'}:payload));
   if(!alive.current||bridge.getDocumentEpoch()!==epoch)throw new Error('原页面已变化，请回到文件库核对');
   return result;
  });
  tail.current=operation;
  return operation.catch(cause=>{if(alive.current&&showError)setError(cause instanceof Error?cause.message:'原文件操作未确认');throw cause}).finally(()=>{if(alive.current)setPending(x=>Math.max(0,x-1))});
 },[view.windowId,view.frameId,view.ready]);
 const draftPresentationFrame=(visit:StandaloneVisit)=>draftVisit.current===visit?standaloneDraftPresentation(bridge.getState(),visit):undefined;
 const draftFrame=(visit:StandaloneVisit)=>{
  const shown=draftPresentationFrame(visit);
  return shown?.leased===true&&shown.frame.windowId===visit.windowId&&visit.document===bridge.getDocumentEpoch()?shown.frame:undefined;
 };
 const draftAct=async(visit:StandaloneVisit,command:string,args:Json={})=>{
  const frame=draftFrame(visit);if(!frame)throw new Error('原草稿窗口已关闭');
  const result=await act(command,args,frame,false);
  // A positively entered generation retires the draft editor and is displayed by the existing file result workflow.
  if(command==='drafts.createDocument'&&result.started===true){
   if(draftVisit.current===visit){draftVisit.current=undefined;setLocalDrafts(undefined)}
  }else if(!draftFrame(visit))throw new Error('原草稿窗口已变化');
  return result;
 };
 const openDraftDesk=(entry:Exclude<StandaloneVisit['entry'],'resume'|'quoted'|'txt'>)=>{
  const frame=attachmentFrame(view),reenter=entry==='catalog'&&mayReenterDraftShelf;
  if((!offerDraftDesk&&!reenter)||(entry==='blank'&&pendingDraftGeneration)||!frame||draftVisit.current||pending||generatedDraftFlight.current||backFlight.current)return;
  const visit:StandaloneVisit={windowId:frame.windowId,document:bridge.getDocumentEpoch(),page:document,
   presentation:view.standaloneDraftLibraryVisit,entry,pendingDocument:pendingDraftGeneration};
  const shown=standaloneDraftPresentation(bridge.getState(),visit);
  if(!shown||shown.leased!==!reenter||shown.frame.windowId!==visit.windowId)return;
  rememberPosition();
  draftVisit.current=visit;setLocalDrafts({visit,loading:true});
  const opening=reenter?act('drafts.list',{reenterVisit:visit.presentation},shown.frame,false).then(async result=>{
   if(result.reenteredVisit!==visit.presentation||result.reenteredWindow!==visit.windowId||!await waitForDraftLibraryFrame(visit))
    throw new Error('本机文档草稿窗口尚未核对');
   return result;
  }):draftAct(visit,entry==='blank'?'drafts.new':'drafts.list');
  void opening.then(initial=>{
   if(draftVisit.current===visit)setLocalDrafts({visit,loading:false,initial});
  }).catch(()=>{
   if(draftVisit.current!==visit)return;
   if(!reenter){setLocalDrafts({visit,loading:false});return}
   draftVisit.current=undefined;setLocalDrafts(undefined);
   const present=standaloneDraftPresentation(bridge.getState(),visit);
   if(bridge.getDocumentEpoch()===visit.document&&present?.frame.windowId===visit.windowId)
    setError('本机文档草稿尚未核对。文件清单保留，可以从这里再打开。');
  });
 };
 useLayoutEffect(()=>{
  const arrival=quotedDraftArrival;
  if(!arrival||usedQuotedDraft.current===arrival||draftVisit.current||backFlight.current||onReturnToSearch||!arrival.current()||
     incoming.open!==true||incoming.ready!==true||incoming.focusedStandaloneDraft!==true||incoming.windowId!==arrival.windowId||
     incoming.standaloneDraftLibraryVisit!==arrival.library)return;
  const visit:StandaloneVisit={windowId:arrival.windowId,document:arrival.document,page:document,
   presentation:arrival.library,entry:'quoted'};
  const native=bridge.getState(),current=obj(native.state.attachmentWorkspace),shown=standaloneDraftPresentation(native,visit);
  if(shown?.leased!==true||shown.frame.windowId!==visit.windowId||bridge.getDocumentEpoch()!==visit.document||
     current.busy!==false||current.exportPending!==false)return;
  // Only the just-acknowledged explicit quote action reaches this owned single-draft frame.
  // No catalogue/name lookup and no repeat when the exact read isn't confirmed.
  usedQuotedDraft.current=arrival;draftVisit.current=visit;setLocalDrafts({visit,loading:true});
  void draftAct(visit,'drafts.openQuotedSource',{library:arrival.library}).then(initial=>{
   if(draftVisit.current===visit)setLocalDrafts({visit,loading:false,
    initial:arrival.current()&&initial.library===arrival.library?initial:undefined});
  }).catch(()=>{if(draftVisit.current===visit)setLocalDrafts({visit,loading:false})});
 },[quotedDraftArrival,incoming,onReturnToSearch]);
 const resumeSavedDraft=async(visit:StandaloneVisit,key:string)=>{
  const native=bridge.getState(),shown=draftPresentationFrame(visit);
  if(!shown||shown.leased!==false||warmDraftKey(native,visit)!==key||backFlight.current)throw new Error('原草稿窗口已变化');
  const fresh:StandaloneVisit={...visit,windowId:shown.frame.windowId,document:bridge.getDocumentEpoch(),warmReentry:true};
  // Track the exact new native window before dispatch, even if the reply is lost. The child hides the old editor until a verified read.
  draftVisit.current=fresh;
  setLocalDrafts(value=>value?.visit===visit?{...value,visit:fresh}:value);
  const result=await act('drafts.resumeSaved',{resumeKey:key},shown.frame,false);
  if(result.resumedKey!==key||result.resumedWindow!==fresh.windowId||!await waitForDraftLibraryFrame(fresh)||!draftFrame(fresh))
   throw new Error('未核对到同一份已保留的草稿');
  return result;
 };
 const returnGeneratedDraft=async()=>{
  const shown=generatedDraftHandoff(view);
  if(!shown||onReturnToSearch||busy||draftBusy||draftVisit.current||backFlight.current||generatedDraftFlight.current)return;
  const native=bridge.getState(),live=obj(native.state.attachmentWorkspace),fresh=generatedDraftHandoff(live),frame=attachmentFrame(live);
  if(!frame||!fresh||!ordinaryLibraryOwner(native)||native.state.filePath!=null||native.host?.ready!==true||
      frame.windowId!==view.windowId||fresh.handle!==shown.handle||fresh.library!==shown.library||
      fresh.fileId!==shown.fileId||fresh.sha256!==shown.sha256||document.hidden)return;
  const visit:StandaloneVisit={windowId:frame.windowId,document:bridge.getDocumentEpoch(),page:document,presentation:fresh.library,entry:'resume'};
  const sameVisit=()=>alive.current&&!document.hidden&&bridge.getDocumentEpoch()===visit.document&&
   ordinaryLibraryOwner(bridge.getState())&&obj(bridge.getState().state.attachmentWorkspace).windowId===visit.windowId;
  generatedDraftFlight.current=true;
  try{
   const receipt=await act('drafts.returnGenerated',{returnHandle:fresh.handle},frame,false);
   if(!sameVisit())return;
   if(receipt.returned!==true||receipt.returnHandle!==fresh.handle||receipt.library!==visit.presentation||
      receipt.windowId!==visit.windowId||!await waitForDraftLibraryFrame(visit))throw new Error();
   if(!sameVisit()||draftVisit.current||backFlight.current)return;
   // Mount the same desk first. If the second acknowledgement is lost, Back still releases
   // only this native draft frame instead of leaving a hidden editor in the Library.
   draftVisit.current=visit;setLocalDrafts({visit,loading:true});
   try{
    const initial=await draftAct(visit,'drafts.openGeneratedSource',{returnHandle:fresh.handle});
    if(draftVisit.current===visit)setLocalDrafts({visit,loading:false,initial});
   }catch{
    if(draftVisit.current===visit)setLocalDrafts({visit,loading:false});
   }
  }catch{
   if(sameVisit())setError('未核对到生成前的原草稿，没有打开其他版本。可到「本机文档草稿」核对。');
  }finally{generatedDraftFlight.current=false}
 };
 const leaveDraftDesk=async(visit:StandaloneVisit,close:boolean)=>{
  const current=draftFrame(visit);
  if(visit.entry==='txt'){
   const source=visit.txtSource;
   if(!current||!source)throw new Error('原草稿窗口已变化');
   const result=await act('drafts.exitTxtEditor',{receipt:source.receipt,close},current,false);
   if(result.left!==true||result.closed!==close||result.receipt!==source.receipt||result.windowId!==visit.windowId||
      result.fileId!==source.fileId||result.sha256!==source.sha256)throw new Error('没有核对到原 TXT 的退出回执');
   if(draftVisit.current===visit){draftVisit.current=undefined;setLocalDrafts(undefined)}
   return;
  }
  if(current){
   const result=await draftAct(visit,'drafts.leave');if(result.left!==true)throw new Error('原草稿窗口尚未退出');
  }
  const shown=draftPresentationFrame(visit);
  if(close){
   // A revoked editor is gone. Only the fresh ordinary frame for this same Library can close it.
   const frame=current&&shown?.leased===true&&shown.frame.windowId===current.windowId?shown.frame:
    shown?.leased===false&&(shown.frame.windowId!==visit.windowId||visit.warmReentry)?shown.frame:undefined;
   if(!frame)throw new Error('原文件库尚未核对，没有关闭其他页面。');
   const result=await act('close',{},frame,false);
   if(result.accepted!==true||obj(result.workspace).open!==false)throw new Error('原文件库尚未确认关闭。');
  }else if(shown)draftDeskReturn.current={windowId:shown.frame.windowId,entry:visit.entry};
  if(draftVisit.current===visit){draftVisit.current=undefined;setLocalDrafts(undefined)}
 };
 useLayoutEffect(()=>{
  // The bridge acknowledgement and current native projection may reach React in separate renders.
  if(localDrafts&&!draftDesk&&!(localDrafts.visit.entry==='txt'&&draftFrame(localDrafts.visit))){
   draftVisit.current=undefined;setLocalDrafts(undefined)
  }
  const returned=draftDeskReturn.current;
  if(!draftDesk&&returned){
   if(returned.windowId===view.windowId){
    const selector=returned.entry==='blank'?'button[data-new-document-draft]':'button[data-local-document-drafts]';
    const entry=offerDraftShelf?scroller.current?.querySelector<HTMLButtonElement>(selector):null;
    (entry??heading.current)?.focus({preventScroll:true});
   }
   draftDeskReturn.current=undefined;
  }
 },[localDrafts,draftDesk,offerDraftShelf,view.windowId]);
 const run=(command:string,args:Json={})=>{
  const pin=textReturn.current;
  let reopenFrame:AttachmentFrame|undefined;
  if(pin?.libraryList&&mode==='list'){
   const native=bridge.getState(),live=obj(native.state.attachmentWorkspace),target=exactFileTarget({id:args.fileId,sha256:args.sha256});
   if(command==='openOriginal'&&pin.libraryList==='listed'&&mayRestoreText&&ordinaryLibraryOwner(native)&&!document.hidden&&
      bridge.getDocumentEpoch()===pin.document&&live.windowId===view.windowId&&live.windowId===pin.range.windowId&&
      live.busy===false&&live.exportPending===false&&!live.error&&privateTxtLibraryList(live)&&target&&
      pin.scope===JSON.stringify([live.windowId,target.fileId,target.sha256])&&listedLocalTxt(live,target)&&
      rows(live.readableLibrarySources).some(row=>row.id===target.fileId&&row.sha256===target.sha256)){
    pin.reopening=true;reopenFrame=attachmentFrame(live);
   }else textReturn.current=null;
  }else if(libraryTextScope&&(command==='originalDetails'||command==='openOriginal'&&pin)){
   const native=bridge.getState(),live=obj(native.state.attachmentWorkspace),scope=privateTxtPositionScope(live),documentEpoch=bridge.getDocumentEpoch();
   const same=!document.hidden&&scope===libraryTextScope&&native.state.chatAttachmentPickerOpen===false;
   if(command==='originalDetails'){
    const range=displayedText.current,body=scroller.current;
    textReturn.current=same&&mode==='original'&&range&&body&&body.scrollTop>0&&body.querySelector('.pw-original-text')&&
     matchesTextViewer(live,range)&&obj(live.originalFile).busy===false?{scope,document:documentEpoch,range,top:body.scrollTop,textStart:readingTextStart(body)}:null;
   }else if(mode==='detail'&&!Object.keys(args).length&&same&&pin?.scope===scope&&pin.document===documentEpoch)pin.reopening=true;
   else textReturn.current=null;
  }else textReturn.current=null;
  rememberPosition();
  const leavesEditor=['openOriginal','originalDetails','continueDocument','openProcessing','compareParent','read','ocr','selectFile','attach','chooseSource','newDocument','continueTask','refresh'].includes(command);
  const retireReopen=()=>{if(reopenFrame&&textReturn.current===pin)textReturn.current=null};
  void Promise.resolve(leavesEditor?beforeBack.current?.():undefined).then(frame=>act(command,args,frame??reopenFrame))
   .then(result=>{if(result.accepted!==true)retireReopen()}).catch(retireReopen);
 };
 const goBack=useCallback(()=>{
  const current=latest.current,transfer=draftFlight.current;
  if(!current.open)return false;
  if(current.ready&&readerActionMenu===readerActionScope){setReaderActionMenu('');return true}
  if(draftDesk){draftDeskExit.current?.(draftDesk.visit.entry==='quoted');return true}
  if(typeof current.txtDraftLanding==='string'){
   // If the editor acknowledgement was lost, Back still closes only its native ephemeral landing.
   if(current.ready&&!backFlight.current){
    backFlight.current=true;
    void act('drafts.exitTxtEditor',{receipt:current.txtDraftLanding,close:false}).catch(()=>{}).finally(()=>{backFlight.current=false});
   }
   return true;
  }
  if(isLiveMessageOriginal(current)){
   if(current.ready&&!backFlight.current){
    const exactLinkedOriginal=current.savedChatLinkedOnly===true&&typeof obj(current.originalFile).id==='string';
    backFlight.current=true;void act(exactLinkedOriginal?'back':'close').catch(()=>{}).finally(()=>{backFlight.current=false});
   }
   return true;
  }
  if(current.passiveGenericPdf===true){
   if(current.ready&&!backFlight.current){
    backFlight.current=true;void act('back').catch(()=>{}).finally(()=>{backFlight.current=false});
   }
   return true;
  }
  if(backFlight.current||current.busy||!current.ready||transfer&&(transfer.phase==='opening'||!current.originalFile))return true;
  const native=bridge.getState(),live=obj(native.state.attachmentWorkspace),range=displayedText.current,body=scroller.current;
  const work=native.state.chatAttachmentPickerOpen===false&&!document.hidden&&live.windowId===current.windowId&&typeof live.taskId==='string'
   ?workTextReader.current?.(live.taskId):undefined;
  const textStart=body&&range&&matchesTextViewer(live,range)?readingTextStart(body):undefined;
  const library=mayRestoreText&&ordinaryLibraryOwner(native)&&!document.hidden&&live.windowId===current.windowId&&
   live.busy===false&&live.exportPending===false&&textStart!==undefined&&listedLocalTxt(live,exactFileTarget(obj(live.file)))?
   privateTxtPositionScope(live):'';
  const scope=workTxtPositionScope(live,work)||library;
  const pin:TextReturn|null=scope&&range&&body&&body.scrollTop>0&&body.querySelector('.pw-original-text')&&
   matchesTextViewer(live,range)&&obj(live.originalFile).busy===false?
   {scope,work,range,document:bridge.getDocumentEpoch(),top:body.scrollTop,textStart,
    ...(library?{libraryList:'returning'}:{reopening:true})}:null;
  textReturn.current=pin;
  if(body)positions.current.set(location,body.scrollTop);
  backFlight.current=true;
  const retirePin=()=>{if(pin&&textReturn.current===pin)textReturn.current=null;};
  void Promise.resolve().then(()=>beforeBack.current?.()).then(frame=>act('back',{},frame)).then(result=>{
   const returned=obj(result.workspace);
   if(pin?.libraryList){
    if(result.accepted===true&&textReturn.current===pin&&privateTxtLibraryList(returned)&&
       returned.windowId===pin.range.windowId&&bridge.getDocumentEpoch()===pin.document&&!document.hidden&&ordinaryLibraryOwner(bridge.getState()))
     pin.libraryList='listed';
    else retirePin();
   }else if(result.accepted!==true||returned.open!==false)retirePin();
  }).catch(retirePin).finally(()=>{backFlight.current=false});
  return true;
 },[act,location,mayRestoreText,draftDesk,readerActionMenu,readerActionScope]);
 const registerBeforeBack=useCallback((handler:(()=>Promise<AttachmentFrame|undefined>)|null)=>{beforeBack.current=handler},[]);
 useEffect(()=>{alive.current=true;registerBack(goBack);return()=>{alive.current=false;registerBack(null)}},[registerBack,goBack]);
 useEffect(()=>{setError('');setPage('1')},[view.windowId,file.id,file.sha256]);
 const busy=pending>0||!!view.busy||!view.ready,hasFile=typeof file.id==='string';
 const compilerFile=compilerOutputRow(file),pythonOutputFile=pythonOutputRow(file),localOnlyFile=compilerFile||pythonOutputFile;
 const ordinaryBookReplyFrame=(shown:Json)=>privateLibraryWindow(shown)&&
  shown.messagePdfOnly===false&&shown.messageInputDocumentOnly===false&&shown.messageReplyDocumentOnly===false&&
  shown.focusedStandaloneDraft===false&&shown.restoredWorkReturn===false&&
  !shown.documentDraft&&!shown.documentOpen&&!shown.processingOpen&&!shown.comparison&&
  (!shown.originalFile||obj(shown.originalFile).sourceUnavailable===false);
 const bookReplyLink=!passiveGenericPdf&&!draftDesk&&!onReturnToSearch&&(mode==='detail'||mode==='original')&&ordinaryBookReplyFrame(view)?
  onOriginalBookReply?.(view):undefined;
 const returnToBookReply=()=>{
  if(!bookReplyLink||busy||draftBusy||view.exportPending||draftFlight.current||
     attachmentFlight.current||bookReplyExit.current||backFlight.current||beforeBack.current)return;
  const native=obj(bridge.getState().state.attachmentWorkspace),frame=attachmentFrame(native);
  if(!frame||!ordinaryBookReplyFrame(native)||!bookReplyLink.current(native))return;
  const exit:BookReplyExit={windowId:frame.windowId,link:bookReplyLink,accepted:false,exited:false};
  bookReplyExit.current=exit;backFlight.current=true;
  void act('close',{},frame,true,shown=>bookReplyExit.current===exit&&ordinaryBookReplyFrame(shown)&&bookReplyLink.current(shown)).then(result=>{
   if(bookReplyExit.current!==exit)return;
   if(result.accepted!==true||obj(result.workspace).open!==false){
    bookReplyExit.current=null;
    if(obj(bridge.getState().state.attachmentWorkspace).windowId===exit.windowId)setError('原文件库尚未确认关闭。');
    return;
   }
   exit.accepted=true;finishBookReplyExit(exit);
  }).catch(()=>{if(bookReplyExit.current===exit)bookReplyExit.current=null;})
   .finally(()=>{backFlight.current=false});
 };
 const bookReplyAction=bookReplyLink&&<Button variant="ghost" disabled={busy||draftBusy||view.exportPending}
   onClick={returnToBookReply}>查看原回复</Button>;
 const canStage=!localOnlyFile&&!scopedResultOnly&&!workOutput&&privateFiles&&!!selection&&!!draftTarget&&!!onStageFile&&!!onOpenDraft;
 const focusedDraft=view.focusedStandaloneDraft===true;
 const title=focusedDraft?'本机文档草稿':draftDesk?'私人文件库 · 本机草稿':view.documentDraft?'编辑文档':pdfPageText?`第 ${pdfPageText} 页的文字`:
  wordExcerpt?'核对 Word 摘录':searchFileOriginal?'本轮原文件':replyTextOnly?'原答复 TXT':replyDocumentOnly?'原答复文档':view.messageInputDocumentOnly===true?
  (pairedOriginal.kind==='office'&&pairedOriginal.mimeType==='application/vnd.openxmlformats-officedocument.wordprocessingml.document'?'提问所附 Word':
   pairedOriginal.kind==='text'&&pairedOriginal.mimeType==='text/plain'?'提问所附 TXT':'提问所附原件'):
  view.messagePdfOnly===true?'原消息 PDF':hasOriginal?'查看原件':view.comparison?'原图对照':view.processingOpen?'处理原件':view.documentOpen?'按来源读取':hasFile?'文件来源':view.team?'成员文件成果':view.taskId?'原任务的文件与成果':'文件与成果';
 const close=()=>{
  if(draftDesk){draftDeskExit.current?.(true);return}
  if(!view.ready||draftFlight.current||bookReplyExit.current)return;
  textReturn.current=null;
  const returnToWork=!localOnlyFile&&view.restoredWorkReturn!==true&&view.fromWorkResult===true&&!!view.taskId&&openTaskId!==view.taskId;
  if(view.busy){if(!returnToWork)run('close');return}
  void Promise.resolve(beforeBack.current?.()).then(frame=>act(returnToWork?'continueTask':'close',{},frame)).catch(()=>{});
 };
 const returnToSearch=()=>{if(busy||draftFlight.current||!onReturnToSearch)return;returningToSearch.current=true;void Promise.resolve(beforeBack.current?.()).then(frame=>act('close',{},frame)).then(()=>onReturnToSearch()).catch(()=>{returningToSearch.current=false})};
 const handOffFile=async(openDraft:boolean,originalTopic?:FileResultTopic,openOnly=false,asAttachment=false,keepReading?:FileDraftTarget)=>{
  if(busy||draftFlight.current||attachmentFlight.current||!selection||!privateFiles||
     (asAttachment?!originalTopic||!onChooseResultAttachment:originalTopic?!onUseResult:!canStage))return;
  const target=draftTarget;
  const reference={kind:'artifact',id:selection.fileId,sha256:selection.sha256},epoch=bridge.getDocumentEpoch();
  const readSource=()=>obj(bridge.getState().state.attachmentWorkspace);
  const stillCurrent=()=>{
   const current=readSource(),selected=obj(current.file);
   return alive.current&&currentDraftScope.current===draftScope&&bridge.getDocumentEpoch()===epoch&&!document.hidden&&
    current.open===true&&current.ready===true&&!current.busy&&current.windowId===view.windowId&&current.taskId===view.taskId&&selected.id===file.id&&selected.sha256===file.sha256&&
    (!originalTopic||mayReferenceOriginal(current)&&selected.sourceTaskId===originalTopic.taskId&&obj(current.originalFile).sha256===selection.sha256);
  };
  if(!stillCurrent())return;
  const inDraft=!!target&&draftContainsFile(target,selection);
  if(!originalTopic&&openDraft!==inDraft){
   // A late draft edit must not turn "open" into a write or "add" into a navigation.
   setDraftHandoff({scope:draftScope,phase:inDraft?'added':'failed',
    ...(inDraft?{}:{error:'原草稿已不含这个文件版本，没有重新加入。'})});
   return;
  }
  let closing:Promise<unknown>|undefined;
  const closeOriginal=()=>closing??=(async()=>{
   if(!stillCurrent())throw new Error('原件位置已变化，请重新打开原件。');
   rememberPosition();
   await beforeBack.current?.();
   const frame=attachmentFrame(readSource());
   if(!stillCurrent()||!frame)throw new Error('原件位置已变化，请重新打开原件。');
   returningToSearch.current=true;
   try{return await act('close',{},frame,false)}catch(error){returningToSearch.current=false;throw error}
  })();
  const request:DraftHandoff={scope:draftScope,phase:openDraft?'opening':'adding'};
  draftFlight.current=request;
  setDraftHandoff(request);
  try{
   const label=originalTopic?.label||target?.label;
   const accepted=await (originalTopic?asAttachment
    ?onChooseResultAttachment?.({...selection,name:String(file.name||'原件')},originalTopic,stillCurrent,closeOriginal)
    :onUseResult?.(reference,originalTopic,openOnly,stillCurrent,closeOriginal,keepReading)
    :target&&(openDraft?onOpenDraft?.(reference,target,closeOriginal):onStageFile?.(reference,target,stillCurrent)));
   if(!accepted)throw new Error(asAttachment?'尚未打开原话题附件选择，没有加入文件。':openDraft?`「${label}」草稿暂未打开，请重新核对后再试。`:`文件位置尚未确认关联到「${label}」草稿，请重试。`);
   if(stillCurrent())setDraftHandoff({...request,phase:'added'});
  }catch(error){
   if(stillCurrent())setDraftHandoff({...request,phase:openDraft&&!originalTopic?'added':'failed',error:error instanceof Error?error.message:'草稿操作尚未确认，请重试。'});
  }finally{
   draftFlight.current=null;
   if(alive.current&&!stillCurrent())setDraftHandoff(current=>current===request?null:current);
  }
 };
 const returnToLibraryDocument=view.returnToLibraryDocument===true;
 const objectTitle=draftDesk?'本机文档草稿':String(view.documentDraft?.name||file.name||snapshot.artifact?.path||(returnToLibraryDocument?'原文档生成记录':view.taskId?'这次工作的成果':'文件与成果'));
 // Use the unfiltered, already-read catalog so changing a filter cannot relabel a saved version.
 const documentVersions=useMemo(()=>privateFiles&&!scopedResultOnly&&!view.choosingSource?savedDocumentVersions([
  ...(snapshot.taskId===(view.taskId||'')?rows(snapshot.artifacts):[]),obj(view.file)
 ]):undefined,[privateFiles,scopedResultOnly,view.choosingSource,snapshot.taskId,snapshot.artifacts,view.taskId,view.file]);
 const documentVersion=documentVersions?.get(documentVersionKey(file));
 const objectVersion=documentVersion?`版本 ${documentVersion.label}`:Number.isSafeInteger(file.sourceVersion)&&file.sourceVersion>0?`来源版本 ${file.sourceVersion}`:hasFile?'固定原件':'';
 const parentVersion=documentVersion?.parent?`续自版本 ${documentVersion.parent}`:'';
 const draftBaseVersion=view.documentDraft?.parentArtifactId===file.id&&view.documentDraft?.parentSha256===file.sha256?documentVersion?.label:undefined;
 const fileOrigin=typeof file.sourceKind==='string'&&Object.hasOwn(fileOrigins,file.sourceKind)?fileOrigins[file.sourceKind]:'';
 // The same local draft survives renewal of the native reader window; the
 // actual read/edit commands still require the fresh window and file frame.
 const continuationKey=view.taskId&&hasFile?JSON.stringify([view.taskId,file.id,file.sha256]):'';
 const receivedOriginal=file.sourceKind==='device_received_file'&&file.sourceRunEvidence==='original_device_file_receive';
 const canKeepSupplement=!localOnlyFile&&!receivedOriginal&&!scopedResultOnly&&privateFiles&&!!continuationKey&&view.continuationDraft?.available===true;
 const continuationVisible=canKeepSupplement&&(mode==='detail'||(mode==='original'&&previewContinuation===continuationKey));
 const returnToOriginalWork=workOutput&&file.sourceTaskId===view.taskId&&openTaskId===view.taskId&&snapshot.taskId===view.taskId&&
  typeof snapshot.taskRunId==='string'&&!!snapshot.taskRunId&&Number.isSafeInteger(snapshot.coordinatorEpoch)&&snapshot.coordinatorEpoch>0
  ?async(frame:AttachmentFrame)=>{
   const native=bridge.getState(),owner=obj(native.view),opened=obj(native.state.attachmentWorkspace),selected=obj(opened.file);
   const task=rows(native.state.tasks).find(task=>task.id===view.taskId),proof=obj(task?.raw);
   if(document.hidden||owner.section||owner.taskId!==view.taskId||opened.windowId!==view.windowId||opened.taskId!==view.taskId||
    selected.id!==file.id||selected.sha256!==file.sha256||proof.id!==view.taskId||proof.actorId!=='owner'||proof.spaceId!=='private'||
    proof.contextAvailable===false||proof.contextWithdrawn===true||proof.taskProjection==='source_unavailable'||proof.sourceCurrentReadRequired===true||
    proof.runId!==snapshot.taskRunId||proof.coordinatorEpoch!==snapshot.coordinatorEpoch)
    throw new Error('原工作窗口已变化。补充仍在这份固定文件旁，没有转到其他执行。');
   const result=await act('close',{},frame,false);
   if(result.workspace?.open!==false)throw new Error('草稿已核对，原文件窗口尚未确认关闭。');
  }:undefined;
 const focusContinuation=()=>{
  continuationHeading.current?.scrollIntoView({block:'start'});
  continuationHeading.current?.focus({preventScroll:true});
 };
 const revealContinuation=()=>{
  if(continuationVisible)focusContinuation();
  else setPreviewContinuation(continuationKey);
 };
 useLayoutEffect(()=>{
  if(mode==='original'&&continuationVisible)focusContinuation();
 },[mode,continuationVisible,continuationKey]);
 const reuseAction=canStage&&draftTarget&&<>
  <Button variant="secondary" className="pw-file-draft-action" disabled={busy||draftBusy} aria-busy={draftBusy||undefined} onClick={()=>void handOffFile(draftHasFile)}>
   {handoff?.phase==='opening'?'正在打开草稿…':handoff?.phase==='adding'?'正在关联文件位置…':draftHasFile?(draftTarget.conversationId?'查看话题草稿':'查看管家草稿'):`向「${draftTarget.label}」关联文件位置`}
  </Button>
  {handoff?.error?<p className="pw-file-draft-status pw-error" role="alert">{handoff.error}</p>:
   draftHasFile&&<p className="pw-file-draft-status" role="status">文件位置已关联到「{draftTarget.label}」；未作为附件。</p>}
 </>;
 const original=obj(view.originalFile),canSaveOriginal=privateFiles&&hasFile&&view.canSaveOriginal===true;
 const offerReplyAttachment=mode==='original'&&replyDocumentOnly&&!replyTextOnly&&!searchFileOriginal&&!historicalReplyOriginal&&view.ready===true&&!!replyAttachmentDraft&&
  !!selection&&original.sourceUnavailable===false&&original.busy===false&&!original.error&&typeof original.id==='string'&&
  original.sha256===selection.sha256&&original.mimeType===file.mimeType&&original.size===file.size;
 const chooseReplyAttachment=async()=>{
  const draft=replyAttachmentDraft;
  if(!offerReplyAttachment||!draft||!selection||busy||view.exportPending||attachmentFlight.current)return;
  const live=obj(bridge.getState().state.attachmentWorkspace),reader=obj(live.originalFile),target=obj(live.file),frame=attachmentFrame(live);
  if(!frame||live.messageReplyDocumentOnly!==true||frame.windowId!==view.windowId||reader.id!==original.id||
     target.id!==selection.fileId||target.sha256!==selection.sha256)return;
  attachmentFlight.current=true;setPending(count=>count+1);setError('');
  try{
   const reply=await bridge.command('attachments.openPicker',{key:draft.key,epoch:draft.epoch,revision:draft.revision,
    replyDocument:{...frame,viewerId:reader.id}});
   if(reply?.opened!==true||typeof reply.requestId!=='string'||!reply.requestId)throw new Error('未确认打开附件选择，草稿没有改动。');
  }catch(failure){
   const current=obj(bridge.getState().state.attachmentWorkspace);
   if(current.open===true&&current.windowId===frame.windowId)setError(failure instanceof Error?failure.message:'尚未打开原附件选择。');
  }finally{attachmentFlight.current=false;setPending(count=>count-1)}
 };
 const canEditDocument=!scopedResultOnly&&privateFiles&&!!selection&&(file.sourceKind||file.source?.kind)==='created_document'&&
  editableDocumentMimes.has(file.mimeType)&&!!exactFileTarget(obj(file.editableSource||file.source?.editableSource));
 const canReturnGeneratedDraft=!onReturnToSearch&&(mode==='detail'||mode==='original')&&!!generatedDraftHandoff(view);
 const generatedDraftAction=(variant:'primary'|'secondary')=><Button variant={variant} disabled={busy||draftBusy||view.exportPending}
  onClick={()=>void returnGeneratedDraft()}>回原草稿</Button>;
 const editDocument=(variant:'primary'|'secondary'|'ghost')=><Button variant={variant} disabled={busy||draftBusy||view.exportPending||!!view.pendingDocumentTask}
  onClick={()=>run('continueDocument')}>{canReturnGeneratedDraft?'从此版另起修改稿':'从此版继续编辑'}</Button>;
 const offerDocumentEdit=canEditDocument&&canSaveOriginal&&!state.chatAttachmentPickerOpen;
 const standaloneDocument=standaloneDocumentAttachmentDraft?standaloneDocumentSelection(view):undefined;
 const attachmentDraft=standaloneDocument?standaloneDocumentAttachmentDraft:draftTarget;
 const canChooseAttachment=!localOnlyFile&&!scopedResultOnly&&(!view.taskId||!!standaloneDocument)&&canSaveOriginal&&!!attachmentDraft&&!!selection&&!!onChooseAttachment;
 const attachmentChoiceError=mode==='original'&&canChooseAttachment&&error;
 const chooseAttachment=async()=>{
  if(!canChooseAttachment||busy||draftBusy||view.exportPending||attachmentFlight.current||!onChooseAttachment||!attachmentDraft||!selection)return;
  const epoch=bridge.getDocumentEpoch(),{windowId}=view;
  const read=()=>obj(bridge.getState().state.attachmentWorkspace);
  const stillCurrent=()=>{
   const native=read(),selected=obj(native.file);
   if(!alive.current||document.hidden||bridge.getDocumentEpoch()!==epoch||native.open!==true||native.ready!==true||native.windowId!==windowId||
      native.busy||native.canSaveOriginal!==true||selected.id!==selection.fileId||selected.sha256!==selection.sha256)return false;
   if(!standaloneDocument)return !native.taskId;
   const current=standaloneDocumentSelection(native);
   return current?.taskId===standaloneDocument.taskId&&current.mimeType===standaloneDocument.mimeType&&current.size===standaloneDocument.size&&
    obj(native.originalFile).id===original.id;
  };
  if(!stillCurrent())return;
  attachmentFlight.current=true;setPending(count=>count+1);setError('');
  try{await onChooseAttachment({...selection,name:String(file.name||'原件'),standaloneDocument},attachmentDraft,stillCurrent,async()=>{
   if(!stillCurrent())throw new Error('原文件窗口已变化，没有打开附件选择。');
   const frame=attachmentFrame(read());
   if(!frame)throw new Error('请在固定原件重开附件选择。');
   returningToSearch.current=true;
   try{if((await act('close',{},frame,false)).workspace?.open!==false)throw new Error('原文件窗口尚未确认关闭。');}
   catch(failure){returningToSearch.current=false;throw failure;}
  });}catch(failure){if(stillCurrent())setError(failure instanceof Error?failure.message:'原附件尚未打开，没有改变草稿。');}
  finally{attachmentFlight.current=false;if(alive.current)setPending(count=>Math.max(0,count-1));}
 };
 const offerReader=canSaveOriginal&&view.canOpenInReader===true&&!view.externalViewerResult;
 const reviewManagedWorkApk=mode==='original'&&privateFiles&&!scopedResultOnly&&view.canReviewManagedWorkApk===true;
 const resultFilesOnly=view.resultFilesOnly===true;
 const reportInWork=!scopedResultOnly&&resultFilesOnly&&view.fromWorkResult===true;
 const preferSave=!pythonOutputFile&&canSaveOriginal&&(reportInWork||original.kind==='unsupported'||original.information?.previewAvailable===false||original.error||view.externalViewerResult);
 const canMoveReportOriginal=reportOnly&&view.canOpenReportOriginalWork===true&&!!selection&&!!onVerifyReportFileWork;
 const reportError=canMoveReportOriginal&&(error||view.error);
 const openReportOriginalWork=async()=>{
  if(busy||draftBusy||view.exportPending||reportFlight.current||!canMoveReportOriginal||!onVerifyReportFileWork||!selection)return;
  const {taskId,windowId}=view,epoch=bridge.getDocumentEpoch();
  const current=()=>{
   const source=obj(bridge.getState().state.attachmentWorkspace),selected=obj(source.file);
   return alive.current&&!document.hidden&&bridge.getDocumentEpoch()===epoch&&source.open===true&&source.ready===true&&
    source.reportResultOnly===true&&source.canOpenReportOriginalWork===true&&source.windowId===windowId&&source.taskId===taskId&&
    selected.id===selection.fileId&&selected.sha256===selection.sha256?source:null;
  };
  if(!current())return;
  reportFlight.current=true;setError('');setPending(count=>count+1);
  try{
   await onVerifyReportFileWork(taskId,windowId);
   const frame=attachmentFrame(current());
   if(!frame)return;
   rememberPosition();
   // The dispatch only requests navigation. Save is a separate tap in the new native work window.
   await act('openReportOriginalWork',{},frame,false);
  }catch(failure){if(current())setError(failure instanceof Error?failure.message:'没有进入原工作，原件仍留在汇报中。');}
  finally{reportFlight.current=false;if(alive.current)setPending(count=>Math.max(0,count-1));}
 };
 const topicHasFile=topic?.conversationId===draftTarget?.conversationId&&draftHasFile;
 const useInTopic=!pythonOutputFile&&privateFiles&&!!topic&&!!onUseResult;
 const attachInTopic=useInTopic&&!!onChooseResultAttachment;
 const topicPositionLabel=draftBusy&&!onChooseResultAttachment?'正在核对原话题草稿…':topicHasFile?'查看草稿中的文件位置':'只关联文件位置';
 const referenceInTopic=()=>void handOffFile(!keepResultReadingDraft||topicHasFile,topic,topicHasFile,false,keepResultReadingDraft);
 const topicFeedback=useInTopic&&<>
  {handoff?.error?<p className="pw-file-draft-status pw-error" role="alert">{handoff.error}</p>:
   keepResultReadingDraft&&topicHasFile&&!draftBusy&&<p className="pw-file-draft-status" role="status">原话题草稿里已保留这份文件位置。这里没有添加附件或发送。</p>}
 </>;
 const copyReceipt=obj(view.privateCopyReceipt),copyStage=view.privateCopyStage;
 const belongsToFile=(receipt:Json)=>privateFiles&&!scopedResultOnly&&receipt.taskId===(view.taskId||'')&&selection&&
  receipt.fileId===selection.fileId&&receipt.sha256===selection.sha256&&typeof receipt.requestId==='string'&&!!receipt.requestId;
 const listedSize=file.size??file.sizeBytes;
 const isVerified=(receipt:Json)=>belongsToFile(receipt)&&receipt.status==='verified'&&Number.isSafeInteger(receipt.verifiedBytes)&&receipt.verifiedBytes>=0&&
  (!Number.isSafeInteger(listedSize)||receipt.verifiedBytes===listedSize);
 const previousVerified=obj(copyReceipt.previousVerified);
 const verifiedCopy=isVerified(copyReceipt)?copyReceipt:previousVerified.historical===true&&isVerified(previousVerified)?previousVerified:null;
 const confirmed=verifiedCopy!==null;
 const copyName=(receipt:Json|null)=>typeof receipt?.displayName==='string'?receipt.displayName:'';
 const destination=(receipt:Json|null)=>copyName(receipt)?`「${copyName(receipt)}」`:'所选位置的副本';
 const attemptStatus=!belongsToFile(copyReceipt)?'':copyReceipt.status==='no_target'?'本次没有取得保存位置，未写入内容。原件保留。'
  :copyReceipt.status==='could_not_read'?`本次向${destination(copyReceipt)}写入，但不能回读核对。请在刚选的位置检查。`
  :copyReceipt.status==='content_mismatch'?`本次${destination(copyReceipt)}与原件不一致，不能确认保存。请在刚选的位置检查。`
  :copyReceipt.status==='destination_changed'?'本次写入后目标已变化，不能确认保存。请在刚选的位置检查。'
  :copyReceipt.status==='unconfirmed'?'本次保存未确认，可能已有部分内容。请先检查刚选的位置；没有自动重写。':'';
 const savedCopy=verifiedCopy?`${verifiedCopy.historical===true?'上次另存时已核对':'已保存并核对'}${destination(verifiedCopy)}：回读 ${verifiedCopy.verifiedBytes.toLocaleString()} 字节，SHA-256 与原件一致。${copyName(verifiedCopy)?'':'目标文件名未核对。'}`:'';
 // This is the evidence stored with the exact save, not a new verification of the destination.
 const copyVersion=verifiedCopy?`原件：${file.name||'原文件'} · ${attachmentFileFormat(file.mimeType,file.name).label} · ${objectVersion}\n回读匹配的 SHA-256：${verifiedCopy.sha256}`:null;
 const copyLocation=`请在系统文件或所选存储服务的保存位置查看${copyName(verifiedCopy)?'，按上述文件名查找':''}。未记录完整路径。`;
 const copyFeedback=(compact=false)=>(attemptStatus||verifiedCopy)&&<div className="pw-file-draft-status">
  {attemptStatus&&<div role="status">{attemptStatus}</div>}
  {verifiedCopy&&<div>
   <span role={verifiedCopy.historical===true?undefined:'status'} className={verifiedCopy.historical===true?'pw-muted':undefined}>{savedCopy}</span>
   {!compact&&<><br/><span className="pw-muted">{copyLocation}</span></>}
   <FileDetails key={verifiedCopy.requestId} title="核对另存的版本" value={compact?`${copyLocation}\n\n${copyVersion}`:copyVersion}/>
  </div>}
 </div>;
 const exportLabel=copyStage==='writing'?'正在另存…':copyStage==='picker_unconfirmed'?'保存位置尚未确认…':
  view.exportPending?'等待选择保存位置…':confirmed?'另存另一份…':'另存原件…';
 const saveFirst=!reviewManagedWorkApk&&preferSave&&!confirmed;
 const saveOriginalCopy=(trusted:boolean)=>{
  heldCopyViewport.current=null;
  const native=bridge.getState(),live=obj(native.state.attachmentWorkspace),owner=obj(native.view),pane=scroller.current;
  const scope=workPdfCopyScope(live,openTaskId);
  if(trusted&&mode==='original'&&!onReturnToSearch&&!busy&&!draftBusy&&!document.hidden&&pane&&scope&&
     owner.section===null&&owner.taskId===openTaskId&&native.host?.ready===true&&live.windowId===view.windowId&&
     live.canSaveOriginal===true&&live.canShareWorkPdf===true&&live.busy===false&&live.exportPending===false){
   const shown=capturePdfCopyViewport(pane,live.windowId,obj(live.originalFile));
   if(shown){
    const nonce=Array.from(crypto.getRandomValues(new Uint8Array(16)),byte=>byte.toString(16).padStart(2,'0')).join('');
    heldCopyViewport.current={...shown,nonce,scope,windowId:live.windowId};
    run('export',{pdfCopyViewportNonce:nonce,displayedPdfRaster:shown.rasterId});return;
   }
  }
  run('export');
 };
 const exportAction=!pythonOutputFile&&<Button variant={confirmed?'ghost':saveFirst?'primary':'secondary'} disabled={busy||draftBusy||view.exportPending}
  onClick={event=>saveOriginalCopy(event.nativeEvent.isTrusted)}>{exportLabel}</Button>;
 const shareableWorkPdf=mode==='original'&&view.canShareWorkPdf===true&&!!openTaskId&&view.taskId===openTaskId&&!onReturnToSearch;
 const shareWorkPdf=()=>{
  const frame=attachmentFrame(view),native=bridge.getState(),live=obj(native.state.attachmentWorkspace),owner=obj(native.view);
  if(!shareableWorkPdf||!frame||busy||draftBusy||view.exportPending||document.hidden||owner.section!=null||
     owner.taskId!==view.taskId||live.windowId!==frame.windowId||live.canShareWorkPdf!==true||
     obj(live.originalFile).id!==original.id||obj(obj(live.originalFile).information).pdfRasterId!==obj(original.information).pdfRasterId)return;
  void act('share',{},frame).catch(()=>{});
 };
 const sharePdfAction=(onClick:()=>void)=>shareableWorkPdf&&<Button variant="ghost" leadingIcon={IconShare}
  disabled={busy||draftBusy||view.exportPending} onClick={onClick}>分享原件…</Button>;
 // Use the real current attach offer, not an inferred draft or an alternate result path.
 // Moving the other actions into a popover does not change their own owner/file admission.
 const compactReaderActions=mode==='original'&&view.ready===true&&attachInTopic&&canSaveOriginal&&!preferSave&&
  !offerDocumentEdit&&!reviewManagedWorkApk&&!localOnlyFile&&!receivedOriginal&&!onReturnToSearch&&view.originalReturn==='entry'&&
  view.fromWorkResult===true&&view.restoredWorkReturn===false&&view.resultFilesOnly===false&&view.choosingSource===false&&
  view.group===false&&view.team===false&&view.deviceOriginalOnly===false&&view.historyResultOnly===false&&view.reportResultOnly===false&&
  !!view.taskId&&view.taskId===openTaskId&&snapshot.taskId===view.taskId&&!!selection&&
  (original.kind==='pdf'||original.kind==='text')&&original.sourceUnavailable===false&&original.sha256===selection.sha256;
 const readerActionsOpen=compactReaderActions&&readerActionMenu===readerActionScope;
 useLayoutEffect(()=>{
  if(readerActionMenu&&!readerActionsOpen)setReaderActionMenu('');
 },[readerActionMenu,readerActionsOpen]);
 const selectReaderAction=(action:()=>void)=>{
  setReaderActionMenu('');action();
 };
 const supplementLabel=view.continuationDraft?.text?.trim()?'查看补充草稿':'留一条补充';
 const readerActions=compactReaderActions&&<PopoverTrigger isOpen={readerActionsOpen}
  onOpenChange={open=>setReaderActionMenu(value=>open?readerActionScope:value===readerActionScope?'':value)}>
  <IconButton icon={IconMore} className="pw-file-reader-more" aria-label="更多文件操作" disabled={busy||draftBusy}/>
  {/* The file drawer owns Escape and Android Back; it dismisses this same menu first. */}
  <Popover aria-label="当前固定文件的更多操作" placement="top end" className="pw-attachment-menu pw-work-object-menu"
   dialogClassName="pw-work-object-menu-dialog" isKeyboardDismissDisabled>
   <div className="pw-work-object-menu-actions">
    <Button variant="ghost" disabled={busy||draftBusy||view.exportPending}
      onClick={()=>selectReaderAction(referenceInTopic)}>{topicPositionLabel}</Button>
    {canKeepSupplement&&<Button variant="ghost" disabled={busy||draftBusy}
      onClick={()=>selectReaderAction(revealContinuation)}>{supplementLabel}</Button>}
    <Button variant="ghost" disabled={busy||draftBusy||view.exportPending}
      onClick={event=>selectReaderAction(()=>saveOriginalCopy(event.nativeEvent.isTrusted))}>{exportLabel}</Button>
    {sharePdfAction(()=>selectReaderAction(shareWorkPdf))}
   </div>
  </Popover>
 </PopoverTrigger>;
 const fileMenu=<ContextMenuTrigger key={`${file.id}:${file.sha256}`}>{({open})=><>
  <IconButton icon={IconMore} aria-label="更多文件操作" disabled={busy} onClick={event=>open(event.currentTarget)}/>
  <ContextMenu className="pw-attachment-menu" aria-label="当前固定文件的操作" onAction={key=>{if(key==='external'||key==='share'&&privateFiles)run(String(key))}}>
   <ContextMenuItem id="external" icon={IconExternalLink}>{confirmed?'用其他应用打开原件':'用其他应用打开'}</ContextMenuItem>
   {privateFiles&&<ContextMenuItem id="share" icon={IconShare}>系统分享…</ContextMenuItem>}
  </ContextMenu>
 </>}</ContextMenuTrigger>;
 const sourceReturnLabel=liveMessageOriginalOnly?(historicalReplyOriginal?'返回原历史消息':replyDocumentOnly&&returnToDeliveredFiles?'返回文件成果':'返回原消息'):reportOnly?'返回原汇报':scopedResultOnly?'返回原历史消息':
  focusedDraft||quotedDraftArrival?.windowId===view.windowId&&quotedDraftArrival?.current()?'返回原消息':
  view.resultFilesOnly===true&&!view.fromWorkResult&&view.taskId!==openTaskId?returnToDeliveredFiles?'返回文件成果':'返回原消息':view.taskId?'返回原工作':'返回原工作区';
 const entryBack=(mode==='original'||mode==='detail')&&(view.originalReturn==='entry'||mode==='detail'&&view.savedChatLinkedOnly===true);
 const mayDismissRestored=view.restoredWorkReturn===true&&!!(error||view.error);
 const originalHasDirectBack=mode==='original'&&(view.originalReturn==='entry'||view.originalReturn==='list');
 const detailsLabel=passiveGenericPdf?'返回文件详情':scopedResultOnly?'文件来源':'来源与操作';
 const backLabel=passiveGenericPdf?'返回文件详情':draftDesk?(draftDesk.visit.entry==='quoted'?'返回原消息':'返回成果'):pdfPageText?'返回原 PDF':wordExcerpt?'返回原 Word':
  view.wordExcerptSourceOpen===true?'返回原摘录':mode==='list'&&returnToLibraryDocument?'返回文件来源':view.deviceOriginalOnly?'返回原工作':entryBack||mode==='list'?sourceReturnLabel:
  mode==='detail'||originalHasDirectBack?'返回文件列表':mode==='edit'&&hasOriginal?'返回这份原件':'返回文件详情';
 const fileList=(label:string,files:Json[],opensOriginal:boolean|((file:Json)=>boolean)=false,resumePdf=false)=><AttachmentFileCollection label={label} files={files} busy={busy||selectionPending} choosing={!!view.choosingSource} opensOriginal={opensOriginal} versions={documentVersions} origins={catalogOrigins}
  onSelect={(target,row,direct)=>{
   const body=scroller.current;
   if(listScope&&body)listOrigin.current={scope:listScope,collection:label,inset:row.getBoundingClientRect().top-body.getBoundingClientRect().top};
   run(view.choosingSource?'attach':direct?'openOriginal':'selectFile',resumePdf&&direct?{...target,resumeLibraryPdf:true}:target);
  }}/>;
 // A source picker reads the private library, not the task it will return to.
 // Do not show retained rows from the preceding location while the new read settles.
 const catalogUnread=privateFiles&&(snapshot.taskId!==(view.choosingSource?'':view.taskId||'')||!Array.isArray(snapshot.sources)||!Array.isArray(snapshot.artifacts));
 useLayoutEffect(()=>{
  if(!view.open){displayedFile.current=null;listOrigin.current=null;return;}
  if(!view.ready)return;
  if(mode!=='list'){
   displayedFile.current=listScope&&selection?{scope:listScope,target:selection}:null;
   return;
  }
  const previous=displayedFile.current,body=scroller.current;
  if(busy||catalogUnread||view.error||!previous||!body)return;
  displayedFile.current=null;
  if(previous.scope!==listScope)return;
  const remembered=listOrigin.current,origin=remembered?.scope===listScope?remembered:null;
  const matches=Array.from(body.querySelectorAll<HTMLButtonElement>('button[data-attachment-file-id][data-attachment-file-sha]')).filter(row=>
   !row.disabled&&row.dataset.attachmentFileId===previous.target.fileId&&row.dataset.attachmentFileSha===previous.target.sha256&&row.getClientRects().length>0);
  const sameCollection=origin?matches.filter(row=>row.closest<HTMLElement>('[data-attachment-collection]')?.dataset.attachmentCollection===origin.collection):[];
  const row=matches.length===1?matches[0]:sameCollection.length===1?sameCollection[0]:null;
  if(!row)return; // A withdrawn/replaced file must not focus any similarly named row.
  const bounds=row.getBoundingClientRect(),offset=Math.max(0,Math.min(body.clientHeight-bounds.height,origin?.inset??16));
  body.scrollTop+=bounds.top-body.getBoundingClientRect().top-offset;
  row.focus({preventScroll:true});
 },[view.open,view.ready,mode,listScope,selection?.fileId,selection?.sha256,busy,catalogUnread,view.error]);
 const media=catalogUnread||view.choosingSource?[]:rows(snapshot.media).length?rows(snapshot.media):snapshot.kind==='group_media_result'?[{...snapshot,operationId:snapshot.sourceOperationId,status:snapshot.mediaState}]:[];
 const allSources=catalogUnread||resultFilesOnly?[]:rows(snapshot.sources),savedFiles=catalogUnread?[]:rows(snapshot.artifacts);
 const privateLibrary=privateFiles&&view.savedChatLinkedOnly!==true&&!view.taskId&&!view.choosingSource&&!resultFilesOnly&&!state.chatAttachmentPickerOpen&&!onReturnToSearch;
 const localFileDetail=mode==='detail'&&privateLibrary&&privateLibraryWindow(view)&&!!selection&&
  view.focusedStandaloneDraft===false&&view.restoredWorkReturn===false;
 const detailPreviewLabel=(localFileDetail||view.savedChatLinkedOnly===true)&&file.mimeType==='application/pdf'?'阅读原 PDF':
  localFileDetail&&typeof file.mimeType==='string'&&file.mimeType.startsWith('image/')?'查看原图':'查看原件';
 const currentNative=bridge.getState(),libraryView=obj(currentNative.view);
 const documentHistory=obj(snapshot.documentActions),documentActions=rows(documentHistory.actions);
 const hasOlderDocumentActions=documentHistory.hasMore===true&&typeof documentHistory.nextCursor==='string'&&
  documentHistory.nextCursor.length>0&&documentHistory.nextCursor.length<=2048;
 const mayContinueEmptyDocumentPage=privateLibrary&&ordinaryLibraryOwner(currentNative)&&hasOlderDocumentActions;
 const documentParent=privateLibrary&&!catalogUnread&&mode==='detail'&&ordinaryLibraryOwner(currentNative)&&currentNative.state.filePath==null?
  listedDocumentParent(view):undefined;
 const openDocumentParent=async()=>{
  if(!documentParent||busy||draftBusy||backFlight.current||documentParentFlight.current)return;
  const epoch=bridge.getDocumentEpoch();
  const at=(file:ExactFile|null,target:ExactFile)=>!!file&&file.fileId===target.fileId&&file.sha256===target.sha256;
  const liveDetails=()=>{
   const native=bridge.getState(),shown=obj(native.state.attachmentWorkspace);
   return alive.current&&!document.hidden&&bridge.getDocumentEpoch()===epoch&&native.host?.ready===true&&ordinaryLibraryOwner(native)&&native.state.filePath==null&&
    shown.windowId===view.windowId&&shown.busy===false&&shown.exportPending===false&&libraryDocumentDetail(shown)?shown:undefined;
  };
  const before=liveDetails(),frame=attachmentFrame(before),fresh=before&&listedDocumentParent(before);
  if(!frame||!fresh||!at(fresh.child,documentParent.child)||!at(fresh.parent,documentParent.parent))return;
  documentParentFlight.current=true;textReturn.current=null;rememberPosition();
  try{
   const result=await act('selectFile',fresh.parent,frame),selected=obj(result.workspace),selectedFrame=attachmentFrame(selected);
   const current=liveDetails(),currentFile=exactFileTarget(obj(current?.file)),selectedFile=exactFileTarget(obj(selected.file));
   // The frame acknowledgement can arrive before the coalesced native state changes from B to A.
   if(result.accepted!==true||!selectedFrame||selectedFrame.windowId!==frame.windowId||!libraryDocumentDetail(selected)||
      selected.originalReturn!=='details'||!at(selectedFile,fresh.parent)||!libraryGeneratedDocument(obj(selected.file))||!listedLibraryDocument(selected,fresh.parent)||!current||
      backFlight.current||!listedLibraryDocument(current,fresh.parent)||!at(currentFile,fresh.child)&&!at(currentFile,fresh.parent))return;
   await act('openOriginal',{},selectedFrame);
  }catch{/* The existing actions report their own errors; never try another version. */}
  finally{documentParentFlight.current=false}
 };
 const justSavedWorkPdf=!onReturnToSearch&&!catalogUnread&&mode==='detail'?justSavedWorkPdfSource(currentNative,view,openTaskId):undefined;
 const excerptPdf=privateLibrary&&!catalogUnread&&mode==='detail'&&libraryView.section===null&&libraryView.taskId===null&&
  state.chatAttachmentPickerOpen===false?excerptPdfSource(view):justSavedWorkPdf;
 const excerptDocument=bridge.getDocumentEpoch();
 const openExcerptSource=()=>{
  const native=bridge.getState(),live=obj(native.state.attachmentWorkspace),nativeView=obj(native.view);
  if(!excerptPdf||busy||draftBusy||document.hidden||bridge.getDocumentEpoch()!==excerptDocument||nativeView.section!==null||
     native.state.chatAttachmentPickerOpen!==false||live.windowId!==view.windowId||
     live.busy!==false||live.exportPending!==false)return;
  const currentCatalog=obj(live.snapshot);
  if(justSavedWorkPdf?currentCatalog.taskRunId!==snapshot.taskRunId||currentCatalog.coordinatorEpoch!==snapshot.coordinatorEpoch:
     nativeView.taskId!==null)return;
  const fresh=justSavedWorkPdf?justSavedWorkPdfSource(native,live,openTaskId):excerptPdfSource(live);
  const frame=attachmentFrame(live);
  if(!fresh||!frame||fresh.excerpt.fileId!==excerptPdf.excerpt.fileId||fresh.excerpt.sha256!==excerptPdf.excerpt.sha256||
     fresh.target.fileId!==excerptPdf.target.fileId||fresh.target.sha256!==excerptPdf.target.sha256)return;
  // This is the same file-details selection as a source row; no document range or original body is requested.
  textReturn.current=null;rememberPosition();
  void act('selectFile',fresh.target,frame).catch(()=>{});
 };
 const documentRecord=privateFiles&&!scopedResultOnly&&!resultFilesOnly&&!view.choosingSource&&!!view.taskId&&
  snapshot.taskId===view.taskId&&snapshot.taskRunId===view.taskId&&snapshot.coordinatorEpoch===1&&snapshot.originalDocumentActionId===view.taskId;
 const pendingDocumentHere=!!view.pendingDocumentTask&&(privateLibrary||documentRecord&&view.pendingDocumentTask===view.taskId);
 const otherPendingDocument=privateFiles&&!scopedResultOnly&&!!view.pendingDocumentTask&&!!view.taskId&&view.pendingDocumentTask!==view.taskId;
 const workSourceSelecting=privateFiles&&!scopedResultOnly&&!resultFilesOnly&&view.choosingSource===true&&
  !!openTaskId&&view.taskId===openTaskId&&!state.chatAttachmentPickerOpen&&!onReturnToSearch;
 const sourceWork=workSourceSelecting?obj(rows(state.tasks).find(row=>row.id===openTaskId)?.raw):{};
 const nativeWork=workSourceSelecting?obj(bridge.getState().view):{};
 const workSourcePicker=workSourceSelecting&&view.ready===true&&snapshot.taskId===''&&!catalogUnread&&
  nativeWork.taskId===openTaskId&&nativeWork.section===null&&sourceWork.id===openTaskId&&sourceWork.kind==='assistant'&&
  sourceWork.actorId==='owner'&&sourceWork.spaceId==='private'&&sourceWork.origin==null&&
  sourceWork.parentTaskId==null&&sourceWork.teamRunId==null&&sourceWork.memberId==null&&
  sourceWork.ownerDeviceWork==null&&sourceWork.ownerPhoneControl==null&&sourceWork.contextAvailable!==false&&
  sourceWork.contextWithdrawn!==true&&sourceWork.sourceCurrentReadRequired!==true&&sourceWork.taskProjection!=='source_unavailable';
 const catalogOrigins=useMemo(()=>(privateLibrary||workSourcePicker)&&view.open&&view.ready&&mode==='list'&&!catalogUnread?
  libraryFileOrigins([...rows(snapshot.artifacts),...rows(snapshot.sources)],rows(state.tasks)):undefined,
  [privateLibrary,workSourcePicker,view.open,view.ready,mode,catalogUnread,snapshot.artifacts,snapshot.sources,state.tasks]);
 // One view of the native catalog: no content search, new index, or changes to which originals can open.
 const [sourceName,setSourceName]=useState<{scope:string;name:string}|null>(null);
 useEffect(()=>{if(!view.choosingSource)setSourceName(null);},[view.choosingSource]);
 const sourceScope=workSourcePicker?JSON.stringify([view.windowId,openTaskId]):'';
 const filterable=workSourcePicker||privateLibrary||privateFiles&&!!view.taskId&&!scopedResultOnly&&!view.choosingSource&&!state.chatAttachmentPickerOpen&&!onReturnToSearch;
 const fileFilter=filterable&&catalogFilter?.scope===listScope?catalogFilter:null;
 const nameFilter=workSourcePicker?(sourceName?.scope===sourceScope?sourceName.name:''):fileFilter?.name||'',
  formatFilter=workSourcePicker?'all':fileFilter?.format||'all',needle=normalizedFileName(nameFilter);
 const filterActive=!!needle||formatFilter!=='all';
 const matchesFile=(row:Json)=>(!needle||normalizedFileName(row.name).includes(needle))&&
  (formatFilter==='all'||attachmentFileFormat(row.mimeType,row.name).category===formatFilter);
 const updateFilter=(name:string,format:string)=>workSourcePicker?setSourceName({scope:sourceScope,name}):setCatalogFilter({scope:listScope,name,format});
 const relinquishLibraryRowReturn=()=>{
  // Back can still be rereading this list. Deliberate search use takes priority
  // over pending row scroll/focus; passive focus and scrolling do not.
  if(!privateLibrary||mode!=='list'||!privateLibraryWindow(view)||displayedFile.current?.scope!==listScope)return;
  const native=bridge.getState(),live=obj(native.state.attachmentWorkspace);
  if(!ordinaryLibraryOwner(native)||live.windowId!==view.windowId||!standaloneLibrarySurface(live)||!privateLibraryWindow(live))return;
  displayedFile.current=null;listOrigin.current=null;
 };
 const trustedSearchControl=(event:{nativeEvent:Event;target:EventTarget})=>event.nativeEvent.isTrusted&&
  event.target instanceof Element&&!!event.target.closest('input:not([type="hidden"]), label, button.pw-select-trigger, [role="option"], [role="listbox"]');
 const libraryImport=privateLibrary?obj(view.libraryImport):{},registration=obj(libraryImport.registration);
 const importPhase=typeof libraryImport.phase==='string'?libraryImport.phase:'';
 const selectionPending=['selecting','selection_unconfirmed','registering'].includes(importPhase);
 const registeredCount=registration.registered,selectedCount=registration.selected;
 const confirmedImport=importPhase==='confirmed'&&Number.isSafeInteger(selectedCount)&&Number.isSafeInteger(registeredCount)&&
  selectedCount>0&&selectedCount<=10&&registeredCount>=0&&registeredCount<=selectedCount;
 const importedKeys=new Set(confirmedImport?rows(registration.files).map(exactFileKey).filter(Boolean):[]);
 const continuedTxt=obj(view.continuedImportedTxt);
 const continuedTxtKey=mode==='list'&&offerDraftDesk&&!draftDesk&&typeof continuedTxt.requestId==='string'?
  exactFileKey(continuedTxt):'';
 if(continuedTxtKey)importedKeys.add(continuedTxtKey);
 const continueTxtRequest=mode==='list'&&mayReenterDraftShelf&&!draftDesk&&!filterActive&&
  view.canContinueImportedTxt===true&&typeof libraryImport.requestId==='string'?libraryImport.requestId:undefined;
 const systemShare=privateLibrary&&mode==='list'?obj(view.librarySystemShare):{},shareRegistration=obj(systemShare.registration);
 const shareReceived=shareRegistration.selected,shareRegistered=shareRegistration.registered;
 const shareFiles=rows(shareRegistration.files),shareKeys=new Set(shareFiles.map(exactFileKey).filter(Boolean));
 const confirmedShare=systemShare.phase==='confirmed'&&Number.isSafeInteger(shareReceived)&&Number.isSafeInteger(shareRegistered)&&
  shareReceived>=1&&shareReceived<=10&&shareRegistered>=1&&shareRegistered<=shareReceived&&shareFiles.length===shareRegistered&&
  shareKeys.size===shareRegistered&&shareFiles.every(file=>!!exactFileTarget(file));
 const shareMessage=confirmedShare?(shareRegistered===shareReceived
  ?`最近一次系统分享已在本机登记 ${shareRegistered} 个文件。`:`最近一次系统分享确认登记 ${shareRegistered}/${shareReceived} 个文件，其余未确认。`)
  :systemShare.phase==='unconfirmed'?'本次系统分享没有确认登记结果。可能已有文件保留，可重新读取，不必重复导入。'
  :systemShare.phase==='not_started'?'本次系统分享尚未开始接收。可返回原应用重新分享。':'';
 const readableKeys=new Set(privateLibrary?rows(view.readableLibrarySources).map(exactFileKey).filter(Boolean):[]);
 const opensSource=(row:Json)=>readableKeys.has(exactFileKey(row));
 const recentSources=allSources.filter(row=>importedKeys.has(exactFileKey(row)));
 const sharedSources=confirmedShare?allSources.filter(row=>shareKeys.has(exactFileKey(row))):[];
 const importMessage=confirmedImport?`收到 ${selectedCount} 个文件，本机已登记 ${registeredCount} 个。${registeredCount<selectedCount?`还有 ${selectedCount-registeredCount} 个未确认登记。`:''}`:
  Object.hasOwn(importMessages,importPhase)?importMessages[importPhase]:'';
 const editableCompanions=new Set(privateLibrary?savedFiles.flatMap(record=>{
  const key=record.sourceKind==='created_document'&&exactFileTarget(record)?documentSourceKey(record,obj(record.editableSource)):'';
  return key?[key]:[];
 }):[]);
 const editableSources:Json[]=[];
 // The writer stores its editable data separately. Fold it only when the exact published document is also listed.
 const sourceFiles=allSources.filter(record=>{
  if(importedKeys.has(exactFileKey(record))||confirmedShare&&shareKeys.has(exactFileKey(record)))return false;
  if(record.sourceKind!=='document_source'||!editableCompanions.has(documentSourceKey(record)))return true;
  editableSources.push(record);return false;
 });
 const fileCount=allSources.length+savedFiles.length,hasLibraryFiles=fileCount>0;
 const listedOutputs=savedFiles.filter(matchesFile),listedSources=sourceFiles.filter(matchesFile),listedRecent=recentSources.filter(matchesFile),listedEditable=editableSources.filter(matchesFile);
 const listedShared=sharedSources.filter(row=>!importedKeys.has(exactFileKey(row))&&matchesFile(row));
 const matchingCount=listedOutputs.length+listedSources.length+listedRecent.length+listedEditable.length+listedShared.length;
 const runFiles=privateFiles&&resultFilesOnly?listedOutputs.flatMap(row=>{
  const target=exactFileTarget(row);
  return row.runProvenance==='current'&&row.availability==='available'&&target?[{target,name:String(row.name||'原件')}]:[];
 }):[];
 const allowsBrowse=runFiles.length>1;
 const runIndex=allowsBrowse&&selection?runFiles.findIndex(({target})=>target.fileId===selection.fileId&&target.sha256===selection.sha256):-1;
 const browseRunFile=(direction:-1|1)=>{
  const next=runFiles[runIndex+direction],label=direction<0?'上一份文件':'下一份文件';
  return <IconButton icon={direction<0?IconChevronLeft:IconChevronRight}
   aria-label={next?`${label}：${next.name}`:label} title={next?`${label}：${next.name}`:label}
   disabled={!next||busy||draftBusy||view.exportPending} onClick={()=>{if(next)run('openOriginal',next.target)}}/>;
 };
 const catalogIncomplete=privateFiles&&!catalogUnread&&(resultFilesOnly?snapshot.resultFilesReadLimited===true:snapshot.indexDiagnostics?.incomplete===true);
 const catalogUnconfirmed=catalogIncomplete||catalogUnread;
 const refreshList=()=>run(view.choosingSource?'chooseSource':'refresh');
 const sourcesList=fileList(view.choosingSource?'可用文件':'来源文件',listedSources,opensSource);
 const pdfResume=obj(view.libraryPdfResume),pdfResumeTarget=exactFileTarget({id:pdfResume.fileId,sha256:pdfResume.sha256});
 const resumeRows=privateLibrary&&mode==='list'&&view.ready===true&&!filterActive&&ordinaryLibraryOwner(currentNative)&&pdfResumeTarget?
  allSources.filter(row=>row.id===pdfResumeTarget.fileId):[];
 const recentPdf=resumeRows.length===1&&resumeRows[0].sha256===pdfResumeTarget?.sha256&&
  resumeRows[0].mimeType==='application/pdf'&&resumeRows[0].role==='source'&&resumeRows[0].availability==='available'&&opensSource(resumeRows[0])?
  resumeRows[0]:undefined;
 const recentSourcesList=fileList('本次已登记的文件',listedRecent,opensSource);
 const sharedSourcesList=fileList('从系统分享登记的文件',listedShared,opensSource);
 const outputsList=fileList(resultFilesOnly?'本轮已保存文件':'已保存的文件成果',listedOutputs,privateLibrary||resultFilesOnly||
  (row=>documentRecord&&view.fromWorkResult===false&&row.recordedDocumentOriginal===true));
 const editableSourceList=listedEditable.length>0&&<Accordion className="pw-attachment-tools"><AccordionItem id="library-editable-sources" title={`文档编辑源 · ${listedEditable.length} 项`}>
  {fileList('已关联成品的编辑源',listedEditable)}
 </AccordionItem></Accordion>;
 const libraryActions=privateFiles&&!resultFilesOnly&&<div className="pw-action-row pw-file-library-actions">
  <Button variant={hasLibraryFiles?'secondary':'primary'} leadingIcon={IconUpload} disabled={busy||selectionPending||state.attachmentImporting===true} onClick={()=>run('import')}>导入文件</Button>
  <Button variant="ghost" leadingIcon={privateLibrary?undefined:IconFileAdd} disabled={busy||selectionPending||!!view.pendingDocumentTask}
   onClick={()=>run('newDocument')}>{privateLibrary?'直接生成 Word / PDF（不单独留草稿）':'创建文档'}</Button>
  {view.taskId&&!view.choosingSource&&<Button variant="ghost" disabled={busy} onClick={()=>run('chooseSource')}>从附件库关联</Button>}
 </div>;
 const offerPdfPageText=(privateLibrary||workPdfText)&&mode==='original'&&view.canReadPdfPageText===true&&!busy&&!draftBusy&&!view.exportPending;
 const requestPdfPageText=(viewerId:string,rasterId:string)=>{
  const live=bridge.getState(),current=obj(live.state.attachmentWorkspace),reader=obj(current.originalFile);
  const workHere=workPdfText&&workPdfTextWindow(current,openTaskId)&&obj(live.view).taskId===openTaskId&&obj(live.view).section===null;
  if(!offerPdfPageText||document.hidden||!(workPdfText?workHere:ordinaryLibraryOwner(live))||current.windowId!==view.windowId||
     current.canReadPdfPageText!==true||reader.id!==viewerId||obj(reader.information).pdfRasterId!==rasterId)return;
  run('read',{pdfViewerId:viewerId,displayedPdfRaster:rasterId});
 };
 const workTextResult=workPdfTextResult(view,openTaskId),workCopy=!error&&workTextResult?.text?workTextResult:undefined;
 const copyWorkPdfPage=async()=>{
  if(!workCopy||busy||draftBusy)return;
  const epoch=bridge.getDocumentEpoch();
  const current=()=>{
   const native=bridge.getState(),window=obj(native.view),page=workPdfTextResult(obj(native.state.attachmentWorkspace),openTaskId);
   if(alive.current&&!document.hidden&&bridge.getDocumentEpoch()===epoch&&window.section===null&&window.taskId===openTaskId&&
      page?.key===workCopy.key&&page.text===workCopy.text)return page;
  };
  const shown=current();if(!shown)return;
  try{
   if(!navigator.clipboard?.writeText)throw new Error('clipboard unavailable');
   await navigator.clipboard.writeText(shown.text);
   if(current())setWorkPdfCopyFeedback({key:shown.key,message:'已复制本页显示的文字。'});
  }catch{
   if(current())setWorkPdfCopyFeedback({key:shown.key,message:'复制未完成，可长按选中文字后复制。',error:true});
  }
 };
 const savePdfPagePending=!!pdfPageText&&pdfTextCurrent&&typeof pdfTextRead.readReceiptId==='string';
 const savedPdfPageTxt=(current:Json)=>{
  const source=obj(current.file),page=obj(current.pdfPageText),read=obj(current.document),catalog=obj(current.snapshot);
  const saved=obj(catalog.savedPdfPageExtractHere),target=exactFileTarget(saved);
  if(!privateLibraryWindow(current)||current.originalReturn!=='list'||current.documentOpen!==true||!target||
     current.busy!==false||current.exportPending!==false||
     saved.viewerId!==page.viewerId||saved.rasterId!==page.rasterId||saved.page!==page.page||
     typeof read.readReceiptId!=='string'||!read.readReceiptId||saved.readReceiptId!==read.readReceiptId||
     read.attachmentId!==source.id||read.sha256!==source.sha256||
     saved.sourceAttachmentId!==source.id||saved.sourceSha256!==source.sha256)return;
  const listed=rows(catalog.artifacts).filter(row=>row.id===target.fileId),record=listed[0];
  if(listed.length===1&&record.sha256===target.sha256&&record.role==='result'&&record.availability==='available'&&
     record.sourceKind==='document_extract'&&record.mimeType==='text/plain'&&
     record.sourceAttachmentId===source.id&&record.sourceSha256===source.sha256)return target;
 };
 const pdfPageTxt=pdfTextCurrent?savedPdfPageTxt(view):undefined;
 const openPdfPageTxt=()=>{
  const native=bridge.getState(),shown=obj(native.state.attachmentWorkspace),frame=attachmentFrame(shown);
  const reply=savedPdfPageTxt(shown),page=obj(shown.pdfPageText),read=obj(shown.document);
  if(!pdfPageTxt||!reply||!frame||document.hidden||!ordinaryLibraryOwner(native)||shown.windowId!==view.windowId||
     page.viewerId!==nativePdfPage.viewerId||page.rasterId!==nativePdfPage.rasterId||page.page!==pdfPageText||
     read.readReceiptId!==pdfTextRead.readReceiptId||reply.fileId!==pdfPageTxt.fileId||reply.sha256!==pdfPageTxt.sha256)return;
  rememberPosition();
  void act('openOriginal',reply,frame).catch(()=>{});
 };
 const pdfDraft=obj(view.pdfPageDraft),pdfDraftFile=pdfTextCurrent?exactFileTarget(file):undefined;
 const retainPdfPage=()=>{
  if(!pdfDraftFile||busy||draftBusy||view.exportPending||!['READY','UNKNOWN'].includes(pdfDraft.state))return;
  const epoch=bridge.getDocumentEpoch(),viewerId=nativePdfPage.viewerId,rasterId=nativePdfPage.rasterId,readReceiptId=pdfTextRead.readReceiptId;
  if(typeof viewerId!=='string'||typeof rasterId!=='string'||typeof readReceiptId!=='string'||!readReceiptId)return;
  const current=()=>{
   const native=bridge.getState(),live=obj(native.state.attachmentWorkspace),target=exactFileTarget(obj(live.file)),
    page=obj(live.pdfPageText),read=obj(live.document);
   return alive.current&&!document.hidden&&bridge.getDocumentEpoch()===epoch&&ordinaryLibraryOwner(native)&&
    live.windowId===view.windowId&&page.viewerId===viewerId&&page.rasterId===rasterId&&page.page===pdfPageText&&
    target?.fileId===pdfDraftFile.fileId&&target?.sha256===pdfDraftFile.sha256&&read.readReceiptId===readReceiptId?live:undefined;
  };
  const live=current(),frame=attachmentFrame(live);
  if(!frame||obj(live?.pdfPageDraft).state!==pdfDraft.state)return;
  void act(pdfDraft.state==='READY'?'drafts.retainPdfPage':'drafts.checkPdfPage',
   {viewerId,rasterId,readReceiptId,...pdfDraftFile},frame,false).then(result=>{
    if(!current())return;
    if(result.sourceAvailable===false)setError('未核对到当前原页。可到本机文档草稿核对，不会自动再次保留。');
    else if(result.status==='QUOTA')setError('本机草稿已达到容量上限，没有保留这份草稿。');
   }).catch(()=>{if(current())setError('还未确认草稿已保留。请先核对本机文档草稿。')});
 };
 const savedPdfDraft=['SAVED','VERIFIED'].includes(pdfDraft.state)&&Number.isSafeInteger(pdfDraft.savedAt)&&pdfDraft.savedAt>0;
 const pdfPageTextView=pdfPageText&&<section aria-label={`原 PDF 第 ${pdfPageText} 页的文字`}>
  {pdfPageTxt&&<p role="status">本页文字已保存为 TXT。可返回原 PDF 继续阅读。</p>}
  {workPdfText?workTextResult&&!error&&!view.error&&<>
   <p className="pw-muted" role="status">{workTextResult.message}</p>
   {workCopy&&<div className="pw-file-projection"><pre className="pw-file-text" tabIndex={0} data-vaul-no-drag
    style={{userSelect:'text',WebkitUserSelect:'text'}}>{workCopy.text}</pre></div>}
  </>:pdfTextCurrent?<DocumentProjection value={pdfTextRead} pdfPage={pdfPageText}/>:
   !view.busy&&!error&&!view.error&&<p role="status">本页文字尚未读到。可返回原 PDF。</p>}
  {workCopy&&<Button variant="primary" disabled={busy||draftBusy} onClick={()=>void copyWorkPdfPage()}>复制本页显示的文字</Button>}
  {workCopy&&workPdfCopyFeedback?.key===workCopy.key&&<p role={workPdfCopyFeedback.error?'alert':'status'}>{workPdfCopyFeedback.message}</p>}
  {pdfTextCurrent&&view.canSaveRead===true&&<div className="pw-action-row">
   {pdfPageTxt?<Button variant="primary" disabled={busy||draftBusy} onClick={openPdfPageTxt}>打开已保存的 TXT</Button>:
    <Button variant="primary" disabled={busy||draftBusy} onClick={()=>run('saveRead')}>保存本页文字为 TXT</Button>}
   {pdfDraftFile&&['READY','UNKNOWN'].includes(pdfDraft.state)&&<Button variant="secondary"
    disabled={busy||draftBusy||view.exportPending} onClick={retainPdfPage}>{pdfDraft.state==='UNKNOWN'?'核对本页草稿':'保留为可编辑草稿'}</Button>}
  </div>}
  {pdfDraftFile&&savedPdfDraft?<p role="status">已保留本页文字草稿。返回「成果 → 本机文档草稿」可继续编辑。</p>:
   pdfDraftFile&&pdfDraft.state==='UNKNOWN'?<p role="status">草稿是否保留仍待核对，不会重复保存。</p>:
   pdfTextCurrent&&view.canSaveRead===true&&(!pdfPageTxt||pdfDraftFile&&pdfDraft.state==='READY')&&<p className="pw-muted">
    {pdfDraftFile&&pdfDraft.state==='READY'?'草稿只含本页文字层，不保留原排版。':'保存到私人文件库，原 PDF 保留。'}
   </p>}
 </section>;
 const wordCopyScope=mode==='original'&&!onReturnToSearch&&!busy&&!draftBusy?workWordCopyScope(bridge.getState(),incoming,openTaskId):'';
 const mayCopyWorkWordPassage=wordCopyScope?()=>{
  const live=bridge.getState();
  return alive.current&&!backFlight.current&&!draftFlight.current&&
   workWordCopyScope(live,obj(live.state.attachmentWorkspace),openTaskId)===wordCopyScope;
 }:undefined;
 const offerWordExcerpt=privateLibrary&&mode==='original'&&view.canReadWordExcerpt===true&&!busy&&!draftBusy&&!view.exportPending;
 const wordSave=obj(snapshot.savedWordExcerptHere),wordResult=offerWordExcerpt&&wordSave.viewerId===original.id&&
  wordSave.unit===obj(original.information).unit&&wordSave.chunk===obj(original.information).chunk?exactFileTarget(wordSave):undefined;
 const shownWordResult=wordResult&&rows(snapshot.artifacts).filter(row=>row.id===wordResult.fileId).length===1&&
  rows(snapshot.artifacts).some(row=>row.id===wordResult.fileId&&row.sha256===wordResult.sha256&&row.role==='result'&&
   row.sourceKind==='document_extract'&&row.mimeType==='text/plain'&&row.sourceAttachmentId===file.id&&
   row.sourceSha256===file.sha256&&obj(row.originalWordExcerpt).format==='pocketworld.original-word-excerpt.v1');
 const requestWordExcerpt=(viewerId:string,unit:number,chunk:number)=>{
  const native=bridge.getState(),current=obj(native.state.attachmentWorkspace),reader=obj(current.originalFile),info=obj(reader.information);
  const frame=attachmentFrame(current);
  if(!offerWordExcerpt||!frame||document.hidden||!ordinaryLibraryOwner(native)||current.windowId!==view.windowId||
     current.canReadWordExcerpt!==true||reader.id!==viewerId||reader.sha256!==file.sha256||info.unit!==unit||info.chunk!==chunk)return;
  rememberPosition();
  void act('read',{wordViewerId:viewerId,displayedWordUnit:unit,displayedWordChunk:chunk},frame).catch(()=>{});
 };
 const openSavedWordExcerpt=()=>{
  const native=bridge.getState(),current=obj(native.state.attachmentWorkspace),shown=obj(obj(current.snapshot).savedWordExcerptHere);
  const frame=attachmentFrame(current);
  if(!shownWordResult||!wordResult||!frame||document.hidden||!ordinaryLibraryOwner(native)||current.windowId!==view.windowId||
     current.busy!==false||shown.viewerId!==obj(current.originalFile).id||shown.id!==wordResult.fileId||shown.sha256!==wordResult.sha256||
     shown.unit!==obj(obj(current.originalFile).information).unit||shown.chunk!==obj(obj(current.originalFile).information).chunk)return;
  rememberPosition();
  void act('selectFile',wordResult,frame).catch(()=>{});
 };
 const showWordOrigin=privateLibrary&&(mode==='detail'||mode==='original')&&view.canOpenWordExcerptSource===true&&!!selection;
 const openWordOrigin=()=>{
  const native=bridge.getState(),current=obj(native.state.attachmentWorkspace),selected=exactFileTarget(obj(current.file));
  const frame=attachmentFrame(current);
  if(!showWordOrigin||busy||draftBusy||view.exportPending||!frame||document.hidden||!ordinaryLibraryOwner(native)||
     current.windowId!==view.windowId||current.canOpenWordExcerptSource!==true||current.busy!==false||
     selected?.fileId!==selection?.fileId||selected?.sha256!==selection?.sha256)return;
  rememberPosition();
  void act('openOriginal',{wordExcerptSource:true},frame).catch(()=>{});
 };
 const wordOriginAction=showWordOrigin&&<Button variant="ghost" disabled={busy||draftBusy||view.exportPending} onClick={openWordOrigin}>查看 Word 原段</Button>;
 const wordExcerptView=wordExcerpt&&<section aria-label="原 Word 摘录">
  {wordPreview?<DocumentProjection value={{...wordRead,status:wordRead.extractionStatus}} orderedOriginalWord/>:
   !view.busy&&!error&&!view.error&&<p role="status">还没核对到这段文字。可返回原 Word。</p>}
  {wordPreview&&view.canSaveRead===true&&<div className="pw-action-row">
   <Button variant="primary" disabled={busy} onClick={()=>run('saveRead')}>保存摘录为 TXT</Button>
   <p className="pw-muted">只保存此处已核对的文字片段；原 Word 不变。</p>
  </div>}
  {view.wordExcerptSaveAttempted===true&&!view.busy&&<p role="status">保存尚未确认。请返回后在成果清单核对，不会自动重复保存。</p>}
 </section>;
 const txtOffer=mode==='original'&&privateLibrary&&!draftDesk?wholeTxtDraftOffer(view,mountedTxtRead):undefined;
 const startTxtDraft=async()=>{
  if(!txtOffer||busy||draftBusy||view.exportPending||txtDraftFlight.current||draftVisit.current||backFlight.current)return;
  const native=bridge.getState(),windowId=view.windowId,epoch=bridge.getDocumentEpoch();
  const live=obj(native.state.attachmentWorkspace),frame=attachmentFrame(live);
  const painted=scroller.current?.querySelectorAll<HTMLPreElement>('.pw-original-text > pre.pw-file-text');
  const exact=wholeTxtDraftOffer(live,mountedTxtRead);
  if(document.hidden||!frame||frame.windowId!==windowId||!ordinaryLibraryOwner(native)||!exact||
     exact.source.receipt!==txtOffer.source.receipt||exact.source.fileId!==txtOffer.source.fileId||exact.source.sha256!==txtOffer.source.sha256||
     !painted||painted.length!==1||!painted[0].isConnected||!painted[0].getClientRects().length||painted[0].textContent!==exact.body)return;
  const visit:StandaloneVisit={windowId,document:epoch,page:document,presentation:exact.library,entry:'txt',txtSource:exact.source};
  txtDraftFlight.current=true;
  try{
   const result=await act('drafts.newFromTxt',{receipt:exact.source.receipt},frame,false);
   if(result.opened!==true||result.windowId!==windowId||result.library!==exact.library||result.receipt!==exact.source.receipt||
      typeof result.editorHandle!=='string'||!result.editorHandle||typeof result.name!=='string'||!result.name.trim()||result.name.length>80||
      typeof result.blankName!=='string'||result.blankName.length>80||result.blankFormat!=='docx'||result.blankFontPoints!==12||
      !await waitForDraftLibraryFrame(visit)||draftVisit.current||!alive.current)throw new Error('原 TXT 草稿尚未打开');
   const initial={editorHandle:result.editorHandle,fields:{format:result.blankFormat,name:result.blankName,title:'',body:'',fontPoints:result.blankFontPoints}};
   draftVisit.current=visit;
   setLocalDrafts({visit,loading:false,initial,initialTxt:{name:result.name,body:exact.body}});
  }catch{
   if(alive.current&&!document.hidden&&bridge.getDocumentEpoch()===epoch&&obj(bridge.getState().state.attachmentWorkspace).windowId===windowId)
    setError('未核对到这份 TXT 的文档草稿。没有保存或更改原件。');
  }finally{txtDraftFlight.current=false}
 };
 const markdown=obj(view.markdownDraft),markdownTarget=mode==='original'&&privateLibrary&&view.ready===true&&
  file.role==='source'&&file.sourceKind==='content_uri'&&typeof markdown.name==='string'&&markdown.name.length>0&&markdown.name.length<=80?
  exactFileTarget(file):undefined;
 const retainMarkdown=()=>{
  if(!markdownTarget||busy||draftBusy||view.exportPending||!['READY','UNKNOWN'].includes(markdown.state))return;
  const epoch=bridge.getDocumentEpoch(),viewerId=original.id;
  const current=()=>{
   const native=bridge.getState(),live=obj(native.state.attachmentWorkspace),target=exactFileTarget(obj(live.file));
   return alive.current&&!document.hidden&&bridge.getDocumentEpoch()===epoch&&ordinaryLibraryOwner(native)&&
    live.windowId===view.windowId&&obj(live.originalFile).id===viewerId&&target?.fileId===markdownTarget.fileId&&
    target?.sha256===markdownTarget.sha256?live:undefined;
  };
  const live=current(),frame=attachmentFrame(live);
  if(!frame||obj(live?.markdownDraft).state!==markdown.state||typeof viewerId!=='string'||!viewerId)return;
  void act(markdown.state==='READY'?'drafts.retainMarkdown':'drafts.checkMarkdown',{viewerId,...markdownTarget},frame)
   .then(result=>{
    if(!current())return;
    if(result.sourceAvailable===false)setError('暂不能核对该原件。请回到本机文档草稿核对，不会自动重复保留。');
    else if(result.status==='QUOTA')setError('本机草稿已达到容量上限，没有保留这份草稿。');
   }).catch(()=>{});
 };
 return <Drawer.Root open={view.open===true} onOpenChange={open=>{if(!open)close()}} direction="right" handleOnly dismissible={false} shouldScaleBackground={false}><Drawer.Portal><Drawer.Overlay className="pw-file-page-underlay"/><Drawer.Content className="pw-drawer pw-file-workspace pw-file-page" data-file-object-id={file.id||undefined} data-file-version={file.sha256||undefined} data-file-view={mode} onOpenAutoFocus={event=>{event.preventDefault();heading.current?.focus({preventScroll:true})}} onCloseAutoFocus={event=>{
  if(returningToSearch.current)event.preventDefault();
  const window=lastLiveMessageFileWindow.current,history=lastHistoricalReply.current;
  lastLiveMessageFileWindow.current=undefined;lastHistoricalReply.current=undefined;
  if(history)event.preventDefault(); // Never let the drawer retarget a different History/Chat opener.
  onReturnFocus?.(event,window,history);
  const book=bookReplyExit.current;
  if(book&&bridge.getState().state.attachmentWorkspace?.open===false){
   book.exited=true;finishBookReplyExit(book);
  }
 }} onEscapeKeyDown={event=>{event.preventDefault();goBack()}} onInteractOutside={event=>{event.preventDefault()}} aria-describedby="pw-file-page-description">
 <header className="pw-drawer-header pw-file-page-header">
  {mode==='original'&&!originalHasDirectBack&&view.wordExcerptSourceOpen!==true?<Button variant="ghost" size="small" leadingIcon={IconArrowLeft} disabled={(!passiveGenericPdf&&busy)||draftHandoff?.phase==='opening'} onClick={goBack}>{detailsLabel}</Button>:
   <IconButton icon={IconArrowLeft} aria-label={backLabel} title={backLabel} onClick={goBack} disabled={liveMessageOriginalOnly?!view.ready||pending>0:draftDesk?pending>0:(!passiveGenericPdf&&busy)||draftBusy}/>}
  <div ref={heading} tabIndex={-1}><Drawer.Title>{objectTitle}</Drawer.Title><Drawer.Description id="pw-file-page-description">{[draftDesk||hasFile||view.documentDraft?title:'成果工作区',fileOrigin,fileRunProvenance(file.runProvenance,file.sourceRunEvidence),objectVersion,parentVersion,view.group?'原群任务':view.team?'原团队分工':view.taskId?(file.role==='source'?'原工作关联的来源文件':'来自原工作'):searchFileOriginal?'来自命中的原工作 · 仅供查看':replyTextOnly?'原答复已保存的 TXT':replyDocumentOnly?'原答复已保存的文档':view.savedChatLinkedOnly===true?'原消息中的文件位置':liveMessageOriginalOnly?'原消息附件':draftDesk?'':'私人文件库'].filter(Boolean).join(' · ')}</Drawer.Description></div>
  {originalHasDirectBack&&!liveMessageOriginalOnly&&<IconButton icon={IconInfo} aria-label={detailsLabel} title={detailsLabel} disabled={busy||draftBusy} onClick={()=>run('originalDetails')}/>}
  {!scopedResultOnly&&onReturnToSearch&&<Button variant="ghost" size="small" disabled={busy||draftBusy} onClick={returnToSearch}>返回搜索</Button>}
  {!liveMessageOriginalOnly&&(!entryBack||mayDismissRestored)&&<IconButton icon={IconClose} aria-label={view.restoredWorkReturn===true||returnToLibraryDocument?'关闭文件':sourceReturnLabel} title={view.restoredWorkReturn===true||returnToLibraryDocument?'关闭文件':sourceReturnLabel} disabled={pending>0||draftBusy} onClick={close}/>}
 </header>
 {mode==='original'&&view.ready&&runIndex>=0&&<nav className="pw-result-file-navigation" aria-label="本轮已列出的文件">
  {browseRunFile(-1)}<span role="status">第 {runIndex+1} 份 · 已列出 {runFiles.length} 份</span>{browseRunFile(1)}
 </nav>}
 <div className="pw-drawer-body pw-file-page-scroll" ref={scroller} onScroll={rememberPosition}>
 {receivedOriginal&&<p className="pw-field-hint">这是原设备交给本机的固定文件，不是对端已经完成目标的证明。{topic&&'带回原话题只添加文件引用，不会发送。'}</p>}
 {file.sourceRunEvidence==='original_snapshot_capture'&&file.sourceKind==='remote_result_snapshot'&&<p className="pw-field-hint">这份文件在原主机作业结束后被选中并固定。采集轮次已核对，不代表文件一定由该作业生成。</p>}
 {view.busy&&<div className="pw-action-row"><p role="status">{pdfPageText?(savePdfPagePending?'正在保存本页文字…':'正在读取本页文字…'):wordExcerpt?
  (view.wordExcerptSaveAttempted?'正在保存这段摘录…':'正在核对这段原文…'):copyStage==='writing'?'正在写入并核对这次另存…':'正在核对原记录…'}</p><Button variant="ghost" onClick={()=>run('cancel')}>{pdfPageText?(savePdfPagePending?'停止等待':'取消本页读取'):copyStage==='writing'?'停止本次另存':'停止本次读取'}</Button></div>}
 {!reportError&&!attachmentChoiceError&&(error||view.error)&&<p className="pw-error" role="alert">{error||view.error}</p>}{view.notice&&<p role="status">{view.notice}</p>}
 {!view.ready&&!draftDesk&&<p>回到当前工作区后继续查看，原文件、记录和当前输入保留。</p>}
 {draftDesk?(draftDesk.loading&&(draftDesk.visit.entry!=='catalog'||!draftDeskActive)?
  <p role="status">{draftDesk.visit.entry==='blank'?'正在打开空白文档…':
   draftDesk.visit.entry==='resume'?'正在核对生成前的原草稿…':draftDesk.visit.entry==='quoted'?'正在打开刚保留的草稿…':'正在打开本机文档草稿…'}</p>:
  <AttachmentStandaloneDrafts active={draftDeskActive} recoverable={draftDeskReadOnly} loading={draftDesk.loading} initial={draftDesk.initial}
  generationPending={!!draftDesk.visit.pendingDocument} initialBlank={draftDesk.visit.entry==='blank'} initialResume={draftDesk.visit.entry==='resume'||draftDesk.visit.entry==='quoted'}
  fromMessage={draftDesk.visit.entry==='quoted'} initialTxt={draftDesk.initialTxt}
  warmReentry={draftDesk.visit.warmReentry} warmResumeKey={suspendedDraftKey} onWarmResume={key=>resumeSavedDraft(draftDesk.visit,key)}
  invoke={(command,args)=>draftAct(draftDesk.visit,command,args)} registerExit={handler=>{draftDeskExit.current=handler}}
  onExit={close=>leaveDraftDesk(draftDesk.visit,close)}/>):view.documentDraft?<AttachmentDocumentEditor key={attachmentDocumentKey(view)} original={view.documentDraft} baseVersion={draftBaseVersion} unpublished={view.documentDraftEdited===true} windowId={view.windowId} foregroundReady={view.ready===true} pendingTask={view.pendingDocumentTask} busy={busy} act={act} beforeBack={registerBeforeBack}
  retainLinkedDraft={view.canRetainLinkedDocumentDraft===true&&!onReturnToSearch}/>:!view.ready||(passiveGenericPdf&&!hasOriginal)?null:view.comparison?<OriginalImageComparison key={`${view.windowId}:${rows(view.comparison).map(source=>source.id).join(':')}`} sources={rows(view.comparison)} windowId={view.windowId}/>:view.processingOpen?<AttachmentProcessingEditor key={view.processingInfo?.sourceIdentity} info={obj(view.processingInfo)} review={obj(view.processingReview)} reviewId={view.processingReviewId} pendingTask={view.pendingProcessingTask} busy={busy} act={act}/>:liveMessageOriginalOnly&&!hasOriginal&&view.savedChatLinkedOnly!==true?null:Object.keys(original).length?<><AttachmentOriginalReader source={original} windowId={view.windowId} showTitle={false}
  findBeforeLongText={!!textScope&&canSaveOriginal} onTextDisplayed={textScope?textDisplayed:undefined} mayCopyWorkWordPassage={mayCopyWorkWordPassage}
  showingPdfPageText={!!pdfPageText} onReadPdfPageText={offerPdfPageText?requestPdfPageText:undefined}
  showingWordExcerpt={!!wordExcerpt} onReadWordExcerpt={offerWordExcerpt?requestWordExcerpt:undefined} onOpenSavedWordExcerpt={shownWordResult?openSavedWordExcerpt:undefined}
  pdfCopyViewport={copyViewport}
  recovery={passiveGenericPdf?undefined:{canSave:canSaveOriginal&&!pythonOutputFile,busy:busy||draftBusy,onOpenInReader:offerReader?()=>run('openInReader'):undefined}}/>
  {passiveGenericPdf&&original.sourceUnavailable===false&&!!original.error&&<p className="pw-muted">可返回文件详情，再选择另存或用其他应用打开原件。</p>}
  {pdfPageTextView}{wordExcerptView}</>:view.documentOpen?<section><h3>{file.name||'原文件'}</h3><div className="pw-action-row">{Number(view.document?.nextUnit)>0&&<Button variant="secondary" disabled={busy} onClick={()=>run('read',{unit:view.document.nextUnit,chunk:view.document.nextChunk||0})}>读取后续范围</Button>}{view.canRequestOcr&&<Button variant="secondary" disabled={busy} onClick={()=>run('ocr')}>识别原图或 PDF 文字</Button>}{view.canSaveRead&&<Button variant="primary" disabled={busy} onClick={()=>run('saveRead')}>保存当前已读范围为文件</Button>}</div><DocumentProjection value={obj(view.document)}/><p className="pw-muted">文字摘录只包含当前已查看的部分，并保留来源位置。</p></section>:view.team?<section><h3>{snapshot.artifact?.path||'成员的固定文件成果'}</h3><div className="pw-action-row"><Button variant="primary" disabled={busy} onClick={()=>run('openOriginal')}>查看原件</Button><Button variant="secondary" disabled={busy||view.exportPending} onClick={()=>run('export')}>导出到手机</Button><Button variant="ghost" disabled={busy} onClick={()=>run('newerTeam')}>核对较新成果</Button>{view.hasPreviousTeamArtifact&&<Button variant="ghost" disabled={busy} onClick={()=>run('previousTeam')}>返回上一份成果</Button>}{view.canReadTeamDifference&&<Button variant="ghost" disabled={busy} onClick={()=>run('teamDifference')}>比较原项目当前文件</Button>}<Button variant="ghost" disabled={busy} onClick={()=>run('continueTask')}>回原分工</Button></div>{typeof snapshot.content==='string'&&<pre className="pw-file-text">{snapshot.content}</pre>}{typeof snapshot.preview?.content==='string'&&<pre className="pw-file-text">{snapshot.preview.content}</pre>}<FileDetails value={snapshot} title="原成果与读取范围"/>{snapshot.nextOffset!=null&&<Button variant="ghost" disabled={busy} onClick={()=>run('readTeam',{offset:snapshot.nextOffset})}>读取后续内容</Button>}{view.teamDifference&&<><FileDetails value={view.teamDifference} title="原文件差异"/>{view.teamDifference.nextOffset!=null&&<Button variant="ghost" disabled={busy} onClick={()=>run('teamDifference',{offset:view.teamDifference.nextOffset})}>读取后续差异</Button>}</>}</section>:<>
 {otherPendingDocument&&<p className="pw-muted">另有文档待核对，可回「成果」核对。</p>}
 {!scopedResultOnly&&(documentRecord||pendingDocumentHere)&&<div className="pw-action-row">{pendingDocumentHere&&<p>有原文档动作待核对。</p>}<Button variant="secondary" disabled={busy} onClick={()=>run('queryDocument')}>{pendingDocumentHere?'核对原文档结果':'核对这条文档记录'}</Button></div>}{!scopedResultOnly&&view.pendingProcessingTask&&<div className="pw-action-row"><p>有原加工动作待核对。</p><Button variant="secondary" disabled={busy} onClick={()=>run('queryProcessing')}>核对原加工结果</Button></div>}
 {hasFile?<section className="pw-outcome-file-detail">
 <p className="pw-muted">{file.mimeType} · {fileBytes(file.size??file.sizeBytes)}{(file.parentArtifact||file.source?.parentArtifact)&&' · 基于已有成果生成'}</p>
 {view.group&&<p className="pw-muted">这是原群任务与原受众的文件。群投递回执在原任务中核对。</p>}
 <div className="pw-action-row">
  <Button variant="primary" disabled={busy} onClick={()=>run('openOriginal')}>{detailPreviewLabel}</Button>
  {canReturnGeneratedDraft&&generatedDraftAction('secondary')}
  {reuseAction}
  {!scopedResultOnly&&exportAction}
  {canEditDocument&&editDocument('ghost')}
  {documentParent&&<Button variant="ghost" aria-label={`查看原版本：${documentParent.parentName}`} disabled={busy||draftBusy||view.exportPending}
   onClick={()=>void openDocumentParent()}>查看原版本</Button>}
  {wordOriginAction}
  {bookReplyAction}
  {excerptPdf&&<Button variant="ghost" disabled={busy||draftBusy||view.exportPending}
   onClick={openExcerptSource}>{justSavedWorkPdf?'返回这份 PDF 的文件信息':'查看来源 PDF 信息'}</Button>}
  {mode==='detail'&&view.ready===true&&privateFiles&&!onReturnToSearch&&typeof view.libraryDocumentActionId==='string'&&
   view.libraryDocumentActionId===file.sourceTaskId&&<Button variant="ghost" disabled={busy||draftBusy||view.exportPending}
    onClick={()=>run('openDocumentAction',{taskId:view.libraryDocumentActionId})}>查看原生成记录</Button>}
  {!localOnlyFile&&!scopedResultOnly&&fileMenu}
 </div>
 {copyFeedback()}
 {!localOnlyFile&&!scopedResultOnly&&<Accordion className="pw-attachment-tools"><AccordionItem id="read-source" title="读取文字与表格" description="保留页码、位置与识别说明"><section>{file.mimeType==='application/pdf'&&<TextField label="从第几页开始" value={page} onChange={setPage}/>}<div className="pw-action-row"><Button variant="secondary" disabled={busy||!/^\d+$/.test(page)||Number(page)<1} onClick={()=>run('read',{unit:Number(page),chunk:0})}>读取文字与表格</Button>{view.canRequestOcr&&<Button variant="secondary" disabled={busy} onClick={()=>run('ocr')}>本机识别图片文字</Button>}</div></section></AccordionItem></Accordion>}
 {!scopedResultOnly&&privateFiles&&(String(file.mimeType).startsWith('image/')||['video/mp4','audio/mp4'].includes(file.mimeType))&&<Accordion className="pw-attachment-tools"><AccordionItem id="continue-source" title="图像与媒体处理" description="裁剪、转换或对照原版本"><section><div className="pw-action-row">{(String(file.mimeType).startsWith('image/')||['video/mp4','audio/mp4'].includes(file.mimeType))&&<Button variant="secondary" disabled={busy||!!view.pendingProcessingTask} onClick={()=>run('openProcessing')}>{String(file.mimeType).startsWith('image/')?'裁剪、缩放或转换原图':'剪辑原媒体片段'}</Button>}{String(file.mimeType).startsWith('image/')&&(file.parentArtifact||file.source?.parentArtifact)&&<Button variant="ghost" disabled={busy} onClick={()=>run('compareParent')}>对照父原图</Button>}</div></section></AccordionItem></Accordion>}<FileDetails value={file} title="来源与文件版本"/></section>:<>
 {focusedDraft?<p role="status">{view.pendingDocumentTask?'本次生成仍只按原记录核对，不会重复生成。':
  '本机草稿仍保留。可以返回原消息，不会打开其他草稿。'}</p>:
 <section className="pw-file-library" aria-label={view.choosingSource?'选择来源文件':'文件集合'}>
 <header className="pw-file-library-header"><span className="pw-attachment-library-scope">{view.choosingSource?'选择来源':resultFilesOnly?'本轮文件':view.taskId?'原工作文件':'私人文件库'}{hasLibraryFiles&&(filterActive?` · ${matchingCount} 项匹配`:` · 当前列出 ${fileCount} 项`)}</span>{!busy&&(catalogUnconfirmed||error||view.error)?<Button variant="ghost" leadingIcon={IconRefresh} onClick={refreshList}>重新读取文件</Button>:<IconButton icon={IconRefresh} aria-label="重新读取文件" title="重新读取文件" disabled={busy} onClick={refreshList}/>}</header>
 {!busy&&catalogIncomplete&&<p className="pw-muted" role="status">{resultFilesOnly?(hasLibraryFiles?'本轮文件尚未核全。可以先打开已确认属于本轮的文件。':'本轮文件尚未核全，暂未核到可列出的原件。'):(hasLibraryFiles?'文件清单未读全，已列出的可用版本仍可打开。':'文件清单未读全，目前无法确认有哪些文件。')}</p>}
 {!busy&&catalogUnread&&!catalogIncomplete&&!error&&!view.error&&<p className="pw-muted" role="status">文件清单尚未读取。</p>}
 {privateLibrary&&<>
  {!scopedResultOnly&&bridge.getState().capabilities.commands.includes('projects.savedBuildWeb.read')&&<p className="pw-field-hint">通过「确认保存网页包」保存的网页，在原项目的「工作 → 已保存的构建网页」查找。其他固定网页，请从「我的工作」打开原工作，在「概览 → 项目固定成果」查看。</p>}
  {offerDraftShelf&&<div role="group" aria-label="本机文档草稿" className="pw-action-row">
   <Button variant="secondary" leadingIcon={IconFolder} data-local-document-drafts disabled={busy}
    onClick={()=>openDraftDesk('catalog')}>打开本机文档草稿</Button>
   {offerDraftDesk&&!pendingDraftGeneration&&(!catalogUnconfirmed||hasLibraryFiles)&&
    <Button variant="ghost" leadingIcon={IconFileAdd} data-new-document-draft disabled={busy||selectionPending}
     onClick={()=>openDraftDesk('blank')}>新建 Word / PDF 草稿</Button>}
  </div>}
  {(!catalogUnconfirmed||hasLibraryFiles)&&libraryActions}
  {importMessage&&<p role="status" className="pw-field-hint">{importMessage}
   {confirmedImport&&registeredCount>recentSources.length&&!busy&&' 当前清单未列齐这批已登记文件，可重新读取，不必再导入。'}</p>}
  {continueTxtRequest&&<Button variant="secondary" disabled={busy||draftBusy} aria-description="重新打开私人文件库，不打开原件或新建草稿"
   onClick={event=>{if(event.nativeEvent.isTrusted)run('continueImportedTxtLibrary',{importRequestId:continueTxtRequest})}}>继续到成果</Button>}
  {shareMessage&&<p role="status" className="pw-field-hint">{shareMessage}
   {confirmedShare&&!busy&&shareRegistered>sharedSources.length&&' 当前清单未核对到全部已登记版本，可重新读取，不必再导入。'}</p>}
 </>}
 {filterable&&((workSourcePicker?fileCount>1:hasLibraryFiles)||filterActive)&&<div role="search" aria-label="查找当前清单中的文件" className="pw-file-search"
  onClickCapture={event=>{if(trustedSearchControl(event))relinquishLibraryRowReturn()}}
  onInputCapture={event=>{if(event.nativeEvent.isTrusted&&event.target instanceof HTMLInputElement&&event.target.type!=='hidden')relinquishLibraryRowReturn()}}
  onChangeCapture={event=>{if(event.nativeEvent.isTrusted&&event.target instanceof HTMLSelectElement)relinquishLibraryRowReturn()}}
  onKeyDownCapture={event=>{if(trustedSearchControl(event)&&(event.key.length===1||event.nativeEvent.isComposing||
   ['Enter','ArrowDown','ArrowUp','Backspace','Delete','Process'].includes(event.key)))relinquishLibraryRowReturn()}}>
  <TextField label="文件名" placeholder="在当前清单查找" leadingIcon={IconSearch} value={nameFilter} onChange={name=>updateFilter(name.slice(0,180),formatFilter)}/>
  {!workSourcePicker&&<SelectField label="文件类别" compactValue options={catalogFormats} value={formatFilter} onChange={format=>updateFilter(nameFilter,format)}/>}
 </div>}
 {filterActive&&!busy&&<div className="pw-action-row"><p className="pw-muted" role="status">{matchingCount?`已读取的文件中有 ${matchingCount} 项匹配。`:'当前已读取的文件没有匹配项。'}{catalogIncomplete&&' 文件清单尚未读全。'}</p>
  <Button variant="ghost" onClick={event=>{if(event.nativeEvent.isTrusted)relinquishLibraryRowReturn();updateFilter('','all')}}>清除筛选</Button></div>}
 {recentPdf&&fileList('继续上次阅读',[recentPdf],true,true)}
 {privateLibrary&&recentSourcesList}
 {privateLibrary&&sharedSourcesList}
 {hasLibraryFiles?privateLibrary?<>{outputsList}{sourcesList}{editableSourceList}</>:view.taskId&&!view.choosingSource?<>{outputsList}{sourcesList}{libraryActions}</>:<>{libraryActions}{sourcesList}{outputsList}</>
 :!busy&&!catalogUnconfirmed&&!error&&!view.error?<div className="pw-file-library-empty"><IconFolder size={28} aria-hidden="true"/><h2>{view.choosingSource?'还没有可关联的文件':resultFilesOnly?'本轮暂无可读取的文件':view.taskId?'这个任务还没有文件':'资料库还没有文件'}</h2><p>{view.choosingSource?'导入文件后，就可以把它关联到当前任务。':view.taskId?resultFilesOnly?(scopedResultOnly?'没有用其他轮次或同名文件替代。':'本轮文件将在这里显示，也可回原工作核对已保存的文件。'):'任务中使用的文件和生成的成果会出现在这里。':'从手机选择文件。支持的 PDF、Word（.docx）、Excel（.xlsx）和文本可在本机查看，不用模型。'}</p>{!privateLibrary&&libraryActions}</div>:!privateLibrary&&!catalogUnconfirmed&&libraryActions}
 {privateFiles&&!view.taskId&&!catalogUnread&&(documentActions.length>0||mayContinueEmptyDocumentPage)&&<details>
  <summary>文档生成记录</summary>
  {!documentActions.length&&<p className="pw-muted" role="status">本页没有可打开的记录，可以继续查找更早的记录。</p>}
  {documentActions.map(action=><div className="pw-file-action-record" key={action.taskId}>
   <div><strong>{action.name||'文档'} · {String(action.format||'').toUpperCase()}</strong><small>{action.status}</small></div>
   <Button variant="ghost" disabled={busy} onClick={()=>run('openDocumentAction',{taskId:action.taskId})}>打开原记录</Button>
  </div>)}
  {hasOlderDocumentActions&&<Button variant="ghost" disabled={busy} onClick={()=>run('olderDocumentActions')}>读取更早记录</Button>}
  <FileDetails value={documentHistory.gaps} title="未完整核对的记录"/>
 </details>}
 </section>}
 </>}
 {media.map(job=><Accordion className="pw-attachment-tools" key={job.operationId}><AccordionItem id={`media-${job.operationId}`} title="原媒体操作与取回" description={job.status==='completed'?'已完成':job.status||'尚待核对'}><section className="pw-file-media-job"><div className="pw-action-row"><Button variant="secondary" disabled={busy} onClick={()=>run('refreshMedia',{operationId:job.operationId})}>查询原操作</Button><Button variant="secondary" disabled={busy} onClick={()=>run('fetchMedia',{operationId:job.operationId})}>取回实际成果</Button>{view.observing?<Button variant="ghost" onClick={()=>run('stopObservation')}>停止观察</Button>:<Button variant="ghost" disabled={busy} onClick={()=>run('observeMedia',{operationId:job.operationId})}>观察进度</Button>}<Button variant="ghost" disabled={busy} onClick={()=>run('cancelMedia',{operationId:job.operationId})}>请求取消原操作</Button></div>{view.progress&&<FileDetails value={view.progress} title="实际进度回执"/>}<FileDetails value={job} title="生成与保存回执"/></section></AccordionItem></Accordion>)}
 {view.group&&<FileDetails value={snapshot.delivery||snapshot} title="原群保存与投递回执"/>}{!localOnlyFile&&!scopedResultOnly&&view.taskId&&view.restoredWorkReturn!==true&&<Button variant="ghost" disabled={busy} onClick={()=>run('continueTask')}>{resultFilesOnly?'回原工作核对其他文件':'回原任务继续处理'}</Button>}
 </>}
 {continuationVisible&&<div ref={continuationHeading} tabIndex={-1} className="pw-file-continuation-target">
  <AttachmentVersionContinuation key={continuationKey} original={view.continuationDraft} foregroundReady={view.ready===true}
    busy={busy} act={act} beforeBack={registerBeforeBack} onReturn={returnToOriginalWork}
    onAdopt={mode==='original'&&onAdoptFileNote?note=>{
     const frame=attachmentFrame(bridge.getState().state.attachmentWorkspace);
     if(!frame||frame.windowId!==view.windowId)throw new Error('原文件窗口已变化，备注仍保留在原件。');
     return onAdoptFileNote(note,frame,()=>act('close',{},frame,false));
    }:undefined}/>
 </div>}
 {privateFiles&&hasFile&&view.taskId&&view.ready&&(mode==='detail'||mode==='original')&&view.continuationDraft?.available===false&&
  <p className="pw-error" role="alert">{view.continuationDraft.error||'原版本补充草稿暂不可读取，请重新读取这份成果。'}</p>}
 </div>
 {!passiveGenericPdf&&mode==='original'&&privateFiles&&hasFile&&view.ready&&(!scopedResultOnly||useInTopic||canMoveReportOriginal||offerReplyAttachment)&&<div className="pw-file-use-footer" role="group" aria-label={replyDocumentOnly?'使用原答复文档':file.role==='source'?'使用来源文件':'使用此成果'}>
  {offerReplyAttachment&&<Button variant="secondary" disabled={busy||draftBusy||view.exportPending}
   onClick={()=>void chooseReplyAttachment()}>选作草稿附件…</Button>}
  {canMoveReportOriginal&&<>
   <Button variant="primary" disabled={busy||draftBusy||view.exportPending} aria-busy={reportFlight.current||undefined} onClick={()=>void openReportOriginalWork()}>到原工作另存这份</Button>
   <p className={'pw-file-draft-status'+(reportError?' pw-error':'')} role={reportError?'alert':undefined}>{reportError||'汇报中只读。进入原工作后，再选择保存位置。'}</p>
  </>}
  {confirmed&&copyFeedback(compactReaderActions)}
  {reviewManagedWorkApk&&<Button variant="primary" disabled={busy||draftBusy||view.exportPending}
   onClick={()=>run('external',{reviewProjectApk:true})}>核对这份 APK</Button>}
  {saveFirst&&exportAction}
  {attachInTopic&&<Button variant={preferSave?'secondary':'primary'} className="pw-file-draft-action" disabled={busy||draftBusy||view.exportPending} aria-busy={draftBusy||undefined}
   onClick={()=>void handOffFile(true,topic,false,true)}>选作原话题附件…</Button>}
  {useInTopic&&!compactReaderActions&&<Button variant={onChooseResultAttachment?'ghost':preferSave?'secondary':'primary'} className="pw-file-draft-action"
   disabled={busy||draftBusy||view.exportPending} aria-busy={draftBusy||undefined} onClick={referenceInTopic}>{topicPositionLabel}</Button>}
  {readerActions}
  {bookReplyAction}
  {topicFeedback}
  {canReturnGeneratedDraft&&generatedDraftAction(preferSave||useInTopic?'secondary':'primary')}
  {offerDocumentEdit&&editDocument(canReturnGeneratedDraft?'ghost':preferSave||useInTopic?'secondary':'primary')}
  {wordOriginAction}
  {txtOffer&&<Button variant="secondary" disabled={busy||draftBusy||view.exportPending}
   aria-description="将已显示的文字带到未保存的本机草稿，不改动原件"
   onClick={event=>{if(event.nativeEvent.isTrusted)void startTxtDraft()}}>用这份 TXT 新建文档草稿</Button>}
  {markdownTarget&&(markdown.state==='READY'||markdown.state==='UNKNOWN')&&
   <Button variant="secondary" disabled={busy||draftBusy||view.exportPending} onClick={retainMarkdown}>
    {markdown.state==='UNKNOWN'?'核对这次草稿':'保留原文为本机文档草稿'}</Button>}
  {markdownTarget&&markdown.state==='UNKNOWN'&&<p className="pw-file-draft-status" role="status">
   保留结果尚待核对。离开后可先到「成果 → 本机文档草稿」查找；不会自动重试。</p>}
  {markdownTarget&&['SAVED','VERIFIED'].includes(markdown.state)&&Number.isSafeInteger(markdown.savedAt)&&markdown.savedAt>0&&
   <p className="pw-file-draft-status" role="status">已保留 Word 草稿「{markdown.name}」（{new Date(markdown.savedAt).toLocaleString()}）。
    可在「成果 → 本机文档草稿」继续编辑或核对生成，不会改动原件。</p>}
  {canChooseAttachment&&<Button variant={preferSave||offerDocumentEdit||canReturnGeneratedDraft?'secondary':'primary'} className="pw-file-draft-action" disabled={busy||draftBusy||view.exportPending}
   aria-busy={attachmentFlight.current||undefined} onClick={()=>void chooseAttachment()}>{attachmentFlight.current?'正在核对原文件…':`选作「${attachmentDraft?.label}」的附件…`}</Button>}
  {attachmentChoiceError&&<p className="pw-file-draft-status pw-error" role="alert">{attachmentChoiceError}</p>}
  {canKeepSupplement&&!compactReaderActions&&<Button variant={preferSave||useInTopic||offerDocumentEdit?'secondary':'primary'} disabled={busy||draftBusy} onClick={revealContinuation}>{supplementLabel}</Button>}
  {!canChooseAttachment&&reuseAction}
  {canSaveOriginal&&!saveFirst&&!compactReaderActions&&exportAction}
  {!compactReaderActions&&sharePdfAction(shareWorkPdf)}
  {!confirmed&&copyFeedback(compactReaderActions)}
 </div>}
 </Drawer.Content></Drawer.Portal></Drawer.Root>;
}
