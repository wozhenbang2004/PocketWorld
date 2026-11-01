package dev.pocketengine.store;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.runtime.models.ModelBinding;
import dev.pocketengine.runtime.models.ApiConnectionProfile;
import org.json.JSONArray;
import dev.pocketengine.runtime.models.ModelCatalogRoutes;
import dev.pocketengine.runtime.models.ReasoningOptions;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

public final class SettingsStore {
    private static final String ALIAS="pocketengine.model-key.v1";
    // Android commits to memory before reporting the disk result. Never consume that memory as a confirmed selection.
    private static final java.util.Set<SharedPreferences> UNCERTAIN=java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private final SharedPreferences prefs;
    private final ApiSecrets apiSecrets;
    public interface ApiSecrets { String get(String slot)throws Exception;void put(String slot,String value)throws Exception; }
    private static final String API_PROFILES="apiConnections";
    private record Profiles(java.util.LinkedHashMap<String,ApiConnectionProfile> rows,String selected,long selectionRevision){}
    public interface ImageCapabilitySource { boolean supported(ModelConfig config) throws Exception; }
    private ImageCapabilitySource imageCapabilities;
    public SettingsStore(Context context) { this(context.getSharedPreferences("private_settings",Context.MODE_PRIVATE),new SecretVault(context)); }
    SettingsStore(SharedPreferences prefs) { this(prefs,null); }
    SettingsStore(SharedPreferences prefs,ApiSecrets secrets) { this.prefs=java.util.Objects.requireNonNull(prefs);apiSecrets=secrets; }
    public static final class PersistenceUncertain extends IllegalStateException {
        PersistenceUncertain(){super("本机模型设置的写入结果不确定；请检查存储并重新启动应用后核对选择。没有把未确认的内存值当作保存成功。");}
    }
    private void requireConfirmed(){synchronized(UNCERTAIN){if(UNCERTAIN.contains(prefs))throw new PersistenceUncertain();}}
    private void commitConfirmed(SharedPreferences.Editor edit){
        synchronized(UNCERTAIN){if(UNCERTAIN.contains(prefs))throw new PersistenceUncertain();UNCERTAIN.add(prefs);}
        try{if(edit.commit()){synchronized(UNCERTAIN){UNCERTAIN.remove(prefs);}return;}}catch(RuntimeException failure){/* The disk outcome can no longer be established here. */}
        throw new PersistenceUncertain();
    }
    /** Application singleton only; current facts may change without rewriting private model settings. */
    public synchronized void installImageCapabilities(ImageCapabilitySource source) { imageCapabilities=java.util.Objects.requireNonNull(source); }
    public synchronized ModelConfig get() throws Exception {
        ModelConfig selected=getSelected();
        return selected.withLocalObservation(prefs.getBoolean("localCaptureAllowed",selected.sendScreenshots),prefs.getBoolean("localOcrAllowed",selected.sendScreenshots));
    }
    /** Local capture/OCR consent is independent of a model's pixel-upload setting. */
    public synchronized void saveLocalObservation(boolean capture,boolean ocr)throws Exception {
        requireConfirmed();commitConfirmed(prefs.edit().putBoolean("localCaptureAllowed",capture).putBoolean("localOcrAllowed",ocr));
    }
    private ModelConfig getSelected() throws Exception {
        requireConfirmed();
        if("account".equals(prefs.getString("modelRoute","api_key"))){
            ModelConfig.AccountModelRef ref=ModelConfig.AccountModelRef.fromJson(new JSONObject(prefs.getString("accountModelRef","")));
            return new ModelConfig("","","",prefs.getBoolean("sendScreenshots",true),prefs.getInt("maxSteps",20),prefs.getInt("requestTimeoutSeconds",60),ModelConfig.DEFAULT_PROVIDER,accountOutputReserve()).withAccountModel(ref).withAccountOptions(ReasoningOptions.fromJson(new JSONObject(prefs.getString("accountReasoning",ReasoningOptions.DEFAULT.toJson().toString()))),prefs.getInt("accountImageReserve",0),accountOutputReserve());
        }
        return getApiKeyConfig();
    }
    /** Native status lookup; never decrypts a retained API key. */
    public synchronized ModelConfig.AccountModelRef selectedAccountModel() throws Exception {
        requireConfirmed();
        return "account".equals(prefs.getString("modelRoute","api_key"))
            ?ModelConfig.AccountModelRef.fromJson(new JSONObject(prefs.getString("accountModelRef",""))):null;
    }
    /** Separate retained API configuration: choosing an account does not decrypt or erase this key. */
    private ModelConfig getApiKeyConfig() throws Exception {
        requireConfirmed();
        Profiles profiles=profiles();
        if(profiles!=null){ApiConnectionProfile selected=profiles.rows.get(profiles.selected);if(selected==null)return unconfiguredApi();return selected.enabled?materialize(selected,true):selected.configuration;}
        String key="", stored=prefs.getString("encryptedKey","");
        if (!stored.isEmpty()) key=decrypt(stored);
        ModelConfig config=new ModelConfig(prefs.getString("baseUrl",""),key,prefs.getString("model",""),prefs.getBoolean("sendScreenshots",true),prefs.getInt("maxSteps",20),prefs.getInt("requestTimeoutSeconds",60),prefs.getString("provider",ModelConfig.DEFAULT_PROVIDER),prefs.getInt("maxOutputTokens",4096)).withCleartextConsent(prefs.getString("cleartextConsent","")).withOwnerImageInput(prefs.getBoolean("supportsImageInput",false));
        ModelBinding binding=apiCatalogBinding();
        if(binding!=null){
            ReasoningOptions reasoning=ReasoningOptions.fromJson(new JSONObject(prefs.getString("modelReasoning",ReasoningOptions.DEFAULT.toJson().toString())));
            config=config.withOwnerImageInput(false).withNativeCatalog(binding,reasoning,prefs.getInt("modelImageTokenReserve",0));
            if(imageCapabilities!=null)config=config.withOwnerImageInput(imageCapabilities.supported(config));
        }
        return config;
    }
    public synchronized ModelConfig apiConfigForCatalog() throws Exception { return getApiKeyConfig(); }
    /** Metadata only: refresh fences never decrypt a credential. */
    public synchronized ModelBinding apiCatalogBinding() throws Exception {
        requireConfirmed();
        Profiles profiles=profiles();if(profiles!=null){ApiConnectionProfile selected=profiles.rows.get(profiles.selected);return selected!=null&&selected.configuration.catalogEnforced?selected.configuration.capabilityBinding:null;}
        String value=prefs.getString("apiCatalogBinding","");
        return value.isEmpty()?null:ModelBinding.fromJson(new JSONObject(value));
    }
    /** Native exact credential comparison; no credential/digest is exposed in metadata or diagnostics. */
    public synchronized boolean matchesApiCatalogRoute(ModelConfig captured) throws Exception {
        requireConfirmed();Profiles profiles=profiles();
        if(profiles!=null){
            ApiConnectionProfile row=captured.capabilityBinding==null?null:profiles.rows.get(captured.capabilityBinding.connectionId());
            if(row==null||!row.enabled||!captured.capabilityBinding.equals(row.configuration.capabilityBinding)||captured.catalogEnforced!=row.configuration.catalogEnforced)return false;
            ModelConfig current=materialize(row,false);return captured.provider.equals(current.provider)&&captured.baseUrl.equals(current.baseUrl)&&captured.apiKey.equals(current.apiKey);
        }
        if(captured.capabilityBinding==null||!captured.capabilityBinding.equals(apiCatalogBinding())
                ||!captured.provider.equals(prefs.getString("provider",ModelConfig.DEFAULT_PROVIDER))||!captured.baseUrl.equals(prefs.getString("baseUrl","")))return false;
        String encrypted=prefs.getString("encryptedKey","");
        return captured.apiKey.equals(encrypted.isEmpty()?"":decrypt(encrypted));
    }
    public synchronized ModelConfig merged(JSONObject json) throws Exception {
        requireConfirmed();
        ModelConfig old;
        if(json.has("nativeRef")||json.has("accountModel")||json.has("modelRoute")||json.has("capabilityBinding")||json.has("catalogEnforced")||json.has("reasoning")||json.has("imageTokenReserve"))throw new IllegalArgumentException("请在本机模型/账号页管理精确选择与推理参数；普通设置只配置API密钥连接");
        try{old=getApiKeyConfig();}catch(Exception unavailable){
            if(!json.optBoolean("clearApiKey",false)&&json.optString("apiKey").trim().isEmpty())throw new IllegalStateException("已保存的密钥无法读取，请重新填写或清除密钥");
            old=new ModelConfig(prefs.getString("baseUrl",""),"",prefs.getString("model",""),prefs.getBoolean("sendScreenshots",true),prefs.getInt("maxSteps",20),prefs.getInt("requestTimeoutSeconds",60),prefs.getString("provider",ModelConfig.DEFAULT_PROVIDER),prefs.getInt("maxOutputTokens",4096));
        }
        String key=json.optBoolean("clearApiKey",false)?"":json.optString("apiKey").trim();
        String baseUrl=json.optString("baseUrl",old.baseUrl).trim();
        String provider=json.optString("provider",old.provider);
        String model=json.optString("model",old.model).trim();
        if(json.has("supportsImageInput")&&!(json.opt("supportsImageInput") instanceof Boolean))throw new IllegalArgumentException("请明确配置当前连接和模型的图像输入能力");
        boolean imageSupport=json.has("supportsImageInput")?json.getBoolean("supportsImageInput"):old.provider.equals(provider)&&old.baseUrl.equals(baseUrl)&&old.model.equals(model)&&old.supportsImageInput;
        if(key.isEmpty()&&!json.optBoolean("clearApiKey",false)&&old.provider.equals(provider)&&sameOrigin(old.baseUrl,baseUrl))key=old.apiKey;
        String consent=json.has("cleartextConsent")?json.optString("cleartextConsent"):old.provider.equals(provider)&&sameOrigin(old.baseUrl,baseUrl)?old.cleartextConsent:"";
        if(ModelConfig.cleartextConsentFor(provider,baseUrl).isEmpty())consent="";
        ModelConfig merged=new ModelConfig(baseUrl,key,model,json.optBoolean("sendScreenshots",old.sendScreenshots),json.optInt("maxSteps",old.maxSteps),json.optInt("requestTimeoutSeconds",old.requestTimeoutSeconds),provider,json.optInt("maxOutputTokens",old.maxOutputTokens)).withCleartextConsent(consent).withOwnerImageInput(imageSupport);
        return old.capabilityBinding!=null&&old.provider.equals(provider)&&old.baseUrl.equals(baseUrl)&&old.apiKey.equals(key)
            ?old.catalogEnforced?merged.withNativeCatalog(old.capabilityBinding,old.model.equals(model)?old.reasoning:ReasoningOptions.DEFAULT,old.model.equals(model)?old.imageTokenReserve:0):merged.withApiConnection(old.capabilityBinding):merged;
    }
    public synchronized ModelConfig save(JSONObject json) throws Exception {
        requireConfirmed();
        Profiles profileState=profiles();
        if(profileState!=null){
            ApiConnectionProfile selected=profileState.rows.get(profileState.selected);
            if(selected==null)throw new IllegalStateException("请在模型页新增并明确选择一项API连接");
            saveApiConnectionInternal(selected.id(),selected.revision,selected.name,json,true);
            return getApiKeyConfig();
        }
        ModelConfig previous=null;try{previous=getApiKeyConfig();}catch(Exception unreadable){/* merged() requires replacement/clear before a write. */}
        ModelConfig c=merged(json); validate(c);
        ModelBinding binding=apiCatalogBinding();
        boolean changed=binding!=null&&(previous==null||!previous.provider.equals(c.provider)||!previous.baseUrl.equals(c.baseUrl)||!previous.apiKey.equals(c.apiKey));
        if(changed){
            boolean routeChanged=previous==null||!previous.provider.equals(c.provider)||!previous.baseUrl.equals(c.baseUrl);
            boolean credentialChanged=previous==null||!previous.apiKey.equals(c.apiKey);
            binding=new ModelBinding(binding.connectionId(),c.provider,ModelCatalogRoutes.protocol(c.provider),routeChanged?ModelCatalogRoutes.defaultFormat(c.provider):binding.format(),routeChanged?ModelCatalogRoutes.modelsEndpoint(c):binding.modelsEndpoint(),binding.credentialId(),Math.addExact(binding.credentialRevision(),credentialChanged?1:0),binding.installationId(),Math.addExact(binding.installationRevision(),routeChanged?1:0),!ModelConfig.cleartextConsentFor(c.provider,c.baseUrl).isEmpty());
        }
        String encrypted=c.apiKey.isEmpty()?"":encrypt(c.apiKey);
        SharedPreferences.Editor edit=prefs.edit().putString("modelRoute","api_key").remove("accountModelRef").putString("baseUrl",c.baseUrl).putString("model",c.model).putString("provider",c.provider).putString("cleartextConsent",c.cleartextConsent).putInt("maxOutputTokens",c.maxOutputTokens).putString("encryptedKey",encrypted).putBoolean("sendScreenshots",c.sendScreenshots).putBoolean("supportsImageInput",c.supportsImageInput).putInt("maxSteps",c.maxSteps).putInt("requestTimeoutSeconds",c.requestTimeoutSeconds);
        if(binding!=null){edit.putString("apiCatalogBinding",binding.toJson().toString());if(changed||previous==null||!previous.model.equals(c.model))edit.putString("modelReasoning",ReasoningOptions.DEFAULT.toJson().toString()).putInt("modelImageTokenReserve",0);}
        commitConfirmed(edit);
        return getApiKeyConfig();
    }
    /** Native owner opt-in. A failed/missing catalog never changes route or guesses capabilities. */
    public synchronized ModelConfig configureApiCatalog(ModelBinding expected,ModelBinding.Format format,URI modelsEndpoint) throws Exception {
        ModelConfig config=getApiKeyConfig();validate(config);
        Profiles profiles=profiles();
        if(profiles!=null){
            ApiConnectionProfile row=selectedProfile(profiles);if(!row.enabled)throw new IllegalStateException("当前API连接已停用，请先明确启用");ModelBinding current=row.configuration.catalogEnforced?row.configuration.capabilityBinding:null;
            if(!java.util.Objects.equals(expected,current))throw new IllegalStateException("模型目录连接已变化，请刷新");
            ModelCatalogRoutes.requireSameOrigin(config,modelsEndpoint);ModelBinding identity=row.configuration.capabilityBinding;
            if(current!=null&&current.format()==format&&current.modelsEndpoint().equals(modelsEndpoint))return config;
            ModelBinding next=new ModelBinding(identity.connectionId(),config.provider,ModelCatalogRoutes.protocol(config.provider),format,modelsEndpoint,identity.credentialId(),identity.credentialRevision(),identity.installationId(),Math.addExact(identity.installationRevision(),1),!ModelConfig.cleartextConsentFor(config.provider,config.baseUrl).isEmpty());
            ModelConfig stored=withoutSecret(config).withNativeCatalog(next,ReasoningOptions.DEFAULT,0);
            profiles.rows.put(row.id(),new ApiConnectionProfile(row.name,Math.addExact(row.revision,1),row.enabled,row.secretSlot,stored));
            commitProfiles(profiles,Math.addExact(profiles.selectionRevision,1),false);return getApiKeyConfig();
        }
        ModelBinding current=apiCatalogBinding();
        if(!java.util.Objects.equals(expected,current))throw new IllegalStateException("模型目录连接已变化，请刷新");
        ModelCatalogRoutes.requireSameOrigin(config,modelsEndpoint);
        ModelBinding next;
        if(current==null){
            next=new ModelBinding("api-"+java.util.UUID.randomUUID(),config.provider,ModelCatalogRoutes.protocol(config.provider),format,modelsEndpoint,"key-"+java.util.UUID.randomUUID(),1,"connection-"+java.util.UUID.randomUUID(),1,!ModelConfig.cleartextConsentFor(config.provider,config.baseUrl).isEmpty());
        }else if(current.format()==format&&current.modelsEndpoint().equals(modelsEndpoint))return config;
        else next=new ModelBinding(current.connectionId(),config.provider,ModelCatalogRoutes.protocol(config.provider),format,modelsEndpoint,current.credentialId(),current.credentialRevision(),current.installationId(),Math.addExact(current.installationRevision(),1),!ModelConfig.cleartextConsentFor(config.provider,config.baseUrl).isEmpty());
        commitConfirmed(prefs.edit().putString("apiCatalogBinding",next.toJson().toString()).putString("modelReasoning",ReasoningOptions.DEFAULT.toJson().toString()).putInt("modelImageTokenReserve",0));
        return getApiKeyConfig();
    }
    /** Native exact-selection CAS. An unconfirmed disk result fences all future consumers; it is not a rollback claim. */
    public synchronized ModelConfig selectCatalogModel(ModelBinding expected,String model,int output,ReasoningOptions reasoning,int imageTokenReserve) throws Exception {
        if(!java.util.Objects.equals(expected,apiCatalogBinding())||expected==null)throw new IllegalStateException("模型连接或密钥修订已变化，请刷新");
        new dev.pocketengine.runtime.models.ModelCatalog.ModelKey(expected,model);
        if(output<128||output>32768)throw new IllegalArgumentException("输出上限需在128–32768之间");
        if(imageTokenReserve<0||imageTokenReserve>2000000)throw new IllegalArgumentException("图片预留需在0–2000000之间");
        Profiles profiles=profiles();
        if(profiles!=null){
            ApiConnectionProfile row=selectedProfile(profiles);ModelConfig old=materialize(row,true);
            ModelConfig selected=new ModelConfig(old.baseUrl,"",model,old.sendScreenshots,old.maxSteps,old.requestTimeoutSeconds,old.provider,output).withCleartextConsent(old.cleartextConsent).withNativeCatalog(expected,reasoning,imageTokenReserve);
            profiles.rows.put(row.id(),new ApiConnectionProfile(row.name,Math.addExact(row.revision,1),row.enabled,row.secretSlot,selected));
            commitProfiles(profiles,Math.addExact(profiles.selectionRevision,1),true);return getApiKeyConfig();
        }
        commitConfirmed(prefs.edit().putString("modelRoute","api_key").remove("accountModelRef").putString("model",model).putInt("maxOutputTokens",output).putString("modelReasoning",reasoning.toJson().toString()).putInt("modelImageTokenReserve",imageTokenReserve));
        return getApiKeyConfig();
    }
    /** Native owner only, after the host checks the current account catalog and version. */
    public synchronized ModelConfig selectAccountModel(JSONObject nativeRef) throws Exception {
        requireConfirmed();
        return selectAccountModel(nativeRef,ReasoningOptions.DEFAULT,0,retainedApiOutputReserve());
    }
    /** The original native account workflow has already validated these options against its fresh catalog. */
    public synchronized ModelConfig selectAccountModel(JSONObject nativeRef,ReasoningOptions reasoning,int imageReserve,int outputReserve)throws Exception {
        requireConfirmed();ModelConfig.AccountModelRef ref=ModelConfig.AccountModelRef.fromJson(nativeRef);
        new ModelConfig("","","",false,1,10).withAccountModel(ref).withAccountOptions(reasoning,imageReserve,outputReserve);
        commitConfirmed(prefs.edit().putString("modelRoute","account").putString("accountModelRef",ref.toJson().toString())
                .putString("accountReasoning",reasoning.toJson().toString()).putInt("accountImageReserve",imageReserve).putInt("accountOutputReserve",outputReserve));
        return get();
    }
    /** Native renewal CAS. False means the owner changed selection; never overwrite that choice. */
    public synchronized boolean advanceAccountModel(ModelConfig.AccountModelRef expected,long actualVersion) throws Exception {
        ModelConfig.AccountModelRef advanced=expected.withVersion(actualVersion),current=selectedAccountModel();
        if(current==null||!current.matches(expected))return false;
        if(actualVersion==expected.accountVersion)return true;
        commitConfirmed(prefs.edit().putString("accountModelRef",advanced.toJson().toString()));
        return true;
    }
    /** Explicit owner choice, never invoked automatically after an account failure. */
    public synchronized ModelConfig useApiKeyModel() throws Exception {
        Profiles profiles=profiles();if(profiles!=null&&!selectedProfile(profiles).enabled)throw new IllegalStateException("当前API连接已停用，请明确启用或选择另一项");
        ModelConfig config=getApiKeyConfig();validate(config);
        commitConfirmed(prefs.edit().putString("modelRoute","api_key").remove("accountModelRef"));
        return config;
    }
    /** Explicit native owner page entry. Existing API settings are atomically moved, never reset. */
    public synchronized JSONObject apiConnections()throws Exception {
        requireConfirmed();if(profiles()==null)migrateApiConnections();return apiConnectionsSnapshot();
    }
    /** Read-only public metadata. This method itself never migrates, decrypts or activates a connection. */
    public synchronized JSONObject apiConnectionsSnapshot()throws Exception {
        requireConfirmed();Profiles state=profiles();JSONArray rows=new JSONArray();
        if(state!=null)for(ApiConnectionProfile row:state.rows.values())rows.put(row.publicJson());
        return new JSONObject().put("format","pocketworld.api-connections.v1").put("migrated",state!=null).put("connections",rows)
            .put("selectedConnectionId",state==null?"":state.selected).put("selectionRevision",state==null?0:state.selectionRevision)
            .put("maximumConnections",ApiConnectionProfile.MAX_CONNECTIONS);
    }
    public synchronized long apiSelectionRevision()throws Exception{requireConfirmed();Profiles p=profiles();return p==null?0:p.selectionRevision;}
    /** A settings switch chooses future calls; an existing task keeps its original enabled profile. */
    public synchronized ModelConfig currentForCaptured(ModelConfig captured)throws Exception{
        requireConfirmed();Profiles state=profiles();
        if(state!=null&&!captured.usesAccount()&&captured.capabilityBinding!=null){
            ApiConnectionProfile row=state.rows.get(captured.capabilityBinding.connectionId());
            if(row==null||!row.enabled||!row.configuration.capabilityBinding.equals(captured.capabilityBinding))throw new SecurityException("原API连接已停用或凭据/地址代次已变化，未切换连接");
            return materialize(row,true);
        }return get();
    }
    /** Owner estimate lookup only; returns no key bytes, never selects or materializes a credential. */
    public synchronized ModelConfig apiMetadataForPricing(String connectionId,String model)throws Exception{
        requireConfirmed();Profiles state=profiles();if(state==null)return null;ApiConnectionProfile row=state.rows.get(connectionId);
        return row==null||!row.enabled?null:row.configuration.withModel(model);
    }
    /** No caller-supplied identity, key slot or catalog policy is accepted. Saving never selects another profile. */
    public synchronized JSONObject saveApiConnection(String id,long expectedRevision,String name,JSONObject input)throws Exception{
        return saveApiConnectionInternal(id,expectedRevision,name,input,false);
    }
    private JSONObject saveApiConnectionInternal(String id,long expectedRevision,String name,JSONObject input,boolean useApi)throws Exception{
        requireConfirmed();Profiles state=profiles();if(state==null){migrateApiConnections();state=profiles();}
        ApiConnectionProfile prior=id==null||id.isEmpty()?null:state.rows.get(id);
        if(prior==null&&id!=null&&!id.isEmpty()||prior==null&&expectedRevision!=0||prior!=null&&prior.revision!=expectedRevision)throw new IllegalStateException("API连接已变化，请重新打开原连接");
        if(prior==null&&state.rows.size()>=ApiConnectionProfile.MAX_CONNECTIONS)throw new IllegalStateException("已保存32项API连接，请先管理已有连接");
        String checkedName=ApiConnectionProfile.checkedName(name);
        ModelConfig previous;boolean unreadableCredential=false;
        try{previous=prior==null?unconfiguredApi():materialize(prior,false);}catch(Exception unavailable){
            if(prior==null||!input.optBoolean("clearApiKey",false)&&input.optString("apiKey").trim().isEmpty())throw new IllegalStateException("原API凭据不可读取，请在此连接重新填写或明确清除；未借用其他连接密钥");
            previous=prior.configuration;unreadableCredential=true;
        }
        ModelConfig next=mergeApi(previous,input);validate(next);
        if(!next.apiKey.isEmpty()&&checkedName.contains(next.apiKey))throw new IllegalArgumentException("连接名称不能包含API密钥");
        boolean route=prior==null||!previous.provider.equals(next.provider)||!previous.baseUrl.equals(next.baseUrl),credential=prior==null||unreadableCredential||!previous.apiKey.equals(next.apiKey);
        ModelBinding old=prior==null?null:prior.configuration.capabilityBinding;
        ModelBinding identity=new ModelBinding(old==null?"api-"+java.util.UUID.randomUUID():old.connectionId(),next.provider,ModelCatalogRoutes.protocol(next.provider),route?ModelCatalogRoutes.defaultFormat(next.provider):old.format(),route?ModelCatalogRoutes.modelsEndpoint(next):old.modelsEndpoint(),old==null?"key-"+java.util.UUID.randomUUID():old.credentialId(),old==null?1:Math.addExact(old.credentialRevision(),credential?1:0),old==null?"connection-"+java.util.UUID.randomUUID():old.installationId(),old==null?1:Math.addExact(old.installationRevision(),route?1:0),!ModelConfig.cleartextConsentFor(next.provider,next.baseUrl).isEmpty());
        String slot=prior==null?"":prior.secretSlot;
        if(credential)slot=next.apiKey.isEmpty()?"":profileSecretSlot(identity);
        ModelConfig stored=withoutSecret(next);
        // A failed refresh or a profile switch can never remove a strict catalog contract.
        if(prior!=null&&prior.configuration.catalogEnforced){boolean sameModel=!route&&!credential&&previous.model.equals(next.model);stored=stored.withNativeCatalog(identity,sameModel?previous.reasoning:ReasoningOptions.DEFAULT,sameModel?previous.imageTokenReserve:0);}
        else stored=stored.withApiConnection(identity);
        ApiConnectionProfile saved=new ApiConnectionProfile(checkedName,prior==null?1:Math.addExact(prior.revision,1),prior==null||prior.enabled,slot,stored);
        state.rows.put(saved.id(),saved);long nextRevision=Math.addExact(state.selectionRevision,1);
        profilesDocument(state,nextRevision); // Preflight before writing even the inactive credential slot.
        if(credential&&!slot.isEmpty())putProfileSecret(slot,next.apiKey);
        commitProfiles(state,nextRevision,useApi);return saved.publicJson();
    }
    public synchronized ModelConfig selectApiConnection(String id,long expectedRevision)throws Exception{
        requireConfirmed();Profiles state=requireProfiles();ApiConnectionProfile row=requireProfile(state,id,expectedRevision);
        if(!row.enabled)throw new IllegalStateException("请先明确启用这项API连接");ModelConfig config=materialize(row,true);validate(config);
        commitProfiles(new Profiles(state.rows,id,state.selectionRevision),Math.addExact(state.selectionRevision,1),true);return config;
    }
    public synchronized JSONObject setApiConnectionEnabled(String id,long expectedRevision,boolean enabled)throws Exception{
        requireConfirmed();Profiles state=requireProfiles();ApiConnectionProfile row=requireProfile(state,id,expectedRevision);
        if(row.enabled==enabled)return row.publicJson();
        ModelBinding old=row.configuration.capabilityBinding;
        // Toggling off and back on must not revive a previously captured credential/install generation.
        ModelBinding next=new ModelBinding(old.connectionId(),old.providerId(),old.protocol(),old.format(),old.modelsEndpoint(),old.credentialId(),old.credentialRevision(),old.installationId(),Math.addExact(old.installationRevision(),1),old.cleartextApproved());
        ModelConfig config=row.configuration.catalogEnforced?row.configuration.withNativeCatalog(next,ReasoningOptions.DEFAULT,0):row.configuration.withApiConnection(next);
        ApiConnectionProfile saved=new ApiConnectionProfile(row.name,Math.addExact(row.revision,1),enabled,row.secretSlot,config);state.rows.put(id,saved);
        commitProfiles(state,Math.addExact(state.selectionRevision,1),false);return saved.publicJson();
    }
    private Profiles requireProfiles()throws Exception{Profiles p=profiles();if(p==null)throw new IllegalStateException("请先在本机模型页核对原API连接迁移");return p;}
    private static ApiConnectionProfile requireProfile(Profiles state,String id,long expected){ApiConnectionProfile row=state.rows.get(id);if(row==null||row.revision!=expected)throw new IllegalStateException("API连接已变化，请刷新原记录");return row;}
    private static ApiConnectionProfile selectedProfile(Profiles state){ApiConnectionProfile row=state.rows.get(state.selected);if(row==null)throw new IllegalStateException("请先明确选择一项API连接");return row;}
    private int accountOutputReserve()throws Exception{return prefs.contains("accountOutputReserve")?prefs.getInt("accountOutputReserve",4096):retainedApiOutputReserve();}
    private int retainedApiOutputReserve()throws Exception{Profiles state=profiles();ApiConnectionProfile selected=state==null?null:state.rows.get(state.selected);return selected==null?prefs.getInt("maxOutputTokens",4096):selected.configuration.maxOutputTokens;}
    private ModelConfig unconfiguredApi(){return new ModelConfig("","","",prefs.getBoolean("sendScreenshots",true),prefs.getInt("maxSteps",20),prefs.getInt("requestTimeoutSeconds",60),ModelConfig.DEFAULT_PROVIDER,4096);}
    private ModelConfig materialize(ApiConnectionProfile row,boolean requireEnabled)throws Exception{
        if(requireEnabled&&!row.enabled)throw new IllegalStateException("当前API连接已停用；请明确选择其他已启用连接，没有自动换号");
        String key=row.secretSlot.isEmpty()?"":profileSecret(row.secretSlot);if(!row.secretSlot.isEmpty()&&key.isEmpty())throw new IllegalStateException("此API连接的原凭据不可读取，请在原连接重新填写；未改用其他密钥");
        ModelConfig c=row.configuration,next=new ModelConfig(c.baseUrl,key,c.model,c.sendScreenshots,c.maxSteps,c.requestTimeoutSeconds,c.provider,c.maxOutputTokens).withCleartextConsent(c.cleartextConsent).withOwnerImageInput(c.supportsImageInput);
        if(c.catalogEnforced){next=next.withOwnerImageInput(false).withNativeCatalog(c.capabilityBinding,c.reasoning,c.imageTokenReserve);if(imageCapabilities!=null&&requireEnabled)next=next.withOwnerImageInput(imageCapabilities.supported(next));}
        else next=next.withApiConnection(c.capabilityBinding);return next;
    }
    private static ModelConfig withoutSecret(ModelConfig c){return new ModelConfig(c.baseUrl,"",c.model,c.sendScreenshots,c.maxSteps,c.requestTimeoutSeconds,c.provider,c.maxOutputTokens).withCleartextConsent(c.cleartextConsent).withOwnerImageInput(c.supportsImageInput);}
    private ModelConfig mergeApi(ModelConfig old,JSONObject input)throws Exception{
        for(String key:new String[]{"nativeRef","accountModel","modelRoute","capabilityBinding","catalogEnforced","reasoning","imageTokenReserve","secretSlot","connectionId","credentialId","installationId"})if(input.has(key))throw new SecurityException("原生连接身份与目录策略不能由配置JSON替代");
        String provider=input.optString("provider",old.provider),base=input.optString("baseUrl",old.baseUrl).trim(),model=input.optString("model",old.model).trim();
        if(input.has("supportsImageInput")&&!(input.opt("supportsImageInput") instanceof Boolean))throw new IllegalArgumentException("请明确填写图像输入能力");
        String key=input.optBoolean("clearApiKey",false)?"":input.optString("apiKey").trim();if(key.isEmpty()&&!input.optBoolean("clearApiKey",false)&&old.provider.equals(provider)&&sameOrigin(old.baseUrl,base))key=old.apiKey;
        String consent=input.has("cleartextConsent")?input.optString("cleartextConsent"):old.provider.equals(provider)&&sameOrigin(old.baseUrl,base)?old.cleartextConsent:"";if(ModelConfig.cleartextConsentFor(provider,base).isEmpty())consent="";
        boolean image=input.has("supportsImageInput")?input.getBoolean("supportsImageInput"):old.provider.equals(provider)&&old.baseUrl.equals(base)&&old.model.equals(model)&&old.supportsImageInput;
        return new ModelConfig(base,key,model,input.optBoolean("sendScreenshots",old.sendScreenshots),input.optInt("maxSteps",old.maxSteps),input.optInt("requestTimeoutSeconds",old.requestTimeoutSeconds),provider,input.optInt("maxOutputTokens",old.maxOutputTokens)).withCleartextConsent(consent).withOwnerImageInput(image);
    }
    /** At most two native key versions per credential identity; never overwrite the currently referenced slot before commit. */
    private static String profileSecretSlot(ModelBinding binding)throws Exception{
        byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(binding.credentialId().getBytes(StandardCharsets.UTF_8));
        StringBuilder id=new StringBuilder();for(byte b:digest)id.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        return "model.api."+id+"."+(binding.credentialRevision()&1);
    }
    private String profileSecret(String slot)throws Exception{if(apiSecrets==null)throw new IllegalStateException("原生凭据库尚未挂载");return apiSecrets.get(slot);}
    private void putProfileSecret(String slot,String value)throws Exception{if(apiSecrets==null)throw new IllegalStateException("原生凭据库尚未挂载");apiSecrets.put(slot,value);}
    private Profiles profiles()throws Exception{
        requireConfirmed();String text=prefs.getString(API_PROFILES,"");if(!prefs.contains(API_PROFILES))return null;if(text.isEmpty())throw new IllegalStateException("原API连接资料为空或损坏，未重新初始化");
        if(text.getBytes(StandardCharsets.UTF_8).length>ApiConnectionProfile.MAX_DOCUMENT_BYTES)throw new IllegalStateException("API连接资料超过有界范围，请保留原配置");
        JSONObject root=new JSONObject(text);Object seq=root.opt("selectionRevision");
        if(!"pocketworld.api-connections.v1".equals(root.getString("format"))||!(seq instanceof Integer||seq instanceof Long)||((Number)seq).longValue()<1)throw new IllegalArgumentException("API连接版本不可核对，请保留原配置");
        JSONArray rows=root.getJSONArray("connections");if(rows.length()>ApiConnectionProfile.MAX_CONNECTIONS)throw new IllegalArgumentException("API连接数量超过上限");
        java.util.LinkedHashMap<String,ApiConnectionProfile> profiles=new java.util.LinkedHashMap<>();for(int i=0;i<rows.length();i++){ApiConnectionProfile row=ApiConnectionProfile.fromJson(rows.getJSONObject(i));if(profiles.put(row.id(),row)!=null)throw new IllegalArgumentException("API连接身份重复");}
        String selected=root.getString("selectedConnectionId");if(!selected.isEmpty()&&!profiles.containsKey(selected))throw new IllegalStateException("原选择的API连接不存在，未自动选另一项");
        return new Profiles(profiles,selected,((Number)seq).longValue());
    }
    private String profilesDocument(Profiles state,long selectionRevision)throws Exception{
        JSONArray rows=new JSONArray();for(ApiConnectionProfile row:state.rows.values())rows.put(row.toJson());
        String text=new JSONObject().put("format","pocketworld.api-connections.v1").put("selectionRevision",selectionRevision).put("selectedConnectionId",state.selected).put("connections",rows).toString();
        if(text.getBytes(StandardCharsets.UTF_8).length>ApiConnectionProfile.MAX_DOCUMENT_BYTES)throw new IllegalArgumentException("API连接资料超出本机有界保存范围");
        return text;
    }
    private void commitProfiles(Profiles state,long selectionRevision,boolean useApi)throws Exception{
        SharedPreferences.Editor edit=prefs.edit().putString(API_PROFILES,profilesDocument(state,selectionRevision));
        if(useApi)edit.putString("modelRoute","api_key").remove("accountModelRef");commitConfirmed(edit);
    }
    private void migrateApiConnections()throws Exception{
        if(profiles()!=null)return;
        ModelConfig legacy=getApiKeyConfig();java.util.LinkedHashMap<String,ApiConnectionProfile> rows=new java.util.LinkedHashMap<>();String selected="";
        if(!legacy.baseUrl.isEmpty()||!legacy.model.isEmpty()||!legacy.apiKey.isEmpty()||legacy.capabilityBinding!=null){
            validate(legacy);ModelBinding binding=legacy.capabilityBinding;
            if(binding==null)binding=new ModelBinding("api-"+java.util.UUID.randomUUID(),legacy.provider,ModelCatalogRoutes.protocol(legacy.provider),ModelCatalogRoutes.defaultFormat(legacy.provider),ModelCatalogRoutes.modelsEndpoint(legacy),"key-"+java.util.UUID.randomUUID(),1,"connection-"+java.util.UUID.randomUUID(),1,!ModelConfig.cleartextConsentFor(legacy.provider,legacy.baseUrl).isEmpty());
            String slot=legacy.apiKey.isEmpty()?"":profileSecretSlot(binding);
            ModelConfig config=withoutSecret(legacy);config=legacy.capabilityBinding==null?config.withApiConnection(binding):config.withNativeCatalog(binding,legacy.reasoning,legacy.imageTokenReserve);
            ApiConnectionProfile row=new ApiConnectionProfile("原API连接",1,true,slot,config);rows.put(row.id(),row);selected=row.id();
        }
        String text=profilesDocument(new Profiles(rows,selected,1),1);
        if(!legacy.apiKey.isEmpty())putProfileSecret(rows.get(selected).secretSlot,legacy.apiKey);
        // One native commit chooses the sole authority and removes its old key/settings representation.
        SharedPreferences.Editor edit=prefs.edit().putString(API_PROFILES,text);
        if("account".equals(prefs.getString("modelRoute","api_key"))&&!prefs.contains("accountOutputReserve"))edit.putInt("accountOutputReserve",prefs.getInt("maxOutputTokens",4096));
        for(String key:new String[]{"encryptedKey","baseUrl","model","provider","cleartextConsent","maxOutputTokens","supportsImageInput","apiCatalogBinding","modelReasoning","modelImageTokenReserve"})edit.remove(key);
        commitConfirmed(edit);
    }
    public static void validate(ModelConfig c) throws Exception {
        if(c.usesAccount())return; // Account lifecycle/version is checked atomically by the native gateway.
        URI uri=new URI(c.baseUrl);
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null) throw new IllegalArgumentException("请输入有效的 http/https 模型接口地址");
        if (c.model.isEmpty()) throw new IllegalArgumentException("请填写模型名称");
        c.requireTransportConsent(uri.toURL());
    }
    static boolean sameOrigin(String first,String second) {
        try {
            URI a=new URI(first),b=new URI(second);
            if(a.getScheme()==null||b.getScheme()==null||a.getHost()==null||b.getHost()==null)return false;
            return a.getScheme().equalsIgnoreCase(b.getScheme())&&a.getHost().equalsIgnoreCase(b.getHost())&&port(a)==port(b);
        } catch(Exception invalid) { return false; }
    }
    private static int port(URI uri){return uri.getPort()>=0?uri.getPort():"https".equalsIgnoreCase(uri.getScheme())?443:80;}
    private SecretKey key() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());generator.generateKey();
        }
        return (SecretKey)ks.getKey(ALIAS,null);
    }
    private String encrypt(String plain) throws Exception {
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());
        return Base64.encodeToString(c.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(c.doFinal(plain.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
    }
    private String decrypt(String stored) throws Exception {
        String[] parts=stored.split(":",2);if(parts.length!=2)throw new IllegalStateException("密钥存储不可用，请重新填写密钥");
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));
        return new String(c.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),StandardCharsets.UTF_8);
    }
}
