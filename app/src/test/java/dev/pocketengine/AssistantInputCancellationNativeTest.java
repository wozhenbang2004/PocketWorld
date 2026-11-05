package dev.pocketengine;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.testutil.ReadyApplicationFixture;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import static org.junit.Assert.*;

/** Actual admission, pickup, RunControl cancellation and SQL settlement; no model request. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class,shadows=AssistantInputCancellationNativeTest.Presentation.class)
public final class AssistantInputCancellationNativeTest {
    @Implements(EngineApplication.class)
    public static final class Presentation extends org.robolectric.shadows.ShadowApplication {
        @Implementation protected void onCreate(){}
        @Implementation protected void pushTask(String id){}
    }
    private static Object field(Object object,String name)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private static Object invoke(EngineApplication app,String name,Class<?>[] types,Object... args)throws Exception{
        Method m=EngineApplication.class.getDeclaredMethod(name,types);m.setAccessible(true);
        try{return m.invoke(app,args);}catch(InvocationTargetException failed){if(failed.getCause() instanceof Exception e)throw e;throw failed;}
    }
    @Test public void cancellationAfterPickupBeforeAttemptAssignmentSettlesTheReservedRun()throws Exception{
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();app.tasks=new TaskStore(app);ReadyApplicationFixture.complete(app);
        SQLiteDatabase db=(SQLiteDatabase)field(app.tasks,"db");RunControl original=new RunControl();
        try{
            String conversation=UUID.randomUUID().toString();ModelConfig model=new ModelConfig("http://127.0.0.1:1","","fixture",false,8,100);
            JSONObject message=new JSONObject().put("id",UUID.randomUUID().toString()).put("clientMessageId",UUID.randomUUID().toString()).put("content","原输入").put("attachments",new JSONArray());
            JSONObject accepted=(JSONObject)invoke(app,"startAssistantInput",new Class<?>[]{String.class,String.class,JSONObject.class,ModelConfig.class,RunControl.class},conversation,"原输入",message,model,original);
            Map<?,?> bindings=(Map<?,?>)field(app,"assistantInputs");Object binding=bindings.get(original);AtomicInteger phase=(AtomicInteger)field(binding,"phase");
            original.retainAuthorityCheck(()->{
                if(phase.get()==1){app.tasks.requestCancel(accepted.getString("taskId"),accepted.getString("runId"));original.cancel();}
            });
            assertThrows(InterruptedException.class,()->invoke(app,"runAssistantTurn",new Class<?>[]{String.class,String.class,JSONArray.class,ModelConfig.class,RunControl.class},conversation,"原输入",new JSONArray(),model,original));
            JSONObject task=app.tasks.get(accepted.getString("taskId"),false);assertEquals("cancelled",task.getString("status"));assertEquals(accepted.getString("runId"),task.getString("runId"));
            try(Cursor row=db.rawQuery("SELECT status FROM runs WHERE task_id=? AND run_id=?",new String[]{accepted.getString("taskId"),accepted.getString("runId")})){assertTrue(row.moveToFirst());assertEquals("cancelled",row.getString(0));}
            assertTrue(bindings.isEmpty());assertTrue(((Map<?,?>)field(app,"capabilityControls")).isEmpty());assertEquals(1,app.tasks.list().length());
        }finally{original.cancel();((ExecutorService)field(app,"capabilityWorkers")).shutdownNow();db.close();}
    }
}
