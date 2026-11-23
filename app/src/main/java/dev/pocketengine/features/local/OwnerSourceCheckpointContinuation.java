package dev.pocketengine.features.local;

import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import dev.pocketengine.features.local.OwnerSourceCheckpoints.CheckoutProof;
import dev.pocketengine.features.local.OwnerSourceCheckpoints.Opened;
import dev.pocketengine.features.local.OwnerSourceCheckpoints.Version;
import dev.pocketengine.features.local.project.OwnerDraftCopyShelf;
import dev.pocketengine.features.local.project.ProjectFileBytes;
import dev.pocketengine.features.local.project.ProjectPackageArchive;
import dev.pocketengine.features.local.project.ProjectSources;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Dormant owner-only copy of one retained version. Not a checkout chooser, import, or Work. */
public final class OwnerSourceCheckpointContinuation {
    static final String FIELD="ownerSourceCheckpointContinuation";
    private static final String FORMAT="pocketworld.owner-source-checkpoint-continuation.v1";
    private static final long REVIEW_MS=120_000;
    private static final int MAX_RECORD=2*1024*1024;
    private static final LinkOption NOFOLLOW=LinkOption.NOFOLLOW_LINKS;

    /** Future native owner window only. A rendered locator is not a valid implementation.
     * Selection/current checks are lock-free and do not read source or acquire Local. */
    public interface SelectionWindow extends OwnerSourceCheckpoints.Window {
        void requireSelectedCheckpoint(Version version)throws Exception;
    }
    public interface Window extends SelectionWindow {
        void requireConfirmedContinue(String reviewId,Version version,Target target)throws Exception;
        /** The same separate affirmative click, fenced against leaving or changing its selection.
         * Call commit on this worker before returning; no Local/store reentry or other IO. */
        void atConfirmedContinue(String reviewId,Version version,Target target,OwnerSourceCheckpoints.Commit commit)throws Exception;
    }

    public record Target(String projectId,String originatingCheckoutId,String checkpointId,String checkoutId,
            String name,String manifestIdentity,String archiveSha256,String filteredSourceDigest) {
        private JSONObject pin()throws Exception{
            return new JSONObject().put("format",FORMAT).put("projectId",projectId).put("originatingCheckoutId",originatingCheckoutId)
                .put("checkpointId",checkpointId).put("checkoutId",checkoutId).put("manifestIdentity",manifestIdentity)
                .put("archiveSha256",archiveSha256).put("filteredSourceDigest",filteredSourceDigest);
        }
    }
    public enum State { NOT_OBSERVED, UNCONFIRMED, CAPACITY, PUBLISHED }
    /** PUBLISHED is registration evidence, not a claim that later editor bytes never changed.
     * NOT_OBSERVED is an observation only, not a license to replay an uncertain attempt. */
    public record Observation(State state,Target target) {}
    public record Offer(Observation observed,Review review) {}

    @FunctionalInterface interface Reopen { Opened exact(OwnerSourceCheckpoints.Window selected)throws Exception; }

    public static final class Review implements AutoCloseable {
        private final LocalModule issuer;
        private final Window window;
        private final Version version;
        private final CheckoutProof origin;
        private final Target target;
        private final String id=UUID.randomUUID().toString();
        private final long expiresAt=SystemClock.elapsedRealtime()+REVIEW_MS;
        private boolean used;
        private volatile boolean retired;

        private Review(LocalModule issuer,Window window,Version version,CheckoutProof origin,Target target){
            this.issuer=issuer;this.window=window;this.version=version;this.origin=origin;this.target=target;
        }
        public String id(){return id;}
        public Version selected(){return version;}
        public Target target(){return target;}
        CheckoutProof origin(){return origin;}
        private synchronized void take(LocalModule original,Window current)throws Exception{
            if(issuer!=original||window!=current||used||retired||SystemClock.elapsedRealtime()>=expiresAt)
                throw new SecurityException("原继续核对已关闭、过期或用过，只核对原登记");
            used=true;
            requireSelected(window,version);
            window.requireConfirmedContinue(id,version,target);
        }
        @Override public void close(){retired=true;}

