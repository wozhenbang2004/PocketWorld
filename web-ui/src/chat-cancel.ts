import {taskNeedsReconciliation} from './task-presentation';

export type ChatCancelIdentity = { conversationId: string; taskId: string; runId: string; epoch: number };
export type ChatStopRecord = { original: ChatCancelIdentity; request: 'sending' | 'requested' | 'unknown' };

/** Only already-projected native identity is used, with no content or inferred replacement run. */
export function chatCancelIdentity(state: Record<string, any>): ChatCancelIdentity | null {
  const conversationId = state.conversationId;
  const turn = state.chatActiveTurn;
  if (state.chatBusy !== true || typeof conversationId !== 'string' || !conversationId ||
      !turn || turn.conversationId !== conversationId ||
      typeof turn.taskId !== 'string' || !turn.taskId || typeof turn.runId !== 'string' || !turn.runId ||
      !Number.isInteger(turn.epoch) || turn.epoch < 1) return null;
  return { conversationId, taskId: turn.taskId, runId: turn.runId, epoch: turn.epoch };
}

export function sameChatCancelIdentity(state: Record<string, any>, expected: ChatCancelIdentity): boolean {
  const actual = chatCancelIdentity(state);
  return !!actual && actual.conversationId === expected.conversationId && actual.taskId === expected.taskId &&
    actual.runId === expected.runId && actual.epoch === expected.epoch;
}

export function chatCancelKey(identity: ChatCancelIdentity): string {
  return JSON.stringify([identity.conversationId, identity.taskId, identity.runId, identity.epoch]);
}

export function chatCancelFeedback<T>(state: Record<string, any>, feedback: Record<string, T>): T | undefined {
  const current = chatCancelIdentity(state);
  return current ? feedback[chatCancelKey(current)] : undefined;
}

/** Keep the clicked run addressable even when its foreground slot is released.
 * A stop ACK, cleared composer busy state, or newer task generation is not a terminal receipt. */
export function chatStopObservation(state:Record<string,any>, records:Record<string,ChatStopRecord>){
  const conversation=state.conversationId;
  if(typeof conversation!=='string'||!conversation||state.conversationReader)return null;
  const active=chatCancelIdentity(state);
  const retained=state.chatBusy===true?chatCancelFeedback(state,records):
    Object.values(records).reverse().find(row=>row.original.conversationId===conversation);
  if(!retained)return null;
  const {original,request}=retained;
  const rows=Array.isArray(state.tasks)?state.tasks.filter(row=>row?.id===original.taskId):[];
  const row=rows.length===1?rows[0]:null,raw=row?.raw;
  const exact=raw?.id===original.taskId&&raw.actorId==='owner'&&raw.spaceId==='private'&&
    raw.conversationId===conversation&&raw.runId===original.runId&&raw.coordinatorEpoch===original.epoch;
  const shown={original,canOpen:rows.length===0||exact};
  if(rows.length&&!exact)return {...shown,error:true,message:'当前记录不是刚才停止的那一轮。请从原工作历史核对，没有切换到其他结果。'};
  const status=exact&&typeof row.status==='string'&&row.status===raw.status?row.status:'';
  if(status&&taskNeedsReconciliation(row))
    return {...shown,error:false,message:'原工作还有操作结果待核对。停止请求不代表这些动作没有发生。'};
  if(status==='interrupted')return {...shown,error:false,message:'原运行已中断，停止结果仍待核对。'};
  if(!active&&['cancelled','canceled','stopped'].includes(status))
    return {...shown,error:false,message:'原工作已记录为停止。已发生的动作不会因此撤销。'};
  if(status==='completed'||status==='failed')
    return {...shown,error:false,message:status==='completed'?'原工作有完成记录，不是停止回执。':'原工作已结束但未完成，不是停止回执。'};
  if(status&&(raw.cancelRequested===true||['stopping','cancelling'].includes(status)))
    return {...shown,error:false,message:'原工作已登记停止请求，尚未核对到这轮结束。'};
  if(request==='requested')return {...shown,error:false,message:'已请求停止原回复，尚未确认这轮结束。'};
  if(request==='sending')return {...shown,error:false,message:'正在请求停止这次原回复。'};
  return {...shown,error:true,message:'原停止请求尚未确认。请核对这轮工作，不会自动重试。'};
}
