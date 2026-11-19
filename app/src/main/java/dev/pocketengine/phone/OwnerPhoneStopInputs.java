package dev.pocketengine.phone;

import dev.pocketengine.core.ExecutionResources;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** An explicit owner's stop must not sit behind a document provider, remote listing or other screen.
 * Holds only in-flight delivery and an immutable reply. No backlog, timer, retries or task authority. */
public final class OwnerPhoneStopInputs {
    /** This click did not enter the stop worker. An earlier owner request for the same run may still be pending. */
    public static final class NotDispatched extends RejectedExecutionException {
        private NotDispatched(String message){super(message);}
    }
    private record Key(long generation, String task, String run, int epoch, dev.pocketengine.core.RunControl source, boolean overlay) {}
    private static final class Input {
        final CompletableFuture<String> result=new CompletableFuture<>();
        int listeners;
    }
    private final Map<Key,Input> active=new HashMap<>();
    private final ThreadPoolExecutor lane=new ThreadPoolExecutor(0,ExecutionResources.MAX_BACKGROUND_PHONE_RUNS,
            15,TimeUnit.SECONDS,new SynchronousQueue<>(),work->{
                Thread thread=new Thread(work,"owner-phone-stop");thread.setDaemon(true);return thread;
            });

    public synchronized CompletionStage<String> submit(long generation,String id,String run,int epoch,dev.pocketengine.core.RunControl source,boolean overlay,Callable<String> stop) {
        if(id==null||id.isBlank()||run==null||run.isBlank()||epoch<1||source==null||!source.matchesHostRun(id,run,epoch)||stop==null)
            throw new IllegalArgumentException("没有原手机执行身份，这次没有提交停手。");
        Key key=new Key(generation,id,run,epoch,source,overlay);Input previous=active.get(key);
        if(previous!=null){
            if(previous.listeners>=8)throw new NotDispatched("这轮已有停手在处理，本次没有另发。");
            previous.listeners++;return previous.result.thenApply(value->value);
        }
        Input input=new Input();input.listeners=1;active.put(key,input);
        try{lane.execute(()->{
            try{input.result.complete(stop.call());}
            catch(Exception failure){input.result.completeExceptionally(failure);}
            catch(Error fatal){input.result.completeExceptionally(fatal);throw fatal;}
            finally{synchronized(OwnerPhoneStopInputs.this){active.remove(key,input);}}
        });}catch(RejectedExecutionException unavailable){
            active.remove(key,input);
            throw new NotDispatched("当前停手入口忙，这次没有提交。如果原任务的 Android 通知还在，可从该通知停止。");
        }
        // An observer disappearing cannot cancel a stop that the owner explicitly sent.
        return input.result.thenApply(value->value);
    }
}
