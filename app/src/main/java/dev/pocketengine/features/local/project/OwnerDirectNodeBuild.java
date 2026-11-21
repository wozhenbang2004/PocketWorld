package dev.pocketengine.features.local.project;

import dev.pocketengine.features.local.LocalModule;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** One fixed compiler or Vite plan reviewed by its owner. No shell, install, or model. */
public final class OwnerDirectNodeBuild {
    public static final String FIELD="ownerDirectNodeBuild", FORMAT="pocketworld.owner-direct-node-build.v1";
    public static final String COMMAND="project.nodeBuild.owner", MODE="OWNER_DIRECT_NODE_BUILD";
    public static final String VITE_FIELD="ownerDirectViteBuild", VITE_FORMAT="pocketworld.owner-direct-vite-build.v1";
    public static final String VITE_COMMAND="project.viteBuild.owner", VITE_MODE="OWNER_DIRECT_VITE_BUILD";
    private static final long REVIEW_MILLIS=120_000L;
    private OwnerDirectNodeBuild(){}

    public static boolean claims(JSONObject task){return task!=null&&(task.has(FIELD)||task.has(VITE_FIELD));}
    public static String workId(String project,String checkout,String sourceDigest){return workId(project,checkout,sourceDigest,false);}
    public static String workId(String project,String checkout,String sourceDigest,boolean vite){
        if(!digest(sourceDigest))throw new SecurityException("没有原项目的准确源码版本");
        return UUID.nameUUIDFromBytes(((vite?VITE_FORMAT:FORMAT)+"\0"+ProjectBuildPlan.identity(project)+"\0"+
                ProjectBuildPlan.identity(checkout)+"\0"+sourceDigest).getBytes(StandardCharsets.UTF_8)).toString();
    }
    public static boolean digest(Object value){return value instanceof String s&&s.matches("[a-f0-9]{64}");}
    public static boolean ordinaryCheckout(JSONObject metadata){return OwnerDraftFirstBuild.ordinaryCheckout(metadata);}
    public static boolean path(Object value){return value instanceof String s&&s.matches("dist/[A-Za-z0-9][A-Za-z0-9_-]{0,63}\\.js");}

    /** Source-only. Pick exactly the profile evidenced by this frozen ordinary checkout. */
    public static String selectedOutput(JSONObject exact,JSONObject snapshot,boolean vite)throws Exception{
        if(vite)return OriginalNodeWebOutput.directViteSource(exact,snapshot)==null?null:OriginalNodeWebOutput.ENTRY;
        return OriginalNodeCompilerOutput.selectedPathForOwnerReview(exact,snapshot);
    }
    static Review offer(LocalModule local,JSONObject plan,JSONObject pin,JSONObject snapshot,boolean vite)throws Exception{
        JSONObject lock=OwnerDraftFirstBuild.dependencies(pin);
        if(!OwnerDraftFirstBuild.existingCheckout(pin)||lock==null||!"npm".equals(lock.opt("manager")))return null;
        JSONObject checked=ProjectBuildPlan.verified(plan),step=step(checked),exact=ProjectDevelopment.exact(checked,step);
        String output=selectedOutput(exact,snapshot,vite);
        if(output==null)return null;
        String source=ProjectSources.describe(snapshot).getString("sourceDigest");
        if(!source.equals(checked.opt("sourceDigest"))||!source.equals(pin.opt("sourceDigest"))||
                !checked.getString("planDigest").equals(pin.opt("planDigest"))||!digest(lock.opt("lockSha256")))
            throw new SecurityException("锁、构建方案与所选源码版本不同");
        String project=checked.getString("projectId"),checkout=checked.getString("checkoutId"),id=workId(project,checkout,source,vite);
        JSONObject scope=new JSONObject().put("format",vite?VITE_FORMAT:FORMAT).put("requestId",id).put("runId",id)
                .put("epoch",1).put("buildOperationId",id).put("projectId",project).put("checkoutId",checkout)
                .put("sourceDigest",source).put("planDigest",checked.getString("planDigest"))
                .put("runtimeId",checked.getString("runtimeId")).put("runtimeIdentity",checked.getString("runtimeIdentity"))
                .put("lockSha256",lock.getString("lockSha256")).put("outputPath",output)
                .put("modelDispatchAllowed",false).put("replayAllowed",false);
        JSONObject capability=local.projectCapabilityForHost(exact).put("nativeProjectPlan",new JSONObject()
                .put("fullPlanStoredInOriginalTaskStore",true).put("planDigest",scope.getString("planDigest"))
                .put("step",ProjectPlanRecords.reviewStep(step)).put("executionAuthorized",false)).put(vite?VITE_FIELD:FIELD,ProjectSources.copy(scope));
        return new Review(local,scope,checked,lock,capability,vite);
    }

