package dev.pocketengine.ui

import java.io.OutputStream
import org.json.JSONObject

internal data class SkillExportPin(val id: String, val revision: Int, val digest: String, val fileName: String, val documentSha256: String? = null)

private val skillDigestPattern = Regex("[0-9a-f]{64}")

/** The only scope offered by this native owner surface. Archive/model data never selects it. */

internal fun skillOwnerPrivateScope(): JSONObject = JSONObject().put("ownerId", "owner").put("spaceId", "private")

internal fun skillExportPin(skill: JSONObject): SkillExportPin {
    val id = skill.getString("id"); val revision = skill.getInt("revision"); val digest = skill.getString("digest")
    require(id.isNotBlank() && revision > 0 && skillDigestPattern.matches(digest)) { "当前技能版本信息不完整，请刷新" }
    // Only a picker suggestion, never a filesystem path.
    val name = skill.optString("name", "skill").replace(Regex("[^A-Za-z0-9_-]"), "_").take(80).ifBlank { "skill" }
    return SkillExportPin(id, revision, digest, "$name.zip")
}

internal fun skillDocumentExportPin(reviewed: JSONObject): SkillExportPin {
    val pin = skillExportPin(reviewed)
    val documentSha256 = reviewed.getString("documentSha256")
    require(skillDigestPattern.matches(documentSha256) && reviewed.optBoolean("singleDocumentOnly")) { "单文件审阅信息不完整，请重新检查" }
    return pin.copy(fileName = "SKILL.md", documentSha256 = documentSha256)
}

internal fun skillInspectionIsCurrent(inspection: JSONObject, targetId: String?, current: JSONObject?): Boolean {
    val incoming = inspection.optJSONObject("package") ?: return false
    if (!skillDigestPattern.matches(incoming.optString("digest"))) return false
    val comparison = inspection.optJSONObject("comparison") ?: return false
    if (targetId == null) return comparison.optString("kind") == "new" && !comparison.has("id")
    if (current == null || current.optString("id") != targetId || comparison.optString("id") != targetId) return false
    return comparison.optString("kind") in setOf("same-content", "changed-content", "repair") &&
        comparison.optInt("revision", -1) > 0 && comparison.optInt("revision") == current.optInt("revision", -2) &&
        skillDigestPattern.matches(comparison.optString("expectedCurrentDigest")) && comparison.optString("expectedCurrentDigest") == current.optString("digest") &&
        comparison.has("expectedExecutionGeneration") && comparison.optLong("expectedExecutionGeneration", -1) == current.optLong("executionGeneration", 0)
}

internal fun skillImportOptions(inspection: JSONObject, targetId: String?, source: String, current: JSONObject?): JSONObject {
    check(skillInspectionIsCurrent(inspection, targetId, current)) { "当前技能版本已变化，请重新选择文件并检查" }
    require(source.isNotBlank()) { "请填写这份文件的来源" }
    return JSONObject().put("source", source.trim()).put("inspectedDigest", inspection.getJSONObject("package").getString("digest")).also { options ->
        if (targetId != null) {
            val comparison = inspection.getJSONObject("comparison")
            options.put("id", targetId).put("revision", comparison.getInt("revision")).put("expectedCurrentDigest", comparison.getString("expectedCurrentDigest")).put("expectedExecutionGeneration", comparison.getLong("expectedExecutionGeneration"))
        }
    }
}

/** A receipt escapes only after both provider flush and close succeed. */

internal fun writeSkillArchive(output: OutputStream, export: (OutputStream) -> JSONObject): JSONObject = output.use { stream ->
    val receipt = export(stream)
    stream.flush()
    receipt
}
