import {useCallback,useEffect,useLayoutEffect,useRef,useState} from 'react';
import {bridge} from './bridge';
import type {ReadingDocument} from './reading-context';
import type {RetainedDeliveryReading} from './ReadingView';
import type {TaskResult} from './conversation-work';

export const originalWorkDeliveryCommand='ownerWork.deliveryText.read';
export const retainedSpanStageCommand='ownerWork.deliverySpan.stage';
export const retainedSpanRemoveCommand='ownerWork.deliverySpan.remove';
const releasePageCommand='ownerWork.deliveryText.release';
export type OriginalWorkDeliverySource=Pick<TaskResult,'taskId'|'runId'|'epoch'|'deliveryId'|'summaryChars'>&{
  conversationId:string;messageId:string;sourceRevision:number;readerWindow?:number;
};
type Receipt={digest:string;retainedChars:number;excerptChars:number;limited:boolean};
type Page={body:string;next:number;more:boolean;token?:string};
export type RetainedSpanEditor={key:string;epoch:number;revision:number};
type Visit={source:OriginalWorkDeliverySource;trigger:HTMLButtonElement;window:string;restorePosition:()=>void;historyVisit?:string};
type Read={visit:Visit;offset:number;prior:number[];receipt?:Receipt;page?:Page;busy:boolean;error?:string;retiring?:'pending'|'retry'};
const unavailable='暂时无法核对这份交付的留存正文，可返回原消息查看摘录。';
const badRange='没有取得这份交付的原段落，没有显示其他版本。';
const unconfirmedExit='尚未核对到原正文已关闭；可再点「返回原消息」。';
let historyVisitOrder=0;

export function sameOriginalWorkDelivery(a:OriginalWorkDeliverySource,b:OriginalWorkDeliverySource){
  return a.conversationId===b.conversationId&&a.messageId===b.messageId&&a.sourceRevision===b.sourceRevision&&a.readerWindow===b.readerWindow&&
    a.taskId===b.taskId&&a.runId===b.runId&&a.epoch===b.epoch&&a.deliveryId===b.deliveryId&&a.summaryChars===b.summaryChars;
}
function deliveredPage(value:any,visit:Visit,offset:number,prior?:Receipt):{receipt:Receipt;page:Page}{
  const {source,historyVisit}=visit;
  if(value?.format!=='pocketworld.original-work-delivery-page.v1'||value.conversationId!==source.conversationId||
    value.messageId!==source.messageId||value.sourceRevision!==source.sourceRevision||value.taskId!==source.taskId||
    value.runId!==source.runId||value.epoch!==source.epoch||value.deliveryId!==source.deliveryId||
    source.readerWindow!==undefined&&(value.readerWindow!==source.readerWindow||value.historyVisit!==historyVisit||value.pageToken!==undefined)||
    value.readOnly!==true||value.executionAuthorized!==false||value.unit!=='UTF-16'||value.originalChars!==source.summaryChars||
    typeof value.resultDigest!=='string'||!/^[a-f0-9]{64}$/.test(value.resultDigest)||
    !Number.isSafeInteger(value.retainedChars)||value.retainedChars>240000||value.retainedChars>value.originalChars||
    !Number.isSafeInteger(value.excerptChars)||value.excerptChars<1||value.excerptChars>24000||value.excerptChars>=value.retainedChars||
    value.retentionLimited!==(value.retainedChars<value.originalChars)||
    typeof value.body!=='string'||value.body.length<1||value.body.length>6000||
    value.chars!==value.body.length||value.offset!==offset||value.nextOffset!==offset+value.body.length||
    value.nextOffset>value.retainedChars||value.hasMore!==(value.nextOffset<value.retainedChars)||
    /^[\uDC00-\uDFFF]/.test(value.body)||/[\uD800-\uDBFF]$/.test(value.body))throw new Error(badRange);
  const receipt={digest:value.resultDigest,retainedChars:value.retainedChars,excerptChars:value.excerptChars,limited:value.retentionLimited};
  if(prior&&(receipt.digest!==prior.digest||receipt.retainedChars!==prior.retainedChars||
    receipt.excerptChars!==prior.excerptChars||receipt.limited!==prior.limited))throw new Error(badRange);
  return {receipt,page:{body:value.body,next:value.nextOffset,more:value.hasMore,
    token:typeof value.pageToken==='string'&&/^[a-f0-9-]{36}$/.test(value.pageToken)?value.pageToken:undefined}};
}
function release(token?:string){if(token)void bridge.command(releasePageCommand,{pageToken:token}).catch(()=>{})}

/** The parent supplies the actual, currently rendered ordinary Chat or History scope. A
 * page never survives the original button/window, even when a native ACK is late. */
