write(JAVA+'phone/observation/PhoneObservationRecovery.java', '''package dev.pocketengine.phone.observation;
import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
/** Same-port retries of read-only transient failures. No effect dispatch or display fallback. */
public final class PhoneObservationRecovery {
    public static final int MAX_ATTEMPTS=3;
    public static final long RETRY_START_WINDOW_MS=2600;
    private static final Set<String> TRANSIENT=Set.of("screen_changed","frame_pending","screenshot_throttled","screenshot_busy");
    @FunctionalInterface public interface Check { void check() throws Exception; }
    @FunctionalInterface public interface RetryNotice { void retry(String code,int attempt,long delayMs) throws Exception; }
    public interface Timing {long now();void pause(long millis)throws Exception;}
    private static final Timing REAL=new Timing(){public long now(){return System.nanoTime()/1_000_000L;}public void pause(long millis)throws Exception{Thread.sleep(millis);}};
    private PhoneObservationRecovery(){}
    public static JSONObject read(PhonePort port,boolean image,RunControl control,Check check,boolean retriesAllowed,RetryNotice notice)throws Exception {
        return read(port,image,control,check,retriesAllowed,notice,REAL);
    }
    public static JSONObject read(PhonePort port,boolean image,RunControl control,Check check,boolean retriesAllowed,RetryNotice notice,Timing timing)throws Exception {
        java.util.Objects.requireNonNull(port);java.util.Objects.requireNonNull(control);
        long revision=control.pauseRevision(),started=timing.now(),waited=0;JSONArray attempts=new JSONArray();
        for(int attempt=1;;attempt++){
            control.check();check.check();
            if(control.isPaused()||control.pauseRevision()!=revision)return changed(port,attempts,waited);
            JSONObject result=port.observe(image,control);
            control.check();check.check();
            if(control.isPaused()||control.pauseRevision()!=revision)return changed(port,attempts,waited);
            if(result==null)result=new JSONObject().put("ok",false).put("error","empty_observation").put("accepted",JSONObject.NULL).put("message","手机没有返回可核对的观察结果。");
            String code=result.optString("error",result.optString("code"));
            attempts.put(new JSONObject().put("attempt",attempt).put("code",result.optBoolean("ok")?"ok":code));
            boolean transientRead=retryable(result);long elapsed=timing.now()-started;
            boolean exhausted=attempt>=MAX_ATTEMPTS||elapsed<0||elapsed>=RETRY_START_WINDOW_MS;
            if(!retriesAllowed||!transientRead||exhausted)return annotate(result,attempts,waited,retriesAllowed&&transientRead&&exhausted);
            long delay=Math.min("screenshot_throttled".equals(code)?1000:160L*attempt,RETRY_START_WINDOW_MS-elapsed);
            port.clearReferences();notice.retry(code,attempt,delay);long until=timing.now()+delay;
            while(timing.now()<until){
                control.check();check.check();
                if(control.isPaused()||control.pauseRevision()!=revision)return changed(port,attempts,waited);
                long part=Math.max(1,Math.min(50,until-timing.now()));timing.pause(part);waited+=part;
            }
            control.check();check.check();
            if(control.isPaused()||control.pauseRevision()!=revision)return changed(port,attempts,waited);
            long afterWait=timing.now()-started;
            if(afterWait<0||afterWait>=RETRY_START_WINDOW_MS)return annotate(result,attempts,waited,true);
        }
    }
    public static boolean retryable(JSONObject result){
        if(result==null||result.optBoolean("ok")||result.optBoolean("requiresPause")||result.optBoolean("requiresUser")
                ||result.optBoolean("requiresOwnerReturn")||result.optBoolean("requiresReconciliation")||result.optBoolean("reconciliationRequired")
                ||result.has("accepted")&&result.isNull("accepted")||"reconcile".equals(result.optString("nextObservationAction")))return false;
        return TRANSIENT.contains(result.optString("error",result.optString("code")));
    }
    private static JSONObject changed(PhonePort port,JSONArray attempts,long waited)throws Exception {
        port.clearReferences();return annotate(ObservationReport.annotateFailure(new JSONObject().put("ok",false).put("accepted",false).put("verified",false)
                .put("error","paused_or_changed").put("message","任务已暂停或执行状态变化，旧观察未继续。")),attempts,waited,false);
    }
    private static JSONObject annotate(JSONObject result,JSONArray attempts,long waited,boolean exhausted)throws Exception {
        if(!result.optBoolean("ok"))ObservationReport.annotateFailure(result);
        return result.put("readOnlyRetries",Math.max(0,attempts.length()-1)).put("observationRecovery",new JSONObject()
                .put("attempts",attempts).put("waitedMs",waited).put("retryStartWindowMs",RETRY_START_WINDOW_MS)
                .put("retryBudgetExhausted",exhausted).put("samePort",true).put("actionReplay",false).put("displayFallback",false));
    }
}
''')
p=ROOT/(JAVA+'runtime/AgentRunner.java');s=p.read_text()
a=s.index('            for(int attempt=0;attempt<2;attempt++){',s.index('    /** Automatic observations debit'))
b=s.index('        }catch(Exception failure){',a)
s=s[:a]+'''            control.checkpoint();requirePlanningCurrent(planning,expected);
            JSONObject observation=dev.pocketengine.phone.observation.PhoneObservationRecovery.read(
                    phone,config.sendScreenshots,control,()->requirePlanningCurrent(planning,expected),read==null,
                    (code,attempt,delay)->RuntimeDiagnostics.event("phone_observation","retrying","READ_ONLY_"+code.toUpperCase(java.util.Locale.ROOT),control,null));
            control.check();requirePlanningCurrent(planning,expected);
            boolean ok=observation.optBoolean("ok",false);
            emit(taskId,"observation",ok?"completed":"failed",ok?"已读取当前页面。":observation.optString("message","观察未成功。"),"phone_observe",observation,config,sink);
            if(ownRead){
                String operation=read.operationId;ExecutionPlanning.ReadOutcome outcome=planningReadOutcome(observation);
                completePlanningRead(planning,read,observation,config);read=null;
                if(outcome==ExecutionPlanning.ReadOutcome.UNCONFIRMED)throw new PlanningReadIncomplete(operation,new IllegalStateException("Original automatic observation is unconfirmed"));
            }
            return observation;
'''+s[b:];p.write_text(s)
replace(JAVA+'core/PhonePort.java','    boolean isConnected();','''    boolean isConnected();
    /** Negative catalog narrowing only, never an execution grant. */
    default boolean supportsTool(String toolName) { return true; }''')
