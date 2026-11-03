package dev.pocketengine.features.workspace;

import dev.pocketengine.core.RunControl;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Password wrapper around the original archive bytes; no new archive sections or restore rights.
 * V1: fixed 48-byte header, 64KiB AES-256-GCM records, and an authenticated SHA-256 footer.
 * PBKDF2-HMAC-SHA256 uses a fresh 128-bit salt and exactly 600,000 iterations. Each record
 * uses the random 64-bit nonce prefix plus its 32-bit index; AAD binds the entire header,
 * index and final-record flag. Size, order, truncation and replacement are authenticated.
 * A reader publishes no usable cache until every tag, footer, length and EOF is verified.
 * This is an application format, not encrypted ZIP or a claim of third-party interoperability.
 */
public final class OwnerBackupEnvelope {
    private static final byte[] MAGIC = {'P','W','B','K','E','N','C','1'};
    static final int HEADER_BYTES=48, CHUNK_BYTES=65536, ITERATIONS=600000;
    private static final long MAX_PLAINTEXT=1536L*1024*1024;
    private static final SecureRandom RANDOM=new SecureRandom();
    private OwnerBackupEnvelope(){}
    public static void requirePassword(char[] password){
        if(password==null||password.length<12||password.length>1024)throw new IllegalArgumentException("备份口令须为12至1024个字符；建议使用独立长口令");
        for(int i=0;i<password.length;i++)if(Character.isSurrogate(password[i])){
            if(!Character.isHighSurrogate(password[i])||i+1>=password.length||!Character.isLowSurrogate(password[++i]))throw new IllegalArgumentException("备份口令包含不完整的字符");
        }
    }
    public static long encryptedBytes(long plain){
        if(plain<22||plain>MAX_PLAINTEXT)throw new IllegalArgumentException("原备份大小超出支持范围");
        return HEADER_BYTES+plain+16*((plain+CHUNK_BYTES-1)/CHUNK_BYTES)+48;
    }
    public static long encrypt(InputStream original,OutputStream destination,long bytes,String originalSha,char[] password,RunControl control)throws Exception{
        requirePassword(password);long result=encryptedBytes(bytes);
        if(originalSha==null||!originalSha.matches("[a-f0-9]{64}"))throw new SecurityException("缺少原备份的固定摘要");
        byte[] salt=new byte[16],prefix=new byte[8];RANDOM.nextBytes(salt);RANDOM.nextBytes(prefix);
        byte[] header=ByteBuffer.allocate(HEADER_BYTES).put(MAGIC).putInt(ITERATIONS).putInt(CHUNK_BYTES).putLong(bytes).put(salt).put(prefix).array();
        byte[] key=derive(password,salt,control),buffer=new byte[CHUNK_BYTES];
        try {
            destination.write(header);MessageDigest hash=MessageDigest.getInstance("SHA-256");long remaining=bytes;int index=0;
            while(remaining>0){control.checkpoint();int length=(int)Math.min(remaining,CHUNK_BYTES);readExactly(original,buffer,length,control);hash.update(buffer,0,length);byte[] encrypted=record(Cipher.ENCRYPT_MODE,key,header,prefix,index++,false,buffer,length);try{destination.write(encrypted);}finally{Arrays.fill(encrypted,(byte)0);}Arrays.fill(buffer,(byte)0);remaining-=length;}
            if(original.read()!=-1)throw new SecurityException("原备份大小已变化，未完成加密导出");
            byte[] digest=hash.digest();try{if(!hex(digest).equals(originalSha))throw new SecurityException("原备份摘要已变化，未完成加密导出");control.checkpoint();byte[] footer=record(Cipher.ENCRYPT_MODE,key,header,prefix,index,true,digest,digest.length);try{destination.write(footer);}finally{Arrays.fill(footer,(byte)0);}}finally{Arrays.fill(digest,(byte)0);}
            control.checkpoint();return result;
        }finally{Arrays.fill(key,(byte)0);Arrays.fill(buffer,(byte)0);}
    }
    /** Only the original private archive cache is a destination. Partial plaintext remains
     * unreviewable and is removed on failure; callers still run OwnerBackupArchive.verify. */
    static void decryptToNewFile(InputStream encrypted,File newPrivateCache,char[] password,RunControl control)throws Exception{
        requirePassword(password);byte[] header=new byte[HEADER_BYTES];readExactly(encrypted,header,header.length,control);
        ByteBuffer parsed=ByteBuffer.wrap(header);byte[] magic=new byte[8];parsed.get(magic);
        if(!Arrays.equals(MAGIC,magic)||parsed.getInt()!=ITERATIONS||parsed.getInt()!=CHUNK_BYTES)throw new SecurityException("不支持的加密备份版本或参数；未尝试其它格式");
        long bytes=parsed.getLong();encryptedBytes(bytes);byte[] salt=new byte[16],prefix=new byte[8];parsed.get(salt);parsed.get(prefix);
        byte[] key=derive(password,salt,control),buffer=new byte[CHUNK_BYTES+16];Path path=newPrivateCache.toPath();Object fileKey=null;boolean created=false,complete=false;
        try {
            try(FileChannel channel=FileChannel.open(path,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))){
                created=true;fileKey=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();
                OutputStream output=Channels.newOutputStream(channel);MessageDigest hash=MessageDigest.getInstance("SHA-256");long remaining=bytes;int index=0;
                while(remaining>0){control.checkpoint();int length=(int)Math.min(remaining,CHUNK_BYTES);readExactly(encrypted,buffer,length+16,control);byte[] plain=record(Cipher.DECRYPT_MODE,key,header,prefix,index++,false,buffer,length+16);try{if(plain.length!=length)throw invalid();hash.update(plain);output.write(plain);}finally{Arrays.fill(plain,(byte)0);}remaining-=length;}
                readExactly(encrypted,buffer,48,control);byte[] expected=record(Cipher.DECRYPT_MODE,key,header,prefix,index,true,buffer,48),actual=hash.digest();
                try{if(expected.length!=32||!MessageDigest.isEqual(expected,actual)||encrypted.read()!=-1)throw invalid();}finally{Arrays.fill(expected,(byte)0);Arrays.fill(actual,(byte)0);}
                control.checkpoint();channel.force(true);complete=true;
            }
        }catch(javax.crypto.AEADBadTagException changed){throw invalid();}
        finally {
            Arrays.fill(key,(byte)0);Arrays.fill(buffer,(byte)0);
            if(created&&!complete){BasicFileAttributes now=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(fileKey!=null&&now.isRegularFile()&&fileKey.equals(now.fileKey()))Files.delete(path);}
        }
    }
    private static byte[] derive(char[] password,byte[] salt,RunControl control)throws Exception{
        control.checkpoint();PBEKeySpec spec=new PBEKeySpec(password,salt,ITERATIONS,256);
        try{byte[] key=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();try{control.checkpoint();return key;}catch(Exception stopped){Arrays.fill(key,(byte)0);throw stopped;}}finally{spec.clearPassword();}
    }
    private static byte[] record(int mode,byte[] key,byte[] header,byte[] prefix,int index,boolean last,byte[] input,int count)throws Exception{
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");byte[] nonce=ByteBuffer.allocate(12).put(prefix).putInt(index).array();
        cipher.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(header);cipher.updateAAD(ByteBuffer.allocate(5).putInt(index).put((byte)(last?1:0)).array());return cipher.doFinal(input,0,count);
    }
    private static void readExactly(InputStream input,byte[] target,int bytes,RunControl control)throws Exception{int offset=0;while(offset<bytes){control.checkpoint();int read=input.read(target,offset,bytes-offset);if(read<0)throw invalid();if(read>0)offset+=read;}}
    private static SecurityException invalid(){return new SecurityException("口令错误，或加密备份不完整/已变化；没有采用部分数据");}
    private static String hex(byte[] value){StringBuilder text=new StringBuilder();for(byte b:value)text.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return text.toString();}
}
