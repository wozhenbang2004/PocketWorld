package dev.pocketengine.ui;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;

/** Same-operation enum projection only; not a phone/WebView pixel assertion. */
public final class RuntimeActivityCaptionTest {
    private JSONObject activity(String state)throws Exception{return new JSONObject().put("format","pocketworld.runtime-activity.v1").put("operationId","original-only").put("state",state);}
    @Test public void unrelatedOrUnrecognizedProjectionDoesNotBecomeOriginalExecutionState()throws Exception{
        assertNull(ProjectWorkbenchEvidenceKt.runtimeActivityCaption(null,"original-only"));
        assertNull(ProjectWorkbenchEvidenceKt.runtimeActivityCaption(activity("running"),"another-operation"));
        assertNull(ProjectWorkbenchEvidenceKt.runtimeActivityCaption(activity("running").put("format","untrusted_external_json"),"original-only"));
        String unknown=ProjectWorkbenchEvidenceKt.runtimeActivityCaption(activity("external-secret-state"),"original-only");assertTrue(unknown.contains("不自动重放"));assertFalse(unknown.contains("external-secret-state"));
    }
    @Test public void quietInputAckAndBackgroundWindowAreFactsWithoutReadinessOrNewAuthority()throws Exception{
        JSONObject original=activity("inferred_idle").put("text","private-argument-canary").put("input",new JSONObject().put("state","delivered").put("text","private-input-canary")).put("observationWindow",new JSONObject().put("phase","background_observation"));
        String caption=ProjectWorkbenchEvidenceKt.runtimeActivityCaption(original,"original-only");assertTrue(caption.contains("同一个原执行"));assertTrue(caption.contains("总期限未延长"));assertTrue(caption.contains("不证明正在等输入或已经完成"));assertTrue(caption.contains("未证明程序已经处理"));assertFalse(caption.contains("canary"));
        assertTrue(ProjectWorkbenchEvidenceKt.runtimeActivityCaption(activity("exited"),"original-only").contains("业务结果仍需验收"));
    }
}
