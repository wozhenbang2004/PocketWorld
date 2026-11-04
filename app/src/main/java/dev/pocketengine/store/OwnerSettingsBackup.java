package dev.pocketengine.store;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.features.controlplane.WorkProfile;
import dev.pocketengine.features.workspace.OwnerConfigurationBackupSections;
import dev.pocketengine.runtime.models.ApiConnectionProfile;
import dev.pocketengine.runtime.models.ModelBinding;
import dev.pocketengine.runtime.models.ModelCatalogRoutes;
import dev.pocketengine.runtime.models.ModelGroup;
import dev.pocketengine.runtime.models.ModelTargetRef;
import dev.pocketengine.runtime.models.ReasoningOptions;
import dev.pocketengine.runtime.providers.ProviderCompatProfile;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Public portable definitions and deterministic remapping. SettingsStore owns the only writer. */
public final class OwnerSettingsBackup {
    private OwnerSettingsBackup() { }
    public static JSONObject api(String sourceId,String name,ModelConfig configuration)throws Exception {
        ModelBinding binding=configuration.capabilityBinding;
        JSONObject config=new JSONObject().put("provider",configuration.provider).put("baseUrl",configuration.baseUrl)
                .put("model",configuration.model).put("maxSteps",configuration.maxSteps).put("requestTimeoutSeconds",configuration.requestTimeoutSeconds)
                .put("maxOutputTokens",configuration.maxOutputTokens).put("catalogEnabled",configuration.catalogEnforced)
                .put("catalogFormat",binding==null?ModelCatalogRoutes.defaultFormat(configuration.provider).name():binding.format().name())
                .put("modelsEndpoint",binding==null?ModelCatalogRoutes.modelsEndpoint(configuration).toASCIIString():binding.modelsEndpoint().toASCIIString())
                .put("reasoning",configuration.reasoning.toJson()).put("imageTokenReserve",configuration.imageTokenReserve);
        if(configuration.usesBedrock())config.put("bedrockRegion",configuration.bedrockRegion);
        if(configuration.compatibility!=null)config.put("compatibility",configuration.compatibility.toJson());
        return checkedApi(new JSONObject().put("sourceId",sourceId).put("name",name).put("configuration",config));
    }
    public static JSONObject checked(JSONObject section)throws Exception {
        JSONObject exact=OwnerConfigurationBackupSections.checked("settings",section),content=exact.getJSONObject("content");
        OwnerConfigurationBackupSections.fields(content,"preferences","apiConnections","modelGroups","workProfiles","sourcePurposeBindings");
        JSONObject preferences=content.getJSONObject("preferences");OwnerConfigurationBackupSections.fields(preferences,"response_detail","operation_pace");
        if(!Set.of("concise","standard","detailed").contains(preferences.getString("response_detail"))
                ||!Set.of("fast","standard","careful").contains(preferences.getString("operation_pace")))throw new SecurityException("归档回复/节奏偏好不受支持");
        JSONArray api=content.getJSONArray("apiConnections"),groups=content.getJSONArray("modelGroups"),profiles=content.getJSONArray("workProfiles");
        if(api.length()>ApiConnectionProfile.MAX_CONNECTIONS||groups.length()>ModelGroup.MAX_GROUPS||profiles.length()>WorkProfile.MAX_PROFILES)throw new SecurityException("设置归档条目数量超限");
        Set<String> seen=new HashSet<>();for(int i=0;i<api.length();i++)if(!seen.add(checkedApi(api.getJSONObject(i)).getString("sourceId")))throw new SecurityException("归档API身份重复");
        seen.clear();for(int i=0;i<groups.length();i++){JSONObject group=groups.getJSONObject(i);checkGroupFields(group);if(!seen.add(ModelGroup.fromJson(group).groupId))throw new SecurityException("归档模型组身份重复");}
        seen.clear();for(int i=0;i<profiles.length();i++)if(!seen.add(WorkProfile.fromJson(profiles.getJSONObject(i)).id))throw new SecurityException("归档工作配置身份重复");
        JSONObject purposes=content.getJSONObject("sourcePurposeBindings");if(purposes.length()>20)throw new SecurityException("归档用途引用超限");
        for(var names=purposes.keys();names.hasNext();){String purpose=names.next();if(!purpose.matches("MAIN|SUMMARY|RELAY|MEMBER(?:\\.[A-Za-z0-9_-]{1,60})?"))throw new SecurityException("归档模型用途无效");ModelTargetRef.fromJson(purposes.getJSONObject(purpose));}
        return exact;
    }
    private static JSONObject checkedApi(JSONObject row)throws Exception {
        OwnerConfigurationBackupSections.fields(row,"sourceId","name","configuration");
        String source=OwnerConfigurationBackupSections.text(row,"sourceId",200);if(!source.matches("[A-Za-z0-9_.:-]+"))throw new SecurityException("归档API来源身份无效");
        ApiConnectionProfile.checkedName(OwnerConfigurationBackupSections.text(row,"name",80));JSONObject c=row.getJSONObject("configuration");
        OwnerConfigurationBackupSections.fields(c,"provider","baseUrl","model","maxSteps","requestTimeoutSeconds","maxOutputTokens","catalogEnabled","catalogFormat","modelsEndpoint","reasoning","imageTokenReserve","compatibility","bedrockRegion");
        if(!(c.get("catalogEnabled") instanceof Boolean))throw new SecurityException("归档目录开关必须明确");
        String provider=OwnerConfigurationBackupSections.text(c,"provider",80);if(!ModelConfig.PROVIDERS.contains(provider))throw new SecurityException("归档API协议不支持");
        String model=OwnerConfigurationBackupSections.text(c,"model",200);if(!model.equals(model.trim())||model.chars().anyMatch(Character::isISOControl))throw new SecurityException("归档模型名无效");
        integer(c,"maxSteps",1,40);integer(c,"requestTimeoutSeconds",10,120);integer(c,"maxOutputTokens",128,32768);integer(c,"imageTokenReserve",0,2000000);
        ModelBinding.Format format=ModelBinding.Format.valueOf(c.getString("catalogFormat"));ModelBinding.Protocol protocol=ModelCatalogRoutes.protocol(provider);
        if(ModelConfig.BEDROCK_PROVIDER.equals(provider)){
            String region=c.getString("bedrockRegion");dev.pocketengine.runtime.providers.bedrock.BedrockEndpoint.validate(region,model);
            if(c.getBoolean("catalogEnabled")||format!=ModelBinding.Format.NONE||!c.getString("modelsEndpoint").isEmpty()||!c.getString("baseUrl").equals(dev.pocketengine.runtime.providers.bedrock.BedrockEndpoint.baseUrl(region)))throw new SecurityException("归档 Bedrock 区域/目标或无目录身份不一致");
        }else{
            if(c.has("bedrockRegion"))throw new SecurityException("非 Bedrock 归档不能包含 AWS 区域");
            URI base=endpoint(OwnerConfigurationBackupSections.text(c,"baseUrl",4096)),models=endpoint(OwnerConfigurationBackupSections.text(c,"modelsEndpoint",4096));
            if(!base.getScheme().equalsIgnoreCase(models.getScheme())||!base.getHost().equalsIgnoreCase(models.getHost())||port(base)!=port(models)||models.getPath()==null||models.getPath().isEmpty())throw new SecurityException("归档目录必须保持原接口同源");
            if(!(switch(format){case OPENAI->protocol==ModelBinding.Protocol.OPENAI_CHAT||protocol==ModelBinding.Protocol.OPENAI_RESPONSES;case ANTHROPIC->protocol==ModelBinding.Protocol.ANTHROPIC;case GEMINI->protocol==ModelBinding.Protocol.GEMINI;case OPENROUTER->protocol==ModelBinding.Protocol.OPENAI_CHAT;case NONE->false;}))throw new SecurityException("归档目录与推理协议不符");
        }
        ReasoningOptions.fromJson(c.getJSONObject("reasoning"));
        if(c.has("compatibility")){ProviderCompatProfile compatibility=ProviderCompatProfile.fromJson(c.getJSONObject("compatibility"));ModelConfig selected=new ModelConfig(c.getString("baseUrl"),"",model,false,20,60,provider,4096);if(selected.usesBedrock())selected=selected.withBedrockSession(c.getString("bedrockRegion"),null);selected.withProviderCompatibility(compatibility);}
        return new JSONObject(row.toString());
    }
    private static URI endpoint(String value)throws Exception {
        URI uri=new URI(value);if(uri.getScheme()==null||!Set.of("http","https").contains(uri.getScheme().toLowerCase(java.util.Locale.ROOT))||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||uri.getPort()>65535||!uri.normalize().equals(uri))throw new SecurityException("归档接口地址无效");return uri;
    }
    private static int port(URI value){return value.getPort()<0?("https".equalsIgnoreCase(value.getScheme())?443:80):value.getPort();}
    private static long integer(JSONObject value,String field,long low,long high)throws Exception{Object raw=value.get(field);if(!(raw instanceof Integer||raw instanceof Long)||((Number)raw).longValue()<low||((Number)raw).longValue()>high)throw new SecurityException("归档数值超出原边界："+field);return ((Number)raw).longValue();}
    private static void checkGroupFields(JSONObject value)throws Exception {
        OwnerConfigurationBackupSections.fields(value,"groupId","revision","name","allowedAccounts","candidates","attemptPolicy");
        OwnerConfigurationBackupSections.fields(value.getJSONObject("allowedAccounts"),"mode","refs");OwnerConfigurationBackupSections.fields(value.getJSONObject("attemptPolicy"),"maxAttempts","maxRouteSwitches");
        JSONArray rows=value.getJSONArray("candidates");for(int i=0;i<rows.length();i++)OwnerConfigurationBackupSections.fields(rows.getJSONObject(i),"candidateId","target","enabled","allowedPurposes","allowedAudiences");
    }
    public static String id(String action,String kind,String source)throws Exception {
        return (kind.equals("api")?"api-":kind.equals("group")?"group-":kind.equals("profile")?"work-":"unavailable-")
                +OwnerConfigurationBackupSections.originalId("settings",action,kind+":"+WorkProfile.fingerprint(source));
    }
    /** No caller identity is retained: every reference maps to this exact action or an unusable placeholder. */
    public static JSONObject prepare(JSONObject selected,String action)throws Exception {
        JSONObject section=checked(selected),content=section.getJSONObject("content");JSONArray api=content.getJSONArray("apiConnections"),groups=content.getJSONArray("modelGroups"),profiles=content.getJSONArray("workProfiles");
        Map<String,String> apiMap=new LinkedHashMap<>();Map<String,ModelGroup> sourceGroups=new LinkedHashMap<>();Map<String,String> groupMap=new LinkedHashMap<>();
        JSONArray restoredApi=new JSONArray(),restoredGroups=new JSONArray(),restoredProfiles=new JSONArray(),rows=new JSONArray(),pending=new JSONArray();
        for(int i=0;i<api.length();i++){
            JSONObject row=api.getJSONObject(i),c=row.getJSONObject("configuration");String source=row.getString("sourceId"),newId=id(action,"api",source);
            if(!ModelConfig.cleartextConsentFor(c.getString("provider"),c.getString("baseUrl")).isEmpty()){
                apiMap.put(source,id(action,"unavailable",source));pending.put(new JSONObject(row.toString()).put("state","requires_recreation").put("restored",false).put("reason","旧设备的明文传输许可未复制，请在模型页按原目标重新创建并确认。"));continue;
            }
            apiMap.put(source,newId);ModelBinding binding=new ModelBinding(newId,c.getString("provider"),ModelCatalogRoutes.protocol(c.getString("provider")),ModelBinding.Format.valueOf(c.getString("catalogFormat")),new URI(c.getString("modelsEndpoint")),"key-"+id(action,"credential",source),1,"connection-"+id(action,"installation",source),1,false);
            ModelConfig config=new ModelConfig(c.getString("baseUrl"),"",c.getString("model"),false,c.getInt("maxSteps"),c.getInt("requestTimeoutSeconds"),c.getString("provider"),c.getInt("maxOutputTokens"));
            if(config.usesBedrock())config=config.withBedrockSession(c.getString("bedrockRegion"),null);
            config=c.getBoolean("catalogEnabled")?config.withNativeCatalog(binding,ReasoningOptions.fromJson(c.getJSONObject("reasoning")),c.getInt("imageTokenReserve")):config.withApiConnection(binding);
            if(c.has("compatibility"))config=config.withProviderCompatibility(ProviderCompatProfile.fromJson(c.getJSONObject("compatibility")));
            ApiConnectionProfile restored=new ApiConnectionProfile(row.getString("name"),1,false,"",config);restoredApi.put(restored.toJson());rows.put(identity("api",source,newId));
        }
        for(int i=0;i<groups.length();i++){ModelGroup group=ModelGroup.fromJson(groups.getJSONObject(i));sourceGroups.put(group.groupId,group);groupMap.put(group.groupId,id(action,"group",group.groupId));}
        for(ModelGroup old:sourceGroups.values()){
            List<ModelGroup.Candidate> candidates=new ArrayList<>();Set<String> allowed=new HashSet<>();
            for(String source:old.allowedAccounts)allowed.add(mappedApi(apiMap,action,source));
            for(ModelGroup.Candidate row:old.candidates)candidates.add(new ModelGroup.Candidate("candidate-"+OwnerConfigurationBackupSections.originalId("settings",action,"candidate:"+WorkProfile.fingerprint(old.groupId+":"+row.candidateId())),ModelTargetRef.exact(mappedApi(apiMap,action,row.target().connectionId()),row.target().modelId()),false,row.allowedPurposes(),row.allowedAudiences()));
            ModelGroup restored=new ModelGroup(groupMap.get(old.groupId),1,old.name,allowed,candidates,old.maxAttempts,old.maxRouteSwitches);restoredGroups.put(restored.toJson());rows.put(identity("group",old.groupId,restored.groupId));
        }
        for(int i=0;i<profiles.length();i++){
            WorkProfile old=WorkProfile.fromJson(profiles.getJSONObject(i));JSONObject source=old.toJson(),next=WorkProfile.definition(id(action,"profile",old.id),1,old.name).put("resourcePreferences",source.getJSONObject("resourcePreferences"));
            JSONArray history=source.has("restoredReferences")?new JSONArray(source.getJSONArray("restoredReferences").toString()):new JSONArray();Set<String> seen=new HashSet<>();for(int n=0;n<history.length();n++)seen.add(WorkProfile.fingerprint(history.get(n)));
            JSONObject models=source.getJSONObject("modelPolicies");for(var names=models.keys();names.hasNext();){String purpose=names.next();ModelTargetRef ref=ModelTargetRef.fromJson(models.getJSONObject(purpose));ModelTargetRef mapped;
                if(ref.kind()==ModelTargetRef.Kind.EXACT)mapped=ModelTargetRef.exact(mappedApi(apiMap,action,ref.connectionId()),ref.modelId());
                else {ModelGroup group=sourceGroups.get(ref.groupId());mapped=ModelTargetRef.group(group!=null&&group.revision==ref.groupRevision()?groupMap.get(ref.groupId()):id(action,"unavailable",ref.groupId()+":"+ref.groupRevision()),1);}
                next.getJSONObject("modelPolicies").put(purpose,mapped.toJson());historical(history,seen,"model:"+purpose,ref.toJson());
            }
            for(String kind:new String[]{"instructionRefs","skillRefs","pluginRefs","extensionRefs","targetBindings"}){JSONArray refs=source.getJSONArray(kind);for(int n=0;n<refs.length();n++)historical(history,seen,kind,refs.getJSONObject(n));}
            if(!source.isNull("teamTemplateRef"))historical(history,seen,"teamTemplateRef",source.getJSONObject("teamTemplateRef"));
            WorkProfile restored=WorkProfile.fromJson(next.put("restoredPendingReview",true).put("restoredReferences",history));restoredProfiles.put(restored.toJson());rows.put(identity("profile",old.id,restored.id));
        }
        return new JSONObject().put("apiConnections",restoredApi).put("modelGroups",restoredGroups).put("workProfiles",restoredProfiles)
                .put("rows",rows).put("requiresRecreation",pending).put("preferences",content.getJSONObject("preferences"));
    }
    private static JSONObject identity(String kind,String source,String id)throws Exception{return new JSONObject().put("kind",kind).put("sourceId",source).put("id",id);}
    private static String mappedApi(Map<String,String> mapping,String action,String source)throws Exception{return mapping.containsKey(source)?mapping.get(source):id(action,"unavailable",source);}
    private static void historical(JSONArray history,Set<String> seen,String kind,JSONObject reference)throws Exception{JSONObject row=new JSONObject().put("kind",kind).put("reference",new JSONObject(reference.toString()));if(seen.add(WorkProfile.fingerprint(row)))history.put(row);}
}
