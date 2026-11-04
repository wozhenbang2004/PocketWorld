package dev.pocketengine.runtime;

import dev.pocketengine.core.RunControl;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process-local physical-call fence only, never a task/grant/usage registry.
 * Empty preferences keep existing parallelism. Account/bridge limits remain independent.
 * Old epochs retain their occupied slot until the actual transport has exited. */
final class ModelCallAdmission {
    // Bounded blocked callers, not a new global limit on active model calls or a measured device optimum.
    private final int maximumWaiters;
    ModelCallAdmission(){this(64);}
    ModelCallAdmission(int maximumWaiters){if(maximumWaiters<1||maximumWaiters>64)throw new IllegalArgumentException("Invalid bounded native wait queue");this.maximumWaiters=maximumWaiters;}
    private final Map<Object,Active> active=new HashMap<>();
    private final ArrayDeque<Waiter> waiting=new ArrayDeque<>();
    static final class Refused extends Exception {
        final boolean deadline;
        Refused(String message,boolean deadline){super(message);this.deadline=deadline;}
    }
    private static final class Active {int count,limit;Active(int limit){this.limit=limit;}}
    private static final class Waiter {final Object key;final int limit;Waiter(Object key,int limit){this.key=key;this.limit=limit;}}
    final class Permit implements AutoCloseable {
        private final Object key;
        private final AtomicBoolean closed=new AtomicBoolean();
        private Permit(Object key){this.key=key;}
        @Override public void close(){if(closed.compareAndSet(false,true))synchronized(ModelCallAdmission.this){Active held=active.get(key);if(--held.count==0)active.remove(key);ModelCallAdmission.this.notifyAll();}}
    }
    Permit enter(RunControl original,String originalTask,long deadlineAt)throws Exception {
        return enter(original,originalTask,deadlineAt,Integer.MAX_VALUE);
    }
    Permit enter(RunControl original,String originalTask,long deadlineAt,int inheritedLimit)throws Exception {
        return enter(original,originalTask,deadlineAt,inheritedLimit,null);
    }
    Permit enter(RunControl original,String originalTask,long deadlineAt,int inheritedLimit,RunControl.ModelRequest modelRequest)throws Exception {
        if(inheritedLimit!=Integer.MAX_VALUE&&(inheritedLimit<1||inheritedLimit>2))throw new SecurityException("Invalid original model resource scope");
        Object key=originalTask.isEmpty()?original:originalTask;
        long pause=original.pauseRevision();
        Waiter request=new Waiter(key,Math.min(original.modelConcurrencyLimit(),inheritedLimit));
        Runnable wake=()->{synchronized(this){notifyAll();}};
        AutoCloseable cancellation;
        if(modelRequest!=null)cancellation=modelRequest.onCancel(wake);
        else {original.onCancel(wake);cancellation=()->original.removeCancelAction(wake);}
        try {
            check(original,modelRequest);current(original,pause,deadlineAt);
            Permit immediate;
            synchronized(this){
                current(original,pause,deadlineAt);immediate=admit(request);
                if(immediate==null){if(waiting.size()>=maximumWaiters)throw new Refused("本任务的模型并发等待队列已满，尚未派发请求；请核对原任务后继续。",false);waiting.addLast(request);}
            }
            if(immediate!=null)try{check(original,modelRequest);current(original,pause,deadlineAt);return immediate;}catch(Exception|Error revoked){immediate.close();throw revoked;}
            while(true){
                // May enter TaskStore/other native source checks. Never execute this under our monitor.
                check(original,modelRequest);current(original,pause,deadlineAt);
                Permit admitted=null;
                synchronized(this){
                    current(original,pause,deadlineAt);
                    admitted=admit(request);
                    if(admitted==null)wait(Math.min(100,Math.max(1,deadlineAt-System.currentTimeMillis())));
                }
                if(admitted!=null)try{check(original,modelRequest);current(original,pause,deadlineAt);return admitted;}catch(Exception|Error revoked){admitted.close();throw revoked;}
            }
        }finally{cancellation.close();synchronized(this){waiting.remove(request);notifyAll();}}
    }
    private static void check(RunControl original,RunControl.ModelRequest request)throws InterruptedException {if(request==null)original.check();else request.check();}
    // Called only under this monitor; no original authority/ledger callback is made here.
    private Permit admit(Waiter request){
        for(Waiter prior:waiting){if(prior==request)break;if(prior.key.equals(request.key))return null;}
        Active held=active.get(request.key);if(held!=null&&held.count>=Math.min(held.limit,request.limit))return null;
        if(held==null){held=new Active(request.limit);active.put(request.key,held);}else held.limit=Math.min(held.limit,request.limit);
        held.count++;waiting.remove(request);notifyAll();return new Permit(request.key);
    }
    private static void current(RunControl original,long pause,long deadlineAt)throws Refused {
        if(original.isPaused()||original.pauseRevision()!=pause)throw new Refused("排队期间原任务暂停状态已变化，尚未派发本次请求；请重新核对原任务。",false);
        if(System.currentTimeMillis()>=deadlineAt)throw new Refused("等待本任务模型并发的原请求总期限已到，尚未派发；等待没有续期。",true);
    }
    synchronized int waitingCount(){return waiting.size();}
    synchronized int activeCount(){int count=0;for(Active held:active.values())count+=held.count;return count;}
}
