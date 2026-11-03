package dev.pocketengine.features.local.runtime.android;

import android.content.Context;
import android.app.ActivityManager;
import android.content.ComponentCallbacks2;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import dev.pocketengine.features.local.runtime.RuntimeLogObservationPolicy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.json.JSONObject;

/** One original runtime's read-only platform observer. Thermal callbacks use
 * the existing main Looper; optional memory reads have one zero-queue lane so
 * a blocked system service cannot block stop. No shell, Runner or task restart. */
public final class AndroidRuntimeThermalObservation implements AutoCloseable {
    private static final long FRESH_READ_INTERVAL_MILLIS=5000;
    private final PowerManager power;
    private final Context context;
    private final java.util.function.Consumer<ActivityManager.MemoryInfo> memoryRead;
    private final Handler loop;
    private final LongSupplier elapsed;
    private final RuntimeLogObservationPolicy policy;
    private final AtomicBoolean closed=new AtomicBoolean(),refreshQueued=new AtomicBoolean();
    private final AtomicBoolean memoryReading=new AtomicBoolean();
    private final java.util.concurrent.ThreadPoolExecutor memoryReads=new java.util.concurrent.ThreadPoolExecutor(0,1,0,java.util.concurrent.TimeUnit.MILLISECONDS,new java.util.concurrent.SynchronousQueue<>(),r->{Thread t=new Thread(r,"runtime-memory-observation");t.setDaemon(true);return t;});
    private final PowerManager.OnThermalStatusChangedListener listener;
    private final Runnable coolingRecheck=this::readCurrent;
    private final Runnable memoryRetry=this::queueFreshRead;
    private final ComponentCallbacks2 memoryListener=new ComponentCallbacks2(){
        @Override public void onTrimMemory(int level){memoryChanged();}
        @Override public void onLowMemory(){memoryChanged();}
        @Override public void onConfigurationChanged(Configuration configuration){}
    };
    private volatile long lastRead=-1;
    private volatile boolean registrationAttempted,registered,removalAttempted,removalConfirmed;
    private volatile boolean memoryRegistrationAttempted,memoryRegistered,memoryRemovalConfirmed;

