package com.satoshilimited.tv;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

public class DomoActivity extends Activity {
    private HouseView view;
    private IdleSaver idle;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        view = new HouseView(this);
        idle = new IdleSaver(this, view);
        setContentView(idle.view());
    }

    @Override protected void onResume() {
        super.onResume();
        view.resume();
        idle.resume();
    }

    @Override protected void onPause() {
        idle.pause();
        view.pause();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (idle.key(event)) return true;
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    static final class HouseView extends View implements ArcaDomo.Listener {
        private final FocusRing focusRing = new FocusRing(this);
        private static final int INK = 0xFFF2F2F2, MUTE = 0xB8FFFFFF, AMBER = 0xFFF0B45C, TRACK = 0x24FFFFFF, PAST = 0x73FFFFFF, BONE = 0x14FFFFFF, DARK = 0xFF141414;
        private static final int[] LEVEL_COLORS = {0xFFCFEAF2, 0xFFA8D8B6, 0xFFE3D98C, 0xFFE8B070, 0xFFE08A6A, 0xFFD0606A};
        private static final float LEFT = 112, TOP = 290, CARD_W = 544, CARD_H = 346, GAP = 32, PAD_X = 36;
        private static final float PANEL_L = 1008, PANEL_R = 1808, PILL_H = 60, FAN_ROW_H = 104;
        private static final int COLS = 3, VISIBLE_ROWS = 2, ROOMS = 0, DEVICE = 1;
        private static final int R_POWER = 0, R_MODE = 1, R_FAN = 2, V_START = 0, V_STOP = 1, V_DOCK = 2;
        private static final String[] POWER = {"On", "Off"}, MODES = {"Auto", "Sleep", "Favorite"}, MODE_KEYS = {"auto", "sleep", "favorite"};
        private static final String[] VACUUM_ROWS = {"Start", "Stop", "Dock"}, VACUUM_NOTES = {"Clean the whole house", "Stop where it is", "Go back and charge"};
        private static final String[] VACUUM_ACTIONS = {"start", "stop", "dock"};
        private static final int[] VACUUM_ICONS = {R.drawable.icon_play, R.drawable.icon_stop, R.drawable.icon_dock};
        private static final String[] AGES = new String[181], NUMBERS = new String[15];
        static {
            for (int i = 0; i < NUMBERS.length; i++) NUMBERS[i] = String.valueOf(i);
        }

        private final DomoActivity activity;
        private final ArcaDomo domo = ArcaDomo.get();
        private final Backdrop backdrop = new Backdrop();
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG), spin = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF(), tmp = new RectF();
        private int screen = ROOMS, focus, firstRow, row, col;
        private String deviceId = "";
        private String pending, pendingLabel = "", note = "", lastReadingLine = "";
        private int pendingIndex = -1;
        private long noteUntil;
        private boolean running, okDown, backDown;
        private int w, h;
        private float k = 1;
        private Header header;
        private Paint name32, status24, big112, unit24, level28, small24, pill24, pct40;
        private Paint huge220, unit32, level44, facts30, label30, sub24, value30, note26, pillOn, pillOff, fan36;
        private Paint word160, sub30, value36, rowTitle32, rowNote26, title34, msg24, button28, skeletonTitle;

        HouseView(DomoActivity a) {
            super(a);
            activity = a;
            stroke.setStyle(Paint.Style.STROKE);
            spin.setStyle(Paint.Style.STROKE);
            spin.setStrokeCap(Paint.Cap.ROUND);
            setFocusable(true);
            setContentDescription("arca domo");
        }

        void resume() {
            running = true;
            showWash();
            domo.start(this);
            invalidate();
        }

        void pause() {
            running = false;
            okDown = backDown = false;
            domo.stop(this);
        }

        private void showWash() {
            int color = domo.washColor();
            backdrop.showWash(color == ArcaDomo.RED ? "domo-red" : color == ArcaDomo.AMBER ? "domo-amber" : "domo", color, 0.5f);
        }

        @Override public void onDomo() {
            showWash();
            long last = domo.lastReading();
            lastReadingLine = last > 0 ? "Last reading at " + Ui.time(last).toLowerCase(Locale.US) + "." : "";
            List<ArcaDomo.Device> list = domo.devices();
            focus = Math.max(0, Math.min(focus, list.size() - 1));
            if (screen == DEVICE && current() == null) screen = ROOMS;
            if (screen == DEVICE) clampDeviceFocus();
            invalidate();
        }

        private ArcaDomo.Device current() {
            return domo.device(deviceId);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            header = new Header(k);
            name32 = Ui.text(Ui.medium, 32 * k, INK);
            status24 = Ui.text(Ui.regular, 24 * k, MUTE);
            status24.setTextAlign(Paint.Align.RIGHT);
            big112 = Ui.numbers(Ui.light, 112 * k, INK);
            big112.setLetterSpacing(-0.036f);
            unit24 = Ui.text(Ui.regular, 24 * k, MUTE);
            level28 = Ui.text(Ui.medium, 28 * k, INK);
            small24 = Ui.numbers(Ui.regular, 24 * k, MUTE);
            pill24 = Ui.numbers(Ui.medium, 24 * k, INK);
            pct40 = Ui.text(Ui.light, 40 * k, MUTE);
            huge220 = Ui.numbers(Ui.light, 220 * k, INK);
            huge220.setLetterSpacing(-0.036f);
            unit32 = Ui.text(Ui.regular, 32 * k, MUTE);
            level44 = Ui.text(Ui.regular, 44 * k, INK);
            facts30 = Ui.numbers(Ui.regular, Ui.BODY * k, INK);
            label30 = Ui.text(Ui.medium, Ui.BODY * k, INK);
            sub24 = Ui.text(Ui.regular, 24 * k, MUTE);
            value30 = Ui.numbers(Ui.regular, Ui.BODY * k, INK);
            value30.setTextAlign(Paint.Align.RIGHT);
            note26 = Ui.text(Ui.regular, Ui.LABEL * k, MUTE);
            pillOn = Ui.text(Ui.semibold, Ui.LABEL * k, DARK);
            pillOff = Ui.text(Ui.medium, Ui.LABEL * k, INK);
            fan36 = Ui.numbers(Ui.regular, 36 * k, INK);
            fan36.setTextAlign(Paint.Align.CENTER);
            word160 = Ui.text(Ui.light, 160 * k, INK);
            word160.setLetterSpacing(-0.0375f);
            sub30 = Ui.text(Ui.regular, Ui.BODY * k, MUTE);
            value36 = Ui.numbers(Ui.regular, 36 * k, INK);
            rowTitle32 = Ui.text(Ui.medium, 32 * k, INK);
            rowNote26 = Ui.text(Ui.regular, Ui.LABEL * k, MUTE);
            rowNote26.setTextAlign(Paint.Align.RIGHT);
            title34 = Ui.text(Ui.medium, 34 * k, INK);
            title34.setTextAlign(Paint.Align.CENTER);
            msg24 = Ui.text(Ui.regular, 24 * k, MUTE);
            msg24.setTextAlign(Paint.Align.CENTER);
            button28 = Ui.text(Ui.medium, 28 * k, INK);
            button28.setTextAlign(Paint.Align.CENTER);
            skeletonTitle = Ui.text(Ui.medium, 28 * k, INK);
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            c.drawColor(0xFF0A0A0A);
            backdrop.draw(c, w, h, 1f);
            long now = System.currentTimeMillis(), tick = SystemClock.elapsedRealtime();
            ArcaDomo.Device d = screen == DEVICE ? current() : null;
            header.drawScreen(c, d == null ? "Devices" : d.name, Ui.clock(now), Ui.meridiem(now), Ui.date(now), "DOMO");
            if (d != null) {
                if (d.vacuum()) drawVacuum(c, d, tick);
                else drawPurifier(c, d, tick);
            } else {
                drawRooms(c, tick);
            }
            if (running) postInvalidateDelayed(pending != null ? 16 : 50);
        }

        private boolean showsMessage() {
            int s = domo.status();
            return s == ArcaDomo.UNREACHABLE || s == ArcaDomo.REJECTED || s == ArcaDomo.READY && domo.devices().isEmpty();
        }

        private void drawRooms(Canvas c, long tick) {
            int s = domo.status();
            if (s == ArcaDomo.UNREACHABLE) {
                drawMessage(c, R.drawable.icon_offline, "Can't reach the home server", null, "Trying again by itself.", lastReadingLine, true);
                return;
            }
            if (s == ArcaDomo.REJECTED) {
                drawMessage(c, R.drawable.icon_key, "The home server didn't", "accept this TV", "Check the home server key", "in this TV's setup.", false);
                return;
            }
            List<ArcaDomo.Device> list = domo.devices();
            if (s == ArcaDomo.LOADING && list.isEmpty()) {
                for (int i = 0; i < COLS * VISIBLE_ROWS; i++) {
                    cardBox(i, 0, rect);
                    drawSkeleton(c, rect);
                }
                return;
            }
            if (list.isEmpty()) {
                drawMessage(c, R.drawable.icon_house, "No rooms yet", null, "The home server has no purifier", "or vacuum to show.", false);
                return;
            }
            for (int i = firstRow * COLS; i < Math.min(list.size(), (firstRow + VISIBLE_ROWS) * COLS); i++) {
                cardBox(i, firstRow, rect);
                if (i == focus) focusRing.at(i);
                drawCard(c, list.get(i), rect, i == focus, tick);
            }
        }

        private void cardBox(int index, int first, RectF out) {
            int r = index / COLS - first, cc = index % COLS;
            float x = LEFT + cc * (CARD_W + GAP), y = TOP + r * (CARD_H + GAP);
            out.set(x * k, y * k, (x + CARD_W) * k, (y + CARD_H) * k);
        }

        private void drawSkeleton(Canvas c, RectF r) {
            Glass.plate(c, r, k, fill, stroke);
            fill.setColor(BONE);
            float x = r.left + PAD_X * k;
            tmp.set(x, r.top + 38 * k, x + 200 * k, r.top + 64 * k);
            c.drawRoundRect(tmp, 13 * k, 13 * k, fill);
            tmp.set(x, r.top + 110 * k, x + 150 * k, r.top + 200 * k);
            c.drawRoundRect(tmp, 20 * k, 20 * k, fill);
            tmp.set(x, r.top + 228 * k, x + 246 * k, r.top + 238 * k);
            c.drawRoundRect(tmp, 5 * k, 5 * k, fill);
            tmp.set(x, r.top + 280 * k, x + 220 * k, r.top + 304 * k);
            c.drawRoundRect(tmp, 12 * k, 12 * k, fill);
        }

        private void drawCard(Canvas c, ArcaDomo.Device d, RectF r, boolean focused, long tick) {
            if (focused) focusRing.focus(c, r, Glass.PLATE_RADIUS * k, "cards", k, fill, stroke);
            else Glass.plate(c, r, k, fill, stroke);
            float x0 = r.left + PAD_X * k, x1 = r.right - PAD_X * k, top = r.top;
            long age = d.age(tick);
            boolean unavailable = !d.hasState, silent = d.hasState && d.failed(), old = age > ArcaDomo.OLD_MS, stale = age > ArcaDomo.STALE_MS;
            float titleY = top + 51 * k;
            c.drawText(d.name, x0, titleY + Ui.halfCap(name32), name32);
            if (unavailable) badge(c, x1, titleY, R.drawable.icon_alert, "Unavailable");
            else if (silent) badge(c, x1, titleY, R.drawable.icon_offline, "No response");
            else if (old) badge(c, x1, titleY, R.drawable.icon_clock, "Out of date");
            else c.drawText(d.statusText, x1, titleY + Ui.halfCap(status24), status24);
            if (unavailable) {
                c.drawText("No reading yet", x0, top + 170 * k, skeletonTitle);
                c.drawText("It hasn't answered since", x0, top + 212 * k, unit24);
                c.drawText("the home server started", x0, top + 246 * k, unit24);
                return;
            }
            boolean dim = old || silent;
            int save = dim ? c.saveLayerAlpha(r.left, r.top, r.right, r.bottom, 128) : -1;
            float numberBase = top + 186 * k, barY = top + 233 * k;
            if (d.vacuum()) {
                c.drawText(d.batteryText, x0, numberBase, big112);
                c.drawText("%", x0 + big112.measureText(d.batteryText) + 10 * k, numberBase, pct40);
                meter(c, x0, barY, 228 * k, 10 * k, d.battery);
                c.drawText("Battery", x0 + 244 * k, barY + Ui.halfCap(level28), level28);
            } else if (!d.pmText.isEmpty()) {
                c.drawText(d.pmText, x0, numberBase, big112);
                c.drawText("µg/m³", x0 + big112.measureText(d.pmText) + 12 * k, numberBase, unit24);
                int level = d.level();
                steps(c, x0, barY, 36 * k, 10 * k, 6 * k, level);
                c.drawText(ArcaDomo.LEVELS[level], x0 + 262 * k, barY + Ui.halfCap(level28), level28);
            }
            if (save >= 0) c.restoreToCount(save);
            float bottomY = top + 292 * k, x = x0;
            if (stale) x = fact(c, x, bottomY, R.drawable.icon_clock, ageText(age), old || silent ? MUTE : AMBER);
            if (d.vacuum()) {
                if (!d.cleaningWord.isEmpty()) fact(c, x, bottomY, R.drawable.icon_vacuum, d.cleaningWord, MUTE);
            } else {
                if (!d.humidityText.isEmpty()) x = fact(c, x, bottomY, R.drawable.icon_humidity, d.humidityText, MUTE);
                if (!d.temperatureText.isEmpty()) fact(c, x, bottomY, R.drawable.icon_temperature, d.temperatureText, MUTE);
                if (d.filter >= 0 && d.filter <= 10) badge(c, x1, bottomY, R.drawable.icon_filter, d.filterText);
            }
        }

        private float fact(Canvas c, float x, float cy, int icon, String text, int color) {
            Icons.draw(getContext(), c, icon, x + 12 * k, cy, 24 * k, color);
            small24.setColor(color);
            c.drawText(text, x + 32 * k, cy + Ui.halfCap(small24), small24);
            float end = x + 32 * k + small24.measureText(text) + 22 * k;
            small24.setColor(MUTE);
            return end;
        }

        private void badge(Canvas c, float right, float cy, int icon, String text) {
            float width = pill24.measureText(text) + (20 + 26 + 10 + 20) * k;
            tmp.set(right - width, cy - 22 * k, right, cy + 22 * k);
            Glass.plate(c, tmp, 22 * k, k, fill, stroke);
            Icons.draw(getContext(), c, icon, tmp.left + 33 * k, cy, 26 * k, INK);
            c.drawText(text, tmp.left + 56 * k, cy + Ui.halfCap(pill24), pill24);
        }

        private void steps(Canvas c, float x, float cy, float stepW, float stepH, float gap, int level) {
            for (int i = 0; i < 6; i++) {
                fill.setColor(i < level ? PAST : i == level ? LEVEL_COLORS[i] : TRACK);
                tmp.set(x + i * (stepW + gap), cy - stepH / 2, x + i * (stepW + gap) + stepW, cy + stepH / 2);
                c.drawRoundRect(tmp, stepH / 2, stepH / 2, fill);
            }
        }

        private void meter(Canvas c, float x, float cy, float width, float height, int percent) {
            fill.setColor(TRACK);
            tmp.set(x, cy - height / 2, x + width, cy + height / 2);
            c.drawRoundRect(tmp, height / 2, height / 2, fill);
            if (percent <= 0) return;
            fill.setColor(INK);
            tmp.right = x + Math.max(height, width * Math.min(100, percent) / 100f);
            c.drawRoundRect(tmp, height / 2, height / 2, fill);
        }

        private static String ageText(long ms) {
            int minutes = (int) Math.min(180, ms / 60_000);
            if (AGES[minutes] == null) AGES[minutes] = minutes >= 180 ? "Hours ago" : minutes >= 60 ? (minutes / 60) + " h ago" : minutes + " min ago";
            return AGES[minutes];
        }

        private void drawMessage(Canvas c, int icon, String title, String title2, String line1, String line2, boolean button) {
            float width = 640, height = 500, left = (1920 - width) / 2;
            rect.set(left * k, TOP * k, (left + width) * k, (TOP + height) * k);
            Glass.plate(c, rect, k, fill, stroke);
            float cx = rect.centerX(), content = 64 + 30 + 41 + (title2 == null ? 0 : 41) + 14 + 32 + (line2 == null || line2.isEmpty() ? 0 : 32) + (button ? 36 + 68 : 0);
            float y = TOP + (height - content) / 2;
            Icons.draw(getContext(), c, icon, cx, (y + 32) * k, 64 * k, INK);
            y += 64 + 30 + 33;
            c.drawText(title, cx, y * k, title34);
            if (title2 != null) {
                y += 41;
                c.drawText(title2, cx, y * k, title34);
            }
            y += 14 + 26;
            c.drawText(line1, cx, y * k, msg24);
            if (line2 != null && !line2.isEmpty()) {
                y += 32;
                c.drawText(line2, cx, y * k, msg24);
            }
            if (!button) return;
            y += 6 + 36;
            float bw = button28.measureText("Try again") / k + 72;
            tmp.set(cx - bw / 2 * k, y * k, cx + bw / 2 * k, (y + 68) * k);
            Glass.focus(c, tmp, 34 * k, k, fill, stroke);
            c.drawText("Try again", cx, tmp.centerY() + Ui.halfCap(button28), button28);
        }

        private void drawFreshness(Canvas c, ArcaDomo.Device d, float x, float cy, long tick) {
            long age = d.age(tick);
            if (!d.hasState) return;
            if (d.failed()) fact(c, x, cy, R.drawable.icon_offline, "No response · " + ageText(age), MUTE);
            else if (age > ArcaDomo.OLD_MS) fact(c, x, cy, R.drawable.icon_clock, "Out of date · " + ageText(age), MUTE);
            else if (age > ArcaDomo.STALE_MS) fact(c, x, cy, R.drawable.icon_clock, ageText(age), AMBER);
        }

        private void drawPurifier(Canvas c, ArcaDomo.Device d, long tick) {
            float x0 = LEFT * k;
            if (!d.hasState) {
                c.drawText("No reading yet", x0, 420 * k, level44);
                c.drawText("It hasn't answered since the home server started.", x0, 480 * k, sub30);
            } else {
                long age = d.age(tick);
                boolean dim = d.failed() || age > ArcaDomo.OLD_MS;
                int save = dim ? c.saveLayerAlpha(0, TOP * k, PANEL_L * k, h, 128) : -1;
                float base = 466 * k, factsY = 615 * k;
                if (!d.pmText.isEmpty()) {
                    c.drawText(d.pmText, x0 - Ui.leftBearing(huge220, d.pmText), base, huge220);
                    c.drawText("µg/m³", x0 + huge220.measureText(d.pmText) + 18 * k, base, unit32);
                    int level = d.level();
                    steps(c, x0, 538 * k, 56 * k, 12 * k, 8 * k, level);
                    c.drawText(ArcaDomo.LEVELS[level], x0 + 398 * k, 538 * k + Ui.halfCap(level44), level44);
                }
                float x = x0;
                if (!d.humidityText.isEmpty()) x = labelled(c, x, factsY, R.drawable.icon_humidity, "Humidity", d.humidityText);
                if (!d.temperatureText.isEmpty()) labelled(c, x, factsY, R.drawable.icon_temperature, "Temperature", d.temperatureText);
                if (save >= 0) c.restoreToCount(save);
                boolean facts = !d.humidityText.isEmpty() || !d.temperatureText.isEmpty();
                drawFreshness(c, d, x0, (facts ? 675 : 615) * k, tick);
                if (d.filter >= 0) drawFilter(c, d);
            }
            drawPurifierControls(c, d, tick);
        }

        private float labelled(Canvas c, float x, float cy, int icon, String label, String value) {
            Icons.draw(getContext(), c, icon, x + 15 * k, cy, 30 * k, MUTE);
            facts30.setColor(MUTE);
            float tx = x + 42 * k;
            c.drawText(label, tx, cy + Ui.halfCap(facts30), facts30);
            tx += facts30.measureText(label) + 10 * k;
            facts30.setColor(INK);
            c.drawText(value, tx, cy + Ui.halfCap(facts30), facts30);
            return tx + facts30.measureText(value) + 40 * k;
        }

        private void drawFilter(Canvas c, ArcaDomo.Device d) {
            rect.set(LEFT * k, 842 * k, (LEFT + 760) * k, 1016 * k);
            Glass.plate(c, rect, k, fill, stroke);
            float x0 = rect.left + PAD_X * k, x1 = rect.right - PAD_X * k, rowY = rect.top + 46 * k;
            Icons.draw(getContext(), c, R.drawable.icon_filter, x0 + 15 * k, rowY, 30 * k, INK);
            c.drawText("Filter", x0 + 44 * k, rowY + Ui.halfCap(label30), label30);
            c.drawText(d.filter + " %", x1, rowY + Ui.halfCap(value30), value30);
            meter(c, x0, rect.top + 91 * k, x1 - x0, 10 * k, d.filter);
            if (!d.filterNote.isEmpty()) c.drawText(d.filterNote, x0, rect.top + 140 * k, note26);
        }

        private boolean favorite(ArcaDomo.Device d) {
            return "favorite".equals(d.mode);
        }

        private int modeIndex(ArcaDomo.Device d) {
            for (int i = 0; i < MODE_KEYS.length; i++) if (MODE_KEYS[i].equals(d.mode)) return i;
            return -1;
        }

        private float rowTop(int r) {
            return TOP + 20 + (r == 0 ? 0 : r == 1 ? Glass.ROW_H + Glass.ROW_GAP : 2 * (Glass.ROW_H + Glass.ROW_GAP));
        }

        private void drawPurifierControls(Canvas c, ArcaDomo.Device d, long tick) {
            float bottom = rowTop(R_FAN) + FAN_ROW_H + 20;
            rect.set(PANEL_L * k, TOP * k, PANEL_R * k, bottom * k);
            Glass.plate(c, rect, k, fill, stroke);
            float inL = (PANEL_L + 20) * k, inR = (PANEL_R - 20) * k;
            boolean live = d.hasState;
            int powerActive = "power".equals(pending) ? pendingIndex : d.power ? 0 : 1;
            float cy = (rowTop(R_POWER) + Glass.ROW_H / 2) * k;
            c.drawText("Power", inL + 28 * k, cy + Ui.halfCap(label30), label30);
            pills(c, POWER, inR - 20 * k, cy, live ? powerActive : -1, row == R_POWER ? col : -1, "power".equals(pending), tick);
            int modeActive = "mode".equals(pending) ? pendingIndex : modeIndex(d);
            cy = (rowTop(R_MODE) + Glass.ROW_H / 2) * k;
            int modeSave = d.power ? -1 : c.saveLayerAlpha(rect.left, rect.top, rect.right, rect.bottom, 115);
            c.drawText("Mode", inL + 28 * k, cy + Ui.halfCap(label30), label30);
            pills(c, MODES, inR - 20 * k, cy, live ? modeActive : -1, row == R_MODE ? col : -1, "mode".equals(pending), tick);
            if (modeSave >= 0) c.restoreToCount(modeSave);
            boolean fanOn = live && d.power && favorite(d);
            int save = fanOn ? -1 : c.saveLayerAlpha(rect.left, rect.top, rect.right, rect.bottom, 115);
            cy = (rowTop(R_FAN) + FAN_ROW_H / 2) * k;
            c.drawText("Fan", inL + 28 * k, cy - 6 * k, label30);
            c.drawText(fanOn ? "0 to 14" : d.power ? "Only in Favorite" : "Turn it on first", inL + 28 * k, cy + 30 * k, sub24);
            drawFan(c, d, inR - 20 * k, cy, tick);
            if (save >= 0) c.restoreToCount(save);
            drawStatusLine(c, bottom, tick);
        }

        private void pills(Canvas c, String[] labels, float right, float cy, int active, int focused, boolean waiting, long tick) {
            float x = right;
            for (int i = labels.length - 1; i >= 0; i--) {
                Paint p = i == active ? pillOn : pillOff;
                float width = pillOn.measureText(labels[i]) + 60 * k;
                tmp.set(x - width, cy - PILL_H / 2 * k, x, cy + PILL_H / 2 * k);
                float radius = PILL_H / 2 * k;
                if (i == active) {
                    fill.setColor(INK);
                    c.drawRoundRect(tmp, radius, radius, fill);
                    if (i == focused) focusRing.ring(c, tmp, radius, "options", k, stroke);
                } else if (i == focused) {
                    focusRing.focus(c, tmp, radius, "options", k, fill, stroke);
                } else {
                    Glass.plate(c, tmp, radius, k, fill, stroke);
                }
                if (waiting && i == active) spinner(c, tmp.centerX(), cy, 11 * k, DARK, tick);
                else c.drawText(labels[i], tmp.centerX() - p.measureText(labels[i]) / 2, cy + Ui.halfCap(p), p);
                x -= width + 12 * k;
            }
        }

        private void drawFan(Canvas c, ArcaDomo.Device d, float right, float cy, long tick) {
            boolean waiting = "fan".equals(pending);
            int level = waiting ? pendingIndex : Math.max(0, d.fan);
            float plusL = right - 60 * k, valueL = plusL - 96 * k, minusL = valueL - 60 * k, barsR = minusL - 16 * k;
            circle(c, minusL, cy, R.drawable.icon_minus, row == R_FAN && col == 0);
            circle(c, plusL, cy, R.drawable.icon_plus, row == R_FAN && col == 1);
            float vx = valueL + 48 * k;
            if (waiting) {
                spinner(c, vx - 22 * k, cy, 11 * k, INK, tick);
                fan36.setColor(MUTE);
                c.drawText(NUMBERS[level], vx + 14 * k, cy + Ui.halfCap(fan36), fan36);
                fan36.setColor(INK);
            } else {
                c.drawText(d.fan >= 0 ? NUMBERS[Math.min(14, level)] : "–", vx, cy + Ui.halfCap(fan36), fan36);
            }
            float barW = 8 * k, gap = 5 * k, x = barsR - 14 * barW - 13 * gap;
            for (int i = 0; i < 14; i++) {
                fill.setColor(i < level ? INK : TRACK);
                tmp.set(x + i * (barW + gap), cy - 13 * k, x + i * (barW + gap) + barW, cy + 13 * k);
                c.drawRoundRect(tmp, 4 * k, 4 * k, fill);
            }
        }

        private void circle(Canvas c, float left, float cy, int icon, boolean focused) {
            tmp.set(left, cy - 30 * k, left + 60 * k, cy + 30 * k);
            if (focused) focusRing.focus(c, tmp, 30 * k, "options", k, fill, stroke);
            else Glass.plate(c, tmp, 30 * k, k, fill, stroke);
            Icons.draw(getContext(), c, icon, tmp.centerX(), cy, 28 * k, INK);
        }

        private void spinner(Canvas c, float cx, float cy, float r, int color, long tick) {
            spin.setColor(color);
            spin.setStrokeWidth(2.5f * k);
            Ui.spinner(c, cx, cy, r, (tick % 1000) / 1000f, spin);
        }

        private void drawStatusLine(Canvas c, float plateBottom, long tick) {
            float cy = (plateBottom + 24 + 16) * k, x = (PANEL_L + 28) * k;
            if (pending != null) {
                spinner(c, x + 13 * k, cy, 11 * k, MUTE, tick);
                c.drawText(pendingLabel, x + 38 * k, cy + Ui.halfCap(note26), note26);
            } else if (!note.isEmpty() && tick < noteUntil) {
                c.drawText(note, x, cy + Ui.halfCap(note26), note26);
            }
        }

        private void drawVacuum(Canvas c, ArcaDomo.Device d, long tick) {
            float x0 = LEFT * k;
            if (!d.hasState) {
                c.drawText("No reading yet", x0, 420 * k, level44);
                c.drawText("It hasn't answered since the home server started.", x0, 480 * k, sub30);
            } else {
                long age = d.age(tick);
                boolean dim = d.failed() || age > ArcaDomo.OLD_MS;
                int save = dim ? c.saveLayerAlpha(0, TOP * k, PANEL_L * k, h, 128) : -1;
                c.drawText(d.statusWord, x0 - Ui.leftBearing(word160, d.statusWord), 426 * k, word160);
                if (!d.statusSub.isEmpty()) c.drawText(d.statusSub, x0, 494 * k, sub30);
                float y = 580 * k;
                c.drawText("Battery", x0, y + Ui.halfCap(sub30), sub30);
                meter(c, x0 + 172 * k, y, 360 * k, 12 * k, d.battery);
                c.drawText(d.batteryText.isEmpty() ? "–" : d.batteryText + " %", x0 + 554 * k, y + Ui.halfCap(value36), value36);
                y = 648 * k;
                c.drawText("Cleaning", x0, y + Ui.halfCap(sub30), sub30);
                c.drawText(d.cleaningWord, x0 + 172 * k, y + Ui.halfCap(value36), value36);
                if (save >= 0) c.restoreToCount(save);
                drawFreshness(c, d, x0, 716 * k, tick);
            }
            float bottom = rowTop(V_DOCK) + Glass.ROW_H + 20;
            rect.set(PANEL_L * k, TOP * k, PANEL_R * k, bottom * k);
            Glass.plate(c, rect, k, fill, stroke);
            for (int i = 0; i < 3; i++) {
                float top = rowTop(i);
                tmp.set((PANEL_L + 20) * k, top * k, (PANEL_R - 20) * k, (top + Glass.ROW_H) * k);
                boolean usable = vacuumUsable(d, i);
                int save = usable ? -1 : c.saveLayerAlpha(tmp.left - 10 * k, tmp.top - 10 * k, tmp.right + 10 * k, tmp.bottom + 10 * k, 115);
                if (row == i && usable) focusRing.row(c, tmp, "rows", k, fill, stroke);
                float cy = tmp.centerY(), ix = tmp.left + 46 * k;
                if (VACUUM_ACTIONS[i].equals(pending)) spinner(c, ix, cy, 14 * k, INK, tick);
                else Icons.draw(getContext(), c, VACUUM_ICONS[i], ix, cy, 36 * k, i == V_START ? INK : MUTE);
                c.drawText(VACUUM_ROWS[i], tmp.left + 88 * k, cy + Ui.halfCap(rowTitle32), rowTitle32);
                c.drawText(VACUUM_NOTES[i], tmp.right - 28 * k, cy + Ui.halfCap(rowNote26), rowNote26);
                if (save >= 0) c.restoreToCount(save);
            }
            drawStatusLine(c, bottom, tick);
        }

        private boolean vacuumUsable(ArcaDomo.Device d, int i) {
            if (!d.hasState) return false;
            if (i == V_START) return !d.cleaning();
            if (i == V_DOCK) return !d.charging();
            return true;
        }

        private boolean purifierUsable(ArcaDomo.Device d, int r) {
            if (!d.hasState) return false;
            return r == R_POWER || d.power && (r != R_FAN || favorite(d));
        }

        private void clampDeviceFocus() {
            ArcaDomo.Device d = current();
            if (d == null) return;
            if (d.vacuum()) {
                if (!vacuumUsable(d, row)) row = firstUsable(d);
            } else {
                if (!purifierUsable(d, row)) {
                    row = R_POWER;
                    col = d.power ? 0 : 1;
                }
                col = Math.max(0, Math.min(col, columns(row) - 1));
            }
        }

        private int firstUsable(ArcaDomo.Device d) {
            for (int i = 0; i < 3; i++) if (vacuumUsable(d, i)) return i;
            return 0;
        }

        private int columns(int r) {
            return r == R_POWER ? 2 : r == R_MODE ? 3 : 2;
        }

        private int entryCol(ArcaDomo.Device d, int r) {
            if (r == R_POWER) return d.power ? 0 : 1;
            if (r == R_MODE) return Math.max(0, modeIndex(d));
            return 1;
        }

        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (down) move(-1, 0);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down) move(1, 0);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down) move(0, -1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down) move(0, 1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled()) press();
                        okDown = false;
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled()) back();
                        backDown = false;
                    }
                    return true;
                default:
                    return false;
            }
        }

        private void back() {
            if (screen == DEVICE) {
                screen = ROOMS;
                note = "";
                invalidate();
            } else {
                activity.finish();
            }
        }

        private void move(int dx, int dy) {
            focusRing.pressed(dx, dy);
            if (screen == ROOMS) {
                if (showsMessage()) return;
                int n = domo.devices().size();
                if (n == 0) return;
                int next = focus + dx + dy * COLS;
                if (dx != 0 && next / COLS != focus / COLS) return;
                if (next < 0) return;
                if (next >= n) {
                    if (dy > 0 && focus / COLS < (n - 1) / COLS) next = n - 1;
                    else return;
                }
                focus = next;
                int r = focus / COLS;
                if (r < firstRow) firstRow = r;
                else if (r >= firstRow + VISIBLE_ROWS) firstRow = r - VISIBLE_ROWS + 1;
                invalidate();
                return;
            }
            ArcaDomo.Device d = current();
            if (d == null) return;
            if (d.vacuum()) {
                if (dy == 0) return;
                for (int r = row + dy; r >= 0 && r < 3; r += dy) {
                    if (vacuumUsable(d, r)) {
                        row = r;
                        break;
                    }
                }
            } else if (dy != 0) {
                for (int r = row + dy; r >= 0 && r < 3; r += dy) {
                    if (purifierUsable(d, r)) {
                        row = r;
                        col = entryCol(d, r);
                        break;
                    }
                }
            } else {
                col = Math.max(0, Math.min(columns(row) - 1, col + dx));
            }
            invalidate();
        }

        private void press() {
            if (screen == ROOMS) {
                if (domo.status() == ArcaDomo.UNREACHABLE) {
                    domo.retry();
                    return;
                }
                List<ArcaDomo.Device> list = domo.devices();
                if (showsMessage() || focus >= list.size()) return;
                ArcaDomo.Device d = list.get(focus);
                deviceId = d.id;
                screen = DEVICE;
                note = "";
                if (d.vacuum()) row = firstUsable(d);
                else {
                    row = R_POWER;
                    col = entryCol(d, R_POWER);
                }
                invalidate();
                return;
            }
            ArcaDomo.Device d = current();
            if (d == null || pending != null || !d.hasState) return;
            if (d.vacuum()) {
                if (!vacuumUsable(d, row)) return;
                String[] doing = {"Starting the vacuum", "Stopping the vacuum", "Sending the vacuum home"};
                send(d, VACUUM_ACTIONS[row], null, row, doing[row], d.name + " didn't answer.");
                return;
            }
            try {
                if (row == R_POWER) {
                    boolean on = col == 0;
                    if (on == d.power) return;
                    send(d, "power", new JSONObject().put("on", on), col, "Turning " + d.name + (on ? " on" : " off"), d.name + " didn't answer. It stays " + (d.power ? "on." : "off."));
                } else if (row == R_MODE) {
                    if (MODE_KEYS[col].equals(d.mode)) return;
                    int before = modeIndex(d);
                    send(d, "mode", new JSONObject().put("mode", MODE_KEYS[col]), col, "Setting mode to " + MODES[col], d.name + " didn't answer. Mode stays " + (before >= 0 ? MODES[before] : d.modeWord) + ".");
                } else if (favorite(d)) {
                    int level = Math.max(0, Math.min(14, Math.max(0, d.fan) + (col == 0 ? -1 : 1)));
                    if (level == d.fan) return;
                    send(d, "fan", new JSONObject().put("level", level), level, "Setting fan to " + level, d.name + " didn't answer. Fan stays at " + Math.max(0, d.fan) + ".");
                }
            } catch (JSONException ignored) {
            }
        }

        private void send(ArcaDomo.Device d, String action, JSONObject body, int index, String doing, String failure) {
            pending = action;
            pendingIndex = index;
            pendingLabel = doing;
            note = "";
            invalidate();
            domo.act(d.id, action, body, (updated, error) -> {
                pending = null;
                pendingIndex = -1;
                if (error != null) {
                    note = "rejected".equals(error) ? "The home server didn't accept this TV."
                        : ArcaDomo.IGNORED.equals(error) ? d.name + " didn't take the change." : failure;
                    noteUntil = SystemClock.elapsedRealtime() + 6_000;
                }
                clampDeviceFocus();
                invalidate();
            });
        }
    }
}
