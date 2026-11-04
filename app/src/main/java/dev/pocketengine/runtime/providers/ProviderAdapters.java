package dev.pocketengine.runtime.providers;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.runtime.ModelGateway.GatewayException;
import dev.pocketengine.runtime.providers.bedrock.BedrockEndpoint;
import dev.pocketengine.runtime.providers.bedrock.BedrockSigV4;
import java.net.URI;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Independently authored stateless codecs for each provider's documented native wire format. */
public final class ProviderAdapters {
    private ProviderAdapters(){}
    public static ProviderAdapter selected(String provider)throws GatewayException {
        switch(provider){case "openai_chat":return new Chat();case "openai_responses":return new Responses();case "anthropic":return new Anthropic();case "bedrock_anthropic":return new BedrockAnthropic();case "gemini":return new Gemini();default:throw error("不支持的模型协议");}
    }
    private abstract static class Base implements ProviderAdapter {
        final String id;Base(String id){this.id=id;}
        public Map<String,String> headers(ModelConfig config){Map<String,String> headers=new HashMap<>();if(!config.apiKey.isEmpty())headers.put("Authorization","Bearer "+config.apiKey);return headers;}
        JSONArray nativeItems(JSONObject message)throws Exception {JSONObject metadata=message.optJSONObject("provider_data");return metadata!=null&&id.equals(metadata.optString("provider"))?metadata.optJSONArray("items"):null;}
        JSONObject normalized(String text,JSONArray calls,JSONArray nativeItems)throws Exception {JSONObject out=new JSONObject().put("role","assistant").put("content",text);if(calls.length()>0)out.put("tool_calls",calls);if(nativeItems!=null)out.put("provider_data",new JSONObject().put("provider",id).put("items",copy(nativeItems)));return out;}
    }
    private static final class Chat extends Base {
        Chat(){super("openai_chat");}
        public URL endpoint(ModelConfig config)throws Exception{return route(config.baseUrl,"/chat/completions");}
        public JSONObject request(ModelConfig config,JSONArray messages,JSONArray tools)throws Exception {
            JSONArray clean=copy(messages);for(int i=0;i<clean.length();i++){JSONObject message=clean.getJSONObject(i);message.remove("provider_data");JSONArray calls=message.optJSONArray("tool_calls");if(calls!=null)for(int j=0;j<calls.length();j++)calls.getJSONObject(j).remove("provider_data");}
            JSONObject body=new JSONObject().put("model",config.model).put("messages",clean).put("stream",false);if(tools!=null&&tools.length()>0)body.put("tools",tools).put("tool_choice","auto");return body;
        }
        public JSONObject response(JSONObject wire)throws Exception {
            JSONArray choices=wire.optJSONArray("choices");JSONObject choice=choices==null?null:choices.optJSONObject(0);JSONObject message=choice==null?null:choice.optJSONObject("message");if(message==null)throw error("接口没有返回Chat Completions消息，请检查所选协议");if(!message.optString("refusal").isEmpty())throw error("模型拒绝执行此请求");if("length".equals(choice.optString("finish_reason")))throw error("模型回复被截断，请调整输出限制");return message;
        }
    }
    private static final class Responses extends Base {
        Responses(){super("openai_responses");}
        public URL endpoint(ModelConfig config)throws Exception{return route(config.baseUrl,"/responses");}
        public JSONObject request(ModelConfig config,JSONArray messages,JSONArray tools)throws Exception {
            JSONArray input=new JSONArray();
            for(int i=0;i<messages.length();i++){JSONObject message=messages.getJSONObject(i);String role=message.getString("role");JSONArray raw=nativeItems(message);
                if("assistant".equals(role)&&raw!=null){for(int j=0;j<raw.length();j++)input.put(new JSONObject(raw.getJSONObject(j).toString()));continue;}
                if("tool".equals(role)){input.put(new JSONObject().put("type","function_call_output").put("call_id",message.getString("tool_call_id")).put("output",message.getString("content")));continue;}
                JSONArray content=parts(message.opt("content"),"responses",role);if(content.length()>0)input.put(new JSONObject().put("role",role).put("content",role.equals("assistant")?assistantText(message.opt("content")):content));JSONArray calls=message.optJSONArray("tool_calls");if(calls!=null)for(int j=0;j<calls.length();j++){JSONObject call=calls.getJSONObject(j),fn=call.getJSONObject("function");input.put(new JSONObject().put("type","function_call").put("call_id",call.getString("id")).put("name",fn.getString("name")).put("arguments",fn.getString("arguments")));}
            }
            JSONObject body=new JSONObject().put("model",config.model).put("input",input).put("store",false).put("stream",false).put("max_output_tokens",config.maxOutputTokens);
            if(tools!=null&&tools.length()>0){JSONArray converted=new JSONArray();for(int i=0;i<tools.length();i++){JSONObject fn=definition(tools.getJSONObject(i));converted.put(new JSONObject().put("type","function").put("name",fn.getString("name")).put("description",fn.optString("description")).put("parameters",fn.getJSONObject("parameters")).put("strict",false));}body.put("tools",converted).put("tool_choice","auto");}return body;
        }
        public JSONObject response(JSONObject wire)throws Exception {
            String status=wire.optString("status");if(wire.has("error")&&!wire.isNull("error")||!status.isEmpty()&&!"completed".equals(status))throw error("Responses未返回完整终态，请检查输出预算和服务状态");JSONArray output=wire.optJSONArray("output");if(output==null)throw error("接口没有返回Responses output数组");JSONArray calls=new JSONArray();StringBuilder text=new StringBuilder();
            for(int i=0;i<output.length();i++){JSONObject item=output.getJSONObject(i);String type=item.getString("type");switch(type){case "function_call":if(!item.optString("status").isEmpty()&&!"completed".equals(item.optString("status")))throw error("Responses工具参数尚未完整，未派发动作");calls.put(call(item.getString("call_id"),item.getString("name"),item.getString("arguments")));break;case "reasoning":break;case "message":if(!"assistant".equals(item.optString("role","assistant")))throw error("Responses输出消息角色无效");JSONArray content=item.getJSONArray("content");for(int j=0;j<content.length();j++){JSONObject part=content.getJSONObject(j);if("refusal".equals(part.optString("type")))throw error("模型拒绝执行此请求");if("output_text".equals(part.optString("type")))text.append(part.getString("text"));else throw error("Responses返回了尚不支持的消息内容");}break;default:throw error("Responses返回了未授权的原生工具或未知输出类型");}}
            return normalized(text.toString(),calls,output);
        }
    }
    private static class Anthropic extends Base {
        Anthropic(){this("anthropic");}
        Anthropic(String provider){super(provider);}
        public URL endpoint(ModelConfig config)throws Exception{return route(config.baseUrl,"/messages");}
        @Override public Map<String,String> headers(ModelConfig config){Map<String,String> headers=super.headers(config);headers.put("anthropic-version","2026-10-21");return headers;}
        public JSONObject request(ModelConfig config,JSONArray messages,JSONArray tools)throws Exception {
            JSONArray conversation=new JSONArray(),system=new JSONArray();
            for(int i=0;i<messages.length();i++){JSONObject message=messages.getJSONObject(i);String role=message.getString("role");if(role.equals("system")||role.equals("developer")){append(system,parts(message.opt("content"),"anthropic","system"));continue;}if(role.equals("tool")){push(conversation,"user",new JSONArray().put(new JSONObject().put("type","tool_result").put("tool_use_id",message.getString("tool_call_id")).put("content",message.getString("content"))));continue;}
                JSONArray raw=nativeItems(message);JSONArray content=raw!=null?copy(raw):parts(message.opt("content"),"anthropic",role);if(raw==null){JSONArray calls=message.optJSONArray("tool_calls");if(calls!=null)for(int j=0;j<calls.length();j++){JSONObject call=calls.getJSONObject(j),fn=call.getJSONObject("function");content.put(new JSONObject().put("type","tool_use").put("id",call.getString("id")).put("name",fn.getString("name")).put("input",new JSONObject(fn.getString("arguments"))));}}if(content.length()>0)push(conversation,role,content);
            }
            JSONObject body=new JSONObject().put("model",config.model).put("messages",conversation).put("max_tokens",config.maxOutputTokens).put("stream",false);if(system.length()>0)body.put("system",system);
            if(tools!=null&&tools.length()>0){JSONArray definitions=new JSONArray();for(int i=0;i<tools.length();i++){JSONObject fn=definition(tools.getJSONObject(i));definitions.put(new JSONObject().put("name",fn.getString("name")).put("description",fn.optString("description")).put("input_schema",fn.getJSONObject("parameters")));}body.put("tools",definitions).put("tool_choice",new JSONObject().put("type","auto"));}return body;
        }
        public JSONObject response(JSONObject wire)throws Exception {
            if("error".equals(wire.optString("type")))throw error("Claude服务返回错误，请检查认证或模型设置");String stop=wire.optString("stop_reason");if(!stop.isEmpty()&&!java.util.Set.of("end_turn","tool_use","stop_sequence").contains(stop))throw error("Claude回复未完成、拒绝或需要后续处理，未派发其中动作");JSONArray content=wire.optJSONArray("content");if(content==null)throw error("接口没有返回Claude Messages内容");StringBuilder text=new StringBuilder();JSONArray calls=new JSONArray();
            for(int i=0;i<content.length();i++){JSONObject block=content.getJSONObject(i);switch(block.getString("type")){case "text":text.append(block.getString("text"));break;case "tool_use":calls.put(call(block.getString("id"),block.getString("name"),block.getJSONObject("input").toString()));break;case "thinking":case "redacted_thinking":break;default:throw error("Claude返回了尚未接入的内容或服务端工具，未派发动作");}}return normalized(text.toString(),calls,content);
        }
    }
    private static final class BedrockAnthropic extends Anthropic {
        BedrockAnthropic(){super("bedrock_anthropic");}
        @Override public URL endpoint(ModelConfig config)throws Exception {
            if(!config.usesBedrock())throw error("Bedrock请求必须使用原生Bedrock连接");
            return BedrockEndpoint.endpoint(config.bedrockRegion,config.model,false);
        }
        @Override public JSONObject request(ModelConfig config,JSONArray messages,JSONArray tools)throws Exception {
            if(!config.usesBedrock())throw error("Bedrock请求必须使用原生Bedrock连接");
            BedrockEndpoint.validate(config.bedrockRegion,config.model);
            JSONObject body=super.request(config,messages,tools);
            body.remove("model");body.remove("stream");
            return body.put("anthropic_version","bedrock-2023-05-31");
        }
        @Override public Map<String,String> headers(ModelConfig config){
            throw new SecurityException("bedrock_requires_exact_final_request_bytes");
        }
        @Override public Map<String,String> headers(ModelConfig config,URL endpoint,byte[] payload)throws Exception {
            if(!config.usesBedrock())throw error("Bedrock签名必须使用原生Bedrock连接");
            return BedrockSigV4.headers(config.bedrockRegion,config.model,config.awsSession,endpoint,payload,System.currentTimeMillis());
        }
    }
    private static final class Gemini extends Base {
        Gemini(){super("gemini");}
        public URL endpoint(ModelConfig config)throws Exception {
            URI base=base(config.baseUrl);String path=base.getPath()==null?"":base.getPath().replaceAll("/+$","");String model=config.model.startsWith("models/")?config.model.substring(7):config.model;if(!model.matches("[A-Za-z0-9._-]{1,200}"))throw error("Gemini模型名称无效；当前适配公开GenerateContent API");if(path.isEmpty())path="/v1beta";if(!path.endsWith(":generateContent")){if(path.contains("/models/"))throw error("请填写Gemini API根地址或完整generateContent地址");path+="/models/"+model+":generateContent";}return new URI(base.getScheme(),null,base.getHost(),base.getPort(),path,null,null).toURL();
        }
        @Override public Map<String,String> headers(ModelConfig config){Map<String,String> headers=new HashMap<>();if(!config.apiKey.isEmpty())headers.put("x-goog-api-key",config.apiKey);return headers;}
        public JSONObject request(ModelConfig config,JSONArray messages,JSONArray tools)throws Exception {
            JSONArray contents=new JSONArray(),system=new JSONArray();Map<String,JSONObject> callNames=new HashMap<>();
            for(int i=0;i<messages.length();i++){JSONObject message=messages.getJSONObject(i);String role=message.getString("role");if(role.equals("system")||role.equals("developer")){append(system,parts(message.opt("content"),"gemini","system"));continue;}
                if(role.equals("tool")){JSONObject info=callNames.get(message.getString("tool_call_id"));if(info==null)throw error("Gemini历史工具结果缺少对应调用身份");JSONObject result;try{result=new JSONObject(message.getString("content"));}catch(Exception nonObject){result=new JSONObject().put("result",message.getString("content"));}JSONObject output=new JSONObject().put("name",info.getString("name")).put("response",result);if(!info.optString("wireId").isEmpty())output.put("id",info.getString("wireId"));push(contents,"user",new JSONArray().put(new JSONObject().put("functionResponse",output)),"parts");continue;}
                JSONArray raw=nativeItems(message),content=raw==null?parts(message.opt("content"),"gemini",role):copy(raw),calls=message.optJSONArray("tool_calls");
                if(calls!=null){int cursor=0;for(int j=0;j<calls.length();j++){JSONObject call=calls.getJSONObject(j),fn=call.getJSONObject("function");String wireId="";if(raw!=null){while(cursor<raw.length()){JSONObject part=raw.getJSONObject(cursor++),nativeCall=part.optJSONObject("functionCall");if(nativeCall!=null){wireId=nativeCall.optString("id");break;}}}else{content.put(new JSONObject().put("functionCall",new JSONObject().put("name",fn.getString("name")).put("args",new JSONObject(fn.getString("arguments")))));}callNames.put(call.getString("id"),new JSONObject().put("name",fn.getString("name")).put("wireId",wireId));}}
                if(content.length()>0)push(contents,role.equals("assistant")?"model":"user",content,"parts");
            }
            JSONObject body=new JSONObject().put("contents",contents).put("generationConfig",new JSONObject().put("maxOutputTokens",config.maxOutputTokens));if(system.length()>0)body.put("systemInstruction",new JSONObject().put("parts",system));if(tools!=null&&tools.length()>0){JSONArray definitions=new JSONArray();for(int i=0;i<tools.length();i++){JSONObject fn=definition(tools.getJSONObject(i));definitions.put(new JSONObject().put("name",fn.getString("name")).put("description",fn.optString("description")).put("parametersJsonSchema",fn.getJSONObject("parameters")));}body.put("tools",new JSONArray().put(new JSONObject().put("functionDeclarations",definitions))).put("toolConfig",new JSONObject().put("functionCallingConfig",new JSONObject().put("mode","AUTO")));}return body;
        }
        public JSONObject response(JSONObject wire)throws Exception {
            if(wire.has("error"))throw error("Gemini服务返回错误，请检查模型设置");JSONObject feedback=wire.optJSONObject("promptFeedback");if(feedback!=null&&!feedback.optString("blockReason").isEmpty())throw error("Gemini拒绝或阻止了此请求");JSONArray candidates=wire.optJSONArray("candidates");JSONObject first=candidates==null?null:candidates.optJSONObject(0);if(first==null)throw error("Gemini没有返回候选内容");String finish=first.optString("finishReason");if(!finish.isEmpty()&&!finish.equals("STOP"))throw error("Gemini回复被截断或未正常结束，未派发动作");JSONObject content=first.optJSONObject("content");JSONArray parts=content==null?null:content.optJSONArray("parts");if(parts==null)throw error("Gemini没有返回内容parts");StringBuilder text=new StringBuilder();JSONArray calls=new JSONArray();for(int i=0;i<parts.length();i++){JSONObject part=parts.getJSONObject(i);JSONObject fn=part.optJSONObject("functionCall");if(fn!=null){String id=fn.optString("id");if(id.isEmpty())id="gemini_"+UUID.randomUUID();if(fn.has("args")&&fn.optJSONObject("args")==null)throw error("Gemini工具参数必须是JSON对象，未派发动作");calls.put(call(id,fn.getString("name"),fn.optJSONObject("args")==null?"{}":fn.getJSONObject("args").toString()));}else if(part.has("text")){if(!part.optBoolean("thought"))text.append(part.getString("text"));}else throw error("Gemini返回了尚未接入的媒体或服务端工具");}return normalized(text.toString(),calls,parts);
        }
    }
    private static JSONObject call(String id,String name,String arguments)throws Exception{if(id.isEmpty()||name.isEmpty())throw error("模型工具调用缺少身份或名称");return new JSONObject().put("id",id).put("type","function").put("function",new JSONObject().put("name",name).put("arguments",arguments));}
    private static JSONObject definition(JSONObject tool)throws Exception{if(!"function".equals(tool.optString("type"))||tool.optJSONObject("function")==null)throw error("该连接仅支持宿主客户端function工具");return tool.getJSONObject("function");}
    private static JSONArray parts(Object content,String provider,String role)throws Exception {
        JSONArray result=new JSONArray();if(content==null||content==JSONObject.NULL)return result;if(content instanceof String){if(!((String)content).isEmpty())result.put(text((String)content,provider,role));return result;}if(!(content instanceof JSONArray))throw error("对话内容格式不支持");JSONArray source=(JSONArray)content;
        for(int i=0;i<source.length();i++){JSONObject part=source.getJSONObject(i);String type=part.getString("type");if(type.equals("text")){result.put(text(part.getString("text"),provider,role));continue;}if(!type.equals("image_url"))throw error("此连接尚未接入该附件类型");String url=part.getJSONObject("image_url").getString("url");if(provider.equals("responses")){result.put(new JSONObject().put("type","input_image").put("image_url",url));continue;}int marker=url.indexOf(";base64,");if(!url.startsWith("data:image/")||marker<5)throw error("此连接只接受实际base64图片，不能把路径或图片ID当作图像");String mime=url.substring(5,marker),data=url.substring(marker+8);if(!java.util.Set.of("image/png","image/jpeg","image/webp","image/gif").contains(mime)||data.isEmpty()||!data.matches("[A-Za-z0-9+/=]+"))throw error("图片数据或类型无效");if(provider.equals("anthropic"))result.put(new JSONObject().put("type","image").put("source",new JSONObject().put("type","base64").put("media_type",mime).put("data",data)));else result.put(new JSONObject().put("inlineData",new JSONObject().put("mimeType",mime).put("data",data)));}return result;
    }
    private static String assistantText(Object content)throws Exception{if(content instanceof String)return (String)content;StringBuilder out=new StringBuilder();if(content instanceof JSONArray){JSONArray parts=(JSONArray)content;for(int i=0;i<parts.length();i++){JSONObject part=parts.getJSONObject(i);if(!"text".equals(part.optString("type")))throw error("历史assistant消息只能包含文字");out.append(part.getString("text"));}}return out.toString();}
    private static JSONObject text(String text,String provider,String role)throws Exception{return provider.equals("gemini")?new JSONObject().put("text",text):new JSONObject().put("type",provider.equals("responses")?(role.equals("assistant")?"output_text":"input_text"):"text").put("text",text);}
    private static void append(JSONArray into,JSONArray values)throws Exception{for(int i=0;i<values.length();i++)into.put(values.get(i));}
    private static void push(JSONArray messages,String role,JSONArray parts)throws Exception{push(messages,role,parts,"content");}
    private static void push(JSONArray messages,String role,JSONArray parts,String key)throws Exception{JSONObject previous=messages.length()>0?messages.getJSONObject(messages.length()-1):null;if(previous!=null&&role.equals(previous.optString("role")))append(previous.getJSONArray(key),parts);else messages.put(new JSONObject().put("role",role).put(key,parts));}
    private static JSONArray copy(JSONArray array)throws Exception{return new JSONArray(array.toString());}
    private static URI base(String url)throws Exception{try{URI uri=new URI(url==null?"":url.trim());if(!java.util.Set.of("https","http").contains(uri.getScheme()==null?"":uri.getScheme().toLowerCase(java.util.Locale.ROOT))||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||uri.getPort()==0||uri.getPort()>65535)throw new Exception();return uri;}catch(Exception invalid){throw error("模型地址无效，请填写http(s)接口根地址，密钥不能放在URL里");}}
    private static URL route(String url,String suffix)throws Exception{URI base=base(url);String path=base.getPath()==null?"":base.getPath().replaceAll("/+$","");if(path.isEmpty())path="/v1";if(!path.endsWith(suffix)){if(path.endsWith("/chat/completions")||path.endsWith("/responses")||path.endsWith("/messages")||path.endsWith(":generateContent"))throw error("完整接口地址与所选协议不一致，请修改连接类型或根地址");path+=suffix;}return new URI(base.getScheme(),null,base.getHost(),base.getPort(),path,null,null).toURL();}
    private static GatewayException error(String message){return new GatewayException(message+"。");}
}
