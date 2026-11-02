package dev.pocketengine.ui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Pure text projection of the native path, not an Android UI/connection acceptance test. */
class DeviceConnectionPathDetailTest {
    private fun peer(state: String, path: String) = JSONObject().put("connection", JSONObject().put("state", state).put("path", path))

    @Test fun onlineDetailsDistinguishActualWssAndDirectTlsPaths() {
        assertTrue(deviceConnectionDetail(peer("ONLINE", "RELAY_WSS")).contains("出站 WSS 中继"))
        assertFalse(deviceConnectionDetail(peer("ONLINE", "RELAY_WSS")).contains("直连"))
        assertTrue(deviceConnectionDetail(peer("DEGRADED", "DIRECT_TLS")).contains("直连 TLS"))
        assertFalse(deviceConnectionDetail(peer("DEGRADED", "DIRECT_TLS")).contains("中继"))
    }

    @Test fun unknownOrOfflineFactsNeverBecomeAClaimThatTheConfiguredRelayIsOnline() {
        val unknown = deviceConnectionDetail(peer("ONLINE", "UNKNOWN"))
        assertTrue(unknown.contains("路径尚未核对")); assertFalse(unknown.contains("中继"))
        val offline = deviceConnectionDetail(peer("OFFLINE", "RELAY_WSS"))
        assertTrue(offline.contains("离线")); assertFalse(offline.contains("通过"))
    }
}
