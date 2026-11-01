package dev.pocketengine.browser;

import java.util.UUID;
import java.util.function.LongSupplier;
import org.json.JSONObject;

/** Ephemeral executor fencing only. TaskStore remains the persistent task authority. */
public final class BrowserLease {
    public static final String PROFILE="owner-default";
    private final LongSupplier clock;
    private String taskId,runId,leaseId;
    private String profileId=PROFILE,browserSessionId="legacy-single-view",profileIsolation="default-webview-only";
    private long profileRevision=1;
    private long epoch,expiresAt;
    private java.util.function.Consumer<JSONObject> expirationListener;
    public BrowserLease(){this(System::currentTimeMillis);}
    BrowserLease(LongSupplier clock){this.clock=clock;}
    public synchronized void setExpirationListener(java.util.function.Consumer<JSONObject> listener){expirationListener=listener;}
    public synchronized JSONObject acquire(JSONObject args)throws Exception{
        if(!"owner".equals(args.optString("principalId"))||!"private".equals(args.optString("spaceId")))throw new SecurityException("内置浏览器当前仅允许主人私人空间");
        if(profileId==null)throw new IllegalStateException("请主人先选择可用的浏览器资料");
        if(profileIsolation.equals("androidx-multi-profile")&&(!args.has("profileId")||!args.has("profileRevision")||!args.has("browserSessionId")))throw new SecurityException("托管资料执行需要原生宿主绑定资料和会话版本");
        if(!profileId.equals(args.optString("profileId",profileId)))throw new IllegalArgumentException("任务不能切换浏览器资料，请主人从原生设置选择");
        requireProfile(args);
        String task=identifier(args,"taskId"),run=identifier(args,"runId");
        expire();
        if(leaseId!=null){if(task.equals(taskId)&&run.equals(runId))return snapshot();throw new IllegalStateException("浏览器正被另一执行占用，请先停止或接管");}
        taskId=task;runId=run;leaseId=UUID.randomUUID().toString();epoch++;expiresAt=clock.getAsLong()+600_000L;return snapshot();
    }
    public synchronized long require(JSONObject args)throws Exception{
        expire();
        if(leaseId==null||!leaseId.equals(args.optString("leaseId"))||!taskId.equals(args.optString("taskId"))||!runId.equals(args.optString("runId")))throw new SecurityException("浏览器执行租约已失效，请从原任务重新取得控制");
        requireProfile(args);
        return epoch;
    }
    public synchronized boolean current(long expected){expire();return leaseId!=null&&epoch==expected;}
    public synchronized JSONObject release(JSONObject args,String reason)throws Exception{require(args);JSONObject previous=snapshot();invalidate();return previous.put("status",reason).put("active",false);}
    public synchronized void invalidate(){taskId=null;runId=null;leaseId=null;expiresAt=0;epoch++;}
    public synchronized void bindProfile(BrowserProfileRegistry.Session session){
        invalidate();profileId=session==null?null:session.profileId;profileRevision=session==null?0:session.profileRevision;
        browserSessionId=session==null?"":session.sessionId;profileIsolation=session==null?"unavailable":session.isolation;
    }
    public synchronized JSONObject snapshot(){
        expire();return state();
    }
    private JSONObject state(){JSONObject result=new JSONObject();try{result.put("active",leaseId!=null).put("epoch",epoch).put("profileId",profileId==null?"":profileId).put("profileRevision",profileRevision).put("browserSessionId",browserSessionId).put("profileIsolation",profileIsolation).put("sharedWithSystemChrome",false);if(leaseId!=null)result.put("taskId",taskId).put("runId",runId).put("leaseId",leaseId).put("expiresAt",expiresAt).put("status","leased");}catch(Exception ignored){}return result;}
    private void requireProfile(JSONObject args){
        if(args.has("profileId")&&!java.util.Objects.equals(profileId,args.optString("profileId"))||args.has("profileRevision")&&profileRevision!=args.optLong("profileRevision",-1)||args.has("browserSessionId")&&!browserSessionId.equals(args.optString("browserSessionId")))throw new SecurityException("浏览器资料或会话版本已变化，请重新取得原任务授权");
    }
    private void expire(){if(leaseId!=null&&clock.getAsLong()>=expiresAt){JSONObject previous=state();invalidate();if(expirationListener!=null)try{expirationListener.accept(previous.put("active",false).put("status","expired").put("message","浏览器执行租约已到期，请从原任务继续"));}catch(Exception ignored){}}}
    private static String identifier(JSONObject args,String key){String value=args.optString(key);if(value.isEmpty()||value.length()>100||!value.matches("[A-Za-z0-9._:-]+"))throw new IllegalArgumentException("缺少有效的"+key);return value;}
}
