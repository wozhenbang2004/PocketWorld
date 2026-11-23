package dev.pocketengine.features.local;

import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import dev.pocketengine.features.local.project.ProjectPackageArchive;
import dev.pocketengine.features.local.project.ProjectSources;
import java.io.File;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Local native foundation only. This history is not a Work, a Workspace artifact, or a capability. */
public final class OwnerSourceCheckpoints {
    public static final int MAX_VERSIONS=4;
    public static final long MAX_STORED_BYTES=64L*1024*1024;
    private static final int MAX_HEADER_BYTES=2048;
    private static final long REVIEW_MS=120_000;
    private static final String SCHEMA="pocketworld.owner-source-checkpoint.v1";
    private static final String PENDING="pending.checkpoint", ARCHIVE="source.zip", HEADER="checkpoint.json";
    private static final Object LOCK=new Object(); // The main app process is the only store writer.
    private static final Set<MutationClaim> MUTATIONS=ConcurrentHashMap.newKeySet();
    private static final FileAttribute<Set<PosixFilePermission>> FILE_MODE=PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
    private static final FileAttribute<Set<PosixFilePermission>> DIRECTORY_MODE=PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
    private final Path base;
    private final BooleanSupplier alive;

    /** Captured by the future native host from the actual foreground private project and
     * selected checkout. requireCurrent is a non-blocking admission check, not a source read. */
    public interface Window {
        String projectId();
        String checkoutId();
        void requireCurrent() throws Exception;
    }
    @FunctionalInterface public interface Commit {void enter() throws Exception;}
    public interface SaveWindow extends Window {
        /** Observation of the same positively confirmed review before any bytes are staged. */
        void requireConfirmedSave(String reviewId,String manifestIdentity)throws Exception;
        /** The host independently proves the exact displayed manifest/exclusions and the owner's
         * separate Save action. Call commit synchronously, fenced against leaving that window.
         * Apart from the final exact stage/target check and rename, do not do IO,
         * acquire Local, or reenter the store. */
        void atConfirmedSave(String reviewId,String manifestIdentity,Commit commit)throws Exception;
        /** Called synchronously only after this exact prepared version takes the publication entry.
         * Retain only its locator so an interrupted owner visit can check this same save.
         * Refusal is before movement; do not throw after retaining the locator. */
        void publicationEntered(Version expected)throws Exception;
    }
    public interface PendingWindow extends Window {
        /** Same current owner and separate affirmative action; enter synchronously, no store reentry. */
        void atConfirmedAbandonment(String reviewId,Commit unlink)throws Exception;
    }

    /** This ID identifies only this capture and, if saved, its one exact checkpoint. */
    public static final class Review implements AutoCloseable {
        private final OwnerSourceCheckpoints issuer;
        private final SaveWindow window;
        private final Scope scope;
        private final CheckoutProof checkout;
        private final String id=UUID.randomUUID().toString();
        private final long capturedAt=System.currentTimeMillis(),until=SystemClock.elapsedRealtime()+REVIEW_MS;
        private ProjectPackageArchive.Frozen frozen;
        private volatile boolean retired;
        private Review(OwnerSourceCheckpoints issuer,SaveWindow window,Scope scope,CheckoutProof checkout,ProjectPackageArchive.Frozen frozen){
            this.issuer=issuer;this.window=window;this.scope=scope;this.checkout=checkout;this.frozen=frozen;
        }
        public String id(){return id;}
        public synchronized String manifestIdentity()throws Exception{return checked().identity;}
        public synchronized JSONObject manifest()throws Exception{
            requireWorker();JSONObject value=checked().manifest();
            issuer.current(window,scope);return value;
        }
        private ProjectPackageArchive.Frozen checked()throws Exception{
            issuer.current(window,scope);
            if(frozen==null||SystemClock.elapsedRealtime()>=until){frozen=null;throw new SecurityException("原源码核对已使用或过期");}
            return frozen;
        }
        private synchronized ProjectPackageArchive.Frozen consume(String reviewedManifest)throws Exception{
            var sealed=checked();frozen=null;
            if(!sealed.identity.equals(reviewedManifest))throw new SecurityException("未确认同一份源码及排除清单");
            return sealed;
        }
        @Override public synchronized void close(){retired=true;frozen=null;}
    }

