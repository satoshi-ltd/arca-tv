package com.satoshilimited.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

final class Header {
    static final int INK = 0xFFF2F2F2, MUTE = 0xB8FFFFFF;
    private static final float LEFT = 112, RIGHT = 1808, CLOCK_BASE = 180, FACTS_BASE = 231, EDGE_GAP = 28;

    private static final String UNIT = "µg/m³";
    private static final int DIVIDER = 0x3DFFFFFF;
    private static final float CONDITION_BASE = 168;

    private final float k;
    private final Context context;
    private final Paint divider = new Paint();
    private final Paint clock, meridiem, date, temp, forecast, facts, factsMute;
    private final Path path = new Path();
    private final RectF oval = new RectF();

    Header(float k) {
        this(k, null);
    }

    Header(float k, Context context) {
        this.k = k;
        this.context = context;
        clock = Ui.numbers(Ui.light, 112 * k, INK);
        clock.setLetterSpacing(-0.035f);
        temp = Ui.numbers(Ui.light, 112 * k, INK);
        temp.setLetterSpacing(-0.035f);
        meridiem = Ui.text(Ui.regular, 36 * k, MUTE);
        date = Ui.text(Ui.regular, 24 * k, MUTE);
        forecast = Ui.numbers(Ui.regular, Ui.LABEL * k, INK);
        forecast.setTextAlign(Paint.Align.RIGHT);
        facts = Ui.numbers(Ui.regular, 24 * k, INK);
        factsMute = Ui.numbers(Ui.regular, 24 * k, MUTE);
    }

    float weatherLeft = Float.NaN;
    private int outdoorValue = -1;
    private String outdoorText = "";

    void draw(Canvas c, String time, String ampm, String day, HomeData.Weather wx, String inside) {
        float left = LEFT * k;
        c.drawText(time, left - Ui.leftBearing(clock, time), CLOCK_BASE * k, clock);
        c.drawText(ampm, left + clock.measureText(time) + 10 * k, CLOCK_BASE * k, meridiem);
        c.drawText(day, left, FACTS_BASE * k, date);
        if (Prefs.weather() && !Double.isNaN(wx.temp)) weatherLeft = drawWeather(c, wx, inside);
        else weatherLeft = inside == null ? Float.NaN : drawAir(c, RIGHT * k, -1, inside);
    }

    private float drawWeather(Canvas c, HomeData.Weather wx, String inside) {
        float right = RIGHT * k;
        String degrees = HomeData.degrees(wx.temp);
        float tempWidth = temp.measureText(degrees);
        c.drawText(degrees, right - tempWidth, CLOCK_BASE * k, temp);
        float x = right - tempWidth - EDGE_GAP * k, leftmost = right - tempWidth;
        String condition = HomeData.condition(wx.dayCode, true);
        if (!condition.isEmpty()) {
            c.drawText(condition, x, CONDITION_BASE * k, forecast);
            x -= forecast.measureText(condition) + 12 * k + 32 * k;
            icon(c, weatherIcon(wx.dayCode, wx.isDay), x + 16 * k, (CONDITION_BASE - 9.5f) * k, 32 * k);
            leftmost = Math.min(leftmost, x);
        }
        float line = right;
        if (!Double.isNaN(wx.max)) line = drawRange(c, right, FACTS_BASE * k, HomeData.degrees(wx.max), HomeData.degrees(wx.min));
        if (wx.pm25 >= 0 || inside != null) {
            if (line < right) {
                line -= 28 * k + 1.5f * k;
                divider.setColor(DIVIDER);
                c.drawRect(line, (FACTS_BASE - 9 - 14) * k, line + 1.5f * k, (FACTS_BASE - 9 + 14) * k, divider);
                line -= 28 * k;
            }
            line = drawAir(c, line, wx.pm25, inside);
        }
        return Math.min(leftmost, line);
    }

    private void icon(Canvas c, int res, float cx, float cy, float size) {
        if (context != null) Icons.draw(context, c, res, cx, cy, size, MUTE);
    }

    private static int weatherIcon(int code, boolean day) {
        if (code == 0) return day ? R.drawable.icon_clear_day : R.drawable.icon_clear_night;
        if (code == 1 || code == 2) return day ? R.drawable.icon_partly_day : R.drawable.icon_partly_night;
        if (code == 3) return R.drawable.icon_cloudy;
        if (code == 45 || code == 48) return R.drawable.icon_fog;
        if (code >= 51 && code <= 57) return R.drawable.icon_drizzle;
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return R.drawable.icon_snow;
        if (code >= 95) return R.drawable.icon_thunder;
        return R.drawable.icon_rain;
    }

    private float drawRange(Canvas c, float edge, float base, String high, String low) {
        float x = edge;
        forecast.setTextAlign(Paint.Align.LEFT);
        x -= forecast.measureText(low);
        c.drawText(low, x, base, forecast);
        x -= 28 * k;
        icon(c, R.drawable.icon_arrow_down, x + 10 * k, base - 9 * k, 26 * k);
        x -= 18 * k + forecast.measureText(high);
        c.drawText(high, x, base, forecast);
        x -= 28 * k;
        icon(c, R.drawable.icon_arrow_up, x + 10 * k, base - 9 * k, 26 * k);
        forecast.setTextAlign(Paint.Align.RIGHT);
        return x + 2 * k;
    }

