import { useLayoutEffect, useMemo, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Accordion, AccordionItem, Button, CheckboxField } from './forms-library';
import { useProjectPreviewViewport } from './ProjectPreviewPane';
import { keepStaticPage, keptStaticPage, supportsKeptStaticPages } from './ProjectKeptStaticPages';

const openPreview='projects.development.files.staticPreview.open';
const closePreview='projects.preview.close';
const available=(command:string)=>bridge.getState().capabilities.commands.includes(command);
export const canOpenSavedStaticPreview=()=>[openPreview,closePreview,'projects.preview.viewport'].every(available);
const maximumResources=24, maximumAssetBytes=2*1024*1024, maximumPreviewBytes=4*1024*1024;
const publicationWaitMs=4_000; // The native host reaps a preview without a viewport after five seconds.
const keepUnconfirmed='本次保留未确认。可到同项目的「保留的网页」核对，没有重新保留。';
const hash=(value:unknown):value is string=>typeof value==='string'&&/^[a-f0-9]{64}$/.test(value);
const row=(value:unknown):Record<string,unknown>=>value&&typeof value==='object'&&!Array.isArray(value)?value as Record<string,unknown>:{};
const mimeTypes:Readonly<Record<string,string>>={css:'text/css',js:'text/javascript',mjs:'text/javascript',png:'image/png',
  jpg:'image/jpeg',jpeg:'image/jpeg',webp:'image/webp',gif:'image/gif',ico:'image/x-icon'};
