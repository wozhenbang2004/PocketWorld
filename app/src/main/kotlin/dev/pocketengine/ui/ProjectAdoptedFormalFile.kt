package dev.pocketengine.ui

import dev.pocketengine.features.local.project.ProjectTextImport
import dev.pocketengine.features.workspace.VersionedWorkspace
import dev.pocketengine.features.workspace.WorkspaceModule
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

/** A read position minted only from a real native file receipt or a fresh equality review.
 * It cannot authorize a version write, execute a file or replace an existing editor. */
internal class ProjectAdoptedFormalFile private constructor(
    val project:String, val checkout:String, val path:String, val version:Int,
    val revision:String, val sha:String, val bytes:Long, private val evidence:String
) {
    val handle=UUID.randomUUID().toString()
    fun position():JSONObject=JSONObject().put("handle",handle).put("projectId",project).put("checkoutId",checkout)
        .put("path",path).put("version",version).put("revisionId",revision).put("sha256",sha).put("bytes",bytes).put("evidence",evidence)
    private fun same(head:VersionedWorkspace.HeadObservation)=head.authorityKnown && !head.deleted && head.projectId==project &&
        head.path==path && head.version==version && head.revisionId==revision && head.sha256==sha && head.sizeBytes==bytes

    class Loaded internal constructor(val pin:ProjectAdoptedFormalFile,val content:String,private val workspace:WorkspaceModule) {
        // Main-thread guard: commits, catalog removal and controller close invalidate this no-IO native head.
        fun stillCurrent()=pin.same(workspace.currentFileHeadForHost(pin.project,pin.path))
    }
    fun read(ws:WorkspaceModule):Loaded {
        check(same(ws.refreshFileHeadForHost(project,path))) { "正式文件已经变化或不可核对；仍保留副本，未代开最新版本" }
        val exact=ws.fileSnapshotForHost(project,path,version)
        check(!exact.tombstone && exact.kind=="text" && exact.projectId==project && exact.path==path && exact.version==version &&
            exact.revisionId==revision && exact.sizeBytes==bytes && exact.sha256==sha) { "正式项目的原文件修订不符，未打开" }
        val original=exact.bytes()
        try {
            check(original.size.toLong()==bytes && ProjectFileDeliveryBytes.sha(original)==sha)
            val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(original)).toString()
            check(text.length<=65536 && same(ws.currentFileHeadForHost(project,path))) { "原正式文字已变化或超过编辑器上限" }
            return Loaded(this,text,ws)
        } finally {original.fill(0)}
    }

    companion object {
        private val id=Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        private val digest=Regex("[a-f0-9]{64}")
        private fun source(row:JSONObject,project:String,checkout:String,path:String)=row.optString("projectId")==project &&
            row.optString("checkoutId")==checkout && row.optString("path")==path
        private fun length(bytes:Long)=bytes in 0L..262144L

        fun receipt(row:JSONObject,project:String,checkout:String,path:String):ProjectAdoptedFormalFile? {
            if(row.optString("format")!=ProjectTextImport.RECEIPT || !source(row,project,checkout,path) || row.opt("deleted")!=false ||
                row.optString("kind")!="text" || row.optInt("version")<1 || !id.matches(row.optString("revisionId")) ||
                !digest.matches(row.optString("sha256")) || row.optString("importedHash")!=row.optString("sha256") || !length(row.optLong("sizeBytes",-1)))return null
            if(row.opt("projectVersionCommitted")==true && row.opt("alreadyMatchingVersionObserved")==false ||
                row.opt("projectVersionCommitted")==false && row.opt("alreadyMatchingVersionObserved")==true)
                return ProjectAdoptedFormalFile(project,checkout,path,row.getInt("version"),row.getString("revisionId"),row.getString("sha256"),row.getLong("sizeBytes"),"import_receipt")
            return null
        }
        // A closed/recreated checkout window has no old admission. Only a new equality comparison
        // may recover a read position; equal content is not proof that a previous import succeeded.
        fun matching(row:JSONObject,project:String,checkout:String,path:String,ws:WorkspaceModule):ProjectAdoptedFormalFile? {
            if(row.optString("format")!=ProjectTextImport.REVIEW || !source(row,project,checkout,path) || row.opt("same")!=true ||
                row.opt("deleted")!=false || row.opt("targetDeleted")!=false || row.optInt("expectedVersion")<1 ||
                !digest.matches(row.optString("expectedHash")) || row.optString("expectedTargetHash")!=row.optString("expectedHash"))return null
            val head=ws.fileSnapshotForHost(project,path,0)
            if(head.tombstone || head.kind!="text" || head.projectId!=project || head.path!=path || head.version!=row.getInt("expectedVersion") ||
                !id.matches(head.revisionId) || head.sha256!=row.getString("expectedHash") || !length(head.sizeBytes))return null
            return ProjectAdoptedFormalFile(project,checkout,path,head.version,head.revisionId,head.sha256,head.sizeBytes,"equal_content")
        }
    }
}
