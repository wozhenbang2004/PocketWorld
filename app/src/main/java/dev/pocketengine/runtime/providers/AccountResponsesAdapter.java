package dev.pocketengine.runtime.providers;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.features.proxy.providers.ResponsesRequest;
import dev.pocketengine.runtime.ModelGateway.GatewayException;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Canonical host history <-> SIWC Responses. No credentials, execution, permissions, or retries. */
public final class AccountResponsesAdapter {
    private static final String NAMESPACE="pocketworld";
    private AccountResponsesAdapter(){}

    public static ResponsesRequest request(ModelConfig config,JSONArray messages,JSONArray tools)throws Exception {
        if(config==null||!config.usesAccount())throw invalid();
        return request(config,messages,tools,Set.of(config.accountModel.accountVersion));
    }
    /** Accepted versions come only from the native single-Run binding, never from history itself. */
    public static ResponsesRequest request(ModelConfig config,JSONArray messages,JSONArray tools,Set<Long> acceptedVersions)throws Exception {
        return request(config,messages,tools,acceptedVersions,false);
    }
    /** Image permission comes from the native gateway, never from history or a model config declaration. */
    public static ResponsesRequest request(ModelConfig config,JSONArray messages,JSONArray tools,Set<Long> acceptedVersions,boolean imageInputSupported)throws Exception {
        if(!config.usesAccount()||messages==null)throw invalid();
        JSONArray input=new JSONArray(),functions=new JSONArray(),namespaces=new JSONArray();
        if(tools!=null)for(int i=0;i<tools.length();i++){
            JSONObject tool=tools.getJSONObject(i);keys(tool,"type","function");
            if(!"function".equals(tool.optString("type")))throw unsupported();
            JSONObject fn=tool.getJSONObject("function");keys(fn,"name","description","parameters","strict","output_schema");
            JSONObject declaration=copy(fn).put("type","function");
            // Canonical host schemas are non-strict unless explicitly declared otherwise.
            if(!declaration.has("strict"))declaration.put("strict",false);
            functions.put(declaration);
        }
        if(functions.length()>0)namespaces.put(new JSONObject().put("type","namespace").put("name",NAMESPACE)
                .put("description","Host-provided tools. Every execution remains subject to native task authority and approval.").put("tools",functions));
        for(int i=0;i<messages.length();i++){
            JSONObject message=messages.getJSONObject(i);keys(message,"role","content","tool_calls","tool_call_id","provider_data","refusal");
            String role=message.getString("role");
            if(role.equals("tool")){
                if(message.has("provider_data")||message.has("tool_calls"))throw invalid();
                input.put(new JSONObject().put("type","function_call_output").put("call_id",message.getString("tool_call_id")).put("output",text(message.opt("content"))));
                continue;
            }
            if(!Set.of("system","developer","user","assistant").contains(role))throw invalid();
            JSONObject metadata=message.optJSONObject("provider_data");
            if(message.has("provider_data")&&metadata==null)throw invalid();
            if(metadata!=null){
                if(!role.equals("assistant"))throw invalid();
                keys(metadata,"provider","nativeRef","items");
                ModelConfig.AccountModelRef prior=ModelConfig.AccountModelRef.fromJson(metadata.getJSONObject("nativeRef"));
                if(!ModelConfig.ACCOUNT_PROVIDER.equals(metadata.optString("provider"))||
                        !config.accountModel.sameModel(prior)||!acceptedVersions.contains(prior.accountVersion))
                    throw new GatewayException("历史模型上下文属于另一个账号、版本或模型；请在原连接恢复，或新建对话，不能自动重放。");
                JSONArray raw=metadata.getJSONArray("items");
                for(int j=0;j<raw.length();j++)input.put(copy(raw.getJSONObject(j)));
                continue; // Native output already contains its messages and calls, in original order.
            }
            Object content=message.opt("content");
            Object body=content;
            if(content instanceof JSONArray&&role.equals("user")){
                JSONArray converted=new JSONArray(),parts=(JSONArray)content;
                for(int j=0;j<parts.length();j++){
                    JSONObject part=parts.getJSONObject(j);String type=part.optString("type");
                    if(type.equals("text")){keys(part,"type","text");converted.put(new JSONObject().put("type","input_text").put("text",part.getString("text")));}
                    else if(type.equals("image_url")){
                        if(!config.sendScreenshots)throw new GatewayException("截图发送已关闭，图片未发送；请在本机设置明确允许后再继续原任务。");
                        if(!imageInputSupported)throw new dev.pocketengine.features.proxy.accounts.GatewayFailure("ACCOUNT_IMAGE_CAPABILITY_REQUIRED");
                        keys(part,"type","image_url");JSONObject image=part.getJSONObject("image_url");keys(image,"url","detail");
                        JSONObject nativeImage=new JSONObject().put("type","input_image").put("image_url",image.getString("url"));
                        if(image.has("detail"))nativeImage.put("detail",image.getString("detail"));converted.put(nativeImage);
                    }else throw new GatewayException("此账号连接尚未接入文件、音频或视频，原内容没有被丢弃或发送。");
                }
                body=converted;
            }else body=text(content);
            if(body instanceof JSONArray||!((String)body).isEmpty())input.put(new JSONObject().put("type","message").put("role",role.equals("system")?"developer":role).put("content",body));
            JSONArray calls=message.optJSONArray("tool_calls");
            if(calls!=null){
                if(!role.equals("assistant"))throw invalid();
                for(int j=0;j<calls.length();j++){
                    JSONObject call=calls.getJSONObject(j);keys(call,"id","type","function");
                    if(!"function".equals(call.optString("type")))throw unsupported();
                    JSONObject fn=call.getJSONObject("function");keys(fn,"name","arguments");
                    input.put(new JSONObject().put("type","function_call").put("call_id",call.getString("id"))
                        .put("namespace",NAMESPACE).put("name",fn.getString("name")).put("arguments",fn.getString("arguments")));
                }
            }
            if(!message.optString("refusal").isEmpty())throw new GatewayException("历史包含未接入的拒绝消息，请新建对话。");
        }
        // This bounded validator rejects unknown fields, dangling results and unsupported modalities.
        return new ResponsesRequest(config.model,"",input,namespaces,config.sendScreenshots&&imageInputSupported,config.reasoning,config.maxOutputTokens,config.imageTokenReserve);
    }

