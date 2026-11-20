package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import dev.pocketengine.features.devices.NativeDeviceWorkspaceHost as Host
import dev.pocketengine.features.devices.DeviceProtocol
import dev.pocketengine.features.devices.DirectedPhoneTarget
import dev.pocketengine.store.OwnerPhoneControlScope
import dev.pocketengine.store.TaskStoreDeviceTasks
import dev.pocketengine.ui.ReceivedFileSelection
import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.isDeviceWorkspaceTask
import dev.pocketengine.ui.objects
import dev.pocketengine.ui.workspaceTaskLinks
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Opaque native reviews stay in their original device Window. JSON is only a view/selection. */
internal class PocketWebDevices(private val controller: PocketWorldController, private val window: () -> Host.Window?, private val visible: () -> Boolean) {
    private data class Review(val handle: String, val kind: String, val native: Any, val view: JSONObject, val window: Host.Window, val generation: Long, val expires: Long)
    private data class FirstPair(val original:Host.FirstIdentityPreview,val picker:String)
    private data class TaskControl(val taskId: String, val runId: String, val epoch: Int, val pending: TaskStoreDeviceTasks.PendingGoalCancellation? = null,val pick:PendingPick?=null)
    private data class FileOffer(val key:String,val selected:ReceivedFileSelection,val until:Long)
    internal data class FileHandoff(val sourceHandle:String,val key:String,val selected:ReceivedFileSelection,val until:Long)
    private class History(val original: Host.HistoryWindow, val archiveOnly: Boolean = false) : AutoCloseable {
        val stopSubmitted = AtomicBoolean(false)
        val fileOffer=AtomicReference<FileOffer?>(null);val fileHandoff=AtomicReference<FileHandoff?>(null)
        override fun close(){fileOffer.set(null);fileHandoff.set(null);original.close()}
    }
    private val main = Handler(Looper.getMainLooper())
    private val inFlight = AtomicReference<String?>(null)
    private val controlInFlight = AtomicReference<String?>(null)
    private val generation = AtomicLong(0)
    @Volatile private var review: Review? = null
    private data class LocalHistoryPage(val handle: String, val window: Host.Window, val epoch: Long, val target: String, val after: String,
        val previous: List<String>, val next: String?, val operations: Map<String, String>)
    private data class PhoneArchive(val handle: String, val window: Host.Window, val epoch: Long, val target: String,
        val targetInstance: String, val fingerprint: String, val localId: String, val localInstance: String,
        val after: Pair<String,String>, val previous: List<Pair<String,String>>, val next: Pair<String,String>?, val digests: Map<Pair<String,String>,String>,
        val received: Boolean, val inbox: Map<Pair<String,String>, Pair<String,String>>, val search: String, val source: String,val partnerEpoch:Long,val needsAcceptanceProof:Boolean)
    @Volatile private var phoneArchive: PhoneArchive? = null
    private data class ReceiptOrigin(val task: String, val operation: String, val source: String, val here: String, val instance: String, val digest: String, val mode: String,
        val reviewRun: String = "", val reviewEpoch: Int = 0, val reviewRecordedAt: Long = 0,val activeRun:String="",val activeEpoch:Int=0,val failed:Boolean=false,val candidateState:String="")
    private data class WaitingPage(val handle: String,val window: Host.Window,val epoch: Long,val here: String,val instance: String,
        val after: Pair<String,String>,val previous: List<Pair<String,String>>,val next: Pair<String,String>?,val rows: Map<Pair<String,String>,ReceiptOrigin>,val results: Boolean,val started:Boolean,val declined:Boolean,val failed:Boolean,val withdrawn:Boolean,val search:String)
    @Volatile private var waitingPage: WaitingPage? = null
    private data class RulePage(val handle:String,val native:Host.OwnerModelRules,val window:Host.Window,val epoch:Long)
    @Volatile private var ownerRules:RulePage?=null
    // Across the local model-settings detour keep IDs only. No goal text, model selection or native Admission.
    private data class ModelPlace(val handle:String,val source:ReceiptOrigin,val issuedEpoch:Long,val until:Long)
    @Volatile private var modelPlace:ModelPlace?=null
    private data class LocalLeaf(val handle:String,val origin:ReceiptOrigin,val wire:String,val stamp:String="",val matchPart:String="",val matchPage:Int=0)
    private data class ScreenDecision(val origin:ReceiptOrigin,val proof:JSONObject)
    private data class ClosedPage(val handle:String,val window:Host.Window,val epoch:Long,val here:String,val instance:String,
        val after:Pair<String,String>,val previous:List<Pair<String,String>>,val next:Pair<String,String>?,val entries:Map<Pair<String,String>,LocalLeaf>,val search:String,val screens:Map<Pair<String,String>,ScreenDecision>)
    @Volatile private var closedPage:ClosedPage?=null
    private val historyPages = LinkedHashMap<String, LocalHistoryPage>()
    @Volatile private var latest = JSONObject()
    @Volatile private var desktop: Host.DesktopWindow? = null
    private val desktopGeneration = AtomicLong(0)
    private val desktopInFlight = AtomicReference<String?>(null)
    private val pairingImage = PocketWebPairingImage()
    companion object {
        val COMMANDS = setOf("devices.discovery.start", "devices.discovery.stop", "devices.discovery.reviewConnection", "devices.snapshot", "devices.createInvitation", "devices.inspectInvitation", "devices.readOwnerModelRules", "devices.disableOwnerModelRule", "devices.readSavedPeerScope", "devices.changeLocalPeerName", "devices.continueFirstPair", "devices.reviewPairing", "devices.advancePairing", "devices.reviewPeerGrant",
            "devices.readPendingGoalInput", "devices.reviewGrant", "devices.reviewConnection", "devices.reviewRelay", "devices.reviewAction", "devices.reviewGoal", "devices.reviewResult", "devices.reviewApproval",
            "devices.confirm", "devices.reject", "devices.discard", "devices.recoveryAddresses", "devices.openRecovery", "devices.recoverPairing", "devices.reviewAbandonment", "devices.openTask", "devices.readTask", "devices.reviewResumeRequest",
            "devices.readPhoneGoalArchive", "devices.readReceivedPhoneGoals", "devices.readLocalReviewedPhoneGoal", "devices.readReviewedPhoneInbox", "devices.readWaitingPhoneGoals", "devices.findWaitingPhoneGoals", "devices.returnToReceivedPhoneGoal", "devices.prepareReceivedModelReturn", "devices.openHistory", "devices.checkLocalExportedFile", "devices.openCheckedLocalFile", "devices.queryHistory", "devices.queryPhoneGoalResult", "devices.stopHistory",
            "devices.pairImage.pick", "devices.pairImage.review", "devices.pairImage.close",
            "devices.desktop.open", "devices.desktop.negotiate", "devices.desktop.status",
            "devices.desktop.control", "devices.desktop.input", "devices.desktop.close")
        private fun fields(value: JSONObject, vararg allowed: String) = PocketWebGroupReviews.fields(value, *allowed)
        private fun text(value: JSONObject, key: String, max: Int = 240) = PocketWebGroupReviews.text(value, key, max)
        private fun integer(value: JSONObject, key: String, min: Long, max: Long) = PocketWebGroupReviews.integer(value, key, min, max)
        private fun bool(value: JSONObject, key: String) = PocketWebGroupReviews.bool(value, key)
        private fun strings(value: JSONArray?): List<String> = if (value == null) emptyList() else (0 until value.length()).map { index ->
            val item = value.get(index); require(item is String && item.isNotBlank()) { "设备范围选项必须是准确字符串" }; item
        }
        private fun peer(snapshot: JSONObject, id: String): JSONObject = snapshot.optJSONArray("peers").objects().singleOrNull { it.opt("deviceId") == id } ?: error("这台设备不在原读取窗口，请刷新")
        private fun samePeer(a: JSONObject, b: JSONObject): Boolean = listOf("deviceId", "certificateFingerprint", "instanceId", "authorizationEpoch", "revoked").all { a.opt(it) == b.opt(it) }
    }
    @Synchronized fun clear(preserveImageChoice: Boolean = false,preserveModelPlace:Boolean=false) {
        if(!preserveModelPlace)modelPlace=null
        if (!preserveImageChoice) pairingImage.clear()
        // Keep running-lane gates until their own callbacks settle; an invalidated read cannot
        // race a new review on this same native Window and close its resources.
        generation.incrementAndGet(); review = null; ownerRules=null; historyPages.clear(); phoneArchive = null; waitingPage = null; closedPage=null; latest = JSONObject()
        desktopGeneration.incrementAndGet(); desktop?.close(); desktop = null
        desktopInFlight.set(null); runCatching { window()?.closeDesktop() }
        runCatching { window()?.stopLanDiscovery() }
        runCatching { window()?.discardReview() }
    }
    @Synchronized private fun discard(original: Host.Window, handle: String = ""): Boolean {
        if (handle.isNotBlank() && review?.handle != handle) return false
        generation.incrementAndGet(); review = null
        original.discardReview(); return true
    }
    private fun captureSnapshot(original: Host.Window, ids: Array<String>): JSONObject {
        val value = original.snapshot(ids)
        val actions = value.optJSONObject("taskActions") ?: JSONObject()
        // The original aggregate omits pending resume requests; retain the full native per-task view.
        ids.take(40).forEach { id ->
            try { actions.put(id, original.taskActions(id)) }
            catch (_: Exception) { actions.put(id, JSONObject().put("capturedAt", System.currentTimeMillis()).put("unavailable", true)) }
        }
        return value.put("taskActions", actions)
    }
    /** Match the original private peer view; this only limits a new LOCAL draft. No route lookup. */
    fun originalPhoneForPreparation(hint: JSONObject): JSONObject {
        fields(hint,"deviceId","instanceId","certificateFingerprint")
        val native = requireNotNull(window()); current(native,generation.get())
        val target = peer(latest,text(hint,"deviceId"))
        val platform = target.optString("platform"); val profiles = target.optJSONObject("capabilitySnapshot")?.optJSONArray("capabilities").objects()
        require((platform.contains("android",true) || platform.contains("phone",true) || profiles.any { it.optString("id") in setOf("phone.tools.main","phone.tools.background") })
            && target.opt("revoked") != true && target.optJSONObject("connection")?.optString("state") != "REVOKED"
            && target.opt("instanceId") == hint.opt("instanceId") && target.opt("certificateFingerprint") == hint.opt("certificateFingerprint")) { "所选原手机身份或配对已变化，不会换用其他设备" }
        return dev.pocketengine.features.devices.DirectedPhoneTarget.read(hint).view()
    }
    /** The current original Windows peer is only a local draft audience; live grants are checked later. */
    fun originalWorkspaceForPreparation(hint: JSONObject): JSONObject {
        fields(hint, "deviceId", "instanceId", "certificateFingerprint")
        val native = requireNotNull(window()); current(native, generation.get())
        val target = peer(latest, text(hint, "deviceId"))
        require((target.optJSONObject("windowsDesktop") != null || target.optString("platform").contains("windows", true))
            && target.opt("revoked") != true && target.optJSONObject("connection")?.optString("state") != "REVOKED"
            && target.opt("instanceId") == hint.opt("instanceId") && target.opt("certificateFingerprint") == hint.opt("certificateFingerprint")) {
            "原 Windows 配对或安装已变化，没有改用其他设备"
        }
        return dev.pocketengine.features.devices.DirectedWorkspaceTarget.read(hint).view()
    }
    /** Identity of an already accepted Android goal on THIS receiving phone. Navigation facts only. */
    private fun receivedPhoneActionScope(id: String): JSONObject? {
        val task = controller.readOriginalDeviceTask(id)
        val origin = task.optJSONObject("origin") ?: return null
        val scope = task.optJSONObject("deviceExecutionScope") ?: return null
        val resource = scope.optJSONObject("resourceBinding") ?: return null
        if (task.opt("id") != id || task.opt("deviceExecutionKind") != "GOAL" || origin.opt("kind") != "paired_device" ||
            origin.opt("originDeviceId") != origin.opt("callerDeviceId") || origin.opt("rootTaskId") != origin.opt("parentTaskId") ||
            !DeviceProtocol.acceptedTextReturn(task) && (resource.optString("screenMode") !in setOf("main_screen", "background") || resource.opt("androidUserId") !is Number ||
            (resource.optJSONArray("packageNames")?.length() ?: 0) == 0)) return null
        return JSONObject().put("taskId", id).put("status", task.getString("status")).put("updatedAt", task.getLong("updatedAt"))
            .put("runId", task.optString("runId")).put("epoch", task.optInt("coordinatorEpoch")).put("instanceId", task.getString("deviceNativeInstanceId"))
            .put("sourceDeviceId", origin.getString("callerDeviceId")).put("targetDeviceId", origin.getString("targetDeviceId"))
            .put("operationId", origin.getString("remoteOperationId")).put("grantId", origin.getString("grantId"))
            .put("authorizationEpoch", origin.getLong("authorizationEpoch")).put("executionDeadline", scope.getLong("deadline"))
    }
    private fun taskIds(): Array<String> = (controller.state.tasks.filter(::isDeviceWorkspaceTask).map { it.id } + workspaceTaskLinks(latest).map { it.optString("parentTaskId") }).filter { it.isNotBlank() }.distinct().toTypedArray()
    fun checkedFileForNavigation(handle:String,oneTime:String):FileHandoff {
        check(visible() && controller.webPrivateOwnerVisible())
        val held=selected(JSONObject().put("viewHandle",handle),requireNotNull(window()),generation.get(),consume=false)
        val native=held.native as? History ?: error("原文件派发窗口已关闭")
        return requireNotNull(native.fileHandoff.get()?.takeIf { held.kind=="history" && held.view.opt("canCheckLocalExportedFile")==true &&
            it.key==oneTime && it.sourceHandle==handle && it.until>android.os.SystemClock.elapsedRealtime() &&
            held.view.opt("taskId")==it.selected.task && held.view.opt("operationId")==it.selected.op }) { "这次原文件打开许可已变化，请重新核对" }
    }
    fun taskForNavigation(args: JSONObject): String {
        check(visible() && controller.webPrivateOwnerVisible())
        if (args.has("savedDeviceChoice")) {
            fields(args, "taskId", "savedDeviceChoice")
            current(requireNotNull(window()), generation.get())
            val id = text(args, "taskId")
            val original = controller.state.tasks.singleOrNull { it.id == id } ?: error("原任务不在当前本机记录内，请重新读取")
            checkSavedDeviceChoice(args, original.raw)
            return id
        }
        if (args.has("manualPhoneTarget")) {
            fields(args, "taskId", "manualPhoneTarget")
            val original = requireNotNull(window()); current(original, generation.get())
            val id = text(args, "taskId"); val chosen = DirectedPhoneTarget.read(args.getJSONObject("manualPhoneTarget"))
            val partner = peer(latest, chosen.deviceId())
            require(partner.opt("instanceId") == chosen.instanceId() && partner.opt("certificateFingerprint") == chosen.certificateFingerprint() &&
                partner.opt("revoked") != true && partner.optJSONObject("connection")?.opt("state") != "REVOKED") { "原配对手机已变化，不打开其他设备的准备" }
            val row = controller.state.tasks.singleOrNull { it.id == id } ?: error("这份原手控准备不在本机已读取的工作内")
            val saved = if (OwnerPhoneControlScope.marked(row.raw)) OwnerPhoneControlScope.directedPreparation(row.raw)
                else row.raw.optJSONObject("deviceWorkspaceNavigation")?.takeIf {
                    it.opt("format") == "pocketworld.device-workspace-navigation.v1" && it.opt("mode") == "manual_phone" &&
                        it.opt("readOnly") == true && it.opt("executionAuthorized") == false
                }?.optJSONObject("manualPhonePreparation")
            require(OwnerPhoneControlScope.unstartedPreparation(row.raw) && saved != null && DirectedPhoneTarget.read(saved.getJSONObject("target")) == chosen) {
                "本机原手控准备已变化，不重新创建或启动"
            }
            return id
        }
        if (args.has("sourceViewHandle")) {
            fields(args, "taskId", "operationId", "sourceViewHandle")
            val original = selected(JSONObject().put("viewHandle",text(args,"sourceViewHandle")),requireNotNull(window()),generation.get(),consume=false)
            val id = text(args,"taskId"); val op = text(args,"operationId")
            require(original.kind == "history" && original.native is History && original.view.opt("phoneGoalResult") == true
                && original.view.optString("localPhoneResultPath") in setOf("original_run","need_dispatch_receipt")
                && original.view.opt("taskId") == id && original.view.opt("operationId") == op) { "这不是当前原手机结果的发起工作" }
            return id // Return to A's private source, not B's task or a continuation command.
        }
        if (args.has("archiveHandle")) {
            fields(args, "taskId", "operationId", "archiveHandle"); val id = text(args, "taskId")
            val index = archiveFor(text(args, "archiveHandle"), requireNotNull(window()), generation.get())
            require(!index.received && index.digests.containsKey(id to text(args, "operationId"))) { "不在这部手机已读取的原派工内" }
            return id // Read-only navigation only; do not put archive parents in taskIds() / controls.
        }
        fields(args, "taskId")
        return text(args, "taskId").also { require(it in taskIds()) { "请从原设备任务记录打开" } }
    }
    /** A scope-only row is not a device task-control candidate. Check the saved fixed target
     * against the same native device window and the bounded original task read. */
    fun checkSavedDeviceChoice(args: JSONObject, actual: JSONObject) {
        val id = text(args, "taskId")
        val input = args.getJSONObject("savedDeviceChoice")
        fields(input, "scopeRevision", "runId", "epoch", "conversationId", "localDeviceId", "target")
        val target = input.getJSONObject("target")
        fields(target, "deviceId", "instanceId", "certificateFingerprint")
        val device = text(target, "deviceId"); val partner = peer(latest, device)
        val keys = listOf("deviceId", "instanceId", "certificateFingerprint")
        val saved = actual.optJSONObject("ownerSavedDeviceChoice") ?: error("本机没有这次原设备选择，请重新读取")
        val run = input.opt("runId"); val conversation = input.opt("conversationId")
        val revision = integer(input, "scopeRevision", 1, DeviceProtocol.MAX_SAFE_INTEGER)
        val epoch = integer(input, "epoch", 0, Int.MAX_VALUE.toLong()).toInt()
        require(device.matches(Regex("pw1_[a-f0-9]{64}")) && text(target, "instanceId").matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) &&
            text(target, "certificateFingerprint").matches(Regex("[a-f0-9]{64}")) && keys.all { partner.opt(it) == target.opt(it) } &&
            partner.opt("revoked") != true && partner.optJSONObject("connection")?.opt("state") != "REVOKED" &&
            input.opt("localDeviceId") is String && latest.optJSONObject("local")?.opt("deviceId") == input.opt("localDeviceId") &&
            actual.opt("id") == id && actual.opt("actorId") == "owner" && actual.opt("spaceId") == "private" &&
            actual.opt("kind") in setOf("assistant", "phone") && !actual.has("origin") && !actual.has("ownerDeviceWork") &&
            actual.opt("contextAvailable") != false && actual.opt("sourceCurrentReadRequired") != true && actual.opt("taskProjection") != "source_unavailable" &&
            run is String && (if (run.isEmpty()) epoch == 0 else epoch > 0) && actual.opt("runId") == run && actual.optInt("coordinatorEpoch", -1) == epoch &&
            conversation is String && actual.opt("conversationId") == conversation &&
            saved.opt("format") == "pocketworld.owner-saved-device-choice.v1" && saved.opt("readOnly") == true &&
            saved.opt("executionAuthorized") == false && saved.opt("localDeviceId") == input.opt("localDeviceId") &&
            saved.optLong("scopeRevision", -1) == revision &&
            keys.all { saved.optJSONObject("target")?.opt(it) == target.opt(it) }) { "原任务、设备或已选范围已变化，没有打开其他工作" }
    }
    fun checkManualPreparationForNavigation(args: JSONObject, actual: JSONObject) {
        val saved = OwnerPhoneControlScope.directedPreparation(actual)
        require(taskForNavigation(args) == actual.opt("id") && saved != null &&
            DirectedPhoneTarget.read(saved.getJSONObject("target")) == DirectedPhoneTarget.read(args.getJSONObject("manualPhoneTarget"))) {
            "这份手控准备已不属于所选原手机，没有开始其他任务"
        }
    }
    @Synchronized private fun waitingFor(handle: String, original: Host.Window, epoch: Long): WaitingPage {
        current(original,epoch); val here=latest.getJSONObject("local")
        return waitingPage?.takeIf { it.handle==handle && it.window===original && it.epoch==epoch && it.here==here.opt("deviceId") && it.instance==here.opt("instanceId") }
            ?: error("本机待接手窗口或设备身份已变化，请重新读取")
    }
    // Single local row carried across this control lane's own preemption, never an execution proof.
    private data class PendingPick(val handle:String,val oldEpoch:Long,val source:ReceiptOrigin)
    @Synchronized private fun beginControl(original:Host.Window,args:JSONObject,picking:Boolean):Pair<Long,PendingPick?>{
        val pick=if(picking){
            fields(args,"operation","waitingHandle","taskId","operationId");require(text(args,"operation")=="task.cancelPending")
            val page=waitingFor(text(args,"waitingHandle"),original,generation.get());require(!page.results && !page.started && !page.declined && !page.withdrawn && !page.failed)
            val row=page.rows[text(args,"taskId") to text(args,"operationId")]?:error("请选择原目录里的未启动工作")
            PendingPick(page.handle,page.epoch,row)
        }else null
        discard(original);return generation.get() to pick
    }
    @Synchronized private fun pickedPending(pick:PendingPick,original:Host.Window,epoch:Long):ReceiptOrigin{
        current(original,epoch);val local=latest.getJSONObject("local");val row=pick.source;val page=waitingPage
        require(page!=null && page.window===original && page.handle==pick.handle && page.epoch==pick.oldEpoch && page.rows[row.task to row.operation]===row &&
                row.here==local.opt("deviceId") && row.instance==local.opt("instanceId")) { "本机原目录或所选未启动记录已变化" }
        return row
    }
    private fun receivedEntry(args: JSONObject): ReceiptOrigin {
        val key=text(args,"taskId") to text(args,"operationId")
        if(args.has("modelReturnHandle")){
            fields(args,"modelReturnHandle","taskId","operationId");val win=requireNotNull(window());val epoch=generation.get();current(win,epoch)
            val local=latest.getJSONObject("local")
            return modelPlace?.takeIf { it.handle==text(args,"modelReturnHandle") && it.issuedEpoch!=epoch && it.until>android.os.SystemClock.elapsedRealtime() &&
                it.source.task==key.first && it.source.operation==key.second && it.source.here==local.opt("deviceId") && it.source.instance==local.opt("instanceId") }?.source
                ?:error("原工作返回位置已过期或本机身份已变化；请从本机收件重新选择")
        }
        if(args.has("waitingHandle")){
            fields(args,"taskId","operationId","waitingHandle");val index=waitingFor(text(args,"waitingHandle"),requireNotNull(window()),generation.get())
            return index.rows[key] ?: error("这项工作不在本机刚核对的待接手页内")
        }
        fields(args,"taskId","operationId","archiveHandle")
        val original=archiveFor(text(args,"archiveHandle"),requireNotNull(window()),generation.get())
        require(original.received && key in original.inbox) { "请从本机接纳的这项目标返回，不能改换工作" }
        val (digest,mode)=original.inbox.getValue(key)
        return ReceiptOrigin(key.first,key.second,original.target,original.localId,original.localInstance,digest,mode)
    }
    /** One local, still-never-started received goal; an archive navigation token is not an action scope. */
    private fun waitingPending(args: JSONObject): Pair<ReceiptOrigin,JSONObject> {
        fields(args,"waitingHandle","taskId","operationId")
        val index=waitingFor(text(args,"waitingHandle"),requireNotNull(window()),generation.get())
        require(!index.results && !index.started && !index.declined && !index.withdrawn && !index.failed) { "请从本机待接手页重新读取原目标" }
        return checkedPending(receivedEntry(args))
    }
    private fun checkedPending(source:ReceiptOrigin):Pair<ReceiptOrigin,JSONObject>{
        val raw=controller.readOriginalDeviceTask(source.task);checkedReceivedSource(source,raw)
        require(raw.optString("runId").isEmpty()&&raw.optInt("coordinatorEpoch",-1)==0&&raw.opt("cancelRequested")==false
            &&raw.optString("status") in setOf("queued","awaiting_user","interrupted")) { "原目标已启动或结束，不能再按未启动原件处理" }
        return source to raw
    }
    private fun screenReturn(args:JSONObject):ScreenDecision {
        fields(args,"closedHandle","taskId","operationId")
        val page=closedFor(text(args,"closedHandle"),requireNotNull(window()),generation.get())
        return page.screens[text(args,"taskId") to text(args,"operationId")]?:error("不是本机刚核对的原屏幕工作")
    }
    fun receivedTaskForReturn(args:JSONObject):String = if(args.has("closedHandle"))screenReturn(args).origin.task else receivedEntry(args).task
    fun checkReceivedTaskForReturn(args:JSONObject,raw:JSONObject):JSONObject {
        if(!args.has("closedHandle")){
            val source=receivedEntry(args)
            // From the started directory the read-only navigation stays on the same run,
            // even if its candidate was decided after this snapshot. Opening the candidate is stricter.
            return checkedReceivedSource(source,raw,checkReview=source.activeRun.isEmpty())
        }
        val chosen=screenReturn(args)
        dev.pocketengine.store.ReceivedScreenGoalDecision.requireSame(raw,chosen.proof)
        return checkedReceivedSource(chosen.origin,raw).put("screenDecision",true).put("confirmed",chosen.proof.getBoolean("confirmed"))
    }
    private fun checkedReceivedSource(source:ReceiptOrigin,raw:JSONObject,checkReview:Boolean=true):JSONObject{
        val origin=raw.optJSONObject("origin") ?: error("原接纳来源未能核对")
        require(raw.opt("id")==source.task && raw.opt("deviceExecutionKind")=="GOAL" && DeviceProtocol.receivedPhoneScopeMode(raw)==source.mode
            && raw.opt("actorId")=="paired_device:${source.source}" && raw.opt("spaceId")=="device:${source.source}"
            && raw.opt("deviceNativeInstanceId")==source.instance
            && DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(origin))==source.digest) { "本机原接纳任务身份已变化，未打开或恢复执行" }
        if(source.activeRun.isNotEmpty())require(raw.opt("runId")==source.activeRun && raw.optInt("coordinatorEpoch",-1)==source.activeEpoch) { "本机原执行已换代，未打开另一轮或重新接手" }
        if(source.failed)require(source.activeRun.isNotEmpty() && raw.opt("status")=="failed"){"原任务已不处于这次失败状态，没有打开其他执行"}
        if(checkReview&&source.reviewRun.isNotEmpty()){
            val result=raw.optJSONObject("deviceGoalResult") ?: error("原成果不再可审阅，请重新读取")
            require(raw.opt("status")==source.candidateState.ifEmpty { "needs_confirmation" } && raw.opt("runId")==source.reviewRun && raw.optInt("coordinatorEpoch",-1)==source.reviewEpoch
                && result.opt("status")=="needs_confirmation" && result.opt("closed")==false && result.opt("nativeRunnerExited")==true && result.opt("nativeDispatchClosed")==true
                && result.opt("runId")==source.reviewRun && result.optInt("executorEpoch",-1)==source.reviewEpoch && result.optLong("recordedAt")==source.reviewRecordedAt
            ) { "这份原成果已核对或运行身份已变化，不会打开另一轮成果" }
        }
        return JSONObject().put("kind","receivedGoalReturn").put("readOnly",true).put("taskId",source.task).put("operationId",source.operation)
            .put("instanceId",source.instance).put("sourceId",source.source).put("targetId",source.here)
            .put("parentTaskId",origin.getString("parentTaskId")).put("grantId",origin.getString("grantId")).put("epoch",origin.getLong("authorizationEpoch"))
            .also { if(source.activeRun.isNotEmpty())it.put("runId",source.activeRun).put("executorEpoch",source.activeEpoch);if(source.failed)it.put("failedOriginalRun",true) }
    }
    private fun prepareModelPlace(args:JSONObject,original:Host.Window,epoch:Long):JSONObject{
        val local=latest.getJSONObject("local");val held:Review?;val raw:JSONObject;val source:ReceiptOrigin
        if(args.has("waitingHandle")){
            held=null;val pending=waitingPending(args);source=pending.first;raw=pending.second
        }else{
            val id=if(args.has("viewHandle")){
                fields(args,"viewHandle");held=selected(args,original,epoch,consume=false);require(held.kind=="goal" && held.native is Host.GoalReview)
                held.view.getString("taskId")
            }else{
                fields(args,"taskId");held=null;text(args,"taskId").also { require(it in taskIds()) }
            }
            raw=controller.readOriginalDeviceTask(id);val origin=raw.getJSONObject("origin");val mode=DeviceProtocol.receivedPhoneScopeMode(raw)
            require(mode.isNotEmpty() && origin.opt("targetDeviceId")==local.opt("deviceId") && origin.opt("parentTaskId")==origin.opt("rootTaskId"))
            if(held!=null)require(DeviceProtocol.canonical(origin)==DeviceProtocol.canonical(held.view.getJSONObject("origin"))
                && mode==held.view.getJSONObject("resourceBinding").opt("screenMode"))
            source=ReceiptOrigin(id,origin.getString("remoteOperationId"),origin.getString("callerDeviceId"),local.getString("deviceId"),local.getString("instanceId"),
                DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(origin)),mode)
        }
        require(raw.optString("runId").isEmpty() && raw.optInt("coordinatorEpoch",-1)==0 && raw.opt("cancelRequested")==false &&
            raw.optString("status") in setOf("queued","awaiting_user") && controller.retainedDeviceGoalCondition(source.task)=="retained") {
            "原目标已接手、失效或没有本机保留的准入，未把它带到模型设置"
        }
        val receipt=checkedReceivedSource(source,raw)
        val place=ModelPlace(UUID.randomUUID().toString(),source,epoch,android.os.SystemClock.elapsedRealtime()+15*60_000L)
        synchronized(this){
            current(original,epoch);if(held!=null)require(selected(args,original,epoch,consume=false)===held)
            if(args.has("waitingHandle"))require(receivedEntry(args)==source)
            modelPlace=place
        }
        return receipt.put("modelReturnHandle",place.handle)
    }
    private fun waitingGoals(args: JSONObject, original: Host.Window, epoch: Long, searching:Boolean): JSONObject {
        val search:String
        val old: WaitingPage?;val after: Pair<String,String>;val previous: List<Pair<String,String>>;val results:Boolean;val started:Boolean;val declined:Boolean;val failed:Boolean;val withdrawn:Boolean
        if(args.has("waitingHandle")){
            fields(args,"waitingHandle","direction");old=waitingFor(text(args,"waitingHandle"),original,epoch);results=old.results;started=old.started;declined=old.declined;failed=old.failed;withdrawn=old.withdrawn;search=old.search
            require(searching==search.isNotEmpty()) { "搜索页与完整受理目录不能混用" }
            when(text(args,"direction")){
                "older" -> { after=requireNotNull(old.next); previous=(old.previous+listOf(old.after)).takeLast(32) }
                "newer" -> { require(old.previous.isNotEmpty());after=old.previous.last();previous=old.previous.dropLast(1) }
                else -> error("仅能读取本机较早或较新的一批接纳记录")
            }
        }else{
            if(searching)fields(args,"searchText","queue")else fields(args,"queue")
            search=if(searching)text(args,"searchText",128).trim().also{require(it.isNotEmpty())}else ""
            val lane=if(args.has("queue"))text(args,"queue") else "pending"
            require(!searching || lane in setOf("pending","declined","withdrawn")) { "这里只在所选原目标目录中查找" }
            require(lane in setOf("pending","results","started","declined","failed","withdrawn"));withdrawn=lane=="withdrawn";results=lane=="results";started=lane=="started";declined=lane=="declined";failed=lane=="failed"
            old=null;after="" to "";previous=emptyList()
        }
        val here=latest.getJSONObject("local");val id=here.getString("deviceId");val instance=here.getString("instanceId")
        var seek=after;var unverified=0
        fun part():JSONObject{
            current(original,epoch);if(old!=null)require(waitingPage===old)
            val page=if(searching&&(withdrawn||declined))original.findBeforePickupDecision(seek.first,seek.second,search,withdrawn)
                else if(searching)original.findWaitingPhoneGoals(seek.first,seek.second,search)else if(withdrawn)original.withdrawnReceivedPhoneGoals(seek.first,seek.second) else if(failed)original.failedReceivedPhoneGoals(seek.first,seek.second) else if(declined)original.declinedReceivedPhoneGoals(seek.first,seek.second) else if(started)original.startedReceivedPhoneGoals(seek.first,seek.second) else original.waitingPhoneGoals(seek.first,seek.second,results)
            require(page.opt("readOnly")==true && page.opt("waitingOnly")==(!started&&!declined&&!failed&&!withdrawn) && page.optBoolean("withdrawnOnly")==withdrawn && page.optBoolean("failedOnly")==failed && page.optBoolean("startedOnly")==started && page.optBoolean("declinedOnly")==declined && page.opt("resultOnly")==results && page.opt("localDeviceId")==id && page.opt("localInstanceId")==instance)
            val limited=page.getInt("limitedCandidates");require(limited in 0..(if(declined||withdrawn)6 else 24));unverified+=limited
            return page
        }
        var page=part();var count=1
        // At most three windows per click: 6 originals for closed decisions, 24 for pending. No peer query.
        while(searching && count<3 && page.getJSONArray("rows").length()==0 && page.getBoolean("hasMore")){
            val nextSeek=page.getString("lastParent") to page.getString("lastOperation")
            require(nextSeek!=seek && nextSeek.first.isNotEmpty() && nextSeek.second.isNotEmpty());seek=nextSeek;page=part();count++
        }
        val rows=page.getJSONArray("rows").objects();require(rows.size<=(if(declined||withdrawn)6 else 24))
        // A closed decision has NO actionable or navigable task entries. Its handle only moves the page.
        val keys=if(declined||withdrawn)emptyMap() else rows.associate { val task=it.getString("taskId");val op=it.getString("operationId")
            val reviewSlot=results||started&&it.getJSONObject("startedRun").opt("taskState")=="needs_confirmation"
            (task to op) to ReceiptOrigin(task,op,it.getString("sourceDeviceId"),id,instance,it.getString("originDigest"),it.getString("screenMode"),
                if(reviewSlot)it.getString("reviewRun") else "", if(reviewSlot)it.getInt("reviewEpoch") else 0, if(reviewSlot)it.getLong("reviewRecordedAt") else 0,
                if(failed||started)it.getJSONObject(if(failed)"failedRun" else "startedRun").getString("runId") else "",if(failed||started)it.getJSONObject(if(failed)"failedRun" else "startedRun").getInt("epoch") else 0,failed,if(results)it.optString("candidateState") else "") }
        require(declined||withdrawn||keys.size==rows.size)
        val next=if(page.getBoolean("hasMore")) page.getString("lastParent") to page.getString("lastOperation") else null
        val index=WaitingPage(UUID.randomUUID().toString(),original,epoch,id,instance,after,previous,next,keys,results,started,declined,failed,withdrawn,search)
        synchronized(this){current(original,epoch);if(old!=null)require(waitingPage===old);waitingPage=index}
        val display=JSONArray();for(row in rows)display.put(JSONObject().also { item ->
            for(key in listOf("taskId","operationId","sourceDeviceId","goal","screenMode","createdAt","waitingState"))item.put(key,row.get(key))
            row.optJSONObject("sourceIdentity")?.let{item.put("sourceIdentity",it)}
            if(!results&&!started&&!declined&&!withdrawn&&!failed){
                item.put("pickupRetained",row.getBoolean("pickupRetained"))
                if(row.has("pickupGrantBlock"))item.put("pickupGrantBlock",row.getString("pickupGrantBlock").also { require(it in setOf("revoked","changed","expired","unverifiable")) })
                if(row.has("pickupBy"))item.put("pickupBy",row.getLong("pickupBy"))
            }
            if(searching && row.optBoolean("matchedLater"))item.put("matchedLater",true)
            if(row.optBoolean("textOnly"))item.put("textOnly",true)
            if(withdrawn)item.put("withdrawnAt",row.getLong("withdrawnAt")).put("notDispatched",true)
            if(declined)item.put("declinedAt",row.getLong("declinedAt")).put("notDispatched",true)
            if(failed){val run=row.getJSONObject("failedRun");for(key in listOf("runId","epoch","registeredAt","stateRecordedAt"))item.put(key,run.get(key))}
            if(started){val run=row.getJSONObject("startedRun");for(key in listOf("runId","epoch","taskState","runState","registeredAt","stopRequested"))item.put(key,run.get(key))}
            if(results||started&&row.getJSONObject("startedRun").opt("taskState")=="needs_confirmation"){
                item.put("reviewRecordedAt",row.getLong("reviewRecordedAt"))
                if(row.has("candidateState")){
                    val state=row.getString("candidateState");require(state in setOf("interrupted","failed","cancelled"))
                    item.put("candidateState",state)
                }
            } })
        return JSONObject().put("kind","waitingPhoneGoals").put("searchText",search.takeIf{it.isNotEmpty()}).put("withdrawnOnly",withdrawn).put("failedOnly",failed).put("declinedOnly",declined).put("startedOnly",started).put("resultOnly",results).put("limitedCandidates",unverified).put("readOnly",true).put("waitingHandle",index.handle).put("localDeviceId",id)
            .put("localInstanceId",instance).put("capturedAt",System.currentTimeMillis()).put("rows",display).put("hasMore",next!=null).put("canReadNewer",previous.isNotEmpty())
    }
    @Synchronized private fun archiveFor(handle: String, original: Host.Window, epoch: Long): PhoneArchive {
        current(original, epoch)
        val index = phoneArchive?.takeIf { it.handle == handle && it.window === original && it.epoch == epoch } ?: error("原手机派工目录已变化，请重新读取")
        val target = peer(latest, index.target); val here = latest.getJSONObject("local")
        require(index.source.isNotEmpty() || target.optLong("authorizationEpoch")==index.partnerEpoch) { "原伙伴记录已更新，请重读这台手机的目录" }
        require(index.source.isEmpty() || target.opt("revoked")!=true) { "原配对已撤销" }
        require(target.opt("instanceId") == index.targetInstance && target.opt("certificateFingerprint") == index.fingerprint &&
            here.opt("deviceId") == index.localId && here.opt("instanceId") == index.localInstance) { "原本机或手机身份已变化" }
        return index
    }
    private fun phoneArchivePage(args: JSONObject, original: Host.Window, epoch: Long, received: Boolean): JSONObject {
        if(args.has("localCopy")){
            fields(args,"sourceTaskId","localCopy","after");require(!received) { "本机发件留档不能用于收件或读对端" }
            val source=DeviceProtocol.identifier(args.opt("sourceTaskId"),"sourceTaskId")
            val after=if(args.has("after")) DeviceProtocol.identifier(args.opt("after"),"after") else ""
            val here=latest.getJSONObject("local");current(original,epoch)
            val page=original.savedDirectedPhoneDispatches(args.getJSONObject("localCopy"),source,after)
            current(original,epoch);val live=latest.getJSONObject("local")
            require(listOf("deviceId" to "localDeviceId","instanceId" to "localInstanceId").all { (field,key) ->
                here.getString(field)==page.opt(key) && here.getString(field)==live.opt(field) }) { "本机身份已变化，未展示派单留档" }
            return page // No PhoneArchive/Review capability is minted, even after a previously valid pairing.
        }
        val old: PhoneArchive?; val targetId: String; val after: Pair<String,String>; val previous: List<Pair<String,String>>; val search: String; val source: String; val needsProof:Boolean
        if (args.has("archiveHandle")) {
            fields(args, "archiveHandle", "direction")
            old = archiveFor(text(args, "archiveHandle"), original, epoch); targetId = old.target; search=old.search; source=old.source; needsProof=old.needsAcceptanceProof; require(old.received == received) { "发出与接纳目录不能混用" }
            when (text(args, "direction")) {
                "older" -> { after = requireNotNull(old.next) { "没有更早的本机记录" }; previous = (old.previous + listOf(old.after)).takeLast(32) }
                "newer" -> { require(old.previous.isNotEmpty()); after = old.previous.last(); previous = old.previous.dropLast(1) }
                else -> error("只支持在本机原派工目录翻页")
            }
        } else {
            fields(args, "targetDeviceId", "searchText","sourceTaskId","needsAcceptanceProof");
            source=if(args.has("sourceTaskId")) text(args,"sourceTaskId") else ""
            needsProof=args.has("needsAcceptanceProof")
            require(!needsProof || !received && source.isEmpty() && args.opt("needsAcceptanceProof")==true) { "接单凭据筛选只属于本机发给当前伙伴的目录" }
            require(source.isEmpty()||!received&&!args.has("searchText")) { "原任务只能读取自己的直接派单" }
            search=if(args.has("searchText")) text(args,"searchText",128).trim() else ""; old = null; targetId = text(args, "targetDeviceId"); after = "" to ""; previous = emptyList()
        }
        val target = peer(latest, targetId); val here = latest.getJSONObject("local")
        val side = if (received) "received" else "sent"
        var seek=after; var pages=0
        fun readPart():JSONObject {
            current(original,epoch);if(old!=null)require(phoneArchive===old)
            val live=peer(latest,targetId);val device=latest.getJSONObject("local")
            require(live.opt("instanceId")==target.opt("instanceId") && live.opt("certificateFingerprint")==target.opt("certificateFingerprint") &&
                device.opt("deviceId")==here.opt("deviceId") && device.opt("instanceId")==here.opt("instanceId")) { "本机或原伙伴身份已变化" }
            val part=if(source.isEmpty()) original.phoneGoalIndex(targetId,seek.first,seek.second,received,search)
                else original.directedPhoneDispatches(targetId,source,seek.first,seek.second).also {
                    require(it.opt("sourceTaskId")==source&&it.opt("targetFingerprint")==target.opt("certificateFingerprint")&&target.opt("revoked")!=true)
                }
            require(part.opt("side")==side && part.opt("readOnly")==true && part.opt("targetDeviceId")==targetId &&
                (source.isNotEmpty() || part.opt("targetFingerprint")==target.opt("certificateFingerprint") && part.getLong(if(received)"receivedPeerEpoch" else "sentPeerEpoch")==target.getLong("authorizationEpoch")) &&
                part.opt("instanceId")==target.opt("instanceId") && part.opt("localDeviceId")==here.opt("deviceId") &&
                part.opt("localInstanceId")==here.opt("instanceId") && part.getJSONArray("rows").length()<=24) { "本机索引不属于原手机" }
            pages++;return part
        }
        // Each native call releases TaskStore before continuing: at most 4 x 24
        // original rows per explicit search/filter. Never poll or scan the other peer/direction.
        fun matching(part:JSONObject) = part.getJSONArray("rows").objects().filter { !needsProof || !it.has("targetAcceptedAt") }
        var page=readPart();var rows=matching(page)
        while((search.isNotEmpty()||needsProof) && rows.isEmpty() && page.getBoolean("hasMore") && pages<4){
            val nextSeek=page.getString("lastParent") to page.getString("lastOperation")
            require(nextSeek!=seek && nextSeek.first.isNotEmpty() && nextSeek.second.isNotEmpty());seek=nextSeek;page=readPart();rows=matching(page)
        }
        require(rows.size <= 24)
        val digests = rows.associate { (it.getString("taskId") to it.getString("operationId")) to it.getString("argumentsDigest") }
        require(digests.size == rows.size && (source.isEmpty() || digests.keys.all { it.first == source }))
        val inbox = if (received) rows.associate { (it.getString("taskId") to it.getString("operationId")) to (it.getString("originDigest") to it.getString("screenMode")) } else emptyMap()
        val next = if (page.getBoolean("hasMore")) page.getString("lastParent") to page.getString("lastOperation") else null
        val index = PhoneArchive(UUID.randomUUID().toString(), original, epoch, targetId, target.getString("instanceId"), target.getString("certificateFingerprint"),
            here.getString("deviceId"), here.getString("instanceId"), after, previous, next, digests, received, inbox,search,source,if(source.isNotEmpty())0 else page.getLong(if(received)"receivedPeerEpoch" else "sentPeerEpoch"),needsProof)
        synchronized(this) { current(original, epoch); if (old != null) require(phoneArchive === old); phoneArchive = index }
        val display = JSONArray()
        for (row in rows) display.put(JSONObject().also { safe ->
            for (key in listOf("taskId", "operationId", "goal", "screenMode", "createdAt")) safe.put(key, row.get(key))
            if(received&&row.optBoolean("mayCheckLocalText"))safe.put("mayCheckLocalText",true)
            if(row.optBoolean("textOnly"))safe.put("textOnly",true)
            if(source.isNotEmpty()){
                row.optJSONObject("stopReceipt")?.let { safe.put("stopReceipt",it) }
                    ?:row.optJSONObject("stopAttempt")?.let { safe.put("stopAttempt",it) }
            }
            if(needsProof && row.has("requestDeadline"))safe.put("requestDeadline",row.getLong("requestDeadline"))
            if(!received && !row.has("targetAcceptedAt") && row.has("sourceSendPhase"))safe.put("sourceSendPhase",row.getString("sourceSendPhase"))
            if(!received && row.has("targetAcceptedAt")) {
                safe.put("targetAcceptedAt",row.getLong("targetAcceptedAt"))
                if(row.optBoolean("targetRunRecorded"))safe.put("targetRunRecorded",true)
            }
            if(search.isNotEmpty() && row.optBoolean("matchedLater"))safe.put("matchedLater",true)
        })
        return JSONObject().put("kind", "phoneGoalArchive").put("side", side).put("archiveHandle", index.handle).put("targetDeviceId", targetId).put("readOnly", true)
            .put("instanceId", index.targetInstance).put("targetFingerprint", index.fingerprint).put("receivedPeerEpoch",if(received)index.partnerEpoch else null)
            .put("sentPeerEpoch",if(!received&&source.isEmpty())index.partnerEpoch else null)
            .put("oldTargetIdentityChanged",true.takeIf { !received&&source.isEmpty()&&page.optBoolean("oldTargetIdentityChanged") }).put("localDeviceId", index.localId).put("localInstanceId", index.localInstance)
            .put("sourceTaskId",source.takeIf { it.isNotEmpty() }).put("needsAcceptanceProof",true.takeIf { needsProof })
            .put("searchText",search).put("rows", display).put("hasMore", next != null).put("canReadNewer", previous.isNotEmpty())
    }
    @Synchronized private fun closedFor(handle:String,original:Host.Window,epoch:Long):ClosedPage{
        current(original,epoch);val local=latest.getJSONObject("local")
        return closedPage?.takeIf { it.handle==handle && it.window===original && it.epoch==epoch && local.opt("deviceId")==it.here && local.opt("instanceId")==it.instance }
            ?:error("本机已核对目录已改变，请重新读取")
    }
    private fun reviewedInbox(args:JSONObject,original:Host.Window,epoch:Long):JSONObject{
        val old:ClosedPage?;val after:Pair<String,String>;val previous:List<Pair<String,String>>;val search:String
        if(args.has("closedHandle")){
            fields(args,"closedHandle","direction");old=closedFor(text(args,"closedHandle"),original,epoch);search=old.search
            when(text(args,"direction")){
                "older"->{after=requireNotNull(old.next);previous=(old.previous+listOf(old.after)).takeLast(32)}
                "newer"->{require(old.previous.isNotEmpty());after=old.previous.last();previous=old.previous.dropLast(1)}
                else->error("仅支持翻阅本机目录")
            }
        }else{fields(args,"searchText");old=null;after="" to "";previous=emptyList();search=if(args.has("searchText"))text(args,"searchText",128).trim() else ""}
        val local=latest.getJSONObject("local");val here=local.getString("deviceId");val instance=local.getString("instanceId")
        var seek=after;var limited=0
        fun readPart():JSONObject{
            current(original,epoch);if(old!=null)require(closedPage===old)
            val live=latest.getJSONObject("local");require(here==live.opt("deviceId") && instance==live.opt("instanceId"))
            val part=original.closedReceivedPhoneGoals(seek.first,seek.second,search)
            require(part.opt("closedOnly")==true && part.opt("readOnly")==true && part.opt("waitingOnly")==false && here==part.opt("localDeviceId") && instance==part.opt("localInstanceId"))
            limited+=part.getInt("limitedCandidates");return part
        }
        var page=readPart()
        // An explicit lookup checks at most two six-candidate windows; release TaskStore between them.
        if(search.isNotEmpty() && page.getJSONArray("rows").length()==0 && page.getBoolean("hasMore")){
            val nextSeek=page.getString("lastParent") to page.getString("lastOperation")
            require(seek!=nextSeek && nextSeek.first.isNotEmpty() && nextSeek.second.isNotEmpty());seek=nextSeek;page=readPart()
        }
        val handle=UUID.randomUUID().toString();val rows=page.getJSONArray("rows").objects();require(rows.size<=6)
        val screens=rows.filter{it.getJSONObject("closedReview").opt("screenDecision")==true}.associate{ row->
            val task=row.getString("taskId");val op=row.getString("operationId");val proof=row.getJSONObject("closedReview")
            val record=ReceiptOrigin(task,op,row.getString("sourceDeviceId"),here,instance,row.getString("originDigest"),row.getString("screenMode"),
                activeRun=proof.getString("runId"),activeEpoch=proof.getInt("epoch"))
            (task to op) to ScreenDecision(record,JSONObject(proof.toString()))
        }
        val readable=rows.filter { it.getJSONObject("closedReview").opt("notConfirmed")!=true && it.getJSONObject("closedReview").opt("screenDecision")!=true }
        val entries=readable.associate { row->
            val task=row.getString("taskId");val op=row.getString("operationId")
            val record=ReceiptOrigin(task,op,row.getString("sourceDeviceId"),here,instance,row.getString("originDigest"),row.getString("screenMode"))
            val meta=row.getJSONObject("closedReview");val match=if(search.isEmpty())"" else meta.getString("matchPart")
            val position=if(search.isEmpty())0 else meta.getInt("matchPage")
            require(search.isEmpty() || match in setOf("goal","result") && position in 0..187 && meta.opt("bothParts") is Boolean)
            (task to op) to LocalLeaf(handle,record,row.getString("argumentsDigest"),meta.getString("stamp"),match,position)
        }
        require(entries.size==readable.size && rows.map { it.getString("taskId") to it.getString("operationId") }.distinct().size==rows.size)
        val next=if(page.getBoolean("hasMore"))page.getString("lastParent") to page.getString("lastOperation") else null
        synchronized(this){current(original,epoch);if(old!=null)require(closedPage===old);closedPage=ClosedPage(handle,original,epoch,here,instance,after,previous,next,entries,search,screens)}
        val display=JSONArray();for(row in rows){val meta=row.getJSONObject("closedReview");display.put(JSONObject().also { out->
            for(key in listOf("taskId","operationId","sourceDeviceId","goal"))out.put(key,row.get(key))
            out.put("reviewedAt",meta.getLong("reviewedAt"))
            if(meta.opt("screenDecision")==true) {
                out.put("screenDecision",true).put("confirmed",meta.getBoolean("confirmed")).put("runId",meta.getString("runId")).put("epoch",meta.getInt("epoch"))
            }else if(meta.opt("notConfirmed")==true)out.put("notConfirmed",true)
            else{
                out.put("keptOnPhone",meta.getBoolean("keptOnPhone")).put("hasText",meta.getBoolean("hasText"))
                if(search.isNotEmpty())for(key in listOf("matchPart","matchPage","bothParts"))out.put(key,meta.get(key))
            }
        })}
        return JSONObject().put("kind","reviewedPhoneInbox").put("readOnly",true).put("closedHandle",handle).put("localDeviceId",here).put("localInstanceId",instance)
            .put("rows",display).put("searchText",search).put("limitedCandidates",limited).put("hasMore",next!=null).put("canReadNewer",previous.isNotEmpty())
    }
    private fun localTextLeaf(args:JSONObject,original:Host.Window,epoch:Long):LocalLeaf{
        val task=text(args,"taskId"); val operation=text(args,"operationId");val key=task to operation
        if(args.has("closedHandle")){
            fields(args,"closedHandle","taskId","operationId","page","stamp","part","openMatch")
            return closedFor(text(args,"closedHandle"),original,epoch).entries[key]?:error("不在这一本机原文目录中")
        }
        fields(args,"archiveHandle","taskId","operationId","page","stamp","part")
        val handle=text(args,"archiveHandle");val index=archiveFor(handle,original,epoch)
        val picked=JSONObject().put("archiveHandle",handle).put("taskId",task).put("operationId",operation)
        require(index.received&&index.source.isEmpty()&&index.inbox.containsKey(key))
        return LocalLeaf(handle,receivedEntry(picked),index.digests.getValue(key))
    }
    /** Two receiver-owned local shelves only. Neither handle is an execution or remote-read authority. */
    private fun localReviewedPhoneGoal(args: JSONObject,original: Host.Window,epoch: Long):JSONObject{
        val held=localTextLeaf(args,original,epoch); val entry=held.origin
        val match=args.has("openMatch")
        if(match)require(bool(args,"openMatch") && args.has("closedHandle") && held.stamp.isNotEmpty() && held.matchPart in setOf("goal","result") && !args.has("page") && !args.has("part") && !args.has("stamp"))
        val page=if(match)held.matchPage else if(args.has("page"))integer(args,"page",0,200).toInt() else 0
        val supplied=if(args.has("stamp"))text(args,"stamp",64) else ""
        val part=if(match)held.matchPart else if(args.has("part"))text(args,"part") else "result"
        require(part=="result" || part=="goal" && (match || supplied.matches(Regex("[a-f0-9]{64}"))))
        require(!args.has("page")||supplied.matches(Regex("[a-f0-9]{64}")))
        require(held.stamp.isEmpty()||supplied.isEmpty()||held.stamp==supplied)
        val selected=JSONObject().put("taskId",entry.task).put("operationId",entry.operation).put("sourceId",entry.source)
            .put("localId",entry.here).put("localInstance",entry.instance).put("originDigest",entry.digest).put("wireDigest",held.wire).put("screenMode",entry.mode).put("displayPart",part)
        val result=controller.readLocallyReviewedPhoneGoal(selected,page,held.stamp.ifEmpty { supplied })
        require(localTextLeaf(args,original,epoch)==held && result.optString("part","result")==part)
        return result.put("archiveHandle",held.handle)
    }
    @Synchronized private fun localHistoryPage(id: String, handle: String, original: Host.Window, epoch: Long): LocalHistoryPage {
        current(original, epoch)
        return historyPages[id]?.takeIf { it.handle == handle && it.window === original && it.epoch == epoch }
            ?: error("这批本机派发记录已变化，请从最近的记录重新读取")
    }
    private fun readLocalHistoryPage(id: String, target: String, held: LocalHistoryPage?, direction: String, original: Host.Window, epoch: Long): JSONObject {
        val after: String
        val previous: List<String>
        when (direction) {
            "older" -> { val old = requireNotNull(held); after = requireNotNull(old.next) { "没有更早的本机派发记录" }; previous = (old.previous + old.after).takeLast(32) }
            "newer" -> { require(held != null && held.previous.isNotEmpty()) { "请从最近的原记录重新读取" }; after = held.previous.last(); previous = held.previous.dropLast(1) }
            else -> { require(direction.isEmpty() && held == null); after = ""; previous = emptyList() }
        }
        val page = original.historyPage(id, target, after)
        val links = page.getJSONArray("links").objects()
        require(links.size <= 24 && links.all { it.opt("parentTaskId") == id && (target.isEmpty() || it.opt("targetDeviceId") == target) })
        val operations = links.associate { it.getString("operationId") to it.getString("targetDeviceId") }
        require(operations.size == links.size)
        val next = if (page.optBoolean("hasMore")) links.last().getString("operationId") else null
        val entry = LocalHistoryPage(UUID.randomUUID().toString(), original, epoch, target, after, previous, next, operations)
        synchronized(this) {
            current(original, epoch)
            if (held != null) require(historyPages[id] === held) { "原派发列表已更新，请从当前页读取" }
            if (historyPages.size >= 32 && id !in historyPages) historyPages.remove(historyPages.keys.first())
            historyPages[id] = entry
        }
        page.remove("nextOffset") // This local owner view uses an original row, not an unstable offset.
        return page.put("pageHandle", entry.handle).put("targetDeviceId", target).put("canReadNewer", previous.isNotEmpty())
    }
    /** Project the actual single-use SQL cancellation, never the whole private task or a peer delivery claim. */
    private fun localDeclineReceipt(reply:JSONObject,reviewed:JSONObject,id:String):JSONObject{
        val out=JSONObject().put("kind","receivedGoalDecline").put("verified",false)
        val origin=reply.optJSONObject("origin")?:return out;val proof=reply.optJSONObject("evidence")?:return out
        val local=latest.optJSONObject("local")?:return out;val op=reviewed.optString("operationId");val source=reviewed.optString("callerDeviceId")
        if(op.isEmpty()||source.isEmpty()||reviewed.opt("taskId")!=id||reviewed.opt("localOnly")!=true||reviewed.opt("runStarted")!=false ||
            reply.opt("id")!=id||reply.opt("status")!="cancelled"||reply.opt("cancelRequested")!=true||reply.optString("runId").isNotEmpty()||reply.optInt("coordinatorEpoch",-1)!=0||
            reply.opt("stopped")!=true||reply.opt("notDispatched")!=true||reply.opt("localOnly")!=true||reply.opt("newRun")!=false||
            origin.opt("callerDeviceId")!=source||origin.opt("originDeviceId")!=reviewed.opt("originDeviceId")||origin.opt("remoteOperationId")!=op||
            origin.opt("targetDeviceId")!=local.opt("deviceId")||reply.opt("deviceNativeInstanceId")!=local.opt("instanceId")||
            proof.opt("nativePendingGoalCancelled")!=true||proof.opt("notDispatched")!=true||proof.opt("localOnly")!=true||proof.opt("originalOperationId")!=op||
            !proof.optString("argumentsDigest").matches(Regex("[a-f0-9]{64}"))||reviewed.optLong("acceptedAt")<=0||proof.optLong("acceptedAt",-1)!=reviewed.optLong("acceptedAt"))return out
        return out.put("verified",true).put("taskId",id).put("operationId",op).put("sourceId",source).put("acceptedAt",reviewed.getLong("acceptedAt"))
            .put("localDeviceId",local.getString("deviceId")).put("localInstanceId",local.getString("instanceId")).put("notDispatched",true)
    }
    private fun current(original: Host.Window, epoch: Long) {
        check(visible() && controller.webPrivateOwnerVisible() && window() === original && generation.get() == epoch) { "原设备窗口已关闭，请先核对原回执" }
    }
    @Synchronized private fun store(kind: String, native: Any, view: JSONObject, original: Host.Window, epoch: Long): JSONObject {
        try { current(original, epoch) } catch (closed: Exception) {
            // A native read may finish after a background/Back event. Close its hidden resources.
            (native as? AutoCloseable)?.let { runCatching { it.close() } }
            runCatching { original.discardReview() }; throw closed
        }
        val value = Review(UUID.randomUUID().toString(), kind, native, JSONObject(view.toString()), original, epoch, System.currentTimeMillis() + 120_000)
        review = value
        return JSONObject().put("kind", kind).put("viewHandle", value.handle).put("view", view)
    }
    @Synchronized private fun selected(args: JSONObject, original: Host.Window, epoch: Long, consume: Boolean = true): Review {
        current(original, epoch)
        val value = review?.takeIf { it.handle == text(args, "viewHandle") && it.window === original && it.generation == epoch } ?: error("原设备审阅已关闭或使用，请重新核对")
        if (consume) review = null
        check(value.expires > System.currentTimeMillis()) { "原设备审阅已过期" }
        return value
    }
    private fun pairRaw(args:JSONObject):String{
        val raw=args.opt("raw");require(raw is String && raw.isNotBlank() && raw.length<=16_384 && '\u0000' !in raw && raw.toByteArray(Charsets.UTF_8).size<=16_384){"请提供完整的原邀请文本"}
        return raw
    }
    private fun previewPair(raw:String,pick:String,original:Host.Window,epoch:Long):JSONObject{
        fun visible(){current(original,epoch);require(pick.isEmpty()||pairingImage.current(pick,original)){"原选图已关闭，请重新选择"}}
        visible();val first=original.firstIdentityPreview(raw)
        if(first!=null){
            visible();return store("firstIdentity",FirstPair(first,pick),first.view().put("fromImage",pick.isNotEmpty()),original,epoch)
        }
        val pair=original.inspectInvitation(raw)
        try{visible()}catch(changed:Exception){original.discardReview();throw changed}
        return store("pair",pair,pair.view(),original,epoch)
    }
    private fun choice(args: JSONObject, view: JSONObject): Host.GrantChoice {
        fields(args, "viewHandle", "optionId", "toolBudget", "modelBudget", "durationMinutes", "historyDays", "historyReadMethods", "additionalOriginOptionIds", "forwardingOptionIds", "forwardingToolBudget", "forwardingModelBudget", "wholeDisplayCapture")
        val option = view.optJSONArray("grantOptions").objects().singleOrNull { it.opt("optionId") == text(args, "optionId", 128) } ?: error("请明确选择原生列出的权限范围")
        val tools = integer(args, "toolBudget", option.optLong("toolBudgetMin"), option.getLong("toolBudgetMax")).toInt()
        val models = integer(args, "modelBudget", option.optLong("modelBudgetMin"), option.getLong("modelBudgetMax")).toInt()
        val duration = integer(args, "durationMinutes", 1, 43_200)
        val origins = strings(args.getJSONArray("additionalOriginOptionIds")); val forwarding = strings(args.getJSONArray("forwardingOptionIds"))
        require(origins.size <= 8 && origins.toSet().size == origins.size && forwarding.size <= 8 && forwarding.toSet().size == forwarding.size)
        require(origins.all { id -> view.optJSONArray("originOptions").objects().any { it.opt("optionId") == id } }) { "转交来源不属于原生已认证选项" }
        val forwarded = forwarding.map { id -> view.optJSONArray("forwardingOptions").objects().singleOrNull { it.opt("optionId") == id } ?: error("下游范围已变化") }
        require(forwarded.isEmpty() || option.opt("allowDelegation") == true) { "当前范围不允许继续委派" }
        val forwardTools = integer(args, "forwardingToolBudget", 0, minOf(tools.toLong(), forwarded.minOfOrNull { it.getLong("toolBudgetMax") } ?: 0)).toInt()
        val forwardModels = integer(args, "forwardingModelBudget", 0, minOf(models.toLong(), forwarded.minOfOrNull { it.getLong("modelBudgetMax") } ?: 0)).toInt()
        require(forwarded.isEmpty() || forwardTools + forwardModels > 0) { "请选择有限下游次数" }
        val now = System.currentTimeMillis()
        val expires = minOf(now + duration * 60_000, option.getLong("expiresAtMax"), forwarded.minOfOrNull { it.getLong("expiresAtMax") } ?: Long.MAX_VALUE)
        require(expires > now) { "原权限选项已经到期" }
        val days = integer(args, "historyDays", 0, 30); val methods = strings(args.getJSONArray("historyReadMethods"))
        require(methods.toSet().size == methods.size && methods.all { it in strings(option.optJSONArray("historyReadMethods")) } && (days == 0L) == methods.isEmpty()) { "请明确选择原生支持的结果读取期限与方法" }
        val historyExpires = if (days == 0L) 0 else minOf(now + days * 86_400_000, option.getLong("historyReadExpiresAtMax"))
        require(days == 0L || historyExpires > now)
        val whole = bool(args, "wholeDisplayCapture"); require(!whole || option.opt("canGrantWholeDisplayCapture") == true) { "此范围未开放整屏截图" }
        val delegation = origins.isNotEmpty() || forwarding.isNotEmpty()
        return Host.GrantChoice(option.getString("optionId"), tools, models, expires, delegation, if (delegation) 2 else 0,
            historyExpires, methods, origins, forwarding, forwardTools, forwardModels, whole)
    }

    private fun desktopCurrent(original: Host.Window, epoch: Long) {
        check(visible() && controller.webPrivateOwnerVisible() && window() === original && desktopGeneration.get() == epoch) {
            "原桌面窗口已关闭，请重新连接"
        }
    }
    private fun dispatchDesktop(action: String, args: JSONObject, original: Host.Window, done: (JSONObject?, String?) -> Unit) {
        require(action in COMMANDS)
        if (action == "devices.desktop.close") {
            fields(args, "sessionId"); val id = text(args, "sessionId")
            val current = desktop
            if (current?.id() == id) {
                desktopGeneration.incrementAndGet(); desktop = null; desktopInFlight.set(null)
                val closed = original.closeDesktop(id)
                done(JSONObject().put("value", closed), null)
                return
            }
            done(JSONObject().put("value", JSONObject().put("sessionId", id).put("closed", false).put("remoteCloseConfirmed", false)), null)
            return
        }
        val call = UUID.randomUUID().toString()
        check(desktopInFlight.compareAndSet(null, call)) { "请先等待当前桌面操作的回执" }
        val epoch = if (action == "devices.desktop.open") desktopGeneration.incrementAndGet() else desktopGeneration.get()
        if (action == "devices.desktop.open") { desktop?.close(); desktop = null }
        val work = Runnable {
            var value: JSONObject? = null; var failure: String? = null; var opened: Host.DesktopWindow? = null
            try {
                desktopCurrent(original, epoch)
                if (action == "devices.desktop.open") {
                    fields(args, "deviceId", "instanceId", "grantId", "authorizationEpoch", "resourceBinding")
                    val deviceId = text(args, "deviceId"); val grantId = text(args, "grantId")
                    val instanceId = text(args, "instanceId"); val grantEpoch = integer(args, "authorizationEpoch", 1, DeviceProtocol.MAX_SAFE_INTEGER)
                    val binding = args.getJSONObject("resourceBinding")
                    DeviceProtocol.validateResourceBinding("desktop-v2", binding)
                    val selected = peer(original.snapshot(), deviceId)
                    require(!selected.optBoolean("revoked") && selected.opt("instanceId") == instanceId) { "设备信任或电脑实例已变化，请返回核对原设备" }
                    require(selected.optJSONArray("receivedGrantScopes").objects().any { grant ->
                        grant.optString("grantId") == grantId && grant.optLong("authorizationEpoch") == grantEpoch && grant.optLong("expiresAt") > System.currentTimeMillis() &&
                            strings(grant.optJSONArray("capabilities")).contains("desktop.viewer") &&
                            grant.optJSONArray("resources").objects().any { DeviceProtocol.resourceEquals(it, binding) }
                    }) { "请从电脑当前已授予的桌面范围打开" }
                    val source = original.openDesktop(deviceId, instanceId, grantId, grantEpoch, binding, Host.DesktopCurrent { desktopCurrent(original, epoch) }); opened = source
                    value = source.view()
                    synchronized(this) { desktopCurrent(original, epoch); desktop = source }
                } else {
                    val source = desktop ?: error("原桌面会话已关闭")
                    check(source.id() == text(args, "sessionId")) { "桌面请求属于旧会话" }
                    val input = JSONObject(args.toString()).also { it.remove("sessionId") }
                    val operation = when (action) {
                        "devices.desktop.negotiate" -> { fields(args, "sessionId", "offer"); "negotiate" }
                        "devices.desktop.status" -> { fields(args, "sessionId"); "status" }
                        "devices.desktop.control" -> {
                            fields(args, "sessionId", "action", "expectedRevision")
                            text(args, "action").also { require(it in setOf("take", "release")); input.remove("action") }
                        }
                        "devices.desktop.input" -> { fields(args, "sessionId", "controlLease", "frameRef", "input", "receiptSequence"); "input" }
                        else -> error("桌面操作尚未接入")
                    }
                    value = source.request(operation, input)
                }
                desktopCurrent(original, epoch)
            } catch (error: Exception) {
                if (action == "devices.desktop.open") opened?.close()
                failure = if (error is org.json.JSONException) "桌面回执不完整，请重新连接核对。" else error.message?.take(400) ?: "桌面操作结果待核对"
            }
            main.post {
                desktopInFlight.compareAndSet(call, null)
                if (runCatching { desktopCurrent(original, epoch) }.isFailure) {
                    opened?.close(); done(null, "原桌面窗口已关闭，未重放输入")
                } else done(JSONObject().put("value", value?.let { PocketWebState.publicValue(it) } ?: JSONObject.NULL), failure)
            }
        }
        try { controller.runDeviceWorkspaceWork(work) }
        catch (failure: Exception) { desktopInFlight.compareAndSet(call, null); done(null, failure.message ?: "桌面工作线程不可用") }
    }

    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        val original = window() ?: error("原设备窗口已关闭，请重新进入")
        current(original, generation.get())
        if (action == "devices.pairImage.pick" || action == "devices.pairImage.close") {
            fields(args, "pickerId"); val id = text(args, "pickerId", 36)
            if (action.endsWith(".pick")) {
                val available = original.joinAvailability(); check(available.available) { available.reason }
                pairingImage.pick(controller.webPresentationActivity(), id, original)
            } else pairingImage.clear(id)
            done(JSONObject().put("value", JSONObject().put("pickerId", id).put("opened", action.endsWith(".pick"))), null); return
        }
        if (action.startsWith("devices.desktop.")) { dispatchDesktop(action, args, original, done); return }
        if (action == "devices.discovery.stop") {
            fields(args); discard(original); original.stopLanDiscovery()
            done(JSONObject().put("value", JSONObject().put("candidatesDisabled", true).put("stopRequested", true)).put("snapshot", captureSnapshot(original, taskIds())), null); return
        }
        if (action == "devices.discard") {
            if(args.has("rulesHandle")){
                fields(args,"rulesHandle");val handle=text(args,"rulesHandle")
                val closed=synchronized(this){if(ownerRules?.handle!=handle)false else{ownerRules=null;true}}
                done(JSONObject().put("closed",closed),null);return
            }
            if(args.has("closedHandle")){
                fields(args,"closedHandle");val expected=text(args,"closedHandle")
                synchronized(this){if(closedPage?.handle==expected)closedPage=null}
                done(JSONObject().put("value",JSONObject().put("discarded",true)),null);return
            }
            if (args.has("waitingHandle")) {
                fields(args,"waitingHandle");val expected=text(args,"waitingHandle")
                val closed=synchronized(this){if(waitingPage?.handle==expected){waitingPage=null;true}else false}
                done(JSONObject().put("closed",closed),null); return
            }
            if (args.has("archiveHandle")) {
                fields(args, "archiveHandle"); val expected = text(args,"archiveHandle")
                val closed = synchronized(this) { if (phoneArchive?.handle == expected) { phoneArchive = null; true } else false }
                done(JSONObject().put("closed",closed),null); return // Release one local reader only, never stop discovery.
            }
            fields(args, "viewHandle")
            val handle = if (args.has("viewHandle")) text(args, "viewHandle") else ""
            if (handle.isBlank()) { synchronized(this) { phoneArchive = null; waitingPage = null; closedPage=null; ownerRules=null }; original.stopLanDiscovery() }
            val closed = discard(original, handle)
            done(JSONObject().put("closed", closed), null); return
        }
        require(action in COMMANDS) { "设备操作尚未接通" }
        val controlLane = action == "devices.reviewResumeRequest" || action == "devices.reviewAction" && args.optString("operation").startsWith("task.") ||
            action == "devices.confirm" && (review?.native is TaskControl || review?.kind == "resume")
        val gate = if (controlLane) controlInFlight else inFlight
        val call = UUID.randomUUID().toString()
        check(gate.compareAndSet(null, call)) { "请先等待已有设备操作回执" }
        // Original local task control uses the existing control lane, independent of slow peer reads.
        val (epoch,pendingPick)=try{
            if(controlLane && action!="devices.confirm")beginControl(original,args,action=="devices.reviewAction" && args.has("waitingHandle"))
            else generation.get() to null
        }catch(failure:Exception){gate.compareAndSet(call,null);throw failure}
        val phoneResultRead=action=="devices.queryPhoneGoalResult"
        val readRevision=if(phoneResultRead) controller.deviceWorkspaceRevision else -1L
        val ownerModelRule=action=="devices.readOwnerModelRules"||action=="devices.disableOwnerModelRule"
        val pendingInput=action=="devices.readPendingGoalInput"
        val previewRevision=if(pendingInput)controller.deviceWorkspaceRevision else -1L
        val ids = if(action=="devices.findWaitingPhoneGoals"||pendingInput||ownerModelRule||action in setOf("devices.inspectInvitation","devices.pairImage.review","devices.continueFirstPair")||action=="devices.readPhoneGoalArchive"&&args.has("localCopy")||action=="devices.openCheckedLocalFile"||action=="devices.checkLocalExportedFile"||phoneResultRead||action=="devices.readLocalReviewedPhoneGoal"||action=="devices.readReviewedPhoneInbox") emptyArray() else taskIds()
        fun taskId(): String = text(args, "taskId").also { require(it in ids) { "请从原设备任务记录选择" } }
        val work = Runnable {
            var snapshot: JSONObject? = null; var value: JSONObject? = null; var error: String? = null
            var inviteDeadline=0L;var previewDeadline=0L
            try {
                current(original, epoch)
                val before by lazy { captureSnapshot(original, ids) }
                value = when (action) {
                    "devices.discovery.start" -> {
                        fields(args, "advertise", "acknowledged"); require(bool(args, "acknowledged")) { "请先确认本次局域网查找及广播范围" }
                        original.startLanDiscovery(bool(args, "advertise")); JSONObject().put("startRequested", true)
                    }
                    "devices.discovery.reviewConnection" -> {
                        fields(args, "deviceId", "candidateId"); val id = text(args, "deviceId"); peer(before, id)
                        val native = original.reviewDiscoveredConnection(id, text(args, "candidateId"))
                        store("connection", native, native.view(), original, epoch)
                    }
                    "devices.readOwnerModelRules" -> {
                        fields(args,"here","hereInstance","hereFingerprint","peer","instance","fingerprint","epoch","offset")
                        val native=original.ownerModelRules(text(args,"here"),text(args,"hereInstance"),text(args,"hereFingerprint"),text(args,"peer"),text(args,"instance"),
                            text(args,"fingerprint"),integer(args,"epoch",1,9_007_199_254_740_991L),integer(args,"offset",0,127).toInt())
                        val view=native.view();val handle=UUID.randomUUID().toString()
                        synchronized(this){current(original,epoch);ownerRules=RulePage(handle,native,original,epoch)}
                        JSONObject().put("kind","ownerModelRules").put("rulesHandle",handle).put("view",view)
                    }
                    "devices.disableOwnerModelRule" -> {
                        fields(args,"rulesHandle","choice","acknowledged");require(bool(args,"acknowledged")) { "请先确认改为逐单审阅的影响" }
                        val choice=text(args,"choice")
                        val handle=text(args,"rulesHandle")
                        val held=synchronized(this){current(original,epoch);val page=ownerRules?.takeIf { it.handle==handle && it.window===original && it.epoch==epoch }
                            ?:error("原本机规则窗口已关闭，请重新查看");ownerRules=null;page}
                        JSONObject().put("modelRuleDisabled",held.native.disable(choice))
                    }
                    "devices.readSavedPeerScope" -> {
                        fields(args,"deviceId","instanceId","fingerprint")
                        val id=text(args,"deviceId"); val instance=text(args,"instanceId"); val fp=text(args,"fingerprint"); val selected=peer(before,id)
                        require(selected.opt("revoked")==false && selected.opt("instanceId")==instance && selected.opt("certificateFingerprint")==fp) { "请重新打开原配对伙伴" }
                        JSONObject().put("savedPeerScope",original.readSavedPeerScope(id,instance,fp))
                    }
                    "devices.changeLocalPeerName" -> {
                        fields(args,"localDeviceId","localInstanceId","localFingerprint","deviceId","instanceId","fingerprint","expected","name")
                        val here=text(args,"localDeviceId"); val hereInstance=text(args,"localInstanceId"); val hereFp=text(args,"localFingerprint")
                        val local=before.getJSONObject("local"); require(local.opt("deviceId")==here && local.opt("instanceId")==hereInstance && local.opt("certificateFingerprint")==hereFp) { "本机原身份已变化" }
                        val id=text(args,"deviceId"); val instance=text(args,"instanceId"); val fp=text(args,"fingerprint")
                        val expected=args.opt("expected") as? String ?: error("请核对旧备注"); val name=args.opt("name") as? String ?: error("请输入本机备注")
                        val selected=peer(before,id); require(selected.opt("instanceId")==instance && selected.opt("certificateFingerprint")==fp && expected.length<=128 && name.length<=128) { "请重新打开原伙伴" }
                        original.changeLocalPeerName(here,hereInstance,hereFp,id,instance,fp,expected,name)
                        JSONObject().put("localPeerNameSaved",true).put("deviceId",id).put("instanceId",instance)
                    }
                    "devices.snapshot" -> { fields(args, "deviceId"); if (args.has("deviceId")) { val id = text(args, "deviceId"); peer(before, id); original.refreshPeerFacts(id) }; JSONObject().put("readOnly", true) }
                    "devices.createInvitation" -> {
                        fields(args, "address"); val address = text(args, "address", 512)
                        require(address in strings(before.getJSONObject("actions").optJSONArray("localAddresses"))) { "请选择本机当前可用地址或已配置中继" }
                        val raw=original.createInvitation(address) // The one and only explicit creation; encoding is presentation only.
                        val elapsed=android.os.SystemClock.elapsedRealtime();val now=System.currentTimeMillis()
                        dev.pocketengine.features.devices.PairingInvitationDisplay.project(raw,now).also {
                            inviteDeadline=elapsed+(it.getLong("expiresAt")-now).coerceIn(0L,300_000L)
                        }
                    }
                    "devices.inspectInvitation" -> { fields(args,"raw");previewPair(pairRaw(args),"",original,epoch) }
                    "devices.continueFirstPair" -> {
                        fields(args,"viewHandle","acknowledged","raw","pickerId");require(bool(args,"acknowledged"))
                        val held=selected(args,original,epoch,consume=false);val first=held.native as? FirstPair?:error("请从这次首次身份预览继续")
                        require(held.kind=="firstIdentity" && (first.picker.isEmpty()==args.has("raw")) && (first.picker.isNotEmpty()==args.has("pickerId")))
                        fun visible(){current(original,epoch);require(first.picker.isEmpty()||pairingImage.current(first.picker,original)){"原选图窗口已变化"}}
                        val raw=if(first.picker.isEmpty())pairRaw(args) else {
                            require(text(args,"pickerId")==first.picker);pairingImage.read(first.picker,original,controller.webPresentationActivity().contentResolver,::visible)
                                ?:error("没有原先选择的邀请图片")
                        }
                        visible();require(selected(args,original,epoch)===held)
                        val pair=original.continueFirstIdentity(first.original,raw){visible()}
                        visible();store("pair",pair,pair.view(),original,epoch)
                    }
                    "devices.pairImage.review" -> {
                        fields(args, "pickerId"); val id = text(args, "pickerId", 36)
                        val raw = pairingImage.read(id, original, controller.webPresentationActivity().contentResolver) { current(original, epoch) }
                        if (raw == null) JSONObject().put("pickerId", id).put("cancelled", true)
                        else {
                            current(original, epoch); check(pairingImage.current(id, original)) { "原图片选择已关闭，未核对配对。" }
                            previewPair(raw,id,original,epoch)
                        }
                    }
                    "devices.reviewPairing", "devices.advancePairing" -> {
                        fields(args, "candidateId"); val id = text(args, "candidateId")
                        require(before.optJSONArray("pendingPairings").objects().any { it.optString("candidateId", it.optString("inviteId")) == id }) { "原配对候选已变化" }
                        if (action == "devices.advancePairing") { original.advancePairing(id); JSONObject().put("queriedOriginalPairing", true) }
                        else { val native = original.reviewCandidate(id); store("pair", native, native.view(), original, epoch) }
                    }
                    "devices.reviewPeerGrant" -> { fields(args, "deviceId"); val id = text(args, "deviceId"); peer(before, id); val native = original.reviewPeerGrant(id); store("grant", native, native.view(), original, epoch) }
                    "devices.reviewGrant" -> {
                        val old = selected(args, original, epoch, consume = false); val selection = choice(args, old.view)
                        require(old.kind in setOf("pair", "grant")) { "这个原审阅不接受权限选择" }
                        selected(args, original, epoch)
                        when (old.kind) {
                            "pair" -> { val native = original.reviewPairingGrant(old.native as Host.PairReview, selection); store("pair", native, native.view(), original, epoch) }
                            "grant" -> { val native = original.reviewGrantRevision(old.native as Host.GrantReview, selection); store("grant", native, native.view(), original, epoch) }
                            else -> error("这个原审阅不接受权限选择")
                        }
                    }
                    "devices.reviewConnection" -> {
                        fields(args, "deviceId", "raw", "serviceBase"); val id = text(args, "deviceId"); peer(before, id)
                        require(args.has("raw") != args.has("serviceBase"))
                        val native = if (args.has("raw")) original.reviewConnectionUpdate(id, text(args, "raw", 16384)) else {
                            val service = text(args, "serviceBase", 512)
                            require(before.getJSONObject("actions").optJSONArray("relayLeases").objects().any { it.opt("serviceBase") == service }) { "请选择已配置的原中继" }
                            original.reviewRelayConnection(id, service)
                        }
                        store("connection", native, native.view(), original, epoch)
                    }
                    "devices.reviewRelay" -> { fields(args, "raw"); val native = original.reviewRelayLease(text(args, "raw", 4096)); store("relay", native, native.view(), original, epoch) }
                    "devices.readPendingGoalInput" -> {
                        val source=waitingPending(args).first
                        val preview=controller.readPendingDeviceGoalInput(source.task,source.operation)
                        require(preview.opt("taskId")==source.task && DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(preview.getJSONObject("origin")))==source.digest)
                        val fresh=waitingPending(args);require(fresh.first==source && fresh.second.getLong("deviceStateRevision")==preview.getLong("revision"))
                        val left=minOf(25_000L,preview.getLong("viewUntil")-System.currentTimeMillis());require(left>0L)
                        previewDeadline=android.os.SystemClock.elapsedRealtime()+left
                        JSONObject().put("waitingHandle",text(args,"waitingHandle")).put("localInstanceId",source.instance).put("input",preview)
                    }
                    "devices.reviewGoal" -> if(!args.has("waitingHandle")){
                        fields(args, "taskId"); val native=original.reviewGoal(taskId());store("goal",native,native.view(),original,epoch)
                    }else{
                        val (source,raw)=waitingPending(args);val facts=original.taskActions(source.task)
                        val state=when {
                            raw.getJSONObject("deviceExecutionScope").getLong("deadline")<=System.currentTimeMillis() -> "expired"
                            raw.opt("status")=="interrupted" -> "interrupted_before_start"
                            else -> controller.retainedDeviceGoalCondition(source.task)
                        }
                        val review=facts.getJSONObject("goalReview")
                        val candidate=if(state=="retained"&&review.opt("available")==true){
                            try{original.reviewGoal(source.task)}catch(_:Exception){null}
                        }else null
                        if(candidate!=null){
                            try{
                                waitingPending(args)
                                val view=candidate.view();require(view.opt("taskId")==source.task&&DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(view.getJSONObject("origin")))==source.digest)
                                store("goal",candidate,view,original,epoch)
                            }catch(changed:Exception){original.discardReview();throw changed}
                        }else{
                            waitingPending(args)
                            JSONObject().put("kind","waitingGoalBlocked").put("waitingHandle",text(args,"waitingHandle")).put("taskId",source.task)
                            .put("operationId",source.operation).put("sourceId",source.source).put("state",if(state=="retained")"local_review_unavailable" else state)
                            .put("reason",if(state=="retained")review.optString("reason") else "").put("canCheckLocalModel",state=="retained" && review.opt("available")==true).put("canRetire",facts.getJSONObject("pendingCancel").opt("available")==true)
                        }
                    }
                    "devices.reviewResult" -> {
                        val source=if(args.has("waitingHandle"))receivedEntry(args).also {
                            require(it.reviewRun.isNotEmpty())
                            checkedReceivedSource(it,controller.readOriginalDeviceTask(it.task))
                        } else null
                        val id=source?.task ?: run { fields(args,"taskId");taskId() }
                        val native=original.reviewResult(id);val view=native.view()
                        if(source!=null&&!(view.opt("taskId")==id && view.opt("runId")==source.reviewRun &&
                                view.optInt("executorEpoch",-1)==source.reviewEpoch && view.optLong("recordedAt")==source.reviewRecordedAt &&
                                view.opt("closed")==false && view.opt("nativeRunnerExited")==true && view.opt("nativeDispatchClosed")==true &&
                                view.opt("canRecordDecision")==source.candidateState.isEmpty())) {
                            original.discardReview()
                            error("原结果的执行或审阅状态已改变；未打开下一轮或别的成果")
                        }
                        store("result",native,view,original,epoch)
                    }
                    "devices.reviewApproval" -> { fields(args, "taskId", "approvalId"); val native = original.reviewApproval(taskId(), text(args, "approvalId")); store("approval", native, native.view(), original, epoch) }
                    "devices.readTask" -> {
                        val id = taskId()
                        if (args.has("historyPageHandle")) {
                            fields(args, "taskId", "historyPageHandle", "direction")
                            val held = localHistoryPage(id, text(args, "historyPageHandle"), original, epoch)
                            val direction = text(args, "direction"); require(direction == "older" || direction == "newer")
                            // Paging the local index does not refresh or issue any task-control authority.
                            JSONObject().put("kind", "taskHistoryPage").put("taskId", id).put("history", readLocalHistoryPage(id, held.target, held, direction, original, epoch))
                        } else {
                            fields(args, "taskId", "targetDeviceId")
                            val target = if (args.has("targetDeviceId")) text(args, "targetDeviceId").also { peer(latest, it) } else ""
                            val header = controller.state.tasks.firstOrNull { it.id == id }?.raw
                            val headerOrigin=header?.optJSONObject("origin")
                            val receivedCandidate=header!=null && headerOrigin?.optString("kind")=="paired_device" &&
                                (header.optString("executionMode") in setOf("main_screen","background") ||
                                    header.opt("executionMode")=="capabilities" && (DeviceProtocol.textMarker(headerOrigin?.opt("textReturnContract"))
                                        || header.optString("receivedZeroToolAppScope") in setOf("main_screen","background")
                                        || DeviceProtocol.receivedPhoneScopeMode(header) in setOf("main_screen","background")))
                            val before=if(receivedCandidate)receivedPhoneActionScope(id) else null
                            val actions = original.taskActions(id)
                            val response = JSONObject().put("kind", "taskDetails").put("taskId", id).put("actions", actions).put("history", readLocalHistoryPage(id, target, null, "", original, epoch))
                            if (before != null) {
                                val after = receivedPhoneActionScope(id)
                                if (after != null && DeviceProtocol.canonical(before) == DeviceProtocol.canonical(after)) response.put("phoneActionScope", before)
                                else response.put("phoneActionScopeChanged", true)
                            }
                            response
                        }
                    }
                    "devices.reviewResumeRequest" -> {
                        fields(args, "taskId", "resumeRequestId", "reviewRevision"); val id = taskId(); val actions = original.taskActions(id)
                        val request = actions.optJSONObject("resumeRequest") ?: error("原任务没有待核对的恢复请求")
                        require(request.opt("ownerActionRequired") == true && request.opt("taskId") == id && request.opt("resumeRequestId") == text(args, "resumeRequestId") &&
                            request.optLong("reviewRevision", -1) == integer(args, "reviewRevision", 1, Long.MAX_VALUE)) { "原恢复请求或审阅版本已变化" }
                        val view = JSONObject(request.toString()).put("canApprove", request.opt("stale") == false && actions.getJSONObject("resume").opt("available") == true)
                        original.discardReview(); store("resume", view, view, original, epoch)
                    }
                    "devices.readReviewedPhoneInbox" -> reviewedInbox(args,original,epoch)
                    "devices.readLocalReviewedPhoneGoal" -> localReviewedPhoneGoal(args,original,epoch)
                    "devices.prepareReceivedModelReturn" -> prepareModelPlace(args,original,epoch)
                    "devices.readWaitingPhoneGoals","devices.findWaitingPhoneGoals" -> waitingGoals(args,original,epoch,action=="devices.findWaitingPhoneGoals")
                    "devices.readPhoneGoalArchive", "devices.readReceivedPhoneGoals" -> phoneArchivePage(args, original, epoch, action == "devices.readReceivedPhoneGoals")
                    "devices.openHistory" -> {
                        fields(args, "taskId", "operationId", "historyPageHandle", "archiveHandle")
                        require(!args.has("archiveHandle") || !args.has("historyPageHandle"))
                        val archive = if (args.has("archiveHandle")) archiveFor(text(args, "archiveHandle"), original, epoch) else null
                        val id = if (archive == null) taskId() else text(args, "taskId"); val operation = text(args, "operationId")
                        val selectedPage = if (args.has("historyPageHandle")) localHistoryPage(id, text(args, "historyPageHandle"), original, epoch)
                            else synchronized(this) { historyPages[id]?.takeIf { it.window === original && it.epoch == epoch } }
                        require(archive?.received != true) { "收方目录不能打开对外派发或查询其他设备" }
                        val archivedDigest = archive?.digests?.get(id to operation)
                        val target = if (archive != null) archive.target.takeIf { archivedDigest != null } else if (selectedPage != null) selectedPage.operations[operation]
                            else original.historyLinks(id).getJSONArray("links").objects().singleOrNull { it.opt("parentTaskId") == id && it.opt("operationId") == operation }?.getString("targetDeviceId")
                        require(target != null) { "原操作不在这项任务当前读取的派发页内" }
                        val native = original.openHistory(id, operation)
                        try {
                            val view = native.view()
                            require(view.opt("targetDeviceId") == target) { "原派发的目标设备已变化，未打开其他设备" }
                            if (archive != null) {
                                require(view.opt("instanceId") == archive.targetInstance && view.opt("argumentsDigest") == archivedDigest && view.opt("phoneGoalResult") == true) { "原手机派工身份或内容已变化" }
                                view.put("archiveReadOnly", true)
                            }
                            store("history", History(native, archive != null), view, original, epoch)
                        } catch (failure: Exception) { native.close(); throw failure }
                    }
                    "devices.checkLocalExportedFile" -> {
                        fields(args,"viewHandle")
                        val held = selected(args,original,epoch,consume=false)
                        val history = held.native as? History ?: error("请从原派发核实本机附件")
                        require(held.kind=="history" && !history.archiveOnly && held.view.opt("canCheckLocalExportedFile")==true)
                        history.fileOffer.set(null);history.fileHandoff.set(null)
                        val fixed=history.original.checkLocalExportedFile()
                        current(original,epoch)
                        require(selected(args,original,epoch,consume=false)===held)
                        val response=JSONObject().put("kind","originalLocalExportedFile").put("viewHandle",held.handle).put("result",fixed)
                        if(fixed.opt("state")=="verified_here"){
                            val offer=FileOffer(UUID.randomUUID().toString(),ReceivedFileSelection.fromNative(fixed),android.os.SystemClock.elapsedRealtime()+60_000)
                            history.fileOffer.set(offer);response.put("fileReview",offer.key).put("fileReviewMs",minOf(60_000L,held.expires-System.currentTimeMillis()).coerceAtLeast(0L))
                        }
                        response
                    }
                    "devices.openCheckedLocalFile" -> {
                        fields(args,"viewHandle","fileReview")
                        val held=selected(args,original,epoch,consume=false)
                        val history=held.native as? History ?: error("请选择原文件")
                        require(!history.archiveOnly && held.kind=="history" && held.view.opt("canCheckLocalExportedFile")==true)
                        val offer=requireNotNull(history.fileOffer.getAndSet(null)?.takeIf { it.key==text(args,"fileReview") &&
                            it.until>android.os.SystemClock.elapsedRealtime() }) { "原文件核验已过期，请重新核实" }
                        val fixed=ReceivedFileSelection.fromNative(history.original.checkLocalExportedFile())
                        require(fixed==offer.selected && offer.until>android.os.SystemClock.elapsedRealtime() && selected(args,original,epoch,consume=false)===held)
                        val handoff=FileHandoff(held.handle,UUID.randomUUID().toString(),fixed,offer.until)
                        history.fileHandoff.set(handoff)
                        JSONObject().put("kind","internalFileHandoff").put("handoff",handoff.key)
                    }
                    "devices.queryPhoneGoalResult" -> {
                        fields(args, "viewHandle")
                        val old = selected(args, original, epoch, consume = false)
                        require(old.kind == "history" && old.view.opt("phoneGoalResult") == true) { "请从原手机目标的派发记录读取" }
                        val result = (old.native as History).original.queryPhoneGoalResult()
                        JSONObject().put("kind", "phoneGoalResult").put("viewHandle", old.handle).put("taskId", old.view.getString("taskId"))
                            .put("operationId", old.view.getString("operationId")).put("targetDeviceId", old.view.getString("targetDeviceId")).put("result", result)
                    }
                    "devices.queryHistory", "devices.stopHistory" -> {
                        fields(args, "viewHandle", "acknowledged")
                        val old = selected(args, original, epoch, consume = false); require(old.kind == "history") { "请先打开原操作回执" }
                        val history = old.native as History
                        val result = if (action == "devices.stopHistory") {
                            require(!history.archiveOnly) { "旧手机派工目录只核对原结果，不能发出停止命令" }
                            require(bool(args, "acknowledged")) { "请明确核对这次原操作与设备范围" }
                            check(history.stopSubmitted.compareAndSet(false, true)) { "本窗口已提交原操作停止，请只查询回执" }
                            current(original, epoch); history.original.requestStop()
                        } else history.original.query()
                        JSONObject().put("kind", "historyReceipt").put("viewHandle", old.handle).put("taskId", old.view.getString("taskId"))
                            .put("operationId", old.view.getString("operationId")).put("targetDeviceId", old.view.getString("targetDeviceId"))
                            .put("stopSubmitted", history.stopSubmitted.get()).put("receipt", result)
                    }
                    "devices.reviewAction" -> {
                        val operation=text(args,"operation",40)
                        val waiting=if(args.has("waitingHandle")){
                            fields(args,"operation","waitingHandle","taskId","operationId");require(operation=="task.cancelPending")
                            checkedPending(pickedPending(requireNotNull(pendingPick),original,epoch)).first
                        }else{fields(args,"operation","deviceId","taskId","serviceBase","inviterDeviceId");null}
                        val view = JSONObject().put("operation", operation)
                        var native: Any = view
                        when (operation) {
                            "receiver.enable", "receiver.disable" -> { original.receiverAvailability(operation == "receiver.enable").also { require(it.available) { it.reason } }; view.put("local", before.getJSONObject("local")) }
                            "peer.connect", "peer.disconnect", "peer.revoke" -> { val target = peer(before, text(args, "deviceId")); val available = target.getJSONObject("actions").getJSONObject(operation.substringAfter('.')); require(available.opt("available") == true) { available.optString("reason") }; view.put("peer", target) }
                            "task.stop", "task.pause", "task.resume", "task.cancelPending" -> {
                                val id=waiting?.task ?: taskId(); val actions=original.taskActions(id)
                                val availability = actions.getJSONObject(if (operation == "task.cancelPending") "pendingCancel" else operation.substringAfter('.'))
                                require(availability.opt("available") == true) { availability.optString("reason") }
                                if (operation == "task.resume") require(actions.optJSONObject("resumeRequest")?.opt("ownerActionRequired") != true) { "请先核对对方这次原执行的恢复请求" }
                                if (operation == "task.cancelPending" || operation == "task.stop" && actions.getJSONObject("pendingCancel").opt("available") == true) {
                                    val pending = controller.reviewPendingDeviceGoalCancellation(id)
                                    val actual=pending.view();require(actual.opt("taskId")==id)
                                    if(waiting!=null)require(actual.opt("callerDeviceId")==waiting.source&&actual.opt("originDeviceId")==waiting.source
                                        &&actual.opt("operationId")==waiting.operation&&actual.opt("localOnly")==true&&actual.opt("runStarted")==false)
                                    pendingPick?.let { pickedPending(it,original,epoch) }
                                    native = TaskControl(id, "", 0, pending,pendingPick); view.put("pendingGoal", actual).put("taskId", id)
                                } else {
                                    val actual = controller.readOriginalDeviceTask(id)
                                    require(actual.opt("id") == id && actual.optJSONObject("origin")?.opt("kind") == "paired_device") { "不是这项原设备任务" }
                                    val run = text(actual, "runId"); val runEpoch = integer(actual, "coordinatorEpoch", 1, Int.MAX_VALUE.toLong()).toInt()
                                    native = TaskControl(id, run, runEpoch)
                                    view.put("taskId", id).put("runId", run).put("executorEpoch", runEpoch).put("goal", actual.optString("goal"))
                                        .put("origin", actual.optJSONObject("origin")).put("resourceBinding", actual.optJSONObject("deviceExecutionScope")?.optJSONObject("resourceBinding"))
                                }
                            }
                            "relay.remove" -> { val service = text(args, "serviceBase", 512); val inviter = text(args, "inviterDeviceId"); val lease = before.getJSONObject("actions").optJSONArray("relayLeases").objects().singleOrNull { it.opt("serviceBase") == service && it.opt("inviterDeviceId") == inviter } ?: error("原临时中继租约已变化"); view.put("lease", lease) }
                            else -> error("设备操作未接通")
                        }
                        original.discardReview(); store("action", native, view, original, epoch)
                    }
                    "devices.confirm", "devices.reject" -> {
                        fields(args, "viewHandle", "acknowledged", "useForFutureGoals", "completed", "approved","keepTextLocal")
                        if (action == "devices.confirm") require(bool(args, "acknowledged")) { "请明确核对本次设备范围与影响" }
                        val old = selected(args, original, epoch)
                        if(args.has("keepTextLocal")) require(action=="devices.confirm" && old.kind=="result" && old.view.opt("canKeepOriginalText")==true && bool(args,"completed") && bool(args,"keepTextLocal"))
                        var nativeReceipt: JSONObject? = null
                        if (action == "devices.reject") { require(old.kind == "pair"); original.rejectPairing(old.native as Host.PairReview) }
                        else {
                            require(bool(args, "acknowledged")) { "请明确核对本次设备范围与影响" }
                            when (old.kind) {
                                "pair" -> original.confirmPairing(old.native as Host.PairReview)
                                "grant" -> original.confirmGrantRevision(old.native as Host.GrantReview)
                                "connection" -> original.confirmConnectionUpdate(old.native as Host.ConnectionReview)
                                "relay" -> original.confirmRelayLease(old.native as Host.RelayLeaseReview)
                                "goal" -> original.confirmGoal(old.native as Host.GoalReview, bool(args, "useForFutureGoals"))
                                "result" -> original.confirmResult(old.native as Host.ResultReview, bool(args, "completed"),args.has("keepTextLocal"))
                                "approval" -> original.decideApproval(old.native as Host.ApprovalReview, bool(args, "approved"))
                                "abandon" -> original.confirmPairingAbandonment(old.native as Host.AbandonReview)
                                "resume" -> {
                                    val approved = bool(args, "approved"); val request = old.view
                                    require(!approved || request.opt("canApprove") == true) { "原恢复请求已经过期或原执行暂不可继续" }
                                    current(original, epoch)
                                    original.decideResumeRequest(request.getString("taskId"), request.getString("resumeRequestId"), request.getLong("reviewRevision"), approved)
                                }
                                "action" -> {
                                    val view = old.view; val operation = view.getString("operation")
                                    view.optJSONObject("peer")?.let { check(samePeer(it, peer(before, it.getString("deviceId")))) { "原设备身份或授权已变化，请重新核对" } }
                                    when (operation) {
                                        "receiver.enable", "receiver.disable" -> { check(view.getJSONObject("local").opt("receiverEnabled") == before.getJSONObject("local").opt("receiverEnabled")) { "设备接收状态已变化" }; original.setReceiverEnabled(operation == "receiver.enable") }
                                        "peer.connect" -> original.connectPeer(view.getJSONObject("peer").getString("deviceId"))
                                        "peer.disconnect" -> original.disconnectPeer(view.getJSONObject("peer").getString("deviceId"))
                                        "peer.revoke" -> original.revoke(view.getJSONObject("peer").getString("deviceId"))
                                        "task.stop", "task.pause", "task.resume", "task.cancelPending" -> {
                                            val control = old.native as TaskControl
                                            current(original, epoch);control.pick?.let { pickedPending(it,original,epoch) }
                                            nativeReceipt = if(control.pending!=null){
                                                val reply=controller.confirmPendingDeviceGoalCancellation(control.pending)
                                                localDeclineReceipt(reply,old.view.getJSONObject("pendingGoal"),control.taskId)
                                            }
                                                else controller.controlOriginalDeviceTask(control.taskId, control.runId, control.epoch, if (operation == "task.stop") "cancel" else operation.substringAfter('.'))
                                            controller.deviceTaskWorkspaceChanged(control.taskId)
                                        }
                                        "relay.remove" -> {
                                            val lease = view.getJSONObject("lease")
                                            val actual = before.getJSONObject("actions").optJSONArray("relayLeases").objects().singleOrNull { it.opt("serviceBase") == lease.opt("serviceBase") && it.opt("inviterDeviceId") == lease.opt("inviterDeviceId") }
                                            require(actual != null && DeviceProtocol.canonical(actual) == DeviceProtocol.canonical(lease)) { "原中继租约已变化，请重新核对" }
                                            current(original, epoch); original.removeRelayLease(lease.getString("serviceBase"), lease.getString("inviterDeviceId"))
                                        }
                                    }
                                }
                                else -> error("原审阅不接受确认")
                            }
                        }
                        JSONObject().put("kind", old.kind).put("operation", old.view.optString("operation", action)).put("nativeCallReturned", true).put("receipt", nativeReceipt ?: JSONObject.NULL)
                    }
                    "devices.recoveryAddresses" -> { fields(args); JSONObject().put("addresses", JSONArray(original.recoveryAddresses())) }
                    "devices.openRecovery" -> {
                        fields(args, "inviteId", "address"); val id = text(args, "inviteId"); val address = text(args, "address", 512)
                        require(before.optJSONArray("committedPairingsToRecover").objects().any { it.opt("inviteId") == id }) { "请从原已提交配对记录选择" }
                        require(address in original.recoveryAddresses()); val native = original.openCommittedRecovery(id, address); store("recovery", native, native.view(), original, epoch)
                    }
                    "devices.recoverPairing" -> {
                        fields(args, "inviteId", "host", "port"); val id = text(args, "inviteId")
                        require(before.optJSONArray("pairingOffersToRecover").objects().any { it.opt("inviteId") == id }) { "请从原本机待配对记录选择" }
                        original.recoverPairing(id, text(args, "host", 512), integer(args, "port", 0, 65535).toInt()); JSONObject().put("queriedOriginalPairing", true)
                    }
                    "devices.reviewAbandonment" -> { fields(args, "inviteId"); val id = text(args, "inviteId"); require(before.optJSONArray("pairingOffersToRecover").objects().any { it.opt("inviteId") == id }); val native = original.reviewPairingAbandonment(id); store("abandon", native, native.view(), original, epoch) }
                    else -> error("设备操作尚未接通")
                }
                current(original, epoch)
            } catch (failure: Exception) { if (action == "devices.discovery.start" && runCatching { current(original, epoch) }.isFailure) original.stopLanDiscovery(); error = if (failure is org.json.JSONException) "原设备信息不完整，请刷新核对。" else failure.message?.take(400) ?: "设备操作结果待核对" }
            if (value?.optString("kind")!="firstIdentity" && !phoneResultRead && !pendingInput && !ownerModelRule && action !in setOf("devices.prepareReceivedModelReturn","devices.openCheckedLocalFile","devices.checkLocalExportedFile","devices.readPhoneGoalArchive","devices.readReceivedPhoneGoals","devices.readWaitingPhoneGoals","devices.findWaitingPhoneGoals","devices.readLocalReviewedPhoneGoal","devices.readReviewedPhoneInbox") && (action != "devices.readTask" || !args.has("historyPageHandle"))) {
                try { current(original, epoch); snapshot = captureSnapshot(original, ids) } catch (_: Exception) { }
            }
            val result = JSONObject().put("value", value ?: JSONObject.NULL).put("snapshot", snapshot ?: JSONObject.NULL)
            main.post {
                gate.compareAndSet(call, null)
                if (runCatching { current(original, epoch) }.isFailure) done(null, "原设备窗口已变化，请读取原回执，不要重复提交")
                else {
                    if(pendingInput && error==null && runCatching {
                        val (source,raw)=waitingPending(args);val preview=result.getJSONObject("value").getJSONObject("input")
                        require(previewRevision==controller.deviceWorkspaceRevision && preview.opt("taskId")==source.task && raw.getLong("deviceStateRevision")==preview.getLong("revision")
                            && controller.retainedDeviceGoalCondition(source.task)=="retained")
                        val left=minOf(preview.getLong("viewUntil")-System.currentTimeMillis(),previewDeadline-android.os.SystemClock.elapsedRealtime())
                        require(left>0L);preview.put("remainingMs",left)
                    }.isFailure){done(null,"原派单、准入或这次阅读窗口已变化，未交付内容");return@post}
                    if(action=="devices.createInvitation" && error==null){
                        val invite=result.getJSONObject("value")
                        val left=minOf(invite.getLong("expiresAt")-System.currentTimeMillis(),inviteDeadline-android.os.SystemClock.elapsedRealtime())
                        if(left<=0L){done(null,"这次邀请已到期，没有重建或重新显示。");return@post}
                        invite.put("remainingMs",left)
                    }
                    if((action=="devices.readWaitingPhoneGoals"||action=="devices.findWaitingPhoneGoals") && error==null && runCatching {
                        waitingFor(result.getJSONObject("value").getString("waitingHandle"),original,epoch)
                    }.isFailure){done(null,"本机收件目录已变化，未交付过期的记录");return@post}
                    if(action=="devices.readReviewedPhoneInbox" && error==null && runCatching {
                        closedFor(result.getJSONObject("value").getString("closedHandle"),original,epoch)
                    }.isFailure){done(null,"本机目录窗口已改变，未交付旧目录");return@post}
                    if(action=="devices.checkLocalExportedFile" && error==null && runCatching {
                        val held=selected(args,original,epoch,consume=false)
                        val fixed=result.getJSONObject("value").getJSONObject("result")
                        require(held.kind=="history" && held.view.opt("canCheckLocalExportedFile")==true && held.handle==result.getJSONObject("value").opt("viewHandle")
                            && listOf("taskId","operationId","targetDeviceId","instanceId","argumentsDigest").all { held.view.opt(it)==fixed.opt(it) }
                            && held.view.opt("exportRequestRunId")==fixed.opt("requestRunId") && held.view.getLong("exportRequestEpoch")==fixed.getLong("requestExecutorEpoch"))
                    }.isFailure) { done(null,"原文件核验窗口已变化，不展示旧的本机结果"); return@post }
                    if(action=="devices.readLocalReviewedPhoneGoal" && error==null && runCatching {
                        val held=localTextLeaf(args,original,epoch);val leaf=result.getJSONObject("value");val source=held.origin
                        require(leaf.opt("archiveHandle")==held.handle && leaf.opt("taskId")==source.task && leaf.opt("operationId")==source.operation
                            && leaf.opt("sourceId")==source.source && leaf.opt("localInstance")==source.instance && (held.stamp.isEmpty()||held.stamp==leaf.opt("stamp")))
                        if(args.has("openMatch"))require(leaf.opt("part")==held.matchPart && leaf.optInt("page",-1)==held.matchPage)
                        else require(leaf.optString("part","result")==args.optString("part","result"))
                    }.isFailure){done(null,"本机原文窗口已变化，未向界面交付文字");return@post}
                    val text=if(phoneResultRead) result.optJSONObject("value")?.optJSONObject("result") else null
                    if(text!=null && text.optString("summary").isNotEmpty()){
                        val left=minOf(text.optLong("textReadRemainingMs"),text.optLong("textReadUntil")-System.currentTimeMillis())
                        if(left<=0L || readRevision!=controller.deviceWorkspaceRevision){
                            done(null,"原设备状态或这次读取时限已变化，未向界面交付正文。请手动重读。")
                            return@post
                        }
                        text.put("textReadRemainingMs",left)
                    }
                    snapshot?.let { latest = JSONObject(it.toString()) }
                    done(PocketWebState.publicValue(result) as JSONObject, error)
                }
            }
        }
        try {
            if (controlLane) controller.runDeviceWorkspaceControlWork(work) else controller.runDeviceWorkspaceWork(work)
        } catch (failure: Exception) { gate.compareAndSet(call, null); done(null, failure.message ?: "原设备工作线程不可用") }
    }
}
