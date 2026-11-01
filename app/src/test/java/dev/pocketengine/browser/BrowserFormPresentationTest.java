package dev.pocketengine.browser;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static dev.pocketengine.browser.BrowserFormControlsTest.*;

/** Real port/native KnownSet projection with explicit driver records, not DOM/provider/device. */
public final class BrowserFormPresentationTest {
    @Test public void registeredButOutputOmittedOptionsCannotBeGuessedIntoApproval()throws Exception {
        try(BrowserTaskSpaceTest.Harness h=new BrowserTaskSpaceTest.Harness(true)){
            String tab=h.open("bounded-options");JSONObject control=select(32);for(int i=0;i<32;i++)control.getJSONArray("options").getJSONObject(i).put("value","值".repeat(250)).put("label","名".repeat(150));
            JSONObject observed=BrowserPresentedReadTest.completeObservation(h,tab,j("text","正文".repeat(3000),"elements",new JSONArray().put(preview(control))));JSONArray options=observed.getJSONArray("elements").getJSONObject(0).getJSONObject("control").getJSONArray("options");
            assertTrue(options.length()>0&&options.length()<32);assertTrue(observed.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=32768);assertTrue(observed.getBoolean("outputTruncated"));
            int approvals=h.approvals.get();JSONObject omitted=h.invoke("select_option",j("tabId",tab,"observationId",observed.getString("observationId"),"ref","n0","optionRef","o31"));assertFalse(omitted.getBoolean("accepted"));assertEquals(approvals,h.approvals.get());assertEquals(0,h.driver.mutableDispatches);
            JSONObject accepted=h.invoke("select_option",j("tabId",tab,"observationId",observed.getString("observationId"),"ref","n0","optionRef","o0"));assertTrue(accepted.toString(),accepted.getBoolean("accepted"));assertEquals(1,h.driver.mutableDispatches);assertEquals(options.length(),h.driver.lastExecuteArgs.getJSONObject("_nativeTargetPreview").getJSONObject("control").getJSONArray("options").length());
            JSONObject stale=h.invoke("select_option",j("tabId",tab,"observationId",observed.getString("observationId"),"ref","n0","optionRef","o0"));assertFalse(stale.getBoolean("accepted"));assertEquals(1,h.driver.mutableDispatches);
        }
    }
    @Test public void typedWritesCannotUseSemanticReadRefsOrSmugglePinsAndArbitraryValues()throws Exception {
        try(BrowserTaskSpaceTest.Harness h=new BrowserTaskSpaceTest.Harness(true)){
            String tab=h.open("safe-checkbox"),observation=java.util.UUID.randomUUID().toString();
            for(JSONObject invalid:new JSONObject[]{j("tabId",tab,"observationId",observation,"ref","n0","checked","true"),j("tabId",tab,"observationId",observation,"ref","n0","checked",true,"_nativeTargetPreview",preview(check("checkbox",false)))})assertThrows(Exception.class,()->h.port.validate("browser_task_set_checked",invalid));
            assertThrows(Exception.class,()->h.port.validate("browser_task_select_option",j("tabId",tab,"observationId",observation,"ref","n0","optionRef","o0","value","private-model-context")));
            JSONObject shown=BrowserPresentedReadTest.completeObservation(h,tab,j("elements",new JSONArray().put(preview(check("checkbox",false)))));h.authority.approved=false;JSONObject denied=h.invoke("set_checked",j("tabId",tab,"observationId",shown.getString("observationId"),"ref","n0","checked",true));assertFalse(denied.getBoolean("accepted"));assertEquals(0,h.driver.mutableDispatches);
        }
    }
    @Test public void readScopeNeverClassifiesTypedWritesAsReadsAndGrantNamesAreExplicit()throws Exception {
        assertFalse(BrowserReadScope.readTool("browser_task_select_option"));assertFalse(BrowserReadScope.readTool("browser_task_set_checked"));String names=BrowserTaskCapabilityPort.taskToolNames(true).toString();assertTrue(names.contains("browser_task_select_option"));assertTrue(names.contains("browser_task_set_checked"));
    }
}
