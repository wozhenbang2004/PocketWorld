package dev.pocketengine.ui

internal data class McpAuthorizationView(
    val reviewId: String,
    val installationId: String,
    val bindingFingerprint: String,
    val resource: String,
    val issuer: String,
    val clientId: String,
    val scopes: List<String>,
    val phase: String,
    val expiresAt: Long,
    val flowId: String = ""
)

internal fun mcpAuthorizationCurrent(view: McpAuthorizationView, installationId: String, bindingFingerprint: String, now: Long): Boolean =
    view.reviewId.isNotBlank() && view.installationId == installationId && view.bindingFingerprint == bindingFingerprint &&
        bindingFingerprint.matches(Regex("[a-f0-9]{64}")) && view.expiresAt > now && view.phase in setOf("review", "waiting")

/** The host repeats identity checks atomically; UI guards are only feedback, never authorization. */
