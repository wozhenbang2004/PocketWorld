package dev.pocketengine.browser;

import android.app.Application;
import android.os.Looper;
import dev.pocketengine.BrowserActivity;
import dev.pocketengine.core.ExecutionResources;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import java.lang.reflect.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual single-leaf-compiled owner controller/state/queue, not Compose rendering or Android process-death. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class BrowserWorkspaceControllerNativeTest {
    interface Check {boolean done()throws Exception;}
    static void pump(Check check)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!check.done()&&System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(3);}Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue("original owner UI queue did not reach expected receipt",check.done());}
    static Object call(Object target,String name,Class<?>[] parameters,Object... args)throws Exception {Method method=target.getClass().getDeclaredMethod(name,parameters);method.setAccessible(true);try{return method.invoke(target,args);}catch(InvocationTargetException failure){if(failure.getCause() instanceof Exception)throw (Exception)failure.getCause();throw new AssertionError(failure.getCause());}}
    static Object controller(BrowserTaskHost host,BrowserActivity activity)throws Exception {Class<?> type=Class.forName("dev.pocketengine.browser.OwnerController");Constructor<?> constructor=type.getDeclaredConstructor(BrowserTaskHost.class,BrowserActivity.class,boolean.class);constructor.setAccessible(true);Object controller=constructor.newInstance(host,activity,true);call(controller,"refresh",new Class<?>[0]);pump(()->Boolean.TRUE.equals(call(controller,"getDraftReady",new Class<?>[0])));return controller;}
    static void close(Object controller)throws Exception {call(controller,"close",new Class<?>[0]);Field field=controller.getClass().getDeclaredField("io");field.setAccessible(true);ExecutorService io=(ExecutorService)field.get(controller);pump(io::isTerminated);}
    static final class F implements AutoCloseable {
        final BrowserTaskActivityTest.Fixture original;final BrowserModule module;final BrowserTaskHost host;
        F()throws Exception {BrowserActivity.setOwnerPanelInstaller(null);original=new BrowserTaskActivityTest().new Fixture();original.open("original-context");module=new BrowserModule(new FeatureContext(original.browser,null,null,event->{}));host=new BrowserTaskHost(module,original.tasks,new ExecutionResources(),new BrowserTaskHost.OriginalTasks(){public void checkModelConfigured(){throw new AssertionError("A draft cannot start inference");}public void launch(String task){throw new AssertionError("A draft cannot launch a new task");}public RunControl currentControl(String task){return original.current.control;}},event->{});original.browser.showResearchPanelForOwner(original.taskId);}
        public void close()throws Exception {module.close();Shadows.shadowOf(Looper.getMainLooper()).idle();original.close();}
    }
    @Test public void closeDrainsLatestUnacknowledgedTextAndNewControllerRestoresNoConsentOrTask()throws Exception {
        try(F f=new F()){int operations=f.original.tasks.get(f.original.taskId,false).getJSONArray("operations").length();Object one=controller(f.host,f.original.browser);call(one,"editGoal",new Class<?>[]{String.class},"最后一份未等400ms的目标🙂");close(one);Object two=controller(f.host,f.original.browser);try{assertEquals("最后一份未等400ms的目标🙂",call(two,"getGoal",new Class<?>[0]));assertEquals(Boolean.FALSE,call(two,"getConsent",new Class<?>[0]));assertEquals(Boolean.FALSE,call(two,"getAutoRead",new Class<?>[0]));assertEquals(Boolean.FALSE,call(two,"getSaveReadSources",new Class<?>[0]));assertEquals("",call(two,"getPublicSeeds",new Class<?>[0]));assertEquals(operations,f.original.tasks.get(f.original.taskId,false).getJSONArray("operations").length());}finally{close(two);}}
    }
    @Test public void explicitClearCancelsPendingAutosaveAndCannotRestoreOldInputAfterClose()throws Exception {
        try(F f=new F()){Object one=controller(f.host,f.original.browser);call(one,"editGoal",new Class<?>[]{String.class},"不应复活的旧输入");call(one,"flushGoal",new Class<?>[]{boolean.class,boolean.class},false,false);call(one,"clearGoal",new Class<?>[0]);pump(()->Boolean.FALSE.equals(call(one,"getDraftSaving",new Class<?>[0])));close(one);Object two=controller(f.host,f.original.browser);try{assertEquals("",call(two,"getGoal",new Class<?>[0]));assertNull(call(two,"getDraftError",new Class<?>[0]));}finally{close(two);}}
    }
    @Test public void nativeBackClosesOnlyResearchSurfaceAndFlushesTextWithoutResumingOrStoppingTheOriginalRun()throws Exception {
        try(F f=new F()){int operations=f.original.tasks.get(f.original.taskId,false).getJSONArray("operations").length();Object owner=controller(f.host,f.original.browser);try{call(owner,"editGoal",new Class<?>[]{String.class},"退出编辑仍保留的目标");assertEquals(Boolean.TRUE,call(owner,"backToPage",new Class<?>[0]));assertEquals("page",call(owner,"getWorkspaceView",new Class<?>[0]));assertEquals(Boolean.FALSE,call(owner,"getExpanded",new Class<?>[0]));assertEquals(Boolean.FALSE,call(owner,"backToPage",new Class<?>[0]));assertFalse(f.original.current.control.isPaused());assertFalse(f.original.current.control.isCancelled());assertEquals(operations,f.original.tasks.get(f.original.taskId,false).getJSONArray("operations").length());}finally{close(owner);}Object restored=controller(f.host,f.original.browser);try{assertEquals("退出编辑仍保留的目标",call(restored,"getGoal",new Class<?>[0]));}finally{close(restored);}}
    }
}
