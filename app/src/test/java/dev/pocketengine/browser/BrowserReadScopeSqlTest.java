package dev.pocketengine.browser;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual TaskStore/SQLite/prepared journal/native approval bridge. Page/body interfaces are explicit fixtures, not Android/provider/network passes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
public final class BrowserReadScopeSqlTest {
    static JSONObject j(Object... pairs)throws Exception{return PublicHttpReaderTest.obj(pairs);}
    static final class F implements AutoCloseable {
        final BrowserTaskPersistenceTest.Fixture f=new BrowserTaskPersistenceTest.Fixture(true);
        final BrowserTaskSpaceTest.Queue ui=new BrowserTaskSpaceTest.Queue();final BrowserTaskSpaceTest.Driver driver=new BrowserTaskSpaceTest.Driver();
        final BrowserTaskSourcePort sources;final BrowserTaskApprovalPort approvals;final BrowserTaskSpace space;final BrowserTaskCapabilityPort port;final String tab;
        final AtomicInteger callbacks=new AtomicInteger();
        F()throws Exception {
            f.tasks.authorizeTools(f.binding.taskId,f.binding.runId,BrowserTaskCapabilityPort.taskToolNames(true));
            ModelCapabilityAdapters.ResourceCheck resource=()->{assertFalse("native resource callback under TaskStore lock",Thread.holdsLock(f.tasks));if(!f.resource.get())throw new SecurityException("original reservation revoked");};
            sources=new BrowserTaskSourcePort(f.tasks,f.binding,f.control,f.authority,resource,f.events::add);approvals=new BrowserTaskApprovalPort(f.tasks,f.binding,f.control,f.authority,resource,f.events::add);
            space=ui.call(()->new BrowserTaskSpace(f.binding,f.control,BrowserProfileCapabilityBindingTest.namedSession(),driver,ui,sources,null));port=new BrowserTaskCapabilityPort(space,f.authority,resource,approvals,ui,null);
            // Native explicit owner adoption seeds the page-interface fixture. This is not a model-open or real WebView claim.
            tab=driver.ownerId;ui.call(()->{space.adoptOwnerNative(tab,()->{f.control.check();f.tasks.checkCapabilityCurrent(f.binding.taskId,f.binding.runId,f.binding.epoch,"browser_task_open");});return null;});
        }
        void guard(){f.control.setAuthorityCheck(()->{callbacks.incrementAndGet();assertFalse("control authority callback under TaskStore lock",Thread.holdsLock(f.tasks));});}
        void unguard(){f.control.setAuthorityCheck(()->{});}
        JSONObject identity()throws Exception{return ui.call(()->space.identity().put("pauseRevision",f.control.pauseRevision()));}
        JSONObject pin(String tool,JSONObject args)throws Exception{return ui.call(()->tool.equals(PublicHttpReader.TOOL)?space.publicHttpPin(args):space.pin(args));}
        JSONObject grant(boolean save,String seeds)throws Exception{return f.tasks.grantBrowserReadScope(f.binding.taskId,f.binding.runId,f.binding.epoch,f.control,identity(),BrowserReadScope.forOwner(true,save,seeds));}
        JSONObject state()throws Exception{return f.tasks.browserReadScopeForOwner(f.binding.taskId,f.binding.runId,f.binding.epoch,f.control.pauseRevision());}
        String prepare(String tool,JSONObject args)throws Exception {unguard();return f.journal.beginOperation(tool,args);}
        JSONObject invoke(String command,JSONObject args)throws Exception {String tool="browser_task_"+command,op=prepare(tool,args);guard();JSONObject receipt=port.execute(tool,new JSONObject(args.toString()).put("_hostOperationId",op),f.control);unguard();f.journal.finishOperation(op,receipt);return receipt;}
        JSONObject admit(String op,String tool,JSONObject args)throws Exception{return approvals.admitReadScope(f.binding,f.control,op,tool,args,pin(tool,args));}
        void finish(String op)throws Exception {unguard();f.journal.finishOperation(op,j("accepted",false,"verified",false,"status","fixture_no_dispatch"));}
        public void close()throws Exception {unguard();approvals.close();try{ui.call(()->{space.endNative("fixture_end");return null;});}finally{f.close();ui.close();}}
    }
    @Test public void threeRealPortObservationsNeedNoPerReadApprovalButEachHasItsOriginalJournalAndNativePermit()throws Exception {try(F x=new F()){
        String task=x.f.binding.taskId,run=x.f.binding.runId;int tasks=x.f.tasks.list().length();JSONObject scope=x.grant(false,"");assertEquals(32,scope.getInt("remainingReads"));
        for(int i=0;i<3;i++){JSONObject observed=x.invoke("observe",j("tabId",x.tab));assertEquals(observed.toString(),"observed",observed.getString("status"));assertEquals("native_read_scope",observed.getJSONObject("readAuthorization").getString("mode"));assertFalse(observed.has("approvalId"));assertFalse(observed.getBoolean("verified"));}
        JSONObject stored=x.f.tasks.get(task,false);assertEquals(run,stored.getString("runId"));assertEquals(tasks,x.f.tasks.list().length());assertEquals(3,stored.getJSONArray("operations").length());assertEquals(0,stored.optJSONArray("approvals")==null?0:stored.getJSONArray("approvals").length());assertEquals(29,x.state().getInt("remainingReads"));assertTrue(x.callbacks.get()>0);
    }}
    @Test public void scopeHasNoDefaultAndCannotGrantWithoutAnOriginalPreparedOperation()throws Exception {try(F x=new F()){
        assertEquals("not_granted",x.state().getString("state"));assertThrows(SecurityException.class,()->x.f.tasks.grantBrowserReadScope(x.f.binding.taskId,x.f.binding.runId,x.f.binding.epoch,x.f.control,x.identity(),BrowserReadScope.disabled()));x.grant(false,"");JSONObject args=j("tabId",x.tab);assertThrows(SecurityException.class,()->x.admit(UUID.randomUUID().toString(),"browser_task_observe",args));assertEquals(32,x.state().getInt("remainingReads"));
    }}
    @Test public void onePreparedOperationCanConsumeTheScopeOnlyOnceAndNoLockCallsBackIntoAuthority()throws Exception {try(F x=new F()){
        x.guard();x.grant(false,"");assertEquals(0,x.callbacks.get());JSONObject args=j("tabId",x.tab);String op=x.prepare("browser_task_observe",args);x.guard();JSONObject permit=x.admit(op,"browser_task_observe",args);assertNotNull(permit);assertThrows(SecurityException.class,()->x.admit(op,"browser_task_observe",args));assertEquals(31,x.state().getInt("remainingReads"));x.f.tasks.checkBrowserReadScopePermit(x.f.binding.taskId,x.f.binding.runId,x.f.binding.epoch,x.f.control,permit);x.finish(op);
    }}
    @Test public void readCountIsReallyBoundedAndAlreadyAdmittedLastReadRemainsCheckable()throws Exception {try(F x=new F()){
        x.grant(false,"");JSONObject args=j("tabId",x.tab);for(int i=0;i<32;i++){String op=x.prepare("browser_task_observe",args);JSONObject permit=x.admit(op,"browser_task_observe",args);assertNotNull(permit);x.f.tasks.checkBrowserReadScopePermit(x.f.binding.taskId,x.f.binding.runId,x.f.binding.epoch,x.f.control,permit);x.finish(op);}
        assertEquals("exhausted",x.state().getString("state"));String over=x.prepare("browser_task_observe",args);assertNull(x.admit(over,"browser_task_observe",args));assertEquals(0,x.state().getInt("remainingReads"));x.finish(over);
    }}
    @Test public void aggregatePublicBytesReserveTheRequestedCeilingAndCannotBeRefundedByRepeatingAQuery()throws Exception {try(F x=new F()){
        x.grant(true,"https://public.example/article?edition=2026");JSONObject args=PublicHttpReaderTest.args().put("maxBytes",524288);for(int i=0;i<2;i++){String op=x.prepare(PublicHttpReader.TOOL,args);JSONObject permit=x.admit(op,PublicHttpReader.TOOL,args);assertEquals(524288,permit.getLong("reservedBodyBytes"));x.finish(op);}assertEquals(0,x.state().getLong("remainingPublicBytes"));assertEquals(30,x.state().getInt("remainingReads"));String over=x.prepare(PublicHttpReader.TOOL,args);assertNull(x.admit(over,PublicHttpReader.TOOL,args));x.finish(over);
    }}
    @Test public void modelChangedUrlOrRedirectOriginDoesNotGetAnAutomaticPermit()throws Exception {try(F x=new F()){
        x.grant(true,"https://public.example/article?edition=2026");for(JSONObject args:new JSONObject[]{PublicHttpReaderTest.args().put("url","https://public.example/article?private_context=secret"),PublicHttpReaderTest.args().put("allowedRedirectOrigins",new JSONArray().put("https://unapproved.example"))}){String op=x.prepare(PublicHttpReader.TOOL,args);assertNull(x.admit(op,PublicHttpReader.TOOL,args));x.finish(op);}assertEquals(32,x.state().getInt("remainingReads"));
    }}
    @Test public void revokeAndRegrantCreateFreshIdentityAndRevisionWithoutRevivingOldPermits()throws Exception {try(F x=new F()){
        JSONObject first=x.grant(false,"");JSONObject args=j("tabId",x.tab);String op=x.prepare("browser_task_observe",args);JSONObject permit=x.admit(op,"browser_task_observe",args);x.f.tasks.revokeBrowserReadScope(x.f.binding.taskId,x.f.binding.runId,x.f.binding.epoch,first.getString("scopeId"),"owner_revoked");assertThrows(SecurityException.class,()->x.f.tasks.checkBrowserReadScopePermit(x.f.binding.taskId,x.f.binding.runId,x.f.binding.epoch,x.f.control,permit));assertThrows(SecurityException.class,()->x.admit(op,"browser_task_observe",args));x.finish(op);
        JSONObject second=x.grant(false,"");assertNotEquals(first.getString("scopeId"),second.getString("scopeId"));assertEquals(first.getLong("revision")+1,second.getLong("revision"));assertThrows(SecurityException.class,()->x.f.tasks.revokeBrowserReadScope(x.f.binding.taskId,x.f.binding.runId,x.f.binding.epoch,first.getString("scopeId"),"stale_owner_ui"));assertEquals("active",x.state().getString("state"));
    }}
    @Test public void pauseResumeAndSessionChangesCannotRestoreAnOldGrant()throws Exception {try(F x=new F()){
        x.grant(false,"");JSONObject args=j("tabId",x.tab);String op=x.prepare("browser_task_observe",args);JSONObject permit=x.admit(op,"browser_task_observe",args);x.f.control.pause();x.f.control.resume();assertThrows(SecurityException.class,()->x.f.tasks.checkBrowserReadScopePermit(x.f.binding.taskId,x.f.binding.runId,x.f.binding.epoch,x.f.control,permit));x.finish(op);assertEquals("revoked",x.state().getString("state"));
        x.grant(false,"");String another=x.prepare("browser_task_observe",args);JSONObject changed=x.pin("browser_task_observe",args).put("browserSessionId",UUID.randomUUID().toString());assertNull(x.f.tasks.admitBrowserReadScope(x.f.binding.taskId,x.f.binding.runId,x.f.binding.epoch,x.f.control,another,"browser_task_observe",args,changed));assertEquals("revoked",x.state().getString("state"));x.finish(another);
    }}
    @Test public void expiryAndMissingUnderlyingToolGrantFailClosedWithoutAnotherPermissionLibrary()throws Exception {try(F x=new F()){
        x.grant(false,"");Field field=dev.pocketengine.store.TaskStore.class.getDeclaredField("db");field.setAccessible(true);android.database.sqlite.SQLiteDatabase db=(android.database.sqlite.SQLiteDatabase)field.get(x.f.tasks);
        try(android.database.Cursor c=db.rawQuery("SELECT grant_json FROM runs WHERE run_id=?",new String[]{x.f.binding.runId})){assertTrue(c.moveToFirst());JSONObject grant=new JSONObject(c.getString(0));grant.getJSONObject("browserReadScope").put("expiresAt",System.currentTimeMillis()-1);android.content.ContentValues value=new android.content.ContentValues();value.put("grant_json",grant.toString());db.update("runs",value,"run_id=?",new String[]{x.f.binding.runId});}
        JSONObject args=j("tabId",x.tab);String op=x.prepare("browser_task_observe",args);assertNull(x.admit(op,"browser_task_observe",args));x.finish(op);x.grant(false,"");x.f.tasks.authorizeTools(x.f.binding.taskId,x.f.binding.runId,new JSONArray().put("browser_task_open"));assertThrows(SecurityException.class,()->x.f.journal.beginOperation("browser_task_observe",args));
    }}
    @Test public void clickStillNeedsTheRealExactApprovalAndSourceRetentionHasItsOwnExplicitConsent()throws Exception {try(F x=new F()){
        x.grant(false,"");JSONObject observation=x.invoke("observe",j("tabId",x.tab));JSONObject source=j("tabId",x.tab,"observationId",observation.getString("observationId"));String sourceOp=x.prepare("browser_task_retain_source",source);assertNull(x.admit(sourceOp,"browser_task_retain_source",source));x.finish(sourceOp);
        JSONObject args=j("tabId",x.tab,"observationId",observation.getString("observationId"),"ref","n0");String op=x.prepare("browser_task_click",args);var worker=Executors.newSingleThreadExecutor();try{var future=worker.submit(()->x.port.execute("browser_task_click",new JSONObject(args.toString()).put("_hostOperationId",op),x.f.control));JSONObject event=null;long until=System.nanoTime()+2_000_000_000L;while(System.nanoTime()<until){JSONObject seen=x.f.events.poll(50,TimeUnit.MILLISECONDS);if(seen!=null&&"capability_approval".equals(seen.optString("type"))){event=seen;break;}}assertNotNull(event);assertFalse(future.isDone());assertEquals(0,x.driver.mutableDispatches);x.f.tasks.decideCapabilityApproval(x.f.binding.taskId,event.getString("approvalId"),false);x.approvals.nativeDecisionChanged(x.f.binding.taskId,event.getString("approvalId"),x.f.control);JSONObject denied=future.get(2,TimeUnit.SECONDS);assertFalse(denied.getBoolean("accepted"));assertFalse(denied.has("readAuthorization"));x.f.journal.finishOperation(op,denied);}finally{worker.shutdownNow();worker.awaitTermination(2,TimeUnit.SECONDS);}assertEquals(31,x.state().getInt("remainingReads"));
    }}
    @Test public void explicitlyAllowedSourceSaveUsesTheSameRealSourcePortAndPersistsOriginalIdentity()throws Exception {try(F x=new F()){
        x.grant(true,"");JSONObject observation=x.invoke("observe",j("tabId",x.tab));JSONObject receipt=x.invoke("retain_source",j("tabId",x.tab,"observationId",observation.getString("observationId"),"excerpt","Alpha evidence"));assertTrue(receipt.toString(),receipt.getBoolean("sourceRetained"));assertEquals("native_read_scope",receipt.getJSONObject("readAuthorization").getString("mode"));assertEquals(30,x.state().getInt("remainingReads"));assertEquals(1,BrowserTaskSourcePort.sourcesForOwner(x.f.tasks,x.f.binding.taskId).length());assertEquals("retained",x.sources.lookup(x.f.binding,receipt.getString("sourceId")).getString("status"));
    }}
    @Test public void publicSourceCommitRechecksRevocationAndAPermitCannotAddAnotherWritePermission()throws Exception {try(F x=new F()){
        JSONObject scope=x.grant(true,"https://public.example/article?edition=2026"),args=PublicHttpReaderTest.args();String op=x.prepare(PublicHttpReader.TOOL,args);JSONObject permit=x.admit(op,PublicHttpReader.TOOL,args);PublicHttpReader.Result read=BrowserPublicHttpPersistenceTest.fetched(args,x.f.control);JSONObject source=BrowserPublicHttpPersistenceTest.source(x.f,read);
        x.f.tasks.revokeBrowserReadScope(x.f.binding.taskId,x.f.binding.runId,x.f.binding.epoch,scope.getString("scopeId"),"owner_revoked_before_commit");assertThrows(SecurityException.class,()->x.sources.retainPublic(x.f.binding,x.f.control,op,source.getString("sourceId"),source,read.budget));assertEquals(0,BrowserTaskSourcePort.sourcesForOwner(x.f.tasks,x.f.binding.taskId).length());assertThrows(SecurityException.class,()->x.f.tasks.checkBrowserReadScopeOperationIfPresent(x.f.binding.taskId,x.f.binding.runId,x.f.binding.epoch,x.f.control,op,"browser_task_retain_source"));x.finish(op);
    }}
}
