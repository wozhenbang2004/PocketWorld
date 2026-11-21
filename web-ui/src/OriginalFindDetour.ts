import {useLayoutEffect,useRef,useState,type RefObject} from 'react';
import {bridge} from './bridge';
import {fileObject as obj,fileRows as rows,type FileJson as Json} from './attachment-workspace';

export type OriginalFindPosition={kind:'pdf';page:number}|{kind:'text';offset:number;encoding:string}|
 {kind:'word';unit:number;chunk:number};
type Scope={windowId:string;viewerId:string;identity:string;sha256:string;fileId:string;epoch:number};
type Painted={scope:Scope;position:OriginalFindPosition;pane:HTMLElement;body:HTMLElement;raster?:HTMLImageElement};
type Pose={inset:number;left:number;top:number;paneWidth:number;bodyWidth:number;contentWidth:number;contentHeight:number};
type Spot={scope:Scope;position:OriginalFindPosition;pose?:Pose};
type Detour={start:Spot;landed:OriginalFindPosition};
type Arrival={detour:Detour;gesture:number;acknowledged:boolean};
type Options={root:RefObject<HTMLElement|null>;windowId:string;source:Json;actual:Json;ready:boolean;enabled:boolean;failed:boolean;
 read:(position:OriginalFindPosition)=>Promise<Json|null>;beforeReturn:()=>void};

const encodings=['UTF-8','UTF-16LE','UTF-16BE','GB18030','ISO-8859-1'];
const uuid=(value:unknown):value is string=>typeof value==='string'&&/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(value);
const digest=(value:unknown):value is string=>typeof value==='string'&&/^[a-f0-9]{64}$/.test(value);
const integer=(value:unknown,min:number,max:number):value is number=>
 typeof value==='number'&&Number.isSafeInteger(value)&&value>=min&&value<=max;

function nativeReader(options:Options):{scope:Scope;kind:unknown;info:Json}|undefined{
 const {source,windowId}=options,native=bridge.getState(),owner=obj(native.view);
 const view=obj(native.state.attachmentWorkspace),file=obj(view.file),reader=obj(view.originalFile);
 if(!options.enabled||document.hidden||!windowId||native.state.chatAttachmentPickerOpen!==false||owner.section!==null||
    owner.taskId!==(view.taskId??null)||view.open!==true||view.ready!==true||view.windowId!==windowId||
    view.group!==false||view.team!==false||view.deviceOriginalOnly!==false||view.choosingSource!==false||
    view.historyResultOnly!==false||view.reportResultOnly!==false||view.canSaveOriginal!==true||
    view.documentOpen!==false||view.documentDraft!=null||view.processingOpen!==false||view.comparison!=null||
    !uuid(file.id)||!uuid(reader.id)||!digest(reader.identity)||!digest(reader.sha256)||file.sha256!==reader.sha256||
    reader.sourceUnavailable!==false||reader.id!==source.id||reader.sha256!==source.sha256||
    reader.identity!==source.identity||reader.kind!==source.kind)return;
 return {scope:{windowId,viewerId:reader.id,identity:reader.identity,sha256:reader.sha256,fileId:file.id,epoch:bridge.getDocumentEpoch()},
  kind:reader.kind,info:obj(reader.information)};
}
function sameScope(a:Scope,b:Scope){
 return a.windowId===b.windowId&&a.viewerId===b.viewerId&&a.identity===b.identity&&a.sha256===b.sha256&&a.fileId===b.fileId&&a.epoch===b.epoch;
}
function positionOf(value:Json,kind:unknown,scope:Scope):OriginalFindPosition|undefined{
 if(kind==='pdf'&&uuid(value.pdfRasterId)&&integer(value.pages,1,1_000_000)&&integer(value.page,1,value.pages))
  return {kind:'pdf',page:value.page};
 if(value.sourceIdentity!==scope.identity||value.sha256!==scope.sha256)return;
 if(kind==='text'&&integer(value.offset,0,20*1024*1024)&&encodings.includes(value.encoding)&&typeof value.content==='string'&&value.content.length)
  return {kind:'text',offset:value.offset,encoding:value.encoding};
 if(kind==='office'&&value.format==='docx'&&value.status==='partial'&&value.coverage==='body_paragraphs_and_tables'&&
    integer(value.totalUnits,1,1_000_000)&&integer(value.unit,1,value.totalUnits)&&integer(value.chunk,0,1_000_000)&&rows(value.chunks).length)
  return {kind:'word',unit:value.unit,chunk:value.chunk};
}
function samePosition(a:OriginalFindPosition,b:OriginalFindPosition){
 if(a.kind==='pdf'&&b.kind==='pdf')return a.page===b.page;
 if(a.kind==='text'&&b.kind==='text')return a.offset===b.offset&&a.encoding===b.encoding;
 return a.kind==='word'&&b.kind==='word'&&a.unit===b.unit&&a.chunk===b.chunk;
}