    public record Version(String projectId,String checkoutId,String checkpointId,int storageSlot,String manifestIdentity,
            String filteredSourceDigest,String archiveSha256,long archiveBytes,long capturedAt,
            int fileCount,int omittedCount,long expandedBytes) {}
    /** Comparison accepts only two exact rows from this same still-current native visit. */
    public static final class Catalog {
        private final OwnerSourceCheckpoints issuer;
        private final Window window;
        private final Scope scope;
        private final List<Version> versions;
        private final boolean pending;
        private final long storedBytes;
        private Catalog(OwnerSourceCheckpoints issuer,Window window,Scope scope,List<Version> versions,boolean pending,long storedBytes){
            this.issuer=issuer;this.window=window;this.scope=scope;this.versions=List.copyOf(versions);
            this.pending=pending;this.storedBytes=storedBytes;
        }
        public List<Version> versions(){return versions;}
        public boolean pending(){return pending;}
        public long storedBytes(){return storedBytes;}
    }
    public enum EntryKind { FILE, DIRECTORY }
    /** These are changes within retained source, not claims about physical file deletion. */
    public enum ChangeKind { ADDED_TO_CHECKPOINT, REMOVED_FROM_CHECKPOINT, CHANGED_IN_CHECKPOINT, EXCLUDED_IN_BEFORE, EXCLUDED_IN_AFTER }
    public enum OmissionKind {
        DEPENDENCY_ENVIRONMENT_OR_PRIVATE_DIRECTORY, GENERATED_ROOT_NOT_SELECTED, CREDENTIAL_OR_PRIVATE_CONFIGURATION
    }
    /** Directory entries have no file SHA, size or executable bit. */
    public record Entry(EntryKind kind,String sha256,long bytes,boolean executable) {}
    /** Native proof of the exact omitted path or a covering ancestor, not proof that its child exists. */
    public record Exclusion(String omittedPath,OmissionKind kind,boolean exact) {}
    public record Change(String path,ChangeKind kind,Entry before,Entry after,Exclusion exclusion) {}
    public record Comparison(Version before,Version after,List<Change> paths) {
        public Comparison {paths=List.copyOf(paths);}
    }
    public enum SaveStatus { SAVED, CAPACITY, PENDING, NOT_SAVED, UNKNOWN }
    /** UNKNOWN carries the original expected locator only, not proof of publication. */
    public record SaveResult(SaveStatus status,Version version) {}
    public enum PendingStatus { CLEARED, NOT_CLEARED, UNKNOWN }
    public record PendingResult(String reviewId,PendingStatus status) {}
    /** One exact, private stage observation. Inodes and storage paths stay in native code. */
    public static final class PendingReview {
        private final OwnerSourceCheckpoints issuer;
        private final PendingWindow window;
        private final Scope scope;
        private final Path parent;
        private final PendingStamp stamp;
        private final String id=UUID.randomUUID().toString();
        private final long until=SystemClock.elapsedRealtime()+REVIEW_MS;
        private final AtomicBoolean used=new AtomicBoolean();
        private PendingReview(OwnerSourceCheckpoints issuer,PendingWindow window,Scope scope,Path parent,PendingStamp stamp){
            this.issuer=issuer;this.window=window;this.scope=scope;this.parent=parent;this.stamp=stamp;
        }
        public String id(){return id;}
        public int leafCount(){return (stamp.archive==null?0:1)+(stamp.header==null?0:1);}
        public long storedBytes(){return (stamp.archive==null?0:stamp.archive.size)+(stamp.header==null?0:stamp.header.size);}
    }

    /** No public constructor or source byte getter. Later native materialization must
     * reopen the selected Version afresh, not borrow a previously displayed reader. */
    public static final class Opened {
        private final OwnerSourceCheckpoints issuer;
        private final Window window;
        private final Scope scope;
        private final Version version;
        private final ProjectPackageArchive.Frozen frozen;
        private Opened(OwnerSourceCheckpoints issuer,Window window,Scope scope,Version version,ProjectPackageArchive.Frozen frozen){
            this.issuer=issuer;this.window=window;this.scope=scope;this.version=version;this.frozen=frozen;
        }
        public Version version(){return version;}
        public JSONObject manifest()throws Exception{
            requireWorker();issuer.current(window,scope);JSONObject value=frozen.manifest();issuer.current(window,scope);return value;
        }
        org.json.JSONArray materializeContinuation(Window original,Version exact,File empty,ProjectPackageArchive.CopyCurrent current)throws Exception{
            requireWorker();issuer.current(window,scope);
            if(window!=original||!version.equals(exact))throw new SecurityException("未核对同一窗口选择的原源码快照");
            return ProjectPackageArchive.materializeWorkspace(frozen,empty,()->{issuer.current(window,scope);current.check();});
        }
    }

    private record Scope(String project,String checkout) {}
    private record MutationClaim(Path store,Scope scope) {}
    private record PendingNode(Object key,long device,long inode,long changed,long size,FileTime modified) {}
    private record PendingStamp(Object parent,PendingNode stage,PendingNode archive,PendingNode header) {}
    record CheckoutProof(File files,Object fileKey,String name,String registrationIdentity) {}
    private record Scan(List<Version> saved,int free,long bytes) {}

    OwnerSourceCheckpoints(File noBackup,BooleanSupplier alive){
        this.base=noBackup.toPath().resolve("owner-source-checkpoints-v1");
        this.alive=Objects.requireNonNull(alive);
    }

    static void requireWorker(){
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("不得在项目界面线程采集或读取源码快照");
    }

