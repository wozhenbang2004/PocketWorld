package dev.pocketengine.features.remote;

import android.os.SystemClock;
import java.util.LinkedHashMap;
import org.json.JSONObject;
import dev.pocketengine.store.TaskStore;

/** Single-use local navigation provenance for a specifically confirmed native first submit. Not a grant or wire field. */
public final class PrivateHostGoalLink {
    public static final String LOCAL_ONLY="_privateOriginalHostReceipt";
    public record Origin(String conversation,dev.pocketengine.features.assistant.AssistantModule.SavedFirstHostSource source,String workspaceSha) {}
    private static final class Pin {
        final Origin origin;final String digest,receipt;final long ends;
        Pin(Origin origin,String d){this.origin=origin;digest=d;receipt=java.util.UUID.randomUUID().toString();ends=SystemClock.elapsedRealtime()+120_000;}
    }
    private final LinkedHashMap<String,Pin> pending=new LinkedHashMap<>();
    public String bind(String conversation,JSONObject reviewed)throws Exception {
        return bind(conversation,null,null,reviewed);
    }
    public synchronized String bind(String conversation,dev.pocketengine.features.assistant.AssistantModule.SavedFirstHostSource source,
                                    JSONObject location,JSONObject reviewed)throws Exception {
        String op=reviewed.getString("operationId");
        if(source!=null&&(!source.conversation().equals(conversation)||location==null))
            throw new SecurityException("本机原话和所选主机工作区未核对");
        if(conversation==null||conversation.isBlank()||conversation.length()>160||conversation.indexOf(0)>=0||!op.matches("[a-f0-9-]{36}")
                ||!reviewed.optString("_profileBinding").matches("[a-f0-9]{64}")||!reviewed.optString("environmentBinding").matches("[a-f0-9]{64}")
                ||reviewed.optString("prompt").isBlank()||"command".equals(reviewed.opt("worker")))throw new SecurityException("原私人对话或本次主机审阅身份不完整");
        pending.entrySet().removeIf(e->e.getValue().ends<=SystemClock.elapsedRealtime());
        if(pending.size()>=8||pending.containsKey(op))throw new SecurityException("此主机交办来源已使用或待处理，请重新审阅");
        Pin p=new Pin(new Origin(conversation,source,source==null?null:TaskStore.capabilityFingerprint(location)),TaskStore.capabilityFingerprint(reviewed));
        pending.put(op,p);return p.receipt;
    }
    public synchronized Origin take(JSONObject input)throws Exception {
        // Remove the private seal before hashing, registration and every transport branch.
        Object receipt=input.remove(LOCAL_ONLY);String op=input.optString("operationId");Pin v=pending.get(op);
        if(v==null&&receipt==null)return null;
        if(v==null||!v.receipt.equals(receipt))throw new SecurityException("原对话的本机交办回执已失效，未改作独立委托");
        pending.remove(op);
        if(v.ends<=SystemClock.elapsedRealtime()||!v.digest.equals(TaskStore.capabilityFingerprint(input)))throw new SecurityException("原主机审阅已过期或内容变化，未提交或关联到其他对话");
        return v.origin;
    }
}
