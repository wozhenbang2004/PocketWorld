package dev.pocketengine.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.pocketengine.EngineApplication
import dev.pocketengine.features.workspace.VersionedWorkspace
import dev.pocketengine.features.workspace.WorkspaceModule
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A retained owner text is not a formal version. Its bytes never pass through a task or service. */
internal class ProjectFormalDrafts(private val app:EngineApplication, private val controller:PocketWorldController) {
    private val shelf=FormalDraftShelf(File(app.noBackupFilesDir,"formal-editor-drafts-v1"))
    private val main=Handler(Looper.getMainLooper())
    private var busy=false
    private data class Scope(val project:String,val path:String?,val version:Int,val epoch:Long)
    private data class Review(val scope:Scope,val id:String,val sha:String,val handle:String,val until:Long)
    private var review:Review?=null
    private data class IndexPin(val project:String,val path:String,val id:String,val sha:String,val fileEpoch:Long)
    private var fromIndex:IndexPin?=null
    companion object {val COMMANDS=setOf("projects.keptDrafts.list","projects.keptDrafts.forFile","projects.keptDrafts.open",
        "projects.keptDrafts.keep","projects.keptDrafts.read","projects.keptDrafts.stage","projects.keptDrafts.discard")}
    private fun string(a:JSONObject,key:String,max:Int)= (a.opt(key) as? String)?.takeIf {it.length in 1..max}?:error("保留原稿的 ${key} 无效")
    private fun number(a:JSONObject,key:String):Int {val n=a.opt(key);require(n is Int || n is Long);return (n as Number).toLong().also {require(it in 0L..Int.MAX_VALUE.toLong())}.toInt()}
    private fun current(s:Scope)=controller.webPrivateOwnerVisible()&&controller.formalFileWindowEpoch()==s.epoch&&
        controller.state.projectId==s.project&&controller.state.filePath==s.path&&controller.state.projects.any {it.id==s.project}&&
        (s.path==null||controller.state.fileVersion==s.version)
    private fun workspace()=app.features.module("workspace") as WorkspaceModule
    private fun matches(head:VersionedWorkspace.HeadObservation,row:JSONObject)=head.authorityKnown&&head.projectId==row.getString("projectId")&&
        head.path==row.getString("path")&&head.version==row.getInt("baseVersion")&&head.revisionId==row.getString("baseRevisionId")&&
        head.sha256==row.getString("baseSha256")&&(if(head.version==0)head.deleted else !head.deleted)
    fun dispatch(action:String,args:JSONObject,window:()->Boolean,done:(JSONObject?,String?)->Unit) {
        require(action in COMMANDS);val mode=action.substringAfterLast('.')
        val project=string(args,"projectId",80);val projectView=mode=="list"||mode=="open"
        val scope=Scope(project,if(projectView)null else string(args,"path",240),if(projectView)0 else number(args,"version"),controller.formalFileWindowEpoch())
        val required=(if(projectView)setOf("projectId") else setOf("projectId","path","version"))+when(mode){
            "open"->setOf("path","keepId","sha256");"read"->setOf("keepId","sha256");"keep"->setOf("baseVersion","baseSha256","text")
            "stage","discard"->setOf("keepId","sha256","reviewHandle");else->emptySet()
        }
        check(args.keys().asSequence().toSet()==required && window() && current(scope)){"原私人项目或文件窗口已变化"}
        check(!busy){"请先等待正在核对的本机原稿"}
        val path=scope.path?:if(mode=="open")string(args,"path",240) else ""
        val id=if(mode in setOf("read","open","stage","discard"))string(args,"keepId",36) else ""
        val sha=if(id.isNotEmpty())string(args,"sha256",64).also {require(it.matches(Regex("[a-f0-9]{64}")))}else ""
        val selected=review
        if(mode=="stage"||mode=="discard"){
            check(selected!=null&&selected.scope==scope&&selected.id==id&&selected.sha==sha&&selected.handle==string(args,"reviewHandle",36)&&
              SystemClock.elapsedRealtime()<selected.until){"上次原稿审阅已结束，请重新打开；没有改变任何文件"};review=null
        }else if(mode in setOf("open","read","keep")) review=null
        val wasEditable=controller.state.fileReady&&!controller.state.fileLoading&&!controller.state.fileSaving
        if(mode=="stage")check(wasEditable){"当前正式文件没有完整可编辑的文字，只能核对以前保留的原稿"}
        fun gate(){check(window()&&current(scope)){"原私人文件窗口已变化；请在原项目核对保留稿，不会写正式版本"}}
        fun editable(){gate();controller.requireFormalTextEditor()}
        if(mode=="keep")check(!controller.state.fileLoading&&!controller.state.fileSaving){"请先等原文件读取或保存完成，文字没有被替换"}
        var headForStage:WorkspaceModule?=null
        busy=true
        try{controller.runPluginCatalogWork(Runnable {
            val outcome=runCatching {
                gate()
                when(mode){
                    "list","forFile"->JSONObject().put("entries",JSONArray(shelf.list(project,scope.path))).put("bodyRead",false)
                    "open"->{
                        val item=shelf.list(project,path).singleOrNull {it.optString("keepId")==id&&it.optString("sha256")==sha}?:error("原保留稿定位已变化，未打开同名文件")
                        val ws=workspace();headForStage=ws
                        val head=runCatching {ws.refreshFileHeadForHost(project,path)}.getOrNull()
                        JSONObject().put("item",item).put("newAbsent",head!=null&&head.authorityKnown&&head.version==0&&head.deleted&&item.getInt("baseVersion")==0)
                    }
                    "keep"->{
                        val text=args.opt("text") as? String?:error("未收到明确选择保留的文字")
                        check(text.length<=FormalDraftShelf.MAX_TEXT){"每份手机保留稿最多65536字符"}
                        val base=number(args,"baseVersion");val claimed=string(args,"baseSha256",64)
                        val held=controller.state.heldFormalDraft
                        val originalHeld=claimed=="native_held"&&held!=null&&held.project==project&&held.path==path&&held.version==base&&held.text==text
                        val original=workspace().fileSnapshotForHost(project,path,base)
                        check(original.projectId==project&&original.path==path&&original.version==base&&
                            (if(base==0)original.tombstone&&(claimed=="absent"||originalHeld) else !original.tombstone&&original.kind=="text"&&(original.sha256==claimed||originalHeld))) {
                            "这份草稿的原项目版本已无法核对；没有代用新版本或覆盖已有保留稿"
                        }
                        check((base!=0||text.isNotEmpty())&&(base==0||ProjectFileDeliveryBytes.sha(text.toByteArray(Charsets.UTF_8))!=original.sha256)){"文字与原始基线相同，不需要另存草稿"}
                        val item=shelf.keep(project,path,base,original.revisionId,original.sha256,text){gate();check(!controller.state.fileLoading&&!controller.state.fileSaving)}
                        JSONObject().put("item",item).put("durablyReadBack",true)
                    }
                    "read","stage"->{
                        val item=shelf.read(project,path,id,sha);val ws=workspace()
                        // A lost/deleted/edit-unavailable baseline must never prevent a read of the user's own kept text.
                        val exact=runCatching {matches(ws.refreshFileHeadForHost(project,path),item)}.getOrDefault(false)
                        if(mode=="stage"){editable();check(exact){"正式基线已经改变，保留稿仍可只读核对，未覆盖当前草稿"};headForStage=ws}
                        JSONObject().put("item",item).put("sameFormalBase",exact)
                    }
                    else->{shelf.discard(project,path,id,sha){gate()};JSONObject().put("removed",true)}
                }
            }
            main.post {busy=false
                if(!window()||!current(scope)){done(null,"已离开原项目窗口；从原文件再核对，不会重发");return@post}
                outcome.mapCatching {out->
                    if(mode=="open"){
                        check(controller.state.filePath==null&&!controller.state.fileSaving)
                        val absent=headForStage?.currentFileHeadForHost(project,path)
                        if(out.getBoolean("newAbsent")&&absent?.authorityKnown==true&&absent.version==0&&absent.deleted)controller.newFile(path) else controller.openFile(path)
                        fromIndex=IndexPin(project,path,id,sha,controller.formalFileWindowEpoch())
                    } else if(mode=="forFile"){
                        val pin=fromIndex
                        if(pin!=null&&pin.project==project&&pin.path==path&&pin.fileEpoch==scope.epoch){
                            val rows=out.getJSONArray("entries")
                            if((0 until rows.length()).any {n->rows.getJSONObject(n).let {it.optString("keepId")==pin.id&&it.optString("sha256")==pin.sha}})
                                out.put("selectedKeepId",pin.id).put("selectedKeepSha256",pin.sha)
                        }
                    } else if(mode=="read"){
                        if(fromIndex?.id==id&&fromIndex?.sha==sha)fromIndex=null
                        val r=Review(scope,id,sha,UUID.randomUUID().toString(),SystemClock.elapsedRealtime()+120000);review=r;out.put("reviewHandle",r.handle)
                    } else if(mode=="stage"){
                        editable();check(headForStage?.let {matches(it.currentFileHeadForHost(project,path),out.getJSONObject("item"))}==true){"原正式版本已变化，没有代用或覆盖"}
                    }
                    out.put("projectId",project).put("path",scope.path?:JSONObject.NULL).put("mode",mode).put("projectVersionWritten",false).put("networkUsed",false)
                }.fold({done(it,null)},{done(null,it.message?:"本机原稿未核对，未改正式文件")})
            }
        })}catch(e:Exception){busy=false;throw e}
    }
}
