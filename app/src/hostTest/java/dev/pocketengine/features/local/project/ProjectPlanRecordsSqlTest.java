package dev.pocketengine.features.local.project;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.*;
import dev.pocketengine.features.capabilities.*;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.local.runtime.ManagedProjectRuntime;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class ProjectPlanRecordsSqlTest {
    private ProjectManagedRunnerJourneyTest factory;private LocalModule local;private TaskStore tasks;private RunControl control;private ProjectCapabilityPort port;private String id,run,checkout,project;private int epoch;
    @SuppressWarnings("unchecked") private <T>T field(String name)throws Exception{Field f=ProjectManagedRunnerJourneyTest.class.getDeclaredField(name);f.setAccessible(true);return (T)f.get(factory);}
    private Object call(String name,Class<?>[] types,Object... values)throws Exception{Method m=ProjectManagedRunnerJourneyTest.class.getDeclaredMethod(name,types);m.setAccessible(true);try{return m.invoke(factory,values);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception)throw (Exception)e.getCause();throw (Error)e.getCause();}}
    private SQLiteDatabase db()throws Exception{Field f=TaskStore.class.getDeclaredField("db");f.setAccessible(true);return (SQLiteDatabase)f.get(tasks);}
    @Before public void create()throws Exception{factory=new ProjectManagedRunnerJourneyTest();factory.create();local=field("local");tasks=field("tasks");call("selectedProject",new Class<?>[]{String.class},"Original immutable project plan SQL");call("file",new Class<?>[]{String.class,String.class},"main.mjs","console.log('original exact source');\n");call("startTask",new Class<?>[]{String.class},"Original SQL proposal retention and authority");id=field("taskId");checkout=field("checkout");project=field("project");attempt();}
    private void attempt()throws Exception{JSONObject task=(JSONObject)call("startFixtureAttempt",new Class<?>[0]);run=task.getString("runId");epoch=task.getInt("coordinatorEpoch");control=((AtomicReference<RunControl>)field("live")).get();var binding=new ModelCapabilityAdapters.Binding(id,run,epoch,control,true,project);port=new ProjectCapabilityPort(binding,new TaskStoreCapabilityAuthority(tasks,binding,event->{}),control,null,local,tasks,checkout,ManagedProjectRuntime.RUNTIME_ID);JSONArray names=new JSONArray(),tools=port.tools();for(int i=0;i<tools.length();i++)names.put(tools.getJSONObject(i).getJSONObject("function").getString("name"));tasks.authorizeTools(id,run,names);}
    @After public void close()throws Exception{try{if(port!=null)port.close();}finally{factory.close();}}
    private JSONObject plan(String intent)throws Exception{JSONObject inspect=port.execute("project_inspect",new JSONObject(),control);return port.execute("project_plan",new JSONObject().put("intent",intent).put("ecosystem","node").put("expectedSourceDigest",inspect.getString("sourceDigest")),control);}
    private JSONObject args(JSONObject plan)throws Exception{return new JSONObject().put("planRef",plan.getString("planRef")).put("planDigest",plan.getString("planDigest")).put("stepId",plan.getJSONArray("steps").getJSONObject(0).getString("id"));}
    private String requestApproval(JSONObject args)throws Exception{RunJournal journal=tasks.journal(id,run,epoch,control);String operation=journal.beginOperation("project_execute",args);JSONObject receipt=port.execute("project_execute",new JSONObject(args.toString()).put("_hostOperationId",operation),control);assertEquals("approval_required",receipt.getString("code"));assertEquals("not_dispatched",receipt.getString("status"));assertFalse(receipt.getBoolean("accepted"));journal.finishOperation(operation,receipt);tasks.endAttempt(id,run,"awaiting_user","owner decision fixture",new JSONObject());JSONArray approvals=tasks.get(id,false).getJSONArray("approvals");tasks.decideCapabilityApproval(id,approvals.getJSONObject(approvals.length()-1).getString("id"),true);return operation;}
    @Test public void immutableBodyPinsOriginalUsedProposalAndExpiresOnlyUnusedProposals()throws Exception{
        JSONObject first=plan("run"),second=plan("test"),firstArgs=args(first);String op=tasks.journal(id,run,epoch,control).beginOperation("project_execute",firstArgs);assertTrue(tasks.projectArguments(id,run,epoch,firstArgs).has("plan"));assertThrows(SecurityException.class,()->tasks.projectArguments(id,run,epoch+1,firstArgs));
        db().execSQL("UPDATE project_plans SET created_at=?",new Object[]{System.currentTimeMillis()-8L*24*60*60*1000});ProjectPlanRecords.prune(db(),System.currentTimeMillis());assertTrue(tasks.projectArguments(id,run,epoch,firstArgs).has("plan"));assertThrows(SecurityException.class,()->tasks.projectArguments(id,run,epoch,args(second)));
        try(var row=db().rawQuery("SELECT plan_ref FROM project_plan_uses WHERE operation_id=?",new String[]{op})){assertTrue(row.moveToFirst());assertEquals(first.getString("planRef"),row.getString(0));}
        db().execSQL("UPDATE project_plans SET body=body||' ' WHERE plan_ref=?",new Object[]{first.getString("planRef")});assertThrows(SecurityException.class,()->tasks.projectArguments(id,run,epoch,firstArgs));
    }
    @Test public void noUnknownIsNotProofAndAmbiguousOldReceiptCannotTransferApproval()throws Exception{
        JSONObject reference=plan("run"),old=args(reference);String operation=requestApproval(old);db().execSQL("UPDATE operations SET receipt=? WHERE operation_id=?",new Object[]{new JSONObject().put("code","legacy_unspecified").put("status","not_dispatched").put("accepted",false).toString(),operation});assertFalse(tasks.hasUnresolved(id));port.close();attempt();
        assertEquals(0,tasks.approvedProjectPlanProposals(id,run,epoch,control).length());assertThrows(SecurityException.class,()->port.validate("project_execute",old));JSONArray approvals=tasks.get(id,false).getJSONArray("approvals");assertEquals("invalidated",approvals.getJSONObject(0).getString("state"));assertEquals("project_plan_original_may_have_dispatched",approvals.getJSONObject(0).getString("reason"));
    }
    @Test public void originalApprovedProposalStillCannotExecuteChangedActualSourceInNewRun()throws Exception{
        JSONObject reference=plan("run"),old=args(reference);requestApproval(old);JSONObject before=local.handle("readFile",new JSONObject().put("checkoutId",checkout).put("path","main.mjs"));local.handle("writeFile",new JSONObject().put("checkoutId",checkout).put("path","main.mjs").put("expectedHash",before.getString("sha256")).put("content","console.log('owner later source');\n"));port.close();attempt();JSONArray approved=tasks.approvedProjectPlanProposals(id,run,epoch,control);assertEquals(1,approved.length());JSONObject proposal=approved.getJSONObject(0);JSONObject current=new JSONObject().put("planRef",proposal.getString("planRef")).put("planDigest",proposal.getString("planDigest")).put("stepId",proposal.getString("stepId"));int operations=tasks.get(id,false).getJSONArray("operations").length();assertThrows(SecurityException.class,()->port.validate("project_execute",current));assertEquals(operations,tasks.get(id,false).getJSONArray("operations").length());assertFalse(tasks.hasUnresolved(id));assertThrows(SecurityException.class,()->port.validate("project_execute",old));
    }
    @Test public void originalVersionThreeDatabaseMigratesPlansWithoutDeletingNativeTaskOrAudit()throws Exception{
        Context android=field("android");port.close();port=null;db().execSQL("INSERT INTO meta(key,value) VALUES('s01_migration_canary','original native row')");db().execSQL("DROP TABLE project_plan_uses");db().execSQL("DROP TABLE project_plans");db().setVersion(3);db().close();tasks=new TaskStore(android);Field f=ProjectManagedRunnerJourneyTest.class.getDeclaredField("tasks");f.setAccessible(true);f.set(factory,tasks);assertNotNull(tasks.get(id,false));assertEquals(4,db().getVersion());try(var row=db().rawQuery("SELECT value FROM meta WHERE key='s01_migration_canary'",null)){assertTrue(row.moveToFirst());assertEquals("original native row",row.getString(0));}try(var row=db().rawQuery("SELECT count(*) FROM project_plans",null)){assertTrue(row.moveToFirst());assertEquals(0,row.getInt(0));}
    }
}
