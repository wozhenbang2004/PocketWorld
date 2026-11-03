package dev.pocketengine.displayhelper;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** Actual helper path/event/cancellation code with an explicit InputManager receiver fixture. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class DisplayMultiGesturePathsTest {
    private final DisplayGesturePaths gestures=new DisplayGesturePaths(new Handler(Looper.getMainLooper()));
    private static LeasePolicy lease(String id){return new LeasePolicy(id,5,SystemClock.uptimeMillis(),600000);}
    private static JSONObject request()throws Exception {
        return new JSONObject().put("durationMs",100).put("paths",new JSONArray("[[[0,0],[1000,1000]],[[1000,0],[0,1000]]]"));
    }
    private static final class Point {
        final int action,index,count,source;final int[] ids;final float[] x,y;final long down;
        Point(MotionEvent event){action=event.getActionMasked();index=event.getActionIndex();count=event.getPointerCount();source=event.getSource();down=event.getDownTime();ids=new int[count];x=new float[count];y=new float[count];for(int i=0;i<count;i++){ids[i]=event.getPointerId(i);x[i]=event.getX(i);y[i]=event.getY(i);}}
    }
    @After public void clearInterrupted(){Thread.interrupted();}
    @Test public void twoPointersKeepTheirIdentityAndFinishWithoutASyntheticExtraCancel()throws Exception {
        List<Point> points=new ArrayList<>();JSONObject result=gestures.run(lease("original"),request(),321,481,()->{},event->{points.add(new Point(event));return true;});
        assertTrue(result.getBoolean("accepted"));assertFalse(result.getBoolean("verified"));assertTrue(result.getBoolean("requiresPostconditionCheck"));assertFalse(gestures.active());
        assertEquals(MotionEvent.ACTION_DOWN,points.get(0).action);assertEquals(1,points.get(0).count);
        Point second=points.get(1);assertEquals(MotionEvent.ACTION_POINTER_DOWN,second.action);assertEquals(1,second.index);assertEquals(2,second.count);assertEquals(0,second.ids[0]);assertEquals(1,second.ids[1]);
        Point pointerUp=points.get(points.size()-2);assertEquals(MotionEvent.ACTION_POINTER_UP,pointerUp.action);assertEquals(1,pointerUp.index);assertEquals(2,pointerUp.count);
        Point up=points.get(points.size()-1);assertEquals(MotionEvent.ACTION_UP,up.action);assertEquals(1,up.count);assertEquals(320f,up.x[0],0f);assertEquals(480f,up.y[0],0f);
        for(Point point:points){assertNotEquals(MotionEvent.ACTION_CANCEL,point.action);assertEquals(points.get(0).down,point.down);assertEquals(InputDevice.SOURCE_TOUCHSCREEN,point.source);for(int i=0;i<point.count;i++){assertEquals(i,point.ids[i]);assertTrue(point.x[i]>=0&&point.x[i]<=320);assertTrue(point.y[i]>=0&&point.y[i]<=480);}}
    }
    @Test public void rejectedMoveGetsOnlyOneCancelAndUnconfirmedCleanupBlocksFurtherInput()throws Exception {
        LeasePolicy original=lease("original");List<Integer> actions=new ArrayList<>();
        DisplayGesturePaths.Input input=event->{int action=event.getActionMasked();actions.add(action);return action!=MotionEvent.ACTION_MOVE&&action!=MotionEvent.ACTION_CANCEL;};
        JSONObject result=gestures.run(original,request(),320,480,()->{},input);
        assertTrue(result.isNull("accepted"));assertEquals("cancel_unconfirmed",result.getString("pointerCleanup"));assertTrue(result.getBoolean("requiresReconciliation"));assertTrue(gestures.active());
        assertEquals(List.of(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_POINTER_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_CANCEL),actions);
        gestures.cancelLease(original,input);assertEquals(4,actions.size());JSONObject repeated=gestures.run(original,request(),320,480,()->{},input);assertFalse(repeated.getBoolean("accepted"));assertEquals(4,actions.size());
        gestures.releaseLease(original);assertFalse("only explicit original display teardown removes uncertain touch",gestures.active());
    }
    @Test public void unconfirmedFirstDownCannotAuthorizeCancelOrAnotherGesture()throws Exception {
        LeasePolicy original=lease("original");List<Integer> actions=new ArrayList<>();DisplayGesturePaths.Input input=event->{actions.add(event.getActionMasked());return false;};
        JSONObject result=gestures.run(original,request(),320,480,()->{},input);
        assertTrue(result.isNull("accepted"));assertEquals("not_attempted_down_unconfirmed",result.getString("pointerCleanup"));assertTrue(gestures.active());
        gestures.cancelLease(original,input);assertEquals(List.of(MotionEvent.ACTION_DOWN),actions);
        assertFalse(gestures.run(original,request(),320,480,()->{},input).getBoolean("accepted"));assertEquals(1,actions.size());gestures.releaseLease(original);assertFalse(gestures.active());
    }
    @Test public void cancellationBetweenReservationAndFirstDownPreventsTheLateDown()throws Exception {
        LeasePolicy original=lease("original");AtomicInteger checks=new AtomicInteger(),events=new AtomicInteger();DisplayGesturePaths.Input input=event->{events.incrementAndGet();return true;};
        JSONObject result=gestures.run(original,request(),320,480,()->{if(checks.incrementAndGet()==2)gestures.cancelLease(original,input);},input);
        assertFalse(result.getBoolean("accepted"));assertEquals("gesture_not_started",result.getString("error"));assertEquals(0,events.get());assertFalse(gestures.active());
    }
    @Test public void oldCleanupCannotClearOrCancelAReplacementLeaseSequence()throws Exception {
        LeasePolicy old=lease("old"),replacement=lease("replacement");List<Integer> oldActions=new ArrayList<>(),newActions=new ArrayList<>();
        DisplayGesturePaths.Input newInput=event->{newActions.add(event.getActionMasked());return false;};
        DisplayGesturePaths.Input oldInput=event->{int action=event.getActionMasked();oldActions.add(action);
            if(action==MotionEvent.ACTION_CANCEL){gestures.releaseLease(old);JSONObject next=gestures.run(replacement,request(),320,480,()->{},newInput);assertTrue(next.isNull("accepted"));assertTrue(gestures.active());}
            return action!=MotionEvent.ACTION_MOVE;
        };
        JSONObject result=gestures.run(old,request(),320,480,()->{},oldInput);assertTrue(result.isNull("accepted"));assertTrue(gestures.active());
        gestures.cancelLease(old,oldInput);gestures.releaseLease(old);assertTrue(gestures.active());assertEquals(4,oldActions.size());assertEquals(List.of(MotionEvent.ACTION_DOWN),newActions);
        gestures.releaseLease(replacement);assertFalse(gestures.active());
    }
    @Test public void cancelledQueuedHelperRequestNeverRunsWhenTheMainLooperEventuallyDrains()throws Exception {
        AtomicInteger events=new AtomicInteger();AtomicReference<JSONObject> result=new AtomicReference<>();AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread caller=new Thread(()->{try{result.set(gestures.run(lease("queued"),request(),320,480,()->{},event->{events.incrementAndGet();return true;}));}catch(Throwable failed){failure.set(failed);}},"queued-multipointer-fixture");
        caller.start();try{
            long waitingUntil=System.nanoTime()+2_000_000_000L;while(caller.isAlive()&&caller.getState()!=Thread.State.TIMED_WAITING&&System.nanoTime()<waitingUntil)Thread.sleep(2);
            assertEquals(Thread.State.TIMED_WAITING,caller.getState());caller.interrupt();
            long drainUntil=System.nanoTime()+2_000_000_000L;while(caller.isAlive()&&System.nanoTime()<drainUntil){Shadows.shadowOf(Looper.getMainLooper()).idle();caller.join(2);}
            assertFalse(caller.isAlive());Shadows.shadowOf(Looper.getMainLooper()).idle();assertNull(failure.get());assertNotNull(result.get());assertFalse(result.get().getBoolean("accepted"));assertEquals(0,events.get());assertFalse(gestures.active());
        }finally{if(caller.isAlive()){caller.interrupt();Shadows.shadowOf(Looper.getMainLooper()).idle();caller.join(1000);}}
    }
}
