package dev.pocketengine.features.teams;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class TeamResearchReadScopeTest {
    @Test public void explicitAnonymousOriginsCannotBecomePrivateProfilesOrExtraSites()throws Exception {
        JSONObject allowed=TeamResearchReadScope.empty().put("webOrigins",new JSONArray().put("https://example.org"));assertTrue(TeamResearchReadScope.allows(allowed,"https://example.org/article?q=topic"));assertFalse(TeamResearchReadScope.allows(allowed,"https://attacker.example/article"));assertThrows(SecurityException.class,()->TeamResearchReadScope.allows(allowed,"http://example.org"));assertThrows(SecurityException.class,()->TeamResearchReadScope.checked(new JSONObject(allowed.toString()).put("profileId","owner-login")));assertThrows(SecurityException.class,()->TeamResearchReadScope.origin("https://user:password@example.org"));assertThrows(SecurityException.class,()->TeamResearchReadScope.origin("https://127.0.0.1"));
    }
    @Test public void peerResearchNeedsAllSourcePermissionsAndExactFileVersions()throws Exception {
        JSONObject web=TeamResearchReadScope.empty().put("webOrigins",new JSONArray().put("https://example.org"));assertFalse(TeamResearchReadScope.covers(TeamResearchReadScope.empty(),web));assertTrue(TeamResearchReadScope.covers(web,web));JSONObject file=new JSONObject().put("projectId","aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa").put("path","src/main.txt").put("version",1).put("revisionId","bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb").put("sha256","a".repeat(64)).put("sizeBytes",20);JSONObject first=TeamResearchReadScope.empty().put("projectFiles",new JSONArray().put(file));JSONObject changed=new JSONObject(first.toString());changed.getJSONArray("projectFiles").getJSONObject(0).put("version",2);assertFalse(TeamResearchReadScope.covers(first,changed));assertThrows(IllegalArgumentException.class,()->TeamResearchReadScope.path("../private.key"));
    }
}
