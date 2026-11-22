package dev.pocketengine.features.local.preview;

import android.content.Context;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import dev.pocketengine.features.local.project.ProjectSources;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native private project versions, not a Workspace/Book artifact, export, or tool. */
public final class OwnerKeptStaticPages {
    public static final int MAX_VERSIONS=4,MAX_STORED_BYTES=8*1024*1024;
    private static final int MAX_HEADER_BYTES=64*1024,HEADER_PREFIX_BYTES=4+64;
    private static final int MAX_RECORD_BYTES=HEADER_PREFIX_BYTES+MAX_HEADER_BYTES+ProjectPreviewBundle.MAX_SAVED_STATIC_BYTES;
    private static final long PENDING_REVIEW_MS=120_000L;
    private static final String KIND="owner_saved_static_page",SCHEMA="pocketworld.owner-kept-static-page.v1";
    private static final String DIRECTORY="owner-kept-static-pages-v1";
    private static final Object LOCK=new Object(); // Only the main app process hosts this owner shelf.
    private final Context app;

    /** Derived from the exact active native private Project, never the raw requested id alone. */
    public interface ProjectWindow {
        String projectId();
        boolean current();
    }

    @FunctionalInterface public interface Commit { void enter() throws Exception; }
    /** Only the original live preview supplies this. The callback is synchronous and serialized
     * with that window's pause/leave. It must never reenter the store. */
    public interface PreviewWindow extends ProjectWindow { void atCommit(Commit commit)throws Exception; }

    /** The separate affirmative cleanup control in this exact native Project visit.
     * As with Keep, the host checks the shown review on main and calls the short
     * filesystem action synchronously, without reentering the store. */
    public interface PendingRecoveryWindow extends ProjectWindow {
        void atConfirmedAbandonment(String reviewId,Commit unlink)throws Exception;
    }

    /** Single-use native observation of one inactive staging inode, not a saved page or a file reader. */
    public static final class PendingReview {
        private final OwnerKeptStaticPages issuer;
        private final PendingRecoveryWindow window;
        private final String project,reviewId=UUID.randomUUID().toString();
        private final Root root;
        private final PendingStamp stamp;
        private final long until=SystemClock.elapsedRealtime()+PENDING_REVIEW_MS;
        private boolean used; // Under LOCK only.
        private PendingReview(OwnerKeptStaticPages issuer,PendingRecoveryWindow window,String project,Root root,PendingStamp stamp){
            this.issuer=issuer;this.window=window;this.project=project;this.root=root;this.stamp=stamp;
        }
        public String reviewId(){return reviewId;}
        public JSONObject metadata()throws Exception{
            long remaining=until-SystemClock.elapsedRealtime();
            if(remaining<=0)throw new SecurityException("原暂存核对已到期");
            return new JSONObject().put("kind","owner_saved_static_page_pending_review").put("projectId",project)
                .put("reviewId",reviewId).put("expiresInMs",remaining);
        }
    }

    public record PendingAbandonment(String projectId,String reviewId,String status) {
        public JSONObject metadata()throws Exception{
            return new JSONObject().put("kind","owner_saved_static_page_pending_abandonment")
                .put("projectId",projectId).put("reviewId",reviewId).put("status",status);
        }
    }

    public record Page(String versionId,String projectId,String checkoutId,String entrySha256,
            String manifestIdentity,long savedAt,int resourceCount,int bytes) {
        public JSONObject metadata()throws Exception{
            return new JSONObject().put("kind",KIND).put("versionId",versionId).put("projectId",projectId)
                .put("checkoutId",checkoutId).put("entryPath",ProjectPreviewBundle.SAVED_STATIC_ENTRY)
                .put("entrySha256",entrySha256).put("manifestIdentity",manifestIdentity).put("savedAt",savedAt)
                .put("resourceCount",resourceCount).put("bytes",bytes);
        }
    }

