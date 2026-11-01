package dev.pocketengine.browser;

import dev.pocketengine.core.RunControl;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Native consent / eligibility only, not a grant store or Android/device/network test. */
public final class BrowserReadScopeTest {
    static JSONObject j(Object... pairs)throws Exception{return PublicHttpReaderTest.obj(pairs);}
    static JSONObject pin()throws Exception{return j("taskId",UUID.randomUUID().toString(),"runId",UUID.randomUUID().toString(),"epoch",1,"profileId","profile-a","profileRevision",1L,"profileIsolation","androidx-multi-profile","browserSessionId",UUID.randomUUID().toString(),"browserSpaceId",UUID.randomUUID().toString(),"tabId",UUID.randomUUID().toString());}
    @Test public void scopeIsDisabledByDefaultAndTheLegacyApprovalInterfaceDoesNotInventAPermit()throws Exception {
        assertFalse(BrowserReadScope.disabled().enabled());assertFalse(BrowserReadScope.forOwner(false,true,"https://public.example/a").enabled());assertThrows(SecurityException.class,()->BrowserReadScope.validatePolicy(BrowserReadScope.disabled().review()));
        BrowserTaskCapabilityPort.ApprovalPort legacy=(b,c,op,tool,args,p)->j("approved",false);assertNull(legacy.admitReadScope(null,null,"none","browser_task_observe",new JSONObject(),new JSONObject()));assertFalse(legacy.nativeReadAuthority(null,null,"browser_task_observe"));
    }
    @Test public void ownerReviewIsImmutableAndCanonicalSeedsAreExplicitlySourceConsented()throws Exception {
        assertThrows(IllegalArgumentException.class,()->BrowserReadScope.forOwner(true,false,"https://public.example/a"));BrowserReadScope.Consent consent=BrowserReadScope.forOwner(true,true,"https://public.example\nhttps://second.example/a?q=public");JSONObject review=consent.review();assertEquals("https://public.example/",review.getJSONArray("publicSeeds").getString(0));review.put("maxReads",9999);assertEquals(32,consent.review().getInt("maxReads"));BrowserReadScope.validatePolicy(consent.review());
        assertThrows(Exception.class,()->BrowserReadScope.forOwner(true,true,"https://user@public.example/a"));assertThrows(Exception.class,()->BrowserReadScope.forOwner(true,true,"https://public.example/?access_token=private"));
        assertThrows(Exception.class,()->BrowserReadScope.forOwner(true,true,String.join("\n",java.util.stream.IntStream.range(0,9).mapToObj(i->"https://public.example/"+i).toArray(String[]::new))));
    }
    @Test public void fixedBudgetsAndUnknownFieldsCannotBeEnlarged()throws Exception {
        JSONObject policy=BrowserReadScope.forOwner(true,false,"").review();for(String key:new String[]{"ttlMs","maxReads","maxPublicBytes"})assertThrows(SecurityException.class,()->BrowserReadScope.validatePolicy(new JSONObject(policy.toString()).put(key,999999999)));
        assertThrows(SecurityException.class,()->BrowserReadScope.validatePolicy(new JSONObject(policy.toString()).put("allowClicks",true)));assertThrows(SecurityException.class,()->BrowserReadScope.validatePolicy(new JSONObject(policy.toString()).put("ttlMs","300000")));
    }
    @Test public void onlyOwnedOriginalPageReadsAndTheExactObservedSourceAreEligible()throws Exception {
        JSONObject p=pin(),policy=BrowserReadScope.forOwner(true,false,"").review(),read=j("tabId",p.getString("tabId"));assertTrue(BrowserReadScope.eligible(policy,"browser_task_observe",read,p));assertFalse(BrowserReadScope.eligible(policy,"browser_task_observe",j("tabId",UUID.randomUUID().toString()),p));assertFalse(BrowserReadScope.eligible(policy,"browser_task_observe",new JSONObject(read.toString()).put("scopeId",UUID.randomUUID().toString()),p));
        assertTrue(BrowserReadScope.eligible(policy,"browser_task_wait",new JSONObject(read.toString()).put("condition","text").put("value","local expected text"),p));assertFalse(BrowserReadScope.eligible(policy,"browser_task_wait",new JSONObject(read.toString()).put("condition","load").put("value","arbitrary"),p));
        p.put("observationId",UUID.randomUUID().toString());JSONObject source=new JSONObject(read.toString()).put("observationId",p.getString("observationId"));assertFalse(BrowserReadScope.eligible(policy,"browser_task_retain_source",source,p));assertTrue(BrowserReadScope.eligible(BrowserReadScope.forOwner(true,true,"").review(),"browser_task_retain_source",source,p));assertFalse(BrowserReadScope.eligible(BrowserReadScope.forOwner(true,true,"").review(),"browser_task_retain_source",new JSONObject(source.toString()).put("observationId",UUID.randomUUID().toString()),p));
    }
    @Test public void publicSeedsDoNotAuthorizeModelInventedPathsQueriesPrefixesOrOrigins()throws Exception {
        JSONObject policy=BrowserReadScope.forOwner(true,true,"https://public.example/article?q=owner\nhttps://cdn.example/source").review(),p=pin(),args=j("url","https://public.example/article?q=owner");assertTrue(BrowserReadScope.eligible(policy,PublicHttpReader.TOOL,args,p));assertTrue(BrowserReadScope.eligible(policy,PublicHttpReader.TOOL,new JSONObject(args.toString()).put("allowedRedirectOrigins",new JSONArray().put("https://cdn.example")),p));
        for(String bad:new String[]{"https://public.example/article?q=private-context","https://public.example/other","https://public.example/article?q=owner&extra=x","https://public.example.attacker.example/article?q=owner"})assertFalse(BrowserReadScope.eligible(policy,PublicHttpReader.TOOL,j("url",bad),p));
        assertFalse(BrowserReadScope.eligible(policy,PublicHttpReader.TOOL,new JSONObject(args.toString()).put("allowedRedirectOrigins",new JSONArray().put("https://outside.example")),p));assertEquals(262144,BrowserReadScope.bodyReservation(PublicHttpReader.TOOL,args));
    }
    @Test public void sensitiveActionsNeverBecomeReadScoped()throws Exception {JSONObject policy=BrowserReadScope.forOwner(true,true,"https://public.example/").review();for(String action:new String[]{"open","select","navigate","click","type","scroll","download","upload","request_user","close","finish"})assertFalse(action,BrowserReadScope.eligible(policy,"browser_task_"+action,new JSONObject(),pin()));}
    @Test public void runEpochProfileSessionAndPauseRevisionAreAllPartOfTheBinding()throws Exception {
        JSONObject pin=pin(),identity=BrowserReadScope.identity(pin,0);assertTrue(BrowserReadScope.sameIdentity(identity,pin,0));assertFalse(BrowserReadScope.sameIdentity(identity,pin,1));for(String key:new String[]{"taskId","runId","browserSessionId","browserSpaceId"})assertFalse(key,BrowserReadScope.sameIdentity(identity,new JSONObject(pin.toString()).put(key,UUID.randomUUID().toString()),0));assertFalse(BrowserReadScope.sameIdentity(identity,new JSONObject(pin.toString()).put("epoch",2),0));assertFalse(BrowserReadScope.sameIdentity(identity,new JSONObject(pin.toString()).put("profileRevision",2),0));
    }
    @Test public void receiptUsesNativeScopeNotAFakeApprovalAndNeverProvesBusinessCompletion()throws Exception {
        JSONObject actual=j("verified",false,"status","observed"),permit=j("scopeId",UUID.randomUUID().toString(),"scopeRevision",2L,"operationId",UUID.randomUUID().toString(),"admittedAt",1L,"remainingReadsAtAdmission",31,"remainingPublicBytesAtAdmission",1048576L);JSONObject receipt=BrowserReadScope.receipt(actual,permit);assertEquals("native_read_scope",receipt.getJSONObject("readAuthorization").getString("mode"));assertFalse(receipt.has("approvalId"));assertFalse(receipt.getBoolean("verified"));
    }
    @Test public void revokingANativeScopeStopsTheSameBudgetWithoutReplacingTheOriginalControl()throws Exception {RunControl original=new RunControl();PublicHttpReader.Budget budget=new PublicHttpReader.Budget(original);budget.stopNativeScope();assertThrows(SecurityException.class,budget::check);original.resume();assertThrows(SecurityException.class,budget::check);assertTrue(budget.boundTo(original));}
}
