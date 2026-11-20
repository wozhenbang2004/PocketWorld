package dev.pocketengine.ui.web

import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.content.Context
import dev.pocketengine.store.TaskStore
import dev.pocketengine.ui.PrivateProjectDraftDownload
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Original private comparison plus optional owner-authored texts; no project writer or model handle. */
internal class PocketWebProjectDraftDifference(private val context:Context,private val tasks:()->TaskStore,private val work:(Runnable)->Unit,
    private val capture:()->PocketWebWindowTicket,
    private val accepts:(PocketWebWindowTicket)->Boolean,private val local:()->dev.pocketengine.features.local.LocalModule,
    private val openWork:(JSONObject)->Unit,private val selected:()->String?) {
    companion object {
        const val READ="projectDraft.readDifference";const val EDIT="projectDraft.editLocal"
        const val KEEP="projectDraft.keepRevision";const val CLOSE="projectDraft.closeEditor"
        const val OFFER="projectDraft.reviewFileCopy";const val SAVE="projectDraft.saveFileCopy"
        val COMMANDS=setOf(READ,EDIT,KEEP,CLOSE,OFFER,SAVE)+PocketWebDraftWorkCopy.commands
    }
    private data class Source(val task:String,val root:String,val run:String,val epoch:Int,val path:String,val window:PocketWebWindowTicket,val read:Long)
    private data class Editor(val source:Source,val id:String,val parent:String)
    private data class CopyReview(val source:Source,val editor:Editor?,val token:String,val version:PrivateProjectDraftDownload.Version,val expires:Long)
    private var copyReview:CopyReview?=null
    private val workCopy=PocketWebDraftWorkCopy(tasks,local,work,openWork)
    fun clearWorkCopy(){workCopy.clear()}
    private var copying=false
    private val main=Handler(Looper.getMainLooper())
    @Volatile private var generation=0L
    @Volatile private var source:Source?=null
    @Volatile private var editor:Editor?=null
    private fun current(task:String,window:PocketWebWindowTicket)=accepts(window)&&selected()==task
    private fun fields(args:JSONObject,vararg names:String)=require(args.keys().asSequence().toSet()==names.toSet()&&names.all{args.opt(it) is String})
    fun dispatch(action:String,args:JSONObject,done:(JSONObject?,String?)->Unit){
        val exact=action==READ&&args.has("draftId")
        when(action){PocketWebDraftWorkCopy.OPEN_RETAINED->fields(args,"taskId","draftId","revisionId","sha256","workIdentity");PocketWebDraftWorkCopy.MOVE_REVIEW->fields(args,"taskId","draftId","revisionId","sha256","fromState");PocketWebDraftWorkCopy.MOVE->fields(args,"taskId","draftId","revisionId","sha256","fromState","reviewHandle");PocketWebDraftWorkCopy.FIND,PocketWebDraftWorkCopy.REVIEW,PocketWebDraftWorkCopy.OPEN->fields(args,"taskId","draftId","revisionId","sha256");PocketWebDraftWorkCopy.CREATE->fields(args,"taskId","draftId","revisionId","sha256","reviewHandle");READ->if(exact)fields(args,"taskId","draftId","runId","epoch") else fields(args,"taskId");EDIT->fields(args,"taskId","draftId");KEEP->fields(args,"taskId","draftId","editHandle","parentId","text");CLOSE->fields(args,"taskId","editHandle");OFFER->fields(args,"taskId","draftId","revisionId","sha256");SAVE->fields(args,"taskId","draftId","revisionId","sha256","reviewHandle");else->error("未知原稿操作")}
        val bound=if(exact) Triple(args.getString("draftId"),args.getString("runId"),args.getString("epoch").toIntOrNull()).also{
            require(it.first.matches(Regex("[a-f0-9]{64}"))&&it.second.isNotBlank()&&it.second.length<=240&&(it.third ?: 0)>0)
        } else null
        val task=args.getString("taskId");require(task.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val window=capture();check(current(task,window)){"请打开原先确认只出改稿的私人项目任务"}
        if(action!=SAVE)copyReview=null
        if(action !in PocketWebDraftWorkCopy.commands){workCopy.clear();check(!workCopy.busy){"请等待本次工作副本反馈。"}}
        if(action==READ){
            source=null;editor=null;val turn=++generation
            work(Runnable{
                val result=runCatching{
                    check(turn==generation&&current(task,window))
                    tasks().readPrivateProjectEditDraftForOwnerView(task,bound?.first,bound?.second,bound?.third ?: -1)
                }
                main.post{if(turn!=generation||!current(task,window))done(null,"原私人任务已离开，没有保留差异正文")
                    else result.fold({if(it.opt("available")==true)source=Source(task,it.getString("draftId"),it.getString("runId"),it.getInt("epoch"),it.getString("path"),window,turn);done(it,null)},{done(null,it.message?:"这一次交办的原稿暂不可读")})}
            });return
        }
        val seen=source;check(seen!=null&&seen.task==task&&seen.window==window&&seen.read==generation){"请先重新打开这份实际差异"}
        if(action in PocketWebDraftWorkCopy.commands){
            check(!copying&&args.getString("draftId")==seen.root)
            val revision=args.getString("revisionId");val held=editor
            val prior=action==PocketWebDraftWorkCopy.OPEN_RETAINED
            if(prior)check(held==null&&revision!=seen.root&&args.getString("workIdentity").matches(Regex("[a-f0-9]{64}")))
            fun shown()=source===seen&&generation==seen.read&&editor===held&&current(task,window)&&
                (prior&&held==null||revision==seen.root||held!=null&&held.source===seen&&held.parent==revision)
            val retained: (()->JSONArray)? = if(prior||action==PocketWebDraftWorkCopy.FIND&&revision==seen.root&&held==null) { {
                check(shown())
                val registered=local().retainedDraftCopyReferencesForOwner(task,seen.root)
                tasks().retainedDraftWorkForOwner(task,seen.root,seen.run,seen.epoch,seen.path,registered).also{check(shown())}
            } } else null
            workCopy.dispatch(action,args,::shown,retained,done);return
        }
        if(action==OFFER||action==SAVE){fileCopy(action,args,seen,done);return}
        val previous=editor
        if(action==CLOSE){if(previous!=null&&previous.source==seen&&previous.id==args.getString("editHandle"))editor=null;done(JSONObject().put("closed",true),null);return}
        val root=args.getString("draftId");check(root==seen.root)
        val token=if(action==EDIT){Editor(seen,UUID.randomUUID().toString(),"").also{editor=it}}
        else{check(previous!=null&&previous.source==seen&&previous.id==args.getString("editHandle")&&previous.parent==args.getString("parentId")){"原编辑位置已有变化，请重新打开已保留的稿"};previous}
        val proposed=if(action==KEEP)args.getString("text").also{require(it.length<=24576){"只保留完整的 24 KiB 文本改稿"}} else null
        fun live()=editor===token&&source==seen&&seen.read==generation&&current(task,window)
        work(Runnable{
            val result=runCatching{check(live());val tasks=tasks()
                if(proposed==null)tasks.ownerProjectDraftHead(task,root)
                else tasks.keepOwnerProjectDraftRevision(task,root,token.parent,proposed,::live)}
            main.post{if(!live())done(null,"原编辑窗口已变化，请重新核对实际已保留稿；没有自动重交")
                else result.fold({v->
                    val next=Editor(seen,UUID.randomUUID().toString(),v.getString("revisionId"));editor=next
                    done(v.put("editHandle",next.id).put("retained",proposed!=null),null)
                },{done(null,it.message?:"无法核对这份本地独立稿，未覆盖任何原文件")})}
        })
    }

    private fun fileCopy(action:String,args:JSONObject,seen:Source,done:(JSONObject?,String?)->Unit){
        val p=PrivateProjectDraftDownload.Pin(seen.task,seen.root,args.getString("revisionId"),args.getString("sha256"))
        check(!copying && args.getString("draftId")==seen.root){"请等待当前保存反馈，并核对同一份原稿。"}
        val hold=editor
        fun shown()=source===seen && generation==seen.read && editor===hold && current(seen.task,seen.window) &&
            (p.revision==p.root || hold!=null && hold.source===seen && hold.parent==p.revision)
        check(shown()){"请从当前文件差异或已保留的明确稿本开始另存。"}
        val review=copyReview
        if(action==SAVE){
            copyReview=null
            require(review!=null && review.source===seen && review.editor===hold && review.version.pin==p && review.token==args.getString("reviewHandle") &&
                SystemClock.elapsedRealtime()<review.expires){"保存审阅已失效，请重新核对文件与去向。"}
        }
        copying=true
        try{work(Runnable{
            var offered:CopyReview?=null
            val result=runCatching{
                check(shown()){"原稿窗口已离开。"}
                if(action==OFFER){
                    val v=PrivateProjectDraftDownload.review(tasks(),p)
                    val next=CopyReview(seen,hold,UUID.randomUUID().toString(),v,SystemClock.elapsedRealtime()+120_000)
                    offered=next;v.view().put("reviewHandle",next.token)
                }else PrivateProjectDraftDownload.save(context,tasks(),review!!.version){check(shown()){"原稿窗口或版本已变化。"}}
            }
            main.post{
                copying=false
                if(!shown())done(null,if(action==SAVE)"已离开原稿，请先查看 下载/PocketWorld；不会自动重存。" else "原稿窗口已离开，未保存。")
                else result.fold({if(offered!=null)copyReview=offered;done(it,null)},{done(null,it.message?:"无法核对这次保存；没有自动再存。")})
            }
        })}catch(e:RuntimeException){copying=false;throw e}
    }
}
