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
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;

/**
 * Rings the sound chosen in the app (the phone's alarm sound at first, or none), and vibrates if
 * that's switched on, for whatever is ringing (the alarm's choice when both ring),
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
    /** Swaps a sound that won't play for the phone's alarm sound; see updateSound. */
    private final Runnable fallback = this::fallBack;
    private Ringtone ringtone;
    /** The sound playing, or null for silence. */
    private Uri playing;
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
        updateSound();
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

    /** Plays the sound chosen for what's ringing (the alarm's when both are), switching if it changed. */
    private void updateSound() {
        boolean alarm = AlarmController.state(this) == AlarmController.State.RINGING;
        boolean timer = TimerController.state(this) == TimerController.State.RINGING;
        Uri want = alarm || timer ? Prefs.sound(this, alarm ? KIND_ALARM : KIND_TIMER) : null;
        if (java.util.Objects.equals(want, playing) && (want == null || ringtone != null)) {
            return;
        }
        main.removeCallbacks(fallback);
        if (ringtone != null) {
            ringtone.stop();
            ringtone = null;
        }
        playing = want;
        if (want != null) {
            play(want);
            main.postDelayed(fallback, 1000);
        }
    }

    /** A sound that won't play (a deleted file, say): the phone's alarm sound instead. */
    private void fallBack() {
        if (ringtone != null && !ringtone.isPlaying() && playing != null
                && !playing.equals(Settings.System.DEFAULT_ALARM_ALERT_URI)) {
            ringtone.stop();
            play(Settings.System.DEFAULT_ALARM_ALERT_URI);
        }
    }

    private void play(Uri uri) {
        ringtone = RingtoneManager.getRingtone(this, uri);
        if (ringtone == null) {
            return;
        }
        ringtone.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        ringtone.setLooping(true);
        ringtone.play();
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
        updateSound();
        updateVibration();
    }

    private void finish() {
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        // Stopped within a second of ringing, the fallback check would otherwise find the sound
        // stopped and start the alarm sound again, with nothing left to stop it.
        main.removeCallbacksAndMessages(null);
        unregisterReceiver(screenOff);
        if (ringtone != null) {
            ringtone.stop();
            ringtone = null;
        }
        playing = null;
        vibrator.cancel();
        TimerController.onRingingStopped(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
