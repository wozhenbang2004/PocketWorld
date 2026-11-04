package dev.pocketengine.runtime.usage;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.models.ModelBinding;
import java.net.URI;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

/** Original capture/sink admission and public-receipt redaction; no provider network call. */
public final class InferenceCaptureBudgetIdentityTest {
    private static ModelConfig config() { return new ModelConfig("https://fixture.invalid/v1", "fixture-key", "fixture-model", false, 4, 10, "openai_chat", 256); }
    private static JSONArray message(String text) throws Exception { return new JSONArray().put(new JSONObject().put("role", "user").put("content", text)); }
    private static ModelConfig identified(String connection, String provider, String model) {
        return config().withModel(model).withApiConnection(new ModelBinding(connection, provider, ModelBinding.Protocol.OPENAI_CHAT,
                ModelBinding.Format.OPENAI, URI.create("https://fixture.invalid/v1/models"), "credential", 1, "installation", 1, false));
    }
    @After public void releaseNativeBudgetHook() { InferenceCapture.installBudgetAttribution((context, identity) -> { }); }

    @Test public void realCaptureKeepsNativeBudgetAdmissionWhenUnrelatedPublicFieldsAreRedacted() throws Exception {
        ModelConfig selected = identified("saved-a", "provider-fixture-key", "model-fixture-key");
        java.util.concurrent.atomic.AtomicInteger stored = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<InferenceReceipt> observed = new java.util.concurrent.atomic.AtomicReference<>();
        InferenceCapture.Sink sink = new InferenceCapture.Sink() {
            public void started(InferenceReceipt receipt) { stored.incrementAndGet(); observed.set(receipt); }
            public void finished(InferenceReceipt receipt) { observed.set(receipt); }
        };
        InferenceCapture.installBudgetAttribution((context, identity) -> {
            assertTrue(identity.known()); assertEquals("connection:saved-a", identity.routeKey());
            throw new SecurityException("native_daily_budget_rejected");
        });
        InferenceCapture blocked = new InferenceCapture(selected, new RunControl(), new InferenceCapture.Target(InferenceReceipt.Context.unattributed(), sink));
        blocked.tokenReservation(message("fixture input"), null);
        assertThrows(SecurityException.class, blocked::dispatch); assertFalse(blocked.wasDispatched()); assertEquals(0, stored.get());
        InferenceCapture.installBudgetAttribution((context, identity) -> assertEquals("connection:saved-a", identity.routeKey()));
        InferenceCapture admitted = new InferenceCapture(selected, new RunControl(), new InferenceCapture.Target(InferenceReceipt.Context.unattributed(), sink));
        admitted.tokenReservation(message("fixture input"), null); admitted.dispatch(); admitted.finish(InferenceReceipt.Outcome.CANCELLED);
        JSONObject route = observed.get().toJson().getJSONObject("route");
        assertTrue(route.getBoolean("identityRedacted")); assertTrue(route.isNull("requestedModel")); assertTrue(route.isNull("providerId"));
        assertEquals("connection:saved-a", InferenceTokenReservation.routeKey(route));
        assertEquals("connection:saved-a", observed.get().nativeBudgetIdentity().routeKey());
        assertFalse(observed.get().toJson().toString().contains("fixture-key"));
    }

    @Test public void redactedConnectionIdentityRemainsPrivateAndStillReachesTheNativeGuardAndSink() throws Exception {
        ModelConfig selected = identified("saved-fixture-key", "provider", "model");
        java.util.concurrent.atomic.AtomicReference<InferenceReceipt> observed = new java.util.concurrent.atomic.AtomicReference<>();
        InferenceCapture.installBudgetAttribution((context, identity) -> assertEquals("connection:saved-fixture-key", identity.routeKey()));
        InferenceCapture call = new InferenceCapture(selected, new RunControl(), new InferenceCapture.Target(InferenceReceipt.Context.unattributed(), new InferenceCapture.Sink() {
            public void started(InferenceReceipt receipt) { observed.set(receipt); }
            public void finished(InferenceReceipt receipt) { observed.set(receipt); }
        }));
        call.tokenReservation(message("fixture input"), null); call.dispatch(); call.finish(InferenceReceipt.Outcome.FAILED);
        JSONObject publicReceipt = observed.get().toJson();
        assertTrue(publicReceipt.getJSONObject("route").isNull("connectionId"));
        assertEquals("", InferenceTokenReservation.routeKey(publicReceipt.getJSONObject("route")));
        assertEquals("connection:saved-fixture-key", observed.get().nativeBudgetIdentity().routeKey());
        assertFalse(publicReceipt.toString().contains("fixture-key")); assertFalse(observed.get().nativeBudgetIdentity().toString().contains("fixture-key"));
    }

}
