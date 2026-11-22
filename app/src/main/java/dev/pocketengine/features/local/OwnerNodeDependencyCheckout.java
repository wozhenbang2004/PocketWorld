package dev.pocketengine.features.local;

import dev.pocketengine.features.local.project.OwnerDraftCopyShelf;
import dev.pocketengine.features.local.project.ProjectBuildPlan;
import dev.pocketengine.features.local.project.ProjectFileBytes;
import dev.pocketengine.features.local.project.ProjectPackageArchive;
import dev.pocketengine.features.local.project.ProjectSources;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** One native review of a saved supported Vite source, with no runtime or old-copy write authority. */
public final class OwnerNodeDependencyCheckout {
    private static final String FORMAT="pocketworld.owner-node-dependency-checkout.v1", FIELD="ownerNodeDependencyCheckout";
    private static final int MAX_RECORD=1024*1024;
    private static final LinkOption NOFOLLOW=LinkOption.NOFOLLOW_LINKS;
    private final LocalModule issuer;
    private final String project,original,source,lock,reviewedLock,checkout,name;
    private final int files,bytes;
    private final long expires=System.nanoTime()+120_000_000_000L;
    private final long expiresAt=System.currentTimeMillis()+120_000L;
    private boolean taken;

    OwnerNodeDependencyCheckout(LocalModule issuer,JSONObject exactSource,JSONObject exactLock)throws Exception {
        this.issuer=issuer;
        project=ProjectBuildPlan.identity(exactSource.getString("projectId"));
        original=ProjectBuildPlan.identity(exactSource.getString("checkoutId"));
        source=exactSource.getString("sourceDigest");lock=exactLock.getString("lockSha256");reviewedLock=exactLock.toString();
        if(!source.matches("[a-f0-9]{64}")||!lock.matches("[a-f0-9]{64}")||!"npm".equals(exactLock.opt("manager"))||
                !"package-lock.json".equals(exactLock.opt("lockPath")))throw new SecurityException("不是已核对的本机 Vite 锁");
        checkout=identity(project,original,source,lock);
        if(checkout.equals(original))throw new SecurityException("新副本不能是原副本");
        files=exactSource.getInt("fileCount");bytes=exactSource.getInt("bytes");
        name=name(source);
    }
    private static String identity(String project,String original,String source,String lock){
        return UUID.nameUUIDFromBytes(String.join("\n",FORMAT,project,original,source,lock)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }
    private static String name(String source){return "依赖副本 · "+source.substring(0,8);}
    private static boolean uuid(Object value){return value instanceof String text&&text.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}");}
    private static boolean hash(Object value){return value instanceof String text&&text.matches("[a-f0-9]{64}");}
    private static boolean positive(Object value,long max){
        return (value instanceof Integer||value instanceof Long)&&((Number)value).longValue()>0&&((Number)value).longValue()<=max;
    }

    /** Re-prove the actual registration for an editor, not the old source or any execution permit.
     * The caller supplies a native checkout record already read for this catalogue/working snapshot. */
    static boolean publishedEditorCheckout(File root,JSONObject record,String project,String checkout)throws Exception{
        JSONObject pin=record.optJSONObject(FIELD);
        if(pin==null||pin.length()!=9||!FORMAT.equals(pin.opt("format"))||!uuid(project)||!uuid(checkout)||
                !project.equals(record.opt("projectId"))||!checkout.equals(record.opt("id"))||
                !project.equals(pin.opt("projectId"))||!checkout.equals(pin.opt("newCheckoutId"))||
                !uuid(pin.opt("checkoutId"))||checkout.equals(pin.opt("checkoutId"))||
                !hash(pin.opt("sourceDigest"))||!hash(pin.opt("lockSha256"))||
                !positive(pin.opt("fileCount"),ProjectSources.MAX_FILES)||!positive(pin.opt("bytes"),ProjectSources.MAX_TOTAL_BYTES)||
                !positive(record.opt("createdAt"),Long.MAX_VALUE)||record.optJSONArray("files")==null)return false;
        String initialSource=pin.getString("sourceDigest");
        if(!name(initialSource).equals(record.opt("name"))||!name(initialSource).equals(pin.opt("name"))||
                !checkout.equals(identity(project,pin.getString("checkoutId"),initialSource,pin.getString("lockSha256"))))return false;
        File registered=OwnerDraftCopyShelf.location(root,checkout,false);
        directoryKey(root);directoryKey(registered.getParentFile());directoryKey(registered);directoryKey(new File(registered,"files"));
        File metadata=new File(registered,"checkout.json");
        var physical=Files.readAttributes(metadata.toPath(),BasicFileAttributes.class,NOFOLLOW);
        return physical.isRegularFile()&&!physical.isSymbolicLink()&&physical.fileKey()!=null&&physical.size()>0&&
                physical.size()<=MAX_RECORD&&metadata.equals(metadata.getCanonicalFile());
    }
    String project(){return project;}
    String original(){return original;}
    String source(){return source;}
    public long expiresAt(){return expiresAt;}
    JSONObject lock()throws Exception{return new JSONObject(reviewedLock);}
    synchronized void take(LocalModule local){
        if(issuer!=local||taken||System.nanoTime()-expires>=0)throw new SecurityException("原依赖副本核对已关闭或用过");
        taken=true;
    }
    private JSONObject pin()throws Exception {
        return new JSONObject().put("format",FORMAT).put("projectId",project).put("checkoutId",original)
                .put("sourceDigest",source).put("lockSha256",lock).put("newCheckoutId",checkout)
                .put("name",name).put("fileCount",files).put("bytes",bytes);
    }
    private JSONObject status(String state)throws Exception {
        return pin().put("state",state).put("executionAuthorized",false).put("switchedCheckout",false)
                .put("originalCheckoutUnchanged",true).put("replayAllowed",false);
    }

    /** Read only, even after a lost confirmation. A surviving partial directory blocks this exact creation. */
    JSONObject observe(File root)throws Exception {
        File destination=OwnerDraftCopyShelf.location(root,checkout,false);
        if(Files.notExists(destination.toPath(),NOFOLLOW))return status("review");
        try {
            Object folder=directoryKey(destination);
            File tree=new File(destination,"files");Object treeKey=directoryKey(tree);
            File record=new File(destination,"checkout.json");
            var attrs=Files.readAttributes(record.toPath(),BasicFileAttributes.class,NOFOLLOW);
            byte[] bytes=ProjectFileBytes.read(record.toPath(),attrs,MAX_RECORD);
            JSONObject saved=new JSONObject(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
            if(!folder.equals(directoryKey(destination))||!treeKey.equals(directoryKey(tree))||!checkout.equals(saved.opt("id"))||!project.equals(saved.opt("projectId"))||
                    !name.equals(saved.opt("name"))||saved.optLong("createdAt")<=0||saved.optJSONArray("files")==null||
                    !ProjectSources.canonical(pin()).equals(ProjectSources.canonical(saved.optJSONObject(FIELD))))return status("partial");
            return status("published").put("createdAt",saved.getLong("createdAt"));
        }catch(Exception unavailable){return status("partial");}
    }
    private static Object directoryKey(File dir)throws Exception {
        var a=Files.readAttributes(dir.toPath(),BasicFileAttributes.class,NOFOLLOW);
        if(!a.isDirectory()||a.isSymbolicLink()||a.fileKey()==null||!dir.equals(dir.getCanonicalFile()))
            throw new SecurityException("原依赖副本目录无法核对");
        return a.fileKey();
    }
    private static void syncDirectory(File dir)throws Exception {
        try(var channel=FileChannel.open(dir.toPath(),StandardOpenOption.READ,NOFOLLOW)){channel.force(true);}
    }
    private static void writeNew(File file,byte[] value,boolean executable)throws Exception {
        try(var channel=FileChannel.open(file.toPath(),java.util.Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,NOFOLLOW),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(executable?"rwx------":"rw-------")))){
            var data=ByteBuffer.wrap(value);while(data.hasRemaining())if(channel.write(data)<=0)throw new java.io.IOException("副本文件写入未继续");
            channel.force(true);
        }
    }
    private static void mkdir(File dir)throws Exception {
        Files.createDirectory(dir.toPath(),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    /** Called while LocalModule owns its normal checkout monitor. Nothing under original is opened for write. */
    JSONObject publish(File root,ProjectPackageArchive.Frozen frozen,ProjectPackageArchive.CopyCurrent current,
                       ProjectPackageArchive.CopyCurrent exactOriginal)throws Exception {
        JSONObject manifest=frozen.manifest(),origin=manifest.getJSONObject("source");
        JSONArray inventory=manifest.getJSONArray("files"),directories=manifest.getJSONArray("directories");
        if(!"source_project".equals(manifest.opt("mode"))||!project.equals(origin.opt("projectId"))||
                !original.equals(origin.opt("checkoutId"))||!source.equals(origin.opt("sourceDigest"))||
                !source.equals(ProjectSources.manifestDigest(inventory))||files!=inventory.length()||bytes!=manifest.getLong("bytes"))
            throw new SecurityException("无法完整复制这版已保存源码，未复制部分文件");
        current.check();
        File destination=OwnerDraftCopyShelf.location(root,checkout,false),parent=destination.getParentFile();
        Object parentKey=directoryKey(parent),rootKey=directoryKey(root);
        File[] registered=parent.listFiles();if(registered==null||registered.length>=40)
            throw new IllegalStateException("本机副本位置不可核对或已满，保留原副本");
        // One reserved path per A + saved B identity. CreateDirectory and CREATE_NEW
        // below never replace a prior partial copy, even if a second window submits.
        mkdir(destination);
        Object destinationKey=directoryKey(destination);
        File newFiles=new File(destination,"files");
        mkdir(newFiles);Object filesKey=directoryKey(newFiles);
        ProjectPackageArchive.CopyCurrent guarded=()->{
            current.check();
            if(!rootKey.equals(directoryKey(root))||!parentKey.equals(directoryKey(parent))||
                    !destinationKey.equals(directoryKey(destination))||!filesKey.equals(directoryKey(newFiles)))
                throw new SecurityException("新依赖副本位置已变化，未覆盖或恢复写入");
        };
        var createdDirs=new ArrayList<File>();createdDirs.add(newFiles);
        JSONArray baseline=new JSONArray();
        for(int i=0;i<directories.length();i++){
            guarded.check();File child=NativeCommandExecutor.within(newFiles,directories.getString(i));mkdir(child);createdDirs.add(child);
        }
        for(int i=0;i<inventory.length();i++){
            guarded.check();JSONObject item=inventory.getJSONObject(i);String path=item.getString("path");
            boolean executable=item.getBoolean("executable");
            File target=NativeCommandExecutor.within(newFiles,path);writeNew(target,frozen.file(path),executable);
            baseline.put(new JSONObject().put("path",path).put("sha256",item.getString("sha256"))
                    .put("bytes",item.getLong("bytes")).put("sourceVersion",0).put("binary","binary".equals(item.opt("kind"))));
        }
        Collections.reverse(createdDirs);for(File dir:createdDirs){guarded.check();directoryKey(dir);syncDirectory(dir);}
        JSONObject readBack=ProjectPackageArchive.capture(newFiles,new JSONObject().put("projectId",project)
                .put("checkoutId",checkout).put("sourceDigest",source).put("name",name),"source_project","",false).manifest();
        if(readBack.getJSONArray("omitted").length()!=0||!ProjectSources.canonical(inventory).equals(ProjectSources.canonical(readBack.getJSONArray("files")))||
                !ProjectSources.canonical(directories).equals(ProjectSources.canonical(readBack.getJSONArray("directories"))))
            throw new SecurityException("没有核对到新副本的完整实际字节和目录");
        guarded.check();exactOriginal.check();guarded.check();
        JSONObject record=new JSONObject().put("id",checkout).put("projectId",project).put("name",name)
                .put("files",baseline).put("createdAt",System.currentTimeMillis()).put(FIELD,pin());
        byte[] encoded=record.toString().getBytes(StandardCharsets.UTF_8);
        if(encoded.length>MAX_RECORD)throw new SecurityException("新副本登记超出可核对范围");
        File pending=new File(destination,"checkout-registration.pending");
        writeNew(pending,encoded,false);
        var staged=Files.readAttributes(pending.toPath(),BasicFileAttributes.class,NOFOLLOW);
        if(staged.fileKey()==null||!java.util.Arrays.equals(encoded,ProjectFileBytes.read(pending.toPath(),staged,MAX_RECORD)))
            throw new SecurityException("新副本的登记字节无法核对");
        syncDirectory(destination);syncDirectory(parent);
        guarded.check();
        if(!staged.fileKey().equals(Files.readAttributes(pending.toPath(),BasicFileAttributes.class,NOFOLLOW).fileKey()))
            throw new SecurityException("原登记暂存已变化，未发布其他内容");
        // Only this last synced record makes the dedicated root visible to the existing checkout catalog.
        // link(2) cannot overwrite even if another exact publication won the name first.
        File published=new File(destination,"checkout.json");
        Files.createLink(published.toPath(),pending.toPath());syncDirectory(destination);
        JSONObject observed=observe(root);if(!"published".equals(observed.opt("state")))throw new java.io.IOException("新副本登记结果待核对");
        // Clean only this in-call temporary name once the actual commit is positively visible.
        try{guarded.check();if(Files.isSameFile(published.toPath(),pending.toPath()))Files.delete(pending.toPath());}
        catch(Exception left){/* A private temp name does not make an already verified checkout disappear. */}
        return observed;
    }
}
