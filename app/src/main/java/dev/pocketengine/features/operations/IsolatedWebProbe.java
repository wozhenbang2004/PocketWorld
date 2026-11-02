package dev.pocketengine.features.operations;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.*;
import dev.pocketengine.core.RunControl;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Real system WebView read/click/read against one local page, with no native bridge or network. */
final class IsolatedWebProbe implements CompatibilityCheckRunner.Probe {
    private final Context context;private final Handler main=new Handler(Looper.getMainLooper());private final AtomicBoolean closed=new AtomicBoolean();private volatile WebView web;
    IsolatedWebProbe(Context context){this.context=context;}
    public JSONObject run(RunControl original)throws Exception{
        CompletableFuture<JSONObject> result=new CompletableFuture<>();
        main.post(()->{
            if(closed.get()||original.isCancelled()){result.completeExceptionally(new InterruptedException());return;}
            try{
                web=new WebView(context);WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setDomStorageEnabled(false);settings.setBlockNetworkLoads(true);
                web.setWebViewClient(new WebViewClient(){
                    @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return true;}
                    @Override public void onPageFinished(WebView view,String url){
                        if(closed.get()||original.isCancelled()){result.completeExceptionally(new InterruptedException());return;}
                        view.evaluateJavascript("(function(){var before=document.getElementById('state').textContent;document.getElementById('probe').click();return JSON.stringify({readBefore:before==='pocketworld-ready',readAfter:document.getElementById('state').textContent==='pocketworld-clicked'});})()",raw->{
                            try{if(closed.get()||original.isCancelled())throw new InterruptedException();Object decoded=new JSONTokener(raw).nextValue();JSONObject observed=new JSONObject((String)decoded);result.complete(observed.put("status",observed.optBoolean("readBefore")&&observed.optBoolean("readAfter")?"passed":"failed").put("page","isolated_local_fixture").put("networkUsed",false));}catch(Exception invalid){result.completeExceptionally(invalid);}
                        });
                    }
                });
                web.loadDataWithBaseURL("https://pocketworld-selfcheck.invalid/","<!doctype html><meta charset='utf-8'><p id='state'>pocketworld-ready</p><button id='probe' onclick=\"document.getElementById('state').textContent='pocketworld-clicked'\">Test</button>","text/html","UTF-8",null);
            }catch(Exception unavailable){result.completeExceptionally(unavailable);}
        });
        try{return result.get(12000,TimeUnit.MILLISECONDS);}finally{close();}
    }
    public void close()throws Exception{
        closed.set(true);CompletableFuture<Void> disposed=new CompletableFuture<>();Runnable release=()->{WebView owned=web;web=null;if(owned!=null){owned.stopLoading();owned.setWebViewClient(new WebViewClient());owned.removeAllViews();owned.destroy();}disposed.complete(null);};
        if(Looper.myLooper()==Looper.getMainLooper())release.run();else main.post(release);disposed.get(2000,TimeUnit.MILLISECONDS);
    }
}
