import { useLayoutEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Accordion, AccordionItem, Button, SelectField } from './forms-library';

const prefix='projects.keptStaticPages.portable.';
const prepare=prefix+'prepare',pick=prefix+'pick',cancel=prefix+'cancel',write=prefix+'write',clear=prefix+'clear',leave=prefix+'leave';
export const supportsKeptPortableCopy=()=>[prepare,pick,cancel,write,clear,leave].every(command=>
  bridge.getState().capabilities.commands.includes(command));
const row=(value:unknown):Record<string,unknown>=>value&&typeof value==='object'&&!Array.isArray(value)?value as Record<string,unknown>:{};
const uuid=(value:unknown):value is string=>typeof value==='string'&&/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(value);
const hash=(value:unknown):value is string=>typeof value==='string'&&/^[a-f0-9]{64}$/.test(value);
type Page={projectId:string;checkoutId:string;versionId:string;manifestIdentity:string;entrySha256:string;savedAt:number;resourceCount:number;bytes:number};
type Shelf={projectId:string;visitId:string;shelfHandle:string};
type Proof={page:Page;requestId:string;name:string;bytes:number;sha256:string};
const phases=['choosing','chooser_unconfirmed','checking_target','target_selected','writing','verified','unknown','not_written','cancelled'] as const;
type Phase=typeof phases[number];
export type Delivery=Proof&{phase:Phase;destinationName:string|null};

function exactPage(value:unknown,projectId:string):Page|null{
  const p=row(value);
  if(p.kind!=='owner_saved_static_page'||p.projectId!==projectId||p.entryPath!=='index.html'||
    !uuid(p.checkoutId)||!uuid(p.versionId)||!hash(p.manifestIdentity)||!hash(p.entrySha256)||
    typeof p.savedAt!=='number'||!Number.isSafeInteger(p.savedAt)||p.savedAt<1||p.savedAt>8.64e15||
    p.resourceCount!==3||typeof p.bytes!=='number'||!Number.isSafeInteger(p.bytes)||p.bytes<1||p.bytes>4*1024*1024)return null;
  return {projectId,checkoutId:p.checkoutId,versionId:p.versionId,manifestIdentity:p.manifestIdentity,
    entrySha256:p.entrySha256,savedAt:p.savedAt,resourceCount:p.resourceCount,bytes:p.bytes};
}
const samePage=(a:Page,b:Page)=>a.projectId===b.projectId&&a.checkoutId===b.checkoutId&&a.versionId===b.versionId&&
  a.manifestIdentity===b.manifestIdentity&&a.entrySha256===b.entrySha256&&a.savedAt===b.savedAt&&
  a.resourceCount===b.resourceCount&&a.bytes===b.bytes;
function proof(value:unknown,projectId:string):Proof|null{
  const v=row(value),page=exactPage(v.page,projectId);
  if(!page||!uuid(v.requestId)||!hash(v.sha256)||
    typeof v.bytes!=='number'||!Number.isSafeInteger(v.bytes)||v.bytes<1||v.bytes>16*1024)return null;
  const name='offline-page-'+page.versionId.slice(0,8)+'.html';
  return v.name===name?{page,requestId:v.requestId,name,sha256:v.sha256,bytes:v.bytes}:null;
}
export function exactKeptPortableDelivery(value:unknown,projectId:string):Delivery|null{
  const v=row(value),exact=proof(value,projectId),phase=phases.find(candidate=>candidate===v.phase);
  const name=typeof v.destinationName==='string'&&v.destinationName.length<=240&&/\.html$/i.test(v.destinationName)&&
    !/[\u0000-\u001f\u007f-\u009f]/.test(v.destinationName)?v.destinationName:null;
  if(!exact||v.kind!=='owner_kept_portable_delivery'||!phase||(!name&&
    (phase==='target_selected'||phase==='writing'||phase==='verified')))return null;
  return {...exact,phase,destinationName:name};
}
const exactShelf=(value:unknown,shelf:Shelf)=>{
  const r=row(value);
  return r.projectId===shelf.projectId&&r.visitId===shelf.visitId&&r.shelfHandle===shelf.shelfHandle;
};
const keys=(value:Proof)=>({requestId:value.requestId,versionId:value.page.versionId,manifestIdentity:value.page.manifestIdentity,sha256:value.sha256});
export async function leaveKeptPortableCopy(value:Delivery){
  try{
    const r=row(await bridge.command(leave,{projectId:value.page.projectId,...keys(value)}));
    return r.kind==='owner_kept_portable_left'&&r.projectId===value.page.projectId&&r.requestId===value.requestId;
  }catch{return false;}
}
const summary=(p:Proof)=><p className="pw-field-hint"><bdi>{p.name}</bdi> · {p.bytes.toLocaleString()} 字节 · SHA-256 <code className="[overflow-wrap:anywhere]">{p.sha256}</code></p>;

