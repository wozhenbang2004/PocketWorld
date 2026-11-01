package dev.pocketengine.browser;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class BrowserDomActionTest {
    @Test public void existingRefAndMutationFenceRemainBeforeOneSyntheticDispatch()throws Exception {
        String script=BrowserDomAction.script("click",new JSONObject().put("ref","n2"),"https://example.test/","o","key");
        assertTrue(script.indexOf("observation_changed")<script.indexOf("e.click()"));assertTrue(script.indexOf("element_occluded")<script.indexOf("e.click()"));
        assertEquals(1,script.split("e.click\\(\\)",-1).length-1);assertFalse(script.contains("dispatchGesture"));assertFalse(script.contains("setTimeout"));
    }
    @Test public void userTextAndConditionsAreLiteralsNotExecutableScript()throws Exception {
        String value="');throw new Error('injection');//\n中文";
        String script=BrowserDomAction.script("type",new JSONObject().put("ref","n0").put("text",value).put("postcondition",new JSONObject().put("kind","value").put("value",value)),"https://example.test/","o","key");
        assertTrue(script.contains("setter.call(e,"+JSONObject.quote(value)+")"));assertTrue(script.contains("e.value==="+JSONObject.quote(value)));assertTrue(script.contains("owner_input_required"));
    }
    @Test public void invalidRefsAndDistancesNeverBuildAnAction()throws Exception {
        for(JSONObject args:new JSONObject[]{new JSONObject().put("ref","n0;evil()"),new JSONObject().put("dy",3001),new JSONObject().put("dy",0.5)}){
            try{BrowserDomAction.script(args.has("ref")?"click":"scroll",args,"https://example.test/","o","key");fail();}catch(IllegalArgumentException expected){}
        }
    }    @Test public void exactApprovedSinkIsRecheckedBeforeDispatchAndIsNeverModelRoutingAuthority()throws Exception {
        JSONObject args=new JSONObject().put("ref","n0").put("_nativeTargetPreview",new JSONObject().put("ref","n0").put("formAction","https://approved.test/send").put("submitterFormAction","https://approved.test/override"));
        String script=BrowserDomAction.script("click",args,"https://example.test/","o","key");
        assertTrue(script.indexOf("destination_changed")<script.indexOf("e.click()"));assertTrue(script.contains(JSONObject.quote("https://approved.test/override")));
        args.getJSONObject("_nativeTargetPreview").put("ref","n1");assertThrows(IllegalArgumentException.class,()->BrowserDomAction.script("click",args,"https://example.test/","o","key"));
    }

}
