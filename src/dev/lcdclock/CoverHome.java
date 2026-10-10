package dev.lcdclock;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;

/**
 * Opens the cover pages when the outside screen wakes with the phone closed: the playing app's
 * MUSIC page, or LCD Clock's own copy of the clock face (while LCD Digits is the chosen face and
 * the app's setting allows), from which swipes reach the other pages. Run by ButtonService,
 * whose accessibility binding is what lets the app open activities from the background.
 * Usually CoverActivity is still there from before the screen slept and this has nothing to
 * do; it covers the first wake after closing the phone, or after leaving our face.
 */
final class CoverHome {
    /** Closing the phone wakes the cover before the inner screen reports off; check after this. */
    private static final long WAKE_SETTLE_MS = 400;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Context app;
    private static int coverState = Display.STATE_UNKNOWN;

    private CoverHome() {
    }

    private static final DisplayManager.DisplayListener coverListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
        }

        @Override
        public void onDisplayRemoved(int displayId) {
        }

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId != CoverScreen.DISPLAY_ID) {
                return;
            }
            int state = coverState();
            boolean woke = state == Display.STATE_ON && coverState != Display.STATE_ON;
            coverState = state;
            if (woke) {
                main.postDelayed(CoverHome::onWake, WAKE_SETTLE_MS);
            }
        }
    };

    static void start(Context context) {
        if (app != null) {
            return;
        }
        app = context.getApplicationContext();
        coverState = coverState();
        app.getSystemService(DisplayManager.class).registerDisplayListener(coverListener, main);
        if (coverState == Display.STATE_ON) {
            // ButtonPause switches the service on as the phone closes, after the cover has woken.
            main.postDelayed(CoverHome::onWake, WAKE_SETTLE_MS);
        }
    }

    static void stop() {
        if (app == null) {
            return;
        }
        app.getSystemService(DisplayManager.class).unregisterDisplayListener(coverListener);
        main.removeCallbacksAndMessages(null);
        app = null;
    }

    private static void onWake() {
        if (app == null || !CoverScreen.isCoverAwake(app) || CoverActivity.isShowing() || CoverActivity.ringing(app)) {
            return;
        }
        String page = CoverActivity.homePage(app);
        if (page != null) {
            Log.d(Binders.TAG, "cover woke: opening " + page);
            CoverScreen.open(app, page);
        }
    }

    private static int coverState() {
        Display cover = app.getSystemService(DisplayManager.class).getDisplay(CoverScreen.DISPLAY_ID);
        return cover == null ? Display.STATE_UNKNOWN : cover.getState();
    }
}
