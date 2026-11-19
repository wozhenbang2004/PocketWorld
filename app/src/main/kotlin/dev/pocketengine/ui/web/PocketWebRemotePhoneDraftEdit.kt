package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.pocketengine.features.remote.RemotePhoneDrafts
import dev.pocketengine.ui.PocketWorldController
import org.json.JSONObject
import java.util.UUID

/** Native review of one existing local draft and its replacement. Neither command knows a service, queue, or transport. */
internal class PocketWebRemotePhoneDraftEdit(private val controller:PocketWorldController,private val capture:()->PocketWebWindowTicket,
    private val accepts:(PocketWebWindowTicket)->Boolean,private val showing:()->String?){
    companion object{
        private const val PREFIX="remoteTask.phoneDrafts."
        val COMMANDS=setOf(PREFIX+"reviewEdit",PREFIX+"saveEdit")
    }
    private data class Change(val task:String,val run:String,val epoch:Int,val ticket:PocketWebWindowTicket,val pin:String,val oldSha:String,
        val replacement:String,val sha:String,val handle:String,val expires:Long)
    private var pending:Change?=null
    private val main=Handler(Looper.getMainLooper());@Volatile private var generation=0L
    private var expiry:Runnable?=null
    fun clear(){generation++;pending=null;expiry?.let(main::removeCallbacks);expiry=null}
    fun dispatch(action:String,args:JSONObject,done:(JSONObject?,String?)->Unit){
        val commit=action==PREFIX+"saveEdit";require(commit||action==PREFIX+"reviewEdit")
        val common=setOf("taskId","runId","coordinatorEpoch")
        val allowed=common+if(commit)setOf("changeHandle") else setOf("id","revision","scopeHash","oldSha256","replacement")
        require(args.keys().asSequence().toSet()==allowed&&args.toString().length<80000)
        val task=args.getString("taskId");val run=args.getString("runId");val n=args.opt("coordinatorEpoch")
        require(task.length in 1..80&&run.length in 1..80&&(n is Int||n is Long)&&(n as Number).toLong() in 0L..Int.MAX_VALUE.toLong())
        val epoch=(n as Number).toInt();val window=capture();val change=pending
        if(commit)check(change!=null&&change.task==task&&change.run==run&&change.epoch==epoch&&change.ticket==window&&
            args.opt("changeHandle")==change.handle&&SystemClock.elapsedRealtime()<change.expires){"修改审阅已结束，请重新核对原稿；没有自动保存或发送"}
        clear();val gen=generation
        fun owner()=generation==gen&&accepts(window)&&controller.webPrivateOwnerVisible()&&showing()==task&&(!commit||SystemClock.elapsedRealtime()<change!!.expires)
        check(owner()){"请回到这份原私人任务草稿"}
        val pin=if(commit)JSONObject(change!!.pin) else JSONObject().also {o->for(k in common+setOf("id","revision","scopeHash"))o.put(k,args.get(k))}
        val old=if(commit)change!!.oldSha else args.getString("oldSha256");val text=if(commit)change!!.replacement else args.getString("replacement")
        require(old.matches(Regex("[a-f0-9]{64}"))&&text.length in 1..12000)
        controller.runEcosystemControlWork(Runnable{
            val result=runCatching{
                check(owner());val sha=RemotePhoneDrafts.textFingerprint(text)
                if(commit)controller.reviseUnsentPhoneDraft(pin,old,text,change!!.sha,::owner)
                else{
                    val read=controller.readOrWithdrawPhoneDraft(pin,"read",null,::owner);val item=read.getJSONObject("item")
                    check(item.opt("textSha256")==old&&old!=sha){"原稿已变或没有新改动；没有覆盖原稿"}
                    read.remove("item");read.put("id",item.get("id")).put("revision",item.get("revision")).put("scopeHash",item.get("scopeHash"))
                        .put("previousLocalState",item.get("localState")).put("oldSha256",old).put("textSha256",sha).put("saved",false).put("willQueue",false)
                }
            }
            main.post{
                if(!owner())done(null,if(commit)"本机保存回执未收到，请重读原清单；没有排队、发送或重试" else "原窗口已变化，没有保存修改")
                else result.fold({out->
                    if(!commit){val handle=UUID.randomUUID().toString()
                        pending=Change(task,run,epoch,window,pin.toString(),old,text,out.getString("textSha256"),handle,SystemClock.elapsedRealtime()+120000)
                        expiry=Runnable{if(pending?.handle==handle)clear()}.also{main.postDelayed(it,120000)}
                        out.put("changeHandle",handle)
                    }else controller.getTask(task)
                    done(out,null)
                },{done(null,it.message?:"未取得原稿回执；请重新核对，不自动重试")})
            }
        })
    }
}
