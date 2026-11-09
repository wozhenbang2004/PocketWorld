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
    /** Public expiry copied from the encrypted session at the same original profile commit. */
    public final long credentialExpiresAtEpochMillis;
    public ApiConnectionProfile(String name,long revision,boolean enabled,String secretSlot,ModelConfig configuration){
        this(name,revision,enabled,secretSlot,configuration,0);
    }
    public ApiConnectionProfile(String name,long revision,boolean enabled,String secretSlot,ModelConfig configuration,long credentialExpiresAtEpochMillis){
        this.name=checkedName(name);this.revision=revision;this.enabled=enabled;this.secretSlot=Objects.requireNonNull(secretSlot);
        this.configuration=Objects.requireNonNull(configuration);
        this.credentialExpiresAtEpochMillis=credentialExpiresAtEpochMillis;
        // A saved source can be ready to read its catalog before a model has been chosen.
        if(configuration.capabilityBinding!=null&&!configuration.model.isEmpty())new ModelCatalog.ModelKey(configuration.capabilityBinding,configuration.model);
        if(configuration.usesBedrock()&&configuration.model.isEmpty())throw new IllegalArgumentException("Bedrock 连接仍需明确填写模型 ID");
        if(configuration.baseUrl.length()>4096)throw new IllegalArgumentException("API接口地址过长");
        if(revision<1||configuration.usesAccount()||!configuration.apiKey.isEmpty()||configuration.awsSession!=null||configuration.capabilityBinding==null
                ||!secretSlot.isEmpty()&&!secretSlot.matches("model\\.api\\.[a-f0-9]{64}\\.[01]"))throw new IllegalArgumentException("API连接原生身份无效");
        if(configuration.usesBedrock()?(secretSlot.isEmpty()?credentialExpiresAtEpochMillis!=0:credentialExpiresAtEpochMillis<1):credentialExpiresAtEpochMillis!=0)
            throw new IllegalArgumentException("原短期凭据到期身份不完整");
    }
    public String id(){return configuration.capabilityBinding.connectionId();}
    /** Native storage only; credential values live in the existing SecretVault. */
    public JSONObject toJson()throws Exception{return new JSONObject().put("format","pocketworld.api-connection.v1").put("name",name)
        .put("revision",revision).put("enabled",enabled).put("secretSlot",secretSlot).put("credentialExpiresAtEpochMillis",credentialExpiresAtEpochMillis).put("config",configuration.publicJson());}
    public JSONObject publicJson()throws Exception{String status=credentialStatus();return new JSONObject().put("id",id()).put("name",name).put("revision",revision)
        .put("enabled",enabled).put("hasApiKey",!secretSlot.isEmpty()).put("provider",configuration.provider).put("baseUrl",configuration.baseUrl)
        .put("model",configuration.model).put("selectionReady",enabled&&!configuration.model.isEmpty()&&(!configuration.usesBedrock()||!secretSlot.isEmpty()&&System.currentTimeMillis()<credentialExpiresAtEpochMillis)).put("catalogEnabled",configuration.catalogEnforced).put("binding",configuration.capabilityBinding.toJson())
        .put("configuration",publicConfiguration()).put("credentialKind",configuration.usesBedrock()?"aws_sts_session":"api_key")
        .put("hasAwsSession",configuration.usesBedrock()&&!secretSlot.isEmpty()).put("bedrockRegion",configuration.bedrockRegion)
        .put("credentialExpiresAt",expiry()).put("credentialStatus",status).put("hasApiKey",!configuration.usesBedrock()&&!secretSlot.isEmpty())
        .put("source",InferenceSourceDescriptor.forApi(configuration.provider,configuration.baseUrl,configuration.bedrockRegion,status));}
    private Object expiry(){return credentialExpiresAtEpochMillis<1?JSONObject.NULL:java.time.Instant.ofEpochMilli(credentialExpiresAtEpochMillis).toString();}
    private String credentialStatus(){return secretSlot.isEmpty()?"not_configured":!enabled?"disabled":configuration.usesBedrock()&&System.currentTimeMillis()>=credentialExpiresAtEpochMillis?"expired":"saved_unverified";}
    private JSONObject publicConfiguration()throws Exception{return configuration.publicJson().put("hasApiKey",!configuration.usesBedrock()&&!secretSlot.isEmpty())
        .put("hasAwsSession",configuration.usesBedrock()&&!secretSlot.isEmpty()).put("credentialExpiresAt",expiry()).put("credentialStatus",credentialStatus());}
    public static ApiConnectionProfile fromJson(JSONObject row)throws Exception{
        if(!"pocketworld.api-connection.v1".equals(row.getString("format"))||!(row.opt("enabled") instanceof Boolean))throw new IllegalArgumentException("API连接格式不可核对，请保留原配置");
        Object revision=row.opt("revision");if(!(revision instanceof Integer||revision instanceof Long)||((Number)revision).longValue()<1)throw new IllegalArgumentException("API连接修订无效");
        JSONObject c=row.getJSONObject("config");if(!"api_key".equals(c.getString("modelRoute"))||!(c.opt("catalogEnforced") instanceof Boolean))throw new IllegalArgumentException("API连接不能保存账号身份或未知能力策略");
        if(c.has("awsSession")||c.has("apiKey"))throw new IllegalArgumentException("连接公开配置不能携带凭据内容");
        for(String field:new String[]{"sendScreenshots","supportsImageInput"})if(!(c.opt(field) instanceof Boolean))throw new IllegalArgumentException("API连接状态无效");
        ModelConfig config=new ModelConfig(c.getString("baseUrl"),"",c.getString("model"),c.getBoolean("sendScreenshots"),c.getInt("maxSteps"),c.getInt("requestTimeoutSeconds"),c.getString("provider"),c.getInt("maxOutputTokens"));
        if(config.usesBedrock())config=config.withBedrockSession(c.getString("bedrockRegion"),null);
        config=config.withCleartextConsent(c.getString("cleartextConsent")).withOwnerImageInput(c.getBoolean("supportsImageInput"));
        ModelBinding binding=ModelBinding.fromJson(c.getJSONObject("capabilityBinding"));ModelCatalogRoutes.requireSameOrigin(config,binding.modelsEndpoint());
        config=c.getBoolean("catalogEnforced")?config.withNativeCatalog(binding,ReasoningOptions.fromJson(c.getJSONObject("reasoning")),c.getInt("imageTokenReserve")):config.withApiConnection(binding);
        if(c.has("compatibility"))config=config.withProviderCompatibility(dev.pocketengine.runtime.providers.ProviderCompatProfile.fromJson(c.getJSONObject("compatibility")));
        Object expiry=row.opt("credentialExpiresAtEpochMillis");long at=0;
        if(expiry!=null){if(!(expiry instanceof Integer||expiry instanceof Long))throw new IllegalArgumentException("短期凭据到期时间格式无效");at=((Number)expiry).longValue();}
        return new ApiConnectionProfile(row.getString("name"),((Number)revision).longValue(),row.getBoolean("enabled"),row.getString("secretSlot"),config,at);
    }
    public static String checkedName(String name){
        if(name==null||name.length()>80||name.codePoints().allMatch(Character::isWhitespace)||name.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("连接名称需为1–80个可见字符");return name.trim();
    }
}
