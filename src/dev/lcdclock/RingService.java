package dev.lcdclock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;

/**
 * Rings the alarm sound, and vibrates if that's switched on in the app for whatever is ringing,
 * when the timer finishes or the alarm goes off, until stopped, snoozed (alarm only) or it gives
 * up: 2 minutes for the timer, 10 for the alarm. Stopping one of the two leaves the other ringing.
 *
 * <p>Android cancels an app's vibration whenever the screen turns off, even an alarm's, so it's
 * started again each time that happens.
 */
public class RingService extends Service {
    static final String ACTION_STOP = "dev.lcdclock.action.STOP_RINGING";
    static final String ACTION_SNOOZE = "dev.lcdclock.action.SNOOZE";
    static final String EXTRA_KIND = "kind";
    static final String KIND_TIMER = "timer";
    static final String KIND_ALARM = "alarm";
    private static final String CHANNEL = "timer_done";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable timeout = this::stopRinging;
    private Ringtone ringtone;
    private Vibrator vibrator;
    private String kind = KIND_TIMER;
    private static final long[] PATTERN = {0, 600, 400};

    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            main.postDelayed(RingService.this::updateVibration, 300);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        vibrator = getSystemService(VibratorManager.class).getDefaultVibrator();
        registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stop(intent.getStringExtra(EXTRA_KIND)); // no kind: stop everything (the notification)
            return START_NOT_STICKY;
        }
        if (ACTION_SNOOZE.equals(action)) {
            AlarmController.snooze(this);
            afterOneStopped();
            return START_NOT_STICKY;
        }
        if (intent != null && intent.getStringExtra(EXTRA_KIND) != null) {
            kind = intent.getStringExtra(EXTRA_KIND);
        }
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        if (ringtone == null) {
            // The settings URI plays the user's alarm sound without needing media access.
            ringtone = RingtoneManager.getRingtone(this, Settings.System.DEFAULT_ALARM_ALERT_URI);
            if (ringtone != null) {
                ringtone.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
                ringtone.setLooping(true);
                ringtone.play();
            }
        }
        updateVibration();
        main.removeCallbacks(timeout);
        main.postDelayed(timeout, KIND_ALARM.equals(kind) ? 10 * 60_000 : 2 * 60_000);
        return START_NOT_STICKY;
    }

    private Notification notification() {
        NotificationManager notifications = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, getString(R.string.timer_done_channel),
                NotificationManager.IMPORTANCE_HIGH);
        channel.setSound(null, null); // the service plays the sound itself
        notifications.createNotificationChannel(channel);
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, RingService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        boolean alarm = KIND_ALARM.equals(kind);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(getString(alarm ? R.string.alarm_ringing : R.string.timer_done))
                .setCategory(Notification.CATEGORY_ALARM)
                .setOngoing(true)
                .setContentIntent(stop);
        if (alarm) {
            PendingIntent snooze = PendingIntent.getService(this, 1,
                    new Intent(this, RingService.class).setAction(ACTION_SNOOZE), PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(null, getString(R.string.snooze), snooze).build());
        }
        return builder.addAction(new Notification.Action.Builder(null, getString(R.string.stop), stop).build()).build();
    }

    /** Vibrates while anything ringing has vibration switched on; (re)starts the pattern. */
    private void updateVibration() {
        boolean timer = TimerController.state(this) == TimerController.State.RINGING && Prefs.vibrate(this, KIND_TIMER);
        boolean alarm = AlarmController.state(this) == AlarmController.State.RINGING && Prefs.vibrate(this, KIND_ALARM);
        if (timer || alarm) {
            vibrator.vibrate(VibrationEffect.createWaveform(PATTERN, 0),
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM));
        } else {
            vibrator.cancel();
        }
    }

    /** Gives up after the time limit: stops both. */
    private void stopRinging() {
        stop(null);
    }

    /** Stops the timer, the alarm (back to on for tomorrow), or with null both. */
    private void stop(String which) {
        if (which == null || KIND_TIMER.equals(which)) {
            TimerController.onRingingStopped(this);
        }
        if ((which == null || KIND_ALARM.equals(which))
                && AlarmController.state(this) == AlarmController.State.RINGING) {
            AlarmController.stop(this); // not when a timer rings during a snooze
        }
        afterOneStopped();
    }

    /** Keeps ringing for whatever still rings, or finishes. */
    private void afterOneStopped() {
        boolean timer = TimerController.state(this) == TimerController.State.RINGING;
        boolean alarm = AlarmController.state(this) == AlarmController.State.RINGING;
        if (!timer && !alarm) {
            finish();
            return;
        }
        kind = alarm ? KIND_ALARM : KIND_TIMER;
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        updateVibration();
    }

    private void finish() {
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(timeout);
        unregisterReceiver(screenOff);
        if (ringtone != null) {
            ringtone.stop();
        }
        vibrator.cancel();
        TimerController.onRingingStopped(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