    public static AndroidRuntimeThermalObservation open(Context context){
        PowerManager power=null;try{power=context.getSystemService(PowerManager.class);}catch(RuntimeException unavailable){/* Optional OS facts must not disable an otherwise valid runtime. */}
        ActivityManager memory=null;try{memory=context.getSystemService(ActivityManager.class);}catch(RuntimeException unavailable){/* Unknown stays conservative. */}
        return new AndroidRuntimeThermalObservation(context,power,memory,new Handler(Looper.getMainLooper()),SystemClock::elapsedRealtime);
    }
    AndroidRuntimeThermalObservation(PowerManager power,Handler loop,LongSupplier elapsed){
        this(null,power,null,loop,elapsed);
    }
    AndroidRuntimeThermalObservation(Context context,PowerManager power,ActivityManager memory,Handler loop,LongSupplier elapsed){
        this(context,power,loop,elapsed,memory==null?null:memory::getMemoryInfo);
    }
    AndroidRuntimeThermalObservation(Context context,PowerManager power,Handler loop,LongSupplier elapsed,java.util.function.Consumer<ActivityManager.MemoryInfo> memoryRead){
        this.context=context;this.power=power;this.memoryRead=memoryRead;this.loop=loop;this.elapsed=elapsed;policy=new RuntimeLogObservationPolicy(elapsed);
        listener=ignored->queueFreshRead();
        loop.post(()->{
            if(closed.get())return;
            if(power!=null){
                registrationAttempted=true;
                try{power.addThermalStatusListener(listener);registered=true;}
                catch(RuntimeException unavailable){/* No retries of an uncertain registration; facts remain unknown. */}
            }
            if(context!=null){memoryRegistrationAttempted=true;try{context.registerComponentCallbacks(memoryListener);memoryRegistered=true;}catch(RuntimeException unavailable){/* Do not retry uncertain registration. */}}
            if(closed.get()){removeOriginalListener();return;}readCurrent();
        });
    }
    /** Thermal reads run serially on this source's main Handler. A delayed
     * callback's stale numeric argument can never overwrite a newer reading. */
    private void readCurrent(){
        if(closed.get())return;loop.removeCallbacks(coolingRecheck);
        var observation=policy.beginObservation();Integer current=null;
        try{if(registered)current=power.getCurrentThermalStatus();}catch(RuntimeException unavailable){/* Unknown is not NONE. */}
        lastRead=elapsed.getAsLong();policy.observe(observation,current);readMemory();scheduleCoolingRecheck();
    }
    private void scheduleCoolingRecheck(){
        loop.removeCallbacks(coolingRecheck);
        long after=policy.freshColdRecheckAfterMillis();
        // A due cooldown may still await the single in-flight memory read.
        // Never spin the main Looper every millisecond while that call is blocked.
        if(!closed.get()&&after>=0)loop.postDelayed(coolingRecheck,after==0?FRESH_READ_INTERVAL_MILLIS:Math.min(after,FRESH_READ_INTERVAL_MILLIS));
    }
    private void memoryChanged(){if(closed.get())return;policy.invalidateMemory();queueFreshRead();}
    private void readMemory(){
        if(closed.get()||!memoryReading.compareAndSet(false,true))return;
        var observation=policy.beginMemoryObservation();
        try{memoryReads.execute(()->{
            Boolean low=null;Long available=null,total=null,threshold=null;
            try{if(memoryRead!=null){ActivityManager.MemoryInfo info=new ActivityManager.MemoryInfo();memoryRead.accept(info);low=info.lowMemory;available=info.availMem;total=info.totalMem;threshold=info.threshold;}}
            catch(RuntimeException unavailable){/* A failed/late OS read is never healthy memory. */}
            finally{
                boolean accepted=policy.observeMemory(observation,low,available,total,threshold);memoryReading.set(false);
                if(!closed.get()){
                    loop.post(this::scheduleCoolingRecheck);
                    // A callback invalidating this in-flight sample still gets
                    // one fresh read after the original worker actually returns.
                    if(!accepted)retryMemoryRead();
                }
            }
        });}catch(java.util.concurrent.RejectedExecutionException busy){memoryReading.set(false);retryMemoryRead();}
    }
    private synchronized void retryMemoryRead(){loop.removeCallbacks(memoryRetry);if(!closed.get())loop.postDelayed(memoryRetry,FRESH_READ_INTERVAL_MILLIS);}
    public JSONObject snapshotForHost()throws Exception {
        long now=elapsed.getAsLong();
        // A visible original log reader can recover from a temporary failed OS
        // read even if no new thermal event is emitted. At most one queued read.
        if(lastRead<0||now<lastRead||now-lastRead>=FRESH_READ_INTERVAL_MILLIS)queueFreshRead();
        return cachedSnapshotForHost();
    }
    /** The diagnostic allowlist reads this cache without issuing OS reads. */
    public JSONObject cachedSnapshotForHost()throws Exception{return policy.snapshot().put("nativeListenerRegistrationConfirmed",registered).put("listenerRemovalConfirmed",removalConfirmed).put("memoryListenerRegistrationConfirmed",memoryRegistered).put("memoryListenerRemovalConfirmed",memoryRemovalConfirmed);}
    private void queueFreshRead(){if(!closed.get()&&refreshQueued.compareAndSet(false,true))if(!loop.post(()->{refreshQueued.set(false);readCurrent();}))refreshQueued.set(false);}
    private void removeOriginalListener(){
        if(removalAttempted)return;removalAttempted=true;
        if(!registrationAttempted)removalConfirmed=true;
        else try{power.removeThermalStatusListener(listener);registered=false;removalConfirmed=true;}
        catch(RuntimeException unconfirmed){/* Closed generation still rejects every callback; do not claim removal. */}
        if(!memoryRegistrationAttempted)memoryRemovalConfirmed=true;
        else try{context.unregisterComponentCallbacks(memoryListener);memoryRegistered=false;memoryRemovalConfirmed=true;}catch(RuntimeException unconfirmed){/* Removal is separate from closed-source currentness. */}
    }
    @Override public void close(){
        if(!closed.compareAndSet(false,true))return;policy.close();memoryReads.shutdownNow();loop.removeCallbacksAndMessages(null);
        // Serialized behind any in-flight registration, never block a stop/UI
        // caller on that system-service call. Confirmation remains an actual fact.
        loop.post(this::removeOriginalListener);
    }
}
