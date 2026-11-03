package dev.pocketengine.features;

import dev.pocketengine.features.assistant.AssistantModule;
import dev.pocketengine.features.memory.MemoryBook;
import dev.pocketengine.features.memory.MemoryModule;
import dev.pocketengine.features.memory.SourceRef;
import dev.pocketengine.features.plugins.PluginsModule;
import dev.pocketengine.features.proxy.accounts.AccountGateway;
import dev.pocketengine.features.proxy.accounts.GatewayCall;
import dev.pocketengine.features.proxy.accounts.GatewayFailure;
import dev.pocketengine.features.proxy.accounts.SecretVaultAdapter;
import dev.pocketengine.features.proxy.providers.ChatGptProvider;
import dev.pocketengine.store.TaskStore;
import java.io.File;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Native owner screens only. Deliberately not an EngineModule, featureRequest command or model tool.
 * The application owns this singleton; every method that touches modules or storage runs on a worker.
 */
public final class NativeOwnerWorkflows {
    private final FeatureHost host;
    private final FeatureContext context;
    private final TaskStore tasks;
    private final MemorySourceBridge sources;
    private final dev.pocketengine.features.memory.TaskMemoryNativeHost taskMemory;
    private volatile AccountGateway accounts;
    private final Map<String,String> authorizationUrls=new HashMap<>();
    private final Map<String,JSONObject> catalogs=new HashMap<>();
    private final Map<String,dev.pocketengine.features.plugins.McpLocalDiscovery.Review> localMcpReviews=new HashMap<>();
    private final Map<String,dev.pocketengine.features.plugins.McpLocalInstallation.Review> localMcpInstallReviews=new HashMap<>();
    private JSONObject selectedModel;
    private long accountSnapshotRevision;
    private volatile boolean accountInferenceInstalled;
    /** Install only the host-owned singleton. The model sees public references, never credentials. */
    public void installAccountInference(){
        dev.pocketengine.runtime.ModelGateway.installAccountInferencePort(new dev.pocketengine.runtime.ModelGateway.AccountInferencePort(){
            @Override public boolean accountIdentityCurrent(dev.pocketengine.core.ModelConfig.AccountModelRef reference){AccountGateway loaded=accounts;return loaded!=null&&loaded.inferenceIdentityCurrent(reference.accountId,reference.accountVersion);}
            @Override public dev.pocketengine.runtime.models.AccountCatalogFacts modelFacts(dev.pocketengine.core.ModelConfig.AccountModelRef reference)throws GatewayFailure {
                AccountGateway loaded=accounts;if(loaded==null)return null;
                return loaded.modelFacts(reference.accountId,reference.accountVersion,reference.modelId);
            }
            @Override public boolean imageInputSupported(dev.pocketengine.core.ModelConfig.AccountModelRef reference){
                AccountGateway loaded=accounts;return loaded!=null&&loaded.supportsImageInput(reference.accountId,reference.accountVersion,reference.modelId);
            }
            @Override public boolean planUsageLimited(dev.pocketengine.core.ModelConfig.AccountModelRef reference){
                AccountGateway loaded=accounts;return loaded!=null&&loaded.planUsageLimited(reference.accountId,reference.accountVersion);
            }
            @Override public void stream(dev.pocketengine.core.ModelConfig.AccountModelRef reference,dev.pocketengine.features.proxy.providers.ResponsesRequest request,GatewayCall operation,ChatGptProvider.EventSink sink)throws GatewayFailure{
                accounts().streamResponsesReviewed(reference.accountId,reference.accountVersion,request,operation,sink);
            }
            @Override public void streamWithVersion(dev.pocketengine.core.ModelConfig.AccountModelRef reference,dev.pocketengine.features.proxy.providers.ResponsesRequest request,GatewayCall operation,dev.pocketengine.runtime.ModelGateway.AccountVersionSink versions,ChatGptProvider.EventSink sink)throws GatewayFailure{
                accounts().streamResponsesReviewed(reference.accountId,reference.accountVersion,request,operation,actualVersion->{
                    try{context.settings.advanceAccountModel(reference,actualVersion);}catch(Exception failed){throw new GatewayFailure("ACCOUNT_SELECTION_STORE_UNAVAILABLE");}
                    // A false CAS means the owner changed selection; do not replace that newer choice.
                    versions.authorized(actualVersion);
                },sink);
            }
        });
        accountInferenceInstalled=true;
    }

    /** The loopback endpoint shares the exact same private gateway as native account screens. */
    public dev.pocketengine.features.proxy.LoopbackRelay.AccountRoute accountRelayRoute(){
        return new dev.pocketengine.features.proxy.LoopbackRelay.AccountRoute(){
            @Override public JSONArray models(dev.pocketengine.core.ModelConfig.AccountModelRef reference,GatewayCall operation)throws GatewayFailure{
                JSONObject result=accounts().modelsReviewed(reference.accountId,reference.accountVersion,operation);
                try{context.settings.advanceAccountModel(reference,result.getLong("version"));return result.getJSONArray("models");}
                catch(Exception failure){throw new GatewayFailure("ACCOUNT_SELECTION_STORE_UNAVAILABLE");}
            }
            @Override public void stream(dev.pocketengine.core.ModelConfig.AccountModelRef reference,dev.pocketengine.features.proxy.providers.ResponsesRequest request,GatewayCall operation,ChatGptProvider.EventSink sink)throws GatewayFailure{
                accounts().streamResponsesReviewed(reference.accountId,reference.accountVersion,request,operation,actualVersion->{
                    try{context.settings.advanceAccountModel(reference,actualVersion);}catch(Exception failure){throw new GatewayFailure("ACCOUNT_SELECTION_STORE_UNAVAILABLE");}
                    // CAS false preserves the owner's newer selection; never replace it with this request's route.
                },sink);
            }
        };
    }

