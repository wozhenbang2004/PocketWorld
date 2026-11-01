package dev.pocketengine.features.workspace;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Immutable text deliverables freeze committed file bytes independently of the editable revision window. */
public final class WorkspaceArtifacts {
    public static final long MAX_PROJECT_PACKAGE_BYTES=64L*1024*1024;
    private final File root,catalog;
    private final VersionedWorkspace workspace;
    public WorkspaceArtifacts(File root,VersionedWorkspace workspace){this.root=root;this.catalog=new File(root,"catalog.json");this.workspace=workspace;}
    private JSONArray catalog()throws Exception {JSONArray records=JsonFiles.read(catalog).optJSONArray("artifacts");return records==null?new JSONArray():records;}
    public synchronized JSONArray list(String projectId)throws Exception {
        JSONArray all=catalog(),selected=new JSONArray();for(int i=0;i<all.length();i++){JSONObject record=all.getJSONObject(i);if(projectId==null||projectId.isEmpty()||projectId.equals(record.optString("projectId")))selected.put(JsonFiles.copy(record));}return selected;
    }
    public synchronized JSONObject create(String projectId,String path,int version,JSONObject hostIdentity)throws Exception {
        JSONObject source=workspace.read(projectId,path,version);JSONArray all=catalog();if(all.length()>=200)throw new IllegalStateException("最多保留200份文件成果，请先整理已有成果");
        String content=source.getString("content"),id=UUID.randomUUID().toString();
        JSONObject artifact=new JSONObject().put("id",id).put("kind","text_file").put("projectId",projectId).put("path",VersionedWorkspace.path(path)).put("sourceVersion",source.getInt("version")).put("sourceRevisionId",source.getString("revisionId")).put("mimeType",mime(path)).put("createdAt",System.currentTimeMillis()).put("characters",content.length()).put("sha256",hash(content)).put("content",content);
        String task=hostIdentity.optString("_taskId"),run=hostIdentity.optString("_runId");
        if(!task.isEmpty()||!run.isEmpty()){
            if(!task.matches("[a-f0-9-]{36}")||!run.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("成果关联需由宿主提供有效taskId与runId");
            artifact.put("taskId",task).put("runId",run).put("source","task_workspace");
        }else artifact.put("source","owner_workspace");
        File record=new File(root,id+".json");long total=artifact.toString().getBytes(StandardCharsets.UTF_8).length;File[] retained=root.listFiles(file->file.isFile()&&file.getName().matches("[a-f0-9-]{36}\\.json"));if(retained!=null)for(File file:retained)total+=file.length();
        if(total>8*1024*1024)throw new IllegalStateException("文件成果已达8MiB上限，请先整理已有成果");
        JsonFiles.write(record,artifact);JSONObject metadata=metadata(artifact);all.put(metadata);
        try{JsonFiles.write(catalog,new JSONObject().put("artifacts",all));}catch(Exception failure){Files.deleteIfExists(record.toPath());throw failure;}
        return JsonFiles.copy(metadata);
    }
    public synchronized JSONObject read(String id)throws Exception {
        if(id==null||!id.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("成果ID无效");
        boolean found=false;JSONArray all=catalog();for(int i=0;i<all.length();i++)if(id.equals(all.getJSONObject(i).optString("id")))found=true;if(!found)throw new IllegalArgumentException("文件成果不存在");
        JSONObject artifact=JsonFiles.read(new File(root,id+".json"));if(!id.equals(artifact.optString("id"))||!hash(artifact.getString("content")).equals(artifact.optString("sha256")))throw new IllegalStateException("文件成果内容校验失败，未用其他内容冒充");return artifact;
    }
    /** Native active-tree selection creates another typed version in this same artifact catalog. */
    public synchronized JSONObject createProjectPackage(dev.pocketengine.features.local.project.ProjectPackageArchive.Frozen frozen,String ownerActionId)throws Exception {
        if(ownerActionId==null||!ownerActionId.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("缺少这次原生文件快照动作身份");
        String id=UUID.nameUUIDFromBytes(("project-package:"+ownerActionId).getBytes(StandardCharsets.UTF_8)).toString();
        JSONObject manifest=frozen.manifest(),source=manifest.getJSONObject("source");JSONArray all=catalog();int sequence=1;
        boolean projectExists=false;JSONArray projects=workspace.projects();for(int i=0;i<projects.length();i++)if(source.getString("projectId").equals(projects.getJSONObject(i).getString("id")))projectExists=true;if(!projectExists)throw new IllegalArgumentException("原项目不存在，不能把外来描述冒充本机项目成果");
        for(int i=0;i<all.length();i++){
            JSONObject prior=all.getJSONObject(i);
            if(id.equals(prior.optString("id"))){if("retired".equals(prior.optString("availability")))throw new IllegalStateException("原固定成果已明确撤下，不重放此保存动作");if(!"project_bundle".equals(prior.optString("kind"))||!frozen.identity.equals(prior.optString("manifestIdentity")))throw new SecurityException("这次原快照动作已对应其他文件版本，未另存或覆盖");readProjectPackage(id,prior.getString("sha256"));return JsonFiles.copy(prior);}
            if("project_bundle".equals(prior.optString("kind"))&&source.getString("projectId").equals(prior.optString("projectId")))sequence=Math.max(sequence,prior.optInt("versionSequence")+1);
        }
        if(all.length()>=200)throw new IllegalStateException("最多保留200份文件成果，请先明确整理已有成果");
        if(!root.isDirectory()&&!root.mkdirs())throw new IllegalStateException("无法创建原文件成果目录");
        File archive=new File(root,id+".project.zip"),record=new File(root,id+".project.json"),pending=File.createTempFile("project-package-",".pending",root);
        boolean published=false,catalogAttempted=false,createdPayload=false;
        try{
            if(Files.exists(archive.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)||Files.exists(record.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)){
                if(!Files.isSymbolicLink(record.toPath())&&record.isFile()&&JsonFiles.read(record).optBoolean("retired"))throw new IllegalStateException("原固定成果已明确移除，保留原动作删除记录，不重放生成");
                if(Files.isSymbolicLink(archive.toPath())||Files.isSymbolicLink(record.toPath())||!archive.isFile()||!record.isFile())throw new SecurityException("原快照动作有未确认文件，未覆盖");
                JSONObject retained=JsonFiles.read(record),prior=retained.getJSONObject("artifact");
                if(!id.equals(prior.optString("id"))||!ownerActionId.equals(prior.optString("ownerActionId"))||!frozen.identity.equals(prior.optString("manifestIdentity"))||!frozen.identity.equals(dev.pocketengine.features.local.project.ProjectSources.digest(retained.getJSONObject("manifest")))||archive.length()!=prior.getLong("sizeBytes")||!prior.getString("sha256").equals(hashFile(archive))||!frozen.identity.equals(dev.pocketengine.features.local.project.ProjectPackageArchive.read(archive).identity))throw new SecurityException("原快照动作的未登记文件与所选清单不同，未覆盖或另存");
                all.put(prior);catalogAttempted=true;JsonFiles.write(catalog,new JSONObject().put("artifacts",all));published=true;return JsonFiles.copy(prior);
            }
            try(java.io.FileOutputStream output=new java.io.FileOutputStream(pending)){dev.pocketengine.features.local.project.ProjectPackageArchive.write(frozen,output);output.getFD().sync();}
            String sha=hashFile(pending);
            JSONObject artifact=new JSONObject().put("id",id).put("kind","project_bundle").put("source","owner_active_project").put("projectId",source.getString("projectId")).put("checkoutId",source.getString("checkoutId"))
                .put("sourceDigest",source.getString("sourceDigest")).put("manifestIdentity",frozen.identity).put("versionId",id).put("versionSequence",sequence).put("ownerActionId",ownerActionId)
                .put("mode",manifest.getString("mode")).put("entryPath",manifest.optString("entryPath")).put("previewRoot",manifest.optString("previewRoot")).put("fileCount",manifest.getJSONArray("files").length()).put("expandedBytes",manifest.getLong("bytes"))
                .put("omittedCount",manifest.getJSONArray("omitted").length()).put("name",manifest.getJSONObject("project").getString("name")).put("mimeType","application/zip").put("createdAt",System.currentTimeMillis())
                .put("sizeBytes",pending.length()).put("sha256",sha).put("persisted",true).put("businessVerified",false).put("atomicSourceFence",false);
            JSONObject retainedRecord=new JSONObject().put("artifact",artifact).put("manifest",manifest);
            long total=pending.length()+retainedRecord.toString().getBytes(StandardCharsets.UTF_8).length;File[] retained=root.listFiles(file->file.isFile()&&file.getName().matches("[a-f0-9-]{36}\\.project\\.(zip|json)"));if(retained==null)throw new IllegalStateException("无法核对文件成果空间");for(File file:retained)if(!file.equals(archive)&&!file.equals(record))total+=file.length();
            if(total>MAX_PROJECT_PACKAGE_BYTES)throw new IllegalStateException("固定项目包及清单已达64MiB；未自动删除旧成果或项目");
            Files.move(pending.toPath(),archive.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE);createdPayload=true;
            JsonFiles.write(record,retainedRecord);all.put(artifact);
            catalogAttempted=true;JsonFiles.write(catalog,new JSONObject().put("artifacts",all));published=true;return JsonFiles.copy(artifact);
        }finally{Files.deleteIfExists(pending.toPath());if(createdPayload&&!published&&!catalogAttempted){Files.deleteIfExists(record.toPath());Files.deleteIfExists(archive.toPath());}}
    }
    public synchronized JSONObject projectPackage(String id,String expectedSha)throws Exception {
        JSONObject artifact=packageMetadata(id,expectedSha);var frozen=readProjectPackage(id,expectedSha);return JsonFiles.copy(artifact).put("manifest",frozen.manifest());
    }
    public synchronized JSONObject findProjectPackage(String ownerActionId,String projectId,String checkoutId,String manifestIdentity)throws Exception {
        if(ownerActionId==null||!ownerActionId.matches("[a-f0-9-]{36}")||manifestIdentity==null||!manifestIdentity.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("缺少原快照动作定位");
        String id=UUID.nameUUIDFromBytes(("project-package:"+ownerActionId).getBytes(StandardCharsets.UTF_8)).toString();JSONArray all=catalog();
        for(int i=0;i<all.length();i++){JSONObject row=all.getJSONObject(i);if(id.equals(row.optString("id"))){if(!projectId.equals(row.optString("projectId"))||!checkoutId.equals(row.optString("checkoutId"))||!manifestIdentity.equals(row.optString("manifestIdentity")))throw new SecurityException("原快照动作与所选项目版本不一致");readProjectPackage(id,row.getString("sha256"));return JsonFiles.copy(row).put("found",true);}}
        return new JSONObject().put("found",false).put("ownerActionId",ownerActionId);
    }
    public synchronized dev.pocketengine.features.local.project.ProjectPackageArchive.Frozen readProjectPackage(String id,String expectedSha)throws Exception {
        JSONObject meta=packageMetadata(id,expectedSha);File archive=new File(root,id+".project.zip"),record=new File(root,id+".project.json");
        if(Files.isSymbolicLink(record.toPath())||!record.isFile())throw new IllegalStateException("原固定项目包登记不可用");JSONObject retained=JsonFiles.read(record);
        if(!dev.pocketengine.features.local.project.ProjectSources.canonical(meta).equals(dev.pocketengine.features.local.project.ProjectSources.canonical(retained.getJSONObject("artifact"))))throw new SecurityException("原固定项目包登记与所选版本不一致");
        if(Files.isSymbolicLink(archive.toPath())||!archive.isFile()||archive.length()!=meta.getLong("sizeBytes")||!expectedSha.equals(hashFile(archive)))throw new IllegalStateException("所选固定项目包字节不可用或已变化，未改用最新成果");
        var frozen=dev.pocketengine.features.local.project.ProjectPackageArchive.read(archive);JSONObject source=frozen.manifest().getJSONObject("source");
        if(!frozen.identity.equals(meta.getString("manifestIdentity"))||!frozen.identity.equals(dev.pocketengine.features.local.project.ProjectSources.digest(retained.getJSONObject("manifest")))||!source.getString("projectId").equals(meta.getString("projectId"))||!source.getString("checkoutId").equals(meta.getString("checkoutId"))||!source.getString("sourceDigest").equals(meta.getString("sourceDigest")))throw new SecurityException("固定成果与原项目版本不一致");
        if(!expectedSha.equals(hashFile(archive))||archive.length()!=meta.getLong("sizeBytes"))throw new SecurityException("读取期间原固定项目包已变化，未交付其他版本");
        return frozen;
    }
    /** Stream exactly the owner's pinned original version. A failed postcheck never confirms export. */
    public synchronized long exportProjectPackage(String id,String expectedSha,java.io.OutputStream output)throws Exception {
        JSONObject meta=packageMetadata(id,expectedSha);readProjectPackage(id,expectedSha);
        java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");long count=0;File archive=new File(root,id+".project.zip");
        try(java.io.InputStream input=Files.newInputStream(archive.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)){
            byte[] buffer=new byte[8192];for(int n;(n=input.read(buffer))>=0;){if(n==0)continue;count+=n;if(count>dev.pocketengine.features.local.project.ProjectPackageArchive.MAX_ARCHIVE_BYTES)throw new IllegalStateException("固定项目包导出超限");digest.update(buffer,0,n);output.write(buffer,0,n);}
        }
        if(count!=meta.getLong("sizeBytes")||!expectedSha.equals(hex(digest.digest())))throw new IllegalStateException("导出未完整确认，目标可能已有部分内容，请核对原固定成果");
        packageMetadata(id,expectedSha);output.flush();return count;
    }
    /** Explicit owner retirement of one package only. A pending tombstone keeps failed cleanup retryable. */
    public synchronized JSONObject removeProjectPackage(String id,String expectedSha)throws Exception {
        if(id==null||!id.matches("[a-f0-9-]{36}")||expectedSha==null||!expectedSha.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("请选择原固定项目包");
        JSONArray all=catalog();JSONObject selected=null;
        for(int i=0;i<all.length();i++)if(id.equals(all.getJSONObject(i).optString("id")))selected=all.getJSONObject(i);
        if(selected==null)throw new IllegalStateException("原固定项目包已不在目录中，未删除其他文件");
        if(!"project_bundle".equals(selected.optString("kind"))||!"owner_active_project".equals(selected.optString("source"))||!expectedSha.equals(selected.optString("sha256"))||selected.has("taskId")||selected.has("runId"))throw new SecurityException("只能明确移除这个原私人文件快照，未删除任务成果或其他类型");
        selected.put("availability","retired").put("removalRequested",true);if(!selected.has("retiredAt"))selected.put("retiredAt",System.currentTimeMillis());JsonFiles.write(catalog,new JSONObject().put("artifacts",all));
        // Deleting the named entry never follows a symlink. Source checkouts/environment/receipts are elsewhere.
        try{Files.deleteIfExists(new File(root,id+".project.zip").toPath());JsonFiles.write(new File(root,id+".project.json"),new JSONObject().put("retired",true).put("id",id).put("sha256",expectedSha).put("ownerActionId",selected.getString("ownerActionId")).put("retiredAt",selected.getLong("retiredAt")));}
        catch(Exception pending){return JsonFiles.copy(selected).put("payloadCleanupComplete",false).put("message","原固定版本已撤下；文件清理尚未完整确认，可核对后再清理这个原版本");}
        JSONArray retained=new JSONArray();for(int i=0;i<all.length();i++)if(!id.equals(all.getJSONObject(i).optString("id")))retained.put(all.getJSONObject(i));JsonFiles.write(catalog,new JSONObject().put("artifacts",retained));
        return new JSONObject().put("id",id).put("sha256",expectedSha).put("removed",true).put("payloadCleanupComplete",true).put("sourceProjectRemoved",false).put("environmentRemoved",false);
    }
    private JSONObject packageMetadata(String id,String sha)throws Exception {
        if(id==null||!id.matches("[a-f0-9-]{36}")||sha==null||!sha.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("请选择真实固定项目包版本");
        JSONArray all=catalog();for(int i=0;i<all.length();i++){JSONObject row=all.getJSONObject(i);if(id.equals(row.optString("id"))){if("retired".equals(row.optString("availability")))throw new IllegalStateException("此固定成果已明确撤下，未改用其他版本");if(!"project_bundle".equals(row.optString("kind"))||!sha.equals(row.optString("sha256")))throw new SecurityException("原文件成果版本不匹配");return JsonFiles.copy(row);}}
        throw new IllegalArgumentException("所选固定项目包不存在，未改用其它版本");
    }
    private static String hashFile(File file)throws Exception {java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");try(java.io.InputStream input=Files.newInputStream(file.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)){byte[] b=new byte[8192];long count=0;for(int n;(n=input.read(b))>=0;){if(n==0)continue;count+=n;if(count>dev.pocketengine.features.local.project.ProjectPackageArchive.MAX_ARCHIVE_BYTES)throw new IllegalStateException("固定项目包超过20MiB");digest.update(b,0,n);}}return hex(digest.digest());}
    private static String hex(byte[] bytes){StringBuilder result=new StringBuilder();for(byte value:bytes)result.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return result.toString();}
    public synchronized JSONObject prepareExport(JSONObject input)throws Exception {
        String artifactId=input.optString("artifactId"),projectId=input.optString("projectId"),path=input.optString("path");
        if(!artifactId.isEmpty()){
            if(!projectId.isEmpty()||!path.isEmpty())throw new IllegalArgumentException("请分别选择一份成果或项目文件导出");
            JSONObject artifact=read(artifactId);return document(filename(artifact.getString("path")),artifact.getString("mimeType"),artifact.getString("content")).put("artifactId",artifactId);
        }
        if(projectId.isEmpty())throw new IllegalArgumentException("请选择要导出的项目、文件或成果");
        if(!path.isEmpty()){JSONObject file=workspace.read(projectId,path,input.optInt("version",0));return document(filename(path),mime(path),file.getString("content")).put("projectId",projectId).put("revisionId",file.getString("revisionId"));}
        JSONObject project=workspace.export(projectId);return document(filename(project.getJSONObject("project").getString("name"))+".pocketworld.json","application/json",project.toString(2)).put("projectId",projectId);
    }
    public synchronized JSONObject diff(String artifactId)throws Exception {
        JSONObject artifact=read(artifactId),current=workspace.read(artifact.getString("projectId"),artifact.getString("path"),0);
        return VersionedWorkspace.compare(artifact.getString("path"),artifact.getString("content"),current.getString("content"),"artifact/"+artifactId,"v"+current.getInt("version")).put("artifactId",artifactId).put("projectId",artifact.getString("projectId")).put("version",current.getInt("version")).put("revisionId",current.getString("revisionId"));
    }
    private static JSONObject metadata(JSONObject artifact)throws Exception {JSONObject copy=JsonFiles.copy(artifact);copy.remove("content");return copy;}
    private static JSONObject document(String name,String mime,String content)throws Exception{return new JSONObject().put("suggestedName",name).put("mimeType",mime).put("content",content).put("encoding","UTF-8").put("sizeBytes",content.getBytes(StandardCharsets.UTF_8).length).put("sha256",hash(content)).put("requiresDocumentPicker",true);}
    private static String filename(String path){String name=path.substring(path.lastIndexOf('/')+1).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_").trim();return name.isEmpty()?"PocketWorld-export.txt":name;}
    private static String mime(String path){String lower=path.toLowerCase(java.util.Locale.ROOT);if(lower.endsWith(".json"))return "application/json";if(lower.endsWith(".md"))return "text/markdown";if(lower.endsWith(".html")||lower.endsWith(".htm"))return "text/html";if(lower.endsWith(".csv"))return "text/csv";return "text/plain";}
    private static String hash(String content)throws Exception {byte[] bytes=MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));StringBuilder hash=new StringBuilder();for(byte value:bytes)hash.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return hash.toString();}
}
