package dev.pocketengine.features.local.project;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.json.*;
import org.junit.*;

public final class ProjectFileMetadataTest {
    private Path root;
    @Before public void create()throws Exception{root=Files.createTempDirectory("project-file-metadata-");}
    @After public void close()throws Exception{try(var paths=Files.walk(root)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    private JSONObject input(String path)throws Exception{return new JSONObject().put("path",path).put("metadataOnly",true);}
    private JSONObject read(JSONObject input)throws Exception{return ProjectFileQueries.read(root.toFile(),"11111111-1111-1111-1111-111111111111",input,()->{});}
    @Test public void realSmallFileMetadataIncludesHashAndMtimeButNotItsBody()throws Exception{
        String source="原文件🙂\n";writeUtf8(root.resolve("a.txt"),source);JSONObject result=read(input("a.txt"));assertTrue(result.getBoolean("exists"));assertEquals("regular_file",result.getString("entryKind"));assertEquals(source.getBytes(StandardCharsets.UTF_8).length,result.getLong("bytes"));assertEquals(ProjectSources.sha256(source.getBytes(StandardCharsets.UTF_8)),result.getString("sha256"));assertEquals(Files.getLastModifiedTime(root.resolve("a.txt")).toMillis(),result.getLong("lastModifiedMillis"));assertTrue(result.getBoolean("metadataComplete"));assertFalse(result.getBoolean("complete"));assertFalse(result.has("content"));assertTrue(result.getBoolean("hashAvailable"));
    }
    @Test public void directoryMetadataDoesNotInventARecursiveSizeOrContentHash()throws Exception{
        Files.createDirectory(root.resolve("folder"));writeUtf8(root.resolve("folder/a.txt"),"body");JSONObject result=read(input("folder"));assertTrue(result.getBoolean("exists"));assertEquals("directory",result.getString("entryKind"));assertTrue(result.isNull("bytes"));assertTrue(result.isNull("sha256"));assertFalse(result.getBoolean("recursiveSizeComputed"));assertFalse(result.getBoolean("hashAvailable"));
    }
    @Test public void missingIsAnExplicitNativeObservationNotASuccessfulEmptyFileRead()throws Exception{
        JSONObject result=read(input("missing/a.txt").put("expectedHash","absent"));assertFalse(result.getBoolean("exists"));assertEquals("missing",result.getString("entryKind"));assertFalse(result.has("content"));assertTrue(result.isNull("bytes"));assertTrue(result.isNull("sha256"));
        assertThrows(SecurityException.class,()->read(input("missing/a.txt").put("expectedHash","a".repeat(64))));assertThrows(IllegalArgumentException.class,()->read(new JSONObject().put("path","missing/a.txt")));
    }
    @Test public void largeFileMetadataUsesAttributesWithoutPretendingHashOrFullRead()throws Exception{
        Files.write(root.resolve("large.bin"),new byte[ProjectSources.MAX_FILE_BYTES+1]);JSONObject result=read(input("large.bin"));assertEquals(ProjectSources.MAX_FILE_BYTES+1,result.getLong("bytes"));assertTrue(result.isNull("sha256"));assertFalse(result.getBoolean("hashAvailable"));assertEquals("over_2MiB_hash_limit",result.getString("hashUnavailableReason"));assertTrue(result.getBoolean("metadataComplete"));
        assertThrows(SecurityException.class,()->read(input("large.bin").put("expectedHash","a".repeat(64))));assertThrows(IllegalArgumentException.class,()->read(new JSONObject().put("path","large.bin")));
    }
    @Test public void binaryMetadataKeepsActualHashWithoutDecodingOrGuessingMime()throws Exception{
        byte[] bytes={0,(byte)255,1};Files.write(root.resolve("binary.dat"),bytes);JSONObject result=read(input("binary.dat"));assertTrue(result.getBoolean("binary"));assertEquals(ProjectSources.sha256(bytes),result.getString("sha256"));assertTrue(result.isNull("mimeType"));assertEquals("not_probed",result.getString("mimeSource"));assertFalse(result.has("content"));
    }
    @Test public void permissionDenialDoesNotBecomeNotFound()throws Exception{
        Path blocked=Files.createDirectory(root.resolve("blocked"));writeUtf8(blocked.resolve("a.txt"),"private");Set<PosixFilePermission> before=Files.getPosixFilePermissions(blocked);
        try{Files.setPosixFilePermissions(blocked,Set.of());assertThrows(IOException.class,()->read(input("blocked/a.txt")));}finally{Files.setPosixFilePermissions(blocked,before);}
    }
    @Test public void symlinksAndInvalidBooleanDoNotBypassTheOriginalPathFence()throws Exception{
        writeUtf8(root.resolve("a.txt"),"actual");Files.createSymbolicLink(root.resolve("link.txt"),root.resolve("a.txt"));assertThrows(SecurityException.class,()->read(input("link.txt")));assertThrows(IllegalArgumentException.class,()->read(input("a.txt").put("metadataOnly","true")));assertThrows(IllegalArgumentException.class,()->read(input("a.txt").put("metadataOnly",1)));assertThrows(IllegalArgumentException.class,()->read(input("a.txt").put("offset",0)));assertEquals("actual",read(input("a.txt").put("metadataOnly",false)).getString("content"));
    }
    @Test public void originalCancellationCannotBeConvertedToAnAbsenceObservation()throws Exception{
        assertThrows(InterruptedException.class,()->ProjectFileQueries.read(root.toFile(),"11111111-1111-1111-1111-111111111111",input("missing.txt"),()->{throw new InterruptedException("original cancelled");}));
    }
    private static java.nio.file.Path writeUtf8(java.nio.file.Path path,String text)throws java.io.IOException{return Files.write(path,text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}

}