    public NativeOwnerWorkflows(FeatureHost host,FeatureContext context,TaskStore tasks,MemorySourceBridge sources){
        this.host=host;this.context=context;this.tasks=tasks;this.sources=sources;taskMemory=new dev.pocketengine.features.memory.TaskMemoryNativeHost(host,tasks,sources);
    }
    public JSONObject taskMemory(String command,JSONObject payload)throws Exception{return taskMemory.handle(command,payload);}
    public JSONObject taskMemory(String command,JSONObject payload,dev.pocketengine.core.RunControl originalOwnerRead)throws Exception{return taskMemory.handle(command,payload,originalOwnerRead);}
    private static JSONObject ownerScope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private MemoryModule memory(){EngineModule module=host.module("memory");if(!(module instanceof MemoryModule))throw new IllegalStateException("记忆模块暂不可用");return (MemoryModule)module;}
    private PluginsModule plugins(){EngineModule module=host.module("plugins");if(!(module instanceof PluginsModule))throw new IllegalStateException("插件模块暂不可用");return (PluginsModule)module;}
    /** Application worker only. Read-only UI metadata ports never initialize storage or touch the vault. */
    public boolean warmSelectedAccountMetadata()throws Exception {
        if(context.settings.selectedAccountModel()==null)return false;accounts();return true;
    }
    private synchronized AccountGateway accounts()throws GatewayFailure{
        if(accounts==null)accounts=new AccountGateway(new File(context.android.getFilesDir(),"accounts/chatgpt.json"),new SecretVaultAdapter(context.secrets),"owner/private");
        return accounts;
    }
    /** Native owner screen only; neither a TeamSelection nor its JSON metadata grants this token. */
    public dev.pocketengine.features.memory.ProjectMemoryNamespaces projectMemoryForOwner(){
        EngineModule local=host.module("local"),workspace=host.module("workspace");
        if(!(local instanceof dev.pocketengine.features.local.LocalModule)||!(workspace instanceof dev.pocketengine.features.workspace.WorkspaceModule))throw new IllegalStateException("原项目记忆宿主暂不可用");
        return memory().projectNamespacesForOwner((dev.pocketengine.features.local.LocalModule)local,(dev.pocketengine.features.workspace.WorkspaceModule)workspace,sources);
    }
    public JSONObject memorySnapshot()throws Exception{
        host.synchronizeMemory(ownerScope());
        return memory().snapshot().put("nativeCapabilities",new JSONObject().put("acceptCandidate",true).put("candidateCapacity",true).put("listCandidateHistory",true).put("archiveCandidates",true).put("reconsiderRejected",true).put("forgetReviewed",true).put("openSource",true));
    }
    public JSONObject memory(String command,JSONObject payload)throws Exception{
        dev.pocketengine.features.memory.ProjectMemoryNamespaces.rejectNamespaceArguments(payload);
        MemoryModule module=memory();JSONObject scope=ownerScope();host.synchronizeMemory(scope);
        switch(command){
            case "snapshot":return memorySnapshot();
            case "getParentRecord":{
                JSONObject result=module.handle("get",new JSONObject().put("scope",scope).put("id",payload.getString("id")));
                if(positiveVersion(payload,"revision")!=result.getJSONObject("record").getLong("revision"))throw new IllegalStateException("父记忆版本已变化，请重新读取当前候选来源");return result;
            }
            case "resolveAssertionConflictReviewed":return sources.resolveAssertionConflictReviewed(scope,payload.getJSONArray("records"),payload.getString("decision"),payload.optString("selectedId"),payload.getString("reason"));
            case "readAssertionEvidence":return sources.readAssertionEvidence(scope,SourceRef.fromJson(payload.getJSONObject("sourceRef")),Math.toIntExact(nativeOffset(payload,"offset")),Math.toIntExact(positiveVersion(payload,"chars")),payload.getString("sha256"));
            case "acceptCandidate":return module.acceptCandidate(scope,payload.getString("id"),positiveVersion(payload,"revision"));
            case "candidateCapacity":return module.candidateCapacity(scope);
            case "listCandidateHistory":return module.listCandidateHistory(scope);
            case "archiveCandidates":return module.archiveCandidates(scope,payload.getJSONArray("selections"));
            case "reconsiderRejected":return module.reconsiderRejected(scope,payload.getString("id"),positiveVersion(payload,"revision"),payload.getJSONObject("draft"));
            case "forgetReviewed":return sources.forgetReviewed(scope,payload.getString("id"),positiveVersion(payload,"expectedRevision"));
            case "cognitionProjection":case "list":case "search":case "get":case "listCandidates":case "getCandidate":case "editCandidate":case "rejectCandidate":case "setCore":
                return module.handle(command,new JSONObject(payload.toString()).put("scope",scope));
            default:throw new IllegalArgumentException("此操作不属于原生记忆审核");
        }
    }
    private static long nativeOffset(JSONObject value,String key){Object raw=value.opt(key);if(!(raw instanceof Integer||raw instanceof Long)||((Number)raw).longValue()<0)throw new IllegalArgumentException("原范围起点须为非负整数");return ((Number)raw).longValue();}
    private dev.pocketengine.features.skills.SkillsModule skills()throws Exception{
        EngineModule module=host.module("skills");if(!(module instanceof dev.pocketengine.features.skills.SkillsModule))throw new IllegalStateException("技能模块暂不可用");return (dev.pocketengine.features.skills.SkillsModule)module;
    }
    public JSONObject learningSnapshot()throws Exception{
        JSONObject scope=ownerScope();host.synchronizeMemory(scope);JSONArray records=memory().handle("list",new JSONObject().put("scope",scope)).getJSONArray("records"),procedures=new JSONArray();
        for(int i=0;i<records.length();i++){JSONObject row=records.getJSONObject(i);if("procedural".equals(row.optString("layer"))&&"reviewed_learning".equals(row.optString("formation")))procedures.put(row);}
        return new JSONObject().put("procedures",procedures).put("skills",skills().snapshot().getJSONArray("skills"))
                .put("automaticLearning",false).put("reviewRequired",true).put("executionVerified",false);
    }
    private KnowledgeLearningWorkflow.SourceDocument learningSource(SourceRef expected)throws Exception{
        if(!"owner".equals(expected.ownerId)||!"private".equals(expected.spaceId))throw new SecurityException("来源不属于当前私人空间");
        if("workspace_file".equals(expected.kind)||"task_evidence".equals(expected.kind))return sources.read(expected);
        EngineModule module=host.module("assistant");
        if("message".equals(expected.kind)&&module instanceof AssistantModule)return ((AssistantModule)module).readMemorySource(expected);
        throw new IllegalArgumentException("只可提炼真实消息或主人已确认的任务来源");
    }
    private KnowledgeLearningWorkflow learning()throws Exception{
        // inspect/import/approve do not invoke a model. A future model-using operation must
        // explicitly bind its native original call, rather than falling through to NONE.
        return learning((messages,control)->{throw new SecurityException("技能草稿需要本次原生调用归属，未派发未归属请求");});
    }
    private KnowledgeLearningWorkflow learning(KnowledgeLearningWorkflow.ModelClient model)throws Exception{
        return new KnowledgeLearningWorkflow(memory(),skills(),this::learningSource,model);
    }
    private JSONArray procedureApps(JSONObject scope,String record,long revision)throws Exception {
        JSONObject procedure=memory().reviewedProcedure(scope,record,revision);JSONArray refs=procedure.getJSONArray("sourceRefs"),result=new JSONArray();java.util.Set<String> seen=new java.util.HashSet<>();
        for(int i=0;i<refs.length();i++){SourceRef ref=SourceRef.fromJson(refs.getJSONObject(i));if(!"task_evidence".equals(ref.kind))continue;JSONArray apps=tasks.appIdentitiesForSource(ref);for(int j=0;j<apps.length();j++){JSONObject app=apps.getJSONObject(j);if(seen.add(app.getString("packageName")+":"+app.getString("appVersion")))result.put(app);}}
        return result;
    }
    private JSONObject draftExperience(JSONObject scope,JSONObject payload,dev.pocketengine.core.RunControl original,boolean appRevision)throws Exception{
        String record=payload.getString("recordId");long revision=positiveVersion(payload,"expectedRevision");
        // Check the original reviewed source before allocating an accounting-only record.
        memory().reviewedProcedure(scope,record,revision);
        if(appRevision){JSONArray identities=procedureApps(scope,record,revision);boolean found=false;for(int i=0;i<identities.length();i++){JSONObject app=identities.getJSONObject(i);if(payload.getString("packageName").equals(app.getString("packageName"))&&payload.getString("appVersion").equals(app.getString("appVersion")))found=true;}if(!found)throw new SecurityException("所选 App 版本没有原任务设备观察实证，未发送模型");}
        final dev.pocketengine.core.ModelConfig captured=context.model();
        try(var usage=dev.pocketengine.runtime.usage.NativeInferenceWork.open(tasks,TaskStore.NativeInferenceKind.SKILL_DRAFT,java.util.UUID.randomUUID().toString(),original)){
            var target=usage.target();var audience=dev.pocketengine.runtime.models.NativeInferenceScope.ownerApp("owner","private",target.context.taskId,target.context.runId,target.context.epoch,original);
            var admitted=dev.pocketengine.runtime.usage.InferenceCapture.withAdmission(target,()->{
                original.check();dev.pocketengine.runtime.models.InferenceAudiencePolicy.require(captured,audience,original);
                // This is outside the original Target's short TaskStore admission monitor.
                memory().reviewedProcedure(scope,record,revision);original.check();
            });
            try{
                KnowledgeLearningWorkflow workflow=learning((messages,control)->{
                    if(control!=original)throw new SecurityException("技能草稿不能替换本次原生控制器");
                    return dev.pocketengine.runtime.ModelGateway.complete(captured,messages,null,original,admitted);
                });
                JSONObject result=appRevision?workflow.draftAppRevision(scope,record,revision,payload.getString("packageName"),payload.getString("appVersion"),payload.optString("baseId"),payload.optLong("baseRevision",0),payload.optBoolean("lesson"),original):workflow.draftExperience(scope,record,revision,original);
                usage.completed();return result.put("inferenceReference",usage.reference());
            }catch(Exception failure){try{usage.failed(failure);}catch(Exception storage){failure.addSuppressed(storage);}throw failure;}
        }
    }
    /** Explicit native owner requests only. No feature/model tool exposes extraction or Skill approval. */
    public JSONObject learning(String command,JSONObject payload,dev.pocketengine.core.RunControl control)throws Exception{
        control.check();JSONObject scope=ownerScope();host.synchronizeMemory(scope);
        switch(command){
            case "snapshot":case "listProcedures":return learningSnapshot();
            case "prepareLearningSource":{
                String kind=payload.getString("kind"),id=payload.getString("sourceId");
                sources.prepareSourceHead(kind,id,control);
                SourceRef.State source=sources.resolve(MemoryBook.Scope.from(scope),kind,id);
                if(source==null||source.deleted)throw new IllegalStateException("真实来源不存在或尚未确认");
                if(memory().isSourceForgotten(scope,source.ref))return new JSONObject().put("sourceRef",source.ref.toJson()).put("status","source_forgotten").put("learningAllowed",false).put("modelCalled",false).put("reason","此来源已遗忘，提炼不会再次发送旧正文；可返回原明确保存流程处理新内容");
                KnowledgeLearningWorkflow.SourceDocument document=learningSource(source.ref);
                return new JSONObject().put("sourceRef",document.ref.toJson()).put("title",document.title).put("text",document.text).put("modelCalled",false).put("status","current").put("learningAllowed",true);
            }
            case "proposeLearning":throw new SecurityException("来源候选必须经过本机原TaskStore提炼任务；不能绕过提交与停止门禁");
            case "draftExperience":return draftExperience(scope,payload,control,false);
            case "procedureAppIdentities":return new JSONObject().put("apps",procedureApps(scope,payload.getString("recordId"),positiveVersion(payload,"expectedRevision")));
            case "draftAppRevision":return draftExperience(scope,payload,control,true);
            case "inspectExperience":return learning().inspectExperience(scope,payload.getString("recordId"),positiveVersion(payload,"expectedRevision"),payload.getJSONObject("draft"),payload.getJSONObject("options"));
            case "importExperience":return learning().importExperience(scope,payload.getString("recordId"),positiveVersion(payload,"expectedRevision"),payload.getJSONObject("draft"),payload.getJSONObject("options"));
            case "approveExperience":{
                long revision=positiveVersion(payload,"revision");if(revision>Integer.MAX_VALUE)throw new IllegalArgumentException("技能版本无效");
                return learning().approveExperience(scope,payload.getString("id"),(int)revision,payload.getString("digest"));
            }
            case "appQualityApplications":return tasks.learnedMethodApplications(payload.getString("recordId"),positiveVersion(payload,"revision"),payload.optString("cursor"),20);
            case "reviewAppQuality":{
                var receipt=tasks.reviewAppQuality(payload.getString("taskId"),payload.getString("operationId"),payload.getString("recordId"),positiveVersion(payload,"revision"),payload.getString("outcome"),payload.getString("note"));control.check();return memory().recordAppQuality(scope,receipt);
            }
            case "skillQualityApplications":return tasks.skillQualityApplications(payload.getString("id"),Math.toIntExact(positiveVersion(payload,"revision")),payload.getString("digest"));
            case "reviewSkillQuality":{
                var evidence=tasks.reviewSkillQuality(payload.getString("taskId"),payload.getString("operationId"),payload.getString("id"),Math.toIntExact(positiveVersion(payload,"revision")),payload.getString("digest"),payload.getString("outcome"),payload.getString("note"));
                control.check();JSONObject result=skills().recordQuality(scope,evidence);return result.put("appKnowledge",memory().recordAppQuality(scope,evidence));
            }
            case "getSkill":return skills().handle("get",new JSONObject().put("scope",scope).put("id",payload.getString("id")));
            case "readSkill":{
                long revision=positiveVersion(payload,"revision");String digest=payload.getString("digest");
                JSONObject result=skills().handle("read",new JSONObject().put("scope",scope).put("id",payload.getString("id")).put("path",payload.optString("path","SKILL.md")).put("offset",payload.optInt("offset",0)).put("length",Math.min(8000,Math.max(1,payload.optInt("length",8000)))));
                if(revision!=result.getLong("revision")||!digest.equals(result.getString("digest")))throw new IllegalStateException("技能正文版本已变化，请重新读取后审核");return result;
            }
            case "listSkills":return skills().handle("list",new JSONObject().put("scope",scope));
            default:throw new IllegalArgumentException("此操作不属于原生经验审核");
        }
    }
    public JSONObject mcpSnapshot()throws Exception{return plugins().snapshot().put("oauthConnections",plugins().mcpOAuthSnapshot()).put("nativeCapabilities",new JSONObject().put("setEnabledReviewed",true).put("setToolGrantReviewed",true).put("mcpOAuthMounted",true).put("pingMcpInstalledReadOnly",true).put("mcpPromptsInstalledReadOnly",true).put("localMcpDiscoveryOwnerOnly",true));}
    private static long mcpStateRevision(JSONObject payload)throws Exception{
        Object raw=payload.get("expectedStateRevision");if(!(raw instanceof Number))throw new IllegalArgumentException("MCP状态修订需为整数");
        try{return new java.math.BigDecimal(raw.toString()).longValueExact();}catch(NumberFormatException|ArithmeticException invalid){throw new IllegalArgumentException("MCP状态修订需为准确整数",invalid);}
    }
    public JSONObject mcp(String command,JSONObject payload)throws Exception{
        switch(command){
            case "snapshot":return mcpSnapshot();
            case "localMcpDiscoveryChoices":{
                EngineModule selected=host.module("local");if(!(selected instanceof dev.pocketengine.features.local.LocalModule local))throw new SecurityException("原手机开发副本宿主不可用");return new JSONObject().put("checkouts",local.managedSkillCheckoutsForHost()).put("runtime",local.projectRuntimeForHost(dev.pocketengine.features.local.runtime.ManagedProjectRuntime.RUNTIME_ID)).put("metadataOnly",true).put("executionAuthorized",false).put("credentialsSupported",false);
            }
            case "describeLocalMcpWorkspace":{
                EngineModule selected=host.module("local");if(!(selected instanceof dev.pocketengine.features.local.LocalModule local))throw new SecurityException("原手机开发副本宿主不可用");String checkout=payload.getString("checkoutId");boolean ordinary=false;JSONArray allowed=local.managedSkillCheckoutsForHost();for(int i=0;i<allowed.length();i++)if(checkout.equals(allowed.getJSONObject(i).getString("checkoutId")))ordinary=true;if(!ordinary)throw new SecurityException("请选择原主人普通副本");return local.describeMcpWorkspaceForOwner(checkout,new dev.pocketengine.core.RunControl());
            }
            case "prepareLocalMcpDiscovery":{
                EngineModule selected=host.module("local");if(!(selected instanceof dev.pocketengine.features.local.LocalModule local))throw new SecurityException("原手机开发副本宿主不可用");
                synchronized(localMcpReviews){for(var iterator=localMcpReviews.entrySet().iterator();iterator.hasNext();){var entry=iterator.next();try{entry.getValue().metadata();}catch(Exception stale){entry.getValue().close();iterator.remove();}}if(localMcpReviews.size()>=4)throw new SecurityException("请先关闭旧的本机MCP程序审阅");var review=local.reviewMcpForOwner(payload.getJSONObject("configuration"),new dev.pocketengine.core.RunControl()).bindPluginTarget(plugins());JSONObject view=review.metadata();String id=view.getString("actionId");localMcpReviews.put(id,review);return view.put("reviewId",id).put("previewOnly",true).put("programStarted",false);}
            }
            case "confirmLocalMcpDiscovery":{
                dev.pocketengine.features.plugins.McpLocalDiscovery.Review review;synchronized(localMcpReviews){review=localMcpReviews.remove(payload.getString("reviewId"));}if(review==null)throw new SecurityException("请在此原生窗口重新完整审阅本机程序，不能从JSON恢复批准");try{return host.submitMcpDiscoveryForOwner(review.confirm(payload.getString("planDigest")));}finally{review.close();}
            }
            case "discardLocalMcpReview":{
                dev.pocketengine.features.plugins.McpLocalDiscovery.Review review;synchronized(localMcpReviews){review=localMcpReviews.remove(payload.getString("reviewId"));}if(review!=null)review.close();return new JSONObject().put("discarded",review!=null).put("programStarted",false).put("taskCancelled",false);
            }
            case "readLocalMcpDiscovery":return host.observeMcpDiscoveryForOwner(payload.getString("taskId"),false);
            case "cancelLocalMcpDiscovery":return host.observeMcpDiscoveryForOwner(payload.getString("taskId"),true);
            case "prepareLocalMcpInstall":{
                EngineModule selected=host.module("local");if(!(selected instanceof dev.pocketengine.features.local.LocalModule local))throw new SecurityException("原手机开发源码宿主不可用");
                synchronized(localMcpInstallReviews){for(var iterator=localMcpInstallReviews.entrySet().iterator();iterator.hasNext();){var entry=iterator.next();try{entry.getValue().metadata();}catch(Exception stale){entry.getValue().close();iterator.remove();}}if(localMcpInstallReviews.size()>=4)throw new SecurityException("请先关闭旧的本机MCP安装审阅");var completed=tasks.completedOwnerMcpDiscoveryForInstallForOwner(payload.getString("taskId"));var review=plugins().reviewLocalMcpInstallForOwner(tasks,completed,local,payload.getJSONArray("tools"),new dev.pocketengine.core.RunControl());JSONObject view=review.metadata();localMcpInstallReviews.put(view.getString("reviewId"),review);return view;}
            }
            case "confirmLocalMcpInstall":{
                dev.pocketengine.features.plugins.McpLocalInstallation.Review review;synchronized(localMcpInstallReviews){review=localMcpInstallReviews.remove(payload.getString("reviewId"));}if(review==null)throw new SecurityException("请在此原生窗口重新审阅本机MCP安装；JSON不是批准");try{return plugins().installLocalMcpForOwner(review,payload.getString("reviewDigest"));}finally{review.close();}
            }
            case "discardLocalMcpInstallReview":{
                dev.pocketengine.features.plugins.McpLocalInstallation.Review review;synchronized(localMcpInstallReviews){review=localMcpInstallReviews.remove(payload.getString("reviewId"));}if(review!=null)review.close();return new JSONObject().put("discarded",review!=null).put("programStarted",false).put("installationChanged",false);
            }
            case "pingMcp":return plugins().pingMcpInstallation(payload.getString("id"),payload.getString("expectedInstallationId"),mcpStateRevision(payload),new dev.pocketengine.core.RunControl(),payload.getString("_operationId"));
            case "listMcpPrompts":return plugins().listMcpPrompts(payload.getString("id"),payload.getString("expectedInstallationId"),mcpStateRevision(payload),new dev.pocketengine.core.RunControl(),payload.getString("_operationId"));
            case "getMcpPrompt":return plugins().getMcpPrompt(payload.getString("id"),payload.getString("expectedInstallationId"),mcpStateRevision(payload),payload.getString("name"),payload.getString("descriptorDigest"),payload.getJSONObject("arguments"),new dev.pocketengine.core.RunControl(),payload.getString("_operationId"));
            case "setEnabledReviewed":return plugins().setEnabledReviewed(payload.getString("id"),payload.getString("expectedInstallationId"),payload.getBoolean("enabled"));
            case "setToolGrantReviewed":return plugins().setToolGrantReviewed(payload.getString("id"),payload.getString("expectedInstallationId"),payload.getString("expectedGrantsDigest"),payload.getString("tool"),payload.getBoolean("granted"));
            case "discoverMcp":{
                // No credential-bearing JSON enters the view-model or generic feature dispatcher.
                JSONObject config=publicMcpConfig(payload,Set.of("id","name","endpoint","protocolVersion","_operationId"));
                config.put("_operationId",payload.getString("_operationId"));return plugins().handle(command,config);
            }
            case "discoverMcpAuthorized":{
                JSONObject config=publicMcpConfig(payload,Set.of("id","name","endpoint","protocolVersion","_operationId","connectionId","expectedAuthorizationRevision"));config.put("_operationId",payload.getString("_operationId"));return plugins().discoverMcpAuthorized(config,payload.getString("connectionId"),payload.getInt("expectedAuthorizationRevision"));
            }
            case "installMcp":return plugins().handle(command,new JSONObject().put("previewId",payload.getString("previewId")).put("tools",payload.getJSONArray("tools")).put("expectedInstallationId",payload.getString("expectedInstallationId")));
            case "discardMcpPreview":return plugins().handle(command,new JSONObject().put("previewId",payload.getString("previewId")));
            case "cancel":return plugins().handle(command,new JSONObject().put("operationId",payload.getString("operationId")));
            default:throw new IllegalArgumentException("此操作不属于MCP导入审核");
        }
    }
    /** A deliberately small credential-free SAF format. Unsupported fields fail closed, not silently drop. */
    public static JSONObject parseMcpConfig(String text)throws Exception{
        if(text==null||text.length()>65536)throw new IllegalArgumentException("MCP配置过大，请使用不超过64KB的JSON配置");
        return publicMcpConfig(new JSONObject(text),Set.of("id","name","endpoint","protocolVersion"));
    }
    private static JSONObject publicMcpConfig(JSONObject value,Set<String> allowed)throws Exception{
        for(String key:keys(value))if(!allowed.contains(key))throw new IllegalArgumentException("本版仅导入id、name、endpoint、protocolVersion；不支持凭据、headers、环境变量或多服务器配置，请勿把凭据放入名称或地址");
        String id=value.getString("id").trim(),name=value.getString("name").trim(),endpoint=value.getString("endpoint").trim();
        if(!id.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,79}")||name.isEmpty()||name.length()>80||endpoint.length()>2048)throw new IllegalArgumentException("MCP配置的标识、名称或地址无效");
        URI uri;try{uri=new URI(endpoint);}catch(Exception invalid){throw new IllegalArgumentException("请输入有效的HTTPS服务地址");}
        if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null)throw new IllegalArgumentException("MCP仅接受无账号信息、查询参数和片段的HTTPS地址；本版不支持凭据导入");
        return new JSONObject().put("id",id).put("name",name).put("endpoint",endpoint).put("protocolVersion",value.optString("protocolVersion","2026-07-28"));
    }
    public synchronized JSONObject accountSnapshot()throws Exception{
        JSONObject snapshot=accounts().snapshot();
        JSONObject login=snapshot.optJSONObject("login");
        authorizationUrls.keySet().removeIf(id->login==null||!id.equals(login.optString("sessionId"))||!"pending".equals(login.optString("status"))||login.optLong("expiresAt")<=System.currentTimeMillis());
        if(accountInferenceInstalled){
            try{
                dev.pocketengine.core.ModelConfig.AccountModelRef reference=context.settings.selectedAccountModel();
                selectedModel=reference==null?null:reference.toJson();
                snapshot.put("mainModelRoute",reference==null?"api_key":"account");
            }catch(Exception invalid){
                selectedModel=null;snapshot.put("mainModelRoute","invalid").put("modelSelectionError","已保存的账号模型引用不可用，请刷新目录后重新选择，或在设置中切回API模型。");
            }
        }
        boolean selectedUsable=false;
        if(selectedModel!=null){
            JSONObject row=findAccount(snapshot,selectedModel.optString("accountId"));
            selectedUsable=row!=null&&row.optLong("version")==selectedModel.optLong("accountVersion")&&row.optBoolean("enabled")&&row.optBoolean("planUsageEnabled")&&!row.optBoolean("usageLimited")&&("connected".equals(row.optString("status"))||"cooldown".equals(row.optString("status"))&&row.optLong("cooldownUntil")<=System.currentTimeMillis());
            if(selectedUsable)try{
                dev.pocketengine.runtime.models.AccountCatalogFacts facts=accounts().modelFacts(selectedModel.optString("accountId"),selectedModel.optLong("accountVersion"),selectedModel.optString("modelId"));
                snapshot.put("selectedModelCatalogState",facts==null?"unobserved":"current");if(facts!=null)snapshot.put("selectedModelCatalogFacts",facts.toJson());
            }catch(GatewayFailure stale){selectedUsable=false;snapshot.put("selectedModelCatalogState","refresh_required");}
            snapshot.put("selectedModelRef",new JSONObject(selectedModel.toString())).put("selectedModelUsable",selectedUsable);
            if(accountInferenceInstalled){dev.pocketengine.core.ModelConfig selected=context.settings.get();if(selected.usesAccount())snapshot.put("selectedModelOptions",selected.publicJson());}
        }
        return snapshot.put("nativeSnapshotRevision",++accountSnapshotRevision).put("mainInferenceAvailable",accountInferenceInstalled).put("mainInferenceReady",accountInferenceInstalled&&selectedUsable)
            .put("mainInferenceSupportsImages",selectedUsable&&selectedModel!=null&&accounts().supportsImageInput(selectedModel.optString("accountId"),selectedModel.optLong("accountVersion"),selectedModel.optString("modelId"))).put("mainInferenceSupportsAudioVideo",false)
            .put("modelSelectionPurpose",accountInferenceInstalled?"main_inference":"account_catalog_only")
            .put("mainInferenceMessage",accountInferenceInstalled?"使用所选 ChatGPT 账号和模型的套餐推理。图片仅在当前目录明确支持、机主允许发送且原任务已授权时发送；不接入音视频或文件上传。运行内续期仍固定原账号，不会自动换号或重试推理。":"账号连接与模型目录可用；主对话和手机任务暂仍使用设置中的API密钥模型，尚未切换到此账号。");
    }

    public JSONObject account(String command,JSONObject payload,GatewayCall operation)throws Exception{
        AccountGateway gateway=accounts();String id=payload.optString("accountId");
        if(command.equals("snapshot"))return accountSnapshot();
        if(command.equals("beginLogin")){
            JSONObject result=gateway.beginLoginReviewed(payload.optString("label"),id,id.isEmpty()?0:accountVersion(payload,"expectedVersion"));
            String session=result.getString("sessionId"),url=result.getString("authorizationUrl");
            synchronized(this){authorizationUrls.clear();authorizationUrls.put(session,url);}
            result.remove("authorizationUrl");return result;
        }
        if(command.equals("cancelLogin")){
            JSONObject result=gateway.cancelLogin(payload.getString("sessionId"));synchronized(this){authorizationUrls.remove(payload.getString("sessionId"));}return result;
        }
        long version=accountVersion(payload,"expectedVersion");
        switch(command){
            case "acknowledgePlanWelcome":return gateway.acknowledgePlanWelcome(id,version);
            case "retryPlanUsage":return gateway.retryPlanUsageReviewed(id,version);
            case "select":return gateway.selectReviewed(id,version);
            case "setEnabled":return gateway.setEnabledReviewed(id,version,payload.getBoolean("enabled"));
            case "refresh":return gateway.refreshReviewed(id,version,operation);
            case "logout":return gateway.logoutReviewed(id,version,operation);
            case "models":return catalog(gateway.modelsReviewed(id,version,operation));
            default:throw new IllegalArgumentException("此操作不属于原生账号管理");
        }
    }
    private JSONObject catalog(JSONObject result)throws Exception{
        String id=result.getString("accountId");long version=result.getLong("version");JSONArray models=new JSONArray(),raw=result.getJSONArray("models");
        for(int i=0;i<raw.length();i++){
            JSONObject model=raw.getJSONObject(i);String modelId=model.getString("id");
            JSONObject ref=new JSONObject().put("provider",ChatGptProvider.ID).put("accountId",id).put("accountVersion",version).put("modelId",modelId);
            models.put(new JSONObject().put("id",modelId).put("displayName",model.optString("displayName",modelId)).put("nativeRef",ref).put("imageInput",model.optJSONObject("imageInput")).put("catalogFacts",model.optJSONObject("catalogFacts")));
        }
        JSONObject publicResult=new JSONObject().put("accountId",id).put("accountVersion",version).put("models",models).put("mainInferenceReady",accountInferenceInstalled);
        // The prior-review cache needs only identities. Do not retain a second full per-model facts catalog.
        JSONArray reviewedIds=new JSONArray();for(int i=0;i<models.length();i++)reviewedIds.put(new JSONObject().put("id",models.getJSONObject(i).getString("id")));
        synchronized(this){catalogs.put(id,new JSONObject().put("accountId",id).put("accountVersion",version).put("models",reviewedIds));}return publicResult;
    }
    public JSONObject selectModel(JSONObject payload,GatewayCall operation)throws Exception{
        Set<String> allowed=Set.of("provider","accountId","accountVersion","modelId","reasoningEffort","imageTokenReserve","outputReserve");
        if(!allowed.containsAll(keys(payload))||!ChatGptProvider.ID.equals(payload.optString("provider")))throw new GatewayFailure("INVALID_NATIVE_MODEL_REFERENCE");
        JSONObject reference=new JSONObject().put("provider",payload.getString("provider")).put("accountId",payload.getString("accountId"))
                .put("accountVersion",payload.get("accountVersion")).put("modelId",payload.getString("modelId"));
        dev.pocketengine.core.ModelConfig.AccountModelRef nativeRef=dev.pocketengine.core.ModelConfig.AccountModelRef.fromJson(reference);
        String id=nativeRef.accountId,model=nativeRef.modelId;long version=nativeRef.accountVersion;
        Object effort=payload.has("reasoningEffort")?payload.get("reasoningEffort"):"";if(!(effort instanceof String))throw new IllegalArgumentException("推理选项无效");
        dev.pocketengine.runtime.models.ReasoningOptions reasoning=((String)effort).isEmpty()?dev.pocketengine.runtime.models.ReasoningOptions.DEFAULT:
                new dev.pocketengine.runtime.models.ReasoningOptions(dev.pocketengine.runtime.models.ReasoningOptions.Mode.OPENAI_EFFORT,(String)effort,0);
        int imageReserve=accountReserve(payload,"imageTokenReserve",0,0,2000000);
        int outputReserve=accountReserve(payload,"outputReserve",payload.has("outputReserve")?4096:context.settings.get().maxOutputTokens,128,32768);
        synchronized(this){if(!catalogContains(catalogs.get(id),version,model))throw new GatewayFailure("STALE_MODEL_CATALOG");}
        JSONObject fresh=catalog(accounts().modelsReviewed(id,version,operation));
        if(!catalogContains(fresh,version,model))throw new GatewayFailure("STALE_MODEL_CATALOG");
        dev.pocketengine.runtime.models.AccountCatalogFacts facts=null;JSONArray rows=fresh.getJSONArray("models");
        for(int i=0;i<rows.length();i++)if(model.equals(rows.getJSONObject(i).optString("id")))facts=dev.pocketengine.runtime.models.AccountCatalogFacts.fromJson(rows.getJSONObject(i).getJSONObject("catalogFacts"));
        if(facts==null)throw new GatewayFailure("STALE_MODEL_CATALOG");
        facts.validateOptions(reasoning);
        dev.pocketengine.core.ModelConfig selection=new dev.pocketengine.core.ModelConfig("","","",false,1,10).withAccountModel(nativeRef).withAccountOptions(reasoning,imageReserve,outputReserve);
        facts.budget(selection); // Validate the local reserve before changing either native selection.
        accounts().selectModelReviewed(id,version,model);operation.check();
        if(accountInferenceInstalled)context.settings.selectAccountModel(reference,reasoning,imageReserve,outputReserve);
        synchronized(this){selectedModel=new JSONObject(reference.toString());}
        return new JSONObject().put("nativeRef",new JSONObject(reference.toString())).put("selected",true).put("mainInferenceReady",accountInferenceInstalled)
            .put("reasoning",reasoning.toJson()).put("imageTokenReserve",imageReserve).put("outputReserve",outputReserve).put("outputLimitSent",false)
            .put("message",accountInferenceInstalled?"已选择此账号模型与当前目录声明的推理选项；输出与图片数值仅作本地预留，不发送不支持的输出上限字段。API key设置仍保留。":"已核对账号模型和选项；主模型宿主尚未连接，没有替换主模型设置。");
    }
    private static int accountReserve(JSONObject payload,String key,int fallback,int min,int max)throws Exception {
        if(!payload.has(key))return fallback;Object value=payload.get(key);
        if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<min||((Number)value).longValue()>max)throw new IllegalArgumentException("本地预留范围无效");
        return ((Number)value).intValue();
    }
    private static boolean catalogContains(JSONObject catalog,long version,String model){
        if(catalog==null||catalog.optLong("accountVersion")!=version)return false;JSONArray rows=catalog.optJSONArray("models");
        for(int i=0;rows!=null&&i<rows.length();i++)if(model.equals(rows.optJSONObject(i).optString("id")))return true;return false;
    }
    /** Returned only to the Activity's explicit browser-click path, never snapshots, responses or logs. */
    public String authorizationUrl(String sessionId)throws Exception{
        JSONObject snapshot=accountSnapshot(),login=snapshot.optJSONObject("login");
        if(login==null||!sessionId.equals(login.optString("sessionId"))||!"pending".equals(login.optString("status"))||login.optLong("expiresAt")<=System.currentTimeMillis())throw new GatewayFailure("STALE_LOGIN_SESSION");
        JSONObject row=findAccount(snapshot,login.optString("accountId"));if(row==null||!row.optBoolean("enabled")||!"authorizing".equals(row.optString("status")))throw new GatewayFailure("STALE_LOGIN_SESSION");
        String value;synchronized(this){value=authorizationUrls.get(sessionId);}if(value==null)throw new GatewayFailure("LOGIN_URL_UNAVAILABLE");
        URI uri=new URI(value);if(!"https".equals(uri.getScheme())||!"auth.openai.com".equals(uri.getHost())||!"/api/accounts/authorize".equals(uri.getPath())||uri.getUserInfo()!=null||uri.getFragment()!=null)throw new GatewayFailure("INVALID_AUTHORIZATION_URL");return value;
    }
    public void cancelPendingLogin()throws Exception{
        AccountGateway gateway;synchronized(this){gateway=accounts;authorizationUrls.clear();}if(gateway==null)return;
        JSONObject login=gateway.snapshot().optJSONObject("login");if(login!=null&&Set.of("pending","exchanging").contains(login.optString("status")))gateway.cancelLogin(login.getString("sessionId"));
    }
    private static JSONObject findAccount(JSONObject snapshot,String id){JSONArray rows=snapshot.optJSONArray("accounts");for(int i=0;rows!=null&&i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null&&id.equals(row.optString("id")))return row;}return null;}
    private static Set<String> keys(JSONObject value){Set<String> keys=new java.util.HashSet<>();java.util.Iterator<String> iterator=value.keys();while(iterator.hasNext())keys.add(iterator.next());return keys;}
    private static long accountVersion(JSONObject value,String key)throws Exception{Object version=value.opt(key);if(!(version instanceof Integer||version instanceof Long)||((Number)version).longValue()<0)throw new IllegalArgumentException("请刷新并重新确认当前账号版本");return ((Number)version).longValue();}
    private static long positiveVersion(JSONObject value,String key)throws Exception{Object version=value.opt(key);if(!(version instanceof Integer||version instanceof Long)||((Number)version).longValue()<1)throw new IllegalArgumentException("请刷新并重新确认当前版本");return ((Number)version).longValue();}

    public JSONObject source(JSONObject reference)throws Exception{
        SourceRef ref=SourceRef.fromJson(reference);if(!"owner".equals(ref.ownerId)||!"private".equals(ref.spaceId))throw new SecurityException("此来源不属于当前私人空间");
        boolean nativeSaved="workspace_file".equals(ref.kind)||"task_evidence".equals(ref.kind)&&dev.pocketengine.features.memory.TaskSavedEvidence.matches(ref.sourceId);
        if(nativeSaved)sources.prepareSourceHead(ref.kind,ref.sourceId,null);
        SourceRef.State actual=sources.resolve(MemoryBook.Scope.from(ownerScope()),ref.kind,ref.sourceId);
        if(actual==null||actual.deleted||!ref.equals(actual.ref))throw new IllegalStateException("来源已变化或被删除，请刷新候选后查看");
        if(nativeSaved){KnowledgeLearningWorkflow.SourceDocument document=sources.read(ref);return new JSONObject().put("kind",ref.kind).put("title",document.title).put("text",document.text).put("sourceRef",ref.toJson()).put("historicalBodySnapshot",true).put("executionAuthorized",false);}
        if(ref.kind.equals("task_evidence")){
            synchronized(tasks){
                SourceRef.State current=sources.resolve(MemoryBook.Scope.from(ownerScope()),ref.kind,ref.sourceId);
                if(current==null||current.deleted||!ref.equals(current.ref))throw new IllegalStateException("来源任务已变化，请刷新后查看");
                String taskId=ref.sourceId.substring(5,ref.sourceId.length()-16);JSONObject task=tasks.get(taskId,false);
                if(task==null)throw new IllegalStateException("来源任务已不可用");
                JSONArray events=task.optJSONArray("events");JSONObject confirmation=null;
                for(int i=0;events!=null&&i<events.length();i++){JSONObject event=events.getJSONObject(i);if(event.optLong("seq")==ref.revision&&"user_verification".equals(event.optString("type")))confirmation=event;}
                if(confirmation==null)throw new IllegalStateException("来源确认记录已不可用");
                String feedback=confirmation.optString("feedback").trim();
                String text="任务目标："+task.optString("goal")+"\n\n来源确认记录：主人已确认完成"+(feedback.isEmpty()?"":"\n"+feedback)+"\n\n当前任务摘要（可能已更新，并非确认时的正文快照）：\n"+task.optString("summary");
                return new JSONObject().put("kind",ref.kind).put("title","任务来源与确认记录").put("taskId",taskId).put("text",text).put("historicalBodySnapshot",false).put("sourceRef",ref.toJson());
            }
        }
        EngineModule module=host.module("assistant");if(!(module instanceof AssistantModule))throw new IllegalStateException("对话来源暂不可用");
        JSONArray metadata=((AssistantModule)module).sourceStates(ownerScope());
        for(int i=0;i<metadata.length();i++){
            JSONObject entry=metadata.getJSONObject(i);if(!ref.sourceId.equals(entry.optString("sourceId")))continue;
            JSONObject conversation=module.handle("get",new JSONObject().put("conversationId",entry.getString("conversationId")));JSONArray messages=conversation.getJSONArray("messages");
            for(int j=0;j<messages.length();j++){JSONObject message=messages.getJSONObject(j);if(ref.sourceId.equals(message.optString("id"))&&ref.revision==message.optLong("revision"))return new JSONObject().put("kind",ref.kind).put("title","原对话消息").put("text",message.getString("content")).put("sourceRef",ref.toJson());}
        }
        throw new IllegalStateException("来源消息已变化或被删除");
    }
}
