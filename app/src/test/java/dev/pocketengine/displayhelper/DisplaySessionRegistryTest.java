package dev.pocketengine.displayhelper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real registry, controlled display backends: no Android focus/input compatibility is claimed. */
public final class DisplaySessionRegistryTest {
    private static final class Backend implements DisplaySessionRegistry.Backend {
        final int display;
        final AtomicInteger creates=new AtomicInteger(),calls=new AtomicInteger(),closes=new AtomicInteger();
        volatile boolean active;
        volatile boolean failCreateReply;
        volatile CountDownLatch createEntered,createContinue,frameEntered,frameContinue,statusEntered,statusContinue;
        Backend(int display){this.display=display;}
        public JSONObject handle(JSONObject request)throws Exception {
            String command=request.getString("command");
            if(command.equals("create")){
                creates.incrementAndGet();
                if(createEntered!=null){createEntered.countDown();assertTrue(createContinue.await(3,TimeUnit.SECONDS));}
                active=true;if(failCreateReply)throw new java.io.IOException("create reply lost");
                return status();
            }
            if(command.equals("status")){
                if(statusEntered!=null){statusEntered.countDown();assertTrue(statusContinue.await(3,TimeUnit.SECONDS));}
                return status();
            }
            if(command.equals("frame")&&frameEntered!=null){frameEntered.countDown();assertTrue(frameContinue.await(3,TimeUnit.SECONDS));}
            calls.incrementAndGet();
            if(command.equals("release"))active=false;
            return new JSONObject().put("ok",true).put("sessionId","session-"+display).put("displayId",display)
                    .put("released",command.equals("release")).put("value",display);
        }
        JSONObject status()throws Exception{return new JSONObject().put("ok",true).put("protocol",1).put("uid",2000).put("sdk",35)
                .put("sessionActive",active).put("sessionId",active?"session-"+display:JSONObject.NULL)
                .put("displayId",active?display:JSONObject.NULL);}
        public void tick() { }
        public void close(){closes.incrementAndGet();active=false;}
    }
    private static final class Fixture implements AutoCloseable {
        final List<Backend> backends=new ArrayList<>();
        final DisplaySessionRegistry registry=new DisplaySessionRegistry(()->{
            Backend backend=new Backend(7+backends.size());backends.add(backend);return backend;
        },new Backend(-1));
        JSONObject create(String task,String run,int epoch)throws Exception{return registry.handle(owner("create",task,run,epoch));}
        @Override public void close(){registry.close();}
    }
    private static JSONObject owner(String command,String task,String run,int epoch)throws Exception{return new JSONObject().put("command",command)
            .put("ownerTaskId",task).put("ownerRunId",run).put("ownerEpoch",epoch);}
    private static JSONObject request(String command,JSONObject created)throws Exception{return new JSONObject(created.toString()).put("command",command);}
    private static void rejected(String code,Throwing operation){
        DisplaySessionRegistry.Failure rejected=assertThrows(DisplaySessionRegistry.Failure.class,operation::run);
        assertEquals(code,rejected.code);
    }
    @FunctionalInterface private interface Throwing {void run()throws Exception;}

