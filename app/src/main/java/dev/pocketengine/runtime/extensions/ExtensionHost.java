package dev.pocketengine.runtime.extensions;

import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.skills.AndroidJavascriptSandboxRuntime;
import dev.pocketengine.store.TaskStore;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Application-owned binding factory, not another task engine, sandbox or approval registry. */
public final class ExtensionHost implements AutoCloseable {
    public interface Catalogue {
        /** Actual approved executable packages in this native task's authenticated scope. */
        List<ExtensionProgram> approved(ExtensionRuntime.Binding binding,RunControl originalControl)throws Exception;
        /** Current enabled/approved source, installation and approvalRevision must still match. */
        void checkCurrent(ExtensionProgram program,ExtensionRuntime.Binding binding,RunControl originalControl)throws Exception;
        /** Read-only source identity check; never sufficient to dispatch a prepared effect. */
        default void checkSourceCurrent(ExtensionProgram program,ExtensionRuntime.Binding binding,RunControl originalControl)throws Exception{checkCurrent(program,binding,originalControl);}
    }
    interface Evaluators {ExtensionRuntime.Evaluator create();}
    private final Evaluators evaluators;
    private final Catalogue catalogue;
    private final TaskStore tasks;
    private final Set<ExtensionCapabilityPort> live=ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public ExtensionHost(AndroidJavascriptSandboxRuntime sharedWarmSandbox,Catalogue trustedCatalogue,TaskStore tasks){
        java.util.Objects.requireNonNull(sharedWarmSandbox);evaluators=()->new AndroidExtensionEvaluator(sharedWarmSandbox);catalogue=java.util.Objects.requireNonNull(trustedCatalogue);this.tasks=java.util.Objects.requireNonNull(tasks);
    }
    ExtensionHost(Catalogue trustedCatalogue,TaskStore tasks,Evaluators evaluators){catalogue=java.util.Objects.requireNonNull(trustedCatalogue);this.tasks=java.util.Objects.requireNonNull(tasks);this.evaluators=java.util.Objects.requireNonNull(evaluators);}
    private ExtensionHost(TaskStore tasks){this.tasks=java.util.Objects.requireNonNull(tasks);evaluators=null;catalogue=null;}
    /** Explicit unavailable integration, not a fallback after a real catalogue/authority failure. */
    public static ExtensionHost disabled(TaskStore tasks){return new ExtensionHost(tasks);}

    /** Wrap the FINAL combined capability port, including W08, using the original live control. */
    public CapabilityPort bind(CapabilityPort combined,ExtensionRuntime.Binding binding,RunControl originalControl)throws Exception {
        if(closed)throw new IllegalStateException("扩展宿主已关闭");
        java.util.Objects.requireNonNull(combined);java.util.Objects.requireNonNull(binding);java.util.Objects.requireNonNull(originalControl);
        if(!originalControl.hasHostRun()||!originalControl.matchesHostRun(binding.taskId,binding.runId,binding.epoch))throw new SecurityException("扩展必须绑定原任务控制器");
        ExtensionCapabilityPort port=new ExtensionCapabilityPort(this,combined,tasks,binding,originalControl);live.add(port);
        if(closed){port.close();throw new IllegalStateException("扩展宿主已关闭");}return port;
    }
    List<ExtensionProgram> approved(ExtensionRuntime.Binding binding,RunControl control)throws Exception {
        if(closed)throw new IllegalStateException("扩展宿主已关闭");
        return catalogue==null?List.of():java.util.Objects.requireNonNull(catalogue.approved(binding,control),"扩展目录不能用null冒充空列表");
    }
    ExtensionRuntime runtime(ExtensionRuntime.Binding binding)throws Exception {
        if(catalogue==null||evaluators==null)throw new IllegalStateException("没有已接入的可执行扩展目录与沙箱");
        return new ExtensionRuntime(evaluators.create(),binding,(program,scope,control)->{
            tasks.checkExtensionRun(scope.taskId,scope.runId,scope.epoch,control);catalogue.checkCurrent(program,scope,control);
        });
    }
    void checkSourceCurrent(ExtensionProgram program,ExtensionRuntime.Binding binding,RunControl control)throws Exception{
        if(closed||catalogue==null)throw new SecurityException("原扩展来源已关闭");catalogue.checkSourceCurrent(program,binding,control);
    }
    void release(ExtensionCapabilityPort port){live.remove(port);}
    /** Call after a committed native disable/replace/delete; missed events still fail live checks. */
    public void invalidate(String installationId){for(ExtensionCapabilityPort port:live)port.invalidate(installationId);}
    @Override public void close(){
        closed=true;RuntimeException failure=null;for(ExtensionCapabilityPort port:live)try{port.close();}catch(RuntimeException error){failure=error;}
        if(failure!=null)throw failure;
        // The Application alone closes its shared AndroidJavascriptSandboxRuntime.
    }
}
