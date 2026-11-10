package dev.pocketengine.runtime.models;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.context.ContextBudgetPolicy;
import dev.pocketengine.runtime.usage.ModelPrice;
import dev.pocketengine.runtime.usage.InferencePricing;
import dev.pocketengine.runtime.context.ContextWindow;
import dev.pocketengine.runtime.providers.ProviderAdapters;
import dev.pocketengine.store.SettingsStore;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** One native owner workflow over retained API settings and the original catalog; never a model tool. */
public final class ModelConnectionService {
    public static final long MAX_CATALOG_AGE_MILLIS = 6 * 60 * 60 * 1000L;
    public void refreshPublicIndexAsync() { catalog.refreshPublicIndexAsync(); }
    private final SettingsStore settings;
    private final ModelCatalog catalog;
    private final CatalogHttpClient http;
    private final Clock clock;
    private final ConnectionInferenceObservations inferenceObservations;
    private final ModelSelectionPreferences modelPreferences;
    private final ModelMetadataSuggestions metadataSuggestions;
    private final java.util.concurrent.atomic.AtomicLong metadataGeneration=new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean recentUseSaveFailed;
    private volatile dev.pocketengine.store.TaskStore ownerTasks;
    private JSONObject lastProbe;
    private Attempt attempt;
    private final Map<String,Attempt> connectionAttempts=new java.util.LinkedHashMap<>();
    private record Attempt(ModelBinding binding, ModelCatalog.Ticket ticket, long started, String status, String code,long sourceRevision) {
        private Attempt(ModelBinding binding,ModelCatalog.Ticket ticket,long started,String status,String code){this(binding,ticket,started,status,code,0);}
    }

    public ModelConnectionService(SettingsStore settings, Path privateCache, Clock clock) throws IOException {
        this(settings, new ModelCatalog(privateCache, clock), new CatalogHttpClient(clock, 15000), clock);
    }
    public ModelConnectionService(SettingsStore settings, ModelCatalog catalog, CatalogHttpClient http, Clock clock) {
        this.settings=Objects.requireNonNull(settings);this.catalog=Objects.requireNonNull(catalog);
        this.http=Objects.requireNonNull(http);this.clock=Objects.requireNonNull(clock);inferenceObservations=new ConnectionInferenceObservations(clock);
        modelPreferences=new ModelSelectionPreferences(catalog.selectionMetadataPath());
        metadataSuggestions=new ModelMetadataSuggestions(catalog.thirdPartyMetadataPath());
    }
    public ModelSelectionCoordinator modelSelection(){return settings.modelSelection();}

    /** Application-only mounting. Existing AgentRunner/context and Gateway remain the consumers. */
    public void install() {
        dev.pocketengine.runtime.usage.InferenceCapture.installPresentationObserver(new dev.pocketengine.runtime.usage.InferenceCapture.Sink(){
            public void started(dev.pocketengine.runtime.usage.InferenceReceipt receipt){}
            public void finished(dev.pocketengine.runtime.usage.InferenceReceipt receipt)throws Exception{
                inferenceObservations.committed(receipt);
                if(receipt.outcome==dev.pocketengine.runtime.usage.InferenceReceipt.Outcome.COMPLETED
                        &&receipt.context.purpose!=dev.pocketengine.runtime.usage.InferenceReceipt.Purpose.MODEL_CAPABILITY_PROBE
                        &&receipt.context.purpose!=dev.pocketengine.runtime.usage.InferenceReceipt.Purpose.CONNECTION_TEST
                        &&receipt.context.purpose!=dev.pocketengine.runtime.usage.InferenceReceipt.Purpose.UNATTRIBUTED)try{
                    JSONObject route=receipt.toJson().getJSONObject("route");String id="account".equals(route.optString("kind"))?"account:"+route.optString("accountId"):route.optString("connectionId");
                    if(!id.isEmpty()){modelPreferences.used(new ModelSelectionPreferences.Key(id,route.getString("requestedModel")),receipt.finishedAt);recentUseSaveFailed=false;modelSelection().changed();}
                }catch(Exception metadataFailure){recentUseSaveFailed=true;modelSelection().changed();}
            }
        });
        ModelRequestPolicy.installHostSource(this::resolve);
        dev.pocketengine.runtime.ModelGateway.installModelGroupAdmission(settings::requireModelGroupAdmission);
        StepModelRouting.installHostSource(new StepModelRouting.Source() {
            public StepModelRouting.Preference preference(ModelConfig original) throws Exception {
                JSONObject value=settings.stepRoutingPreferences();if(!value.getBoolean("enabled"))return null;
                var selected=new StepModelRouting.Preference(value.getLong("revision"),value.getString("planFingerprint"),
                        value.getString("candidateId"),value.getInt("minimumSuccesses"));
                requireCurrent(selected,original);return selected;
            }
            public void requireCurrent(StepModelRouting.Preference expected,ModelConfig original) throws Exception {
                synchronized(settings){JSONObject live=settings.stepRoutingPreferences();
                    if(!live.getBoolean("enabled")||live.getLong("revision")!=expected.revision()
                            ||!expected.planFingerprint().equals(StepModelRouting.planFingerprint(original.routePlan))
                            ||!expected.planFingerprint().equals(StepModelRouting.planFingerprint(settings.get().routePlan)))
                        throw new SecurityException("step_routing_original_configuration_changed");
                }
            }
            public boolean capabilitiesKnown(ModelConfig candidate,boolean images)throws Exception {return stepRoutingCapabilities(candidate,images);}
        });
        InferencePricing.installHostSource(this::resolvePrice);
        settings.installImageCapabilities(config -> {
            ModelCatalog.View view=resolve(config);if(view==null)return false;
            ModelFacts.Fact<Boolean> image = view.facts().imageInput();
            return image.known && image.value;
        });
        ContextBudgetPolicy.installHostSource(config -> {
            if(config.usesAccount()){
                AccountCatalogFacts facts=dev.pocketengine.runtime.ModelGateway.accountModelFacts(config.accountModel);
                return facts==null?null:facts.budget(config);
            }
            if(config.capabilityBinding==null)return null;
            ModelCatalog.View view=resolve(config);if(view==null)return null;ModelFacts.Fact<ModelFacts.TokenWindow> fact=view.facts().contextWindow();
            if(!fact.known)return null;
            ModelRequestPolicy.validateOutput(config,view.facts());
            long total=fact.value.kind()==ModelFacts.WindowKind.INPUT_ONLY?Math.addExact(fact.value.tokens(),config.maxOutputTokens):fact.value.tokens();
            return ContextBudgetPolicy.RouteBudget.forRoute(config,new ContextWindow.Budget(total,config.maxOutputTokens,0,ContextBudgetPolicy.REQUEST_BYTES,0,config.imageTokenReserve),
                    fact.source==ModelFacts.Source.OWNER_OVERRIDE?ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE:ContextBudgetPolicy.SourceKind.PROVIDER_METADATA,
                    Math.max(1,view.ownerRevision()),Math.max(1,fact.observedAtMillis));
        });
    }

