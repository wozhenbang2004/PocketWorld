package dev.pocketengine.browser;
import static org.junit.Assert.*;
import org.junit.Test;
import org.json.JSONObject;
public final class BrowserTextControlsTest {
    static JSONObject j(Object... v)throws Exception{return BrowserReadScopeTest.j(v);}
    @Test public void typeRollbackAndReplacementAreNotRetryableSuccesses()throws Exception {
        JSONObject dispatched=j("accepted",true,"noOp",false),args=j("text","中文目标"),actual=j("status","observed","kind","type","value","旧值","valueLength",2,"valueTruncated",false,"focused",true,"matched",false);
        JSONObject mismatch=BrowserTextControls.readback(dispatched,actual,args);assertEquals("failed",mismatch.getString("status"));assertFalse(mismatch.getBoolean("automaticReplayAllowed"));
        JSONObject missing=BrowserTextControls.readback(dispatched,j("status","unavailable"),args);assertEquals("unknown",missing.getString("status"));assertTrue(missing.isNull("accepted"));
        actual.put("value","中文目标").put("valueLength",4).put("matched",true);assertEquals("form_state_read_back",BrowserTextControls.readback(dispatched,actual,args).getString("status"));
    }
    @Test public void finiteKeyAndRealCalendarValidationRejectScriptOrInvalidDate()throws Exception {
        BrowserTextControls.validate("set_date",j("date","2026-11-01"));assertThrows(Exception.class,()->BrowserTextControls.validate("set_date",j("date","2026-02-30")));assertThrows(Exception.class,()->BrowserTextControls.validate("press",j("key","Ctrl+Enter")));BrowserTextControls.validate("press",j("key","Enter"));
        String script=BrowserDomAction.script("type",j("ref","n0","text","中文"),"https://example.test/","observation","key","token");assertTrue(script.contains("state.textReadback"));assertTrue(script.contains("e.isContentEditable"));assertFalse(script.contains("querySelector"));
        String read=BrowserTextControls.readbackScript("key","observation","https://example.test/","token");assertTrue(read.contains("p.node"));assertTrue(read.contains("original_element_changed"));assertFalse(read.contains("querySelector"));
    }
}
