package dev.pocketengine.bootstrap;
import android.content.Context;
import android.content.pm.PackageInfo;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.store.TaskDatabaseMaintenance;
import dev.pocketengine.features.memory.*;
import java.lang.reflect.*;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
/** Runs the actual initializer on a worker. UI asset integrity and platform FTS are separately covered;
 * these two boundaries alone are substituted so failure injection is deterministic. Not a device test. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class,shadows={BootstrapInitializationNativeTest.Held.class,BootstrapInitializationNativeTest.UiAssets.class,BootstrapInitializationNativeTest.MemoryIndex.class})
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class BootstrapInitializationNativeTest {
 @Implements(EngineApplication.class) public static class Held extends org.robolectric.shadows.ShadowApplication { @Implementation protected void onCreate() {} }
 @Implements(BootstrapChecks.class) public static class UiAssets { @Implementation protected static void requiredUi(Context context) {} }
 @Implements(SQLiteMemoryRetrievalBackend.class) public static class MemoryIndex {
  @Implementation protected static MemoryRetrievalBackend create() { if(injectFailure)throw new IllegalStateException("private-key /private/user-path");return new LexicalMemoryRetrievalBackend(); }
 }
 private static boolean injectFailure;
 private EngineApplication app;
 @Before public void prepare() throws Exception {
  injectFailure=false;app=(EngineApplication)RuntimeEnvironment.getApplication();
  PackageInfo installed=new PackageInfo();installed.packageName=app.getPackageName();installed.applicationInfo=app.getApplicationInfo();installed.applicationInfo.uid=android.os.Process.myUid();installed.versionName="first-launch-test";installed.setLongVersionCode(1);shadowOf(app.getPackageManager()).installPackage(installed);
 }
 private void initialize() throws Exception {
  Field f=EngineApplication.class.getDeclaredField("bootstrap");f.setAccessible(true);BootstrapStatus status=(BootstrapStatus)f.get(app);long generation=status.beginInitial();
  Method m=EngineApplication.class.getDeclaredMethod("initializeOriginalBusiness",long.class,TaskDatabaseMaintenance.OriginalReadPermit.class);m.setAccessible(true);
  FutureTask<Void> task=new FutureTask<>(()->{m.invoke(app,generation,null);return null;});new Thread(task,"first-launch-native-test").start();task.get(60,TimeUnit.SECONDS);
 }
 @After public void close(){app.onTerminate();injectFailure=false;}
 @Test public void emptyFirstLaunchReachesReadyThroughTheActualInitializer() throws Exception {
  initialize();assertEquals(app.bootstrapState().toString(),"ready",app.bootstrapState().getString("state"));assertTrue(app.businessReady());assertNotNull(app.tasks);
 }
 @Test public void injectedRequiredModuleFailureKeepsGateClosedAndShowsSafeLocation() throws Exception {
  injectFailure=true;initialize();var view=app.bootstrapState();assertEquals("failed",view.getString("state"));assertFalse(app.businessReady());assertEquals("features",view.getString("activity"));assertEquals("memory",view.getString("module"));assertEquals("initialization_failed",view.getJSONObject("reason").getString("code"));
  String diagnostic=view.getJSONObject("diagnostics").toString();assertTrue(diagnostic.contains("IllegalStateException"));assertFalse(diagnostic.contains("private-key"));assertFalse(diagnostic.contains("/private/"));assertTrue(app.getDatabasePath("pocket-world.db").exists());assertFalse(view.getBoolean("retryAvailable"));
 }
}
