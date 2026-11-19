import {useEffect,useId,useRef,useState} from 'react';
import {bridge} from './bridge';
import {Button} from './forms-library';
import {IconChevronDown,IconChevronRight} from './icons';
import {sameTeamProposal,teamProposalEntryKey,teamProposalMark,type TeamProposalMark,type TeamDecisionOrigin} from './team-navigation';

type Option={target:TeamProposalMark;title:string;author:string;excerpt?:string;excerptTruncated?:boolean};
type Page={stamp:string;options?:Option[];error?:string;opening?:string};
function original(value:Record<string,unknown>,s:TeamDecisionOrigin){
  return value.runId===s.teamRunId&&value.parentTaskId===s.parentTaskId&&value.parentRunId===s.parentRunId&&value.parentEpoch===s.parentEpoch
    &&value.conversationId===s.conversationId&&value.readOnly===true&&value.executionAuthorized===false;
}
export function ConversationParentRevisions({source,stamp,count,label,onOpen}:{source:TeamDecisionOrigin;stamp:string;count:number;label:string;onOpen:(target:TeamProposalMark)=>void}){
  const id=useId(),turn=useRef(0),pending=useRef(false),[page,setPage]=useState<Page|null>(null);
  const shown=page?.stamp===stamp?page:null,visible=()=>!document.hidden&&bridge.getState().state.conversationId===source.conversationId;
  const close=()=>{turn.current++;pending.current=false;setPage(null)};
  useEffect(()=>{const hide=()=>{if(document.hidden)close()};document.addEventListener('visibilitychange',hide);return()=>{turn.current++;pending.current=false;document.removeEventListener('visibilitychange',hide)}},[stamp]);
  const read=async(target?:TeamProposalMark)=>{
    if(pending.current||!visible())return;
    const n=++turn.current;pending.current=true;setPage({stamp,options:target?shown?.options:undefined,opening:target?.operationId});
    const current=()=>turn.current===n&&visible();
    try{
      const value=await bridge.command('teams.attention',{...source,...(target?{proposal:target}:{proposalDirectory:true})});
      if(!current())return;
      if(!original(value,source))throw new Error('原工作或协作已变化，没有离开对话。');
      if(target){if(!sameTeamProposal(target,value.proposal))throw new Error('这份提案已变化，没有打开其他分工。');onOpen(target);return}
      if(!Array.isArray(value.proposals)||value.proposals.length>16)throw new Error('原提案目录未能核对。');
      const seen=new Set<string>();
      const options:Option[]=value.proposals.map((row:Record<string,unknown>)=>{
        const mark=teamProposalMark(row);
        if(!mark||seen.has(mark.operationId)||typeof row.title!=='string'||typeof row.author!=='string')throw new Error('原提案目录已变化，请重新读取。');
        seen.add(mark.operationId);return {target:mark,title:row.title,author:row.author,excerpt:typeof row.excerpt==='string'?row.excerpt:undefined,excerptTruncated:row.excerptTruncated===true};
      });setPage({stamp,options});
    }catch(e){if(current())setPage({stamp,error:e instanceof Error?e.message:'无法核对这份原提案，没有离开对话。'})}
    finally{if(current())pending.current=false}
  };
  return <><Button size="small" variant="ghost" aria-expanded={!!shown} aria-controls={id} data-workspace-entry={teamProposalEntryKey(source)}
    trailingIcon={shown?IconChevronDown:IconChevronRight} onClick={()=>shown?close():void read()}>{label&&label+' · '}{count} 项管家修订提案</Button>
    {shown&&<div id={id} className="pw-team-decision-entries">{shown.error?<><p role="alert">{shown.error}</p><Button size="small" variant="ghost" onClick={()=>void read()}>重新读取原提案</Button></>
      :!shown.options?<p role="status">正在读原工作…</p>:!shown.options.length?<p role="status">这里已没有可核对的原修订提案。</p>
      :shown.options.map((item,i)=><Button size="small" variant="ghost" key={item.target.operationId} disabled={!!shown.opening} trailingIcon={IconChevronRight} onClick={()=>void read(item.target)}>
        <span><small>{item.author} · {shown.opening===item.target.operationId?'正在核对原版本…':'原分工'}</small><strong dir="auto">{item.title}</strong>
        {item.excerpt&&<small dir="auto">第 {i+1} 项 · 提案节选：{item.excerpt}{item.excerptTruncated?'…':''}</small>}</span>
      </Button>)}</div>}</>;
}
