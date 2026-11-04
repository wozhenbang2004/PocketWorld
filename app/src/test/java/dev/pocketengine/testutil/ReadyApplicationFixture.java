package dev.pocketengine.testutil;

import dev.pocketengine.EngineApplication;
import dev.pocketengine.bootstrap.BootstrapStatus;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Explicit setup for tests that shadow Application.onCreate. This is not startup coverage. */
public final class ReadyApplicationFixture {
    private ReadyApplicationFixture(){}
    /** Config.NONE journeys have no installed package/resources. They explicitly mount
     * the real business host; installation, migration and asynchronous bootstrap have
     * their own tests and are never declared successful by this shadow. */
    @org.robolectric.annotation.Implements(EngineApplication.class)
    public static final class NoUnrelatedStartup extends org.robolectric.shadows.ShadowApplication {
        @org.robolectric.annotation.Implementation protected void onCreate(){}
    }
    private static final Map<EngineApplication,HostState> hosts=new IdentityHashMap<>();
    private static final class HostState {
        final Map<Field,Object> prior=new LinkedHashMap<>();
        HostState()throws Exception {
            capture(RunControl.class,"hostAdmission");capture(InferenceCapture.class,"budgetAttribution");capture(InferenceCapture.class,"presentationObserver");
            capture(dev.pocketengine.runtime.models.ModelRequestPolicy.class,"source");capture(dev.pocketengine.runtime.context.ContextBudgetPolicy.class,"hostSource");
            capture(dev.pocketengine.runtime.models.StepModelRouting.class,"nativeSource");capture(dev.pocketengine.runtime.usage.InferencePricing.class,"source");
            capture(dev.pocketengine.runtime.ModelGateway.class,"modelGroupAdmission");capture(dev.pocketengine.runtime.ModelGateway.class,"accountInference");
            capture(dev.pocketengine.runtime.routes.PhoneRouteSession.class,"factory");capture(dev.pocketengine.BrowserActivity.class,"ownerPanelInstaller");
        }
        void capture(Class<?> owner,String name)throws Exception {Field field=field(owner,name);prior.put(field,field.get(null));}
        void restore()throws Exception {
            for(var entry:prior.entrySet()){
                if(entry.getKey().getDeclaringClass()==dev.pocketengine.runtime.ModelGateway.class&&entry.getKey().getName().equals("accountInference"))
                    dev.pocketengine.runtime.ModelGateway.installAccountInferencePort((dev.pocketengine.runtime.ModelGateway.AccountInferencePort)entry.getValue());
                else entry.getKey().set(null,entry.getValue()); // Preserve an original null hook too.
            }
        }
    }
    private static Field field(Class<?> owner,String name)throws Exception {Field field=owner.getDeclaredField(name);field.setAccessible(true);return field;}
    private static void initialize(EngineApplication app,String name)throws Exception {
        var method=EngineApplication.class.getDeclaredMethod(name);method.setAccessible(true);
        try{method.invoke(app);}catch(java.lang.reflect.InvocationTargetException failed){Throwable cause=failed.getCause();if(cause instanceof Exception exception)throw exception;if(cause instanceof Error error)throw error;throw failed;}
    }
    /** Call after installing the explicit test-only AndroidKeyStore and before creating
     * task/service/UI fixtures. The original TaskStore gate and all host callbacks stay real. */
    public static synchronized void initializeHost(EngineApplication app)throws Exception {
        assertTrue("Non-startup journey must explicitly select NoUnrelatedStartup",org.robolectric.shadow.api.Shadow.extract(app) instanceof NoUnrelatedStartup);
        assertFalse("Host fixture cannot initialize the same Application twice",hosts.containsKey(app));
        assertNull("Do not replace an already opened original TaskStore",app.tasks);assertNull("Do not race an original asynchronous bootstrap",app.features);assertNull(app.settings);
        HostState state=new HostState();hosts.put(app,state);
        try{
            android.os.SystemClock.setCurrentTimeMillis(System.currentTimeMillis());
            RunControl.installHostAdmission((original,id,run,epoch)->{if(!app.businessReady())throw new SecurityException("Explicit host fixture is not ready");return app.tasks.admitExecutionControl(original,id,run,epoch);});
            InferenceCapture.installBudgetAttribution((capture,identity)->{if(!app.businessReady())throw new SecurityException("Explicit host fixture is not ready");app.tasks.requireTokenBudgetAttribution(capture,identity);});
            app.settings=new SettingsStore(app);app.tasks=new TaskStore(app,app::businessReady,(stage,version)->{});
            field(EngineApplication.class,"assistantTaskNotifications").set(app,new dev.pocketengine.features.assistant.notifications.AssistantTaskNotifications(app,app.tasks));
            initialize(app,"initializeModelConnections");assertNotNull("The original model catalog must actually mount",app.modelConnections);
            field(EngineApplication.class,"attachmentRoutePins").set(app,new dev.pocketengine.features.AssistantAttachmentRoutePins(new dev.pocketengine.store.SecretVault(app)));
            android.os.Handler main=(android.os.Handler)field(EngineApplication.class,"main").get(app);
            @SuppressWarnings("unchecked") Iterable<EngineApplication.Observer> observers=(Iterable<EngineApplication.Observer>)field(EngineApplication.class,"observers").get(app);
            field(EngineApplication.class,"modelStreams").set(app,new dev.pocketengine.features.NativeModelStreamUi(app.tasks,main,app::ownsNativeTaskControl,event->{for(EngineApplication.Observer observer:observers)try{observer.onEvent(event);}catch(RuntimeException ignored){}}));
            initialize(app,"initializeFeatures");assertNotNull(app.features);assertNotNull(app.features.module("assistant"));assertNotNull(app.features.module("memory"));
            initialize(app,"initializeDeviceWorkspace");
            assertNotNull("The original device workspace must mount with its cancellation host",app.deviceWorkspaceHost);
            assertNotNull("The original device task scope host must mount",app.deviceTaskScopeHost);
            assertNotNull("Cancellation must use the original bounded child-stop queue",field(EngineApplication.class,"originalDeviceChildStops").get(app));
            complete(app);
        }catch(Exception|Error failure){try{closeHost(app);}catch(Exception|Error cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    /** Idempotent even after partial setup. Restore only callbacks captured by this
     * explicit fixture, so later pure TaskStore tests cannot inherit a closed App. */
    public static synchronized void closeHost(EngineApplication app)throws Exception {
        HostState state=hosts.remove(app);if(state==null)return;Throwable failure=null;
        try{app.onTerminate();}catch(Throwable stopped){failure=stopped;}
        try{if(app.features!=null)app.features.close();}catch(Throwable closed){failure=combine(failure,closed);}
        try{ExecutorService worker=(ExecutorService)field(EngineApplication.class,"capabilityWorkers").get(app);assertTrue("Original host workers must settle before its database closes",worker.awaitTermination(5,TimeUnit.SECONDS));}catch(Throwable stopped){failure=combine(failure,stopped);}
        try{if(app.tasks!=null){var db=(android.database.sqlite.SQLiteDatabase)field(TaskStore.class,"db").get(app.tasks);if(db.isOpen())db.close();}}catch(Throwable closed){failure=combine(failure,closed);}
        try{state.restore();}catch(Throwable restored){failure=combine(failure,restored);}
        if(failure instanceof Exception exception)throw exception;if(failure instanceof Error error)throw error;
    }
    private static Throwable combine(Throwable first,Throwable next){if(first==null)return next;first.addSuppressed(next);return first;}
    public static void complete(EngineApplication app)throws Exception {
        assertNotNull("The fixture must create its actual original TaskStore first",app.tasks);
        app.tasks.verifyBootstrapReadback();
        Field field=EngineApplication.class.getDeclaredField("bootstrap");field.setAccessible(true);
        BootstrapStatus state=(BootstrapStatus)field.get(app);
        // Some journey tests mount successive isolated hosts in the same test Application.
        if(!state.ready()){
            assertFalse(app.businessReady());
            long generation=state.beginInitial();
            state.stage(generation,"data_opened",TaskStore.DATABASE_SCHEMA);
            state.stage(generation,"migrated",TaskStore.DATABASE_SCHEMA);
            state.recovering(generation);
            state.stage(generation,"features_ready",TaskStore.DATABASE_SCHEMA);
            assertTrue(state.complete(generation));
        }
        assertTrue(app.businessReady());
    }
}
