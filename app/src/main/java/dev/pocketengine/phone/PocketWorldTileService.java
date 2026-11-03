package dev.pocketengine.phone;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;
import dev.pocketengine.ui.ComposeActivity;

/** System-bound launcher only. A tile click neither observes a screen nor starts/resumes a task. */
public final class PocketWorldTileService extends TileService {
    @Override public void onStartListening() {
        super.onStartListening();
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(Tile.STATE_INACTIVE);
        tile.setSubtitle("打开应用");
        tile.setContentDescription("打开 PocketWorld；不会自动开始或继续任务");
        tile.updateTile();
    }

    @Override public void onClick() {
        super.onClick();
        // Opening the existing private workspace requires the system's ordinary unlock challenge.
        unlockAndRun(this::openOwnerSurface);
    }

    @SuppressWarnings("deprecation")
    private void openOwnerSurface() {
        Intent open = new Intent(this, ComposeActivity.class).setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                PendingIntent exact = PendingIntent.getActivity(this, 0, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                startActivityAndCollapse(exact);
            } else startActivityAndCollapse(open);
        } catch (RuntimeException unavailable) {
            Toast.makeText(this, "暂时无法打开 PocketWorld，请从应用图标进入。", Toast.LENGTH_SHORT).show();
        }
    }
}
