package dev.pocketengine.browser;

import android.app.Application;
import android.os.Looper;
import android.view.MotionEvent;
import android.webkit.WebView;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real SDK event objects/main-looper dispatcher; the View is an explicit input receiver, not a browser renderer. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class BrowserNativeInputNativeTest {
    static final class Receiver extends WebView {
        final List<Integer> events=new ArrayList<>();final AtomicBoolean owned;Runnable afterDown=()->{};boolean accepted=true;
        Receiver(AtomicBoolean owned){super(RuntimeEnvironment.getApplication());this.owned=owned;layout(0,0,400,800);}
        @Override public boolean dispatchTouchEvent(MotionEvent event){assertTrue("every event must be inside native host ownership",owned.get());events.add(event.getActionMasked());if(event.getActionMasked()==MotionEvent.ACTION_DOWN)afterDown.run();return accepted;}
    }
    static JSONObject geometry()throws Exception{return new JSONObject().put("status","geometry_observed").put("viewport",new JSONObject().put("width",400).put("height",800).put("offsetX",0).put("offsetY",0)).put("from",new JSONObject().put("x",40).put("y",80)).put("to",new JSONObject().put("x",200).put("y",500));}
    @Test public void ownerTakeoverBetweenDragFramesCancelsOnlyOriginalViewAndStopsTheRemainingMotion()throws Exception {
        AtomicBoolean owned=new AtomicBoolean(),live=new AtomicBoolean(true);Receiver receiver=new Receiver(owned);try{CompletableFuture<JSONObject> result=new CompletableFuture<>();receiver.afterDown=()->live.set(false);
            BrowserNativeInput.dispatch(receiver,new JSONObject().put("action","drag"),geometry(),()->{if(!live.get())throw new SecurityException("owner took over original page");},()->owned.set(true),()->owned.set(false),result);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
            assertEquals(List.of(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_CANCEL),receiver.events);assertEquals("unknown",result.get().getString("status"));assertTrue(result.get().isNull("accepted"));assertFalse(result.get().getBoolean("automaticReplayAllowed"));assertFalse(owned.get());
        }finally{receiver.destroy();}
    }
    @Test public void staleAuthorityAndCoordinatesNeverDispatchAndClipboardOrSystemKeyStringsAreRejected()throws Exception {
        AtomicBoolean owned=new AtomicBoolean();Receiver receiver=new Receiver(owned);try{assertThrows(SecurityException.class,()->BrowserNativeInput.dispatch(receiver,new JSONObject().put("action","tap"),geometry(),()->{throw new SecurityException("original run revoked");},()->owned.set(true),()->owned.set(false),new CompletableFuture<>()));assertTrue(receiver.events.isEmpty());
            JSONObject outside=geometry();outside.getJSONObject("from").put("x",500);assertThrows(SecurityException.class,()->BrowserNativeInput.dispatch(receiver,new JSONObject().put("action","tap"),outside,()->{},()->owned.set(true),()->owned.set(false),new CompletableFuture<>()));assertTrue(receiver.events.isEmpty());
            for(String key:new String[]{"Control+V","Meta+C","HOME","Power"})assertThrows(IllegalArgumentException.class,()->BrowserNativeInput.validate(new JSONObject().put("action","key").put("key",key)));
        }finally{receiver.destroy();}
    }
    @Test public void unconsumedNativeEventsRemainUnknownAfterEntryAndNeverLookUndispatched()throws Exception {
        AtomicBoolean owned=new AtomicBoolean();Receiver receiver=new Receiver(owned);try{receiver.accepted=false;CompletableFuture<JSONObject> result=new CompletableFuture<>();
            BrowserNativeInput.dispatch(receiver,new JSONObject().put("action","tap"),geometry(),()->{},()->owned.set(true),()->owned.set(false),result);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
            assertEquals(List.of(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP),receiver.events);JSONObject receipt=result.get();assertEquals("unknown",receipt.getString("status"));assertTrue(receipt.isNull("accepted"));assertFalse(receipt.getBoolean("platformDispatchAccepted"));assertFalse(receipt.getBoolean("automaticReplayAllowed"));assertEquals("unknown",BrowserActionReceipt.attach("input",new JSONObject(),null,null,true,receipt).getJSONObject("actionReceipt").getJSONObject("dispatch").getString("state"));
        }finally{receiver.destroy();}
    }
}
