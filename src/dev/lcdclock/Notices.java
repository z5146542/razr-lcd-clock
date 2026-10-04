package dev.lcdclock;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Person;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The notifications the cover's notifications panel lists and the face's dot counts, read
 * through MediaListener. Ongoing ones (music, timers, VPNs), group summaries, minimised ones and
 * LCD Clock's own are left out.
 *
 * <p>While the phone is locked this shows them restricted, as a lock screen that hides sensitive
 * content would, whatever the phone's own lock screen is set to: private ones (most apps') as the
 * app's public version or just "Contents hidden", secret ones not at all, and nothing if the lock
 * screen hides notifications. Unlocking (the fingerprint works with the phone closed) shows everything.
 */
final class Notices {
    static final class Item {
        String key;
        String app;
        String packageName;
        String title;
        String text;
        long when;
        boolean clearable;
        /** Shown without its contents because the phone is locked. */
        boolean hidden;
        /** All of it, for the notification's own page: every message or line, newlines kept. */
        CharSequence detail = "";
        /** Its sub text (often the account it's for), or "". */
        String sub = "";
    }

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final List<Runnable> listeners = new ArrayList<>();
    private static final Map<String, String> appNames = new HashMap<>();

    private Notices() {
    }

    /** Called on the main thread whenever notifications are posted, removed or re-ranked. */
    static void listen(Runnable listener) {
        listeners.add(listener);
    }

    static void unlisten(Runnable listener) {
        listeners.remove(listener);
    }

    static void changed() {
        main.post(() -> {
            for (Runnable listener : new ArrayList<>(listeners)) {
                listener.run();
            }
        });
    }

    static boolean available() {
        return MediaListener.connected != null;
    }

    /** The lock screen setting, or `fallback` (the more private choice) if it can't be read. */
    private static boolean lockScreenSetting(Context context, String name, boolean fallback) {
        try {
            return Settings.Secure.getInt(context.getContentResolver(), name) != 0;
        } catch (Settings.SettingNotFoundException | SecurityException e) {
            return fallback;
        }
    }

    /** False while locked if the lock screen shows no notifications at all. */
    static boolean showingAllowed(Context context) {
        return !Lock.locked(context) || lockScreenSetting(context, "lock_screen_show_notifications", true);
    }

    /** Newest first; empty without notification access, or while locked if the lock screen hides them. */
    static List<Item> list(Context context) {
        MediaListener listener = MediaListener.connected;
        if (listener == null || !showingAllowed(context)) {
            return Collections.emptyList();
        }
        boolean locked = Lock.locked(context);
        StatusBarNotification[] active;
        NotificationListenerService.RankingMap ranking;
        try {
            active = listener.getActiveNotifications();
            ranking = listener.getCurrentRanking();
        } catch (RuntimeException e) {
            Log.w(Binders.TAG, "can't read notifications", e);
            return Collections.emptyList();
        }
        List<Item> items = new ArrayList<>();
        NotificationListenerService.Ranking rank = new NotificationListenerService.Ranking();
        for (StatusBarNotification sbn : active == null ? new StatusBarNotification[0] : active) {
            Notification n = sbn.getNotification();
            if (sbn.isOngoing() || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0
                    || context.getPackageName().equals(sbn.getPackageName())) {
                continue;
            }
            int visibility = n.visibility;
            if (ranking != null && ranking.getRanking(sbn.getKey(), rank)) {
                if (rank.getImportance() <= NotificationManager.IMPORTANCE_MIN) {
                    continue;
                }
                if (rank.getLockscreenVisibilityOverride() != NotificationListenerService.Ranking.VISIBILITY_NO_OVERRIDE) {
                    visibility = rank.getLockscreenVisibilityOverride();
                }
            }
            Item item = new Item();
            item.key = sbn.getKey();
            item.packageName = sbn.getPackageName();
            item.app = appName(context, sbn.getPackageName());
            item.when = n.when != 0 ? n.when : sbn.getPostTime();
            item.clearable = sbn.isClearable();
            Notification shown = n;
            if (locked && visibility == Notification.VISIBILITY_SECRET) {
                continue;
            }
            if (locked && visibility == Notification.VISIBILITY_PRIVATE) {
                shown = n.publicVersion;
                item.hidden = shown == null;
            }
            if (shown != null) {
                Bundle extras = shown.extras;
                item.title = text(extras, Notification.EXTRA_TITLE);
                item.text = text(extras, Notification.EXTRA_BIG_TEXT);
                if (item.text.isEmpty()) {
                    item.text = text(extras, Notification.EXTRA_TEXT);
                }
                item.detail = detail(extras);
                item.sub = text(extras, Notification.EXTRA_SUB_TEXT);
            }
            if (item.title == null || item.title.isEmpty()) {
                item.title = item.app;
            }
            if (item.text == null) {
                item.text = "";
            }
            items.add(item);
        }
        items.sort((a, b) -> Long.compare(b.when, a.when));
        return items;
    }

    /**
     * The whole notification: a conversation's messages ("Name: text", one a line, names in bold
     * white), an inbox style's lines, else its big text, else its text.
     */
    private static CharSequence detail(Bundle extras) {
        if (extras == null) {
            return "";
        }
        Parcelable[] bundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable.class);
        List<Notification.MessagingStyle.Message> messages =
                Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles);
        if (messages != null && !messages.isEmpty()) {
            Person me = extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON, Person.class);
            SpannableStringBuilder s = new SpannableStringBuilder();
            for (Notification.MessagingStyle.Message message : messages) {
                Person sender = message.getSenderPerson();
                CharSequence name = sender != null ? sender.getName() : me != null ? me.getName() : null;
                s.append(s.length() == 0 ? "" : "\n");
                if (name != null && name.length() > 0) {
                    int start = s.length();
                    s.append(name.toString()).append(": ");
                    s.setSpan(new StyleSpan(Typeface.BOLD), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    s.setSpan(new ForegroundColorSpan(Color.WHITE), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                s.append(message.getText() == null ? "" : message.getText().toString());
            }
            return s;
        }
        CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null && lines.length > 0) {
            StringBuilder s = new StringBuilder();
            for (CharSequence line : lines) {
                s.append(s.length() == 0 ? "" : "\n").append(line);
            }
            return s.toString().trim();
        }
        CharSequence big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (big != null && big.toString().trim().length() > 0) {
            return big.toString().trim();
        }
        CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
        return text == null ? "" : text.toString().trim();
    }

    static int count(Context context) {
        return list(context).size();
    }

    private static String text(Bundle extras, String key) {
        CharSequence value = extras == null ? null : extras.getCharSequence(key);
        return value == null ? "" : value.toString().replace('\n', ' ').trim();
    }

    static void dismiss(List<String> keys) {
        MediaListener listener = MediaListener.connected;
        if (listener != null && !keys.isEmpty()) {
            listener.cancelNotifications(keys.toArray(new String[0]));
        }
    }

    static void clearAll(Context context) {
        MediaListener listener = MediaListener.connected;
        if (listener == null) {
            return;
        }
        List<String> keys = new ArrayList<>();
        for (Item item : list(context)) {
            if (item.clearable) {
                keys.add(item.key);
            }
        }
        listener.cancelNotifications(keys.toArray(new String[0]));
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
}
