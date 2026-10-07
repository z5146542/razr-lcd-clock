package dev.lcdclock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;

/**
 * SOS in Morse code, the quick controls' SOS tile (held for 3 seconds): ··· ——— ··· over and over
 * with the flashlight, a vibration and a loud beep on every mark, in standard timing (a dot 250 ms,
 * a dash three dots, a dot's gap inside a letter, three between letters, seven before it starts
 * again). The beep is a ~2.8 kHz tone driven nearly square, where hearing is most sensitive, at
 * full alarm volume (the alarm volume is put back afterwards). Without a flashlight (another app
 * has the camera) it carries on with sound and vibration. A foreground service that keeps the CPU
 * awake, so it carries on with the screen off and the phone locked, until SOS or LIGHT is tapped
 * again or its notification's Stop.
 */
public class SosService extends Service {
    static final String ACTION_START = "dev.lcdclock.action.SOS";
    static final String ACTION_STOP = "dev.lcdclock.action.STOP_SOS";
    /** With ACTION_STOP: leave the flashlight on afterwards (LIGHT was tapped). */
    static final String EXTRA_LIGHT = "light";
    private static final String CHANNEL = "sos";
    private static final int NOTIFICATION_ID = 0x5053;
    private static final long UNIT_MS = 250;
    /** On, off, on, off… in units: S (dots), O (dashes), S, then the gap before it repeats. */
    private static final int[] PATTERN = {1, 1, 1, 1, 1, 3, 3, 1, 3, 1, 3, 3, 1, 1, 1, 1, 1, 7};

    private static volatile boolean running;

    private HandlerThread thread;
    private Handler handler;
    private PowerManager.WakeLock wakeLock;
    private String camera;
    private int step;
    private Vibrator vibrator;
    /** The beep for a dot and for a dash, ready to replay. */
    private AudioTrack dotTone;
    private AudioTrack dashTone;
    /** The alarm volume before SOS turned it up, or -1. */
    private int savedAlarmVolume = -1;
    private static final int SAMPLE_RATE = 48000;
    private static final double TONE_HZ = 2800;
    private static final AudioAttributes ALARM_AUDIO = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();

    private final Runnable blink = new Runnable() {
        @Override
        public void run() {
            boolean on = step % 2 == 0;
            long duration = PATTERN[step] * UNIT_MS;
            torch(on); // without a flashlight, the sound and vibration carry on
            if (on) {
                mark(duration, PATTERN[step] == 1 ? dotTone : dashTone);
            }
            step = (step + 1) % PATTERN.length;
            handler.postDelayed(this, duration);
        }
    };

    /** A dot or a dash: vibrate and beep for its length (each mark starts afresh, so a screen-off can't stop them). */
    private void mark(long duration, AudioTrack tone) {
        vibrator.vibrate(VibrationEffect.createOneShot(duration, 255),
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM));
        if (tone != null) {
            try {
                tone.stop();
                tone.reloadStaticData();
                tone.play();
            } catch (IllegalStateException e) {
                Log.w(Binders.TAG, "sos: can't beep", e);
            }
        }
    }

    /** A beep `ms` long: a tone clipped nearly square (louder on a phone speaker), with 5 ms fades. */
    private static AudioTrack tone(long ms) {
        int n = (int) (SAMPLE_RATE * ms / 1000);
        int fade = SAMPLE_RATE / 200;
        short[] pcm = new short[n];
        for (int i = 0; i < n; i++) {
            double wave = Math.max(-1, Math.min(1, 3 * Math.sin(2 * Math.PI * TONE_HZ * i / SAMPLE_RATE)));
            double envelope = Math.min(1, Math.min(i, n - 1 - i) / (double) fade);
            pcm[i] = (short) (wave * envelope * 32000);
        }
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(ALARM_AUDIO)
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(n * 2)
                .build();
        track.write(pcm, 0, n);
        return track;
    }

    static boolean isRunning() {
        return running;
    }

    static void start(Context context) {
        context.startForegroundService(new Intent(context, SosService.class).setAction(ACTION_START));
    }

    /** Stops signalling; with `lightOn`, the flashlight stays on afterwards. */
    static void stop(Context context, boolean lightOn) {
        context.startService(new Intent(context, SosService.class).setAction(ACTION_STOP)
                .putExtra(EXTRA_LIGHT, lightOn));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(action) && !running) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            camera = QuickControls.torchCamera(this);
            if (camera == null) {
                Log.w(Binders.TAG, "sos: no flashlight, sound and vibration only");
            }
            vibrator = getSystemService(VibratorManager.class).getDefaultVibrator();
            try {
                dotTone = tone(UNIT_MS);
                dashTone = tone(3 * UNIT_MS);
            } catch (RuntimeException e) {
                Log.w(Binders.TAG, "sos: no sound", e);
            }
            AudioManager audio = getSystemService(AudioManager.class);
            try {
                savedAlarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM);
                audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
            } catch (SecurityException e) {
                savedAlarmVolume = -1;
                Log.w(Binders.TAG, "sos: can't turn the alarm volume up", e);
            }
            wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LcdClock:sos");
            wakeLock.acquire();
            thread = new HandlerThread("sos");
            thread.start();
            handler = new Handler(thread.getLooper());
            running = true;
            step = 0;
            handler.post(blink);
            Log.d(Binders.TAG, "sos: on");
        } else if (ACTION_STOP.equals(action)) {
            finish();
            if (intent.getBooleanExtra(EXTRA_LIGHT, false)) {
                torch(true);
            }
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        } else if (!running) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    /** Turns the flashlight on or off; false if it can't (another app has the camera). */
    private boolean torch(boolean on) {
        if (camera == null) {
            return false;
        }
        try {
            getSystemService(CameraManager.class).setTorchMode(camera, on);
            return true;
        } catch (CameraAccessException | IllegalArgumentException e) {
            Log.w(Binders.TAG, "sos: can't switch the flashlight", e);
            return false;
        }
    }

    private void finish() {
        if (!running) {
            return;
        }
        running = false;
        handler.removeCallbacksAndMessages(null);
        thread.quitSafely();
        torch(false);
        vibrator.cancel();
        for (AudioTrack tone : new AudioTrack[] {dotTone, dashTone}) {
            if (tone != null) {
                tone.release();
            }
        }
        dotTone = null;
        dashTone = null;
        if (savedAlarmVolume >= 0) {
            try {
                getSystemService(AudioManager.class).setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0);
            } catch (SecurityException e) {
                Log.w(Binders.TAG, "sos: can't put the alarm volume back", e);
            }
            savedAlarmVolume = -1;
        }
        if (wakeLock.isHeld()) {
            wakeLock.release();
        }
        Log.d(Binders.TAG, "sos: off");
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.sos_channel),
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, SosService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(getString(R.string.sos_on))
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.stop), stop).build())
                .build();
    }

    @Override
    public void onDestroy() {
        finish();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
