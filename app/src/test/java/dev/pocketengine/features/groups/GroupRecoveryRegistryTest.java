package dev.pocketengine.features.groups;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;

/** Production group registry/recovery contract; task/transport/native owner ports are explicit fixtures. */
public class GroupRecoveryRegistryTest {
    static final class Fixture implements AutoCloseable {
        final GroupsModuleTest.Fixture base=new GroupsModuleTest.Fixture();
        final Port port=new Port();int recoveries;boolean reject,loseAck,unknownNoStart,crash;Runnable during;
        GroupHostPort.Authority recoveryAuthority;
        Fixture()throws Exception{base.module.close();reopen();}
        void reopen()throws Exception{base.module=new GroupsModule(base.directory,base.ingress,port,base.delivery,()->{if(!base.ownerAllowed)throw new SecurityException("fixture native owner denied");},base.now::get);}
        void enableOne()throws Exception{JSONObject policy=base.policy("public.answer");policy.getJSONObject("quota").put("maxConcurrent",1);base.module.ownerCommand("policy.set",policy);}
        GroupHostPort.Request request(String id){return base.host.requests.stream().filter(request->request.requestId.equals(id)).findFirst().orElseThrow();}
        void interrupted(String id)throws Exception{GroupHostPort.Request request=request(id);GroupHostPort.TaskReceipt old=base.host.tasks.get(request.taskId);base.host.tasks.put(request.taskId,new GroupHostPort.TaskReceipt(old.taskId,old.runId,old.coordinatorEpoch,old.revision+1,"interrupted","fixture process interrupted",List.of()));base.inspect(id);}
        JSONObject input(String id)throws Exception{JSONObject task=base.inspect(id).getJSONObject("task");return new JSONObject().put("requestId",id).put("runId",task.getString("runId")).put("coordinatorEpoch",task.getInt("coordinatorEpoch")).put("taskRevision",task.getLong("revision"));}
        JSONObject recover(String id)throws Exception{return base.module.ownerCommand("task.resume",input(id));}
        int active()throws Exception{return base.group().getJSONObject("usage").getInt("activeTasks");}
        final class Port implements GroupHostPort {
            public TaskReceipt admit(Request request,Authority authority)throws Exception{return base.host.admit(request,authority);}
            public TaskReceipt inspect(Request request,Authority authority)throws Exception{return base.host.inspect(request,authority);}
            public TaskReceipt cancel(Request request,String run,int epoch,Authority authority)throws Exception{return base.host.cancel(request,run,epoch,authority);}
            public TaskReceipt resume(Request request,Authority authority){throw new SecurityException("fixture ordinary resume does not restart running/terminal work");}
            public TaskReceipt recover(Request request,String run,int epoch,long revision,Authority authority)throws Exception {
                authority.check();recoveries++;recoveryAuthority=authority;if(during!=null)during.run();
                if(crash)throw new AssertionError("fixture process died before attempt admission");
                if(reject)throw new RecoveryNotStarted("fixture rejected before committing attempt");
                if(unknownNoStart)throw new IOException("fixture synchronous admission did not commit; ACK unknown");
                TaskReceipt next=new TaskReceipt(request.taskId,UUID.randomUUID().toString(),epoch+1,revision+1,"running","fixture same task recovered",List.of());base.host.tasks.put(request.taskId,next);
                if(loseAck)throw new IOException("fixture lost ACK after original TaskStore committed");return next;
            }
        }
        @Override public void close(){base.close();}
    }
    @Test public void newRecoveryGenerationNeverRevivesOldAuthorityOrReleasesInFlightClaim()throws Exception {
        try(Fixture f=new Fixture()) {
            f.enableOne();String id=f.base.request("alice");GroupHostPort.Authority old=f.base.host.authorities.get(0);f.interrupted(id);JSONObject selected=f.input(id);
            f.during=()->{try{assertEquals(1,f.active());assertThrows(SecurityException.class,old::check);f.base.inspect(id);assertEquals(1,f.active());assertThrows(IllegalStateException.class,()->f.base.module.ownerCommand("task.resume",selected));}catch(Exception error){throw new AssertionError(error);}};
            JSONObject result=f.base.module.ownerCommand("task.resume",selected);assertEquals("running",result.getJSONObject("task").getString("status"));assertEquals(2,result.getJSONObject("task").getInt("coordinatorEpoch"));
            assertThrows(SecurityException.class,old::check);assertEquals(1,f.recoveries);assertEquals(1,f.base.host.admitted);assertEquals(1,f.active());
        }
    }
    @Test public void recoveryReacquiresConcurrentSlotInsteadOfBypassingAnotherTask()throws Exception {
        try(Fixture f=new Fixture()) {
            f.enableOne();String original=f.base.request("alice");f.interrupted(original);String other=f.base.request("bob");
            assertThrows(IllegalStateException.class,()->f.recover(original));assertEquals(0,f.recoveries);assertEquals(1,f.active());
            f.base.host.finish(f.request(other),List.of());f.base.inspect(other);f.recover(original);assertEquals(1,f.recoveries);assertEquals(2,f.base.host.admitted);assertEquals(1,f.active());
        }
    }
    @Test public void lostRestartAckQueriesNewAttemptAndNeverAdmitsAnotherTask()throws Exception {
        try(Fixture f=new Fixture()) {
            f.enableOne();String id=f.base.request("alice");f.interrupted(id);f.loseAck=true;
            assertThrows(IOException.class,()->f.recover(id));JSONObject actual=f.base.inspect(id).getJSONObject("task");assertEquals(2,actual.getInt("coordinatorEpoch"));assertEquals("running",actual.getString("status"));
            assertThrows(SecurityException.class,()->f.recover(id));assertEquals(1,f.recoveries);assertEquals(1,f.base.host.admitted);assertEquals(1,f.active());
        }
    }
    @Test public void definiteNoDispatchReleasesOnlyClaimAndRevokesBothSavedAuthorities()throws Exception {
        try(Fixture f=new Fixture()) {
            f.enableOne();String id=f.base.request("alice");GroupHostPort.Authority old=f.base.host.authorities.get(0);f.interrupted(id);f.reject=true;
            assertThrows(GroupHostPort.RecoveryNotStarted.class,()->f.recover(id));assertEquals(0,f.active());assertThrows(SecurityException.class,old::check);assertThrows(SecurityException.class,f.recoveryAuthority::check);
            assertEquals(1000,f.base.group().getJSONObject("usage").getLong("reservedTokens"));assertEquals(1,f.base.host.admitted);
        }
    }
    @Test public void unknownNoDispatchAndModuleRestartSettleOnlyAfterOriginalQuery()throws Exception {
        try(Fixture f=new Fixture()) {
            f.enableOne();String id=f.base.request("alice");f.interrupted(id);f.unknownNoStart=true;
            assertThrows(IOException.class,()->f.recover(id));assertEquals(1,f.active());f.base.inspect(id);assertEquals(0,f.active());assertThrows(SecurityException.class,f.recoveryAuthority::check);
            f.unknownNoStart=false;f.crash=true;assertThrows(AssertionError.class,()->f.recover(id));assertEquals(1,f.active());
            f.base.module.close();f.reopen();assertEquals(1,f.active());f.base.inspect(id);assertEquals(0,f.active());assertEquals(1,f.base.host.admitted);
        }
    }
    @Test public void nextDayRecoveryReservesThatDaysQuotaWithoutRefundingOriginalReservation()throws Exception {
        try(Fixture f=new Fixture()) {
            JSONObject policy=f.base.policy("public.answer");policy.getJSONObject("quota").put("maxConcurrent",1).put("dailyTokens",1000).put("maxTokensPerTask",1000);policy.getJSONArray("grants").getJSONObject(0).put("expiresAt",GroupsModuleTest.START+4*86_400_000L);f.base.module.ownerCommand("policy.set",policy);
            String original=f.base.request("alice");f.interrupted(original);f.base.now.addAndGet(86_400_000L);String other=f.base.request("bob");f.base.host.finish(f.request(other),List.of());f.base.inspect(other);
            assertThrows(IllegalStateException.class,()->f.recover(original));assertEquals(0,f.recoveries);f.base.now.addAndGet(86_400_000L);f.recover(original);
            assertEquals(1000,f.base.group().getJSONObject("usage").getLong("reservedTokens"));assertEquals(1,f.base.group().getJSONObject("usage").getInt("dailyTasks"));assertEquals(1000,f.request(original).maxTokens);
        }
    }
    @Test public void staleSelectionAndRevokedPolicyNeverBecomeRecoveryPermission()throws Exception {
        try(Fixture f=new Fixture()) {
            f.enableOne();String id=f.base.request("alice");f.interrupted(id);JSONObject selected=f.input(id).put("taskRevision",1);
            assertThrows(SecurityException.class,()->f.base.module.ownerCommand("task.resume",selected));f.base.enable("public.answer");assertThrows(SecurityException.class,()->f.recover(id));assertEquals(0,f.recoveries);assertEquals(0,f.active());assertTrue(f.base.inspect(id).getBoolean("known"));
            f.base.ownerAllowed=false;assertThrows(SecurityException.class,()->f.base.module.ownerCommand("task.resume",selected));
        }
    }
}
