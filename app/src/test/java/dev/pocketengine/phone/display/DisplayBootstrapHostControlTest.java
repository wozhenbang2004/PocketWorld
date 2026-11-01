package dev.pocketengine.phone.display;

import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureHost;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual host admission lanes with blocked ordinary workers; not device process cancellation. */
public final class DisplayBootstrapHostControlTest {
    @Test public void adbCancellationBypassesSaturatedOrdinaryWorkers()throws Exception {
        CountDownLatch entered=new CountDownLatch(4),release=new CountDownLatch(1),cancelled=new CountDownLatch(1);
        FeatureHost host=new FeatureHost(event->{});
        host.register(new EngineModule(){
            public String id(){return "display";}
            public JSONObject snapshot(){return new JSONObject();}
            public JSONObject handle(String command,JSONObject input)throws Exception {
                if(command.equals("adb.cancel"))cancelled.countDown();
                else {entered.countDown();release.await(5,TimeUnit.SECONDS);}
                return new JSONObject();
            }
        });
        try{
            for(int i=0;i<4;i++)host.request(new JSONObject().put("feature","display").put("command","status").put("requestId","ordinary-"+i));
            assertTrue("all ordinary workers must be occupied before cancellation",entered.await(2,TimeUnit.SECONDS));
            host.request(new JSONObject().put("feature","display").put("command","adb.cancel").put("requestId","owner-cancel"));
            assertTrue("owner cancellation cannot wait behind ordinary work",cancelled.await(1,TimeUnit.SECONDS));
        }finally{release.countDown();host.close();}
    }
}
