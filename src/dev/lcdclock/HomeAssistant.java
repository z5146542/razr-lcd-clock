package dev.lcdclock;

import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * The cover screen's Home Assistant buttons (monitor, lamp, fan). Each taps one or more
 * Home Assistant Button widgets that LCD Clock hosts privately; the HA app runs each widget's
 * configured action with its own server login, so LCD Clock never needs a URL or token.
 *
 * The widget's receiver isn't exported, so LCD Clock can't broadcast to it directly. Tapping
 * the widget's own view instead sends the HA app's PendingIntent, which carries the HA
 * app's identity.
 */
final class HomeAssistant {
    static final String PACKAGE = "io.homeassistant.companion.android";
    static final ComponentName BUTTON_WIDGET =
            new ComponentName(PACKAGE, PACKAGE + ".widgets.button.ButtonWidget");
    /** Our AppWidgetHost id; any number unique within this app. */
    static final int HOST_ID = 0x4C43;
    static final int SLOTS = 3;

    private HomeAssistant() {
    }

    /** The widget ids whose actions a button runs, in the order they were added. */
    static List<Integer> widgets(Context context, int slot) {
        List<Integer> ids = new ArrayList<>();
        for (String s : Prefs.get(context).getString("home_widgets_" + slot, "").split(",")) {
            if (!s.isEmpty()) {
                ids.add(Integer.parseInt(s));
            }
        }
        return ids;
    }

    static void addWidget(Context context, int slot, int id) {
        List<Integer> ids = widgets(context, slot);
        ids.add(id);
        saveWidgets(context, slot, ids);
    }

    static void removeWidget(Context context, int slot, int id) {
        List<Integer> ids = widgets(context, slot);
        ids.remove(Integer.valueOf(id));
        saveWidgets(context, slot, ids);
    }

    private static void saveWidgets(Context context, int slot, List<Integer> ids) {
        StringBuilder s = new StringBuilder();
        for (int id : ids) {
            s.append(s.length() == 0 ? "" : ",").append(id);
        }
        Prefs.get(context).edit().putString("home_widgets_" + slot, s.toString()).apply();
    }

    static boolean hasAnyAction(Context context) {
        for (int slot = 0; slot < SLOTS; slot++) {
            if (isConfigured(context, slot)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a button's entities are on; null when not known (no token or entities). */
    static Boolean state(Context context, int slot) {
        return HomeAssistantApi.slotState(context, slot);
    }

    /** A button does something: it has entities to switch, or widget actions to tap. */
    static boolean isConfigured(Context context, int slot) {
        return HomeAssistantApi.usesEntities(context, slot) || !widgets(context, slot).isEmpty();
    }

    /** Taps every widget on a button (main thread); returns false if the button has none or the phone is locked. */
    static boolean press(Context context, int slot) {
        if (Lock.locked(context)) {
            Log.d(Binders.TAG, "home button " + slot + ": locked, not pressing");
            return false;
        }
        List<Integer> ids = widgets(context, slot);
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        AppWidgetHost host = new AppWidgetHost(context, HOST_ID);
        for (int id : ids) {
            AppWidgetProviderInfo info = manager.getAppWidgetInfo(id);
            if (info == null) {
                Log.w(Binders.TAG, "home button " + slot + ": widget " + id + " is gone");
                continue;
            }
            // createView loads the widget's current look, including its tap action.
            AppWidgetHostView view = host.createView(context, id, info);
            View target = findTappable(view);
            if (target == null) {
                Log.w(Binders.TAG, "home button " + slot + ": widget " + id + " has no tap action yet");
                continue;
            }
            Log.d(Binders.TAG, "home button " + slot + ": tapping Home Assistant widget " + id);
            target.performClick();
        }
        return !ids.isEmpty();
    }

    /** The first view in a hosted widget that has a tap action (its PendingIntent). */
    static View findTappable(View view) {
        if (view.hasOnClickListeners()) {
            return view;
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findTappable(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
