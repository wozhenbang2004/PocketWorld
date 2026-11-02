package dev.pocketengine.features.teams;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.*;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.runtime.context.*;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** A genuine pre-dispatch step ceiling, not a reconciliation claim for historical unknown calls. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class TeamStepCeilingReceiptNativeTest {
    @Test public void knownUndispatchedTailIsPairedBeforeCheckpointAndColdRestoreDoesNotInventNotReplayed()throws Exception {
        var app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");TaskStore tasks=new TaskStore(app);AtomicInteger http=new AtomicInteger(),executed=new AtomicInteger();HttpServer peer=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);peer.createContext("/",exchange->{try{int n=http.incrementAndGet();JSONObject answer=new JSONObject().put("role","assistant").put("content","原读取结果保留");if(n==1){JSONArray calls=new JSONArray();for(String id:new String[]{"read-one","read-two"})calls.put(new JSONObject().put("id",id).put("type","function").put("function",new JSONObject().put("name","read_original").put("arguments","{}")));answer.put("tool_calls",calls);}byte[] body=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason",n==1?"tool_calls":"stop").put("message",answer))).toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);}finally{exchange.close();}});peer.start();
        CapabilityPort reads=new CapabilityPort(){public JSONArray tools()throws Exception{return new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","read_original").put("parameters",new JSONObject().put("type","object").put("properties",new JSONObject()).put("additionalProperties",false))));}public boolean supports(String tool){return tool.equals("read_original");}public boolean sideEffecting(String tool){return false;}public JSONObject execute(String tool,JSONObject args,RunControl control)throws Exception{executed.incrementAndGet();return new JSONObject().put("ok",true).put("text","actual original read");}};
        ModelConfig selected=new ModelConfig("http://127.0.0.1:"+peer.getAddress().getPort()+"/v1","synthetic-step-boundary","step-fixture",false,1,15,"openai_chat",512);ContextBudgetPolicy.Source budgets=c->ContextBudgetPolicy.RouteBudget.forRoute(c,new ContextWindow.Budget(128000,512,1024,1048576,128,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,System.currentTimeMillis());RunControl control=new RunControl();
        try{JSONObject first=tasks.startAttempt(tasks.createCapabilityTask("只读一次后保持明确未派发尾部","").getString("id"),"capabilities",1);String task=first.getString("id");control.bindHostRun(task,first.getString("runId"),first.getInt("coordinatorEpoch"));RunJournal journal=tasks.journal(task,first.getString("runId"),first.getInt("coordinatorEpoch"),control);RunOutcome outcome=new AgentRunner(null,reads,budgets).run(task,first.getString("goal"),selected,control,event->{},journal);assertEquals("needs_confirmation",outcome.status);assertEquals(1,executed.get());assertEquals(1,http.get());assertEquals("read-only ceiling created no operation",0,tasks.get(task,false).getJSONArray("operations").length());JSONArray history=journal.restore().getJSONArray("messages");JSONObject receipt=null;for(int i=0;i<history.length();i++){JSONObject row=history.getJSONObject(i);if("read-two".equals(row.optString("tool_call_id")))receipt=new JSONObject(row.getString("content"));}assertNotNull("known pre-dispatch tail must have its actual receipt before cold restore",receipt);assertEquals("step_limit_not_dispatched",receipt.getString("code"));assertEquals("not_dispatched",receipt.getString("effectStatus"));assertFalse(receipt.getBoolean("accepted"));assertFalse(history.toString().contains("not_replayed"));tasks.endAttempt(task,first.getString("runId"),outcome.status,outcome.summary,outcome.evidence);control.cancel();
            JSONObject next=tasks.startAttempt(task,"capabilities",2);control=new RunControl();control.bindHostRun(task,next.getString("runId"),next.getInt("coordinatorEpoch"));journal=tasks.journal(task,next.getString("runId"),next.getInt("coordinatorEpoch"),control);RunOutcome completed=new AgentRunner(null,reads,budgets).run(task,first.getString("goal"),selected.withStepCeiling(2),control,event->{},journal);assertEquals("needs_confirmation",completed.status);assertEquals(1,executed.get());assertEquals(2,http.get());assertFalse(journal.restore().getJSONArray("messages").toString().contains("not_replayed"));assertFalse(tasks.hasUnresolved(task));
        }finally{control.cancel();peer.stop(0);var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((SQLiteDatabase)field.get(tasks)).close();}
    }
}
