package dev.pocketengine.features.web;

import android.app.Application;
import dev.pocketengine.store.SettingsStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class WebSearchModuleTest {
    @Test public void duckDuckGoUnwrapsRealTargetAndRejectsUnsafeLinks()throws Exception {
        String html="<div class='result'><a class='result__a' href='https://duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fnews'>A verified link</a><div class='result__snippet'>A snippet</div></div>"
                +"<div class='result'><a class='result__a' href='http://localhost/private'>Local address</a></div>"
                +"<div class='result'><a class='result__a' href='https://example.com/news'>Duplicate</a></div>";
        JSONArray rows=WebSearchModule.parseDuckDuckGo(html,5);
        assertEquals(1,rows.length());
        assertEquals("https://example.com/news",rows.getJSONObject(0).getString("url"));
        assertEquals("A snippet",rows.getJSONObject(0).getString("snippet"));
    }
    @Test public void searxngAndBraveNormalizeActualResultsWithoutManufacturingSources()throws Exception {
        JSONObject searxng=new JSONObject().put("results",new JSONArray()
                .put(new JSONObject().put("title","Current page").put("url","https://example.org/current").put("content","Summary"))
                .put(new JSONObject().put("title","Private page").put("url","http://localhost:8765/private").put("content","Ignored")));
        JSONArray a=WebSearchModule.parseSearxng(searxng,5);
        assertEquals(1,a.length());
        assertEquals("Summary",a.getJSONObject(0).getString("snippet"));
        JSONObject brave=new JSONObject().put("web",new JSONObject().put("results",new JSONArray()
                .put(new JSONObject().put("title","Result").put("url","https://example.net/story").put("description","Brave summary"))));
        JSONArray b=WebSearchModule.parseBrave(brave,3);
        assertEquals("Brave summary",b.getJSONObject(0).getString("snippet"));
        assertEquals(0,WebSearchModule.parseBrave(new JSONObject(),5).length());
    }
    @Test public void ownerConfigurationIsRevisionBoundAndCannotUseInsecureOrigin()throws Exception {
        SettingsStore store=new SettingsStore(RuntimeEnvironment.getApplication());
        JSONObject before=store.webSearchSettings();
        assertEquals("duckduckgo",before.getString("provider"));
        assertFalse(before.getBoolean("networkVerified"));
        JSONObject saved=store.saveWebSearchSettings(new JSONObject()
                .put("provider","searxng").put("baseUrl","https://search.example.org/")
                .put("expectedRevision",before.getLong("revision")));
        assertEquals("https://search.example.org",saved.getString("baseUrl"));
        assertFalse(saved.getBoolean("apiKeyConfigured"));
        try {
            store.saveWebSearchSettings(new JSONObject().put("provider","searxng")
                    .put("baseUrl","https://search.example.org").put("expectedRevision",before.getLong("revision")));
            fail("stale owner revision must not be accepted");
        }catch(SecurityException expected){}
        try {
            store.saveWebSearchSettings(new JSONObject().put("provider","searxng")
                    .put("baseUrl","http://localhost:8080").put("expectedRevision",saved.getLong("revision")));
            fail("insecure owner endpoint must not be accepted");
        }catch(SecurityException expected){}
    }
}

