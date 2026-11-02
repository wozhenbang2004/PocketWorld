package dev.pocketengine.phone.observation;

import android.graphics.Rect;
import android.util.SparseArray;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import dev.pocketengine.phone.PhoneAccessibilityService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** One bounded, display-specific native read. Never retains Android nodes or relocates a ref by label. */
public final class AccessibilityForest {
    private static final int MAX_WINDOWS=32, MAX_NODES=384, MAX_WINDOW_NODES=256, MAX_DEPTH=40, MAX_CHILDREN=64;
    private static final int MAX_TEXT=400, MAX_IDENTITY_TEXT=64000, MAX_BODY_CHARS=24000;
    public interface Check { void check() throws Exception; }
    public static final class Ref {
        public final int windowId;
        public final int[] path;
        public final String identity, nativeIdentity;
        public final boolean editable, focused;
        private final Rect bounds;
        private final boolean visible;
        private final int drawingOrder;
        private final List<String> ancestors;
        final NativeSensitiveStep.Facts sensitive;
        private Ref(AccessibilityNodeInfo node,int windowId,int[] path,String identity,List<String> lineage){
            this.windowId=windowId;this.path=path.clone();this.identity=identity;this.nativeIdentity=nativeIdentity(node);
            editable=node.isEditable();focused=node.isFocused();visible=node.isVisibleToUser();drawingOrder=node.getDrawingOrder();
            bounds=new Rect();node.getBoundsInScreen(bounds);ancestors=new ArrayList<>(lineage);
            boolean password=node.isPassword();sensitive=new NativeSensitiveStep.Facts(password?null:node.getText(),password?null:node.getContentDescription(),password?null:node.getHintText(),password?"":relatedLabel(node),String.valueOf(node.getViewIdResourceName()),String.valueOf(node.getPackageName()),String.valueOf(node.getClassName()),node.isEditable(),node.isChecked());
        }
    }
    public final JSONArray nodes=new JSONArray(), windows=new JSONArray();
    public final Map<String,Ref> refs=new LinkedHashMap<>();
    public final Set<String> reasons=new LinkedHashSet<>();
    public String signature, packageName="";
    public int windowId=-1;
    public boolean available, truncated;
    private int visited, bodyChars, textTruncations, omittedWindows;
    private int primaryPriority=-1;
    private final MessageDigest digest;
    private final Map<Integer,WindowIdentity> windowIdentities=new LinkedHashMap<>();
    private AccessibilityForest() throws Exception { digest=MessageDigest.getInstance("SHA-256"); }

