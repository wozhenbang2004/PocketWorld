package dev.pocketengine;

import dev.pocketengine.features.NativePluginHost;
import dev.pocketengine.features.plugins.catalog.PluginCatalogSession;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Read-only reviewer test over frozen V19 classes: no production source change. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
public final class NativePluginUnknownAdmissionReviewTest {
  static JSONObject read(NativePluginHost.Page page)throws Exception {
    return page.session.begin(page.nativeOwner(),UUID.randomUUID().toString(),"read",new JSONObject()).execute().data().getJSONObject("config");
  }
  @Test public void unknownOriginalCatalogOperationMustFenceAReplacementNativePage()throws Exception {
    EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();
    NativePluginHost.Page old=app.pluginHost.newOwnerPage();JSONObject config=read(old);
    JSONObject input=new JSONObject().put("sources",new JSONArray()).put("expectedRevision",config.getString("revision"));
    PluginCatalogSession.Request first=old.session.begin(old.nativeOwner(),UUID.randomUUID().toString(),"configure",input);
    old.beforeDispatch(first,old.session.view(old.nativeOwner()),input);old.close();
    // Same documented unknown path as the retained V19 no-receipt regression.
    old.settled(first,null,null);assertTrue(app.tasks.hasUnresolved(first.requestId));
    NativePluginHost.Page replacement=app.pluginHost.newOwnerPage();JSONObject live=read(replacement);
    JSONObject again=new JSONObject().put("sources",new JSONArray()).put("expectedRevision",live.getString("revision"));
    PluginCatalogSession.Request second=replacement.session.begin(replacement.nativeOwner(),UUID.randomUUID().toString(),"configure",again);
    try {
      assertThrows("new page must not bypass original durable unknown",IllegalStateException.class,
        ()->replacement.beforeDispatch(second,replacement.session.view(replacement.nativeOwner()),again));
    } finally { replacement.close(); }
  }
}