    public record Catalog(String projectId,List<Page> versions,long storedBytes,String pendingState) {
        public Catalog { versions=List.copyOf(versions); }
        public JSONObject metadata()throws Exception{
            var entries=new JSONArray();
            for(Page entry:versions)entries.put(entry.metadata());
            return new JSONObject().put("kind","owner_saved_static_page_catalog").put("projectId",projectId)
                .put("versions",entries).put("storedBytes",storedBytes).put("maxVersions",MAX_VERSIONS)
                .put("maxStoredBytes",MAX_STORED_BYTES).put("pendingState",pendingState);
        }
    }

    /** A positive result is returned only after exact native readback. Unknown must not auto-retry. */
    public record KeepResult(String status,Page page) {
        public static KeepResult unknown(){return new KeepResult("unknown",null);}
        public JSONObject metadata()throws Exception{
            return new JSONObject().put("kind","owner_saved_static_page_keep").put("status",status)
                .put("page",page==null?JSONObject.NULL:page.metadata());
        }
    }

    /** Native renderer-only payload; callers must never serialize the bundle. */
    public record Opened(Page page,ProjectPreviewBundle.Verified bundle) {}

    private record Directory(Path path,Object key) {}
    private record Root(List<Directory> parents,Path projectFolder,boolean exists) {}
    private record PendingStamp(BasicFileAttributes identity,long device,long inode,long changedAt) {}
    private record Member(String path,String sha256,String mimeType,int bytes) {}
    private record Stored(Path path,Page page,BasicFileAttributes identity,ProjectPreviewBundle.Verified bundle) {}
    private record Scan(List<Stored> committed,int firstFree,long bytes) {}
    private enum Read { HEADER, VERIFY, OPEN }
    private enum AbandonEntry { READY, ENTERED, RETIRED }

    public OwnerKeptStaticPages(Context context){app=context.getApplicationContext();}

    /** Explicit foreground Project lookup for recovery only. It reads no staged bytes,
     * and LOCK excludes a still-running Keep in the only process hosting this store. */
    public PendingReview reviewPendingAbandonment(PendingRecoveryWindow window)throws Exception{
        requireRecoveryWorker();
        String project=project(window);
        synchronized(LOCK){
            requireCurrent(window,project);
            Root root=root(project,false);
            PendingStamp original=root.exists?pendingStamp(root):null;
            requireCurrent(window,project);
            return original==null?null:new PendingReview(this,window,project,root,original);
        }
    }

    /** Only a separate positive owner action on the exact shown native review may unlink.
     * Do not open, publish, or retry the staged save. A stale review is not a cleanup receipt. */
    public PendingAbandonment abandonPending(PendingReview shown,PendingRecoveryWindow window)throws Exception{
        requireRecoveryWorker();
        String project=project(window);
        synchronized(LOCK){
            if(shown==null||shown.issuer!=this||shown.window!=window||!project.equals(shown.project)||shown.used)
                throw new SecurityException("请选择原项目仍显示的暂存清理确认");
            shown.used=true;
            var entry=new AtomicReference<>(AbandonEntry.READY);
            String status;
            try{
                requirePendingCurrent(shown,window);
                window.atConfirmedAbandonment(shown.reviewId,()->{
                    requirePendingCurrent(shown,window);
                    if(!entry.compareAndSet(AbandonEntry.READY,AbandonEntry.ENTERED))
                        throw new IOException("不会重复或迟到清除原暂存");
                    Files.delete(pending(shown.root));
                });
                if(entry.get()!=AbandonEntry.ENTERED)throw new IOException("未确认清除原暂存");
                syncDirectories(shown.root);
                if(leaf(pending(shown.root))!=null)throw new IOException("原暂存清除尚未确认");
                requireRoot(shown.root);
                requireCurrent(window,project);
                status="cleared";
            }catch(Exception failure){
                // Retire an unentered callback even if the host timed out.
                status=entry.compareAndSet(AbandonEntry.READY,AbandonEntry.RETIRED)?"not_cleared":"unknown";
            }
            return new PendingAbandonment(project,shown.reviewId,status);
        }
    }

