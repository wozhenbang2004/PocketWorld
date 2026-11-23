package dev.pocketengine.ui

import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import dev.pocketengine.features.local.preview.OwnerKeptStaticPages
import dev.pocketengine.features.local.project.PortableOfflinePage
import dev.pocketengine.features.workspace.WorkspaceModule
import dev.pocketengine.ui.web.KeptStaticEffects
import dev.pocketengine.ui.web.KeptStaticMainFence
import java.util.concurrent.Executors
import org.json.JSONObject

/** A one-document picker return, separate from a live shelf, formal file or Work export. */
internal class KeptPortablePageDelivery(private val activity:ComposeActivity,private val changed:()->Unit){
    companion object{private const val LIFETIME_MS=120_000L;private const val WRITE_MS=20_000L}
    data class Proof(val page:OwnerKeptStaticPages.Page,val name:String,val bytes:Int,val sha:String){
        constructor(page:OwnerKeptStaticPages.Page,copy:PortableOfflinePage.Copy):this(
            page,"offline-page-"+page.versionId().take(8)+".html",copy.byteSize(),copy.sha256())
        fun metadata()=JSONObject().put("page",page.metadata()).put("name",name)
            .put("bytes",bytes).put("sha256",sha)
    }
    private data class Target(val uri:Uri,val documentId:String,val name:String)
    data class Binding(val proof:Proof,val workspace:WorkspaceModule,val projectCreatedAt:Long)
    private class Attempt(val id:String,val binding:Binding,val route:()->Boolean){
        val proof=binding.proof
        val until=SystemClock.elapsedRealtime()+LIFETIME_MS
        var pauseSeen=false
        var phase="choosing"
        var target:Target?=null
        var destinationName:String?=null
        var signal:CancellationSignal?=null
        var flight:Any?=null
        var interrupted:(()->Unit)?=null
        var timeout:Runnable?=null
    }
    private val main=Handler(Looper.getMainLooper())
    private val fence=KeptStaticMainFence(main)
    private val io=Executors.newSingleThreadExecutor()
    private val picker=AttachmentExportPicker(activity.activityResultRegistry,"kept_portable_html",::picked)
    private var attempt:Attempt?=null
    private var closed=false

    fun busy()=attempt!=null
    private fun owns(value:Attempt)=!closed&&attempt===value&&activity.keptPageExportOwnerCurrent()&&
        SystemClock.elapsedRealtime()<value.until&&value.route()
    private fun clear(original:Attempt){
        if(attempt!==original)return
        original.interrupted?.invoke()
        attempt=null;original.target=null;original.flight=null
        original.signal?.cancel();original.signal=null
        original.timeout?.let(main::removeCallbacks);original.timeout=null
        picker.close()
    }
    fun arm(id:String,binding:Binding,route:()->Boolean):JSONObject{
        check(!closed&&attempt==null&&activity.keptPageExportOwnerCurrent()&&route()){"原项目不能准备另一个离线副本"}
        val original=Attempt(id,binding,route);attempt=original
        original.timeout=Runnable{if(attempt===original){clear(original);changed()}}.also{main.postDelayed(it,LIFETIME_MS)}
        when(picker.launch(id,"text/html",binding.proof.name)){
            OutcomeExportLaunch.NOT_STARTED->original.phase="not_written"
            OutcomeExportLaunch.UNKNOWN->if(original.phase=="choosing")original.phase="chooser_unconfirmed"
            OutcomeExportLaunch.REQUESTED->Unit
        }
        return metadata(original)
    }
    fun background(){
        val original=attempt?:return
        if(!original.pauseSeen&&original.phase in setOf("choosing","chooser_unconfirmed","checking_target")){
            original.pauseSeen=true // Only the one expected OS picker pause may retain this nonce.
        }else if(original.phase=="writing"){
            original.interrupted?.invoke()
        }else clear(original)
    }
    fun retire(){attempt?.let(::clear)}
    fun close(){closed=true;retire();picker.close();io.shutdownNow()}

    private fun metadata(value:Attempt)=value.proof.metadata().put("kind","owner_kept_portable_delivery")
        .put("requestId",value.id).put("phase",value.phase)
        .put("destinationName",value.destinationName?:JSONObject.NULL)

    fun snapshot():JSONObject?{
        val original=attempt?:return null
        if(!owns(original)){clear(original);return null}
        return metadata(original)
    }
    fun original(id:String,project:String,version:String,manifest:String,sha:String):Binding{
        val selected=attempt?.takeIf{owns(it)&&it.id==id&&it.proof.page.projectId()==project&&
            it.proof.page.versionId()==version&&it.proof.page.manifestIdentity()==manifest&&it.proof.sha==sha}
            ?:error("不是原项目这次离线副本的回执")
        return selected.binding
    }

