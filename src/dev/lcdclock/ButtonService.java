package dev.lcdclock;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Volume-button holds with the phone closed: volume-down opens Alarm & Timer on the cover
 * display, volume-up opens the HOME page of Home Assistant buttons (if any are set up). Accessibility
 * services may start activities from the background, which nothing else on the razr 40's
 * small cover screen can do. Short presses still change the volume. It also runs CoverHome,
 * which opens the cover pages when the outside screen wakes.
 */
public class ButtonService extends AccessibilityService {
    private static final long HOLD_MS = 700;

    private final Handler main = new Handler(Looper.getMainLooper());
    /** The volume key being held, or 0. */
    private int trackedKey;
    private boolean fired;
    private final Runnable hold = () -> {
        fired = true;
        onHold(trackedKey);
    };

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (key != KeyEvent.KEYCODE_VOLUME_DOWN && key != KeyEvent.KEYCODE_VOLUME_UP) {
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                trackedKey = hasHoldAction(key) && CoverScreen.isCoverOn(this) ? key : 0;
                fired = false;
                if (trackedKey != 0) {
                    main.postDelayed(hold, HOLD_MS);
                }
            } else if (key == trackedKey && !fired && (event.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0) {
                main.removeCallbacks(hold);
                fired = true;
                onHold(key);
            }
            return key == trackedKey;
        }
        if (event.getAction() == KeyEvent.ACTION_UP && key == trackedKey) {
            trackedKey = 0;
            main.removeCallbacks(hold);
            if (!fired) {
                // A short press: do what the volume key would have done.
                getSystemService(AudioManager.class).adjustSuggestedStreamVolume(
                        key == KeyEvent.KEYCODE_VOLUME_UP ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                        AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI);
            }
            return true;
        }
        return false;
    }

    private boolean hasHoldAction(int key) {
        return key == KeyEvent.KEYCODE_VOLUME_DOWN || HomeAssistant.hasAnyAction(this);
    }

    private void onHold(int key) {
        if (key == KeyEvent.KEYCODE_VOLUME_DOWN) {
            Log.d(Binders.TAG, "volume-down held: opening the cover screen");
            CoverScreen.open(this);
        } else if (key == KeyEvent.KEYCODE_VOLUME_UP) {
            Log.d(Binders.TAG, "volume-up held: opening HOME");
            CoverScreen.open(this, CoverActivity.PAGE_HOME);
        }
    }

    @Override
    protected void onServiceConnected() {
        CoverHome.start(this);
        Prefs.dropFossify(this);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        CoverHome.stop();
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
