package dev.pocketengine.runtime;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public final class ReferenceHistoryTest {
 private JSONArray history(String tool,JSONObject result)throws Exception{return new JSONArray().put(new JSONObject().put("role","assistant").put("tool_calls",new JSONArray().put(new JSONObject().put("id","reference-call").put("type","function").put("function",new JSONObject().put("name",tool).put("arguments","{}"))))).put(new JSONObject().put("role","tool").put("tool_call_id","reference-call").put("content",result.toString()));}
 private JSONObject live()throws Exception{return new JSONObject().put("memory",new JSONArray().put(new JSONObject().put("id","r").put("revision",2))).put("skills",new JSONArray().put(new JSONObject().put("id","s").put("revision",3).put("digest","digest-current")));}
 @Test public void exactLiveMemoryVersionRemainsUsableButChangedRevisionClearsEveryDerivedBody()throws Exception{
  JSONObject receipt=new JSONObject().put("records",new JSONArray().put(new JSONObject().put("id","r").put("revision",2).put("text","private-body"))).put("context","summary-private-body");JSONArray history=history("memory_recall",receipt);
  AgentRunner.refreshReferenceHistory(history,live());assertTrue(history.toString().contains("private-body"));JSONObject changed=live();changed.getJSONArray("memory").getJSONObject(0).put("revision",3);
  AgentRunner.refreshReferenceHistory(history,changed);assertFalse(history.toString().contains("private-body"));assertEquals("reference-call",history.getJSONObject(1).getString("tool_call_id"));assertTrue(history.toString().contains("reference_expired"));
 }
 @Test public void skillReadAndCatalogueRequireBothRevisionAndDigest()throws Exception{
  JSONObject row=new JSONObject().put("id","s").put("revision",3).put("digest","digest-current").put("content","skill-body");
  for(String tool:new String[]{"skills_read","skills_list"}){JSONArray history=history(tool,tool.equals("skills_read")?row:new JSONObject().put("skills",new JSONArray().put(row)));AgentRunner.refreshReferenceHistory(history,live());assertTrue(history.toString().contains("skill-body"));JSONObject stale=live();stale.getJSONArray("skills").getJSONObject(0).put("digest","different");AgentRunner.refreshReferenceHistory(history,stale);assertFalse(history.toString().contains("skill-body"));}
 }
 @Test public void absentAuthorityFailsClosedOnlyForReferenceTools()throws Exception{
  JSONArray history=history("memory_search",new JSONObject().put("content","retired-body"));AgentRunner.refreshReferenceHistory(history,null);assertFalse(history.toString().contains("retired-body"));
  history=history("workspace_read",new JSONObject().put("content","actual-effect-evidence"));AgentRunner.refreshReferenceHistory(history,null);assertTrue(history.toString().contains("actual-effect-evidence"));
 }
 @Test public void disableAndReapprovalRetiresSameContentHistoryByExecutionGeneration()throws Exception{
  JSONObject current=live();current.getJSONArray("skills").getJSONObject(0).put("executionGeneration",7L);
  for(String tool:new String[]{"skills_read","skills_list"}){
   JSONObject row=new JSONObject().put("id","s").put("revision",3).put("digest","digest-current").put("executionGeneration",7L).put("content","OLD_APPROVAL_BODY");
   JSONArray history=history(tool,tool.equals("skills_read")?row:new JSONObject().put("skills",new JSONArray().put(row)));
   AgentRunner.refreshReferenceHistory(history,current);assertTrue(history.toString().contains("OLD_APPROVAL_BODY"));
   JSONObject reapproved=new JSONObject(current.toString());reapproved.getJSONArray("skills").getJSONObject(0).put("executionGeneration",9L);
   AgentRunner.refreshReferenceHistory(history,reapproved);assertFalse(history.toString().contains("OLD_APPROVAL_BODY"));assertTrue(history.toString().contains("reference_expired"));
  }
 }
 @Test public void changedProjectScopeRetiresSameVersionGlobalAndProjectReferences()throws Exception{
  String first="a".repeat(64),next="b".repeat(64);JSONObject current=live().put("skillScopeFingerprint",first);
  current.getJSONArray("skills").getJSONObject(0).put("skillScopeFingerprint",first);
  for(String tool:new String[]{"skills_read","skills_list"}){
   JSONObject row=new JSONObject().put("id","s").put("revision",3).put("digest","digest-current").put("skillScopeFingerprint",first).put("content","EXACT_PROJECT_BODY");
   JSONObject receipt=tool.equals("skills_read")?row:new JSONObject().put("skills",new JSONArray().put(row)).put("skillScopeFingerprint",first);
   JSONArray history=history(tool,receipt);AgentRunner.refreshReferenceHistory(history,current);assertTrue(history.toString().contains("EXACT_PROJECT_BODY"));
   JSONObject switched=new JSONObject(current.toString()).put("skillScopeFingerprint",next);switched.getJSONArray("skills").getJSONObject(0).put("skillScopeFingerprint",next);
   AgentRunner.refreshReferenceHistory(history,switched);assertFalse(history.toString().contains("EXACT_PROJECT_BODY"));assertTrue(history.toString().contains("reference_expired"));
  }
 }
 @Test public void oldUnscopedReceiptsCannotAcquireNewNativeScopeByMatchingVersion()throws Exception{
  String scope="c".repeat(64);JSONObject current=live().put("skillScopeFingerprint",scope);current.getJSONArray("skills").getJSONObject(0).put("skillScopeFingerprint",scope);
  JSONObject row=new JSONObject().put("id","s").put("revision",3).put("digest","digest-current").put("content","LEGACY_UNSCOPED_BODY");
  JSONArray history=history("skills_read",row);AgentRunner.refreshReferenceHistory(history,current);assertFalse(history.toString().contains("LEGACY_UNSCOPED_BODY"));
  row.put("skillScopeFingerprint",scope);history=history("skills_list",new JSONObject().put("skills",new JSONArray().put(row)).put("skillScopeFingerprint","d".repeat(64)));
  AgentRunner.refreshReferenceHistory(history,current);assertFalse(history.toString().contains("LEGACY_UNSCOPED_BODY"));
 }
 @Test public void oneStaleMemberClearsWholeRecallInsteadOfLeavingItsTextInContext()throws Exception{
  JSONArray rows=new JSONArray().put(new JSONObject().put("id","r").put("revision",2).put("text","live")).put(new JSONObject().put("id","forgotten").put("revision",1).put("text","forgotten-body"));JSONArray history=history("memory_recall",new JSONObject().put("records",rows).put("context","live + forgotten-body"));AgentRunner.refreshReferenceHistory(history,live());assertFalse(history.toString().contains("forgotten-body"));
 }
 @Test public void reusedCallIdIsReboundToNewToolAndNeverRedactsUnrelatedEvidence()throws Exception{
  JSONArray history=history("memory_search",new JSONObject().put("content","retired-reference"));JSONArray next=history("workspace_read",new JSONObject().put("content","actual-effect-evidence"));for(int i=0;i<next.length();i++)history.put(next.get(i));
  AgentRunner.refreshReferenceHistory(history,null);assertFalse(history.toString().contains("retired-reference"));assertTrue(history.getJSONObject(3).getString("content").contains("actual-effect-evidence"));
  java.lang.reflect.Method durable=AgentRunner.class.getDeclaredMethod("durableHistory",JSONArray.class,dev.pocketengine.core.ModelConfig.class);durable.setAccessible(true);
  JSONArray saved=(JSONArray)durable.invoke(null,history,new dev.pocketengine.core.ModelConfig("","","fixture",false,3,10));assertTrue(saved.toString().contains("actual-effect-evidence"));assertEquals("reference-call",saved.getJSONObject(saved.length()-1).getString("tool_call_id"));
 }
}
