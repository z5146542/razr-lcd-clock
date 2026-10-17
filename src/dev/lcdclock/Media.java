package dev.lcdclock;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The music the MUSIC page shows and controls: Android's active media sessions, which LCD
 * Clock may read once it has notification access (MediaListener). While MediaListener is
 * connected this also opens the playing app's MUSIC page on the cover screen when playback
 * starts with the phone closed (over our own clock face page too, not over other pages), and
 * tells CoverHome which page to wake to while something plays, unless leaving MUSIC for the face
 * by its tab dismissed it for this playback.
 */
final class Media {
    /** A pause shorter than this (e.g. between tracks) doesn't count as playback starting again. */
    private static final long RESTART_GAP_MS = 3000;
    /** How long a seek counts before the player reports it, so quick taps add up. */
    private static final long SEEK_PENDING_MS = 1500;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final List<Runnable> listeners = new ArrayList<>();
    private static final List<MediaController> sessions = new ArrayList<>();
    private static Context app;
    private static boolean watching;
    private static boolean active;
    private static long lastActiveAt = -RESTART_GAP_MS;
    private static boolean dismissed;

    /** App names by package, for the tab order. */
    private static final Map<String, String> appNames = new HashMap<>();

    private static MediaSession.Token seekToken;
    private static long seekTarget;
    private static long seekAt;

    private Media() {
    }

