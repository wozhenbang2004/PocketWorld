package dev.pocketengine.features.automation;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Body-free identity rules for a projection of an original, settled completion.
 * This is not a delivery queue, an audience grant, or proof that a model used evidence. */
public final class CompletionFacts {
    private CompletionFacts(){}
    public static final String FORMAT="pocketworld.original-completion-fact.v1";
    public static String id(String task,String run,int epoch,String stateHash){
        if(!uuid(task)||!uuid(run)||epoch<1||!hash(stateHash))throw new IllegalArgumentException("完成事实缺少原任务身份");
        return UUID.nameUUIDFromBytes((FORMAT+"\n"+task+"\n"+run+"\n"+epoch+"\n"+stateHash).getBytes(StandardCharsets.UTF_8)).toString();
    }
    public static String summary(String original){
        if(original==null)throw new IllegalArgumentException("完成摘要缺失");
        if(original.length()<=3000)return original;
        int end=Character.isHighSurrogate(original.charAt(2999))?2999:3000;
        return original.substring(0,end);
    }
    public static JSONArray artifactRefs(JSONObject verified)throws Exception{
        JSONArray result=new JSONArray();if(verified==null)return result;
        if(!"workspace_artifact".equals(verified.optString("kind"))||!uuid(verified.optString("artifactId"))
                ||!uuid(verified.optString("operationId"))||!uuid(verified.optString("projectId"))
                ||!hash(verified.optString("sha256"))||verified.optInt("sourceVersion")<1
                ||!uuid(verified.optString("sourceRevisionId")))throw new SecurityException("完成文件缺少原生精确版本");
        String path=verified.getString("path");
        if(path.isEmpty()||path.length()>240||path.startsWith("/")||path.contains("\\")
                ||path.matches(".*[\\p{Cntrl}].*")||path.matches("(^|.*/)\\.\\.?(/.*|$)"))throw new SecurityException("完成文件路径无效");
        long bytes=integer(verified,"sizeBytes",0);if(bytes>1_000_000)throw new SecurityException("完成文件超出原核验范围");
        return result.put(new JSONObject().put("kind","workspace_artifact").put("artifactId",verified.getString("artifactId"))
                .put("operationId",verified.getString("operationId")).put("projectId",verified.getString("projectId"))
                .put("path",path).put("version",verified.getInt("sourceVersion"))
                .put("revisionId",verified.getString("sourceRevisionId")).put("sha256",verified.getString("sha256"))
                .put("sizeBytes",bytes).put("immutableSnapshot",true));
    }
    public static boolean uuid(String id){return id!=null&&id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");}
    public static boolean hash(String hash){return hash!=null&&hash.matches("[a-f0-9]{64}");}
    public static long integer(JSONObject value,String key,long minimum){
        Object n=value.opt(key);if(!(n instanceof Integer||n instanceof Long)||((Number)n).longValue()<minimum)throw new SecurityException("完成事实数值身份无效");
        return ((Number)n).longValue();
    }
}