    private Scope scope(Window window)throws Exception{
        Objects.requireNonNull(window);
        String project=window.projectId(),checkout=window.checkoutId();
        if(project==null||!project.matches("[A-Za-z0-9_-]{1,80}")||!uuid(checkout))
            throw new SecurityException("原项目及所选副本不可核对");
        Scope scope=new Scope(project,checkout);current(window,scope);return scope;
    }

    private void current(Window window,Scope scope)throws Exception{
        if(!alive.getAsBoolean()||!scope.project.equals(window.projectId())||!scope.checkout.equals(window.checkoutId()))
            throw new SecurityException("已离开原项目副本");
        window.requireCurrent();
    }

    /** LocalModule alone supplies the admitted registered checkout and both stable capture passes. */
    Review review(SaveWindow window,CheckoutProof checkout,ProjectPackageArchive.Frozen frozen)throws Exception{
        Scope scope=scope(window);requireCheckpointManifest(frozen.manifest(),scope);return new Review(this,window,scope,checkout,frozen);
    }

    Catalog catalog(Window window)throws Exception{
        requireWorker();Scope scope=scope(window);
        synchronized(LOCK){
            current(window,scope);
            Path root=lineage(scope,false);Scan scan=scan(root,scope);
            var versions=new ArrayList<>(scan.saved);
            versions.sort(Comparator.comparingLong(Version::capturedAt).reversed().thenComparing(Version::checkpointId));
            boolean pending=root!=null&&leaf(root.resolve(PENDING))!=null;
            current(window,scope);return new Catalog(this,window,scope,versions,pending,scan.bytes);
        }
    }
    /** Review on the same writer lock; never open archive/JSON bodies or inspect committed slots. */
    PendingReview reviewPendingAbandonment(PendingWindow window)throws Exception{
        requireWorker();Scope scope=scope(window);var claim=new MutationClaim(base,scope);
        synchronized(LOCK){
            current(window,scope);
            if(MUTATIONS.contains(claim))throw new SecurityException("原副本仍有保存或清理待核对");
            Path parent=lineage(scope,false);
            PendingStamp stamp=parent==null?null:pendingStamp(parent);
            current(window,scope);
            if(MUTATIONS.contains(claim))throw new SecurityException("原副本仍有保存或清理待核对");
            return stamp==null?null:new PendingReview(this,window,scope,parent,stamp);
        }
    }

    PendingResult abandonPending(PendingWindow window,PendingReview review)throws Exception{
        requireWorker();Scope scope=scope(window);
        if(review==null||review.issuer!=this||review.window!=window||!review.scope.equals(scope))
            throw new SecurityException("请明确确认当前原副本已核对的未完成暂存");
        if(!review.used.compareAndSet(false,true))throw new SecurityException("原清理核对已使用");
        var claim=new MutationClaim(base,scope);
        if(!MUTATIONS.add(claim))return new PendingResult(review.id,PendingStatus.NOT_CLEARED);
        try{synchronized(LOCK){
            var entry=new AtomicInteger();
            try{
                requirePendingCurrent(window,review);
                Path stage=review.parent.resolve(PENDING);
                window.atConfirmedAbandonment(review.id,()->{
                    requirePendingCurrent(window,review);
                    if(!entry.compareAndSet(0,1))throw new IOException("不会重复或迟到清除暂存");
                    unlinkPendingLeaf(review,stage,ARCHIVE,review.stamp.archive,ProjectPackageArchive.MAX_ARCHIVE_BYTES);
                    unlinkPendingLeaf(review,stage,HEADER,review.stamp.header,MAX_HEADER_BYTES);
                    requirePendingDirectory(review,stage);
                    Files.delete(stage); // Not recursive; unexpected surviving entries refuse rmdir.
                    entry.set(2);
                });
                if(entry.get()!=2)throw new IOException("没有观察到原暂存清除");
                syncDirectory(review.parent);
                if(!review.stamp.parent.equals(requireDirectory(review.parent).fileKey())||leaf(stage)!=null)
                    throw new IOException("原未完成暂存的清除尚未确认");
                current(window,scope);
                return new PendingResult(review.id,PendingStatus.CLEARED);
            }catch(Exception unavailable){
                return new PendingResult(review.id,entry.compareAndSet(0,3)?PendingStatus.NOT_CLEARED:PendingStatus.UNKNOWN);
            }
        }}finally{MUTATIONS.remove(claim);}
    }

