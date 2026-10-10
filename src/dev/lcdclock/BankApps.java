package dev.lcdclock;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.ApplicationExitInfo;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Process;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The banking apps the cover button keeps clear of (ButtonPause): close the phone while one of
 * these is still running and the accessibility service stays off until the phone next opens, as a
 * banking app running behind the outside screen, even from Recents (HSBC's RaspActivity), notices
 * the service coming on. Running means used since boot (an activity resumed, from usage access)
 * and its process not dead since (ApplicationExitInfo, which needs DUMP); swiping it away from
 * Recents kills it. Both are granted over adb by install.sh. Without DUMP only the app in front
 * counts. The apps are picked in LCD Clock; until then, any launchable app whose package name
 * names a bank.
 */
final class BankApps {
    /** Package name parts that make an app a banking app until the user picks (GUESS). */
    private static final List<String> GUESS = Arrays.asList("bank", "hsbc", "lloyds", "barclays", "natwest",
            "santander", "halifax", "nationwide", "monzo", "starling", "revolut", "westpac");
    /** Activities resumed this close to the phone shutting were opened by the closing (the outside screen's own). */
    private static final long CLOSING_MS = 1000;
    /** How far back to look for banking apps used. */
    private static final long LOOK_BACK_MS = 24 * 60 * 60 * 1000L;

    private BankApps() {
    }

    /** The picked banking apps' packages, or the guess before any were picked. */
    static Set<String> chosen(Context context) {
        String saved = Prefs.get(context).getString(Prefs.BANK_APPS, null);
        if (saved != null) {
            return new HashSet<>(saved.isEmpty() ? List.of() : Arrays.asList(saved.split(",")));
        }
        Set<String> guess = new HashSet<>();
        for (String[] app : launchable(context)) {
            String name = app[0].toLowerCase(Locale.ROOT);
            for (String part : GUESS) {
                if (name.contains(part)) {
                    guess.add(app[0]);
                    break;
                }
            }
        }
        return guess;
    }

    static void setChosen(Context context, Set<String> packages) {
        Prefs.get(context).edit().putString(Prefs.BANK_APPS, String.join(",", packages)).apply();
    }

    /** The apps with a launcher icon, as {package, label}, sorted by label; not LCD Clock. */
    static List<String[]> launchable(Context context) {
        PackageManager pm = context.getPackageManager();
        List<String[]> apps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo info : pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            String pkg = info.activityInfo.packageName;
            if (!pkg.equals(context.getPackageName()) && seen.add(pkg)) {
                apps.add(new String[] {pkg, info.loadLabel(pm).toString()});
            }
        }
        apps.sort((a, b) -> a[1].compareToIgnoreCase(b[1]));
        return apps;
    }

    /** Usage access has been given (install.sh does it over adb; otherwise Settings → Usage access). */
    static boolean usageAccess(Context context) {
        return context.getSystemService(AppOpsManager.class).unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    /** DUMP has been granted (install.sh), which reading other apps' process deaths needs. */
    static boolean canSeeExits(Context context) {
        return context.checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * A picked banking app still running as the phone closed at `closedAt` (wall-clock ms), or
     * null. Without DUMP, the one in front, if it's a banking app.
     */
    static String running(Context context, long closedAt) {
        if (!usageAccess(context)) {
            return null;
        }
        // Since boot only: nothing from before it is still running.
        long boot = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        Map<String, Long> lastUsed = new HashMap<>();
        String front = null;
        UsageEvents events = context.getSystemService(UsageStatsManager.class)
                .queryEvents(Math.max(boot, closedAt - LOOK_BACK_MS), closedAt - CLOSING_MS);
        UsageEvents.Event event = new UsageEvents.Event();
        while (events != null && events.hasNextEvent()) {
            events.getNextEvent(event);
            if (event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                front = event.getPackageName();
                lastUsed.put(front, event.getTimeStamp());
            }
        }
        Set<String> banks = chosen(context);
        if (!canSeeExits(context)) {
            return front != null && banks.contains(front) ? front : null;
        }
        for (String bank : banks) {
            Long used = lastUsed.get(bank);
            if (used != null && lastDeath(context, bank) < used) {
                return bank;
            }
        }
        return null;
    }

    /** When the app's own process last died (not its WebView's), in wall-clock ms; 0 if never seen. */
    private static long lastDeath(Context context, String pkg) {
        try {
            for (ApplicationExitInfo exit : context.getSystemService(ActivityManager.class)
                    .getHistoricalProcessExitReasons(pkg, 0, 0)) { // newest first
                if (pkg.equals(exit.getProcessName())) {
                    return exit.getTimestamp();
                }
            }
        } catch (SecurityException | IllegalArgumentException e) {
            // DUMP taken away, or the app's gone
        }
        return 0;
    }
}
