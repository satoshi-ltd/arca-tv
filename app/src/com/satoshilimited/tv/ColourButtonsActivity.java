package com.satoshilimited.tv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeProvider;

import java.util.ArrayList;
import java.util.List;

public class ColourButtonsActivity extends Activity {
    private ColoursView view;
    private IdleSaver idle;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        view = new ColoursView(this);
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

    static final class ColoursView extends View implements Choreographer.FrameCallback {
        private static final int LIST = 0, PICK = 1;
        private static final int BG_TOP = 0xFF141414, BG_MID = 0xFF0E0E0E, BG_BOTTOM = 0xFF0A0A0A;
        private static final int INK = 0xFFF2F2F2, MUTE = 0xB8FFFFFF, DIM = 0x73FFFFFF;
        private static final float LIST_L = 112, LIST_R = 1112, LIST_TOP = 330, LIST_ROW = 96, INSET = 32;
        private static final float PICK_TOP = 260, PICK_BOTTOM = 1016, LEFT_L = 112, LEFT_R = 1216, RIGHT_L = 1248, RIGHT_R = 1808;
        private static final float ROW = 76, GAP = 6, PAD_X = 32, PAD_Y = 28, LEFT_HEAD = 50, RIGHT_HEAD = 44;

        private static final class Pick {
            String title, target = "", symbol = "";
            int icon;
            boolean header, dim;
        }

        private final ColourButtonsActivity activity;
        private final FocusRing focusRing = new FocusRing(this);
        private final RectF tmp = new RectF(), rowRect = new RectF(), plate = new RectF();
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG), fade = new Paint();
        private final Path clip = new Path();
        private final Spoken spoken;
        private final List<Pick> left = new ArrayList<>(), right = new ArrayList<>();
        private final List<Integer> leftRows = new ArrayList<>();
        private final String[] values = new String[RemoteColours.NAMES.length];
        private float[] leftTop = new float[0];
        private int screen = LIST, focus, colour, col, leftAt, rightAt;
        private float scrollY;
        private boolean running, okDown, backDown, ringReady, free;
        private long lastNanos;
        private int w, h;
        private float k = 1;
        private LinearGradient background;
        private Header header;
        private Paint rowTitle, rowValue, headerPaint, hintPaint;

