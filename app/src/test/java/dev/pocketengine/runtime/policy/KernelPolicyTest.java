package dev.pocketengine.runtime.policy;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class KernelPolicyTest {
    @Test public void instructionQuestionsCannotBecomeDeviceActions()throws Exception {
        OwnerIntent intent=new OwnerIntent("告诉我如何转账");assertEquals(OwnerIntent.Kind.DISCUSS,intent.kind());assertFalse(intent.deviceReadAllowed());assertFalse(intent.permits("phone_click_node",s->true));
        intent.update("帮我打开设置");assertEquals(OwnerIntent.Kind.ACT,intent.kind());assertTrue(intent.permits("phone_click_node",s->true));
        intent.update("只读研究，不要修改文件");assertEquals(OwnerIntent.Kind.READ,intent.kind());assertTrue(intent.permits("inspect",s->false));assertFalse(intent.permits("write",s->true));
    }
    @Test public void ordinaryClicksKeepTheirExistingAuthorization()throws Exception {
        JSONObject page=page("搜索");assertNull(PhoneActionPolicy.before("phone_click_node",new JSONObject().put("nodeId","n"),page));
        assertTrue(PhoneActionPolicy.routeNavigationLabel("搜索"));assertFalse(PhoneActionPolicy.routeNavigationLabel("发送"));
    }
    @Test public void paymentTargetAndUnknownCoordinateOnPaymentPageHandoffBeforeDispatch()throws Exception {
        JSONObject page=page("确认支付 ¥12.00");for(JSONObject args:new JSONObject[]{new JSONObject().put("nodeId","n"),new JSONObject().put("x",300).put("y",900)}){
            JSONObject blocked=PhoneActionPolicy.before(args.has("nodeId")?"phone_click_node":"phone_tap",args,page);assertFalse(blocked.getBoolean("accepted"));assertEquals("not_dispatched",blocked.getString("effectStatus"));assertFalse(blocked.getBoolean("approvalGranted"));
        }
        assertNull(PhoneActionPolicy.before("phone_observe",new JSONObject(),page));
    }
    @Test public void obstacleHintDoesNotResolveOrAuthorizeAnything()throws Exception {
        JSONObject receipt=RunOutcomeTools.obstacle(new JSONObject().put("kind","captcha").put("detail","需要真人验证"));assertFalse(receipt.getBoolean("obstacleResolved"));assertFalse(receipt.getBoolean("effectsDispatched"));
        JSONObject input=new JSONObject().put("kind","access_blocked").put("reason","访问限制").put("performed","已读取首页").put("remaining","未提交").put("recoverable",true);JSONObject declaration=RunOutcomeTools.declaration(input);assertFalse(declaration.getBoolean("automaticRetryAuthorized"));assertFalse(declaration.getBoolean("businessVerified"));assertTrue(RunOutcomeTools.summary(input).contains("未提交"));
        input.put("taskId","forged");assertThrows(IllegalArgumentException.class,()->RunOutcomeTools.declaration(input));
    }
    private static JSONObject page(String label)throws Exception{return new JSONObject().put("observationId","o").put("nodes",new JSONArray().put(new JSONObject().put("id","n").put("text",label).put("clickable",true)));}
}