    /** Accept only the gateway's verified completed event, never deltas or partial function arguments. */
    public static JSONObject response(ModelConfig config,JSONObject completed,JSONArray tools)throws Exception {
        if(completed==null||!"completed".equals(completed.optString("type")))throw invalid();
        JSONArray output=completed.getJSONArray("output"),calls=new JSONArray();StringBuilder text=new StringBuilder();
        Set<String> declared=new HashSet<>(),callIds=new HashSet<>();
        if(tools!=null)for(int i=0;i<tools.length();i++)declared.add(tools.getJSONObject(i).getJSONObject("function").getString("name"));
        for(int i=0;i<output.length();i++){
            JSONObject item=output.getJSONObject(i);String type=item.getString("type");
            if(!"completed".equals(item.optString("status","completed")))throw invalid();
            if(type.equals("reasoning"))continue; // Opaque continuation stays in provider_data, not visible text.
            if(type.equals("function_call")){
                String id=item.getString("call_id"),name=item.getString("name");
                if(id.isEmpty()||!callIds.add(id)||!NAMESPACE.equals(item.optString("namespace"))||!declared.contains(name))throw unsupported();
                calls.put(new JSONObject().put("id",id).put("type","function").put("function",new JSONObject()
                    .put("name",name).put("arguments",item.getString("arguments"))));
            }else if(type.equals("message")){
                if(!"assistant".equals(item.optString("role")))throw invalid();
                JSONArray parts=item.getJSONArray("content");
                for(int j=0;j<parts.length();j++){
                    JSONObject part=parts.getJSONObject(j);String partType=part.getString("type");
                    if(partType.equals("output_text"))text.append(part.getString("text"));
                    else if(partType.equals("refusal"))throw new GatewayException("模型拒绝了这次请求；未执行其中的工具，请调整请求。");
                    else throw unsupported();
                }
            }else throw unsupported();
        }
        JSONObject result=new JSONObject().put("role","assistant").put("content",text.toString());
        if(calls.length()>0)result.put("tool_calls",calls);
        return result.put("provider_data",new JSONObject().put("provider",ModelConfig.ACCOUNT_PROVIDER)
            .put("nativeRef",config.accountModel.toJson()).put("items",new JSONArray(output.toString())));
    }
    private static String text(Object content)throws Exception {
        if(content==null||content==JSONObject.NULL)return "";
        if(content instanceof String)return (String)content;
        if(!(content instanceof JSONArray))throw invalid();
        StringBuilder result=new StringBuilder();JSONArray parts=(JSONArray)content;
        for(int i=0;i<parts.length();i++){
            JSONObject part=parts.getJSONObject(i);
            if(!"text".equals(part.optString("type")))throw new GatewayException("此历史消息包含未接入的媒体输入，原内容未被丢弃或发送。");
            keys(part,"type","text");result.append(part.getString("text"));
        }
        return result.toString();
    }
    private static JSONObject copy(JSONObject value)throws Exception{return new JSONObject(value.toString());}
    private static void keys(JSONObject value,String... allowed)throws GatewayException {
        Set<String> known=new HashSet<>(java.util.Arrays.asList(allowed));java.util.Iterator<String> keys=value.keys();
        while(keys.hasNext())if(!known.contains(keys.next()))throw unsupported();
    }
    private static GatewayException invalid(){return new GatewayException("账号模型上下文无效或不完整，未发出新的请求。");}
    private static GatewayException unsupported(){return new GatewayException("账号模型返回或请求了尚未接入的字段或工具，未派发动作。");}
}