    /** Not the generic command, package-preparation or interactive-terminal planner. */
    public static JSONObject step(JSONObject plan)throws Exception{
        JSONObject verified=ProjectBuildPlan.verified(plan);
        if(!"build".equals(verified.opt("intent"))||!"node".equals(verified.opt("ecosystem"))||
                !"managed_process_v1".equals(verified.opt("runtimeId"))||!Boolean.FALSE.equals(verified.opt("automaticChaining"))||
                !Boolean.TRUE.equals(verified.opt("available"))||verified.getJSONArray("steps").length()!=1)
            throw new SecurityException("仅支持这一份本机 Node 构建方案");
        JSONObject step=ProjectBuildPlan.step(verified,"build"),payload=step.getJSONObject("payload");
        if(!"project.runtime.run".equals(step.opt("localCommand"))||!Boolean.TRUE.equals(step.opt("approvalRequired"))||
                !"npm".equals(payload.opt("executable"))||!"[\"run\",\"build\"]".equals(ProjectSources.canonical(payload.opt("argv")))||
                !".".equals(payload.optString("workingDirectory","."))||!"foreground".equals(payload.optString("runMode","foreground"))||
                payload.has("terminalMode")||payload.has("environment")||payload.has("devWatch"))
            throw new SecurityException("本机编译只接受已核对的固定构建命令");
        return step;
    }

    public static final class Review implements AutoCloseable {
        private final LocalModule issuer;
        private final String scope,plan,lock,capability;
        private final boolean vite;
        private final long until=System.nanoTime()+REVIEW_MILLIS*1_000_000L, expiresAt=System.currentTimeMillis()+REVIEW_MILLIS;
        private boolean closed, confirmed;
        private Review(LocalModule issuer,JSONObject scope,JSONObject plan,JSONObject lock,JSONObject capability,boolean vite){
            this.issuer=issuer;this.vite=vite;this.scope=scope.toString();this.plan=plan.toString();this.lock=lock.toString();this.capability=capability.toString();
        }
        public synchronized JSONObject view()throws Exception{
            if(closed||confirmed||System.nanoTime()-until>=0)throw new SecurityException("本机构建审阅已过期");
            return new JSONObject(scope).put("expiresAt",expiresAt).put("executionAuthorized",false);
        }
        public synchronized Confirmed confirm(BooleanSupplier sameOwnerWindow)throws Exception{
            if(closed||confirmed||System.nanoTime()-until>=0||!sameOwnerWindow.getAsBoolean())throw new SecurityException("请在同一项目重新核对构建");
            confirmed=true;return new Confirmed(this,sameOwnerWindow);
        }
        @Override public synchronized void close(){closed=true;}
    }

    public static final class Confirmed {
        private final Review review;
        private final BooleanSupplier sameOwnerWindow;
        private boolean consumed;
        private Confirmed(Review review,BooleanSupplier sameOwnerWindow){this.review=review;this.sameOwnerWindow=sameOwnerWindow;}
        public boolean vite(){return review.vite;}
        public JSONObject scope()throws Exception{return new JSONObject(review.scope);}
        public JSONObject plan()throws Exception{return new JSONObject(review.plan);}
        public JSONObject lock()throws Exception{return new JSONObject(review.lock);}
        public JSONObject capability()throws Exception{return new JSONObject(review.capability);}
        public void requireUnenteredWindow(){synchronized(review){
            if(review.closed||System.nanoTime()-review.until>=0||!sameOwnerWindow.getAsBoolean())
                throw new SecurityException("原构建审阅窗口已变化，没有启动");
        }}
        /** Consumed only at the native SQL admission, after all read-only preconditions. */
        public synchronized void enter(LocalModule expected){
            if(review.issuer!=expected||consumed)throw new SecurityException("这次确认已经使用或不属于原本机项目宿主");
            requireUnenteredWindow();consumed=true;
        }
        public void requireHost(LocalModule expected){if(review.issuer!=expected)throw new SecurityException("构建审阅不属于这个本机项目宿主");}
    }
}
