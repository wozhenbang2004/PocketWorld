package dev.pocketengine.features.devices;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class DevicePhoneGoalReadinessTest {
    private static JSONObject helper()throws Exception{return new JSONObject().put("helperConnected",true).put("sessionActive",false).put("activeSessionCount",0).put("parallelPhone",new JSONObject().put("helperSupportsParallel",true));}
    private static JSONObject preparation()throws Exception{return new JSONObject().put("backgroundPreparation",new JSONObject().put("ready",true).put("checks",new JSONArray().put(new JSONObject().put("id","backgroundConnected").put("state","ready")).put(new JSONObject().put("id","backgroundClean").put("state","ready")))).put("backgroundExecution",new JSONObject().put("ready",false));}
    @Test public void firstBackgroundCandidateDoesNotRequirePreexistingSession()throws Exception{assertTrue(DevicePhoneGoalReadiness.canPrepareBackground(preparation(),helper()));}
    @Test public void legacyOrDisconnectedHelperCannotCreateParallelCandidate()throws Exception{assertFalse(DevicePhoneGoalReadiness.canPrepareBackground(preparation(),helper().put("helperConnected",false)));JSONObject legacy=helper();legacy.getJSONObject("parallelPhone").put("helperSupportsParallel",false);assertFalse(DevicePhoneGoalReadiness.canPrepareBackground(preparation(),legacy));}
    @Test public void preparationFailureRemainsBlockedEvenWithReadyOldSession()throws Exception{JSONObject value=preparation();value.getJSONObject("backgroundPreparation").put("ready",false);value.getJSONObject("backgroundExecution").put("ready",true);assertFalse(DevicePhoneGoalReadiness.canPrepareBackground(value,helper().put("sessionActive",true)));}
    @Test public void otherRunPendingDoesNotRevokeAdmittedRunButLostHelperDoes()throws Exception{JSONObject value=preparation();value.getJSONObject("backgroundPreparation").getJSONArray("checks").getJSONObject(1).put("state","blocked");assertTrue(DevicePhoneGoalReadiness.admittedBackgroundPrerequisites(value,helper()));assertFalse(DevicePhoneGoalReadiness.admittedBackgroundPrerequisites(value,helper().put("helperConnected",false)));value.getJSONObject("backgroundPreparation").getJSONArray("checks").getJSONObject(0).put("state","blocked");assertFalse(DevicePhoneGoalReadiness.admittedBackgroundPrerequisites(value,helper()));}
}
