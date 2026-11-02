package dev.pocketengine.browser;

import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Only synthetic destinations. Tests the original no-backup profile file, never real credentials. */
public final class BrowserPrivateNavigationTest {
    private static File file()throws Exception{return new File(Files.createTempDirectory("browser-private-navigation-").toFile(),"profiles.json");}
    private static final String[] ROUTES={"https://example.com/item?id=A","https://example.com/item?id=B","https://example.com/app#/item/A","https://example.com/app#/item/B"};
    @Test public void parameterBearingTitlesAreConservativeWhileBarePageTitlesRemainUseful()throws Exception {
        String url="https://example.com/article?token=SYNTHETIC%5FSECRET#access_token=SYNTHETIC_FRAGMENT";BrowserNavigationTarget target=BrowserNavigationTarget.parse(url);assertEquals(target.displayUrl,target.title(url,"Research notes"));assertEquals(target.displayUrl,target.title(url,"SYNTHETIC_SECRET"));assertEquals(target.displayUrl,target.title(url,"SYNTHETIC_FRAGMENT"));assertEquals(target.displayUrl,target.title(url,url));String bare="https://example.com/article";assertEquals("Research notes",BrowserNavigationTarget.parse(bare).title(bare,"Research notes"));
    }
    @Test public void routeSegmentEchoTitlesNeverPersistWithoutExplicitUrlRetention()throws Exception {
        File file=file();BrowserProfileRegistry registry=new BrowserProfileRegistry(file);var session=registry.openSession(false);String task=UUID.randomUUID().toString(),secret="route-secret-"+UUID.randomUUID();String encoded=secret.replace("-","%2D");String[] urls={"https://example.com/app#/item/"+secret,"https://example.com/app#!/item/"+secret,"https://example.com/app#/item/"+encoded,"https://example.com/app?route=/item/"+secret,"https://example.com/app?route=%2Fitem%2F"+encoded,"https://example.com/app#/item/"+secret+"?tab=summary","https://example.com/app?view=article#/item/"+secret};
        for(String url:urls){JSONObject page=registry.recoveryPageForOwner(session,task,UUID.randomUUID().toString(),url,"Document "+secret,"ready","task");assertFalse("research title echoed a private route segment",page.toString().contains(secret));assertEquals("https://example.com/app",page.getString("title"));BrowserResearchPages.validate(BrowserResearchPagesTest.manifest(page));JSONObject history=registry.visitForOwner(session,url,"Document "+secret,1,false);assertFalse("history title echoed a private route segment",history.toString().contains(secret));assertEquals("https://example.com/app",history.getString("title"));assertFalse("default profile persisted private parameter data",Files.readString(file.toPath()).contains(secret));}
        BrowserProfileRegistry cold=new BrowserProfileRegistry(file);assertFalse(cold.historyForOwner(cold.openSession(false)).toString().contains(secret));
        registry.exactUrlsForOwner(session,true);for(String url:urls){JSONObject page=registry.recoveryPageForOwner(session,task,UUID.randomUUID().toString(),url,"Document "+secret,"ready","task");assertFalse(page.toString().contains(secret));assertEquals(url,registry.recoveryUrlForOwner(session,task,page));JSONObject history=registry.visitForOwner(session,url,"Document "+secret,2,false);assertFalse(history.toString().contains(secret));assertEquals(url,registry.historyUrlForOwner(session,history.getString("id")));}registry.exactUrlsForOwner(session,false);assertFalse("revoked private data survived through a title",Files.readString(file.toPath()).contains(secret));
    }
    @Test public void unsavedParameterPageCannotBeRestoredAsDifferentBaseContent()throws Exception {
        BrowserProfileRegistry registry=new BrowserProfileRegistry(file());var session=registry.openSession(false);String task=UUID.randomUUID().toString();JSONObject page=registry.recoveryPageForOwner(session,task,UUID.randomUUID().toString(),ROUTES[0],"A","ready","task");assertFalse(page.has("privateNavigationId"));assertThrows(SecurityException.class,()->registry.recoveryUrlForOwner(session,task,page));BrowserTaskSpace.Driver driver=new BrowserTaskSpaceTest.Driver();assertThrows(BrowserTaskSpace.StalePage.class,()->driver.recoveryUrl(page));JSONObject history=registry.visitForOwner(session,ROUTES[0],"A",1,false);assertEquals("https://example.com/item",registry.historyUrlForOwner(session,history.getString("id")));
    }
    @Test public void fourExactHistoryRoutesSurviveColdRegistryWithoutLeakingIntoOwnerProjection()throws Exception {
        File file=file();BrowserProfileRegistry registry=new BrowserProfileRegistry(file);var original=registry.openSession(false);registry.exactUrlsForOwner(original,true);JSONArray ids=new JSONArray();
        for(int i=0;i<ROUTES.length;i++){JSONObject row=registry.visitForOwner(original,ROUTES[i],ROUTES[i],i+1,false);ids.put(row.getString("id"));assertTrue(row.has("privateNavigationId"));assertFalse(row.toString().contains("?id="));assertFalse(row.toString().contains("#/item/"));}
        JSONObject first=registry.visitForOwner(original,ROUTES[0],"First",5,true);assertEquals(ids.getString(0),first.getString("id"));registry.bookmarkForOwner(original,first.getString("id"));
        BrowserProfileRegistry cold=new BrowserProfileRegistry(file);var fresh=cold.openSession(false);assertNotEquals(original.sessionId,fresh.sessionId);assertEquals(4,cold.historyForOwner(fresh).length());
        for(int i=0;i<ROUTES.length;i++)assertEquals(ROUTES[i],cold.historyUrlForOwner(fresh,ids.getString(i)));
        assertTrue(cold.historyItemForOwner(fresh,ids.getString(0)).getBoolean("bookmark"));assertFalse(cold.snapshot(false).toString().contains("?id="));
    }
    @Test public void explicitOptInIsRequiredAndRevocationPermanentlyInvalidatesOldManifestRefs()throws Exception {
        File file=file();BrowserProfileRegistry registry=new BrowserProfileRegistry(file);var session=registry.openSession(false);String task=UUID.randomUUID().toString(),logical=UUID.randomUUID().toString(),url="https://example.com/callback?token=SYNTHETIC_SECRET#SYNTHETIC_FRAGMENT";
        JSONObject omitted=registry.recoveryPageForOwner(session,task,logical,url,"SYNTHETIC_SECRET","ready","task");assertFalse(omitted.has("privateNavigationId"));assertFalse(Files.readString(file.toPath()).contains("SYNTHETIC_SECRET"));
        registry.exactUrlsForOwner(session,true);JSONObject saved=registry.recoveryPageForOwner(session,task,logical,url,"SYNTHETIC_SECRET","ready","task");assertEquals(url,registry.recoveryUrlForOwner(session,task,saved));assertFalse(saved.toString().contains("SYNTHETIC_"));BrowserResearchPages.validate(BrowserResearchPagesTest.manifest(saved));
        JSONObject history=registry.visitForOwner(session,url,url,1,false);registry.exactUrlsForOwner(session,false);assertFalse(Files.readString(file.toPath()).contains("SYNTHETIC_"));assertFalse(registry.historyItemForOwner(session,history.getString("id")).has("privateNavigationId"));assertThrows(SecurityException.class,()->registry.recoveryUrlForOwner(session,task,saved));
        registry.exactUrlsForOwner(session,true);assertThrows(SecurityException.class,()->registry.recoveryUrlForOwner(session,task,saved));JSONObject replacement=registry.recoveryPageForOwner(session,task,logical,url,"safe","ready","task");assertNotEquals(saved.getString("privateNavigationId"),replacement.getString("privateNavigationId"));
    }
    @Test public void privateTargetsAreBoundToTaskLogicalPageAndSelectedProfile()throws Exception {
        BrowserProfileRegistry registry=new BrowserProfileRegistry(file());var session=registry.openSession(true);registry.exactUrlsForOwner(session,true);String task=UUID.randomUUID().toString(),logical=UUID.randomUUID().toString();JSONObject saved=registry.recoveryPageForOwner(session,task,logical,ROUTES[0],"A","ready","task");
        assertThrows(SecurityException.class,()->registry.recoveryUrlForOwner(session,UUID.randomUUID().toString(),saved));assertThrows(SecurityException.class,()->registry.recoveryUrlForOwner(session,task,new JSONObject(saved.toString()).put("logicalPageId",UUID.randomUUID().toString())));assertThrows(SecurityException.class,()->registry.recoveryUrlForOwner(session,task,new JSONObject(saved.toString()).put("url","https://other.example/item")));
        String other=registry.create("Other",registry.revision(),true).getString("id");registry.select(other,registry.revision(),true);var selected=registry.openSession(true);assertThrows(SecurityException.class,()->registry.recoveryUrlForOwner(session,task,saved));assertThrows(SecurityException.class,()->registry.recoveryUrlForOwner(selected,task,saved));assertFalse(registry.exactUrlsForOwner(selected));
    }
    @Test public void fullRawEncodedPathQueryAndFragmentAreNeverNormalizedOrMerged()throws Exception {
        File file=file();BrowserProfileRegistry registry=new BrowserProfileRegistry(file);var session=registry.openSession(false);registry.exactUrlsForOwner(session,true);String task=UUID.randomUUID().toString(),logical=UUID.randomUUID().toString();String exact="https://example.com/a%2Fb/%2E/%2e%2E?k=a%2Fb&k=a+b#/%2e/%2F";JSONObject row=registry.recoveryPageForOwner(session,task,logical,exact,"Encoded","ready","task");BrowserProfileRegistry cold=new BrowserProfileRegistry(file);assertEquals(exact,cold.recoveryUrlForOwner(cold.openSession(false),task,row));
        JSONObject changed=registry.recoveryPageForOwner(session,task,logical,exact.replace("a%2Fb&","a/b&"),"Encoded","ready","task");assertNotEquals(row.getString("privateNavigationId"),changed.getString("privateNavigationId"));assertThrows(SecurityException.class,()->registry.recoveryUrlForOwner(session,task,row));
    }
    @Test public void clearingHistoryRemovesItsPrivateAddressesButNotOriginalResearchMappings()throws Exception {
        File file=file();BrowserProfileRegistry registry=new BrowserProfileRegistry(file);var session=registry.openSession(false);registry.exactUrlsForOwner(session,true);String task=UUID.randomUUID().toString();JSONObject page=registry.recoveryPageForOwner(session,task,UUID.randomUUID().toString(),ROUTES[0],"A","ready","task");JSONObject history=registry.visitForOwner(session,"https://example.com/history?token=SYNTHETIC_HISTORY","Synthetic",1,false);registry.clearHistoryForOwner(session,true);assertThrows(IllegalStateException.class,()->registry.historyUrlForOwner(session,history.getString("id")));assertFalse(Files.readString(file.toPath()).contains("SYNTHETIC_HISTORY"));assertEquals(ROUTES[0],registry.recoveryUrlForOwner(session,task,page));
    }
    @Test public void failedPrivateSaveDoesNotReturnAnUncommittedReference()throws Exception {
        File file=file();BrowserProfileRegistry registry=new BrowserProfileRegistry(file);var session=registry.openSession(false);registry.exactUrlsForOwner(session,true);Files.delete(file.toPath());Files.createDirectory(file.toPath());Files.writeString(new File(file,"blocker").toPath(),"x");assertThrows(Exception.class,()->registry.recoveryPageForOwner(session,UUID.randomUUID().toString(),UUID.randomUUID().toString(),ROUTES[0],"A","ready","task"));assertEquals(0,registry.historyForOwner(session).length());
    }
    @Test public void missingNativeResolverCannotFallBackToWrongRedactedDestination()throws Exception {
        BrowserTaskSpace.Driver driver=new BrowserTaskSpaceTest.Driver();JSONObject row=BrowserResearchPages.page(UUID.randomUUID().toString(),ROUTES[0],"A","ready","task").put("privateNavigationId",UUID.randomUUID().toString());assertThrows(BrowserTaskSpace.StalePage.class,()->driver.recoveryUrl(row));assertThrows(SecurityException.class,()->BrowserResearchPages.validate(BrowserResearchPagesTest.manifest(new JSONObject(row.toString()).put("privateUrl",ROUTES[0]))));
    }
    @Test public void invalidCredentialUrlsRemainUnrecoverableEvenAfterOptIn()throws Exception {
        BrowserProfileRegistry registry=new BrowserProfileRegistry(file());var session=registry.openSession(false);registry.exactUrlsForOwner(session,true);String task=UUID.randomUUID().toString();for(String url:new String[]{"https://user:password@example.com/?token=SYNTHETIC","javascript:alert(1)","file:///tmp/secret"}){assertNull(registry.visitForOwner(session,url,"bad",1,false));assertEquals("",registry.recoveryPageForOwner(session,task,UUID.randomUUID().toString(),url,"bad","unknown","task").getString("url"));}
    }
}
