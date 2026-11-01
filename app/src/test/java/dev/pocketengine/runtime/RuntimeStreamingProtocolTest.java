package dev.pocketengine.runtime;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real loopback HTTP and production codecs. No vendor account, Android device or live model claim. */
public final class RuntimeStreamingProtocolTest {
    private static final String KEY = "fixture-api-secret-123456789";

    private ModelConfig config(Fixture server, String protocol) {
        return new ModelConfig(server.base(), KEY, "fixture-model", false, 4, 10, protocol, 512);
    }
    private JSONArray messages() throws Exception {
        return new JSONArray().put(new JSONObject().put("role", "user").put("content", "检查真实结果"));
    }
    private JSONArray tools() throws Exception {
        return new JSONArray().put(new JSONObject().put("type", "function").put("function", new JSONObject()
                .put("name", "inspect").put("parameters", new JSONObject().put("type", "object"))));
    }
    private static String frame(String event, JSONObject data) {
        return (event.isEmpty() ? "" : "event: " + event + "\r\n") + "data: " + data + "\r\n\r\n";
    }
    private static String chat(JSONObject delta, Object finish) throws Exception {
        return frame("", new JSONObject().put("id", "reply-one").put("object", "chat.completion.chunk")
                .put("choices", new JSONArray().put(new JSONObject().put("index", 0).put("delta", delta)
                        .put("finish_reason", finish == null ? JSONObject.NULL : finish))));
    }
    private static String done() { return "data: [DONE]\r\n\r\n"; }
    private static JSONObject callFragment(int index, String id, String name, String arguments) throws Exception {
        JSONObject part = new JSONObject().put("index", index);
        if (id != null) part.put("id", id).put("type", "function");
        JSONObject function = new JSONObject(); if (name != null) function.put("name", name);
        if (arguments != null) function.put("arguments", arguments); return part.put("function", function);
    }
    private static JSONObject function(String itemId, String callId, String arguments) throws Exception {
        return new JSONObject().put("type", "function_call").put("id", itemId).put("call_id", callId)
                .put("name", "inspect").put("arguments", arguments).put("status", "completed");
    }
    private static JSONObject outputText(String value) throws Exception {
        return new JSONObject().put("type", "message").put("id", "message-one").put("role", "assistant")
                .put("status", "completed").put("content", new JSONArray().put(new JSONObject()
                        .put("type", "output_text").put("text", value).put("annotations", new JSONArray())));
    }
    private static JSONObject completed(JSONArray output) throws Exception {
        return new JSONObject().put("id", "response-one").put("status", "completed").put("output", output);
    }
    private static String responseEvent(String type, int sequence, JSONObject extra) throws Exception {
        JSONObject event = new JSONObject(extra.toString()).put("type", type).put("sequence_number", sequence);
        return frame(type, event);
    }
    private static String responseStart() throws Exception {
        return responseEvent("response.created", 0, new JSONObject().put("response", new JSONObject()
                .put("id", "response-one").put("status", "in_progress")));
    }
    private static String anthropic(String type, JSONObject extra) throws Exception {
        return frame(type, new JSONObject(extra.toString()).put("type", type));
    }
    private static String anthropicStart() throws Exception {
        return anthropic("message_start", new JSONObject().put("message", new JSONObject().put("type", "message")
                .put("id", "anthropic-one").put("role", "assistant").put("content", new JSONArray())));
    }
    private static String blockStart(int index, JSONObject block) throws Exception {
        return anthropic("content_block_start", new JSONObject().put("index", index).put("content_block", block));
    }
    private static String blockDelta(int index, JSONObject delta) throws Exception {
        return anthropic("content_block_delta", new JSONObject().put("index", index).put("delta", delta));
    }
    private static String blockStop(int index) throws Exception {
        return anthropic("content_block_stop", new JSONObject().put("index", index));
    }
    private static String anthropicEnd(String reason) throws Exception {
        return anthropic("message_delta", new JSONObject().put("delta", new JSONObject().put("stop_reason", reason)))
                + anthropic("message_stop", new JSONObject());
    }
    private static String gemini(JSONArray parts, String finish) throws Exception {
        JSONObject candidate = new JSONObject().put("index", 0).put("content", new JSONObject().put("role", "model").put("parts", parts));
        if (finish != null) candidate.put("finishReason", finish);
        return frame("", new JSONObject().put("responseId", "gemini-one").put("candidates", new JSONArray().put(candidate)));
    }
    private JSONObject stream(Fixture server, String protocol, List<ModelGateway.StreamEvent> events) throws Exception {
        return ModelGateway.completeStreaming(config(server, protocol), messages(), tools(), new RunControl(), events::add);
    }
    private void rejected(String protocol, String body) throws Exception {
        try (Fixture server = new Fixture()) {
            server.reply(Reply.sse(body)); List<ModelGateway.StreamEvent> events = new ArrayList<>();
            try { stream(server, protocol, events); fail("Invalid or incomplete stream returned tools"); }
            catch (ModelGateway.GatewayException expected) { assertFalse(expected.getMessage().contains(KEY)); }
            assertEquals(1, server.requests.size());
            assertFalse(events.stream().anyMatch(event -> event.kind == ModelGateway.StreamKind.COMPLETED));
            assertEquals(ModelGateway.StreamKind.FAILED, events.get(events.size() - 1).kind);
            assertTrue(events.stream().allMatch(event -> event.kind != ModelGateway.StreamKind.TEXT_DELTA || event.provisional));
        }
    }

