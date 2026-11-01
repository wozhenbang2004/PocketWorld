package dev.pocketengine.features.teams;
import android.app.Application;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
/** Runs the unchanged module definition checks in an actual Android Context sandbox. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class TeamsDefinitionHostRuntimeTest extends TeamsDefinitionTest {}
