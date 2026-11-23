package dev.pocketengine.ui

import dev.pocketengine.features.workspace.VersionedWorkspace
import dev.pocketengine.features.workspace.WorkspaceModule
import org.json.JSONArray
import org.json.JSONObject

/** Only the explicit history reader emits this. Never serialize version content or continuedFromArtifact. */
internal data class FormalHistoryPin(val project:String,val path:String,val version:Int,val revision:String,val kind:String,val size:Long,val sha:String) {
    fun json()=JSONObject().put("projectId",project).put("path",path).put("version",version).put("revisionId",revision)
        .put("kind",kind).put("bytes",size).also { if(kind!="tombstone")it.put("sha256",sha) }
    companion object {
        private val uuid=Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        private val hash=Regex("[a-f0-9]{64}")
        private const val EMPTY="e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        private fun integer(o:Any?)=when(o){is Int->o.toLong();is Long->o;else->null}
        fun from(row:JSONObject,project:String,path:String):FormalHistoryPin?{
            if(row.opt("projectId")!=project||row.opt("path")!=path||!uuid.matches(project)||path.isEmpty())return null
            val version=integer(row.opt("version"))?.takeIf{it in 1..Int.MAX_VALUE.toLong()}?.toInt()?:return null
            val revision=row.optString("revisionId");val size=integer(row.opt("sizeBytes"))?.takeIf{it in 0L..2_097_152L}?:return null
            val kind=row.optString("kind");val sha=row.optString("sha256");val deleted=row.opt("deleted") as? Boolean?:return null
            val shape=if(deleted) kind=="tombstone"&&size==0L&&sha=="absent" else
                (kind=="binary"||kind=="text")&&hash.matches(sha)&&(size!=0L||sha==EMPTY)
            if(!uuid.matches(revision)||row.opt("tombstone")!=deleted||!shape)return null
            return FormalHistoryPin(project,path,version,revision,kind,size,sha)
        }
    }
}


/** Read already-observed metadata only on an explicit private-file request. Nothing enters general UI state. */
internal fun originalHistoryComparison(rows:List<UiFileRevision>,project:String,path:String,version:Int,versions:String):JSONObject{
    require(rows.size in 2..8 && version>0 && rows.maxOf { it.version } == version && rows.joinToString(","){it.version.toString()}==versions){"原文件版本清单已变，请重新读取版本记录"}
    val seenVersions=mutableSetOf<Int>();val seenRevisions=mutableSetOf<String>();val out=JSONArray()
    for(row in rows){
        val pin=row.original?:error("旧记录没有可核对的原文件身份，请重新读取")
        require(pin.project==project && pin.path==path && row.version==pin.version && row.deleted==(pin.kind=="tombstone") &&
            seenVersions.add(pin.version) && seenRevisions.add(pin.revision)){"不是这一个原文件的完整版本记录"}
        out.put(JSONObject().put("version",row.version).put("deleted",row.deleted).put("original",pin.json()))
    }
    return JSONObject().put("format","pocketworld.formal-file-observed-history.v1").put("projectId",project).put("path",path).put("version",version).put("versions",versions).put("items",out)
}


private const val FORMAL_HISTORY_TEXT_LIMIT = 64L * 1024

/** The pair is drawn from the same observed native history. It is never an editor selection or a write ticket. */
internal data class FormalHistoryTextPair(
    val head:FormalHistoryPin,val before:FormalHistoryPin,val after:FormalHistoryPin
) {
    fun matchesHead(value:FormalFileObservation?):Boolean =
        value!=null && value.project==head.project && value.path==head.path && value.version==head.version &&
            value.revision==head.revision && value.sha==head.sha && value.size==head.size &&
            value.kind=="text" && value.representation=="text"

    private fun matchesLatestHead(actual:VersionedWorkspace.HeadObservation):Boolean =
        actual.authorityKnown && !actual.deleted && actual.state=="current" && actual.projectId==head.project &&
            actual.path==head.path && actual.version==head.version && actual.revisionId==head.revision &&
            actual.sha256==head.sha && actual.sizeBytes==head.size

    /** Native head authority only; can be rechecked on the main thread without I/O. */
    fun latestHeadMatches(workspace:WorkspaceModule):Boolean =
        matchesLatestHead(workspace.currentFileHeadForHost(head.project,head.path))

    fun read(workspace:WorkspaceModule):JSONObject {
        fun exact(pin:FormalHistoryPin):JSONObject {
            val raw=runCatching {
                workspace.handle("read",JSONObject().put("projectId",pin.project).put("path",pin.path).put("version",pin.version))
            }.getOrElse { error("未取得所选正式版本，未使用其他版本。") }
            val (observation,text)=FormalFileObservation.read(raw,pin.project,pin.path,pin.version)
                ?: error("未取得所选正式版本，未使用其他版本。")
            check(raw.opt("ok")!=false && text!=null && '\u0000' !in text && observation.representation=="text" &&
                observation.kind=="text" && observation.revision==pin.revision && observation.sha==pin.sha &&
                observation.size==pin.size && observation.size in 0L..FORMAL_HISTORY_TEXT_LIMIT) {
                "未取得所选两版的完整文字，未显示截断内容或其他版本。"
            }
            return pin.json().put("text",text)
        }
        val changed="正式文件的当前版本已变化或暂不能核对，请重新读取版本记录。"
        // Ordinary files need not yet have a transient native head. Bootstrap only
        // this already selected path on IO; its exact reads refresh that same entry.
        val observed=runCatching { workspace.refreshFileHeadForHost(head.project,head.path) }.getOrElse { error(changed) }
        check(matchesLatestHead(observed)) { changed }
        val left=exact(before);val right=exact(after)
        // Reject a new head even before its notice reaches the editor; it supplies no pair text.
        check(latestHeadMatches(workspace)) { changed }
        return JSONObject().put("format","pocketworld.formal-file-text-pair.v1").put("head",head.json())
            .put("before",left).put("after",right).put("completeText",true).put("readOnly",true)
    }
}

internal fun selectFormalHistoryTextPair(rows:List<UiFileRevision>,version:Int,selection:JSONObject):FormalHistoryTextPair {
    require(selection.keys().asSequence().toSet()==setOf("beforeVersion","beforeRevisionId","afterVersion","afterRevisionId")) {
        "请明确选择同一正式文件的两个版本。"
    }
    fun selected(side:String):FormalHistoryPin {
        val number=selection.opt(side+"Version")
        val revision=selection.opt(side+"RevisionId")
        val pin=rows.singleOrNull { it.version==number && it.original?.revision==revision }?.original
        require(number is Int && number>0 && pin!=null && pin.kind=="text" && pin.size in 0L..FORMAL_HISTORY_TEXT_LIMIT) {
            "所选版本没有可完整对照的文字，只保留版本依据。"
        }
        return pin
    }
    val before=selected("before");val after=selected("after")
    val head=rows.singleOrNull { it.version==version }?.original
    require(head!=null && head.kind=="text" && before.version<after.version && before.revision!=after.revision) {
        "请选择这件正式文件的两个不同版本。"
    }
    return FormalHistoryTextPair(head,before,after)
}
