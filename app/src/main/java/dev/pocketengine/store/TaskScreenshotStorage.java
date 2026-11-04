package dev.pocketengine.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Binary files only. Policy, original task identity, and deletion decisions belong to TaskStore. */
public final class TaskScreenshotStorage {
    public static final int MAX_IMAGE_BYTES=4_000_000;
    private static final byte[] MAGIC="PWSC0001".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_AAD_BYTES=2048,MAX_CONTAINER_BYTES=MAX_IMAGE_BYTES+MAX_AAD_BYTES+128;
    public interface Crypto {byte[] seal(byte[] plain,byte[] aad)throws Exception;byte[] open(byte[] encrypted,byte[] aad)throws Exception;}
    private final Path root;private final Crypto crypto;
    public TaskScreenshotStorage(Path root,Crypto crypto){this.root=root.toAbsolutePath().normalize();this.crypto=java.util.Objects.requireNonNull(crypto);}
    public String fileName(String task,String image)throws Exception {return prefix(task)+identity(image)+".pws";}
    public void write(String task,String image,byte[] plain,byte[] aad)throws Exception {
        checkRoot();if(plain==null||plain.length==0||plain.length>MAX_IMAGE_BYTES)throw new IllegalArgumentException("结果截图大小无效");
        Path destination=root.resolve(fileName(task,image));if(Files.exists(destination,LinkOption.NOFOLLOW_LINKS))throw new SecurityException("原截图编号已存在，不能覆盖");
        byte[] encrypted=crypto.seal(plain,aad);if(encrypted.length>MAX_CONTAINER_BYTES)throw new SecurityException("截图密文超出上限");
        Path pending=Files.createTempFile(root,prefix(task)+identity(image)+"-",".pending");
        try{
            try(FileChannel file=FileChannel.open(pending,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){ByteBuffer buffer=ByteBuffer.wrap(encrypted);while(buffer.hasRemaining())file.write(buffer);file.force(true);}
            Files.move(pending,destination,StandardCopyOption.ATOMIC_MOVE);
        }finally{Files.deleteIfExists(pending);Arrays.fill(encrypted,(byte)0);}
    }
    public byte[] read(String task,String image,byte[] aad)throws Exception {
        checkRoot();Path file=root.resolve(fileName(task,image));byte[] encrypted=bounded(file,MAX_CONTAINER_BYTES);
        try{byte[] plain=crypto.open(encrypted,aad);if(plain.length==0||plain.length>MAX_IMAGE_BYTES){Arrays.fill(plain,(byte)0);throw new SecurityException("原截图长度不可核对");}return plain;}
        finally{Arrays.fill(encrypted,(byte)0);}
    }
    public byte[] readLegacy(String task)throws Exception {
        checkRoot();Path file=legacy(task),backup=root.resolve(file.getFileName()+".bak");
        // Match the previous AtomicFile reader: its backup wins after an interrupted write.
        return bounded(Files.exists(backup,LinkOption.NOFOLLOW_LINKS)?backup:file,MAX_IMAGE_BYTES);
    }
    public boolean legacyExists(String task)throws Exception {
        checkRoot();Path file=legacy(task);return Files.exists(file,LinkOption.NOFOLLOW_LINKS)||Files.exists(root.resolve(file.getFileName()+".bak"),LinkOption.NOFOLLOW_LINKS);
    }
    public void deleteLegacy(String task)throws Exception {
        checkRoot();Path original=legacy(task);for(String suffix:new String[]{"",".bak",".new"}){
            Path file=root.resolve(original.getFileName()+suffix);Files.deleteIfExists(file);if(Files.exists(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("原明文截图删除未确认");
        }
    }
    /** Removes only files in this task's dedicated screenshot namespace, including interrupted ciphertext stages. */
    public void deleteTask(String task)throws Exception {
        checkRoot();String prefix=prefix(task);try(var files=Files.newDirectoryStream(root,prefix+"*")){for(Path file:files){String name=file.getFileName().toString();if(name.endsWith(".pws")||name.endsWith(".pending"))Files.delete(file);}}
        deleteLegacy(task);
    }
    public void deleteImage(String task,String image)throws Exception {checkRoot();Files.deleteIfExists(root.resolve(fileName(task,image)));}
    public void removeSuperseded(String task,String keepImage)throws Exception {
        checkRoot();String keep=keepImage==null?"":fileName(task,keepImage),prefix=prefix(task);
        try(var files=Files.newDirectoryStream(root,prefix+"*")){for(Path file:files){String name=file.getFileName().toString();if(!name.equals(keep)&&(name.endsWith(".pws")||name.endsWith(".pending")))Files.delete(file);}}
    }
    /** Expired native ciphertext only, including a completed file whose SQL pointer never committed. */
    public JSONObject removeExpired(long now)throws Exception {
        checkRoot();int inspected=0,deleted=0,unconfirmed=0;
        try(var files=Files.newDirectoryStream(root,"screenshot-*")){for(Path file:files){
            if(Thread.currentThread().isInterrupted())break;if(!nativeName(file)&&!stagingName(file))continue;inspected++;
            try{
                // TaskStore never references .pending. A partial encrypted stage with this
                // module's exact namespace/magic can be removed after a full day at rest.
                if(stagingName(file)&&nativeMagic(file)&&now-Files.getLastModifiedTime(file,LinkOption.NOFOLLOW_LINKS).toMillis()>=TaskScreenshotPolicy.DAY){Files.delete(file);deleted++;continue;}
                byte[] aad=header(file);JSONObject metadata=TaskScreenshotPolicy.metadataFromAad(aad);if(!matchesHeaderName(file,metadata))throw new SecurityException("截图原头与文件不符");
                if(!TaskScreenshotPolicy.expired(metadata,now))continue;byte[] encrypted=bounded(file,MAX_CONTAINER_BYTES),plain=null;
                try{plain=crypto.open(encrypted,aad);if(plain.length!=metadata.getLong("bytes"))throw new SecurityException("截图原字节长度不同");Files.delete(file);deleted++;}
                finally{Arrays.fill(encrypted,(byte)0);if(plain!=null)Arrays.fill(plain,(byte)0);}
            }catch(Exception unavailable){unconfirmed++;}
        }}return new JSONObject().put("inspected",inspected).put("deleted",deleted).put("unconfirmed",unconfirmed);
    }
    /** Header lookup schedules cleanup only. It grants neither reading nor deletion. */
    public long nextExpiry()throws Exception {
        checkRoot();long next=-1;try(var files=Files.newDirectoryStream(root,"screenshot-*")){for(Path file:files){
            if(Thread.currentThread().isInterrupted())break;if(!nativeName(file)&&!stagingName(file))continue;
            try{if(stagingName(file)&&nativeMagic(file)){long stageExpiry=Math.addExact(Files.getLastModifiedTime(file,LinkOption.NOFOLLOW_LINKS).toMillis(),TaskScreenshotPolicy.DAY);next=next<0?stageExpiry:Math.min(next,stageExpiry);}
                JSONObject metadata=TaskScreenshotPolicy.metadataFromAad(header(file));if(!matchesHeaderName(file,metadata))continue;long expiry=metadata.getLong("expiresAt");if(expiry>0)next=next<0?expiry:Math.min(next,expiry);}catch(Exception unavailable){/* Invalid headers cannot authorize result cleanup. */}
        }}return next;
    }
    private static boolean nativeName(Path file){return file.getFileName().toString().matches("screenshot-[a-f0-9]{64}-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.pws");}
    private static boolean stagingName(Path file){return file.getFileName().toString().matches("screenshot-[a-f0-9]{64}-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}--?[0-9]{1,20}\\.pending");}
    private boolean matchesHeaderName(Path file,JSONObject metadata)throws Exception {String expected=fileName(metadata.getString("taskId"),metadata.getString("imageId")),actual=file.getFileName().toString();return actual.equals(expected)||stagingName(file)&&actual.startsWith(expected.substring(0,expected.length()-4)+"-");}
    private static boolean nativeMagic(Path file)throws Exception {
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file))return false;
        try(FileChannel channel=FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){ByteBuffer first=ByteBuffer.allocate(MAGIC.length);while(first.hasRemaining())if(channel.read(first)<0)return false;return Arrays.equals(MAGIC,first.array());}
    }
    private static byte[] header(Path file)throws Exception {
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file))throw new IOException("截图原密文不可读");
        try(FileChannel channel=FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            ByteBuffer first=ByteBuffer.allocate(MAGIC.length+4);while(first.hasRemaining())if(channel.read(first)<0)throw new IOException("截图原头不完整");
            if(!Arrays.equals(MAGIC,Arrays.copyOf(first.array(),MAGIC.length)))throw new SecurityException("截图封装不符");int size=ByteBuffer.wrap(first.array(),MAGIC.length,4).getInt();
            if(size<1||size>MAX_AAD_BYTES)throw new SecurityException("截图原头超出上限");ByteBuffer aad=ByteBuffer.allocate(size);while(aad.hasRemaining())if(channel.read(aad)<0)throw new IOException("截图原头不完整");return aad.array();
        }
    }
    private Path legacy(String task)throws Exception {
        if(task==null||!task.matches("[A-Za-z0-9_.:-]{1,200}"))throw new SecurityException("原明文截图任务身份无效");
        Path file=root.resolve(task+".jpg").normalize();if(!root.equals(file.getParent()))throw new SecurityException("原截图路径无效");return file;
    }
    private void checkRoot()throws Exception {
        if(Files.isSymbolicLink(root))throw new SecurityException("原截图私有目录不可用");
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw new SecurityException("原截图私有目录不可用");
        if(!root.equals(root.toRealPath()))throw new SecurityException("原截图私有目录不可用");
    }
    private static byte[] bounded(Path file,int maximum)throws Exception {
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file))throw new IOException("原截图文件缺失或不可读取");
        long size=Files.size(file);if(size<1||size>maximum)throw new IOException("原截图文件大小不可核对");
        try(FileChannel channel=FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            ByteBuffer bytes=ByteBuffer.allocate(Math.toIntExact(size));while(bytes.hasRemaining()){if(channel.read(bytes)<0)throw new IOException("原截图读取不完整");}
            if(channel.read(ByteBuffer.allocate(1))!=-1)throw new IOException("原截图读取期间大小变化");return bytes.array();
        }
    }
    private static String prefix(String task)throws Exception {
        if(task==null||task.isEmpty()||task.length()>200)throw new SecurityException("原截图任务身份无效");
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(task.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder("screenshot-");for(byte b:hash)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.append('-').toString();
    }
    private static String identity(String value){if(value==null||!UUID.fromString(value).toString().equals(value))throw new SecurityException("原截图编号无效");return value;}
    /** Standard AES-GCM, using the existing SecretVault Android Keystore key in production. */
    public static byte[] seal(SecretKey key,byte[] plain,byte[] aad)throws Exception {
        if(aad==null||aad.length<1||aad.length>MAX_AAD_BYTES)throw new SecurityException("截图原头无效");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key);byte[] iv=cipher.getIV();if(iv.length!=12)throw new SecurityException("不支持的截图加密随机数长度");
        cipher.updateAAD(MAGIC);cipher.updateAAD(aad);byte[] encrypted=cipher.doFinal(plain);
        return ByteBuffer.allocate(MAGIC.length+4+aad.length+iv.length+encrypted.length).put(MAGIC).putInt(aad.length).put(aad).put(iv).put(encrypted).array();
    }
    public static byte[] open(SecretKey key,byte[] encrypted,byte[] aad)throws Exception {
        if(encrypted.length<MAGIC.length+4+12+16||!Arrays.equals(MAGIC,Arrays.copyOf(encrypted,MAGIC.length)))throw new SecurityException("原截图加密封装无效");
        int size=ByteBuffer.wrap(encrypted,MAGIC.length,4).getInt(),offset=MAGIC.length+4;
        if(size<1||size>MAX_AAD_BYTES||encrypted.length<offset+size+12+16||!Arrays.equals(aad,Arrays.copyOfRange(encrypted,offset,offset+size)))throw new SecurityException("原截图认证头无效");offset+=size;
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Arrays.copyOfRange(encrypted,offset,offset+12)));
        cipher.updateAAD(MAGIC);cipher.updateAAD(aad);return cipher.doFinal(encrypted,offset+12,encrypted.length-offset-12);
    }
}
