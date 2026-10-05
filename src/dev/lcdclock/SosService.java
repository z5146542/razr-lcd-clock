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
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

/**
 * SOS in Morse code with the flashlight, the quick controls' SOS tile: ··· ——— ··· over and over,
 * in standard timing (a dot 250 ms, a dash three dots, a dot's gap inside a letter, three between
 * letters, seven before it starts again). A foreground service that keeps the CPU awake, so it
 * carries on with the screen off and the phone locked, until SOS or LIGHT is tapped again or its
 * notification's Stop.
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

    private final Runnable blink = new Runnable() {
        @Override
        public void run() {
            if (!torch(step % 2 == 0)) {
                stopSelf();
                return;
            }
            long delay = PATTERN[step] * UNIT_MS;
            step = (step + 1) % PATTERN.length;
            handler.postDelayed(this, delay);
        }
    };

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
                Log.w(Binders.TAG, "sos: no flashlight");
                stopSelf();
                return START_NOT_STICKY;
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
