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
    private final SettingsStore settings;
    private final ModelCatalog catalog;
    private final CatalogHttpClient http;
    private final Clock clock;
    private final ConnectionInferenceObservations inferenceObservations;
    private Attempt attempt;
    private record Attempt(ModelBinding binding, ModelCatalog.Ticket ticket, long started, String status, String code) { }

    public ModelConnectionService(SettingsStore settings, Path privateCache, Clock clock) throws IOException {
        this(settings, new ModelCatalog(privateCache, clock), new CatalogHttpClient(clock, 15000), clock);
    }
    public ModelConnectionService(SettingsStore settings, ModelCatalog catalog, CatalogHttpClient http, Clock clock) {
        this.settings=Objects.requireNonNull(settings);this.catalog=Objects.requireNonNull(catalog);
        this.http=Objects.requireNonNull(http);this.clock=Objects.requireNonNull(clock);inferenceObservations=new ConnectionInferenceObservations(clock);
    }

    /** Application-only mounting. Existing AgentRunner/context and Gateway remain the consumers. */
    public void install() {
        dev.pocketengine.runtime.usage.InferenceCapture.installPresentationObserver(new dev.pocketengine.runtime.usage.InferenceCapture.Sink(){
            public void started(dev.pocketengine.runtime.usage.InferenceReceipt receipt){}
            public void finished(dev.pocketengine.runtime.usage.InferenceReceipt receipt)throws Exception{inferenceObservations.committed(receipt);}
        });
        ModelRequestPolicy.installHostSource(this::resolve);
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

    public ModelCatalog.View resolve(ModelConfig selected) throws Exception {
        synchronized(settings){
            if(!settings.matchesApiCatalogRoute(selected))throw new ModelRequestPolicy.Rejected("stale_model_credential","模型凭据与所选修订不匹配，请重新核对连接。");
            if(!selected.catalogEnforced)return null;
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
            if(!current.apiKey.isEmpty()&&values!=null&&values.toString().contains(current.apiKey))throw new IllegalArgumentException("请勿把当前API密钥放入公开价目说明");
            catalog.bind(expected);catalog.updateOwnerPrice(new ModelCatalog.ModelKey(expected,model),ownerRevision,clear?null:ModelPrice.owner(expected,model,values,clock.millis()));return snapshot();}
    }

    /** Snapshot contains public references/facts only, including failures alongside the last real data. */
    public JSONObject snapshot() throws Exception {
        synchronized(settings){
            ModelConfig current=settings.apiConfigForCatalog();ModelBinding binding=current.catalogEnforced?current.capabilityBinding:null;
            JSONObject out=new JSONObject().put("api",current.publicJson()).put("activeRoute",settings.get().usesAccount()?"account":"api_key")
                    .put("catalogEnabled",binding!=null).put("connections",settings.apiConnectionsSnapshot()).put("maxCatalogAgeMillis",MAX_CATALOG_AGE_MILLIS)
                    .put("modelsEndpoint",binding==null?defaultEndpoint(current):binding.modelsEndpoint().toASCIIString())
                    .put("catalogFormat",binding==null?ModelCatalogRoutes.defaultFormat(current.provider).name():binding.format().name());
            JSONArray models=new JSONArray();
            if(binding!=null){
                catalog.bind(binding);out.put("binding",binding.toJson());
                boolean selected=false;
                for(ModelCatalog.View view:catalog.list(binding,MAX_CATALOG_AGE_MILLIS)){models.put(view.toJson());selected|=view.key().modelId().equals(current.model);}
                if(!selected&&!current.model.isEmpty())models.put(catalog.get(new ModelCatalog.ModelKey(binding,current.model),MAX_CATALOG_AGE_MILLIS).toJson());
                out.put("selected",catalog.get(new ModelCatalog.ModelKey(binding,current.model),MAX_CATALOG_AGE_MILLIS).toJson());
            }
            JSONObject connections=out.getJSONObject("connections");JSONArray profiles=connections.getJSONArray("connections");
            for(int i=0;i<profiles.length();i++){JSONObject profile=profiles.getJSONObject(i);profile.put("inferenceObservation",inferenceObservations.snapshot(ModelBinding.fromJson(profile.getJSONObject("binding")),profile.getString("model")));}
            out.put("inferenceObservation",inferenceObservations.snapshot(current.capabilityBinding,current.model));
            out.put("models",models).put("reasoning",current.reasoning.toJson()).put("imageTokenReserve",current.imageTokenReserve);
            JSONObject pricing=new JSONObject().put("known",false).put("quote",JSONObject.NULL).put("reason","price_not_observed");
            if(current.capabilityBinding!=null&&!current.model.isEmpty()&&settings.matchesApiCatalogRoute(current)){
                catalog.bind(current.capabilityBinding);var key=new ModelCatalog.ModelKey(current.capabilityBinding,current.model);ModelPrice observed=catalog.priceObservation(key),active=catalog.price(key,MAX_CATALOG_AGE_MILLIS);
                pricing.put("binding",current.capabilityBinding.toJson()).put("model",current.model).put("ownerRevision",catalog.get(key,MAX_CATALOG_AGE_MILLIS).ownerRevision())
                    .put("known",active!=null).put("quote",observed==null?JSONObject.NULL:observed.toJson()).put("reason",active!=null?"quote_available_not_a_bill":observed!=null?"quote_not_current":"price_not_observed");
            }out.put("pricing",pricing);
            Attempt observed; synchronized(this){observed=attempt;}
            if(observed!=null&&Objects.equals(observed.binding,binding))out.put("refreshAttempt",new JSONObject().put("startedAtMillis",observed.started).put("status",observed.status).put("code",observed.code));
            else out.put("refreshAttempt",JSONObject.NULL);
            return out;
        }
    }

    private static String defaultEndpoint(ModelConfig config) {
        try{return ModelCatalogRoutes.modelsEndpoint(config).toASCIIString();}catch(Exception unconfigured){return "";}
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

    public JSONObject select(ModelBinding expected,String model,int output,ReasoningOptions reasoning) throws Exception {
        return select(expected,model,output,reasoning,0);
    }
    public JSONObject select(ModelBinding expected,String model,int output,ReasoningOptions reasoning,int imageTokenReserve) throws Exception {
        synchronized(settings){
            requireBinding(expected);ModelConfig current=settings.apiConfigForCatalog();
            ModelConfig selection=new ModelConfig(current.baseUrl,current.apiKey,model,current.sendScreenshots,current.maxSteps,current.requestTimeoutSeconds,current.provider,output)
                    .withCleartextConsent(current.cleartextConsent).withNativeCatalog(expected,reasoning,imageTokenReserve);
            ModelCatalog.View view=resolve(selection);ModelRequestPolicy.validateOutput(selection,view.facts());
            settings.selectCatalogModel(expected,model,output,reasoning,imageTokenReserve);
            return snapshot();
        }
    }

    private void requireBinding(ModelBinding expected) throws Exception {
        if(expected==null||!expected.equals(settings.apiCatalogBinding())||!settings.matchesApiCatalogRoute(settings.apiConfigForCatalog()))throw new ModelRequestPolicy.Rejected("stale_model_binding","模型目录、连接或密钥修订已变化，请刷新；没有使用旧能力或切换模型。");
    }
    private boolean stillBound(ModelBinding binding) {try{return binding.equals(settings.apiCatalogBinding())&&settings.matchesApiCatalogRoute(settings.apiConfigForCatalog());}catch(Exception unavailable){return false;}}
    private boolean stillBound(ModelBinding binding,long selectionRevision){try{return stillBound(binding)&&settings.apiSelectionRevision()==selectionRevision;}catch(Exception unavailable){return false;}}
    /** Existing owner model page, no feature/model tool and no automatic network request. */
    public JSONObject connections()throws Exception{synchronized(settings){settings.apiConnections();return snapshot();}}
    public JSONObject saveConnection(String id,long revision,String name,JSONObject input)throws Exception{synchronized(settings){settings.saveApiConnection(id,revision,name,input);return snapshot();}}
    public JSONObject selectConnection(String id,long revision)throws Exception{synchronized(settings){settings.selectApiConnection(id,revision);return snapshot();}}
    public JSONObject enableConnection(String id,long revision,boolean enabled)throws Exception{synchronized(settings){settings.setApiConnectionEnabled(id,revision,enabled);return snapshot();}}
    private void setAttempt(ModelBinding binding,ModelCatalog.Ticket ticket,String status,String code) {
        if(!stillBound(binding))return;
        synchronized(this){if(attempt!=null&&attempt.ticket==ticket)attempt=new Attempt(binding,ticket,attempt.started,status,code);}
    }
}
