package dev.pocketengine.runtime.routes;

import dev.pocketengine.core.RunControl;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PortablePhoneRouteReferenceTest {
    private JSONObject candidate()throws Exception {
        JSONObject step=new JSONObject().put("tool","phone_click_node").put("selector",new JSONObject().put("resourceId","app.example:id/private_account_12345").put("role","Button").put("textExact","搜索"))
                .put("beforeAnchors",new JSONArray().put("PRIVATE_ACCOUNT_NAME")).put("afterAnchors",new JSONArray().put("PRIVATE_TOKEN_VALUE"));
        return signed(new JSONObject().put("format","pocketworld.phone-route-candidate.v1").put("routeId","PRIVATE_ROUTE_ID").put("sourceTaskId","PRIVATE_TASK_ID").put("sourceRunId","PRIVATE_RUN_ID").put("sourceEpoch",8)
                .put("goal","PRIVATE_ORIGINAL_GOAL").put("hostUserIdentity","PRIVATE_USER_ID").put("operationIds",new JSONArray().put("PRIVATE_OPERATION_ID"))
                .put("packageName","app.example").put("packageVersion",7L).put("steps",new JSONArray().put(step)).put("requiresOwnerSuccess",true).put("businessVerified",false));
    }
    private JSONObject signed(JSONObject input)throws Exception {return input.put("digest",PhoneRouteSession.digest(input));}
    private JSONObject reference()throws Exception{return PortablePhoneRouteReference.fromLocalCandidate(candidate());}
    private JSONObject read(String text)throws Exception{return PortablePhoneRouteReference.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),new RunControl());}
    @Test public void sourceIdentityPageTextAndRawSelectorsCannotEscapeTheNativeProjection()throws Exception {
        JSONObject value=reference(),step=value.getJSONArray("steps").getJSONObject(0);String serialized=value.toString();
        assertFalse(serialized.contains("PRIVATE_"));assertFalse(serialized.contains("private_account"));assertFalse(serialized.contains("resourceId"));assertFalse(serialized.contains("textExact"));
        assertEquals("search",step.getString("navigation"));assertEquals("button",step.getString("role"));assertEquals("target_1",step.getString("targetParameter"));assertEquals(3,value.getJSONArray("parameters").length());
        assertEquals("unverified_reference",value.getString("trust"));assertFalse(value.getBoolean("executionAuthorized"));
        JSONObject view=PortablePhoneRouteReference.presentation(value);assertFalse(view.getBoolean("replayAllowed"));assertFalse(view.getBoolean("localTargetsBound"));assertFalse(view.getBoolean("crossDeviceVerified"));assertFalse(view.getBoolean("imported"));
        assertEquals(value.getString("digest"),read(view.getString("text")).getString("digest"));assertEquals(view.getInt("bytes"),view.getString("text").getBytes(StandardCharsets.UTF_8).length);
    }
    @Test public void privateOriginDifferencesDoNotCreateDifferentTransferableContentOrProofOfTrust()throws Exception {
        JSONObject first=candidate(),other=candidate().put("goal","ANOTHER_PRIVATE_GOAL").put("hostUserIdentity","ANOTHER_PRIVATE_USER").put("sourceTaskId","ANOTHER_TASK");
        other.getJSONArray("steps").getJSONObject(0).getJSONObject("selector").put("resourceId","app.example:id/different_private_account");signed(other);
        assertEquals(PortablePhoneRouteReference.fromLocalCandidate(first).getString("digest"),PortablePhoneRouteReference.fromLocalCandidate(other).getString("digest"));
        assertThrows(IllegalArgumentException.class,()->PortablePhoneRouteReference.checked(signed(reference().put("trust","owner_confirmed"))));
        assertThrows(IllegalArgumentException.class,()->PortablePhoneRouteReference.checked(signed(reference().put("executionAuthorized",true))));
    }
    @Test public void closedFieldsRejectRawTextGrantsAndBoundParameterValuesEvenWithCorrectDigests()throws Exception {
        for(String key:new String[]{"goal","account","token","sourceTaskId","hostUserIdentity","approval","screenshots","metadata"})assertThrows(IllegalArgumentException.class,()->PortablePhoneRouteReference.checked(signed(reference().put(key,"SECRET"))));
        JSONObject selector=reference();selector.getJSONArray("steps").getJSONObject(0).put("selector",new JSONObject().put("resourceId","secret"));signed(selector);assertThrows(IllegalArgumentException.class,()->PortablePhoneRouteReference.checked(selector));
        JSONObject bound=reference();bound.getJSONArray("parameters").getJSONObject(0).put("value","SECRET");signed(bound);assertThrows(IllegalArgumentException.class,()->PortablePhoneRouteReference.checked(bound));
        JSONObject injected=reference();injected.getJSONArray("parameters").getJSONObject(0).put("name","accountName");signed(injected);assertThrows(IllegalArgumentException.class,()->PortablePhoneRouteReference.checked(injected));
    }
    @Test public void legacyLocalRouteCannotBeImportedAndHashVersionIntegerChecksRemainStrict()throws Exception {
        assertThrows(IllegalArgumentException.class,()->read(candidate().toString()));assertThrows(IllegalArgumentException.class,()->read(reference().put("digest","0".repeat(64)).toString()));
        assertThrows(IllegalArgumentException.class,()->read(signed(reference().put("schemaVersion",2)).toString()));
        JSONObject fractional=reference();fractional.getJSONObject("application").put("versionCode",7.5);signed(fractional);assertThrows(IllegalArgumentException.class,()->PortablePhoneRouteReference.checked(fractional));
        JSONObject unsafe=reference();unsafe.getJSONObject("application").put("packageName","https://account:token@example.test");signed(unsafe);assertThrows(IllegalArgumentException.class,()->PortablePhoneRouteReference.checked(unsafe));
    }
    @Test public void originalFourStepLimitAndNavigationOnlyToolSubsetStayClosed()throws Exception {
        JSONObject local=candidate();JSONArray steps=local.getJSONArray("steps");for(int i=1;i<4;i++)steps.put(new JSONObject(steps.getJSONObject(0).toString()));signed(local);assertEquals(4,PortablePhoneRouteReference.fromLocalCandidate(local).getJSONArray("steps").length());
        steps.put(new JSONObject(steps.getJSONObject(0).toString()));signed(local);assertThrows(IllegalArgumentException.class,()->PortablePhoneRouteReference.fromLocalCandidate(local));
        JSONObject input=candidate();input.getJSONArray("steps").getJSONObject(0).put("tool","phone_type").put("text","SECRET");signed(input);assertThrows(IllegalArgumentException.class,()->PortablePhoneRouteReference.fromLocalCandidate(input));
        JSONObject dangerous=candidate();dangerous.getJSONArray("steps").getJSONObject(0).getJSONObject("selector").put("textExact","确认付款");signed(dangerous);assertThrows(IllegalArgumentException.class,()->PortablePhoneRouteReference.fromLocalCandidate(dangerous));
    }
    @Test public void boundedStrictJsonRejectsDuplicateTrailingDeepMalformedAndOversizeInput()throws Exception {
        String text=reference().toString();assertThrows(IllegalArgumentException.class,()->read(text+" {}"));assertThrows(IllegalArgumentException.class,()->read(text.replace("\"schemaVersion\":1","\"schemaVersion\":1,\"schemaVersion\":1")));
        assertThrows(IllegalArgumentException.class,()->read("{\"a\":"+"[".repeat(34)+"0"+"]".repeat(34)+"}"));assertThrows(IllegalArgumentException.class,()->read(" ".repeat(PortablePhoneRouteReference.MAX_BYTES+1)));
        assertThrows(java.nio.charset.CharacterCodingException.class,()->PortablePhoneRouteReference.read(new ByteArrayInputStream(new byte[]{(byte)0xc3,0x28}),new RunControl()));
    }
    @Test public void cancelledOriginalReadDoesNotEnterTheSelectedStream()throws Exception {
        RunControl control=new RunControl();control.cancel();int[] reads={0};InputStream selected=new InputStream(){public int read(){reads[0]++;return -1;}};
        assertThrows(InterruptedException.class,()->PortablePhoneRouteReference.read(selected,control));assertEquals(0,reads[0]);
    }
}
