package dev.pocketengine.browser;
import static org.junit.Assert.*;
import org.json.*;
import org.junit.Test;
public final class BrowserActionScopeTest {
 static JSONObject j(Object...p)throws Exception{return BrowserReadScopeTest.j(p);}
 JSONObject policy()throws Exception{return BrowserActionScope.forOwner(true,"https://example.com",5,true).review();}
 JSONObject pin()throws Exception{return j("tabId","page","pageUrl","https://example.com/research","targetPreview",j("tag","INPUT","type","text","text","查询","href",""));}
 @Test public void routineNavigationIsExactOriginButConsequencesAndScriptsRemainPrecise()throws Exception {
  JSONObject p=policy();assertTrue(BrowserActionScope.eligible(p,"browser_task_open",j("url","https://example.com/page/2"),new JSONObject()));
  for(String url:new String[]{"https://other.example/page","https://example.com/checkout","https://example.com/delete?id=1"})assertFalse(BrowserActionScope.eligible(p,"browser_task_open",j("url",url),new JSONObject()));
  for(String tool:new String[]{"script","press","download","set_checked","request_user","click"})assertFalse(BrowserActionScope.eligible(p,"browser_task_"+tool,j("tabId","page"),pin()));
 }
 @Test public void inputIsOptedInAndSensitiveOrConsequenceTargetsCannotBorrowIt()throws Exception {
  assertTrue(BrowserActionScope.eligible(policy(),"browser_task_type",j("tabId","page"),pin()));assertFalse(BrowserActionScope.eligible(BrowserActionScope.forOwner(true,"https://example.com",5,false).review(),"browser_task_type",j("tabId","page"),pin()));
  for(JSONObject target:new JSONObject[]{j("type","password"),j("type","text","ownerRequired",true),j("type","text","text","发送给客户")})assertFalse(BrowserActionScope.eligible(policy(),"browser_task_type",j("tabId","page"),pin().put("targetPreview",target)));
 }
 @Test public void childReadsAndNetworkInstallationCannotEscapeTheSameOriginScope()throws Exception {
  assertFalse(BrowserActionScope.eligible(policy(),"browser_task_observe",j("tabId","page"),pin().put("readFrame",j("sourceOrigin","https://private.example"))));
  assertTrue(BrowserActionScope.eligible(policy(),"browser_task_network",j("tabId","page","action","read"),pin()));assertFalse(BrowserActionScope.eligible(policy(),"browser_task_network",j("tabId","page","action","start"),pin()));
 }
 @Test public void nativeOwnerClosedControlSetAllowsRepeatedInteractionsButNeverInfersFromNextPage()throws Exception {
  JSONObject target=j("tag","BUTTON","type","button","id","more","text","下一页");JSONObject pin=pin().put("targetPreview",target);JSONObject args=j("tabId","page");
  assertFalse(BrowserActionScope.eligible(policy(),"browser_task_click",args,pin));
  JSONObject explicit=BrowserActionScope.forOwner(true,"https://example.com",8,false,true,false,false,"下一页\n#more").review();BrowserActionScope.validate(explicit);
  assertTrue(BrowserActionScope.eligible(explicit,"browser_task_click",args,pin));assertTrue(BrowserActionScope.eligible(explicit,"browser_task_click",args,pin));
  assertFalse(BrowserActionScope.eligible(explicit,"browser_task_click",args,pin().put("targetPreview",j("tag","BUTTON","type","button","text","其他按钮"))));
  for(JSONObject bad:new JSONObject[]{j("tag","BUTTON","type","submit","text","下一页"),j("tag","BUTTON","type","button","text","下一页","formAction","https://example.com/search"),j("tag","BUTTON","type","button","id","more","text","购买"),j("tag","BUTTON","type","button","id","more","text","下一页\n删除"),j("tag","A","type","","text","下一页","href","https://example.com/page2")})assertFalse(BrowserActionScope.eligible(explicit,"browser_task_click",args,pin().put("targetPreview",bad)));
  assertThrows(Exception.class,()->BrowserActionScope.forOwner(true,"https://example.com/path",8,false));assertThrows(Exception.class,()->BrowserActionScope.forOwner(true,"https://example.com",8,false,true,false,false,"*"));assertThrows(Exception.class,()->BrowserActionScope.forOwner(true,"https://example.com",8,false,true,false,false,""));
 }
 @Test public void observedSelectionIsNotPortableToAnotherDocumentOrFrame()throws Exception {
  JSONObject args=j("tabId","page","observationId","obs","ref","n1"),p=pin().put("observationId","obs").put("targetPreview",j("ref","n1","nodeKey","k2","tag","BUTTON","type","button","id","more","text","下一页"));for(String key:new String[]{"taskId","runId","epoch","profileId","profileRevision","browserSessionId","browserSpaceId","tabGeneration","navigation","documentId"})p.put(key,key);
  JSONObject policy=BrowserActionScope.forOwner(true,"https://example.com",8,false,true,false,false,"",new JSONArray().put(j("arguments",args,"pin",p))).review();BrowserActionScope.validate(policy);assertTrue(BrowserActionScope.eligible(policy,"browser_task_click",args,p));
  assertFalse(BrowserActionScope.eligible(policy,"browser_task_click",args,new JSONObject(p.toString()).put("targetPreview",new JSONObject(p.getJSONObject("targetPreview").toString()).put("ref","n2").put("nodeKey","k3"))));assertTrue(BrowserActionScope.eligible(policy,"browser_task_click",args,new JSONObject(p.toString()).put("targetPreview",new JSONObject(p.getJSONObject("targetPreview").toString()).put("ref","n2"))));assertFalse(BrowserActionScope.eligible(policy,"browser_task_click",args,new JSONObject(p.toString()).put("documentId","replacement")));assertFalse(BrowserActionScope.eligible(policy,"browser_task_click",args,new JSONObject(p.toString()).put("actionFrame",j("frameId","child","sourceOrigin","https://example.com"))));
 }
 @Test public void readonlyLanguageRejectsMutationOperatorsAndCanonicalizesSource()throws Exception {
  assertThrows(Exception.class,()->BrowserPagePrograms.validateExpression("{\"op\":\"eval\",\"value\":\"fetch('/send')\"}"));
  assertThrows(Exception.class,()->BrowserPagePrograms.validateExpression("{\"op\":\"attr\",\"name\":\"value\",\"input\":{\"op\":\"item\"}}"));
  String script=BrowserPagePrograms.expression("{\"op\":\"literal\",\"value\":42} trailing_raw_injection");assertFalse(script.contains("trailing_raw_injection"));assertTrue(script.contains("42"));
 }
 @Test public void networkEvidenceDropsQueriesWithoutChangingEscapedPaths()throws Exception {assertEquals("https://example.com/a%2Fb",BrowserNetworkWitness.redact("https://u:p@example.com/a%2Fb?token=secret#cookie"));}
 @Test public void rawScriptsAreExplicitlyMainOnlyAndDoNotPretendReadonly()throws Exception {assertThrows(Exception.class,()->BrowserPagePrograms.validateScript(j("source","return 1;","effectReview","读页","frameRef","child")));String script=BrowserPagePrograms.script("document.body.textContent='changed'; return 7;","token");assertTrue(script.contains("document.body.textContent='changed'"));assertTrue(script.contains("effectsMayContinue:true"));}
}