    @Test public void chatTextArrivesBeforeTheTerminalAndUsesAnActualOutputCeiling() throws Exception {
        try (Fixture server = new Fixture()) {
            String prefix = "\uFEFF: keepalive\r\n\r\n" + chat(new JSONObject().put("role", "assistant")
                    .put("content", "中文🙂"), null);
            Reply reply = Reply.held(prefix, chat(new JSONObject(), "stop") + done()); reply.writeSize = 1; server.reply(reply);
            List<ModelGateway.StreamEvent> events = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch visible = new CountDownLatch(1);
            Future<JSONObject> pending = server.pool.submit(() -> ModelGateway.completeStreaming(config(server, "openai_chat"), messages(), tools(), new RunControl(), event -> {
                events.add(event); if (event.kind == ModelGateway.StreamKind.TEXT_DELTA) visible.countDown();
            }));
            assertTrue(visible.await(2, TimeUnit.SECONDS)); assertFalse(pending.isDone());
            assertEquals("中文🙂", visible(events));
            assertTrue(server.requests.get(0).body.getBoolean("stream"));
            assertEquals(512, server.requests.get(0).body.getInt("max_tokens"));
            reply.release.countDown(); assertEquals("中文🙂", pending.get(2, TimeUnit.SECONDS).getString("content"));
            assertEquals(1, server.requests.size()); ordered(events);
            assertEquals(ModelGateway.StreamKind.COMPLETED, events.get(events.size() - 1).kind);
        }
    }

    @Test public void parallelChatToolsRemainPrivateUntilDoneAndArgumentsAreWhole() throws Exception {
        try (Fixture server = new Fixture()) {
            String prefix = chat(new JSONObject().put("tool_calls", new JSONArray()
                    .put(callFragment(0, "call-a", "in", "{\"city\":"))
                    .put(callFragment(1, "call-b", "inspect", "{\"n\":"))), null)
                    + chat(new JSONObject().put("tool_calls", new JSONArray()
                    .put(callFragment(1, null, null, "2}"))
                    .put(callFragment(0, null, "spect", "\"北京\"}"))), "tool_calls");
            Reply reply = Reply.held(prefix, done()); server.reply(reply);
            List<ModelGateway.StreamEvent> events = Collections.synchronizedList(new ArrayList<>());
            Future<JSONObject> pending = server.pool.submit(() -> stream(server, "openai_chat", events));
            assertTrue(reply.prefixSent.await(2, TimeUnit.SECONDS)); assertFalse(pending.isDone()); assertEquals("", visible(events));
            reply.release.countDown(); JSONArray calls = pending.get(2, TimeUnit.SECONDS).getJSONArray("tool_calls");
            assertEquals("call-a", calls.getJSONObject(0).getString("id"));
            assertEquals("北京", new JSONObject(calls.getJSONObject(0).getJSONObject("function").getString("arguments")).getString("city"));
            assertEquals("call-b", calls.getJSONObject(1).getString("id"));
            for (ModelGateway.StreamEvent event : events) assertFalse(event.text.contains("city"));
        }
    }

    @Test public void chatRequiresBothAValidFinishReasonAndDone() throws Exception {
        rejected("openai_chat", chat(new JSONObject().put("content", "temporary"), null) + done());
        rejected("openai_chat", chat(new JSONObject().put("content", "temporary"), "stop"));
        rejected("openai_chat", chat(new JSONObject().put("content", "temporary"), null));
    }

    @Test public void chatTruncationRefusalAndUnknownTerminalsDoNotYieldCalls() throws Exception {
        for (String finish : new String[]{"length", "content_filter", "future_unknown"})
            rejected("openai_chat", chat(new JSONObject().put("tool_calls", new JSONArray().put(callFragment(0, "c", "inspect", "{}"))), finish) + done());
        rejected("openai_chat", chat(new JSONObject().put("refusal", "I cannot do that"), "stop") + done());
    }

    @Test public void chatRejectsIncompleteScalarTrailingAndUndeclaredArguments() throws Exception {
        for (String arguments : new String[]{"{\"x\":", "{} trailing", "[]", "null", "{\"x\":\"\\ud800\"}"})
            rejected("openai_chat", chat(new JSONObject().put("tool_calls", new JSONArray().put(callFragment(0, "c", "inspect", arguments))), "tool_calls") + done());
        rejected("openai_chat", chat(new JSONObject().put("tool_calls", new JSONArray().put(callFragment(0, "c", "not_offered", "{}"))), "tool_calls") + done());
        rejected("openai_chat", chat(new JSONObject().put("tool_calls", new JSONArray()
                .put(callFragment(0, "same", "inspect", "{}")).put(callFragment(1, "same", "inspect", "{}"))), "tool_calls") + done());
    }

