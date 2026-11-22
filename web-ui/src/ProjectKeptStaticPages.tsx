import { useLayoutEffect, useRef, useState, type MouseEvent } from 'react';
import { bridge, type PocketSnapshot } from './bridge';
import { Button, SettingsDialog } from './forms-library';
import { IconChevronRight, IconFile } from './icons';
import { useProjectPreviewViewport } from './ProjectPreviewPane';

const prefix='projects.keptStaticPages.';
export const keepStaticPage=prefix+'keep';
const listPages=prefix+'list',openPage=prefix+'open',closeShelf=prefix+'close';
const reviewPending=prefix+'pending.review',abandonPending=prefix+'pending.abandon',dismissPending=prefix+'pending.dismiss';
const closePreview='projects.preview.close';
const available=(action:string)=>bridge.getState().capabilities.commands.includes(action);
export const supportsKeptStaticPages=(can:(action:string)=>boolean)=>
  [keepStaticPage,listPages,openPage,closeShelf,reviewPending,abandonPending,dismissPending,'projects.preview.viewport',closePreview].every(can);
const row=(value:unknown):Record<string,unknown>=>value&&typeof value==='object'&&!Array.isArray(value)?value as Record<string,unknown>:{};
const id=(value:unknown):value is string=>typeof value==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(value);
const hash=(value:unknown):value is string=>typeof value==='string'&&/^[a-f0-9]{64}$/.test(value);
const integer=(value:unknown,min=0,max=Number.MAX_SAFE_INTEGER):value is number=>
  typeof value==='number'&&Number.isSafeInteger(value)&&value>=min&&value<=max;
const maxPages=4,maxStoredBytes=8*1024*1024;
type Page={projectId:string;checkoutId:string;versionId:string;entrySha256:string;manifestIdentity:string;savedAt:number;resourceCount:number;bytes:number};
export function keptStaticPage(value:unknown,projectId:string):Page|null {
  const p=row(value);
  if(p.kind!=='owner_saved_static_page'||p.projectId!==projectId||p.entryPath!=='index.html'||
    !id(p.checkoutId)||!id(p.versionId)||!hash(p.entrySha256)||!hash(p.manifestIdentity)||
    !integer(p.savedAt,1,8.64e15)||!integer(p.resourceCount,1,25)||!integer(p.bytes,0,4*1024*1024))return null;
  return {projectId,checkoutId:p.checkoutId,versionId:p.versionId,entrySha256:p.entrySha256,manifestIdentity:p.manifestIdentity,
    savedAt:p.savedAt,resourceCount:p.resourceCount,bytes:p.bytes};
}
const samePage=(p:Page,other:Page|null)=>!!other&&p.projectId===other.projectId&&p.checkoutId===other.checkoutId&&
  p.versionId===other.versionId&&p.entrySha256===other.entrySha256&&p.manifestIdentity===other.manifestIdentity&&
  p.savedAt===other.savedAt&&p.resourceCount===other.resourceCount&&p.bytes===other.bytes;
