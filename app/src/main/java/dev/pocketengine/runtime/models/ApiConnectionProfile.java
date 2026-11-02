package dev.pocketengine.runtime.models;

import dev.pocketengine.core.ModelConfig;
import java.util.Objects;
import org.json.JSONObject;

/** One native owner's retained API configuration. No key bytes, tasks, grants or routing strategy. */
public final class ApiConnectionProfile {
    public static final int MAX_CONNECTIONS=32,MAX_DOCUMENT_BYTES=262144;
    public final String name,secretSlot;
    public final long revision;
    public final boolean enabled;
    public final ModelConfig configuration;
    public ApiConnectionProfile(String name,long revision,boolean enabled,String secretSlot,ModelConfig configuration){
        this.name=checkedName(name);this.revision=revision;this.enabled=enabled;this.secretSlot=Objects.requireNonNull(secretSlot);
        this.configuration=Objects.requireNonNull(configuration);
        if(configuration.capabilityBinding!=null)new ModelCatalog.ModelKey(configuration.capabilityBinding,configuration.model);
        if(configuration.baseUrl.length()>4096)throw new IllegalArgumentException("API接口地址过长");
        if(revision<1||configuration.usesAccount()||!configuration.apiKey.isEmpty()||configuration.capabilityBinding==null
                ||!secretSlot.isEmpty()&&!secretSlot.matches("model\\.api\\.[a-f0-9]{64}\\.[01]"))throw new IllegalArgumentException("API连接原生身份无效");
    }
    public String id(){return configuration.capabilityBinding.connectionId();}
    /** Native storage only; credential values live in the existing SecretVault. */
    public JSONObject toJson()throws Exception{return new JSONObject().put("format","pocketworld.api-connection.v1").put("name",name)
        .put("revision",revision).put("enabled",enabled).put("secretSlot",secretSlot).put("config",configuration.publicJson());}
    public JSONObject publicJson()throws Exception{return new JSONObject().put("id",id()).put("name",name).put("revision",revision)
        .put("enabled",enabled).put("hasApiKey",!secretSlot.isEmpty()).put("provider",configuration.provider).put("baseUrl",configuration.baseUrl)
        .put("model",configuration.model).put("catalogEnabled",configuration.catalogEnforced).put("binding",configuration.capabilityBinding.toJson())
        .put("configuration",configuration.publicJson().put("hasApiKey",!secretSlot.isEmpty()));}
    public static ApiConnectionProfile fromJson(JSONObject row)throws Exception{
        if(!"pocketworld.api-connection.v1".equals(row.getString("format"))||!(row.opt("enabled") instanceof Boolean))throw new IllegalArgumentException("API连接格式不可核对，请保留原配置");
        Object revision=row.opt("revision");if(!(revision instanceof Integer||revision instanceof Long)||((Number)revision).longValue()<1)throw new IllegalArgumentException("API连接修订无效");
        JSONObject c=row.getJSONObject("config");if(!"api_key".equals(c.getString("modelRoute"))||!(c.opt("catalogEnforced") instanceof Boolean))throw new IllegalArgumentException("API连接不能保存账号身份或未知能力策略");
        for(String field:new String[]{"sendScreenshots","supportsImageInput"})if(!(c.opt(field) instanceof Boolean))throw new IllegalArgumentException("API连接状态无效");
        ModelConfig config=new ModelConfig(c.getString("baseUrl"),"",c.getString("model"),c.getBoolean("sendScreenshots"),c.getInt("maxSteps"),c.getInt("requestTimeoutSeconds"),c.getString("provider"),c.getInt("maxOutputTokens"))
            .withCleartextConsent(c.getString("cleartextConsent")).withOwnerImageInput(c.getBoolean("supportsImageInput"));
        ModelBinding binding=ModelBinding.fromJson(c.getJSONObject("capabilityBinding"));ModelCatalogRoutes.requireSameOrigin(config,binding.modelsEndpoint());
        config=c.getBoolean("catalogEnforced")?config.withNativeCatalog(binding,ReasoningOptions.fromJson(c.getJSONObject("reasoning")),c.getInt("imageTokenReserve")):config.withApiConnection(binding);
        if(c.has("compatibility"))config=config.withProviderCompatibility(dev.pocketengine.runtime.providers.ProviderCompatProfile.fromJson(c.getJSONObject("compatibility")));
        return new ApiConnectionProfile(row.getString("name"),((Number)revision).longValue(),row.getBoolean("enabled"),row.getString("secretSlot"),config);
    }
    public static String checkedName(String name){
        if(name==null||name.length()>80||name.codePoints().allMatch(Character::isWhitespace)||name.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("连接名称需为1–80个可见字符");return name.trim();
    }
}
