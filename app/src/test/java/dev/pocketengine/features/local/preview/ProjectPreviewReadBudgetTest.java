package dev.pocketengine.features.local.preview;

import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class ProjectPreviewReadBudgetTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private static final int LIMIT=ProjectPreviewBundle.MAX_RESOURCE_BYTES;
    private ProjectPreviewBundle.Verified capture(ProjectReadRaceFileSystem fs,Path root)throws Exception{return ProjectPreviewBundle.capture(fs.root(root),"checkout","project","a".repeat(64),"index.html");}
    @Test public void resourceGrowthAfterWalkStatStopsAtSingleBudgetPlusOne()throws Exception {
        Path root=temporary.newFolder().toPath(),entry=root.resolve("index.html");Files.write(entry,new byte[]{1});ProjectReadRaceFileSystem fs=new ProjectReadRaceFileSystem();
        fs.beforeOpen=path->{if(path.equals(entry))Files.write(path,new byte[LIMIT+65536]);};
        assertThrows(java.io.IOException.class,()->capture(fs,root));
        assertEquals("one opened resource only",1,fs.opens);assertEquals(fs.opens,fs.closes);assertTrue("read="+fs.bytesRead(entry),fs.bytesRead(entry)<=LIMIT+1L);assertTrue("same resource stream is no-follow",fs.noFollow);
    }
    @Test public void resourceGrowthDuringReadStopsAtSingleBudgetPlusOne()throws Exception {
        Path root=temporary.newFolder().toPath(),entry=root.resolve("index.html");Files.write(entry,new byte[4096]);ProjectReadRaceFileSystem fs=new ProjectReadRaceFileSystem();AtomicBoolean changed=new AtomicBoolean();
        fs.afterRead=path->{if(path.equals(entry)&&changed.compareAndSet(false,true))Files.write(path,new byte[LIMIT+65536],StandardOpenOption.APPEND);};
        assertThrows(java.io.IOException.class,()->capture(fs,root));assertTrue(changed.get());assertEquals(1,fs.opens);assertEquals(fs.opens,fs.closes);assertTrue("read="+fs.bytesRead(entry),fs.bytesRead(entry)<=LIMIT+1L);
    }
    @Test public void totalBudgetUsesRemainingBytesBeforeReadingNextGrowingResource()throws Exception {
        Path root=temporary.newFolder().toPath();for(int i=0;i<8;i++)Files.write(root.resolve(i==0?"index.html":"a"+i+".js"),new byte[LIMIT]);Path tail=root.resolve("z.js");Files.write(tail,new byte[0]);ProjectReadRaceFileSystem fs=new ProjectReadRaceFileSystem();
        fs.beforeOpen=path->{if(path.equals(tail))Files.write(path,new byte[65536]);};
        assertThrows(java.io.IOException.class,()->capture(fs,root));assertEquals(1L,fs.bytesRead(tail));assertEquals(9,fs.opens);assertEquals(fs.opens,fs.closes);
    }
    @Test public void exactSingleAndTotalBoundariesRemainValidAndImmutable()throws Exception {
        Path root=temporary.newFolder().toPath();for(int i=0;i<8;i++)Files.write(root.resolve(i==0?"index.html":"a"+i+".js"),new byte[LIMIT]);Files.write(root.resolve("z.js"),new byte[0]);ProjectReadRaceFileSystem fs=new ProjectReadRaceFileSystem();
        ProjectPreviewBundle.Verified bundle=capture(fs,root);assertEquals(ProjectPreviewBundle.MAX_TOTAL_BYTES,bundle.totalBytes);assertEquals(9,bundle.manifest().length());assertEquals(LIMIT,bundle.resource(bundle.entryUrl).bytes().length);assertFalse(bundle.metadata().getBoolean("atomicArtifactFence"));assertFalse(bundle.metadata().getBoolean("businessVerified"));assertTrue(fs.noFollow);assertEquals(fs.opens,fs.closes);
        byte[] copy=bundle.resource(bundle.entryUrl).bytes();copy[0]=3;assertEquals(0,bundle.resource(bundle.entryUrl).bytes()[0]);
    }
    @Test public void underBudgetGrowthIsStillAChangedObservation()throws Exception {
        Path root=temporary.newFolder().toPath(),entry=root.resolve("index.html");Files.write(entry,new byte[]{1});ProjectReadRaceFileSystem fs=new ProjectReadRaceFileSystem();
        fs.beforeOpen=path->{if(path.equals(entry))Files.write(path,new byte[]{1,2});};
        assertThrows(java.io.IOException.class,()->capture(fs,root));assertEquals(2L,fs.bytesRead(entry));assertEquals(fs.opens,fs.closes);
    }
    @Test public void sameLengthReplacementAfterReadDoesNotAdoptNewFileIdentity()throws Exception {
        Path root=temporary.newFolder().toPath(),entry=root.resolve("index.html");Files.write(entry,new byte[]{1});var modified=Files.getLastModifiedTime(entry);ProjectReadRaceFileSystem fs=new ProjectReadRaceFileSystem();AtomicBoolean changed=new AtomicBoolean();
        fs.afterRead=path->{if(path.equals(entry)&&changed.compareAndSet(false,true)){Files.move(entry,root.resolve("original"));Files.write(entry,new byte[]{1});Files.setLastModifiedTime(entry,modified);}};
        assertThrows(java.io.IOException.class,()->capture(fs,root));assertEquals(fs.opens,fs.closes);
    }
    @Test public void finalPathSwapToSymlinkIsRejectedByTheOpenedStream()throws Exception {
        Path root=temporary.newFolder().toPath(),entry=root.resolve("index.html"),outside=temporary.newFile().toPath();Files.write(entry,new byte[]{1});Files.write(outside,new byte[]{9});ProjectReadRaceFileSystem fs=new ProjectReadRaceFileSystem();
        fs.beforeOpen=path->{if(path.equals(entry)){Files.delete(entry);Files.createSymbolicLink(entry,outside);}};
        assertThrows(java.io.IOException.class,()->capture(fs,root));assertTrue(fs.noFollow);assertEquals(0L,fs.bytesRead(entry));assertEquals(fs.opens,fs.closes);
    }
    @Test public void excludedCachesAndUnsupportedFilesAreNotOpened()throws Exception {
        Path root=temporary.newFolder().toPath();Files.write(root.resolve("index.html"),new byte[]{1});Files.createDirectories(root.resolve("node_modules"));Files.write(root.resolve("node_modules/hidden.js"),new byte[]{2});Files.write(root.resolve("private.txt"),new byte[]{3});ProjectReadRaceFileSystem fs=new ProjectReadRaceFileSystem();
        ProjectPreviewBundle.Verified bundle=capture(fs,root);assertEquals(1,bundle.totalBytes);assertEquals(1,bundle.manifest().length());assertEquals(1,fs.opens);assertEquals(fs.opens,fs.closes);
    }
}
