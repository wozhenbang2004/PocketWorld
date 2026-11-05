package dev.pocketengine.runtime;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.providers.ApiTurnStream;
import dev.pocketengine.runtime.providers.ProviderAdapters;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual native Gemini parser/adapter, with bounded provider reply fixtures and no network. */
public final class GeminiNoArgumentToolTest {
    private static JSONArray tools() throws Exception {
        return new JSONArray().put(new JSONObject().put("type", "function").put("function",
                new JSONObject().put("name", "phone_observe").put("parameters",
                        new JSONObject().put("type", "object").put("properties", new JSONObject()))));
    }

    private static JSONObject reply(JSONObject call) throws Exception {
        return new JSONObject().put("candidates", new JSONArray().put(new JSONObject()
                .put("finishReason", "STOP").put("content", new JSONObject().put("role", "model")
                        .put("parts", new JSONArray().put(new JSONObject().put("functionCall", call))))));
    }

    private static JSONObject parse(JSONObject call, boolean streaming) throws Exception {
        String body = reply(call).toString();
        if (!streaming) return ApiTurnStream.completeJson("gemini", ProviderAdapters.selected("gemini"),
                body, tools(), delta -> {});
        byte[] bytes = ("data: " + body + "\n\n").getBytes(StandardCharsets.UTF_8);
        return ApiTurnStream.read(new ByteArrayInputStream(bytes), "gemini", ProviderAdapters.selected("gemini"),
                tools(), new RunControl(), delta -> {});
    }

    private static void assertEmptyArguments(boolean streaming) throws Exception {
        JSONObject result = parse(new JSONObject().put("name", "phone_observe"), streaming);
        JSONObject call = result.getJSONArray("tool_calls").getJSONObject(0);
        assertEquals("phone_observe", call.getJSONObject("function").getString("name"));
        assertEquals("{}", call.getJSONObject("function").getString("arguments"));
        assertFalse(call.getString("id").isEmpty());
        assertFalse("Keep the original provider part unchanged", result.getJSONObject("provider_data")
                .getJSONArray("items").getJSONObject(0).getJSONObject("functionCall").has("args"));
    }

    @Test public void omittedArgumentsNormalizeToEmptyObjectInJsonReply() throws Exception {
        assertEmptyArguments(false);
    }

    @Test public void omittedArgumentsNormalizeToEmptyObjectInStream() throws Exception {
        assertEmptyArguments(true);
    }

    @Test public void explicitNonObjectArgumentsRemainRejected() throws Exception {
        for (boolean streaming : new boolean[]{false, true}) {
            for (Object args : new Object[]{JSONObject.NULL, "{}", new JSONArray(), 3, true}) {
                assertThrows(Exception.class, () -> parse(new JSONObject().put("name", "phone_observe")
                        .put("args", args), streaming));
            }
        }
    }

    @Test public void incompleteCallsStillCannotBecomeEmptyArgumentCalls() throws Exception {
        for (boolean streaming : new boolean[]{false, true}) {
            assertThrows(Exception.class, () -> parse(new JSONObject().put("name", "phone_observe")
                    .put("partialArgs", new JSONArray()), streaming));
            assertThrows(Exception.class, () -> parse(new JSONObject().put("name", "phone_observe")
                    .put("willContinue", true), streaming));
        }
    }
}
