import {useLayoutEffect,useRef,useState,type RefObject} from 'react';
import {bridge} from './bridge';
import {IconQuote} from './icons';
import {confirmedQuoteStage,selectedQuoteRange} from './message-quote';
import type {ReadingQuoteSource} from './reading-context';

type Props={
  source:ReadingQuoteSource;text:string;field:RefObject<HTMLTextAreaElement|null>;
  start:number;end:number;visible:boolean;report:(text:string)=>void;
};
const displayed=(value:string)=>value.replace(/\r\n?/g,'\n');

/** A reader selection is only a proposed range. The native original message
 * and draft window own validation and staging; never paste the preview text. */
export function ReadingQuoteAction({source,text,field,start,end,visible,report}:Props){
  const alive=useRef(false),enabled=useRef(visible),entered=useRef(false);
  enabled.current=visible;
  const [busy,setBusy]=useState(false);
  const [last,setLast]=useState<{start:number;end:number;confirmed:boolean}|null>(null);
  useLayoutEffect(()=>{alive.current=true;return()=>{alive.current=false}},[]);
  const sameLast=!!last&&last.start===start&&last.end===end;
  const quote=async()=>{
    const node=field.current;
    if(!visible||!alive.current||entered.current||!node||document.hidden)return;
    // Read the selection on the gesture, including its actual occurrence when
    // identical passages repeat. A rendered Markdown selection is never used.
    const a=node.selectionStart,b=node.selectionEnd,selected=node.value.slice(a,b);
    if(!selected||last&&last.start===a&&last.end===b)return;
    const range=selectedQuoteRange(text,node);
    if(!range||displayed(text.slice(range.offset,range.offset+range.chars))!==selected){
      report('这段未形成可核对的原文范围。请选完整字符，最多 4000 个原文字符。');return;
    }
    const current=()=>alive.current&&enabled.current&&node.isConnected&&!document.hidden;
    const sameDraft=()=>{
      const {composer,state,phoneDraft}=bridge.getState();
      return !document.hidden&&bridge.getDocumentEpoch()===source.documentEpoch&&composer?.ready===true&&composer.key===source.target.key&&
        composer.epoch===source.target.epoch&&!state.chatSubmitting&&(phoneDraft as {phone?:boolean}|null)?.phone!==true;
    };
    let windowId='',submitted=false;
    entered.current=true;setBusy(true);report('正在核对这段原文…');
    try{
      if(!sameDraft())throw new Error('原草稿窗口已变化，这次没有加入。');
      const original=await bridge.command('messageQuote.open',source.target);
      windowId=typeof original?.windowId==='string'?original.windowId:'';
      if(!current()){if(alive.current)report('已离开原选区，没有加入草稿。');return}
      if(!windowId||original.text!==text||original.maxChars!==4000||range.chars>original.maxChars||!Number.isSafeInteger(original.draftRevision)||original.draftRevision<0)throw new Error('当前原消息与阅读时不一致，没有加入草稿。请返回原消息重新选择。');
      if(node.selectionStart!==a||node.selectionEnd!==b||node.value.slice(a,b)!==selected)throw new Error('选区已改变，没有加入草稿。请核对当前选区。');
      if(!sameDraft()||bridge.getState().composer?.revision!==original.draftRevision)throw new Error('原草稿已变化，这次没有加入。');
      submitted=true;setLast({start:a,end:b,confirmed:false});
      const receipt=await bridge.command('messageQuote.stage',{windowId,...range});
      if(!alive.current)return;
      if(!confirmedQuoteStage(receipt,source.target,original.draftRevision))throw new Error('尚未确认所选原文已保存到这份草稿。');
      if(!sameDraft())throw new Error('原选段的保存回执属于原草稿，不是现在的输入。');
      setLast({start:a,end:b,confirmed:true});
      const letters=Array.from(selected),excerpt=letters.slice(0,24).join('')+(letters.length>24?'…':'');
      const moved=!current()||node.selectionStart!==a||node.selectionEnd!==b;
      report(`已将刚才选的「${excerpt}」关联并保存在这份草稿里。${moved?'当前选区未加入，问题也没发送。':'正文未改，问题还没发送。'}`);
    }catch(failure){
      if(alive.current){
        const reason=failure instanceof Error?failure.message:'原消息暂不可核对。';
        report(submitted?`${reason} 请核对原草稿中的引用，没有重复提交。`:reason);
      }
    }finally{
      // Close only our window after its receipt, even if the reader was closed.
      if(windowId)void bridge.command('messageQuote.close',{windowId}).catch(()=>{});
      entered.current=false;if(alive.current)setBusy(false);
    }
  };
  if(!visible)return null;
  return <button type="button" aria-busy={busy} disabled={busy||start>=end||sameLast}
    onPointerDown={event=>event.preventDefault()} onClick={()=>{void quote()}}>
    <IconQuote size={18} aria-hidden="true"/><span>{busy?'正在核对':sameLast?(last.confirmed?'已引用':'待核对'):'引用到草稿'}</span>
  </button>;
}
