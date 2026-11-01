package dev.pocketengine.features;

import android.content.Context;
import dev.pocketengine.core.EventSink;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import org.json.JSONObject;

public final class FeatureContext {
    public final Context android;
    public final SettingsStore settings;
    public final SecretVault secrets;
    private final EventSink events;
    public FeatureContext(Context context, SettingsStore settings, SecretVault secrets, EventSink events) {
        this.android=context.getApplicationContext();this.settings=settings;this.secrets=secrets;this.events=events;
    }
    public ModelConfig model() throws Exception { return settings.get(); }
    public void emit(String module, JSONObject payload) {
        JSONObject event=new JSONObject();try{event.put("type","feature_event").put("feature",module).put("payload",payload);}catch(Exception ignored){}
        events.emit(event);
    }
}
