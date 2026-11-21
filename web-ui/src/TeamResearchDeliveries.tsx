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
type MemberAnswer={key:string;label:string;text:string;plainText?:true};
export type TeamResearchDelivery={key:string;team:string;memberId:string;round:number;taskId:string;hash:string;name:string;excerpt:string;excerpted:boolean;archivedVersion?:number;revisionNote?:string;answer?:MemberAnswer};

/** Read the coordinator's stored presentation of the protocol answer, not a
 * UI interpretation of the worker's raw JSON or a new evidence finding. */
function discussionAnswer(run:Row, stage:Row, message:Row|undefined):MemberAnswer|undefined{
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

/** The coordinator only keeps a preview. Use the immutable child reference, not
 * its preview text, roster count or task ID, as the link to the actual answer. */
function originalRef(runId:string, memberId:string, round:number, taskId:unknown, result:Row|undefined) {
  const ref=result?.outputReference;
  if(!id(taskId)||result?.taskId!==taskId||result.teamRunId!==runId||result.memberId!==memberId||result.round!=null&&result.round!==round||
    result.status!=='completed'||result.executionAuthorized!==false||!id(result.runId)||!positive(result.coordinatorEpoch)||
    ref?.format!=='pocketworld.team-research-original.v1'||ref.taskId!==taskId||ref.runId!==result.runId||
    ref.coordinatorEpoch!==result.coordinatorEpoch||!Number.isSafeInteger(ref.messageIndex)||ref.messageIndex<0||!positive(ref.chars)||!sha(ref.sha256))return;
  return {key:JSON.stringify([runId,memberId,round,taskId,result.runId,result.coordinatorEpoch,ref.messageIndex,ref.sha256]),
    team:runId,memberId,round,taskId,hash:ref.sha256 as string};
}

/** The current native parent Work link is required; a team ID from the directory is not enough. */
export function classicAnswerOwner(reading:Row|null):string{
  const run=reading?.run, link=reading?.ownerWorkLink, roster=rows(run?.members);
  if(reading?.kind!=='run'||!reading.viewHandle||!id(run?.id)||!id(run.teamId)||run.mode!=='collaborate'||run.definitionKind!=null||
    (run.discussionFormat??'classic')!=='classic'||run.discussion||run.workPlan||run.productionScope||run.nativeWorkProfileModels||
    run.researchExecutionFormat!=='pocketworld.team-research-agent.v1'||run.archived===true||!['running','paused','completed','partial'].includes(run.status)||
    roster.length<1||roster.length>4||!roster.every(slot=>(slot.runner||'model')==='model'&&(!slot.device||slot.device==='phone'))||
    link?.readOnly!==true||link.currentRun!==true||link.stopping!==false||link.teamRunId!==run.id||!id(link.teamTaskId)||
    reading.taskId!==link.teamTaskId||!id(link.taskId)||!id(link.runId)||!positive(link.epoch)||!id(link.conversationId))return '';
  return JSON.stringify([run.id,run.teamId,link.teamTaskId,link.taskId,link.runId,link.epoch,link.conversationId]);
}
const classicAnswerBytes=65536;

/** Only a current exact stage and its unique authored native message can
 * nominate the already visible full text. The read gesture checks its SHA. */
function classicAnswer(stage:Row, result:Row, message:Row|undefined):MemberAnswer|undefined{
  const ref=result.outputReference, body=stage.text, preview=result.content;
  if(!message||!id(message.messageId)||message.truncated===true||!id(stage.turnId)||result.executionTurnId!==stage.turnId||
    result.round!==stage.round||result.memberGeneration!==stage.generation||result.runGeneration!==message.generation||
    !Number.isSafeInteger(result.runGeneration)||result.runGeneration<0||!positive(result.identityRevision)||
    (message.memberSnapshot?.identityRevision??1)!==result.identityRevision||
    typeof body!=='string'||!body.trim()||body.length!==ref.chars||body.length>classicAnswerBytes||message.content!==body||
    typeof preview!=='string'||!preview||result.contentChars!==ref.chars||
    !(result.contentTruncated===false&&preview===body||result.contentTruncated===true&&preview.length<body.length&&body.startsWith(preview))||
    new TextEncoder().encode(body).byteLength>classicAnswerBytes)return;
  return {key:JSON.stringify([message.messageId,stage.turnId,stage.generation,result.runGeneration,result.identityRevision]),
    label:'第 '+stage.round+' 阶段答复',text:body,plainText:true};
}
export async function exactClassicAnswer(source:TeamResearchDelivery):Promise<boolean>{
  if(source.answer?.plainText!==true||!globalThis.crypto?.subtle)return false;
  const body=new TextEncoder().encode(source.answer.text);
  if(body.byteLength>classicAnswerBytes)return false;
  const digest=new Uint8Array(await crypto.subtle.digest('SHA-256',body));
  return Array.from(digest,byte=>byte.toString(16).padStart(2,'0')).join('')===source.hash;
}

export function teamResearchDeliveries(run:Row|null,messages:unknown,edition:'current'|'archived'='current',classic=false):TeamResearchDelivery[]{
  if(!run?.id||run.researchExecutionFormat!=='pocketworld.team-research-agent.v1')return [];
  if(edition==='archived'&&(run.mode!=='collaborate'||run.workPlan||run.productionScope))return [];
  const results:TeamResearchDelivery[]=[], roster=rows(run.members), answers=rows(messages).filter(m=>m.role==='assistant'&&m.kind==='answer');
  for(const member of roster){
    if(typeof member.id!=='string'||!member.id||roster.filter(m=>m.id===member.id).length!==1)continue;
    const stages=rows(member.roundStates);
    for(const stage of stages){
      const round=stage.round;
      if(!positive(round)||round>6||stages.filter(s=>s.round===round).length!==1)continue;
      const author=(turn:unknown,generation:unknown)=>{
        const authors=answers.filter(a=>id(turn)&&a.turnId===turn&&a.memberId===member.id&&a.round===round&&
          Number.isSafeInteger(generation)&&generation===a.memberGeneration&&a.memberSnapshot?.id===member.id);
        const message=authors.length===1?authors[0]:undefined, original=message?.memberSnapshot;
        const name=original?teamMemberLabel(original,roster.filter(m=>m.id!==member.id).concat(original)):
          typeof member.name==='string'&&member.name.trim()?teamMemberLabel(member,roster)+'（当前名称）':member.id;
        return {message,name};
      };
      // A superseded receipt can be read by its owner, but must never count as
      // a current-stage result or make a retry/continuation eligible.
      if(edition==='archived'){
        const history=stage.researchHistory;
        if(!Array.isArray(history)||history.length>32)continue;
        const currentOriginal=stage.status==='completed'&&originalRef(run.id,member.id,round,stage.researchTaskId,stage.researchTask);
        let revisionNote=currentOriginal?'新版已有独立交付':'尚未读到新版的交付回执';
        for(let i=history.length-1;i>=0;i--){
          const old=history[i], r=old?.result;
          if(old?.reason!=='native_input_revision'||!positive(old.retiredAt)||old.taskId===stage.researchTaskId||
              history.filter(h=>h?.taskId===old.taskId).length!==1)continue;
          const source=originalRef(run.id,member.id,round,old.taskId,r);
          if(!source)continue;
          const {name}=author(r.executionTurnId,r.memberGeneration);
          results.push({...source,key:JSON.stringify([source.key,'archived',i+1,old.retiredAt]),name,archivedVersion:i+1,revisionNote,excerpt:'',excerpted:false});
          revisionNote='';
        }
        continue;
      }
      const r=stage.researchTask,source=originalRef(run.id,member.id,round,stage.researchTaskId,r);
      if(stage.status!=='completed'||!source)continue;
      const {name,message}=author(stage.turnId,stage.generation);
      const answer=discussionAnswer(run,stage,message)??(classic ? classicAnswer(stage,r,message) : undefined);
      const body=answer ? (answer.plainText ? answer.text : answer.text.slice(answer.text.indexOf('\n')+1)).trim()
        : typeof r.content==='string' ? r.content.trim() : '';
      const points=Array.from(body.slice(0,224).replace(/\s+/gu,' '));
      results.push({...source,name,excerpt:points.slice(0,80).join(''),excerpted:points.length>80||!answer&&r.contentTruncated===true,answer});
    }
  }
  return results;
}

export function TeamResearchDeliveries({records,disabled,rawAvailable,open,openAnswer,expanded,onExpanded,archived=false,groupByMember,locate}:{records:TeamResearchDelivery[];disabled:boolean;rawAvailable:boolean;
  open:(source:TeamResearchDelivery)=>void;openAnswer?:(source:TeamResearchDelivery,trigger:HTMLButtonElement)=>void;expanded:boolean;onExpanded:(v:boolean)=>void;archived?:boolean;
  groupByMember?:(id:string)=>string;locate?:{deliveryKey:string;open:()=>void}}){
  if(!records.length)return null;
  const group=archived?'archived-member-originals':'member-originals';
  const grouped=groupByMember&&!archived?new Map<string,TeamResearchDelivery[]>():null;
  if(grouped)for(const row of records){const member=grouped.get(row.memberId);if(member)member.push(row);else grouped.set(row.memberId,[row]);}
  const rowsFor=(source:TeamResearchDelivery,byline=true)=>{
    const answer=openAnswer&&source.answer, stage=source.archivedVersion?`第 ${source.round} 阶段 · 第 ${source.archivedVersion} 版`:answer?answer.label:`第 ${source.round} 阶段`;
    const rawLabel=source.archivedVersion?`查看${source.name}的${stage}答复的原任务`:`查看${source.name}第 ${source.round} 阶段的原研究任务`;
    return <div key={source.key}>
      <div className="pw-team-research-delivery">
        <Button data-team-delivery={answer?undefined:source.key} data-team-follow-up={answer?answer.key:source.key} variant="ghost" className="pw-team-question-row h-auto whitespace-normal" trailingIcon={IconChevronRight}
          aria-label={answer?`阅读${source.name}的${stage}${answer.plainText?'全文':''}`:rawLabel} aria-description={source.excerpt||undefined} disabled={disabled||!answer&&!rawAvailable}
          onClick={event=>answer?openAnswer!(source,event.currentTarget):open(source)}>
          <span className="pw-team-attention-copy"><strong>{byline?<><bdi>{source.name}</bdi> · </>:null}{stage}</strong>
            <small dir="auto">{source.excerpt?`${source.excerpt}${source.excerpted?'…':''}`:source.archivedVersion?(source.revisionNote||'查看原答复的任务记录'):answer?'阅读成员答复':'查看原研究任务'}</small></span>
        </Button>
        {answer&&rawAvailable&&<IconButton className="pw-utility-icon" icon={IconExternalLink} data-team-delivery={source.key} data-team-follow-up={source.key} aria-label={rawLabel} title={rawLabel}
          disabled={disabled} onClick={()=>open(source)}/>}
      </div>
      {locate?.deliveryKey===source.key&&<Button variant="ghost" size="small" className="pw-team-delivery-locate" trailingIcon={IconChevronRight}
        disabled={disabled} onClick={locate.open}>在进展定位此阶段</Button>}
    </div>;
  };
  return <Accordion className="pw-team-disclosures" variant="divided" expandedKeys={new Set(expanded?[group]:[])}
    onExpandedChange={keys=>onExpanded(Array.from(keys).includes(group))}>
    <AccordionItem id={group} title={archived?'修订前已交回的答复':grouped?'已保存的成员答复':'成员交付的原件'}
      description={`${new Set(records.map(s=>s.memberId)).size} 名成员 · ${records.length} 份${grouped?'阶段答复':'研究产出'}`}>
      {archived&&<p className="pw-field-hint">这些是修改要求前已交回的版本，不算本次新稿。点选可查看答复的原任务，不会让成员重做。</p>}
      <div className="pw-team-delivery-list">{grouped?Array.from(grouped,([id,member])=>{
        const author=member[0].name, sameName=member.every(source=>source.name===author);
        return <section className="pw-team-delivery-member" key={id}>
          <h4><bdi>{sameName?author:groupByMember!(id)+'（当前名称）'}</bdi><span>{member.length} 份</span></h4>
          {member.slice().sort((a,b)=>a.round-b.round).map(source=>rowsFor(source,!sameName))}
        </section>;
      }):records.map(source=>rowsFor(source))}</div>
    </AccordionItem>
  </Accordion>;
}
