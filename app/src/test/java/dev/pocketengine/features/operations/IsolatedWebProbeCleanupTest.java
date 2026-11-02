package dev.pocketengine.features.operations;

import android.app.Application;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.util.concurrent.ExecutionException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class IsolatedWebProbeCleanupTest {
    private static final class FailingStopView extends WebView {
        boolean destroyed;
        FailingStopView(Application app){super(app);}
        @Override public void stopLoading(){throw new IllegalStateException("controlled stop failure");}
        @Override public void setWebViewClient(WebViewClient client){}
        @Override public void removeAllViews(){}
        @Override public void destroy(){destroyed=true;}
    }
    @Test public void failedStopStillDestroysTheOriginalOwnedViewAndReportsCleanupUnconfirmed()throws Exception{
        Application app=RuntimeEnvironment.getApplication();IsolatedWebProbe probe=new IsolatedWebProbe(app);FailingStopView owned=new FailingStopView(app);
        var field=IsolatedWebProbe.class.getDeclaredField("web");field.setAccessible(true);field.set(probe,owned);
        assertThrows(ExecutionException.class,probe::close);assertTrue(owned.destroyed);assertNull(field.get(probe));
    }
}