    private void requirePendingCurrent(PendingWindow window,PendingReview review)throws Exception{
        current(window,review.scope);
        if(SystemClock.elapsedRealtime()>=review.until||!review.parent.equals(lineage(review.scope,false))||
                !review.stamp.equals(pendingStamp(review.parent)))
            throw new SecurityException("原未完成暂存已变化，不清除其他记录");
    }
    private static void requirePendingDirectory(PendingReview review,Path stage)throws Exception{
        if(!review.stamp.parent.equals(requireDirectory(review.parent).fileKey())||
                !review.stamp.stage.key.equals(requireDirectory(stage).fileKey()))
            throw new SecurityException("原未完成暂存目录已变化");
    }
    private static void unlinkPendingLeaf(PendingReview review,Path stage,String name,PendingNode expected,long max)throws Exception{
        if(expected==null)return;
        requirePendingDirectory(review,stage);
        Path item=stage.resolve(name);
        if(!expected.equals(pendingNode(item,false,max)))throw new SecurityException("原未完成暂存文件已变化");
        Files.delete(item);
    }
    private static PendingStamp pendingStamp(Path parent)throws Exception{
        Object key=requireDirectory(parent).fileKey();
        Path stage=parent.resolve(PENDING);PendingNode before=pendingNode(stage,true,0);
        if(before==null)return null;
        PendingNode archive=null,header=null;
        try(var entries=Files.newDirectoryStream(stage)){
            for(Path path:entries){
                String name=path.getFileName().toString();
                if(ARCHIVE.equals(name)&&archive==null)archive=pendingNode(stage.resolve(ARCHIVE),false,ProjectPackageArchive.MAX_ARCHIVE_BYTES);
                else if(HEADER.equals(name)&&header==null)header=pendingNode(stage.resolve(HEADER),false,MAX_HEADER_BYTES);
                else throw new SecurityException("原暂存含未声明项目，不清除");
                if((ARCHIVE.equals(name)?archive:header)==null)throw new IOException("原暂存文件变化");
            }
        }
        if(!before.equals(pendingNode(stage,true,0))||!key.equals(requireDirectory(parent).fileKey()))
            throw new IOException("原暂存或所属目录变化");
        return new PendingStamp(key,before,archive,header);
    }
    private static PendingNode pendingNode(Path path,boolean directory,long max)throws Exception{
        var before=leaf(path);
        if(before==null)return null;
        var file=path.toFile();
        if(before.fileKey()==null||
                (directory?!before.isDirectory():!before.isRegularFile()||before.size()<0||before.size()>max)||
                !file.equals(file.getCanonicalFile()))
            throw new SecurityException("原未完成暂存不是私有普通项目");
        var nativeStat=Os.lstat(path.toString());
        if(nativeStat.st_uid!=Process.myUid()||(nativeStat.st_mode&07777)!=(directory?0700:0600)||
                (directory?!OsConstants.S_ISDIR(nativeStat.st_mode):!OsConstants.S_ISREG(nativeStat.st_mode)||nativeStat.st_nlink!=1||nativeStat.st_size!=before.size()))
            throw new SecurityException("原未完成暂存私人属主或文件类型不同");
        var after=leaf(path);
        if(after==null||!sameFile(before,after))throw new IOException("原未完成暂存身份变化");
        return new PendingNode(before.fileKey(),nativeStat.st_dev,nativeStat.st_ino,nativeStat.st_ctime,before.size(),before.lastModifiedTime());
    }

    /** Local readback of one exact already shown version, not a full Open/Continue. */
    Version readReceipt(Catalog shown,Version selected)throws Exception {
        requireWorker();
        if(shown==null||shown.issuer!=this||selected==null||!shown.versions.contains(selected))
            throw new SecurityException("请核对本次目录中同一份已保留源码");
        Scope scope=shown.scope;Window window=shown.window;
        synchronized(LOCK){
            current(window,scope);
            Path root=lineage(scope,false);
            if(root==null)throw new SecurityException("没有核对到所选的原源码快照");
            readVerifiedIndex(window,scope,slot(root,selected.storageSlot),selected);
            return selected;
        }
    }

    /** Ordered owner selection, not latest, the live checkout, a text diff, or restore permission. */
    Comparison compare(Catalog shown,Version before,Version after)throws Exception{
        requireWorker();
        if(shown==null||shown.issuer!=this)throw new SecurityException("请从当前原副本目录选择两份已保存源码");
        Scope scope=shown.scope;Window window=shown.window;current(window,scope);
        if(before==null||after==null||!shown.versions.contains(before)||!shown.versions.contains(after)||
                before.storageSlot==after.storageSlot||before.checkpointId.equals(after.checkpointId))
            throw new SecurityException("请选择两份不同的原源码快照，不使用最新或其他副本");
        synchronized(LOCK){
            current(window,scope);
            Path root=lineage(scope,false);
            if(root==null)throw new SecurityException("没有核对到所选的两份原源码快照");
            var guard=(ProjectPackageArchive.CopyCurrent)()->current(window,scope);
            var from=retainedPaths(readVerifiedIndex(window,scope,slot(root,before.storageSlot),before),guard);
            var to=retainedPaths(readVerifiedIndex(window,scope,slot(root,after.storageSlot),after),guard);
            var paths=new TreeSet<>(from.entries.keySet());paths.addAll(to.entries.keySet());
            var changes=new ArrayList<Change>();
            for(String path:paths){
                guard.check();
                Entry a=from.entries.get(path),b=to.entries.get(path);
                if(Objects.equals(a,b))continue;
                Exclusion exclusion=a==null?from.exclusion(path):b==null?to.exclusion(path):null;
                ChangeKind kind=a==null?(exclusion==null?ChangeKind.ADDED_TO_CHECKPOINT:ChangeKind.EXCLUDED_IN_BEFORE):
                    b==null?(exclusion==null?ChangeKind.REMOVED_FROM_CHECKPOINT:ChangeKind.EXCLUDED_IN_AFTER):ChangeKind.CHANGED_IN_CHECKPOINT;
                changes.add(new Change(path,kind,a,b,exclusion));
            }
            guard.check();return new Comparison(before,after,changes);
        }
    }

