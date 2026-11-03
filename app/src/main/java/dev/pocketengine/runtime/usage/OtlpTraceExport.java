package dev.pocketengine.runtime.usage;

import dev.pocketengine.core.RunControl;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONObject;

/** OTLP specification 1.11.1, HTTP/JSON: https://opentelemetry.io/docs/specs/otlp/ .
 * Independently implemented protocol adapter; no automatic retries or redirects. */
public final class OtlpTraceExport {
    private static final int MAX_RESPONSE_BYTES=65536;
    private static final OkHttpClient CLIENT=new OkHttpClient.Builder().retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false).connectTimeout(10,TimeUnit.SECONDS)
            .readTimeout(15,TimeUnit.SECONDS).writeTimeout(15,TimeUnit.SECONDS).callTimeout(20,TimeUnit.SECONDS).build();
    private OtlpTraceExport() { }
    public static String endpoint(String value){
        if(value==null||value.length()>2048)throw new IllegalArgumentException("请输入明确的OTLP traces地址");
        URI uri;try{uri=URI.create(value);}catch(IllegalArgumentException bad){throw new IllegalArgumentException("OTLP地址格式无效");}
        String host=uri.getHost();boolean loopback="127.0.0.1".equals(host)||"localhost".equalsIgnoreCase(host)||"[::1]".equals(host)||"::1".equals(host);
        if(host==null||uri.getUserInfo()!=null||uri.getRawQuery()!=null||uri.getFragment()!=null
                ||!("https".equals(uri.getScheme())||"http".equals(uri.getScheme())&&loopback)
                ||uri.getPath()==null||!uri.getPath().endsWith("/v1/traces"))
            throw new IllegalArgumentException("请使用无凭据和查询参数的HTTPS /v1/traces地址；本机收集器可用HTTP");
        return uri.toASCIIString();
    }
    public static JSONObject send(String endpoint,JSONObject payload,int spanCount,RunControl control)throws Exception {
        byte[] data=payload.toString().getBytes(StandardCharsets.UTF_8);if(data.length>1048576)throw new IllegalArgumentException("本次诊断超过导出上限");
        control.check();Call call=CLIENT.newCall(new Request.Builder().url(endpoint(endpoint))
                .header("Accept","application/json").header("Accept-Encoding","identity")
                .post(RequestBody.create(MediaType.get("application/json"),data)).build());
        Runnable cancel=call::cancel;control.onCancel(cancel);
        try {
            control.check();try(Response response=call.execute()){
                int status=response.code();
                if(status!=200)return new JSONObject().put("outcome",status>=400&&status<500&&status!=408&&status!=429?"rejected":"unknown")
                        .put("httpStatus",status).put("acceptedSpans",JSONObject.NULL).put("automaticRetry",false);
                if(response.body()==null||response.body().contentType()==null||!"application".equals(response.body().contentType().type())||!"json".equals(response.body().contentType().subtype()))
                    return unknown("unrecognized_response",status);
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(var input=response.body().byteStream()){
                    byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))!=-1){control.check();if(bytes.size()+count>MAX_RESPONSE_BYTES)return unknown("response_limit",status);bytes.write(buffer,0,count);}
                }
                JSONObject result;try{result=new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));}catch(Exception invalid){return unknown("invalid_json",status);}
                if(result.has("partialSuccess")){
                    JSONObject partial=result.optJSONObject("partialSuccess");if(partial==null)return unknown("invalid_partial_response",status);
                    long rejected=0;try{Object count=partial.opt("rejectedSpans");if(count!=null&&count!=JSONObject.NULL)rejected=new java.math.BigDecimal(count.toString()).longValueExact();}catch(Exception invalid){return unknown("invalid_rejected_count",status);}
                    if(rejected<0||rejected>spanCount)return unknown("invalid_rejected_count",status);
                    return new JSONObject().put("outcome",rejected>0?"partial":"accepted_with_warning").put("httpStatus",status)
                            .put("rejectedSpans",rejected).put("acceptedSpans",spanCount-rejected)
                            .put("collectorMessagePresent",!partial.optString("errorMessage").isEmpty()).put("automaticRetry",false);
                }
                // The empty JSON service response is the protocol's full-acceptance receipt.
                if(result.length()!=0)return unknown("unrecognized_response_fields",status);
                return new JSONObject().put("outcome","accepted").put("httpStatus",status).put("acceptedSpans",spanCount).put("automaticRetry",false);
            }
        }catch(java.io.IOException|InterruptedException uncertain){return new JSONObject().put("outcome",control.isCancelled()?"unknown_after_cancel":"unknown")
                .put("acceptedSpans",JSONObject.NULL).put("automaticRetry",false);}
        finally{control.removeCancelAction(cancel);}
    }
    private static JSONObject unknown(String reason,int status)throws Exception{return new JSONObject().put("outcome","unknown").put("reason",reason).put("httpStatus",status).put("acceptedSpans",JSONObject.NULL).put("automaticRetry",false);}
}
