package dev.pocketengine.features.devices;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.store.TaskStoreDeviceTrust;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.Key;
import java.security.KeyStoreSpi;
import java.security.Provider;
import java.security.Security;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import static org.junit.Assert.*;

/** Actual production assembler + original SQLite. The empty AndroidKeyStore is an explicit
 * READ-ONLY platform fixture: any key/certificate write fails. No TLS/device/model/service runs. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, manifest=Config.NONE, application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class DeviceWorkspaceNativeOwnerReadTest {
    private EngineApplication attachedApp;
    public static final class EmptyReadOnlyKeyStore extends KeyStoreSpi {
        static final AtomicInteger reads = new AtomicInteger(), writes = new AtomicInteger();
        public Key engineGetKey(String alias,char[] password){return null;}
        public Certificate[] engineGetCertificateChain(String alias){return null;}
        public Certificate engineGetCertificate(String alias){return null;}
        public Date engineGetCreationDate(String alias){return null;}
        public void engineSetKeyEntry(String alias,Key key,char[] password,Certificate[] chain){writes.incrementAndGet();throw new AssertionError("No key writes in a read fixture");}
        public void engineSetKeyEntry(String alias,byte[] key,Certificate[] chain){writes.incrementAndGet();throw new AssertionError("No key writes in a read fixture");}
        public void engineSetCertificateEntry(String alias,Certificate cert){writes.incrementAndGet();throw new AssertionError("No certificate writes in a read fixture");}
        public void engineDeleteEntry(String alias){writes.incrementAndGet();throw new AssertionError("No key deletion in a read fixture");}
        public Enumeration<String> engineAliases(){return Collections.emptyEnumeration();}
        public boolean engineContainsAlias(String alias){reads.incrementAndGet();return false;}
        public int engineSize(){return 0;}
        public boolean engineIsKeyEntry(String alias){return false;}
        public boolean engineIsCertificateEntry(String alias){return false;}
        public String engineGetCertificateAlias(Certificate certificate){return null;}
        public void engineStore(OutputStream output,char[] password){writes.incrementAndGet();throw new AssertionError("No keystore store in a read fixture");}
        public void engineLoad(InputStream input,char[] password){}
    }
    @Before public void emptyKeys() {
        EmptyReadOnlyKeyStore.reads.set(0); EmptyReadOnlyKeyStore.writes.set(0);
        Security.insertProviderAt(new Provider("DeviceReadOnlyPlatformFixture",1.0,"No keys exist and writes fail"){{put("KeyStore.AndroidKeyStore",EmptyReadOnlyKeyStore.class.getName());}},1);
    }
    @After public void close() throws Exception {
        Security.removeProvider("DeviceReadOnlyPlatformFixture"); if (attachedApp != null) {
            attachedApp.onTerminate(); var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((SQLiteDatabase)field.get(attachedApp.tasks)).close();
        }
    }
    @Test public void absentReceiverNeverCommitsNewSubmission() throws Exception {
        AtomicInteger accepted = new AtomicInteger();
        try { DeviceWorkspaceNativeOwner.receivingGate(null).accept(() -> { accepted.incrementAndGet(); return new JSONObject(); }); fail("Missing receiver used permissive SQL acceptance"); }
        catch (SecurityException expected) { assertEquals(0, accepted.get()); }
    }
    @Test public void ordinaryLocalStopDoesNotReadKeysOrCreateDeviceWork() throws Exception {
        var runtime=RuntimeEnvironment.getApplication();var app=new EngineApplication();attachedApp=app;
        var attach=Application.class.getDeclaredMethod("attach",Context.class);attach.setAccessible(true);attach.invoke(app,runtime);
        app.tasks=new TaskStore(runtime);var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);var original=(SQLiteDatabase)field.get(app.tasks);
        TaskStoreDeviceTrust.createSchema(original);dev.pocketengine.store.TaskStoreDeviceTasks.createSchema(original);
        JSONObject task=app.tasks.create("Explicit native-fixture original owner parent with no device child");
        var owner=new DeviceWorkspaceNativeOwner(app,new TaskStoreDeviceTrust(original),work->{throw new AssertionError("Ordinary stop cannot dispatch a device request");});
        var originalCancelled=new dev.pocketengine.core.RunControl();originalCancelled.cancel();
        try{JSONObject result=owner.stopOriginalChildren(task.getString("id"),originalCancelled);assertTrue(result.getBoolean("noDeviceChildren"));assertEquals(0,EmptyReadOnlyKeyStore.reads.get());assertEquals(0,EmptyReadOnlyKeyStore.writes.get());assertNull(owner.existingArtifacts());
            for(String table:new String[]{"runs","operations","device_peers","device_grants"})try(var rows=original.rawQuery("SELECT COUNT(*) FROM "+table,null)){assertTrue(rows.moveToFirst());assertEquals(table,0,rows.getLong(0));}
        }finally{owner.close();}
    }
    @Test public void realFactoryReadCloseReopenDoesNotCreateIdentityListenOrTask() throws Exception {
        var runtime=RuntimeEnvironment.getApplication(); var app=new EngineApplication(); attachedApp=app;
        // Attach the actual final App class without running unrelated application startup services.
        // This is a bounded assembler fixture, not a claim that full App/Service startup was tested.
        var attach=Application.class.getDeclaredMethod("attach",Context.class);attach.setAccessible(true);attach.invoke(app,runtime);
        app.tasks=new TaskStore(runtime);var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);var original=(SQLiteDatabase)field.get(app.tasks);
        // This narrow host classpath uses the phone domain's frozen original-store classes.
        // Install its exact schema adapters on that SAME database, as its ingress tests do.
        // Root's version-8 Database migration remains a separate integrated test gate.
        TaskStoreDeviceTrust.createSchema(original); dev.pocketengine.store.TaskStoreDeviceTasks.createSchema(original);
        AtomicInteger dispatched=new AtomicInteger();
        var owner=new DeviceWorkspaceNativeOwner(app,new TaskStoreDeviceTrust(original),work->{dispatched.incrementAndGet();throw new AssertionError("Read cannot dispatch ingress work");});
        try {
            assertNull(owner.existingArtifacts()); assertFalse(new java.io.File(app.getFilesDir(),"native-device-artifact-bytes-v1").exists());
            var page=owner.host().openOwnerWindow();JSONObject snapshot=page.snapshot();
            assertEquals(NativeDeviceWorkspaceHost.FORMAT,snapshot.getString("format"));assertFalse(snapshot.getJSONObject("local").has("deviceId"));
            assertFalse(snapshot.getJSONObject("local").getBoolean("receiverEnabled"));assertFalse(snapshot.getJSONObject("local").getBoolean("listenerReady"));
            assertEquals(0,snapshot.getJSONArray("peers").length());assertEquals(0,snapshot.getJSONArray("pendingPairings").length());
            assertFalse(snapshot.getJSONObject("actions").getJSONObject("invite").getBoolean("available"));
            try{page.createInvitation("192.0.2.10");fail("Unenabled receiver created an invitation");}catch(IllegalStateException expected){}
            page.close();var reopened=owner.host().openOwnerWindow();assertEquals(0,reopened.snapshot().getJSONArray("peers").length());reopened.close();
            assertTrue(EmptyReadOnlyKeyStore.reads.get()>0);assertEquals(0,EmptyReadOnlyKeyStore.writes.get());assertNull(org.robolectric.Shadows.shadowOf(runtime).getNextStartedService());assertEquals(0,dispatched.get());assertNull(owner.existingArtifacts());assertFalse(new java.io.File(app.getFilesDir(),"native-device-artifact-bytes-v1").exists());
            for(String table:new String[]{"tasks","runs","operations","device_peers","device_grants"})try(var rows=original.rawQuery("SELECT COUNT(*) FROM "+table,null)){assertTrue(rows.moveToFirst());assertEquals(table,0,rows.getLong(0));}
            owner.close();try{owner.host().openOwnerWindow();fail("Closed factory reopened a mutable owner window");}catch(IllegalStateException expected){}assertNull(owner.existingArtifacts());
        } finally { owner.close(); org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    }
}