export function useOriginalWorkDeliveryReader(
  readWindow:(source:OriginalWorkDeliverySource,trigger:HTMLButtonElement)=>string|undefined,
  rememberChatPosition:()=>()=>void,
  spanEditor?:(source:OriginalWorkDeliverySource)=>RetainedSpanEditor|undefined,
  onStaged?:(editor:RetainedSpanEditor)=>void
){
  const windowNow=useRef(readWindow);windowNow.current=readWindow;
  const editorNow=useRef(spanEditor);editorNow.current=spanEditor;
  const original=useRef<Read|null>(null);
  const [reading,setReading]=useState<Read|null>(null);
  const focusRequest=useRef(0);
  const staging=useRef(false);
  const current=(visit:Visit)=>!document.hidden&&windowNow.current(visit.source,visit.trigger)===visit.window;
  const put=(value:Read|null)=>{original.current=value;setReading(value)};
  const close=useCallback((restore=true)=>{
    const target=original.current;
    if(!target)return false;
    const visit=target.visit;
    const active=()=>!document.hidden&&windowNow.current(visit.source,visit.trigger)===visit.window;
    const finish=()=>{
      const request=++focusRequest.current;
      original.current=null;setReading(null);
      release(target.page?.token);
      if(!restore||!active())return false;
      requestAnimationFrame(()=>{
        if(!original.current&&request===focusRequest.current&&active()&&!visit.trigger.closest('[hidden],[inert]')){
          visit.restorePosition();visit.trigger.focus({preventScroll:true});
        }
      });
      return true;
    };
    if(!restore||!visit.historyVisit||!active())return finish();
    if(target.retiring==='pending')return true;
    // Retire the local body immediately, but keep Back at this child until the
    // native retirement settles. A held parent History is not a close proof.
    const retiring:Read={...target,page:undefined,busy:true,retiring:'pending',error:'正在关闭原交付正文…'};
    original.current=retiring;setReading(retiring);
    const retry=()=>{
      if(original.current!==retiring||!active())return;
      const unknown:Read={...retiring,busy:false,retiring:'retry',error:unconfirmedExit};
      original.current=unknown;setReading(unknown);
    };
    const {readerWindow,conversationId,messageId,sourceRevision}=visit.source;
    void bridge.command(releasePageCommand,{historyVisit:visit.historyVisit,readerWindow,conversationId,messageId,sourceRevision})
      .then(value=>{
        if(original.current!==retiring||!active())return;
        if(value?.retired===true&&value.readerWindow===readerWindow&&value.historyVisit===visit.historyVisit)finish();
        else retry();
      },retry);
    return true;
  },[]);
  const shown=reading&&current(reading.visit)?reading:null;
  useLayoutEffect(()=>{if(reading&&!shown&&original.current===reading)close(false)},[reading,shown,close]);
  useEffect(()=>{
    const hidden=()=>{if(document.hidden)close(false)};
    document.addEventListener('visibilitychange',hidden);
    return()=>{document.removeEventListener('visibilitychange',hidden);release(original.current?.page?.token);original.current=null;focusRequest.current++};
  },[close]);

  const read=async(visit:Visit,offset:number,prior:number[],receipt?:Receipt)=>{
    if(!current(visit)||original.current?.busy||original.current?.retiring||staging.current)return;
    release(original.current?.page?.token);
    const pending={visit,offset,prior,receipt,busy:true};
    put(pending);
    const accepts=()=>original.current===pending&&current(visit);
    try{
      const {conversationId,messageId,sourceRevision,readerWindow}=visit.source;
      const data=await bridge.command(originalWorkDeliveryCommand,{conversationId,messageId,sourceRevision,offset,
        ...(readerWindow===undefined?{}:{readerWindow,historyVisit:visit.historyVisit}),
        ...(receipt?{expectedDigest:receipt.digest}:{})});
      const proved=deliveredPage(data,visit,offset,receipt);
      if(accepts())put({...pending,...proved,busy:false});else release(proved.page.token);
    }catch(failure){
      if(accepts())put({...pending,busy:false,error:failure instanceof Error&&failure.message===badRange?badRange:unavailable});
    }
  };
  const open=(source:OriginalWorkDeliverySource,trigger:HTMLButtonElement)=>{
    if(original.current)return;
    const window=windowNow.current(source,trigger);
    if(!window)return;
    focusRequest.current++;
    void read({source,trigger,window,restorePosition:rememberChatPosition(),
      historyVisit:source.readerWindow===undefined?undefined:`${++historyVisitOrder}:${crypto.randomUUID()}`},0,[]);
  };
  const documents:ReadingDocument[]=shown?[{key:shown.page?shown.offset+1:0,kind:'message',title:'原交付正文',
    plainText:true,text:shown.page?.body??''}]:[];
  const retained:RetainedDeliveryReading|undefined=shown?{
    verified:!!shown.page,pending:shown.busy,error:shown.error,
    segment:shown.page?shown.prior.length+1:undefined,retentionLimited:shown.page?shown.receipt?.limited:undefined,
    current:()=>original.current===shown&&current(shown.visit),
    previous:shown.page&&shown.prior.length>0?()=>void read(shown.visit,shown.prior[shown.prior.length-1],shown.prior.slice(0,-1),shown.receipt):undefined,
    next:shown.page?.more?()=>void read(shown.visit,shown.page!.next,[...shown.prior,shown.offset],shown.receipt):undefined,
    retry:shown.error&&!shown.retiring?()=>void read(shown.visit,shown.offset,shown.prior,shown.receipt):undefined,
    stage:shown.visit.source.readerWindow===undefined&&shown.page?.token&&editorNow.current?.(shown.visit.source)?async(offset,chars)=>{
      const editor=editorNow.current?.(shown.visit.source),token=shown.page?.token;
      if(!editor||!token||!current(shown.visit)||original.current!==shown||staging.current)
        throw Error('这份原交付或对应草稿已变化，可保留阅读或复制。');
      staging.current=true;
      try{
        const value=await bridge.command<Record<string,unknown>>(retainedSpanStageCommand,{
          ...editor,pageToken:token,offset:shown.offset+offset,chars});
        if(value?.persisted!==true||value.selected!==true||value.executionAuthorized!==false||
           value.key!==editor.key||value.epoch!==editor.epoch||value.fromRevision!==editor.revision||value.revision!==editor.revision+1)
          throw Error('尚未核对选段保存，请回原草稿核对，不要重复添加。');
        if(original.current===shown&&current(shown.visit)){close(false);onStaged?.(editor)}
      }finally{staging.current=false}
    }:undefined,
  }:undefined;
  return {open,close,documents,retained};
}
