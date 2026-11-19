package dev.pocketengine.ui

import org.json.JSONObject

/** Only the message written with the original private TaskStore admission may claim this link. */
internal object PrivateHostReceipt {
    fun fromMessage(row: JSONObject,conversation: String): String? {
        val source=row.optJSONObject("privateHostReceipt") ?: return null
        val id=source.optString("taskId")
        return id.takeIf { row.opt("role")=="assistant" && !row.optBoolean("importAssignedIdentity") && row.optLong("revision")>0 &&
            source.opt("version")==1 && source.opt("conversationId")==conversation && id==row.opt("taskId") && id.matches(Regex("[a-f0-9-]{36}")) }
    }
    fun verify(task: UiTask,conversation: String,id: String) {
        val raw=task.raw
        require(task.id==id && raw.opt("id")==id && raw.opt("operationId")==id && raw.opt("conversationId")==conversation &&
            raw.opt("feature")=="remote" && raw.opt("command")=="submitJob" && raw.opt("actorId")=="owner" && raw.opt("spaceId")=="private") { "没有找到属于这条回执的原私人主机任务" }
    }
}