function painted(options:Options):Painted|undefined{
 const native=options.ready?nativeReader(options):undefined,root=options.root.current;
 if(!native||!root?.isConnected||!root.getClientRects().length||root.closest('[hidden],[inert],[aria-hidden="true"]'))return;
 const {scope,kind,info}=native,point=positionOf(info,kind,scope),actual=positionOf(options.actual,kind,scope);
 if(!point||!actual||!samePosition(point,actual))return;
 const pane=root.closest<HTMLElement>('.pw-file-page-scroll');
 const body=root.querySelector<HTMLElement>(point.kind==='pdf'?'.pw-original-raster':point.kind==='text'?
  '.pw-original-text > .pw-file-text':'[data-word-reading-body]');
 if(!pane||!body?.isConnected||!body.getClientRects().length||body.closest('[hidden],[inert],[aria-hidden="true"]'))return;
 if(point.kind==='pdf'){
  if(options.actual.pdfRasterId!==info.pdfRasterId)return;
  const raster=body.querySelector<HTMLImageElement>(`[data-pdf-raster="${info.pdfRasterId}"] img`);
  if(!raster?.complete||raster.naturalWidth<1||!raster.getClientRects().length)return;
  return {scope,position:point,pane,body,raster};
 }
 return {scope,position:point,pane,body};
}
function measure({pane,body,raster}:Painted,remember:boolean):Pose|undefined{
 const outer=pane.getBoundingClientRect(),inner=body.getBoundingClientRect(),inset=outer.top+pane.clientTop-inner.top;
 const width=raster?.clientWidth??body.scrollWidth,height=raster?.clientHeight??body.scrollHeight;
 if(width<1||height<1||remember&&!(inset>=0&&inset<inner.height||raster&&body.scrollTop+body.scrollLeft>0))return;
 return {inset:Math.max(0,inset),left:raster?body.scrollLeft:0,top:raster?body.scrollTop:0,
  paneWidth:pane.clientWidth,bodyWidth:body.clientWidth,contentWidth:width,contentHeight:height};
}
function place(where:Painted,remembered:Pose|undefined){
 const {pane,body,raster}=where,current=measure(where,false);
 const pose=remembered&&current&&current.paneWidth===remembered.paneWidth&&current.bodyWidth===remembered.bodyWidth&&
  current.contentWidth===remembered.contentWidth&&current.contentHeight===remembered.contentHeight?remembered:undefined;
 if(raster){body.scrollTop=pose?.top??0;body.scrollLeft=pose?.left??0;}
 pane.scrollTop+=body.getBoundingClientRect().top-pane.getBoundingClientRect().top-pane.clientTop+(pose?.inset??0);
 if(body.hasAttribute('tabindex'))body.focus({preventScroll:true});
}

