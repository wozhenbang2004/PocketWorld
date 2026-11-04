package dev.pocketengine.phone.display;

import org.json.JSONObject;

/** Exact owner-view selection. These pixels never create an actionable observation. */
public final class PhoneLivePreview {
    private PhoneLivePreview() { }
    /** The exact admitted run is current, but its first private display is still being prepared. */
    public static final class NotReady extends IllegalStateException {
        public NotReady(){super("独立屏正在准备，画面就绪后会自动显示");}
    }
    public record Request(String taskId,String runId,int epoch,String sessionId,int displayId,long afterFrameGeneration) { }

    public static Request request(JSONObject input)throws Exception {
        if(input==null)throw new SecurityException("请选择原独立屏执行");
        String task=text(input,"taskId"),run=text(input,"expectedRunId");
        int epoch=(int)integer(input,"expectedEpoch",1,Integer.MAX_VALUE);
        boolean session=input.has("expectedSessionId"),display=input.has("expectedDisplayId");
        if(session!=display)throw new SecurityException("原独立屏会话和显示身份必须同时提供");
        String sessionId=session?text(input,"expectedSessionId"):null;
        int displayId=display?(int)integer(input,"expectedDisplayId",1,Integer.MAX_VALUE):-1;
        long after=input.has("afterFrameGeneration")?integer(input,"afterFrameGeneration",-1,9_007_199_254_740_991L):-1;
        return new Request(task,run,epoch,sessionId,displayId,after);
    }

    static String text(JSONObject value,String key) {
        Object raw=value.opt(key);
        if(!(raw instanceof String text)||text.isBlank()||text.length()>200)throw new SecurityException("缺少准确原身份："+key);
        return text;
    }
    static long integer(JSONObject value,String key,long minimum,long maximum) {
        Object raw=value.opt(key);
        if(!(raw instanceof Number))throw new SecurityException("原画面编号必须为整数："+key);
        try {
            long exact=new java.math.BigDecimal(raw.toString()).longValueExact();
            if(exact<minimum||exact>maximum)throw new ArithmeticException();
            return exact;
        }catch(NumberFormatException|ArithmeticException invalid){throw new SecurityException("原画面编号无效："+key);}
    }

    /** A bounded presentation receipt, with no helper fields that could become action refs. */
    static JSONObject present(JSONObject frame,dev.pocketengine.core.RunControl.HostRunIdentity owner,
            String session,int display,long after)throws Exception {
        String state=frame.optString("state");
        if(!java.util.Set.of("ready","pending","unchanged").contains(state))throw new SecurityException("原画面状态无效");
        long generation=integer(frame,"frameGeneration",0,9_007_199_254_740_991L);
        long captured=integer(frame,"capturedAt",0,Long.MAX_VALUE);
        int width=(int)integer(frame,"sourceWidth",0,10_000),height=(int)integer(frame,"sourceHeight",0,10_000);
        int previewWidth=(int)integer(frame,"previewWidth",0,960),previewHeight=(int)integer(frame,"previewHeight",0,960);
        if(generation<after||"unchanged".equals(state)&&generation!=after
                ||(long)width*height>2_400_000||!"image/jpeg".equals(frame.optString("mime")))
            throw new SecurityException("原画面代次或尺寸不匹配");
        JSONObject result=new JSONObject().put("taskId",owner.taskId()).put("runId",owner.runId()).put("epoch",owner.epoch())
                .put("sessionId",session).put("displayId",display).put("state",state).put("frameGeneration",generation)
                .put("capturedAt",captured).put("sourceWidth",width).put("sourceHeight",height)
                .put("previewWidth",previewWidth).put("previewHeight",previewHeight).put("mime","image/jpeg");
        if(!"pending".equals(state)&&(width==0||height==0||previewWidth==0||previewHeight==0))throw new SecurityException("原画面缺少尺寸");
        if("ready".equals(state)) {
            Object raw=frame.opt("imageBase64");
            if(!(raw instanceof String bytes)||bytes.isEmpty()||bytes.length()>2_000_000)throw new SecurityException("原画面编码无效或过大");
            result.put("imageBase64",bytes);
        }
        return result;
    }
}