    @Test public void visibleCredentialEchoIsRedactedAcrossDeltasAndReasoningIsNotShown() throws Exception {
        try (Fixture server = new Fixture()) {
            server.reply(Reply.sse(chat(new JSONObject().put("reasoning_content", "private reasoning")
                    .put("content", "visible " + KEY.substring(0, 11)), null)
                    + chat(new JSONObject().put("content", KEY.substring(11) + " tail"), null)
                    + chat(new JSONObject(), "stop") + done()));
            List<ModelGateway.StreamEvent> events = new ArrayList<>(); stream(server, "openai_chat", events);
            assertEquals("visible [密钥已隐藏] tail", visible(events));
            assertFalse(visible(events).contains(KEY)); assertFalse(visible(events).contains("private reasoning"));
            assertTrue(events.stream().allMatch(event -> event.text.length() <= 2048)); ordered(events);
        }
    }

    @Test public void responsesPreservesTheNativeOpaqueItemsAndChecksArgumentDeltas() throws Exception {
        try (Fixture server = new Fixture()) {
            JSONObject initial = function("item-one", "call-one", "").put("status", "in_progress");
            JSONObject finalCall = function("item-one", "call-one", "{\"ok\":true}");
            JSONObject reason = new JSONObject().put("type", "reasoning").put("id", "reason-one")
                    .put("encrypted_content", "opaque-do-not-display").put("summary", new JSONArray());
            String body = responseStart()
                    + responseEvent("response.output_item.added", 1, new JSONObject().put("output_index", 0).put("item", initial))
                    + responseEvent("response.function_call_arguments.delta", 2, new JSONObject().put("output_index", 0).put("item_id", "item-one").put("delta", "{\"ok\":"))
                    + responseEvent("response.function_call_arguments.delta", 3, new JSONObject().put("output_index", 0).put("item_id", "item-one").put("delta", "true}"))
                    + responseEvent("response.function_call_arguments.done", 4, new JSONObject().put("output_index", 0).put("item_id", "item-one").put("arguments", "{\"ok\":true}"))
                    + responseEvent("response.output_item.done", 5, new JSONObject().put("output_index", 0).put("item", finalCall))
                    + responseEvent("response.completed", 6, new JSONObject().put("response", completed(new JSONArray().put(finalCall).put(reason))));
            server.reply(Reply.sse(body)); List<ModelGateway.StreamEvent> events = new ArrayList<>();
            JSONObject assistant = stream(server, "openai_responses", events);
            assertEquals("call-one", assistant.getJSONArray("tool_calls").getJSONObject(0).getString("id"));
            assertEquals("opaque-do-not-display", assistant.getJSONObject("provider_data").getJSONArray("items").getJSONObject(1).getString("encrypted_content"));
            assertEquals("", visible(events)); assertTrue(server.requests.get(0).body.getBoolean("stream"));
            JSONObject follow = dev.pocketengine.runtime.providers.ProviderAdapters.selected("openai_responses")
                    .request(config(server, "openai_responses"), messages().put(assistant).put(new JSONObject().put("role", "tool")
                            .put("tool_call_id", "call-one").put("content", "actual result")), tools());
            assertEquals("opaque-do-not-display", follow.getJSONArray("input").getJSONObject(2).getString("encrypted_content"));
        }
    }

    @Test public void responsesTextIsProvisionalAndMustAgreeWithTheFinalMessage() throws Exception {
        String prefix = responseTextPrefix("visible");
        try (Fixture server = new Fixture()) {
            server.reply(Reply.sse(prefix + responseEvent("response.completed", 4, new JSONObject().put("response", completed(new JSONArray().put(outputText("visible")))))));
            List<ModelGateway.StreamEvent> events = new ArrayList<>();
            assertEquals("visible", stream(server, "openai_responses", events).getString("content")); assertEquals("visible", visible(events));
        }
        rejected("openai_responses", prefix + responseEvent("response.completed", 4, new JSONObject().put("response", completed(new JSONArray().put(outputText("different"))))));
    }

    private static String responseTextPrefix(String value) throws Exception {
        return responseStart() + responseEvent("response.output_item.added", 1, new JSONObject().put("output_index", 0)
                .put("item", new JSONObject().put("id", "message-one").put("type", "message").put("role", "assistant").put("content", new JSONArray())))
                + responseEvent("response.content_part.added", 2, new JSONObject().put("output_index", 0).put("item_id", "message-one")
                .put("content_index", 0).put("part", new JSONObject().put("type", "output_text").put("text", "")))
                + responseEvent("response.output_text.delta", 3, new JSONObject().put("output_index", 0).put("item_id", "message-one").put("content_index", 0).put("delta", value));
    }

    @Test public void responsesMissingFailedIncompleteAndWrongIdentityRemainUnexecutable() throws Exception {
        rejected("openai_responses", responseTextPrefix("temporary"));
        for (String terminal : new String[]{"response.incomplete", "response.failed", "response.cancelled"})
            rejected("openai_responses", responseTextPrefix("temporary") + responseEvent(terminal, 4, new JSONObject()));
        rejected("openai_responses", responseTextPrefix("temporary") + responseEvent("response.completed", 4,
                new JSONObject().put("response", completed(new JSONArray().put(outputText("temporary"))).put("id", "replacement"))));
        rejected("openai_responses", responseTextPrefix("temporary") + responseEvent("response.completed", 3,
                new JSONObject().put("response", completed(new JSONArray().put(outputText("temporary"))))));
    }

