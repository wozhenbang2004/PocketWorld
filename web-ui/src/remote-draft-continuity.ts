import {remoteDraftState,remoteObject as obj,remoteRows,savedRemoteEditor,sameRemoteScope,type RemoteData as Data,type RemoteEditor} from './remote-task-workspace';

const nonBlank=(s:unknown):s is string=>typeof s==='string'&&s.length>0&&s.length<=2048;
const sha=(s:unknown):s is string=>typeof s==='string'&&/^[a-f0-9]{64}$/.test(s);
export type PhoneDraftPin={taskId:string;runId:string;epoch:number;id:string;revision:number;mode:string;scopeHash:string;textSha256:string};
export const validPhoneDraftPin=(p:PhoneDraftPin)=>nonBlank(p.taskId)&&nonBlank(p.runId)&&Number.isSafeInteger(p.epoch)&&p.epoch>=0&&
  /^[a-f0-9-]{36}$/.test(p.id)&&Number.isSafeInteger(p.revision)&&p.revision>0&&['steer','next_turn'].includes(p.mode)&&sha(p.scopeHash)&&sha(p.textSha256);

/** Volatile unsaved text is not a TaskStore record. Only reopen it in the same inspected native attempt, host and thread. */
export function originalEditorWindow(s:Data,documentEpoch:number):string|null{
  const source=obj(s.source),task=obj(s.task);
  if(s.teamRemoteWriter||s.configurationOnly||task.actorId!=='owner'||task.spaceId!=='private'||task.teamRunId||task.memberId||
      !nonBlank(s.taskId)||s.taskId!==source.taskId||s.taskId!==task.id||!nonBlank(source.runId)||source.runId!==task.runId||source.runId!==source.jobOperationId||
      !Number.isSafeInteger(source.coordinatorEpoch)||source.coordinatorEpoch<0||source.coordinatorEpoch!==task.coordinatorEpoch||!sha(s.profileBindingHash)||
      ![source.profileId,source.environmentId,source.jobId,source.worker,source.protocol,source.threadId].every(nonBlank))return null;
  return JSON.stringify([documentEpoch,s.taskId,source.runId,source.coordinatorEpoch,s.profileBindingHash,source.environmentId,source.jobId,
    source.worker,source.protocol,source.threadId,source.sessionId??null,source.turnId??null,source.promptRequestId??null,source.workspaceIdentity??null]);
}
const kept=new Map<string,{editor:RemoteEditor;until:number}>();
export function rememberOriginalEditor(key:string|null,snapshot:Data,editor:RemoteEditor){
  if(!key||!editor.scope||!sameRemoteScope(editor.scope,obj(snapshot.draftScopes)[editor.mode]))return;
  const now=Date.now();for(const[k,v]of kept)if(v.until<=now)kept.delete(k);
  kept.delete(key);kept.set(key,{editor,until:now+600000});if(kept.size>16)kept.delete(kept.keys().next().value!);
}
export function rememberedOriginalEditor(key:string|null,snapshot:Data):RemoteEditor|undefined{
  const record=key?kept.get(key):undefined;
  return record&&record.until>Date.now()&&record.editor.scope&&sameRemoteScope(record.editor.scope,obj(snapshot.draftScopes)[record.editor.mode])?record.editor:undefined;
}
export function exactLivePhoneDraft(snapshot:Data,p:PhoneDraftPin):Data|null{
  const source=obj(snapshot.source);
  if(!validPhoneDraftPin(p)||snapshot.fresh!==true||!snapshot.reviewId||!snapshot.windowHandle||snapshot.taskId!==p.taskId||
      source.taskId!==p.taskId||source.runId!==p.runId||source.jobOperationId!==p.runId||source.coordinatorEpoch!==p.epoch)return null;
  const matches=remoteRows(snapshot.inputDrafts).filter(r=>r.id===p.id);if(matches.length!==1)return null;const r=matches[0];
  return r.revision===p.revision&&r.mode===p.mode&&r.phoneScopeHash===p.scopeHash&&r.targetCurrent===true&&['draft','queued'].includes(r.state)&&
    !r.operationId&&typeof r.text==='string'&&r.text.length>0&&r.text.length<=12000&&remoteDraftState(snapshot,savedRemoteEditor(r)).sameTarget?r:null;
}
