package dev.pocketengine.ui

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
