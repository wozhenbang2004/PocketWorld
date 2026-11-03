package dev.pocketengine.features.memory;

import java.time.Instant;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native owner inspection of already retained versions. Neither a current-fact snapshot
 * nor a source, task/member read grant, persisted index or replacement for original evidence. */
final class MemoryHistoryPage {
    static final int MAX_LIMIT=20,PREVIEW_CHARS=280;
    private MemoryHistoryPage(){}
    static boolean inspectable(String status){return !Set.of("imagined_not_real_world_fact","not_yet_valid","source_or_record_inactive").contains(status);}
    static JSONObject search(List<JSONObject> admitted,Map<String,String> statuses,String rawQuery,String cursor,int limit)throws Exception {
        String query=MemoryQueryPlan.lexical(rawQuery,"not_requested").queries().get(0);
        if(limit<1||limit>MAX_LIMIT)throw new IllegalArgumentException("旧事查阅每页为1至20条");
        List<JSONObject> inspectable=new ArrayList<>();for(JSONObject row:admitted)if(inspectable(statuses.getOrDefault(row.getString("id"),"untyped_time_unknown")))inspectable.add(row);
        MemoryEntityAliasProjection aliases=MemoryEntityAliasProjection.from(inspectable);List<Hit> hits=new ArrayList<>();
        for(JSONObject row:inspectable){String status=statuses.getOrDefault(row.getString("id"),"untyped_time_unknown");
            MemoryRetrievalBackend.Document base=MemoryBook.retrievalDocument(row,aliases);String searchable=base.text+'\n'+date(row.opt("createdAt"));JSONObject assertion=row.optJSONObject("assertion");
            if(assertion!=null)for(String key:List.of("eventTime","validFrom","validTo"))searchable+='\n'+date(assertion.opt(key));
            String normalized=LexicalMemoryRetrievalBackend.normalize(query);
            double score=query.matches("[0-9]{4}(?:-[0-9]{2}){0,2}")&&!LexicalMemoryRetrievalBackend.normalize(searchable).contains(normalized)?-1:
                LexicalMemoryRetrievalBackend.score(new MemoryRetrievalBackend.Document(base.ownerId,base.spaceId,base.id,base.revision,searchable,base.updatedAt,base.confidence),normalized);
            if(score>=0)hits.add(new Hit(row,status,score));
        }
        hits.sort(Comparator.comparingDouble((Hit hit)->hit.score).reversed().thenComparing(Comparator.comparingLong((Hit hit)->sortTime(hit.row)).reversed()).thenComparing(hit->hit.row.optString("id")));
        JSONArray versions=new JSONArray();for(Hit hit:hits)versions.put(new JSONArray().put(hit.row.getString("id")).put(hit.row.getLong("revision")).put(hit.status));
        String version=TaskMemoryUse.digest(new JSONArray().put(query).put(limit).put(versions).toString());int offset=0;
        if(cursor!=null&&!cursor.isEmpty()){
            if(!cursor.matches("[0-9]{1,4}\\.[a-f0-9]{64}"))throw new IllegalArgumentException("旧事分页位置无效");int split=cursor.indexOf('.');offset=Integer.parseInt(cursor.substring(0,split));
            if(offset>hits.size()||!version.equals(cursor.substring(split+1)))throw new IllegalStateException("旧事内容、来源或检索条件已变化，请重新搜索");
        }
        JSONArray page=new JSONArray();int end=Math.min(hits.size(),offset+limit);for(int i=offset;i<end;i++)page.put(header(hits.get(i)));
        return new JSONObject().put("format","pocketworld.owner-memory-history-page.v1").put("matches",page).put("totalMatches",hits.size()).put("nextCursor",end<hits.size()?end+"."+version:"").put("resultVersion",version)
            .put("query",query).put("limit",limit).put("readMode","native_owner_retained_versions").put("activeRecall",false).put("originalEvidenceRead",false).put("memberReadAuthorized",false).put("executionAuthorized",false);
    }
    private static JSONObject header(Hit hit)throws Exception {
        JSONObject row=hit.row,assertion=row.optJSONObject("assertion");String text=row.getString("text");int end=Math.min(PREVIEW_CHARS,text.length());if(end>0&&end<text.length()&&Character.isHighSurrogate(text.charAt(end-1))&&Character.isLowSurrogate(text.charAt(end)))end--;
        JSONObject out=new JSONObject().put("id",row.getString("id")).put("revision",row.getLong("revision")).put("textPreview",text.substring(0,end)).put("previewTruncated",end<text.length())
            .put("memoryTextIsOriginalEvidence",false).put("temporalStatus",hit.status).put("layer",row.getString("layer")).put("savedAt",row.getLong("createdAt")).put("eventTime",assertion==null?JSONObject.NULL:assertion.opt("eventTime"))
            .put("evidenceSpanCount",assertion==null?0:assertion.getJSONArray("evidenceSpans").length());
        if(assertion!=null)out.put("subjectId",assertion.getString("subjectId")).put("subjectType",assertion.getString("subjectType")).put("subjectLabel",assertion.getString("subjectLabel")).put("assertionKind",assertion.getString("kind"));
        return out;
    }
    private static String date(Object value){return value instanceof Number?Instant.ofEpochMilli(((Number)value).longValue()).toString():"";}
    private static long sortTime(JSONObject row){JSONObject assertion=row.optJSONObject("assertion");Object event=assertion==null?null:assertion.opt("eventTime");return event instanceof Number?((Number)event).longValue():row.optLong("createdAt");}
    private static final class Hit {final JSONObject row;final String status;final double score;Hit(JSONObject row,String status,double score){this.row=row;this.status=status;this.score=score;}}
}