    @Test public void responsesArgumentsCannotChangeAtTheTerminal() throws Exception {
        String prefix = responseStart() + responseEvent("response.output_item.added", 1, new JSONObject().put("output_index", 0)
                .put("item", function("i", "c", "").put("status", "in_progress")))
                + responseEvent("response.function_call_arguments.delta", 2, new JSONObject().put("output_index", 0).put("item_id", "i").put("delta", "{}"));
        rejected("openai_responses", prefix + responseEvent("response.completed", 3, new JSONObject().put("response", completed(new JSONArray().put(function("i", "c", "{\"changed\":true}"))))));
        rejected("openai_responses", prefix + responseEvent("response.completed", 3, new JSONObject().put("response", completed(new JSONArray().put(function("i", "different-call", "{}"))))));
    }

    @Test public void anthropicPreservesThinkingSignaturesAndOnlyCompletesAfterMessageStop() throws Exception {
        try (Fixture server = new Fixture()) {
            String body = anthropicStart() + blockStart(0, new JSONObject().put("type", "thinking").put("thinking", ""))
                    + blockDelta(0, new JSONObject().put("type", "thinking_delta").put("thinking", "never present this"))
                    + blockDelta(0, new JSONObject().put("type", "signature_delta").put("signature", "native-signature")) + blockStop(0)
                    + blockStart(1, new JSONObject().put("type", "text").put("text", ""))
                    + blockDelta(1, new JSONObject().put("type", "text_delta").put("text", "先检查")) + blockStop(1)
                    + blockStart(2, new JSONObject().put("type", "tool_use").put("id", "tool-one").put("name", "inspect").put("input", new JSONObject()))
                    + blockDelta(2, new JSONObject().put("type", "input_json_delta").put("partial_json", "{\"n\":"))
                    + blockDelta(2, new JSONObject().put("type", "input_json_delta").put("partial_json", "1}")) + blockStop(2);
            Reply reply = Reply.held(body, anthropicEnd("tool_use")); server.reply(reply);
            List<ModelGateway.StreamEvent> events = Collections.synchronizedList(new ArrayList<>());
            Future<JSONObject> pending = server.pool.submit(() -> stream(server, "anthropic", events));
            assertTrue(reply.prefixSent.await(2, TimeUnit.SECONDS)); assertFalse(pending.isDone());
            reply.release.countDown(); JSONObject result = pending.get(2, TimeUnit.SECONDS);
            assertEquals("先检查", visible(events));
            assertEquals("native-signature", result.getJSONObject("provider_data").getJSONArray("items").getJSONObject(0).getString("signature"));
            assertEquals(1, new JSONObject(result.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("arguments")).getInt("n"));
            assertTrue(server.requests.get(0).body.getBoolean("stream"));
        }
    }

    @Test public void anthropicRejectsMissingStopsMalformedToolsAndRefusal() throws Exception {
        String text = anthropicStart() + blockStart(0, new JSONObject().put("type", "text").put("text", ""))
                + blockDelta(0, new JSONObject().put("type", "text_delta").put("text", "temporary"));
        rejected("anthropic", text + anthropicEnd("end_turn"));
        rejected("anthropic", text + blockStop(0) + anthropic("message_stop", new JSONObject()));
        rejected("anthropic", text + blockStop(0) + anthropicEnd("max_tokens"));
        rejected("anthropic", text + blockStop(0) + anthropicEnd("refusal"));
        rejected("anthropic", anthropicStart() + blockStart(0, new JSONObject().put("type", "tool_use")
                .put("id", "t").put("name", "inspect").put("input", new JSONObject()))
                + blockDelta(0, new JSONObject().put("type", "input_json_delta").put("partial_json", "{\"half\":"))
                + blockStop(0) + anthropicEnd("tool_use"));
    }

    @Test public void geminiUsesItsSseEndpointAndKeepsNativeToolSignatures() throws Exception {
        try (Fixture server = new Fixture()) {
            StringBuilder body = new StringBuilder();
            for (int i = 0; i < 150; i++) body.append(gemini(new JSONArray().put(new JSONObject().put("text", "字")), null));
            body.append(gemini(new JSONArray().put(new JSONObject().put("thought", true).put("text", "private thought"))
                    .put(new JSONObject().put("thoughtSignature", "gemini-signature").put("functionCall", new JSONObject()
                            .put("id", "native-call").put("name", "inspect").put("args", new JSONObject().put("ok", true)))), "STOP"));
            server.reply(Reply.sse(body.toString())); List<ModelGateway.StreamEvent> events = new ArrayList<>();
            JSONObject result = stream(server, "gemini", events);
            assertEquals("字".repeat(150), result.getString("content")); assertEquals("字".repeat(150), visible(events));
            assertEquals("/v1beta/models/fixture-model:streamGenerateContent?alt=sse", server.requests.get(0).path);
            assertEquals(KEY, server.requests.get(0).headers.get("x-goog-api-key")); assertFalse(server.requests.get(0).path.contains(KEY));
            assertFalse(server.requests.get(0).body.has("stream"));
            assertEquals("native-call", result.getJSONArray("tool_calls").getJSONObject(0).getString("id"));
            JSONArray parts = result.getJSONObject("provider_data").getJSONArray("items");
            assertEquals("gemini-signature", parts.getJSONObject(parts.length() - 1).getString("thoughtSignature"));
        }
    }

