package dev.pocketengine.phone.diagnostics;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Owner-started safe target. It contains no account, network, permission or third-party business action. */
public final class PhonePracticeActivity extends Activity {
    public static final String MARKER="PW_DIAG_TARGET_V1",ASCII="PocketWorld ASCII 42",CHINESE="口袋世界中文演练42";
    private final Handler main=new Handler(Looper.getMainLooper());private TextView tick;private int ticks,clicks;
    private final Runnable advance=new Runnable(){public void run(){if(tick!=null){tick.setText("PW_DIAG_TICK:"+(++ticks));main.postDelayed(this,350);}}};
    @Override public void onCreate(Bundle state){super.onCreate(state);LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(24,24,24,24);page.setBackgroundColor(Color.WHITE);
        TextView title=text("手机能力演练 · "+MARKER);title.setTextSize(20);page.addView(title);page.addView(text("只修改此测试页。随时返回或停止原任务。"));
        TextView count=text("PW_DIAG_CLICK_COUNT:0");page.addView(count);Button click=new Button(this);click.setText("测试点击");click.setContentDescription("PW_DIAG_CLICK");click.setOnClickListener(v->count.setText("PW_DIAG_CLICK_COUNT:"+(++clicks)));page.addView(click);
        EditText input=new EditText(this);input.setSingleLine(true);input.setContentDescription("PW_DIAG_INPUT");input.setHint("中英文输入测试");page.addView(input,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        tick=text("PW_DIAG_TICK:0");page.addView(tick);ScrollView scroll=new ScrollView(this);scroll.setContentDescription("PW_DIAG_LIST");LinearLayout rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);for(int i=0;i<40;i++){TextView row=text(String.format(java.util.Locale.ROOT,"PW_DIAG_ROW_%02d · 可滚动测试行",i));row.setPadding(0,20,0,20);rows.addView(row);}scroll.addView(rows);page.addView(scroll,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1));setContentView(page);main.postDelayed(advance,350);}
    private TextView text(String value){TextView text=new TextView(this);text.setText(value);text.setTextColor(0xff202020);text.setTextSize(15);text.setPadding(0,8,0,8);return text;}
    @Override protected void onDestroy(){main.removeCallbacks(advance);tick=null;super.onDestroy();}
}
