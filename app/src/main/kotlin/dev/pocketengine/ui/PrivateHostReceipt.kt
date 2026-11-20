package dev.pocketengine.ui

import org.json.JSONObject

/** Only the message written with the original private TaskStore admission may claim this link. */
internal object PrivateHostReceipt {
    fun fromMessage(row: JSONObject,conversation: String): String? {
        val source=row.optJSONObject("privateHostReceipt") ?: return null
        val id=source.optString("taskId")
        return id.takeIf { row.opt("role")=="assistant" && !row.optBoolean("importAssignedIdentity") && row.optLong("revision")>0 &&
            source.opt("version") in setOf(1,2) && source.opt("conversationId")==conversation && id==row.opt("taskId") && id.matches(Regex("[a-f0-9-]{36}")) }
    }
    internal data class FinalView(val text: String?)
    fun finalView(row: JSONObject,conversation: String): FinalView? {
        if(fromMessage(row,conversation)==null||row.optLong("revision")!=1L||row.optJSONObject("privateHostReceipt")?.opt("version")!=2)return null
        val raw=row.optJSONObject("privateHostFinalText") ?: return FinalView(null)
        val text=raw.opt("text") as? String ?: return null
        if(raw.length()!=4||raw.opt("format")!=dev.pocketengine.features.remote.PrivateHostFinalText.FORMAT||
            !(raw.opt("jobId") as? String).orEmpty().matches(Regex("[a-zA-Z0-9_-]{1,80}"))||
            text.toByteArray(Charsets.UTF_8).size !in 1..12000||!dev.pocketengine.features.remote.PrivateHostFinalText.safeReply(text)||
            runCatching{dev.pocketengine.features.remote.PrivateHostFinalText.digest(text)}.getOrNull()!=raw.opt("sha256"))return null
        return FinalView(text)
    }
    fun verify(task: UiTask,conversation: String,id: String) {
        val raw=task.raw
        require(task.id==id && raw.opt("id")==id && raw.opt("operationId")==id && raw.opt("conversationId")==conversation &&
            raw.opt("feature")=="remote" && raw.opt("command")=="submitJob" && raw.opt("actorId")=="owner" && raw.opt("spaceId")=="private") { "没有找到属于这条回执的原私人主机任务" }
    }
}