    @Test public void geminiMissingTerminalBlockedPartialArgsAndLengthDoNotYieldTools() throws Exception {
        rejected("gemini", gemini(new JSONArray().put(new JSONObject().put("text", "temporary")), null));
        rejected("gemini", gemini(new JSONArray().put(new JSONObject().put("text", "temporary")), "MAX_TOKENS"));
        rejected("gemini", frame("", new JSONObject().put("promptFeedback", new JSONObject().put("blockReason", "SAFETY"))));
        rejected("gemini", gemini(new JSONArray().put(new JSONObject().put("functionCall", new JSONObject().put("name", "inspect")
                .put("args", new JSONObject()).put("partialArgs", new JSONArray().put("half")))), "STOP"));
    }

    @Test public void malformedEventNamesUtf8AndUnterminatedEventsFailClosed() throws Exception {
        rejected("openai_responses", frame("wrong-event", new JSONObject().put("type", "response.created")
                .put("response", new JSONObject().put("id", "x").put("status", "in_progress"))));
        rejected("openai_chat", chat(new JSONObject().put("content", "temporary"), "stop") + "data: [DONE]\n");
        try (Fixture server = new Fixture()) {
            server.reply(new Reply("text/event-stream", new byte[]{'d', 'a', 't', 'a', ':', ' ', (byte) 0xc3, (byte) 0x28, '\n', '\n'}));
            try { stream(server, "openai_chat", new ArrayList<>()); fail(); } catch (ModelGateway.GatewayException expected) { }
            assertEquals(1, server.requests.size());
        }
    }

    @Test public void eventsAndNestedArgumentsHaveRealBounds() throws Exception {
        rejected("openai_chat", ":" + "x".repeat(256 * 1024 + 1) + "\n\n");
        String deep = "{\"n\":".repeat(40) + "0" + "}".repeat(40);
        rejected("openai_chat", chat(new JSONObject().put("tool_calls", new JSONArray().put(callFragment(0, "c", "inspect", deep))), "tool_calls") + done());
    }