    private fun inspect(uri:Uri,signal:CancellationSignal,expectBytes:Long):Target?{
        if(!ProjectPackageDocument.allowed(activity,uri))return null
        val document=DocumentsContract.getDocumentId(uri)
        return activity.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null,signal)?.use{cursor->
            if(!cursor.moveToFirst()||cursor.isNull(0)||cursor.isNull(1)||cursor.getLong(1)!=expectBytes)return@use null
            val name=cursor.getString(0)?.takeIf{it.length in 1..240&&it.endsWith(".html",ignoreCase=true)&&
                it.none{c->c.isISOControl()||Character.getType(c)==Character.FORMAT.toInt()}}?:return@use null
            Target(uri,document,name)
        }
    }
    private fun picked(id:String,uri:Uri?){
        val original=attempt?.takeIf{it.id==id&&it.phase in setOf("choosing","chooser_unconfirmed")}?:return
        if(!owns(original)){clear(original);changed();return}
        if(uri==null){original.phase="cancelled";changed();return}
        // Android may already have created an empty document. Never open an output here.
        val signal=CancellationSignal()
        original.phase="checking_target";original.signal=signal;changed()
        try{io.execute{
            val target=runCatching{inspect(uri,signal,0L)}.getOrNull()
            main.post{
                if(attempt!==original||original.phase!="checking_target")return@post
                original.signal=null
                if(!owns(original))clear(original)
                else if(target==null)original.phase="not_written"
                else{original.target=target;original.destinationName=target.name;original.phase="target_selected"}
                changed()
            }
        }}catch(_:Exception){original.signal=null;original.phase="not_written";changed()}
    }

    fun dismiss(id:String,proof:Proof){
        val original=attempt?.takeIf{it.id==id&&it.proof==proof&&owns(it)&&it.phase!="writing"}
            ?:error("原离线副本操作已结束")
        clear(original)
    }
    fun write(id:String,proof:Proof,window:()->Boolean,read:((()->Boolean)->PortableOfflinePage.Copy),
              done:(JSONObject?,String?)->Unit){
        val original=attempt?.takeIf{it.id==id&&it.proof==proof&&it.phase=="target_selected"&&owns(it)&&window()}
            ?:error("请先在原项目核对原目标和保留版本")
        val target=checkNotNull(original.target)
        original.target=null // One explicit Write consumes the opaque Android result.
        val flight=Any()
        val signal=CancellationSignal()
        original.flight=flight;original.signal=signal;original.phase="writing"
        val deadline=SystemClock.elapsedRealtime()+WRITE_MS
        fun current()=runCatching{fence.call{owns(original)&&original.flight===flight&&
            SystemClock.elapsedRealtime()<deadline&&window()}}.getOrDefault(false)
        fun requireCurrent(){check(current()){"原离线副本写入窗口已离开"}}
        val alarm=Runnable{
            if(attempt===original&&original.flight===flight){
                original.flight=null;original.interrupted=null;signal.cancel();original.signal=null;original.phase="unknown"
                done(metadata(original),null);changed()
            }
        }
        original.interrupted={
            if(attempt===original&&original.flight===flight){
                main.removeCallbacks(alarm);original.flight=null;original.interrupted=null
                signal.cancel();original.signal=null;original.phase="unknown"
                done(null,"原写入回执未核对；请检查所选位置，不会重写")
            }
        }
        main.postDelayed(alarm,WRITE_MS)
        changed()
        try{io.execute{
            var entered=false
            var effect:KeptStaticEffects.Flight?=null
            val status=try{
                requireCurrent()
                effect=KeptStaticEffects.begin(proof.page.projectId(),KeptStaticEffects.Kind.WRITE)
                    ?:error("请先等原保留网页操作结束")
                requireCurrent()
                val copy=read(::current)
                check(Proof(proof.page,copy)==proof)
                check(inspect(target.uri,signal,0L)==target)
                requireCurrent()
                val bytes=copy.bytes()
                val result=try{ProjectFileDeliveryBytes.deliver(bytes,proof.sha,{
                    requireCurrent()
                    entered=true // A provider may not obey the contract even if open returns no descriptor.
                    activity.contentResolver.openFileDescriptor(target.uri,"rw",signal)?.let{file->
                        try{
                            check(file.statSize==0L){"原系统文件不再为空，未覆盖现有内容"}
                            ParcelFileDescriptor.AutoCloseOutputStream(file)
                        }catch(failure:Exception){file.close();throw failure}
                    }
                },{
                    requireCurrent()
                    activity.contentResolver.openAssetFileDescriptor(target.uri,"r",signal)?.let{file->
                        try{file.createInputStream()}catch(failure:Exception){file.close();throw failure}
                    }
                },::requireCurrent)}finally{bytes.fill(0)}
                if(result=="verified"&&inspect(target.uri,signal,proof.bytes.toLong())==target&&current())"verified"
                else if(entered)"unknown" else "not_written"
            }catch(_:Exception){if(entered)"unknown" else "not_written"}
            finally{effect?.let(KeptStaticEffects::end)}
            main.post{
                main.removeCallbacks(alarm)
                if(attempt!==original||original.flight!==flight)return@post
                original.flight=null;original.interrupted=null;original.signal=null
                if(!owns(original)||!window()){
                    original.phase=if(entered)"unknown" else "not_written"
                    done(null,"原写入回执未核对；请检查所选位置，不会重写")
                }else{
                    original.phase=status
                    done(metadata(original),null)
                }
                changed()
            }
        }}catch(_:Exception){
            main.removeCallbacks(alarm);original.flight=null;original.interrupted=null;original.signal=null;original.phase="not_written"
            done(metadata(original),null);changed()
        }
    }
}
