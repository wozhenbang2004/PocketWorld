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
    @FunctionalInterface interface CreateCatalogWrite {void write(File target,JSONObject value)throws Exception;}
    private final CreateCatalogWrite createCatalogWrite;
    public WorkspaceArtifacts(File root,VersionedWorkspace workspace){this(root,workspace,JsonFiles::write);}
    /** Package-local IO fixtures still write the real catalog before simulating a lost ACK. */
    WorkspaceArtifacts(File root,VersionedWorkspace workspace,CreateCatalogWrite createCatalogWrite){this.root=root;this.catalog=new File(root,"catalog.json");this.workspace=workspace;this.createCatalogWrite=java.util.Objects.requireNonNull(createCatalogWrite);}
    /** Only this original catalog can issue a reference; JSON metadata grants no read or execution. */
    public static final class ArtifactRef {
        private final WorkspaceArtifacts issuer;private final JSONObject metadata;
        public final String artifactId,projectId,path,sha256,revisionId,mimeType,kind;public final int version;public final long sizeBytes;public final boolean tombstone;
        private ArtifactRef(WorkspaceArtifacts issuer,JSONObject actual)throws Exception{this.issuer=issuer;metadata=JsonFiles.copy(actual);artifactId=actual.getString("id");projectId=actual.getString("projectId");path=actual.getString("path");sha256=actual.getString("sha256");revisionId=actual.getString("sourceRevisionId");version=actual.getInt("sourceVersion");mimeType=actual.getString("mimeType");kind=actual.getString("kind");sizeBytes=actual.getLong("sizeBytes");tombstone="file_tombstone".equals(kind);}
        public JSONObject json()throws Exception{return JsonFiles.copy(metadata).put("artifactId",artifactId).put("immutable",true).put("authorityAdded",false);}
    }
    public synchronized ArtifactRef freezeFile(VersionedWorkspace.FileSnapshot original,String nativeOperationId,String taskId,String runId)throws Exception {
        workspace.requireIssued(original);if(original.version<1)throw new IllegalArgumentException("从未存在的文件没有可冻结的原修订或删除回执");
        if(nativeOperationId==null||!nativeOperationId.matches("[a-f0-9-]{36}"))throw new SecurityException("缺少原native保存操作身份");
        boolean task=taskId!=null&&!taskId.isEmpty()||runId!=null&&!runId.isEmpty();if(task&&(!validId(taskId)||!validId(runId)))throw new SecurityException("成果原任务身份不完整");
        byte[] bytes=original.tombstone?new byte[0]:original.bytes();String id=UUID.nameUUIDFromBytes(("workspace-byte-artifact-v1:"+nativeOperationId+":"+original.projectId+":"+original.path+":"+original.revisionId).getBytes(StandardCharsets.UTF_8)).toString();
        JSONArray all=catalog();for(int i=0;i<all.length();i++)if(id.equals(all.getJSONObject(i).optString("id"))){ArtifactRef prior=reference(id,original.sha256);if(!original.revisionId.equals(prior.revisionId)||!nativeOperationId.equals(prior.metadata.optString("nativeOperationId"))||!java.util.Objects.equals(taskId==null?"":taskId,prior.metadata.optString("taskId"))||!java.util.Objects.equals(runId==null?"":runId,prior.metadata.optString("runId")))throw new SecurityException("原保存操作已经对应其他文件来源");return prior;}
        if(all.length()>=200)throw new IllegalStateException("最多保留200份文件成果；原文件和版本保留");
        JSONObject artifact=new JSONObject().put("id",id).put("kind",original.tombstone?"file_tombstone":"binary_file").put("tombstone",original.tombstone).put("projectId",original.projectId).put("path",original.path).put("sourceVersion",original.version).put("sourceRevisionId",original.revisionId).put("mimeType",mime(original.path)).put("createdAt",System.currentTimeMillis()).put("sha256",original.sha256).put("sizeBytes",bytes.length).put("encoding","base64").put("contentBase64",java.util.Base64.getEncoder().encodeToString(bytes)).put("nativeOperationId",nativeOperationId).put("source",task?"task_workspace":"owner_workspace");
        if(original.tombstone){artifact.remove("encoding");artifact.remove("contentBase64");}if(task)artifact.put("taskId",taskId).put("runId",runId);
        // Preserve the existing text viewer/export contract when this exact file fits its original bound.
        if("text".equals(original.kind)){String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();if(text.length()<=65536){artifact.remove("contentBase64");artifact.remove("encoding");artifact.put("kind","text_file").put("content",text).put("characters",text.length());}}
        File record=new File(root,id+".json");if(record.exists())throw new SecurityException("原保存操作已有未登记文件，未覆盖或重复保存；请核对原操作");
        long total=artifact.toString().getBytes(StandardCharsets.UTF_8).length;File[] retained=root.listFiles(file->file.isFile()&&file.getName().matches("[a-f0-9-]{36}\\.json"));if(retained!=null)for(File file:retained)total+=file.length();if(total>8L*1024*1024)throw new IllegalStateException("原文件成果存储已达8MiB（含原字节编码），未自动清理历史");
        JsonFiles.write(record,artifact);all.put(metadata(artifact));JsonFiles.write(catalog,new JSONObject().put("artifacts",all));return reference(id,original.sha256);
    }
    public synchronized ArtifactRef reference(String id,String sha)throws Exception {
        JSONObject actual=checkedFile(id,sha);JSONObject meta=metadata(actual);if(!meta.has("sizeBytes"))meta.put("sizeBytes",fileBytes(actual).length);return new ArtifactRef(this,meta);
    }
    public synchronized byte[] bytes(ArtifactRef selected)throws Exception{requireIssued(selected);if(selected.tombstone)throw new IllegalStateException("所选成果是原删除修订，没有文件字节；未导出伪空文件");JSONObject actual=checkedFile(selected.artifactId,selected.sha256);if(!selected.revisionId.equals(actual.getString("sourceRevisionId")))throw new SecurityException("原成果来源版本已变化");return fileBytes(actual);}
    public synchronized long exportFile(ArtifactRef selected,java.io.OutputStream target)throws Exception{byte[] bytes=bytes(selected);target.write(bytes);bytes(selected);return bytes.length;}
    private void requireIssued(ArtifactRef value){if(value==null||value.issuer!=this)throw new SecurityException("成果引用不是这个原目录签发的对象");}
    private static boolean validId(String value){return value!=null&&value.matches("[a-f0-9-]{36}");}
    private JSONObject checkedFile(String id,String sha)throws Exception {
        if(!validId(id)||sha==null||!sha.matches("absent|[a-f0-9]{64}"))throw new IllegalArgumentException("请选择原固定文件成果或删除修订及精确SHA");JSONObject listed=null;JSONArray all=catalog();for(int i=0;i<all.length();i++)if(id.equals(all.getJSONObject(i).optString("id")))listed=all.getJSONObject(i);
        if(listed==null||!java.util.Set.of("text_file","binary_file","file_tombstone").contains(listed.optString("kind"))||!sha.equals(listed.optString("sha256"))||"retired".equals(listed.optString("availability")))throw new SecurityException("原固定文件成果已不可用或版本不同");
        File record=new File(root,id+".json");if(Files.isSymbolicLink(record.toPath())||!record.isFile())throw new SecurityException("原固定成果记录不可用");JSONObject actual=JsonFiles.read(record);if(!id.equals(actual.optString("id"))||!dev.pocketengine.features.local.project.ProjectSources.canonical(listed).equals(dev.pocketengine.features.local.project.ProjectSources.canonical(metadata(actual)))||!sha.equals("file_tombstone".equals(actual.optString("kind"))?"absent":hashBytes(fileBytes(actual))))throw new SecurityException("原固定成果字节或登记已变化，未读取其他版本");if("file_tombstone".equals(actual.optString("kind"))&&(!actual.optBoolean("tombstone")||actual.optInt("sourceVersion")<1||actual.optLong("sizeBytes",-1)!=0||actual.has("content")||actual.has("contentBase64")||!validId(actual.optString("sourceRevisionId"))))throw new SecurityException("原删除成果不是没有正文的实际修订");if(!"file_tombstone".equals(actual.optString("kind"))&&sha.equals("absent"))throw new SecurityException("文件字节不能使用删除摘要");return actual;
    }
    private static byte[] fileBytes(JSONObject actual)throws Exception{byte[] bytes;if("base64".equals(actual.optString("encoding"))){String value=actual.getString("contentBase64");if(value.length()>((VersionedWorkspace.MAX_FILE_BYTES+2)/3)*4)throw new SecurityException("原文件成果超限");bytes=java.util.Base64.getDecoder().decode(value);if(!java.util.Base64.getEncoder().encodeToString(bytes).equals(value))throw new SecurityException("原成果字节编码无效");}else bytes=actual.getString("content").getBytes(StandardCharsets.UTF_8);if(bytes.length>VersionedWorkspace.MAX_FILE_BYTES||actual.has("sizeBytes")&&bytes.length!=actual.getLong("sizeBytes"))throw new SecurityException("原成果字节大小不符");return bytes;}
    private static String hashBytes(byte[] bytes)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private JSONArray catalog()throws Exception {JSONArray records=JsonFiles.read(catalog).optJSONArray("artifacts");return records==null?new JSONArray():records;}
    public synchronized JSONArray list(String projectId)throws Exception {
        JSONArray all=catalog(),selected=new JSONArray();for(int i=0;i<all.length();i++){JSONObject record=all.getJSONObject(i);if(projectId==null||projectId.isEmpty()||projectId.equals(record.optString("projectId")))selected.put(JsonFiles.copy(record));}return selected;
    }
    public synchronized JSONObject create(String projectId,String path,int version,JSONObject hostIdentity)throws Exception {
        JSONObject source=workspace.read(projectId,path,version);if(!source.optBoolean("contentAvailable"))throw new IllegalArgumentException("此原版本需使用native字节成果入口，未将二进制或大文件改成文字");JSONArray all=catalog();if(all.length()>=200)throw new IllegalStateException("最多保留200份文件成果，请先整理已有成果");
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
        // A failed acknowledgement cannot prove that the catalog did not publish this record.
        try{createCatalogWrite.write(catalog,new JSONObject().put("artifacts",all));}
        catch(Exception failure){throw new java.io.IOException("成果目录登记结果未知，原成果记录已保留；请仅按原成果ID "+id+" 查询，不重放保存",failure);}
        return JsonFiles.copy(metadata);
    }
    public synchronized JSONObject read(String id)throws Exception {
        if(id==null||!id.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("成果ID无效");
        boolean found=false;JSONArray all=catalog();for(int i=0;i<all.length();i++)if(id.equals(all.getJSONObject(i).optString("id")))found=true;if(!found)throw new IllegalArgumentException("文件成果不存在");
        JSONObject artifact=JsonFiles.read(new File(root,id+".json"));artifact=checkedFile(id,artifact.getString("sha256"));if(!"text_file".equals(artifact.optString("kind")))return metadata(artifact).put("contentAvailable",false).put("requiresNativeByteRead",!"file_tombstone".equals(artifact.optString("kind"))).put("message","file_tombstone".equals(artifact.optString("kind"))?"这是原文件删除修订，无正文；原历史和审阅来源仍保留，不导出为空文件":"所选成果保留的是完整原字节，请使用原生查看或导出入口");return artifact;
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
    /** Native owner backup: immutable original file metadata only. The portable
     * archive stores its bytes separately and cannot grant execution or source access. */
    public synchronized JSONObject ownerFileArchive(String id,String sha)throws Exception {
        ArtifactRef ref=reference(id,sha);return new JSONObject().put("format","pocketworld.fixed-file-archive.v1")
            .put("originalArtifactId",ref.artifactId).put("originalProjectId",ref.projectId).put("path",ref.path).put("kind",ref.kind)
            .put("sourceVersion",ref.version).put("sourceRevisionId",ref.revisionId).put("sha256",ref.sha256).put("bytes",ref.sizeBytes).put("mimeType",ref.mimeType);
    }
    /** Restores a new immutable catalog item, not an active file or the old task.
     * A missing catalog ACK after a record write remains unknown, never overwritten. */
    public synchronized JSONObject restoreOwnerFileArchive(JSONObject supplied,byte[] actual,String newProject,String nativeImportId,String sectionDigest,dev.pocketengine.core.RunControl original)throws Exception {
        JSONObject item=checkedOwnerFileArchive(supplied);requireArchiveImport(nativeImportId,sectionDigest);original.checkpoint();workspace.snapshotProject(newProject);
        String old=item.getString("originalArtifactId"),id=ownerArchiveId(nativeImportId,old);JSONObject prior=queryOwnerFileArchive(item,newProject,nativeImportId,sectionDigest);
        if(prior.getBoolean("found"))return prior;boolean tombstone="file_tombstone".equals(item.getString("kind"));
        if(actual==null||tombstone&&actual.length!=0||!tombstone&&(actual.length!=item.getLong("bytes")||!hashBytes(actual).equals(item.getString("sha256"))))throw new SecurityException("恢复原成果实际字节与审阅版本不同");
        JSONArray all=catalog();if(all.length()>=200)throw new IllegalStateException("原成果目录已满200项；没有驱逐或覆盖已有版本");
        File target=new File(root,id+".json");if(Files.exists(target.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(root.toPath())||Files.isSymbolicLink(catalog.toPath()))throw new SecurityException("原恢复成果已有未确认数据或目录变化；只查询原动作");
        String sourceRevision=UUID.nameUUIDFromBytes(("owner-archive-artifact-revision-v1:"+nativeImportId+":"+old).getBytes(StandardCharsets.UTF_8)).toString();boolean sourceAvailable=false;JSONArray versions=workspace.history(newProject,item.getString("path"));for(int i=0;i<versions.length();i++){JSONObject version=versions.getJSONObject(i);if(version.getInt("version")==item.getInt("sourceVersion")&&item.getString("sha256").equals(version.getString("sha256"))){sourceRevision=version.getString("revisionId");sourceAvailable=true;}}
        JSONObject value=new JSONObject().put("id",id).put("kind",item.getString("kind")).put("projectId",newProject).put("path",item.getString("path"))
            .put("sourceVersion",item.getInt("sourceVersion")).put("sourceRevisionId",sourceRevision).put("restoredSourceRevisionAvailable",sourceAvailable)
            .put("mimeType",item.getString("mimeType")).put("createdAt",System.currentTimeMillis()).put("sha256",item.getString("sha256")).put("sizeBytes",actual.length).put("source","owner_archive")
            .put("archiveImport",new JSONObject().put("nativeImportId",nativeImportId).put("sectionDigest",sectionDigest).put("original",item).put("executionAuthorityRestored",false));
        if(tombstone)value.put("tombstone",true);
        else if("text_file".equals(item.getString("kind"))){String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(actual)).toString();if(text.length()>65536||text.indexOf(0)>=0)throw new SecurityException("归档文字成果超出原文字格式范围");value.put("content",text).put("characters",text.length());}
        else value.put("encoding","base64").put("contentBase64",java.util.Base64.getEncoder().encodeToString(actual));
        long total=value.toString().getBytes(StandardCharsets.UTF_8).length;File[] saved=root.listFiles(file->file.isFile()&&file.getName().matches("[a-f0-9-]{36}\\.json"));if(saved!=null)for(File file:saved)total+=file.length();if(total>8L*1024*1024)throw new IllegalStateException("恢复成果超过原8MiB目录容量；未自动清理旧版本");
        original.checkpoint();JsonFiles.write(target,value);original.checkpoint();all.put(metadata(value));JsonFiles.write(catalog,new JSONObject().put("artifacts",all));return queryOwnerFileArchive(item,newProject,nativeImportId,sectionDigest);
    }
    /** Cold query is read-only; absent is not proof that publication never began. */
    public synchronized JSONObject queryOwnerFileArchive(JSONObject supplied,String newProject,String nativeImportId,String sectionDigest)throws Exception {
        JSONObject item=checkedOwnerFileArchive(supplied);requireArchiveImport(nativeImportId,sectionDigest);String id=ownerArchiveId(nativeImportId,item.getString("originalArtifactId"));JSONArray all=catalog();boolean listed=false;for(int i=0;i<all.length();i++)if(id.equals(all.getJSONObject(i).optString("id")))listed=true;
        if(!listed)return new JSONObject().put("found",false).put("status","unknown").put("artifactId",id).put("replayAllowed",false);
        JSONObject actual=checkedFile(id,item.getString("sha256")),archive=actual.getJSONObject("archiveImport");if(!newProject.equals(actual.getString("projectId"))||!nativeImportId.equals(archive.getString("nativeImportId"))||!sectionDigest.equals(archive.getString("sectionDigest"))||!dev.pocketengine.features.local.project.ProjectSources.canonical(item).equals(dev.pocketengine.features.local.project.ProjectSources.canonical(archive.getJSONObject("original"))))throw new SecurityException("原成果恢复动作与固定归档范围不同");
        return metadata(actual).put("artifactId",id).put("found",true).put("status","found").put("restoredTaskOrGrant",false).put("replayAllowed",false);
    }
    private static String ownerArchiveId(String action,String old){return UUID.nameUUIDFromBytes(("owner-artifact-restore-v1:"+action+":"+old).getBytes(StandardCharsets.UTF_8)).toString();}
    private static void requireArchiveImport(String id,String digest){if(!validId(id)||digest==null||!digest.matches("[a-f0-9]{64}"))throw new SecurityException("缺少原生恢复动作及分区摘要");}
    public static JSONObject checkedOwnerFileArchiveForHost(JSONObject source)throws Exception{return checkedOwnerFileArchive(source);}
    private static JSONObject checkedOwnerFileArchive(JSONObject source)throws Exception {
        JSONObject item=JsonFiles.copy(source);java.util.Set<String> keys=new java.util.HashSet<>();item.keys().forEachRemaining(keys::add);if(!keys.equals(java.util.Set.of("format","originalArtifactId","originalProjectId","path","kind","sourceVersion","sourceRevisionId","sha256","bytes","mimeType"))||!"pocketworld.fixed-file-archive.v1".equals(item.optString("format"))||!validId(item.optString("originalArtifactId"))||!validId(item.optString("originalProjectId"))||!validId(item.optString("sourceRevisionId"))||!(item.opt("sourceVersion") instanceof Integer)||item.getInt("sourceVersion")<1||!(item.opt("bytes") instanceof Integer||item.opt("bytes") instanceof Long)||item.getLong("bytes")<0||item.getLong("bytes")>VersionedWorkspace.MAX_FILE_BYTES)throw new SecurityException("固定成果归档格式、版本或容量不支持");
        VersionedWorkspace.path(item.getString("path"));String kind=item.getString("kind");if(!java.util.Set.of("text_file","binary_file","file_tombstone").contains(kind)||item.getString("mimeType").length()>160||item.getString("mimeType").indexOf('\n')>=0||item.getString("mimeType").indexOf('\r')>=0||!item.getString("sha256").matches("file_tombstone".equals(kind)?"absent":"[a-f0-9]{64}")||"file_tombstone".equals(kind)&&item.getLong("bytes")!=0)throw new SecurityException("原删除修订与实际文件字节不能混淆");return item;
    }
    public synchronized JSONObject prepareExport(JSONObject input)throws Exception {
        String artifactId=input.optString("artifactId"),projectId=input.optString("projectId"),path=input.optString("path");
        if(!artifactId.isEmpty()){
            if(!projectId.isEmpty()||!path.isEmpty())throw new IllegalArgumentException("请分别选择一份成果或项目文件导出");
            JSONObject artifact=read(artifactId);if(!artifact.has("content"))throw new IllegalArgumentException("二进制需原native字节导出；删除修订没有可导出的正文");return document(filename(artifact.getString("path")),artifact.getString("mimeType"),artifact.getString("content")).put("artifactId",artifactId);
        }
        if(projectId.isEmpty())throw new IllegalArgumentException("请选择要导出的项目、文件或成果");
        if(!path.isEmpty()){JSONObject file=workspace.read(projectId,path,input.optInt("version",0));return document(filename(path),mime(path),file.getString("content")).put("projectId",projectId).put("revisionId",file.getString("revisionId"));}
        JSONObject project=workspace.export(projectId);return document(filename(project.getJSONObject("project").getString("name"))+".pocketworld.json","application/json",project.toString(2)).put("projectId",projectId);
    }
    public synchronized JSONObject diff(String artifactId)throws Exception {
        JSONObject artifact=read(artifactId),current=workspace.read(artifact.getString("projectId"),artifact.getString("path"),0);
        return VersionedWorkspace.compare(artifact.getString("path"),artifact.getString("content"),current.getString("content"),"artifact/"+artifactId,"v"+current.getInt("version")).put("artifactId",artifactId).put("projectId",artifact.getString("projectId")).put("version",current.getInt("version")).put("revisionId",current.getString("revisionId"));
    }
    private static JSONObject metadata(JSONObject artifact)throws Exception {JSONObject copy=JsonFiles.copy(artifact);copy.remove("content");copy.remove("contentBase64");return copy;}
    private static JSONObject document(String name,String mime,String content)throws Exception{return new JSONObject().put("suggestedName",name).put("mimeType",mime).put("content",content).put("encoding","UTF-8").put("sizeBytes",content.getBytes(StandardCharsets.UTF_8).length).put("sha256",hash(content)).put("requiresDocumentPicker",true);}
    private static String filename(String path){String name=path.substring(path.lastIndexOf('/')+1).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_").trim();return name.isEmpty()?"PocketWorld-export.txt":name;}
    private static String mime(String path){String lower=path.toLowerCase(java.util.Locale.ROOT);if(lower.endsWith(".png"))return "image/png";if(lower.endsWith(".jpg")||lower.endsWith(".jpeg"))return "image/jpeg";if(lower.endsWith(".webp"))return "image/webp";if(lower.endsWith(".gif"))return "image/gif";if(lower.endsWith(".svg"))return "image/svg+xml";if(lower.endsWith(".pdf"))return "application/pdf";if(lower.endsWith(".mp4"))return "video/mp4";if(lower.endsWith(".webm"))return "video/webm";if(lower.endsWith(".mp3"))return "audio/mpeg";if(lower.endsWith(".wav"))return "audio/wav";if(lower.endsWith(".ogg"))return "audio/ogg";if(lower.endsWith(".docx"))return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";if(lower.endsWith(".xlsx"))return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";if(lower.endsWith(".pptx"))return "application/vnd.openxmlformats-officedocument.presentationml.presentation";if(lower.endsWith(".bin"))return "application/octet-stream";if(lower.endsWith(".json"))return "application/json";if(lower.endsWith(".md"))return "text/markdown";if(lower.endsWith(".html")||lower.endsWith(".htm"))return "text/html";if(lower.endsWith(".csv"))return "text/csv";return "text/plain";}
    private static String hash(String content)throws Exception {byte[] bytes=MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));StringBuilder hash=new StringBuilder();for(byte value:bytes)hash.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return hash.toString();}
}
