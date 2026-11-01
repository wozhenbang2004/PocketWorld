package dev.pocketengine.phone.display;
import static org.junit.Assert.*;
import android.app.Application;
import android.graphics.Rect;
import android.os.Looper;
import android.util.SparseArray;
import android.view.accessibility.*;
import dev.pocketengine.core.*;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.phone.*;
import dev.pocketengine.store.SecretVault;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
/** Real main/background observe→execute and real final NativePhoneEntry; only Android node/window/socket interfaces are fixtures. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class,shadows={NativeNodeEntryJourneyTest.Screen.class,NativeNodeEntryJourneyTest.Windows.class,OcrNativeBridgeTest.SocketShadow.class,OcrNativeBridgeTest.Vault.class})
@LooperMode(LooperMode.Mode.PAUSED)
public final class NativeNodeEntryJourneyTest {
 static AccessibilityWindowInfo window;static AccessibilityNodeInfo node;static int display;static AtomicInteger effects=new AtomicInteger();
 @Implements(PhoneAccessibilityService.class) public static class Screen extends Windows {
  @Implementation public String observationSafetyFailure(){return null;}
 }
 @Implements(android.accessibilityservice.AccessibilityService.class) public static class Windows {
  @Implementation public SparseArray<List<AccessibilityWindowInfo>> getWindowsOnAllDisplays(){SparseArray<List<AccessibilityWindowInfo>> out=new SparseArray<>();out.put(display,List.of(AccessibilityWindowInfo.obtain(window)));return out;}
 }
 final ExecutorService worker=Executors.newSingleThreadExecutor();PhoneAccessibilityService service;
 static void set(Object object,String name,Object value)throws Exception {Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
 @Before public void prepare()throws Exception {effects.set(0);service=Robolectric.buildService(PhoneAccessibilityService.class).create().get();Field f=PhoneAccessibilityService.class.getDeclaredField("instance");f.setAccessible(true);f.set(null,service);}
 @After public void close()throws Exception {worker.shutdownNow();worker.awaitTermination(2,TimeUnit.SECONDS);Field f=PhoneAccessibilityService.class.getDeclaredField("instance");f.setAccessible(true);f.set(null,null);window=null;node=null;effects.set(0);}
 <T>T await(Future<T> f)throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!f.isDone()&&System.nanoTime()<until){Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(25));Thread.sleep(2);}return f.get(1,TimeUnit.SECONDS);}
 PhonePort port(boolean background)throws Exception {
  display=background?5:0;node=AccessibilityNodeInfo.obtain();node.setPackageName("example.original");node.setClassName("android.widget.EditText");node.setViewIdResourceName("example.original:id/input");node.setVisibleToUser(true);node.setEnabled(true);node.setClickable(true);node.setEditable(true);node.setFocused(true);node.setText("before");node.setBoundsInScreen(new Rect(5,5,50,50));
  window=AccessibilityWindowInfo.obtain();var w=Shadows.shadowOf(window);w.setId(14);w.setDisplayId(display);w.setType(AccessibilityWindowInfo.TYPE_APPLICATION);w.setActive(true);w.setFocused(true);w.setRoot(node);var n=Shadows.shadowOf(node);n.setAccessibilityWindowInfo(window);n.setRefreshReturnValue(true);n.setOnPerformActionListener((action,args)->{effects.incrementAndGet();return true;});
  if(!background){AndroidPhonePort port=new AndroidPhonePort(RuntimeEnvironment.getApplication());Method read=AndroidPhonePort.class.getDeclaredMethod("readTree",PhoneAccessibilityService.class);read.setAccessible(true);read.invoke(port,service);return port;}
  var context=RuntimeEnvironment.getApplication();context.getSharedPreferences("display_backend",0).edit().putString("socketName","fixture-socket").putString("backend","adb").commit();DisplayModule module=new DisplayModule(new FeatureContext(context,null,new SecretVault(context),event->{}));Method status=OcrNativeBridgeTest.class.getDeclaredMethod("status");status.setAccessible(true);JSONObject state=(JSONObject)status.invoke(null);set(module,"state",state);module.acceptStatus(state);module.pinOwner("task");return new BackgroundPhonePort(module);
 }
 void race(boolean background,String tool)throws Exception {PhonePort port=port(background);RunControl c=new RunControl();c.bindHostRun("task","run",1);JSONObject observed=await(worker.submit(()->port.observe(false,c)));assertTrue(observed.toString(),observed.optBoolean("ok"));AtomicInteger finalGate=new AtomicInteger();c.setAuthorityCheck(()->{for(StackTraceElement f:Thread.currentThread().getStackTrace())if(f.getClassName().equals("dev.pocketengine.phone.observation.NativePhoneEntry")){finalGate.incrementAndGet();c.pause();break;}});JSONObject args=new JSONObject().put("observationId",observed.getString("observationId")).put("nodeId","n0").put("text","中文受控输入");JSONObject result=await(worker.submit(()->port.execute(tool,args,c)));assertTrue("did not reach the actual final entry: "+result,finalGate.get()>0);assertEquals(result.toString(),0,effects.get());assertFalse(result.toString(),result.optBoolean("accepted"));assertTrue(c.isPaused());assertEquals("paused_or_changed",result.getString("error"));}
 @Test public void mainClickPauseAtFinalEntryCallsNoAndroidAction()throws Exception {race(false,"phone_click_node");}
 @Test public void mainTypePauseAtFinalEntryCallsNoAndroidAction()throws Exception {race(false,"phone_type");}
 @Test public void backgroundClickPauseAtFinalEntryCallsNoAndroidAction()throws Exception {race(true,"phone_click_node");}
 @Test public void backgroundTypePauseAtFinalEntryCallsNoAndroidAction()throws Exception {race(true,"phone_type");}
}
