import { Accordion, AccordionItem, Button, IconButton } from './forms-library';
import { IconChevronRight, IconExternalLink } from './icons';
import { teamMemberLabel } from './team-identity';

type Row=Record<string,any>;
const rows=(value:unknown):Row[]=>Array.isArray(value)?value.filter(x=>x&&typeof x==='object'):[];
const id=(s:unknown):s is string=>typeof s==='string'&&/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/.test(s);
const positive=(n:unknown):n is number=>Number.isSafeInteger(n)&&Number(n)>0;
const sha=(s:unknown):s is string=>typeof s==='string'&&/^[0-9a-f]{64}$/.test(s);
const discussionFormat='pocketworld.evidence-dialogue.v1';
const discussionStages=[['independent','独立观点'],['challenge','证据质疑'],['response','原作者回应']] as const;
type DiscussionAnswer={key:string;label:string;text:string};
export type TeamResearchDelivery={key:string;team:string;memberId:string;round:number;taskId:string;hash:string;name:string;excerpt:string;excerpted:boolean;answer?:DiscussionAnswer};

/** Read the coordinator's stored presentation of the protocol answer, not a
 * UI interpretation of the worker's raw JSON or a new evidence finding. */
function discussionAnswer(run:Row, stage:Row, message:Row|undefined):DiscussionAnswer|undefined{
  if(run.discussionFormat!==discussionFormat||run.discussion?.format!==discussionFormat||!message)return;
  const phase=discussionStages[stage.round-1],record=stage.discussionRecord;
  if(!phase||!record||!id(message.messageId)||!sha(record.recordId)||!sha(record.recordVersion)||record.businessVerified!==false||
    !Number.isSafeInteger(stage.generation)||stage.generation<0||record.memberGeneration!==stage.generation||record.memberId!==message.memberId||
    record.turnId!==stage.turnId||record.round!==stage.round||record.stage!==phase[0]||message.discussionStage!==phase[0]||
    message.discussionRecordId!==record.recordId||message.truncated===true||typeof stage.text!=='string'||!stage.text.trim()||message.content!==stage.text)return;
  const retained=rows(run.discussion.records).filter(row=>row.recordId===record.recordId);
  if(retained.length!==1||retained[0].recordVersion!==record.recordVersion||retained[0].turnId!==record.turnId||
    retained[0].memberId!==record.memberId||retained[0].memberGeneration!==record.memberGeneration||retained[0].round!==record.round||retained[0].stage!==record.stage)return;
  return {key:JSON.stringify([message.messageId,record.recordId,record.recordVersion]),label:phase[1],text:stage.text};
}

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
        !Number.isSafeInteger(ref.messageIndex)||ref.messageIndex<0||!positive(ref.chars)||!sha(ref.sha256))continue;
      // A renamed or exited member's actual turn can still have a frozen author.
      const authors=answers.filter(a=>id(stage.turnId)&&a.turnId===stage.turnId&&a.memberId===member.id&&a.round===round&&a.memberGeneration===stage.generation&&a.memberSnapshot?.id===member.id);
      const message=authors.length===1?authors[0]:undefined, original=message?.memberSnapshot;
      const name=original?teamMemberLabel(original,roster.filter(m=>m.id!==member.id).concat(original)):
        typeof member.name==='string'&&member.name.trim()?`${teamMemberLabel(member,roster)}（当前名称）`:member.id;
      const answer=discussionAnswer(run,stage,message);
      const body=answer?answer.text.slice(answer.text.indexOf('\n')+1).trim():typeof r.content==='string'?r.content.trim():'';
      const points=Array.from(body.slice(0,224).replace(/\s+/gu,' '));
      results.push({key:JSON.stringify([run.id,member.id,round,taskId,r.runId,r.coordinatorEpoch,ref.messageIndex,ref.sha256]),
        team:run.id,memberId:member.id,round,taskId,hash:ref.sha256,name,excerpt:points.slice(0,80).join(''),excerpted:points.length>80||!answer&&r.contentTruncated===true,answer});
    }
  }
  return results;
}

export function TeamResearchDeliveries({records,disabled,rawAvailable,open,openAnswer,expanded,onExpanded}:{records:TeamResearchDelivery[];disabled:boolean;rawAvailable:boolean;
  open:(source:TeamResearchDelivery)=>void;openAnswer?:(source:TeamResearchDelivery,trigger:HTMLButtonElement)=>void;expanded:boolean;onExpanded:(v:boolean)=>void}){
  if(!records.length)return null;
  return <Accordion className="pw-team-disclosures" variant="divided" expandedKeys={new Set(expanded?['member-originals']:[])}
    onExpandedChange={keys=>onExpanded(Array.from(keys).includes('member-originals'))}>
    <AccordionItem id="member-originals" title="成员交付的原件" description={`${new Set(records.map(s=>s.memberId)).size} 名成员 · ${records.length} 份研究产出`}>
      <div className="pw-team-action-list">{records.map(source=>{
        const answer=openAnswer&&source.answer, stage=answer?answer.label:`第 ${source.round} 阶段`;
        const rawLabel=`查看${source.name}第 ${source.round} 阶段的原研究任务`;
        return <div className="pw-team-research-delivery" key={source.key}>
          <Button data-team-delivery={answer?undefined:source.key} data-team-follow-up={answer?answer.key:source.key} variant="ghost" className="pw-team-question-row h-auto whitespace-normal" trailingIcon={IconChevronRight}
            aria-label={answer?`阅读${source.name}的${stage}`:rawLabel} aria-description={source.excerpt||undefined} disabled={disabled||!answer&&!rawAvailable}
            onClick={event=>answer?openAnswer!(source,event.currentTarget):open(source)}>
            <span className="pw-team-attention-copy"><strong>{source.name} · {stage}</strong>
              <small dir="auto">{source.excerpt?`${source.excerpt}${source.excerpted?'…':''}`:answer?'阅读成员答复':'查看原研究任务'}</small></span>
          </Button>
          {answer&&rawAvailable&&<IconButton className="pw-utility-icon" icon={IconExternalLink} data-team-delivery={source.key} data-team-follow-up={source.key} aria-label={rawLabel} title={rawLabel}
            disabled={disabled} onClick={()=>open(source)}/>}
        </div>;
      })}</div>
    </AccordionItem>
  </Accordion>;
}
