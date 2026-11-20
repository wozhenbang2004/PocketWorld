package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.local.LocalModule
import dev.pocketengine.features.local.project.OriginalNodeWebOutput
import dev.pocketengine.features.workspace.WorkspaceArtifacts
import dev.pocketengine.store.TaskStore
import java.util.UUID
import org.json.JSONObject

/** Registered original owner task window only. The Web document never supplies paths or a file body. */
internal class PocketWebFirstNodeWebOutput(
    private val tasks: () -> TaskStore, private val local: () -> LocalModule, private val work:(Runnable)->Unit,
    private val capture: () -> PocketWebWindowTicket, private val accepts: (PocketWebWindowTicket) -> Boolean,
    private val selected: () -> String?
) {
    companion object {
        const val READ="projects.firstWebOutput.read"
        const val REVIEW="projects.firstWebOutput.review"
        const val SAVE="projects.firstWebOutput.save"
        val COMMANDS=setOf(READ,REVIEW,SAVE)
    }
    private data class Position(val task:String,val run:String,val epoch:Int)
    private data class Offer(val ref:Position,val build:String,val id:String,val review:OriginalNodeWebOutput.Review,
        val window:PocketWebWindowTicket,val until:Long)
    private val main=Handler(Looper.getMainLooper())
    private var offer:Offer?=null
    @Volatile private var active:RunControl?=null
    fun clear(){offer=null;active?.cancel()}
    private fun current(ref:Position,window:PocketWebWindowTicket)=accepts(window)&&selected()==ref.task
    fun dispatch(action:String,args:JSONObject,done:(JSONObject?,String?)->Unit){
        val keys=setOf("taskId","runId","epoch")+(if(action==READ) emptySet() else setOf("buildOperationId"))+
            (if(action==SAVE) setOf("reviewHandle") else emptySet())
        require(args.keys().asSequence().toSet()==keys&&keys.filter{it!="epoch"}.all{args.opt(it) is String})
        val epoch=args.get("epoch")
        require(epoch is Int || epoch is Long)
        val ref=Position(args.getString("taskId"),args.getString("runId"),args.getInt("epoch"))
        require(ref.task.matches(Regex("[A-Za-z0-9_-]{1,80}"))&&ref.run.length in 1..240&&ref.epoch>0&&ref.epoch.toLong()==(epoch as Number).toLong())
        val op=args.optString("buildOperationId")
        if(action!=READ)require(op.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val window=capture()
        check(current(ref,window)&&active==null){"请回正在查看的原构建，等待当前核对"}
        val remembered=offer
        if(action==SAVE)check(remembered!=null&&remembered.ref==ref&&remembered.build==op&&remembered.id==args.getString("reviewHandle")&&
            remembered.window==window&&SystemClock.elapsedRealtime()<remembered.until){"请重新审阅原清单；没有保存"}
        offer=null
        val control=RunControl();active=control
        try { work(Runnable {
            var next:Offer?=null
            fun requireCurrent(){
                control.check();check(active===control&&current(ref,window)){"原任务窗口已离开，不会保存或打开其他网页"}
            }
            val result=runCatching{
                requireCurrent()
                val store=tasks()
                val build=store.firstWebBuildForOwner(ref.task,ref.run,ref.epoch,op.takeIf{action!=READ})
                if(build==null){
                    check(action==READ){"尚没有这轮实际成功的原构建"}
                    return@runCatching JSONObject().put("eligible",false)
                        .put("message","这次未核对到可保存的完整网页，不会用旧输出补回。已记录的构建退出与此前保存的成品不受影响。")
                }
                val state=store.firstWebOutputForOwner(build)
                when(action){
                    READ->{
                        // A pending save may have completed in the native catalog before a close.
                        // Only inspect that exact archive; this has no way to create one.
                        if(state.optString("state")=="pending"){
                            val pin=state.getJSONObject("savedPin")
                            requireCurrent()
                            local().findFirstWebOutputForOwner(build,pin)?.let { store.finishFirstWebOutputForOwner(it) } ?: state
                        } else state
                    }
                    REVIEW->{
                        check(state.optString("state")=="ready"){"请查看这笔原保存，不再创建另一份"}
                        val review=local().reviewFirstWebOutputForOwner(build,control)
                        requireCurrent()
                        val token=Offer(ref,op,UUID.randomUUID().toString(),review,window,SystemClock.elapsedRealtime()+120_000L)
                        next=token
                        review.view().put("reviewHandle",token.id).put("expiresAt",System.currentTimeMillis()+120_000L)
                    }
                    SAVE->{
                        check(state.optString("state")=="ready"){"已有这笔原保存；不再次写入"}
                        val approved=remembered?:error("请查看同一原审阅")
                        OriginalNodeWebOutput.requirePin(build,approved.review.pin())
                        try {
                            val actual=local().saveFirstWebOutputForOwner(approved.review,control,{requireCurrent()}){
                                requireCurrent();check(SystemClock.elapsedRealtime()<approved.until){"原固定网页审阅已过期"}
                                store.beginFirstWebOutputForOwner(approved.review,control)
                            }
                            // Settle the original intent even if navigation moved away. The main thread
                            // still suppresses late results for the old window.
                            store.finishFirstWebOutputForOwner(actual)
                        } catch (rejected:WorkspaceArtifacts.QuotaBeforePublication) {
                            store.recordFirstWebQuotaRefusal(approved.review,rejected)
                        }
                    }
                    else-> error("未知网页成果操作")
                }.also{it.remove("savedPin");it.put("eligible",true)}
            }
            main.post{
                if(active===control)active=null
                if(!current(ref,window)||control.isCancelled)done(null,"已离开原构建。回来只核对这笔保存，不会重建。")
                else result.fold({r->offer=next;next?.let { token->main.postDelayed({if(offer===token)offer=null},120_000L) };done(r,null)},
                    { done(null,it.message?:"未核对到这份原网页，不会改用其他产物") })
            }
        })}catch(error:Exception){active=null;throw error}
    }
}