        /** Local owns the checkout monitor. A reserved directory is never reclaimed or overwritten. */
        Observation publish(LocalModule local,File root,Window originalWindow,Reopen reopen,ProjectPackageArchive.CopyCurrent requireOrigin,
                ProjectPackageArchive.CopyCurrent host)throws Exception{
            take(local,originalWindow);
            ProjectPackageArchive.CopyCurrent current=()->{
                host.check();
                if(retired)throw new SecurityException("原继续核对已关闭");
                requireSelected(window,version);
            };
            requireOrigin.check();current.check();
            Observation existing=observe(root,window,version);
            if(existing.state()!=State.NOT_OBSERVED)return existing;
            File destination=OwnerDraftCopyShelf.location(root,target.checkoutId,false),parent=destination.getParentFile();
            Object rootKey=directoryKey(root),parentKey=directoryKey(parent);
            File[] registered=parent.listFiles();
            if(registered==null)throw new IOException("无法核对原私人副本目录");
            if(registered.length>=40)return new Observation(State.CAPACITY,target);

            // Only the affirmative one-time call can get here. Reopen full original bytes now,
            // with the selected-row fence during body reads, not only before/after.
            var exactWindow=new OwnerSourceCheckpoints.Window(){
                @Override public String projectId(){return version.projectId();}
                @Override public String checkoutId(){return version.checkoutId();}
                @Override public void requireCurrent()throws Exception{current.check();}
            };
            current.check();Opened opened=reopen.exact(exactWindow);
            if(!version.equals(opened.version()))throw new SecurityException("所选源码快照已经变化");
            JSONObject original=opened.manifest();
            var inventory=original.getJSONArray("files");
            var directories=original.getJSONArray("directories");
            requireOrigin.check();current.check();

            try{
                Files.createDirectory(destination.toPath(),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                Object newKey=directoryKey(destination);
                syncDirectory(parent); // Keep an entered but incomplete attempt reserved even after restart.
                File files=new File(destination,"files");
                ProjectPackageArchive.CopyCurrent reserved=()->{
                    current.check();
                    requireKey(root,rootKey);requireKey(parent,parentKey);requireKey(destination,newKey);
                };
                var baseline=opened.materializeContinuation(exactWindow,version,files,reserved);
                Object filesKey=directoryKey(files);
                ProjectPackageArchive.CopyCurrent copied=()->{reserved.check();requireKey(files,filesKey);};
                if(inventory.length()!=baseline.length())throw new SecurityException("未完整保留原文件清单");
                for(int i=0;i<inventory.length();i++){
                    copied.check();JSONObject file=inventory.getJSONObject(i);
                    baseline.getJSONObject(i).put("binary","binary".equals(file.getString("kind")));
                    // The existing materializer sets x after its per-file sync. Flush
                    // that inode change as well before making the copy catalogue-visible.
                    if(file.getBoolean("executable"))syncOwnerMode(NativeCommandExecutor.within(files,file.getString("path")));
                }

                // The stock writer syncs each file. Sync only the explicit directories it
                // created, then fully reopen the new tree and compare paths, bytes/SHA and owner x-bit.
                for(int i=directories.length()-1;i>=0;i--){
                    copied.check();syncDirectory(NativeCommandExecutor.within(files,directories.getString(i)));
                }
                copied.check();syncDirectory(files);
                JSONObject actual=ProjectPackageArchive.captureOwnerCheckpoint(files,target.projectId,target.checkoutId,target.name,copied).manifest();
                if(actual.getJSONArray("omitted").length()!=0||
                        !ProjectSources.canonical(inventory).equals(ProjectSources.canonical(actual.getJSONArray("files")))||
                        !ProjectSources.canonical(directories).equals(ProjectSources.canonical(actual.getJSONArray("directories"))))
                    throw new SecurityException("新副本的完整文件、目录和执行位尚未核对");

                requireOrigin.check();copied.check();
                JSONObject record=new JSONObject().put("id",target.checkoutId).put("projectId",target.projectId)
                    .put("name",target.name).put("createdAt",System.currentTimeMillis()).put("files",baseline).put(FIELD,target.pin());
                byte[] expected=ProjectSources.canonical(record).getBytes(StandardCharsets.UTF_8);
                if(expected.length>MAX_RECORD)throw new IOException("新副本登记超出核对范围");
                File pending=new File(destination,"checkpoint-continuation.pending");
                File published=new File(destination,"checkout.json");
                writeNew(pending,expected);
                var staged=recordAttributes(pending);
                if(!java.util.Arrays.equals(expected,ProjectFileBytes.read(pending.toPath(),staged,MAX_RECORD)))
                    throw new IOException("原副本登记暂存未完整回读");
                syncDirectory(destination);

                // Admission last: the hard link cannot replace a previously registered checkout.
                // A late callback or another call cannot publish after this visit is retired.
                requireOrigin.check();copied.check();
                var entered=new AtomicInteger();
                Thread worker=Thread.currentThread();
                try{
                    window.atConfirmedContinue(id,version,target,()->{
                        if(Thread.currentThread()!=worker||entered.get()!=0)
                            throw new SecurityException("不会重复或迟到提交原继续动作");
                        copied.check();
                        var now=recordAttributes(pending);
                        if(!staged.fileKey().equals(now.fileKey())||staged.size()!=now.size()||
                                !staged.lastModifiedTime().equals(now.lastModifiedTime())||!entered.compareAndSet(0,1))
                            throw new SecurityException("原副本登记暂存已变化");
                        Files.createLink(published.toPath(),pending.toPath());entered.set(2);
                    });
                    if(entered.get()!=2)throw new IOException("没有观察到原确认登记");
                }finally{entered.set(3);}
                syncDirectory(destination);
                copied.check();
                var admitted=recordAttributes(published);
                if(!staged.fileKey().equals(admitted.fileKey())||
                        !java.util.Arrays.equals(expected,ProjectFileBytes.read(published.toPath(),admitted,MAX_RECORD)))
                    throw new IOException("尚未回读确切的新副本登记");
                copied.check();

                // Only positively read-back publication may retire this exact same-inode temp name.
                try{copied.check();if(Files.isSameFile(published.toPath(),pending.toPath()))Files.delete(pending.toPath());}
                catch(Exception left){/* A private second name is not another checkout. */}
                current.check();
                return new Observation(State.PUBLISHED,target);
            }catch(Exception uncertain){
                // No retry, rollback or cleanup on an ambiguous result. Keep the exact locator.
                current.check();
                return new Observation(State.UNCONFIRMED,target);
            }
        }
    }

