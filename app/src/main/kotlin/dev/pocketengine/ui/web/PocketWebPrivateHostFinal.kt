package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import dev.pocketengine.features.assistant.AssistantModule
import dev.pocketengine.features.remote.RemoteModule
import dev.pocketengine.ui.PocketWorldController
import org.json.JSONObject

/** One private chat press can read its original authenticated job, never another task or a model. */
internal class PocketWebPrivateHostFinal(
    private val controller:PocketWorldController,
    private val capture:()->PocketWebWindowTicket,
    private val accepts:(PocketWebWindowTicket)->Boolean
) {
    private val main=Handler(Looper.getMainLooper())
    @Volatile private var active:Any?=null
    fun read(args:JSONObject, view:()->Boolean, done:(JSONObject?,String?)->Unit){
        require(args.keys().asSequence().toSet()==setOf("conversationId","messageId","taskId"))
        val conversation=args.getString("conversationId")
        val message=args.getString("messageId")
        val task=args.getString("taskId")
        require(conversation.isNotBlank()&&conversation.length<=160&&message.matches(Regex("[a-f0-9-]{36}"))&&task.matches(Regex("[a-f0-9-]{36}")))
        val window=capture();val ticket=Any()
        fun shown()=view()&&accepts(window)&&controller.state.conversationId==conversation&&
            controller.state.messages.any{it.id==message&&it.sourceRevision==1L&&it.hostGoalTaskId==task&&it.originalHostFinal!=null}
        check(active==null&&shown()) { "请从原私人委托读取这份结果" }
        active=ticket
        val current=java.util.function.BooleanSupplier{active===ticket&&shown()}
        val unavailable="没能核对这份原结果。没有重跑，也没有替换目标。"
        try{controller.runEcosystemControlWork(Runnable {
            val result=runCatching {
                check(current.asBoolean)
                val features=controller.nativeButlerHost().features
                val assistant=features.module("assistant") as AssistantModule
                val remote=features.module("remote") as RemoteModule
                assistant.readPrivateHostFinal(conversation,message,task,remote,current)
            }
            main.post {
                val returning=current.asBoolean
                if(active===ticket)active=null
                if(!returning){done(null,"原私人对话已关闭，未显示旧结果");return@post}
                result.fold({
                    if(it.opt("stage")=="saved")controller.refreshPrivateHostFinal(conversation)
                    done(it,null)
                },{done(null,unavailable)})
            }
        })}catch(failure:Exception){if(active===ticket)active=null;done(null,unavailable)}
    }
}
