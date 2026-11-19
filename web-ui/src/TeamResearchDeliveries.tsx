import { Accordion, AccordionItem, Button } from './forms-library';
import { IconChevronRight } from './icons';
import { teamMemberLabel } from './team-identity';

type Row=Record<string,any>;
const rows=(value:unknown):Row[]=>Array.isArray(value)?value.filter(x=>x&&typeof x==='object'):[];
const id=(s:unknown):s is string=>typeof s==='string'&&/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/.test(s);
const positive=(n:unknown):n is number=>Number.isSafeInteger(n)&&Number(n)>0;
export type TeamResearchDelivery={key:string;team:string;memberId:string;round:number;taskId:string;hash:string;name:string;excerpt:string;excerpted:boolean};

/** Only completed original SQL research answers. A roster, task ID, status
 * count, historical entry or a member's last displayed text is not a source. */
export function teamResearchDeliveries(run:Row|null,messages:unknown):TeamResearchDelivery[]{
  if(!run?.id||run.researchExecutionFormat!=='pocketworld.team-research-agent.v1')return [];
  const results:TeamResearchDelivery[]=[], roster=rows(run.members), answers=rows(messages).filter(m=>m.role==='assistant'&&m.kind==='answer');
  for(const member of roster){
    if(typeof member.id!=='string'||!member.id||roster.filter(m=>m.id===member.id).length!==1)continue;
    const stages=rows(member.roundStates);
    for(const stage of stages){
      const r=stage.researchTask,ref=r?.outputReference,round=stage.round,taskId=stage.researchTaskId;
      if(stage.status!=='completed'||!positive(round)||round>6||stages.filter(s=>s.round===round).length!==1||
        !id(taskId)||r?.taskId!==taskId||r.teamRunId!==run.id||r.memberId!==member.id||r.round!=null&&r.round!==round||
        r.status!=='completed'||r.executionAuthorized!==false||!id(r.runId)||!positive(r.coordinatorEpoch)||
        ref?.format!=='pocketworld.team-research-original.v1'||ref.taskId!==taskId||ref.runId!==r.runId||ref.coordinatorEpoch!==r.coordinatorEpoch||
        !Number.isSafeInteger(ref.messageIndex)||ref.messageIndex<0||!positive(ref.chars)||typeof ref.sha256!=='string'||!/^([0-9a-f]{64})$/.test(ref.sha256))continue;
      // A renamed or exited member's actual turn can still have a frozen author.
      const authors=answers.filter(a=>id(stage.turnId)&&a.turnId===stage.turnId&&a.memberId===member.id&&a.round===round&&a.memberGeneration===stage.generation&&a.memberSnapshot?.id===member.id);
      const original=authors.length===1?authors[0].memberSnapshot:null;
      const name=original?teamMemberLabel(original,roster.filter(m=>m.id!==member.id).concat(original)):
        typeof member.name==='string'&&member.name.trim()?`${teamMemberLabel(member,roster)}（当前名称）`:member.id;
      const body=typeof r.content==='string'?r.content.trim():'';
      const points=Array.from(body.slice(0,224).replace(/\s+/gu,' '));
      results.push({key:JSON.stringify([run.id,member.id,round,taskId,r.runId,r.coordinatorEpoch,ref.messageIndex,ref.sha256]),
        team:run.id,memberId:member.id,round,taskId,hash:ref.sha256,name,excerpt:points.slice(0,80).join(''),excerpted:points.length>80||r.contentTruncated===true});
    }
  }
  return results;
}

export function TeamResearchDeliveries({records,disabled,open,expanded,onExpanded}:{records:TeamResearchDelivery[];disabled:boolean;open:(source:TeamResearchDelivery)=>void;expanded:boolean;onExpanded:(v:boolean)=>void}){
  if(!records.length)return null;
  return <Accordion className="pw-team-disclosures" variant="divided" expandedKeys={new Set(expanded?['member-originals']:[])}
    onExpandedChange={keys=>onExpanded(Array.from(keys).includes('member-originals'))}>
    <AccordionItem id="member-originals" title="成员交付的原件" description={`${new Set(records.map(s=>s.memberId)).size} 名成员 · ${records.length} 份研究产出`}>
      <div className="pw-team-action-list">{records.map(source=><Button key={source.key} data-team-delivery={source.key} variant="ghost"
        className="pw-team-question-row h-auto whitespace-normal" trailingIcon={IconChevronRight} disabled={disabled}
        aria-label={`查看${source.name}第 ${source.round} 阶段的原研究任务`} aria-description={source.excerpt||undefined} onClick={()=>open(source)}>
        <span className="pw-team-attention-copy"><strong>{source.name} · 第 {source.round} 阶段</strong>
          <small dir="auto">{source.excerpt?`${source.excerpt}${source.excerpted?'…':''}`:'查看原研究任务'}</small></span>
      </Button>)}</div>
    </AccordionItem>
  </Accordion>;
}
