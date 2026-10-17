package dev.lcdclock;

import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.PowerManager;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

/**
 * The CALL panel (CallView) as an accessibility overlay on the outside screen. While a call rings,
 * Motorola's own call screen sits in a system window above every app, CoverActivity included;
 * only an accessibility service's overlay goes over it. Shown while the call lasts, gone when it
 * ends or the phone opens. Needs ButtonService running; Calls falls back to CoverActivity without.
 */
final class CallOverlay {
    private static CallView view;
    private static WindowManager windows;
    private static DisplayManager displays;

    /** The phone opening: the call carries on inside. */
    private static final DisplayManager.DisplayListener innerScreen = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
        }

        @Override
        public void onDisplayRemoved(int displayId) {
        }

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId == Display.DEFAULT_DISPLAY && displays != null) {
                Display inner = displays.getDisplay(Display.DEFAULT_DISPLAY);
                if (inner != null && inner.getState() != Display.STATE_OFF) {
                    hide();
                }
            }
        }
    };

    private CallOverlay() {
    }

    static boolean isShowing() {
        return view != null;
    }

    /** Shows the panel over the outside screen; false if it can't (the service is off). */
    static boolean show() {
        if (view != null) {
            return true;
        }
        Context service = ButtonService.get();
        if (service == null) {
            return false;
        }
        try {
            displays = service.getSystemService(DisplayManager.class);
            Display cover = displays.getDisplay(CoverScreen.DISPLAY_ID);
            if (cover == null) {
                return false;
            }
            Context context = service.createDisplayContext(cover)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null);
            windows = context.getSystemService(WindowManager.class);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.OPAQUE);
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            view = new CallView(context, CallOverlay::hide);
            windows.addView(view, params);
            displays.registerDisplayListener(innerScreen, null);
            wake(service);
            Log.d(Binders.TAG, "call: overlay shown");
            return true;
        } catch (RuntimeException e) {
            Log.w(Binders.TAG, "call: can't show the overlay", e);
            view = null;
            return false;
        }
    }

    /** Turns the outside screen on for the ring; the window's flag keeps it on from there. */
    @SuppressWarnings("deprecation")
    private static void wake(Context context) {
        PowerManager.WakeLock lock = context.getSystemService(PowerManager.class).newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "LcdClock:call");
        lock.acquire(3000);
    }

    static void hide() {
        if (view == null) {
            return;
        }
        try {
            windows.removeView(view);
        } catch (RuntimeException e) {
            Log.w(Binders.TAG, "call: can't remove the overlay", e);
        }
        displays.unregisterDisplayListener(innerScreen);
        view = null;
        Log.d(Binders.TAG, "call: overlay gone");
    }
}
