package dev.pocketengine.ui;

import android.os.Looper;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.media.MediaController;
import dev.pocketengine.testutil.ReadyApplicationFixture;
import dev.pocketengine.ui.web.PocketWebBridge;
import dev.pocketengine.ui.web.PocketWebWindowTicket;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.Provider;
import java.security.Security;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import kotlin.Unit;
import org.json.JSONObject;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import static org.junit.Assert.*;

/** Original ready App and Controller through the production Web bridge. No rendered-UI claim. */
public final class OwnerWebTestFixture implements AutoCloseable {
    public final EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();
    public final ActivityController<ComposeActivity> owner;
    public final ComposeActivity activity;
    public final PocketWorldController controller;
    public final MediaController media;
    public final PocketWebBridge bridge;
    private final List<JSONObject> events=new ArrayList<>();
    private final String document="owner-document-"+UUID.randomUUID();
    private int sequence;
    private final Provider keys=new Provider("OwnerWeb-"+UUID.randomUUID(),1.0,"Test-only AndroidKeyStore") {
        {put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());}
    };
    public OwnerWebTestFixture()throws Exception {
        assertTrue(Security.insertProviderAt(keys,1)>0);
        ReadyApplicationFixture.initializeHost(app);
        owner=Robolectric.buildActivity(ComposeActivity.class);activity=owner.get();
        media=new MediaController(activity,app::publish);
        Field field=ComposeActivity.class.getDeclaredField("media");field.setAccessible(true);field.set(activity,media);
        controller=new PocketWorldController(activity,app);
        field=ComposeActivity.class.getDeclaredField("controller");field.setAccessible(true);field.set(activity,controller);
        field=PocketWorldController.class.getDeclaredField("voiceForeground");field.setAccessible(true);field.setBoolean(controller,true);
        controller.attach();
        bridge=new PocketWebBridge(controller,event->{events.add(event);return Unit.INSTANCE;});bridge.start();
        call("app.ready",new JSONObject().put("documentId",document));
    }
    public static Object invoke(Object target,String name,Object...arguments)throws Exception {
        for(Method method:target.getClass().getDeclaredMethods())if((method.getName().equals(name)||method.getName().startsWith(name+"$"))&&method.getParameterCount()==arguments.length){method.setAccessible(true);return method.invoke(target,arguments);}
        throw new AssertionError("No original method "+name);
    }
    public void drain()throws Exception {
        Field f=PocketWorldController.class.getDeclaredField("io");f.setAccessible(true);
        ((ExecutorService)f.get(controller)).submit(()->{}).get(5,TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32));
    }
    public JSONObject envelope(String action,JSONObject args)throws Exception {
        String id="owner-"+(++sequence);
        JSONObject request=new JSONObject().put("v",1).put("id",id).put("documentId",document).put("action",action).put("args",args);
        PocketWebWindowTicket ticket=(PocketWebWindowTicket)invoke(bridge,"captureWindow");
        bridge.postMessage(request.toString(),ticket,()->{throw new AssertionError("Original dispatch unexpectedly stale");});
        for(int i=0;i<100;i++){
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            for(JSONObject event:events)if(id.equals(event.optString("id"))&&"result".equals(event.optString("kind")))return event;
            drain();Thread.sleep(5);
        }
        throw new AssertionError("Original bridge did not complete "+action);
    }
    public JSONObject call(String action,JSONObject args)throws Exception {
        JSONObject result=envelope(action,args);assertTrue(result.toString(),result.getBoolean("ok"));return result.getJSONObject("value");
    }
    public JSONObject state()throws Exception {
        for(int i=events.size()-1;i>=0;i--){JSONObject event=events.get(i);if("state".equals(event.optString("kind")))return event.getJSONObject("snapshot").getJSONObject("state");}
        throw new AssertionError("No actual Web state projection");
    }
    public interface Condition {boolean ready()throws Exception;}
    public void await(Condition condition)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!condition.ready()&&System.nanoTime()<until){drain();Thread.sleep(5);}
        assertTrue("Original App condition not reached",condition.ready());
    }
    @Override public void close()throws Exception {
        try{bridge.close();controller.detach();media.close();owner.close();}
        finally{try{ReadyApplicationFixture.closeHost(app);}finally{Security.removeProvider(keys.getName());}}
    }
}
