package dev.pocketengine.features.local.runtime.android;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import dev.pocketengine.features.local.runtime.RuntimeLogObservationPolicy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.json.JSONObject;

/** One original runtime's read-only platform observer. Uses the existing main
 * Looper, not a worker/Runner. No shell, thermometry, permission or task restart. */
public final class AndroidRuntimeThermalObservation implements AutoCloseable {
    private static final long FRESH_READ_INTERVAL_MILLIS=5000;
    private final PowerManager power;
    private final Handler loop;
    private final LongSupplier elapsed;
    private final RuntimeLogObservationPolicy policy;
    private final AtomicBoolean closed=new AtomicBoolean(),refreshQueued=new AtomicBoolean();
    private final PowerManager.OnThermalStatusChangedListener listener;
    private final Runnable coolingRecheck=this::readCurrent;
    private volatile long lastRead=-1;
    private volatile boolean registrationAttempted,registered,removalAttempted,removalConfirmed;

    public static AndroidRuntimeThermalObservation open(Context context){
        PowerManager power=null;try{power=context.getSystemService(PowerManager.class);}catch(RuntimeException unavailable){/* Optional OS facts must not disable an otherwise valid runtime. */}
        return new AndroidRuntimeThermalObservation(power,new Handler(Looper.getMainLooper()),SystemClock::elapsedRealtime);
    }
    AndroidRuntimeThermalObservation(PowerManager power,Handler loop,LongSupplier elapsed){
        this.power=power;this.loop=loop;this.elapsed=elapsed;policy=new RuntimeLogObservationPolicy(elapsed);
        listener=ignored->queueFreshRead();
        loop.post(()->{
            if(closed.get())return;
            if(power!=null){
                registrationAttempted=true;
                try{power.addThermalStatusListener(listener);registered=true;}
                catch(RuntimeException unavailable){/* No retries of an uncertain registration; facts remain unknown. */}
            }
            if(closed.get()){removeOriginalListener();return;}readCurrent();
        });
    }
    /** All actual reads run serially on this source's main Handler. A delayed
     * callback's stale numeric argument can never overwrite a newer reading. */
    private void readCurrent(){
        if(closed.get())return;loop.removeCallbacks(coolingRecheck);
        var observation=policy.beginObservation();Integer current=null;
        try{if(registered)current=power.getCurrentThermalStatus();}catch(RuntimeException unavailable){/* Unknown is not NONE. */}
        lastRead=elapsed.getAsLong();policy.observe(observation,current);
        long after=policy.freshColdRecheckAfterMillis();
        if(!closed.get()&&after>=0)loop.postDelayed(coolingRecheck,Math.max(1,after));
    }
    public JSONObject snapshotForHost()throws Exception {
        long now=elapsed.getAsLong();
        // A visible original log reader can recover from a temporary failed OS
        // read even if no new thermal event is emitted. At most one queued read.
        if(lastRead<0||now<lastRead||now-lastRead>=FRESH_READ_INTERVAL_MILLIS)queueFreshRead();
        return policy.snapshot().put("nativeListenerRegistrationConfirmed",registered).put("listenerRemovalConfirmed",removalConfirmed);
    }
    private void queueFreshRead(){if(!closed.get()&&refreshQueued.compareAndSet(false,true))if(!loop.post(()->{refreshQueued.set(false);readCurrent();}))refreshQueued.set(false);}
    private void removeOriginalListener(){
        if(removalAttempted)return;removalAttempted=true;
        if(!registrationAttempted){removalConfirmed=true;return;}
        try{power.removeThermalStatusListener(listener);registered=false;removalConfirmed=true;}
        catch(RuntimeException unconfirmed){/* Closed generation still rejects every callback; do not claim removal. */}
    }
    @Override public void close(){
        if(!closed.compareAndSet(false,true))return;policy.close();loop.removeCallbacksAndMessages(null);
        // Serialized behind any in-flight registration, never block a stop/UI
        // caller on that system-service call. Confirmation remains an actual fact.
        loop.post(this::removeOriginalListener);
    }
}
