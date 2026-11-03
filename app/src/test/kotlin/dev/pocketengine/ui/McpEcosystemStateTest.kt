package dev.pocketengine.ui

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class McpEcosystemStateTest {
    private fun plugin() = JSONObject().put("installationId", "original-install").put("stateRevision", 4)
    private fun attempt() = McpSourceAttempt("getMcpResource", "request-1", "operation-1", "original-install", 4)
    private fun response() = JSONObject().put("requestId", "request-1").put("ok", true).put("result", JSONObject()
        .put("operationId", "operation-1").put("source", JSONObject().put("installationId", "original-install").put("stateRevision", 4)))

    @Test fun replacementOrRevocationWithdrawsPreviouslyValidText() {
        val source = plugin(); val pending = attempt(); val callback = response()
        assertNotNull(McpEcosystemRules.result(source, pending, callback))
        source.put("stateRevision", 5)
        assertNull(McpEcosystemRules.result(source, pending, callback))
        source.put("stateRevision", 4).put("installationId", "replacement-install")
        assertNull(McpEcosystemRules.result(source, pending, callback))
    }

    @Test fun supersededOrFailedRequestCannotPublishReference() {
        val callback = response().put("requestId", "older-request")
        assertNull(McpEcosystemRules.result(plugin(), attempt(), callback))
        callback.put("requestId", "request-1").put("ok", false)
        assertNull(McpEcosystemRules.result(plugin(), attempt(), callback))
        assertNull(McpEcosystemRules.result(plugin(), null, response()))
    }

    @Test fun correlatedEnvelopeCannotRelabelAnotherConnectionOrOperation() {
        val callback = response(); callback.getJSONObject("result").put("operationId", "different-operation")
        assertNull(McpEcosystemRules.result(plugin(), attempt(), callback))
        callback.getJSONObject("result").put("operationId", "operation-1").getJSONObject("source").put("installationId", "different-connection")
        assertNull(McpEcosystemRules.result(plugin(), attempt(), callback))
    }

    @Test fun templateDirectoryAndOriginalGetRequireTheSameLiveRequestOperationAndSourceRevision() {
        for (command in listOf("listMcpResourceTemplates", "getMcpResource")) {
            val current = attempt().copy(command = command)
            assertNotNull(McpEcosystemRules.result(plugin(), current, response()))
            assertNull(McpEcosystemRules.result(plugin(), current.copy(request = "newer-request"), response()))
            assertNull(McpEcosystemRules.result(plugin(), current.copy(operation = "newer-operation"), response()))
            assertNull(McpEcosystemRules.result(plugin(), null, response())) // cancelled/withdrawn attempt
            val oldRevision = response()
            oldRevision.getJSONObject("result").getJSONObject("source").put("stateRevision", 3)
            assertNull(McpEcosystemRules.result(plugin(), current, oldRevision))
            val incomplete = response()
            incomplete.getJSONObject("result").remove("source")
            assertNull(McpEcosystemRules.result(plugin(), current, incomplete))
        }
    }
}
