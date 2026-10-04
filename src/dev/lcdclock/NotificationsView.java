package dev.lcdclock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.format.DateFormat;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The notifications panel, swiped up from LCD Clock's clock face: two rows to the screen (ROWS),
 * newest first, each with its app's icon, title and text (each scrolling when long) and time, and × to
 * dismiss. An app with several notifications is one row (its name, how many, and the newest);
 * tapping it opens the rest underneath, and its × dismisses them all. Tapping a notification opens
 * its own page: the app, time, title and all of its text (every message of a conversation), wrapped,
 * drag to read on; BACK or a drag down from the top returns to the list. CLEAR dismisses everything;
 * a drag down from the top, or a sideways swipe, goes back to the face. Notices decides
 * what may be shown while the phone is locked; a padlock after the title says it's locked, and
 * tapping it asks to unlock (so the fingerprint sensor listens).
 */
final class NotificationsView extends CoverPage {
    private static final int CLEAR = 2;
    private static final int UNLOCK = 3;
    private static final int BACK = 4;
    private static final int DETAIL_DISMISS = 5;
    /** Notifications to the screen; everything in a row scales with its height. */
    private static final int ROWS = 2;
    /** DISMISS + row index for its ×; ROW + row index for opening or closing an app's group. */
    private static final int DISMISS = 100;
    private static final int ROW = 1000;

    private final Runnable onClose;
    private List<Notices.Item> items = Collections.emptyList();
    /** Apps whose group of notifications is open. */
    private final Set<String> expanded = new HashSet<>();

