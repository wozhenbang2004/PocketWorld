package dev.pocketengine.runtime.models;

import android.app.Application;
import android.content.Context;
import com.sun.net.httpserver.HttpServer;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.store.SettingsStore;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static dev.pocketengine.runtime.models.ModelFacts.*;

/** Actual SettingsStore/SharedPreferences + catalog disk + loopback Gateway; no device/visual/vendor claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class ModelConnectionServiceTest {
    private HttpServer server;private ExecutorService httpWorkers;
    private SettingsStore settings;private ModelConnectionService service;private Path cache;
    private final AtomicInteger modelCalls=new AtomicInteger(),inferences=new AtomicInteger();
    private volatile int modelStatus=200;
    private volatile String modelBody="{\"data\":[{\"id\":\"remote-model\"}]}";
    private volatile JSONObject inference;
    private volatile CountDownLatch entered,release;
    private volatile boolean partial;
    @Before public void open()throws Exception{
        Context context=RuntimeEnvironment.getApplication();context.getSharedPreferences("private_settings",0).edit().clear().commit();settings=new SettingsStore(context);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);httpWorkers=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"native-catalog-fixture");t.setDaemon(true);return t;});server.setExecutor(httpWorkers);
        server.createContext("/",exchange->{try{
            String body;int status;String type="application/json";
            if(exchange.getRequestMethod().equals("GET")){
                modelCalls.incrementAndGet();if(entered!=null)entered.countDown();if(release!=null)release.await(3,TimeUnit.SECONDS);
                status=modelStatus;body=modelBody;
                if(partial){if(exchange.getRequestURI().getQuery()==null)body="{\"data\":[{\"id\":\"partial-must-not-appear\"}],\"has_more\":true,\"last_id\":\"partial-must-not-appear\"}";else{status=503;body="{}";}}
            }else{
                inferences.incrementAndGet();inference=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));status=200;
                if(inference.optBoolean("stream")){type="text/event-stream";body="data: {\"id\":\"reply\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"云端分片\"},\"finish_reason\":null}]}\n\ndata: {\"id\":\"reply\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";}
                else body="{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"OK\"}}]}";
            }
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type",type);exchange.sendResponseHeaders(status,bytes.length);try(var output=exchange.getResponseBody()){output.write(bytes);}
        }catch(Exception ignored){/* Deliberate cancellation/disconnect is part of the fixture. */}finally{exchange.close();}});server.start();
        settings.save(new JSONObject().put("baseUrl",base()).put("provider","openai_chat").put("model","manual-model").put("sendScreenshots",false).put("maxOutputTokens",512));
        cache=Files.createTempDirectory("native-model-catalog-").resolve("catalog.json");service=new ModelConnectionService(settings,cache,Clock.systemUTC());service.install();
    }
    @After public void close(){if(release!=null)release.countDown();server.stop(0);httpWorkers.shutdownNow();}
    private String base(){return "http://127.0.0.1:"+server.getAddress().getPort()+"/v1";}
    private ModelBinding enable(ModelBinding.Format format)throws Exception{service.configure(null,format,URI.create(base()+"/models"));return settings.apiCatalogBinding();}
    private JSONObject row(String model)throws Exception{JSONArray rows=service.snapshot().getJSONArray("models");for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getString("modelId").equals(model))return rows.getJSONObject(i);throw new AssertionError("Missing "+model);}
    private void field(ModelBinding binding,String model,Field field,OwnerValue value,boolean clear)throws Exception{service.editField(binding,model,row(model).getLong("ownerRevision"),field,value,clear);}
    private JSONArray messages()throws Exception{return new JSONArray().put(new JSONObject().put("role","user").put("content","实际文字请求"));}
    private JSONObject richModel()throws Exception{return new JSONObject().put("data",new JSONArray().put(new JSONObject().put("id","remote-model").put("context_length",8192).put("top_provider",new JSONObject().put("max_completion_tokens",2048)).put("architecture",new JSONObject().put("input_modalities",new JSONArray().put("text").put("image"))).put("supported_parameters",new JSONArray().put("tools").put("reasoning_effort").put("max_completion_tokens")).put("reasoning",new JSONObject().put("supported_efforts",new JSONArray().put("low").put("high")))));}
    private JSONObject source(String name,String model)throws Exception{return service.saveSource("",0,name,new JSONObject().put("baseUrl",base()).put("provider","openai_chat").put("model",model).put("sendScreenshots",false).put("maxOutputTokens",512)).getJSONObject("savedConnection");}

    @Test public void firstSourceCanPersistBeforeAModelAndOnlyExplicitSelectionCreatesDefault()throws Exception {
        Context context=RuntimeEnvironment.getApplication();context.getSharedPreferences("private_settings",0).edit().clear().commit();settings=new SettingsStore(context);
        service=new ModelConnectionService(settings,cache,Clock.systemUTC());service.install();
        JSONObject saved=source("先保存来源","");String id=saved.getString("id");long revision=saved.getLong("revision");
        assertFalse(saved.getBoolean("selectionReady"));assertEquals("",settings.apiConnectionsSnapshot().getString("selectedConnectionId"));
        assertEquals(0,service.modelPickerSnapshot().getJSONArray("models").length());assertEquals(0,modelCalls.get());assertEquals(0,inferences.get());
        assertThrows(IllegalArgumentException.class,()->settings.selectApiConnection(id,revision));
        SettingsStore reopened=new SettingsStore(context);assertEquals("",reopened.apiConnectionMetadata(id,revision).configuration.model);
        ModelConfig captured=service.captureConnectionModel(id,revision,"manual-only",512,ReasoningOptions.DEFAULT,0);
        assertFalse(captured.catalogEnforced);assertEquals(captured.capabilityBinding,settings.currentForCaptured(captured).capabilityBinding);
        assertEquals("",settings.apiConnectionsSnapshot().getString("selectedConnectionId"));
        service.selectConnectionModel(id,revision,"manual-only",512,ReasoningOptions.DEFAULT,0);
        assertEquals("manual-only",settings.get().model);assertEquals(id,settings.apiConnectionsSnapshot().getString("selectedConnectionId"));
        assertEquals("manual-only",settings.get().purposePlans.get("SUMMARY").primary().model);assertEquals(0,inferences.get());
    }
    @Test public void savedSourceRefreshDoesNotSelectOrEnableStrictAndSurvivesUnrelatedSelection()throws Exception {
        service.connections();String original=settings.apiConnectionsSnapshot().getString("selectedConnectionId");
        JSONObject source=source("独立目录","");String id=source.getString("id");long revision=source.getLong("revision");
        entered=new CountDownLatch(1);release=new CountDownLatch(1);ExecutorService caller=Executors.newSingleThreadExecutor();
        try{
            Future<JSONObject> pending=caller.submit(()->service.refreshConnection(id,revision,new RunControl()));assertTrue(entered.await(2,TimeUnit.SECONDS));
            settings.selectAccountModel(new JSONObject().put("provider","chatgpt_plan").put("accountId","other-account").put("accountVersion",1).put("modelId","account-model"));
            release.countDown();JSONObject result=pending.get(3,TimeUnit.SECONDS);
            assertEquals("remote-model",result.getJSONArray("models").getJSONObject(0).getString("modelId"));assertFalse(result.getBoolean("selectionChanged"));
            assertFalse(result.getJSONObject("connection").getBoolean("catalogEnabled"));assertEquals(original,settings.apiConnectionsSnapshot().getString("selectedConnectionId"));assertNotNull(settings.selectedAccountModel());
            JSONObject picker=service.modelPickerSnapshot();boolean found=false;for(int i=0;i<picker.getJSONArray("models").length();i++){JSONObject model=picker.getJSONArray("models").getJSONObject(i);if(id.equals(model.getString("connectionId"))){assertEquals("remote-model",model.getString("modelId"));assertTrue(model.getBoolean("available"));found=true;}}
            assertTrue(found);assertEquals(0,inferences.get());
        }finally{release.countDown();caller.shutdownNow();}
    }
    @Test public void sourceRevisionRejectsLateRefreshWhileRetainingLastSuccessfulDirectory()throws Exception {
        JSONObject source=source("原目录","");String id=source.getString("id");long revision=source.getLong("revision");
        service.refreshConnection(id,revision,new RunControl());modelBody="{\"data\":[{\"id\":\"stale-result\"}]}";
        entered=new CountDownLatch(1);release=new CountDownLatch(1);ExecutorService caller=Executors.newSingleThreadExecutor();
        try{
            Future<?> pending=caller.submit(()->assertThrows(CatalogException.class,()->service.refreshConnection(id,revision,new RunControl())));assertTrue(entered.await(2,TimeUnit.SECONDS));
            JSONObject changed=settings.saveApiConnection(id,revision,"新名称",new JSONObject());release.countDown();pending.get(3,TimeUnit.SECONDS);
            JSONObject current=service.connectionSnapshot(id,changed.getLong("revision"));assertTrue(current.toString().contains("remote-model"));assertFalse(current.toString().contains("stale-result"));assertTrue(current.isNull("refreshAttempt"));
            assertThrows(IllegalStateException.class,()->service.captureConnectionModel(id,revision,"remote-model",512,ReasoningOptions.DEFAULT,0));
        }finally{release.countDown();caller.shutdownNow();}
    }
    @Test public void independentRefreshFailureKeepsCacheAndManualModelRemainsExecutable()throws Exception {
        JSONObject source=source("失败保留","");String id=source.getString("id");long revision=source.getLong("revision");
        JSONObject first=service.refreshConnection(id,revision,new RunControl());long fetched=first.getJSONArray("models").getJSONObject(0).getLong("catalogFetchedAtMillis");
        modelStatus=429;assertThrows(CatalogException.class,()->service.refreshConnection(id,revision,new RunControl()));
        JSONObject retained=service.connectionSnapshot(id,revision);assertEquals(fetched,retained.getJSONArray("models").getJSONObject(0).getLong("catalogFetchedAtMillis"));assertEquals("failed",retained.getJSONObject("refreshAttempt").getString("status"));
        ModelConfig captured=service.captureConnectionModel(id,revision,"remote-model",512,ReasoningOptions.DEFAULT,0);assertFalse(captured.catalogEnforced);SettingsStore.validate(captured);
        assertThrows(ModelRequestPolicy.Rejected.class,()->service.captureConnectionModel(id,revision,"remote-model",512,new ReasoningOptions(ReasoningOptions.Mode.OPENAI_EFFORT,"high",0),0));
        assertEquals("manual-model",settings.get().model);assertEquals(0,inferences.get());
    }
    @Test public void draftCatalogUsesTemporaryIdentityAndLeavesSavedSourcesAndCacheUntouched()throws Exception {
        String before=settings.apiConnectionsSnapshot().toString();String selected=settings.get().model;
        JSONObject result=service.previewConnectionCatalog("",0,new JSONObject().put("baseUrl",base()).put("provider","openai_chat").put("model",""),new RunControl());
        assertEquals("succeeded",result.getString("status"));assertEquals("remote-model",result.getJSONArray("models").getJSONObject(0).getString("modelId"));assertFalse(result.getBoolean("saved"));assertFalse(result.getBoolean("selectionChanged"));
        assertFalse(result.toString().contains("draft-key-"));assertEquals(before,settings.apiConnectionsSnapshot().toString());assertEquals(selected,settings.get().model);assertFalse(Files.exists(cache));assertEquals(1,modelCalls.get());assertEquals(0,inferences.get());
    }
    @Test public void strictCaptureOnlyStrengthensExactNativeBindingAndCannotSurviveDisableReenable()throws Exception {
        JSONObject source=source("原生能力","");String id=source.getString("id");long revision=source.getLong("revision");
        settings.configureApiCatalog(id,revision,ModelBinding.Format.OPENROUTER,URI.create(base()+"/models"));revision++;
        modelBody=richModel().toString();service.refreshConnection(id,revision,new RunControl());
        assertFalse(service.captureConnectionModel(id,revision,"remote-model",512,ReasoningOptions.DEFAULT,0).supportsImageInput);
        ModelConfig captured=service.captureConnectionModel(id,revision,"remote-model",512,new ReasoningOptions(ReasoningOptions.Mode.OPENAI_EFFORT,"high",0),0);
        assertTrue(captured.catalogEnforced);assertTrue(captured.supportsImageInput);assertFalse(settings.apiConnectionMetadata(id,revision).configuration.catalogEnforced);assertTrue(settings.matchesApiCatalogRoute(captured));assertSame(captured,settings.currentForCaptured(captured));
        long reviewedRevision=revision;
        assertThrows(IllegalArgumentException.class,()->service.selectConnectionModel(id,reviewedRevision,"remote-model",512,new ReasoningOptions(ReasoningOptions.Mode.OPENAI_EFFORT,"high",0),0));
        assertFalse(settings.apiConnectionMetadata(id,revision).configuration.catalogEnforced);assertEquals("manual-model",settings.get().model);
        JSONObject disabled=settings.setApiConnectionEnabled(id,revision,false);settings.setApiConnectionEnabled(id,disabled.getLong("revision"),true);
        assertFalse(settings.matchesApiCatalogRoute(captured));assertThrows(SecurityException.class,()->settings.currentForCaptured(captured));assertEquals(0,inferences.get());
    }
    @Test public void modelSpecificCompatibilityPreventsGlobalSwitchButAllowsConversationCapture()throws Exception {
        service.connections();JSONObject first=settings.apiConnectionsSnapshot().getJSONArray("connections").getJSONObject(0);String id=first.getString("id");
        JSONObject saved=settings.saveApiConnection(id,first.getLong("revision"),"原模型兼容设置",new JSONObject().put("compatibility",new JSONObject().put("tokenField","MAX_TOKENS")));
        long revision=saved.getLong("revision");ModelConfig running=settings.get();String before=settings.apiConnectionsSnapshot().toString();assertNotNull(running.compatibility);
        ModelConfig conversation=service.captureConnectionModel(id,revision,"another-model",512,ReasoningOptions.DEFAULT,0);
        assertEquals("another-model",conversation.model);assertNull(conversation.compatibility);
        IllegalArgumentException denied=assertThrows(IllegalArgumentException.class,()->service.selectConnectionModel(id,revision,"another-model",512,ReasoningOptions.DEFAULT,0));
        assertTrue(denied.getMessage().contains("此对话"));assertThrows(IllegalArgumentException.class,()->settings.selectConnectionModel(id,revision,conversation));
        assertEquals(before,settings.apiConnectionsSnapshot().toString());assertEquals("manual-model",settings.get().model);assertTrue(settings.matchesApiCatalogRoute(running));assertSame(running,settings.currentForCaptured(running));
        service.selectConnectionModel(id,revision,"manual-model",384,ReasoningOptions.DEFAULT,0);
        assertEquals(384,settings.get().maxOutputTokens);assertEquals(512,settings.currentForCaptured(running).maxOutputTokens);assertTrue(settings.matchesApiCatalogRoute(running));assertEquals(0,inferences.get());
    }
    @Test public void boundConversationModelSurvivesRenameAndDefaultChangeButRejectsANewBinding()throws Exception {
        JSONObject source=source("会话原来源","model-m");String id=source.getString("id");ModelBinding original=ModelBinding.fromJson(source.getJSONObject("binding"));
        JSONObject renamed=settings.saveApiConnection(id,source.getLong("revision"),"来源新名称",new JSONObject());
        assertEquals(original,ModelBinding.fromJson(renamed.getJSONObject("binding")));
        ModelConfig afterRename=service.captureBoundConnectionModel(original,"model-m",512,ReasoningOptions.DEFAULT,0);assertEquals("model-m",afterRename.model);
        service.selectConnectionModel(id,renamed.getLong("revision"),"model-n",384,ReasoningOptions.DEFAULT,0);assertEquals("model-n",settings.get().model);
        ModelConfig afterDefault=service.captureBoundConnectionModel(original,"model-m",512,ReasoningOptions.DEFAULT,0);
        assertEquals("model-m",afterDefault.model);assertEquals(512,afterDefault.maxOutputTokens);assertEquals(original,afterDefault.capabilityBinding);assertTrue(settings.matchesApiCatalogRoute(afterDefault));
        JSONObject current=null;JSONArray rows=settings.apiConnectionsSnapshot().getJSONArray("connections");for(int i=0;i<rows.length();i++)if(id.equals(rows.getJSONObject(i).getString("id")))current=rows.getJSONObject(i);assertNotNull(current);
        assertEquals(id,current.getString("id"));assertEquals(original,ModelBinding.fromJson(current.getJSONObject("binding")));
        JSONObject changed=settings.saveApiConnection(id,current.getLong("revision"),"地址已更新",new JSONObject().put("baseUrl",base()+"/changed"));
        assertNotEquals(original,ModelBinding.fromJson(changed.getJSONObject("binding")));
        assertThrows(SecurityException.class,()->service.captureBoundConnectionModel(original,"model-m",512,ReasoningOptions.DEFAULT,0));assertEquals(0,inferences.get());
    }
    @Test public void legacyCatalogDefaultSelectionCannotRemoveModelSpecificCompatibility()throws Exception {
        service.connections();enable(ModelBinding.Format.OPENAI);JSONObject source=settings.apiConnectionsSnapshot().getJSONArray("connections").getJSONObject(0);
        settings.saveApiConnection(source.getString("id"),source.getLong("revision"),"原生兼容目录",new JSONObject().put("compatibility",new JSONObject().put("tokenField","MAX_TOKENS")));
        ModelConfig running=settings.get();ModelBinding binding=running.capabilityBinding;String before=settings.apiConnectionsSnapshot().toString();
        assertThrows(IllegalArgumentException.class,()->service.select(binding,"another-model",512,ReasoningOptions.DEFAULT));
        assertThrows(IllegalArgumentException.class,()->settings.selectCatalogModel(binding,"another-model",512,ReasoningOptions.DEFAULT,0));
        assertEquals(before,settings.apiConnectionsSnapshot().toString());assertTrue(settings.matchesApiCatalogRoute(running));assertSame(running,settings.currentForCaptured(running));assertEquals(0,inferences.get());
    }

    @Test public void batchFavoritesPreviewExactReferencesWithoutSelectingCopyingSecretsOrCallingModels()throws Exception{
        service.connections();modelBody="{\"data\":[{\"id\":\"remote-model\",\"supported_endpoints\":[\"/chat/completions\"]},{\"id\":\"other-protocol\",\"supported_endpoints\":[\"/messages\"]}]}";
        ModelBinding binding=enable(ModelBinding.Format.OPENAI);service.refresh(binding,new RunControl());JSONObject profile=service.snapshot().getJSONObject("connections").getJSONArray("connections").getJSONObject(0);long revision=profile.getLong("revision");String original=settings.get().model;
        JSONArray requested=new JSONArray().put("remote-model").put("remote-model").put("other-protocol").put("not-listed");JSONObject preview=service.previewModelFavorites(binding,revision,requested);JSONArray rows=preview.getJSONArray("results");
        assertEquals("new",rows.getJSONObject(0).getString("status"));assertEquals("duplicate",rows.getJSONObject(1).getString("status"));assertEquals("unavailable",rows.getJSONObject(2).getString("status"));assertEquals("catalog_protocol_mismatch",rows.getJSONObject(2).getString("reason"));assertEquals("unavailable",rows.getJSONObject(3).getString("status"));
        assertEquals(0,preview.getInt("credentialCopies"));assertEquals(0,preview.getInt("networkRequests"));assertFalse(preview.getBoolean("selectionChanged"));
        assertEquals("added",service.saveModelFavorites(binding,revision,requested).getJSONArray("results").getJSONObject(0).getString("status"));assertEquals("existing",service.saveModelFavorites(binding,revision,requested).getJSONArray("results").getJSONObject(0).getString("status"));assertEquals(original,settings.get().model);assertEquals(0,inferences.get());assertEquals(1,modelCalls.get());
        ModelConnectionService reopened=new ModelConnectionService(settings,cache,Clock.systemUTC());JSONArray restored=reopened.modelPickerSnapshot().getJSONArray("models");assertEquals("remote-model",restored.getJSONObject(0).getString("modelId"));assertTrue(restored.getJSONObject(0).getBoolean("favorite"));assertEquals(0,restored.getJSONObject(0).getLong("lastUsedAt"));
    }
    @Test public void composerSelectionUsesExistingMainPurposeAndRejectsChangedOriginalConnection()throws Exception{
        service.connections();ModelBinding binding=enable(ModelBinding.Format.OPENAI);service.refresh(binding,new RunControl());field(binding,"remote-model",Field.CHAT_OUTPUT_PARAMETER,OwnerValue.known(ChatOutputParameter.MAX_TOKENS),false);
        JSONObject profile=service.snapshot().getJSONObject("connections").getJSONArray("connections").getJSONObject(0);long revision=profile.getLong("revision");ModelConfig captured=settings.get();
        service.selectPickerModel(binding,revision,"remote-model");assertEquals("remote-model",settings.get().model);assertEquals("manual-model",captured.model);assertEquals("manual-model",settings.apiConfigForCatalog().model);assertEquals("remote-model",settings.modelPurposeTarget("MAIN").modelId());assertEquals(0,inferences.get());
        settings.setApiConnectionEnabled(profile.getString("id"),revision,false);assertThrows(SecurityException.class,()->service.selectPickerModel(binding,revision,"remote-model"));assertThrows(SecurityException.class,()->service.saveModelFavorites(binding,revision,new JSONArray().put("remote-model")));assertEquals(0,inferences.get());
    }
    @Test public void explicitOtherProtocolCannotBeSelectedFromCatalogButManualConnectionSurvives()throws Exception{
        service.connections();modelBody="{\"data\":[{\"id\":\"remote-model\",\"supported_endpoints\":[\"/messages\"]}]}";ModelBinding binding=enable(ModelBinding.Format.OPENAI);service.refresh(binding,new RunControl());
        assertEquals("declared_incompatible",row("remote-model").getString("protocolCompatibility"));assertThrows(IllegalStateException.class,()->service.select(binding,"remote-model",512,ReasoningOptions.DEFAULT));assertEquals("manual-model",settings.get().model);assertEquals(1,settings.apiConnectionsSnapshot().getJSONArray("connections").length());assertEquals(0,inferences.get());
    }

    @Test public void nativeConnectRefreshExplainOverrideSelectAndActualStreamSurviveReopen()throws Exception{
        assertNull(settings.get().capabilityBinding);ModelBinding binding=enable(ModelBinding.Format.OPENAI);service.refresh(binding,new RunControl());
        JSONObject initial=row("remote-model");assertFalse(initial.getJSONObject("facts").getJSONObject("TOOL_CALLS").getBoolean("known"));assertTrue(initial.getLong("catalogFetchedAtMillis")>0);
        field(binding,"remote-model",Field.CHAT_OUTPUT_PARAMETER,OwnerValue.known(ChatOutputParameter.MAX_COMPLETION_TOKENS),false);field(binding,"remote-model",Field.TOOL_CALLS,OwnerValue.known(true),false);
        service.select(binding,"remote-model",512,ReasoningOptions.DEFAULT);ModelConfig selected=settings.get();assertEquals(binding,selected.capabilityBinding);assertEquals("remote-model",selected.model);
        RunControl original=new RunControl();original.bindHostRun("task","run",4);List<ModelGateway.StreamEvent> events=new ArrayList<>();assertEquals("云端分片",ModelGateway.completeStreaming(selected,messages(),null,original,events::add).getString("content"));
        assertEquals(512,inference.getInt("max_completion_tokens"));assertFalse(inference.has("max_tokens"));assertEquals(1,inferences.get());assertTrue(original.matchesHostRun("task","run",4));assertEquals(ModelGateway.StreamKind.COMPLETED,events.get(events.size()-1).kind);
        SettingsStore reopened=new SettingsStore(RuntimeEnvironment.getApplication());ModelConnectionService restored=new ModelConnectionService(reopened,cache,Clock.systemUTC());restored.install();
        assertEquals(binding,reopened.get().capabilityBinding);assertEquals("remote-model",reopened.get().model);assertEquals("OWNER_OVERRIDE",restored.snapshot().getJSONObject("selected").getJSONObject("facts").getJSONObject("TOOL_CALLS").getString("source"));assertFalse(restored.snapshot().toString().contains("apiKey"));
    }
    @Test public void refreshFailureAndPartialCatalogPreserveSuccessfulDataAndOwnerFacts()throws Exception{
        ModelBinding binding=enable(ModelBinding.Format.OPENAI);service.refresh(binding,new RunControl());field(binding,"remote-model",Field.TOOL_CALLS,OwnerValue.known(true),false);long fetched=row("remote-model").getLong("catalogFetchedAtMillis");
        modelStatus=429;try{service.refresh(binding,new RunControl());fail();}catch(CatalogException expected){assertEquals(CatalogException.Code.RATE_LIMITED,expected.code);}
        assertEquals(fetched,row("remote-model").getLong("catalogFetchedAtMillis"));assertTrue(row("remote-model").getJSONObject("facts").getJSONObject("TOOL_CALLS").getBoolean("value"));assertEquals("failed",service.snapshot().getJSONObject("refreshAttempt").getString("status"));
        modelStatus=200;partial=true;try{service.refresh(binding,new RunControl());fail();}catch(CatalogException expected){}
        assertEquals(fetched,row("remote-model").getLong("catalogFetchedAtMillis"));assertFalse(service.snapshot().toString().contains("partial-must-not-appear"));assertEquals(binding,settings.apiCatalogBinding());assertEquals(0,inferences.get());
    }
    @Test public void installedRealHostSourceSuppliesTheOriginalRunnerBudgetAndClearReturnsUnknown()throws Exception{
        ModelBinding binding=enable(ModelBinding.Format.OPENAI);service.refresh(binding,new RunControl());
        field(binding,"remote-model",Field.CHAT_OUTPUT_PARAMETER,OwnerValue.known(ChatOutputParameter.MAX_COMPLETION_TOKENS),false);
        field(binding,"remote-model",Field.CONTEXT_WINDOW,OwnerValue.known(new TokenWindow(4096,WindowKind.INPUT_AND_OUTPUT)),false);
        service.select(binding,"remote-model",512,ReasoningOptions.DEFAULT);ModelConfig selected=settings.get();
        var source=dev.pocketengine.runtime.context.ContextBudgetPolicy.currentHostSource();var known=source.resolve(selected);
        assertEquals(4096,known.limits.contextTokens);assertEquals(3584,known.limits.inputLimit());
        assertEquals(dev.pocketengine.runtime.context.ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,known.source);
        var budget=new dev.pocketengine.runtime.context.RunContextBudget(source);var small=budget.request(messages(),null,selected);
        assertTrue(small.diagnostics().getBoolean("modelWindowKnown"));assertEquals("owner_override",small.diagnostics().getJSONObject("metadata").getString("metadataSource"));
        JSONArray longHistory=new JSONArray().put(new JSONObject().put("role","user").put("content","x".repeat(5000)));
        assertThrows(dev.pocketengine.runtime.context.RunContextBudget.Blocked.class,()->budget.request(longHistory,null,selected));
        field(binding,"remote-model",Field.CONTEXT_WINDOW,null,true);assertNull(source.resolve(selected));
        var unknown=budget.request(longHistory,null,selected).diagnostics();assertFalse(unknown.getBoolean("modelWindowKnown"));
        assertTrue(unknown.isNull("modelContextTokens"));assertEquals("local_byte_safety_only",unknown.getString("budgetKind"));assertEquals(0,inferences.get());
    }
    @Test public void explicitUnknownAndClearOverrideAreDifferentAndRefreshCannotOverwriteOwner()throws Exception{
        modelBody=richModel().toString();ModelBinding binding=enable(ModelBinding.Format.OPENROUTER);service.refresh(binding,new RunControl());
        field(binding,"remote-model",Field.IMAGE_INPUT,OwnerValue.known(false),false);service.refresh(binding,new RunControl());assertFalse(row("remote-model").getJSONObject("facts").getJSONObject("IMAGE_INPUT").getBoolean("value"));
        field(binding,"remote-model",Field.IMAGE_INPUT,OwnerValue.unknown(),false);assertFalse(row("remote-model").getJSONObject("facts").getJSONObject("IMAGE_INPUT").getBoolean("known"));
        field(binding,"remote-model",Field.IMAGE_INPUT,null,true);JSONObject image=row("remote-model").getJSONObject("facts").getJSONObject("IMAGE_INPUT");assertTrue(image.getBoolean("value"));assertEquals("PROVIDER_CATALOG",image.getString("source"));
        service.select(binding,"remote-model",512,new ReasoningOptions(ReasoningOptions.Mode.OPENAI_EFFORT,"high",0),1024);assertTrue(settings.get().supportsImageInput);assertEquals(1024,settings.get().imageTokenReserve);
        ModelConfig old=settings.get();long revision=row("remote-model").getLong("ownerRevision");try{service.editField(binding,"remote-model",revision-1,Field.IMAGE_INPUT,OwnerValue.known(false),false);fail();}catch(IllegalStateException expected){}
        assertEquals(old.reasoning,settings.get().reasoning);assertTrue(settings.get().supportsImageInput);
    }
    @Test public void nativeSelectionDoesNotReplaceAnAccountOnCapacityFailureAndCannotCrossOrigin()throws Exception{
        settings.selectAccountModel(new JSONObject().put("provider","chatgpt_plan").put("accountId","account-fixture").put("accountVersion",1).put("modelId","account-model"));
        modelBody=richModel().toString();ModelBinding binding=enable(ModelBinding.Format.OPENROUTER);service.refresh(binding,new RunControl());assertTrue(settings.get().usesAccount());
        try{service.select(binding,"remote-model",4096,ReasoningOptions.DEFAULT);fail();}catch(ModelRequestPolicy.Rejected expected){assertEquals("output_limit_exceeded",expected.code);}assertTrue(settings.get().usesAccount());
        try{service.configure(binding,ModelBinding.Format.OPENAI,URI.create("https://another-origin.example/v1/models"));fail();}catch(IllegalArgumentException expected){}assertEquals(binding,settings.apiCatalogBinding());assertTrue(settings.get().usesAccount());
        service.select(binding,"remote-model",512,ReasoningOptions.DEFAULT);assertFalse(settings.get().usesAccount());assertEquals("remote-model",settings.get().model);assertEquals(0,inferences.get());
    }
    @Test public void changingConnectionDuringRefreshAndReturningCannotReviveOldRevision()throws Exception{
        ModelBinding first=enable(ModelBinding.Format.OPENAI);entered=new CountDownLatch(1);release=new CountDownLatch(1);ExecutorService caller=Executors.newSingleThreadExecutor();
        try{Future<?> pending=caller.submit(()->{try{service.refresh(first,new RunControl());throw new AssertionError("Old refresh applied");}catch(Exception expected){}});assertTrue(entered.await(2,TimeUnit.SECONDS));
            settings.save(new JSONObject().put("baseUrl",base()+"/changed"));ModelBinding changed=settings.apiCatalogBinding();assertTrue(changed.installationRevision()>first.installationRevision());release.countDown();pending.get(3,TimeUnit.SECONDS);
            settings.save(new JSONObject().put("baseUrl",base()));ModelBinding returned=settings.apiCatalogBinding();assertTrue(returned.installationRevision()>changed.installationRevision());assertEquals(first.connectionId(),returned.connectionId());
            int before=modelCalls.get();try{service.refresh(first,new RunControl());fail();}catch(ModelRequestPolicy.Rejected expected){}assertEquals(before,modelCalls.get());assertFalse(service.snapshot().getJSONObject("selected").getBoolean("catalogFresh"));
        }finally{release.countDown();caller.shutdownNow();}
    }
    @Test public void clearingUnreadableCredentialAdvancesRevisionAndCorruptMetadataIsNotReset()throws Exception{
        ModelBinding first=enable(ModelBinding.Format.OPENAI);Context context=RuntimeEnvironment.getApplication();var prefs=context.getSharedPreferences("private_settings",0);prefs.edit().putString("encryptedKey","unreadable-test-fixture").commit();
        try{settings.get();fail();}catch(Exception expected){}settings.save(new JSONObject().put("clearApiKey",true));ModelBinding cleared=settings.apiCatalogBinding();assertTrue(cleared.credentialRevision()>first.credentialRevision());assertEquals(first.installationId(),cleared.installationId());
        try{service.editField(first,"manual-model",0,Field.TOOL_CALLS,OwnerValue.known(true),false);fail();}catch(ModelRequestPolicy.Rejected expected){}
        prefs.edit().putString("apiCatalogBinding","{corrupt-test-fixture").commit();try{settings.get();fail();}catch(Exception expected){}assertEquals("{corrupt-test-fixture",prefs.getString("apiCatalogBinding",""));assertEquals("manual-model",prefs.getString("model",""));
    }
    @Test public void failedCatalogPersistenceDoesNotCommitOwnerFactOrSelectedModel()throws Exception{
        ModelBinding binding=enable(ModelBinding.Format.OPENAI);service.refresh(binding,new RunControl());JSONObject before=row("remote-model");byte[] original=Files.readAllBytes(cache);Path held=cache.resolveSibling("held.json");Files.move(cache,held);Files.createDirectory(cache);Files.writeString(cache.resolve("occupied"),"fixture");
        try{try{field(binding,"remote-model",Field.TOOL_CALLS,OwnerValue.known(true),false);fail();}catch(java.io.IOException expected){}
            assertEquals(before.getLong("ownerRevision"),row("remote-model").getLong("ownerRevision"));assertFalse(row("remote-model").getJSONObject("facts").getJSONObject("TOOL_CALLS").getBoolean("known"));assertArrayEquals(original,Files.readAllBytes(held));assertEquals("manual-model",settings.get().model);
        }finally{Files.delete(cache.resolve("occupied"));Files.delete(cache);Files.move(held,cache);}
    }
}
