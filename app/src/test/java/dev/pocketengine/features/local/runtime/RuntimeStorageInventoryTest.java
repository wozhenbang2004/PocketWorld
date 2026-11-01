package dev.pocketengine.features.local.runtime;

import static org.junit.Assert.*;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.project.NativeStorageTree;
import java.nio.file.*;
import java.io.File;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;

/** Real native storage directories/JSON; no Android process, assets download,
 * guessed generation validity or simulated zero-byte failure. */
public final class RuntimeStorageInventoryTest {
    @Test public void unknownAndCorruptGenerationsAndUnclaimedBackingsRemainCountedAndProtected()throws Exception {
        Path temporary=Files.createTempDirectory("c2-runtime-inventory-");Path root=Files.createDirectory(temporary.resolve("runtime"));RuntimeEnvironment environment=new RuntimeEnvironment(root.toFile(),identity->{});Path nativeLib=Files.createDirectory(temporary.resolve("native")),home=Files.createDirectory(temporary.resolve("home")),tmp=Files.createDirectory(temporary.resolve("tmp"));
        Path catalog=Path.of(Objects.requireNonNull(System.getProperty("c2.originalRuntimeCatalog"),"exact own APK catalog fixture path required"));AndroidRuntimePreparation original=new AndroidRuntimePreparation(root.toFile(),nativeLib.toFile(),new AndroidRuntimeCatalog(new JSONObject(Files.readString(catalog))),environment,home.toFile(),tmp.toFile());
        Path generations=environment.generationStorage().toPath();Files.createDirectory(generations.resolve("unrecognized-original"));Files.write(generations.resolve("unrecognized-original/retain.bin"),new byte[37]);Path corrupt=Files.createDirectories(generations.resolve("a".repeat(64)+"/rootfs"));Files.write(corrupt.resolve("retain-original.bin"),new byte[41]);Path backing=root.resolve("android-runtime/link-storage/unknown-backing");Files.createDirectory(backing);Files.write(backing.resolve("retain.bin"),new byte[43]);
        List<RuntimeStorage.Target> actual=original.ownerStorageTargets();RuntimeStorage.Target generation=actual.stream().filter(v->v.id.equals("runtime:generation-store-all")).findFirst().orElseThrow(),links=actual.stream().filter(v->v.id.equals("runtime:link-store-all")).findFirst().orElseThrow();assertFalse(generation.cleanable);assertFalse(links.cleanable);assertTrue(generation.note.contains("重叠"));assertEquals(78,NativeStorageTree.measure(generation.file,new RunControl()).getLong("logicalBytes"));assertEquals(43,NativeStorageTree.measure(links.file,new RunControl()).getLong("logicalBytes"));assertTrue(actual.stream().noneMatch(v->v.id.startsWith("runtime:generation:")&&v.cleanable));assertEquals(37,Files.size(generations.resolve("unrecognized-original/retain.bin")));assertEquals(41,Files.size(corrupt.resolve("retain-original.bin")));assertEquals(43,Files.size(backing.resolve("retain.bin")));
    }
}