    private static void requirePendingCurrent(PendingReview review,PendingRecoveryWindow window)throws Exception{
        requireCurrent(window,review.project);
        if(SystemClock.elapsedRealtime()>=review.until)throw new SecurityException("原暂存核对已到期");
        PendingStamp actual=pendingStamp(review.root),prior=review.stamp;
        if(actual==null||prior.device!=actual.device||prior.inode!=actual.inode||prior.changedAt!=actual.changedAt)
            throw new SecurityException("原暂存已变化，未清除其他记录");
        requireSame(prior.identity,actual.identity);
    }

    private static PendingStamp pendingStamp(Root root)throws IOException{
        requireRoot(root);
        Path path=pending(root);
        BasicFileAttributes before=leaf(path);
        if(before==null){requireRoot(root);return null;}
        // This private, fixed CREATE_NEW leaf may be empty after a crash. Prove
        // its app-owned namespace/inode by metadata, not by parsing incomplete bytes.
        final android.system.StructStat stamp;
        try{stamp=Os.lstat(path.toString());}
        catch(ErrnoException unavailable){throw new IOException("原暂存身份不可核对",unavailable);}
        if(stamp.st_uid!=Process.myUid()||!OsConstants.S_ISREG(stamp.st_mode)||(stamp.st_mode&07777)!=0600||
                stamp.st_nlink!=1||stamp.st_size!=before.size()||stamp.st_size<0||stamp.st_size>MAX_RECORD_BYTES)
            throw new IOException("原暂存不是本项目内可清理的私人记录");
        requireSame(before,leaf(path));requireRoot(root);
        return new PendingStamp(before,stamp.st_dev,stamp.st_ino,stamp.st_ctime);
    }

    private static void requireRecoveryWorker(){
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("不得在原项目界面线程等待暂存存储");
    }

    /** Four fixed headers, no checkout/source walk or version bodies. Missing root is not created. */
    public Catalog catalog(ProjectWindow window)throws Exception{
        String project=project(window);
        synchronized(LOCK){
            requireCurrent(window,project);
            Root root=root(project,false);
            Scan known=scan(root,project);
            var result=new ArrayList<Page>();
            for(Stored row:known.committed)result.add(row.page);
            result.sort(Comparator.comparingLong(Page::savedAt).reversed().thenComparing(Page::versionId));
            String pendingState="none";
            if(root.exists)try{if(pendingStamp(root)!=null)pendingState="reviewable";}
            catch(IOException|RuntimeException unavailable){pendingState="unconfirmed";}
            requireRoot(root);
            requireCurrent(window,project);
            return new Catalog(project,result,known.bytes,pendingState);
        }
    }

    /** Exact immutable selected version only. Never resolves against the current source checkout. */
    public Opened open(ProjectWindow window,String versionId,String manifestIdentity)throws Exception{
        String project=project(window);
        requireId(versionId);requireSha(manifestIdentity);
        synchronized(LOCK){
            requireCurrent(window,project);
            Root root=root(project,false);
            for(Stored row:scan(root,project).committed)if(row.page.versionId.equals(versionId)&&row.page.manifestIdentity.equals(manifestIdentity)){
                requireCurrent(window,project);
                Stored actual=read(root,project,row.path,row.page,Read.OPEN);
                requireSame(row.identity,actual.identity);
                requireCurrent(window,project);
                return new Opened(actual.page,actual.bundle);
            }
            throw new SecurityException("未核对到这份原保留网页，不使用其他版本");
        }
    }

