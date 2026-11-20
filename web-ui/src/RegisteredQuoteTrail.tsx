import {Accordion,AccordionItem,Button} from './forms-library';
import {IconArrowUpRight} from './icons';

type Range = {revision:number; offset:number; chars:number; visibleMessageId?:string};
export type HistoryQuoteVisit = Range & {window:number; conversation:string; request:string; requestRevision:number; target:string; returnInset:number};
type Row = Record<string,any>;

function ranges(message:Row):Range[] {
  const refs=message.registeredQuotes;
  if(message.role!=='user'||typeof message.readerInputTaskId!=='string'||!message.readerInputTaskId||
    !Array.isArray(refs)||!refs.length||refs.length>4)return [];
  return refs.every(r=>r&&Number.isSafeInteger(r.revision)&&r.revision>0&&Number.isSafeInteger(r.offset)&&r.offset>=0&&
    Number.isSafeInteger(r.chars)&&r.chars>0&&r.chars<=4000&&r.offset+r.chars<=2147483647&&
    (r.visibleMessageId===undefined||typeof r.visibleMessageId==='string'&&r.visibleMessageId.length>0))?refs:[];
}

/** A link scrolls only within these already-visible original rows. No read, text expansion or submission. */
export function sameQuoteVisit(page:Row|null,visit:HistoryQuoteVisit):boolean {
  if(!page||page.loading||page.limited||page.error||page.window!==visit.window||page.conversation!==visit.conversation||
    !Array.isArray(page.messages))return false;
  const from=page.messages.find((m:Row)=>m.id===visit.request&&m.sourceRevision===visit.requestRevision);
  return !!from&&ranges(from).some(r=>r.visibleMessageId===visit.target&&r.revision===visit.revision&&r.offset===visit.offset&&r.chars===visit.chars)&&
    page.messages.some((m:Row)=>m.id===visit.target&&m.sourceRevision===visit.revision);
}

export function RegisteredQuoteTrail({message,locked,hasEarlier,locate}:{message:Row;locked:boolean;hasEarlier:boolean;locate:(range:Range,target:string,from:HTMLElement)=>void}) {
  const selected=ranges(message);
  if(!selected.length)return null;
  return <Accordion className="pw-compact-disclosures"><AccordionItem id={'quotes-'+message.id} title={`随这条请求提交的引用 · ${selected.length}`}>
    <ol className="pw-history-quote-ranges">{selected.map((r,i)=><li key={[i,r.visibleMessageId,r.offset,r.chars,r.revision].join(':')}>
      <span>第 {r.offset+1} 至 {r.offset+r.chars} 位 · 原消息第 {r.revision} 版</span>
      {r.visibleMessageId?<Button variant="ghost" size="small" leadingIcon={IconArrowUpRight} disabled={locked} data-quote-start={r.offset} data-quote-size={r.chars} data-quote-target={r.visibleMessageId} onClick={event=>locate(r,r.visibleMessageId!,event.currentTarget)}>定位原消息</Button>:<small>{hasEarlier?'本页尚未核对到这一版原消息，可先查看更早消息。':'这份阅读窗口没有可核对的这一版原消息。'}</small>}
    </li>)}</ol>
    <p className="pw-muted">位置按原文 UTF-16 计，部分表情占两位。登记不等于模型已经读取或采用。</p>
  </AccordionItem></Accordion>;
}
