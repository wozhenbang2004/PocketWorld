package dev.pocketengine.features.proxy;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.runtime.models.ModelRequestPolicy;
import dev.pocketengine.runtime.models.ReasoningOptions;
import dev.pocketengine.runtime.providers.ApiTurnStream;
import dev.pocketengine.runtime.providers.ProviderAdapters;
import java.net.URL;
import java.util.*;
import org.json.*;

/** Native protocol in = same selected native protocol out. No account route or lossy translation. */
final class NativeRelayPolicy {
    static final class Rejected extends Exception { Rejected(String text){super(text);} }
    static final class Prepared {
        final ModelConfig config;final JSONObject wire;final JSONArray messages,tools;final URL endpoint;final boolean streaming,functionHistory;
        Prepared(ModelConfig config,JSONObject wire,JSONArray messages,JSONArray tools,URL endpoint,boolean streaming,boolean functionHistory){this.config=config;this.wire=wire;this.messages=messages;this.tools=tools;this.endpoint=endpoint;this.streaming=streaming;this.functionHistory=functionHistory;}
        void revalidate()throws Exception{ModelRequestPolicy.applyNative(config,messages,tools,new JSONObject(wire.toString()),functionHistory);}
        void validateResult(JSONObject result,dev.pocketengine.runtime.usage.ProviderUsage.Observer usage)throws Exception{ApiTurnStream.completeJson(config.provider,ProviderAdapters.selected(config.provider),result.toString(),tools,delta->{},usage);}
    }
    static JSONArray paths(ModelConfig config)throws Exception{
        return switch(config.provider){
            case "openai_chat"->new JSONArray().put("/v1/chat/completions");
            case "openai_responses"->new JSONArray().put("/v1/responses");
            case "anthropic"->new JSONArray().put("/v1/messages");
            case "gemini"->new JSONArray().put(geminiPath(config,false)).put(geminiPath(config,true));
            default->new JSONArray();
        };
    }
    static String protocol(ModelConfig config){return config.usesAccount()?"unavailable":config.provider;}
    static boolean acceptsPath(String path){return path.equals("/v1/chat/completions")||path.equals("/v1/responses")||path.equals("/v1/messages")||path.startsWith("/v1beta/models/");}
    static Prepared prepare(ModelConfig selected,String path,JSONObject client)throws Exception{
        if(selected.usesAccount()||selected.provider.equals("openai_chat"))throw new Rejected("此入口需要已明确选择的同协议 API-key 连接");
        boolean stream;
        if(selected.provider.equals("gemini")){
            if(path.equals(geminiPath(selected,true)))stream=true;
            else if(path.equals(geminiPath(selected,false)))stream=false;
            else throw new Rejected("本机 Gemini 服务只提供已选模型的 generateContent / streamGenerateContent 路径");
            if(client.has("stream")||client.has("model"))throw new Rejected("Gemini 模型与流式方式由已选模型路径确定，不能在正文代换");
        }else{
            if(!path.equals(selected.provider.equals("anthropic")?"/v1/messages":"/v1/responses"))throw new Rejected("请求协议与本机已固定的 API-key 连接不一致，没有转换或切换连接");
            if(client.has("stream")&&!(client.get("stream") instanceof Boolean))throw new Rejected("stream 需要布尔值");stream=client.optBoolean("stream",false);
            if(client.has("model")&&(!(client.get("model") instanceof String)||!selected.model.equals(client.getString("model"))))throw new Rejected("请求模型不是本机服务已选模型，没有代换");
        }
        JSONObject wire=new JSONObject(client.toString());if(!selected.provider.equals("gemini"))wire.put("model",selected.model).put("stream",stream);
        JSONArray messages=new JSONArray(),tools=new JSONArray();ModelConfig request=selected;boolean functionHistory=false;
        switch(selected.provider){
            case "openai_responses":{
                keys(wire,"model","input","instructions","tools","tool_choice","parallel_tool_calls","max_output_tokens","reasoning","temperature","top_p","text","metadata","store","stream","service_tier","user","truncation");
                if(wire.has("store")&&!Boolean.FALSE.equals(wire.get("store")))throw new Rejected("本机 Responses 仅接显式无服务端持久化的单次请求，store 只能为 false");wire.put("store",false);
                if(wire.has("truncation")&&!"disabled".equals(wire.get("truncation")))throw new Rejected("不能由上游静默裁剪本次输入；truncation 只能为 disabled");
                request=limit(selected,wire,"max_output_tokens");
                if(wire.has("instructions")){string(wire,"instructions");messages.put(message("system",wire.getString("instructions")));}
                Object input=wire.opt("input");if(input instanceof String)messages.put(message("user",input));else{
                    JSONArray items=array(input,"input",1,100);
                    for(int i=0;i<items.length();i++){
                        JSONObject item=items.getJSONObject(i);String type=item.optString("type","message");
                        switch(type){
                            case "message":messages.put(message(role(item,"role",Set.of("system","developer","user","assistant")),responseParts(item.get("content"))));break;
                            case "function_call":messages.put(new JSONObject().put("role","assistant").put("content","").put("tool_calls",new JSONArray().put(new JSONObject().put("id",item.getString("call_id")).put("type","function").put("function",new JSONObject().put("name",name(item,"name")).put("arguments",item.getString("arguments"))))));break;
                            case "function_call_output":messages.put(new JSONObject().put("role","tool").put("tool_call_id",item.getString("call_id")).put("content",item.get("output").toString()));break;
                            case "reasoning":messages.put(message("assistant",item.toString()));break;
                            default:throw new Rejected("当前 Responses relay 接文字、图像和客户端函数，不接未知输入/托管工具");
                        }
                    }
                }
                JSONArray nativeTools=wire.optJSONArray("tools");if(wire.has("tools"))array(nativeTools,"tools",0,64);
                for(int i=0;nativeTools!=null&&i<nativeTools.length();i++){JSONObject fn=nativeTools.getJSONObject(i);if(!"function".equals(fn.optString("type")))throw new Rejected("relay 仅接客户端 function 声明，不执行本机或托管工具");tools.put(function(name(fn,"name"),fn.optString("description"),fn.getJSONObject("parameters")));}
                break;
            }
            case "anthropic":{
                keys(wire,"model","messages","system","tools","tool_choice","max_tokens","stream","metadata","temperature","top_p","top_k","stop_sequences","thinking","output_config","service_tier");request=limit(selected,wire,"max_tokens");
                if(wire.has("system"))messages.put(message("system",anthropicParts(wire.get("system"))));JSONArray items=array(wire.opt("messages"),"messages",1,100);
                for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);JSONObject projected=message(role(item,"role",Set.of("user","assistant")),anthropicParts(item.get("content")));if(hasFunctionHistory(item.get("content")))functionHistory=true;messages.put(projected);}
                JSONArray nativeTools=wire.optJSONArray("tools");if(wire.has("tools"))array(nativeTools,"tools",0,64);
                for(int i=0;nativeTools!=null&&i<nativeTools.length();i++){JSONObject fn=nativeTools.getJSONObject(i);if(fn.has("type")&&!"custom".equals(fn.optString("type")))throw new Rejected("relay 不接服务端工具或计算机执行权限");tools.put(function(name(fn,"name"),fn.optString("description"),fn.getJSONObject("input_schema")));}break;
            }
            case "gemini":{
                keys(wire,"contents","systemInstruction","tools","toolConfig","generationConfig","safetySettings");JSONObject generation=wire.optJSONObject("generationConfig");if(wire.has("generationConfig")&&generation==null)throw new Rejected("generationConfig 需要对象");if(generation==null){generation=new JSONObject();wire.put("generationConfig",generation);}request=limit(selected,generation,"maxOutputTokens");
                if(generation.has("candidateCount")&&integer(generation.get("candidateCount"))!=1)throw new Rejected("本机 relay 只接单候选请求");
                if(wire.has("systemInstruction"))messages.put(message("system",geminiParts(wire.getJSONObject("systemInstruction").getJSONArray("parts"))));
                JSONArray contents=array(wire.opt("contents"),"contents",1,100);for(int i=0;i<contents.length();i++){JSONObject item=contents.getJSONObject(i);String role=item.optString("role","user");if(!Set.of("user","model").contains(role))throw new Rejected("Gemini contents role 无效");JSONObject projected=message(role.equals("model")?"assistant":"user",geminiParts(item.getJSONArray("parts")));if(hasFunctionHistory(item.get("parts")))functionHistory=true;messages.put(projected);}
                JSONArray groups=wire.optJSONArray("tools");if(wire.has("tools"))array(groups,"tools",0,64);int count=0;
                for(int i=0;groups!=null&&i<groups.length();i++){JSONObject group=groups.getJSONObject(i);keys(group,"functionDeclarations");JSONArray fns=array(group.opt("functionDeclarations"),"functionDeclarations",1,64);for(int j=0;j<fns.length();j++){if(++count>64)throw new Rejected("函数声明超过64项");JSONObject fn=fns.getJSONObject(j);JSONObject schema=fn.optJSONObject("parametersJsonSchema");if(schema==null)schema=fn.optJSONObject("parameters");if(schema==null)schema=new JSONObject().put("type","object");tools.put(function(name(fn,"name"),fn.optString("description"),schema));}}break;
            }
            default:throw new Rejected("未接入此本机协议");
        }
        if(messages.length()==0)throw new Rejected("需要本次实际输入，不能用隐藏的服务端会话代替");
        request=reasoning(request,wire);validateToolNames(tools);
        // Include all actual native framing/options, not merely a shortened content projection.
        // Inline image bytes are charged by the existing image reserve, not as text tokens twice.
        messages.put(message("user",withoutInlineImages(wire).toString()));
        Object summary=wire.optJSONObject("reasoning")==null?null:wire.getJSONObject("reasoning").opt("summary");
        JSONObject admitted=ModelRequestPolicy.applyNative(request,messages,tools,wire,functionHistory);
        if(summary!=null){if(!(summary instanceof String)||((String)summary).length()>64)throw new Rejected("reasoning summary 参数无效");admitted.getJSONObject("reasoning").put("summary",summary);}
        URL endpoint=ProviderAdapters.selected(request.provider).endpoint(request);if(stream)endpoint=ApiTurnStream.endpoint(request.provider,endpoint);
        return new Prepared(request,admitted,messages,tools,endpoint,stream,functionHistory);
    }
    private static ModelConfig reasoning(ModelConfig selected,JSONObject wire)throws Exception{
        ReasoningOptions supplied=null;
        switch(selected.provider){
            case "openai_responses":if(wire.has("reasoning")){JSONObject r=wire.getJSONObject("reasoning");keys(r,"effort","summary");if(r.has("effort"))supplied=new ReasoningOptions(ReasoningOptions.Mode.OPENAI_EFFORT,r.getString("effort"),0);}break;
            case "anthropic":if(wire.has("thinking")){JSONObject t=wire.getJSONObject("thinking");if("adaptive".equals(t.optString("type"))){keys(t,"type");JSONObject o=wire.optJSONObject("output_config");if(o==null||!o.has("effort"))throw new Rejected("adaptive thinking 需明确 effort");keys(o,"effort");supplied=new ReasoningOptions(ReasoningOptions.Mode.ANTHROPIC_ADAPTIVE,o.getString("effort"),0);}else if("enabled".equals(t.optString("type"))){keys(t,"type","budget_tokens");supplied=new ReasoningOptions(ReasoningOptions.Mode.ANTHROPIC_BUDGET,"",Math.toIntExact(integer(t.get("budget_tokens"))));}else throw new Rejected("未接入该 thinking 模式");}break;
            case "gemini":JSONObject g=wire.getJSONObject("generationConfig");if(g.has("thinkingConfig")){JSONObject t=g.getJSONObject("thinkingConfig");keys(t,"thinkingLevel","thinkingBudget");if(t.has("thinkingLevel")==t.has("thinkingBudget"))throw new Rejected("thinking 配置只能指定一种模式");supplied=t.has("thinkingLevel")?new ReasoningOptions(ReasoningOptions.Mode.GEMINI_LEVEL,t.getString("thinkingLevel"),0):new ReasoningOptions(ReasoningOptions.Mode.GEMINI_BUDGET,"",Math.toIntExact(integer(t.get("thinkingBudget"))));}break;
        }
        if(supplied==null)return selected;
        if(selected.reasoning.mode()!=ReasoningOptions.Mode.DEFAULT&&!selected.reasoning.equals(supplied))throw new Rejected("本机已固定推理设置；客户端不能代换");
        return selected.catalogEnforced?selected.withNativeCatalog(selected.capabilityBinding,supplied,selected.imageTokenReserve):selected;
    }
    private static ModelConfig limit(ModelConfig selected,JSONObject object,String field)throws Exception{
        long n=object.has(field)?integer(object.get(field)):selected.maxOutputTokens;if(n<1||n>selected.maxOutputTokens)throw new Rejected("请求输出上限超过本机服务预算，未截小或提高预算");object.put(field,n);return selected.withRequestOutputLimit((int)n);
    }
    private static JSONArray responseParts(Object content)throws Exception{
        if(content instanceof String)return new JSONArray().put(text((String)content));JSONArray source=array(content,"content",1,100),result=new JSONArray();
        for(int i=0;i<source.length();i++){JSONObject p=source.getJSONObject(i);switch(p.optString("type")){case "input_text":case "output_text":result.put(text(p.getString("text")));break;case "input_image":result.put(image(p.getString("image_url")));break;default:throw new Rejected("未接入该 Responses 消息部分");}}return result;
    }
    private static Object anthropicParts(Object content)throws Exception{
        if(content instanceof String)return content;JSONArray source=array(content,"content",1,100),result=new JSONArray();boolean history=false;
        for(int i=0;i<source.length();i++){JSONObject p=source.getJSONObject(i);switch(p.optString("type")){
            case "text":result.put(text(p.getString("text")));break;
            case "image":JSONObject sourceImage=p.getJSONObject("source");if(!"base64".equals(sourceImage.optString("type")))throw new Rejected("当前图像合同只接实际 base64，不抓取外部地址");result.put(image("data:"+sourceImage.getString("media_type")+";base64,"+sourceImage.getString("data")));break;
            case "tool_use":case "tool_result":history=true;result.put(text(p.toString()));break;
            case "thinking":case "redacted_thinking":result.put(text(p.toString()));break;
            default:throw new Rejected("未接入该 Anthropic 内容或服务端工具");
        }}return result;
    }
    private static JSONArray geminiParts(JSONArray source)throws Exception{
        if(source.length()<1||source.length()>100)throw new Rejected("parts 数量应为1至100");JSONArray result=new JSONArray();for(int i=0;i<source.length();i++){JSONObject p=source.getJSONObject(i);
            keys(p,"text","inlineData","functionCall","functionResponse","thought","thoughtSignature");
            int kinds=0;for(String kind:new String[]{"text","inlineData","functionCall","functionResponse"})if(p.has(kind))kinds++;
            if(kinds!=1||p.has("thought")&&!(p.get("thought") instanceof Boolean)||p.has("thoughtSignature")&&!(p.get("thoughtSignature") instanceof String))throw new Rejected("Gemini part需要唯一已接入的实际内容类型，不能混入未计预算的文件或图片");
            if(p.has("text"))result.put(text(p.getString("text")));
            else if(p.has("inlineData")){JSONObject data=p.getJSONObject("inlineData");result.put(image("data:"+data.getString("mimeType")+";base64,"+data.getString("data")));}
            else if(p.has("functionCall")||p.has("functionResponse"))result.put(text(p.toString()));
            else throw new Rejected("当前 Gemini relay 只接文字、实际图像与客户端函数，不自动抓取文件或运行工具");
        }return result;
    }
    private static boolean hasFunctionHistory(Object raw)throws Exception{
        if(!(raw instanceof JSONArray))return false;JSONArray rows=(JSONArray)raw;for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(row.optString("type").matches("tool_use|tool_result")||row.has("functionCall")||row.has("functionResponse"))return true;}return false;
    }
    private static JSONObject withoutInlineImages(JSONObject source)throws Exception{return (JSONObject)stripImages(source);}
    private static Object stripImages(Object raw)throws Exception{
        if(raw instanceof JSONObject){JSONObject j=(JSONObject)raw,out=new JSONObject();for(Iterator<String> it=j.keys();it.hasNext();){String key=it.next();Object value=j.get(key);if((key.equals("data")||key.equals("image_url"))&&value instanceof String&&((String)value).length()>64&&(j.has("media_type")||j.has("mimeType")||key.equals("image_url")))out.put(key,"<inline image charged separately>");else out.put(key,stripImages(value));}return out;}
        if(raw instanceof JSONArray){JSONArray a=(JSONArray)raw,out=new JSONArray();for(int i=0;i<a.length();i++)out.put(stripImages(a.get(i)));return out;}return raw;
    }
    private static JSONObject image(String url)throws Exception{if(!url.matches("data:image/(png|jpeg|webp|gif);base64,[A-Za-z0-9+/=]+"))throw new Rejected("图像必须为已提供的有效 base64 内容，不代取其他地址");return new JSONObject().put("type","image_url").put("image_url",new JSONObject().put("url",url));}
    private static JSONObject text(String value)throws Exception{return new JSONObject().put("type","text").put("text",value);}
    private static JSONObject message(String role,Object content)throws Exception{return new JSONObject().put("role",role).put("content",content);}
    private static JSONObject function(String name,String description,JSONObject schema)throws Exception{return new JSONObject().put("type","function").put("function",new JSONObject().put("name",name).put("description",description).put("parameters",schema));}
    private static void validateToolNames(JSONArray tools)throws Exception{Set<String> names=new HashSet<>();for(int i=0;i<tools.length();i++)if(!names.add(tools.getJSONObject(i).getJSONObject("function").getString("name")))throw new Rejected("函数名称不能重复");}
    private static String name(JSONObject object,String field)throws Exception{String name=object.getString(field);if(!name.matches("[A-Za-z0-9_-]{1,64}"))throw new Rejected("函数名称无效");return name;}
    private static String role(JSONObject object,String key,Set<String> allowed)throws Exception{String value=object.getString(key);if(!allowed.contains(value))throw new Rejected("消息角色无效");return value;}
    private static void string(JSONObject object,String key)throws Exception{if(!(object.get(key) instanceof String))throw new Rejected(key+" 需要字符串");}
    private static JSONArray array(Object object,String key,int min,int max)throws Rejected{if(!(object instanceof JSONArray)||((JSONArray)object).length()<min||((JSONArray)object).length()>max)throw new Rejected(key+" 数量或类型无效");return (JSONArray)object;}
    private static long integer(Object value)throws Rejected{if(!(value instanceof Integer||value instanceof Long))throw new Rejected("输出/预算参数需要范围内的实际整数");return ((Number)value).longValue();}
    private static void keys(JSONObject object,String... names)throws Rejected{Set<String> allowed=Set.of(names);for(Iterator<String> it=object.keys();it.hasNext();)if(!allowed.contains(it.next()))throw new Rejected("存在未接入字段；没有忽略后假装兼容");}
    private static String geminiPath(ModelConfig config,boolean stream)throws Rejected{String model=config.model.startsWith("models/")?config.model.substring(7):config.model;if(!model.matches("[A-Za-z0-9._-]{1,200}"))throw new Rejected("当前 Gemini 服务模型名称无效");return "/v1beta/models/"+model+(stream?":streamGenerateContent?alt=sse":":generateContent");}
    private NativeRelayPolicy(){}
}
