package dev.pocketengine.phone.observation;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** One bounded in-memory read cursor of the original phone adapter, not an executor or durable journal. */
public final class PhoneTreeCollection {
    public static final String TOOL="phone_collect";
    public static final int MAX_PAGES=30, MAX_ROWS=1000, MAX_BYTES=256*1024;
    public static final long TTL_MS=600_000;
    private PhoneTreeCollection() { }
    public static void validate(JSONObject args)throws Exception {
        String action=PhoneTreeExtract.text(args,"action",10);
        if(action.equals("start")){
            PhoneTreeExtract.keys(args,"action","observationId","containerId","rowSelector","fields","maxPages","maxRows","direction","stopSelector");
            JSONObject spec=new JSONObject(args.toString());for(String key:new String[]{"action","maxPages","maxRows","direction","stopSelector"})spec.remove(key);spec.put("limit",PhoneTreeExtract.MAX_ROWS);PhoneTreeExtract.validate(spec);
            PhoneTreeExtract.integer(args,"maxPages",6,1,MAX_PAGES);PhoneTreeExtract.integer(args,"maxRows",200,1,MAX_ROWS);
            if(!Set.of("forward","backward").contains(PhoneTreeExtract.text(args,"direction",10)))throw new IllegalArgumentException("需要明确滚动方向");
            if(args.has("stopSelector")){PhoneTreeExtract.selector(args.getJSONObject("stopSelector"));if(args.getJSONObject("stopSelector").length()==0)throw new IllegalArgumentException("停止条件不能为空");}
        }else if(Set.of("advance","read","finish").contains(action)){
            PhoneTreeExtract.keys(args,"action","cursorId","afterOperationId","offset","limit");PhoneTreeExtract.text(args,"cursorId",100);
            if(action.equals("advance"))PhoneTreeExtract.text(args,"afterOperationId",100);else if(args.has("afterOperationId"))throw new IllegalArgumentException("此命令不接受动作回执");
            if(!action.equals("read")&&(args.has("offset")||args.has("limit")))throw new IllegalArgumentException("分页参数仅用于read");
            PhoneTreeExtract.integer(args,"offset",0,0,MAX_ROWS);PhoneTreeExtract.integer(args,"limit",80,1,100);
        }else throw new IllegalArgumentException("未知采集命令");
    }
    public static final class State {
        public final String id=UUID.randomUUID().toString();
        private final JSONObject spec,anchor,stopSelector;
        private final String scope,direction,packageName;
        private final long created;
        private final int maxPages,maxRows;
        private JSONArray rows=new JSONArray(),lastKeys=new JSONArray(),sources=new JSONArray(),overlaps=new JSONArray();
        private JSONObject latest,unmerged,lastScroll;
        private final Set<String> operations=new HashSet<>();
        private String stopped="";
        private int pages;
        private boolean possibleGap;
        private State(JSONObject args,JSONObject observation,String scope,long now)throws Exception {
            this.scope=scope;created=now;direction=args.getString("direction");maxPages=args.optInt("maxPages",6);maxRows=args.optInt("maxRows",200);packageName=observation.optString("packageName");
            spec=new JSONObject(args.toString());for(String key:new String[]{"action","maxPages","maxRows","direction","stopSelector"})spec.remove(key);spec.put("limit",PhoneTreeExtract.MAX_ROWS);
            stopSelector=args.optJSONObject("stopSelector")==null?null:new JSONObject(args.getJSONObject("stopSelector").toString());
            JSONObject container=PhoneTreeExtract.index(observation).get(args.getString("containerId"));if(container==null)throw new IllegalArgumentException("采集容器不存在");anchor=anchor(container);
            accept(observation,null);
        }
        public JSONObject expectedScroll()throws Exception {
            return new JSONObject().put("observationId",latest.getString("observationId")).put("nodeId",spec.getString("containerId")).put("direction",direction);
        }
        public String observationId(){return latest==null?"":latest.optString("observationId");}
        public boolean stopped(){return !stopped.isEmpty();}
        public JSONObject scopeCheck(String nativeScope,long now)throws Exception {
            if(!scope.equals(nativeScope))return stop("scope_or_pause_changed");if(now<created||now-created>TTL_MS)return stop("cursor_expired");return null;
        }
        public JSONObject readStored(String nativeScope,long now,int offset,int limit)throws Exception {
            if(!scope.equals(nativeScope))return PhoneTreeExtract.unavailable("collection_scope_changed");
            if(now<created||now-created>TTL_MS)stopped="cursor_expired";
            JSONObject out=result(offset,limit);out.remove("observation");out.remove("nextScroll");out.remove("nextScrollRef");
            return out.put("historicalEvidenceOnly",true);
        }
        public JSONObject stop(String reason)throws Exception {if(stopped.isEmpty())stopped=reason;return result();}
        public JSONObject stopAfterScroll(String reason,JSONObject originalRecord)throws Exception {rememberScroll(originalRecord);return stop(reason);}
        private void rememberScroll(JSONObject record)throws Exception {if(record==null)return;String operation=record.optString("operationId");if(!operation.isEmpty())operations.add(operation);JSONObject receipt=record.optJSONObject("receipt");lastScroll=new JSONObject().put("operationId",operation).put("journalState",record.optString("state")).put("accepted",receipt==null?JSONObject.NULL:receipt.opt("accepted")).put("effect",receipt==null?"unknown":receipt.optString("effect","unknown")).put("notBusinessProof",true);}
        /** This record must come from the original TaskStore operations projection, not tool arguments. */
        public void requireScroll(JSONObject op)throws Exception {
            if(stopped())throw new IllegalStateException("采集已停止");
            if(op==null||!NativeTreeScrollPort.TOOL.equals(op.optString("tool"))||!"receipt".equals(op.optString("state"))||!op.has("receipt"))throw new SecurityException("缺少原滚动确定回执");
            String operation=op.getString("operationId");if(operations.contains(operation))throw new SecurityException("原滚动回执已用于此采集");
            JSONObject expected=expectedScroll(),actual=op.getJSONObject("arguments");if(!same(expected,actual))throw new SecurityException("滚动不是本游标的当前容器/方向/观察");
            JSONObject receipt=op.getJSONObject("receipt");if(!Boolean.TRUE.equals(receipt.opt("accepted"))||!receipt.optString("operationId").equals(operation))throw new SecurityException("滚动没有确定原生接收回执");
            JSONObject target=receipt.optJSONObject("targetRef");if(target==null||!target.getJSONArray("path").toString().equals(anchor.getJSONArray("path").toString())||target.getInt("windowId")!=anchor.getInt("windowId"))throw new SecurityException("滚动原生目标不属于固定窗口/path");
        }
        public JSONObject advance(JSONObject observation,JSONObject op)throws Exception {
            requireScroll(op);String operation=op.getString("operationId");JSONObject receipt=op.getJSONObject("receipt");
            rememberScroll(op);if(!observation.optString("observationId").equals(receipt.optString("afterObservationId")))return stop("scroll_fresh_observation_unavailable");
            accept(observation,operation);return result();
        }
        /** Renew only read references, never silently add a page or re-run the preceding scroll. */
        public JSONObject refresh(JSONObject observation)throws Exception {
            if(stopped())return result();JSONObject extracted=extractPage(observation);if(extracted==null)return result();
            JSONArray keys=rowKeys(extracted.getJSONArray("rows"));if(!keys.toString().equals(lastKeys.toString())){unmerged=pageEvidence(extracted,observation);return stop("scene_changed_without_admitted_scroll");}
            latest=new JSONObject(observation.toString());return result();
        }
        private JSONObject extractPage(JSONObject observation)throws Exception {
            if(!packageName.equals(observation.optString("packageName"))){stopped="app_changed";return null;}
            JSONObject current=null;for(JSONObject node:PhoneTreeExtract.index(observation).values())if(same(anchor,anchor(node))){if(current!=null){stopped="ambiguous_container";return null;}current=node;}
            if(current==null){stopped="container_changed";return null;}
            spec.put("observationId",observation.getString("observationId")).put("containerId",current.getString("id"));return PhoneTreeExtract.extractRows(observation,spec);
        }
        private void accept(JSONObject observation,String operation)throws Exception {
            JSONObject extracted=extractPage(observation);if(extracted==null)return;
            if(!extracted.optBoolean("ok")){stopped=extracted.optString("reason","extraction_unavailable");return;}
            JSONArray page=extracted.getJSONArray("rows"),keys=rowKeys(page);int overlap=0;pages++;
            if(latest!=null){
                if(keys.toString().equals(lastKeys.toString())){unmerged=pageEvidence(extracted,observation);stopped=observation.optString("treeFingerprint").equals(latest.optString("treeFingerprint"))?"no_progress_observed":"ambiguous_identical_viewport";latest=new JSONObject(observation.toString());return;}
                int bound=Math.min(lastKeys.length(),keys.length());for(int n=bound;n>=1;n--)if(direction.equals("forward")?segment(lastKeys,lastKeys.length()-n,n).equals(segment(keys,0,n)):segment(keys,keys.length()-n,n).equals(segment(lastKeys,0,n))){overlap=n;break;}
                if(overlap>0){
                    JSONArray sequence=direction.equals("forward")?segmentArray(keys,0,overlap):segmentArray(keys,keys.length()-overlap,overlap);Set<String> distinct=new HashSet<>();for(int i=0;i<sequence.length();i++)distinct.add(sequence.getString(i));int alignments=0;
                    for(int i=0;i+overlap<=lastKeys.length();i++)if(segment(lastKeys,i,overlap).equals(sequence.toString()))alignments++;
                    if(overlap<2||distinct.size()<2||alignments!=1){unmerged=pageEvidence(extracted,observation);stopped="ambiguous_overlap";latest=new JSONObject(observation.toString());return;}
                }else{
                    Set<String> previous=new HashSet<>();for(int i=0;i<lastKeys.length();i++)previous.add(lastKeys.getString(i));for(int i=0;i<keys.length();i++)if(previous.contains(keys.getString(i))){unmerged=pageEvidence(extracted,observation);stopped="order_changed_or_unproven_overlap";latest=new JSONObject(observation.toString());return;}possibleGap=true;
                }
                overlaps.put(new JSONObject().put("operationId",operation).put("previousObservationId",latest.getString("observationId")).put("observationId",observation.getString("observationId")).put("orderedRowsOverlapped",overlap).put("basis","ordered_observed_field_values_not_stable_item_identity"));
            }
            JSONArray additions=new JSONArray();int start=direction.equals("forward")?overlap:0,end=direction.equals("forward")?page.length():page.length()-overlap;
            for(int i=start;i<end;i++){
                JSONObject value=new JSONObject(page.getJSONObject(i).toString()).put("sourceObservationId",observation.getString("observationId")).put("sourcePage",pages).put("sourceRefsAreHistoricalEvidence",true);
                if(rows.length()+additions.length()>=maxRows){stopped="row_limit";break;}additions.put(value);
                if((rows.toString()+additions.toString()).getBytes(StandardCharsets.UTF_8).length>MAX_BYTES){additions.remove(additions.length()-1);stopped="output_budget";break;}
            }
            JSONArray combined=new JSONArray();if(direction.equals("backward"))copy(additions,combined);copy(rows,combined);if(direction.equals("forward"))copy(additions,combined);rows=combined;
            latest=new JSONObject(observation.toString());lastKeys=keys;sources.put(new JSONObject().put("observationId",observation.getString("observationId")).put("treeFingerprint",observation.optString("treeFingerprint")).put("containerRef",extracted.getJSONObject("containerRef")).put("scrollOperationId",operation==null?JSONObject.NULL:operation).put("visibleRows",page.length()).put("fieldIssues",extracted.optInt("fieldIssues")).put("coverage",extracted.getJSONObject("coverage")));
            if(stopped.isEmpty()&&(!extracted.getJSONObject("coverage").optBoolean("structuralComplete")||extracted.optBoolean("truncated")))stopped="partial_accessibility_page";
            if(stopped.isEmpty()&&stopSelector!=null){Map<String,JSONObject> nodes=PhoneTreeExtract.index(observation);for(JSONObject n:nodes.values())if(n.optBoolean("visible",true)&&!n.optBoolean("password")&&PhoneTreeExtract.descendant(n,spec.getString("containerId"),nodes)&&PhoneTreeExtract.matches(n,stopSelector)){stopped="explicit_boundary_observed";break;}}
            if(stopped.isEmpty()&&page.length()==0)stopped="no_visible_rows";
            if(stopped.isEmpty()&&pages>=maxPages)stopped="page_limit";
            if(stopped.isEmpty()&&rows.length()>=maxRows)stopped="row_limit";
        }
        private static JSONObject pageEvidence(JSONObject extracted,JSONObject observation)throws Exception {
            JSONArray subset=new JSONArray(),all=extracted.getJSONArray("rows");for(int i=0;i<all.length();i++){subset.put(all.get(i));if(subset.toString().getBytes(StandardCharsets.UTF_8).length>4096){subset.remove(subset.length()-1);break;}}
            return new JSONObject().put("observationId",observation.getString("observationId")).put("rows",subset).put("visibleRows",all.length()).put("evidenceRowsOmitted",all.length()-subset.length()).put("notMergedOrDeduplicated",true);
        }
        public JSONObject result()throws Exception {return result(0,80);}
        public JSONObject result(int offset,int limit)throws Exception {
            if(offset<0||offset>rows.length()||limit<1||limit>100)return PhoneTreeExtract.unavailable("collection_range_unavailable");
            JSONArray visible=new JSONArray();int next=offset;
            // A retained row is bounded by extraction; its added provenance must not stall pagination.
            while(next<rows.length()&&visible.length()<limit){
                visible.put(rows.get(next));int bytes=visible.toString().getBytes(StandardCharsets.UTF_8).length;
                if(bytes>48*1024&&visible.length()>1){visible.remove(visible.length()-1);break;}next++;
                if(bytes>48*1024)break;
            }
            JSONObject result=PhoneTreeExtract.base().put("ok",true).put("status",stopped()?"stopped":"needs_scroll").put("cursorId",id).put("restartPersistence","none_cursor_expires_on_process_or_run_restart")
                    .put("stoppedBecause",stopped.isEmpty()?JSONObject.NULL:stopped).put("partial",true).put("wholeListComplete",false).put("rows",visible).put("rowCount",rows.length()).put("returnedRows",visible.length()).put("offset",offset).put("nextOffset",next<rows.length()?next:JSONObject.NULL).put("pagesInspected",pages).put("scrollOperations",operations.size())
                    .put("sources",new JSONArray(sources.toString())).put("overlapEvidence",new JSONArray(overlaps.toString())).put("possibleSkippedRows",possibleGap).put("expiresAfterMs",TTL_MS).put("maxPages",maxPages).put("maxRows",maxRows);
            if(unmerged!=null)result.put("unmergedViewport",new JSONObject(unmerged.toString()));if(lastScroll!=null)result.put("lastScrollReceipt",new JSONObject(lastScroll.toString()));
            if(!stopped()&&latest!=null)result.put("nextScroll",expectedScroll()).put("nextScrollRef",PhoneTreeExtract.ref(PhoneTreeExtract.index(latest).get(spec.getString("containerId"))));
            if(!stopped()&&latest!=null)result.put("observation",PhoneTreeExtract.actionObservation(latest,spec.getString("containerId")));
            if(result.toString().getBytes(StandardCharsets.UTF_8).length>96*1024){result.remove("observation");result.remove("nextScroll");result.remove("nextScrollRef");stopped="result_budget";result.put("status","stopped").put("stoppedBecause",stopped);}return result;
        }
    }
    public static State start(JSONObject args,JSONObject observation,String nativeScope,long now)throws Exception {validate(args);if(!"start".equals(args.optString("action")))throw new IllegalArgumentException("需要start");if(!args.getString("observationId").equals(observation.optString("observationId")))throw new SecurityException("旧观察不能启动采集");return new State(args,observation,nativeScope,now);}
    private static JSONObject anchor(JSONObject node)throws Exception {JSONObject out=new JSONObject();for(String key:new String[]{"windowId","path","resourceId","className","role","bounds"})out.put(key,node.get(key));return new JSONObject(out.toString());}
    private static boolean same(JSONObject a,JSONObject b)throws Exception {return dev.pocketengine.store.TaskStore.capabilityFingerprint(a).equals(dev.pocketengine.store.TaskStore.capabilityFingerprint(b));}
    private static JSONArray rowKeys(JSONArray rows)throws Exception {JSONArray out=new JSONArray();for(int i=0;i<rows.length();i++){JSONObject fields=rows.getJSONObject(i).getJSONObject("fields"),values=new JSONObject();for(Iterator<String> it=fields.keys();it.hasNext();){String key=it.next();JSONObject f=fields.getJSONObject(key);values.put(key,new JSONObject().put("status",f.get("status")).put("value",f.get("value")));}out.put(dev.pocketengine.store.TaskStore.capabilityFingerprint(values));}return out;}
    private static String segment(JSONArray list,int start,int count)throws Exception{return segmentArray(list,start,count).toString();}
    private static JSONArray segmentArray(JSONArray list,int start,int count)throws Exception {JSONArray out=new JSONArray();for(int i=start;i<start+count;i++)out.put(list.get(i));return out;}
    private static void copy(JSONArray from,JSONArray to)throws Exception {for(int i=0;i<from.length();i++)to.put(from.get(i));}
}
