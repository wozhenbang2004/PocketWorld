package dev.pocketengine.ui

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import dev.pocketengine.EngineApplication
import dev.pocketengine.features.workspace.WorkspaceModule
import org.json.JSONObject
import java.util.UUID

/** Owner UI only. A review/picker/destination is not an export. Only the original foreground
 * file can authorize writing; no URI, bytes or reusable write grant are ever persisted. */
internal class ProjectFormalFileExport(
    private val app: EngineApplication, private val controller: PocketWorldController,
    private val choose: (String, String, String) -> OutcomeExportLaunch
) {
    private data class View(val project:String,val path:String,val version:Int)
    private data class Review(val view:View,val proof:String,val handle:String,val until:Long)
    private data class Pending(val view:View,val proof:String,val request:String,@Volatile var uri:Uri?=null)
    // A cloud-backed Android document provider may block. It must never park the controller's shared work queue.
    private val io=java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var signal:android.os.CancellationSignal?=null
    private val main=Handler(Looper.getMainLooper())
    private val prefs=app.getSharedPreferences("original_formal_file_deliveries_v1", Context.MODE_PRIVATE)
    @Volatile private var closed=false
    @Volatile private var pending:Pending?=null
    private var review:Review?=null
    private var busy=false
    companion object{
        val COMMANDS=setOf("projects.fileExport.status","projects.fileExport.review","projects.fileExport.choose","projects.fileExport.write","projects.fileExport.release")
        private val process=UUID.randomUUID().toString()
        private val lock=Any()
        private val active=mutableSetOf<String>()
        private val waiting=setOf("choosing","target_selected","writing")
    }
    private fun owner(v:View)=!closed&&controller.webPrivateOwnerVisible()&&controller.state.projectId==v.project&&controller.state.filePath==v.path&&
        controller.state.fileVersion==v.version&&v.version>0&&controller.state.fileReady&&!controller.state.fileSaving
    private fun key(v:View)=ProjectFileDeliveryBytes.sha((v.project+"\u0000"+v.path).toByteArray(Charsets.UTF_8))
    private fun position(v:View)=synchronized(lock){
        prefs.getString(key(v),null)?.let { raw->check(raw.length<=8192);JSONObject(raw).also {
            check(it.getString("projectId")==v.project&&it.getString("path")==v.path) { "原单文件导出定位不符" }
        } }
    }
    private fun replace(v:View,old:String?,value:JSONObject)=synchronized(lock){
        val prior=position(v)
        check(prior?.optString("requestId")==old) { "另一份导出已改变本机回执，请重新读取" }
        check(value.toString().length<=8192&&prefs.edit().putString(key(v),value.toString()).commit()) { "不能保存原导出定位，没有继续写入" }
    }
    private fun phase(p:Pending,from:Set<String>,next:String,details:(JSONObject)->Unit={}):JSONObject=synchronized(lock){
        val value=position(p.view)?:error("原导出定位不存在")
        check(value.getString("requestId")==p.request&&value.getString("phase") in from)
        value.put("phase",next).put("updatedAt",System.currentTimeMillis());details(value);replace(p.view,p.request,value);value
    }
    private fun public(v:View):JSONObject=synchronized(lock){
        val r=position(v)?:return@synchronized JSONObject().put("phase","none").put("projectId",v.project).put("path",v.path)
        if(r.optString("phase") in waiting && (r.optString("process")!=process||r.optString("requestId") !in active))r.put("phase","unknown")
        val local=pending
        r.remove("process");r.put("canWrite",r.optString("phase")=="target_selected"&&r.optString("destinationName").isNotBlank()&&local!=null&&local.request==r.optString("requestId")&&local.uri!=null&&local.view==v)
            .put("sameVersion",r.optInt("version")==v.version)
    }
    private fun actual(v:View):Pair<JSONObject,ByteArray>{
        check(owner(v)) { "请在已载入的原私人正式文件中核对当前版本" }
        val ws=app.features.module("workspace") as WorkspaceModule
        val head=ws.fileSnapshotForHost(v.project,v.path,0)
        check(head.projectId==v.project&&head.path==v.path&&head.version==v.version&&!head.tombstone&&head.kind=="text"&&head.sizeBytes in 0L..ProjectFileDeliveryBytes.LIMIT.toLong()) { "正式文件已变化、已删除或不是 256 KiB 内的文字；未改用当前版本或草稿" }
        val exported=ws.handle("prepareExport",JSONObject().put("projectId",v.project).put("path",v.path).put("version",v.version))
        val bytes=exported.getString("content").toByteArray(Charsets.UTF_8)
        check(exported.getString("projectId")==v.project&&exported.getString("revisionId")==head.revisionId&&exported.getString("encoding")=="UTF-8"&&
            exported.getLong("sizeBytes")==head.sizeBytes&&bytes.size.toLong()==head.sizeBytes&&exported.getString("sha256")==head.sha256&&
            ProjectFileDeliveryBytes.sha(bytes)==head.sha256&&ProjectFileDeliveryBytes.sha(controller.state.fileBaseline.toByteArray(Charsets.UTF_8))==head.sha256&&owner(v)) { "原正式版本和编辑器已读取的内容不一致，请重新读取；未选择其他文件" }
        val after=ws.fileSnapshotForHost(v.project,v.path,0)
        check(after.version==head.version&&after.revisionId==head.revisionId&&after.sha256==head.sha256&&!after.tombstone) { "读取期间正式文件的原版本已改变，未准备旧预览" }
        return JSONObject().put("projectId",v.project).put("path",v.path).put("version",v.version).put("revisionId",head.revisionId)
            .put("sha256",head.sha256).put("bytes",bytes.size).put("suggestedName",exported.getString("suggestedName")).put("mimeType",exported.getString("mimeType")) to bytes
    }
    private fun same(a:JSONObject,b:JSONObject)=listOf("projectId","path","version","revisionId","sha256","bytes","suggestedName","mimeType").all { a.get(it)==b.get(it) }
    private fun work(v:View,done:(JSONObject?,String?)->Unit,action:()->JSONObject,finish:(JSONObject)->JSONObject={it}){
        check(!busy) { "请等待原文件导出的核对" };busy=true
        try{io.execute(Runnable{
            val value=runCatching(action)
            main.post {busy=false;if(!owner(v))done(null,"原文件窗口已变化；请回原文件查询导出回执，不会自动重试")
                else value.fold({row->runCatching {finish(row)}.fold({done(it,null)},{done(null,it.message?:"原导出不可用")})},{done(null,it.message?:"原导出不可用")})}
        })}catch(e:Exception){busy=false;throw e}
    }
    fun dispatch(action:String,args:JSONObject,done:(JSONObject?,String?)->Unit){
        require(action in COMMANDS)
        val extra=when(action){"projects.fileExport.choose"->setOf("reviewHandle");"projects.fileExport.write","projects.fileExport.release"->setOf("requestId");else->emptySet()}
        require(args.keys().asSequence().all { it in setOf("projectId","path","version")+extra })
        val v=View(args.getString("projectId"),args.getString("path"),args.getInt("version"));check(owner(v))
        when(action){
            "projects.fileExport.status"->done(public(v),null)
            "projects.fileExport.review"->{check(!busy);val old=public(v);check(old.optString("phase") !in waiting+setOf("unknown","write_unknown","written_unverified")){"请先核对上次的原导出回执，未重复打开保存位置"}
                work(v,done,{actual(v).let{(proof,bytes)->bytes.fill(0);proof}}){proof->
                    val handle=UUID.randomUUID().toString();review=Review(v,proof.toString(),handle,SystemClock.elapsedRealtime()+120000)
                    JSONObject(proof.toString()).put("reviewHandle",handle).put("phase","review")
                }}
            "projects.fileExport.choose"->{
                val selected=review;check(selected!=null&&selected.view==v&&selected.handle==args.getString("reviewHandle")&&SystemClock.elapsedRealtime()<selected.until&&pending==null) { "原审阅已结束，或另一个系统文件保存仍待核对；没有重新打开选择器" }
                review=null
                work(v,done,{actual(v).let{(proof,bytes)->bytes.fill(0);check(same(proof,JSONObject(selected.proof)));proof}}){proof->
                    check(SystemClock.elapsedRealtime()<selected.until);val p=Pending(v,proof.toString(),UUID.randomUUID().toString())
                    synchronized(lock){val old=public(v);check(old.optString("phase") !in waiting+setOf("unknown","write_unknown","written_unverified"))
                        val row=JSONObject(proof.toString()).put("phase","choosing").put("requestId",p.request).put("process",process).put("updatedAt",System.currentTimeMillis())
                        replace(v,old.optString("requestId").takeIf {it.isNotEmpty()},row);active.add(p.request);pending=p}
                    val result=runCatching {choose(p.request,proof.getString("mimeType"),proof.getString("suggestedName"))}.getOrDefault(OutcomeExportLaunch.UNKNOWN)
                    if(result!=OutcomeExportLaunch.REQUESTED&&pending===p){phase(p,setOf("choosing"),if(result==OutcomeExportLaunch.NOT_STARTED)"not_started" else "unknown");retire(p)}
                    public(v)
                }}
            "projects.fileExport.write"->{
                val p=pending;check(p!=null&&p.view==v&&p.request==args.getString("requestId")&&p.uri!=null&&!busy);val uri=p.uri!!
                check(public(v).optBoolean("canWrite")) { "原系统文档及名称未能核对；未写入" };p.uri=null
                work(v,done,{
                    val result=try{
                        val (proof,bytes)=actual(v)
                        try{check(same(proof,JSONObject(p.proof)));check(owner(v)&&pending===p)
                            val actualName=name(uri);val originalTarget=position(v)!!
                            if(actualName==null||actualName!=originalTarget.optString("destinationName")||uri.authority!=originalTarget.optString("provider"))throw TargetChanged()
                            check(owner(v)&&pending===p)
                            phase(p,setOf("target_selected"),"writing")
                            val stop=android.os.CancellationSignal();signal=stop
                            try{ProjectFileDeliveryBytes.deliver(bytes,proof.getString("sha256"),{
                                app.contentResolver.openAssetFileDescriptor(uri,"wt",stop)?.let { asset ->
                                    try{asset.createOutputStream()}catch(e:Exception){asset.close();throw e}
                                }
                            },{
                                app.contentResolver.openAssetFileDescriptor(uri,"r",stop)?.let { asset ->
                                    try{asset.createInputStream()}catch(e:Exception){asset.close();throw e}
                                }
                            }){check(owner(v)&&pending===p){"原私人文件窗口已离开"}}}finally{signal=null}
                        }finally{bytes.fill(0)}
                    }catch(_:TargetChanged){"target_changed"}catch(_:Exception){if(position(v)?.optString("phase")=="writing")"write_unknown" else "source_changed"}
                    try{phase(p,setOf("writing","target_selected"),result)}finally{retire(p)}
                    public(v)
                })
            }
            "projects.fileExport.release"->{
                val id=args.getString("requestId");synchronized(lock){
                    val old=public(v);check(old.optString("requestId")==id&&old.optString("phase") in setOf("unknown","write_unknown","written_unverified","choosing","target_selected"))
                    check(!busy&&(!active.contains(id)||pending?.request==id)){"仍有原导出正在处理"}
                    val actual=position(v)!!;check(actual.optString("phase")!="writing"||id !in active)
                    replace(v,id,actual.put("phase","released").put("updatedAt",System.currentTimeMillis()))
                    pending?.takeIf {it.request==id}?.let(::retire)
                };done(public(v),null)
            }
        }
    }
    private fun name(uri:Uri):String?=runCatching{
        app.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {cursor ->
            if(cursor.moveToFirst())cursor.getString(0)?.takeIf{it.length in 1..240&&it.none {c->c.isISOControl()||Character.getType(c)==Character.FORMAT.toInt()}} else null
        }
    }.getOrNull()
    private class TargetChanged:Exception()
    fun picked(id:String,uri:Uri?){
        val p=pending?:return;if(p.request!=id||closed||position(p.view)?.optString("phase")!="choosing")return
        if(uri==null||uri.scheme!="content"||uri.authority.isNullOrBlank()||!runCatching {
                android.provider.DocumentsContract.isDocumentUri(app,uri)&&app.packageManager.resolveContentProvider(uri.authority!!,0)?.let {it.packageName!=app.packageName}==true
            }.getOrDefault(false)){
            phase(p,setOf("choosing"),if(uri==null)"cancelled" else "invalid_target");retire(p);return
        }
        // The returned grant stays in memory. Selecting a destination never writes its contents.
        p.uri=uri
        val provider=uri.authority!!.take(160)
        val claimedName=p.proof.let{JSONObject(it).getString("suggestedName")}
        phase(p,setOf("choosing"),"target_selected"){it.put("provider",provider).put("suggestedName",claimedName)}
        io.execute(Runnable{
            val name=name(uri)
            synchronized(lock){if(!closed&&pending===p&&position(p.view)?.optString("phase")=="target_selected"){
                if(!name.isNullOrBlank())phase(p,setOf("target_selected"),"target_selected"){it.put("destinationName",name)}
                else {phase(p,setOf("target_selected"),"invalid_target");retire(p)}
            }}
        })
    }
    fun discardUnconfirmedPicker(id:String){pending?.takeIf {it.request==id}?.let {p->
        try{phase(p,setOf("choosing","target_selected"),"not_written")}catch(_:Exception){/* Keep the original unsettled locator if storage itself failed. */}finally{retire(p)}
    }}
    private fun retire(p:Pending)=synchronized(lock){active.remove(p.request);if(pending===p)pending=null;p.uri=null}
    fun close(){closed=true;signal?.cancel();io.shutdownNow();review=null;pending?.let {p->
        synchronized(lock){val saved=position(p.view);if(saved?.optString("requestId")==p.request&&saved.optString("phase") in setOf("choosing","target_selected"))
            phase(p,setOf("choosing","target_selected"),"not_written");if(saved?.optString("phase")!="writing")retire(p)}
    }}
}