    static void requireSelected(SelectionWindow window,Version version)throws Exception{
        if(window==null||version==null||!uuid(version.projectId())||!uuid(version.checkoutId())||!uuid(version.checkpointId())||
                version.storageSlot()<1||version.storageSlot()>OwnerSourceCheckpoints.MAX_VERSIONS||
                !sha(version.manifestIdentity())||!sha(version.archiveSha256())||!sha(version.filteredSourceDigest())||
                !version.projectId().equals(window.projectId())||!version.checkoutId().equals(window.checkoutId())||
                Thread.currentThread().isInterrupted())
            throw new SecurityException("只使用当前私人项目所选的确切快照");
        window.requireCurrent();window.requireSelectedCheckpoint(version);window.requireCurrent();
    }

    static Offer review(LocalModule issuer,File root,Window window,Version selected,CheckoutProof origin)throws Exception{
        Observation found=observe(root,window,selected);
        return new Offer(found,found.state!=State.NOT_OBSERVED?null:new Review(issuer,window,selected,origin,found.target));
    }

    /** Read-only reconciliation of exactly this stable target (even on another explicit visit).
     * No source tree read, cleanup, adoption, or fallback to the newest checkout. */
    static Observation observe(File root,SelectionWindow window,Version selected)throws Exception{
        requireSelected(window,selected);Target target=target(selected);
        State state;
        try{
            File directory=OwnerDraftCopyShelf.location(root,target.checkoutId,false);
            try{
                Files.readAttributes(directory.toPath(),BasicFileAttributes.class,NOFOLLOW);state=State.UNCONFIRMED;
            }catch(NoSuchFileException absent){
                requireSelected(window,selected);return new Observation(State.NOT_OBSERVED,target);
            }
            Object folder=directoryKey(directory);
            File files=new File(directory,"files"),metadata=new File(directory,"checkout.json");
            Object tree=directoryKey(files);
            byte[] bytes=ProjectFileBytes.read(metadata.toPath(),recordAttributes(metadata),MAX_RECORD);
            JSONObject row=new JSONObject(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
            requireKey(directory,folder);requireKey(files,tree);
            JSONObject pin=row.optJSONObject(FIELD);
            if(target.projectId.equals(row.opt("projectId"))&&target.checkoutId.equals(row.opt("id"))&&
                    target.name.equals(row.opt("name"))&&row.optLong("createdAt")>0&&row.optJSONArray("files")!=null&&pin!=null&&
                    ProjectSources.canonical(target.pin()).equals(ProjectSources.canonical(pin)))state=State.PUBLISHED;
        }catch(Exception unreadable){state=State.UNCONFIRMED;}
        requireSelected(window,selected);return new Observation(state,target);
    }

    private static Target target(Version v)throws Exception{
        String id=UUID.nameUUIDFromBytes(String.join("\n",FORMAT,v.projectId(),v.checkoutId(),v.checkpointId()).getBytes(StandardCharsets.UTF_8)).toString();
        if(id.equals(v.checkoutId()))throw new SecurityException("独立副本不能使用原副本编号");
        return new Target(v.projectId(),v.checkoutId(),v.checkpointId(),id,"快照副本 · "+v.checkpointId().substring(0,8),
                v.manifestIdentity(),v.archiveSha256(),v.filteredSourceDigest());
    }
    private static boolean uuid(String value){return value!=null&&value.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}");}
    private static boolean sha(String value){return value!=null&&value.matches("[a-f0-9]{64}");}
    private static Object directoryKey(File dir)throws Exception{
        var attrs=Files.readAttributes(dir.toPath(),BasicFileAttributes.class,NOFOLLOW);
        if(!attrs.isDirectory()||attrs.fileKey()==null||!dir.equals(dir.getCanonicalFile()))
            throw new IOException("原私人副本目录无法核对");
        return attrs.fileKey();
    }
    private static void requireKey(File dir,Object expected)throws Exception{
        if(!expected.equals(directoryKey(dir)))throw new SecurityException("原私人副本位置已变化");
    }
    private static BasicFileAttributes recordAttributes(File f)throws Exception{
        var attrs=Files.readAttributes(f.toPath(),BasicFileAttributes.class,NOFOLLOW);
        if(!attrs.isRegularFile()||attrs.fileKey()==null||attrs.size()<1||attrs.size()>MAX_RECORD||!f.equals(f.getCanonicalFile()))
            throw new IOException("原副本登记不可核对");
        return attrs;
    }
    private static void writeNew(File f,byte[] value)throws Exception{
        try(var channel=FileChannel.open(f.toPath(),Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,NOFOLLOW),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))){
            var buffer=ByteBuffer.wrap(value);while(buffer.hasRemaining())if(channel.write(buffer)<=0)throw new IOException("副本登记写入未继续");
            channel.force(true);
        }
    }
    private static void syncDirectory(File directory)throws Exception{
        var fd=Os.open(directory.toString(),OsConstants.O_RDONLY|OsConstants.O_NONBLOCK|OsConstants.O_NOFOLLOW,0);
        try{
            if(!OsConstants.S_ISDIR(Os.fstat(fd).st_mode))throw new IOException("原源码版本目录不可核对");
            Os.fsync(fd);
        }finally{Os.close(fd);}
    }
    private static void syncOwnerMode(File file)throws Exception{
        var fd=Os.open(file.toString(),OsConstants.O_RDONLY|OsConstants.O_NONBLOCK|OsConstants.O_NOFOLLOW,0);
        try{
            if(!OsConstants.S_ISREG(Os.fstat(fd).st_mode))throw new IOException("新副本执行位不属于普通文件");
            Os.fsync(fd);
        }finally{Os.close(fd);}
    }
}
