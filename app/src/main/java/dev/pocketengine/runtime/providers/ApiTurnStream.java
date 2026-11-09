package dev.pocketengine.runtime.providers;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.ModelGateway.GatewayException;
import dev.pocketengine.runtime.providers.bedrock.BedrockEventStream;
import dev.pocketengine.runtime.usage.ProviderUsage;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Independently authored, bounded SSE accumulation around the existing native wire codecs.
 * This class never executes a tool, retries a request, changes a route or publishes reasoning.
 */
public final class ApiTurnStream {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_EVENT_BYTES = 256 * 1024;
    private static final int MAX_EVENTS = 8192;
    private static final int MAX_ITEMS = 128;
    private static final int MAX_TOOLS = 64;
    private static final int MAX_ARGUMENT_CHARS = 64 * 1024;
    private static final int MAX_TEXT_CHARS = 1024 * 1024;
    private static final String BEDROCK_METRICS = "amazon-bedrock-invocationMetrics";

    private ApiTurnStream() { }

    public interface TextSink { void text(String delta) throws Exception; }
    /** Same validated native SSE frame, for the authenticated protocol-preserving loopback relay.
     * No id/retry/control fields are exposed; the final frame is emitted only after normalization. */
    public interface NativeFrameSink { void frame(String event,JSONObject data) throws Exception; }
    public interface WireFrameSink {
        void frame(String event,String data) throws Exception;
        default void beforeTerminal(JSONObject nativeResult) throws Exception {}
    }
    /** Same state machine and terminal validation, retaining native final order and usage. */
    public static JSONObject readWire(InputStream input,String protocol,ProviderAdapter adapter,JSONArray tools,
            RunControl control,ProviderUsage.Observer usage,WireFrameSink frames)throws Exception {
        return readFrames(input,protocol,adapter,tools,control,delta->{},usage,frames,true);
    }

    /** Reuse the native serializer; only enable streaming and the Chat output ceiling. */
    public static JSONObject request(String protocol, JSONObject ordinary, int outputTokens) throws Exception {
        JSONObject body = copy(ordinary);
        if (!"gemini".equals(protocol) && !"bedrock_anthropic".equals(protocol)) body.put("stream", true);
        if ("openai_chat".equals(protocol)) body.put("max_tokens", outputTokens)
                .put("stream_options",new JSONObject().put("include_usage",true));
        return body;
    }

