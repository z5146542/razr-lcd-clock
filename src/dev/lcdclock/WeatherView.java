package dev.lcdclock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.text.format.DateFormat;

import java.util.Calendar;
import java.util.Locale;

/**
 * The cover screen's WEATHER page: a condition icon and the temperature in segment digits, today's
 * high, low and chance of rain beside them, then the next hours (time, icon, temperature), with
 * the place and when it was fetched. Weather supplies the forecast.
 */
final class WeatherView extends CoverPage {
    private static final int HOURS = 6;

    private Weather weather;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            Weather kept = Weather.get(getContext(), fresh -> {
                weather = fresh;
                invalidate();
            });
            if (kept != null) {
                weather = kept;
            }
            invalidate();
            postDelayed(this, 60_000);
        }
    };

    WeatherView(Context context) {
        super(context);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(refresh);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(refresh);
        super.onDetachedFromWindow();
    }

    @Override
    void drawPage(Canvas canvas, int color) {
        Context context = getContext();
        float left = bx + pad * 1.4f;
        float right = bx + bw - pad * 1.4f;
        float bottom = by + bh - pad;
        if (weather == null) {
            String note = context.getString(Weather.allowed(context) ? R.string.weather_loading : R.string.weather_needs_location);
            line(canvas, note, left, top + bh * 0.12f, bh * 0.085f, right - left, Weather.allowed(context) ? Color.WHITE : GREY);
            return;
        }

        // Now: icon, temperature, then today's high, low and rain.
        float h = bottom - top;
        float iconS = h * 0.48f;
        drawIcon(canvas, path, fill, weather.code, weather.day, left + iconS / 2, top + iconS / 2, iconS, color);
        float digitH = h * 0.42f;
        float x = left + iconS + pad;
        x = temperature(canvas, x, top + (iconS - digitH) / 2, digitH, weather.temp, color);

        // High, low and rain in three rows, then the place and when it was fetched.
        float infoH = h * 0.085f;
        float step = infoH * 1.55f;
        float infoX = x + pad * 1.2f;
        float infoTop = top + infoH;
        x = label(canvas, "H", infoX, infoTop, infoH, Color.WHITE) + infoH * 0.4f;
        small(canvas, x, infoTop, infoH, weather.high, Color.WHITE);
        x = label(canvas, "L", infoX, infoTop + step, infoH, Color.WHITE) + infoH * 0.4f;
        small(canvas, x, infoTop + step, infoH, weather.low, Color.WHITE);
        float rainBase = infoTop + step * 2;
        x = label(canvas, "R", infoX, rainBase, infoH, Color.WHITE) + infoH * 0.4f;
        seg.textStyle(SegmentPainter.seg7(), infoH);
        SegmentPainter.italic(canvas, rainBase - infoH / 2);
        float pctX = seg.cell(canvas, "888", rightAligned(weather.rain, 3), x, rainBase, Color.WHITE);
        canvas.restore();
        label(canvas, "%", pctX, rainBase, infoH, Color.WHITE);
        String place = weather.place == null ? "" : weather.place + " · ";
        line(canvas, place + DateFormat.getTimeFormat(context).format(weather.fetchedAt), infoX,
                Math.max(rainBase + h * 0.12f, top + iconS - h * 0.02f), h * 0.09f, right - infoX, GREY);

        // The coming hours.
        float stripTop = top + iconS + h * 0.08f;
        float slot = (right - left) / HOURS;
        boolean twelveHour = !DateFormat.is24HourFormat(context);
        float textH = h * 0.085f;
        for (int i = 0; i < HOURS && i < weather.hours.size(); i++) {
            Weather.Hour hour = weather.hours.get(i);
            float cx = left + slot * (i + 0.5f);
            Calendar at = Calendar.getInstance();
            at.setTimeInMillis(hour.time);
            int hr = at.get(Calendar.HOUR_OF_DAY);
            String time = twelveHour ? String.format(Locale.ROOT, "%d%s", hr % 12 == 0 ? 12 : hr % 12, hr < 12 ? "A" : "P")
                    : String.format(Locale.ROOT, "%02d", hr);
            seg.textStyle(SegmentPainter.seg14(), textH);
            float tw = seg.textWidth(time);
            label(canvas, time, cx - tw / 2, stripTop + textH, textH, i == 0 ? Color.WHITE : GREY);
            float s = slot * 0.55f;
            drawIcon(canvas, path, fill, hour.code, isDayHour(hr), cx, stripTop + textH * 1.6f + s / 2, s, color);
            seg.textStyle(SegmentPainter.seg7(), textH * 1.2f);
            String t = Integer.toString(hour.temp);
            float ttw = seg.textWidth(t);
            SegmentPainter.italic(canvas, bottom - textH * 0.6f);
            seg.cell(canvas, t.replaceAll("[0-9]", "8"), t, cx - ttw / 2, bottom, Color.WHITE);
            canvas.restore();
        }
    }

    private static boolean isDayHour(int hour) {
        return hour >= 7 && hour < 19;
    }

    private static String rightAligned(int value, int cells) {
        String s = Integer.toString(Math.max(0, value));
        StringBuilder b = new StringBuilder();
        for (int i = s.length(); i < cells; i++) {
            b.append('!');
        }
        return b.append(s).toString();
    }

    /** A temperature in big segment digits with a minus bar and a degree ring; returns the x after it. */
    private float temperature(Canvas canvas, float x, float y, float h, int value, int color) {
        SegmentPainter.italic(canvas, y + h / 2);
        if (value < 0) {
            fill.setColor(color);
            float t = h * SegmentPainter.SEGMENT_T;
            canvas.drawRect(x, y + h / 2 - t / 2, x + h * 0.32f, y + h / 2 + t / 2, fill);
            x += h * 0.42f;
        }
        int abs = Math.abs(value);
        if (abs >= 10) {
            x = seg.pair(canvas, x, y, h, abs % 100, false, color);
        } else {
            seg.digit(canvas, x, y, h, abs, color);
            x += h * SegmentPainter.DIGIT_W;
        }
        float ring = h * 0.11f;
        outline.setColor(color);
        outline.setStrokeWidth(h * SegmentPainter.SEGMENT_T * 0.8f);
        canvas.drawCircle(x + h * 0.12f + ring, y + ring * 1.2f, ring, outline);
        canvas.restore();
        return x + h * 0.12f + ring * 2;
    }

    /** A small temperature in DSEG7 at `baseline`; returns the x after it. */
    private float small(Canvas canvas, float x, float baseline, float glyphH, int value, int color) {
        seg.textStyle(SegmentPainter.seg7(), glyphH);
        String s = Integer.toString(value);
        SegmentPainter.italic(canvas, baseline - glyphH / 2);
        float end = seg.cell(canvas, s.replaceAll("[0-9]", "8"), s, x, baseline, color);
        canvas.restore();
        return end;
    }

    /**
     * A WMO weather code as a filled icon `s` across, centred on (cx, cy): sun or moon, cloud,
     * sun behind cloud, fog bars, and cloud with drizzle dots, rain streaks, snow flakes or a bolt.
     */
    static void drawIcon(Canvas canvas, Path path, Paint fill, int code, boolean day, float cx, float cy, float s,
            int color) {
        fill.setColor(color);
        path.reset();
        path.setFillType(Path.FillType.WINDING); // reset() keeps the fill type, and the moon's cut-out changes it
        if (code == 0 || code == 1) {
            sun(canvas, path, fill, cx, cy, s, day);
            return;
        }
        if (code == 2) {
            sun(canvas, path, fill, cx - s * 0.18f, cy - s * 0.16f, s * 0.7f, day);
            path.reset();
            cloud(path, cx + s * 0.06f, cy + s * 0.1f, s * 0.8f);
            Path halo = new Path(); // a black margin keeps the sun's rays off the cloud's edge
            cloud(halo, cx + s * 0.06f, cy + s * 0.1f, s * 0.92f);
            fill.setColor(Color.BLACK);
            canvas.drawPath(halo, fill);
            fill.setColor(color);
            canvas.drawPath(path, fill);
            return;
        }
        if (code == 45 || code == 48) {
            float t = s * 0.1f;
            for (int i = 0; i < 3; i++) {
                float y = cy - s * 0.22f + i * s * 0.22f;
                float inset = i == 1 ? 0 : s * 0.08f;
                canvas.drawRoundRect(cx - s * 0.42f + inset, y - t / 2, cx + s * 0.42f - inset, y + t / 2, t, t, fill);
            }
            return;
        }
        boolean precipitation = code >= 51;
        cloud(path, cx, precipitation ? cy - s * 0.12f : cy, s);
        canvas.drawPath(path, fill);
        if (!precipitation) {
            return;
        }
        float below = cy + s * 0.24f;
        float t = s * 0.07f;
        if (code >= 95) { // thunder
            path.reset();
            path.moveTo(cx + s * 0.04f, below - s * 0.04f);
            path.lineTo(cx - s * 0.12f, below + s * 0.16f);
            path.lineTo(cx, below + s * 0.16f);
            path.lineTo(cx - s * 0.08f, below + s * 0.32f);
            path.lineTo(cx + s * 0.14f, below + s * 0.08f);
            path.lineTo(cx + s * 0.02f, below + s * 0.08f);
            path.close();
            canvas.drawPath(path, fill);
        } else if (code >= 71 && code <= 77 || code == 85 || code == 86) { // snow
            for (int i = -1; i <= 1; i++) {
                float fx = cx + i * s * 0.22f;
                float fy = below + s * 0.1f + (i == 0 ? s * 0.08f : 0);
                path.reset();
                SegmentPainter.bar(path, fx - s * 0.07f, fy, fx + s * 0.07f, fy, t);
                SegmentPainter.bar(path, fx, fy - s * 0.07f, fx, fy + s * 0.07f, t);
                canvas.drawPath(path, fill);
            }
        } else if (code <= 57) { // drizzle
            for (int i = -1; i <= 1; i++) {
                canvas.drawCircle(cx + i * s * 0.2f, below + s * 0.1f + (i == 0 ? s * 0.08f : 0), t * 0.8f, fill);
            }
        } else { // rain and showers
            path.reset();
            for (int i = -1; i <= 1; i++) {
                float rx = cx + i * s * 0.2f;
                SegmentPainter.bar(path, rx + s * 0.05f, below, rx - s * 0.05f, below + s * 0.22f, t);
            }
            canvas.drawPath(path, fill);
        }
    }

    private static void sun(Canvas canvas, Path path, Paint fill, float cx, float cy, float s, boolean day) {
        if (!day) { // crescent moon, in its own path: the cut-out changes a path's fill type
            Path moon = new Path();
            moon.addCircle(cx, cy, s * 0.3f, Path.Direction.CW);
            Path bite = new Path();
            bite.addCircle(cx + s * 0.16f, cy - s * 0.1f, s * 0.26f, Path.Direction.CW);
            moon.op(bite, Path.Op.DIFFERENCE);
            canvas.drawPath(moon, fill);
            return;
        }
        canvas.drawCircle(cx, cy, s * 0.2f, fill);
        path.reset();
        float t = s * 0.08f;
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(i * 45);
            float c = (float) Math.cos(a);
            float sn = (float) Math.sin(a);
            SegmentPainter.bar(path, cx + c * s * 0.3f, cy + sn * s * 0.3f, cx + c * s * 0.46f, cy + sn * s * 0.46f, t);
        }
        canvas.drawPath(path, fill);
    }

    /** A cloud `s` across centred on (cx, cy): three puffs on a flat base. */
    private static void cloud(Path path, float cx, float cy, float s) {
        path.addCircle(cx - s * 0.2f, cy + s * 0.04f, s * 0.17f, Path.Direction.CW);
        path.addCircle(cx + s * 0.02f, cy - s * 0.08f, s * 0.22f, Path.Direction.CW);
        path.addCircle(cx + s * 0.24f, cy + s * 0.06f, s * 0.15f, Path.Direction.CW);
        path.addRoundRect(cx - s * 0.37f, cy + s * 0.02f, cx + s * 0.39f, cy + s * 0.21f, s * 0.1f, s * 0.1f,
                Path.Direction.CW);
    }
}
