package dev.pocketengine.features.proxy.accounts;

import java.util.Set;
import org.json.JSONObject;

/** Public SIWC recovery categories. Provider prose is never displayed or retained. */
public final class ChatGptRecovery {
    public static final String MANAGE_USAGE_URL="https://chatgpt.com/settings/usage";
    private static final Set<String> CODES=Set.of("invalid_grant","invalid_refresh_token","token_expired","refresh_token_expired",
        "refresh_token_invalidated","refresh_token_reused","invalid_client","subscription_sharing_user_not_eligible",
        "subscription_sharing_usage_limit_exceeded","subscription_sharing_usage_unavailable","subscription_sharing_unsupported_capability",
        "subscription_sharing_route_not_supported","subscription_sharing_invalid_user","subscription_sharing_user_unavailable",
        "chatpass_v2_scope_not_authorized","chatpass_v2_invalid_authorization_context");
    private ChatGptRecovery(){}
    /** tokenContext is empty, exchange, or refresh; the caller supplies its own fixed route context. */
    public static GatewayFailure failure(int status,JSONObject body,String shape,String requestId,String tokenContext,long retryAt) {
        Object raw=body==null?null:body.opt("error");JSONObject error=raw instanceof JSONObject?(JSONObject)raw:null;
        String provider=raw instanceof String?(String)raw:error==null?"":error.optString("code");
        if(!CODES.contains(provider))provider="";
        String param=error==null?"":error.optString("param");
        // Only documented field paths; no arbitrary values, headers, prose or reflected user data.
        if(!param.matches("(?:input|model|tools|service_tier|include|store|stream|instructions)(?:\\[[0-9]{1,3}\\]|\\.(?:content|type|image_url|detail|name|parameters|tools|supported|text|function|arguments))*"))param="";
        String id=requestId!=null&&requestId.matches("req_[A-Za-z0-9_-]{1,120}")?requestId:"";
        String code;long delay=0;
        switch(provider){
            case "subscription_sharing_usage_limit_exceeded":code="CHATGPT_USAGE_LIMIT_REACHED";break;
            case "subscription_sharing_user_not_eligible":code="CHATGPT_PLAN_NOT_ELIGIBLE";break;
            case "subscription_sharing_unsupported_capability":code="CHATGPT_UNSUPPORTED_CAPABILITY";break;
            case "subscription_sharing_route_not_supported":code="CHATGPT_ROUTE_NOT_SUPPORTED";break;
            case "chatpass_v2_scope_not_authorized":case "chatpass_v2_invalid_authorization_context":code="CHATGPT_DIRECT_PERMISSION_REJECTED";break;
            case "subscription_sharing_invalid_user":code="CHATGPT_ACCOUNT_PERMISSION_REJECTED";break;
            case "subscription_sharing_usage_unavailable":case "subscription_sharing_user_unavailable":code="PROVIDER_UNAVAILABLE";delay=retryAt;break;
            case "invalid_client":code=tokenContext.isEmpty()?"UNSUPPORTED_PROVIDER_REQUEST":"PROVIDER_CLIENT_REJECTED";break;
            case "invalid_grant":case "invalid_refresh_token":case "token_expired":case "refresh_token_expired":case "refresh_token_invalidated":case "refresh_token_reused":
                code=tokenContext.equals("refresh")?"REAUTHORIZATION_REQUIRED":tokenContext.equals("exchange")?"AUTHORIZATION_CODE_EXPIRED":"CHATGPT_ACCOUNT_PERMISSION_REJECTED";break;
            default:
                if(status==401)code=tokenContext.isEmpty()?"CHATGPT_ACCOUNT_PERMISSION_REJECTED":"PROVIDER_GRANT_REJECTED";
                else if(status==403)code=tokenContext.isEmpty()?"CHATGPT_POLICY_RESTRICTED":"PROVIDER_GRANT_REJECTED";
                else if(status==400)code=tokenContext.isEmpty()?"UNSUPPORTED_PROVIDER_REQUEST":"PROVIDER_GRANT_REJECTED";
                else if(status==429){code="PROVIDER_RATE_LIMITED";delay=retryAt;}
                else if(status>=500){code="PROVIDER_UNAVAILABLE";delay=retryAt;}
                else code=status==0?"PROVIDER_RESPONSE_FAILED":"PROVIDER_HTTP_ERROR";
        }
        return new GatewayFailure(code,delay,status,provider,param,id,Set.of("json_error","json_detail","json_other","non_json","empty","stream_error").contains(shape)?shape:"");
    }
    public static GatewayFailure streamFailure(JSONObject error) {
        JSONObject body=new JSONObject();try{body.put("error",error==null?new JSONObject():error);}catch(Exception ignored){}
        return failure(0,body,"stream_error","","",System.currentTimeMillis()+60_000);
    }
    /** Empty means the older host-specific messages should handle this code. */
    public static String message(String code) {
        switch(code){
            case "CHATGPT_USAGE_LIMIT_REACHED":return "ChatGPT 套餐或此应用的用量限制已到达。已暂停套餐请求，请在 Manage usage 查看；这里没有余额或重置时间。";
            case "CHATGPT_PLAN_NOT_ELIGIBLE":return "当前 ChatGPT 账号暂不具备用套餐连接此应用的资格。请查看套餐设置，或明确选择 API key 连接；重复登录不会解除限制。";
            case "CHATGPT_POLICY_RESTRICTED":return "ChatGPT 的地区或政策限制阻止了此次请求。请检查官方使用条件；重复登录不会解除限制。";
            case "CHATGPT_ACCOUNT_PERMISSION_REJECTED":return "ChatGPT 拒绝了本次账号权限。登录身份已保留；请核对授权设置，确认授权已撤销时再重新登录。";
            case "CHATGPT_DIRECT_PERMISSION_REJECTED":case "PLAN_USAGE_PERMISSION_REQUIRED":return "已保留 ChatGPT 登录身份，但尚未授权使用套餐推理。可主动重新授权套餐权限，或选择 API key 连接。";
            case "CHATGPT_UNSUPPORTED_CAPABILITY":return "当前 ChatGPT 模型不支持此次输入或能力。请检查所选模型；原请求没有自动重试。";
            case "CHATGPT_ROUTE_NOT_SUPPORTED":return "此 ChatGPT 连接不支持该请求入口，请更新应用或选择已支持的连接。";
            case "ACCOUNT_IMAGE_CAPABILITY_REQUIRED":return "所选账号、模型或授权版本没有已核实的图片能力。请刷新目录并重新选择支持图片的模型；图片未发送。";
            case "AUTHORIZATION_CODE_EXPIRED":case "LOGIN_EXPIRED":return "ChatGPT 登录已过期。请主动重新开始登录；旧授权码不会重试。";
            case "AUTHORIZATION_DECLINED":return "你已拒绝 ChatGPT 登录。可保留 API key 连接，或稍后主动重新登录。";
            case "PROVIDER_UNAVAILABLE":case "PROVIDER_NETWORK_ERROR":return "ChatGPT 暂时不可用或网络中断。登录信息已保留，请稍后手动再试。";
            case "REFRESH_VERIFICATION_PENDING":return "ChatGPT 续期凭据已安全保存，身份核对尚未完成。请稍后在账号页刷新授权；不会重复使用旧的续期凭据。";
            case "REFRESH_CHECKPOINT_UNAVAILABLE":return "ChatGPT 已返回新的续期凭据，但本机未能安全保存。已停止使用旧凭据，请检查存储后重新登录。";
            default:return "";
        }
    }
}
