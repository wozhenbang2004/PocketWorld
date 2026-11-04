package dev.pocketengine.store;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
        Path original=database.toPath().toAbsolutePath().normalize(),parent=original.getParent();
        if(parent==null||!Files.isDirectory(parent,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(parent)||!parent.toRealPath().equals(parent))throw new IOException("database_parent_unavailable");
        String base=original.getFileName().toString();List<String> names=new ArrayList<>(List.of(base,base+"-wal",base+"-shm",base+"-journal"));
        try(var children=Files.list(parent)){for(Path child:(Iterable<Path>)children::iterator){String name=child.getFileName().toString();if(name.startsWith(base+"-mj")){if(names.size()>=128)throw new IOException("database_journal_inventory_limited");names.add(name);}}}
        names.sort(Comparator.naturalOrder());List<Member> members=new ArrayList<>();
        for(String name:names){Path path=parent.resolve(name);boolean exists=Files.exists(path,LinkOption.NOFOLLOW_LINKS);long length=0;String sha="";
            if(exists){if(Files.isSymbolicLink(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("database_file_not_regular");length=Files.size(path);sha=sha256(path.toFile());if(length!=Files.size(path))throw new IOException("database_file_changed_during_hash");}
            members.add(new Member(name,exists,length,sha));
        }
        return new Snapshot(base,members,fingerprint(members));
    }
    /** A successful return proves stable bytes and verified copies, not logical SQLite consistency. */
    public static Snapshot preserve(File database,File destination,Snapshot expected,BooleanSupplier current)throws Exception {
        requireCurrent(current);Snapshot before=inspect(database);if(!before.fingerprint.equals(expected.fingerprint))throw new IOException("database_source_changed_before_copy");
        Path directory=destination.toPath().toAbsolutePath().normalize();if(Files.exists(directory,LinkOption.NOFOLLOW_LINKS))throw new IOException("preservation_destination_already_exists");
        Files.createDirectory(directory);privateDirectory(directory);
        long available=Files.getFileStore(directory).getUsableSpace();if(before.bytes()>available-Math.min(1048576,available))throw new IOException("preservation_space_unavailable");
        for(Member member:before.members){requireCurrent(current);if(!member.exists)continue;Path output=directory.resolve(member.name);copyNew(database.toPath().getParent().resolve(member.name),output,current);if(Files.size(output)!=member.length||!sha256(output.toFile()).equals(member.sha256))throw new IOException("preservation_copy_mismatch");}
        syncDirectory(directory);requireCurrent(current);Snapshot after=inspect(database),copy=inspect(directory.resolve(before.databaseName).toFile());
        if(!after.fingerprint.equals(before.fingerprint))throw new IOException("database_source_changed_during_copy");
        if(!copy.fingerprint.equals(before.fingerprint))throw new IOException("preservation_cohort_mismatch");
        return copy;
    }
    public static void verify(File database,Snapshot expected)throws Exception {if(!inspect(database).fingerprint.equals(expected.fingerprint))throw new IOException("database_fingerprint_changed");}
    static void copyNew(Path source,Path target,BooleanSupplier current)throws Exception {
        if(Files.isSymbolicLink(source)||!Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS))throw new IOException("copy_source_not_regular");
        Files.createFile(target);privateFile(target);
        try(FileInputStream input=new FileInputStream(source.toFile());FileOutputStream output=new FileOutputStream(target.toFile())){byte[] bytes=new byte[65536];int n;while((n=input.read(bytes))!=-1){requireCurrent(current);output.write(bytes,0,n);}output.flush();output.getFD().sync();}
    }
    public static String sha256(File file)throws Exception {MessageDigest digest=MessageDigest.getInstance("SHA-256");try(FileInputStream in=new FileInputStream(file)){byte[] bytes=new byte[65536];int n;while((n=in.read(bytes))!=-1)digest.update(bytes,0,n);}return hex(digest.digest());}
    public static String sha256(byte[] bytes)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static String hex(byte[] value){StringBuilder result=new StringBuilder();for(byte b:value)result.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return result.toString();}
    static void privateDirectory(Path path)throws IOException{File file=path.toFile();if(!file.setReadable(false,false)||!file.setWritable(false,false)||!file.setExecutable(false,false)||!file.setReadable(true,true)||!file.setWritable(true,true)||!file.setExecutable(true,true))throw new IOException("private_preservation_permissions_unconfirmed");}
    static void privateFile(Path path)throws IOException{File file=path.toFile();if(!file.setReadable(false,false)||!file.setWritable(false,false)||!file.setReadable(true,true)||!file.setWritable(true,true))throw new IOException("private_preservation_permissions_unconfirmed");}
    static void syncDirectory(Path directory)throws IOException{try(FileChannel handle=FileChannel.open(directory,StandardOpenOption.READ)){handle.force(true);}}
    static void requireCurrent(BooleanSupplier current){if(current==null||!current.getAsBoolean())throw new SecurityException("原维护审阅窗口已关闭；没有扩大或重放操作");}
    private static String fingerprint(List<Member> members)throws Exception{StringBuilder identity=new StringBuilder("pocketworld.sqlite-files.v1\n");for(Member member:members)identity.append(member.name).append('\0').append(member.exists).append('\0').append(member.length).append('\0').append(member.sha256).append('\n');return sha256(identity.toString().getBytes(StandardCharsets.UTF_8));}
    static Snapshot fromManifest(String base,JSONArray files,String fingerprint)throws Exception{
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
