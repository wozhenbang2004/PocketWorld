package dev.pocketengine.features.workspace;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashMap;

/** Owns only import/capture staging, never a retained ZIP or the original restore authority.
 * All staging writers retain the same OS lock through verification and publication.
 * Process death releases that lock, so a later owner can remove abandoned plaintext.
 * This shortens retention; it does not make local archive caches encrypted. */
final class OwnerBackupPendingFiles implements AutoCloseable {
    private static final String LOCK = ".owner-archive-pending-v1.lock";
    // On POSIX, closing any second descriptor for a locked inode can release this
    // process's OS lock. Check our live owner before opening another descriptor.
    private static final Map<Path,OwnerBackupPendingFiles> ACTIVE = new HashMap<>();
    private final Path root, lockPath;
    private final FileChannel channel;
    private final FileLock lock;
    private final Object rootKey, lockKey;

    private OwnerBackupPendingFiles(Path root, Path lockPath, FileChannel channel, FileLock lock,
                                    Object rootKey, Object lockKey) {
        this.root=root;this.lockPath=lockPath;this.channel=channel;this.lock=lock;
        this.rootKey=rootKey;this.lockKey=lockKey;
    }

    /** A concurrently active import is not abandoned. Startup must leave it alone. */
    static boolean recoverIfIdle(File directory) throws IOException {
        OwnerBackupPendingFiles owned=acquire(directory);
        if(owned==null)return false;
        try(OwnerBackupPendingFiles ignored=owned){owned.recover();return true;}
    }

    static OwnerBackupPendingFiles begin(File directory) throws IOException {
        OwnerBackupPendingFiles owned=acquire(directory);
        if(owned==null)throw new IOException("另一份原备份正在暂存或核对；未清理暂存或开始新的备份操作");
        try{owned.recover();return owned;}catch(IOException|RuntimeException failed){try{owned.close();}catch(IOException close){failed.addSuppressed(close);}throw failed;}
    }

