package dev.pocketengine.ui;

import dev.pocketengine.features.plugins.catalog.PluginCatalogSession;
import dev.pocketengine.features.plugins.catalog.PluginCatalogSessionTest;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Runs the actual compiled Kotlin guard over real native review objects, not fabricated approval JSON. */
public final class PluginCatalogPageGuardTest {
    @Test public void explicitAcknowledgementAndReadyNativeReviewAreRequired() throws Exception {
        try (PluginCatalogSessionTest.Fixture fixture = new PluginCatalogSessionTest.Fixture()) {
            PluginCatalogSession.View view = fixture.prepareDeclaration();
            assertFalse(PluginCatalogPageGuard.canConfirm(view, false)); assertTrue(PluginCatalogPageGuard.canConfirm(view, true));
            fixture.session.submit(fixture.owner, "native-click", view.approval);
            assertFalse(PluginCatalogPageGuard.canConfirm(fixture.session.view(fixture.owner), true));
        }
    }
    @Test public void requestAndPageIdentityCannotBeSubstitutedByLateMetadata() throws Exception {
        JSONObject data = new JSONObject().put("pageRequestId", "page-A").put("revision", 4);
        assertTrue(PluginCatalogPageGuard.sameRequest("page-A", 4, data));
        assertFalse(PluginCatalogPageGuard.sameRequest("page-B", 4, data)); assertFalse(PluginCatalogPageGuard.sameRequest("page-A", 3, data));
    }
}
