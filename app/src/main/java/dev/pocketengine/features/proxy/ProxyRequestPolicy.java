package dev.pocketengine.features.proxy;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.runtime.models.ModelRequestPolicy;
import dev.pocketengine.runtime.models.ReasoningOptions;
import dev.pocketengine.runtime.providers.ApiTurnStream;
import dev.pocketengine.runtime.providers.ProviderAdapters;
import java.math.BigDecimal;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Authorized client parameters remain data, never native model/account/TaskStore selections. */
final class ProxyRequestPolicy {
    private static final Set<String> FIELDS=Set.of("model","messages","stream","max_tokens","max_completion_tokens","reasoning_effort",
            "tools","tool_choice","parallel_tool_calls","temperature","top_p","stop","seed","presence_penalty","frequency_penalty",
            "user","metadata","store","stream_options","n");
    static final class Rejected extends Exception {
        Rejected(String message){super(message);}
    }
    static final class Prepared {
        final ModelConfig requestConfig;
        final JSONArray messages,tools;
        final JSONObject wire;
        Prepared(ModelConfig config,JSONArray messages,JSONArray tools,JSONObject wire){requestConfig=config;this.messages=messages;this.tools=tools;this.wire=wire;}
        void revalidate()throws Exception{
            ModelRequestPolicy.apply(requestConfig,messages,tools,new JSONObject(wire.toString()));
        }
        void beforeStreamData(String data)throws Exception{
            if("[DONE]".equals(data)){revalidate();return;}
            JSONObject frame=new JSONObject(data);JSONArray choices=frame.optJSONArray("choices");
            if(choices!=null)for(int i=0;i<choices.length();i++){
                JSONObject choice=choices.getJSONObject(i);
                if(choice.has("finish_reason")&&!choice.isNull("finish_reason")){revalidate();return;}
            }
        }
    }
    static Prepared prepare(ModelConfig configured,JSONObject client,boolean streaming)throws Exception{
        JSONArray messages=client.getJSONArray("messages");JSONArray tools=client.optJSONArray("tools");
        if(client.has("tools")&&!client.isNull("tools")&&tools==null)throw new Rejected("tools需为函数工具数组");
        if(client.has("max_tokens")&&client.has("max_completion_tokens"))throw new Rejected("输出参数只能提供max_tokens或max_completion_tokens之一");
        ModelConfig request=configured;
        for(String field:new String[]{"max_tokens","max_completion_tokens"})if(client.has(field)){
            long limit=integer(client.opt(field));
            if(limit<1||limit>configured.maxOutputTokens)throw new Rejected("请求输出上限超过本机服务预算，或不是正整数；没有提高预算或截小参数");
            request=configured.withRequestOutputLimit((int)limit);
        }
        // Existing pre-catalog routes remain visibly legacy/unknown, not inferred capabilities.
        if(configured.capabilityBinding==null)return new Prepared(request,messages,tools,new JSONObject(client.toString()));
        java.util.Iterator<String> keys=client.keys();while(keys.hasNext())if(!FIELDS.contains(keys.next()))throw new Rejected("当前目录约束路线只接文本、图像与函数工具的标准Chat参数");
        validateMessages(messages);
        Set<String> declared=validateTools(tools);
        if(client.has("tool_choice"))validateToolChoice(client.get("tool_choice"),declared);
        if(client.has("n")&&integer(client.get("n"))!=1)throw new Rejected("本机预算按单候选请求执行，n只能为1");
        if(client.has("reasoning_effort")){
            Object raw=client.opt("reasoning_effort");
            if(!(raw instanceof String))throw new Rejected("reasoning_effort需为精确已知级别");
            ReasoningOptions chosen=new ReasoningOptions(ReasoningOptions.Mode.OPENAI_EFFORT,(String)raw,0);
            if(configured.reasoning.mode()!=ReasoningOptions.Mode.DEFAULT&&!configured.reasoning.equals(chosen))throw new Rejected("本机已固定推理设置；客户端不能代换该设置");
            request=request.withNativeCatalog(configured.capabilityBinding,chosen,configured.imageTokenReserve);
        }
        JSONObject wire=ProviderAdapters.selected(configured.provider).request(request,messages,tools);
        if(streaming)wire=ApiTurnStream.request(configured.provider,wire,request.maxOutputTokens);
        for(String field:new String[]{"tool_choice","parallel_tool_calls","temperature","top_p","stop","seed","presence_penalty","frequency_penalty","user","metadata","store","n"})
            if(client.has(field))wire.put(field,client.get(field));
        range(client,"temperature",0,2);range(client,"top_p",0,1);range(client,"presence_penalty",-2,2);range(client,"frequency_penalty",-2,2);
        if(client.has("seed"))integer(client.get("seed"));
        for(String field:new String[]{"parallel_tool_calls","store"})if(client.has(field)&&!(client.get(field) instanceof Boolean))throw new Rejected(field+"需为布尔值");
        if(client.has("user")&&(!(client.get("user") instanceof String)||client.getString("user").length()>256))throw new Rejected("user需为不超过256字符的标识");
        if(client.has("metadata")&&!(client.get("metadata") instanceof JSONObject))throw new Rejected("metadata需为对象，不是本机任务授权");
        if(client.has("stream_options")&&!client.isNull("stream_options")){
            if(!streaming||!(client.get("stream_options") instanceof JSONObject))throw new Rejected("stream_options只能用于流式请求");
            JSONObject options=client.getJSONObject("stream_options");java.util.Iterator<String> optionKeys=options.keys();
            while(optionKeys.hasNext()){String key=optionKeys.next();if(!Set.of("include_usage","include_obfuscation").contains(key)||!(options.get(key) instanceof Boolean))throw new Rejected("不支持该stream_options参数");}
            wire.put("stream_options",new JSONObject(options.toString()));
        }
        wire=ModelRequestPolicy.apply(request,messages,tools,wire);
        return new Prepared(request,messages,tools,wire);
    }
    private static long integer(Object value)throws Rejected{
        try{if(!(value instanceof Number))throw new ArithmeticException();return new BigDecimal(value.toString()).longValueExact();}
        catch(ArithmeticException|NumberFormatException invalid){throw new Rejected("请求参数需为范围内整数");}
    }
    private static void range(JSONObject input,String field,double min,double max)throws Exception{
        if(!input.has(field))return;Object raw=input.get(field);
        if(!(raw instanceof Number))throw new Rejected(field+"需为数字");double value=((Number)raw).doubleValue();
        if(!Double.isFinite(value)||value<min||value>max)throw new Rejected(field+"超出标准Chat参数范围");
    }
    private static Set<String> validateTools(JSONArray tools)throws Exception{
        Set<String> names=new java.util.HashSet<>();if(tools==null)return names;if(tools.length()>64)throw new Rejected("函数工具超过64项");
        for(int i=0;i<tools.length();i++){
            JSONObject tool=tools.getJSONObject(i),fn=tool.optJSONObject("function");
            if(!"function".equals(tool.optString("type"))||fn==null||!fn.optString("name").matches("[A-Za-z0-9_-]{1,64}")||!names.add(fn.getString("name")))
                throw new Rejected("当前路线仅接名称明确且不重复的函数工具，不推断托管工具能力");
        }
        return names;
    }
    private static void validateToolChoice(Object choice,Set<String> declared)throws Exception{
        if(choice instanceof String){
            if(!Set.of("none","auto","required").contains(choice))throw new Rejected("tool_choice只能是none、auto、required或已声明函数的选择");
            if("required".equals(choice)&&declared.isEmpty())throw new Rejected("required需要至少一个已声明函数，不能请求未知工具");
            return;
        }
        if(!(choice instanceof JSONObject))throw new Rejected("tool_choice需为标准Chat工具选择，不能作为本机工具授权");
        JSONObject selected=(JSONObject)choice;
        if("function".equals(selected.optString("type"))){namedFunction(selected,declared);return;}
        if(!"allowed_tools".equals(selected.optString("type")))throw new Rejected("当前目录约束路线只接已声明function工具选择，尚未接入自定义或托管工具");
        onlyKeys(selected,"type","allowed_tools");JSONObject allowed=selected.getJSONObject("allowed_tools");onlyKeys(allowed,"mode","tools");
        Object mode=allowed.opt("mode");
        if(!(mode instanceof String)||!Set.of("auto","required").contains(mode))throw new Rejected("allowed_tools的mode只能为auto或required");
        JSONArray subset=allowed.getJSONArray("tools");
        if(subset.length()>64||"required".equals(allowed.getString("mode"))&&subset.length()==0)
            throw new Rejected("allowed_tools需要范围内的明确函数集合；required不能选择空集合");
        Set<String> unique=new java.util.HashSet<>();
        for(int i=0;i<subset.length();i++)if(!unique.add(namedFunction(subset.getJSONObject(i),declared)))
            throw new Rejected("allowed_tools不能重复选择同一函数");
    }
    private static String namedFunction(JSONObject choice,Set<String> declared)throws Exception{
        onlyKeys(choice,"type","function");JSONObject function=choice.optJSONObject("function");
        if(!"function".equals(choice.optString("type"))||function==null)throw new Rejected("函数选择需使用Chat的type/function结构");
        onlyKeys(function,"name");Object name=function.opt("name");
        if(!(name instanceof String)||!declared.contains(name))throw new Rejected("选择的函数未在本次tools中声明，没有添加或代换工具");
        return (String)name;
    }
    private static void onlyKeys(JSONObject object,String... fields)throws Rejected{
        Set<String> allowed=Set.of(fields);java.util.Iterator<String> keys=object.keys();
        while(keys.hasNext())if(!allowed.contains(keys.next()))throw new Rejected("工具选择包含未接入字段；没有作为本机权限或继续凭据使用");
    }
    private static void validateMessages(JSONArray messages)throws Exception{
        for(int i=0;i<messages.length();i++){
            JSONObject message=messages.getJSONObject(i);
            if(message.has("provider_data"))throw new Rejected("客户端Chat消息不能携带本机私有provider_data继续凭据");
            if(!Set.of("system","developer","user","assistant","tool").contains(message.optString("role")))throw new Rejected("消息角色不属于已接入的Chat合同");
            Object content=message.opt("content");if(content==null||content==JSONObject.NULL||content instanceof String)continue;
            if(!(content instanceof JSONArray))throw new Rejected("消息content需为文字或文字/图像数组");
            JSONArray parts=(JSONArray)content;for(int p=0;p<parts.length();p++){
                JSONObject part=parts.getJSONObject(p);String type=part.optString("type");
                if(type.equals("text")){if(!(part.opt("text") instanceof String))throw new Rejected("text部分需为字符串");}
                else if(type.equals("image_url")){JSONObject image=part.optJSONObject("image_url");if(image==null||!(image.opt("url") instanceof String))throw new Rejected("image_url需包含url");}
                else throw new Rejected("当前目录约束路线未接音频或文件消息部分");
            }
        }
    }
    private ProxyRequestPolicy(){}
}
