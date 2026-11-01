package dev.pocketengine.store;

import static org.junit.Assert.*;

import android.app.Application;
import dev.pocketengine.core.RunControl;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Real SQLite/run/mailbox. Reflection keeps the same missing-entry example runnable on base v20. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class PhoneOwnerInputMailboxTest {
    private static Object call(TaskStore store,String name,Object...args)throws Exception {
        Method selected=null;for(Method method:TaskStore.class.getMethods())if(method.getName().equals(name)&&method.getParameterCount()==args.length){selected=method;break;}
        assertNotNull("Existing phone tasks need an explicit same-task context input mailbox: "+name,selected);
        try{return selected.invoke(store,args);}catch(InvocationTargetException failure){throw (Exception)failure.getCause();}
    }
    private static final class Fixture {
        final TaskStore store=new TaskStore(RuntimeEnvironment.getApplication());
        final String id,run;final int epoch;final RunControl original=new RunControl();
        Fixture()throws Exception {
            JSONObject attempt=store.startAttempt(store.create("比较原任务订单，不提交", "background").getString("id"),"background",12);
            id=attempt.getString("id");run=attempt.getString("runId");epoch=attempt.getInt("coordinatorEpoch");original.bindHostRun(id,run,epoch);
            call(store,"openPhoneOwnerInputMailbox",id,run,epoch,original);
        }
        JSONObject append(JSONObject message)throws Exception{return (JSONObject)call(store,"appendPhoneOwnerInput",id,run,epoch,message,original);}
        JSONArray poll(JSONArray included,boolean close)throws Exception{return (JSONArray)call(store,"pollPhoneOwnerInputs",id,run,epoch,included,true,close,original);}
        JSONObject receipt(String client)throws Exception{return (JSONObject)call(store,"phoneOwnerInputReceipt",id,run,epoch,client);}
    }
    private static JSONObject message(String text)throws Exception{return new JSONObject().put("id",UUID.randomUUID().toString()).put("clientMessageId",UUID.randomUUID().toString()).put("content",text).put("attachments",new JSONArray());}

    @Test public void pausedPhoneAcceptsOnceWithoutResumeNewTaskOrEffectAndOldReceiptSurvivesStop()throws Exception {
        Fixture f=new Fixture();f.original.pause();f.store.status(f.id,"paused","主人先核对");JSONObject input=message("来源是主人明确选择的当前主屏，只比较价格。");
        JSONObject accepted=f.append(input);assertFalse(accepted.getBoolean("duplicate"));assertTrue(f.append(new JSONObject(input.toString()).put("id",UUID.randomUUID().toString())).getBoolean("duplicate"));
        assertThrows(SecurityException.class,()->f.append(new JSONObject(input.toString()).put("content","同client改写")));
        assertTrue(f.original.isPaused());assertFalse(f.original.isCancelled());assertEquals("paused",f.store.get(f.id,false).getString("status"));assertEquals(f.run,f.store.get(f.id,false).getString("runId"));
        assertEquals(1,f.store.list().length());assertEquals(0,f.store.get(f.id,false).getJSONArray("operations").length());assertEquals(1,f.store.get(f.id,false).getJSONArray("ownerInputs").length());
        f.original.cancel();f.store.endAttempt(f.id,f.run,"cancelled","原任务停止",new JSONObject());JSONObject receipt=f.receipt(input.getString("clientMessageId"));
        assertEquals("queued",receipt.getString("status"));assertEquals(f.run,receipt.getString("runId"));assertEquals(accepted.getString("messageId"),receipt.getString("messageId"));
        assertEquals(1,f.store.list().length());
    }
    @Test public void safeBoundaryUsesOriginalRowsAndTerminalCloseCannotReopenOrAcceptLateContent()throws Exception {
        Fixture f=new Fixture();JSONObject input=message("主人补充，进入原下一安全步骤。");f.append(input);
        assertEquals(input.getString("id"),f.poll(new JSONArray(),false).getJSONObject(0).getString("messageId"));
        assertEquals(1,f.poll(new JSONArray(),false).length());assertEquals(0,f.poll(new JSONArray().put(input.getString("id")),true).length());
        assertFalse(((JSONObject)call(f.store,"openPhoneOwnerInputMailbox",f.id,f.run,f.epoch,f.original)).getBoolean("open"));
        assertThrows(IllegalStateException.class,()->f.append(message("原邮箱关闭后不能再加入")));
        assertEquals(1,f.store.get(f.id,false).getJSONArray("ownerInputs").length());
    }
    @Test public void unknownEffectsAndWrongRunRefuseAdmissionButReadOnlyTargetStillExplainsOriginalTask()throws Exception {
        Fixture f=new Fixture();String operation=f.store.journal(f.id,f.run,f.epoch,f.original).beginOperation("phone_tap",new JSONObject().put("x",30).put("y",40));
        f.store.journal(f.id,f.run,f.epoch,f.original).finishOperation(operation,new JSONObject().put("status","unknown"));
        JSONObject input=message("不能跨过未知动作继续");assertThrows(IllegalStateException.class,()->f.append(input));assertEquals("unconfirmed",f.receipt(input.getString("clientMessageId")).getString("status"));
        f.store.status(f.id,"reconciling","等待核对");assertEquals(f.id,((JSONObject)call(f.store,"ownerScreenContextTarget",f.id,f.run,f.epoch,f.original)).getString("id"));
        assertThrows(SecurityException.class,()->call(f.store,"ownerScreenContextTarget",f.id,f.run,f.epoch+1,f.original));assertEquals(0,f.store.get(f.id,false).getJSONArray("ownerInputs").length());
        assertEquals(1,f.store.get(f.id,false).getJSONArray("operations").length());assertEquals(1,f.store.list().length());
    }
    @Test public void oversizeIsRejectedAndOnlyImmutableAttachmentMetadataIsDurable()throws Exception {
        Fixture f=new Fixture();assertThrows(IllegalArgumentException.class,()->f.append(message("过".repeat(12001))));
        JSONObject input=message("这是已审核的当前主屏附件，仅作参考。");input.getJSONArray("attachments").put(new JSONObject().put("id",UUID.randomUUID().toString()).put("sha256","a".repeat(64)).put("mimeType","image/png").put("size",68));f.append(input);
        String encoded=f.store.get(f.id,false).getJSONArray("ownerInputs").toString();assertTrue(encoded.contains("sha256"));assertFalse(encoded.contains("base64"));assertFalse(encoded.contains("image_url"));assertFalse(encoded.contains("content://"));
        assertEquals(1,f.store.get(f.id,false).getJSONArray("ownerInputs").length());
    }
}
