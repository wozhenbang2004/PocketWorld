package dev.pocketengine.ui

import dev.pocketengine.runtime.models.ModelTargetRef
import org.json.JSONArray
import org.json.JSONObject

/** Preserve policies the simplified editor did not change. Native revision/admission checks remain authoritative. */
internal fun modelGroupEditDefinition(
    original: JSONObject?, chosen: List<JSONObject>, attempts: Int, switches: Boolean,
    external: Boolean, externalEdited: Boolean
): JSONObject {
    val priorRows = original?.getJSONArray("candidates")
    val prior = (0 until (priorRows?.length() ?: 0)).map { priorRows!!.getJSONObject(it) }.associateBy { it.getString("candidateId") }
    val sourceAccounts = original?.optJSONObject("allowedAccounts")
    val sourceRefs = sourceAccounts?.optJSONArray("refs")
    require(original == null || sourceAccounts?.optString("mode") == "EXPLICIT" && sourceRefs != null) {
        "原模型组账户许可格式不可编辑，请保留原记录并重新核对。"
    }
    val accounts = linkedSetOf<String>()
    sourceRefs?.let { refs ->
        for (index in 0 until refs.length()) accounts.add(refs.getString(index))
    }
    val originalAccounts = accounts.toSet()
    val retainedIds = chosen.map { it.getString("candidateId") }.toSet()
    val ids = mutableSetOf<String>()
    val rows = JSONArray()
    chosen.forEach { candidate ->
        val row = JSONObject(candidate.toString())
        val id = row.getString("candidateId")
        require(ids.add(id)) { "模型组候选身份重复，请重新打开原组" }
        val target = ModelTargetRef.fromJson(row.getJSONObject("target"))
        val previous = prior[id]
        if (previous != null) {
            require(target == ModelTargetRef.fromJson(previous.getJSONObject("target"))) { "原模型组候选来源已变化，请重新选择" }
            row.put("allowedPurposes", JSONArray(previous.getJSONArray("allowedPurposes").toString()))
            row.put("allowedAudiences", JSONArray(previous.getJSONArray("allowedAudiences").toString()))
        } else {
            require(prior.values.none { old -> old.getString("candidateId") in retainedIds &&
                ModelTargetRef.fromJson(old.getJSONObject("target")).connectionId() == target.connectionId() && target.connectionId() !in originalAccounts }) {
                "此连接有保留但未获准的原候选。请先移除该原候选，再加入新模型。"
            }
            accounts.add(target.connectionId())
            row.put("allowedPurposes", JSONArray(listOf("MAIN", "SUMMARY", "MEMBER")))
            row.put("allowedAudiences", JSONArray(listOf("APP_OWNER")))
        }
        if (previous == null || externalEdited) {
            fun relayPolicy(field: String, permission: String) {
                val old = row.getJSONArray(field)
                val values = (0 until old.length()).map { old.getString(it) }.toMutableSet()
                if (external) values.add(permission) else values.remove(permission)
                row.put(field, JSONArray(values.toList()))
            }
            relayPolicy("allowedPurposes", "RELAY")
            relayPolicy("allowedAudiences", "EXTERNAL_RELAY")
        }
        rows.put(row)
    }
    return JSONObject().put("allowedAccounts", JSONObject().put("mode", "EXPLICIT").put("refs", JSONArray(accounts.toList())))
        .put("candidates", rows).put("attemptPolicy", JSONObject().put("maxAttempts", attempts).put("maxRouteSwitches", if (attempts > 1 && switches) 1 else 0))
}
