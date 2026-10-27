package com.satoshilimited.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;

final class HomeHero {
    private static final long FADE = 200;
    private static final int INK = 0xFFF2F2F2, MUTE = 0xB8FFFFFF;
    private static final float LEFT = 112, TITLE = 376, META = 422, OVERVIEW = 462;

    private final Context context;
    private final Runnable changed;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();
    private Paint title, meta, badge, overview;
    private float k = 1;
    private int w;

    private String id;
    private long since = -FADE;
    private String titleText = "", overviewText = "";
    private VideoLibrary.Item metaItem;
    private boolean metaShow;
    private int minutes;

    HomeHero(Context context, Runnable changed) {
        this.context = context;
        this.changed = changed;
        stroke.setStyle(Paint.Style.STROKE);
    }

    void size(int w, int h, float k) {
        this.w = w;
        this.k = k;
        title = Ui.text(Ui.light, 72 * k, INK);
        title.setLetterSpacing(-0.03f);
        meta = Ui.text(Ui.regular, 24 * k, MUTE);
        badge = Ui.text(Ui.semibold, 18 * k, INK);
        overview = Ui.text(Ui.regular, 24 * k, MUTE);
    }

    void focus(Watching.Card card) {
        String next = card == null ? null : card.id;
        if (next == null ? id == null : next.equals(id)) return;
        id = next;
        since = SystemClock.uptimeMillis();
        metaItem = null;
        titleText = overviewText = "";
        if (card != null) describe(card);
        changed.run();
    }

    private void describe(Watching.Card card) {
        titleText = card.title == null ? "" : card.title;
        VideoLibrary lib = VideoLibrary.current();
        VideoLibrary.Item it = lib == null ? null : lib.find(card.id);
        if (it == null) return;
        VideoLibrary.Item show = it.isEpisode() ? lib.showOf(it) : null;
        metaItem = show != null ? show : it;
        metaShow = it.isShow();
        minutes = it.isShow() ? 0 : it.minutes();
        String text = !it.overview.isEmpty() ? it.overview : show != null ? show.overview : "";
        overviewText = text.replace('\n', ' ').trim();
    }

    private float progress() {
        return Ui.clamp((SystemClock.uptimeMillis() - since) / (float) FADE);
    }

    boolean fading() {
        return SystemClock.uptimeMillis() - since < FADE;
    }

    void draw(Canvas c) {
        if (title == null) return;
        float t = progress();
        if (id == null) return;
        int save = c.saveLayerAlpha(0, 0, w, OVERVIEW * k + 20 * k, Math.round(t * 255));
        float x = LEFT * k;
        c.drawText(Ui.fit(titleText, title, 1100 * k), x - Ui.leftBearing(title, titleText), TITLE * k, title);
        if (metaItem != null) MetaLine.draw(context, c, meta, badge, stroke, tmp, k, metaItem, metaShow, minutes, x, META * k, INK);
        if (!overviewText.isEmpty()) c.drawText(Ui.fit(overviewText, overview, 1000 * k), x, OVERVIEW * k, overview);
        c.restoreToCount(save);
    }
}
