package dev.pocketengine.browser;

import android.content.Context;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebView;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Main-thread AndroidX adapter. No API exposes raw cookies to tools, events or callers. */
public final class BrowserProfiles {
    private static final Map<String,BrowserProfiles> INSTANCES=new HashMap<>();
    private final BrowserProfileRegistry registry;
    private final boolean supported;
    private final String featureCheck;
    private final Set<String> loadedNames=new HashSet<>();
    private BrowserProfiles(Context context)throws Exception {
        boolean feature=false;String check="unsupported";
        try{feature=WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE);check=feature?"supported":"unsupported";}catch(RuntimeException|LinkageError unavailable){check="provider_check_unavailable";}
        supported=feature;featureCheck=check;registry=new BrowserProfileRegistry(new File(context.getNoBackupFilesDir(),"browser-profiles.json"));
        cleanupPending();
    }
    public static synchronized BrowserProfiles get(Context context)throws Exception {
        main();String path=context.getNoBackupFilesDir().getAbsolutePath();BrowserProfiles profiles=INSTANCES.get(path);
        if(profiles==null){profiles=new BrowserProfiles(context.getApplicationContext());INSTANCES.put(path,profiles);}return profiles;
    }
    public JSONObject snapshot()throws Exception {main();JSONObject state=registry.snapshot(supported).put("featureCheck",featureCheck).put("cookieExportAvailable",false).put("systemChromeSessionAvailable",false);
        android.content.pm.PackageInfo provider=WebView.getCurrentWebViewPackage();if(provider!=null)state.put("providerPackage",provider.packageName).put("providerVersion",provider.versionName);return state;
    }
    public String selectedId(){main();return registry.selectedId();}
    public void requireRevision(long expected){main();registry.requireRevision(expected);}
    public JSONObject createForOwner(String label,long expected)throws Exception {main();registry.create(label,expected,supported);return snapshot();}
    public JSONObject selectForOwner(String id,long expected)throws Exception {main();registry.select(id,expected,supported);return snapshot();}
    public JSONObject deleteForOwner(String id,long expected)throws Exception {main();registry.requestDelete(id,expected,supported);return snapshot();}
    public JSONArray historyForOwner(BrowserProfileRegistry.Session session)throws Exception {main();return registry.historyForOwner(session);}
    public JSONObject recordOwnerVisit(BrowserProfileRegistry.Session session,String url,String title,boolean closed)throws Exception {main();return registry.visitForOwner(session,url,title,System.currentTimeMillis(),closed);}
    public JSONObject historyItemForOwner(BrowserProfileRegistry.Session session,String id)throws Exception {main();return registry.historyItemForOwner(session,id);}
    public String historyUrlForOwner(BrowserProfileRegistry.Session session,String id)throws Exception {main();return registry.historyUrlForOwner(session,id);}
    public boolean exactUrlsForOwner(BrowserProfileRegistry.Session session)throws Exception {main();return registry.exactUrlsForOwner(session);}
    public void exactUrlsForOwner(BrowserProfileRegistry.Session session,boolean enabled)throws Exception {main();registry.exactUrlsForOwner(session,enabled);}
    public JSONObject recoveryPageForOwner(BrowserProfileRegistry.Session session,String taskId,String logicalId,String url,String title,String state,String openedBy)throws Exception {main();return registry.recoveryPageForOwner(session,taskId,logicalId,url,title,state,openedBy);}
    public String recoveryUrlForOwner(BrowserProfileRegistry.Session session,String taskId,JSONObject page)throws Exception {main();return registry.recoveryUrlForOwner(session,taskId,page);}
    public boolean bookmarkForOwner(BrowserProfileRegistry.Session session,String id)throws Exception {main();return registry.bookmarkForOwner(session,id);}
    public void clearHistoryForOwner(BrowserProfileRegistry.Session session,boolean bookmarksToo)throws Exception {main();registry.clearHistoryForOwner(session,bookmarksToo);}
    public BrowserProfileRegistry.Session openSession()throws Exception {main();return registry.openSession(supported);}
    public void endSession(BrowserProfileRegistry.Session session){main();registry.endSession(session);}
    public void bindFreshWebView(WebView view,BrowserProfileRegistry.Session session)throws Exception {
        main();if(!session.current())throw new SecurityException("浏览器资料已变化");
        // This is intentionally the first operation on a newly constructed WebView.
        if(session.multiProfile){WebViewCompat.setProfile(view,session.providerName);loadedNames.add(session.providerName);}
    }
    public JSONObject startDownload(Context context,WebView view,BrowserProfileRegistry.Session session,String url,String userAgent,String name,String mime,JSONObject identity,java.util.function.Consumer<JSONObject> updates)throws Exception {
        return startDownload(context,view,session,url,userAgent,name,mime,identity,session,updates);
    }
    public JSONObject startDownload(Context context,WebView view,BrowserProfileRegistry.Session session,String url,String userAgent,String name,String mime,JSONObject identity,BrowserTransferFence fence,java.util.function.Consumer<JSONObject> updates)throws Exception {
        main();CookieManager manager=cookieManagerForDownload(view,session);
        fence.check();return BrowserDownloads.get(context).start(url,userAgent,target->{fence.check();if(!session.current())throw new SecurityException("下载会话已失效");return manager.getCookie(target);},name,mime,identity,session,fence,updates);
    }
    private CookieManager cookieManagerForDownload(WebView view,BrowserProfileRegistry.Session session)throws Exception {
        main();if(session==null||!session.current())throw new SecurityException("下载所属浏览器会话已失效");
        CookieManager manager;
        if(session.multiProfile){loadedNames.add(session.providerName);androidx.webkit.Profile profile=WebViewCompat.getProfile(view);if(!session.providerName.equals(profile.getName()))throw new SecurityException("下载资料与网页不一致");manager=profile.getCookieManager();}
        else manager=CookieManager.getInstance();
        return manager;
    }
    /** Deletion by name only: never call getProfile/getOrCreateProfile before deleting a tombstone. */
    public JSONObject cleanupPending()throws Exception {
        main();if(supported){JSONArray pending=registry.pendingDeletes();for(int i=0;i<pending.length();i++){
            JSONObject profile=pending.getJSONObject(i);String id=profile.getString("id"),name=profile.getString("providerName");
            if(loadedNames.contains(name)){registry.deletionResult(id,"awaiting_process_restart",false);continue;}
            try{boolean deleted=ProfileStore.getInstance().deleteProfile(name);registry.deletionResult(id,deleted?"provider_deletion_accepted_async":"provider_profile_absent",true);}
            catch(IllegalStateException stillLoaded){registry.deletionResult(id,"awaiting_process_restart",false);}
            catch(RuntimeException unavailable){registry.deletionResult(id,"provider_cleanup_unavailable",false);}
        }}return registry.snapshot(supported);
    }
    static void main(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("浏览器资料操作必须由原生主线程执行");}
}
