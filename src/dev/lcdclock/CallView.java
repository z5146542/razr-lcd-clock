package dev.lcdclock;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.format.DateUtils;

/**
 * The CALL panel, for a call in any app (Calls): the caller's picture and name, and while it rings
 * a red DECLINE and a green ANSWER, once answered the call's time, MUTE and a red HANG UP. No menu
 * row, and swipes do nothing, so a call isn't swiped away by mistake. Runs `onGone` once there's no
 * call any more.
 */
final class CallView extends CoverPage {
    private static final int ANSWER = 1;
    private static final int END = 2;
    private static final int MUTE = 3;
    static final int ANSWER_GREEN = 0xFF1FB347;

    private final Runnable onGone;
    private final Paint photoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Calls.Call call;
    /** The picture drawn, and its shader: made again only when the picture changes. */
    private Drawable shaderFor;
    private Bitmap photoBitmap;
    /** Muting didn't take: the microphone couldn't be muted from here. */
    private boolean muteFailed;

    private final Runnable changed = this::refresh;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            invalidate();
            postDelayed(this, 1000);
        }
    };

    CallView(Context context, Runnable onGone) {
        super(context);
        this.onGone = onGone;
        call = Calls.current(context);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Notices.listen(changed);
        post(tick);
        if (call == null) {
            post(onGone);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        Notices.unlisten(changed);
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    private void refresh() {
        call = Calls.current(getContext());
        if (call == null) {
            Calls.forgetMute();
            onGone.run();
            return;
        }
        invalidate();
    }

    @Override
    void drawPage(Canvas canvas, int color) {
        if (call == null) {
            return;
        }
        float photoS = bh * 0.42f;
        float px = bx + pad * 1.5f;
        float py = by + pad * 1.5f;
        drawPhoto(canvas, px, py, photoS, color);

        float textX = px + photoS + pad * 1.5f;
        float textW = bx + bw - pad * 1.5f - textX;
        String status;
        if (call.ringing) {
            status = call.video ? "VIDEO CALL" : "INCOMING";
        } else if (call.since > 0) {
            status = DateUtils.formatElapsedTime(Math.max(0, (System.currentTimeMillis() - call.since) / 1000));
        } else {
            status = "IN CALL";
        }
        float glyphH = bh * 0.08f;
        float statusBase = py + glyphH;
        label(canvas, status, textX, statusBase, glyphH, color);
        line(canvas, call.app, textX, statusBase + bh * 0.11f, bh * 0.085f, textW, GREY);
        if (marquee(canvas, call.caller, textX, py + photoS - bh * 0.02f, bh * 0.14f, textW, Color.WHITE)) {
            postInvalidateOnAnimation();
        }

        float h = buttonHeight();
        float y = buttonTop();
        float gap = pad * 1.5f;
        float w = (bw - pad * 3 - gap) / 2;
        RectF left = new RectF(bx + pad * 1.5f, y, bx + pad * 1.5f + w, y + h);
        RectF right = new RectF(left.right + gap, y, left.right + gap + w, y + h);
        if (call.ringing) {
            filled(canvas, END, left, CLEAR_RED, "DECLINE", call.end != null);
            filled(canvas, ANSWER, right, ANSWER_GREEN, "ANSWER", call.answer != null);
        } else {
            boolean muted = Calls.muted(getContext());
            button(canvas, MUTE, left, muted, !muteFailed, color);
            String s = muteFailed ? "NO MUTE" : muted ? "MUTED" : "MUTE";
            centred(canvas, s, left, muteFailed ? GREY : muted ? color : Color.WHITE);
            filled(canvas, END, right, CLEAR_RED, "HANG UP", call.end != null);
        }
    }

    /** A solid button (the call's own colours: red ends, green answers) with a white label. */
    private void filled(Canvas canvas, int id, RectF r, int colour, String s, boolean enabled) {
        float radius = r.height() * 0.5f;
        fill.setColor(!enabled ? GREY : pressed == id ? SegmentPainter.dim(colour) : colour);
        canvas.drawRoundRect(r, radius, radius, fill);
        if (enabled) {
            hits.put(id, r);
        }
        centred(canvas, s, r, Color.WHITE);
    }

    private void centred(Canvas canvas, String s, RectF r, int colour) {
        text.setTextSize(r.height() * 0.42f);
        text.setColor(colour);
        text.setFakeBoldText(true);
        float w = text.measureText(s);
        Paint.FontMetrics m = text.getFontMetrics();
        canvas.drawText(s, r.centerX() - w / 2, r.centerY() - (m.ascent + m.descent) / 2, text);
        text.setFakeBoldText(false);
    }

    /** The caller's picture in a circle, else their initial on the clock colour. */
    private void drawPhoto(Canvas canvas, float x, float y, float s, int color) {
        float cx = x + s / 2;
        float cy = y + s / 2;
        Bitmap bitmap = bitmap(call.photo);
        if (bitmap != null) {
            float scale = s / Math.min(bitmap.getWidth(), bitmap.getHeight());
            Matrix m = new Matrix();
            m.setScale(scale, scale);
            m.postTranslate(cx - bitmap.getWidth() * scale / 2, cy - bitmap.getHeight() * scale / 2);
            photoPaint.getShader().setLocalMatrix(m);
            canvas.drawCircle(cx, cy, s / 2, photoPaint);
            return;
        }
        fill.setColor(SegmentPainter.dim(color));
        canvas.drawCircle(cx, cy, s / 2, fill);
        String initial = call.caller.isEmpty() || !Character.isLetter(call.caller.codePointAt(0))
                ? "?" : new String(Character.toChars(Character.toUpperCase(call.caller.codePointAt(0))));
        text.setTextSize(s * 0.5f);
        text.setColor(Color.WHITE);
        Paint.FontMetrics m = text.getFontMetrics();
        canvas.drawText(initial, cx - text.measureText(initial) / 2, cy - (m.ascent + m.descent) / 2, text);
    }

    private Bitmap bitmap(Drawable photo) {
        if (photo != shaderFor) {
            shaderFor = photo;
            photoBitmap = null;
            if (photo instanceof BitmapDrawable) {
                photoBitmap = ((BitmapDrawable) photo).getBitmap();
            } else if (photo != null && photo.getIntrinsicWidth() > 0 && photo.getIntrinsicHeight() > 0) {
                photoBitmap = Bitmap.createBitmap(photo.getIntrinsicWidth(), photo.getIntrinsicHeight(),
                        Bitmap.Config.ARGB_8888);
                photo.setBounds(0, 0, photoBitmap.getWidth(), photoBitmap.getHeight());
                photo.draw(new Canvas(photoBitmap));
            }
            photoPaint.setShader(photoBitmap == null ? null
                    : new BitmapShader(photoBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        }
        return photoBitmap;
    }

    @Override
    boolean perform(int id) {
        Context context = getContext();
        if (call == null) {
            return false;
        }
        if (id == ANSWER) {
            Calls.answer(context, call);
        } else if (id == END) {
            Calls.end(context, call);
        } else if (id == MUTE) {
            muteFailed = !Calls.setMuted(context, call, !Calls.muted(context));
        }
        invalidate();
        return true;
    }
}
