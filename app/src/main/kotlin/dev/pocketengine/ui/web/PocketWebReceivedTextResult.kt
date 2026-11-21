package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import dev.pocketengine.features.devices.DeviceProtocol
import dev.pocketengine.features.devices.NativeDeviceWorkspaceHost as Host
import dev.pocketengine.store.TaskStore
import dev.pocketengine.ui.PocketWorldController
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/** Only the receiving phone's exact current Work can open this separate, local owner review. */
internal class PocketWebReceivedTextResult(
    private val controller: PocketWorldController,
    private val capture: () -> PocketWebWindowTicket,
    private val accepts: (PocketWebWindowTicket) -> Boolean,
    private val shownWork: () -> String?,
) {
    companion object {
        const val READ="receivedPhone.result.read"
        const val CONFIRM="receivedPhone.result.confirm"
        const val CLOSE="receivedPhone.result.close"
        val COMMANDS=setOf(READ,CONFIRM,CLOSE)
    }
    private val main=Handler(Looper.getMainLooper())
    private class Visit(val target:Host.ReceivedTextResultOrigin,val ticket:PocketWebWindowTicket,val window:Host.Window,val clientRequest:String) {
        val handle=UUID.randomUUID().toString()
        @Volatile var review:Host.ResultReview?=null
        @Volatile var resultDigest=""
    }
    private val active=AtomicReference<Visit?>(null)
    private fun current(visit:Visit) {
        check(active.get()===visit && shownWork()==visit.target.taskId() && accepts(visit.ticket)) { "原文字工作的核对窗口已关闭" }
    }
    private fun retire(visit:Visit) { if(active.compareAndSet(visit,null))visit.window.close() }
    fun clear() { active.getAndSet(null)?.window?.close() }

    private fun target(args:JSONObject):Host.ReceivedTextResultOrigin {
        PocketWebGroupReviews.fields(args,"taskId","runId","epoch","operationId","recordedAt","clientRequest")
        val task=DeviceProtocol.identifier(args.opt("taskId"),"taskId")
        val run=DeviceProtocol.identifier(args.opt("runId"),"runId")
        val epoch=DeviceProtocol.integer(args.opt("epoch"),"epoch",1,Int.MAX_VALUE.toLong()).toInt()
        val operation=DeviceProtocol.identifier(args.opt("operationId"),"operationId")
        val at=DeviceProtocol.integer(args.opt("recordedAt"),"recordedAt",1,DeviceProtocol.MAX_SAFE_INTEGER)
        check(shownWork()==task) { "请从原接收工作的概览核对这次成果" }
        val display=controller.state.tasks.singleOrNull { it.id==task } ?: error("没有当前原工作")
        val raw=display.raw; val origin=raw.getJSONObject("origin")
        val sourceId=DeviceProtocol.deviceId(origin.opt("callerDeviceId"),"sourceDeviceId")
        val targetId=DeviceProtocol.deviceId(origin.opt("targetDeviceId"),"targetDeviceId")
        check(raw.opt("id")==task && display.status=="needs_confirmation" && raw.opt("status")==display.status &&
            raw.opt("runId")==run && raw.optInt("coordinatorEpoch",-1)==epoch && raw.opt("cancelRequested")==false &&
            raw.opt("kind")=="assistant" && raw.opt("executionMode")=="capabilities" &&
            raw.opt("contextAvailable")!=false && raw.opt("contextWithdrawn")!=true &&
            raw.opt("sourceCurrentReadRequired")!=true && raw.opt("taskProjection")!="source_unavailable" &&
            raw.opt("actorId")=="paired_device:$sourceId" && raw.opt("spaceId")=="device:$sourceId" &&
            origin.opt("kind")=="paired_device" && origin.opt("textReturnContract")==DeviceProtocol.ANDROID_TEXT_RETURN &&
            origin.opt("originDeviceId")==sourceId && origin.opt("remoteOperationId")==operation && sourceId!=targetId) {
            "不是当前这一轮原文字工作"
        }
        val candidate=raw.optJSONObject("deviceGoalResult")
        val shownDigest=if(candidate!=null) {
            check(candidate.opt("runId")==run && candidate.optInt("executorEpoch",-1)==epoch && candidate.optLong("recordedAt")==at &&
                candidate.opt("status")=="needs_confirmation" && candidate.opt("closed")==false &&
                candidate.opt("nativeRunnerExited")==true && candidate.opt("nativeDispatchClosed")==true)
            TaskStore.capabilityFingerprint(candidate)
        }else{
            val slot=raw.getJSONObject("receivedPhoneResultSlot")
            check(slot.opt("runId")==run && slot.optInt("epoch",-1)==epoch && slot.optLong("recordedAt")==at)
            "" // The persisted candidate digest is captured by the exact TaskStore review below.
        }
        return Host.ReceivedTextResultOrigin(task,run,epoch,operation,at,sourceId,targetId,
            DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(origin)),shownDigest)
    }

    private fun wire(visit:Visit,kind:String):JSONObject {
        val t=visit.target
        return JSONObject().put("kind",kind).put("handle",visit.handle).put("taskId",t.taskId()).put("runId",t.runId())
            .put("epoch",t.epoch()).put("operationId",t.operationId()).put("recordedAt",t.recordedAt())
            .put("sourceDeviceId",t.sourceDeviceId()).put("resultDigest",visit.resultDigest)
    }

    fun dispatch(action:String,args:JSONObject,done:(JSONObject?,String?)->Unit) {
        if(action==READ){
            val selected=target(args);val ticket=capture()
            val clientRequest=DeviceProtocol.identifier(args.opt("clientRequest"),"clientRequest")
            val window=requireNotNull(controller.nativeDeviceWorkspace()) { "本机原接收工作区尚不可用" }.openOwnerWindow()
            val visit=Visit(selected,ticket,window,clientRequest)
            if(!active.compareAndSet(null,visit)){window.close();error("请先关闭当前原成果核对")}
            submit(visit,done){
                val review=window.reviewResult(selected)
                val value=review.view()
                val digest=value.getString("resultDigest")
                check(value.opt("taskId")==selected.taskId() && value.opt("runId")==selected.runId() &&
                    value.optInt("executorEpoch",-1)==selected.epoch() && value.optLong("recordedAt")==selected.recordedAt() &&
                    value.opt("closed")==false && value.opt("canRecordDecision")==true &&
                    value.opt("textResultReader")==selected.sourceDeviceId() && value.opt("canKeepOriginalText")==true &&
                    digest.matches(Regex("[a-f0-9]{64}")) && (selected.shownResultDigest().isEmpty()||digest==selected.shownResultDigest()))
                val text=value.opt("summary") as? String ?: error("没有可核对的原文字")
                require(text.length<=24_000) { "原文字超出本页阅读范围，请从本机设备工作核对" }
                visit.resultDigest=digest
                visit.review=review
                wire(visit,"receivedPhoneResultReview").put("summary",text).put("canKeepOriginalText",true).put("readOnly",true)
            }
            return
        }
        if(action==CLOSE){
            PocketWebGroupReviews.fields(args,"clientRequest")
            val request=DeviceProtocol.identifier(args.opt("clientRequest"),"clientRequest")
            val visit=active.get()?.takeIf { it.clientRequest==request }
            if(visit!=null)retire(visit)
            done(JSONObject().put("closed",visit!=null),null)
            return
        }
        check(action==CONFIRM)
        PocketWebGroupReviews.fields(args,"handle","acknowledged","completed","keepTextLocal")
        val handle=DeviceProtocol.identifier(args.opt("handle"),"handle")
        val visit=active.get()?.takeIf { it.handle==handle } ?: error("原成果核对已关闭")

        current(visit)
        check(args.opt("acknowledged")==true)
        val completed=args.opt("completed") as? Boolean ?: error("请明确是否确认完成")
        val keep=args.opt("keepTextLocal") as? Boolean ?: error("请明确是否保留原文字")
        check(!keep||completed)
        val original=requireNotNull(visit.review)
        // One explicit decision; only the original native compare-and-set can record it.
        visit.review=null
        submit(visit,done,decision=true){
            visit.window.receivedTextResultProof(visit.target,visit.resultDigest,null,false)
            current(visit)
            visit.window.confirmResult(original,completed,keep)
            val recorded=visit.window.receivedTextResultProof(visit.target,visit.resultDigest,completed,keep)
            wire(visit,"receivedPhoneResultDecision").put("decisionRecorded",recorded.getBoolean("decisionRecorded"))
                .put("completed",recorded.getBoolean("completed")).put("keepTextLocal",recorded.getBoolean("keepTextLocal"))
                .put("reviewedAt",recorded.getLong("reviewedAt")).put("sentToPeer",false)
        }
    }

    private fun submit(visit:Visit,done:(JSONObject?,String?)->Unit,decision:Boolean=false,work:()->JSONObject) {
        try {
            controller.runDeviceWorkspaceWork(Runnable {
                val result=runCatching { current(visit); work() }
                main.post {
                    val deliver=runCatching {
                        current(visit)
                        val value=result.getOrThrow()
                        if(!decision)visit.window.receivedTextResultProof(visit.target,visit.resultDigest,null,false)
                        current(visit)
                        value
                    }
                    if(decision||deliver.isFailure)retire(visit)
                    deliver.fold({done(it,null)},{done(null,if(decision) "原决定尚未核对到保存，请回这份原工作查看，勿重复确认。"
                        else it.message ?: "原成果已变化，未显示另一轮的内容")})
                }
            })
        } catch (failure:Exception) {
            retire(visit)
            throw failure
        }
    }
}
