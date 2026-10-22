package com.satoshilimited.tv;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

final class Glass {
    private Glass() {}

    static final int FILL = 0x1AFFFFFF, EDGE = 0x24FFFFFF, GAP = 0x59000000, FOCUS = 0x24FFFFFF, HIGHLIGHT = 0x1FFFFFFF;
    static final float ROW_H = 80, ROW_GAP = 6, ROW_RADIUS = 24, PLATE_RADIUS = 44, PLATE_INSET = 20;
    private static final RectF EDGE_BOX = new RectF();

    static void plate(Canvas c, RectF box, float radius, float k, Paint fill, Paint stroke) {
        fill.setColor(FILL);
        c.drawRoundRect(box, radius, radius, fill);
        stroke.setStrokeWidth(1.5f * k);
        stroke.setColor(EDGE);
        EDGE_BOX.set(box.left + 0.75f * k, box.top + 0.75f * k, box.right - 0.75f * k, box.bottom - 0.75f * k);
        c.drawRoundRect(EDGE_BOX, radius - 0.75f * k, radius - 0.75f * k, stroke);
        stroke.setStrokeWidth(k);
        stroke.setColor(HIGHLIGHT);
        c.drawLine(box.left + radius, box.top + 1.5f * k, box.right - radius, box.top + 1.5f * k, stroke);
    }

    static void plate(Canvas c, RectF box, float k, Paint fill, Paint stroke) {
        plate(c, box, PLATE_RADIUS * k, k, fill, stroke);
    }

    static void row(Canvas c, RectF box, float k, Paint fill, Paint stroke, boolean focused) {
        if (focused) focus(c, box, ROW_RADIUS * k, k, fill, stroke);
    }

    static void focus(Canvas c, RectF box, float radius, float k, Paint fill, Paint stroke) {
        fill.setColor(FOCUS);
        c.drawRoundRect(box, radius, radius, fill);
        ring(c, box, radius, k, stroke);
    }

    /** The double ring alone (dark translucent gap + white outer), for tiles/covers that already draw their own fill. */
    static void ring(Canvas c, RectF box, float radius, float k, Paint stroke) {
        stroke.setStrokeWidth(6 * k);
        stroke.setColor(GAP);
        EDGE_BOX.set(box.left - 3 * k, box.top - 3 * k, box.right + 3 * k, box.bottom + 3 * k);
        c.drawRoundRect(EDGE_BOX, radius + 3 * k, radius + 3 * k, stroke);
        stroke.setStrokeWidth(3 * k);
        stroke.setColor(0xFFF2F2F2);
        EDGE_BOX.set(box.left - 7.5f * k, box.top - 7.5f * k, box.right + 7.5f * k, box.bottom + 7.5f * k);
        c.drawRoundRect(EDGE_BOX, radius + 7.5f * k, radius + 7.5f * k, stroke);
    }

    static void ringOval(Canvas c, RectF box, float k, Paint stroke) {
        stroke.setStrokeWidth(6 * k);
        stroke.setColor(GAP);
        EDGE_BOX.set(box.left - 3 * k, box.top - 3 * k, box.right + 3 * k, box.bottom + 3 * k);
        c.drawOval(EDGE_BOX, stroke);
        stroke.setStrokeWidth(3 * k);
        stroke.setColor(0xFFF2F2F2);
        EDGE_BOX.set(box.left - 7.5f * k, box.top - 7.5f * k, box.right + 7.5f * k, box.bottom + 7.5f * k);
        c.drawOval(EDGE_BOX, stroke);
    }
}
