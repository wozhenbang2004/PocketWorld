package dev.pocketengine.features.local.runtime;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class WorkspaceFileAccessTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    @Test public void normalUtf8AndEmptyRegularFilesRemainReadable()throws Exception{
        Path root=temp.newFolder("checkout").toPath();Files.createDirectories(root.resolve("src"));byte[] value="中文🙂\nline2".getBytes(StandardCharsets.UTF_8);Files.write(root.resolve("src/a.txt"),value);Files.write(root.resolve("empty"),new byte[0]);
        assertArrayEquals(value,WorkspaceFileAccess.read(root.toFile(),"src/a.txt",100));assertThrows(java.nio.charset.CharacterCodingException.class,()->WorkspaceFileAccess.read(root.toFile(),"bad\uD800",100));assertThrows(java.nio.charset.CharacterCodingException.class,()->WorkspaceFileAccess.commit(root.toFile(),"bad\uD800",null,new byte[0]));assertEquals(0,WorkspaceFileAccess.read(root.toFile(),"empty",100).length);
    }
    @Test public void linksFifoAndOversizeFailWithoutReadingTheirContents()throws Exception{
        Path root=temp.newFolder("checkout").toPath(),outside=temp.newFolder("private").toPath();Files.writeString(outside.resolve("secret"),"PRIVATE_SECRET");Files.createSymbolicLink(root.resolve("link"),outside);Files.createLink(root.resolve("hard"),outside.resolve("secret"));
        assertThrows(SecurityException.class,()->WorkspaceFileAccess.read(root.toFile(),"link/secret",100));assertThrows(SecurityException.class,()->WorkspaceFileAccess.read(root.toFile(),"hard",100));
        File fifo=root.resolve("pipe").toFile();assertEquals(0,new ProcessBuilder("mkfifo",fifo.getPath()).start().waitFor());long start=System.nanoTime();assertThrows(SecurityException.class,()->WorkspaceFileAccess.read(root.toFile(),"pipe",100));assertTrue(System.nanoTime()-start<2_000_000_000L);
        Files.writeString(root.resolve("large"),"123456");assertThrows(SecurityException.class,()->WorkspaceFileAccess.read(root.toFile(),"large",5));
    }
    @Test public void exactNativePatchCreatesReplacesDeletesAndRejectsHardlinkBeforeAnyTruncate()throws Exception{
        Path root=temp.newFolder("patch").toPath(),outside=temp.newFolder("private-patch").toPath();byte[] first="中文 before".getBytes(StandardCharsets.UTF_8),second="中文 after".getBytes(StandardCharsets.UTF_8);
        WorkspaceFileAccess.commit(root.toFile(),"src/file",null,first);assertArrayEquals(first,WorkspaceFileAccess.read(root.toFile(),"src/file",100));
        assertThrows(SecurityException.class,()->WorkspaceFileAccess.commit(root.toFile(),"src/file",null,second));assertThrows(SecurityException.class,()->WorkspaceFileAccess.commit(root.toFile(),"src/file",new byte[0],second));
        WorkspaceFileAccess.commit(root.toFile(),"src/file",first,second);assertArrayEquals(second,WorkspaceFileAccess.read(root.toFile(),"src/file",100));WorkspaceFileAccess.commit(root.toFile(),"src/file",second,null);assertFalse(Files.exists(root.resolve("src/file")));
        Files.write(outside.resolve("secret"),first);Files.createLink(root.resolve("hard"),outside.resolve("secret"));assertThrows(SecurityException.class,()->WorkspaceFileAccess.commit(root.toFile(),"hard",first,second));assertArrayEquals(first,Files.readAllBytes(outside.resolve("secret")));
    }
    @Test public void concurrentParentReplacementNeverCapturesOutsideSecret()throws Exception{
        Path root=temp.newFolder("checkout").toPath(),outside=temp.newFolder("private").toPath(),dir=root.resolve("dir"),held=root.resolve("held");Files.createDirectory(dir);Files.writeString(dir.resolve("file"),"SAFE");Files.writeString(outside.resolve("file"),"PRIVATE_SECRET");AtomicBoolean stop=new AtomicBoolean();AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread swap=new Thread(()->{try{while(!stop.get()){Files.move(dir,held);Files.createSymbolicLink(dir,outside);Files.delete(dir);Files.move(held,dir);}}catch(Throwable e){failure.set(e);}});swap.start();
        try{for(int i=0;i<300;i++)try{assertEquals("SAFE",new String(WorkspaceFileAccess.read(root.toFile(),"dir/file",100),StandardCharsets.UTF_8));}catch(SecurityException expected){}}
        finally{stop.set(true);swap.join(2000);}assertFalse(swap.isAlive());if(failure.get()!=null)throw new AssertionError(failure.get());
    }
}
