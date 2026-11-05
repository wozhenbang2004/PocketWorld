package dev.pocketengine.features.workspace;

import android.app.Application;
import java.nio.file.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Framework directory aliases are trusted parents; archive leaves remain untrusted. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class OwnerArchivePrivateRootTest {
    @Before public void installVault(){OwnerDataArchiveFixture.installFixtureVault();}
    @After public void releaseVault(){OwnerDataArchiveFixture.removeFixtureVault();}
    @Test public void ordinaryPrivateArchiveDirectoryIsAvailable()throws Exception {
        Path install=Files.createTempDirectory("archive-ordinary-");
        try(var fixture=new OwnerDataArchiveFixture(RuntimeEnvironment.getApplication(),install)){
            assertEquals(0,fixture.host.ownerDataArchives(fixture.android).localCachesForOwner(new dev.pocketengine.core.RunControl()).length());
        }
    }
    @Test public void trustedFrameworkParentAliasWorksAcrossReopen()throws Exception {
        Path base=Files.createTempDirectory("archive-framework-alias-");
        Path original=Files.createDirectory(base.resolve("original")),alias=base.resolve("framework-files");
        try(var fixture=new OwnerDataArchiveFixture(RuntimeEnvironment.getApplication(),original)){
            Files.createSymbolicLink(alias,fixture.android.getFilesDir().toPath());
            android.content.Context context=aliasedFiles(fixture.android,alias);
            assertEquals(0,fixture.host.ownerDataArchives(context).localCachesForOwner(new dev.pocketengine.core.RunControl()).length());
        }
        try(var fixture=new OwnerDataArchiveFixture(RuntimeEnvironment.getApplication(),original)){
            assertEquals(0,fixture.host.ownerDataArchives(aliasedFiles(fixture.android,alias)).localCachesForOwner(new dev.pocketengine.core.RunControl()).length());
            assertTrue(Files.isDirectory(original.resolve("files/owner-data-archives"),LinkOption.NOFOLLOW_LINKS));
        }
    }
    @Test public void ordinaryFileCannotOccupyArchiveDirectory()throws Exception {
        Path base=Files.createTempDirectory("archive-file-leaf-");
        try(var fixture=new OwnerDataArchiveFixture(RuntimeEnvironment.getApplication(),base)){
            Path leaf=fixture.android.getFilesDir().toPath().resolve("owner-data-archives");
            byte[] bytes={4,5,6};Files.write(leaf,bytes);
            assertThrows(SecurityException.class,()->fixture.host.ownerDataArchives(fixture.android));
            assertArrayEquals(bytes,Files.readAllBytes(leaf));
        }
    }
    @Test public void replacementAfterArchiveOpenIsStillRejected()throws Exception {
        Path base=Files.createTempDirectory("archive-replaced-leaf-"),outside=Files.createDirectory(base.resolve("outside"));
        Path sentinel=outside.resolve("sentinel.txt");Files.write(sentinel,new byte[]{9});
        try(var fixture=new OwnerDataArchiveFixture(RuntimeEnvironment.getApplication(),base.resolve("install"))){
            OwnerDataArchives archives=fixture.host.ownerDataArchives(fixture.android);
            Path leaf=fixture.android.getFilesDir().toPath().resolve("owner-data-archives");
            Files.move(leaf,leaf.resolveSibling("original-archives"));Files.createSymbolicLink(leaf,outside);
            assertThrows(SecurityException.class,()->archives.localCachesForOwner(new dev.pocketengine.core.RunControl()));
            assertArrayEquals(new byte[]{9},Files.readAllBytes(sentinel));
            try(var entries=Files.list(outside)){assertEquals(1,entries.count());}
        }
    }
    private static android.content.Context aliasedFiles(android.content.Context original,Path alias){
        return new android.content.ContextWrapper(original){
            @Override public android.content.Context getApplicationContext(){return this;}
            @Override public java.io.File getFilesDir(){return alias.toFile();}
        };
    }
    @Test public void substitutedArchiveLeafIsRejectedWithoutTouchingOutside()throws Exception {
        Path base=Files.createTempDirectory("archive-substituted-leaf-"),outside=Files.createDirectory(base.resolve("outside"));
        Path sentinel=outside.resolve("sentinel.txt");byte[] bytes={1,2,3};Files.write(sentinel,bytes);
        try(var fixture=new OwnerDataArchiveFixture(RuntimeEnvironment.getApplication(),base.resolve("install"))){
            Files.createSymbolicLink(fixture.android.getFilesDir().toPath().resolve("owner-data-archives"),outside);
            assertThrows(SecurityException.class,()->fixture.host.ownerDataArchives(fixture.android));
            assertArrayEquals(bytes,Files.readAllBytes(sentinel));
            try(var entries=Files.list(outside)){assertEquals(1,entries.count());}
        }
    }
}