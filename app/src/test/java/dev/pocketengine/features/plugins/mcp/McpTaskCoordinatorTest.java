package dev.pocketengine.features.plugins.mcp;

import dev.pocketengine.core.RunControl;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class McpTaskCoordinatorTest {
    private static McpTaskCoordinator.Binding binding(String operation)throws Exception {
        return new McpTaskCoordinator.Binding("task","run",1,operation,"installation","credential",new JSONObject().put("protocolVersion","2026-07-28").put("name","write")
                .put("endpoint","https://mcp.example.com/mcp").put("serverDigest","a".repeat(64)).put("descriptorDigest","b".repeat(64)));
    }
    private static RunControl control(){RunControl control=new RunControl();control.bindHostRun("task","run",1);return control;}
    private static JSONObject remote(String status,boolean creation)throws Exception {
        JSONObject out=new JSONObject().put("resultType",creation?"task":"complete").put("taskId","remote").put("status",status)
                .put("createdAt","2026-10-31T00:00:00Z").put("lastUpdatedAt","2026-10-31T00:00:00Z").put("ttlMs",JSONObject.NULL).put("pollIntervalMs",1000);
        if(!creation&&status.equals("completed"))out.put("result",new JSONObject().put("content",new org.json.JSONArray()).put("isError",true));return out;
    }
    private static final class Host implements McpTaskCoordinator.Host {
        final Map<String,JSONObject> data=new HashMap<>();boolean valid=true,cancellation;int reservations;
        @Override public void check(McpTaskCoordinator.Binding b,McpTaskCoordinator.Access access){if(!valid)throw new SecurityException("Replaced installation");}
        @Override public synchronized JSONObject load(McpTaskCoordinator.Binding b)throws Exception{JSONObject value=data.get(b.fingerprint());return value==null?null:new JSONObject(value.toString());}
        @Override public synchronized void reserve(McpTaskCoordinator.Binding b,String arguments,JSONObject receipt)throws Exception {
            if(!data.isEmpty())throw new SecurityException("Unknown write effect requires reconciliation");reservations++;data.put(b.fingerprint(),new JSONObject(receipt.toString()));
        }
        @Override public synchronized void save(McpTaskCoordinator.Binding b,JSONObject receipt)throws Exception {
            JSONObject old=data.get(b.fingerprint());if(old==null)throw new SecurityException();
            JSONObject copy=new JSONObject(receipt.toString());if(old.optBoolean("cancelRequested"))copy.put("cancelRequested",true);
            if(old.optBoolean("cancelAcknowledged"))copy.put("cancelAcknowledged",true);data.put(b.fingerprint(),copy);
        }
        @Override public synchronized boolean reserveCancellation(McpTaskCoordinator.Binding b)throws Exception {
            if(cancellation)return false;cancellation=true;data.get(b.fingerprint()).put("cancelRequested",true);return true;
        }
    }
    private static final class Wire implements McpTaskCoordinator.Transport {
        int starts,queries,cancels;boolean loseStart,loseCancel,preflightFailure;String initial="working",next="completed";JSONObject queryOverride;
        @Override public JSONObject start(JSONObject d,JSONObject a,RunControl c)throws Exception{starts++;if(preflightFailure)throw new Undispatched();if(loseStart)throw new java.io.IOException("lost ACK");return remote(initial,true);}
        @Override public JSONObject query(JSONObject d,String id,RunControl c)throws Exception{queries++;return queryOverride==null?remote(next,false):queryOverride;}
        @Override public void cancel(JSONObject d,String id,RunControl c)throws Exception{cancels++;if(loseCancel)throw new java.io.IOException("lost cancellation ACK");}
    }
    private static final class Undispatched extends Exception implements McpTaskCoordinator.DispatchFailure {
        @Override public boolean mayHaveDispatched(){return false;}
    }
    @Test public void terminalCreationStillRetrievesFinalResultAndReceiptIsNotBusinessSuccess()throws Exception {
        Host host=new Host();Wire wire=new Wire();wire.initial="completed";AtomicLong now=new AtomicLong(1000);McpTaskCoordinator c=new McpTaskCoordinator(host,wire,now::get);McpTaskCoordinator.Binding b=binding("op");
        JSONObject created=c.start(b,new JSONObject(),control());assertTrue(created.getBoolean("terminal"));assertFalse(created.getBoolean("resolved"));assertFalse(created.getBoolean("verified"));assertFalse(created.has("toolResult"));
        c.query(b,control());assertEquals(0,wire.queries);now.set(2000);JSONObject done=c.query(b,control());
        assertTrue(done.getBoolean("resolved"));assertTrue(done.getJSONObject("toolResult").getBoolean("isError"));assertFalse(done.getBoolean("verified"));c.query(b,control());assertEquals(1,wire.queries);assertEquals(1,wire.starts);
    }
    @Test public void durableUnknownReceiptSurvivesRestartAndCannotBeReplayedWithNewOperation()throws Exception {
        Host host=new Host();Wire wire=new Wire();wire.loseStart=true;McpTaskCoordinator c=new McpTaskCoordinator(host,wire);McpTaskCoordinator.Binding b=binding("op");
        try{c.start(b,new JSONObject().put("value",1),control());fail();}catch(java.io.IOException expected){}
        McpTaskCoordinator restarted=new McpTaskCoordinator(host,wire);assertEquals("unknown",restarted.start(b,new JSONObject().put("value",1),control()).getString("status"));
        assertEquals("unknown",restarted.query(b,control()).getString("status"));assertEquals(0,wire.queries);assertEquals(1,wire.starts);
        try{restarted.start(b,new JSONObject().put("value",2),control());fail();}catch(SecurityException expected){}
        try{restarted.start(binding("new-op"),new JSONObject().put("value",1),control());fail();}catch(SecurityException expected){}assertEquals(1,wire.starts);
    }
    @Test public void reservedCrashWithoutAnyResponseDoesNotDispatchAgain()throws Exception {
        Host host=new Host();Wire wire=new Wire();McpTaskCoordinator.Binding b=binding("op");String digest=McpJson.digest(new JSONObject());
        host.reserve(b,digest,new JSONObject().put("binding",b.fingerprint()).put("argumentsDigest",digest).put("status","unknown"));
        assertEquals("unknown",new McpTaskCoordinator(host,wire).start(b,new JSONObject(),control()).getString("status"));assertEquals(0,wire.starts);
    }
    @Test public void cancelledOrWrongRunControlsCannotDispatchAndReplacementBlocksQuery()throws Exception {
        Host host=new Host();Wire wire=new Wire();McpTaskCoordinator c=new McpTaskCoordinator(host,wire);McpTaskCoordinator.Binding b=binding("op");
        try{c.start(b,new JSONObject(),new RunControl());fail();}catch(SecurityException expected){}
        RunControl paused=control();paused.pause();try{c.start(b,new JSONObject(),paused);fail();}catch(IllegalStateException expected){}
        RunControl stopped=control();stopped.cancel();try{c.start(b,new JSONObject(),stopped);fail();}catch(InterruptedException expected){}
        assertEquals(0,wire.starts);c.start(b,new JSONObject(),control());host.valid=false;try{c.query(b,control());fail();}catch(SecurityException expected){}assertEquals(0,wire.queries);
    }
    @Test public void cancellationIntentIsDurableOnceOnlyAndAckDoesNotClaimRemoteCancelled()throws Exception {
        Host host=new Host();Wire wire=new Wire();wire.loseCancel=true;McpTaskCoordinator c=new McpTaskCoordinator(host,wire);McpTaskCoordinator.Binding b=binding("op");c.start(b,new JSONObject(),control());
        try{c.cancel(b,control());fail();}catch(java.io.IOException expected){}
        JSONObject again=new McpTaskCoordinator(host,wire).cancel(b,control());assertTrue(again.getBoolean("cancelRequested"));assertFalse(again.getBoolean("cancelAcknowledged"));assertEquals("working",again.getString("status"));assertEquals(1,wire.cancels);
    }
    @Test public void changedRemoteIdentityOrChronologyCannotReplaceStoredReceipt()throws Exception {
        for(String field:new String[]{"taskId","createdAt","lastUpdatedAt"}){
            Host host=new Host();Wire wire=new Wire();AtomicLong now=new AtomicLong(0);McpTaskCoordinator c=new McpTaskCoordinator(host,wire,now::get);McpTaskCoordinator.Binding b=binding("op");c.start(b,new JSONObject(),control());now.set(2000);
            wire.queryOverride=remote("completed",false).put(field,field.equals("taskId")?"different":"2026-10-30T00:00:00Z");
            try{c.query(b,control());fail();}catch(IllegalArgumentException|SecurityException expected){}
            assertEquals("working",host.load(b).getString("status"));assertEquals(1,wire.starts);
        }
    }
    @Test public void inputRequiredIsBlockedAndExpiryNeverCreatesAnotherTask()throws Exception {
        Host host=new Host();Wire wire=new Wire();wire.initial="input_required";McpTaskCoordinator c=new McpTaskCoordinator(host,wire);McpTaskCoordinator.Binding b=binding("op");
        assertTrue(c.start(b,new JSONObject(),control()).getBoolean("unsupportedInputRequired"));JSONObject old=host.load(b);old.put("ttlMs",1);host.save(b,old);
        assertTrue(c.query(b,control()).getBoolean("receiptExpired"));assertEquals(0,wire.queries);assertEquals(1,wire.starts);
        assertTrue(host.load(b).getBoolean("receiptExpired"));assertFalse(host.load(b).optBoolean("resolved"));
        assertTrue(new McpTaskCoordinator(host,wire).query(b,control()).getBoolean("receiptExpired"));assertEquals(0,wire.queries);assertEquals(1,wire.starts);
    }
    @Test public void taskProtocolCannotSilentlyDowngrade()throws Exception {
        JSONObject declaration=binding("op").declaration().put("protocolVersion","2025-11-25");
        try{new McpTaskCoordinator.Binding("task","run",1,"op","installation","credential",declaration);fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void provenPreDispatchFailureIsRecordedWithoutManufacturingUnknownBusinessEffects()throws Exception {
        Host host=new Host();Wire wire=new Wire();wire.preflightFailure=true;McpTaskCoordinator.Binding b=binding("op");McpTaskCoordinator c=new McpTaskCoordinator(host,wire);
        try{c.start(b,new JSONObject(),control());fail();}catch(Undispatched expected){}
        JSONObject receipt=host.load(b);assertEquals("not_dispatched",receipt.getString("dispatchStatus"));assertTrue(receipt.getBoolean("resolved"));
        assertEquals("failed",c.start(b,new JSONObject(),control()).getString("status"));assertEquals(1,wire.starts);
    }
}
