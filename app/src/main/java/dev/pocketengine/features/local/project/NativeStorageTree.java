package dev.pocketengine.features.local.project;

import dev.pocketengine.core.RunControl;
import java.io.File;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import org.json.*;

/** Native private-directory inventory. Logical bytes are not disk blocks, and a
 * filesystem snapshot is not an OS sandbox. No file contents are read or logged. */
public final class NativeStorageTree {
    public static final int MAX_ENTRIES=200000,MAX_MANIFEST_CHARS=16*1024*1024;
    private NativeStorageTree(){}
    private static final class Node {
        final String path,key,type;final long bytes;final java.nio.file.attribute.FileTime modified;final Object fileKey;
        Node(Path root,Path file,BasicFileAttributes attributes)throws Exception {
            path=root.equals(file)?"":root.relativize(file).toString().replace(File.separatorChar,'/');if(!root.equals(file)&&!root.resolve(path).equals(file))throw new SecurityException("存储文件名不是可往返UTF-8路径，未改名删除");if(path.length()>4096)throw new IllegalArgumentException("存储路径超过支持的4096字符");java.nio.charset.StandardCharsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(path));fileKey=attributes.fileKey();if(fileKey==null)throw new SecurityException("此平台未提供原缓存文件身份，不能用弱清单删除");key=String.valueOf(fileKey);modified=attributes.lastModifiedTime();bytes=attributes.isRegularFile()?attributes.size():0;type=attributes.isDirectory()?"directory":attributes.isRegularFile()?"file":attributes.isSymbolicLink()?"link":"special";
            if(type.equals("special"))throw new SecurityException("存储范围包含特殊文件，未将其当作可回收缓存");
        }
        JSONObject pin()throws Exception{return new JSONObject().put("path",path).put("kind",type).put("bytes",bytes).put("modified",modified.toString()).put("fileKey",key);}
        void unchanged(Path target)throws Exception {BasicFileAttributes actual=Files.readAttributes(target,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);String actualType=actual.isDirectory()?"directory":actual.isRegularFile()?"file":actual.isSymbolicLink()?"link":"special";if(!type.equals(actualType)||!Objects.equals(fileKey,actual.fileKey())||!modified.equals(actual.lastModifiedTime())||bytes!=(actual.isRegularFile()?actual.size():0))throw new SecurityException("所选存储资源在审阅后已变化；未继续删除");}
    }
    public static final class Snapshot {
        private final Path root;private final List<Node> nodes;private final String digest;public final long logicalBytes;public final int files,links;
        private Snapshot(Path root,List<Node> nodes,String digest,long bytes,int files,int links){this.root=root;this.nodes=Collections.unmodifiableList(nodes);this.digest=digest;logicalBytes=bytes;this.files=files;this.links=links;}
        public String digest(){return digest;}
        public JSONObject metadata()throws Exception{return new JSONObject().put("snapshotDigest",digest).put("logicalBytes",logicalBytes).put("files",files).put("links",links).put("entries",nodes.size()).put("complete",true).put("followsLinks",false).put("allocatedBlocksMeasured",false).put("filesystemSandbox",false);}
        public JSONArray paths(int max)throws Exception{JSONArray listed=new JSONArray();for(int i=0;i<nodes.size()&&i<max;i++)listed.put(nodes.get(i).pin());return listed;}
        public void requireCurrent(RunControl original)throws Exception {Snapshot current=scan(root.toFile(),original);if(!digest.equals(current.digest))throw new SecurityException("原存储清单已变化，请重新读取审阅；没有删除");}
        /** Delete only nodes present in the original exact snapshot. A new child
         * or changed identity stops this phase; unknown resources are not swept. */
        public JSONObject delete(RunControl original)throws Exception {
            requireCurrent(original);int removed=0;long logical=0;List<Node> reverse=new ArrayList<>(nodes);reverse.sort(Comparator.comparingInt((Node node)->node.path.isEmpty()?0:node.path.split("/").length).reversed().thenComparing(node->node.path,Comparator.reverseOrder()));
            for(Node node:reverse){original.checkpoint();Path target=node.path.isEmpty()?root:root.resolve(node.path);requireAncestors(root,target);if(node.type.equals("directory")){BasicFileAttributes actual=Files.readAttributes(target,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(!actual.isDirectory()||!Objects.equals(node.fileKey,actual.fileKey()))throw new SecurityException("原缓存目录身份已变化，未继续清理");}else node.unchanged(target);Files.delete(target);removed++;logical+=node.bytes;}
            return new JSONObject().put("snapshotDigest",digest).put("removedEntries",removed).put("logicalBytesRemoved",logical).put("targetAbsent",!Files.exists(root,LinkOption.NOFOLLOW_LINKS)).put("actualDiskBytesFreed",JSONObject.NULL).put("crossFileTransaction",false);
        }
        public JSONObject observe()throws Exception{return new JSONObject().put("snapshotDigest",digest).put("targetAbsent",!Files.exists(root,LinkOption.NOFOLLOW_LINKS)).put("observationOnly",true).put("deletionAttributionProven",false).put("replayAllowed",false);}
    }
    /** Inventory-only walk uses constant retained memory, even for a large
     * protected rootfs. Incomplete/failed counts are never cleanup authority. */
    public static JSONObject measure(File supplied,RunControl original)throws Exception {
        Path root=supplied.getAbsoluteFile().toPath();if(Files.isSymbolicLink(root)||!root.getParent().toFile().getCanonicalFile().toPath().equals(root.getParent()))throw new SecurityException("存储入口含链接");long[] bytes={0};int[] entries={0},files={0},links={0};if(Files.exists(root,LinkOption.NOFOLLOW_LINKS))Files.walkFileTree(root,EnumSet.noneOf(FileVisitOption.class),128,new SimpleFileVisitor<Path>(){private FileVisitResult add(BasicFileAttributes attributes)throws java.io.IOException{try{original.checkpoint();if(++entries[0]>MAX_ENTRIES)throw new java.io.IOException("存储条目超过一次完整计量范围，结果未知而非0");if(attributes.isRegularFile()){files[0]++;bytes[0]=Math.addExact(bytes[0],attributes.size());}else if(attributes.isSymbolicLink())links[0]++;else if(!attributes.isDirectory())throw new java.io.IOException("存储范围含特殊文件");return FileVisitResult.CONTINUE;}catch(Exception failed){throw new java.io.IOException(failed.getMessage(),failed);}}@Override public FileVisitResult preVisitDirectory(Path path,BasicFileAttributes attributes)throws java.io.IOException{return add(attributes);}@Override public FileVisitResult visitFile(Path path,BasicFileAttributes attributes)throws java.io.IOException{if(attributes.isDirectory())throw new java.io.IOException("存储目录深度超过128层，未计作完整范围");return add(attributes);}@Override public FileVisitResult visitFileFailed(Path path,java.io.IOException failure)throws java.io.IOException{throw failure;}});
        return new JSONObject().put("logicalBytes",bytes[0]).put("entries",entries[0]).put("files",files[0]).put("links",links[0]).put("complete",true).put("allocatedBlocksMeasured",false).put("followsLinks",false);
    }
    public static Snapshot scan(File supplied,RunControl original)throws Exception {
        Path root=supplied.getAbsoluteFile().toPath();if(Files.isSymbolicLink(root)||!root.getParent().toFile().getCanonicalFile().toPath().equals(root.getParent()))throw new SecurityException("存储入口含目录链接，未跟随其他路径");List<Node> nodes=new ArrayList<>();long[] bytes={0};int[] files={0},links={0},manifest={0};
        if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return new Snapshot(root,nodes,ProjectSources.digest(new JSONObject().put("nodes",new JSONArray())),0,0,0);
        Files.walkFileTree(root,EnumSet.noneOf(FileVisitOption.class),128,new SimpleFileVisitor<Path>(){
            private void add(Path path,BasicFileAttributes attributes)throws java.io.IOException {try{original.checkpoint();if(nodes.size()>=MAX_ENTRIES)throw new IllegalArgumentException("此范围超过20万条目，未把截断清单当作可清理范围");Node node=new Node(root,path,attributes);manifest[0]+=node.path.length()+160;if(manifest[0]>MAX_MANIFEST_CHARS)throw new IllegalArgumentException("此范围的存储清单超过16Mi字符，未给截断清单删除权限");nodes.add(node);bytes[0]=Math.addExact(bytes[0],node.bytes);if(node.type.equals("file"))files[0]++;if(node.type.equals("link"))links[0]++;}catch(Exception failed){throw new java.io.IOException(failed.getMessage(),failed);}}
            @Override public FileVisitResult preVisitDirectory(Path path,BasicFileAttributes attributes)throws java.io.IOException{add(path,attributes);return FileVisitResult.CONTINUE;}
            @Override public FileVisitResult visitFile(Path path,BasicFileAttributes attributes)throws java.io.IOException{if(attributes.isDirectory())throw new java.io.IOException("存储目录深度超过128层，未采用截断清单");add(path,attributes);return FileVisitResult.CONTINUE;}
            @Override public FileVisitResult visitFileFailed(Path path,java.io.IOException failure)throws java.io.IOException{throw failure;}
        });
        nodes.sort(Comparator.comparing(node->node.path));var checksum=java.security.MessageDigest.getInstance("SHA-256");for(Node node:nodes){checksum.update(ProjectSources.canonical(node.pin()).getBytes(java.nio.charset.StandardCharsets.UTF_8));checksum.update((byte)10);}StringBuilder pin=new StringBuilder();for(byte value:checksum.digest())pin.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return new Snapshot(root,nodes,pin.toString(),bytes[0],files[0],links[0]);
    }
    private static void requireAncestors(Path root,Path target)throws Exception {
        if(!target.startsWith(root))throw new SecurityException("不是原存储范围");Path current=root;for(Path part:root.relativize(target)){if(Files.isSymbolicLink(current)||!Files.isDirectory(current,LinkOption.NOFOLLOW_LINKS))throw new SecurityException("删除前原父目录已变化，未跟随目录链接");current=current.resolve(part);}
        if(!root.getParent().toFile().getCanonicalFile().toPath().equals(root.getParent()))throw new SecurityException("原存储入口的父目录已变化");
    }
}
