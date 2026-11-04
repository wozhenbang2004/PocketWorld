package dev.pocketengine.displayhelper;

import static org.junit.Assert.*;
import android.app.Application;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.KeyEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowProcess;

/** Actual helper loop/guards/MotionEvents; display flags/removeMode and InputManager receiver are controlled fixtures, not device proof. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class DisplayGestureCleanupTest {
    private DisplayEngine engine;private final List<VirtualDisplay> displays=new ArrayList<>();
    private LeasePolicy lease;private VirtualDisplay original;private FakeInput input;
    private static final class Point {
        final int action,display,source,meta;final long downTime,eventTime;final float x,y;
        Point(MotionEvent e)throws Exception{action=e.getActionMasked();source=e.getSource();meta=e.getMetaState();downTime=e.getDownTime();eventTime=e.getEventTime();x=e.getX();y=e.getY();Method get=InputEvent.class.getDeclaredMethod("getDisplayId");get.setAccessible(true);display=((Number)get.invoke(e)).intValue();}
    }
    public static final class FakeInput {
        final List<Point> points=new ArrayList<>(),lateDownSnapshots=new ArrayList<>();final List<Integer> keyActions=new ArrayList<>();int falseKeyAction=-1;boolean rejectDown,unknownDown,rejectMove,rejectUp,rejectCancel,interruptAfterDown;Runnable onMove,onDown;java.util.concurrent.Callable<Void> beforeDownResult;
        public boolean inject(InputEvent event,int sync)throws Exception {
            assertEquals(2,sync);if(event instanceof KeyEvent){int action=((KeyEvent)event).getAction();keyActions.add(action);return action!=falseKeyAction;}MotionEvent motion=(MotionEvent)event;points.add(new Point(motion));int action=motion.getActionMasked();
            if(action==MotionEvent.ACTION_DOWN){if(beforeDownResult!=null){beforeDownResult.call();lateDownSnapshots.add(new Point(motion));}if(unknownDown)throw new IllegalStateException("controlled unknown native DOWN");if(rejectDown)return false;if(onDown!=null)onDown.run();if(interruptAfterDown)Thread.currentThread().interrupt();}
            if(action==MotionEvent.ACTION_MOVE){if(onMove!=null)onMove.run();if(rejectMove)return false;}
            if(action==MotionEvent.ACTION_UP&&rejectUp)return false;
            return action!=MotionEvent.ACTION_CANCEL||!rejectCancel;
        }
    }
    private static void set(Object object,String field,Object value)throws Exception{Field f=object.getClass().getDeclaredField(field);f.setAccessible(true);f.set(object,value);}
    private static Object get(Object object,String field)throws Exception{Field f=object.getClass().getDeclaredField(field);f.setAccessible(true);return f.get(object);}
    private static int requiredFlags()throws Exception{return Display.class.getDeclaredField("FLAG_TRUSTED").getInt(null)|Display.class.getDeclaredField("FLAG_OWN_FOCUS").getInt(null)|Display.class.getDeclaredField("FLAG_STEAL_TOP_FOCUS_DISABLED").getInt(null);}
    private static int removeMode(String name)throws Exception{return Display.class.getDeclaredField(name).getInt(null);}
    private static void setRemoveMode(Display display,int mode)throws Exception {
        // Change the actual shadow service's DisplayInfo, then read it via the real Display API.
        // A nonexistent Display flag must never stand in for this independent policy.
        Class<?> globalType=Class.forName("android.hardware.display.DisplayManagerGlobal");Object global=globalType.getDeclaredMethod("getInstance").invoke(null);
        Object info=globalType.getDeclaredMethod("getDisplayInfo",int.class).invoke(global,display.getDisplayId());assertNotNull(info);Field remove=info.getClass().getDeclaredField("removeMode");remove.setInt(info,mode);
        Object shadow=org.robolectric.shadow.api.Shadow.extract(global);Method change=shadow.getClass().getDeclaredMethod("changeDisplay",int.class,info.getClass());change.setAccessible(true);change.invoke(shadow,display.getDisplayId(),info);
        assertTrue(display.isValid());assertEquals(mode,((Integer)Display.class.getDeclaredMethod("getRemoveMode").invoke(display)).intValue());
    }
    private VirtualDisplay display()throws Exception {
        VirtualDisplay v=RuntimeEnvironment.getApplication().getSystemService(DisplayManager.class).createVirtualDisplay("controlled-helper-display",320,480,160,null,DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC|DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
        assertNotNull(v);assertNotNull(v.getDisplay());assertTrue(v.getDisplay().getDisplayId()>0);Shadows.shadowOf(v.getDisplay()).setFlags(requiredFlags());setRemoveMode(v.getDisplay(),removeMode("REMOVE_MODE_DESTROY_CONTENT"));displays.add(v);return v;
    }
    private void fixture()throws Exception {
        ShadowProcess.setUid(2000);Context shell=new ContextWrapper(RuntimeEnvironment.getApplication()){
            @Override public String getPackageName(){return "com.android.shell";}
            @Override public ApplicationInfo getApplicationInfo(){ApplicationInfo a=new ApplicationInfo();a.uid=2000;return a;}};
        assertFalse(shell.getSystemService(KeyguardManager.class).isDeviceLocked());assertFalse(shell.getSystemService(KeyguardManager.class).isKeyguardLocked());
        engine=new DisplayEngine(shell,new Handler(Looper.getMainLooper()));original=display();lease=new LeasePolicy("original-session",original.getDisplay().getDisplayId(),SystemClock.uptimeMillis(),600000);
        set(engine,"display",original);set(engine,"lease",lease);set(engine,"width",320);set(engine,"height",480);set(engine,"requiredDisplayFlags",requiredFlags());
        set(engine,"density",160);set(engine,"geometry",new DisplayGeometry(1,320,480,160,0));set(engine,"geometryReady",true);
        ((java.util.concurrent.atomic.AtomicLong)get(engine,"geometryGeneration")).set(1);
        ((java.util.concurrent.atomic.AtomicLong)get(engine,"frameGeneration")).set(7);
        input=new FakeInput();set(engine,"inputManager",input);set(engine,"inject",FakeInput.class.getMethod("inject",InputEvent.class,int.class));
        Method setDisplay=InputEvent.class.getDeclaredMethod("setDisplayId",int.class);setDisplay.setAccessible(true);set(engine,"setDisplay",setDisplay);
    }
    private JSONObject request(boolean swipe)throws Exception {
        JSONObject r=new JSONObject().put("command",swipe?"swipe":"tap").put("sessionId",lease.session()).put("displayId",lease.display()).put("frameGeneration",7).put("geometryGeneration",1);
        return swipe?r.put("x1",100).put("y1",200).put("x2",800).put("y2",900).put("durationMs",100):r.put("x",400).put("y",600);
    }
    private DisplayEngine.Failure failed(boolean swipe)throws Exception{return assertThrows(DisplayEngine.Failure.class,()->engine.handle(request(swipe)));}
    private void actions(int... actions){assertEquals(actions.length,input.points.size());for(int i=0;i<actions.length;i++)assertEquals(actions[i],input.points.get(i).action);}
    private void unknown(DisplayEngine.Failure failure){assertEquals("action_unknown",failure.code);assertTrue(failure.dispatched);assertTrue(failure.getMessage().contains("勿直接重放"));}
    @After public void close(){Thread.interrupted();if(engine!=null)engine.close();for(VirtualDisplay v:displays)try{v.release();}catch(Exception ignored){}ShadowProcess.setUid(10000);}
    private void rotate(int rotation)throws Exception {
        Class<?> type=Class.forName("android.hardware.display.DisplayManagerGlobal");Object global=type.getDeclaredMethod("getInstance").invoke(null);
        Object info=type.getDeclaredMethod("getDisplayInfo",int.class).invoke(global,lease.display());
        info.getClass().getField("rotation").setInt(info,rotation);
        info.getClass().getField("logicalWidth").setInt(info,(rotation&1)==0?320:480);
        info.getClass().getField("logicalHeight").setInt(info,(rotation&1)==0?480:320);
        Object shadow=org.robolectric.shadow.api.Shadow.extract(global);Method change=shadow.getClass().getDeclaredMethod("changeDisplay",int.class,info.getClass());change.setAccessible(true);change.invoke(shadow,lease.display(),info);
        assertEquals(rotation,original.getDisplay().getRotation());
    }
    @Test public void rotationAfterDownCancelsOriginalCoordinatesBeforeAnyMove()throws Exception {
        fixture();input.onDown=()->{try{rotate(1);}catch(Exception failure){throw new IllegalStateException(failure);}};
        DisplayEngine.Failure failure=failed(true);unknown(failure);actions(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_CANCEL);
        Point down=input.points.get(0),cancel=input.points.get(1);assertEquals(down.downTime,cancel.downTime);assertEquals(down.x,cancel.x,0f);assertEquals(down.y,cancel.y,0f);
        assertSame(lease,get(engine,"lease"));assertSame(original,get(engine,"display"));assertNull(get(engine,"activeTouch"));
        DisplayGeometry geometry=(DisplayGeometry)get(engine,"geometry");assertEquals(480,geometry.width);assertEquals(320,geometry.height);assertEquals(1,geometry.rotation);assertTrue(geometry.generation>1);
        android.media.ImageReader reader=(android.media.ImageReader)get(engine,"reader");assertEquals(320,reader.getWidth());assertEquals(480,reader.getHeight());
    }
    @Test public void halfTurnWithUnchangedDimensionsRejectsOldDown()throws Exception {
        fixture();rotate(2);DisplayEngine.Failure failure=failed(false);assertEquals("stale_observation",failure.code);assertFalse(failure.dispatched);actions();
        DisplayGeometry geometry=(DisplayGeometry)get(engine,"geometry");assertEquals(320,geometry.width);assertEquals(480,geometry.height);assertEquals(2,geometry.rotation);assertTrue(geometry.generation>1);
        assertSame(lease,get(engine,"lease"));assertSame(original,get(engine,"display"));
    }
    @Test public void delayedGeometryEventInvalidatesRoundTripEvenWithOriginalFinalSize()throws Exception {
        fixture();rotate(1);rotate(0);
        ((DisplayManager.DisplayListener)get(engine,"geometryListener")).onDisplayChanged(lease.display());
        DisplayEngine.Failure failure=failed(false);assertEquals("stale_observation",failure.code);assertFalse(failure.dispatched);actions();
        DisplayGeometry geometry=(DisplayGeometry)get(engine,"geometry");assertEquals(0,geometry.rotation);assertTrue(geometry.generation>1);
        assertTrue(((java.util.concurrent.atomic.AtomicLong)get(engine,"frameGeneration")).get()>7);
    }
    @Test public void geometryChangeCannotClearUnconfirmedOriginalDownOrReplayIt()throws Exception {
        fixture();input.rejectDown=true;unknown(failed(false));assertNotNull(get(engine,"activeTouch"));
        rotate(1);DisplayEngine.Failure failure=failed(false);assertEquals("geometry_unavailable",failure.code);assertFalse(failure.dispatched);
        actions(MotionEvent.ACTION_DOWN);assertNotNull(get(engine,"activeTouch"));assertSame(lease,get(engine,"lease"));assertFalse((Boolean)get(engine,"geometryReady"));
    }
    @Test public void moveToPrimaryPolicyRefusesInputDespiteEveryRequiredFocusFlag()throws Exception {
        fixture();assertThrows(NoSuchFieldException.class,()->Display.class.getDeclaredField("FLAG_DESTROY_CONTENT_ON_REMOVAL"));assertEquals(requiredFlags(),original.getDisplay().getFlags());
        setRemoveMode(original.getDisplay(),removeMode("REMOVE_MODE_MOVE_CONTENT_TO_PRIMARY"));DisplayEngine.Failure failure=failed(false);assertEquals("display_lost",failure.code);assertFalse(failure.dispatched);actions();assertNull(get(engine,"activeTouch"));
    }
    @Test public void removalPolicyChangeAfterDownPreventsCleanupInputAndKeepsUnknownTouch()throws Exception {
        fixture();input.rejectMove=true;input.onMove=()->{try{setRemoveMode(original.getDisplay(),removeMode("REMOVE_MODE_MOVE_CONTENT_TO_PRIMARY"));}catch(Exception e){throw new IllegalStateException(e);}};
        DisplayEngine.Failure failure=failed(true);unknown(failure);actions(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE);assertEquals("cancel_unconfirmed",failure.pointerCleanup);assertNotNull(get(engine,"activeTouch"));
    }
    @Test public void confirmedDownThenRejectedMoveGetsOneDirectedCancelButRemainsUnknown()throws Exception {
        fixture();input.rejectMove=true;DisplayEngine.Failure failure=failed(true);unknown(failure);actions(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_CANCEL);
        assertEquals("cancel_accepted_not_business_proof",failure.pointerCleanup);assertNull(get(engine,"activeTouch"));
        Point down=input.points.get(0),cancel=input.points.get(2);assertEquals(down.downTime,cancel.downTime);assertEquals(down.x,cancel.x,0f);assertEquals(down.y,cancel.y,0f);
        for(Point point:input.points){assertEquals(lease.display(),point.display);assertTrue(point.display>0);assertEquals(InputDevice.SOURCE_TOUCHSCREEN,point.source);}
    }
    @Test public void unconfirmedUpStillOnlyGetsCancelRatherThanASecondUpOrTap()throws Exception {
        fixture();input.rejectUp=true;DisplayEngine.Failure failure=failed(false);unknown(failure);actions(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL);assertEquals("cancel_accepted_not_business_proof",failure.pointerCleanup);
    }
    @Test public void falseDownIsUnconfirmedAndBlocksAnotherDownUntilOriginalDisplayRelease()throws Exception {
        fixture();input.rejectDown=true;DisplayEngine.Failure failure=failed(false);unknown(failure);actions(MotionEvent.ACTION_DOWN);assertEquals("not_attempted_down_unconfirmed",failure.pointerCleanup);assertNotNull(get(engine,"activeTouch"));
        assertEquals("gesture_in_flight",failed(false).code);actions(MotionEvent.ACTION_DOWN);engine.close();assertNull(get(engine,"activeTouch"));assertNull(get(engine,"lease"));assertNull(get(engine,"display"));
    }
    @Test public void explicitPreInjectionFrameRefusalHasNoInputOrUnknownTouch()throws Exception {
        fixture();((java.util.concurrent.atomic.AtomicLong)get(engine,"frameGeneration")).set(8);DisplayEngine.Failure failure=failed(false);assertEquals("stale_observation",failure.code);assertFalse(failure.dispatched);actions();assertNull(get(engine,"activeTouch"));
    }
    @Test public void falseBackKeyDownOrUpRemainsUnknownAndNeverAddsAnotherKeyEvent()throws Exception {
        for(int rejected:new int[]{KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP}){
            fixture();input.falseKeyAction=rejected;JSONObject r=new JSONObject().put("command","key").put("keyCode",KeyEvent.KEYCODE_BACK).put("sessionId",lease.session()).put("displayId",lease.display()).put("frameGeneration",7);
            DisplayEngine.Failure failure=assertThrows(DisplayEngine.Failure.class,()->engine.handle(r));assertEquals("action_unknown",failure.code);assertTrue(failure.dispatched);assertEquals(rejected==KeyEvent.ACTION_DOWN?List.of(KeyEvent.ACTION_DOWN):List.of(KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP),input.keyActions);actions();assertNull(get(engine,"activeTouch"));engine.close();
        }
    }
    @Test public void unknownDownCannotAuthorizeCancelOrAnotherDown()throws Exception {
        fixture();input.unknownDown=true;DisplayEngine.Failure failure=failed(false);unknown(failure);actions(MotionEvent.ACTION_DOWN);assertEquals("not_attempted_down_unconfirmed",failure.pointerCleanup);
        assertEquals("gesture_in_flight",failed(false).code);actions(MotionEvent.ACTION_DOWN);
    }
    @Test public void queuedCancellationWinsAdmissionAndCallableCannotRunLater()throws Exception {
        fixture();Method onMain=DisplayEngine.class.getDeclaredMethod("onMain",java.util.concurrent.Callable.class);onMain.setAccessible(true);
        java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();java.util.concurrent.atomic.AtomicBoolean interrupted=new java.util.concurrent.atomic.AtomicBoolean();
        Thread waiting=new Thread(()->{try{onMain.invoke(engine,(java.util.concurrent.Callable<Void>)()->{calls.incrementAndGet();return null;});}catch(java.lang.reflect.InvocationTargetException caught){failure.set(caught.getCause());}catch(Throwable caught){failure.set(caught);}finally{interrupted.set(Thread.currentThread().isInterrupted());}},"queued-helper-cancel-fixture");
        waiting.start();try{awaitState(()->waiting.getState()==Thread.State.TIMED_WAITING,2000);waiting.interrupt();waiting.join(2000);assertFalse(waiting.isAlive());Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(0,calls.get());assertTrue(String.valueOf(failure.get()),failure.get() instanceof DisplayEngine.Failure);DisplayEngine.Failure rejected=(DisplayEngine.Failure)failure.get();assertEquals("dispatch_cancelled",rejected.code);assertFalse(rejected.dispatched);assertTrue(interrupted.get());actions();}
        finally{if(waiting.isAlive()){waiting.interrupt();Shadows.shadowOf(Looper.getMainLooper()).idle();waiting.join(1000);}}
    }
    @Test public void enteredDownWaitInterruptsBeforeFalseResultButKeepsUnknownGateAndMainOwnedEvent()throws Exception {
        fixture();input.rejectDown=true;java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),allowFalse=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>(),signalFailure=new java.util.concurrent.atomic.AtomicReference<>();java.util.concurrent.atomic.AtomicBoolean interrupted=new java.util.concurrent.atomic.AtomicBoolean(),waitReturnedBeforeFalse=new java.util.concurrent.atomic.AtomicBoolean();java.util.concurrent.atomic.AtomicReference<MotionEvent> recycledCallerSlot=new java.util.concurrent.atomic.AtomicReference<>();
        input.beforeDownResult=()->{entered.countDown();if(!allowFalse.await(3,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("fixture did not release native result");return null;};
        JSONObject originalRequest=request(false);Thread waiting=new Thread(()->{try{engine.handle(originalRequest);}catch(Throwable caught){failure.set(caught);}finally{interrupted.set(Thread.currentThread().isInterrupted());}},"entered-helper-cancel-fixture");
        Thread signal=new Thread(()->{try{assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS));waiting.interrupt();awaitState(()->waiting.getState()==Thread.State.TIMED_WAITING&&hasMethod(waiting,"cancelTouch"),2000);waitReturnedBeforeFalse.set(true);
            // The caller's finally has recycled its own event before cancelTouch waits. Holding
            // an obtained slot proves the still-running native callback must not depend on it.
            MotionEvent slot=MotionEvent.obtain(1,2,MotionEvent.ACTION_MOVE,999,888,7);slot.setSource(InputDevice.SOURCE_MOUSE);recycledCallerSlot.set(slot);
        }catch(Throwable caught){signalFailure.set(caught);}finally{allowFalse.countDown();}},"native-result-release-fixture");
        waiting.start();signal.start();try{
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(7);while(waiting.isAlive()&&System.nanoTime()<deadline){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(2);}waiting.join(1000);signal.join(1000);assertFalse(waiting.isAlive());assertFalse(signal.isAlive());assertNull(String.valueOf(signalFailure.get()),signalFailure.get());assertTrue(waitReturnedBeforeFalse.get());assertTrue(String.valueOf(failure.get()),failure.get() instanceof DisplayEngine.Failure);unknown((DisplayEngine.Failure)failure.get());assertTrue(interrupted.get());actions(MotionEvent.ACTION_DOWN);assertEquals(1,input.lateDownSnapshots.size());
            Point before=input.points.get(0),after=input.lateDownSnapshots.get(0);assertEquals(before.action,after.action);assertEquals(before.display,after.display);assertEquals(before.source,after.source);assertEquals(before.meta,after.meta);assertEquals(before.downTime,after.downTime);assertEquals(before.eventTime,after.eventTime);assertEquals(before.x,after.x,0f);assertEquals(before.y,after.y,0f);assertNotNull(get(engine,"activeTouch"));assertEquals("gesture_in_flight",failed(false).code);actions(MotionEvent.ACTION_DOWN);
        }finally{allowFalse.countDown();if(waiting.isAlive())waiting.interrupt();Shadows.shadowOf(Looper.getMainLooper()).idle();waiting.join(1000);signal.join(1000);MotionEvent slot=recycledCallerSlot.get();if(slot!=null)slot.recycle();}
    }
    private static boolean hasMethod(Thread thread,String method){for(StackTraceElement item:thread.getStackTrace())if(item.getClassName().equals(DisplayEngine.class.getName())&&item.getMethodName().equals(method))return true;return false;}
    private static void awaitState(java.util.function.BooleanSupplier condition,long ms)throws Exception{long until=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(ms);while(!condition.getAsBoolean()&&System.nanoTime()<until)Thread.sleep(2);assertTrue("fixture race boundary not reached",condition.getAsBoolean());}
    @Test public void successfulUpNeverGetsAnExtraCancel()throws Exception {
        fixture();JSONObject receipt=engine.handle(request(false));assertTrue(receipt.getBoolean("accepted"));assertFalse(receipt.getBoolean("verified"));actions(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP);assertNull(get(engine,"activeTouch"));
    }
    @Test public void replacementVirtualDisplayEvenWithReusedNumericIdCannotReceiveOldCancel()throws Exception {
        fixture();VirtualDisplay replacement=display();Field nativeDisplay=VirtualDisplay.class.getDeclaredField("mDisplay");nativeDisplay.setAccessible(true);nativeDisplay.set(replacement,original.getDisplay());
        assertEquals(original.getDisplay().getDisplayId(),replacement.getDisplay().getDisplayId());assertNotSame(original,replacement);input.rejectMove=true;
        input.onMove=()->{try{set(engine,"display",replacement);}catch(Exception e){throw new IllegalStateException(e);}};
        DisplayEngine.Failure failure=failed(true);unknown(failure);assertEquals("not_attempted_original_sequence_or_display_gone",failure.pointerCleanup);actions(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE);
    }
    @Test public void originalDisplayReleasePreventsLateCancelFromTouchingAReplacement()throws Exception {
        fixture();VirtualDisplay replacement=display();input.rejectMove=true;
        input.onMove=()->{try{engine.close();set(engine,"display",replacement);set(engine,"lease",new LeasePolicy("replacement-session",replacement.getDisplay().getDisplayId(),SystemClock.uptimeMillis(),600000));set(engine,"requiredDisplayFlags",requiredFlags());}catch(Exception e){throw new IllegalStateException(e);}};
        DisplayEngine.Failure failure=failed(true);unknown(failure);assertEquals("not_attempted_original_sequence_or_display_gone",failure.pointerCleanup);actions(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE);
    }
    @Test public void interruptedGestureCancelsItsOwnTouchAndPreservesInterrupt()throws Exception {
        fixture();input.interruptAfterDown=true;try{DisplayEngine.Failure failure=failed(true);unknown(failure);assertTrue(Thread.currentThread().isInterrupted());actions(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_CANCEL);assertEquals("cancel_accepted_not_business_proof",failure.pointerCleanup);}finally{Thread.interrupted();}
    }
    @Test public void rejectedCancelStaysUnknownAndDoesNotPermitAReplacementTouch()throws Exception {
        fixture();input.rejectMove=true;input.rejectCancel=true;DisplayEngine.Failure failure=failed(true);unknown(failure);assertEquals("cancel_unconfirmed",failure.pointerCleanup);assertNotNull(get(engine,"activeTouch"));actions(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_CANCEL);
        assertEquals("gesture_in_flight",failed(false).code);actions(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_CANCEL);
    }
    @Test public void expiredOriginalLeaseDoesNotGrantEvenCleanupInput()throws Exception {
        fixture();input.onDown=lease::release;DisplayEngine.Failure failure=failed(true);unknown(failure);assertEquals("cancel_unconfirmed",failure.pointerCleanup);actions(MotionEvent.ACTION_DOWN);
    }
}
