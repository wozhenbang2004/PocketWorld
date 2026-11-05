package dev.pocketengine.features.plugins;

import org.json.JSONObject;

/** Native protocol adapter. Remote bodies stay data and cannot supply host outcome
 * metadata. A received HTTP response is not verified remote business success. */
public final class PluginToolReceipts {
    private PluginToolReceipts(){}
    static JSONObject received(String operation,JSONObject result,boolean mcp)throws Exception{
        if(result==null)throw new IllegalStateException("Missing actual plugin response");
        JSONObject receipt=new JSONObject().put("operationId",operation).put("status","response_received").put("verified",false)
                .put("verifiedBusinessSuccess",false).put("automaticReplayAllowed",false).put("toolResult",new JSONObject(result.toString()));
        return mcp?completedMcp(receipt,result):receipt.put("ok",true);
    }
    /** Only native adapters that confirmed the original response call this. The
     * provider's protocol fields are data; they never supply execution authority. */
    public static JSONObject completedMcp(JSONObject receipt,JSONObject result)throws Exception{
        if(result==null)throw new IllegalStateException("Missing actual MCP response");
        if(result.has("isError")&&!(result.opt("isError") instanceof Boolean)
                ||result.has("unsupportedInputRequired")&&!(result.opt("unsupportedInputRequired") instanceof Boolean))
            return receipt.put("ok",false).put("status","unknown").put("code","mcp_result_unknown").put("requiresReconciliation",true);
        if("input_required".equals(result.optString("resultType"))||Boolean.TRUE.equals(result.opt("unsupportedInputRequired")))
            return receipt.put("ok",false).put("code","input_required").put("requiresPause",true)
                    .put("message","原MCP调用需要额外输入；请在原任务核对服务端要求，未自动响应或重新调用。");
        if(Boolean.TRUE.equals(result.opt("isError")))
            return receipt.put("ok",false).put("code","mcp_tool_error").put("message","MCP已返回工具错误；请检查原回执并修正请求，不会自动重放。");
        return receipt.put("ok",true);
    }
}
