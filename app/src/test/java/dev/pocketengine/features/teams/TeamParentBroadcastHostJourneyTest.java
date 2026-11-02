package dev.pocketengine.features.teams;

import android.app.Application;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.HostToolSupervisor;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.InvocationTargetException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Reuses the existing actual SQLite/workspace/HTTP/Runner fixture, with its real native grants.
 * No substitute Coordinator, TaskStore, model transport, or mailbox is supplied by this test. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class TeamParentBroadcastHostJourneyTest {
    private static Object value(Object domain,String key)throws Exception{var f=domain.getClass().getDeclaredField(key);f.setAccessible(true);return f.get(domain);}
    private static Object call(Object domain,String name,Class<?>[] types,Object...args)throws Exception{var m=domain.getClass().getDeclaredMethod(name,types);m.setAccessible(true);try{return m.invoke(domain,args);}catch(InvocationTargetException error){Throwable cause=error.getCause();if(cause instanceof Exception)throw(Exception)cause;if(cause instanceof Error)throw(Error)cause;throw error;}}
    private static JSONObject effect(HostToolSupervisor port,RunJournal journal,String tool,JSONObject args,RunControl control)throws Exception{port.validate(tool,args);String operation=journal.beginOperation(tool,args);JSONObject receipt=port.execute(tool,new JSONObject(args.toString()).put("_hostOperationId",operation),control);journal.finishOperation(operation,receipt);return receipt;}
    @Test public void actualParentBroadcastPersistsAndIndependentlyConsumesTheOriginalMemberMessages()throws Exception {
        Class<?> type=Class.forName(TeamParentRunnerJourneyTest.class.getName()+"$Domain");var constructor=type.getDeclaredConstructor(boolean.class,boolean.class);constructor.setAccessible(true);Object domain=constructor.newInstance(false,false);HostToolSupervisor port=null;RunControl original=new RunControl();
        try{
            assertEquals("awaiting_user",((dev.pocketengine.core.RunOutcome)call(domain,"parentRun",new Class<?>[]{})).status);call(domain,"approvePlan",new Class<?>[]{});
            TaskStore tasks=(TaskStore)value(domain,"tasks");FeatureHost features=(FeatureHost)value(domain,"features");TeamCoordinator coordinator=(TeamCoordinator)value(domain,"coordinator");String parent=(String)value(domain,"parent");
            JSONObject attempt=tasks.startAttempt(parent,"capabilities",40);String run=attempt.getString("runId");int epoch=attempt.getInt("coordinatorEpoch");original.bindHostRun(parent,run,epoch);if(attempt.optJSONObject("developmentContext")==null)tasks.bindDevelopmentContext(parent,run,epoch,original,(JSONObject)value(domain,"projectContext"));
            port=new HostToolSupervisor(features,tasks,parent,run,epoch,true);JSONArray tools=port.tools(),names=new JSONArray();for(int i=0;i<tools.length();i++)names.put(tools.getJSONObject(i).getJSONObject("function").getString("name"));tasks.authorizeTools(parent,run,names);RunJournal journal=tasks.journal(parent,run,epoch,original);
            JSONObject started=effect(port,journal,"teams_start",new JSONObject().put("planRef",value(domain,"planRef")).put("planHash",value(domain,"planHash")),original);String team=started.getString("teamRunId");((java.util.concurrent.atomic.AtomicReference<String>)value(domain,"originalTeam")).set(team);
            assertTrue(((CountDownLatch)value(domain,"authorEntered")).await(8,TimeUnit.SECONDS));JSONObject snapshot=coordinator.get(team);JSONArray recipients=new JSONArray();for(int i=0;i<snapshot.getJSONArray("members").length();i++){JSONObject member=snapshot.getJSONArray("members").getJSONObject(i);recipients.put(new JSONObject().put("memberId",member.getString("id")).put("expectedMemberGeneration",member.getInt("generation")).put("identityRevision",member.optInt("identityRevision",1)));}
            JSONObject args=new JSONObject().put("teamRunId",team).put("expectedTeamRevision",snapshot.getLong("revision")).put("expectedGeneration",snapshot.getInt("generation")).put("delivery","queue").put("messageId",UUID.randomUUID().toString()).put("text","PARENT FIFO broadcast：保留原前置冻结版本；本资料不是主人批准。").put("recipients",recipients);
            JSONObject queued=effect(port,journal,"teams_message",args,original);assertEquals(2,queued.getInt("acceptedCount"));assertEquals(0,queued.getInt("consumedCount"));assertFalse(queued.getBoolean("automaticWake"));assertEquals(2,coordinator.get(team).getJSONArray("inbox").length());
            ((CountDownLatch)value(domain,"releaseAuthor")).countDown();call(domain,"awaitState",new Class<?>[]{Predicate.class},(Predicate<JSONObject>)state->"awaiting_review".equals(state.optString("status")));
            JSONObject observed=effect(port,journal,"teams_message",args,original);assertEquals(2,observed.getInt("targetCount"));assertEquals(2,coordinator.get(team).getJSONArray("inbox").length());JSONArray deliveries=observed.getJSONArray("deliveries");int actuallyConsumed=0;for(int i=0;i<deliveries.length();i++)if(deliveries.getJSONObject(i).optBoolean("consumptionVerified"))actuallyConsumed++;assertEquals(1,actuallyConsumed);assertEquals(1,observed.getInt("consumedCount"));
            JSONObject pool=tasks.get(parent,false).getJSONObject("teamReservationPool");JSONArray rows=pool.getJSONArray("reservations");for(String op:new String[]{queued.getString("operationId"),observed.getString("operationId")}){int count=0;for(int i=0;i<rows.length();i++){JSONArray ids=rows.getJSONObject(i).getJSONArray("operationIds");for(int j=0;j<ids.length();j++)if(op.equals(ids.getString(j)))count++;}assertEquals("one original parent action reservation, not per recipient",1,count);}
            original.cancel();assertThrows(Exception.class,()->teamsCall(domain,parent,run,epoch,args,original));assertEquals(2,coordinator.get(team).getJSONArray("inbox").length());
        }finally{original.cancel();if(port!=null)port.close();call(domain,"close",new Class<?>[]{});}
    }
    private static JSONObject teamsCall(Object domain,String task,String run,int epoch,JSONObject args,RunControl control)throws Exception{return ((TeamsModule)value(domain,"teams")).parentCall(task,run,epoch,"teams_message",UUID.randomUUID().toString(),args,control);}
}
