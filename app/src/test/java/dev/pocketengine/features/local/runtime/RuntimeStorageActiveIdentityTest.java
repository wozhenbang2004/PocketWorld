package dev.pocketengine.features.local.runtime;

import static org.junit.Assert.*;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.project.ProjectSources;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import org.junit.Test;

/** Actual owned files/seals and ORIGINAL RuntimeStorage review/maintenance guards.
 * Tool bytes are explicitly synthetic metadata fixtures, never execve/probe or
 * Android/ARM64 acceptance. No unknown/user directory is cleaned by these cases. */
public final class RuntimeStorageActiveIdentityTest {
    private static final class Fixture implements AutoCloseable {
        final Path temporary=Files.createTempDirectory("own-runtime-active-storage-"),outer=temporary.resolve("runtime"),nativeLib=Files.createDirectory(temporary.resolve("native"));
        final AndroidRuntimeCatalog catalog;final Path managed,android,pointer,generations;final RuntimeLinkStorage links;
        final Map<String,Path> backing=new HashMap<>();final String previous,current;
        ManagedProjectRuntime runtime;RuntimeStorage storage;
        Fixture()throws Exception {
            Path proot=Files.write(nativeLib.resolve("libproot.so"),"synthetic non-executable engine fixture".getBytes(StandardCharsets.UTF_8)),loader=Files.write(nativeLib.resolve("libloader.so"),"synthetic non-executable loader fixture".getBytes(StandardCharsets.UTF_8));
            JSONObject descriptor=AndroidRuntimePreparationTest.catalog();descriptor.getJSONObject("engine").put("prootSha256",RuntimeFiles.sha256(proot.toFile())).put("loaderSha256",RuntimeFiles.sha256(loader.toFile()));catalog=new AndroidRuntimeCatalog(descriptor);
            runtime=ManagedProjectRuntime.createAndroid(outer.toFile(),nativeLib.toFile(),catalog);managed=outer.resolve("managed-process");android=managed.resolve("android-runtime");pointer=android.resolve("active.json");generations=runtime.environmentManager().generationStorage().toPath();links=new RuntimeLinkStorage(android.toFile());previous=generation("previous");current=generation("current");activate(current);reopen();
        }
        String generation(String version)throws Exception {
            JSONObject binding=links.allocate(catalog.identity);Path data=links.require(binding,catalog.identity).toPath();JSONArray tools=new JSONArray();
            for(String name:List.of("sh","node","npm","python3","git")){Path file=Files.write(data.resolve(".l2s."+name+"0001.0001"),("not a real tool: "+version+" "+name).getBytes(StandardCharsets.UTF_8));tools.put(new JSONObject().put("name",name).put("path","/usr/bin/"+name).put("sha256",RuntimeFiles.sha256(file.toFile())).put("bytes",Files.size(file)).put("modifiedAt",file.toFile().lastModified()));}
            JSONObject seal=new JSONObject().put("schema","pocketworld.runtime-generation.v1").put("catalogIdentity",catalog.identity).put("catalog",catalog.descriptor()).put("linkStorage",binding).put("tools",tools);String id=ProjectSources.digest(seal);seal.put("generationIdentity",id);Path generation=Files.createDirectory(generations.resolve(id)),bin=Files.createDirectories(generation.resolve("rootfs/usr/bin"));
            for(int i=0;i<tools.length();i++){String name=tools.getJSONObject(i).getString("name");Files.createSymbolicLink(bin.resolve(name),data.resolve(".l2s."+name+"0001.0001"));}RuntimeFiles.write(generation.resolve("generation.json").toFile(),seal);backing.put(id,data);return id;
        }
        void activate(String id)throws Exception{RuntimeFiles.write(pointer.toFile(),new JSONObject().put("generationIdentity",id).put("catalogIdentity",catalog.identity));}
        void reopen()throws Exception{runtime.close();runtime=ManagedProjectRuntime.createAndroid(outer.toFile(),nativeLib.toFile(),catalog);storage=runtime.ownerStorageForHost();}
        JSONObject row(String id)throws Exception{JSONArray rows=storage.inventory(new RunControl());for(int i=0;i<rows.length();i++)if(id.equals(rows.getJSONObject(i).optString("id")))return rows.getJSONObject(i);throw new AssertionError("Original storage row missing: "+id);}
        void retained()throws Exception{for(String id:List.of(previous,current)){assertTrue(Files.isRegularFile(generations.resolve(id+"/generation.json")));assertTrue(Files.isRegularFile(backing.get(id).resolve(".l2s.sh0001.0001")));assertTrue(new String(Files.readAllBytes(backing.get(id).resolve(".l2s.sh0001.0001")),StandardCharsets.UTF_8).startsWith("not a real tool:"));}}
        void protectedPairs()throws Exception{for(String id:List.of(previous,current))for(String kind:List.of("generation","links")){JSONObject row=row("runtime:"+kind+":"+id);assertFalse(row.toString(),row.getBoolean("cleanable"));assertTrue(row.getString("label").contains("当前归属待核"));assertTrue(row.getString("note").contains("发布指针"));}retained();}
        public void close()throws Exception{runtime.close();try(var paths=Files.walk(temporary)){for(Path path:paths.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList()))Files.deleteIfExists(path);}}
    }
    @Test public void knownCurrentIdentityKeepsOnlyTheIdleOldPairEligibleAndPreservesOtherPolicies()throws Exception {
        try(var f=new Fixture()){
            assertFalse(f.row("runtime:generation:"+f.current).getBoolean("cleanable"));assertFalse(f.row("runtime:links:"+f.current).getBoolean("cleanable"));assertTrue(f.row("runtime:generation:"+f.previous).getBoolean("cleanable"));assertTrue(f.row("runtime:links:"+f.previous).getBoolean("cleanable"));assertTrue(f.row("runtime:downloads").getBoolean("cleanable"));assertTrue(f.row("runtime:temporary").getBoolean("cleanable"));assertFalse(f.row("runtime:home").getBoolean("cleanable"));
            RuntimeStorage.Entry entry=f.storage.review("runtime:generation:"+f.previous,new RunControl());f.storage.requireCurrent(entry,new RunControl());f.retained();
        }
    }
    @Test public void healthyOldGenerationAndBackingStillDeleteInEitherOriginalPairedOrder()throws Exception {
        for(boolean backingFirst:List.of(false,true))try(var f=new Fixture()){
            RunControl original=new RunControl();RuntimeStorage.Entry generation=f.storage.review("runtime:generation:"+f.previous,original),backing=f.storage.review("runtime:links:"+f.previous,original);
            try(var maintenance=f.storage.beginMaintenance()){
                f.storage.requireCurrent(generation,original);f.storage.requireCurrent(backing,original);
                for(RuntimeStorage.Entry entry:backingFirst?List.of(backing,generation):List.of(generation,backing))assertTrue(f.storage.clean(entry,maintenance,original).getBoolean("targetAbsent"));
            }
            assertFalse(Files.exists(f.generations.resolve(f.previous)));assertFalse(Files.exists(f.backing.get(f.previous)));assertTrue(Files.isRegularFile(f.generations.resolve(f.current+"/generation.json")));assertTrue(Files.isRegularFile(f.backing.get(f.current).resolve(".l2s.sh0001.0001")));assertEquals(f.current,f.runtime.identity());assertEquals(f.current,new JSONObject(new String(Files.readAllBytes(f.pointer),StandardCharsets.UTF_8)).getString("generationIdentity"));
        }
    }
    @Test public void coldDamagedOrUnknownPointerProtectsEveryValidPairWithoutChangingThePointerBytes()throws Exception {
        for(byte[] damaged:List.of("{damaged-original-active".getBytes(StandardCharsets.UTF_8),new JSONObject().put("generationIdentity","e".repeat(64)).toString().getBytes(StandardCharsets.UTF_8))){
            try(var f=new Fixture()){Files.write(f.pointer,damaged);f.reopen();f.protectedPairs();assertArrayEquals(damaged,Files.readAllBytes(f.pointer));assertThrows(Exception.class,()->f.storage.review("runtime:generation:"+f.previous,new RunControl()));}
        }
    }
    @Test public void coldMissingPointerDoesNotTurnTheLastRecoverableVersionsIntoOrdinaryCache()throws Exception {
        try(var f=new Fixture()){Files.delete(f.pointer);f.reopen();f.protectedPairs();assertFalse(Files.exists(f.pointer));assertTrue(f.row("runtime:downloads").getBoolean("cleanable"));assertTrue(f.row("runtime:temporary").getBoolean("cleanable"));assertFalse(f.row("runtime:home").getBoolean("cleanable"));}
    }
    @Test public void aPointerSymlinkCannotAuthorizeVersionDeletionEvenIfItsTargetContainsAValidSealReference()throws Exception {
        try(var f=new Fixture()){Path ownTarget=Files.write(f.temporary.resolve("own-pointer-reference"),Files.readAllBytes(f.pointer));Files.delete(f.pointer);Files.createSymbolicLink(f.pointer,ownTarget);f.reopen();f.protectedPairs();assertTrue(Files.isSymbolicLink(f.pointer));assertTrue(Files.isRegularFile(ownTarget));}
    }
    @Test public void oldReviewCannotRemainCurrentWhenThePointerChangesAwayFromTheOriginalLiveIdentity()throws Exception {
        try(var f=new Fixture()){RunControl original=new RunControl();RuntimeStorage.Entry entry=f.storage.review("runtime:generation:"+f.previous,original);String identity=f.runtime.identity();f.activate(f.previous);assertEquals(identity,f.runtime.identity());assertThrows(Exception.class,()->f.storage.requireCurrent(entry,original));f.protectedPairs();f.retained();}
    }
    @Test public void physicalDeleteRechecksCurrentnessAfterTheEarlierAllTargetPreflight()throws Exception {
        try(var f=new Fixture()){RunControl original=new RunControl();RuntimeStorage.Entry entry=f.storage.review("runtime:generation:"+f.previous,original);try(var maintenance=f.storage.beginMaintenance()){
            f.storage.requireCurrent(entry,original);Files.write(f.pointer,"{damaged-after-preflight".getBytes(StandardCharsets.UTF_8));Exception refused=null;JSONObject returned=null;
            try{returned=f.storage.clean(entry,maintenance,original);}catch(Exception expected){refused=expected;}
            assertNotNull("Stale deletion must be refused; actual="+returned+"; selectedGenerationPresent="+Files.exists(f.generations.resolve(f.previous)),refused);assertNull(returned);f.retained();
        }}
    }
}
