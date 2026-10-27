package com.satoshilimited.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends Activity {
    private SettingsView view;
    private IdleSaver idle;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        view = new SettingsView(this);
        idle = new IdleSaver(this, view);
        setContentView(idle.view());
    }

    @Override protected void onResume() {
        super.onResume();
        view.resume();
        idle.resume();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
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

    static final class SettingsView extends View implements Choreographer.FrameCallback {
        private static final int MAIN = 0, HOME_CHOICE = 1, SHELF_EDIT = 2;
        private static final int BG_TOP = 0xFF141414, BG_MID = 0xFF0E0E0E, BG_BOTTOM = 0xFF0A0A0A;
        private static final int INK = 0xFFF2F2F2, MUTE = 0xB8FFFFFF, DIM = 0x73FFFFFF, CARD = 0xFF1E1E1E, WARN = 0xFFFFC266;
        private static final float CARD_L = 112, CARD_R = 1112, CARD_TOP = 330, INSET = 32;
        private static final int PICK_VISIBLE = 7;

        private final SettingsActivity activity;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final FocusRing focusRing = new FocusRing(this);
        private final List<String[]> shelfRows = new ArrayList<>();
        private final List<String> shelfOrder = new ArrayList<>();
        private final RectF tmp = new RectF(), rowRect = new RectF(), card = new RectF();
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Spoken spoken;
        private JSONObject state = new JSONObject();
        private int screen = MAIN, focus, scroll, shelfCount;
        private static final int[] MAIN_ICONS = {R.drawable.icon_home_screen, R.drawable.icon_hub, R.drawable.icon_screensaver, R.drawable.icon_partly_day, R.drawable.icon_shortcuts, R.drawable.icon_info};
        private static final int[] MAIN_ICONS_COLOURS = {R.drawable.icon_home_screen, R.drawable.icon_hub, R.drawable.icon_screensaver, R.drawable.icon_partly_day, R.drawable.icon_shortcuts, R.drawable.icon_remote, R.drawable.icon_info};
        private String note = "";
        private long noteAt;
        private boolean grabbing, colours;
        private boolean running, okDown, backDown, wasBusy, ringReady;
        private StaticLayout errorLayout;
        private String errorShown;
        private long startedAt, lastNanos, pendingAt;
        private int w, h;
        private float k = 1;
        private LinearGradient background;
        private Header header;
        private Paint titlePaint, crumbPaint, rowTitle, rowValue, rowSub, hintPaint, headerPaint, spinnerPaint;

        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!running) return;
                state = HomeMode.state(getContext());
                boolean nowBusy = !busyText().isEmpty();
                if (nowBusy) pendingAt = 0;
                JSONArray notices = state.optJSONArray("notices");
                if (wasBusy && !nowBusy && notices != null && notices.length() > 0) {
                    JSONObject newest = notices.optJSONObject(0);
                    if (newest != null) announceForAccessibility(newest.optString("text"));
                }
                wasBusy = nowBusy;
                invalidate();
                handler.postDelayed(this, busy() ? 500 : 2000);
            }
        };

        SettingsView(SettingsActivity a) {
            super(a);
            activity = a;
            stroke.setStyle(Paint.Style.STROKE);
            setFocusable(true);
            setContentDescription("arca TV settings");
            spoken = new Spoken(this, new Spoken.Source() {
                @Override public void items(List<Integer> out) { for (int i = 1; i <= rowCount(); i++) out.add(i); }
                @Override public String text(int id) { return id >= 1 && id <= rowCount() ? rowText(id - 1) : ""; }
                @Override public boolean bounds(int id, Rect out) {
                    if (id < 1 || id > rowCount()) return false;
                    rowBox(id - 1, tmp);
                    tmp.round(out);
                    return true;
                }
                @Override public int focused() { return focus + 1; }
                @Override public void focus(int id) {
                    focus = id - 1;
                    invalidate();
                }
                @Override public void click(int id) { activate(); }
            });
        }

        @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return spoken;
        }

        void resume() {
            if (running) return;
            running = true;
            shelfCount = HomeApps.shelfPackages(getContext()).size();
            colours = RemoteKeys.hasColours();
            startedAt = SystemClock.uptimeMillis();
            lastNanos = 0;
            handler.post(poll);
            Choreographer.getInstance().postFrameCallback(this);
            spoken.readAgain();
        }

        void pause() {
            running = false;
            handler.removeCallbacks(poll);
            Choreographer.getInstance().removeFrameCallback(this);
        }

        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f);
            lastNanos = frameTimeNanos;
            boolean moving = busy();
            if (rowCount() > 0) {
                if (!ringReady) {
                    focusRing.snap();
                    ringReady = true;
                }
                moving |= focusRing.moving();
            }
            if (moving) invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            background = new LinearGradient(0, 0, 0, h, new int[] {BG_TOP, BG_MID, BG_BOTTOM}, new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP);
            header = new Header(k);
            titlePaint = Ui.text(Ui.light, 64 * k, INK);
            titlePaint.setLetterSpacing(-0.03f);
            crumbPaint = Ui.text(Ui.medium, 24 * k, MUTE);
            rowTitle = Ui.text(Ui.medium, Ui.BODY * k, INK);
            rowValue = Ui.text(Ui.regular, 28 * k, MUTE);
            rowValue.setTextAlign(Paint.Align.RIGHT);
            rowSub = Ui.text(Ui.regular, Ui.LABEL * k, MUTE);
            hintPaint = Ui.text(Ui.regular, Ui.LABEL * k, MUTE);
            headerPaint = Ui.text(Ui.semibold, Ui.TAG * k, MUTE);
            headerPaint.setLetterSpacing(0.11f);
            spinnerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            spinnerPaint.setStyle(Paint.Style.STROKE);
            spinnerPaint.setStrokeCap(Paint.Cap.ROUND);
            spinnerPaint.setStrokeWidth(3.5f * k);
            spinnerPaint.setColor(INK);
            ringReady = false;
        }

        private boolean busy() {
            return !busyText().isEmpty() || (pendingAt != 0 && SystemClock.uptimeMillis() - pendingAt < 10_000);
        }

        private String busyText() {
            long at = state.optLong("busyAt"), now = SystemClock.elapsedRealtime();
            boolean stale = now < at || now - at > 10 * 60_000L;
            return stale ? "" : state.optString("busy");
        }

        private boolean freeActive() {
            return HomeMode.FREE.equals(state.optString("mode")) && !state.optBoolean("homeReverted");
        }

        private int rowCount() {
            switch (screen) {
                case MAIN: return mainRows().length;
                case HOME_CHOICE: return 2;
                default: return Math.min(PICK_VISIBLE, shelfRows.size());
            }
        }

        private float rowHeight() {
            return screen == MAIN || screen == SHELF_EDIT ? Glass.ROW_H : 104;
        }

        private float pad() {
            return Glass.PLATE_INSET;
        }

        private float gap() {
            return Glass.ROW_GAP;
        }

        private void rowBox(int row, RectF out) {
            float top = CARD_TOP + pad() + row * (rowHeight() + gap());
            out.set((CARD_L + pad()) * k, top * k, (CARD_R - pad()) * k, (top + rowHeight()) * k);
        }

        private String heading() {
            switch (screen) {
                case HOME_CHOICE: return "Home screen";
                case SHELF_EDIT: return "Shortcuts";
                default: return "Settings";
            }
        }

        private String[][] mainRows() {
            String mode = freeActive() ? "arca TV" : "Original";
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[] {"Home screen", busy() ? "" : mode});
            rows.add(new String[] {"Hub", ArcaHub.connected(getContext()) ? ArcaHub.name(getContext()) : "Not connected"});
            rows.add(new String[] {"Screensaver", !Prefs.screensaver() ? "Off" : PhotoStream.available(getContext()) ? "Photos" : "Colours"});
            rows.add(new String[] {"Weather", weatherValue()});
            rows.add(new String[] {"Shortcuts", shelfCount + " of " + HomeApps.MAX_SHELF});
            if (colours) rows.add(new String[] {"Colour buttons", RemoteColours.count(getContext()) + " of " + RemoteColours.NAMES.length});
            rows.add(new String[] {"Version", releaseName(getContext())});
            return rows.toArray(new String[0][]);
        }

        private static String weatherValue() {
            if (!Prefs.weather()) return "Off";
            return Prefs.city().isEmpty() ? "On" : Prefs.city();
        }

        private String rowText(int row) {
            switch (screen) {
                case MAIN: {
                    String[][] rows = mainRows();
                    if (row == 0 && busy()) return "Home screen, " + (busyText().isEmpty() ? "Starting" : busyText());
                    return row < rows.length ? rows[row][0] + (rows[row][1].isEmpty() ? "" : ", " + rows[row][1]) : "";
                }
                case HOME_CHOICE:
                    return (row == 0 ? "arca TV" : "Original") + ((row == 0) == freeActive() ? ", in use" : "");
                default: {
                    int at = scroll + row;
                    if (at >= shelfRows.size()) return "";
                    int place = shelfOrder.indexOf(shelfRows.get(at)[0]);
                    return shelfRows.get(at)[1] + ", " + (place >= 0 ? "on the shelf, place " + (place + 1) : "not on the shelf");
                }
            }
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            fill.setColor(0xFF000000);
            fill.setShader(background);
            c.drawRect(0, 0, w, h, fill);
            fill.setShader(null);
            long now = System.currentTimeMillis();
            header.drawScreen(c, heading(), Ui.clock(now), Ui.meridiem(now), Ui.date(now));
            rowTitle.setTextSize((screen == MAIN ? Ui.BODY : Ui.HEADING) * k);
            rowValue.setTextSize((screen == MAIN ? Ui.LABEL : Ui.BODY) * k);
            int rows = rowCount();
            float cardBottom = CARD_TOP + 2 * pad() + rows * (rowHeight() + gap()) - gap();
            card.set(CARD_L * k, CARD_TOP * k, CARD_R * k, cardBottom * k);
            Glass.plate(c, card, k, fill, stroke);
            if (screen == MAIN) drawMain(c);
            else if (screen == SHELF_EDIT) drawShelf(c);
            else drawChoice(c);
            if (ringReady && rows > 0) {
                rowBox(Math.min(focus, rowCount() - 1), tmp);
                focusRing.at(focus);
                focusRing.row(c, tmp, screen, k, fill, stroke);
            }
            drawFooter(c, cardBottom);
        }

        private void drawMain(Canvas c) {
            String[][] rows = mainRows();
            for (int i = 0; i < rows.length; i++) {
                rowBox(i, rowRect);
                float base = rowRect.centerY() + Ui.halfCap(rowTitle);
                rowTitle.setColor(INK);
                rowValue.setColor(MUTE);
                float iconX = rowRect.left + INSET * k;
                Icons.draw(getContext(), c, (colours ? MAIN_ICONS_COLOURS : MAIN_ICONS)[i], iconX + 14 * k, rowRect.centerY(), 28 * k, i == focus ? Icons.INK : Icons.MUTE);
                c.drawText(rows[i][0], iconX + 44 * k, base, rowTitle);
                float right = rowRect.right - INSET * k;
                if (i == 0 && busy()) {
                    String text = busyText().isEmpty() ? "Starting…" : busyText();
                    String shown = TextUtils.ellipsize(text, new TextPaint(rowValue), 470 * k, TextUtils.TruncateAt.END).toString();
                    c.drawText(shown, right, base, rowValue);
                    Ui.spinner(c, right - rowValue.measureText(shown) - 26 * k, rowRect.centerY(), 12 * k, (SystemClock.uptimeMillis() - startedAt) / 1000f * 1.3f, spinnerPaint);
                } else {
                    c.drawText(rows[i][1], right, base, rowValue);
                }
            }
            rowTitle.setColor(INK);
            rowValue.setColor(MUTE);
        }

        private void drawChoice(Canvas c) {
            String[][] options = {{"arca TV", "Your home screen, no ads"}, {"Original", "The TV's factory home screen"}};
            int current = freeActive() ? 0 : 1;
            for (int i = 0; i < options.length; i++) {
                rowBox(i, rowRect);
                c.drawText(options[i][0], rowRect.left + INSET * k, rowRect.top + 42 * k, rowTitle);
                c.drawText(fit(options[i][1], rowSub, rowRect.width() - 120 * k), rowRect.left + INSET * k, rowRect.top + 76 * k, rowSub);
                if (i == current) drawCheck(c, rowRect.right - (INSET + 16) * k, rowRect.centerY());
            }
        }

        private void drawShelf(Canvas c) {
            for (int i = 0; i < rowCount(); i++) {
                String[] app = shelfRows.get(scroll + i);
                boolean onShelf = shelfOrder.contains(app[0]);
                rowBox(i, rowRect);
                if (scroll + i == shelfOrder.size() && shelfOrder.size() > 0) {
                    stroke.setStrokeWidth(2 * k);
                    stroke.setColor(0xFF333333);
                    c.drawLine(rowRect.left + INSET * k, rowRect.top - 3 * k, rowRect.right - INSET * k, rowRect.top - 3 * k, stroke);
                }
                boolean grabbed = grabbing && i == focus;
                if (grabbed) {
                    fill.setColor(0xFF2F2F2F);
                    c.drawRoundRect(rowRect, Glass.ROW_RADIUS * k, Glass.ROW_RADIUS * k, fill);
                }
                rowTitle.setColor(onShelf ? INK : DIM);
                rowValue.setColor(onShelf ? MUTE : DIM);
                float base = rowRect.centerY() + Ui.halfCap(rowTitle);
                c.drawText(fit(app[1], rowTitle, 760 * k), rowRect.left + INSET * k, base, rowTitle);
                String value = grabbed ? "↑ ↓" : onShelf ? "" : "Add";
                if (!value.isEmpty()) c.drawText(value, rowRect.right - INSET * k, base, rowValue);
            }
            rowTitle.setColor(INK);
            rowValue.setColor(MUTE);
        }

        private void drawFooter(Canvas c, float cardBottom) {
            float y = (cardBottom + 56) * k;
            if (screen == SHELF_EDIT) {
                boolean showNote = !note.isEmpty() && SystemClock.uptimeMillis() - noteAt < 4000;
                hintPaint.setColor(showNote ? WARN : MUTE);
                c.drawText(showNote ? note : grabbing ? "↑ ↓ move it · OK drop it · ← take it off" : shelfOrder.size() + " of " + HomeApps.MAX_SHELF + " · OK to move", CARD_L * k, y, hintPaint);
                hintPaint.setColor(MUTE);
                return;
            }
            if (screen == MAIN && !note.isEmpty() && SystemClock.uptimeMillis() - noteAt < 4000) {
                hintPaint.setColor(WARN);
                c.drawText(note, CARD_L * k, y, hintPaint);
                hintPaint.setColor(MUTE);
                y += 44 * k;
            }
            if (screen != MAIN) return;
            boolean working = busy();
            String error = state.optString("error");
            String busyText = busyText();
            if (working && (busyText.contains("Allow") || busyText.contains("Permitir"))) {
                headerPaint.setColor(WARN);
                c.drawText("On the TV: tick “Always allow” and press OK.", CARD_L * k, y, hintPaint);
                headerPaint.setColor(MUTE);
                y += 44 * k;
            } else if (!working && !error.isEmpty()) {
                int width = Math.round(900 * k);
                if (errorLayout == null || !error.equals(errorShown) || errorLayout.getWidth() != width) {
                    errorShown = error;
                    TextPaint warn = new TextPaint(Ui.text(Ui.regular, Ui.LABEL * k, WARN));
                    errorLayout = StaticLayout.Builder.obtain(error, 0, error.length(), warn, width)
                            .setMaxLines(5).setEllipsize(TextUtils.TruncateAt.END).build();
                }
                c.save();
                c.translate(CARD_L * k, y - errorLayout.getLineBaseline(0));
                errorLayout.draw(c);
                c.restore();
                y += errorLayout.getLineBaseline(errorLayout.getLineCount() - 1) - errorLayout.getLineBaseline(0) + 44 * k;
            }
        }

        private void drawCheck(Canvas c, float cx, float cy) {
            Icons.draw(getContext(), c, R.drawable.icon_check, cx, cy, 32 * k, INK);
        }

        private String fit(String text, Paint paint, float max) {
            if (paint.measureText(text) <= max) return text;
            int n = paint.breakText(text, true, max - paint.measureText("…"), null);
            return text.substring(0, Math.max(0, n)).trim() + "…";
        }

        private static String releaseName(Context c) {
            try {
                return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                return "";
            }
        }

        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            focusRing.key(e);
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down && grabbing) {
                        moveShelfApp(-1);
                        invalidate();
                        return true;
                    }
                    if (down && focus > 0) focus--;
                    else if (down && screen == SHELF_EDIT && scroll > 0) scroll--;
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down && grabbing) {
                        moveShelfApp(1);
                        invalidate();
                        return true;
                    }
                    if (down && focus < rowCount() - 1) focus++;
                    else if (down && screen == SHELF_EDIT && scroll + rowCount() < shelfRows.size()) scroll++;
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down && screen == SHELF_EDIT && grabbing && e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT) {
                        String pkg = shelfRows.get(scroll + focus)[0];
                        shelfOrder.remove(pkg);
                        grabbing = false;
                        refreshShelfRows(pkg);
                        invalidate();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled() && spoken.keyClick()) activate();
                        okDown = false;
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (screen == MAIN) return false;
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled()) {
                            if (grabbing) {
                                grabbing = false;
                            } else {
                                focus = screen == HOME_CHOICE ? 0 : 4;
                                screen = MAIN;
                                shelfCount = HomeApps.shelfPackages(getContext()).size();
                            }
                        }
                        backDown = false;
                    }
                    invalidate();
                    spoken.focusMoved();
                    return true;
                default:
                    return false;
            }
        }

        private void openShelfEditor() {
            shelfOrder.clear();
            shelfOrder.addAll(HomeApps.shelfPackages(getContext()));
            rebuildShelfRows(HomeApps.shelfCandidates(getContext()));
            scroll = 0;
            focus = 0;
            grabbing = false;
            screen = SHELF_EDIT;
        }

        private void rebuildShelfRows(List<String[]> candidates) {
            shelfRows.clear();
            for (String pkg : shelfOrder) {
                for (String[] cand : candidates) if (cand[0].equals(pkg)) { shelfRows.add(cand); break; }
            }
            for (String[] cand : candidates) if (!shelfOrder.contains(cand[0])) shelfRows.add(cand);
        }

        private void refreshShelfRows(String focusedPkg) {
            rebuildShelfRows(new ArrayList<>(shelfRows));
            for (int i = 0; i < shelfRows.size(); i++) {
                if (!shelfRows.get(i)[0].equals(focusedPkg)) continue;
                int visible = rowCount();
                if (i < scroll) scroll = i;
                else if (i >= scroll + visible) scroll = i - visible + 1;
                focus = i - scroll;
                break;
            }
            Prefs.setShelf(shelfOrder);
        }

        private void toggleShelfApp() {
            String pkg = shelfRows.get(scroll + focus)[0];
            if (shelfOrder.contains(pkg)) shelfOrder.remove(pkg);
            else if (shelfOrder.size() < HomeApps.MAX_SHELF) shelfOrder.add(pkg);
            else return;
            refreshShelfRows(pkg);
        }

        private void moveShelfApp(int dir) {
            String pkg = shelfRows.get(scroll + focus)[0];
            int at = shelfOrder.indexOf(pkg), to = at + dir;
            if (at < 0 || to < 0 || to >= shelfOrder.size()) return;
            shelfOrder.remove(at);
            shelfOrder.add(to, pkg);
            refreshShelfRows(pkg);
        }

        private void toggleOrGrab() {
            String[] app = shelfRows.get(scroll + focus);
            if (grabbing) {
                grabbing = false;
            } else if (shelfOrder.contains(app[0])) {
                grabbing = true;
            } else if (shelfOrder.size() >= HomeApps.MAX_SHELF) {
                note = "The shortcuts are full: take one off first.";
                noteAt = SystemClock.uptimeMillis();
            } else {
                shelfOrder.add(app[0]);
                refreshShelfRows(app[0]);
            }
        }

        private void activate() {
            Context c = getContext();
            if (screen == HOME_CHOICE) {
                boolean wantFree = focus == 0;
                boolean already = wantFree ? freeActive() : HomeMode.ORIGINAL.equals(state.optString("mode", HomeMode.ORIGINAL));
                if (!already && !busy()) {
                    HomeMode.request(c, wantFree ? HomeMode.FREE : HomeMode.ORIGINAL);
                    pendingAt = SystemClock.uptimeMillis();
                }
                screen = MAIN;
                focus = 0;
            } else if (screen == SHELF_EDIT) {
                toggleOrGrab();
                invalidate();
                spoken.focusMoved();
                return;
            } else if (focus == 0) {
                if (busy()) return;
                screen = HOME_CHOICE;
                focus = freeActive() ? 0 : 1;
            } else if (focus == 1) {
                c.startActivity(new Intent(c, HubActivity.class));
            } else if (focus == 2) {
                Prefs.setScreensaver(!Prefs.screensaver());
                HomeMode.requestSaver(c);
            } else if (focus == 3) {
                c.startActivity(new Intent(c, LocationActivity.class));
            } else if (focus == 4) {
                openShelfEditor();
            } else if (focus == 5 && colours) {
                c.startActivity(new Intent(c, ColourButtonsActivity.class));
            }
            invalidate();
            spoken.focusMoved();
        }
    }
}
