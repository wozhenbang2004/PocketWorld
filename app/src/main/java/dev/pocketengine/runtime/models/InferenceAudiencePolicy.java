package dev.pocketengine.runtime.models;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import java.util.Objects;

/** Billing eligibility precedes transport. A loopback bearer or a group role is not plan authority. */
public final class InferenceAudiencePolicy {
    private InferenceAudiencePolicy() { }

    public static void require(ModelConfig selected,NativeInferenceScope scope,RunControl original)throws Exception {
        Objects.requireNonNull(selected);
        if(original!=null)original.check();
        if(scope!=null)scope.check(original);
        if(!selected.usesAccount())return; // API-key authorization and exact-route gates remain with the existing host.
        if(scope==null)throw new Rejected("INFERENCE_AUDIENCE_UNPROVEN","缺少本应用本人活动的原生来源；未发起 ChatGPT 套餐请求。");
        if(scope.entry==NativeInferenceScope.Entry.EXTERNAL_RELAY)
            throw new Rejected("CHATGPT_PLAN_APP_ONLY","ChatGPT 账号仅供本应用本人活动使用，不能作为其他工具的通用反代；请明确选择自己的 API-key 连接。");
        if(scope.entry==NativeInferenceScope.Entry.EXTERNAL_GROUP)
            throw new Rejected("CHATGPT_PLAN_GROUP_SHARING_DENIED","群请求不能使用机主的 ChatGPT 套餐；请在本机明确配置获授权的 API-key 群服务路线。未发起套餐调用。");
        if(scope.entry==NativeInferenceScope.Entry.PAIRED_DEVICE_GOAL)
            throw new Rejected("CHATGPT_DEVICE_GOAL_AUDIENCE_UNPROVEN","已配对目标尚缺原账户协议允许的本机本人 App 活动证明；设备配对与模型策略不是共享套餐授权，未发起套餐调用。");
        if(scope.entry!=NativeInferenceScope.Entry.OWNER_APP&&scope.entry!=NativeInferenceScope.Entry.OWNER_BACKGROUND)
            throw new Rejected("INFERENCE_AUDIENCE_UNPROVEN","此原生来源没有明确的本人 App 套餐资格；未发起套餐调用。");
    }
    public static final class Rejected extends SecurityException {
        public final String code;
        private Rejected(String code,String message){super(message);this.code=code;}
    }
}
