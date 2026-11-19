import {useEffect,useId,useRef,useState} from 'react';
import {bridge} from './bridge';
import {Button} from './forms-library';
import {IconChevronDown,IconChevronRight} from './icons';
import type {OwnerWorkTeam} from './owner-work-presentation';
import type {TeamAttentionLocator} from './NotificationTeamAttention';
import {originalAttentionLocator} from './team-result-reading';
import {teamStatusLabel} from './team-presentation';
import {teamDecisionEntryKey,type TeamDecisionOrigin} from './team-navigation';

type Item={locator:TeamAttentionLocator;name:string;title:string;detail:string};
type Page={items:Item[];total:number};
export type OpenTeamDecision=(locator:TeamAttentionLocator,source:TeamDecisionOrigin)=>void;

function pageFor(value:Record<string,any>,source:TeamDecisionOrigin):Page {
  if(value.runId!==source.teamRunId||value.parentTaskId!==source.parentTaskId||value.parentRunId!==source.parentRunId
      ||value.parentEpoch!==source.parentEpoch||value.conversationId!==source.conversationId||value.readOnly!==true||value.executionAuthorized!==false
      ||!Number.isSafeInteger(value.total)||value.total<0||!Array.isArray(value.items)||value.items.length!==Math.min(value.total,8))throw new Error('没有核对到原工作的待办。');
  const items=value.items.map((row:Record<string,any>)=>{
    const locator=row&&originalAttentionLocator(row);
    if(!locator||locator.runId!==source.teamRunId||!['awaiting_approval','awaiting_input','awaiting_user','needs_confirmation'].includes(row.status))throw new Error('这项原待办已变化，请重新查看。');
    const title=typeof row.title==='string'?row.title.trim():'';
    return {locator,name:String(row.memberName||'成员'),title:title+(title&&row.titleTruncated===true?'…':''),
      detail:locator.questionId?'等待回答':teamStatusLabel(row.status)};
  });
  const sameNames=new Set(items.filter((item:Item)=>items.some((other:Item)=>other.name===item.name&&other.locator.memberId!==item.locator.memberId)).map((item:Item)=>item.name));
  for(const item of items)if(sameNames.has(item.name))item.name+=` · ${item.locator.memberId}`;
  return {items,total:value.total};
}

/** The existing work row still opens the parent. Only the owner expanding its team
 * reads question excerpts; neither the composer nor an executor owns this document. */
export function ConversationTeamDecisions({team,distinguish,onOpen}:{team:OwnerWorkTeam;distinguish:boolean;onOpen:OpenTeamDecision}) {
  const source:TeamDecisionOrigin={parentTaskId:team.parentTaskId,parentRunId:team.parentRunId,parentEpoch:team.parentEpoch,conversationId:team.conversationId,teamRunId:team.teamRunId};
  const entry=teamDecisionEntryKey(source),stamp=JSON.stringify([entry,team.updatedAt,bridge.getDocumentEpoch()]);
  const id=useId(),ticket=useRef(0),[reading,setReading]=useState<{stamp:string;page?:Page;error?:string}|null>(null);
  const shown=reading?.stamp===stamp?reading:null;
  const close=()=>{ticket.current++;setReading(null)};
  useEffect(()=>{const hidden=()=>{if(document.hidden)close()};document.addEventListener('visibilitychange',hidden);return()=>{ticket.current++;document.removeEventListener('visibilitychange',hidden)}},[stamp]);
  const open=async()=>{
    if(document.hidden||bridge.getState().state.conversationId!==source.conversationId)return;
    const token=++ticket.current;setReading({stamp});
    const current=()=>ticket.current===token&&!document.hidden&&bridge.getState().state.conversationId===source.conversationId;
    try{const page=await bridge.command('teams.attention',source);if(current())setReading({stamp,page:pageFor(page,source)})}
    catch(error){if(current())setReading({stamp,error:error instanceof Error?error.message:'未能读取这组原待办。'})}
  };
  const count=team.pendingDecisionCount??team.pendingQuestionCount??0;
  if(!team.needsAttention||team.cancelRequested||count<1)return null;
  return <div className="pw-conversation-team-decisions">
    <Button size="small" variant="ghost" data-workspace-entry={entry} aria-expanded={!!shown} aria-controls={id}
      trailingIcon={shown?IconChevronDown:IconChevronRight} onClick={()=>shown?close():void open()}>
      {distinguish&&team.goal?`${team.goal} · `:''}{team.pendingQuestionCount===count?`${count} 个成员问题`:`${count} 项成员待办`}
    </Button>
    {shown&&<div id={id} className="pw-team-decision-entries">
      {shown.error?<><p role="alert">{shown.error}</p><Button size="small" variant="ghost" onClick={()=>void open()}>重新读取</Button></>
        :!shown.page?<p role="status">正在读原协作…</p>
        :shown.page.total===0?<p role="status">这组不再有待答或待确认的成员事项。</p>
        :<>{shown.page.items.map(item=><Button key={JSON.stringify(item.locator)} variant="ghost" size="small" trailingIcon={IconChevronRight}
          onClick={()=>{if(!document.hidden&&bridge.getState().state.conversationId===source.conversationId)onOpen(item.locator,source)}}>
          <span><small>{item.name} · {item.detail}</small><strong dir="auto">{item.title||'查看原分工'}</strong></span>
        </Button>)}{shown.page.total>shown.page.items.length&&<p>仅显示前 {shown.page.items.length} 项，其余请从上方原工作查看。</p>}</>}
    </div>}
  </div>;
}
