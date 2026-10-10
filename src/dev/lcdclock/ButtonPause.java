package dev.lcdclock;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps ButtonService, LCD Clock's accessibility service, switched off while the phone is open.
 * Banking apps (HSBC, CommBank) refuse to run, or flag the account, while an app that didn't come
 * from Google Play has an accessibility service on, and the service's work is for the closed
 * phone: the cover pages on wake and the volume holds. So this takes it out of Android's enabled
 * accessibility services when the phone opens and puts it back when it closes, through "write
 * secure settings" (granted over adb: QuickControls.canWriteSecure). MediaListener runs it, as
 * Android keeps notification access running throughout.
 *
 * <p>Closed is by the hinge, not the inner screen being off (CoverScreen.isPhoneClosed): locking
 * the open phone turns that screen off too, and a banking app left open (HSBC's ThreatMetrix)
 * notices the service coming back. The inner screen stands in only until the hinge has reported,
 * or on a phone without a hinge sensor. For the same reason, closing the phone while a banking app
 * is still running, even from Recents (BankApps), leaves the service off until the phone next opens.
 *
 * <p>The exception is PipGuard, which has to be watching as the phone closes: with "keep playing"
 * on, the service stays on while an app that can go into picture-in-picture is playing. Only a
 * service this switched off comes back (Prefs.BUTTON_PAUSED), so switching it off in Android's
 * settings keeps it off; switching it on there with the phone open leaves it on until the phone
 * next opens. ButtonTile and ButtonWidget show where it stands and switch it by hand (toggle).
 */
final class ButtonPause {
    /** After playback stops with the phone open, how long before the service goes off: pauses and skips are brief. */
    private static final long PLAYBACK_GRACE_MS = 5000;
    /**
     * Hinge angles: below CLOSED_DEG the phone is shut (Motorola commits "folded" at ~15°), above
     * OPEN_DEG open again; in between it stays as it was.
     */
    private static final float CLOSED_DEG = 15;
    private static final float OPEN_DEG = 30;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Context app;
    /** Whether the service was wanted on at the last check; null before the first. */
    private static Boolean wanted;
    private static boolean wasClosed;
    private static boolean pausePending;
    /** Shut, by the hinge; null before its first reading. */
    private static Boolean folded;
    /** The phone was shut with a banking app still running (BankApps): off until it next opens. */
    private static boolean closedWithBank;

    private ButtonPause() {
    }

    private static final SensorEventListener hinge = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            float angle = event.values[0];
            boolean now = angle < CLOSED_DEG || (angle <= OPEN_DEG
                    && (folded != null ? folded : angle < (CLOSED_DEG + OPEN_DEG) / 2));
            if (folded == null || folded != now) {
                folded = now;
                String bank = now ? BankApps.running(app, System.currentTimeMillis()) : null;
                closedWithBank = bank != null;
                Log.d(Binders.TAG, "cover button: hinge at " + Math.round(angle) + "°, " + (now ? "closed" : "open")
                        + (bank != null ? " with " + bank + " running, staying off" : ""));
                update();
            }
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
            if (displayId == Display.DEFAULT_DISPLAY) {
                update();
            }
        }
    };

    private static final Runnable mediaChanged = ButtonPause::update;

    /** Playback stopped a while ago with the phone open: off now, unless that's changed. */
    private static final Runnable pauseLater = () -> {
        pausePending = false;
        if (app != null && !wantOn(app)) {
            pause(app);
            wanted = false;
        }
    };

    /** From MediaListener once connected. */
    static void start(Context context) {
        if (app != null) {
            return;
        }
        app = context.getApplicationContext();
        wanted = null;
        folded = null;
        closedWithBank = false;
        // The inner screen, for until the hinge reports (and without one).
        app.getSystemService(DisplayManager.class).registerDisplayListener(innerScreen, main);
        SensorManager sensors = app.getSystemService(SensorManager.class);
        Sensor sensor = sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE, true); // closing while asleep wakes it
        if (sensor == null) {
            sensor = sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE);
        }
        if (sensor == null) {
            Log.w(Binders.TAG, "cover button: no hinge angle sensor, going by the inner screen");
        } else {
            // On-change: it reports once on registering, then only while the hinge moves.
            sensors.registerListener(hinge, sensor, SensorManager.SENSOR_DELAY_NORMAL, main);
        }
        Media.listen(mediaChanged);
        update();
    }

    /** From MediaListener on losing notification access: nothing will switch it back, so it goes back on. */
    static void stop() {
        if (app == null) {
            return;
        }
        app.getSystemService(DisplayManager.class).unregisterDisplayListener(innerScreen);
        app.getSystemService(SensorManager.class).unregisterListener(hinge);
        folded = null;
        Media.unlisten(mediaChanged);
        main.removeCallbacks(pauseLater);
        pausePending = false;
        resume(app);
        app = null;
    }

    /** Checks again, e.g. after "keep playing" changed; nothing while MediaListener isn't connected. */
    static void update() {
        if (app == null) {
            return;
        }
        boolean closed = closed(app);
        boolean want = wantOn(app);
        boolean opened = !closed && wasClosed;
        wasClosed = closed;
        if (want) {
            main.removeCallbacks(pauseLater);
            pausePending = false;
            if (!Boolean.TRUE.equals(wanted)) {
                resume(app);
            }
            wanted = true;
        } else if (wanted == null || opened) {
            // Just opened (or the first check): off before a banking app can be opened.
            main.removeCallbacks(pauseLater);
            pausePending = false;
            pause(app);
            wanted = false;
        } else if (wanted && !pausePending) {
            pausePending = true;
            main.postDelayed(pauseLater, PLAYBACK_GRACE_MS);
        }
    }

    /** Shut, by the hinge; by the inner screen being off until the hinge has reported. */
    private static boolean closed(Context context) {
        return folded != null ? folded : CoverScreen.isPhoneClosed(context);
    }

    /** Closed (with no banking app running), or PipGuard needs it. */
    private static boolean wantOn(Context context) {
        return (closed(context) && !closedWithBank) || pipPlaying(context);
    }

    /** An app that can go into picture-in-picture is playing, and PipGuard is to keep it going. */
    private static boolean pipPlaying(Context context) {
        if (!Prefs.keepPip(context)) {
            return false;
        }
        for (String playing : Media.playingApps(context)) {
            if (PipGuard.supportsPip(context, playing)) {
                return true;
            }
        }
        return false;
    }

    /** ButtonService is among Android's enabled accessibility services. */
    static boolean isOn(Context context) {
        return enabledServices(context).contains(component(context).flattenToString());
    }

    /** Switched off by this while the phone is open, to come back on when it closes. */
    static boolean paused(Context context) {
        return Prefs.get(context).getBoolean(Prefs.BUTTON_PAUSED, false);
    }

    /** Whether this can do its job: notification access to run it, and "write secure settings". */
    static boolean manages(Context context) {
        return Media.allowed(context) && QuickControls.canWriteSecure(context);
    }

    /** For MediaListener's dump (install.sh), ButtonTile and ButtonWidget: "on", "paused" or "off". */
    static String state(Context context) {
        return isOn(context) ? "on" : paused(context) ? "paused" : "off";
    }

    /**
     * ButtonTile's and ButtonWidget's tap. On: off now, as when the phone opens, so back on when
     * it closes (or a video starts playing). Paused or off: on now, until the phone next opens.
     * False if this can't switch it (manages).
     */
    static boolean toggle(Context context) {
        if (!manages(context)) {
            return false;
        }
        if (isOn(context)) {
            pause(context);
        } else if (write(context, true)) {
            Log.d(Binders.TAG, "cover button: switched on by hand");
        }
        return true;
    }

    private static void pause(Context context) {
        if (!isOn(context) || !QuickControls.canWriteSecure(context)) {
            return;
        }
        // Noted first, so it comes back even if the process dies straight after.
        Prefs.get(context).edit().putBoolean(Prefs.BUTTON_PAUSED, true).commit();
        if (write(context, false)) {
            Log.d(Binders.TAG, "cover button: switched off until the phone closes");
        }
    }

    private static void resume(Context context) {
        if (!paused(context)) {
            return;
        }
        if (isOn(context) || write(context, true)) {
            Prefs.get(context).edit().remove(Prefs.BUTTON_PAUSED).apply();
            Log.d(Binders.TAG, "cover button: switched back on");
        }
    }

    private static ComponentName component(Context context) {
        return new ComponentName(context, ButtonService.class);
    }

    /** The enabled accessibility services, each in its full flattened form. */
    private static List<String> enabledServices(Context context) {
        List<String> services = new ArrayList<>();
        String setting = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (setting != null) {
            for (String s : setting.split(":")) {
                ComponentName c = ComponentName.unflattenFromString(s);
                if (c != null) {
                    services.add(c.flattenToString());
                }
            }
        }
        return services;
    }

    /** Adds ButtonService to the enabled accessibility services, or takes it out; false if not allowed. */
    private static boolean write(Context context, boolean on) {
        String ours = component(context).flattenToString();
        List<String> services = enabledServices(context);
        services.remove(ours);
        if (on) {
            services.add(ours);
        }
        ContentResolver resolver = context.getContentResolver();
        try {
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    String.join(":", services));
            if (on) {
                Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            }
            return true;
        } catch (SecurityException e) {
            Log.w(Binders.TAG, "cover button: can't switch it without write secure settings", e);
            return false;
        }
    }
}
