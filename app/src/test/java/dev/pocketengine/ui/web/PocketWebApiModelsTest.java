package dev.pocketengine.ui.web;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual native projection/reference code; no Android service or real provider. */
public class PocketWebApiModelsTest {
    private static JSONObject row(String connection,long version,boolean available,boolean favorite)throws Exception{
        return new JSONObject().put("connectionId",connection).put("connectionRevision",version).put("modelId","same-model")
            .put("binding",new JSONObject().put("connectionId",connection).put("endpoint","https://"+connection+".example"))
            .put("available",available).put("favorite",favorite).put("lastUsedAt",0);
    }
    private static JSONObject snapshot(JSONObject... rows)throws Exception{
        JSONArray values=new JSONArray();for(JSONObject row:rows)values.put(row);
        return new JSONObject().put("format","pocketworld.model-picker.v1").put("models",values).put("preferencesAvailable",true);
    }
    @Test public void duplicateModelNamesRemainBoundToTheirOwnConnection()throws Exception{
        PocketWebApiModels models=new PocketWebApiModels(()->10L,()->true);
        JSONObject input=snapshot(row("a",3,true,false),row("b",5,true,true));
        JSONObject projected=models.publish(input,8);
        assertEquals(2,projected.getJSONArray("models").length());
        assertFalse(projected.getJSONArray("models").getJSONObject(1).has("binding"));
        input.getJSONArray("models").getJSONObject(1).getJSONObject("binding").put("connectionId","a");
        JSONObject review=models.review("b","same-model",5,8);
        PocketWebApiModels.Selection selected=models.take(review.getString("reviewHandle"),8);
        assertEquals(8,selected.selectionRevision());
        JSONObject original=models.claim(selected);
        assertEquals("b",original.getString("connectionId"));assertEquals("b",original.getJSONObject("binding").getString("connectionId"));
        assertThrows(IllegalStateException.class,()->models.claim(selected));
        assertThrows(IllegalStateException.class,()->models.take(review.getString("reviewHandle"),8));
    }
    @Test public void aDifferentRevisionOrConnectionNeverFallsBackToCurrentModel()throws Exception{
        PocketWebApiModels models=new PocketWebApiModels(()->10L,()->true);models.publish(snapshot(row("b",5,true,false)),8);
        assertThrows(IllegalStateException.class,()->models.review("b","same-model",6,8));
        assertThrows(IllegalStateException.class,()->models.review("a","same-model",5,8));
        JSONObject review=models.review("b","same-model",5,8);
        assertThrows(IllegalStateException.class,()->models.take(review.getString("reviewHandle"),9));
    }
    @Test public void replacementAndOwnerRetirementInvalidateAlreadyCapturedWrites()throws Exception{
        AtomicBoolean owner=new AtomicBoolean(true);PocketWebApiModels models=new PocketWebApiModels(()->10L,owner::get);
        JSONObject old=models.publish(snapshot(row("a",3,true,false)),8);
        PocketWebApiModels.Selection first=models.favorite("a","same-model",3,true,8);
        models.publish(snapshot(row("a",4,true,false)),9);
        assertFalse(models.projectionCurrent(old,9));assertThrows(IllegalStateException.class,()->models.claim(first));
        PocketWebApiModels.Selection second=models.favorite("a","same-model",4,true,9);owner.set(false);
        assertThrows(IllegalStateException.class,()->models.claim(second));
    }
    @Test public void closedExpiredAndForeignReviewsCannotBeUsed()throws Exception{
        AtomicLong clock=new AtomicLong(10);PocketWebApiModels models=new PocketWebApiModels(clock::get,()->true);
        models.publish(snapshot(row("a",3,true,false)),8);String handle=models.review("a","same-model",3,8).getString("reviewHandle");
        clock.set(120010);assertThrows(IllegalStateException.class,()->models.take(handle,8));
        PocketWebApiModels.Selection selected=models.favorite("a","same-model",3,true,8);
        PocketWebApiModels other=new PocketWebApiModels(clock::get,()->true);other.publish(snapshot(row("a",3,true,false)),8);
        assertThrows(IllegalStateException.class,()->other.claim(selected));models.close();assertThrows(IllegalStateException.class,()->models.claim(selected));
    }
    @Test public void missingConnectionOnlyAllowsRemovingItsExistingFavorite()throws Exception{
        PocketWebApiModels models=new PocketWebApiModels(()->10L,()->true);
        JSONObject ghost=new JSONObject().put("connectionId","removed").put("modelId","same-model").put("available",false).put("favorite",true).put("lastUsedAt",42);
        JSONObject projection=models.publish(snapshot(ghost),8);assertEquals(0,projection.getJSONArray("models").getJSONObject(0).getInt("connectionRevision"));
        assertFalse(models.review("removed","same-model",0,8).has("reviewHandle"));
        assertThrows(IllegalStateException.class,()->models.favorite("removed","same-model",0,true,8));
        PocketWebApiModels.Selection removal=models.favorite("removed","same-model",0,false,8);assertEquals(Boolean.FALSE,removal.favorite());
        JSONObject original=models.claim(removal);assertEquals("removed",original.getString("connectionId"));assertFalse(original.has("binding"));assertEquals(42,original.getLong("lastUsedAt"));
    }
    @Test public void unreadablePreferencesAndDuplicateRowsFailClosed()throws Exception{
        PocketWebApiModels models=new PocketWebApiModels(()->10L,()->true);
        assertThrows(IllegalArgumentException.class,()->models.publish(snapshot(row("a",3,true,false),row("a",3,true,false)),8));
        models.publish(snapshot(row("a",3,true,false)).put("preferencesAvailable",false),8);
        assertThrows(IllegalStateException.class,()->models.favorite("a","same-model",3,true,8));
    }
}
