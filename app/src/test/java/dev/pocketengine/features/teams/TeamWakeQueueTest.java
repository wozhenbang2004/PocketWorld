package dev.pocketengine.features.teams;

import dev.pocketengine.features.workspace.JsonFiles;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static dev.pocketengine.features.teams.TeamWakeQueue.*;

/** Actual state/persistence contracts. Native SQL/Coordinator proofs are deliberately not mocked
 * into executable authority: NativeClaims/View below are data fixtures, never mounted dispatch. */
public final class TeamWakeQueueTest {
    private static final long NOW=1000;
    private static String id(){return UUID.randomUUID().toString();}
    private static final String SOURCE="a".repeat(64),BUDGET="b".repeat(64);
    private static Scope scope(){return new Scope("10000000-0000-0000-0000-000000000001","10000000-0000-0000-0000-000000000002","10000000-0000-0000-0000-000000000003",2,BUDGET);}
    private static Target target(){return new Target("researcher",1,3,4,1,"10000000-0000-0000-0000-000000000004","10000000-0000-0000-0000-000000000005",5,"d".repeat(64));}
    private static NativeClaims counts(int n){return new NativeClaims(n,n==0?Map.of():Map.of(1,n),String.format(Locale.ROOT,"%064x",n));}
    private static OwnerPolicy policy(int each,int total){return new OwnerPolicy(true,Set.of(SourceKind.values()),each,total,NOW+100000);}
    private static JSONObject configured(int each,int total,NativeClaims nativeClaims)throws Exception {
        JSONObject task=new JSONObject().put("nativeInput",new JSONArray().put("never_consumed_here")).put("nativeBudget",new JSONObject().put("used",7)).put("unrelated","kept");
        return configure(task,scope(),0,policy(each,total),NOW,nativeClaims).mergeInto(task);
    }
    private static Message message(Target target,SourceKind kind,boolean command,boolean intervention){return new Message(scope(),target,id(),"c".repeat(64),SOURCE,kind,command,intervention);}
    private static JSONObject enqueue(JSONObject task,Message message,NativeClaims counts)throws Exception{return TeamWakeQueue.enqueue(task,message,task.getJSONObject(KEY).getLong("policyRevision"),NOW,counts).mergeInto(task);}
    private static NativeView view(Target target,NativeClaims c){return new NativeView(scope(),target,NOW,c,true,false,false,false,true,Map.of(SOURCE,SourceState.CURRENT));}
    private static NativeView gate(NativeClaims c,boolean idle,boolean paused,boolean stopped,boolean unknown,boolean budget,SourceState source,long now){return new NativeView(scope(),target(),now,c,idle,paused,stopped,unknown,budget,Map.of(SOURCE,source));}
    private static OriginalAttempt attempt(Target t){return new OriginalAttempt(id(),t.childTaskId(),id(),t.childEpoch()+1);}
    private static String wake(JSONObject task,int index)throws Exception{return task.getJSONObject(KEY).getJSONArray("intents").getJSONObject(index).getString("id");}
    private static String reason(Plan plan)throws Exception{return plan.receipt().getJSONArray("blocked").getJSONObject(0).getString("reason");}
    private static List<String> ids(Plan plan)throws Exception{JSONArray values=plan.intentIds();List<String> result=new ArrayList<>();for(int i=0;i<values.length();i++)result.add(values.get(i).toString());return List.copyOf(result);}

