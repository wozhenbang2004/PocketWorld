package dev.pocketengine.browser;

import java.io.File;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Normal pre-profile upgrade and exact private-metadata parsing failures. No cookie migration. */
public final class BrowserProfileUpgradeTest {
    private File file()throws Exception{return Files.createTempDirectory("browser-upgrade-").resolve("profiles.json").toFile();}
    @Test public void absentCatalogPreservesFrameworkDefaultOnSupportedProvider()throws Exception {
        File file=file();assertFalse(file.exists());BrowserProfileRegistry registry=new BrowserProfileRegistry(file);BrowserProfileRegistry.Session session=registry.openSession(true);
        assertEquals(BrowserLease.PROFILE,session.profileId);assertFalse(session.multiProfile);assertEquals("default-webview-only",session.isolation);assertEquals("legacy-default",registry.snapshot(true).getJSONArray("profiles").getJSONObject(0).getString("storageKind"));
        BrowserProfileRegistry restored=new BrowserProfileRegistry(file);assertFalse(restored.openSession(true).multiProfile);assertFalse(restored.openSession(false).multiProfile);
    }
    @Test public void namedProfileRequiresExplicitNativeCreationAndSelection()throws Exception {
        BrowserProfileRegistry registry=new BrowserProfileRegistry(file());BrowserProfileRegistry.Session existing=registry.openSession(true);String named=registry.create("独立工作资料",registry.revision(),true).getString("id");
        assertEquals(BrowserLease.PROFILE,registry.selectedId());assertTrue(existing.current());assertFalse(registry.openSession(true).multiProfile);
        registry.select(named,registry.revision(),true);BrowserProfileRegistry.Session selected=registry.openSession(true);assertTrue(selected.multiProfile);assertEquals(named,selected.profileId);
        registry.select(BrowserLease.PROFILE,registry.revision(),true);assertFalse(registry.openSession(true).multiProfile);
    }
    @Test public void earlierUnopenedUnboundDefaultDoesNotGuessAProviderBackend()throws Exception {
        File file=file();new BrowserProfileRegistry(file);JSONObject saved=new JSONObject(Files.readString(file.toPath()));saved.getJSONArray("profiles").getJSONObject(0).put("storageKind","unbound");Files.writeString(file.toPath(),saved.toString());
        BrowserProfileRegistry restored=new BrowserProfileRegistry(file);assertFalse(restored.openSession(true).multiProfile);assertEquals("legacy-default",new JSONObject(Files.readString(file.toPath())).getJSONArray("profiles").getJSONObject(0).getString("storageKind"));
    }
    @Test public void unknownStorageKindIsRejectedWithoutRewritingThePrivateFile()throws Exception {
        File file=file();new BrowserProfileRegistry(file);JSONObject saved=new JSONObject(Files.readString(file.toPath()));saved.getJSONArray("profiles").getJSONObject(0).put("storageKind","unknown-v1-kind");String original=saved.toString();Files.writeString(file.toPath(),original);
        assertThrows(IllegalStateException.class,()->new BrowserProfileRegistry(file));assertEquals(original,Files.readString(file.toPath()));
        saved.getJSONArray("profiles").getJSONObject(0).remove("storageKind");Files.writeString(file.toPath(),saved.toString());assertThrows(IllegalStateException.class,()->new BrowserProfileRegistry(file));
    }
    @Test public void alreadyBoundNamedProfileRetainsItsIdentityAndDowngradeRefusal()throws Exception {
        File file=file();BrowserProfileRegistry registry=new BrowserProfileRegistry(file);String id=registry.create("已经使用的资料",registry.revision(),true).getString("id");registry.select(id,registry.revision(),true);BrowserProfileRegistry.Session original=registry.openSession(true);
        BrowserProfileRegistry restored=new BrowserProfileRegistry(file);BrowserProfileRegistry.Session selected=restored.openSession(true);assertTrue(selected.multiProfile);assertEquals(original.providerName,selected.providerName);assertThrows(UnsupportedOperationException.class,()->restored.openSession(false));
    }
}
