package dev.lcdclock;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.util.Calendar;

/**
 * LCD Clock's own alarm: one time of day, rung every day while on. Scheduled with
 * setAlarmClock so Android treats it as a real alarm (status-bar icon, the face's bell).
 */
final class AlarmController {
    enum State { OFF, ON, RINGING, SNOOZED }

    static final long SNOOZE_MS = 9 * 60_000;
    private static final String STATE = "alarm_state";
    private static final String HOUR = "alarm_hour";
    private static final String MINUTE = "alarm_minute";
    private static final String SNOOZE_UNTIL = "alarm_snooze_until";
    private static final int REQUEST_DAILY = 1;
    private static final int REQUEST_SNOOZE = 2;

    private AlarmController() {
    }

    static State state(Context context) {
        return State.valueOf(Prefs.get(context).getString(STATE, State.OFF.name()));
    }

    static boolean isOn(Context context) {
        return state(context) != State.OFF;
    }

    static int hour(Context context) {
        return Prefs.get(context).getInt(HOUR, 7);
    }

    static int minute(Context context) {
        return Prefs.get(context).getInt(MINUTE, 0);
    }

    /** Changes the time; if the alarm is on, it moves straight away. */
    static void setTime(Context context, int hour, int minute) {
        Prefs.get(context).edit().putInt(HOUR, hour).putInt(MINUTE, minute).apply();
        if (state(context) == State.ON) {
            scheduleDaily(context);
        }
    }

    static void turnOn(Context context) {
        if (state(context) == State.OFF) {
            setState(context, State.ON);
        }
        scheduleDaily(context);
    }

    static void turnOff(Context context) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        alarms.cancel(fireIntent(context, REQUEST_DAILY));
        alarms.cancel(fireIntent(context, REQUEST_SNOOZE));
        boolean ringing = state(context) == State.RINGING;
        setState(context, State.OFF);
        if (ringing) {
            context.startService(new Intent(context, RingService.class).setAction(RingService.ACTION_STOP)
                    .putExtra(RingService.EXTRA_KIND, RingService.KIND_ALARM));
        }
    }

    /** The alarm (or a snooze) went off: ring, and arm tomorrow's. */
    static void onFired(Context context) {
        setState(context, State.RINGING);
        scheduleDaily(context);
    }

    static void snooze(Context context) {
        if (state(context) != State.RINGING) {
            return;
        }
        long until = System.currentTimeMillis() + SNOOZE_MS;
        Prefs.get(context).edit().putString(STATE, State.SNOOZED.name()).putLong(SNOOZE_UNTIL, until).apply();
        setAlarmClock(context, until, REQUEST_SNOOZE);
    }

    /** STOP: ringing or snoozed goes back to plain "on" for tomorrow. */
    static void stop(Context context) {
        State state = state(context);
        if (state == State.RINGING || state == State.SNOOZED) {
            context.getSystemService(AlarmManager.class).cancel(fireIntent(context, REQUEST_SNOOZE));
            setState(context, State.ON);
            scheduleDaily(context);
        }
    }

    /** After a reboot, time change or app update, which all drop or skew scheduled alarms. */
    static void reschedule(Context context) {
        switch (state(context)) {
            case ON:
                scheduleDaily(context);
                break;
            case SNOOZED:
                long until = Prefs.get(context).getLong(SNOOZE_UNTIL, 0);
                if (until > System.currentTimeMillis()) {
                    setAlarmClock(context, until, REQUEST_SNOOZE);
                    scheduleDaily(context);
                } else {
                    stop(context);
                }
                break;
            case RINGING:
                stop(context);
                break;
            default:
                break;
        }
    }

    /** The next time the alarm's hour:minute comes round. */
    static long nextTrigger(Context context) {
        Calendar at = Calendar.getInstance();
        at.set(Calendar.HOUR_OF_DAY, hour(context));
        at.set(Calendar.MINUTE, minute(context));
        at.set(Calendar.SECOND, 0);
        at.set(Calendar.MILLISECOND, 0);
        if (at.getTimeInMillis() <= System.currentTimeMillis()) {
            at.add(Calendar.DAY_OF_YEAR, 1);
        }
        return at.getTimeInMillis();
    }

    private static void scheduleDaily(Context context) {
        setAlarmClock(context, nextTrigger(context), REQUEST_DAILY);
    }

    private static void setAlarmClock(Context context, long at, int request) {
        PendingIntent show = PendingIntent.getActivity(context, 0, new Intent(context, PreviewActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        context.getSystemService(AlarmManager.class)
                .setAlarmClock(new AlarmManager.AlarmClockInfo(at, show), fireIntent(context, request));
    }

    private static void setState(Context context, State state) {
        Prefs.get(context).edit().putString(STATE, state.name()).apply();
    }

    private static PendingIntent fireIntent(Context context, int request) {
        return PendingIntent.getBroadcast(context, request, new Intent(context, AlarmReceiver.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
