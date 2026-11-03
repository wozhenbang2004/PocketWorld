package dev.pocketengine.features.local.project;

import dev.pocketengine.features.local.LocalModule;
import java.util.UUID;
import org.json.JSONObject;

/** An opaque native review for the original managed command. It owns no runtime or journal. */
public final class OwnerLocalTerminal {
    public static final String COMMAND="project.terminal.owner";
    private OwnerLocalTerminal(){}
    public static Review review(LocalModule local,JSONObject request)throws Exception {
        JSONObject input=OwnerTerminalRequest.command(request),plan=ProjectBuildPlan.create(local,input);
        if(!input.getString("projectId").equals(plan.getString("projectId"))||!plan.getBoolean("available"))throw new SecurityException("原本机工具、PTY或工作副本尚未可用，请先核对环境");
        JSONObject exact=ProjectDevelopment.exact(plan,step(plan)),capability=local.projectCapabilityForHost(exact);
        capability.put("nativeProjectPlan",new JSONObject().put("fullPlanStoredInOriginalTaskStore",true).put("planDigest",plan.getString("planDigest")).put("step",ProjectPlanRecords.reviewStep(step(plan))).put("executionAuthorized",false));
        return new Review(local,plan,capability);
    }
    public static JSONObject step(JSONObject plan)throws Exception {
        JSONObject checked=ProjectBuildPlan.verified(plan);JSONObject step=checked.getJSONArray("steps").getJSONObject(0),payload=step.getJSONObject("payload");
        if(!"command".equals(checked.getString("intent"))||!"managed_process_v1".equals(checked.getString("runtimeId"))||!"owned_service".equals(payload.getString("runMode"))||!"pty".equals(payload.getString("terminalMode"))||!"sh".equals(payload.getString("executable"))||payload.getJSONArray("argv").length()!=1||!"-i".equals(payload.getJSONArray("argv").getString(0)))throw new SecurityException("不是原生手动Shell方案");
        return step;
    }
    public static final class Review implements AutoCloseable {
        private final LocalModule issuer;private final String id=UUID.randomUUID().toString(),plan,capability;private final long expiresAt=System.currentTimeMillis()+120000;private boolean closed;
        private Review(LocalModule issuer,JSONObject plan,JSONObject capability){this.issuer=issuer;this.plan=plan.toString();this.capability=capability.toString();}
        public synchronized JSONObject view()throws Exception {JSONObject original=new JSONObject(plan),payload=step(original).getJSONObject("payload");return new JSONObject().put("requestId",id).put("planDigest",original.getString("planDigest")).put("projectId",original.getString("projectId")).put("checkoutId",original.getString("checkoutId")).put("sourceDigest",original.getString("sourceDigest")).put("runtimeId",original.getString("runtimeId")).put("runtimeIdentity",original.getString("runtimeIdentity")).put("command",ProjectCommandSpec.fromPayload(payload)).put("warnings",original.getJSONArray("warnings")).put("inputRequestLimit",128).put("expiresAt",expiresAt).put("modelDispatchAllowed",false).put("executionAuthorized",false).put("closeStopsProcess",false);}
        private synchronized void current()throws Exception {
            if(closed||System.currentTimeMillis()>=expiresAt)throw new SecurityException("手动终端审阅已关闭或过期，请重新核对");JSONObject original=new JSONObject(plan),exact=ProjectDevelopment.exact(original,step(original));
            if(!issuer.projectCapabilityStillCurrent(exact,new JSONObject(capability))||!original.getString("sourceDigest").equals(ProjectSources.describe(issuer.handle("export",new JSONObject().put("checkoutId",original.getString("checkoutId")))).getString("sourceDigest")))throw new SecurityException("原环境或工作副本已变化，没有启动终端");
        }
        public synchronized Confirmed confirm()throws Exception{current();return new Confirmed(this);}
        @Override public synchronized void close(){closed=true;}
    }
    public static final class Confirmed {
        private final Review reviewed;private Confirmed(Review reviewed){this.reviewed=reviewed;}
        public String requestId(){return reviewed.id;}
        public JSONObject plan()throws Exception{return new JSONObject(reviewed.plan);}
        public JSONObject capability()throws Exception{return new JSONObject(reviewed.capability);}
        public void requireCurrent(LocalModule local)throws Exception{if(local!=reviewed.issuer)throw new SecurityException("不是原本机终端宿主的审阅");reviewed.current();}
        public void requireUnexpired()throws Exception{synchronized(reviewed){if(reviewed.closed||System.currentTimeMillis()>=reviewed.expiresAt)throw new SecurityException("原手动终端确认已过期");}}
    }
}
