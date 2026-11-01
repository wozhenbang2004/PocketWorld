package dev.pocketengine.ui
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class ChannelReceivingUiStateTest {
 private fun profile(id: String="qq-a",provider: String="qq") = JSONObject().put("id",id).put("name",id).put("provider",provider).put("enabled",true).put("hasSecret",true).put("updatedAt",12L)
 private fun channels(vararg profiles: JSONObject) = JSONObject().put("profiles",JSONArray(profiles.toList()))
 @Test fun selectionReviewIsOrderIndependentAndChangesWithActualPublicConfiguration() {
  val a=profile();val b=profile("wx-a","weixin");val source=channels(a,b)
  assertEquals(reviewChannelReceiving(source,listOf("qq-a","wx-a")).fingerprint,reviewChannelReceiving(source,listOf("wx-a","qq-a")).fingerprint)
  val previous=reviewChannelReceiving(source,listOf("qq-a"));a.put("name","changed")
  assertNotEquals(previous.fingerprint,reviewChannelReceiving(source,listOf("qq-a")).fingerprint)
 }
 @Test fun selectionCannotBorrowRelayDisabledMissingCredentialsOrDuplicateIdentity() {
  assertThrows(IllegalArgumentException::class.java) { reviewChannelReceiving(channels(profile("relay","webhook")),listOf("relay")) }
  assertThrows(IllegalArgumentException::class.java) { reviewChannelReceiving(channels(profile().put("enabled",false)),listOf("qq-a")) }
  assertThrows(IllegalArgumentException::class.java) { reviewChannelReceiving(channels(profile().put("hasSecret",false)),listOf("qq-a")) }
  assertThrows(IllegalArgumentException::class.java) { reviewChannelReceiving(channels(profile()),listOf("qq-a","qq-a")) }
  assertThrows(IllegalStateException::class.java) { reviewChannelReceiving(channels(profile()),listOf("foreign")) }
 }
 @Test fun providerAndGlobalReceiverLimitsAreAppliedBeforeConfirmation() {
  val rows=(1..4).map { profile("qq-$it") };assertThrows(IllegalArgumentException::class.java) { reviewChannelReceiving(channels(*rows.toTypedArray()),rows.map { it.getString("id") }) }
  assertThrows(IllegalArgumentException::class.java) { reviewChannelReceiving(channels(),emptyList()) }
 }
 @Test fun requestedAndForegroundStatesNeverPretendAccountAuthentication() {
  val starting=JSONObject().put("sessionId","native-session").put("status","starting")
  assertTrue(channelReceivingCanStop(starting));assertEquals("已请求开启，尚未建立前台服务",channelReceivingStatus(starting))
  val ready=JSONObject(starting.toString()).put("status","foreground").put("foregroundActive",true)
  assertFalse(channelReceivingStatus(ready).contains("已建立"));ready.put("foregroundServicePresent",true)
  assertTrue(channelReceivingStatus(ready).contains("账号状态单独核对"))
 }
 @Test fun cleanupRemainsStoppableAndNeverUsesAStoppedOnlineLabel() {
  val cleanup=JSONObject().put("sessionId","native-session").put("status","stopped").put("cleanupPending",true).put("foregroundServicePresent",true)
  assertTrue(channelReceivingCanStop(cleanup));assertEquals("正在结束接收，清理尚未确认",channelReceivingStatus(cleanup));cleanup.put("cleanupFailed",true)
  assertTrue(channelReceivingStatus(cleanup).contains("未完全确认"));assertFalse(channelReceivingCanStop(JSONObject().put("status","stopped")))
 }
 @Test fun onlyExactSelectedProfilesBelongToCurrentForegroundSession() {
  val state=JSONObject().put("background",JSONObject().put("sessionId","current").put("status","starting").put("profiles",JSONArray().put(JSONObject().put("id","qq-a"))))
  assertTrue(channelReceivingOwns(state,"qq-a"));assertFalse(channelReceivingOwns(state,"other"));state.getJSONObject("background").put("status","stopped")
  assertFalse(channelReceivingOwns(state,"qq-a"))
 }
 @Test fun weixinSyncStatusNeedsActualMonitorAndLoginFailureIsNotOnline() {
  assertEquals("尚未开始iLink接收",weixinReceivingStatus(null));assertEquals("iLink接收已停止",weixinReceivingStatus(JSONObject().put("status","listening").put("monitorRunning",false)))
  assertTrue(weixinReceivingStatus(JSONObject().put("status","starting").put("monitorRunning",true)).contains("尚未确认"))
  assertTrue(weixinReceivingStatus(JSONObject().put("status","login_required").put("monitorRunning",false)).contains("已失效"))
 }
}
