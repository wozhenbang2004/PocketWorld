package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import dev.pocketengine.ui.PocketWorldController
import java.util.UUID
import org.json.JSONObject

/** Original private comparison plus optional owner-authored texts; no project writer or model handle. */
internal class PocketWebProjectDraftDifference(private val controller:PocketWorldController,private val capture:()->PocketWebWindowTicket,
    private val accepts:(PocketWebWindowTicket)->Boolean,private val selected:()->String?) {
    companion object {
        const val READ="projectDraft.readDifference";const val EDIT="projectDraft.editLocal"
        const val KEEP="projectDraft.keepRevision";const val CLOSE="projectDraft.closeEditor"
        val COMMANDS=setOf(READ,EDIT,KEEP,CLOSE)
    }
    private data class Source(val task:String,val root:String,val window:PocketWebWindowTicket,val read:Long)
    private data class Editor(val source:Source,val id:String,val parent:String)
    private val main=Handler(Looper.getMainLooper())
    @Volatile private var generation=0L
    @Volatile private var source:Source?=null
    @Volatile private var editor:Editor?=null
    private fun current(task:String,window:PocketWebWindowTicket)=accepts(window)&&selected()==task&&controller.webPrivateOwnerVisible()
    private fun fields(args:JSONObject,vararg names:String)=require(args.keys().asSequence().toSet()==names.toSet()&&names.all{args.opt(it) is String})
    fun dispatch(action:String,args:JSONObject,done:(JSONObject?,String?)->Unit){
        when(action){READ->fields(args,"taskId");EDIT->fields(args,"taskId","draftId");KEEP->fields(args,"taskId","draftId","editHandle","parentId","text");CLOSE->fields(args,"taskId","editHandle");else->error("未知原稿操作")}
        val task=args.getString("taskId");require(task.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val window=capture();check(current(task,window)){"请打开原先确认只出改稿的私人项目任务"}
        if(action==READ){
            source=null;editor=null;val turn=++generation
            controller.runPluginCatalogWork(Runnable{
                val result=runCatching{check(turn==generation&&current(task,window));controller.nativeButlerHost().tasks.readPrivateProjectEditDraft(task)}
                main.post{if(turn!=generation||!current(task,window))done(null,"原私人任务已离开，没有保留差异正文")
                    else result.fold({if(it.opt("available")==true)source=Source(task,it.getString("draftId"),window,turn);done(it,null)},{done(null,it.message?:"这一次交办的原稿暂不可读")})}
            });return
        }
        val seen=source;check(seen!=null&&seen.task==task&&seen.window==window&&seen.read==generation){"请先重新打开这份实际差异"}
        val previous=editor
        if(action==CLOSE){if(previous!=null&&previous.source==seen&&previous.id==args.getString("editHandle"))editor=null;done(JSONObject().put("closed",true),null);return}
        val root=args.getString("draftId");check(root==seen.root)
        val token=if(action==EDIT){Editor(seen,UUID.randomUUID().toString(),"").also{editor=it}}
        else{check(previous!=null&&previous.source==seen&&previous.id==args.getString("editHandle")&&previous.parent==args.getString("parentId")){"原编辑位置已有变化，请重新打开已保留的稿"};previous}
        val proposed=if(action==KEEP)args.getString("text").also{require(it.length<=24576){"只保留完整的 24 KiB 文本改稿"}} else null
        fun live()=editor===token&&source==seen&&seen.read==generation&&current(task,window)
        controller.runPluginCatalogWork(Runnable{
            val result=runCatching{check(live());val tasks=controller.nativeButlerHost().tasks
                if(proposed==null)tasks.ownerProjectDraftHead(task,root)
                else tasks.keepOwnerProjectDraftRevision(task,root,token.parent,proposed,::live)}
            main.post{if(!live())done(null,"原编辑窗口已变化，请重新核对实际已保留稿；没有自动重交")
                else result.fold({v->
                    val next=Editor(seen,UUID.randomUUID().toString(),v.getString("revisionId"));editor=next
                    done(v.put("editHandle",next.id).put("retained",proposed!=null),null)
                },{done(null,it.message?:"无法核对这份本地独立稿，未覆盖任何原文件")})}
        })
    }
}
