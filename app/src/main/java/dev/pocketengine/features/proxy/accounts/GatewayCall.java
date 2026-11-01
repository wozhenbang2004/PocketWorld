package dev.pocketengine.features.proxy.accounts;

import java.util.concurrent.TimeUnit;
import okhttp3.Call;

/** A single caller-owned cancellation/deadline, shared by refresh and the following request. */
public final class GatewayCall implements AutoCloseable {
    private final long deadline;
    private boolean cancelled;
    private Call call;
    public GatewayCall(long timeoutMillis) {
        if(timeoutMillis<1||timeoutMillis>120_000)throw new IllegalArgumentException("INVALID_DEADLINE");
        deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    }
    public synchronized void check() throws GatewayFailure {
        if(cancelled||Thread.currentThread().isInterrupted())throw new GatewayFailure("CANCELLED");
        if(System.nanoTime()>=deadline)throw new GatewayFailure("DEADLINE_EXCEEDED");
    }
    public synchronized void attach(Call next)throws GatewayFailure {
        check(); if(call!=null)throw new GatewayFailure("CALL_ALREADY_ACTIVE");
        call=next; next.timeout().timeout(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
    }
    public synchronized void detach(Call old) { if(call==old)call=null; }
    public synchronized void cancel() {cancelled=true;if(call!=null)call.cancel();}
    @Override public void close(){cancel();}
}
