package com.satoshilimited.tv;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class JellyfinActivity extends Activity {
    private JellyfinView view;
    private IdleSaver idle;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        view = new JellyfinView(this);
        idle = new IdleSaver(this, view, view::awaitingApproval);
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

    @Override protected void onDestroy() {
        view.stop();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (idle.key(event)) return true;
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    static final class JellyfinView extends View {
        private static final int SEARCH = 0, PICK = 1, ADDRESS = 2, CODE = 3, NOTICE = 4, CONNECTED = 5;
        private static final int INK = 0xFFF2F2F2, MUTE = 0xB8FFFFFF, DIM = 0x73FFFFFF, DARK = 0xFF141414;
        private static final float CARD_L = 112, CARD_R = 1112, CARD_TOP = 330, INSET = 32;
        private static final float PANEL_L = 112, PANEL_TOP = 300, PANEL_W = 900, PANEL_H = 560, PANEL_PAD = 60;
        private static final int CODE_LENGTH = 6;

        private static final class Row {
            String title, value = "";
            boolean focusable = true, spinner, check;
            int toggle = -1;
            Runnable action;
        }

        private final JellyfinActivity activity;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF(), tmp = new RectF();
        private final List<Row> rows = new ArrayList<>();
        private final StringBuilder address = new StringBuilder();
        private JellyfinLink.Link link;
        private int epoch;
        private JellyfinLink.Server target;
        private int state = SEARCH, focus, noticeIcon;
        private String code = "", error = "", noticeTitle = "", noticeBody = "";
        private boolean running, okDown, backDown;
        private long startedAt;
        private int w, h;
        private float k = 1;
        private LinearGradient background;
        private Header header;
        private Paint rowTitle, rowValue, hintPaint, slotPaint, keyPaint, spinnerPaint, noticeTitlePaint, buttonPaint;
        private TextPaint bodyPaint;
        private StaticLayout bodyLayout;
        private String bodyShown = "";

        private final Runnable tick = new Runnable() {
            @Override public void run() {
                if (!running) return;
                invalidate();
                handler.postDelayed(this, spinning() ? 33 : 1000);
            }
        };

        JellyfinView(JellyfinActivity a) {
            super(a);
            activity = a;
            stroke.setStyle(Paint.Style.STROKE);
            setFocusable(true);
            setContentDescription("Jellyfin");
            if (Jellyfin.configured()) showConnected();
            else search();
        }

        void resume() {
            if (running) return;
            running = true;
            startedAt = SystemClock.uptimeMillis();
            handler.post(tick);
        }

        void pause() {
            running = false;
            handler.removeCallbacks(tick);
        }

        void stop() {
            epoch++;
            if (link != null) link.cancel();
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            background = new LinearGradient(0, 0, 0, h, new int[] {0xFF141414, 0xFF0E0E0E, 0xFF0A0A0A}, new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP);
            header = new Header(k);
            rowTitle = Ui.text(Ui.medium, 30 * k, INK);
            rowValue = Ui.text(Ui.regular, 28 * k, MUTE);
            rowValue.setTextAlign(Paint.Align.RIGHT);
            hintPaint = Ui.text(Ui.regular, 26 * k, MUTE);
            slotPaint = Ui.numbers(Ui.light, 84 * k, INK);
            slotPaint.setTextAlign(Paint.Align.CENTER);
            keyPaint = Ui.numbers(Ui.regular, 44 * k, INK);
            keyPaint.setTextAlign(Paint.Align.CENTER);
            noticeTitlePaint = Ui.text(Ui.light, 40 * k, INK);
            noticeTitlePaint.setLetterSpacing(-0.025f);
            buttonPaint = Ui.text(Ui.semibold, 26 * k, INK);
            bodyPaint = new TextPaint(Ui.text(Ui.regular, 26 * k, MUTE));
            spinnerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            spinnerPaint.setStyle(Paint.Style.STROKE);
            spinnerPaint.setStrokeCap(Paint.Cap.ROUND);
            spinnerPaint.setStrokeWidth(3.5f * k);
            spinnerPaint.setColor(INK);
            bodyShown = "";
        }

        private boolean spinning() {
            return state == SEARCH || (state == CODE && code.isEmpty());
        }

        boolean awaitingApproval() {
            return state == CODE;
        }

        private boolean keypad() {
            return state == ADDRESS;
        }

        private void search() {
            stop();
            state = SEARCH;
            error = "";
            buildRows();
            int mine = epoch;
            JellyfinLink.search(getContext(), (servers, err) -> {
                if (state != SEARCH || mine != epoch) return;
                if (servers.isEmpty()) notFound();
                else if (servers.size() == 1) connect(servers.get(0));
                else pick(servers);
                invalidate();
            });
        }

        private void pick(List<JellyfinLink.Server> servers) {
            state = PICK;
            rows.clear();
            for (JellyfinLink.Server s : servers) addRow(s.name, s.url.replaceFirst("^https?://", ""), true, () -> connect(s));
            focus = 0;
        }

        private void notFound() {
            notice(R.drawable.icon_offline, "No Jellyfin server found",
                    "Make sure the server is on and on the same network as this TV, or type its address.");
            addRow("Try again", "", true, this::search);
            addRow("Enter address", "", true, this::openAddress);
        }

        private void notice(int icon, String title, String body) {
            stop();
            state = NOTICE;
            noticeIcon = icon;
            noticeTitle = title;
            noticeBody = body;
            rows.clear();
            focus = 0;
        }

        private void connect(JellyfinLink.Server server) {
            stop();
            target = server;
            code = "";
            error = "";
            state = CODE;
            focus = 0;
            rows.clear();
            addRow("Search again", "", true, this::search);
            link = new JellyfinLink.Link(server);
            link.start(getContext(), new JellyfinLink.Listener() {
                @Override public void code(String value) {
                    code = value;
                    invalidate();
                }

                @Override public void signedIn(JellyfinLink.Session session) {
                    Prefs.setJellyfin(server.url, session.token, session.userId, session.user, server.name);
                    Jellyfin.get(getContext()).reset();
                    showConnected();
                    invalidate();
                }

                @Override public void failed(String reason) {
                    if (JellyfinLink.OFF.equals(reason)) {
                        notice(R.drawable.icon_info, "Quick Connect is off on " + server.name,
                                "Turn it on in Jellyfin: Dashboard → General → Enable Quick Connect on this server. Then try again.");
                        addRow("Try again", "", true, () -> connect(server));
                    } else if (JellyfinLink.EXPIRED.equals(reason)) {
                        notice(R.drawable.icon_info, "The code expired", "Codes last 10 minutes. Ask for a new one.");
                        addRow("New code", "", true, () -> connect(server));
                    } else {
                        notice(R.drawable.icon_offline, "Couldn't reach " + server.name, "Check that the server is on, then try again.");
                        addRow("Try again", "", true, () -> connect(server));
                        addRow("Search again", "", true, JellyfinView.this::search);
                    }
                    invalidate();
                }
            });
        }

        private void openAddress() {
            address.setLength(0);
            address.append(LanScan.prefix());
            error = "";
            state = ADDRESS;
            focus = 10;
        }

        private void submitAddress() {
            String host = address.toString().trim();
            if (host.isEmpty()) return;
            int mine = epoch;
            JellyfinLink.probe(host, (found, err) -> {
                if (state != ADDRESS || mine != epoch) return;
                if (found != null) connect(found);
                else error = JellyfinLink.UNREACHABLE;
                invalidate();
            });
        }

        private void showConnected() {
            stop();
            state = CONNECTED;
            focus = 1;
            rows.clear();
            Row user = new Row();
            user.title = "Signed in as";
            user.value = Prefs.jellyfinUser();
            user.focusable = false;
            user.check = true;
            rows.add(user);
            Row toggle = new Row();
            toggle.title = "Continue watching on Home";
            toggle.toggle = Prefs.continueSource().equals("jellyfin") ? 1 : 0;
            toggle.action = () -> {
                Prefs.setContinueSource(toggle.toggle == 1 ? "off" : "jellyfin");
                showConnected();
                focus = 1;
            };
            rows.add(toggle);
            addRow("Disconnect", "", true, () -> {
                Prefs.clearJellyfin();
                Jellyfin.get(getContext()).reset();
                activity.finish();
            });
        }

        private void addRow(String title, String value, boolean focusable, Runnable action) {
            Row r = new Row();
            r.title = title;
            r.value = value;
            r.focusable = focusable;
            r.action = action;
            rows.add(r);
        }

        private void buildRows() {
            rows.clear();
            if (state != SEARCH) return;
            Row r = new Row();
            r.title = "Looking for your Jellyfin server";
            r.focusable = false;
            r.spinner = true;
            rows.add(r);
        }

        private String title() {
            switch (state) {
                case CODE: return "Connect to " + (target == null ? "Jellyfin" : target.name);
                case CONNECTED: return Prefs.jellyfinServer().isEmpty() ? "Jellyfin" : Prefs.jellyfinServer();
                default: return "Jellyfin";
            }
        }

        private void rowBox(int i, RectF out) {
            float top = CARD_TOP + Glass.PLATE_INSET + i * (Glass.ROW_H + Glass.ROW_GAP);
            out.set((CARD_L + Glass.PLATE_INSET) * k, top * k, (CARD_R - Glass.PLATE_INSET) * k, (top + Glass.ROW_H) * k);
        }

        private void sideBox(int i, RectF out) {
            float top = CARD_TOP + Glass.PLATE_INSET + i * (Glass.ROW_H + Glass.ROW_GAP);
            out.set((1248 + Glass.PLATE_INSET) * k, top * k, (1808 - Glass.PLATE_INSET) * k, (top + Glass.ROW_H) * k);
        }

        private void keyBox(int i, RectF out) {
            float gap = 20, width = (560 - 2 * gap) / 3f, height = 112;
            float x = 1248 + (i % 3) * (width + gap), y = CARD_TOP + (i / 3) * (height + gap);
            out.set(x * k, y * k, (x + width) * k, (y + height) * k);
        }

        private String keyLabel(int i) {
            if (i < 9) return String.valueOf(i + 1);
            if (i == 9) return "←";
            if (i == 10) return "0";
            return i == 11 ? "." : "✓";
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            fill.setColor(0xFF000000);
            fill.setShader(background);
            c.drawRect(0, 0, w, h, fill);
            fill.setShader(null);
            long now = System.currentTimeMillis();
            header.drawScreen(c, title(), Ui.clock(now), Ui.meridiem(now), Ui.date(now));
            float seconds = (SystemClock.uptimeMillis() - startedAt) / 1000f * 1.3f;
            if (state == CODE) drawCode(c, seconds);
            else if (state == ADDRESS) drawAddress(c);
            else if (state == NOTICE) drawNotice(c);
            else drawRows(c, seconds);
            if (keypad()) drawKeys(c);
        }

        private void drawRows(Canvas c, float seconds) {
            float bottom = CARD_TOP + 2 * Glass.PLATE_INSET + rows.size() * (Glass.ROW_H + Glass.ROW_GAP) - Glass.ROW_GAP;
            tmp.set(CARD_L * k, CARD_TOP * k, CARD_R * k, bottom * k);
            Glass.plate(c, tmp, k, fill, stroke);
            for (int i = 0; i < rows.size(); i++) {
                rowBox(i, rect);
                drawRow(c, rows.get(i), rect, seconds);
            }
            if (!rows.isEmpty() && focus < rows.size() && rows.get(focus).focusable) {
                rowBox(focus, rect);
                Glass.row(c, rect, k, fill, stroke, true);
            }
            float y = (bottom + 56) * k;
            String text = state == SEARCH ? "Servers on the same network as this TV answer on their own. With one server, the code shows straight away."
                    : state == CONNECTED ? "What you watch on Jellyfin, on any screen, shows up in Continue watching on Home. The key lives on this TV only." : "";
            if (!text.isEmpty()) drawWrapped(c, text, CARD_L * k, y - 26 * k, 1000 * k);
        }

        private void drawRow(Canvas c, Row r, RectF box, float seconds) {
            rowTitle.setColor(r.focusable || r.check ? INK : DIM);
            float base = box.centerY() + Ui.halfCap(rowTitle), right = box.right - INSET * k;
            float titleX = box.left + INSET * k + (r.check ? 52 * k : 0);
            if (r.check) Icons.draw(getContext(), c, R.drawable.icon_check, box.left + INSET * k + 16 * k, box.centerY(), 32 * k, MUTE);
            c.drawText(r.title, titleX, base, rowTitle);
            if (r.spinner) {
                Ui.spinner(c, right - 12 * k, box.centerY(), 12 * k, seconds, spinnerPaint);
            } else if (r.toggle >= 0) {
                drawSwitch(c, right - 68 * k, box.centerY(), r.toggle == 1);
            } else if (!r.value.isEmpty()) {
                rowValue.setColor(MUTE);
                c.drawText(r.value, right, base, rowValue);
            }
            rowTitle.setColor(INK);
        }

        private void drawSwitch(Canvas c, float left, float cy, boolean on) {
            tmp.set(left, cy - 19 * k, left + 68 * k, cy + 19 * k);
            fill.setColor(on ? INK : 0x33FFFFFF);
            c.drawRoundRect(tmp, 19 * k, 19 * k, fill);
            fill.setColor(on ? DARK : MUTE);
            float knob = on ? left + 68 * k - 19 * k : left + 19 * k;
            c.drawCircle(knob, cy, 15 * k, fill);
        }

        private void drawWrapped(Canvas c, String text, float x, float y, float width) {
            if (bodyLayout == null || !text.equals(bodyShown) || bodyLayout.getWidth() != Math.round(width)) {
                bodyShown = text;
                bodyLayout = StaticLayout.Builder.obtain(text, 0, text.length(), bodyPaint, Math.round(width)).setLineSpacing(0, 1.4f).build();
            }
            c.save();
            c.translate(x, y);
            bodyLayout.draw(c);
            c.restore();
        }

        private void drawNotice(Canvas c) {
            tmp.set(PANEL_L * k, PANEL_TOP * k, (PANEL_L + PANEL_W) * k, (PANEL_TOP + PANEL_H) * k);
            Glass.plate(c, tmp, k, fill, stroke);
            float left = (PANEL_L + PANEL_PAD) * k;
            Icons.draw(getContext(), c, noticeIcon, left + 28 * k, (PANEL_TOP + 56 + 28) * k, 56 * k, INK);
            c.drawText(noticeTitle, left, (PANEL_TOP + 56 + 56 + 28 + 38) * k, noticeTitlePaint);
            drawWrapped(c, noticeBody, left, (PANEL_TOP + 56 + 56 + 28 + 38 + 18) * k, (PANEL_W - 2 * PANEL_PAD) * k);
            float x = left, top = (PANEL_TOP + PANEL_H - 56 - 68) * k;
            for (int i = 0; i < rows.size(); i++) {
                Row r = rows.get(i);
                float width = buttonPaint.measureText(r.title) + 68 * k;
                tmp.set(x, top, x + width, top + 68 * k);
                boolean focused = i == focus;
                if (focused) {
                    fill.setColor(INK);
                    c.drawRoundRect(tmp, 34 * k, 34 * k, fill);
                    Glass.ring(c, tmp, 34 * k, k, stroke);
                } else {
                    Glass.plate(c, tmp, 34 * k, k, fill, stroke);
                }
                buttonPaint.setColor(focused ? DARK : INK);
                buttonPaint.setTextAlign(Paint.Align.CENTER);
                c.drawText(r.title, tmp.centerX(), tmp.centerY() + Ui.halfCap(buttonPaint), buttonPaint);
                x += width + 20 * k;
            }
            buttonPaint.setTextAlign(Paint.Align.LEFT);
        }

        private void drawCode(Canvas c, float seconds) {
            float width = 136, gap = 20;
            for (int i = 0; i < CODE_LENGTH; i++) {
                float x = 112 + i * (width + gap);
                rect.set(x * k, CARD_TOP * k, (x + width) * k, (CARD_TOP + 168) * k);
                Glass.plate(c, rect, 32 * k, k, fill, stroke);
                if (i < code.length()) c.drawText(String.valueOf(code.charAt(i)), rect.centerX(), rect.centerY() + Ui.halfCap(slotPaint), slotPaint);
            }
            float y = (CARD_TOP + 168 + 64) * k;
            drawWrapped(c, "On a phone or computer signed in to Jellyfin, open your user settings → Quick Connect and enter this code.",
                    112 * k, y - 26 * k, 1000 * k);
            String status = code.isEmpty() ? "Getting a code…" : "Waiting for approval";
            float sy = (CARD_TOP + 168 + 64 + 210) * k;
            Ui.spinner(c, 112 * k + 14 * k, sy - 9 * k, 12 * k, seconds, spinnerPaint);
            c.drawText(status, 112 * k + 44 * k, sy, hintPaint);
            float bottom = CARD_TOP + 2 * Glass.PLATE_INSET + rows.size() * (Glass.ROW_H + Glass.ROW_GAP) - Glass.ROW_GAP;
            tmp.set(1248 * k, CARD_TOP * k, 1808 * k, bottom * k);
            Glass.plate(c, tmp, k, fill, stroke);
            for (int i = 0; i < rows.size(); i++) {
                sideBox(i, rect);
                drawRow(c, rows.get(i), rect, seconds);
            }
            if (!rows.isEmpty() && focus < rows.size()) {
                sideBox(focus, rect);
                Glass.row(c, rect, k, fill, stroke, true);
            }
        }

        private void drawAddress(Canvas c) {
            tmp.set(CARD_L * k, CARD_TOP * k, CARD_R * k, (CARD_TOP + 2 * Glass.PLATE_INSET + Glass.ROW_H) * k);
            Glass.plate(c, tmp, k, fill, stroke);
            rowBox(0, rect);
            rowTitle.setColor(INK);
            float baseline = rect.centerY() + Ui.halfCap(rowTitle), textEnd = rect.left + INSET * k + rowTitle.measureText(address.toString());
            c.drawText(address.toString(), rect.left + INSET * k, baseline, rowTitle);
            fill.setColor(INK);
            c.drawRect(textEnd + 4 * k, rect.centerY() - 20 * k, textEnd + 7 * k, rect.centerY() + 20 * k, fill);
            if (!error.isEmpty()) {
                hintPaint.setColor(0xFFFFC266);
                c.drawText("No Jellyfin server found there.", CARD_L * k, (CARD_TOP + 2 * Glass.PLATE_INSET + Glass.ROW_H + 56) * k, hintPaint);
                hintPaint.setColor(MUTE);
            }
        }

        private void drawKeys(Canvas c) {
            for (int i = 0; i < 13; i++) {
                keyBox(i, rect);
                Glass.plate(c, rect, 28 * k, k, fill, stroke);
                drawKey(c, i);
            }
            keyBox(focus, rect);
            Glass.focus(c, rect, 28 * k, k, fill, stroke);
            drawKey(c, focus);
        }

        private void drawKey(Canvas c, int i) {
            if (i == 12) Icons.draw(getContext(), c, R.drawable.icon_check, rect.centerX(), rect.centerY(), 32 * k, INK);
            else if (i == 9) Icons.draw(getContext(), c, R.drawable.icon_backspace, rect.centerX(), rect.centerY(), 32 * k, INK);
            else c.drawText(keyLabel(i), rect.centerX(), rect.centerY() + Ui.halfCap(keyPaint), keyPaint);
        }

        private void move(int dx, int dy) {
            if (keypad()) {
                int rowsN = 5, col = focus % 3 + dx, row = focus / 3 + dy;
                if (col >= 0 && col < 3 && row >= 0 && row < rowsN) {
                    int next = row * 3 + col;
                    if (next < 13) focus = next;
                }
                return;
            }
            int step = state == NOTICE ? dx : dy;
            if (step == 0) return;
            int next = focus + step;
            while (next >= 0 && next < rows.size() && !rows.get(next).focusable) next += step;
            if (next >= 0 && next < rows.size()) focus = next;
        }

        private void press() {
            if (keypad()) {
                if (focus == 12) submitAddress();
                else if (focus == 9) deleteLast();
                else typeInto(keyLabel(focus).charAt(0));
                return;
            }
            if (focus < rows.size() && rows.get(focus).focusable && rows.get(focus).action != null) rows.get(focus).action.run();
        }

        private void typeInto(char ch) {
            error = "";
            if (address.length() < 15) address.append(ch);
        }

        private void deleteLast() {
            error = "";
            if (address.length() > 0) address.setLength(address.length() - 1);
        }

        private boolean back() {
            if (state == ADDRESS) {
                error = "";
                notFound();
                return true;
            }
            return false;
        }

        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            int key = e.getKeyCode();
            if (down && keypad()) {
                if (key >= KeyEvent.KEYCODE_0 && key <= KeyEvent.KEYCODE_9) {
                    typeInto((char) ('0' + key - KeyEvent.KEYCODE_0));
                    invalidate();
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DEL) {
                    deleteLast();
                    invalidate();
                    return true;
                }
            }
            switch (key) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down) move(0, -1);
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down) move(0, 1);
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (down) move(-1, 0);
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down) move(1, 0);
                    break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled()) press();
                        okDown = false;
                    }
                    break;
                case KeyEvent.KEYCODE_BACK:
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled() && !back()) activity.finish();
                        backDown = false;
                    }
                    break;
                default:
                    return false;
            }
            invalidate();
            return true;
        }
    }
}
