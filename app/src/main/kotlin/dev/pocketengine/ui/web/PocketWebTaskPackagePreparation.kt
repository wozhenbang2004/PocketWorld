package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.local.LocalModule
import dev.pocketengine.ui.PocketWorldController
import org.json.JSONObject

/** Private temporary selection only. The existing import review owns any actual project creation. */
internal class PocketWebTaskPackagePreparation(private val controller: PocketWorldController) {
    companion object { const val PREPARE="tasks.prepareProjectSource"; const val CLOSE="tasks.closeProjectSourcePreparation" }
    private data class Attempt(val task:String,val token:String,val control:RunControl)
    private var pending:Attempt?=null
    private val main=Handler(Looper.getMainLooper())
    fun clear(){pending?.control?.cancel();pending=null}
    fun dispatch(action:String,args:JSONObject,current:()->Boolean,done:(JSONObject?,String?)->Unit){
        val closing=action==CLOSE
        require(args.keys().asSequence().toSet()==(if(closing)setOf("taskId","token") else
            setOf("taskId","token","operationId","artifactId","archiveSha256","projectId","checkoutId","runId","epoch"))){"固定源码选择不完整"}
        fun id(key:String)=(args.opt(key) as? String)?.takeIf{it.isNotBlank()&&it.length<=160&&it.none(Char::isISOControl)}?:error("原工作身份无效")
        val task=id("taskId");val token=id("token");require(token.matches(Regex("[a-f0-9-]{36}")))
        if(closing){pending?.takeIf{it.task==task&&it.token==token}?.let{clear()};done(JSONObject(),null);return}
        require(action==PREPARE&&pending==null&&current()){"请结束已有准备并回到原工作"}
        val operation=id("operationId");val artifact=id("artifactId");val sha=id("archiveSha256");require(sha.matches(Regex("[a-f0-9]{64}")))
        val project=id("projectId");val checkout=id("checkoutId");val run=id("runId");val epoch=(args.opt("epoch") as? Number)?.toLong()
        require(epoch!=null && epoch in 1..Int.MAX_VALUE.toLong() && args.getDouble("epoch")==epoch.toDouble())
        val work=Attempt(task,token,RunControl());pending=work
        try{controller.runPluginCatalogWork(Runnable {
            val result=runCatching {
                val gate=work.control.retainAuthorityCheck { check(current()){ "已离开原任务，不再准备固定源码" } }
                try{
                    work.control.check()
                    val local=controller.nativeButlerHost().features.module("local") as? LocalModule ?: error("本机项目模块不可用")
                    local.preparePrivateTaskSource(task,operation,artifact,sha,project,checkout,run,epoch.toInt(),work.control)
                }finally{gate.close()}
            }
            main.post {
                val same=pending===work;if(same)pending=null
                if(!same||!current())done(null,"已离开原任务，未创建项目")
                else result.fold({done(it,null)},{done(null,it.message?:"原固定源码暂不可准备")})
            }
        })}catch(e:Exception){if(pending===work)clear();throw e}
    }
}
