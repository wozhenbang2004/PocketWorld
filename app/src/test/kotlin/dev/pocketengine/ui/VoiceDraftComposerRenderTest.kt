package dev.pocketengine.ui

import android.Manifest
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import dev.pocketengine.EngineApplication
import dev.pocketengine.testutil.ReadyApplicationFixture
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSpeechRecognizer

/** Production Web bridge -> original VoiceDraftInput/Composer; only System-ASR callbacks use SDK shadows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = EngineApplication::class, shadows = [ReadyApplicationFixture.NoUnrelatedStartup::class])
@LooperMode(LooperMode.Mode.PAUSED)
class VoiceDraftComposerRenderTest {
    private fun listening(f: OwnerWebTestFixture) {
        f.await { f.controller.composerDraft != null }
        val service = ResolveInfo().also { it.serviceInfo = ServiceInfo().also { info -> info.packageName = "fixture.system.speech"; info.name = "Recognition" } }
        shadowOf(f.app.packageManager).addResolveInfoForIntent(Intent(RecognitionService.SERVICE_INTERFACE), service); shadowOf(f.app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        f.call("voice.start", JSONObject().put("phone", false)); f.await { f.controller.state.voiceInput.optBoolean("active") }
    }
    private fun final(f: OwnerWebTestFixture, text: String): JSONObject {
        shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()).triggerOnResults(Bundle().also { it.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text)) })
        shadowOf(Looper.getMainLooper()).idle(); f.await { f.controller.state.voiceInput.optBoolean("reviewAvailable") }
        return f.call("voice.review", JSONObject().put("phone", false))
    }
    @Test fun newConversationReleasesStagedMicFenceAndKeepsUnsentOriginalDraft() {
        OwnerWebTestFixture().use { f ->
            listening(f); val text = "这份未发送文字保留在原编辑器，我仍要自己决定是否发送。"; val reviewed = final(f, text)
            val originalId = f.controller.state.voiceInput.getString("inputId")
            f.call("voice.accept", JSONObject().put("reviewHandle", reviewed.getString("reviewHandle")).put("text", text))
            assertTrue(f.controller.state.voiceInput.optBoolean("staged")); assertFalse(f.controller.state.voiceInput.optBoolean("reviewAvailable")); assertEquals(text, f.controller.composerDraft!!.text)
            f.call("conversation.new", JSONObject()); assertFalse(f.controller.state.voiceInput.optBoolean("staged")); assertEquals(0, f.app.tasks.list().length())
            assertEquals(text, f.controller.composerDraft!!.text)
            listening(f); assertEquals("listening", f.controller.state.voiceInput.getString("phase")); assertNotEquals(originalId, f.controller.state.voiceInput.getString("inputId")); assertEquals(0, f.app.tasks.list().length())
        }
    }
    @Test fun explicitSystemFinalCanBeEditedIntoOriginalComposerWithoutAutomaticSend() {
        OwnerWebTestFixture().use { f ->
            listening(f); val original = "把周三下午三点的项目评审整理为一份待我确认的安排，识别中的姓名和数字需要再核对。"; val reviewed = final(f, original)
            assertEquals(original, reviewed.getString("text")); assertEquals(0, f.app.tasks.list().length())
            val edited = "整理周四下午四点的项目评审安排，任何对外发送都先让我确认。"
            f.call("voice.accept", JSONObject().put("reviewHandle", reviewed.getString("reviewHandle")).put("text", edited))
            assertFalse(f.controller.state.voiceInput.optBoolean("reviewAvailable")); assertEquals(edited, f.controller.composerDraft!!.text); assertTrue(f.controller.state.voiceInput.optBoolean("staged")); assertEquals(0, f.app.tasks.list().length())
            assertFalse(f.envelope("voice.accept", JSONObject().put("reviewHandle", reviewed.getString("reviewHandle")).put("text", "stale replacement")).getBoolean("ok"))
            assertEquals(edited, f.controller.composerDraft!!.text); assertEquals(0, f.app.tasks.list().length())
        }
    }
}
