package dev.pocketengine.ui.web

import android.os.SystemClock
import dev.pocketengine.features.assistant.AssistantModule
import dev.pocketengine.features.remote.RemoteModule
import dev.pocketengine.features.remote.RemoteTransport
import dev.pocketengine.store.TaskStore
import dev.pocketengine.ui.PocketWorldController
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** One ephemeral pointer to an existing native private composer. Never sent to Companion, never a task grant. */
internal class PocketWebCloudGoalOrigin(private val controller: PocketWorldController) {
    internal data class Saved(val source: AssistantModule.SavedFirstHostSource, val profile: String, val binding: String, val service: String, val current: () -> Boolean)
    internal data class Pin(val handle: String, val conversation: String, val key: String?, val epoch: Int, val digest: String, val expires: Long, val saved: Saved? = null)
    private fun assistant() = controller.nativeButlerHost().features.module("assistant") as? AssistantModule ?: error("原私人记录不可读")
    private fun remote() = controller.nativeButlerHost().features.module("remote") as? RemoteModule ?: error("没有已核对的主机连接")
    @Volatile private var captured: Pin? = null
    private fun digest(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString(""){ "%02x".format(it.toInt() and 255) }
    private fun current(pin: Pin) = captured === pin && SystemClock.elapsedRealtime() < pin.expires &&
        controller.webPrivateOwnerVisible() && controller.state.conversationId == pin.conversation &&
        (pin.saved?.let { it.current() } ?: (controller.composerDraft?.conversationId == pin.conversation &&
            controller.composerDraft?.key == pin.key && controller.composerViewEpoch == pin.epoch))
    fun closeSaved(){if(captured?.saved!=null)captured=null}
    fun isSaved(handle:String)=captured?.let { it.handle==handle&&it.saved!=null }==true
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
    /** Only a deliberately selected, existing HTTPS Companion connection; no address check or host IO. */
    fun captureSaved(conversation: String,message: String,revision: Long,profileId: String,binding: String,
                     currentChat: () -> Boolean,sameDocument: () -> Boolean): JSONObject {
        require(currentChat()){ "原话窗口已经离开，未联系主机" }
        val native=assistant().reviewSavedFirstHostSource(conversation,message,revision)
        val profile=remote().requireProfileBinding(profileId,binding)
        val service=profile.getString("serviceBase")
        require(RemoteTransport.validateBase(service).scheme.equals("https",true)){ "这条交办只支持已有 HTTPS Companion 连接" }
        val text=native.promptForReview()
        check(currentChat()){ "原话窗口已经离开，未联系主机" }
        val saved=Saved(native,profileId,binding,service,sameDocument)
        val p=Pin(UUID.randomUUID().toString(),conversation,null,0,digest(text),SystemClock.elapsedRealtime()+15*60_000,saved)
        captured=p
        return JSONObject().put("status","host_selected_local").put("originHandle",p.handle)
            .put("profileId",profileId).put("profileBinding",binding).put("executionAuthorized",false).put("networkUsed",false)
    }
    fun prepareSavedPrompt(handle: String,input: JSONObject) {
        val p=captured ?: error("原话或原审阅窗口已关闭")
        require(handle==p.handle&&current(p)) { "原话或原审阅窗口已变化，未发送" }
        val saved=p.saved ?: return
        require(input.keys().asSequence().toSet()==setOf("profileId","bindingHash","environmentId","environmentBinding","worker","timeoutSeconds","readOnly") &&
            input.opt("profileId")==saved.profile&&input.opt("bindingHash")==saved.binding&&input.opt("worker")=="codex_app_server"&&input.opt("readOnly")==true){
            "只能使用所选主机和本机保存的只读原话，未使用编辑框或其他资料"
        }
        input.put("prompt",saved.source.promptForReview())
    }
    fun workspace(pin: Pin,input: JSONObject): JSONObject {
        val saved=requireNotNull(pin.saved)
        val profile=remote().requireProfileBinding(saved.profile,saved.binding)
        require(profile.opt("serviceBase")==saved.service&&input.opt("profileId")==saved.profile&&input.opt("_profileBinding")==saved.binding&&
            input.opt("worker")=="codex_app_server"&&input.opt("readOnly")==true&&saved.source.promptForReview()==input.opt("prompt")){
            "原话、所选主机或凭据已变化，没有改用其他执行"
        }
        val location=remote().observedWorkspaceForOwner(input) ?: error("这台主机没有核对到支持原成果的本机工作目录")
        require(location.opt("serviceBase")==saved.service) { "原工作目录不属于所选主机" }
        return location
    }
    fun review(handle: String, input: JSONObject): Pin {
        val p = captured ?: error("原对话来源已关闭；请重新带入目标或改为独立委托")
        val reviewed=input.getString("prompt").let { if(p.saved==null)it.trim() else it }
        require(handle == p.handle && current(p) && digest(reviewed) == p.digest && input.opt("worker") != "command") {
            "原私人对话或目标已变化，请重新带入或明确改为独立委托"
        }
        return p
    }
    fun confirm(pin: Pin,input: JSONObject,reviewedWorkspace: String?) {
        require(review(pin.handle,input) === pin)
        val features=controller.nativeButlerHost().features
        val seal=pin.saved?.let {
            val location=workspace(pin,input)
            require(reviewedWorkspace==TaskStore.capabilityFingerprint(location)){ "原工作目录在审阅后变化，未发送" }
            features.bindReviewedSavedHostGoalForOwner(it.source,input,location)
        } ?: features.bindReviewedHostGoalForOwner(pin.conversation,input)
        input.put(dev.pocketengine.features.remote.PrivateHostGoalLink.LOCAL_ONLY,seal)
        captured=null
    }
}