    private float drawAir(Canvas c, float right, int outdoor, String inside) {
        float base = FACTS_BASE * k, cy = base - 9 * k, x = right;
        if (inside != null) {
            x = drawReading(c, x, base, inside);
            x -= 10 * k + 28 * k;
            icon(c, R.drawable.icon_house, x + 14 * k, cy, 28 * k);
            if (outdoor >= 0) x -= 28 * k;
        }
        if (outdoor >= 0) {
            if (outdoor != outdoorValue) {
                outdoorValue = outdoor;
                outdoorText = String.valueOf(outdoor);
            }
            x = drawReading(c, x, base, outdoorText);
            x -= 10 * k + 28 * k;
            icon(c, R.drawable.icon_wind, x + 14 * k, cy, 28 * k);
        }
        return x;
    }

    private float drawReading(Canvas c, float x, float base, String value) {
        if (!Character.isDigit(value.charAt(0))) {
            x -= factsMute.measureText(value);
            c.drawText(value, x, base, factsMute);
            return x;
        }
        x -= factsMute.measureText(UNIT);
        c.drawText(UNIT, x, base, factsMute);
        x -= 6 * k + facts.measureText(value);
        c.drawText(value, x, base, facts);
        return x;
    }

    private Paint screenTitle, screenMeridiem, tagName;
    private final Paint screenDot = new Paint(Paint.ANTI_ALIAS_FLAG);

    private void ensureScreenPaints() {
        if (screenTitle != null) return;
        screenTitle = Ui.numbers(Ui.light, 72 * k, INK);
        screenTitle.setLetterSpacing(-0.03f);
        screenMeridiem = Ui.text(Ui.regular, 28 * k, MUTE);
        tagName = Ui.text(Ui.semibold, Ui.TAG * k, 0xFF141414);
        tagName.setLetterSpacing(0.06f);
    }

    void drawLockup(Canvas c, String tag) {
        drawLockupAt(c, tag, LEFT * k, 101 * k, 1f);
    }

    void drawLockupAt(Canvas c, String tag, float left, float centre, float scale) {
        ensureScreenPaints();
        float u = k * scale;
        tagName.setTextSize(Ui.TAG * u);
        drawMark(c, left, centre - 13 * u, 26 * u);
        float x = left + 24.2f * u + 10 * u;
        float tagWidth = tagName.measureText(tag) + 20 * u, tagHeight = 30 * u;
        oval.set(x, centre - tagHeight / 2, x + tagWidth, centre + tagHeight / 2);
        markPaint.setColor(INK);
        c.drawRoundRect(oval, 8 * u, 8 * u, markPaint);
        c.drawText(tag, x + 10 * u, centre + Ui.halfCap(tagName), tagName);
        tagName.setTextSize(Ui.TAG * k);
    }

    void drawScreen(Canvas c, String title, String time, String ampm, String day) {
        drawScreen(c, title, time, ampm, day, "TV");
    }

    void drawScreen(Canvas c, String title, String time, String ampm, String day, String tag) {
        drawScreen(c, title, time, ampm, day, tag, 0);
    }

    void drawScreen(Canvas c, String title, String time, String ampm, String day, int dot) {
        drawScreen(c, title, time, ampm, day, "TV", dot);
    }

    private void drawScreen(Canvas c, String title, String time, String ampm, String day, String tag, int dot) {
        ensureScreenPaints();
        float left = LEFT * k, right = RIGHT * k, titleBase = 184 * k;
        float centre = 84 * k + 17 * k;
        float crumbBase = centre + Ui.halfCap(date);
        drawLockup(c, tag);
        date.setTextAlign(Paint.Align.RIGHT);
        c.drawText(day, right, crumbBase, date);
        date.setTextAlign(Paint.Align.LEFT);
        float inset = dot == 0 ? 0 : 64 * k;
        if (dot != 0) {
            screenDot.setColor(dot);
            c.drawCircle(left + 20 * k, titleBase - 26 * k, 20 * k, screenDot);
        }
        c.drawText(title, left + inset - Ui.leftBearing(screenTitle, title), titleBase, screenTitle);
        float ampmWidth = screenMeridiem.measureText(ampm);
        c.drawText(ampm, right - ampmWidth, titleBase, screenMeridiem);
        float timeWidth = screenTitle.measureText(time);
        c.drawText(time, right - ampmWidth - 8 * k - timeWidth, titleBase, screenTitle);
    }

    private final Paint markPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    void drawMarkCentered(Canvas c, float cx, float cy, float size) {
        drawMark(c, cx - size * 240 / 258 / 2, cy - size / 2, size);
    }

    private void drawMark(Canvas c, float left, float top, float height) {
        float u = height / 258f;
        path.rewind();
        path.moveTo(left, top + 258 * u);
        path.lineTo(left, top + 120 * u);
        oval.set(left, top, left + 240 * u, top + 240 * u);
        path.arcTo(oval, 180, 180);
        path.lineTo(left + 240 * u, top + 258 * u);
        path.lineTo(left + 172 * u, top + 258 * u);
        path.lineTo(left + 172 * u, top + 120 * u);
        oval.set(left + 68 * u, top + 68 * u, left + 172 * u, top + 172 * u);
        path.arcTo(oval, 0, -180);
        path.lineTo(left + 68 * u, top + 258 * u);
        path.close();
        markPaint.setColor(INK);
        c.drawPath(path, markPaint);
        markPaint.setColor(0x66FFFFFF);
        oval.set(left + 90 * u, top + 158 * u, left + 150 * u, top + 258 * u);
        c.drawRoundRect(oval, 4 * u, 4 * u, markPaint);
    }
}