    public static URL endpoint(String protocol, URL ordinary) throws Exception {
        if (!"gemini".equals(protocol)) return ordinary;
        URI uri = ordinary.toURI();
        String path = uri.getPath();
        if (!path.endsWith(":generateContent")) throw invalid("AST-L67-1");
        path = path.substring(0, path.length() - ":generateContent".length()) + ":streamGenerateContent";
        return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), path, "alt=sse", null).toURL();
    }

    public static JSONObject read(InputStream input, String protocol, ProviderAdapter adapter,
                                  JSONArray tools, RunControl control, TextSink sink) throws Exception {
        return read(input,protocol,adapter,tools,control,sink,ProviderUsage.NONE);
    }
    public static JSONObject read(InputStream input, String protocol, ProviderAdapter adapter,
                                  JSONArray tools, RunControl control, TextSink sink,ProviderUsage.Observer usage) throws Exception {
        return readFrames(input,protocol,adapter,tools,control,sink,usage,null);
    }
    /** The original Anthropic decision waits for the complete binary transport, including trailers.
     * A message_stop is only a candidate terminal; no tool or completion escapes before clean EOF. */
    public static JSONObject readBedrock(InputStream input,ProviderAdapter provider,JSONArray offeredTools,
            RunControl control,TextSink sink,ProviderUsage.Collector usage)throws Exception {
        Anthropic state=new Anthropic(sink,usage);
        BedrockEventStream parser=new BedrockEventStream(input);
        boolean terminal=false;
        int events=0;long total=0;
        while(true){
            control.check();byte[] payload=parser.nextPayload();control.check();
            if(payload==null){
                if(!terminal)throw GatewayException.incompleteStream();
                JSONObject result=normalize("bedrock_anthropic",provider,state.result(),offeredTools);
                control.check();usage.completed();return result;
            }
            total+=payload.length;
            if(++events>MAX_EVENTS||payload.length>MAX_EVENT_BYTES||total>MAX_BYTES)throw tooLarge();
            String decoded;
            try{decoded=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(payload)).toString();}
            catch(java.nio.charset.CharacterCodingException invalidUtf8){GatewayException failure=invalid("AST-L100-1");failure.initCause(invalidUtf8);throw failure;}
            JSONObject event=eventObject(decoded);
            if(!event.has("type")){
                if(!terminal||event.length()!=1)throw invalid("AST-L103-1");
                bedrockMetrics(event);continue;
            }
            if(terminal)throw invalid("AST-L106-1");
            if(event.has(BEDROCK_METRICS)){
                if(!"message_stop".equals(event.optString("type")))throw invalid("AST-L108-1");
                bedrockMetrics(event);
            }
            terminal=state.accept(event);
        }
    }
    /** The undocumented measure fields are not token totals, prices or tool authority. */
    private static void bedrockMetrics(JSONObject event)throws Exception {
        JSONObject metrics=event.optJSONObject(BEDROCK_METRICS);
        if(metrics==null)throw invalid("AST-L117-1");
        unicodeTree(metrics,0);
    }
    public static JSONObject relayNative(InputStream input,String protocol,ProviderAdapter adapter,JSONArray tools,
            RunControl control,NativeFrameSink frames)throws Exception{
        return relayNative(input,protocol,adapter,tools,control,ProviderUsage.NONE,frames);
    }
    public static JSONObject relayNative(InputStream input,String protocol,ProviderAdapter adapter,JSONArray tools,
            RunControl control,ProviderUsage.Observer usage,NativeFrameSink frames)throws Exception{
        return readFrames(input,protocol,adapter,tools,control,delta->{},usage,java.util.Objects.requireNonNull(frames));
    }
    private static JSONObject readFrames(InputStream input,String protocol,ProviderAdapter adapter,JSONArray tools,
            RunControl control,TextSink sink,ProviderUsage.Observer usage,NativeFrameSink frames)throws Exception{
        return readFrames(input,protocol,adapter,tools,control,sink,usage,frames==null?null:(event,data)->{if(!"[DONE]".equals(data))frames.frame(event,eventObject(data));},false);
    }
    private static JSONObject readFrames(InputStream input,String protocol,ProviderAdapter adapter,JSONArray tools,
            RunControl control,TextSink sink,ProviderUsage.Observer usage,WireFrameSink frames,boolean nativeResult)throws Exception{
        State state;
        switch (protocol) {
            case "openai_chat": state = new Chat(sink,usage,tools); break;
            case "openai_responses": state = new Responses(sink,usage); break;
            case "anthropic": state = new Anthropic(sink,usage); break;
            case "gemini": state = new Gemini(sink,usage); break;
            default: throw invalid("AST-L140-1");
        }
        Sse parser = new Sse(input, control);
        for (int count = 0; count < MAX_EVENTS; count++) {
            control.check();
            Frame frame = parser.next();
            if (frame == null) {
                // An observed unfinished call is a protocol failure, not new replay authority.
                if (state instanceof Responses response && (response.incompleteItem || response.incompleteArguments)) throw interrupted();
                throw GatewayException.incompleteStream();
            }
            if (state.accept(frame)) {
                control.check();
                JSONObject result=normalize(protocol, adapter, state.result(), tools);
                if(frames!=null){frames.beforeTerminal(state.result());frames.frame(frame.event,frame.data);}
                usage.completed();return nativeResult?state.result():result;
            }
            if(frames!=null)frames.frame(frame.event,frame.data);
        }
        throw tooLarge();
    }

    /** Same-request JSON negotiation is allowed; it is not a retry with stream:false. */
    public static JSONObject completeJson(String protocol, ProviderAdapter adapter, String body,
                                          JSONArray tools, TextSink sink) throws Exception {
        return completeJson(protocol,adapter,body,tools,sink,ProviderUsage.NONE);
    }
    public static JSONObject completeJson(String protocol, ProviderAdapter adapter, String body,
                                          JSONArray tools, TextSink sink,ProviderUsage.Observer usage) throws Exception {
        JSONObject wire=object(body);
        usage.observe(wire,ProviderUsage.Stage.FINAL);
        terminalUsage(protocol,wire,usage);
        JSONObject result = normalize(protocol, adapter, wire, tools);
        Object content = result.opt("content");
        if (content instanceof String && !((String) content).isEmpty()) sink.text((String) content);
        return result;
    }

    private static JSONObject normalize(String protocol, ProviderAdapter adapter, JSONObject wire,
                                        JSONArray tools) throws Exception {
        if(wire.has("error")&&!wire.isNull("error"))throw invalid("AST-L180-1");
        switch (protocol) {
            case "openai_chat": {
                JSONArray choices = wire.getJSONArray("choices");
                if (choices.length() != 1) throw invalid("AST-L184-1");
                JSONObject choice = choices.getJSONObject(0);
                terminalChat(text(choice, "finish_reason", 64));
                if (!choice.getJSONObject("message").isNull("function_call")) throw invalid("AST-L187-1");
                break;
            }
            case "openai_responses":
                if (!"completed".equals(wire.optString("status"))) throw interrupted();
                JSONArray output = wire.getJSONArray("output");
                for (int i = 0; i < output.length(); i++) {
                    JSONObject item = output.getJSONObject(i);
                    if (item.has("status") && !item.isNull("status")
                            && !"completed".equals(item.optString("status"))) throw interrupted();
                    if ("function_call".equals(item.optString("type")) && item.has("namespace")
                            && !item.isNull("namespace") && !text(item, "namespace", 256).isEmpty()) throw invalid("AST-L198-1");
                }
                break;
            case "anthropic": case "bedrock_anthropic":
                terminalAnthropic(text(wire, "stop_reason", 64));
                if(wire.has("role")&&!"assistant".equals(wire.optString("role")))throw invalid("AST-L203-1");
                break;
            case "gemini": {
                JSONArray candidates = wire.getJSONArray("candidates");
                if (candidates.length() != 1 || !"STOP".equals(candidates.getJSONObject(0).optString("finishReason")))
                    throw interrupted();
                JSONObject content = candidates.getJSONObject(0).getJSONObject("content");
                if (content.has("role") && !"model".equals(content.optString("role"))) throw invalid("AST-L210-1");
                JSONArray parts = content.getJSONArray("parts"); if (parts.length() > MAX_ITEMS) throw tooLarge();
                for (int i = 0; i < parts.length(); i++) validateGeminiPart(parts.getJSONObject(i));
                break;
            }
            default: throw invalid("AST-L215-1");
        }
        JSONObject assistant = adapter.response(wire);
        if (!"assistant".equals(assistant.optString("role"))) throw invalid("AST-L218-1");
        unicodeTree(assistant, 0);
        Set<String> offered = new HashSet<>();
        if (tools != null) for (int i = 0; i < tools.length(); i++) {
            JSONObject tool = tools.getJSONObject(i);
            if ("function".equals(tool.optString("type"))) offered.add(tool.getJSONObject("function").getString("name"));
        }
        JSONArray calls = assistant.optJSONArray("tool_calls");
        if(assistant.has("tool_calls")&&!assistant.isNull("tool_calls")&&calls==null)throw invalid("AST-L226-1");
        Set<String> ids = new HashSet<>();
        if (calls != null) {
            if (calls.length() > MAX_TOOLS) throw tooLarge();
            for (int i = 0; i < calls.length(); i++) {
                JSONObject call = calls.getJSONObject(i);
                if (!"function".equals(call.optString("type")) || !ids.add(identity(call, "id"))) throw invalid("AST-L232-1");
                JSONObject function = call.getJSONObject("function");
                if (!offered.contains(identity(function, "name"))) throw invalid("AST-L234-1");
                JSONObject arguments = object(text(function, "arguments", MAX_ARGUMENT_CHARS));
                unicodeTree(arguments, 0);
            }
        }
        int callCount = calls == null ? 0 : calls.length();
        if ("openai_chat".equals(protocol)
                && "tool_calls".equals(wire.getJSONArray("choices").getJSONObject(0).getString("finish_reason")) != (callCount > 0)) throw invalid("AST-L241-1");
        if (("anthropic".equals(protocol)||"bedrock_anthropic".equals(protocol)) && "tool_use".equals(wire.getString("stop_reason")) != (callCount > 0)) throw invalid("AST-L242-1");
        Object content = assistant.opt("content");
        if (content != null && content != JSONObject.NULL && !(content instanceof String)) throw invalid("AST-L244-1");
        if ((content == null || content == JSONObject.NULL || ((String) content).isEmpty())
                && (calls == null || calls.length() == 0)) throw interrupted();
        return assistant;
    }

    private interface State {
        boolean accept(Frame frame) throws Exception;
        JSONObject result() throws Exception;
    }
    public static void terminalUsage(String protocol,JSONObject wire,ProviderUsage.Observer usage){
        switch(protocol){
            case "openai_chat": {
                JSONArray choices=wire.optJSONArray("choices");JSONObject first=choices==null?null:choices.optJSONObject(0);
                if(first!=null)usage.terminal(first.optString("finish_reason"));break;
            }
            case "openai_responses":usage.terminal(wire.optString("status"));break;
            case "anthropic":case "bedrock_anthropic":usage.terminal(wire.optString("stop_reason"));break;
            case "gemini":{
                JSONArray candidates=wire.optJSONArray("candidates");JSONObject first=candidates==null?null:candidates.optJSONObject(0);
                if(first!=null)usage.terminal(first.optString("finishReason"));break;
            }
            default:break;
        }
    }

    private static final class Chat implements State {
        private final TextSink sink;
        private final ProviderUsage.Observer usage;
        private final StringBuilder visible = new StringBuilder(), reasoning = new StringBuilder();
        private final TreeMap<Integer, ChatCall> calls = new TreeMap<>();
        private String responseId, finish;
        private boolean seen;
        private final Set<String> names=new HashSet<>();
        Chat(TextSink sink,ProviderUsage.Observer usage,JSONArray tools)throws Exception { this.sink = sink;this.usage=usage;
            for(int i=0;tools!=null&&i<tools.length();i++){JSONObject tool=tools.getJSONObject(i);if("function".equals(tool.optString("type")))names.add(tool.getJSONObject("function").getString("name"));}
        }
        public boolean accept(Frame frame) throws Exception {
            if (!frame.event.isEmpty() && !"message".equals(frame.event)) throw invalid("AST-L282-1");
            if ("[DONE]".equals(frame.data)) {
                if (!seen || finish == null) throw interrupted();
                return true;
            }
            JSONObject chunk = eventObject(frame.data);
            if (!chunk.isNull("error") || !"chat.completion.chunk".equals(chunk.optString("object"))) throw invalid("AST-L288-1");
            if (chunk.has("id")) {
                String id = identity(chunk, "id");
                if (responseId != null && !responseId.equals(id)) throw invalid("AST-L291-1");
                responseId = id;
            }
            JSONArray choices = chunk.getJSONArray("choices");
            if (choices.length() == 0) {
                usage.observe(chunk,finish==null?ProviderUsage.Stage.PARTIAL:ProviderUsage.Stage.FINAL);
                return false; // A usage-only frame never completes a response or dispatches a tool.
            }
            if (choices.length() != 1 || finish != null) throw invalid("AST-L299-1");
            JSONObject choice = choices.getJSONObject(0);
            if (integer(choice, "index", 1) != 0) throw invalid("AST-L301-1");
            JSONObject delta = choice.getJSONObject("delta");
            if (!delta.isNull("role") && !"assistant".equals(delta.optString("role"))) throw invalid("AST-L303-1");
            // Numeric evidence from this parsed response is independent of presentation callbacks.
            usage.observe(chunk,ProviderUsage.Stage.PARTIAL);
            if (!delta.isNull("function_call")) throw invalid("AST-L306-1"); // Legacy calls have no stable tool identity.
            if (delta.has("refusal") && !delta.isNull("refusal") && !text(delta, "refusal", MAX_TEXT_CHARS).isEmpty())
                throw refused();
            if (delta.has("content") && !delta.isNull("content")) {
                String value = text(delta, "content", MAX_TEXT_CHARS);
                append(visible, value, MAX_TEXT_CHARS); sink.text(value);
            }
            if (delta.has("reasoning_content") && !delta.isNull("reasoning_content"))
                append(reasoning, text(delta, "reasoning_content", MAX_TEXT_CHARS), MAX_TEXT_CHARS);
            if (!delta.isNull("tool_calls")) {
                JSONArray fragments = delta.getJSONArray("tool_calls");
                Set<Integer> frameIndexes = new HashSet<>();
                for (int i = 0; i < fragments.length(); i++) {
                    JSONObject fragment = fragments.getJSONObject(i);
                    int index = integer(fragment, "index", MAX_TOOLS);
                    if (!frameIndexes.add(index)) throw invalid("AST-L321-1");
                    ChatCall call = calls.get(index);
                    if (call == null) { call = new ChatCall(); calls.put(index, call); }
                    if (fragment.has("type") && !"function".equals(fragment.optString("type"))) throw invalid("AST-L324-1");
                    if (fragment.has("id")) {
                        String id = identity(fragment, "id");
                        if (call.id != null && !call.id.equals(id)) throw invalid("AST-L327-1");
                        for(ChatCall other:calls.values())if(other!=call&&id.equals(other.id))throw invalid("AST-L328-1");
                        call.id = id;
                    }
                    JSONObject function = fragment.optJSONObject("function");
                    if (function != null) {
                        if (function.has("name")) {
                            String name = text(function, "name", 256);
                            String assembled=call.name==null?name:call.name+name;
                            boolean prefix=false;for(String offered:names)if(offered.startsWith(assembled)){prefix=true;break;}
                            if(!prefix)throw invalid("AST-L337-1");call.name=assembled;
                            if (call.name.length() > 256) throw invalid("AST-L338-1");
                        }
                        if (function.has("arguments")) append(call.arguments,
                                text(function, "arguments", MAX_ARGUMENT_CHARS), MAX_ARGUMENT_CHARS);
                    }
                }
            }
            if (choice.has("finish_reason") && !choice.isNull("finish_reason")) {
                finish = text(choice, "finish_reason", 64);
                usage.terminal(finish);
                usage.observe(chunk,ProviderUsage.Stage.FINAL);terminalChat(finish);
            }
            else usage.observe(chunk,ProviderUsage.Stage.PARTIAL);
            seen = true;
            return false;
        }
        public JSONObject result() throws Exception {
            JSONArray output = new JSONArray(); int expected = 0;
            for (Map.Entry<Integer, ChatCall> entry : calls.entrySet()) {
                ChatCall call = entry.getValue();
                if (entry.getKey() != expected++ || call.id == null || call.name == null) throw invalid("AST-L358-1");
                object(call.arguments.toString());
                output.put(new JSONObject().put("type", "function").put("id", call.id)
                        .put("function", new JSONObject().put("name", call.name).put("arguments", call.arguments.toString())));
            }
            if ("tool_calls".equals(finish) != (output.length() > 0)) throw invalid("AST-L363-1");
            JSONObject message = new JSONObject().put("role", "assistant").put("content", visible.toString());
            if (reasoning.length() > 0) message.put("reasoning_content", reasoning.toString());
            if (output.length() > 0) message.put("tool_calls", output);
            return new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                    .put("finish_reason", finish).put("message", message)));
        }
    }

    private static final class ChatCall {
        String id, name;
        final StringBuilder arguments = new StringBuilder();
    }

    private static final class Responses implements State {
        private final TextSink sink;
        private final ProviderUsage.Observer usage;
        private final TreeMap<Integer, ResponseItem> items = new TreeMap<>();
        private String responseId;
        private long sequence = -1;
        private Boolean numbered;
        private final Set<String> itemIds = new HashSet<>();
        private JSONObject completed;
        private boolean incompleteItem, incompleteArguments;
        Responses(TextSink sink,ProviderUsage.Observer usage) { this.sink = sink;this.usage=usage; }
        public boolean accept(Frame frame) throws Exception {
            JSONObject event = eventObject(frame.data);
            String type = identity(event, "type"); named(frame, type);
            if (numbered == null) numbered = event.has("sequence_number");
            if (numbered != event.has("sequence_number")) throw invalid("AST-L392-1");
            if (numbered) {
                long next = nonnegative(event, "sequence_number");
                if (next <= sequence) throw invalid("AST-L395-1"); sequence = next;
            }
            if(type.equals("error"))throw interrupted();
            if(type.equals("response.failed")||type.equals("response.incomplete")||type.equals("response.cancelled")){
                JSONObject response=event.getJSONObject("response");
                if(responseId==null||!responseId.equals(identity(response,"id")))throw invalid("AST-L400-1");
                usage.terminal(type.substring("response.".length()));
                usage.observe(response,ProviderUsage.Stage.FINAL);throw interrupted();
            }
            if (type.equals("response.created")) {
                JSONObject response = event.getJSONObject("response");
                if (responseId != null || !"in_progress".equals(response.optString("status"))) throw invalid("AST-L406-1");
                responseId = identity(response, "id"); return false;
            }
            if (responseId == null) throw invalid("AST-L409-1");
            switch (type) {
                case "response.in_progress": {
                    JSONObject response = event.getJSONObject("response");
                    if (!responseId.equals(identity(response, "id")) || !"in_progress".equals(response.optString("status")))
                        throw invalid("AST-L414-1");
                    break;
                }
                case "response.output_item.added": {
                    int index = integer(event, "output_index", MAX_ITEMS);
                    JSONObject raw = event.getJSONObject("item"); String kind = identity(raw, "type");
                    if (!Set.of("message", "reasoning", "function_call").contains(kind) || items.containsKey(index)) throw invalid("AST-L420-1");
                    if (!itemIds.add(identity(raw, "id"))) throw invalid("AST-L421-1");
                    if("function_call".equals(kind)){String callId=identity(raw,"call_id");identity(raw,"name");for(ResponseItem other:items.values())if("function_call".equals(other.raw.optString("type"))&&callId.equals(other.raw.optString("call_id")))throw invalid("AST-L422-1");}
                    ResponseItem item = new ResponseItem(copy(raw)); items.put(index, item); break;
                }
                case "response.function_call_arguments.delta": {
                    ResponseItem item = item(event, "function_call");
                    if (item.argumentsDone || item.done != null) throw invalid("AST-L427-1");
                    item.argumentDeltas = true;
                    append(item.arguments, text(event, "delta", MAX_ARGUMENT_CHARS), MAX_ARGUMENT_CHARS); break;
                }
                case "response.function_call_arguments.done": {
                    ResponseItem item = item(event, "function_call");
                    if (item.argumentsDone || item.done != null) throw invalid("AST-L433-1");
                    // A token-limited call may end with partial JSON. Only a completed
                    // output item can become executable and must pass object validation.
                    String args = text(event, "arguments", MAX_ARGUMENT_CHARS);
                    try { object(args); } catch (GatewayException unfinished) { incompleteArguments = true; }
                    if ((item.argumentDeltas || item.arguments.length() > 0) && !item.arguments.toString().equals(args)) throw invalid("AST-L438-1");
                    item.arguments.setLength(0); item.arguments.append(args); item.argumentsDone = true; break;
                }
                case "response.content_part.added": {
                    ResponseItem item = item(event, "message"); int index = integer(event, "content_index", MAX_ITEMS);
                    JSONObject part = event.getJSONObject("part");
                    if (!"output_text".equals(part.optString("type")) || item.parts.containsKey(index) || item.done != null)
                        throw invalid("AST-L445-1");
                    String initial = part.has("text") ? text(part, "text", MAX_TEXT_CHARS) : "";
                    item.parts.put(index, new TextPart(initial)); if (!initial.isEmpty()) sink.text(initial); break;
                }
                case "response.output_text.delta": {
                    TextPart part = part(event); if (part.done || part.textDone) throw invalid("AST-L450-1");
                    String value = text(event, "delta", MAX_TEXT_CHARS);
                    append(part.text, value, MAX_TEXT_CHARS); sink.text(value); break;
                }
                case "response.output_text.done": {
                    TextPart part = part(event);
                    if (part.done || part.textDone || !part.text.toString().equals(text(event, "text", MAX_TEXT_CHARS)))
                        throw invalid("AST-L457-1");
                    part.textDone = true; break;
                }
                case "response.content_part.done": {
                    TextPart part = part(event); JSONObject raw = event.getJSONObject("part");
                    if (part.done || !"output_text".equals(raw.optString("type"))
                            || !part.text.toString().equals(text(raw, "text", MAX_TEXT_CHARS))) throw invalid("AST-L463-1");
                    part.done = true; break;
                }
                case "response.refusal.delta": case "response.refusal.done": throw refused();
                case "response.output_item.done": {
                    ResponseItem item = item(event, null);
                    if (item.done != null) throw invalid("AST-L469-1");
                    JSONObject raw = event.getJSONObject("item");
                    boolean incomplete="incomplete".equals(raw.optString("status"));
                    checkItem(item,raw,!incomplete);item.done=copy(raw);incompleteItem|=incomplete;break;
                }
                case "response.completed": {
                    JSONObject raw = event.getJSONObject("response");
                    if (!responseId.equals(identity(raw, "id")) || !"completed".equals(raw.optString("status"))) throw invalid("AST-L476-1");
                    usage.observe(raw,ProviderUsage.Stage.FINAL);
                    usage.terminal("completed");
                    if(incompleteItem)throw invalid("AST-L479-1"); // An incomplete item never becomes a successful turn.
                    JSONArray output = raw.getJSONArray("output");
                    if (output.length() > MAX_ITEMS) throw tooLarge();
                    Set<String> finalIds = new HashSet<>();
                    for (int i = 0; i < output.length(); i++) if (!finalIds.add(identity(output.getJSONObject(i), "id"))) throw invalid("AST-L483-1");
                    for (Map.Entry<Integer, ResponseItem> entry : items.entrySet()) {
                        if (entry.getKey() >= output.length()) throw invalid("AST-L485-1");
                        JSONObject finalItem = output.getJSONObject(entry.getKey());
                        checkItem(entry.getValue(), finalItem,true);
                        if (entry.getValue().done != null && !same(entry.getValue().done, finalItem)) throw invalid("AST-L488-1");
                    }
                    completed = copy(raw); return true;
                }
                default:
                    // Unknown metadata/reasoning events never become text, a tool or authority.
                    // The completed native output must still pass the existing wire codec.
                    if (type.startsWith("response.function_call") || type.startsWith("response.output_text")
                            || type.startsWith("response.content_part")) throw invalid("AST-L496-1");
            }
            return false;
        }
        private ResponseItem item(JSONObject event, String expected) throws Exception {
            ResponseItem item = items.get(integer(event, "output_index", MAX_ITEMS));
            if (item == null || expected != null && !expected.equals(item.raw.optString("type"))) throw invalid("AST-L502-1");
            if (event.has("item_id") && !item.raw.getString("id").equals(identity(event, "item_id"))) throw invalid("AST-L503-1");
            return item;
        }
        private TextPart part(JSONObject event) throws Exception {
            ResponseItem item = item(event, "message");
            TextPart part = item.parts.get(integer(event, "content_index", MAX_ITEMS));
            if (part == null || item.done != null) throw invalid("AST-L509-1"); return part;
        }
        private void checkItem(ResponseItem item, JSONObject raw,boolean complete) throws Exception {
            if (!item.raw.getString("id").equals(identity(raw, "id")) || !item.raw.getString("type").equals(raw.optString("type")))
                throw invalid("AST-L513-1");
            if ("function_call".equals(raw.optString("type"))) {
                for (String key : new String[]{"call_id", "name"})
                    if (!item.raw.getString(key).equals(identity(raw, key))) throw invalid("AST-L516-1");
                String args = text(raw, "arguments", MAX_ARGUMENT_CHARS);if(complete)object(args);
                if ((item.argumentDeltas || item.argumentsDone || item.arguments.length() > 0) && !item.arguments.toString().equals(args)) throw invalid("AST-L518-1");
            }
            if (!item.parts.isEmpty()) {
                JSONArray content = raw.getJSONArray("content");
                for (Map.Entry<Integer, TextPart> entry : item.parts.entrySet()) {
                    if (entry.getKey() >= content.length()) throw invalid("AST-L523-1"); JSONObject part = content.getJSONObject(entry.getKey());
                    if (!"output_text".equals(part.optString("type"))
                            || !entry.getValue().text.toString().equals(text(part, "text", MAX_TEXT_CHARS))) throw invalid("AST-L525-1");
                }
            }
        }
        public JSONObject result() throws Exception { if (completed == null) throw interrupted(); return completed; }
    }

    private static final class ResponseItem {
        final JSONObject raw;
        final StringBuilder arguments = new StringBuilder();
        final Map<Integer, TextPart> parts = new HashMap<>();
        boolean argumentDeltas, argumentsDone;
        JSONObject done;
        ResponseItem(JSONObject raw) { this.raw = raw; if ("function_call".equals(raw.optString("type"))) arguments.append(raw.optString("arguments", "")); }
    }
    private static final class TextPart {
        final StringBuilder text = new StringBuilder(); boolean textDone, done;
        TextPart(String initial) { text.append(initial); }
    }

    private static final class Anthropic implements State {
        private final TextSink sink;
        private final ProviderUsage.Observer usage;
        private final TreeMap<Integer, AnthropicBlock> blocks = new TreeMap<>();
        private JSONObject message;
        private String finish;
        Anthropic(TextSink sink,ProviderUsage.Observer usage) { this.sink = sink;this.usage=usage; }
        public boolean accept(Frame frame) throws Exception {
            JSONObject event = eventObject(frame.data); String type = identity(event, "type"); named(frame, type);
            return accept(event,type);
        }
        boolean accept(JSONObject event)throws Exception {return accept(event,identity(event,"type"));}
        private boolean accept(JSONObject event,String type)throws Exception {
            if ("error".equals(type)) throw interrupted();
            if ("ping".equals(type)) return false;
            if ("message_start".equals(type)) {
                if (message != null) throw invalid("AST-L561-1"); message = copy(event.getJSONObject("message"));
                if (!"assistant".equals(message.optString("role")) || !"message".equals(message.optString("type"))
                        || message.getJSONArray("content").length() != 0) throw invalid("AST-L563-1");
                identity(message, "id");usage.observe(message,ProviderUsage.Stage.PARTIAL); return false;
            }
            if (message == null) throw invalid("AST-L566-1");
            switch (type) {
                case "content_block_start": {
                    if (finish != null) throw invalid("AST-L569-1"); int index = integer(event, "index", MAX_ITEMS);
                    JSONObject raw = copy(event.getJSONObject("content_block"));
                    if (blocks.containsKey(index) || !Set.of("text", "tool_use", "thinking", "redacted_thinking").contains(raw.optString("type")))
                        throw invalid("AST-L572-1");
                    if ("tool_use".equals(raw.optString("type"))) { String callId=identity(raw, "id"); identity(raw, "name"); raw.getJSONObject("input");for(AnthropicBlock other:blocks.values())if("tool_use".equals(other.raw.optString("type"))&&callId.equals(other.raw.optString("id")))throw invalid("AST-L573-1"); }
                    if ("text".equals(raw.optString("type")) && raw.has("text")) sink.text(text(raw, "text", MAX_TEXT_CHARS));
                    blocks.put(index, new AnthropicBlock(raw)); break;
                }
                case "content_block_delta": {
                    AnthropicBlock block = block(event); if (block.done || finish != null) throw invalid("AST-L578-1");
                    JSONObject delta = event.getJSONObject("delta"); String change = identity(delta, "type");
                    String kind = block.raw.getString("type");
                    if ("text_delta".equals(change) && "text".equals(kind)) {
                        String value = text(delta, "text", MAX_TEXT_CHARS); extend(block.raw, "text", value, MAX_TEXT_CHARS); sink.text(value);
                    } else if ("input_json_delta".equals(change) && "tool_use".equals(kind)) {
                        if(block.raw.getJSONObject("input").length()!=0)throw invalid("AST-L584-1");
                        append(block.arguments, text(delta, "partial_json", MAX_ARGUMENT_CHARS), MAX_ARGUMENT_CHARS); block.argumentDeltas = true;
                    } else if ("thinking_delta".equals(change) && "thinking".equals(kind)) {
                        extend(block.raw, "thinking", text(delta, "thinking", MAX_TEXT_CHARS), MAX_TEXT_CHARS);
                    } else if ("signature_delta".equals(change) && "thinking".equals(kind)) {
                        extend(block.raw, "signature", text(delta, "signature", MAX_TEXT_CHARS), MAX_TEXT_CHARS);
                    } else throw invalid("AST-L590-1");
                    break;
                }
                case "content_block_stop": {
                    AnthropicBlock block = block(event); if (block.done) throw invalid("AST-L594-1");
                    if (block.argumentDeltas) block.raw.put("input", object(block.arguments.toString()));
                    block.done = true; break;
                }
                case "message_delta": {
                    JSONObject delta = event.getJSONObject("delta");
                    usage.observe(event,ProviderUsage.Stage.PARTIAL);
                    JSONObject counters=event.optJSONObject("usage");if(counters!=null){JSONObject merged=message.optJSONObject("usage");if(merged==null)merged=new JSONObject();for(Iterator<String> it=counters.keys();it.hasNext();){String key=it.next();merged.put(key,counters.get(key));}message.put("usage",merged);}
                    if (delta.has("stop_reason") && !delta.isNull("stop_reason")) {
                        for (AnthropicBlock block : blocks.values()) if (!block.done) throw invalid("AST-L603-1");
                        if (finish != null) throw invalid("AST-L604-1"); finish = text(delta, "stop_reason", 64);usage.terminal(finish); terminalAnthropic(finish);
                    }
                    break;
                }
                case "message_stop": {
                    if (finish == null) throw interrupted();
                    int expected = 0, calls = 0; JSONArray content = new JSONArray();
                    for (Map.Entry<Integer, AnthropicBlock> entry : blocks.entrySet()) {
                        if (entry.getKey() != expected++ || !entry.getValue().done) throw interrupted();
                        JSONObject raw = entry.getValue().raw;
                        if ("tool_use".equals(raw.optString("type"))) calls++;
                        content.put(raw);
                    }
                    if ("tool_use".equals(finish) != (calls > 0)) throw invalid("AST-L617-1");
                    message.put("content", content).put("stop_reason", finish); return true;
                }
                default: throw invalid("AST-L620-1");
            }
            return false;
        }
        private AnthropicBlock block(JSONObject event) throws Exception {
            AnthropicBlock block = blocks.get(integer(event, "index", MAX_ITEMS)); if (block == null) throw invalid("AST-L625-1"); return block;
        }
        public JSONObject result() { return message; }
    }
    private static final class AnthropicBlock {
        final JSONObject raw; final StringBuilder arguments = new StringBuilder(); boolean argumentDeltas, done;
        AnthropicBlock(JSONObject raw) { this.raw = raw; }
    }

    private static final class Gemini implements State {
        private final TextSink sink;
        private final ProviderUsage.Observer usage;
        private final JSONArray parts = new JSONArray();
        private String responseId;
        private JSONObject completed;
        Gemini(TextSink sink,ProviderUsage.Observer usage) { this.sink = sink;this.usage=usage; }
        public boolean accept(Frame frame) throws Exception {
            if (!frame.event.isEmpty() && !"message".equals(frame.event)) throw invalid("AST-L642-1");
            JSONObject event = eventObject(frame.data);
            if (event.has("error")) throw interrupted();
            if (event.has("responseId")) {
                String id = identity(event, "responseId");
                if (responseId != null && !responseId.equals(id)) throw invalid("AST-L647-1"); responseId = id;
            }
            JSONObject feedback = event.optJSONObject("promptFeedback");
            usage.observe(event,ProviderUsage.Stage.PARTIAL);
            if (feedback != null && !feedback.isNull("blockReason") && !feedback.optString("blockReason").isEmpty()) throw refused();
            JSONArray candidates = event.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) return false;
            if (candidates.length() != 1) throw invalid("AST-L654-1");
            JSONObject candidate = candidates.getJSONObject(0);
            if (candidate.has("index") && integer(candidate, "index", 1) != 0) throw invalid("AST-L656-1");
            JSONObject content = candidate.optJSONObject("content");
            if (content != null) {
                if (content.has("role") && !"model".equals(content.optString("role"))) throw invalid("AST-L659-1");
                JSONArray chunk = content.getJSONArray("parts");
                for (int i = 0; i < chunk.length(); i++) {
                    JSONObject part = copy(chunk.getJSONObject(i));
                    validateGeminiPart(part);
                    if (part.has("text")) {
                        String value = text(part, "text", MAX_TEXT_CHARS);
                        if (!part.optBoolean("thought", false)) sink.text(value);
                    }
                    JSONObject previous = parts.length() == 0 ? null : parts.getJSONObject(parts.length() - 1);
                    if (part.has("text") && previous != null && previous.has("text") && !previous.has("thoughtSignature")
                            && part.optBoolean("thought", false) == previous.optBoolean("thought", false)) {
                        extend(previous, "text", part.getString("text"), MAX_TEXT_CHARS);
                        if (part.has("thoughtSignature")) previous.put("thoughtSignature", text(part, "thoughtSignature", MAX_TEXT_CHARS));
                    } else {
                        if (parts.length() >= MAX_ITEMS) throw tooLarge(); parts.put(part);
                    }
                }
            }
            if (candidate.has("finishReason")) {
                usage.terminal(candidate.optString("finishReason"));
                usage.observe(event,ProviderUsage.Stage.FINAL);
                if (!"STOP".equals(candidate.optString("finishReason"))) throw interrupted();
                completed = new JSONObject().put("candidates", new JSONArray().put(new JSONObject().put("finishReason", "STOP")
                        .put("content", new JSONObject().put("role", "model").put("parts", parts))));
                return true;
            }
            return false;
        }
        public JSONObject result() throws Exception { if (completed == null) throw interrupted(); return completed; }
    }

    private static final class Frame {
        final String event, data;
        Frame(String event, String data) { this.event = event; this.data = data; }
    }
    private static final class Sse {
        private final InputStream input;
        private final RunControl control;
        private int total, eventBytes;
        private boolean skipLf, first = true;
        Sse(InputStream input, RunControl control) { this.input = new BufferedInputStream(input, 8192); this.control = control; }
        Frame next() throws Exception {
            eventBytes = 0; String event = ""; StringBuilder data = new StringBuilder(); boolean hasData = false;
            while (true) {
                control.check(); String line = line(); if (line == null) return null;
                if (first) { first = false; if (line.startsWith("\uFEFF")) line = line.substring(1); }
                if (line.isEmpty()) {
                    if (hasData) return new Frame(event, data.toString());
                    event = ""; eventBytes = 0; continue;
                }
                if (line.startsWith(":")) continue;
                int colon = line.indexOf(':'); String field = colon < 0 ? line : line.substring(0, colon);
                String value = colon < 0 ? "" : line.substring(colon + 1); if (value.startsWith(" ")) value = value.substring(1);
                if ("data".equals(field)) { if (hasData) data.append('\n'); data.append(value); hasData = true; }
                else if ("event".equals(field)) event = value;
            }
        }
        private String line() throws Exception {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            while (true) {
                int value = input.read();
                if (value < 0) return null; // An unterminated SSE event never reaches the decoder.
                if (++total > MAX_BYTES || ++eventBytes > MAX_EVENT_BYTES) throw tooLarge();
                if (skipLf) { skipLf = false; if (value == '\n') continue; }
                if (value == '\r') { skipLf = true; break; }
                if (value == '\n') break;
                bytes.write(value);
            }
            try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString(); }
            catch (java.nio.charset.CharacterCodingException badUtf8) { GatewayException failure=invalid("AST-L730-1");failure.initCause(badUtf8);throw failure; }
        }
    }

    private static void named(Frame frame, String type) throws Exception {
        if (!frame.event.isEmpty() && !frame.event.equals(type)) throw invalid("AST-L735-1");
    }
    /** The same native completeness checks apply to SSE chunks and same-request JSON replies. */
    private static void validateGeminiPart(JSONObject part) throws Exception {
        for (Iterator<String> keys = part.keys(); keys.hasNext();)
            if (!Set.of("text", "thought", "thoughtSignature", "functionCall").contains(keys.next())) throw invalid("AST-L740-1");
        if (part.has("thought") && !(part.opt("thought") instanceof Boolean)) throw invalid("AST-L741-1");
        if (part.has("thoughtSignature")) text(part, "thoughtSignature", MAX_TEXT_CHARS);
        if (part.has("text")) {
            if (part.has("functionCall")) throw invalid("AST-L744-1"); text(part, "text", MAX_TEXT_CHARS);
        } else if (part.has("functionCall")) {
            JSONObject call = part.getJSONObject("functionCall"); identity(call, "name");
            // Gemini omits args for no-argument functions. The native adapter normalizes
            // that absence to {}; an explicitly supplied value must still be an object.
            if (call.has("args")) call.getJSONObject("args");
            if (call.has("id") && !call.isNull("id")) identity(call, "id");
            if (call.has("partialArgs") || call.has("willContinue")
                    && (!(call.opt("willContinue") instanceof Boolean) || call.getBoolean("willContinue"))) throw invalid("AST-L752-1");
        } else throw invalid("AST-L753-1");
    }
    private static void terminalChat(String reason) throws Exception {
        if (!Set.of("stop", "tool_calls").contains(reason)) throw interrupted();
    }
    private static void terminalAnthropic(String reason) throws Exception {
        if (!Set.of("end_turn", "tool_use", "stop_sequence").contains(reason)) throw interrupted();
    }
    private static void extend(JSONObject target, String key, String delta, int max) throws Exception {
        String old = target.has(key) ? text(target, key, max) : "";
        if ((long) old.length() + delta.length() > max) throw tooLarge(); target.put(key, old + delta);
    }
    private static void append(StringBuilder target, String delta, int max) throws Exception {
        if ((long) target.length() + delta.length() > max) throw tooLarge(); target.append(delta);
    }
    private static String text(JSONObject object, String key, int max) throws Exception {
        Object value = object.get(key); if (!(value instanceof String) || ((String) value).length() > max) throw invalid("AST-L769-1"); return (String) value;
    }
    private static String identity(JSONObject object, String key) throws Exception {
        String value = text(object, key, 256); if (value.isEmpty()) throw invalid("AST-L772-1");
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) throw invalid("AST-L773-1");
        return value;
    }
    private static long nonnegative(JSONObject object, String key) throws Exception {
        Object raw = object.get(key);
        if (!(raw instanceof Number)) throw invalid("AST-L778-1"); double value = ((Number) raw).doubleValue(); long result = ((Number) raw).longValue();
        if (!Double.isFinite(value) || value != result || result < 0) throw invalid("AST-L779-1"); return result;
    }
    private static int integer(JSONObject object, String key, int upperExclusive) throws Exception {
        long value = nonnegative(object, key); if (value >= upperExclusive) throw invalid("AST-L782-1"); return (int) value;
    }
    private static JSONObject object(String value) throws Exception {
        try{return ProtocolJson.parse(value);}catch(Exception bad){GatewayException failure=invalid("AST-L785-1");failure.initCause(bad);throw failure;}
    }
    private static JSONObject eventObject(String value)throws Exception{try{return ProtocolJson.parseEvent(value);}catch(Exception bad){GatewayException failure=invalid("AST-L787-1");failure.initCause(bad);throw failure;}}
    private static JSONObject copy(JSONObject value) throws Exception { return eventObject(value.toString()); }
    private static boolean same(Object a, Object b) throws Exception {
        if (a instanceof JSONObject && b instanceof JSONObject) {
            JSONObject left = (JSONObject) a, right = (JSONObject) b;
            if (left.length() != right.length()) return false;
            for (Iterator<String> keys = left.keys(); keys.hasNext();) { String key = keys.next(); if (!right.has(key) || !same(left.get(key), right.get(key))) return false; }
            return true;
        }
        if (a instanceof JSONArray && b instanceof JSONArray) {
            JSONArray left = (JSONArray) a, right = (JSONArray) b; if (left.length() != right.length()) return false;
            for (int i = 0; i < left.length(); i++) if (!same(left.get(i), right.get(i))) return false; return true;
        }
        return a == null ? b == null : a.equals(b);
    }
    private static void unicodeTree(Object value, int depth) throws Exception {
        if (depth > 32) throw invalid("AST-L803-1");
        if (value instanceof String) {
            String text = (String) value;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (Character.isHighSurrogate(c)) { if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) throw invalid("AST-L808-1"); }
                else if (Character.isLowSurrogate(c)) throw invalid("AST-L809-1");
            }
        } else if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            for (Iterator<String> keys = object.keys(); keys.hasNext();) { String key = keys.next(); unicodeTree(key, depth + 1); unicodeTree(object.get(key), depth + 1); }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value; for (int i = 0; i < array.length(); i++) unicodeTree(array.get(i), depth + 1);
        }
    }
    private static GatewayException invalid(String checkpoint) {
        StackTraceElement[] trace = new Throwable().getStackTrace();
        GatewayException failure = new GatewayException("模型流校验失败 [" + checkpoint + "]。当前流已停止；此前工具执行情况请查看调用记录。");
        failure.setStackTrace(trace);
        return failure;
    }
    private static GatewayException interrupted() { return new GatewayException("模型流没有取得可验证的完整终态；临时输出不能作为完成结果，未派发工具，没有自动重试。"); }
    private static GatewayException tooLarge() { return new GatewayException("模型流超过本次大小或事件限制；未派发工具，没有自动重试。"); }
    private static GatewayException refused() { return new GatewayException("模型拒绝或阻止了本次请求；未派发工具，也没有自动换模型重试。"); }
}
