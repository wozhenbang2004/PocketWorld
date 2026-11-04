package dev.pocketengine.features.memory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Pure bounded packing of records already admitted by MemoryBook. Never a retrieval authority. */
final class MemoryRecallBudget {
    private static final int MAX_OMISSIONS=32;
    private static final Set<String> REASONS=Set.of("record_limit","character_limit","record_too_large","source_or_record_changed");
    private MemoryRecallBudget(){}
    static String boundedQuery(String normalized){
        if(normalized.length()<=500)return normalized;
        int first=249,last=normalized.length()-250;
        if(Character.isHighSurrogate(normalized.charAt(first-1)))first--;
        if(Character.isLowSurrogate(normalized.charAt(last)))last++;
        return normalized.substring(0,first)+" "+normalized.substring(last);
    }
    static JSONObject pack(List<JSONObject> core,List<JSONObject> retrieved,Map<String,JSONObject> admitted,int limit,int maxChars,String header,boolean queryTruncated,int queryChars,String backend)throws Exception{
        return pack(core,retrieved,admitted,limit,maxChars,header,queryTruncated,queryChars,backend,false);
    }
    static JSONObject pack(List<JSONObject> core,List<JSONObject> retrieved,Map<String,JSONObject> admitted,int limit,int maxChars,String header,boolean queryTruncated,int queryChars,String backend,boolean ownerContext)throws Exception{
        List<JSONObject> all=new ArrayList<>(core);all.addAll(retrieved);
        Map<String,String> lines=new LinkedHashMap<>();for(JSONObject row:admitted.values())lines.put(row.getString("id"),row.toString()+"\n");
        Set<String> selected=new HashSet<>();int[] used={0};
        int coreQuota=retrieved.isEmpty()?limit:limit/2,coreCharsTarget=retrieved.isEmpty()?maxChars:Math.max(0,(maxChars-header.length())/2);
        // Guarantee a fitting task match a place before core can fill either count or characters.
        for(JSONObject row:retrieved)if((!ownerContext||!ownerCurrent(admitted.get(row.getString("id"))))&&take(row,admitted,lines,selected,used,limit,maxChars,header.length()))break;
        // A personal turn also reserves one fitting current owner fact. It is still an exact
        // formal record in the original budget, not an untracked profile appended afterward.
        if(ownerContext)for(JSONObject row:retrieved)if(ownerCurrent(admitted.get(row.getString("id")))&&take(row,admitted,lines,selected,used,limit,maxChars,header.length()))break;
        int cores=0,coreChars=0;
        for(JSONObject row:core){String id=row.getString("id"),line=lines.get(id);if(line==null||cores>=coreQuota||coreChars+line.length()>coreCharsTarget)continue;
            if(take(row,admitted,lines,selected,used,limit,maxChars,header.length())){cores++;coreChars+=line.length();}}
        for(JSONObject row:retrieved)take(row,admitted,lines,selected,used,limit,maxChars,header.length());
        // Unused task allocation is borrowed by core, including large whole core records.
        for(JSONObject row:core)take(row,admitted,lines,selected,used,limit,maxChars,header.length());
        JSONArray records=new JSONArray(),selection=new JSONArray(),omitted=new JSONArray();JSONObject counts=new JSONObject();StringBuilder context=new StringBuilder();int keptCore=0,keptRetrieved=0;
        for(JSONObject row:all){String id=row.getString("id");boolean isCore=row.optBoolean("core");
            if(selected.contains(id)){
                JSONObject value=admitted.get(id);if(context.length()==0)context.append(header);context.append(lines.get(id));records.put(value);
                selection.put(identity(value).put("reason",isCore?"owner_core":ownerContext&&ownerCurrent(value)?"owner_current":"task_query_match"));if(isCore)keptCore++;else keptRetrieved++;
            }else{
                String line=lines.get(id),reason=line==null?"source_or_record_changed":selected.size()>=limit?"record_limit":header.length()+line.length()>maxChars?"record_too_large":"character_limit";
                counts.put(reason,counts.optInt(reason)+1);if(omitted.length()<MAX_OMISSIONS)omitted.put(identity(row).put("reason",reason));
            }
        }
        JSONObject diagnostics=new JSONObject().put("format",1).put("policy",ownerContext?"balanced_core_owner_task_v1":"balanced_core_task_v1").put("limit",limit).put("maxChars",maxChars).put("charsUsed",context.length())
                .put("coreAvailable",core.size()).put("retrievedAvailable",retrieved.size()).put("coreSelected",keptCore).put("retrievedSelected",keptRetrieved)
                .put("coreQuota",coreQuota).put("coreCharsTarget",coreCharsTarget).put("queryTruncated",queryTruncated).put("queryChars",queryChars)
                .put("queryTruncation",queryTruncated?"head_and_tail":"none").put("backend",backend).put("selected",selection).put("omitted",omitted)
                .put("omissionCounts",counts).put("omissionsTruncated",all.size()-records.length()>omitted.length());
        return new JSONObject().put("records",records).put("context",context.toString()).put("selectionDiagnostics",diagnostics)
                .put("coreSelected",keptCore).put("retrievedSelected",keptRetrieved);
    }
    private static boolean ownerCurrent(JSONObject row){return row!=null&&"owner_current".equals(row.optString("selectionReason"));}
    private static boolean take(JSONObject row,Map<String,JSONObject> admitted,Map<String,String> lines,Set<String> selected,int[] used,int limit,int chars,int header)throws Exception{
        String id=row.getString("id");if(!admitted.containsKey(id)||selected.contains(id)||selected.size()>=limit)return false;
        String line=lines.get(id);if(header+used[0]+line.length()>chars)return false;selected.add(id);used[0]+=line.length();return true;
    }
    private static JSONObject identity(JSONObject row)throws Exception{return new JSONObject().put("id",row.getString("id")).put("revision",row.getLong("revision")).put("selection",row.optBoolean("core")?"core":"retrieved");}

