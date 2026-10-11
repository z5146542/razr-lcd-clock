package dev.lcdclock;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps picture-in-picture video playing through closing the phone. Motorola's SystemUI dismisses
 * any PiP window the moment the hinge reports the phone folded (a FoldStateListener in its
 * PipController that no setting turns off), and apps stop playing when their PiP window is
 * dismissed. So while the phone is closing this expands the PiP window back to full screen through
 * the "expand" accessibility action SystemUI gives it: an app on its way out of PiP no longer
 * counts as in PiP, closing just turns its screen off, and apps that play in the background
 * (YouTube, Twitch) carry on; opening the phone finds the app full screen, still playing. If the
 * hinge only dipped and the phone never closed, Home puts it straight back into PiP.
 *
 * SystemUI's PiP code also takes every display's rotation for the inner screen's, and the cover
 * screen rotates when the phone folds and opens, so after a fold PiP lays itself out for a
 * landscape screen and the next PiP window lands off the right edge, where it can't be seen (nor
 * found to expand). Only a real rotation of the inner screen puts that right, so the first time a
 * playing video leaves the screen after a fold (most likely into PiP) this turns the screen once
 * (PipResetActivity). Run by ButtonService, whose accessibility binding gives the window access,
 * window changes, the Home action and the right to open activities from the background; with the
 * phone open, ButtonPause keeps that on only while a PiP-capable app is playing.
 */
final class PipGuard {
    /**
     * The hinge sensor reports in bursts ~190 ms apart, and Motorola commits "folded" as the angle
     * reaches ~15°, so waiting for a low angle is too late when the phone is shut quickly: a drop
     * of DROP_DEG within DROP_WINDOW_MS, ending below FAST_BELOW_DEG, already counts as closing.
     */
    private static final float DROP_DEG = 30;
    private static final long DROP_WINDOW_MS = 400;
    private static final float FAST_BELOW_DEG = 110;
    /** Below this it's closing, however slowly. */
    private static final float CLOSING_DEG = 45;
    /** Back above this with the inner screen still on: it wasn't closed after all. */
    private static final float OPEN_DEG = 120;
    /** Lets the hinge settle before a PiP that wasn't closed after all goes back. */
    private static final long RESTORE_DELAY_MS = 600;
    /** Gives up on the screen turning (and goes on) after this. */
    private static final long RESET_TIMEOUT_MS = 2500;
    /** The owner of the PiP menu window, which counts as in PiP too. */
    private static final String SYSTEM_UI = "com.android.systemui";
    /** ActivityInfo.FLAG_SUPPORTS_PICTURE_IN_PICTURE, which the SDK hides. */
    private static final int FLAG_SUPPORTS_PIP = 0x400000;
    /** Lets an app that just left finish going into PiP before the screen turns. */
    private static final long LEFT_SETTLE_MS = 700;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static AccessibilityService service;
    /** Recent hinge readings, oldest first: when (elapsed ms) and the angle. */
    private static final ArrayDeque<Long> times = new ArrayDeque<>();
    private static final ArrayDeque<Float> angles = new ArrayDeque<>();
    private static float lastAngle = Float.NaN;
    /** The app whose PiP window was expanded while closing, until the phone opens again; or null. */
    private static String expanded;
    /** The inner screen went off after the expand: the phone really closed. */
    private static boolean closed;
    /** The app that left the screen while the screen turns (PipResetActivity), or null. */
    private static String resetting;
    private static PipResetActivity resetScreen;
    /** The inner screen has turned away from portrait during the reset. */
    private static boolean turned;
    /**
     * The phone has folded since the inner screen last really turned, so PiP's geometry is off.
     * Unknown at first (e.g. after an update), so taken as off.
     */
    private static boolean dirty = true;
    private static boolean innerOff;
    private static int innerRotation = Surface.ROTATION_0;
    /** The PiP-capable app that was in front playing at the last window change, or null. */
    private static String playingInFront;
    private static String leftApp;
    /** Whether each app has an activity that can go into PiP. */
    private static final Map<String, Boolean> pipApps = new HashMap<>();

    private PipGuard() {
    }

