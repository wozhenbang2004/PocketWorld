package dev.pocketengine.features.local.runtime;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Actual native session/birth checks, independent of the unavailable host Unix transport. */
public final class NativeScriptPeerTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    @Test public void missingOrForgedLiveIdentityCannotGrantAnyPeer()throws Exception{
        var supervisor=new NativeProcessSupervisor();assertFalse(supervisor.isSameOwnedSession(0,1,0,1));assertFalse(supervisor.isSameOwnedSession(1,1,2,1));
        if(!supervisor.currentProcessIdentityAvailable())assertFalse(supervisor.isSameOwnedSession(5,178,5,5));
    }
    @Test public void originalChildIsCurrentButOtherOwnedJobPidAndForgedBirthAreRefused()throws Exception{
        var supervisor=new NativeProcessSupervisor();if(!supervisor.currentProcessIdentityAvailable()){System.out.println("SKIPPED positive peer lineage: procfs PID namespace differs from current kernel namespace; callbacks fail closed");org.junit.Assume.assumeTrue("Current native procfs identity unavailable",false);}var pool=Executors.newFixedThreadPool(2);var cancel=new AtomicBoolean();var identities=new CountDownLatch(2);var childKnown=new CountDownLatch(1);long[][] owned=new long[2][3];var child=new AtomicLong();
        String code="import subprocess,sys,time\np=subprocess.Popen([sys.executable,'-c','import time;time.sleep(5)'])\nprint('PEER:'+str(p.pid),flush=True)\np.wait()";
        java.util.List<Future<NativeProcessSupervisor.Exit>> runs=new java.util.ArrayList<>();
        try{
            for(int i=0;i<2;i++){final int index=i;var text=new StringBuilder();runs.add(pool.submit(()->supervisor.execute(List.of("/usr/bin/python3","-c",code),Map.of(),temp.getRoot(),5000,100,new NativeProcessSupervisor.Observer(){
                public boolean processIdentity(long pid,long born,long session){owned[index][0]=pid;owned[index][1]=born;owned[index][2]=session;identities.countDown();return true;}
                public boolean cancelled(){return cancel.get();}
                public boolean output(int stream,byte[] bytes){if(index==0){text.append(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));var match=java.util.regex.Pattern.compile("PEER:([0-9]+)").matcher(text);if(match.find()){child.set(Long.parseLong(match.group(1)));childKnown.countDown();}}return true;}
            })));}
            assertTrue(identities.await(2,TimeUnit.SECONDS));assertTrue(childKnown.await(2,TimeUnit.SECONDS));
            assertTrue(supervisor.isSameOwnedSession(owned[0][0],owned[0][1],owned[0][2],child.get()));
            assertFalse(supervisor.isSameOwnedSession(owned[0][0],owned[0][1],owned[0][2],owned[1][0]));
            assertFalse(supervisor.isSameOwnedSession(owned[0][0],owned[0][1]+1,owned[0][2],child.get()));
            assertFalse(supervisor.isSameOwnedSession(owned[0][0],owned[0][1],owned[0][2],ProcessHandle.current().pid()));
        }finally{cancel.set(true);pool.shutdown();for(var run:runs)assertTrue(run.get(3,TimeUnit.SECONDS).groupAbsent);assertTrue(pool.awaitTermination(3,TimeUnit.SECONDS));}
        assertFalse(supervisor.isSameOwnedSession(owned[0][0],owned[0][1],owned[0][2],child.get()));
    }
}