    private static synchronized OwnerBackupPendingFiles acquire(File directory) throws IOException {
        Path root=directory.getAbsoluteFile().toPath();
        BasicFileAttributes parent=Files.readAttributes(root,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!parent.isDirectory()||parent.isSymbolicLink()||parent.fileKey()==null||!directory.getAbsoluteFile().equals(directory.getCanonicalFile()))
            throw new IOException("原备份暂存目录身份不可核对");
        if(ACTIVE.containsKey(root))return null;
        Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));
        Path guard=root.resolve(LOCK);
        FileChannel channel=FileChannel.open(guard,Set.of(StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        FileLock lock=null;
        try {
            BasicFileAttributes before=Files.readAttributes(guard,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!before.isRegularFile()||before.fileKey()==null)throw new IOException("原暂存锁不是固定的私人文件");
            Files.setPosixFilePermissions(guard,PosixFilePermissions.fromString("rw-------"));
            try{lock=channel.tryLock();}catch(OverlappingFileLockException active){channel.close();return null;}
            if(lock==null){channel.close();return null;}
            OwnerBackupPendingFiles owned=new OwnerBackupPendingFiles(root,guard,channel,lock,parent.fileKey(),before.fileKey());
            owned.current();ACTIVE.put(root,owned);return owned;
        }catch(IOException|RuntimeException failed){if(lock!=null)try{lock.release();}catch(IOException close){failed.addSuppressed(close);}try{channel.close();}catch(IOException close){failed.addSuppressed(close);}throw failed;}
    }

    File pending(String id) throws IOException {
        current();
        if(!uuid(id))throw new IOException("原备份暂存身份无效");
        Path path=root.resolve(id+".pending");
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("原暂存目标已存在；未覆盖");
        return path.toFile();
    }

    FileChannel createPlain(File pending) throws IOException {
        current();
        if(!root.equals(pending.toPath().getParent())||!pending.getName().endsWith(".pending")||!uuid(pending.getName().substring(0,pending.getName().length()-8)))
            throw new IOException("不是这份原导入的暂存路径");
        return privateFile(pending);
    }

    static FileChannel privateFile(File file) throws IOException {
        return FileChannel.open(file.toPath(),Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    }

    File capture(String id) throws IOException {
        current();if(!uuid(id))throw new IOException("原备份捕获暂存身份无效");
        return Files.createDirectory(root.resolve("capture-"+id),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))).toFile();
    }

    void discardCapture(File directory) throws IOException {
        current();Path path=directory.toPath();
        if(!root.equals(path.getParent())||!captureName(path.getFileName().toString()))throw new IOException("不是原备份的捕获暂存目录");
        recoverCapture(path);
    }

    /** link(2) creates one new name or fails; unlike ATOMIC_MOVE it cannot replace
     * an already existing target. A kill between link and unlink leaves two names
     * for the verified inode; recovery removes only pending, retaining the ZIP. */
    OwnerBackupArchive.Verified publish(File pending,File target,OwnerBackupArchive.Verified exact,dev.pocketengine.core.RunControl control)throws Exception{
        current();String name=pending.getName();
        if(!root.equals(pending.toPath().getParent())||!root.equals(target.toPath().getParent())||!name.endsWith(".pending")
                ||!uuid(name.substring(0,name.length()-8))||!target.getName().equals(name.substring(0,name.length()-8)+".zip"))
            throw new IOException("不是原备份暂存与发布目标；未写入其它位置");
        exact.requireEquivalentFile(pending,control);
        Files.createLink(target.toPath(),pending.toPath());
        try{
            exact.requireEquivalentFile(target,control);current();Files.delete(pending.toPath());
            OwnerBackupArchive.Verified published=OwnerBackupArchive.verify(target,control);
            if(!exact.sha256().equals(published.sha256()))throw new SecurityException("发布后的实际备份与原固定摘要不同");
            return published;
        }catch(Exception uncertain){throw new IOException("完整备份可能已经保存为本机缓存，但发布结果未完整确认；请先核对本机备份缓存，不要自动重试。",uncertain);}
    }

    void finishCapture(File stage,File pending,boolean published)throws IOException{
        current();String name=pending.getName();
        if(!root.equals(pending.toPath().getParent())||!name.endsWith(".pending")||!uuid(name.substring(0,name.length()-8))
                ||!root.equals(stage.toPath().getParent())||!stage.getName().equals("capture-"+name.substring(0,name.length()-8)))
            throw new IOException("不是这份原备份的捕获和待发布暂存；未清理其它位置");
        IOException failure=null;
        try{if(!published)Files.deleteIfExists(pending.toPath());}catch(IOException failed){failure=failed;}
        try{discardCapture(stage);}catch(IOException failed){if(failure==null)failure=failed;else failure.addSuppressed(failed);}
        if(failure!=null)throw new IOException(published
                ?"完整备份已保存为本机缓存，但捕获暂存清理未确认；请先核对本机备份缓存，不要自动重试。"
                :"备份操作或暂存清理未完整确认；请先核对本机备份缓存和原输入，不要自动重试。",failure);
    }

    private void recover() throws IOException {
        current();
        try(DirectoryStream<Path> entries=Files.newDirectoryStream(root)){
            for(Path path:entries){
                String name=path.getFileName().toString();
                if(captureName(name)){recoverCapture(path);continue;}
                if(!name.endsWith(".pending")||!uuid(name.substring(0,name.length()-8)))continue;
                current();
                BasicFileAttributes prior=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                if(!prior.isRegularFile()||prior.isSymbolicLink()||prior.fileKey()==null)throw new IOException("原废弃暂存不是固定文件；未跟随或递归清理");
                BasicFileAttributes now=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                if(!now.isRegularFile()||!prior.fileKey().equals(now.fileKey()))throw new IOException("原废弃暂存身份已变化；未清理");
                Files.delete(path);
                if(Files.exists(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("原废弃暂存移除未确认");
            }
        }
        current();
    }

    private void recoverCapture(Path directory) throws IOException {
        current();BasicFileAttributes original=Files.readAttributes(directory,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!original.isDirectory()||original.isSymbolicLink()||original.fileKey()==null)throw new IOException("原捕获暂存目录不可核对；未跟随链接或递归清理");
        Map<Path,Object> files=new LinkedHashMap<>();
        // Original capture writes a flat set of fixed objects. Validate the whole set
        // before deleting anything; unknown content and nested directories are retained.
        try(DirectoryStream<Path> entries=Files.newDirectoryStream(directory)){
            for(Path file:entries){
                BasicFileAttributes attrs=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                if(!captureObject(file.getFileName().toString())||!attrs.isRegularFile()||attrs.isSymbolicLink()||attrs.fileKey()==null)
                    throw new IOException("原捕获暂存含未知对象、目录或链接；未递归清理");
                files.put(file,attrs.fileKey());
            }
        }
        for(Map.Entry<Path,Object> entry:files.entrySet()){
            requireCapture(directory,original.fileKey());
            BasicFileAttributes now=Files.readAttributes(entry.getKey(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!now.isRegularFile()||!entry.getValue().equals(now.fileKey()))throw new IOException("原捕获暂存对象已变化；未继续清理");
            Files.delete(entry.getKey());
        }
        requireCapture(directory,original.fileKey());Files.delete(directory);
    }
    private void requireCapture(Path directory,Object key) throws IOException {
        current();BasicFileAttributes attrs=Files.readAttributes(directory,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!attrs.isDirectory()||!key.equals(attrs.fileKey()))throw new IOException("原捕获暂存目录已变化；未继续清理");
    }
    private static boolean captureName(String name){
        if(!name.startsWith("capture-"))return false;String suffix=name.substring(8);
        return uuid(suffix)||suffix.matches("[0-9]{1,20}"); // Previous Files.createTempDirectory names.
    }
    private static boolean captureObject(String name){
        if(name.matches("[a-f0-9]{64}\\.json"))return true;
        int dot=name.lastIndexOf('.');return dot>0&&uuid(name.substring(0,dot))&&Set.of("package","file","attachment").contains(name.substring(dot+1));
    }

    private void current() throws IOException {
        BasicFileAttributes parent=Files.readAttributes(root,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS),guard=Files.readAttributes(lockPath,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!lock.isValid()||!parent.isDirectory()||!rootKey.equals(parent.fileKey())||!guard.isRegularFile()||!lockKey.equals(guard.fileKey()))
            throw new IOException("原备份暂存锁或目录已变化；未继续清理或导入");
    }
    private static boolean uuid(String id){try{return UUID.fromString(id).toString().equals(id);}catch(IllegalArgumentException invalid){return false;}}
    @Override public void close() throws IOException {
        synchronized(OwnerBackupPendingFiles.class){
            if(ACTIVE.get(root)!=this)return;
            try{lock.release();}finally{try{channel.close();}finally{ACTIVE.remove(root,this);}}
        }
    }
}
