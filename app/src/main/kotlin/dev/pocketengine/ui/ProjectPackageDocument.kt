package dev.pocketengine.ui

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import dev.pocketengine.core.RunControl
import org.json.JSONObject

/** One result from Android's existing CreateDocument contract; not an import, a locator or a persistent grant. */
internal object ProjectPackageDocument {
    private fun printable(s:String?,limit:Int)=s?.takeIf {it.length in 1..limit&&it.none {c->c.isISOControl()||Character.getType(c)==Character.FORMAT.toInt()}}
    fun allowed(context:Context,uri:Uri)=uri.scheme=="content"&&printable(uri.authority,160)!=null&&runCatching {
        DocumentsContract.isDocumentUri(context,uri)&&context.packageManager.resolveContentProvider(uri.authority!!,0)?.let {it.packageName!=context.packageName}==true
    }.getOrDefault(false)
    fun name(context:Context,uri:Uri,c:RunControl,current:()->Unit):String?{
        val signal=CancellationSignal();val stop=Runnable{signal.cancel()};c.onCancel(stop)
        try{current();return runCatching{
            context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null,signal)?.use { cursor->
                if(cursor.moveToFirst())printable(cursor.getString(0),240) else null
            }
        }.getOrNull().also {current()}}
        finally{c.removeCancelAction(stop)}
    }
    fun readBack(context:Context,uri:Uri,before:String?,bytes:Long,sha:String,c:RunControl,current:()->Unit)=
        inspected(context,uri,before,bytes,sha,c,current,false)
    fun taskOutput(context:Context,uri:Uri,before:String?,bytes:Long,sha:String,c:RunControl,current:()->Unit)=
        inspected(context,uri,before,bytes,sha,c,current,true)
    private fun inspected(context:Context,uri:Uri,before:String?,bytes:Long,sha:String,c:RunControl,current:()->Unit,taskFile:Boolean):JSONObject{
        current();val provider=printable(uri.authority,160)?:error("不是原保存位置")
        val open=ProjectPackageTargetBytes.Open {
            val signal=CancellationSignal();val stop=Runnable {signal.cancel()};c.onCancel(stop)
            try{current();context.contentResolver.openFileDescriptor(uri,"r",signal)?.let {ParcelFileDescriptor.AutoCloseInputStream(it)}}
            finally{c.removeCancelAction(stop)}
        }
        val observed=if(taskFile)ProjectPackageTargetBytes.taskOutput(bytes,sha,open,current,c) else ProjectPackageTargetBytes.check(bytes,sha,open,current,c)
        current();val after=name(context,uri,c,current)
        val verdict=when{
            before!=null&&after!=before -> "destination_changed"
            observed==ProjectPackageTargetBytes.Outcome.MATCHED -> "verified"
            observed==ProjectPackageTargetBytes.Outcome.DIFFERENT -> "content_mismatch"
            else -> "could_not_read"
        }
        return JSONObject().put("protocol",if(taskFile)ProjectPackageTargetBytes.TASK_OUTPUT_PROTOCOL else ProjectPackageTargetBytes.PROTOCOL).put("status",verdict).put("provider",provider).also{
            if(after!=null&&before==after)it.put("displayName",after)
            if(verdict=="verified")it.put("bytes",bytes).put("sha256",sha)
        }
    }
    fun verified(proof:JSONObject?,bytes:Long,sha:String):Boolean = proof!=null&&proof.opt("protocol")==ProjectPackageTargetBytes.PROTOCOL&&
        proof.opt("status")=="verified"&&printable(proof.opt("provider") as? String,160)!=null&&bytes in 1..dev.pocketengine.features.local.project.ProjectPackageArchive.MAX_ARCHIVE_BYTES.toLong()&&
        proof.opt("bytes").let {it is Int||it is Long}&&proof.optLong("bytes",-1)==bytes&&sha.matches(Regex("[a-f0-9]{64}"))&&proof.opt("sha256")==sha
    fun restore(status:JSONObject){
        if(status.optString("status")=="exported"&&(status.opt("exported")!=true||status.opt("bytes").let {it !is Int&&it !is Long}||
            !verified(status.optJSONObject("targetReadback"),status.optLong("bytes",-1),status.optString("artifactSha256"))))
            status.put("status","written_unverified").put("exported",false).put("message","旧导出记录只有写入回执，没有目标文件的回读依据；请在原保存位置核对。没有重新写入。")
    }
}
