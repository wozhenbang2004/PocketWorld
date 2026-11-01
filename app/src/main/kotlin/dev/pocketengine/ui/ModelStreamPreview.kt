package dev.pocketengine.ui
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
@Composable internal fun ModelStreamPreview(draft:UiModelDraft){
 if(draft.text.isBlank()&&draft.interrupted.isEmpty())return
 Column(Modifier.fillMaxWidth().padding(vertical=8.dp)){
  draft.interrupted.filter{it.text.isNotBlank()}.forEach{attempt ->
   Text("此前推理 ${attempt.requestId.takeLast(8)} · 中断草稿，未作为答复或工具结果",fontSize=11.sp,color=SecondaryInk)
   SelectionContainer{Text(attempt.text,fontSize=13.sp,lineHeight=21.sp,color=SecondaryInk,modifier=Modifier.heightIn(max=100.dp).verticalScroll(rememberScrollState()))}
  }
  Text(if(draft.failed)"本次推理中断 · 已显示片段保留在原步骤记录" else if(draft.turnEnded)"模型草稿 · 本轮尚待任务核对" else "模型正在生成 · 临时草稿",fontSize=11.sp,color=SecondaryInk)
  SelectionContainer{Text(draft.text,fontSize=14.sp,lineHeight=23.sp,color=SecondaryInk,modifier=Modifier.padding(top=5.dp).heightIn(max=160.dp).verticalScroll(rememberScrollState()))}
  if(draft.truncated)Text("仅预览前32768字；正式回复和真实操作回执以任务记录为准。",fontSize=11.sp,color=SecondaryInk)
 }
}
