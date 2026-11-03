package dev.pocketengine.features.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Query-local ranking of Book-admitted retained versions. The Book owns scope, audience,
 * source validity and final reread; these views are neither a new index nor current facts. */
final class MemoryHistoricalRecall {
    private MemoryHistoricalRecall(){}
    static JSONObject select(List<JSONObject> admitted,Map<String,String> statuses,MemoryQueryPlan plan,int limit,int maxChars)throws Exception{
        if(admitted.size()>1000||limit<1||limit>16||maxChars<0||maxChars>12000)throw new IllegalArgumentException("历史记忆查询超过原Book窗口");
        Map<String,JSONObject> rows=new LinkedHashMap<>();Map<String,Double> fused=new LinkedHashMap<>();
        MemoryEntityAliasProjection directOnly=MemoryEntityAliasProjection.from(List.of());
        for(JSONObject row:admitted)if(MemoryHistoryPage.inspectable(statuses.getOrDefault(row.getString("id"),"untyped_time_unknown"))&&plan.constraints().matches(row))rows.put(row.getString("id"),row);
        for(String query:plan.queries()){
            List<Hit> channel=new ArrayList<>();String normalized=LexicalMemoryRetrievalBackend.normalize(query);
            for(JSONObject row:rows.values()){double score=LexicalMemoryRetrievalBackend.score(MemoryBook.retrievalDocument(row,directOnly),normalized);if(score>=0)channel.add(new Hit(row,score));}
            channel.sort(Comparator.comparingDouble((Hit hit)->hit.score).reversed().thenComparing(Comparator.comparingLong((Hit hit)->eventTime(hit.row)).reversed()).thenComparing(hit->hit.row.optString("id")));
            for(int rank=0;rank<channel.size();rank++)fused.merge(channel.get(rank).row.getString("id"),1d/(61+rank),Double::sum);
        }
        List<String> ordered=new ArrayList<>(fused.keySet());ordered.sort(Comparator.<String>comparingDouble(fused::get).reversed().thenComparing(Comparator.comparingLong((String id)->eventTime(rows.get(id))).reversed()).thenComparing(id->id));
        String header="以下是已保留的历史记忆版本，只读参考，不是当前事实证明，也不是原始会话或执行证据。时间未知不补造；需要原文时按sourceRefs/span/hash另行核对。\n";
        StringBuilder context=new StringBuilder(header);JSONArray selected=new JSONArray();int charsOmitted=0,countOmitted=0;
        for(String id:ordered){
            JSONObject source=rows.get(id),row=new JSONObject();
            for(String field:List.of("id","revision","ownerId","spaceId","layer","text","sourceRefs","assertion","createdAt","updatedAt"))if(source.has(field))row.put(field,source.get(field));
            if(!row.has("sourceRefs"))row.put("sourceRefs",new JSONArray());
            String text=row.getString("text");row.put("textSha256",TaskMemoryUse.digest(text)).put("temporalStatus",statuses.getOrDefault(id,"untyped_time_unknown"))
                    .put("readOnlyHistory",true).put("memoryTextIsOriginalEvidence",false).put("currentFactVerified",false);
            String line=row+"\n";
            if(selected.length()>=limit){countOmitted++;continue;}
            if(context.length()+line.length()>maxChars){charsOmitted++;continue;}
            context.append(line);selected.put(row);
        }
        return new JSONObject().put("format","pocketworld.task-memory-history.v1").put("readMode","retained_history").put("readOnlyHistory",true)
                .put("activeRecall",false).put("currentFactVerified",false).put("memoryTextIsOriginalEvidence",false).put("modelUseVerified",false).put("executionAuthorized",false)
                .put("records",selected).put("context",selected.length()==0?"":context.toString()).put("status","retained_versions").put("ok",true)
                .put("selectionDiagnostics",new JSONObject().put("admittedRecords",admitted.size()).put("matchingRecords",ordered.size()).put("selectedRecords",selected.length())
                        .put("omittedByCharacterBudget",charsOmitted).put("omittedByRecordBudget",countOmitted).put("recordLimit",limit).put("contextCharLimit",maxChars)
                        .put("bodyChars",selected.length()==0?0:context.length()).put("originalEvidenceRead",false).put("indexPersisted",false))
                .put("queryPlan",plan.metadata().put("historicalStrategy","query_union_rrf_retained_versions_v1").put("maxHopsApplied",0)
                        .put("relationshipExpansion","not_used_for_historical_versions").put("additionalModelRequests",0).put("additionalNetworkRequests",0));
    }
    private static long eventTime(JSONObject row){JSONObject assertion=row.optJSONObject("assertion");Object time=assertion==null?null:assertion.opt("eventTime");return time instanceof Number?((Number)time).longValue():row.optLong("createdAt");}
    private static final class Hit {final JSONObject row;final double score;Hit(JSONObject row,double score){this.row=row;this.score=score;}}
}
