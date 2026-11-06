package dev.pocketengine.ui.web

import android.os.SystemClock
import dev.pocketengine.ui.PocketWorldController
import java.util.UUID
import org.json.JSONObject

/** Original live/recovered dictation reviews; no new voice session or independent composer state. */
internal class PocketWebVoiceInput(
    private val controller: PocketWorldController,
    private val phoneComposer: PocketWebPhoneComposer
) {
    private data class VoiceReview(val inputId: String, val phone: Boolean, val recovered: Boolean, val key: String, val draftRevision: Long, val epoch: Int, val phoneRevision: Long, val expires: Long)
    private val voiceReviews = mutableMapOf<String, VoiceReview>()

    companion object {
        val COMMANDS: Set<String> = setOf("voice.start", "voice.stop", "voice.review", "voice.accept", "voice.discard")
    }

    fun clear() = voiceReviews.clear()

    fun dispatch(action: String, args: JSONObject): JSONObject? {
        when (action) {
            "voice.start" -> { require(args.optBoolean("phone") == phoneComposer.intent) { "原输入方式已变化" }; controller.startSystemVoiceInput(phoneComposer.intent) }
            "voice.stop" -> controller.stopListening()
            "voice.review" -> {
                val phone = requiredBoolean(args, "phone"); require(phone == phoneComposer.intent) { "原输入方式已变化" }
                val draft = controller.composerDraft
                val current = controller.state.voiceInput
                val live = controller.canReviewVoiceDraft(phone)
                val recovered = !phone && !live && !draft?.voiceText.isNullOrBlank()
                require(live || recovered) { "没有属于原输入的可核对听写文字" }
                val input = if (recovered) draft!!.voiceInputId else current.optString("inputId")
                val text = if (recovered) draft!!.voiceText else current.optString("finalText")
                require(input.isNotBlank() && text.isNotBlank()) { "原听写文字尚未准备" }
                voiceReviews.clear(); val handle = UUID.randomUUID().toString()
                voiceReviews[handle] = VoiceReview(input, phone, recovered, draft?.key ?: "", draft?.revision ?: 0L, controller.composerViewEpoch, phoneComposer.draftRevision, SystemClock.elapsedRealtime() + 120_000)
                return JSONObject().put("reviewHandle", handle).put("inputId", input).put("text", text)
                    .put("phone", phone).put("recovered", recovered).put("expiresInMs", 120000)
            }
            "voice.accept", "voice.discard" -> {
                val reviewed = voiceReviews.remove(requiredString(args, "reviewHandle")) ?: error("原听写审阅已关闭或已使用")
                require(SystemClock.elapsedRealtime() < reviewed.expires && reviewed.phone == phoneComposer.intent && reviewed.epoch == controller.composerViewEpoch) { "原听写输入窗口已变化" }
                val draft = controller.composerDraft
                if (reviewed.phone) require(phoneComposer.draftRevision == reviewed.phoneRevision) { "手机目标草稿已变化，请重新核对听写" }
                else require(draft != null && draft.key == reviewed.key && draft.revision == reviewed.draftRevision) { "原私聊草稿已变化，请重新核对听写" }
                if (reviewed.recovered) {
                    require(draft?.voiceInputId == reviewed.inputId) { "原保留听写已变化" }
                    if (action == "voice.discard") controller.discardRecoveredComposerVoice(reviewed.key, reviewed.draftRevision)
                    else controller.useRecoveredComposerVoice(reviewed.key, reviewed.draftRevision, requiredString(args, "text"))
                } else {
                    require(controller.state.voiceInput.optString("inputId") == reviewed.inputId && controller.canReviewVoiceDraft(reviewed.phone)) { "原听写结果已变化" }
                    if (action == "voice.discard") controller.discardVoiceDraft(reviewed.inputId)
                    else {
                        val accepted = controller.useVoiceDraft(reviewed.inputId, requiredString(args, "text"), reviewed.phone) ?: error("听写文字未被原输入接受")
                        if (reviewed.phone) phoneComposer.appendVoice(accepted)
                        else {
                            val original = controller.composerDraft ?: error("原私聊草稿已关闭")
                            controller.editComposerText(reviewed.key, reviewed.epoch, if (original.text.isBlank()) accepted else original.text + "\n" + accepted)
                        }
                    }
                }
            }
            else -> error("这项操作没有已接入的宿主实现")
        }
        return null
    }

    private fun requiredString(args: JSONObject, name: String, empty: Boolean = false): String {
        val value = args.opt(name); require(value is String && (empty || value.isNotBlank())) { "缺少有效参数 $name" }; return value
    }
    private fun requiredBoolean(args: JSONObject, name: String): Boolean {
        val value = args.opt(name); require(value is Boolean) { "缺少有效开关参数 $name" }; return value
    }
}
