package dev.pocketengine.runtime;

import dev.pocketengine.core.ScopedToolCatalog;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded non-progress state in the original runs.checkpoint, never a retry ledger or a grant.
 * Only an entirely, definitely failed native tool round advances the repeated-pattern window. */
public final class RepeatToolNotice {
    static final int THRESHOLD=3,MAX_REFERENCE_BYTES=1400,MAX_STATE_BYTES=16*1024;
    private static final int WINDOW=12,RECENT=24;
    private static final String FORMAT="pocketworld.progress-guard.v1";
    private long revision;
    private String ownerBoundary="";
    private int consecutiveAllFailed;
    private boolean notified,pending,stopped;
    private JSONArray cycleWindow=new JSONArray(),recentFingerprints=new JSONArray();
    private JSONObject round;

    public RepeatToolNotice() { }

    /** Missing means a pre-guard checkpoint. Malformed persisted state must never silently reset. */
    public void restore(JSONObject saved)throws Exception {
        if(saved==null)return;
        validate(saved);
        revision=saved.getLong("progressGuardRevision");ownerBoundary=saved.getString("ownerBoundary");
        consecutiveAllFailed=saved.getInt("consecutiveAllFailed");notified=saved.getBoolean("notified");
        pending=saved.getBoolean("noticePending");stopped=saved.getBoolean("stopped");
        cycleWindow=new JSONArray(saved.getJSONArray("cycleWindow").toString());
        recentFingerprints=new JSONArray(saved.getJSONArray("recentFingerprints").toString());
        round=saved.isNull("pendingRound")?null:new JSONObject(saved.getJSONObject("pendingRound").toString());
        // A crash after the last real receipt but before the ordinary round boundary retains it.
        // A partially known round is not relabelled all-failed and is never replayed.
        if(round!=null)finishRound();
    }

    /** Native admitted owner rows only. Ordinary observations, pause and new budgets do not reset. */
    void ownerBoundary(JSONArray history)throws Exception {
        String current="";
        for(int i=history.length()-1;i>=0;i--){JSONObject row=history.getJSONObject(i);
            if("user".equals(row.optString("role"))&&(!row.optString("ownerMessageId").isEmpty()||Boolean.TRUE.equals(row.opt("ownerRecallFeedback")))){
                current=ScopedToolCatalog.fingerprint(new JSONObject().put("ownerMessageId",row.optString("ownerMessageId"))
                        .put("ownerRecallFeedback",row.optBoolean("ownerRecallFeedback")).put("content",row.opt("content")));break;
            }
        }
        if(!current.isEmpty()&&!current.equals(ownerBoundary)){clearChain();round=null;recentFingerprints=new JSONArray();ownerBoundary=current;changed();}
    }

    void beginRound(int calls)throws Exception {
        if(calls<1||calls>8||stopped)throw new IllegalStateException("progress_guard_round_not_admitted");
        if(round!=null)finishRound();
        round=new JSONObject().put("expected",calls).put("processed",0).put("allFailed",true).put("failures",new JSONArray());changed();
    }

    /** nativeNotDispatched is supplied at the actual validation/beforeTool boundary, never from JSON.
     * Post-dispatch side-effect receipts cannot assert their way into a definitely-failed round. */
    void afterTool(String tool,Object arguments,JSONObject receipt,boolean nativeReadOnly,boolean nativeNotDispatched)throws Exception {
        recordTool(tool,arguments,receipt,nativeReadOnly,nativeNotDispatched);
        if(round!=null&&round.getInt("processed")==round.getInt("expected"))finishRound();
    }

