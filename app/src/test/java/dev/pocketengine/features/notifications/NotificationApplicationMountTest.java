package dev.pocketengine.features.notifications;

import dev.pocketengine.EngineApplication;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.w3c.dom.Element;
import static org.junit.Assert.*;

/** Production composition, not a fixture Host that could hide a missing Application mount. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
public final class NotificationApplicationMountTest {
    @Test public void systemListenerIsDeclaredWithTheOfficialBindingContract()throws Exception {
        var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
        var document=factory.newDocumentBuilder().parse(Path.of(System.getProperty("pocketworld.sourceRoot","."),"app/src/main/AndroidManifest.xml").toFile());
        String android="http://schemas.android.com/apk/res/android";Element match=null;
        var services=document.getElementsByTagName("service");
        for(int i=0;i<services.getLength();i++){Element service=(Element)services.item(i);if(service.getAttributeNS(android,"name").endsWith(".features.notifications.OwnerNotificationListenerService"))match=service;}
        assertNotNull("The actual product Manifest must declare the system listener",match);
        assertEquals("false",match.getAttributeNS(android,"exported"));assertEquals("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",match.getAttributeNS(android,"permission"));
        var actions=match.getElementsByTagName("action");assertEquals(1,actions.getLength());assertEquals("android.service.notification.NotificationListenerService",((Element)actions.item(0)).getAttributeNS(android,"name"));
    }
    @Test public void unavailableModuleDoesNotCreateAnotherHostOrCrashTheSystemCallback()throws Exception {
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();
        var original=app.features;app.features=new dev.pocketengine.features.FeatureHost(event->{});
        app.features.register(new dev.pocketengine.features.EngineModule(){public String id(){return "notifications";}public org.json.JSONObject snapshot(){return new org.json.JSONObject();}public org.json.JSONObject handle(String command,org.json.JSONObject input){throw new SecurityException("unavailable fixture");}});
        assertNull(app.notificationsModule());var service=org.robolectric.Robolectric.buildService(OwnerNotificationListenerService.class).create();
        try{assertNull(service.get().resolveModule());service.get().onListenerConnected();assertEquals(0,app.tasks.list().length());}finally{service.destroy();app.features.close();app.features=original;}
    }
    @Test public void realApplicationOwnsTheOnlyModuleResolvedByTheSystemService()throws Exception {
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();
        assertTrue("The production Application must implement the listener Host",((Object)app) instanceof NotificationsModule.Host);
        NotificationsModule singleton=((NotificationsModule.Host)(Object)app).notificationsModule();
        assertNotNull(singleton);assertSame(app.features.module("notifications"),singleton);
        var service=org.robolectric.Robolectric.buildService(OwnerNotificationListenerService.class).create();try{
            assertSame(singleton,service.get().resolveModule());assertFalse(singleton.snapshot().getBoolean("enabled"));
            service.get().onListenerConnected();assertFalse(singleton.snapshot().getBoolean("listenerConnected"));
        }finally{service.destroy();}
        assertEquals(0,app.tasks.list().length());
    }
}
