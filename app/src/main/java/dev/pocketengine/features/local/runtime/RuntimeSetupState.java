package dev.pocketengine.features.local.runtime;

import org.json.JSONObject;

/** Owner display of original observations. Reading this projection never installs or retries. */
public final class RuntimeSetupState {
    private RuntimeSetupState(){}

    public static JSONObject view(JSONObject runtime,JSONObject availability,JSONObject original,
            boolean retainedControl,JSONObject logs,String observationError)throws Exception {
        JSONObject preparation=runtime.optJSONObject("preparation");
        if(preparation==null)preparation=new JSONObject();
        JSONObject operation=null;boolean live=false,unsettled=false,cancel=false;
        String status=runtime.optBoolean("canExecute")?"ready":runtime.optBoolean("preparationAvailable")?"not_prepared":"unavailable";
        if(original!=null){
            JSONObject task=original.getJSONObject("task"),row=original.getJSONObject("operation"),receipt=row.optJSONObject("receipt");
            if(receipt==null)receipt=new JSONObject();
            boolean preflight=Boolean.FALSE.equals(receipt.opt("accepted"))&&receipt.optBoolean("notDispatched");
            boolean settled="receipt".equals(row.optString("state"))&&(preflight||receipt.optBoolean("receiptReceived")&&receipt.optBoolean("resourcesReleased"));
            live=retainedControl&&receipt.optBoolean("ownedWorkerLive");
            cancel=task.optBoolean("cancelRequested")||receipt.optBoolean("cancelRequested");
            unsettled=!settled;
            if(settled)status=preflight&&receipt.optString("code").equals("runtime_setup_interrupted_before_dispatch")?"interrupted":cancel?"cancelled":"completed".equals(receipt.optString("status"))?status:"failed";
            else if(live)status=cancel?"cancel_requested":"running";
            else status="interrupted";
            String phase=receipt.optString("preparationPhase",settled?status:"queued");
            long completed=Math.max(0,receipt.optLong("completedBytes",0)),total=Math.max(0,receipt.optLong("totalBytes",0));
            boolean bytes=phase.equals("downloading")||phase.startsWith("extracting_")||phase.equals("verifying_download");
            boolean items=phase.equals("probing_tools")||phase.equals("verifying_installed_tools");
            JSONObject failure=error(receipt,observationError);
            operation=new JSONObject().put("taskId",task.getString("id")).put("runId",task.getString("runId"))
                    .put("epoch",task.getInt("coordinatorEpoch")).put("operationId",row.getString("operationId"))
                    .put("status",status).put("phase",phase).put("completedBytes",completed).put("totalBytes",total)
                    .put("progressUnit",bytes?"bytes":items?"items":JSONObject.NULL)
                    .put("fraction",(bytes||items)&&total>0?Math.min(1d,(double)completed/total):JSONObject.NULL)
                    .put("error",failure==null?JSONObject.NULL:failure).put("receipt",receipt);
        }
        boolean canPrepare=availability.optBoolean("canPrepare")&&!unsettled;
        if(original==null&&availability.optBoolean("preparationInProgress"))status="running";
        else if(original==null&&!canPrepare&&runtime.optBoolean("preparationAvailable")&&!runtime.optBoolean("canExecute"))status="blocked";
        String message=switch(status){
            case "ready" -> "本机基础工具已准备好；项目依赖、运行范围与执行批准仍需单独核对。";
            case "running" -> "正在准备本机工具；可以离开此页面，返回后继续查看同一次安装。";
            case "cancel_requested" -> "已请求停止，正在等待原安装释放资源；现有项目和已完成的下载保留。";
            case "cancelled" -> "本次准备已停止；再次准备会使用已校验的下载，已有工具环境保留。";
            case "failed" -> "本次准备未完成。请查看具体错误和原日志，再明确重试。";
            case "interrupted" -> unsettled?"原安装已中断或执行结果待核对。保留原记录，不会自动重新派发。":"上次准备在派发前中断，可以重新审阅并准备。";
            case "blocked" -> availability.optString("message","当前仍有运行或未核对的执行，请先查看并结束原任务，再准备工具。");
            case "unavailable" -> runtime.optString("message","当前安装包不能准备这台设备的本机工具。");
            default -> "需要运行项目或本机技能时再准备工具；普通对话可以先使用。";
        };
        if(!unsettled&&!canPrepare&&availability.has("executionEnabled")&&!availability.optBoolean("executionEnabled")&&!status.equals("blocked"))message+=" "+availability.optString("message","本机执行准入当前未开启。");
        return new JSONObject().put("format","pocketworld.runtime-setup-view.v1").put("status",status)
                .put("ready",runtime.optBoolean("canExecute")).put("canPrepare",canPrepare)
                .put("canRetry",canPrepare&&java.util.Set.of("failed","cancelled","interrupted").contains(status))
                .put("canCancel",live&&!cancel).put("message",message).put("runtime",new JSONObject(runtime.toString()))
                .put("preparation",new JSONObject(preparation.toString())).put("availability",new JSONObject(availability.toString()))
                .put("operation",operation==null?JSONObject.NULL:operation).put("logs",logs==null?JSONObject.NULL:logs)
                .put("modelRequired",false).put("projectRequired",false).put("automaticReplay",false);
    }

    private static JSONObject error(JSONObject receipt,String observationError)throws Exception {
        String code=receipt.optString("error",receipt.optString("code",""));
        if(code.isEmpty())code=observationError==null?"":observationError;
        if(code.isEmpty())return null;
        JSONObject details=new JSONObject();
        for(String key:new String[]{"launchErrno","terminationSignal","exitCode","timedOut","nativeResultContract","effectStatus","cancelDelivery","observation","failedPreparationPhase"})if(receipt.has(key))details.put(key,receipt.get(key));
        // A later preparation may have the same error code for another cause.
        // Recovery guidance belongs to this original receipt, not the global
        // preparation.json observation; old receipts without it stay unknown.
        if(receipt.optJSONObject("preparationFailure")!=null)details.put("preparationFailure",new JSONObject(receipt.getJSONObject("preparationFailure").toString()));
        return new JSONObject().put("code",code).put("message",receipt.optString("message",code)).put("details",details);
    }
}
