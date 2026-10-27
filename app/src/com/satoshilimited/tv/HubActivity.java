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
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class HubActivity extends Activity {
    private HubView view;
    private IdleSaver idle;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        view = new HubView(this);
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

    static final class HubView extends View {
        private final FocusRing focusRing = new FocusRing(this);
        private static final int SEARCH = 0, NOT_FOUND = 2, CODE = 3, LINKING = 4, CONNECTED = 5, ADDRESS = 6;
        private static final int INK = 0xFFF2F2F2, MUTE = 0xB8FFFFFF, DIM = 0x73FFFFFF, CARD = 0xFF1E1E1E, KEY = 0xFF2A2A2A, WARN = 0xFFFFC266;
        private static final float CARD_L = 112, CARD_R = 1112, CARD_TOP = 330, INSET = 32;
        private static final int CODE_LENGTH = 6;

        private static final class Row {
            String title, value = "";
            boolean focusable = true, dim, check, spinner;
            Runnable action;
        }

        private final HubActivity activity;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final android.graphics.Path tmpPath = new android.graphics.Path();
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF(), tmp = new RectF();
        private final List<Row> rows = new ArrayList<>();
        private final List<ArcaHub.Found> hubs = new ArrayList<>();
        private final StringBuilder code = new StringBuilder(), address = new StringBuilder();
        private ArcaHub.Found target;
        private ArcaHub.Folders folders;
        private boolean loadingFolders;
        private int state = SEARCH, focus;
        private String error = "";
        private boolean running, okDown, backDown;
        private long startedAt;
        private int w, h;
        private float k = 1;
        private LinearGradient background;
        private Header header;
        private Paint rowTitle, rowValue, hintPaint, slotPaint, keyPaint, spinnerPaint;

        private final Runnable tick = new Runnable() {
            @Override public void run() {
                if (!running) return;
                invalidate();
                handler.postDelayed(this, spinning() ? 33 : 1000);
            }
        };

        HubView(HubActivity a) {
            super(a);
            activity = a;
            stroke.setStyle(Paint.Style.STROKE);
            setFocusable(true);
            setContentDescription("arca hub");
            if (ArcaHub.connected(a)) showConnected();
            else startScan();
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

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            background = new LinearGradient(0, 0, 0, h, new int[] {0xFF141414, 0xFF0E0E0E, 0xFF0A0A0A}, new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP);
            header = new Header(k);
            rowTitle = Ui.text(Ui.medium, Ui.BODY * k, INK);
            rowValue = Ui.text(Ui.regular, 28 * k, MUTE);
            rowValue.setTextAlign(Paint.Align.RIGHT);
            hintPaint = Ui.text(Ui.regular, Ui.LABEL * k, MUTE);
            slotPaint = Ui.numbers(Ui.light, 84 * k, INK);
            slotPaint.setTextAlign(Paint.Align.CENTER);
            keyPaint = Ui.numbers(Ui.regular, 44 * k, INK);
            keyPaint.setTextAlign(Paint.Align.CENTER);
            spinnerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            spinnerPaint.setStyle(Paint.Style.STROKE);
            spinnerPaint.setStrokeCap(Paint.Cap.ROUND);
            spinnerPaint.setStrokeWidth(3.5f * k);
            spinnerPaint.setColor(INK);
        }

        private boolean spinning() {
            return state == SEARCH || state == LINKING || (state == CONNECTED && loadingFolders);
        }

        private boolean keypad() {
            return state == CODE || state == ADDRESS || state == LINKING;
        }

        private void startScan() {
            state = SEARCH;
            error = "";
            buildRows();
            ArcaHub.scan((found, err) -> {
                if (state != SEARCH) return;
                hubs.clear();
                hubs.addAll(found);
                if (hubs.isEmpty()) {
                    state = NOT_FOUND;
                    focus = 0;
                    buildRows();
                } else {
                    chooseHub(hubs.get(0));
                }
                invalidate();
            });
        }

        private void showConnected() {
            state = CONNECTED;
            folders = null;
            loadingFolders = true;
            focus = 0;
            buildRows();
            ArcaHub.folders(getContext(), (result, err) -> {
                loadingFolders = false;
                if (ArcaHub.REVOKED.equals(err)) {
                    activity.finish();
                    return;
                }
                folders = err == null ? result : null;
                buildRows();
                invalidate();
                if (folders != null) HubCounts.refresh(getContext(), () -> {
                    if (state != CONNECTED) return;
                    buildRows();
                    invalidate();
                });
            });
        }

        private void chooseHub(ArcaHub.Found hub) {
            target = hub;
            code.setLength(0);
            error = "";
            state = CODE;
            focus = 4;
            buildRows();
        }

        private void openAddress() {
            address.setLength(0);
            address.append(LanScan.prefix());
            error = "";
            state = ADDRESS;
            focus = 10;
            buildRows();
        }

        private void submitCode() {
            if (code.length() != CODE_LENGTH || target == null) return;
            state = LINKING;
            error = "";
            ArcaHub.pair(getContext(), target, code.toString(), (result, err) -> {
                if (err == null) {
                    showConnected();
                } else {
                    code.setLength(0);
                    error = err;
                    state = CODE;
                    buildRows();
                }
                invalidate();
            });
        }

        private void submitAddress() {
            String host = address.toString().trim();
            if (host.isEmpty()) return;
            ArcaHub.probe(host, (found, err) -> {
                if (found != null) {
                    chooseHub(found);
                } else {
                    error = ArcaHub.UNREACHABLE;
                }
                invalidate();
            });
        }

        private void addRow(String title, String value, boolean focusable, Runnable action) {
            Row r = new Row();
            r.title = title;
            r.value = value;
            r.focusable = focusable;
            r.dim = !focusable;
            r.action = action;
            rows.add(r);
        }

        private void buildRows() {
            rows.clear();
            switch (state) {
                case SEARCH: {
                    Row r = new Row();
                    r.title = "Looking for your hub";
                    r.focusable = false;
                    r.spinner = true;
                    rows.add(r);
                    break;
                }
                case NOT_FOUND:
                    addRow("No hub found", "", false, null);
                    addRow("Try again", "", true, this::startScan);
                    addRow("Enter address", "", true, this::openAddress);
                    focus = 1;
                    break;
                case CONNECTED: {
                    boolean photos = folders != null && !folders.photos.isEmpty(), music = folders != null && !folders.music.isEmpty();
                    Row p = new Row();
                    p.title = "Photos";
                    p.focusable = false;
                    p.check = photos;
                    p.spinner = loadingFolders;
                    p.value = loadingFolders ? "" : photos ? HubCounts.text(getContext(), "photos") : "Not set up";
                    p.dim = !photos;
                    rows.add(p);
                    Row m = new Row();
                    m.title = "Music";
                    m.focusable = false;
                    m.check = music;
                    m.spinner = loadingFolders;
                    m.value = loadingFolders ? "" : music ? HubCounts.text(getContext(), "music") : "Not set up";
                    m.dim = !music;
                    rows.add(m);
                    boolean video = folders != null && !folders.video.isEmpty();
                    Row v = new Row();
                    v.title = "Movies";
                    v.focusable = false;
                    v.check = video;
                    v.spinner = loadingFolders;
                    v.value = loadingFolders ? "" : video ? HubCounts.text(getContext(), "video") : "Not set up";
                    v.dim = !video;
                    rows.add(v);
                    addRow("Disconnect", "", true, () -> {
                        ArcaHub.disconnect(getContext());
                        activity.finish();
                    });
                    focus = 3;
                    break;
                }
                default:
                    break;
            }
            if (focus >= rows.size() && !keypad()) focus = Math.max(0, rows.size() - 1);
        }

        private String title() {
            switch (state) {
                case CODE:
                case LINKING: return "Connect to " + (target == null ? "hub" : target.name);
                case CONNECTED: return ArcaHub.name(getContext());
                case ADDRESS: return "Hub address";
                default: return "arca hub";
            }
        }

        private void rowBox(int i, RectF out) {
            float top = CARD_TOP + Glass.PLATE_INSET + i * (Glass.ROW_H + Glass.ROW_GAP);
            out.set((CARD_L + Glass.PLATE_INSET) * k, top * k, (CARD_R - Glass.PLATE_INSET) * k, (top + Glass.ROW_H) * k);
        }

        private void keyBox(int i, RectF out) {
            float gap = 20, width = (560 - 2 * gap) / 3f, height = 112;
            float x = 1248 + (i % 3) * (width + gap), y = CARD_TOP + (i / 3) * (height + gap);
            out.set(x * k, y * k, (x + width) * k, (y + height) * k);
        }

        private int keyCount() {
            return state == ADDRESS ? 13 : 11;
        }

        private int submitKey() {
            return state == ADDRESS ? 12 : -1;
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
            if (state == CODE || state == LINKING) drawCode(c, seconds);
            else if (state == ADDRESS) drawAddress(c);
            else drawRows(c, seconds);
            if (keypad()) drawKeys(c);
        }

        private void drawRows(Canvas c, float seconds) {
            float bottom = CARD_TOP + 2 * Glass.PLATE_INSET + rows.size() * (Glass.ROW_H + Glass.ROW_GAP) - Glass.ROW_GAP;
            tmp.set(CARD_L * k, CARD_TOP * k, CARD_R * k, bottom * k);
            Glass.plate(c, tmp, k, fill, stroke);
            for (int i = 0; i < rows.size(); i++) {
                Row r = rows.get(i);
                rowBox(i, rect);
                rowTitle.setColor(r.dim && !r.focusable ? DIM : INK);
                float base = rect.centerY() + Ui.halfCap(rowTitle);
                c.drawText(r.title, rect.left + INSET * k, base, rowTitle);
                float right = rect.right - INSET * k;
                if (r.spinner) Ui.spinner(c, right - 12 * k, rect.centerY(), 12 * k, seconds, spinnerPaint);
                else if (r.check) {
                    drawCheck(c, right - 16 * k, rect.centerY());
                    if (!r.value.isEmpty()) {
                        rowValue.setColor(MUTE);
                        c.drawText(r.value, right - 48 * k, base, rowValue);
                    }
                }
                else if (!r.value.isEmpty()) {
                    rowValue.setColor(r.dim ? DIM : MUTE);
                    c.drawText(r.value, right, base, rowValue);
                }
            }
            rowTitle.setColor(INK);
            if (!rows.isEmpty() && focus < rows.size() && rows.get(focus).focusable) {
                rowBox(focus, rect);
                focusRing.row(c, rect, "rows", k, fill, stroke);
            }
            float y = (bottom + 56) * k;
            String text = state == NOT_FOUND ? "Make sure your hub is on and connected to the same network as this TV."
                    : state == CONNECTED ? "Your photos and music play straight from " + ArcaHub.name(getContext()) + "." : "";
            if (!text.isEmpty()) c.drawText(text, CARD_L * k, y, hintPaint);
        }

        private void drawCode(Canvas c, float seconds) {
            float width = 136, gap = 20;
            for (int i = 0; i < CODE_LENGTH; i++) {
                float x = 112 + i * (width + gap);
                rect.set(x * k, CARD_TOP * k, (x + width) * k, (CARD_TOP + 168) * k);
                Glass.plate(c, rect, 32 * k, k, fill, stroke);
                boolean active = state == CODE && i == code.length();
                stroke.setStrokeWidth((active ? 3 : 1.5f) * k);
                stroke.setColor(active ? INK : 0x22FFFFFF);
                tmp.set(rect.left + 1 * k, rect.top + 1 * k, rect.right - 1 * k, rect.bottom - 1 * k);
                c.drawRoundRect(tmp, 32 * k, 32 * k, stroke);
                if (i < code.length()) c.drawText(String.valueOf(code.charAt(i)), rect.centerX(), rect.centerY() + Ui.halfCap(slotPaint), slotPaint);
            }
            String name = target == null ? "your hub" : target.name;
            c.drawText(state == LINKING ? "Connecting…" : "Enter the code shown on " + name, 112 * k, (CARD_TOP + 168 + 56) * k, hintPaint);
            if (state == LINKING) Ui.spinner(c, 112 * k + hintPaint.measureText("Connecting…") + 36 * k, (CARD_TOP + 168 + 46) * k, 12 * k, seconds, spinnerPaint);
            if (!error.isEmpty()) {
                hintPaint.setColor(WARN);
                c.drawText(errorText(), 112 * k, (CARD_TOP + 168 + 110) * k, hintPaint);
                hintPaint.setColor(MUTE);
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
                hintPaint.setColor(WARN);
                c.drawText(errorText(), CARD_L * k, (CARD_TOP + 2 * Glass.PLATE_INSET + Glass.ROW_H + 56) * k, hintPaint);
                hintPaint.setColor(MUTE);
            }
        }

        private String errorText() {
            switch (error) {
                case ArcaHub.INVALID_CODE: return "That code didn't work. Ask for a new one.";
                case ArcaHub.TOO_MANY: return "Too many tries. Ask for a new code.";
                case ArcaHub.NOT_OPEN: return (target == null ? "Your hub" : target.name) + " isn't open to your network yet.";
                default: return state == ADDRESS ? "No hub found there." : "Couldn't reach " + (target == null ? "your hub" : target.name) + ".";
            }
        }

        private void drawKeys(Canvas c) {
            int count = keyCount(), submitIndex = submitKey();
            for (int i = 0; i < count; i++) {
                keyBox(i, rect);
                Glass.plate(c, rect, 28 * k, k, fill, stroke);
                if (i == submitIndex) drawCheck(c, rect.centerX(), rect.centerY());
                else if (i == 9) drawBackspace(c, rect.centerX(), rect.centerY());
                else c.drawText(keyLabel(i), rect.centerX(), rect.centerY() + Ui.halfCap(keyPaint), keyPaint);
            }
            if (state != LINKING) {
                keyBox(focus, rect);
                focusRing.focus(c, rect, 28 * k, "keys", k, fill, stroke);
                if (focus == submitIndex) drawCheck(c, rect.centerX(), rect.centerY());
                else if (focus == 9) drawBackspace(c, rect.centerX(), rect.centerY());
                else c.drawText(keyLabel(focus), rect.centerX(), rect.centerY() + Ui.halfCap(keyPaint), keyPaint);
            }
        }

        private void drawBackspace(Canvas c, float cx, float cy) {
            Icons.draw(getContext(), c, R.drawable.icon_backspace, cx, cy, 32 * k, INK);
        }

        private void drawCheck(Canvas c, float cx, float cy) {
            Icons.draw(getContext(), c, R.drawable.icon_check, cx, cy, 32 * k, INK);
        }

        private void move(int dx, int dy) {
            focusRing.pressed(dx, dy);
            if (keypad()) {
                if (state == LINKING) return;
                int rowsN = (keyCount() + 2) / 3;
                int col = focus % 3 + dx, row = focus / 3 + dy;
                if (col >= 0 && col < 3 && row >= 0 && row < rowsN) {
                    int next = row * 3 + col;
                    if (next < keyCount()) focus = next;
                }
                return;
            }
            int next = focus + dy;
            while (next >= 0 && next < rows.size() && !rows.get(next).focusable) next += dy;
            if (dy != 0 && next >= 0 && next < rows.size()) focus = next;
        }

        private void press() {
            if (keypad()) {
                if (state == LINKING) return;
                if (focus == submitKey()) {
                    submitAddress();
                } else if (focus == 9) {
                    deleteLast();
                } else {
                    String label = keyLabel(focus);
                    typeInto(label.charAt(0));
                }
                return;
            }
            if (focus < rows.size() && rows.get(focus).focusable && rows.get(focus).action != null) rows.get(focus).action.run();
        }

        private void typeInto(char ch) {
            error = "";
            if (state == CODE) {
                if (!Character.isDigit(ch) || code.length() >= CODE_LENGTH) return;
                code.append(ch);
                if (code.length() == CODE_LENGTH) submitCode();
            } else if (state == ADDRESS && address.length() < 15) {
                address.append(ch);
            }
        }

        private void deleteLast() {
            error = "";
            StringBuilder b = state == ADDRESS ? address : code;
            if (b.length() > 0) b.setLength(b.length() - 1);
        }

        private boolean back() {
            if (state == LINKING) return true;
            if (state == ADDRESS) {
                error = "";
                state = NOT_FOUND;
                focus = 0;
                buildRows();
                return true;
            }
            return false;
        }

        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            int key = e.getKeyCode();
            if (down && keypad() && state != LINKING) {
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
