package dev.pocketengine.ui

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class RealtimeUiStateTest {
    @Test fun joinedEmptyRoomDoesNotClaimAiPresence() {
        val value=JSONObject().put("session",JSONObject().put("state","connected").put("remoteParticipants",0))
        assertEquals("已加入房间，等待对端媒体",realtimeLabel(value))
    }
    @Test fun subscribedVideoIsLabelledMediaNotVerifiedHuman() {
        val video=JSONObject().put("trackSid","synthetic-track").put("origin","unverified_remote")
        assertTrue(videoOriginLabel(video).contains("来源未核验"))
        val value=JSONObject().put("session",JSONObject().put("state","connected").put("remoteVideos",JSONArray().put(video)))
        assertEquals("媒体已订阅 · 远端身份见下方来源",realtimeLabel(value))
    }
    @Test fun agentRoleRemainsServerDeclarationNotVerifiedPerson() {
        assertEquals("远端 AI 服务视频（房间服务端标记，非真人验证）",videoOriginLabel(JSONObject().put("origin","server_declared_agent")))
    }
    @Test fun waitingInvitationAndConnectingRemainDistinct() {
        assertEquals("等待你接受或拒绝",realtimeLabel(JSONObject().put("invitationState","awaiting_owner")))
        assertEquals("正在接受并连接，随时可取消",realtimeLabel(JSONObject().put("accepting",true)))
    }
    @Test fun configuredEndpointWithoutManualTokenDoesNotClaimMissingServiceOrAiPresence() {
        assertEquals("服务已配置，尚未发起通话",realtimeLabel(JSONObject().put("providerConfigured",true).put("configured",false)))
    }
    private fun transcriptFixture(): Pair<JSONObject, JSONObject> {
        val row=JSONObject().put("id","turn").put("revision",2L).put("isFinal",true).put("canReview",true).put("text","安排任务")
        val realtime=JSONObject().put("sessionId","session").put("transcription",JSONObject().put("active",true).put("commitAvailable",true).put("turns",JSONArray().put(row)))
        val review=JSONObject().put("sessionId","session").put("turnId","turn").put("revision",2L).put("text","安排任务").put("expiresAt",System.currentTimeMillis()+60_000)
        return realtime to review
    }
    @Test fun exactFinalReviewIsUsableButPartialAndRevisedTextAreNot() {
        val (realtime,review)=transcriptFixture();assertTrue(realtimeReviewCurrent(realtime,review))
        val row=realtime.getJSONObject("transcription").getJSONArray("turns").getJSONObject(0)
        row.put("isFinal",false);assertFalse(realtimeReviewCurrent(realtime,review));row.put("isFinal",true).put("revision",3L);assertFalse(realtimeReviewCurrent(realtime,review));row.put("revision",2L).put("text","另一个目标");assertFalse(realtimeReviewCurrent(realtime,review))
    }
    @Test fun endedCallUnavailableStoreOrExpiredReviewDisableConfirmation() {
        val (realtime,review)=transcriptFixture();realtime.put("sessionId","new");assertFalse(realtimeReviewCurrent(realtime,review));realtime.put("sessionId","session");realtime.getJSONObject("transcription").put("active",false);assertFalse(realtimeReviewCurrent(realtime,review));realtime.getJSONObject("transcription").put("active",true).put("commitAvailable",false);assertFalse(realtimeReviewCurrent(realtime,review));realtime.getJSONObject("transcription").put("commitAvailable",true);review.put("expiresAt",0);assertFalse(realtimeReviewCurrent(realtime,review))
    }
    @Test fun retainedFinalRequiresCurrentReviewAndFinishedMediaHandoff() {
        val (realtime,review)=transcriptFixture()
        val transcript=realtime.getJSONObject("transcription").put("active",false).put("retained",true).put("reviewReady",true)
        assertTrue(realtimeReviewCurrent(realtime,review))
        transcript.put("reviewReady",false);assertFalse(realtimeReviewCurrent(realtime,review));transcript.put("reviewReady",true)
        transcript.getJSONArray("turns").getJSONObject(0).put("canReview",false);assertFalse(realtimeReviewCurrent(realtime,review))
    }
    @Test fun transcriptLabelsDoNotEquateInterimRemoteOrPersistenceWithExecution() {
        assertEquals("临时转录，不会执行",realtimeTurnLabel(JSONObject().put("isFinal",false)))
        assertEquals("远端最终字幕，只供查看",realtimeTurnLabel(JSONObject().put("isFinal",true).put("sourceKind","remote_or_unverified")))
        assertTrue(realtimeTurnLabel(JSONObject().put("commitState","committing")).contains("不是执行"))
        assertTrue(realtimeTurnLabel(JSONObject().put("commitState","unknown")).contains("原提交"))
    }
    @Test fun voiceLabelsNeverClaimAlwaysOnOrFullDuplex() {
        assertEquals("尚未开始，不在录音",voiceTurnLabel(JSONObject()))
        assertEquals("正在等待模型回复",voiceTurnLabel(JSONObject().put("status","thinking")))
        assertEquals("这一轮已结束 · 麦克风已停止",voiceTurnLabel(JSONObject().put("status","complete")))
    }
    @Test fun voiceStartRequiresOwnPortForegroundPermissionAndNoAudioConflict() {
        val turn=JSONObject().put("portReady",true)
        val value=JSONObject().put("voiceTurn",turn).put("foregroundOwner",true).put("captureCoordinatorReady",true).put("microphonePermission",true)
        assertTrue(voiceTurnCanStart(value,false));assertFalse(voiceTurnCanStart(value,true))
        value.put("microphonePermission",false);assertFalse(voiceTurnCanStart(value,false));value.put("microphonePermission",true)
        turn.put("active",true);assertFalse(voiceTurnCanStart(value,false));turn.put("active",false).put("resourceReleaseUnknown",true);assertFalse(voiceTurnCanStart(value,false));turn.put("resourceReleaseUnknown",false)
        value.put("foregroundOwner",false);assertFalse(voiceTurnCanStart(value,false));value.put("foregroundOwner",true).put("audioLeaseHeld",true);assertFalse(voiceTurnCanStart(value,false))
    }

}
