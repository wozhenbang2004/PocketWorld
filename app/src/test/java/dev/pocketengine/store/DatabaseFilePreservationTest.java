package dev.pocketengine.store;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class DatabaseFilePreservationTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private File database()throws Exception{File directory=temporary.newFolder();File file=new File(directory,"pocket-world.db");Files.write(file.toPath(),new byte[]{1,2,3,4,5,6});return file;}
    @Test public void closedCohortPreservesExactMainWalShmJournalAndMasterBytes()throws Exception {
        File original=database();for(String suffix:new String[]{"-wal","-shm","-journal","-mj123"})Files.writeString(Path.of(original+suffix),"original "+suffix);
        var before=DatabaseFilePreservation.inspect(original);File output=new File(temporary.getRoot(),"preserved");var after=DatabaseFilePreservation.preserve(original,output,before,()->true);
        assertEquals(before.fingerprint(),after.fingerprint());assertEquals(before.fingerprint(),DatabaseFilePreservation.inspect(original).fingerprint());assertEquals(5,before.members().size());
        for(var member:before.members()){assertTrue(member.exists());assertArrayEquals(Files.readAllBytes(new File(original.getParentFile(),member.name()).toPath()),Files.readAllBytes(new File(output,member.name()).toPath()));}
    }
    @Test public void absentAuxiliariesAreExplicitAndManifestRoundTrips()throws Exception {
        var before=DatabaseFilePreservation.inspect(database());assertEquals(4,before.members().size());assertEquals(3,before.members().stream().filter(member->!member.exists()).count());assertEquals(before,DatabaseFilePreservation.fromManifest(before.databaseName(),before.files(),before.fingerprint()));
    }
    @Test public void sourceChangeDuringCopyNeverReturnsVerifiedOrDeletesOriginal()throws Exception {
        File original=database();File wal=new File(original+"-wal");Files.writeString(wal.toPath(),"before");var before=DatabaseFilePreservation.inspect(original);AtomicInteger checks=new AtomicInteger();
        try{DatabaseFilePreservation.preserve(original,new File(temporary.getRoot(),"partial"),before,()->{if(checks.incrementAndGet()==3)try{Files.writeString(wal.toPath(),"changed original");}catch(Exception e){throw new AssertionError(e);}return true;});fail();}catch(java.io.IOException expected){}
        assertTrue(original.exists());assertEquals("changed original",Files.readString(wal.toPath()));
    }
    @Test public void ownerDepartureLeavesOriginalBytesAndUnverifiedPartialCopy()throws Exception {
        File original=database();var before=DatabaseFilePreservation.inspect(original);AtomicInteger checks=new AtomicInteger();
        try{DatabaseFilePreservation.preserve(original,new File(temporary.getRoot(),"partial"),before,()->checks.incrementAndGet()<3);fail();}catch(SecurityException expected){}
        assertEquals(before.fingerprint(),DatabaseFilePreservation.inspect(original).fingerprint());
    }
    @Test public void existingDestinationIsNeverOverwritten()throws Exception {
        File original=database();File target=temporary.newFolder();File retained=new File(target,"already-retained");Files.writeString(retained.toPath(),"keep");
        try{DatabaseFilePreservation.preserve(original,target,DatabaseFilePreservation.inspect(original),()->true);fail();}catch(java.io.IOException expected){}
        assertEquals("keep",Files.readString(retained.toPath()));
    }
    @Test public void corruptedManifestCannotClaimAnUnchangedFingerprint()throws Exception {
        var original=DatabaseFilePreservation.inspect(database());JSONArray files=original.files();JSONObject member=files.getJSONObject(0);member.put("length",member.getLong("length")+1);
        rejects(files,original.fingerprint());files=original.files();files.put(new JSONObject(files.getJSONObject(0).toString()));rejects(files,original.fingerprint());
        files=original.files();files.getJSONObject(0).put("length",-1);rejects(files,original.fingerprint());files=original.files();files.getJSONObject(0).put("sha256","not-a-hash");rejects(files,original.fingerprint());
    }
    @Test public void missingAuxiliaryExistenceRecordIsNotAValidManifest()throws Exception {
        var original=DatabaseFilePreservation.inspect(database());JSONArray files=original.files();files.remove(files.length()-1);rejects(files,original.fingerprint());
    }
    @Test public void symbolicLinkSourceIsNeverFollowedOrCopied()throws Exception {
        File original=database();Path source=original.toPath();Path outside=temporary.newFile().toPath();Files.writeString(outside,"outside");Files.delete(source);Files.createSymbolicLink(source,outside);
        try{DatabaseFilePreservation.inspect(original);fail();}catch(java.io.IOException expected){}assertEquals("outside",Files.readString(outside));
    }
    private static void rejects(JSONArray files,String fingerprint)throws Exception{try{DatabaseFilePreservation.fromManifest("pocket-world.db",files,fingerprint);fail();}catch(SecurityException expected){}}
}
