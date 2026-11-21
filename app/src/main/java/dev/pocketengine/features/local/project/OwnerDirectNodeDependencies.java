package dev.pocketengine.features.local.project;

import dev.pocketengine.features.local.LocalModule;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** One explicit native npm lock preparation. Never a compiler, general command, or model permit. */
public final class OwnerDirectNodeDependencies {
    public static final String FIELD="ownerDirectNodeDependencies",FORMAT="pocketworld.owner-direct-node-dependencies.v1";
    public static final String COMMAND="project.nodeDependencies.owner",MODE="OWNER_DIRECT_NODE_DEPENDENCIES";
    private static final long REVIEW_MILLIS=120_000L;
    private static final String[] SOURCE={"projectId","checkoutId","sourceDigest","planDigest","runtimeId","runtimeIdentity"};
    private static final String[] LOCK={"manager","managerVersion","registry","lockPath","lockSha256","packages"};
    private OwnerDirectNodeDependencies(){}

    public static String workId(String project,String checkout,String source){
        if(!OwnerDirectNodeBuild.digest(source))throw new SecurityException("没有原项目的准确源码版本");
        return UUID.nameUUIDFromBytes((FORMAT+"\0"+ProjectBuildPlan.identity(project)+"\0"+
                ProjectBuildPlan.identity(checkout)+"\0"+source).getBytes(StandardCharsets.UTF_8)).toString();
    }
    public static JSONObject step(JSONObject plan)throws Exception{
        JSONObject checked=ProjectBuildPlan.verified(plan);
        if(!"dependencies".equals(checked.opt("intent"))||!"locked_node_registry".equals(checked.opt("ecosystem"))||
                !"managed_process_v1".equals(checked.opt("runtimeId"))||!Boolean.TRUE.equals(checked.opt("available"))||
                !Boolean.FALSE.equals(checked.opt("automaticChaining"))||checked.getJSONArray("steps").length()!=1)
            throw new SecurityException("仅支持这一次原生 npm 锁定依赖准备");
        JSONObject step=ProjectBuildPlan.step(checked,OwnerDraftNodeDependencies.STEP);
        JSONObject lock=step.getJSONObject("payload").getJSONObject(OwnerDraftNodeDependencies.FIELD);
        if(!"npm".equals(lock.opt("manager"))||!"package-lock.json".equals(lock.opt("lockPath"))||
                !OwnerDirectNodeBuild.digest(lock.opt("lockSha256"))||!OwnerDirectNodeBuild.digest(lock.opt("manifestSha256"))||
                !lock.optString("managerVersion").matches("11\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?")||
                lock.optInt("packages")<1||lock.optInt("packages")>8192)
            throw new SecurityException("没有这份副本的固定 npm 锁和实际工具版本");
        return step;
    }
    public static JSONObject lock(JSONObject plan)throws Exception{
        return ProjectSources.copy(step(plan).getJSONObject("payload").getJSONObject(OwnerDraftNodeDependencies.FIELD));
    }
    public static void requireScope(JSONObject scope,JSONObject plan)throws Exception{
        JSONObject lock=lock(plan);
        for(String key:SOURCE)if(!scope.get(key).equals(plan.opt(key)))throw new SecurityException("准备方案不是原项目和锁");
        for(String key:LOCK)if(!scope.get(key).equals(lock.opt(key)))throw new SecurityException("准备方案不是原锁的准确版本");
    }
    /** Full outer SQL route after the one native approval; no compiler/website witness marker. */
    public static boolean matchesRoute(JSONObject exact,JSONObject route)throws Exception{
        if(!"native_host_operation".equals(route.opt("authority"))||!route.getString("operationId").equals(route.opt("jobId"))||
                route.has(OriginalNodeCompilerOutput.DIRECT_ROUTE)||route.has(OriginalNodeWebOutput.EXISTING_ROUTE)||route.has(OriginalNodeWebOutput.EDITED_ROUTE))return false;
        for(String key:SOURCE)if(!exact.get(key).equals(route.opt(key)))return false;
        for(String key:new String[]{"command","stepId","intent","requestDigest"})if(!exact.get(key).equals(route.opt(key)))return false;
        return true;
    }

