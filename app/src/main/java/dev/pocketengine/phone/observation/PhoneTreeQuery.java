package dev.pocketengine.phone.observation;

import dev.pocketengine.core.RunControl;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Pure tree selection and bounded read-only condition evaluation; never invokes a physical action. */
public final class PhoneTreeQuery {
    public static final String QUERY="phone_query", WAIT="phone_condition_wait";
    public static final int MAX_WAIT_MS=60_000, MAX_STABLE_MS=30_000;
    private static final int MAX_READS=121, QUERY_BYTES=80*1024, MODEL_TREE_BYTES=96*1024;
    private PhoneTreeQuery() { }
    public interface Reader { JSONObject read() throws Exception; }
    public static boolean supports(String name){return PhoneTreeSequence.TOOL.equals(name)||QUERY.equals(name)||WAIT.equals(name)||PhoneTreeExtract.TOOL.equals(name)||PhoneTreeCollection.TOOL.equals(name)||NativeTreeScrollPort.TOOL.equals(name);}
    public static void validate(String tool,JSONObject args)throws Exception {
        if(args==null)throw new IllegalArgumentException("需要结构化查询参数");
        if(PhoneTreeSequence.TOOL.equals(tool)){PhoneTreeSequence.validate(args);return;}
        if(PhoneTreeExtract.TOOL.equals(tool)){PhoneTreeExtract.validate(args);return;}
        if(PhoneTreeCollection.TOOL.equals(tool)){PhoneTreeCollection.validate(args);return;}
        if(NativeTreeScrollPort.TOOL.equals(tool)){NativeTreeScrollPort.validate(args);return;}
        Set<String> allowed=new HashSet<>();allowed.add("selector");allowed.add("observationId");
        if(QUERY.equals(tool)){allowed.add("limit");text(args,"observationId",100,true);if(args.has("limit"))integer(args,"limit",1,40);}
        else if(WAIT.equals(tool)){
            allowed.add("kind");allowed.add("timeoutMs");allowed.add("stableForMs");
            if(!text(args,"kind",20,true).matches("present|absent|stable"))throw new IllegalArgumentException("未知条件");
            int timeout=integer(args,"timeoutMs",100,MAX_WAIT_MS);
            if(args.has("stableForMs")&&(!"stable".equals(args.optString("kind"))||integer(args,"stableForMs",250,MAX_STABLE_MS)>timeout))throw new IllegalArgumentException("稳定时间必须在等待预算内");
            if(args.has("observationId"))text(args,"observationId",100,true);
        }else throw new IllegalArgumentException("未知树工具");
        for(java.util.Iterator<String> keys=args.keys();keys.hasNext();)if(!allowed.contains(keys.next()))throw new IllegalArgumentException("不支持的查询字段");
        if(!(args.opt("selector") instanceof JSONObject))throw new IllegalArgumentException("需要selector对象");
        JSONObject selector=args.getJSONObject("selector");
        for(java.util.Iterator<String> keys=selector.keys();keys.hasNext();) { String key=keys.next();switch(key){
            case "windowId":integer(selector,key,0,Integer.MAX_VALUE);break;
            case "parentId":text(selector,key,100,true);text(args,"observationId",100,true);break;
            case "text":case "resourceId":case "role":text(selector,key,400,true);break;
            default:throw new IllegalArgumentException("selector只能缩小当前显示范围");
        }}
    }
    public static JSONObject query(JSONObject observation,JSONObject args)throws Exception {
        validate(QUERY,args);
        if(!args.getString("observationId").equals(observation.optString("observationId")))return unavailable("stale_observation",observation);
        return select(observation,args.getJSONObject("selector"),args.optInt("limit",20));
    }
    private static JSONObject select(JSONObject observation,JSONObject selector,int limit)throws Exception {
        JSONArray raw=observation.optJSONArray("nodes");Map<String,JSONObject> nodes=index(raw);
        String parent=selector.optString("parentId");
        if(!parent.isEmpty()&&!nodes.containsKey(parent))return unavailable("parent_unavailable",observation);
        if(!parent.isEmpty()&&selector.has("windowId")&&nodes.get(parent).optInt("windowId",-1)!=selector.getInt("windowId"))return unavailable("parent_outside_window",observation);
        List<JSONObject> matched=new ArrayList<>();
        for(JSONObject node:nodes.values()){
            if(node.optBoolean("password")||!node.optBoolean("visible",true))continue;
            if(selector.has("windowId")&&node.optInt("windowId",-1)!=selector.getInt("windowId"))continue;
            if(!parent.isEmpty()&&!descendant(node,parent,nodes))continue;
            if(selector.has("text")&&!contains(node.optString("text")+"\n"+node.optString("description")+"\n"+node.optString("hint")+"\n"+node.optString("labelledBy"),selector.getString("text")))continue;
            if(selector.has("resourceId")&&!selector.getString("resourceId").equals(node.optString("resourceId")))continue;
            if(selector.has("role")&&!selector.getString("role").equals(node.optString("role")))continue;
            matched.add(node);
        }
        String fingerprint=selectionFingerprint(nodes,matched);boolean partial=!completeForSelector(observation,selector);
        int requested=Math.min(limit,matched.size());
        JSONObject answer=selection(observation,selector,nodes,matched,requested,fingerprint,partial).put("outputBudgetReached",false);
        if(bytes(answer)<=QUERY_BYTES)return answer;
        // Bounded search avoids rebuilding a large native snapshot once for every dropped match.
        JSONObject best=null;int low=0,high=requested-1;
        while(low<=high){int returned=(low+high)/2;answer=selection(observation,selector,nodes,matched,returned,fingerprint,partial).put("outputBudgetReached",true);
            if(bytes(answer)<=QUERY_BYTES){best=answer;low=returned+1;}else high=returned-1;
        }
        return best==null?unavailable("query_output_budget",observation):best;
    }
    private static JSONObject selection(JSONObject observation,JSONObject selector,Map<String,JSONObject> nodes,List<JSONObject> matched,int count,String fingerprint,boolean partial)throws Exception {
        JSONArray matches=new JSONArray();Set<String> retained=new LinkedHashSet<>(),selected=new LinkedHashSet<>();String needle=selector.optString("text");
        for(int i=0;i<count;i++){
            JSONObject node=matched.get(i);JSONArray parents=new JSONArray();JSONObject ancestor=node;String click=null,edit=null;
            selected.add(node.getString("id"));
            Set<String> seen=new HashSet<>();
            while(ancestor!=null&&seen.add(ancestor.optString("id"))){
                retained.add(ancestor.getString("id"));
                if(ancestor!=node)parents.put(ancestor.getString("id"));
                if(ancestor.optBoolean("enabled",true)&&ancestor.optBoolean("visible",true)){
                    if(click==null&&ancestor.optBoolean("clickable")&&!ancestor.optBoolean("password"))click=ancestor.getString("id");
                    if(edit==null&&ancestor.optBoolean("editable")&&!ancestor.optBoolean("password"))edit=ancestor.getString("id");
                }
                ancestor=nodes.get(ancestor.optString("parentId"));
            }
            matches.put(compactNode(node,2000,needle).put("parents",parents).put("actionNodeId",click==null?JSONObject.NULL:click).put("editNodeId",edit==null?JSONObject.NULL:edit));
        }
        JSONObject projected=projection(observation,nodes,retained,selected,needle);
        return new JSONObject().put("ok",true).put("status","read").put("observationId",observation.optString("observationId"))
                .put("selectionFingerprint",fingerprint).put("matches",matches).put("total",matched.size()).put("truncated",matched.size()>count).put("partial",partial)
                .put("returned",count).put("outputBudgetBytes",QUERY_BYTES).put("selectionComplete",!partial)
                .put("coverage",partial?"partial_accessibility_forest":"accessible_forest_only").put("observation",projected)
                .put("verifiedBusinessSuccess",false).put("sideEffecting",false).put("automaticReplayAllowed",false);
    }
    /** Every read is fenced by the adapter. No checkpoint()/resume wait occurs under a port monitor. */
    public static JSONObject await(JSONObject args,JSONObject anchor,Reader reader,RunControl control)throws Exception {
        validate(WAIT,args);JSONObject selector=new JSONObject(args.getJSONObject("selector").toString());
        JSONObject parentAnchor=null;
        if(selector.has("parentId")){
            if(anchor==null||!args.getString("observationId").equals(anchor.optString("observationId")))return unavailable("stale_observation",null);
            parentAnchor=index(anchor.optJSONArray("nodes")).get(selector.getString("parentId"));
            if(parentAnchor==null||!parentAnchor.has("path"))return unavailable("parent_unavailable",null);
        }
        long started=System.nanoTime(), deadline=started+args.getLong("timeoutMs")*1_000_000L, revision=control.pauseRevision();
        long stableSince=-1;String previous=null,session=null;int display=-1,reads=0;JSONObject latest=null,result=null;
        String kind=args.getString("kind");long stableFor=args.optLong("stableForMs",Math.max(250,Math.min(500,args.getLong("timeoutMs"))))*1_000_000L;
        while(reads<MAX_READS){
            control.check();if(control.isPaused()||control.pauseRevision()!=revision)return unavailable("paused_or_changed",null);
            if(System.nanoTime()>=deadline&&reads>0)break;
            latest=reader.read();reads++;
            control.check();if(control.isPaused()||control.pauseRevision()!=revision)return unavailable("paused_or_changed",null);
            if(System.nanoTime()>deadline)return waitResult("timed_out",null,reads,started,"read_deadline"); // Discard a late read, including its refs.
            if(latest==null||!latest.optBoolean("ok"))return waitResult("unavailable",latest,reads,started,"observation_unavailable");
            if(session==null){session=latest.optString("sessionId");display=latest.optInt("displayId",-1);}
            else if(!session.equals(latest.optString("sessionId"))||display!=latest.optInt("displayId",-1))return waitResult("unavailable",null,reads,started,"identity_changed");
            if(parentAnchor!=null){
                JSONObject currentParent=null;
                for(JSONObject node:index(latest.optJSONArray("nodes")).values())if(sameAnchor(parentAnchor,node)){if(currentParent!=null)return waitResult("unavailable",null,reads,started,"ambiguous_parent");currentParent=node;}
                if(currentParent==null)return waitResult("unavailable",null,reads,started,"parent_changed");selector.put("parentId",currentParent.getString("id"));
            }
            result=select(latest,selector,40);
            control.check();if(control.isPaused()||control.pauseRevision()!=revision)return unavailable("paused_or_changed",null);
            if(!result.optBoolean("ok"))return waitResult("unavailable",null,reads,started,result.optString("reason"));
            int count=result.optInt("total");boolean complete=completeForSelector(latest,selector,"stable".equals(kind)||selector.has("text"));
            boolean matched="present".equals(kind)&&count>0||"absent".equals(kind)&&complete&&count==0;
            if("stable".equals(kind)){
                boolean wholeScene=selector.length()==0;
                String fingerprint=wholeScene?latest.optString("treeFingerprint",String.valueOf(latest.optJSONArray("nodes"))+latest.optJSONArray("windows")):result.getString("selectionFingerprint");
                long now=System.nanoTime();
                if(!complete||!wholeScene&&count==0){previous=null;stableSince=-1;}
                else if(!fingerprint.equals(previous)){previous=fingerprint;stableSince=now;}
                else matched=stableSince>=0&&now-stableSince>=stableFor;
            }
            if(matched)return waitResult("matched",result,reads,started,"predicate_satisfied");
            long left=deadline-System.nanoTime();if(left<=0)break;
            // Longer waits reduce Binder load without slowing native cancellation/pause checks.
            long elapsed=System.nanoTime()-started,poll=elapsed<5_000_000_000L?250_000_000L:elapsed<20_000_000_000L?500_000_000L:1_000_000_000L;
            long wake=System.nanoTime()+poll;
            while(System.nanoTime()<wake&&System.nanoTime()<deadline){
                control.check();if(control.isPaused()||control.pauseRevision()!=revision)return unavailable("paused_or_changed",null);
                Thread.sleep(Math.max(1,Math.min(50,(Math.min(wake,deadline)-System.nanoTime())/1_000_000L)));
            }
        }
        return waitResult("timed_out",result,reads,started,reads>=MAX_READS?"observation_limit":"deadline");
    }
    private static String selectionFingerprint(Map<String,JSONObject> nodes,List<JSONObject> matches)throws Exception {
        java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
        Set<String> roots=new HashSet<>();for(JSONObject match:matches)roots.add(match.optString("id"));
        for(JSONObject node:nodes.values()){
            boolean included=false;Set<String> seen=new HashSet<>();
            for(JSONObject at=node;at!=null&&seen.add(at.optString("id"));at=nodes.get(at.optString("parentId")))if(roots.contains(at.optString("id"))){included=true;break;}
            if(!included)continue;
            // Original display/window/path and complete native identity survive unrelated node-ID shifts.
            JSONObject state=new JSONObject();
            for(String key:new String[]{"windowId","path","identityFingerprint","structuralFingerprint","resourceId","className","role","text","description","hint","labelledBy","enabled","visible","password","clickable","editable","checked","selected","focused","scrollable","scrollDirections","bounds","childrenComplete","subtreeComplete","subtreeTextComplete"})
                if(node.has(key))state.put(key,node.get(key));
            byte[] bytes=state.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            digest.update(Integer.toString(bytes.length).getBytes(java.nio.charset.StandardCharsets.US_ASCII));digest.update((byte)':');digest.update(bytes);
        }
        StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return hash.toString();
    }
    private static boolean sameAnchor(JSONObject a,JSONObject b)throws Exception {
        JSONArray path=a.optJSONArray("path");
        if(path==null||a.optInt("windowId",-1)!=b.optInt("windowId",-1)||!path.toString().equals(String.valueOf(b.optJSONArray("path"))))return false;
        // A loading container may change childCount/text while retaining the same native ancestry.
        if(a.has("structuralFingerprint")||b.has("structuralFingerprint"))return !a.optString("structuralFingerprint").isEmpty()&&a.optString("structuralFingerprint").equals(b.optString("structuralFingerprint"));
        for(String key:new String[]{"identityFingerprint","resourceId","className","role","text","description","hint","labelledBy","password","bounds"})if(!String.valueOf(a.opt(key)).equals(String.valueOf(b.opt(key))))return false;
        return true;
    }
    private static JSONObject waitResult(String status,JSONObject selection,int reads,long started,String reason)throws Exception {
        JSONObject out=selection==null?new JSONObject():new JSONObject(selection.toString());
        return out.put("ok",!"unavailable".equals(status)).put("status",status).put("matched","matched".equals(status))
                .put("reason",reason).put("observations",reads).put("elapsedMs",(System.nanoTime()-started)/1_000_000L)
                .put("sideEffecting",false).put("verifiedBusinessSuccess",false).put("automaticReplayAllowed",false);
    }
    private static JSONObject projection(JSONObject observation,Map<String,JSONObject> nodes,Set<String> retained,Set<String> selected,String needle)throws Exception {
        return project(observation,nodes,retained,selected,needle,2000,"selector_and_ancestors");
    }
    /** Call after retaining the full native observation; queries still search that original snapshot. */
    public static JSONObject modelObservation(JSONObject observation)throws Exception {
        Map<String,JSONObject> nodes=index(observation.optJSONArray("nodes"));
        if(nodes.isEmpty())return new JSONObject(observation.toString());
        Set<String> kept=new LinkedHashSet<>(),selected=new LinkedHashSet<>();Map<String,Integer> costs=new LinkedHashMap<>();
        int used=bytes(observation.optJSONArray("windows"))+4096;
        // Readable controls first, then content. Empty layout wrappers only survive as real ancestors.
        for(int priority=0;priority<3;priority++)for(JSONObject node:nodes.values()){
            boolean interactive=node.optBoolean("visible",true)&&node.optBoolean("enabled",true)
                    &&(node.optBoolean("clickable")||node.optBoolean("editable")||node.optBoolean("scrollable"));
            boolean content=node.optBoolean("visible",true)&&!node.optBoolean("password")&&hasText(node);
            boolean root=node.isNull("parentId")||node.optString("parentId").isEmpty();
            int actual=interactive?0:content?1:root?2:3;if(actual!=priority)continue;
            Set<String> chain=new LinkedHashSet<>();JSONObject at=node;int added=0;
            while(at!=null&&chain.add(at.optString("id"))){
                String id=at.optString("id");boolean own=at==node;
                int cost=bytes(compactNode(at,own||selected.contains(id)?2000:96,""));
                added+=cost-costs.getOrDefault(id,0);at=nodes.get(at.optString("parentId"));
            }
            if(used+added>MODEL_TREE_BYTES)continue;
            selected.add(node.getString("id"));used+=added;
            for(String id:chain){kept.add(id);costs.put(id,bytes(compactNode(nodes.get(id),selected.contains(id)?2000:96,"")));}
        }
        JSONObject out=project(observation,nodes,kept,selected,"",2000,"useful_nodes_and_ancestors");
        // Images and OCR keep their own existing budgets and authorization; only the tree is projected.
        for(String key:new String[]{"imageBase64","imageMimeType","imageWidth","imageHeight","ocr"})if(observation.has(key))out.put(key,observation.get(key));
        if(observation.optJSONObject("observationSources")!=null)out.put("observationSources",new JSONObject(observation.getJSONObject("observationSources").toString()));
        return out.put("modelTreeBudgetBytes",MODEL_TREE_BYTES);
    }
    private static boolean hasText(JSONObject node){for(String key:new String[]{"text","description","hint","labelledBy"})if(!node.optString(key).isBlank())return true;return false;}
    static JSONObject compactNode(JSONObject node,int maxChars,String needle)throws Exception {
        JSONObject out=new JSONObject(node.toString()),ranges=new JSONObject();boolean clipped=node.optBoolean("textTruncated");
        for(String key:new String[]{"text","description","hint","labelledBy"}){
            String value=node.optString(key);int start=0;
            if(!needle.isEmpty()&&value.length()>maxChars){int found=value.toLowerCase(java.util.Locale.ROOT).indexOf(needle.toLowerCase(java.util.Locale.ROOT));if(found>=0)start=Math.max(0,Math.min(value.length()-maxChars,found-Math.min(200,maxChars/4)));}
            int end=Math.min(value.length(),start+maxChars);
            // Do not split a surrogate pair at either presentation boundary.
            if(start>0&&start<value.length()&&Character.isLowSurrogate(value.charAt(start))&&Character.isHighSurrogate(value.charAt(start-1)))start--;
            if(end>start&&end<value.length()&&Character.isHighSurrogate(value.charAt(end-1))&&Character.isLowSurrogate(value.charAt(end)))end--;
            if(start!=0||end!=value.length()){
                clipped=true;out.put(key,value.substring(start,end));
                JSONObject lengths=node.optJSONObject("textLengths");ranges.put(key,new JSONObject().put("offset",start).put("returnedChars",end-start)
                        .put("availableChars",value.length()).put("sourceChars",lengths==null?value.length():lengths.optInt(key,value.length())));
            }
        }
        if(ranges.length()>0)out.put("textWindows",ranges);
        if(clipped)out.put("textTruncated",true).put("subtreeTextComplete",false);
        return out;
    }
    static JSONObject project(JSONObject observation,Map<String,JSONObject> nodes,Set<String> retained,Set<String> selected,String needle,int selectedTextChars,String kind)throws Exception {
        JSONObject out=new JSONObject(observation.toString());for(String key:new String[]{"imageBase64","imageMimeType","imageWidth","imageHeight","ocr"})out.remove(key);
        JSONObject sources=out.optJSONObject("observationSources");if(sources!=null)sources.put("imageIncluded",false).put("ocrTextIncluded",false).put("imageReason","tree_projection");
        JSONArray projected=new JSONArray();Map<String,JSONObject> copies=new LinkedHashMap<>();Set<Integer> omittedWindows=new HashSet<>(),clippedWindows=new HashSet<>();boolean textClipped=false;
        for(JSONObject node:nodes.values())if(!retained.contains(node.optString("id")))omittedWindows.add(node.optInt("windowId",-1));
        for(JSONObject node:nodes.values())if(retained.contains(node.optString("id"))){
            JSONObject copy=compactNode(node,selected.contains(node.optString("id"))?selectedTextChars:96,selected.contains(node.optString("id"))?needle:"");JSONArray children=new JSONArray(), raw=node.optJSONArray("children");
            if(raw!=null)for(int i=0;i<raw.length();i++)if(retained.contains(raw.optString(i)))children.put(raw.getString(i));
            if(raw==null||children.length()!=raw.length())copy.put("childrenComplete",false).put("subtreeComplete",false).put("subtreeTextComplete",false);
            if(copy.optBoolean("textTruncated")){textClipped=true;clippedWindows.add(copy.optInt("windowId",-1));}
            projected.put(copy.put("children",children));copies.put(copy.getString("id"),copy);
        }
        // Native preorder means children are settled before parents in this reverse pass.
        for(int i=projected.length()-1;i>=0;i--){JSONObject parent=projected.getJSONObject(i);JSONArray children=parent.getJSONArray("children");for(int j=0;j<children.length();j++){
            JSONObject child=copies.get(children.getString(j));if(child==null||!child.optBoolean("subtreeComplete"))parent.put("subtreeComplete",false).put("subtreeTextComplete",false);
            else if(!child.optBoolean("subtreeTextComplete"))parent.put("subtreeTextComplete",false);
        }}
        JSONArray windows=new JSONArray(), rawWindows=observation.optJSONArray("windows");
        if(rawWindows!=null)for(int i=0;i<rawWindows.length();i++){
            JSONObject window=new JSONObject(rawWindows.getJSONObject(i).toString());JSONArray roots=new JSONArray(),rawRoots=window.optJSONArray("rootIds");
            if(rawRoots!=null)for(int j=0;j<rawRoots.length();j++)if(retained.contains(rawRoots.optString(j)))roots.put(rawRoots.getString(j));
            if(omittedWindows.contains(window.optInt("windowId",-1)))window.put("truncated",true).put("structuralComplete",false).put("projectionOmittedNodes",true);
            if(clippedWindows.contains(window.optInt("windowId",-1)))window.put("projectionTextTruncated",true);
            windows.put(window.put("rootIds",roots));
        }
        JSONObject coverage=out.optJSONObject("treeCoverage");if(coverage==null)coverage=new JSONObject();
        int omitted=nodes.size()-projected.length();coverage.put("projectionOmittedNodes",omitted).put("projectionTextTruncated",textClipped).put("returnedNodes",projected.length());
        if(textClipped)coverage.put("bodyTruncated",true);
        return out.put("nodes",projected).put("windows",windows).put("truncated",observation.optBoolean("truncated")||omitted>0).put("treeCoverage",coverage)
                .put("taskProjection",new JSONObject().put("kind",kind).put("sourceNodes",nodes.size()).put("returnedNodes",projected.length()).put("omittedNodes",omitted).put("textClipped",textClipped));
    }
    static boolean completeForSelector(JSONObject observation,JSONObject selector){
        return completeForSelector(observation,selector,selector.has("text"));
    }
    private static boolean completeForSelector(JSONObject observation,JSONObject selector,boolean text){
        String parent=selector.optString("parentId");
        if(!parent.isEmpty()){JSONObject node=index(observation.optJSONArray("nodes")).get(parent);return node!=null&&completeSubtree(observation,node,text);}
        if(selector.has("windowId")){
            if(!treeAvailable(observation))return false;JSONArray windows=observation.optJSONArray("windows");
            for(int i=0;windows!=null&&i<windows.length();i++){JSONObject window=windows.optJSONObject(i);if(window==null||window.optInt("windowId",-1)!=selector.optInt("windowId",-2))continue;
                return window.optBoolean("rootAvailable")&&!window.optBoolean("truncated")&&(!text||window.optInt("textTruncations")==0&&!window.optBoolean("projectionOmittedNodes")&&!window.optBoolean("projectionTextTruncated"));}
            return false;
        }
        JSONObject coverage=observation.optJSONObject("treeCoverage");
        return complete(observation)&&!(text&&coverage!=null&&coverage.optBoolean("bodyTruncated"));
    }
    static boolean completeSubtree(JSONObject observation,JSONObject node,boolean text){
        if(!treeAvailable(observation))return false;boolean windowPresent=false;JSONArray windows=observation.optJSONArray("windows");
        for(int i=0;windows!=null&&i<windows.length();i++){JSONObject window=windows.optJSONObject(i);if(window!=null&&window.optInt("windowId",-1)==node.optInt("windowId",-2)&&window.optBoolean("rootAvailable"))windowPresent=true;}
        if(!windowPresent)return false;
        if(node.has("subtreeComplete"))return node.optBoolean("subtreeComplete")&&(!text||node.optBoolean("subtreeTextComplete"));
        JSONObject coverage=observation.optJSONObject("treeCoverage");return complete(observation)&&(!text||coverage==null||!coverage.optBoolean("bodyTruncated"));
    }
    private static boolean treeAvailable(JSONObject observation){
        JSONObject sources=observation.optJSONObject("observationSources");return observation.optInt("treeSchemaVersion")==1&&sources!=null&&sources.optBoolean("treeAvailable");
    }
    private static boolean complete(JSONObject observation){
        if(observation.optBoolean("truncated")||observation.optInt("treeSchemaVersion")!=1)return false;
        JSONObject sources=observation.optJSONObject("observationSources");if(sources==null||!sources.optBoolean("treeAvailable"))return false;
        JSONArray windows=observation.optJSONArray("windows");if(windows==null||windows.length()==0)return false;
        for(int i=0;i<windows.length();i++){JSONObject w=windows.optJSONObject(i);if(w==null||!w.optBoolean("rootAvailable")||w.optBoolean("truncated"))return false;}
        return true;
    }
    private static Map<String,JSONObject> index(JSONArray nodes){Map<String,JSONObject> out=new LinkedHashMap<>();if(nodes!=null)for(int i=0;i<nodes.length();i++){JSONObject n=nodes.optJSONObject(i);if(n!=null)out.put(n.optString("id"),n);}return out;}
    private static boolean descendant(JSONObject node,String parent,Map<String,JSONObject> nodes){Set<String> seen=new HashSet<>();JSONObject n=node;while(n!=null&&seen.add(n.optString("id"))){if(parent.equals(n.optString("id")))return true;n=nodes.get(n.optString("parentId"));}return false;}
    private static boolean contains(String haystack,String needle){return haystack.toLowerCase(java.util.Locale.ROOT).contains(needle.toLowerCase(java.util.Locale.ROOT));}
    private static int bytes(Object value){return String.valueOf(value).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;}
    private static JSONObject unavailable(String reason,JSONObject observation)throws Exception{return new JSONObject().put("ok",false).put("status","unavailable").put("reason",reason).put("error",reason).put("sideEffecting",false).put("verifiedBusinessSuccess",false).put("automaticReplayAllowed",false);}
    private static String text(JSONObject json,String key,int max,boolean required)throws Exception{Object v=json.opt(key);if(!(v instanceof String)||((String)v).length()>max||required&&((String)v).isEmpty())throw new IllegalArgumentException("无效的"+key);return(String)v;}
    private static int integer(JSONObject json,String key,int min,int max){Object v=json.opt(key);if(!(v instanceof Number))throw new IllegalArgumentException("无效的"+key);double n=((Number)v).doubleValue();if(!Double.isFinite(n)||n!=Math.rint(n)||n<min||n>max)throw new IllegalArgumentException("无效的"+key);return(int)n;}
}