    private record RetainedPaths(TreeMap<String,Entry> entries,TreeMap<String,OmissionKind> omissions) {
        Exclusion exclusion(String path){
            String candidate=path;
            for(;;){
                OmissionKind kind=omissions.get(candidate);
                boolean exact=candidate.equals(path);
                // This reason is emitted only for a nondirectory file, never for a skipped subtree.
                if(kind!=null&&(exact||kind!=OmissionKind.CREDENTIAL_OR_PRIVATE_CONFIGURATION))
                    return new Exclusion(candidate,kind,exact);
                int slash=candidate.lastIndexOf('/');
                if(slash<0)return null;
                candidate=candidate.substring(0,slash);
            }
        }
    }

    private static RetainedPaths retainedPaths(JSONObject index,ProjectPackageArchive.CopyCurrent current)throws Exception{
        var result=new TreeMap<String,Entry>();
        var files=index.getJSONArray("files");
        for(int i=0;i<files.length();i++){
            current.check();
            JSONObject file=files.getJSONObject(i);
            if(result.putIfAbsent(file.getString("path"),new Entry(EntryKind.FILE,file.getString("sha256"),file.getLong("bytes"),file.getBoolean("executable")))!=null)
                throw new SecurityException("原快照文件路径不唯一");
        }
        var dirs=index.getJSONArray("directories");
        var directory=new Entry(EntryKind.DIRECTORY,null,0,false);
        for(int i=0;i<dirs.length();i++){
            current.check();
            if(result.putIfAbsent(dirs.getString(i),directory)!=null)throw new SecurityException("原快照文件和目录路径冲突");
        }
        var reasons=new TreeMap<String,OmissionKind>();
        var omissions=index.getJSONArray("omitted");
        for(int i=0;i<omissions.length();i++){
            current.check();
            JSONObject omitted=omissions.getJSONObject(i);
            var reason=OmissionKind.valueOf(omitted.getString("reason").toUpperCase(java.util.Locale.ROOT));
            if(reasons.putIfAbsent(omitted.getString("path"),reason)!=null)
                throw new SecurityException("原快照排除路径不唯一");
        }
        return new RetainedPaths(result,reasons);
    }

    Opened open(Window window,Version expected)throws Exception{
        requireWorker();Scope scope=scope(window);
        if(expected==null||!scope.project.equals(expected.projectId)||!scope.checkout.equals(expected.checkoutId)||
                expected.storageSlot<1||expected.storageSlot>MAX_VERSIONS||!uuid(expected.checkpointId)||
                !sha(expected.archiveSha256)||!sha(expected.manifestIdentity))
            throw new SecurityException("请选择本副本确切的源码快照");
        synchronized(LOCK){
            current(window,scope);
            Path root=lineage(scope,false);
            if(root==null)throw new SecurityException("没有核对到该源码快照，不使用最新或其他副本");
            return readVerified(window,scope,slot(root,expected.storageSlot),expected);
        }
    }

