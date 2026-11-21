package dev.pocketengine.features.local.preview;

import dev.pocketengine.features.local.project.OriginalNodeWebOutput;
import dev.pocketengine.features.local.project.ProjectPackageArchive;
import dev.pocketengine.features.local.project.ProjectSources;
import dev.pocketengine.features.local.project.ProjectFileBytes;
import dev.pocketengine.features.local.NativeCommandExecutor;
import dev.pocketengine.features.workspace.VersionedWorkspace;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Immutable observed native preview bytes; never a model-supplied file resolver. */
public final class ProjectPreviewBundle {
    public static final int MAX_RESOURCES=256,MAX_RESOURCE_BYTES=4*1024*1024,MAX_TOTAL_BYTES=32*1024*1024;
    public static final String ORIGINAL_LOCAL_VITE_BUILD="original_local_vite_build";
    private ProjectPreviewBundle(){}
    public static final class Resource {
        public final String path,mimeType,sha256;private final byte[] bytes;
        private Resource(String path,byte[] bytes)throws Exception {this.path=path;this.bytes=bytes.clone();mimeType=mime(path);sha256=ProjectSources.sha256(bytes);}
        public byte[] bytes(){return bytes.clone();}
    }
    // Renderer-only provenance. No package/saved artifact id or recipient is minted.
    private record LocalViteBuild(String taskId,String runId,int epoch,String buildOperationId,
            String manifestIdentity,String archiveSha256,long archiveBytes){
        JSONObject metadata()throws Exception{
            return new JSONObject().put("taskId",taskId).put("runId",runId).put("epoch",epoch)
                .put("buildOperationId",buildOperationId).put("manifestIdentity",manifestIdentity)
                .put("archiveSha256",archiveSha256).put("archiveBytes",archiveBytes);
        }
    }
    public static final class Verified {
        public final String checkoutId,projectId,sourceDigest,entryPath,previewRoot,identity,origin,entryUrl;public final long capturedAt;public final int totalBytes;
        public final String artifactId,artifactSha256;
        private final Map<String,Resource> resources;
        private final String rootedBase;
        private final LocalViteBuild localViteBuild;
        private Verified(String checkout,String project,String source,String entry,String root,Map<String,Resource> resources,int total)throws Exception {
            this(checkout,project,source,entry,root,resources,total,null,null,"",null);
        }
        private Verified(String checkout,String project,String source,String entry,String root,Map<String,Resource> resources,int total,
                String artifactId,String artifactSha,String rootedBase,LocalViteBuild localViteBuild)throws Exception {
            checkoutId=checkout;projectId=project;sourceDigest=source;entryPath=entry;previewRoot=root;capturedAt=System.currentTimeMillis();totalBytes=total;this.resources=Collections.unmodifiableMap(new LinkedHashMap<>(resources));
            this.artifactId=artifactId;artifactSha256=artifactSha;this.rootedBase=rootedBase;this.localViteBuild=localViteBuild;
            JSONObject core=new JSONObject().put("checkoutId",checkout).put("projectId",project).put("sourceDigest",source).put("entryPath",entry).put("previewRoot",root).put("resources",manifest());if(artifactId!=null)core.put("artifactId",artifactId).put("artifactSha256",artifactSha);
            if(!rootedBase.isEmpty())core.put("rootedBase",rootedBase);
            if(localViteBuild!=null)core.put(ORIGINAL_LOCAL_VITE_BUILD,localViteBuild.metadata());
            identity=ProjectSources.digest(core);origin="https://pw-project-"+identity.substring(0,40)+".invalid/";
            entryUrl=origin+(rootedBase.isEmpty()?"":rootedBase.substring(1))+encoded(entry.substring(root.isEmpty()?0:root.length()+1));
        }
        public boolean originalLocalViteBuild(){return localViteBuild!=null;}
        public JSONObject metadata()throws Exception{
            JSONObject result=new JSONObject().put("previewId",identity).put("checkoutId",checkoutId).put("projectId",projectId)
                .put("sourceDigest",sourceDigest).put("entryPath",entryPath).put("previewRoot",previewRoot).put("resources",manifest())
                .put("resourceCount",resources.size()).put("bytes",totalBytes).put("capturedAt",capturedAt);
            if(localViteBuild==null)result.put("origin",artifactId==null?"active_workspace_observation":"fixed_workspace_project_artifact")
                .put("artifactId",artifactId).put("artifactSha256",artifactSha256);
            else {
                JSONObject proof=localViteBuild.metadata();
                for(var keys=proof.keys();keys.hasNext();){String key=keys.next();result.put(key,proof.get(key));}
                result.put("origin",ORIGINAL_LOCAL_VITE_BUILD).put("authorityAdded",false);
            }
            return result.put("atomicArtifactFence",false).put("businessVerified",false).put("persisted",artifactId!=null);
        }
        public JSONArray manifest()throws Exception {JSONArray result=new JSONArray();for(Resource resource:resources.values())result.put(new JSONObject().put("path",resource.path).put("mimeType",resource.mimeType).put("sha256",resource.sha256).put("bytes",resource.bytes.length));return result;}
        String resourcePath(String url){
            try {
                URI parsed=new URI(url);
                if(!"https".equals(parsed.getScheme())||!new URI(origin).getRawAuthority().equals(parsed.getRawAuthority())||parsed.getRawUserInfo()!=null)return null;
                String path=parsed.getPath();if(path==null||!path.startsWith("/"))return null;
                if(!rootedBase.isEmpty())return parsed.getRawQuery()==null&&path.equals(parsed.getRawPath())?ViteOutputMount.relativeMember(rootedBase,path):null;
                String relative=path.substring(1);VersionedWorkspace.path(relative);
                if(!relative.equals(relative.trim())||relative.indexOf('\\')>=0)return null;return relative;
            }catch(Exception invalid){return null;}
        }
        public Resource resource(String url){String path=resourcePath(url);return path==null?null:resources.get(path);}
        public boolean document(String url){Resource resource=resource(url);return resource!=null&&resource.mimeType.equals("text/html");}
    }
    /** Reopen verified immutable archive bytes; no listener, live service, grant or active-tree fallback. */
    public static Verified fromProjectPackage(ProjectPackageArchive.Frozen frozen,String artifactId,String artifactSha,String selectedEntry)throws Exception {
        if(artifactId==null||!artifactId.matches("[a-f0-9-]{36}")||artifactSha==null||!artifactSha.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("请选择原固定成果版本");
        return fromArchive(frozen,frozen.manifest(),selectedEntry,artifactId,artifactSha,null);
    }
    /** Same already-reviewed bytes in memory, with no archive registration or resource fallback. */
    public static Verified fromOriginalLocalViteBuild(ProjectPackageArchive.Frozen frozen,OriginalNodeWebOutput.Review original)throws Exception{
        if(original==null||!original.build.directOwnerVite())throw new SecurityException("请选择这项原本机网页构建");
        JSONObject pin=original.pin(),manifest=frozen.manifest(),source=manifest.getJSONObject("source");
        OriginalNodeWebOutput.requirePin(original.build,pin);
        // The typed review checked the archive SHA/size and complete tree against the exit witness.
        // Frozen's manifest identity also binds every byte; pairing it to that review needs no ZIP rewrite.
        if(!frozen.identity.equals(pin.getString("manifestIdentity"))||!"web_output".equals(manifest.opt("mode"))||
            !OriginalNodeWebOutput.ENTRY.equals(manifest.opt("entryPath"))||!"dist".equals(manifest.opt("previewRoot"))||
            manifest.getJSONArray("omitted").length()!=0)
            throw new SecurityException("预览资源与这次原构建的清单不同，未打开其他输出");
        for(String key:new String[]{"projectId","checkoutId","sourceDigest"})if(!pin.getString(key).equals(source.opt(key)))
            throw new SecurityException("预览资源不是这项原工作的项目、工作副本或源码版本");
        var proof=new LocalViteBuild(pin.getString("taskId"),pin.getString("runId"),pin.getInt("epoch"),pin.getString("buildOperationId"),
            frozen.identity,pin.getString("archiveSha256"),pin.getLong("archiveBytes"));
        return fromArchive(frozen,manifest,OriginalNodeWebOutput.ENTRY,null,null,proof);
    }
    private static Verified fromArchive(ProjectPackageArchive.Frozen frozen,JSONObject manifest,String selectedEntry,
            String artifactId,String artifactSha,LocalViteBuild localViteBuild)throws Exception{
        JSONObject source=manifest.getJSONObject("source");String entry=VersionedWorkspace.path(selectedEntry);
        if(!mime(entry).equals("text/html")||manifest.getString("mode").equals("web_output")&&!entry.equals(manifest.getString("entryPath")))throw new IllegalArgumentException("请选择这份成果声明的HTML入口");
        int slash=entry.lastIndexOf('/');String root=slash<0?"":entry.substring(0,slash),prefix=root.isEmpty()?"":root+"/";Map<String,Resource> resources=new TreeMap<>();int total=0;
        for(int i=0;i<manifest.getJSONArray("files").length();i++){
            JSONObject file=manifest.getJSONArray("files").getJSONObject(i);String path=file.getString("path");if(!path.startsWith(prefix)||mime(path).isEmpty())continue;
            byte[] bytes=frozen.file(path);if(resources.size()>=MAX_RESOURCES||bytes.length>MAX_RESOURCE_BYTES||(total+=bytes.length)>MAX_TOTAL_BYTES)throw new IllegalArgumentException("此固定项目包超出网页预览资源预算，仍可导出或重导入");
            String relative=path.substring(prefix.length());resources.put(relative,new Resource(relative,bytes));
        }
        Resource main=resources.get(entry.substring(prefix.length()));
        if(main==null)throw new IllegalArgumentException("固定项目包没有该HTML入口，不改用当前项目");
        // Only the single dist entry can mount its own complete archive at a different URL prefix.
        String rootedBase="web_output".equals(manifest.opt("mode"))&&"dist/index.html".equals(entry)&&manifest.getJSONArray("omitted").length()==0?
            ViteOutputMount.rootedBase(main.bytes,resources::containsKey):"";
        return new Verified(source.getString("checkoutId"),source.getString("projectId"),source.getString("sourceDigest"),
            entry,root,resources,total,artifactId,artifactSha,rootedBase,localViteBuild);
    }
    /** Capture the entry's resource directory, excluding dependency/cache trees. No HTTP listener is opened. */
    public static Verified capture(File activeRoot,String checkout,String project,String source,String entryPath)throws Exception {
        VersionedWorkspace.path(entryPath);if(!source.matches("[a-f0-9]{64}")||!mime(entryPath).equals("text/html"))throw new IllegalArgumentException("请选择真实项目内HTML入口");
        File entry=NativeCommandExecutor.within(activeRoot,entryPath);if(!entry.isFile())throw new IllegalArgumentException("HTML入口不存在");int slash=entryPath.lastIndexOf('/');String relativeRoot=slash<0?"":entryPath.substring(0,slash);File root=relativeRoot.isEmpty()?activeRoot:NativeCommandExecutor.within(activeRoot,relativeRoot);
        Map<String,Resource> resources=new TreeMap<>();int[] total={0};
        Files.walkFileTree(root.toPath(),new java.nio.file.SimpleFileVisitor<java.nio.file.Path>(){
            @Override public java.nio.file.FileVisitResult preVisitDirectory(java.nio.file.Path path,java.nio.file.attribute.BasicFileAttributes attrs){String relative=root.toPath().relativize(path).toString();for(String part:relative.split("/"))if(java.util.Set.of(".git","node_modules",".venv","venv",".gradle",".pocketworld","__pycache__").contains(part))return java.nio.file.FileVisitResult.SKIP_SUBTREE;return java.nio.file.FileVisitResult.CONTINUE;}
            @Override public java.nio.file.FileVisitResult visitFile(java.nio.file.Path path,java.nio.file.attribute.BasicFileAttributes attrs)throws java.io.IOException {
                String relative=root.toPath().relativize(path).toString();if(mime(relative).isEmpty())return java.nio.file.FileVisitResult.CONTINUE;
                try{VersionedWorkspace.path(relative);if(attrs.isSymbolicLink()||!attrs.isRegularFile())throw new SecurityException("预览资源不跟随链接或特殊文件");if(attrs.size()>MAX_RESOURCE_BYTES||resources.size()>=MAX_RESOURCES)throw new IllegalArgumentException("预览资源超过256个或单文件4MiB");NativeCommandExecutor.within(root,relative);
                    byte[] bytes=ProjectFileBytes.read(path,attrs,Math.min(MAX_RESOURCE_BYTES,MAX_TOTAL_BYTES-total[0]));
                    NativeCommandExecutor.within(root,relative);total[0]+=bytes.length;resources.put(relative,new Resource(relative,bytes));return java.nio.file.FileVisitResult.CONTINUE;}catch(java.io.IOException failed){throw failed;}catch(Exception invalid){throw new java.io.IOException(invalid.getMessage(),invalid);}
            }
        });
        if(!resources.containsKey(entryPath.substring(slash+1)))throw new SecurityException("预览入口不在实际资源清单中");return new Verified(checkout,project,source,entryPath,relativeRoot,resources,total[0]);
    }
    private static String encoded(String path)throws Exception{return new URI(null,null,"/"+path,null).getRawPath().substring(1);}
    public static String mime(String path){
        // The Vite public-directory convention places this plain-text file next to index.html.
        if(path.equals("robots.txt")||path.equals("dist/robots.txt"))return "text/plain";
        String name=path.toLowerCase(java.util.Locale.ROOT);int dot=name.lastIndexOf('.');String ext=dot<0?"":name.substring(dot+1);return switch(ext){case "html","htm"->"text/html";case "css"->"text/css";case "js","mjs","cjs"->"text/javascript";case "json","map"->"application/json";case "svg"->"image/svg+xml";case "png"->"image/png";case "jpg","jpeg"->"image/jpeg";case "gif"->"image/gif";case "webp"->"image/webp";case "ico"->"image/x-icon";case "woff"->"font/woff";case "woff2"->"font/woff2";case "ttf"->"font/ttf";case "wasm"->"application/wasm";default->"";};}
}
