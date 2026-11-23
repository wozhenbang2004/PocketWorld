package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.pocketengine.features.local.preview.OwnerKeptStaticPages
import dev.pocketengine.features.workspace.WorkspaceModule
import dev.pocketengine.ui.PocketWorldController
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** One explicit native visit to the same private Project. The store is not an Engine feature. */
internal class PocketWebKeptStaticPages(
    private val controller: PocketWorldController,
    private val renderer: () -> PocketWebProjectPreview?,
    private val document: () -> String?,
    private val projectVisible: (String) -> Boolean,
    private val captureWindow: () -> PocketWebWindowTicket,
    private val acceptsWindow: (PocketWebWindowTicket) -> Boolean,
) {
    companion object {
        private const val PREFIX="projects.keptStaticPages."
        const val KEEP=PREFIX+"keep"
        private const val LIST=PREFIX+"list"
        private const val OPEN=PREFIX+"open"
        private const val COMPARE=PREFIX+"compare"
        private const val CLOSE=PREFIX+"close"
        private const val REVIEW=PREFIX+"pending.review"
        private const val ABANDON=PREFIX+"pending.abandon"
        private const val DISMISS=PREFIX+"pending.dismiss"
        private const val REVIEW_REMOVAL=PREFIX+"removal.review"
        private const val REMOVE=PREFIX+"removal.confirm"
        private const val DISMISS_REMOVAL=PREFIX+"removal.dismiss"
        val COMMANDS=setOf(KEEP,LIST,OPEN,COMPARE,CLOSE,REVIEW,ABANDON,DISMISS,REVIEW_REMOVAL,REMOVE,DISMISS_REMOVAL)
        fun mayMutate(action:String)=action==KEEP||action==ABANDON||action==REMOVE
        private val uuid=Regex("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")
        private val digest=Regex("[a-f0-9]{64}")
        private val shelfFields=setOf("projectId","visitId","shelfHandle")
        private val exactVersion=setOf("versionId","manifestIdentity")
        private const val IO_WINDOW_MS=20_000L
        private const val RETIRED_LIMIT=16
        private const val LOST_READ="原项目窗口已关闭，请重新选择原本机网页"
        private const val LOST_EFFECT="本次动作尚未核对，请回同一项目查看；没有重试"
    }

    private data class Visit(val project:String,val id:String,val document:String)
    private data class Registration(val module:WorkspaceModule,val createdAt:Long)
    private class Flight(val until:Long,val confirmation:String?,val reject:(String)->Unit) {
        lateinit var timeout:Runnable
    }
    private inner class Shelf(val one:Visit,val window:PocketWebWindowTicket):OwnerKeptStaticPages.PendingRecoveryWindow {
        val handle=UUID.randomUUID().toString()
        val preview=PocketWebProjectPreview.KeptVisit(one.project,one.id,handle){ live(this) }
        var registration:Registration?=null
        var catalog:OwnerKeptStaticPages.Catalog?=null
        var review:OwnerKeptStaticPages.PendingReview?=null
        var removal:RemovalOffer?=null
        var removalNeedsList=false
        var flight:Flight?=null

        override fun projectId()=one.project
        override fun current()=try { fence.call { active(this) } } catch (_:Exception) { false }
        override fun atConfirmedAbandonment(reviewId:String,unlink:OwnerKeptStaticPages.Commit){
            fence.call {
                check(active(this) && registration!=null && flight?.confirmation==reviewId &&
                    renderer()?.keptShelfCanRead()==true) { "原项目未确认清理这次暂存" }
                unlink.enter()
            }
        }
    }

    private inner class RemovalOffer(val owner:Shelf,val catalog:OwnerKeptStaticPages.Catalog,val page:OwnerKeptStaticPages.Page,
            val registration:Registration,val originalRenderer:PocketWebProjectPreview):OwnerKeptStaticPages.RemovalWindow{
        private val idle=originalRenderer.pinKeptRemovalWindow(owner.preview)
        var flight:Flight?=null
        var shown:OwnerKeptStaticPages.RemovalReview?=null
        fun stillHere()=live(owner) && owner.catalog===catalog &&
            owner.registration?.let{it.module===registration.module && it.createdAt==registration.createdAt}==true &&
            renderer()===originalRenderer && idle()
        private fun admitted()=owner.flight===flight && active(owner) && stillHere()

        override fun projectId()=owner.one.project
        override fun current()=try{fence.call{admitted()}}catch(_:Exception){false}
        override fun atConfirmedRemoval(reviewId:String,unlink:OwnerKeptStaticPages.Commit){
            fence.call{
                check(admitted() && flight?.confirmation==reviewId && shown?.reviewId()==reviewId){
                    "原项目未确认移除这一版保留网页"
                }
                unlink.enter()
            }
        }
    }

    private val main=Handler(Looper.getMainLooper())
    private val fence=KeptStaticMainFence(main)
    private val storage by lazy { OwnerKeptStaticPages(controller.webPresentationActivity().applicationContext) }
    private var shelf:Shelf?=null
    private val retired=LinkedHashSet<Visit>()

    private fun mainThread(){check(Looper.myLooper()==main.looper){"请在原项目界面核对保留网页"}}
    private fun live(value:Shelf)=shelf===value && document()==value.one.document &&
        acceptsWindow(value.window) && projectVisible(value.one.project)
    private fun active(value:Shelf)=live(value) && value.flight?.let{SystemClock.elapsedRealtime()<it.until}==true
    private fun remember(visit:Visit){
        retired.add(visit)
        while(retired.size>RETIRED_LIMIT)retired.remove(retired.first())
    }
    private fun finishFlight(value:Shelf,flight:Flight){
        main.removeCallbacks(flight.timeout)
        if(value.flight===flight)value.flight=null
    }
    fun clear(){
        mainThread()
        val old=shelf?:return
        shelf=null
        remember(old.one)
        old.review=null;old.removal=null;old.catalog=null
        old.flight?.let{flight->
            finishFlight(old,flight)
            flight.reject(if(flight.confirmation==null) LOST_READ else LOST_EFFECT)
        }
        renderer()?.retireKept(old.preview)
    }
    fun retireLostOwner(){if(shelf?.let{!live(it)}==true)clear()}
    fun snapshot():JSONObject?=shelf?.takeIf{live(it)&&it.registration!=null&&it.catalog!=null}
        ?.let{present(it,JSONObject())}

    private fun projectRegistration(project:String):Registration{
        val workspace=controller.nativeButlerHost().features.module("workspace") as? WorkspaceModule
            ?:error("原项目登记暂不可核对")
        val found=workspace.projectIdentityForHost(project)
        val created=(found.opt("createdAt") as? Number)?.toLong()?:0L
        check(found.opt("id")==project && created>0){"原项目登记不同"}
        return Registration(workspace,created)
    }

    private fun <T> work(
        value:Shelf,done:(JSONObject?,String?)->Unit,confirmation:String?=null,
        bindFlight:((Flight)->Unit)?=null,
        read:(OwnerKeptStaticPages)->T, show:(T)->JSONObject,
    ){
        check(live(value)&&value.flight==null){"原项目网页窗口已变化或已有操作"}
        val prior=value.registration
        val original=Flight(SystemClock.elapsedRealtime()+IO_WINDOW_MS,confirmation){message->done(null,message)}
        original.timeout=Runnable{
            if(shelf!==value||value.flight!==original)return@Runnable
            if(prior==null)clear()
            else{
                finishFlight(value,original)
                original.reject(if(confirmation==null) "本次读取未确认。请在同一项目重新查看。" else LOST_EFFECT)
            }
        }
        value.flight=original
        bindFlight?.invoke(original)
        main.postDelayed(original.timeout,IO_WINDOW_MS)
        try{
            val book=storage
            controller.runPluginCatalogWork(Runnable{
                val result=runCatching{
                    check(value.current())
                    val actual=projectRegistration(value.one.project)
                    if(prior!=null)check(actual.module===prior.module && actual.createdAt==prior.createdAt)
                    check(value.current())
                    actual to read(book)
                }
                main.post{
                    if(value.flight!==original)return@post
                    val current=active(value)
                    finishFlight(value,original)
                    if(!current){
                        if(shelf===value)clear()
                        done(null,if(confirmation==null) LOST_READ else LOST_EFFECT)
                        return@post
                    }
                    result.mapCatching{(actual,payload)->
                        value.registration=actual
                        show(payload)
                    }.fold({done(it,null)},{
                        if(prior==null)clear()
                        done(null,if(confirmation==null) "未核对到这次所选项目记录，没有打开其他页面。" else LOST_EFFECT)
                    })
                }
            })
        }catch(failure:Exception){
            finishFlight(value,original)
            if(prior==null)clear()
            throw failure
        }
    }

    private fun fields(args:JSONObject,expected:Set<String>){
        require(args.keys().asSequence().toSet()==expected){"原本机网页操作参数不同"}
    }
    private fun text(args:JSONObject,key:String,pattern:Regex=uuid):String =
        (args.opt(key) as? String)?.takeIf(pattern::matches)?:error("原本机网页身份无效")
    private fun present(value:Shelf,result:JSONObject)=result.put("projectId",value.one.project)
        .put("visitId",value.one.id).put("shelfHandle",value.handle)

    fun dispatch(action:String,args:JSONObject,done:(JSONObject?,String?)->Unit){
        mainThread()
        if(action==KEEP){
            fields(args,setOf("sessionId","documentGeneration","manifestIdentity"))
            val rawGeneration=args.opt("documentGeneration")
            require(rawGeneration is Int || rawGeneration is Long)
            val generation=(rawGeneration as Number).toLong()
            require(generation in 1L..9_007_199_254_740_991L)
            val exact=renderer()?:error("原网页已关闭")
            exact.keepVisibleSavedStatic(text(args,"sessionId"),generation,text(args,"manifestIdentity",digest)){result->
                if(result==null)done(null,LOST_EFFECT) else done(result.metadata(),null)
            }
            return
        }
        require(action in COMMANDS)
        val project=text(args,"projectId")
        val visit=Visit(project,text(args,"visitId"),document()?:error("原项目界面已关闭"))
        if(action==CLOSE){
            fields(args,setOf("projectId","visitId"))
            val selected=shelf
            if(selected?.one==visit)clear()
            else if(visit !in retired){
                // Exact cancellation is possible even before the first list was admitted.
                check(selected==null && projectVisible(project)){"未确认关闭这次原项目窗口"}
                remember(visit)
            }
            done(JSONObject().put("kind","owner_saved_static_page_shelf_closed")
                .put("projectId",project).put("visitId",visit.id).put("closed",true),null)
            return
        }
        val first=action==LIST&&!args.has("shelfHandle")
        if(first)fields(args,setOf("projectId","visitId"))
        else fields(args,shelfFields + when(action){
            OPEN,REVIEW_REMOVAL->exactVersion
            COMPARE->setOf("beforeVersionId","beforeManifestIdentity","afterVersionId","afterManifestIdentity")
            ABANDON->setOf("reviewId","confirmed")
            DISMISS->setOf("reviewId")
            REMOVE->exactVersion+setOf("reviewId","confirmed")
            DISMISS_REMOVAL->exactVersion+"reviewId"
            else->emptySet()
        })
        if(action==ABANDON)require(args.opt("confirmed")==true){"请明确确认仅清除本项目未完成的暂存"}
        if(action==REMOVE)require(args.opt("confirmed")==true){"请明确确认移除这份已保留的网页"}
        check(projectVisible(project) && renderer()?.keptShelfCanRead()==true){"请回到当前私人项目的工作台"}
        val selected=if(first){
            check(visit !in retired){"这次项目网页窗口已关闭，请使用新的选择"}
            shelf?.let{
                check(!live(it)){"请先退出原本机网页窗口"}
                clear()
            }
            Shelf(visit,captureWindow()).also{shelf=it}
        }else{
            val original=shelf?:error("请重新打开原项目的本机网页目录")
            check(visit==original.one && text(args,"shelfHandle")==original.handle &&
                live(original) && original.registration!=null && original.catalog!=null && original.flight==null){
                "原项目网页目录已失效或仍在核对"
            }
            original
        }
        check(action==LIST || !selected.removalNeedsList){"请先核对原项目保留列表"}
        check(selected.removal==null || action in setOf(LIST,REMOVE,DISMISS_REMOVAL)){"请先退出原保留网页的移除确认"}
        when(action){
            LIST->{
                selected.review=null
                selected.removal=null
                work(selected,done,read={it.catalog(selected)},show={catalog->
                    check(catalog.projectId()==project){"原目录不属于此项目"}
                    selected.catalog=catalog
                    selected.removalNeedsList=false
                    present(selected,catalog.metadata())
                })
            }
            OPEN->{
                check(selected.review==null){"请先关闭原暂存清理确认"}
                val id=text(args,"versionId");val manifest=text(args,"manifestIdentity",digest)
                val row=checkNotNull(selected.catalog).versions().singleOrNull{
                    it.projectId()==project && it.versionId()==id && it.manifestIdentity()==manifest
                }?:error("请只打开本次目录中确切的一份网页版本")
                val mount=(renderer()?:error("原预览宿主已关闭")).prepareKeptMount(selected.preview)
                work(selected,done,read={it.open(selected,id,manifest)},show={opened->
                    check(opened.page()==row){"原保留网页的元数据已变化"}
                    mount(opened)
                })
            }
            COMPARE->{
                check(selected.review==null){"请先关闭原暂存清理确认"}
                val catalog=checkNotNull(selected.catalog)
                fun row(side:String):OwnerKeptStaticPages.Page{
                    val id=text(args,side+"VersionId");val manifest=text(args,side+"ManifestIdentity",digest)
                    return catalog.versions().singleOrNull{
                        it.projectId()==project && it.versionId()==id && it.manifestIdentity()==manifest
                    }?:error("请只对照这次目录中的确切保留版本")
                }
                val before=row("before");val after=row("after")
                check(before.versionId()!=after.versionId() && before.checkoutId()==after.checkoutId()){
                    "只对照同一副本的两份不同保留网页"
                }
                // The shared can-read gate is passive. No renderer session or body read is issued.
                work(selected,done,read={it.compare(selected,catalog,before,after)},show={result->
                    check(selected.catalog===catalog && renderer()?.keptShelfCanRead()==true){"原保留列表或窗口已变化"}
                    present(selected,result)
                })
            }
            REVIEW->{
                check(checkNotNull(selected.catalog).pendingState()=="reviewable" && selected.review==null){"请先从原项目核对未完成的暂存"}
                work(selected,done,read={it.reviewPendingAbandonment(selected)},show={review->
                    val info=review?.metadata()?:JSONObject().put("kind","owner_saved_static_page_pending_review")
                        .put("reviewId",JSONObject.NULL).put("expiresInMs",0)
                    selected.review=review
                    present(selected,info)
                })
            }
            ABANDON->{
                val id=text(args,"reviewId")
                val exact=selected.review?.takeIf{it.reviewId()==id}?:error("未确认原项目这次暂存")
                selected.review=null
                work(selected,done,confirmation=id,read={it.abandonPending(exact,selected)},show={result->
                    check(result.projectId()==project && result.reviewId()==id){"原暂存清理回执不同"}
                    present(selected,result.metadata())
                })
            }
            DISMISS->{
                val id=text(args,"reviewId")
                check(selected.review?.reviewId()==id){"原暂存核对已关闭"}
                selected.review=null
                done(present(selected,JSONObject().put("kind","owner_saved_static_page_pending_dismissed")
                    .put("reviewId",id).put("dismissed",true)),null)
            }
            REVIEW_REMOVAL->{
                check(selected.review==null){"请先关闭原暂存清理确认"}
                val catalog=checkNotNull(selected.catalog)
                val version=text(args,"versionId");val manifest=text(args,"manifestIdentity",digest)
                val page=catalog.versions().singleOrNull{
                    it.projectId()==project && it.versionId()==version && it.manifestIdentity()==manifest
                }?:error("请只选择本次目录中确切的一份已保留网页")
                val offer=RemovalOffer(selected,catalog,page,checkNotNull(selected.registration),checkNotNull(renderer()))
                work(selected,done,bindFlight={offer.flight=it},read={it.reviewRemoval(offer,catalog,page)},show={review->
                    check(offer.stillHere() && review.page()==page){"原保留版本或项目窗口已变化"}
                    val response=present(selected,review.metadata())
                    offer.shown=review
                    selected.removal=offer
                    response
                })
            }
            REMOVE,DISMISS_REMOVAL->{
                val id=text(args,"reviewId");val version=text(args,"versionId");val manifest=text(args,"manifestIdentity",digest)
                val offer=selected.removal?.takeIf{it.page.versionId()==version && it.page.manifestIdentity()==manifest &&
                    it.shown?.reviewId()==id}?:error("未确认原项目这次所选已保留网页")
                val review=checkNotNull(offer.shown)
                selected.removal=null // Both paths retire the one native offer; never reuse it.
                if(action==DISMISS_REMOVAL){
                    done(present(selected,JSONObject().put("kind","owner_saved_static_page_removal_dismissed")
                        .put("reviewId",id).put("page",offer.page.metadata()).put("dismissed",true)),null)
                    return
                }
                selected.removalNeedsList=true // If anything is lost, only a deliberate stock LIST restores this shelf.
                work(selected,done,confirmation=id,bindFlight={offer.flight=it},read={it.remove(review,offer)},show={result->
                    check(offer.stillHere() && result.page()==offer.page && result.reviewId()==id){"原保留版本的移除回执不同"}
                    val response=present(selected,result.metadata())
                    if(result.status()==OwnerKeptStaticPages.RemovalStatus.REMOVED){
                        val updated=checkNotNull(result.catalog())
                        check(updated.projectId()==project){"原保留列表归属不同"}
                        response.put("catalog",present(selected,updated.metadata()))
                        selected.catalog=updated
                        selected.removalNeedsList=false
                    }
                    response
                })
            }
            else->error("未接入的本机网页操作")
        }
    }
}