    /** Body-free native journal shape; optional only for historical format1 receipts. */
    static JSONObject checked(JSONObject input,JSONArray references)throws Exception{
        if(input==null)return null;JSONObject d=new JSONObject(input.toString());
        keys(d,Set.of("format","policy","limit","maxChars","charsUsed","coreAvailable","retrievedAvailable","coreSelected","retrievedSelected","coreQuota","coreCharsTarget","queryTruncated","queryChars","queryTruncation","backend","selected","omitted","omissionCounts","omissionsTruncated"));
        if(SourceRef.integer(d,"format",1)!=1||!Set.of("balanced_core_task_v1","balanced_core_owner_task_v1").contains(d.optString("policy")))throw new IllegalArgumentException("记忆选择预算版本无效");
        int limit=number(d,"limit",16),chars=number(d,"maxChars",12000),used=number(d,"charsUsed",12000);
        int core=number(d,"coreAvailable",1000),retrieved=number(d,"retrievedAvailable",1000),keptCore=number(d,"coreSelected",16),keptRetrieved=number(d,"retrievedSelected",16);
        number(d,"coreQuota",16);number(d,"coreCharsTarget",12000);number(d,"queryChars",500);
        boolean truncated=MemoryBook.requiredBoolean(d,"queryTruncated");MemoryBook.requiredBoolean(d,"omissionsTruncated");
        if(used>chars||keptCore>core||keptRetrieved>retrieved||keptCore+keptRetrieved>limit||references.length()!=keptCore+keptRetrieved
                ||!(truncated?"head_and_tail":"none").equals(d.optString("queryTruncation"))||d.getString("backend").length()>100)throw new IllegalArgumentException("记忆选择预算不一致");
        JSONArray selected=d.getJSONArray("selected"),omitted=d.getJSONArray("omitted");if(selected.length()!=references.length()||omitted.length()>MAX_OMISSIONS)throw new IllegalArgumentException("记忆选择说明超出范围");
        Set<String> ids=new HashSet<>();int actualCore=0;
        for(int i=0;i<selected.length();i++){
            JSONObject row=selected.getJSONObject(i),ref=references.getJSONObject(i);checkedIdentity(row,ids);
            if(!row.getString("id").equals(ref.getString("id"))||SourceRef.integer(row,"revision",1)!=SourceRef.integer(ref,"revision",1)||!row.getString("selection").equals(ref.getString("selection")))throw new SecurityException("选择说明不是该次原始记录");
            boolean isCore="core".equals(row.getString("selection"));if(isCore)actualCore++;
            if(!(isCore?"owner_core":"task_query_match").equals(row.getString("reason"))&&!("balanced_core_owner_task_v1".equals(d.getString("policy"))&&!isCore&&"owner_current".equals(row.getString("reason"))))throw new IllegalArgumentException("记忆选择原因无效");
        }
        int omittedTotal=core+retrieved-selected.length();JSONObject counts=d.getJSONObject("omissionCounts");int total=0;
        for(var it=counts.keys();it.hasNext();){String reason=it.next();if(!REASONS.contains(reason))throw new IllegalArgumentException("记忆省略原因无效");total+=number(counts,reason,1000);}
        Map<String,Integer> shown=new LinkedHashMap<>();for(int i=0;i<omitted.length();i++){JSONObject row=omitted.getJSONObject(i);checkedIdentity(row,ids);String reason=row.getString("reason");if(!REASONS.contains(reason))throw new IllegalArgumentException("记忆省略原因无效");shown.merge(reason,1,Integer::sum);}
        if(actualCore!=keptCore||total!=omittedTotal||omitted.length()!=Math.min(MAX_OMISSIONS,omittedTotal)||d.getBoolean("omissionsTruncated")!=(omittedTotal>MAX_OMISSIONS))throw new IllegalArgumentException("记忆省略计数不一致");
        for(var e:shown.entrySet())if(e.getValue()>counts.optInt(e.getKey()))throw new IllegalArgumentException("记忆省略明细多于原计数");return d;
    }
    private static int number(JSONObject d,String key,int max)throws Exception{long value=SourceRef.integer(d,key,0);if(value>max)throw new IllegalArgumentException("记忆选择预算超出范围");return (int)value;}
    private static void checkedIdentity(JSONObject row,Set<String> ids)throws Exception{
        keys(row,Set.of("id","revision","selection","reason"));if(!row.getString("id").matches("[0-9a-f-]{36}")||!ids.add(row.getString("id"))||!Set.of("core","retrieved").contains(row.getString("selection")))throw new IllegalArgumentException("记忆选择身份无效");SourceRef.integer(row,"revision",1);
    }
    private static void keys(JSONObject d,Set<String> keys){if(d.length()!=keys.size())throw new IllegalArgumentException("记忆选择说明字段缺失");for(var it=d.keys();it.hasNext();)if(!keys.contains(it.next()))throw new IllegalArgumentException("记忆选择说明不接受正文或非投影字段");}
}
