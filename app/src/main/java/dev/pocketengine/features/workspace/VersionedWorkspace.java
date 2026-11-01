package dev.pocketengine.features.workspace;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Base64;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Each file head and retained revisions are one atomic record; paths never become host paths. */
public final class VersionedWorkspace {
    public static final int MAX_FILE_BYTES=2*1024*1024;
    public static final long MAX_PROJECT_RECORD_BYTES=4L*1024*1024;
    private final File root,catalog;
    @FunctionalInterface interface RecordWrite {void write(File target,JSONObject value)throws Exception;}
    private final RecordWrite recordWrite;
    private volatile java.util.Map<String,HeadObservation> nativeHeads=Collections.emptyMap();
    private volatile java.util.Set<String> nativeProjects;private volatile boolean projectionClosed;
    public VersionedWorkspace(File root){this(root,JsonFiles::write);}
    /** The same real atomic writer is injected only by package-local deterministic IO fixtures. */
    VersionedWorkspace(File root,RecordWrite recordWrite){this.root=root;this.catalog=new File(root,"projects.json");this.recordWrite=java.util.Objects.requireNonNull(recordWrite);}
    /** Body-free currentness of a registered native source, not a permission, file watcher or
     * persistent store. UNKNOWN is mandatory before IO bootstrap, during writes and after errors. */
    public static final class HeadObservation {
        public final String projectId,path,state,revisionId,sha256;public final int version;public final long sizeBytes;public final boolean deleted,authorityKnown;
        private HeadObservation(String project,String path,FileSnapshot actual){projectId=project;this.path=path;state=actual==null?"unknown":actual.version==0?"missing":"current";revisionId=actual==null?"":actual.revisionId;sha256=actual==null?"":actual.sha256;version=actual==null?-1:actual.version;sizeBytes=actual==null?-1:actual.sizeBytes;deleted=actual!=null&&actual.tombstone;authorityKnown=actual!=null;}
        public JSONObject metadata()throws Exception{return new JSONObject().put("projectId",projectId).put("path",path).put("state",state).put("version",version).put("revisionId",revisionId).put("sha256",sha256).put("sizeBytes",sizeBytes).put("deleted",deleted).put("authorityKnown",authorityKnown).put("nativeAuthorityProjection",true).put("filesystemWatcher",false).put("authorityAdded",false);}
    }
    private static String nativeKey(String project,String path){return project+'\0'+path;}
    /** No monitor, disk/Book/TaskStore callback, hidden warm read, or hash-derived version. */
    public HeadObservation currentFileHeadForHost(String project,String path){
        java.util.Set<String> projects=nativeProjects;java.util.Map<String,HeadObservation> heads=nativeHeads;
        if(projectionClosed||projects==null||!projects.contains(project))return new HeadObservation(project,path,null);
        HeadObservation value=heads.get(nativeKey(project,path));return value==null?new HeadObservation(project,path,null):value;
    }
    /** SourceBridge bootstraps just its real indexed/selected native sources on IO BEFORE its
     * Book gate. All subsequent native commits below maintain the same read-only projection. */
    public synchronized HeadObservation refreshFileHeadForHost(String project,String relative)throws Exception {
        if(projectionClosed)throw new IllegalStateException("原工作区来源当前性已关闭");relative=path(relative);
        if(project==null||!project.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("原项目身份无效");String key=nativeKey(project,relative);
        if(!nativeHeads.containsKey(key)&&nativeHeads.size()>=8192)throw new IllegalStateException("已登记项目来源head超过8192，未冒称未登记来源当前有效");
        updateNativeHead(project,relative,null,true);head(project,relative);return currentFileHeadForHost(project,relative);
    }
    public synchronized void closeNativeHeadsForHost(){projectionClosed=true;nativeProjects=null;nativeHeads=Collections.emptyMap();}
    private void updateNativeHead(String project,String path,FileSnapshot actual,boolean register){
        if(projectionClosed)return;String key=nativeKey(project,path);if(!register&&!nativeHeads.containsKey(key))return;
        java.util.Map<String,HeadObservation> next=new java.util.LinkedHashMap<>(nativeHeads);next.put(key,new HeadObservation(project,path,actual));nativeHeads=Collections.unmodifiableMap(next);
    }
    private void publishNativeProjects(JSONArray projects)throws Exception {
        java.util.Set<String> ids=new java.util.HashSet<>();if(projects.length()>20)throw new SecurityException("原项目目录超出20项范围");for(int i=0;i<projects.length();i++){String id=projects.getJSONObject(i).getString("id");if(!id.matches("[a-f0-9-]{36}")||!ids.add(id))throw new SecurityException("原项目目录身份重复或无效");}
        java.util.Map<String,HeadObservation> retained=new java.util.LinkedHashMap<>();for(var row:nativeHeads.entrySet())if(ids.contains(row.getValue().projectId))retained.put(row.getKey(),row.getValue());nativeHeads=Collections.unmodifiableMap(retained);nativeProjects=Collections.unmodifiableSet(ids);
    }
    /** An observation of bytes in this original store, not a grant or a model-supplied file. */
    public static final class FileSnapshot {
        private final VersionedWorkspace issuer;private final byte[] original;
        public final String projectId,path,revisionId,sha256,kind;public final int version;public final long sizeBytes;public final boolean tombstone;
        private FileSnapshot(VersionedWorkspace issuer,String project,String path,JSONObject entry)throws Exception {
            this.issuer=issuer;projectId=project;this.path=path;version=entry.optInt("version");revisionId=entry.optString("revisionId");if(entry.length()>0&&(version<1||!revisionId.matches("[a-f0-9-]{36}")))throw new SecurityException("原文件修订缺少真实版本或revisionId");tombstone=version==0||entry.optBoolean("deleted");
            original=tombstone?new byte[0]:entryBytes(entry);sizeBytes=original.length;sha256=tombstone?"absent":sha256(original);kind=tombstone?"tombstone":entry.optString("kind","text");
            if(entry.has("sha256")&&!sha256.equals(entry.getString("sha256")))throw new SecurityException("原版本字节摘要不一致，未改用最新文件");
        }
        public byte[] bytes(){if(tombstone)throw new IllegalStateException("所选版本是删除记录，没有文件字节");return original.clone();}
        public JSONObject json()throws Exception{return new JSONObject().put("projectId",projectId).put("path",path).put("version",version).put("revisionId",revisionId).put("sha256",sha256).put("kind",kind).put("sizeBytes",sizeBytes).put("tombstone",tombstone).put("authorityAdded",false);}
    }
    /** One synchronized observation of committed project heads, never an active checkout. */
    public static final class ProjectSnapshot {
        private final JSONObject definition;private final List<FileSnapshot> files;
        private ProjectSnapshot(JSONObject definition,List<FileSnapshot> files)throws Exception{this.definition=JsonFiles.copy(definition);this.files=Collections.unmodifiableList(new ArrayList<>(files));}
        public JSONObject definition()throws Exception{return JsonFiles.copy(definition);}
        public List<FileSnapshot> files(){return files;}
    }
    public synchronized FileSnapshot snapshot(String projectId,String path,int revision)throws Exception {
        path=path(path);JSONObject head=head(projectId,path);
        if(head.length()==0){if(revision>0)throw new IllegalArgumentException("原文件版本不存在");return new FileSnapshot(this,projectId,path,new JSONObject());}
        int wanted=revision<=0?head.getInt("version"):revision;JSONArray history=head.getJSONArray("history");
        for(int i=0;i<history.length();i++)if(history.getJSONObject(i).getInt("version")==wanted)return new FileSnapshot(this,projectId,path,history.getJSONObject(i));
        throw new IllegalArgumentException("所选版本已不在最近8版中；未改用最新文件");
    }
    public synchronized ProjectSnapshot snapshotProject(String projectId)throws Exception {
        project(projectId);JSONObject definition=null;JSONArray all=projects();for(int i=0;i<all.length();i++)if(projectId.equals(all.getJSONObject(i).getString("id")))definition=all.getJSONObject(i);
        List<FileSnapshot> result=new ArrayList<>();JSONArray heads=list(projectId);for(int i=0;i<heads.length();i++){JSONObject row=heads.getJSONObject(i);result.add(snapshot(projectId,row.getString("path"),row.getInt("version")));}
        return new ProjectSnapshot(definition,result);
    }
    public synchronized FileSnapshot writeBytes(String projectId,String path,byte[] bytes,int expectedVersion,String expectedSha256)throws Exception {
        if(bytes==null||bytes.length>MAX_FILE_BYTES)throw new IllegalArgumentException("单个版本文件最多2MiB；没有保存部分内容");
        path=path(path);FileSnapshot current=snapshot(projectId,path,0);requireExpected(current,expectedVersion,expectedSha256);
        byte[] retained=bytes.clone();String text=null;try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(retained)).toString();if(text.indexOf('\0')>=0)text=null;}catch(java.nio.charset.CharacterCodingException binary){/* Preserve the original bytes, not replacement text. */}
        JSONObject entry=new JSONObject().put("encoding","base64").put("contentBase64",Base64.getEncoder().encodeToString(retained)).put("kind",text==null?"binary":"text").put("sizeBytes",retained.length).put("sha256",sha256(retained));
        commitEntry(projectId,path,entry,expectedVersion,false);return snapshot(projectId,path,0);
    }
    public synchronized FileSnapshot writeBytes(FileSnapshot expected,byte[] bytes)throws Exception{requireIssued(expected);return writeBytes(expected.projectId,expected.path,bytes,expected.version,expected.sha256);}
    public synchronized FileSnapshot delete(String projectId,String path,int expectedVersion,String expectedSha256)throws Exception {
        path=path(path);FileSnapshot current=snapshot(projectId,path,0);requireExpected(current,expectedVersion,expectedSha256);if(current.tombstone)throw new IllegalArgumentException("文件不存在或已经删除");
        commitEntry(projectId,path,new JSONObject().put("kind","tombstone").put("sha256","absent").put("sizeBytes",0).put("content",""),expectedVersion,true);return snapshot(projectId,path,0);
    }
    public synchronized FileSnapshot delete(FileSnapshot expected)throws Exception{requireIssued(expected);return delete(expected.projectId,expected.path,expected.version,expected.sha256);}
    public void requireIssued(FileSnapshot value){if(value==null||value.issuer!=this)throw new SecurityException("文件快照不是这个原工作区读取的对象");}
    private static void requireExpected(FileSnapshot current,int version,String sha){if(version<0||version!=current.version||sha==null||!sha.equals(current.sha256))throw new IllegalStateException("文件版本或SHA已变化；保留当前文件，请读取最新版本后重新审阅");}
    private static byte[] entryBytes(JSONObject entry)throws Exception {
        if(entry.has("encoding")&&!"base64".equals(entry.optString("encoding")))throw new SecurityException("原版本编码未知，未猜测解码");byte[] bytes;if("base64".equals(entry.optString("encoding"))){String value=entry.getString("contentBase64");if(value.length()>((MAX_FILE_BYTES+2)/3)*4)throw new IllegalStateException("原版本字节超限");bytes=Base64.getDecoder().decode(value);if(!Base64.getEncoder().encodeToString(bytes).equals(value))throw new SecurityException("原版本字节编码不完整");}
        else {java.nio.ByteBuffer encoded=StandardCharsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(entry.getString("content")));bytes=new byte[encoded.remaining()];encoded.get(bytes);}
        if(bytes.length>MAX_FILE_BYTES||entry.has("sizeBytes")&&entry.getLong("sizeBytes")!=bytes.length)throw new SecurityException("原版本大小与完整字节不同");return bytes;
    }
    private static String sha256(byte[] bytes)throws Exception{StringBuilder result=new StringBuilder();for(byte value:MessageDigest.getInstance("SHA-256").digest(bytes))result.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return result.toString();}
    public synchronized JSONArray projects()throws Exception{try{JSONObject original=JsonFiles.read(catalog);JSONArray projects=original.optJSONArray("projects");if(projects==null)projects=new JSONArray();publishNativeProjects(projects);return projects;}catch(Exception unreadable){nativeProjects=null;throw unreadable;}}
    public synchronized JSONObject create(String name)throws Exception {
        if(name==null||name.trim().isEmpty()||name.length()>80)throw new IllegalArgumentException("项目名称需为1至80字");
        JSONArray all=projects();if(all.length()>=20)throw new IllegalStateException("最多保留20个项目");
        JSONObject project=new JSONObject().put("id",UUID.randomUUID().toString()).put("name",name.trim()).put("createdAt",System.currentTimeMillis());
        all.put(project);nativeProjects=null;recordWrite.write(catalog,new JSONObject().put("projects",all));publishNativeProjects(all);return JsonFiles.copy(project);
    }
    /** Native package admission only: retries retain the same new project, never overwrite an old one. */
    public synchronized JSONObject createImported(String name,String importClientId,String packageSha)throws Exception {
        if(name==null||name.trim().isEmpty()||name.length()>80||importClientId==null||!importClientId.matches("[a-f0-9-]{36}")||packageSha==null||!packageSha.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("项目包导入名称或原动作身份无效");
        String id=UUID.nameUUIDFromBytes(("project-package-import:"+importClientId).getBytes(StandardCharsets.UTF_8)).toString();JSONArray all=projects();
        for(int i=0;i<all.length();i++){JSONObject row=all.getJSONObject(i);if(id.equals(row.optString("id"))){JSONObject original=row.optJSONObject("sourceImport");if(original==null||!importClientId.equals(original.optString("clientId"))||!packageSha.equals(original.optString("packageSha256"))||!name.trim().equals(row.optString("name")))throw new SecurityException("原导入身份与已有项目不同，未覆盖项目");return JsonFiles.copy(row);}}
        if(all.length()>=20)throw new IllegalStateException("最多保留20个项目；原项目包保留，未自动删除项目");
        JSONObject project=new JSONObject().put("id",id).put("name",name.trim()).put("createdAt",System.currentTimeMillis()).put("sourceImport",new JSONObject().put("clientId",importClientId).put("packageSha256",packageSha).put("executionAuthorityRestored",false));
        all.put(project);nativeProjects=null;recordWrite.write(catalog,new JSONObject().put("projects",all));publishNativeProjects(all);return JsonFiles.copy(project);
    }
    public synchronized JSONArray list(String projectId)throws Exception {
        File folder=project(projectId);JSONArray result=new JSONArray();File[] files=committedRecords(folder);
        if(files!=null){Arrays.sort(files);for(File file:files){JSONObject head=JsonFiles.read(file);if(head.optBoolean("deleted"))continue;result.put(metadata(head));}}
        return result;
    }
    public synchronized JSONObject read(String projectId,String path,int revision)throws Exception {
        FileSnapshot actual=snapshot(projectId,path,revision);if(actual.tombstone)throw new IllegalStateException(actual.version==0?"文件不存在":"此版本已删除文件");
        JSONObject out=actual.json().put("deleted",false);
        if(!"text".equals(actual.kind))return out.put("contentAvailable",false).put("requiresNativeByteRead",true).put("message","这是原二进制版本；未解码成文字或改用其他文件");
        String content=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(actual.bytes())).toString();
        if(content.length()>65536)return out.put("contentAvailable",false).put("requiresNativeByteRead",true).put("message","原UTF-8版本超过文字接口65536字符上限；完整字节仍在原版本中");
        return out.put("content",content).put("contentAvailable",true);
    }
    public synchronized JSONObject write(String projectId,String path,String content,int expectedVersion,boolean deleted)throws Exception {
        path=path(path);if(content==null||content.length()>65536)throw new IllegalArgumentException("单文件最多65536字符");
        FileSnapshot current=snapshot(projectId,path,0);if(current.version!=expectedVersion)throw new IllegalStateException("版本冲突，请重新读取文件后保存（当前版本"+current.version+"）");
        if(deleted){delete(projectId,path,expectedVersion,current.sha256);return metadata(head(projectId,path)).put("content","");}
        // A valid source file is strict UTF-8; incomplete IME code units belong in the private draft, not a saved source.
        java.nio.ByteBuffer encoded=StandardCharsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(content));byte[] bytes=new byte[encoded.remaining()];encoded.get(bytes);
        commitEntry(projectId,path,new JSONObject().put("content",content).put("kind","text").put("sha256",sha256(bytes)).put("sizeBytes",bytes.length),expectedVersion,false);
        return metadata(head(projectId,path)).put("content",content);
    }
    private void commitEntry(String projectId,String path,JSONObject value,int expectedVersion,boolean deleted)throws Exception {
        File folder=project(projectId);JSONObject head=head(projectId,path);int current=head.optInt("version");
        if(current!=expectedVersion)throw new IllegalStateException("版本冲突，请重新读取原文件");
        if(!deleted&&(current==0||head.optBoolean("deleted"))&&list(projectId).length()>=100)throw new IllegalStateException("项目最多100个文件");
        JSONObject entry=JsonFiles.copy(value).put("revisionId",UUID.randomUUID().toString()).put("version",current+1).put("deleted",deleted).put("at",System.currentTimeMillis());
        JSONArray history=head.optJSONArray("history"),kept=new JSONArray();if(history!=null)for(int i=Math.max(0,history.length()-7);i<history.length();i++)kept.put(history.get(i));kept.put(entry);
        JSONObject next=new JSONObject().put("path",path).put("revisionId",entry.getString("revisionId")).put("version",current+1).put("deleted",deleted).put("history",kept).put("updatedAt",entry.getLong("at"));
        long total=next.toString().getBytes(StandardCharsets.UTF_8).length;File target=new File(folder,key(path)+".json");File[] records=committedRecords(folder);if(records!=null)for(File record:records)if(!record.equals(target))total+=record.length();
        if(total>MAX_PROJECT_RECORD_BYTES)throw new IllegalStateException("项目原版本内容已达4MiB上限（含二进制编码和最近8版）；没有保存或自动删除旧版本");
        updateNativeHead(projectId,path,null,false);
        recordWrite.write(target,next);
        updateNativeHead(projectId,path,new FileSnapshot(this,projectId,path,entry),false);
    }
    public synchronized JSONArray history(String projectId,String path)throws Exception {
        JSONObject head=head(projectId,path);JSONArray result=new JSONArray(),all=head.optJSONArray("history");
        if(all!=null)for(int i=0;i<all.length();i++){JSONObject v=all.getJSONObject(i);FileSnapshot pin=new FileSnapshot(this,projectId,path,v);result.put(pin.json().put("at",v.getLong("at")).put("deleted",pin.tombstone).put("characters",v.optString("content").length()));}
        return result;
    }
    /** A consistent, portable snapshot of actual committed text files; never includes app-private host paths. */
    public synchronized JSONObject export(String projectId)throws Exception {
        project(projectId);JSONObject definition=null;JSONArray projects=projects();
        for(int i=0;i<projects.length();i++)if(projectId.equals(projects.getJSONObject(i).getString("id")))definition=projects.getJSONObject(i);
        JSONArray files=new JSONArray(),omitted=new JSONArray(),listed=list(projectId);long characters=0;
        for(int i=0;i<listed.length();i++){
            JSONObject info=listed.getJSONObject(i),content=read(projectId,info.getString("path"),info.getInt("version"));if(!content.optBoolean("contentAvailable")){omitted.put(new JSONObject().put("path",info.getString("path")).put("version",info.getInt("version")).put("sha256",content.getString("sha256")).put("reason","not_representable_by_legacy_text_export"));continue;}
            characters+=content.getString("content").length();if(characters>1_048_576)throw new IllegalStateException("项目导出超过1Mi字符，请分开保存文件");
            files.put(new JSONObject().put("path",info.getString("path")).put("content",content.getString("content")).put("version",info.getInt("version")).put("revisionId",info.getString("revisionId")));
        }
        return new JSONObject().put("format","pocketworld.project.v1").put("project",JsonFiles.copy(definition)).put("files",files).put("createdAt",System.currentTimeMillis()).put("characters",characters).put("includesHistory",false).put("omitted",omitted).put("complete",omitted.length()==0).put("binaryBytesIncluded",false);
    }
    public synchronized JSONObject diff(String projectId,String path,int from)throws Exception {
        JSONObject old=read(projectId,path,from),now=read(projectId,path,0);
        return compare(path,old.getString("content"),now.getString("content"),"v"+from,"v"+now.getInt("version")).put("fromVersion",from).put("version",now.getInt("version"));
    }
    public static JSONObject compare(String path,String before,String after,String beforeLabel,String afterLabel)throws Exception{
        String[] a=before.split("\n",-1),b=after.split("\n",-1);int prefix=0,suffix=0;
        while(prefix<Math.min(a.length,b.length)&&a[prefix].equals(b[prefix]))prefix++;
        while(suffix<a.length-prefix&&suffix<b.length-prefix&&a[a.length-1-suffix].equals(b[b.length-1-suffix]))suffix++;
        StringBuilder out=new StringBuilder();
        if(prefix<a.length||prefix<b.length){out.append("--- ").append(beforeLabel).append('/').append(path).append("\n+++ ").append(afterLabel).append('/').append(path).append("\n@@ -").append(prefix+1).append(',').append(a.length-prefix-suffix).append(" +").append(prefix+1).append(',').append(b.length-prefix-suffix).append(" @@\n");for(int i=prefix;i<a.length-suffix;i++)out.append('-').append(a[i]).append('\n');for(int i=prefix;i<b.length-suffix;i++)out.append('+').append(b[i]).append('\n');}
        boolean truncated=out.length()>65536;return new JSONObject().put("path",path).put("diff",truncated?out.substring(0,65536):out.toString()).put("truncated",truncated);
    }
    private JSONObject head(String projectId,String relative)throws Exception{
        relative=path(relative);try{
            JSONObject actual=JsonFiles.read(new File(project(projectId),key(relative)+".json"));
            if(nativeHeads.containsKey(nativeKey(projectId,relative))){
                FileSnapshot current;
                if(actual.length()==0)current=new FileSnapshot(this,projectId,relative,new JSONObject());
                else {JSONArray history=actual.getJSONArray("history");JSONObject latest=history.getJSONObject(history.length()-1);if(!relative.equals(actual.getString("path"))||actual.getInt("version")!=latest.getInt("version")||!actual.getString("revisionId").equals(latest.getString("revisionId"))||actual.optBoolean("deleted")!=latest.optBoolean("deleted"))throw new SecurityException("原项目head与实际最新版本不一致");current=new FileSnapshot(this,projectId,relative,latest);}
                updateNativeHead(projectId,relative,current,false);
            }
            return actual;
        }catch(Exception unreadable){updateNativeHead(projectId,relative,null,false);throw unreadable;}
    }
    private File project(String id)throws Exception {
        if(id==null||!id.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("项目ID无效");
        JSONArray all=projects();boolean exists=false;for(int i=0;i<all.length();i++)if(id.equals(all.getJSONObject(i).optString("id")))exists=true;
        if(!exists)throw new IllegalArgumentException("项目不存在");File folder=new File(root,id);if(!folder.isDirectory()&&!folder.mkdirs())throw new IllegalStateException("无法创建项目目录");return folder;
    }
    public static String path(String raw) {
        if(raw==null||raw.length()>240||raw.startsWith("/")||raw.startsWith("\\")||raw.contains("\\")||raw.contains(":"))throw new IllegalArgumentException("文件路径无效");
        String[] parts=raw.split("/",-1);for(String p:parts)if(p.isEmpty()||p.equals(".")||p.equals("..")||p.chars().anyMatch(c->c<32))throw new IllegalArgumentException("路径不能越过项目目录");return raw;
    }
    private static String key(String path)throws Exception {byte[] bytes=MessageDigest.getInstance("SHA-256").digest(path.getBytes(StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();for(byte value:bytes)b.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return b.toString();}
    private static File[] committedRecords(File folder){return folder.listFiles(file->file.isFile()&&file.getName().matches("[a-f0-9]{64}\\.json"));}
    private static JSONObject metadata(JSONObject head)throws Exception{JSONObject latest=head.getJSONArray("history").getJSONObject(head.getJSONArray("history").length()-1);byte[] bytes=head.optBoolean("deleted")?new byte[0]:entryBytes(latest);return new JSONObject().put("path",head.getString("path")).put("revisionId",head.optString("revisionId")).put("version",head.getInt("version")).put("deleted",head.optBoolean("deleted")).put("updatedAt",head.optLong("updatedAt")).put("sha256",head.optBoolean("deleted")?"absent":sha256(bytes)).put("kind",head.optBoolean("deleted")?"tombstone":latest.optString("kind","text")).put("sizeBytes",bytes.length);}
}
