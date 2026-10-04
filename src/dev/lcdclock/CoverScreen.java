package dev.lcdclock;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.util.Log;
import android.view.Display;

/** The razr 40's outside screen (display 1) and opening CoverActivity on it. */
final class CoverScreen {
    static final int DISPLAY_ID = 1;

    private CoverScreen() {
    }

    /** Closed means the inner screen is off; the cover may be on, dozing or off. */
    static boolean isPhoneClosed(Context context) {
        Display inner = context.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        return inner == null || inner.getState() == Display.STATE_OFF;
    }

    /** The phone is closed and the outside screen is showing something. */
    static boolean isCoverOn(Context context) {
        Display cover = context.getSystemService(DisplayManager.class).getDisplay(DISPLAY_ID);
        return cover != null && cover.getState() != Display.STATE_OFF && isPhoneClosed(context);
    }

    /** The phone is closed and the outside screen is fully on: someone is looking at it. */
    static boolean isCoverAwake(Context context) {
        Display cover = context.getSystemService(DisplayManager.class).getDisplay(DISPLAY_ID);
        return cover != null && cover.getState() == Display.STATE_ON && isPhoneClosed(context);
    }

    /** Alarm & Timer; needs a background-activity-start exemption, which ButtonService gives the app. */
    static void open(Context context) {
        open(context, CoverActivity.PAGE_CLOCK);
    }

    static void open(Context context, String page) {
        try {
            Intent intent = new Intent(context, CoverActivity.class)
                    .putExtra(CoverActivity.EXTRA_PAGE, page)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(DISPLAY_ID).toBundle());
        } catch (RuntimeException e) {
            Log.w(Binders.TAG, "could not open the cover screen", e);
        }
    }
}