    private boolean stepRoutingCapabilities(ModelConfig selected,boolean images)throws Exception {
        ModelFacts facts;
        if(selected.usesAccount()){
            AccountCatalogFacts account=dev.pocketengine.runtime.ModelGateway.accountModelFacts(selected.accountModel);
            if(account==null)return false;facts=account.facts;
        }else {ModelCatalog.View view=resolve(selected);if(view==null)return false;facts=view.facts();}
        return facts.toolCalls().known&&Boolean.TRUE.equals(facts.toolCalls().value)
                &&(!images||facts.imageInput().known&&Boolean.TRUE.equals(facts.imageInput().value));
    }
    /** Metadata-only opt-in over existing MAIN candidates. No model selection, probe or HTTP request. */
    public JSONObject stepRoutingSnapshot()throws Exception {
        JSONObject out=settings.stepRoutingPreferences();JSONArray candidates=new JSONArray();out.put("candidates",candidates).put("currentPlanFingerprint","").put("configurationCurrent",false);
        ModelConfig current;
        try{current=settings.get();}catch(Exception unavailable){return out.put("availability","main_selection_unavailable");}
        ModelRoutePlan plan=current.routePlan;var primary=StepModelRouting.primary(plan);
        if(plan==null||plan.groupRevision<1||primary==null||!"MAIN".equals(plan.purpose)||!"APP_OWNER".equals(plan.audience))
            return out.put("availability","select_existing_main_group");
        String fingerprint=StepModelRouting.planFingerprint(plan);out.put("currentPlanFingerprint",fingerprint)
                .put("configurationCurrent",fingerprint.equals(out.optString("planFingerprint"))).put("primaryModel",primary.config().model);
        for(var candidate:plan.candidates)if(candidate!=primary&&candidate.config()!=null&&candidate.exclusion().isEmpty()){
            boolean known=false;try{known=stepRoutingCapabilities(candidate.config(),current.sendScreenshots);}catch(Exception unavailable){/* Unknown stays unknown. */}
            candidates.put(new JSONObject().put("candidateId",candidate.candidateId()).put("model",candidate.config().model)
                    .put("provider",candidate.config().provider).put("capabilitiesKnown",known));
        }
        return out.put("availability",candidates.length()==0?"no_existing_light_candidate":"available");
    }
    public JSONObject saveStepRouting(boolean enabled,long expectedRevision,String expectedPlanFingerprint,String candidateId)throws Exception {
        synchronized(settings){
            if(enabled){ModelConfig selected=settings.get();ModelRoutePlan plan=selected.routePlan;var primary=StepModelRouting.primary(plan);
                if(plan==null||plan.groupRevision<1||primary==null||!"MAIN".equals(plan.purpose)||!"APP_OWNER".equals(plan.audience)
                        ||!expectedPlanFingerprint.equals(StepModelRouting.planFingerprint(plan)))throw new SecurityException("step_routing_original_configuration_changed");
                ModelRoutePlan.Candidate chosen=null;for(var c:plan.candidates)if(c!=primary&&c.candidateId().equals(candidateId)&&c.config()!=null&&c.exclusion().isEmpty())chosen=c;
                if(chosen==null||!stepRoutingCapabilities(chosen.config(),selected.sendScreenshots))throw new IllegalStateException("轻量候选的工具或图片能力未知，请先在原模型目录核对。");
            }
            settings.saveStepRoutingPreference(enabled,expectedRevision,expectedPlanFingerprint,candidateId);
        }
        return stepRoutingSnapshot();
    }

    /** Native owner mounting only. Never accepts a replacement for the original TaskStore. */
    public synchronized void attachOwnerStore(dev.pocketengine.store.TaskStore tasks){
        Objects.requireNonNull(tasks);if(ownerTasks!=null&&ownerTasks!=tasks)throw new SecurityException("original_model_probe_store_required");ownerTasks=tasks;
    }
    public JSONObject probe(ModelBinding expected,boolean image,RunControl control)throws Exception {
        return probe(expected,null,-1,image,control);
    }
    /** A reviewed UI start also pins the model and selection revision under the original Settings monitor. */
    public JSONObject probe(ModelBinding expected,String expectedModel,long expectedSelectionRevision,boolean image,RunControl control)throws Exception {
        dev.pocketengine.store.TaskStore tasks=ownerTasks;if(tasks==null)throw new IllegalStateException("原模型诊断账本尚未挂载");
        final ModelConfig captured;final long selection;
        synchronized(settings){captured=requireProbeConfig(expected);selection=settings.apiSelectionRevision();
            if(expectedModel!=null&&(!expectedModel.equals(captured.model)||expectedSelectionRevision!=selection))throw new SecurityException("原审阅模型或选择修订已变化，未开始诊断");
            synchronized(this){lastProbe=null;}}
        try(var usage=tasks.beginModelCapabilityProbe(java.util.UUID.randomUUID().toString(),control,image)){
            var nativeTarget=usage.target();
            dev.pocketengine.runtime.models.InferenceAudiencePolicy.require(captured,NativeInferenceScope.ownerApp("owner","private",nativeTarget.context.taskId,nativeTarget.context.runId,nativeTarget.context.epoch,control),control);
            var target=dev.pocketengine.runtime.usage.InferenceCapture.withAdmission(nativeTarget,()->{control.check();synchronized(settings){if(settings.apiSelectionRevision()!=selection||!settings.matchesApiCatalogRoute(captured))throw new SecurityException("原模型配置已变化，探测已停止");requireProbeConfig(expected);}});
            try{JSONObject result=ModelCapabilityProbe.run(captured,image,control,target);control.check();
                synchronized(settings){if(settings.apiSelectionRevision()!=selection||!settings.matchesApiCatalogRoute(captured))throw new SecurityException("原模型配置已变化，旧探测未用于当前模型");requireProbeConfig(expected);}
                result.put("selectionRevision",selection);JSONObject ref=usage.reference();tasks.event(ref.getString("taskId"),ref.getString("runId"),ref.getInt("epoch"),new JSONObject().put("type","model_capability_probe").put("probe",result));
                JSONObject retained=new JSONObject(result.toString()).put("selectionRevision",selection).put("inferenceReference",ref);synchronized(this){lastProbe=retained;}usage.finish("completed");return new JSONObject(retained.toString());
            }catch(Exception failure){usage.finish(control.isCancelled()||failure instanceof InterruptedException?"cancelled":"failed");throw failure;}
        }
    }

    public ModelCatalog.View resolve(ModelConfig selected) throws Exception {
        synchronized(settings){
            if(!settings.matchesApiCatalogRoute(selected))throw new ModelRequestPolicy.Rejected("stale_model_credential","模型凭据与所选修订不匹配，请重新核对连接。");
            if(!selected.catalogEnforced&&selected.reasoning.mode()==ReasoningOptions.Mode.DEFAULT)return null;
            catalog.bind(selected.capabilityBinding);
            return catalog.get(new ModelCatalog.ModelKey(selected.capabilityBinding,selected.model),MAX_CATALOG_AGE_MILLIS);
        }
    }

