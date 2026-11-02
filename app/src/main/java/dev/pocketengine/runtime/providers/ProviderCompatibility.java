package dev.pocketengine.runtime.providers;

import dev.pocketengine.core.ModelConfig;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Pure final-wire projection. Canonical history, receipts, permissions and native model facts stay intact. */
public final class ProviderCompatibility {
    private ProviderCompatibility(){}
    public static final class Rejected extends Exception {public final String code;private Rejected(String code,String message){super(message);this.code=code;}}
    private static final Set<String> CORE_SCHEMA=Set.of("type","properties","required","additionalProperties","items","enum","description","title","minimum","maximum","minLength","maxLength","minItems","maxItems");
    public static JSONObject project(ModelConfig config,JSONArray messages,JSONArray tools,JSONObject original)throws Exception {
        ProviderCompatProfile p=config.compatibility;if(p==null)return original;
        requireMatch(config,p);JSONObject body=new JSONObject(original.toString());
        if(p.tokenField!=ProviderCompatProfile.TokenField.NATIVE){
            String key=p.tokenField==ProviderCompatProfile.TokenField.MAX_TOKENS?"max_tokens":"max_completion_tokens";
            if(config.catalogEnforced&&body.has(key.equals("max_tokens")?"max_completion_tokens":"max_tokens"))throw rejected("compat_token_fact_conflict","兼容token字段与原模型事实冲突；请核对原目录，未覆盖事实。");
            body.remove("max_tokens");body.remove("max_completion_tokens");body.put(key,config.maxOutputTokens);
        }
        if(p.streamUsage==ProviderCompatProfile.StreamUsage.OMIT)body.remove("stream_options");
        int count=tools==null?0:tools.length();if(count>p.maxTools)throw rejected("compat_tool_limit","工具数量超过此连接兼容档案上限；未发送。");
        if(p.schemaSubset==ProviderCompatProfile.SchemaSubset.CORE_JSON)for(int i=0;i<count;i++){
            JSONObject tool=tools.getJSONObject(i),fn=tool.optJSONObject("function");if(fn==null)fn=tool;
            validateSchema(fn.getJSONObject("parameters"),0);
        }
        if(p.toolWireShape!=ProviderCompatProfile.ToolShape.NATIVE&&count>0){
            JSONArray definitions=new JSONArray();for(int i=0;i<count;i++){
                JSONObject tool=tools.getJSONObject(i),fn=tool.optJSONObject("function");if(fn==null)fn=tool;
                var fields=fn.keys();while(fields.hasNext())if(!Set.of("name","description","parameters","strict").contains(fields.next()))throw rejected("compat_tool_field_unsupported","工具声明包含不能保真转换的字段；未删除字段或发送。");
                if(fn.has("strict")&&(!(fn.opt("strict") instanceof Boolean)||fn.getBoolean("strict")&&p.toolWireShape==ProviderCompatProfile.ToolShape.ANTHROPIC_INPUT_SCHEMA))throw rejected("compat_tool_strict_unsupported","此工具声明形状不能保真表达strict约束；未降级发送。");
                JSONObject definition=new JSONObject().put("name",fn.getString("name")).put("description",fn.optString("description"));
                if(fn.has("strict")&&p.toolWireShape==ProviderCompatProfile.ToolShape.OPENAI_FUNCTION)definition.put("strict",fn.getBoolean("strict"));
                definitions.put(p.toolWireShape==ProviderCompatProfile.ToolShape.ANTHROPIC_INPUT_SCHEMA?definition.put("input_schema",new JSONObject(fn.getJSONObject("parameters").toString())):new JSONObject().put("type","function").put("function",definition.put("parameters",new JSONObject(fn.getJSONObject("parameters").toString()))));
            }body.put("tools",definitions);
        }
        if(p.cacheMode==ProviderCompatProfile.CacheMode.EPHEMERAL&&p.cacheSupport==ProviderCompatProfile.CacheSupport.SUPPORTED){
            JSONArray system=body.optJSONArray("system");if(system!=null&&system.length()>0){JSONObject block=system.getJSONObject(system.length()-1);if(!"text".equals(block.optString("type")))throw rejected("compat_cache_prefix_unsupported","当前system前缀不能保真标记缓存提示。");block.put("cache_control",hint());}
            JSONArray definitions=body.optJSONArray("tools");if(definitions!=null&&definitions.length()>0){JSONObject last=definitions.getJSONObject(definitions.length()-1);if(!last.has("input_schema"))throw rejected("compat_cache_tool_shape","缓存提示需要Anthropic原生工具声明形状。");last.put("cache_control",hint());}
        }
        if(body.toString().getBytes(StandardCharsets.UTF_8).length>p.maxRequestBytes)throw rejected("compat_request_byte_limit","最终请求字节超过此连接兼容档案上限；未发送。");
        return body;
    }
    /** Safe prepared-wire metadata. Only original dispatch/usage evidence can describe later delivery or caching. */
    public static JSONObject diagnostics(ModelConfig config,JSONObject wire)throws Exception {
        ProviderCompatProfile p=config.compatibility;JSONObject out=new JSONObject().put("format","pocketworld.provider-projection.v1").put("hintPrepared",false).put("dispatchAttempted",false).put("hintDelivery","not_requested").put("cacheHitKnown",false).put("cacheUsageSource","provider_usage_only");
        if(p==null)return out.put("reason","compatibility_not_configured");requireMatch(config,p);
        JSONArray system=wire.optJSONArray("system"),tools=wire.optJSONArray("tools");boolean hint=hasHint(system)||hasHint(tools);
        out.put("compatProfileId",p.id).put("compatProfileRevision",p.revision).put("apiMode",p.apiMode).put("hintPrepared",hint).put("hintDelivery",hint?"not_dispatched":"not_requested")
            .put("cacheMode",p.cacheMode.name()).put("cacheSupport",p.cacheSupport.name()).put("cacheSupportSource","owner_reviewed_connection_profile").put("reason",hint?"hint_requested_not_a_cache_hit":p.cacheMode==ProviderCompatProfile.CacheMode.OFF?"cache_disabled":p.cacheSupport!=ProviderCompatProfile.CacheSupport.SUPPORTED?"cache_support_not_confirmed":"no_stable_system_or_tools");
        if(hint){JSONObject prefix=new JSONObject().put("model",config.model).put("provider",config.provider).put("binding",config.capabilityBinding==null?JSONObject.NULL:config.capabilityBinding.toJson()).put("system",system==null?new JSONArray():system).put("tools",tools==null?new JSONArray():tools);
            for(String key:new String[]{"thinking","output_config","tool_choice"})if(wire.has(key))prefix.put(key,wire.get(key));
            out.put("prefixHash",digest(prefix));}
        return out;
    }
    private static void requireMatch(ModelConfig config,ProviderCompatProfile p)throws Rejected {if(config.usesAccount()||!config.provider.equals(p.apiMode)||!config.model.equals(p.model))throw rejected("compat_profile_mismatch","兼容档案与所选协议/模型不一致；未借用其他模型设置。");}
    private static JSONObject hint()throws Exception{return new JSONObject().put("type","ephemeral");}
    private static boolean hasHint(JSONArray blocks){for(int i=0;blocks!=null&&i<blocks.length();i++){JSONObject block=blocks.optJSONObject(i);if(block!=null&&block.optJSONObject("cache_control")!=null)return true;}return false;}
    private static void validateSchema(JSONObject schema,int depth)throws Exception {
        if(depth>24)throw rejected("compat_schema_depth","工具schema嵌套超过兼容档案支持范围。");
        var keys=schema.keys();while(keys.hasNext()){String key=keys.next();if(!CORE_SCHEMA.contains(key))throw rejected("compat_schema_unsupported","工具schema含此档案未支持的关键字；未删除约束或降级发送。");}
        JSONObject properties=schema.optJSONObject("properties");if(properties!=null){var names=properties.keys();while(names.hasNext())validateSchema(properties.getJSONObject(names.next()),depth+1);}
        Object additional=schema.opt("additionalProperties");if(additional instanceof JSONObject)validateSchema((JSONObject)additional,depth+1);
        Object items=schema.opt("items");if(items!=null)if(items instanceof JSONObject)validateSchema((JSONObject)items,depth+1);else throw rejected("compat_schema_unsupported","此schema items形状未支持；未转换约束。");
    }
    private static String digest(JSONObject value)throws Exception {byte[] bytes=MessageDigest.getInstance("SHA-256").digest(canonical(value).getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
    private static String canonical(Object value)throws Exception {if(value instanceof JSONObject j){java.util.List<String> keys=new java.util.ArrayList<>();j.keys().forEachRemaining(keys::add);java.util.Collections.sort(keys);StringBuilder out=new StringBuilder("{");for(String key:keys){if(out.length()>1)out.append(',');out.append(JSONObject.quote(key)).append(':').append(canonical(j.get(key)));}return out.append('}').toString();}if(value instanceof JSONArray a){StringBuilder out=new StringBuilder("[");for(int i=0;i<a.length();i++){if(i>0)out.append(',');out.append(canonical(a.get(i)));}return out.append(']').toString();}return value instanceof String?JSONObject.quote((String)value):String.valueOf(value);}
    private static Rejected rejected(String code,String message){return new Rejected(code,message);}
}
