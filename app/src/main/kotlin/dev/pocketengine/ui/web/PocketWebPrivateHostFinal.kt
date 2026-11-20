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
    fun read(args:JSONObject, reader:Long?, revision:Long, view:()->Boolean, done:(JSONObject?,String?)->Unit){
        val keys=setOf("conversationId","messageId","taskId")
        require(args.keys().asSequence().toSet()==if(reader==null)keys else keys+setOf("readerWindow","sourceRevision"))
        val conversation=args.opt("conversationId") as? String ?: error("缺少原对话")
        val message=args.opt("messageId") as? String ?: error("缺少原消息")
        val task=args.opt("taskId") as? String ?: error("缺少原工作")
        require(revision==1L&&conversation.isNotBlank()&&conversation.length<=160&&message.matches(Regex("[a-f0-9-]{36}"))&&task.matches(Regex("[a-f0-9-]{36}")))
        val window=capture();val ticket=Any();val source=controller.conversationSourceRevision
        fun shown():Boolean {
            if(!view()||!accepts(window))return false
            val page=controller.conversationReader
            if(reader!=null)
                return source==controller.conversationSourceRevision&&page!=null&&page.window==reader&&page.conversation==conversation&&
                    controller.canReadConversationWindow(page)&&page.privateHostResultRow(message,revision,task)!=null
            return page==null&&controller.state.conversationId==conversation&&
                controller.state.messages.singleOrNull{it.id==message&&it.sourceRevision==revision&&it.hostGoalTaskId==task}?.originalHostFinal!=null
        }
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
                val stage=assistant.readPrivateHostFinal(conversation,message,task,remote,current)
                // Read only the committed original row back into this native page, never the raw GET.
                val saved=if(reader!=null&&stage.opt("stage")=="saved")
                    assistant.readSavedPrivateHostFinal(conversation,message,task,current) else null
                stage to saved
            }
            main.post {
                val returning=current.asBoolean
                if(active===ticket)active=null
                if(!returning){done(null,"原私人对话已关闭，未显示旧结果");return@post}
                result.fold({ (stage,saved) ->
                    if(stage.opt("stage")=="saved"){
                        if(reader==null)controller.refreshPrivateHostFinal(conversation)
                        else if(saved==null||!controller.applyPrivateHostFinalToReader(reader,conversation,message,revision,task,saved))
                            stage.put("stage","saved_reader_limited")
                    }
                    done(stage,null)
                },{done(null,unavailable)})
            }
        })}catch(failure:Exception){if(active===ticket)active=null;done(null,unavailable)}
    }
}
