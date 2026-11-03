package dev.pocketengine.features.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** A disposable plan over the original history readers. Query suggestions are untrusted text;
 * the plan neither owns evidence nor grants a conversation, task or model permission. */
final class MemoryEvidencePlan {
    static final int MAX_CALLS=8,MAX_BRANCHES=3,MAX_CANDIDATES=80,MAX_CANDIDATE_CHARS=32000;
    static final Set<String> FIELDS=Set.of("queries","follow","readTop","maxCalls");
    final ConversationHistory.Request request;
    final List<String> queries;
    final String follow;
    final int readTop,maxCalls;

    static boolean requested(JSONObject input){for(String field:FIELDS)if(input.has(field))return true;return false;}
    MemoryEvidencePlan(JSONObject supplied)throws Exception{
        JSONObject input=new JSONObject(supplied.toString());
        follow=ConversationHistory.Request.text(input,"follow","none",20);
        if(!Set.of("none","messages","operations","outputs").contains(follow))throw new IllegalArgumentException("未知历史补查层次");
        readTop=ConversationHistory.Request.integer(input,"readTop",0,0,3);
        maxCalls=ConversationHistory.Request.integer(input,"maxCalls",4,1,MAX_CALLS);
        if(readTop>=maxCalls)throw new IllegalArgumentException("原件回读须为查询保留调用预算");
        String original=ConversationHistory.Request.text(input,"query","",500);
        JSONArray suggested=input.optJSONArray("queries");
        if(input.has("queries")&&suggested==null)throw new IllegalArgumentException("历史短查询建议必须是数组");
        queries=suggested==null||suggested.length()==0?List.of(original):MemoryQueryPlan.agentSuggested(original,suggested).queries();
        for(String field:FIELDS)input.remove(field);
        String kind=ConversationHistory.Request.text(input,"kind","messages",20);
        if(kind.equals("conversations")&&!follow.equals("none")){
            input.put("query","");input.remove("createdAfter");input.remove("createdBefore");
        }
        request=new ConversationHistory.Request(input);
        if(!request.cursor.isEmpty()||request.kind.equals("neighbors"))throw new IllegalArgumentException("计划补查不重用单页游标或邻域查询；可沿返回位置另作精确查询");
        if(follow.equals("messages")&&!request.kind.equals("conversations")
                ||Set.of("operations","outputs").contains(follow)&&!request.kind.equals("runs")
                ||request.kind.equals("conversations")&&!Set.of("none","messages").contains(follow))
            throw new IllegalArgumentException("历史补查只能沿已发现会话到消息，或原任务到轮次和操作/输出");
        if(request.kind.equals("conversations")&&follow.equals("none")&&suggested!=null&&suggested.length()>0)
            throw new IllegalArgumentException("会话目录没有可改写的正文查询");
        after=supplied.has("createdAfter")?SourceRef.integer(supplied,"createdAfter",0):-1;
        before=supplied.has("createdBefore")?SourceRef.integer(supplied,"createdBefore",0):-1;
        if(after>=0&&before>=0&&after>before)throw new IllegalArgumentException("历史日期范围颠倒");
    }
    final long after,before;
    JSONObject options(String query,boolean leaf,int availableChars)throws Exception{
        return new JSONObject().put("query",query).put("cursor","").put("limit",request.limit)
                .put("maxChars",Math.min(4000,Math.min(request.maxChars,availableChars)))
                .put("createdAfter",leaf?after:-1).put("createdBefore",leaf?before:-1);
    }

