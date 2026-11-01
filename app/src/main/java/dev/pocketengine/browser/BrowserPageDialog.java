package dev.pocketengine.browser;

import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.json.JSONObject;

/** One native WebChromeClient dialog. Page text and prompt replies never enter its task receipt. */
public final class BrowserPageDialog {
    public static final long OWNER_LIMIT_MS=120_000;
    public enum Kind { ALERT, CONFIRM, PROMPT, BEFORE_UNLOAD }
    public enum Reason { OWNER_CONFIRMED, OWNER_CANCELLED, TIMED_OUT, CONTEXT_CHANGED, PAGE_CLOSED,
        PROFILE_CHANGED, TASK_CANCELLED, PAUSE_CHANGED, OWNER_LEFT, REPLACED, CALLBACK_FAILED }
    public interface Current {void check()throws Exception;}
    public interface Reply {void confirm(String text)throws Exception;void cancel()throws Exception;}
    private final String id=UUID.randomUUID().toString();
    private final Kind kind;
    private final JSONObject identity;
    private final String operationId;
    private final LongSupplier uptime;
    private final Current current;
    private final Reply reply;
    private final long openedAt,ownerLimitMs;
    private long resolvedAt=-1;
    private Reason reason;
    private boolean decisionDelivered;

    /** All arguments and the reply handle come from the actual native callback, never a model tool. */
    public BrowserPageDialog(Kind kind,JSONObject nativeIdentity,String nativeOperationId,
            LongSupplier uptime,Current current,Reply reply)throws Exception {this(kind,nativeIdentity,nativeOperationId,uptime,current,reply,OWNER_LIMIT_MS);}
    public BrowserPageDialog(Kind kind,JSONObject nativeIdentity,String nativeOperationId,
            LongSupplier uptime,Current current,Reply reply,long ownerLimitMs)throws Exception {
        if(ownerLimitMs<1||ownerLimitMs>OWNER_LIMIT_MS)throw new SecurityException("原操作等待主人处理的总时限已到");this.ownerLimitMs=ownerLimitMs;
        this.kind=Objects.requireNonNull(kind);this.uptime=Objects.requireNonNull(uptime);
        this.current=Objects.requireNonNull(current);this.reply=Objects.requireNonNull(reply);
        identity=new JSONObject();
        for(String key:new String[]{"profileId","profileRevision","browserSessionId","profileIsolation","tabId","navigation","documentId"})identity.put(key,nativeIdentity.get(key));
        BrowserTaskSpace.uuid(identity.getString("tabId"));BrowserTaskSpace.uuid(identity.getString("documentId"));
        BrowserTaskSpace.uuid(identity.getString("browserSessionId"));
        if(identity.getLong("navigation")<0||identity.getLong("profileRevision")<1)throw new SecurityException("网页提示的原文档或资料版本无效");
        if(nativeIdentity.has("taskId"))for(String key:new String[]{"taskId","runId","epoch","browserSpaceId","tabGeneration"})identity.put(key,nativeIdentity.get(key));
        if(identity.has("taskId")){BrowserTaskSpace.uuid(identity.getString("taskId"));BrowserTaskSpace.uuid(identity.getString("runId"));BrowserTaskSpace.uuid(identity.getString("browserSpaceId"));if(identity.getInt("epoch")<1||identity.getLong("tabGeneration")<1)throw new SecurityException("网页提示不属于原执行");}
        operationId=nativeOperationId==null?"":nativeOperationId;if(!operationId.isEmpty()){BrowserTaskSpace.uuid(operationId);if(!identity.has("taskId"))throw new SecurityException("原网页操作没有原任务绑定");}
        openedAt=uptime.getAsLong();if(openedAt<0)throw new IllegalArgumentException("网页提示时钟无效");current.check();
    }
    public String id(){return id;}
    public String operationId(){return operationId;}
    public synchronized boolean pending(){return resolvedAt<0;}
    public synchronized long remainingMillis(){return Math.max(0,ownerLimitMs-Math.max(0,uptime.getAsLong()-openedAt));}
    /** Only this actual modal interval can extend its original native call's wait; never its run/lease. */
    public synchronized long waitExtensionMillis(){long end=resolvedAt<0?uptime.getAsLong():resolvedAt;return Math.min(ownerLimitMs,Math.max(0,end-openedAt));}
    public synchronized JSONObject snapshot()throws Exception {
        JSONObject out=new JSONObject(identity.toString()).put("dialogId",id).put("dialogKind",kind.name().toLowerCase(java.util.Locale.ROOT))
                .put("status",resolvedAt<0?"pending":"resolved").put("ownerDecisionOnly",true).put("promptValueIncluded",false)
                .put("messageIncluded",false).put("documentScope","hosting_top_level_document").put("initiatingFrameIdentityAvailable",false).put("verifiedBusinessSuccess",false).put("openedAtUptimeMs",openedAt).put("ownerLimitMs",ownerLimitMs)
                .put("decisionDelivered",decisionDelivered).put("resolutionMeaning","dialog_callback_only_not_page_or_server_effect");
        if(!operationId.isEmpty())out.put("operationId",operationId);
        if(reason!=null)out.put("reason",reason.name().toLowerCase(java.util.Locale.ROOT));return out;
    }
    /** Explicit native-owner action. A second click or old callback cannot answer a replacement dialog. */
    public synchronized boolean decide(String expectedId,boolean confirm,String promptText)throws Exception {
        if(!id.equals(expectedId)||resolvedAt>=0)return false;
        if(remainingMillis()==0){cancel(Reason.TIMED_OUT);return false;}
        try{current.check();}catch(Exception changed){cancel(Reason.CONTEXT_CHANGED);return false;}
        if(confirm&&kind==Kind.PROMPT&&(promptText==null||promptText.length()>2048))throw new IllegalArgumentException("请填写不超过2048字符的网页输入，或取消");
        resolvedAt=uptime.getAsLong();reason=confirm?Reason.OWNER_CONFIRMED:Reason.OWNER_CANCELLED;
        try{if(confirm)reply.confirm(kind==Kind.PROMPT?promptText:null);else reply.cancel();decisionDelivered=true;}
        catch(Exception failure){reason=Reason.CALLBACK_FAILED;throw failure;}
        return true;
    }
    /** Best effort targets this old callback handle only. A failed cleanup is not a retry or a confirmation. */
    public synchronized boolean cancel(Reason why)throws Exception {
        Objects.requireNonNull(why);if(why==Reason.OWNER_CONFIRMED)throw new IllegalArgumentException("清理不能确认网页请求");
        if(resolvedAt>=0)return false;resolvedAt=uptime.getAsLong();reason=why;
        try{reply.cancel();decisionDelivered=true;}catch(Exception failure){reason=Reason.CALLBACK_FAILED;throw failure;}return true;
    }
    /** A single original native call may naturally show several dialogs; their total owner wait stays capped. */
    public static final class Call {
        public static final int MAX_DIALOGS=8;
        private final String operationId;
        private final java.util.List<BrowserPageDialog> dialogs=new java.util.ArrayList<>();
        public Call(String operationId){BrowserTaskSpace.uuid(operationId);this.operationId=operationId;}
        public synchronized void add(BrowserPageDialog dialog){
            if(!operationId.equals(dialog.operationId())||dialogs.size()>=MAX_DIALOGS||!dialogs.isEmpty()&&dialogs.get(dialogs.size()-1).pending()||remainingMillis()==0)throw new SecurityException("此原操作的网页提示已达限额或仍有未处理提示");dialogs.add(dialog);
        }
        private synchronized java.util.List<BrowserPageDialog> copy(){return new java.util.ArrayList<>(dialogs);}
        public long waitExtensionMillis(){long total=0;for(BrowserPageDialog dialog:copy())total=Math.min(OWNER_LIMIT_MS,total+dialog.waitExtensionMillis());return total;}
        public long remainingMillis(){return Math.max(0,OWNER_LIMIT_MS-waitExtensionMillis());}
        public synchronized BrowserPageDialog latest(){return dialogs.isEmpty()?null:dialogs.get(dialogs.size()-1);}
    }

}
