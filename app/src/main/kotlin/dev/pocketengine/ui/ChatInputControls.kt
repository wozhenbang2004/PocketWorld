package dev.pocketengine.ui
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketengine.store.TaskStore
import org.json.JSONArray
import org.json.JSONObject
internal data class UiChatAttachment(val id:String,val sha256:String,val name:String,val mimeType:String,val size:Long){fun selection():JSONObject=JSONObject().put("id",id).put("sha256",sha256)}
internal object ChatInputRules {
 fun attachment(row:JSONObject):UiChatAttachment?=try{val id=row.getString("id");val sha=row.getString("sha256");if(!id.matches(Regex("[a-fA-F0-9-]{36}"))||!sha.matches(Regex("[a-f0-9]{64}")))null else UiChatAttachment(id,sha,row.optString("name","附件"),row.getString("mimeType"),row.getLong("size"))}catch(_:Exception){null}
 fun fingerprint(text:String,conversation:String?,mode:String,files:List<UiChatAttachment>,sourceRefs:JSONArray=JSONArray(),previewRefs:JSONArray=JSONArray()):String {
  val checked=dev.pocketengine.features.assistant.OwnerSourceReferences.checked(sourceRefs)
  val previews=dev.pocketengine.features.local.preview.PreviewElementRef.checked(previewRefs)
  val input=JSONObject().put("text",text).put("conversation",conversation?:"").put("mode",mode).put("attachments",JSONArray().also{a->files.forEach{a.put(it.selection())}})
  // Missing/empty locators preserve pre-reference pending identities exactly.
  if(checked.length()>0)input.put("ownerSourceRefs",checked)
  if(previews.length()>0)input.put("ownerPreviewRefs",previews)
  return TaskStore.capabilityFingerprint(input)
 }
 fun mergeReceipt(old:JSONObject?,incoming:JSONObject):JSONObject?{
  val client=incoming.optString("clientMessageId");val status=incoming.optString("status");if(client.isBlank()||status !in setOf("accepted","queued","unconfirmed","consumed","withdrawn"))return null
  if(status!="unconfirmed"&&(incoming.optString("messageId").isBlank()||incoming.optString("conversationId").isBlank()||incoming.optString("taskId").isBlank()||incoming.optString("runId").isBlank()||incoming.optInt("epoch")<1))return null
  if(status=="consumed"&&(incoming.optString("replyMessageId").isBlank()||incoming.optLong("consumedAt")<=0))return null
  val queueVersion=incoming.opt("queueRevision");if(incoming.has("queueRevision")&&(queueVersion !is Int&&queueVersion !is Long||incoming.optLong("queueRevision")<1))return null
  val messageVersion=incoming.opt("ownerMessageRevision");if(status=="withdrawn"&&(incoming.optLong("queueRevision")<1||messageVersion !is Int&&messageVersion !is Long||incoming.optLong("ownerMessageRevision")<1))return null
  if(old!=null){
   if(old.optString("clientMessageId")!=client)return null
   if(old.optString("status") in setOf("accepted","queued","consumed","withdrawn")){
    if(status=="unconfirmed")return old
    for(k in listOf("messageId","conversationId","taskId","runId","epoch"))if(old.opt(k)!=incoming.opt(k))return null
    if(old.optString("status")=="consumed"&&status!="consumed"||old.optString("status")=="withdrawn"&&status!="withdrawn")return old
    if(old.optLong("queueRevision")>0&&incoming.optLong("queueRevision")<old.optLong("queueRevision"))return old
   }
  }
  return JSONObject(incoming.toString())
 }
 /** Compare to the durable pending locator before any acknowledgement clears its draft. */
 fun receiptMatchesOriginal(incoming:JSONObject,conversation:String,target:JSONObject):Boolean {
  if(conversation.isNotBlank()&&incoming.has("conversationId")&&incoming.opt("conversationId")!=conversation)return false
  if(target.length()==0)return true
  val fields=listOf("taskId","runId","epoch")
  if(incoming.optString("status")=="unconfirmed"&&fields.none(incoming::has))return true
  val epoch=incoming.opt("epoch");val expected=target.opt("epoch")
  return incoming.opt("taskId")==target.opt("taskId")&&incoming.opt("runId")==target.opt("runId")&&
   (epoch is Int||epoch is Long)&&(expected is Int||expected is Long)&&(epoch as Number).toLong()==(expected as Number).toLong()
 }
 fun canSend(state:PocketUiState,phone:Boolean,text:String):Boolean=text.isNotBlank()&&text.trim().length<=12000&&!state.chatSubmitting&&(if(phone)!state.chatBusy&&state.activeTaskId==null&&!state.phoneSubmitting else !state.chatBusy||state.feature("assistant").optBoolean("sendAcknowledgements")&&state.chatActiveTurn!=null)
}
@Composable internal fun ChatAttachmentChooser(state:PocketUiState,controller:PocketWorldController){
 if(!state.chatAttachmentPickerOpen)return
 AlertDialog(onDismissRequest=controller::closeChatAttachmentPicker,title={Text("选择本条消息的附件")},text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
  Text("导入只进入私有附件库；只有此处勾选并发送，才会交给本轮模型。最多4个，过期摘要或不支持内容会明确拒绝。",fontSize=12.sp)
  if(state.feature("attachments").optJSONObject("indexDiagnostics")?.optBoolean("incomplete")==true)
   Text("部分附件尚未核对完整，可读文件仍可选择。未确认的原件已保留，请核对后再备份。",fontSize=13.sp,color=MaterialTheme.colorScheme.error)
  Text(if(state.chatAttachmentModel.optBoolean("supportsImageInput"))"当前任务模型由目录或机主声明支持图像输入，未声称已实测识图效果。" else "当前任务/所选模型未配置图像输入，可选受支持文档；图像需先在模型设置明确配置。",fontSize=12.sp,color=SecondaryInk)
  val rows=state.feature("attachments").optJSONArray("attachments").objects();if(rows.isEmpty())Text("还没有附件，可先导入。",fontSize=13.sp)
  rows.forEach{raw->val file=ChatInputRules.attachment(raw);if(file!=null){val selected=state.selectedChatAttachments.any{it.id==file.id};val image=file.mimeType.startsWith("image/");Row(verticalAlignment=Alignment.CenterVertically){Checkbox(selected,{controller.toggleChatAttachment(raw)},enabled=selected||!image||state.chatAttachmentModel.optBoolean("supportsImageInput"));Column(Modifier.weight(1f)){Text(file.name,fontSize=14.sp);Text(file.mimeType+" · "+file.size+" bytes",fontSize=11.sp,color=SecondaryInk)};TextButton(onClick={controller.closeChatAttachmentPicker();controller.openAttachmentOutcome(raw)}){Text("来源",fontSize=11.sp)}}}}
  Text("图片≤4MiB。短文档直接展开；长文档（≤20MiB）保留明确未读的来源引用，由同一原任务按需要分页读取。实际请求内容合计≤6MiB，不静默截断或换文件。",fontSize=11.sp,color=SecondaryInk)
 }},confirmButton={TextButton(onClick=controller::closeChatAttachmentPicker){Text("保留选择")}},dismissButton={TextButton(onClick={controller.pickAttachments()},enabled=!state.attachmentImporting){Text(if(state.attachmentImporting)"导入中" else "导入新附件")}})
}
@Composable internal fun ChatInputReceipt(receipt:JSONObject,controller:PocketWorldController){
 val status=receipt.optString("status");if(status !in setOf("accepted","queued","unconfirmed","consumed","withdrawn"))return
 Column(Modifier.fillMaxWidth()) {
  Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(when(status){"consumed"->"本轮已采用 · 答复已保存";"withdrawn"->"已撤回 · 不再加入后续请求";"queued"->if(receipt.optString("mode")=="steer")"已收到 · 等待下一安全边界" else "排队中 · 本轮结束后处理";"accepted"->"输入已收到";else->"发送状态待核对 · 请勿重复发送"},fontSize=12.sp,color=SecondaryInk,modifier=Modifier.weight(1f));if(status=="unconfirmed")TextButton(onClick={controller.queryChatSubmission(receipt.optString("clientMessageId"))}){Text("查询原回执",fontSize=12.sp)}}
  if(status in setOf("accepted","queued","withdrawn")&&receipt.optString("taskId").isNotBlank()&&receipt.optString("conversationId").isNotBlank())TextButton(onClick={controller.openQueuedOwnerInputs(receipt.getString("taskId"),receipt.getString("conversationId"))},contentPadding=PaddingValues(0.dp)){Text("查看待处理输入",fontSize=12.sp)}
 }
}
