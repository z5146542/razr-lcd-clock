package dev.lcdclock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.RectF;
import android.media.AudioManager;
import android.os.BatteryManager;

import java.util.ArrayList;
import java.util.List;

/**
 * The quick controls panel, swiped down from LCD Clock's clock face: tiles in three columns,
 * chosen and ordered in the app (Prefs.quickOrder), lit in the clock colour while on; the battery
 * at the right of the top row, as on the clock face. Drag up or down when there are more than fit;
 * past the end, a drag up (or a sideways swipe) goes back to the face.
 * While the phone is locked the Home Assistant and VPN tiles carry a padlock (QuickControls.needsUnlock);
 * a tap buzzes twice and asks to unlock, and once the fingerprint has unlocked the phone the tile
 * does its job.
 */
final class QuickView extends CoverPage {
    /** TILE + index; VOLUME_DOWN + index for the volume tile's left half. */
    private static final int TILE = 100;
    private static final int VOLUME_DOWN = 200;

    private final Runnable onClose;
    private final Runnable onLeave;
    private final Path icon = new Path();
    private static final long REFUSED_MS = 700;
    /** The locked control tapped last, and when its padlock stops being lit. */
    private String refused;
    private long refusedUntil;
    /** SOS only starts after a 3-second hold, so a stray touch can't set off the siren. */
    private static final long SOS_HOLD_MS = 3000;
    /** Until when SOS shows HOLD 3S, after a touch let go too soon. */
    private long holdHintUntil;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            invalidate();
            postDelayed(this, 500);
        }
    };

    /** onClose: back to the face; onLeave: to Motorola's own screen. */
    QuickView(Context context, Runnable onClose, Runnable onLeave) {
        super(context);
        this.onClose = onClose;
        closeDrag = 1; // a drag up sends it back up
        onCloseDrag = onClose;
        this.onLeave = onLeave;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    private List<String> controls() {
        List<String> shown = new ArrayList<>();
        for (String control : Prefs.quickOrder(getContext())) {
            if (Prefs.quickShown(getContext(), control) && QuickControls.usable(getContext(), control)) {
                shown.add(control);
            }
        }
        return shown;
    }

    @Override
    void drawPage(Canvas canvas, int color) {
        Context context = getContext();
        panelHeader(canvas, "CONTROLS", null, 0);
        drawBattery(canvas);

        List<String> controls = controls();
        float left = bx + pad;
        float right = bx + bw - pad;
        float bottom = by + bh - pad;
        float gap = pad * 0.8f;
        float tileW = (right - left - 2 * gap) / 3;
        float tileH = (bottom - top - gap) / 2;
        int rows = (controls.size() + 2) / 3;
        float contentH = rows * tileH + (rows - 1) * gap;
        maxScroll = Math.max(0, contentH - (bottom - top));
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        canvas.save();
        canvas.clipRect(bx, top, bx + bw, bottom);
        for (int i = 0; i < controls.size(); i++) {
            float x = left + (i % 3) * (tileW + gap);
            float y = top + (i / 3) * (tileH + gap) - scroll;
            drawTile(canvas, i, controls.get(i), new RectF(x, y, x + tileW, y + tileH), color);
        }
        canvas.restore();
        scrollBar(canvas, top, bottom, contentH, color);
    }

    /** The battery at the right of the top row, as on the clock face: the coloured icon, then the level. */
    private void drawBattery(Canvas canvas) {
        BatteryManager battery = getContext().getSystemService(BatteryManager.class);
        int level = Math.max(0, Math.min(100, battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)));
        float textH = bh * CoverTabs.TAB_H;
        float base = by + pad + textH;
        float right = bx + bw - pad * 1.4f;
        SegmentPainter.italic(canvas, base - textH / 2);
        seg.textStyle(SegmentPainter.seg14(), textH);
        float percentX = right - seg.textWidth("%");
        seg.cell(canvas, "~", "%", percentX, base, Color.WHITE);
        seg.textStyle(SegmentPainter.seg7(), textH);
        float digitsX = percentX - seg.textWidth("888");
        seg.cell(canvas, "888", level == 100 ? "100" : (level < 10 ? "!!" : "!") + level, digitsX, base, Color.WHITE);
        float iconW = textH * 1.9f;
        float iconX = digitsX - textH * 0.5f - iconW;
        LcdClockView.drawBattery(canvas, fill, outline, path, iconX, base - textH, iconW, textH, level,
                LcdClockView.pluggedIn(getContext()), QuickControls.saverOn(getContext()), Prefs.saverColor(getContext()),
                LcdClockView.batteryColor(level));
        canvas.restore();
    }

    private void drawTile(Canvas canvas, int index, String control, RectF r, int color) {
        Context context = getContext();
        boolean on;
        String name;
        int slot = QuickControls.homeSlot(control);
        switch (control) {
            case QuickControls.TORCH:
                on = QuickControls.torchOn(context);
                name = "LIGHT";
                break;
            case QuickControls.SOS:
                on = SosService.isRunning();
                name = !on && System.currentTimeMillis() < holdHintUntil ? "HOLD 3S" : "SOS";
                break;
            case QuickControls.DND:
                on = QuickControls.dndOn(context);
                name = "DND";
                break;
            case QuickControls.SOUND: {
                int mode = QuickControls.ringMode(context);
                on = mode == AudioManager.RINGER_MODE_NORMAL;
                name = mode == AudioManager.RINGER_MODE_NORMAL ? "RING"
                        : mode == AudioManager.RINGER_MODE_VIBRATE ? "VIBRATE" : "SILENT";
                break;
            }
            case QuickControls.VOLUME:
                on = true;
                name = "MEDIA";
                break;
            case QuickControls.SAVER:
                on = QuickControls.saverOn(context);
                name = "SAVER";
                break;
            case QuickControls.NIGHT:
                on = QuickControls.nightOn(context);
                name = "NIGHT";
                break;
            case QuickControls.BEDTIME:
                on = QuickControls.bedtimeOn(context);
                name = "BEDTIME";
                break;
            case QuickControls.WIREGUARD:
                on = QuickControls.vpnOn(context);
                name = "VPN";
                break;
            case QuickControls.MOTOROLA:
                on = false;
                name = "MOTOROLA";
                break;
            default:
                Boolean state = slot >= 0 ? HomeAssistant.state(context, slot) : null;
                on = state == null || state;
                name = "HOME";
                break;
        }
        boolean lit = on && !QuickControls.VOLUME.equals(control) && !QuickControls.MOTOROLA.equals(control);
        button(canvas, TILE + index, r, lit, true, color);
        float progress = holdProgress(TILE + index);
        if (progress >= 0) {
            // Filling up left to right while held.
            float radius = r.height() * 0.28f;
            canvas.save();
            icon.reset();
            icon.addRoundRect(r, radius, radius, Path.Direction.CW);
            canvas.clipPath(icon);
            fill.setColor((color & 0x00FFFFFF) | 0x99000000);
            canvas.drawRect(r.left, r.top, r.left + r.width() * progress, r.bottom, fill);
            canvas.restore();
        }
        int ink = lit ? color : Color.WHITE;
        if (QuickControls.needsUnlock(control) && Lock.locked(context)) {
            float s = r.height() * 0.2f;
            float inset = r.height() * 0.12f;
            boolean flashing = control.equals(refused) && System.currentTimeMillis() < refusedUntil;
            Lock.drawBadge(canvas, r.right - inset - Lock.badgeWidth(s), r.top + inset, s,
                    flashing ? color : Lock.BADGE);
        }

        float s = Math.min(r.width(), r.height()) * 0.42f;
        float cx = r.centerX();
        float cy = r.top + r.height() * 0.42f;
        float labelH = r.height() * 0.15f;
        if (QuickControls.VOLUME.equals(control)) {
            // − level +, with the left half turning it down.
            RectF down = new RectF(r.left, r.top, r.centerX(), r.bottom);
            hits.remove(TILE + index);
            hits.put(VOLUME_DOWN + index, down);
            hits.put(TILE + index, new RectF(r.centerX(), r.top, r.right, r.bottom));
            float t = s * 0.14f;
            fill.setColor(Color.WHITE);
            canvas.drawRect(r.left + r.width() * 0.12f, cy - t / 2, r.left + r.width() * 0.12f + s * 0.5f, cy + t / 2, fill);
            float px = r.right - r.width() * 0.12f - s * 0.25f;
            canvas.drawRect(px - s * 0.25f, cy - t / 2, px + s * 0.25f, cy + t / 2, fill);
            canvas.drawRect(px - t / 2, cy - s * 0.25f, px + t / 2, cy + s * 0.25f, fill);
            float digitH = s * 0.9f;
            int level = QuickControls.volume(context);
            float pairW = digitH * (2 * SegmentPainter.DIGIT_W + SegmentPainter.DIGIT_GAP);
            SegmentPainter.italic(canvas, cy);
            seg.pair(canvas, cx - pairW / 2, cy - digitH / 2, digitH, level, true, color);
            canvas.restore();
        } else if (slot >= 0) {
            HomeView.drawIcon(canvas, icon, fill, slot, cx, cy, s * 1.1f, color, on);
        } else {
            drawIcon(canvas, control, cx, cy, s, ink, color);
        }
        seg.textStyle(SegmentPainter.seg14(), labelH);
        float w = seg.textWidth(SegmentPainter.ghost14(name));
        label(canvas, name, cx - w / 2, r.bottom - r.height() * 0.12f, labelH, lit ? color : Color.WHITE);
    }

    private void drawIcon(Canvas canvas, String control, float cx, float cy, float s, int ink, int color) {
        icon.reset();
        icon.setFillType(Path.FillType.WINDING);
        float t = s * 0.12f;
        fill.setColor(ink);
        switch (control) {
            case QuickControls.TORCH: { // a torch: head, body, and beams while on
                icon.moveTo(cx - s * 0.32f, cy - s * 0.42f);
                icon.lineTo(cx + s * 0.32f, cy - s * 0.42f);
                icon.lineTo(cx + s * 0.16f, cy - s * 0.12f);
                icon.lineTo(cx - s * 0.16f, cy - s * 0.12f);
                icon.close();
                icon.addRoundRect(cx - s * 0.14f, cy - s * 0.08f, cx + s * 0.14f, cy + s * 0.5f, t, t, Path.Direction.CW);
                canvas.drawPath(icon, fill);
                break;
            }
            case QuickControls.SOS: { // ··· ——— ··· in Morse marks
                float unit = s * 2.1f / 25; // S 5 units, gap 2, O 11, gap 2, S 5
                float x = cx - unit * 12.5f;
                float h = unit * 1.3f;
                int[] marks = {1, 1, 1, 0, 3, 3, 3, 0, 1, 1, 1};
                for (int mark : marks) {
                    if (mark == 0) {
                        x += unit; // the letter gap (2 units with the mark gap)
                        continue;
                    }
                    canvas.drawRoundRect(x, cy - h / 2, x + mark * unit, cy + h / 2, h / 2, h / 2, fill);
                    x += (mark + 1) * unit;
                }
                break;
            }
            case QuickControls.SAVER: { // a battery with a plus
                outline.setStrokeWidth(t * 0.8f);
                outline.setColor(ink);
                canvas.drawRoundRect(cx - s * 0.22f, cy - s * 0.36f, cx + s * 0.22f, cy + s * 0.46f, t, t, outline);
                canvas.drawRect(cx - s * 0.09f, cy - s * 0.46f, cx + s * 0.09f, cy - s * 0.36f, fill);
                canvas.drawRect(cx - s * 0.12f, cy + s * 0.05f - t * 0.4f, cx + s * 0.12f, cy + s * 0.05f + t * 0.4f, fill);
                canvas.drawRect(cx - t * 0.4f, cy + s * 0.05f - s * 0.12f, cx + t * 0.4f, cy + s * 0.05f + s * 0.12f, fill);
                break;
            }
            case QuickControls.BEDTIME: { // a bed: headboard, pillow, covers, mattress, foot
                canvas.drawRect(cx - s * 0.46f, cy - s * 0.3f, cx - s * 0.36f, cy + s * 0.32f, fill);
                canvas.drawRoundRect(cx - s * 0.32f, cy - s * 0.1f, cx - s * 0.1f, cy + s * 0.02f, t * 0.6f, t * 0.6f, fill);
                canvas.drawRoundRect(cx - s * 0.06f, cy - s * 0.16f, cx + s * 0.46f, cy + s * 0.02f, t, t, fill);
                canvas.drawRect(cx - s * 0.36f, cy + s * 0.06f, cx + s * 0.46f, cy + s * 0.18f, fill);
                canvas.drawRect(cx + s * 0.36f, cy + s * 0.18f, cx + s * 0.46f, cy + s * 0.32f, fill);
                break;
            }
            case QuickControls.NIGHT: { // a crescent moon
                Path moon = new Path();
                moon.addCircle(cx, cy, s * 0.36f, Path.Direction.CW);
                Path bite = new Path();
                bite.addCircle(cx + s * 0.2f, cy - s * 0.14f, s * 0.3f, Path.Direction.CW);
                moon.op(bite, Path.Op.DIFFERENCE);
                canvas.drawPath(moon, fill);
                break;
            }
            case QuickControls.DND: { // a no-entry sign
                outline.setStrokeWidth(t);
                outline.setColor(ink);
                canvas.drawCircle(cx, cy, s * 0.42f, outline);
                canvas.drawRect(cx - s * 0.26f, cy - t / 2, cx + s * 0.26f, cy + t / 2, fill);
                break;
            }
            case QuickControls.SOUND: {
                int mode = QuickControls.ringMode(getContext());
                if (mode == AudioManager.RINGER_MODE_VIBRATE) { // a phone with shake marks
                    outline.setStrokeWidth(t * 0.8f);
                    outline.setColor(ink);
                    canvas.drawRoundRect(cx - s * 0.18f, cy - s * 0.4f, cx + s * 0.18f, cy + s * 0.4f, t, t, outline);
                    for (int side = -1; side <= 1; side += 2) {
                        SegmentPainter.bar(icon, cx + side * s * 0.32f, cy - s * 0.2f, cx + side * s * 0.32f, cy + s * 0.2f, t * 0.8f);
                        SegmentPainter.bar(icon, cx + side * s * 0.46f, cy - s * 0.1f, cx + side * s * 0.46f, cy + s * 0.1f, t * 0.8f);
                    }
                    canvas.drawPath(icon, fill);
                } else {
                    seg.bell(canvas, cx, cy + s * 0.48f, s * 0.95f, true, ink);
                    if (mode == AudioManager.RINGER_MODE_SILENT) {
                        icon.reset();
                        SegmentPainter.bar(icon, cx - s * 0.42f, cy - s * 0.42f, cx + s * 0.42f, cy + s * 0.42f, t);
                        fill.setColor(ink);
                        canvas.drawPath(icon, fill);
                    }
                }
                break;
            }
            case QuickControls.WIREGUARD: { // a padlock
                outline.setStrokeWidth(t);
                outline.setColor(ink);
                canvas.drawArc(cx - s * 0.22f, cy - s * 0.46f, cx + s * 0.22f, cy - s * 0.02f, 180, 180, false, outline);
                canvas.drawLine(cx - s * 0.22f, cy - s * 0.24f, cx - s * 0.22f, cy - s * 0.02f, outline);
                canvas.drawLine(cx + s * 0.22f, cy - s * 0.24f, cx + s * 0.22f, cy - s * 0.02f, outline);
                canvas.drawRoundRect(cx - s * 0.34f, cy - s * 0.04f, cx + s * 0.34f, cy + s * 0.46f, t, t, fill);
                break;
            }
            default: { // Motorola's screen: an arrow out of a frame
                outline.setStrokeWidth(t);
                outline.setColor(ink);
                canvas.drawRoundRect(cx - s * 0.44f, cy - s * 0.38f, cx + s * 0.1f, cy + s * 0.38f, t, t, outline);
                SegmentPainter.bar(icon, cx - s * 0.12f, cy, cx + s * 0.46f, cy, t);
                SegmentPainter.bar(icon, cx + s * 0.46f, cy, cx + s * 0.28f, cy - s * 0.18f, t);
                SegmentPainter.bar(icon, cx + s * 0.46f, cy, cx + s * 0.28f, cy + s * 0.18f, t);
                canvas.drawPath(icon, fill);
                break;
            }
        }
    }

    /** The control a tile id stands for, or null. */
    private String controlAt(int id) {
        List<String> controls = controls();
        int index = id - TILE;
        return index >= 0 && index < controls.size() ? controls.get(index) : null;
    }

    @Override
    long holdMs(int id) {
        return QuickControls.SOS.equals(controlAt(id)) && !SosService.isRunning() ? SOS_HOLD_MS : 0;
    }

    @Override
    void onHoldTooShort(int id) {
        holdHintUntil = System.currentTimeMillis() + 1500;
        invalidate();
        postInvalidateDelayed(1600);
    }

    @Override
    boolean perform(int id) {
        Context context = getContext();
        List<String> controls = controls();
        if (id >= VOLUME_DOWN && id - VOLUME_DOWN < controls.size()) {
            QuickControls.adjustVolume(context, false);
            return true;
        }
        int index = id - TILE;
        if (index < 0 || index >= controls.size()) {
            return false;
        }
        String control = controls.get(index);
        int slot = QuickControls.homeSlot(control);
        if (QuickControls.needsUnlock(control) && Lock.locked(context)) {
            refused = control;
            refusedUntil = System.currentTimeMillis() + REFUSED_MS;
            Lock.refuse(context);
            invalidate();
            Lock.requestUnlock(context, () -> perform(id));
            return false;
        }
        switch (control) {
            case QuickControls.TORCH:
                QuickControls.toggleTorch(context);
                break;
            case QuickControls.SOS:
                QuickControls.toggleSos(context);
                break;
            case QuickControls.DND:
                QuickControls.toggleDnd(context);
                break;
            case QuickControls.SOUND:
                QuickControls.nextRingMode(context);
                break;
            case QuickControls.VOLUME:
                QuickControls.adjustVolume(context, true);
                break;
            case QuickControls.SAVER:
                QuickControls.toggleSaver(context);
                break;
            case QuickControls.NIGHT:
                QuickControls.toggleNight(context);
                break;
            case QuickControls.BEDTIME:
                QuickControls.toggleBedtime(context);
                break;
            case QuickControls.WIREGUARD:
                QuickControls.toggleWireGuard(context);
                break;
            case QuickControls.MOTOROLA:
                onLeave.run();
                return false;
            default:
                if (slot >= 0) {
                    if (HomeAssistantApi.usesEntities(context, slot)) {
                        HomeAssistantApi.toggle(context, slot, this::invalidate);
                    } else {
                        HomeAssistant.press(context, slot);
                    }
                }
                break;
        }
        invalidate();
        return true;
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            HomeAssistantApi.refresh(getContext(), this::invalidate);
        }
    }
}