    private static final SensorEventListener hinge = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            onAngle(event.values[0], event.timestamp / 1_000_000);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    private static final DisplayManager.DisplayListener innerScreen = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
        }

        @Override
        public void onDisplayRemoved(int displayId) {
        }

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId != Display.DEFAULT_DISPLAY) {
                return;
            }
            Display inner = service.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
            boolean off = inner == null || inner.getState() == Display.STATE_OFF;
            int rotation = inner == null ? Surface.ROTATION_0 : inner.getRotation();
            if (off && !innerOff && !(lastAngle >= CLOSING_DEG)) {
                dirty = true; // folded: the cover screen's rotation has thrown PiP out
            } else if (!off && rotation != innerRotation) {
                dirty = false; // a real turn of the inner screen puts it right
            }
            innerOff = off;
            innerRotation = rotation;
            if (resetting != null) {
                onResetRotation();
            } else if (expanded == null) {
                return;
            } else if (off) {
                closed = true;
            } else if (closed) {
                Log.d(Binders.TAG, "pip guard: opened, leaving " + expanded + " full screen");
                expanded = null;
                closed = false;
            }
        }
    };

    static void start(AccessibilityService accessibility) {
        if (service != null) {
            return;
        }
        service = accessibility;
        SensorManager sensors = service.getSystemService(SensorManager.class);
        Sensor sensor = sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE, true);
        if (sensor == null) {
            sensor = sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE);
        }
        if (sensor == null) {
            Log.w(Binders.TAG, "pip guard: no hinge angle sensor");
        } else {
            // On-change: it only reports while the hinge moves, so it can stay registered.
            sensors.registerListener(hinge, sensor, SensorManager.SENSOR_DELAY_FASTEST, main);
        }
        DisplayManager displays = service.getSystemService(DisplayManager.class);
        displays.registerDisplayListener(innerScreen, main);
        Display inner = displays.getDisplay(Display.DEFAULT_DISPLAY);
        innerOff = inner == null || inner.getState() == Display.STATE_OFF;
        innerRotation = inner == null ? Surface.ROTATION_0 : inner.getRotation();
        onWindowsChanged(); // what's in front already
    }

    static void stop() {
        if (service == null) {
            return;
        }
        service.getSystemService(SensorManager.class).unregisterListener(hinge);
        service.getSystemService(DisplayManager.class).unregisterDisplayListener(innerScreen);
        main.removeCallbacksAndMessages(null);
        if (resetScreen != null) {
            resetScreen.close();
        }
        times.clear();
        angles.clear();
        lastAngle = Float.NaN;
        expanded = null;
        resetting = null;
        playingInFront = null;
        service = null;
    }

    // ---- closing ----

    private static void onAngle(float angle, long at) {
        while (!times.isEmpty() && at - times.peekFirst() > DROP_WINDOW_MS) {
            times.pollFirst();
            angles.pollFirst();
        }
        float highest = angle;
        for (float a : angles) {
            highest = Math.max(highest, a);
        }
        times.addLast(at);
        angles.addLast(angle);
        boolean shutting = angle < FAST_BELOW_DEG && highest - angle >= DROP_DEG;
        boolean crossed = angle < CLOSING_DEG && lastAngle >= CLOSING_DEG;
        lastAngle = angle;
        if (expanded == null && resetting == null && (shutting || crossed) && Prefs.keepPip(service)) {
            String app = expandPip();
            if (app != null) {
                Log.d(Binders.TAG, "pip guard: closing (" + Math.round(highest) + "° -> " + Math.round(angle)
                        + "°), expanded PiP of " + app);
                expanded = app;
                closed = false;
            }
        } else if (expanded != null && !closed && angle > OPEN_DEG) {
            main.removeCallbacks(restore);
            main.postDelayed(restore, RESTORE_DELAY_MS);
        }
    }

    /**
     * Expands the PiP window to full screen; returns its app, or null if there was none. A window
     * that entered PiP less than about a second ago isn't in the accessibility list yet.
     */
    private static String expandPip() {
        // Without window events the cached window list goes stale.
        service.clearCache();
        for (AccessibilityWindowInfo window : service.getWindows()) {
            if (!window.isInPictureInPictureMode()) {
                continue;
            }
            AccessibilityNodeInfo root = window.getRoot();
            CharSequence name = root == null ? null : root.getPackageName();
            if (name == null || SYSTEM_UI.contentEquals(name)) {
                continue; // the PiP menu, while it's showing
            }
            String app = name.toString();
            if (root.performAction(AccessibilityNodeInfo.ACTION_EXPAND)) {
                return app;
            }
            // SystemUI refuses while its PiP menu is showing; bringing the app's task to the
            // front takes it out of PiP just the same.
            Intent launch = service.getPackageManager().getLaunchIntentForPackage(app);
            if (launch != null) {
                try {
                    service.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle());
                    Log.d(Binders.TAG, "pip guard: SystemUI refused to expand, brought " + app + " to the front");
                    return app;
                } catch (RuntimeException e) {
                    Log.w(Binders.TAG, "pip guard: could not bring " + app + " to the front", e);
                }
            }
        }
        return null;
    }

    // ---- a video leaving the screen after a fold ----

    /**
     * From ButtonService: the windows on the inner screen changed. While PiP's geometry is off and
     * a PiP-capable app is playing, notes whether it's in front, and when it stops being in front
     * (Home, Recents, another app) turns the screen, as it has most likely gone into PiP. Window
     * events alone don't say: expanding a PiP window by opening its app sends none.
     */
    static void onWindowsChanged() {
        if (service == null || !dirty || resetting != null || expanded != null || !Prefs.keepPip(service)) {
            playingInFront = null;
            return;
        }
        List<String> playing = Media.playingApps(service);
        playing.removeIf(app -> !supportsPip(service, app));
        if (playing.isEmpty()) {
            playingInFront = null;
            return;
        }
        String top = topApp();
        if (top == null || top.equals(service.getPackageName())) {
            return; // nothing in front yet, or our own screens
        }
        String left = playingInFront;
        playingInFront = playing.contains(top) ? top : null;
        if (left != null && !left.equals(top) && playing.contains(left)) {
            leftApp = left;
            main.removeCallbacks(afterLeaving);
            main.postDelayed(afterLeaving, LEFT_SETTLE_MS);
        }
    }

    /** A playing video left the screen with PiP's geometry off: turn the screen in case it's in PiP. */
    private static final Runnable afterLeaving = () -> {
        String app = leftApp;
        if (service == null || app == null || !dirty || resetting != null || expanded != null
                || CoverScreen.isPhoneClosed(service) || Lock.locked(service) || app.equals(topApp())) {
            return;
        }
        Log.d(Binders.TAG, "pip guard: " + app + " left the screen playing after a fold, turning the screen");
        startReset(app);
    };

    /** Whether an app has an activity that can go into PiP; also ButtonPause's test for a video playing. */
    static boolean supportsPip(Context context, String app) {
        Boolean known = pipApps.get(app);
        if (known == null) {
            known = false;
            try {
                PackageInfo info = context.getPackageManager().getPackageInfo(app, PackageManager.GET_ACTIVITIES);
                if (info.activities != null) {
                    for (ActivityInfo activity : info.activities) {
                        known |= (activity.flags & FLAG_SUPPORTS_PIP) != 0;
                    }
                }
            } catch (PackageManager.NameNotFoundException e) {
                // gone
            }
            pipApps.put(app, known);
        }
        return known;
    }

    // ---- not closed after all ----

    /** The hinge went back up without the phone closing: Home puts the expanded app back into PiP. */
    private static final Runnable restore = () -> {
        if (expanded == null || closed || service == null || CoverScreen.isPhoneClosed(service)) {
            return;
        }
        String app = expanded;
        expanded = null;
        if (app.equals(topApp()) && !Lock.locked(service)) {
            Log.d(Binders.TAG, "pip guard: not closed after all, putting " + app + " back into PiP");
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
        }
    };

    // ---- turning the screen ----

    private static void startReset(String app) {
        resetting = app;
        turned = false;
        try {
            service.startActivity(new Intent(service, PipResetActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION),
                    ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle());
        } catch (RuntimeException e) {
            Log.w(Binders.TAG, "pip guard: could not turn the screen", e);
        }
        main.postDelayed(resetTimeout, RESET_TIMEOUT_MS);
    }

    static void resetShown(PipResetActivity screen) {
        resetScreen = screen;
        if (resetting == null) {
            screen.close(); // opened after the reset was over
        } else {
            onResetRotation(); // it may have opened turned already
        }
    }

    static void resetGone(PipResetActivity screen) {
        if (resetScreen == screen) {
            resetScreen = null;
        }
    }

    /** Closes the black screen once the inner screen has turned, and goes on once it's back. */
    private static void onResetRotation() {
        Display inner = service.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        int rotation = inner == null ? Surface.ROTATION_0 : inner.getRotation();
        if (!turned && rotation != Surface.ROTATION_0 && resetScreen != null) {
            turned = true;
            resetScreen.close();
        } else if (turned && rotation == Surface.ROTATION_0) {
            finishReset("screen turned and back");
        }
    }

    private static final Runnable resetTimeout = () -> finishReset("screen didn't turn in time");

    private static void finishReset(String why) {
        String app = resetting;
        if (app == null) {
            return;
        }
        resetting = null;
        main.removeCallbacks(resetTimeout);
        if (resetScreen != null) {
            resetScreen.close();
        }
        Log.d(Binders.TAG, "pip guard: " + why + " (" + app + " left the screen)");
    }

    /** The app of the topmost application window on the inner screen, or null. */
    private static String topApp() {
        service.clearCache();
        for (AccessibilityWindowInfo window : service.getWindows()) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION || window.isInPictureInPictureMode()) {
                continue;
            }
            AccessibilityNodeInfo root = window.getRoot();
            CharSequence app = root == null ? null : root.getPackageName();
            return app == null ? null : app.toString();
        }
        return null;
    }
}
