package com.satoshilimited.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;

final class DeleteDialog {
    static final long HOLD = 1500, NOTICE = 4000;
    private static final int RED = 0xFFFF6B66, INK = 0xFFF2F2F2;
    private static final float WIDTH = 860, PAD = 48, BUTTON = 80, GAP = 18;

    interface Listener { void confirmed(); }

    private final View view;
    private final Listener listener;
    private final RectF box = new RectF(), cancel = new RectF(), confirm = new RectF(), pill = new RectF();
    private final FocusRing ring;
    private final Runnable fire = this::finishHold;
    private boolean open, working, onConfirm;
    private long holdAt, noticeAt;
    private String title = "", meta = "", body = "", notice = "";
    private Paint titlePaint, metaPaint, bodyPaint, buttonPaint, noticePaint;
    private float paintK;

    DeleteDialog(View view, Listener listener) {
        this.view = view;
        this.listener = listener;
        ring = new FocusRing(view);
    }

    boolean isOpen() {
        return open;
    }

    boolean isActive() {
        return open || noticeAt != 0 && SystemClock.uptimeMillis() - noticeAt < NOTICE;
    }

    void open(String title, String meta, String body) {
        this.title = title;
        this.meta = meta;
        this.body = body;
        open = true;
        working = false;
        onConfirm = false;
        holdAt = 0;
        ring.snap();
    }

    void close() {
        open = false;
        working = false;
        stopHold();
    }

    void working() {
        working = true;
        stopHold();
    }

    void notice(String text) {
        close();
        notice = text;
        noticeAt = SystemClock.uptimeMillis();
        view.postInvalidateDelayed(NOTICE + 50);
    }

    private void stopHold() {
        holdAt = 0;
        view.removeCallbacks(fire);
    }

    private void finishHold() {
        if (!open || working || holdAt == 0) return;
        stopHold();
        listener.confirmed();
        view.invalidate();
    }

