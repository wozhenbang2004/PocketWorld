package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.pocketengine.features.local.LocalModule
import dev.pocketengine.features.local.project.OwnerDraftWorkCopy
import dev.pocketengine.store.TaskStore
import java.util.UUID
import org.json.JSONObject

/** Only a currently shown, already persisted version. A repeat query cannot publish, open or run a project. */
internal class PocketWebDraftWorkCopy(private val tasks:()->TaskStore,private val local:()->LocalModule,
    private val work:(Runnable)->Unit,private val open:(JSONObject)->Unit) {
    companion object {
        const val FIND="projectDraft.findWorkCopy";const val REVIEW="projectDraft.reviewWorkCopy"
        const val CREATE="projectDraft.createWorkCopy";const val OPEN="projectDraft.openWorkCopy"
        const val MOVE_REVIEW="projectDraft.reviewUnusedMove";const val MOVE="projectDraft.moveUnusedCopy"
        val commands=setOf(FIND,REVIEW,CREATE,OPEN,MOVE_REVIEW,MOVE)
    }
    private val main=Handler(Looper.getMainLooper())
    private data class Offer(val id:String,val ref:List<String>,val review:OwnerDraftWorkCopy.Review,val shown:()->Boolean,val expiry:Long)
    private data class Move(val id:String,val ref:List<String>,val status:String,val shown:()->Boolean,val expiry:Long)
    private var move:Move?=null
    private var offer:Offer?=null
    @Volatile var busy=false;private set
    fun clear(){offer=null;move=null}
    fun dispatch(action:String,args:JSONObject,shown:()->Boolean,done:(JSONObject?,String?)->Unit){
        val ref=listOf("taskId","draftId","revisionId","sha256").map(args::getString)
        require(ref.drop(1).all{it.matches(Regex("[a-f0-9]{64}"))})
        check(!busy&&shown()){"请先回到正在查看的这一份已保留稿。"}
        val hold=offer;val moving=move
        if(action in setOf(MOVE_REVIEW,MOVE))require(args.getString("fromState") in setOf("ready","shelved"))
        if(action==MOVE)check(moving!=null&&moving.ref==ref&&moving.status==args.getString("fromState")&&moving.id==args.getString("reviewHandle")&&moving.shown()&&SystemClock.elapsedRealtime()<moving.expiry){"收起或还原的审阅已变化。"}
        if(action==CREATE)check(hold!=null&&hold.ref==ref&&hold.id==args.getString("reviewHandle")&&hold.shown()&&
            SystemClock.elapsedRealtime()<hold.expiry){"副本审阅已变化，未创建。"}
        clear();busy=true
        try{work(Runnable{
            var next:Offer?=null;var nextMove:Move?=null
            val result=runCatching{
                fun current(){check(shown()){"原稿窗口已离开，没有自动重交。"}}
                fun source()=tasks().ownerDraftWorkCopySeed(ref[0],ref[1],ref[2],ref[3])
                current();val seed=source();val host=local()
                val existing=host.findDraftWorkCopy(seed)
                when {
                    action==FIND->existing.also { copy->
                        try { tasks().firstBuildForDraft(seed)?.let { copy.put("firstBuild",it) } }
                        catch(unavailable:Exception) { copy.put("firstBuildUnavailable",true) } // A missing optional child never hides the real working copy.
                    }
                    action==OPEN->host.openDraftWorkCopy(seed){current();check(source().identity==seed.identity)}
                    action==MOVE_REVIEW->{
                        val from=args.getString("fromState");check(existing.opt("status")==from&&existing.opt("recyclable")==true){"这份副本已投入工作或不在原位置，没有移动。"}
                        val m=Move(UUID.randomUUID().toString(),ref,from,shown,SystemClock.elapsedRealtime()+120_000L);nextMove=m
                        existing.put("moveHandle",m.id)
                    }
                    action==MOVE->host.moveUnusedDraftWorkCopy(seed,moving!!.status=="ready"){current();check(source().identity==seed.identity)}
                    existing.opt("found")==true->existing
                    action==REVIEW->{
                        val candidate=host.reviewDraftWorkCopy(seed);current()
                        val token=Offer(UUID.randomUUID().toString(),ref,candidate,shown,SystemClock.elapsedRealtime()+120_000L)
                        next=token;candidate.view().put("found",false).put("reviewHandle",token.id)
                    }
                    action==CREATE->{
                        val candidate=hold!!.review
                        check(candidate.seed.identity==seed.identity){"所选稿已变化，未采用其他版本。"}
                        host.publishDraftWorkCopy(candidate){current();check(source().identity==seed.identity){"原稿来源已变化。"}}
                    }
                    else->error("未知工作副本操作")
                }
            }
            main.post{
                busy=false
                if(!shown())done(null,"已离开原稿。回来可查同一份副本，没有自动重建或打开。")
                else result.fold({r->
                    offer=next;move=nextMove
                    next?.id?.let{token->main.postDelayed({if(offer?.id==token)offer=null},120_000L)}
                    if(action==OPEN)runCatching{open(r)}.fold({done(r,null)},{done(null,it.message?:"没有打开原副本")})
                    else done(r,null)
                },{done(null,it.message?:"暂不能核对这份副本；原工程和改稿均保留。")})
            }
        })}catch(e:Exception){busy=false;throw e}
    }
}
