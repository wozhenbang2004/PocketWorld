package dev.pocketengine.ui

import android.content.Context
import android.net.Uri
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.attachments.AttachmentBook
import dev.pocketengine.features.attachments.AttachmentOutcomeHost
import dev.pocketengine.features.attachments.OriginalTaskOutputPin
import org.json.JSONObject

internal enum class OwnerCopyStatus(val value:String) {
    VERIFIED("verified"), COULD_NOT_READ("could_not_read"), CONTENT_MISMATCH("content_mismatch"),
    DESTINATION_CHANGED("destination_changed"), NO_TARGET("no_target"), UNCONFIRMED("unconfirmed")
}
internal data class OwnerCopyResult(val status:OwnerCopyStatus,val displayName:String?=null,val verifiedBytes:Long?=null)

/** Original private result -> the SAME result of its existing CreateDocument request. No other target, persisted grant or retry. */
internal object OriginalTaskOutputDelivery {
    fun write(context:Context,host:AttachmentOutcomeHost,task:String?,file:JSONObject,uri:Uri,c:RunControl):OwnerCopyResult{
        val source=if(task!=null&&OriginalTaskOutputPin.kind(file.opt("sourceKind"))&&task==file.opt("sourceTaskId"))
            host.originalTaskOutputForExport(task,file,c) else null
        val id=file.getString("id");val sha=file.getString("sha256")
        val readableTarget=ProjectPackageDocument.allowed(context,uri)
        // Keep the stronger existing author/provenance contract for these pinned task outputs.
        check(source==null||readableTarget&&source.getString("id")==id&&source.getString("sha256")==sha){
            "请选择 Android 保存面板中的原文件位置；没有改写其他位置"
        }
        val before=if(readableTarget)ProjectPackageDocument.name(context,uri,c,c::check) else null
        fun stillOriginal(){
            c.check()
            if(source==null)host.requireReference(task,id,sha)
            else check(host.originalTaskOutputForExport(checkNotNull(task),file,c).getString("identity")==source.getString("identity")){"原成果来源已变化"}
            c.check()
        }
        stillOriginal()
        val written=host.export(task,id,sha,uri,c)
        val bytes=written.optLong("bytes",-1)
        check(written.opt("taskId")==task.orEmpty()&&written.opt("id")==id&&written.opt("sha256")==sha&&written.opt("verified")==true&&
              (written.opt("bytes") is Long||written.opt("bytes") is Int)&&bytes in 0..AttachmentBook.MAX_FILE_BYTES&&
              (source==null||source.getLong("bytes")==bytes)){"没有取得原文件写入回执；不自动重写"}
        stillOriginal()
        // A provider without readable document authority remains a write-only observation.
        if(!readableTarget)return OwnerCopyResult(OwnerCopyStatus.COULD_NOT_READ)
        val target=ProjectPackageDocument.taskOutput(context,uri,before,bytes,sha,c,c::check)
        stillOriginal()
        val name=target.optString("displayName").takeIf {it.isNotBlank()}
        if(target.opt("protocol")!=ProjectPackageTargetBytes.TASK_OUTPUT_PROTOCOL)return OwnerCopyResult(OwnerCopyStatus.COULD_NOT_READ)
        return when(target.opt("status")){
            "verified"->if(target.opt("sha256")==sha&&target.optLong("bytes",-1)==bytes)OwnerCopyResult(OwnerCopyStatus.VERIFIED,name,bytes)
                else OwnerCopyResult(OwnerCopyStatus.COULD_NOT_READ)
            "content_mismatch"->OwnerCopyResult(OwnerCopyStatus.CONTENT_MISMATCH,name)
            "destination_changed"->OwnerCopyResult(OwnerCopyStatus.DESTINATION_CHANGED)
            else->OwnerCopyResult(OwnerCopyStatus.COULD_NOT_READ,name)
        }
    }
}