type Binding={projectId:string;visitId:string};
type Pending='none'|'reviewable'|'unconfirmed';
type Catalog={shelfHandle:string;versions:Page[];pendingState:Pending;maxVersions:number;maxStoredBytes:number};
const sameVisit=(value:Record<string,unknown>,visit:Binding)=>value.projectId===visit.projectId&&value.visitId===visit.visitId;
const sameShelf=(value:Record<string,unknown>,visit:Binding,catalog:Catalog)=>sameVisit(value,visit)&&value.shelfHandle===catalog.shelfHandle;
function shelfCatalog(value:unknown,visit:Binding,previous:Catalog|null):Catalog|null {
  const c=row(value);
  if(c.kind!=='owner_saved_static_page_catalog'||!sameVisit(c,visit)||!id(c.shelfHandle)||
    (previous&&c.shelfHandle!==previous.shelfHandle)||!Array.isArray(c.versions)||c.versions.length>maxPages||
    c.maxVersions!==maxPages||c.maxStoredBytes!==maxStoredBytes||!integer(c.storedBytes,0,maxStoredBytes)||
    (c.pendingState!=='none'&&c.pendingState!=='reviewable'&&c.pendingState!=='unconfirmed'))return null;
  const versions:Page[]=[];
  for(const value of c.versions){
    const page=keptStaticPage(value,visit.projectId);
    if(!page||versions.some(p=>p.versionId===page.versionId||p.manifestIdentity===page.manifestIdentity))return null;
    versions.push(page);
  }
  if(versions.reduce((sum,p)=>sum+p.bytes,0)>c.storedBytes)return null;
  return {shelfHandle:c.shelfHandle,versions,pendingState:c.pendingState,maxVersions:c.maxVersions,maxStoredBytes:c.maxStoredBytes};
}
type Frame={sessionId:string;generation:number;ready:boolean;visible:boolean;error:boolean};
function previewFrame(value:unknown,visit:Binding,catalog:Catalog,page:Page):Frame|null {
  const p=row(value);
  if(p.kind!=='owner_kept_static_page_preview'||!sameShelf(p,visit,catalog)||!samePage(page,keptStaticPage(p.page,visit.projectId))||
    !id(p.sessionId)||!integer(p.documentGeneration,p.ready===true?1:0)||typeof p.ready!=='boolean'||typeof p.visible!=='boolean'||
    !(p.error===null||typeof p.error==='string'))return null;
  return {sessionId:p.sessionId,generation:p.documentGeneration,ready:p.ready,visible:p.visible,error:p.error!==null};
}

function ownerRevisions(live:PocketSnapshot,projectId:string){
  const s=live.state,v=row(live.view);
  if(document.hidden||bridge.getDocumentEpoch()<1||live.host?.ready!==true||!supportsKeptStaticPages(action=>live.capabilities.commands.includes(action))||
    v.section!==null||v.taskId!==null||s.projectId!==projectId||s.filePath!==null||s.fileLoading!==false||s.fileSaving!==false||
    s.conversationReader!==null||s.attachmentWorkspace?.open!==false||s.chatAttachmentPickerOpen!==false||s.queuedInputsPage!==null||
    s.attachmentImporting!==false||s.localSourceIntake?.window||s.projectGitImport?.open||s.projectLivePreview?.mounted===true||s.projectLivePreviewChecking||
    !Array.isArray(s.projects)||s.projects.filter(project=>row(project).id===projectId).length!==1)return null;
  const revisions=[v.resumeRevision,s.requestedProjectRevision,s.requestedTaskRevision,s.requestedConnectionRevision,s.requestedExecutionPlanRevision];
  return revisions.every(value=>integer(value))?revisions.join(':'):null;
}
const inactiveRenderer=(value:unknown):string|null=>{
  if(value===null)return '';
  const p=row(value);
  return p.kind==='bundle'&&p.visible===false&&p.localWebWork===false&&id(p.sessionId)?p.sessionId:null;
};
type Focus={node:HTMLButtonElement;scroll:HTMLElement|null;top:number};
const remember=(node:HTMLButtonElement):Focus=>{
  const scroll=node.closest<HTMLElement>('.pw-settings-dialog-content');
  return {node,scroll,top:scroll?.scrollTop??0};
};
type ReadVisit={page:Page;focus:Focus;ack?:Frame;frame?:Frame;timer?:number;returnRequested?:true;
  closing?:true;closeAck?:true;published?:true};
type Review={id:string;deadline:number;focus:Focus;timer:number};
type Visit=Binding&{document:number;revisions:string;inactiveRenderer:string;trigger:HTMLButtonElement;catalog:Catalog|null;
  catalogPublished?:true;catalogTimer?:number;
  reader?:ReadVisit;review?:Review;busy?:'list'|'review'|'abandon'|'dismiss';notice:string;problem:boolean;
  closing?:Promise<boolean>;closeUnconfirmed?:true;retired?:true;focusOnClose?:boolean};
