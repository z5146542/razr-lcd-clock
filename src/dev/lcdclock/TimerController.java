package dev.lcdclock;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * The cover-screen timer, LCD Clock's own (it doesn't depend on any other app): state in Prefs so
 * it keeps running when the screen closes, and an exact alarm fires TimerReceiver when it reaches
 * zero, ringing through RingService.
 */
final class TimerController {
    enum State { IDLE, RUNNING, PAUSED, RINGING }

    private static final String STATE = "timer_state";
    private static final String END_AT = "timer_end_at";
    private static final String REMAINING = "timer_remaining";
    private static final String DURATION = "timer_duration";
    private static final long DEFAULT_DURATION = 5 * 60_000;

    private TimerController() {
    }

    static State state(Context context) {
        return State.valueOf(Prefs.get(context).getString(STATE, State.IDLE.name()));
    }

    /** The last duration set, shown when the timer is idle. */
    static long duration(Context context) {
        return Prefs.get(context).getLong(DURATION, DEFAULT_DURATION);
    }

    static void setDuration(Context context, long millis) {
        Prefs.get(context).edit().putLong(DURATION, millis).apply();
    }

    /** Time left, in milliseconds, for the current state. */
    static long remaining(Context context) {
        SharedPreferences prefs = Prefs.get(context);
        switch (state(context)) {
            case RUNNING:
                return Math.max(0, prefs.getLong(END_AT, 0) - System.currentTimeMillis());
            case PAUSED:
                return prefs.getLong(REMAINING, 0);
            case RINGING:
                return 0;
            default:
                return duration(context);
        }
    }

    static void start(Context context) {
        long millis = state(context) == State.PAUSED ? remaining(context) : duration(context);
        if (millis <= 0) {
            return;
        }
        long endAt = System.currentTimeMillis() + millis;
        Prefs.get(context).edit().putString(STATE, State.RUNNING.name()).putLong(END_AT, endAt).apply();
        context.getSystemService(AlarmManager.class)
                .setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAt, fireIntent(context));
    }

    static void pause(Context context) {
        long left = remaining(context);
        cancelAlarm(context);
        Prefs.get(context).edit().putString(STATE, State.PAUSED.name()).putLong(REMAINING, left).apply();
    }

    static void reset(Context context) {
        cancelAlarm(context);
        Prefs.get(context).edit().putString(STATE, State.IDLE.name()).apply();
    }

    /** Stops a ringing timer, through RingService. */
    static void stopRinging(Context context) {
        context.startService(new Intent(context, RingService.class).setAction(RingService.ACTION_STOP)
                .putExtra(RingService.EXTRA_KIND, RingService.KIND_TIMER));
    }

    /** Called by TimerReceiver when the alarm fires. */
    static void onFinished(Context context) {
        Prefs.get(context).edit().putString(STATE, State.RINGING.name()).apply();
    }

    /** Called when ringing stops, from the cover screen or the notification. */
    static void onRingingStopped(Context context) {
        if (state(context) == State.RINGING) {
            Prefs.get(context).edit().putString(STATE, State.IDLE.name()).apply();
        }
    }

    /** After a reboot or app update, which drop scheduled alarms; an overdue timer rings at once. */
    static void reschedule(Context context) {
        State state = state(context);
        if (state == State.RUNNING) {
            long endAt = Math.max(Prefs.get(context).getLong(END_AT, 0), System.currentTimeMillis() + 1000);
            context.getSystemService(AlarmManager.class)
                    .setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAt, fireIntent(context));
        } else if (state == State.RINGING) {
            Prefs.get(context).edit().putString(STATE, State.IDLE.name()).apply();
        }
    }

    private static void cancelAlarm(Context context) {
        context.getSystemService(AlarmManager.class).cancel(fireIntent(context));
    }

    private static PendingIntent fireIntent(Context context) {
        return PendingIntent.getBroadcast(context, 0, new Intent(context, TimerReceiver.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
