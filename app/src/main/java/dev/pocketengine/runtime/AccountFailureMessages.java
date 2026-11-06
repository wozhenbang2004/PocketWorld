package dev.pocketengine.runtime;

/**
 * User-facing explanation of native account error codes, not a recovery policy.
 * Transport/account code retains original exception identity, retry classification,
 * cancellation precedence and durable receipts. Changing wording here must never
 * grant reauthorization, replay a request or infer that a remote effect completed.
 * Shared ChatGptRecovery messages keep their original priority over local wording.
 */
final class AccountFailureMessages {
    static String describe(String code){
        String recovery=dev.pocketengine.features.proxy.accounts.ChatGptRecovery.message(code);if(!recovery.isEmpty())return recovery;
        switch(code){
            case "ACCOUNT_MODEL_OPTION_UNAVAILABLE":return "当前账号模型目录不再支持所选推理选项，请刷新目录或选服务默认；未发送本次请求。";
            case "ACCOUNT_CONTEXT_BUDGET_EXCEEDED":return "请求超过当前账号模型已知上下文的保守预算，未删历史、未发送；请核对本地输出预留或整理上下文。";
            case "ACCOUNT_IMAGE_BUDGET_REQUIRED":return "该账号模型上下文已知，请先在账号模型选项配置每张图片的本地token预留；未发送图片。";
            case "ACCOUNT_TEXT_CAPABILITY_UNSUPPORTED":return "当前账号模型目录明确不接收文字输入，请选择已声明文字输入的模型；未发送、未自动换模型。";
            case "ACCOUNT_TOOL_CAPABILITY_UNSUPPORTED":return "当前账号模型目录明确不支持工具调用，本次未发送工具或丢弃历史。";
            case "INFERENCE_RECEIPT_STORE_UNAVAILABLE":return "无法保存本次模型调用的原任务用量身份；本次未发送推理请求，请检查存储后再继续。";
            case "CANCELLED":return "账号模型请求已停止；账号可能已停用或退出，旧回复不会继续执行。";
            case "DEADLINE_EXCEEDED":return "账号模型请求超时；没有自动重试，请核对任务后再决定是否继续。";
            case "STALE_ACCOUNT_VERSION":return "账号版本或授权状态与本次请求不一致，请核对当前账号和模型；没有自动切换账号或重放请求。";
            case "STALE_MODEL_CATALOG":return "当前账号目录无法确认所选模型，请刷新目录核对模型是否仍可用；本次未发送推理请求。";
            case "ACCOUNT_SELECTION_STORE_UNAVAILABLE":return "无法确认或保存当前账号模型选择，请检查本机存储后重新选择。";
            case "ACCOUNT_REFRESH_REQUIRED":return "账号令牌需要续期，请在账号页刷新后重新选择模型；没有自动发出或重试推理请求。";
            case "ACCOUNT_DISABLED":case "ACCOUNT_NOT_FOUND":case "ACCOUNT_SELECTION_REQUIRED":return "所选账号已停用、移除或未选择，请到账号页检查。";
            case "REAUTHORIZATION_REQUIRED":case "PLAN_USAGE_PERMISSION_REQUIRED":case "PROVIDER_PERMISSION_DENIED":return "所选账号需要重新授权或未允许使用套餐推理，请到账号页检查。";
            case "ACCOUNT_COOLDOWN":case "RATE_LIMITED":case "QUOTA_EXCEEDED":case "PROVIDER_RATE_LIMITED":return "账号请求正在退避，请稍后手动再试；这不是套餐余额或重置时间。";
            case "GATEWAY_BUSY":case "REFRESH_QUEUE_FULL":return "账号连接正在处理其他请求，请稍后再试；本次没有自动重试。";
            case "GATEWAY_CLOSED":return "账号连接已关闭，请重新打开应用。";
            case "UNSUPPORTED_OR_OVERSIZED_INPUT":return "账号模型请求包含不支持的内容、格式或超出当前大小限制；请核对输入和工具声明。";
            case "INVALID_FUNCTION_RESULT_LINK":return "历史工具结果与模型调用不匹配，已停止请求；请先核对原任务，不能自动重放。";
            case "UNSUPPORTED_PROVIDER_REQUEST":case "UNSUPPORTED_INPUT_TYPE":case "UNSUPPORTED_TOOL_TYPE":case "UNSUPPORTED_PROTOCOL_FIELD":case "UNSUPPORTED_FUNCTION_MODE":case "UNSUPPORTED_PROVIDER_OUTPUT":case "UNSUPPORTED_REASONING_CONTENT":return "账号模型使用了尚未接入的输入、字段或工具，本次未派发动作。";
            case "PROVIDER_UNAVAILABLE":return "账号模型服务暂时不可用；原任务的有限恢复与每次真实调用以任务记录为准。";
            default:return "账号模型没有返回可验证的完整回复，已停止本次执行；请检查账号与网络，不会自动重放或换号。";
        }
    }

    private AccountFailureMessages() {}
}
