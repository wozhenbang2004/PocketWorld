package dev.pocketengine.features.teams;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.features.workspace.JsonFiles;
import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual coordinator persistence and project scope. Child receipt metadata is an explicit SQL fixture. */
public final class TeamRunRetentionTest {
    private static ModelConfig model(){return new ModelConfig("https://example.invalid/v1","fixture-secret","default",false,24,10);}
    private static JSONObject team()throws Exception{return new JSONObject().put("id",TeamWorkFilesTest.id()).put("name","原团队记录").put("mode","produce").put("members",new JSONArray().put(new JSONObject().put("id","a").put("name","甲").put("role","甲").put("runner","model").put("device","phone").put("writeFiles",new JSONArray().put("src/a.txt"))).put(new JSONObject().put("id","b").put("name","乙").put("role","乙").put("runner","model").put("device","phone").put("writeFiles",new JSONArray().put("src/b.txt"))));}
    private static TeamCoordinator open(File file,AtomicInteger calls,boolean uncertain)throws Exception{
        TeamCoordinator coordinator=new TeamCoordinator(file,(config,messages,tools,control)->{throw new AssertionError("No prompt synthesis or research execution");},event->{});
        coordinator.setAssignmentFactory((call,config,control,progress)->{calls.incrementAndGet();return new JSONObject().put("assignmentId",call.assignmentId).put("taskId",call.assignmentId).put("status",uncertain&&call.memberId.equals("a")?"blocked_unknown":"completed").put("originalOperationId","fixture-original-receipt").put("artifacts",new JSONArray());});return coordinator;
    }
    private static JSONObject start(TeamCoordinator coordinator,TeamWorkFilesTest.Fixture files,int index)throws Exception{
        JSONObject definition=team();String id=TeamWorkFilesTest.id();coordinator.startWithScope(definition,"第"+index+"次原分工",1,model(),id,null,null,TeamWriteScope.capture(files.workspace,TeamWorkFilesTest.id(),TeamWorkFilesTest.id(),files.project,definition));
        long until=System.nanoTime()+3_000_000_000L;JSONObject run;do{run=coordinator.get(id);if(!run.optString("status").matches("queued|running"))return run;Thread.sleep(5);}while(System.nanoTime()<until);throw new AssertionError("Original coordinator did not settle");
    }
    private static JSONObject state(JSONObject run)throws Exception{return run.getJSONArray("members").getJSONObject(0).getJSONArray("roundStates").getJSONObject(0);}
    @Test public void twentyFirstRunKeepsOriginalReviewScopeAndAssignmentAcrossReopen()throws Exception{
        TeamWorkFilesTest.Fixture files=new TeamWorkFilesTest.Fixture();File catalog=new File(files.root,"retained-runs.json");AtomicInteger calls=new AtomicInteger();JSONObject first;
        try(TeamCoordinator coordinator=open(catalog,calls,false)){
            first=start(coordinator,files,1);assertEquals("awaiting_review",first.getString("status"));for(int i=2;i<=21;i++)start(coordinator,files,i);
            assertTrue(coordinator.list().length()<=20);assertEquals(42,calls.get());JSONObject restored=coordinator.get(first.getString("id"));assertEquals(state(first).getString("assignmentId"),state(restored).getString("assignmentId"));assertEquals(TeamWorkFiles.canonical(first.getJSONObject("productionScope")),TeamWorkFiles.canonical(restored.getJSONObject("productionScope")));assertEquals("awaiting_review",restored.getString("status"));assertEquals(42,calls.get());
            assertTrue(JsonFiles.read(catalog).getJSONArray("runs").length()<=20);
        }
        try(TeamCoordinator reopened=open(catalog,calls,false)){JSONObject original=reopened.get(first.getString("id"));assertEquals(state(first).getString("assignmentId"),state(original).getString("assignmentId"));assertEquals(42,calls.get());}
    }
    @Test public void interruptedUnknownAssignmentIsRetainedAndReadingItDoesNotReplay()throws Exception{
        TeamWorkFilesTest.Fixture files=new TeamWorkFilesTest.Fixture();File catalog=new File(files.root,"unknown-retained-runs.json");AtomicInteger calls=new AtomicInteger();JSONObject unknown;
        try(TeamCoordinator coordinator=open(catalog,calls,true)){unknown=start(coordinator,files,1);assertEquals("paused",unknown.getString("status"));assertEquals("blocked_unknown",state(unknown).getJSONObject("assignmentResult").getString("status"));}
        try(TeamCoordinator coordinator=open(catalog,calls,false)){
            assertEquals("interrupted",coordinator.get(unknown.getString("id")).getString("status"));for(int i=2;i<=21;i++)start(coordinator,files,i);assertEquals(42,calls.get());JSONObject original=coordinator.get(unknown.getString("id"));assertEquals(state(unknown).getString("assignmentId"),state(original).getString("assignmentId"));assertEquals("blocked_unknown",state(original).getJSONObject("assignmentResult").getString("status"));assertEquals("fixture-original-receipt",state(original).getJSONObject("assignmentResult").getString("originalOperationId"));assertEquals(42,calls.get());
        }
        try(TeamCoordinator reopened=open(catalog,calls,false)){assertEquals("blocked_unknown",state(reopened.get(unknown.getString("id"))).getJSONObject("assignmentResult").getString("status"));assertEquals(42,calls.get());}
    }
    @Test public void actualArchiveWriteFailureKeepsAllOriginalHotContextsAndDoesNotDispatchNewWork()throws Exception{
        TeamWorkFilesTest.Fixture files=new TeamWorkFilesTest.Fixture();File catalog=new File(files.root,"archive-write-failure.json");AtomicInteger calls=new AtomicInteger();
        try(TeamCoordinator coordinator=open(catalog,calls,false)){
            JSONObject first=start(coordinator,files,1);for(int i=2;i<=20;i++)start(coordinator,files,i);File blockedDirectory=new File(files.root,catalog.getName()+".contexts");assertTrue(blockedDirectory.createNewFile());
            assertThrows(java.io.IOException.class,()->start(coordinator,files,21));assertEquals(40,calls.get());assertEquals(20,coordinator.list().length());assertEquals(20,JsonFiles.read(catalog).getJSONArray("runs").length());assertEquals(state(first).getString("assignmentId"),state(coordinator.get(first.getString("id"))).getString("assignmentId"));assertEquals(0,coordinator.history().length());
        }
    }
    @Test public void anActiveNativeIntegratorKeepsItsExactTeamContextHot()throws Exception{
        TeamWorkFilesTest.Fixture files=new TeamWorkFilesTest.Fixture();AtomicInteger calls=new AtomicInteger();
        try(TeamCoordinator coordinator=open(new File(files.root,"native-integration-pin.json"),calls,false)){
            JSONObject first=start(coordinator,files,1);String pinned=first.getString("id");coordinator.setAssignmentFactory(new TeamCoordinator.AssignmentFactory(){
                @Override public boolean hasActiveWork(String teamRun){return pinned.equals(teamRun);}
                @Override public JSONObject execute(TeamCoordinator.AssignmentCall call,ModelConfig config,dev.pocketengine.core.RunControl control,java.util.function.Consumer<JSONObject> progress)throws Exception{calls.incrementAndGet();return new JSONObject().put("assignmentId",call.assignmentId).put("taskId",call.assignmentId).put("status","completed").put("artifacts",new JSONArray());}
            });JSONObject second=start(coordinator,files,2);for(int i=3;i<=21;i++)start(coordinator,files,i);
            assertEquals(1,coordinator.history().length());assertEquals(second.getString("id"),coordinator.history().getJSONObject(0).getString("id"));assertFalse(coordinator.history().getJSONObject(0).has("productionScope"));assertEquals(state(first).getString("assignmentId"),state(coordinator.get(pinned)).getString("assignmentId"));assertEquals(42,calls.get());
        }
    }
}
