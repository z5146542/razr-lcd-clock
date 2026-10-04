package dev.lcdclock.fake;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

/** Silent media session for testing LCD Clock's MUSIC page. */
public class FakePlayer extends Service {
    static final String TAG = "FakePlayer";
    static final String[][] TRACKS = {
        {"Test Artist", "A Very Long Song Title That Has To Scroll Across The Cover Screen"},
        {"테스트 가수", "짧은 노래"},
    };
    /** The second build (dev.lcdclock.fake2) plays these instead. */
    static final String[][] TRACKS2 = {
        {"Second Player", "Another Song"},
        {"Second Player", "Track Two"},
    };
    String[][] tracks() { return getPackageName().endsWith("2") ? TRACKS2 : TRACKS; }
    MediaSession session;
    int track;
    boolean playing;
    long position;
    long since;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (session == null) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("fake", "Fake player", NotificationManager.IMPORTANCE_LOW));
            session = new MediaSession(this, "FakePlayer");
            session.setCallback(new MediaSession.Callback() {
                @Override public void onPlay() { Log.d(TAG, "onPlay"); setPlaying(true); }
                @Override public void onPause() { Log.d(TAG, "onPause"); setPlaying(false); }
                @Override public void onSeekTo(long pos) { Log.d(TAG, "onSeekTo " + pos + " from " + now()); position = pos; since = SystemClock.elapsedRealtime(); publish(); }
                @Override public void onSkipToNext() { Log.d(TAG, "onSkipToNext"); setTrack(track + 1); }
                @Override public void onSkipToPrevious() { Log.d(TAG, "onSkipToPrevious"); setTrack(track - 1); }
            });
            session.setActive(true);
            setTrack(0);
            Notification n = new Notification.Builder(this, "fake").setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle("Fake player").setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken())).build();
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        }
        String action = intent == null ? null : intent.getAction();
        if ("play".equals(action)) setPlaying(true);
        if ("pause".equals(action)) setPlaying(false);
        if ("noart".equals(action)) { track = 1; metadata(false); }
        if ("notice".equals(action)) notice();
        if ("unnotice".equals(action)) getSystemService(NotificationManager.class).cancel(2);
        return START_NOT_STICKY;
    }

    /** A silent, long conversation, public on the lock screen, for the notifications panel's own page. */
    void notice() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("notice", "Test notices", NotificationManager.IMPORTANCE_LOW));
        android.app.Person me = new android.app.Person.Builder().setName("Me").build();
        android.app.Person alex = new android.app.Person.Builder().setName("Alex").build();
        long t = System.currentTimeMillis() - 240_000; // the last message just now: the panel's first row
        Notification.MessagingStyle style = new Notification.MessagingStyle(me).setConversationTitle("LCD Clock test")
                .addMessage("Is the outside screen showing whole notifications now?", t, alex)
                .addMessage("Tap one in the panel and it opens on its own page, with every message wrapped onto as many lines as it needs.", t + 60_000, (android.app.Person) null)
                .addMessage("And a long one scrolls when you drag it up, like the list does?", t + 120_000, alex)
                .addMessage("Yes. BACK or a drag down from the top goes back to the list, and the cross dismisses it.", t + 180_000, (android.app.Person) null)
                .addMessage("Great, this is the last message, so it should be at the bottom of the page.", t + 240_000, alex);
        nm.notify(2, new Notification.Builder(this, "notice").setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle("LCD Clock test").setContentText("Great, this is the last message, so it should be at the bottom of the page.")
                .setStyle(style).setVisibility(Notification.VISIBILITY_PUBLIC).setWhen(t + 240_000).build());
    }

    long now() { return position + (playing ? SystemClock.elapsedRealtime() - since : 0); }

    void setPlaying(boolean p) { position = now(); since = SystemClock.elapsedRealtime(); playing = p; publish(); }

    void setTrack(int t) { track = Math.floorMod(t, tracks().length); position = 0; since = SystemClock.elapsedRealtime(); metadata(track == 0); publish(); }

    void metadata(boolean withArt) {
        MediaMetadata.Builder b = new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_ARTIST, tracks()[track][0])
            .putString(MediaMetadata.METADATA_KEY_TITLE, tracks()[track][1])
            .putLong(MediaMetadata.METADATA_KEY_DURATION, track == 0 ? 225_000 : 4_000_000);
        if (withArt) {
            Bitmap art = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(art);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new LinearGradient(0, 0, 300, 300, Color.rgb(255, 120, 30), Color.rgb(60, 40, 200), Shader.TileMode.CLAMP));
            c.drawRect(0, 0, 300, 300, p);
            p.setShader(null); p.setColor(Color.WHITE); p.setTextSize(90); p.setFakeBoldText(true);
            c.drawText("ART", 60, 185, p);
            b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        }
        session.setMetadata(b.build());
    }

    void publish() {
        session.setPlaybackState(new PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, now(), playing ? 1f : 0f, SystemClock.elapsedRealtime())
            .build());
    }

    @Override public void onDestroy() { if (session != null) session.release(); super.onDestroy(); }
}