    boolean onKey(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down && !working && holdAt == 0) onConfirm = !onConfirm;
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (working) break;
                if (down && e.getRepeatCount() == 0) {
                    if (onConfirm) {
                        holdAt = SystemClock.uptimeMillis();
                        view.postDelayed(fire, HOLD);
                    }
                } else if (!down) {
                    if (!onConfirm && !e.isCanceled()) close();
                    else stopHold();
                }
                break;
            case KeyEvent.KEYCODE_BACK:
                if (!down && !working && !e.isCanceled()) close();
                break;
            default:
                break;
        }
        view.invalidate();
        return true;
    }

    void draw(Context context, Canvas c, float w, float h, float k, Paint fill, Paint stroke) {
        if (open) drawDialog(context, c, w, h, k, fill, stroke);
        else if (noticeAt != 0) drawNotice(c, w, h, k, fill, stroke);
    }

    private void paints(float k) {
        if (titlePaint != null && paintK == k) return;
        paintK = k;
        titlePaint = Ui.text(Ui.light, 40 * k, INK);
        metaPaint = Ui.text(Ui.regular, Ui.LABEL * k, 0xB8F2F2F2);
        bodyPaint = Ui.text(Ui.regular, Ui.LABEL * k, 0xB8F2F2F2);
        buttonPaint = Ui.text(Ui.medium, Ui.BODY * k, INK);
        buttonPaint.setTextAlign(Paint.Align.CENTER);
        noticePaint = Ui.text(Ui.medium, Ui.BODY * k, INK);
        noticePaint.setTextAlign(Paint.Align.CENTER);
    }

    private void drawDialog(Context context, Canvas c, float w, float h, float k, Paint fill, Paint stroke) {
        paints(k);
        fill.setColor(0xCC000000);
        c.drawRect(0, 0, w, h, fill);
        float width = WIDTH * k, pad = PAD * k, textW = width - 2 * pad;
        java.util.List<String> lines = wrap(body, bodyPaint, textW);
        float lineH = bodyPaint.getTextSize() * 1.5f;
        float height = pad + 44 * k + 18 * k + titlePaint.getTextSize() + 12 * k + metaPaint.getTextSize() + 22 * k + lines.size() * lineH + 36 * k + BUTTON * k + pad;
        box.set((w - width) / 2, (h - height) / 2, (w + width) / 2, (h + height) / 2);
        fill.setColor(0xF01A1A1A);
        c.drawRoundRect(box, Glass.PLATE_RADIUS * k, Glass.PLATE_RADIUS * k, fill);
        Glass.plate(c, box, k, fill, stroke);
        float x = box.left + pad, y = box.top + pad;
        Icons.draw(context, c, R.drawable.icon_trash, x + 22 * k, y + 22 * k, 44 * k, RED);
        y += 44 * k + 18 * k + titlePaint.getTextSize() * 0.8f;
        c.drawText(Ui.fit(title, titlePaint, textW), x, y, titlePaint);
        y += 12 * k + metaPaint.getTextSize() * 0.9f;
        c.drawText(Ui.fit(meta, metaPaint, textW), x, y, metaPaint);
        y += 22 * k + bodyPaint.getTextSize() * 0.9f;
        for (String line : lines) {
            c.drawText(line, x, y, bodyPaint);
            y += lineH;
        }
        float top = box.bottom - pad - BUTTON * k, half = (textW - GAP * k) / 2;
        cancel.set(x, top, x + half, top + BUTTON * k);
        confirm.set(x + half + GAP * k, top, x + textW, top + BUTTON * k);
        drawButton(c, cancel, "Cancel", false, k, fill, stroke);
        drawButton(c, confirm, working ? "Moving…" : "Hold OK to delete", true, k, fill, stroke);
        ring.focus(c, onConfirm ? confirm : cancel, 40 * k, "delete", k, fill, stroke);
        if (holdAt != 0) view.postInvalidateOnAnimation();
    }

    private void drawButton(Canvas c, RectF b, String label, boolean danger, float k, Paint fill, Paint stroke) {
        fill.setColor(Glass.FILL);
        c.drawRoundRect(b, 40 * k, 40 * k, fill);
        if (danger && holdAt != 0) {
            float p = Math.min(1f, (SystemClock.uptimeMillis() - holdAt) / (float) HOLD);
            c.save();
            c.clipRect(b.left, b.top, b.left + b.width() * p, b.bottom);
            fill.setColor(0x66FF6B66);
            c.drawRoundRect(b, 40 * k, 40 * k, fill);
            c.restore();
        }
        buttonPaint.setColor(danger ? RED : INK);
        c.drawText(Ui.fit(label, buttonPaint, b.width() - 32 * k), b.centerX(), b.centerY() + Ui.halfCap(buttonPaint), buttonPaint);
    }

    private void drawNotice(Canvas c, float w, float h, float k, Paint fill, Paint stroke) {
        long age = SystemClock.uptimeMillis() - noticeAt;
        if (age >= NOTICE) {
            noticeAt = 0;
            return;
        }
        paints(k);
        float width = Math.min(w - 2 * 112 * k, noticePaint.measureText(notice) + 72 * k);
        float alpha = age < 200 ? age / 200f : age > NOTICE - 300 ? (NOTICE - age) / 300f : 1f;
        pill.set((w - width) / 2, h - 190 * k, (w + width) / 2, h - 110 * k);
        fill.setColor(Ui.alpha(0xEB1A1A1A, alpha));
        c.drawRoundRect(pill, 40 * k, 40 * k, fill);
        noticePaint.setAlpha((int) (255 * alpha));
        c.drawText(Ui.fit(notice, noticePaint, width - 48 * k), pill.centerX(), pill.centerY() + Ui.halfCap(noticePaint), noticePaint);
        noticePaint.setAlpha(255);
        view.postInvalidateOnAnimation();
    }

    private static java.util.List<String> wrap(String text, Paint paint, float max) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = line.length() == 0 ? word : line + " " + word;
            if (line.length() > 0 && paint.measureText(next) > max) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(next);
            }
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }
}
