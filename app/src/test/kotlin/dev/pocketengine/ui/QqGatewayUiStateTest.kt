package dev.pocketengine.ui
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class QqGatewayUiStateTest {
 private fun profile(id: String="qq-a", enabled: Boolean=true) = JSONObject().put("id",id).put("provider","qq").put("enabled",enabled)
 private fun channels(id: String="qq-a", status: String="starting", monitor: Boolean=true) = JSONObject().put("capabilities",JSONObject().put("qqGateway",true)).put("qq",JSONObject().put("connections",JSONArray().put(JSONObject().put("profileId",id).put("status",status).put("monitorRunning",monitor))))
 @Test fun onlyActualProfileBoundMonitorOffersStopAndStartingIsNotOnline() {
  val state=channels(); assertEquals("qq.stop",qqGatewayCommand(state,profile()));assertEquals("qq.start",qqGatewayCommand(state,profile("qq-b")));assertTrue(qqGatewayStatus(qqGatewayConnection(state,"qq-a")).contains("尚未确认在线"));assertEquals("尚未开始接收",qqGatewayStatus(qqGatewayConnection(state,"foreign")))
 }
 @Test fun listeningRequiresActualRunningMonitorAndEnablementDoesNotImplyOnline() {
  assertEquals("官方网关已认证，正在接收",qqGatewayStatus(qqGatewayConnection(channels(status="listening"),"qq-a")));assertEquals("接收已停止",qqGatewayStatus(qqGatewayConnection(channels(status="listening",monitor=false),"qq-a")));assertEquals("qq.start",qqGatewayCommand(channels(monitor=false),profile()));assertNull(qqGatewayCommand(channels(monitor=false),profile(enabled=false)));assertEquals("qq.stop",qqGatewayCommand(channels(),profile(enabled=false)))
 }
 @Test fun unsupportedCapabilityAndOtherProviderNeverBorrowGateway() {
  assertNull(qqGatewayCommand(JSONObject(),profile()));assertNull(qqGatewayCommand(channels(),profile().put("provider","weixin")));assertNull(qqGatewayCommand(channels().put("capabilities",JSONObject().put("qqGateway",false)),profile()))
 }
}
