package dev.pocketengine.ui

import dev.pocketengine.store.TaskStore
import dev.pocketengine.features.assistant.AttachmentRequestProjection
import dev.pocketengine.features.attachments.AttachmentBook
import dev.pocketengine.features.proxy.providers.ResponsesRequest
import dev.pocketengine.core.ModelConfig
import dev.pocketengine.runtime.ModelGateway
import org.json.JSONArray
import org.json.JSONObject

internal data class UiChatAttachment(val id:String,val sha256:String,val name:String,val mimeType:String,val size:Long){fun selection():JSONObject=JSONObject().put("id",id).put("sha256",sha256)}
internal object ChatInputRules {
 private val inlinePhotoTypes=setOf("image/png","image/jpeg","image/webp")
 private fun inlinePhoto(file:UiChatAttachment)=file.mimeType in inlinePhotoTypes && file.size in 1L..AttachmentRequestProjection.MAX_IMAGE_BYTES.toLong()
 private fun encodedPhotoBytes(file:UiChatAttachment)=4L*((file.size+2L)/3L)

 class PhotoAllowance(private val encodedLeft:Long,private val accountLeft:Int?) {
  fun rejectAdding(files:List<UiChatAttachment>, libraryFallback:Boolean=false):String? {
   val photos=files.filter(ChatInputRules::inlinePhoto)
   if(accountLeft!=null&&photos.size>accountLeft)
    return if(!libraryFallback) "当前账号每条最多加入 ${ResponsesRequest.MAX_IMAGES} 张图片。移除一张或分开发送。"
     else if(accountLeft==0) "这条草稿的图片名额已满。可移除已有图片，再从清单勾选。"
     else "按当前账号的图片数量上限，最多还能勾选 $accountLeft 张。请从清单选择，回输入框核对。"
   if(photos.sumOf(ChatInputRules::encodedPhotoBytes)>encodedLeft)
    return if(libraryFallback) "这批原图超过本条容量。请从清单少选几张，或选择较小的版本再核对。"
     else "加上这些原图会超过本条发送容量。可减少图片、改用较小版本，或分开发送。"
   return null
  }

  fun forPicker():JSONObject=JSONObject().put("nextPhotoBytes",encodedLeft/4L*3L)
   .put("accountSlots",accountLeft).put("accountMax",if(accountLeft==null)null else ResponsesRequest.MAX_IMAGES)
 }

 /** Only definite ceilings on this native inline route, not a send/readiness or provider claim. */
 fun photoAllowance(model:JSONObject,held:List<UiChatAttachment>):PhotoAllowance? {
  if(model.opt("supportsImageInput")!=true||model.optString("model").isBlank())return null
  val plan=model.optJSONObject("modelRoutePlan")
  if(plan!=null&&(plan.optLong("groupRevision",-1)!=0L||plan.optInt("maxRouteSwitches",-1)!=0||
      plan.optJSONArray("orderedCandidates")?.length()!=1))return null
  val accountLimit=when(model.optString("modelRoute")){
   "account" -> {
    if(model.opt("sendScreenshots")!=true)return null
    val ref=runCatching { ModelConfig.AccountModelRef.fromJson(model.getJSONObject("nativeRef")) }.getOrNull() ?: return null
    if(ref.modelId!=model.optString("model")||!ModelGateway.accountSupportsImageInput(ref))return null
    ResponsesRequest.MAX_IMAGES
   }
   "api_key" -> {
    if(model.opt("hasApiKey")!=true&&!(model.optString("credentialKind")=="aws_sts_session"&&
       model.opt("hasAwsSession")==true&&model.optString("credentialStatus")=="saved_unverified"))return null
    null
   }
   else -> return null
  }
  val photos=held.filter(::inlinePhoto)
  // Inline photo parts always have JSON overhead. Use only the proven Base64 lower bound.
  val left=(AttachmentRequestProjection.MAX_REQUEST_ATTACHMENT_BYTES-1L-photos.sumOf(::encodedPhotoBytes)).coerceAtLeast(0L)
  return PhotoAllowance(left,accountLimit?.let{(it-photos.size).coerceAtLeast(0)})
 }

 fun attachment(row:JSONObject):UiChatAttachment?=try{val id=row.getString("id");val sha=row.getString("sha256");if(!id.matches(Regex("[a-fA-F0-9-]{36}"))||!sha.matches(Regex("[a-f0-9]{64}")))null else UiChatAttachment(id,sha,row.optString("name","附件"),row.getString("mimeType"),row.getLong("size"))}catch(_:Exception){null}
 /** Same definite exclusions as the ordinary picker. Draft staging reads no file bytes;
  * AttachmentRequestProjection still verifies the model, contents and aggregate budget on Send. */
 fun pickedFileLimit(file:UiChatAttachment,model:JSONObject):String? = when {
  file.size !in 1L..AttachmentBook.MAX_FILE_BYTES -> "空文件或超过20 MiB的文件不能加入这条消息。"
  file.mimeType.startsWith("image/") && file.mimeType !in inlinePhotoTypes -> "普通对话只接收 PNG、JPEG 或 WebP 图片。"
  file.mimeType.startsWith("image/") && file.size > AttachmentRequestProjection.MAX_IMAGE_BYTES -> "图片超过4 MiB，请选较小的版本。"
  file.mimeType.startsWith("image/") && (model.optString("modelRoute")=="account" && model.opt("sendScreenshots")==false || model.opt("supportsImageInput")!=true) ->
    "当前模型或账号连接没有确认接收图片，可从输入框核对。"
  (file.mimeType.startsWith("audio/") || file.mimeType.startsWith("video/")) && file.size > AttachmentRequestProjection.MAX_DOCUMENT_BYTES ->
    "普通对话无法分段读取音视频，可以改附文字稿或截图。"
  else -> null
 }
 fun fingerprint(text:String,conversation:String?,mode:String,files:List<UiChatAttachment>,sourceRefs:JSONArray=JSONArray(),previewRefs:JSONArray=JSONArray(),objectRefs:JSONArray=JSONArray(),firstPaused:Boolean=false,excludeThis:Boolean=false,lockDefaults:Boolean=false):String {
  val checked=dev.pocketengine.features.assistant.OwnerSourceReferences.checked(sourceRefs)
  val previews=dev.pocketengine.features.local.preview.PreviewElementRef.checked(previewRefs)
  val input=JSONObject().put("text",text).put("conversation",conversation?:"").put("mode",mode).put("attachments",JSONArray().also{a->files.forEach{a.put(it.selection())}})
  // Missing/empty locators preserve pre-reference pending identities exactly.
  if(checked.length()>0)input.put("ownerSourceRefs",checked)
  if(previews.length()>0)input.put("ownerPreviewRefs",previews)
  val objects=dev.pocketengine.features.assistant.OwnerObjectReferences.checked(objectRefs)
  if(objects.length()>0)input.put("ownerObjectRefs",objects)
  if(firstPaused)input.put("firstMessageWithoutPersonalMemory",true)
  if(excludeThis)input.put("excludeThisInputFromLearning",true)
  if(lockDefaults){require(excludeThis&&conversation.isNullOrEmpty());input.put("firstTaskWithoutDefaultWrites",true)}
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
