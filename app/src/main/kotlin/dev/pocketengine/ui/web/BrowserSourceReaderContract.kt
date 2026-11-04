package dev.pocketengine.ui.web

import dev.pocketengine.browser.BrowserResearchResult
import dev.pocketengine.browser.BrowserStructuredSource
import dev.pocketengine.browser.BrowserTaskSpace
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

/** The native entry chooses exactly one saved source. JavaScript cannot choose a task or URL. */
internal data class BrowserSourceReaderTarget private constructor(val taskId: String, val sourceId: String, val fieldRef: String?) {
    companion object {
        fun source(taskId: String, sourceId: String): BrowserSourceReaderTarget {
            BrowserTaskSpace.uuid(taskId); BrowserTaskSpace.uuid(sourceId)
            return BrowserSourceReaderTarget(taskId, sourceId, null)
        }
        fun field(taskId: String, fieldRef: String): BrowserSourceReaderTarget =
            BrowserSourceReaderTarget(taskId, BrowserStructuredSource.sourceId(taskId, fieldRef), fieldRef)
    }
}

/** Retain the historical run, never substitute the task's newest execution. */
internal data class BrowserSourceReaderBinding(val sourceFingerprint: String, val runId: String, val epoch: Int, val revision: String?) {
    companion object {
        fun capture(target: BrowserSourceReaderTarget, source: JSONObject): BrowserSourceReaderBinding {
            require(source.getString("taskId") == target.taskId && source.getString("sourceId") == target.sourceId)
            require(source.getString("sourceRef") == BrowserResearchResult.reference(target.taskId, target.sourceId))
            val fingerprint = source.getString("sourceFingerprint")
            require(fingerprint.matches(Regex("[a-f0-9]{64}")))
            val run = source.getString("runId"); BrowserTaskSpace.uuid(run)
            val epoch = source.getInt("epoch"); require(epoch >= 0)
            val projection = source.optJSONObject("structuredProjection")
            projection?.let(BrowserStructuredSource::validate)
            return BrowserSourceReaderBinding(fingerprint, run, epoch, projection?.getString("revision"))
        }
    }
}

/** An allowlist projection, not a JSON copy. Private navigation and browser/profile identities never cross. */
internal object BrowserSourceReaderDto {
    fun source(target: BrowserSourceReaderTarget, value: JSONObject): JSONObject {
        val binding = BrowserSourceReaderBinding.capture(target, value)
        val displayUrl = safeUrl(value.getString("url"))
        val privateUrl = value.getString("privateUrl")
        val out = JSONObject().put("title", displayText(value.optString("title"), privateUrl, displayUrl, provisionalTitle = true).take(240))
            .put("safeUrl", displayUrl)
            .put("excerpt", displayText(value.optString("excerpt"), privateUrl, displayUrl).take(6000))
            .put("capturedAt", value.getLong("observedAt"))
            .put("sourceId", target.sourceId).put("sourceFingerprint", binding.sourceFingerprint)
            .put("runId", binding.runId).put("epoch", binding.epoch)
            .put("untrusted", true).put("verified", false)
            .put("urlQueryOmitted", value.optBoolean("urlQueryOmitted"))
            .put("coverage", coverage(value))
        val projection = value.optJSONObject("structuredProjection")
        if (projection != null) {
            val mode = projection.getString("mode")
            val rows = JSONArray()
            val prefix = BrowserResearchResult.reference(target.taskId, target.sourceId) + "#field:" + binding.revision + ":"
            val data = projection.getJSONArray("data")
            for (i in 0 until data.length()) {
                val row = data.getJSONObject(i)
                val index = row.getInt("index")
                val result = JSONObject().put("index", index)
                if (mode == "list") result.put("text", displayText(row.getString("text"), privateUrl, displayUrl)).put("partial", row.getBoolean("partial"))
                    .put("fieldRef", prefix + "i" + index)
                else {
                    val cells = JSONArray(); val original = row.getJSONArray("cells")
                    for (j in 0 until original.length()) {
                        val cell = original.getJSONObject(j); val column = cell.getInt("column")
                        cells.put(JSONObject().put("column", column).put("role", cell.getString("role"))
                            .put("text", displayText(cell.getString("text"), privateUrl, displayUrl)).put("partial", cell.getBoolean("partial"))
                            .put("colSpan", cell.getInt("colSpan")).put("rowSpan", cell.getInt("rowSpan"))
                            .put("fieldRef", prefix + "r" + index + "c" + column))
                    }
                    result.put("cells", cells)
                }
                rows.put(result)
            }
            out.put("projection", JSONObject().put("mode", mode).put("revision", binding.revision)
                .put("partial", projection.getBoolean("partial")).put("omittedRows", projection.getInt("omittedDeliveredRows"))
                .put("rows", rows))
        }
        return out
    }

