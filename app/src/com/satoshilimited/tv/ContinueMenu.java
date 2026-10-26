package com.satoshilimited.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

final class ContinueMenu {
    static final int DETAILS = 0, REMOVE = 1;
    private static final float WIDTH = 500, ROW = 80, GAP = 6, INSET = 20;

    private final RectF box = new RectF(), row = new RectF();
    private final int[] actions = {DETAILS, REMOVE};
    private int focus;
    private boolean open;
    private Paint text;
    private float textK;

    boolean isOpen() {
        return open;
    }

    void open() {
        focus = 0;
        open = true;
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
            text = Ui.text(Ui.medium, 26 * k, 0xFFF2F2F2);
            textK = k;
        }
        float height = 2 * INSET + actions.length * ROW + (actions.length - 1) * GAP;
        float left = anchor.left, top = anchor.bottom + 24 * k;
        if (top + height * k > screenH - 24 * k) top = anchor.top - 24 * k - height * k;
        box.set(left, top, left + WIDTH * k, top + height * k);
        fill.setColor(0xE61A1A1A);
        c.drawRoundRect(box, Glass.PLATE_RADIUS * k, Glass.PLATE_RADIUS * k, fill);
        Glass.plate(c, box, k, fill, stroke);
        for (int i = 0; i < actions.length; i++) {
            float y = box.top + (INSET + i * (ROW + GAP)) * k;
            row.set(box.left + INSET * k, y, box.right - INSET * k, y + ROW * k);
            if (i == focus) Glass.row(c, row, k, fill, stroke, true);
            Icons.draw(context, c, actions[i] == DETAILS ? R.drawable.icon_film : R.drawable.icon_eye_off, row.left + 40 * k, row.centerY(), 30 * k, 0xFFF2F2F2);
            c.drawText(actions[i] == DETAILS ? "Details" : "Remove from Watch next", row.left + 76 * k, row.centerY() + Ui.halfCap(text), text);
        }
    }
}
