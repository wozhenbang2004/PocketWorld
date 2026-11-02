package dev.pocketengine.features.operations;

import android.app.Application;
import dev.pocketengine.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={30,35},application=Application.class)
public final class LiteralUiTextNativeTest {
    @Test public void originalBrowserLabelChangesWithTheSelectedApplicationLanguage()throws Exception {
        Application app=RuntimeEnvironment.getApplication();LiteralUiText.install(app);
        try {
            AppLanguage.select(app,"en");
            assertEquals("Back to assistant",LiteralUiText.text(R.string.ui_lit_f9131cdf8871,"回助手"));
            AppLanguage.select(app,"zh-Hans");
            assertEquals("回助手",LiteralUiText.text(R.string.ui_lit_f9131cdf8871,"回助手"));
        }finally{AppLanguage.select(app,"system");}
    }
}
