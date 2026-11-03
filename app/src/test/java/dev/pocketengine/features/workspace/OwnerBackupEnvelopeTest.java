package dev.pocketengine.features.workspace;

import dev.pocketengine.core.RunControl;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Arrays;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

public final class OwnerBackupEnvelopeTest {
    private static final char[] PASSWORD="local backup fixture passphrase".toCharArray();
    private static byte[] plain,sealed;private static String sha;
    @BeforeClass public static void originalArchiveBytes()throws Exception{
        plain=new byte[OwnerBackupEnvelope.CHUNK_BYTES*2+37];for(int i=0;i<plain.length;i++)plain[i]=(byte)(i*31+i/7);
        sha=hash(plain);sealed=encrypt(plain,sha,new RunControl());
    }
    private static String hash(byte[] value)throws Exception {return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
    private static byte[] encrypt(byte[] bytes,String expected,RunControl control)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();long count=OwnerBackupEnvelope.encrypt(new ByteArrayInputStream(bytes),out,bytes.length,expected,PASSWORD,control);assertEquals(out.size(),count);return out.toByteArray();}
    private void rejects(byte[] value,char[] password)throws Exception{
        Path dir=Files.createTempDirectory("backup-envelope-test-"),target=dir.resolve("quarantine.zip");
        try{assertThrows(Exception.class,()->OwnerBackupEnvelope.decryptToNewFile(new ByteArrayInputStream(value),target.toFile(),password,new RunControl()));assertFalse("No partial plaintext cache may remain",Files.exists(target));}finally{Files.deleteIfExists(target);Files.delete(dir);}
    }
    @Test public void authenticatesAllRecordsAndFooterBeforePublishingOriginalBytes()throws Exception{
        Path dir=Files.createTempDirectory("backup-envelope-test-"),target=dir.resolve("quarantine.zip");
        try{OwnerBackupEnvelope.decryptToNewFile(new ByteArrayInputStream(sealed),target.toFile(),PASSWORD,new RunControl());assertArrayEquals(plain,Files.readAllBytes(target));assertEquals("rw-------",PosixFilePermissions.toString(Files.getPosixFilePermissions(target)));assertFalse(new String(sealed,java.nio.charset.StandardCharsets.ISO_8859_1).contains(sha));}finally{Files.deleteIfExists(target);Files.delete(dir);}
    }
    @Test public void wrongPasswordAndChangedRecordRemovePartialPlaintext()throws Exception{
        rejects(sealed,"different fixture passphrase".toCharArray());byte[] changed=sealed.clone();changed[OwnerBackupEnvelope.HEADER_BYTES+OwnerBackupEnvelope.CHUNK_BYTES+17]^=1;rejects(changed,PASSWORD);
    }
    @Test public void headerFooterTruncationAndTrailingDataCannotBeAccepted()throws Exception{
        byte[] changed=sealed.clone();changed[24]^=1;rejects(changed,PASSWORD);changed=sealed.clone();changed[changed.length-1]^=1;rejects(changed,PASSWORD);
        rejects(Arrays.copyOf(sealed,sealed.length-1),PASSWORD);rejects(Arrays.copyOf(sealed,sealed.length+1),PASSWORD);
    }
    @Test public void reorderingAndForeignEnvelopeSplicingFailAuthentication()throws Exception{
        byte[] changed=sealed.clone();int record=OwnerBackupEnvelope.CHUNK_BYTES+16,start=OwnerBackupEnvelope.HEADER_BYTES;
        System.arraycopy(sealed,start+record,changed,start,record);System.arraycopy(sealed,start,changed,start+record,record);rejects(changed,PASSWORD);
        byte[] other=encrypt(plain,sha,new RunControl());assertFalse(Arrays.equals(sealed,other));System.arraycopy(other,start,changed,start,record);rejects(changed,PASSWORD);
    }
    @Test public void unsupportedParametersAndOversizedLengthsFailBeforeCreatingFiles()throws Exception{
        byte[] changed=sealed.clone();ByteBuffer.wrap(changed).putInt(8,1);rejects(changed,PASSWORD);changed=sealed.clone();ByteBuffer.wrap(changed).putLong(16,Long.MAX_VALUE);rejects(changed,PASSWORD);
        assertThrows(IllegalArgumentException.class,()->OwnerBackupEnvelope.requirePassword("short".toCharArray()));
        assertThrows(IllegalArgumentException.class,()->OwnerBackupEnvelope.requirePassword("long enough \ud800".toCharArray()));
    }
    @Test public void refusesExistingFilesAndSymlinksWithoutChangingThem()throws Exception{
        Path dir=Files.createTempDirectory("backup-envelope-test-"),existing=dir.resolve("existing"),link=dir.resolve("link");byte[] sentinel={1,2,3};Files.write(existing,sentinel);Files.createSymbolicLink(link,existing);
        try{assertThrows(Exception.class,()->OwnerBackupEnvelope.decryptToNewFile(new ByteArrayInputStream(sealed),existing.toFile(),PASSWORD,new RunControl()));assertThrows(Exception.class,()->OwnerBackupEnvelope.decryptToNewFile(new ByteArrayInputStream(sealed),link.toFile(),PASSWORD,new RunControl()));assertArrayEquals(sentinel,Files.readAllBytes(existing));assertTrue(Files.isSymbolicLink(link));}finally{Files.deleteIfExists(link);Files.delete(existing);Files.delete(dir);}
    }
    @Test public void sourceDigestOrLengthChangesCannotProduceAnAuthenticatedFooter()throws Exception{
        ByteArrayOutputStream output=new ByteArrayOutputStream();assertThrows(SecurityException.class,()->OwnerBackupEnvelope.encrypt(new ByteArrayInputStream(plain),output,plain.length,"a".repeat(64),PASSWORD,new RunControl()));rejects(output.toByteArray(),PASSWORD);
        output.reset();assertThrows(SecurityException.class,()->OwnerBackupEnvelope.encrypt(new ByteArrayInputStream(Arrays.copyOf(plain,plain.length+1)),output,plain.length,sha,PASSWORD,new RunControl()));rejects(output.toByteArray(),PASSWORD);
    }
    @Test public void cancellationDuringInputRemovesOnlyTheOwnedQuarantine()throws Exception{
        Path dir=Files.createTempDirectory("backup-envelope-test-"),target=dir.resolve("quarantine.zip");RunControl control=new RunControl();
        InputStream stopped=new ByteArrayInputStream(sealed){int read;@Override public synchronized int read(byte[] out,int off,int len){int n=super.read(out,off,len);if((read+=Math.max(n,0))>OwnerBackupEnvelope.HEADER_BYTES+OwnerBackupEnvelope.CHUNK_BYTES)control.cancel();return n;}};
        try{assertThrows(InterruptedException.class,()->OwnerBackupEnvelope.decryptToNewFile(stopped,target.toFile(),PASSWORD,control));assertFalse(Files.exists(target));}finally{Files.deleteIfExists(target);Files.delete(dir);}
    }
}
