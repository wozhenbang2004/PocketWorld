package dev.pocketengine;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.net.URLEncoder;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import dev.pocketengine.browser.BrowserLease;
import dev.pocketengine.browser.BrowserPageDialog;
import dev.pocketengine.browser.BrowserSiteRequirement;
import dev.pocketengine.browser.BrowserVisualCapture;
import dev.pocketengine.core.NativeToolObservation;
import dev.pocketengine.browser.BrowserDownloads;
import dev.pocketengine.browser.BrowserProfiles;
import dev.pocketengine.browser.BrowserTargetCondition;
import dev.pocketengine.browser.BrowserProfileRegistry;
import dev.pocketengine.browser.BrowserUploads;
import dev.pocketengine.browser.BrowserTaskSpace;
import dev.pocketengine.browser.BrowserFrameObserver;
import dev.pocketengine.browser.BrowserSemanticRead;
import dev.pocketengine.browser.BrowserTaskCapabilityPort;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import dev.pocketengine.features.attachments.AttachmentRepository;
import org.json.JSONObject;
import org.json.JSONArray;
import dev.pocketengine.store.TaskStore;

/** A real browsing surface, isolated from the local application's Native bridge. */
public final class BrowserActivity extends ComponentActivity {
    public interface OwnerPanel extends AutoCloseable {void show();default boolean backToPage(){return false;}default boolean obscuresPage(){return false;}}
    public interface OwnerPanelInstaller {OwnerPanel install(BrowserActivity activity,android.view.ViewGroup container)throws Exception;}
    private static volatile OwnerPanelInstaller ownerPanelInstaller;
    private OwnerPanel ownerPanel;
    public static void setOwnerPanelInstaller(OwnerPanelInstaller installer){ownerPanelInstaller=installer;}
    public void showResearchPanelForOwner(){requireMain();if(ownerPanel!=null)ownerPanel.show();}
    public void showResearchPanelForOwner(String taskId){requireMain();if(!taskId.isEmpty())BrowserTaskSpace.uuid(taskId);getIntent().putExtra("browserResearchTaskId",taskId);showResearchPanelForOwner();}
    public String researchTaskContextForOwner(){requireMain();String task=getIntent().getStringExtra("browserResearchTaskId");if(task==null)return "";if(!task.isEmpty())BrowserTaskSpace.uuid(task);return task;}
    public void returnToTaskForOwner(String taskId){requireMain();BrowserTaskSpace.uuid(taskId);returnToOwner(taskId);}
    private void returnToOwner(String taskId){
        requireMain();android.content.Intent original=getPackageManager().getLaunchIntentForPackage(getPackageName());if(original==null)throw new IllegalStateException("原任务应用入口不可用；当前网页仍保留");
        original.removeExtra("quickPanelTaskId");if(!taskId.isEmpty())original.putExtra("quickPanelTaskId",taskId);
        hideBrowserIme();startActivity(original.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK|android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }
    private void returnToOwnerForUi(){
        try{String task=researchTaskContextForOwner();if(task.isEmpty()&&taskSpace!=null)task=taskSpace.binding.taskId;returnToOwner(task);}
        catch(RuntimeException unavailable){if(message!=null)message.setText("原任务入口暂不可用；网页仍保留，请稍后重试。");}
    }
    @Override public void onNewIntent(android.content.Intent intent){super.onNewIntent(intent);setIntent(intent);if(intent.getBooleanExtra("showResearch",false))showResearchPanelForOwner();}
    private static volatile WeakReference<BrowserActivity> current=new WeakReference<>(null);
    private static volatile java.util.function.Consumer<JSONObject> events;
    private volatile WebView web;
    private final java.util.Map<String,Page> pages=new java.util.concurrent.ConcurrentHashMap<>();
    private volatile Page activePage;
    private BrowserTaskSpace taskSpace;
    private java.util.concurrent.atomic.AtomicReference<JSONObject> taskLease;
    private boolean closingProfile;
    private static final int MAX_ALL_PAGES=8;
    private static final class Page {
        final String id;final WebView view;final BrowserProfileRegistry.Session profile;final boolean taskCreated;BrowserFrameObserver readObserver;dev.pocketengine.browser.BrowserNetworkWitness networkWitness;AutoCloseable networkOwnership;
        volatile String nativeOperationId="";volatile CompletableFuture<JSONObject> nativeCall;
        boolean nativeInputDispatching,artifactChooserRetired;Runnable popupCancelTransport;String popupParentId;dev.pocketengine.browser.BrowserPopupRoute popupRoute;volatile BrowserTaskSpace.Tab taskOwner;BrowserTaskSpace handoffSpace;JSONObject handoffIdentity;BrowserTaskSpace.Gate taskGate;volatile long navigation;boolean loading;String observationId,observationUrl;long observationAt;
        Page(String id,WebView view,BrowserProfileRegistry.Session profile,BrowserTaskSpace.Tab owner){this.id=id;this.view=view;this.profile=profile;taskCreated=owner!=null;taskOwner=owner;}
    }
    private EditText address;
    private TextView message;
    private Button previous,next;
    private LinearLayout navigationControls;
    private ProgressBar progress;
    private volatile long navigation;
    private BrowserProfiles profiles;
    private volatile BrowserProfileRegistry.Session profileSession;
    private String profileError="";
    private String observationId;
    private String observationUrl;
    private boolean loading;
    private android.widget.FrameLayout browserContainer;
    private boolean rendererRecoveryNeeded;
    private boolean browserImeVisible;
    private android.app.AlertDialog closeConfirmation;
    private NativePageDialog pageDialog;private JSONObject lastPageDialog,lastSiteNotice;private boolean ownerForeground;
    private android.app.AlertDialog popupConsent;private Page popupConsentPage;private dev.pocketengine.browser.BrowserPopupRoute.Request popupRequest;
    private record DeniedSiteRequest(Page page,long navigation,String document,String origin,String[] resources) { }
    private final java.util.Map<android.webkit.PermissionRequest,DeniedSiteRequest> deniedSiteRequests=new java.util.IdentityHashMap<>();
    private String recoveryUrl="https://www.bing.com";
    private dev.pocketengine.browser.BrowserArtifactUpload artifactUpload;private Page artifactUploadPage;
    private android.webkit.ValueCallback<android.net.Uri[]> fileChooser;
    private BrowserUploads.Destination fileChooserDestination,uploadDestination;
    private android.webkit.ValueCallback<android.net.Uri[]> uploadCallback;
    private android.app.AlertDialog downloadDialog;
    private final java.util.Map<String,JSONObject> deliveredUploads=new java.util.LinkedHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger FILE_CHOOSER_REQUESTS=new java.util.concurrent.atomic.AtomicInteger(1000);
    private int fileChooserRequest;
    private final java.util.concurrent.ExecutorService browserIo=java.util.concurrent.Executors.newSingleThreadExecutor();
    private final BrowserLease lease=new BrowserLease();
    private final String pageKey="__pocketWorld_"+UUID.randomUUID().toString().replace("-","");
    private long observationAt;
    private CompletableFuture<JSONObject> pending;
    private boolean pendingMutation;
    private final Handler leaseTimers=new Handler(Looper.getMainLooper());
    private final Runnable leaseExpiryCheck=()->scheduleLeaseExpiry(lease.snapshot());
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        getOnBackPressedDispatcher().addCallback(this,new OnBackPressedCallback(true){@Override public void handleOnBackPressed(){handleBrowserBack();}});
        getWindow().setDecorFitsSystemWindows(false);getWindow().setStatusBarColor(Color.WHITE);getWindow().setNavigationBarColor(Color.WHITE);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setBackgroundColor(Color.WHITE);
        page.setOnApplyWindowInsetsListener((view,insets)->{Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()),keyboard=insets.getInsets(WindowInsets.Type.ime());browserImeVisible=insets.isVisible(WindowInsets.Type.ime());if(navigationControls!=null)navigationControls.setVisibility(browserImeVisible?View.GONE:View.VISIBLE);view.setPadding(bars.left,bars.top,bars.right,Math.max(bars.bottom,keyboard.bottom));return insets;});
        page.setTag("browser_root");
        LinearLayout toolbar=new LinearLayout(this);toolbar.setTag("browser_toolbar");toolbar.setGravity(android.view.Gravity.CENTER_VERTICAL);toolbar.setPadding(dp(8),dp(5),dp(8),dp(5));
        Button backToOwner=button("回助手");backToOwner.setTag("browser_return_owner");backToOwner.setContentDescription("回原任务或助手，保留当前网页，不自动继续执行");backToOwner.setOnClickListener(v->returnToOwnerForUi());toolbar.addView(backToOwner);
        address=new EditText(this);address.setTag("browser_address");address.setMinimumHeight(dp(48));address.setSingleLine(true);address.setTextSize(14);address.setHint("网址或搜索内容");address.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);address.setImeOptions(EditorInfo.IME_ACTION_GO);toolbar.addView(address,new LinearLayout.LayoutParams(0,-2,1));
        Button go=button("打开");go.setOnClickListener(v->{takeover("主人已接管浏览器");navigate(address.getText().toString());hideBrowserIme();});toolbar.addView(go);page.addView(toolbar);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);page.addView(progress,new LinearLayout.LayoutParams(-1,dp(2)));progress.setVisibility(View.GONE);
        message=new TextView(this);message.setTextSize(12);message.setTextColor(0xff777777);message.setPadding(dp(14),dp(6),dp(14),dp(6));message.setText("网页操作使用独立浏览器窗口；可返回助手继续。");page.addView(message);
        browserContainer=new android.widget.FrameLayout(this);try{profiles=BrowserProfiles.get(this);}catch(Exception unavailable){profileError="浏览器资料无法读取，请先核对设置";}createWebView();
        page.addView(browserContainer,new LinearLayout.LayoutParams(-1,0,1));LinearLayout controls=new LinearLayout(this);navigationControls=controls;previous=button("上一页");previous.setOnClickListener(v->{takeover("主人已接管浏览器");if(web!=null&&web.canGoBack())web.goBack();});next=button("下一页");next.setOnClickListener(v->{takeover("主人已接管浏览器");if(web!=null&&web.canGoForward())web.goForward();});Button reload=button("刷新");reload.setOnClickListener(v->{takeover("主人已接管浏览器");if(web==null){createWebView();navigate(recoveryUrl);}else web.reload();});controls.setTag("browser_navigation");controls.addView(previous,new LinearLayout.LayoutParams(0,-2,1));controls.addView(next,new LinearLayout.LayoutParams(0,-2,1));controls.addView(reload,new LinearLayout.LayoutParams(0,-2,1));Button more=button("更多");more.setTag("browser_more");more.setOnClickListener(v->{android.widget.PopupMenu menu=new android.widget.PopupMenu(this,more);menu.getMenu().add(0,2,0,"浏览历史");menu.getMenu().add(0,3,1,"书签");menu.getMenu().add(0,4,2,"收藏当前页");menu.getMenu().add(0,5,3,"站点权限说明");menu.getMenu().add(0,1,4,"关闭浏览器并结束网页执行…");menu.setOnMenuItemClickListener(item->{switch(item.getItemId()){case 1:confirmCloseForOwner();return true;case 2:showBrowserHistory(false);return true;case 3:showBrowserHistory(true);return true;case 4:bookmarkCurrentPage();return true;case 5:showSitePermissionDetails();return true;default:return false;}});menu.show();});controls.addView(more,new LinearLayout.LayoutParams(0,-2,1));page.addView(controls);
        OwnerPanelInstaller installer=ownerPanelInstaller;if(installer!=null)try{ownerPanel=installer.install(this,page);}catch(Exception unavailable){message.setText("研究工作区暂不可用，请核对原任务；浏览页面仍可手动操作。");}
        setContentView(page);
        address.setOnEditorActionListener((view,action,event)->{if(action==EditorInfo.IME_ACTION_GO||(event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER&&event.getAction()==KeyEvent.ACTION_UP)){takeover("主人已接管浏览器");navigate(address.getText().toString());hideBrowserIme();return true;}return false;});
        lease.setExpirationListener(receipt->{leaseTimers.removeCallbacks(leaseExpiryCheck);invalidateObservation();failPending("浏览器执行授权已到期，请先核对已派发的操作");if(message!=null)message.setText(receipt.optString("message"));emitLeaseEvent(receipt);});
        current=new WeakReference<>(this);boolean sameProfile=saved!=null&&profileSession!=null&&profileSession.profileId.equals(saved.getString("browserProfileId"))&&profileSession.profileRevision==saved.getLong("browserProfileRevision")&&profileSession.isolation.equals(saved.getString("browserProfileIsolation"));Bundle browserState=sameProfile?saved.getBundle("browserState"):null;
        if(web!=null&&(browserState==null||web.restoreState(browserState)==null))navigate(sameProfile?saved.getString("browserUrl"):getIntent().getStringExtra("url"));
    }
    private void createWebView(){
        rendererRecoveryNeeded=false;if(profiles==null){lease.bindProfile(null);message.setText(profileError);return;}
        WebView fresh=null;try{if(profileSession==null||!profileSession.current()){profileSession=profiles.openSession();lease.bindProfile(profileSession);}fresh=new WebView(this);profiles.bindFreshWebView(fresh,profileSession);Page page=new Page(UUID.randomUUID().toString(),fresh,profileSession,null);pages.put(page.id,page);configureWebView(fresh);selectPage(page);profileError="";}
        catch(Exception unavailable){if(fresh!=null)fresh.destroy();profiles.endSession(profileSession);profileSession=null;web=null;lease.bindProfile(null);profileError="所选浏览器资料不可用，请主人从原生设置选择资料";message.setText(profileError);return;}
    }
    private void configureWebView(WebView target){
        Page configured=page(target);if(configured!=null){configured.readObserver=new BrowserFrameObserver(target);configured.networkWitness=new dev.pocketengine.browser.BrowserNetworkWitness(target);if(configured.taskOwner!=null)try{configured.readObserver.extraOriginsForNative(configured.taskOwner.space().frameOriginsForNative());}catch(Exception unavailable){configured.readObserver.close();throw new IllegalStateException("原任务嵌入来源配置无效");}}
        target.setBackgroundColor(Color.WHITE);target.getSettings().setJavaScriptEnabled(true);target.getSettings().setDomStorageEnabled(true);target.getSettings().setAllowFileAccess(false);target.getSettings().setAllowContentAccess(false);target.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);target.getSettings().setSupportMultipleWindows(true);target.getSettings().setJavaScriptCanOpenWindowsAutomatically(false);
        target.setWebViewClient(new WebViewClient(){
            @Override public boolean onRenderProcessGone(WebView view,android.webkit.RenderProcessGoneDetail detail){rendererGone(view);return true;}
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){Page requested=page(view);if(request.isForMainFrame()&&requested!=null&&requested.popupRoute!=null&&routePopupNavigation(requested,request.getUrl().toString()))return true;boolean allowed=allowedUrl(request.getUrl().toString());if(!allowed&&request.isForMainFrame())message.setText("这个链接需要其他应用处理，当前浏览器未打开它。");return !allowed;}
            @Override public void onPageStarted(WebView view,String url,android.graphics.Bitmap icon){Page page=page(view);if(page==null)return;cancelPageDialog(page,BrowserPageDialog.Reason.CONTEXT_CHANGED);deniedSiteRequests.values().removeIf(request->request.page==page);page.navigation++;for(Page popup:new java.util.ArrayList<>(pages.values()))if(page.id.equals(popup.popupParentId))closeOwnerPopup(popup);if(page.networkWitness!=null)page.networkWitness.documentChanged();if(page.readObserver!=null)page.readObserver.documentStarted();page.loading=true;page.observationId=null;page.observationUrl=null;page.observationAt=0;if(page.taskOwner!=null)page.taskOwner.space().navigationChangedNative(page.id);if(page.taskOwner!=null)try{page.taskOwner.space().pageStartedNative(page.id,url);}catch(Exception ignored){}if(view!=web)return;navigation=page.navigation;cancelFileChooser();invalidateObservation();failPending("网页已跳转，请重新观察");loading=true;progress.setVisibility(View.VISIBLE);message.setText("正在加载网页…");address.setText(url);updateNavigation();}
            @Override public void onPageFinished(WebView view,String url){Page page=page(view);if(page==null)return;page.loading=false;if(page.taskOwner!=null)try{page.taskOwner.space().pageLoadedNative(page.id,url,view.getTitle());}catch(Exception ignored){}if(page.taskOwner==null)recordOwnerHistory(page,false);if(view!=web)return;loading=false;progress.setVisibility(View.GONE);message.setText("网页已载入");address.setText(url);updateNavigation();}
            @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){if(view!=web)return;if(request.isForMainFrame()){loading=false;message.setText("网页加载失败，请检查地址或网络。");progress.setVisibility(View.GONE);}}
            @Override public void onReceivedSslError(WebView view,SslErrorHandler handler,SslError error){handler.cancel();if(view!=web)return;loading=false;message.setText("证书验证失败，已停止加载。");progress.setVisibility(View.GONE);}
        });
        target.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onJsAlert(WebView view,String url,String text,android.webkit.JsResult result){return showPageDialog(view,url,text,null,BrowserPageDialog.Kind.ALERT,result);}
            @Override public boolean onJsConfirm(WebView view,String url,String text,android.webkit.JsResult result){return showPageDialog(view,url,text,null,BrowserPageDialog.Kind.CONFIRM,result);}
            @Override public boolean onJsPrompt(WebView view,String url,String text,String initial,android.webkit.JsPromptResult result){return showPageDialog(view,url,text,initial,BrowserPageDialog.Kind.PROMPT,result);}
            @Override public boolean onJsBeforeUnload(WebView view,String url,String text,android.webkit.JsResult result){return showPageDialog(view,url,text,null,BrowserPageDialog.Kind.BEFORE_UNLOAD,result);}
            @Override public boolean onCreateWindow(WebView view,boolean dialog,boolean gesture,android.os.Message reply){return createOwnerPopup(page(view),gesture,reply);}
            @Override public void onCloseWindow(WebView view){Page popup=page(view);if(popup!=null&&popup.popupRoute!=null)closeOwnerPopup(popup);}
            @Override public boolean onShowFileChooser(WebView view,android.webkit.ValueCallback<android.net.Uri[]> callback,FileChooserParams params){Page chooserPage=page(view);if(chooserPage!=null&&chooserPage.artifactChooserRetired){callback.onReceiveValue(null);return true;}if(artifactUpload!=null&&artifactUploadPage==chooserPage&&artifactUpload.chooser(callback,params))return true;if(view!=web){callback.onReceiveValue(null);return true;}return chooseUpload(callback,params);}@Override public void onProgressChanged(WebView view,int value){if(view==web)progress.setProgress(value);}@Override public void onPermissionRequest(android.webkit.PermissionRequest request){DeniedSiteRequest original=null;try{Page source=page(target);if(source!=null&&source.profile.current()&&source.readObserver!=null){String origin=request.getOrigin()==null?null:request.getOrigin().toString();String[] resources=request.getResources();original=new DeniedSiteRequest(source,source.navigation,source.readObserver.documentIdentityForNative(),origin,resources==null?new String[0]:java.util.Arrays.copyOf(resources,Math.min(17,resources.length)));if(deniedSiteRequests.size()>=8)deniedSiteRequests.remove(deniedSiteRequests.keySet().iterator().next());deniedSiteRequests.put(request,original);}}catch(RuntimeException unavailable){original=null;}finally{request.deny();}if(original!=null&&deniedSiteRequests.get(request)==original)siteRequestNotice(original,"site_permission_denied");}
            @Override public void onPermissionRequestCanceled(android.webkit.PermissionRequest request){DeniedSiteRequest original=deniedSiteRequests.remove(request);if(original!=null)siteRequestNotice(original,"site_request_cancelled");}
            @Override public void onGeolocationPermissionsShowPrompt(String origin,android.webkit.GeolocationPermissions.Callback callback){callback.invoke(origin,false,false);siteNotice(page(target),"geolocation_denied",origin,new String[0]);}});
        target.setOnTouchListener((view,event)->{Page touched=page(target);if(view==web&&event.getActionMasked()==MotionEvent.ACTION_DOWN&&(touched==null||!touched.nativeInputDispatching)){if(touched!=null)touched.artifactChooserRetired=false;takeover("主人正在操作网页，自动执行已停止");}return false;});
        target.setDownloadListener((url,userAgent,disposition,mime,length)->{if(web==target)requestDownload(url,userAgent,disposition,mime,length);});
        target.setVisibility(View.GONE);browserContainer.addView(target,new android.widget.FrameLayout.LayoutParams(-1,-1));
    }
    private boolean createOwnerPopup(Page source,boolean gesture,android.os.Message reply){
        if(source==null||source!=activePage||source.popupRoute!=null||!gesture||!ownerForeground||popupConsent!=null||pageDialog!=null||fileChooser!=null||pages.size()>=MAX_ALL_PAGES||!(reply.obj instanceof WebView.WebViewTransport)){siteNotice(source,"popup_not_supported");return false;}
        WebView child=null;try{
            final long sourceNavigation=source.navigation;final String sourceDocument=source.readObserver.documentIdentityForNative();final BrowserProfileRegistry.Session profile=source.profile;
            JSONObject identity=source.taskOwner!=null?source.taskOwner.identity():source.handoffIdentity!=null?new JSONObject(source.handoffIdentity.toString()):source.profile.identity().put("tabId",source.id);
            identity.put("documentId",sourceDocument).put("navigation",sourceNavigation).put("sourceOrigin",dialogOrigin(source.view.getUrl()));
            if(source.taskOwner!=null)takeover("网页请求登录或新窗口，自动任务已暂停；请确认真实目的地，完成后关闭新窗口回原页。");
            dev.pocketengine.browser.BrowserPopupRoute route=new dev.pocketengine.browser.BrowserPopupRoute(identity,()->{if(isDestroyed()||!ownerForeground||pages.get(source.id)!=source||profileSession!=profile||!profile.current()||source.navigation!=sourceNavigation||source.readObserver==null||!sourceDocument.equals(source.readObserver.documentIdentityForNative()))throw new SecurityException("弹窗原Profile或父文档已变化");});
            child=new WebView(this);profiles.bindFreshWebView(child,profile);Page popup=new Page(UUID.randomUUID().toString(),child,profile,null);popup.popupParentId=source.id;popup.popupRoute=route;pages.put(popup.id,popup);configureWebView(child);
            WebView.WebViewTransport transport=(WebView.WebViewTransport)reply.obj;popup.popupCancelTransport=()->{try{route.cancelReply(()->{transport.setWebView(null);reply.sendToTarget();});}catch(Exception ignored){}};final WebView target=child;final java.util.concurrent.atomic.AtomicBoolean decided=new java.util.concurrent.atomic.AtomicBoolean();popupConsentPage=popup;
            popupConsent=new android.app.AlertDialog.Builder(this).setTitle("允许本次登录新窗口？").setMessage("来源页："+identity.getString("sourceOrigin")+"\n\n网页尚未向原生回调提供新窗口地址。允许后，本次人工登录窗口可使用当前资料并跟随站点重定向；真实目的地会继续显示供你核对。原任务保持暂停，关闭窗口回原页。")
                    .setNegativeButton("拒绝",(dialog,which)->{if(decided.compareAndSet(false,true)){try{route.cancelReply(()->{transport.setWebView(null);reply.sendToTarget();});}catch(Exception ignored){}closeOwnerPopup(popup);}})
                    .setPositiveButton("允许本次登录",(dialog,which)->{if(!decided.compareAndSet(false,true))return;popupConsent=null;popupConsentPage=null;try{route.reply(()->{transport.setWebView(target);reply.sendToTarget();});selectPage(popup);message.setText("人工登录新窗口已打开；可核对地址，完成后按返回键回原页，任务不会自动继续。");}catch(Exception stale){closeOwnerPopup(popup);}}).create();
            popupConsent.setOnDismissListener(dialog->{if(popupConsentPage==popup){popupConsent=null;popupConsentPage=null;}if(decided.compareAndSet(false,true)){try{route.cancelReply(()->{transport.setWebView(null);reply.sendToTarget();});}catch(Exception ignored){}closeOwnerPopup(popup);}});popupConsent.show();return true;
        }catch(Exception unavailable){if(child!=null){Page created=page(child);if(created!=null)destroyPage(created,false);else child.destroy();}siteNotice(source,"popup_not_supported");return false;}
    }
    private boolean routePopupNavigation(Page popup,String url){
        try{
            popup.popupRoute.requireCurrent();if(!allowedUrl(url)){siteNotice(popup,"popup_not_supported");return true;}if(popup.popupRoute.admitted(url))return false;
            if(popupConsent!=null)return true;dev.pocketengine.browser.BrowserPopupRoute.Request request=popup.popupRoute.request(url);JSONObject preview=request.preview();popupRequest=request;popupConsentPage=popup;
            popupConsent=new android.app.AlertDialog.Builder(this).setTitle("打开登录新窗口？").setMessage("来源页："+preview.getString("sourceOrigin")+"\n目的地："+preview.getString("destinationOrigin")+"\n\n使用当前登录资料。原生回调仅提供父页身份，未提供子框架来源。新站点仍需你确认；相机、麦克风和定位权限不会自动授权。")
                    .setNegativeButton("拒绝",(dialog,which)->{if(request.rejectForOwner())closeOwnerPopup(popup);})
                    .setPositiveButton("打开",(dialog,which)->{try{if(activePage!=popup||pages.get(popup.id)!=popup)throw new SecurityException("原弹窗已变化");String actual=request.approveForOwner();popup.view.loadUrl(actual);}catch(Exception stale){closeOwnerPopup(popup);}}).create();
            popupConsent.setOnDismissListener(dialog->{if(popupConsentPage==popup){popupConsent=null;popupConsentPage=null;popupRequest=null;}if(request.rejectForOwner())closeOwnerPopup(popup);});popupConsent.show();return true;
        }catch(Exception stale){closeOwnerPopup(popup);return true;}
    }
    private void closeOwnerPopup(Page popup){
        requireMain();if(pages.get(popup.id)!=popup||popup.popupRoute==null)return;Page parent=pages.get(popup.popupParentId);popup.popupRoute.close();destroyPage(popup,false);if(parent!=null&&pages.get(parent.id)==parent&&parent.profile==profileSession&&parent.profile.current())selectPage(parent);else selectOwnerFallback();if(message!=null)message.setText("登录新窗口已关闭；原网页和登录资料保留。请核对当前页面后交还原任务，不会自动继续。");
    }
    private Page page(WebView view){for(Page page:pages.values())if(page.view==view)return page;return null;}
    private void selectPage(Page target){
        if(activePage==target)return;cancelPageDialog(activePage,BrowserPageDialog.Reason.OWNER_LEFT);cancelFileChooser();if(downloadDialog!=null){downloadDialog.dismiss();downloadDialog=null;}failPending("已切换页面，原操作回执待核对");
        if(activePage!=null){activePage.navigation=navigation;activePage.loading=loading;activePage.observationId=observationId;activePage.observationUrl=observationUrl;activePage.observationAt=observationAt;activePage.view.setVisibility(View.GONE);activePage.view.onPause();}
        activePage=target;web=target==null?null:target.view;navigation=target==null?0:target.navigation;loading=target!=null&&target.loading;
        observationId=target==null?null:target.observationId;observationUrl=target==null?null:target.observationUrl;observationAt=target==null?0:target.observationAt;
        if(target!=null){target.view.setVisibility(View.VISIBLE);target.view.onResume();address.setText(target.view.getUrl()==null?"":target.view.getUrl());}progress.setVisibility(loading?View.VISIBLE:View.GONE);updateNavigation();
    }
    public JSONObject tabsForOwner()throws Exception {
        requireMain();org.json.JSONArray tabs=new org.json.JSONArray();for(Page page:pages.values())tabs.put(json("tabId",page.id,"navigation",page==activePage?navigation:page.navigation,"documentId",page.readObserver==null?"":page.readObserver.documentIdentityForNative(),"openedBy",page.taskOwner==null?"owner":"task","browserSpaceId",page.taskOwner==null?"":page.taskOwner.space().spaceId,"selected",page==activePage,"url",page.view.getUrl()==null?"":page.view.getUrl(),"title",page.view.getTitle()==null?"":page.view.getTitle()));return json("tabs",tabs,"taskSpace",taskSpace==null?JSONObject.NULL:taskSpace.snapshot());
    }
    public BrowserTaskCapabilityPort attachTaskSpaceForOwner(ModelCapabilityAdapters.Binding binding,RunControl originalControl,ModelCapabilityAdapters.Authority authority,ModelCapabilityAdapters.ResourceCheck resource,BrowserTaskCapabilityPort.ApprovalPort approvals,BrowserTaskSpace.SourcePort sources,BrowserTaskSpace.NativeEvents lifecycle,CapabilityPort existing,String expectedBrowserSessionId)throws Exception {
        requireMain();if(web==null||profileSession==null||!profileSession.current()||!profileSession.sessionId.equals(expectedBrowserSessionId))throw new SecurityException("主人选择的浏览会话已变化");
        if(pageDialog!=null||fileChooser!=null||downloadDialog!=null&&downloadDialog.isShowing())throw new IllegalStateException("请先结束当前文件选择或下载确认");
        if(taskSpace!=null&&(!taskSpace.snapshot().optString("status").equals("ended")||taskSpace.snapshot().getJSONArray("tabs").length()>0))throw new IllegalStateException("已有任务浏览空间，请先结束并清理其任务页");
        java.util.Objects.requireNonNull(approvals,"需要原TaskStore审批端口");
        originalControl.check();authority.check("browser_task_open",originalControl);resource.check();
        JSONObject claim=profileSession.identity().put("taskId",binding.taskId).put("runId",binding.runId).put("principalId","owner").put("spaceId","private");
        final JSONObject acquired=lease.acquireTask(claim,()->uploadStore().taskExecutionDeadline(binding.taskId,binding.runId,binding.epoch));final java.util.concurrent.atomic.AtomicReference<JSONObject> currentLease=new java.util.concurrent.atomic.AtomicReference<>(acquired);scheduleLeaseExpiry(acquired);final BrowserProfileRegistry.Session selectedProfile=profileSession;
        BrowserTaskSpace.Ui ui=new BrowserTaskSpace.Ui(){public boolean isMain(){return Looper.myLooper()==Looper.getMainLooper();}public void post(Runnable work){if(!leaseTimers.post(work))throw new IllegalStateException("浏览器主线程不可用");}};
        BrowserTaskSpace.Driver driver=new BrowserTaskSpace.Driver(){
            private Page owned(BrowserTaskSpace.Tab tab)throws Exception {Page found=pages.get(tab.tabId);if(isDestroyed()||found==null||found.taskOwner!=tab||found.profile!=selectedProfile||profileSession!=selectedProfile||!selectedProfile.current())throw new SecurityException("任务页归属或Profile会话已变化");return found;}
            private JSONObject arguments(BrowserTaskSpace.Tab tab,JSONObject supplied)throws Exception {JSONObject args=new JSONObject(supplied.toString());JSONObject identity=tab.identity();for(java.util.Iterator<String> keys=identity.keys();keys.hasNext();){String key=keys.next();args.put(key,identity.get(key));}for(String key:new String[]{"taskId","runId","leaseId","profileId","profileRevision","browserSessionId"})args.put(key,currentLease.get().get(key));return args;}
            private void check(BrowserTaskSpace.Tab tab,BrowserTaskSpace.Gate gate)throws Exception {gate.check();tab.check();lease.require(currentLease.get());}
            public JSONObject metadata(BrowserTaskSpace.Tab tab)throws Exception {Page page=owned(tab);JSONObject out=json("documentId",page.readObserver==null?"":page.readObserver.documentIdentityForNative(),"navigation",page==activePage?navigation:page.navigation,"loading",page==activePage?loading:page.loading,"url",page.view.getUrl()==null?"":page.view.getUrl(),"title",page.view.getTitle()==null?"":page.view.getTitle());if(lastSiteNotice!=null&&page.id.equals(lastSiteNotice.optString("tabId"))&&page.navigation==lastSiteNotice.optLong("navigation",-1))out.put("siteNotice",new JSONObject(lastSiteNotice.toString()));return out;}
            public void open(BrowserTaskSpace.Tab tab,String url,BrowserTaskSpace.Gate gate)throws Exception {
                check(tab,gate);if(pages.size()>=MAX_ALL_PAGES)throw new IllegalStateException("浏览页数量已达8个，请主人关闭不再使用的页");gate.entered();WebView fresh=new WebView(BrowserActivity.this);
                try{profiles.bindFreshWebView(fresh,selectedProfile);Page page=new Page(tab.tabId,fresh,selectedProfile,tab);page.taskGate=gate;pages.put(page.id,page);configureWebView(fresh);check(tab,gate);selectPage(page);check(tab,gate);navigateForTask(url);}catch(Exception failed){Page made=pages.get(tab.tabId);if(made!=null&&made.view==fresh)destroyPage(made,false);else fresh.destroy();throw failed;}
            }
            public void select(BrowserTaskSpace.Tab tab,BrowserTaskSpace.Gate gate)throws Exception {check(tab,gate);Page page=owned(tab);page.taskGate=gate;if(activePage!=page){gate.entered();selectPage(page);}check(tab,gate);}
            public void execute(BrowserTaskSpace.Tab tab,String command,JSONObject supplied,BrowserTaskSpace.Gate gate,CompletableFuture<JSONObject> result)throws Exception {
                Page target=owned(tab);if(command.equals("observe")&&(target==activePage?loading:target.loading)){result.complete(json("ok",false,"accepted",false,"status","loading","code","page_loading","message","网页正在加载，请稍后重新读取"));return;}select(tab,gate);check(tab,gate);JSONObject input=arguments(tab,supplied);CompletableFuture<JSONObject> pageResult=new CompletableFuture<>();target.nativeOperationId=supplied.optString("_nativeBrowserOperationId");target.nativeCall=pageResult;input.remove("_nativeBrowserOperationId");
                pageResult.whenComplete((value,error)->{if(target.nativeCall==pageResult){target.nativeCall=null;target.nativeOperationId="";}});if(command.equals("wait"))waitForTaskPage(target,input,gate,pageResult);else BrowserActivity.this.execute(command,input,pageResult);
                pageResult.whenComplete((receipt,error)->{if(error!=null){result.completeExceptionally(error);return;}try{check(tab,gate);Page actual=owned(tab);JSONObject bound=new JSONObject(receipt.toString());JSONObject identity=tab.identity();for(java.util.Iterator<String> keys=identity.keys();keys.hasNext();){String key=keys.next();bound.put(key,identity.get(key));}if(command.equals("observe")){try{bound.put("nativeInputViewport",inputPagePin(actual));}catch(Exception inputUnavailable){/* Ordinary observation still works when native input is unavailable. */}String actualUrl=actual.view.getUrl()==null?"":actual.view.getUrl();if(bound.optBoolean("semanticRead")&&bound.optJSONObject("frameCoverage")!=null&&bound.getJSONObject("frameCoverage").optInt("schemaVersion")==2){if(!TaskStore.capabilityFingerprint(json("url",actualUrl)).equals(bound.getJSONObject("frameCoverage").getString("parentPageIdentityDigest")))throw new BrowserTaskSpace.StalePage("嵌入网页的原宿主已跳转");}else {if(!actualUrl.equals(bound.optString("url")))throw new BrowserTaskSpace.StalePage("网页观察回调的完整目的地已变化");bound.put("url",actualUrl).put("title",actual.view.getTitle()==null?"":actual.view.getTitle());}}result.complete(bound);}catch(Exception stale){result.completeExceptionally(stale);}});
                result.whenComplete((value,error)->{if(result.isCancelled())pageResult.cancel(false);});
            }
            public boolean visualAvailable(){return true;}
            public JSONObject visualPin(BrowserTaskSpace.Tab tab,JSONObject args)throws Exception {return visualPagePin(owned(tab),args);}
            public JSONObject inputPin(BrowserTaskSpace.Tab tab,JSONObject args)throws Exception {return inputPagePin(owned(tab));}
            public void visualCapture(BrowserTaskSpace.Tab tab,JSONObject args,JSONObject pin,BrowserTaskSpace.Gate gate,CompletableFuture<BrowserVisualCapture.Frame> result)throws Exception {capturePageVisual(owned(tab),tab,args,pin,gate,result);}
            public JSONObject deliveredObservation(BrowserTaskSpace.Tab tab,JSONObject actual)throws Exception {Page page=owned(tab);if(page.readObserver==null||page.navigation!=actual.getLong("navigation"))throw new BrowserTaskSpace.StalePage("原frame交付页已变化");return page.readObserver.delivered(actual);}
            public JSONObject readFramePin(BrowserTaskSpace.Tab tab,String ref)throws Exception {Page page=owned(tab);if(page.readObserver==null)throw new BrowserTaskSpace.StalePage("原frame读取宿主不可用");return page.readObserver.framePinForNative(ref);}
            public void requireObservedFrame(BrowserTaskSpace.Tab tab,JSONObject observed)throws Exception {Page page=owned(tab);if(page.readObserver==null)throw new BrowserTaskSpace.StalePage("原frame宿主已关闭");if(observed.getString("frameId").equals("main")){if(!page.readObserver.documentIdentityForNative().equals(observed.getString("documentId")))throw new BrowserTaskSpace.StalePage("原来源文档已更换");return;}JSONObject actual=page.readObserver.framePinForNative(observed.getString("frameId"));if(!actual.getString("documentId").equals(observed.getString("documentId"))||!actual.getString("topDocumentId").equals(observed.getJSONObject("frameCoverage").getString("topDocumentId")))throw new BrowserTaskSpace.StalePage("原来源frame文档已更换");}
            public JSONObject presentedObservation(BrowserTaskSpace.Tab tab,JSONObject delivered)throws Exception {
                tab.check();Page page=owned(tab);lease.require(currentLease.get());if(page.readObserver==null||page.navigation!=delivered.getLong("navigation"))throw new BrowserTaskSpace.StalePage("只读投影的原页面已改变");return page.readObserver.presented(delivered);
            }
            public java.util.List<dev.pocketengine.browser.BrowserReadDelta.FullResync> readContextRepairs(BrowserTaskSpace.Tab tab,JSONArray messages)throws Exception {Page page=owned(tab);if(page.readObserver==null)return java.util.List.of();return page.readObserver.contextRepairs(messages,tab.tabId);}
            public void beforeModelRequest(BrowserTaskSpace.Tab tab,String id,JSONArray messages)throws Exception {Page page=owned(tab);if(page.readObserver!=null)page.readObserver.beforeModelRequest(id,messages);}
            public void afterModelResponse(BrowserTaskSpace.Tab tab,String id)throws Exception {Page page=owned(tab);if(page.readObserver!=null)page.readObserver.afterModelResponse(id);}
            public void modelRequestAbandoned(BrowserTaskSpace.Tab tab,String id)throws Exception {Page page=pages.get(tab.tabId);if(page!=null&&page.readObserver!=null)page.readObserver.modelRequestAbandoned(id);}
            public JSONObject download(BrowserTaskSpace.Tab tab,String url,String operationId,BrowserTaskSpace.Gate gate)throws Exception {
                check(tab,gate);Page page=owned(tab);JSONObject identity=tab.identity().put("operationId",operationId).put("taskId",binding.taskId).put("runId",binding.runId);
                String name=dev.pocketengine.features.attachments.AttachmentBook.displayName(android.webkit.URLUtil.guessFileName(url,null,null));
                final long downloadNavigation=page.navigation;dev.pocketengine.browser.BrowserTransferFence fence=new dev.pocketengine.browser.BrowserTransferFence(){public boolean current(){return tab.current()&&page.taskOwner==tab&&pages.get(page.id)==page&&page.navigation==downloadNavigation;}public void check()throws Exception{tab.check();gate.liveCheck();if(!current())throw new SecurityException("下载原页已被接管或跳转");}public AutoCloseable watch(Runnable close){return tab.watch(close);}};
                gate.check();gate.entered();return profiles.startDownload(BrowserActivity.this,page.view,selectedProfile,url,page.view.getSettings().getUserAgentString(),name,"application/octet-stream",identity,fence,event->runOnUiThread(()->{if(fence.current())emitLeaseEvent(event);}));
            }
            public JSONObject downloadStatus(String id,BrowserTaskSpace.Gate gate)throws Exception {gate.check();return BrowserDownloads.get(BrowserActivity.this).status(id,selectedProfile.profileId);}
            public JSONObject attachmentPin(BrowserTaskSpace.Tab tab,JSONObject args)throws Exception {owned(tab);return BrowserUploads.artifactPin(BrowserActivity.this,binding.taskId,args.getString("attachmentId"),args.getString("sha256"));}
            public void uploadAttachment(BrowserTaskSpace.Tab tab,JSONObject args,String operationId,BrowserTaskSpace.Gate gate,CompletableFuture<JSONObject> result)throws Exception {select(tab,gate);check(tab,gate);startArtifactUpload(owned(tab),tab,args,operationId,gate,result);}
            public JSONObject downloadDocument(BrowserTaskSpace.Tab tab,JSONObject args,String operationId,BrowserTaskSpace.Gate gate)throws Exception {
                check(tab,gate);Page page=owned(tab);final long version=page.navigation;final String document=page.readObserver.documentIdentityForNative(),url=page.view.getUrl();
                JSONObject identity=tab.identity().put("operationId",operationId).put("documentId",document).put("navigation",version);
                dev.pocketengine.browser.BrowserTransferFence fence=new dev.pocketengine.browser.BrowserTransferFence(){public boolean current(){return tab.current()&&pages.get(page.id)==page&&page.taskOwner==tab&&page.navigation==version&&page.readObserver!=null&&document.equals(page.readObserver.documentIdentityForNative());}public void check()throws Exception{tab.check();gate.liveCheck();if(!current())throw new SecurityException("文档下载的原页面已变化");}public AutoCloseable watch(Runnable close){return tab.watch(close);}};
                BrowserFrameObserver.Check current=()->{fence.check();if(!url.equals(page.view.getUrl()))throw new BrowserTaskSpace.StalePage("文档下载的原地址已变化");};
                gate.entered();return BrowserDownloads.get(BrowserActivity.this).startDocument(args.getString("name"),identity,selectedProfile,fence,()->dev.pocketengine.browser.BrowserDocumentInput.open(page.view,pageKey,url,args.getString("observationId"),args.getJSONObject("_nativeTargetPreview"),current::check),event->runOnUiThread(()->{if(fence.current())emitLeaseEvent(event);}));
            }
            public void adoptOwner(BrowserTaskSpace.Tab tab,BrowserTaskSpace.Gate gate)throws Exception {authority.check("browser_task_open",originalControl);resource.check();check(tab,gate);Page page=pages.get(tab.tabId);if(page==null||page.taskOwner!=null||page.profile!=selectedProfile)throw new SecurityException("只能明确移交同Profile的主人页");page.taskOwner=tab;if(page.readObserver!=null)page.readObserver.extraOriginsForNative(tab.space().frameOriginsForNative());}
            public JSONObject ownerMetadata(String tabId)throws Exception {Page page=pages.get(tabId);if(isDestroyed()||page==null||page.taskOwner!=null||page.profile!=selectedProfile||profileSession!=selectedProfile||!selectedProfile.current())throw new SecurityException("原接管页或登录资料已变化");return json("navigation",page==activePage?navigation:page.navigation,"url",page.view.getUrl()==null?"":page.view.getUrl());}
            public void returnOwner(BrowserTaskSpace.Tab tab,BrowserTaskSpace.Gate gate)throws Exception {gate.check();ownerMetadata(tab.tabId);Page page=pages.get(tab.tabId);if(page!=activePage)throw new SecurityException("请先查看要交还的原页面");page.taskOwner=tab;page.taskGate=null;page.handoffSpace=null;page.handoffIdentity=null;if(page.readObserver!=null)page.readObserver.extraOriginsForNative(tab.space().frameOriginsForNative());}
            public void releaseToOwner(BrowserTaskSpace.Tab tab)throws Exception {Page page=owned(tab);page.handoffSpace=tab.space();page.handoffIdentity=tab.identity();page.taskOwner=null;page.taskGate=null;lease.invalidate();leaseTimers.removeCallbacks(leaseExpiryCheck);invalidateObservation();failPending("主人已接管原任务页，自动浏览暂停");}
            public boolean closeOwned(BrowserTaskSpace.Tab tab)throws Exception {Page page=pages.get(tab.tabId);if(page==null||page.taskOwner!=tab)return false;destroyPage(page,false);selectOwnerFallback();return true;}
            public boolean closeOnFinish(BrowserTaskSpace.Tab tab)throws Exception {Page page=pages.get(tab.tabId);if(page==null||page.taskOwner!=tab)return false;if(page.taskCreated)return closeOwned(tab);page.taskOwner=null;page.taskGate=null;page.handoffSpace=null;page.handoffIdentity=null;if(page.readObserver!=null)page.readObserver.invalidate();selectOwnerFallback();return false;}
            public void abort(BrowserTaskSpace.Tab tab,CompletableFuture<JSONObject> operation){Page page=pages.get(tab.tabId);cancelPageDialog(page,BrowserPageDialog.Reason.CONTEXT_CHANGED);if(page==null||page.taskOwner!=tab||activePage!=page)return;try{lease.require(currentLease.get());}catch(Exception replaced){return;}if(operation!=null)commandTimedOut(operation);failPending("任务浏览已停止，请核对原页面效果");}
            public void dialogOperationSettled(BrowserTaskSpace.Tab tab,BrowserPageDialog dialog)throws Exception {Page page=pages.get(tab.tabId);if(page==null||page.handoffSpace!=tab.space())throw new SecurityException("原网页提示页已变化");if(pageDialog!=null&&pageDialog.token==dialog){pageDialog.ownHandoff=true;pageDialog.pauseRevision=tab.space().originalControl.pauseRevision();}message.setText("网页提示已处理；请核对当前页面，再从研究任务交还并继续。");}
            public void ended(){try{lease.release(currentLease.get(),"release");leaseTimers.removeCallbacks(leaseExpiryCheck);}catch(Exception noLongerOwned){}if(taskSpace!=null&&taskSpace.profile==selectedProfile)updateNavigation();}
        };
        try{taskSpace=new BrowserTaskSpace(binding,originalControl,selectedProfile,driver,ui,sources,lifecycle);taskLease=currentLease;return new BrowserTaskCapabilityPort(taskSpace,authority,resource,approvals,ui,existing);}catch(Exception failed){lease.release(acquired,"cancel");throw failed;}
    }
    public JSONObject returnTaskSpaceForOwner(JSONObject expected,RunControl original,BrowserTaskSpace.Gate resume)throws Exception {
        requireMain();if(taskSpace==null||taskSpace.originalControl!=original||taskLease==null||profileSession!=taskSpace.profile||pageDialog!=null||fileChooser!=null||uploadDestination!=null||pending!=null&&!pending.isDone()||downloadDialog!=null&&downloadDialog.isShowing())throw new SecurityException("请先处理原页面的文件选择或未决操作，再交还浏览");
        String tabId=expected.getString("tabId");if(!expected.toString().equals(taskSpace.returnPinNative(tabId).toString()))throw new SecurityException("交还目标已变化，请刷新后确认");
        final BrowserTaskSpace returning=taskSpace;JSONObject acquired=lease.acquireTask(taskSpace.profile.identity().put("taskId",taskSpace.binding.taskId).put("runId",taskSpace.binding.runId).put("principalId","owner").put("spaceId","private"),()->uploadStore().taskExecutionDeadline(returning.binding.taskId,returning.binding.runId,returning.binding.epoch));taskLease.set(acquired);
        try{JSONObject receipt=taskSpace.returnOwnerNative(expected,()->{if(original.isCancelled()||!original.isPaused()||original.pauseRevision()!=expected.getLong("pauseRevision")||profileSession!=taskSpace.profile)throw new SecurityException("原任务暂停状态已变化");});invalidateObservation();resume.check();scheduleLeaseExpiry(acquired);message.setText("已交还原任务，正在重新读取当前网页");return receipt;}
        catch(Exception failed){if(!tabId.isEmpty()&&activePage!=null&&activePage.taskOwner!=null&&activePage.taskOwner.space()==taskSpace)taskSpace.takeOverNative(tabId);else taskSpace.ownerBrowsingNative(tabId);try{lease.release(acquired,"return_failed");}catch(Exception ignored){}throw failed;}
    }
    public void adoptOwnerTabForTask(String tabId)throws Exception {requireMain();if(taskSpace==null||pageDialog!=null||fileChooser!=null||uploadDestination!=null||pending!=null&&!pending.isDone()||downloadDialog!=null&&downloadDialog.isShowing())throw new IllegalStateException("没有可移交的原任务空间，或有文件/网页操作待核对");taskSpace.adoptOwnerNative(tabId,()->{if(profileSession!=taskSpace.profile)throw new SecurityException("资料会话已变化");});}
    public void takeOverTaskTabForOwner(String tabId)throws Exception {requireMain();Page page=pages.get(tabId);if(page==null||page.taskOwner==null)throw new SecurityException("此页不属于任务");page.taskOwner.space().takeOverNative(tabId);selectPage(page);takeover("主人已接管任务页");}
    public void selectOwnerTabForOwner(String tabId)throws Exception {requireMain();Page page=pages.get(tabId);if(page==null||page.taskOwner!=null)throw new SecurityException("请选择主人自己的浏览页；任务页须明确接管");if(taskSpace!=null)taskSpace.ownerBrowsingNative(tabId);closingProfile=true;try{takeover("主人已切回自己的页面，自动浏览已暂停");}finally{closingProfile=false;}selectPage(page);}
    public JSONObject endTaskSpaceForOwner(String reason)throws Exception {requireMain();return taskSpace==null?json("status","not_active"):taskSpace.endNative(reason);}
    public JSONObject openOwnerTabForOwner(String url)throws Exception {
        requireMain();if(!allowedUrl(url)||profileSession==null||!profileSession.current())throw new SecurityException("请先选定可用资料和完整网页地址");if(pages.size()>=MAX_ALL_PAGES)throw new IllegalStateException("浏览页已达8个，请先关闭不用的页");
        WebView fresh=new WebView(this);Page page=new Page(UUID.randomUUID().toString(),fresh,profileSession,null);
        try{profiles.bindFreshWebView(fresh,profileSession);pages.put(page.id,page);configureWebView(fresh);if(taskSpace!=null)taskSpace.ownerBrowsingNative(page.id);closingProfile=true;try{takeover("主人已打开保留来源，自动浏览已暂停");}finally{closingProfile=false;}selectPage(page);navigate(url);return json("tabId",page.id,"openedBy","owner","accepted",true,"verified",false,"status","navigation_requested");}
        catch(Exception failed){if(pages.get(page.id)==page)destroyPage(page,false);else fresh.destroy();throw failed;}
    }
    public void closeOwnerTabForOwner(String tabId)throws Exception {requireMain();Page page=pages.get(tabId);if(page==null||page.taskOwner!=null)throw new SecurityException("只能明确关闭主人自己的页面");recordOwnerHistory(page,true);destroyPage(page,false);selectOwnerFallback();}
    private void requireMain(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("浏览页只能由原生主线程操作");}
    private void waitForTaskPage(Page page,JSONObject args,BrowserTaskSpace.Gate gate,CompletableFuture<JSONObject> result)throws Exception {
        if(args.getString("condition").equals("barrier")){
            final BrowserTaskSpace.Tab owner=page.taskOwner;requireWaitPage(page,owner,gate);final long version=page.navigation,leaseEpoch=lease.require(args),pause=owner.space().originalControl.pauseRevision();final String document=page.readObserver.documentIdentityForNative();
            final String epoch=profileSession.sessionId+"|"+leaseEpoch+"|"+version+"|"+pause+owner.identity().toString();
            BrowserFrameObserver.Check current=()->{requireWaitPage(page,owner,gate);if(page.navigation!=version||lease.require(args)!=leaseEpoch||owner.space().originalControl.pauseRevision()!=pause||!document.equals(page.readObserver.documentIdentityForNative()))throw new BrowserTaskSpace.StalePage("屏障等待的原文档已变化");};
            current.check();gate.entered();page.readObserver.waitBarrier(args.getJSONObject("barrier"),args.optInt("timeoutMs",3000),epoch,current,result);return;
        }
        if(args.getString("condition").equals("target")){waitForTaskTarget(page,args,gate,result);return;}
        final BrowserTaskSpace.Tab owner=page.taskOwner;final String condition=args.getString("condition"),expected=args.optString("value");final int timeout=args.optInt("timeoutMs",3000);final long deadline=android.os.SystemClock.elapsedRealtime()+timeout;
        Runnable expired=()->{if(result.isDone())return;try{requireWaitPage(page,owner,gate);result.complete(json("accepted",true,"verified",false,"status","failed","code","condition_timeout","condition",condition,"conditionMet",false,"message","限定时间内未观察到所需网页条件"));}catch(Exception stale){result.completeExceptionally(stale);}};
        Runnable poll=new Runnable(){public void run(){if(result.isDone())return;try{
            requireWaitPage(page,owner,gate);if(android.os.SystemClock.elapsedRealtime()>=deadline){expired.run();return;}gate.entered();
            if(!condition.equals("text")){boolean matched=condition.equals("load")?!(page==activePage?loading:page.loading):expected.equals(page.view.getUrl());if(matched){result.complete(json("accepted",true,"verified",false,"status","condition_observed","condition",condition,"conditionMet",true,"navigation",page.navigation,"url",page.view.getUrl()==null?"":page.view.getUrl()));return;}leaseTimers.postDelayed(this,100);return;}
            final long version=page.navigation;page.view.evaluateJavascript("JSON.stringify({__pw_task_wait:true,matched:(document.body?document.body.innerText:'').includes("+JSONObject.quote(expected)+")})",value->{if(result.isDone())return;try{
                requireWaitPage(page,owner,gate);if(version!=page.navigation){leaseTimers.postDelayed(this,100);return;}if(decoded(value).optBoolean("matched")){result.complete(json("accepted",true,"verified",false,"status","condition_observed","condition",condition,"conditionMet",true,"navigation",page.navigation,"url",page.view.getUrl()==null?"":page.view.getUrl()));return;}leaseTimers.postDelayed(this,100);
            }catch(Exception stale){result.completeExceptionally(stale);}});
        }catch(Exception stale){result.completeExceptionally(stale);}}};
        result.whenComplete((value,failure)->{leaseTimers.removeCallbacks(poll);leaseTimers.removeCallbacks(expired);});leaseTimers.postDelayed(expired,timeout);poll.run();
    }
    /** An optional predicate on the original wait operation, not another model/action loop. */
    private void waitForTaskTarget(Page page,JSONObject args,BrowserTaskSpace.Gate gate,CompletableFuture<JSONObject> result)throws Exception {
        JSONObject target=args.getJSONObject("target");BrowserTargetCondition.validate(target);
        final BrowserTaskSpace.Tab owner=page.taskOwner;requireWaitPage(page,owner,gate);
        if(page.readObserver==null)throw new IllegalStateException("原任务页没有区域读取通道");
        final long version=page==activePage?navigation:page.navigation,leaseEpoch=lease.require(args),pause=owner.space().originalControl.pauseRevision();
        final String url=page.view.getUrl()==null?"":page.view.getUrl(),epoch=profileSession.sessionId+"|"+leaseEpoch+"|"+version+"|"+pause+owner.identity().toString();
        final long deadline=android.os.SystemClock.elapsedRealtime()+args.optInt("timeoutMs",3000);
        final BrowserTargetCondition.Tracker tracker=new BrowserTargetCondition.Tracker(target);
        final java.util.concurrent.atomic.AtomicReference<CompletableFuture<JSONObject>> sampling=new java.util.concurrent.atomic.AtomicReference<>();
        dev.pocketengine.browser.BrowserFrameObserver.Check current=()->{
            requireWaitPage(page,owner,gate);
            if(isDestroyed()||activePage!=page||web!=page.view||navigation!=version||page.navigation!=version||lease.require(args)!=leaseEpoch||!url.equals(page.view.getUrl()==null?"":page.view.getUrl())||!page.profile.current()||owner.space().originalControl.pauseRevision()!=pause)throw new BrowserTaskSpace.StalePage("目标条件的原页/资料/文档或暂停版本已变化");
        };
        Runnable expired=()->{if(result.isDone())return;try{current.check();result.complete(tracker.receipt(false,true,android.os.SystemClock.elapsedRealtime()).put("navigation",version).put("url",url));}catch(Exception stale){result.completeExceptionally(stale);}};
        Runnable poll=new Runnable(){public void run(){if(result.isDone())return;try{
            current.check();if(android.os.SystemClock.elapsedRealtime()>=deadline){expired.run();return;}
            if(tracker.exhausted()){result.complete(tracker.receipt(false,false,android.os.SystemClock.elapsedRealtime()).put("navigation",version).put("url",url));return;}
            gate.entered();CompletableFuture<JSONObject> probe=new CompletableFuture<>();sampling.set(probe);
            probe.whenComplete((evidence,failure)->{if(result.isDone())return;try{
                current.check();if(failure!=null){result.completeExceptionally(failure);return;}
                boolean met=tracker.accept(evidence,android.os.SystemClock.elapsedRealtime());
                if(met||evidence.getString("status").equals("unavailable")){result.complete(tracker.receipt(met,false,android.os.SystemClock.elapsedRealtime()).put("navigation",version).put("url",url));return;}
                leaseTimers.postDelayed(this,BrowserTargetCondition.POLL_MS);
            }catch(Exception stale){result.completeExceptionally(stale);}});
            page.readObserver.condition(target,epoch,current,probe);
        }catch(Exception stale){result.completeExceptionally(stale);}}};
        result.whenComplete((value,failure)->{leaseTimers.removeCallbacks(poll);leaseTimers.removeCallbacks(expired);CompletableFuture<JSONObject> probe=sampling.getAndSet(null);if(probe!=null)probe.cancel(false);});
        leaseTimers.postDelayed(expired,args.optInt("timeoutMs",3000));poll.run();
    }
    private void requireWaitPage(Page page,BrowserTaskSpace.Tab owner,BrowserTaskSpace.Gate gate)throws Exception {gate.check();if(owner==null||pages.get(page.id)!=page||page.taskOwner!=owner||profileSession!=page.profile)throw new SecurityException("等待的原任务页已关闭或交给主人");owner.check();}
    private void selectOwnerFallback(){if(activePage!=null)return;for(Page page:pages.values())if(page.taskOwner==null){selectPage(page);return;}}
    private void destroyPage(Page page,boolean rendererGone){
        if(pages.get(page.id)!=page)return;if(page.popupCancelTransport!=null){page.popupCancelTransport.run();page.popupCancelTransport=null;}if(page.popupRoute!=null)page.popupRoute.close();if(popupConsentPage==page&&popupConsent!=null){android.app.AlertDialog old=popupConsent;dev.pocketengine.browser.BrowserPopupRoute.Request request=popupRequest;popupConsent=null;popupConsentPage=null;popupRequest=null;if(request!=null)request.rejectForOwner();old.dismiss();}deniedSiteRequests.values().removeIf(request->request.page==page);cancelPageDialog(page,BrowserPageDialog.Reason.PAGE_CLOSED);if(page.readObserver!=null)page.readObserver.close();if(page.networkOwnership!=null)try{page.networkOwnership.close();}catch(Exception ignored){}if(page.networkWitness!=null)page.networkWitness.close();pages.remove(page.id);if(activePage==page)selectPage(null);
        if(!rendererGone)try{page.view.stopLoading();page.view.setWebChromeClient(null);page.view.setWebViewClient(new WebViewClient());}catch(RuntimeException ignored){}
        if(page.view.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)page.view.getParent()).removeView(page.view);page.view.destroy();
    }
    public static BrowserActivity current(){BrowserActivity activity=current.get();return activity==null||activity.isDestroyed()||activity.isFinishing()?null:activity;}
    public static void setEvents(java.util.function.Consumer<JSONObject> listener){events=listener;}
    public void openForOwner(String url){takeover("主人重新打开了浏览器");if(web==null){createWebView();navigate(url!=null&&!url.trim().isEmpty()?url:recoveryUrl);}else if(url!=null&&!url.trim().isEmpty())navigate(url);}
    public JSONObject snapshot(){return json("open",web!=null,"url",web==null||web.getUrl()==null?"":web.getUrl(),"title",web==null||web.getTitle()==null?"":web.getTitle(),"loading",loading,"navigation",navigation,"lease",lease.snapshot(),"agentDomTools",true,"backgroundDisplay",false,"profileId",profileSession==null?profiles==null?"":profiles.selectedId():profileSession.profileId,"profileRevision",profileSession==null?0:profileSession.profileRevision,"browserSessionId",profileSession==null?"":profileSession.sessionId,"profileIsolation",profileSession==null?"unavailable":profileSession.isolation,"profiles",profileState(),"profileError",profileError,"sharedWithSystemChrome",false,"domIsolation","page-world-untrusted","operationInFlight",pending!=null&&!pending.isDone(),"rendererRecoveryNeeded",rendererRecoveryNeeded,"fileChooserPending",fileChooser!=null,"uploadInProgress",uploadDestination!=null,"uploadViaOwnerPicker",true,"downloadToAttachments",true,"downloadsRequireOwnerConfirmation",true,"pageDialog",lastPageDialog==null?JSONObject.NULL:lastPageDialog,"siteNotice",currentSiteNotice(),"pageCapabilities",json("javascriptDialogs","native_owner_confirmation","popupWindows","native_user_gesture_owner_origin_confirmation","oauthPopup","manual_same_profile_child_close_returns_original_parent","hardwarePermissions","denied_by_default"));}
    private JSONObject profileState(){try{if(profiles==null)return json("available",false);JSONObject state=profiles.snapshot();return json("multiProfileSupported",state.optBoolean("multiProfileSupported"),"featureCheck",state.optString("featureCheck"),"providerPackage",state.optString("providerPackage"),"providerVersion",state.optString("providerVersion"),"ownerManagementOnly",true);}catch(Exception error){return json("available",false,"message","资料状态无法读取");}}
    /** Called only after the native owner's revision-checked profile mutation. */
    public void profileChangedForOwner(){closeProfileSession("主人已切换或停用浏览器资料，原会话执行已停止");createWebView();if(web!=null)navigate(null);}
    private void closeProfileSession(String reason){closeProfileSession(reason,false);}
    private void closeProfileSession(String reason,boolean rendererGone){
        closingProfile=true;takeover(reason);if(taskSpace!=null)try{taskSpace.endNative("profile_closed");}catch(Exception ignored){}cancelFileChooser();if(downloadDialog!=null){downloadDialog.dismiss();downloadDialog=null;}
        BrowserProfileRegistry.Session old=profileSession;profileSession=null;if(profiles!=null)profiles.endSession(old);lease.bindProfile(null);navigation++;invalidateObservation();
        if(old!=null)try{BrowserDownloads.get(this).cancelSession(old.sessionId);}catch(Exception ignored){}
        for(Page page:new java.util.ArrayList<>(pages.values()))destroyPage(page,rendererGone);web=null;activePage=null;loading=false;taskSpace=null;taskLease=null;lastPageDialog=null;lastSiteNotice=null;deniedSiteRequests.clear();closingProfile=false;
    }
    public void execute(String command,JSONObject args,CompletableFuture<JSONObject> result){
        if(result.isDone())return;
        if(isDestroyed()||web==null||profileSession==null||!profileSession.current()){result.completeExceptionally(new IllegalStateException("浏览器资料或网页已关闭，请主人重新选择"));return;}
        try{
            if(command.equals("acquire")){JSONObject acquired=lease.acquire(args);scheduleLeaseExpiry(acquired);result.complete(acquired);return;}
            if(command.equals("release")||command.equals("cancel")||command.equals("takeover")){
                JSONObject released=lease.release(args,command);leaseTimers.removeCallbacks(leaseExpiryCheck);invalidateObservation();failPending("浏览器执行已停止或接管");result.complete(released);return;
            }
            final long epoch=lease.require(args);
            if(taskSpace!=null&&!taskSpace.snapshot().optString("status").equals("ended")){
                if(activePage==null||activePage.taskOwner==null)throw new SecurityException("任务工具不能操作主人已有页");BrowserTaskSpace.Tab owner=activePage.taskOwner;
                if(!activePage.id.equals(args.optString("tabId"))||!owner.space().spaceId.equals(args.optString("browserSpaceId"))||owner.space().binding.epoch!=args.optInt("epoch"))throw new SecurityException("任务工具缺少原空间/页/epoch绑定");owner.check();
            }
            if(pending!=null&&!pending.isDone())throw new IllegalStateException("上一浏览器操作尚未返回，请先核对结果");
            switch(command){
                case "navigate":
                    if(!allowedUrl(args.getString("url")))throw new IllegalArgumentException("浏览器工具只接受有效的 http/https 地址");
                    if(activePage!=null&&activePage.taskOwner!=null)navigateForTask(args.getString("url"));else navigate(args.getString("url"));result.complete(json("accepted",true,"verified",false,"status","navigation_requested"));return;
                case "back":case "forward":case "reload":
                    invalidateObservation();
                    boolean accepted=command.equals("reload")||command.equals("back")&&web.canGoBack()||command.equals("forward")&&web.canGoForward();
                    if(accepted){if(command.equals("back"))web.goBack();else if(command.equals("forward"))web.goForward();else web.reload();}
                    result.complete(json("accepted",accepted,"verified",false,"status","navigation_requested"));return;
                case "site_mode":{if(loading||activePage==null||activePage.taskOwner==null||activePage.taskGate==null)throw new SecurityException("网页模式需要原任务当前已加载页面");final Page source=activePage;final long version=navigation;source.taskGate.check();source.taskGate.entered();invalidateObservation();result.complete(dev.pocketengine.browser.BrowserSiteMode.apply(web,args.getString("mode"),()->{source.taskOwner.check();source.taskGate.check();if(activePage!=source||web!=source.view||navigation!=version||lease.require(args)!=epoch)throw new BrowserTaskSpace.StalePage("网页模式的原页面已变化");}));return;}
                case "input":{executeNativeInput(args,result,epoch);return;}
                case "evaluate":case "inspect":case "script":case "network":{executePageProgram(command,args,result,epoch);return;}
                case "observe":{
                    if(args.has("read")){observeSemantic(args,result,epoch);return;}
                    if(loading)throw new IllegalStateException("网页正在加载，请稍后重新观察");
                    final long version=navigation;final WebView observedWeb=web;final String observed=UUID.randomUUID().toString();
                    final JSONObject actionFrame=args.has("frameRef")?activePage.readObserver.framePinForNative(args.getString("frameRef")):null;
                    pending=result;pendingMutation=false;
                    String script=dev.pocketengine.browser.BrowserFormControls.observationScript(pageKey,observed);
                    evaluateInFrame(actionFrame,script,value->{
                        if(!callbackCurrent(result,epoch,version,observedWeb))return;
                        try{JSONObject observedPage=decoded(value);if(actionFrame!=null){if(!observedPage.has("elements"))throw new IllegalStateException("frame观察未返回动作目标");actionFrame.put("url",observedPage.getString("url"));observedPage.put("actionFrame",actionFrame).put("url",observedWeb.getUrl());}observationId=observed;observationUrl=actionFrame==null?observedPage.optString("url"):actionFrame.getString("url");observationAt=android.os.SystemClock.elapsedRealtime();observedPage.put("observationId",observed).put("navigation",version).put("untrustedPageContent",true).put("expiresInMs",20000).put("profileId",profileSession.profileId).put("profileRevision",profileSession.profileRevision).put("browserSessionId",profileSession.sessionId);result.complete(observedPage);}catch(Exception malformed){result.completeExceptionally(new IllegalStateException("无法读取网页内容"));}finally{clearPending(result);}
                    },result);return;
                }
                case "click":case "type":case "set_date":case "focus":case "press":case "select_option":case "set_checked":case "scroll":{
                    if(loading||observationId==null||!observationId.equals(args.optString("observationId"))||android.os.SystemClock.elapsedRealtime()-observationAt>20_000L)throw new IllegalStateException("网页观察已过期，请重新观察");
                    final long version=navigation;final WebView actedWeb=web;final String expected=observationUrl;final String observed=observationId;
                    final boolean form=dev.pocketengine.browser.BrowserFormControls.command(command)||dev.pocketengine.browser.BrowserTextControls.command(command);final String formToken=form?UUID.randomUUID().toString():null;
                    String actionScript=dev.pocketengine.browser.BrowserDomAction.script(command,args,expected,observed,pageKey,formToken);
                    invalidateObservation();pending=result;pendingMutation=true;
                    evaluateInFrame(args.optJSONObject("_nativeActionFrame"),actionScript,value->{
                        if(!callbackCurrent(result,epoch,version,actedWeb))return;
                        boolean readingBack=false;
                        try{JSONObject receipt=decoded(value);receipt.put("verified",false);if(form&&Boolean.TRUE.equals(receipt.opt("accepted"))){readingBack=true;readBackForm(command,args,result,epoch,version,actedWeb,observed,expected,formToken,receipt);}else result.complete(receipt);}catch(Exception invalid){result.complete(unknownReceipt("网页操作回执未知，请核对结果后继续"));}finally{if(!readingBack||result.isDone())clearPending(result);}
                    },result);return;
                }
                default:throw new IllegalArgumentException("不支持的浏览器命令");
            }
        }catch(Exception error){result.completeExceptionally(error);}
    }
    private JSONObject inputPagePin(Page page)throws Exception {
        JSONObject visible=visualPagePin(page,new JSONObject());return json("documentId",visible.getString("documentId"),"navigation",page.navigation,"viewport",visible.getJSONObject("viewport"),"windowX",visible.getInt("windowX"),"windowY",visible.getInt("windowY"));
    }
    private void executeNativeInput(JSONObject args,CompletableFuture<JSONObject> result,long leaseEpoch)throws Exception {
        final Page page=activePage;final WebView target=web;final long version=navigation;final String observed=observationId,url=observationUrl;final JSONObject expected=args.getJSONObject("_nativeInputViewport");
        if(page==null||page.taskOwner==null||page.taskGate==null||loading||observed==null||!observed.equals(args.getString("observationId"))||android.os.SystemClock.elapsedRealtime()-observationAt>20000)throw new BrowserTaskSpace.StalePage("原生输入需要原页的最新普通观察");
        final String fingerprint=TaskStore.capabilityFingerprint(expected);BrowserFrameObserver.Check current=()->{page.taskOwner.check();page.taskGate.check();if(activePage!=page||web!=target||pages.get(page.id)!=page||navigation!=version||lease.require(args)!=leaseEpoch||!fingerprint.equals(TaskStore.capabilityFingerprint(inputPagePin(page))))throw new BrowserTaskSpace.StalePage("原生输入的视口、缩放、滚动或窗口已变化");};
        current.check();pending=result;pendingMutation=true;result.whenComplete((value,error)->clearPending(result));
        JSONObject safe=new JSONObject();for(String key:new String[]{"ref","action","x","y","toX","toY","toRef","key"})if(args.has(key))safe.put(key,args.get(key));
        String code=dev.pocketengine.browser.BrowserNativeInput.geometryScript(pageKey,url,observed,safe,args.getJSONObject("_nativeTargetPreview"),args.optJSONObject("_nativeInputEndTarget"));
        evaluate(code,value->{if(result.isDone())return;try{current.check();JSONObject geometry=decoded(value);if(!geometry.optString("status").equals("geometry_observed")){result.complete(geometry.put("accepted",false).put("verified",false));return;}invalidateObservation();CompletableFuture<JSONObject> dispatch=new CompletableFuture<>();
            dispatch.whenComplete((receipt,error)->{if(result.isDone())return;if(error!=null){result.complete(unknownReceipt("原生网页输入结果未知，不会重放"));return;}if(!receipt.optString("status").equals("native_input_returned")){result.complete(receipt);return;}
                leaseTimers.postDelayed(()->{if(result.isDone())return;try{current.check();CompletableFuture<JSONObject> read=new CompletableFuture<>();read.whenComplete((after,failure)->{if(result.isDone())return;try{current.check();if(failure==null)receipt.put("readback",after);else receipt.put("readback",json("status","unavailable"));result.complete(receipt.put("businessOutcomeVerified",false).put("readbackDelayMs",300));}catch(Exception changed){result.complete(unknownReceipt("原生网页输入后原页面已变化，请核对现场"));}});page.readObserver.evaluateLive(new JSONObject(),true,current,read);}catch(Exception changed){result.complete(unknownReceipt("原生网页输入后无法读取原页"));}},300);
            });
            result.whenComplete((receipt,error)->{if(result.isCancelled())dispatch.cancel(false);});page.taskGate.entered();dev.pocketengine.browser.BrowserNativeInput.dispatch(target,safe,geometry,current::check,()->page.nativeInputDispatching=true,()->page.nativeInputDispatching=false,dispatch);
        }catch(Exception stale){result.complete(unknownReceipt("原生网页输入的目标或回执已失效，未重放"));}});
    }
    /** Read-only programs use the isolated observer; raw effectful JS stays in the page world. */
    private void executePageProgram(String command,JSONObject args,CompletableFuture<JSONObject> result,long leaseEpoch)throws Exception {
        final Page source=activePage;final WebView originalWeb=web;final long version=navigation;final String document=source==null||source.readObserver==null?"":source.readObserver.documentIdentityForNative();
        if(source==null||source.taskOwner==null||source.taskGate==null||source.readObserver==null||loading)throw new SecurityException("高级浏览需要原任务当前已加载页面");
        BrowserFrameObserver.Check current=()->{if(source!=activePage||source.view!=web||navigation!=version||pages.get(source.id)!=source||!document.equals(source.readObserver.documentIdentityForNative())||lease.require(args)!=leaseEpoch)throw new BrowserTaskSpace.StalePage("原脚本页面/文档已变化");source.taskOwner.check();source.taskGate.check();};
        current.check();pending=result;pendingMutation=command.equals("script")||command.equals("network")&&!args.optString("action").equals("read");
        result.whenComplete((value,error)->clearPending(result));
        if(command.equals("network")){current.check();if(source.networkWitness==null)throw new IllegalStateException("网络witness不可用");String action=args.getString("action");if(action.equals("read")){source.taskGate.entered();result.complete(source.networkWitness.snapshot(args));return;}if(action.equals("stop")){source.taskGate.entered();String cleanup=source.networkWitness.stopScript();evaluate(cleanup,value->{source.networkWitness.close();if(!result.isDone())result.complete(json("status","stopped","accepted",true,"networkRequestsCancelled",false,"wrapperCleanupAttempted",true));});return;}String code=source.networkWitness.start(originalWeb.getUrl(),document);if(source.networkOwnership!=null)source.networkOwnership.close();source.networkOwnership=source.taskOwner.watch(source.networkWitness::close);current.check();evaluate(code,value->{if(!callbackCurrent(result,leaseEpoch,version,originalWeb))return;try{current.check();JSONObject receipt=decoded(value);if(!receipt.optBoolean("accepted")||!receipt.optString("status").equals("witness_installed"))source.networkWitness.close();result.complete(receipt.put("documentId",document).put("untrustedPageContent",true));}catch(Exception unknown){source.networkWitness.close();result.complete(unknownReceipt("页面witness安装回执未知"));}});return;}
        if(command.equals("script")){
            dev.pocketengine.browser.BrowserPagePrograms.validateScript(args);invalidateObservation();String token=UUID.randomUUID().toString();
            current.check();evaluate(dev.pocketengine.browser.BrowserPagePrograms.script(args.getString("source"),token),value->{
                if(!callbackCurrent(result,leaseEpoch,version,originalWeb))return;
                try{current.check();JSONObject receipt=decoded(value);if(!"script_returned".equals(receipt.optString("status"))||!token.equals(receipt.optString("token"))){result.complete(unknownReceipt("脚本可能已产生效果，完成结果未知；不会重放"));return;}
                    leaseTimers.postDelayed(()->{if(result.isDone())return;try{current.check();CompletableFuture<JSONObject> read=new CompletableFuture<>();source.readObserver.evaluateLive(new JSONObject(),true,current,read);read.whenComplete((after,error)->{if(result.isDone())return;try{current.check();if(error!=null){result.complete(receipt.put("readback",json("status","unavailable")).put("verified",false));return;}result.complete(receipt.put("readback",after).put("readbackDelayMs",600).put("businessOutcomeVerified",false));}catch(Exception changed){result.complete(unknownReceipt("脚本后原文档已变化，不能核对其效果"));}});}catch(Exception changed){result.complete(unknownReceipt("脚本后原文档无法回查"));}},600);
                }catch(Exception unknown){result.complete(unknownReceipt("脚本返回不可核对；不会重放"));}
            });return;
        }
        source.taskGate.entered();CompletableFuture<JSONObject> first=new CompletableFuture<>();source.readObserver.evaluateLive(args,command.equals("inspect"),current,first);
        first.whenComplete((before,error)->{if(result.isDone())return;if(error!=null){result.completeExceptionally(error);return;}if(!command.equals("inspect")||!before.optString("status").equals("observed")){try{if(before.optString("status").equals("observed"))before.put("advancedRead",true).put("observationId",UUID.randomUUID().toString()).put("navigation",version);result.complete(before);}catch(Exception invalid){result.completeExceptionally(invalid);}return;}
            leaseTimers.postDelayed(()->{if(result.isDone())return;try{current.check();CompletableFuture<JSONObject> next=new CompletableFuture<>();source.readObserver.evaluateLive(args,true,current,next);next.whenComplete((after,failure)->{if(result.isDone())return;try{current.check();if(failure!=null){result.completeExceptionally(failure);return;}boolean complete=!before.getJSONObject("coverage").optBoolean("partial")&&!after.getJSONObject("coverage").optBoolean("partial");after.put("stability",json("samples",2,"minimumIntervalMs",600,"firstObservedAt",before.optLong("observedAt"),"lastObservedAt",after.optLong("observedAt"),"boundedBodyUnchanged",before.optString("text").equals(after.optString("text")),"coverageComplete",complete,"entirePageStable",false,"networkIdleProven",false));after.put("advancedRead",true).put("observationId",UUID.randomUUID().toString()).put("navigation",version);result.complete(after);}catch(Exception changed){result.completeExceptionally(changed);}});}catch(Exception changed){result.completeExceptionally(changed);}},600);
        });
    }
    /** Exactly one delayed read of the original node. No write retry, fresh locator, submit or navigation. */
    private void readBackForm(String command,JSONObject args,CompletableFuture<JSONObject> result,long epoch,long version,WebView actedWeb,String observed,String expected,String token,JSONObject dispatch) {
        if(!leaseTimers.postDelayed(()->{
            if(!callbackCurrent(result,epoch,version,actedWeb))return;
            try{
                lease.require(args);if(activePage!=null&&activePage.taskOwner!=null){activePage.taskOwner.check();if(activePage.taskGate==null)throw new SecurityException("原表单操作已停止");activePage.taskGate.check();}
                evaluateInFrame(args.optJSONObject("_nativeActionFrame"),dev.pocketengine.browser.BrowserTextControls.command(command)?dev.pocketengine.browser.BrowserTextControls.readbackScript(pageKey,observed,expected,token):dev.pocketengine.browser.BrowserFormControls.readbackScript(pageKey,observed,expected,token),value->{
                    if(!callbackCurrent(result,epoch,version,actedWeb))return;
                    try{lease.require(args);if(activePage!=null&&activePage.taskOwner!=null){activePage.taskOwner.check();activePage.taskGate.check();}result.complete(dev.pocketengine.browser.BrowserTextControls.command(command)?dev.pocketengine.browser.BrowserTextControls.readback(dispatch,decoded(value),args):dev.pocketengine.browser.BrowserFormControls.readback(dispatch,decoded(value),args));}
                    catch(Exception unavailable){result.complete(unknownReceipt("已处理一次控件操作，但原控件回查未确认；请查看原页，不要重复操作。"));}
                    finally{clearPending(result);}
                },result);
            }catch(Exception stopped){result.complete(unknownReceipt("控件操作后的回查已停止；请核对原页面，没有重试操作。"));clearPending(result);}
        },dev.pocketengine.browser.BrowserTextControls.command(command)?600:100)){result.complete(unknownReceipt("控件回查未能启动；请核对原页面，没有重试操作。"));clearPending(result);}
    }
    private JSONObject visualPagePin(Page page,JSONObject args)throws Exception {
        requireMain();if(page==null||pages.get(page.id)!=page||activePage!=page||web!=page.view||page.profile!=profileSession||!page.profile.current()||!ownerForeground||!hasWindowFocus()||page.loading||isDestroyed())throw new IllegalStateException("请先查看已载入的原任务页，再请求截图");
        if(browserImeVisible||pageDialog!=null||fileChooser!=null||uploadDestination!=null||ownerPanel!=null&&ownerPanel.obscuresPage())throw new IllegalStateException("请先关闭键盘、研究面板或网页提示，避免把遮挡内容当作网页截图");
        if((getWindow().getAttributes().flags&android.view.WindowManager.LayoutParams.FLAG_SECURE)!=0)throw new IllegalStateException("此窗口禁止截屏，未绕过系统保护");
        android.graphics.Rect visible=new android.graphics.Rect();if(!page.view.isAttachedToWindow()||!page.view.isShown()||!page.view.getGlobalVisibleRect(visible)||visible.width()!=page.view.getWidth()||visible.height()!=page.view.getHeight())throw new IllegalStateException("原网页尚未完整显示，未捕获其他区域");
        if(page.readObserver==null)throw new IllegalStateException("原网页文档身份不可核对");int[] location=new int[2];page.view.getLocationInWindow(location);
        return json("documentId",page.readObserver.documentIdentityForNative(),"frameId","main","viewport",BrowserVisualCapture.viewport(page.view.getWidth(),page.view.getHeight(),page.view.getScrollX(),page.view.getScrollY(),page.view.getScale(),args.optJSONObject("crop")),"windowX",location[0],"windowY",location[1]);
    }
    /** Uses the real Window buffer; no View.draw/text-rendered replacement and no capture when native eligibility fails. */
    private void capturePageVisual(Page page,BrowserTaskSpace.Tab tab,JSONObject args,JSONObject expected,BrowserTaskSpace.Gate gate,CompletableFuture<BrowserVisualCapture.Frame> result)throws Exception {
        requireMain();final android.view.ViewTreeObserver observer=page.view.getViewTreeObserver();final long version=page.navigation;final String doc=expected.getString("documentId");final String fingerprint=TaskStore.capabilityFingerprint(expected);final java.util.concurrent.atomic.AtomicBoolean started=new java.util.concurrent.atomic.AtomicBoolean();
        final android.view.ViewTreeObserver.OnPreDrawListener[] draw=new android.view.ViewTreeObserver.OnPreDrawListener[1];Runnable timeout=()->result.completeExceptionally(new IllegalStateException("原网页尚未回到已确认的显示区域，未发送截图"));
        Runnable remove=()->{leaseTimers.removeCallbacks(timeout);if(observer.isAlive()&&draw[0]!=null)observer.removeOnPreDrawListener(draw[0]);};
        draw[0]=()->{if(result.isDone()){remove.run();return true;}try{tab.check();gate.liveCheck();if(page.taskOwner!=tab||page.navigation!=version||page.readObserver==null||!doc.equals(page.readObserver.documentIdentityForNative()))throw new BrowserTaskSpace.StalePage("已确认截图的原文档已变化");
                try{if(!fingerprint.equals(TaskStore.capabilityFingerprint(visualPagePin(page,args))))return true;}catch(IllegalStateException temporarilyCovered){return true;}
                if(started.compareAndSet(false,true)){remove.run();page.view.post(()->{if(result.isDone())return;try{capturePageVisualReady(page,tab,args,expected,gate,result);}catch(Exception unavailable){result.completeExceptionally(unavailable);}});}
            }catch(Exception stale){remove.run();result.completeExceptionally(stale);}return true;};
        result.whenComplete((value,error)->{if(Looper.myLooper()==Looper.getMainLooper())remove.run();else leaseTimers.post(remove);});observer.addOnPreDrawListener(draw[0]);leaseTimers.postDelayed(timeout,1500);page.view.invalidate();
    }
    private void capturePageVisualReady(Page page,BrowserTaskSpace.Tab tab,JSONObject args,JSONObject expected,BrowserTaskSpace.Gate gate,CompletableFuture<BrowserVisualCapture.Frame> result)throws Exception {
        requireMain();final long navigation=page.navigation,pause=tab.space().originalControl.pauseRevision();final String identity=TaskStore.capabilityFingerprint(expected);final java.util.concurrent.atomic.AtomicReference<AutoCloseable> retained=new java.util.concurrent.atomic.AtomicReference<>();
        BrowserFrameObserver.Check current=()->{tab.check();gate.liveCheck();if(tab.space().originalControl.pauseRevision()!=pause||page.taskOwner!=tab||page.navigation!=navigation||!TaskStore.capabilityFingerprint(visualPagePin(page,args)).equals(identity))throw new BrowserTaskSpace.StalePage("截图期间原网页已变化");};
        current.check();retained.set(tab.watch(()->result.completeExceptionally(new IllegalStateException("原截图页已关闭、暂停或接管"))));result.whenComplete((value,error)->{AutoCloseable watch=retained.getAndSet(null);if(watch!=null)try{watch.close();}catch(Exception ignored){}});
        CompletableFuture<JSONObject> before=new CompletableFuture<>();before.whenComplete((first,failure)->{
            if(result.isDone())return;if(failure!=null){result.completeExceptionally(new IllegalStateException("当前网页不适合发送截图，请先人工检查密码、验证码或嵌入页"));return;}
            android.graphics.Bitmap bitmap=null;try{current.check();gate.check();JSONObject viewport=expected.getJSONObject("viewport");NativeToolObservation.Viewport nativeViewport=BrowserVisualCapture.viewport(viewport);NativeToolObservation.Crop crop=nativeViewport.crop();bitmap=android.graphics.Bitmap.createBitmap(nativeViewport.imageWidthPx(),nativeViewport.imageHeightPx(),android.graphics.Bitmap.Config.ARGB_8888);final android.graphics.Bitmap actual=bitmap;
                android.graphics.Rect region=new android.graphics.Rect(expected.getInt("windowX")+crop.x(),expected.getInt("windowY")+crop.y(),expected.getInt("windowX")+crop.x()+crop.width(),expected.getInt("windowY")+crop.y()+crop.height());
                gate.check();current.check();gate.entered();android.view.PixelCopy.request(getWindow(),region,actual,status->{
                    if(result.isDone()){actual.recycle();return;}if(status!=android.view.PixelCopy.SUCCESS){actual.recycle();result.completeExceptionally(new IllegalStateException("系统未能取得这次真实网页图像，未发送替代图片"));return;}
                    try{current.check();CompletableFuture<JSONObject> after=new CompletableFuture<>();after.whenComplete((last,error)->{try{if(result.isDone())return;current.check();if(error!=null||first.getLong("mutationRevision")!=last.getLong("mutationRevision")||!first.getString("documentId").equals(last.getString("documentId")))throw new BrowserTaskSpace.StalePage("截图期间页面结构已变化，未交付旧图");
                                JSONObject nativeIdentity=tab.identity();NativeToolObservation.Identity captured=new NativeToolObservation.Identity(nativeIdentity.getString("taskId"),nativeIdentity.getString("runId"),nativeIdentity.getInt("epoch"),nativeIdentity.getString("profileId"),nativeIdentity.getLong("profileRevision"),nativeIdentity.getString("profileIsolation"),nativeIdentity.getString("browserSessionId"),nativeIdentity.getString("browserSpaceId"),nativeIdentity.getString("tabId"),nativeIdentity.getLong("tabGeneration"),navigation,expected.getString("documentId"),UUID.randomUUID().toString(),System.currentTimeMillis(),nativeViewport);
                                BrowserVisualCapture.Frame frame=new BrowserVisualCapture.Frame(captured,actual,()->requireVisualPageCurrent(page,tab,args,identity,navigation,pause));if(!result.complete(frame))frame.close();
                            }catch(Exception stale){result.completeExceptionally(new IllegalStateException("截图对应的页面已变化，未发送旧图"));}finally{if(!result.isDone()||result.isCompletedExceptionally()||result.isCancelled())actual.recycle();}});page.readObserver.visualProbe(current,after);
                    }catch(Exception stale){actual.recycle();result.completeExceptionally(new IllegalStateException("原截图页已变化，图像未交付"));}
                },leaseTimers);
            }catch(Exception unavailable){if(bitmap!=null)bitmap.recycle();result.completeExceptionally(new IllegalStateException("真实网页图像未能取得，请保留当前页面人工查看"));}
        });
        try{page.readObserver.visualProbe(current,before);}catch(Exception unavailable){before.completeExceptionally(unavailable);}
    }
    /** Plain current-page guard after the original operation's receipt; never requires that operation to remain prepared. */
    private void requireVisualPageCurrent(Page page,BrowserTaskSpace.Tab tab,JSONObject args,String expected,long navigation,long pause)throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper()){tab.check();if(page.taskOwner!=tab||page.navigation!=navigation||tab.space().originalControl.pauseRevision()!=pause||!expected.equals(TaskStore.capabilityFingerprint(visualPagePin(page,args))))throw new BrowserTaskSpace.StalePage("原截图的文档或viewport已变化");return;}
        CompletableFuture<Void> value=new CompletableFuture<>();leaseTimers.post(()->{if(value.isDone())return;try{requireVisualPageCurrent(page,tab,args,expected,navigation,pause);value.complete(null);}catch(Exception changed){value.completeExceptionally(changed);}});try{value.get(3,java.util.concurrent.TimeUnit.SECONDS);}catch(java.util.concurrent.ExecutionException failure){throw new BrowserTaskSpace.StalePage("原截图已不再当前");}finally{value.cancel(false);}
    }
    private void observeSemantic(JSONObject args,CompletableFuture<JSONObject> result,long leaseEpoch)throws Exception {
        if(loading||activePage==null||activePage.readObserver==null)throw new IllegalStateException("网页仍在加载或没有可读宿主");
        BrowserSemanticRead.validate(args.getJSONObject("read"));final Page page=activePage;final WebView target=web;final long version=navigation;final String observed=UUID.randomUUID().toString();
        final long pause=page.taskOwner==null?-1:page.taskOwner.space().originalControl.pauseRevision();
        String epoch=profileSession.sessionId+"|"+leaseEpoch+"|"+version+"|"+pause+(page.taskOwner==null?"":page.taskOwner.identity().toString());
        pending=result;pendingMutation=false;CompletableFuture<JSONObject> read=new CompletableFuture<>();
        BrowserFrameObserver.Check check=()->{if(isDestroyed()||activePage!=page||web!=target||pages.get(page.id)!=page||navigation!=version||lease.require(args)!=leaseEpoch||page.profile!=profileSession||!page.profile.current())throw new BrowserTaskSpace.StalePage("原语义读取页面/会话已变化");if(page.taskOwner!=null){page.taskOwner.check();if(page.taskOwner.space().originalControl.pauseRevision()!=pause||page.taskGate==null)throw new BrowserTaskSpace.StalePage("原语义读取已暂停或接管");page.taskGate.check();}};
        check.check();if(page.taskGate!=null)page.taskGate.entered();
        read.whenComplete((value,error)->{if(!callbackCurrent(result,leaseEpoch,version,target))return;try{check.check();if(error!=null){result.completeExceptionally(error);return;}value.put("observationId",observed).put("navigation",version).put("expiresInMs",20000).put("profileId",profileSession.profileId).put("profileRevision",profileSession.profileRevision).put("browserSessionId",profileSession.sessionId);result.complete(value);}catch(Exception stale){result.completeExceptionally(stale);}finally{clearPending(result);}});
        result.whenComplete((value,error)->{if(result.isCancelled())read.cancel(false);});
        try{page.readObserver.read(args.getJSONObject("read"),observed,epoch,check,read);}catch(Exception failed){read.completeExceptionally(failed);}
    }
    public void commandTimedOut(CompletableFuture<JSONObject> operation){
        if(pending==operation)takeover("浏览器操作超时，执行已停止；请先核对是否发生");
    }
    public void takeover(String reason){cancelPageDialog(null,BrowserPageDialog.Reason.CONTEXT_CHANGED);JSONObject previous=lease.snapshot();if(!closingProfile&&activePage!=null)try{if(activePage.taskOwner!=null)activePage.taskOwner.space().takeOverNative(activePage.id);else if(taskSpace!=null&&!taskSpace.ended())taskSpace.ownerBrowsingNative(activePage.id);}catch(Exception ignored){}lease.invalidate();leaseTimers.removeCallbacks(leaseExpiryCheck);invalidateObservation();failPending(reason);if(message!=null)message.setText(reason);if(previous.optBoolean("active"))try{emitLeaseEvent(previous.put("status","taken_over").put("active",false).put("message",reason));}catch(Exception ignored){}}
    private void emitLeaseEvent(JSONObject receipt){java.util.function.Consumer<JSONObject> listener=events;if(listener!=null)listener.accept(receipt);}
    private void scheduleLeaseExpiry(JSONObject snapshot){leaseTimers.removeCallbacks(leaseExpiryCheck);if(snapshot.optBoolean("active"))leaseTimers.postDelayed(leaseExpiryCheck,Math.max(1,Math.min(1_000,snapshot.optLong("expiresAt")-System.currentTimeMillis())));}
    private boolean callbackCurrent(CompletableFuture<JSONObject> result,long epoch,long version,WebView expectedWeb){
        if(result.isDone()){clearPending(result);return false;}
        if(web==null||web!=expectedWeb||profileSession==null||!profileSession.current()||!lease.current(epoch)||navigation!=version){if(pendingMutation)result.complete(unknownReceipt("网页或执行身份已变化，请核对动作结果"));else result.completeExceptionally(new IllegalStateException("网页或执行身份已变化，请重新观察"));clearPending(result);return false;}return true;
    }
    private void failPending(String reason){if(pending!=null&&!pending.isDone()){if(pendingMutation)pending.complete(unknownReceipt(reason));else pending.completeExceptionally(new IllegalStateException(reason));}pending=null;pendingMutation=false;}
    private void clearPending(CompletableFuture<JSONObject> result){if(pending==result){pending=null;pendingMutation=false;}}
    private void invalidateObservation(){observationId=null;observationUrl=null;observationAt=0;if(activePage!=null){activePage.observationId=null;activePage.observationUrl=null;activePage.observationAt=0;}}
    private static JSONObject unknownReceipt(String reason){return json("accepted",JSONObject.NULL,"verified",false,"status","unknown","code","action_result_unknown","message",reason);}
    private static JSONObject decoded(String value)throws Exception{return new JSONObject(new org.json.JSONTokener(value).nextValue().toString());}
    /** This object owns one actual JsResult, not a page-supplied command or an approval grant. */
    private final class NativePageDialog {
        final Page page;final BrowserProfileRegistry.Session session;final BrowserTaskSpace space;
        final JSONObject identity,nativeLease;final BrowserPageDialog token;final String operationId;final BrowserTaskSpace.Gate originalGate;final long documentNavigation;final String document,url;
        final BrowserTaskSpace.Tab originalTab;long pauseRevision;boolean ownHandoff;
        android.app.AlertDialog window;boolean finished;final Runnable cancelled,watch;
        NativePageDialog(Page page,String callbackUrl,BrowserPageDialog.Kind kind,android.webkit.JsResult reply)throws Exception {
            this.page=page;session=page.profile;url=callbackUrl;documentNavigation=page.navigation;document=page.readObserver.documentIdentityForNative();
            originalTab=page.taskOwner;space=originalTab!=null?originalTab.space():page.handoffSpace;
            identity=originalTab!=null?originalTab.identity():space!=null?new JSONObject(page.handoffIdentity.toString()):session.identity().put("tabId",page.id);
            identity.put("navigation",documentNavigation).put("documentId",document);
            pauseRevision=space==null?0:space.originalControl.pauseRevision();ownHandoff=space!=null&&originalTab==null;nativeLease=lease.snapshot();originalGate=page.taskGate;
            operationId=originalTab!=null&&page.nativeCall!=null&&!page.nativeCall.isDone()?page.nativeOperationId:"";
            token=new BrowserPageDialog(kind,identity,operationId,android.os.SystemClock::elapsedRealtime,this::check,new BrowserPageDialog.Reply(){
                public void confirm(String text){if(reply instanceof android.webkit.JsPromptResult)((android.webkit.JsPromptResult)reply).confirm(text);else reply.confirm();}
                public void cancel(){reply.cancel();}
            },space==null||operationId.isEmpty()?BrowserPageDialog.OWNER_LIMIT_MS:space.pageDialogRemainingMillis(operationId));
            cancelled=()->leaseTimers.post(()->{if(pageDialog==this)cancelPageDialog(page,BrowserPageDialog.Reason.TASK_CANCELLED);});
            watch=new Runnable(){public void run(){if(pageDialog!=NativePageDialog.this)return;try{check();if(token.remainingMillis()==0){cancelPageDialog(page,BrowserPageDialog.Reason.TIMED_OUT);return;}leaseTimers.postDelayed(this,250);}catch(Exception stale){cancelPageDialog(page,BrowserPageDialog.Reason.CONTEXT_CHANGED);}}};
        }
        void check()throws Exception {
            requireMain();if(!ownerForeground||isDestroyed()||isFinishing()||page!=activePage||web!=page.view||pages.get(page.id)!=page||profileSession!=session||!session.current()||page.navigation!=documentNavigation||page.readObserver==null||!document.equals(page.readObserver.documentIdentityForNative())||!url.equals(page.view.getUrl()==null?"":page.view.getUrl()))throw new SecurityException("网页提示的原页面/资料/文档已变化");
            if(space!=null){space.originalControl.check();space.requirePageDialogCurrentNative(identity);if(!ownHandoff){lease.require(nativeLease);if(!operationId.isEmpty()){if(originalGate==null)throw new SecurityException("原网页提示缺少操作门禁");originalGate.liveCheck();}}if(space.originalControl.pauseRevision()!=pauseRevision||space.originalControl.isPaused()!=ownHandoff)throw new SecurityException("网页提示的原任务暂停状态已变化");}
        }
    }
    private boolean showPageDialog(WebView view,String url,String text,String initial,BrowserPageDialog.Kind kind,android.webkit.JsResult reply){
        requireMain();Page page=page(view);
        if(pageDialog!=null||page==null||view!=web||!ownerForeground||page.readObserver==null||url==null||!url.equals(view.getUrl())||!allowedUrl(url)){
            try{reply.cancel();}catch(RuntimeException ignored){}siteNotice(page,"dialog_context_unavailable");if(!ownerForeground&&page!=null&&page.taskOwner!=null)try{page.taskOwner.space().takeOverNative(page.id);}catch(Exception ignored){}return true;
        }
        NativePageDialog selected=null;
        try{
            selected=new NativePageDialog(page,url,kind,reply);pageDialog=selected;
            if(selected.space!=null){if(selected.originalTab!=null)selected.space.pageDialogOpenedNative(selected.originalTab,selected.token);else selected.space.pageDialogOpenedForOwnerNative(selected.token);
                if(page.taskOwner==null&&page.handoffSpace==selected.space){selected.ownHandoff=true;selected.pauseRevision=selected.space.originalControl.pauseRevision();}}
            lastPageDialog=selected.token.snapshot();if(selected.space==null)emitLeaseEvent(new JSONObject(lastPageDialog.toString()).put("type","browser_page_dialog_pending"));
            if(selected.space!=null)selected.space.originalControl.onCancel(selected.cancelled);
            if(pageDialog!=selected||!selected.token.pending())return true;
            final NativePageDialog item=selected;
            LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(20),dp(8),dp(20),0);
            TextView origin=new TextView(this);origin.setText("来自网页："+dialogOrigin(url));origin.setTextSize(12);content.addView(origin);
            android.widget.ScrollView scroll=new android.widget.ScrollView(this){@Override protected void onMeasure(int width,int height){int cap=View.MeasureSpec.getMode(height)==View.MeasureSpec.UNSPECIFIED?dp(160):Math.min(dp(160),View.MeasureSpec.getSize(height));super.onMeasure(width,View.MeasureSpec.makeMeasureSpec(cap,View.MeasureSpec.AT_MOST));}};TextView pageText=new TextView(this);pageText.setTextSize(15);pageText.setText(dialogText(text,2400));scroll.addView(pageText);
            TextView explanation=new TextView(this);explanation.setTextSize(12);explanation.setText("以下请求由网页提出。请核对后决定，网页可能据此继续操作；这不会授权AI自动继续。");content.addView(explanation);
            final EditText prompt;
            if(kind==BrowserPageDialog.Kind.PROMPT){explanation.setText("输入只交给此网页；此提示框不把输入写入任务记录。交还前请核对网页显示的内容。");prompt=new EditText(this);prompt.setTag("browser_page_prompt");prompt.setHint("仅发送给此网页的内容");prompt.setMinLines(1);prompt.setMaxLines(3);prompt.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(2048)});
                if(initial!=null&&initial.length()<=2048)prompt.setText(initial);else if(initial!=null&&!initial.isEmpty())explanation.setText("网页默认输入过长，未自动填入。请自行填写或取消；此提示框不记录输入；交还前请核对网页显示的内容。");content.addView(prompt,new LinearLayout.LayoutParams(-1,-2));
            }else prompt=null;
            // Keep the editable prompt and fixed actions reachable; long untrusted text scrolls in the remaining space.
            content.addView(scroll,new LinearLayout.LayoutParams(-1,-2));
            String title=kind==BrowserPageDialog.Kind.ALERT?"网页提示":kind==BrowserPageDialog.Kind.PROMPT?"网页请求输入":kind==BrowserPageDialog.Kind.BEFORE_UNLOAD?"网页请求离开当前页面":"网页需要你确认";
            item.window=new android.app.AlertDialog.Builder(this).setTitle(title).setView(content)
                    .setPositiveButton(kind==BrowserPageDialog.Kind.ALERT?"我已查看":kind==BrowserPageDialog.Kind.PROMPT?"发送给此网页":kind==BrowserPageDialog.Kind.BEFORE_UNLOAD?"离开此页":"同意网页继续",(dialog,which)->decidePageDialog(item,true,prompt==null?null:prompt.getText().toString()))
                    .setNegativeButton(kind==BrowserPageDialog.Kind.ALERT?"关闭提示":kind==BrowserPageDialog.Kind.BEFORE_UNLOAD?"留在此页":"取消网页请求",(dialog,which)->decidePageDialog(item,false,null)).create();
            item.window.setOnCancelListener(dialog->decidePageDialog(item,false,null));item.window.setOnDismissListener(dialog->{if(pageDialog==item&&item.token.pending())decidePageDialog(item,false,null);});
            item.window.show();if(item.window.getWindow()!=null){item.window.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);if((getWindow().getAttributes().flags&android.view.WindowManager.LayoutParams.FLAG_SECURE)!=0)item.window.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);}
            message.setText("网页正在等你处理提示；原任务不会代你确认。");leaseTimers.post(item.watch);return true;
        }catch(Exception unavailable){if(selected!=null){try{selected.token.cancel(BrowserPageDialog.Reason.CONTEXT_CHANGED);}catch(Exception ignored){}finishPageDialog(selected);}else try{reply.cancel();}catch(RuntimeException ignored){}siteNotice(page,"dialog_context_unavailable");return true;}
    }
    private void decidePageDialog(NativePageDialog item,boolean confirmed,String text){
        requireMain();if(pageDialog!=item)return;pageDialog=null;
        try{item.token.decide(item.token.id(),confirmed,text);}catch(Exception unavailable){if(item.token.pending())try{item.token.cancel(BrowserPageDialog.Reason.CALLBACK_FAILED);}catch(Exception ignored){}message.setText("网页提示的处理回执未确认，请查看原页；没有自动重试。");}
        finally{finishPageDialog(item);}
    }
    private void cancelPageDialog(Page expected,BrowserPageDialog.Reason reason){
        NativePageDialog item=pageDialog;if(item==null||expected!=null&&item.page!=expected)return;
        try{item.token.cancel(reason);}catch(Exception unknown){/* Keep unknown delivery in this old handle's receipt, never confirm or retry. */}
        finally{finishPageDialog(item);}
    }
    private void finishPageDialog(NativePageDialog item){
        if(item.finished)return;item.finished=true;if(pageDialog==item)pageDialog=null;leaseTimers.removeCallbacks(item.watch);if(item.space!=null)item.space.originalControl.removeCancelAction(item.cancelled);
        try{JSONObject resolution=item.token.snapshot();if(pageDialog==null)lastPageDialog=resolution;if(item.space!=null)item.space.pageDialogResolvedNative(item.token);else emitLeaseEvent(new JSONObject(resolution.toString()).put("type","browser_page_dialog_resolved"));}catch(Exception unavailable){/* UI/native cleanup does not depend on its event consumer. */}
        if(item.window!=null){View prompt=item.window.getWindow()==null?null:item.window.getWindow().getDecorView().findViewWithTag("browser_page_prompt");if(prompt instanceof EditText){((EditText)prompt).setText("");prompt.clearFocus();}item.window.dismiss();item.window=null;}
        if(message!=null&&pageDialog==null)message.setText(lastPageDialog!=null&&lastPageDialog.optBoolean("decisionDelivered")?"已把本次选择交给网页。请核对当前页面；继续研究仍需原任务的交还操作。":"网页提示的处理回执未确认，请查看原页；没有自动重试。");
    }
    public void showPendingPageDialogForOwner(){requireMain();NativePageDialog item=pageDialog;if(item==null||item.window==null)return;try{item.check();item.window.show();}catch(Exception stale){cancelPageDialog(item.page,BrowserPageDialog.Reason.CONTEXT_CHANGED);}}
    private void recordOwnerHistory(Page page,boolean closed){if(page==null||profiles==null||!page.profile.current())return;try{profiles.recordOwnerVisit(page.profile,page.view.getUrl(),page.view.getTitle(),closed);}catch(Exception unavailable){if(page==activePage&&message!=null)message.setText("本页浏览记录暂未保存；网页仍可继续使用。");}}
    private void showBrowserHistory(boolean bookmarks){
        try{final BrowserProfileRegistry.Session selected=profileSession;JSONArray all=profiles.historyForOwner(selected),rows=new JSONArray();for(int i=0;i<all.length();i++)if(!bookmarks||all.getJSONObject(i).getBoolean("bookmark"))rows.put(all.getJSONObject(i));
            String[] labels=new String[rows.length()];for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);labels[i]=(row.getBoolean("bookmark")?"★ ":"")+(row.optString("title").isEmpty()?row.getString("url"):row.getString("title"))+"\n"+row.getString("url")+(row.getBoolean("closed")?" · 已关闭":"");}
            android.app.AlertDialog.Builder dialog=new android.app.AlertDialog.Builder(this).setTitle(bookmarks?"本资料的书签":"本资料的浏览历史").setNegativeButton("返回",null);
            if(rows.length()==0)dialog.setMessage(bookmarks?"还没有书签。可从更多菜单收藏当前页。":"还没有日常浏览记录。研究任务与已保存来源在研究工作区。历史不保存地址查询参数、登录状态或表单内容。");
            else dialog.setItems(labels,(d,which)->{try{JSONObject row=profiles.historyItemForOwner(selected,rows.getJSONObject(which).getString("id"));showHistoryItem(selected,row);}catch(Exception changed){message.setText("浏览记录或登录资料已变化，请重新打开历史。");}});
            dialog.setNeutralButton(bookmarks?"清空历史与书签…":"清除历史…",(d,w)->new android.app.AlertDialog.Builder(this).setTitle(bookmarks?"清空本资料历史与书签？":"清除本资料浏览历史？").setMessage(bookmarks?"只清除这份资料的浏览记录和书签；不会删除网页登录数据或已保存的研究来源。":"保留书签，不删除网页登录数据或已保存的研究来源。").setNegativeButton("保留",null).setPositiveButton("清除",(confirm,ok)->{try{profiles.clearHistoryForOwner(selected,bookmarks);message.setText("已清除所选浏览记录。");}catch(Exception changed){message.setText("登录资料已变化，未清除其他资料的记录。");}}).show()).show();
        }catch(Exception unavailable){message.setText("当前资料的浏览记录暂不可用；没有切换资料。");}
    }
    private void showHistoryItem(BrowserProfileRegistry.Session selected,JSONObject row)throws Exception {
        new android.app.AlertDialog.Builder(this).setTitle(row.optString("title").isEmpty()?"浏览记录":row.getString("title")).setMessage(row.getString("url")+(row.getBoolean("urlQueryOmitted")?"\n此记录未保存查询参数和片段；重新打开可能需要搜索或登录。":"")+"\n打开会使用当前这份登录资料的新页面，不恢复旧任务授权或未保存表单。")
                .setNegativeButton("返回",null).setNeutralButton(row.getBoolean("bookmark")?"取消收藏":"收藏",(d,w)->{try{boolean saved=profiles.bookmarkForOwner(selected,row.getString("id"));message.setText(saved?"已加入本资料书签。":"已取消收藏。");}catch(Exception changed){message.setText("记录已变化，未修改其他书签。");}})
                .setPositiveButton("重新打开",(d,w)->{try{JSONObject actual=profiles.historyItemForOwner(selected,row.getString("id"));openOwnerTabForOwner(actual.getString("url"));}catch(Exception changed){message.setText("原记录或资料已变化，网页未打开。");}}).show();
    }
    private void bookmarkCurrentPage(){try{Page page=activePage;if(page==null)throw new IllegalStateException();JSONObject row=profiles.recordOwnerVisit(page.profile,page.view.getUrl(),page.view.getTitle(),false);if(row==null)throw new IllegalStateException();if(!row.getBoolean("bookmark"))profiles.bookmarkForOwner(page.profile,row.getString("id"));message.setText("已收藏到当前登录资料。书签不保存登录状态和地址查询参数。");}catch(Exception unavailable){message.setText("这页暂不能收藏，请确认是可用的网页地址。");}}
    private Object currentSiteNotice(){if(lastSiteNotice==null)return JSONObject.NULL;Page page=pages.get(lastSiteNotice.optString("tabId"));return page!=null&&page.profile.current()&&page.navigation==lastSiteNotice.optLong("navigation",-1)&&page.readObserver!=null&&page.readObserver.documentIdentityForNative().equals(lastSiteNotice.optString("documentId"))?lastSiteNotice:JSONObject.NULL;}
    private static String permissionTypesForOwner(JSONArray types){java.util.List<String> labels=new java.util.ArrayList<>();for(int i=0;types!=null&&i<types.length();i++)labels.add(switch(types.optString(i)){case "camera"->"相机";case "microphone"->"麦克风";case "location"->"定位";case "protected_media"->"受保护媒体";case "midi_sysex"->"MIDI设备";case "popup"->"新窗口";case "javascript_dialog"->"网页确认提示";default->"其他设备权限";});return String.join("、",labels);}
    private void showSitePermissionDetails(){String text="网页不能自动获得相机、麦克风、定位或其他设备权限。当前版本默认拒绝；只读研究授权不会改变这一点。你可以回网页继续其他操作，或回原任务说明需要人工处理。";if(currentSiteNotice()!=JSONObject.NULL&&activePage!=null&&activePage.id.equals(lastSiteNotice.optString("tabId")))text+="\n\n最近请求来源："+(lastSiteNotice.isNull("origin")?"暂不可核对":lastSiteNotice.optString("origin"))+"\n请求类型："+permissionTypesForOwner(lastSiteNotice.optJSONArray("requestTypes"))+"\n处理：已拒绝/取消，没有自动重试。";new android.app.AlertDialog.Builder(this).setTitle("站点权限").setMessage(text).setPositiveButton("返回网页",null).setNeutralButton("回原任务",(d,w)->returnToOwnerForUi()).show();}
    private void siteRequestNotice(DeniedSiteRequest request,String reason){Page page=request.page;if(!ownerForeground||pages.get(page.id)!=page||!page.profile.current()||page.navigation!=request.navigation||page.readObserver==null||!request.document.equals(page.readObserver.documentIdentityForNative()))return;siteNotice(page,reason,request.origin,request.resources);}
    private void siteNotice(Page page,String reason){siteNotice(page,reason,page==null?null:dialogOrigin(page.view.getUrl()),new String[0]);}
    private void siteNotice(Page page,String reason,String origin,String[] resources){
        if(page==null||pages.get(page.id)!=page)return;
        try{JSONObject identity=page.taskOwner!=null?page.taskOwner.identity():page.handoffIdentity!=null?new JSONObject(page.handoffIdentity.toString()):page.profile.identity().put("tabId",page.id);
            String document=page.readObserver==null?UUID.randomUUID().toString():page.readObserver.documentIdentityForNative();lastSiteNotice=BrowserSiteRequirement.create(identity,page.navigation,document,origin,resources,reason);
            if(page.taskOwner!=null)page.taskOwner.space().siteRequirementNative(lastSiteNotice);else if(page.handoffSpace!=null&&page.handoffSpace.ownerTransferCurrent(page.id))page.handoffSpace.siteRequirementNative(lastSiteNotice);emitLeaseEvent(new JSONObject(lastSiteNotice.toString()));
        }catch(Exception ignored){}
        if(page==activePage&&message!=null)message.setText(reason.equals("popup_not_supported")?"此新窗口缺少当前手势、明确来源或可用窗口；已拒绝，可在原页人工重新打开。":reason.matches("site_permission_denied|geolocation_denied|site_request_cancelled")?"此页的设备权限请求已拒绝或取消；可从更多查看来源与说明，其他浏览操作仍可继续。":"网页提示已失效，请查看原页；没有自动重试。");
    }
    private static String dialogOrigin(String url){try{URI u=new URI(url);return new URI(u.getScheme(),null,u.getHost(),u.getPort(),null,null,null).toASCIIString();}catch(Exception malformed){return "来源暂不可核对";}}
    private static String dialogText(String value,int limit){if(value==null)return "";int end=Math.min(value.length(),limit);if(end>0&&end<value.length()&&Character.isHighSurrogate(value.charAt(end-1))&&Character.isLowSurrogate(value.charAt(end)))end--;return (end<value.length()?"（网页提示较长，仅显示前面部分）\n":"")+value.substring(0,end);}

    private JSONObject uploadReadbackUnavailable(JSONObject receipt){try{return receipt.put("readback",json("status","unavailable")).put("siteConfirmed",false);}catch(Exception invalid){return unknownReceipt("上传已进入本地交付，原页读回无法核对；不重传");}}
    private void startArtifactUpload(Page page,BrowserTaskSpace.Tab tab,JSONObject args,String operationId,BrowserTaskSpace.Gate gate,CompletableFuture<JSONObject> result)throws Exception {
        requireMain();if(artifactUpload!=null||fileChooser!=null||uploadDestination!=null||pageDialog!=null)throw new IllegalStateException("上一文件选择或模态尚未结束");
        final long version=page.navigation;final String document=page.readObserver.documentIdentityForNative(),url=page.view.getUrl(),observed=args.getString("observationId");
        if(activePage!=page||web!=page.view||observationId==null||!observationId.equals(observed)||android.os.SystemClock.elapsedRealtime()-observationAt>20000)throw new BrowserTaskSpace.StalePage("上传需要原页当前普通观察");
        JSONObject identity=tab.identity().put("operationId",operationId).put("documentId",document).put("navigation",version).put("chooserId",UUID.randomUUID().toString()).put("fileInput",json("observationId",observed,"ref",args.getString("ref"),"nodeKey",args.getJSONObject("_nativeTargetPreview").getString("nodeKey")));
        JSONObject destination=BrowserUploads.destinationIdentity(url);for(var keys=destination.keys();keys.hasNext();){String key=keys.next();identity.put(key,destination.get(key));}
        BrowserFrameObserver.Check current=()->{tab.check();gate.check();if(isDestroyed()||activePage!=page||web!=page.view||pages.get(page.id)!=page||page.taskOwner!=tab||page.navigation!=version||page.readObserver==null||!document.equals(page.readObserver.documentIdentityForNative())||!url.equals(page.view.getUrl()))throw new BrowserTaskSpace.StalePage("上传原文档或控件已变化");};
        current.check();page.artifactChooserRetired=false;pending=result;pendingMutation=true;CompletableFuture<JSONObject> delivery=new CompletableFuture<>();final dev.pocketengine.browser.BrowserArtifactUpload[] retained=new dev.pocketengine.browser.BrowserArtifactUpload[1];
        dev.pocketengine.browser.BrowserArtifactUpload.Host host=new dev.pocketengine.browser.BrowserArtifactUpload.Host(){
            public void post(Runnable work){leaseTimers.post(work);}public void later(Runnable work,long delay){leaseTimers.postDelayed(work,delay);}public void check()throws Exception{current.check();}
            public void discardUnsent(android.net.Uri stage)throws Exception{new dev.pocketengine.features.attachments.AttachmentViewSnapshots(BrowserActivity.this).discardUnsent(stage);}
            public void openInput(dev.pocketengine.browser.BrowserArtifactUpload upload)throws Exception{current.check();gate.entered();String code=BrowserUploads.openArtifactInputScript(pageKey,url,observed,args.getJSONObject("_nativeTargetPreview"));evaluate(code,value->{if(delivery.isDone())return;try{current.check();if(!decoded(value).optBoolean("accepted"))upload.inputNotOpened();}catch(Exception stale){upload.close();}});}
        };
        retained[0]=new dev.pocketengine.browser.BrowserArtifactUpload(this,uploadStore(),tab.space().originalControl,identity,args.getJSONObject("_nativeAttachmentPin"),browserIo,host,delivery);artifactUpload=retained[0];artifactUploadPage=page;
        delivery.whenComplete((receipt,error)->runOnUiThread(()->{page.artifactChooserRetired=true;if(artifactUpload==retained[0]){artifactUpload=null;artifactUploadPage=null;}if(result.isDone())return;if(error!=null){result.complete(unknownReceipt("上传的本地交付结果未知，未重传"));return;}try{JSONObject attempt=retained[0].attemptSnapshot();if(attempt!=null){deliveredUploads.put(attempt.getString("attemptId"),attempt);while(deliveredUploads.size()>100)deliveredUploads.remove(deliveredUploads.keySet().iterator().next());}if(!receipt.optString("status").equals("local_delivered")){result.complete(receipt);return;}
            leaseTimers.postDelayed(()->{if(result.isDone())return;try{current.check();CompletableFuture<JSONObject> read=new CompletableFuture<>();read.whenComplete((after,failure)->{if(result.isDone())return;try{current.check();result.complete(receipt.put("readback",failure==null?after:json("status","unavailable")).put("siteConfirmed",false));}catch(Exception changed){result.complete(uploadReadbackUnavailable(receipt));}});page.readObserver.evaluateLive(new JSONObject(),true,current,read);}catch(Exception changed){result.complete(uploadReadbackUnavailable(receipt));}},300);
        }catch(Exception unknown){result.complete(unknownReceipt("上传交付回执无法核对，不会重新上传"));}}));
        result.whenComplete((value,error)->{clearPending(result);if(result.isCancelled())retained[0].close();});retained[0].start();
    }
    private boolean chooseUpload(android.webkit.ValueCallback<android.net.Uri[]> callback,android.webkit.WebChromeClient.FileChooserParams parameters){
        if(parameters.getMode()!=android.webkit.WebChromeClient.FileChooserParams.MODE_OPEN&&parameters.getMode()!=android.webkit.WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE){callback.onReceiveValue(null);message.setText("暂不支持网页保存或整文件夹授权，请使用单文件下载或上传");return true;}
        cancelFileChooser();int pickerRequest=FILE_CHOOSER_REQUESTS.getAndIncrement();if(pickerRequest>65534){callback.onReceiveValue(null);message.setText("文件选择次数已达进程上限，请重新打开应用");return true;}
        java.util.concurrent.atomic.AtomicBoolean delivered=new java.util.concurrent.atomic.AtomicBoolean();android.webkit.ValueCallback<android.net.Uri[]> once=value->{if(delivered.compareAndSet(false,true))callback.onReceiveValue(value);};
        try{
            final Page sourcePage=activePage;final BrowserProfileRegistry.Session session=profileSession;final long version=navigation;final BrowserTaskSpace sourceSpace=sourcePage==null?null:sourcePage.taskOwner!=null?sourcePage.taskOwner.space():sourcePage.handoffSpace!=null&&sourcePage.handoffSpace.ownerTransferCurrent(sourcePage.id)?sourcePage.handoffSpace:null;if(sourcePage==null||session==null)throw new SecurityException("上传原页面不可用");
            JSONObject destination=sourceSpace==null?session.identity():sourcePage.taskOwner!=null?sourcePage.taskOwner.identity():new JSONObject(sourcePage.handoffIdentity.toString());JSONObject target=BrowserUploads.destinationIdentity(sourcePage.view.getUrl());for(java.util.Iterator<String> keys=target.keys();keys.hasNext();){String key=keys.next();destination.put(key,target.get(key));}destination.put("tabId",sourcePage.id).put("documentId",sourcePage.readObserver.documentIdentityForNative()).put("navigation",version).put("chooserId",UUID.randomUUID().toString());
            BrowserUploads.Destination picked=new BrowserUploads.Destination(session,destination,parameters.getMode()==android.webkit.WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE,()->!isDestroyed()&&activePage==sourcePage&&pages.get(sourcePage.id)==sourcePage&&profileSession==session&&navigation==version&&sourcePage.navigation==version&&sourcePage.readObserver!=null&&destination.optString("documentId").equals(sourcePage.readObserver.documentIdentityForNative())&&(sourceSpace==null||sourceSpace.ownerTransferCurrent(sourcePage.id)),sourceSpace==null?null:sourceSpace.originalControl);
            takeover("网页需要你选择上传文件，自动执行已暂停");fileChooserRequest=pickerRequest;fileChooserDestination=picked;fileChooser=once;
            android.content.Intent pick=new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("*/*").addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            java.util.ArrayList<String> types=new java.util.ArrayList<>();for(String raw:parameters.getAcceptTypes())if(raw!=null)for(String type:raw.split(",")){type=type.trim();if(type.matches("(?:[A-Za-z0-9.+_-]+|\\*)/(?:[A-Za-z0-9.+_-]+|\\*)"))types.add(type);}
            if(types.size()==1)pick.setType(types.get(0));else if(!types.isEmpty())pick.putExtra(android.content.Intent.EXTRA_MIME_TYPES,types.toArray(new String[0]));
            pick.putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE,parameters.getMode()==android.webkit.WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE);startActivityForResult(pick,pickerRequest);return true;
        }catch(Exception unavailable){cancelFileChooser();once.onReceiveValue(null);message.setText("系统文件选择器不可用，请稍后重试");return true;}
    }
    private void cancelFileChooser(){dev.pocketengine.browser.BrowserArtifactUpload artifact=artifactUpload;artifactUpload=null;artifactUploadPage=null;if(artifact!=null)artifact.close();android.webkit.ValueCallback<android.net.Uri[]> callback=fileChooser,copying=uploadCallback;BrowserUploads.Destination picked=fileChooserDestination,importing=uploadDestination;fileChooser=null;fileChooserDestination=null;fileChooserRequest=0;uploadDestination=null;uploadCallback=null;if(picked!=null)picked.close();if(importing!=null)importing.close();if(callback!=null)callback.onReceiveValue(null);if(copying!=null)copying.onReceiveValue(null);}
    @Override protected void onActivityResult(int request,int result,android.content.Intent data){
        if(request==0||request!=fileChooserRequest){super.onActivityResult(request,result,data);return;}
        final android.webkit.ValueCallback<android.net.Uri[]> callback=fileChooser;final BrowserUploads.Destination destination=fileChooserDestination;final BrowserProfileRegistry.Session session=profileSession;final WebView sourceView=web;fileChooser=null;fileChooserDestination=null;fileChooserRequest=0;
        if(callback==null)return;
        if(result!=RESULT_OK||data==null||web==null||destination==null||!destination.matchesDestination(web.getUrl())){if(destination!=null)destination.close();callback.onReceiveValue(null);return;}
        uploadDestination=destination;uploadCallback=callback;
        try{browserIo.execute(()->{
            org.json.JSONArray importedValues=null;
            try{
                AttachmentRepository repository=AttachmentRepository.get(this);JSONObject imported=BrowserUploads.importPicked(this,data,destination);org.json.JSONArray values=imported.getJSONArray("attachments");importedValues=values;android.net.Uri[] uris=new android.net.Uri[values.length()];for(int i=0;i<values.length();i++)uris[i]=repository.uri(values.getJSONObject(i).getString("id"));
                JSONObject identity=destination.identity();final JSONObject attempt=identity.has("taskId")?uploadStore().beginBrowserUpload(identity,values):null;
                runOnUiThread(()->{
                    if(uploadDestination!=destination||isDestroyed()||web!=sourceView||!destination.matchesDestination(sourceView.getUrl())){destination.close();callback.onReceiveValue(null);uploadEventAsync(attempt,"cancelled_before_delivery",json("source","native_picker","reason","destination_changed"));try{browserIo.execute(()->discardUploads(values));}catch(java.util.concurrent.RejectedExecutionException stopped){discardUploads(values);}return;}
                    // Persist the boundary before WebView receives any URI. A crash leaves unknown, never retryable.
                    try{destination.check();if(attempt!=null)uploadStore().updateBrowserUpload(identity.getString("taskId"),attempt.getString("attemptId"),attempt.getString("fingerprint"),"delivery_entered",json("source","native_webview_callback","siteConfirmed",false));}
                    catch(Exception persistence){destination.close();callback.onReceiveValue(null);discardUploads(values);message.setText("上传尝试无法可靠登记，文件未交给网页。");return;}
                    uploadDestination=null;uploadCallback=null;destination.close();
                    try{if(attempt!=null){deliveredUploads.put(attempt.optString("attemptId"),attempt);while(deliveredUploads.size()>100)deliveredUploads.remove(deliveredUploads.keySet().iterator().next());}callback.onReceiveValue(uris.length==0?null:uris);if(attempt!=null){uploadEventAsync(attempt,"local_delivered",json("source","native_webview_callback","siteConfirmed",false));leaseTimers.postDelayed(()->uploadEventAsync(attempt,"site_unknown",json("source","bounded_timeout","note","30秒内没有主人确认站点结果；没有再次上传")),30000);}}
                    catch(RuntimeException uncertain){uploadEventAsync(attempt,"site_unknown",json("source","native_callback_unknown","note","回调结果未知，禁止自动重传"));message.setText("文件交付结果未知，请核对原网页；不会再次上传。");return;}
                    message.setText("文件已交给网页；这不证明站点接收。请在研究面板核对接收/拒绝结果，或尝试停止。");emitLeaseEvent(json("kind","attachments","status","local_delivery_attempted","attachments",values,"profileId",session.profileId,"profileRevision",session.profileRevision,"browserSessionId",session.sessionId,"uploadAccepted",false,"verified",false));
                });
            }catch(Exception failure){destination.close();if(importedValues!=null)discardUploads(importedValues);runOnUiThread(()->{if(uploadDestination==destination){uploadDestination=null;uploadCallback=null;}callback.onReceiveValue(null);if(failure instanceof BrowserUploads.CleanupRequired)emitLeaseEvent(json("kind","upload","status","cleanup_required","profileId",session.profileId,"browserSessionId",session.sessionId,"message","失效上传的临时附件清理未完成，请核对原任务附件"));if(!isDestroyed())message.setText("上传附件未能读取，请重新选择文件");});}
        });}catch(java.util.concurrent.RejectedExecutionException stopped){uploadDestination=null;uploadCallback=null;destination.close();callback.onReceiveValue(null);message.setText("文件读取已停止，请重新选择");}
    }
    private TaskStore uploadStore(){return ((EngineApplication)getApplication()).tasks;}
    private void uploadEventAsync(JSONObject attempt,String state,JSONObject evidence){if(attempt==null)return;try{browserIo.execute(()->{try{uploadStore().updateBrowserUpload(attempt.getJSONObject("pin").getString("taskId"),attempt.getString("attemptId"),attempt.getString("fingerprint"),state,evidence);}catch(Exception unknown){/* The durable pre-dispatch state remains unknown. Never resend. */}});}catch(java.util.concurrent.RejectedExecutionException closed){/* Durable attempt remains unknown. */}}
    /** stopLoading is best-effort only; WebView offers no per-file HTTP abort acknowledgement. */
    public JSONObject cancelUploadForOwner(JSONObject attempt)throws Exception {
        requireMain();JSONObject pin=attempt.getJSONObject("pin");Page page=pages.get(pin.getString("tabId"));boolean exact=page!=null&&page.profile.current()&&page.profile.sessionId.equals(pin.getString("browserSessionId"))&&page.navigation==pin.getLong("navigation")&&page.readObserver!=null&&page.readObserver.documentIdentityForNative().equals(pin.getString("documentId"));
        JSONObject retained=deliveredUploads.get(attempt.getString("attemptId"));boolean local=retained!=null&&retained.getString("fingerprint").equals(attempt.getString("fingerprint"));boolean requested=false;if(exact&&local)try{page.view.stopLoading();requested=true;}catch(RuntimeException unavailable){/* Keep cancellation uncertainty, never switch pages. */}
        return json("source","owner_cancel","stopLoadingRequested",requested,"platformAbortConfirmed",false,"localDeliveryState",attempt.getString("deliveryState"),"siteState",attempt.getString("siteState"),"note",requested?"已请求原WebView停止加载，但平台不能确认撤回已交付文件或站点请求。":"原页或进程句柄不可用；不能停止已交付上传，未操作其他页面。","automaticReplayAllowed",false);
    }
    private void discardUploads(org.json.JSONArray values){try{BrowserUploads.discard(this,values);}catch(Exception failure){emitLeaseEvent(json("kind","upload","status","cleanup_required","message","失效上传的临时附件清理未完成，请核对原任务附件"));}}
    private void requestDownload(String url,String userAgent,String disposition,String mime,long length){
        JSONObject identity=lease.snapshot();final BrowserProfileRegistry.Session session=profileSession;final WebView sourceView=web;final long version=navigation;takeover("网页下载需要你确认，自动执行已暂停");
        if(length>dev.pocketengine.features.attachments.AttachmentBook.MAX_FILE_BYTES){message.setText("此文件超过20MiB附件上限");return;}
        try{
            java.net.URI target=BrowserDownloads.valid(url);String name=dev.pocketengine.features.attachments.AttachmentBook.displayName(android.webkit.URLUtil.guessFileName(url,disposition,mime));
            if(downloadDialog!=null)downloadDialog.dismiss();
            downloadDialog=new android.app.AlertDialog.Builder(this).setTitle("下载附件").setMessage(name+"\n来源："+target.getHost()+"\n文件将保存在口袋世界中（最多20MiB）。").setNegativeButton("取消",null).setPositiveButton("下载",(dialog,which)->{
                if(isDestroyed()||web!=sourceView||profileSession!=session||session==null||!session.current()||navigation!=version){message.setText("下载所属网页或资料已变化，请重新确认");return;}
                try{JSONObject job=profiles.startDownload(this,sourceView,session,url,userAgent,name,mime,identity,event->runOnUiThread(()->{if(isDestroyed()||profileSession!=session||!session.current())return;message.setText(event.optString("status").equals("downloaded")?"下载完成，附件已保存":event.optString("message","正在下载…"));emitLeaseEvent(event);}));message.setText("下载已排队；可回到附件列表查看");emitLeaseEvent(job);}catch(Exception failure){message.setText("下载未能启动，请检查地址、资料与网络");}
            }).create();downloadDialog.show();
        }catch(Exception invalid){message.setText("此下载地址暂不支持；仅接受http/https文件链接");}
    }
    private void rendererGone(WebView view){
        Page page=page(view);if(page==null)return;boolean active=page==activePage;if(active)recoveryUrl=view.getUrl()==null?recoveryUrl:view.getUrl();
        if(page.taskOwner!=null){page.taskOwner.space().pageGoneNative(page.id);emitLeaseEvent(json("kind","task_page","status","page_unavailable","tabId",page.id,"browserSpaceId",page.taskOwner.space().spaceId));}
        if(active){closingProfile=true;takeover("网页进程已退出，原操作已停止；请重新打开需要的页面");closingProfile=false;}
        destroyPage(page,true);selectOwnerFallback();rendererRecoveryNeeded=active&&web==null;progress.setVisibility(View.GONE);updateNavigation();
    }
    private void updateNavigation(){if(previous!=null)previous.setEnabled(web!=null&&web.canGoBack());if(next!=null)next.setEnabled(web!=null&&web.canGoForward());}
    private void evaluateInFrame(JSONObject frame,String script,android.webkit.ValueCallback<String> callback,CompletableFuture<JSONObject> result)throws Exception {
        if(frame==null){evaluate(script,callback);return;}
        if(activePage==null||activePage.taskOwner==null||activePage.readObserver==null||activePage.taskGate==null)throw new SecurityException("frame动作没有原任务执行门禁");
        final Page original=activePage;original.taskOwner.check();original.taskGate.check();
        original.taskGate.entered();
        original.readObserver.evaluateAction(frame,script,()->{if(activePage!=original||web!=original.view)throw new SecurityException("frame所在页已变化");original.taskOwner.check();original.taskGate.check();},callback,error->{if(!result.isDone())result.complete(pendingMutation?unknownReceipt("原frame动作回执未知，不会重发"):json("ok",false,"accepted",false,"status","unavailable","code","frame_document_changed"));clearPending(result);});
    }
    private void evaluate(String script,android.webkit.ValueCallback<String> callback)throws Exception{if(activePage!=null&&activePage.taskOwner!=null){activePage.taskOwner.check();if(activePage.taskGate==null)throw new SecurityException("任务页面没有原操作门禁");activePage.taskGate.check();activePage.taskGate.entered();}web.evaluateJavascript(script,callback);}
    private void navigateForTask(String url)throws Exception {if(web==null||activePage==null||activePage.taskOwner==null||activePage.taskGate==null||!allowedUrl(url))throw new SecurityException("任务页或地址已失效");activePage.taskOwner.check();activePage.taskGate.check();invalidateObservation();activePage.taskGate.entered();if(activePage.readObserver!=null)activePage.readObserver.beforeNativeLoad(url);web.loadUrl(url);}
    private void navigate(String value){if(web==null){message.setText("网页进程已退出，请点击刷新恢复");return;}try{String url=value==null||value.trim().isEmpty()?"https://www.bing.com":value.trim();if(!url.contains("://")){if(url.contains(" ")||!url.contains("."))url="https://www.bing.com/search?q="+URLEncoder.encode(url,"UTF-8");else url="https://"+url;}if(!allowedUrl(url))throw new IllegalArgumentException();observationId=null;observationUrl=null;if(activePage!=null&&activePage.taskOwner!=null){activePage.taskOwner.check();if(activePage.taskGate==null)throw new SecurityException("任务页面没有原操作门禁");activePage.taskGate.check();activePage.taskGate.entered();}if(activePage!=null&&activePage.readObserver!=null)activePage.readObserver.beforeNativeLoad(url);web.loadUrl(url);}catch(Exception invalid){message.setText("请输入有效的网址或搜索内容。");}}
    private static boolean allowedUrl(String value){try{URI uri=new URI(value);return ("https".equalsIgnoreCase(uri.getScheme())||"http".equalsIgnoreCase(uri.getScheme()))&&uri.getHost()!=null&&uri.getUserInfo()==null;}catch(Exception invalid){return false;}}
    private void hideBrowserIme(){android.view.inputmethod.InputMethodManager ime=getSystemService(android.view.inputmethod.InputMethodManager.class);if(ime!=null)ime.hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(),0);if(address!=null)address.clearFocus();}
    private void confirmCloseForOwner(){
        requireMain();if(closeConfirmation!=null&&closeConfirmation.isShowing())return;
        hideBrowserIme();closeConfirmation=new android.app.AlertDialog.Builder(this).setTitle("关闭浏览器并结束网页执行？")
                .setMessage("关闭这些网页并结束这次浏览，不会把整个任务标为取消。已保存的来源、附件与登录资料保留。已发生的操作不会撤回；有待确认的结果请回任务查看。")
                .setNegativeButton("保留网页",null).setPositiveButton("关闭浏览器",(dialog,which)->{takeover("主人明确关闭浏览器；已派发动作请在原任务核对");finish();}).create();
        final android.app.AlertDialog confirmation=closeConfirmation;confirmation.setOnDismissListener(dialog->{if(closeConfirmation==confirmation)closeConfirmation=null;});confirmation.show();
    }
    private void handleBrowserBack(){
        // The platform offers Back to the IME before invoking this app callback. Insets may still
        // describe its hide animation; do not swallow another keyboard-only cycle here.
        if(pageDialog!=null){decidePageDialog(pageDialog,false,null);return;}
        if(ownerPanel!=null&&ownerPanel.backToPage())return;
        if(activePage!=null&&activePage.popupRoute!=null){closeOwnerPopup(activePage);return;}
        if(web!=null&&web.canGoBack()){takeover("主人已接管浏览器");web.goBack();return;}
        returnToOwnerForUi();
    }
    @Override public void onPause(){ownerForeground=false;if(popupConsent!=null){popupConsent.dismiss();popupConsent=null;popupConsentPage=null;popupRequest=null;}deniedSiteRequests.clear();if(pageDialog!=null){cancelPageDialog(null,BrowserPageDialog.Reason.OWNER_LEFT);takeover("你已离开网页，网页提示已取消；已发生的动作请回原任务核对。");}for(Page page:pages.values())page.view.onPause();super.onPause();}
    @Override public void onResume(){super.onResume();ownerForeground=true;if(web!=null)web.onResume();}
    @Override protected void onSaveInstanceState(Bundle state){Page owner=activePage!=null&&activePage.taskOwner==null?activePage:null;if(owner==null)for(Page page:pages.values())if(page.taskOwner==null){owner=page;break;}if(owner!=null&&profileSession!=null&&profileSession.current()){Bundle page=new Bundle();owner.view.saveState(page);state.putBundle("browserState",page);state.putString("browserProfileId",profileSession.profileId);state.putLong("browserProfileRevision",profileSession.profileRevision);state.putString("browserProfileIsolation",profileSession.isolation);if(owner.view.getUrl()!=null)state.putString("browserUrl",owner.view.getUrl());}super.onSaveInstanceState(state);}
    @Override public void onDestroy(){if(closeConfirmation!=null){closeConfirmation.dismiss();closeConfirmation=null;}if(current.get()==this)current.clear();closeProfileSession("浏览器已关闭");if(ownerPanel!=null)try{ownerPanel.close();}catch(Exception ignored){}ownerPanel=null;browserIo.shutdown();leaseTimers.removeCallbacksAndMessages(null);lease.setExpirationListener(null);super.onDestroy();}
    private Button button(String label){Button button=new Button(this);button.setText(label);button.setTextSize(13);button.setTextColor(0xff222222);button.setAllCaps(false);button.setMinWidth(0);button.setMinimumWidth(0);button.setMinHeight(dp(48));button.setPadding(dp(9),dp(4),dp(9),dp(4));return button;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private static JSONObject json(Object...pairs){JSONObject result=new JSONObject();for(int i=0;i+1<pairs.length;i+=2)try{result.put(String.valueOf(pairs[i]),pairs[i+1]);}catch(Exception ignored){}return result;}
}
