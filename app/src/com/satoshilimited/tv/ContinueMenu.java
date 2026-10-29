package com.satoshilimited.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

final class ContinueMenu {
    static final int DETAILS = 0, REMOVE = 1, DELETE = 2, PLAY = 3, OPEN = 4;
    private static final float WIDTH = 500, ROW = 80, GAP = 6, INSET = 20;

    private final RectF box = new RectF(), row = new RectF();
    private int[] actions = {DETAILS, REMOVE};
    private int focus;
    private boolean open;
    private Paint text;
    private float textK;
    private final FocusRing ring;

    ContinueMenu(View view) {
        ring = new FocusRing(view);
    }

    boolean isOpen() {
        return open;
    }

    void open(int... actions) {
        this.actions = actions;
        focus = 0;
        open = true;
        ring.snap();
    }

    void close() {
        open = false;
    }

    void move(int dy) {
        focus = Math.max(0, Math.min(actions.length - 1, focus + dy));
    }

    int choose() {
        open = false;
        return actions[focus];
    }

    void draw(Context context, Canvas c, RectF anchor, float k, int screenH, Paint fill, Paint stroke) {
        if (!open) return;
        if (text == null || textK != k) {
            text = Ui.text(Ui.medium, Ui.BODY * k, 0xFFF2F2F2);
            textK = k;
        }
        float height = 2 * INSET + actions.length * ROW + (actions.length - 1) * GAP;
        float left = Math.min(anchor.left, c.getWidth() - (WIDTH + 112) * k), top = anchor.bottom + 24 * k;
        if (top + height * k > screenH - 24 * k) top = anchor.top - 24 * k - height * k;
        box.set(left, top, left + WIDTH * k, top + height * k);
        fill.setColor(0xE61A1A1A);
        c.drawRoundRect(box, Glass.PLATE_RADIUS * k, Glass.PLATE_RADIUS * k, fill);
        Glass.plate(c, box, k, fill, stroke);
        for (int i = 0; i < actions.length; i++) {
            float y = box.top + (INSET + i * (ROW + GAP)) * k;
            row.set(box.left + INSET * k, y, box.right - INSET * k, y + ROW * k);
            if (i == focus) ring.row(c, row, 0, k, fill, stroke);
            int ink = actions[i] == DELETE ? 0xFFFF6B66 : 0xFFF2F2F2;
            text.setColor(ink);
            Icons.draw(context, c, icon(actions[i]), row.left + 40 * k, row.centerY(), 30 * k, ink);
            c.drawText(label(actions[i]), row.left + 76 * k, row.centerY() + Ui.halfCap(text), text);
            text.setColor(0xFFF2F2F2);
        }
    }

    private static int icon(int action) {
        switch (action) {
            case DETAILS: return R.drawable.icon_film;
            case DELETE: return R.drawable.icon_trash;
            case PLAY: return R.drawable.icon_play;
            case OPEN: return R.drawable.icon_photos;
            default: return R.drawable.icon_eye_off;
        }
    }

    private static String label(int action) {
        switch (action) {
            case DETAILS: return "Details";
            case DELETE: return "Delete\u2026";
            case PLAY: return "Play";
            case OPEN: return "Open";
            default: return "Remove from Watch next";
        }
    }
}
