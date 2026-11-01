package dev.pocketengine.runtime.usage;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.json.JSONObject;

/** One shared native failure explanation for the connection page and original task receipt. */
public final class InferenceFailurePresentation {
    private InferenceFailurePresentation(){}
    public record View(String title,List<String> lines) { }
    public static View fromFailure(JSONObject supplied,long now) {
        List<String> lines=new ArrayList<>();
        try {
            InferenceFailure failure=InferenceFailure.checked(supplied);String title;
            switch(failure.kind) {
                case HTTP -> {
                    int status=failure.httpStatus;
                    title=status==401?"这次请求未通过身份验证":status==403?"这次请求被服务拒绝":status==429?"服务限制了这次请求":status>=500?"服务返回错误":status>=300&&status<400?"接口返回重定向":"服务未接受这次请求";
                    lines.add("HTTP "+status+" · 来自这次真实响应，未保存响应正文。");
                    if(status==401||status==403)lines.add("请核对这项连接的凭据和权限；不会轮番换号或自动重发。");
                    if(status==429)lines.add("仅凭429不能区分频率、配额或套餐余额，剩余额度仍未知。");
                }
                case RATE_LIMIT -> {title="服务明确报告请求频率限制";lines.add("本次机器错误码明确分类；只允许原连接原模型内的有界重试，不表示配额余额已知。");}
                case QUOTA_EXHAUSTED -> {title="服务报告配额或计费限制";lines.add("没有自动切换账号；具体剩余额度仍未观察到。");}
                case POLICY_REFUSAL -> {title="服务按政策拒绝本次请求";lines.add("没有换号或换模型重试。");}
                case NETWORK -> title="本地连接未能完整结束";
                case TIMEOUT -> title="本次等待超时";
                case PROTOCOL -> title="返回或本机处理未能完整核对";
                case LOCAL_REFUSAL -> title="本机准入未通过";
                case ACCOUNT_GATE -> {title="账号入口未能完成这次请求";if(!failure.nativeCode.isEmpty())lines.add("原生账号门："+failure.nativeCode+"；不是从服务正文猜测的账号状态。");}
                case CANCELLED -> title="本地等待已停止";
                default -> title="本次调用未完成，具体原因未观察到";
            }
            if(failure.kind!=InferenceFailure.Kind.HTTP&&failure.httpStatus>0)lines.add("已观察到HTTP "+failure.httpStatus+"，但这不证明模型决策或本机处理完成。");
            lines.add("观察时间："+time(failure.observedAt)+"。");
            if(failure.partialTextReceived)lines.add("已收到部分模型文本，但没有完整决策；没有自动重发这次请求。");
            JSONObject retry=failure.retry();
            if(!"VALID".equals(retry.optString("status")))lines.add("Retry-After / 冷却时间：未知（"+("INVALID".equals(retry.optString("status"))?"字段不合法、重复、时间已倒置或超过本地展示范围":"未返回可核对的等待时间")+"）；不是立即可用。");
            else {
                long until=retry.getLong("estimatedNotBefore");String basis=retry.getString("basis");
                String source=switch(InferenceFailure.RetryBasis.valueOf(basis)) {
                    case DELTA_SECONDS_LOCAL_RECEIPT -> "响应Retry-After秒数＋本机接收时钟";
                    case RESPONSE_DATE_ESTIMATE -> "响应Retry-After HTTP-date与Date差值＋本机接收时钟估算";
                    case LOCAL_CLOCK_ESTIMATE -> "响应Retry-After HTTP-date按本机时钟估算（没有可用响应Date，时钟偏差未知）";
                    case NATIVE_ACCOUNT_GATE -> "原生账号门给出的等待时间（非HTTP头，不是套餐重置）";
                    default -> "来源不可核对";
                };
                lines.add("等待来源："+source+"。");
                lines.add((now>=until?"服务建议的等待时间已过：":"服务建议至少等待至：")+time(until)+"；不证明连接恢复或下一次一定可用。");
            }
            lines.add("是否已被提供方处理、收费或退费仍未知；不会重放旧请求，也不会把未知用量改成成功或0。");
            return new View(title,immutable(lines));
        } catch(Exception invalid) {
            return new View("失败细节暂不可核对",List.of("保留这次调用的原状态与用量；没有把缺失或不合法的诊断视为连接恢复。"));
        }
    }
    private static String time(long at){return DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(at));}
    private static List<String> immutable(List<String> value){return java.util.Collections.unmodifiableList(new ArrayList<>(value));}
}