    @Test public void fourNativeRunsHaveIndependentRoutesAndFifthCannotAllocateAnything()throws Exception {
        try(Fixture fixture=new Fixture()){
            List<JSONObject> created=new ArrayList<>();
            for(int i=0;i<4;i++)created.add(fixture.create("task-"+i,"run-"+i,1));
            for(int i=0;i<4;i++){
                JSONObject frame=fixture.registry.handle(request("frame",created.get(i)));
                assertEquals(7+i,frame.getInt("displayId"));assertEquals("task-"+i,frame.getString("ownerTaskId"));
                assertEquals(1,fixture.backends.get(i).calls.get());
            }
            rejected("display_capacity_reached",()->fixture.create("task-4","run-4",1));
            assertEquals(4,fixture.backends.size());
            JSONObject inventory=fixture.registry.handle(new JSONObject().put("command","status"));
            assertFalse(inventory.getBoolean("sessionActive"));assertTrue(inventory.isNull("sessionId"));
            assertEquals(4,inventory.getJSONArray("sessions").length());assertEquals(2,inventory.getInt("sessionRouting"));
        }
    }
    @Test public void unscopedOrCrossRunInputCannotPickAnExistingDisplay()throws Exception {
        try(Fixture fixture=new Fixture()){
            JSONObject first=fixture.create("a","run-a",1),second=fixture.create("b","run-b",1);
            rejected("exact_session_required",()->fixture.registry.handle(new JSONObject().put("command","tap")));
            JSONObject foreign=request("tap",first).put("ownerTaskId","b").put("ownerRunId","run-b");
            rejected("original_run_mismatch",()->fixture.registry.handle(foreign));
            rejected("invalid_lease",()->fixture.registry.handle(request("tap",first).put("displayId",second.getInt("displayId"))));
            rejected("original_run_mismatch",()->fixture.registry.handle(new JSONObject().put("command","tap").put("sessionId",first.getString("sessionId")).put("displayId",first.getInt("displayId"))));
            assertEquals(0,fixture.backends.get(0).calls.get());assertEquals(0,fixture.backends.get(1).calls.get());
        }
    }
    @Test public void lostCreateReplyAndDuplicateDeliveryRecoverOnlyTheSameDisplay()throws Exception {
        List<Backend> created=new ArrayList<>();
        try(DisplaySessionRegistry registry=new DisplaySessionRegistry(()->{Backend backend=new Backend(12);backend.failCreateReply=true;created.add(backend);return backend;},new Backend(-1))){
            JSONObject request=owner("create","original","run",2);
            assertThrows(java.io.IOException.class,()->registry.handle(request));
            JSONObject known=registry.handle(owner("status","original","run",2));assertEquals(12,known.getInt("displayId"));
            JSONObject duplicate=registry.handle(request);assertTrue(duplicate.getBoolean("reused"));assertEquals(known.getString("sessionId"),duplicate.getString("sessionId"));
            assertEquals(1,created.size());assertEquals(1,created.get(0).creates.get());
        }
    }
    @Test public void delayedOldReleaseCannotTouchTheNextRunOfTheSameTask()throws Exception {
        try(Fixture fixture=new Fixture()){
            JSONObject old=fixture.create("task","old",1);
            fixture.registry.handle(request("release",old));
            JSONObject next=fixture.create("task","next",2);
            JSONObject duplicate=fixture.registry.handle(request("release",old));assertTrue(duplicate.getBoolean("releaseConfirmed"));
            assertEquals(0,fixture.backends.get(1).calls.get());assertTrue(fixture.backends.get(1).active);
            rejected("original_session_ended",()->fixture.create("task","old",1));
            assertEquals(next.getString("sessionId"),fixture.registry.handle(owner("status","task","next",2)).getString("sessionId"));
        }
    }
    @Test public void slowFrameOnOneDisplayDoesNotSerializeTheOtherDisplay()throws Exception {
        try(Fixture fixture=new Fixture()){
            JSONObject first=fixture.create("a","a-run",1),second=fixture.create("b","b-run",1);
            Backend slow=fixture.backends.get(0);slow.frameEntered=new CountDownLatch(1);slow.frameContinue=new CountDownLatch(1);
            FutureTask<JSONObject> a=new FutureTask<>(()->fixture.registry.handle(request("frame",first)));
            Thread thread=new Thread(a);thread.start();
            try{
                assertTrue(slow.frameEntered.await(1,TimeUnit.SECONDS));
                FutureTask<JSONObject> b=new FutureTask<>(()->fixture.registry.handle(request("frame",second)));
                Thread other=new Thread(b);other.start();assertEquals(second.getInt("displayId"),b.get(1,TimeUnit.SECONDS).getInt("displayId"));other.join(1000);
            }finally{slow.frameContinue.countDown();assertEquals(first.getInt("displayId"),a.get(1,TimeUnit.SECONDS).getInt("displayId"));thread.join(1000);}
        }
    }
    @Test public void inventoryDuringCreationDoesNotRetireAnUnpublishedDisplay()throws Exception {
        Backend backend=new Backend(15);backend.createEntered=new CountDownLatch(1);backend.createContinue=new CountDownLatch(1);
        try(DisplaySessionRegistry registry=new DisplaySessionRegistry(()->backend,new Backend(-1))){
            FutureTask<JSONObject> create=new FutureTask<>(()->registry.handle(owner("create","task","run",1)));
            Thread thread=new Thread(create);thread.start();
            try{
                assertTrue(backend.createEntered.await(1,TimeUnit.SECONDS));
                JSONArray pending=registry.handle(new JSONObject().put("command","status")).getJSONArray("sessions");
                assertEquals(1,pending.length());assertTrue(pending.getJSONObject(0).getBoolean("creationPending"));assertEquals(0,backend.closes.get());
                rejected("display_creation_unconfirmed",()->registry.handle(owner("create","task","run",1)));
            }finally{backend.createContinue.countDown();assertEquals(15,create.get(1,TimeUnit.SECONDS).getInt("displayId"));thread.join(1000);}
            assertTrue(backend.active);assertEquals(1,backend.creates.get());
        }
    }
    @Test public void duplicateCreateStatusDoesNotHoldOtherRunsRoutingLock()throws Exception {
        try(Fixture fixture=new Fixture()){
            fixture.create("a","a-run",1);JSONObject second=fixture.create("b","b-run",1);
            Backend slow=fixture.backends.get(0);slow.statusEntered=new CountDownLatch(1);slow.statusContinue=new CountDownLatch(1);
            FutureTask<JSONObject> duplicate=new FutureTask<>(()->fixture.create("a","a-run",1));Thread first=new Thread(duplicate);first.start();
            try{
                assertTrue(slow.statusEntered.await(1,TimeUnit.SECONDS));
                FutureTask<JSONObject> frame=new FutureTask<>(()->fixture.registry.handle(request("frame",second)));Thread other=new Thread(frame);other.start();
                assertEquals(second.getInt("displayId"),frame.get(1,TimeUnit.SECONDS).getInt("displayId"));other.join(1000);
            }finally{slow.statusContinue.countDown();assertTrue(duplicate.get(1,TimeUnit.SECONDS).getBoolean("reused"));first.join(1000);}
            assertEquals(1,slow.creates.get());
        }
    }
    @Test public void expiryOfOneLeaseLeavesTheOtherRunOwnedAndReleaseIsIdempotent()throws Exception {
        try(Fixture fixture=new Fixture()){
            JSONObject a=fixture.create("a","a-run",1),b=fixture.create("b","b-run",1);
            fixture.backends.get(0).active=false;fixture.registry.tick();
            assertTrue(fixture.registry.handle(request("release",a)).getBoolean("releaseConfirmed"));
            JSONObject remaining=fixture.registry.handle(owner("status","b","b-run",1));assertEquals(b.getInt("displayId"),remaining.getInt("displayId"));
            assertEquals(0,fixture.backends.get(1).closes.get());
        }
    }
    @Test public void legacySessionStaysSingleAndPartialNativeIdentitiesCannotEnter()throws Exception {
        try(Fixture fixture=new Fixture()){
            JSONObject legacy=fixture.registry.handle(new JSONObject().put("command","create"));
            JSONObject inventory=new JSONObject(fixture.registry.handle(new JSONObject().put("command","status")).toString());
            assertEquals(legacy.getString("sessionId"),inventory.getString("sessionId"));
            rejected("original_task_busy",()->fixture.create("task","run",1));
            rejected("original_run_required",()->fixture.registry.handle(new JSONObject().put("command","create").put("ownerTaskId","task")));
            fixture.registry.handle(request("release",legacy));
            rejected("original_run_required",()->fixture.registry.handle(new JSONObject().put("command","create").put("parallel",true)));
            assertEquals(1,fixture.backends.size());
        }
    }
}
