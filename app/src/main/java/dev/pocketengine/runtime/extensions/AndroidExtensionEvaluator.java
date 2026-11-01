package dev.pocketengine.runtime.extensions;

import com.google.common.util.concurrent.ListenableFuture;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.skills.AndroidJavascriptSandboxRuntime;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/**
 * One run's adapter over the Application-owned AndroidX isolated JS service. It creates no
 * sandbox, WebView, app-UID interpreter, filesystem/network callback, worker or scheduler.
 */
public final class AndroidExtensionEvaluator implements ExtensionRuntime.Evaluator {
    interface Lease extends AutoCloseable {
        ListenableFuture<String> initialize(JSONObject request)throws Exception;
        ListenableFuture<String> evaluate(byte[] source)throws Exception;
        @Override void close();
    }
    interface Sessions {Lease open()throws Exception;}
    private final Sessions sessions;
    private final AtomicReference<Owned> active=new AtomicReference<>();
    private volatile boolean closed;

    /** Shared runtime must already have completed native connect()/provider verification. */
    public AndroidExtensionEvaluator(AndroidJavascriptSandboxRuntime sharedRuntime){
        java.util.Objects.requireNonNull(sharedRuntime);
        sessions=()->{
            sharedRuntime.describe();AndroidJavascriptSandboxRuntime.Session session=sharedRuntime.createSession(65536);
            return new Lease(){
                @Override public ListenableFuture<String> initialize(JSONObject request)throws Exception{return session.initialize(List.of(),Map.of(ExtensionScript.INPUT,request.toString().getBytes(StandardCharsets.UTF_8)));}
                @Override public ListenableFuture<String> evaluate(byte[] source)throws Exception{return session.evaluateExact(source);}
                @Override public void close(){session.close();}
            };
        };
    }
    AndroidExtensionEvaluator(Sessions sessions){this.sessions=java.util.Objects.requireNonNull(sessions);}

    @Override public String evaluate(byte[] script,JSONObject request,int maxMillis,RunControl control,ExtensionRuntime.CurrentCheck current)throws Exception {
        if(script==null||script.length==0||script.length>ExtensionProgram.MAX_SOURCE_BYTES+8192||request==null||maxMillis<50||maxMillis>2000)throw new IllegalArgumentException("扩展执行输入无效");
        if(closed||active.get()!=null)throw new IllegalStateException("原扩展隔离实例尚未释放");
        control.check();current.check();long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(maxMillis);
        Owned owned=new Owned(sessions.open());
        if(!active.compareAndSet(null,owned)){owned.close();throw new IllegalStateException("原扩展隔离实例仍在执行");}
        Runnable cancel=owned::close;control.onCancel(cancel);Exception failure=null;
        try{
            if(closed)throw new InterruptedException("本轮扩展已关闭");
            control.check();current.check();await(owned.lease.initialize(request),deadline,control);
            // Initializing data is asynchronous; old approval/source/run cannot authorize eval.
            control.check();current.check();if(closed)throw new InterruptedException("本轮扩展已关闭");
            if(System.nanoTime()>=deadline)throw new TimeoutException();
            String result=await(owned.lease.evaluate(script),deadline,control);control.check();current.check();return result;
        }catch(ExecutionException rejected){failure=new IllegalStateException("扩展JavaScript回调执行失败；没有调用任何宿主能力");throw failure;}
        catch(TimeoutException expired){failure=new IllegalStateException("扩展回调超时，已请求终止原隔离实例；不会自动重试");throw failure;}
        catch(Exception error){failure=error;throw error;}
        finally{
            control.removeCancelAction(cancel);
            try{owned.close();}catch(RuntimeException notTerminated){if(failure!=null)failure.addSuppressed(notTerminated);else throw notTerminated;}
        }
    }
    private static String await(ListenableFuture<String> future,long deadline,RunControl control)throws Exception {
        if(future==null)throw new IllegalStateException("扩展运行时没有返回异步回执");
        while(true){control.check();long left=deadline-System.nanoTime();if(left<=0)throw new TimeoutException();try{return future.get(Math.min(left,TimeUnit.MILLISECONDS.toNanos(100)),TimeUnit.NANOSECONDS);}catch(TimeoutException pending){if(System.nanoTime()>=deadline)throw pending;}}
    }
    private final class Owned {
        final Lease lease;private boolean terminated;
        Owned(Lease lease){this.lease=java.util.Objects.requireNonNull(lease);}
        synchronized void close(){
            if(terminated)return;
            try{lease.close();}catch(RuntimeException uncertain){throw new IllegalStateException("扩展隔离实例未确认终止；保持占用，请核对原实例后释放");}
            terminated=true;active.compareAndSet(this,null);
        }
    }
    /** Idempotent, including retry of a previously failed owned-isolate termination. */
    @Override public void close(){closed=true;Owned owned=active.get();if(owned!=null)owned.close();}
}
