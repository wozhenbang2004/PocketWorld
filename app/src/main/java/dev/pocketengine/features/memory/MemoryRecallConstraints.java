package dev.pocketengine.features.memory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Narrowing predicates over already admitted Book rows. These never select a namespace,
 * create an audience, or turn a historical assertion into a current fact. */
public final class MemoryRecallConstraints {
    private static final Set<String> FIELDS=Set.of("maxHops","layers","sourceKinds","entityIds","updatedAfter","updatedBefore","eventAfter","eventBefore");
    public final int maxHops;
    private final Set<String> layers,sources,entities;
    private final long updatedAfter,updatedBefore,eventAfter,eventBefore;

    private MemoryRecallConstraints(int hops,Set<String> layers,Set<String> sources,Set<String> entities,long after,long before,long eventAfter,long eventBefore){
        maxHops=hops;this.layers=Set.copyOf(layers);this.sources=Set.copyOf(sources);this.entities=Set.copyOf(entities);
        updatedAfter=after;updatedBefore=before;this.eventAfter=eventAfter;this.eventBefore=eventBefore;
    }
    static MemoryRecallConstraints compatibility(){return new MemoryRecallConstraints(0,Set.of(),Set.of(),Set.of(),-1,-1,-1,-1);}
    public static MemoryRecallConstraints checked(JSONObject value)throws Exception {
        if(value==null)throw new IllegalArgumentException("检索约束必须是对象");
        for(var keys=value.keys();keys.hasNext();)if(!FIELDS.contains(keys.next()))throw new IllegalArgumentException("检索约束只能缩小原范围，不能指定权限或记忆正文");
        long hops=value.has("maxHops")?SourceRef.integer(value,"maxHops",0):2;
        if(hops>2)throw new IllegalArgumentException("实体关系最多回读两跳");
        Set<String> layers=strings(value,"layers",Set.of("fact","preference","episodic","procedural"),4,20);
        Set<String> sources=strings(value,"sourceKinds",Set.of("message","task_evidence","workspace_file","owner_saved"),4,30);
        Set<String> entities=strings(value,"entityIds",null,8,160);
        for(String id:entities)if(!id.matches("[\\p{L}\\p{N}_.:/@-]{1,160}"))throw new IllegalArgumentException("实体筛选只接受已有稳定身份");
        long after=time(value,"updatedAfter"),before=time(value,"updatedBefore"),ea=time(value,"eventAfter"),eb=time(value,"eventBefore");
        if(after>=0&&before>=0&&after>before||ea>=0&&eb>=0&&ea>eb)throw new IllegalArgumentException("检索时间范围颠倒");
        return new MemoryRecallConstraints((int)hops,layers,sources,entities,after,before,ea,eb);
    }
    /** Event constraints exclude unknown event time; updatedAt is never substituted for it. */
    boolean matches(JSONObject row)throws Exception {
        if(!layers.isEmpty()&&!layers.contains(row.optString("layer")))return false;
        long updated=row.getLong("updatedAt");
        if(updatedAfter>=0&&updated<updatedAfter||updatedBefore>=0&&updated>updatedBefore)return false;
        JSONObject assertion=row.optJSONObject("assertion");
        if(!entities.isEmpty()&&(assertion==null||!entities.contains(assertion.optString("subjectId"))&&!entities.contains(assertion.optString("objectId"))))return false;
        if(eventAfter>=0||eventBefore>=0){
            if(assertion==null||assertion.isNull("eventTime"))return false;
            long at=assertion.getLong("eventTime");if(eventAfter>=0&&at<eventAfter||eventBefore>=0&&at>eventBefore)return false;
        }
        if(!sources.isEmpty()){
            JSONArray refs=row.optJSONArray("sourceRefs");boolean matches=refs!=null&&refs.length()==0&&sources.contains("owner_saved");
            for(int i=0;refs!=null&&i<refs.length();i++)if(sources.contains(refs.getJSONObject(i).getString("kind")))matches=true;
            if(!matches)return false;
        }
        return true;
    }
    public JSONObject metadata()throws Exception {
        JSONObject result=new JSONObject().put("maxHops",maxHops).put("layers",ordered(layers)).put("sourceKinds",ordered(sources)).put("entityIds",ordered(entities));
        if(updatedAfter>=0)result.put("updatedAfter",updatedAfter);if(updatedBefore>=0)result.put("updatedBefore",updatedBefore);
        if(eventAfter>=0)result.put("eventAfter",eventAfter);if(eventBefore>=0)result.put("eventBefore",eventBefore);
        return result;
    }
    public static JSONObject schema()throws Exception {
        JSONObject properties=new JSONObject().put("maxHops",new JSONObject().put("type","integer").put("minimum",0).put("maximum",2));
        properties.put("layers",arraySchema(4,20,new JSONArray(List.of("fact","preference","episodic","procedural"))));
        properties.put("sourceKinds",arraySchema(4,30,new JSONArray(List.of("message","task_evidence","workspace_file","owner_saved"))));
        properties.put("entityIds",arraySchema(8,160,null).put("description","Exact existing stable subject/object IDs, not display names or permission grants."));
        for(String field:List.of("updatedAfter","updatedBefore","eventAfter","eventBefore"))properties.put(field,new JSONObject().put("type","integer").put("minimum",0).put("maximum",Long.MAX_VALUE)
                .put("description",field.startsWith("event")?"Inclusive event-time bound in Unix milliseconds. Unknown event time is excluded; update time is never substituted.":"Inclusive saved-record update-time bound in Unix milliseconds; this is not the historical event time."));
        return new JSONObject().put("type","object").put("properties",properties).put("additionalProperties",false);
    }
    private static JSONObject arraySchema(int max,int chars,JSONArray values)throws Exception {
        JSONObject item=new JSONObject().put("type","string").put("minLength",1).put("maxLength",chars);if(values!=null)item.put("enum",values);
        return new JSONObject().put("type","array").put("maxItems",max).put("uniqueItems",true).put("items",item);
    }
    private static Set<String> strings(JSONObject input,String key,Set<String> allowed,int max,int chars)throws Exception {
        if(!input.has(key))return Set.of();Object raw=input.get(key);if(!(raw instanceof JSONArray))throw new IllegalArgumentException(key+"必须是列表");
        JSONArray items=(JSONArray)raw;if(items.length()>max)throw new IllegalArgumentException(key+"超出有界范围");Set<String> values=new LinkedHashSet<>();
        for(int i=0;i<items.length();i++){
            Object value=items.get(i);if(!(value instanceof String)||((String)value).isEmpty()||((String)value).length()>chars)throw new IllegalArgumentException(key+"文字无效");
            MemoryBook.validateUnicode((String)value);if(allowed!=null&&!allowed.contains(value)||!values.add((String)value))throw new IllegalArgumentException(key+"含未知或重复值");
        }return values;
    }
    private static long time(JSONObject input,String key)throws Exception{return input.has(key)?SourceRef.integer(input,key,0):-1;}
    private static JSONArray ordered(Set<String> values){return new JSONArray(new java.util.TreeSet<>(values));}
}
