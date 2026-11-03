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
    public interface TeamProfileModels {
        dev.pocketengine.features.teams.TeamParentPlan plan(dev.pocketengine.features.teams.TeamParentPlan actual,dev.pocketengine.core.RunControl original)throws Exception;
        ModelConfig start(dev.pocketengine.store.TaskStore.TeamParentStart actual,dev.pocketengine.core.RunControl original)throws Exception;
    }
    private volatile TeamProfileModels teamProfileModels;
    public synchronized void installTeamProfileModels(TeamProfileModels original){if(teamProfileModels!=null)throw new IllegalStateException("原团队Profile来源已挂载");teamProfileModels=java.util.Objects.requireNonNull(original);}
    public TeamProfileModels teamProfileModels(){return teamProfileModels;}
    public ModelConfig model() throws Exception { return settings.get(); }
    public void emit(String module, JSONObject payload) {
        JSONObject event=new JSONObject();try{event.put("type","feature_event").put("feature",module).put("payload",payload);}catch(Exception ignored){}
        events.emit(event);
    }
}
