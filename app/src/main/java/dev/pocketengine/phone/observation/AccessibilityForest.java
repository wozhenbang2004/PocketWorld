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
    static final int MAX_NODES=2048, MAX_DEPTH=96;
    private static final int MAX_WINDOWS=32, MAX_WINDOW_NODES=1536, MAX_CHILDREN=256;
    private static final int TARGET_NODES=4096, TARGET_DEPTH=128, TARGET_CHILDREN=1024;
    // The native snapshot keeps searchable text. Model-facing projections use a smaller budget.
    private static final int MAX_TEXT=16000, MAX_IDENTITY_TEXT=64000, MAX_BODY_CHARS=96000;
    public interface Check { void check() throws Exception; }
    public static final class Ref {
        public final int windowId;
        public final int[] path;
        public final String identity, nativeIdentity, structuralFingerprint;
        public final boolean editable, focused;
        private final boolean enabled, clickable;
        private final Rect bounds;
        private final boolean visible;
        private final int drawingOrder;
        private final List<String> ancestors;
        final NativeSensitiveStep.Facts sensitive;
        private Ref(AccessibilityNodeInfo node,int windowId,int[] path,String identity,List<String> lineage,List<String> structure,String label){
            this.windowId=windowId;this.path=path.clone();this.identity=identity;this.nativeIdentity=nativeIdentity(node);
            structuralFingerprint=structureIdentity(node,windowId,path,structure);
            editable=supportsSetText(node);focused=node.isFocused();visible=node.isVisibleToUser();drawingOrder=node.getDrawingOrder();
            enabled=node.isEnabled();clickable=supportsClick(node);
            bounds=new Rect();node.getBoundsInScreen(bounds);ancestors=new ArrayList<>(lineage);
            boolean password=node.isPassword();sensitive=new NativeSensitiveStep.Facts(password?null:node.getText(),password?null:node.getContentDescription(),password?null:node.getHintText(),password?"":label,String.valueOf(node.getViewIdResourceName()),String.valueOf(node.getPackageName()),String.valueOf(node.getClassName()),editable,node.isChecked());
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
    private final int nodeLimit,windowNodeLimit,depthLimit,childrenLimit;
    private final Map<Integer,WindowIdentity> windowIdentities=new LinkedHashMap<>();
    private final Map<String,JSONObject> records=new LinkedHashMap<>();
    private AccessibilityForest(int targetWindow) throws Exception {
        boolean targeted=targetWindow>=0;
        nodeLimit=targeted?TARGET_NODES:MAX_NODES;windowNodeLimit=targeted?TARGET_NODES:MAX_WINDOW_NODES;
        depthLimit=targeted?TARGET_DEPTH:MAX_DEPTH;childrenLimit=targeted?TARGET_CHILDREN:MAX_CHILDREN;
        digest=MessageDigest.getInstance("SHA-256");
    }

    public static AccessibilityForest read(PhoneAccessibilityService service,int displayId,int width,int height,Check check)throws Exception {
        return read(service,displayId,width,height,-1,check);
    }
    /** Native revalidation only: complete the original target window, never select another target. */
    public static AccessibilityForest readForTarget(PhoneAccessibilityService service,int displayId,int width,int height,Ref expected,Check check)throws Exception {
        if(expected==null||expected.windowId<0||expected.path.length>TARGET_DEPTH)throw new IllegalArgumentException("original_native_target_required");
        return read(service,displayId,width,height,expected.windowId,check);
    }
    private static AccessibilityForest read(PhoneAccessibilityService service,int displayId,int width,int height,int targetWindow,Check check)throws Exception {
        check.check();PhoneReadGate.checkCurrent();AccessibilityForest out=new AccessibilityForest(targetWindow);
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
                    out.windowIdentities.put(window.getId(),new WindowIdentity(record.toString(),window.getType(),window.getLayer(),windowBounds,null));continue;
                }
                out.mark(record.toString());AccessibilityNodeInfo root=window.getRoot();
                out.windowIdentities.put(window.getId(),new WindowIdentity(record.toString(),window.getType(),window.getLayer(),windowBounds,root==null?null:nativeIdentity(root)));
                try {
                    record.put("rootAvailable",root!=null);
                    if(root==null){localReasons.add("no_accessible_root");out.mark("no_accessible_root");}
                    else {
                        out.available=true;
                        String pkg=limited(root.getPackageName(),250);
                        record.put("packageName",pkg);
                        int priority=window.getType()==AccessibilityWindowInfo.TYPE_APPLICATION?(window.isFocused()?4:window.isActive()?3:2):1;
                        if(priority>out.primaryPriority){out.windowId=window.getId();out.packageName=pkg;out.primaryPriority=priority;}
                        if(targetWindow>=0&&window.getId()!=targetWindow)out.limit("outside_target_window",localReasons);
                        else {
                            String id=out.walk(root,window.getId(),new int[0],null,0,width,height,start,new ArrayList<>(),new ArrayList<>(),new ArrayList<>(),localReasons,check);
                            if(id!=null)roots.put(id);
                        }
                    }
                } finally { if(root!=null)root.recycle(); }
                record.put("rootIds",roots).put("visitedNodes",out.visited-start).put("textTruncations",out.textTruncations-textStart)
                        .put("truncated",!localReasons.isEmpty()).put("structuralComplete",root!=null&&localReasons.isEmpty())
                        .put("truncationReasons",new JSONArray(localReasons));
                out.windowIdentities.get(window.getId()).treeComplete=root!=null&&localReasons.isEmpty();
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
                        int windowStart,List<AccessibilityNodeInfo> ancestors,List<String> ancestorIdentities,List<String> ancestorStructures,Set<String> local,Check check)throws Exception {
        check.check();PhoneReadGate.checkCurrent();
        if(visited>=nodeLimit){limit("forest_node_limit",local);return null;}
        if(visited-windowStart>=windowNodeLimit){limit("window_node_limit",local);return null;}
        if(depth>depthLimit){limit("depth_limit",local);return null;}
        // Equality here is Android source identity, not text/geometry/content equality. Repeated siblings survive.
        if(ancestors.contains(node)){limit("cycle",local);return null;}
        ancestors.add(node);int ancestorCount=ancestorIdentities.size(),structureCount=ancestorStructures.size();
        try {
            String label=node.isPassword()?"":relatedLabel(node);
            String id="n"+visited++;String ownIdentity=identity(node,label);mark(window+":"+Arrays.toString(path));mark(ownIdentity);
            Ref ref=new Ref(node,window,path,ownIdentity,ancestorIdentities,ancestorStructures,label);refs.put(id,ref);
            if(ref.sensitive.truncated)limit("identity_text_limit",local);
            ancestorIdentities.add(ref.nativeIdentity+":"+ownIdentity);
            ancestorStructures.add(ref.structuralFingerprint);
            boolean password=node.isPassword();JSONArray children=new JSONArray();Rect bounds=new Rect();node.getBoundsInScreen(bounds);
            JSONArray nodePath=new JSONArray();for(int index:path)nodePath.put(index);
            JSONObject record=new JSONObject().put("id",id).put("identityFingerprint",identityPart(ownIdentity)).put("structuralFingerprint",ref.structuralFingerprint).put("path",nodePath).put("windowId",window).put("parentId",parent==null?JSONObject.NULL:parent)
                    .put("children",children).put("text",password?"[password]":body(node.getText(),local))
                    .put("description",password?"":body(node.getContentDescription(),local))
                    .put("hint",password?"":body(node.getHintText(),local)).put("labelledBy",password?"":body(label,local)).put("packageName",limited(node.getPackageName(),250))
                    .put("className",limited(node.getClassName(),200)).put("resourceId",limited(node.getViewIdResourceName(),400)).put("role",role(node))
                    .put("clickable",supportsClick(node)).put("editable",supportsSetText(node)).put("enabled",node.isEnabled())
                    .put("visible",node.isVisibleToUser()).put("password",password).put("checked",node.isChecked())
                    .put("selected",node.isSelected()).put("focused",node.isFocused()).put("scrollable",supportsScroll(node))
                    .put("scrollDirections",scrollDirections(node))
                    .put("bounds",new JSONObject().put("left",normal(bounds.left,width)).put("top",normal(bounds.top,height))
                            .put("right",normal(bounds.right,width)).put("bottom",normal(bounds.bottom,height)));
            JSONObject lengths=new JSONObject();boolean textComplete=true;
            CharSequence[] textValues={node.getText(),node.getContentDescription(),node.getHintText(),label};String[] textKeys={"text","description","hint","labelledBy"};
            for(int i=0;i<textKeys.length;i++){int length=password?0:length(textValues[i]);lengths.put(textKeys[i],length);if(!password&&record.getString(textKeys[i]).length()<length)textComplete=false;}
            record.put("textLengths",lengths).put("textTruncated",!textComplete).put("identityComplete",!ref.sensitive.truncated);
            nodes.put(record);records.put(id,record);
            if(!password&&(length(node.getText())>MAX_IDENTITY_TEXT||length(node.getContentDescription())>MAX_IDENTITY_TEXT))limit("identity_text_limit",local);
            int count=node.getChildCount();if(count>childrenLimit)limit("children_limit",local);
            boolean subtreeComplete=true,subtreeTextComplete=textComplete;
            for(int i=0;i<Math.min(count,childrenLimit);i++){
                check.check();PhoneReadGate.checkCurrent();
                if(visited>=nodeLimit||visited-windowStart>=windowNodeLimit){limit(visited>=nodeLimit?"forest_node_limit":"window_node_limit",local);break;}
                AccessibilityNodeInfo child=node.getChild(i);
                if(child==null){limit("missing_child",local);mark("missing:"+i);continue;}
                try{int[] next=Arrays.copyOf(path,path.length+1);next[path.length]=i;
                    String childId=walk(child,window,next,id,depth+1,width,height,windowStart,ancestors,ancestorIdentities,ancestorStructures,local,check);
                    if(childId!=null){children.put(childId);JSONObject childRecord=records.get(childId);subtreeComplete&=childRecord.optBoolean("subtreeComplete");subtreeTextComplete&=childRecord.optBoolean("subtreeTextComplete");}
                }finally{child.recycle();}
            }
            boolean childrenComplete=children.length()==count;
            record.put("childCount",count).put("childrenComplete",childrenComplete).put("subtreeComplete",childrenComplete&&subtreeComplete)
                    .put("subtreeTextComplete",childrenComplete&&subtreeComplete&&subtreeTextComplete);
            mark("end");return id;
        } finally { ancestors.remove(ancestors.size()-1);while(ancestorIdentities.size()>ancestorCount)ancestorIdentities.remove(ancestorIdentities.size()-1);while(ancestorStructures.size()>structureCount)ancestorStructures.remove(ancestorStructures.size()-1); }
    }
    public JSONObject attach(JSONObject observation)throws Exception {
        return observation.put("treeSchemaVersion",1).put("treeFingerprint",signature).put("windows",windows).put("nodes",nodes).put("truncated",truncated)
                .put("treeCoverage",new JSONObject().put("visitedNodes",visited).put("returnedNodes",nodes.length()).put("windowCount",windows.length())
                        .put("omittedWindows",omittedWindows).put("bodyChars",bodyChars).put("textTruncations",textTruncations)
                        .put("nodeLimit",nodeLimit).put("windowNodeLimit",windowNodeLimit).put("depthLimit",depthLimit).put("childrenLimit",childrenLimit)
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
    /** Capture coherence only; node actions still require exact target revalidation. */
    public boolean sameWindowLayout(AccessibilityForest fresh) {
        if(fresh==null||omittedWindows>0||fresh.omittedWindows>0||available!=fresh.available
                ||windowId!=fresh.windowId||!packageName.equals(fresh.packageName))return false;
        int beforeCount=0,afterCount=0;
        for(Map.Entry<Integer,WindowIdentity> entry:windowIdentities.entrySet()){
            WindowIdentity before=entry.getValue();
            if(before.type==AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)continue;
            beforeCount++;WindowIdentity after=fresh.windowIdentities.get(entry.getKey());
            if(after==null||!before.same(after))return false;
        }
        for(WindowIdentity after:fresh.windowIdentities.values())if(after.type!=AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)afterCount++;
        return beforeCount==afterCount;
    }
    /**
     * Exact saved path, source identity and relevant geometry only. No lookup by
     * label and no permission from a changed full-frame generation. A sibling
     * ticker outside the target is irrelevant; any changed overlapping node,
     * ancestor, target window or potentially covering window invalidates the ref.
     */
    public boolean sameTarget(Ref expected,AccessibilityForest fresh) {
        if(expected==null||fresh==null||expected.sensitive.truncated||!sameWindowLayout(fresh))return false;
        Ref actual=null;
        for(Ref candidate:fresh.refs.values())if(candidate.windowId==expected.windowId&&Arrays.equals(candidate.path,expected.path)){actual=candidate;break;}
        if(actual==null||actual.sensitive.truncated||!expected.identity.equals(actual.identity)||!expected.nativeIdentity.equals(actual.nativeIdentity)
                ||!expected.ancestors.equals(actual.ancestors))return false;
        WindowIdentity before=windowIdentities.get(expected.windowId),after=fresh.windowIdentities.get(expected.windowId);
        // A complete fresh target window proves all possible sibling/descendant occluders.
        // A truncated unrelated window is harmless only when its complete window bounds cannot cover us.
        if(before==null||after==null||!after.treeComplete||!before.same(after))return false;
        // A higher window whose bounds cover this target is not proven safe,
        // even if that window existed before or has an inaccessible/transparent root.
        if(!coveringWindows(expected,before).isEmpty()||!fresh.coveringWindows(actual,after).isEmpty())return false;
        return relevantNodes(expected).equals(fresh.relevantNodes(actual));
    }
    /** Resolve a label/icon through its original native ancestry, never by text or a different window. */
    public Ref clickTarget(Ref requested) {
        if(requested==null||!requested.visible||!requested.enabled)return null;
        for(int depth=requested.path.length;depth>=0;depth--)for(Ref candidate:refs.values()) {
            if(candidate.windowId==requested.windowId&&candidate.path.length==depth&&prefix(candidate.path,requested.path)
                    &&candidate.visible&&candidate.enabled&&candidate.clickable)return candidate;
        }
        return null;
    }
    /** Virtual accessibility controls may advertise ACTION_CLICK without the clickable flag. */
    public static boolean supportsClick(AccessibilityNodeInfo node) {
        return node.isClickable()||node.getActionList().stream().anyMatch(action->action.getId()==AccessibilityNodeInfo.ACTION_CLICK);
    }
    /** Virtual editors may advertise ACTION_SET_TEXT without setting the editable flag. */
    public static boolean supportsSetText(AccessibilityNodeInfo node) {
        return node.isEditable()||node.getActionList().stream().anyMatch(action->action.getId()==AccessibilityNodeInfo.ACTION_SET_TEXT);
    }
    /** Native action declarations also describe virtual lists that omit the scrollable flag. */
    public static boolean supportsScroll(AccessibilityNodeInfo node) {
        return node.isScrollable()||supportsScroll(node,AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                ||supportsScroll(node,AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
    }
    public static boolean supportsScroll(AccessibilityNodeInfo node,int action) {
        return (action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD||action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                &&node.getActionList().stream().anyMatch(candidate->candidate.getId()==action);
    }
    private static JSONArray scrollDirections(AccessibilityNodeInfo node) {
        JSONArray directions=new JSONArray();
        if(supportsScroll(node,AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))directions.put("forward");
        if(supportsScroll(node,AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))directions.put("backward");
        return directions;
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
        final String facts,nativeRoot;final int type,layer;final Rect bounds;
        boolean treeComplete;
        WindowIdentity(String facts,int type,int layer,Rect bounds,String root){this.facts=facts;this.type=type;this.layer=layer;this.bounds=new Rect(bounds);nativeRoot=root;}
        boolean same(WindowIdentity other){return facts.equals(other.facts)&&bounds.equals(other.bounds)&&java.util.Objects.equals(nativeRoot,other.nativeRoot);}
    }
    public static String identity(AccessibilityNodeInfo node){
        return identity(node,node.isPassword()?"":relatedLabel(node));
    }
    private static String identity(AccessibilityNodeInfo node,String related){
        Rect r=new Rect();node.getBoundsInScreen(r);StringBuilder s=new StringBuilder();
        for(CharSequence value:new CharSequence[]{node.getPackageName(),node.getClassName(),node.getViewIdResourceName(),
                node.isPassword()?null:node.getText(),node.isPassword()?null:node.getContentDescription(),node.isPassword()?null:node.getHintText(),node.isPassword()?null:related}){
            String part=identityPart(value);s.append(part.length()).append(':').append(part);
        }
        return s.append('|').append(node.getWindowId()).append('|').append(r.toShortString()).append('|').append(node.getChildCount())
                .append('|').append(node.isVisibleToUser()).append('|').append(node.isEnabled()).append('|').append(supportsClick(node))
                .append('|').append(node.isEditable()).append('|').append(supportsSetText(node)).append('|').append(node.isFocused()).append('|').append(node.isPassword())
                .append('|').append(node.isChecked()).append('|').append(node.isSelected()).append('|').append(node.isScrollable())
                .append('|').append(supportsScroll(node,AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
                .append('|').append(supportsScroll(node,AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)).toString();
    }
    /** Stable control evidence, never independent action authority; native hashes alone can collide. */
    private static String structureIdentity(AccessibilityNodeInfo node,int window,int[] path,List<String> ancestors){
        Rect bounds=new Rect();node.getBoundsInScreen(bounds);StringBuilder value=new StringBuilder("native-control-v1|");
        value.append(nativeIdentity(node)).append('|').append(window).append('|').append(Arrays.toString(path)).append('|').append(bounds.toShortString()).append('|').append(node.isPassword());
        for(CharSequence part:new CharSequence[]{node.getPackageName(),node.getClassName(),node.getViewIdResourceName()})value.append('|').append(identityPart(part));
        for(String ancestor:ancestors)value.append('|').append(ancestor);
        String hashed=identityPart(value);return hashed.substring(hashed.indexOf(':')+1);
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
    private static String limited(CharSequence value,int max){
        if(value==null)return "";int end=Math.min(value.length(),max);
        if(end>0&&end<value.length()&&Character.isHighSurrogate(value.charAt(end-1))&&Character.isLowSurrogate(value.charAt(end)))end--;
        return value.subSequence(0,end).toString();
    }
    private static int normal(int value,int size){return Math.max(0,Math.min(1000,Math.round(value*1000f/Math.max(1,size))));}
    private static String role(AccessibilityNodeInfo node){
        String cls=limited(node.getClassName(),200).toLowerCase(java.util.Locale.ROOT);
        if(supportsSetText(node))return "input";if(cls.contains("checkbox"))return "checkbox";if(cls.contains("radiobutton"))return "radio";
        if(cls.contains("switch"))return "switch";if(cls.contains("button"))return "button";if(cls.contains("webview"))return "web";
        if(cls.contains("list")||cls.contains("recyclerview")||cls.contains("grid"))return "list";
        if(supportsScroll(node))return "scroll";if(cls.contains("image"))return "image";if(cls.contains("text"))return "text";return "container";
    }
}

