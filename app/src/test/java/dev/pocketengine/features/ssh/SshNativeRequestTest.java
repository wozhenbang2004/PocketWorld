package dev.pocketengine.features.ssh;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SshNativeRequestTest {
    private JSONObject base()throws Exception{return new JSONObject().put("profileId","original").put("_profileBinding","b".repeat(64));}
    @Test public void nativeCommandViewIsDefensiveAndStoresDigestNotCommandBody()throws Exception {
        JSONObject input=base().put("command","printf fixture-private-body").put("timeoutSeconds",60);SshNativeRequest nativeValue=SshNativeRequest.capture("exec",input);JSONObject view=nativeValue.view();assertFalse(view.toString().contains("fixture-private-body"));String pinned=nativeValue.requestHash();view.put("requestHash","forged");input.put("command","other");assertEquals(pinned,nativeValue.view().getString("requestHash"));assertNotEquals(pinned,SshNativeRequest.describe("exec",input).getString("requestHash"));
    }
    @Test public void pathVersionSourceAndConfigurationChangesNeverKeepTheNativeScopeHash()throws Exception {
        JSONObject input=base().put("path","blob.bin").put("expectedVersion","absent").put("attachmentId","01234567-0123-0123-0123-0123456789ab").put("expectedSha256","a".repeat(64)).put("acceptCooperativeWrite",true);String original=SshNativeRequest.describe("uploadFile",input).getString("requestHash");
        for(JSONObject changed:new JSONObject[]{new JSONObject(input.toString()).put("path","other.bin"),new JSONObject(input.toString()).put("expectedVersion","sha256:"+"c".repeat(64)),new JSONObject(input.toString()).put("expectedSha256","c".repeat(64)),new JSONObject(input.toString()).put("_profileBinding","c".repeat(64)),new JSONObject(input.toString()).put("timeoutSeconds",200)})assertNotEquals(original,SshNativeRequest.describe("uploadFile",changed).getString("requestHash"));
        assertThrows(SecurityException.class,()->SshNativeRequest.describe("uploadFile",new JSONObject(input.toString()).put("acceptCooperativeWrite",false)));assertThrows(IllegalArgumentException.class,()->SshNativeRequest.describe("uploadFile",new JSONObject(input.toString()).put("path","../outside")));
    }
    @Test public void oldTextWritesKeepTheirByteBoundAndBodiesNeverBecomeMetadata()throws Exception {
        JSONObject input=base().put("path","old.txt").put("content","private text content").put("expectedVersion","absent").put("acceptCooperativeWrite",true);JSONObject described=SshNativeRequest.describe("writeFile",input);assertEquals(20,described.getInt("contentBytes"));assertFalse(described.toString().contains("private text content"));assertThrows(IllegalArgumentException.class,()->SshNativeRequest.describe("writeFile",new JSONObject(input.toString()).put("content","x".repeat(262145))));assertFalse(SshNativeRequest.guarded("approveHostKey"));
    }
}
