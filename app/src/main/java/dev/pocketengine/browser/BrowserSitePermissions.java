package dev.pocketengine.browser;

import java.util.LinkedHashSet;

/** Closed native resource mapping. A future WebView resource never inherits an existing approval. */
public final class BrowserSitePermissions {
    public final String[] webResources, androidPermissions;
    public final String ownerLabel;
    private BrowserSitePermissions(String[] web, String[] android, String label) {
        webResources=web;androidPermissions=android;ownerLabel=label;
    }
    public static BrowserSitePermissions media(String[] requested) {
        if(requested==null||requested.length==0||requested.length>2)throw new SecurityException("不支持此设备权限组合");
        LinkedHashSet<String> web=new LinkedHashSet<>(),android=new LinkedHashSet<>(),labels=new LinkedHashSet<>();
        for(String resource:requested){
            if("android.webkit.resource.VIDEO_CAPTURE".equals(resource)){web.add("android.webkit.resource.VIDEO_CAPTURE");android.add("android.permission.CAMERA");labels.add("相机");}
            else if("android.webkit.resource.AUDIO_CAPTURE".equals(resource)){web.add("android.webkit.resource.AUDIO_CAPTURE");android.add("android.permission.RECORD_AUDIO");labels.add("麦克风");}
            else throw new SecurityException("此网页请求的设备权限尚不支持");
        }
        if(web.size()!=requested.length)throw new SecurityException("网页请求的设备权限重复");
        return new BrowserSitePermissions(web.toArray(new String[0]),android.toArray(new String[0]),String.join("、",labels));
    }
    public static BrowserSitePermissions location() {
        return new BrowserSitePermissions(new String[]{"native.geolocation"},new String[]{"android.permission.ACCESS_COARSE_LOCATION"},"大致位置");
    }
}
