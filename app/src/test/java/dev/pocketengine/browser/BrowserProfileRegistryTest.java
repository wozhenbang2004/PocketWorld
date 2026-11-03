package dev.pocketengine.browser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Metadata/fence tests; the capability flag here is a fixture, not a running WebView provider. */
public final class BrowserProfileRegistryTest {
    private File file()throws Exception{return new File(Files.createTempDirectory("browser-profiles-").toFile(),"profiles.json");}
    private BrowserProfileRegistry.Session managed(BrowserProfileRegistry registry)throws Exception {String id=registry.create("独立资料",registry.revision(),true).getString("id");registry.select(id,registry.revision(),true);return registry.openSession(true);}
    private JSONObject record(BrowserProfileRegistry registry,String id)throws Exception {JSONArray profiles=registry.snapshot(true).getJSONArray("profiles");for(int i=0;i<profiles.length();i++)if(profiles.getJSONObject(i).getString("id").equals(id))return profiles.getJSONObject(i);throw new AssertionError(id);}
    @Test public void namedProfilesHaveIndependentStableProviderIdentities()throws Exception {
        File file=file();BrowserProfileRegistry registry=new BrowserProfileRegistry(file);BrowserProfileRegistry.Session first=registry.openSession(true);
        String work=registry.create("工作",registry.revision(),true).getString("id");registry.select(work,registry.revision(),true);BrowserProfileRegistry.Session second=registry.openSession(true);
        assertNotEquals(first.providerName,second.providerName);assertFalse(first.current());assertTrue(second.current());assertTrue(second.multiProfile);
        BrowserProfileRegistry restored=new BrowserProfileRegistry(file);assertEquals(work,restored.selectedId());BrowserProfileRegistry.Session resumed=restored.openSession(true);assertEquals(second.providerName,resumed.providerName);assertNotEquals(second.sessionId,resumed.sessionId);
        assertFalse(restored.snapshot(true).toString().contains(second.providerName));
    }
    @Test public void staleNativeRevisionCannotReplaceOrDeleteCurrentSelection()throws Exception {
        BrowserProfileRegistry registry=new BrowserProfileRegistry(file());BrowserProfileRegistry.Session original=registry.openSession(true);long stale=registry.revision();
        String work=registry.create("工作",stale,true).getString("id");
        assertThrows(IllegalStateException.class,()->registry.select(work,stale,true));assertThrows(IllegalStateException.class,()->registry.requestDelete(original.profileId,stale,true));assertTrue(original.current());assertEquals(original.profileId,registry.selectedId());
    }
    @Test public void validPreflightDoesNotWriteOrRotateTheOriginalSession()throws Exception {
        File file=file();BrowserProfileRegistry registry=new BrowserProfileRegistry(file);BrowserProfileRegistry.Session original=managed(registry);long revision=registry.revision();byte[] before=Files.readAllBytes(file.toPath());AtomicInteger closed=new AtomicInteger();original.watch(closed::incrementAndGet);
        registry.checkSelect(original.profileId,revision,true);registry.checkDelete(original.profileId,revision,true);
        assertArrayEquals(before,Files.readAllBytes(file.toPath()));assertEquals(revision,registry.revision());assertEquals(original.profileId,registry.selectedId());assertTrue(original.current());assertEquals(0,closed.get());
        registry.select(original.profileId,revision,true);assertEquals(revision+1,registry.revision());assertFalse(original.current());assertEquals(1,closed.get());
    }
    @Test public void aPreflightNeverReplacesTheCommitRevisionOrAvailabilityChecks()throws Exception {
        BrowserProfileRegistry registry=new BrowserProfileRegistry(file());BrowserProfileRegistry.Session original=managed(registry);long revision=registry.revision();
        registry.checkSelect(original.profileId,revision,true);registry.checkDelete(original.profileId,revision,true);registry.create("later native change",revision,true);
        assertThrows(IllegalStateException.class,()->registry.select(original.profileId,revision,true));assertThrows(IllegalStateException.class,()->registry.requestDelete(original.profileId,revision,true));assertTrue(original.current());
        registry.checkDelete(original.profileId,registry.revision(),true);registry.requestDelete(original.profileId,registry.revision(),true);
        assertThrows(SecurityException.class,()->registry.select(original.profileId,registry.revision(),true));assertThrows(IllegalStateException.class,()->registry.requestDelete(original.profileId,registry.revision(),true));
    }
    @Test public void switchingAwayAndBackNeverRevivesAnOldViewOrItsOpenResources()throws Exception {
        BrowserProfileRegistry registry=new BrowserProfileRegistry(file());BrowserProfileRegistry.Session first=registry.openSession(true);AtomicInteger closed=new AtomicInteger();first.watch(closed::incrementAndGet);
        String work=registry.create("工作",registry.revision(),true).getString("id");registry.select(work,registry.revision(),true);registry.openSession(true);registry.select(first.profileId,registry.revision(),true);BrowserProfileRegistry.Session replacement=registry.openSession(true);
        assertFalse(first.current());assertEquals(1,closed.get());assertNotEquals(first.sessionId,replacement.sessionId);assertEquals(first.providerName,replacement.providerName);
        first.watch(closed::incrementAndGet);assertEquals(2,closed.get());
    }
    @Test public void detachedResourceIsNotClosedByALaterSwitch()throws Exception {
        BrowserProfileRegistry registry=new BrowserProfileRegistry(file());BrowserProfileRegistry.Session session=registry.openSession(true);AtomicInteger closed=new AtomicInteger();AutoCloseable registration=session.watch(closed::incrementAndGet);registration.close();registry.endSession(session);assertEquals(0,closed.get());assertFalse(session.current());
    }
    @Test public void durableTombstoneCannotBeSelectedOrRecreatedUnderItsOldIdentity()throws Exception {
        BrowserProfileRegistry registry=new BrowserProfileRegistry(file());String id=registry.create("工作",registry.revision(),true).getString("id");registry.select(id,registry.revision(),true);BrowserProfileRegistry.Session old=registry.openSession(true);
        registry.requestDelete(id,registry.revision(),true);assertFalse(old.current());assertEquals("",registry.selectedId());assertEquals("delete_pending",record(registry,id).getString("status"));assertThrows(SecurityException.class,()->registry.select(id,registry.revision(),true));
        String replacement=registry.create("工作",registry.revision(),true).getString("id");registry.select(replacement,registry.revision(),true);assertNotEquals(id,replacement);assertNotEquals(old.providerName,registry.openSession(true).providerName);
        registry.deletionResult(id,"awaiting_process_restart",false);assertEquals(1,registry.pendingDeletes().length());registry.deletionResult(id,"provider_deletion_accepted_async",true);assertEquals("disabled",record(registry,id).getString("status"));assertEquals(0,registry.pendingDeletes().length());
        assertFalse(registry.snapshot(true).toString().contains("physicalDeletionComplete"));
    }
    @Test public void deletionSurvivesRegistryRecreationBeforeAProviderIsLoaded()throws Exception {
        File file=file();BrowserProfileRegistry registry=new BrowserProfileRegistry(file);BrowserProfileRegistry.Session session=managed(registry);registry.requestDelete(session.profileId,registry.revision(),true);
        BrowserProfileRegistry cold=new BrowserProfileRegistry(file);assertEquals(1,cold.pendingDeletes().length());assertEquals(session.providerName,cold.pendingDeletes().getJSONObject(0).getString("providerName"));assertThrows(IllegalArgumentException.class,()->cold.openSession(true));
        cold.deletionResult(session.profileId,"provider_profile_absent",true);BrowserProfileRegistry restored=new BrowserProfileRegistry(file);assertEquals("disabled",record(restored,session.profileId).getString("status"));assertThrows(SecurityException.class,()->restored.select(session.profileId,restored.revision(),true));
    }
    @Test public void unsupportedProviderCannotInventMoreProfilesOrClaimIsolation()throws Exception {
        BrowserProfileRegistry registry=new BrowserProfileRegistry(file());assertThrows(UnsupportedOperationException.class,()->registry.create("工作",registry.revision(),false));BrowserProfileRegistry.Session fallback=registry.openSession(false);
        assertFalse(fallback.multiProfile);assertEquals("default-webview-only",fallback.isolation);assertThrows(UnsupportedOperationException.class,()->registry.requestDelete(fallback.profileId,registry.revision(),false));
        registry.endSession(fallback);assertFalse(registry.openSession(true).multiProfile);assertEquals("legacy-default",record(registry,fallback.profileId).getString("storageKind"));
    }
    @Test public void providerDowngradeCannotSilentlySubstituteTheDefaultCookieJar()throws Exception {
        File file=file();BrowserProfileRegistry registry=new BrowserProfileRegistry(file);managed(registry);BrowserProfileRegistry restored=new BrowserProfileRegistry(file);
        assertThrows(UnsupportedOperationException.class,()->restored.openSession(false));assertFalse(record(restored,restored.selectedId()).getString("storageKind").equals("legacy-default"));
        JSONArray profiles=restored.snapshot(false).getJSONArray("profiles");for(int i=0;i<profiles.length();i++)if(profiles.getJSONObject(i).getString("id").equals(restored.selectedId()))assertFalse(profiles.getJSONObject(i).getBoolean("available"));
    }
    @Test public void failedTombstonePersistenceDoesNotReportSuccessfulDeletion()throws Exception {
        File file=file();BrowserProfileRegistry registry=new BrowserProfileRegistry(file);BrowserProfileRegistry.Session original=managed(registry);long revision=registry.revision();Files.delete(file.toPath());Files.createDirectory(file.toPath());Files.writeString(new File(file,"blocker").toPath(),"x");
        assertThrows(Exception.class,()->registry.requestDelete(original.profileId,revision,true));assertFalse(original.current());assertEquals(revision,registry.revision());assertEquals("active",record(registry,original.profileId).getString("status"));
    }
    @Test public void corruptMetadataIsNotSilentlyResetToAUsableAccount()throws Exception {
        File file=file();Files.writeString(file.toPath(),"{\"version\":2}",StandardCharsets.UTF_8);assertThrows(IllegalStateException.class,()->new BrowserProfileRegistry(file));assertEquals("{\"version\":2}",Files.readString(file.toPath()));
    }
}
