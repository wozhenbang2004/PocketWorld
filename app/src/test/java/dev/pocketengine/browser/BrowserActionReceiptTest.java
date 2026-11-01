package dev.pocketengine.browser;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class BrowserActionReceiptTest {
    @Test public void syntheticAcceptanceIsNotTrustedInputEffectOrBusinessSuccess()throws Exception {
        JSONObject receipt=BrowserActionReceipt.attach("click",new JSONObject(),new JSONObject().put("navigation",1),new JSONObject().put("navigation",1),true,
                new JSONObject().put("accepted",true).put("domEvidence",new JSONObject().put("textChanged",true)));
        JSONObject a=receipt.getJSONObject("actionReceipt");assertEquals("synthetic_dom_click",a.getString("mechanism"));assertEquals("confirmed",a.getJSONObject("dispatch").getString("state"));
        assertFalse(a.getJSONObject("dispatch").getBoolean("trustedInputEvent"));assertEquals("changed_unattributable",a.getJSONObject("effect").getString("state"));
        assertEquals("not_checked",a.getJSONObject("postcondition").getString("state"));assertFalse(receipt.getBoolean("verifiedBusinessSuccess"));assertFalse(receipt.getBoolean("automaticReplayAllowed"));assertFalse(receipt.has("domEvidence"));assertTrue(receipt.getString("message").contains("业务"));
    }
    @Test public void absentEvidenceNeverBecomesNoEffectOrCompletedDocument()throws Exception {
        JSONObject out=BrowserActionReceipt.attach("click",new JSONObject(),null,null,true,new JSONObject().put("accepted",JSONObject.NULL).put("status","unknown"));
        JSONObject a=out.getJSONObject("actionReceipt");assertEquals("unknown",a.getJSONObject("dispatch").getString("state"));assertEquals("not_observed",a.getJSONObject("effect").getString("state"));
        assertFalse(a.getJSONObject("after").getBoolean("available"));assertEquals("not_instrumented",a.getJSONObject("newTab").getString("state"));assertEquals("not_instrumented",a.getJSONObject("downloads").getString("state"));
    }
    @Test public void declaredInputPostconditionDoesNotPromoteBusinessCompletion()throws Exception {
        JSONObject args=new JSONObject().put("postcondition",new JSONObject().put("kind","value").put("value","draft"));
        JSONObject raw=new JSONObject().put("accepted",true).put("domEvidence",new JSONObject().put("valueChanged",true).put("postconditionKind","value").put("postcondition","met"));
        JSONObject a=BrowserActionReceipt.attach("type",args,null,null,true,raw).getJSONObject("actionReceipt");assertEquals("met",a.getJSONObject("postcondition").getString("state"));assertFalse(a.getJSONObject("postcondition").getBoolean("businessSuccess"));
        raw.getJSONObject("domEvidence").put("postcondition","unmet");assertTrue(BrowserActionReceipt.attach("type",args,null,null,true,raw).getString("message").contains("不要直接重发"));
        raw.put("accepted",false);assertEquals("not_checked",BrowserActionReceipt.attach("type",args,null,null,true,raw).getJSONObject("actionReceipt").getJSONObject("postcondition").getString("state"));
    }
    @Test public void wrongConditionKindCannotSatisfyAnApprovedCondition()throws Exception {
        JSONObject args=new JSONObject().put("postcondition",new JSONObject().put("kind","url").put("value","https://example.test/done"));
        JSONObject raw=new JSONObject().put("accepted",true).put("domEvidence",new JSONObject().put("postconditionKind","text").put("postcondition","met"));
        assertEquals("not_checked",BrowserActionReceipt.attach("click",args,null,null,true,raw).getJSONObject("actionReceipt").getJSONObject("postcondition").getString("state"));
    }
    @Test public void unsupportedOrCredentialConditionsAreRejected()throws Exception {
        for(JSONObject expected:new JSONObject[]{new JSONObject().put("kind","script").put("value","anything"),new JSONObject().put("kind","value").put("value","x"),
                new JSONObject().put("kind","url").put("value","https://secret@example.test/")}){
            try{BrowserActionReceipt.validatePostcondition(new JSONObject().put("postcondition",expected),"click");fail();}catch(IllegalArgumentException expectedFailure){}
        }
    }
    @Test public void navigationEpochAndSameDocumentURLAreDifferentSignals()throws Exception {
        JSONObject before=new JSONObject().put("navigation",2).put("pageIdentityDigest","a");
        JSONObject after=new JSONObject().put("navigation",2).put("pageIdentityDigest","b");
        JSONObject raw=new JSONObject().put("accepted",true);
        assertEquals("same_document_url_changed",BrowserActionReceipt.attach("click",new JSONObject(),before,after,true,raw).getJSONObject("actionReceipt").getJSONObject("navigation").getString("state"));
        after.put("navigation",3);assertEquals("navigation_epoch_changed",BrowserActionReceipt.attach("click",new JSONObject(),before,after,true,raw).getJSONObject("actionReceipt").getJSONObject("navigation").getString("state"));
    }
}
