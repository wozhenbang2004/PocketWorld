package dev.pocketengine.features.local.preview;

import android.content.Context;
import android.os.Looper;
import android.webkit.WebView;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import dev.pocketengine.features.local.runtime.ProjectDevSession;
import dev.pocketengine.features.workspace.JsonFiles;
import java.io.File;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Small provider-resource adapter, not user browser profiles or execution authority.
 * Loaded profiles may require a real process restart before provider cleanup is possible. */
final class ProjectLivePreviewProfiles {
    private static final Set<String> loaded=new HashSet<>();
    private ProjectLivePreviewProfiles(){}
    static void bindFirst(Context context,WebView fresh,ProjectDevSession.Observed observed)throws Exception {
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("活预览必须由原生主线程打开");
        if(!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))throw new IllegalStateException("此WebView提供者不支持独立资料，未借用默认或账号浏览器。可继续看日志、修改和固定成果；活页面待支持的提供者");
        File file=new File(context.getNoBackupFilesDir(),"project-live-preview-profiles-v1.json");
        if(java.nio.file.Files.isSymbolicLink(file.toPath())||file.length()>8192)throw new SecurityException("活预览资源登记不可核对，未使用默认资料");
        JSONObject book=file.isFile()?JsonFiles.read(file):new JSONObject().put("format","pocketworld.project-preview-profiles.v1").put("pending",new JSONArray());
        if(!"pocketworld.project-preview-profiles.v1".equals(book.optString("format")))throw new SecurityException("活预览资源登记版本不支持");
        JSONArray before=book.getJSONArray("pending"),pending=new JSONArray();Set<String> known=new HashSet<>();
        if(before.length()>8)throw new IllegalStateException("活预览资源登记超限，请先核对本机资源");
        for(int i=0;i<before.length();i++){
            String name=before.getString(i);if(!name.matches("pw_dev_[a-f0-9]{32}")||!known.add(name))throw new SecurityException("活预览资源登记不是本域资料");
            boolean absent=false;if(!loaded.contains(name))try{
                // true is an asynchronous provider request, not proof of deletion.
                absent=!ProfileStore.getInstance().deleteProfile(name);
            }catch(RuntimeException unavailable){/* Retain the exact pending name. */}
            if(!absent)pending.put(name);
        }
        String name="pw_dev_"+observed.reference.operationId().replace("-","");boolean found=false;for(int i=0;i<pending.length();i++)found|=name.equals(pending.getString(i));
        if(!found){if(pending.length()>=8)throw new IllegalStateException("本进程的活预览资料待提供者清理；请结束原服务并真正重开App后再试。固定成果和文件仍保留");pending.put(name);}
        book.put("pending",pending);JsonFiles.write(file,book);
        // Conservatively mark before calling the provider: a throwing call may have loaded it.
        loaded.add(name);WebViewCompat.setProfile(fresh,name);
        var profile=WebViewCompat.getProfile(fresh);if(!name.equals(profile.getName()))throw new SecurityException("活预览资料绑定未确认");
        profile.getCookieManager().setAcceptCookie(false);
        profile.getCookieManager().setAcceptThirdPartyCookies(fresh,false);
    }
}
