package dev.pocketengine.ui

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns as Columns
import android.webkit.MimeTypeMap
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.local.project.ProjectSources
import dev.pocketengine.store.TaskStore
import java.util.Locale
import org.json.JSONObject

/** A new, owner-chosen copy on this phone. No existing path/URI, project writer, or share intent is accepted. */
internal object PrivateProjectDraftDownload {
    private val directory = Environment.DIRECTORY_DOWNLOADS + "/PocketWorld/"
    private val hash = Regex("[a-f0-9]{64}")
    data class Pin(val task:String,val root:String,val revision:String,val sha:String)
    data class Version(val pin:Pin,val run:String,val epoch:Int,val ordinal:Int,val path:String,val name:String,val size:Int) {
        fun view()=JSONObject().put("taskId",pin.task).put("draftId",pin.root).put("revisionId",pin.revision)
            .put("sha256",pin.sha).put("runId",run).put("epoch",epoch).put("ordinal",ordinal).put("bytes",size)
            .put("path",path).put("name",name).put("destination",directory)
    }

    private fun fileName(path:String):String {
        val leaf=path.substringAfterLast('/')
        require(leaf.isNotBlank() && leaf != "." && leaf != ".." && !leaf.startsWith('.') && leaf.toByteArray(Charsets.UTF_8).size<=190 &&
            leaf.none { it.isISOControl() || Character.getType(it)==Character.FORMAT.toInt() || it in "\\/:*?\"<>|" } &&
            leaf.trim()==leaf && !leaf.endsWith('.')) { "原文件名不能原样另存到手机下载目录；未导出或改名。" }
        return leaf
    }
    // The web only names the fixed version it saw. Fetch bytes from the original native artifact, not from the text area.
    private fun read(store:TaskStore,p:Pin):Pair<Version,ByteArray> {
        require(listOf(p.root,p.revision,p.sha).all(hash::matches))
        val root=store.readPrivateProjectEditDraft(p.task)
        check(root.opt("available")==true && root.getString("taskId")==p.task && root.getString("draftId")==p.root) { "原工作或固定稿已不能核对。" }
        val path=root.getString("path")
        val head=if(p.revision==p.root)null else store.ownerProjectDraftHead(p.task,p.root)
        if(head!=null)check(head.getString("taskId")==p.task && head.getString("draftId")==p.root && head.getString("revisionId")==p.revision &&
            head.getString("path")==path && head.getInt("ordinal") in 1..8) { "正在看的保留稿已变化；没有改存其他版本。" }
        val actualSha=head?.getString("sha256")?:root.getString("afterSha256")
        val size=head?.getInt("bytes")?:root.getInt("afterBytes")
        val bytes=(head?.getString("text")?:root.getString("after")).toByteArray(Charsets.UTF_8)
        check(size in 0..24576 && bytes.size==size && actualSha==p.sha && ProjectSources.sha256(bytes)==p.sha) { "没有核对到这一整份改稿的字节。" }
        return Version(p,root.getString("runId"),root.getInt("epoch"),head?.getInt("ordinal")?:0,path,fileName(path),size) to bytes
    }
    fun review(store:TaskStore,p:Pin):Version=read(store,p).let { (v,b)->b.fill(0);v }

    private fun state(ctx:Context,uri:Uri,pending:Boolean):String {
        // Only the exact URI returned by our insert is ever looked up. Never search public Downloads.
        return ctx.contentResolver.query(uri,arrayOf(Columns.DISPLAY_NAME,Columns.RELATIVE_PATH,Columns.IS_PENDING,Columns.OWNER_PACKAGE_NAME),null,null,null)?.use { c->
            check(c.moveToFirst() && c.getString(1)==directory && c.getInt(2)==(if(pending)1 else 0)) { "本次保存位置或公开状态无法核对。" }
            check(c.getString(3)==ctx.packageName) { "不能确认这个文件由本次应用创建。" }
            c.getString(0)?.takeIf { it.isNotBlank() && it.length<=255 && '/' !in it && '\\' !in it && it.none { ch -> ch.isISOControl() || Character.getType(ch)==Character.FORMAT.toInt() } }
                ?:error("系统未返回可核对的文件名。")
        } ?:error("无法核对下载目录返回的文件。")
    }

    fun save(ctx:Context,store:TaskStore,seen:Version,current:()->Unit):JSONObject {
        current()
        val (actual,bytes)=read(store,seen.pin)
        try{
            check(actual==seen) { "原稿已变化，请重新查看。" }
            val resolver=ctx.contentResolver
            val folder=MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val ext=seen.name.substringAfterLast('.',"").lowercase(Locale.ROOT)
            val mime=MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?:"application/octet-stream"
            current()
            val uri=resolver.insert(folder,ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME,seen.name);put(MediaStore.MediaColumns.MIME_TYPE,mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH,directory);put(MediaStore.MediaColumns.IS_PENDING,1)
            }) ?:error("系统未创建新的下载文件；没有打开已有文件。")
            val id=runCatching{ContentUris.parseId(uri)}.getOrDefault(-1)
            check(id>0 && uri==ContentUris.withAppendedId(folder,id)) { "系统未返回本次新文件的身份；未写正文。" }
            var publishAttempted=false
            val c=RunControl()
            fun matches()=ProjectPackageTargetBytes.taskOutput(seen.size.toLong(),seen.pin.sha,
                { resolver.openInputStream(uri) },{current()},c)==ProjectPackageTargetBytes.Outcome.MATCHED
            try {
                current();state(ctx,uri,true)
                resolver.openOutputStream(uri,"w")?.use { out->current();out.write(bytes);out.flush() } ?:error("新文件不可写。")
                current()
                check(matches()) { "新建文件的字节未通过回读，不会把未核对的文件公开到下载目录。" }
                check(review(store,seen.pin)==seen) { "原工作或所选稿已变化；未交付其他版本。" }
                current();state(ctx,uri,true);current()
                publishAttempted=true
                check(resolver.update(uri,ContentValues().apply{put(MediaStore.MediaColumns.IS_PENDING,0)},
                    "${MediaStore.MediaColumns.IS_PENDING} = ?",arrayOf("1"))==1) { "系统没有确认完成。" }
                val name=state(ctx,uri,false)
                check(matches()) { "保存后的目标字节不能核对。" }
                return seen.view().put("status","saved").put("savedName",name)
            }catch(e:Exception){
                // Once a publish may have happened, never remove a visible file or retry the save.
                if(publishAttempted)throw IllegalStateException("下载目录的最终保存状态尚不能核对；请先去 下载/PocketWorld 查看，没有自动再存。",e)
                runCatching{resolver.delete(uri,"${MediaStore.MediaColumns.IS_PENDING} = ?",arrayOf("1"))}
                throw e
            }
        }finally{bytes.fill(0)}
    }
}
