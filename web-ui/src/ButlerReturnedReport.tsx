import {useRef,useState} from 'react';
import {bridge} from './bridge';
import {Button} from './forms-library';
import {IconFolder} from './icons';
import {OwnerWorkReport, type ReportPin, type ReportTarget} from './OwnerWorkReport';
import {ownerWorkPresentation} from './owner-work-presentation';
import type {ButlerHomeTask} from './ButlerHome';

export type VerifiedHomeReport={target:ReportTarget;pin:ReportPin;documentEpoch:number;resume:number};
const resume=()=>Number((bridge.getState().view as {resumeRevision?:number}|undefined)?.resumeRevision||0);

/** A native saved handoff, not inferred from completion, a summary or a filename. */
export function reportedHomeWork(task:ButlerHomeTask,work=ownerWorkPresentation(task)):ReportTarget|null{
  const runId=task.raw?.runId,epoch=task.raw?.coordinatorEpoch;
  if(!work?.delivered||!work.deliveryId||!work.reportMessageId||!work.conversationId||typeof runId!=='string'||!runId.trim()||!Number.isSafeInteger(epoch)||epoch<1)return null;
  return {taskId:task.id,runId,epoch,reportMessageId:work.reportMessageId,conversationId:work.conversationId};
}
export function sameHomeReport(a:ReportTarget|null,b:ReportTarget){
  return !!a&&a.taskId===b.taskId&&a.runId===b.runId&&a.epoch===b.epoch&&a.reportMessageId===b.reportMessageId&&a.conversationId===b.conversationId;
}

/** Smallest bounded read permitted by the real report store. Never retain or
 * render this probe body: the regular reader gets the pinned original page. */
export async function verifyHomeReport(target:ReportTarget,expected?:ReportPin):Promise<VerifiedHomeReport>{
  const documentEpoch=bridge.getDocumentEpoch(),revision=resume();
  const {taskId,runId,epoch,reportMessageId,conversationId}=target;
  const row=await bridge.command('ownerWork.report.read',{taskId,runId,epoch,reportMessageId,offset:0,maxChars:64,...expected});
  if(bridge.getDocumentEpoch()!==documentEpoch||resume()!==revision||row?.taskId!==taskId||row.runId!==runId||row.epoch!==epoch||
    row.reportMessageId!==reportMessageId||row.conversationId!==conversationId||row.readOnly!==true||row.ownerOnly!==true||row.executionAuthorized!==false||
    row.status!=='current'||!Number.isSafeInteger(row.reportRevision)||row.reportRevision<1||typeof row.bodyFingerprint!=='string'||!/^sha256:[a-f0-9]{64}$/.test(row.bodyFingerprint)||
    expected&&(row.reportRevision!==expected.expectedReportRevision||row.bodyFingerprint!==expected.expectedReportFingerprint)||
    typeof row.body!=='string'||row.body.length>64||row.readRange?.unit!=='UTF-16'||row.readRange.offset!==0||row.readRange?.chars!==row.body.length||!Number.isSafeInteger(row.readRange?.totalChars)||row.readRange.totalChars<row.body.length||row.readRange.totalChars>0&&!row.body.length||row.nextOffset!==(row.body.length<row.readRange.totalChars?row.body.length:-1))
    throw new Error(expected?'未能核对原汇报的版本和正文，请重新打开原工作。':'未核对到这份原汇报，留在管家首页。');
  if(!row.body.length)throw new Error('这份原汇报没有可读正文。');
  return {target,pin:{expectedReportRevision:row.reportRevision,expectedReportFingerprint:row.bodyFingerprint},documentEpoch,resume:revision};
}

