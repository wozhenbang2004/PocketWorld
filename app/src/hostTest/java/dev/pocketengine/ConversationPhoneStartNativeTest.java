package dev.pocketengine;

import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.assistant.AssistantModule;
import dev.pocketengine.features.memory.SourceRef;
import dev.pocketengine.phone.PhoneAccessibilityService;
import dev.pocketengine.runtime.models.ModelConnectionService;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
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
        app=(EngineApplication)RuntimeEnvironment.getApplication();app.deleteDatabase(TaskStore.DATABASE_NAME);
        app.getSharedPreferences("private_settings",0).edit().clear().commit();
        app.tasks=new TaskStore(app);dev.pocketengine.testutil.ReadyApplicationFixture.complete(app);
        app.settings=new SettingsStore(app);app.settings.save(new JSONObject().put("baseUrl","https://fixture.invalid/v1").put("provider","openai_chat").put("model","reviewed-model").put("sendScreenshots",false));
        var models=new ModelConnectionService(app.settings,Files.createTempDirectory("phone-review-models").resolve("catalog.json"),Clock.systemUTC());models.connections();models.install();
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
        var review=review();long confirmedAt=System.currentTimeMillis();JSONObject accepted=start(review);String id=accepted.getString("taskId");
        assertTrue(accepted.getBoolean("accepted"));assertTrue(accepted.getBoolean("admitted"));assertEquals(review.view().getString("requestId"),id);
        assertEquals("reviewed-model",app.config(id).model);JSONObject scope=app.tasks.get(id,false).getJSONObject("executionScope");
        assertEquals(7,scope.getInt("toolLimit"));assertEquals(9,scope.getInt("modelCallLimit"));assertTrue(scope.getLong("deadlineAt")>=confirmedAt+30*60_000L);
        Intent intent=shadowOf(app).getNextStartedService();assertNotNull(intent);assertEquals(PhoneRunService.START,intent.getAction());
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