/** Same native reading visit only; a Find can arm one deliberate return to its previous painted range. */
export function useOriginalFindDetour(options:Options){
 const latest=useRef(options);latest.current=options;
 const lastPose=useRef<Spot|undefined>(undefined),pending=useRef<Arrival|undefined>(undefined),
  held=useRef<Detour|undefined>(undefined),gesture=useRef(0),sequence=useRef(0),mounted=useRef(false);
 const [detour,setDetour]=useState<Detour|undefined>(undefined),[returning,setReturning]=useState(false);
 const replace=(value?:Detour)=>{held.current=value;setDetour(value);};
 const clear=()=>{sequence.current++;lastPose.current=undefined;pending.current=undefined;setReturning(false);replace();};
 const savePose=()=>{
  if(pending.current||!latest.current.ready)return;
  const shown=painted(latest.current),pose=shown&&measure(shown,true);
  if(shown&&pose)lastPose.current={scope:shown.scope,position:shown.position,pose};
 };
 useLayoutEffect(()=>{
  if(!['pdf','text','office'].includes(latest.current.source.kind))return;
  mounted.current=true;
  const root=latest.current.root.current,pane=root?.closest<HTMLElement>('.pw-file-page-scroll');
  let frame=0;
  const roll=()=>{if(!frame)frame=requestAnimationFrame(()=>{frame=0;savePose()})};
  const act=()=>{gesture.current++};
  const interactions=['pointerdown','wheel','touchmove','keydown'];
  for(const event of interactions)pane?.addEventListener(event,act,{capture:true,passive:true});
  pane?.addEventListener('scroll',roll,{capture:true,passive:true});
  const hide=()=>{if(document.hidden){lastPose.current=undefined;clear()}};
  document.addEventListener('visibilitychange',hide);
  const unsub=bridge.subscribeDocumentEpoch(()=>{lastPose.current=undefined;clear()});
  return()=>{
   mounted.current=false;sequence.current++;unsub();cancelAnimationFrame(frame);
   for(const event of interactions)pane?.removeEventListener(event,act,true);
   pane?.removeEventListener('scroll',roll,true);document.removeEventListener('visibilitychange',hide);
  };
 },[]);
 const finish=(arrival:Arrival)=>{
  const shown=arrival.acknowledged?painted(latest.current):undefined;
  if(!shown||!sameScope(shown.scope,arrival.detour.start.scope)||!samePosition(shown.position,arrival.detour.start.position))return;
  pending.current=undefined;
  if(gesture.current===arrival.gesture)place(shown,arrival.detour.start.pose);
  setReturning(false);replace();
 };
 useLayoutEffect(()=>{
  const arrival=pending.current;
  if(!arrival)return;
  const now=nativeReader(latest.current);
  if(!now||!sameScope(now.scope,arrival.detour.start.scope)||options.failed){
   pending.current=undefined;setReturning(false);return;
  }
  finish(arrival);
 });
 const start=(landed:OriginalFindPosition)=>{
  const prior=painted(latest.current);
  if(!prior||prior.position.kind!==landed.kind||pending.current)return;
  const old=held.current,remembered=lastPose.current;
  const pose=measure(prior,true)??(remembered&&sameScope(prior.scope,remembered.scope)&&
   samePosition(prior.position,remembered.position)?remembered.pose:undefined);
  const start=old&&sameScope(old.start.scope,prior.scope)&&samePosition(old.landed,prior.position)?old.start:
   {scope:prior.scope,position:prior.position,pose};
  if(samePosition(start.position,landed)&&!start.pose)return; // No unproven in-page restoration.
  const visit=++sequence.current;
  return ()=>{
   const current=nativeReader(latest.current);
   if(mounted.current&&sequence.current===visit&&current&&sameScope(current.scope,start.scope))replace({start,landed});
  };
 };
 // Render from the actual current native location; DOM proof is rechecked on the click.
 const current=options.ready?nativeReader(options):undefined,point=current&&positionOf(current.info,current.kind,current.scope);
 const actual=current&&positionOf(options.actual,current.kind,current.scope);
 const available=!returning&&detour&&current&&point&&actual&&samePosition(point,actual)&&
  (point.kind!=='pdf'||options.actual.pdfRasterId===current.info.pdfRasterId)&&
  sameScope(detour.start.scope,current.scope)&&samePosition(detour.landed,point)?detour:undefined;
 const back=()=>{
  const shown=painted(latest.current),selected=held.current;
  if(!available||selected!==available||pending.current||!shown||!sameScope(shown.scope,selected.start.scope)||
     !samePosition(shown.position,selected.landed))return;
  const local=samePosition(shown.position,selected.start.position);
  const arrival:Arrival={detour:selected,gesture:gesture.current,acknowledged:local};
  ++sequence.current;pending.current=arrival;setReturning(true);
  latest.current.beforeReturn();
  if(local)return; // Same mounted exact range: never reread merely to restore its viewport.
  void latest.current.read(selected.start.position).then(result=>{
   if(!mounted.current||pending.current!==arrival)return;
   const fresh=nativeReader(latest.current),accepted=fresh&&result&&positionOf(result,fresh.kind,fresh.scope);
   if(!fresh||!sameScope(fresh.scope,selected.start.scope)||!accepted||!samePosition(accepted,selected.start.position)){
    pending.current=undefined;setReturning(false);return;
   }
   arrival.acknowledged=true;
   // The ACK and native projection can settle in either order.
   finish(arrival);
  });
 };
 return {start,clear,available:!!available,returning,back};
}
