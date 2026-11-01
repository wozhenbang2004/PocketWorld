package dev.pocketengine.phone.display.adb;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import dev.pocketengine.store.SecretVault;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Controlled private-Binder lifecycle fixture; not actual pairing, SIGKILL or device evidence. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class AdbBootstrapLifecycleTest {
    private FakeContext context;
    private AdbBootstrap bootstrap;
    private final CopyOnWriteArrayList<Child> children=new CopyOnWriteArrayList<>();
    @Before public void prepare(){context=new FakeContext(RuntimeEnvironment.getApplication());bootstrap=new AdbBootstrap(context,new SecretVault(context),value->{});}
    @After public void close(){bootstrap.close();for(Child child:children)child.die();shadowOf(Looper.getMainLooper()).idle();}
    private Child child(){Child value=new Child();children.add(value);return value;}
    private JSONObject endpoint()throws Exception{return new JSONObject().put("host","127.0.0.1").put("pairingPort",37111).put("connectPort",37112);}
    private JSONObject configuration()throws Exception{return new JSONObject().put("ownerUid",Process.myUid()).put("socketName","pocketengine.display."+"a".repeat(32)).put("token","b".repeat(64));}
    private void deliver(Child child){shadowOf(Looper.getMainLooper()).idle();assertNotNull(context.connection);context.connection.onServiceConnected(new ComponentName(context,AdbBootstrapService.class),child);}
    private static void await(BooleanSupplier condition)throws Exception {long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!condition.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(5);assertTrue("asynchronous private-Binder state did not converge",condition.getAsBoolean());}
    @Test public void existingAuthenticatedHelperIsCheckedWithoutReplayingAdb()throws Exception {
        AtomicInteger checks=new AtomicInteger();bootstrap.connect(endpoint(),configuration(),checks::incrementAndGet);Child child=child();deliver(child);
        await(()->bootstrap.snapshot().optBoolean("helperAuthenticated"));assertEquals(1,checks.get());assertEquals(0,child.requests.get());
        assertFalse(bootstrap.snapshot().optBoolean("recoveryRequired"));assertFalse(bootstrap.snapshot().toString().contains("b".repeat(64)));
    }
    @Test public void unknownPairingRequiresConnectBeforeAnotherPair()throws Exception {
        context.getSharedPreferences("display_adb_bootstrap",0).edit().putString("pendingOperation","old-unknown-operation").putString("pendingAction","pair").commit();
        JSONObject input=endpoint().put("pairingCode","123456");assertThrows(IllegalStateException.class,()->bootstrap.pair(input));
        assertNull(context.connection);assertTrue(bootstrap.snapshot().optBoolean("recoveryRequired"));
        bootstrap.connect(endpoint(),configuration(),()->{});Child child=child();deliver(child);await(()->bootstrap.snapshot().optBoolean("helperAuthenticated"));
        assertEquals(0,child.requests.get());assertFalse(bootstrap.snapshot().optBoolean("recoveryRequired"));
    }
    @Test public void identityForgetWaitsForActualDeathEvidenceBeyondBindingLoss()throws Exception {
        String slot="display.adb.identity.v1";context.getSharedPreferences("feature_secrets",0).edit().putString(slot,"test-ciphertext-reference-only").commit();
        bootstrap.forgetIdentity();Child child=child();deliver(child);await(()->child.cancellations.get()>0);
        assertTrue(context.getSharedPreferences("feature_secrets",0).contains(slot));assertTrue(bootstrap.snapshot().optBoolean("processTerminationPending"));
        context.connection.onServiceDisconnected(new ComponentName(context,AdbBootstrapService.class));
        assertTrue(context.getSharedPreferences("feature_secrets",0).contains(slot));assertTrue(child.isBinderAlive());
        child.die();await(()->bootstrap.snapshot().optString("phase").equals("identity_forgotten"));
        assertFalse(context.getSharedPreferences("feature_secrets",0).contains(slot));
    }
    @Test public void cancellationBeforeServiceConnectionNeverDispatchesTheRequest()throws Exception {
        AtomicInteger checks=new AtomicInteger();bootstrap.connect(endpoint(),configuration(),checks::incrementAndGet);bootstrap.cancel();Child child=child();deliver(child);
        await(()->child.cancellations.get()>0);assertEquals(0,checks.get());assertEquals(0,child.requests.get());assertFalse(bootstrap.snapshot().optBoolean("helperAuthenticated"));
        assertTrue(bootstrap.snapshot().optBoolean("busy"));child.die();await(()->!bootstrap.snapshot().optBoolean("busy"));
    }
    private static final class FakeContext extends ContextWrapper {
        volatile ServiceConnection connection;
        FakeContext(Context base){super(base);}
        @Override public Context getApplicationContext(){return this;}
        @Override public boolean bindService(Intent intent,ServiceConnection connection,int flags){this.connection=connection;assertEquals(AdbBootstrapService.class.getName(),intent.getComponent().getClassName());return true;}
        @Override public void unbindService(ServiceConnection connection){}
    }
    private static final class Child extends Binder {
        final AtomicInteger requests=new AtomicInteger(),cancellations=new AtomicInteger();
        final CopyOnWriteArrayList<IBinder.DeathRecipient> deaths=new CopyOnWriteArrayList<>();
        volatile boolean alive=true;
        @Override public boolean isBinderAlive(){return alive;}
        @Override public void linkToDeath(IBinder.DeathRecipient recipient,int flags){deaths.add(recipient);}
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException {
            data.enforceInterface(AdbBootstrapWire.SERVICE);reply.writeNoException();
            if(code==AdbBootstrapWire.INFO){reply.writeLong(7);reply.writeString("idle");reply.writeString(null);}
            else if(code==AdbBootstrapWire.CANCEL){assertEquals(7,data.readLong());cancellations.incrementAndGet();reply.writeInt(1);}
            else if(code==AdbBootstrapWire.REQUEST)requests.incrementAndGet();
            else throw new AssertionError("unexpected private wire operation "+code);return true;
        }
        void die(){if(!alive)return;alive=false;for(IBinder.DeathRecipient recipient:deaths)recipient.binderDied();}
    }
}
