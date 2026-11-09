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
    public interface ConversationModels {
        ModelConfig capture(String conversationId, String clientMessageId) throws Exception;
        void allocated(String clientMessageId, String conversationId) throws Exception;
    }
    private volatile ConversationModels conversationModels;
    public synchronized void installConversationModels(ConversationModels host) {
        if (conversationModels != null) throw new IllegalStateException("原对话模型来源已挂载");
        conversationModels = java.util.Objects.requireNonNull(host);
    }
    public ModelConfig conversationModel(String conversationId, String clientMessageId) throws Exception {
        ConversationModels host = conversationModels;
        return host == null ? model() : host.capture(conversationId, clientMessageId);
    }
    public void conversationAllocated(String clientMessageId, String conversationId) throws Exception {
        ConversationModels host = conversationModels;
        if (host != null) host.allocated(clientMessageId, conversationId);
    }
    public boolean executionAllowed(){return !(android instanceof dev.pocketengine.EngineApplication app)||app.executionAllowed();}
    public void requireExecutionEnabled(){if(!executionAllowed())throw new SecurityException("PocketWorld 已停用，请在本机设置明确恢复");}
    /** Existing native resources have no task of their own; retain only the same host admission revision. */
    public void admitResourceExecution(dev.pocketengine.core.RunControl original){
        requireExecutionEnabled();
        if(android instanceof dev.pocketengine.EngineApplication app)try{original.retainAuthorityCheck(app.tasks.admitExecutionControl(original,"","",0));}
        catch(Exception unavailable){throw new SecurityException("原本机资源执行准入不可核对",unavailable);}
    }
    public void emit(String module, JSONObject payload) {
        JSONObject event=new JSONObject();try{event.put("type","feature_event").put("feature",module).put("payload",payload);}catch(Exception ignored){}
        events.emit(event);
    }
}
