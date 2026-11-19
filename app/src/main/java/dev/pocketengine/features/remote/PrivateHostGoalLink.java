package dev.pocketengine.features.remote;

import android.os.SystemClock;
import java.util.LinkedHashMap;
import org.json.JSONObject;
import dev.pocketengine.store.TaskStore;

/** Single-use local navigation provenance for a specifically confirmed native first submit. Not a grant or wire field. */
public final class PrivateHostGoalLink {
    public static final String LOCAL_ONLY="_privateOriginalHostReceipt";
    private static final class Pin {
        final String conversation,digest,receipt;final long ends;
        Pin(String c,String d){conversation=c;digest=d;receipt=java.util.UUID.randomUUID().toString();ends=SystemClock.elapsedRealtime()+120_000;}
    }
    private final LinkedHashMap<String,Pin> pending=new LinkedHashMap<>();
    public synchronized String bind(String conversation,JSONObject reviewed)throws Exception {
        String op=reviewed.getString("operationId");
        if(conversation==null||conversation.isBlank()||conversation.length()>160||conversation.indexOf(0)>=0||!op.matches("[a-f0-9-]{36}")
                ||!reviewed.optString("_profileBinding").matches("[a-f0-9]{64}")||!reviewed.optString("environmentBinding").matches("[a-f0-9]{64}")
                ||reviewed.optString("prompt").isBlank()||"command".equals(reviewed.opt("worker")))throw new SecurityException("原私人对话或本次主机审阅身份不完整");
        pending.entrySet().removeIf(e->e.getValue().ends<=SystemClock.elapsedRealtime());
        if(pending.size()>=8||pending.containsKey(op))throw new SecurityException("此主机交办来源已使用或待处理，请重新审阅");
        Pin p=new Pin(conversation,TaskStore.capabilityFingerprint(reviewed));pending.put(op,p);return p.receipt;
    }
    public synchronized String take(JSONObject input)throws Exception {
        // Remove the private seal before hashing, registration and every transport branch.
        Object receipt=input.remove(LOCAL_ONLY);String op=input.optString("operationId");Pin v=pending.get(op);
        if(v==null&&receipt==null)return null;
        if(v==null||!v.receipt.equals(receipt))throw new SecurityException("原对话的本机交办回执已失效，未改作独立委托");
        pending.remove(op);
        if(v.ends<=SystemClock.elapsedRealtime()||!v.digest.equals(TaskStore.capabilityFingerprint(input)))throw new SecurityException("原主机审阅已过期或内容变化，未提交或关联到其他对话");
        return v.conversation;
    }
}
