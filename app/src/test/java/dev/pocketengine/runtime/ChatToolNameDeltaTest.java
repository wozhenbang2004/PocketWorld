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

/** Function name deltas are text fragments, not repeated metadata snapshots. */
public final class ChatToolNameDeltaTest {
    private static JSONObject parse(String[] offered, String... fragments) throws Exception {
        JSONArray tools = new JSONArray();
        for (String name : offered) tools.put(new JSONObject().put("type", "function")
                .put("function", new JSONObject().put("name", name).put("parameters",
                        new JSONObject().put("type", "object").put("properties", new JSONObject()))));
        StringBuilder wire = new StringBuilder();
        for (int i = 0; i < fragments.length; i++) {
            JSONObject call = new JSONObject().put("index", 0).put("function",
                    new JSONObject().put("name", fragments[i]).put("arguments", i == 0 ? "{}" : ""));
            if (i == 0) call.put("type", "function").put("id", "call-one");
            JSONObject choice = new JSONObject().put("index", 0).put("delta",
                    new JSONObject().put("tool_calls", new JSONArray().put(call)))
                    .put("finish_reason", i == fragments.length - 1 ? "tool_calls" : JSONObject.NULL);
            wire.append("data: ").append(new JSONObject().put("object", "chat.completion.chunk")
                    .put("id", "response-one").put("choices", new JSONArray().put(choice))).append("\n\n");
        }
        wire.append("data: [DONE]\n\n");
        return ApiTurnStream.read(new ByteArrayInputStream(wire.toString().getBytes(StandardCharsets.UTF_8)),
                "openai_chat", ProviderAdapters.selected("openai_chat"), tools, new RunControl(), delta -> {});
    }

    private static String name(JSONObject reply) throws Exception {
        return reply.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("name");
    }

    @Test public void identicalNameFragmentsMustNotBeDropped() throws Exception {
        assertEquals("aa", name(parse(new String[]{"aa"}, "a", "a")));
        assertEquals("ssh_read_file", name(parse(new String[]{"ssh_read_file"}, "s", "s", "h_read_file")));
    }

    @Test public void prefixToolMustNotReceiveAnotherToolsCall() throws Exception {
        assertEquals("aa", name(parse(new String[]{"a", "aa"}, "a", "a")));
    }

    @Test public void repeatedWholeNameCannotBeSilentlyRewrittenToAnOfferedTool() throws Exception {
        assertThrows(ModelGateway.GatewayException.class,
                () -> parse(new String[]{"inspect"}, "inspect", "inspect"));
        assertEquals("inspect", name(parse(new String[]{"inspect"}, "in", "spect")));
    }
}