        ColoursView(ColourButtonsActivity a) {
            super(a);
            activity = a;
            stroke.setStyle(Paint.Style.STROKE);
            setFocusable(true);
            setContentDescription("Colour buttons");
            spoken = new Spoken(this, new Spoken.Source() {
                @Override public void items(List<Integer> out) { for (int i = 1; i <= count(); i++) out.add(i); }
                @Override public String text(int id) { return id >= 1 && id <= count() ? spokenText(id - 1) : ""; }
                @Override public boolean bounds(int id, Rect out) {
                    if (id < 1 || id > count()) return false;
                    itemRect(id - 1, tmp);
                    tmp.round(out);
                    return true;
                }
                @Override public int focused() { return focusedItem() + 1; }
                @Override public void focus(int id) {
                    setFocusedItem(id - 1);
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
            refresh();
            lastNanos = 0;
            Choreographer.getInstance().postFrameCallback(this);
            spoken.readAgain();
        }

        void pause() {
            running = false;
            Choreographer.getInstance().removeFrameCallback(this);
        }

        private void refresh() {
            Context c = getContext();
            for (int i = 0; i < values.length; i++) values[i] = RemoteColours.label(c, RemoteColours.target(c, i));
            free = HomeMode.homeHeld(c);
            invalidate();
        }

        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f);
            lastNanos = frameTimeNanos;
            boolean moving = false;
            if (count() > 0 && w > 0) {
                if (!ringReady) {
                    focusRing.snap();
                    ringReady = true;
                }
                moving = focusRing.moving();
            }
            if (moving) invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            background = new LinearGradient(0, 0, 0, h, new int[] {BG_TOP, BG_MID, BG_BOTTOM}, new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP);
            fade.setShader(new LinearGradient(0, (PICK_BOTTOM - 120) * k, 0, PICK_BOTTOM * k, 0x000E0E0E, 0xCC0E0E0E, Shader.TileMode.CLAMP));
            header = new Header(k);
            rowTitle = Ui.text(Ui.medium, Ui.BODY * k, INK);
            rowValue = Ui.text(Ui.regular, 28 * k, MUTE);
            rowValue.setTextAlign(Paint.Align.RIGHT);
            headerPaint = Ui.text(Ui.semibold, Ui.TAG * k, MUTE);
            headerPaint.setLetterSpacing(0.11f);
            hintPaint = Ui.text(Ui.regular, Ui.LABEL * k, MUTE);
            ringReady = false;
        }

        private int count() {
            return screen == LIST ? RemoteColours.NAMES.length : leftRows.size() + right.size();
        }

        private int focusedItem() {
            if (screen == LIST) return focus;
            return col == 0 ? leftAt : leftRows.size() + rightAt;
        }

        private void setFocusedItem(int item) {
            if (screen == LIST) {
                focus = item;
            } else if (item < leftRows.size()) {
                col = 0;
                leftAt = item;
                ensureVisible();
            } else {
                col = 1;
                rightAt = item - leftRows.size();
            }
        }

        private void itemRect(int item, RectF out) {
            if (screen == LIST) listRowRect(item, out);
            else if (item < leftRows.size()) leftRowRect(leftRows.get(item), out);
            else rightRowRect(item - leftRows.size(), out);
        }

        private void focusRect(RectF out) {
            itemRect(focusedItem(), out);
        }

        private void listRowRect(int row, RectF out) {
            float top = LIST_TOP + Glass.PLATE_INSET + row * (LIST_ROW + GAP);
            out.set((LIST_L + Glass.PLATE_INSET) * k, top * k, (LIST_R - Glass.PLATE_INSET) * k, (top + LIST_ROW) * k);
        }

        private void leftRowRect(int item, RectF out) {
            float top = PICK_TOP + PAD_Y + leftTop[item] - scrollY;
            out.set((LEFT_L + PAD_X) * k, top * k, (LEFT_R - PAD_X) * k, (top + ROW) * k);
        }

        private void rightRowRect(int row, RectF out) {
            float top = PICK_TOP + PAD_Y + RIGHT_HEAD + row * (ROW + GAP);
            out.set((RIGHT_L + PAD_X) * k, top * k, (RIGHT_R - PAD_X) * k, (top + ROW) * k);
        }

        private String spokenText(int item) {
            if (screen == LIST) return RemoteColours.NAMES[item] + ", " + values[item];
            Pick p = item < leftRows.size() ? left.get(leftRows.get(item)) : right.get(item - leftRows.size());
            return p.title + (p.target.equals(RemoteColours.target(getContext(), colour)) ? ", selected" : "");
        }

        private void openPick(int which) {
            Context c = getContext();
            colour = which;
            left.clear();
            right.clear();
            leftRows.clear();
            left.add(pick("Default", "", R.drawable.icon_none));
            left.add(headline("arca"));
            if (HomeApps.hubAvailable(c, HomeApps.PHOTOS)) left.add(pick("Photos", RemoteColours.PHOTOS, R.drawable.icon_photos));
            if (HomeApps.hubAvailable(c, HomeApps.MUSIC)) left.add(pick("Music", RemoteColours.MUSIC, R.drawable.icon_music));
            left.add(pick("Screensaver", RemoteColours.SAVER, R.drawable.icon_screensaver));
            List<String[]> apps = new ArrayList<>();
            for (String[] app : HomeApps.shelfCandidates(c)) {
                if (!HomeApps.hubApp(app[0]) && !app[0].equals(c.getPackageName())) apps.add(app);
            }
            if (!apps.isEmpty()) left.add(headline("Apps"));
            for (String[] app : apps) left.add(pick(app[1], "app:" + app[0], R.drawable.icon_apps));
            for (HomeApps.Item it : HomeApps.allInputs(c)) {
                if (it.kind != HomeApps.INPUT) continue;
                Pick p = pick(it.label, "input:" + it.inputId, Icons.res(it.symbol));
                p.dim = !it.connected;
                right.add(p);
            }
            leftTop = new float[left.size()];
            float y = 0;
            for (int i = 0; i < left.size(); i++) {
                leftTop[i] = y;
                if (left.get(i).header) {
                    y += LEFT_HEAD;
                } else {
                    leftRows.add(i);
                    y += ROW + GAP;
                }
            }
            String current = RemoteColours.target(c, which);
            col = 0;
            leftAt = 0;
            rightAt = 0;
            for (int i = 0; i < leftRows.size(); i++) if (left.get(leftRows.get(i)).target.equals(current)) leftAt = i;
            for (int i = 0; i < right.size(); i++) {
                if (right.get(i).target.equals(current)) {
                    col = 1;
                    rightAt = i;
                }
            }
            scrollY = 0;
            ensureVisible();
            screen = PICK;
            ringReady = false;
            invalidate();
            spoken.focusMoved();
        }

        private static Pick pick(String title, String target, int icon) {
            Pick p = new Pick();
            p.title = title;
            p.target = target;
            p.icon = icon;
            return p;
        }

        private static Pick headline(String title) {
            Pick p = new Pick();
            p.title = title;
            p.header = true;
            return p;
        }

        private void ensureVisible() {
            if (leftRows.isEmpty()) return;
            int item = leftRows.get(leftAt);
            float top = leftTop[item] - (item > 0 && left.get(item - 1).header ? LEFT_HEAD : 0);
            float viewH = PICK_BOTTOM - PICK_TOP - PAD_Y;
            if (top < scrollY) scrollY = top;
            else if (leftTop[item] + ROW > scrollY + viewH) scrollY = leftTop[item] + ROW - viewH;
        }

        private void select() {
            Context c = getContext();
            Pick p = col == 0 ? left.get(leftRows.get(leftAt)) : right.get(rightAt);
            RemoteColours.set(c, colour, p.target);
            HomeMode.requestKeys(c);
            AppLog.w(RemoteColours.NAMES[colour] + " button set to " + (p.target.isEmpty() ? "default" : p.target));
            screen = LIST;
            focus = colour;
            ringReady = false;
            refresh();
            spoken.focusMoved();
        }

        private void activate() {
            if (screen == LIST) openPick(focus);
            else select();
        }

        private void move(int dir) {
            focusRing.pressed(0, dir);
            if (screen == LIST) {
                focus = Math.max(0, Math.min(RemoteColours.NAMES.length - 1, focus + dir));
            } else if (col == 0) {
                leftAt = Math.max(0, Math.min(leftRows.size() - 1, leftAt + dir));
                ensureVisible();
            } else {
                rightAt = Math.max(0, Math.min(right.size() - 1, rightAt + dir));
            }
            invalidate();
            spoken.focusMoved();
        }

        private void sideways(int to) {
            if (screen != PICK || col == to || (to == 1 && right.isEmpty())) return;
            col = to;
            rightAt = Math.min(rightAt, Math.max(0, right.size() - 1));
            invalidate();
            spoken.focusMoved();
        }

        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down) move(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down) move(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (down) sideways(0);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down) sideways(1);
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
                    if (screen == LIST) return false;
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled()) {
                            screen = LIST;
                            focus = colour;
                            ringReady = false;
                            invalidate();
                            spoken.focusMoved();
                        }
                        backDown = false;
                    }
                    return true;
                default:
                    return false;
            }
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            fill.setColor(0xFF000000);
            fill.setShader(background);
            c.drawRect(0, 0, w, h, fill);
            fill.setShader(null);
            long now = System.currentTimeMillis();
            if (screen == LIST) header.drawScreen(c, "Colour buttons", Ui.clock(now), Ui.meridiem(now), Ui.date(now));
            else header.drawScreen(c, RemoteColours.NAMES[colour] + " button", Ui.clock(now), Ui.meridiem(now), Ui.date(now), RemoteColours.COLOURS[colour]);
            if (screen == LIST) drawList(c);
            else drawPick(c);
            if (ringReady && count() > 0) {
                focusRect(tmp);
                focusRing.row(c, tmp, screen, k, fill, stroke);
            }
        }

        private void drawList(Canvas c) {
            float bottom = LIST_TOP + 2 * Glass.PLATE_INSET + values.length * (LIST_ROW + GAP) - GAP;
            plate.set(LIST_L * k, LIST_TOP * k, LIST_R * k, bottom * k);
            Glass.plate(c, plate, k, fill, stroke);
            for (int i = 0; i < values.length; i++) {
                listRowRect(i, rowRect);
                float base = rowRect.centerY() + Ui.halfCap(rowTitle);
                float iconX = rowRect.left + INSET * k;
                fill.setColor(RemoteColours.COLOURS[i]);
                c.drawCircle(iconX + 14 * k, rowRect.centerY(), 14 * k, fill);
                rowTitle.setColor(INK);
                c.drawText(RemoteColours.NAMES[i], iconX + 44 * k, base, rowTitle);
                rowValue.setColor(values[i].equals("Default") ? DIM : MUTE);
                c.drawText(fit(values[i], rowValue, 520 * k), rowRect.right - INSET * k, base, rowValue);
            }
            hintPaint.setColor(MUTE);
            c.drawText(free ? "The button works as usual" : "Works when arca TV is your home screen", LIST_L * k, (bottom + 56) * k, hintPaint);
        }

        private void drawPick(Canvas c) {
            plate.set(LEFT_L * k, PICK_TOP * k, LEFT_R * k, PICK_BOTTOM * k);
            Glass.plate(c, plate, k, fill, stroke);
            c.save();
            clip.reset();
            clip.addRoundRect(plate, Glass.PLATE_RADIUS * k, Glass.PLATE_RADIUS * k, Path.Direction.CW);
            c.clipPath(clip);
            for (int i = 0; i < left.size(); i++) {
                Pick p = left.get(i);
                float top = PICK_TOP + PAD_Y + leftTop[i] - scrollY;
                if (top + ROW < PICK_TOP || top > PICK_BOTTOM) continue;
                if (p.header) {
                    c.drawText(p.title.toUpperCase(), (LEFT_L + PAD_X + 20) * k, (top + 35) * k, headerPaint);
                } else {
                    leftRowRect(i, rowRect);
                    drawPickRow(c, p, rowRect, leftRows.indexOf(i) == leftAt && col == 0, LEFT_R - LEFT_L - 2 * PAD_X);
                }
            }
            float contentH = leftTop[left.size() - 1] + (left.get(left.size() - 1).header ? LEFT_HEAD : ROW);
            if (contentH > scrollY + PICK_BOTTOM - PICK_TOP - PAD_Y) c.drawRect(plate, fade);
            c.restore();
            float rightH = PAD_Y + RIGHT_HEAD + right.size() * (ROW + GAP) - (right.isEmpty() ? 0 : GAP) + PAD_Y;
            plate.set(RIGHT_L * k, PICK_TOP * k, RIGHT_R * k, (PICK_TOP + rightH) * k);
            Glass.plate(c, plate, k, fill, stroke);
            c.drawText("INPUTS", (RIGHT_L + PAD_X + 20) * k, (PICK_TOP + PAD_Y + 21) * k, headerPaint);
            for (int i = 0; i < right.size(); i++) {
                rightRowRect(i, rowRect);
                drawPickRow(c, right.get(i), rowRect, i == rightAt && col == 1, RIGHT_R - RIGHT_L - 2 * PAD_X);
            }
        }

        private void drawPickRow(Canvas c, Pick p, RectF r, boolean focused, float width) {
            int ink = p.dim ? DIM : INK;
            Icons.draw(getContext(), c, p.icon, r.left + 38 * k, r.centerY(), 36 * k, p.dim ? DIM : focused ? Icons.INK : Icons.MUTE);
            rowTitle.setColor(ink);
            c.drawText(fit(p.title, rowTitle, (width - 76 - 70) * k), r.left + 76 * k, r.centerY() + Ui.halfCap(rowTitle), rowTitle);
            if (p.target.equals(RemoteColours.target(getContext(), colour))) {
                Icons.draw(getContext(), c, R.drawable.icon_check, r.right - 35 * k, r.centerY(), 32 * k, INK);
            }
            rowTitle.setColor(INK);
        }

        private String fit(String text, Paint paint, float max) {
            if (paint.measureText(text) <= max) return text;
            int n = paint.breakText(text, true, max - paint.measureText("…"), null);
            return text.substring(0, Math.max(0, n)).trim() + "…";
        }
    }
}
