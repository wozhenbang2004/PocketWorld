package dev.pocketengine.features.operations;

import android.content.Context;

/** Explicit application-owned labels only. Call sites retain dynamic text untouched. */
public final class LiteralUiText {
    private static volatile Context application;
    private static volatile Selection selection;
    private static final class Selection {
        final String language; final Context context;
        Selection(String language,Context context){this.language=language;this.context=context;}
    }
    private LiteralUiText() {}
    public static synchronized void install(Context context) {
        application=context.getApplicationContext();selection=null;
    }
    public static String text(int resource,String originalLiteral) {
        Context app=application;
        if(app==null)return originalLiteral;
        String actual=AppLanguage.effectiveTag(app);
        Selection selected=selection;
        if(selected==null||!actual.equals(selected.language))synchronized(LiteralUiText.class){
            if(selection==null||!actual.equals(selection.language)){
                android.content.res.Configuration config=new android.content.res.Configuration(app.getResources().getConfiguration());
                config.setLocales(android.os.LocaleList.forLanguageTags(actual));
                selection=new Selection(actual,app.createConfigurationContext(config));
            }
            selected=selection;
        }
        return selected.context.getString(resource);
    }
}
