package dev.pocketengine.browser;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebBackForwardList;
import android.webkit.WebHistoryItem;
import android.webkit.WebSettings;
import android.webkit.WebView;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Real per-WebView settings, history and layout; no desktop OS, CDP or fake viewport emulation. */
public final class BrowserSiteMode {
    private BrowserSiteMode() { }
    static String desktopUserAgent(String actualDefault){
        if(actualDefault==null||actualDefault.length()>2000||!actualDefault.contains("Mozilla/"))throw new IllegalArgumentException("原WebView默认UA不可核对");
        return actualDefault.replaceFirst("\\([^)]*\\)","(X11; Linux x86_64)").replace(" Mobile","").replace(" Version/4.0","");
    }
    public static void validateResize(JSONObject args)throws Exception {for(String key:new String[]{"width","height"}){Object value=args.get(key);if(!(value instanceof Number)||args.getDouble(key)!=args.getInt(key)||args.getInt(key)<64||args.getInt(key)>4096)throw new IllegalArgumentException("真实网页尺寸须为64至4096的整数物理像素，且不能超过当前容器");}}
    public static String approvalSummary(String command,JSONObject args,JSONObject pin)throws Exception {
        String page="原网页："+pin.getString("pageUrl");
        if(command.equals("site_mode")){String mode=args.getString("mode");if(!Set.of("mobile","desktop").contains(mode))throw new IllegalArgumentException("网页模式无效");JSONObject original=pin.getJSONObject("siteMode");return page+"\n切换为"+(mode.equals("desktop")?"桌面":"手机")+"站点请求模式，修改当前网页的浏览器标识与宽视口设置。\n原显示区域："+original.getInt("width")+" × "+original.getInt("height")+" 物理像素。设备系统与像素密度不改变。网页组件可能因浏览器标识变化自行重新加载；不会额外再刷新一次。\n确认后收起面板、回到同一原网页。操作后重新观察，旧控件坐标失效。";}
        if(command.equals("resize")){validateResize(args);JSONObject original=pin.getJSONObject("resize").getJSONObject("layout");return page+"\n实际网页显示区域："+original.getInt("width")+" × "+original.getInt("height")+" → "+args.getInt("width")+" × "+args.getInt("height")+" 物理像素。\n当前可用容器："+original.getInt("containerWidth")+" × "+original.getInt("containerHeight")+" 物理像素；超过容器会拒绝，不虚报设备分辨率。\n确认后收起面板，在同一网页实际调整布局并回读网页视口与像素密度。旧坐标失效，需要重新观察。";}
        if(!Set.of("back","forward","reload").contains(command))throw new IllegalArgumentException("网页历史动作无效");String label=command.equals("back")?"后退":command.equals("forward")?"前进":"刷新";JSONObject history=pin.getJSONObject("navigationHistory"),target=history.optJSONObject(command.equals("reload")?"current":command);
        return page+"\n本次动作："+label+(target==null?"\n当前没有对应历史页；本次返回页面未变化，不发起导航。":"\n精确目的地："+target.getString("url")+"\n只向这一个原网页发起一次"+label+"。确认后历史项改变会拒绝；返回导航请求后仍须等待或重新读取，不代表网站已加载完成。")+"\n这次确认不会恢复旧控件引用，也不会重试未知操作。";
    }
    public static JSONObject settingsPin(WebView view)throws Exception {WebSettings s=view.getSettings();return new JSONObject().put("userAgent",s.getUserAgentString()).put("wideViewport",s.getUseWideViewPort()).put("overviewMode",s.getLoadWithOverviewMode());}
    public static JSONObject apply(WebView view,String mode,BrowserNativeInput.Check current,Runnable entered)throws Exception {
        if(!Set.of("mobile","desktop").contains(mode))throw new IllegalArgumentException("网页模式仅支持mobile/desktop");current.check();boolean desktop=mode.equals("desktop");String original=WebSettings.getDefaultUserAgent(view.getContext()),requested=desktop?desktopUserAgent(original):original;WebSettings settings=view.getSettings();
        boolean noOp=requested.equals(settings.getUserAgentString())&&settings.getUseWideViewPort()==desktop&&settings.getLoadWithOverviewMode()==desktop;
        if(!noOp){entered.run();settings.setUseWideViewPort(desktop);settings.setLoadWithOverviewMode(desktop);settings.setUserAgentString(requested);}current.check();
        return new JSONObject().put("status",noOp?"no_change":"site_mode_updated").put("accepted",true).put("noOp",noOp).put("verified",false).put("mode",mode).put("userAgentApplied",requested.equals(settings.getUserAgentString())).put("wideViewportApplied",settings.getUseWideViewPort()==desktop).put("implementation","actual_webview_ua_and_wide_viewport").put("desktopOsEmulated",false).put("uaClientHintsEmulated",false).put("automaticReloadRequested",false).put("providerMayReloadAfterIdentityChange",true).put("freshObservationRequired",true).put("automaticReplayAllowed",false);
    }
    public static JSONObject historyPin(WebView view)throws Exception {
        WebBackForwardList history=view.copyBackForwardList();int index=history==null?-1:history.getCurrentIndex(),size=history==null?0:history.getSize();
        return new JSONObject().put("index",index).put("size",size).put("current",item(history,index)).put("back",item(history,index-1)).put("forward",item(history,index+1));
    }
    private static Object item(WebBackForwardList history,int index)throws Exception {if(history==null||index<0||index>=history.getSize())return JSONObject.NULL;WebHistoryItem item=history.getItemAtIndex(index);String url=item==null?null:item.getUrl();if(url==null||url.length()>8192)throw new BrowserTaskSpace.StalePage("原网页历史目标不可核对");return new JSONObject().put("url",url).put("originalUrl",item.getOriginalUrl()==null?url:item.getOriginalUrl());}
    public static JSONObject navigateHistory(WebView view,String command,JSONObject expected,BrowserNativeInput.Check current,Runnable entered)throws Exception {
        if(!Set.of("back","forward","reload").contains(command))throw new IllegalArgumentException("网页历史动作无效");current.check();JSONObject actual=historyPin(view);
        if(expected==null||!actual.toString().equals(expected.toString()))throw new BrowserTaskSpace.StalePage("审批后原网页历史已变化");
        String key=command.equals("reload")?"current":command;JSONObject target=actual.optJSONObject(key);boolean available=target!=null&&(command.equals("reload")||command.equals("back")&&view.canGoBack()||command.equals("forward")&&view.canGoForward());
        if(!available)return new JSONObject().put("status","no_change").put("accepted",true).put("verified",false).put("noOp",true).put("code","no_"+key+"_history").put("freshObservationRequired",true).put("automaticReplayAllowed",false);
        java.net.URI url=new java.net.URI(target.getString("url"));if(url.getHost()==null||url.getUserInfo()!=null||!Set.of("http","https").contains(url.getScheme()))throw new SecurityException("历史目标不是已支持的http/https网页");
        current.check();entered.run();if(command.equals("back"))view.goBack();else if(command.equals("forward"))view.goForward();else view.reload();
        return new JSONObject().put("status","navigation_requested").put("accepted",true).put("verified",false).put("noOp",false).put("historyAction",command).put("targetUrl",target.getString("url")).put("freshObservationRequired",true).put("automaticReplayAllowed",false);
    }
    public static JSONObject resizePin(WebView view,JSONObject args)throws Exception {
        validateResize(args);if(!(view.getParent() instanceof ViewGroup))throw new IllegalStateException("原网页容器不可用");ViewGroup parent=(ViewGroup)view.getParent();int width=parent.getWidth()-parent.getPaddingLeft()-parent.getPaddingRight(),height=parent.getHeight()-parent.getPaddingTop()-parent.getPaddingBottom();
        ViewGroup.LayoutParams raw=view.getLayoutParams();if(raw instanceof ViewGroup.MarginLayoutParams){ViewGroup.MarginLayoutParams p=(ViewGroup.MarginLayoutParams)raw;width-=p.leftMargin+p.rightMargin;height-=p.topMargin+p.bottomMargin;}
        if(args.getInt("width")>width||args.getInt("height")>height)throw new IllegalArgumentException("所需真实网页尺寸超过当前可见容器，请先由主人调整设备窗口；未伪造桌面分辨率");
        return new JSONObject().put("width",view.getWidth()).put("height",view.getHeight()).put("containerWidth",width).put("containerHeight",height).put("unit","physical_pixels");
    }
    /** Wait for actual layout, then read actual CSS viewport and DPR once. Never reports the request as the result. */
    public static void resize(WebView view,JSONObject args,BrowserNativeInput.Check current,Runnable entered,CompletableFuture<JSONObject> result)throws Exception {
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("网页布局只能在原主线程调整");current.check();JSONObject before=resizePin(view,args);int width=args.getInt("width"),height=args.getInt("height");boolean noOp=view.getWidth()==width&&view.getHeight()==height;Handler handler=new Handler(Looper.getMainLooper());
        final View.OnLayoutChangeListener[] listener={null};final boolean[] probing={false},changed={false};
        Runnable timeout=()->{try{result.complete(changed[0]?BrowserNativeInput.unknown("viewport_readback_unknown"):new JSONObject().put("accepted",false).put("verified",false).put("status","unavailable").put("code","viewport_readback_unavailable"));}catch(Exception failed){result.completeExceptionally(failed);}};
        Runnable read=()->{if(result.isDone()||probing[0]||view.getWidth()!=width||view.getHeight()!=height)return;try{current.check();probing[0]=true;view.evaluateJavascript("JSON.stringify({width:innerWidth,height:innerHeight,dpr:devicePixelRatio,visualWidth:visualViewport?visualViewport.width:innerWidth,visualHeight:visualViewport?visualViewport.height:innerHeight,scale:visualViewport?visualViewport.scale:1})",raw->{if(result.isDone())return;try{current.check();if(view.getWidth()!=width||view.getHeight()!=height)throw new BrowserTaskSpace.StalePage("原网页布局再次变化");Object decoded=new JSONTokener(raw).nextValue();JSONObject css=new JSONObject(decoded instanceof String?(String)decoded:raw);for(String key:new String[]{"width","height","dpr","visualWidth","visualHeight","scale"})if(!Double.isFinite(css.getDouble(key))||css.getDouble(key)<=0)throw new IllegalArgumentException("真实网页视口回查无效");result.complete(new JSONObject().put("status",noOp?"no_change":"viewport_resized").put("accepted",true).put("verified",false).put("noOp",noOp).put("before",before).put("actualWidth",view.getWidth()).put("actualHeight",view.getHeight()).put("unit","physical_pixels").put("cssViewport",css).put("cssEvidenceTrust","page_world_untrusted").put("devicePixelRatioEmulated",false).put("freshObservationRequired",true).put("automaticReplayAllowed",false));}catch(Exception failed){timeout.run();}});}catch(Exception failed){timeout.run();}};
        listener[0]=(v,l,t,r,b,ol,ot,or,ob)->read.run();view.addOnLayoutChangeListener(listener[0]);result.whenComplete((value,error)->{Runnable remove=()->{view.removeOnLayoutChangeListener(listener[0]);handler.removeCallbacks(timeout);};if(Looper.myLooper()==Looper.getMainLooper())remove.run();else handler.post(remove);});handler.postDelayed(timeout,1500);
        if(!noOp){current.check();entered.run();changed[0]=true;ViewGroup.LayoutParams params=view.getLayoutParams();params.width=width;params.height=height;view.setLayoutParams(params);view.requestLayout();}read.run();
    }
}
