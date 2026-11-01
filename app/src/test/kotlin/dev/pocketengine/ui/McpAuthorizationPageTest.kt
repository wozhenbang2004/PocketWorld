package dev.pocketengine.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpAuthorizationPageTest {
    private val view = McpAuthorizationView("review", "install", "a".repeat(64), "https://mcp.example.com/mcp", "https://auth.example.com", "registered-client", listOf("files:read"), "review", 2000)

    @Test fun staleInstallationBindingExpiryAndTerminalStateDisableAuthorization() {
        assertTrue(mcpAuthorizationCurrent(view, "install", "a".repeat(64), 1000))
        assertFalse(mcpAuthorizationCurrent(view, "replacement", "a".repeat(64), 1000))
        assertFalse(mcpAuthorizationCurrent(view, "install", "b".repeat(64), 1000))
        assertFalse(mcpAuthorizationCurrent(view, "install", "a".repeat(64), 2000))
        for (phase in listOf("connected", "failed", "cancelled", "expired")) assertFalse(mcpAuthorizationCurrent(view.copy(phase = phase), "install", "a".repeat(64), 1000))
        assertTrue(mcpAuthorizationCurrent(view.copy(phase = "waiting", flowId = "flow"), "install", "a".repeat(64), 1000))
    }
}
