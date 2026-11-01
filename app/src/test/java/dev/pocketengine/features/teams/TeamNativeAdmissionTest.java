package dev.pocketengine.features.teams;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real coordinator monitor/control callbacks. The SQL monitor is an explicit timed-lock fixture, not SQLite. */
public final class TeamNativeAdmissionTest {
    private JSONObject team()throws Exception {return new JSONObject().put("id",TeamWorkFilesTest.id()).put("name","原宿主门禁").put("mode","produce").put("members",new JSONArray().put(new JSONObject().put("id","a").put("name","甲").put("role","原分工A").put("runner","model").put("device","phone").put("writeFiles",new JSONArray().put("src/a.txt"))).put(new JSONObject().put("id","b").put("name","乙").put("role","原分工B").put("runner","model").put("device","phone").put("writeFiles",new JSONArray().put("src/b.txt"))));}
    private ModelConfig model(){return new ModelConfig("https://example.invalid/v1","fixture-secret","default",false,24,10);}
    @Test public void nativeSourceCheckDoesNotWaitForCoordinatorWhileTheSourceMonitorIsHeld()throws Exception {
        TeamWorkFilesTest.Fixture files=new TeamWorkFilesTest.Fixture();CountDownLatch captured=new CountDownLatch(1),finishWorker=new CountDownLatch(1),sourceHeld=new CountDownLatch(1),coordinatorHeld=new CountDownLatch(1);AtomicReference<TeamCoordinator.AssignmentCall> assignment=new AtomicReference<>();AtomicReference<RunControl> child=new AtomicReference<>();AtomicReference<Throwable> failure=new AtomicReference<>();ReentrantLock source=new ReentrantLock();
        TeamCoordinator coordinator=new TeamCoordinator(new File(files.root,"native-lock-team.json"),(model,messages,tools,control)->{throw new AssertionError("no research loop");},event->{});
        coordinator.setAssignmentFactory((call,config,original,progress)->{if(call.memberId.equals("a")){assignment.set(call);child.set(original);original.setAuthorityCheck(call::checkCurrent);original.retainAuthorityCheck(()->{if(!source.tryLock(800,TimeUnit.MILLISECONDS))throw new SecurityException("native source/coordinator lock order inverted");try{}finally{source.unlock();}});captured.countDown();if(!finishWorker.await(4,TimeUnit.SECONDS))throw new AssertionError("fixture did not release original worker");}return new JSONObject().put("assignmentId",call.assignmentId).put("taskId",call.assignmentId).put("status","completed").put("artifacts",new JSONArray());});
        try {
            JSONObject team=team();coordinator.startWithScope(team,"核对原宿主锁序",1,model(),TeamWorkFilesTest.id(),null,null,TeamWriteScope.capture(files.workspace,TeamWorkFilesTest.id(),TeamWorkFilesTest.id(),files.project,team));assertTrue(captured.await(2,TimeUnit.SECONDS));
            Thread sourceThread=new Thread(()->{source.lock();try{sourceHeld.countDown();if(!coordinatorHeld.await(2,TimeUnit.SECONDS))throw new AssertionError("coordinator fixture did not enter");assignment.get().checkCurrent();}catch(Throwable problem){failure.compareAndSet(null,problem);}finally{source.unlock();}},"fixture-original-source");
            Thread ownerThread=new Thread(()->{try{if(!sourceHeld.await(2,TimeUnit.SECONDS))throw new AssertionError("source fixture did not enter");synchronized(coordinator){coordinatorHeld.countDown();child.get().check();}}catch(Throwable problem){failure.compareAndSet(null,problem);}},"fixture-original-coordinator");
            sourceThread.setDaemon(true);ownerThread.setDaemon(true);sourceThread.start();ownerThread.start();sourceThread.join(2500);ownerThread.join(2500);assertFalse(sourceThread.isAlive());assertFalse(ownerThread.isAlive());assertNull(failure.get()==null?"":failure.get().toString(),failure.get());
        }finally {finishWorker.countDown();coordinator.close();}
    }
    @Test public void productionCallsUseTheExistingTeamLimitAcrossConcurrentMembers()throws Exception {
        TeamWorkFilesTest.Fixture files=new TeamWorkFilesTest.Fixture();try(TeamCoordinator coordinator=new TeamCoordinator(new File(files.root,"native-budget-team.json"),(model,messages,tools,control)->{throw new AssertionError("no research loop");},event->{})){
            coordinator.setAssignmentFactory((call,config,original,progress)->{String nativeRun=TeamWorkFilesTest.id();for(int i=0;i<4;i++)call.reserveModelTurn(nativeRun,i);return new JSONObject().put("assignmentId",call.assignmentId).put("taskId",call.assignmentId).put("status","completed").put("artifacts",new JSONArray());});JSONObject team=team();String id=TeamWorkFilesTest.id();coordinator.startWithScope(team,"原团队总调用限制",1,model(),id,null,null,TeamWriteScope.capture(files.workspace,TeamWorkFilesTest.id(),TeamWorkFilesTest.id(),files.project,team));long until=System.nanoTime()+3_000_000_000L;JSONObject actual;
            do{actual=coordinator.get(id);if(!actual.optString("status").matches("queued|running"))break;Thread.sleep(5);}while(System.nanoTime()<until);
            assertEquals(7,actual.getInt("callsUsed"));assertEquals(7,actual.getInt("callLimit"));assertEquals(7,actual.getJSONArray("productionRequests").length());assertTrue(actual.getJSONArray("members").getJSONObject(0).optString("status").equals("failed")||actual.getJSONArray("members").getJSONObject(1).optString("status").equals("failed"));
        }
    }
    @Test public void onlyExplicitOwnerContinuationProducesANewContinuationProofAndInvalidatesTheOldControl()throws Exception {
        TeamWorkFilesTest.Fixture files=new TeamWorkFilesTest.Fixture();AtomicReference<TeamCoordinator.AssignmentCall> first=new AtomicReference<>(),continued=new AtomicReference<>();
        try(TeamCoordinator coordinator=new TeamCoordinator(new File(files.root,"owner-continuation.json"),(model,messages,tools,control)->{throw new AssertionError("no research loop");},event->{})){
            coordinator.setAssignmentFactory((call,config,original,progress)->{String status="completed";if(call.memberId.equals("a")){if(first.compareAndSet(null,call))status="awaiting_user";else continued.set(call);}return new JSONObject().put("assignmentId",call.assignmentId).put("taskId",call.assignmentId).put("status",status).put("artifacts",new JSONArray());});JSONObject team=team();String id=TeamWorkFilesTest.id();coordinator.startWithScope(team,"明确继续原分工",1,model(),id,null,null,TeamWriteScope.capture(files.workspace,TeamWorkFilesTest.id(),TeamWorkFilesTest.id(),files.project,team));long until=System.nanoTime()+2_000_000_000L;while(coordinator.get(id).optString("status").matches("queued|running")&&System.nanoTime()<until)Thread.sleep(5);assertNotNull(first.get());assertFalse(first.get().ownerContinuation);assertNull(continued.get());
            coordinator.resume(id,"a",model(),null,null);until=System.nanoTime()+2_000_000_000L;while(coordinator.get(id).optString("status").matches("queued|running")&&System.nanoTime()<until)Thread.sleep(5);assertNotNull(continued.get());assertTrue(continued.get().ownerContinuation);assertEquals(first.get().assignmentId,continued.get().assignmentId);assertThrows(InterruptedException.class,first.get()::checkCurrent);
        }
    }
}