    fun field(target: BrowserSourceReaderTarget, binding: BrowserSourceReaderBinding, ref: String, value: JSONObject, source: JSONObject): JSONObject {
        require(BrowserStructuredSource.sourceId(target.taskId, ref) == target.sourceId)
        require(value.getString("taskId") == target.taskId && value.getString("sourceId") == target.sourceId)
        require(value.getString("fieldRef") == ref && value.getString("revision") == binding.revision)
        require(BrowserSourceReaderBinding.capture(target, source) == binding)
        val displayUrl = safeUrl(source.getString("url")); val privateUrl = source.getString("privateUrl")
        val headers = JSONArray(); val original = value.getJSONArray("headers")
        for (i in 0 until original.length()) headers.put(displayText(original.getJSONObject(i).getString("text"), privateUrl, displayUrl))
        return JSONObject().put("fieldRef", ref).put("revision", value.getString("revision"))
            .put("mode", value.getString("mode")).put("text", displayText(value.getString("text"), privateUrl, displayUrl))
            .put("rowIndex", value.getJSONObject("row").getInt("index"))
            .put("columnIndex", value.getJSONObject("field").optInt("column", -1))
            .put("headers", headers).put("partial", value.getBoolean("partial"))
            .put("untrusted", true).put("verified", false)
    }

    fun fieldRefs(source: JSONObject): Set<String> = buildSet {
        val rows = source.optJSONObject("projection")?.optJSONArray("rows") ?: return@buildSet
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            row.optString("fieldRef").takeIf { it.isNotEmpty() }?.let(::add)
            val cells = row.optJSONArray("cells") ?: continue
            for (j in 0 until cells.length()) add(cells.getJSONObject(j).getString("fieldRef"))
        }
    }

    // Same native URL-echo rule as BrowserTaskSourcePort.projectReferenceForOwner.
    // Redact before clipping: provisional WebView titles may be a prefix of the private URL.
    private fun displayText(value: String, privateUrl: String, displayUrl: String, provisionalTitle: Boolean = false): String {
        require(privateUrl.isNotEmpty())
        return if (provisionalTitle && value.isNotEmpty() && privateUrl.startsWith(value)) displayUrl
        else value.replace(privateUrl, displayUrl)
    }

    private fun safeUrl(value: String): String {
        val uri = URI(value)
        require(value.length <= 2048 && uri.scheme in setOf("https", "http") && uri.host != null &&
            uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        return value
    }
    private fun coverage(source: JSONObject): JSONObject {
        val kind = source.optString("kind")
        val semantic = source.optJSONObject("semantic")
        val http = source.optJSONObject("http")
        val frame = source.optJSONObject("frameCoverage")
        val partial = source.optJSONObject("structuredProjection")?.optBoolean("partial", true)
            ?: semantic?.optBoolean("partial", true)
            ?: if (kind == "public_http_observation") http?.let { it.optBoolean("bodyTruncated") || it.optBoolean("projectionTruncated") } ?: true else true
        return JSONObject().put("kind", when (kind) {
            "public_http_observation" -> "http"
            "browser_semantic_observation" -> "semantic"
            "browser_live_observation" -> "live"
            else -> "excerpt"
        }).put("partial", partial).put("wholePage", false)
            .put("embeddedFrame", frame?.optBoolean("childFrameContentsRead") == true)
            .put("inlineHostPage", frame?.optString("sourceUrlRole") == "inline_document_host_page")
            .put("continuationAvailable", semantic?.optJSONArray("reasons")?.let { reasons ->
                (0 until reasons.length()).any { reasons.optString(it) == "continuation_available" }
            } ?: false)
    }
}
