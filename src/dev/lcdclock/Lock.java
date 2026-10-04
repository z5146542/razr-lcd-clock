package dev.lcdclock;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.VibratorManager;
import android.util.Log;

/**
 * The phone's lock, for what the outside screen keeps back until it's unlocked: notification
 * contents (Notices), and the Home Assistant and VPN switches (HOME and the quick controls), which
 * refuse while locked whichever page taps them. The fingerprint unlocks the phone while it's
 * closed; the pages showing these check again at least every second, so they open up straight away.
 *
 * <p>While an LCD Clock page covers the lock screen, Android stops listening to the fingerprint
 * sensor (as for any app shown over the lock screen). Tapping something locked asks Android to
 * unlock (requestUnlock), which turns the sensor back on, then finishes the tap once unlocked.
 */
final class Lock {
    private static final Paint STROKE = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint FILL = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** The light grey of a padlock badge at rest. */
    static final int BADGE = 0xFFBBBBBB;
    private static Boolean last;

    static {
        STROKE.setStyle(Paint.Style.STROKE);
        FILL.setStyle(Paint.Style.FILL);
    }

    private Lock() {
    }

    /** The lock screen is up, or the phone wants its fingerprint, PIN or pattern before it opens. */
    static boolean locked(Context context) {
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        boolean keyguardUp = keyguard.isKeyguardLocked();
        boolean deviceLocked = keyguard.isDeviceLocked();
        boolean locked = keyguardUp || deviceLocked;
        if (last == null || last != locked) {
            last = locked;
            Log.d(Binders.TAG, "lock: " + (locked ? "locked" : "unlocked") + " (keyguard " + keyguardUp
                    + ", device " + deviceLocked + ")");
        }
        return locked;
    }

    /**
     * Asks Android to unlock, so a touch on the fingerprint sensor unlocks even with an LCD Clock
     * page showing; `then` (if any) runs once the phone is unlocked. `context` must be the activity.
     */
    static void requestUnlock(Context context, Runnable then) {
        if (!(context instanceof Activity activity)) {
            return;
        }
        Log.d(Binders.TAG, "lock: asking to unlock");
        activity.getSystemService(KeyguardManager.class).requestDismissKeyguard(activity,
                new KeyguardManager.KeyguardDismissCallback() {
                    @Override
                    public void onDismissSucceeded() {
                        Log.d(Binders.TAG, "lock: unlocked on request");
                        if (then != null) {
                            whenUnlocked(activity, then, 20);
                        }
                    }

                    @Override
                    public void onDismissCancelled() {
                        Log.d(Binders.TAG, "lock: unlock request cancelled");
                    }

                    @Override
                    public void onDismissError() {
                        Log.d(Binders.TAG, "lock: unlock request failed");
                    }
                });
    }

    /** Runs `then` once the lock screen has gone, checking every 100 ms up to `tries` times. */
    private static void whenUnlocked(Context context, Runnable then, int tries) {
        if (!locked(context)) {
            then.run();
        } else if (tries > 0) {
            MAIN.postDelayed(() -> whenUnlocked(context, then, tries - 1), 100);
        }
    }

    /** Two short buzzes: a control that needs the phone unlocked was tapped. */
    static void refuse(Context context) {
        context.getSystemService(VibratorManager.class).getDefaultVibrator()
                .vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK));
    }

    /** Width of a padlock badge `s` tall. */
    static float badgeWidth(float s) {
        return s * 0.72f;
    }

    /** A small padlock, `s` tall, with its top-left corner at (left, top). */
    static void drawBadge(Canvas canvas, float left, float top, float s, int color) {
        float w = badgeWidth(s);
        float cx = left + w / 2;
        float t = s * 0.13f;
        float shackle = s * 0.46f;
        float bodyTop = top + s * 0.42f;
        STROKE.setStrokeWidth(t);
        STROKE.setColor(color);
        canvas.drawArc(cx - shackle / 2, top + t / 2, cx + shackle / 2, top + t / 2 + shackle, 180, 180, false, STROKE);
        float sides = top + t / 2 + shackle / 2;
        canvas.drawLine(cx - shackle / 2, sides, cx - shackle / 2, bodyTop, STROKE);
        canvas.drawLine(cx + shackle / 2, sides, cx + shackle / 2, bodyTop, STROKE);
        FILL.setColor(color);
        canvas.drawRoundRect(left, bodyTop, left + w, top + s, t, t, FILL);
    }
}
