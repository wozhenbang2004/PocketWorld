package dev.pocketengine.ui

import org.json.JSONArray
import org.json.JSONObject

internal object ExperienceSkillReviewGuard {
    @JvmStatic fun eligible(record: JSONObject?): Boolean = record != null && record.optString("id").isNotBlank() &&
        MemoryLearningReviewGuard.revision(record) > 0 && record.optString("layer") == "procedural" &&
        record.optString("formation") == "reviewed_learning" && record.optBoolean("active")

    @JvmStatic fun sameProcedure(frozen: JSONObject?, current: JSONObject?): Boolean = eligible(frozen) && eligible(current) &&
        frozen!!.optString("id") == current!!.optString("id") && MemoryLearningReviewGuard.revision(frozen) == MemoryLearningReviewGuard.revision(current) &&
        frozen.optString("text") == current.optString("text") && frozen.optString("applicability") == current.optString("applicability") &&
        canonical(frozen.optJSONArray("sourceRefs")) == canonical(current.optJSONArray("sourceRefs"))

    @JvmStatic fun sameSkill(frozen: JSONObject?, current: JSONObject?): Boolean = frozen != null && current != null &&
        frozen.optString("id").isNotBlank() && frozen.optString("id") == current.optString("id") &&
        MemoryLearningReviewGuard.revision(frozen) > 0 && MemoryLearningReviewGuard.revision(frozen) == MemoryLearningReviewGuard.revision(current) &&
        frozen.optString("digest").matches(Regex("[a-f0-9]{64}")) && frozen.optString("digest") == current.optString("digest") &&
        canonical(frozen.optJSONObject("origin")) == canonical(current.optJSONObject("origin"))

    @JvmStatic fun sourceMatches(record: JSONObject, inspection: JSONObject): Boolean {
        val ref = inspection.optJSONObject("origin")?.optJSONObject("ref") ?: return false
        return ref.optString("recordId") == record.optString("id") && ref.optLong("revision") == MemoryLearningReviewGuard.revision(record) &&
            ref.optString("ownerId") == record.optString("ownerId") && ref.optString("spaceId") == record.optString("spaceId") &&
            inspection.optString("originStatus") == "current" && inspection.optString("inspectedDigest").matches(Regex("[a-f0-9]{64}"))
    }

    @JvmStatic fun importPayload(record: JSONObject, current: JSONObject?, draft: JSONObject, reviewedDraft: JSONObject?, inspection: JSONObject): JSONObject {
        check(sameProcedure(record, current)) { "程序记忆已变化，请返回并重新读取" }
        check(reviewedDraft != null && canonical(draft) == canonical(reviewedDraft) && sourceMatches(record, inspection)) { "草稿或来源已变化，请重新核对草稿" }
        val policy = inspection.getJSONObject("policy")
        val options = JSONObject().put("inspectedDigest", inspection.getString("inspectedDigest"))
            .put("requiredCapabilities", JSONArray(policy.getJSONArray("requiredCapabilities").toString())).put("expiresAt", policy.get("expiresAt"))
        return JSONObject().put("recordId", record.getString("id")).put("expectedRevision", MemoryLearningReviewGuard.revision(record))
            .put("draft", JSONObject(draft.toString())).put("options", options)
    }

    @JvmStatic fun validImport(record: JSONObject, inspection: JSONObject, result: JSONObject?): Boolean {
        val skill = result?.optJSONObject("skill") ?: return false
        val origin = skill.optJSONObject("origin") ?: return false
        return skill.optString("id").isNotBlank() && MemoryLearningReviewGuard.revision(skill) > 0 && skill.opt("enabled") == false &&
            skill.optString("approvedDigest").isEmpty() && skill.optString("digest") == inspection.optJSONObject("package")?.optString("digest") &&
            canonical(origin) == canonical(inspection.optJSONObject("origin")) && sourceMatches(record, inspection)
    }

    @JvmStatic fun approvalPayload(frozen: JSONObject, current: JSONObject?, read: JSONObject?): JSONObject {
        check(sameSkill(frozen, current) && current!!.optBoolean("contentVerified") && current.optString("originStatus") == "current") { "技能或来源已变化，请重新读取" }
        check(read != null && read.optString("id") == frozen.optString("id") && read.optLong("revision") == frozen.optLong("revision") &&
            read.optString("digest") == frozen.optString("digest") && read.optString("path") == "SKILL.md" && !read.optBoolean("hasMore", true)) { "请先读取完整的当前 SKILL.md" }
        check(frozen.optJSONObject("origin") != null && current.optJSONArray("statusReasons")?.toString()?.contains("expired") != true) { "技能来源或有效期不可用" }
        return JSONObject().put("id", frozen.getString("id")).put("revision", frozen.getInt("revision")).put("digest", frozen.getString("digest"))
    }

    @JvmStatic fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(prefix = "{", postfix = "}") { JSONObject.quote(it) + ":" + canonical(value.opt(it)) }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonical(value.opt(it)) }
        is String -> JSONObject.quote(value)
        null, JSONObject.NULL -> "null"
        else -> value.toString()
    }
}
