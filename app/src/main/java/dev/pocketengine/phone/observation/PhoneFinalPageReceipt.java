package dev.pocketengine.phone.observation;

import dev.pocketengine.core.RunControl;
import org.json.JSONObject;
import java.util.Set;

/** Receipt of the existing closing read, or an opening-only fact. No page text, coordinates, App name, image or new observation. */
public final class PhoneFinalPageReceipt {
    private static final String FORMAT="pocketworld.phone-final-page.v1";
    private static final String OPENING_ONLY="opening_structure_only";
    private static final Set<String> STATES=Set.of("not_allowed","not_entered","other_surface","read_failed","unconfirmed","changed","empty","observed",OPENING_ONLY);
    private static final Set<String> METHODS=Set.of("structure","local_text","local_geometry","frame");
    private PhoneFinalPageReceipt(){}
    private static long number(JSONObject row,String key){Object v=row.opt(key);return v instanceof Integer||v instanceof Long?((Number)v).longValue():-1;}
    private static String text(JSONObject row,String key){Object v=row.opt(key);return v instanceof String?(String)v:"";}
    /** Ephemeral witness for the first owner-confirmed structure already admitted and appended by this Runner. */
    public static final class FirstMainRead {
        private final RunControl owner;
        private final RunControl.HostRunIdentity run;
        private FirstMainRead(RunControl owner,RunControl.HostRunIdentity run){this.owner=owner;this.run=run;}
    }
    public static FirstMainRead bindOwnerFirstMainRead(RunControl control,JSONObject actual){
        var run=control.hostRunIdentity();
        if(run==null||run.epoch()!=1||!control.phoneSurfaceActive()||actual==null||!"main".equals(actual.opt("executionMode"))||
                !Boolean.TRUE.equals(actual.opt("ok"))||!Boolean.FALSE.equals(actual.opt("changedDuringRead"))||
                actual.has("imageBase64")||number(actual,"displayId")!=0)return null;
        JSONObject b=actual.optJSONObject("observationBinding"),sources=actual.optJSONObject("observationSources");
        if(b==null||sources==null||!Boolean.TRUE.equals(sources.opt("treeAdequate"))||!Boolean.FALSE.equals(sources.opt("imageIncluded"))||
                !run.taskId().equals(b.opt("taskId"))||!run.runId().equals(b.opt("runId"))||run.epoch()!=number(b,"epoch")||
                number(b,"displayId")!=0||control.pauseRevision()!=number(b,"pauseRevision")||
                !text(actual,"observationId").matches("[a-f0-9-]{36}")||!text(actual,"observationId").equals(b.opt("observationId"))||
                text(actual,"sessionId").isBlank()||!text(actual,"sessionId").equals(b.opt("sessionId")))return null;
        return new FirstMainRead(control,run);
    }
    public static JSONObject from(RunControl control,RunControl.HostRunIdentity run,String mode,String skip,JSONObject actual)throws Exception{
        return from(control,run,mode,skip,actual,null);
    }
    public static JSONObject from(RunControl control,RunControl.HostRunIdentity run,String mode,String skip,JSONObject actual,FirstMainRead opening)throws Exception{
        if(run==null||!control.matchesHostRun(run.taskId(),run.runId(),run.epoch())||!("main_screen".equals(mode)||"background".equals(mode)))return null;
        String state="unconfirmed",method="";
        if(skip!=null){
            if(!Set.of("not_allowed","not_entered","other_surface").contains(skip))return null;
            // An uninitialized deferred port rules out a terminal read, not the earlier native structure.
            state="not_entered".equals(skip)&&"main_screen".equals(mode)&&opening!=null&&opening.owner==control&&run.equals(opening.run)&&
                    actual!=null&&Boolean.TRUE.equals(actual.opt("noDeviceObservation"))?OPENING_ONLY:skip;
        }
        else if(actual!=null&&Boolean.FALSE.equals(actual.opt("ok")))state="read_failed";
        else if(actual!=null&&Boolean.TRUE.equals(actual.opt("ok"))){
            JSONObject b=actual.optJSONObject("observationBinding");String observation=text(actual,"observationId"),session=text(actual,"sessionId");
            long display=number(actual,"displayId");
            if(b!=null&&!observation.isBlank()&&!session.isBlank()&&run.taskId().equals(b.opt("taskId"))&&run.runId().equals(b.opt("runId"))&&
                    run.epoch()==number(b,"epoch")&&control.pauseRevision()==number(b,"pauseRevision")&&display==number(b,"displayId")&&
                    ("main_screen".equals(mode)?display==0:display>0)&&observation.equals(b.opt("observationId"))&&session.equals(b.opt("sessionId"))){
                if(Boolean.TRUE.equals(actual.opt("changedDuringRead")))state="changed";
                else if(display>0&&"display_has_no_app".equals(actual.optString("screenState"))&&Boolean.TRUE.equals(actual.opt("emptyDisplayVerified")))state="empty";
                else{
                    // Partial/malformed source metadata must not lose an otherwise valid model answer.
                    JSONObject basis=null;try{basis=OwnerReadBasis.from(actual);}catch(Exception unavailable){/* unconfirmed */}
                    if(basis!=null&&METHODS.contains(basis.optString("method"))){method=basis.getString("method");state="observed";}
                }
            }
        }
        JSONObject out=new JSONObject().put("format",FORMAT).put("taskId",run.taskId()).put("runId",run.runId()).put("epoch",run.epoch()).put("state",state)
                .put("businessResultVerified",false).put("containsScreenContent",false);
        return method.isEmpty()?out:out.put("method",method);
    }
    /** The sealed original model turn is independently checked before exporting only these small facts. */
    public static JSONObject retained(JSONObject evidence,String task,String run,int epoch)throws Exception{
        JSONObject turn=evidence==null?null:evidence.optJSONObject("nativeFinalAnswer");
        if(turn==null||!"pocketworld.runner-final-answer.v1".equals(turn.optString("format"))||!task.equals(turn.optString("taskId"))||
                !run.equals(turn.optString("runId"))||epoch!=turn.optInt("coordinatorEpoch",-1)||!Boolean.TRUE.equals(turn.opt("inputBoundaryEmpty"))||
                turn.optInt("messageIndex",-1)<0||turn.optLong("historyLength",-1)<=turn.optInt("messageIndex",-1))return null;
        return project(turn.optJSONObject("phoneFinalPage"),task,run,epoch);
    }
    /** Re-project even the immutable archived value; optional unreadable metadata must never hide the original text. */
    public static JSONObject project(JSONObject source,String task,String run,int epoch)throws Exception{
        if(source==null||!FORMAT.equals(source.optString("format"))||!task.equals(source.optString("taskId"))||!run.equals(source.optString("runId"))||
                epoch!=source.optInt("epoch",-1)||!Boolean.FALSE.equals(source.opt("businessResultVerified"))||!Boolean.FALSE.equals(source.opt("containsScreenContent")))return null;
        String state=source.optString("state");
        if(!STATES.contains(state)||OPENING_ONLY.equals(state)&&(epoch!=1||source.has("method")))return null;
        JSONObject out=new JSONObject().put("format",FORMAT).put("taskId",task).put("runId",run).put("epoch",epoch).put("state",state)
                .put("businessResultVerified",false).put("containsScreenContent",false);
        if("observed".equals(state)){if(!METHODS.contains(source.optString("method")))return null;out.put("method",source.getString("method"));}
        return out;
    }
    public static String ownerNotice(JSONObject receipt){
        if(receipt==null)return null;
        return switch(receipt.optString("state")){
            case "not_allowed"->"收尾阶段没有读屏；这段答复不是新的设备回执。";
            case "not_entered"->"本轮没有启用手机观察；外部效果仍以原工具回执为准。";
            case OPENING_ONLY->"已读取你在开始时确认的主屏结构，收尾未再次读取。";
            case "other_surface"->"收尾时手机工作面已切出，没有重新读取最后一屏。";
            case "read_failed","unconfirmed"->"没有取得可核对的最后一屏，请在原任务确认结果。";
            case "changed"->"最后一次读取时页面仍在变化，不能据此确认目标已达成。";
            case "empty"->"最后一次读取核到原副屏没有前台应用；这不是目标完成的证明。";
            case "observed"->"已取得原任务当时的最后一屏依据，请核对目标是否达成。";
            default->null;
        };
    }
}
