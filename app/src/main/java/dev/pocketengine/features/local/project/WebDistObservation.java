package dev.pocketengine.features.local.project;

import dev.pocketengine.features.local.preview.ViteOutputMount;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded read of the same checkout's actual static output. Stores hashes/identity, never old bodies. */
final class WebDistObservation {
    private record FileStamp(Object identity, FileTime modified, FileTime changed, long bytes) {
        boolean rewrittenSince(FileStamp prior) {
            return !identity.equals(prior.identity) ||
                modified.compareTo(prior.modified)>0&&changed.compareTo(prior.changed)>0;
        }
    }
    private record Leaf(FileStamp stamp,String sha) {}
    private record DirectoryStamp(Object identity,FileTime modified) {
        static DirectoryStamp from(BasicFileAttributes info){return new DirectoryStamp(info.fileKey(),info.lastModifiedTime());}
    }
    private final Object checkout;
    private final Map<String,Leaf> files;
    private final Map<String,DirectoryStamp> directories;

    private WebDistObservation(Object checkout,Map<String,Leaf> files,Map<String,DirectoryStamp> directories){
        this.checkout=checkout;this.files=files;this.directories=directories;
    }
    static WebDistObservation read(Path root,boolean nestedPublic)throws Exception {
        return scan(root,nestedPublic,true,false,false);
    }
    static void requireOutputPaths(Path root,boolean nestedPublic,boolean observeOrdinaryViteExit)throws Exception {
        scan(root,nestedPublic,false,true,observeOrdinaryViteExit);
    }
    private static WebDistObservation scan(Path root,boolean nestedPublic,boolean readBodies,boolean requireAssets,
            boolean observeOrdinaryViteExit)throws Exception {
        // An old flat metadata-only preflight did not need a directory key.
        // Physical byte observations and the new public tree always do.
        boolean requireKey=readBodies||nestedPublic;
        root=root.toAbsolutePath();Object checkout=directory(root,requireKey).fileKey();Path dist=root.resolve("dist");
        BasicFileAttributes top;
        try{top=directory(dist,requireKey);}
        catch(NoSuchFileException absent){
            if(requireAssets)throw new SecurityException("原构建没有可核对的 dist/assets，不采用当前其他目录");
            if(!Objects.equals(checkout,directory(root,requireKey).fileKey()))throw new SecurityException("读取时原网页目录已变化");
            return new WebDistObservation(checkout,Map.of(),Map.of());
        }
        Scan scan=new Scan(nestedPublic,readBodies,observeOrdinaryViteExit);
        scan.walk(dist,"",top);
        if(requireAssets&&!scan.directories.containsKey("dist/assets"))
            throw new SecurityException("原构建没有可核对的 dist/assets，不采用当前其他目录");
        if(!Objects.equals(checkout,directory(root,requireKey).fileKey()))throw new SecurityException("读取时原网页目录已变化");
        return new WebDistObservation(checkout,scan.leaves,scan.directories);
    }
    private static final class Scan {
        private final boolean nestedPublic,readBodies,observeOrdinaryViteExit,requireKey;
        private final Map<String,Leaf> leaves=new TreeMap<>();
        private final Map<String,DirectoryStamp> directories=new TreeMap<>();
        private int entries=1,fileCount;
        private long bytes;
        private Scan(boolean nestedPublic,boolean readBodies,boolean observeOrdinaryViteExit){
            this.nestedPublic=nestedPublic;this.readBodies=readBodies;this.observeOrdinaryViteExit=observeOrdinaryViteExit;
            requireKey=readBodies||nestedPublic;
        }
        private void walk(Path path,String folder,BasicFileAttributes before)throws Exception {
            boolean publicFolder=!folder.isEmpty()&&!folder.equals("assets");
            String directoryPath=folder.isEmpty()?"dist":"dist/"+folder;
            if(publicFolder&&(!nestedPublic||!ViteOutputMount.publicStaticDirectory(folder)||
                    ProjectPackageArchive.excluded(directoryPath,true,true)!=null))throw invalidLayout(true);
            int previousCount=fileCount;
            try(var children=Files.newDirectoryStream(path)){
                for(Path child:children){
                    if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("原网页读取已取消");
                    if(++entries>OriginalNodeWebOutput.VITE_ENTRIES)throw new SecurityException("原 Vite 网页包超过目录及文件条目上限");
                    String name=child.getFileName().toString(),member=folder.isEmpty()?name:folder+"/"+name;
                    var info=Files.readAttributes(child,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                    if(info.isSymbolicLink())throw invalidLayout(false);
                    requireCanonicalPath(child);
                    if(info.isDirectory()){
                        requireDirectory(info,requireKey);
                        walk(child,member,info);
                    }else add(child,member,info,publicFolder);
                }
            }
            if(publicFolder&&previousCount==fileCount)throw invalidLayout(true); // No empty extra directories in the archive.
            DirectoryStamp original=DirectoryStamp.from(before);
            if(!original.equals(DirectoryStamp.from(directory(path,requireKey))))throw new SecurityException("读取时原网页目录已变化");
            directories.put(directoryPath,original);
        }
        private void add(Path path,String member,BasicFileAttributes info,boolean publicFolder)throws Exception {
            String relative="dist/"+member;
            if(!info.isRegularFile()||info.isSymbolicLink())throw invalidLayout(false);
            if(!OriginalNodeWebOutput.viteFilePath(member,nestedPublic)||ProjectPackageArchive.excluded(relative,false,true)!=null)
                throw invalidLayout(publicFolder);
            int budget=(int)Math.min(ProjectPackageArchive.MAX_FILE_BYTES,OriginalNodeWebOutput.VITE_BYTES-bytes);
            if(++fileCount>OriginalNodeWebOutput.VITE_FILES||info.size()<1||info.size()>budget)
                throw new SecurityException("原 Vite 网页包超过文件或字节上限");
            if(readBodies){
                FileStamp stable=stamp(path,info);
                byte[] actual=ProjectFileBytes.read(path,info,budget);
                FileStamp observed=stamp(path,Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS));
                requireCanonicalPath(path);
                if(!stable.equals(observed)||
                   leaves.put(relative,new Leaf(stable,ProjectSources.sha256(actual)))!=null)
                    throw new SecurityException("原网页文件在核对中变化");
            }else if(!(Files.getAttribute(path,"unix:nlink",LinkOption.NOFOLLOW_LINKS) instanceof Number links)||links.longValue()!=1)
                throw invalidLayout(false);
            bytes+=info.size();
        }
        private SecurityException invalidLayout(boolean nested){
            return nested&&observeOrdinaryViteExit?new OriginalNodeWebOutput.UnsupportedNestedViteDirectory():
                new SecurityException("原 Vite 输出含不能固定的文件类型、链接或目录；未保存部分成果");
        }
    }
    private static void requireCanonicalPath(Path path)throws Exception {
        if(!path.equals(path.toFile().getCanonicalFile().toPath()))throw new SecurityException("原网页路径不是副本内的普通路径");
    }
    private static void requireDirectory(BasicFileAttributes info,boolean requireKey){
        if(!info.isDirectory()||info.isSymbolicLink()||requireKey&&info.fileKey()==null)throw new SecurityException("无法核对原网页的普通目录");
    }
    private static BasicFileAttributes directory(Path path,boolean requireKey)throws Exception {
        var info=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        requireDirectory(info,requireKey);requireCanonicalPath(path);return info;
    }
    private static FileStamp stamp(Path path,BasicFileAttributes info)throws Exception {
        Map<String,Object> unix=Files.readAttributes(path,"unix:nlink,ctime",LinkOption.NOFOLLOW_LINKS);
        if(!info.isRegularFile()||info.fileKey()==null||!(unix.get("nlink") instanceof Number links)||links.longValue()!=1||
           !(unix.get("ctime") instanceof FileTime changed))throw new SecurityException("无法核对原网页的普通单链接文件");
        return new FileStamp(info.fileKey(),info.lastModifiedTime(),changed,info.size());
    }
    void requireSameRead(WebDistObservation again)throws Exception{
        if(!checkout.equals(again.checkout)||!files.equals(again.files)||!directories.equals(again.directories))
            throw new SecurityException("退出后实际网页仍在变化，未采用部分结果");
    }
    JSONObject changedTo(WebDistObservation after,ProjectPackageArchive.Frozen frozen,boolean directVite)throws Exception {
        if(!checkout.equals(after.checkout))throw new SecurityException("原物理工作副本已改变");
        JSONArray archivedDirectories=frozen.manifest().getJSONArray("directories");
        if(archivedDirectories.length()!=after.directories.size())throw new SecurityException("固定包目录与原退出不同");
        for(int i=0;i<archivedDirectories.length();i++)if(!after.directories.containsKey(archivedDirectories.getString(i)))
            throw new SecurityException("固定包目录与原退出不同");
        String former=treeDigest(),next=after.treeDigest();
        boolean equal=former.equals(next);
        if((equal&&(!directVite||!identicalReplacement(after)))||!next.equals(archiveTreeDigest(frozen)))
            throw new SecurityException("本次没有可分辨的新网页，或固定件与原退出字节不同");
        if(equal)return new JSONObject().put("before",former).put("after",next).put("result",OriginalNodeWebOutput.IDENTICAL_REWRITE);
        for(var file:after.files.entrySet()){
            Leaf prior=files.get(file.getKey()),current=file.getValue();
            if(prior!=null&&prior.sha.equals(current.sha)&&!current.stamp.rewrittenSince(prior.stamp))
                throw new SecurityException("原地址仍含未重写的旧文件，不将其作为本次整站成果");
        }
        return new JSONObject().put("before",former).put("after",next);
    }
    /** Equal content must come from a wholly different set of physical files.
     * Timestamps, a directory change or moving old files between paths are not enough. */
    private boolean identicalReplacement(WebDistObservation after) {
        if(files.isEmpty()||!files.keySet().equals(after.files.keySet()))return false;
        var identities=new HashSet<Object>();
        for(Leaf leaf:files.values())
            if(leaf.stamp.identity==null||!identities.add(leaf.stamp.identity))return false;
        for(var actual:after.files.entrySet()){
            Leaf earlier=files.get(actual.getKey()),current=actual.getValue();
            if(current.stamp.identity==null||!identities.add(current.stamp.identity)||
                earlier.stamp.bytes!=current.stamp.bytes||!earlier.sha.equals(current.sha))return false;
        }
        return true;
    }
    private String treeDigest()throws Exception {
        JSONArray entries=new JSONArray();
        for(var file:files.entrySet())entries.put(new JSONArray().put(file.getKey()).put(file.getValue().sha).put(file.getValue().stamp.bytes));
        return digest(entries);
    }
    static String archiveTreeDigest(ProjectPackageArchive.Frozen frozen)throws Exception {
        JSONArray files=frozen.manifest().getJSONArray("files"),entries=new JSONArray();TreeMap<String,JSONObject> sorted=new TreeMap<>();
        for(int i=0;i<files.length();i++){JSONObject leaf=files.getJSONObject(i);sorted.put(leaf.getString("path"),leaf);}
        for(var file:sorted.entrySet())entries.put(new JSONArray().put(file.getKey()).put(file.getValue().getString("sha256")).put(file.getValue().getLong("bytes")));
        return digest(entries);
    }
    private static String digest(JSONArray entries)throws Exception{
        return ProjectSources.digest(new JSONObject().put("format","pocketworld.web-output-tree.v1").put("files",entries));
    }
}
