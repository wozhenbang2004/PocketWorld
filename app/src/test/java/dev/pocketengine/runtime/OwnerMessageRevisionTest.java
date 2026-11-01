package dev.pocketengine.runtime;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.features.memory.TaskMemoryHistory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Native queued source metadata survives audit/restoration, never becoming a wire field. */
public final class OwnerMessageRevisionTest {
    private static final String ID="01234567-89ab-cdef-0123-456789abcdef";
    private static ModelConfig config(){return new ModelConfig("","","fixture",false,5,10);}
    private static JSONObject input()throws Exception{return new JSONObject().put("messageId",ID).put("mode","queue").put("content","原主人补充，不猜测其来源版本");}
    private static void append(JSONArray history,JSONObject nativeInput)throws Exception{
        Method method=AgentRunner.class.getDeclaredMethod("appendOwnerInputs",JSONArray.class,JSONArray.class,ModelConfig.class);method.setAccessible(true);method.invoke(null,history,new JSONArray().put(nativeInput),config());
    }
    private static JSONArray durable(JSONArray history)throws Exception{
        Method method=AgentRunner.class.getDeclaredMethod("durableHistory",JSONArray.class,ModelConfig.class);method.setAccessible(true);return (JSONArray)method.invoke(null,history,config());
    }
    private static JSONObject owner(JSONArray history)throws Exception{for(int i=0;i<history.length();i++)if(ID.equals(history.getJSONObject(i).optString("ownerMessageId")))return history.getJSONObject(i);throw new AssertionError("native owner input missing");}

    @Test public void exactNativeLongRevisionSurvivesCheckpointAndRestoreButIsNotSentAsWireMetadata()throws Exception{
        long actual=4_294_967_299L;JSONArray history=new JSONArray();append(history,input().put("ownerMessageRevision",actual));
        assertEquals(actual,owner(history).getLong("ownerMessageRevision"));
        append(history,input().put("ownerMessageRevision",actual+1));assertEquals(1,history.length());assertEquals(actual,owner(history).getLong("ownerMessageRevision"));
        JSONArray saved=durable(history),restored=durable(saved);assertEquals(actual,owner(saved).getLong("ownerMessageRevision"));assertEquals(saved.toString(),restored.toString());
        String audit=saved.toString();assertFalse(owner(TaskMemoryHistory.wireMessages(saved)).has("ownerMessageRevision"));assertEquals(audit,saved.toString());
    }
    @Test public void oldQueueRowsWithoutActualRevisionAreNotBackfilledFromAnIdBodyOrDefaultOne()throws Exception{
        JSONArray history=new JSONArray();append(history,input());assertFalse(owner(history).has("ownerMessageRevision"));assertFalse(owner(durable(history)).has("ownerMessageRevision"));
    }
    @Test public void malformedPresentRevisionCannotReachTheCheckpoint()throws Exception{
        for(Object invalid:List.of(0,-1,1.5,"7",JSONObject.NULL)){
            JSONArray history=new JSONArray();try{append(history,input().put("ownerMessageRevision",invalid));fail("invalid native revision accepted: "+invalid);}catch(InvocationTargetException rejected){assertTrue(rejected.getCause() instanceof SecurityException);}
            assertEquals(0,history.length());
        }
    }
}
