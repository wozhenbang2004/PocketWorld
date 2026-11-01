package dev.pocketengine.features.local.preview;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Validates a literal artifact against the exact completed build receipt, never a caller-supplied path. */
public final class ProjectPreviewArtifact {
    public static final int MAX_BYTES=262144;
    private ProjectPreviewArtifact(){}
    public static final class Verified {
        public final String checkoutId,sourceDigest,jobId,receiptIdentity,path,sha256,content,mimeType,identity,artifactOrigin,collectionJobId,collectionReceiptIdentity,buildOutputAttribution;
        public final boolean html;
        public final boolean buildAssociated;
        public final boolean generatedArtifact;
        private Verified(JSONObject artifact,boolean buildAssociated)throws Exception {
            this.buildAssociated=buildAssociated;
            artifactOrigin=artifact.getString("artifactOrigin");generatedArtifact=artifact.getBoolean("generatedArtifact");
            buildOutputAttribution=artifact.optString("buildOutputAttribution");
            checkoutId=artifact.getString("checkoutId");sourceDigest=artifact.getString("sourceDigest");jobId=artifact.getString("jobId");
            receiptIdentity=artifact.getString("buildReceiptIdentity");path=artifact.getString("path");sha256=artifact.getString("sha256");
            collectionJobId=artifact.optString("collectionJobId");collectionReceiptIdentity=artifact.optString("collectionReceiptIdentity");
            content=artifact.getString("content");mimeType=artifact.getString("mimeType");html=mimeType.equals("text/html");
            identity=hash((checkoutId+"\n"+sourceDigest+"\n"+receiptIdentity+"\n"+jobId+"\n"+path+"\n"+sha256+(generatedArtifact?"\n"+collectionJobId+"\n"+collectionReceiptIdentity:"")).getBytes(StandardCharsets.UTF_8));
        }
    }
    public static Verified verify(JSONObject artifact,JSONObject build,String checkoutId,String sourceDigest)throws Exception {
        buildAssociation(artifact,build,checkoutId,sourceDigest);
        if(!"checkout_source".equals(artifact.optString("artifactOrigin"))||!Boolean.FALSE.equals(artifact.opt("generatedArtifact")))throw new SecurityException("此入口只接受构建所用源码，生成产物需核对独立回收回执");
        content(artifact,MAX_BYTES);
        return new Verified(artifact,true);
    }
    private static void buildAssociation(JSONObject artifact,JSONObject build,String checkoutId,String sourceDigest)throws Exception {
        if(artifact==null||build==null)throw new IllegalArgumentException("请先取得当前构建回执和完整产物");
        uuid(checkoutId);digest(sourceDigest);
        for(JSONObject value:new JSONObject[]{artifact,build}) {
            if(!checkoutId.equals(value.optString("checkoutId"))||!sourceDigest.equals(value.optString("sourceDigest")))throw new SecurityException("预览不属于当前工作副本和源码版本");
        }
        if(!successfulBuild(build))throw new SecurityException("只能关联已完成且退出码为0的构建回执");
        String job=build.getString("jobId"),receipt=build.getString("receiptIdentity");
        if(!job.matches("[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException("执行回执身份无效");
        if(receipt.trim().isEmpty()||receipt.length()>200||receipt.matches("(?s).*[\\p{Cntrl}].*"))throw new SecurityException("构建回执身份无效");
        if(!job.equals(artifact.optString("jobId"))||!receipt.equals(artifact.optString("buildReceiptIdentity")))throw new SecurityException("产物与构建回执身份不一致");
    }
    private static byte[] content(JSONObject artifact,int limit)throws Exception {
        if(!Boolean.FALSE.equals(artifact.opt("truncated")))throw new SecurityException("截断或未确认完整的产物不能预览");
        path(artifact.getString("path"));String expected=artifact.getString("sha256");digest(expected);
        String content=artifact.getString("content");ByteBuffer encoded=StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(content));
        byte[] bytes=new byte[encoded.remaining()];encoded.get(bytes);
        if(bytes.length>limit||content.indexOf('\0')>=0)throw new IllegalArgumentException("文本产物超过上限或不是完整UTF-8文本");
        if(!expected.equals(hash(bytes)))throw new SecurityException("产物字节摘要已变化，请重新读取");
        String mime=artifact.getString("mimeType");
        if(!mime.equals("text/html")&&!mime.startsWith("text/")&&!mime.equals("application/json"))throw new IllegalArgumentException("此产物类型尚不支持手机预览");
        return bytes;
    }
    /** Exact selected bytes from a separate native collection job; the UI never treats this as a build job. */
    public static Verified verifyCollected(JSONObject artifact,JSONObject build,JSONObject collectionPlan,String collectionJobId)throws Exception {
        if(collectionPlan==null)throw new SecurityException("缺少主人已核对的回收选择");
        String checkout=collectionPlan.getString("checkoutId"),source=collectionPlan.getString("sourceDigest");
        buildAssociation(artifact,build,checkout,source);
        if(!"termux_selected_output".equals(artifact.optString("artifactOrigin"))||!Boolean.TRUE.equals(artifact.opt("generatedArtifact")))throw new SecurityException("不是独立回收的生成文本产物");
        if(!Boolean.FALSE.equals(artifact.opt("atomicArtifactFence"))||!"selected_output_observed_after_build".equals(artifact.optString("buildOutputAttribution")))throw new SecurityException("回收来源范围不明，不能声称构建输出归属");
        id(collectionJobId);String collectionIdentity=artifact.getString("collectionReceiptIdentity");digest(collectionIdentity);
        if(collectionJobId.equals(build.getString("jobId"))||!collectionJobId.equals(artifact.optString("collectionJobId")))throw new SecurityException("回收操作与原构建身份不一致");
        if(!collectionPlan.getString("buildJobId").equals(build.getString("jobId"))||!collectionPlan.getString("buildReceiptIdentity").equals(build.getString("receiptIdentity"))
                ||!artifact.optString("buildJobId").equals(build.getString("jobId"))||!collectionPlan.getString("path").equals(artifact.getString("path")))throw new SecurityException("回收选择与构建或文件路径不一致");
        String planDigest=collectionPlan.getString("artifactPlanDigest");digest(planDigest);
        if(!planDigest.equals(collectionPlan.getString("artifactPlanId")))throw new SecurityException("回收方案身份不一致");
        JSONObject collected=artifact.getJSONObject("collectionReceipt"),nativeBuild=artifact.getJSONObject("buildReceipt");
        buildAssociation(artifact,nativeBuild,checkout,source);
        if(!build.getString("receiptIdentity").equals(nativeBuild.getString("receiptIdentity")))throw new SecurityException("原构建回执发生变化");
        if(!"artifact_collect".equals(collected.optString("intent"))||!receivedZero(collected)||!Boolean.FALSE.equals(collected.opt("outputTruncated")))throw new SecurityException("回收尚未确认完整成功，不能预览或自动重试");
        if(!collectionJobId.equals(collected.optString("jobId"))||!collectionIdentity.equals(collected.optString("receiptIdentity"))
                ||!planDigest.equals(collected.optString("collectionPlanDigest"))||!checkout.equals(collected.optString("checkoutId"))||!source.equals(collected.optString("sourceDigest"))
                ||!build.getString("jobId").equals(collected.optString("buildJobId"))||!build.getString("receiptIdentity").equals(collected.optString("buildReceiptIdentity"))
                ||!artifact.getString("path").equals(collected.optString("path"))||!artifact.getString("sha256").equals(collected.optString("sha256")))throw new SecurityException("生成文件与原生回收回执不一致");
        JSONObject binding=collected.getJSONObject("binding");
        if(!"owner_tracked_job".equals(binding.optString("authority"))||!collectionJobId.equals(binding.optString("runId"))||!collectionJobId.equals(binding.optString("operationId")))throw new SecurityException("回收缺少原生主人操作关联");
        id(binding.getString("taskId"));
        if(exactInteger(collectionPlan.opt("maxBytes"))!=32000)throw new SecurityException("回收大小范围与已审阅方案不一致");
        byte[] bytes=content(artifact,32000);
        if(exactInteger(artifact.opt("bytes"))!=bytes.length||exactInteger(collected.opt("bytes"))!=bytes.length)throw new SecurityException("回收字节数不一致");
        return new Verified(artifact,true);
    }
    /** Source-only HTML needs no fabricated build. Its actual bytes must match the inspected manifest. */
    public static Verified verifySource(JSONObject actual,JSONObject inspection,String checkoutId)throws Exception {
        if(actual==null||inspection==null||actual.has("truncated")&&!Boolean.FALSE.equals(actual.opt("truncated")))throw new SecurityException("需要完整源文件与当前核对清单");
        uuid(checkoutId);String source=inspection.getString("sourceDigest");digest(source);
        if(!checkoutId.equals(inspection.optString("checkoutId"))||!checkoutId.equals(actual.optString("checkoutId")))throw new SecurityException("源文件不属于当前副本");
        String path=actual.getString("path");path(path);String expected="";
        org.json.JSONArray files=inspection.getJSONObject("source").getJSONArray("files");
        for(int i=0;i<files.length();i++)if(path.equals(files.getJSONObject(i).getString("path"))){if(!expected.isEmpty())throw new SecurityException("源文件清单重复");expected=files.getJSONObject(i).getString("sha256");}
        String text=actual.getString("content");ByteBuffer encoded=StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(text));byte[] bytes=new byte[encoded.remaining()];encoded.get(bytes);
        if(bytes.length>MAX_BYTES||text.indexOf('\0')>=0||expected.isEmpty()||!expected.equals(actual.optString("sha256"))||!expected.equals(hash(bytes)))throw new SecurityException("源文件字节与核对清单不符，请重新读取");
        String mime=path.toLowerCase(java.util.Locale.ROOT).matches(".*\\.html?")?"text/html":"text/plain";
        JSONObject projected=new JSONObject(actual.toString()).put("sourceDigest",source).put("jobId","").put("buildReceiptIdentity","").put("mimeType",mime).put("artifactOrigin","checkout_source").put("generatedArtifact",false);
        return new Verified(projected,false);
    }
    public static boolean successfulBuild(JSONObject build) {
        return build!=null&&"build".equals(build.optString("intent"))&&"completed".equals(build.optString("status"))
            &&receivedZero(build)&&Boolean.TRUE.equals(build.opt("sourceVerifiedBeforeExec"));
    }
    private static boolean receivedZero(JSONObject receipt){return receipt!=null&&"completed".equals(receipt.optString("status"))&&exactInteger(receipt.opt("exitCode"))==0&&Boolean.TRUE.equals(receipt.opt("receiptReceived"))&&Boolean.FALSE.equals(receipt.opt("cancelRequested"));}
    static long exactInteger(Object value){return value instanceof Integer||value instanceof Long?((Number)value).longValue():Long.MIN_VALUE;}
    private static void id(String value){if(value==null||!value.matches("[A-Za-z0-9_-]{1,80}"))throw new SecurityException("原生操作身份无效");}
    public static boolean current(JSONObject result,JSONObject input,String requestId,String checkoutId,String sourceDigest) {
        if(result==null||input==null||requestId==null||requestId.trim().isEmpty()||!requestId.equals(input.optString("_requestId")))return false;
        if(result.has("requestId")&&!requestId.equals(result.optString("requestId"))||result.has("_requestId")&&!requestId.equals(result.optString("_requestId")))return false;
        JSONObject scope=result.has("checkoutId")?result:result.optJSONObject("receipt");
        return scope!=null&&checkoutId.equals(input.optString("checkoutId"))&&checkoutId.equals(scope.optString("checkoutId"))
            &&(sourceDigest==null||sourceDigest.equals(scope.optString("sourceDigest")));
    }
    public static String hash(byte[] bytes)throws Exception {
        StringBuilder result=new StringBuilder();for(byte value:MessageDigest.getInstance("SHA-256").digest(bytes))result.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return result.toString();
    }
    private static void uuid(String value){if(value==null||!value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new IllegalArgumentException("工作副本或执行身份无效");}
    private static void digest(String value){if(value==null||!value.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("需要真实SHA-256摘要");}
    static void path(String value){
        if(value.trim().isEmpty()||value.length()>240||value.startsWith("/")||value.indexOf('\\')>=0||value.indexOf(':')>=0||value.matches("(?s).*[\\p{Cntrl}].*"))throw new IllegalArgumentException("产物需要工作副本内的相对路径");
        for(String segment:value.split("/",-1))if(segment.isEmpty()||segment.equals(".")||segment.equals(".."))throw new IllegalArgumentException("产物路径不能逃逸工作副本");
    }
}
