package dev.pocketengine.features.teams;

import static org.junit.Assert.*;
import android.app.Application;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.*;
import dev.pocketengine.runtime.context.*;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/** Actual owner-reviewed project plan/undispatched receipt/continuation, not a JSON grant.
 * Historical captured prefix is only test DATA. This narrow test never executes the approved
 * command through compaction; full original Runner V2 is verified separately. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class TeamApprovedContinuationCompactionNativeTest {
    private static String id(){return UUID.randomUUID().toString();}
    private static SQLiteDatabase db(TaskStore tasks)throws Exception{var f=TaskStore.class.getDeclaredField("db");f.setAccessible(true);return (SQLiteDatabase)f.get(tasks);}
    private static JSONObject record(TaskStore tasks,String id)throws Exception{Method m=TaskStore.class.getDeclaredMethod("required",String.class);m.setAccessible(true);return new JSONObject(((JSONObject)m.invoke(tasks,id)).toString());}
    private static void write(TaskStore tasks,JSONObject value)throws Exception{Method m=TaskStore.class.getDeclaredMethod("write",JSONObject.class);m.setAccessible(true);m.invoke(tasks,value);}
    private static JSONObject checkpoint(TaskStore tasks,String run)throws Exception{try(Cursor rows=db(tasks).rawQuery("SELECT checkpoint FROM runs WHERE run_id=?",new String[]{run})){assertTrue(rows.moveToFirst());return new JSONObject(rows.getString(0));}}
    private static JSONObject pending(JSONObject task)throws Exception{for(int i=0;i<task.getJSONArray("approvals").length();i++){JSONObject a=task.getJSONArray("approvals").getJSONObject(i);if(task.getString("runId").equals(a.optString("runId"))&&"project_execute".equals(a.optString("tool"))&&"pending".equals(a.optString("state")))return a;}return null;}
    private static JSONObject item(String member,String path)throws Exception{return new JSONObject().put("workItemId",id()).put("memberId",member).put("title",path).put("instruction","精确制作"+path+"，真实原检查与冻结").put("acceptance",member.equals("a")?"实际导出2":"实际导出20").put("paths",new JSONArray().put(path)).put("dependsOn",new JSONArray());}
    @FunctionalInterface private interface Change {void change(JSONObject approval)throws Exception;}
    private static void deniedCurrent(TaskStore tasks,String task,String approvalId,Change change,CompactionJournal host,CompactionJournal.FrozenPrefix frozen,RunControl control)throws Exception {
        synchronized(tasks){SQLiteDatabase db=db(tasks);db.beginTransaction();try{JSONObject value=record(tasks,task),target=null;for(int i=0;i<value.getJSONArray("approvals").length();i++){JSONObject a=value.getJSONArray("approvals").getJSONObject(i);if(approvalId.equals(a.getString("id")))target=a;}assertNotNull(target);change.change(target);write(tasks,value);boolean denied=false;try{denied=!host.current(frozen,control);}catch(CompactionPlan.Unavailable|SecurityException expected){denied=true;}assertTrue(denied);}finally{db.endTransaction();}}
        assertFalse(control.isCancelled());assertTrue(host.current(frozen,control));
    }
    @Test public void nativeApprovedUnsentPlanKeepsItsWholeToolTailAndCannotForgeConsumedProgress()throws Exception {
        try(TeamProjectRunnerDeliveryJourneyTest.Fixture f=new TeamProjectRunnerDeliveryJourneyTest.Fixture(false);TaskStoreTeamWriteHost.WorkPlanPage page=f.host.newWorkPlanPage(f.coordinator,f.run,()->f.model)){
            page.setForeground(true);TaskStore.TeamWorkReview proposal=page.reviewProject(new JSONArray().put(item("a","a.js")).put(item("b","b.js")),f.options());if(proposal.view().optBoolean("parentBudgetIncreaseRequired"))proposal=page.approveParentBudget(proposal,proposal.view().getString("digest"));page.approve(proposal,proposal.view().getString("digest"));f.authors(page);
            JSONObject manifest=f.host.review(f.run,new RunControl()).snapshot(),independent=f.host.independentlyReview(f.run,manifest.getString("planHash"),f.coordinator.get(f.run),f.model.withModel("reviewer"));f.coordinator.recordIndependentReview(f.run,independent);JSONObject owner=f.host.approve(f.run,manifest.getString("planHash"),new RunControl());
            JSONObject task=null,approval=null;
            for(int i=0;i<7;i++){JSONObject state=f.host.integrate(f.run,owner.getString("reviewId"),f.model.withModel("integrator"));f.coordinator.recordIntegration(f.run,state);assertFalse(f.tasks.hasUnresolved(state.getString("taskId")));task=f.tasks.get(state.getString("taskId"),false);approval=pending(task);if(approval!=null)break;assertTrue(f.approvePending()>0);}
            assertNotNull("first real project command proposes exact owner approval",approval);assertNotNull(task);String taskId=task.getString("id"),priorRun=task.getString("runId"),approvalId=approval.getString("id");JSONObject nativePrior=checkpoint(f.tasks,priorRun);f.tasks.decideCapabilityApproval(taskId,approvalId,true);
            RunControl control=new RunControl();JSONObject binding=f.tasks.startTeamIntegration(f.run,owner.getString("reviewId"),control,24);String run=binding.getString("runId");assertNotEquals(priorRun,run);assertNull(f.extraControls.putIfAbsent(taskId,control));
            CapabilityPort files=new TeamIntegrationFiles(f.files,new TaskStoreTeamAssignments(f.tasks,changed->{}).fileHost(binding,control),control,binding);
            CapabilityPort project=new TeamProjectWorkspaces(f.tasks,f.local,f.files,changed->{}).integratorPort(binding,control,files);ModelConfig selected=f.model.withModel("integrator");TeamIntegrationCompaction session=TeamIntegrationCompaction.open(f.tasks,binding,control,selected,project);
            try(AutoCloseable retained=control.retainAuthorityCheck(session::checkCurrent)){
                JSONArray originalPrefix=new JSONObject(Files.readString(Path.of(System.getProperty("c6.approved.continuation.fixture")))).getJSONArray("messages"),raw=new JSONArray();
                // Entire actual native history remains verbatim. Only extra captured settled test
                // DATA precedes it; no owner words/real receipts are removed or authority imported.
                for(int i=0;i<87;i++)raw.put(originalPrefix.get(i));JSONArray actual=nativePrior.getJSONArray("messages");for(int i=0;i<actual.length();i++)if(i!=0||!"system".equals(actual.getJSONObject(i).optString("role")))raw.put(actual.get(i));
                session.journal().checkpoint(raw,nativePrior.getInt("steps"),new JSONObject().put("noDeviceObservation",true));session.port().prepareTurn("只整理历史；准确已批计划仍须原project_execute/current capability，不由摘要执行",0,nativePrior.getInt("steps"),control);
                ContextBudgetPolicy.Source narrow=config->ContextBudgetPolicy.RouteBudget.forRoute(config,new ContextWindow.Budget(96000,1000,1000,2L*1024*1024,64,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,System.currentTimeMillis());
                CompactionJournal nativeHost=session.journal().compaction();AtomicReference<CompactionJournal.FrozenPrefix> captured=new AtomicReference<>();AtomicReference<SummaryDraft> draft=new AtomicReference<>();
                CompactionJournal observed=new CompactionJournal(){public FrozenPrefix freeze(CompactionPlan plan,RunControl live)throws Exception{FrozenPrefix value=nativeHost.freeze(plan,live);captured.set(value);return value;}public PublishReceipt publish(FrozenPrefix expected,SummaryDraft actual,RunControl live)throws Exception{draft.set(actual);return nativeHost.publish(expected,actual,live);}public boolean current(FrozenPrefix expected,RunControl live)throws Exception{return nativeHost.current(expected,live);}public boolean scopeCurrent(RunControl live)throws Exception{return nativeHost.scopeCurrent(live);}};
                ArrayList<Integer> map=new ArrayList<>();for(int i=0;i<raw.length();i++)map.add(i);int beforePosts=f.httpCalls.get(),beforeSummary=f.summaryCalls.get();JSONObject beforeBudget=f.tasks.teamBudgetSnapshot(f.run);RunContextBudget budget=new RunContextBudget(narrow);
                assertThrows(RunContextBudget.Blocked.class,()->budget.request(raw,session.port().tools(),selected));RunContextBudget.Projection request=new ContextCompactor(session.journal(),observed,narrow).request(raw,raw,map,session.port().tools(),selected,control,budget);
                assertTrue(request.diagnostics().getBoolean("summariesGenerated"));assertTrue(request.plan().canSend());assertEquals(beforePosts+1,f.httpCalls.get());assertEquals(beforeSummary+1,f.summaryCalls.get());assertNotNull(captured.get());assertNotNull(draft.get());assertTrue(nativeHost.current(captured.get(),control));
                JSONObject current=f.tasks.get(taskId,false),continued=null;for(int i=0;i<current.getJSONArray("approvals").length();i++){JSONObject a=current.getJSONArray("approvals").getJSONObject(i);if(approvalId.equals(a.getString("id")))continued=a;}assertNotNull(continued);assertEquals("approved",continued.getString("state"));assertTrue(continued.getBoolean("nativeCapabilityRevalidationRequired"));assertEquals(binding.getInt("coordinatorEpoch"),continued.getInt("continuedToEpoch"));
                assertEquals(beforeBudget.getInt("modelRequestsReservedOrUsed")+1,f.tasks.teamBudgetSnapshot(f.run).getInt("modelRequestsReservedOrUsed"));assertEquals(beforeBudget.getInt("toolOperationsReservedOrUsed"),f.tasks.teamBudgetSnapshot(f.run).getInt("toolOperationsReservedOrUsed"));
                deniedCurrent(f.tasks,taskId,approvalId,a->a.put("state","pending"),nativeHost,captured.get(),control);
                deniedCurrent(f.tasks,taskId,approvalId,a->a.put("argumentsHash","0".repeat(64)),nativeHost,captured.get(),control);
                deniedCurrent(f.tasks,taskId,approvalId,a->a.put("notDispatchedOperationId",id()),nativeHost,captured.get(),control);
                deniedCurrent(f.tasks,taskId,approvalId,a->a.put("nativeCapabilityRevalidationRequired",false),nativeHost,captured.get(),control);
                deniedCurrent(f.tasks,taskId,approvalId,a->a.put("continuedToEpoch",binding.getInt("coordinatorEpoch")+1),nativeHost,captured.get(),control);
                // Merely editing approved->consumed is not the original native claim/receipt.
                deniedCurrent(f.tasks,taskId,approvalId,a->a.put("state","consumed"),nativeHost,captured.get(),control);
                synchronized(f.tasks){SQLiteDatabase db=db(f.tasks);db.beginTransaction();try{JSONObject value=record(f.tasks,taskId);for(int i=0;i<value.getJSONArray("approvals").length();i++){JSONObject a=value.getJSONArray("approvals").getJSONObject(i);if(approvalId.equals(a.getString("id")))a.put("state","consumed");}write(f.tasks,value);assertEquals("stale",nativeHost.publish(captured.get(),draft.get(),control).status);}finally{db.endTransaction();}}
                assertTrue(nativeHost.current(captured.get(),control));assertEquals(beforePosts+1,f.httpCalls.get());assertFalse(f.tasks.hasUnresolved(taskId));f.diagnose("native_approved_continuation_tail_summary_preserved");
            }finally{session.port().close();f.extraControls.remove(taskId,control);f.tasks.endAttempt(taskId,run,"awaiting_user","窄例仅整理；已批命令未执行，授权仍需原实际执行门",new JSONObject());}
            assertNull(f.failure.get());
        }
    }
}
