package dev.pocketengine.ui

import org.json.JSONObject

/** Native workspace read/write reply. Missing text is not an empty document. No byte body in this position. */
internal data class FormalFileObservation(val project:String,val path:String,val version:Int,val revision:String,val sha:String,
                                          val size:Long,val kind:String,val representation:String) {
    fun matches(p:String?,f:String?)=project==p && path==f
    fun json()=JSONObject().put("projectId",project).put("path",path).put("version",version).put("revisionId",revision)
        .put("sha256",sha).put("bytes",size).put("kind",kind).put("representation",representation)
    companion object {
        private val id=Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        private val hex=Regex("[a-f0-9]{64}")
        fun read(row:JSONObject,project:String,path:String,expected:Int?):Pair<FormalFileObservation,String?>? {
            val v=row.opt("version") as? Number?:return null
            if(v.toLong() !in 1..Int.MAX_VALUE.toLong() || v.toDouble()!=v.toLong().toDouble() || expected!=null && v.toInt()!=expected ||
                row.optString("projectId")!=project || row.optString("path")!=path || row.opt("deleted")!=false)return null
            val kind=row.optString("kind");val size=row.opt("sizeBytes") as? Number?:return null
            val revision=row.optString("revisionId");val sha=row.optString("sha256")
            if(kind !in setOf("text","binary") || size.toLong() !in 0L..(2L*1024*1024) || size.toDouble()!=size.toLong().toDouble() ||
                !id.matches(revision) || !hex.matches(sha))return null
            val source=(row.opt("content") as? String)?.takeIf { row.opt("contentAvailable")==true && kind=="text" && it.length<=65536 }
            val text=source?.takeIf { value -> val bytes=value.toByteArray(Charsets.UTF_8);try {
                bytes.size.toLong()==size.toLong() && ProjectFileDeliveryBytes.sha(bytes)==sha
            } finally {bytes.fill(0)} }
            val observed=when {
                text!=null -> "text"
                kind=="binary" -> "binary"
                row.opt("contentAvailable")==false && row.opt("requiresNativeByteRead")==true -> "text_limit"
                else -> "unverified"
            }
            return FormalFileObservation(project,path,v.toInt(),revision,sha,size.toLong(),kind,observed) to text
        }
    }
}
internal data class HeldFormalDraft(val project:String,val path:String,val version:Int,val text:String) {
    fun matchesDifferentText(p:String,f:String,value:String)=project==p && path==f && text!=value
}
