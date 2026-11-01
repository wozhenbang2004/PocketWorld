package dev.pocketengine.features.local.preview;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ProjectOwnerActionTest {
    private JSONObject plan()throws Exception{return new JSONObject().put("checkoutId","checkout").put("sourceDigest","digest");}
    private JSONObject step()throws Exception{return new JSONObject().put("stepId","build").put("hostAction",new JSONObject().put("feature","local").put("command","termux.run").put("payload",new JSONObject().put("checkoutId","checkout").put("expectedSourceDigest","digest").put("projectPlan",new JSONObject()).put("projectStepId","build").put("executable","npm").put("argv",new org.json.JSONArray().put("run").put("build"))));}
    @Test public void reviewedActionUsesOriginalTrackedCommandAndDeepCopiesPayload()throws Exception {
        JSONObject step=step(),value=ProjectOwnerAction.reviewed(plan(),step);assertEquals("termux.run",value.getString("command"));value.getJSONObject("payload").put("executable","changed");assertEquals("npm",step.getJSONObject("hostAction").getJSONObject("payload").getString("executable"));
    }
    @Test public void staleSourceAndForeignRouteRejectBeforeDispatch()throws Exception {
        assertThrows(SecurityException.class,()->ProjectOwnerAction.reviewed(plan().put("sourceDigest","new"),step()));
        JSONObject step=step();step.getJSONObject("hostAction").put("command","project.execute");assertThrows(SecurityException.class,()->ProjectOwnerAction.reviewed(plan(),step));
    }
    @Test public void planCannotProvideHostGrantsOrOperationIdentity()throws Exception {
        for(String key:new String[]{"taskId","runId","operationId","_taskId","principalId","grant","approval"}){
            JSONObject step=step();step.getJSONObject("hostAction").getJSONObject("payload").put(key,"forged");assertThrows(SecurityException.class,()->ProjectOwnerAction.reviewed(plan(),step));
        }
    }
    @Test public void actualProbeCanOmitSourceDigestWithoutWeakeningBuildGuard()throws Exception {
        JSONObject probe=step();probe.put("stepId","probe");JSONObject action=probe.getJSONObject("hostAction");action.put("command","termux.probe");JSONObject payload=action.getJSONObject("payload");payload.remove("expectedSourceDigest");payload.put("projectStepId","probe");
        assertEquals("termux.probe",ProjectOwnerAction.reviewed(plan(),probe).getString("command"));
        action.put("command","termux.run");assertThrows(SecurityException.class,()->ProjectOwnerAction.reviewed(plan(),probe));
    }
}
