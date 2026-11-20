package dev.pocketengine.features.local.project;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Map;
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
    private final Object checkout;
    private final Map<String,Leaf> files;

    private WebDistObservation(Object checkout,Map<String,Leaf> files){this.checkout=checkout;this.files=files;}
    static WebDistObservation read(Path root)throws Exception {
        Object checkout=directory(root).fileKey();
        Path dist=root.resolve("dist"),assets=dist.resolve("assets");
        TreeMap<String,Leaf> leaves=new TreeMap<>();
        BasicFileAttributes top;
        try{top=directory(dist);}
        catch(NoSuchFileException absent){return new WebDistObservation(checkout,leaves);}
        BasicFileAttributes nested=null;long bytes=0;
        try(var paths=Files.newDirectoryStream(dist)){
            for(Path path:paths){
                if(path.equals(assets)){
                    nested=directory(path);
                    try(var entries=Files.newDirectoryStream(path)){
                        for(Path entry:entries)bytes+=add(leaves,entry,"dist/assets/",false,bytes);
                    }
                }else bytes+=add(leaves,path,"dist/",true,bytes);
            }
        }
        if(!sameDirectory(top,directory(dist))||nested!=null&&!sameDirectory(nested,directory(assets))||
           !checkout.equals(directory(root).fileKey()))throw new SecurityException("读取时原网页目录已变化");
        return new WebDistObservation(checkout,leaves);
    }
    private static BasicFileAttributes directory(Path path)throws Exception {
        var attrs=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!attrs.isDirectory()||attrs.fileKey()==null)throw new SecurityException("无法核对原网页的普通目录");
        return attrs;
    }
    private static boolean sameDirectory(BasicFileAttributes before,BasicFileAttributes after){
        return before.fileKey().equals(after.fileKey())&&before.lastModifiedTime().equals(after.lastModifiedTime());
    }
    private static FileStamp stamp(Path path,BasicFileAttributes info)throws Exception {
        Map<String,Object> unix=Files.readAttributes(path,"unix:nlink,ctime",LinkOption.NOFOLLOW_LINKS);
        if(!info.isRegularFile()||info.fileKey()==null||!(unix.get("nlink") instanceof Number links)||links.longValue()!=1||
           !(unix.get("ctime") instanceof FileTime changed))throw new SecurityException("无法核对原网页的普通单链接文件");
        return new FileStamp(info.fileKey(),info.lastModifiedTime(),changed,info.size());
    }
    private static long add(Map<String,Leaf> files,Path path,String prefix,boolean top,long used)throws Exception {
        String name=path.getFileName().toString();
        if(files.size()>=OriginalNodeWebOutput.VITE_FILES||!OriginalNodeWebOutput.vitePath(name,top))
            throw new SecurityException("原网页目录超出可完整核对的范围");
        var attrs=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        int budget=(int)Math.min(ProjectPackageArchive.MAX_FILE_BYTES,OriginalNodeWebOutput.VITE_BYTES-used);
        if(attrs.size()<1||attrs.size()>budget)throw new SecurityException("原网页文件超出可完整读取的范围");
        FileStamp stable=stamp(path,attrs);
        byte[] bytes=ProjectFileBytes.read(path,attrs,budget);
        if(!stable.equals(stamp(path,Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS))))
            throw new SecurityException("原网页文件在核对中变化");
        files.put(prefix+name,new Leaf(stable,ProjectSources.sha256(bytes)));
        return bytes.length;
    }
    void requireSameRead(WebDistObservation again)throws Exception{
        if(!checkout.equals(again.checkout)||!files.equals(again.files))throw new SecurityException("退出后实际网页仍在变化，未采用部分结果");
    }
    JSONObject changedTo(WebDistObservation after,ProjectPackageArchive.Frozen frozen)throws Exception {
        if(!checkout.equals(after.checkout))throw new SecurityException("原物理工作副本已改变");
        String former=treeDigest(),next=after.treeDigest();
        if(former.equals(next)||!next.equals(archiveTreeDigest(frozen)))throw new SecurityException("本次没有可分辨的新网页，或固定件与原退出字节不同");
        for(var file:after.files.entrySet()){
            Leaf prior=files.get(file.getKey()),current=file.getValue();
            if(prior!=null&&prior.sha.equals(current.sha)&&!current.stamp.rewrittenSince(prior.stamp))
                throw new SecurityException("原地址仍含未重写的旧文件，不将其作为本次整站成果");
        }
        return new JSONObject().put("before",former).put("after",next);
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
