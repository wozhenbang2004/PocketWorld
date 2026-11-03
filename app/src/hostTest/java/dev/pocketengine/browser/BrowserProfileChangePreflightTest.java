package dev.pocketengine.browser;

import dev.pocketengine.EngineApplication;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Original Host/Registry/SQLite entry; provider support is only a metadata fixture, not device proof. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class,shadows=BrowserTaskHostAdmissionTest.NoUnrelatedStartup.class)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class BrowserProfileChangePreflightTest {
    interface Change {JSONObject run()throws Exception;}
    private static Object read(Object target,String name)throws Exception {Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    @SuppressWarnings("unchecked") private static Map<String,Object> plans(BrowserTaskHostAdmissionTest.F f)throws Exception {return (Map<String,Object>)read(f.host,"plans");}
    @SuppressWarnings("unchecked") private static Map<String,Object> active(BrowserTaskHostAdmissionTest.F f)throws Exception {return (Map<String,Object>)read(f.host,"active");}
    private static Object admissionScope(BrowserTaskHostAdmissionTest.F f,Object plan,RunControl control)throws Exception {
        Class<?> type=Class.forName(BrowserTaskHost.class.getName()+"$Scope");Constructor<?> constructor=type.getDeclaredConstructor(plan.getClass(),ModelCapabilityAdapters.Binding.class,RunControl.class);constructor.setAccessible(true);
        return constructor.newInstance(plan,new ModelCapabilityAdapters.Binding(f.task,f.run,f.epoch,control,true,""),control);
    }
    private static BrowserProfileRegistry registry(BrowserTaskHostAdmissionTest.F f)throws Exception {return (BrowserProfileRegistry)read(BrowserProfiles.get(f.app),"registry");}
    private static Throwable reject(BrowserTaskHostAdmissionTest.F f,Change change)throws Exception {Future<JSONObject> pending=f.workers.submit(change::run);return assertThrows(ExecutionException.class,()->f.pump(pending)).getCause();}
    private static void admit(BrowserTaskHostAdmissionTest.F f)throws Exception {assertEquals("pending_original_turn",f.pump(f.consent()).getString("status"));}
    private static void unchanged(BrowserTaskHostAdmissionTest.F f,Object plan,JSONObject profile,long revision)throws Exception {
        assertFalse("rejected profile change must not stop original research",f.control.isCancelled());assertSame(plan,plans(f).get(f.task));assertEquals(Boolean.FALSE,read(plan,"cancelled"));
        assertEquals(revision,registry(f).revision());JSONObject actual=f.browser.snapshot();
        for(String key:new String[]{"profileId","profileRevision","browserSessionId"})assertEquals(key,profile.get(key),actual.get(key));
        assertSame(f.control,f.app.control(f.task));assertEquals(f.run,f.tasks.get(f.task,false).getString("runId"));assertEquals(f.epoch,f.tasks.get(f.task,false).getInt("coordinatorEpoch"));
    }
    private void rejectsSelection(String kind)throws Exception {
        try(BrowserTaskHostAdmissionTest.F f=new BrowserTaskHostAdmissionTest.F()){
            BrowserProfileRegistry registry=registry(f);String id=registry.selectedId();
            if(kind.equals("missing"))id="profile-"+java.util.UUID.randomUUID();
            if(kind.equals("inactive")||kind.equals("unsupported")){id=registry.create("metadata-only fixture",registry.revision(),true).getString("id");if(kind.equals("inactive"))registry.requestDelete(id,registry.revision(),true);else BrowserTaskHostAdmissionTest.field(BrowserProfiles.get(f.app),"supported",false);}
            admit(f);Object plan=plans(f).get(f.task);JSONObject profile=f.browser.snapshot();long revision=registry.revision(),expected=kind.equals("stale")?revision-1:revision;String target=id;
            Throwable failure=reject(f,()->f.host.selectProfileForOwner(target,expected));
            assertTrue(String.valueOf(failure),failure instanceof IllegalStateException||failure instanceof IllegalArgumentException||failure instanceof SecurityException||failure instanceof UnsupportedOperationException);
            unchanged(f,plan,profile,revision);
        }
    }
    @Test public void staleSelectKeepsOriginalResearchAndSession()throws Exception {rejectsSelection("stale");}
    @Test public void missingSelectKeepsOriginalResearchAndSession()throws Exception {rejectsSelection("missing");}
    @Test public void disabledSelectKeepsOriginalResearchAndSession()throws Exception {rejectsSelection("inactive");}
    @Test public void unsupportedSelectKeepsOriginalResearchAndSession()throws Exception {rejectsSelection("unsupported");}
    private void rejectsCurrentDelete(boolean stale)throws Exception {
        try(BrowserTaskHostAdmissionTest.F f=new BrowserTaskHostAdmissionTest.F()){
            BrowserTaskHostAdmissionTest.field(BrowserProfiles.get(f.app),"supported",false);admit(f);Object plan=plans(f).get(f.task);JSONObject profile=f.browser.snapshot();long revision=registry(f).revision();
            Throwable failure=reject(f,()->f.host.deleteProfileForOwner(profile.getString("profileId"),stale?revision-1:revision));
            assertTrue(String.valueOf(failure),stale?failure instanceof IllegalStateException:failure instanceof UnsupportedOperationException);unchanged(f,plan,profile,revision);
        }
    }
    @Test public void staleCurrentDeleteKeepsOriginalResearchAndSession()throws Exception {rejectsCurrentDelete(true);}
    @Test public void unsupportedCurrentDeleteKeepsOriginalResearchAndSession()throws Exception {rejectsCurrentDelete(false);}
    @Test public void missingDeleteDoesNotStopOrWriteAnything()throws Exception {
        try(BrowserTaskHostAdmissionTest.F f=new BrowserTaskHostAdmissionTest.F()){
            admit(f);Object plan=plans(f).get(f.task);JSONObject profile=f.browser.snapshot();long revision=registry(f).revision();String missing="profile-"+java.util.UUID.randomUUID();
            assertTrue(reject(f,()->f.host.deleteProfileForOwner(missing,revision)) instanceof IllegalArgumentException);unchanged(f,plan,profile,revision);
        }
    }
    @Test public void validSelectCommitsOnceAndStopsOnlyOriginalPendingControl()throws Exception {
        try(BrowserTaskHostAdmissionTest.F f=new BrowserTaskHostAdmissionTest.F()){
            admit(f);Object plan=plans(f).get(f.task);JSONObject profile=f.browser.snapshot();long revision=registry(f).revision();AtomicInteger cancelled=new AtomicInteger();f.control.onCancel(cancelled::incrementAndGet);
            JSONObject changed=f.pump(f.workers.submit(()->f.host.selectProfileForOwner(profile.getString("profileId"),revision)));
            assertEquals(revision+1,registry(f).revision());assertEquals(profile.getString("profileId"),changed.getString("selectedProfileId"));assertTrue(f.control.isCancelled());assertEquals(1,cancelled.get());assertEquals(Boolean.TRUE,read(plan,"cancelled"));
            assertNotEquals(profile.getString("browserSessionId"),f.browser.snapshot().getString("browserSessionId"));assertEquals(1,f.tasks.list().length());assertEquals(f.run,f.tasks.get(f.task,false).getString("runId"));
        }
    }
    @Test public void validNonCurrentDeleteKeepsOriginalResearchAndSession()throws Exception {
        try(BrowserTaskHostAdmissionTest.F f=new BrowserTaskHostAdmissionTest.F()){
            BrowserProfiles profiles=BrowserProfiles.get(f.app);BrowserTaskHostAdmissionTest.field(profiles,"supported",true);BrowserProfileRegistry registry=registry(f);
            String id=registry.create("metadata-only unused profile",registry.revision(),true).getString("id");admit(f);Object plan=plans(f).get(f.task);JSONObject profile=f.browser.snapshot();long revision=registry.revision();
            JSONObject changed=f.pump(f.workers.submit(()->f.host.deleteProfileForOwner(id,revision)));assertEquals(profile.getString("profileId"),changed.getString("selectedProfileId"));
            assertTrue(registry.pendingDeletes().toString().contains(id));unchanged(f,plan,profile,registry.revision());
        }
    }
    @Test public void reentrantRevisionChangeCannotCommitOrCancelReplacementPlan()throws Exception {
        try(BrowserTaskHostAdmissionTest.F f=new BrowserTaskHostAdmissionTest.F()){
            admit(f);Object original=plans(f).get(f.task);active(f).put(f.task,admissionScope(f,original,f.control));BrowserProfileRegistry registry=registry(f);long revision=registry.revision();
            RunControl replacement=new RunControl();replacement.bindHostRun(f.task,f.run,f.epoch);AtomicReference<Throwable> callbackError=new AtomicReference<>();AtomicReference<Object> replacementPlan=new AtomicReference<>(),replacementScope=new AtomicReference<>();
            f.control.onCancel(()->{try{
                Constructor<?> constructor=original.getClass().getDeclaredConstructor(String.class,JSONObject.class,RunControl.class);constructor.setAccessible(true);
                Object next=constructor.newInstance(f.task,new JSONObject(((JSONObject)read(original,"identity")).toString()),replacement);replacementPlan.set(next);plans(f).put(f.task,next);Object scope=admissionScope(f,next,replacement);replacementScope.set(scope);active(f).put(f.task,scope);BrowserTaskHostAdmissionTest.field(f.app,"activeControl",replacement);
                registry.create("reentrant revision change",registry.revision(),true);
            }catch(Throwable failed){callbackError.set(failed);}});
            Throwable failure=reject(f,()->f.host.selectProfileForOwner(registry.selectedId(),revision));
            assertNull(callbackError.get());assertTrue(String.valueOf(failure),failure.getMessage().contains("已请求停止"));assertTrue(failure.getCause() instanceof IllegalStateException);
            assertEquals(revision+1,registry.revision());assertFalse(replacement.isCancelled());assertSame(replacementPlan.get(),plans(f).get(f.task));assertSame(replacementScope.get(),active(f).get(f.task));assertEquals(Boolean.FALSE,read(replacementPlan.get(),"cancelled"));assertSame(replacement,f.app.control(f.task));
        }
    }
    @Test public void persistenceFailureAfterStopReportsPartialOutcomeWithoutRetry()throws Exception {
        try(BrowserTaskHostAdmissionTest.F f=new BrowserTaskHostAdmissionTest.F()){
            admit(f);BrowserProfileRegistry registry=registry(f);String selected=registry.selectedId();long revision=registry.revision();AtomicInteger cancelled=new AtomicInteger();f.control.onCancel(cancelled::incrementAndGet);
            File file=(File)read(registry,"file");Files.delete(file.toPath());Files.createDirectory(file.toPath());Files.writeString(new File(file,"blocker").toPath(),"fixture");
            Throwable failure=reject(f,()->f.host.selectProfileForOwner(selected,revision));
            assertTrue(String.valueOf(failure),failure.getMessage().contains("已请求停止"));assertNotNull(failure.getCause());assertTrue(f.control.isCancelled());assertEquals(1,cancelled.get());assertEquals(revision,registry.revision());assertEquals(selected,registry.selectedId());
        }
    }
}
