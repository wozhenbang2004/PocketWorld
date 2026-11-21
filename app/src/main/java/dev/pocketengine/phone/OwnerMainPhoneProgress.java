package dev.pocketengine.phone;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.ToolOutcome;
import org.json.JSONObject;

/** One display-only fact from this hot native main-screen run. No receipt, text or target is retained. */
public final class OwnerMainPhoneProgress {
    private enum Action {
        OPEN("打开应用"), CLICK("点击"), INPUT("输入"), SWIPE("滑动"), SCROLL("滚动"), BACK("返回"), HOME("回到桌面");
        final String label;
        Action(String label){this.label=label;}
    }
    private enum Fact { SCREEN_READ, ACCEPTED, NOT_ACCEPTED, CHECK }
    private final RunControl owner;
    private final long pause,surface;
    private final String session;
    private final Fact fact;
    private final Action action;

    private OwnerMainPhoneProgress(RunControl owner,long pause,long surface,String session,Fact fact,Action action){
        this.owner=owner;this.pause=pause;this.surface=surface;this.session=session;this.fact=fact;this.action=action;
    }
    boolean belongsTo(RunControl original){return owner==original;}
    boolean current(RunControl original,String accessibilitySession){
        return owner==original&&session.equals(accessibilitySession)&&original.phoneSurfaceActive()&&!original.isCancelled()&&!original.isPaused()&&
                pause==original.pauseRevision()&&surface==original.surfaceRevision();
    }
    boolean isAction(){return fact!=Fact.SCREEN_READ;}
    String label(){return switch(fact){
        case SCREEN_READ->"已读到主屏";
        case ACCEPTED->"手机回执：系统已接纳"+action.label+"请求";
        case NOT_ACCEPTED->"手机回执："+action.label+"请求未获接纳";
        case CHECK->"手机回执："+action.label+"结果待核对";
    };}

    /** The caller already owns the ordinary private Main service, and has already accepted the read. */
    public static OwnerMainPhoneProgress screen(RunControl owner,String session,JSONObject receipt){
        if(receipt==null||!Boolean.TRUE.equals(receipt.opt("ok"))||!Boolean.FALSE.equals(receipt.opt("changedDuringRead"))||
                !integerEquals(receipt.opt("displayId"),0)||!session.equals(receipt.opt("sessionId")))return null;
        var source=receipt.optJSONObject("observationSources");var binding=receipt.optJSONObject("observationBinding");
        long pause=owner.pauseRevision(),surface=owner.surfaceRevision();
        if(source==null||!Boolean.TRUE.equals(source.opt("treeAvailable"))||!sameScope(owner,session,binding)||
                !integerEquals(binding.opt("pauseRevision"),pause)||!integerEquals(receipt.opt("pauseRevision"),pause)||
                !(receipt.opt("observationId") instanceof String observation)||observation.isEmpty()||!observation.equals(binding.opt("observationId")))return null;
        return new OwnerMainPhoneProgress(owner,pause,surface,session,Fact.SCREEN_READ,null);
    }

    /** Only native runner events. A model tool name or a successful plan is not a settled operation. */
    public static OwnerMainPhoneProgress event(RunControl owner,String session,JSONObject event,long invocationPause,long invocationSurface){
        var run=owner.hostRunIdentity();if(run==null||!run.taskId().equals(event.opt("taskId")))return null;
        var receipt=event.optJSONObject("receipt");
        if("observation".equals(event.opt("type"))&&"phone_observe".equals(event.opt("tool"))&&"completed".equals(event.opt("status")))
            return screen(owner,session,receipt);
        String operation=event.optString("nativeOperationId");
        if(!"tool".equals(event.opt("type"))||!operation.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")||receipt==null||
                !Boolean.FALSE.equals(receipt.opt("verifiedBusinessSuccess"))||!Boolean.FALSE.equals(receipt.opt("automaticReplayAllowed")))return null;
        if("phone_scroll_node".equals(event.opt("tool")))return scroll(owner,session,receipt,operation);
        if(!sameScope(owner,session,receipt.optJSONObject("observationScope")))return null;
        Action action=switch(event.optString("tool")){
            case "phone_launch_app"->Action.OPEN;
            case "phone_click_node","phone_click_text","phone_tap"->Action.CLICK;
            case "phone_type"->Action.INPUT;
            case "phone_swipe"->Action.SWIPE;
            case "phone_back"->Action.BACK;
            case "phone_home"->Action.HOME;
            default->null;
        };
        if(action==null)return null;
        long pause=owner.pauseRevision(),surface=owner.surfaceRevision();
        var binding=receipt.optJSONObject("observationBinding");
        // Only the actual native launch is intentionally independent of an existing observation.
        if(binding==null?(action!=Action.OPEN||receipt.has("observationBinding")||pause!=invocationPause||surface!=invocationSurface):
                !sameScope(owner,session,binding)||!integerEquals(binding.opt("pauseRevision"),pause))return null;
        Object accepted=receipt.opt("accepted");
        Fact fact=ToolOutcome.classify(receipt).requiresReconciliation()?Fact.CHECK:
                Boolean.TRUE.equals(accepted)&&!Boolean.FALSE.equals(receipt.opt("nativeDispatched"))&&
                !Boolean.FALSE.equals(receipt.opt("actionDispatched"))&&!"not_dispatched".equals(receipt.opt("effectStatus"))?Fact.ACCEPTED:
                Boolean.FALSE.equals(accepted)?Fact.NOT_ACCEPTED:Fact.CHECK;
        return new OwnerMainPhoneProgress(owner,pause,surface,session,fact,action);
    }

    /** The native node-scroll path carries its own operation ID and the original post-read,
     * not the direct action's top-level scope. Use it only when both existing proofs match. */
    private static OwnerMainPhoneProgress scroll(RunControl owner,String session,JSONObject receipt,String operation){
        if(!operation.equals(receipt.opt("operationId"))||!Boolean.TRUE.equals(receipt.opt("accepted"))||
                !Boolean.TRUE.equals(receipt.opt("freshObservationAvailable"))||
                !receipt.optString("mechanism").matches("accessibility_action_scroll_(forward|backward)")||
                ToolOutcome.classify(receipt).requiresReconciliation())return null;
        var after=receipt.optJSONObject("observation");
        if(after==null||!(receipt.opt("afterObservationId") instanceof String seen)||!seen.equals(after.opt("observationId")))return null;
        var paired=screen(owner,session,after);
        return paired==null?null:new OwnerMainPhoneProgress(owner,paired.pause,paired.surface,session,Fact.ACCEPTED,Action.SCROLL);
    }

    private static boolean sameScope(RunControl owner,String session,JSONObject value){
        var run=owner.hostRunIdentity();return run!=null&&value!=null&&run.taskId().equals(value.opt("taskId"))&&
                run.runId().equals(value.opt("runId"))&&integerEquals(value.opt("epoch"),run.epoch())&&
                integerEquals(value.opt("displayId"),0)&&session.equals(value.opt("sessionId"));
    }
    private static boolean integerEquals(Object value,long expected){
        return (value instanceof Integer||value instanceof Long)&&((Number)value).longValue()==expected;
    }
}