    /** Only prepared from an ordinary, still-selected compiler source with positively absent node_modules. */
    static Review offer(LocalModule local,JSONObject plan)throws Exception{
        JSONObject checked=ProjectBuildPlan.verified(plan),step=step(checked),lock=lock(checked);
        String id=workId(checked.getString("projectId"),checked.getString("checkoutId"),checked.getString("sourceDigest"));
        JSONObject scope=new JSONObject().put("format",FORMAT).put("requestId",id).put("runId",id).put("epoch",1)
                .put("dependencyOperationId",id).put("modelDispatchAllowed",false).put("replayAllowed",false);
        for(String key:SOURCE)scope.put(key,checked.get(key));
        for(String key:LOCK)scope.put(key,lock.get(key));
        JSONObject capability=local.projectCapabilityForHost(ProjectDevelopment.exact(checked,step)).put("nativeProjectPlan",new JSONObject()
                .put("fullPlanStoredInOriginalTaskStore",true).put("planDigest",scope.getString("planDigest"))
                .put("step",ProjectPlanRecords.reviewStep(step)).put("executionAuthorized",false)).put(FIELD,ProjectSources.copy(scope));
        return new Review(local,scope,checked,capability);
    }

    public static final class Review implements AutoCloseable {
        private final LocalModule issuer;
        private final String scope,plan,capability;
        private final long until=System.nanoTime()+REVIEW_MILLIS*1_000_000L,expiresAt=System.currentTimeMillis()+REVIEW_MILLIS;
        private boolean closed,confirmed;
        private Review(LocalModule issuer,JSONObject scope,JSONObject plan,JSONObject capability){
            this.issuer=issuer;this.scope=scope.toString();this.plan=plan.toString();this.capability=capability.toString();
        }
        public synchronized JSONObject view()throws Exception{
            if(closed||confirmed||System.nanoTime()-until>=0)throw new SecurityException("原依赖审阅已过期");
            return new JSONObject(scope).put("argv",step(new JSONObject(plan)).getJSONObject("payload").getJSONArray("argv"))
                    .put("expiresAt",expiresAt).put("executionAuthorized",false);
        }
        public synchronized Confirmed confirm(BooleanSupplier sameOwnerWindow){
            if(closed||confirmed||System.nanoTime()-until>=0||!sameOwnerWindow.getAsBoolean())throw new SecurityException("请在同一项目重新核对锁定依赖");
            confirmed=true;return new Confirmed(this,sameOwnerWindow);
        }
        @Override public synchronized void close(){closed=true;}
    }

    public static final class Confirmed {
        private final Review review;
        private final BooleanSupplier sameOwnerWindow;
        private boolean consumed;
        private Confirmed(Review review,BooleanSupplier sameOwnerWindow){this.review=review;this.sameOwnerWindow=sameOwnerWindow;}
        public JSONObject scope()throws Exception{return new JSONObject(review.scope);}
        public JSONObject plan()throws Exception{return new JSONObject(review.plan);}
        public JSONObject capability()throws Exception{return new JSONObject(review.capability);}
        public void requireUnenteredWindow(){synchronized(review){
            if(review.closed||System.nanoTime()-review.until>=0||!sameOwnerWindow.getAsBoolean())
                throw new SecurityException("原依赖审阅窗口已变化，没有准备");
        }}
        public synchronized void enter(LocalModule expected){
            requireHost(expected);
            if(consumed)throw new SecurityException("这次原依赖确认已经使用");
            requireUnenteredWindow();consumed=true;
        }
        public void requireHost(LocalModule expected){if(review.issuer!=expected)throw new SecurityException("依赖审阅不属于这个本机项目宿主");}
    }
}
