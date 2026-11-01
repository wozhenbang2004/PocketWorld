package dev.pocketengine.ui

import dev.pocketengine.runtime.models.ModelBinding
import java.net.URI
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelPriceEditTargetTest {
    private fun snapshot(connection: String = "original-connection", model: String = "original-model", revision: Long = 3): JSONObject {
        val binding = ModelBinding(connection, "openai_chat", ModelBinding.Protocol.OPENAI_CHAT, ModelBinding.Format.OPENAI,
            URI.create("https://model.fixture/models"), "native-credential", 1, "native-install", 1, false)
        return JSONObject().put("binding", binding.toJson()).put("model", model).put("ownerRevision", revision)
            .put("quote", JSONObject().put("priceVersion", "original-price").put("rates", JSONObject().put("INPUT", "2")))
    }
    @Test fun originalTargetAndQuoteCannotRebindWhenOuterSnapshotMovesToAnotherConnectionAndModel() {
        val original = snapshot(); val target = requireNotNull(ModelPriceEditTarget.capture(original))
        val replacement = snapshot("different-connection", "different-model", 0)
        original.put("binding", replacement.getJSONObject("binding")).put("model", "different-model").put("ownerRevision", 0)
        original.getJSONObject("quote").getJSONObject("rates").put("INPUT", "999")
        assertEquals("original-connection", target.binding.connectionId()); assertEquals("original-model", target.model); assertEquals(3L, target.ownerRevision)
        assertEquals("2", target.quote()!!.getJSONObject("rates").getString("INPUT")); assertFalse(target.current(original))
    }
    @Test fun modelAndOwnerCasRevisionChangesEachInvalidateTheOriginalEditorWithoutAdvancingItsRevision() {
        val target = requireNotNull(ModelPriceEditTarget.capture(snapshot()))
        assertTrue(target.current(snapshot())); assertFalse(target.current(snapshot(model = "another-model"))); assertFalse(target.current(snapshot(revision = 4)))
        assertEquals(3L, target.ownerRevision); assertEquals("original-model", target.model)
    }
    @Test fun quoteReadsAreDefensiveAndMissingOldQuoteRemainsMissing() {
        val target = requireNotNull(ModelPriceEditTarget.capture(snapshot()))
        target.quote()!!.getJSONObject("rates").put("INPUT", "777"); assertEquals("2", target.quote()!!.getJSONObject("rates").getString("INPUT"))
        assertNull(requireNotNull(ModelPriceEditTarget.capture(snapshot().put("quote", JSONObject.NULL))).quote())
    }
    @Test fun missingOrCoercedIdentityDoesNotBecomeAnEditableNativeTarget() {
        assertNull(ModelPriceEditTarget.capture(JSONObject())); assertNull(ModelPriceEditTarget.capture(snapshot().put("ownerRevision", "3")))
        assertNull(ModelPriceEditTarget.capture(snapshot().put("ownerRevision", 3.0))); assertNull(ModelPriceEditTarget.capture(snapshot().put("model", "")))
    }
}
