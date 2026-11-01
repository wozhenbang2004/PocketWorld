package dev.pocketengine.features.capabilities;

import android.os.Handler;
import android.os.Looper;
import dev.pocketengine.BrowserActivity;
import dev.pocketengine.core.RunControl;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.json.JSONObject;

/** Main-queue admission around the existing BrowserActivity executor; no new DOM implementation. */
public final class AndroidBrowserCall implements ModelCapabilityAdapters.LiveBrowserCall {
    interface Endpoint {
        void execute(String command,JSONObject input,CompletableFuture<JSONObject> result);
        void timedOut(CompletableFuture<JSONObject> result);
    }
    interface UiQueue {boolean isUiThread();void post(Runnable command);Endpoint current();}
    private final UiQueue ui;
    public AndroidBrowserCall(){this(new AndroidQueue());}
    AndroidBrowserCall(UiQueue ui){this.ui=Objects.requireNonNull(ui);}
    @Override public JSONObject invokeForHost(String command,JSONObject input,RunControl liveControl,ModelCapabilityAdapters.ResourceCheck admission)throws Exception {
        if(ui.isUiThread())throw new IllegalStateException("浏览器能力必须从现有有界worker调用");
        CompletableFuture<JSONObject> result=new CompletableFuture<>();JSONObject args=new JSONObject(input.toString());
        Runnable stop=()->{result.completeExceptionally(new InterruptedException("Browser run stopped"));requestStop(args);};
        liveControl.onCancel(stop);
        try{
            liveControl.check();admission.check();
            ui.post(()->{
                if(result.isDone())return;
                try{
                    // This runs after queueing, immediately before the real executor. A worker's
                    // earlier authority check alone cannot authorize a delayed main-queue command.
                    liveControl.check();if(liveControl.isPaused())throw new SecurityException("任务已暂停");admission.check();
                    Endpoint browser=ui.current();if(browser==null)throw new IllegalStateException("请先打开内置浏览器");
                    browser.execute(command,args,result);
                }catch(Exception rejected){result.completeExceptionally(rejected);}
            });
            long deadline=System.nanoTime()+8_000_000_000L;
            for(;;){
                try{return result.get(100,TimeUnit.MILLISECONDS);}
                catch(TimeoutException pending){
                    liveControl.check();if(liveControl.isPaused())throw new SecurityException("任务已暂停");admission.check();
                    if(System.nanoTime()>=deadline){result.completeExceptionally(new TimeoutException("Browser receipt timed out"));ui.post(()->{Endpoint current=ui.current();if(current!=null)current.timedOut(result);});throw new TimeoutException("Browser receipt timed out");}
                }
            }
        }catch(ExecutionException failure){Throwable cause=failure.getCause();if(cause instanceof Exception)throw (Exception)cause;throw new IllegalStateException("浏览器执行失败");}
        catch(Exception stopped){result.completeExceptionally(stopped);requestStop(args);throw stopped;}
        finally{liveControl.removeCancelAction(stop);}
    }
    private void requestStop(JSONObject args){
        try{ui.post(()->{Endpoint current=ui.current();if(current==null)return;try{
            // Old exact lease credentials cannot cancel a replacement task/run/lease.
            JSONObject identity=new JSONObject();for(String key:new String[]{"taskId","runId","leaseId","profileId"})identity.put(key,args.get(key));
            for(String key:new String[]{"profileRevision","browserSessionId"})if(args.has(key))identity.put(key,args.get(key));
            current.execute("cancel",identity,new CompletableFuture<>());
        }catch(Exception ignored){}});}catch(RuntimeException ignored){}
    }
    private static final class AndroidQueue implements UiQueue {
        private final Handler handler=new Handler(Looper.getMainLooper());
        public boolean isUiThread(){return Looper.myLooper()==Looper.getMainLooper();}
        public void post(Runnable command){if(!handler.post(command))throw new IllegalStateException("Browser main queue unavailable");}
        public Endpoint current(){BrowserActivity current=BrowserActivity.current();if(current==null)return null;return new Endpoint(){
            public void execute(String command,JSONObject input,CompletableFuture<JSONObject> result){current.execute(command,input,result);}
            public void timedOut(CompletableFuture<JSONObject> result){current.commandTimedOut(result);}
        };}
    }
}
