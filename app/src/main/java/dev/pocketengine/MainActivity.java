package dev.pocketengine;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import dev.pocketengine.features.assistant.CompatibilityChatHandoff;
import dev.pocketengine.ui.ComposeActivity;

/** Retained Android component identity. Every entry uses the one PocketWebHost frontend. */
public final class MainActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        setTheme(dev.pocketengine.ui.UiAppearance.theme(this));
        super.onCreate(state);
        redirect(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        redirect(intent);
    }

    private void redirect(Intent incoming) {
        if (isFinishing()) return;
        Intent target = new Intent(this, ComposeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (incoming != null) {
            // Copy navigation only. Old goal text, settings, grants, files and bridge commands
            // are never replayed by this compatibility component.
            if (incoming.hasExtra("openPanel")) {
                String panel;
                try { panel = incoming.getStringExtra("openPanel"); sectionForPanel(panel); }
                catch (RuntimeException invalid) { panel = "invalid"; }
                incoming.removeExtra("openPanel");
                target.putExtra("openPanel", panel);
            }
            if (incoming.hasExtra("automationGoal")) {
                incoming.removeExtra("automationGoal");
                target.putExtra("automationGoal", "");
            }
            Uri route = incoming.getData();
            if (route != null && "pocketengine".equals(route.getScheme()) &&
                    ("automation".equals(route.getHost()) || "owner-request".equals(route.getHost())) &&
                    route.getUserInfo() == null && route.getQuery() == null && route.getFragment() == null) {
                target.setData(route);
                incoming.setData(null);
            }
            copyLocator(incoming, target, "channelEventId");
            copyLocator(incoming, target, "automationOccurrence");
            copyLocator(incoming, target, "automationRuleId");
            copyLocator(incoming, target, CompatibilityChatHandoff.EXTRA);
        }
        startActivity(target);
        finish();
    }

    private static void copyLocator(Intent source, Intent target, String name) {
        if (!source.hasExtra(name)) return;
        try {
            String value = source.getStringExtra(name);
            if (value != null && !value.isBlank() && value.length() <= 240) target.putExtra(name, value);
        } catch (RuntimeException ignored) { /* Invalid extras are not navigation authority. */ }
        source.removeExtra(name);
    }

    /** Existing callers retain their API, but no longer enter a separate renderer. */
    public static Intent advancedPanelIntent(Context context, String panel) {
        sectionForPanel(panel);
        return new Intent(context, ComposeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("openPanel", panel);
    }

    /** Exact local routes only; no capability grant or configuration payload. */
    public static String sectionForPanel(String panel) {
        if (panel == null) throw new IllegalArgumentException("不支持的设置页面");
        return switch (panel) {
            case "settings" -> "models";
            case "channels", "plugins", "proxy" -> panel;
            default -> throw new IllegalArgumentException("不支持的设置页面");
        };
    }
}
