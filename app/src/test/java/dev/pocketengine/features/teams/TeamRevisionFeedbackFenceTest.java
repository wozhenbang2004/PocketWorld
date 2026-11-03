package dev.pocketengine.features.teams;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.workspace.JsonFiles;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real Coordinator persistence/control methods with isolated seeded runs and actual filesystem faults. No model calls. */
public final class TeamRevisionFeedbackFenceTest {
    private static String id(){return UUID.randomUUID().toString();}
    private static JSONObject seed(String run,String assignment)throws Exception {
        JSONObject member=new JSONObject().put("id","a").put("name","作者").put("role","原文件作者").put("status","completed").put("round",1).put("assignmentTask",new JSONObject().put("assignmentId",assignment));
        return new JSONObject().put("id",run).put("operationId",run).put("mode","produce").put("status","awaiting_review").put("phase","synthesis").put("round",1).put("rounds",1).put("members",new JSONArray().put(member));
    }
    private static JSONObject input(TeamCoordinator c,String run,String assignment)throws Exception {
        JSONObject current=c.get(run);return new JSONObject().put("runId",run).put("assignmentId",assignment).put("memberId","a").put("revisionRequestId",id()).put("text","保留原范围，仅修订这个成果").put("expectedFrozenRunId",id()).put("expectedArtifactsHash","a".repeat(64)).put("expectedRevision",current.getLong("revision")).put("expectedGeneration",current.getInt("generation")).put("expectedMemberGeneration",current.getJSONArray("members").getJSONObject(0).getInt("generation"));
    }
    private static TeamCoordinator open(File state,java.util.function.Consumer<JSONObject> events)throws Exception{return new TeamCoordinator(state,(model,messages,control)->{throw new AssertionError("must never dispatch");},events);}
    @SuppressWarnings("unchecked") private static Object run(TeamCoordinator c,String id)throws Exception {var field=TeamCoordinator.class.getDeclaredField("runs");field.setAccessible(true);return ((Map<String,Object>)field.get(c)).get(id);}
    private static RunControl control(TeamCoordinator c,String id)throws Exception {Object run=run(c,id);var field=run.getClass().getDeclaredField("control");field.setAccessible(true);RunControl original=new RunControl();field.set(run,original);return original;}
    private static JSONArray pending(TeamCoordinator c,String id)throws Exception {Object run=run(c,id);var method=TeamCoordinator.class.getDeclaredMethod("pendingFeedbackIds",run.getClass(),String.class);method.setAccessible(true);return (JSONArray)method.invoke(null,run,"a");}
    private static void makeRoom(TeamCoordinator c)throws Exception {var method=TeamCoordinator.class.getDeclaredMethod("makeRoom");method.setAccessible(true);try{method.invoke(c);}catch(java.lang.reflect.InvocationTargetException failure){throw (Exception)failure.getCause();}}
    @Test public void failedFirstFenceSaveDoesNotPauseOrCancelAndSameProcessDoesNotReplay()throws Exception {
        File root=Files.createTempDirectory("team-revision-first-save-").toFile(),state=new File(root,"runs.json");String run=id(),assignment=id();JsonFiles.write(state,new JSONObject().put("runs",new JSONArray().put(seed(run,assignment))));
        try(TeamCoordinator c=open(state,event->{})){
            JSONObject request=input(c,run,assignment);RunControl original=control(c,run);File backup=new File(root,"before.json");Files.move(state.toPath(),backup.toPath());assertTrue(state.mkdir());
            assertThrows(Exception.class,()->c.reviewedRevisionFeedback("reviseAssignment",request,null));assertFalse(original.isCancelled());assertEquals(0,c.get(run).getJSONArray("members").getJSONObject(0).getInt("generation"));assertEquals(0,pending(c,run).length());
            assertThrows(IllegalStateException.class,()->c.reviewedRevisionFeedback("reviseAssignment",request,null));assertEquals(1,c.get(run).getJSONArray("inbox").length());assertTrue(state.delete());Files.move(backup.toPath(),state.toPath());
        }
    }
    @Test public void failedFeedbackSaveKeepsThePriorDurableFenceAndDoesNotRestoreCancelledControls()throws Exception {
        File root=Files.createTempDirectory("team-revision-late-save-").toFile(),state=new File(root,"runs.json"),fence=new File(root,"reserved.json");String run=id(),assignment=id();JsonFiles.write(state,new JSONObject().put("runs",new JSONArray().put(seed(run,assignment))));AtomicInteger saved=new AtomicInteger();JSONObject request;
        try(TeamCoordinator c=open(state,event->{if(saved.incrementAndGet()==1)try{Files.move(state.toPath(),fence.toPath());if(!state.mkdir())throw new AssertionError();}catch(Exception failure){throw new AssertionError(failure);}})){
            request=input(c,run,assignment);RunControl original=control(c,run);
            assertThrows(Exception.class,()->c.reviewedRevisionFeedback("reviseAssignment",request,null));assertTrue(original.isCancelled());assertEquals(1,c.get(run).getJSONArray("members").getJSONObject(0).getInt("generation"));assertEquals(1,c.get(run).getJSONArray("inbox").length());
            JSONObject reserved=JsonFiles.read(fence).getJSONArray("runs").getJSONObject(0).getJSONArray("inbox").getJSONObject(0);assertEquals("pending_unknown",reserved.getString("revisionFeedbackState"));assertTrue(reserved.getBoolean("nativeAssignmentInput"));assertTrue(state.delete());Files.move(fence.toPath(),state.toPath());
        }
        try(TeamCoordinator cold=open(state,event->{})){
            JSONObject newer=new JSONObject(request.toString()).put("expectedRevision",99999).put("expectedGeneration",999).put("expectedMemberGeneration",999);
            assertEquals("pending_unknown",cold.revisionFeedback("reviseAssignment",newer).getString("revisionFeedbackState"));assertEquals(0,pending(cold,run).length());
            assertThrows(IllegalStateException.class,()->cold.reviewedRevisionFeedback("reviseAssignment",newer,null));assertThrows(SecurityException.class,()->cold.revisionFeedback("reviseAssignment",new JSONObject(newer.toString()).put("text","changed")));assertEquals(1,cold.get(run).getJSONArray("inbox").length());
        }
    }
    @Test public void retainedFenceSurvivesFullInboxArchiveColdReloadAndCapacityOrMissingFileFailsClosed()throws Exception {
        File root=Files.createTempDirectory("team-revision-retention-").toFile(),state=new File(root,"runs.json"),fence=new File(root,"reserved.json");String run=id(),assignment=id();JsonFiles.write(state,new JSONObject().put("runs",new JSONArray().put(seed(run,assignment))));JSONObject request,first;AtomicInteger saved=new AtomicInteger();
        try(TeamCoordinator c=open(state,event->{if(saved.incrementAndGet()==1)try{Files.move(state.toPath(),fence.toPath());if(!state.mkdir())throw new AssertionError();}catch(Exception failure){throw new AssertionError(failure);}})){
            request=input(c,run,assignment);assertThrows(Exception.class,()->c.reviewedRevisionFeedback("reviseAssignment",request,null));first=JsonFiles.read(fence).getJSONArray("runs").getJSONObject(0);assertEquals("pending_unknown",first.getJSONArray("inbox").getJSONObject(0).getString("revisionFeedbackState"));assertTrue(state.delete());Files.move(fence.toPath(),state.toPath());
        }
        // Start from the real pre-feedback durable fence left by that failed second save, not a fabricated receipt.
        for(int i=1;i<20;i++)first.getJSONArray("inbox").put(new JSONObject().put("id",id()).put("targetMemberId","a").put("text","older").put("status","consumed").put("consumedBy",new JSONArray().put("a")).put("deliveries",new JSONArray()));
        JSONArray catalog=new JSONArray().put(first);for(int i=1;i<20;i++)catalog.put(seed(id(),id()).put("status","completed"));JsonFiles.write(state,new JSONObject().put("runs",catalog));
        File contexts=new File(root,"runs.json.contexts"),filler=new File(contexts,"f".repeat(64)+".json");assertTrue(contexts.mkdir());try(RandomAccessFile file=new RandomAccessFile(filler,"rw")){file.setLength(32L*1024*1024);}
        try(TeamCoordinator c=open(state,event->{})){
            assertNotNull(c.revisionFeedback("reviseAssignment",request));JSONObject fresh=input(c,run,assignment);assertThrows(IllegalStateException.class,()->c.checkRevision(fresh,null));assertEquals(20,c.get(run).getJSONArray("inbox").length());
            assertThrows(IllegalStateException.class,()->makeRoom(c));assertEquals(20,c.list().length());assertTrue(filler.isFile());assertTrue(filler.delete());makeRoom(c);assertEquals(19,c.list().length());assertEquals(1,c.history().length());assertNotNull(c.revisionFeedback("reviseAssignment",request));
        }
        File archived=new File(contexts,TeamWorkFiles.hash(run)+".json"),backup=new File(root,"archive.json");assertEquals(20,JsonFiles.read(archived).getJSONArray("inbox").length());
        try(TeamCoordinator cold=open(state,event->{})){
            byte[] before=Files.readAllBytes(state.toPath());assertNotNull(cold.revisionFeedback("reviseAssignment",request));assertArrayEquals(before,Files.readAllBytes(state.toPath()));assertEquals(19,cold.list().length());
            Files.move(archived.toPath(),backup.toPath());assertThrows(SecurityException.class,()->cold.revisionFeedback("reviseAssignment",request));assertEquals(19,cold.list().length());Files.move(backup.toPath(),archived.toPath());
        }
    }
}
