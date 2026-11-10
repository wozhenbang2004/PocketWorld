package dev.pocketengine.phone.observation;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Explicit row/field extraction from one real forest. No inferred labels, network or physical action. */
public final class PhoneTreeExtract {
    public static final String TOOL="phone_extract";
    static final int MAX_ROWS=80, MAX_FIELDS=8, OUTPUT_BYTES=48*1024;
    private static final int RESULT_BYTES=120*1024;
    private PhoneTreeExtract() { }
    static void keys(JSONObject value,String... allowed) {
        Set<String> names=new HashSet<>(Arrays.asList(allowed));
        for(Iterator<String> it=value.keys();it.hasNext();)if(!names.contains(it.next()))throw new IllegalArgumentException("未知提取字段");
    }
    static String text(JSONObject value,String key,int max) throws Exception {
        Object raw=value.opt(key);if(!(raw instanceof String)||((String)raw).isEmpty()||((String)raw).length()>max)throw new IllegalArgumentException("无效的"+key);return (String)raw;
    }
    static int integer(JSONObject value,String key,int fallback,int min,int max) {
        if(!value.has(key))return fallback;Object raw=value.opt(key);if(!(raw instanceof Number))throw new IllegalArgumentException("无效的"+key);
        double n=((Number)raw).doubleValue();if(!Double.isFinite(n)||n!=Math.rint(n)||n<min||n>max)throw new IllegalArgumentException("无效的"+key);return (int)n;
    }
    static void selector(JSONObject value) throws Exception {
        keys(value,"text","resourceId","role");for(Iterator<String> it=value.keys();it.hasNext();)text(value,it.next(),400);
    }
    public static void validate(JSONObject args) throws Exception {
        keys(args,"observationId","containerId","rowSelector","fields","limit","offset");text(args,"observationId",100);text(args,"containerId",100);integer(args,"limit",40,1,MAX_ROWS);integer(args,"offset",0,0,AccessibilityForest.MAX_NODES);
        if(args.has("rowSelector"))selector(args.getJSONObject("rowSelector"));
        JSONArray fields=args.getJSONArray("fields");if(fields.length()<1||fields.length()>MAX_FIELDS)throw new IllegalArgumentException("请明确1至8个行内字段");
        Set<String> names=new HashSet<>();
        for(int i=0;i<fields.length();i++){
            JSONObject f=fields.getJSONObject(i);keys(f,"name","selector","attribute","multiple");String name=text(f,"name",40);
            if(!name.matches("[A-Za-z][A-Za-z0-9_]{0,39}")||!names.add(name))throw new IllegalArgumentException("字段名须唯一");selector(f.getJSONObject("selector"));
            if(f.has("attribute")&&!Set.of("text","description","label","checked","selected").contains(text(f,"attribute",20)))throw new IllegalArgumentException("未知字段属性");
            if(f.has("multiple")&&!(f.get("multiple") instanceof Boolean))throw new IllegalArgumentException("multiple须是布尔值");
        }
    }
    public static JSONObject extract(JSONObject observation,JSONObject args) throws Exception {
        return extract(observation,args,true);
    }
    /** Collection merging keeps row evidence; its own native container projection is delivered later. */
    static JSONObject extractRows(JSONObject observation,JSONObject args) throws Exception {
        return extract(observation,args,false);
    }
    private static JSONObject extract(JSONObject observation,JSONObject args,boolean includeObservation) throws Exception {
        validate(args);if(!args.getString("observationId").equals(observation.optString("observationId")))return unavailable("stale_observation");
        Map<String,JSONObject> nodes=index(observation);JSONObject container=nodes.get(args.getString("containerId"));if(container==null||container.optBoolean("password"))return unavailable("container_unavailable");
        List<JSONObject> candidates=new ArrayList<>();JSONObject rowSelector=args.optJSONObject("rowSelector");
        for(JSONObject node:nodes.values()){
            if(node==container||node.optInt("windowId",-1)!=container.getInt("windowId")||!descendant(node,container.getString("id"),nodes)||node.optBoolean("password"))continue;
            boolean row=rowSelector==null?container.getString("id").equals(node.optString("parentId")):matches(node,rowSelector);
            if(row&&hasVisibleContent(node,nodes))candidates.add(node);
        }
        // Nested candidate rows make ownership ambiguous; never assign a sibling's value to a row.
        for(JSONObject a:candidates)for(JSONObject b:candidates)if(a!=b&&descendant(a,b.getString("id"),nodes))return unavailable("overlapping_row_containers");
        JSONArray rows=new JSONArray();int limit=args.optInt("limit",40),offset=args.optInt("offset",0),rowBytes=2;boolean budget=false;
        for(int rowIndex=offset;rowIndex<candidates.size()&&rows.length()<limit;rowIndex++){
            JSONObject row=candidates.get(rowIndex),fields=new JSONObject();
            for(int i=0;i<args.getJSONArray("fields").length();i++){
                JSONObject field=args.getJSONArray("fields").getJSONObject(i),sel=field.getJSONObject("selector");List<JSONObject> found=new ArrayList<>();
                for(JSONObject node:nodes.values())if(node.optInt("windowId",-1)==row.getInt("windowId")&&descendant(node,row.getString("id"),nodes)
                        &&(sel.length()!=0||node==row)&&node.optBoolean("visible",true)&&matches(node,sel))found.add(node);
                boolean complete=sel.length()==0||PhoneTreeQuery.completeSubtree(observation,row,sel.has("text"));
                fields.put(field.getString("name"),field(found,field,complete));
            }
            JSONObject value=new JSONObject().put("rowRef",ref(row)).put("fields",fields);int added=bytes(value)+(rows.length()==0?0:1);
            if(rowBytes+added>OUTPUT_BYTES){budget=true;break;}rows.put(value);rowBytes+=added;
        }
        boolean structure=PhoneTreeQuery.completeSubtree(observation,container,false),clipped=!PhoneTreeQuery.completeSubtree(observation,container,true);
        JSONObject answer=extracted(observation,container,nodes,rows,rows.length(),offset,candidates.size(),structure,clipped,rowSelector,budget,includeObservation);
        if(!includeObservation||bytes(answer)<=RESULT_BYTES)return answer;
        // Keep whole returned rows and every delivered source ref; never drop only their lookup nodes.
        JSONObject best=null;int low=0,high=rows.length()-1;
        while(low<=high){int count=(low+high)/2;answer=extracted(observation,container,nodes,rows,count,offset,candidates.size(),structure,clipped,rowSelector,true,true);
            if(bytes(answer)<=RESULT_BYTES){best=answer;low=count+1;}else high=count-1;
        }
        return best==null?unavailable("extract_observation_output_budget").put("outputBudgetReached",true).put("offset",offset)
                .put("nextOffset",JSONObject.NULL).put("continuation","narrow_fields_or_row_selector"):best;
    }
    private static JSONObject extracted(JSONObject observation,JSONObject container,Map<String,JSONObject> nodes,JSONArray available,int count,int offset,int total,
                                         boolean structure,boolean clipped,JSONObject rowSelector,boolean budget,boolean includeObservation)throws Exception {
        JSONArray rows=new JSONArray();int fieldIssues=0;Set<String> selected=new LinkedHashSet<>();selected.add(container.getString("id"));
        for(int i=0;i<count;i++){
            JSONObject row=available.getJSONObject(i);rows.put(row);selected.add(row.getJSONObject("rowRef").getString("nodeId"));JSONObject fields=row.getJSONObject("fields");
            for(Iterator<String> names=fields.keys();names.hasNext();){JSONObject cell=fields.getJSONObject(names.next());if(!"value".equals(cell.optString("status")))fieldIssues++;
                JSONArray refs=cell.optJSONArray("sourceRefs");for(int j=0;refs!=null&&j<refs.length();j++)selected.add(refs.getJSONObject(j).getString("nodeId"));}
        }
        boolean more=offset+count<total;Object next=more&&count>0?offset+count:JSONObject.NULL;
        JSONObject out=base().put("ok",true).put("status","extracted").put("observationId",observation.getString("observationId")).put("containerRef",ref(container)).put("rows",rows)
                .put("totalVisibleRows",total).put("rowCountComplete",structure&&(!clipped||rowSelector==null||!rowSelector.has("text")))
                .put("returnedRows",count).put("fieldIssues",fieldIssues).put("truncated",count<total).put("outputBudgetReached",budget)
                .put("offset",offset).put("nextOffset",next).put("hasMore",more).put("continuation",more?(count>0?"same_observation_next_offset":"narrow_fields_or_row_selector"):"snapshot_rows_exhausted")
                .put("rowDataBudgetBytes",OUTPUT_BYTES)
                .put("partial",!structure||clipped||fieldIssues>0||count<total).put("coverage",new JSONObject().put("scope","visible_accessibility_container_only").put("structuralComplete",structure).put("textMayBeClipped",clipped).put("wholeListComplete",false));
        if(includeObservation){
            Set<String> retained=new LinkedHashSet<>();
            for(String id:selected){JSONObject at=nodes.get(id);if(at==null)throw new IllegalStateException("extracted_native_source_missing");
                while(at!=null&&retained.add(at.getString("id")))at=nodes.get(at.optString("parentId"));}
            out.put("outputBudgetBytes",RESULT_BYTES).put("observation",PhoneTreeQuery.project(observation,nodes,retained,selected,"",512,"extracted_sources_and_ancestors"));
        }
        return out;
    }
    private static int bytes(Object value){return String.valueOf(value).getBytes(StandardCharsets.UTF_8).length;}
    private static JSONObject field(List<JSONObject> found,JSONObject spec,boolean complete)throws Exception {
        JSONObject out=new JSONObject().put("matched",found.size()).put("selectionComplete",complete);boolean multiple=spec.optBoolean("multiple");
        if(found.isEmpty())return out.put("status",complete?"missing":"unavailable").put("reason",complete?"no_matching_field":"row_scope_incomplete").put("value",JSONObject.NULL);
        if(!multiple&&found.size()>1)return out.put("status","ambiguous").put("value",JSONObject.NULL);
        JSONArray values=new JSONArray(),refs=new JSONArray();boolean masked=false,clipped=false;int count=multiple?Math.min(found.size(),8):1;
        for(int i=0;i<count;i++){
            JSONObject n=found.get(i);if(n.optBoolean("password")){masked=true;continue;}String attribute=spec.optString("attribute","label");Object value;
            if(attribute.equals("checked")||attribute.equals("selected"))value=n.optBoolean(attribute);
            else if(attribute.equals("label")){String t=n.optString("text"),d=n.optString("description");value=t.isEmpty()?d:d.isEmpty()||d.equals(t)?t:t+"\n"+d;}
            else value=n.optString(attribute);
            if(value instanceof String text){
                JSONObject lengths=n.optJSONObject("textLengths");
                if(attribute.equals("label"))clipped|=lengths!=null&&(lengths.optInt("text")>n.optString("text").length()||lengths.optInt("description")>n.optString("description").length());
                else clipped|=lengths!=null&&lengths.optInt(attribute)>text.length();
                if(text.length()>4000){int end=4000;if(Character.isHighSurrogate(text.charAt(end-1))&&Character.isLowSurrogate(text.charAt(end)))end--;value=text.substring(0,end);clipped=true;}
            }
            values.put(value);refs.put(ref(n));
        }
        return out.put("status",masked?"masked":!complete||clipped||found.size()>count?"partial":"value").put("textMayBeClipped",clipped)
                .put("value",multiple?values:values.length()==0?JSONObject.NULL:values.get(0)).put("sourceRefs",refs);
    }
    static Map<String,JSONObject> index(JSONObject observation)throws Exception {
        if(observation.optInt("treeSchemaVersion")!=1||observation.optJSONArray("nodes")==null)throw new IllegalArgumentException("需要完整父子/窗口/path森林，不猜测扁平列表行归属");
        Map<String,JSONObject> nodes=new LinkedHashMap<>();JSONArray raw=observation.getJSONArray("nodes");if(raw.length()>AccessibilityForest.MAX_NODES)throw new IllegalArgumentException("树超出原生上限");
        for(int i=0;i<raw.length();i++){JSONObject n=raw.getJSONObject(i);String id=text(n,"id",100);if(nodes.put(id,n)!=null||n.optJSONArray("path")==null||n.optInt("windowId",-1)<0)throw new IllegalArgumentException("树身份不完整");}
        Set<String> locations=new HashSet<>();
        for(JSONObject n:nodes.values()){
            JSONArray path=n.getJSONArray("path");if(path.length()>AccessibilityForest.MAX_DEPTH||!locations.add(n.getInt("windowId")+":"+path))throw new IllegalArgumentException("树path无效或重复");
            for(int i=0;i<path.length();i++)integer(new JSONObject().put("index",path.get(i)),"index",0,0,Integer.MAX_VALUE);
            JSONArray children=n.getJSONArray("children");Set<String> childIds=new HashSet<>();
            for(int i=0;i<children.length();i++){String child=children.getString(i);JSONObject value=nodes.get(child);if(value==null||!childIds.add(child)||!n.getString("id").equals(value.optString("parentId")))throw new IllegalArgumentException("树children不完整");}
            String parentId=n.optString("parentId");if(!parentId.isEmpty()&&!parentId.equals("null")){JSONObject p=nodes.get(parentId);if(p==null||!contains(p.getJSONArray("children"),n.getString("id"))||path.length()!=p.getJSONArray("path").length()+1)throw new IllegalArgumentException("树父子/path不一致");for(int i=0;i<p.getJSONArray("path").length();i++)if(path.getInt(i)!=p.getJSONArray("path").getInt(i))throw new IllegalArgumentException("树path不在父域");}
            Set<String> seen=new HashSet<>();JSONObject at=n;while(at!=null){if(!seen.add(at.getString("id")))throw new IllegalArgumentException("树父子成环");String parent=at.optString("parentId");if(parent.isEmpty()||parent.equals("null"))break;JSONObject next=nodes.get(parent);if(next==null||next.getInt("windowId")!=n.getInt("windowId"))throw new IllegalArgumentException("树父域不完整");at=next;}
        }
        return nodes;
    }
    private static boolean contains(JSONArray values,String value)throws Exception {for(int i=0;i<values.length();i++)if(value.equals(values.getString(i)))return true;return false;}
    static boolean descendant(JSONObject node,String id,Map<String,JSONObject> nodes){Set<String> seen=new HashSet<>();for(JSONObject at=node;at!=null&&seen.add(at.optString("id"));at=nodes.get(at.optString("parentId")))if(id.equals(at.optString("id")))return true;return false;}
    static boolean matches(JSONObject node,JSONObject sel)throws Exception {
        if(sel.has("resourceId")&&!sel.getString("resourceId").equals(node.optString("resourceId")))return false;
        if(sel.has("role")&&!sel.getString("role").equals(node.optString("role")))return false;
        return !sel.has("text")||(node.optString("text")+"\n"+node.optString("description")+"\n"+node.optString("hint")+"\n"+node.optString("labelledBy")).toLowerCase(Locale.ROOT).contains(sel.getString("text").toLowerCase(Locale.ROOT));
    }
    private static boolean hasVisibleContent(JSONObject row,Map<String,JSONObject> nodes){for(JSONObject n:nodes.values())if(!n.optBoolean("password")&&n.optBoolean("visible",true)&&descendant(n,row.optString("id"),nodes))return true;return false;}
    static JSONObject ref(JSONObject n)throws Exception{return new JSONObject().put("nodeId",n.getString("id")).put("windowId",n.getInt("windowId")).put("path",new JSONArray(n.getJSONArray("path").toString())).put("identityFingerprint",n.optString("identityFingerprint")).put("structuralFingerprint",n.optString("structuralFingerprint"));}
    static JSONObject base()throws Exception{return new JSONObject().put("sideEffecting",false).put("untrustedScreenContent",true).put("verifiedBusinessSuccess",false).put("automaticReplayAllowed",false);}
    static JSONObject unavailable(String reason)throws Exception{return base().put("ok",false).put("status","unavailable").put("reason",reason).put("error",reason);}
    static boolean complete(JSONObject o){if(o.optBoolean("truncated")||o.optInt("treeSchemaVersion")!=1)return false;JSONObject sources=o.optJSONObject("observationSources");if(sources==null||!sources.optBoolean("treeAvailable"))return false;JSONArray windows=o.optJSONArray("windows");if(windows==null||windows.length()==0)return false;for(int i=0;i<windows.length();i++){JSONObject w=windows.optJSONObject(i);if(w==null||!w.optBoolean("rootAvailable")||w.optBoolean("truncated"))return false;}return true;}
    /** Only the current scroll container and its real ancestors are actionable in the returned projection. */
    public static JSONObject actionObservation(JSONObject observation,String containerId)throws Exception {
        Map<String,JSONObject> all=index(observation);Set<String> kept=new LinkedHashSet<>();JSONObject at=all.get(containerId);
        if(at==null)throw new IllegalArgumentException("原动作节点不在当前观察中");
        while(at!=null&&kept.add(at.getString("id")))at=all.get(at.optString("parentId"));
        JSONObject out=PhoneTreeQuery.project(observation,all,kept,Set.of(containerId),"",512,"action_target_and_ancestors");
        if(out.toString().getBytes(StandardCharsets.UTF_8).length>131072)throw new IllegalArgumentException("原目标与祖先投影超过输出预算");
        return out;
    }
}