    /** Quote lookup is local metadata only, separate from the original request/authority gates. */
    public ModelPrice resolvePrice(ModelConfig captured)throws Exception{
        synchronized(settings){if(captured.usesAccount()||captured.capabilityBinding==null||!settings.matchesApiCatalogRoute(captured))return null;
            catalog.bind(captured.capabilityBinding);return catalog.price(new ModelCatalog.ModelKey(captured.capabilityBinding,captured.model),MAX_CATALOG_AGE_MILLIS);}
    }
    /** Explicit alternate calculation; the original native receipt and its frozen basis are never changed. */
    public JSONObject reestimate(JSONObject original)throws Exception{
        JSONObject out=new JSONObject().put("format","pocketworld.inference-reestimate.v1").put("callId",original.optString("callId"))
            .put("originalReceiptUnchanged",true).put("newInference",false).put("known",false);
        JSONObject route=original.optJSONObject("route"),usage=original.optJSONObject("usage");
        if(original.optBoolean("projectionConflict")||route==null||usage==null||!"api_key".equals(route.optString("kind")))return out.put("reason","subscription_or_original_receipt_unavailable");
        synchronized(settings){ModelConfig metadata=settings.apiMetadataForPricing(route.optString("connectionId"),route.optString("requestedModel"));
            if(metadata==null)return out.put("reason","current_connection_not_available");
            catalog.bind(metadata.capabilityBinding);ModelPrice current=catalog.price(new ModelCatalog.ModelKey(metadata.capabilityBinding,metadata.model),MAX_CATALOG_AGE_MILLIS);
            if(current==null)return out.put("reason","current_price_not_observed");JSONObject estimate=InferencePricing.estimate(usage,current);
            return out.put("known",estimate.has("priceVersion")).put("reason",estimate.optString("reason","alternate_quote_only")).put("calculatedAt",clock.millis()).put("originalPriceVersion",usage.opt("priceVersion"))
                .put("currentPrice",current.toJson()).put("estimate",estimate).put("meaning","explicit_current_quote_not_a_replacement_of_historical_estimate");}
    }
    public JSONObject price(ModelBinding expected,String model,long ownerRevision,JSONObject values,boolean clear)throws Exception{
        synchronized(settings){ModelConfig current=settings.apiConfigForCatalog();
            if(expected==null||!expected.equals(current.capabilityBinding)||!current.model.equals(model)||!settings.matchesApiCatalogRoute(current))throw new ModelRequestPolicy.Rejected("stale_model_binding","原连接/模型已变化，未保存旧价目。请重新核对原模型。");
            if(values!=null&&current.containsSensitive(values.toString()))throw new IllegalArgumentException("请勿把当前API密钥放入公开价目说明");
            if(current.usesBedrock())throw new IllegalArgumentException("此 Bedrock 连接尚无已接入的原生价目记录");
            catalog.bind(expected);catalog.updateOwnerPrice(new ModelCatalog.ModelKey(expected,model),ownerRevision,clear?null:ModelPrice.owner(expected,model,values,clock.millis()));return snapshot();}
    }

    /** Snapshot contains public references/facts only, including failures alongside the last real data. */
    public JSONObject snapshot() throws Exception {
        synchronized(settings){
            ModelConfig current=settings.apiConfigForCatalog();ModelBinding binding=current.catalogEnforced?current.capabilityBinding:null;
            JSONObject out=new JSONObject().put("api",current.publicJson()).put("catalogAvailable",!current.usesBedrock()).put("catalogUnavailableReason",current.usesBedrock()?"Bedrock 当前使用明确的模型 ID，未接入模型目录":"").put("activeRoute",settings.selectedAccountModel()!=null?"account":"api_key")
                    .put("modelGroups",settings.modelGroupsSnapshot()).put("catalogEnabled",binding!=null).put("connections",settings.apiConnectionsSnapshot()).put("publicModelIndex",catalog.publicIndexStatus()).put("maxCatalogAgeMillis",MAX_CATALOG_AGE_MILLIS).put("catalogTimeoutMillis",http.totalBudgetMillis())
                    .put("modelsEndpoint",binding==null?defaultEndpoint(current):binding.modelsEndpoint().toASCIIString())
                    .put("catalogFormat",binding==null?ModelCatalogRoutes.defaultFormat(current.provider).name():binding.format().name());
            out.put("probeSelectionRevision",settings.apiSelectionRevision());
            synchronized(this){if(lastProbe!=null&&current.capabilityBinding!=null
                    &&settings.matchesApiCatalogRoute(current)
                    &&lastProbe.optLong("selectionRevision",-1)==settings.apiSelectionRevision()
                    &&current.capabilityBinding.equals(ModelBinding.fromJson(lastProbe.getJSONObject("binding")))
                    &&current.model.equals(lastProbe.optString("model"))
                    &&ModelRoutePlan.configurationDigest(current).equals(lastProbe.optString("configurationDigest")))
                out.put("capabilityProbe",new JSONObject(lastProbe.toString()));}
            try{ModelConfig active=settings.get();out.put("activeRoute",active.usesAccount()?"account":"api_key").put("nativeSelection",active.publicJson());}catch(Exception unavailable){out.put("selectionUnavailable",true).put("selectionMessage","所选模型组或原连接已变化；请重新核对用途选择。没有回退。");}
            JSONArray models=new JSONArray();
            if(binding!=null){
                catalog.bind(binding);out.put("binding",binding.toJson());
                boolean selected=false;
                for(ModelCatalog.View view:catalog.list(binding,MAX_CATALOG_AGE_MILLIS)){models.put(view.toJson());selected|=view.key().modelId().equals(current.model);}
                if(!selected&&!current.model.isEmpty())models.put(catalog.get(new ModelCatalog.ModelKey(binding,current.model),MAX_CATALOG_AGE_MILLIS).toJson());
                if(!current.model.isEmpty())out.put("selected",catalog.get(new ModelCatalog.ModelKey(binding,current.model),MAX_CATALOG_AGE_MILLIS).toJson());
            }
            JSONObject connections=out.getJSONObject("connections");JSONArray profiles=connections.getJSONArray("connections");
            for(int i=0;i<profiles.length();i++){JSONObject profile=profiles.getJSONObject(i);profile.put("inferenceObservation",inferenceObservations.snapshot(ModelBinding.fromJson(profile.getJSONObject("binding")),profile.getString("model")));}
            out.put("inferenceObservation",inferenceObservations.snapshot(current.capabilityBinding,current.model));
            if(current.capabilityBinding!=null&&!current.model.isEmpty())out.put("metadataSuggestion",metadataSuggestions.snapshot(current.capabilityBinding,current.model,clock.millis()));
            out.put("models",models).put("reasoning",current.reasoning.toJson()).put("imageTokenReserve",current.imageTokenReserve);
            JSONObject pricing=new JSONObject().put("known",false).put("quote",JSONObject.NULL).put("reason","price_not_observed");
            if(!current.usesBedrock()&&current.capabilityBinding!=null&&!current.model.isEmpty()&&settings.matchesApiCatalogRoute(current)){
                catalog.bind(current.capabilityBinding);var key=new ModelCatalog.ModelKey(current.capabilityBinding,current.model);ModelPrice observed=catalog.priceObservation(key),active=catalog.price(key,MAX_CATALOG_AGE_MILLIS);
                pricing.put("binding",current.capabilityBinding.toJson()).put("model",current.model).put("ownerRevision",catalog.get(key,MAX_CATALOG_AGE_MILLIS).ownerRevision())
                    .put("known",active!=null).put("quote",observed==null?JSONObject.NULL:observed.toJson()).put("reason",active!=null?"quote_available_not_a_bill":observed!=null?"quote_not_current":"price_not_observed");
            }if(current.usesBedrock())pricing.put("reason","bedrock_price_not_observed");out.put("pricing",pricing);
            Attempt observed; synchronized(this){observed=attempt;}
            if(observed!=null&&Objects.equals(observed.binding,binding))out.put("refreshAttempt",new JSONObject().put("startedAtMillis",observed.started).put("status",observed.status).put("code",observed.code));
            else out.put("refreshAttempt",JSONObject.NULL);
            return out;
        }
    }

