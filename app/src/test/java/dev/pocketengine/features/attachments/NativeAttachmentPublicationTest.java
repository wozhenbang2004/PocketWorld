package dev.pocketengine.features.attachments;

import static org.junit.Assert.*;
import dev.pocketengine.core.RunControl;
import java.io.*;
import java.nio.file.*;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;

public final class NativeAttachmentPublicationTest {
    @Test public void exactNativeOperationAndRoleColdReadsOriginalBytesWithoutASecondInputRead()throws Exception{
        Path root=Files.createTempDirectory("c2-native-publication-");String operation=UUID.randomUUID().toString(),task=UUID.randomUUID().toString(),run=UUID.randomUUID().toString(),digest="a".repeat(64);var book=new AttachmentBook(root.toFile());var control=new RunControl();
        var one=book.importNativeResult(new ByteArrayInputStream(new byte[]{0,1,(byte)255}),"original.bin","application/octet-stream",new JSONObject().put("kind","derived_media"),operation,"media_output",task,run,digest,control);
        var cold=new AttachmentBook(root.toFile());var same=cold.importNativeResult(new InputStream(){public int read(){throw new AssertionError("must not read replacement input");}},"untrusted-other-name.bin","application/octet-stream",new JSONObject(),operation,"media_output",task,run,digest,control);assertEquals(one.getString("id"),same.getString("id"));assertArrayEquals(new byte[]{0,1,(byte)255},cold.readVerifiedSnapshot(same.getString("id"),same.getString("sha256"),control));assertEquals(1,cold.list().length());assertThrows(Exception.class,()->cold.findNativeResult(operation,"media_output",task,run,"b".repeat(64),control));
    }
    @Test public void orphanAndRetirementNeverPermitOverwriteOrNewRandomPublication()throws Exception{
        Path root=Files.createTempDirectory("c2-native-publication-orphan-");var book=new AttachmentBook(root.toFile());String operation=UUID.randomUUID().toString(),digest="a".repeat(64);var control=new RunControl();String id=book.findNativeResult(operation,"document_output","","",digest,control).getString("id");Files.write(root.resolve(id+".blob"),new byte[]{9});assertThrows(Exception.class,()->book.importNativeResult(new ByteArrayInputStream(new byte[]{2}),"new.bin","application/octet-stream",new JSONObject(),operation,"document_output","","",digest,control));assertArrayEquals(new byte[]{9},Files.readAllBytes(root.resolve(id+".blob")));try(var entries=Files.list(root)){assertEquals(1,entries.count());}
    }
}
