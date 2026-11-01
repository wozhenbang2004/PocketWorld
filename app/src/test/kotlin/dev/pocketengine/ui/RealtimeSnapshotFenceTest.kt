package dev.pocketengine.ui
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class RealtimeSnapshotFenceTest {
    private fun state(version: Long, id: String, camera: Boolean) = JSONObject().put("snapshotRevision",version).put("sessionId",id).put("cameraEnabled",camera)
    @Test fun staleBatchCannotReopenRevokedCaptureOrOldSession() {
        val stopped = state(12,"new-session",false)
        assertSame(stopped,mergeRealtimeSnapshot(stopped,state(11,"old-session",true)))
        assertSame(stopped,mergeRealtimeSnapshot(stopped,JSONObject().put("sessionId","old-session")))
    }
    @Test fun currentNativeRevisionReplacesOnlyItsPublicSnapshot() {
        val incoming = state(13,"new-session",false)
        val merged = mergeRealtimeSnapshot(state(12,"old-session",true),incoming)
        assertEquals(incoming.toString(),merged.toString());assertNotSame(incoming,merged)
    }
    @Test fun invalidVersionCannotReplaceEstablishedState() {
        val stopped = state(1,"session",false)
        assertSame(stopped,mergeRealtimeSnapshot(stopped,state(-1,"bad",true)))
        assertSame(stopped,mergeRealtimeSnapshot(stopped,JSONObject().put("snapshotRevision","2")))
    }
}
