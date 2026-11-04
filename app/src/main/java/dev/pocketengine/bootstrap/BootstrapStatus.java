package dev.pocketengine.bootstrap;

import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Process-local state for the sole Application initializer. It holds no task or execution authority. */
public final class BootstrapStatus {
    private long revision=1,generation;
    private volatile boolean ready;
    private String state="initializing",stage="configured",recovery="not_started",version="unknown";
    private final int schema;
    private Integer observed;
    private String code="initializing",message="正在准备本机存储与原任务恢复",next="请等待当前初始化完成";
    private boolean retryAvailable,inFlight;
    private String lastRetry="";
    private final Map<String,String> optional=new LinkedHashMap<>();
    public BootstrapStatus(int requiredSchema){if(requiredSchema<1)throw new IllegalArgumentException("schema");schema=requiredSchema;}
    public boolean ready(){return ready;}
    public synchronized long generation(){return generation;}
    public synchronized long beginInitial(){if(generation!=0||inFlight)throw new IllegalStateException("初始化已开始");return begin();}
    private long begin(){ready=false;inFlight=true;generation++;revision++;state="initializing";stage="configured";recovery="not_started";observed=null;optional.clear();retryAvailable=false;code="initializing";message="正在准备本机存储与原任务恢复";next="请等待当前初始化完成";return generation;}
    /** A duplicated request only observes its original generation; it never starts another initializer. */
    public synchronized long retry(long expected,String requestId){
        request(requestId);if(requestId.equals(lastRetry))return 0;
        if(expected!=generation||inFlight||!retryAvailable||ready)throw new SecurityException("启动状态已变化，请重新读取");
        lastRetry=requestId;return begin();
    }
    public synchronized void installed(long original,String installedVersion){if(current(original)){version=installedVersion;revision++;}}
    public synchronized void stage(long original,String value,Integer observedSchema){
        if(!current(original))return;
        if(!java.util.Set.of("configured","data_opened","migrated","features_ready").contains(value))throw new IllegalArgumentException("stage");
        stage=value;if(observedSchema!=null)observed=observedSchema;revision++;
    }
    public synchronized void recovering(long original){if(current(original)){recovery="running";revision++;}}
    public synchronized void optional(long original,String capability,String why){if(current(original)){optional.put(capability,why);revision++;}}
    public synchronized boolean complete(long original){if(!current(original)||!"running".equals(recovery)||!"features_ready".equals(stage))return false;recovery="completed";state="ready";code="ready";message="原任务恢复与数据库读取已完成";next="可进入工作区";inFlight=false;retryAvailable=false;revision++;ready=true;return true;}
    public synchronized void failed(long original,String reason,String explanation,String action,boolean retry){
        if(!current(original))return;ready=false;inFlight=false;state="failed";if("running".equals(recovery))recovery="failed";code=reason;message=explanation;next=action;retryAvailable=retry;revision++;
    }
    public synchronized void maintenance(String reason,String explanation,String action,boolean retry){ready=false;inFlight=false;state="maintenance";code=reason;message=explanation;next=action;retryAvailable=retry;revision++;}
    public synchronized boolean current(long original){return inFlight&&generation==original;}
    public synchronized JSONObject snapshot()throws Exception{
        JSONArray unavailable=new JSONArray();for(var item:optional.entrySet())unavailable.put(new JSONObject().put("capability",item.getKey()).put("reason",item.getValue()));
        return new JSONObject().put("surface","bootstrap").put("revision",revision).put("generation",generation).put("state",state).put("stage",stage).put("recovery",recovery)
            .put("reason",new JSONObject().put("code",code).put("message",message).put("nextStep",next)).put("installedVersion",version).put("requiredSchema",schema)
            .put("observedSchema",observed==null?JSONObject.NULL:observed).put("retryAvailable",retryAvailable).put("optionalUnavailable",unavailable).put("maintenance",JSONObject.NULL);
    }
    public static void request(String request){if(request==null||!request.matches("[A-Za-z0-9_-]{8,120}"))throw new IllegalArgumentException("需要本次原生请求编号");}
}
