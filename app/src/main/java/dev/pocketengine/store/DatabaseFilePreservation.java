package dev.pocketengine.store;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Byte preservation of a closed native SQLite file cohort. No SQLite open or source mutation. */
public final class DatabaseFilePreservation {
    private DatabaseFilePreservation() { }
    public record Member(String name,boolean exists,long length,String sha256) {
        JSONObject json()throws Exception{return new JSONObject().put("name",name).put("exists",exists).put("length",length).put("sha256",sha256);}
    }
    public record Snapshot(String databaseName,List<Member> members,String fingerprint) {
        public Snapshot{members=List.copyOf(members);}
        public JSONArray files()throws Exception{JSONArray value=new JSONArray();for(Member member:members)value.put(member.json());return value;}
        public long bytes(){long value=0;for(Member member:members)value=Math.addExact(value,member.length);return value;}
    }
    public static Snapshot inspect(File database)throws Exception {
        return inspect(database,()->true);
    }
    static Snapshot inspect(File database,BooleanSupplier current)throws Exception {
        requireCurrent(current);
        Path original=database.toPath().toAbsolutePath().normalize(),parent=original.getParent();
        requireDirectory(parent);
        String base=original.getFileName().toString();requireName(base);List<String> names=inventory(parent,base);List<Member> members=new ArrayList<>();
        for(String name:names){requireCurrent(current);Path path=parent.resolve(name);BasicFileAttributes attributes=optionalAttributes(path);boolean exists=attributes!=null;long length=0;String sha="";
            if(exists){requireRegular(attributes);length=attributes.size();sha=hash(path,attributes,current);}
            members.add(new Member(name,exists,length,sha));
        }
        if(!names.equals(inventory(parent,base)))throw new IOException("database_journal_inventory_changed");
        requireCurrent(current);
        return new Snapshot(base,members,fingerprint(members));
    }
    private static List<String> inventory(Path parent,String base)throws IOException {
        List<String> names=new ArrayList<>(List.of(base,base+"-wal",base+"-shm",base+"-journal"));
        try(var children=Files.list(parent)){for(Path child:(Iterable<Path>)children::iterator){String name=child.getFileName().toString();if(name.startsWith(base+"-mj")){if(names.size()>=128)throw new IOException("database_journal_inventory_limited");names.add(name);}}}
        names.sort(Comparator.naturalOrder());return names;
    }
    /** A successful return proves stable bytes and verified copies, not logical SQLite consistency. */
    public static Snapshot preserve(File database,File destination,Snapshot expected,BooleanSupplier current)throws Exception {
        requireCurrent(current);Snapshot before=inspect(database,current);if(!before.fingerprint.equals(expected.fingerprint))throw new IOException("database_source_changed_before_copy");
        Path source=database.toPath().toAbsolutePath().normalize();Path directory=destination.toPath().toAbsolutePath().normalize();requireDirectory(directory.getParent());
        if(optionalAttributes(directory)!=null)throw new IOException("preservation_destination_already_exists");
        Files.createDirectory(directory);privateDirectory(directory);syncDirectory(directory.getParent());
        long available=Files.getFileStore(directory).getUsableSpace();if(before.bytes()>available-Math.min(1048576,available))throw new IOException("preservation_space_unavailable");
        for(Member member:before.members){requireCurrent(current);if(!member.exists)continue;Path output=directory.resolve(member.name);copyNew(source.getParent().resolve(member.name),output,member.length,current);if(Files.size(output)!=member.length||!sha256(output.toFile()).equals(member.sha256))throw new IOException("preservation_copy_mismatch");}
        syncDirectory(directory);requireCurrent(current);Snapshot after=inspect(database,current),copy=inspect(directory.resolve(before.databaseName).toFile(),current);
        if(!after.fingerprint.equals(before.fingerprint))throw new IOException("database_source_changed_during_copy");
        if(!copy.fingerprint.equals(before.fingerprint))throw new IOException("preservation_cohort_mismatch");
        return copy;
    }
    public static void verify(File database,Snapshot expected)throws Exception {if(!inspect(database).fingerprint.equals(expected.fingerprint))throw new IOException("database_fingerprint_changed");}
    /** Small maintenance evidence files only; rejects growth before allocating or reading more. */
    static byte[] readBounded(Path path,int maximum,BooleanSupplier current)throws Exception {
        requireCurrent(current);requireDirectory(path.toAbsolutePath().getParent());
        BasicFileAttributes original=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);requireRegular(original);
        if(maximum<0||original.size()<0||original.size()>maximum)throw new IOException("preservation_read_size_unconfirmed");
        byte[] bytes=new byte[(int)original.size()];
        try(FileChannel input=FileChannel.open(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining()){requireCurrent(current);if(input.read(buffer)<0)throw new IOException("preservation_read_changed");}
            if(input.read(ByteBuffer.allocate(1))!=-1)throw new IOException("preservation_read_changed");
        }
        requireUnchanged(path,original);requireCurrent(current);return bytes;
    }
    private static void copyNew(Path source,Path target,long expectedLength,BooleanSupplier current)throws Exception {
        BasicFileAttributes original=Files.readAttributes(source,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);requireRegular(original);
        if(original.size()!=expectedLength)throw new IOException("copy_source_length_changed");
        requireDirectory(target.getParent());requireCurrent(current);
        try(FileChannel input=FileChannel.open(source,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);
            FileChannel output=FileChannel.open(target,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            privateFile(target);ByteBuffer bytes=ByteBuffer.allocate(65536);long count=0;int n;
            while((n=input.read(bytes))!=-1){requireCurrent(current);if(n==0)continue;count=Math.addExact(count,n);if(count>expectedLength)throw new IOException("copy_source_length_changed");bytes.flip();while(bytes.hasRemaining())output.write(bytes);bytes.clear();}
            if(count!=expectedLength)throw new IOException("copy_source_length_changed");output.force(true);
        }
        requireUnchanged(source,original);
    }
    public static String sha256(File file)throws Exception {Path path=file.toPath();BasicFileAttributes original=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);requireRegular(original);return hash(path,original,()->true);}
    private static String hash(Path path,BasicFileAttributes original,BooleanSupplier current)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");long count=0;
        try(FileChannel input=FileChannel.open(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer bytes=ByteBuffer.allocate(65536);int n;while((n=input.read(bytes))!=-1){requireCurrent(current);if(n==0)continue;count=Math.addExact(count,n);if(count>original.size())throw new IOException("database_file_changed_during_hash");bytes.flip();digest.update(bytes);bytes.clear();}
        }
        if(count!=original.size())throw new IOException("database_file_changed_during_hash");requireUnchanged(path,original);return hex(digest.digest());
    }
    private static void requireUnchanged(Path path,BasicFileAttributes original)throws IOException {
        BasicFileAttributes after=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);requireRegular(after);
        if(original.size()!=after.size()||!original.lastModifiedTime().equals(after.lastModifiedTime())||!Objects.equals(original.fileKey(),after.fileKey()))throw new IOException("database_file_changed_during_read");
    }
    static BasicFileAttributes optionalAttributes(Path path)throws IOException {try{return Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}catch(NoSuchFileException absent){return null;}}
    static void requireDirectory(Path path)throws IOException {if(path==null||!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(path)||!path.toAbsolutePath().normalize().toRealPath().equals(path.toAbsolutePath().normalize()))throw new IOException("database_parent_unavailable");}
    private static void requireRegular(BasicFileAttributes attributes)throws IOException {if(!attributes.isRegularFile()||attributes.isSymbolicLink())throw new IOException("database_file_not_regular");}
    private static void requireName(String name){if(name==null||name.isEmpty()||name.equals(".")||name.equals("..")||name.contains("/")||name.contains("\\")||name.contains("\0"))throw new SecurityException("原数据库文件名无效");}
    public static String sha256(byte[] bytes)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static String hex(byte[] value){StringBuilder result=new StringBuilder();for(byte b:value)result.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return result.toString();}
    static void privateDirectory(Path path)throws IOException{requireDirectory(path);File file=path.toFile();if(!file.setReadable(false,false)||!file.setWritable(false,false)||!file.setExecutable(false,false)||!file.setReadable(true,true)||!file.setWritable(true,true)||!file.setExecutable(true,true))throw new IOException("private_preservation_permissions_unconfirmed");}
    static void privateFile(Path path)throws IOException{requireRegular(Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS));File file=path.toFile();if(!file.setReadable(false,false)||!file.setWritable(false,false)||!file.setExecutable(false,false)||!file.setReadable(true,true)||!file.setWritable(true,true))throw new IOException("private_preservation_permissions_unconfirmed");}
    static void syncDirectory(Path directory)throws IOException{requireDirectory(directory);try(FileChannel handle=FileChannel.open(directory,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){handle.force(true);}}
    static void requireCurrent(BooleanSupplier current){if(current==null||Thread.currentThread().isInterrupted()||!current.getAsBoolean())throw new SecurityException("原维护审阅窗口已关闭；没有扩大或重放操作");}
    private static String fingerprint(List<Member> members)throws Exception{StringBuilder identity=new StringBuilder("pocketworld.sqlite-files.v1\n");for(Member member:members)identity.append(member.name).append('\0').append(member.exists).append('\0').append(member.length).append('\0').append(member.sha256).append('\n');return sha256(identity.toString().getBytes(StandardCharsets.UTF_8));}
    static Snapshot fromManifest(String base,JSONArray files,String fingerprint)throws Exception{
        requireName(base);
        if(files==null||files.length()<4||files.length()>128||fingerprint==null||!fingerprint.matches("[a-f0-9]{64}"))throw new SecurityException("原数据库清单范围或指纹无效");
        List<Member> members=new ArrayList<>();java.util.Set<String> names=new java.util.HashSet<>();
        for(int i=0;i<files.length();i++){JSONObject row=files.getJSONObject(i);String name=row.getString("name");if(!name.equals(base)&&!name.equals(base+"-wal")&&!name.equals(base+"-shm")&&!name.equals(base+"-journal")&&!name.startsWith(base+"-mj"))throw new SecurityException("非原数据库文件清单");
            if(name.contains("/")||name.contains("\\")||name.contains("\0")||!names.add(name)||!(row.get("exists") instanceof Boolean)||!(row.get("length") instanceof Integer||row.get("length") instanceof Long))throw new SecurityException("数据库清单字段或唯一身份无效");
            boolean exists=row.getBoolean("exists");long length=row.getLong("length");String sha=row.getString("sha256");if(length<0||exists&&!sha.matches("[a-f0-9]{64}")||!exists&&(length!=0||!sha.isEmpty()))throw new SecurityException("数据库清单长度或SHA无效");members.add(new Member(name,exists,length,sha));
        }
        if(!names.containsAll(List.of(base,base+"-wal",base+"-shm",base+"-journal")))throw new SecurityException("原数据库清单缺少必要存在性记录");members.sort(Comparator.comparing(Member::name));
        if(!fingerprint(members).equals(fingerprint))throw new SecurityException("原数据库清单内容与指纹不同");return new Snapshot(base,members,fingerprint);
    }
}
