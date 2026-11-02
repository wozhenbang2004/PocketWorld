package dev.pocketengine.runtime.usage;

import dev.pocketengine.core.RunControl;
import java.net.URI;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.json.JSONArray;
import org.json.JSONObject;

/** Explicit one-shot OTLP/HTTP JSON export. Neither a task executor nor an automatic uploader. */
public final class OtlpTraceExport {
    private OtlpTraceExport() { }
    public static JSONObject preview(JSONObject archive)throws Exception {
        if(!"pocketworld.content-free-trace.v1".equals(archive.getString("format"))||!Boolean.FALSE.equals(archive.opt("bodyIncluded")))throw new SecurityException("content_free_native_trace_required");
        String run=archive.getString("runId");if(!run.matches("[a-f0-9-]{36}"))throw new SecurityException("native_run_required");JSONArray result=new JSONArray(),spans=archive.getJSONArray("spans");if(spans.length()>1025)throw new IllegalArgumentException("trace_span_limit");
        for(int i=0;i<spans.length();i++){JSONObject row=spans.getJSONObject(i);String name=row.getString("name"),status=row.getString("status"),correlation=row.optString("correlation");if(!name.matches("(?:model|tool|run|task)\\.[A-Za-z0-9_.:-]{1,100}")||!status.matches("[A-Za-z_]{1,40}")||!correlation.isEmpty()&&!correlation.matches("[a-f0-9-]{36}"))throw new SecurityException("native_trace_metadata_required");long start=Math.multiplyExact(row.getLong("startedAt"),1000000),elapsed=row.getLong("elapsedNanos");if(start<0||elapsed<0)throw new IllegalArgumentException("trace_time_invalid");
            result.put(new JSONObject().put("traceId",hex(run).substring(0,32)).put("spanId",hex(run+":"+i).substring(0,16)).put("name",name).put("startTimeUnixNano",Long.toString(start)).put("endTimeUnixNano",Long.toString(Math.addExact(start,elapsed)))
                    .put("attributes",new JSONArray().put(attribute("pocketworld.run_id",run)).put(attribute("pocketworld.outcome",status)).put(attribute("pocketworld.call_id",correlation))));}
        return new JSONObject().put("resourceSpans",new JSONArray().put(new JSONObject().put("resource",new JSONObject().put("attributes",new JSONArray().put(attribute("service.name","PocketWorld")))).put("scopeSpans",new JSONArray().put(new JSONObject().put("scope",new JSONObject().put("name","pocketworld.native")).put("spans",result)))));
    }
    private static JSONObject attribute(String key,String value)throws Exception{return new JSONObject().put("key",key).put("value",new JSONObject().put("stringValue",value));}
    private static String hex(String value)throws Exception {byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder text=new StringBuilder();for(byte b:bytes)text.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return text.toString();}
    public static URI endpoint(String address){URI uri=URI.create(address);String host=uri.getHost();if(uri.getUserInfo()!=null||uri.getFragment()!=null||host==null||!("https".equals(uri.getScheme())||"http".equals(uri.getScheme())&&java.util.Set.of("127.0.0.1","localhost","[::1]","::1").contains(host)))throw new IllegalArgumentException("诊断接收地址需为HTTPS或本机回环HTTP");return uri;}
    public static JSONObject send(JSONObject archive,String address,String header,String secret,RunControl control)throws Exception {
        URI uri=endpoint(address);if(!header.matches("[A-Za-z0-9-]{1,80}")||java.util.Set.of("host","content-length","content-type","content-encoding","transfer-encoding","connection","proxy-authorization","proxy-connection","accept","accept-encoding","te","trailer","upgrade","expect","cookie").contains(header.toLowerCase(java.util.Locale.ROOT))||secret.length()>4096||secret.chars().anyMatch(c->c<32||c>126))throw new IllegalArgumentException("诊断认证头无效");
        byte[] payload=preview(archive).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);if(payload.length>1048576)throw new IllegalArgumentException("诊断导出超过1MiB");control.check();
        OkHttpClient client=new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).callTimeout(15,TimeUnit.SECONDS).build();Request.Builder builder=new Request.Builder().url(uri.toASCIIString()).header("Accept","application/json").post(RequestBody.create(payload,MediaType.get("application/json")));if(!secret.isEmpty())builder.header(header,secret);
        var call=client.newCall(builder.build());Runnable stop=call::cancel;control.onCancel(stop);
        try(var response=call.execute()){
            control.check();if(response.code()!=200)return new JSONObject().put("status","failed").put("httpStatus",response.code()).put("automaticRetry",false);
            if(response.body()==null)throw new IllegalStateException("诊断接收端没有回执");var contentType=response.body().contentType();if(contentType==null||!contentType.type().equals("application")||!contentType.subtype().equals("json"))throw new IllegalStateException("诊断回执格式不是OTLP JSON");
            var source=response.body().source();if(source.request(65537))throw new IllegalStateException("诊断接收回执超限");JSONObject ack=new JSONObject(source.readUtf8());
            if(ack.has("partialSuccess")&&!ack.isNull("partialSuccess")&&ack.optJSONObject("partialSuccess")==null)throw new IllegalStateException("诊断部分接收回执无效");
            JSONObject partial=ack.optJSONObject("partialSuccess");long rejected=0;
            if(partial!=null&&partial.has("rejectedSpans")){Object value=partial.get("rejectedSpans");if(!(value instanceof Number||value instanceof String)||!value.toString().matches("0|[1-9][0-9]{0,18}"))throw new IllegalStateException("诊断拒收计数无效");rejected=Long.parseLong(value.toString());}
            if(rejected>archive.getJSONArray("spans").length())throw new IllegalStateException("诊断部分接收回执无效");
            return new JSONObject().put("status",rejected>0?"partially_rejected":partial!=null&&!partial.optString("errorMessage").isEmpty()?"accepted_with_warning":"accepted").put("rejectedSpans",rejected).put("automaticRetry",false).put("bodyIncluded",false);
        }finally{control.removeCancelAction(stop);}
    }
}
