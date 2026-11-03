package dev.pocketengine.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.view.View;
import dev.pocketengine.R;

/** One local presentation preference for native windows and Compose. Never changes a task or grant. */
public final class UiAppearance {
    public static final String KEY="appearance";
    private UiAppearance(){}
    public static SharedPreferences preferences(Context context){return context.getApplicationContext().getSharedPreferences("ui_presentation",Context.MODE_PRIVATE);}
    public static String mode(SharedPreferences preferences){
        try {String value=preferences.getString(KEY,"system");return "light".equals(value)||"dark".equals(value)?value:"system";}
        catch(ClassCastException invalid){return "system";}
    }
    public static boolean dark(Context context){String mode=mode(preferences(context));return "dark".equals(mode)||"system".equals(mode)&&(context.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;}
    public static int theme(Context context){return dark(context)?R.style.AppTheme_Dark:R.style.AppTheme_Light;}
    public static int surface(Context context){return dark(context)?0xff1b1b1b:0xffffffff;}
    public static int surfaceVariant(Context context){return dark(context)?0xff2b2b2b:0xfff2f2f2;}
    public static int foreground(Context context){return dark(context)?0xffe8e8e8:0xff181818;}
    public static int secondary(Context context){return dark(context)?0xffbdbdbd:0xff666666;}
    public static int primary(Context context){return dark(context)?0xffededed:0xff202020;}
    public static int onPrimary(Context context){return dark(context)?0xff181818:0xffffffff;}
    public static int outline(Context context){return dark(context)?0xff4b4b4b:0xffe4e4e4;}
    public static int warning(Context context){return dark(context)?0xffffb4ab:0xffa3443d;}
    /** Only native app chrome. Web documents, screenshots and media pixels retain their own colors. */
    public static void window(Activity activity){
        activity.setTheme(theme(activity));int surface=surface(activity);activity.getWindow().setStatusBarColor(surface);activity.getWindow().setNavigationBarColor(surface);
        View decor=activity.getWindow().getDecorView();int mask=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        decor.setSystemUiVisibility((decor.getSystemUiVisibility()&~mask)|(dark(activity)?0:mask));
    }
}
