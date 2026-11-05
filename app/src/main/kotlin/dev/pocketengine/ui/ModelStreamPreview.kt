package dev.pocketengine.ui

import org.json.JSONObject

internal data class InterruptedModelAttempt(val requestId:String,val text:String)

internal data class UiModelDraft(val taskId:String,val runId:String,val epoch:Int,val requestId:String,val generation:Long,val sequence:Long,val pauseRevision:Long,val provider:String,val text:String="",val turnEnded:Boolean=false,val truncated:Boolean=false,val failed:Boolean=false,val interrupted:List<InterruptedModelAttempt> = emptyList())
/** Presentation reducer only; no task/message/history state can be completed or persisted here. */

internal object ModelStreamDrafts {
 const val MAX_CHARS=32768
 fun accept(drafts:Map<String,UiModelDraft>,event:JSONObject,tasks:List<UiTask>):Map<String,UiModelDraft>{
  val id=event.optString("taskId");val body=event.optJSONObject("stream")?:return drafts
  val old=drafts[id];val request=body.optString("requestId");val run=event.optString("runId");val epoch=event.optInt("coordinatorEpoch");val revision=event.optLong("presentationRevision");val sequence=body.optLong("sequence");val pause=body.optLong("pauseRevision");val kind=body.optString("kind")
  if(body.opt("taskCompleted")!=false||id.isBlank()||request.isBlank()||revision<1||sequence<1)return drafts
  val same=old!=null&&old.requestId==request&&old.runId==run&&old.epoch==epoch&&old.generation==revision&&old.pauseRevision==pause
  if(body.optBoolean("discardDraft"))return if(same&&sequence>old!!.sequence)drafts-id else drafts
  val task=tasks.find{it.id==id}?:return drafts
  if(task.raw.optString("runId")!=run||task.raw.optInt("coordinatorEpoch")!=epoch||task.status !in setOf("preparing","running"))return drafts
  if(old!=null&&(revision<old.generation||revision==old.generation&&!same))return drafts
  if(kind=="started")return if(sequence==1L&&(old==null||revision>old.generation))drafts+(id to UiModelDraft(id,run,epoch,request,revision,sequence,pause,body.optString("provider"),interrupted=if(old?.failed==true)(old.interrupted+InterruptedModelAttempt(old.requestId,old.text)).takeLast(6)else old?.interrupted.orEmpty())) else drafts
  if(!same||sequence<=old!!.sequence)return drafts
  if(sequence!=old.sequence+1)return drafts-id
  if(kind=="failed")return drafts+(id to old.copy(sequence=sequence,turnEnded=true,failed=true))
  if(kind=="completed")return drafts+(id to old.copy(sequence=sequence,turnEnded=true))
  if(kind!="text_delta"||old.turnEnded||!body.optBoolean("provisional"))return drafts
  val delta=body.optString("text");if(delta.length>2048)return drafts-id
  val joined=old.text+delta;var end=minOf(joined.length,MAX_CHARS);if(end<joined.length&&end>0&&Character.isHighSurrogate(joined[end-1]))end--
  return drafts+(id to old.copy(sequence=sequence,text=joined.substring(0,end),truncated=old.truncated||end<joined.length))
 }
 fun retain(drafts:Map<String,UiModelDraft>,tasks:List<UiTask>):Map<String,UiModelDraft> = drafts.filterValues{draft->tasks.any{it.id==draft.taskId&&it.raw.optString("runId")==draft.runId&&it.raw.optInt("coordinatorEpoch")==draft.epoch&&it.status in setOf("preparing","running")}}
}
