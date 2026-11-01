package dev.pocketengine.features.workspace;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Each file head and retained revisions are one atomic record; paths never become host paths. */
public final class VersionedWorkspace {
    private final File root,catalog;
    public VersionedWorkspace(File root){this.root=root;this.catalog=new File(root,"projects.json");}
    public synchronized JSONArray projects()throws Exception{return JsonFiles.read(catalog).optJSONArray("projects")==null?new JSONArray():JsonFiles.read(catalog).getJSONArray("projects");}
    public synchronized JSONObject create(String name)throws Exception {
        if(name==null||name.trim().isEmpty()||name.length()>80)throw new IllegalArgumentException("项目名称需为1至80字");
        JSONArray all=projects();if(all.length()>=20)throw new IllegalStateException("最多保留20个项目");
        JSONObject project=new JSONObject().put("id",UUID.randomUUID().toString()).put("name",name.trim()).put("createdAt",System.currentTimeMillis());
        all.put(project);JsonFiles.write(catalog,new JSONObject().put("projects",all));return JsonFiles.copy(project);
    }
    /** Native package admission only: retries retain the same new project, never overwrite an old one. */
    public synchronized JSONObject createImported(String name,String importClientId,String packageSha)throws Exception {
        if(name==null||name.trim().isEmpty()||name.length()>80||importClientId==null||!importClientId.matches("[a-f0-9-]{36}")||packageSha==null||!packageSha.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("项目包导入名称或原动作身份无效");
        String id=UUID.nameUUIDFromBytes(("project-package-import:"+importClientId).getBytes(StandardCharsets.UTF_8)).toString();JSONArray all=projects();
        for(int i=0;i<all.length();i++){JSONObject row=all.getJSONObject(i);if(id.equals(row.optString("id"))){JSONObject original=row.optJSONObject("sourceImport");if(original==null||!importClientId.equals(original.optString("clientId"))||!packageSha.equals(original.optString("packageSha256"))||!name.trim().equals(row.optString("name")))throw new SecurityException("原导入身份与已有项目不同，未覆盖项目");return JsonFiles.copy(row);}}
        if(all.length()>=20)throw new IllegalStateException("最多保留20个项目；原项目包保留，未自动删除项目");
        JSONObject project=new JSONObject().put("id",id).put("name",name.trim()).put("createdAt",System.currentTimeMillis()).put("sourceImport",new JSONObject().put("clientId",importClientId).put("packageSha256",packageSha).put("executionAuthorityRestored",false));
        all.put(project);JsonFiles.write(catalog,new JSONObject().put("projects",all));return JsonFiles.copy(project);
    }
    public synchronized JSONArray list(String projectId)throws Exception {
        File folder=project(projectId);JSONArray result=new JSONArray();File[] files=committedRecords(folder);
        if(files!=null){Arrays.sort(files);for(File file:files){JSONObject head=JsonFiles.read(file);if(head.optBoolean("deleted"))continue;result.put(metadata(head));}}
        return result;
    }
    public synchronized JSONObject read(String projectId,String path,int revision)throws Exception {
        JSONObject head=head(projectId,path);if(head.length()==0)throw new IllegalArgumentException("文件不存在");
        if(revision<=0)revision=head.getInt("version");
        JSONArray history=head.getJSONArray("history");
        for(int i=0;i<history.length();i++){JSONObject entry=history.getJSONObject(i);if(entry.getInt("version")==revision){if(entry.optBoolean("deleted"))throw new IllegalStateException("此版本已删除文件");return JsonFiles.copy(entry).put("path",head.getString("path"));}}
        throw new IllegalArgumentException("版本未保留；每文件保留最近8个版本");
    }
    public synchronized JSONObject write(String projectId,String path,String content,int expectedVersion,boolean deleted)throws Exception {
        path=path(path);if(content==null||content.length()>65536)throw new IllegalArgumentException("单文件最多65536字符");
        File folder=project(projectId);JSONObject head=head(projectId,path);int current=head.optInt("version");
        if(current!=expectedVersion)throw new IllegalStateException("版本冲突，请重新读取文件后保存（当前版本"+current+"）");
        if(!deleted&&(current==0||head.optBoolean("deleted"))&&list(projectId).length()>=100)throw new IllegalStateException("项目最多100个文件");
        if(deleted&&(current==0||head.optBoolean("deleted")))throw new IllegalArgumentException("文件不存在或已经删除");
        JSONArray history=head.optJSONArray("history");if(history==null)history=new JSONArray();
        JSONObject entry=new JSONObject().put("revisionId",UUID.randomUUID().toString()).put("version",current+1).put("content",deleted?"":content).put("deleted",deleted).put("at",System.currentTimeMillis());
        JSONArray kept=new JSONArray();for(int i=Math.max(0,history.length()-7);i<history.length();i++)kept.put(history.get(i));kept.put(entry);
        JSONObject next=new JSONObject().put("path",path).put("revisionId",entry.getString("revisionId")).put("version",current+1).put("deleted",deleted).put("history",kept).put("updatedAt",entry.getLong("at"));
        long total=next.toString().getBytes(StandardCharsets.UTF_8).length;File target=new File(folder,key(path)+".json");File[] records=committedRecords(folder);
        if(records!=null)for(File record:records)if(!record.equals(target))total+=record.length();
        if(total>4*1024*1024)throw new IllegalStateException("项目版本内容已达4MiB上限，请新建项目");
        JsonFiles.write(target,next);return metadata(next).put("content",deleted?"":content);
    }
    public synchronized JSONArray history(String projectId,String path)throws Exception {
        JSONObject head=head(projectId,path);JSONArray result=new JSONArray(),all=head.optJSONArray("history");
        if(all!=null)for(int i=0;i<all.length();i++){JSONObject v=all.getJSONObject(i);result.put(new JSONObject().put("revisionId",v.optString("revisionId")).put("version",v.getInt("version")).put("at",v.getLong("at")).put("deleted",v.optBoolean("deleted")).put("characters",v.optString("content").length()));}
        return result;
    }
    /** A consistent, portable snapshot of actual committed text files; never includes app-private host paths. */
    public synchronized JSONObject export(String projectId)throws Exception {
        project(projectId);JSONObject definition=null;JSONArray projects=projects();
        for(int i=0;i<projects.length();i++)if(projectId.equals(projects.getJSONObject(i).getString("id")))definition=projects.getJSONObject(i);
        JSONArray files=new JSONArray(),listed=list(projectId);long characters=0;
        for(int i=0;i<listed.length();i++){
            JSONObject info=listed.getJSONObject(i),content=read(projectId,info.getString("path"),info.getInt("version"));
            characters+=content.getString("content").length();if(characters>1_048_576)throw new IllegalStateException("项目导出超过1Mi字符，请分开保存文件");
            files.put(new JSONObject().put("path",info.getString("path")).put("content",content.getString("content")).put("version",info.getInt("version")).put("revisionId",info.getString("revisionId")));
        }
        return new JSONObject().put("format","pocketworld.project.v1").put("project",JsonFiles.copy(definition)).put("files",files).put("createdAt",System.currentTimeMillis()).put("characters",characters).put("includesHistory",false);
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
    private JSONObject head(String projectId,String path)throws Exception{return JsonFiles.read(new File(project(projectId),key(path(path))+".json"));}
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
    private static JSONObject metadata(JSONObject head)throws Exception{return new JSONObject().put("path",head.getString("path")).put("revisionId",head.optString("revisionId")).put("version",head.getInt("version")).put("deleted",head.optBoolean("deleted")).put("updatedAt",head.optLong("updatedAt"));}
}
