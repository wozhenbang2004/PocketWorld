package dev.pocketengine.testutil;

import dev.pocketengine.EngineApplication;
import dev.pocketengine.bootstrap.BootstrapStatus;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Field;
import static org.junit.Assert.*;

/** Explicit setup for tests that shadow Application.onCreate. This is not startup coverage. */
public final class ReadyApplicationFixture {
    private ReadyApplicationFixture(){}
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
