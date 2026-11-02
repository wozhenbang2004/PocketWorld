package dev.pocketengine.features.operations;

import android.app.LocaleManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;
import java.util.Locale;
import java.util.Set;

/** App presentation only. User messages, model language and task identity are never rewritten. */
public final class AppLanguage {
    public static final Set<String> SUPPORTED=Set.of("system","zh-Hans","en");
    private static volatile String loadedLegacy;
    private AppLanguage() {}
    private static String checked(String tag){if(!SUPPORTED.contains(tag))throw new IllegalArgumentException("Unsupported application language");return tag;}
    public static String selected(Context context){
        if(Build.VERSION.SDK_INT>=33){LocaleManager manager=context.getSystemService(LocaleManager.class);LocaleList locales=manager==null?LocaleList.getEmptyLocaleList():manager.getApplicationLocales();return locales.isEmpty()?"system":choice(locales.get(0));}
        String selected=loadedLegacy;if(selected==null)synchronized(AppLanguage.class){if(loadedLegacy==null){String saved=context.getSharedPreferences("private_settings",Context.MODE_PRIVATE).getString("applicationLanguage","system");loadedLegacy=SUPPORTED.contains(saved)?saved:"system";}selected=loadedLegacy;}return selected;
    }
    private static String choice(Locale locale){return "en".equals(locale.getLanguage())?"en":"zh".equals(locale.getLanguage())?"zh-Hans":"system";}
    public static String effectiveTag(Context context){String selected=selected(context);if(!selected.equals("system"))return selected;LocaleList system=Resources.getSystem().getConfiguration().getLocales();return !system.isEmpty()&&"en".equals(system.get(0).getLanguage())?"en":"zh-Hans";}
    public static Locale speechLocale(Context context){return Locale.forLanguageTag(effectiveTag(context).equals("en")?"en-US":"zh-CN");}
    public static Context wrap(Context base){
        String selected=selected(base);if(selected.equals("system"))return base;
        Configuration configuration=new Configuration(base.getResources().getConfiguration());configuration.setLocales(LocaleList.forLanguageTags(selected));return base.createConfigurationContext(configuration);
    }
    /** Call after the original draft save has completed. Android 13+ owns its app-language persistence. */
    public static void select(Context context,String tag)throws Exception{
        checked(tag);
        if(Build.VERSION.SDK_INT>=33){LocaleManager manager=context.getSystemService(LocaleManager.class);if(manager==null)throw new IllegalStateException("Application language service unavailable");manager.setApplicationLocales(tag.equals("system")?LocaleList.getEmptyLocaleList():LocaleList.forLanguageTags(tag));}
        else{selected(context);if(!context.getSharedPreferences("private_settings",Context.MODE_PRIVATE).edit().putString("applicationLanguage",tag).commit())throw new IllegalStateException("Language preference save unconfirmed");loadedLegacy=tag;}
        applyProcessLocale(context);
    }
    public static void applyProcessLocale(Context context){String selected=selected(context);LocaleList system=Resources.getSystem().getConfiguration().getLocales();Locale locale=selected.equals("system")?(system.isEmpty()?Locale.SIMPLIFIED_CHINESE:system.get(0)):Locale.forLanguageTag(selected);Locale.setDefault(locale);}
}
