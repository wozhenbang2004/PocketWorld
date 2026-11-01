package dev.pocketengine.browser;

import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Finite native WebChromeClient feedback. No page text, prompt value or Android permission is returned. */
public final class BrowserSiteRequirement {
    private BrowserSiteRequirement() { }
    public static JSONObject create(JSONObject nativeIdentity,long navigation,String documentId,String origin,String[] resources,String reason)throws Exception {
        if(!Set.of("popup_not_supported","site_permission_denied","geolocation_denied","site_request_cancelled","dialog_context_unavailable").contains(reason)||navigation<0)throw new IllegalArgumentException("站点请求类型无效");
        BrowserTaskSpace.uuid(documentId);JSONObject out=new JSONObject();for(String key:new String[]{"taskId","runId","epoch","profileId","profileRevision","profileIsolation","browserSessionId","browserSpaceId","tabId","tabGeneration"})if(nativeIdentity.has(key))out.put(key,nativeIdentity.get(key));Set<String> types=new LinkedHashSet<>();
        if(reason.equals("geolocation_denied"))types.add("location");else if(reason.equals("popup_not_supported"))types.add("popup");else if(reason.equals("dialog_context_unavailable"))types.add("javascript_dialog");
        for(int i=0;resources!=null&&i<resources.length&&i<16;i++)types.add(switch(resources[i]==null?"":resources[i]){case "android.webkit.resource.VIDEO_CAPTURE"->"camera";case "android.webkit.resource.AUDIO_CAPTURE"->"microphone";case "android.webkit.resource.PROTECTED_MEDIA_ID"->"protected_media";case "android.webkit.resource.MIDI_SYSEX"->"midi_sysex";default->"other_device_request";});
        if(types.isEmpty())types.add("unspecified_site_request");String actual=null;try{actual=BrowserFrameOrigins.exact(origin);}catch(Exception unavailable){/* No guessed origin when a provider supplies no verifiable origin. */}
        return out.put("type","browser_page_requirement").put("status",reason.equals("site_request_cancelled")?"cancelled":"blocked").put("reason",reason).put("navigation",navigation).put("documentId",documentId)
                .put("origin",actual==null?JSONObject.NULL:actual).put("originVerified",actual!=null).put("requestTypes",new JSONArray(types)).put("resourcesOmitted",resources!=null&&resources.length>16)
                .put("hardwareAuthorizationGranted",false).put("retryPerformed",false).put("verifiedBusinessSuccess",false).put("observedAt",System.currentTimeMillis());
    }
}
