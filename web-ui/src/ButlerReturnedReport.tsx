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
    throw new Error(expected?'原汇报暂不可核对，没有打开本轮文件。':'未核对到这份原汇报，留在管家首页。');
  if(!row.body.length)throw new Error('这份原汇报没有可读正文。');
  return {target,pin:{expectedReportRevision:row.reportRevision,expectedReportFingerprint:row.bodyFingerprint},documentEpoch,resume:revision};
}

export function currentHomeReport(report:VerifiedHomeReport){
  const rows=bridge.getState().state.ownerWork;
  return !document.hidden&&bridge.getDocumentEpoch()===report.documentEpoch&&resume()===report.resume&&Array.isArray(rows)&&
    rows.some((row:ButlerHomeTask)=>row?.id===report.target.taskId&&sameHomeReport(reportedHomeWork(row),report.target));
}
export function ButlerReturnedReport({report,task,active,onCopy,onOriginal,onFollowUp,onConversation,onFiles}:{report:VerifiedHomeReport|null;task:ButlerHomeTask;active:boolean;onCopy:(text:string)=>Promise<unknown>;onOriginal:()=>void;onFollowUp?:()=>void;onConversation?:()=>void;onFiles?:(report:VerifiedHomeReport,target:HTMLButtonElement)=>Promise<void>}){
  const [notice,setNotice]=useState('');
  const [filesPending,setFilesPending]=useState(false),fileFlight=useRef(false);
  if(!report||bridge.getDocumentEpoch()!==report.documentEpoch||resume()!==report.resume||!sameHomeReport(reportedHomeWork(task),report.target))
    return <p className="pw-reading-note" role="status">原工作或汇报已变化，没有改读其他轮次。请返回管家首页核对。</p>;
  const identity=JSON.stringify([report.documentEpoch,report.resume,report.target,report.pin]);
  const openFiles=async(target:HTMLButtonElement)=>{
    if(!onFiles||!active||fileFlight.current)return;
    fileFlight.current=true;setFilesPending(true);setNotice('');
    try{await onFiles(report,target)}
    catch(error){if(currentHomeReport(report)&&target.isConnected)setNotice(error instanceof Error?error.message:'未能打开本轮文件。')}
    finally{fileFlight.current=false;setFilesPending(false)}
  };
  return <><OwnerWorkReport key={identity} identity={identity} target={report.target} originalVersion={report.pin} active={active} supported={bridge.getState().capabilities.commands.includes('ownerWork.report.read')} onCopy={onCopy}/>
    <div className="pw-action-row">
      {onFiles&&<Button variant="secondary" leadingIcon={IconFolder} disabled={!active||filesPending} aria-busy={filesPending||undefined} onClick={event=>void openFiles(event.currentTarget)}>
        {filesPending?'正在打开文件…':'查看本次文件'}
      </Button>}
      {onFollowUp?<Button variant="secondary" disabled={!active||filesPending} onClick={()=>{if(currentHomeReport(report))onFollowUp();else setNotice('原汇报已变化，未关联到其他对话。')}}>在原对话追问</Button>
        :onConversation&&<Button variant="secondary" disabled={!active||filesPending} onClick={()=>{if(currentHomeReport(report))onConversation();else setNotice('原汇报已变化，没有打开其他对话。')}}>打开原对话</Button>}
      <Button variant="ghost" disabled={!active||filesPending} onClick={()=>{if(currentHomeReport(report))onOriginal();else setNotice('原工作已变化，没有打开其他执行。')}}>查看原工作详情</Button>
    </div>
    {notice&&<p className="pw-reading-note" role="status">{notice}</p>}</>;
}
