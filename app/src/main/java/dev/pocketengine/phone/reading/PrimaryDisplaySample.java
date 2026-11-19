package dev.pocketengine.phone.reading;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.SystemClock;
import android.view.Display;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONObject;

/** Exactly one local Display.Mode snapshot. No screenshot, package observation, settings Intent, or permission. */
public final class PrimaryDisplaySample {
    private final PrimaryDisplayQuestion question;
    private final String state;
    private final long at;
    private final float rate;
    private final int width,height;
    private PrimaryDisplaySample(PrimaryDisplayQuestion q,String state,long at,float rate,int w,int h) {
        this.question=q;this.state=state;this.at=at;this.rate=rate;this.width=w;this.height=h;
    }
    public static PrimaryDisplaySample read(Context context,PrimaryDisplayQuestion q) {
        java.util.Objects.requireNonNull(q);
        long began=SystemClock.elapsedRealtime();String reason="not_available";
        try{
            DisplayManager manager=context.getSystemService(DisplayManager.class);
            Display display=manager==null?null:manager.getDisplay(Display.DEFAULT_DISPLAY);
            if(display!=null&&display.getDisplayId()==Display.DEFAULT_DISPLAY&&display.isValid()){
                if(display.getState()!=Display.STATE_ON)reason="not_on";
                else {
                    Display.Mode mode=display.getMode();
                    if(mode!=null){
                        float r=q==PrimaryDisplayQuestion.SIZE?0:mode.getRefreshRate();
                        int w=q==PrimaryDisplayQuestion.RATE?0:mode.getPhysicalWidth(),h=q==PrimaryDisplayQuestion.RATE?0:mode.getPhysicalHeight();
                        long at=System.currentTimeMillis();
                        Display.Mode after=display.getMode();
                        if(after!=null&&mode.equals(after)&&display.isValid()&&display.getState()==Display.STATE_ON
                                &&SystemClock.elapsedRealtime()-began<=2000L&&(q==PrimaryDisplayQuestion.SIZE||Float.isFinite(r)&&r>0)
                                &&(q==PrimaryDisplayQuestion.RATE||w>0&&h>0))
                            return new PrimaryDisplaySample(q,"observed",at,r,w,h);
                        reason="unstable";
                    }
                }
            }
        }catch(RuntimeException unavailable){reason="not_available";}
        return new PrimaryDisplaySample(q,reason,System.currentTimeMillis(),0,0,0);
    }
    public boolean observed(){return "observed".equals(state);}
    public JSONObject evidence()throws Exception {
        JSONObject result=new JSONObject().put("format","pocketworld.android-primary-mode.v1").put("displayId",Display.DEFAULT_DISPLAY)
                .put("requested",question.name()).put("readState",state).put("checkedAt",at);
        if(observed()&&question!=PrimaryDisplayQuestion.SIZE)result.put("modeHz",Double.parseDouble(Float.toString(rate)));
        if(observed()&&question!=PrimaryDisplayQuestion.RATE)result.put("modeWidth",width).put("modeHeight",height);
        return result;
    }
    public String answer(boolean english){
        if(!observed())return english?(state.equals("not_on")?"The default display was not on.":state.equals("unstable")?"The display mode was changing during the read.":"Android did not provide a readable default display mode.")+" I couldn't verify a reading. No settings were changed."
            :(state.equals("not_on")?"默认主屏当时未处于亮屏状态":state.equals("unstable")?"采样期间主屏模式不稳定":"Android 没有提供可读取的默认主屏模式")+"，没有可核实的读数，也没有改设置。";
        String time=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.ROOT).format(new Date(at));
        String hz=question==PrimaryDisplayQuestion.SIZE?"":new BigDecimal(Float.toString(rate)).stripTrailingZeros().toPlainString()+" Hz";
        String size=question==PrimaryDisplayQuestion.RATE?"":width+" × "+height+" px";
        String values=question==PrimaryDisplayQuestion.BOTH?hz+" · "+size:hz+size;
        return english?"At "+time+", Android reported "+values+" for this app's default display mode."+(question==PrimaryDisplayQuestion.SIZE?"":" This is a mode refresh rate, not measured game FPS.")
                       :time+"，Android 向此应用报告的默认主屏模式为 "+values+"。"+(question==PrimaryDisplayQuestion.SIZE?"":"这是模式刷新率，不是游戏实测帧率。");
    }
}