/** Worker may await native window/confirmation; main never waits for IO nor reenters the store.
 * Only a final exact stat/rename or unlink runs synchronously with navigation on main. */
internal class KeptStaticMainFence(private val main:Handler) {
    fun <T> call(block:()->T):T {
        if(Looper.myLooper()==main.looper)return block()
        val claim=AtomicBoolean(true)
        val task=FutureTask{
            if(!claim.compareAndSet(true,false))throw IOException("原项目窗口核对已结束")
            block()
        }
        if(!main.post(task))throw IOException("原项目窗口主队列不可用")
        var interrupted=false;var entered=false
        try{
            while(true)try{
                return if(entered)task.get() else task.get(1500L,TimeUnit.MILLISECONDS)
            }catch(_:TimeoutException){
                if(claim.compareAndSet(true,false)){
                    main.removeCallbacks(task);task.cancel(false)
                    throw IOException("未进入原项目窗口核对")
                }
                entered=true
            }catch(_:InterruptedException){
                interrupted=true
                if(claim.compareAndSet(true,false)){
                    main.removeCallbacks(task);task.cancel(false)
                    throw IOException("原项目窗口核对已停止")
                }
                entered=true
            }catch(failure:ExecutionException){
                throw (failure.cause as? Exception?:IOException("原项目窗口不可核对"))
            }
        }finally{
            if(interrupted)Thread.currentThread().interrupt()
        }
    }
}
