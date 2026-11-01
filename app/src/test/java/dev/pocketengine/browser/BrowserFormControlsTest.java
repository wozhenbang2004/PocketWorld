package dev.pocketengine.browser;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pure protocol evidence, not a DOM engine/provider/device test. */
public final class BrowserFormControlsTest {
    static JSONObject j(Object... pairs)throws Exception {JSONObject out=new JSONObject();for(int i=0;i<pairs.length;i+=2)out.put((String)pairs[i],pairs[i+1]);return out;}
    static JSONObject option(int index,String value,String label)throws Exception {return j("optionRef","o"+index,"index",index,"value",value,"label",label,"disabled",false,"selected",index==0,"complete",true);}
    static JSONObject select(int count)throws Exception {JSONArray options=new JSONArray();for(int i=0;i<count;i++)options.put(option(i,"value"+i,"选项"+i));return j("kind","select","disabled",false,"readOnly",false,"multiple",false,"selectedIndex",0,"optionsCount",count,"optionsOmitted",0,"options",options);}
    static JSONObject check(String kind,boolean checked)throws Exception {return j("kind",kind,"disabled",false,"readOnly",false,"checked",checked,"indeterminate",false);}
    static JSONObject preview(JSONObject control)throws Exception {return j("ref","n0","tag",control.optString("kind").equals("select")?"SELECT":"INPUT","type",control.optString("kind"),"text","配送方式","control",control);}
    static JSONObject args(JSONObject control,Object... extra)throws Exception {JSONObject args=j("ref","n0","_nativeTargetPreview",preview(control));for(int i=0;i<extra.length;i+=2)args.put((String)extra[i],extra[i+1]);return args;}

