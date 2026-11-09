package dev.pocketengine.ui

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
  val client=incoming.optString("clientMessageId");val status=incoming.optString("status");if(client.isBlank()||status !in setOf("accepted","queued","unconfirmed","consumed","withdrawn","rejected"))return null
  if(status!="unconfirmed"&&(incoming.optString("messageId").isBlank()||incoming.optString("conversationId").isBlank()||incoming.optString("taskId").isBlank()||incoming.optString("runId").isBlank()||incoming.optInt("epoch")<1))return null
  if(status=="rejected"&&(incoming.opt("notAdmitted")!=true||incoming.opt("reconciliationRequired")!=false||incoming.optString("reason") !in setOf("mailbox_closed","capacity_reached")))return null
  if(status=="consumed"&&(incoming.optString("replyMessageId").isBlank()||incoming.optLong("consumedAt")<=0))return null
  val queueVersion=incoming.opt("queueRevision");if(incoming.has("queueRevision")&&(queueVersion !is Int&&queueVersion !is Long||incoming.optLong("queueRevision")<1))return null
  val messageVersion=incoming.opt("ownerMessageRevision");if(status=="withdrawn"&&(incoming.optLong("queueRevision")<1||messageVersion !is Int&&messageVersion !is Long||incoming.optLong("ownerMessageRevision")<1))return null
  if(old!=null){
   if(old.optString("clientMessageId")!=client)return null
   if(old.optString("status") in setOf("accepted","queued","consumed","withdrawn","rejected")){
    if(status=="unconfirmed")return old
    for(k in listOf("messageId","conversationId","taskId","runId","epoch"))if(old.opt(k)!=incoming.opt(k))return null
    if(old.optString("status")=="rejected")return old
    if(status=="rejected")return null
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
 fun canSend(state:PocketUiState,phone:Boolean,text:String):Boolean=(text.isNotBlank()||!phone&&state.selectedChatAttachments.isNotEmpty())&&text.trim().length<=12000&&!state.chatSubmitting&&(if(phone)!state.chatBusy&&state.activeTaskId==null&&!state.phoneSubmitting else !state.chatBusy||state.feature("assistant").optBoolean("sendAcknowledgements")&&state.chatActiveTurn!=null)
 /** Only the native, already selected attachments can supply a body for an empty original draft. */
 fun submittedText(text:String,files:List<UiChatAttachment>):String=if(text.isBlank()&&files.isNotEmpty())"请查看附件。" else text.trim()
}
