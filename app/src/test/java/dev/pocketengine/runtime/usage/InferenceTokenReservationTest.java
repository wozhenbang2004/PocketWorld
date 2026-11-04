package dev.pocketengine.runtime.usage;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.runtime.models.ModelBinding;
import dev.pocketengine.runtime.models.ReasoningOptions;
import java.net.URI;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Native pure accounting contracts, not an exact tokenizer or provider-bill test. */
public final class InferenceTokenReservationTest {
    private static ModelConfig config() { return new ModelConfig("https://fixture.invalid/v1", "fixture-key", "fixture-model", false, 4, 10, "openai_chat", 256); }
    private static JSONArray message(String text) throws Exception { return new JSONArray().put(new JSONObject().put("role", "user").put("content", text)); }
    private static JSONObject counter(Object value, String stage) throws Exception { return new JSONObject().put("value", value).put("source", "provider_reported").put("stage", stage); }
    private static JSONObject usage(String protocol, JSONObject counters) throws Exception { return new JSONObject().put("protocol", protocol).put("counters", counters).put("invalidFields", new JSONArray()); }
    @Test public void unicodeAndToolSchemasAreChargedBeforeDispatchWithoutStoringContent() throws Exception {
        ModelConfig selected = config();
        JSONObject ascii = InferenceTokenReservation.prepare(selected, message("a"), null);
        JSONObject unicode = InferenceTokenReservation.prepare(selected, message("龟"), null);
        assertEquals(2, unicode.getLong("inputEstimate") - ascii.getLong("inputEstimate"));
        JSONArray tools = new JSONArray().put(new JSONObject().put("type", "function").put("function", new JSONObject().put("name", "read_original").put("description", "PRIVATE TOOL DESCRIPTION").put("parameters", new JSONObject().put("type", "object"))));
        JSONObject larger = InferenceTokenReservation.prepare(selected, message("private task content"), tools);
        assertTrue(larger.getLong("inputEstimate") > ascii.getLong("inputEstimate"));
        assertEquals(larger.getLong("inputEstimate") + selected.maxOutputTokens, larger.getLong("reservedTokens"));
        assertFalse(larger.getBoolean("providerHardCap")); assertFalse(larger.getBoolean("actualUsageKnown"));
        assertFalse(larger.toString().contains("PRIVATE TOOL")); assertFalse(larger.toString().contains("private task content")); assertFalse(larger.toString().contains("fixture-key"));
    }
    @Test public void imageBytesDoNotMasqueradeAsKnownImageTokens() throws Exception {
        JSONArray images = new JSONArray().put(new JSONObject().put("role", "user").put("content", new JSONArray().put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("url", "data:image/png;base64,PRIVATEIMAGE")))));
        JSONObject unknown = InferenceTokenReservation.prepare(config(), images, null);
        assertFalse(unknown.getBoolean("available")); assertTrue(unknown.isNull("reservedTokens"));
        ModelBinding binding = new ModelBinding("route", "fixture", ModelBinding.Protocol.OPENAI_CHAT, ModelBinding.Format.OPENAI, URI.create("https://fixture.invalid/v1/models"), "credential", 1, "installation", 1, false);
        JSONObject reserved = InferenceTokenReservation.prepare(config().withNativeCatalog(binding, ReasoningOptions.DEFAULT, 2000), images, null);
        assertTrue(reserved.getBoolean("available")); assertEquals(1, reserved.getInt("imageCount")); assertTrue(reserved.getLong("inputEstimate") >= 2000);
        assertFalse(reserved.toString().contains("PRIVATEIMAGE"));
    }
    @Test public void reportedTotalsMustBeFinalNativeProviderCountersAndMissingIsNeverZero() throws Exception {
        JSONObject partial = usage("openai_chat", new JSONObject().put("TOTAL", counter(123, "PARTIAL")));
        assertNull(InferenceTokenReservation.reportedTotal(partial));
        JSONObject actual = usage("openai_chat", new JSONObject().put("TOTAL", counter(123, "FINAL")).put("INPUT", counter(100, "FINAL")).put("OUTPUT", counter(23, "FINAL")));
        assertEquals(Long.valueOf(123), InferenceTokenReservation.reportedTotal(actual));
        actual.getJSONArray("invalidFields").put("usage.total_tokens"); assertNull(InferenceTokenReservation.reportedTotal(actual));
        assertNull(InferenceTokenReservation.reportedTotal(usage("openai_chat", new JSONObject().put("INPUT", counter(100, "FINAL")))));
        assertNull(InferenceTokenReservation.reportedTotal(usage("openai_chat", new JSONObject().put("TOTAL", counter("0", "FINAL")))));
        assertNull(InferenceTokenReservation.reportedTotal(usage("openai_chat", new JSONObject().put("TOTAL", counter(-1, "FINAL")))));
        assertEquals(Long.valueOf(0), InferenceTokenReservation.reportedTotal(usage("openai_chat", new JSONObject().put("TOTAL", counter(0, "FINAL")))));
    }
    @Test public void anthropicNonOverlappingInputCacheAndOutputAreOnlyAddedWhenAllFinal() throws Exception {
        JSONObject counters = new JSONObject().put("INPUT", counter(10, "FINAL")).put("OUTPUT", counter(5, "FINAL")).put("CACHE_READ", counter(20, "FINAL"));
        assertNull(InferenceTokenReservation.reportedTotal(usage("anthropic", counters)));
        counters.put("CACHE_WRITE", counter(30, "FINAL")); assertEquals(Long.valueOf(65), InferenceTokenReservation.reportedTotal(usage("anthropic", counters)));
        counters.put("CACHE_WRITE_5M", counter(30, "FINAL")).put("REASONING", counter(4, "FINAL"));
        assertEquals(Long.valueOf(65), InferenceTokenReservation.reportedTotal(usage("anthropic", counters)));
        counters.put("CACHE_WRITE", counter(30, "PARTIAL")); assertNull(InferenceTokenReservation.reportedTotal(usage("anthropic", counters)));
    }
    @Test public void stableBudgetIdentitySurvivesCredentialAndModelRenewalButNeverMergesRegistrations() throws Exception {
        JSONObject route = new JSONObject().put("kind", "api_key").put("connectionId", "saved-a").put("credentialRevision", 1).put("requestedModel", "old");
        assertEquals("connection:saved-a", InferenceTokenReservation.routeKey(route));
        route.put("credentialRevision", 2).put("requestedModel", "new"); assertEquals("connection:saved-a", InferenceTokenReservation.routeKey(route));
        route.put("identityRedacted", true); assertEquals("connection:saved-a", InferenceTokenReservation.routeKey(route));
        route.put("connectionId", JSONObject.NULL); assertEquals("", InferenceTokenReservation.routeKey(route));
        JSONObject account = new JSONObject().put("kind", "account").put("accountId", "registration-a").put("accountVersion", 1);
        assertEquals("account:registration-a", InferenceTokenReservation.routeKey(account)); account.put("accountVersion", 2); assertEquals("account:registration-a", InferenceTokenReservation.routeKey(account));
        account.put("accountId", "registration-b"); assertEquals("account:registration-b", InferenceTokenReservation.routeKey(account));
        assertEquals("", InferenceTokenReservation.routeKey(new JSONObject().put("kind", "api_key")));
    }

    @Test public void missingNativeBindingIsExplicitlyUnknownAndNullPublicIdsCannotBecomeAnIdentity() throws Exception {
        assertFalse(InferenceTokenReservation.captureIdentity(config()).known());
        for (Object missing : new Object[]{JSONObject.NULL, 123, true})
            assertEquals("", InferenceTokenReservation.routeKey(new JSONObject().put("kind", "api_key").put("connectionId", missing)));
        ModelConfig.AccountModelRef account = ModelConfig.AccountModelRef.fromJson(new JSONObject().put("provider", "chatgpt_plan").put("accountId", "original-account").put("accountVersion", 1).put("modelId", "account-model"));
        assertEquals("account:original-account", InferenceTokenReservation.captureIdentity(config().withAccountModel(account)).routeKey());
        assertEquals("account:original-account", InferenceTokenReservation.captureIdentity(config().withAccountModel(account.withVersion(2))).routeKey());
    }
}