    private void recordTool(String tool,Object arguments,JSONObject receipt,boolean nativeReadOnly,boolean nativeNotDispatched)throws Exception {
        if(round==null||round.getInt("processed")>=round.getInt("expected"))throw new IllegalStateException("progress_guard_round_missing");
        round.put("processed",round.getInt("processed")+1);changed();
        String code=failureCode(receipt),status=receipt.optString("status").toLowerCase(Locale.ROOT);
        if(code.contains("unknown")||code.contains("timeout")||code.contains("running")
                ||Set.of("unknown","running","pending","queued").contains(status)||receipt.optBoolean("requiresReconciliation")||receipt.optBoolean("requiresPause")
                ||"reconcile".equals(receipt.optString("nextObservationAction"))||receipt.has("accepted")&&receipt.isNull("accepted")){
            round.put("allFailed",false);return;
        }
        String call=callFingerprint(tool,arguments),source=sourceVersion(receipt);
        if(Boolean.TRUE.equals(receipt.opt("ok"))){
            round.put("allFailed",false);
            if(nativeNotDispatched)return;
            if(!nativeReadOnly){clearChain();return;}
            // A new handle, timestamp or successful transport is not new task evidence.
            // Keep only bounded digests, including across recovery and failure-chain resets,
            // so alternating the same reads cannot repeatedly buy another failed allowance.
            String result=readEvidence(receipt,source);
            if(result.isEmpty())return;
            String fingerprint=ScopedToolCatalog.fingerprint(new JSONObject().put("call",call).put("result",result));
            boolean seen=false,modernBaseline=false,legacyObserved=false;
            for(int i=recentFingerprints.length()-1;i>=0;i--){JSONObject prior=recentFingerprints.getJSONObject(i);
                if("read_result".equals(prior.getString("category"))){
                    if(call.equals(prior.getString("call"))&&source.equals(prior.getString("source")))modernBaseline=true;
                    if(fingerprint.equals(prior.getString("fingerprint"))){seen=true;break;}
                }else if("observed_result".equals(prior.getString("category"))&&call.equals(prior.getString("call"))&&source.equals(prior.getString("source")))legacyObserved=true;
            }
            // Published wait checkpoints did not retain a content digest. The first modern
            // observation establishes that baseline without renewing the old failure allowance.
            if(legacyObserved&&!modernBaseline)seen=true;
            if(!seen)clearChain();
            remember(tool,call,source,fingerprint,"read_result");return;
        }
        if(observationWait(tool)&&!nativeNotDispatched||!Boolean.FALSE.equals(receipt.opt("ok"))||(!nativeReadOnly&&!nativeNotDispatched)||code.isEmpty()){
            round.put("allFailed",false);return;
        }
        // A previously unseen source version is new evidence. Returning to an already
        // observed version is part of the same bounded cycle, not another fresh allowance.
        if(!source.isEmpty()){
            boolean seen=false,changedSource=false;
            for(int i=recentFingerprints.length()-1;i>=0;i--){JSONObject prior=recentFingerprints.getJSONObject(i);
                if(call.equals(prior.getString("call"))){String previous=prior.getString("source");
                    if(source.equals(previous))seen=true;else if(!previous.isEmpty())changedSource=true;}}
            if(changedSource&&!seen)clearChain();
        }
        String fingerprint=ScopedToolCatalog.fingerprint(new JSONObject().put("call",call).put("category",code).put("source",source));
        remember(tool,call,source,fingerprint,publicCategory(code));round.getJSONArray("failures").put(fingerprint);
    }

    private void remember(String tool,String call,String source,String fingerprint,String category)throws Exception {
        recentFingerprints.put(new JSONObject().put("call",call).put("source",source).put("fingerprint",fingerprint)
                .put("tool",tool.length()<=128?tool:"tool_"+ScopedToolCatalog.fingerprint(tool)).put("category",category));
        recentFingerprints=tail(recentFingerprints,RECENT);
    }

    private static String callFingerprint(String tool,Object arguments)throws Exception {
        Object normalized=arguments;
        if(arguments instanceof String raw){try{normalized=new JSONObject(raw);}catch(Exception invalid){normalized=new JSONObject().put("invalidArgumentsDigest",ScopedToolCatalog.fingerprint(raw));}}
        return ScopedToolCatalog.fingerprint(new JSONObject().put("tool",tool).put("arguments",normalized));
    }

    private static String readEvidence(JSONObject receipt,String source)throws Exception {
        if(!source.isEmpty())return source;
        JSONObject content=new JSONObject();
        // These are original result payloads, not delivery receipts or status prose. Unknown
        // receipt shapes remain neutral rather than being presumed to establish progress.
        for(String key:Set.of("content","text","body","result","results","data","items","entries","rows","matches","files",
                "lines","stdout","stderr","nodes","packageName","screenWidth","screenHeight","url","title"))
            if(receipt.has(key)&&!receipt.isNull(key))content.put(key,receipt.get(key));
        return content.length()==0?"":ScopedToolCatalog.fingerprint(content);
    }

    void finishRound()throws Exception {
        if(round==null)return;
        if(round.getBoolean("allFailed")&&round.getInt("processed")==round.getInt("expected")
                &&round.getJSONArray("failures").length()==round.getInt("expected")){
            consecutiveAllFailed=Math.min(1_000_000,consecutiveAllFailed+1);
            cycleWindow.put(ScopedToolCatalog.fingerprint(round.getJSONArray("failures")));cycleWindow=tail(cycleWindow,WINDOW);
            int[] pattern=pattern(cycleWindow);
            if(pattern[0]>0&&pattern[1]>=warningLength(pattern[0])&&!notified)pending=true;
            if(pattern[0]>0&&pattern[1]>=hardLength(pattern[0]))stopped=true;
        }
        round=null;changed();
    }

    boolean stopRequired(){return stopped;}

