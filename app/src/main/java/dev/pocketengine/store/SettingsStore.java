package dev.pocketengine.store;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.workspace.OwnerConfigurationBackupSections;
import dev.pocketengine.features.controlplane.WorkProfileResolver;
import dev.pocketengine.runtime.models.ModelBinding;
import dev.pocketengine.runtime.models.ApiConnectionProfile;
import dev.pocketengine.runtime.models.ModelTargetRef;
import dev.pocketengine.features.controlplane.WorkProfile;
import dev.pocketengine.runtime.models.ModelGroup;
import dev.pocketengine.runtime.models.ModelRoutePlan;
import dev.pocketengine.runtime.models.ModelSelectionCoordinator;
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
    private static final java.util.Map<SharedPreferences,ModelSelectionCoordinator> MODEL_SELECTIONS=new java.util.WeakHashMap<>();
    private final SharedPreferences prefs;
    private final ApiSecrets apiSecrets;
    private final ModelSelectionCoordinator modelSelection;
    public interface ApiSecrets { String get(String slot)throws Exception;void put(String slot,String value)throws Exception; }
    private static final String API_PROFILES="apiConnections";
    private record Profiles(java.util.LinkedHashMap<String,ApiConnectionProfile> rows,String selected,long selectionRevision){}
    public interface ImageCapabilitySource { boolean supported(ModelConfig config) throws Exception; }
    private ImageCapabilitySource imageCapabilities;
    public SettingsStore(Context context) { this(context.getSharedPreferences("private_settings",Context.MODE_PRIVATE),new SecretVault(context)); }
    SettingsStore(SharedPreferences prefs) { this(prefs,null); }
    SettingsStore(SharedPreferences prefs,ApiSecrets secrets) { this.prefs=java.util.Objects.requireNonNull(prefs);apiSecrets=secrets;synchronized(MODEL_SELECTIONS){modelSelection=MODEL_SELECTIONS.computeIfAbsent(prefs,ignored->new ModelSelectionCoordinator());} }
    public ModelSelectionCoordinator modelSelection(){return modelSelection;}
    public static final class PersistenceUncertain extends IllegalStateException {
        PersistenceUncertain(){super("本机模型设置的写入结果不确定；请检查存储并重新启动应用后核对选择。没有把未确认的内存值当作保存成功。");}
    }
    private void requireConfirmed(){synchronized(UNCERTAIN){if(UNCERTAIN.contains(prefs))throw new PersistenceUncertain();}}
    private void commitConfirmed(SharedPreferences.Editor edit){commitConfirmed(edit,true);}
    private void commitConfirmed(SharedPreferences.Editor edit,boolean selectionChanged){
        synchronized(UNCERTAIN){if(UNCERTAIN.contains(prefs))throw new PersistenceUncertain();UNCERTAIN.add(prefs);}
        try{if(edit.commit()){synchronized(UNCERTAIN){UNCERTAIN.remove(prefs);}if(selectionChanged)modelSelection.changed();return;}}catch(RuntimeException failure){/* The disk outcome can no longer be established here. */}
        if(selectionChanged)modelSelection.changed();
        throw new PersistenceUncertain();
    }
    /** Explicit owner opt-in only. Reading this preference never starts playback or changes a model. */
    public synchronized boolean taskAnnouncementsEnabled(){requireConfirmed();return prefs.getBoolean("taskAnnouncementsEnabled",false);}
    public synchronized void saveTaskAnnouncementsEnabled(boolean enabled){
        requireConfirmed();commitConfirmed(prefs.edit().putBoolean("taskAnnouncementsEnabled",enabled),false);
    }
    /** Application singleton only; current facts may change without rewriting private model settings. */
    public synchronized void installImageCapabilities(ImageCapabilitySource source) { imageCapabilities=java.util.Objects.requireNonNull(source); }
    public synchronized ModelConfig get() throws Exception {
        modelSelection.requireAvailable();
        ModelConfig selected=getSelected(),global=selected;
        ModelTargetRef target=modelPurposeTarget("MAIN");
        if(target!=null)selected=captureModelTarget(target,"MAIN","APP_OWNER",selected);
        ModelTargetRef summary=modelPurposeTarget("SUMMARY");
        ModelConfig capturedSummary=summary==null?global.withRoutePlan(ModelRoutePlan.exact(global,"SUMMARY","APP_OWNER")):captureModelTarget(summary,"SUMMARY","APP_OWNER",global);
        selected=selected.withPurposePlans(java.util.Map.of("SUMMARY",capturedSummary.routePlan,"DEFAULT",ModelRoutePlan.exact(global,"DEFAULT","APP_OWNER")));
        return selected.withLocalObservation(prefs.getBoolean("localCaptureAllowed",selected.sendScreenshots),prefs.getBoolean("localOcrAllowed",selected.sendScreenshots));
    }
    /** Local capture/OCR consent is independent of a model's pixel-upload setting. */
    public synchronized void saveLocalObservation(boolean capture,boolean ocr)throws Exception {
        requireConfirmed();commitConfirmed(prefs.edit().putBoolean("localCaptureAllowed",capture).putBoolean("localOcrAllowed",ocr));
    }
    /** Native owner preference only; this never changes the selected models or issues a tool grant. */
    public synchronized JSONObject stepRoutingPreferences() throws Exception {
        requireConfirmed();String raw=prefs.getString("stepModelRouting","");
        if(raw.isEmpty())return new JSONObject().put("format","pocketworld.step-routing-preferences.v1")
                .put("enabled",false).put("revision",1).put("planFingerprint","").put("candidateId","").put("minimumSuccesses",2);
        JSONObject value=new JSONObject(raw);
        if(!"pocketworld.step-routing-preferences.v1".equals(value.getString("format"))||!(value.get("enabled") instanceof Boolean)
                ||value.getLong("revision")<1)throw new IllegalStateException("轻量路由设置不可读取，请关闭后重新配置");
        if(value.getBoolean("enabled"))new dev.pocketengine.runtime.models.StepModelRouting.Preference(value.getLong("revision"),
                value.getString("planFingerprint"),value.getString("candidateId"),value.getInt("minimumSuccesses"));
        return value;
    }
    public synchronized void saveStepRoutingPreference(boolean enabled,long expectedRevision,String planFingerprint,String candidateId) throws Exception {
        JSONObject current=stepRoutingPreferences();if(current.getLong("revision")!=expectedRevision)throw new SecurityException("owner_revision_conflict");
        long next=Math.addExact(expectedRevision,1);
        if(enabled)new dev.pocketengine.runtime.models.StepModelRouting.Preference(next,planFingerprint,candidateId,2);
        JSONObject value=new JSONObject().put("format","pocketworld.step-routing-preferences.v1").put("enabled",enabled)
                .put("revision",next).put("planFingerprint",enabled?planFingerprint:"").put("candidateId",enabled?candidateId:"").put("minimumSuccesses",2);
        commitConfirmed(prefs.edit().putString("stepModelRouting",value.toString()));
    }

    /** Reversible agent preferences only. Model routes, grants, credentials and network policy are absent. */
    public synchronized JSONObject agentPreferences()throws Exception {
        requireConfirmed();return new JSONObject().put("enabled",prefs.getBoolean("allowAgentPreferences",false))
                .put("revision",prefs.getLong("agentPreferencesRevision",1)).put("response_detail",prefs.getString("agentResponseDetail","standard"))
                .put("operation_pace",prefs.getString("agentOperationPace","standard"));
    }
    public synchronized JSONObject saveAgentPreferences(boolean enabled,String detail,String pace,long expectedRevision)throws Exception {
        JSONObject old=agentPreferences();if(old.getLong("revision")!=expectedRevision)throw new SecurityException("偏好已由主人改变，请刷新原设置");
        requireAgentPreference("response_detail",detail);requireAgentPreference("operation_pace",pace);long next=Math.addExact(expectedRevision,1);
        commitConfirmed(prefs.edit().putBoolean("allowAgentPreferences",enabled).putString("agentResponseDetail",detail).putString("agentOperationPace",pace).putLong("agentPreferencesRevision",next));
        return new JSONObject().put("previous",old).put("current",agentPreferences()).put("reversible",true);
    }
    public synchronized JSONObject setAgentPreference(String key,String value,long expectedRevision)throws Exception {
        requireAgentPreference(key,value);JSONObject old=agentPreferences();if(!old.getBoolean("enabled"))throw new SecurityException("主人尚未开启对话修改可逆偏好");
        return saveAgentPreferences(true,key.equals("response_detail")?value:old.getString("response_detail"),key.equals("operation_pace")?value:old.getString("operation_pace"),expectedRevision);
    }
    private static void requireAgentPreference(String key,String value){
        boolean valid=key.equals("response_detail")?java.util.Set.of("concise","standard","detailed").contains(value):key.equals("operation_pace")&&java.util.Set.of("fast","standard","careful").contains(value);
        if(!valid)throw new IllegalArgumentException("仅可修改白名单回复详略或普通操作节奏，值须来自当前选项");
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
            if(row==null||!row.enabled||!captured.capabilityBinding.equals(row.configuration.capabilityBinding)||captured.catalogEnforced!=row.configuration.catalogEnforced||!sameCompatibility(captured.compatibility,captured.model.equals(row.configuration.model)?row.configuration.compatibility:null))return false;
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
        if(json.has("compatibility")||json.has("nativeRef")||json.has("accountModel")||json.has("modelRoute")||json.has("capabilityBinding")||json.has("catalogEnforced")||json.has("reasoning")||json.has("imageTokenReserve"))throw new IllegalArgumentException("请在本机模型/账号页管理精确选择与推理参数；普通设置只配置API密钥连接");
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
        commitConfirmed(selectDefaultMain(edit));
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
            ModelConfig selected=new ModelConfig(old.baseUrl,"",model,old.sendScreenshots,old.maxSteps,old.requestTimeoutSeconds,old.provider,output).withCleartextConsent(old.cleartextConsent).withNativeCatalog(expected,reasoning,imageTokenReserve).withProviderCompatibility(old.model.equals(model)?old.compatibility:null);
            profiles.rows.put(row.id(),new ApiConnectionProfile(row.name,Math.addExact(row.revision,1),row.enabled,row.secretSlot,selected));
            commitProfiles(profiles,Math.addExact(profiles.selectionRevision,1),true);return getApiKeyConfig();
        }
        commitConfirmed(selectDefaultMain(prefs.edit().putString("modelRoute","api_key").remove("accountModelRef").putString("model",model).putInt("maxOutputTokens",output).putString("modelReasoning",reasoning.toJson().toString()).putInt("modelImageTokenReserve",imageTokenReserve)));
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
        commitConfirmed(selectDefaultMain(prefs.edit().putString("modelRoute","account").putString("accountModelRef",ref.toJson().toString())
                .putString("accountReasoning",reasoning.toJson().toString()).putInt("accountImageReserve",imageReserve).putInt("accountOutputReserve",outputReserve)));
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
        commitConfirmed(selectDefaultMain(prefs.edit().putString("modelRoute","api_key").remove("accountModelRef")));
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
        requireConfirmed();java.util.Objects.requireNonNull(captured);
        // The account gateway checks this run's actual renewal lineage and current revocation.
        // A later default account/model or new-task option is not that run's identity.
        if(captured.usesAccount())return captured;
        Profiles state=profiles();
        if(state!=null&&captured.capabilityBinding!=null){
            ApiConnectionProfile row=state.rows.get(captured.capabilityBinding.connectionId());
            if(row==null||!row.enabled||!row.configuration.capabilityBinding.equals(captured.capabilityBinding))throw new SecurityException("原API连接已停用或凭据/地址代次已变化，未切换连接");
            ModelConfig live=materialize(row,true);
            if(!captured.provider.equals(live.provider)||!captured.baseUrl.equals(live.baseUrl)||captured.catalogEnforced!=live.catalogEnforced
                    ||!captured.apiKey.isEmpty()&&!java.security.MessageDigest.isEqual(captured.apiKey.getBytes(StandardCharsets.UTF_8),live.apiKey.getBytes(StandardCharsets.UTF_8)))
                throw new SecurityException("原API连接的身份或凭据已变化，未切换连接");
            if(captured.apiKey.equals(live.apiKey))return captured;
            // Exact native metadata lookups may need the original profile's credential materialized.
            ModelConfig resolved=new ModelConfig(captured.baseUrl,live.apiKey,captured.model,captured.sendScreenshots,captured.maxSteps,captured.requestTimeoutSeconds,captured.provider,captured.maxOutputTokens)
                    .withStepCeiling(captured.maxSteps).withCleartextConsent(captured.cleartextConsent).withOwnerImageInput(captured.supportsImageInput)
                    .withProviderCompatibility(captured.compatibility).withLocalObservation(captured.localCaptureAllowed,captured.localOcrAllowed)
                    .withRoutePlan(captured.routePlan).withPurposePlans(captured.purposePlans);
            return captured.catalogEnforced?resolved.withNativeCatalog(captured.capabilityBinding,captured.reasoning,captured.imageTokenReserve):resolved.withApiConnection(captured.capabilityBinding);
        }
        ModelConfig live=getApiKeyConfig();
        if(!captured.provider.equals(live.provider)||!captured.baseUrl.equals(live.baseUrl)||!captured.apiKey.equals(live.apiKey)
                ||!java.util.Objects.equals(captured.capabilityBinding,live.capabilityBinding)||captured.catalogEnforced!=live.catalogEnforced)
            throw new SecurityException("原API连接已变化，未改用新的默认模型");
        return captured;
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
    /** Explicit add-and-use: never edits the previously selected profile or borrows its credential. */
    public synchronized JSONObject addAndSelectApiConnection(String name,JSONObject input)throws Exception{
        return saveApiConnectionInternal("",0,name,input,true);
    }
    /** Unsaved connection test input: add has no previous secret; edit is bound to the reviewed row. */
    public synchronized ModelConfig previewApiConnection(String id,long expectedRevision,JSONObject input)throws Exception{
        requireConfirmed();boolean adding=id==null||id.isEmpty();
        if(adding&&expectedRevision!=0)throw new IllegalStateException("新增连接不能复用旧连接修订");
        ModelConfig old=unconfiguredApi();
        if(!adding){ApiConnectionProfile row=requireProfile(requireProfiles(),id,expectedRevision);
            if(!row.enabled)throw new IllegalStateException("请先明确启用原连接，再测试此连接");
            try{old=materialize(row,true);}catch(Exception unavailable){
                if(!input.optBoolean("clearApiKey",false)&&input.optString("apiKey").trim().isEmpty())throw new IllegalStateException("原API凭据不可读取，请填写替换密钥或明确清除；未借用其他连接密钥");
                old=row.configuration;
            }
        }
        ModelConfig next=mergeApi(old,input);validate(next);
        if(old.capabilityBinding!=null&&old.provider.equals(next.provider)&&old.baseUrl.equals(next.baseUrl)&&old.apiKey.equals(next.apiKey))
            return old.catalogEnforced?next.withNativeCatalog(old.capabilityBinding,old.model.equals(next.model)?old.reasoning:ReasoningOptions.DEFAULT,old.model.equals(next.model)?old.imageTokenReserve:0):next.withApiConnection(old.capabilityBinding);
        return next;
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
        if(useApi&&input.has("compatibility"))throw new SecurityException("兼容档案只能通过本机原API连接的修订保存");
        ModelConfig next=mergeApi(previous,input);
        if(input.has("compatibility"))next=next.withProviderCompatibility(input.isNull("compatibility")?null:dev.pocketengine.runtime.providers.ProviderCompatProfile.reviewed(input.getJSONObject("compatibility"),next.provider,next.model,previous.compatibility));
        validate(next);
        if(!next.apiKey.isEmpty()&&checkedName.contains(next.apiKey))throw new IllegalArgumentException("连接名称不能包含API密钥");
        boolean projection=!sameCompatibility(previous.compatibility,next.compatibility);
        boolean route=prior==null||!previous.provider.equals(next.provider)||!previous.baseUrl.equals(next.baseUrl),credential=prior==null||unreadableCredential||!previous.apiKey.equals(next.apiKey);
        ModelBinding old=prior==null?null:prior.configuration.capabilityBinding;
        ModelBinding identity=new ModelBinding(old==null?"api-"+java.util.UUID.randomUUID():old.connectionId(),next.provider,ModelCatalogRoutes.protocol(next.provider),route?ModelCatalogRoutes.defaultFormat(next.provider):old.format(),route?ModelCatalogRoutes.modelsEndpoint(next):old.modelsEndpoint(),old==null?"key-"+java.util.UUID.randomUUID():old.credentialId(),old==null?1:Math.addExact(old.credentialRevision(),credential?1:0),old==null?"connection-"+java.util.UUID.randomUUID():old.installationId(),old==null?1:Math.addExact(old.installationRevision(),route||projection?1:0),!ModelConfig.cleartextConsentFor(next.provider,next.baseUrl).isEmpty());
        String slot=prior==null?"":prior.secretSlot;
        if(credential)slot=next.apiKey.isEmpty()?"":profileSecretSlot(identity);
        ModelConfig stored=withoutSecret(next);
        // A failed refresh or a profile switch can never remove a strict catalog contract.
        if(prior!=null&&prior.configuration.catalogEnforced){boolean sameModel=!route&&!credential&&previous.model.equals(next.model);stored=stored.withNativeCatalog(identity,sameModel?previous.reasoning:ReasoningOptions.DEFAULT,sameModel?previous.imageTokenReserve:0);}
        else stored=stored.withApiConnection(identity);
        ApiConnectionProfile saved=new ApiConnectionProfile(checkedName,prior==null?1:Math.addExact(prior.revision,1),prior==null||prior.enabled,slot,stored);
        state.rows.put(saved.id(),saved);long nextRevision=Math.addExact(state.selectionRevision,1);
        if(useApi)state=new Profiles(state.rows,saved.id(),state.selectionRevision);
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
    /** Native owner definitions in the existing confirmed preferences transaction. Never stores keys. */
    /** Reusable reference definitions only; saving/importing does not install, grant, select, or execute. */
    public synchronized JSONObject workProfilesSnapshot()throws Exception {
        requireConfirmed();return new JSONObject(workProfilesDocument().toString());
    }
    private JSONObject workProfilesDocument()throws Exception {
        String raw=prefs.getString("workProfiles","");
        JSONObject root=raw.isEmpty()?new JSONObject().put("format","pocketworld.work-profiles.v1").put("revision",0L).put("profiles",new JSONArray()):new JSONObject(raw);
        if(!"pocketworld.work-profiles.v1".equals(root.getString("format"))||root.toString().getBytes(StandardCharsets.UTF_8).length>262144)throw new IllegalStateException("工作配置版本或大小不可读取，保留原记录");
        Object revision=root.get("revision");if(!(revision instanceof Integer||revision instanceof Long)||((Number)revision).longValue()<0)throw new IllegalStateException("工作配置存储版本无效");
        JSONArray rows=root.getJSONArray("profiles");if(rows.length()>WorkProfile.MAX_PROFILES)throw new IllegalStateException("工作配置数量超过边界");
        java.util.Set<String> ids=new java.util.HashSet<>();for(int i=0;i<rows.length();i++)if(!ids.add(WorkProfile.fromJson(rows.getJSONObject(i)).id))throw new IllegalStateException("工作配置身份重复");
        return root;
    }
    public synchronized JSONObject saveWorkProfile(String id,long expectedRevision,String name,JSONObject definition)throws Exception {
        return saveWorkProfile(id,expectedRevision,name,definition,false);
    }
    /** Only a resolution minted by the original native source reader can clear the restore fence. */
    public JSONObject saveReboundWorkProfile(String id,long expectedRevision,String name,WorkProfileResolver.Resolution reviewed,RunControl original)throws Exception {
        ModelSelectionCoordinator.Status pin=modelSelection.status();modelSelection.requireAvailable();original.checkpoint();reviewed.requireReady();reviewed.checkCurrent(original);
        WorkProfile candidate=reviewed.profile();if(candidate.restoredPendingReview||!candidate.id.equals(id)||candidate.revision!=expectedRevision||!candidate.name.equals(name)||!candidate.toJson().getJSONObject("modelPolicies").has("MAIN"))throw new SecurityException("恢复工作配置需重新明确选择本机主模型并审阅全部来源");
        synchronized(this){if(!pin.equals(modelSelection.status()))throw new SecurityException("模型设置在重绑审阅后已变化，请重新核对");original.checkpoint();return saveWorkProfile(id,expectedRevision,name,candidate.toJson(),true);}
    }
    private JSONObject saveWorkProfile(String id,long expectedRevision,String name,JSONObject definition,boolean rebound)throws Exception {
        requireConfirmed();JSONObject root=workProfilesDocument();JSONArray rows=root.getJSONArray("profiles");int found=-1;
        for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getString("profileId").equals(id))found=i;
        if(found<0&&(id!=null&&!id.isEmpty()||expectedRevision!=0)||found>=0&&rows.getJSONObject(found).getLong("revision")!=expectedRevision)throw new SecurityException("工作配置已变化，请重新读取原版本");
        if(found<0&&rows.length()>=WorkProfile.MAX_PROFILES)throw new IllegalStateException("最多保存32份工作配置");
        String actual=found<0?"work-"+java.util.UUID.randomUUID():id;
        JSONObject input=new JSONObject(definition.toString()),prior=found<0?null:rows.getJSONObject(found);
        if(prior==null&&(input.has("restoredPendingReview")||input.has("restoredReferences")))throw new SecurityException("恢复标记只由原归档入口登记");
        if(prior!=null&&prior.has("restoredReferences"))input.put("restoredReferences",new JSONArray(prior.getJSONArray("restoredReferences").toString()));
        if(prior!=null&&prior.optBoolean("restoredPendingReview")){
            if(!rebound&&Boolean.FALSE.equals(input.opt("restoredPendingReview")))throw new SecurityException("请先在主人恢复审阅中重新绑定，普通保存不会激活恢复配置");
            input.put("restoredPendingReview",!rebound);
        }else if(rebound)throw new SecurityException("原恢复配置已变化，请核对保存结果");
        WorkProfile validated=WorkProfile.fromJson(input);
        WorkProfile value=WorkProfile.fromJson(validated.toJson().put("profileId",actual).put("revision",Math.addExact(expectedRevision,1L)).put("name",name));
        if(found<0)rows.put(value.toJson());else rows.put(found,value.toJson());
        commitWorkProfiles(root);return value.toJson();
    }
    /** Removing a reusable definition never edits the frozen references of an existing task/run. */
    public synchronized JSONObject removeWorkProfile(String id,long expectedRevision)throws Exception {
        requireConfirmed();JSONObject root=workProfilesDocument();JSONArray rows=root.getJSONArray("profiles");int found=-1;
        for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getString("profileId").equals(id))found=i;
        if(found<0||rows.getJSONObject(found).getLong("revision")!=expectedRevision)throw new SecurityException("工作配置已变化，请重新读取原版本");
        rows.remove(found);commitWorkProfiles(root);return new JSONObject().put("removed",true).put("profileId",id).put("runsChanged",false).put("permissionsChanged",false);
    }
    private void commitWorkProfiles(JSONObject root)throws Exception {
        root.put("revision",Math.addExact(root.getLong("revision"),1L));String raw=root.toString();if(raw.getBytes(StandardCharsets.UTF_8).length>262144)throw new IllegalArgumentException("工作配置保存范围超限");
        commitConfirmed(prefs.edit().putString("workProfiles",raw));
    }
    /** An explicit Profile target is resolved against original settings, not a newly selected MAIN group. */
    public synchronized ModelConfig captureWorkProfileModel(ModelTargetRef ref,String purpose,String audience)throws Exception {
        requireConfirmed();if(ref==null)throw new IllegalArgumentException("工作配置必须引用准确模型");return captureModelTarget(ref,purpose,audience,getSelected());
    }
    /** Native Profile startup fallback only: freeze the original selected exact binding without
     * resolving unrelated global MAIN/SUMMARY aliases or creating a new default setting. */
    public synchronized ModelConfig captureWorkProfileGlobalDefault()throws Exception {
        requireConfirmed();ModelConfig original=getSelected();
        return original.withPurposePlans(java.util.Map.of()).withRoutePlan(ModelRoutePlan.exact(original,"DEFAULT","APP_OWNER"));
    }
    public synchronized JSONObject modelGroupsSnapshot()throws Exception {
        requireConfirmed();JSONObject root=modelGroupsDocument();return new JSONObject(root.toString());
    }
    private JSONObject modelGroupsDocument()throws Exception {
        String raw=prefs.getString("modelGroups","");
        if(raw.isEmpty())return new JSONObject().put("format","pocketworld.model-groups.v1").put("groups",new JSONArray()).put("purposeBindings",new JSONObject());
        if(raw.getBytes(StandardCharsets.UTF_8).length>131072)throw new IllegalStateException("模型组资料超过有界范围，保留原文");
        JSONObject root=new JSONObject(raw);if(!"pocketworld.model-groups.v1".equals(root.getString("format")))throw new IllegalStateException("模型组版本不可识别");
        JSONArray groups=root.getJSONArray("groups");if(groups.length()>ModelGroup.MAX_GROUPS)throw new IllegalStateException("模型组数量超限");
        java.util.Set<String> ids=new java.util.HashSet<>();for(int i=0;i<groups.length();i++)if(!ids.add(ModelGroup.fromJson(groups.getJSONObject(i)).groupId))throw new IllegalStateException("模型组身份重复");
        JSONObject bindings=root.getJSONObject("purposeBindings");java.util.Iterator<String> names=bindings.keys();while(names.hasNext())ModelTargetRef.fromJson(bindings.getJSONObject(names.next()));return root;
    }
    public synchronized JSONObject saveModelGroup(String id,long expectedRevision,String name,JSONObject definition)throws Exception {
        requireConfirmed();JSONObject root=modelGroupsDocument();JSONArray rows=root.getJSONArray("groups");int found=-1;
        for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getString("groupId").equals(id))found=i;
        if(found<0&&(id!=null&&!id.isEmpty()||expectedRevision!=0)||found>=0&&rows.getJSONObject(found).getLong("revision")!=expectedRevision)throw new IllegalStateException("模型组已变化，请重新打开原记录");
        if(found<0&&rows.length()>=ModelGroup.MAX_GROUPS)throw new IllegalStateException("模型组数量已达32项");
        String actual=found<0?"group-"+java.util.UUID.randomUUID():id;
        JSONObject value=new JSONObject(definition.toString()).put("groupId",actual).put("revision",Math.addExact(expectedRevision,1)).put("name",name);
        ModelGroup checked=ModelGroup.fromJson(value);
        // Refusing missing references at edit time does not delete later unavailable candidates.
        for(ModelGroup.Candidate c:checked.candidates){ModelConfig config=resolveExactModel(c.target(),getSelected());if(!config.apiKey.isEmpty()&&checked.toJson().toString().contains(config.apiKey))throw new SecurityException("模型组说明不得包含原凭据");}
        if(found<0)rows.put(checked.toJson());else rows.put(found,checked.toJson());
        saveModelGroups(root);return checked.toJson();
    }
    public synchronized void selectModelPurpose(String purpose,ModelTargetRef ref)throws Exception {
        requireConfirmed();if(!purpose.matches("MAIN|SUMMARY|RELAY|MEMBER(?:\\.[A-Za-z0-9_-]{1,60})?"))throw new IllegalArgumentException("无效模型用途");
        if(ref!=null)captureModelTarget(ref,purpose,"RELAY".equals(purpose)?"EXTERNAL_RELAY":"APP_OWNER",getSelected());
        JSONObject root=modelGroupsDocument(),bindings=root.getJSONObject("purposeBindings");if(ref==null)bindings.remove(purpose);else bindings.put(purpose,ref.toJson());saveModelGroups(root);
    }
    private void saveModelGroups(JSONObject root)throws Exception {commitConfirmed(editModelGroups(prefs.edit(),root));}
    private SharedPreferences.Editor editModelGroups(SharedPreferences.Editor edit,JSONObject root)throws Exception {
        String raw=root.toString();if(raw.getBytes(StandardCharsets.UTF_8).length>131072)throw new IllegalArgumentException("模型组资料超限");
        return edit.putString("modelGroups",raw).putLong("modelGroupSelectionRevision",Math.addExact(prefs.getLong("modelGroupSelectionRevision",0),1));
    }
    /** An explicit main-model switch uses the newly selected route in the same transaction.
     * Only MAIN is replaced; reusable groups, other purposes and captured run plans are retained. */
    private SharedPreferences.Editor selectDefaultMain(SharedPreferences.Editor edit)throws Exception {
        JSONObject root=modelGroupsDocument(),bindings=root.getJSONObject("purposeBindings");
        if(!bindings.has("MAIN"))return edit;
        bindings.remove("MAIN");return editModelGroups(edit,root);
    }
    public synchronized ModelTargetRef modelPurposeTarget(String purpose)throws Exception {requireConfirmed();JSONObject bindings=modelGroupsDocument().getJSONObject("purposeBindings");JSONObject value=bindings.optJSONObject(purpose);return value==null?null:ModelTargetRef.fromJson(value);}
    /** Explicit native local-service selection. MAIN/SUMMARY group permission never grants external use. */
    public synchronized ModelConfig captureRelaySelection()throws Exception {
        ModelConfig original=getSelected();ModelTargetRef relay=modelPurposeTarget("RELAY");
        // With no explicit binding retain the original exact choice, including an account's denial.
        return relay==null?original:captureModelTarget(relay,"RELAY","EXTERNAL_RELAY",original);
    }
    /** Both counters are monotonic; change-away-and-back still requires the original service restart. */
    public synchronized long relaySelectionRevision()throws Exception {return Math.addExact(apiSelectionRevision(),prefs.getLong("modelGroupSelectionRevision",0));}
    /** Capture once at native run admission. Edits cannot add candidates to this returned plan. */
    public synchronized ModelConfig captureModelTarget(ModelTargetRef ref,String purpose,String audience,ModelConfig capturedFallback)throws Exception {
        requireConfirmed();if(ref==null)return capturedFallback;
        if(ref.kind()==ModelTargetRef.Kind.EXACT){ModelConfig exact=resolveExactModel(ref,capturedFallback);if(exact.usesAccount()&&!"APP_OWNER".equals(audience))throw new SecurityException("CHATGPT_PLAN_APP_ONLY");return exact.withRoutePlan(ModelRoutePlan.exact(exact,purpose,audience));}
        ModelGroup group=null;JSONArray rows=modelGroupsDocument().getJSONArray("groups");for(int i=0;i<rows.length();i++){ModelGroup candidate=ModelGroup.fromJson(rows.getJSONObject(i));if(candidate.groupId.equals(ref.groupId()))group=candidate;}
        if(group==null||group.revision!=ref.groupRevision())throw new IllegalStateException("所选模型组修订已失效，请重新选择；未使用最新版本替代");
        java.util.List<ModelRoutePlan.Candidate> frozen=new java.util.ArrayList<>();
        for(ModelGroup.Candidate c:group.candidates){String reason=!c.enabled()?"candidate_disabled":!group.allowedAccounts.contains(c.target().connectionId())?"account_not_allowed":!c.allowedAudiences().contains(audience)?"audience_not_allowed":!c.allowedPurposes().contains(purpose)&&!(purpose.startsWith("MEMBER.")&&c.allowedPurposes().contains("MEMBER"))?"purpose_not_allowed":"";ModelConfig config=null;
            if(reason.isEmpty())try{config=resolveExactModel(c.target(),capturedFallback);if(config.usesAccount()&&!"APP_OWNER".equals(audience))reason="CHATGPT_PLAN_APP_ONLY";}catch(Exception unavailable){reason="original_connection_unavailable";}
            frozen.add(new ModelRoutePlan.Candidate(c.candidateId(),config,reason));
        }
        ModelRoutePlan plan=new ModelRoutePlan(group.groupId,group.revision,purpose,audience,frozen,group.maxAttempts,group.maxRouteSwitches);return plan.primary().withRoutePlan(plan);
    }
    /** Current policy is intersected with the frozen candidate; edits can revoke but never add to a run. */
    public synchronized void requireModelGroupAdmission(ModelRoutePlan plan,String candidateId,ModelConfig selected)throws Exception {
        requireConfirmed();if(plan.groupRevision<1)return;ModelGroup live=null;JSONArray rows=modelGroupsDocument().getJSONArray("groups");for(int i=0;i<rows.length();i++){ModelGroup group=ModelGroup.fromJson(rows.getJSONObject(i));if(group.groupId.equals(plan.requestedAlias))live=group;}
        if(live==null)throw new SecurityException("original_model_group_removed");String connection=selected.usesAccount()?"account:"+selected.accountModel.accountId:selected.capabilityBinding==null?"":selected.capabilityBinding.connectionId();
        if(!live.allowedAccounts.contains(connection))throw new SecurityException("model_group_account_revoked");
        for(ModelGroup.Candidate candidate:live.candidates)if(candidate.candidateId().equals(candidateId)&&candidate.enabled()&&candidate.target().connectionId().equals(connection)&&candidate.target().modelId().equals(selected.model)&&candidate.allowedAudiences().contains(plan.audience)&&(candidate.allowedPurposes().contains(plan.purpose)||plan.purpose.startsWith("MEMBER.")&&candidate.allowedPurposes().contains("MEMBER")))return;
        throw new SecurityException("model_group_candidate_permission_revoked");
    }
    private ModelConfig resolveExactModel(ModelTargetRef ref,ModelConfig capturedFallback)throws Exception {
        if(ref.kind()!=ModelTargetRef.Kind.EXACT)throw new IllegalArgumentException("exact_model_required");
        if(ref.connectionId().startsWith("account:")){
            if(capturedFallback==null||!capturedFallback.usesAccount()||!ref.connectionId().equals("account:"+capturedFallback.accountModel.accountId)||!ref.modelId().equals(capturedFallback.model))throw new SecurityException("账号模型必须使用原账号页已选绑定");return capturedFallback.withRoutePlan(null);
        }
        Profiles profiles=requireProfiles();ApiConnectionProfile row=profiles.rows.get(ref.connectionId());if(row==null||!row.enabled)throw new SecurityException("原API连接不存在或已停用");
        ModelConfig selected=materialize(row,true).withModel(ref.modelId());return selected.withLocalObservation(capturedFallback.localCaptureAllowed,capturedFallback.localOcrAllowed);
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
        ModelConfig c=row.configuration,next=new ModelConfig(c.baseUrl,key,c.model,c.sendScreenshots,c.maxSteps,c.requestTimeoutSeconds,c.provider,c.maxOutputTokens).withCleartextConsent(c.cleartextConsent).withOwnerImageInput(c.supportsImageInput).withProviderCompatibility(c.compatibility);
        if(c.catalogEnforced){next=next.withOwnerImageInput(false).withNativeCatalog(c.capabilityBinding,c.reasoning,c.imageTokenReserve);if(imageCapabilities!=null&&requireEnabled)next=next.withOwnerImageInput(imageCapabilities.supported(next));}
        else next=next.withApiConnection(c.capabilityBinding);return next;
    }
    private static ModelConfig withoutSecret(ModelConfig c){return new ModelConfig(c.baseUrl,"",c.model,c.sendScreenshots,c.maxSteps,c.requestTimeoutSeconds,c.provider,c.maxOutputTokens).withCleartextConsent(c.cleartextConsent).withOwnerImageInput(c.supportsImageInput).withProviderCompatibility(c.compatibility);}
    private static boolean sameCompatibility(dev.pocketengine.runtime.providers.ProviderCompatProfile a,dev.pocketengine.runtime.providers.ProviderCompatProfile b){return a==null?b==null:a.matches(b);}
    private ModelConfig mergeApi(ModelConfig old,JSONObject input)throws Exception{
        for(String key:new String[]{"nativeRef","accountModel","modelRoute","capabilityBinding","catalogEnforced","reasoning","imageTokenReserve","secretSlot","connectionId","credentialId","installationId"})if(input.has(key))throw new SecurityException("原生连接身份与目录策略不能由配置JSON替代");
        String provider=input.optString("provider",old.provider),base=input.optString("baseUrl",old.baseUrl).trim(),model=input.optString("model",old.model).trim();
        if(input.has("supportsImageInput")&&!(input.opt("supportsImageInput") instanceof Boolean))throw new IllegalArgumentException("请明确填写图像输入能力");
        String key=input.optBoolean("clearApiKey",false)?"":input.optString("apiKey").trim();if(key.isEmpty()&&!input.optBoolean("clearApiKey",false)&&old.provider.equals(provider)&&sameOrigin(old.baseUrl,base))key=old.apiKey;
        String consent=input.has("cleartextConsent")?input.optString("cleartextConsent"):old.provider.equals(provider)&&sameOrigin(old.baseUrl,base)?old.cleartextConsent:"";if(ModelConfig.cleartextConsentFor(provider,base).isEmpty())consent="";
        boolean image=input.has("supportsImageInput")?input.getBoolean("supportsImageInput"):old.provider.equals(provider)&&old.baseUrl.equals(base)&&old.model.equals(model)&&old.supportsImageInput;
        return new ModelConfig(base,key,model,input.optBoolean("sendScreenshots",old.sendScreenshots),input.optInt("maxSteps",old.maxSteps),input.optInt("requestTimeoutSeconds",old.requestTimeoutSeconds),provider,input.optInt("maxOutputTokens",old.maxOutputTokens)).withCleartextConsent(consent).withOwnerImageInput(image).withProviderCompatibility(old.provider.equals(provider)&&old.baseUrl.equals(base)&&old.model.equals(model)&&old.apiKey.equals(key)?old.compatibility:null);
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
        if(useApi)edit=selectDefaultMain(edit.putString("modelRoute","api_key").remove("accountModelRef"));commitConfirmed(edit);
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
    /** Portable owner definitions only. This never decrypts, migrates, selects or exports a credential. */
    public JSONObject exportBackupForOwner(RunControl original)throws Exception {
        return modelSelection.readStable(()->{synchronized(SettingsStore.this){
            requireConfirmed();original.checkpoint();Profiles local=profiles();JSONArray connections=new JSONArray(),omitted=new JSONArray();
            if(local!=null)for(ApiConnectionProfile row:local.rows.values()){original.checkpoint();connections.put(OwnerSettingsBackup.api(row.id(),row.name,row.configuration));}
            else if(legacyApiConfigured()){
                ModelConfig legacy=new ModelConfig(prefs.getString("baseUrl",""),"",prefs.getString("model",""),false,prefs.getInt("maxSteps",20),prefs.getInt("requestTimeoutSeconds",60),prefs.getString("provider",ModelConfig.DEFAULT_PROVIDER),prefs.getInt("maxOutputTokens",4096)).withCleartextConsent(prefs.getString("cleartextConsent",""));
                ModelBinding binding=apiCatalogBinding();if(binding!=null)legacy=legacy.withNativeCatalog(binding,ReasoningOptions.fromJson(new JSONObject(prefs.getString("modelReasoning",ReasoningOptions.DEFAULT.toJson().toString()))),prefs.getInt("modelImageTokenReserve",0));
                connections.put(OwnerSettingsBackup.api(binding==null?"legacy-api":binding.connectionId(),"原API连接",legacy));
            }
            JSONObject groups=modelGroupsDocument(),preferences=agentPreferences();
            JSONObject content=new JSONObject().put("preferences",new JSONObject().put("response_detail",preferences.getString("response_detail")).put("operation_pace",preferences.getString("operation_pace")))
                    .put("apiConnections",connections).put("modelGroups",groups.getJSONArray("groups")).put("workProfiles",workProfilesDocument().getJSONArray("profiles")).put("sourcePurposeBindings",groups.getJSONObject("purposeBindings"));
            for(String[] exclusion:new String[][]{{"credentials","API密钥、SecretVault、OAuth账号会话与登录状态不导出。"},{"currentSelections","当前模型选择、用途绑定和轻量路由不自动应用到新设备；归档保留原用途引用供核对。"},{"devicePermissions","HTTP明文许可、图像上传/本机观察、任务播报与对话改偏好开关不恢复为开启。"},{"installedAuthorities","插件/扩展安装审批、设备配对、目标信任和旧任务许可不恢复；工作配置保留待重新绑定的原引用。"}})omitted.put(new JSONObject().put("id",exclusion[0]).put("reason",exclusion[1]));
            original.checkpoint();return OwnerSettingsBackup.checked(OwnerConfigurationBackupSections.create("settings",content,omitted));
        }}).value();
    }
    private boolean legacyApiConfigured(){return !prefs.getString("baseUrl","").isEmpty()||!prefs.getString("model","").isEmpty()||!prefs.getString("encryptedKey","").isEmpty()||!prefs.getString("apiCatalogBinding","").isEmpty();}
    /** Private CAS only: storage values (including encrypted identities) never leave this method. */
    private String configurationBackupGuard()throws Exception {requireConfirmed();return WorkProfile.fingerprint(new JSONObject(prefs.getAll()));}
    private static String archiveOriginKey(JSONObject row)throws Exception{return row.getString("kind")+":"+row.getString("id");}
    private JSONObject configurationArchiveOrigins()throws Exception {
        JSONObject value=new JSONObject(prefs.getString("ownerConfigurationArchiveOrigins","{}"));if(value.length()>97||value.toString().getBytes(StandardCharsets.UTF_8).length>65536)throw new SecurityException("设置恢复来源记录超限，保留原数据");
        for(var keys=value.keys();keys.hasNext();){JSONObject row=value.getJSONObject(keys.next());OwnerConfigurationBackupSections.fields(row,"format","actionId","sectionDigest","sourceId");if(!"pocketworld.owner-configuration-restore.v1".equals(row.getString("format"))||!row.getString("sectionDigest").matches("[a-f0-9]{64}"))throw new SecurityException("设置恢复来源不可核对");}
        return value;
    }
    private JSONObject backupPreflight(JSONObject plan)throws Exception {
        requireConfirmed();Profiles api=profiles();JSONArray added=plan.getJSONArray("apiConnections"),groups=modelGroupsDocument().getJSONArray("groups"),work=workProfilesDocument().getJSONArray("profiles");
        if(added.length()>0&&api==null&&legacyApiConfigured())throw new SecurityException("本机仍有旧单API设置，请先在模型连接页核对原配置迁移，再恢复；没有覆盖或切换原选择");
        if((api==null?0:api.rows.size())+added.length()>ApiConnectionProfile.MAX_CONNECTIONS||groups.length()+plan.getJSONArray("modelGroups").length()>ModelGroup.MAX_GROUPS||work.length()+plan.getJSONArray("workProfiles").length()>WorkProfile.MAX_PROFILES)throw new SecurityException("本机设置位置不足；恢复只新增停用身份，请先管理现有条目");
        JSONArray conflicts=new JSONArray();
        for(int i=0;i<added.length();i++){JSONObject row=added.getJSONObject(i);if(api!=null&&api.rows.containsKey(row.getJSONObject("config").getJSONObject("capabilityBinding").getString("connectionId")))throw new SecurityException("原设置恢复身份已存在，请查询原动作");if(api!=null)for(ApiConnectionProfile local:api.rows.values())if(local.name.equals(row.getString("name")))conflicts.put(new JSONObject().put("kind","api").put("name",local.name).put("existingId",local.id()));}
        for(String kind:new String[]{"modelGroups","workProfiles"}){JSONArray incoming=plan.getJSONArray(kind),existing=kind.equals("modelGroups")?groups:work;for(int i=0;i<incoming.length();i++)for(int n=0;n<existing.length();n++){String key=kind.equals("modelGroups")?"groupId":"profileId";if(incoming.getJSONObject(i).getString(key).equals(existing.getJSONObject(n).getString(key)))throw new SecurityException("原设置恢复身份已存在，请查询原动作");if(incoming.getJSONObject(i).getString("name").equals(existing.getJSONObject(n).getString("name")))conflicts.put(new JSONObject().put("kind",kind).put("name",incoming.getJSONObject(i).getString("name")).put("existingId",existing.getJSONObject(n).getString(key)));}}
        return new JSONObject().put("counts",new JSONObject().put("apiConnections",added.length()).put("modelGroups",plan.getJSONArray("modelGroups").length()).put("workProfiles",plan.getJSONArray("workProfiles").length()).put("preferences",2))
                .put("sameNameConflicts",conflicts).put("mergeMode","new_disabled_identities").put("identities",plan.getJSONArray("rows")).put("requiresRecreation",plan.getJSONArray("requiresRecreation"))
                .put("restoredEnabled",false).put("restoresSecrets",false).put("workProfilesRequireRebinding",true).put("currentModelSelectionChanged",false).put("purposeBindingsChanged",false)
                .put("preferences",plan.getJSONObject("preferences")).put("agentPreferenceEditingEnabled",false);
    }
    /** The reservation is acquired at owner confirmation, before FeatureHost submits its original worker. */
    public OwnerConfigurationBackupSections.Review reviewBackupForOwner(JSONObject selected,String action,RunControl original)throws Exception {
        JSONObject section=OwnerSettingsBackup.checked(selected),plan=OwnerSettingsBackup.prepare(section,action);
        var before=modelSelection.readStable(()->{synchronized(SettingsStore.this){original.checkpoint();return new JSONObject().put("guard",configurationBackupGuard()).put("preview",backupPreflight(plan));}});
        return new OwnerConfigurationBackupSections.Review(){
            private ModelSelectionCoordinator.Mutation reservation;private Runnable cancelReservation;private boolean entered,reserved;
            public JSONObject metadata()throws Exception{original.checkpoint();return new JSONObject(before.value().getJSONObject("preview").toString()).put("omitted",section.getJSONArray("omitted"));}
            public synchronized void reserve()throws Exception{
                if(reserved)throw new SecurityException("原设置恢复已预留，不得重复派发");original.checkpoint();reservation=modelSelection.beginMutation(before.revision());reserved=true;cancelReservation=reservation::close;original.onCancel(cancelReservation);
                try{original.checkpoint();}catch(Exception failure){reservation.close();original.removeCancelAction(cancelReservation);throw failure;}
            }
            public JSONObject restore()throws Exception{
                final ModelSelectionCoordinator.Mutation accepted;final Runnable cancellation;
                synchronized(this){if(entered||reservation==null)throw new SecurityException("原设置恢复尚未确认或已经进入，只核对原动作");entered=true;accepted=reservation;cancellation=cancelReservation;}
                try{return accepted.run(()->{synchronized(SettingsStore.this){
                    original.checkpoint();if(!before.value().getString("guard").equals(configurationBackupGuard()))throw new SecurityException("本机设置在审阅后已改变，请核对原动作；未覆盖新设置");backupPreflight(plan);
                    Profiles api=profiles();JSONObject groups=modelGroupsDocument(),profiles=workProfilesDocument(),origins=configurationArchiveOrigins();SharedPreferences.Editor edit=prefs.edit();
                    JSONArray addedApi=plan.getJSONArray("apiConnections");
                    if(addedApi.length()>0){if(api==null)api=new Profiles(new java.util.LinkedHashMap<>(),"",0);for(int i=0;i<addedApi.length();i++){ApiConnectionProfile row=ApiConnectionProfile.fromJson(addedApi.getJSONObject(i));if(api.rows.putIfAbsent(row.id(),row)!=null)throw new SecurityException("原恢复身份已存在，只查询原动作");}edit.putString(API_PROFILES,profilesDocument(api,Math.addExact(api.selectionRevision,1)));}
                    JSONArray addedGroups=plan.getJSONArray("modelGroups"),groupRows=groups.getJSONArray("groups");for(int i=0;i<addedGroups.length();i++)groupRows.put(addedGroups.getJSONObject(i));if(addedGroups.length()>0)editModelGroups(edit,groups);
                    JSONArray addedProfiles=plan.getJSONArray("workProfiles"),profileRows=profiles.getJSONArray("profiles");for(int i=0;i<addedProfiles.length();i++)profileRows.put(addedProfiles.getJSONObject(i));
                    if(addedProfiles.length()>0){profiles.put("revision",Math.addExact(profiles.getLong("revision"),1));if(profiles.toString().getBytes(StandardCharsets.UTF_8).length>262144)throw new SecurityException("工作配置恢复超过原存储边界");edit.putString("workProfiles",profiles.toString());}
                    java.util.Set<String> live=new java.util.HashSet<>();live.add("preferences");if(api!=null)for(String id:api.rows.keySet())live.add("api:"+id);for(int i=0;i<groupRows.length();i++)live.add("group:"+groupRows.getJSONObject(i).getString("groupId"));for(int i=0;i<profileRows.length();i++)live.add("profile:"+profileRows.getJSONObject(i).getString("profileId"));
                    java.util.List<String> stale=new java.util.ArrayList<>();origins.keys().forEachRemaining(key->{if(!live.contains(key))stale.add(key);});for(String key:stale)origins.remove(key);
                    String digest=section.getString("sectionDigest");JSONArray identities=plan.getJSONArray("rows");for(int i=0;i<identities.length();i++){JSONObject row=identities.getJSONObject(i);origins.put(archiveOriginKey(row),OwnerConfigurationBackupSections.receipt(action,digest,row.getString("kind")+":"+row.getString("sourceId")));}
                    origins.put("preferences",OwnerConfigurationBackupSections.receipt(action,digest,"preferences"));if(origins.length()>97||origins.toString().getBytes(StandardCharsets.UTF_8).length>65536)throw new SecurityException("设置恢复来源记录超限");
                    JSONObject preferences=plan.getJSONObject("preferences");edit.putString("ownerConfigurationArchiveOrigins",origins.toString()).putBoolean("allowAgentPreferences",false).putString("agentResponseDetail",preferences.getString("response_detail")).putString("agentOperationPace",preferences.getString("operation_pace")).putLong("agentPreferencesRevision",Math.addExact(prefs.getLong("agentPreferencesRevision",1),1));
                    original.checkpoint();commitConfirmed(edit);return queryBackupForOwner(section,action,original);
                }});}finally{original.removeCancelAction(cancellation);accepted.close();}
            }
        };
    }
    /** No import path is called here. A failed/partial receipt can only inspect this original action's identities. */
    public synchronized JSONObject queryBackupForOwner(JSONObject selected,String action,RunControl original)throws Exception {
        requireConfirmed();original.checkpoint();JSONObject section=OwnerSettingsBackup.checked(selected),plan=OwnerSettingsBackup.prepare(section,action),origins=configurationArchiveOrigins();Profiles api=profiles();JSONArray groups=modelGroupsDocument().getJSONArray("groups"),profiles=workProfilesDocument().getJSONArray("profiles"),rows=new JSONArray();String digest=section.getString("sectionDigest");
        JSONArray identities=plan.getJSONArray("rows");for(int i=0;i<identities.length();i++){original.checkpoint();JSONObject expected=identities.getJSONObject(i),row=new JSONObject(expected.toString());String id=expected.getString("id"),kind=expected.getString("kind");boolean found=false;
            if(kind.equals("api")){ApiConnectionProfile actual=api==null?null:api.rows.get(id);found=actual!=null;if(actual!=null)row.put("enabled",actual.enabled);}
            else {JSONArray actual=kind.equals("group")?groups:profiles;String key=kind.equals("group")?"groupId":"profileId";for(int n=0;n<actual.length();n++)if(id.equals(actual.getJSONObject(n).getString(key))){found=true;if(kind.equals("profile"))row.put("restoredPendingReview",actual.getJSONObject(n).optBoolean("restoredPendingReview"));}}
            found&=OwnerConfigurationBackupSections.matches(origins.optJSONObject(archiveOriginKey(expected)),action,digest,kind+":"+expected.getString("sourceId"));rows.put(row.put("found",found));
        }
        rows.put(new JSONObject().put("kind","preferences").put("found",OwnerConfigurationBackupSections.matches(origins.optJSONObject("preferences"),action,digest,"preferences")));
        JSONObject result=OwnerConfigurationBackupSections.result(rows).put("requiresRecreation",plan.getJSONArray("requiresRecreation")).put("restoresSecrets",false).put("currentModelSelectionChanged",false).put("purposeBindingsChanged",false);
        if(result.getBoolean("found")&&plan.getJSONArray("requiresRecreation").length()>0)result.put("status","completed_with_pending_recreation");return result;
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
