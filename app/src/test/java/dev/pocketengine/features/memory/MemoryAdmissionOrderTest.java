package dev.pocketengine.features.memory;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.*;
import dev.pocketengine.features.skills.SkillsModule;
import dev.pocketengine.store.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Deterministic actual Book/TaskStore callback order; daemon threads bound the old deadlock repro. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class MemoryAdmissionOrderTest {
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    @Test public void forgetCallbackCannotTakeBookBeforeTheTaskAdmissionLock()throws Exception{
        var android=RuntimeEnvironment.getApplication();TaskStore tasks=new TaskStore(android);FeatureHost host=new FeatureHost(event->{},tasks,()->false);
        FeatureContext context=new FeatureContext(android,new SettingsStore(android),new SecretVault(android),event->{});MemorySourceBridge bridge=new MemorySourceBridge(host,tasks);
        MemoryModule memory=new MemoryModule(context,bridge::resolve);host.register(memory);host.register(new SkillsModule(context,memory::resolveExperience));
        JSONObject saved=memory.handle("save",new JSONObject().put("scope",scope()).put("record",new JSONObject().put("text","原生锁序事实").put("core",true))).getJSONObject("record");
        JSONObject attempt=tasks.startAttempt(tasks.createCapabilityTask("检查当前上下文","").getString("id"),"capabilities",5);String id=attempt.getString("id"),run=attempt.getString("runId");int epoch=attempt.getInt("coordinatorEpoch");RunControl control=new RunControl();control.bindHostRun(id,run,epoch);
        CountDownLatch admission=new CountDownLatch(1),proceed=new CountDownLatch(1),selected=new CountDownLatch(1),forgotten=new CountDownLatch(1);AtomicReference<Throwable> error=new AtomicReference<>();
        Thread request=new Thread(()->{synchronized(tasks){admission.countDown();try{
            if(!proceed.await(3,TimeUnit.SECONDS))throw new AssertionError("missing selection gate");
            MemoryHostFence fence=new MemoryHostFence(){public void check()throws Exception{tasks.checkExtensionRun(id,run,epoch,control);}public JSONObject admit(Admission action)throws Exception{synchronized(tasks){check();return action.run();}}};
            var reader=new TaskStoreMemoryReader(tasks,null,tasks::queryMemoryTasks);memory.taskMemoryUse(reader,reader).prepareTaskRecall(scope(),new TaskMemoryUse.TaskRef(id,run,epoch,"owner","private"),"",16,12000,fence);selected.countDown();
        }catch(Throwable failure){error.compareAndSet(null,failure);}}},"b1-native-admission");request.setDaemon(true);request.start();assertTrue(admission.await(2,TimeUnit.SECONDS));
        Thread forget=new Thread(()->{try{bridge.forgetReviewed(scope(),saved.getString("id"),saved.getLong("revision"));}catch(Throwable failure){error.compareAndSet(null,failure);}finally{forgotten.countDown();}},"b1-native-forget");forget.setDaemon(true);forget.start();
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(forget.getState()!=Thread.State.BLOCKED&&System.nanoTime()<until)Thread.sleep(5);assertEquals(Thread.State.BLOCKED,forget.getState());proceed.countDown();
        assertTrue("forget took Book then waited TaskStore while native admission held TaskStore then waited Book",selected.await(2,TimeUnit.SECONDS));assertTrue(forgotten.await(2,TimeUnit.SECONDS));
        assertNull(error.get());assertEquals(0,memory.handle("list",new JSONObject().put("scope",scope())).getJSONArray("records").length());host.close();
    }
    @Test public void sourceStateReadsOneAtomicSqlDocumentWithoutInvertingTheTaskMonitor()throws Exception{
        var android=RuntimeEnvironment.getApplication();TaskStore tasks=new TaskStore(android);String id=tasks.createCapabilityTask("来源","").getString("id");tasks.event(id,new JSONObject().put("type","user_verification").put("completed",true));
        CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1),read=new CountDownLatch(1);AtomicReference<Throwable> error=new AtomicReference<>();
        Thread owner=new Thread(()->{synchronized(tasks){locked.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(Exception failure){error.set(failure);}}});owner.setDaemon(true);owner.start();assertTrue(locked.await(1,TimeUnit.SECONDS));
        Thread reader=new Thread(()->{try{SourceRef.State state=tasks.resolveMemorySource(MemoryBook.Scope.from(scope()),"task_evidence","task:"+id+":verified_result");if(state==null||state.deleted)throw new AssertionError("missing atomic source document");read.countDown();}catch(Throwable failure){error.set(failure);}});reader.setDaemon(true);reader.start();
        try{assertTrue("Book source validation waited the TaskStore admission monitor",read.await(1,TimeUnit.SECONDS));}finally{release.countDown();owner.join(1500);reader.join(1500);}assertNull(error.get());
    }
}
