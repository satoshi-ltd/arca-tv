package com.satoshilimited.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class MetaLine {
    private MetaLine() {}

    static void draw(Context context, Canvas c, Paint meta, Paint badge, Paint stroke, RectF tmp, float k, VideoLibrary.Item it, boolean show, int minutes, float x, float base, int starColor) {
        float cursor = x, cy = base - Ui.halfCap(meta);
        boolean first = true;
        if (it.rating > 0) {
            String text = String.format(Locale.US, "%.1f", it.rating);
            Icons.draw(context, c, R.drawable.icon_star, cursor + 12 * k, cy, 24 * k, starColor);
            c.drawText(text, cursor + 34 * k, base, meta);
            cursor += 34 * k + meta.measureText(text);
            first = false;
        }
        if (!it.certification.isEmpty()) {
            if (!first) cursor = separator(c, meta, cursor, base);
            float width = badge.measureText(it.certification) + 20 * k;
            tmp.set(cursor, cy - 15 * k, cursor + width, cy + 15 * k);
            stroke.setStrokeWidth(2 * k);
            stroke.setColor(0x99FFFFFF);
            c.drawRoundRect(tmp, 6 * k, 6 * k, stroke);
            c.drawText(it.certification, tmp.left + 10 * k, tmp.centerY() + Ui.halfCap(badge), badge);
            cursor += width;
            first = false;
        }
        List<String> parts = new ArrayList<>();
        if (it.year > 0) parts.add(String.valueOf(it.year));
        if (show) {
            int n = it.seasons.size();
            if (n > 0) parts.add(n + (n == 1 ? " season" : " seasons"));
        } else if (minutes > 0) {
            parts.add(VideoLibrary.length(minutes));
        }
        for (String part : parts) {
            if (!first) cursor = separator(c, meta, cursor, base);
            c.drawText(part, cursor, base, meta);
            cursor += meta.measureText(part);
            first = false;
        }
    }

    private static float separator(Canvas c, Paint meta, float x, float base) {
        c.drawText(" · ", x, base, meta);
        return x + meta.measureText(" · ");
    }
}