    public static AccessibilityForest read(PhoneAccessibilityService service,int displayId,int width,int height,Check check)throws Exception {
        check.check();PhoneReadGate.checkCurrent();AccessibilityForest out=new AccessibilityForest();
        out.mark(service.observationSessionId());out.mark(displayId+":"+width+":"+height);
        SparseArray<List<AccessibilityWindowInfo>> all=service.getWindowsOnAllDisplays();
        try {
            List<AccessibilityWindowInfo> selected=new ArrayList<>();
            List<AccessibilityWindowInfo> candidates=all.get(displayId);
            if(candidates!=null)for(AccessibilityWindowInfo window:candidates)
                if(window.getDisplayId()==displayId)selected.add(window);
            selected.sort(Comparator.comparingInt(AccessibilityWindowInfo::getLayer).reversed().thenComparingInt(AccessibilityWindowInfo::getId));
            for(AccessibilityWindowInfo window:selected){
                check.check();
                if(out.windowIdentities.size()>=MAX_WINDOWS){out.omittedWindows++;out.limit("window_limit");continue;}
                Rect windowBounds=new Rect();window.getBoundsInScreen(windowBounds);
                JSONObject record=new JSONObject().put("windowId",window.getId()).put("displayId",displayId)
                        .put("type",window.getType()).put("layer",window.getLayer()).put("active",window.isActive()).put("focused",window.isFocused());
                JSONArray roots=new JSONArray();Set<String> localReasons=new LinkedHashSet<>();int start=out.visited, textStart=out.textTruncations;
                record.put("bounds",new JSONObject().put("left",normal(windowBounds.left,width)).put("top",normal(windowBounds.top,height))
                        .put("right",normal(windowBounds.right,width)).put("bottom",normal(windowBounds.bottom,height)));
                if(window.getType()==AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY){
                    out.windowIdentities.put(window.getId(),new WindowIdentity(record.toString(),window.getLayer(),windowBounds,null));continue;
                }
                out.mark(record.toString());AccessibilityNodeInfo root=window.getRoot();
                out.windowIdentities.put(window.getId(),new WindowIdentity(record.toString(),window.getLayer(),windowBounds,root==null?null:nativeIdentity(root)));
                try {
                    record.put("rootAvailable",root!=null);
                    if(root==null){localReasons.add("no_accessible_root");out.mark("no_accessible_root");}
                    else {
                        out.available=true;
                        String pkg=limited(root.getPackageName(),250);
                        record.put("packageName",pkg);
                        int priority=window.getType()==AccessibilityWindowInfo.TYPE_APPLICATION?(window.isFocused()?4:window.isActive()?3:2):1;
                        if(priority>out.primaryPriority){out.windowId=window.getId();out.packageName=pkg;out.primaryPriority=priority;}
                        String id=out.walk(root,window.getId(),new int[0],null,0,width,height,start,new ArrayList<>(),new ArrayList<>(),localReasons,check);
                        if(id!=null)roots.put(id);
                    }
                } finally { if(root!=null)root.recycle(); }
                record.put("rootIds",roots).put("visitedNodes",out.visited-start).put("textTruncations",out.textTruncations-textStart)
                        .put("truncated",!localReasons.isEmpty()).put("truncationReasons",new JSONArray(localReasons));
                out.windows.put(record);
            }
        } finally {
            for(int i=0;i<all.size();i++)for(AccessibilityWindowInfo window:all.valueAt(i))window.recycle();
        }
        out.mark(out.reasons.toString());
        StringBuilder hash=new StringBuilder();for(byte b:out.digest.digest())hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        out.signature=hash.toString();check.check();PhoneReadGate.checkCurrent();return out;
    }
    private String walk(AccessibilityNodeInfo node,int window,int[] path,String parent,int depth,int width,int height,
                        int windowStart,List<AccessibilityNodeInfo> ancestors,List<String> ancestorIdentities,Set<String> local,Check check)throws Exception {
        check.check();PhoneReadGate.checkCurrent();
        if(visited>=MAX_NODES){limit("forest_node_limit",local);return null;}
        if(visited-windowStart>=MAX_WINDOW_NODES){limit("window_node_limit",local);return null;}
        if(depth>MAX_DEPTH){limit("depth_limit",local);return null;}
        // Equality here is Android source identity, not text/geometry/content equality. Repeated siblings survive.
        if(ancestors.contains(node)){limit("cycle",local);return null;}
        ancestors.add(node);int ancestorCount=ancestorIdentities.size();
        try {
            String id="n"+visited++;String ownIdentity=identity(node);mark(window+":"+Arrays.toString(path));mark(ownIdentity);
            Ref ref=new Ref(node,window,path,ownIdentity,ancestorIdentities);refs.put(id,ref);
            if(ref.sensitive.truncated)limit("identity_text_limit",local);
            ancestorIdentities.add(ref.nativeIdentity+":"+ownIdentity);
            boolean password=node.isPassword();JSONArray children=new JSONArray();Rect bounds=new Rect();node.getBoundsInScreen(bounds);
            JSONArray nodePath=new JSONArray();for(int index:path)nodePath.put(index);
            JSONObject record=new JSONObject().put("id",id).put("identityFingerprint",identityPart(ownIdentity)).put("path",nodePath).put("windowId",window).put("parentId",parent==null?JSONObject.NULL:parent)
                    .put("children",children).put("text",password?"[password]":body(node.getText(),local))
                    .put("description",password?"":body(node.getContentDescription(),local))
                    .put("hint",password?"":body(node.getHintText(),local)).put("labelledBy",password?"":body(relatedLabel(node),local)).put("packageName",limited(node.getPackageName(),250))
                    .put("className",limited(node.getClassName(),200)).put("resourceId",limited(node.getViewIdResourceName(),400)).put("role",role(node))
                    .put("clickable",node.isClickable()).put("editable",node.isEditable()).put("enabled",node.isEnabled())
                    .put("visible",node.isVisibleToUser()).put("password",password).put("checked",node.isChecked())
                    .put("selected",node.isSelected()).put("focused",node.isFocused()).put("scrollable",node.isScrollable())
                    .put("bounds",new JSONObject().put("left",normal(bounds.left,width)).put("top",normal(bounds.top,height))
                            .put("right",normal(bounds.right,width)).put("bottom",normal(bounds.bottom,height)));
            nodes.put(record);
            if(!password&&(length(node.getText())>MAX_IDENTITY_TEXT||length(node.getContentDescription())>MAX_IDENTITY_TEXT))limit("identity_text_limit",local);
            int count=node.getChildCount();if(count>MAX_CHILDREN)limit("children_limit",local);
            for(int i=0;i<Math.min(count,MAX_CHILDREN);i++){
                if(visited>=MAX_NODES||visited-windowStart>=MAX_WINDOW_NODES){limit(visited>=MAX_NODES?"forest_node_limit":"window_node_limit",local);break;}
                AccessibilityNodeInfo child=node.getChild(i);
                if(child==null){limit("missing_child",local);mark("missing:"+i);continue;}
                try{int[] next=Arrays.copyOf(path,path.length+1);next[path.length]=i;
                    String childId=walk(child,window,next,id,depth+1,width,height,windowStart,ancestors,ancestorIdentities,local,check);if(childId!=null)children.put(childId);
                }finally{child.recycle();}
            }
            mark("end");return id;
        } finally { ancestors.remove(ancestors.size()-1);while(ancestorIdentities.size()>ancestorCount)ancestorIdentities.remove(ancestorIdentities.size()-1); }
    }
    public JSONObject attach(JSONObject observation)throws Exception {
        return observation.put("treeSchemaVersion",1).put("treeFingerprint",signature).put("windows",windows).put("nodes",nodes).put("truncated",truncated)
                .put("treeCoverage",new JSONObject().put("visitedNodes",visited).put("returnedNodes",nodes.length()).put("windowCount",windows.length())
                        .put("omittedWindows",omittedWindows).put("bodyChars",bodyChars).put("textTruncations",textTruncations)
                        .put("truncated",truncated).put("bodyTruncated",textTruncations>0).put("truncationReasons",new JSONArray(reasons)));
    }
    /** Resolves only the exact saved display/window/path. No active-root or same-label fallback. */
    public static AccessibilityNodeInfo resolve(PhoneAccessibilityService service,int displayId,Ref ref)throws Exception {
        PhoneReadGate.checkCurrent();SparseArray<List<AccessibilityWindowInfo>> all=service.getWindowsOnAllDisplays();AccessibilityNodeInfo node=null;
        try{PhoneReadGate.checkCurrent();List<AccessibilityWindowInfo> windows=all.get(displayId);if(windows!=null)for(AccessibilityWindowInfo window:windows)
            if(window.getDisplayId()==displayId&&window.getId()==ref.windowId&&window.getType()!=AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY){node=window.getRoot();break;}}
        finally{for(int i=0;i<all.size();i++)for(AccessibilityWindowInfo window:all.valueAt(i))window.recycle();}
        try {
            for(int index:ref.path){PhoneReadGate.checkCurrent();if(node==null)return null;AccessibilityNodeInfo parent=node;node=null;try{node=parent.getChild(index);}finally{parent.recycle();}}
            PhoneReadGate.checkCurrent();return node;
        }catch(Exception|Error stopped){if(node!=null)node.recycle();throw stopped;}
    }
    /**
     * Exact saved path, source identity and relevant geometry only. No lookup by
     * label and no permission from a changed full-frame generation. A sibling
     * ticker outside the target is irrelevant; any changed overlapping node,
     * ancestor, target window or potentially covering window invalidates the ref.
     */
    public boolean sameTarget(Ref expected,AccessibilityForest fresh) {
        if(expected==null||fresh==null||truncated||fresh.truncated)return false;
        Ref actual=null;
        for(Ref candidate:fresh.refs.values())if(candidate.windowId==expected.windowId&&Arrays.equals(candidate.path,expected.path)){actual=candidate;break;}
        if(actual==null||!expected.identity.equals(actual.identity)||!expected.nativeIdentity.equals(actual.nativeIdentity)
                ||!expected.ancestors.equals(actual.ancestors))return false;
        WindowIdentity before=windowIdentities.get(expected.windowId),after=fresh.windowIdentities.get(expected.windowId);
        if(before==null||after==null||!before.same(after))return false;
        // A higher window whose bounds cover this target is not proven safe,
        // even if that window existed before or has an inaccessible/transparent root.
        if(!coveringWindows(expected,before).isEmpty()||!fresh.coveringWindows(actual,after).isEmpty())return false;
        return relevantNodes(expected).equals(fresh.relevantNodes(actual));
    }
    /** A refreshed native handle still has to be the saved source, not an identical replacement at that path. */
    public static boolean matches(Ref ref,AccessibilityNodeInfo node) {
        return ref!=null&&node!=null&&ref.nativeIdentity.equals(nativeIdentity(node))&&ref.identity.equals(identity(node));
    }
    private List<String> relevantNodes(Ref target) {
        List<String> result=new ArrayList<>();
        for(Ref node:refs.values()) {
            if(node.windowId!=target.windowId||!node.visible||prefix(node.path,target.path)||!Rect.intersects(node.bounds,target.bounds))continue;
            result.add(Arrays.toString(node.path)+":"+node.nativeIdentity+":"+node.drawingOrder+":"+node.identity);
        }
        return result;
    }
    private List<String> coveringWindows(Ref target,WindowIdentity own) {
        List<String> result=new ArrayList<>();
        for(Map.Entry<Integer,WindowIdentity> entry:windowIdentities.entrySet()) {
            WindowIdentity other=entry.getValue();
            if(entry.getKey()!=target.windowId&&other.layer>=own.layer&&(other.bounds.isEmpty()||Rect.intersects(other.bounds,target.bounds)))
                result.add(entry.getKey()+":"+other.facts+":"+other.nativeRoot);
        }
        return result;
    }
    private static boolean prefix(int[] candidate,int[] path) {
        if(candidate.length>path.length)return false;
        for(int i=0;i<candidate.length;i++)if(candidate[i]!=path[i])return false;return true;
    }
    private static String nativeIdentity(AccessibilityNodeInfo node) {
        // Android's source-based hash (window + source node id), strengthened by
        // the platform's optional app-provided unique id on API 33+. No node is retained.
        String unique=android.os.Build.VERSION.SDK_INT>=33?node.getUniqueId():null;
        return node.getWindowId()+":"+node.hashCode()+":"+identityPart(unique);
    }
    private static final class WindowIdentity {
        final String facts,nativeRoot;final int layer;final Rect bounds;
        WindowIdentity(String facts,int layer,Rect bounds,String root){this.facts=facts;this.layer=layer;this.bounds=new Rect(bounds);nativeRoot=root;}
        boolean same(WindowIdentity other){return facts.equals(other.facts)&&bounds.equals(other.bounds)&&java.util.Objects.equals(nativeRoot,other.nativeRoot);}
    }
    public static String identity(AccessibilityNodeInfo node){
        Rect r=new Rect();node.getBoundsInScreen(r);StringBuilder s=new StringBuilder();
        for(CharSequence value:new CharSequence[]{node.getPackageName(),node.getClassName(),node.getViewIdResourceName(),
                node.isPassword()?null:node.getText(),node.isPassword()?null:node.getContentDescription(),node.isPassword()?null:node.getHintText(),node.isPassword()?null:relatedLabel(node)}){
            String part=identityPart(value);s.append(part.length()).append(':').append(part);
        }
        return s.append('|').append(node.getWindowId()).append('|').append(r.toShortString()).append('|').append(node.getChildCount())
                .append('|').append(node.isVisibleToUser()).append('|').append(node.isEnabled()).append('|').append(node.isClickable())
                .append('|').append(node.isEditable()).append('|').append(node.isFocused()).append('|').append(node.isPassword())
                .append('|').append(node.isChecked()).append('|').append(node.isSelected()).append('|').append(node.isScrollable()).toString();
    }
    /** Platform labelFor relation, not a guessed match to another window's text. Read only on bounded workers. */
    private static String relatedLabel(AccessibilityNodeInfo node){
        AccessibilityNodeInfo label=node.getLabeledBy();if(label==null)return "";
        try{label.refresh();return label.isPassword()?"":String.valueOf(label.getText()==null?"":label.getText())+" "+String.valueOf(label.getContentDescription()==null?"":label.getContentDescription())+" "+String.valueOf(label.getHintText()==null?"":label.getHintText());}
        finally{if(label!=node)label.recycle();}
    }
    private String body(CharSequence value,Set<String> local){
        int length=length(value), keep=Math.min(length,Math.min(MAX_TEXT,MAX_BODY_CHARS-bodyChars));
        if(length>keep){textTruncations++;reasons.add(bodyChars>=MAX_BODY_CHARS?"body_char_limit":"node_text_limit");}
        bodyChars+=keep;return limited(value,keep);
    }
    private static String identityPart(CharSequence value){
        if(value==null)return "null";
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            for(int i=0;i<Math.min(value.length(),MAX_IDENTITY_TEXT);i++){char c=value.charAt(i);digest.update((byte)(c>>>8));digest.update((byte)c);}
            StringBuilder out=new StringBuilder().append(value.length()).append(':');
            for(byte b:digest.digest())out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private void mark(String part){byte[] bytes=part.getBytes(StandardCharsets.UTF_8);digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));digest.update((byte)':');digest.update(bytes);}
    private void limit(String reason){truncated=true;reasons.add(reason);}
    private void limit(String reason,Set<String> local){limit(reason);local.add(reason);}
    private static int length(CharSequence value){return value==null?0:value.length();}
    private static String limited(CharSequence value,int max){return value==null?"":value.subSequence(0,Math.min(value.length(),max)).toString();}
    private static int normal(int value,int size){return Math.max(0,Math.min(1000,Math.round(value*1000f/Math.max(1,size))));}
    private static String role(AccessibilityNodeInfo node){
        String cls=limited(node.getClassName(),200).toLowerCase(java.util.Locale.ROOT);
        if(node.isEditable())return "input";if(cls.contains("checkbox"))return "checkbox";if(cls.contains("radiobutton"))return "radio";
        if(cls.contains("switch"))return "switch";if(cls.contains("button"))return "button";if(cls.contains("webview"))return "web";
        if(cls.contains("list")||cls.contains("recyclerview")||cls.contains("grid"))return "list";
        if(node.isScrollable())return "scroll";if(cls.contains("image"))return "image";if(cls.contains("text"))return "text";return "container";
    }
}