    @Test public void optionsAreActualRefsAndBoundedStateNotFreeModelValues()throws Exception {
        JSONObject c=select(3),p=preview(c),a=j("optionRef","o2");assertEquals("value2",BrowserFormControls.requireTarget(p,a).getString("value"));assertEquals(3,BrowserFormControls.project(c).getJSONArray("options").length());assertTrue(BrowserFormControls.approvalSummary(p,a).contains("选项2"));
        assertThrows(Exception.class,()->BrowserFormControls.requireTarget(p,j("optionRef","o3")));assertThrows(Exception.class,()->BrowserFormControls.project(select(33)));
    }
    @Test public void malformedOrIncompleteOptionsNeverAcquireWriteEligibility()throws Exception {
        for(String field:new String[]{"disabled","complete","optionRef","value"}){
            JSONObject c=select(1),o=c.getJSONArray("options").getJSONObject(0);o.put(field,field.equals("disabled")?true:field.equals("complete")?false:field.equals("optionRef")?"o4":"x".repeat(257));
            assertThrows(field,Exception.class,()->BrowserFormControls.requireTarget(preview(c),j("optionRef","o0")));
        }
    }
    @Test public void projectionCannotInventOrModifyARegisteredOption()throws Exception {
        JSONObject original=select(3),shown=select(3);shown.getJSONArray("options").remove(2);JSONObject p=BrowserFormControls.presented(original,shown);assertEquals(2,p.getJSONArray("options").length());assertEquals(1,p.getInt("optionsOmitted"));assertThrows(Exception.class,()->BrowserFormControls.requireTarget(preview(p),j("optionRef","o2")));
        shown.getJSONArray("options").getJSONObject(0).put("value","model-context-exfiltration");assertThrows(SecurityException.class,()->BrowserFormControls.presented(original,shown));
    }
    @Test public void checkedBooleansAreStrictAndRadioUncheckIsNotAToggleShortcut()throws Exception {
        assertEquals(false,BrowserFormControls.requireTarget(preview(check("checkbox",false)),j("checked",true)).getBoolean("checked"));assertTrue(BrowserFormControls.approvalSummary(preview(check("radio",false)),j("checked",true)).contains("已选"));
        assertThrows(Exception.class,()->BrowserFormControls.requireTarget(preview(check("checkbox",false)),j("checked","true")));assertThrows(Exception.class,()->BrowserFormControls.requireTarget(preview(check("radio",true)),j("checked",false)));assertNotNull(BrowserFormControls.requireTarget(preview(check("radio",false)),j("checked",false)));
    }
    @Test public void disabledReadonlyMixedAndMultipleHaveExplicitOwnerBoundaries()throws Exception {
        for(String field:new String[]{"disabled","readOnly","indeterminate"}){JSONObject c=check("checkbox",false).put(field,true);assertThrows(BrowserFormControls.OwnerRequired.class,()->BrowserFormControls.requireTarget(preview(c),j("checked",true)));}
        assertTrue(assertThrows(BrowserFormControls.OwnerRequired.class,()->BrowserFormControls.requireTarget(preview(select(2).put("multiple",true)),j("optionRef","o1"))).getMessage().contains("主人"));assertThrows(Exception.class,()->BrowserFormControls.requireTarget(j("ref","n0"),j("checked",true)));
    }
    @Test public void formWritesKeepMutationDestinationHitAndObservedStateFences()throws Exception {
        String script=BrowserDomAction.script("select_option",args(select(2),"optionRef","o1"),"https://example.test/","obs","key","native-token");
        int write=script.indexOf("setter.call(e,optionIndex)");assertTrue(write>0);for(String fence:new String[]{"observation_changed","element_occluded","destination_changed","form_state_changed","option_changed"})assertTrue(fence,script.indexOf(fence)<write);assertTrue(script.contains("state.optionNodes[0][optionIndex]!==option"));assertFalse(script.contains("submit("));assertFalse(script.contains(".focus("));
        assertThrows(SecurityException.class,()->BrowserDomAction.script("set_checked",j("ref","n0","checked",true),"https://example.test/","obs","key","token"));
    }
    @Test public void formLiteralsCannotBecomeArbitraryJavascript()throws Exception {
        String literal="');fetch('https://bad.test');//🙂";JSONObject c=select(2);c.getJSONArray("options").getJSONObject(1).put("value",literal);
        String script=BrowserDomAction.script("select_option",args(c,"optionRef","o1"),"https://example.test/","obs","key","native-token");assertTrue(script.contains("option.value!=="+JSONObject.quote(literal)));assertEquals(1,script.split("setter.call\\(e,optionIndex\\)",-1).length-1);
    }
    @Test public void actualNoOpReceiptDoesNotClaimAnotherDispatchOrBusinessSuccess()throws Exception {
        JSONObject a=args(check("checkbox",true),"checked",true),raw=j("accepted",true,"noOp",true);
        JSONObject actual=BrowserFormControls.readback(raw,j("status","observed","kind","checkbox","checked",true,"indeterminate",false,"matched",true),a);assertEquals("no_op",actual.getString("status"));
        JSONObject receipt=BrowserActionReceipt.attach("set_checked",a,null,null,true,actual);assertEquals("not_needed",receipt.getJSONObject("actionReceipt").getJSONObject("dispatch").getString("state"));assertFalse(receipt.getBoolean("verifiedBusinessSuccess"));assertFalse(receipt.getBoolean("automaticReplayAllowed"));
    }
    @Test public void controlledRevertIsUnmetAndNeverInvitesASecondClick()throws Exception {
        JSONObject a=args(check("checkbox",false),"checked",true),out=BrowserFormControls.readback(j("accepted",true,"noOp",false),j("status","observed","kind","checkbox","checked",false,"indeterminate",false,"matched",false),a);
        assertEquals("failed",out.getString("status"));assertEquals("unmet",out.getJSONObject("controlEvidence").getString("state"));assertTrue(out.getString("message").contains("没有重试"));assertFalse(BrowserActionReceipt.attach("set_checked",a,null,null,true,out).getBoolean("automaticReplayAllowed"));
    }
    @Test public void lostReadbackRemainsUnknownRatherThanUndispatchedOrRetried()throws Exception {
        JSONObject out=BrowserFormControls.readback(j("accepted",true,"noOp",false),j("status","unavailable","code","form_element_changed"),args(select(2),"optionRef","o1"));assertEquals("unknown",out.getString("status"));assertEquals(JSONObject.NULL,out.get("accepted"));assertEquals("unknown",BrowserActionReceipt.attach("select_option",new JSONObject(),null,null,true,out).getJSONObject("actionReceipt").getJSONObject("dispatch").getString("state"));
    }
    @Test public void selectedValueAndCheckedReadbackMustActuallyMatchTheApprovedTarget()throws Exception {
        JSONObject a=args(select(2),"optionRef","o1");assertThrows(Exception.class,()->BrowserFormControls.readback(j("accepted",true),j("status","observed","kind","select","selectedIndex",1,"value","wrong","valueTruncated",false,"matched",true),a));
        JSONObject met=BrowserFormControls.readback(j("accepted",true,"noOp",false),j("status","observed","kind","select","selectedIndex",1,"value","value1","valueTruncated",false,"matched",true),a);assertEquals("met",met.getJSONObject("controlEvidence").getString("state"));
        assertThrows(Exception.class,()->BrowserFormControls.readback(j("accepted",true),j("status","observed","kind","radio","checked",true,"indeterminate",false,"matched",true),args(check("checkbox",false),"checked",true)));
    }
    @Test public void delayedReadbackIsReadOnlyAndNeverRetargetsOrRegistersMoreRefs()throws Exception {
        String script=BrowserFormControls.readbackScript("key","obs","https://example.test/","token");for(String token:new String[]{".click(",".dispatchEvent(",".focus(",".querySelector","fetch(",".submit(",".loadUrl("})assertFalse(token,script.contains(token));assertTrue(script.contains("e.options[p.desired]!==p.option"));assertTrue(script.contains("e.ownerDocument!==document"));assertTrue(script.contains("state.formReadback.token"));
    }
}
