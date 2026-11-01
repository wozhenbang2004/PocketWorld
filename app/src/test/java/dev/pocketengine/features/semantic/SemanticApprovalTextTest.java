package dev.pocketengine.features.semantic;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class SemanticApprovalTextTest {
    private JSONObject capability(JSONObject request)throws Exception {
        return new JSONObject().put("request",request).put("target",new JSONObject().put("label","已选择的编辑器").put("packageName","review.editor").put("component","review.editor/.Main"))
                .put("targetIdentity",new JSONObject().put("versionCode",17).put("certificates",new JSONArray().put("fixed-certificate-hash")));
    }
    @Test public void calendarShowsExactParametersUtcOffsetAndRealReceiverWarningWithoutMutatingApproval()throws Exception {
        JSONObject request=SemanticCalendarRequestTest.draft().put("timeZone","America/New_York").put("location","实际地点").put("description","第一行\n第二行");
        JSONObject capability=capability(request);String original=capability.toString(),text=SemanticApprovalText.describe(capability);
        assertTrue(text,text.contains("实际地点")&&text.contains("第一行\n第二行")&&text.contains("开始 UTC：")&&text.contains("-04:00")&&text.contains("America/New_York"));
        assertTrue(text.contains("第三方可能自动保存或创建事件")&&text.contains("退出不保证取消")&&text.contains("实际显示时区可能不同"));
        assertTrue(text.contains("review.editor/.Main")&&text.contains("版本：17")&&text.contains("fixed-certificate-hash")&&text.contains("手机主屏"));
        assertEquals(original,capability.toString());
    }
    @Test public void mailShowsRecipientSubjectAndWholeBody()throws Exception {
        String text=SemanticApprovalText.describe(capability(new JSONObject().put("kind","email_draft").put("to","owner@example.org").put("subject","已审主题").put("body","第一行\n完整正文")));
        assertTrue(text.contains("owner@example.org")&&text.contains("已审主题")&&text.contains("第一行\n完整正文")&&text.contains("启动回执不代表业务完成"));
    }
    @Test public void incompleteNativeMetadataDoesNotPresentAnApprovalAsSafe() {
        assertTrue(SemanticApprovalText.describe(new JSONObject()).contains("请拒绝这次操作"));
    }
}
