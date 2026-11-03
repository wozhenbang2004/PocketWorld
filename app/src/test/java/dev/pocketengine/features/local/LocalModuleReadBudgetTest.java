package dev.pocketengine.features.local;

import dev.pocketengine.features.local.preview.ProjectReadRaceFileSystem;
import dev.pocketengine.features.local.project.ProjectSources;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Original legacy read/export reader tests; no native process or device is launched. */
public final class LocalModuleReadBudgetTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private JSONObject export(File root)throws Exception {
        Method method=LocalModule.class.getDeclaredMethod("exportDirectory",File.class,JSONObject.class);method.setAccessible(true);
        try{return (JSONObject)method.invoke(null,root,new JSONObject().put("id","original-checkout"));}catch(InvocationTargetException failure){throw (Exception)failure.getCause();}
    }
    @Test public void legacyExportGrowthAfterWalkStatStopsAtSingleBudgetPlusOne()throws Exception {
        Path root=temporary.newFolder().toPath(),file=root.resolve("main.js");Files.write(file,new byte[]{1});ProjectReadRaceFileSystem fs=new ProjectReadRaceFileSystem();fs.beforeOpen=path->{if(path.equals(file))Files.write(path,new byte[ProjectSources.MAX_FILE_BYTES+65536]);};
        assertThrows(java.io.IOException.class,()->export(fs.root(root)));assertTrue("read="+fs.bytesRead(file),fs.bytesRead(file)<=ProjectSources.MAX_FILE_BYTES+1L);assertEquals(1,fs.opens);assertEquals(fs.opens,fs.closes);assertTrue(fs.noFollow);
    }
    @Test public void legacyExportGrowthDuringReadStopsAtSingleBudgetPlusOne()throws Exception {
        Path root=temporary.newFolder().toPath(),file=root.resolve("main.js");Files.write(file,new byte[4096]);ProjectReadRaceFileSystem fs=new ProjectReadRaceFileSystem();AtomicBoolean changed=new AtomicBoolean();fs.afterRead=path->{if(path.equals(file)&&changed.compareAndSet(false,true))Files.write(path,new byte[ProjectSources.MAX_FILE_BYTES+65536],StandardOpenOption.APPEND);};
        assertThrows(java.io.IOException.class,()->export(fs.root(root)));assertTrue(changed.get());assertTrue("read="+fs.bytesRead(file),fs.bytesRead(file)<=ProjectSources.MAX_FILE_BYTES+1L);assertEquals(fs.opens,fs.closes);
    }
    @Test public void legacyExportAppliesRemainingTotalBudgetToNextOpenedFile()throws Exception {
        Path root=temporary.newFolder().toPath();for(int i=0;i<8;i++)Files.write(root.resolve("a"+i+".bin"),new byte[ProjectSources.MAX_FILE_BYTES]);Path tail=root.resolve("z.bin");Files.write(tail,new byte[0]);ProjectReadRaceFileSystem fs=new ProjectReadRaceFileSystem();fs.beforeOpen=path->{if(path.equals(tail))Files.write(path,new byte[65536]);};
        assertThrows(java.io.IOException.class,()->export(fs.root(root)));assertEquals(1L,fs.bytesRead(tail));assertEquals(9,fs.opens);assertEquals(fs.opens,fs.closes);
    }
    @Test public void legacyExportRetainsExactLimitsBinaryIdentityAndSourceExclusions()throws Exception {
        Path root=temporary.newFolder().toPath();for(int i=0;i<8;i++)Files.write(root.resolve("a"+i+".bin"),new byte[ProjectSources.MAX_FILE_BYTES]);Files.write(root.resolve("z.txt"),new byte[0]);Files.createDirectories(root.resolve("dist"));Files.write(root.resolve("dist/output.js"),new byte[]{1});Files.createDirectories(root.resolve("node_modules"));Files.write(root.resolve("node_modules/hidden.js"),new byte[]{2});ProjectReadRaceFileSystem fs=new ProjectReadRaceFileSystem();
        JSONObject snapshot=export(fs.root(root));assertEquals(ProjectSources.MAX_TOTAL_BYTES,snapshot.getInt("bytes"));assertEquals(9,snapshot.getJSONArray("files").length());assertEquals(ProjectSources.MAX_TOTAL_BYTES,ProjectSources.describe(snapshot.put("checkout",new JSONObject().put("id","original-checkout").put("projectId","original-project"))).getInt("bytes"));assertTrue(fs.noFollow);assertEquals(fs.opens,fs.closes);
    }
    @Test public void legacyExportRejectsSameLengthFileReplacementAfterRead()throws Exception {
        Path root=temporary.newFolder().toPath(),file=root.resolve("main.js");Files.write(file,new byte[]{1});var modified=Files.getLastModifiedTime(file);ProjectReadRaceFileSystem fs=new ProjectReadRaceFileSystem();AtomicBoolean changed=new AtomicBoolean();fs.afterRead=path->{if(path.equals(file)&&changed.compareAndSet(false,true)){Files.move(file,root.resolve("original"));Files.write(file,new byte[]{1});Files.setLastModifiedTime(file,modified);}};
        assertThrows(java.io.IOException.class,()->export(fs.root(root)));assertEquals(fs.opens,fs.closes);
    }
    @Test public void legacyExportDoesNotOpenAFileWhoseKnownSizeExceedsRemainingBudget()throws Exception {
        Path root=temporary.newFolder().toPath();for(int i=0;i<8;i++)Files.write(root.resolve("a"+i+".bin"),new byte[ProjectSources.MAX_FILE_BYTES]);Path tail=root.resolve("z.bin");Files.write(tail,new byte[]{1});ProjectReadRaceFileSystem fs=new ProjectReadRaceFileSystem();
        assertThrows(java.io.IOException.class,()->export(fs.root(root)));assertEquals(0L,fs.bytesRead(tail));assertEquals(8,fs.opens);assertEquals(fs.opens,fs.closes);
    }
    @Test public void legacyExportDoesNotFollowAResourceSwappedToSymlinkAtOpen()throws Exception {
        Path root=temporary.newFolder().toPath(),file=root.resolve("main.js"),outside=temporary.newFile().toPath();Files.write(file,new byte[]{1});Files.write(outside,new byte[]{9});ProjectReadRaceFileSystem fs=new ProjectReadRaceFileSystem();
        fs.beforeOpen=path->{if(path.equals(file)){Files.delete(file);Files.createSymbolicLink(file,outside);}};
        assertThrows(java.io.IOException.class,()->export(fs.root(root)));assertTrue(fs.noFollow);assertEquals(0L,fs.bytesRead(file));assertEquals(fs.opens,fs.closes);
    }
}
