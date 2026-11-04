package dev.pocketengine.ui
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
class ChatInputRulesTest {
 private fun receipt(status:String="queued",client:String="client")=JSONObject().put("clientMessageId",client).put("status",status).put("messageId","message").put("conversationId","conversation").put("taskId","task").put("runId","run").put("epoch",1)
 @Test fun busyNativeTurnCanReceiveInputButPhoneAndUnboundOrPendingCannot(){val features=JSONObject().put("assistant",JSONObject().put("sendAcknowledgements",true));val busy=PocketUiState(features=features,chatBusy=true,chatActiveTurn=JSONObject().put("taskId","task"));assertTrue(ChatInputRules.canSend(busy,false,"追加输入"));assertFalse(ChatInputRules.canSend(busy,true,"手机目标"));assertFalse(ChatInputRules.canSend(busy.copy(chatActiveTurn=null),false,"追加"));assertFalse(ChatInputRules.canSend(busy.copy(chatSubmitting=true),false,"追加"));assertFalse(ChatInputRules.canSend(busy,false,"x".repeat(12001)))}
 @Test fun knownReceiptCannotRegressOrRebindAndUnknownDoesNotInventTask(){val known=receipt();assertSame(known,ChatInputRules.mergeReceipt(known,receipt("unconfirmed")));assertNull(ChatInputRules.mergeReceipt(known,receipt().put("runId","another")));assertNull(ChatInputRules.mergeReceipt(known,receipt(client="another")));val unknown=ChatInputRules.mergeReceipt(null,JSONObject().put("clientMessageId","client").put("status","unconfirmed"))!!;assertFalse(unknown.has("taskId"));assertNotNull(ChatInputRules.mergeReceipt(unknown,known))}
 @Test fun durableOriginalTargetMustMatchBeforeTheFirstAcknowledgementCanClearADraft(){
  val original=JSONObject().put("taskId","task").put("runId","run").put("epoch",1L)
  assertTrue(ChatInputRules.receiptMatchesOriginal(receipt(),"conversation",original))
  assertTrue(ChatInputRules.receiptMatchesOriginal(JSONObject().put("status","unconfirmed"),"conversation",original))
  assertFalse(ChatInputRules.receiptMatchesOriginal(receipt().put("taskId","foreground"),"conversation",original))
  assertFalse(ChatInputRules.receiptMatchesOriginal(receipt().put("runId","later-run"),"conversation",original))
  assertFalse(ChatInputRules.receiptMatchesOriginal(receipt().put("epoch","1"),"conversation",original))
  assertFalse(ChatInputRules.receiptMatchesOriginal(receipt().put("conversationId","another"),"conversation",original))
 }
 @Test fun selectionUsesOnlyExactIdDigestAndDoesNotTreatAnImportAsSelection(){val id="11111111-1111-1111-1111-111111111111";val row=JSONObject().put("id",id).put("sha256","a".repeat(64)).put("name","photo.png").put("mimeType","image/png").put("size",70).put("uri","content://not-a-request");val file=ChatInputRules.attachment(row)!!;assertEquals(2,file.selection().length());assertFalse(file.selection().has("uri"));assertTrue(PocketUiState().selectedChatAttachments.isEmpty());assertNotEquals(ChatInputRules.fingerprint("message","c","queue",listOf(file)),ChatInputRules.fingerprint("message","c","queue",listOf(file.copy(sha256="b".repeat(64)))))}
 @Test fun nativeSourceRangesAffectPendingFingerprintAndEmptyRefsPreserveLegacyIdentity(){
  val legacy=dev.pocketengine.store.TaskStore.capabilityFingerprint(JSONObject().put("text","message").put("conversation","c").put("mode","queue").put("attachments",org.json.JSONArray()))
  assertEquals(legacy,ChatInputRules.fingerprint("message","c","queue",emptyList()))
  assertEquals(legacy,ChatInputRules.fingerprint("message","c","queue",emptyList(),org.json.JSONArray()))
  val source=JSONObject().put("generation","actual-generation").put("sourceConversationId","original-conversation").put("sourceRef",JSONObject().put("kind","message").put("sourceId","original-message").put("revision",4294967299L).put("ownerId","owner").put("spaceId","private")).put("bodyFingerprint","sha256:"+"a".repeat(64)).put("offset",0).put("chars",10).put("unit","UTF-16")
  val refs=org.json.JSONArray().put(source);val with=ChatInputRules.fingerprint("message","c","queue",emptyList(),refs)
  assertNotEquals(legacy,with);source.put("offset",20);assertNotEquals(with,ChatInputRules.fingerprint("message","c","queue",emptyList(),refs))
 }
 @Test fun previewSlotChangesPendingIdentityWithoutReplacingQuoteOrAttachmentIdentity(){
  val empty=org.json.JSONArray();val legacy=ChatInputRules.fingerprint("message","c","queue",emptyList(),empty)
  assertEquals(legacy,ChatInputRules.fingerprint("message","c","queue",emptyList(),empty,empty))
  val ref=JSONObject().put("referenceId","reference").put("taskId","task").put("runId","run").put("epoch",1).put("conversationId","c").put("projectId","project").put("checkoutId","checkout")
   .put("previewId","a".repeat(64)).put("sourceDigest","b".repeat(64)).put("documentId","document").put("documentGeneration",1).put("path","index.html").put("resourceSha256","c".repeat(64))
   .put("selectorHint","button > span").put("text","Selected element").put("htmlSnippet","<span>Selected element</span>")
   .put("bounds",JSONObject().put("x",1).put("y",2).put("width",100).put("height",20)).put("truncated",JSONObject().put("selectorHint",false).put("text",false).put("htmlSnippet",false)).put("untrusted",true).put("selectorIsSourceLocation",false)
  val refs=org.json.JSONArray().put(ref);val first=ChatInputRules.fingerprint("message","c","queue",emptyList(),empty,refs)
  assertNotEquals(legacy,first);ref.put("documentGeneration",2);assertNotEquals(first,ChatInputRules.fingerprint("message","c","queue",emptyList(),empty,refs))
  val file=UiChatAttachment("11111111-1111-1111-1111-111111111111","d".repeat(64),"file.txt","text/plain",4)
  assertNotEquals(ChatInputRules.fingerprint("message","c","queue",listOf(file),empty,refs),ChatInputRules.fingerprint("message","c","queue",emptyList(),empty,refs))
 }
}
