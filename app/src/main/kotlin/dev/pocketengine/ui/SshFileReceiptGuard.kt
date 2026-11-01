package dev.pocketengine.ui

import java.security.MessageDigest
import org.json.JSONObject

/** One SFTP editor consumes only its exact native request, path and reviewed version. */
internal object SshFileReceiptGuard {
    fun target(input: JSONObject?, profileId: String, path: String): Boolean = input != null && input.optString("profileId") == profileId && input.optString("path") == path

    fun result(response: JSONObject?, input: JSONObject?, requestId: String?, profileId: String, path: String, expectedVersion: String? = null): JSONObject? {
        if (requestId.isNullOrBlank() || response == null || input == null || input.optString("_requestId") != requestId || input.optBoolean("_failed") || !target(input, profileId, path)) return null
        if (expectedVersion != null && input.optString("expectedVersion") != expectedVersion) return null
        if (response.optString("profileId") != profileId || response.has("path") && response.optString("path") != path || response.optString("status") == "completed" && response.optString("path") != path) return null
        return response
    }

    fun saved(response: JSONObject, submitted: String): Boolean {
        val wanted = "sha256:" + MessageDigest.getInstance("SHA-256").digest(submitted.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return response.optString("status") == "completed" && response.opt("accepted") == true && response.optString("version") == wanted
    }
}
