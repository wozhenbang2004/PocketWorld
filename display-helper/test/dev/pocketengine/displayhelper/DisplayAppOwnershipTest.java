package dev.pocketengine.displayhelper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Explicit text-free native dump fixtures; no device access or owner-task migration. */
public final class DisplayAppOwnershipTest {
    private static final String ACTUAL="Display #0\n  mResumedActivity: ActivityRecord{aa u0 example.video/.Main t41}\nDisplay #7\n  topResumedActivity=ActivityRecord{bb u10 example.notes/.Main t42}\nDisplay #9\n  mResumedActivity: null\n";
    @Test public void sameCopyOnTheMainDisplayCannotBeStolenAtTheNativeEntry()throws Exception {assertThrows(SecurityException.class,()->DisplayAppOwnership.requireAppFree(ACTUAL,"example.video",0,7));DisplayAppOwnership.requireAppFree(ACTUAL,"example.video",10,7);DisplayAppOwnership.requireAppFree(ACTUAL,"example.notes",10,7);assertThrows(SecurityException.class,()->DisplayAppOwnership.requireAppFree(ACTUAL,"example.notes",10,9));}
    @Test public void missingRealUserOrDisplayFormatIsNeverTreatedAsAnEmptyScreen()throws Exception {for(String dump:new String[]{"",ACTUAL.replace("u0 example.video","example.video"),ACTUAL.replace("Display #0","Display unknown")})assertThrows(SecurityException.class,()->DisplayAppOwnership.requireAppFree(dump,"example.video",0,7));assertThrows(SecurityException.class,()->DisplayAppOwnership.requireAppFree(ACTUAL,"example.video",0,0));}
    @Test public void actualUserAttributionRequiresSameTaskDisplayAndPackage()throws Exception {JSONObject ownership=new JSONObject().put("owned",new JSONArray().put(new JSONObject().put("packageName","example.notes").put("taskId",42).put("displayId",7)).put(new JSONObject().put("packageName","example.notes").put("taskId",42).put("displayId",9)).put(new JSONObject().put("packageName","example.other").put("taskId",42).put("displayId",7)));JSONObject result=DisplayAppOwnership.attach(ownership,ACTUAL);assertEquals(10,result.getJSONArray("owned").getJSONObject(0).getInt("userId"));assertFalse(result.getJSONArray("owned").getJSONObject(1).has("userId"));assertFalse(result.getJSONArray("owned").getJSONObject(2).has("userId"));assertEquals("resumed_tasks_only",result.getString("identityScope"));}
}
