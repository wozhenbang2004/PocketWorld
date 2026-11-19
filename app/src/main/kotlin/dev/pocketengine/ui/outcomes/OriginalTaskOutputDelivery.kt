package dev.pocketengine.ui

import android.content.Context
import android.net.Uri
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.attachments.AttachmentOutcomeHost
import dev.pocketengine.features.attachments.OriginalTaskOutputPin
import org.json.JSONObject

/** Original private result -> the SAME result of its existing CreateDocument request. No other target, persisted grant or retry. */
internal object OriginalTaskOutputDelivery {
    fun selected(task:String?,file:JSONObject)=!task.isNullOrBlank()&&OriginalTaskOutputPin.kind(file.opt("sourceKind"))&&task==file.opt("sourceTaskId")
    fun write(context:Context,host:AttachmentOutcomeHost,task:String,file:JSONObject,uri:Uri,c:RunControl):JSONObject{
        val source=host.originalTaskOutputForExport(task,file,c);val id=source.getString("id")
        val sha=source.getString("sha256");val bytes=source.getLong("bytes")
        check(ProjectPackageDocument.allowed(context,uri)){"请选择 Android 保存面板中的原文档位置；没有改写其他位置"}
        val before=ProjectPackageDocument.name(context,uri,c,c::check)
        fun stillOriginal(){c.check();check(host.originalTaskOutputForExport(task,file,c).getString("identity")==source.getString("identity")){"原成果来源已变化"}}
        stillOriginal()
        val written=host.export(task,id,sha,uri,c)
        check(written.opt("taskId")==task&&written.opt("id")==id&&written.opt("sha256")==sha&&written.opt("verified")==true&&
              (written.opt("bytes") is Long||written.opt("bytes") is Int)&&written.optLong("bytes",-1)==bytes){"没有取得原文件写入回执；不自动重写"}
        stillOriginal()
        val target=ProjectPackageDocument.taskOutput(context,uri,before,bytes,sha,c,c::check)
        stillOriginal()
        val status=target.getString("status");val same=target.opt("protocol")==ProjectPackageTargetBytes.TASK_OUTPUT_PROTOCOL&&status=="verified"&&
                target.opt("sha256")==sha&&target.optLong("bytes",-1)==bytes
        val where=target.optString("displayName").let{if(it.isNotBlank())"「$it」" else "所选文档"}+"（"+target.getString("provider")+"）"
        val notice=when{
            same->"$where 已回读 $bytes 字节，SHA-256 与原件一致（${sha.take(12)}…）。这是本次保存时的核对。"
            status=="content_mismatch"->"$where 写入后实际字节或 SHA-256 与原件不符，不能当作交付成功。原文件保留，没有重写。"
            status=="destination_changed"->"写入后所选文档的标识发生变化，不能确认交付。原文件保留，请在原位置核对。"
            else->"$where 已收到写入，但不能回读核对，不能确认交付。原文件保留，没有重试。"
        }
        return JSONObject().put("taskId",task).put("id",id).put("status",if(same)"exported" else "written_unverified")
            .put("verified",same).put("targetReadback",target).put("outcomeNotice",notice)
    }
}
