package dev.pocketengine.phone.diagnostics;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.core.RunControl;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Private owner-started target; no account, network, permission or third-party business action. */
public final class PhonePracticeActivity extends Activity {
    public static final String MARKER="PW_DIAG_TARGET_V1",ASCII="PocketWorld ASCII 42",CHINESE="口袋世界中文演练42";
    private static volatile PhonePracticeActivity current;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final CountDownLatch destroyed=new CountDownLatch(1);
    private TextView tick;private int ticks,clicks;private RunControl original;
    private String refusalReason="";
    private final Runnable advance=new Runnable(){public void run(){if(tick!=null){tick.setText("PW_DIAG_TICK:"+(++ticks));main.postDelayed(this,350);}}};
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        String stage="practice_identity";
        try{
            EngineApplication app=(EngineApplication)getApplication();String id=getIntent().getStringExtra("taskId"),run=getIntent().getStringExtra("runId");
            int epoch=getIntent().getIntExtra("epoch",-1),display=getIntent().getIntExtra("displayId",-1);String nonce=getIntent().getStringExtra("practiceSessionId");
            original=app.control(id);
            if(original==null||!original.matchesHostRun(id,run,epoch)||original.isCancelled()||nonce==null||!nonce.matches("[a-f0-9-]{36}")||getDisplay()==null||getDisplay().getDisplayId()!=display)throw new SecurityException("practice_identity_changed");
            stage="practice_task_authority";app.tasks.requireOwnerPhonePractice(id,run,epoch,original);
            stage="practice_native_claim";
            PhonePracticeTarget.claim(getIntent(),original,getDisplay().getDisplayId(),()->{PhonePracticeActivity previous=current;if(previous!=null&&previous!=this&&!previous.isDestroyed())throw new SecurityException("practice_target_busy");current=this;});
            stage="practice_ui";
            LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(24),dp(24),dp(24),dp(20));page.setBackgroundColor(Color.WHITE);
            TextView title=text("手机能力演练");title.setTextSize(22);title.setTypeface(null,android.graphics.Typeface.BOLD);page.addView(title);
            page.addView(text("只修改此测试页。可随时停止原任务；测试结果以实际回读为准。"));
            TextView marker=text(MARKER+"\nPW_DIAG_SESSION:"+nonce);marker.setTextSize(11);marker.setTextColor(0xff737373);page.addView(marker);
            TextView count=text("PW_DIAG_CLICK_COUNT:0");page.addView(count);
            Button click=new Button(this);click.setText("测试点击");click.setAllCaps(false);click.setTextColor(Color.WHITE);click.setBackground(shape(0xff252525,0xff252525));click.setContentDescription("PW_DIAG_CLICK");click.setOnClickListener(v->count.setText("PW_DIAG_CLICK_COUNT:"+(++clicks)));page.addView(click,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(48)));
            EditText input=new EditText(this);input.setSingleLine(true);input.setTextColor(0xff202020);input.setTextSize(16);input.setPadding(dp(12),dp(10),dp(12),dp(10));input.setBackground(shape(0xfffafafa,0xffbdbdbd));input.setContentDescription("PW_DIAG_INPUT");input.setHint("中英文输入测试");
            LinearLayout.LayoutParams inputLayout=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(52));inputLayout.topMargin=dp(16);page.addView(input,inputLayout);
            tick=text("PW_DIAG_TICK:0");tick.setTextColor(0xff737373);page.addView(tick);
            ScrollView scroll=new ScrollView(this);scroll.setContentDescription("PW_DIAG_LIST");LinearLayout rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);
            for(int i=0;i<40;i++){TextView row=text(String.format(java.util.Locale.ROOT,"PW_DIAG_ROW_%02d · 可滚动测试行",i));row.setPadding(0,dp(16),0,dp(16));rows.addView(row);}scroll.addView(rows);page.addView(scroll,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1));
            setContentView(page);main.postDelayed(advance,350);
        }catch(Exception expired){refusalReason=stage+":"+expired.getClass().getSimpleName();finishAndRemoveTask();}
    }
    /** Native diagnostic code only; never contains intent values, source text or exception messages. */
    String refusalReason(){return refusalReason;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private GradientDrawable shape(int fill,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(8));d.setStroke(dp(1),stroke);return d;}
    private TextView text(String value){TextView text=new TextView(this);text.setText(value);text.setTextColor(0xff202020);text.setTextSize(15);text.setPadding(0,dp(8),0,dp(8));return text;}
    public static void requestCloseOriginal(RunControl original){PhonePracticeTarget.revokePending(original);PhonePracticeActivity target=current;if(target!=null&&target.original==original)target.main.post(()->{if(current==target&&target.original==original&&!target.isDestroyed())target.finishAndRemoveTask();});}
    /** Does not navigate or kill any other application. A timeout remains unknown cleanup. */
    public static JSONObject closeOriginal(RunControl original)throws Exception {
        PhonePracticeTarget.revokePending(original);
        PhonePracticeActivity target=current;if(target==null||target.original!=original)return new JSONObject().put("requested",false).put("confirmed",true).put("reason","no_original_practice_activity");
        requestCloseOriginal(original);boolean done=Looper.myLooper()!=Looper.getMainLooper()&&target.destroyed.await(2,TimeUnit.SECONDS);
        return new JSONObject().put("requested",true).put("confirmed",done).put("reason",done?"original_activity_destroyed":"practice_cleanup_pending").put("otherAppsTouched",false);
    }
    @Override protected void onDestroy(){main.removeCallbacks(advance);tick=null;if(current==this)current=null;super.onDestroy();destroyed.countDown();}
}