    private static final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            changed();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            changed();
        }

        @Override
        public void onSessionDestroyed() {
            changed();
        }
    };

    private static final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = list -> {
        watch(list);
        changed();
    };

    // ---- watching (while MediaListener is connected) ----

    static void start(Context context) {
        if (watching) {
            return;
        }
        app = context.getApplicationContext();
        MediaSessionManager manager = app.getSystemService(MediaSessionManager.class);
        try {
            manager.addOnActiveSessionsChangedListener(sessionsChanged, component(app), main);
            watch(manager.getActiveSessions(component(app)));
        } catch (SecurityException e) {
            Log.w(Binders.TAG, "no notification access for media sessions", e);
            return;
        }
        watching = true;
        // Already playing when we connect (e.g. after an update) isn't a start.
        active = anyActive();
        notifyListeners();
    }

    static void stop() {
        if (!watching) {
            return;
        }
        watching = false;
        app.getSystemService(MediaSessionManager.class).removeOnActiveSessionsChangedListener(sessionsChanged);
        watch(null);
        notifyListeners();
    }

    private static void watch(List<MediaController> list) {
        for (MediaController c : sessions) {
            c.unregisterCallback(callback);
        }
        sessions.clear();
        if (list != null) {
            sessions.addAll(list);
        }
        for (MediaController c : sessions) {
            c.registerCallback(callback, main);
        }
    }

    private static void changed() {
        boolean now = anyActive();
        long t = SystemClock.elapsedRealtime();
        boolean started = now && !active && t - lastActiveAt > RESTART_GAP_MS;
        if (active || now) {
            lastActiveAt = t;
        }
        active = now;
        if (started) {
            dismissed = false;
            if (CoverScreen.isCoverAwake(app)) {
                open("playback started", firstActiveKey());
            }
        }
        notifyListeners();
    }

    /** The player whose MUSIC page the cover should wake to, or null: none playing, or dismissed. */
    static String wakeKey() {
        return watching && active && !dismissed ? firstActiveKey() : null;
    }

    /** Opens the MUSIC tab of the player with this key. */
    private static void open(String why, String key) {
        if (CoverActivity.isShowing() && !CoverActivity.isOnFace() || CoverActivity.ringing(app)
                || !Prefs.tabShown(app, CoverActivity.PAGE_MUSIC)) {
            return;
        }
        Log.d(Binders.TAG, why + ": opening MUSIC for " + key);
        CoverScreen.open(app, CoverActivity.musicPage(key));
    }

    private static String firstActiveKey() {
        for (MediaController c : sessions) {
            if (isActive(c.getPlaybackState())) {
                return key(c);
            }
        }
        return null;
    }

    private static boolean anyActive() {
        for (MediaController c : sessions) {
            if (isActive(c.getPlaybackState())) {
                return true;
            }
        }
        return false;
    }

    /** Left MUSIC for the face by its tab: don't open it by itself again until playback next starts. */
    static void dismiss() {
        dismissed = true;
    }

    /** Called on the main thread whenever the sessions, a track or a playback state changes. */
    static void listen(Runnable listener) {
        listeners.add(listener);
    }

    static void unlisten(Runnable listener) {
        listeners.remove(listener);
    }

    private static void notifyListeners() {
        for (Runnable listener : new ArrayList<>(listeners)) {
            listener.run();
        }
    }

    // ---- reading ----

    static ComponentName component(Context context) {
        return new ComponentName(context, MediaListener.class);
    }

    static boolean allowed(Context context) {
        return context.getSystemService(NotificationManager.class).isNotificationListenerAccessGranted(component(context));
    }

    /** An active session, which gets a MUSIC page and tab on the outside screen. */
    static final class Player {
        /** Names the session across updates; see key(MediaController). */
        final String key;
        final String appName;

        Player(String key, String appName) {
            this.key = key;
            this.appName = appName;
        }
    }

    static String key(MediaController controller) {
        return controller.getPackageName() + "/" + controller.getTag();
    }

    /** The active sessions in a steady order (by app name) so tabs don't jump around. */
    static List<Player> players(Context context) {
        List<Player> players = new ArrayList<>();
        for (MediaController c : watching ? sessions : query(context)) {
            players.add(new Player(key(c), appName(context, c.getPackageName())));
        }
        players.sort(Comparator.comparing((Player p) -> p.appName.toLowerCase(Locale.ROOT)).thenComparing(p -> p.key));
        return players;
    }

    /** The session with this key, or null once it's gone. */
    static MediaController session(Context context, String key) {
        for (MediaController c : watching ? sessions : query(context)) {
            if (key(c).equals(key)) {
                return c;
            }
        }
        return null;
    }

    private static String appName(Context context, String packageName) {
        String name = appNames.get(packageName);
        if (name == null) {
            PackageManager pm = context.getPackageManager();
            try {
                name = pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString();
            } catch (PackageManager.NameNotFoundException e) {
                name = packageName;
            }
            appNames.put(packageName, name);
        }
        return name;
    }

    /** For the plain MUSIC tab: the first session playing, else the most recent; null if none (or no access). */
    static MediaController current(Context context) {
        List<MediaController> list = watching ? sessions : query(context);
        for (MediaController c : list) {
            if (isActive(c.getPlaybackState())) {
                return c;
            }
        }
        return list.isEmpty() ? null : list.get(0);
    }

    /** The apps with a session playing (or about to); none without notification access. */
    static List<String> playingApps(Context context) {
        List<String> apps = new ArrayList<>();
        for (MediaController c : watching ? sessions : query(context)) {
            if (isActive(c.getPlaybackState()) && !apps.contains(c.getPackageName())) {
                apps.add(c.getPackageName());
            }
        }
        return apps;
    }

    private static List<MediaController> query(Context context) {
        if (!allowed(context)) {
            return Collections.emptyList();
        }
        try {
            return context.getSystemService(MediaSessionManager.class).getActiveSessions(component(context));
        } catch (SecurityException e) {
            return Collections.emptyList();
        }
    }

    /** Playing, or about to (buffering, skipping, seeking). */
    static boolean isActive(PlaybackState state) {
        return state != null && state.isActive();
    }

    /** "Artist - Title", or whichever of them the player gives. */
    static String title(MediaMetadata metadata) {
        if (metadata == null) {
            return "";
        }
        String title = first(metadata, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        String artist = first(metadata, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        if (title.isEmpty() || artist.isEmpty()) {
            return title + artist;
        }
        return artist + " - " + title;
    }

    private static String first(MediaMetadata metadata, String... keys) {
        for (String key : keys) {
            String value = metadata.getString(key);
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    static Bitmap art(MediaMetadata metadata) {
        if (metadata == null) {
            return null;
        }
        for (String key : new String[] {MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON}) {
            Bitmap bitmap = metadata.getBitmap(key);
            if (bitmap != null) {
                return bitmap;
            }
        }
        return null;
    }

    /** Track length in ms, or 0 if the player doesn't say (e.g. a live stream). */
    static long duration(MediaMetadata metadata) {
        return metadata == null ? 0 : Math.max(0, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
    }

    /** The position now in ms, counting a seek the player hasn't reported yet; -1 if unknown. */
    static long position(MediaController controller, PlaybackState state) {
        if (state == null || state.getPosition() == PlaybackState.PLAYBACK_POSITION_UNKNOWN) {
            return -1;
        }
        long now = SystemClock.elapsedRealtime();
        boolean playing = state.getState() == PlaybackState.STATE_PLAYING;
        if (controller.getSessionToken().equals(seekToken) && now - seekAt < SEEK_PENDING_MS
                && state.getLastPositionUpdateTime() < seekAt) {
            return seekTarget + (playing ? now - seekAt : 0);
        }
        long position = state.getPosition();
        if (playing && state.getLastPositionUpdateTime() > 0) {
            position += (long) ((now - state.getLastPositionUpdateTime()) * state.getPlaybackSpeed());
        }
        return Math.max(0, position);
    }

    // ---- controlling ----

    /** Pauses or plays; with no session at all, a play key resumes the last player if it can. */
    static void playPause(Context context, MediaController controller, PlaybackState state) {
        if (controller == null) {
            AudioManager audio = context.getSystemService(AudioManager.class);
            audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY));
            audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY));
        } else if (isActive(state)) {
            controller.getTransportControls().pause();
        } else {
            controller.getTransportControls().play();
        }
    }

    /** Seeks by deltaMs from where the track is now (or from the last seek, if it's still pending). */
    static void seekBy(MediaController controller, PlaybackState state, MediaMetadata metadata, long deltaMs) {
        long position = position(controller, state);
        if (position >= 0) {
            seekTo(controller, metadata, position + deltaMs);
        }
    }

    /** Whether the player takes a seek to any point: most do, some live streams don't. */
    static boolean canSeek(PlaybackState state) {
        return state != null && (state.getActions() & PlaybackState.ACTION_SEEK_TO) != 0;
    }

    /** Seeks to `target` ms, kept within the track; shown at once, before the player reports it. */
    static void seekTo(MediaController controller, MediaMetadata metadata, long target) {
        target = Math.max(0, target);
        long duration = duration(metadata);
        if (duration > 0) {
            target = Math.min(target, duration);
        }
        seekToken = controller.getSessionToken();
        seekTarget = target;
        seekAt = SystemClock.elapsedRealtime();
        controller.getTransportControls().seekTo(target);
    }
}
