package dev.pocketengine.features.devices;

import dev.pocketengine.core.NativeToolObservation;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskStore;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Human input on one original zero-model source run. Pixels and action objects are volatile,
 * never revived from task JSON; original journal / signed route / receiver lease remain final. */
public final class OwnerPhoneControlSession implements AutoCloseable {
    @FunctionalInterface public interface Stop { void enqueue(String task,RunControl cancelled)throws Exception; }
    private final TaskStore.OwnerPhoneControlRun run;private final DeviceCapabilityPort port;private final Stop stop;
    private final AtomicBoolean busy=new AtomicBoolean(),closed=new AtomicBoolean(),cleanupQueued=new AtomicBoolean();private final String session=UUID.randomUUID().toString();
    private volatile String stopReason="原任务已停止",cleanupNotice="";
    private volatile Frame frame;private volatile long serial;private volatile String lastOperation="",lastTool="",notice="先刷新画面，或打开本次范围内的应用。";private volatile JSONObject lastReceipt;
    private volatile java.util.concurrent.ScheduledFuture<?> expiry;
    private static final java.util.concurrent.ScheduledExecutorService EXPIRY=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"owner-phone-control-expiry");t.setDaemon(true);return t;});
    private static final class Frame implements AutoCloseable {
        final NativeToolObservation.Image image;final JSONObject metadata;final JSONArray nodes;final String handle=UUID.randomUUID().toString();
        Frame(NativeToolObservation.Image image,JSONObject metadata,JSONArray nodes){this.image=image;this.metadata=metadata;this.nodes=nodes;}
        public void close(){image.close();}
    }
    /** Only this class can construct an action. JSON provides intent, never this authority. */
    public static final class FrameAction {
        private final OwnerPhoneControlSession issuer;private final Frame frame;private final long serial;private final JSONObject args;private final AtomicBoolean used=new AtomicBoolean();
        private FrameAction(OwnerPhoneControlSession issuer,Frame frame,long serial,JSONObject args){this.issuer=issuer;this.frame=frame;this.serial=serial;this.args=args;}
        public JSONObject consume(TaskStore.OwnerPhoneControlRun actual)throws Exception {
            if(actual!=issuer.run||!issuer.busy.get()||issuer.closed.get()||serial!=issuer.serial||!used.compareAndSet(false,true))throw new SecurityException("不是原手控窗口的一次当前输入");
            actual.requireCurrent();if(frame!=null&&frame!=issuer.frame)throw new SecurityException("原画面已更换");
            issuer.serial=Math.addExact(issuer.serial,1);return new JSONObject(args.toString());
        }
    }
    public OwnerPhoneControlSession(TaskStore.OwnerPhoneControlRun run,DeviceCapabilityPort port,Stop stop)throws Exception {
        this.run=java.util.Objects.requireNonNull(run);this.port=java.util.Objects.requireNonNull(port);this.stop=java.util.Objects.requireNonNull(stop);run.requireCurrent();
        long deadline=Math.min(run.view().getJSONObject("executionScope").getLong("deadlineAt"),run.route().getLong("deadline"));
        expiry=EXPIRY.schedule(()->stop("本次手控期限已到"),Math.max(0,deadline-System.currentTimeMillis()),java.util.concurrent.TimeUnit.MILLISECONDS);
        run.control().onCancel(()->{closed.set(true);notice=stopReason+"；已禁止新输入，远端释放需原回执核对。";queueCleanup(stopReason);});
    }
    public String taskId(){return run.taskId();}public RunControl control(){return run.control();}
    private JSONObject base()throws Exception {JSONObject selected=run.route();return new JSONObject().put("routeId",selected.getString("optionId")).put("targetDeviceId",selected.getString("targetDeviceId"));}
    private void requireOpen()throws Exception {if(closed.get())throw new SecurityException("原手控页已停止，不恢复旧输入权限");run.requireCurrent();}
    private void retirePixels(){Frame old=frame;frame=null;if(old!=null)old.close();}
    private JSONObject typedIntent(String tool,JSONObject intent,Frame selected)throws Exception {
        if(!java.util.Set.of("phone_observe","phone_launch_app","phone_tap","phone_swipe","phone_back","phone_home","phone_type","phone_click_node").contains(tool))throw new SecurityException("这不是手控输入");
        if(!run.route().getJSONObject("toolDeclarations").has(tool))throw new SecurityException("本次原手机范围没有此输入工具");
        JSONObject args=new JSONObject(intent.toString());
        if(tool.equals("phone_observe")){if(args.length()!=0)throw new SecurityException("刷新画面不接受额外范围");args.put("includeImage",true);}
        if(tool.equals("phone_launch_app")){
            JSONArray packages=run.route().getJSONObject("resourceBinding").getJSONArray("packageNames");if(!PairingCoordinator.contains(packages,args.optString("packageName")))throw new SecurityException("应用不在原审阅名单");
        }else if(!tool.equals("phone_observe")){
            if(selected==null)throw new SecurityException("请先刷新并查看原手机画面");
            if(tool.equals("phone_type")||tool.equals("phone_click_node")){
                String choice=args.getString("nodeChoice");args.remove("nodeChoice");JSONObject node=null;
                for(int i=0;i<selected.nodes.length();i++)if(choice.equals(selected.nodes.getJSONObject(i).getString("choiceId")))node=selected.nodes.getJSONObject(i);
                if(node==null||tool.equals("phone_type")&&!node.optBoolean("editable"))throw new SecurityException("输入目标不是当前画面的可编辑控件");args.put("nodeId",node.getString("nodeId"));
            }
        }
        DeviceSourceToolContracts.validate(run.route().getString("capabilityVersion"),tool,args);
        JSONObject input=base().put("toolName",tool).put("arguments",args).put("toolBudget",run.route().getInt("toolBudget"));
        if(selected!=null&&!tool.equals("phone_observe")&&!tool.equals("phone_launch_app"))input.put("observationRef",new JSONObject(selected.metadata.getJSONObject("observationRef").toString()));return input;
    }
    /** One user gesture. Duplicate serials do not create another original operation. */
    public JSONObject input(String expectedSession,long expectedSerial,String expectedFrame,String tool,JSONObject intent)throws Exception {
        if(!session.equals(expectedSession)||!busy.compareAndSet(false,true))throw new SecurityException("原手控页面已变化或仍在核对上一次操作");String operation="";
        try{
            requireOpen();if(expectedSerial!=serial)throw new SecurityException("这次输入已处理或页面过旧；请读取原回执，不重复提交");
            if(run.unresolved())throw new SecurityException("原操作尚未确认，只能核对原编号或停止");
            Frame selected=frame;boolean needsFrame=!tool.equals("phone_observe")&&!tool.equals("phone_launch_app");
            if(needsFrame&&(selected==null||!selected.handle.equals(expectedFrame)))throw new SecurityException("输入不是当前已显示画面");
            JSONObject args=typedIntent(tool,intent,needsFrame?selected:null);port.validate("device_invoke_tool",args);port.observationRequirement("device_invoke_tool",args,run.control());
            // Validate original image evidence before entering SQL. The single input worker and
            // the private action retain identity; the SQL consume uses no Visual monitor.
            if(needsFrame)selected.image.base64(run.control());
            FrameAction action=new FrameAction(this,needsFrame?selected:null,serial,args);operation=run.begin(action);lastOperation=operation;lastTool=tool;
            // Native action is already single-use. Losing/refusing the following receipt never replays it.
            retirePixels();port.beforeTool("device_invoke_tool",args,run.control());JSONObject result=port.execute("device_invoke_tool",new JSONObject(args.toString()).put("_hostOperationId",operation),run.control());
            lastReceipt=run.record(operation,result);notice="已保存原操作回执；不等于业务结果已核实。";
            if(tool.equals("phone_observe"))try{takeFrame("device_invoke_tool",operation,result);}catch(Exception unavailableImage){notice="原观察回执已保存，但真实画面已失效；请刷新。";}
        }catch(Exception unavailable){
            if(!operation.isEmpty()){
                JSONObject unknown=new JSONObject().put("ok",false).put("accepted",JSONObject.NULL).put("code","device_effect_unknown").put("operationId",operation).put("retrySubmitted",false).put("message","原输入已登记，派发或回执尚未确认；只能核对原编号，不重投。");
                try{lastReceipt=run.record(operation,unknown);}catch(Exception preserveOriginal){/* Original prepared / receipt row remains authoritative. */}
            }
            notice=unavailable.getMessage()==null?"原手控条件已变化":unavailable.getMessage();
        }finally{busy.set(false);}
        return view();
    }
    /** Read only the last original operation; never journal another invocation or refill. */
    public JSONObject reconcile(String expectedSession)throws Exception {
        if(!session.equals(expectedSession)||!busy.compareAndSet(false,true))throw new SecurityException("原手控仍忙或页面已变化");
        try{requireOpen();if(lastOperation.isEmpty())throw new SecurityException("尚无原操作需要核对");String operation=lastOperation;
            JSONObject result=port.execute("device_operation_get",base().put("operationId",operation),run.control());lastReceipt=run.record(operation,result);
            if(lastTool.equals("phone_observe")&&frame==null)try{takeFrame("device_operation_get",operation,result);}catch(Exception unavailableImage){retirePixels();}notice="已核对同一个原操作，没有重发输入。";
        }catch(Exception unavailable){notice=unavailable.getMessage();}finally{busy.set(false);}return view();
    }
    private void takeFrame(String function,String operation,JSONObject receipt)throws Exception {
        if(closed.get())return;NativeToolObservation observation=port.takeToolObservation(function,operation,run.control());
        if(observation==null){notice="观察回执已保存，但没有可显示的已验证画面；请核对全屏捕获授权、设备前台与连接。";return;}
        NativeToolObservation.Image image=null;boolean installed=false;try{
            image=observation.consume(function,operation,run.control());JSONObject metadata=image.metadata(),selected=run.route();
            if(!run.taskId().equals(metadata.optString("taskId"))||!run.runId().equals(metadata.optString("runId"))||metadata.optInt("epoch")!=1||!operation.equals(metadata.optString("sourceOperationId"))
                    ||!selected.getString("targetDeviceId").equals(metadata.optString("targetDeviceId"))||!DeviceProtocol.resourceEquals(selected.getJSONObject("resourceBinding"),metadata.getJSONObject("resourceBinding")))throw new SecurityException("真实画面不属于原手机手控范围");
            JSONObject device=receipt.optJSONObject("deviceReceipt"),payload=device==null?null:device.optJSONObject("payload"),actual=payload==null?null:payload.optJSONObject("receipt");JSONArray nodes=new JSONArray();
            if(actual!=null&&DeviceProtocol.resourceEquals(metadata.getJSONObject("observationRef"),actual.getJSONObject("observationRef"))){JSONArray source=actual.optJSONArray("nodes");for(int i=0;source!=null&&i<source.length()&&nodes.length()<200;i++){
                JSONObject node=source.getJSONObject(i);String id=node.optString("id");if(id.isEmpty()||node.optBoolean("password"))continue;
                nodes.put(new JSONObject().put("choiceId",UUID.randomUUID().toString()).put("nodeId",id).put("label",node.optString("text",node.optString("contentDescription",node.optString("className","控件")))).put("editable",node.optBoolean("editable")));
            }}
            image.base64(run.control());requireOpen();Frame next=new Frame(image,metadata,nodes);retirePixels();frame=next;image=null;installed=true;
            if(closed.get()){retirePixels();return;}notice="这是刚才取得的画面，不是实时视频。输入一次后需重新刷新。";
        }finally{if(image!=null)image.close();if(!installed)observation.close();}
    }
    public JSONObject view()throws Exception {return view(true);}
    public JSONObject status()throws Exception {return view(false);}
    private JSONObject view(boolean includePixels)throws Exception {
        JSONObject task=run.view(),selected=run.route(),scope=task.getJSONObject("executionScope");boolean active=!closed.get();String reason=closed.get()?stopReason+"；已禁止新输入，远端释放需原回执核对。"+cleanupNotice:notice;
        if(active)try{requireOpen();}catch(Exception retired){active=false;reason=retired.getMessage();retirePixels();}
        JSONObject result=new JSONObject().put("taskId",run.taskId()).put("runId",run.runId()).put("sessionId",session).put("serial",serial).put("active",active).put("busy",busy.get())
                .put("targetDeviceId",selected.getString("targetDeviceId")).put("resourceBinding",selected.getJSONObject("resourceBinding")).put("deadlineAt",Math.min(scope.getLong("deadlineAt"),selected.getLong("deadline")))
                .put("toolsUsed",scope.getInt("toolsUsed")).put("toolLimit",scope.getInt("toolLimit")).put("modelCalls",0).put("sourceCallsRemaining",task.getInt("manualSourceCallsRemaining")).put("unresolved",run.unresolved()).put("lastOperationId",lastOperation).put("notice",reason==null?"":reason)
                .put("availableTools",new JSONArray(DeviceProtocol.objectKeys(selected.getJSONObject("toolDeclarations")))).put("liveVideo",false).put("pixelsPersistedByThisPage",false);
        if(lastReceipt!=null)result.put("receipt",new JSONObject(lastReceipt.toString()));Frame current=frame;
        if(active&&current!=null)try{String data="data:"+current.image.mimeType()+";base64,"+current.image.base64(run.control());if(frame==current&&!closed.get()){
            JSONArray nodes=new JSONArray();for(int i=0;i<current.nodes.length();i++){JSONObject node=new JSONObject(current.nodes.getJSONObject(i).toString());node.remove("nodeId");nodes.put(node);}
            JSONObject shown=new JSONObject().put("frameHandle",current.handle).put("capturedAt",current.metadata.getLong("capturedAt")).put("width",current.metadata.getInt("imageWidthPx")).put("height",current.metadata.getInt("imageHeightPx")).put("nodes",nodes);if(includePixels)shown.put("dataUrl",data);result.put("frame",shown);
        }}catch(Exception stale){retirePixels();result.put("notice","原画面已失效，请刷新；没有授权旧画面输入。");}return result;
    }
    /** Cancellation may be delivered while TaskStore is locked. Retire authority immediately,
     * but never take the Visual monitor or run the persistence/remote cleanup on that stack. */
    public void stop(String reason){if(closed.compareAndSet(false,true))stopReason=reason;notice=stopReason+"；已禁止新输入，远端释放需原回执核对。";run.control().cancel();queueCleanup(stopReason);}
    private void queueCleanup(String reason){if(!cleanupQueued.compareAndSet(false,true))return;
        try{EXPIRY.execute(()->{
            java.util.concurrent.ScheduledFuture<?> timer=expiry;if(timer!=null)timer.cancel(false);retirePixels();
            try{run.stop(reason);}catch(Exception unresolved){cleanupNotice="原停止登记尚未确认。";}
            try{stop.enqueue(run.taskId(),run.control());}catch(Exception unconfirmed){cleanupNotice+="远端停止投递尚未确认；请核对原任务。";}
            port.close();
        });}catch(java.util.concurrent.RejectedExecutionException unconfirmed){cleanupNotice="停止清理尚未登记，请核对原任务。";}
    }
    @Override public void close(){stop("已离开原手控页面");}
}
