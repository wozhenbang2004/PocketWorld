package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.pocketengine.ui.PocketWorldController
import java.util.UUID
import org.json.JSONObject

/** Separate owner-only phone ledger; no live host/remote review or send action is available here. */
internal class PocketWebRemotePhoneDrafts(private val controller:PocketWorldController,private val capture:()->PocketWebWindowTicket,
    private val accepts:(PocketWebWindowTicket)->Boolean,private val showing:()->String?){
    companion object{
        private const val PREFIX="remoteTask.phoneDrafts."
        val COMMANDS=setOf(PREFIX+"list",PREFIX+"read",PREFIX+"withdraw")
    }
    private data class Review(val task:String,val run:String,val epoch:Int,val ticket:PocketWebWindowTicket,val pin:String,
        val sha:String,val handle:String,val until:Long)
    private val main=Handler(Looper.getMainLooper());@Volatile private var turn=0L
    private var selected:Review?=null
    fun clear(){turn++;selected=null}
    fun dispatch(action:String,args:JSONObject,done:(JSONObject?,String?)->Unit){
        val mode=action.removePrefix(PREFIX)
        val required=setOf("taskId","runId","coordinatorEpoch")+when(mode){"list"->emptySet();"read"->setOf("id","revision","scopeHash");"withdraw"->setOf("reviewHandle");else->error("未知本机原稿操作")}
        require(args.keys().asSequence().toSet()==required&&args.toString().length<=2048){"请选择这一执行轮次的一份本机原稿"}
        val task=args.getString("taskId");val run=args.getString("runId");val n=args.opt("coordinatorEpoch")
        require(task.length in 1..80&&run.length in 1..80&&(n is Int||n is Long)&&(n as Number).toLong() in 0L..Int.MAX_VALUE.toLong())
        val epoch=(n as Number).toInt();val ticket=capture();val review=selected
        val writing=mode=="withdraw"
        if(writing)check(review!=null&&review.task==task&&review.run==run&&review.epoch==epoch&&review.ticket==ticket&&
            review.handle==args.opt("reviewHandle")&&SystemClock.elapsedRealtime()<review.until){"本机原稿审阅已结束，请重读原清单；未撤回主机操作"}
        clear();val generation=turn
        fun owner()=generation==turn&&accepts(ticket)&&controller.webPrivateOwnerVisible()&&showing()==task&&(!writing||SystemClock.elapsedRealtime()<review!!.until)
        check(owner()){"请回到这个原私人主机任务"}
        val pin=if(writing)JSONObject(review!!.pin) else JSONObject(args.toString())
        controller.runEcosystemControlWork(Runnable {
            val outcome=runCatching{check(owner());controller.readOrWithdrawPhoneDraft(pin,mode,review?.sha,::owner)}
            main.post {
                if(!owner())done(null,if(writing)"本机撤下回执未收到，请回原清单核对；没有自动重试或撤回主机指令" else "原任务窗口已变化，没有保留原稿正文")
                else outcome.fold({out->
                    if(mode=="read"){
                        val item=out.getJSONObject("item");val handle=UUID.randomUUID().toString()
                        selected=Review(task,run,epoch,ticket,pin.toString(),item.getString("textSha256"),handle,SystemClock.elapsedRealtime()+120000)
                        out.put("reviewHandle",handle)
                    }
                    if(writing)controller.getTask(task)
                    done(out,null)
                },{done(null,it.message?:"未取得本机原稿回执，请重读同一轮次")})
            }
        })
    }
}
