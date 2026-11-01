package dev.pocketengine.features;

import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.media.AuthenticatedLiveKitProvider;
import dev.pocketengine.media.RealtimeCallProvider;
import dev.pocketengine.media.RealtimeEndpointProfile;
import dev.pocketengine.media.RealtimeSessionModule;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual Application factory installation; no accept, issuer request, media or real secret. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
public final class RealtimeEndpointProviderHostTest {
    @Test public void actualApplicationInstallsConcreteFactoryWithoutConfiguringOrContactingAService()throws Exception{
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();
        RealtimeCallProvider provider=null;
        try{
            RealtimeSessionModule module=(RealtimeSessionModule)app.features.module("realtime");
            Field endpoint=RealtimeSessionModule.class.getDeclaredField("endpointFactory");endpoint.setAccessible(true);
            RealtimeCallProvider.EndpointFactory factory=(RealtimeCallProvider.EndpointFactory)endpoint.get(module);
            assertNotNull("the real Application must install the configured-endpoint adapter",factory);
            Field committer=RealtimeSessionModule.class.getDeclaredField("turnCommitter");committer.setAccessible(true);
            assertNotNull("provider wiring must retain the original reviewed-task callback",committer.get(module));
            JSONObject snapshot=module.snapshot();assertTrue(snapshot.getJSONObject("capabilities").getBoolean("endpointProviderSupported"));
            assertFalse(snapshot.getJSONObject("capabilities").getBoolean("tokenEndpointConfigured"));
            assertFalse(snapshot.getJSONObject("capabilities").getBoolean("incomingInvitations"));
            assertEquals("idle",snapshot.getJSONObject("session").getString("state"));
            AtomicInteger reads=new AtomicInteger();
            provider=factory.create(new RealtimeEndpointProfile("https://issuer.invalid/token","wss://room.invalid","fixture-agent",1),()->{reads.incrementAndGet();throw new AssertionError("configuration must not read a credential");});
            assertTrue(provider instanceof AuthenticatedLiveKitProvider);assertFalse(provider.supportsIncomingInvitations());
            provider.reject(UUID.randomUUID().toString()).get(1,TimeUnit.SECONDS);
            assertEquals(0,reads.get());assertEquals(0,app.tasks.list().length());assertNull(app.activeId());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }finally{
            if(provider!=null)provider.close();app.features.close();
            Field field=EngineApplication.class.getDeclaredField("capabilityWorkers");field.setAccessible(true);ExecutorService workers=(ExecutorService)field.get(app);workers.shutdownNow();workers.awaitTermination(3,TimeUnit.SECONDS);
            Field database=TaskStore.class.getDeclaredField("db");database.setAccessible(true);((SQLiteDatabase)database.get(app.tasks)).close();
        }
    }
}
