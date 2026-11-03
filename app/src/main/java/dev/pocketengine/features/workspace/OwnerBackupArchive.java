package dev.pocketengine.features.workspace;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.project.ProjectSources;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.json.*;

/** Portable owner data archive, not a SQLite/task/grant snapshot. Entries are
 * content-addressed blobs and a strict manifest; no archived path is extracted. */
public final class OwnerBackupArchive {
    public static final String FORMAT="pocketworld.owner-data-backup.v1";
    public static final long MAX_BYTES=1536L*1024*1024,MAX_OBJECT=20L*1024*1024;
    public static final int MAX_OBJECTS=20000,MAX_MANIFEST=4*1024*1024;
    public interface Read {InputStream open()throws Exception;}
    public static final class ObjectBytes {
        public final String sha256;public final long bytes;private final Read original;
        public ObjectBytes(String hash,long size,Read original){if(hash==null||!hash.matches("[a-f0-9]{64}")||size<0||size>MAX_OBJECT)throw new IllegalArgumentException("备份原件身份或容量无效");sha256=hash;bytes=size;this.original=Objects.requireNonNull(original);}
        public static ObjectBytes json(JSONObject nativeSection)throws Exception{byte[] bytes=encode(ProjectSources.canonical(nativeSection));if(bytes.length>8*1024*1024)throw new IllegalArgumentException("备份数据分区超过8MiB");return new ObjectBytes(ProjectSources.sha256(bytes),bytes.length,()->new ByteArrayInputStream(bytes));}
    }
    /** Only this decoder constructs a verified portable archive. SHA alone is not
     * owner approval and does not restore source permissions or running work. */
    public static final class Verified {
        private final File file;private final String sha;private final long size;private final JSONObject manifest;private final Map<String,Long> objects;private final Object fileKey;private final java.nio.file.attribute.FileTime modified;
        private Verified(File file,String sha,long size,JSONObject manifest,Map<String,Long> objects)throws Exception{this.file=file;this.sha=sha;this.size=size;this.manifest=new JSONObject(manifest.toString());this.objects=Collections.unmodifiableMap(new LinkedHashMap<>(objects));var identity=Files.readAttributes(file.toPath(),java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);fileKey=identity.fileKey();modified=identity.lastModifiedTime();}
        private void current(RunControl control)throws Exception{control.checkpoint();var now=Files.readAttributes(file.toPath(),java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(!now.isRegularFile()||now.size()!=size||!Objects.equals(fileKey,now.fileKey())||!modified.equals(now.lastModifiedTime()))throw new SecurityException("原备份已变化，未继续恢复");}
        public JSONObject metadata()throws Exception{return new JSONObject().put("format",FORMAT).put("archiveSha256",sha).put("archiveBytes",size).put("objectCount",objects.size()).put("sections",new JSONObject(manifest.getJSONObject("sections").toString())).put("encrypted",false).put("userContentMayContainSensitiveData",true).put("restoresAccountCredentials",false).put("restoresTasksOrGrants",false);}
        public String sha256(){return sha;}
        public long bytes(){return size;}
        public JSONObject section(String name,RunControl control)throws Exception{
            if(!SECTIONS.contains(name)||!manifest.getJSONObject("sections").has(name))throw new IllegalArgumentException("备份没有此数据分区");String hash=manifest.getJSONObject("sections").getString(name);if(objects.get(hash)>8L*1024*1024)throw new SecurityException("备份分区超出8MiB");ByteArrayOutputStream data=new ByteArrayOutputStream();copyObject(hash,data,control);return parseObject(data.toByteArray());
        }
        public void copyObject(String hash,OutputStream output,RunControl control)throws Exception{
            Long expected=objects.get(hash);if(expected==null)throw new SecurityException("不是这份已核备份的原对象");current(control);try(ZipFile archive=open(file)){ZipArchiveEntry entry=archive.getEntry("objects/"+hash);if(entry==null)throw new SecurityException("备份原对象已缺失");try(InputStream input=archive.getInputStream(entry)){copy(input,output,expected,hash,control);}}current(control);
        }
        public byte[] object(String hash,RunControl control)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();copyObject(hash,out,control);return out.toByteArray();}
        void discardLocalCache(RunControl original)throws Exception{current(original);Files.delete(file.toPath());if(Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS))throw new java.io.IOException("原备份缓存移除未确认");}
        public long export(OutputStream target,RunControl control)throws Exception{current(control);try(InputStream input=Files.newInputStream(file.toPath(),LinkOption.NOFOLLOW_LINKS)){copy(input,target,size,sha,control);}current(control);return size;}
        public long exportEncrypted(OutputStream target,char[] password,RunControl control)throws Exception{current(control);try(InputStream input=Files.newInputStream(file.toPath(),LinkOption.NOFOLLOW_LINKS)){long bytes=OwnerBackupEnvelope.encrypt(input,target,size,sha,password,control);current(control);return bytes;}}

    }
    private static final Set<String> SECTIONS=Set.of("projects","artifacts","memory","experiences","remoteConnections","sshConnections");
    private OwnerBackupArchive(){}
    public static Verified create(File newFile,Map<String,ObjectBytes> supplied,Map<String,String> sections,RunControl control)throws Exception{
        if(newFile.exists()||Files.isSymbolicLink(newFile.toPath()))throw new SecurityException("备份目标必须是新的本机临时文件，未覆盖");Map<String,ObjectBytes> objects=new TreeMap<>();long total=0;
        for(var row:supplied.entrySet()){ObjectBytes value=row.getValue();if(!row.getKey().equals(value.sha256))throw new SecurityException("备份对象键与实际SHA不同");if(objects.put(value.sha256,value)!=null)throw new SecurityException("备份对象重复");total=Math.addExact(total,value.bytes);}
        if(objects.size()>MAX_OBJECTS||total>MAX_BYTES)throw new IllegalArgumentException("完整备份超过20000对象或1.5GiB；未生成截断备份");JSONObject selected=new JSONObject();for(var section:sections.entrySet()){if(!SECTIONS.contains(section.getKey())||!objects.containsKey(section.getValue()))throw new SecurityException("备份数据分区无效");selected.put(section.getKey(),section.getValue());}
        if(selected.length()==0)throw new IllegalArgumentException("请至少明确选择一个数据分区");JSONArray entries=new JSONArray();for(var row:objects.values())entries.put(new JSONObject().put("sha256",row.sha256).put("bytes",row.bytes));JSONObject manifest=new JSONObject().put("format",FORMAT).put("version",1).put("sections",selected).put("objects",entries).put("encrypted",false).put("accountCredentialsIncluded",false).put("restoresTasksOrGrants",false);byte[] body=encode(ProjectSources.canonical(manifest));if(body.length>MAX_MANIFEST)throw new IllegalArgumentException("备份清单超过4MiB");
        boolean completed=false;try(FileOutputStream raw=new FileOutputStream(newFile);java.util.zip.ZipOutputStream zip=new java.util.zip.ZipOutputStream(raw,StandardCharsets.UTF_8)){
            zip.putNextEntry(new java.util.zip.ZipEntry("manifest.json"));zip.write(body);zip.closeEntry();for(var object:objects.values()){control.checkpoint();zip.putNextEntry(new java.util.zip.ZipEntry("objects/"+object.sha256));try(InputStream input=object.original.open()){copy(input,zip,object.bytes,object.sha256,control);}zip.closeEntry();}zip.finish();zip.flush();raw.getFD().sync();control.checkpoint();completed=true;
        }finally{if(!completed)Files.deleteIfExists(newFile.toPath());}
        if(newFile.length()>MAX_BYTES){Files.deleteIfExists(newFile.toPath());throw new IllegalArgumentException("备份ZIP超过1.5GiB，未保留部分归档");}return verify(newFile,control);
    }
    public static Verified verify(File supplied,RunControl control)throws Exception{
        File file=supplied.getAbsoluteFile();if(Files.isSymbolicLink(file.toPath())||!file.isFile()||file.length()<22||file.length()>MAX_BYTES)throw new SecurityException("请选择1.5GiB以内的完整PocketWorld数据备份");long size=file.length();String sha=hash(file,control);JSONObject manifest;Map<String,ZipArchiveEntry> actual=new LinkedHashMap<>();
        try(ZipFile archive=open(file)){
            for(Enumeration<ZipArchiveEntry> it=archive.getEntries();it.hasMoreElements();){control.checkpoint();ZipArchiveEntry entry=it.nextElement();String name=entry.getName(),original=strict(entry.getRawName());if(!original.equals(name)||!name.matches("manifest\\.json|objects/[a-f0-9]{64}")||entry.isDirectory()||entry.isUnixSymlink()||entry.getUnixMode()!=0&&(entry.getUnixMode()&0170000)!=0100000||entry.getGeneralPurposeBit().usesEncryption()||!archive.canReadEntryData(entry)||entry.getSize()<0||entry.getSize()>MAX_OBJECT||actual.put(name,entry)!=null||actual.size()>MAX_OBJECTS+1)throw new SecurityException("备份含链接、特殊/重复/非标准路径或不支持的ZIP条目");}
            ZipArchiveEntry root=actual.get("manifest.json");if(root==null||root.getSize()>MAX_MANIFEST)throw new SecurityException("不是完整PocketWorld数据备份");try(InputStream input=archive.getInputStream(root)){ByteArrayOutputStream bytes=new ByteArrayOutputStream();copy(input,bytes,root.getSize(),null,control);manifest=parseObject(bytes.toByteArray());}
            requireKeys(manifest,Set.of("format","version","sections","objects","encrypted","accountCredentialsIncluded","restoresTasksOrGrants"));if(!FORMAT.equals(manifest.getString("format"))||!Integer.valueOf(1).equals(manifest.opt("version"))||!Boolean.FALSE.equals(manifest.opt("encrypted"))||!Boolean.FALSE.equals(manifest.opt("accountCredentialsIncluded"))||!Boolean.FALSE.equals(manifest.opt("restoresTasksOrGrants")))throw new SecurityException("备份版本或授权边界不支持");
            Map<String,Long> objects=new LinkedHashMap<>();JSONArray listed=manifest.getJSONArray("objects");long total=0;if(listed.length()>MAX_OBJECTS)throw new SecurityException("备份对象过多");for(int i=0;i<listed.length();i++){JSONObject row=listed.getJSONObject(i);requireKeys(row,Set.of("sha256","bytes"));String id=row.getString("sha256");Object bytes=row.opt("bytes");if(!id.matches("[a-f0-9]{64}")||!(bytes instanceof Integer||bytes instanceof Long)||row.getLong("bytes")<0||row.getLong("bytes")>MAX_OBJECT||objects.put(id,row.getLong("bytes"))!=null)throw new SecurityException("备份对象清单无效");total=Math.addExact(total,row.getLong("bytes"));ZipArchiveEntry entry=actual.get("objects/"+id);if(entry==null||entry.getSize()!=row.getLong("bytes")||total>MAX_BYTES)throw new SecurityException("备份原对象缺失或总量超限");try(InputStream input=archive.getInputStream(entry)){copy(input,new OutputStream(){@Override public void write(int value){}@Override public void write(byte[] value,int offset,int length){}},row.getLong("bytes"),id,control);}}
            if(actual.size()!=objects.size()+1)throw new SecurityException("备份包含清单外数据，未读取其他对象");JSONObject sections=manifest.getJSONObject("sections");if(sections.length()<1)throw new SecurityException("备份缺少数据分区");for(var keys=sections.keys();keys.hasNext();){String name=keys.next();if(!SECTIONS.contains(name)||!objects.containsKey(sections.getString(name)))throw new SecurityException("备份分区引用不完整");}
            requireCurrent(file,sha,size,control);return new Verified(file,sha,size,manifest,objects);
        }
    }
    /** Bound nesting before invoking Android's original JSON parser; this is
     * not a replacement parser/serializer. Valid strings are never repaired. */
    public static JSONObject parseObject(byte[] bytes)throws Exception {
        if(bytes.length>8*1024*1024)throw new SecurityException("归档JSON对象超过8MiB");String text=strict(bytes);boolean quoted=false,escaped=false;int depth=0,nodes=0;for(int i=0;i<text.length();i++){char c=text.charAt(i);if(quoted){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')quoted=false;continue;}if(c=='"')quoted=true;else if(c=='{'||c=='['){if(++depth>32||++nodes>200000)throw new SecurityException("归档JSON嵌套或节点数超出支持范围");}else if(c=='}'||c==']'){if(--depth<0)throw new SecurityException("归档JSON结构不完整");}else if(c==','){if(++nodes>200000)throw new SecurityException("归档JSON条目超出支持范围");}}if(quoted||escaped||depth!=0)throw new SecurityException("归档JSON不是完整对象");JSONObject parsed=new JSONObject(text);unicode(parsed);return parsed;
    }
    private static void unicode(Object value)throws Exception {if(value instanceof String)encode((String)value);else if(value instanceof JSONObject){JSONObject object=(JSONObject)value;for(var keys=object.keys();keys.hasNext();){String key=keys.next();encode(key);unicode(object.get(key));}}else if(value instanceof JSONArray){JSONArray rows=(JSONArray)value;for(int i=0;i<rows.length();i++)unicode(rows.get(i));}}
    private static ZipFile open(File file)throws IOException{return ZipFile.builder().setFile(file).setCharset(StandardCharsets.UTF_8).setUseUnicodeExtraFields(false).get();}
    private static byte[] encode(String text)throws Exception{var encoded=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text));byte[] value=new byte[encoded.remaining()];encoded.get(value);return value;}
    private static String strict(byte[] bytes)throws Exception{if(bytes==null)throw new SecurityException("备份ZIP缺少原UTF-8文件名");return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();}
    private static void requireKeys(JSONObject object,Set<String> expected){Set<String> actual=new HashSet<>();object.keys().forEachRemaining(actual::add);if(!actual.equals(expected))throw new SecurityException("备份含未声明字段");}
    private static void requireCurrent(File file,String sha,long bytes,RunControl control)throws Exception{if(Files.isSymbolicLink(file.toPath())||!file.isFile()||file.length()!=bytes||!hash(file,control).equals(sha))throw new SecurityException("原备份在审阅后已变化，未继续恢复");}
    private static String hash(File file,RunControl control)throws Exception{var digest=java.security.MessageDigest.getInstance("SHA-256");try(InputStream input=Files.newInputStream(file.toPath(),LinkOption.NOFOLLOW_LINKS)){byte[] buffer=new byte[32768];long count=0;for(int n;(n=input.read(buffer))>=0;){control.checkpoint();if(n==0)continue;if((count+=n)>MAX_BYTES)throw new SecurityException("备份读取超限");digest.update(buffer,0,n);}}return hex(digest.digest());}
    private static void copy(InputStream input,OutputStream target,long expected,String hash,RunControl control)throws Exception{var digest=java.security.MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[32768];long count=0;for(int n;(n=input.read(buffer))>=0;){control.checkpoint();if(n==0)continue;if((count+=n)>expected)throw new SecurityException("备份原件实际字节超过声明");digest.update(buffer,0,n);target.write(buffer,0,n);}if(count!=expected||hash!=null&&!hash.equals(hex(digest.digest())))throw new SecurityException("备份原件SHA或实际长度不同，未采用部分数据");}
    private static String hex(byte[] hash){StringBuilder out=new StringBuilder();for(byte b:hash)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
}
