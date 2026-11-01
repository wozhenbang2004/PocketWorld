package dev.pocketengine.runtime.providers;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.runtime.models.ModelRequestPolicy;
import java.net.URL;
import java.util.*;
import org.json.*;

/**
 * Conservative request codecs around the existing native adapters. Inspired by Magpie's ordered
 * Request/Part contract (16ce5ed), not its lossy fallbacks, boolean strict or tool execution paths.
 */
public final class ProtocolCodec {
    public enum Strict { UNSPECIFIED, FALSE, TRUE }
    public enum Kind { TEXT, TOOL_CALL, TOOL_RESULT }
    public record Part(Kind kind,String text,String id,String name,JSONObject arguments,Boolean isError){}
    public record Message(String role,int position,List<Part> parts){}
    public record Tool(String name,String description,JSONObject schema,Strict strict){}
    public record Canonical(String protocol,List<Message> messages,List<Tool> tools,Strict outputStrict){}
    public static final class Rejected extends Exception {
        public final String code;
        public Rejected(String code){super("协议不支持此无损组合（"+code+"）；本次未发送，也未删除字段。");this.code=code;}
    }
    public static String ingress(String path)throws Rejected {
        if(path.equals("/v1/chat/completions"))return "openai_chat";
        if(path.equals("/v1/responses"))return "openai_responses";
        if(path.equals("/v1/messages"))return "anthropic";
        if(path.startsWith("/v1beta/models/")&&(path.endsWith(":generateContent")||path.endsWith(":streamGenerateContent?alt=sse")))return "gemini";
        throw reject("unknown_protocol_path");
    }
    public static GatewayRequest prepare(ModelConfig selected,String ingress,JSONObject client)throws Exception {
        if(selected.usesAccount())throw reject("api_only_audience");
        if(!ProtocolCompatibility.crossPair(ingress,selected.provider))throw reject("pair_not_enabled");
        JSONObject original=copy(client);Canonical decoded=decode(ingress,original);
        for(Iterator<String> it=original.keys();it.hasNext();)if(ProtocolCompatibility.field(ingress,selected.provider,it.next())==ProtocolCompatibility.Disposition.UNSUPPORTED)throw reject("field_disposition_unsupported");
        if(original.has("model")&&!selected.model.equals(string(original,"model"))&&(selected.routePlan==null||!selected.routePlan.requestedAlias.equals(string(original,"model"))))throw reject("requested_model_not_bound");
        boolean stream=bool(original,"stream",false);
        String ceiling=ingress.equals("anthropic")?"max_tokens":original.has("max_completion_tokens")?"max_completion_tokens":"max_tokens";
        if(original.has("max_completion_tokens")&&original.has("max_tokens"))throw reject("ambiguous_output_limit");
        int limit=original.has(ceiling)?integer(original.get(ceiling),1,selected.maxOutputTokens):selected.maxOutputTokens;
        ModelConfig config=selected.withRequestOutputLimit(limit);
        if(config.reasoning.mode()!=dev.pocketengine.runtime.models.ReasoningOptions.Mode.DEFAULT)throw reject("cross_reasoning_not_supported");
        JSONObject wire=config.provider.equals("anthropic")?anthropic(decoded):responses(decoded);
        wire.put("model",config.model).put("stream",stream).put(config.provider.equals("anthropic")?"max_tokens":"max_output_tokens",limit);
        if(config.provider.equals("openai_responses"))wire.put("store",false);
        for(String key:new String[]{"temperature","top_p"})if(original.has(key)){Object n=original.get(key);if(!(n instanceof Number)||!Double.isFinite(((Number)n).doubleValue()))throw reject("invalid_sampling");wire.put(key,n);}
        if(ingress.equals("openai_chat")&&original.has("stop"))wire.put("stop_sequences",stops(original.get("stop")));
        if(original.has("tool_choice")){
            Object chosen=choice(ingress,config.provider,original.get("tool_choice"));
            if(chosen instanceof JSONObject&&((JSONObject)chosen).has("name")){String toolName=((JSONObject)chosen).getString("name");if(decoded.tools.stream().noneMatch(t->t.name.equals(toolName)))throw reject("undeclared_tool_choice");}
            wire.put("tool_choice",chosen);
        }
        if(original.has("parallel_tool_calls")){
            boolean parallel=bool(original,"parallel_tool_calls",true);JSONObject choice=wire.optJSONObject("tool_choice");
            if(choice==null)choice=new JSONObject().put("type","auto");choice.put("disable_parallel_tool_use",!parallel);wire.put("tool_choice",choice);
        }
        if(ingress.equals("anthropic")){JSONObject choice=original.optJSONObject("tool_choice");if(choice!=null&&choice.has("disable_parallel_tool_use"))wire.put("parallel_tool_calls",!bool(choice,"disable_parallel_tool_use",false));}
        JSONArray tools=internalTools(decoded),messages=budgetMessages(decoded,wire);boolean history=decoded.messages.stream().anyMatch(m->m.parts.stream().anyMatch(p->p.kind!=Kind.TEXT));
        JSONObject admitted=ModelRequestPolicy.applyNative(config,messages,tools,wire,history);
        URL endpoint=ProviderAdapters.selected(config.provider).endpoint(config);if(stream)endpoint=ApiTurnStream.endpoint(config.provider,endpoint);
        final JSONObject frozen=copy(admitted);
        return new GatewayRequest(config,ingress,original,admitted,messages,tools,endpoint,stream,history,
                ()->ModelRequestPolicy.applyNative(config,messages,tools,copy(frozen),history),candidate->prepare(candidate,ingress,original));
    }
    /** All four ingress schemas have explicit nested allowlists, even when their cross-pair is disabled. */
    public static Canonical decode(String protocol,JSONObject raw)throws Exception {
        if(!ProtocolCompatibility.PROTOCOLS.contains(protocol))throw reject("unknown_protocol");
        rejectOpaque(raw);
        Strict output=Strict.UNSPECIFIED;
        JSONObject format=raw.optJSONObject("response_format");if(format!=null){JSONObject schema=format.optJSONObject("json_schema");if(schema!=null)output=strict(schema);throw reject("output_format_unsupported_"+output.name());}
        JSONObject text=raw.optJSONObject("text");if(text!=null){JSONObject f=text.optJSONObject("format");if(f!=null)output=strict(f);throw reject("output_format_unsupported_"+output.name());}
        if(raw.has("output_config"))throw reject("output_format_unsupported");
        keys(raw,ProtocolCompatibility.coreFields(protocol));
        if(raw.has("stream"))bool(raw,"stream",false);
        if(raw.has("stream_options")){JSONObject opts=raw.getJSONObject("stream_options");keys(opts,Set.of("include_usage"));bool(opts,"include_usage",false);}
        List<Message> messages=new ArrayList<>();List<Tool> tools=new ArrayList<>();
        switch(protocol){
            case "openai_chat":chat(raw,messages,tools);break;
            case "anthropic":messages(raw,messages,tools);break;
            case "openai_responses":responsesInput(raw,messages,tools);break;
            case "gemini":gemini(raw,messages,tools);break;
            default:throw reject("unknown_protocol");
        }
        if(messages.isEmpty())throw reject("empty_input");if(tools.size()>64)throw reject("tool_count");
        Set<String> names=new HashSet<>();for(Tool tool:tools)if(!names.add(tool.name))throw reject("duplicate_tool_name");
        Map<String,String> pending=new LinkedHashMap<>();Set<String> all=new HashSet<>();
        for(Message m:messages)for(Part p:m.parts){
            if(p.kind==Kind.TOOL_CALL){if(!m.role.equals("assistant")||!all.add(p.id))throw reject("duplicate_or_misplaced_tool_call");pending.put(p.id,p.name);}
            if(p.kind==Kind.TOOL_RESULT){if(!m.role.equals("user")&&!m.role.equals("tool")||pending.remove(p.id)==null)throw reject("orphan_or_duplicate_tool_result");}
        }
        if(!pending.isEmpty())throw reject("unanswered_tool_call");
        return new Canonical(protocol,List.copyOf(messages),List.copyOf(tools),output);
    }
    private static void chat(JSONObject raw,List<Message> out,List<Tool> tools)throws Exception {
        JSONArray rows=array(raw.get("messages"));for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);keys(row,Set.of("role","content","tool_calls","tool_call_id"));String role=string(row,"role");
            if(!Set.of("system","developer","user","assistant","tool").contains(role))throw reject("role_unsupported");List<Part> parts=new ArrayList<>();
            if(role.equals("tool")){parts.add(new Part(Kind.TOOL_RESULT,textContent(row.get("content"),"text"),identity(row,"tool_call_id"),"",null,null));}
            else{if(row.has("tool_call_id"))throw reject("tool_id_wrong_role");if(row.has("content")&&!row.isNull("content"))parts.addAll(textParts(row.get("content"),"text"));
                JSONArray calls=row.optJSONArray("tool_calls");if(row.has("tool_calls")&&calls==null)throw reject("invalid_tool_calls");
                for(int t=0;calls!=null&&t<calls.length();t++){JSONObject c=calls.getJSONObject(t);keys(c,Set.of("type","id","function"));if(!"function".equals(string(c,"type")))throw reject("hosted_tool_unsupported");JSONObject fn=c.getJSONObject("function");keys(fn,Set.of("name","arguments"));parts.add(new Part(Kind.TOOL_CALL,"",identity(c,"id"),name(fn),jsonObject(string(fn,"arguments")),null));}}
            out.add(new Message(role,i,List.copyOf(parts)));
        }
        JSONArray definitions=raw.optJSONArray("tools");if(raw.has("tools")&&definitions==null)throw reject("invalid_tools");
        for(int i=0;definitions!=null&&i<definitions.length();i++){JSONObject t=definitions.getJSONObject(i);keys(t,Set.of("type","function"));if(!"function".equals(string(t,"type")))throw reject("hosted_tool_unsupported");tools.add(tool(t.getJSONObject("function"),"parameters",Set.of("name","description","parameters","strict")));}
    }
    private static void messages(JSONObject raw,List<Message> out,List<Tool> tools)throws Exception {
        if(raw.has("system"))out.add(new Message("system",out.size(),textParts(raw.get("system"),"text")));
        JSONArray rows=array(raw.get("messages"));for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);keys(row,Set.of("role","content"));String role=string(row,"role");if(!Set.of("user","assistant").contains(role))throw reject("role_unsupported");
            List<Part> parts=new ArrayList<>();Object content=row.get("content");if(content instanceof String)parts.add(new Part(Kind.TEXT,(String)content,"","",null,null));
            else{JSONArray blocks=array(content);for(int j=0;j<blocks.length();j++){JSONObject p=blocks.getJSONObject(j);String type=string(p,"type");switch(type){
                case "text":keys(p,Set.of("type","text"));parts.add(new Part(Kind.TEXT,string(p,"text"),"","",null,null));break;
                case "tool_use":keys(p,Set.of("type","id","name","input"));parts.add(new Part(Kind.TOOL_CALL,"",identity(p,"id"),name(p),copy(p.getJSONObject("input")),null));break;
                case "tool_result":keys(p,Set.of("type","tool_use_id","content","is_error"));Boolean error=p.has("is_error")?bool(p,"is_error",false):null;parts.add(new Part(Kind.TOOL_RESULT,textContent(p.get("content"),"text"),identity(p,"tool_use_id"),"",null,error));break;
                default:throw reject("content_unsupported_"+type);
            }}}
            out.add(new Message(role,out.size(),List.copyOf(parts)));
        }
        JSONArray defs=raw.optJSONArray("tools");if(raw.has("tools")&&defs==null)throw reject("invalid_tools");for(int i=0;defs!=null&&i<defs.length();i++)tools.add(tool(defs.getJSONObject(i),"input_schema",Set.of("name","description","input_schema","strict")));
    }
    private static void responsesInput(JSONObject raw,List<Message> out,List<Tool> tools)throws Exception {
        if(raw.has("instructions"))out.add(new Message("system",out.size(),List.of(new Part(Kind.TEXT,string(raw,"instructions"),"","",null,null))));
        Object input=raw.get("input");if(input instanceof String)out.add(new Message("user",out.size(),List.of(new Part(Kind.TEXT,(String)input,"","",null,null))));
        else{JSONArray rows=array(input);for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);String type=r.optString("type","message");
            if(type.equals("message")){keys(r,Set.of("type","role","content"));String role=string(r,"role");if(!Set.of("system","developer","user","assistant").contains(role))throw reject("role_unsupported");out.add(new Message(role,out.size(),textParts(r.get("content"),role.equals("assistant")?"output_text":"input_text")));}
            else if(type.equals("function_call")){keys(r,Set.of("type","call_id","name","arguments"));out.add(new Message("assistant",out.size(),List.of(new Part(Kind.TOOL_CALL,"",identity(r,"call_id"),name(r),jsonObject(string(r,"arguments")),null))));}
            else if(type.equals("function_call_output")){keys(r,Set.of("type","call_id","output"));out.add(new Message("tool",out.size(),List.of(new Part(Kind.TOOL_RESULT,string(r,"output"),identity(r,"call_id"),"",null,null))));}
            else throw reject("input_item_unsupported_"+type);
        }}
        JSONArray defs=raw.optJSONArray("tools");if(raw.has("tools")&&defs==null)throw reject("invalid_tools");for(int i=0;defs!=null&&i<defs.length();i++){JSONObject t=defs.getJSONObject(i);if(!"function".equals(string(t,"type")))throw reject("hosted_tool_unsupported");tools.add(tool(t,"parameters",Set.of("type","name","description","parameters","strict")));}
        if(raw.has("store")&&bool(raw,"store",false))throw reject("native_state_not_bound");
    }
    private static void gemini(JSONObject raw,List<Message> out,List<Tool> tools)throws Exception {
        if(raw.has("systemInstruction")){JSONObject si=raw.getJSONObject("systemInstruction");keys(si,Set.of("parts"));out.add(new Message("system",out.size(),geminiParts(si.getJSONArray("parts"))));}
        JSONArray rows=array(raw.get("contents"));for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);keys(row,Set.of("role","parts"));String role=row.optString("role","user");if(!Set.of("user","model").contains(role))throw reject("role_unsupported");out.add(new Message(role.equals("model")?"assistant":"user",out.size(),geminiParts(row.getJSONArray("parts"))));}
        JSONArray groups=raw.optJSONArray("tools");if(raw.has("tools")&&groups==null)throw reject("invalid_tools");for(int i=0;groups!=null&&i<groups.length();i++){JSONObject group=groups.getJSONObject(i);keys(group,Set.of("functionDeclarations"));JSONArray defs=array(group.get("functionDeclarations"));for(int j=0;j<defs.length();j++)tools.add(tool(defs.getJSONObject(j),"parameters",Set.of("name","description","parameters")));}
        if(raw.has("generationConfig"))keys(raw.getJSONObject("generationConfig"),Set.of("maxOutputTokens","temperature","topP","stopSequences","candidateCount"));
        if(raw.has("toolConfig"))throw reject("gemini_tool_config_not_supported");
    }
    private static List<Part> geminiParts(JSONArray rows)throws Exception {
        List<Part> out=new ArrayList<>();for(int i=0;i<rows.length();i++){JSONObject p=rows.getJSONObject(i);keys(p,Set.of("text","functionCall","functionResponse"));if(p.length()!=1)throw reject("ambiguous_part");
            if(p.has("text"))out.add(new Part(Kind.TEXT,string(p,"text"),"","",null,null));
            else if(p.has("functionCall")){JSONObject f=p.getJSONObject("functionCall");keys(f,Set.of("id","name","args"));out.add(new Part(Kind.TOOL_CALL,"",identity(f,"id"),name(f),copy(f.getJSONObject("args")),null));}
            else{JSONObject f=p.getJSONObject("functionResponse");keys(f,Set.of("id","name","response"));out.add(new Part(Kind.TOOL_RESULT,f.getJSONObject("response").toString(),identity(f,"id"),name(f),null,null));}}
        return List.copyOf(out);
    }
    private static JSONObject anthropic(Canonical request)throws Exception {
        JSONArray messages=new JSONArray();JSONObject wire=new JSONObject();boolean history=false;
        for(Message m:request.messages){
            if(m.role.equals("developer"))throw reject("developer_role_not_representable");
            if(m.role.equals("system")){if(history||wire.has("system"))throw reject("instruction_position_not_representable");wire.put("system",anthropicParts(m.parts));continue;}
            history=true;messages.put(new JSONObject().put("role",m.role.equals("tool")?"user":m.role).put("content",anthropicParts(m.parts)));
        }
        if(messages.length()==0||!messages.getJSONObject(messages.length()-1).getString("role").equals("user"))throw reject("assistant_prefill_semantics");
        wire.put("messages",messages);JSONArray tools=new JSONArray();for(Tool t:request.tools){if(t.strict==Strict.TRUE)throw reject("strict_schema_capability_unverified");JSONObject fn=new JSONObject().put("name",t.name).put("description",t.description).put("input_schema",t.schema);if(t.strict==Strict.FALSE)fn.put("strict",false);tools.put(fn);}if(tools.length()>0)wire.put("tools",tools);return wire;
    }
    private static JSONArray anthropicParts(List<Part> parts)throws Exception {
        JSONArray out=new JSONArray();for(Part p:parts)switch(p.kind){
            case TEXT:out.put(new JSONObject().put("type","text").put("text",p.text));break;
            case TOOL_CALL:out.put(new JSONObject().put("type","tool_use").put("id",p.id).put("name",p.name).put("input",p.arguments));break;
            case TOOL_RESULT:JSONObject result=new JSONObject().put("type","tool_result").put("tool_use_id",p.id).put("content",p.text);if(p.isError!=null)result.put("is_error",p.isError);out.put(result);break;
        }return out;
    }
    private static JSONObject responses(Canonical request)throws Exception {
        JSONArray input=new JSONArray();for(Message m:request.messages){JSONArray text=new JSONArray();for(Part p:m.parts){
            if(p.kind==Kind.TEXT){text.put(new JSONObject().put("type",m.role.equals("assistant")?"output_text":"input_text").put("text",p.text));continue;}
            flush(input,m.role,text);text=new JSONArray();
            if(p.kind==Kind.TOOL_CALL)input.put(new JSONObject().put("type","function_call").put("call_id",p.id).put("name",p.name).put("arguments",p.arguments.toString()));
            else{if(Boolean.TRUE.equals(p.isError))throw reject("error_tool_result_not_representable");input.put(new JSONObject().put("type","function_call_output").put("call_id",p.id).put("output",p.text));}
        }flush(input,m.role,text);}
        if(request.messages.get(request.messages.size()-1).role.equals("assistant"))throw reject("assistant_prefill_semantics");
        JSONArray tools=new JSONArray();for(Tool t:request.tools){if(t.strict==Strict.TRUE)throw reject("strict_schema_capability_unverified");
            // Responses otherwise defaults to strict normalization. Source UNSPECIFIED remains in
            // the canonical Tool, but its non-strict Messages semantics require explicit false.
            tools.put(new JSONObject().put("type","function").put("name",t.name).put("description",t.description).put("parameters",t.schema).put("strict",false));}
        JSONObject wire=new JSONObject().put("input",input);if(tools.length()>0)wire.put("tools",tools);return wire;
    }
    private static void flush(JSONArray input,String role,JSONArray text)throws Exception{if(text.length()>0)input.put(new JSONObject().put("type","message").put("role",role).put("content",text));}
    private static Object choice(String from,String to,Object value)throws Exception {
        if(from.equals("openai_chat")){String kind;if(value instanceof String)kind=(String)value;else{JSONObject c=(JSONObject)value;keys(c,Set.of("type","function"));if(!"function".equals(string(c,"type")))throw reject("tool_choice_unsupported");JSONObject fn=c.getJSONObject("function");keys(fn,Set.of("name"));return new JSONObject().put("type","tool").put("name",name(fn));}
            if(!Set.of("auto","none","required").contains(kind))throw reject("tool_choice_unsupported");return new JSONObject().put("type",kind.equals("required")?"any":kind);}
        JSONObject c=(JSONObject)value;keys(c,Set.of("type","name","disable_parallel_tool_use"));String kind=string(c,"type");if(kind.equals("tool"))return new JSONObject().put("type","function").put("name",name(c));if(!Set.of("auto","none","any").contains(kind))throw reject("tool_choice_unsupported");return kind.equals("any")?"required":kind;
    }
    private static JSONArray stops(Object value)throws Exception {JSONArray result=value instanceof String?new JSONArray().put(value):array(value);if(result.length()>4)throw reject("stop_count");for(int i=0;i<result.length();i++)if(!(result.get(i) instanceof String))throw reject("stop_type");return result;}
    private static JSONArray internalTools(Canonical c)throws Exception{JSONArray out=new JSONArray();for(Tool t:c.tools){JSONObject f=new JSONObject().put("name",t.name).put("description",t.description).put("parameters",t.schema);if(t.strict!=Strict.UNSPECIFIED)f.put("strict",t.strict==Strict.TRUE);out.put(new JSONObject().put("type","function").put("function",f));}return out;}
    private static JSONArray budgetMessages(Canonical c,JSONObject wire)throws Exception{JSONArray out=new JSONArray();for(Message m:c.messages){for(Part p:m.parts){if(p.kind==Kind.TOOL_CALL)out.put(new JSONObject().put("role","assistant").put("content","").put("tool_calls",new JSONArray().put(new JSONObject().put("type","function").put("id",p.id).put("function",new JSONObject().put("name",p.name).put("arguments",p.arguments.toString())))));else if(p.kind==Kind.TOOL_RESULT)out.put(new JSONObject().put("role","tool").put("tool_call_id",p.id).put("content",p.text));else out.put(new JSONObject().put("role",m.role).put("content",p.text));}}out.put(new JSONObject().put("role","user").put("content",wire.toString()));return out;}
    public static void rejectOpaque(Object value)throws Exception {
        // Client-asserted origin cannot bind vendor-native history. These require an existing
        // protected receipt/reference store; until that exists, same-protocol input also rejects.
        if(value instanceof JSONObject){JSONObject j=(JSONObject)value;String type=j.optString("type");if(Set.of("thinking","redacted_thinking","reasoning","item_reference","input_file","file","document").contains(type))throw reject("opaque_origin_unknown");
            for(Iterator<String> it=j.keys();it.hasNext();){String key=it.next();if(Set.of("previous_response_id","conversation","file_id","cachedContent","cache_control","encrypted_content","signature","thoughtSignature","provider_data").contains(key))throw reject("opaque_origin_unknown");
                if(!Set.of("parameters","parametersJsonSchema","input_schema","schema","arguments","args","input","response").contains(key))rejectOpaque(j.get(key));
                else if(key.equals("input")&&j.get(key) instanceof JSONArray)rejectOpaque(j.get(key));}}
        else if(value instanceof JSONArray){JSONArray a=(JSONArray)value;for(int i=0;i<a.length();i++)rejectOpaque(a.get(i));}
    }
    public static Strict strict(JSONObject object)throws Exception {if(!object.has("strict"))return Strict.UNSPECIFIED;return bool(object,"strict",false)?Strict.TRUE:Strict.FALSE;}
    private static Tool tool(JSONObject t,String schema,Set<String> fields)throws Exception{keys(t,fields);return new Tool(name(t),t.has("description")?string(t,"description"):"",copy(t.getJSONObject(schema)),strict(t));}
    private static List<Part> textParts(Object content,String kind)throws Exception{List<Part> result=new ArrayList<>();if(content instanceof String)result.add(new Part(Kind.TEXT,(String)content,"","",null,null));else{JSONArray a=array(content);for(int i=0;i<a.length();i++){JSONObject p=a.getJSONObject(i);keys(p,Set.of("type","text"));if(!kind.equals(string(p,"type")))throw reject("media_or_content_unsupported");result.add(new Part(Kind.TEXT,string(p,"text"),"","",null,null));}}return List.copyOf(result);}
    private static String textContent(Object content,String kind)throws Exception{if(!(content instanceof String))throw reject("tool_result_block_boundaries_unsupported");return (String)content;}
    private static JSONArray array(Object value)throws Exception{if(!(value instanceof JSONArray)||((JSONArray)value).length()<1||((JSONArray)value).length()>128)throw reject("array_bound");return (JSONArray)value;}
    private static void keys(JSONObject j,Set<String> allowed)throws Exception{for(Iterator<String> it=j.keys();it.hasNext();)if(!allowed.contains(it.next()))throw reject("unknown_semantic_field");}
    private static String string(JSONObject j,String key)throws Exception{Object value=j.get(key);if(!(value instanceof String))throw reject("string_required");return (String)value;}
    private static String identity(JSONObject j,String key)throws Exception{String value=string(j,key);if(!value.matches("[A-Za-z0-9_.:-]{1,256}"))throw reject("invalid_identity");return value;}
    private static String name(JSONObject j)throws Exception{String value=string(j,"name");if(!value.matches("[A-Za-z0-9_-]{1,64}"))throw reject("invalid_tool_name");return value;}
    private static boolean bool(JSONObject j,String key,boolean absent)throws Exception{if(!j.has(key))return absent;Object value=j.get(key);if(!(value instanceof Boolean))throw reject("boolean_required");return (Boolean)value;}
    private static int integer(Object v,int min,int max)throws Exception{if(!(v instanceof Integer||v instanceof Long)||((Number)v).longValue()<min||((Number)v).longValue()>max)throw reject("output_limit");return ((Number)v).intValue();}
    static JSONObject jsonObject(String value)throws Exception{if(value.length()>64*1024)throw reject("tool_arguments_too_large");return ProtocolJson.parse(value);}
    private static JSONObject copy(JSONObject value)throws Exception{return new JSONObject(value.toString());}
    private static Rejected reject(String code){return new Rejected(code);}
    private ProtocolCodec(){}
}
