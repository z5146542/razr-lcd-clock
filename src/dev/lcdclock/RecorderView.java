package dev.lcdclock;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.media.MediaPlayer;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * The cover screen's RECORDER page, like a pocket recorder's LCD: the time in segment digits
 * (recording, playing, or the last recording's length) with a blinking REC or PLAY beside it, a
 * level meter of segments, and REC/STOP, play/pause for the last recording and LIST. LIST shows
 * every recording in Recordings, newest first, two to the screen with its date and length; tap one
 * to play or pause it, BACK to return. Recording works while the phone is locked, but playing and
 * the list need it unlocked: their buttons carry a padlock, and a tap asks for the fingerprint
 * first. RecorderService (LCD Clock's own, no other app needed) does the recording; the app's
 * Recordings screen renames, shares and deletes them.
 */
final class RecorderView extends CoverPage {
    private static final int RECORD = 1;
    private static final int PLAY = 2;
    private static final int LIST = 3;
    private static final int BACK = 4;
    /** ROW + index: a recording in the list. */
    private static final int ROW = 100;
    private static final int METER_BARS = 24;
    private static final int RED = 0xFFFF2020;
    /** Quietest level the meter shows, in dB below full scale. */
    private static final float METER_FLOOR_DB = 50f;

    private MediaPlayer player;
    /** What the player has loaded. */
    private Uri playingUri;
    private boolean listing;
    private List<Recordings.Item> recordings = Collections.emptyList();
    /** Smoothed meter level, 0..1. */
    private float level;
    private static final long REFUSED_MS = 700;
    /** The locked button tapped last, and when its padlock stops being lit. */
    private int refused;
    private long refusedUntil;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            float now = RecorderService.isRecording() ? toLevel(RecorderService.amplitude()) : 0;
            level = Math.max(now, level * 0.75f);
            invalidate();
            postDelayed(this, RecorderService.isRecording() || player != null ? 80 : 500);
        }
    };

    RecorderView(Context context) {
        super(context);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        forgetDeletedLast();
        post(tick);
    }

    /** The last recording, if it has since been deleted (in the app, Files or another app), is forgotten. */
    private void forgetDeletedLast() {
        Uri last = lastRecording();
        if (last == null) {
            return;
        }
        try (Cursor c = getContext().getContentResolver().query(last, new String[] {MediaStore.Audio.Media._ID},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                return;
            }
        } catch (RuntimeException e) {
            // unreadable counts as gone
        }
        Prefs.get(getContext()).edit().remove(Prefs.LAST_RECORDING).remove(Prefs.LAST_RECORDING_MS).apply();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        stopPlaying();
        super.onDetachedFromWindow();
    }

    private static float toLevel(int amplitude) {
        if (amplitude <= 0) {
            return 0;
        }
        float db = 20f * (float) Math.log10(amplitude / 32767f);
        return Math.max(0, Math.min(1, 1 + db / METER_FLOOR_DB));
    }

    @Override
    void drawPage(Canvas canvas, int color) {
        Context context = getContext();
        boolean locked = Lock.locked(context);
        if (listing && locked) {
            listing = false; // the recordings are for an unlocked phone only
            stopPlaying();
        }
        if (listing) {
            drawList(canvas, color);
            return;
        }
        boolean allowed = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        boolean recording = RecorderService.isRecording();
        Uri last = lastRecording();
        boolean playing = player != null && player.isPlaying();

        float left = bx + pad * 1.4f;
        float right = bx + bw - pad * 1.4f;
        float btnTop = buttonTop();
        float h = btnTop - pad * 0.8f - top;

        // Time: recording, playing, else the last recording's length.
        long ms = recording ? RecorderService.elapsed()
                : player != null ? player.getCurrentPosition() : Prefs.get(context).getLong(Prefs.LAST_RECORDING_MS, 0);
        long secs = ms / 1000;
        boolean hours = secs >= 3600;
        int[] values = hours ? new int[] {(int) (secs / 3600), (int) (secs / 60 % 60), (int) (secs % 60)}
                : new int[] {(int) (secs / 60), (int) (secs % 60)};
        float digitH = h * 0.56f;
        float x = left;
        SegmentPainter.italic(canvas, top + digitH / 2);
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                x = seg.colon(canvas, x, top, digitH, true, color);
            }
            x = seg.pair(canvas, x, top, digitH, values[i], hours && i == 0, color);
        }
        canvas.restore();

        // REC (blinking while recording) or PLAY beside the digits.
        float tagH = digitH * 0.3f;
        boolean blinkOn = System.currentTimeMillis() / 500 % 2 == 0;
        String tag = recording ? "REC" : playing ? "PLAY" : "STOP";
        int tagColor = recording ? (blinkOn ? RED : SegmentPainter.dim(RED)) : playing ? color : GREY;
        label(canvas, tag, x + digitH * 0.3f, top + tagH, tagH, tagColor);

        // Level meter: a row of segments under the digits.
        float meterTop = top + digitH + h * 0.12f;
        float meterH = h * 0.12f;
        float slot = (right - left) / METER_BARS;
        int lit = Math.round(level * METER_BARS);
        for (int i = 0; i < METER_BARS; i++) {
            int barColor = i >= METER_BARS - 3 ? RED : color;
            fill.setColor(i < lit ? barColor : SegmentPainter.dim(barColor));
            canvas.drawRect(left + i * slot + slot * 0.15f, meterTop, left + (i + 1) * slot - slot * 0.15f,
                    meterTop + meterH, fill);
        }

        // What's going on, in words.
        String note;
        if (!allowed) {
            note = context.getString(R.string.recorder_needs_mic);
        } else if (recording) {
            note = context.getString(R.string.recorder_recording);
        } else if (last != null) {
            note = context.getString(R.string.recorder_last);
        } else {
            note = context.getString(R.string.recorder_empty);
        }
        line(canvas, note, left, top + h, h * 0.17f, right - left, allowed ? Color.WHITE : GREY);

        // REC / STOP, play / pause for the last recording, and the list of them all.
        float bh2 = buttonHeight();
        float gap = pad * 0.8f;
        float unit = (bw - 2 * pad - 2 * gap) / 4.4f;
        RectF rec = button(canvas, RECORD, new RectF(bx + pad, btnTop, bx + pad + unit * 2, btnTop + bh2), true,
                allowed, color);
        RectF play = button(canvas, PLAY, new RectF(rec.right + gap, btnTop, rec.right + gap + unit * 1.2f,
                btnTop + bh2), false, allowed && !recording && last != null, color);
        RectF list = button(canvas, LIST, new RectF(play.right + gap, btnTop, bx + bw - pad, btnTop + bh2), false,
                !recording, color);
        drawRecordIcon(canvas, rec, recording, allowed ? RED : GREY);
        drawPlayIcon(canvas, play, playing && last != null && last.equals(playingUri),
                allowed && !recording && last != null ? Color.WHITE : GREY);
        drawListIcon(canvas, list, recording ? GREY : Color.WHITE);
        if (locked) {
            // Playing and the list wait for the fingerprint.
            long now = System.currentTimeMillis();
            if (allowed && !recording && last != null) {
                lockBadge(canvas, play, PLAY, now, color);
            }
            if (!recording) {
                lockBadge(canvas, list, LIST, now, color);
            }
        }
    }

    private void lockBadge(Canvas canvas, RectF r, int id, long now, int color) {
        float s = r.height() * 0.3f;
        float inset = r.height() * 0.13f;
        Lock.drawBadge(canvas, r.right - inset - Lock.badgeWidth(s), r.top + inset, s,
                id == refused && now < refusedUntil ? color : Lock.BADGE);
    }

    /** Every recording, two to the screen: a play button, its date (or name) and its length. */
    private void drawList(Canvas canvas, int color) {
        Context context = getContext();
        float left = bx + pad * 1.4f;
        float right = bx + bw - pad * 1.4f;
        float bottom = by + bh - pad;
        // "< BACK" at the left, clear of the menu row's X above; the heading after it.
        float headH = bh * 0.06f;
        float backY = top + pad * 0.6f;
        float backH = bh * 0.125f;
        float listTop = backY + backH + pad * 0.6f;
        float backRight = backButton(canvas, BACK, backY, backH, top, listTop, color);
        label(canvas, "RECORDINGS", backRight + pad * 1.6f, backY + backH / 2 + headH / 2, headH, Color.WHITE);

        if (recordings.isEmpty()) {
            line(canvas, context.getString(R.string.recordings_none_cover), left, listTop + bh * 0.1f, bh * 0.085f,
                    right - left, GREY);
            return;
        }
        float rowH = (bottom - listTop) / 2;
        maxScroll = Math.max(0, recordings.size() * rowH - (bottom - listTop));
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        boolean scrolling = false;
        canvas.save();
        canvas.clipRect(bx, listTop, bx + bw, bottom);
        for (int i = 0; i < recordings.size(); i++) {
            float rowTop = listTop + i * rowH - scroll;
            if (rowTop > bottom || rowTop + rowH < listTop) {
                continue;
            }
            Recordings.Item item = recordings.get(i);
            boolean loaded = item.uri.equals(playingUri) && player != null;
            boolean playing = loaded && player.isPlaying();
            float s = rowH * 0.66f;
            RectF knob = new RectF(left, rowTop + (rowH - s) / 2, left + s, rowTop + (rowH + s) / 2);
            button(canvas, ROW + i, knob, loaded, true, color);
            drawPlayIcon(canvas, knob, playing, loaded ? color : Color.WHITE);
            hits.put(ROW + i, new RectF(bx, rowTop, bx + bw, rowTop + rowH)); // the whole row plays it
            float textX = knob.right + pad;
            scrolling |= marquee(canvas, Recordings.title(context, item), textX, rowTop + rowH * 0.47f, rowH * 0.33f,
                    right - textX, loaded ? color : Color.WHITE);
            String length = loaded ? Recordings.length(player.getCurrentPosition()) + " / " + Recordings.length(item.length)
                    : Recordings.length(item.length);
            line(canvas, length, textX, rowTop + rowH * 0.82f, rowH * 0.26f, right - textX, GREY);
        }
        canvas.restore();
        scrollBar(canvas, listTop, bottom, recordings.size() * rowH, color);
        if (scrolling) {
            postInvalidateOnAnimation();
        }
    }

    /** Three lines with dots: the list. */
    private void drawListIcon(Canvas canvas, RectF r, int ink) {
        float s = r.height() * 0.36f;
        float cx = r.centerX();
        float cy = r.centerY();
        fill.setColor(ink);
        for (int i = -1; i <= 1; i++) {
            float y = cy + i * s * 0.4f;
            canvas.drawCircle(cx - s * 0.55f, y, s * 0.09f, fill);
            canvas.drawRect(cx - s * 0.35f, y - s * 0.07f, cx + s * 0.6f, y + s * 0.07f, fill);
        }
    }

    private void drawRecordIcon(Canvas canvas, RectF r, boolean recording, int ink) {
        float s = r.height() * 0.34f;
        fill.setColor(ink);
        if (recording) {
            canvas.drawRect(r.centerX() - s / 2, r.centerY() - s / 2, r.centerX() + s / 2, r.centerY() + s / 2, fill);
        } else {
            canvas.drawCircle(r.centerX(), r.centerY(), s * 0.55f, fill);
        }
    }

    private void drawPlayIcon(Canvas canvas, RectF r, boolean playing, int ink) {
        float s = r.height() * 0.36f;
        float cx = r.centerX();
        float cy = r.centerY();
        fill.setColor(ink);
        if (playing) {
            float bar = s * 0.3f;
            float space = s * 0.24f;
            canvas.drawRect(cx - space / 2 - bar, cy - s / 2, cx - space / 2, cy + s / 2, fill);
            canvas.drawRect(cx + space / 2, cy - s / 2, cx + space / 2 + bar, cy + s / 2, fill);
        } else {
            path.reset();
            path.moveTo(cx - s * 0.4f, cy - s / 2);
            path.lineTo(cx + s * 0.5f, cy);
            path.lineTo(cx - s * 0.4f, cy + s / 2);
            path.close();
            canvas.drawPath(path, fill);
        }
    }

    /** What PLAY plays: LCD Clock's last recording. */
    private Uri lastRecording() {
        String uri = Prefs.get(getContext()).getString(Prefs.LAST_RECORDING, null);
        return uri == null ? null : Uri.parse(uri);
    }

    @Override
    boolean perform(int id) {
        Context context = getContext();
        if ((id == PLAY || id == LIST) && Lock.locked(context)) {
            refused = id;
            refusedUntil = System.currentTimeMillis() + REFUSED_MS;
            Lock.refuse(context);
            invalidate();
            Lock.requestUnlock(context, () -> perform(id));
            return false;
        }
        if (id == RECORD) {
            if (RecorderService.isRecording()) {
                RecorderService.stop(context);
            } else {
                stopPlaying();
                RecorderService.start(context);
            }
            return true;
        }
        if (id == PLAY) {
            toggle(lastRecording());
            return true;
        }
        if (id == LIST) {
            recordings = Recordings.list(context);
            listing = true;
            scroll = 0;
            return true;
        }
        if (id == BACK) {
            listing = false;
            maxScroll = 0;
            return true;
        }
        int row = id - ROW;
        if (row >= 0 && row < recordings.size()) {
            toggle(recordings.get(row).uri);
            return true;
        }
        return false;
    }

    /** Plays `uri`, or pauses or resumes it if it's the one loaded. */
    private void toggle(Uri uri) {
        if (uri == null) {
            return;
        }
        if (player != null && uri.equals(playingUri)) {
            if (player.isPlaying()) {
                player.pause();
            } else {
                player.start();
            }
            return;
        }
        stopPlaying();
        MediaPlayer p = new MediaPlayer();
        try {
            p.setDataSource(getContext(), uri);
            p.prepare();
            p.setOnCompletionListener(done -> stopPlaying());
            p.start();
            player = p;
            playingUri = uri;
        } catch (IOException | RuntimeException e) {
            Log.w(Binders.TAG, "can't play " + uri, e);
            p.release();
        }
    }

    private void stopPlaying() {
        if (player != null) {
            player.release();
            player = null;
        }
        playingUri = null;
    }
}
