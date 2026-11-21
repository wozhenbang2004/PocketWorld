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
    public static final String BUILTIN_VITE_STARTER="builtin_vite_starter";
    private static final String BUILTIN_VITE_FORMAT="pocketworld.builtin-vite-starter.v1", BUILTIN_VITE_LABEL="Vite 网页起步项目";
    private static final Set<String> ZIP_ROOT_AMBIGUOUS=Set.of("pnpm-workspace.yaml","lerna.json","nx.json","rush.json","turbo.json",
        "yarn.lock","pnpm-lock.yaml","bun.lock","bun.lockb","npm-shrinkwrap.json","pom.xml","build.gradle","build.gradle.kts","settings.gradle","settings.gradle.kts",
        "pyproject.toml","requirements.txt","go.mod","cargo.toml");
    public enum ZipRoot { PRESERVE, STRIP_SINGLE, RECOGNIZE_WEB }
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
        final TreeMap<String,String> omitted=new TreeMap<>();final Set<String> handles=new HashSet<>();int visited;String skippedHandle;
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
    /** Literal owner-selected directory below the selected tree, never a pattern, root, file or published deletion. */
    public static String checkedSkipDirectory(String value)throws Exception {
        if(value==null||value.isEmpty())return "";
        if(value.contains("*")||value.contains("?"))throw new IllegalArgumentException("跳过目录请填写原件的确切相对路径，不使用通配符");
        return path(value);
    }
    private static Scan enumerate(Tree source,boolean generated,String skip,Current current)throws Exception {
        Scan scan=new Scan();String root=source.rootHandle();if(root==null||root.isEmpty())throw new IllegalArgumentException("没有可读取的主人所选目录");scan.handles.add(root);enumerate(source,root,"",generated,skip,scan,current);
        if(!skip.isEmpty()&&scan.skippedHandle==null)throw new IllegalArgumentException("未在所选文件夹中找到可单独跳过的目录「"+skip+"」（或它已被默认排除），没有建立项目");return scan;
    }
    private static void enumerate(Tree source,String handle,String parent,boolean generated,String skip,Scan scan,Current current)throws Exception {
        current.check();List<Entry> children=source.children(handle);if(children==null||children.size()>MAX_ENTRIES)throw new IllegalArgumentException("目录读取未完整返回或条目超限");Set<String> siblingNames=new HashSet<>();
        for(Entry entry:children){current.check();count(scan);if(entry==null||entry.handle==null||entry.handle.isEmpty()||!scan.handles.add(entry.handle))throw new SecurityException("所选目录包含循环、重复目标或无法确定的文件别名，未导入部分工程");String name=component(entry.name),relative=path(parent.isEmpty()?name:parent+"/"+name);if(!siblingNames.add(name))throw new SecurityException("所选目录包含同名冲突，未覆盖文件");
            // Refuse unknown provider objects even if their names would otherwise be excluded.
            if(entry.virtual||entry.partial)throw new IllegalArgumentException("来源包含虚拟或尚未下载完整的文件，请先在原文件应用准备完成："+relative);
            if(omission(relative,entry.directory,generated,scan.omitted)!=null)continue;
            if(relative.equals(skip)){
                if(!entry.directory)throw new IllegalArgumentException("「"+relative+"」是文件，不是这次要跳过的目录；未建立项目");
                if(scan.omitted.size()>=ProjectPackageArchive.MAX_FILES)throw new IllegalArgumentException("源码排除清单超过2000项");
                scan.skippedHandle=entry.handle;scan.omitted.put(relative,"owner_excluded_source_directory");continue;
            }
            if(entry.directory){if(scan.directories.size()>=ProjectPackageArchive.MAX_FILES)throw new IllegalArgumentException("源码目录超过2000项");scan.directories.put(relative,entry);enumerate(source,entry.handle,relative,generated,skip,scan,current);}
            else {if(entry.advertisedBytes!=null&&(entry.advertisedBytes<0||entry.advertisedBytes>ProjectPackageArchive.MAX_FILE_BYTES))throw treeFileTooLarge(relative);if(scan.files.size()>=ProjectPackageArchive.MAX_FILES)throw new IllegalArgumentException("源码文件超过2000个，当前路径："+relative);scan.files.put(relative,entry);}
        }
    }
    public static Selection tree(Tree source,File emptyDestination,String selectionId,String label,boolean generated,Current current)throws Exception {
        return tree(source,emptyDestination,selectionId,label,generated,"",current);
    }
    /** A relative-path suggestion from this real error, never a default selection or an assertion about all files there. */
    public static final class FolderFileTooLarge extends IllegalArgumentException {
        public final String suggestedDirectory;
        private FolderFileTooLarge(String path){super("单个源码文件超过2MiB或大小无效："+path+"。可在导入范围中明确跳过它所在的目录后重新选择。");
            String[] parts=path.split("/");String suggestion=null;
            for(int i=1;i+2<parts.length;i++)if(parts[i].equals("build")&&Set.of("outputs","intermediates","generated","tmp").contains(parts[i+1])){
                suggestion=String.join("/",java.util.Arrays.copyOf(parts,i+1));break;
            }
            suggestedDirectory=suggestion;
        }
    }
    private static FolderFileTooLarge treeFileTooLarge(String path){return new FolderFileTooLarge(path);}
    public static Selection tree(Tree source,File emptyDestination,String selectionId,String label,boolean generated,String excludedDirectory,Current current)throws Exception {
        String skip=checkedSkipDirectory(excludedDirectory);freshDirectory(emptyDestination);Scan before=enumerate(source,generated,skip,current);if(before.files.isEmpty())throw new IllegalArgumentException("所选目录没有纳入的源码文件，请核对排除范围");
        for(String directory:before.directories.keySet()){current.check();File target=NativeCommandExecutor.within(emptyDestination,directory);if(!target.mkdirs()&&!target.isDirectory())throw new IllegalStateException("无法保留来源中的空目录");}
        TreeMap<String,String> hashes=new TreeMap<>();long total=0;
        for(var item:before.files.entrySet()){current.check();File target=target(emptyDestination,item.getKey());Copy copied;try(InputStream input=source.open(item.getValue())){if(input==null)throw new IllegalStateException("原目录有文件无法打开，未导入部分工程");copied=copy(input,target,ProjectPackageArchive.MAX_FILE_BYTES,current);}catch(ByteLimit limit){throw treeFileTooLarge(item.getKey());}
            if(item.getValue().advertisedBytes!=null&&item.getValue().advertisedBytes!=copied.bytes)throw new SecurityException("原文件读取量与来源声明不同，请重新选择当前版本");total+=copied.bytes;if(total>ProjectPackageArchive.MAX_TOTAL_BYTES)throw new IllegalArgumentException("源码合计超过16MiB，当前文件："+item.getKey()+"。没有建立部分项目");hashes.put(item.getKey(),copied.sha);
        }
        // A directory provider is not a POSIX snapshot. Re-enumerate and reread the included bytes;
        // still declare atomicSourceFence=false rather than claiming a provider-wide transaction.
        Scan after=enumerate(source,generated,skip,current);if(!java.util.Objects.equals(before.skippedHandle,after.skippedHandle)||!before.identity().equals(after.identity()))throw new SecurityException("来源目录在读取时变化，未发布混合版本工程");
        for(var item:after.files.entrySet()){current.check();Copy checked;try(InputStream input=source.open(item.getValue())){if(input==null)throw new IllegalStateException("原文件复核读取失败，未发布工程");checked=copy(input,null,ProjectPackageArchive.MAX_FILE_BYTES,current);}catch(ByteLimit limit){throw treeFileTooLarge(item.getKey());}if(!hashes.get(item.getKey()).equals(checked.sha))throw new SecurityException("原文件在读取时SHA变化，未发布混合版本工程");}
        return finish(emptyDestination,selectionId,label,"saf_tree",generated,before.omissions(),new JSONObject().put("ownerExcludedDirectory",skip.isEmpty()?null:skip).put("sourceFilesReread",true).put("providerAtomicSnapshot",false).put("executableBitsAvailable",false),current);
    }
    public static Selection zip(File archive,File emptyDestination,String selectionId,String label,boolean stripSingleRoot,boolean generated,Current current)throws Exception {
        return zip(archive,emptyDestination,selectionId,label,stripSingleRoot?ZipRoot.STRIP_SINGLE:ZipRoot.PRESERVE,generated,current);
    }
    public static Selection zip(File archive,File emptyDestination,String selectionId,String label,ZipRoot root,boolean generated,Current current)throws Exception {
        java.util.Objects.requireNonNull(root);
        freshDirectory(emptyDestination);current.check();if(Files.isSymbolicLink(archive.toPath())||!Files.isRegularFile(archive.toPath(),LinkOption.NOFOLLOW_LINKS)||archive.length()<22||archive.length()>ProjectPackageArchive.MAX_ARCHIVE_BYTES)throw new IllegalArgumentException("普通ZIP不是有界完整普通文件");long expanded=ProjectPackageArchive.preflightSelectedZip(archive);String archiveSha=fileSha(archive,current);long archiveBytes=archive.length();
        Scan scan=new Scan();String stripped="";JSONObject fixed=null;
        try(var zip=new org.apache.commons.compress.archivers.zip.ZipFile(archive,StandardCharsets.UTF_8.name())){
            TreeMap<String,ZipArchiveEntry> all=new TreeMap<>();var entries=zip.getEntries();
            while(entries.hasMoreElements()){current.check();count(scan);ZipArchiveEntry entry=entries.nextElement();byte[] originalName=entry.getRawName();if(originalName==null)throw new SecurityException("普通ZIP缺少可核对的原条目名字节，未猜测名称");
                // This importer is explicitly UTF-8. FAT normalization or a different Unicode
                // extra name must not silently change the owner's original path.
                String raw;try{raw=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(originalName)).toString();}catch(CharacterCodingException badName){throw new SecurityException("普通ZIP条目名须为完整UTF-8；不猜测其它编码或改名",badName);}
                if(!raw.equals(entry.getName()))throw new SecurityException("普通ZIP原条目名与库解码名不同，未静默改名或采用不同Unicode附加名");
                String name=entry.isDirectory()&&raw.endsWith("/")?raw.substring(0,raw.length()-1):raw;path(name.startsWith("project/")?name.substring(8):name);int mode=entry.getUnixMode(),type=mode&0170000;boolean extraLink=false;for(var extra:entry.getExtraFields())if(extra instanceof AsiExtraField&&((AsiExtraField)extra).isLink())extraLink=true;
                if(entry.isUnixSymlink()||extraLink||entry.getGeneralPurposeBit().usesEncryption()||!zip.canReadEntryData(entry)||(mode&07000)!=0||type!=0&&type!=(entry.isDirectory()?0040000:0100000))throw new SecurityException("普通ZIP含链接、特殊类型、特权位、加密或不支持条目，未导入："+name);
                if(all.put(name,entry)!=null)throw new SecurityException("普通ZIP有重复或文件/目录同名路径，未覆盖："+name);
            }
            for(String name:all.keySet())for(int slash=name.indexOf('/');slash>=0;slash=name.indexOf('/',slash+1)){ZipArchiveEntry ancestor=all.get(name.substring(0,slash));if(ancestor!=null&&!ancestor.isDirectory())throw new SecurityException("ZIP的文件与目录祖先冲突");}
            if(ProjectSourcePackageLayout.declared(zip,all.get("manifest.json"),current)){
                fixed=copyVerifiedSourcePackage(archive,emptyDestination,generated,scan,current);
            }else{
                // Without a verified PocketWorld envelope the original ordinary-ZIP limits still apply to the full name.
                if(expanded>ProjectPackageArchive.MAX_TOTAL_BYTES)throw new IllegalArgumentException("普通 ZIP 声明的展开总量超过16MiB，未导入");
                for(String name:all.keySet())path(name);
                if(root!=ZipRoot.PRESERVE){
                    String candidate=singleZipRoot(all,generated);
                    if(candidate!=null&&(root==ZipRoot.STRIP_SINGLE||recognizedWebRoot(zip,all,candidate,current)))stripped=candidate;
                }
                long total=0;
                for(var item:all.entrySet()){current.check();String relative=item.getKey();ZipArchiveEntry entry=item.getValue();if(!stripped.isEmpty()){if(relative.equals(stripped))continue;relative=relative.substring(stripped.length()+1);}path(relative);if(omission(relative,entry.isDirectory(),generated,scan.omitted)!=null)continue;parents(relative,scan);
                    if(entry.isDirectory()){if(entry.getSize()!=0||!scan.directories.containsKey(relative)&&scan.directories.size()>=ProjectPackageArchive.MAX_FILES)throw new SecurityException("ZIP目录带内容或目录超限");File directory=NativeCommandExecutor.within(emptyDestination,relative);if(!directory.mkdirs()&&!directory.isDirectory())throw new IllegalStateException("无法保留ZIP空目录");scan.directories.put(relative,new Entry(relative,relative,true,0L,false,false));continue;}
                    if(scan.files.size()>=ProjectPackageArchive.MAX_FILES||entry.getSize()<0||entry.getSize()>ProjectPackageArchive.MAX_FILE_BYTES)throw new IllegalArgumentException("ZIP源码单文件/条数超限或大小未知");Copy copied;try(InputStream input=zip.getInputStream(entry)){copied=copy(input,target(emptyDestination,relative),ProjectPackageArchive.MAX_FILE_BYTES,current);}if(copied.bytes!=entry.getSize()||entry.getCrc()<0||copied.crc!=entry.getCrc())throw new SecurityException("ZIP文件实际大小或CRC不同，未导入部分工程");total+=copied.bytes;if(total>ProjectPackageArchive.MAX_TOTAL_BYTES)throw new IllegalArgumentException("ZIP纳入源码总量超过16MiB");if((entry.getUnixMode()&0111)!=0&&!NativeCommandExecutor.within(emptyDestination,relative).setExecutable(true,true))throw new IllegalStateException("无法保留ZIP脚本的主人执行位");scan.files.put(relative,new Entry(relative,relative,false,copied.bytes,false,false));
                }
            }
        }
        if(!archiveSha.equals(fileSha(archive,current)))throw new SecurityException("原ZIP暂存字节在读取时变化");
        if(scan.files.isEmpty())throw new IllegalArgumentException("普通ZIP没有纳入的源码文件，请核对原归档和排除范围");
        // Preserve the old durable shape. A recognition with no match has the exact retained layout.
        boolean stripRequested=root==ZipRoot.STRIP_SINGLE||root==ZipRoot.RECOGNIZE_WEB&&!stripped.isEmpty();
        JSONObject original=(fixed==null?new JSONObject():fixed).put("originalArchiveSha256",archiveSha).put("originalArchiveBytes",archiveBytes)
            .put("singleRootStripped",stripped).put("stripSingleRootRequested",stripRequested).put("executableBitsAvailable",fixed==null).put("zipLinksRestored",false);
        return finish(emptyDestination,selectionId,label,"external_zip",generated,scan.omissions(),original,current);
    }
    private static String singleZipRoot(Map<String,ZipArchiveEntry> entries,boolean generated){
        String root=null;
        for(var item:entries.entrySet()){
            String name=item.getKey();int slash=name.indexOf('/');String top=slash<0?name:name.substring(0,slash);
            if(root==null)root=top;
            if(!root.equals(top)||slash<0&&!item.getValue().isDirectory())return null;
        }
        return root!=null&&ProjectPackageArchive.excluded(root,true,generated)==null?root:null;
    }
    /** Shape of one npm web project, not a supported build/lock or installed-tool assertion. */
    private static boolean recognizedWebRoot(org.apache.commons.compress.archivers.zip.ZipFile zip,Map<String,ZipArchiveEntry> entries,
            String root,Current current)throws Exception {
        String prefix=root+"/",manifestName=prefix+"package.json",lockName=prefix+"package-lock.json";
        ZipArchiveEntry wrapper=entries.get(root),manifest=entries.get(manifestName);
        if(wrapper!=null&&wrapper.getSize()!=0||!nonemptyFile(manifest)||manifest.getSize()>65_536||!nonemptyFile(entries.get(lockName))||
            !nonemptyFile(entries.get(prefix+"index.html")))return false;
        // Inspect the complete validated name set before exclusions, not the truncated UI list.
        for(String name:entries.keySet()){
            current.check();String leaf=name.substring(name.lastIndexOf('/')+1).toLowerCase(java.util.Locale.ROOT);
            if(ZIP_ROOT_AMBIGUOUS.contains(leaf)||"package.json".equals(leaf)&&!manifestName.equals(name)||
                "package-lock.json".equals(leaf)&&!lockName.equals(name))return false;
        }
        byte[] bytes=new byte[(int)manifest.getSize()];
        try(InputStream input=zip.getInputStream(manifest)){
            int at=0;
            while(at<bytes.length){current.check();int n=input.read(bytes,at,bytes.length-at);if(n<=0)return false;at+=n;}
            current.check();if(input.read()!=-1)return false;
        }
        CRC32 crc=new CRC32();crc.update(bytes);if(crc.getValue()!=manifest.getCrc())return false;
        JSONObject pkg;
        try{
            String body=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            pkg=dev.pocketengine.features.plugins.mcp.McpJson.parse(body);
        }catch(Exception malformed){return false;}
        current.check();Object manager=pkg.opt("packageManager");
        return !pkg.has("workspaces")&&(manager==null||manager instanceof String value&&value.matches("npm@[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?"));
    }
    private static boolean nonemptyFile(ZipArchiveEntry entry){return entry!=null&&!entry.isDirectory()&&entry.getSize()>0;}
    /** The sealed decoder verifies the whole envelope before we write a single payload. No old project ID,
     * checkout, executable bit, task or host is adopted. Empty directories exist only in the verified manifest. */
    private static JSONObject copyVerifiedSourcePackage(File archive,File root,boolean generated,Scan scan,Current current)throws Exception {
        current.check();ProjectPackageArchive.Frozen fixed=ProjectPackageArchive.read(archive);current.check();JSONObject manifest=fixed.manifest();
        if(!"source_project".equals(manifest.getString("mode")))throw new IllegalArgumentException("这是固定网页成果，不是源项目包。请选取源码包或原工程目录；未创建项目。");
        JSONArray directories=manifest.getJSONArray("directories"),files=manifest.getJSONArray("files");
        for(int i=0;i<directories.length();i++){
            current.check();String rel=path(directories.getString(i));if(omission(rel,true,generated,scan.omitted)!=null)continue;
            parents(rel,scan);if(!scan.directories.containsKey(rel)&&scan.directories.size()>=ProjectPackageArchive.MAX_FILES)throw new IllegalArgumentException("源码目录超过2000项");
            File dir=NativeCommandExecutor.within(root,rel);if(!dir.mkdirs()&&!dir.isDirectory())throw new IllegalStateException("无法保留固定包目录");
            scan.directories.put(rel,new Entry(rel,rel,true,0L,false,false));
        }
        for(int i=0;i<files.length();i++){
            current.check();JSONObject file=files.getJSONObject(i);String rel=path(file.getString("path"));if(omission(rel,false,generated,scan.omitted)!=null)continue;
            parents(rel,scan);Copy kept=copy(new java.io.ByteArrayInputStream(fixed.file(rel)),target(root,rel),ProjectPackageArchive.MAX_FILE_BYTES,current);
            if(kept.bytes!=file.getLong("bytes")||!kept.sha.equals(file.getString("sha256")))throw new SecurityException("固定源码文件与原清单不符，未发布工程");
            scan.files.put(rel,new Entry(rel,rel,false,kept.bytes,false,false));
        }
        return new JSONObject().put("fixedPackageManifestIdentity",fixed.identity).put("fixedPackageOmitted",manifest.getJSONArray("omitted").length());
    }
    private static void parents(String relative,Scan scan)throws Exception {for(int slash=relative.indexOf('/');slash>=0;slash=relative.indexOf('/',slash+1)){String path=relative.substring(0,slash);if(!scan.directories.containsKey(path)){if(scan.directories.size()>=ProjectPackageArchive.MAX_FILES)throw new IllegalArgumentException("ZIP隐含目录超过2000项");scan.directories.put(path,new Entry(path,path,true,0L,false,false));}}}
    private static File target(File root,String path)throws Exception {File target=NativeCommandExecutor.within(root,path);File parent=target.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IllegalStateException("无法建立源码目录");if(Files.exists(target.toPath(),LinkOption.NOFOLLOW_LINKS))throw new SecurityException("源码目标不是新空文件，未覆盖");return target;}
    private static final class Copy {final String sha;final long bytes,crc;Copy(String sha,long bytes,long crc){this.sha=sha;this.bytes=bytes;this.crc=crc;}}
    private static final class ByteLimit extends IllegalArgumentException {ByteLimit(){super("源码读取超过原生字节上限，未导入部分文件");}}
    private static Copy copy(InputStream input,File destination,int limit,Current current)throws Exception {
        MessageDigest sha=MessageDigest.getInstance("SHA-256");CRC32 crc=new CRC32();long bytes=0;int idleReads=0;byte[] buffer=new byte[8192];
        try(java.nio.channels.FileChannel output=destination==null?null:java.nio.channels.FileChannel.open(destination.toPath(),java.nio.file.StandardOpenOption.CREATE_NEW,java.nio.file.StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){for(int count;(count=input.read(buffer))>=0;){current.check();if(count==0){if(++idleReads>8)throw new java.io.IOException("来源流连续返回零进度，未发布部分文件");continue;}idleReads=0;bytes+=count;if(bytes>limit)throw new ByteLimit();sha.update(buffer,0,count);crc.update(buffer,0,count);if(output!=null){java.nio.ByteBuffer data=java.nio.ByteBuffer.wrap(buffer,0,count);while(data.hasRemaining())output.write(data);}}current.check();if(output!=null)output.force(true);}
        return new Copy(hex(sha.digest()),bytes,crc.getValue());
    }
    public static long copyArchive(InputStream input,File target,Current current)throws Exception{return copy(input,target,ProjectPackageArchive.MAX_ARCHIVE_BYTES,current).bytes;}
    public static String fileSha(File file,Current current)throws Exception{if(Files.isSymbolicLink(file.toPath())||!file.isFile())throw new SecurityException("源码暂存不是普通文件");try(InputStream input=Files.newInputStream(file.toPath(),LinkOption.NOFOLLOW_LINKS)){return copy(input,null,ProjectPackageArchive.MAX_ARCHIVE_BYTES,current).sha;}}
    /** These are already verified, explicitly chosen TEXT originals on this phone, not a live host tree or a SAF grant. */
    public static Selection savedHost(ProjectHostSource capture, File root, Current current)throws Exception {
        freshDirectory(root);current.check();
        for(String name:capture.paths()){
            current.check();
            Files.write(target(root,path(name)).toPath(),capture.bytes(name),java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.StandardOpenOption.CREATE_NEW,LinkOption.NOFOLLOW_LINKS);
        }
        JSONObject original=capture.evidence();
        String label="主机成果-"+original.getString("snapshotVersion").substring(0,Math.min(12,original.getString("snapshotVersion").length()));
        Selection selection=finish(root,capture.selectionId,label,"host_saved_snapshot",false,new JSONArray(),original,current);
        ProjectHostSource.requireManifest(selection.evidence(),selection.frozen.manifest());return selection;
    }
    /** Only the original managed Git reader can create Result. The portable
     * source package intentionally excludes .git; native publication separately
     * copies that exact, reviewed metadata into the new unpublished checkout. */
    public static Selection nativeGit(ProjectGitImport.Result result)throws Exception {
        JSONObject original=result.metadata();String id=result.actionId();original.remove("files");ProjectPackageArchive.Frozen frozen=result.source();JSONObject manifest=frozen.manifest();return new Selection(frozen,new JSONObject().put("format","pocketworld.project-source-selection.v1").put("sourceKind","git_https").put("sourceLabel",original.getString("projectName")).put("selectionId",id).put("manifestIdentity",frozen.identity).put("sourceDigest",manifest.getJSONObject("source").getString("sourceDigest")).put("files",manifest.getJSONArray("files").length()).put("expandedBytes",manifest.getLong("bytes")).put("omittedCount",manifest.getJSONArray("omitted").length()).put("includeGeneratedRoots",true).put("completeSelection",true).put("providerAtomicSnapshot",false).put("ordinaryZipIsPocketWorldPackage",false).put("stagingIdsAreNotOriginalProjectIdentity",true).put("executionAuthorityRestored",false).put("sourcePermissionPersisted",false).put("gitHistoryInPortableSourcePackage",false).put("gitHistoryCopiedOnlyByNativePublication",true).put("original",original));
    }
    private record BuiltinVite(Map<String,String> files,String selectionId,String sourceDigest,String lockSha256,long bytes){
        JSONObject marker()throws Exception{
            return new JSONObject().put("format",BUILTIN_VITE_FORMAT).put("viteVersion",ProjectLiveProjectTemplate.VITE_VERSION).put("lockSha256",lockSha256);
        }
    }
    private static BuiltinVite builtinVite()throws Exception{
        Map<String,String> files=new TreeMap<>(ProjectLiveProjectTemplate.builtinStarterFiles());
        JSONArray entries=new JSONArray();long size=0;
        for(var file:files.entrySet()){
            byte[] bytes=file.getValue().getBytes(StandardCharsets.UTF_8);
            entries.put(new JSONObject().put("path",file.getKey()).put("sha256",ProjectSources.sha256(bytes)).put("bytes",bytes.length));
            size+=bytes.length;
        }
        String digest=ProjectSources.manifestDigest(entries),lockSha=ProjectSources.sha256(files.get("package-lock.json").getBytes(StandardCharsets.UTF_8));
        String id=java.util.UUID.nameUUIDFromBytes((BUILTIN_VITE_FORMAT+"\0"+digest).getBytes(StandardCharsets.UTF_8)).toString();
        return new BuiltinVite(files,id,digest,lockSha,size);
    }
    public static String builtinViteSelectionId()throws Exception{return builtinVite().selectionId();}
    /** Only signed application text enters this native source staging path. */
    public static Selection builtinViteStarter(File root,Current current)throws Exception{
        current.check();freshDirectory(root);var starter=builtinVite();
        for(var file:starter.files().entrySet()){
            current.check();
            Files.write(target(root,path(file.getKey())).toPath(),file.getValue().getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.WRITE,java.nio.file.StandardOpenOption.CREATE_NEW,LinkOption.NOFOLLOW_LINKS);
        }
        var selected=finish(root,starter.selectionId(),BUILTIN_VITE_LABEL,BUILTIN_VITE_STARTER,false,new JSONArray(),starter.marker(),current);
        requireBuiltinViteStarter(selected.evidence(),selected.frozen.manifest());
        current.check();return selected;
    }
    /** Called with the actual codec-verified manifest: a source-kind label or stable ID alone proves nothing. */
    public static void requireBuiltinViteStarter(JSONObject actual,JSONObject manifest)throws Exception{
        var starter=builtinVite();JSONObject source=manifest.getJSONObject("source"),project=manifest.getJSONObject("project");
        JSONArray files=manifest.getJSONArray("files"),directories=manifest.getJSONArray("directories");
        if(actual==null||!starter.selectionId().equals(source.opt("projectId"))||!starter.selectionId().equals(source.opt("checkoutId"))||
            !starter.selectionId().equals(project.opt("id"))||!BUILTIN_VITE_LABEL.equals(project.opt("name"))||
            !"source_project".equals(manifest.opt("mode"))||!"".equals(manifest.opt("entryPath"))||!"".equals(manifest.opt("previewRoot"))||
            !starter.sourceDigest().equals(source.opt("sourceDigest"))||!starter.sourceDigest().equals(ProjectSources.manifestDigest(files))||
            files.length()!=starter.files().size()||starter.bytes()!=manifest.getLong("bytes")||manifest.getJSONArray("omitted").length()!=0||
            !Boolean.FALSE.equals(manifest.getJSONObject("policy").opt("generatedRootsIncluded"))||
            directories.length()!=1||!"src".equals(directories.opt(0)))
            throw new SecurityException("原起步项目临时包不匹配内置源码，未替换或导入其他文件");
        int i=0;
        for(String path:starter.files().keySet()){
            JSONObject file=files.getJSONObject(i++);
            if(!path.equals(file.opt("path"))||!"text".equals(file.opt("kind"))||!Boolean.FALSE.equals(file.opt("executable")))
                throw new SecurityException("原起步项目文件或权限不同，没有使用缓存");
        }
        JSONObject expected=sourceEvidence(starter.selectionId(),BUILTIN_VITE_LABEL,BUILTIN_VITE_STARTER,false,0,
            starter.marker(),manifest,ProjectSources.digest(manifest));
        if(!ProjectSources.canonical(expected).equals(ProjectSources.canonical(actual)))
            throw new SecurityException("原起步项目来源说明不同，没有使用缓存");
    }
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte value:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return out.toString();}
    private static Selection finish(File root,String selection,String label,String kind,boolean generated,JSONArray omitted,JSONObject originalEvidence,Current current)throws Exception {
        if(selection==null||!selection.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("缺少本次原生来源选择身份");String name=label==null?"已有源码":label.replaceAll("[\\p{Cntrl}]"," ").trim();if(name.isEmpty())name="已有源码";if(name.length()>80)name=name.substring(0,80);if(!name.isEmpty()&&Character.isHighSurrogate(name.charAt(name.length()-1)))name=name.substring(0,name.length()-1);
        JSONArray manifest=new JSONArray();try(var paths=Files.walk(root.toPath())){for(var file:java.util.Collections.unmodifiableList(paths.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).sorted().collect(java.util.stream.Collectors.toList()))){current.check();String relative=root.toPath().relativize(file).toString().replace(File.separatorChar,'/');if(ProjectSources.sourcePath(relative))manifest.put(new JSONObject().put("path",relative).put("sha256",fileSha(file.toFile(),current)).put("bytes",Files.size(file)));}}
        String digest=ProjectSources.manifestDigest(manifest);JSONObject original=new JSONObject().put("projectId",selection).put("checkoutId",selection).put("sourceDigest",digest).put("name",name);ProjectPackageArchive.Frozen frozen=ProjectPackageArchive.captureSourceImport(root,original,generated,omitted);current.check();
        return new Selection(frozen,sourceEvidence(selection,name,kind,generated,omitted.length(),originalEvidence,frozen.manifest(),frozen.identity));
    }
    private static JSONObject sourceEvidence(String selection,String name,String kind,boolean generated,int omitted,JSONObject original,
            JSONObject manifest,String identity)throws Exception{
        return new JSONObject().put("format","pocketworld.project-source-selection.v1").put("sourceKind",kind).put("sourceLabel",name)
            .put("selectionId",selection).put("manifestIdentity",identity).put("sourceDigest",manifest.getJSONObject("source").getString("sourceDigest"))
            .put("files",manifest.getJSONArray("files").length()).put("expandedBytes",manifest.getLong("bytes")).put("omittedCount",omitted)
            .put("includeGeneratedRoots",generated).put("completeSelection",true).put("providerAtomicSnapshot",false)
            .put("ordinaryZipIsPocketWorldPackage",original.has("fixedPackageManifestIdentity")).put("stagingIdsAreNotOriginalProjectIdentity",true)
            .put("executionAuthorityRestored",false).put("sourcePermissionPersisted",false).put("original",original);
    }
}
