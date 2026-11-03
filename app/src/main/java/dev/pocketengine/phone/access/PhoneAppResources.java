package dev.pocketengine.phone.access;

import dev.pocketengine.core.RunControl;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/** ExecutionResources-owned process fence. TaskStore and the original RunControl remain the task authority. */
public final class PhoneAppResources {
    public enum Result { ACQUIRED,BUSY,DEADLOCK,TIMED_OUT }
    @FunctionalInterface public interface ActualUse {boolean free(AppCopyKey key,String owner)throws Exception;}
    private final Map<AppCopyKey,String> held=new HashMap<>();
    private final Map<String,AppCopyKey> waiting=new HashMap<>();
    /** Caller verifies its original phone lease immediately before and after this admission. */
    public Result reserve(String owner,AppCopyKey key,ActualUse actual)throws Exception {
        owner(owner);if(!actual.free(key,owner))return Result.BUSY;
        synchronized(this){String holder=held.get(key);if(holder!=null&&!owner.equals(holder))return Result.BUSY;
            held.put(key,owner);waiting.remove(owner);return Result.ACQUIRED;}
    }
    public Result await(String owner,AppCopyKey key,long timeoutMs,RunControl control,ActualUse actual)throws Exception {
        owner(owner);if(timeoutMs<1||timeoutMs>180000)throw new IllegalArgumentException("app_wait_out_of_range");
        long revision=control.pauseRevision(),until=System.nanoTime()+timeoutMs*1_000_000L;
        synchronized(this){if(cycle(owner,key))return Result.DEADLOCK;waiting.put(owner,key);}
        try{for(;;){control.check();if(control.isPaused()||control.pauseRevision()!=revision)throw new SecurityException("app_wait_original_control_changed");
            Result result=reserve(owner,key,actual);if(result==Result.ACQUIRED)return result;
            synchronized(this){if(cycle(owner,key))return Result.DEADLOCK;long remaining=until-System.nanoTime();if(remaining<=0)return Result.TIMED_OUT;waiting.put(owner,key);wait(Math.max(1,Math.min(100,remaining/1_000_000L)));}
        }}finally{synchronized(this){waiting.remove(owner);}}
    }
    public synchronized void release(String owner,AppCopyKey key){owner(owner);if(owner.equals(held.get(key))){held.remove(key);notifyAll();}}
    public synchronized void releaseOwner(String owner){owner(owner);waiting.remove(owner);if(held.values().removeIf(owner::equals))notifyAll();}
    public synchronized String holder(AppCopyKey key){return held.get(key);}
    public synchronized int heldCount(){return held.size();}
    private boolean cycle(String requester,AppCopyKey desired){String holder=held.get(desired);HashSet<String> seen=new HashSet<>();
        while(holder!=null&&!holder.equals(requester)&&seen.add(holder)){AppCopyKey next=waiting.get(holder);if(next==null)return false;holder=held.get(next);}return requester.equals(holder)&&!requester.equals(held.get(desired));}
    private static void owner(String owner){if(owner==null||owner.isBlank()||owner.length()>200)throw new IllegalArgumentException("invalid_resource_owner");}
}
