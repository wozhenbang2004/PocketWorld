package dev.pocketengine.features.memory;

import dev.pocketengine.features.workspace.JsonFiles;
import java.io.File;
import org.json.JSONObject;

/** Native owner opt-in settings, never a task, inference grant or source authority. */
public final class AutomaticMemoryPolicy {
    private final File file;
    public AutomaticMemoryPolicy(File file){this.file=java.util.Objects.requireNonNull(file);}
    /** Keep the original four-field candidate policy bound into existing learning jobs. */
    public synchronized JSONObject snapshot()throws Exception{
        return learning(readState());
    }
    public synchronized JSONObject controlsSnapshot()throws Exception{
        return controls(readState());
    }
    public synchronized boolean readingEnabled()throws Exception{
        return readState().getBoolean("readingEnabled");
    }
    public synchronized JSONObject setForOwner(long expectedRevision,boolean enabled,int maxSources)throws Exception{
        JSONObject state=readState();if(state.getLong("revision")!=expectedRevision)throw new IllegalStateException("自动候选设置已变化，请刷新后核对");if(maxSources<1||maxSources>5)throw new IllegalArgumentException("每任务自动提取原话数量为1至5");
        state.put("revision",Math.addExact(expectedRevision,1)).put("enabled",enabled).put("maxUserSourcesPerTask",maxSources);
        return learning(writeConfirmed(state));
    }
    /** Reading and candidate production keep independent revisions; neither switch changes the other. */
    public synchronized JSONObject setReadForOwner(long expectedRevision,boolean enabled)throws Exception{
        JSONObject state=readState();if(state.getLong("readingRevision")!=expectedRevision)throw new IllegalStateException("记忆读取设置已变化，请刷新后核对");
        state.put("readingRevision",Math.addExact(expectedRevision,1)).put("readingEnabled",enabled);
        return new JSONObject().put("saved",true).put("persisted",true).put("controls",controls(writeConfirmed(state)));
    }
    public synchronized void requireCurrent(JSONObject expected)throws Exception{JSONObject current=snapshot();if(expected==null||expected.length()!=4||!(expected.opt("revision") instanceof Integer||expected.opt("revision") instanceof Long)||!(expected.opt("enabled") instanceof Boolean)||!(expected.opt("maxUserSourcesPerTask") instanceof Integer)||!"native_owner_review_required".equals(expected.optString("candidateAcceptance"))||current.getLong("revision")!=expected.optLong("revision",-1)||current.getBoolean("enabled")!=expected.optBoolean("enabled",!current.getBoolean("enabled"))||current.getInt("maxUserSourcesPerTask")!=expected.optInt("maxUserSourcesPerTask",-1))throw new SecurityException("自动提取已关闭或策略已改变，旧任务不能提交新候选");}
    private JSONObject readState()throws Exception{
        JSONObject state=JsonFiles.read(file);
        if(state.length()==0)return new JSONObject().put("revision",0).put("enabled",false).put("maxUserSourcesPerTask",3)
                .put("candidateAcceptance","native_owner_review_required").put("readingEnabled",true).put("readingRevision",0);
        if(state.length()==4){validateLearning(state,false);return state.put("readingEnabled",true).put("readingRevision",0);}
        if(state.length()!=6)throw new IllegalStateException("记忆设置不可核对，未使用旧的读取或候选设置");
        validateLearning(learning(state),true);
        if(!(state.opt("readingEnabled") instanceof Boolean)||!(state.opt("readingRevision") instanceof Integer||state.opt("readingRevision") instanceof Long)
                ||state.getLong("readingRevision")<0||state.getLong("readingRevision")==0&&!state.getBoolean("readingEnabled"))
            throw new IllegalStateException("记忆读取设置不可核对，未使用旧的读取设置");
        return state;
    }
    private static JSONObject learning(JSONObject state)throws Exception{
        return new JSONObject().put("revision",state.get("revision")).put("enabled",state.get("enabled"))
                .put("maxUserSourcesPerTask",state.get("maxUserSourcesPerTask")).put("candidateAcceptance",state.get("candidateAcceptance"));
    }
    private static JSONObject controls(JSONObject state)throws Exception{
        return new JSONObject().put("reading",new JSONObject().put("enabled",state.getBoolean("readingEnabled")).put("revision",state.getLong("readingRevision")))
                .put("learning",learning(state));
    }
    private JSONObject writeConfirmed(JSONObject state)throws Exception{
        JsonFiles.write(file,state);JSONObject actual=readState();
        if(actual.getLong("revision")!=state.getLong("revision")||actual.getBoolean("enabled")!=state.getBoolean("enabled")
                ||actual.getInt("maxUserSourcesPerTask")!=state.getInt("maxUserSourcesPerTask")||actual.getLong("readingRevision")!=state.getLong("readingRevision")
                ||actual.getBoolean("readingEnabled")!=state.getBoolean("readingEnabled"))throw new IllegalStateException("记忆设置写入后已变化，请刷新核对；未自动重试");
        return actual;
    }
    private static void validateLearning(JSONObject state,boolean allowUnconfigured)throws Exception{
        if(state.length()!=4||!(state.opt("revision") instanceof Integer||state.opt("revision") instanceof Long)||state.getLong("revision")<(allowUnconfigured?0:1)
                ||!(state.opt("enabled") instanceof Boolean)||!(state.opt("maxUserSourcesPerTask") instanceof Integer)||state.getInt("maxUserSourcesPerTask")<1||state.getInt("maxUserSourcesPerTask")>5
                ||!"native_owner_review_required".equals(state.optString("candidateAcceptance"))
                ||state.getLong("revision")==0&&(state.getBoolean("enabled")||state.getInt("maxUserSourcesPerTask")!=3))throw new IllegalStateException("自动候选设置不可核对，未启用自动提取");
    }
}
