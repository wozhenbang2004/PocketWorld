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
            int timeout=integer(args,"timeoutMs",100,6000);
            if(args.has("stableForMs")&&(!"stable".equals(args.optString("kind"))||integer(args,"stableForMs",250,6000)>timeout))throw new IllegalArgumentException("稳定时间必须在等待预算内");
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
        JSONArray matches=new JSONArray();Set<String> retained=new LinkedHashSet<>();
        for(int i=0;i<Math.min(limit,matched.size());i++){
            JSONObject node=matched.get(i);JSONArray parents=new JSONArray();JSONObject ancestor=node;String click=null,edit=null;
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
            matches.put(new JSONObject(node.toString()).put("parents",parents).put("actionNodeId",click==null?JSONObject.NULL:click).put("editNodeId",edit==null?JSONObject.NULL:edit));
        }
        JSONObject projected=projection(observation,nodes,retained);
        boolean partial=!completeForSelector(observation,selector);
        return new JSONObject().put("ok",true).put("status","read").put("observationId",observation.optString("observationId"))
                .put("selectionFingerprint",selectionFingerprint(nodes,matched)).put("matches",matches).put("total",matched.size()).put("truncated",matched.size()>limit).put("partial",partial)
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
        while(reads<25){
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
            if(!result.optBoolean("ok"))return waitResult("unavailable",null,reads,started,result.optString("reason"));
            int count=result.optInt("total");boolean complete=completeForSelector(latest,selector);
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
            // Sleep at least 250 ms between observations; cancel and pause are checked every <=50 ms.
            long wake=System.nanoTime()+250_000_000L;
            while(System.nanoTime()<wake&&System.nanoTime()<deadline){
                control.check();if(control.isPaused()||control.pauseRevision()!=revision)return unavailable("paused_or_changed",null);
                Thread.sleep(Math.max(1,Math.min(50,(Math.min(wake,deadline)-System.nanoTime())/1_000_000L)));
            }
        }
        return waitResult("timed_out",result,reads,started,reads>=25?"observation_limit":"deadline");
    }
    private static String selectionFingerprint(Map<String,JSONObject> nodes,List<JSONObject> matches)throws Exception {
        java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
        for(JSONObject node:nodes.values()){
            boolean included=false;
            for(JSONObject match:matches)if(descendant(node,match.optString("id"),nodes)){included=true;break;}
            if(!included)continue;
            // Original display/window/path and complete native identity survive unrelated node-ID shifts.
            JSONObject state=new JSONObject();
            for(String key:new String[]{"windowId","path","identityFingerprint","resourceId","className","role","text","description","hint","labelledBy","enabled","visible","password","clickable","editable","checked","selected","focused","scrollable","scrollDirections","bounds"})
                if(node.has(key))state.put(key,node.get(key));
            byte[] bytes=state.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            digest.update(Integer.toString(bytes.length).getBytes(java.nio.charset.StandardCharsets.US_ASCII));digest.update((byte)':');digest.update(bytes);
        }
        StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return hash.toString();
    }
    private static boolean sameAnchor(JSONObject a,JSONObject b)throws Exception {
        JSONArray path=a.optJSONArray("path");
        if(path==null||a.optInt("windowId",-1)!=b.optInt("windowId",-1)||!path.toString().equals(String.valueOf(b.optJSONArray("path"))))return false;
        for(String key:new String[]{"identityFingerprint","resourceId","className","role","text","description","hint","labelledBy","password","bounds"})if(!String.valueOf(a.opt(key)).equals(String.valueOf(b.opt(key))))return false;
        return true;
    }
    private static JSONObject waitResult(String status,JSONObject selection,int reads,long started,String reason)throws Exception {
        JSONObject out=selection==null?new JSONObject():new JSONObject(selection.toString());
        return out.put("ok",!"unavailable".equals(status)).put("status",status).put("matched","matched".equals(status))
                .put("reason",reason).put("observations",reads).put("elapsedMs",(System.nanoTime()-started)/1_000_000L)
                .put("sideEffecting",false).put("verifiedBusinessSuccess",false).put("automaticReplayAllowed",false);
    }
    private static JSONObject projection(JSONObject observation,Map<String,JSONObject> nodes,Set<String> retained)throws Exception {
        JSONObject out=new JSONObject(observation.toString());for(String key:new String[]{"imageBase64","imageMimeType","imageWidth","imageHeight","ocr"})out.remove(key);
        JSONArray projected=new JSONArray();
        for(JSONObject node:nodes.values())if(retained.contains(node.optString("id"))){
            JSONObject copy=new JSONObject(node.toString());JSONArray children=new JSONArray(), raw=node.optJSONArray("children");
            if(raw!=null)for(int i=0;i<raw.length();i++)if(retained.contains(raw.optString(i)))children.put(raw.getString(i));
            projected.put(copy.put("children",children));
        }
        JSONArray windows=new JSONArray(), rawWindows=observation.optJSONArray("windows");
        if(rawWindows!=null)for(int i=0;i<rawWindows.length();i++){
            JSONObject window=new JSONObject(rawWindows.getJSONObject(i).toString());JSONArray roots=new JSONArray(),rawRoots=window.optJSONArray("rootIds");
            if(rawRoots!=null)for(int j=0;j<rawRoots.length();j++)if(retained.contains(rawRoots.optString(j)))roots.put(rawRoots.getString(j));
            windows.put(window.put("rootIds",roots));
        }
        return out.put("nodes",projected).put("windows",windows).put("taskProjection",new JSONObject().put("kind","selector_and_ancestors")
                .put("sourceNodes",nodes.size()).put("returnedNodes",projected.length()).put("omittedNodes",nodes.size()-projected.length()));
    }
    private static boolean completeForSelector(JSONObject observation,JSONObject selector){
        JSONObject coverage=observation.optJSONObject("treeCoverage");
        // Output clipping does not revoke native action refs, but cannot prove absence of unshown text.
        return complete(observation)&&!(selector.has("text")&&coverage!=null&&coverage.optBoolean("bodyTruncated"));
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
    private static JSONObject unavailable(String reason,JSONObject observation)throws Exception{return new JSONObject().put("ok",false).put("status","unavailable").put("reason",reason).put("error",reason).put("sideEffecting",false).put("verifiedBusinessSuccess",false).put("automaticReplayAllowed",false);}
    private static String text(JSONObject json,String key,int max,boolean required)throws Exception{Object v=json.opt(key);if(!(v instanceof String)||((String)v).length()>max||required&&((String)v).isEmpty())throw new IllegalArgumentException("无效的"+key);return(String)v;}
    private static int integer(JSONObject json,String key,int min,int max){Object v=json.opt(key);if(!(v instanceof Number))throw new IllegalArgumentException("无效的"+key);double n=((Number)v).doubleValue();if(!Double.isFinite(n)||n!=Math.rint(n)||n<min||n>max)throw new IllegalArgumentException("无效的"+key);return(int)n;}
}
