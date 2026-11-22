package dev.pocketengine.features.local.project;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.plugins.mcp.McpJson;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** The owner's one native document → one absent npm lock. Never a resolver, process or generic import. */
public final class OwnerSuppliedViteLock {
    public static final String PATH="package-lock.json";
    public static final int MAX_BYTES=512*1024;
    private static final LinkOption NOFOLLOW=LinkOption.NOFOLLOW_LINKS;
    private static final Set<String> PHONE=Set.of("external_zip","saf_tree");
    private static final Set<String> RECEIPT=Set.of("clientId","packageSha256","manifestIdentity","mode",
        "executionAuthorityRestored","sourceKind","sourceEvidenceSha256");
    private OwnerSuppliedViteLock(){}

    /** Narrow source-only affordance. The native offer still checks physical absence and actual lock bytes. */
    public static boolean candidate(JSONObject snapshot)throws Exception{
        JSONObject copy=snapshot.getJSONObject("checkout"),origin=copy.optJSONObject("sourceImport");
        if(!LocalModule.matchesOriginalPhoneLockSource(copy,origin)||
            origin.length()!=RECEIPT.size()||!RECEIPT.stream().allMatch(origin::has)||
            !PHONE.contains(origin.optString("sourceKind")))return false;
        var rows=snapshot.getJSONArray("files");
        String html=null,body=null,reactConfig=null,reactEntry=null;Set<String> paths=new HashSet<>();
        boolean customReactTransform=false;
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);
            String path=row.getString("path"),leaf=path.substring(path.lastIndexOf('/')+1).toLowerCase(Locale.ROOT);
            paths.add(path);
            if(leaf.equals(PATH)||leaf.equals("yarn.lock")||leaf.equals("bun.lock")||leaf.equals("bun.lockb")||
                leaf.equals("pnpm-lock.yaml")||leaf.startsWith(".env")||leaf.startsWith(".postcssrc")||
                leaf.startsWith("postcss.config."))return false;
            if(leaf.startsWith("vite.config.")){
                if(!OriginalNodeWebOutput.REACT_CONFIG.equals(path)||row.optBoolean("binary")||!(row.opt("content") instanceof String config))return false;
                reactConfig=config;
            }
            customReactTransform|=OriginalNodeWebOutput.customReactViteTransform(leaf);
            if(OriginalNodeWebOutput.REACT_ENTRY.equals(path)&&!row.optBoolean("binary")&&row.opt("content") instanceof String entry)
                reactEntry=entry;
            if("index.html".equals(path)&&row.opt("content") instanceof String contents)html=contents;
            if("package.json".equals(path)&&row.opt("content") instanceof String contents)body=contents;
        }
        if(html==null||html.isBlank()||body==null||body.getBytes(StandardCharsets.UTF_8).length>65_536)return false;
        JSONObject pkg=McpJson.parse(body),scripts=pkg.optJSONObject("scripts");
        if(pkg.has("packageManager")||pkg.has("postcss")||scripts==null||!"vite build".equals(scripts.opt("build"))||
            scripts.has("prebuild")||scripts.has("postbuild")||
            !"npm".equals(ProjectCheckoutTooling.manager(paths,""))||
            !PATH.equals(OwnerDraftNodeDependencies.missingRequiredLock(pkg,paths,"npm")))return false;
        OwnerDraftNodeDependencies.requirePlainProject(pkg,paths);
        boolean vite=false;
        for(String name:Set.of("dependencies","devDependencies")){
            JSONObject declared=pkg.optJSONObject(name);
            vite|=declared!=null&&declared.opt("vite") instanceof String version&&!version.isBlank();
        }
        return vite&&pkg.opt("name") instanceof String name&&!name.isBlank()&&
            pkg.opt("version") instanceof String version&&!version.isBlank()&&
            (reactConfig==null||!customReactTransform&&OriginalNodeWebOutput.defaultReactViteJsWithoutLock(pkg,reactConfig,reactEntry,html));
    }

    private static String originalManifest(JSONObject snapshot)throws Exception{
        JSONArray rows=snapshot.getJSONArray("files");
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);
            if("package.json".equals(row.opt("path"))&&row.opt("content") instanceof String text)return text;
        }
        throw new SecurityException("所选副本没有根 package.json");
    }

    public static final class Origin {
        private final LocalModule issuer;
        private final String project,checkout,source,manifest,importDigest;
        private final Path root,base;
        private final Object baseKey,rootKey;
        private Origin(LocalModule issuer,String project,String checkout,String expectedSource, Path root,JSONObject snapshot)throws Exception{
            this.issuer=issuer;this.project=ProjectBuildPlan.identity(project);this.checkout=ProjectBuildPlan.identity(checkout);
            this.source=expectedSource;this.root=root;this.base=root.getParent();
            baseKey=directoryKey(base);rootKey=directoryKey(root);
            JSONObject metadata=snapshot.getJSONObject("checkout");
            importDigest=ProjectSources.digest(metadata.getJSONObject("sourceImport"));
            manifest=ProjectSources.sha256(originalManifest(snapshot).getBytes(StandardCharsets.UTF_8));
            requireSource(issuer,snapshot);
        }
        public String project(){return project;}
        public String checkout(){return checkout;}
        public String sourceDigest(){return source;}
        public void requireSource(LocalModule issuer,JSONObject snapshot)throws Exception{
            requireDirectories();
            JSONObject actual=ProjectSources.describe(snapshot);
            if(this.issuer!=issuer||!project.equals(actual.opt("projectId"))||!checkout.equals(actual.opt("checkoutId"))||
                !source.equals(actual.opt("sourceDigest"))||!candidate(snapshot)||
                !importDigest.equals(ProjectSources.digest(snapshot.getJSONObject("checkout").getJSONObject("sourceImport"))))
                throw new SecurityException("原网页副本或 package.json 已变化，没有填入其他副本");
            requireMissingAt(root);
        }
        private void requireDirectories()throws Exception{
            if(!baseKey.equals(directoryKey(base))||!rootKey.equals(directoryKey(root)))
                throw new SecurityException("原私人副本目录已变化，没有写入");
        }
    }
    public static Origin captureForHost(LocalModule issuer,String project,String checkout,String expectedSource,
                                        Path root,JSONObject snapshot)throws Exception{
        if(!OwnerDirectNodeBuild.digest(expectedSource))throw new SecurityException("没有原源码的准确版本");
        return new Origin(issuer,project,checkout,expectedSource,root,snapshot);
    }
    public static void requireMissingAt(Path root)throws Exception{
        directoryKey(root.getParent());directoryKey(root);
        requireAbsent(root.resolve(PATH));requireAbsent(root.resolve("node_modules"));
    }
    private static Object directoryKey(Path path)throws Exception{
        var attributes=Files.readAttributes(path,BasicFileAttributes.class,NOFOLLOW);
        if(!attributes.isDirectory()||attributes.isSymbolicLink()||attributes.fileKey()==null||
            !path.equals(path.toFile().getCanonicalFile().toPath()))throw new SecurityException("原副本目录不可核对");
        return attributes.fileKey();
    }
    private static void requireAbsent(Path path)throws Exception{
        try{Files.readAttributes(path,BasicFileAttributes.class,NOFOLLOW);}
        catch(NoSuchFileException missing){return;}
        throw new SecurityException("只补入缺少的锁；原锁或依赖目录已经存在，没有覆盖");
    }

    private static JSONObject withLock(Origin origin,JSONObject original,String text,String sha,int size)throws Exception{
        JSONArray before=original.getJSONArray("files"),after=new JSONArray();
        for(int i=0;i<before.length();i++)after.put(before.getJSONObject(i));
        after.put(new JSONObject().put("checkoutId",origin.checkout).put("path",PATH)
            .put("content",text).put("bytes",size).put("sha256",sha));
        return new JSONObject().put("checkout",original.getJSONObject("checkout")).put("files",after);
    }

    public static final class Review implements AutoCloseable {
        private final Origin origin;
        private final String sha,resultSource;
        private final int size;
        private byte[] bytes;
        private Review(Origin origin,byte[] actual,String sha,String resultSource){
            this.origin=origin;this.sha=sha;this.resultSource=resultSource;size=actual.length;bytes=actual;
        }
        public Origin origin(){return origin;}
        public synchronized JSONObject view()throws Exception{
            if(bytes==null)throw new SecurityException("这份锁文件审阅已使用或关闭");
            return identity().put("state","review").put("saved",false);
        }
        private JSONObject identity()throws Exception{
            return new JSONObject().put("projectId",origin.project).put("checkoutId",origin.checkout)
                .put("sourceDigest",origin.source).put("manifestSha256",origin.manifest).put("path",PATH)
                .put("sha256",sha).put("bytes",size).put("nextSourceDigest",resultSource)
                .put("executionAuthorized",false);
        }
        private synchronized byte[] take(LocalModule issuer){
            if(origin.issuer!=issuer||bytes==null)throw new SecurityException("原确认已使用或已关闭");
            byte[] taken=bytes;bytes=null;return taken;
        }
        @Override public synchronized void close(){if(bytes!=null){Arrays.fill(bytes,(byte)0);bytes=null;}}
    }
    public static Review reviewForHost(LocalModule issuer,Origin origin,JSONObject original,
            ProjectDocumentImport.Source selected,RunControl control)throws Exception{
        origin.requireSource(issuer,original);byte[] bytes=selected.copySuppliedViteLock(control);
        try{
            String body=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            String pkg=originalManifest(original);
            if(!OwnerDraftNodeDependencies.matchesOriginalNpmProfile(McpJson.parse(pkg),body,Set.of("vite")))
                throw new SecurityException("原锁文件与这个副本根目录的 package.json 或固定 npm 注册表格式不同");
            String sha=ProjectSources.sha256(bytes);
            JSONObject next=withLock(origin,original,body,sha,bytes.length);
            if(OriginalNodeWebOutput.directViteSource(next)==null)
                throw new SecurityException("加入锁后仍不属于受支持的本机 Vite 项目，没有写入");
            String result=ProjectSources.describe(next).getString("sourceDigest");
            control.check();var reviewed=new Review(origin,bytes,sha,result);bytes=null;return reviewed;
        }finally{if(bytes!=null)Arrays.fill(bytes,(byte)0);}
    }

    private static void syncDirectory(Path path)throws Exception{
        try(var directory=FileChannel.open(path,StandardOpenOption.READ,NOFOLLOW)){directory.force(true);}
    }
    private static boolean exactFile(Path path,Object key,byte[] expected,int links)throws Exception{
        var attrs=Files.readAttributes(path,BasicFileAttributes.class,NOFOLLOW);
        return attrs.isRegularFile()&&!attrs.isSymbolicLink()&&attrs.fileKey()!=null&&attrs.fileKey().equals(key)&&
            attrs.size()==expected.length&&Files.getAttribute(path,"unix:nlink",NOFOLLOW) instanceof Number n&&n.longValue()==links&&
            Arrays.equals(expected,ProjectFileBytes.read(path,attrs,MAX_BYTES));
    }
    @FunctionalInterface public interface Readback {String sourceDigest()throws Exception;}
    /** Publish the fully synced native bytes without a replacing rename or a partially visible target. */
    public static JSONObject publishForHost(LocalModule issuer,Review review,ProjectPackageArchive.CopyCurrent beforeLink,
                                            ProjectPackageArchive.CopyCurrent enter,Readback readback)throws Exception{
        Origin origin=review.origin;byte[] bytes=review.take(issuer);
        Path source=null;Object key=null;Path target=origin.root.resolve(PATH);boolean attempted=false;
        try{
            origin.requireDirectories();beforeLink.check();
            source=Files.createTempFile(origin.base,"owner-vite-lock-",".pending");
            var attrs=Files.readAttributes(source,BasicFileAttributes.class,NOFOLLOW);
            key=attrs.fileKey();
            if(key==null||!attrs.isRegularFile()||attrs.isSymbolicLink())
                throw new SecurityException("暂存文件身份无法核对，没有写入锁");
            try(var out=FileChannel.open(source,StandardOpenOption.WRITE,NOFOLLOW)){
                ByteBuffer remaining=ByteBuffer.wrap(bytes);
                while(remaining.hasRemaining())out.write(remaining);
                out.force(true);
            }
            origin.requireDirectories();beforeLink.check();requireAbsent(target);
            if(!exactFile(source,key,bytes,1))throw new SecurityException("原锁文件暂存已变化");
            origin.requireDirectories();enter.check();
            attempted=true;
            // Atomic no-clobber. No alternate target, retry, or replacing fallback.
            try{Files.createLink(target,source);}
            catch(FileAlreadyExistsException noReplace){return review.identity().put("state","not_written").put("saved",false);}
            origin.requireDirectories();
            if(!exactFile(target,key,bytes,2)||!exactFile(source,key,bytes,2))
                throw new SecurityException("没有取得原锁文件的写入身份");
            Files.delete(source);source=null;
            syncDirectory(origin.root);syncDirectory(origin.base);origin.requireDirectories();
            if(!exactFile(target,key,bytes,1)||!review.resultSource.equals(readback.sourceDigest()))
                throw new SecurityException("尚未核对新锁和整份原源码");
            return review.identity().put("state","saved").put("saved",true);
        }catch(Exception uncertain){
            if(!attempted)throw uncertain;
            return review.identity().put("state","unknown");
        }finally{
            if(source!=null&&key!=null)try{
                origin.requireDirectories();var info=Files.readAttributes(source,BasicFileAttributes.class,NOFOLLOW);
                if(info.isRegularFile()&&!info.isSymbolicLink()&&key.equals(info.fileKey()))Files.delete(source);
            }catch(Exception keepPrivateScratch){/* Never remove an unproven name, and never delete a target. */}
            Arrays.fill(bytes,(byte)0);
        }
    }
}