replace(JAVA+'phone/access/GuardedPhonePort.java','    @Override public boolean isConnected(){return policy.current().available()&&inner.isConnected();}','''    @Override public boolean isConnected(){return policy.current().available()&&inner.isConnected();}
    @Override public boolean supportsTool(String tool){return inner.supportsTool(tool);}''')
replace(JAVA+'phone/observation/AdaptivePhonePort.java','    public PhonePort physicalPort(){','''    @Override public boolean supportsTool(String tool){return source.supportsTool(tool);}
    public PhonePort physicalPort(){''')
replace(JAVA+'phone/display/BackgroundPhonePort.java','    @Override public synchronized JSONObject observe(boolean includeImage,RunControl control)throws Exception {','''    @Override public boolean supportsTool(String tool) { return !"phone_home".equals(tool); }
    @Override public synchronized JSONObject observe(boolean includeImage,RunControl control)throws Exception {''')
replace(JAVA+'runtime/AgentRunner.java','                tools=intent.filter(tools,this::intentEffecting);','''                if(phone!=null)tools=phoneTools(tools,phone);
                tools=intent.filter(tools,this::intentEffecting);''')
replace(JAVA+'runtime/AgentRunner.java','    public static JSONArray tools() throws Exception {','''    static JSONArray phoneTools(JSONArray tools,PhonePort port)throws Exception {
        JSONArray narrowed=new JSONArray();
        for(int i=0;i<tools.length();i++){
            JSONObject tool=tools.getJSONObject(i);String name=tool.getJSONObject("function").getString("name");
            if(!name.startsWith("phone_")||port.supportsTool(name))narrowed.put(tool);
        }
        return narrowed;
    }
    public static JSONArray tools() throws Exception {''')
replace(JAVA+'runtime/policy/OwnerIntent.java','        if(text.isEmpty())return Kind.UNSPECIFIED;',r'''        if(text.isEmpty())return Kind.UNSPECIFIED;
        if(text.matches("^(你好|您好|嗨|哈喽|在吗|早上好|下午好|晚上好|谢谢|hi|hello|hey|thanks|thank you)[!！?？。.,，\\s]*$"))return Kind.DISCUSS;''')
replace(JAVA+'phone/observation/ObservationSafety.java','case "screenshots_disabled":','case "screenshots_disabled": case "local_capture_disabled":')
p=ROOT/'app/src/main/kotlin/dev/pocketengine/ui/PocketWorldController.kt';s=p.read_text()
a=s.index('    internal fun startDraftPhone(');b=s.index('    internal fun queryConversationPhoneStart(',a);part=s[a:b]
old='result.exceptionOrNull()?.let { state = state.copy(error = message(it)) }'
assert part.count(old)==2
part=part.replace(old,'if (ownerCurrent()) result.exceptionOrNull()?.let { state = state.copy(error = message(it)) }')
p.write_text(s[:a]+part+s[b:])
