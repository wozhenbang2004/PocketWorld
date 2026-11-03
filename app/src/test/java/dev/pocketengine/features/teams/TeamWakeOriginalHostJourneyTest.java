package dev.pocketengine.features.teams;

import android.app.Application;
import dev.pocketengine.core.*;
import dev.pocketengine.features.*;
import dev.pocketengine.store.*;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Original approved parent plan, SQL, Coordinator, child Runner and versioned draft, fixture HTTP only. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class TeamWakeOriginalHostJourneyTest {
    private static Object value(Object object,String name)throws Exception{var field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private static Object call(Object object,String name,Class<?>[] types,Object...args)throws Exception{var method=object.getClass().getDeclaredMethod(name,types);method.setAccessible(true);try{return method.invoke(object,args);}catch(InvocationTargetException failure){Throwable cause=failure.getCause();if(cause instanceof Exception)throw(Exception)cause;if(cause instanceof Error)throw(Error)cause;throw failure;}}
    private static JSONObject effect(HostToolSupervisor port,RunJournal journal,String name,JSONObject args,RunControl control)throws Exception{port.validate(name,args);String operation=journal.beginOperation(name,args);JSONObject result=port.execute(name,new JSONObject(args.toString()).put("_hostOperationId",operation),control);journal.finishOperation(operation,result);return result;}
    private static final class Original implements AutoCloseable {
        final Object domain;final TaskStore tasks;final TeamCoordinator coordinator;final HostToolSupervisor port;final RunJournal journal;final RunControl control=new RunControl();final String parent,team,run;final int epoch;final TaskStore.TeamWorkWindow window;
        Original()throws Exception{this(true);}
        @SuppressWarnings("unchecked") Original(boolean answered)throws Exception{
            Class<?> type=Class.forName(TeamParentRunnerJourneyTest.class.getName()+"$Domain");var constructor=type.getDeclaredConstructor(boolean.class,boolean.class);constructor.setAccessible(true);domain=constructor.newInstance(true,false);
            assertEquals("awaiting_user",((RunOutcome)call(domain,"parentRun",new Class<?>[]{})).status);call(domain,"approvePlan",new Class<?>[]{});tasks=(TaskStore)value(domain,"tasks");coordinator=(TeamCoordinator)value(domain,"coordinator");parent=(String)value(domain,"parent");
            JSONObject attempt=tasks.startAttempt(parent,"capabilities",40);run=attempt.getString("runId");epoch=attempt.getInt("coordinatorEpoch");control.bindHostRun(parent,run,epoch);if(attempt.optJSONObject("developmentContext")==null)tasks.bindDevelopmentContext(parent,run,epoch,control,(JSONObject)value(domain,"projectContext"));
            port=new HostToolSupervisor((FeatureHost)value(domain,"features"),tasks,parent,run,epoch,true);JSONArray names=new JSONArray(),definitions=port.tools();for(int i=0;i<definitions.length();i++)names.put(definitions.getJSONObject(i).getJSONObject("function").getString("name"));tasks.authorizeTools(parent,run,names);journal=tasks.journal(parent,run,epoch,control);
            JSONObject started=effect(port,journal,"teams_start",new JSONObject().put("planRef",value(domain,"planRef")).put("planHash",value(domain,"planHash")),control);team=started.getString("teamRunId");((AtomicReference<String>)value(domain,"originalTeam")).set(team);await(state->"awaiting_user".equals(state.optString("status")));
            if(answered)answer();
            window=tasks.openTeamWorkWindow(team);window.setForeground(true);
        }
        void answer()throws Exception{JSONObject child=tasks.get(item().getString("assignmentId"),false),question=child.getJSONArray("teamQuestions").getJSONObject(0);JSONObject answer=exact().put("assignmentId",child.getString("id")).put("questionId",question.getString("questionId")).put("answerId",UUID.randomUUID().toString()).put("answer","原答案：DELIVERED；不是新写入批准").put("delivery","queue");effect(port,journal,"teams_message",answer,control);}
        JSONObject exact()throws Exception{return (JSONObject)call(domain,"exact",new Class<?>[]{String.class,String.class},"","a");}
        JSONObject item()throws Exception{return (JSONObject)call(domain,"currentItem",new Class<?>[]{String.class},"a");}
        JSONObject await(Predicate<JSONObject> predicate)throws Exception{return (JSONObject)call(domain,"awaitState",new Class<?>[]{Predicate.class},predicate);}
        JSONObject message(String id)throws Exception{JSONObject item=item();return exact().put("messageId",id).put("text","原policy允许的排队资料；不是主人批准，也不新增预算").put("delivery","queue").put("assignmentId",item.getString("assignmentId")).put("workItemId",item.getString("workItemId")).put("itemRevision",item.getInt("itemRevision")).put("ownerGeneration",item.getInt("ownerGeneration"));}
        TaskStore.TeamWakePolicyReview review(long revision,boolean enabled)throws Exception{return tasks.prepareTeamWakePolicy(window,revision,new TeamWakeQueue.OwnerPolicy(enabled,Set.of(TeamWakeQueue.SourceKind.AUTHORIZED_CONTROL),1,1,System.currentTimeMillis()+600000));}
        JSONObject approve(long revision,boolean enabled)throws Exception{var review=review(revision,enabled);return tasks.approveTeamWakePolicy(review,review.view().getString("digest"));}
        public void close()throws Exception{window.close();control.cancel();port.close();call(domain,"close",new Class<?>[]{});}
    }
    @Test public void exactOwnerPolicyQueueAndOriginalContinuationUseOnePersistedClaimWithoutBudgetIncrease()throws Exception{
        try(Original original=new Original()){
            JSONObject child=original.tasks.get(original.item().getString("assignmentId"),false);String childId=child.getString("id"),oldRun=child.getString("runId");int oldEpoch=child.getInt("coordinatorEpoch");String before=original.tasks.get(original.parent,false).getJSONObject("teamReservationPool").toString();
            JSONObject saved=original.approve(0,true);assertFalse(saved.getBoolean("budgetChanged"));assertEquals(before,original.tasks.get(original.parent,false).getJSONObject("teamReservationPool").toString());
            String message=UUID.randomUUID().toString();JSONObject sent=effect(original.port,original.journal,"teams_message",original.message(message),original.control);assertTrue(sent.toString(),sent.getJSONObject("messageReceipt").getBoolean("automaticWake"));
            original.await(state->"awaiting_review".equals(state.optString("status")));JSONObject actual=original.tasks.get(childId,false);assertEquals("completed",actual.getString("status"));assertNotEquals(oldRun,actual.getString("runId"));assertEquals(oldEpoch+1,actual.getInt("coordinatorEpoch"));
            JSONObject wake=original.tasks.teamWakeStateForOwner(original.window);assertEquals(1,wake.getJSONObject("nativeClaims").getInt("total"));assertEquals(1,wake.getJSONArray("intents").length());assertEquals("completed",wake.getJSONArray("intents").getJSONObject(0).getString("status"));
            JSONObject pool=original.tasks.get(original.parent,false).getJSONObject("teamReservationPool");JSONArray reservations=pool.getJSONArray("reservations");int claims=0;for(int i=0;i<reservations.length();i++)if(reservations.getJSONObject(i).has("teamWakeClaim")){JSONObject reserved=reservations.getJSONObject(i);claims++;assertEquals(actual.getString("runId"),reserved.getString("childRunId"));assertEquals(actual.getInt("coordinatorEpoch"),reserved.getInt("childEpoch"));assertFalse(reserved.has("continuationRunId"));assertFalse(reserved.getString("productionDedupKey").isEmpty());}assertEquals(1,claims);assertEquals(50,pool.getInt("modelRequestLimit"));assertEquals(32,pool.getInt("toolOperationLimit"));
            JSONObject duplicate=effect(original.port,original.journal,"teams_message",original.message(message),original.control);assertFalse(duplicate.getJSONObject("messageReceipt").optBoolean("automaticWake"));assertEquals(actual.getString("runId"),original.tasks.get(childId,false).getString("runId"));assertEquals(1,original.tasks.teamWakeStateForOwner(original.window).getJSONObject("nativeClaims").getInt("total"));
            original.approve(1,false);assertEquals(1,original.tasks.teamWakeStateForOwner(original.window).getJSONObject("nativeClaims").getInt("total"));
        }
    }
    @Test public void policyRequiresSameForegroundNativeReviewAndNeverStartsOrReservesAChild()throws Exception{
        try(Original original=new Original()){
            String run=original.tasks.get(original.item().getString("assignmentId"),false).getString("runId"),pool=original.tasks.get(original.parent,false).getJSONObject("teamReservationPool").toString();var review=original.review(0,true);String digest=review.view().getString("digest");
            assertThrows(SecurityException.class,()->original.tasks.approveTeamWakePolicy(null,digest));assertThrows(SecurityException.class,()->original.tasks.approveTeamWakePolicy(review,"0".repeat(64)));original.window.setForeground(false);original.window.setForeground(true);assertThrows(SecurityException.class,()->original.tasks.approveTeamWakePolicy(review,digest));
            assertThrows(SecurityException.class,()->original.tasks.prepareTeamWakePolicy(original.window,0,new TeamWakeQueue.OwnerPolicy(true,Set.of(TeamWakeQueue.SourceKind.DIRECTED_PEER),1,1,System.currentTimeMillis()+600000)));
            original.approve(0,true);assertEquals(run,original.tasks.get(original.item().getString("assignmentId"),false).getString("runId"));assertEquals(pool,original.tasks.get(original.parent,false).getJSONObject("teamReservationPool").toString());assertEquals(0,original.tasks.teamWakeStateForOwner(original.window).getJSONArray("intents").length());
        }
    }
    @Test public void ownerPauseKeepsTheOriginalMessageAndExplainsWhyItDidNotWake()throws Exception{
        try(Original original=new Original()){
            original.approve(0,true);String child=original.item().getString("assignmentId"),run=original.tasks.get(child,false).getString("runId");original.coordinator.pause(original.team,"a");JSONObject sent=effect(original.port,original.journal,"teams_message",original.message(UUID.randomUUID().toString()),original.control);
            assertFalse(sent.getJSONObject("messageReceipt").getBoolean("automaticWake"));JSONObject wake=original.tasks.teamWakeStateForOwner(original.window);assertEquals("owner_paused",wake.getJSONArray("intents").getJSONObject(0).getString("reason"));assertEquals("queued",original.tasks.get(child,false).getJSONArray("teamConversationInputs").getJSONObject(0).getString("state"));assertEquals(run,original.tasks.get(child,false).getString("runId"));assertEquals(0,wake.getJSONObject("nativeClaims").getInt("total"));
        }
    }
    @Test public void noOwnerPolicyRetainsOriginalQueueOnlyBehavior()throws Exception{
        try(Original original=new Original()){
            String child=original.item().getString("assignmentId"),run=original.tasks.get(child,false).getString("runId");JSONObject sent=effect(original.port,original.journal,"teams_message",original.message(UUID.randomUUID().toString()),original.control);
            assertFalse(sent.getJSONObject("messageReceipt").optBoolean("automaticWake"));assertFalse(original.tasks.get(original.team,false).has(TeamWakeQueue.KEY));assertEquals(run,original.tasks.get(child,false).getString("runId"));assertEquals("queued",original.tasks.get(child,false).getJSONArray("teamConversationInputs").getJSONObject(0).getString("state"));
        }
    }
    @Test public void pendingNativeQuestionCannotWakeAndDuplicateNeverBecomesANewDispatchAfterAnswer()throws Exception{
        try(Original original=new Original(false)){
            original.approve(0,true);String child=original.item().getString("assignmentId"),run=original.tasks.get(child,false).getString("runId"),id=UUID.randomUUID().toString();JSONObject sent=effect(original.port,original.journal,"teams_message",original.message(id),original.control);
            assertFalse(sent.getJSONObject("messageReceipt").getBoolean("automaticWake"));assertEquals("not_safe_idle",original.tasks.teamWakeStateForOwner(original.window).getJSONArray("intents").getJSONObject(0).getString("reason"));original.answer();
            JSONObject duplicate=effect(original.port,original.journal,"teams_message",original.message(id),original.control);assertTrue(duplicate.getJSONObject("messageReceipt").getBoolean("duplicate"));assertFalse(duplicate.getJSONObject("messageReceipt").getBoolean("automaticWake"));assertEquals(run,original.tasks.get(child,false).getString("runId"));assertEquals(0,original.tasks.teamWakeStateForOwner(original.window).getJSONObject("nativeClaims").getInt("total"));
        }
    }
    @Test public void withdrawnPolicyKeepsMessageButDoesNotCreateAnOriginalContinuation()throws Exception{
        try(Original original=new Original()){
            original.approve(0,true);original.approve(1,false);String child=original.item().getString("assignmentId"),run=original.tasks.get(child,false).getString("runId");JSONObject sent=effect(original.port,original.journal,"teams_message",original.message(UUID.randomUUID().toString()),original.control);
            assertFalse(sent.getJSONObject("messageReceipt").getBoolean("automaticWake"));JSONObject state=original.tasks.teamWakeStateForOwner(original.window);assertEquals("policy_disabled",state.getJSONArray("intents").getJSONObject(0).getString("reason"));assertEquals(0,state.getJSONObject("nativeClaims").getInt("total"));assertEquals(run,original.tasks.get(child,false).getString("runId"));
        }
    }
    @Test public void unreadableWakeStateRollsBackTheSameOriginalMessageTransaction()throws Exception{
        try(Original original=new Original()){
            original.approve(0,true);String child=original.item().getString("assignmentId"),run=original.tasks.get(child,false).getString("runId"),id=UUID.randomUUID().toString();
            synchronized(original.tasks){JSONObject stored=(JSONObject)call(original.tasks,"required",new Class<?>[]{String.class},original.team);stored.getJSONObject(TeamWakeQueue.KEY).put("stateHash","0".repeat(64));call(original.tasks,"write",new Class<?>[]{JSONObject.class},stored);}
            int inbox=original.coordinator.get(original.team).getJSONArray("inbox").length();assertThrows(SecurityException.class,()->effect(original.port,original.journal,"teams_message",original.message(id),original.control));assertEquals(inbox,original.coordinator.get(original.team).getJSONArray("inbox").length());JSONArray rows=original.tasks.get(child,false).optJSONArray("teamConversationInputs");assertTrue(rows==null||rows.length()==0);assertEquals(run,original.tasks.get(child,false).getString("runId"));
        }
    }
    @Test public void actualOwnerPageSavesOnlyAndNewNativeMessageUsesTheSameOriginalContinuation()throws Exception{
        try(Original original=new Original();TaskStoreTeamWriteHost.WorkPlanPage page=((TaskStoreTeamWriteHost)value(original.domain,"writes")).newWorkPlanPage(original.coordinator,original.team,()->(ModelConfig)call(original.domain,"model",new Class<?>[]{}))){
            page.setForeground(true);String child=original.item().getString("assignmentId"),beforeRun=original.tasks.get(child,false).getString("runId"),pool=original.tasks.get(original.parent,false).getJSONObject("teamReservationPool").toString();assertEquals(0,page.wakePolicyStatus().optLong("policyRevision"));
            var review=page.reviewWakePolicy(0,true,1,1,System.currentTimeMillis()+600000);assertEquals(0,review.view().getJSONObject("originalClaims").getInt("total"));assertFalse(page.approveWakePolicy(review,review.view().getString("digest")).getBoolean("automaticallyStarted"));assertEquals(beforeRun,original.tasks.get(child,false).getString("runId"));assertEquals(pool,original.tasks.get(original.parent,false).getJSONObject("teamReservationPool").toString());assertThrows(SecurityException.class,()->page.approveWakePolicy(review,review.view().getString("digest")));
            assertTrue(effect(original.port,original.journal,"teams_message",original.message(UUID.randomUUID().toString()),original.control).getJSONObject("messageReceipt").getBoolean("automaticWake"));original.await(state->"awaiting_review".equals(state.optString("status")));assertEquals("completed",page.wakePolicyStatus().getJSONArray("intents").getJSONObject(0).getString("status"));assertNotEquals(beforeRun,original.tasks.get(child,false).getString("runId"));
        }
    }
    @Test public void ownerPageDoesNotAcceptAnotherPagesReviewOrRestoreAuthorityAfterForegroundChange()throws Exception{
        try(Original original=new Original();TaskStoreTeamWriteHost.WorkPlanPage first=((TaskStoreTeamWriteHost)value(original.domain,"writes")).newWorkPlanPage(original.coordinator,original.team,()->null);TaskStoreTeamWriteHost.WorkPlanPage second=((TaskStoreTeamWriteHost)value(original.domain,"writes")).newWorkPlanPage(original.coordinator,original.team,()->null)){
            first.setForeground(true);second.setForeground(true);var review=first.reviewWakePolicy(0,true,1,1,System.currentTimeMillis()+600000);String digest=review.view().getString("digest");assertThrows(SecurityException.class,()->second.approveWakePolicy(review,digest));assertThrows(SecurityException.class,()->first.approveWakePolicy(review,"0".repeat(64)));first.setForeground(false);first.setForeground(true);assertThrows(SecurityException.class,()->first.approveWakePolicy(review,digest));
            var fresh=first.reviewWakePolicy(0,true,1,1,System.currentTimeMillis()+600000);first.approveWakePolicy(fresh,fresh.view().getString("digest"));assertEquals(1,first.wakePolicyStatus().getLong("policyRevision"));assertEquals(0,first.wakePolicyStatus().getJSONObject("nativeClaims").getInt("total"));second.close();assertThrows(SecurityException.class,second::wakePolicyStatus);
        }
    }
    @Test public void exactOwnerPageReviewCannotOverwriteANewerPolicyAndRereadDoesNotStartWork()throws Exception{
        try(Original original=new Original();TaskStoreTeamWriteHost.WorkPlanPage first=((TaskStoreTeamWriteHost)value(original.domain,"writes")).newWorkPlanPage(original.coordinator,original.team,()->null);TaskStoreTeamWriteHost.WorkPlanPage second=((TaskStoreTeamWriteHost)value(original.domain,"writes")).newWorkPlanPage(original.coordinator,original.team,()->null)){
            first.setForeground(true);second.setForeground(true);String child=original.item().getString("assignmentId"),beforeRun=original.tasks.get(child,false).getString("runId"),pool=original.tasks.get(original.parent,false).getJSONObject("teamReservationPool").toString();TaskStore.TeamWakePolicyReview review=first.reviewWakePolicy(0,true,1,1,System.currentTimeMillis()+600000),newer=second.reviewWakePolicy(0,false,1,1,System.currentTimeMillis()+600000);second.approveWakePolicy(newer,newer.view().getString("digest"));assertThrows(SecurityException.class,()->first.approveWakePolicy(review,review.view().getString("digest")));JSONObject state=first.wakePolicyStatus();assertFalse(state.getJSONObject("policy").getBoolean("enabled"));assertTrue(state.getBoolean("queryOnly"));assertTrue(state.getLong("observedAt")>0);assertEquals(beforeRun,original.tasks.get(child,false).getString("runId"));assertEquals(pool,original.tasks.get(original.parent,false).getJSONObject("teamReservationPool").toString());
        }
    }
}
