package dev.pocketengine.features.automation;

import dev.pocketengine.browser.BrowserTaskSpace;
import dev.pocketengine.browser.PublicHttpReader;
import dev.pocketengine.core.RunControl;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Native anonymous-reader return. No constructor or JSON parser can manufacture this witness. */
public final class PublicConditionObservation {
    private final String frozen;
    private PublicConditionObservation(JSONObject actual)throws Exception{frozen=actual.toString();}
    public static PublicConditionObservation read(JSONObject fixedArguments,RunControl original,BrowserTaskSpace.Gate gate)throws Exception{
        PublicHttpReader.validate(fixedArguments);original.check();gate.check();
        PublicHttpReader.Result actual=new PublicHttpReader().read(new JSONObject(fixedArguments.toString()),new PublicHttpReader.Budget(original),gate,original);
        return fromNativeRead(actual,original,gate);
    }
    /** Native typed reader result only. Its original budget/control cannot be replaced by JSON. */
    public static PublicConditionObservation fromNativeRead(PublicHttpReader.Result actual,RunControl original,BrowserTaskSpace.Gate gate)throws Exception{
        if(actual==null||actual.budget==null||!actual.budget.boundTo(original))throw new SecurityException("条件来源不是这个原控制器的实际native读取");actual.budget.check();original.check();gate.check();return new PublicConditionObservation(actual.value);
    }
    public JSONObject receipt()throws Exception{return new JSONObject(frozen);}
    public boolean readable()throws Exception{JSONObject value=receipt();return value.optBoolean("ok")&&"read".equals(value.optString("status"))&&!value.optString("text").isBlank()&&value.optBoolean("responseBodyComplete")&&!value.optBoolean("projectionTruncated")&&value.optJSONObject("http")!=null&&value.getJSONObject("http").optBoolean("bodyComplete")&&value.getJSONObject("http").optString("bodySha256").matches("[a-f0-9]{64}");}
    public JSONObject witness(String task,String run,int epoch)throws Exception{
        JSONObject value=receipt();if(!readable())throw new SecurityException("公共来源没有真实可读正文，不能生成条件见证");
        return new JSONObject().put("taskId",task).put("runId",run).put("epoch",epoch).put("observationId",value.getString("observationId")).put("sha256",sha256(value.getString("text")));
    }
    public static String sha256(String actual)throws Exception{byte[] digest=MessageDigest.getInstance("SHA-256").digest(actual.getBytes(StandardCharsets.UTF_8));StringBuilder hex=new StringBuilder();for(byte value:digest)hex.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return hex.toString();}
}
