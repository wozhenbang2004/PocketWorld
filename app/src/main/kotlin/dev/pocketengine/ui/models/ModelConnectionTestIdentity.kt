package dev.pocketengine.ui

import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

/** Display provenance for one explicit native test, never an inference or settings authority. */
internal class ModelConnectionTestIdentity private constructor(
    val testId: String,
    val connectionId: String,
    val connectionRevision: Long,
    private val editorId: String,
    private val draftRevision: Long,
    val draftFingerprint: String
) {
    fun matchesStoredRevision(snapshot: JSONObject): Boolean {
        if (connectionId.isEmpty()) return connectionRevision == 0L
        val rows = snapshot.optJSONArray("connections") ?: return false
        val matches = (0 until rows.length()).mapNotNull(rows::optJSONObject).filter { it.optString("id") == connectionId }
        return matches.size == 1 && matches.single().optLong("revision", -1) == connectionRevision && matches.single().optBoolean("enabled")
    }

    fun receipt(presentationCurrent: Boolean, outcome: String, reference: JSONObject?): JSONObject {
        require(outcome in setOf("completed", "failed", "not_dispatched", "unknown"))
        require(outcome !in setOf("completed", "failed") || reference != null) { "原连接测试用量身份不完整" }
        if (reference != null) requireReference(reference)
        return JSONObject().put("format", "pocketworld.connection-test.v1").put("testId", testId)
            .put("connectionId", connectionId).put("connectionRevision", connectionRevision)
            .put("editorId", editorId).put("draftRevision", draftRevision).put("draftFingerprint", draftFingerprint)
            .put("presentationCurrent", presentationCurrent).put("outcome", outcome)
            .put("inferenceReference", reference?.let { JSONObject(it.toString()) } ?: JSONObject.NULL)
    }

    companion object {
        // A plain hash of a draft containing a short key would disclose a guessing oracle.
        // This process-local key is never returned, persisted, or used as execution authority.
        private val fingerprintKey = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "HmacSHA256")
        private val uuid = Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")

        fun capture(connectionId: String, revision: Long, editorId: String, draftRevision: Long, input: JSONObject): ModelConnectionTestIdentity {
            require(connectionId.length <= 240 && connectionId.none(Char::isISOControl) &&
                (if (connectionId.isEmpty()) revision == 0L else revision > 0L)) { "请重新读取原连接修订" }
            require(uuid.matches(editorId) && draftRevision in 0..9_007_199_254_740_991L) { "请重新打开这份连接草稿" }
            val bytes = canonical(JSONObject().put("connectionId", connectionId).put("revision", revision).put("input", input)).toByteArray(Charsets.UTF_8)
            val mac = Mac.getInstance("HmacSHA256").also { it.init(fingerprintKey) }
            val fingerprint = mac.doFinal(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            return ModelConnectionTestIdentity(UUID.randomUUID().toString(), connectionId, revision, editorId, draftRevision, fingerprint)
        }

        private fun canonical(value: Any?): String = when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
            is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
            is String -> JSONObject.quote(value)
            is Number, is Boolean -> value.toString()
            else -> throw IllegalArgumentException("连接草稿包含不支持的值")
        }

        private fun requireReference(reference: JSONObject) {
            require(reference.optString("purpose") == "CONNECTION_TEST" && reference.optBoolean("accountingOnly") &&
                !reference.optBoolean("executionAuthorized", true) && uuid.matches(reference.optString("taskId")) &&
                uuid.matches(reference.optString("runId")) && reference.optInt("epoch") > 0) { "原连接测试用量身份不完整" }
        }

        /** A bounded view of the existing SQL receipts. No estimate, cache ledger or inferred zero. */
        fun feedback(reference: JSONObject, originalUsage: JSONObject): JSONObject {
            requireReference(reference)
            val receipts = originalUsage.optJSONArray("receipts") ?: return unavailableFeedback()
            if (receipts.length() == 0 || receipts.length() > 8 || originalUsage.optInt("conflictingCalls") > 0 || originalUsage.optInt("invalidReceipts") > 0)
                return unavailableFeedback()
            val calls = JSONArray()
            for (index in 0 until receipts.length()) {
                val actual = receipts.getJSONObject(index)
                val context = actual.getJSONObject("context")
                require(context.optString("taskId") == reference.getString("taskId") && context.optString("runId") == reference.getString("runId") &&
                    context.optInt("epoch") == reference.getInt("epoch") && context.optString("purpose") == "CONNECTION_TEST" && !actual.optBoolean("projectionConflict")) {
                    "用量回执不属于本次原连接测试"
                }
                val route = actual.getJSONObject("route")
                val usage = actual.getJSONObject("usage")
                val counters = usage.optJSONObject("counters") ?: JSONObject()
                val cache = JSONObject()
                for (key in listOf("CACHE_READ", "CACHE_WRITE", "CACHE_WRITE_5M", "CACHE_WRITE_1H")) {
                    val count = counters.optJSONObject(key)
                    cache.put(key, count?.let { JSONObject(it.toString()) } ?: JSONObject.NULL)
                }
                calls.put(JSONObject().put("callId", actual.getString("callId")).put("outcome", actual.getString("outcome"))
                    .put("protocol", route.opt("protocol") ?: JSONObject.NULL)
                    .put("requestedModel", route.opt("requestedModel") ?: JSONObject.NULL).put("responseModel", route.opt("responseModel") ?: JSONObject.NULL)
                    .put("providerProjection", route.optJSONObject("providerProjection")?.let { JSONObject(it.toString()) } ?: JSONObject.NULL)
                    .put("cacheCounters", cache).put("actualCost", usage.opt("actualCost") ?: JSONObject.NULL)
                    .put("actualCostSource", usage.optString("actualCostSource", "unknown")))
            }
            val logical = originalUsage.optJSONArray("logicalRequests") ?: JSONArray()
            val unknown = originalUsage.optInt("unfinished") > 0 || (0 until logical.length()).any {
                logical.optJSONObject(it)?.optString("state") in setOf("UNKNOWN", "PARTIAL")
            }
            return JSONObject().put("available", true).put("source", "original_task_inference_receipts")
                .put("calls", calls).put("outcomeUnknown", unknown).put("usageMeaning", "provider_reported_not_a_bill")
        }

        fun unavailableFeedback(): JSONObject = JSONObject().put("available", false).put("reason", "original_receipt_unavailable")
            .put("cacheHitKnown", false).put("actualCost", JSONObject.NULL)
    }
}