    private static String defaultEndpoint(ModelConfig config) {
        try{return ModelCatalogRoutes.modelsEndpoint(config).toASCIIString();}catch(Exception unconfigured){return "";}
    }
    /** One saved source's public directory, independent of every current task/default selection. */
    public JSONObject connectionSnapshot(String connectionId,long expectedRevision)throws Exception {
        synchronized(settings){
            ApiConnectionProfile profile=settings.apiConnectionMetadata(connectionId,expectedRevision);ModelConfig config=profile.configuration;
            ModelBinding binding=config.capabilityBinding;catalog.bind(binding);JSONArray models=new JSONArray();boolean configured=false;
            if(!config.usesBedrock())for(ModelCatalog.View view:catalog.list(binding,MAX_CATALOG_AGE_MILLIS)){
                models.put(view.toJson());configured|=view.key().modelId().equals(config.model);
            }
            if(!configured&&!config.model.isEmpty())models.put(catalog.get(new ModelCatalog.ModelKey(binding,config.model),MAX_CATALOG_AGE_MILLIS).toJson());
            JSONObject out=new JSONObject().put("format","pocketworld.connection-catalog.v1").put("connection",profile.publicJson()).put("binding",binding.toJson())
                    .put("models",models).put("catalogAvailable",!config.usesBedrock()).put("canRefresh",profile.enabled&&!config.usesBedrock())
                    .put("catalogUnavailableReason",config.usesBedrock()?"Bedrock 当前使用明确的模型 ID，未接入模型目录":"")
                    .put("modelsEndpoint",binding.modelsEndpoint().toASCIIString()).put("catalogFormat",binding.format().name())
                    .put("publicModelIndex",catalog.publicIndexStatus()).put("maxCatalogAgeMillis",MAX_CATALOG_AGE_MILLIS).put("catalogTimeoutMillis",http.totalBudgetMillis()).put("selectionChanged",false);
            Attempt observed;synchronized(this){observed=connectionAttempts.get(connectionId);}
            out.put("refreshAttempt",observed!=null&&observed.sourceRevision==expectedRevision&&observed.binding.equals(binding)?new JSONObject().put("startedAtMillis",observed.started).put("status",observed.status).put("code",observed.code):JSONObject.NULL);
            return out;
        }
    }
    /** GET-only directory refresh. A switch to another source does not invalidate this source's work. */
    public JSONObject refreshConnection(String connectionId,long expectedRevision,RunControl control)throws Exception {
        final ModelConfig source;final ModelBinding binding;final ModelCatalog.Ticket ticket;
        synchronized(settings){
            source=settings.apiConfigForConnection(connectionId,expectedRevision);binding=source.capabilityBinding;
            if(source.usesBedrock())throw new ModelRequestPolicy.Rejected("catalog_unavailable","Bedrock 当前未接入模型目录，请手动填写支持的模型 ID");
            catalog.bind(binding);ticket=catalog.beginRefresh(binding);
            synchronized(this){connectionAttempts.put(connectionId,new Attempt(binding,ticket,clock.millis(),"refreshing","",expectedRevision));}
        }
        try{
            control.check();CatalogHttpClient.Headers headers=CatalogHttpClient.Headers.inference(ProviderAdapters.selected(source.provider).headers(source));
            CatalogHttpClient.Snapshot fetched=http.fetch(binding,headers,()->readCurrent(control)&&catalog.isCurrent(ticket)&&stillConnection(connectionId,expectedRevision,binding));
            requirePublicCatalog(source,fetched);
            synchronized(settings){
                control.check();if(!stillConnection(connectionId,expectedRevision,binding))throw new CatalogException(CatalogException.Code.CANCELLED);
                if(catalog.commitRefresh(ticket,fetched)!=ModelCatalog.RefreshResult.APPLIED)throw new CatalogException(CatalogException.Code.CANCELLED);
                setConnectionAttempt(connectionId,expectedRevision,binding,ticket,"succeeded","");
            }
        }catch(Exception failure){
            setConnectionAttempt(connectionId,expectedRevision,binding,ticket,"failed",failure instanceof CatalogException known?known.code.name():failure instanceof InterruptedException?"CANCELLED":"CATALOG_UNAVAILABLE");
            throw failure;
        }
        return connectionSnapshot(connectionId,expectedRevision);
    }
    /** Draft-only GET with a temporary identity. Neither credentials nor results enter the saved catalog. */
    public JSONObject previewConnectionCatalog(String id,long expectedRevision,JSONObject input,RunControl control)throws Exception {
        final ModelConfig draft;final ModelBinding binding;
        synchronized(settings){
            draft=settings.previewApiSource(id,expectedRevision,input);
            if(draft.usesBedrock())throw new IllegalArgumentException("Bedrock 当前使用明确的模型 ID，未接入模型目录");
            ModelConfig original=id==null||id.isEmpty()?null:settings.apiConnectionMetadata(id,expectedRevision).configuration;
            ModelBinding retained=original!=null&&original.provider.equals(draft.provider)&&original.baseUrl.equals(draft.baseUrl)?original.capabilityBinding:null;
            binding=new ModelBinding("draft-"+java.util.UUID.randomUUID(),draft.provider,ModelCatalogRoutes.protocol(draft.provider),retained==null?ModelCatalogRoutes.defaultFormat(draft.provider):retained.format(),retained==null?ModelCatalogRoutes.modelsEndpoint(draft):retained.modelsEndpoint(),
                    "draft-key-"+java.util.UUID.randomUUID(),1,"draft-install-"+java.util.UUID.randomUUID(),1,!ModelConfig.cleartextConsentFor(draft.provider,draft.baseUrl).isEmpty());
        }
        control.check();CatalogHttpClient.Snapshot fetched=http.fetch(binding,CatalogHttpClient.Headers.inference(ProviderAdapters.selected(draft.provider).headers(draft)),
                ()->readCurrent(control)&&(id==null||id.isEmpty()||draftSourceCurrent(id,expectedRevision)));
        control.check();if(id!=null&&!id.isEmpty()&&!draftSourceCurrent(id,expectedRevision))throw new CatalogException(CatalogException.Code.CANCELLED);
        requirePublicCatalog(draft,fetched);
        JSONArray rows=new JSONArray();
        for(var entry:fetched.models().entrySet()){
            ModelEndpointSupport support=fetched.endpointSupport().get(entry.getKey());
            rows.put(new JSONObject().put("modelId",entry.getKey()).put("facts",entry.getValue().toJson()).put("endpointSupport",support==null?JSONObject.NULL:support.toJson())
                    .put("protocolCompatibility",support==null?"unknown":support.supports(binding.protocol())?"declared_compatible":"declared_incompatible"));
        }
        return new JSONObject().put("format","pocketworld.draft-catalog.v1").put("status","succeeded").put("models",rows).put("saved",false).put("selectionChanged",false);
    }
    private boolean draftSourceCurrent(String id,long revision){try{settings.apiConnectionMetadata(id,revision);return true;}catch(Exception stale){return false;}}
    private static boolean readCurrent(RunControl control){try{control.check();return true;}catch(Exception unavailable){return false;}}
    public boolean connectionCurrent(String id,long revision,ModelBinding binding){try{return binding.equals(settings.apiConnectionMetadata(id,revision).configuration.capabilityBinding);}catch(Exception stale){return false;}}
    private static void requirePublicCatalog(ModelConfig source,CatalogHttpClient.Snapshot fetched)throws Exception {
        for(var entry:fetched.models().entrySet())if(source.containsSensitive(entry.getKey())||source.containsSensitive(entry.getValue().toJson().toString()))throw new CatalogException(CatalogException.Code.INVALID_CATALOG);
        for(var entry:fetched.prices().entrySet())if(source.containsSensitive(entry.getValue().toJson().toString()))throw new CatalogException(CatalogException.Code.INVALID_CATALOG);
    }
    public JSONObject reviewConnectionModel(String connectionId,long expectedRevision,String model)throws Exception {
        synchronized(settings){
            ApiConnectionProfile profile=settings.apiConnectionMetadata(connectionId,expectedRevision);ModelConfig config=profile.configuration;ModelBinding binding=config.capabilityBinding;
            catalog.bind(binding);ModelCatalog.View view=catalog.get(new ModelCatalog.ModelKey(binding,model),MAX_CATALOG_AGE_MILLIS);
            boolean compatible=view.endpointSupport()==null||view.endpointSupport().supports(binding.protocol());
            String unavailable=!profile.enabled?"connection_disabled":!compatible?"catalog_protocol_mismatch":"";
            if(unavailable.isEmpty()&&config.usesBedrock())unavailable=profile.secretSlot.isEmpty()?"credential_not_configured":System.currentTimeMillis()>=profile.credentialExpiresAtEpochMillis?"credential_expired":"";
            JSONObject row=view.toJson().put("connectionId",connectionId).put("connectionName",profile.name).put("connectionRevision",profile.revision)
                    .put("provider",config.provider).put("available",unavailable.isEmpty()).put("unavailableReason",unavailable);
            return new JSONObject().put("model",row).put("connection",profile.publicJson()).put("selectionChanged",false)
                    .put("options",new JSONObject().put("maxOutputTokens",config.maxOutputTokens).put("reasoning",ReasoningOptions.DEFAULT.toJson()).put("imageTokenReserve",config.catalogEnforced?config.imageTokenReserve:0));
        }
    }
    /** Native capture for conversation/run admission. It never changes source, defaults or catalog policy. */
    public ModelConfig captureBoundConnectionModel(ModelBinding original,String model,int output,ReasoningOptions reasoning,int imageTokenReserve)throws Exception {
        synchronized(settings){
            if(original==null)throw new SecurityException("原会话模型缺少来源绑定，请重新选择模型");
            JSONArray rows=settings.apiConnectionsSnapshot().getJSONArray("connections");
            for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);
                if(original.connectionId().equals(row.getString("id"))){
                    if(!original.equals(ModelBinding.fromJson(row.getJSONObject("binding"))))throw new SecurityException("原会话模型的来源、凭据或启停代次已变化，请重新选择模型");
                    return captureConnectionModel(original.connectionId(),row.getLong("revision"),model,output,reasoning,imageTokenReserve);
                }
            }
            throw new SecurityException("原会话模型来源已不可用，请重新选择模型");
        }
    }
    /** A fresh picker review additionally pins the exact source metadata revision it displayed. */
    public ModelConfig captureConnectionModel(String connectionId,long expectedRevision,String model,int output,ReasoningOptions reasoning,int imageTokenReserve)throws Exception {
        synchronized(settings){
            ModelConfig current=settings.apiConfigForConnection(connectionId,expectedRevision);ModelBinding binding=current.capabilityBinding;
            ModelCatalog.ModelKey key=new ModelCatalog.ModelKey(binding,model);
            if(output<128||output>32768)throw new IllegalArgumentException("输出上限需在128–32768之间");
            if(imageTokenReserve<0||imageTokenReserve>2000000)throw new IllegalArgumentException("图片预留需在0–2000000之间");
            Objects.requireNonNull(reasoning);catalog.bind(binding);ModelCatalog.View view=catalog.get(key,MAX_CATALOG_AGE_MILLIS);
            if(view.endpointSupport()!=null&&!view.endpointSupport().supports(binding.protocol()))throw new IllegalStateException("此模型明确声明使用其他协议，请重新核对原连接");
            // An explicit reasoning choice verifies the exact owner levels without enabling full
            // catalog enforcement for unrelated tool, image and output fields.
            boolean strict=current.catalogEnforced||imageTokenReserve>0;
            if(current.usesBedrock()&&(strict||reasoning.mode()!=ReasoningOptions.Mode.DEFAULT))
                throw new IllegalArgumentException("此 Bedrock 接入尚不支持目录推理参数，请保留默认参数");
            boolean image=current.model.equals(model)&&current.supportsImageInput;
            // Catalog evidence cannot silently become a durable manual owner declaration.
            if(strict)image=view.facts().imageInput().known&&Boolean.TRUE.equals(view.facts().imageInput().value);
            else if(view.facts().imageInput().known&&!Boolean.TRUE.equals(view.facts().imageInput().value))image=false;
            ModelConfig selected=new ModelConfig(current.baseUrl,current.apiKey,model,current.sendScreenshots,current.maxSteps,current.requestTimeoutSeconds,current.provider,output);
            if(current.usesBedrock())selected=selected.withBedrockSession(current.bedrockRegion,current.awsSession);
            selected=selected.withCleartextConsent(current.cleartextConsent).withOwnerImageInput(image).withLocalObservation(current.localCaptureAllowed,current.localOcrAllowed)
                    .withProviderCompatibility(current.model.equals(model)?current.compatibility:null);
            selected=strict?selected.withNativeCatalog(binding,reasoning,imageTokenReserve):selected.withReviewedReasoning(binding,reasoning);
            SettingsStore.validate(selected);ModelRequestPolicy.validateOutput(selected,view.facts());return selected;
        }
    }
    /** Explicit default selection, separate from saving a source and reading its models. */
    public JSONObject selectConnectionModel(String connectionId,long expectedRevision,String model,int output,ReasoningOptions reasoning,int imageTokenReserve)throws Exception {
        synchronized(settings){
            ModelConfig source=settings.apiConnectionMetadata(connectionId,expectedRevision).configuration;
            if(source.compatibility!=null&&!source.model.equals(model))throw new IllegalArgumentException("此来源保留了当前模型专属的兼容设置，请在此对话选择其他模型；运行中的任务会继续使用原设置");
            if(!source.catalogEnforced
                    &&(reasoning.mode()!=ReasoningOptions.Mode.DEFAULT||imageTokenReserve>0))
                throw new IllegalArgumentException("请在此对话设置推理参数；若要设为服务默认，请先明确启用该来源能力目录");
            ModelConfig captured=captureConnectionModel(connectionId,expectedRevision,model,output,reasoning,imageTokenReserve);
            settings.selectConnectionModel(connectionId,expectedRevision,captured);return snapshot();
        }
    }
    private boolean stillConnection(String id,long revision,ModelBinding binding){
        synchronized(settings){try{ApiConnectionProfile row=settings.apiConnectionMetadata(id,revision);return row.enabled&&binding.equals(row.configuration.capabilityBinding);}catch(Exception unavailable){return false;}}
    }
    private void setConnectionAttempt(String id,long revision,ModelBinding binding,ModelCatalog.Ticket ticket,String status,String code){
        if(!stillConnection(id,revision,binding))return;
        synchronized(this){Attempt current=connectionAttempts.get(id);if(current!=null&&current.ticket==ticket)connectionAttempts.put(id,new Attempt(binding,ticket,current.started,status,code,revision));}
    }
    /** Explicit owner opt-in/configuration; never called automatically by a task or refresh failure. */
    public JSONObject configure(ModelBinding expected,ModelBinding.Format format,URI modelsEndpoint) throws Exception {
        synchronized(settings){
            ModelConfig config=settings.configureApiCatalog(expected,format,modelsEndpoint);catalog.bind(config.capabilityBinding);
            return snapshot();
        }
    }

    public JSONObject refresh(ModelBinding expected,RunControl control) throws Exception {
        final ModelConfig selected;final ModelCatalog.Ticket ticket;final long selectionRevision;
        synchronized(settings){
            requireBinding(expected);selected=settings.apiConfigForCatalog();selectionRevision=settings.apiSelectionRevision();catalog.bind(expected);ticket=catalog.beginRefresh(expected);
            synchronized(this){attempt=new Attempt(expected,ticket,clock.millis(),"refreshing","");}
        }
        try{
            control.check();
            CatalogHttpClient.Headers headers=CatalogHttpClient.Headers.inference(ProviderAdapters.selected(selected.provider).headers(selected));
            CatalogHttpClient.Snapshot fetched=http.fetch(expected,headers,()->!control.isCancelled()&&!Thread.currentThread().isInterrupted()&&catalog.isCurrent(ticket)&&stillBound(expected,selectionRevision));
            synchronized(settings){
                control.check();requireBinding(expected);if(settings.apiSelectionRevision()!=selectionRevision)throw new CatalogException(CatalogException.Code.CANCELLED);
                ModelCatalog.RefreshResult result=catalog.commitRefresh(ticket,fetched);
                if(result==ModelCatalog.RefreshResult.APPLIED)setAttempt(expected,ticket,"succeeded","");
                else throw new CatalogException(CatalogException.Code.CANCELLED);
            }
        }catch(Exception failure){
            setAttempt(expected,ticket,"failed",failure instanceof CatalogException known?known.code.name():failure instanceof InterruptedException?"CANCELLED":"CATALOG_UNAVAILABLE");
            throw failure;
        }
        return snapshot();
    }

    public JSONObject editField(ModelBinding expected,String model,long ownerRevision,ModelFacts.Field field,ModelFacts.OwnerValue value,boolean clear) throws Exception {
        synchronized(settings){
            requireBinding(expected);catalog.bind(expected);
            catalog.updateOwner(new ModelCatalog.ModelKey(expected,model),ownerRevision,clear?Map.of():Map.of(field,Objects.requireNonNull(value)),clear?Set.of(field):Set.of());
            return snapshot();
        }
    }

    /**
     * The owner may declare the reasoning-effort vocabulary for one exact API connection/model.
     * A normal /models list has no universal reasoning schema. This does not probe the model,
     * grant tool/image access, or claim that a third-party gateway accepts the declared levels.
     */
    public JSONObject declareConnectionReasoning(ModelBinding expected,long connectionRevision,String model,
            long ownerRevision,java.util.List<String> levels)throws Exception{
        synchronized(settings){
            Objects.requireNonNull(expected);Objects.requireNonNull(levels);
            ApiConnectionProfile profile=settings.apiConnectionMetadata(expected.connectionId(),connectionRevision);
            ModelConfig config=profile.configuration;
            if(!profile.enabled||!expected.equals(config.capabilityBinding))
                throw new SecurityException("原推理来源或连接修订已变化，请重新读取");
            Set<String> allowed=switch(config.provider){
                case "openai_chat","openai_responses" -> Set.of("none","minimal","low","medium","high","xhigh","max");
                case "anthropic" -> Set.of("low","medium","high","xhigh","max");
                case "gemini" -> ModelReasoningProfiles.usesTokenBudget(model)
                        ? Set.of("low","medium","high") : Set.of("minimal","low","medium","high");
                default -> throw new IllegalArgumentException("此模型协议尚未接入可声明的推理强度");
            };
            ModelCatalog.ModelKey key=new ModelCatalog.ModelKey(expected,ModelBinding.modelId(model));
            catalog.bind(expected);
            ModelCatalog.View before=catalog.get(key,MAX_CATALOG_AGE_MILLIS);
            if(before.ownerRevision()!=ownerRevision)throw new IllegalStateException("这项模型的能力设置已变化，请重新读取");
            if(levels.size()>allowed.size()||new java.util.HashSet<>(levels).size()!=levels.size()
                    ||levels.stream().anyMatch(level->!allowed.contains(level)))
                throw new IllegalArgumentException("推理档位必须属于当前协议允许的候选集合，不能重复");
            long saved=levels.isEmpty()
                ?catalog.updateOwner(key,ownerRevision,Map.of(),
                    Set.of(ModelFacts.Field.REASONING,ModelFacts.Field.REASONING_LEVELS))
                :catalog.updateOwner(key,ownerRevision,Map.of(
                    ModelFacts.Field.REASONING,ModelFacts.OwnerValue.known(true),
                    ModelFacts.Field.REASONING_LEVELS,ModelFacts.OwnerValue.known(java.util.List.copyOf(levels))),
                    Set.of());
            ModelCatalog.View after=catalog.get(key,MAX_CATALOG_AGE_MILLIS);
            return new JSONObject().put("status","saved").put("modelId",model)
                .put("ownerRevision",saved).put("facts",after.facts().toJson());
        }
    }

    public JSONObject refreshMetadataSuggestion(ModelBinding expected,String model,String provider,RunControl control)throws Exception{
        final long selection,generation;
        synchronized(settings){requireMetadataSelection(expected,model);selection=settings.apiSelectionRevision();generation=metadataGeneration.incrementAndGet();}
        control.check();String body=http.modelsDev(()->!control.isCancelled()&&!Thread.currentThread().isInterrupted()&&metadataGeneration.get()==generation&&stillMetadataSelection(expected,model,selection));
        JSONObject advice=ModelMetadataSuggestions.parse(expected,model,provider,body,clock.millis());
        synchronized(settings){control.check();requireMetadataSelection(expected,model);if(selection!=settings.apiSelectionRevision()||generation!=metadataGeneration.get())throw new SecurityException("metadata_original_selection_changed");metadataSuggestions.save(expected,model,advice);return snapshot();}
    }
    public JSONObject clearMetadataSuggestion(ModelBinding expected,String model)throws Exception{
        synchronized(settings){requireMetadataSelection(expected,model);metadataGeneration.incrementAndGet();metadataSuggestions.clear(expected,model);return snapshot();}
    }
    private void requireMetadataSelection(ModelBinding expected,String model)throws Exception{ModelConfig current=settings.apiConfigForCatalog();if(expected==null||!expected.equals(current.capabilityBinding)||!model.equals(current.model)||!settings.matchesApiCatalogRoute(current))throw new SecurityException("metadata_original_selection_changed");}
    private boolean stillMetadataSelection(ModelBinding expected,String model,long selection){synchronized(settings){try{requireMetadataSelection(expected,model);return settings.apiSelectionRevision()==selection;}catch(Exception unavailable){return false;}}}

    public JSONObject select(ModelBinding expected,String model,int output,ReasoningOptions reasoning) throws Exception {
        return select(expected,model,output,reasoning,0);
    }
    public JSONObject select(ModelBinding expected,String model,int output,ReasoningOptions reasoning,int imageTokenReserve) throws Exception {
        synchronized(settings){
            requireBinding(expected);ModelConfig current=settings.apiConfigForCatalog();
            if(current.compatibility!=null&&!current.model.equals(model))throw new IllegalArgumentException("此来源保留了当前模型专属的兼容设置，请在此对话选择其他模型；运行中的任务会继续使用原设置");
            ModelConfig selection=new ModelConfig(current.baseUrl,current.apiKey,model,current.sendScreenshots,current.maxSteps,current.requestTimeoutSeconds,current.provider,output)
                    .withCleartextConsent(current.cleartextConsent).withNativeCatalog(expected,reasoning,imageTokenReserve).withProviderCompatibility(current.model.equals(model)?current.compatibility:null);
            ModelCatalog.View view=resolve(selection);if(view.endpointSupport()!=null&&!view.endpointSupport().supports(expected.protocol()))throw new IllegalStateException("此目录模型明确声明使用其他协议；请修改原连接的协议或手动核对模型，原配置仍保留。");ModelRequestPolicy.validateOutput(selection,view.facts());
            settings.selectCatalogModel(expected,model,output,reasoning,imageTokenReserve);
            return snapshot();
        }
    }

    /** Diagnostics bind the selected API profile even when metadata lookup was never enabled. */
    private ModelConfig requireProbeConfig(ModelBinding expected) throws Exception {
        ModelConfig current=settings.apiConfigForCatalog();
        if(expected==null||!expected.equals(current.capabilityBinding)||!settings.matchesApiCatalogRoute(current))throw new ModelRequestPolicy.Rejected("stale_model_binding","原API连接、密钥或配置修订已变化，探测未继续；没有改换模型。");
        return current;
    }
    private void requireBinding(ModelBinding expected) throws Exception {
        if(settings.apiConfigForCatalog().usesBedrock()||expected!=null&&expected.protocol()==ModelBinding.Protocol.BEDROCK_ANTHROPIC)throw new ModelRequestPolicy.Rejected("catalog_unavailable","Bedrock 当前未提供模型目录；请在原连接明确填写支持的模型 ID");
        if(expected==null||!expected.equals(settings.apiCatalogBinding())||!settings.matchesApiCatalogRoute(settings.apiConfigForCatalog()))throw new ModelRequestPolicy.Rejected("stale_model_binding","模型目录、连接或密钥修订已变化，请刷新；没有使用旧能力或切换模型。");
    }
    private boolean stillBound(ModelBinding binding) {try{return binding.equals(settings.apiCatalogBinding())&&settings.matchesApiCatalogRoute(settings.apiConfigForCatalog());}catch(Exception unavailable){return false;}}
    private boolean stillBound(ModelBinding binding,long selectionRevision){try{return stillBound(binding)&&settings.apiSelectionRevision()==selectionRevision;}catch(Exception unavailable){return false;}}
    /** Existing owner model page, no feature/model tool and no automatic network request. */
    public JSONObject connections()throws Exception{synchronized(settings){settings.apiConnections();return snapshot();}}
    public JSONObject saveConnection(String id,long revision,String name,JSONObject input)throws Exception{synchronized(settings){settings.saveApiConnection(id,revision,name,input);return snapshot();}}
    public JSONObject saveSource(String id,long revision,String name,JSONObject input)throws Exception{synchronized(settings){
        JSONObject saved=settings.saveApiConnection(id,revision,name,input);
        return new JSONObject().put("status","saved").put("savedConnection",saved).put("snapshot",snapshot())
                .put("catalog",connectionSnapshot(saved.getString("id"),saved.getLong("revision"))).put("selectionChanged",false);
    }}
    public JSONObject selectConnection(String id,long revision)throws Exception{synchronized(settings){settings.selectApiConnection(id,revision);return snapshot();}}
    public JSONObject addAndSelectConnection(String name,JSONObject input)throws Exception{synchronized(settings){settings.addAndSelectApiConnection(name,input);return snapshot();}}
    public JSONObject enableConnection(String id,long revision,boolean enabled)throws Exception{synchronized(settings){settings.setApiConnectionEnabled(id,revision,enabled);return snapshot();}}
    public JSONObject enableSource(String id,long revision,boolean enabled)throws Exception{synchronized(settings){
        JSONObject saved=settings.setApiConnectionEnabled(id,revision,enabled);
        return new JSONObject().put("status","saved").put("savedConnection",saved).put("snapshot",snapshot()).put("selectionChanged",false);
    }}
    public JSONObject saveGroup(String id,long revision,String name,JSONObject definition)throws Exception{synchronized(settings){settings.saveModelGroup(id,revision,name,definition);return snapshot();}}
    public JSONObject deleteGroup(String id,long revision)throws Exception{synchronized(settings){settings.deleteModelGroup(id,revision);return snapshot();}}
    public JSONObject selectPurpose(String purpose,ModelTargetRef target)throws Exception{synchronized(settings){settings.selectModelPurpose(purpose,target);return snapshot();}}
    public ModelConfig captureTarget(ModelTargetRef target,String purpose,String audience,ModelConfig capturedGlobal)throws Exception{return settings.captureModelTarget(target,purpose,audience,capturedGlobal);}
    public ModelConfig captureConversationGroup(String groupId,long groupRevision)throws Exception{return settings.captureConversationGroup(groupId,groupRevision);}
    /** Inputs are already captured native policy references. Never reads a later global default. */
    public ModelConfig capturePurpose(ModelTargetRef member,java.util.Map<String,ModelTargetRef> task,java.util.Map<String,ModelTargetRef> profile,String purpose,String audience,ModelConfig capturedGlobal)throws Exception{
        ModelTargetRef chosen=member!=null?member:task!=null&&task.containsKey(purpose)?task.get(purpose):profile==null?null:profile.get(purpose);
        return chosen==null?capturedGlobal.withRoutePlan(null):captureTarget(chosen,purpose,audience,capturedGlobal);
    }
    /** Local metadata only. Catalog availability and live identity remain independent of favorites. */
    public JSONObject modelPickerSnapshot() throws Exception {
        synchronized(settings){
            modelPreferences.refresh();
            JSONObject connections=settings.apiConnectionsSnapshot();JSONArray profiles=connections.getJSONArray("connections");
            Map<ModelSelectionPreferences.Key,JSONObject> rows=new java.util.LinkedHashMap<>();
            String selectedConnection="",selectedModel="";
            try{ModelConfig active=settings.get();selectedConnection=active.usesAccount()?"account:"+active.accountModel.accountId:active.capabilityBinding==null?"":active.capabilityBinding.connectionId();selectedModel=active.model;}catch(Exception unavailable){/* An invalid selection is shown, never replaced. */}
            for(int i=0;i<profiles.length();i++){
                JSONObject profile=profiles.getJSONObject(i);ModelBinding binding=ModelBinding.fromJson(profile.getJSONObject("binding"));catalog.bind(binding);
                java.util.LinkedHashSet<String> models=new java.util.LinkedHashSet<>();if(!profile.getString("model").isEmpty())models.add(profile.getString("model"));
                if(!ModelConfig.BEDROCK_PROVIDER.equals(profile.getString("provider")))for(ModelCatalog.View view:catalog.list(binding,MAX_CATALOG_AGE_MILLIS))models.add(view.key().modelId());
                for(ModelSelectionPreferences.Entry preference:modelPreferences.entries())if(preference.key().connectionId().equals(binding.connectionId()))models.add(preference.key().modelId());
                for(String model:models){
                    ModelCatalog.View view=catalog.get(new ModelCatalog.ModelKey(binding,model),MAX_CATALOG_AGE_MILLIS);
                    String reason=!profile.getBoolean("enabled")?"connection_disabled":!model.equals(profile.getString("model"))&&(!view.listed().known||!Boolean.TRUE.equals(view.listed().value))?"catalog_model_unavailable":"";
                    if(reason.isEmpty()&&ModelConfig.BEDROCK_PROVIDER.equals(profile.getString("provider"))){
                        if(!profile.optBoolean("hasAwsSession"))reason="credential_not_configured";
                        else if("expired".equals(profile.optString("credentialStatus")))reason="credential_expired";
                    }
                    if(reason.isEmpty()&&view.endpointSupport()!=null&&!view.endpointSupport().supports(binding.protocol()))reason="catalog_protocol_mismatch";
                    try{ModelTargetRef.exact(binding.connectionId(),model);}catch(IllegalArgumentException invalid){reason="model_reference_not_supported";}
                    ModelSelectionPreferences.Key key=new ModelSelectionPreferences.Key(binding.connectionId(),model);ModelSelectionPreferences.Entry pref=modelPreferences.get(key);
                    JSONObject row=new JSONObject().put("connectionId",binding.connectionId()).put("connectionName",profile.getString("name")).put("connectionRevision",profile.getLong("revision"))
                            .put("binding",binding.toJson()).put("modelId",model).put("provider",profile.getString("provider")).put("available",reason.isEmpty()).put("unavailableReason",reason)
                            .put("favorite",pref.favorite()).put("lastUsedAt",pref.lastUsedAt()).put("selected",binding.connectionId().equals(selectedConnection)&&model.equals(selectedModel))
                            .put("catalogFresh",view.catalogFresh()).put("facts",view.facts().toJson()).put("toolCalls",view.facts().toolCalls().toJson()).put("imageInput",view.facts().imageInput().toJson())
                            .put("endpointSupport",view.endpointSupport()==null?JSONObject.NULL:view.endpointSupport().toJson());
                    rows.put(key,row);
                }
            }
            for(ModelSelectionPreferences.Entry pref:modelPreferences.entries())if(!pref.key().connectionId().startsWith("account:")&&!rows.containsKey(pref.key()))
                rows.put(pref.key(),new JSONObject().put("connectionId",pref.key().connectionId()).put("modelId",pref.key().modelId()).put("connectionName","原连接不可用")
                        .put("available",false).put("unavailableReason","connection_unavailable").put("favorite",pref.favorite()).put("lastUsedAt",pref.lastUsedAt()));
            JSONArray ordered=new JSONArray();for(ModelSelectionPreferences.Key key:modelPreferences.sort(new java.util.ArrayList<>(rows.keySet())))ordered.put(rows.get(key));
            return new JSONObject().put("format","pocketworld.model-picker.v1").put("models",ordered).put("selectedConnectionId",selectedConnection).put("selectedModel",selectedModel)
                    .put("preferencesAvailable",modelPreferences.readable()).put("recentUseSaveFailed",recentUseSaveFailed).put("recentMeaning","successful_persisted_inference_only")
                    .put("selectionScope","future_tasks_only").put("networkRequest",false);
        }
    }
    public JSONObject selectPickerModel(ModelBinding expected,long connectionRevision,String model) throws Exception {
        synchronized(settings){
            JSONObject row=requirePickerRow(expected,connectionRevision,model);
            if(!row.getBoolean("available"))throw new IllegalStateException("此模型的原连接或目录引用已不可用，请在模型页重新核对。");
            ModelConfig metadata=settings.apiMetadataForPricing(expected.connectionId(),model);
            if(metadata==null||!expected.equals(metadata.capabilityBinding))throw new SecurityException("original_model_picker_binding_changed");
            if(metadata.catalogEnforced)ModelRequestPolicy.validateOutput(metadata,catalog.get(new ModelCatalog.ModelKey(expected,model),MAX_CATALOG_AGE_MILLIS).facts());
            settings.selectModelPurpose("MAIN",ModelTargetRef.exact(expected.connectionId(),model));
            return modelPickerSnapshot();
        }
    }
    public JSONObject favoriteModel(ModelBinding expected,long connectionRevision,String model,boolean favorite)throws Exception {
        synchronized(settings){JSONObject row=requirePickerRow(expected,connectionRevision,model);
            if(favorite&&!row.getBoolean("available"))throw new IllegalStateException("先核对原连接与模型目录，再收藏此模型。");
            modelPreferences.favorites(java.util.List.of(new ModelSelectionPreferences.Key(expected.connectionId(),model)),favorite);modelSelection().changed();return modelPickerSnapshot();}
    }
    /** Removing stale metadata cannot activate or edit its missing connection. */
    public JSONObject removeModelFavorite(String connection,String model)throws Exception {modelPreferences.favorites(java.util.List.of(new ModelSelectionPreferences.Key(connection,model)),false);modelSelection().changed();return modelPickerSnapshot();}
    /** Local account presentation only; the account page still requires its current native catalog reference for selection. */
    public JSONObject accountModelPreferences(String accountId)throws Exception{
        modelPreferences.refresh();
        String connection=accountPreferenceId(accountId);JSONArray rows=new JSONArray();
        for(ModelSelectionPreferences.Entry entry:modelPreferences.entries())if(entry.key().connectionId().equals(connection))rows.put(new JSONObject().put("modelId",entry.key().modelId()).put("favorite",entry.favorite()).put("lastUsedAt",entry.lastUsedAt()));
        return new JSONObject().put("models",rows).put("preferencesAvailable",modelPreferences.readable()).put("recentUseSaveFailed",recentUseSaveFailed);
    }
    public JSONObject favoriteAccountModel(String accountId,String model,boolean favorite)throws Exception{
        modelPreferences.favorites(java.util.List.of(new ModelSelectionPreferences.Key(accountPreferenceId(accountId),model)),favorite);modelSelection().changed();return accountModelPreferences(accountId);
    }
    private static String accountPreferenceId(String accountId){if(accountId==null||!accountId.matches("[A-Za-z0-9_.:-]{1,200}"))throw new IllegalArgumentException("invalid_account_model_reference");return "account:"+accountId;}
    public JSONObject previewModelFavorites(ModelBinding expected,long connectionRevision,JSONArray models)throws Exception {
        synchronized(settings){
            if(models==null||models.length()<1||models.length()>ModelSelectionPreferences.MAX_ENTRIES)throw new IllegalArgumentException("每批请选择1–512个模型。");
            requirePickerConnection(expected,connectionRevision);
            Map<String,JSONObject> candidates=new java.util.LinkedHashMap<>();
            JSONArray available=modelPickerSnapshot().getJSONArray("models");
            for(int i=0;i<available.length();i++){JSONObject row=available.getJSONObject(i);if(expected.connectionId().equals(row.getString("connectionId")))candidates.put(row.getString("modelId"),row);}
            JSONArray results=new JSONArray();Set<String> seen=new java.util.HashSet<>();
            for(int i=0;i<models.length();i++){
                String model=ModelBinding.modelId(models.getString(i));JSONObject row=candidates.get(model);
                String status=!seen.add(model)?"duplicate":row==null||!row.getBoolean("available")?"unavailable":row.optBoolean("favorite")?"existing":"new";
                results.put(new JSONObject().put("modelId",model).put("status",status).put("reason",row==null?"catalog_model_unavailable":row.optString("unavailableReason")));
            }
            return new JSONObject().put("results",results).put("binding",expected.toJson()).put("connectionRevision",connectionRevision)
                    .put("credentialCopies",0).put("networkRequests",0).put("selectionChanged",false);
        }
    }
    public JSONObject saveModelFavorites(ModelBinding expected,long connectionRevision,JSONArray models)throws Exception {
        synchronized(settings){JSONObject preview=previewModelFavorites(expected,connectionRevision,models);JSONArray rows=preview.getJSONArray("results");java.util.List<ModelSelectionPreferences.Key> add=new java.util.ArrayList<>();
            for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if("new".equals(row.getString("status")))add.add(new ModelSelectionPreferences.Key(expected.connectionId(),row.getString("modelId")));}
            if(!add.isEmpty()){modelPreferences.favorites(add,true);modelSelection().changed();}
            for(int i=0;i<rows.length();i++)if("new".equals(rows.getJSONObject(i).getString("status")))rows.getJSONObject(i).put("status","added");
            return preview.put("saved",true);
        }
    }
    private JSONObject requirePickerRow(ModelBinding expected,long revision,String model)throws Exception {
        requirePickerConnection(expected,revision);
        JSONArray rows=modelPickerSnapshot().getJSONArray("models");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(expected.connectionId().equals(row.getString("connectionId"))&&model.equals(row.getString("modelId")))return row;}
        throw new IllegalStateException("原目录未包含此模型，请先在模型页核对或明确手填。");
    }
    private void requirePickerConnection(ModelBinding expected,long revision)throws Exception {
        if(expected==null)throw new SecurityException("original_model_picker_binding_required");
        JSONArray profiles=settings.apiConnectionsSnapshot().getJSONArray("connections");JSONObject current=null;
        for(int i=0;i<profiles.length();i++){JSONObject row=profiles.getJSONObject(i);if(expected.connectionId().equals(row.getString("id")))current=row;}
        if(current==null||current.getLong("revision")!=revision||!expected.equals(ModelBinding.fromJson(current.getJSONObject("binding"))))throw new SecurityException("原模型连接已变化，请刷新选择列表；没有切换到其他连接。");
    }
    private void setAttempt(ModelBinding binding,ModelCatalog.Ticket ticket,String status,String code) {
        if(!stillBound(binding))return;
        synchronized(this){if(attempt!=null&&attempt.ticket==ticket)attempt=new Attempt(binding,ticket,attempt.started,status,code);}
    }
}
