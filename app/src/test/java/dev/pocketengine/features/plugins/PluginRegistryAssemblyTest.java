package dev.pocketengine.features.plugins;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PluginRegistryAssemblyTest {
    private static JSONObject plugin(String id)throws Exception{return new JSONObject().put("id",id).put("enabled",true);}
    @Test public void brokenOptionalInstallationDoesNotPublishPartialToolsOrPoisonHealthyPlugin()throws Exception {
        JSONArray original=new JSONArray().put(plugin("broken")).put(plugin("healthy"));String before=original.toString();
        var result=PluginRegistryAssembly.assemble(original,plugin->{
            JSONArray staged=new JSONArray().put(new JSONObject().put("name",plugin.getString("id")));
            if(plugin.getString("id").equals("broken"))throw new SecurityException("private argv /home/private token=never-export");
            return staged;
        });
        assertEquals(before,original.toString());assertEquals(1,result.tools().length());assertEquals("healthy",result.tools().getJSONObject(0).getString("name"));
        JSONObject failure=result.unavailable().getJSONObject(0);assertEquals("broken",failure.getString("pluginId"));assertFalse(failure.getBoolean("toolsExposed"));
        assertFalse(result.unavailable().toString().contains("private"));assertFalse(result.unavailable().toString().contains("never-export"));
        result.tools().getJSONObject(0).put("name","mutated");assertEquals("healthy",result.tools().getJSONObject(0).getString("name"));
    }
    @Test public void malformedStagedEntryRollsBackOnlyItsEntirePlugin()throws Exception {
        var result=PluginRegistryAssembly.assemble(new JSONArray().put(plugin("bad")).put(plugin("good")),plugin->
                plugin.getString("id").equals("bad")?new JSONArray().put(new JSONObject().put("name","must-not-survive")).put("invalid"):
                        new JSONArray().put(new JSONObject().put("name","good")));
        assertEquals(1,result.tools().length());assertEquals("good",result.tools().getJSONObject(0).getString("name"));
        assertEquals(1,result.unavailable().length());
    }
    @Test public void disabledPluginsAreNotProbedAndKnownAvailabilityReasonsRemainBounded()throws Exception {
        AtomicInteger calls=new AtomicInteger();
        var result=PluginRegistryAssembly.assemble(new JSONArray().put(plugin("disabled").put("enabled",false)).put(plugin("changed")),plugin->{
            calls.incrementAndGet();throw new PluginRegistryAssembly.Unavailable("source_invalidated");
        });
        assertEquals(1,calls.get());assertEquals(0,result.tools().length());assertEquals("source_invalidated",result.unavailable().getJSONObject(0).getString("code"));
        assertThrows(IllegalArgumentException.class,()->new PluginRegistryAssembly.Unavailable("arbitrary-private-error"));
    }
    @Test public void cancellationAndFatalRuntimeFailureAreNotDisguisedAsOptionalAbsence()throws Exception {
        JSONArray installed=new JSONArray().put(plugin("selected"));
        assertThrows(CancellationException.class,()->PluginRegistryAssembly.assemble(installed,plugin->{throw new CancellationException();}));
        assertThrows(AssertionError.class,()->PluginRegistryAssembly.assemble(installed,plugin->{throw new AssertionError("fatal");}));
        try {
            assertThrows(InterruptedException.class,()->PluginRegistryAssembly.assemble(installed,plugin->{throw new InterruptedException();}));
            assertTrue(Thread.currentThread().isInterrupted());
        }finally{Thread.interrupted();}
        assertThrows(NullPointerException.class,()->PluginRegistryAssembly.assemble(null,plugin->new JSONArray()));
    }
}
