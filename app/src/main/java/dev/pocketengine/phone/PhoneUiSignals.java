package dev.pocketengine.phone;

import dev.pocketengine.core.RunControl;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/** Bounded text-free accessibility-event hints. Events wake reads; they never prove UI state or authorize input. */
public final class PhoneUiSignals {
    public static final class Stamp {
        public final String serviceSession;public final long generation,lastEventAt;public final boolean closed;
        private Stamp(String session,long generation,long at,boolean closed){serviceSession=session;this.generation=generation;lastEventAt=at;this.closed=closed;}
        public boolean same(Stamp other){return other!=null&&serviceSession.equals(other.serviceSession)&&generation==other.generation&&closed==other.closed;}
    }
    private static final class Event {final long generation,at;Event(long generation,long at){this.generation=generation;this.at=at;}}
    private final String session;private final LongSupplier uptime;
    private final Map<Integer,Event> windows=new LinkedHashMap<>();
    private long generation;private Event global=new Event(0,0);private boolean closed;
    public PhoneUiSignals(String serviceSession,LongSupplier uptime){if(serviceSession==null||serviceSession.isEmpty())throw new IllegalArgumentException("native service session required");session=serviceSession;this.uptime=java.util.Objects.requireNonNull(uptime);}
    public synchronized void record(int windowId){if(closed)return;Event event=new Event(++generation,uptime.getAsLong());if(windowId<0)global=event;else{windows.remove(windowId);windows.put(windowId,event);while(windows.size()>64)windows.remove(windows.keySet().iterator().next());}notifyAll();}
    public synchronized Stamp snapshot(Set<Integer> scope){long sequence=global.generation,at=global.at;
        if(scope.isEmpty()){for(Event e:windows.values())if(e.generation>sequence){sequence=e.generation;at=e.at;}}
        else for(Integer id:scope){Event e=windows.get(id);if(e!=null&&e.generation>sequence){sequence=e.generation;at=e.at;}}
        return new Stamp(session,sequence,at,closed);
    }
    /** No RunControl/native callback while holding this monitor; never waits for an owner resume. */
    public void awaitChange(Stamp before,Set<Integer> scope,long maxMs,RunControl control,long pauseRevision)throws Exception {
        if(maxMs<0||maxMs>250)throw new IllegalArgumentException("bounded event wait required");long until=System.nanoTime()+maxMs*1_000_000L;
        for(;;){control.check();if(control.isPaused()||control.pauseRevision()!=pauseRevision)return;
            synchronized(this){if(!snapshot(scope).same(before)||closed)return;long remaining=until-System.nanoTime();if(remaining<=0)return;wait(Math.max(1,Math.min(50,remaining/1_000_000L)));}
        }
    }
    public synchronized void close(){if(closed)return;closed=true;generation++;notifyAll();}
    public synchronized int retainedWindowCount(){return windows.size();}
}