    /** Source producers currently accept their own 1 MiB / 100-row / 500 ms windows, not a
     * shared physical-I/O meter. Reserve a complete window before each call. The receipt
     * reports that conservative aggregate ceiling and the producer counters separately. */
    static final class Budget {
        final int limit;final long started=System.nanoTime();
        int calls,candidateChars,reportedRows,unmeteredCalls;
        long reportedBytes,reportedAuthorityBytes;
        final JSONArray stages=new JSONArray();
        Budget(int limit){this.limit=limit;}
        boolean available(){return calls<limit&&candidateChars<MAX_CANDIDATE_CHARS
                &&System.nanoTime()-started<(long)limit*TaskStoreMemoryQuery.RAW_DEADLINE_MILLIS*1_000_000L;}
        void take(){if(!available())throw new IllegalStateException("历史计划预算已耗尽");calls++;}
        void record(String kind,JSONObject result)throws Exception{
            JSONObject coverage=result.optJSONObject("pageCoverage");if(coverage==null)coverage=result.optJSONObject("readBudget");
            long bytes=-1,rows=-1;
            if(coverage!=null){
                if(coverage.has("scannedUtf8Bytes"))bytes=SourceRef.integer(coverage,"scannedUtf8Bytes",0);
                else if(coverage.has("sourceUtf8Bytes"))bytes=SourceRef.integer(coverage,"sourceUtf8Bytes",0);
                if(coverage.has("scannedRows"))rows=SourceRef.integer(coverage,"scannedRows",0);
                if(coverage.has("authorityUtf8Bytes"))reportedAuthorityBytes=Math.addExact(reportedAuthorityBytes,SourceRef.integer(coverage,"authorityUtf8Bytes",0));
            }
            if(bytes>TaskStoreMemoryQuery.RAW_MAX_BYTES||rows>TaskStoreMemoryQuery.RAW_MAX_ROWS)throw new SecurityException("历史来源超出原生单次读取窗口");
            if(bytes<0||rows<0)unmeteredCalls++;
            if(bytes>=0)reportedBytes=Math.addExact(reportedBytes,bytes);
            if(rows>=0)reportedRows=Math.addExact(reportedRows,Math.toIntExact(rows));
            if(reportedBytes>(long)limit*TaskStoreMemoryQuery.RAW_MAX_BYTES||reportedRows>limit*TaskStoreMemoryQuery.RAW_MAX_ROWS)throw new SecurityException("历史计划来源累计预算超限");
            JSONArray entries=result.optJSONArray("entries");int chars=result.optString("body").length();
            for(int i=0;entries!=null&&i<entries.length();i++)chars=Math.addExact(chars,entries.getJSONObject(i).optString("body").length());
            candidateChars=Math.addExact(candidateChars,chars);if(candidateChars>MAX_CANDIDATE_CHARS)throw new SecurityException("历史计划候选正文超过范围");
            stages.put(new JSONObject().put("kind",kind).put("status",result.optString("status","unavailable"))
                    .put("reason",result.optString("reason")).put("returnedRows",entries==null?0:entries.length())
                    .put("returnedBodyChars",chars).put("reportedSourceUtf8Bytes",bytes<0?JSONObject.NULL:bytes)
                    .put("reportedSourceRows",rows<0?JSONObject.NULL:rows));
        }
        JSONObject metadata()throws Exception{return new JSONObject().put("producerCalls",calls).put("maxProducerCalls",limit)
                .put("reservedSourceUtf8Bytes",(long)calls*TaskStoreMemoryQuery.RAW_MAX_BYTES)
                .put("sourceUtf8ByteCeiling",(long)limit*TaskStoreMemoryQuery.RAW_MAX_BYTES)
                .put("sourceRowCeiling",limit*TaskStoreMemoryQuery.RAW_MAX_ROWS)
                .put("reportedSourceUtf8Bytes",reportedBytes).put("reportedSourceRows",reportedRows)
                .put("reportedAuthorityUtf8Bytes",reportedAuthorityBytes).put("callsWithIncompleteCounters",unmeteredCalls)
                .put("candidateBodyChars",candidateChars).put("maxCandidateBodyChars",MAX_CANDIDATE_CHARS)
                .put("producerSchedulingDeadlineMillis",limit*TaskStoreMemoryQuery.RAW_DEADLINE_MILLIS)
                .put("elapsedMillis",(System.nanoTime()-started)/1_000_000L)
                .put("byteAccounting","producer_materialized_text_not_physical_io")
                .put("sharedProducerWindow",false).put("nativeIntegrityChecksMeteredHere",false)
                .put("extraModelRequests",0).put("extraNetworkRequests",0).put("indexPersisted",false)
                .put("stages",new JSONArray(stages.toString()));}
    }

    static final class Candidates {
        private final Map<String,JSONObject> rows=new LinkedHashMap<>();
        private final Map<String,Double> scores=new LinkedHashMap<>();
        private final Map<String,String> revisions=new LinkedHashMap<>();
        private final Set<String> changed=new LinkedHashSet<>();
        private final Set<String> rankContributions=new LinkedHashSet<>();
        private int omitted;
        void add(JSONObject supplied,int queryIndex,int rank)throws Exception{
            ConversationHistory.SourceLocator locator=ConversationHistory.SourceLocator.from(supplied);
            String key=locator.referenceKey();String revision=locator.message==null?locator.revision+'\n'+locator.membership:Long.toString(locator.message.revision)+'\n'+locator.messageFingerprint;
            if(rows.size()>=MAX_CANDIDATES&&!rows.containsKey(key)){omitted++;return;}
            String prior=revisions.putIfAbsent(key,revision);
            if(prior!=null&&!prior.equals(revision)){changed.add(key);rows.remove(key);scores.remove(key);return;}
            if(changed.contains(key))return;
            rows.putIfAbsent(key,new JSONObject(supplied.toString()));
            if(rankContributions.add(queryIndex+"\n"+key))scores.merge(key,1d/(61+rank),Double::sum);
        }
        List<JSONObject> ordered(){List<String> keys=new ArrayList<>(rows.keySet());keys.sort(Comparator.<String>comparingDouble(scores::get).reversed().thenComparing(key->key));List<JSONObject> result=new ArrayList<>();for(String key:keys)result.add(rows.get(key));return result;}
        int changedCount(){return changed.size();}
        int omittedCount(){return omitted;}
        int size(){return rows.size();}
    }
}
