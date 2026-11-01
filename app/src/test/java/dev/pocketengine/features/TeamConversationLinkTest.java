package dev.pocketengine.features;

import android.app.Application;
import android.content.Context;
import dev.pocketengine.features.assistant.AssistantModule;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class TeamConversationLinkTest {
    private AssistantModule assistant(){Context app=RuntimeEnvironment.getApplication();app.getSharedPreferences("private_settings",0).edit().putString("baseUrl","https://example.invalid/v1").putString("model","fixture-model").commit();return new AssistantModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{}),null,(conversation,goal,messages,config,control)->new JSONObject().put("content","真实存储fixture回复"));}
    private String conversation(AssistantModule assistant)throws Exception{return assistant.handle("send",new JSONObject().put("message","检查团队旅程")).getString("conversationId");}
    private EngineModule teams(AtomicInteger starts){return new EngineModule(){public String id(){return "teams";}public JSONObject snapshot(){return new JSONObject();}public JSONObject handle(String command,JSONObject input)throws Exception{starts.incrementAndGet();return new JSONObject().put("id",input.getString("_operationId")).put("operationId",input.getString("_operationId")).put("status","running");}};}
    private JSONObject start(FeatureHost host,CopyOnWriteArrayList<JSONObject> events,String conversation)throws Exception{String requestId=UUID.randomUUID().toString();host.request(new JSONObject().put("feature","teams").put("command","start").put("requestId",requestId).put("payload",new JSONObject().put("goal","有真实聊天入口的团队任务").put("conversationId",conversation)));long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<until){for(JSONObject event:events)if(event.optString("type").equals("feature_result")&&requestId.equals(event.optString("requestId")))return event;Thread.sleep(5);}throw new AssertionError("missing host reply");}

    @Test public void nativeStartPersistsValidatedConversationAndAppendsRealTaskLink()throws Exception {
        AssistantModule assistant=assistant();String conversation=conversation(assistant);TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());CopyOnWriteArrayList<JSONObject> events=new CopyOnWriteArrayList<>();AtomicInteger starts=new AtomicInteger();String taskId;
        try(FeatureHost host=new FeatureHost(events::add,tasks,()->false)){host.register(assistant);host.register(teams(starts));JSONObject reply=start(host,events,conversation);assertTrue(reply.toString(),reply.getBoolean("ok"));JSONObject result=reply.getJSONObject("result");taskId=result.getString("hostTaskId");assertEquals(conversation,tasks.get(taskId,false).getString("conversationId"));assertEquals(conversation,result.getString("conversationId"));assertEquals(taskId,result.getString("taskId"));assertEquals(1,starts.get());JSONArray messages=assistant.handle("get",new JSONObject().put("conversationId",conversation)).getJSONArray("messages");assertEquals(3,messages.length());assertEquals(taskId,messages.getJSONObject(2).getString("taskId"));assertEquals("assistant",messages.getJSONObject(2).getString("role"));}
        AssistantModule restored=assistant();assertEquals(taskId,restored.handle("get",new JSONObject().put("conversationId",conversation)).getJSONArray("messages").getJSONObject(2).getString("taskId"));TaskStore reopened=new TaskStore(RuntimeEnvironment.getApplication());assertEquals(conversation,reopened.get(taskId,false).getString("conversationId"));
    }

    @Test public void nonexistentConversationCannotStartOrCreateAnExternalTask()throws Exception {
        TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());CopyOnWriteArrayList<JSONObject> events=new CopyOnWriteArrayList<>();AtomicInteger starts=new AtomicInteger();try(FeatureHost host=new FeatureHost(events::add,tasks,()->false)){host.register(assistant());host.register(teams(starts));JSONObject reply=start(host,events,UUID.randomUUID().toString());assertFalse(reply.toString(),reply.getBoolean("ok"));assertEquals(0,starts.get());assertEquals(0,tasks.list().length());}
    }

    @Test public void aModuleNamedAssistantCannotForgeConversationValidation()throws Exception {
        TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());CopyOnWriteArrayList<JSONObject> events=new CopyOnWriteArrayList<>();AtomicInteger starts=new AtomicInteger();try(FeatureHost host=new FeatureHost(events::add,tasks,()->false)){host.register(new EngineModule(){public String id(){return "assistant";}public JSONObject snapshot(){return new JSONObject();}public JSONObject handle(String command,JSONObject input){return input;}});host.register(teams(starts));assertFalse(start(host,events,"forged-conversation").getBoolean("ok"));assertEquals(0,starts.get());assertEquals(0,tasks.list().length());}
    }

    @Test public void chatAppendFailureWarnsSeparatelyWithoutFailingTheStartedTeam()throws Exception {
        AssistantModule assistant=assistant();String conversation=conversation(assistant);assistant.setSourceSynchronizer(()->{throw new IOException("fixture source-sync failure after conversation commit");});TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());CopyOnWriteArrayList<JSONObject> events=new CopyOnWriteArrayList<>();AtomicInteger starts=new AtomicInteger();try(FeatureHost host=new FeatureHost(events::add,tasks,()->false)){host.register(assistant);host.register(teams(starts));JSONObject reply=start(host,events,conversation);assertTrue(reply.toString(),reply.getBoolean("ok"));JSONObject result=reply.getJSONObject("result");String id=result.getString("hostTaskId");assertEquals("running",tasks.get(id,false).getString("status"));assertEquals(1,starts.get());assertEquals("unconfirmed",result.getString("conversationLinkStatus"));assertTrue(events.stream().anyMatch(event->event.optString("code").equals("conversation_task_link_failed")&&event.optString("taskId").equals(id)&&event.optString("conversationId").equals(conversation)));}
    }

    @Test public void relatedExternalInheritsOnlyItsRealParentConversation()throws Exception {
        AssistantModule assistant=assistant();String original=conversation(assistant),forged=conversation(assistant);TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());JSONObject parent=tasks.createCapabilityTask("来自实际聊天",original);parent=tasks.startAttempt(parent.getString("id"),"capabilities",10);String operation=UUID.randomUUID().toString();tasks.beginRelatedExternal(parent.getString("id"),parent.getString("runId"),"teams","start",operation,new JSONObject().put("conversationId",forged).put("_conversationId",forged));assertEquals(original,tasks.get(operation,false).getString("conversationId"));String standalone=UUID.randomUUID().toString();tasks.beginExternal("teams","start",standalone,new JSONObject().put("conversationId",forged).put("_conversationId",forged));assertFalse(tasks.get(standalone,false).has("conversationId"));
    }
}
