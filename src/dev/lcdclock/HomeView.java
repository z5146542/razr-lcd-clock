package dev.lcdclock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.VibrationEffect;
import android.os.VibratorManager;
import android.view.MotionEvent;
import android.view.View;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The cover screen's HOME page: three big icon buttons (monitor, lamp, fan) in the clock's
 * segment style, each running its Home Assistant actions. An icon is lit while its switches
 * are on (or while their state isn't known) and shows only unlit segments while they're off.
 * While the phone is locked each carries a padlock; a tap buzzes twice and asks to unlock, and
 * once the fingerprint has unlocked the phone the button does its job.
 * Laid out for the 368x194 outside screen; on any other shape it uses the largest box of that shape.
 */
final class HomeView extends View {
    private static final int NONE = -1;
    private static final int GREY = 0xFF7A7A7A;
    private static final long FLASH_MS = 700;

    private final SegmentPainter seg;
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    /** Touch targets laid out by the last onDraw. */
    private final Map<Integer, RectF> hits = new LinkedHashMap<>();
    private final long[] flashUntil = new long[HomeAssistant.SLOTS];
    /** When a tap while locked stops lighting a button's padlock. */
    private final long[] refusedUntil = new long[HomeAssistant.SLOTS];
    private boolean drawnLocked;
    private int pressed = NONE;
    private static final long REFRESH_MS = 5000;

    /** Re-reads entity states while HOME is showing. */
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            HomeAssistantApi.refresh(getContext(), HomeView.this::invalidate);
            postDelayed(this, REFRESH_MS);
        }
    };

    /** Redraws when the phone locks or unlocks. */
    private final Runnable lockWatch = new Runnable() {
        @Override
        public void run() {
            if (Lock.locked(getContext()) != drawnLocked) {
                invalidate();
            }
            postDelayed(this, 500);
        }
    };

    HomeView(Context context) {
        super(context);
        seg = new SegmentPainter(context);
        outline.setStyle(Paint.Style.STROKE);
        fill.setStyle(Paint.Style.FILL);
        iconFill.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(refresh);
        post(lockWatch);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(refresh);
        removeCallbacks(lockWatch);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(Color.BLACK);
        hits.clear();
        Context context = getContext();
        int color = Prefs.color(context);
        long now = System.currentTimeMillis();
        boolean locked = Lock.locked(context);
        drawnLocked = locked;

        // Largest 368:194 box, centred.
        float bw = getWidth();
        float bh = getHeight();
        if (bw / bh < 368f / 194f) {
            bh = bw * 194f / 368f;
        } else {
            bw = bh * 368f / 194f;
        }
        float bx = (getWidth() - bw) / 2;
        float by = (getHeight() - bh) / 2;
        float pad = bh * 0.05f;

        // The menu row above is CoverTabs'.
        float tabBase = by + pad + bh * CoverTabs.TAB_H;

        // Three buttons filling the rest.
        float top = tabBase + pad;
        float bottom = by + bh - pad;
        float w = (bw - 2 * pad - 2 * pad) / HomeAssistant.SLOTS;
        float stroke = Math.max(1.5f, bh * 0.012f);
        outline.setStrokeWidth(stroke);
        for (int slot = 0; slot < HomeAssistant.SLOTS; slot++) {
            float left = bx + pad + slot * (w + pad);
            RectF r = new RectF(left, top, left + w, bottom);
            boolean configured = HomeAssistant.isConfigured(context, slot);
            boolean flashing = now < flashUntil[slot];
            float radius = w * 0.14f;
            if (pressed == slot || flashing) {
                fill.setColor(SegmentPainter.dim(color));
                canvas.drawRoundRect(r, radius, radius, fill);
            }
            outline.setColor(configured ? color : GREY);
            canvas.drawRoundRect(r.left + stroke / 2, r.top + stroke / 2, r.right - stroke / 2, r.bottom - stroke / 2,
                    radius, radius, outline);

            Boolean on = HomeAssistant.state(context, slot); // null: not known
            drawIcon(canvas, path, iconFill, slot, r.centerX(), r.centerY(), Math.min(r.width(), r.height()) * 0.62f,
                    configured ? color : GREY, configured && (on == null || on));
            if (configured && locked) {
                float s = Math.min(r.width(), r.height()) * 0.17f;
                float inset = radius * 0.55f;
                Lock.drawBadge(canvas, r.right - inset - Lock.badgeWidth(s), r.top + inset, s,
                        now < refusedUntil[slot] ? color : Lock.BADGE);
            }
            hits.put(slot, r);
        }
    }

    /**
     * Monitor (left), lamp (centre) or fan (right), `s` across, centred on (cx, cy), built from
     * clock-style segments: unlit segments always, lit on top when `lit`.
     */
    static void drawIcon(Canvas canvas, Path path, Paint iconFill, int slot, float cx, float cy, float s, int color,
            boolean lit) {
        path.reset();
        float t = s * 0.085f;
        float g = t * 0.3f; // gap where segments meet, as between a digit's segments
        switch (slot) {
            case 0: { // monitor: screen frame, stand, base
                float l = cx - 0.46f * s, r = cx + 0.46f * s, top = cy - 0.36f * s, bot = cy + 0.16f * s;
                SegmentPainter.bar(path, l + g, top, r - g, top, t);
                SegmentPainter.bar(path, l + g, bot, r - g, bot, t);
                SegmentPainter.bar(path, l, top + g, l, bot - g, t);
                SegmentPainter.bar(path, r, top + g, r, bot - g, t);
                SegmentPainter.bar(path, cx, bot + g, cx, cy + 0.34f * s - g, t);
                SegmentPainter.bar(path, cx - 0.22f * s, cy + 0.36f * s, cx + 0.22f * s, cy + 0.36f * s, t);
                break;
            }
            case 1: { // lamp: shade (top, bottom, slanted sides), stem, base
                float tl = cx - 0.16f * s, tr = cx + 0.16f * s, ty = cy - 0.40f * s;
                float bl = cx - 0.36f * s, br = cx + 0.36f * s, by = cy - 0.06f * s;
                SegmentPainter.bar(path, tl + g, ty, tr - g, ty, t);
                SegmentPainter.bar(path, bl + g, by, br - g, by, t);
                SegmentPainter.bar(path, tl - g * 0.3f, ty + g, bl + g * 0.3f, by - g, t);
                SegmentPainter.bar(path, tr + g * 0.3f, ty + g, br - g * 0.3f, by - g, t);
                SegmentPainter.bar(path, cx, by + g, cx, cy + 0.34f * s - g, t);
                SegmentPainter.bar(path, cx - 0.22f * s, cy + 0.36f * s, cx + 0.22f * s, cy + 0.36f * s, t);
                break;
            }
            default: { // fan: ring of eight segments, three petal blades, hub
                float ring = 0.46f * s;
                for (int i = 0; i < 8; i++) {
                    double a0 = Math.toRadians(i * 45 + 22.5) + g / ring;
                    double a1 = Math.toRadians(i * 45 + 67.5) - g / ring;
                    SegmentPainter.bar(path, cx + ring * (float) Math.cos(a0), cy + ring * (float) Math.sin(a0),
                            cx + ring * (float) Math.cos(a1), cy + ring * (float) Math.sin(a1), t);
                }
                for (int i = 0; i < 3; i++) {
                    android.graphics.Matrix turn = new android.graphics.Matrix();
                    turn.setRotate(i * 120f, cx, cy);
                    Path blade = new Path();
                    blade.moveTo(cx, cy - 0.10f * s);
                    blade.cubicTo(cx - 0.14f * s, cy - 0.16f * s, cx - 0.10f * s, cy - 0.36f * s, cx + 0.04f * s, cy - 0.36f * s);
                    blade.cubicTo(cx + 0.14f * s, cy - 0.34f * s, cx + 0.10f * s, cy - 0.16f * s, cx + 0.02f * s, cy - 0.10f * s);
                    blade.close();
                    blade.transform(turn);
                    path.addPath(blade);
                }
                path.addCircle(cx, cy, 0.06f * s, Path.Direction.CW);
                break;
            }
        }
        iconFill.setColor(SegmentPainter.dim(color));
        canvas.drawPath(path, iconFill);
        if (lit) {
            iconFill.setColor(color);
            canvas.drawPath(path, iconFill);
        }
    }

    private int hit(float x, float y) {
        for (Map.Entry<Integer, RectF> e : hits.entrySet()) {
            if (e.getValue().contains(x, y)) {
                return e.getKey();
            }
        }
        return NONE;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = hit(event.getX(), event.getY());
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (pressed != NONE && hit(event.getX(), event.getY()) != pressed) {
                    pressed = NONE;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                int control = pressed;
                pressed = NONE;
                if (control != NONE && hit(event.getX(), event.getY()) == control) {
                    perform(control);
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressed = NONE;
                invalidate();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void perform(int control) {
        Context context = getContext();
        if (HomeAssistant.isConfigured(context, control) && Lock.locked(context)) {
            refusedUntil[control] = System.currentTimeMillis() + FLASH_MS;
            Lock.refuse(context);
            postInvalidateDelayed(FLASH_MS + 20);
            Lock.requestUnlock(context, () -> perform(control));
            return;
        }
        boolean ran;
        if (HomeAssistantApi.usesEntities(context, control)) {
            HomeAssistantApi.toggle(context, control, this::invalidate);
            ran = true;
        } else {
            ran = HomeAssistant.press(context, control);
        }
        if (ran) {
            flashUntil[control] = System.currentTimeMillis() + FLASH_MS;
            context.getSystemService(VibratorManager.class).getDefaultVibrator()
                    .vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
            postInvalidateDelayed(FLASH_MS + 20);
        }
    }
}