    /** Only already-held renderer bytes. Even an identical version is verified before its receipt. */
    public KeepResult keep(ProjectBundleWebView.VisibleSavedStaticPage review,PreviewWindow window)throws Exception{
        String project=project(window);
        var bundle=review.bundle();
        if(!bundle.originalSavedStaticPage()||!project.equals(bundle.projectId))throw new SecurityException("原网页不属于这个项目");
        requireId(bundle.checkoutId);
        synchronized(LOCK){
            requireCurrent(window,project);
            Root root=root(project,false);
            Scan known=scan(root,project);
            for(Stored row:known.committed)if(row.page.manifestIdentity.equals(bundle.identity)){
                requireCurrent(window,project);
                Stored verified=read(root,project,row.path,row.page,Read.VERIFY);
                requireSame(row.identity,verified.identity);
                requireCurrent(window,project);
                return new KeepResult("already_kept",verified.page);
            }
            JSONArray manifest=bundle.manifest();
            var entry=Objects.requireNonNull(bundle.exactSavedStaticResource(ProjectPreviewBundle.SAVED_STATIC_ENTRY));
            Page page=new Page(UUID.randomUUID().toString(),project,bundle.checkoutId,entry.sha256,bundle.identity,
                System.currentTimeMillis(),manifest.length(),bundle.totalBytes);
            JSONObject info=page.metadata().put("schema",SCHEMA).put("owner","owner").put("resources",manifest);
            byte[] header=ProjectSources.canonical(info).getBytes(StandardCharsets.UTF_8);
            long size=HEADER_PREFIX_BYTES+(long)header.length+bundle.totalBytes;
            if(header.length>MAX_HEADER_BYTES)throw new IOException("原网页清单超出保留预算");
            if(known.firstFree==0||known.bytes+size>MAX_STORED_BYTES)return new KeepResult("quota",null);
            requireCurrent(window,project);
            if(!root.exists)root=root(project,true);
            return publish(root,project,known.firstFree,page,bundle,manifest,header,size,review,window);
        }
    }

    private KeepResult publish(Root root,String project,int slot,Page expected,ProjectPreviewBundle.Verified original,
            JSONArray manifest,byte[] header,long size,ProjectBundleWebView.VisibleSavedStaticPage review,PreviewWindow window)throws Exception{
        Path target=slot(root,slot),stage=pending(root);
        requireCurrent(window,project);
        requireRoot(root);
        // One bounded native staging path. An interrupted prior attempt is never replaced or replayed.
        if(leaf(stage)!=null)throw new IOException("上一次网页保留尚未确认，未覆盖或重新发布");
        var entered=new AtomicBoolean();
        BasicFileAttributes created=null;
        try{
            try(FileChannel out=FileChannel.open(stage,Set.of(StandardOpenOption.WRITE,StandardOpenOption.CREATE_NEW,LinkOption.NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))){
                created=leaf(stage);
                if(created==null||created.size()!=0)throw new IOException("原网页暂存文件不可核对");
                put(out,ByteBuffer.allocate(4).putInt(header.length).array());
                put(out,ProjectSources.sha256(header).getBytes(StandardCharsets.US_ASCII));
                put(out,header);
                for(int i=0;i<manifest.length();i++){
                    var resource=Objects.requireNonNull(original.exactSavedStaticResource(manifest.getJSONObject(i).getString("path")));
                    put(out,resource.bytes());
                }
                out.force(true);
            }
            requireCurrent(window,project);
            Stored prepared=read(root,project,stage,expected,Read.VERIFY);
            if(prepared.identity.size()!=size||!created.fileKey().equals(prepared.identity.fileKey()))
                throw new IOException("原网页暂存身份已变化");
            window.atCommit(()->{
                requireCurrent(window,project);
                requireRoot(root);
                if(leaf(target)!=null)throw new IOException("原保留位置已使用，没有覆盖");
                requireSame(prepared.identity,leaf(stage));
                review.requireCurrent();
                if(!entered.compareAndSet(false,true))throw new IOException("不会重复发布原网页");
                Files.move(stage,target,StandardCopyOption.ATOMIC_MOVE);
            });
            if(!entered.get())throw new IOException("未进入原网页发布");
            syncDirectories(root);
            // Post-rename full readback of the same inode and manifest before a positive receipt.
            Stored actual=read(root,project,target,expected,Read.VERIFY);
            requireSame(prepared.identity,actual.identity);
            requireCurrent(window,project);
            return new KeepResult("saved",actual.page);
        }catch(Exception failed){
            if(entered.get())return KeepResult.unknown(); // Publication may already be visible in the original shelf.
            if(created!=null)try{
                requireRoot(root);
                BasicFileAttributes own=leaf(stage);
                // Only an unpublished stage created by this call, never a retained page or old staging.
                if(own!=null&&created.fileKey().equals(own.fileKey())&&own.size()<=size)Files.delete(stage);
            }catch(IOException|RuntimeException cleanup){failed.addSuppressed(cleanup);}
            throw failed;
        }
    }

