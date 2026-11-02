package dev.pocketengine.browser;

import android.webkit.WebSettings;
import android.webkit.WebView;
import java.util.Set;
import org.json.JSONObject;

/** Explicit per-live-WebView request identity; this does not claim desktop OS/CDP emulation. */
public final class BrowserSiteMode {
    private BrowserSiteMode() { }
    static String desktopUserAgent(String actualDefault){
        if(actualDefault==null||actualDefault.length()>2000||!actualDefault.contains("Mozilla/"))throw new IllegalArgumentException("原WebView默认UA不可核对");
        return actualDefault.replaceFirst("\\([^)]*\\)","(X11; Linux x86_64)").replace(" Mobile","").replace(" Version/4.0","");
    }
    public static JSONObject apply(WebView view,String mode,BrowserNativeInput.Check current)throws Exception {
        if(!Set.of("mobile","desktop").contains(mode))throw new IllegalArgumentException("网页模式仅支持mobile/desktop");current.check();boolean desktop=mode.equals("desktop");String original=WebSettings.getDefaultUserAgent(view.getContext()),requested=desktop?desktopUserAgent(original):original;WebSettings settings=view.getSettings();
        settings.setUseWideViewPort(desktop);settings.setLoadWithOverviewMode(desktop);settings.setUserAgentString(requested);current.check();
        return new JSONObject().put("status","site_mode_updated").put("accepted",true).put("verified",false).put("mode",mode).put("userAgentApplied",requested.equals(settings.getUserAgentString())).put("wideViewportApplied",settings.getUseWideViewPort()==desktop).put("implementation","actual_webview_ua_and_wide_viewport").put("desktopOsEmulated",false).put("uaClientHintsEmulated",false).put("automaticReloadRequested",false).put("providerMayReloadAfterIdentityChange",true).put("freshObservationRequired",true).put("automaticReplayAllowed",false);
    }
}
