package dev.pocketengine.ui
import org.json.*
import org.junit.Assert.*
import org.junit.Test
class ConversationReaderStateTest {
 private fun row(id:String,body:String="actual body")=JSONObject().put("id",id).put("role","assistant").put("content",body).put("revision",3L).put("at",5L)
 private fun native(vararg rows:JSONObject,more:Boolean=false,cursor:String="")=JSONObject().put("conversationId","B").put("title","B original").put("messages",JSONArray().also { a->rows.forEach(a::put) }).put("messagePage",JSONObject().put("hasMore",more).put("nextCursor",cursor))
 @Test fun readingPagePreservesActualIdentityAndUnknownImportedRevisionWithoutSelectingA() {
  val page=ConversationReadPage(1,"B","","reading-B").accept(native(row("native-one"),row("import-assigned").put("importAssignedIdentity",true)),false)
  assertEquals("B",page.conversation);assertEquals(3L,page.messages[0].sourceRevision);assertEquals(0L,page.messages[1].sourceRevision);assertFalse(page.loading);assertTrue(page.initialized);assertEquals(2,page.rowBytes.size)
 }
 @Test fun foreignDuplicateAndFalseCursorCannotBeAcceptedAsOriginalBWindow() {
  val page=ConversationReadPage(1,"B","","reading-B")
  assertThrows(IllegalArgumentException::class.java){page.accept(native(row("one")).put("conversationId","C"),false)}
  assertThrows(IllegalArgumentException::class.java){page.accept(native(row("one"),row("one")),false)}
  assertThrows(IllegalArgumentException::class.java){page.accept(native(row("one"),more=true),false)}
 }
 @Test fun olderRowsKeepStableReadingAnchorAndActualSourceMetadata() {
  var page=ConversationReadPage(1,"B","","reading-B",anchor="middle",offset=19).accept(native(row("middle"),row("last"),more=true,cursor="native-cursor"),false)
  page=page.accept(native(row("earlier"),row("middle")),true)
  assertEquals(listOf("earlier","middle","last"),page.messages.map{it.id});assertEquals("middle",page.anchor);assertEquals(1,page.locate());assertEquals(19,page.offset)
 }
 @Test fun windowBudgetStopsWithoutDiscardingAlreadyReadMessagesOrAdvancingPastThem() {
  val original=ConversationReadPage(1,"B","","reading-B").accept(native(row("kept"),more=true,cursor="keep-this-cursor"),false)
  val refused=original.accept(native(row("too-large","x".repeat(1024*1024)),more=true,cursor="must-not-advance"),true)
  assertTrue(refused.limited);assertEquals(original.messages,refused.messages);assertEquals("keep-this-cursor",refused.cursor);assertNotNull(refused.notice);assertFalse(refused.loading)
 }
}
