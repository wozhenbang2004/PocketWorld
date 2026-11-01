package dev.pocketengine.features.local.project;

import dev.pocketengine.features.local.NativeCommandExecutor;
import dev.pocketengine.features.workspace.VersionedWorkspace;
import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.CRC32;
import org.apache.commons.compress.archivers.zip.AsiExtraField;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owner-selected source bytes become an unpublished selection for the existing package importer.
 * No URI, executable, model grant, artifact catalog or alternate project repository is introduced.
 * Ordinary ZIP entry names are strictly UTF-8; renamed Unicode-extra aliases are conservatively refused. */
public final class ProjectSourceImport {
    public static final int MAX_ENTRIES=10_000,MAX_DEPTH=40;
    private ProjectSourceImport(){}
    @FunctionalInterface public interface Current {void check()throws Exception;}
    /** Opaque provider handles never enter a JSON receipt. Only the native SAF adapter creates them. */
    public static final class Entry {
        public final String handle,name;public final boolean directory,virtual,partial;public final Long advertisedBytes;
        public Entry(String handle,String name,boolean directory,Long advertisedBytes,boolean virtual,boolean partial){this.handle=handle;this.name=name;this.directory=directory;this.advertisedBytes=advertisedBytes;this.virtual=virtual;this.partial=partial;}
    }
    public interface Tree {
        String rootHandle();
        List<Entry> children(String directoryHandle)throws Exception;
        InputStream open(Entry file)throws Exception;
    }
    /** Complete, immutable copied bytes; still not a published project or owner execution approval. */
    public static final class Selection {
        public final ProjectPackageArchive.Frozen frozen;private final String evidence;
        private Selection(ProjectPackageArchive.Frozen frozen,JSONObject evidence)throws Exception{this.frozen=frozen;this.evidence=evidence.toString();}
        public JSONObject evidence()throws Exception{return new JSONObject(evidence);}
    }
    private static final class Scan {
        final TreeMap<String,Entry> files=new TreeMap<>(),directories=new TreeMap<>();
        final TreeMap<String,String> omitted=new TreeMap<>();final Set<String> handles=new HashSet<>();int visited;
        JSONArray omissions()throws Exception{JSONArray rows=new JSONArray();for(var item:omitted.entrySet())rows.put(new JSONObject().put("path",item.getKey()).put("reason",item.getValue()));return rows;}
        String identity()throws Exception{JSONArray entries=new JSONArray();for(var item:directories.entrySet())entries.put(identity(item.getKey(),item.getValue()));for(var item:files.entrySet())entries.put(identity(item.getKey(),item.getValue()));return ProjectSources.digest(new JSONObject().put("entries",entries).put("omitted",omissions()));}
        private JSONObject identity(String path,Entry entry)throws Exception{return new JSONObject().put("path",path).put("handle",entry.handle).put("directory",entry.directory).put("bytes",entry.advertisedBytes==null?JSONObject.NULL:entry.advertisedBytes);}
    }
    private static String path(String value)throws Exception {
        String valid=VersionedWorkspace.path(value);StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(valid));
        if(valid.split("/",-1).length>MAX_DEPTH)throw new IllegalArgumentException("源码目录超过40层，未导入部分工程");return valid;
    }
    private static String component(String name)throws Exception{path(name);if(name.indexOf('/')>=0)throw new SecurityException("所选目录包含不能往返的文件名");return name;}
    private static String omission(String value,boolean directory,boolean generated,Map<String,String> omitted)throws Exception {
        String[] parts=value.split("/");String prefix="";
        for(int i=0;i<parts.length;i++){prefix=prefix.isEmpty()?parts[i]:prefix+"/"+parts[i];String reason=ProjectPackageArchive.excluded(prefix,i<parts.length-1||directory,generated);if(reason!=null){if(omitted.size()>=ProjectPackageArchive.MAX_FILES&&!omitted.containsKey(prefix))throw new IllegalArgumentException("源码排除清单超过2000项，未隐瞒遗漏继续导入");omitted.put(prefix,reason);return prefix;}}
        return null;
    }
    private static void freshDirectory(File directory)throws Exception {
        if(!directory.isDirectory()||Files.isSymbolicLink(directory.toPath())||!directory.getAbsoluteFile().equals(directory.getCanonicalFile()))throw new SecurityException("源码导入暂存目录不可用");
        try(var contents=Files.list(directory.toPath())){if(contents.findAny().isPresent())throw new SecurityException("源码导入只能写入这次新空暂存目录");}
    }
    private static void count(Scan scan)throws Exception{if(++scan.visited>MAX_ENTRIES)throw new IllegalArgumentException("来源超过10000个目录条目，未导入部分工程");}
    private static Scan enumerate(Tree source,boolean generated,Current current)throws Exception {
        Scan scan=new Scan();String root=source.rootHandle();if(root==null||root.isEmpty())throw new IllegalArgumentException("没有可读取的主人所选目录");scan.handles.add(root);enumerate(source,root,"",generated,scan,current);return scan;
    }
    private static void enumerate(Tree source,String handle,String parent,boolean generated,Scan scan,Current current)throws Exception {
        current.check();List<Entry> children=source.children(handle);if(children==null||children.size()>MAX_ENTRIES)throw new IllegalArgumentException("目录读取未完整返回或条目超限");Set<String> siblingNames=new HashSet<>();
        for(Entry entry:children){current.check();count(scan);if(entry==null||entry.handle==null||entry.handle.isEmpty()||!scan.handles.add(entry.handle))throw new SecurityException("所选目录包含循环、重复目标或无法确定的文件别名，未导入部分工程");String name=component(entry.name),relative=path(parent.isEmpty()?name:parent+"/"+name);if(!siblingNames.add(name))throw new SecurityException("所选目录包含同名冲突，未覆盖文件");
            // Refuse unknown provider objects even if their names would otherwise be excluded.
            if(entry.virtual||entry.partial)throw new IllegalArgumentException("来源包含虚拟或尚未下载完整的文件，请先在原文件应用准备完成："+relative);
            if(omission(relative,entry.directory,generated,scan.omitted)!=null)continue;
            if(entry.directory){if(scan.directories.size()>=ProjectPackageArchive.MAX_FILES)throw new IllegalArgumentException("源码目录超过2000项");scan.directories.put(relative,entry);enumerate(source,entry.handle,relative,generated,scan,current);}
            else {if(entry.advertisedBytes!=null&&(entry.advertisedBytes<0||entry.advertisedBytes>ProjectPackageArchive.MAX_FILE_BYTES)||scan.files.size()>=ProjectPackageArchive.MAX_FILES)throw new IllegalArgumentException("源码文件超过单个2MiB或2000文件范围");scan.files.put(relative,entry);}
        }
    }
    public static Selection tree(Tree source,File emptyDestination,String selectionId,String label,boolean generated,Current current)throws Exception {
        freshDirectory(emptyDestination);Scan before=enumerate(source,generated,current);if(before.files.isEmpty())throw new IllegalArgumentException("所选目录没有纳入的源码文件，请核对排除范围");
        for(String directory:before.directories.keySet()){current.check();File target=NativeCommandExecutor.within(emptyDestination,directory);if(!target.mkdirs()&&!target.isDirectory())throw new IllegalStateException("无法保留来源中的空目录");}
        TreeMap<String,String> hashes=new TreeMap<>();long total=0;
        for(var item:before.files.entrySet()){current.check();File target=target(emptyDestination,item.getKey());Copy copied;try(InputStream input=source.open(item.getValue())){if(input==null)throw new IllegalStateException("原目录有文件无法打开，未导入部分工程");copied=copy(input,target,ProjectPackageArchive.MAX_FILE_BYTES,current);}
            if(item.getValue().advertisedBytes!=null&&item.getValue().advertisedBytes!=copied.bytes)throw new SecurityException("原文件读取量与来源声明不同，请重新选择当前版本");total+=copied.bytes;if(total>ProjectPackageArchive.MAX_TOTAL_BYTES)throw new IllegalArgumentException("源码合计超过16MiB，未导入部分工程");hashes.put(item.getKey(),copied.sha);
        }
        // A directory provider is not a POSIX snapshot. Re-enumerate and reread the included bytes;
        // still declare atomicSourceFence=false rather than claiming a provider-wide transaction.
        Scan after=enumerate(source,generated,current);if(!before.identity().equals(after.identity()))throw new SecurityException("来源目录在读取时变化，未发布混合版本工程");
        for(var item:after.files.entrySet()){current.check();Copy checked;try(InputStream input=source.open(item.getValue())){if(input==null)throw new IllegalStateException("原文件复核读取失败，未发布工程");checked=copy(input,null,ProjectPackageArchive.MAX_FILE_BYTES,current);}if(!hashes.get(item.getKey()).equals(checked.sha))throw new SecurityException("原文件在读取时SHA变化，未发布混合版本工程");}
        return finish(emptyDestination,selectionId,label,"saf_tree",generated,before.omissions(),new JSONObject().put("sourceFilesReread",true).put("providerAtomicSnapshot",false).put("executableBitsAvailable",false),current);
    }
    public static Selection zip(File archive,File emptyDestination,String selectionId,String label,boolean stripSingleRoot,boolean generated,Current current)throws Exception {
        freshDirectory(emptyDestination);current.check();if(Files.isSymbolicLink(archive.toPath())||!Files.isRegularFile(archive.toPath(),LinkOption.NOFOLLOW_LINKS)||archive.length()<22||archive.length()>ProjectPackageArchive.MAX_ARCHIVE_BYTES)throw new IllegalArgumentException("普通ZIP不是有界完整普通文件");ProjectPackageArchive.preflightSourceZip(archive);String archiveSha=fileSha(archive,current);long archiveBytes=archive.length();
        Scan scan=new Scan();String stripped="";
        try(var zip=new org.apache.commons.compress.archivers.zip.ZipFile(archive,StandardCharsets.UTF_8.name())){
            TreeMap<String,ZipArchiveEntry> all=new TreeMap<>();var entries=zip.getEntries();
            while(entries.hasMoreElements()){current.check();count(scan);ZipArchiveEntry entry=entries.nextElement();byte[] originalName=entry.getRawName();if(originalName==null)throw new SecurityException("普通ZIP缺少可核对的原条目名字节，未猜测名称");
                // This importer is explicitly UTF-8. FAT normalization or a different Unicode
                // extra name must not silently change the owner's original path.
                String raw;try{raw=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(originalName)).toString();}catch(CharacterCodingException badName){throw new SecurityException("普通ZIP条目名须为完整UTF-8；不猜测其它编码或改名",badName);}
                if(!raw.equals(entry.getName()))throw new SecurityException("普通ZIP原条目名与库解码名不同，未静默改名或采用不同Unicode附加名");
                String name=entry.isDirectory()&&raw.endsWith("/")?raw.substring(0,raw.length()-1):raw;path(name);int mode=entry.getUnixMode(),type=mode&0170000;boolean extraLink=false;for(var extra:entry.getExtraFields())if(extra instanceof AsiExtraField&&((AsiExtraField)extra).isLink())extraLink=true;
                if(entry.isUnixSymlink()||extraLink||entry.getGeneralPurposeBit().usesEncryption()||!zip.canReadEntryData(entry)||(mode&07000)!=0||type!=0&&type!=(entry.isDirectory()?0040000:0100000))throw new SecurityException("普通ZIP含链接、特殊类型、特权位、加密或不支持条目，未导入："+name);
                if(all.put(name,entry)!=null)throw new SecurityException("普通ZIP有重复或文件/目录同名路径，未覆盖："+name);
            }
            for(String name:all.keySet())for(int slash=name.indexOf('/');slash>=0;slash=name.indexOf('/',slash+1)){ZipArchiveEntry ancestor=all.get(name.substring(0,slash));if(ancestor!=null&&!ancestor.isDirectory())throw new SecurityException("ZIP的文件与目录祖先冲突");}
            if(stripSingleRoot){String candidate=null;boolean canStrip=true;for(var item:all.entrySet()){String name=item.getKey();int slash=name.indexOf('/');String first=slash<0?name:name.substring(0,slash);if(candidate==null)candidate=first;if(!candidate.equals(first)||slash<0&&!item.getValue().isDirectory()){canStrip=false;break;}}if(canStrip&&candidate!=null&&ProjectPackageArchive.excluded(candidate,true,generated)==null)stripped=candidate;}
            long total=0;
            for(var item:all.entrySet()){current.check();String relative=item.getKey();ZipArchiveEntry entry=item.getValue();if(!stripped.isEmpty()){if(relative.equals(stripped))continue;relative=relative.substring(stripped.length()+1);}path(relative);if(omission(relative,entry.isDirectory(),generated,scan.omitted)!=null)continue;parents(relative,scan);
                if(entry.isDirectory()){if(entry.getSize()!=0||!scan.directories.containsKey(relative)&&scan.directories.size()>=ProjectPackageArchive.MAX_FILES)throw new SecurityException("ZIP目录带内容或目录超限");File directory=NativeCommandExecutor.within(emptyDestination,relative);if(!directory.mkdirs()&&!directory.isDirectory())throw new IllegalStateException("无法保留ZIP空目录");scan.directories.put(relative,new Entry(relative,relative,true,0L,false,false));continue;}
                if(scan.files.size()>=ProjectPackageArchive.MAX_FILES||entry.getSize()<0||entry.getSize()>ProjectPackageArchive.MAX_FILE_BYTES)throw new IllegalArgumentException("ZIP源码单文件/条数超限或大小未知");Copy copied;try(InputStream input=zip.getInputStream(entry)){copied=copy(input,target(emptyDestination,relative),ProjectPackageArchive.MAX_FILE_BYTES,current);}if(copied.bytes!=entry.getSize()||entry.getCrc()<0||copied.crc!=entry.getCrc())throw new SecurityException("ZIP文件实际大小或CRC不同，未导入部分工程");total+=copied.bytes;if(total>ProjectPackageArchive.MAX_TOTAL_BYTES)throw new IllegalArgumentException("ZIP纳入源码总量超过16MiB");if((entry.getUnixMode()&0111)!=0&&!NativeCommandExecutor.within(emptyDestination,relative).setExecutable(true,true))throw new IllegalStateException("无法保留ZIP脚本的主人执行位");scan.files.put(relative,new Entry(relative,relative,false,copied.bytes,false,false));
            }
        }
        if(!archiveSha.equals(fileSha(archive,current)))throw new SecurityException("原ZIP暂存字节在读取时变化");
        if(scan.files.isEmpty())throw new IllegalArgumentException("普通ZIP没有纳入的源码文件，请核对原归档和排除范围");
        return finish(emptyDestination,selectionId,label,"external_zip",generated,scan.omissions(),new JSONObject().put("originalArchiveSha256",archiveSha).put("originalArchiveBytes",archiveBytes).put("singleRootStripped",stripped).put("executableBitsAvailable",true).put("zipLinksRestored",false),current);
    }
    private static void parents(String relative,Scan scan)throws Exception {for(int slash=relative.indexOf('/');slash>=0;slash=relative.indexOf('/',slash+1)){String path=relative.substring(0,slash);if(!scan.directories.containsKey(path)){if(scan.directories.size()>=ProjectPackageArchive.MAX_FILES)throw new IllegalArgumentException("ZIP隐含目录超过2000项");scan.directories.put(path,new Entry(path,path,true,0L,false,false));}}}
    private static File target(File root,String path)throws Exception {File target=NativeCommandExecutor.within(root,path);File parent=target.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IllegalStateException("无法建立源码目录");if(Files.exists(target.toPath(),LinkOption.NOFOLLOW_LINKS))throw new SecurityException("源码目标不是新空文件，未覆盖");return target;}
    private static final class Copy {final String sha;final long bytes,crc;Copy(String sha,long bytes,long crc){this.sha=sha;this.bytes=bytes;this.crc=crc;}}
    private static Copy copy(InputStream input,File destination,int limit,Current current)throws Exception {
        MessageDigest sha=MessageDigest.getInstance("SHA-256");CRC32 crc=new CRC32();long bytes=0;int idleReads=0;byte[] buffer=new byte[8192];
        try(java.nio.channels.FileChannel output=destination==null?null:java.nio.channels.FileChannel.open(destination.toPath(),java.nio.file.StandardOpenOption.CREATE_NEW,java.nio.file.StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){for(int count;(count=input.read(buffer))>=0;){current.check();if(count==0){if(++idleReads>8)throw new java.io.IOException("来源流连续返回零进度，未发布部分文件");continue;}idleReads=0;bytes+=count;if(bytes>limit)throw new IllegalArgumentException("源码读取超过原生字节上限，未导入部分文件");sha.update(buffer,0,count);crc.update(buffer,0,count);if(output!=null){java.nio.ByteBuffer data=java.nio.ByteBuffer.wrap(buffer,0,count);while(data.hasRemaining())output.write(data);}}current.check();if(output!=null)output.force(true);}
        return new Copy(hex(sha.digest()),bytes,crc.getValue());
    }
    public static long copyArchive(InputStream input,File target,Current current)throws Exception{return copy(input,target,ProjectPackageArchive.MAX_ARCHIVE_BYTES,current).bytes;}
    public static String fileSha(File file,Current current)throws Exception{if(Files.isSymbolicLink(file.toPath())||!file.isFile())throw new SecurityException("源码暂存不是普通文件");try(InputStream input=Files.newInputStream(file.toPath(),LinkOption.NOFOLLOW_LINKS)){return copy(input,null,ProjectPackageArchive.MAX_ARCHIVE_BYTES,current).sha;}}
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte value:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return out.toString();}
    private static Selection finish(File root,String selection,String label,String kind,boolean generated,JSONArray omitted,JSONObject originalEvidence,Current current)throws Exception {
        if(selection==null||!selection.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("缺少本次原生来源选择身份");String name=label==null?"已有源码":label.replaceAll("[\\p{Cntrl}]"," ").trim();if(name.isEmpty())name="已有源码";if(name.length()>80)name=name.substring(0,80);if(!name.isEmpty()&&Character.isHighSurrogate(name.charAt(name.length()-1)))name=name.substring(0,name.length()-1);
        JSONArray manifest=new JSONArray();try(var paths=Files.walk(root.toPath())){for(var file:java.util.Collections.unmodifiableList(paths.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).sorted().collect(java.util.stream.Collectors.toList()))){current.check();String relative=root.toPath().relativize(file).toString().replace(File.separatorChar,'/');if(ProjectSources.sourcePath(relative))manifest.put(new JSONObject().put("path",relative).put("sha256",fileSha(file.toFile(),current)).put("bytes",Files.size(file)));}}
        String digest=ProjectSources.manifestDigest(manifest);JSONObject original=new JSONObject().put("projectId",selection).put("checkoutId",selection).put("sourceDigest",digest).put("name",name);ProjectPackageArchive.Frozen frozen=ProjectPackageArchive.captureSourceImport(root,original,generated,omitted);current.check();JSONObject evidence=new JSONObject().put("format","pocketworld.project-source-selection.v1").put("sourceKind",kind).put("sourceLabel",name).put("selectionId",selection).put("manifestIdentity",frozen.identity).put("sourceDigest",digest).put("files",frozen.manifest().getJSONArray("files").length()).put("expandedBytes",frozen.manifest().getLong("bytes")).put("omittedCount",omitted.length()).put("includeGeneratedRoots",generated).put("completeSelection",true).put("providerAtomicSnapshot",false).put("ordinaryZipIsPocketWorldPackage",false).put("stagingIdsAreNotOriginalProjectIdentity",true).put("executionAuthorityRestored",false).put("sourcePermissionPersisted",false).put("original",originalEvidence);
        return new Selection(frozen,evidence);
    }
}
