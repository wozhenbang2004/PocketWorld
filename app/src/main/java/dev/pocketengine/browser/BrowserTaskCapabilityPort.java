package dev.pocketengine.browser;

import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.NativeToolObservation;
import dev.pocketengine.core.NativeToolPlan;
import dev.pocketengine.core.NativeReadContextRepair;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import dev.pocketengine.features.plugins.PluginRules;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Adds task browsing to the original runner; it never prepares an operation or fabricates a grant. */
public final class BrowserTaskCapabilityPort implements CapabilityPort {
    /** Native Runner context query; only an exact owner-return registration can produce a read. */
    public static final String OWNER_RETURN_CONTEXT="native.browser.owner-return-observation.v1";
    public interface ApprovalPort {
        JSONObject approve(ModelCapabilityAdapters.Binding binding,RunControl originalControl,String operationId,String toolName,JSONObject normalizedArgs,JSONObject pinnedBrowserIdentity)throws Exception;
        default JSONObject admitTaskScope(ModelCapabilityAdapters.Binding binding,RunControl original,String operation,String tool,JSONObject args,JSONObject pin)throws Exception{return null;}
        default void checkTaskScope(ModelCapabilityAdapters.Binding binding,RunControl original,JSONObject permit)throws Exception{throw new SecurityException("原任务浏览scope未接入");}
        default JSONObject taskScopeSnapshot(ModelCapabilityAdapters.Binding binding,RunControl original)throws Exception{return new JSONObject().put("state","not_granted");}
        default boolean nativeReadAuthority(ModelCapabilityAdapters.Binding binding,RunControl original,String tool)throws Exception{return false;}
        default JSONObject admitReadScope(ModelCapabilityAdapters.Binding binding,RunControl original,String operation,String tool,JSONObject args,JSONObject pin)throws Exception{return null;}
        default void checkReadScope(ModelCapabilityAdapters.Binding binding,RunControl original,JSONObject permit)throws Exception{throw new SecurityException("原生只读许可端口未接入");}
        default JSONObject readScopeSnapshot(ModelCapabilityAdapters.Binding binding,RunControl original)throws Exception{return new JSONObject().put("state","not_granted");}
        default void checkReadContextRepair(ModelCapabilityAdapters.Binding binding,RunControl original,JSONObject actualSafeReceipt)throws Exception {throw new SecurityException("browser_context_repair_authority_unavailable");}
    }
    private final BrowserTaskSpace space;
    private final ModelCapabilityAdapters.Authority authority;
    private final ModelCapabilityAdapters.ResourceCheck resource;
    private final ApprovalPort approvals;
    private final BrowserTaskSpace.Ui ui;
    private final CapabilityPort existing;
    private final Map<String,JSONObject> schemas=new LinkedHashMap<>();
    private final Set<String> entered=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final AtomicBoolean inFlight=new AtomicBoolean();
    private volatile boolean uncertain,closed;
    private final PublicHttpReader publicHttp=new PublicHttpReader();
    private static final class VisualDelivery {final String operation;final NativeToolObservation image;VisualDelivery(String operation,NativeToolObservation image){this.operation=operation;this.image=image;}}
    private final java.util.concurrent.atomic.AtomicReference<VisualDelivery> visualDelivery=new java.util.concurrent.atomic.AtomicReference<>();
    private record PlanDelivery(String operation,NativeToolPlan plan) { }
    private final java.util.concurrent.atomic.AtomicReference<PlanDelivery> toolPlan=new java.util.concurrent.atomic.AtomicReference<>();
    private static final class ScopedReadCancel {final String scope;final PublicHttpReader.Budget budget;ScopedReadCancel(String scope,PublicHttpReader.Budget budget){this.scope=scope;this.budget=budget;}}
    private final java.util.concurrent.atomic.AtomicReference<ScopedReadCancel> scopedReadCancel=new java.util.concurrent.atomic.AtomicReference<>();
    private static final class OwnerReturnRead {
        final String token=java.util.UUID.randomUUID().toString();final JSONObject confirmed;final ModelCapabilityAdapters.ResourceCheck retainedAuthority;volatile JSONObject observedPin;volatile boolean sourceDelivered;
        OwnerReturnRead(JSONObject confirmed,ModelCapabilityAdapters.ResourceCheck retainedAuthority)throws Exception{this.confirmed=copy(confirmed);this.retainedAuthority=java.util.Objects.requireNonNull(retainedAuthority);}
    }
    private final java.util.concurrent.atomic.AtomicReference<OwnerReturnRead> ownerReturnRead=new java.util.concurrent.atomic.AtomicReference<>();
    private final AtomicBoolean returnReadInFlight=new AtomicBoolean();
    private volatile JSONObject completedOwnerReturnObservation;
    private volatile OwnerReturnRead completedOwnerReturnRead;
    private record OwnerReturnDelivery(String requestId,OwnerReturnRead read,JSONObject observation) { }
    private final java.util.concurrent.atomic.AtomicReference<OwnerReturnDelivery> ownerReturnDelivery=new java.util.concurrent.atomic.AtomicReference<>();
    /** Native host grant construction only; TaskStore remains the authority applying this list. */
    public static JSONArray taskToolNames(boolean sourceRetention)throws Exception {JSONArray names=new JSONArray();for(String command:new String[]{"open","select","observe","request_user","navigate","click","type","set_date","focus","press","select_option","set_checked","scroll","wait","download","download_status","retain_source","close","finish","observe_visual","evaluate","inspect","script","network","network_body","download_document","prepare_sequence","native_input","site_mode","resize","back","forward","reload","upload_artifact"})if(sourceRetention||!command.equals("retain_source"))names.put("browser_task_"+command);if(sourceRetention)names.put(PublicHttpReader.TOOL);return names;}
    public BrowserTaskCapabilityPort(BrowserTaskSpace space,ModelCapabilityAdapters.Authority authority,ModelCapabilityAdapters.ResourceCheck resource,ApprovalPort approvals,BrowserTaskSpace.Ui ui,CapabilityPort existing)throws Exception {
        this.space=java.util.Objects.requireNonNull(space);this.authority=java.util.Objects.requireNonNull(authority);this.resource=java.util.Objects.requireNonNull(resource);
        this.approvals=java.util.Objects.requireNonNull(approvals,"Original TaskStore approval bridge required");this.ui=java.util.Objects.requireNonNull(ui);this.existing=existing;
        add("open",props("url",text(2048),"pageRecordId",text(36)),"url");add("select",props("tabId",text(36)),"tabId");add("observe",props("tabId",text(36),"frameRef",text(36),"read",BrowserSemanticRead.schema(),"recipe",BrowserReadRecipe.referenceSchema()),"tabId");
        add("evaluate",props("tabId",text(36),"frameRef",text(36),"expression",text(8000)),"tabId","expression");
        add("inspect",props("tabId",text(36),"frameRef",text(36)),"tabId");
        add("script",props("tabId",text(36),"source",text(12000),"effectReview",text(1000)),"tabId","source","effectReview");
        add("prepare_sequence",props("tabId",text(36),"observationId",text(36),"steps",BrowserToolSequence.schema()),"tabId","observationId","steps");
        add("network",props("tabId",text(36),"action",new JSONObject().put("type","string").put("enum",new JSONArray().put("start").put("read").put("stop")),"backend",new JSONObject().put("type","string").put("enum",new JSONArray().put("witness").put("native")),"captureId",text(36)),"tabId","action");
        add("network_body",props("tabId",text(36),"captureId",text(36),"requestRef",text(36),"maxBytes",new JSONObject().put("type","integer").put("minimum",256).put("maximum",16384)),"tabId","captureId","requestRef");
        add("request_user",props("tabId",text(36),"reason",text(600)),"tabId","reason");
        add("navigate",props("tabId",text(36),"url",text(2048)),"tabId","url");
        for(String command:new String[]{"back","forward","reload"})add(command,props("tabId",text(36)),"tabId");
        add("site_mode",props("tabId",text(36),"mode",new JSONObject().put("type","string").put("enum",new JSONArray().put("mobile").put("desktop"))),"tabId","mode");
        add("resize",props("tabId",text(36),"width",new JSONObject().put("type","integer").put("minimum",64).put("maximum",4096),"height",new JSONObject().put("type","integer").put("minimum",64).put("maximum",4096)),"tabId","width","height");
        JSONObject coordinate=new JSONObject().put("type","integer").put("minimum",0).put("maximum",999);
        add("native_input",props("tabId",text(36),"observationId",text(36),"ref",text(4),"action",new JSONObject().put("type","string").put("enum",new JSONArray().put("tap").put("hover").put("drag").put("key")),"toRef",text(4),"x",coordinate,"y",coordinate,"toX",coordinate,"toY",coordinate,"key",new JSONObject().put("type","string").put("enum",new JSONArray().put("Enter").put("Escape").put("Tab").put("ArrowUp").put("ArrowDown").put("ArrowLeft").put("ArrowRight").put("Home").put("End").put("PageUp").put("PageDown").put("Backspace").put("Delete").put("Space"))),"tabId","observationId","ref","action");
        add("click",props("tabId",text(36),"observationId",text(36),"ref",text(4),"postcondition",BrowserActionReceipt.postconditionSchema()),"tabId","observationId","ref");
        add("type",props("tabId",text(36),"observationId",text(36),"ref",text(4),"text",text(4000),"postcondition",BrowserActionReceipt.postconditionSchema()),"tabId","observationId","ref","text");
        add("set_date",props("tabId",text(36),"observationId",text(36),"ref",text(4),"date",text(10)),"tabId","observationId","ref","date");
        add("focus",props("tabId",text(36),"observationId",text(36),"ref",text(4)),"tabId","observationId","ref");
        add("press",props("tabId",text(36),"observationId",text(36),"ref",text(4),"key",new JSONObject().put("type","string").put("enum",new JSONArray().put("Enter").put("Escape").put("ArrowUp").put("ArrowDown").put("ArrowLeft").put("ArrowRight").put("Home").put("End"))),"tabId","observationId","ref","key");
        add("select_option",props("tabId",text(36),"observationId",text(36),"ref",text(4),"optionRef",text(7)),"tabId","observationId","ref","optionRef");
        add("set_checked",props("tabId",text(36),"observationId",text(36),"ref",text(4),"checked",new JSONObject().put("type","boolean")),"tabId","observationId","ref","checked");
        add("scroll",props("tabId",text(36),"observationId",text(36),"dy",new JSONObject().put("type","integer").put("minimum",-3000).put("maximum",3000),"postcondition",BrowserActionReceipt.postconditionSchema()),"tabId","observationId","dy");
        add("wait",props("tabId",text(36),"condition",new JSONObject().put("type","string").put("enum",new JSONArray().put("load").put("url").put("text").put("target").put("barrier")),"value",text(2048),"target",BrowserTargetCondition.schema(),"barrier",BrowserWaitBarrier.schema(),"timeoutMs",new JSONObject().put("type","integer").put("minimum",100).put("maximum",6000)),"tabId","condition");
        add("download",props("tabId",text(36),"observationId",text(36),"url",text(2048)),"tabId","observationId","url");
        add("download_status",props("downloadId",text(36)),"downloadId");
        add("upload_artifact",props("tabId",text(36),"observationId",text(36),"ref",text(4),"artifact",BrowserArtifactSource.schema()),"tabId","observationId","ref","artifact");
        add("download_document",props("tabId",text(36),"observationId",text(36),"ref",text(4),"name",text(200)),"tabId","observationId","ref","name");
        if(space.sourcePortAvailable())add("retain_source",props("tabId",text(36),"observationId",text(36),"excerpt",text(6000)),"tabId","observationId");
        if(space.publicHttpAvailable())add("read_public",props("url",text(2048),"allowedRedirectOrigins",new JSONObject().put("type","array").put("maxItems",3).put("items",text(300)),
                "maxBytes",new JSONObject().put("type","integer").put("minimum",4096).put("maximum",PublicHttpReader.MAX_BYTES),
                "maxChars",new JSONObject().put("type","integer").put("minimum",64).put("maximum",PublicHttpReader.MAX_CHARS)),"url");
        if(space.visualAvailable())add("observe_visual",props("tabId",text(36),"crop",BrowserVisualCapture.cropSchema()),"tabId");
        add("close",props("tabId",text(36)),"tabId");add("finish",new JSONObject());
        if(existing!=null)for(String name:schemas.keySet())if(existing.supports(name))throw new IllegalArgumentException("任务浏览工具名称冲突");
    }
    private void add(String command,JSONObject properties,String... required)throws Exception {schemas.put("browser_task_"+command,new JSONObject().put("type","object").put("properties",properties).put("required",new JSONArray(required)).put("additionalProperties",false));}
    @Override public JSONArray tools()throws Exception {
        JSONArray tools=existing==null?new JSONArray():new JSONArray(existing.tools().toString());JSONArray own=ownTools();for(int i=0;i<own.length();i++)tools.put(own.getJSONObject(i));return tools;
    }
    private JSONArray ownTools()throws Exception {
        JSONArray tools=new JSONArray();if(closed||space.ended()||!space.binding.allowWrites)return tools;for(Map.Entry<String,JSONObject> tool:schemas.entrySet())tools.put(new JSONObject().put("type","function").put("function",new JSONObject().put("name",tool.getKey())
                .put("description",tool.getKey().equals("browser_task_upload_artifact")?"把本任务已存在的准确私人附件或工作区文件成果版本交给当前普通observe的原主文档file input。artifact={store:attachments或workspace,id,sha256}；workspace还必须含sourceVersion和sourceRevisionId。每次均需主人确认准确文件、SHA、版本与站点/控件，不属于常规自动scope；不接受路径、URI或其他任务成果。最多20MiB，只调用一次原native chooser；local_delivered仅说明交给WebView，站点结果仍unknown，必须主人核对原上传记录，未知不得自动重传。子frame、删除修订或没有冻结的工作区文件请主人处理或先通过原工作区工具保存成果。":tool.getKey().equals("browser_task_native_input")?"对原主文档最新普通observe的准确ref发送一次Android WebView真实输入，action=tap/hover/drag/key；保留旧DOM合成工具。drag必须同次已展示toRef；可选x/y、toX/toY为目标元素内0..999相对位置，默认中心，截图绝不授予坐标权限。只接实际前台视口，文档/滚动/缩放/遮挡/原节点/焦点变化拒绝；key需先聚焦再重新observe，仅枚举单键，不支持剪贴板或系统组合键。每次精确主人审批；密码、验证码、文件选择及子frame请主人处理。Android派发和600ms同原节点回查分开呈现，page-world的isTrusted见证可被网页篡改，不证明业务成功；派发后导航、取消、未知立停且不重试。":tool.getKey().equals("browser_task_site_mode")?"经本次精确审批，将当前原WebView实际UA与wide viewport/overview设置为mobile或desktop；不是桌面系统或UA Client Hints模拟。WebView可能因UA变化自行加载，不额外请求reload；返回后必须重新观察，旧坐标失效。":tool.getKey().equals("browser_task_resize")?"经本次精确审批，调整当前WebView真实宽高，width/height单位为物理像素、64..4096且不得超过当前可见容器；过大明确拒绝，不伪造设备分辨率。等实际布局并回读CSS viewport/DPR；不模拟DPR，需重新观察，旧坐标失效。":tool.getKey().matches("browser_task_back|browser_task_forward|browser_task_reload")?"经原prepared和本次精确审批对原tab执行一次真实WebView历史后退/前进/刷新；审批绑定实际历史目的地，历史改变拒绝。无可用历史明确no_change；导航请求不等于加载完成，之后重新observe或wait，不恢复旧ref，不重试未知操作。":tool.getKey().equals(BrowserToolSequence.TOOL)?"准备原Runner的1至4步浏览组合；steps支持observe/click/type/scroll/wait(load或text)。每个写动作前必须有observe，ref均来自准备时该普通observation；后续仅重新绑定同一nodeKey和相同目标身份，不按同名控件替换。各步仍走原operation、授权、预算、账本；导航、取消、未知、条件未满足即停，保留已完成步骤。不执行任意JS、导航、附件或权限动作。":tool.getKey().equals("browser_task_download_document")?"从原普通观察中的准确blob/data链接读取真实文件到原AttachmentBook，原文档/原nodeKey/导航/任务/资料绑定，最多20MiB/120秒。name是所需文件名。仅已展示主文档链接，HTTP请用原download；不点击链接或导航，native不发送HTTP或Cookie；原文档page-world读取桥可被网页篡改，文件内容始终低信任。返回queued不等于下载完成，须download_status=downloaded及实际附件hash/size。取消或未知不会自动重新下载。":tool.getKey().equals("browser_task_evaluate")?"真实当前DOM的隔离世界只读组合表达式，不是任意JS或snapshot求值。expression是JSON字符串，op支持literal(value)、item、query/queryAll(selector,可选input)、text/attr(name)/count/first/number/not(input)、map/filter(input,body以item引用节点)、record(fields各子表达式)、eq/contains/and/or/add/subtract/multiply/divide/gt/gte/lt/lte(left,right)，trim/lower/upper/sum/min/max/distinct/sort/slice/join(input)。slice用start/limit，sort可descending，join可separator。每次<=200节点/4000步骤/16KiB结果；不执行setter、调用页面函数或发网络。计算值与表达式保留在原operation，不冒充网页原文；需要引用正文用inspect或observe后retain_source。frameRef仅原已呈现文档，不降级。":tool.getKey().equals("browser_task_inspect")?"在原当前文档隔离世界真实采样两次，600ms间隔比较有界可见正文摘要；返回局部稳定证据、实际滚动余量、懒加载图片线索、下一页链接和障碍线索，不把局部稳定当全页稳定/网络idle。实际交付的正文可用本次observationId走原retain_source保存，计算结果不会冒充原文。不会自动滚动或翻页；需要时在任务授权内明确scroll/navigate后重新inspect。":tool.getKey().equals("browser_task_script")?"真实当前主文档页面JS函数体，必须逐次由主人审阅完整source与effectReview后批准；不属于常规自动范围。运行在page world，可读写DOM、使用登录态发网络/触发提交，非隔离安全沙箱，效果说明是模型声明并非证明。最多12KiB源码/16KiB结果；返回Promise、异常、超时、文档变化都效果unknown不得重放。返回后600ms原DOM只读回查，不证明服务器成功或所有异步效果结束。子frame通用脚本未支持，不能借main降级。":tool.getKey().equals(BrowserNativeNetwork.BODY_TOOL)?"逐个响应单独审批：captureId和requestRef必须来自本页原生采集已实际交付的完成响应，maxBytes默认8192、最多16384，每采集最多8次/65536字节。只读该CDP原请求缓存中的JSON响应，不发HTTP、不导出Cookie/Authorization或请求正文。敏感端点/非JSON/旧文档/已读取对象拒绝；正文敏感字段与明显凭据值脱敏并明确截断，仍属私有低信任内容。丢失、取消或未知不得重取，不属于常规任务/只读自动范围。":tool.getKey().equals("browser_task_network")?"省略backend保留原witness：start精确审批后安装当前主文档fetch/XHR wrapper，页面可篡改或绕过，不是CDP。backend=native使用本进程固定LocalSocket的真实WebView CDP，须主人在更多菜单允许本次进程调试及本次start精确审批；用原隔离文档nonce认证目标，不按URL猜。native read/stop须原captureId。最多120秒/128行，只覆盖启用后开始的原主frame/loader请求，排除旧请求、子frame、worker等；不宣称全局网络idle。metadata含脱敏URL、状态、MIME、时序和有界Retry-After，不含正文或凭据头。正文须另用network_body逐个响应审批。导航/接管/取消/资料或epoch变化即失效；stop不撤销网站请求。不自动reload、重发或降级冒充。":tool.getKey().equals(BrowserVisualCapture.TOOL)?"仅文字/树不足以理解图表、canvas或布局时，按需取得当前已选任务页的一次真实截图。须原截图设置、当前模型图像资格/预算及本次精确主人审批；不包含在自动文字read-scope中。crop可选，单位是当前原WebView viewport物理像素，不是点击权限；默认当前可见viewport、最多1Mpx/2MiB。密码/验证码、未核嵌入页、键盘/提示/研究面板遮挡或页面滚动/缩放/导航变化时不可捕。只走原native图像输入，不在JSON中返图、不默认保存图像，也不据旧图坐标执行操作。":tool.getKey().equals("browser_task_open")?"在原prepared和原任务明确站点范围内开任务页，超范围才精确审批。连续翻页请使用observe实际链接地址后browser_task_navigate；链接优先原生navigate而非执行onclick；仅主人任务范围中明确的交互动作与完整控件名称/#id可连续click/选择/勾选，页面标签不是安全证明，已知提交/敏感/有后果及越界动作仍精确审批。恢复时可带taskBrowser.recovery已展示的logicalPageId为pageRecordId，url须逐字等于该记录地址；不恢复旧tab/session/action ref。地址查询参数可能未保存，须核对后重新观察，未知提交不得重发。":tool.getKey().equals("browser_task_observe")?"读取明确tab的网页。省略read保留原动作refs与有界control状态（单选下拉每项optionRef/value/label/disabled/selected；checkbox/radio的checked）。optionsOmitted表示未展示选项，不能猜ID；read.mode=outline/text/table/list按任务query字面词定向找区域、读长文或保序提取表格列表。动态正文/表格可带read.settle={timeoutMs:4000,stableMs:600,minItems:1}，在同一原frame/document/区域内持续采样至条件满足或最多6秒/32探针；busy、空表或观察器不可用不冒充稳定。翻页后可加afterProjectionSha256为前页read.projectionSha256，旧表未更新不算新页完成；这只是读取条件，不执行翻页。read.dynamic给最近容器内实际分页控件（无动作ref）、busy及有界覆盖；read.settle.conditionMet=false时仅最后实际投影，不能说页面已完成。局部mutation安静并非网络idle/完整页面稳定，绝不补发网页请求。默认主文档；frameCoverage中实际展示的子frameId可填read.frameRef，后续该frame的regionRef/cursor均须带同frameRef。只对原生明确允许的origin且已成功桥接的frame定向执行；未支持provider/未桥接/未许可/旧proxy明确不可读，不自动reload登录页。read.frameRef/regionRef/cursor均只读证据、不能变动作refs。需要操作已呈现子frame时，另以普通observe的frameRef显式读取并取得独立动作refs，原文档与审批仍分别核对。续读须同mode，仅cursor；预算耗尽可返回空data和cursor，表示剩余DOM或行尚未搜索而非不命中，继续原cursor可读到后续范围。table/list的index保原区域行序；每次facts只说明本次有界切片，continued_region_slice尾片也不是完整区域基线。续读中区域变更/文档切换拒旧cursor；已读区域可带原read.delivery.revision为baseRevision刷新。delivery.format=full完整替换；delta须用baseRevision的data在patch.at删除patch.remove项并插入本次read.data，textChanged时替换text，否则沿用基线；unchanged沿用基线text/data，新read.facts仍为本次事实。只有同文档完整区域且基线实际留在本次模型上下文才差分；基线未知/缺失/覆盖不对称给明确full_resync，不推断整页删除。每次32KiB，覆盖/截断/缺失frame和续读均显式，不隐式滚动/导航/截图。字段/链接出自真实DOM而非另一个模型。可用当前taskBrowser.readRecipes精确id/revision/digest的recipe；read.mode/query必须与配方一致，配方只校验当前页schema并给逐字字段。drift后在原授权内普通observe探索，不自动导航、重试或取得新权限。":tool.getKey().equals("browser_task_wait")?"condition=barrier可用barrier={kind:dom|network|dom_network,quietMs:100至2000,网络时captureId}；在同一原文档内观察主文档light DOM MutationObserver及已启用原生采集的请求安静窗口。它不会自行启用网络采集；未覆盖frame/旧请求/未来定时任务始终未知，networkIdleProven=false。最多6秒/32探针，自适应退避，timeout/unavailable明确返回，不重放动作。原load/url/text等待保持兼容。condition=target时仅target：regionRef须原页已正式展示且未过期；可用selector.role/name在该区域light/open-shadow DOM精确选唯一子目标。state=present/absent；text为规范化文字包含value；value为非敏感表单值逐字等于value；checked/enabled用expected布尔值。子目标未出现可等，歧义/不完整范围/私密控件返回unknown，不扩大到整页；子frame须显式提供原已呈现frameRef并保留原document身份。stableMs仅要求同目标实例及所读状态持续满足，不要求全页静止；最多原6秒/32探针，返回实际局部证据，不点击/重发动作或证明服务器业务成功。":tool.getKey().equals("browser_task_retain_source")?"在原来源保存同意/精确审批内保留本次已读依据。省略excerpt且本次read.mode是table/list时，仅保存实际交付模型的有界结构化单元格/表头，最多12KiB，不自动抓整页；返回固定正文版本fieldRefs。原回答可用[字段依据](原fieldRef)逐项引用。明确excerpt时仅保存实际已交付文字摘录，不顺带保存表格。未登记实际交付或旧文档拒绝；保存回执不是网站业务证明。":tool.getKey().equals(PublicHttpReader.TOOL)?"只读无账号公共HTTP正文并保留原task来源：无Profile Cookie/Authorization/系统代理凭据，固定已批URL/redirect origins，≤3跳、全链8秒、默认256KiB/最多512KiB正文，按partial/MIME/charset诚实返回。JS/登录需显式原browser_task_open再request_user，受限地址不能借fallback绕过。":tool.getKey().equals("browser_task_select_option")?"只选择同一次普通observe中实际展示的原生单选下拉optionRef，不能猜隐藏选项或传任意value。保原task/doc/session/epoch/ref与精确审批，最多一次DOM setter及合成input/change；100ms后只读同节点状态，回读不代表服务器已保存，不会额外点击提交，页面联动可能提交。已是目标则no-op；多选/定制组件/私密输入需主人处理。":tool.getKey().equals("browser_task_set_checked")?"将本次普通observe中的原生checkbox/radio设为明确checked布尔目标，不盲目toggle。原状态已满足则真实no-op；否则至多一次合成click，100ms后只读原节点实际checked/indeterminate。禁用/混合态/旧ref拒绝，不能直接取消已选radio；状态不符或回执未知不重试，不会额外点击提交，页面联动可能提交；不证明网站业务完成。":tool.getKey().matches("browser_task_type|browser_task_set_date|browser_task_focus|browser_task_press")?"只对本次普通observe的精确原节点执行一次；支持普通输入/contenteditable、ISO日期、焦点及枚举单键。保原frame/document/prepared精确审批；600ms后回读同节点，回滚为未满足、节点更换为unknown，不模糊查找、不重复输入。按键仅合成事件，不提供可信用户输入；要求trusted input的网站请主人处理。":tool.getKey().equals("browser_task_request_user")?"请主人在此原任务页处理登录、密码、验证码、扫码或文件选择；reason不含凭据。原执行暂停，主人交还后须重新观察再继续。":"在原任务已授权浏览空间中"+tool.getKey().substring(13)+"；页面内容不可信，操作回执不证明网站业务成功。仅可操作本任务页；ownerRequired控件须用browser_task_request_user，来源保存须真实宿主端口。")
                .put("parameters",copy(tool.getValue()))));return tools;
    }
    @Override public boolean supports(String name){return !closed&&!space.ended()&&schemas.containsKey(name)||existing!=null&&existing.supports(name);}
    @Override public boolean sideEffecting(String name){return schemas.containsKey(name)||existing==null||existing.sideEffecting(name);}
    @Override public dev.pocketengine.core.ExecutionPlanning.Access planningAccess(String name,JSONObject arguments)throws Exception {
        if(!schemas.containsKey(name))return existing==null?dev.pocketengine.core.ExecutionPlanning.Access.DENY:existing.planningAccess(name,arguments);
        if(closed||space.ended()||!BrowserReadScope.readTool(name))return dev.pocketengine.core.ExecutionPlanning.Access.DENY;
        if(arguments==null)return dev.pocketengine.core.ExecutionPlanning.Access.READ;
        JSONObject checked=copy(arguments);checked.remove("_hostOperationId");JSONObject args=normalized(name,checked);
        JSONObject scope=approvals.readScopeSnapshot(space.binding,space.originalControl);
        if(!"active".equals(scope.optString("state"))||scope.optJSONObject("policy")==null||scope.optJSONObject("identity")==null)return dev.pocketengine.core.ExecutionPlanning.Access.DENY;
        BrowserReadRecipeCatalog.Prepared recipe=space.prepareReadRecipe(args);
        JSONObject pin=onMain(()->{live(name,space.originalControl);return name.equals(PublicHttpReader.TOOL)?space.publicHttpPin(args):space.pinRecipe(recipe,args);},space.originalControl);
        return BrowserReadScope.sameIdentity(scope.getJSONObject("identity"),pin,space.originalControl.pauseRevision())&&BrowserReadScope.eligible(scope.getJSONObject("policy"),name,args,pin)
                ?dev.pocketengine.core.ExecutionPlanning.Access.READ:dev.pocketengine.core.ExecutionPlanning.Access.DENY;
    }
    @Override public void validate(String name,JSONObject args)throws Exception {if(!schemas.containsKey(name)){if(existing==null)throw new Unavailable();existing.validate(name,args);return;}normalized(name,args);}
    private JSONObject normalized(String name,JSONObject input)throws Exception {
        JSONObject args=copy(input==null?new JSONObject():input);if(args.toString().length()>16000)throw new IllegalArgumentException("任务浏览参数过大");PluginRules.validateArguments(args,schemas.get(name));
        for(java.util.Iterator<String> keys=args.keys();keys.hasNext();){String key=keys.next();if(key.startsWith("_")||key.matches("taskId|runId|epoch|profileId|profileRevision|browserSessionId|browserSpaceId|operationId|owner|grant"))throw new SecurityException("模型不能提供任务浏览身份或授权");}
        for(String key:new String[]{"tabId","observationId","downloadId","pageRecordId","captureId","requestRef"})if(args.has(key))BrowserTaskSpace.uuid(args.getString(key));
        if(args.has("ref")&&!args.getString("ref").matches("n[0-9]{1,3}"))throw new IllegalArgumentException("需要该页最新观察中的ref");
        if(args.has("optionRef")&&!args.getString("optionRef").matches("o[0-9]{1,6}"))throw new IllegalArgumentException("需要本次已展示的optionRef");
        if(name.equals("browser_task_observe")&&args.has("read"))BrowserSemanticRead.validate(args.getJSONObject("read"));if(args.has("recipe")){if(!name.equals("browser_task_observe")||!args.has("read"))throw new IllegalArgumentException("只读配方只用于明确read的单次observe");BrowserReadRecipe.reference(args.getJSONObject("recipe"));}
        if(name.equals("browser_task_evaluate"))BrowserPagePrograms.validateExpression(args.getString("expression"));
        if(name.equals("browser_task_script"))BrowserPagePrograms.validateScript(args);
        if(name.equals("browser_task_native_input"))BrowserNativeInput.validate(args);
        if(name.equals("browser_task_resize"))BrowserSiteMode.validateResize(args);
        if(name.equals(BrowserToolSequence.TOOL))BrowserToolSequence.validate(args);
        if(name.equals("browser_task_upload_artifact"))BrowserArtifactSource.validate(args.getJSONObject("artifact"));
        if(name.equals("browser_task_network")){boolean nativeBackend=args.optString("backend","witness").equals("native"),start=args.getString("action").equals("start");if(args.has("captureId")&&(!nativeBackend||start)||nativeBackend&&!start&&!args.has("captureId"))throw new IllegalArgumentException("原生读取/停止须明确原captureId；启用及witness不接受captureId");}
        if(name.equals("browser_task_wait")){String condition=args.getString("condition");if(condition.equals("barrier")){if(args.has("value")||args.has("target"))throw new IllegalArgumentException("屏障条件只接受barrier");BrowserWaitBarrier.validate(args.getJSONObject("barrier"));}else{if(args.has("barrier"))throw new IllegalArgumentException("barrier只用于屏障条件");if(condition.equals("target")){if(args.has("value"))throw new IllegalArgumentException("目标等待的比较值仅放在target内");BrowserTargetCondition.validate(args.getJSONObject("target"));}else{if(args.has("target")||condition.equals("load")&&args.has("value")||!condition.equals("load")&&args.optString("value").isEmpty())throw new IllegalArgumentException("请指定实际等待条件，加载等待不接受额外内容");if(condition.equals("url")){java.net.URI target=new java.net.URI(args.getString("value"));if(target.getHost()==null||target.getUserInfo()!=null||!Set.of("http","https").contains(target.getScheme()))throw new IllegalArgumentException("等待地址须为完整http/https网页");}}}}
        if(name.equals(PublicHttpReader.TOOL))PublicHttpReader.validate(args);
        if(BrowserTextControls.command(name.substring("browser_task_".length())))BrowserTextControls.validate(name.substring("browser_task_".length()),args);
        if(args.has("frameRef")){BrowserTaskSpace.uuid(args.getString("frameRef"));if(args.has("read"))throw new IllegalArgumentException("动作观察frameRef与只读read不可混用");}
        BrowserActionReceipt.validatePostcondition(args,name.substring("browser_task_".length()));
        if(args.has("url")){if(name.equals("browser_task_download"))BrowserDownloads.valid(args.getString("url"));else{java.net.URI url=new java.net.URI(args.getString("url"));if(url.getHost()==null||url.getUserInfo()!=null||url.getScheme()==null||!Set.of("http","https").contains(url.getScheme().toLowerCase(java.util.Locale.ROOT)))throw new IllegalArgumentException("浏览页仅接受不含凭据的完整http/https地址");}}return args;
    }
    @Override public JSONObject contextSnapshot(String query)throws Exception {
        if(OWNER_RETURN_CONTEXT.equals(query)){
            OwnerReturnRead returned=ownerReturnRead.get();if(returned!=null)return new JSONObject().put("browserReturnObservation",observeOwnerReturn(returned));
            JSONObject complete=completedOwnerReturnObservation;OwnerReturnRead completed=completedOwnerReturnRead;if(complete!=null&&completed!=null&&complete.optLong("pauseRevision",-1)==space.originalControl.pauseRevision()){completed.retainedAuthority.check();live("browser_task_observe",space.originalControl);if(completed.observedPin!=null)onMain(()->{space.requirePin(completed.observedPin,new JSONObject().put("tabId",completed.observedPin.getString("tabId")));return new JSONObject();},space.originalControl);return new JSONObject().put("browserReturnObservation",copy(complete));}return new JSONObject();
        }
        JSONObject context=existing==null?new JSONObject():copy(existing.contextSnapshot(query));JSONObject browserContext=taskBrowserSnapshot(null);if(!space.ended())browserContext.put("readRecipes",space.readRecipesForHost());context.put("taskBrowser",browserContext);return context;
    }
    private JSONObject observeOwnerReturn(OwnerReturnRead returned)throws Exception {
        if(ui.isMain()||!returnReadInFlight.compareAndSet(false,true))throw new SecurityException("交还观察只能由原Runner顺序读取");String target="";
        try{
            returned.retainedAuthority.check();live("browser_task_observe",space.originalControl);final JSONObject pin=onMain(()->space.returnObservationPin(returned.confirmed),space.originalControl);
            if("no_task_page".equals(pin.optString("status")))return rememberReturnObservation(returned,pin.put("kind","browser_owner_return_observation").put("pauseRevision",space.originalControl.pauseRevision()).put("untrustedPageContent",true).put("verified",false));
            target=pin.getString("tabId");final JSONObject args=new JSONObject().put("tabId",target);BrowserTaskSpace.Gate gate=()->{live("browser_task_observe",space.originalControl);};
            CompletableFuture<CompletableFuture<JSONObject>> call=new CompletableFuture<>();ui.post(()->{if(call.isDone())return;try{gate.check();space.requirePin(pin,args);call.complete(space.dispatch("wait",new JSONObject(args.toString()).put("condition","load").put("timeoutMs",6000),"",gate));}catch(Exception failure){call.completeExceptionally(failure);}});
            JSONObject loaded=await(await(call,space.originalControl,"browser_task_observe",8000,null),space.originalControl,"browser_task_observe",8000,null);if(!loaded.optBoolean("accepted",true)||"failed".equals(loaded.optString("status")))throw new IllegalStateException("交还网页尚未加载完成");
            CompletableFuture<CompletableFuture<JSONObject>> read=new CompletableFuture<>();ui.post(()->{if(read.isDone())return;try{gate.check();space.requirePin(pin,args);read.complete(space.dispatch("observe",args,"",gate));}catch(Exception failure){read.completeExceptionally(failure);}});
            JSONObject raw=await(await(read,space.originalControl,"browser_task_observe",8000,null),space.originalControl,"browser_task_observe",8000,null);JSONObject actual=onMain(()->space.bindReturnObservation(pin,raw),space.originalControl);live("browser_task_observe",space.originalControl);returned.retainedAuthority.check();returned.observedPin=copy(pin);JSONObject presented=boundedObservation(actual);onMain(()->space.presentedObservation(presented),space.originalControl);return rememberReturnObservation(returned,presented.put("kind","browser_owner_return_observation").put("status","observed").put("ok",true).put("accepted",true).put("freshObservationAvailable",true).put("freshObservationRequired",false));
        }catch(Exception failed){
            returned.retainedAuthority.check();if(closed||space.ended())throw failed;if(!space.waitingForOwner())resource.check();final String tab=target.isEmpty()?returned.confirmed.optString("tabId"):target;if(tab.isEmpty())throw failed;
            onMain(()->{space.returnObservationFailedNative(tab);return new JSONObject();},null);if(!space.waitingForOwner())throw failed;
            return rememberReturnObservation(returned,space.identity().put("kind","browser_owner_return_observation").put("pauseRevision",space.originalControl.pauseRevision()).put("status","unavailable").put("ok",false).put("accepted",false).put("requiresOwnerReturn",true).put("freshObservationAvailable",false).put("verified",false).put("message","尚未取得交还网页的新观察，请检查原页面并再次交还；没有继续模型或重试网页动作"));
        }finally{ownerReturnRead.compareAndSet(returned,null);returnReadInFlight.set(false);}
    }
    private JSONObject rememberReturnObservation(OwnerReturnRead read,JSONObject observation)throws Exception {observation.put("ownerReturnNonce",read.token);completedOwnerReturnRead=read;completedOwnerReturnObservation=copy(observation);return observation;}
    private JSONObject taskBrowserSnapshot(RunControl control)throws Exception {
        JSONObject snapshot=onMain(()->space.snapshot(),control);try{snapshot.put("readScope",approvals.readScopeSnapshot(space.binding,space.originalControl));}catch(Exception unavailable){snapshot.put("readScope",new JSONObject().put("state","unavailable"));}try{snapshot.put("taskActionScope",approvals.taskScopeSnapshot(space.binding,space.originalControl));}catch(Exception unavailable){snapshot.put("taskActionScope",new JSONObject().put("state","unavailable"));}return snapshot;
    }
    @Override public JSONObject execute(String name,JSONObject supplied,RunControl control)throws Exception {
        if(!schemas.containsKey(name)){if(existing==null)throw new Unavailable();return existing.execute(name,supplied,control);}
        java.util.concurrent.atomic.AtomicReference<JSONObject> admitted=new java.util.concurrent.atomic.AtomicReference<>();JSONObject result=executeOwned(name,supplied,control,admitted);JSONObject permit=admitted.get();return permit!=null&&permit.optString("kind").equals("task_browser_actions")?BrowserActionScope.receipt(result,permit):BrowserReadScope.receipt(result,permit);
    }
    private JSONObject executeOwned(String name,JSONObject supplied,RunControl control,java.util.concurrent.atomic.AtomicReference<JSONObject> admitted)throws Exception {
        if(closed||space.ended())throw new Unavailable();
        if(ownerReturnRead.get()!=null||returnReadInFlight.get())throw new SecurityException("交还后的真实网页观察尚未完成");
        if(ui.isMain())throw new IllegalStateException("任务浏览工具必须由原有界worker调用");
        if(!inFlight.compareAndSet(false,true))throw new SecurityException("上一浏览调用尚未结束，不能并发切换页面");
        JSONObject args=null,actionPin=null;CompletableFuture<JSONObject> pending=null;boolean preparedVerified=false;AtomicBoolean nativeEntered=new AtomicBoolean();String operationId="";
        try{
            if(control!=space.originalControl||!control.matchesHostRun(space.binding.taskId,space.binding.runId,space.binding.epoch))throw new SecurityException("不能替换原任务浏览控制器");if(!space.binding.allowWrites)throw new SecurityException("此原任务未授权任务浏览写操作");
            JSONObject original=copy(supplied==null?new JSONObject():supplied);Object operation=original.remove("_hostOperationId");if(!(operation instanceof String))throw new SecurityException("缺少原AgentRunner预留的operation");operationId=(String)operation;BrowserTaskSpace.uuid(operationId);args=normalized(name,original);
            authority.requirePrepared(operationId,name,copy(original));preparedVerified=true;live(name,control);if(uncertain||entered.contains(operationId))return unknown(operationId,null,name,args,actionPin,nativeEntered.get());
            final JSONObject normalized=args;final BrowserReadRecipeCatalog.Prepared recipe=space.prepareReadRecipe(normalized);JSONObject pin=onMain(()->{live(name,control);return name.equals(PublicHttpReader.TOOL)?space.publicHttpPin(normalized):name.equals(BrowserVisualCapture.TOOL)?space.visualPin(normalized):space.pinRecipe(recipe,normalized);},control);actionPin=copy(pin);
            JSONObject readPermit=name.equals("browser_task_upload_artifact")?null:approvals.admitReadScope(space.binding,space.originalControl,operationId,name,copy(args),copy(pin));if(readPermit!=null)admitted.set(copy(readPermit));
            final JSONObject actionPermit=readPermit==null&&!name.equals("browser_task_upload_artifact")?approvals.admitTaskScope(space.binding,space.originalControl,operationId,name,copy(args),copy(pin)):null;if(actionPermit!=null)admitted.set(copy(actionPermit));
            if(readPermit==null&&actionPermit==null){JSONObject approval=approvals.approve(space.binding,space.originalControl,operationId,name,copy(args),copy(pin));
                if(approval==null||!approval.optBoolean("approved"))return BrowserActionReceipt.attach(name.substring("browser_task_".length()),args,actionPin,null,false,new JSONObject().put("ok",false).put("accepted",false).put("code","approval_required").put("approvalId",approval==null?"":approval.optString("approvalId")).put("message","需要主人确认原参数、来源范围与页面目标，尚未派发"));BrowserTaskSpace.uuid(approval.getString("approvalId"));}
            live(name,control);authority.requirePrepared(operationId,name,copy(original));
            if(entered.size()>=1000)throw new IllegalStateException("本执行浏览调用数量超限");entered.add(operationId);
            final String op=operationId;final JSONObject originalArgs=original;final String command=name.substring("browser_task_".length());
            BrowserTaskSpace.Gate gate=new BrowserTaskSpace.Gate(){public void check()throws Exception{live(name,control);if(recipe!=null)recipe.requireCurrent();authority.requirePrepared(op,name,copy(originalArgs));if(readPermit!=null)approvals.checkReadScope(space.binding,control,readPermit);if(actionPermit!=null)approvals.checkTaskScope(space.binding,control,actionPermit);}public void entered(){nativeEntered.set(true);}public void liveCheck()throws Exception{live(name,control);if(readPermit!=null)approvals.checkReadScope(space.binding,control,readPermit);if(actionPermit!=null)approvals.checkTaskScope(space.binding,control,actionPermit);}};
            if(command.equals("prepare_sequence")){
                onMain(()->{gate.check();space.requirePin(pin,normalized);return new JSONObject();},control);
                RunControl.AuthorityCheck current=()->{live(name,control);onMain(()->{space.requireSequencePage(pin);return new JSONObject();},control);};
                NativeToolPlan plan=NativeToolPlan.captureBrowser(op,control,BrowserToolSequence.identity(pin),normalized.getJSONArray("steps").length(),(index,actual)->{
                    current.check();JSONObject observed=onMain(()->space.sequenceObservation(normalized.getString("tabId")),control);
                    return BrowserToolSequence.resolve(normalized,pin,index,observed,current);
                },current);
                PlanDelivery previous=toolPlan.getAndSet(new PlanDelivery(op,plan));if(previous!=null)previous.plan.close();
                return space.identity().put("operationId",op).put("ok",true).put("accepted",true).put("verified",false).put("status","sequence_prepared").put("totalSteps",plan.size()).put("dispatchEnteredSteps",0).put("originalRunnerDispatch",true).put("eachStepRequiresOriginalOperation",true).put("automaticReplayAllowed",false);
            }
            if(command.equals("request_user")){
                JSONObject handoff=onMain(()->{gate.check();space.requirePinRecipe(pin,normalized,recipe);JSONObject value=space.dispatch(command,normalized,op,gate).getNow(null);if(value==null)throw new IllegalStateException("人工接管尚未取得原页回执");return value;},control);
                if(space.originalControl.isCancelled()||space.ended())return unknown(operationId,handoff,name,args,actionPin,nativeEntered.get());
                return handoff.put("ok",true).put("operationId",operationId).put("freshObservationRequired",true);
            }
            if(command.equals("read_public")){
                PublicHttpReader.Budget budget=new PublicHttpReader.Budget(control);
                ScopedReadCancel cancellation=readPermit==null?null:new ScopedReadCancel(readPermit.getString("scopeId"),budget);if(cancellation!=null)scopedReadCancel.set(cancellation);
                BrowserTaskSpace.Gate publicGate=new BrowserTaskSpace.Gate(){public void check()throws Exception{budget.check();gate.check();onMain(()->{space.requirePublicHttpPin(pin,normalized);return new JSONObject();},control);budget.checkInsideStore();}public void entered(){gate.entered();}};
                java.util.concurrent.atomic.AtomicReference<JSONObject> sourceProgress=new java.util.concurrent.atomic.AtomicReference<>();
                try{return PublicHttpReader.bounded(budget,()->{
                    publicGate.check();PublicHttpReader.Result fetched=publicHttp.read(normalized,budget,publicGate,control);
                    JSONObject receipt=copy(fetched.value).put("operationId",op);
                    if(fetched.readable()){
                        publicGate.check();JSONObject saved=space.retainPublicSource(fetched,op,publicGate,id->{try{sourceProgress.set(new JSONObject().put("sourceId",id).put("status","unknown"));}catch(Exception invalid){throw new IllegalStateException(invalid);}});sourceProgress.set(copy(saved));
                        publicGate.check();
                        receipt.put("source",saved).put("sourceRetained",saved.optBoolean("sourceRetained"));
                        if(saved.optString("status").equals("unknown")){uncertain=true;return unknown(op,saved,name,normalized,pin,nativeEntered.get());}
                        boolean partial=fetched.value.getJSONObject("http").optBoolean("bodyTruncated")||fetched.value.getJSONObject("http").optBoolean("projectionTruncated");
                        receipt.put("sourceId",saved.getString("sourceId")).put("message",partial?"仅保留预算内的公共正文片段，已按partial来源保存；未声称完整，需更多内容请走原任务WebView。":"已匿名读取本次公共HTTP响应并保留原任务来源；这是未渲染的服务器正文投影，不是渲染后整页或业务完成。");
                    }else if(receipt.optString("status").equals("unknown"))uncertain=true;
                    return receipt;
                });}catch(Exception failure){
                    if(nativeEntered.get()){uncertain=true;return unknown(op,sourceProgress.get(),name,normalized,pin,true);}
                    if(failure instanceof TimeoutException)return PublicHttpReader.failed("unavailable","public_http_budget_expired",false,budget,null).value.put("operationId",op);
                    throw failure;
                }finally{if(cancellation!=null)scopedReadCancel.compareAndSet(cancellation,null);}

            }
            if(name.equals(BrowserVisualCapture.TOOL)){
                CompletableFuture<BrowserVisualCapture.Frame> capture=new CompletableFuture<>();ui.post(()->{if(capture.isDone())return;try{gate.check();space.captureVisual(normalized,pin,gate,capture);}catch(Exception unavailable){capture.completeExceptionally(unavailable);}});
                try(BrowserVisualCapture.Frame frame=await(capture,control,name,8000,null,gate,operationId)){
                    gate.check();byte[] encoded=frame.encodePng();try{
                        gate.check();frame.requirePageCurrent();NativeToolObservation image=NativeToolObservation.capture(name,op,control,frame.identity,encoded,"image/png",()->{live(name,control);frame.requirePageCurrent();onMain(()->{space.requireVisualPin(pin,normalized);return new JSONObject();},control);});
                        VisualDelivery previous=visualDelivery.getAndSet(new VisualDelivery(op,image));if(previous!=null)previous.image.close();
                        NativeToolObservation.Identity id=frame.identity;return space.identity().put("tabId",id.tabId()).put("tabGeneration",id.tabGeneration()).put("operationId",op).put("ok",true).put("accepted",true).put("verified",false).put("status","captured").put("kind","browser_visual_capture_receipt").put("documentId",id.documentId()).put("observationId",id.observationId()).put("navigation",id.navigation()).put("capturedAt",id.capturedAt()).put("frameId","main").put("viewport",copy(pin.getJSONObject("visual").getJSONObject("viewport")))
                                .put("captureMethod","android_window_pixelcopy").put("privacyCheckCoverage","bounded_main_and_open_shadow_known_controls_not_complete_secret_classification").put("pixelStabilityAsserted",false).put("imageDelivery","pending_original_native_image_path_not_yet_confirmed").put("imageRetained",false).put("coordinateAuthority",false).put("untrustedPageContent",true).put("message","已取得这次原网页的真实可见图像；仅等待原模型图像通路接收，未声称模型已看到，也未保存成历史来源。");
                    }finally{java.util.Arrays.fill(encoded,(byte)0);}
                }finally{capture.cancel(false);}
            }
            if(command.equals("retain_source")){
                BrowserTaskSpace.SourceDraft source=onMain(()->{gate.check();authority.requirePrepared(op,name,copy(originalArgs));space.requirePinRecipe(pin,normalized,recipe);return space.sourceDraft(normalized);},control);
                JSONObject receipt=space.retainSource(source,operationId,gate);try{gate.check();}catch(Exception stopped){if(nativeEntered.get()){uncertain=true;return unknown(operationId,receipt,name,args,actionPin,nativeEntered.get());}throw stopped;}if(receipt.optString("status").equals("unknown"))uncertain=true;return receipt.put("operationId",operationId);
            }
            CompletableFuture<CompletableFuture<JSONObject>> nativeCall=new CompletableFuture<>();
            ui.post(()->{if(nativeCall.isDone())return;try{gate.check();space.requirePinRecipe(pin,normalized,recipe);nativeCall.complete(space.dispatch(command,normalized,op,gate));}catch(Exception rejected){nativeCall.completeExceptionally(rejected);}});
            AtomicBoolean retiring=command.equals("finish")?nativeEntered:null;
            pending=await(nativeCall,control,name,8000,retiring,readPermit==null?null:gate,operationId);JSONObject result=await(pending,control,name,command.equals("upload_artifact")?30000:8000,retiring,readPermit==null?null:gate,operationId);
            if(!command.equals("finish"))try{gate.check();}catch(Exception stopped){uncertain=true;return unknown(operationId,result,name,args,actionPin,nativeEntered.get());}
            if(result.optString("status").equals("unknown"))uncertain=true;boolean observed=command.equals("observe")&&!(result.opt("accepted") instanceof Boolean&&!result.getBoolean("accepted"));if(observed&&recipe!=null)result=recipe.project(result);JSONObject receipt=observed?boundedObservation(result):copy(result);boolean ok=!receipt.optString("status").matches("unknown|failed|cancelled|cleanup_required|unavailable")&&receipt.optBoolean("accepted",true);
            receipt.put("ok",ok).put("operationId",operationId).put("verified",false);if(!receipt.has("accepted"))receipt.put("accepted",true);if(observed&&!receipt.optString("status").equals("unavailable"))receipt.put("status","observed");if(receipt.optString("status").equals("cleanup_required"))receipt.put("code","needs_user").put("requiresPause",true);
            if(observed){if(ok)return onMain(()->{gate.check();return space.presentedObservation(receipt);},control);return receipt;} // Publish only the final bounded ref set; old action/dirty guards stay unchanged.
            JSONObject after=null;try{after=onMain(()->space.receiptPageState(normalized),control);}catch(Exception unavailable){/* No second input or assumed document after a failed evidence read. */}
            return BrowserActionReceipt.attach(command,args,actionPin,after,nativeEntered.get(),receipt);
        }catch(Exception failure){if(name.equals("browser_task_retain_source")&&failure instanceof dev.pocketengine.features.memory.ToolOutputCapture.OutputSourceUnavailable)return new JSONObject().put("operationId",operationId).put("ok",false).put("accepted",false).put("verified",false).put("status","unavailable").put("code","native_output_source_unavailable").put("requiresPause",true).put("sourceRetained",false).put("automaticReplayAllowed",false).put("message","原任务引用的来源已撤回、变化或无法核对，本次尚未保存新的网页来源。请返回任务核对来源后继续。");if(name.equals(BrowserVisualCapture.TOOL)&&preparedVerified){VisualDelivery abandoned=visualDelivery.getAndSet(null);if(abandoned!=null)abandoned.image.close();return space.identity().put("operationId",operationId).put("ok",false).put("accepted",false).put("verified",false).put("status","unavailable").put("code","visual_capture_unavailable").put("nativeReadStarted",nativeEntered.get()).put("imageDelivered",false).put("imageRetained",false).put("automaticReplayAllowed",false).put("message","这次网页图像未能安全交付；请查看原页、关闭遮挡并按需要重新观察。没有发送替代图片或执行页面输入。");}if(!nativeEntered.get()&&failure instanceof BrowserTaskSpace.FrameReadUnavailable&&preparedVerified&&(name.equals("browser_task_observe")||name.equals("browser_task_retain_source")))return new JSONObject().put("ok",false).put("accepted",false).put("verified",false).put("operationId",operationId).put("status","unavailable").put("code","frame_ref_not_current").put("message","嵌入网页已变化或引用未展示，请先重新读取frame目录；没有读取新文档或保存来源。");if(nativeEntered.get()){uncertain=true;final JSONObject interrupted=args;final CompletableFuture<JSONObject> active=pending;ui.post(()->space.abortNative(interrupted,active));return unknown(operationId,null,name,args,actionPin,nativeEntered.get());}if(preparedVerified&&control==space.originalControl&&space.waitingForOwner())return BrowserActionReceipt.attach(name.substring("browser_task_".length()),args,actionPin,null,false,space.identity().put("ok",false).put("accepted",false).put("verified",false).put("status","owner_control").put("code","browser_decision_discarded").put("operationId",operationId).put("freshObservationRequired",true).put("message","主人已接管，旧浏览决定未派发；交还后将重新观察原页面"));if(preparedVerified)return BrowserActionReceipt.attach(name.substring("browser_task_".length()),args,actionPin,null,false,new JSONObject().put("ok",false).put("accepted",false).put("verified",false).put("code",failure instanceof BrowserTaskSpace.StalePage?"stale_browser_observation":failure instanceof SecurityException?"needs_user":"browser_not_dispatched").put("requiresPause",failure instanceof SecurityException&&!(failure instanceof BrowserTaskSpace.StalePage)).put("message",failure instanceof BrowserFormControls.OwnerRequired||failure instanceof IllegalStateException?clip(String.valueOf(failure.getMessage()),240):"任务或页面已变化，操作尚未派发，请刷新后继续"));throw failure;}
        finally{inFlight.set(false);}
    }
    private void live(String name,RunControl control)throws Exception {if(closed)throw new Unavailable();space.checkOriginal(control);if(!BrowserReadScope.readTool(name)||!approvals.nativeReadAuthority(space.binding,control,name))authority.check(name,control);resource.check();}
    private <T>T await(CompletableFuture<T> future,RunControl control,String name,long timeout,AtomicBoolean retiring)throws Exception {
        return await(future,control,name,timeout,retiring,null);
    }
    private <T>T await(CompletableFuture<T> future,RunControl control,String name,long timeout,AtomicBoolean retiring,BrowserTaskSpace.Gate readGate)throws Exception {
        return await(future,control,name,timeout,retiring,readGate,"");
    }
    private <T>T await(CompletableFuture<T> future,RunControl control,String name,long timeout,AtomicBoolean retiring,BrowserTaskSpace.Gate readGate,String operationId)throws Exception {
        long deadline=System.nanoTime()+timeout*1_000_000L;try{for(;;)try{return future.get(50,TimeUnit.MILLISECONDS);}catch(TimeoutException waiting){
            if(future.isDone())continue;
            // Finish retires its own pages/reservation before the native cleanup receipt is ready.
            // Once entered, wait for that receipt under the original run; no further page effect is admitted.
            if(retiring!=null&&retiring.get()){if(closed)throw new Unavailable();space.checkOriginalRun(control);authority.check(name,control);}else if(readGate!=null)readGate.check();else live(name,control);
            long modal=space.pageDialogWaitExtensionMillis(operationId);if(System.nanoTime()>=deadline+modal*1_000_000L)throw new TimeoutException("任务浏览回执超时");}}
        catch(ExecutionException failure){Throwable cause=failure.getCause();if(cause instanceof Exception)throw (Exception)cause;throw new IllegalStateException("任务浏览执行失败");}
        catch(Exception stopped){future.cancel(false);throw stopped;}
    }
    private interface Read<T> {T get()throws Exception;}
    private <T>T onMain(Read<T> read,RunControl control)throws Exception {if(ui.isMain())return read.get();CompletableFuture<T> value=new CompletableFuture<>();ui.post(()->{if(value.isDone())return;try{value.complete(read.get());}catch(Exception failure){value.completeExceptionally(failure);}});try{return value.get(3,TimeUnit.SECONDS);}catch(ExecutionException failure){Throwable cause=failure.getCause();if(cause instanceof Exception)throw (Exception)cause;throw new IllegalStateException(failure);}finally{value.cancel(false);}}
    private JSONObject unknown(String operationId,JSONObject actual,String name,JSONObject args,JSONObject pin,boolean nativeEntered)throws Exception {JSONObject result=space.identity().put("ok",false).put("accepted",JSONObject.NULL).put("verified",false).put("status","unknown").put("code","browser_action_result_unknown").put("operationId",operationId).put("message","任务浏览效果待核对，未重试或切到其他页面");result=BrowserActionReceipt.attach(name.substring("browser_task_".length()),args,pin,null,nativeEntered,result);if(actual!=null){JSONObject evidence=new JSONObject();for(String key:new String[]{"id","tabId","sourceId","sourceRef","status","accepted","sourceRetained","profileId","browserSpaceId","navigation"}){Object value=actual.opt(key);if(value instanceof String)evidence.put(key,clip((String)value,200));else if(value instanceof Number||value instanceof Boolean||value==JSONObject.NULL)evidence.put(key,value);}result.put("executorReceipt",evidence);if(evidence.has("sourceId"))result.put("sourceId",evidence.get("sourceId"));}return result;}
    private JSONObject boundedObservation(JSONObject raw)throws Exception {
        if(raw.optBoolean("semanticRead")){JSONObject out=new JSONObject(raw.toString());if(out.optString("status").equals("observed")){BrowserTaskSpace.uuid(out.getString("observationId"));BrowserSemanticRead.result(out);}out.put("url",clip(out.optString("url"),2048)).put("title",clip(out.optString("title"),240));if(out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>32768)throw new IllegalStateException("语义网页观察超过32KiB");return out;}
        BrowserTaskSpace.uuid(raw.optString("observationId"));if(!(raw.opt("text") instanceof String)||raw.optJSONArray("elements")==null||!(raw.opt("navigation") instanceof Number))throw new IllegalStateException("页面观察缺少正式内容与引用");
        JSONObject out=new JSONObject();for(String key:new String[]{"profileId","profileRevision","profileIsolation","browserSessionId","browserSpaceId","taskId","runId","epoch","tabId","tabGeneration","observationId","navigation","expiresInMs","pageIdentityDigest","pauseRevision","frameId","observedAt","actionFrame"})if(raw.has(key))out.put(key,raw.get(key));
        out.put("untrustedPageContent",true).put("url",clip(raw.optString("url"),2048)).put("title",clip(raw.optString("title"),240)).put("text",clip(raw.getString("text"),6000));JSONArray kept=new JSONArray(),all=raw.getJSONArray("elements");out.put("elements",kept);
        for(int i=0;i<all.length()&&i<250;i++){JSONObject element=all.optJSONObject(i);if(element==null||!element.optString("ref").matches("n[0-9]{1,3}"))continue;JSONObject row=new JSONObject().put("ref",element.getString("ref"));for(String key:new String[]{"nodeKey","tag","type","role","text","id","name","href","formAction","formMethod","submitterFormAction"})if(element.opt(key) instanceof String)row.put(key,clip(element.getString(key),key.matches("href|formAction|submitterFormAction")?512:160));if(element.has("ownerRequired"))row.put("ownerRequired",element.optBoolean("ownerRequired"));if(element.has("control"))row.put("control",BrowserFormControls.project(element.getJSONObject("control")));kept.put(row);JSONObject control=row.optJSONObject("control");if(control!=null&&control.optString("kind").equals("select")){JSONArray options=control.getJSONArray("options");while(options.length()>0&&out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>31000){options.remove(options.length()-1);control.put("optionsOmitted",control.getInt("optionsCount")-options.length());}}if(out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>31000){kept.remove(kept.length()-1);break;}}
        int omittedOptions=0;for(int i=0;i<kept.length();i++){JSONObject c=kept.getJSONObject(i).optJSONObject("control");if(c!=null&&c.optString("kind").equals("select"))omittedOptions+=c.getInt("optionsOmitted");}out.put("formOptionsOmitted",omittedOptions).put("elementsOmitted",all.length()-kept.length()).put("outputTruncated",raw.optBoolean("partial")||all.length()!=kept.length()||raw.getString("text").length()>6000||omittedOptions>0);if(out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>32768)throw new IllegalStateException("页面观察超过模型预算");return out;
    }
    private static String clip(String value,int limit){String out=value.substring(0,Math.min(value.length(),limit));return !out.isEmpty()&&Character.isHighSurrogate(out.charAt(out.length()-1))?out.substring(0,out.length()-1):out;}
    public JSONObject endForOwner(String reason)throws Exception {return onMain(()->space.endNative(reason),null);}
    public void requireInteractionCandidatesForOwner(JSONObject policy)throws Exception {onMain(()->{space.checkOriginal(space.originalControl);space.requireInteractionCandidatesCurrent(policy);return new JSONObject();},space.originalControl);}
    public JSONObject readScopeIdentityForOwner()throws Exception {return onMain(()->{space.checkOriginal(space.originalControl);return space.identity().put("pauseRevision",space.originalControl.pauseRevision());},space.originalControl);}
    public void cancelReadScopeNative(String scopeId){ScopedReadCancel current=scopedReadCancel.get();if(current!=null&&current.scope.equals(scopeId))current.budget.stopNativeScope();}
    public void requireOwnerReturnReady(){if(closed||inFlight.get()||uncertain||ownerReturnRead.get()!=null||returnReadInFlight.get())throw new SecurityException("原浏览调用或交还观察仍待回执或核对，暂不能交还");}
    public String expectOwnerReturnObservation(JSONObject confirmed,ModelCapabilityAdapters.ResourceCheck retainedAuthority)throws Exception {requireOwnerReturnReady();OwnerReturnRead read=new OwnerReturnRead(confirmed,retainedAuthority);if(!ownerReturnRead.compareAndSet(null,read))throw new SecurityException("原交还观察尚未结束");completedOwnerReturnObservation=null;return read.token;}
    public void abandonOwnerReturnObservation(String token){OwnerReturnRead read=ownerReturnRead.get();if(read!=null&&read.token.equals(token))ownerReturnRead.compareAndSet(read,null);}
    public JSONObject lookupSourceForOwner(String sourceId)throws Exception {return space.lookupSourceForOwner(sourceId);}
    private void nativeHookControl(RunControl control)throws Exception {if(closed)throw new Unavailable();if(space.ended())space.checkOriginalRun(control);else space.checkOriginal(control);}
    @Override public NativeToolObservation.Requirement observationRequirement(String tool,JSONObject args,RunControl control)throws Exception {nativeHookControl(control);if(tool.equals(BrowserVisualCapture.TOOL)&&schemas.containsKey(tool))return NativeToolObservation.Requirement.IMAGE;return existing==null?NativeToolObservation.Requirement.NONE:existing.observationRequirement(tool,args,control);}
    @Override public NativeToolObservation takeToolObservation(String tool,String operation,RunControl control)throws Exception {nativeHookControl(control);if(!tool.equals(BrowserVisualCapture.TOOL))return existing==null?null:existing.takeToolObservation(tool,operation,control);VisualDelivery selected=visualDelivery.get();if(selected==null)return null;if(!selected.operation.equals(operation))throw new SecurityException("截图不属于这个原operation");if(!visualDelivery.compareAndSet(selected,null))throw new SecurityException("原截图已消费或释放");return selected.image;}
    @Override public NativeToolPlan takeToolPlan(String tool,String operation,RunControl control)throws Exception {nativeHookControl(control);if(tool.equals(BrowserToolSequence.TOOL)){PlanDelivery selected=toolPlan.get();if(selected==null)return null;if(!selected.operation.equals(operation)||!toolPlan.compareAndSet(selected,null))throw new SecurityException("浏览组合不属于原operation或已消费");return selected.plan;}if(schemas.containsKey(tool))return null;return existing==null?null:existing.takeToolPlan(tool,operation,control);}
    @Override public NativeReadContextRepair nativeReadContextRepair(JSONArray finalMessages,RunControl control)throws Exception {
        nativeHookControl(control);java.util.List<BrowserReadDelta.FullResync> repairs;
        try{repairs=onMain(()->space.readContextRepairs(finalMessages),control);if(repairs.isEmpty())BrowserReadDelta.validateFinalWire(finalMessages);}
        catch(BrowserReadDelta.ResyncRequired unavailable){throw new NativeReadContextRepair.Unavailable();}
        if(repairs.isEmpty())return existing==null?null:existing.nativeReadContextRepair(finalMessages,control);
        long repairBytes=0;for(BrowserReadDelta.FullResync value:repairs)repairBytes+=value.fullSafeContent().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;if(repairBytes>NativeReadContextRepair.MAX_BYTES)throw new NativeReadContextRepair.Unavailable();
        java.util.List<NativeReadContextRepair.Replacement> replacements=new java.util.ArrayList<>();for(BrowserReadDelta.FullResync value:repairs)replacements.add(new NativeReadContextRepair.Replacement(value.toolCallId(),value.expectedContentSha256(),value.fullSafeContent()));
        RunControl.AuthorityCheck current=()->{nativeHookControl(control);resource.check();for(BrowserReadDelta.FullResync value:repairs){approvals.checkReadContextRepair(space.binding,control,value.original());space.requireReadRepairRecipe(value.original());onMain(()->{space.requireReadContextRepair(value.original());return new JSONObject();},control);}resource.check();control.check();};
        return NativeReadContextRepair.capture(control,new NativeReadContextRepair.Identity(space.binding.taskId,space.binding.runId,space.binding.epoch),replacements,current,(actual,original)->{if(original!=control)throw new SecurityException("browser_context_repair_control_changed");BrowserReadDelta.validateFinalWire(actual);});
    }
    @Override public void beginRun(String goal,RunControl control)throws Exception {space.checkOriginal(control);if(existing!=null)existing.beginRun(goal,control);}
    @Override public void prepareTurnSurface(RunControl control)throws Exception {if(closed)throw new Unavailable();if(space.ended())space.checkOriginalRun(control);else space.checkOriginal(control);if(existing!=null)existing.prepareTurnSurface(control);}
    @Override public void admitCapabilities(dev.pocketengine.core.CapabilitySnapshot snapshot,RunControl control)throws Exception {
        if(closed)throw new Unavailable();if(space.ended())space.checkOriginalRun(control);else space.checkOriginal(control);
        if(existing!=null)existing.admitCapabilities(snapshot,control);
    }
    @Override public JSONObject prepareTurn(String query,int round,int steps,RunControl control)throws Exception {
        if(closed)throw new Unavailable();if(space.ended())space.checkOriginalRun(control);else space.checkOriginal(control);JSONObject prepared=existing==null?new JSONObject().put("tools",new JSONArray()).put("reference",new JSONObject()):copy(existing.prepareTurn(query,round,steps,control));
        JSONArray selected=prepared.getJSONArray("tools"),own=ownTools();Set<String> names=new java.util.HashSet<>();for(int i=0;i<selected.length();i++)names.add(selected.getJSONObject(i).getJSONObject("function").getString("name"));for(int i=0;i<own.length();i++){JSONObject definition=own.getJSONObject(i);if(!names.add(definition.getJSONObject("function").getString("name")))throw new SecurityException("本轮工具名称与任务浏览冲突");selected.put(definition);}
        JSONObject reference=prepared.optJSONObject("reference");if(reference==null)reference=new JSONObject();JSONObject browserContext=taskBrowserSnapshot(control);if(!space.ended())browserContext.put("readRecipes",space.readRecipesForHost());reference.put("taskBrowser",browserContext);return prepared.put("tools",selected).put("reference",reference);
    }
    @Override public boolean responseCurrent(RunControl control)throws Exception {try{if(closed)return false;if(space.ended())space.checkOriginalRun(control);else space.checkOriginal(control);return existing==null||existing.responseCurrent(control);}catch(Exception stale){return false;}}
    @Override public boolean stepReadSourceCurrent(String name,JSONObject receipt,RunControl original)throws Exception {
        if(!schemas.containsKey(name))return existing!=null&&existing.stepReadSourceCurrent(name,receipt,original);
        if(!Set.of("browser_task_observe","browser_task_inspect","browser_task_wait").contains(name)||original!=space.originalControl||receipt==null||closed||uncertain||inFlight.get()||!entered.contains(receipt.optString("operationId")))return false;
        if(name.equals("browser_task_inspect")&&!receipt.optBoolean("advancedRead")||name.equals("browser_task_observe")&&receipt.optBoolean("advancedRead"))return false;
        original.check();try{live(name,original);boolean current=onMain(()->space.stepReadSourceCurrent(receipt),original);live(name,original);return current;}
        catch(InterruptedException cancelled){throw cancelled;}catch(Exception unavailable){original.check();return false;}
    }
    @Override public JSONObject beforeTool(String name,JSONObject args,RunControl control)throws Exception {return existing==null?null:existing.beforeTool(name,args,control);}
    @Override public void afterTool(String name,JSONObject args,JSONObject receipt,RunControl control)throws Exception {
        JSONObject delivered=receipt==null?null:copy(receipt),arguments=args==null?null:copy(args);
        if(existing!=null)existing.afterTool(name,args,receipt,control);
        if(control!=space.originalControl||delivered==null||arguments==null||!entered.contains(delivered.optString("operationId")))return;
        if(name.equals("browser_task_network")&&delivered.has("networkObservationId"))try{live(name,control);onMain(()->{space.deliveredNetwork(arguments,delivered);return new JSONObject();},control);}catch(Exception notDelivered){/* Unknown/omitted response objects cannot become body approval targets. */}
        if(name.equals("browser_task_evaluate")||name.equals("browser_task_inspect"))try{live(name,control);onMain(()->{space.deliveredAdvancedSource(arguments,delivered);return new JSONObject();},control);}catch(Exception notDelivered){/* Missing original delivery proof never enables source retention. */}
        if(name.equals("browser_task_observe"))try{live(name,control);onMain(()->{space.finalSequenceObservation(arguments,delivered);space.deliveredSourceObservation(arguments,delivered);return new JSONObject();},control);}
        catch(Exception notRegistered){/* Observation already has its receipt. Missing delivery proof cannot enable source retention. */}
        try{onMain(()->{space.effectSettledNative(arguments,delivered);space.pageDialogOperationSettled(delivered.getString("operationId"),delivered);return new JSONObject();},control);}
        catch(Exception unavailable){/* Never replace an already journalled action result or clear its unknown-effect fence. */}
    }
    /** Owner-return context has no tool operation. Match its original native envelope on the
     * final wire, then wait for a completed response. JSON flags/nonce alone prove no delivery. */
    private JSONObject ownerReturnOnWire(JSONArray messages,JSONObject expected)throws Exception {
        if(expected==null||!expected.optBoolean("freshObservationAvailable")||!expected.optBoolean("ok")||!expected.optString("kind").equals("browser_owner_return_observation"))return null;
        String fingerprint=dev.pocketengine.store.TaskStore.capabilityFingerprint(dev.pocketengine.features.memory.TaskMemoryHistory.observationData(expected));
        for(int i=messages.length()-1;i>=0;i--){JSONObject message=messages.optJSONObject(i);if(message==null||!"user".equals(message.optString("role")))continue;JSONArray parts=message.optJSONArray("content");
            for(int j=0;parts!=null&&j<parts.length();j++){JSONObject part=parts.optJSONObject(j);if(part==null||!"text".equals(part.optString("type")))continue;String text=part.optString("text"),prefix=dev.pocketengine.features.memory.TaskMemoryHistory.OBSERVATION_PREFIX;if(!text.startsWith(prefix))continue;
                try{org.json.JSONTokener parser=new org.json.JSONTokener(text.substring(prefix.length()));Object value=parser.nextValue();if(!(value instanceof JSONObject)||parser.nextClean()!=0)continue;JSONObject candidate=copy((JSONObject)value);candidate.remove("actualImageAttached");candidate.remove("visionNotice");if(fingerprint.equals(dev.pocketengine.store.TaskStore.capabilityFingerprint(candidate)))return candidate;}catch(Exception omittedOrChanged){/* No exact native envelope survived safe projection. */}
            }
        }return null;
    }
    @Override public void beforeModelRequest(String requestId,JSONArray messages,RunControl control)throws Exception {
        nativeHookControl(control);ownerReturnDelivery.set(null);if(existing!=null)existing.beforeModelRequest(requestId,messages,control);onMain(()->{space.beforeModelRequest(requestId,messages);return new JSONObject();},control);
        OwnerReturnRead read=completedOwnerReturnRead;JSONObject actual=read==null||read.sourceDelivered?null:ownerReturnOnWire(messages,completedOwnerReturnObservation);if(read!=null&&actual!=null&&read.token.equals(actual.optString("ownerReturnNonce")))ownerReturnDelivery.set(new OwnerReturnDelivery(requestId,read,actual));
    }
    @Override public JSONObject afterModelResponse(String requestId,String answerId,RunControl control)throws Exception {
        nativeHookControl(control);JSONObject result=existing==null?null:existing.afterModelResponse(requestId,answerId,control);onMain(()->{space.afterModelResponse(requestId);return new JSONObject();},control);
        OwnerReturnDelivery delivered=ownerReturnDelivery.get();if(delivered!=null&&delivered.requestId.equals(requestId)&&ownerReturnDelivery.compareAndSet(delivered,null))try{
            if(delivered.read!=completedOwnerReturnRead)throw new BrowserTaskSpace.StalePage("交还观察已被替换");delivered.read.retainedAuthority.check();live("browser_task_observe",control);
            onMain(()->{JSONObject args=new JSONObject().put("tabId",delivered.observation.getString("tabId"));space.requirePin(delivered.read.observedPin,args);space.finalSequenceObservation(args,delivered.observation);return new JSONObject();},control);delivered.read.sourceDelivered=true;
        }catch(Exception notRegistered){control.check();/* A missing original delivery proof never enables source retention. */}
        return result;
    }
    @Override public void modelRequestAbandoned(String requestId)throws Exception {
        OwnerReturnDelivery pending=ownerReturnDelivery.get();if(pending!=null&&pending.requestId.equals(requestId))ownerReturnDelivery.compareAndSet(pending,null);
        if(existing!=null)existing.modelRequestAbandoned(requestId);onMain(()->{space.modelRequestAbandoned(requestId);return new JSONObject();},space.originalControl);
    }
    @Override public void endRun(RunControl control)throws Exception {ownerReturnDelivery.set(null);PlanDelivery plan=toolPlan.getAndSet(null);if(plan!=null)plan.plan.close();VisualDelivery pending=visualDelivery.getAndSet(null);if(pending!=null)pending.image.close();if(existing!=null)existing.endRun(control);}
    @Override public void close(){closed=true;ownerReturnDelivery.set(null);PlanDelivery plan=toolPlan.getAndSet(null);if(plan!=null)plan.plan.close();VisualDelivery pending=visualDelivery.getAndSet(null);if(pending!=null)pending.image.close();ui.post(()->{try{space.endNative("capability_closed");}catch(Exception ignored){}});if(existing!=null)existing.close();}
    private static JSONObject copy(JSONObject value)throws Exception{return new JSONObject(value.toString());}
    private static JSONObject text(int length)throws Exception{return new JSONObject().put("type","string").put("maxLength",length);}
    private static JSONObject props(Object... values)throws Exception {JSONObject p=new JSONObject();for(int i=0;i<values.length;i+=2)p.put((String)values[i],values[i+1]);return p;}
}