    private static Scan scan(Root root,String project)throws Exception{
        requireRoot(root);
        var committed=new ArrayList<Stored>();long bytes=0;int firstFree=0;
        var ids=new HashSet<String>();var manifests=new HashSet<String>();
        for(int i=1;i<=MAX_VERSIONS;i++){
            Stored value=root.exists?read(root,project,slot(root,i),null,Read.HEADER):null;
            if(value==null){if(firstFree==0)firstFree=i;continue;}
            if(!ids.add(value.page.versionId)||!manifests.add(value.page.manifestIdentity)||
                    (bytes+=value.identity.size())>MAX_STORED_BYTES)throw new IOException("原保留网页目录不完整，没有替换任何版本");
            committed.add(value);
        }
        requireRoot(root);
        return new Scan(committed,firstFree,bytes);
    }

    private static Stored read(Root root,String project,Path path,Page expected,Read mode)throws Exception{
        requireRoot(root);
        BasicFileAttributes before=leaf(path);
        if(before==null){
            requireRoot(root);
            if(expected!=null)throw new IOException("原保留网页文件不可核对");
            return null;
        }
        if(before.size()<=HEADER_PREFIX_BYTES||before.size()>MAX_RECORD_BYTES)
            throw new IOException("原保留网页大小不可核对");
        try(var in=new DataInputStream(Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS))){
            int length=in.readInt();
            if(length<=0||length>MAX_HEADER_BYTES||before.size()<HEADER_PREFIX_BYTES+(long)length)
                throw new IOException("原保留网页头不可核对");
            byte[] digest=new byte[64],encoded=new byte[length];
            in.readFully(digest);in.readFully(encoded);
            if(!Arrays.equals(digest,ProjectSources.sha256(encoded).getBytes(StandardCharsets.US_ASCII)))
                throw new IOException("原保留网页元数据摘要不同");
            String canonical=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(encoded)).toString();
            JSONObject info=new JSONObject(canonical);
            requireFields(info,"schema","owner","kind","projectId","versionId","checkoutId","entryPath",
                "entrySha256","manifestIdentity","savedAt","resourceCount","bytes","resources");
            if(!canonical.equals(ProjectSources.canonical(info))||!SCHEMA.equals(info.opt("schema"))||!"owner".equals(info.opt("owner"))||
                    !KIND.equals(info.opt("kind"))||!project.equals(info.opt("projectId"))||
                    !ProjectPreviewBundle.SAVED_STATIC_ENTRY.equals(info.opt("entryPath")))
                throw new IOException("原网页保留位置或归属不同");
            Page page=new Page(requireId(string(info,"versionId")),project,requireId(string(info,"checkoutId")),
                requireSha(string(info,"entrySha256")),requireSha(string(info,"manifestIdentity")),number(info,"savedAt",1,9_007_199_254_740_991L),
                (int)number(info,"resourceCount",1,1+ProjectPreviewBundle.MAX_SAVED_STATIC_ASSETS),
                (int)number(info,"bytes",0,ProjectPreviewBundle.MAX_SAVED_STATIC_BYTES));
            if((expected!=null&&!expected.equals(page))||before.size()!=HEADER_PREFIX_BYTES+(long)length+page.bytes)
                throw new IOException("原保留网页元数据或字节长度已变化");
            JSONArray manifest=info.getJSONArray("resources"),digestMembers=new JSONArray();
            if(manifest.length()!=page.resourceCount)throw new IOException("原保留网页资源数不同");
            var members=new ArrayList<Member>();String previous="",entry=null;int total=0;
            for(int i=0;i<manifest.length();i++){
                JSONObject row=manifest.getJSONObject(i);
                requireFields(row,"path","mimeType","sha256","bytes");
                String name=string(row,"path"),mime=string(row,"mimeType"),sha=requireSha(string(row,"sha256"));
                boolean main=ProjectPreviewBundle.SAVED_STATIC_ENTRY.equals(name);
                if(!main)ProjectPreviewBundle.savedStaticAssetPath(name);
                int bytes=(int)number(row,"bytes",0,main?ProjectPreviewBundle.MAX_SAVED_STATIC_ENTRY_BYTES:ProjectSources.MAX_FILE_BYTES);
                if(name.compareTo(previous)<=0||!mime.equals(ProjectPreviewBundle.mime(name))||(total+=bytes)>page.bytes)
                    throw new IOException("原保留网页资源范围或顺序不同");
                if(main)entry=sha;
                previous=name;
                members.add(new Member(name,sha,mime,bytes));
                digestMembers.put(new JSONArray().put(name).put(sha).put(bytes));
            }
            String source=ProjectPreviewBundle.savedStaticSourceDigest(digestMembers);
            if(total!=page.bytes||!page.entrySha256.equals(entry)||
                    !page.manifestIdentity.equals(ProjectPreviewBundle.savedStaticIdentity(project,page.checkoutId,source,manifest)))
                throw new IOException("原保留网页清单与入口不同");
            ProjectPreviewBundle.Verified bundle=null;
            if(mode!=Read.HEADER){
                var resources=new TreeMap<String,ProjectPreviewBundle.Resource>();
                for(Member member:members){
                    if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                    byte[] bytes=new byte[member.bytes];in.readFully(bytes);
                    if(mode==Read.OPEN){
                        var resource=new ProjectPreviewBundle.Resource(member.path,bytes);
                        if(!member.sha256.equals(resource.sha256)||!member.mimeType.equals(resource.mimeType))
                            throw new IOException("原保留网页资源字节未通过核对");
                        resources.put(member.path,resource);
                    }else if(!member.sha256.equals(ProjectSources.sha256(bytes)))
                        throw new IOException("原保留网页资源字节未通过核对");
                }
                if(in.read()!=-1)throw new IOException("原保留网页结尾不同");
                if(mode==Read.OPEN){
                    bundle=ProjectPreviewBundle.reopenKeptStaticPage(project,page.checkoutId,source,resources,page.bytes);
                    if(!page.manifestIdentity.equals(bundle.identity))throw new IOException("原保留网页重开清单不同");
                }
            }
            requireSame(before,leaf(path));requireRoot(root);
            return new Stored(path,page,before,bundle);
        }
    }

    private Root root(String project,boolean create)throws IOException{
        Path data=canonicalParentChild(app.getDataDir()),noBackup=canonicalParentChild(app.getNoBackupFilesDir());
        if(!data.equals(noBackup.getParent()))throw new IOException("本机私人非备份位置不可核对");
        var chain=new ArrayList<Directory>();
        chain.add(directory(data));chain.add(directory(noBackup));
        Path shelf=noBackup.resolve(DIRECTORY),folder=shelf.resolve(project);
        for(Path path:List.of(shelf,folder)){
            if(!create&&attributes(path)==null){
                Root absent=new Root(List.copyOf(chain),folder,false);
                requireRoot(absent);return absent;
            }
            if(create&&attributes(path)==null){
                requireRoot(new Root(List.copyOf(chain),folder,false));
                Files.createDirectory(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            }
            chain.add(directory(path));
        }
        Root actual=new Root(List.copyOf(chain),folder,true);
        requireRoot(actual);return actual;
    }

    private static Path canonicalParentChild(File file)throws IOException{
        Path raw=file.toPath().toAbsolutePath();
        return raw.getParent().toRealPath().resolve(raw.getFileName());
    }
    private static Path slot(Root root,int slot){return root.projectFolder.resolve("version-"+slot+".page");}
    private static Path pending(Root root){return root.projectFolder.resolve("pending.page");}
    private static BasicFileAttributes attributes(Path path)throws IOException{
        try{return Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}
        catch(NoSuchFileException absent){return null;}
    }
    private static Directory directory(Path path)throws IOException{
        BasicFileAttributes stat=attributes(path);
        if(stat==null||!stat.isDirectory()||stat.isSymbolicLink()||stat.fileKey()==null||!path.equals(path.toRealPath()))
            throw new IOException("原本机保留网页目录不可核对");
        return new Directory(path,stat.fileKey());
    }
    private static void requireRoot(Root root)throws IOException{
        for(Directory parent:root.parents)if(!parent.equals(directory(parent.path)))
            throw new IOException("原保留网页目录已变化");
        if(!root.exists){
            Path next=root.parents.size()==2?root.projectFolder.getParent():root.projectFolder;
            if(attributes(next)!=null)throw new IOException("原保留网页目录已变化");
        }
    }
    private static BasicFileAttributes leaf(Path path)throws IOException{
        BasicFileAttributes stat=attributes(path);
        if(stat==null)return null;
        if(!stat.isRegularFile()||stat.isSymbolicLink()||stat.fileKey()==null||!path.equals(path.toRealPath())||
                !(Files.getAttribute(path,"unix:nlink",LinkOption.NOFOLLOW_LINKS) instanceof Number links)||links.longValue()!=1)
            throw new IOException("原保留网页不是独立普通文件");
        return stat;
    }
    private static void requireSame(BasicFileAttributes a,BasicFileAttributes b)throws IOException{
        if(b==null||a.size()!=b.size()||!a.fileKey().equals(b.fileKey())||!a.lastModifiedTime().equals(b.lastModifiedTime()))
            throw new IOException("原保留网页文件身份已变化");
    }
    private static void syncDirectories(Root root)throws IOException{
        for(int i=root.parents.size()-1;i>=0;i--){
            requireRoot(root);
            try(var fd=FileChannel.open(root.parents.get(i).path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){fd.force(true);}
        }
        requireRoot(root);
    }
    private static void put(FileChannel out,byte[] bytes)throws IOException{
        ByteBuffer buffer=ByteBuffer.wrap(bytes);
        while(buffer.hasRemaining())if(out.write(buffer)<=0)throw new IOException("网页暂存写入未完成");
    }
    private static String project(ProjectWindow window){
        String value=requireId(window.projectId());requireCurrent(window,value);return value;
    }
    private static void requireCurrent(ProjectWindow window,String project){
        if(Thread.currentThread().isInterrupted()||!project.equals(window.projectId())||!window.current())
            throw new SecurityException("原私人项目窗口已离开，未继续网页保留操作");
    }
    private static String requireId(String id){
        if(id==null||!id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
            throw new SecurityException("原保留网页或项目身份无效");
        return id;
    }
    private static String requireSha(String sha){
        if(sha==null||!sha.matches("[a-f0-9]{64}"))throw new SecurityException("原保留网页摘要无效");
        return sha;
    }
    private static String string(JSONObject value,String key)throws IOException{
        if(value.opt(key) instanceof String text)return text;
        throw new IOException("原保留网页文字字段不可核对");
    }
    private static long number(JSONObject value,String key,long min,long max)throws IOException{
        Object raw=value.opt(key);
        if(raw instanceof Integer||raw instanceof Long){
            long n=((Number)raw).longValue();if(n>=min&&n<=max)return n;
        }
        throw new IOException("原保留网页数值字段不可核对");
    }
    private static void requireFields(JSONObject value,String... fields)throws IOException{
        if(value.length()!=fields.length)throw new IOException("原保留网页字段不同");
        for(String field:fields)if(!value.has(field))throw new IOException("原保留网页字段不同");
    }
}
