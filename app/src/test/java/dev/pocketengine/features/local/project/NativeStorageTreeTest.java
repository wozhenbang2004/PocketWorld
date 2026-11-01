package dev.pocketengine.features.local.project;

import dev.pocketengine.core.RunControl;
import java.nio.file.*;
import org.junit.*;
import static org.junit.Assert.*;

/** Private temporary fixtures only. The original source and artifacts are never cleanup targets. */
public final class NativeStorageTreeTest {
    @Test public void exactReviewedCacheDeletesOnlyItsNodesAndLeavesSourceAndLinkedTargetUntouched()throws Exception {
        Path base=Files.createTempDirectory("storage-exact-"),cache=Files.createDirectory(base.resolve("node_modules")),source=base.resolve("main.ts"),outside=Files.createDirectory(base.resolve("outside"));Files.writeString(source,"keep source");Files.writeString(outside.resolve("secret"),"not inside cache");Files.createDirectories(cache.resolve("pkg"));Files.write(cache.resolve("pkg/binary"),new byte[]{0,1,2});Files.createSymbolicLink(cache.resolve("linked-package"),outside);var measured=NativeStorageTree.measure(cache.toFile(),new RunControl());assertEquals(3,measured.getLong("logicalBytes"));assertEquals(1,measured.getInt("links"));var reviewed=NativeStorageTree.scan(cache.toFile(),new RunControl());assertEquals(3,reviewed.logicalBytes);assertEquals(1,reviewed.links);var result=reviewed.delete(new RunControl());assertTrue(result.getBoolean("targetAbsent"));assertEquals(3,result.getLong("logicalBytesRemoved"));assertTrue(result.isNull("actualDiskBytesFreed"));assertEquals("keep source",Files.readString(source));assertEquals("not inside cache",Files.readString(outside.resolve("secret")));assertFalse(reviewed.observe().getBoolean("deletionAttributionProven"));
    }
    @Test public void changedFileOrNewChildRefusesBeforeDeletingAnyReviewedEntry()throws Exception {
        Path base=Files.createTempDirectory("storage-drift-"),cache=Files.createDirectory(base.resolve("build"));Files.writeString(cache.resolve("first"),"old");Files.writeString(cache.resolve("second"),"keep");var review=NativeStorageTree.scan(cache.toFile(),new RunControl());Files.writeString(cache.resolve("first"),"new and different");assertThrows(Exception.class,()->review.delete(new RunControl()));assertTrue(Files.exists(cache.resolve("first")));assertTrue(Files.exists(cache.resolve("second")));var again=NativeStorageTree.scan(cache.toFile(),new RunControl());Files.writeString(cache.resolve("new-unreviewed"),"new");assertThrows(Exception.class,()->again.delete(new RunControl()));assertEquals(3,cache.toFile().list().length);
    }
    @Test public void replacedDirectorySymlinkAndCancelledReviewNeverTraverseOtherResources()throws Exception {
        Path base=Files.createTempDirectory("storage-link-"),cache=Files.createDirectory(base.resolve("dist")),original=cache.resolve("data");Files.writeString(original,"original");var review=NativeStorageTree.scan(cache.toFile(),new RunControl());Path moved=base.resolve("retained");Files.move(cache,moved);Path outside=Files.createDirectory(base.resolve("outside"));Files.writeString(outside.resolve("data"),"outside");Files.createSymbolicLink(cache,outside);assertThrows(Exception.class,()->review.delete(new RunControl()));assertEquals("outside",Files.readString(outside.resolve("data")));assertEquals("original",Files.readString(moved.resolve("data")));RunControl cancelled=new RunControl();cancelled.cancel();assertThrows(Exception.class,()->NativeStorageTree.scan(moved.toFile(),cancelled));assertTrue(Files.exists(moved.resolve("data")));
    }
    @Test public void emptyAndAbsentCachesStayExplicitWithoutInventingReclaimedPhysicalBytes()throws Exception {
        Path base=Files.createTempDirectory("storage-empty-"),empty=Files.createDirectory(base.resolve("empty")),missing=base.resolve("missing");var absent=NativeStorageTree.scan(missing.toFile(),new RunControl());assertEquals(0,absent.logicalBytes);assertEquals(0,absent.metadata().getInt("entries"));assertTrue(absent.observe().getBoolean("targetAbsent"));assertFalse(absent.observe().getBoolean("deletionAttributionProven"));var snapshot=NativeStorageTree.scan(empty.toFile(),new RunControl());assertEquals(1,snapshot.metadata().getInt("entries"));assertTrue(snapshot.delete(new RunControl()).getBoolean("targetAbsent"));assertEquals(0,NativeStorageTree.measure(missing.toFile(),new RunControl()).getLong("logicalBytes"));
    }
}