    private final Runnable changed = this::reload;
    /** The notification whose own page is open, by key; null for the list. */
    private String detailKey;
    /** Where the list was scrolled to, for coming back to it. */
    private float listScroll;
    private final TextPaint titlePaint;
    private final TextPaint bodyPaint;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            reload(); // times, and the lock state (a fingerprint unlock shows the contents)
            postDelayed(this, 1000);
        }
    };

    NotificationsView(Context context, Runnable onClose) {
        super(context);
        this.onClose = onClose;
        closeDrag = -1; // a drag down sends it back down
        onCloseDrag = this::closeDragged;
        titlePaint = new TextPaint(text);
        titlePaint.setColor(Color.WHITE);
        bodyPaint = new TextPaint(text);
        bodyPaint.setColor(0xFFD2D2D2);
    }

    /** A drag down past the top: from a notification's page back to the list, else the panel closes. */
    private void closeDragged() {
        if (detailKey != null) {
            back();
            animate().translationY(0).setDuration(120);
        } else {
            onClose.run();
        }
    }

    private Notices.Item opened() {
        for (Notices.Item item : items) {
            if (item.key.equals(detailKey)) {
                return item;
            }
        }
        return null;
    }

    private void open(Notices.Item item) {
        listScroll = scroll;
        scroll = 0;
        detailKey = item.key;
        invalidate();
    }

    private void back() {
        detailKey = null;
        scroll = listScroll;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Notices.listen(changed);
        post(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        Notices.unlisten(changed);
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    private void reload() {
        items = Notices.list(getContext());
        if (detailKey != null && opened() == null) {
            back(); // dismissed, here or elsewhere
        }
        invalidate();
    }

    /** While locked, a padlock in the header from `x`; tapping it asks to unlock. */
    private void lockBadge(Canvas canvas, float x, int color) {
        if (!Lock.locked(getContext())) {
            return;
        }
        float textH = bh * CoverTabs.TAB_H;
        float s = textH * 1.2f;
        float y = by + pad - textH * 0.1f;
        Lock.drawBadge(canvas, x, y, s, pressed == UNLOCK ? color : Lock.BADGE);
        hits.put(UNLOCK, new RectF(x - textH, by, x + Lock.badgeWidth(s) + textH, y + s + textH * 0.4f));
    }

    @Override
    void drawPage(Canvas canvas, int color) {
        Context context = getContext();
        Notices.Item opened = detailKey == null ? null : opened();
        if (opened != null) {
            drawDetail(canvas, opened, color);
            return;
        }
        boolean anyClearable = false;
        for (Notices.Item item : items) {
            anyClearable |= item.clearable;
        }
        panelHeader(canvas, "NOTIFICATIONS", anyClearable ? "CLEAR" : null, CLEAR);
        float textH = bh * CoverTabs.TAB_H;
        seg.textStyle(SegmentPainter.seg14(), textH);
        lockBadge(canvas, bx + pad * 1.4f + seg.textWidth(SegmentPainter.ghost14("NOTIFICATIONS")) + textH * 0.7f, color);

        float left = bx + pad * 1.4f;
        float right = bx + bw - pad * 1.4f;
        float bottom = by + bh - pad;
        if (items.isEmpty()) {
            int note = !Notices.available() ? R.string.notices_needs_access
                    : !Notices.showingAllowed(context) ? R.string.notices_locked : R.string.notices_none;
            line(canvas, context.getString(note), left, top + bh * 0.12f, bh * 0.085f, right - left, GREY);
            return;
        }

        float rowH = (bottom - top) / ROWS;
        List<Row> rows = rows();
        maxScroll = Math.max(0, rows.size() * rowH - (bottom - top));
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        float iconS = rowH * 0.56f;
        float dismissS = rowH * 0.5f;
        boolean scrolling = false;
        canvas.save();
        canvas.clipRect(bx, top, bx + bw, bottom);
        for (int i = 0; i < rows.size(); i++) {
            float rowTop = top + i * rowH - scroll;
            if (rowTop > bottom || rowTop + rowH < top) {
                continue;
            }
            Row row = rows.get(i);
            Notices.Item item = row.item;
            float cy = rowTop + rowH / 2;
            RectF rowRect = new RectF(bx, rowTop, bx + bw, rowTop + rowH);
            if (pressed == ROW + i) {
                fill.setColor(SegmentPainter.dim(color));
                canvas.drawRoundRect(rowRect.left + pad * 0.5f, rowRect.top + 2, rowRect.right - pad * 0.5f,
                        rowRect.bottom - 2, rowH * 0.2f, rowH * 0.2f, fill);
            }
            // An expanded group's members sit indented under it, joined by a line.
            float rowLeft = left;
            if (row.child) {
                fill.setColor(SegmentPainter.dim(color));
                canvas.drawRect(left + iconS * 0.45f, rowTop, left + iconS * 0.55f, rowTop + rowH, fill);
                rowLeft = left + iconS * 0.9f;
            }
            float rowIcon = row.child ? iconS * 0.75f : iconS;
            Drawable icon = TabIcons.appIcon(context, item.packageName);
            if (icon != null) {
                icon.setAlpha(255);
                icon.setBounds(Math.round(rowLeft), Math.round(cy - rowIcon / 2), Math.round(rowLeft + rowIcon),
                        Math.round(cy + rowIcon / 2));
                icon.draw(canvas);
            }
            float textX = rowLeft + rowIcon + pad;
            boolean clearable = row.group != null ? anyClearable(row.group) : item.clearable;
            float textRight = right - (clearable ? dismissS + pad : 0);

            // Time at the right of the title line; the title scrolls in the space before it.
            String when = when(item.when);
            text.setTextSize(rowH * 0.26f);
            float whenW = text.measureText(when);
            line(canvas, when, textRight - whenW, rowTop + rowH * 0.42f, rowH * 0.26f, whenW + 1, GREY);
            String title;
            String body;
            if (row.group != null) {
                // The app and how many, with the newest one underneath; a chevron says it opens.
                title = context.getString(R.string.notices_group, item.app, row.group.size());
                body = item.hidden ? context.getString(R.string.notices_hidden)
                        : item.text.isEmpty() ? item.title : item.title + ": " + item.text;
                float chevronX = textRight - whenW - pad * 1.6f;
                float c = rowH * 0.09f;
                boolean open = expanded.contains(item.packageName);
                path.reset();
                float tipY = rowTop + rowH * 0.32f + (open ? -c / 2 : c / 2);
                float baseY = rowTop + rowH * 0.32f + (open ? c / 2 : -c / 2);
                SegmentPainter.bar(path, chevronX - c, baseY, chevronX, tipY, rowH * 0.05f);
                SegmentPainter.bar(path, chevronX, tipY, chevronX + c, baseY, rowH * 0.05f);
                fill.setColor(Color.WHITE);
                canvas.drawPath(path, fill);
                whenW += pad * 1.6f + c;
            } else {
                title = item.title;
                body = item.hidden ? context.getString(R.string.notices_hidden) : item.text;
            }
            scrolling |= marquee(canvas, title, textX, rowTop + rowH * 0.44f, rowH * 0.36f,
                    textRight - whenW - pad * 0.8f - textX, Color.WHITE);
            scrolling |= marquee(canvas, body, textX, rowTop + rowH * 0.84f, rowH * 0.29f, textRight - textX, GREY);

            if (clearable) {
                RectF r = new RectF(right - dismissS, cy - dismissS / 2, right, cy + dismissS / 2);
                button(canvas, DISMISS + i, r, false, true, color);
                float x = dismissS * 0.18f;
                path.reset();
                SegmentPainter.bar(path, r.centerX() - x, r.centerY() - x, r.centerX() + x, r.centerY() + x, dismissS * 0.08f);
                SegmentPainter.bar(path, r.centerX() + x, r.centerY() - x, r.centerX() - x, r.centerY() + x, dismissS * 0.08f);
                fill.setColor(Color.WHITE);
                canvas.drawPath(path, fill);
            }
            hits.put(ROW + i, rowRect); // after ×, so × wins where they overlap
        }
        canvas.restore();
        scrollBar(canvas, top, bottom, rows.size() * rowH, color);
        if (scrolling) {
            postInvalidateOnAnimation();
        }
    }

    /**
     * A notification's own page: its app (and sub text) and time with ×, then its title and all of
     * its text, wrapped; drag to read on.
     */
    private void drawDetail(Canvas canvas, Notices.Item item, int color) {
        Context context = getContext();
        float backRight = backHeader(canvas, BACK, color);
        lockBadge(canvas, backRight + bh * CoverTabs.TAB_H * 1.6f, color);
        float left = bx + pad * 1.4f;
        float right = bx + bw - pad * 1.4f;
        float bottom = by + bh - pad;
        float width = right - left - pad; // clear of the scroll bar

        float iconS = bh * 0.13f;
        float dismissS = bh * 0.15f;
        float appRowH = Math.max(iconS, dismissS);
        titlePaint.setTextSize(bh * 0.12f);
        StaticLayout title = wrap(item.title, titlePaint, width);
        CharSequence body = item.hidden ? context.getString(R.string.notices_hidden) : item.detail;
        bodyPaint.setTextSize(bh * 0.105f);
        StaticLayout content = body.length() == 0 || body.toString().equals(item.title) ? null
                : wrap(body, bodyPaint, width);
        float contentH = appRowH + pad + title.getHeight() + (content == null ? 0 : pad * 0.6f + content.getHeight());
        maxScroll = Math.max(0, contentH - (bottom - top));
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        canvas.save();
        canvas.clipRect(bx, top, bx + bw, bottom);
        float y = top - scroll;
        Drawable icon = TabIcons.appIcon(context, item.packageName);
        if (icon != null) {
            icon.setAlpha(255);
            icon.setBounds(Math.round(left), Math.round(y + (appRowH - iconS) / 2), Math.round(left + iconS),
                    Math.round(y + (appRowH + iconS) / 2));
            icon.draw(canvas);
        }
        float metaH = bh * 0.085f;
        float metaRight = right - (item.clearable ? dismissS + pad : 0);
        String when = when(item.when);
        text.setTextSize(metaH);
        float whenW = text.measureText(when);
        float base = y + appRowH / 2 + metaH * 0.35f;
        line(canvas, when, metaRight - whenW, base, metaH, whenW + 1, GREY);
        float appX = left + iconS + pad;
        line(canvas, item.sub.isEmpty() ? item.app : item.app + " · " + item.sub, appX, base, metaH,
                metaRight - whenW - pad - appX, GREY);
        if (item.clearable) {
            RectF r = new RectF(right - dismissS, y + (appRowH - dismissS) / 2, right, y + (appRowH + dismissS) / 2);
            button(canvas, DETAIL_DISMISS, r, false, true, color);
            float x = dismissS * 0.18f;
            path.reset();
            SegmentPainter.bar(path, r.centerX() - x, r.centerY() - x, r.centerX() + x, r.centerY() + x, dismissS * 0.08f);
            SegmentPainter.bar(path, r.centerX() + x, r.centerY() - x, r.centerX() - x, r.centerY() + x, dismissS * 0.08f);
            fill.setColor(Color.WHITE);
            canvas.drawPath(path, fill);
        }
        y += appRowH + pad;
        canvas.translate(left, y);
        title.draw(canvas);
        if (content != null) {
            canvas.translate(0, title.getHeight() + pad * 0.6f);
            content.draw(canvas);
        }
        canvas.restore();
        scrollBar(canvas, top, bottom, contentH, color);
    }

    private static StaticLayout wrap(CharSequence s, TextPaint paint, float width) {
        return StaticLayout.Builder.obtain(s, 0, s.length(), paint, Math.max(1, (int) width))
                .setLineSpacing(0, 1.05f)
                .build();
    }

    /** One line of the list: a notification, or an app's group of them, or a member of an open group. */
    private static final class Row {
        /** The notification, or the group's newest. */
        Notices.Item item;
        /** The app's notifications, for a group's row; null otherwise. */
        List<Notices.Item> group;
        boolean child;
    }

    /** Apps with more than one notification as one row, opened (followed by their members) by a tap. */
    private List<Row> rows() {
        Map<String, List<Notices.Item>> byApp = new LinkedHashMap<>();
        for (Notices.Item item : items) {
            byApp.computeIfAbsent(item.packageName, k -> new ArrayList<>()).add(item);
        }
        List<Row> rows = new ArrayList<>();
        for (List<Notices.Item> app : byApp.values()) {
            Row first = new Row();
            first.item = app.get(0);
            if (app.size() > 1) {
                first.group = app;
            }
            rows.add(first);
            if (app.size() > 1 && expanded.contains(first.item.packageName)) {
                for (Notices.Item item : app) {
                    Row child = new Row();
                    child.item = item;
                    child.child = true;
                    rows.add(child);
                }
            }
        }
        return rows;
    }

    private static boolean anyClearable(List<Notices.Item> group) {
        for (Notices.Item item : group) {
            if (item.clearable) {
                return true;
            }
        }
        return false;
    }

    /** "now", "5 min", the time today, else the weekday. */
    private String when(long time) {
        long ago = System.currentTimeMillis() - time;
        if (ago < 60_000) {
            return getContext().getString(R.string.notices_now);
        }
        if (ago < 3_600_000) {
            return getContext().getString(R.string.notices_minutes, (int) (ago / 60_000));
        }
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(time);
        Calendar now = Calendar.getInstance();
        if (then.get(Calendar.YEAR) == now.get(Calendar.YEAR) && then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)) {
            return DateFormat.getTimeFormat(getContext()).format(then.getTime());
        }
        return DateFormat.format("EEE", then).toString();
    }

    @Override
    boolean perform(int id) {
        if (id == CLEAR) {
            Notices.clearAll(getContext());
            return true;
        }
        if (id == UNLOCK) {
            Lock.requestUnlock(getContext(), null);
            return true;
        }
        if (id == BACK) {
            back();
            return false;
        }
        if (id == DETAIL_DISMISS) {
            if (detailKey != null) {
                Notices.dismiss(Collections.singletonList(detailKey));
                back();
            }
            return true;
        }
        if (detailKey != null) {
            return false; // the list's controls aren't showing
        }
        List<Row> rows = rows();
        if (id >= ROW && id - ROW < rows.size()) {
            Row row = rows.get(id - ROW);
            if (row.group == null) {
                open(row.item);
                return true;
            }
            if (!expanded.remove(row.item.packageName)) {
                expanded.add(row.item.packageName);
            }
            invalidate();
            return true;
        }
        int index = id - DISMISS;
        if (index >= 0 && index < rows.size()) {
            Row row = rows.get(index);
            if (row.group != null) {
                List<String> keys = new ArrayList<>();
                for (Notices.Item item : row.group) {
                    if (item.clearable) {
                        keys.add(item.key);
                    }
                }
                Notices.dismiss(keys);
            } else {
                Notices.dismiss(java.util.Collections.singletonList(row.item.key));
            }
            return true;
        }
        return false;
    }
}
