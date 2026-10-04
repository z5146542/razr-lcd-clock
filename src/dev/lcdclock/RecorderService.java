package dev.lcdclock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The RECORDER page's recording, LCD Clock's own: AAC in .m4a, saved to Recordings through
 * MediaStore so other apps see it. A microphone foreground service, so it carries on while the outside screen
 * is off; its notification has Stop. Started from the cover page while it's showing, as Android
 * requires for the microphone.
 */
public class RecorderService extends Service {
    static final String ACTION_START = "dev.lcdclock.action.RECORD";
    static final String ACTION_STOP = "dev.lcdclock.action.STOP_RECORDING";
    private static final String CHANNEL = "recording";
    private static final int NOTIFICATION_ID = 0x4C44;

    private static MediaRecorder recorder;
    private static ParcelFileDescriptor output;
    private static Uri recording;
    private static long startedAt;

    static boolean isRecording() {
        return recorder != null;
    }

    /** How long the current recording has run, in ms. */
    static long elapsed() {
        return recorder == null ? 0 : SystemClock.elapsedRealtime() - startedAt;
    }

    /** Loudest input since the last call, 0..32767. */
    static int amplitude() {
        try {
            return recorder == null ? 0 : recorder.getMaxAmplitude();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    static void start(Context context) {
        context.startForegroundService(new Intent(context, RecorderService.class).setAction(ACTION_START));
    }

    static void stop(Context context) {
        context.startService(new Intent(context, RecorderService.class).setAction(ACTION_STOP));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_START.equals(intent.getAction()) && recorder == null) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            if (!begin()) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        } else if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            finishRecording();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        } else if (recorder == null) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private boolean begin() {
        ContentResolver resolver = getContentResolver();
        String name = "LCD Clock " + new SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.ROOT).format(new Date());
        ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Media.DISPLAY_NAME, name + ".m4a");
        values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
        values.put(MediaStore.Audio.Media.RELATIVE_PATH, Recordings.FOLDER);
        values.put(MediaStore.Audio.Media.IS_PENDING, 1);
        try {
            recording = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
            if (recording == null) {
                return false;
            }
            output = resolver.openFileDescriptor(recording, "w");
            MediaRecorder r = new MediaRecorder(this);
            r.setAudioSource(MediaRecorder.AudioSource.MIC);
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            r.setAudioEncodingBitRate(128_000);
            r.setAudioSamplingRate(44_100);
            r.setOutputFile(output.getFileDescriptor());
            r.prepare();
            r.start();
            recorder = r;
            startedAt = SystemClock.elapsedRealtime();
            Log.d(Binders.TAG, "recording to " + recording);
            return true;
        } catch (IOException | RuntimeException e) {
            Log.w(Binders.TAG, "can't record", e);
            discard();
            return false;
        }
    }

    /** Stops and keeps the file, as the page's last recording. */
    private void finishRecording() {
        if (recorder == null) {
            return;
        }
        long length = elapsed();
        try {
            recorder.stop();
        } catch (RuntimeException e) {
            // Stopped straight after starting: nothing was recorded.
            recorder.release();
            recorder = null;
            discard();
            return;
        }
        recorder.release();
        recorder = null;
        closeOutput();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Media.IS_PENDING, 0);
        getContentResolver().update(recording, values, null, null);
        Prefs.get(this).edit()
                .putString(Prefs.LAST_RECORDING, recording.toString())
                .putLong(Prefs.LAST_RECORDING_MS, length)
                .apply();
        Log.d(Binders.TAG, "recorded " + length + " ms to " + recording);
        recording = null;
    }

    private void discard() {
        closeOutput();
        if (recording != null) {
            getContentResolver().delete(recording, null, null);
            recording = null;
        }
    }

    private static void closeOutput() {
        if (output != null) {
            try {
                output.close();
            } catch (IOException ignored) {
                // nothing more to do with it
            }
            output = null;
        }
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.recording_channel),
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, RecorderService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle(getString(R.string.recording))
                .setUsesChronometer(true)
                .setWhen(System.currentTimeMillis())
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.stop), stop).build())
                .build();
    }

    @Override
    public void onDestroy() {
        finishRecording();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