    SaveResult save(SaveWindow window,CheckoutProof checkout,Review review,String reviewedManifest)throws Exception{
        requireWorker();Scope scope=scope(window);
        if(review==null||review.issuer!=this||review.window!=window||!review.scope.equals(scope)||!review.checkout.equals(checkout))
            throw new SecurityException("只保存原界面刚核对的源码和排除范围");
        var frozen=review.consume(reviewedManifest);
        var claim=new MutationClaim(base,scope);
        if(!MUTATIONS.add(claim))throw new SecurityException("原副本仍有保存或清理待核对");
        try{return saveClaimed(window,review,reviewedManifest,scope,frozen);}
        finally{MUTATIONS.remove(claim);}
    }
    private SaveResult saveClaimed(SaveWindow window,Review review,String reviewedManifest,Scope scope,
                                   ProjectPackageArchive.Frozen frozen)throws Exception{
        synchronized(LOCK){
            requireUnexpired(window,review);
            window.requireConfirmedSave(review.id,reviewedManifest);
            Path root=lineage(scope,true);Scan scan=scan(root,scope);
            Path pending=root.resolve(PENDING);
            if(leaf(pending)!=null)return new SaveResult(SaveStatus.PENDING,null);
            if(scan.free==0)return new SaveResult(SaveStatus.CAPACITY,null);
            Path target=slot(root,scan.free);
            Files.createDirectory(pending,DIRECTORY_MODE);
            BasicFileAttributes staged=requireDirectory(pending);
            var entry=new AtomicInteger(); // zero: never entered, one: attempted, two: retired
            Version expected=null;
            try{
                Path archive=pending.resolve(ARCHIVE);
                try(FileChannel channel=FileChannel.open(archive,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS),FILE_MODE)){
                    ProjectPackageArchive.write(frozen,Channels.newOutputStream(channel));channel.force(true);
                }
                long bytes=regular(archive,ProjectPackageArchive.MAX_ARCHIVE_BYTES).size();
                if(scan.bytes+bytes>MAX_STORED_BYTES)return new SaveResult(SaveStatus.CAPACITY,null);
                JSONObject manifest=requireCheckpointManifest(frozen.manifest(),scope);
                expected=new Version(scope.project,scope.checkout,review.id,scan.free,frozen.identity,manifest.getJSONObject("source").getString("sourceDigest"),
                    digest(archive,bytes,()->requireUnexpired(window,review)),bytes,review.capturedAt,
                    manifest.getJSONArray("files").length(),manifest.getJSONArray("omitted").length(),manifest.getLong("bytes"));
                writeHeader(pending,expected);
                if(scan.bytes+bytes+regular(pending.resolve(HEADER),MAX_HEADER_BYTES).size()>MAX_STORED_BYTES)
                    return new SaveResult(SaveStatus.CAPACITY,null);
                readVerified(window,scope,pending,expected);
                syncDirectory(pending);
                requireUnexpired(window,review);
                Version committedTarget=expected;
                window.atConfirmedSave(review.id,reviewedManifest,()->{
                    requireUnexpired(window,review);
                    if(!sameDirectory(staged,requireDirectory(pending))||leaf(target)!=null)
                        throw new SecurityException("原快照暂存或目标变化，不覆盖");
                    if(!entry.compareAndSet(0,1))throw new IOException("不会重复或迟到提交原源码快照");
                    try{window.publicationEntered(committedTarget);}
                    catch(Exception refused){entry.set(2);throw refused;}
                    Files.move(pending,target,StandardCopyOption.ATOMIC_MOVE);
                });
                if(entry.get()!=1)throw new IOException("没有观察到主人确认的保存");
                syncDirectory(root);
                // Do not infer success from the rename alone; reopen the committed exact record.
                readVerified(window,scope,target,expected);
                return new SaveResult(SaveStatus.SAVED,expected);
            }catch(Exception failure){
                return entry.get()==2||entry.compareAndSet(0,2)?new SaveResult(SaveStatus.NOT_SAVED,null):new SaveResult(SaveStatus.UNKNOWN,expected);
            }finally{
                if(entry.get()!=1){
                    entry.compareAndSet(0,2);
                    // Only this invocation's unentered stage. Existing/unknown stages are never retired here.
                    try{
                        if(sameDirectory(staged,requireDirectory(pending))){
                            Files.deleteIfExists(pending.resolve(HEADER));
                            Files.deleteIfExists(pending.resolve(ARCHIVE));
                            Files.delete(pending);
                            syncDirectory(root);
                        }
                    }catch(Exception unavailable){/* A leftover blocks another Save; it is never a version. */}
                }
            }
        }
    }

    private void requireUnexpired(SaveWindow window,Review review)throws Exception{
        current(window,review.scope);
        if(review.retired||SystemClock.elapsedRealtime()>=review.until)throw new SecurityException("原源码核对已关闭或过期");
    }

    private static JSONObject requireCheckpointManifest(JSONObject m,Scope scope)throws Exception{
        JSONObject s=m.getJSONObject("source");
        if(!"source_project".equals(m.getString("mode"))||!m.getString("entryPath").isEmpty()||!m.getString("previewRoot").isEmpty()||
                !Boolean.FALSE.equals(m.getJSONObject("policy").opt("generatedRootsIncluded"))||
                !scope.project.equals(s.opt("projectId"))||!scope.checkout.equals(s.opt("checkoutId"))||
                !ProjectSources.manifestDigest(m.getJSONArray("files")).equals(s.opt("sourceDigest")))
            throw new SecurityException("原快照不是该副本策略内的完整源码集合");
        var omitted=m.getJSONArray("omitted");
        for(int i=0;i<omitted.length();i++){
            var row=omitted.getJSONObject(i);String path=row.getString("path"),reason=row.getString("reason");
            if(!reason.equals(ProjectPackageArchive.excluded(path,true,false))&&!reason.equals(ProjectPackageArchive.excluded(path,false,false)))
                throw new SecurityException("原源码快照不能省略未选择的路径");
        }
        return m;
    }

    private static JSONObject header(Version v)throws Exception{
        return new JSONObject().put("schema",SCHEMA).put("projectId",v.projectId).put("checkoutId",v.checkoutId)
            .put("checkpointId",v.checkpointId).put("storageSlot",v.storageSlot).put("manifestIdentity",v.manifestIdentity)
            .put("filteredSourceDigest",v.filteredSourceDigest).put("archiveSha256",v.archiveSha256).put("archiveBytes",v.archiveBytes)
            .put("capturedAt",v.capturedAt).put("fileCount",v.fileCount).put("omittedCount",v.omittedCount).put("expandedBytes",v.expandedBytes);
    }

    private static void writeHeader(Path path,Version v)throws Exception{
        byte[] body=ProjectSources.canonical(header(v)).getBytes(StandardCharsets.UTF_8);
        if(body.length>MAX_HEADER_BYTES)throw new IOException("原快照登记超限");
        try(FileChannel c=FileChannel.open(path.resolve("checkpoint.json"),Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS),FILE_MODE)){
            var bytes=java.nio.ByteBuffer.wrap(body);while(bytes.hasRemaining())c.write(bytes);
            c.force(true);
        }
    }

    private static Version readHeader(Path path,Scope scope)throws Exception{
        requireDirectory(path);Path file=path.resolve("checkpoint.json");
        var before=regular(file,MAX_HEADER_BYTES);
        byte[] data=new byte[(int)before.size()];
        try(var in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){
            for(int off=0;off<data.length;){
                int n=in.read(data,off,data.length-off);
                if(n<=0)throw new IOException("原快照登记未读完整");off+=n;
            }
            if(in.read()!=-1)throw new IOException("原快照登记大小变化");
        }
        if(!sameFile(before,regular(file,MAX_HEADER_BYTES)))
            throw new IOException("原快照登记在核对时变化");
        String text=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(data)).toString();
        JSONObject o=new JSONObject(text);
        Version v=new Version(o.getString("projectId"),o.getString("checkoutId"),o.getString("checkpointId"),o.getInt("storageSlot"),o.getString("manifestIdentity"),
            o.getString("filteredSourceDigest"),o.getString("archiveSha256"),o.getLong("archiveBytes"),o.getLong("capturedAt"),
            o.getInt("fileCount"),o.getInt("omittedCount"),o.getLong("expandedBytes"));
        if(!text.equals(ProjectSources.canonical(header(v)))||!scope.project.equals(v.projectId)||!scope.checkout.equals(v.checkoutId)||
                v.storageSlot<1||v.storageSlot>MAX_VERSIONS||!uuid(v.checkpointId)||!sha(v.archiveSha256)||
                !sha(v.manifestIdentity)||!sha(v.filteredSourceDigest)||
                v.archiveBytes<22||v.archiveBytes>ProjectPackageArchive.MAX_ARCHIVE_BYTES||v.capturedAt<1||
                v.fileCount<1||v.fileCount>ProjectPackageArchive.MAX_FILES||v.omittedCount<0||v.omittedCount>ProjectPackageArchive.MAX_FILES||
                v.expandedBytes<0||v.expandedBytes>ProjectPackageArchive.MAX_TOTAL_BYTES)
            throw new SecurityException("原快照登记无效");
        return v;
    }

    private record ArchivePin(Path directory,Path archive,BasicFileAttributes identity,ProjectPackageArchive.CopyCurrent guard) {}
    private ArchivePin pinArchive(Window window,Scope scope,Path path,Version expected)throws Exception{
        current(window,scope);
        var dir=requireDirectory(path);
        Path archive=path.resolve("source.zip");
        if(!expected.equals(readHeader(path,scope)))throw new SecurityException("原快照登记不是所选版本");
        var guard=(ProjectPackageArchive.CopyCurrent)()->current(window,scope);
        if(!expected.archiveSha256.equals(digest(archive,expected.archiveBytes,guard)))throw new SecurityException("原快照包摘要已变化");
        return new ArchivePin(path,archive,dir,guard);
    }
    private static void requireVerifiedManifest(ArchivePin pin,Scope scope,Version expected,JSONObject m,String manifestIdentity)throws Exception{
        requireCheckpointManifest(m,scope);
        if(!expected.manifestIdentity.equals(manifestIdentity)||!expected.filteredSourceDigest.equals(m.getJSONObject("source").opt("sourceDigest"))||
                expected.fileCount!=m.getJSONArray("files").length()||expected.omittedCount!=m.getJSONArray("omitted").length()||
                expected.expandedBytes!=m.getLong("bytes")||!expected.archiveSha256.equals(digest(pin.archive,expected.archiveBytes,pin.guard))||
                !sameDirectory(pin.identity,requireDirectory(pin.directory))||!expected.equals(readHeader(pin.directory,scope)))
            throw new SecurityException("原源码快照字节或清单已变化");
        pin.guard.check();
    }
    private Opened readVerified(Window window,Scope scope,Path path,Version expected)throws Exception{
        ArchivePin pin=pinArchive(window,scope,path,expected);
        var frozen=ProjectPackageArchive.read(pin.archive.toFile(),pin.guard);
        requireVerifiedManifest(pin,scope,expected,frozen.manifest(),frozen.identity);
        return new Opened(this,window,scope,expected,frozen);
    }
    private JSONObject readVerifiedIndex(Window window,Scope scope,Path path,Version expected)throws Exception{
        ArchivePin pin=pinArchive(window,scope,path,expected);
        JSONObject index=ProjectPackageArchive.readManifestIndex(pin.archive.toFile(),pin.guard);
        requireVerifiedManifest(pin,scope,expected,index,ProjectSources.digest(index));
        return index;
    }

    private Path lineage(Scope scope,boolean create)throws Exception{
        Path path=base.getParent();requireDirectory(path);
        for(String key:new String[]{base.getFileName().toString(),scope.project,scope.checkout}){
            Path parent=path;
            path=parent.resolve(key);
            if(leaf(path)==null){
                if(!create)return null;
                Files.createDirectory(path,DIRECTORY_MODE);syncDirectory(parent);
            }
            requireDirectory(path);
        }
        return path;
    }

    private static Scan scan(Path root,Scope scope)throws Exception{
        var saved=new ArrayList<Version>();
        if(root==null)return new Scan(saved,0,0);
        int free=0;long bytes=0;var ids=new HashSet<String>();
        for(int i=1;i<=MAX_VERSIONS;i++){
            Path path=slot(root,i);
            if(leaf(path)==null){if(free==0)free=i;continue;}
            Version v=readHeader(path,scope);
            if(v.storageSlot!=i||!ids.add(v.checkpointId)||regular(path.resolve("source.zip"),ProjectPackageArchive.MAX_ARCHIVE_BYTES).size()!=v.archiveBytes)
                throw new SecurityException("原快照目录记录无法核对");
            bytes+=v.archiveBytes+regular(path.resolve("checkpoint.json"),MAX_HEADER_BYTES).size();
            if(bytes>MAX_STORED_BYTES)throw new SecurityException("原快照目录超出保留预算");
            saved.add(v);
        }
        return new Scan(saved,free,bytes);
    }
    private static Path slot(Path root,int value){return root.resolve("version-"+value);}

    private static String digest(Path file,long bytes,ProjectPackageArchive.CopyCurrent guard)throws Exception{
        var before=regular(file,ProjectPackageArchive.MAX_ARCHIVE_BYTES);
        if(bytes!=before.size())throw new IOException("原快照包大小已变化");
        var digest=java.security.MessageDigest.getInstance("SHA-256");
        try(var in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){
            byte[] buf=new byte[8192];long seen=0;
            for(;;){guard.check();int n=in.read(buf);if(n<0)break;
                seen+=n;if(seen>bytes)throw new IOException("原快照包大小已变化");digest.update(buf,0,n);}
            if(seen!=bytes)throw new IOException("未读完原快照包");
        }
        if(!sameFile(before,regular(file,ProjectPackageArchive.MAX_ARCHIVE_BYTES)))throw new IOException("读取期间原快照包发生变化");
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes){
        var out=new StringBuilder(64);for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
    }
    private static boolean uuid(String value){return value!=null&&value.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}");}
    private static boolean sha(String value){return value!=null&&value.matches("[a-f0-9]{64}");}
    private static BasicFileAttributes leaf(Path path)throws IOException{
        try{return Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}
        catch(NoSuchFileException absent){return null;}
    }
    private static BasicFileAttributes requireDirectory(Path path)throws IOException{
        var a=leaf(path);File f=path.toFile();
        if(a==null||!a.isDirectory()||a.fileKey()==null||!f.equals(f.getCanonicalFile()))throw new IOException("原私有快照目录不可核对");
        return a;
    }
    private static BasicFileAttributes regular(Path path,long max)throws IOException{
        var a=leaf(path);File f=path.toFile();
        if(a==null||!a.isRegularFile()||a.fileKey()==null||a.size()<1||a.size()>max||!f.equals(f.getCanonicalFile()))
            throw new IOException("原私有快照记录不可核对");
        return a;
    }
    private static boolean sameDirectory(BasicFileAttributes a,BasicFileAttributes b){return a.fileKey().equals(b.fileKey());}
    private static boolean sameFile(BasicFileAttributes a,BasicFileAttributes b){
        return sameDirectory(a,b)&&a.size()==b.size()&&a.lastModifiedTime().equals(b.lastModifiedTime());
    }
    private static void syncDirectory(Path path)throws Exception{
        var fd=Os.open(path.toString(),OsConstants.O_RDONLY|OsConstants.O_DIRECTORY|OsConstants.O_NOFOLLOW,0);
        try{Os.fsync(fd);}finally{Os.close(fd);}
    }
}
