package dev.lcdclock;

import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Small persistent state, in device-protected storage so it works before first unlock. */
final class Prefs {
    static final String COLOR = "color";
    static final String BLACKOUT = "blackout_ms";
    static final String FACE_HOME = "face_home";
    static final String LAST_RECORDING = "last_recording";
    static final String LAST_RECORDING_MS = "last_recording_ms";
    static final String TAB_ORDER = "tab_order";
    static final String TAB_HIDDEN = "tab_hidden";
    static final String QUICK_ORDER = "quick_order";
    static final String WG_TUNNEL = "wg_tunnel";
    static final String WG_KEY = "wg_key";
    /** Left from when RECORDER could record through Fossify Voice Recorder's widget (dropFossify). */
    private static final String FOSSIFY_WIDGET = "fossify_widget";
    private static final String FOSSIFY_OFF = "fossify_off";
    /** The cover tabs in their first order; PAGE_MUSIC stands for all the music apps' tabs. */
    static final List<String> TABS = Arrays.asList(CoverActivity.PAGE_CLOCK, CoverActivity.PAGE_ALARM,
            CoverActivity.PAGE_CALENDAR, CoverActivity.PAGE_WEATHER, CoverActivity.PAGE_RECORDER,
            CoverActivity.PAGE_HOME, CoverActivity.PAGE_MUSIC);
    /** -1 for BLACKOUT: the phone's own screen timeout. */
    static final long PHONE_TIMEOUT = -1;

    private Prefs() {
    }

    static SharedPreferences get(Context context) {
        return context.createDeviceProtectedStorageContext().getSharedPreferences("lcdclock", Context.MODE_PRIVATE);
    }

    /**
     * How long a cover page keeps the outside screen on after the last touch, in ms, or
     * PHONE_TIMEOUT; `page` is a page name, with PAGE_MUSIC for every player's page. TIMER starts
     * on the phone's own timeout, the rest on the one setting there used to be (BLACKOUT), 15 s.
     */
    static long blackout(Context context, String page) {
        long shared = CoverActivity.PAGE_CLOCK.equals(page) ? PHONE_TIMEOUT : get(context).getLong(BLACKOUT, 15_000);
        return get(context).getLong(BLACKOUT + "_" + page, shared);
    }

    static void setBlackout(Context context, String page, long ms) {
        get(context).edit().putLong(BLACKOUT + "_" + page, ms).apply();
    }

    /** Open LCD Clock's own copy of the face when the outside screen wakes, so swipes reach the pages. */
    static boolean faceHome(Context context) {
        return get(context).getBoolean(FACE_HOME, true);
    }

    /** The tabs in the order chosen in the app; tabs added since go at the end. */
    static List<String> tabOrder(Context context) {
        List<String> order = new ArrayList<>();
        for (String tab : get(context).getString(TAB_ORDER, "").split(",")) {
            if (TABS.contains(tab) && !order.contains(tab)) {
                order.add(tab);
            }
        }
        for (String tab : TABS) {
            if (!order.contains(tab)) {
                order.add(tab);
            }
        }
        return order;
    }

    static void setTabOrder(Context context, List<String> order) {
        get(context).edit().putString(TAB_ORDER, String.join(",", order)).apply();
    }

    /** The quick controls in the order chosen in the app; ones added since go at the end. */
    static List<String> quickOrder(Context context) {
        List<String> order = new ArrayList<>();
        for (String control : get(context).getString(QUICK_ORDER, "").split(",")) {
            if (QuickControls.ALL.contains(control) && !order.contains(control)) {
                order.add(control);
            }
        }
        // Controls added since the order was saved go after the one they follow by default.
        for (int i = 0; i < QuickControls.ALL.size(); i++) {
            String control = QuickControls.ALL.get(i);
            if (!order.contains(control)) {
                int after = i == 0 ? -1 : order.indexOf(QuickControls.ALL.get(i - 1));
                order.add(after < 0 ? order.size() : after + 1, control);
            }
        }
        return order;
    }

    static void setQuickOrder(Context context, List<String> order) {
        get(context).edit().putString(QUICK_ORDER, String.join(",", order)).apply();
    }

    /** Shown in the quick controls panel; all are, except WireGuard until it's switched on. */
    static boolean quickShown(Context context, String control) {
        return get(context).getBoolean("quick_show_" + control, !QuickControls.WIREGUARD.equals(control));
    }

    static void setQuickShown(Context context, String control, boolean shown) {
        get(context).edit().putBoolean("quick_show_" + control, shown).apply();
    }

    static boolean tabShown(Context context, String tab) {
        return !get(context).getStringSet(TAB_HIDDEN, new HashSet<>()).contains(tab);
    }

    static void setTabShown(Context context, String tab, boolean shown) {
        Set<String> hidden = new HashSet<>(get(context).getStringSet(TAB_HIDDEN, new HashSet<>()));
        if (shown) {
            hidden.remove(tab);
        } else {
            hidden.add(tab);
        }
        get(context).edit().putStringSet(TAB_HIDDEN, hidden).apply();
    }

    /** The colour picked for the clock face, so the cover screen can match it. */
    static int color(Context context) {
        return get(context).getInt(COLOR, Palette.PRESETS[0]);
    }

    /**
     * RECORDER once recorded through a Fossify Voice Recorder widget that LCD Clock hosted out of
     * sight; it's LCD Clock's own recorder now, so let that widget go.
     */
    static void dropFossify(Context context) {
        SharedPreferences prefs = get(context);
        int id = prefs.getInt(FOSSIFY_WIDGET, AppWidgetManager.INVALID_APPWIDGET_ID);
        if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
            new AppWidgetHost(context, HomeAssistant.HOST_ID).deleteAppWidgetId(id);
        }
        if (prefs.contains(FOSSIFY_WIDGET) || prefs.contains(FOSSIFY_OFF)) {
            prefs.edit().remove(FOSSIFY_WIDGET).remove(FOSSIFY_OFF).apply();
        }
    }
}