export function ProjectKeptPortableCopy({shelf,pages,delivery,active,current}:{shelf:Shelf;pages:Page[];
  delivery:Delivery|null;active:boolean;current:()=>boolean}){
  const [selected,setSelected]=useState(''),[offered,setOffered]=useState<Proof|null>(null),[pending,setPending]=useState<string|null>(null),
    [note,setNote]=useState('');
  const work=useRef<object|null>(null),latest=useRef({active,current});
  latest.current={active,current};
  useLayoutEffect(()=>()=>{work.current=null;},[]);
  useLayoutEffect(()=>{if(!active)setOffered(null);},[active]);
  const now=()=>latest.current.active&&latest.current.current()&&supportsKeptPortableCopy()&&
    exactShelf(bridge.getState().state.keptStaticPageShelf,shelf);
  const candidates=pages.filter(page=>page.resourceCount===3);
  const fromList=delivery?pages.find(page=>samePage(page,delivery.page)):null;
  const selectedPage=candidates.find(page=>page.versionId===selected);
  const sameDelivery=(value:Delivery)=>{
    const live=exactKeptPortableDelivery(bridge.getState().state.keptStaticPagePortable,shelf.projectId);
    return live?.requestId===value.requestId&&live.sha256===value.sha256&&samePage(live.page,value.page)&&
      live.phase===value.phase&&live.destinationName===value.destinationName;
  };
  const invoke=(action:string,args:Record<string,unknown>,accept:(value:unknown)=>void,failed:string)=>{
    if(!now()||work.current)return;
    const ticket={},epoch=bridge.getDocumentEpoch();work.current=ticket;setPending(action);setNote('');
    const stillHere=()=>work.current===ticket&&epoch===bridge.getDocumentEpoch()&&now();
    void bridge.command(action,{...shelf,...args}).then(value=>{
      if(stillHere()){
        if(!exactShelf(value,shelf))throw Error();
        accept(value);
      }
    }).catch(()=>{if(stillHere())setNote(failed);}).finally(()=>{
      if(work.current===ticket){work.current=null;setPending(null);}
    });
  };
  const checkPage=()=>{
    const page=selectedPage;
    if(!page||delivery)return;
    invoke(prepare,{versionId:page.versionId,manifestIdentity:page.manifestIdentity},value=>{
      const result=proof(value,shelf.projectId);
      if(row(value).kind!=='owner_kept_portable_review'||!result||!samePage(page,result.page))throw Error();
      setOffered(result);
    },'这版未能核对为保留了原样式和计数器的内置起步网页。没有创建或写入副本。');
  };
  const take=(value:Proof,allow:boolean)=>{
    setOffered(null);
    invoke(allow?pick:cancel,keys(value),response=>{
      if(allow){
        const ack=exactKeptPortableDelivery(response,shelf.projectId);
        if(!ack||ack.requestId!==value.requestId||ack.sha256!==value.sha256||!samePage(ack.page,value.page))throw Error();
        setNote(ack.phase==='not_written'?'没有打开系统保存位置。':
          '若系统选择器已打开，回来后请重新打开「保留的网页」核对位置。没有写入内容。');
      }else if(row(response).kind!=='owner_kept_portable_cancelled'||row(response).requestId!==value.requestId)throw Error();
    },allow?'系统选择状态未确认。未提交写入，请回来核对原操作。':'已退出当前核对，没有提交写入。');
  };
  const continueDelivery=(value:Delivery,allow:boolean)=>{
    if(!sameDelivery(value)||allow&&(!fromList||value.phase!=='target_selected'))return;
    invoke(allow?write:clear,keys(value),response=>{
      if(!allow){
        if(row(response).kind!=='owner_kept_portable_cancelled'||row(response).requestId!==value.requestId)throw Error();
        return;
      }
      const actual=exactKeptPortableDelivery(response,shelf.projectId);
      if(!actual||actual.requestId!==value.requestId||actual.sha256!==value.sha256||
        !samePage(actual.page,value.page)||actual.destinationName!==value.destinationName)throw Error();
    },allow?'本次写入回执未确认。请核对所选位置，不会重新写入。':'未确认退出原操作，没有重新提交写入。');
  };
  if(!delivery&&!candidates.length)return null;
  if(delivery){
    const {phase}=delivery;
    const canWrite=phase==='target_selected'&&!!fromList;
    const message=phase==='verified'?'已写入并核对所选文件。可用支持打开本地 HTML 的浏览器离线查看。':
      phase==='unknown'?'写入结果未确认。请在选择的位置核对，不会重新写入。':
      phase==='not_written'?'没有确认到可写的原空白 HTML 文件，未写入内容。':
      phase==='cancelled'?'已取消选择，没有写入内容。':
      phase==='target_selected'?(canWrite?'位置已选择，尚未写入内容。只在你确认后写入这一个文件。':'原保留版本已不在这次列表中，不会写入。'):
      phase==='writing'?'正在写入所选文件并回读核对…':phase==='checking_target'?'正在核对系统返回的原位置…':
      phase==='chooser_unconfirmed'?'等待核对原系统选择，没有写入内容。':'正在等待选择保存位置…';
    return <section className="pw-form-flow" aria-label="原离线网页副本">
      <h4>单文件离线副本 · {new Date(delivery.page.savedAt).toLocaleString()}</h4>
      {summary(delivery)}
      {delivery.destinationName&&<p>系统文件：<bdi className="[overflow-wrap:anywhere]">{delivery.destinationName}</bdi></p>}
      <p role="status">{message}</p>
      {note&&<p className="pw-field-hint" role="status">{note}</p>}
      {phase!=='writing'&&<div className="pw-form-actions">
        <Button size="small" variant="secondary" disabled={!active||!!pending} onClick={()=>continueDelivery(delivery,false)}>{canWrite?'取消写入':'关闭此次记录'}</Button>
        {canWrite&&<Button size="small" disabled={!active||!!pending} onClick={()=>continueDelivery(delivery,true)}>写入这个文件</Button>}
      </div>}
    </section>;
  }
  return <Accordion variant="divided" className="pw-compact-disclosures">
    <AccordionItem id="kept-portable-html" title="保存单文件离线副本">
      <div className="pw-form-flow">
        <p className="pw-field-hint">只支持保留原样式和计数器的内置离线起步页。副本不改项目文件和这版保留网页。</p>
        {offered?<><p>这次将保存 {new Date(offered.page.savedAt).toLocaleString()} 的网页内容、内嵌样式和计数器，作为新的 HTML 文件。</p>
          {summary(offered)}
          <p className="pw-field-hint">选择系统位置不会写入内容。回来后，请重新打开「保留的网页」，确认文件名再写入。</p>
          <div className="pw-form-actions">
            <Button size="small" variant="secondary" disabled={!active||!!pending} onClick={()=>take(offered,false)}>取消</Button>
            <Button size="small" disabled={!active||!!pending} onClick={()=>take(offered,true)}>选择保存位置</Button>
          </div>
        </>:<>
          <SelectField label="选择保留版本" value={selectedPage?.versionId??''} options={candidates.map(page=>({
            id:page.versionId,label:new Date(page.savedAt).toLocaleString()+' · '+page.versionId.slice(0,8),
          }))} isDisabled={!active||!!pending} onChange={value=>{setSelected(value);setNote('');}} compactValue/>
          <div className="pw-form-actions"><Button size="small" variant="secondary" disabled={!active||!!pending||!selectedPage}
            onClick={checkPage}>核对这个版本</Button></div>
        </>}
        {pending===prepare&&<p role="status">正在核对这版的入口、样式和计数器…</p>}
        {note&&<p className="pw-field-hint" role="status">{note}</p>}
      </div>
    </AccordionItem>
  </Accordion>;
}
