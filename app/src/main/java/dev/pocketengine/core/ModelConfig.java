package dev.pocketengine.core;

import java.util.Set;
import org.json.JSONObject;
import dev.pocketengine.runtime.models.ModelBinding;
import dev.pocketengine.runtime.models.ReasoningOptions;

public final class ModelConfig {
    /** Native run selection; never accepted from generic JSON or model output. */
    public final dev.pocketengine.runtime.models.ModelRoutePlan routePlan;
    public final java.util.Map<String,dev.pocketengine.runtime.models.ModelRoutePlan> purposePlans;
    public static final String DEFAULT_PROVIDER="openai_chat";
    public static final String ACCOUNT_PROVIDER="chatgpt_plan";
    /** Public identity only. OAuth credentials never enter a model config. */
    public final AccountModelRef accountModel;
    /** Public native selection, bound to the exact API credential and installation revision. */
    public final ModelBinding capabilityBinding;
    /** Identity-only API profiles keep the pre-catalog contract until the owner explicitly enables facts. */
    public final boolean catalogEnforced;
    public final ReasoningOptions reasoning;
    /** Owner's conservative per-image input reserve; zero means unconfigured, never a vendor token count. */
    public final int imageTokenReserve;
    public static final Set<String> PROVIDERS=Set.of(DEFAULT_PROVIDER,"openai_responses","anthropic","gemini");
    public final String baseUrl, apiKey, model, provider;
    public final boolean sendScreenshots;
    public final boolean localCaptureAllowed, localOcrAllowed;
    /** Native owner's route/model capability declaration, not a live vision-quality result. */
    public final boolean supportsImageInput;
    public final String cleartextConsent;
    public final int maxSteps, requestTimeoutSeconds, maxOutputTokens;
    public ModelConfig(String baseUrl,String apiKey,String model,boolean sendScreenshots,int maxSteps,int requestTimeoutSeconds){this(baseUrl,apiKey,model,sendScreenshots,maxSteps,requestTimeoutSeconds,DEFAULT_PROVIDER,4096,false);}
    public ModelConfig(String baseUrl,String apiKey,String model,boolean sendScreenshots,int maxSteps,int requestTimeoutSeconds,String provider,int maxOutputTokens){this(baseUrl,apiKey,model,sendScreenshots,maxSteps,requestTimeoutSeconds,provider,maxOutputTokens,false);}
    private ModelConfig(String baseUrl,String apiKey,String model,boolean sendScreenshots,int maxSteps,int requestTimeoutSeconds,String provider,int maxOutputTokens,boolean hostCeiling){this(baseUrl,apiKey,model,sendScreenshots,maxSteps,requestTimeoutSeconds,provider,maxOutputTokens,hostCeiling,"");}
    private ModelConfig(String baseUrl,String apiKey,String model,boolean sendScreenshots,int maxSteps,int requestTimeoutSeconds,String provider,int maxOutputTokens,boolean hostCeiling,String cleartextConsent){
        this(baseUrl,apiKey,model,sendScreenshots,maxSteps,requestTimeoutSeconds,provider,maxOutputTokens,hostCeiling,cleartextConsent,false);
    }
    private ModelConfig(String baseUrl,String apiKey,String model,boolean sendScreenshots,int maxSteps,int requestTimeoutSeconds,String provider,int maxOutputTokens,boolean hostCeiling,String cleartextConsent,boolean supportsImageInput){
        this.routePlan=null;this.purposePlans=java.util.Map.of();
        this.baseUrl=baseUrl==null?"":baseUrl.trim();this.apiKey=apiKey==null?"":apiKey.trim();this.model=model==null?"":model.trim();
        this.provider=provider==null||provider.isBlank()?DEFAULT_PROVIDER:provider.trim();if(!PROVIDERS.contains(this.provider))throw new IllegalArgumentException("不支持的模型协议，请明确选择连接类型");
        this.localCaptureAllowed=sendScreenshots;this.localOcrAllowed=sendScreenshots;this.sendScreenshots=sendScreenshots;this.maxSteps=Math.max(1,Math.min(hostCeiling?400:40,maxSteps));this.requestTimeoutSeconds=Math.max(10,Math.min(120,requestTimeoutSeconds));this.maxOutputTokens=Math.max(128,Math.min(32768,maxOutputTokens));
        this.supportsImageInput=supportsImageInput;
        this.cleartextConsent=cleartextConsent==null?"":cleartextConsent;
        this.accountModel=null;
        this.capabilityBinding=null;this.catalogEnforced=false;this.reasoning=ReasoningOptions.DEFAULT;this.imageTokenReserve=0;
    }
    private ModelConfig(ModelConfig source,AccountModelRef reference,int ceiling){
        routePlan=source.routePlan;purposePlans=source.purposePlans;
        accountModel=java.util.Objects.requireNonNull(reference);provider=ACCOUNT_PROVIDER;model=reference.modelId;
        baseUrl="";apiKey="";cleartextConsent="";localCaptureAllowed=source.localCaptureAllowed;localOcrAllowed=source.localOcrAllowed;sendScreenshots=source.sendScreenshots;
        boolean same=source.usesAccount()&&source.accountModel.sameModel(reference);supportsImageInput=same&&source.supportsImageInput;
        maxSteps=Math.max(1,Math.min(400,ceiling));requestTimeoutSeconds=source.requestTimeoutSeconds;maxOutputTokens=source.maxOutputTokens;
        capabilityBinding=null;catalogEnforced=false;reasoning=same?source.reasoning:ReasoningOptions.DEFAULT;imageTokenReserve=same?source.imageTokenReserve:0;
    }
    private ModelConfig(ModelConfig source,ReasoningOptions options,int imageReserve,int outputReserve){
        routePlan=source.routePlan;purposePlans=source.purposePlans;
        if(!source.usesAccount()||options==null||(options.mode()!=ReasoningOptions.Mode.DEFAULT&&options.mode()!=ReasoningOptions.Mode.OPENAI_EFFORT)
                ||imageReserve<0||imageReserve>2000000||outputReserve<128||outputReserve>32768)throw new IllegalArgumentException("账号本地预留或推理选项无效");
        baseUrl=source.baseUrl;apiKey=source.apiKey;model=source.model;provider=source.provider;accountModel=source.accountModel;
        localCaptureAllowed=source.localCaptureAllowed;localOcrAllowed=source.localOcrAllowed;sendScreenshots=source.sendScreenshots;supportsImageInput=source.supportsImageInput;cleartextConsent=source.cleartextConsent;
        maxSteps=source.maxSteps;requestTimeoutSeconds=source.requestTimeoutSeconds;maxOutputTokens=outputReserve;
        capabilityBinding=null;catalogEnforced=false;reasoning=options;imageTokenReserve=imageReserve;
    }
    /** Native owner selection only; output is a local reserve, not a supported SIWC request field. */
    public ModelConfig withAccountOptions(ReasoningOptions options,int imageReserve,int outputReserve){return new ModelConfig(this,options,imageReserve,outputReserve);}
    private ModelConfig(ModelConfig source,boolean accountImages){
        routePlan=source.routePlan;purposePlans=source.purposePlans;
        baseUrl=source.baseUrl;apiKey=source.apiKey;model=source.model;provider=source.provider;accountModel=source.accountModel;
        localCaptureAllowed=source.localCaptureAllowed;localOcrAllowed=source.localOcrAllowed;sendScreenshots=source.sendScreenshots;supportsImageInput=accountImages;cleartextConsent=source.cleartextConsent;
        maxSteps=source.maxSteps;requestTimeoutSeconds=source.requestTimeoutSeconds;maxOutputTokens=source.maxOutputTokens;
        capabilityBinding=source.capabilityBinding;catalogEnforced=source.catalogEnforced;reasoning=source.reasoning;imageTokenReserve=source.imageTokenReserve;
    }
    /** Native host display/capture projection only. Inference always rechecks the gateway's exact evidence. */
    public ModelConfig withAccountImageInput(boolean supported){if(!usesAccount())throw new IllegalArgumentException("账号图片能力只能投影到账号连接");return new ModelConfig(this,supported);}
    private ModelConfig(ModelConfig source,int outputLimit){
        routePlan=source.routePlan;purposePlans=source.purposePlans;
        baseUrl=source.baseUrl;apiKey=source.apiKey;model=source.model;provider=source.provider;accountModel=source.accountModel;
        localCaptureAllowed=source.localCaptureAllowed;localOcrAllowed=source.localOcrAllowed;sendScreenshots=source.sendScreenshots;supportsImageInput=source.supportsImageInput;cleartextConsent=source.cleartextConsent;
        maxSteps=source.maxSteps;requestTimeoutSeconds=source.requestTimeoutSeconds;maxOutputTokens=outputLimit;
        capabilityBinding=source.capabilityBinding;catalogEnforced=source.catalogEnforced;reasoning=source.reasoning;imageTokenReserve=source.imageTokenReserve;
    }
    /** Per-request reduction only. It does not save settings, grant capacity or raise the owner's ceiling. */
    public ModelConfig withRequestOutputLimit(int limit){
        if(limit<1||limit>maxOutputTokens)throw new IllegalArgumentException("请求输出上限必须为1至本机已配置上限，不会自动提高预算");
        if(usesAccount())throw new IllegalArgumentException("此账号协议未接入逐请求输出参数，不能假装执行该限制");
        return new ModelConfig(this,limit);
    }
    private ModelConfig(ModelConfig source,ModelBinding binding,ReasoningOptions reasoning,int imageTokenReserve,boolean catalogEnforced){
        routePlan=source.routePlan;purposePlans=source.purposePlans;
        if(source.usesAccount())throw new IllegalArgumentException("套餐账号不能伪装为API目录连接");
        String protocol=switch(binding.protocol()){case OPENAI_CHAT->"openai_chat";case OPENAI_RESPONSES->"openai_responses";case ANTHROPIC->"anthropic";case GEMINI->"gemini";};
        if(!protocol.equals(source.provider))throw new IllegalArgumentException("目录与请求协议不一致");
        baseUrl=source.baseUrl;apiKey=source.apiKey;model=source.model;provider=source.provider;accountModel=null;
        localCaptureAllowed=source.localCaptureAllowed;localOcrAllowed=source.localOcrAllowed;sendScreenshots=source.sendScreenshots;supportsImageInput=source.supportsImageInput;cleartextConsent=source.cleartextConsent;
        maxSteps=source.maxSteps;requestTimeoutSeconds=source.requestTimeoutSeconds;maxOutputTokens=source.maxOutputTokens;
        capabilityBinding=java.util.Objects.requireNonNull(binding);this.catalogEnforced=catalogEnforced;this.reasoning=java.util.Objects.requireNonNull(reasoning);
        if(imageTokenReserve<0||imageTokenReserve>2000000)throw new IllegalArgumentException("图片预留需在0–2000000之间，0表示尚未配置");
        this.imageTokenReserve=imageTokenReserve;
    }
    public ModelConfig withNativeCatalog(ModelBinding binding,ReasoningOptions reasoning){return withNativeCatalog(binding,reasoning,0);}
    public ModelConfig withNativeCatalog(ModelBinding binding,ReasoningOptions reasoning,int imageTokenReserve){return new ModelConfig(this,binding,reasoning,imageTokenReserve,true);}
    /** Native identity only; this does not assert any model capability or enable directory enforcement. */
    public ModelConfig withApiConnection(ModelBinding binding){return new ModelConfig(this,binding,ReasoningOptions.DEFAULT,0,false);}
    private ModelConfig retainCatalog(ModelConfig next){next=next.withLocalObservation(localCaptureAllowed,localOcrAllowed).withRoutePlan(routePlan).withPurposePlans(purposePlans);return capabilityBinding==null?next:catalogEnforced?next.withNativeCatalog(capabilityBinding,reasoning,imageTokenReserve):next.withApiConnection(capabilityBinding);}
    public boolean usesAccount(){return accountModel!=null;}
    public ModelConfig withAccountModel(AccountModelRef reference){return new ModelConfig(this,reference,maxSteps);}
    public ModelConfig withStepCeiling(int ceiling){return usesAccount()?new ModelConfig(this,accountModel,ceiling):retainCatalog(new ModelConfig(baseUrl,apiKey,model,sendScreenshots,ceiling,requestTimeoutSeconds,provider,maxOutputTokens,true,cleartextConsent,supportsImageInput));}
    public ModelConfig withModel(String selected){if(usesAccount()){if(!model.equals(selected))throw new IllegalArgumentException("账号模型必须在账号页从当前目录重新选择，不能自动切换模型");return this;}ModelConfig next=new ModelConfig(baseUrl,apiKey,selected,sendScreenshots,maxSteps,requestTimeoutSeconds,provider,maxOutputTokens,true,cleartextConsent,model.equals(selected==null?"":selected.trim())&&supportsImageInput).withLocalObservation(localCaptureAllowed,localOcrAllowed);return model.equals(next.model)?retainCatalog(next):capabilityBinding==null?next:catalogEnforced?next.withNativeCatalog(capabilityBinding,ReasoningOptions.DEFAULT):next.withApiConnection(capabilityBinding);}
    public ModelConfig withCleartextConsent(String consent){if(usesAccount())return this;return retainCatalog(new ModelConfig(baseUrl,apiKey,model,sendScreenshots,maxSteps,requestTimeoutSeconds,provider,maxOutputTokens,true,consent,supportsImageInput));}
    public ModelConfig withOwnerImageInput(boolean supported){if(usesAccount())return this;return retainCatalog(new ModelConfig(baseUrl,apiKey,model,sendScreenshots,maxSteps,requestTimeoutSeconds,provider,maxOutputTokens,true,cleartextConsent,supported));}
    private ModelConfig(ModelConfig source,boolean capture,boolean ocr){
        routePlan=source.routePlan;purposePlans=source.purposePlans;
        baseUrl=source.baseUrl;apiKey=source.apiKey;model=source.model;provider=source.provider;accountModel=source.accountModel;
        sendScreenshots=source.sendScreenshots;localCaptureAllowed=capture;localOcrAllowed=ocr;
        supportsImageInput=source.supportsImageInput;cleartextConsent=source.cleartextConsent;
        maxSteps=source.maxSteps;requestTimeoutSeconds=source.requestTimeoutSeconds;maxOutputTokens=source.maxOutputTokens;
        capabilityBinding=source.capabilityBinding;catalogEnforced=source.catalogEnforced;reasoning=source.reasoning;imageTokenReserve=source.imageTokenReserve;
    }
    private ModelConfig(ModelConfig source,dev.pocketengine.runtime.models.ModelRoutePlan plan){this(source,plan,source.purposePlans);}
    private ModelConfig(ModelConfig source,dev.pocketengine.runtime.models.ModelRoutePlan plan,java.util.Map<String,dev.pocketengine.runtime.models.ModelRoutePlan> purposes){
        routePlan=plan;purposePlans=java.util.Map.copyOf(purposes);baseUrl=source.baseUrl;apiKey=source.apiKey;model=source.model;provider=source.provider;accountModel=source.accountModel;
        sendScreenshots=source.sendScreenshots;localCaptureAllowed=source.localCaptureAllowed;localOcrAllowed=source.localOcrAllowed;
        supportsImageInput=source.supportsImageInput;cleartextConsent=source.cleartextConsent;maxSteps=source.maxSteps;requestTimeoutSeconds=source.requestTimeoutSeconds;maxOutputTokens=source.maxOutputTokens;
        capabilityBinding=source.capabilityBinding;catalogEnforced=source.catalogEnforced;reasoning=source.reasoning;imageTokenReserve=source.imageTokenReserve;
    }
    public ModelConfig withPurposePlans(java.util.Map<String,dev.pocketengine.runtime.models.ModelRoutePlan> plans){return new ModelConfig(this,routePlan,plans);}
    public ModelConfig forPurpose(String purpose){var plan=purposePlans.get(purpose);if(plan!=null)return plan.primary().withRoutePlan(plan);if(routePlan!=null&&!routePlan.purpose.equals(purpose)&&purposePlans.containsKey("DEFAULT")){ModelConfig exact=purposePlans.get("DEFAULT").primary();return exact.withRoutePlan(dev.pocketengine.runtime.models.ModelRoutePlan.exact(exact,purpose,"APP_OWNER"));}return this;}
    public ModelConfig withRoutePlan(dev.pocketengine.runtime.models.ModelRoutePlan plan){return new ModelConfig(this,plan);}
    public ModelConfig withLocalObservation(boolean capture,boolean ocr){return new ModelConfig(this,capture,ocr);}
    public static ModelConfig fromJson(JSONObject j){if(j.has("purposePlans")||j.has("routePlan")||j.has("modelTarget")||j.has("nativeRef")||j.has("accountModel")||j.has("capabilityBinding")||j.has("catalogEnforced")||j.has("reasoning")||j.has("imageTokenReserve"))throw new IllegalArgumentException("精确模型选择与推理设置只能在本机模型页配置");if(j.has("supportsImageInput")&&!(j.opt("supportsImageInput") instanceof Boolean))throw new IllegalArgumentException("图像输入能力必须是明确的本机布尔配置");return new ModelConfig(j.optString("baseUrl"),j.optString("apiKey"),j.optString("model"),j.optBoolean("sendScreenshots",true),j.optInt("maxSteps",20),j.optInt("requestTimeoutSeconds",60),j.optString("provider",DEFAULT_PROVIDER),j.optInt("maxOutputTokens",4096)).withCleartextConsent(j.optString("cleartextConsent")).withOwnerImageInput(j.optBoolean("supportsImageInput",false)).withLocalObservation(j.optBoolean("localCaptureAllowed",j.optBoolean("sendScreenshots",true)),j.optBoolean("localOcrAllowed",j.optBoolean("sendScreenshots",true)));}
    /** Empty means HTTPS or explicitly recognized device-loopback; no DNS resolution grants an exemption. */
    public static String cleartextConsentFor(String provider,String baseUrl){
        try{java.net.URI uri=new java.net.URI(baseUrl);String scheme=uri.getScheme(),host=uri.getHost();if(!"http".equalsIgnoreCase(scheme)||host==null)return "";host=host.toLowerCase(java.util.Locale.ROOT);if(host.equals("localhost")||host.equals("127.0.0.1")||host.equals("[::1]")||host.equals("::1"))return "";return provider+"|http://"+host+":"+(uri.getPort()<0?80:uri.getPort());}catch(Exception invalid){return "";}
    }
    public void requireTransportConsent(java.net.URL endpoint){
        String expected=cleartextConsentFor(provider,endpoint.toString());if(!expected.isEmpty()&&!expected.equals(cleartextConsent))throw new IllegalArgumentException("远端或局域网HTTP会明文传输API凭据、可见文字和屏幕内容；请在本机模型设置确认该目标后再连接，建议使用HTTPS。");
    }
    public JSONObject publicJson(){JSONObject j=new JSONObject();try{j.put("baseUrl",baseUrl).put("model",model).put("provider",provider).put("maxOutputTokens",maxOutputTokens).put("hasApiKey",!apiKey.isEmpty()).put("sendScreenshots",sendScreenshots).put("localCaptureAllowed",localCaptureAllowed).put("localOcrAllowed",localOcrAllowed).put("supportsImageInput",supportsImageInput).put("imageInputCapabilitySource",usesAccount()?(supportsImageInput?"selected_account_catalog":"account_capability_unknown"):catalogEnforced?"native_catalog":supportsImageInput?"owner_configured":"not_configured").put("maxSteps",maxSteps).put("requestTimeoutSeconds",requestTimeoutSeconds).put("cleartextConsent",cleartextConsent).put("modelRoute",usesAccount()?"account":"api_key");if(routePlan!=null)j.put("modelRoutePlan",routePlan.toJson());if(usesAccount())j.put("nativeRef",accountModel.toJson()).put("supportsOutputTokenLimit",false).put("maxOutputTokensRole","local_admission_reserve_not_sent").put("reasoning",reasoning.toJson()).put("imageTokenReserve",imageTokenReserve);if(capabilityBinding!=null)j.put("capabilityBinding",capabilityBinding.toJson()).put("catalogEnforced",catalogEnforced).put("reasoning",reasoning.toJson()).put("imageTokenReserve",imageTokenReserve);}catch(Exception ignored){}return j;}
    /** Immutable reviewed catalog selection, never parsed from a model response or generic settings request. */
    public static final class AccountModelRef {
        public final String provider,accountId,modelId;
        public final long accountVersion;
        private AccountModelRef(String accountId,long version,String model){provider=ACCOUNT_PROVIDER;this.accountId=accountId;accountVersion=version;modelId=model;}
        public static AccountModelRef fromJson(JSONObject reference){
            try{
                java.util.Set<String> keys=new java.util.HashSet<>();java.util.Iterator<String> it=reference.keys();while(it.hasNext())keys.add(it.next());
                if(!keys.equals(Set.of("provider","accountId","accountVersion","modelId"))||!ACCOUNT_PROVIDER.equals(reference.getString("provider")))throw new IllegalArgumentException();
                Object version=reference.get("accountVersion");String id=reference.getString("accountId"),model=reference.getString("modelId");
                if(!(version instanceof Integer||version instanceof Long)||((Number)version).longValue()<1||!id.matches("[A-Za-z0-9_-]{1,160}")||!model.matches("[A-Za-z0-9_.:-]{1,160}"))throw new IllegalArgumentException();
                return new AccountModelRef(id,((Number)version).longValue(),model);
            }catch(Exception invalid){throw new IllegalArgumentException("账号模型引用无效，请刷新账号目录并重新选择");}
        }
        public JSONObject toJson(){JSONObject value=new JSONObject();try{value.put("provider",provider).put("accountId",accountId).put("accountVersion",accountVersion).put("modelId",modelId);}catch(Exception impossible){throw new IllegalStateException(impossible);}return value;}
        /** Native version advancement only; account and model identity cannot change. */
        public AccountModelRef withVersion(long version){if(version<accountVersion)throw new IllegalArgumentException("账号版本不能倒退");return new AccountModelRef(accountId,version,modelId);}
        public boolean sameModel(AccountModelRef other){return other!=null&&provider.equals(other.provider)&&accountId.equals(other.accountId)&&modelId.equals(other.modelId);}
        public boolean matches(AccountModelRef other){return other!=null&&provider.equals(other.provider)&&accountId.equals(other.accountId)&&accountVersion==other.accountVersion&&modelId.equals(other.modelId);}
        @Override public String toString(){return "AccountModelRef[public identity]";}
    }

}