type Presentation=Pick<Visit,'catalog'|'notice'|'problem'|'busy'> & {reader?:ReadVisit;review?:Review;closing:boolean};
export type KeptStaticShelfNavigation={leave:()=>Promise<boolean>|undefined;back:()=>boolean};

const clearOpenTimer=(reader?:ReadVisit)=>{if(reader?.timer!==undefined)window.clearTimeout(reader.timer);};
const clearReviewTimer=(review?:Review)=>{if(review)window.clearTimeout(review.timer);};
const focusable=(node:HTMLElement)=>node.isConnected&&!node.closest('[hidden],[inert]')&&node.getClientRects().length>0;
const publicationWaitMs=4_000; // Native reaps a renderer that has not received a viewport.

export function ProjectKeptStaticPages({project,active,registerNavigation}:{project:{id:string;name:string};active:boolean;
  registerNavigation:(control:KeptStaticShelfNavigation|null)=>void}){
  const visit=useRef<Visit|null>(null), latest=useRef(active),mounted=useRef(false);
  const [shown,setShown]=useState<Presentation|null>(null);
  const nextFocus=useRef<Focus|null>(null), panelHeading=useRef<HTMLHeadingElement>(null);
  latest.current=active;
  const update=(original:Visit)=>{
    if(!mounted.current||visit.current!==original)return;
    setShown({catalog:publishedCatalog(original)?original.catalog:null,reader:original.reader?{...original.reader}:undefined,review:original.review,
      busy:original.busy,notice:original.notice,problem:original.problem,closing:!!original.closing||!!original.closeUnconfirmed});
  };
  const note=(original:Visit,text:string,problem=true)=>{original.notice=text;original.problem=problem;update(original);};
  const current=(original:Visit)=>{
    const live=bridge.getState();
    if(!mounted.current||!latest.current||visit.current!==original||original.retired||bridge.getDocumentEpoch()!==original.document||
      ownerRevisions(live,original.projectId)!==original.revisions)return false;
    const value=live.state.projectPreview;
    if(value===null||inactiveRenderer(value)===original.inactiveRenderer)return true;
    const reader=original.reader,frame=reader&&original.catalog&&previewFrame(value,original,original.catalog,reader.page);
    return !!(frame&&(!reader?.ack||frame.sessionId===reader.ack.sessionId));
  };
  const wire=(original:Visit)=>({projectId:original.projectId,visitId:original.visitId});
  const publishedCatalog=(original:Visit)=>{
    const value=row(bridge.getState().state.keptStaticPageShelf);
    return original.catalogPublished===true&&!!original.catalog&&sameShelf(value,original,original.catalog);
  };
  const clearCatalogTimer=(original:Visit)=>{
    if(original.catalogTimer!==undefined)window.clearTimeout(original.catalogTimer);
    original.catalogTimer=undefined;
  };
  const retire=(original:Visit)=>{
    clearCatalogTimer(original);clearOpenTimer(original.reader);clearReviewTimer(original.review);
    original.retired=true;
    original.catalog=null;original.reader=undefined;original.review=undefined;original.busy=undefined;
    if(visit.current!==original)return;
    visit.current=null;nextFocus.current=null;if(mounted.current)setShown(null);
  };
  const requestClose=(original:Visit)=>{
    if(bridge.getDocumentEpoch()!==original.document||!available(closeShelf))return Promise.resolve(false);
    return bridge.command(closeShelf,wire(original)).then(value=>{
      const r=row(value);
      return r.kind==='owner_saved_static_page_shelf_closed'&&sameVisit(r,original)&&r.closed===true;
    }).catch(()=>false);
  };
  const close=(original:Visit,restoreFocus:boolean):Promise<boolean>=>{
    if(!restoreFocus)original.focusOnClose=false;
    else if(original.focusOnClose===undefined)original.focusOnClose=true;
    if(original.closing)return original.closing;
    if(!current(original))return Promise.resolve(false);
    clearCatalogTimer(original);clearOpenTimer(original.reader);clearReviewTimer(original.review);original.review=undefined;original.closeUnconfirmed=undefined;
    original.closing=requestClose(original).then(confirmed=>{
      original.closing=undefined;
      if(!current(original))return false;
      if(!confirmed){
        original.closeUnconfirmed=true;note(original,'尚未确认关闭原列表。可以再次返回；不会重新保留或清除网页。');return false;
      }
      const {focusOnClose,trigger,document:epoch,revisions,projectId}=original;
      retire(original);
      if(focusOnClose)requestAnimationFrame(()=>{
        if(mounted.current&&!visit.current&&latest.current&&bridge.getDocumentEpoch()===epoch&&
          ownerRevisions(bridge.getState(),projectId)===revisions&&focusable(trigger))trigger.focus({preventScroll:true});
      });
      return true;
    });
    update(original);return original.closing;
  };
  const returnToRows=(original:Visit,reader:ReadVisit,message='')=>{
    clearOpenTimer(reader);original.reader=undefined;nextFocus.current=reader.focus;note(original,message);
  };
  const closeReader=(original:Visit,reader:ReadVisit)=>{
    if(reader.closing||!current(original))return;
    reader.returnRequested=true;reader.frame=undefined;
    if(!reader.ack){update(original);return;}
    clearOpenTimer(reader);
    const sessionId=reader.ack.sessionId;reader.closing=true;
    note(original,'');
    const stillHere=()=>current(original)&&!original.closing&&original.reader===reader;
    const unconfirmed=()=>{
      if(!stillHere())return;
      if((reader.published||reader.closeAck)&&bridge.getState().state.projectPreview===null)returnToRows(original,reader);
      else {
        reader.closing=undefined;note(original,'尚未确认关闭这版网页。可以再返回一次。');
      }
    };
    void bridge.command(closePreview,{sessionId}).then(value=>{
      if(!stillHere())return;
      if(row(value).closed!==true||row(value).sessionId!==sessionId)throw Error();
      reader.closeAck=true;
      if(bridge.getState().state.projectPreview===null)returnToRows(original,reader);
      else reader.timer=window.setTimeout(unconfirmed,publicationWaitMs);
    }).catch(unconfirmed);
  };
  const checkPreview=(original:Visit)=>{
    const reader=original.reader,catalog=original.catalog;
    if(original.closing||original.closeUnconfirmed||!reader?.ack||!catalog)return;
    const value=bridge.getState().state.projectPreview;
    if(reader.returnRequested){
      if(reader.closeAck&&value===null)returnToRows(original,reader);
      return;
    }
    const frame=previewFrame(value,original,catalog,reader.page),ack=reader.ack;
    if(frame?.sessionId===ack.sessionId&&frame.generation>=ack.generation){
      clearOpenTimer(reader);reader.published=true;
      const previous=reader.frame;
      if(!previous||frame.generation!==previous.generation||frame.ready!==previous.ready||frame.visible!==previous.visible||frame.error!==previous.error){
        reader.frame=frame;update(original);
      }
    }else if(reader.published&&value===null)returnToRows(original,reader,'这版网页的查看已结束。保留的版本未改变。');
  };
  const checkCatalog=(original:Visit)=>{
    const catalog=original.catalog;
    if(!catalog||original.closing||original.closeUnconfirmed)return;
    const value=row(bridge.getState().state.keptStaticPageShelf);
    if(sameShelf(value,original,catalog)){
      if(!original.catalogPublished){
        clearCatalogTimer(original);original.catalogPublished=true;original.busy=undefined;update(original);
      }
    }else if(original.catalogPublished)retire(original);
  };
  const inspect=(original:Visit)=>{
    if(!current(original)){
      // Same-token IME snapshots are not retirement. Actual owner/window loss hides all data.
      const closing=original.closing;retire(original);
      if(!closing&&!document.hidden)void requestClose(original);
      return;
    }
    checkCatalog(original);
    if(visit.current===original)checkPreview(original);
  };
  const read=(original:Visit)=>{
    if(!current(original)||original.busy||original.closing||original.closeUnconfirmed||original.reader||original.review)return;
    const catalog=original.catalog;
    if(catalog&&!publishedCatalog(original))return;
    original.busy='list';note(original,'',false);
    void bridge.command(listPages,{...wire(original),...(catalog?{shelfHandle:catalog.shelfHandle}:{})}).then(value=>{
      if(!current(original)||original.closing)return;
      const exact=shelfCatalog(value,original,catalog);
      if(!exact)throw Error();
      original.catalog=exact;checkCatalog(original);
      if(!current(original))return;
      if(publishedCatalog(original)){original.busy=undefined;update(original);return;}
      original.catalogTimer=window.setTimeout(()=>{
        if(!current(original)||original.closing)return;
        checkCatalog(original);
        if(!current(original)||publishedCatalog(original))return;
        original.busy=undefined;original.closeUnconfirmed=true;
        note(original,'保留列表未在这次项目窗口确认。请返回，没有重新读取。');
      },publicationWaitMs);
    }).catch(()=>{
      if(!current(original)||original.closing)return;
      original.busy=undefined;original.catalog=null;original.catalogPublished=undefined;
      note(original,'未核对到这次保留列表。请先返回，再重新打开列表。');
    });
  };
  const enter=(event:MouseEvent<HTMLButtonElement>)=>{
    const live=bridge.getState(),revisions=ownerRevisions(live,project.id),prior=inactiveRenderer(live.state.projectPreview),trigger=event.currentTarget;
    if(visit.current||!latest.current||revisions===null||prior===null||!focusable(trigger)||!id(project.id))return;
    const original:Visit={projectId:project.id,visitId:crypto.randomUUID(),document:bridge.getDocumentEpoch(),revisions,inactiveRenderer:prior,
      trigger,catalog:null,notice:'',problem:false};
    visit.current=original;read(original);
  };
  const open=(original:Visit,page:Page,trigger:HTMLButtonElement)=>{
    const catalog=original.catalog;
    if(!current(original)||!publishedCatalog(original)||!catalog?.versions.includes(page)||original.busy||original.closing||original.closeUnconfirmed||original.reader||original.review||!focusable(trigger))return;
    const reader:ReadVisit={page,focus:remember(trigger)};
    original.reader=reader;note(original,'');
    const failed=(error?:unknown)=>{
      if(!current(original)||original.closing||original.reader!==reader)return;
      if(error instanceof Error&&'code' in error&&(error.code==='not_sent'||error.code==='not_accepted')){
        returnToRows(original,reader,'这版网页暂时打不开。没有改用其他版本。');return;
      }
      // A missing ACK is not a known rejection. Retire this visit before another read.
      reader.returnRequested=true;reader.frame=undefined;original.closeUnconfirmed=true;
      note(original,'未确认打开这版网页。没有改用其他版本，请先关闭原列表。');
    };
    void bridge.command(openPage,{...wire(original),shelfHandle:catalog.shelfHandle,versionId:page.versionId,manifestIdentity:page.manifestIdentity}).then(value=>{
      if(!current(original)||original.closing||original.reader!==reader)return;
      const exact=previewFrame(value,original,catalog,page);
      if(!exact){failed();return;}
      reader.ack=exact;
      if(reader.returnRequested){closeReader(original,reader);return;}
      checkPreview(original);
      if(!reader.published)reader.timer=window.setTimeout(()=>{
        if(!current(original)||original.closing||original.reader!==reader)return;
        checkPreview(original);if(!reader.published)failed();
      },publicationWaitMs);
    }).catch(failed);
  };
  const replacePending=(original:Visit,catalog:Catalog,pendingState:Pending)=>{
    original.catalog={...catalog,pendingState};
  };
  const finishReview=(original:Visit,review:Review)=>{
    clearReviewTimer(review);original.review=undefined;nextFocus.current=review.focus;
  };
  const startReview=(original:Visit,trigger:HTMLButtonElement)=>{
    const catalog=original.catalog;
    if(!current(original)||!publishedCatalog(original)||catalog?.pendingState!=='reviewable'||original.busy||original.closing||original.closeUnconfirmed||original.reader||original.review||!focusable(trigger))return;
    const focus=remember(trigger),started=performance.now();
    original.busy='review';note(original,'',false);
    void bridge.command(reviewPending,{...wire(original),shelfHandle:catalog.shelfHandle}).then(value=>{
      if(!current(original)||original.closing)return;
      const r=row(value);
      if(r.kind!=='owner_saved_static_page_pending_review'||!sameShelf(r,original,catalog))throw Error();
      original.busy=undefined;
      if(r.reviewId===null&&r.expiresInMs===0){
        replacePending(original,catalog,'none');note(original,'这次没有找到未完成的暂存。',false);return;
      }
      if(!id(r.reviewId)||!integer(r.expiresInMs,1,120_000))throw Error();
      const deadline=started+r.expiresInMs,remaining=deadline-performance.now();
      if(remaining<=0)throw Error();
      const review:Review={id:r.reviewId,deadline,focus,timer:window.setTimeout(()=>{
        if(!current(original)||original.closing||original.review!==review)return;
        finishReview(original,review);replacePending(original,catalog,'unconfirmed');note(original,'这次核对已到期。请先核对列表，没有清除暂存。');
      },remaining)};
      original.review=review;update(original);
    }).catch(()=>{
      if(!current(original)||original.closing)return;
      original.busy=undefined;replacePending(original,catalog,'unconfirmed');note(original,'未确认这次暂存。请先核对列表，没有清除暂存。');
    });
  };
  const finish=(original:Visit,review:Review,confirmed:boolean)=>{
    const catalog=original.catalog;
    if(!current(original)||!publishedCatalog(original)||!catalog||original.review!==review||original.busy||original.closing)return;
    if(performance.now()>=review.deadline){
      finishReview(original,review);replacePending(original,catalog,'unconfirmed');note(original,'这次核对已到期。请先核对列表，没有清除暂存。');return;
    }
    finishReview(original,review);
    original.busy=confirmed?'abandon':'dismiss';note(original,'',false);
    void bridge.command(confirmed?abandonPending:dismissPending,{...wire(original),shelfHandle:catalog.shelfHandle,reviewId:review.id,
      ...(confirmed?{confirmed:true}:{})}).then(value=>{
      if(!current(original)||original.closing)return;
      const r=row(value);
      if(!sameShelf(r,original,catalog)||r.reviewId!==review.id)throw Error();
      if(confirmed){
        if(r.kind!=='owner_saved_static_page_pending_abandonment'||!['cleared','not_cleared','unknown'].some(status=>status===r.status))throw Error();
        replacePending(original,catalog,r.status==='cleared'?'none':'unconfirmed');
        original.busy=undefined;
        note(original,r.status==='cleared'?'已清除这次未完成的暂存。保留的网页没有改变。':r.status==='not_cleared'?
          '未清除原暂存。请先核对列表。':'清除结果未确认。请先核对列表，不要重复提交。',r.status!=='cleared');
      }else{
        if(r.kind!=='owner_saved_static_page_pending_dismissed'||r.dismissed!==true)throw Error();
        original.busy=undefined;note(original,'没有清除暂存。',false);
      }
    }).catch(()=>{
      if(!current(original)||original.closing)return;
      original.busy=undefined;replacePending(original,catalog,'unconfirmed');
      note(original,confirmed?'清除结果未确认。请先核对列表，不要重复提交。':'退出核对未确认，没有提交清除。请先核对列表。');
    });
  };
  const back=()=>{
    const original=visit.current;
    if(!original)return false;
    if(!original.closing){
      if(original.closeUnconfirmed)void close(original,true);
      else if(original.review)finish(original,original.review,false);
      else if(original.reader)closeReader(original,original.reader);
      else void close(original,true);
    }
    return true;
  };
  const leave=()=>visit.current?close(visit.current,false):undefined;
  const handlers=useRef({back,leave,inspect,retire,requestClose});handlers.current={back,leave,inspect,retire,requestClose};
  useLayoutEffect(()=>{
    mounted.current=true;
    registerNavigation({back:()=>handlers.current.back(),leave:()=>handlers.current.leave()});
    const check=()=>{if(visit.current)handlers.current.inspect(visit.current);};
    const hidden=()=>{if(visit.current)handlers.current.retire(visit.current);};
    const off=bridge.subscribe(check),offEpoch=bridge.subscribeDocumentEpoch(check);
    document.addEventListener('visibilitychange',check);window.addEventListener('pagehide',hidden);
    return ()=>{
      mounted.current=false;registerNavigation(null);off();offEpoch();
      document.removeEventListener('visibilitychange',check);window.removeEventListener('pagehide',hidden);
      const original=visit.current;
      if(original){
        handlers.current.retire(original);
        if(!original.closing&&!document.hidden)void handlers.current.requestClose(original);
      }
    };
  },[project.id,registerNavigation]);
  useLayoutEffect(()=>{
    const original=visit.current;
    if(original&&!active)handlers.current.inspect(original);
  },[active]);
  const focusKind=shown?.reader?.page.versionId??shown?.review?.id;
  useLayoutEffect(()=>{
    if(!focusKind)return;
    const scroll=panelHeading.current?.closest<HTMLElement>('.pw-settings-dialog-content');
    if(scroll)scroll.scrollTop=0;
    panelHeading.current?.focus({preventScroll:true});
  },[focusKind]);
  useLayoutEffect(()=>{
    if(!shown||shown.reader||shown.review||shown.busy||shown.closing||!nextFocus.current)return;
    const {node,scroll,top}=nextFocus.current;nextFocus.current=null;
    if(scroll?.isConnected)scroll.scrollTop=top;
    if(!node.disabled&&focusable(node))node.focus({preventScroll:true});
    else panelHeading.current?.focus({preventScroll:true});
  },[shown]);
  const original=visit.current;
  const showing=!!(shown&&original&&current(original)&&
    (!original.catalogPublished||original.closing||original.closeUnconfirmed||publishedCatalog(original)));
  const native=bridge.getState();
  const canEnter=active&&id(project.id)&&ownerRevisions(native,project.id)!==null&&inactiveRenderer(native.state.projectPreview)!==null;
  const frame=showing&&!shown?.closing&&!shown?.reader?.returnRequested?shown?.reader?.frame:undefined;
  const viewport=useProjectPreviewViewport(!!frame,frame?.sessionId??'',!frame||frame.error,available,original?.visitId??'', '.pw-settings-dialog-content');
  const reader=shown?.reader,review=shown?.review,catalog=shown?.catalog;
  return <>
    {(canEnter||showing)&&<Button variant="ghost" size="small" leadingIcon={IconFile} trailingIcon={IconChevronRight} disabled={showing} onClick={enter}>保留的网页</Button>}
    {showing&&original&&shown&&<SettingsDialog isOpen title="保留的网页" onClose={back} closeLabel={!shown.closing&&(reader||review)?'返回保留列表':'关闭保留列表'}>
      <div className="pw-form-flow" aria-busy={!!shown.busy||!!original.closing||!!reader&&!original.closeUnconfirmed&&(!frame?.ready||!!reader.closing)}>
        <div hidden={!!reader||!!review||shown.closing} inert={!!reader||!!review||shown.closing}>
          <div className="pw-form-flow">
            <h3 tabIndex={-1} ref={!reader&&!review?panelHeading:undefined}>{project.name}</h3>
            <p className="pw-reading-note">打开保留时的网页与资源，不重新构建，也不读取现在的文件。</p>
            {catalog&&<p className="pw-field-hint">每个项目最多保留 {catalog.maxVersions} 版，合计不超过 {catalog.maxStoredBytes/(1024*1024)} MiB。不会自动替换已有版本。</p>}
            {shown.busy==='list'&&<p role="status">正在核对保留的网页…</p>}
            {catalog?.versions.length===0&&<p>这个项目还没有确认保留的网页。</p>}
            {catalog&&catalog.versions.length>0&&<ul className="pw-form-flow">{catalog.versions.map((page,index)=>{
              const when=new Date(page.savedAt);
              return <li key={page.versionId}>
                <strong>index.html</strong>{' · '}<time dateTime={when.toISOString()}>{when.toLocaleString()}</time>
                <p className="pw-field-hint">本机保留 · {page.resourceCount} 个文件</p>
                <Button variant="secondary" size="small" aria-label={`打开第 ${index+1} 项保留的网页，${when.toLocaleString()}`}
                  disabled={!!shown.busy||shown.closing} onClick={event=>open(original,page,event.currentTarget)}>打开这版网页</Button>
              </li>;
            })}</ul>}
            {catalog&&<div className="pw-form-actions">
              <Button variant="ghost" size="small" disabled={!!shown.busy} onClick={()=>read(original)}>核对列表</Button>
              {catalog.pendingState==='reviewable'&&<Button variant="ghost" size="small" disabled={!!shown.busy}
                onClick={event=>startReview(original,event.currentTarget)}>核对未完成的保留</Button>}
            </div>}
            {catalog?.pendingState==='unconfirmed'&&<p className="pw-field-hint">暂存状态未核对。可以再核对列表。</p>}
          </div>
        </div>
        {reader&&!shown.closing&&<section className="pw-preview-pane pw-original-local-web" aria-label="这版保留网页的本机预览">
          <h3 ref={panelHeading} tabIndex={-1}>index.html · 保留于 {new Date(reader.page.savedAt).toLocaleString()}</h3>
          <div ref={viewport} className="pw-preview-viewport" aria-label="原生隔离预览画面" data-preview-session={frame?.sessionId}>
            {(!frame?.ready||!frame.visible||frame.error)&&<p className="pw-preview-placeholder" role="status">{reader.returnRequested?
              reader.closing||!reader.ack?'正在返回保留列表…':'本机画面已收起。':frame?.error?'本机网页画面暂不可读。':'正在打开这版网页…'}</p>}
          </div>
          <Button variant="secondary" disabled={!!reader.closing||!!reader.returnRequested&&!reader.ack} onClick={()=>{
            if(original.reader?.page===reader.page)closeReader(original,original.reader);
          }}>返回保留列表</Button>
        </section>}
        {review&&!shown.closing&&<section className="pw-form-flow" aria-label="核对未完成的保留">
          <h3 ref={panelHeading} tabIndex={-1}>清除这次暂存？</h3>
          <p>只清除这次未发布的暂存。保留的网页不会改变，也不会重新保留。</p>
          <div className="pw-form-actions">
            <Button variant="secondary" onClick={()=>finish(original,review,false)}>先不清除</Button>
            <Button onClick={()=>finish(original,review,true)}>清除这次暂存</Button>
          </div>
        </section>}
        {!!original.closing?<p role="status">正在关闭原保留列表…</p>:shown.notice&&
          <p className={shown.problem?'pw-error':'pw-reading-note'} role={shown.problem?'alert':'status'}>{shown.notice}</p>}
        {shown.busy==='abandon'&&<p role="status">正在核对这次清除…</p>}
      </div>
    </SettingsDialog>}
  </>;
}
