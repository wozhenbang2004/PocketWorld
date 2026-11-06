package dev.pocketengine.runtime;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.runtime.usage.InferenceFailure;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/**
 * Bounded interpretation of failed HTTP responses from either transport stack.
 * Raw provider bodies remain local to this reader; callers receive only native
 * failure facts and fixed application messages. This class neither dispatches
 * requests nor decides cancellation, retry, route switching or tool admission.
 */
final class HttpFailureEvidence {
    private HttpFailureEvidence() { }

    /** The gateway retains ownership of its public exception and logical state. */
    record Evidence(String message, InferenceFailure observation, String diagnosticReason) { }

    static Evidence read(ModelConfig captured,int status,java.util.List<String> retryAfter,java.util.List<String> date,java.util.List<String> errorTypes,InputStream errorBody)throws Exception {
        String bounded=boundedError(errorBody);
        if(!captured.usesBedrock()&&(status==400||status==413)&&contextLimitCode(bounded))return new Evidence("模型明确报告上下文超限；尚未派发工具。",InferenceFailure.local(InferenceFailure.Kind.CONTEXT_LIMIT,status,System.currentTimeMillis()),"UNKNOWN");
        var reason=dev.pocketengine.runtime.models.ModelConnectionFailure.reason(status,bounded);
        InferenceFailure observation=captured.usesBedrock()
                ?InferenceFailure.bedrockHttp(status,withoutCapturedSecret(retryAfter,captured),withoutCapturedSecret(date,captured),withoutCapturedSecret(errorTypes,captured),System.currentTimeMillis(),bounded)
                :InferenceFailure.classifiedHttp(status,withoutCapturedSecret(retryAfter,captured),withoutCapturedSecret(date,captured),System.currentTimeMillis(),bounded);
        String explanation=dev.pocketengine.runtime.models.ModelConnectionFailure.message(reason);
        if(captured.usesBedrock()){
            if(status==401||status==403||java.util.Set.of("BEDROCK_SIGNATURE_INVALID","BEDROCK_NOT_AUTHORIZED","BEDROCK_INVALID_IDENTITY").contains(observation.nativeCode))explanation="Bedrock 拒绝了原临时身份或签名，请核对该连接的完整会话、到期时间、区域和权限；没有换连接。";
            if("BEDROCK_REQUEST_EXPIRED".equals(observation.nativeCode))explanation="Bedrock 报告请求时间戳已失效，请核对设备时钟和原会话；没有重发请求。";
        }
        return new Evidence(explanation+"（HTTP "+status+"）",observation,reason.name());
    }
    /** Recognize only exact protocol codes; the original recovery owner decides compaction. */
    private static boolean contextLimitCode(String text){try{JSONObject error=new JSONObject(text).optJSONObject("error");return error!=null&&java.util.Set.of("context_length_exceeded","context_window_exceeded","prompt_too_long").contains(error.optString("code"));}catch(Exception unavailable){return false;}}
    // Consume and close the supplied body here. Oversized, malformed, zero-progress or
    // unreadable input yields no body evidence, never a truncated JSON inference.
    private static String boundedError(InputStream input){
        if(input==null)return "";
        try(InputStream body=input){
            // readNBytes is Android API 33; retain minSdk 30 using the same hard byte bound.
            byte[] bytes=new byte[16_385];int length=0;
            while(length<bytes.length){int read=body.read(bytes,length,bytes.length-length);if(read<0)break;if(read==0)return "";length+=read;}
            if(length>16_384)return "";
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes,0,length)).toString();
            return text;
        }catch(Exception unavailable){return "";}
    }
    // Reject the whole header collection if it contains captured credentials;
    // removing just the matching entry could turn an ambiguous header into an
    // apparently valid retry hint. The existing sentinel preserves invalidity.
    private static java.util.List<String> withoutCapturedSecret(java.util.List<String> values,ModelConfig captured) {
        if(values!=null)for(String value:values)if(captured.containsSensitive(value))return java.util.List.of("invalid_header");
        return values;
    }
}
