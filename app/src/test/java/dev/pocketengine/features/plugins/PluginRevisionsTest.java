package dev.pocketengine.features.plugins;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PluginRevisionsTest {
    private JSONObject manifest(String version)throws Exception {
        return PluginRules.manifest(new JSONObject().put("id","demo").put("name","工具").put("version",version)
                .put("tools",new JSONArray().put(new JSONObject().put("name","lookup").put("kind","http")
                        .put("endpoint","https://old.example/tool").put("effect","read"))));
    }
    private JSONObject plugin(String version)throws Exception {
        return new JSONObject().put("id","demo").put("manifest",manifest(version)).put("installationId","install-"+version)
                .put("installedAt",10).put("enabled",true).put("grants",new JSONArray().put("tool:lookup"))
                .put("credentialRef","private-slot").put("token","never-archive-this");
    }

    @Test public void previewExplainsEndpointEffectSchemaAndToolChangesWithoutMutation()throws Exception {
        JSONObject current=plugin("1"),next=manifest("2");String before=current.toString();
        next.getJSONArray("tools").getJSONObject(0).put("endpoint","https://new.example/tool").put("effect","write")
                .put("schema",new JSONObject().put("type","object").put("required",new JSONArray().put("query")));
        next.getJSONArray("tools").put(new JSONObject().put("name","echo").put("kind","echo"));
        JSONObject preview=PluginRevisions.preview(current,PluginRules.manifest(next));
        assertEquals("install-1",preview.getString("expectedInstallationId"));assertTrue(preview.getBoolean("isUpdate"));
        assertEquals("2",preview.getString("targetVersion"));assertEquals(1,preview.getJSONArray("addedTools").length());
        JSONObject change=preview.getJSONArray("changedTools").getJSONObject(0);
        assertEquals("lookup",change.getString("name"));assertEquals(3,change.getJSONArray("fields").length());
        assertTrue(change.getJSONArray("fields").toString().contains("endpoint"));
        assertTrue(change.getJSONArray("fields").toString().contains("schema"));
        assertTrue(change.getJSONArray("fields").toString().contains("effect"));
        assertFalse(preview.getBoolean("enabledAfterInstall"));assertFalse(preview.getBoolean("credentialsRetained"));
        assertEquals(0,preview.getJSONArray("grantsAfterInstall").length());assertEquals(before,current.toString());
        assertFalse(preview.toString().contains("private-slot"));assertFalse(preview.toString().contains("never-archive-this"));
    }

    @Test public void previewShowsRemovedToolsAndSameVersionStillHasNewInstallationConsequences()throws Exception {
        JSONObject current=plugin("1");
        JSONObject preview=PluginRevisions.preview(current,manifest("1"));assertTrue(preview.getBoolean("sameManifest"));
        assertFalse(preview.getBoolean("enabledAfterInstall"));
        JSONObject next=manifest("1");next.getJSONArray("tools").getJSONObject(0).put("name","replacement");
        preview=PluginRevisions.preview(current,next);assertEquals(1,preview.getJSONArray("removedTools").length());
        assertEquals(1,preview.getJSONArray("addedTools").length());assertFalse(preview.getBoolean("sameManifest"));
    }

    @Test public void firstInstallPreviewHasAnAbsenceFenceAndDoesNotClaimAnUpgrade()throws Exception {
        JSONObject preview=PluginRevisions.preview(null,manifest("1"));
        assertFalse(preview.getBoolean("isUpdate"));assertEquals("",preview.getString("expectedInstallationId"));
        PluginRevisions.checkExpected(null,preview,false);
        try{PluginRevisions.checkExpected(plugin("1"),preview,false);fail("an intervening install invalidates a fresh-install preview");}catch(IllegalStateException expected){}
    }

    @Test public void historyIsBoundedNewestFirstAndNeverKeepsGrantsOrCredentialSlots()throws Exception {
        JSONObject current=plugin("1");
        for(int n=2;n<=6;n++){
            JSONObject next=plugin(Integer.toString(n));next.put("history",PluginRevisions.archive(current,100+n));current=next;
        }
        JSONArray versions=PluginRevisions.history(current).getJSONArray("versions");assertEquals(3,versions.length());
        assertEquals("install-5",versions.getJSONObject(0).getString("installationId"));
        assertEquals("install-3",versions.getJSONObject(2).getString("installationId"));
        assertEquals(106,versions.getJSONObject(0).getLong("archivedAt"));
        String json=versions.toString();for(String forbidden:new String[]{"credentialRef","private-slot","token","never-archive-this","enabled","grants","history"})assertFalse(forbidden,json.contains(forbidden));
        assertEquals("5",PluginRevisions.restoreManifest(current,"install-5").getString("version"));
        try{PluginRevisions.restoreManifest(current,"install-1");fail("pruned versions cannot be resurrected");}catch(IllegalArgumentException expected){}
    }

    @Test public void archivedAndReturnedDeclarationsAreDetachedFromTheCurrentState()throws Exception {
        JSONObject current=plugin("1"),next=plugin("2");next.put("history",PluginRevisions.archive(current,20));
        current.getJSONObject("manifest").put("name","edited source");
        JSONObject restored=PluginRevisions.restoreManifest(next,"install-1");assertEquals("工具",restored.getString("name"));
        restored.put("name","edited result");
        assertEquals("工具",PluginRevisions.restoreManifest(next,"install-1").getString("name"));
    }

    @Test public void rollbackRequiresCurrentIdentityButNeverResurrectsAnUnretainedTarget()throws Exception {
        JSONObject current=plugin("2");
        try{PluginRevisions.checkExpected(current,new JSONObject(),true);fail();}catch(IllegalArgumentException expected){}
        try{PluginRevisions.checkExpected(current,new JSONObject().put("expectedInstallationId","install-1"),true);fail();}catch(IllegalStateException expected){}
        PluginRevisions.checkExpected(current,new JSONObject().put("expectedInstallationId","install-2"),true);
        try{PluginRevisions.restoreManifest(current,"install-1");fail();}catch(IllegalArgumentException expected){}
    }

    @Test public void restoreRequiresTheReviewedOwnerStateAsWellAsTheInstallation()throws Exception {
        JSONObject current=plugin("2");
        JSONObject request=new JSONObject().put("expectedInstallationId","install-2");
        try{PluginRevisions.checkRestoreExpected(current,request);fail("restoration needs an owner-state fence");}catch(IllegalArgumentException expected){}
        request.put("expectedStateRevision",0);PluginRevisions.checkRestoreExpected(current,request);
        PluginRevisions.advanceState(current);assertEquals(1,PluginRevisions.stateRevision(current));
        try{PluginRevisions.checkRestoreExpected(current,request);fail("a later owner write invalidates restoration");}catch(IllegalStateException expected){}
        assertEquals(1,PluginRevisions.history(current).getLong("currentStateRevision"));
        assertEquals(1,PluginRevisions.preview(current,manifest("1")).getLong("expectedStateRevision"));
        assertFalse(PluginRevisions.archive(current,30).getJSONObject(0).has("stateRevision"));
    }

    @Test public void stateFenceRejectsLossyCoercedMissingAndNegativeRevisions()throws Exception {
        JSONObject current=plugin("2");
        assertEquals(0,PluginRevisions.stateRevision(current));
        for(Object revision:new Object[]{JSONObject.NULL,"0",-1,0.5,new java.math.BigInteger("9223372036854775808"),true}){
            JSONObject request=new JSONObject().put("expectedInstallationId","install-2").put("expectedStateRevision",revision);
            try{PluginRevisions.checkRestoreExpected(current,request);fail("invalid revision: "+revision);}catch(IllegalArgumentException expected){}
        }
        PluginRevisions.checkRestoreExpected(current,new JSONObject().put("expectedInstallationId","install-2").put("expectedStateRevision",new java.math.BigDecimal("0.0")));
    }

    @Test public void legacyInstallationsAcquireAStableHistoryIdentityWithoutGrantMigration()throws Exception {
        JSONObject legacy=plugin("1");legacy.remove("installationId");
        JSONObject current=plugin("2").put("history",PluginRevisions.archive(legacy,20));
        assertEquals("legacy-10",PluginRevisions.history(current).getJSONArray("versions").getJSONObject(0).getString("installationId"));
        assertEquals("1",PluginRevisions.restoreManifest(current,"legacy-10").getString("version"));
    }

    @Test public void aHistoryRecordCannotSwitchThePluginIdentity()throws Exception {
        JSONObject previous=plugin("1");previous.getJSONObject("manifest").put("id","other");
        JSONObject current=plugin("2").put("history",PluginRevisions.archive(previous,20));
        try{PluginRevisions.restoreManifest(current,"install-1");fail();}catch(IllegalStateException expected){}
    }

    @Test public void previewDoesNotHideSchemaIntegerChangesBeyondDoublePrecision()throws Exception {
        for(long value:new long[]{9007199254740992L,Long.MAX_VALUE-1,Long.MIN_VALUE}){
            JSONObject current=plugin("1"),next=manifest("1");
            current.getJSONObject("manifest").getJSONArray("tools").getJSONObject(0).put("schema",numericSchema(value));
            next.getJSONArray("tools").getJSONObject(0).put("schema",numericSchema(value+1));
            JSONObject preview=PluginRevisions.preview(current,next);
            assertFalse(preview.getBoolean("sameManifest"));assertEquals(1,preview.getJSONArray("changedTools").length());
            assertEquals("schema",preview.getJSONArray("changedTools").getJSONObject(0).getJSONArray("fields").getString(0));
        }
    }

    @Test public void previewTreatsEquivalentJsonNumberRepresentationsAsEqual()throws Exception {
        JSONObject current=plugin("1"),next=manifest("1");
        current.getJSONObject("manifest").getJSONArray("tools").getJSONObject(0).put("schema",numericSchema(1));
        next.getJSONArray("tools").getJSONObject(0).put("schema",numericSchema(1.0));
        JSONObject preview=PluginRevisions.preview(current,next);
        assertTrue(preview.getBoolean("sameManifest"));assertEquals(0,preview.getJSONArray("changedTools").length());
    }

    private JSONObject numericSchema(Number value)throws Exception {
        return new JSONObject().put("type","object").put("properties",new JSONObject().put("id",
                new JSONObject().put("type","integer").put("enum",new JSONArray().put(value))));
    }
}