const pathMime=(path:string):string|undefined=>{
  const extension=/\.([a-z]+)$/i.exec(path)?.[1].toLowerCase();
  return extension&&Object.hasOwn(mimeTypes,extension)?mimeTypes[extension]:undefined;
};
const excludedSegments=new Set(['node_modules','venv','__pycache__']);
const excludedRoots=new Set(['build','dist','out','target','coverage']);
function assetPath(value:unknown):value is string {
  if(typeof value!=='string'||!value||value.length>240||!pathMime(value)||/[\\:?#%\u0000-\u001f\u007f-\u009f]/.test(value))return false;
  const parts=value.split('/');
  return !excludedRoots.has(parts[0])&&!parts.at(-1)?.toLowerCase().startsWith('service-account')&&parts.every(part=>
    part.length>0&&part===part.trim()&&!part.startsWith('.')&&!excludedSegments.has(part));
}

// Read literal path references from the editor's already-loaded text. Never parse
// the HTML into a DOM, resolve a URL, load a resource or follow an indirect import.
function directAssets(html:string):string[] {
  const tags=/<!--[\s\S]*?(?:-->|$)|<\/?([a-z][a-z0-9:-]*)(?=[\s/>])((?:[^"'<>]|"[^"]*"|'[^']*')*)>/gi;
  const inactive=new Set(['script','style','title','textarea','template','noscript','iframe','xmp','noembed','noframes','plaintext']);
  const paths=new Set<string>();
  let raw='',depth=0;
  for(const token of html.matchAll(tags)){
    const tag=token[1]?.toLowerCase();
    if(!tag)continue;
    const end=token[0].startsWith('</');
    if(raw){
      if(tag===raw&&end&&--depth===0)raw='';
      else if(tag===raw&&!end&&raw==='template')depth++;
      continue;
    }
    if(end)continue;
    if(inactive.has(tag)){raw=tag;depth=1;}
    if(!['link','script','img'].includes(tag))continue;
    const attributes=new Map<string,string>();
    for(const attr of token[2].matchAll(/\s+([^\s/=<>'"`]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+)))?/g)){
      const name=attr[1].toLowerCase();
      if(!attributes.has(name))attributes.set(name,attr[2]??attr[3]??attr[4]??'');
    }
    const literal=attributes.get(tag==='link'?'href':'src');
    if(!literal||literal.includes('&')||literal.startsWith('//'))continue; // No entity expansion or external origin.
    const path=literal.startsWith('./')?literal.slice(2):literal.startsWith('/')?literal.slice(1):literal;
    if(!assetPath(path))continue;
    const mime=pathMime(path), rel=attributes.get('rel')?.toLowerCase().split(/\s+/)||[];
    if(tag==='script'&&mime==='text/javascript'||tag==='img'&&mime?.startsWith('image/')||
      tag==='link'&&(mime==='text/css'&&rel.includes('stylesheet')||mime?.startsWith('image/')&&rel.includes('icon'))){
      paths.add(path);
      if(paths.size===maximumResources)break;
    }
  }
  return Array.from(paths);
}

type SavedStaticCompanion={path:string;sha256:string;savedEditId:string;reviewedPaths:readonly string[]};
export type SavedStaticEditor={projectId:string;checkoutId:string;viewHandle:string;filesHandle:string;fileHandle:string;
  revision:number;editSequence:number;entrySha256:string;documentEpoch:number;html:string;listedPaths:readonly string[];companion?:SavedStaticCompanion};
type Snapshot={sessionId:string;manifestIdentity:string;resourceIdentity:string;ready:boolean;visible:boolean;generation:number;error:string|null;canKeep:boolean};
type Visit={paths:readonly string[];ack?:Snapshot;publicationTimer?:number;keep?:{sessionId:string;generation:number;manifest:string};published:boolean;verifiedRoot?:true;returning:boolean;closing:boolean;dismissed:boolean;closed:boolean};
function missingResourceReview(value:unknown,editor:SavedStaticEditor,paths:readonly string[]):string[]|null {
  const review=row(value), sent=review.resourcePaths, missing=review.missingPaths;
  if(review.kind!=='saved_static_resource_review'||review.opened!==false||Object.hasOwn(review,'sessionId')||
    review.projectId!==editor.projectId||review.checkoutId!==editor.checkoutId||review.entrySha256!==editor.entrySha256||
    review.viewHandle!==editor.viewHandle||review.filesHandle!==editor.filesHandle||review.fileHandle!==editor.fileHandle||
    review.revision!==editor.revision||review.editSequence!==editor.editSequence||
    !Array.isArray(sent)||sent.length!==paths.length||!paths.every((path,i)=>sent[i]===path)||
    !Array.isArray(missing)||!missing.length||missing.length>paths.length||new Set(missing).size!==missing.length||
    !missing.every(path=>assetPath(path)&&paths.includes(path))) return null;
  return missing;
}
function clearPublicationTimer(original:Visit) {
  if(original.publicationTimer!==undefined)window.clearTimeout(original.publicationTimer);
  original.publicationTimer=undefined;
}
function nativeSnapshot(value:unknown,editor:SavedStaticEditor,paths:readonly string[]):Snapshot|null {
  const view=row(value), resources=view.resources, companion=editor.companion;
  if((companion&&!paths.includes(companion.path))||view.kind!=='saved_static_page'||view.projectId!==editor.projectId||view.checkoutId!==editor.checkoutId||
    view.entryPath!=='index.html'||view.entrySha256!==editor.entrySha256||!hash(view.manifestIdentity)||
    typeof view.sessionId!=='string'||!view.sessionId||view.sessionId.length>200||
    typeof view.ready!=='boolean'||typeof view.visible!=='boolean'||!(view.error===null||typeof view.error==='string')||
    !Number.isSafeInteger(view.documentGeneration)||Number(view.documentGeneration)<0||
    (view.ready&&Number(view.documentGeneration)<1)||!Array.isArray(resources)||resources.length!==paths.length+1)return null;
  const remaining=new Set(paths);
  const identities:string[]=[];
  let bytes=0,entry=false;
  for(const value of resources){
    const resource=row(value),path=resource.path;
    if(!hash(resource.sha256)||!Number.isSafeInteger(resource.bytes)||Number(resource.bytes)<0)return null;
    if(path==='index.html'){
      if(entry||resource.sha256!==editor.entrySha256||resource.mimeType!=='text/html'||Number(resource.bytes)>256*1024)return null;
      entry=true;
    }else if(!assetPath(path)||!remaining.delete(path)||resource.mimeType!==pathMime(path)||Number(resource.bytes)>maximumAssetBytes||
      (companion&&path===companion.path&&resource.sha256!==companion.sha256))return null;
    bytes+=Number(resource.bytes);
    identities.push(JSON.stringify([path,resource.sha256,resource.bytes,resource.mimeType]));
  }
  return entry&&remaining.size===0&&bytes<=maximumPreviewBytes?{sessionId:view.sessionId,manifestIdentity:view.manifestIdentity,
    resourceIdentity:JSON.stringify(identities.sort()),ready:view.ready,visible:view.visible,generation:Number(view.documentGeneration),error:view.error,canKeep:view.canKeep===true}:null;
}

/** Same editor and BoardUI surface. The viewport is only a slot for the native session. */
export function ProjectSavedStaticPreview({editor,current,onLeave,onVerifiedRoot,registerBack}:{editor:SavedStaticEditor;current:()=>boolean;
  onLeave:()=>void;onVerifiedRoot?:(paths:readonly string[])=>void;registerBack:(handler:(()=>void)|null)=>void}) {
  const companion=editor.companion;
  const suggestions=useMemo(()=>editor.companion?.reviewedPaths??directAssets(editor.html),[editor]);
  const other=useMemo(()=>editor.companion?[]:Array.from(new Set(editor.listedPaths.filter(path=>assetPath(path)&&!suggestions.includes(path)))).slice(0,40),[editor,suggestions]);
  const [selected,setSelected]=useState<string[]>(()=>[...suggestions]);
  const [missing,setMissing]=useState<string[]>([]);
  const hasSelectedMissing=selected.some(path=>missing.includes(path));
  const hasRequiredAsset=!companion||selected.includes(companion.path);
  const [stage,setStage]=useState<'review'|'opening'|'preview'|'closing'|'error'>('review');
  const [shown,setShown]=useState<Snapshot|null>(null), [error,setError]=useState('');
  const [keepStatus,setKeepStatus]=useState('');
  const visit=useRef<Visit|null>(null), mounted=useRef(false), heading=useRef<HTMLHeadingElement>(null);
  const callbacks=useRef({current,onLeave,onVerifiedRoot});callbacks.current={current,onLeave,onVerifiedRoot};
  const viewport=useProjectPreviewViewport(stage==='preview',shown?.sessionId||'',!shown||!!shown.error,available,
    `${editor.documentEpoch}:${editor.filesHandle}:${editor.fileHandle}:${editor.revision}`,'.pw-settings-dialog-content');
  const owns=()=>mounted.current&&callbacks.current.current()&&bridge.getDocumentEpoch()===editor.documentEpoch;
  const live=(original:Visit)=>owns()&&visit.current===original&&!original.dismissed;
  const release=(original:Visit)=>{
    if(!original.ack||original.closing||original.closed||bridge.getDocumentEpoch()!==editor.documentEpoch||!available(closePreview))return;
    original.closed=true; // Best effort on one exact unpublished/dismissed session, never a substitute.
    void bridge.command(closePreview,{sessionId:original.ack.sessionId}).catch(()=>{});
  };
  const leave=(original?:Visit)=>{
    if(!mounted.current||(original&&visit.current!==original))return;
    if(original){clearPublicationTimer(original);original.dismissed=true;}
    visit.current=null;
    callbacks.current.onLeave();
  };
  const sync=()=>{
    const original=visit.current;
    if(!owns()){if(original)release(original);leave(original??undefined);return;}
    if(!original?.ack||original.dismissed)return;
    const state=nativeSnapshot(bridge.getState().state.projectPreview,editor,original.paths);
    if(state?.sessionId===original.ack.sessionId&&state.manifestIdentity===original.ack.manifestIdentity&&
      state.resourceIdentity===original.ack.resourceIdentity&&state.generation>=original.ack.generation){
      clearPublicationTimer(original);
      original.published=true;
      if(!original.returning){
        setShown(state);setStage('preview');
        if(!editor.companion&&!original.verifiedRoot&&state.ready&&state.visible&&!state.error){
          original.verifiedRoot=true;callbacks.current.onVerifiedRoot?.(original.paths);
        }
      }
    }else if(original.published){original.closed=true;leave(original);}
  };
  const keep=()=>{
    const original=visit.current;
    if(!original?.ack||!original.published||!live(original)||document.hidden||original.returning||original.keep||
      !supportsKeptStaticPages(available))return;
    const view=nativeSnapshot(bridge.getState().state.projectPreview,editor,original.paths);
    if(!view?.canKeep||!view.ready||!view.visible||view.error||view.sessionId!==original.ack.sessionId||
      view.resourceIdentity!==original.ack.resourceIdentity||view.manifestIdentity!==original.ack.manifestIdentity||
      view.generation!==shown?.generation||view.sessionId!==shown?.sessionId)return;
    const keep={sessionId:view.sessionId,generation:view.generation,manifest:view.manifestIdentity};
    original.keep=keep;setKeepStatus('正在核对本次保留…');
    const stillHere=()=>{
      if(!live(original)||document.hidden||original.returning||original.keep!==keep)return false;
      const state=nativeSnapshot(bridge.getState().state.projectPreview,editor,original.paths);
      return state?.sessionId===keep.sessionId&&state.generation===keep.generation&&state.manifestIdentity===keep.manifest;
    };
    const stale=()=>{
      if(live(original)&&!document.hidden&&!original.returning&&original.keep===keep)setKeepStatus(keepUnconfirmed);
    };
    void bridge.command(keepStaticPage,{sessionId:keep.sessionId,documentGeneration:keep.generation,manifestIdentity:keep.manifest}).then(value=>{
      if(!stillHere()){stale();return;}
      const receipt=row(value),page=keptStaticPage(receipt.page,editor.projectId);
      if(receipt.kind!=='owner_saved_static_page_keep'){setKeepStatus(keepUnconfirmed);return;}
      if((receipt.status==='saved'||receipt.status==='already_kept')&&page?.manifestIdentity===keep.manifest&&
        page.checkoutId===editor.checkoutId&&page.entrySha256===editor.entrySha256&&page.resourceCount===original.paths.length+1){
        setKeepStatus(receipt.status==='saved'?'此版本已保留。可在项目的「工作 → 保留的网页」找回。':
          '此版本已在项目的「保留的网页」中，没有另存一份。');
      }else if(receipt.status==='quota'&&receipt.page===null)setKeepStatus(
        '保留这版会超出项目上限，本次未保留。已有版本仍在项目的「工作 → 保留的网页」，可以继续编辑或预览。');
      else setKeepStatus(keepUnconfirmed);
    }).catch((failure:unknown)=>{
      if(!stillHere()){stale();return;}
      setKeepStatus(failure instanceof Error&&'code' in failure&&(failure.code==='not_sent'||failure.code==='not_accepted')?
        '本次预览未能受理保留。没有再次提交。':keepUnconfirmed);
    });
  };
  const dismiss=()=>{
    const original=visit.current;
    if(!original?.ack||!live(original)){
      if(original)release(original);
      leave(original??undefined);return;
    }
    if(original.closing)return;
    clearPublicationTimer(original);
    const {sessionId}=original.ack;
    original.returning=true;original.closing=true;setShown(null);setStage('closing');setError('');
    void bridge.command(closePreview,{sessionId}).then(value=>{
      if(row(value).closed!==true||row(value).sessionId!==sessionId)throw Error('未确认关闭原预览。');
      original.closed=true;leave(original);
    }).catch(()=>{
      if(!live(original))return;
      const state=nativeSnapshot(bridge.getState().state.projectPreview,editor,original.paths);
      if(original.published&&state?.sessionId!==sessionId){original.closed=true;leave(original);return;}
      original.closing=false;setStage('error');setError('尚未确认关闭本次预览。可以再次返回；编辑内容仍保留。');
    });
  };
  const closeNow=useRef(dismiss);closeNow.current=dismiss;
  useLayoutEffect(()=>{
    if(stage==='review'&&missing.length)heading.current?.focus({preventScroll:true});
  },[stage,missing]);
  useLayoutEffect(()=>{
    mounted.current=true;registerBack(()=>closeNow.current());
    const drawer=heading.current?.closest<HTMLElement>('.pw-settings-dialog-content');
    if(drawer)drawer.scrollTop=0;
    heading.current?.focus({preventScroll:true});
    const events=bridge.subscribe(sync), epochs=bridge.subscribeDocumentEpoch(sync);
    document.addEventListener('visibilitychange',sync);
    sync();
    return ()=>{
      mounted.current=false;registerBack(null);events();epochs();document.removeEventListener('visibilitychange',sync);
      const original=visit.current;
      if(original){clearPublicationTimer(original);original.dismissed=true;release(original);}
      visit.current=null;
    };
  },[editor]);
  const show=()=>{
    if(!owns()||stage!=='review'||visit.current||!canOpenSavedStaticPreview()||hasSelectedMissing||!hasRequiredAsset||selected.length>maximumResources)return;
    const original:Visit={paths:[...selected],published:false,returning:false,closing:false,dismissed:false,closed:false};
    visit.current=original;setStage('opening');setError('');setKeepStatus('');
    const {viewHandle,filesHandle,fileHandle,revision,editSequence}=editor;
    void bridge.command(openPreview,{viewHandle,filesHandle,fileHandle,revision,editSequence,resourcePaths:original.paths,
      ...(companion?{companion:{entrySha256:editor.entrySha256,savedEditId:companion.savedEditId}}:{})}).then(value=>{
      const unavailable=missingResourceReview(value,editor,original.paths);
      if(unavailable){
        if(!live(original))return;
        visit.current=null;setMissing(unavailable);setStage('review');return;
      }
      const ack=nativeSnapshot(value,editor,original.paths);
      if(!ack)throw Error('未取得所选文件的预览回执，没有打开其他副本。');
      original.ack=ack;
      if(!live(original)){release(original);return;}
      sync();
      if(live(original)&&!original.published)original.publicationTimer=window.setTimeout(()=>{
        original.publicationTimer=undefined;
        if(!live(original)||original.returning||original.ack!==ack)return;
        sync(); // Honor an exact matching frame already received; the ACK alone cannot mount a viewport.
        if(!live(original)||original.published)return;
        original.dismissed=true;visit.current=null;release(original);
        setStage('error');setError('未能确认本次预览画面。没有重新打开，可以返回编辑。');
      },publicationWaitMs);
    }).catch(failure=>{
      if(!live(original))return;
      const code=failure instanceof Error&&'code' in failure?failure.code:null;
      visit.current=null;setStage(code==='not_sent'||code==='not_accepted'?'review':'error');
      setError(code==='outcome_unknown'?
        '尚未确认本次预览，没有重新打开。请返回编辑。':failure instanceof Error?failure.message:'未能打开这份本机预览。');
    });
  };
  const options=(paths:readonly string[])=>paths.map(path=><CheckboxField key={path} isSelected={selected.includes(path)}
    isDisabled={path===companion?.path||!selected.includes(path)&&selected.length>=maximumResources}
    onChange={checked=>{if(path!==companion?.path)setSelected(previous=>checked?(previous.length<maximumResources&&!previous.includes(path)?[...previous,path]:previous):previous.filter(p=>p!==path))}}>
    <bdi className="pw-preview-entry-path">{path}</bdi>
    {path===companion?.path&&<span className="pw-field-hint"> · 本次保存</span>}
    {missing.includes(path)&&<span className="pw-field-hint"> · 本次未找到</span>}
  </CheckboxField>);
  return stage==='review'?<section className="pw-form-flow" aria-label="核对本机网页预览">
    <h3 ref={heading} tabIndex={-1}>index.html</h3>
    <p>{companion?<>核对刚才打开的同一网页，并带上这次保存的 <bdi>{companion.path}</bdi>。</>:'用当前已保存的入口和你选中的资源。'}预览只在本机打开，不联网、不构建，也不改动文件。</p>
    {suggestions.length>0&&<div className="pw-form-flow" role="group" aria-label={companion?'上次已核对的网页资源':'入口直接引用的文件'}>{options(suggestions)}</div>}
    {other.length>0&&<Accordion defaultExpandedKeys={other.some(path=>missing.includes(path))?['static-listed-resources']:undefined}>
      <AccordionItem id="static-listed-resources" title="从已读取的文件清单再选" headingLevel={4}>
      <div className="pw-form-flow" role="group" aria-label="清单中的其他可选资源">{options(other)}</div>
    </AccordionItem></Accordion>}
    {(suggestions.length>0||other.length>0)&&<p className="pw-field-hint" role="status">已选 {selected.length} 项，最多 {maximumResources} 项。</p>}
    {hasSelectedMissing&&<p className="pw-field-hint" role="status">{companion&&missing.includes(companion.path)?
      '未找到本次保存的资源，请返回编辑器核对。':'请取消勾选未找到的资源，或者返回补齐文件后再预览。'}</p>}
    {error&&<p className="pw-error" role="alert">{error}</p>}
    <div className="pw-form-actions"><Button variant="secondary" onClick={dismiss}>返回编辑</Button>
      <Button disabled={hasSelectedMissing||!hasRequiredAsset} onClick={show}>打开本机预览</Button></div>
  </section>:<section className="pw-preview-pane pw-original-local-web" aria-label="已保存网页的本机预览" aria-busy={stage==='opening'||stage==='closing'||stage==='preview'&&!shown?.ready&&!shown?.error}>
    <p className="pw-reading-note">index.html · 此次已保存版本</p>
    {stage==='preview'&&shown?.canKeep&&shown.ready&&shown.visible&&!shown.error&&!visit.current?.keep&&supportsKeptStaticPages(available)&&
      <div className="pw-form-actions"><Button variant="ghost" size="small" onClick={keep}>保留此网页</Button></div>}
    {stage==='preview'&&keepStatus&&<p className="pw-reading-note" role="status">{keepStatus}</p>}
    {stage!=='error'&&<div ref={viewport} className="pw-preview-viewport" aria-label="原生隔离预览画面" data-preview-session={shown?.sessionId}>
      {(!shown?.ready||!shown.visible)&&<p className="pw-preview-placeholder" role="status">{shown?.error?'本机网页画面暂不可读。':stage==='closing'?'正在返回编辑…':'正在打开本机网页…'}</p>}
    </div>}
    {(error||shown?.error)&&<p className="pw-error" role="alert">{error||shown?.error}</p>}
  </section>;
}