export function currentHomeReport(report:VerifiedHomeReport){
  const rows=bridge.getState().state.ownerWork;
  return !document.hidden&&bridge.getDocumentEpoch()===report.documentEpoch&&resume()===report.resume&&Array.isArray(rows)&&
    rows.some((row:ButlerHomeTask)=>row?.id===report.target.taskId&&sameHomeReport(reportedHomeWork(row),report.target));
}
type ReturnedReportProps={
  report:VerifiedHomeReport|null; task:ButlerHomeTask; active:boolean;
  onCopy:(text:string)=>Promise<unknown>; onOriginal:()=>void; onConversation?:()=>void;
  onIndependentFollowUp?:(handle:string)=>Promise<boolean>;
  onLocateReport?:(report:VerifiedHomeReport)=>Promise<void>;
  onFiles?:(report:VerifiedHomeReport,target:HTMLButtonElement)=>Promise<void>;
};
export function ButlerReturnedReport({report,task,active,onCopy,onOriginal,onIndependentFollowUp,onConversation,onLocateReport,onFiles}:ReturnedReportProps){
  const [notice,setNotice]=useState('');
  const [opening,setOpening]=useState<'files'|'history'|'followup'|null>(null),flight=useRef(false);
  const work=ownerWorkPresentation(task);
  if(!report||bridge.getDocumentEpoch()!==report.documentEpoch||resume()!==report.resume||!sameHomeReport(reportedHomeWork(task,work),report.target))
    return <p className="pw-reading-note" role="status">原工作或汇报已变化，没有改读其他轮次。请返回管家首页核对。</p>;
  const identity=JSON.stringify([report.documentEpoch,report.resume,report.target,report.pin]);
  const blocked=!active||opening!==null;
  const open=async(kind:'files'|'history',target:HTMLButtonElement)=>{
    if(!active||flight.current)return;
    if(!currentHomeReport(report)){setNotice('原汇报已变化，请重新打开原工作。');return}
    const read=kind==='history'?onLocateReport:onFiles;
    if(!read)return;
    flight.current=true;setOpening(kind);setNotice('');
    try{await read(report,target)}
    catch(error){if(currentHomeReport(report)&&target.isConnected)setNotice(error instanceof Error?error.message:kind==='history'?'未能定位这条原消息。':'未能打开本轮文件。')}
    finally{flight.current=false;setOpening(null)}
  };
  const followUp=onIndependentFollowUp&&task.status==='completed'&&task.raw?.executionRole==='owner_work'&&
    !task.raw.developmentContext&&!task.raw.ownerPhoneControl&&!task.raw.ownerDeviceWork?async(handle:string)=>{
    if(!active||flight.current)return false;
    if(!currentHomeReport(report))throw Error('原汇报已变化，没有打开其他报告的追问。');
    flight.current=true;setOpening('followup');setNotice('');
    try{return await onIndependentFollowUp(handle)}
    finally{flight.current=false;setOpening(null)}
  }:undefined;
  return <>{work?.ownerDecision&&<p className="pw-reading-note">{work.ownerDecision.label}。阅读已保存的汇报不会替你确认或授权。</p>}
    <OwnerWorkReport key={identity} identity={identity} target={report.target} originalVersion={report.pin} active={active} supported={bridge.getState().capabilities.commands.includes('ownerWork.report.read')} onCopy={onCopy}
      onIndependentFollowUp={followUp} followUpState={opening==='followup'?'pending':blocked?'blocked':undefined}/>
    <div className="pw-action-row">
      {onLocateReport&&<Button variant="secondary" disabled={blocked} aria-busy={opening==='history'||undefined} onClick={event=>void open('history',event.currentTarget)}>
        {opening==='history'?'正在定位原消息…':'在原对话定位汇报'}
      </Button>}
      {onFiles&&<Button variant="secondary" leadingIcon={IconFolder} disabled={blocked} aria-busy={opening==='files'||undefined} onClick={event=>void open('files',event.currentTarget)}>
        {opening==='files'?'正在打开文件…':'查看本次文件'}
      </Button>}
      {!onLocateReport&&onConversation&&<Button variant="secondary" disabled={blocked} onClick={()=>{if(currentHomeReport(report))onConversation();else setNotice('原汇报已变化，没有打开其他对话。')}}>打开原对话</Button>}
      <Button variant="ghost" disabled={blocked} onClick={()=>{if(currentHomeReport(report))onOriginal();else setNotice('原工作已变化，没有打开其他执行。')}}>{work?.ownerDecision?'回原工作处理待办':'查看原工作详情'}</Button>
    </div>
    {notice&&<p className="pw-reading-note" role="status">{notice}</p>}</>;
}
