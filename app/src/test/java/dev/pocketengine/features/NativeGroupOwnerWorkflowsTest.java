package dev.pocketengine.features;

import android.app.Activity;
import android.app.Application;
import androidx.activity.ComponentActivity;
import dev.pocketengine.features.groups.GroupsModule;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class NativeGroupOwnerWorkflowsTest {
    public static class OwnerActivity extends ComponentActivity {}
    public static class OtherActivity extends ComponentActivity {}
    public static class PlainActivity extends Activity {}
    private interface Checked<T> {T run()throws Exception;}
    private static final class Fixture implements AutoCloseable {
        final Application application=RuntimeEnvironment.getApplication();
        final NativeGroupOwnerWorkflows workflows=new NativeGroupOwnerWorkflows(application,OwnerActivity.class);
        final File directory=Files.createTempDirectory("native-group-owner").toFile();
        final GroupsModule groups=new GroupsModule(directory,null,null,null,workflows);
        final ExecutorService worker=Executors.newSingleThreadExecutor();
        final ActivityController<OwnerActivity> activity=Robolectric.buildActivity(OwnerActivity.class).setup();
        Fixture()throws Exception{workflows.bind(groups);}
        String open(){return workflows.open(activity.get());}
        <T>T work(Checked<T> action)throws Exception{
            try{return worker.submit(action::run).get(10,TimeUnit.SECONDS);}
            catch(ExecutionException failure){Throwable cause=failure.getCause();if(cause instanceof Exception)throw (Exception)cause;throw new AssertionError(cause);}
        }
        public void close(){workflows.close();worker.shutdownNow();groups.close();activity.pause().stop().destroy();}
    }
    private static JSONObject create(String room)throws Exception{
        return new JSONObject().put("provider","qq").put("accountId","native-profile").put("roomId",room).put("name","协作群");
    }

    @Test public void actualModuleOwnerRouteIsClosedWithoutTheNativeWorkerContext()throws Exception{
        try(Fixture f=new Fixture()){
            String window=f.open();
            assertEquals(0,f.work(()->f.workflows.snapshot(window)).getJSONArray("groups").length());
            assertThrows(SecurityException.class,()->f.groups.ownerSnapshot());
            JSONObject poisoned=create("room-a").put("owner",true).put("scope","owner/private").put("windowId",window).put("role","admin");
            assertThrows(SecurityException.class,()->f.work(()->f.groups.ownerCommand("group.create",poisoned)));
            assertThrows(SecurityException.class,()->f.work(()->{f.workflows.check();return null;}));
            assertThrows(SecurityException.class,()->f.groups.handle("group.create",poisoned));
            assertEquals(0,f.work(()->f.workflows.snapshot(window)).getJSONArray("groups").length());
        }
    }

    @Test public void realGroupSettingsUseTheirOriginalRevisionAndThePayloadIsNotAuthority()throws Exception{
        try(Fixture f=new Fixture()){
            String window=f.open();JSONObject input=create("room-a");String before=input.toString();
            JSONObject group=f.work(()->f.workflows.handle(window,"group.create",input));
            assertEquals(before,input.toString());assertFalse(group.getBoolean("enabled"));
            JSONObject member=new JSONObject().put("id",group.getString("id")).put("revision",group.getLong("revision"))
                    .put("senderId","member-a").put("displayName","群成员").put("role","member").put("enabled",true);
            JSONObject changed=f.work(()->f.workflows.handle(window,"member.set",member));
            assertEquals(2,changed.getLong("revision"));
            assertThrows(IllegalStateException.class,()->f.work(()->f.workflows.handle(window,"member.set",member)));
            JSONObject current=f.work(()->f.workflows.snapshot(window)).getJSONArray("groups").getJSONObject(0);
            assertEquals(group.getString("id"),current.getString("id"));assertEquals(1,current.getJSONArray("members").length());
            assertThrows(SecurityException.class,()->f.work(()->{f.workflows.check();return null;}));
        }
    }

    @Test public void oldPageDisposalCannotCloseOrRetargetTheCurrentNativeWindow()throws Exception{
        try(Fixture f=new Fixture()){
            String old=f.open(),current=f.open();f.workflows.close(old);
            assertThrows(SecurityException.class,()->f.work(()->f.workflows.handle(old,"group.create",create("old-room"))));
            assertEquals(0,f.work(()->f.workflows.snapshot(current)).getJSONArray("groups").length());
            f.workflows.close(current);
            assertThrows(SecurityException.class,()->f.work(()->f.workflows.handle(current,"group.create",create("closed-room"))));
            String reopened=f.open();assertEquals(0,f.work(()->f.workflows.snapshot(reopened)).getJSONArray("groups").length());
        }
    }

    @Test public void queuedRequestCannotClaimAuthorityAfterItsPageHasClosed()throws Exception{
        try(Fixture f=new Fixture()){
            String window=f.open();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            f.worker.submit(()->{entered.countDown();release.await();return null;});assertTrue(entered.await(5,TimeUnit.SECONDS));
            Future<JSONObject> stale=f.worker.submit(()->f.workflows.handle(window,"group.create",create("late-room")));
            f.workflows.close(window);release.countDown();
            assertTrue(assertThrows(ExecutionException.class,()->stale.get(5,TimeUnit.SECONDS)).getCause() instanceof SecurityException);
            String current=f.open();assertEquals(0,f.work(()->f.workflows.snapshot(current)).getJSONArray("groups").length());
        }
    }

    @Test public void pauseRevokesImmediatelyAndResumeCannotResurrectTheOldPage()throws Exception{
        try(Fixture f=new Fixture()){
            String old=f.open();f.activity.pause();
            assertThrows(SecurityException.class,()->f.work(()->f.workflows.snapshot(old)));
            assertThrows(SecurityException.class,()->f.open());
            f.activity.resume();
            assertThrows(SecurityException.class,()->f.work(()->f.workflows.snapshot(old)));
            String current=f.open();assertEquals(0,f.work(()->f.workflows.snapshot(current)).getJSONArray("groups").length());
        }
    }

    @Test public void aReplacementActivityCannotKeepTheOldActivitysWindowAlive()throws Exception{
        try(Fixture f=new Fixture()){
            String old=f.open();ActivityController<OwnerActivity> replacement=Robolectric.buildActivity(OwnerActivity.class).setup();
            try{
                String current=f.workflows.open(replacement.get());
                assertThrows(SecurityException.class,()->f.work(()->f.workflows.snapshot(old)));
                f.workflows.close(old);
                assertEquals(0,f.work(()->f.workflows.snapshot(current)).getJSONArray("groups").length());
                replacement.pause().stop().destroy();
                assertThrows(SecurityException.class,()->f.work(()->f.workflows.snapshot(current)));
            }finally{if(!replacement.get().isDestroyed())replacement.pause().stop().destroy();}
        }
    }

    @Test public void gateChecksTheActualApplicationActivityTypeAndForegroundLifecycle()throws Exception{
        try(Fixture f=new Fixture()){
            ActivityController<OtherActivity> other=Robolectric.buildActivity(OtherActivity.class).setup();
            try{assertThrows(SecurityException.class,()->f.workflows.open(other.get()));}finally{other.pause().stop().destroy();}
            assertThrows(SecurityException.class,()->f.workflows.open(new OwnerActivity()));
            assertThrows(SecurityException.class,()->f.workflows.open(null));
            NativeGroupOwnerWorkflows foreign=new NativeGroupOwnerWorkflows(new Application(),OwnerActivity.class);
            try{assertThrows(SecurityException.class,()->foreign.open(f.activity.get()));}finally{foreign.close();}
            ActivityController<PlainActivity> plain=Robolectric.buildActivity(PlainActivity.class).setup();
            NativeGroupOwnerWorkflows noLifecycle=new NativeGroupOwnerWorkflows(f.application,PlainActivity.class);
            try{assertThrows(SecurityException.class,()->noLifecycle.open(plain.get()));}finally{noLifecycle.close();plain.pause().stop().destroy();}
        }
    }

    @Test public void contextIsRemovedOnFailureAndCannotBeNestedOrInheritedByAnotherWorker()throws Exception{
        try(Fixture f=new Fixture()){
            String window=f.open();
            assertThrows(IllegalStateException.class,()->f.work(()->f.workflows.call(window,()->{f.workflows.check();throw new IllegalStateException("original failure");})));
            assertThrows(SecurityException.class,()->f.work(()->{f.workflows.check();return null;}));
            f.work(()->f.workflows.call(window,()->{
                assertThrows(SecurityException.class,()->f.workflows.call(window,()->new JSONObject()));
                ExecutorService other=Executors.newSingleThreadExecutor();
                try{assertTrue(other.submit(()->{try{f.workflows.check();return false;}catch(SecurityException denied){return true;}}).get(5,TimeUnit.SECONDS));}
                finally{other.shutdownNow();}
                f.workflows.check();return new JSONObject();
            }));
            assertThrows(SecurityException.class,()->f.work(()->{f.workflows.check();return null;}));
        }
    }

    @Test public void pageCloseDoesNotWaitForIoAndInFlightChecksLoseTheirAuthority()throws Exception{
        try(Fixture f=new Fixture()){
            String window=f.open();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            Future<JSONObject> inFlight=f.worker.submit(()->f.workflows.call(window,()->{
                entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));
                return f.groups.ownerCommand("group.create",create("must-not-create"));
            }));
            assertTrue(entered.await(5,TimeUnit.SECONDS));f.workflows.close(window);release.countDown();
            assertTrue(assertThrows(ExecutionException.class,()->inFlight.get(5,TimeUnit.SECONDS)).getCause() instanceof SecurityException);
            String current=f.open();assertEquals(0,f.work(()->f.workflows.snapshot(current)).getJSONArray("groups").length());
        }
    }

    @Test public void gateIsBoundOnceAndRequestsStayOffMainWhileOpenStaysOnMain()throws Exception{
        try(Fixture f=new Fixture()){
            assertThrows(IllegalStateException.class,()->f.workflows.bind(f.groups));
            String window=f.open();
            assertThrows(IllegalStateException.class,()->f.workflows.snapshot(window));
            assertThrows(SecurityException.class,()->f.work(()->f.workflows.open(f.activity.get())));
            f.workflows.close();
            assertThrows(IllegalStateException.class,()->f.open());
            assertThrows(SecurityException.class,()->f.work(()->f.workflows.snapshot(window)));
            assertThrows(SecurityException.class,()->f.groups.ownerSnapshot());
        }
    }
}