    JSONObject snapshot()throws Exception {
        JSONObject value=new JSONObject().put("format",FORMAT).put("progressGuardRevision",revision).put("ownerBoundary",ownerBoundary)
                .put("consecutiveAllFailed",consecutiveAllFailed).put("cycleWindow",new JSONArray(cycleWindow.toString()))
                .put("recentFingerprints",new JSONArray(recentFingerprints.toString())).put("notified",notified).put("noticePending",pending)
                .put("stopped",stopped).put("pendingRound",round==null?JSONObject.NULL:new JSONObject(round.toString()));
        validate(value);return value;
    }

    /** Store-side validation and monotonic write guard in the same original checkpoint transaction.
     * A legacy three-argument checkpoint preserves the last guard instead of deleting it. */
    public static JSONObject checkpointValue(JSONObject previous,JSONObject supplied)throws Exception {
        Object old=previous==null?null:previous.opt("progressGuard");
        if(old!=null&&!(old instanceof JSONObject))throw new SecurityException("progress_guard_checkpoint_invalid");
        JSONObject before=(JSONObject)old;if(before!=null)validate(before);
        if(supplied==null)return before==null?null:new JSONObject(before.toString());
        validate(supplied);
        if(before!=null){long prior=before.getLong("progressGuardRevision"),next=supplied.getLong("progressGuardRevision");
            if(next<prior||next==prior&&!ScopedToolCatalog.fingerprint(before).equals(ScopedToolCatalog.fingerprint(supplied)))
                throw new SecurityException("progress_guard_checkpoint_stale");
        }
        return new JSONObject(supplied.toString());
    }

    JSONObject diagnostics()throws Exception {
        int[] pattern=pattern(cycleWindow);
        return new JSONObject().put("format",FORMAT).put("progressGuardRevision",revision).put("consecutiveAllFailed",consecutiveAllFailed)
                .put("cyclePeriod",pattern[0]).put("repeatingRounds",pattern[1]).put("stopped",stopped)
                .put("reason",pattern[0]>1?"repeated_failure_cycle":"repeated_same_failure").put("argumentsIncluded",false).put("receiptBodyIncluded",false)
                .put("executionAuthorized",false).put("automaticReplay",false).put("budgetsReset",false);
    }

    String stopSummary()throws Exception {
        int[] pattern=pattern(cycleWindow);
        return (pattern[0]>1?"工具失败以 "+pattern[0]+" 轮为周期重复，":"同一工具和参数持续收到相同类别的确定失败，")
                +"原任务已停止后续模型和工具调用。已有回执与预算保留；请补充新信息或修正条件后再从原任务继续。未知效果仍须核对，不会自动重放。";
    }

    /** Once per chain, in the next ordinary request only; no extra inference or body mirror. */
    String referenceData()throws Exception {
        if(!pending||stopped)return "";
        pending=false;notified=true;changed();
        String reference="原工具轮已出现重复的确定失败，或短周期失败循环。请核对原因、改变有效方案或向主人说明阻塞；继续相同失败将由原宿主停止。未知效果须沿原任务核对。\n<native_repeat_tool_notice>\n"
                +diagnostics()+"\n</native_repeat_tool_notice>";
        if(reference.getBytes(StandardCharsets.UTF_8).length>MAX_REFERENCE_BYTES)throw new IllegalStateException("progress_guard_notice_unbounded");
        return reference;
    }

