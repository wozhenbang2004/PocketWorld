package dev.pocketengine.runtime;

import dev.pocketengine.core.RunControl;
import java.util.Set;
import org.json.JSONObject;

/** Native registration for one prepared Skill operation. No JSON can install a factory or create a control. */
public final class ScriptPhoneDispatch {
    public interface Actions {Set<String> allowedTools();JSONObject invoke(String tool,JSONObject arguments)throws Exception;}
    @FunctionalInterface public interface Factory {AutoCloseable register(String task,String run,int epoch,String parentOperation,RunControl original,Actions actions)throws Exception;}
    private static volatile Factory factory;
    private ScriptPhoneDispatch(){}
    public static synchronized AutoCloseable installFactory(Factory nativeFactory){
        factory=java.util.Objects.requireNonNull(nativeFactory);
        return ()->{synchronized(ScriptPhoneDispatch.class){if(factory==nativeFactory)factory=null;}};
    }
    public static boolean available(){return factory!=null;}
    static AutoCloseable register(String task,String parent,RunControl original,Actions actions)throws Exception {
        Factory selected=factory;RunControl.HostRunIdentity identity=original.hostRunIdentity();
        if(selected==null||identity==null||!task.equals(identity.taskId())||parent==null||parent.isEmpty())throw new SecurityException("缺少原Skill与原生手机回调绑定");
        original.check();if(original.isPaused())throw new SecurityException("原Skill已暂停");
        return java.util.Objects.requireNonNull(selected.register(task,identity.runId(),identity.epoch(),parent,original,java.util.Objects.requireNonNull(actions)));
    }
}
