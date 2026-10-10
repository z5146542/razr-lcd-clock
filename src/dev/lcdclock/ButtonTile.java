package dev.lcdclock;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * The "Cover button" quick settings tile: lit while the accessibility service is on, with
 * ButtonPause's state underneath, and a tap switches it (ButtonPause.toggle), after unlocking if
 * the phone is locked. Without notification access or "write secure settings" nothing can switch
 * it, so a tap opens the app's Setup list instead. Android asks it for its state each time the
 * quick settings open.
 */
public class ButtonTile extends TileService {
    @Override
    public void onStartListening() {
        refresh();
    }

    @Override
    public void onClick() {
        if (!ButtonPause.manages(this)) {
            openApp();
        } else if (isLocked()) {
            unlockAndRun(this::toggle);
        } else {
            toggle();
        }
    }

    private void toggle() {
        ButtonPause.toggle(this);
        ButtonWidget.update(this);
        refresh();
    }

    private void refresh() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        String state = ButtonPause.state(this);
        tile.setState("on".equals(state) ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setSubtitle(getString("on".equals(state) ? R.string.tile_on
                : "paused".equals(state) ? R.string.tile_paused : R.string.tile_off));
        tile.updateTile();
    }

    @SuppressWarnings("deprecation") // the Intent form, for Android 13
    private void openApp() {
        Intent intent = new Intent(this, PreviewActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE));
        } else {
            startActivityAndCollapse(intent);
        }
    }
}
