package dev.pocketengine.features.semantic;

import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SemanticTypedShareRequestTest {
    static JSONObject share()throws Exception{return new JSONObject().put("kind","share_text").put("packageName","share.native.fixture").put("text","主人确认的完整纯文本\n第二行，不选择收件人。");}
    static JSONObject link()throws Exception{return new JSONObject().put("kind","app_link").put("packageName","share.native.fixture").put("url","https://example.org/entry?item=owner-selected");}
    @Test public void typedShareAndHttpsPackageLinkPreserveExactContentInTheOriginalDigest()throws Exception{
        JSONObject raw=share();SemanticRequest request=SemanticRequest.parse(raw);String digest=request.digest();raw.put("text","后来修改");assertNotEquals(raw.getString("text"),request.parameters().getString("text"));JSONObject copy=request.parameters();copy.put("packageName","other.fixture");assertEquals("share.native.fixture",request.parameters().getString("packageName"));assertEquals(digest,request.digest());assertNotEquals(digest,SemanticRequest.parse(share().put("packageName","other.fixture")).digest());assertNotEquals(digest,SemanticRequest.parse(share().put("text","不同完整文本")).digest());assertEquals("android.intent.action.SEND",request.action);assertEquals("owner_verifies_target_app_no_automatic_continuation",request.followup());
        SemanticRequest https=SemanticRequest.parse(link());assertEquals("https://example.org/entry?item=owner-selected",https.data);assertEquals("example.org",https.host);assertEquals("android.intent.action.VIEW",https.action);
    }
    @Test public void appLinkCannotBypassTheOriginalHttpsTypedDialOrMailAdmission()throws Exception{
        for(String uri:new String[]{"http://example.org/plain","tel:*123%23","mailto:a@example.org?body=hidden","sms:+123","tg://resolve?domain=x","file:///sdcard/private","content://private/1","javascript:alert(1)","intent://x#Intent;end","data:text/plain,x","https://user:secret@example.org/x","https://example.org/with space","https://example.org\\other/x"})assertThrows(uri,Exception.class,()->SemanticRequest.parse(link().put("url",uri)));
    }
    @Test public void shareRejectsArbitraryIntentExtrasRecipientsFilesAndUnboundedOrMissingBody()throws Exception{
        for(String field:new String[]{"action","extras","recipient","component","uri","mimeType","flags","_hostOperationId"})assertThrows(field,Exception.class,()->SemanticRequest.parse(share().put(field,"not-native-authority")));
        for(Object body:new Object[]{JSONObject.NULL,"", " ","x".repeat(10001),"secret\u0000control"})assertThrows(Exception.class,()->SemanticRequest.parse(share().put("text",body)));
        assertEquals(10000,SemanticRequest.parse(share().put("text","字".repeat(10000))).parameters().getString("text").length());
    }
    @Test public void exactPackageIsRequiredForBothAdaptersAndSchemaCannotAdvertiseRawIntentOrCustomSchemes()throws Exception{
        for(String pkg:new String[]{"", "space package", "com.example;other", "../other", "*", "system"}){assertThrows(Exception.class,()->SemanticRequest.parse(share().put("packageName",pkg)));assertThrows(Exception.class,()->SemanticRequest.parse(link().put("packageName",pkg)));}
        JSONObject schema=SemanticRequest.discoverySchema();JSONArray kinds=schema.getJSONObject("properties").getJSONObject("kind").getJSONArray("enum");Set<String> names=new java.util.HashSet<>();for(int i=0;i<kinds.length();i++)names.add(kinds.getString(i));assertTrue(names.containsAll(Set.of("web","app_link","share_text","calendar_draft","alarm","timer")));assertFalse(schema.getBoolean("additionalProperties"));assertFalse(schema.getJSONObject("properties").has("extras"));
    }
    @Test public void nativeApprovalShowsTheEntireBoundedTextAndWarnsRatherThanPromisingDraftOrDelivery()throws Exception{
        JSONObject request=share().put("text","字".repeat(9990)+"EXACT-TAIL"),capability=new JSONObject().put("request",request).put("target",new JSONObject().put("label","准确分享接收App").put("packageName","share.native.fixture").put("component","share.native.fixture/.Main")).put("targetIdentity",new JSONObject().put("versionCode",7).put("certificates",new JSONArray().put("actual-test-signature")));
        String original=capability.toString(),text=SemanticApprovalText.describe(capability);assertTrue(text.contains(request.getString("text")));assertTrue(text.contains("EXACT-TAIL")&&text.contains("不保证只打开草稿")&&text.contains("可能立即")&&text.contains("不据此宣称已发送"));assertTrue(text.contains("share.native.fixture/.Main")&&text.contains("actual-test-signature")&&text.contains("手机主屏"));assertEquals(original,capability.toString());
        capability.put("request",link());text=SemanticApprovalText.describe(capability);assertTrue(text.contains(link().getString("url")));assertTrue(text.contains("不保证业务只读")&&text.contains("退出不保证撤销"));
    }
    private static final class Lifetime implements SemanticPlatform,SemanticCapabilityPort.Authority {
        final dev.pocketengine.core.RunControl original=new dev.pocketengine.core.RunControl();final SemanticRunSession session;final Target target;int effects;boolean loseReceipt;
        Lifetime()throws Exception{original.bindHostRun("11111111-1111-1111-1111-111111111111","22222222-2222-2222-2222-222222222222",1);session=new SemanticRunSession(original,()->1);target=new Target(new JSONObject().put("component","share.native.fixture/share.native.fixture.Main").put("packageName","share.native.fixture").put("versionCode",1).put("certificates",new JSONArray().put("fixture")),"Explicit fixture","official_intent");}
        SemanticCapabilityPort port()throws Exception{return new SemanticCapabilityPort(null,original,this,this,true,session);}
        public void check(String tool,dev.pocketengine.core.RunControl live)throws Exception{assertSame(original,live);live.check();}
        public void requirePrepared(String operation,String tool,JSONObject args){assertNotNull(operation);}
        public JSONObject approve(String tool,JSONObject args,JSONObject capability)throws Exception{return new JSONObject().put("approved",true);}
        public java.util.List<Target> discover(SemanticRequest request,dev.pocketengine.core.RunControl live){return java.util.List.of(target);}
        public JSONObject resourceIdentity()throws Exception{return new JSONObject().put("executionMode","main_screen").put("displayId",0).put("serviceSessionId","original");}
        public JSONObject dispatch(SemanticRequest request,Target selected,dev.pocketengine.core.RunControl live,Admission admission)throws Exception{admission.check();effects++;if(loseReceipt)throw new java.io.IOException("explicit fixture lost ACK after entry");return new JSONObject().put("accepted",true);}
        JSONObject args(SemanticCapabilityPort port,JSONObject request)throws Exception{JSONObject found=port.execute(SemanticCapabilityPort.DISCOVER,request,original);return new JSONObject().put("discoveryId",found.getString("discoveryId")).put("candidateId",found.getJSONArray("candidates").getJSONObject(0).getString("candidateId"));}
        JSONObject send(SemanticCapabilityPort port,JSONObject args)throws Exception{return port.execute(SemanticCapabilityPort.DISPATCH,new JSONObject(args.toString()).put("_hostOperationId",java.util.UUID.randomUUID().toString()),original);}
    }
    @Test public void unknownTypedEntryReceiptFencesNewOperationsAcrossTheSameOriginalSessionPortReconstruction()throws Exception{
        for(JSONObject request:new JSONObject[]{share(),link()}){Lifetime fixture=new Lifetime();SemanticCapabilityPort first=fixture.port();fixture.loseReceipt=true;JSONObject unknown=fixture.send(first,fixture.args(first,request));assertTrue(unknown.isNull("accepted"));assertTrue(unknown.getBoolean("requiresReconciliation"));assertFalse(unknown.getBoolean("automaticFallback"));first.close();fixture.loseReceipt=false;SemanticCapabilityPort next=fixture.port();assertEquals("unknown",fixture.send(next,fixture.args(next,request)).getString("status"));assertEquals(1,fixture.effects);fixture.session.close();}
    }
    @Test public void acceptedTypedEntryCannotReplayItsDiscoveryThroughANewPortAndAlwaysStopsForTheOwner()throws Exception{
        for(JSONObject request:new JSONObject[]{share(),link()}){Lifetime fixture=new Lifetime();SemanticCapabilityPort first=fixture.port();JSONObject args=fixture.args(first,request),accepted=fixture.send(first,args);assertTrue(accepted.getBoolean("accepted"));assertTrue(accepted.getBoolean("requiresPause"));assertTrue(accepted.getBoolean("requiresOwnerCompletion"));assertFalse(accepted.getBoolean("externalDeliveryVerified"));assertFalse(accepted.getBoolean("automaticContinuation"));first.close();assertEquals("stale_semantic_discovery",fixture.send(fixture.port(),args).getString("code"));assertEquals(1,fixture.effects);fixture.session.close();}
    }

}
