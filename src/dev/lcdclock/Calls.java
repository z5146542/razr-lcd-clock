package dev.lcdclock;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Person;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.telephony.PhoneNumberUtils;
import android.util.Log;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Calls from any app, read from their notifications through MediaListener, for the cover's CALL
 * panel: the phone's own (Google Phone), WhatsApp, KakaoTalk and anything else that posts one.
 *
 * <p>Each app's notification is read by the first {@link Source} that understands it. Most apps
 * post Android's CallStyle, whose answer, decline and hang-up intents are in the extras
 * ({@link #CALL_STYLE}); older ones only have buttons, found by their labels ({@link #BUTTONS}).
 * An app that needs its own reading gets a Source here, ahead of those two.
 */
final class Calls {
    /** A call: ringing (answer and decline) or in progress (hang up). */
    static final class Call {
        String key;
        String packageName;
        String app;
        /** The contact's name as the app shows it, else the number, else the app's name. */
        String caller;
        boolean ringing;
        boolean video;
        /** When the call started, for the in-call time; 0 if the app doesn't say. */
        long since;
        PendingIntent answer;
        /** Declines while ringing, hangs up once answered. */
        PendingIntent end;
        /** The app's own mute button, if its notification has one. */
        PendingIntent mute;
        /** The caller's picture, or null (none, or hidden while locked). */
        Drawable photo;
    }

    /** Reads one kind of call notification; null if it isn't one this source understands. */
    interface Source {
        Call read(StatusBarNotification sbn);
    }

    // CallStyle's extras; the key names are hidden from apps but fixed since Android 12.
    private static final String EXTRA_CALL_TYPE = "android.callType";
    private static final String EXTRA_ANSWER = "android.answerIntent";
    private static final String EXTRA_DECLINE = "android.declineIntent";
    private static final String EXTRA_HANG_UP = "android.hangUpIntent";
    private static final String EXTRA_VIDEO = "android.callIsVideo";
    private static final int CALL_TYPE_INCOMING = 1;
    private static final int CALL_TYPE_ONGOING = 2;

    /** Button labels, in English and Korean (KakaoTalk). */
    private static final Pattern ANSWER_LABEL = Pattern.compile(
            "(?i)\\b(answer|accept|pick up)\\b|받기|수락|응답");
    private static final Pattern MUTE_LABEL = Pattern.compile("(?i)\\b(un)?mute\\b|음소거");
    private static final Pattern END_LABEL = Pattern.compile(
            "(?i)\\b(decline|reject|hang up|end)\\b|거절|종료|끊기");

    /** Android's CallStyle: Google Phone, WhatsApp and most apps since Android 12. */
    static final Source CALL_STYLE = sbn -> {
        Bundle extras = sbn.getNotification().extras;
        PendingIntent answer = extras.getParcelable(EXTRA_ANSWER, PendingIntent.class);
        PendingIntent decline = extras.getParcelable(EXTRA_DECLINE, PendingIntent.class);
        PendingIntent hangUp = extras.getParcelable(EXTRA_HANG_UP, PendingIntent.class);
        int type = extras.getInt(EXTRA_CALL_TYPE, 0);
        if (answer == null && decline == null && hangUp == null) {
            return null;
        }
        Call call = new Call();
        call.ringing = type == CALL_TYPE_INCOMING || type != CALL_TYPE_ONGOING && answer != null;
        call.answer = call.ringing ? answer : null;
        call.end = call.ringing && decline != null ? decline : hangUp != null ? hangUp : decline;
        call.video = extras.getBoolean(EXTRA_VIDEO);
        return call;
    };

    /** A call notification with plain buttons, told apart by their labels. */
    static final Source BUTTONS = sbn -> {
        Notification n = sbn.getNotification();
        if (!Notification.CATEGORY_CALL.equals(n.category) || n.actions == null) {
            return null;
        }
        Call call = new Call();
        call.answer = action(n, ANSWER_LABEL);
        call.end = action(n, END_LABEL);
        if (call.answer == null && call.end == null) {
            return null;
        }
        call.ringing = call.answer != null;
        return call;
    };

    /** Tried in order: app-specific sources go first. */
    static final List<Source> SOURCES = Arrays.asList(CALL_STYLE, BUTTONS);

    private Calls() {
    }

    /** The ringing call if there is one, else the call in progress, else null. */
    static Call current(Context context) {
        MediaListener listener = MediaListener.connected;
        if (listener == null) {
            return null;
        }
        StatusBarNotification[] active;
        try {
            active = listener.getActiveNotifications();
        } catch (RuntimeException e) {
            Log.w(Binders.TAG, "can't read notifications", e);
            return null;
        }
        Call found = null;
        for (StatusBarNotification sbn : active == null ? new StatusBarNotification[0] : active) {
            Call call = read(context, sbn);
            if (call != null && (found == null || call.ringing && !found.ringing)) {
                found = call;
            }
        }
        return found;
    }

    private static final Handler main = new Handler(Looper.getMainLooper());

    /**
     * A notification was posted: if it's a call ringing with the phone closed, show the CALL
     * panel over Motorola's call screen (CallOverlay), else as a cover page, which Motorola's
     * may hide. Tried again a moment later in case the first try was too early.
     */
    static void posted(Context context, StatusBarNotification sbn) {
        Call call = read(context, sbn);
        if (call == null || !call.ringing || !CoverScreen.isPhoneClosed(context)) {
            return;
        }
        Context app = context.getApplicationContext();
        for (long delay : new long[] {0, 1500}) {
            main.postDelayed(() -> {
                if (!ringing(app) || !CoverScreen.isPhoneClosed(app) || CallOverlay.isShowing()) {
                    return;
                }
                if (!CallOverlay.show() && !CoverActivity.isOnCall()) {
                    Log.d(Binders.TAG, "call ringing: opening CALL");
                    CoverScreen.open(app, CoverActivity.PAGE_CALL);
                }
            }, delay);
        }
    }

    static boolean ringing(Context context) {
        Call call = current(context);
        return call != null && call.ringing;
    }

    /** The notification as a call, or null if it isn't one. */
    static Call read(Context context, StatusBarNotification sbn) {
        if (context.getPackageName().equals(sbn.getPackageName())) {
            return null;
        }
        Notification n = sbn.getNotification();
        for (Source source : SOURCES) {
            Call call = source.read(sbn);
            if (call == null) {
                continue;
            }
            call.key = sbn.getKey();
            call.packageName = sbn.getPackageName();
            call.app = appName(context, sbn.getPackageName());
            Notification shown = shown(context, n);
            call.caller = caller(context, shown, call.app);
            call.photo = photo(context, shown);
            call.mute = action(n, MUTE_LABEL);
            if (!call.ringing && n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)) {
                call.since = n.when;
            }
            return call;
        }
        return null;
    }

    /**
     * The notification as the cover may show it: while locked, one that hides its contents on the
     * lock screen is its public version, or null.
     */
    private static Notification shown(Context context, Notification n) {
        if (Lock.locked(context) && n.visibility != Notification.VISIBILITY_PUBLIC) {
            return n.visibility == Notification.VISIBILITY_PRIVATE ? n.publicVersion : null;
        }
        return n;
    }

    /** The CallStyle person's picture, else the large icon (the contact photo, for most apps). */
    private static Drawable photo(Context context, Notification shown) {
        if (shown == null) {
            return null;
        }
        Person person = shown.extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person.class);
        Icon icon = person != null && person.getIcon() != null ? person.getIcon() : shown.getLargeIcon();
        try {
            return icon == null ? null : icon.loadDrawable(context);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The first button whose label matches. */
    private static PendingIntent action(Notification n, Pattern label) {
        for (Notification.Action action : n.actions == null ? new Notification.Action[0] : n.actions) {
            if (action.title != null && label.matcher(action.title).find()) {
                return action.actionIntent;
            }
        }
        return null;
    }

    /** Who's calling: the CallStyle person, else the title, else the app. */
    private static String caller(Context context, Notification shown, String app) {
        String name = null;
        if (shown != null) {
            Person person = shown.extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person.class);
            if (person != null && person.getName() != null) {
                name = person.getName().toString().trim();
            }
            if (name == null || name.isEmpty()) {
                CharSequence title = shown.extras.getCharSequence(Notification.EXTRA_TITLE);
                name = title == null ? null : title.toString().trim();
            }
        }
        if (name == null || name.isEmpty()) {
            return app;
        }
        if (PhoneNumberUtils.isGlobalPhoneNumber(name.replace(" ", ""))) {
            String formatted = PhoneNumberUtils.formatNumber(name, Locale.getDefault().getCountry());
            return formatted != null ? formatted : name;
        }
        return name;
    }

    static void answer(Context context, Call call) {
        send(context, call.answer, "answer");
    }

    static void end(Context context, Call call) {
        send(context, call.end, "end");
    }

    /**
     * Mutes or unmutes: with the app's own button if it has one, else the microphone itself.
     * Android ignores the latter from an app that isn't running the call on some versions, so
     * this returns whether the microphone ended up as asked.
     */
    static boolean setMuted(Context context, Call call, boolean muted) {
        if (call.mute != null) {
            send(context, call.mute, muted ? "mute" : "unmute");
            Calls.muted = muted;
            return true;
        }
        AudioManager audio = context.getSystemService(AudioManager.class);
        audio.setMicrophoneMute(muted);
        Calls.muted = audio.isMicrophoneMute();
        return Calls.muted == muted;
    }

    /** Muted from the cover: the app's own button can't be asked, so remembered here. */
    private static boolean muted;

    static boolean muted(Context context) {
        return muted || context.getSystemService(AudioManager.class).isMicrophoneMute();
    }

    /** The call has gone: the next one starts unmuted. */
    static void forgetMute() {
        muted = false;
    }

    /** Sent as the cover's own tap, so an app that answers by opening its call screen may. */
    private static void send(Context context, PendingIntent intent, String what) {
        if (intent == null) {
            return;
        }
        try {
            ActivityOptions options = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            intent.send(context, 0, null, null, null, null, options.toBundle());
            Log.d(Binders.TAG, "call: " + what);
        } catch (PendingIntent.CanceledException e) {
            Log.w(Binders.TAG, "call: can't " + what, e);
        }
    }

    private static String appName(Context context, String packageName) {
        PackageManager pm = context.getPackageManager();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }
}
