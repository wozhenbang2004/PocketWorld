package dev.pocketengine.features.memory;

import dev.pocketengine.features.workspace.JsonFiles;
import java.io.File;
import org.json.JSONObject;

/** Native owner opt-in settings, never a task, inference grant or source authority. */
public final class AutomaticMemoryPolicy {
    private final File file;
    public AutomaticMemoryPolicy(File file){this.file=java.util.Objects.requireNonNull(file);}
    public synchronized JSONObject snapshot()throws Exception{
        JSONObject state=JsonFiles.read(file);if(state.length()==0)return new JSONObject().put("revision",0).put("enabled",false).put("maxUserSourcesPerTask",3).put("candidateAcceptance","native_owner_review_required");
        validate(state);return new JSONObject(state.toString());
    }
    public synchronized JSONObject setForOwner(long expectedRevision,boolean enabled,int maxSources)throws Exception{
        JSONObject before=snapshot();if(before.getLong("revision")!=expectedRevision)throw new IllegalStateException("自动候选设置已变化，请刷新后核对");if(maxSources<1||maxSources>5)throw new IllegalArgumentException("每任务自动提取原话数量为1至5");
        JSONObject next=new JSONObject().put("revision",Math.addExact(expectedRevision,1)).put("enabled",enabled).put("maxUserSourcesPerTask",maxSources).put("candidateAcceptance","native_owner_review_required");JsonFiles.write(file,next);return next;
    }
    public synchronized void requireCurrent(JSONObject expected)throws Exception{JSONObject current=snapshot();if(expected==null||expected.length()!=4||!(expected.opt("revision") instanceof Integer||expected.opt("revision") instanceof Long)||!(expected.opt("enabled") instanceof Boolean)||!(expected.opt("maxUserSourcesPerTask") instanceof Integer)||!"native_owner_review_required".equals(expected.optString("candidateAcceptance"))||current.getLong("revision")!=expected.optLong("revision",-1)||current.getBoolean("enabled")!=expected.optBoolean("enabled",!current.getBoolean("enabled"))||current.getInt("maxUserSourcesPerTask")!=expected.optInt("maxUserSourcesPerTask",-1))throw new SecurityException("自动提取已关闭或策略已改变，旧任务不能提交新候选");}
    private static void validate(JSONObject state)throws Exception{if(state.length()!=4||!(state.opt("revision") instanceof Integer||state.opt("revision") instanceof Long)||state.getLong("revision")<1||!(state.opt("enabled") instanceof Boolean)||!(state.opt("maxUserSourcesPerTask") instanceof Integer)||state.getInt("maxUserSourcesPerTask")<1||state.getInt("maxUserSourcesPerTask")>5||!"native_owner_review_required".equals(state.optString("candidateAcceptance")))throw new IllegalStateException("自动候选设置不可核对，未启用自动提取");}
}
