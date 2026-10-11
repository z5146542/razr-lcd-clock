package dev.lcdclock;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.VibratorManager;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Volume-button holds with the phone closed and the outside screen on: each button's shortcut is
 * chosen in the app (Prefs.holdAction): a page to open (TIMER for volume-down and HOME for
 * volume-up at first; HOME only once a Home Assistant button is set up), the flashlight, or
 * nothing. Accessibility services may start activities from the background, which nothing else
 * on the razr 40's small cover screen can do. Short presses still change the volume. It also runs
 * CoverHome, which opens the cover pages when the outside screen wakes, ClockWidget's
 * minute-by-minute redraw of the home screen widget while the inside screen is on (when
 * MediaListener isn't running it), and PipGuard, which keeps picture-in-picture video playing when
 * the phone closes. ButtonPause switches it off while the phone is open, for banking apps.
 */
public class ButtonService extends AccessibilityService {
    private static final long HOLD_MS = 700;
    /** Connected: Android has it on. */
    static boolean running;
    private static ButtonService instance;

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
        String action = Prefs.holdAction(this, key == KeyEvent.KEYCODE_VOLUME_UP);
        if (Prefs.HOLD_NONE.equals(action)) {
            return false; // the button just changes the volume
        }
        return !CoverActivity.PAGE_HOME.equals(action) || HomeAssistant.hasAnyAction(this);
    }

    private void onHold(int key) {
        boolean up = key == KeyEvent.KEYCODE_VOLUME_UP;
        String action = Prefs.holdAction(this, up);
        Log.d(Binders.TAG, "volume-" + (up ? "up" : "down") + " held: " + action);
        if (Prefs.HOLD_TORCH.equals(action)) {
            QuickControls.toggleTorch(this);
            getSystemService(VibratorManager.class).getDefaultVibrator()
                    .vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
        } else if (!Prefs.HOLD_NONE.equals(action)) {
            CoverScreen.open(this, action);
        }
    }

    @Override
    protected void onServiceConnected() {
        running = true;
        instance = this;
        // On, whoever switched it on: switched off in Android's settings from here, it stays off.
        // (Unless ButtonPause took it out again while it was connecting.)
        if (ButtonPause.isOn(this)) {
            Prefs.get(this).edit().remove(Prefs.BUTTON_PAUSED).apply();
        }
        CoverHome.start(this);
        ClockWidget.start(this);
        PipGuard.start(this);
        Prefs.dropFossify(this);
        ButtonWidget.update(this);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        running = false;
        instance = null;
        CoverHome.stop();
        if (MediaListener.connected == null) {
            ClockWidget.stop();
        }
        PipGuard.stop();
        ButtonWidget.update(this);
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getDisplayId() == Display.DEFAULT_DISPLAY) {
            PipGuard.onWindowsChanged();
        }
    }

    /** Locks the phone now, as the power button does; false while the service is off. */
    static boolean lockScreen() {
        return instance != null && instance.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
    }

    @Override
    public void onInterrupt() {
    }
}
