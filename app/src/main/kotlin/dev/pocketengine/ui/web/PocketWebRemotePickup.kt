package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.remote.RemotePhonePickup
import dev.pocketengine.ui.PocketWorldController
import java.util.UUID
import org.json.JSONObject

/** The phone-only repair has no RemoteOwnerWorkflows window: opening its review must not poll the host. */
internal class PocketWebRemotePickup(private val controller: PocketWorldController, private val capture: () -> PocketWebWindowTicket,
    private val accepts: (PocketWebWindowTicket) -> Boolean, private val taskView: () -> String?) {
    companion object {
        const val REVIEW = "remoteTask.reviewPhoneCopy"
        const val LINK = "remoteTask.linkPhoneCopy"
        val COMMANDS = setOf(REVIEW, LINK)
        private val pin = setOf("taskId","runId","coordinatorEpoch","jobId","snapshotId","snapshotVersion","path","bytes","fileVersion","sha256")
    }
    private data class Pending(val token:String,val task:String,val ticket:PocketWebWindowTicket,val expires:Long,val check:RemotePhonePickup.Review)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0L
    private var pending:Pending? = null
    fun clear(){generation++;pending=null}
    fun dispatch(action:String,args:JSONObject,done:(JSONObject?,String?)->Unit){
        val review=action==REVIEW
        require(args.keys().asSequence().toSet()==if(review)pin else setOf("taskId","reviewId")) { "请选择原快照中的一个文件" }
        val task=args.getString("taskId");require(task.isNotBlank()&&task.length<=80)
        val window=capture(); val remembered=pending
        if(!review)require(remembered!=null && remembered.task==task && remembered.ticket==window &&
            remembered.token==args.opt("reviewId") && SystemClock.elapsedRealtime()<remembered.expires) { "核对已过期，请重查这份手机原件" }
        if(review)require(args.toString().length<=4096&&args.getString("path").length in 1..1024) { "原文件位置超出可核对范围" }
        clear();val turn=generation
        fun current() = generation==turn && accepts(window) && controller.webPrivateOwnerVisible() && taskView()==task &&
            (review || SystemClock.elapsedRealtime()<remembered!!.expires)
        check(current()) { "请先回到这一个私人主机任务" }
        val ctrl=RunControl().apply{setAuthorityCheck { check(current()) { "原任务窗口已变化" } }}
        val fixed=JSONObject(args.toString())
        controller.runEcosystemControlWork(Runnable {
            val observed=runCatching {
                ctrl.check()
                if(review)controller.offlineRemotePickup.review(fixed,ctrl) to null
                else null to controller.offlineRemotePickup.confirm(remembered!!.check,ctrl)
            }
            main.post {
                if(!current())done(null,if(review)"原任务窗口已变化，未保留读取结果" else "未收到这次本机关联回执；请在原任务核对，没有自动重新处理")
                else observed.fold({ (checked,linked) ->
                    when {
                        linked!=null->{controller.getTask(task);done(linked,null)}
                        checked==null->done(JSONObject(fixed.toString()).put("state","not_in_phone_book").put("networkUsed",false),null)
                        else->{
                            val prepared=Pending(UUID.randomUUID().toString(),task,window,SystemClock.elapsedRealtime()+120_000,checked)
                            pending=prepared;done(checked.receipt().put("reviewId",prepared.token),null)
                        }
                    }
                },{done(null,it.message?:"无法核对原文件；未重新下载或提交作业")})
            }
        })
    }
}
