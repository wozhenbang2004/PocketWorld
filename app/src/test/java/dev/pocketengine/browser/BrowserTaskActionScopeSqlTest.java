package dev.pocketengine.browser;
import static org.junit.Assert.*;
import android.app.Application;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import dev.pocketengine.store.*;
import dev.pocketengine.core.*;
import java.util.*;
/** Original SQLite grant/prepared journal transaction. Explicit native pin fixture, not WebView device evidence. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=35,manifest=Config.NONE,application=Application.class) @SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class BrowserTaskActionScopeSqlTest {
 static JSONObject j(Object...p)throws Exception{return BrowserReadScopeTest.j(p);}
 static final class F implements AutoCloseable {
  final TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());final RunControl control=new RunControl();final String id,run;final int epoch;final RunJournal journal;final JSONObject identity;
  F()throws Exception{id=tasks.createCapabilityTask("明确站点连续浏览","").getString("id");tasks.configureExecutionScope(id,0,0,20,28);JSONObject task=tasks.startAttempt(id,"capabilities",20);run=task.getString("runId");epoch=task.getInt("coordinatorEpoch");control.bindHostRun(id,run,epoch);tasks.authorizeTools(id,run,BrowserTaskCapabilityPort.taskToolNames(true));tasks.status(id,"running","native fixture");journal=tasks.journal(id,run,epoch,control);identity=BrowserProfileCapabilityBindingTest.namedSession().identity().put("taskId",id).put("runId",run).put("epoch",epoch).put("browserSpaceId",UUID.randomUUID().toString()).put("pauseRevision",control.pauseRevision());}
  JSONObject scope()throws Exception{return tasks.get(id,false).getJSONObject("executionScope");}
  JSONObject grant(int max)throws Exception{return tasks.grantBrowserActions(id,run,epoch,control,identity,0,scope().getLong("revision"),BrowserActionScope.forOwner(true,"https://example.com",max,true));}
  JSONObject admit(String op,String tool,JSONObject args)throws Exception{return tasks.admitBrowserActionScope(id,run,epoch,control,op,tool,args,identity);}
  void finish(String op)throws Exception{journal.finishOperation(op,j("ok",true,"accepted",true));}
  public void close()throws Exception{control.cancel();BrowserTaskPersistenceTest.closeDatabase(tasks);}
 }
 @Test public void originalPreparedScopeConsumesBothBudgetsOnceAndLastSlotStillReadsBack()throws Exception {try(F f=new F()){f.grant(1);JSONObject args=j("url","https://example.com/page");String op=f.journal.beginOperation("browser_task_open",args);JSONObject permit=f.admit(op,"browser_task_open",args);assertNotNull(permit);assertEquals(1,f.scope().getInt("toolsUsed"));assertEquals(0,permit.getInt("remainingActions"));f.tasks.checkBrowserActionPermit(f.id,f.run,f.epoch,f.control,permit);assertThrows(SecurityException.class,()->f.admit(op,"browser_task_open",args));f.finish(op);String second=f.journal.beginOperation("browser_task_open",args);assertNull(f.admit(second,"browser_task_open",args));f.finish(second);}}
 @Test public void undeclaredOriginAndRawScriptNeverUseTheAutomaticBudget()throws Exception {try(F f=new F()){f.grant(3);JSONObject args=j("url","https://other.example/page");String op=f.journal.beginOperation("browser_task_open",args);assertNull(f.admit(op,"browser_task_open",args));f.finish(op);JSONObject script=j("tabId",UUID.randomUUID().toString(),"source","fetch('/send')","effectReview","发送");op=f.journal.beginOperation("browser_task_script",script);assertNull(f.admit(op,"browser_task_script",script));f.finish(op);assertEquals(3,f.tasks.browserActionsForOwner(f.id,f.run,f.epoch,f.control).getInt("remainingActions"));}}
 @Test public void pauseAbaAndTaskRenewalCannotReviveTheBrowserPermit()throws Exception {try(F f=new F()){f.grant(3);JSONObject args=j("url","https://example.com/page");String op=f.journal.beginOperation("browser_task_open",args);JSONObject permit=f.admit(op,"browser_task_open",args);f.control.pause();f.control.resume();assertThrows(SecurityException.class,()->f.tasks.checkBrowserActionPermit(f.id,f.run,f.epoch,f.control,permit));f.finish(op);assertEquals("revoked",f.tasks.browserActionsForOwner(f.id,f.run,f.epoch,f.control).getString("state"));}}
 @Test public void staleScopeReviewCannotAttachToARenewedTask()throws Exception {try(F f=new F()){long old=f.scope().getLong("revision");f.tasks.configureExecutionScope(f.id,old,0,20,28);assertThrows(SecurityException.class,()->f.tasks.grantBrowserActions(f.id,f.run,f.epoch,f.control,f.identity,0,old,BrowserActionScope.forOwner(true,"https://example.com",3,false)));}}
 @Test public void changingScopeCannotBlessAnUnknownOrAlreadyApprovedOperation()throws Exception {try(F f=new F()){f.grant(3);JSONObject args=j("url","https://example.com/page");String op=f.journal.beginOperation("browser_task_open",args);JSONObject requested=f.tasks.requireCapabilityApproval(f.id,f.run,"browser_task_open",args,f.identity),waiting=j("approvalId",requested.getString("approvalId"),"operationId",op);f.tasks.decideCapabilityApproval(f.id,requested.getString("approvalId"),true);assertThrows(SecurityException.class,()->f.tasks.grantBrowserActions(f.id,f.run,f.epoch,f.control,f.identity,1,f.scope().getLong("revision"),BrowserActionScope.forOwner(true,"https://example.com",5,false),waiting));f.journal.finishOperation(op,j("status","unknown","accepted",JSONObject.NULL));assertThrows(SecurityException.class,()->f.tasks.grantBrowserActions(f.id,f.run,f.epoch,f.control,f.identity,1,f.scope().getLong("revision"),BrowserActionScope.forOwner(true,"https://example.com",5,false),waiting));}}
}
