package dev.pocketengine.ui

import org.json.JSONObject

/** Bind task-local feedback only to the identity produced by the native reviewed question. */
internal fun ownerQuestionWebInput(identity: JSONObject, text: String): JSONObject {
    require(text.isNotBlank() && text.length <= 4000) { "问题回复必须是1至4000字符" }
    val original = JSONObject(identity.toString())
    return JSONObject().put("taskId", original.getString("taskId")).put("questionIdentity", original).put("text", text)
}

/** A returned transport result is not proof the original question accepted the answer. */
internal fun requireOwnerQuestionAccepted(result: JSONObject?): JSONObject {
    check(result != null && result.opt("accepted") is Boolean && result.getBoolean("accepted")) {
        "原回答未确认接收，请重新核对原任务后再决定。"
    }
    return result
}
