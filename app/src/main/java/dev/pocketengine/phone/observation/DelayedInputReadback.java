package dev.pocketengine.phone.observation;

import android.os.Handler;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** One read-only sample of the exact retained native node. Never repeats an input. */
public final class DelayedInputReadback {
    private final CompletableFuture<JSONObject> result=new CompletableFuture<>();
    private boolean scheduled;
    public void schedule(Handler handler,PhoneActionEffects.Reader reader,Runnable release) {
        schedule(handler,Runnable::run,reader,release);
    }
    /** The UI timer only hands off; potentially blocking accessibility reads run in the port's bounded pool. */
    public void schedule(Handler handler,java.util.concurrent.Executor worker,PhoneActionEffects.Reader reader,Runnable release) {
        scheduled=true;AtomicBoolean freed=new AtomicBoolean();
        Runnable sample=()->{try{result.complete(reader.read().put("phase","delayed_after_dispatch").put("minimumDelayMs",600));}
            catch(Exception unavailable){try{result.complete(PhoneActionEffects.unavailableReadback("original_native_node_unavailable"));}catch(Exception impossible){result.completeExceptionally(impossible);}}
            finally{if(freed.compareAndSet(false,true))release.run();}};
        Runnable handoff=()->{try{worker.execute(sample);}catch(java.util.concurrent.RejectedExecutionException busy){
            if(freed.compareAndSet(false,true))release.run();result.completeExceptionally(busy);
        }};
        if(!handler.postDelayed(handoff,600)){if(freed.compareAndSet(false,true))release.run();result.completeExceptionally(new IllegalStateException("main_handler_closed"));}
    }
    /** Called on the worker, never on the Android UI thread or under RunControl's monitor. */
    public JSONObject attach(JSONObject receipt)throws Exception {
        if(!scheduled)return receipt;
        JSONObject delayed;
        try{delayed=result.get(1500,TimeUnit.MILLISECONDS);}
        catch(Exception unavailable){if(unavailable instanceof InterruptedException)Thread.currentThread().interrupt();delayed=PhoneActionEffects.unavailableReadback("delayed_native_readback_unavailable");}
        return receipt.put("delayedTextReadback",delayed).put("automaticReplayAllowed",false);
    }
}
