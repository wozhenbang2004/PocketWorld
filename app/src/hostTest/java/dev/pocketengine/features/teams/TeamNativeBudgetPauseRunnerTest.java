package dev.pocketengine.features.teams;

import static org.junit.Assert.*;
import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.*;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.runtime.context.ContextBudgetPolicy;
import dev.pocketengine.runtime.context.ContextWindow;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.testutil.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/** Real Runner/SQL classification and zero HTTP; it does not mint a native team budget. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class TeamNativeBudgetPauseRunnerTest {
    @Test public void exactPreTargetPauseIsAwaitingUserWithoutDispatchOrQuotaReset()throws Exception {run(true);}
    @Test public void ordinaryTargetFailureIsNotMisreportedAsARefillableBudgetPause()throws Exception {run(false);}

    private void run(boolean budgetPause)throws Exception {
        Application app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");
        TaskStore tasks=new TaskStore(app);AtomicInteger requests=new AtomicInteger(),targets=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{requests.incrementAndGet();exchange.sendResponseHeaders(500,0);exchange.close();});server.start();
        RunControl original=new RunControl();JSONObject started=tasks.startAttempt(tasks.createCapabilityTask("核对本次调用上限与已保留工作","").getString("id"),"capabilities",4);
        String task=started.getString("id"),run=started.getString("runId");int epoch=started.getInt("coordinatorEpoch");original.bindHostRun(task,run,epoch);
        RunJournal saved=tasks.journal(task,run,epoch,original);
        RunJournal measured=new RunJournal(){
            public JSONObject restore()throws Exception{return saved.restore();}
            public void checkpoint(JSONArray history,int steps,JSONObject observation)throws Exception{saved.checkpoint(history,steps,observation);}
            public String beginOperation(String tool,JSONObject arguments)throws Exception{return saved.beginOperation(tool,arguments);}
            public void finishOperation(String operation,JSONObject receipt)throws Exception{saved.finishOperation(operation,receipt);}
            public InferenceCapture.Target inferenceTarget(InferenceReceipt.Purpose purpose,String member)throws Exception {
                assertTrue(original.matchesHostRun(task,run,epoch));targets.incrementAndGet();
                if(budgetPause)throw new RunJournal.NativeBudgetPaused(task,run,epoch,task,run,purpose.name(),97,97);
                throw new IOException("controlled original target failure, not budget exhaustion");
            }
        };
        CapabilityPort capabilities=new CapabilityPort(){
            public JSONArray tools(){return new JSONArray();}public boolean supports(String tool){return false;}
            public boolean sideEffecting(String tool){return false;}
            public void validate(String tool,JSONObject arguments){throw new AssertionError("no tool is offered");}
            public JSONObject execute(String tool,JSONObject arguments,RunControl caller){throw new AssertionError("zero dispatch");}
        };
        ModelConfig selected=new ModelConfig("http://127.0.0.1:"+server.getAddress().getPort()+"/v1","synthetic-not-an-account","budget-fixture",false,4,5,"openai_chat",256);
        ContextBudgetPolicy.Source source=c->ContextBudgetPolicy.RouteBudget.forRoute(c,new ContextWindow.Budget(128000,1000,1000,2L*1024*1024,64,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,System.currentTimeMillis());
        try {
            RunOutcome result=new AgentRunner(null,capabilities,source).run(task,started.getString("goal"),selected,original,event->{},measured);
            tasks.endAttempt(task,run,result.status,result.summary,result.evidence);
            assertEquals(1,targets.get());assertEquals(0,requests.get());assertFalse(tasks.hasUnresolved(task));
            assertEquals(budgetPause?"awaiting_user":"failed",result.status);assertEquals(result.status,tasks.get(task,false).getString("status"));
            if(budgetPause) {
                JSONObject evidence=result.evidence.getJSONObject("nativeBudget");assertEquals(97,evidence.getInt("modelRequestsReservedOrUsed"));assertEquals(97,evidence.getInt("modelRequestLimit"));
                assertFalse(evidence.getBoolean("thisRequestDispatched"));assertTrue(evidence.getBoolean("priorUsageRetained"));
                assertTrue(result.summary.contains("已完成内容保留"));assertFalse(result.summary.contains("NativeBudgetPaused"));assertFalse(result.summary.contains("GPT"));
            } else assertFalse(result.evidence.has("nativeBudget"));
            assertEquals("no native operation was begun or refunded",0,tasks.get(task,false).getJSONArray("operations").length());
        } finally {
            server.stop(0);var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((SQLiteDatabase)field.get(tasks)).close();
        }
    }
}
