package dev.pocketengine.features.local.project;

import dev.pocketengine.features.workspace.VersionedWorkspace;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owner presentation of actual file facts plus separately identified unsaved drafts. Not a file/permission authority. */
public final class ProjectFileTree {
    public static final int ALL=0,CHANGED=1,DRAFTS=2,MAX_RECORDS=2*ProjectSources.MAX_FILES;
    public static final class Node {
        public final String path,label,change,sha256;public final long bytes;
        public final boolean directory,binary,draft,draftOnly,deleted;
        public final int fileCount,changedCount,draftCount;
        public final List<Node> children;
        private Node(FileFact file){path=file.path;label=path.substring(path.lastIndexOf('/')+1);change=file.change;sha256=file.sha;bytes=file.bytes;directory=false;binary=file.binary;draft=file.draft;draftOnly=file.draftOnly;deleted=change.equals("deleted");fileCount=1;changedCount=!change.equals("unchanged")&&!draftOnly?1:0;draftCount=draft?1:0;children=List.of();}
        private Node(String path,String label,List<Node> children){this.path=path;this.label=label;this.children=Collections.unmodifiableList(children);change="directory";sha256="";bytes=0;directory=true;binary=false;draftOnly=false;deleted=false;int files=0,changed=0,drafts=0;for(Node node:children){files+=node.fileCount;changed+=node.changedCount;drafts+=node.draftCount;}fileCount=files;changedCount=changed;draftCount=drafts;draft=drafts>0;}
    }
    public static final class Row {
        public final Node node;public final int depth;public final boolean expanded;public final String displayName;
        private Row(Node node,int depth,boolean expanded,boolean search){this.node=node;this.depth=depth;this.expanded=expanded;displayName=search?node.path:node.label;}
    }
    private static final class FileFact {
        final String path,change,sha;final long bytes;final boolean binary,draft,draftOnly;
        FileFact(String path,String change,String sha,long bytes,boolean binary,boolean draft,boolean draftOnly){this.path=path;this.change=change;this.sha=sha;this.bytes=bytes;this.binary=binary;this.draft=draft;this.draftOnly=draftOnly;}
    }
    public final List<Node> roots;public final int recordCount,nodeCount,draftOnlyCount;
    private final List<Node> files;
    private ProjectFileTree(List<Node> roots,List<Node> files,int nodeCount,int draftOnly){this.roots=Collections.unmodifiableList(roots);this.files=Collections.unmodifiableList(files);recordCount=files.size();this.nodeCount=nodeCount;draftOnlyCount=draftOnly;}
    public static ProjectFileTree from(JSONArray actual,Collection<String> actualDirtyDraftPaths)throws Exception {
        if(actual==null||actual.length()>MAX_RECORDS||actualDirtyDraftPaths==null||actualDirtyDraftPaths.size()>ProjectFileDraftStore.MAX_DIRTY_FILES)throw new IllegalArgumentException("本次文件树记录超出活跃文件/删除记录或原草稿范围，未将缺失视为无文件");Set<String> dirty=new HashSet<>();for(String path:actualDirtyDraftPaths)dirty.add(VersionedWorkspace.path(path));Map<String,FileFact> facts=new TreeMap<>();
        for(int i=0;i<actual.length();i++){
            JSONObject file=actual.getJSONObject(i);String path=VersionedWorkspace.path(file.getString("path")),change=file.getString("change"),sha=file.getString("sha256");Object raw=file.opt("bytes");
            if(!Set.of("added","modified","deleted","unchanged").contains(change)||!(raw instanceof Integer||raw instanceof Long)||file.getLong("bytes")<0||file.getLong("bytes")>ProjectSources.MAX_FILE_BYTES||!(file.opt("binary") instanceof Boolean)||!(change.equals("deleted")?sha.equals("absent"):sha.matches("[a-f0-9]{64}")))throw new IllegalArgumentException("原文件事实不完整，未猜测文件类型、版本或状态");
            if(facts.put(path,new FileFact(path,change,sha,file.getLong("bytes"),file.getBoolean("binary"),dirty.contains(path),false))!=null)throw new IllegalArgumentException("原文件路径重复，未把两个版本合为一行");
        }
        int draftOnly=0;for(String path:dirty)if(!facts.containsKey(path)){facts.put(path,new FileFact(path,"unsaved_draft","",-1,false,true,true));draftOnly++;}
        // Changes is a union of current paths and deleted baseline paths. A former directory can
        // legitimately be replaced by a file (or the reverse); deleted rows are not current files.
        for(FileFact file:facts.values())for(int slash=file.path.indexOf('/');slash>=0;slash=file.path.indexOf('/',slash+1)){FileFact parent=facts.get(file.path.substring(0,slash));if(parent!=null&&!parent.draftOnly&&!file.draftOnly&&!parent.change.equals("deleted")&&!file.change.equals("deleted"))throw new IllegalArgumentException("原文件事实存在文件/目录冲突");}
        // At most n file leaves + n compact unary parents + (n-1) branching directories.
        // Unary chains do not allocate a node for every segment; a shared branching root still
        // counts in addition to each leaf's compact parent (two files may legitimately need five).
        List<Node> roots=build(new ArrayList<>(facts.values()),""),files=new ArrayList<>();int nodes=count(roots,files);if(nodes>Math.max(0,3*facts.size()-1))throw new IllegalStateException("文件树压缩范围不一致");files.sort(Comparator.comparing(node->node.path));return new ProjectFileTree(roots,files,nodes,draftOnly);
    }
    private static List<Node> build(List<FileFact> facts,String prefix){
        List<Node> result=new ArrayList<>();Map<String,List<FileFact>> directories=new TreeMap<>();
        for(FileFact file:facts){String tail=file.path.substring(prefix.length());int slash=tail.indexOf('/');if(slash<0)result.add(new Node(file));else directories.computeIfAbsent(tail.substring(0,slash+1),key->new ArrayList<>()).add(file);}
        for(List<FileFact> group:directories.values()){
            String directory=group.get(0).path.substring(0,group.get(0).path.lastIndexOf('/')+1);for(FileFact file:group)while(!file.path.startsWith(directory)){int end=directory.lastIndexOf('/',directory.length()-2);directory=directory.substring(0,end+1);}
            String path=directory.substring(0,directory.length()-1),label=path.substring(prefix.length());result.add(new Node(path,label,build(group,directory)));
        }
        result.sort(Comparator.<Node>comparingInt(node->node.directory?0:1).thenComparing(node->node.label,String.CASE_INSENSITIVE_ORDER).thenComparing(node->node.path));return result;
    }
    private static int count(List<Node> nodes,List<Node> files){int total=nodes.size();for(Node node:nodes)if(node.directory)total+=count(node.children,files);else files.add(node);return total;}
    public List<Row> visible(Collection<String> expanded,String rawQuery,int filter){
        if(expanded==null||expanded.size()>MAX_RECORDS+ProjectFileDraftStore.MAX_DIRTY_FILES||rawQuery==null||rawQuery.length()>160||filter<ALL||filter>DRAFTS)throw new IllegalArgumentException("文件树显示条件无效");Set<String> open=new HashSet<>(expanded);List<Row> rows=new ArrayList<>();String query=rawQuery.trim().toLowerCase(Locale.ROOT);
        if(!query.isEmpty()){for(Node file:files)if(eligible(file,filter)&&file.path.toLowerCase(Locale.ROOT).contains(query))rows.add(new Row(file,0,false,true));}
        else for(Node node:roots)flatten(node,0,open,filter,rows);return Collections.unmodifiableList(rows);
    }
    private static boolean eligible(Node node,int filter){return filter==ALL||filter==CHANGED&&(node.changedCount>0||node.draftCount>0)||filter==DRAFTS&&node.draftCount>0;}
    public static boolean expanded(Node node,Collection<String> open){if(!node.directory)return false;for(String path:open)if(path.equals(node.path)||path.startsWith(node.path+"/"))return true;return false;}
    private static void flatten(Node node,int depth,Set<String> expanded,int filter,List<Row> out){if(!eligible(node,filter))return;boolean open=expanded(node,expanded);out.add(new Row(node,depth,open,false));if(open)for(Node child:node.children)flatten(child,depth+1,expanded,filter,out);}
    public Set<String> ancestors(String filePath){VersionedWorkspace.path(filePath);Set<String> out=new HashSet<>();for(Node root:roots)ancestors(root,filePath,out);return Collections.unmodifiableSet(out);}
    private static void ancestors(Node node,String path,Set<String> result){if(node.directory&&path.startsWith(node.path+"/")){result.add(node.path);for(Node child:node.children)ancestors(child,path,result);}}
}