    @Test public void aSingleJsonResponseStillWorksWithoutASecondRequest() throws Exception {
        for (String protocol : new String[]{"openai_chat", "openai_responses", "anthropic", "gemini"}) try (Fixture server = new Fixture()) {
            JSONObject reply;
            if (protocol.equals("openai_chat")) reply = new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                    .put("finish_reason", "stop").put("message", new JSONObject().put("role", "assistant").put("content", "JSON answer"))));
            else if (protocol.equals("openai_responses")) reply = completed(new JSONArray().put(outputText("JSON answer")));
            else if (protocol.equals("anthropic")) reply = new JSONObject().put("type", "message").put("stop_reason", "end_turn")
                    .put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", "JSON answer")));
            else reply = new JSONObject().put("candidates", new JSONArray().put(new JSONObject().put("finishReason", "STOP")
                    .put("content", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", "JSON answer"))))));
            server.reply(Reply.json(reply)); List<ModelGateway.StreamEvent> events = new ArrayList<>();
            assertEquals("JSON answer", stream(server, protocol, events).getString("content"));
            assertEquals("JSON answer", visible(events)); assertEquals(1, server.requests.size());
        }
    }

    @Test public void legacyNonStreamingChatRequestIsNotSilentlyChanged() throws Exception {
        try (Fixture server = new Fixture()) {
            server.reply(Reply.json(new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                    .put("message", new JSONObject().put("role", "assistant").put("content", "legacy"))))));
            assertEquals("legacy", ModelGateway.complete(config(server, "openai_chat"), messages(), null, new RunControl()).getString("content"));
            assertFalse(server.requests.get(0).body.getBoolean("stream")); assertFalse(server.requests.get(0).body.has("max_tokens"));
        }
    }

    @Test public void cancellationStopsTheOriginalConnectionAndOnlyClearsItsDraft() throws Exception {
        try (Fixture server = new Fixture()) {
            Reply reply = Reply.held(chat(new JSONObject().put("content", "temporary"), null), chat(new JSONObject(), "stop") + done()); server.reply(reply);
            RunControl control = new RunControl(); List<ModelGateway.StreamEvent> events = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch visible = new CountDownLatch(1);
            Future<JSONObject> pending = server.pool.submit(() -> ModelGateway.completeStreaming(config(server, "openai_chat"), messages(), tools(), control, event -> {
                events.add(event); if (event.kind == ModelGateway.StreamKind.TEXT_DELTA) visible.countDown();
            }));
            assertTrue(visible.await(2, TimeUnit.SECONDS)); control.cancel();
            try { pending.get(2, TimeUnit.SECONDS); fail("Cancellation admitted tools"); }
            catch (ExecutionException expected) { assertTrue(expected.getCause() instanceof InterruptedException); }
            assertEquals(1, server.requests.size()); assertEquals(ModelGateway.StreamKind.CANCELLED, events.get(events.size() - 1).kind);
            assertEquals("", events.get(events.size() - 1).text); ordered(events);
        }
    }

    @Test public void pauseAndResumeClearDraftButCannotPresentOldCompletion() throws Exception {
        try (Fixture server = new Fixture()) {
            Reply reply = Reply.held(chat(new JSONObject().put("content", "before pause"), null),
                    chat(new JSONObject().put("content", " after pause"), "stop") + done()); server.reply(reply);
            RunControl control = new RunControl(); List<ModelGateway.StreamEvent> events = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch visible = new CountDownLatch(1);
            Future<JSONObject> pending = server.pool.submit(() -> ModelGateway.completeStreaming(config(server, "openai_chat"), messages(), tools(), control, event -> {
                events.add(event); if (event.kind == ModelGateway.StreamKind.TEXT_DELTA) visible.countDown();
            }));
            assertTrue(visible.await(2, TimeUnit.SECONDS)); control.pause(); control.resume(); reply.release.countDown();
            assertEquals("before pause after pause", pending.get(2, TimeUnit.SECONDS).getString("content"));
            assertEquals("before pause", visible(events));
            assertEquals(ModelGateway.StreamKind.CANCELLED, events.get(events.size() - 1).kind);
            assertEquals("presentation_invalidated", events.get(events.size() - 1).code);
            assertFalse(events.stream().anyMatch(event -> event.kind == ModelGateway.StreamKind.COMPLETED));
        }
    }

    @Test public void revokedAuthorityCannotAdmitTheTerminalOrAnotherRequest() throws Exception {
        try (Fixture server = new Fixture()) {
            server.reply(Reply.sse(chat(new JSONObject().put("content", "temporary"), null) + chat(new JSONObject(), "stop") + done()));
            RunControl control = new RunControl(); AtomicBoolean revoked = new AtomicBoolean();
            control.setAuthorityCheck(() -> { if (revoked.get()) throw new SecurityException("revoked fixture"); });
            List<ModelGateway.StreamEvent> events = new ArrayList<>();
            try { ModelGateway.completeStreaming(config(server, "openai_chat"), messages(), tools(), control, event -> {
                events.add(event); if (event.kind == ModelGateway.StreamKind.TEXT_DELTA) revoked.set(true);
            }); fail("Revoked request completed"); } catch (InterruptedException expected) { }
            assertEquals(1, server.requests.size()); assertEquals(ModelGateway.StreamKind.CANCELLED, events.get(events.size() - 1).kind);
        }
    }

    @Test public void failingPresentationDoesNotTurnIntoASecondProviderCall() throws Exception {
        try (Fixture server = new Fixture()) {
            server.reply(Reply.sse(chat(new JSONObject().put("content", "temporary"), null) + chat(new JSONObject(), "stop") + done()));
            try { ModelGateway.completeStreaming(config(server, "openai_chat"), messages(), tools(), new RunControl(), event -> {
                if (event.kind == ModelGateway.StreamKind.TEXT_DELTA) throw new IllegalStateException(KEY);
            }); fail(); } catch (ModelGateway.GatewayException expected) { assertFalse(expected.getMessage().contains(KEY)); }
            assertEquals(1, server.requests.size());
        }
    }

    @Test public void actualSerializedWireLimitRejectsBeforeOpeningAnHttpRequest() throws Exception {
        try (Fixture server = new Fixture()) {
            JSONArray large = new JSONArray().put(new JSONObject().put("role", "user")
                    .put("content", "\0".repeat(ModelGateway.MAX_REQUEST_BYTES / 6 + 1)));
            for (boolean streaming : new boolean[]{false, true}) try {
                if (streaming) ModelGateway.completeStreaming(config(server, "openai_chat"), large, null, new RunControl(), event -> { });
                else ModelGateway.complete(config(server, "openai_chat"), large, null, new RunControl());
                fail("Oversized serialized request opened the network");
            } catch (ModelGateway.GatewayException expected) { assertTrue(expected.getMessage().contains("24MiB")); }
            assertEquals(0, server.requests.size());
        }
    }

    @Test public void authenticationAndRedirectErrorsNeverLeakBodiesOrContactTheRedirect() throws Exception {
        try (Fixture server = new Fixture(); ServerSocket destination = new ServerSocket(0)) {
            Reply refused = new Reply("application/json", ("{\"error\":\"" + KEY + "\"}").getBytes(StandardCharsets.UTF_8)); refused.status = 401; server.reply(refused);
            try { stream(server, "openai_chat", new ArrayList<>()); fail(); } catch (ModelGateway.GatewayException expected) { assertFalse(expected.getMessage().contains(KEY)); }
            Reply redirect = Reply.json(new JSONObject()); redirect.status = 302;
            redirect.location = "http://127.0.0.1:" + destination.getLocalPort() + "/never"; server.reply(redirect);
            try { stream(server, "openai_chat", new ArrayList<>()); fail(); } catch (ModelGateway.GatewayException expected) { assertTrue(expected.getMessage().contains("重定向")); }
            destination.setSoTimeout(150);
            try (Socket ignored = destination.accept()) { fail("Redirect received credentials"); } catch (java.net.SocketTimeoutException expected) { }
            assertEquals(2, server.requests.size());
        }
    }

    @Test public void http503RetryAfterZeroCannotResubmitTheInference() throws Exception {
        try (Fixture server = new Fixture()) {
            Reply unavailable = Reply.json(new JSONObject().put("error", "fixture unavailable"));
            unavailable.status = 503; unavailable.retryAfter = "0"; server.reply(unavailable);
            server.reply(Reply.sse(chat(new JSONObject().put("content", "must not be requested"), "stop") + done()));
            try { stream(server, "openai_chat", new ArrayList<>()); fail("A second inference was used"); }
            catch (ModelGateway.GatewayException expected) { }
            assertEquals("No hidden HTTP-status retry is allowed", 1, server.requests.size());
        }
    }

    @Test public void streamingJsonNegotiationStillRejectsAnUnmarkedOrPartialTerminal() throws Exception {
        try (Fixture server = new Fixture()) {
            JSONObject call = new JSONObject().put("id", "c").put("type", "function")
                    .put("function", new JSONObject().put("name", "inspect").put("arguments", "{}"));
            JSONObject message = new JSONObject().put("role", "assistant").put("tool_calls", new JSONArray().put(call));
            server.reply(Reply.json(new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("message", message)))));
            try { stream(server, "openai_chat", new ArrayList<>()); fail(); } catch (ModelGateway.GatewayException expected) { }
            assertEquals(1, server.requests.size());
        }
    }

    @Test public void noProgressEventContainsPartialToolArgumentsEvenWhenTheConnectionBreaks() throws Exception {
        try (Fixture server = new Fixture()) {
            server.reply(Reply.sse(chat(new JSONObject().put("content", "checking").put("tool_calls", new JSONArray()
                    .put(callFragment(0, "c", "inspect", "{\"password\":\""))), null)));
            List<ModelGateway.StreamEvent> events = new ArrayList<>();
            try { stream(server, "openai_chat", events); fail(); } catch (ModelGateway.GatewayException expected) { }
            assertEquals("checking", visible(events));
            for (ModelGateway.StreamEvent event : events) assertFalse(event.text.contains("password"));
            assertEquals(ModelGateway.StreamKind.FAILED, events.get(events.size() - 1).kind);
        }
    }

    @Test public void unsolicitedResponseNamespacesCannotRetargetFlatHostTools() throws Exception {
        JSONObject call = function("i", "c", "{}").put("namespace", "not_offered");
        rejected("openai_responses", responseStart() + responseEvent("response.completed", 1,
                new JSONObject().put("response", completed(new JSONArray().put(call)))));
    }

    @Test public void geminiFunctionContinuationAndIdentityHaveTypedFields() throws Exception {
        JSONObject call = new JSONObject().put("id", false).put("name", "inspect").put("args", new JSONObject());
        rejected("gemini", gemini(new JSONArray().put(new JSONObject().put("functionCall", call)), "STOP"));
        call.put("id", "native-call").put("willContinue", "false");
        rejected("gemini", gemini(new JSONArray().put(new JSONObject().put("functionCall", call)), "STOP"));
    }

    @Test public void logicalUnicodeSurrogatePairCanSpanTextDeltasWithoutSplittingPresentation() throws Exception {
        try (Fixture server = new Fixture()) {
            String body = chat(new JSONObject().put("content", "\ud83d"), null)
                    + chat(new JSONObject().put("content", "\ude42"), "stop") + done();
            // A real UTF-8 stream carries these partial UTF-16 units as JSON escapes, not bad UTF-8.
            server.reply(Reply.sse(body.replace("\ud83d", "\\ud83d").replace("\ude42", "\\ude42")));
            List<ModelGateway.StreamEvent> events = new ArrayList<>();
            assertEquals("🙂", stream(server, "openai_chat", events).getString("content"));
            assertEquals("🙂", visible(events));
            assertEquals(1, events.stream().filter(event -> event.kind == ModelGateway.StreamKind.TEXT_DELTA).count());
        }
    }

    @Test public void jsonNegotiationHasTheSameToolCompletenessRulesAsTheSsePath() throws Exception {
        JSONObject partial = new JSONObject().put("name", "inspect").put("args", new JSONObject())
                .put("partialArgs", new JSONArray().put("partial")).put("willContinue", true);
        JSONObject missing = new JSONObject().put("name", "inspect");
        JSONObject chatCall = new JSONObject().put("id", "c").put("type", "function")
                .put("function", new JSONObject().put("name", "inspect").put("arguments", "{}"));
        JSONObject claudeCall = new JSONObject().put("type", "tool_use").put("id", "c").put("name", "inspect").put("input", new JSONObject());
        List<String> protocols = new ArrayList<>(); List<JSONObject> replies = new ArrayList<>();
        for (JSONObject call : new JSONObject[]{partial, missing}) {
            protocols.add("gemini"); replies.add(new JSONObject().put("candidates", new JSONArray().put(new JSONObject().put("finishReason", "STOP")
                    .put("content", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("functionCall", call)))))));
        }
        protocols.add("openai_chat"); replies.add(new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("finish_reason", "stop")
                .put("message", new JSONObject().put("role", "assistant").put("tool_calls", new JSONArray().put(chatCall))))));
        protocols.add("openai_chat"); replies.add(new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("finish_reason", "tool_calls")
                .put("message", new JSONObject().put("role", "assistant").put("content", "not a call")))));
        protocols.add("anthropic"); replies.add(new JSONObject().put("stop_reason", "end_turn").put("content", new JSONArray().put(claudeCall)));
        protocols.add("anthropic"); replies.add(new JSONObject().put("stop_reason", "tool_use").put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", "not a call"))));
        for (int i = 0; i < replies.size(); i++) try (Fixture server = new Fixture()) {
            server.reply(Reply.json(replies.get(i))); List<ModelGateway.StreamEvent> events = new ArrayList<>();
            try { stream(server, protocols.get(i), events); fail("JSON path released an incomplete call: " + protocols.get(i)); }
            catch (ModelGateway.GatewayException expected) { }
            assertEquals(1, server.requests.size()); assertFalse(events.stream().anyMatch(event -> event.kind == ModelGateway.StreamKind.COMPLETED));
        }
    }

    private static String visible(List<ModelGateway.StreamEvent> events) {
        StringBuilder text = new StringBuilder(); synchronized (events) {
            for (ModelGateway.StreamEvent event : events) if (event.kind == ModelGateway.StreamKind.TEXT_DELTA) text.append(event.text);
        } return text.toString();
    }
    private static void ordered(List<ModelGateway.StreamEvent> events) {
        String id = events.get(0).requestId; long sequence = 0;
        for (ModelGateway.StreamEvent event : events) { assertEquals(id, event.requestId); assertEquals(++sequence, event.sequence); }
    }
    private static final class Request {
        final String path; final Map<String, String> headers; final JSONObject body;
        Request(String path, Map<String, String> headers, JSONObject body) { this.path = path; this.headers = headers; this.body = body; }
    }
    private static final class Reply {
        final String contentType; final byte[] body; int status = 200, split = -1, writeSize = 8192; String location, retryAfter;
        final CountDownLatch prefixSent = new CountDownLatch(1), release = new CountDownLatch(1);
        Reply(String contentType, byte[] body) { this.contentType = contentType; this.body = body; }
        static Reply sse(String body) { return new Reply("text/event-stream; charset=utf-8", body.getBytes(StandardCharsets.UTF_8)); }
        static Reply json(JSONObject body) { return new Reply("application/json", body.toString().getBytes(StandardCharsets.UTF_8)); }
        static Reply held(String prefix, String suffix) {
            Reply reply = sse(prefix + suffix); reply.split = prefix.getBytes(StandardCharsets.UTF_8).length; return reply;
        }
    }
    private static final class Fixture implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0);
        final ExecutorService pool = Executors.newCachedThreadPool();
        final BlockingQueue<Reply> replies = new LinkedBlockingQueue<>();
        final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
        final List<Reply> allReplies = Collections.synchronizedList(new ArrayList<>());
        Fixture() throws Exception {
            pool.submit(() -> { while (!server.isClosed()) try { Socket socket = server.accept(); pool.submit(() -> serve(socket)); }
            catch (Exception closed) { return; } });
        }
        String base() { return "http://127.0.0.1:" + server.getLocalPort(); }
        void reply(Reply reply) { allReplies.add(reply); replies.add(reply); }
        void serve(Socket socket) {
            try (Socket client = socket) {
                client.setSoTimeout(4000); InputStream input = client.getInputStream();
                String start = line(input), header; Map<String, String> headers = new HashMap<>();
                while (!(header = line(input)).isEmpty()) { int colon = header.indexOf(':'); headers.put(header.substring(0, colon).toLowerCase(java.util.Locale.ROOT), header.substring(colon + 1).trim()); }
                int length = Integer.parseInt(headers.getOrDefault("content-length", "0")); byte[] bytes = new byte[length]; int offset = 0;
                while (offset < length) { int read = input.read(bytes, offset, length - offset); if (read < 0) return; offset += read; }
                requests.add(new Request(start.split(" ")[1], headers, new JSONObject(new String(bytes, StandardCharsets.UTF_8))));
                Reply reply = replies.poll(4, TimeUnit.SECONDS); if (reply == null) return;
                OutputStream output = client.getOutputStream();
                output.write(("HTTP/1.1 " + reply.status + " Fixture\r\nContent-Type: " + reply.contentType + "\r\nContent-Length: " + reply.body.length
                        + (reply.location == null ? "" : "\r\nLocation: " + reply.location)
                        + (reply.retryAfter == null ? "" : "\r\nRetry-After: " + reply.retryAfter)
                        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8)); output.flush();
                int first = reply.split < 0 ? reply.body.length : reply.split;
                write(output, reply.body, 0, first, reply.writeSize); reply.prefixSent.countDown();
                if (reply.split >= 0) { reply.release.await(4, TimeUnit.SECONDS); write(output, reply.body, first, reply.body.length, reply.writeSize); }
            } catch (Exception ignored) { /* Client cancellation intentionally disconnects a real socket. */ }
        }
        private static void write(OutputStream output, byte[] body, int start, int end, int size) throws Exception {
            for (int i = start; i < end; i += size) { output.write(body, i, Math.min(size, end - i)); output.flush(); }
        }
        private static String line(InputStream input) throws Exception {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); int value;
            while ((value = input.read()) >= 0) { if (value == '\n') return bytes.toString("UTF-8").replace("\r", ""); bytes.write(value); }
            throw new java.io.EOFException();
        }
        public void close() throws Exception {
            for (Reply reply : allReplies) reply.release.countDown(); server.close(); pool.shutdownNow(); pool.awaitTermination(2, TimeUnit.SECONDS);
        }
    }
}
