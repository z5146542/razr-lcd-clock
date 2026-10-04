package dev.lcdclock;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import java.util.HashMap;
import java.util.Map;

/**
 * The cover pages' tab icons, for CoverTabs' menu row and the app's page list: segment-style
 * drawings for FACE (clock), TIMER (hourglass), ALARM (bell), CALENDAR, WEATHER (sun and cloud),
 * RECORDER (microphone, with a red dot while recording) and the plain MUSIC page (notes); the Home
 * Assistant app's icon for HOME; a player's app icon for its MUSIC page.
 */
final class TabIcons {
    private static final int GREY = 0xFF7A7A7A;
    /** App icons on tabs other than the current one. */
    private static final int DIM_ALPHA = 110;
    /** Launcher icons by package; null once one turned out not to be there. */
    private static final Map<String, Drawable> appIcons = new HashMap<>();

    private final Context context;
    private final SegmentPainter seg;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    TabIcons(Context context) {
        this.context = context;
        seg = new SegmentPainter(context);
        fill.setStyle(Paint.Style.FILL);
    }

    /** A page's tab icon in `r`: lit for the current page, grey (or a faded app icon) otherwise. */
    void draw(Canvas canvas, String page, RectF r, boolean on) {
        int ink = on ? Color.WHITE : GREY;
        float s = r.width();
        float cx = r.centerX();
        float cy = r.centerY();
        float t = s * 0.12f;
        float g = t * 0.3f; // gap where segments meet, as between a digit's segments
        path.reset();
        switch (page) {
            case CoverActivity.PAGE_FACE: { // a clock: ring of segments, hands at ten past ten
                float ring = 0.44f * s;
                for (int i = 0; i < 8; i++) {
                    double a0 = Math.toRadians(i * 45 + 22.5) + g / ring;
                    double a1 = Math.toRadians(i * 45 + 67.5) - g / ring;
                    SegmentPainter.bar(path, cx + ring * (float) Math.cos(a0), cy + ring * (float) Math.sin(a0),
                            cx + ring * (float) Math.cos(a1), cy + ring * (float) Math.sin(a1), t);
                }
                SegmentPainter.bar(path, cx, cy, cx + 0.22f * s, cy - 0.14f * s, t);
                SegmentPainter.bar(path, cx, cy, cx - 0.17f * s, cy - 0.10f * s, t);
                break;
            }
            case CoverActivity.PAGE_NOTICES: { // notifications: three lines, each with a dot
                for (int i = -1; i <= 1; i++) {
                    float y = cy + i * 0.3f * s;
                    path.addCircle(cx - 0.36f * s, y, t * 0.75f, Path.Direction.CW);
                    SegmentPainter.bar(path, cx - 0.2f * s, y, cx + 0.42f * s, y, t);
                }
                break;
            }
            case CoverActivity.PAGE_CONTROLS: { // controls: two sliders
                for (int i = -1; i <= 1; i += 2) {
                    float y = cy + i * 0.22f * s;
                    SegmentPainter.bar(path, cx - 0.42f * s, y, cx + 0.42f * s, y, t * 0.8f);
                    path.addCircle(cx + i * 0.18f * s, y, t * 1.4f, Path.Direction.CW);
                }
                break;
            }
            case CoverActivity.PAGE_CLOCK: { // hourglass: top and bottom bars, crossed sides
                float l = cx - 0.32f * s;
                float rt = cx + 0.32f * s;
                float top = cy - 0.44f * s;
                float bot = cy + 0.44f * s;
                SegmentPainter.bar(path, l, top, rt, top, t);
                SegmentPainter.bar(path, l, bot, rt, bot, t);
                SegmentPainter.bar(path, l + g, top + g * 2, rt - g, bot - g * 2, t);
                SegmentPainter.bar(path, rt - g, top + g * 2, l + g, bot - g * 2, t);
                break;
            }
            case CoverActivity.PAGE_ALARM:
                seg.bell(canvas, cx, cy + 0.42f * s, s * 0.9f, true, ink);
                return;
            case CoverActivity.PAGE_CALENDAR: { // a calendar sheet: frame, heading band, binding rings, dates
                float l = cx - 0.4f * s;
                float rt = cx + 0.4f * s;
                float top = cy - 0.34f * s;
                float bot = cy + 0.42f * s;
                SegmentPainter.bar(path, l + g, top, rt - g, top, t * 1.6f);
                SegmentPainter.bar(path, l, top + g, l, bot - g, t);
                SegmentPainter.bar(path, rt, top + g, rt, bot - g, t);
                SegmentPainter.bar(path, l + g, bot, rt - g, bot, t);
                SegmentPainter.bar(path, cx - 0.2f * s, top - 0.14f * s, cx - 0.2f * s, top + 0.06f * s, t);
                SegmentPainter.bar(path, cx + 0.2f * s, top - 0.14f * s, cx + 0.2f * s, top + 0.06f * s, t);
                for (int row = 0; row < 2; row++) {
                    for (int col = 0; col < 3; col++) {
                        float dx = cx + (col - 1) * 0.22f * s;
                        float dy = cy + 0.02f * s + row * 0.2f * s;
                        path.addRect(dx - t * 0.6f, dy - t * 0.6f, dx + t * 0.6f, dy + t * 0.6f, Path.Direction.CW);
                    }
                }
                break;
            }
            case CoverActivity.PAGE_WEATHER:
                WeatherView.drawIcon(canvas, path, fill, 2, true, cx, cy, s * 1.1f, ink);
                return;
            case CoverActivity.PAGE_RECORDER: { // a microphone: capsule, U-shaped holder, stand
                canvas.drawRoundRect(cx - 0.14f * s, cy - 0.46f * s, cx + 0.14f * s, cy + 0.08f * s, 0.14f * s, 0.14f * s,
                        fillWith(ink));
                SegmentPainter.bar(path, cx - 0.28f * s, cy - 0.12f * s, cx - 0.28f * s, cy + 0.1f * s, t);
                SegmentPainter.bar(path, cx + 0.28f * s, cy - 0.12f * s, cx + 0.28f * s, cy + 0.1f * s, t);
                SegmentPainter.bar(path, cx - 0.26f * s, cy + 0.14f * s, cx - g, cy + 0.24f * s, t);
                SegmentPainter.bar(path, cx + g, cy + 0.24f * s, cx + 0.26f * s, cy + 0.14f * s, t);
                SegmentPainter.bar(path, cx, cy + 0.26f * s, cx, cy + 0.42f * s, t);
                SegmentPainter.bar(path, cx - 0.18f * s, cy + 0.44f * s, cx + 0.18f * s, cy + 0.44f * s, t);
                fill.setColor(ink);
                canvas.drawPath(path, fill);
                if (RecorderService.isRecording()) {
                    fill.setColor(0xFFFF2020);
                    canvas.drawCircle(r.right - 0.08f * s, r.top + 0.1f * s, 0.13f * s, fill);
                }
                return;
            }
            case CoverActivity.PAGE_HOME: { // Home Assistant's icon, or a house: roof, walls, floor
                if (drawApp(canvas, appIcon(context, HomeAssistant.PACKAGE), r, on)) {
                    return;
                }
                float l = cx - 0.34f * s;
                float rt = cx + 0.34f * s;
                float eave = cy - 0.02f * s;
                float peak = cy - 0.46f * s;
                float bot = cy + 0.42f * s;
                SegmentPainter.bar(path, cx - 0.48f * s, eave + 0.06f * s, cx - g, peak, t);
                SegmentPainter.bar(path, cx + g, peak, cx + 0.48f * s, eave + 0.06f * s, t);
                SegmentPainter.bar(path, l, eave + g * 3, l, bot - g, t);
                SegmentPainter.bar(path, rt, eave + g * 3, rt, bot - g, t);
                SegmentPainter.bar(path, l + g, bot, rt - g, bot, t);
                SegmentPainter.bar(path, cx, bot - g, cx, cy + 0.12f * s, t * 1.6f);
                break;
            }
            default: {
                if (!CoverActivity.PAGE_MUSIC.equals(page)) { // a player: "music:<package>/<tag>"
                    String key = page.substring(CoverActivity.PAGE_MUSIC.length() + 1);
                    if (drawApp(canvas, appIcon(context, key.substring(0, key.indexOf('/'))), r, on)) {
                        return;
                    }
                }
                // Two beamed quavers, as on MUSIC without album art.
                float stemL = cx - 0.12f * s;
                float stemR = cx + 0.34f * s;
                SegmentPainter.bar(path, stemL, cy - 0.30f * s, stemR, cy - 0.44f * s, t * 1.3f);
                SegmentPainter.bar(path, stemL, cy - 0.30f * s, stemL, cy + 0.26f * s, t);
                SegmentPainter.bar(path, stemR, cy - 0.44f * s, stemR, cy + 0.12f * s, t);
                path.addOval(stemL - 0.30f * s, cy + 0.14f * s, stemL + t / 2, cy + 0.40f * s, Path.Direction.CW);
                path.addOval(stemR - 0.30f * s, cy + 0.00f * s, stemR + t / 2, cy + 0.26f * s, Path.Direction.CW);
                break;
            }
        }
        fill.setColor(ink);
        canvas.drawPath(path, fill);
    }

    private Paint fillWith(int color) {
        fill.setColor(color);
        return fill;
    }

    /** Draws an app icon in `r`, faded unless `on`; false if there's no icon to draw. */
    private static boolean drawApp(Canvas canvas, Drawable icon, RectF r, boolean on) {
        if (icon == null) {
            return false;
        }
        icon.setBounds(Math.round(r.left), Math.round(r.top), Math.round(r.right), Math.round(r.bottom));
        icon.setAlpha(on ? 255 : DIM_ALPHA);
        icon.draw(canvas);
        return true;
    }

    /** An app's launcher icon (cached), or null if it isn't installed. */
    static Drawable appIcon(Context context, String packageName) {
        if (!appIcons.containsKey(packageName)) {
            Drawable icon;
            try {
                icon = context.getPackageManager().getApplicationIcon(packageName).mutate();
            } catch (PackageManager.NameNotFoundException e) {
                icon = null;
            }
            appIcons.put(packageName, icon);
        }
        return appIcons.get(packageName);
    }

}
