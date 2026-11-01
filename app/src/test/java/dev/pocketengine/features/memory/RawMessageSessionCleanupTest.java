package dev.pocketengine.features.memory;

import dev.pocketengine.core.CapabilitySnapshot;
import dev.pocketengine.core.RunControl;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Native lifecycle contract only; no SQLite, Android device, model or provider claim. */
public final class RawMessageSessionCleanupTest {
    private static final class Fixture {
        final String task=UUID.randomUUID().toString(),run=UUID.randomUUID().toString();final RunControl control=new RunControl();final AtomicBoolean route=new AtomicBoolean(true);final AtomicInteger closed=new AtomicInteger();final TaskStoreMemoryReader.RawBinding binding;final JSONArray originals=new JSONArray();boolean closeFails;
        Fixture()throws Exception{
            control.bindHostRun(task,run,1);originals.put(new JSONObject().put("role","user").put("content","native source"));
            binding=new TaskStoreMemoryReader.RawBinding(task,run,1,control,new MemoryHostFence(){public void check(){if(!route.get())throw new SecurityException("original_route_revoked");}public JSONObject admit(Admission action)throws Exception{check();return action.run();}},new AtomicLong()::get);
            binding.bindMessageCurrentness((rows,bytes)->new TaskStoreMemoryReader.RawMessageCheck(){public void requireCurrentOriginalMessage(int index,JSONObject original)throws Exception{assertEquals(rows.getJSONObject(index).toString(),original.toString());}public void close(){closed.incrementAndGet();if(closeFails)throw new IllegalStateException("native_cleanup_failure");}});
        }
    }
    @Test public void cancelAndPauseAlwaysReleaseNativeBatchAndNeverBecomeReadAdmission()throws Exception{
        for(boolean cancel:new boolean[]{false,true}){
            Fixture f=new Fixture();var session=f.binding.openMessageCurrentness(f.originals);session.requireCurrentOriginalMessage(0,f.originals.getJSONObject(0));
            if(cancel)f.control.cancel();else f.control.pause();
            if(cancel)assertThrows(InterruptedException.class,session::close);else assertThrows(SecurityException.class,session::close);
            assertEquals(1,f.closed.get());session.close();assertEquals(1,f.closed.get());
            if(cancel)assertThrows(InterruptedException.class,()->f.binding.openMessageCurrentness(f.originals));
            else{f.control.resume();f.binding.admitRequest(new CapabilitySnapshot(f.task,f.run,1,f.control,Set.of(TaskStoreMemoryReader.RAW_QUERY,TaskStoreMemoryReader.RAW_READ),()->{}));try(var fresh=f.binding.openMessageCurrentness(f.originals)){fresh.requireCurrentOriginalMessage(0,f.originals.getJSONObject(0));}assertEquals(2,f.closed.get());}
        }
    }
    @Test public void originalRouteRefusalWinsEvenIfNativeCleanupAlsoFails()throws Exception{
        Fixture f=new Fixture();var session=f.binding.openMessageCurrentness(f.originals);f.route.set(false);f.closeFails=true;
        SecurityException failure=assertThrows(SecurityException.class,session::close);assertEquals("original_route_revoked",failure.getMessage());assertEquals(1,f.closed.get());assertEquals(1,failure.getSuppressed().length);assertTrue(failure.getSuppressed()[0] instanceof IllegalStateException);session.close();assertEquals(1,f.closed.get());assertThrows(SecurityException.class,()->f.binding.openMessageCurrentness(f.originals));
    }
}
