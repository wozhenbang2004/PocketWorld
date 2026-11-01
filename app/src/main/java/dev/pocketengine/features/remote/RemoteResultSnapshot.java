package dev.pocketengine.features.remote;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.json.JSONObject;

/** Bounded private result projection. A driver diff, current file bytes and a frozen artifact are distinct. */
public final class RemoteResultSnapshot {
    private RemoteResultSnapshot() { }
    public static JSONObject job(JSONObject result,JSONObject source)throws Exception {
        for(String key:new String[]{"profileId","serviceBase","operationId"}){
            String expected=source.optString(key.equals("operationId")?"jobOperationId":key);
            if(!expected.equals(result.optString(key)))throw new SecurityException("成果回执未绑定原电脑操作");
        }
        if(!source.optString("jobId").isEmpty()&&!source.getString("jobId").equals(result.optString("id")))throw new SecurityException("成果回执属于另一job");
        JSONObject view=new JSONObject().put("operationId",result.getString("operationId")).put("jobId",result.optString("id")).put("capturedAt",System.currentTimeMillis()).put("kind","driver_report").put("immutableArtifact",false);
        for(String key:new String[]{"profileId","serviceBase","environmentId","worker","threadId","sessionId","turnId","status","exitCode","outputTruncated","diffTruncated","model","reportedModel","promptResultIdentity"})if(result.has(key))view.put(key,result.get(key));
        JSONObject progress=RemoteInteractions.progress(result);if(progress!=null)view.put("progress",progress);
        for(String key:new String[]{"output","diff"})if(result.opt(key) instanceof String){String value=result.getString(key);int end=Math.min(65536,value.length());if(end<value.length()&&end>0&&Character.isHighSurrogate(value.charAt(end-1)))end--;view.put(key,value.substring(0,end)).put(key+"ViewTruncated",end<value.length());}
        return view;
    }
    public static JSONObject artifact(JSONObject bytes,JSONObject source,String expectedPath)throws Exception {
        if(!expectedPath.equals(bytes.optString("path")))throw new SecurityException("文件回执路径与本次原请求不符");
        long declared=bytes.getLong("bytes");if(declared<0||declared>262144||!(bytes.opt("base64") instanceof String)||bytes.getString("base64").length()>349528)throw new SecurityException("成果文件超过读取上限");
        byte[] raw=Base64.getDecoder().decode(bytes.getString("base64"));String sha=hex(MessageDigest.getInstance("SHA-256").digest(raw));
        if(raw.length!=declared||!sha.equals(bytes.optString("sha256")))throw new SecurityException("文件实际字节与远端大小/哈希不一致");
        JSONObject view=new JSONObject().put("path",expectedPath).put("bytes",raw.length).put("sha256",sha).put("capturedAt",System.currentTimeMillis()).put("kind","current_workspace_bytes").put("immutableArtifact",false);
        for(String key:new String[]{"profileId","serviceBase","environmentId","jobId","jobOperationId","threadId","sessionId","turnId"})if(source.has(key))view.put(key,source.get(key));
        try {String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();if(text.indexOf('\0')>=0)throw new IllegalArgumentException("binary");int end=Math.min(text.length(),65536);if(end<text.length()&&end>0&&Character.isHighSurrogate(text.charAt(end-1)))end--;view.put("text",text.substring(0,end)).put("viewTruncated",end<text.length()).put("preview","utf8_text");}
        catch(Exception binary){view.put("preview","binary_not_previewable");}
        return view;
    }
    private static String hex(byte[] bytes){StringBuilder text=new StringBuilder();for(byte b:bytes)text.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return text.toString();}
}
