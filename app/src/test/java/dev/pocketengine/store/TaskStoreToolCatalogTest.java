package dev.pocketengine.store;

import android.app.Application;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.CapabilitySnapshot;
import dev.pocketengine.core.ExecutionPlanning;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.core.ScopedToolCatalog;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Real original TaskStore SQL, native snapshots and read claims; no JSON-created authority. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class TaskStoreToolCatalogTest {
    private static final String SEARCH=ScopedToolCatalog.SEARCH_TOOL;
    private static final Set<String> BUSINESS=Set.of("memory_search","workspace_write");
    private TaskStore store;
    private String id,run;
    private int epoch;
    private RunControl original;
    private final List<TaskStore.ToolCatalogAccess> opened=new ArrayList<>();

    private static SQLiteDatabase database(TaskStore value)throws Exception {
        Field field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);return (SQLiteDatabase)field.get(value);
    }
    @Before public void prepare()throws Exception {
        Application app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");store=new TaskStore(app);
    }
    @After public void close()throws Exception {
        for(TaskStore.ToolCatalogAccess access:opened)access.close();
        if(original!=null)original.cancel();
        if(store!=null&&database(store).isOpen())database(store).close();
    }
    private void start(boolean planning,int toolLimit,Set<String> tools)throws Exception {
        id=store.createCapabilityTask("Discover only tools already authorized for this original task","").getString("id");
        JSONObject initialized=store.initializeExecutionScope(id,toolLimit);
        assertFalse(initialized.getJSONObject("executionScope").has("executionPhase"));
        if(planning)store.enterExecutionPlanning(id,"",0,initialized.getJSONObject("executionScope").getLong("revision"),()->true);
        JSONObject attempt=store.startAttempt(id,"capabilities",toolLimit);run=attempt.getString("runId");epoch=attempt.getInt("coordinatorEpoch");
        original=new RunControl();original.bindHostRun(id,run,epoch);
        store.authorizeTools(id,run,new JSONArray(tools));store.status(id,"running","Native catalogue SQL fixture");
        assertEquals(tools,names(grant().getJSONArray("tools")));assertBudget(0,toolLimit);
    }
    private JSONObject scope()throws Exception{return store.get(id,false).getJSONObject("executionScope");}
    private JSONObject grant()throws Exception {
        try(Cursor row=database(store).rawQuery("SELECT grant_json FROM runs WHERE task_id=? AND run_id=?",new String[]{id,run})){
            assertTrue(row.moveToFirst());JSONObject result=new JSONObject(row.getString(0));assertFalse(row.moveToNext());return result;
        }
    }
    private JSONArray operations()throws Exception{return store.get(id,false).getJSONArray("operations");}
    private JSONObject onlyOperation()throws Exception{JSONArray rows=operations();assertEquals(1,rows.length());return rows.getJSONObject(0);}
    private static Set<String> names(JSONArray array)throws Exception {
        Set<String> result=new LinkedHashSet<>();for(int index=0;index<array.length();index++)assertTrue(result.add(array.getString(index)));return result;
    }
    private void assertBudget(int used,int remaining)throws Exception {
        assertEquals(used,scope().getInt("toolsUsed"));assertEquals(remaining,grant().getInt("remainingOperations"));
    }
    private CapabilitySnapshot snapshot(Set<String> candidates,AtomicBoolean sourceCurrent) {
        return new CapabilitySnapshot(id,run,epoch,original,candidates,()->{
            assertFalse("Native source checks must stay outside the SQL monitor",Thread.holdsLock(store));
            if(!sourceCurrent.get())throw new SecurityException("Original native installation changed");
        });
    }
    private CapabilitySnapshot snapshot(Set<String> candidates){return snapshot(candidates,new AtomicBoolean(true));}
    private TaskStore.ToolCatalogAccess open(CapabilitySnapshot snapshot,AtomicBoolean published)throws Exception {
        TaskStore.ToolCatalogAccess access=store.openToolCatalog(id,run,epoch,original,snapshot);assertNotNull(access);opened.add(access);
        if(published!=null)access.bindRequest(()->{
            assertFalse("Native publication checks must stay outside the SQL monitor",Thread.holdsLock(store));
            if(!published.get())throw new SecurityException("Original request no longer publishes search");
        });
        return access;
    }
    private TaskStore.ToolCatalogAccess open(Set<String> candidates)throws Exception{return open(snapshot(candidates),new AtomicBoolean(true));}
    private static JSONObject args()throws Exception{return new JSONObject().put("query","original evidence").put("limit",2);}
    private static JSONObject page(String proof)throws Exception{return new JSONObject().put("ok",true).put("status","no_match").put("tools",new JSONArray()).put("nativeProof",proof);}

    @Test public void oneHundredActualBusinessToolsDoNotGainASearchSqlGrant()throws Exception {
        Set<String> hundred=new LinkedHashSet<>();for(int index=0;index<100;index++)hundred.add("native_tool_"+index);
        start(false,8,hundred);CapabilitySnapshot nativeSnapshot=snapshot(hundred);TaskStore.ToolCatalogAccess access=open(nativeSnapshot,new AtomicBoolean(true));
        assertEquals(hundred,access.admittedTools());assertFalse(access.planning());
        assertEquals(hundred,nativeSnapshot.catalogTools(id,run,epoch,original));
        assertEquals(hundred,names(grant().getJSONArray("tools")));assertEquals(hundred,names(scope().getJSONArray("tools")));
        assertFalse(access.admittedTools().contains(SEARCH));assertFalse(names(grant().getJSONArray("tools")).contains(SEARCH));
        assertEquals(0,operations().length());assertBudget(0,8);
    }

    @Test public void candidateDefinitionsCannotExpandTheOriginalSqlAndScopeTools()throws Exception {
        start(false,8,BUSINESS);CapabilitySnapshot nativeSnapshot=snapshot(Set.of("memory_search","not_granted"));
        TaskStore.ToolCatalogAccess access=open(nativeSnapshot,new AtomicBoolean(true));
        assertEquals(Set.of("memory_search"),access.admittedTools());assertEquals(access.admittedTools(),nativeSnapshot.catalogTools(id,run,epoch,original));
        assertEquals(BUSINESS,names(grant().getJSONArray("tools")));assertEquals(BUSINESS,names(scope().getJSONArray("tools")));assertBudget(0,8);
        assertThrows(UnsupportedOperationException.class,()->access.admittedTools().add("not_granted"));
    }

    @Test public void executingSearchRecordsOnePendingReadAndSettlesTheSameExpenseOnce()throws Exception {
        start(false,8,BUSINESS);TaskStore.ToolCatalogAccess access=open(BUSINESS);AtomicInteger reads=new AtomicInteger();JSONObject request=args();
        JSONObject actual=access.executeRead(request,original,()->{
            assertFalse(Thread.holdsLock(store));reads.incrementAndGet();assertBudget(1,7);
            JSONObject pending=onlyOperation();assertEquals(SEARCH,pending.getString("tool"));assertEquals("read_pending",pending.getString("state"));
            assertEquals(TaskStore.capabilityFingerprint(request),TaskStore.capabilityFingerprint(pending.getJSONObject("arguments")));
            return page("original-metadata-read");
        });
        assertEquals(1,reads.get());assertEquals("original-metadata-read",actual.getString("nativeProof"));
        JSONObject record=onlyOperation(),receipt=record.getJSONObject("receipt");assertEquals("read_receipt",record.getString("state"));
        assertFalse(receipt.getBoolean("planningRead"));assertTrue(receipt.getBoolean("catalogRead"));assertFalse(receipt.getBoolean("replayed"));
        assertEquals("original-metadata-read",receipt.getString("nativeProof"));assertEquals(record.getString("operationId"),receipt.getString("operationId"));
        assertBudget(1,7);access.check(original);assertEquals(BUSINESS,names(grant().getJSONArray("tools")));assertFalse(store.hasUnresolved(id));
    }

    @Test public void planningSearchReusesTheRunnersClaimWithoutASecondDebitOrSettlement()throws Exception {
        start(true,8,BUSINESS);TaskStore.ToolCatalogAccess access=open(BUSINESS);assertTrue(access.planning());
        ExecutionPlanning planning=store.journal(id,run,epoch,original).planning();JSONObject request=args();
        ExecutionPlanning.ReadClaim claim=planning.beginRead(planning.capture(),SEARCH,request,"original-runner-search",ExecutionPlanning.Access.READ);
        AtomicInteger reads=new AtomicInteger();assertBudget(1,7);
        JSONObject actual=access.executeRead(request,original,()->{assertFalse(Thread.holdsLock(store));reads.incrementAndGet();assertEquals(claim.operationId,onlyOperation().getString("operationId"));assertEquals("read_pending",onlyOperation().getString("state"));return page("runner-owned-read");});
        assertEquals(1,reads.get());assertEquals("read_pending",onlyOperation().getString("state"));assertBudget(1,7);
        planning.finishRead(claim,actual,ExecutionPlanning.ReadOutcome.RECEIPT);
        JSONObject receipt=onlyOperation().getJSONObject("receipt");assertEquals("read_receipt",onlyOperation().getString("state"));assertTrue(receipt.getBoolean("planningRead"));assertTrue(receipt.getBoolean("catalogRead"));
        assertEquals("runner-owned-read",receipt.getString("nativeProof"));assertBudget(1,7);
        assertThrows(SecurityException.class,()->planning.finishRead(claim,actual,ExecutionPlanning.ReadOutcome.RECEIPT));
        assertThrows(SecurityException.class,()->access.executeRead(request,original,()->{reads.incrementAndGet();return page("must-not-replay");}));assertEquals(1,reads.get());assertBudget(1,7);
    }

    @Test public void planningSearchRequiresItsNativeAccessAndOneCurrentPublicationBinding()throws Exception {
        start(true,8,BUSINESS);ExecutionPlanning planning=store.journal(id,run,epoch,original).planning();CapabilitySnapshot nativeSnapshot=snapshot(BUSINESS);
        assertThrows(SecurityException.class,()->nativeSnapshot.catalogTools(id,run,epoch,original));
        assertThrows(SecurityException.class,()->planning.beginRead(planning.capture(),SEARCH,args(),"no-native-catalog",ExecutionPlanning.Access.READ));
        TaskStore.ToolCatalogAccess access=open(nativeSnapshot,null);
        assertThrows(SecurityException.class,()->planning.beginRead(planning.capture(),SEARCH,args(),"not-published",ExecutionPlanning.Access.READ));
        access.bindRequest(()->assertFalse(Thread.holdsLock(store)));
        assertThrows(SecurityException.class,()->access.bindRequest(()->{}));
        assertThrows(SecurityException.class,()->access.executeRead(args(),original,()->{fail("No Runner claim was issued");return page("unreachable");}));
        assertEquals(0,operations().length());assertBudget(0,8);
    }

    @Test public void matchingJsonRunIdentityCannotBorrowAnotherNativeControl()throws Exception {
        start(false,8,BUSINESS);CapabilitySnapshot nativeSnapshot=snapshot(BUSINESS);TaskStore.ToolCatalogAccess access=open(nativeSnapshot,new AtomicBoolean(true));
        RunControl foreign=new RunControl();foreign.bindHostRun(id,run,epoch);
        assertThrows(SecurityException.class,()->store.openToolCatalog(id,run,epoch,foreign,nativeSnapshot));
        assertThrows(SecurityException.class,()->access.check(foreign));
        assertThrows(SecurityException.class,()->access.executeRead(args(),foreign,()->{fail("Foreign control must not read");return page("unreachable");}));
        assertEquals(0,operations().length());assertBudget(0,8);
    }

    @Test public void returningAnApprovedScopeToPlanningInvalidatesTheOldCatalogWithoutRefilling()throws Exception {
        start(true,8,BUSINESS);ExecutionPlanning planning=store.journal(id,run,epoch,original).planning();
        JSONObject plan=new JSONObject().put("format","pocketworld.execution-plan.v1").put("goal","Review the same original tools").put("steps",new JSONArray().put(new JSONObject().put("id","read").put("title","Read original evidence").put("detail","Use only the original scope.")));
        JSONObject pending=planning.submitPlan(planning.capture(),plan,"original-plan");
        store.decideExecutionPlanApproval(id,pending.getString("approvalId"),true,run,epoch,pending.getLong("scopeRevision"),pending.getLong("planRevision"),pending.getString("planArtifactRef"),pending.getString("planHash"),()->true);
        TaskStore.ToolCatalogAccess access=open(BUSINESS);assertFalse(access.planning());access.executeRead(args(),original,()->page("before-phase-change"));assertBudget(2,6);
        JSONObject prior=scope();store.configureExecutionScope(id,prior.getLong("revision"),prior.getLong("deadlineAt"),prior.getInt("toolLimit"),prior.getInt("modelCallLimit"));
        assertEquals("planning",scope().getString("executionPhase"));assertBudget(2,6);
        assertThrows(SecurityException.class,()->access.check(original));
        assertThrows(SecurityException.class,()->access.executeRead(args(),original,()->{fail("Old executing catalogue must not read");return page("unreachable");}));
        TaskStore.ToolCatalogAccess replacement=open(BUSINESS);assertTrue(replacement.planning());assertEquals(BUSINESS,replacement.admittedTools());assertBudget(2,6);
    }

    @Test public void changingTheActualSqlGrantInvalidatesTheOldCatalog()throws Exception {
        start(false,8,BUSINESS);TaskStore.ToolCatalogAccess access=open(BUSINESS);
        store.authorizeTools(id,run,new JSONArray().put("memory_search"));assertEquals(Set.of("memory_search"),names(grant().getJSONArray("tools")));
        assertThrows(SecurityException.class,()->access.executeRead(args(),original,()->{fail("Withdrawn SQL tool set must not read");return page("unreachable");}));
        TaskStore.ToolCatalogAccess replacement=open(BUSINESS);assertEquals(Set.of("memory_search"),replacement.admittedTools());assertEquals(0,operations().length());assertBudget(0,8);
    }

    @Test public void nativeSourceAndRequestWithdrawalDenyBeforeAnyReadExpense()throws Exception {
        start(false,8,BUSINESS);AtomicBoolean sourceCurrent=new AtomicBoolean(true),published=new AtomicBoolean(true);
        TaskStore.ToolCatalogAccess first=open(snapshot(BUSINESS,sourceCurrent),published);sourceCurrent.set(false);
        assertThrows(SecurityException.class,()->first.executeRead(args(),original,()->{fail("Reinstalled source must not read");return page("unreachable");}));
        TaskStore.ToolCatalogAccess second=open(snapshot(BUSINESS),published);published.set(false);
        assertThrows(SecurityException.class,()->second.executeRead(args(),original,()->{fail("Unpublished search must not read");return page("unreachable");}));
        assertEquals(0,operations().length());assertBudget(0,8);
    }

    @Test public void exhaustedOriginalBudgetCannotBeResetByOpeningAnotherCatalog()throws Exception {
        start(false,1,BUSINESS);TaskStore.ToolCatalogAccess access=open(BUSINESS);access.executeRead(args(),original,()->page("last-unit"));assertBudget(1,0);
        access.check(original);TaskStore.ToolCatalogAccess replacement=open(BUSINESS);replacement.check(original);
        assertThrows(IllegalStateException.class,()->replacement.executeRead(args(),original,()->{fail("Exhausted budget must stop before the read");return page("unreachable");}));
        assertEquals(1,operations().length());assertEquals("last-unit",onlyOperation().getJSONObject("receipt").getString("nativeProof"));assertBudget(1,0);
    }

    @Test public void pendingPlanningReadPreventsReplacingItsNativeCatalog()throws Exception {
        start(true,8,BUSINESS);TaskStore.ToolCatalogAccess access=open(BUSINESS);ExecutionPlanning planning=store.journal(id,run,epoch,original).planning();
        ExecutionPlanning.ReadClaim claim=planning.beginRead(planning.capture(),SEARCH,args(),"original-pending-search",ExecutionPlanning.Access.READ);
        assertThrows(SecurityException.class,()->store.openToolCatalog(id,run,epoch,original,snapshot(BUSINESS)));
        access.check(original);assertEquals("read_pending",onlyOperation().getString("state"));assertBudget(1,7);
        JSONObject receipt=access.executeRead(args(),original,()->page("same-original-access"));planning.finishRead(claim,receipt,ExecutionPlanning.ReadOutcome.RECEIPT);
        assertEquals("read_receipt",onlyOperation().getString("state"));assertBudget(1,7);
    }

    @Test public void anOriginalPreparedEffectDoesNotInvalidateMetadataButStillBlocksNewDispatch()throws Exception {
        start(false,8,BUSINESS);TaskStore.ToolCatalogAccess access=open(BUSINESS);RunJournal journal=store.journal(id,run,epoch,original);
        ExecutionPlanning planning=journal.planning();ExecutionPlanning.Snapshot turn=planning.capture();
        String operation=journal.beginOperation("workspace_write",new JSONObject().put("path","pending.txt").put("content","Original prepared intent; no effect dispatched"));
        assertEquals(operation,onlyOperation().getString("operationId"));assertEquals("prepared",onlyOperation().getString("state"));assertTrue(store.hasUnresolved(id));assertBudget(1,7);
        // The Runner checks this same pin after beginOperation. Metadata is not a second dispatch.
        planning.requireCurrent(turn);access.check(original);
        assertThrows(IllegalStateException.class,()->access.executeRead(args(),original,()->{fail("An unrelated prepared effect blocks a new read");return page("unreachable");}));
        assertThrows(IllegalStateException.class,()->planning.requestPhase(turn,"planning","Review the unresolved original effect first","pending-phase-request"));
        assertThrows(IllegalStateException.class,()->journal.beginOperation("workspace_write",new JSONObject().put("path","second.txt")));
        assertEquals(operation,onlyOperation().getString("operationId"));assertEquals("prepared",onlyOperation().getString("state"));assertBudget(1,7);
    }

    @Test public void ownerScopeRevisionDoesNotLetPlanningReadOrProposePastAnUnresolvedEffect()throws Exception {
        start(true,8,BUSINESS);RunJournal journal=store.journal(id,run,epoch,original);ExecutionPlanning planning=journal.planning();
        JSONObject plan=new JSONObject().put("format","pocketworld.execution-plan.v1").put("goal","Use the original reviewed scope").put("steps",new JSONArray().put(new JSONObject().put("id","write").put("title","Review one original change").put("detail","Preserve any unresolved original operation.")));
        JSONObject pending=planning.submitPlan(planning.capture(),plan,"pending-effect-plan");
        store.decideExecutionPlanApproval(id,pending.getString("approvalId"),true,run,epoch,pending.getLong("scopeRevision"),pending.getLong("planRevision"),pending.getString("planArtifactRef"),pending.getString("planHash"),()->true);
        String operation=journal.beginOperation("workspace_write",new JSONObject().put("path","pending.txt").put("content","Original prepared intent; no effect dispatched"));
        assertTrue(store.hasUnresolved(id));assertBudget(2,6);
        // This is the real owner scope CAS, not a forged SQL phase or a newly minted grant.
        JSONObject prior=scope();store.configureExecutionScope(id,prior.getLong("revision"),prior.getLong("deadlineAt"),prior.getInt("toolLimit"),prior.getInt("modelCallLimit"));
        TaskStore.ToolCatalogAccess access=open(BUSINESS);ExecutionPlanning.Snapshot turn=planning.capture();assertTrue(turn.planning());
        planning.requireCurrent(turn);access.check(original);
        assertThrows(IllegalStateException.class,()->planning.beginRead(turn,SEARCH,args(),"pending-effect-search",ExecutionPlanning.Access.READ));
        assertThrows(IllegalStateException.class,()->planning.beginRead(turn,"memory_search",new JSONObject().put("query","original evidence"),"pending-effect-read",ExecutionPlanning.Access.READ));
        assertThrows(IllegalStateException.class,()->planning.submitPlan(turn,plan,"pending-effect-resubmit"));
        assertThrows(IllegalStateException.class,()->planning.requestPhase(turn,"executing","The original effect still needs its actual receipt","pending-effect-request"));
        assertEquals(2,operations().length());boolean originalPending=false;
        for(int index=0;index<operations().length();index++){JSONObject row=operations().getJSONObject(index);if(operation.equals(row.getString("operationId"))){assertEquals("prepared",row.getString("state"));originalPending=true;}}
        assertTrue(originalPending);assertBudget(2,6);
    }

    @Test public void anInterruptedCatalogReadKeepsItsOriginalUnconfirmedExpense()throws Exception {
        start(false,2,BUSINESS);TaskStore.ToolCatalogAccess access=open(BUSINESS);AtomicInteger reads=new AtomicInteger();
        assertThrows(IOException.class,()->access.executeRead(args(),original,()->{reads.incrementAndGet();throw new IOException("Original metadata read interrupted");}));
        assertEquals(1,reads.get());assertEquals("read_unconfirmed",onlyOperation().getString("state"));assertBudget(1,1);
        JSONObject receipt=onlyOperation().getJSONObject("receipt");assertTrue(receipt.getBoolean("catalogRead"));assertFalse(receipt.getBoolean("planningRead"));assertFalse(receipt.getBoolean("replayed"));
        access.close();assertThrows(SecurityException.class,()->access.executeRead(args(),original,()->{reads.incrementAndGet();return page("must-not-replay");}));assertEquals(1,reads.get());assertBudget(1,1);
    }

    @Test public void closingDuringARealReadKeepsItsReceiptButCannotPublishOrReadAgain()throws Exception {
        start(false,2,BUSINESS);CapabilitySnapshot nativeSnapshot=snapshot(BUSINESS);TaskStore.ToolCatalogAccess access=open(nativeSnapshot,new AtomicBoolean(true));AtomicInteger reads=new AtomicInteger();
        assertThrows(SecurityException.class,()->access.executeRead(args(),original,()->{reads.incrementAndGet();access.close();return page("real-late-receipt");}));
        JSONObject receipt=onlyOperation().getJSONObject("receipt");assertEquals("read_receipt",onlyOperation().getString("state"));assertEquals("real-late-receipt",receipt.getString("nativeProof"));assertBudget(1,1);
        assertThrows(SecurityException.class,nativeSnapshot::check);
        assertThrows(SecurityException.class,()->access.executeRead(args(),original,()->{reads.incrementAndGet();return page("must-not-replay");}));assertEquals(1,reads.get());assertBudget(1,1);
    }

    @Test public void aRealBusinessNameConflictKeepsTheOriginalFullSurface()throws Exception {
        Set<String> conflicting=Set.of("memory_search",SEARCH);start(false,8,conflicting);CapabilitySnapshot nativeSnapshot=snapshot(conflicting);
        assertNull(store.openToolCatalog(id,run,epoch,original,nativeSnapshot));assertEquals(conflicting,names(grant().getJSONArray("tools")));
        assertThrows(SecurityException.class,()->nativeSnapshot.catalogTools(id,run,epoch,original));assertEquals(0,operations().length());assertBudget(0,8);
    }
}
