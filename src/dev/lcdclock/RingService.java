package dev.lcdclock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
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
 * Rings the alarm sound and vibrates when the timer finishes or the alarm goes off, until
 * stopped, snoozed (alarm only) or it gives up: 2 minutes for the timer, 10 for the alarm.
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

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopRinging();
            return START_NOT_STICKY;
        }
        if (ACTION_SNOOZE.equals(action)) {
            AlarmController.snooze(this);
            finish();
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
            vibrator = getSystemService(VibratorManager.class).getDefaultVibrator();
            vibrator.vibrate(VibrationEffect.createWaveform(new long[] {0, 600, 400}, 0),
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM));
        }
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

    private void stopRinging() {
        TimerController.onRingingStopped(this);
        if (AlarmController.state(this) == AlarmController.State.RINGING) {
            AlarmController.stop(this); // not when a timer rings during a snooze
        }
        finish();
    }

    private void finish() {
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(timeout);
        if (ringtone != null) {
            ringtone.stop();
        }
        if (vibrator != null) {
            vibrator.cancel();
        }
        TimerController.onRingingStopped(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