    @Test public void nativeMessageAndIntentReopenIdempotentlyWithoutTouchingInputsOrBudget()throws Exception {
        NativeClaims c=counts(0);JSONObject task=configured(2,4,c);Message message=message(target(),SourceKind.DIRECTED_PEER,false,false);task=enqueue(task,message,c);
        File file=new File(Files.createTempDirectory("original-task-wake-").toFile(),"task.json");JsonFiles.write(file,task);JSONObject reopened=JsonFiles.read(file);
        Change duplicate=TeamWakeQueue.enqueue(reopened,message,1,NOW+10,c);assertTrue(duplicate.receipt().getBoolean("duplicate"));assertTrue(duplicate.receipt().getBoolean("queryOnly"));
        JSONObject next=duplicate.mergeInto(reopened);assertEquals(1,next.getJSONObject(KEY).getJSONArray("intents").length());assertEquals(wake(task,0),wake(next,0));assertEquals("kept",next.getString("unrelated"));
        assertEquals(7,next.getJSONObject("nativeBudget").getInt("used"));assertEquals("never_consumed_here",next.getJSONArray("nativeInput").getString(0));assertFalse(duplicate.receipt().getBoolean("executionAuthorized"));assertFalse(duplicate.receipt().getBoolean("messageConsumptionChanged"));
    }
    @Test public void fourNativePrioritiesAndStableFifoPreventPeerTrafficFromHidingOwnerControl()throws Exception {
        var c=counts(0);JSONObject task=configured(4,8,c);for(int n=0;n<12;n++)task=enqueue(task,message(target(),SourceKind.DIRECTED_PEER,false,false),c);
        task=enqueue(task,message(target(),SourceKind.BACKGROUND,false,false),c);task=enqueue(task,message(target(),SourceKind.AUTHORIZED_CONTROL,false,false),c);
        Plan control=plan(task,view(target(),c));assertEquals(List.of(wake(task,13)),ids(control));assertTrue(control.receipt().getBoolean("isolatedBatch"));
        task=enqueue(task,message(target(),SourceKind.OWNER_FOREGROUND,false,false),c);task=enqueue(task,message(target(),SourceKind.OWNER_FOREGROUND,false,false),c);
        assertEquals(List.of(wake(task,14),wake(task,15)),ids(plan(task,view(target(),c))));
        JSONObject peers=configured(4,8,c);for(int n=0;n<10;n++)peers=enqueue(peers,message(target(),SourceKind.DIRECTED_PEER,false,false),c);
        List<String> expected=new ArrayList<>();for(int i=0;i<MAX_BATCH;i++)expected.add(wake(peers,i));assertEquals(expected,ids(plan(peers,view(target(),c))));
    }
    @Test public void recognizedNativeCommandsAndInterventionAreExclusiveFifoBarriers()throws Exception {
        var c=counts(0);JSONObject task=configured(4,8,c);task=enqueue(task,message(target(),SourceKind.OWNER_FOREGROUND,false,false),c);
        task=enqueue(task,message(target(),SourceKind.OWNER_FOREGROUND,true,false),c);task=enqueue(task,message(target(),SourceKind.OWNER_FOREGROUND,false,false),c);
        assertEquals(List.of(wake(task,0)),ids(plan(task,view(target(),c))));
        JSONObject head=configured(4,8,c);head=enqueue(head,message(target(),SourceKind.OWNER_FOREGROUND,true,false),c);head=enqueue(head,message(target(),SourceKind.OWNER_FOREGROUND,false,false),c);
        Plan command=plan(head,view(target(),c));assertEquals(1,command.intentIds().length());assertTrue(command.receipt().getBoolean("nativeCommand"));assertTrue(command.receipt().getBoolean("isolatedBatch"));
        JSONObject intervention=configured(4,8,c);intervention=enqueue(intervention,message(target(),SourceKind.OWNER_FOREGROUND,false,true),c);intervention=enqueue(intervention,message(target(),SourceKind.OWNER_FOREGROUND,false,false),c);
        assertEquals(1,plan(intervention,view(target(),c)).intentIds().length());assertThrows(SecurityException.class,()->message(target(),SourceKind.DIRECTED_PEER,true,false));assertThrows(SecurityException.class,()->message(target(),SourceKind.BACKGROUND,false,true));
    }
    @Test public void oneOriginalSqlClaimIsRequiredAndMergePreservesOtherNativeWrites()throws Exception {
        var c=counts(0);JSONObject task=enqueue(configured(2,4,c),message(target(),SourceKind.DIRECTED_PEER,false,false),c);Plan selected=plan(task,view(target(),c));OriginalAttempt attempt=attempt(target());JSONObject original=task;
        assertThrows(SecurityException.class,()->recordOriginalClaim(original,selected,view(target(),counts(0)),attempt,c));assertThrows(SecurityException.class,()->recordOriginalClaim(original,selected,view(target(),counts(0)),attempt,counts(2)));
        assertThrows(SecurityException.class,()->recordOriginalClaim(original,selected,gate(c,true,false,false,false,true,SourceState.CURRENT,NOW+100000),attempt,counts(1)));
        assertThrows(SecurityException.class,()->recordOriginalClaim(original,selected,gate(c,true,false,false,false,false,SourceState.CURRENT,NOW),attempt,counts(1)));
        assertThrows(SecurityException.class,()->recordOriginalClaim(original,selected,gate(c,true,false,false,false,true,SourceState.REVOKED,NOW),attempt,counts(1)));
        Change claim=recordOriginalClaim(task,selected,view(target(),counts(0)),attempt,counts(1));JSONObject concurrent=new JSONObject(task.toString());concurrent.getJSONObject("nativeBudget").put("used",8);concurrent.getJSONArray("nativeInput").put("actual-SQL-input");
        JSONObject merged=claim.mergeInto(concurrent);assertEquals(8,merged.getJSONObject("nativeBudget").getInt("used"));assertEquals(2,merged.getJSONArray("nativeInput").length());assertTrue(claim.receipt().getBoolean("nativeDispatchProofRequired"));assertFalse(claim.receipt().getBoolean("executionAuthorized"));
        assertThrows(IllegalStateException.class,()->recordOriginalClaim(merged,selected,view(target(),counts(0)),attempt,counts(1)));assertThrows(SecurityException.class,()->plan(merged,view(target(),c)));
        assertEquals("original_continuation_active_or_unknown",reason(plan(merged,view(target(),counts(1)))));
    }
    @Test public void crashBeforeSchedulingKeepsCandidateButClaimedRestartOnlyQueriesOneOriginalAttempt()throws Exception {
        var c=counts(0);JSONObject task=configured(2,4,c);task=enqueue(task,message(target(),SourceKind.DIRECTED_PEER,false,false),c);task=enqueue(task,message(target(),SourceKind.DIRECTED_PEER,false,false),c);
        File file=new File(Files.createTempDirectory("wake-restart-").toFile(),"original-task.json");JsonFiles.write(file,task);JSONObject reopened=JsonFiles.read(file);assertEquals(2,plan(reopened,view(target(),c)).intentIds().length());
        OriginalAttempt attempt=attempt(target());task=recordOriginalClaim(reopened,plan(reopened,view(target(),c)),view(target(),counts(0)),attempt,counts(1)).mergeInto(reopened);JsonFiles.write(file,task);reopened=JsonFiles.read(file);
        Change restored=coldReconcile(reopened,scope(),counts(1));assertEquals(1,restored.receipt().getJSONArray("queryOriginalAttempts").length());task=restored.mergeInto(reopened);assertFalse(plan(task,view(target(),counts(1))).hasCandidate());
        JSONObject unknown=task;assertThrows(SecurityException.class,()->observeOriginal(unknown,scope(),new OriginalAttempt(attempt.operationId(),attempt.childTaskId(),id(),attempt.childEpoch()),Outcome.COMPLETED,"d".repeat(64),counts(1)));
        task=observeOriginal(task,scope(),attempt,Outcome.COMPLETED,"d".repeat(64),counts(1)).mergeInto(task);assertEquals("completed",task.getJSONObject(KEY).getJSONArray("intents").getJSONObject(0).getString("status"));assertEquals("never_consumed_here",task.getJSONArray("nativeInput").getString(0));assertEquals(1,task.getJSONObject(KEY).getJSONObject("nativeClaims").getInt("total"));
    }
    @Test public void pauseStopExpiryRevocationUnknownAndBudgetReturnSpecificRetainedQueueReasons()throws Exception {
        var c=counts(0);JSONObject task=enqueue(configured(2,4,c),message(target(),SourceKind.DIRECTED_PEER,false,false),c);
        List<NativeView> views=List.of(gate(c,true,true,false,false,true,SourceState.CURRENT,NOW),gate(c,true,false,true,false,true,SourceState.CURRENT,NOW),gate(c,true,false,false,true,true,SourceState.CURRENT,NOW),gate(c,true,false,false,false,false,SourceState.CURRENT,NOW),gate(c,true,false,false,false,true,SourceState.REVOKED,NOW),gate(c,true,false,false,false,true,SourceState.UNKNOWN,NOW),gate(c,false,false,false,false,true,SourceState.CURRENT,NOW),gate(c,true,false,false,false,true,SourceState.CURRENT,NOW+100000));
        List<String> expected=List.of("owner_paused","owner_stopped","original_effect_unknown","original_budget_exhausted","source_revoked","source_currentness_unknown","not_safe_idle","expired");
        for(int i=0;i<views.size();i++){Plan p=plan(task,views.get(i));assertFalse(p.hasCandidate());assertEquals(expected.get(i),reason(p));JSONObject persisted=recordBlocked(p).mergeInto(task);assertEquals(expected.get(i),persisted.getJSONObject(KEY).getJSONArray("intents").getJSONObject(0).getString("reason"));assertEquals("never_consumed_here",persisted.getJSONArray("nativeInput").getString(0));}
    }
    @Test public void oldParentEpochMemberIdentityAndChildRunNeverTurnIntoCurrentAdmission()throws Exception {
        var c=counts(0);JSONObject task=enqueue(configured(2,4,c),message(target(),SourceKind.DIRECTED_PEER,false,false),c);Scope old=new Scope(scope().teamRunId(),scope().parentTaskId(),scope().parentRunId(),1,BUDGET);
        assertThrows(SecurityException.class,()->plan(task,new NativeView(old,target(),NOW,c,true,false,false,false,true,Map.of(SOURCE,SourceState.CURRENT))));
        Target a=target();for(Target changed:List.of(new Target(a.memberId(),2,a.runGeneration(),a.memberGeneration(),a.round(),a.childTaskId(),a.childRunId(),a.childEpoch(),a.bindingHash()),new Target(a.memberId(),a.identityRevision(),a.runGeneration(),a.memberGeneration()+1,a.round(),a.childTaskId(),a.childRunId(),a.childEpoch(),a.bindingHash()),new Target(a.memberId(),a.identityRevision(),a.runGeneration(),a.memberGeneration(),a.round(),a.childTaskId(),id(),a.childEpoch()+1,a.bindingHash())))assertEquals("stale_target_identity_run_epoch",reason(plan(task,view(changed,c))));
        Plan p=plan(task,view(a,c));assertThrows(SecurityException.class,()->recordOriginalClaim(task,p,view(target(),counts(0)),new OriginalAttempt(id(),a.childTaskId(),a.childRunId(),a.childEpoch()-1),counts(1)));assertThrows(SecurityException.class,()->recordOriginalClaim(task,p,view(target(),counts(0)),new OriginalAttempt(id(),id(),id(),a.childEpoch()+1),counts(1)));
        Target revisionChanged=new Target(a.memberId(),a.identityRevision(),a.runGeneration(),a.memberGeneration(),a.round(),a.childTaskId(),a.childRunId(),a.childEpoch(),"f".repeat(64));assertEquals("stale_target_identity_run_epoch",reason(plan(task,view(revisionChanged,c))));
    }
    @Test public void sharedNativeCumulativeFactsCannotBeBypassedWithNewMessageIdsMembersOrPolicyEdits()throws Exception {
        NativeClaims actual=counts(2);JSONObject task=configured(2,2,actual);task=enqueue(task,message(target(),SourceKind.DIRECTED_PEER,false,false),actual);
        Target other=new Target("other",1,3,4,1,id(),id(),5,"d".repeat(64));task=enqueue(task,message(other,SourceKind.DIRECTED_PEER,false,false),actual);
        assertEquals("cumulative_wake_limit",reason(plan(task,view(target(),actual))));assertEquals("cumulative_wake_limit",reason(plan(task,view(other,actual))));
        JSONObject original=task;assertThrows(SecurityException.class,()->configure(original,scope(),1,policy(2,4),NOW,counts(0)));assertThrows(SecurityException.class,()->plan(original,view(target(),counts(0))));
        task=configure(task,scope(),1,policy(2,4),NOW,actual).mergeInto(task);assertEquals(2,task.getJSONObject(KEY).getJSONObject("nativeClaims").getInt("total"));assertEquals("policy_revision_changed",reason(plan(task,view(target(),actual))));
        NativeClaims perRound=new NativeClaims(2,Map.of(1,1,2,1),"e".repeat(64));JSONObject round=enqueue(configured(1,4,perRound),message(target(),SourceKind.DIRECTED_PEER,false,false),perRound);assertEquals("round_wake_limit",reason(plan(round,view(target(),perRound))));
    }
    @Test public void failedBatchEscalatesOnceAcrossItsMessagesAndNeverMarksInputConsumed()throws Exception {
        var c=counts(0);JSONObject task=configured(2,4,c);task=enqueue(task,message(target(),SourceKind.DIRECTED_PEER,false,false),c);task=enqueue(task,message(target(),SourceKind.DIRECTED_PEER,false,false),c);OriginalAttempt attempt=attempt(target());task=recordOriginalClaim(task,plan(task,view(target(),c)),view(target(),counts(0)),attempt,counts(1)).mergeInto(task);
        task=observeOriginal(task,scope(),attempt,Outcome.FAILED,"f".repeat(64),counts(1)).mergeInto(task);Change first=takeEscalation(task,scope(),wake(task,0),counts(1));assertTrue(first.receipt().getBoolean("escalationCandidate"));task=first.mergeInto(task);
        Change second=takeEscalation(task,scope(),wake(task,1),counts(1));assertFalse(second.receipt().getBoolean("escalationCandidate"));assertTrue(second.receipt().getBoolean("duplicate"));task=second.mergeInto(task);
        assertTrue(observeOriginal(task,scope(),attempt,Outcome.FAILED,"f".repeat(64),counts(1)).receipt().getBoolean("duplicate"));JSONObject finalTask=task;assertThrows(SecurityException.class,()->observeOriginal(finalTask,scope(),attempt,Outcome.COMPLETED,"f".repeat(64),counts(1)));assertEquals("never_consumed_here",task.getJSONArray("nativeInput").getString(0));
    }
    @Test public void sameMessageCannotChangePayloadSourcePolicyOrGenerationOnReplay()throws Exception {
        var c=counts(0);Message m=message(target(),SourceKind.DIRECTED_PEER,false,false);JSONObject task=enqueue(configured(2,4,c),m,c);
        for(Message changed:List.of(new Message(m.scope(),m.target(),m.messageId(),"d".repeat(64),m.sourceBindingHash(),m.sourceKind(),false,false),new Message(m.scope(),m.target(),m.messageId(),m.payloadHash(),m.sourceBindingHash(),SourceKind.OWNER_FOREGROUND,false,false),new Message(m.scope(),new Target(target().memberId(),1,3,5,1,target().childTaskId(),target().childRunId(),5,target().bindingHash()),m.messageId(),m.payloadHash(),m.sourceBindingHash(),m.sourceKind(),false,false),new Message(m.scope(),new Target(target().memberId(),2,3,4,1,target().childTaskId(),target().childRunId(),5,target().bindingHash()),m.messageId(),m.payloadHash(),m.sourceBindingHash(),m.sourceKind(),false,false)))assertThrows(SecurityException.class,()->TeamWakeQueue.enqueue(task,changed,1,NOW,c));
        assertThrows(SecurityException.class,()->TeamWakeQueue.enqueue(task,m,2,NOW,c));
    }
    @Test public void queueCapacityAndUnreadOrTamperedStateFailWithoutEvictionOrReplacement()throws Exception {
        var c=counts(0);JSONObject task=configured(2,4,c);for(int i=0;i<MAX_INTENTS;i++)task=enqueue(task,message(target(),SourceKind.AUTHORIZED_CONTROL,false,false),c);JSONObject full=task;String before=task.toString();assertThrows(IllegalStateException.class,()->enqueue(full,message(target(),SourceKind.AUTHORIZED_CONTROL,false,false),c));assertEquals(before,task.toString());
        JSONObject corrupt=new JSONObject(task.toString());corrupt.getJSONObject(KEY).getJSONObject("nativeClaims").put("total",0).put("digest","f".repeat(64));assertThrows(SecurityException.class,()->plan(corrupt,view(target(),c)));
        JSONObject unread=new JSONObject().put(KEY,"unread old state");assertThrows(SecurityException.class,()->configure(unread,scope(),0,policy(2,4),NOW,c));assertEquals("unread old state",unread.getString(KEY));
        assertThrows(SecurityException.class,()->configure(new JSONObject().put(KEY,new JSONObject()),scope(),0,policy(2,4),NOW,c));
    }
    @Test public void policyDisabledAndSourceRestrictionCannotBeConvertedIntoPermissionByData()throws Exception {
        var c=counts(0);JSONObject task=new JSONObject();task=configure(task,scope(),0,new OwnerPolicy(false,Set.of(),1,2,NOW+10000),NOW,c).mergeInto(task);task=enqueue(task,message(target(),SourceKind.DIRECTED_PEER,false,false),c);assertEquals("policy_disabled",reason(plan(task,view(target(),c))));
        task=configure(task,scope(),1,new OwnerPolicy(true,Set.of(SourceKind.OWNER_FOREGROUND),1,2,NOW+10000),NOW,c).mergeInto(task);task=enqueue(task,message(target(),SourceKind.DIRECTED_PEER,false,false),c);assertEquals("source_not_allowed",plan(task,view(target(),c)).receipt().getJSONArray("blocked").getJSONObject(1).getString("reason"));
        JSONObject original=task;assertThrows(IllegalArgumentException.class,()->configure(original,scope(),2,policy(2,4),NOW+200000,c));assertThrows(SecurityException.class,()->new NativeClaims(1,Map.of(),"0".repeat(64)));
    }
    @Test public void peerCapacityLeavesOwnerAndControlSpaceWithoutDeletingHistoryOrUnknown()throws Exception {
        var c=counts(0);Message first=message(target(),SourceKind.DIRECTED_PEER,false,false);JSONObject task=enqueue(configured(2,4,c),first,c);OriginalAttempt original=attempt(target());
        task=recordOriginalClaim(task,plan(task,view(target(),c)),view(target(),c),original,counts(1)).mergeInto(task);c=counts(1);task=coldReconcile(task,scope(),c).mergeInto(task);
        while(task.getJSONObject(KEY).getJSONArray("intents").length()<MAX_PEER_BACKGROUND_INTENTS)task=enqueue(task,message(target(),SourceKind.DIRECTED_PEER,false,false),c);
        JSONObject peersFull=task;NativeClaims actual=c;assertThrows(IllegalStateException.class,()->enqueue(peersFull,message(target(),SourceKind.DIRECTED_PEER,false,false),actual));assertThrows(IllegalStateException.class,()->enqueue(peersFull,message(target(),SourceKind.BACKGROUND,false,false),actual));
        assertTrue(TeamWakeQueue.enqueue(task,first,1,NOW,c).receipt().getBoolean("duplicate"));
        while(task.getJSONObject(KEY).getJSONArray("intents").length()<MAX_NON_CONTROL_INTENTS)task=enqueue(task,message(target(),SourceKind.OWNER_FOREGROUND,false,false),c);
        JSONObject ownerFull=task;assertThrows(IllegalStateException.class,()->enqueue(ownerFull,message(target(),SourceKind.OWNER_FOREGROUND,false,false),actual));
        Message control=null;while(task.getJSONObject(KEY).getJSONArray("intents").length()<MAX_INTENTS){control=message(target(),SourceKind.AUTHORIZED_CONTROL,false,false);task=enqueue(task,control,c);}
        JSONObject full=task;assertThrows(IllegalStateException.class,()->enqueue(full,message(target(),SourceKind.AUTHORIZED_CONTROL,false,false),actual));
        Change duplicate=TeamWakeQueue.enqueue(task,control,1,NOW,c);assertTrue(duplicate.receipt().getBoolean("duplicate"));assertTrue(duplicate.receipt().getBoolean("queryOnly"));
        Change restored=coldReconcile(task,scope(),c);assertEquals(1,restored.receipt().getJSONArray("queryOriginalAttempts").length());JSONObject retained=restored.mergeInto(task);
        assertEquals(MAX_INTENTS,retained.getJSONObject(KEY).getJSONArray("intents").length());assertEquals("unknown",retained.getJSONObject(KEY).getJSONArray("intents").getJSONObject(0).getString("status"));assertEquals(original.operationId(),retained.getJSONObject(KEY).getJSONArray("intents").getJSONObject(0).getJSONObject("originalAttempt").getString("operationId"));assertEquals(1,retained.getJSONObject(KEY).getJSONObject("nativeClaims").getInt("total"));assertEquals(7,retained.getJSONObject("nativeBudget").getInt("used"));
    }
}