    private void clearChain(){cycleWindow=new JSONArray();consecutiveAllFailed=0;notified=false;pending=false;stopped=false;}
    private void changed(){revision=Math.addExact(revision,1);}
    private static int warningLength(int period){return period==1?THRESHOLD:period+1;}
    private static int hardLength(int period){return Math.max(6,period*3);}
    private static int[] pattern(JSONArray window)throws Exception {
        int n=window.length();for(int period=1;period<=3;period++){
            int length=period;for(int i=n-1;i>=period;i--){if(!window.getString(i).equals(window.getString(i-period)))break;length++;}
            if(length>=warningLength(period)&&length<=n)return new int[]{period,length};
        }
        return new int[]{0,0};
    }
    private static boolean observationWait(String name){
        String words=name.replaceAll("([a-z0-9])([A-Z])","$1_$2").toLowerCase(Locale.ROOT);
        return java.util.regex.Pattern.compile("(?:^|_)(?:poll|status|wait|watch|logs?|get_?job|list_?jobs|receipt)(?:_|$)").matcher(words).find();
    }
    private static String failureCode(JSONObject receipt){String code=receipt.optString("code",receipt.optString("error")).toLowerCase(Locale.ROOT);return code.matches("[a-z0-9_.:-]{1,128}")?code:"";}
    private static String publicCategory(String code){
        if(code.contains("permission")||code.contains("denied")||code.contains("forbidden"))return "permission_denied";
        if(code.contains("invalid")||code.contains("argument")||code.contains("owner_intent"))return "input_or_scope_rejected";
        if(code.contains("not_found"))return "source_not_found";
        if(code.contains("unavailable")||code.contains("withdrawn")||code.contains("stale"))return "source_unavailable";
        return "definite_failure";
    }
    private static String sourceVersion(JSONObject receipt)throws Exception {
        JSONObject source=new JSONObject();
        for(String key:Set.of("sourceSha256","contentSha256","sha256","sourceDigest","sourceRevision","resultRevision","sourceVersion","version","revision","headSha")){
            Object value=receipt.opt(key);if(value instanceof String text&&!text.isEmpty()&&text.length()<=512||value instanceof Number)source.put(key,value);
        }
        return source.length()==0?"":ScopedToolCatalog.fingerprint(source);
    }
    private static JSONArray tail(JSONArray array,int maximum)throws Exception {JSONArray result=new JSONArray();for(int i=Math.max(0,array.length()-maximum);i<array.length();i++)result.put(array.get(i));return result;}
    private static long integer(JSONObject value,String key,long maximum)throws Exception {
        Object raw=value.opt(key);if(!(raw instanceof Integer||raw instanceof Long)||((Number)raw).longValue()<0||((Number)raw).longValue()>maximum)throw new SecurityException("progress_guard_checkpoint_invalid");return ((Number)raw).longValue();
    }
    private static void digest(String value,boolean empty){if(!(empty&&value.isEmpty())&&!value.matches("[a-f0-9]{64}"))throw new SecurityException("progress_guard_checkpoint_invalid");}
    private static void keys(JSONObject value,Set<String> allowed){Set<String> actual=new java.util.HashSet<>();value.keys().forEachRemaining(actual::add);if(!actual.equals(allowed))throw new SecurityException("progress_guard_checkpoint_invalid");}
    private static void validate(JSONObject value)throws Exception {
        keys(value,Set.of("format","progressGuardRevision","ownerBoundary","consecutiveAllFailed","cycleWindow","recentFingerprints","notified","noticePending","stopped","pendingRound"));
        if(!FORMAT.equals(value.optString("format"))||value.toString().getBytes(StandardCharsets.UTF_8).length>MAX_STATE_BYTES)throw new SecurityException("progress_guard_checkpoint_invalid");
        integer(value,"progressGuardRevision",Long.MAX_VALUE);integer(value,"consecutiveAllFailed",1_000_000);digest(value.getString("ownerBoundary"),true);
        for(String key:Set.of("notified","noticePending","stopped"))if(!(value.opt(key) instanceof Boolean))throw new SecurityException("progress_guard_checkpoint_invalid");
        JSONArray window=value.getJSONArray("cycleWindow"),recent=value.getJSONArray("recentFingerprints");
        if(window.length()>WINDOW||recent.length()>RECENT||window.length()>value.getInt("consecutiveAllFailed"))throw new SecurityException("progress_guard_checkpoint_invalid");
        for(int i=0;i<window.length();i++)digest(window.getString(i),false);
        for(int i=0;i<recent.length();i++){JSONObject row=recent.getJSONObject(i);keys(row,Set.of("call","source","fingerprint","tool","category"));digest(row.getString("call"),false);digest(row.getString("fingerprint"),false);digest(row.getString("source"),true);
            if(row.getString("tool").length()>128||!row.getString("category").matches("[a-z0-9_.:-]{1,128}"))throw new SecurityException("progress_guard_checkpoint_invalid");}
        int[] pattern=pattern(window);boolean hard=pattern[0]>0&&pattern[1]>=hardLength(pattern[0]);
        if(value.getBoolean("stopped")!=hard||value.getBoolean("notified")&&value.getBoolean("noticePending"))throw new SecurityException("progress_guard_checkpoint_invalid");
        if(!value.has("pendingRound"))throw new SecurityException("progress_guard_checkpoint_invalid");
        if(!value.isNull("pendingRound")){JSONObject round=value.getJSONObject("pendingRound");keys(round,Set.of("expected","processed","allFailed","failures"));long expected=integer(round,"expected",8),processed=integer(round,"processed",8);
            if(expected<1||processed>expected||!(round.opt("allFailed") instanceof Boolean))throw new SecurityException("progress_guard_checkpoint_invalid");
            JSONArray failures=round.getJSONArray("failures");if(failures.length()>processed||round.getBoolean("allFailed")&&failures.length()!=processed)throw new SecurityException("progress_guard_checkpoint_invalid");
            for(int i=0;i<failures.length();i++)digest(failures.getString(i),false);
        }
    }
}
