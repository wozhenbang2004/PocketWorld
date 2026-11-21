package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import dev.pocketengine.features.devices.DeviceProtocol
import dev.pocketengine.features.devices.DirectedPhoneTarget
import dev.pocketengine.ui.PocketWorldController
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/** A single explicit original Work read. Device archive controls and replay never own this visit. */
internal class PocketWebSentPhoneResult(
    private val controller: PocketWorldController,
    private val capture: () -> PocketWebWindowTicket,
    private val accepts: (PocketWebWindowTicket) -> Boolean,
    private val shownWork: () -> String?,
) {
    companion object {
        const val ARG="originalSentPhoneText"
    }
    private data class Work(val task: String,val run: String,val epoch: Int,val target: DirectedPhoneTarget,val requestDigest: String)
    private data class Visit(val work: Work,val client: String,val ticket: PocketWebWindowTicket,val deviceRevision: Long)
    private val active=AtomicReference<Visit?>(null)
    private val main=Handler(Looper.getMainLooper())
    private val closedMessage="原工作或手机读取窗口已变化，未展示结果。"
    fun clear(){active.set(null)}

    private fun original(task:String,run:String,epoch:Int):Work? {
        if(shownWork()!=task)return null
        val row=controller.state.tasks.singleOrNull{it.id==task}?:return null
        val raw=row.raw;val work=raw.optJSONObject("ownerDeviceWork")?:return null
        if(raw.opt("id")!=task||raw.opt("runId")!=run||raw.optInt("coordinatorEpoch",-1)!=epoch||
            raw.opt("actorId")!="owner"||raw.opt("spaceId")!="private"||raw.opt("kind")!="assistant"||
            raw.opt("executionMode")!="capabilities"||raw.opt("conversationId")!=""||!raw.isNull("origin")||
            !raw.isNull("parentTaskId")||raw.has("ownerPhoneControl")||raw.opt("taskProjection")!="detail_window"||
            raw.opt("taskCoreReadLimited")!=false||raw.opt("contextAvailable")==false||raw.opt("contextWithdrawn")==true||
            raw.opt("sourceCurrentReadRequired")==true||work.opt("format")!="pocketworld.owner-device-work.v1"||
            work.opt("requestId")!=task||work.has("directWorkspace"))return null
        val target=runCatching{DirectedPhoneTarget.fromTask(raw)}.getOrNull()?:return null
        val digest=work.optString("requestDigest")
        return if(digest.matches(Regex("[a-f0-9]{64}")))Work(task,run,epoch,target,digest)else null
    }

    private fun current(visit:Visit):Boolean {
        val work=visit.work
        return active.get()===visit&&accepts(visit.ticket)&&controller.deviceWorkspaceRevision==visit.deviceRevision&&
            original(work.task,work.run,work.epoch)==work
    }

    fun close(args:JSONObject):JSONObject {
        PocketWebGroupReviews.fields(args,ARG,"clientRequest")
        require(args.opt(ARG)==true)
        val client=DeviceProtocol.identifier(args.opt("clientRequest"),"clientRequest")
        active.get()?.let { if(it.client==client&&shownWork()==it.work.task&&accepts(it.ticket))active.compareAndSet(it,null) }
        return JSONObject().put("closed",true)
    }

    fun read(args:JSONObject,done:(JSONObject?,String?)->Unit) {
        PocketWebGroupReviews.fields(args,ARG,"taskId","runId","epoch","operationId","childTaskId","childRunId","childEpoch","clientRequest")
        require(args.opt(ARG)==true)
        val task=DeviceProtocol.identifier(args.opt("taskId"),"taskId");val run=DeviceProtocol.identifier(args.opt("runId"),"runId")
        val epoch=DeviceProtocol.integer(args.opt("epoch"),"epoch",1,Int.MAX_VALUE.toLong()).toInt()
        val operation=DeviceProtocol.identifier(args.opt("operationId"),"operationId")
        val child=DeviceProtocol.identifier(args.opt("childTaskId"),"childTaskId")
        val childRun=DeviceProtocol.identifier(args.opt("childRunId"),"childRunId")
        val childEpoch=DeviceProtocol.integer(args.opt("childEpoch"),"childEpoch",1,Int.MAX_VALUE.toLong())
        val client=DeviceProtocol.identifier(args.opt("clientRequest"),"clientRequest")
        val work=checkNotNull(original(task,run,epoch)){"请打开原工作这次执行的概览。"}
        val visit=Visit(work,client,capture(),controller.deviceWorkspaceRevision)
        check(active.compareAndSet(null,visit)){"正在读取原手机这次结果。"}
        try {
            controller.runDeviceWorkspaceWork(Runnable {
                val result=runCatching {
                    check(current(visit)){closedMessage}
                    val host=controller.nativeDeviceWorkspace()?:error("本机设备读取不可用。")
                    host.openOwnerWindow().use {
                        it.readOriginalSentPhoneResult(task,run,epoch,operation,child,childRun,childEpoch){check(current(visit)){closedMessage}}
                    }
                }
                main.post {
                    val shown=current(visit)
                    active.compareAndSet(visit,null)
                    if(!shown)done(null,closedMessage)
                    else result.fold({ value ->
                        val original=value.getJSONObject("result")
                        if(original.optString("summary").isNotEmpty()&&original.optLong("textReadUntil")<=System.currentTimeMillis())
                            done(null,"原文字读取时间已结束，未保留正文。")
                        else done(value,null)
                    },{failure ->
                        val reason=when(failure){
                            is java.io.IOException -> if(failure.message=="原手机未连接，未读取结果。") failure.message
                                else "没有收到原手机的可核对答复。连接可能中断，对端也可能未放行。"
                            is SecurityException -> "原手机、原执行或读取授权暂不可核对，未展示其他结果。"
                            else -> "没有取得原手机可核对的这次结果，不能据此判断没有成果。"
                        }
                        done(null,reason)
                    })
                }
            })
        }catch(failure:Exception){active.compareAndSet(visit,null);throw failure}
    }
}
