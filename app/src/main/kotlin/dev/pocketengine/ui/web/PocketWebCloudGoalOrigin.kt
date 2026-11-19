package dev.pocketengine.ui.web

import android.os.SystemClock
import dev.pocketengine.ui.PocketWorldController
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** One ephemeral pointer to an existing native private composer. Never sent to Companion, never a task grant. */
internal class PocketWebCloudGoalOrigin(private val controller: PocketWorldController) {
    internal data class Pin(val handle: String, val conversation: String, val key: String, val epoch: Int, val digest: String, val expires: Long)
    @Volatile private var captured: Pin? = null
    private fun digest(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString(""){ "%02x".format(it.toInt() and 255) }
    private fun current(pin: Pin) = captured === pin && SystemClock.elapsedRealtime() < pin.expires &&
        controller.webPrivateOwnerVisible() && controller.state.conversationId == pin.conversation && controller.composerDraft?.conversationId == pin.conversation &&
        controller.composerDraft?.key == pin.key && controller.composerViewEpoch == pin.epoch
    fun capture(args: JSONObject): JSONObject {
        require(args.keys().asSequence().toSet() == setOf("key","epoch","goal"))
        captured = null
        val draft = controller.composerDraft ?: return JSONObject().put("linked",false)
        val goal = args.opt("goal"); val id = controller.state.conversationId
        require(goal is String && goal.isNotBlank() && goal.length <= 8000 && '\u0000' !in goal)
        require(args.opt("key") == draft.key && args.opt("epoch") is Number && args.optInt("epoch",-1) == controller.composerViewEpoch)
        // A new/changed draft still works as an independent host goal; don't manufacture a conversation.
        val assistant=controller.nativeButlerHost().features.module("assistant") as? dev.pocketengine.features.assistant.AssistantModule
        if(id.isNullOrBlank() || draft.conversationId != id || draft.text.trim() != goal || assistant?.canKeepPrivateHostReceipt()!=true)
            return JSONObject().put("linked",false)
        val p = Pin(UUID.randomUUID().toString(),id,draft.key,controller.composerViewEpoch,digest(goal),SystemClock.elapsedRealtime()+15*60_000)
        captured=p
        return JSONObject().put("linked",true).put("originHandle",p.handle).put("executionAuthorized",false).put("networkUsed",false)
    }
    fun review(handle: String, input: JSONObject): Pin {
        val p = captured ?: error("原对话来源已关闭；请重新带入目标或改为独立委托")
        require(handle == p.handle && current(p) && digest(input.getString("prompt").trim()) == p.digest && input.opt("worker") != "command") {
            "原私人对话或目标已变化，请重新带入或明确改为独立委托"
        }
        return p
    }
    fun confirm(pin: Pin,input: JSONObject) {
        require(review(pin.handle,input) === pin)
        val seal=controller.nativeButlerHost().features.bindReviewedHostGoalForOwner(pin.conversation,input)
        input.put(dev.pocketengine.features.remote.PrivateHostGoalLink.LOCAL_ONLY,seal)
        captured=null
    }
}
