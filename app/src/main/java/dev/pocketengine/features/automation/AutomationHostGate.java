package dev.pocketengine.features.automation;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Bounds waiting, not effects. A timed-out host remains single-flight until it actually exits. */
final class AutomationHostGate {
    static final long RETRY_MILLIS=300000;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"pocket-automation-host");t.setDaemon(true);return t;});
    private Call<?> active;
    private long retryAt;
    private static final class Call<T> {
        final CompletableFuture<T> result=new CompletableFuture<>();
        Thread thread;boolean cancelled;
    }
    synchronized boolean onCallbackThread(){return active!=null&&active.thread==Thread.currentThread();}
    synchronized long retryAt(long now){return Math.max(retryAt,now+RETRY_MILLIS);}

    /** Null means unavailable/failure; callbacks must return a non-null value. No queued retries. */
    <T> T call(Callable<T> callback,long now,long timeoutMillis){
        Call<T> call=new Call<>();
        synchronized(this){if(active!=null||now<retryAt)return null;active=call;}
        worker.execute(()->{
            T value=null;Exception error=null;
            try{
                synchronized(this){if(call.cancelled)return;call.thread=Thread.currentThread();}
                value=callback.call();
            }catch(Exception failure){
                error=failure;
                synchronized(this){retryAt=Math.max(retryAt,now+RETRY_MILLIS);}
            }finally{
                synchronized(this){call.thread=null;if(active==call)active=null;}
                // Clear cancellation before this daemon is reused; then publish completion so
                // the enclosing alarm pass can safely ask for the next time immediately.
                Thread.interrupted();
            }
            if(error==null)call.result.complete(value);else call.result.completeExceptionally(error);
        });
        try{return call.result.get(timeoutMillis,TimeUnit.MILLISECONDS);}
        catch(Exception unavailable){
            synchronized(this){call.cancelled=true;retryAt=Math.max(retryAt,now+RETRY_MILLIS);if(call.thread!=null)call.thread.interrupt();}
            if(unavailable instanceof InterruptedException)Thread.currentThread().interrupt();
            return null;
        }
    }
}
