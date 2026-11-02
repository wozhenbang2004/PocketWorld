package dev.pocketengine.runtime.providers;

import dev.pocketengine.core.ModelConfig;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.json.JSONObject;

/** Native owner's versioned wire preferences. These never assert model capabilities or tool grants. */
public final class ProviderCompatProfile {
    public static final String FORMAT="pocketworld.provider-compat.v1";
    public enum TokenField { NATIVE, MAX_TOKENS, MAX_COMPLETION_TOKENS }
    public enum StreamUsage { NATIVE, OMIT }
    public enum ToolShape { NATIVE, OPENAI_FUNCTION, ANTHROPIC_INPUT_SCHEMA }
    public enum SchemaSubset { NATIVE, CORE_JSON }
    public enum CacheMode { OFF, EPHEMERAL }
    public enum CacheSupport { UNKNOWN, SUPPORTED, UNSUPPORTED }
    private static final Set<String> OPTIONS=Set.of("tokenField","streamUsage","toolWireShape","schemaSubset","maxTools","maxRequestBytes","cacheMode","cacheSupport");
    public final String id,apiMode,model;
    public final long revision;
    public final TokenField tokenField;
    public final StreamUsage streamUsage;
    public final ToolShape toolWireShape;
    public final SchemaSubset schemaSubset;
    public final CacheMode cacheMode;
    public final CacheSupport cacheSupport;
    public final int maxTools,maxRequestBytes;

    private ProviderCompatProfile(String id,long revision,String protocol,String model,JSONObject options)throws Exception {
        if(!id.matches("compat-[a-f0-9-]{36}")||revision<1||!ModelConfig.PROVIDERS.contains(protocol)||model==null||model.isEmpty()||model.length()>300||model.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("兼容档案身份无效");
        Set<String> keys=new HashSet<>();options.keys().forEachRemaining(keys::add);if(!OPTIONS.containsAll(keys))throw new IllegalArgumentException("兼容档案包含未支持的选项；未忽略或透传");
        this.id=id;this.revision=revision;apiMode=protocol;this.model=model;
        tokenField=read(options,"tokenField",TokenField.NATIVE,TokenField.class);streamUsage=read(options,"streamUsage",StreamUsage.NATIVE,StreamUsage.class);
        toolWireShape=read(options,"toolWireShape",ToolShape.NATIVE,ToolShape.class);schemaSubset=read(options,"schemaSubset",SchemaSubset.NATIVE,SchemaSubset.class);
        cacheMode=read(options,"cacheMode",CacheMode.OFF,CacheMode.class);cacheSupport=read(options,"cacheSupport",CacheSupport.UNKNOWN,CacheSupport.class);
        maxTools=integer(options,"maxTools",256,1,512);maxRequestBytes=integer(options,"maxRequestBytes",24*1024*1024,256,24*1024*1024);
        if(tokenField!=TokenField.NATIVE&&!protocol.equals("openai_chat")||streamUsage!=StreamUsage.NATIVE&&!protocol.equals("openai_chat"))throw new IllegalArgumentException("token/stream usage选项只支持所选Chat协议");
        if(toolWireShape!=ToolShape.NATIVE&&!Set.of("openai_chat","anthropic").contains(protocol))throw new IllegalArgumentException("此协议不支持工具声明形状变体");
        if(cacheMode!=CacheMode.OFF&&!protocol.equals("anthropic"))throw new IllegalArgumentException("此协议尚未接入显式缓存提示");
    }
    /** Called only by the retained owner API-profile workflow; callers cannot choose a revision. */
    public static ProviderCompatProfile reviewed(JSONObject options,String protocol,String model,ProviderCompatProfile previous)throws Exception {
        ProviderCompatProfile candidate=new ProviderCompatProfile(previous==null?"compat-"+UUID.randomUUID():previous.id,previous==null?1:Math.addExact(previous.revision,1),protocol,model,options);
        return previous!=null&&previous.apiMode.equals(protocol)&&previous.model.equals(model)&&previous.definition().toString().equals(candidate.definition().toString())?previous:candidate;
    }
    public JSONObject definition()throws Exception{return new JSONObject().put("tokenField",tokenField.name()).put("streamUsage",streamUsage.name()).put("toolWireShape",toolWireShape.name()).put("schemaSubset",schemaSubset.name()).put("maxTools",maxTools).put("maxRequestBytes",maxRequestBytes).put("cacheMode",cacheMode.name()).put("cacheSupport",cacheSupport.name());}
    public JSONObject toJson()throws Exception{return definition().put("format",FORMAT).put("id",id).put("revision",revision).put("apiMode",apiMode).put("model",model);}
    public static ProviderCompatProfile fromJson(JSONObject row)throws Exception {
        if(!FORMAT.equals(row.getString("format")))throw new IllegalArgumentException("兼容档案版本未知");
        Object revision=row.get("revision");if(!(revision instanceof Integer||revision instanceof Long))throw new IllegalArgumentException("兼容档案修订无效");
        JSONObject options=new JSONObject(row.toString());for(String key:new String[]{"format","id","revision","apiMode","model"})options.remove(key);
        return new ProviderCompatProfile(row.getString("id"),((Number)revision).longValue(),row.getString("apiMode"),row.getString("model"),options);
    }
    public boolean matches(ProviderCompatProfile other){return other!=null&&id.equals(other.id)&&revision==other.revision&&apiMode.equals(other.apiMode)&&model.equals(other.model)&&tokenField==other.tokenField&&streamUsage==other.streamUsage&&toolWireShape==other.toolWireShape&&schemaSubset==other.schemaSubset&&cacheMode==other.cacheMode&&cacheSupport==other.cacheSupport&&maxTools==other.maxTools&&maxRequestBytes==other.maxRequestBytes;}
    private static int integer(JSONObject options,String key,int fallback,int min,int max)throws Exception {
        if(!options.has(key))return fallback;Object value=options.get(key);if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<min||((Number)value).longValue()>max)throw new IllegalArgumentException("兼容档案数值超出支持范围");return ((Number)value).intValue();
    }
    private static <T extends Enum<T>> T read(JSONObject options,String key,T fallback,Class<T> type)throws Exception {
        if(!options.has(key))return fallback;Object value=options.get(key);if(!(value instanceof String))throw new IllegalArgumentException("兼容档案选项需为明确枚举");return Enum.valueOf(type,(String)value);
    }
}
