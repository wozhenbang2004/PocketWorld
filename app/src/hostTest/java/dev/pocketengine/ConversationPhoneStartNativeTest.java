package dev.pocketengine;

import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.assistant.AssistantModule;
import dev.pocketengine.features.memory.SourceRef;
import dev.pocketengine.phone.PhoneAccessibilityService;
import dev.pocketengine.runtime.models.ModelConnectionService;
import dev.pocketengine.runtime.models.ConversationModelPreferences;
import dev.pocketengine.runtime.models.ModelCatalog;
import dev.pocketengine.runtime.models.ModelFacts;
import dev.pocketengine.runtime.models.ReasoningOptions;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Real Engine/SQL admission. Accessibility, service execution and startup are explicit fixture boundaries. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class,shadows={ConversationPhoneStartNativeTest.PresentationBoundary.class,ConversationPhoneStartNativeTest.AccessibilityBoundary.class})
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ConversationPhoneStartNativeTest {
    @Implements(EngineApplication.class)
    public static final class PresentationBoundary extends org.robolectric.shadows.ShadowApplication {
        @Implementation protected void onCreate(){}
        @Implementation protected void pushTask(String id){}
    }
    @Implements(PhoneAccessibilityService.class)
    public static final class AccessibilityBoundary {
        static PhoneAccessibilityService connected;
        @Implementation protected static PhoneAccessibilityService get(){return connected;}
    }
    private static final class Queue extends AbstractExecutorService {
        final List<Runnable> pending=new ArrayList<>();boolean closed;
        @Override public void execute(Runnable run){if(closed)throw new java.util.concurrent.RejectedExecutionException();pending.add(run);}
        @Override public void shutdown(){closed=true;}
        @Override public List<Runnable> shutdownNow(){closed=true;var result=new ArrayList<>(pending);pending.clear();return result;}
        @Override public boolean isShutdown(){return closed;}
        @Override public boolean isTerminated(){return closed&&pending.isEmpty();}
        @Override public boolean awaitTermination(long timeout,TimeUnit unit){return isTerminated();}
    }
    private EngineApplication app;private AssistantModule assistant;private Queue queue;
    private static Field field(Class<?> type,String name)throws Exception{Field field=type.getDeclaredField(name);field.setAccessible(true);return field;}
    @Before public void prepare()throws Exception {
        // Shadowed Application bytecode uses Robolectric's wall clock; ordinary store
        // classes use the JVM clock. Align them before testing a real native deadline.
        android.os.SystemClock.setCurrentTimeMillis(System.currentTimeMillis());
        app=(EngineApplication)RuntimeEnvironment.getApplication();app.deleteDatabase(TaskStore.DATABASE_NAME);
        app.getSharedPreferences("private_settings",0).edit().clear().commit();
        app.tasks=new TaskStore(app);dev.pocketengine.testutil.ReadyApplicationFixture.complete(app);
        app.settings=new SettingsStore(app);app.settings.save(new JSONObject().put("baseUrl","https://fixture.invalid/v1").put("provider","openai_chat").put("model","reviewed-model").put("sendScreenshots",false));
        var models=new ModelConnectionService(app.settings,Files.createTempDirectory("phone-review-models").resolve("catalog.json"),Clock.systemUTC());models.connections();models.install();app.modelConnections=models;
        field(EngineApplication.class,"conversationModelPreferences").set(app,new ConversationModelPreferences(Files.createTempDirectory("phone-review-preferences").resolve("preferences.json")));
        app.features=new FeatureHost(event->{},app.tasks,()->false);
        assistant=new AssistantModule(new FeatureContext(app,app.settings,new SecretVault(app),event->{}),null,null,app.tasks);app.features.register(assistant);
        var repo=app.tasks.conversationRepository();repo.transaction(()->{
            repo.saveConversation(new JSONObject().put("conversationId","original-conversation").put("title","原对话").put("updatedAt",1).put("messages",new JSONArray()));
            repo.saveSource(new SourceRef("message","original-user",1,"owner","private").toJson().put("conversationId","original-conversation").put("role","user").put("deleted",false));
            repo.saveMessage("original-conversation",new JSONObject().put("id","original-user").put("revision",1).put("role","user").put("content","请查看当前页面上的信息").put("at",1));return null;
        });
        ((ExecutorService)field(EngineApplication.class,"capabilityWorkers").get(app)).shutdownNow();queue=new Queue();field(EngineApplication.class,"capabilityWorkers").set(app,queue);
        AccessibilityBoundary.connected=new PhoneAccessibilityService();
    }
    @After public void close()throws Exception {
        AccessibilityBoundary.connected=null;queue.shutdownNow();app.features.close();
        ((SQLiteDatabase)field(TaskStore.class,"db").get(app.tasks)).close();
    }
    private EngineApplication.ConversationPhoneStartReview review()throws Exception {
        return app.prepareConversationPhoneStart("original-conversation","查看已确认的目标","main_screen",new JSONObject().put("scopeMinutes",30).put("toolLimit",7).put("modelCallLimit",9));
    }
    private JSONObject start(EngineApplication.ConversationPhoneStartReview review)throws Exception{return app.startConversationPhone(review,true,()->true,id->{});}
    private static JSONObject scope()throws Exception{return new JSONObject().put("scopeMinutes",30).put("toolLimit",7).put("modelCallLimit",9);}
    private JSONObject draftChoice(String model)throws Exception {
        JSONObject source=app.modelConnections.saveSource("",0,"草稿专用来源",new JSONObject().put("baseUrl","https://draft.fixture.invalid/v1")
                .put("provider","openai_chat").put("model",model).put("sendScreenshots",false)).getJSONObject("savedConnection");
        var binding=app.settings.apiConnectionMetadata(source.getString("id"),source.getLong("revision")).configuration.capabilityBinding;
        var catalog=(ModelCatalog)field(ModelConnectionService.class,"catalog").get(app.modelConnections);catalog.bind(binding);
        catalog.updateOwner(new ModelCatalog.ModelKey(binding,model),0,Map.of(
                ModelFacts.Field.REASONING,ModelFacts.OwnerValue.known(true),
                ModelFacts.Field.REASONING_LEVELS,ModelFacts.OwnerValue.known(List.of("high"))),Set.of());
        var config=app.modelConnections.captureConnectionModel(source.getString("id"),source.getLong("revision"),model,1024,
                new ReasoningOptions(ReasoningOptions.Mode.OPENAI_EFFORT,"high",0),0);
        return app.conversationTarget(config);
    }
    private EngineApplication.DraftPhoneStartReview draftReview(String key,String mode)throws Exception {
        return app.prepareDraftPhoneStart(key,"核对当前手机页面",mode,scope());
    }

    @Test public void blankDraftCapturesItsSelectedModelAndReasoningWithoutReplacingTheGlobalModel()throws Exception {
        String key=ConversationModelPreferences.draft("new-phone-input");
        app.conversationModels().save(key,0,draftChoice("draft-model"));
        var background=draftReview(key,"background");assertEquals("draft-model",background.view().getString("modelName"));
        var review=draftReview(key,"main_screen");JSONObject view=review.view();
        assertFalse(view.has("config"));assertFalse(view.has("apiKey"));assertFalse(view.has("baseUrl"));
        view.put("modelName","forged").put("toolLimit",400);
        assertEquals("draft-model",review.view().getString("modelName"));assertEquals(7,review.view().getInt("toolLimit"));
        assertEquals(0,app.tasks.list().length());
        JSONObject receipt=app.startDraftPhone(review,true,()->true,id->{});String id=receipt.getString("taskId");
        assertTrue(receipt.getBoolean("admitted"));assertEquals("draft-model",app.config(id).model);
        assertEquals(new ReasoningOptions(ReasoningOptions.Mode.OPENAI_EFFORT,"high",0),app.config(id).reasoning);
        assertEquals(1024,app.config(id).maxOutputTokens);assertEquals("reviewed-model",app.settings.get().model);
        assertNull(app.tasks.get(id,false).optJSONObject("origin"));
        assertNotNull(shadowOf(app).getNextStartedService());
        String originalScope=app.tasks.get(id,false).getJSONObject("executionScope").toString();
        JSONObject duplicate=app.startDraftPhone(review,true,()->false,ignored->{throw new AssertionError("duplicate allocated");});
        assertTrue(duplicate.getBoolean("duplicate"));assertEquals(id,duplicate.getString("taskId"));
        assertEquals(receipt.getString("runId"),duplicate.getString("runId"));assertEquals(originalScope,app.tasks.get(id,false).getJSONObject("executionScope").toString());
        assertNull(shadowOf(app).getNextStartedService());
    }
    @Test public void blankDraftCanStartWithAConfiguredSourceAndNoGlobalDefault()throws Exception {
        app.getSharedPreferences("private_settings",0).edit().clear().commit();app.settings=new SettingsStore(app);
        app.modelConnections=new ModelConnectionService(app.settings,Files.createTempDirectory("no-default-models").resolve("catalog.json"),Clock.systemUTC());app.modelConnections.install();
        String key=ConversationModelPreferences.draft("first-phone-input");app.conversationModels().save(key,0,draftChoice("only-draft-model"));
        assertEquals("",app.settings.get().model);
        JSONObject receipt=app.startDraftPhone(draftReview(key,"main_screen"),true,()->true,id->{});
        assertEquals("only-draft-model",app.config(receipt.getString("taskId")).model);assertEquals("",app.settings.get().model);
    }
    @Test public void draftPreferenceOrGlobalRevisionChangesInvalidateTheOriginalReviewBeforeCreation()throws Exception {
        String key=ConversationModelPreferences.draft("changing-phone-input");JSONObject choice=draftChoice("draft-model");
        app.conversationModels().save(key,0,choice);var changedPreference=draftReview(key,"main_screen");
        // A preference revision is checked independently, even without a global coordinator mutation.
        app.conversationModels().save(key,1,new JSONObject(choice.toString()).put("outputTokens",2048));
        assertThrows(EngineApplication.DraftPhoneStartRejected.class,()->app.startDraftPhone(changedPreference,true,()->true,id->{}));
        var changedGlobal=draftReview(key,"main_screen");app.settings.save(new JSONObject().put("model","later-default"));
        assertThrows(EngineApplication.DraftPhoneStartRejected.class,()->app.startDraftPhone(changedGlobal,true,()->true,id->{}));
        var closed=draftReview(key,"main_screen");
        assertThrows(EngineApplication.DraftPhoneStartRejected.class,()->app.startDraftPhone(closed,true,()->false,id->{}));
        assertThrows(SecurityException.class,()->app.prepareDraftPhoneStart(ConversationModelPreferences.conversation("original-conversation"),"目标","main_screen",scope()));
        assertEquals(0,app.tasks.list().length());assertNull(shadowOf(app).getNextStartedService());
    }
    @Test public void draftChangeDuringAllocationRetainsTheOriginalTaskAndCannotRetryCreation()throws Exception {
        String key=ConversationModelPreferences.draft("allocated-phone-input");JSONObject choice=draftChoice("draft-model");app.conversationModels().save(key,0,choice);
        var review=draftReview(key,"main_screen");String[] allocated={null};
        assertThrows(SecurityException.class,()->app.startDraftPhone(review,true,()->true,id->{
            allocated[0]=id;try{app.conversationModels().save(key,1,new JSONObject(choice.toString()).put("outputTokens",2048));}catch(Exception error){throw new AssertionError(error);}
        }));
        assertNotNull(allocated[0]);JSONObject original=app.tasks.get(allocated[0],false);assertEquals("awaiting_user",original.getString("status"));
        JSONObject duplicate=app.startDraftPhone(review,true,()->true,id->{throw new AssertionError("reallocated after uncertain admission");});
        assertTrue(duplicate.getBoolean("duplicate"));assertTrue(duplicate.getBoolean("accepted"));assertFalse(duplicate.getBoolean("admitted"));
        assertEquals(allocated[0],duplicate.getString("taskId"));assertEquals(1,app.tasks.list().length());assertEquals(original.toString(),app.tasks.get(allocated[0],false).toString());
        assertNull(shadowOf(app).getNextStartedService());
    }
    @Test public void existingConversationUsesItsPreferenceAndRejectsAChangedPreferenceReview()throws Exception {
        String key=ConversationModelPreferences.conversation("original-conversation");JSONObject choice=draftChoice("conversation-phone-model");
        app.conversationModels().save(key,0,choice);var review=review();assertEquals("conversation-phone-model",review.view().getString("modelName"));
        app.conversationModels().save(key,1,new JSONObject(choice.toString()).put("outputTokens",2048));
        assertThrows(EngineApplication.ConversationPhoneStartRejected.class,()->start(review));
        assertNull(app.tasks.get(review.view().getString("requestId"),false));assertEquals("reviewed-model",app.settings.get().model);
    }

    @Test public void prepareIsReadOnlyAndPublicReviewCannotReplaceItsNativeFields()throws Exception {
        var review=review();JSONObject view=review.view();String id=view.getString("requestId");
        assertNull(app.tasks.get(id,false));assertEquals("reviewed-model",view.getString("modelName"));
        assertFalse(view.has("apiKey"));assertFalse(view.has("config"));assertFalse(view.has("baseUrl"));
        view.put("modelName","forged").put("toolLimit",400).put("conversationId","other");
        assertEquals("reviewed-model",review.view().getString("modelName"));assertEquals(7,review.view().getInt("toolLimit"));
        assertFalse(app.queryConversationPhoneStart(id,"original-conversation").getBoolean("accepted"));
        assertNull(shadowOf(app).getNextStartedService());
    }
    @Test public void changedModelOrClosedOwnerWindowRejectsBeforeCreatingATask()throws Exception {
        var changed=review();app.settings.save(new JSONObject().put("model","later-model"));
        assertThrows(EngineApplication.ConversationPhoneStartRejected.class,()->start(changed));assertNull(app.tasks.get(changed.view().getString("requestId"),false));
        var closed=review();assertThrows(EngineApplication.ConversationPhoneStartRejected.class,()->app.startConversationPhone(closed,true,()->false,id->{}));
        assertNull(app.tasks.get(closed.view().getString("requestId"),false));assertNull(shadowOf(app).getNextStartedService());
    }
    @Test public void exactNativeReviewStartsOnceAndDuplicateDoesNotRenewScopeOrRun()throws Exception {
        var review=review();long[] confirmedAt={-1L};
        JSONObject accepted=app.startConversationPhone(review,true,()->true,allocated->{
            // EngineApplication is instrumented and reads Robolectric's fixed wall
            // clock, whereas this test and TaskStore read the advancing JVM clock.
            // Pin the former at the actual native allocation boundary: aligning only
            // in @Before leaves fixture setup time incorrectly deducted from the scope.
            assertEquals(-1L,confirmedAt[0]);confirmedAt[0]=System.currentTimeMillis();
            assertTrue(android.os.SystemClock.setCurrentTimeMillis(confirmedAt[0]));
        });String id=accepted.getString("taskId");
        assertTrue(accepted.getBoolean("accepted"));assertTrue(accepted.getBoolean("admitted"));assertEquals(review.view().getString("requestId"),id);
        assertEquals("reviewed-model",app.config(id).model);JSONObject scope=app.tasks.get(id,false).getJSONObject("executionScope");
        assertEquals(7,scope.getInt("toolLimit"));assertEquals(9,scope.getInt("modelCallLimit"));assertEquals(confirmedAt[0]+30*60_000L,scope.getLong("deadlineAt"));
        Intent intent=shadowOf(app).getNextStartedService();assertNotNull(intent);assertEquals(PhoneRunService.START,intent.getAction());
        assertTrue(android.os.SystemClock.setCurrentTimeMillis(confirmedAt[0]+60_000L));
        JSONObject duplicate=app.startConversationPhone(review,true,()->false,ignored->{throw new AssertionError("duplicate allocated again");});
        assertTrue(duplicate.getBoolean("duplicate"));assertEquals(id,duplicate.getString("taskId"));assertEquals(accepted.getString("runId"),duplicate.getString("runId"));
        assertEquals(accepted.getInt("epoch"),duplicate.getInt("epoch"));assertEquals(scope.toString(),app.tasks.get(id,false).getJSONObject("executionScope").toString());
        assertNull(shadowOf(app).getNextStartedService());
    }
    @Test public void knownPreflightRejectionCanBeExplicitlyReviewedAgain()throws Exception {
        var first=review();AccessibilityBoundary.connected=null;
        assertThrows(EngineApplication.ConversationPhoneStartRejected.class,()->start(first));
        assertNull(app.tasks.get(first.view().getString("requestId"),false));assertNull(shadowOf(app).getNextStartedService());
        AccessibilityBoundary.connected=new PhoneAccessibilityService();var next=review();
        assertNotEquals(first.view().getString("requestId"),next.view().getString("requestId"));
        assertTrue(start(next).getBoolean("admitted"));assertNull(app.tasks.get(first.view().getString("requestId"),false));
    }
    @Test public void selectionChangeAtAllocationLeavesOnlyTheOriginalQueryableCreation()throws Exception {
        var review=review();String id=review.view().getString("requestId");
        assertThrows(SecurityException.class,()->app.startConversationPhone(review,true,()->true,allocated->{
            assertEquals(id,allocated);try{app.settings.save(new JSONObject().put("model","changed-during-create"));}catch(Exception failure){throw new AssertionError(failure);}
        }));
        JSONObject found=app.queryConversationPhoneStart(id,"original-conversation");assertTrue(found.getBoolean("accepted"));assertFalse(found.getBoolean("admitted"));assertEquals("awaiting_user",found.getString("status"));
        String before=app.tasks.get(id,false).toString();JSONObject duplicate=start(review);assertTrue(duplicate.getBoolean("duplicate"));
        assertFalse(duplicate.getBoolean("admitted"));assertEquals(before,app.tasks.get(id,false).toString());assertNull(shadowOf(app).getNextStartedService());
    }
    @Test public void deletedSourceRejectsAndQueriesNeverAllocateOrStart()throws Exception {
        var review=review();String id=review.view().getString("requestId");
        assistant.handle("deleteMessage",new JSONObject().put("conversationId","original-conversation").put("messageId","original-user").put("revision",1));
        assertThrows(Exception.class,()->start(review));assertNull(app.tasks.get(id,false));
        for(int i=0;i<2;i++)assertFalse(app.queryConversationPhoneStart(id,"original-conversation").getBoolean("accepted"));
        assertNull(shadowOf(app).getNextStartedService());
    }
}
