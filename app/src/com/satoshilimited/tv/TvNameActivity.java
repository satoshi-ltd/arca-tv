package com.satoshilimited.tv;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;

public class TvNameActivity extends Activity {
    private NameView view;
    private IdleSaver idle;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        view = new NameView(this);
        idle = new IdleSaver(this, view);
        setContentView(idle.view());
    }

    @Override protected void onResume() {
        super.onResume();
        idle.resume();
    }

    @Override protected void onPause() {
        idle.pause();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (idle.key(event)) return true;
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    static final class NameView extends View {
        private static final int INK = 0xFFF2F2F2, MUTE = 0xB8FFFFFF, DIM = 0x73FFFFFF, CARD = 0xFF1E1E1E, KEY = 0xFF2A2A2A;
        private static final int COLS = 10, ROWS = 4, MAX = 24, CAPS = 36, SPACE = 37, DELETE = 38, DONE = 39;
        private static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-";

        private final TvNameActivity activity;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF(), tmp = new RectF();
        private final StringBuilder name = new StringBuilder();
        private int focus = DONE;
        private boolean okDown, backDown, caps;
        private int w, h;
        private float k = 1;
        private LinearGradient background;
        private Header header;
        private Paint namePaint, keyPaint, smallKey, hint;

        NameView(TvNameActivity a) {
            super(a);
            activity = a;
            stroke.setStyle(Paint.Style.STROKE);
            setFocusable(true);
            setContentDescription("TV name");
            name.append(Prefs.tvName());
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            background = new LinearGradient(0, 0, 0, h, new int[] {0xFF141414, 0xFF0E0E0E, 0xFF0A0A0A}, new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP);
            header = new Header(k);
            namePaint = Ui.text(Ui.light, 72 * k, INK);
            keyPaint = Ui.text(Ui.regular, 40 * k, INK);
            keyPaint.setTextAlign(Paint.Align.CENTER);
            smallKey = Ui.text(Ui.medium, 24 * k, INK);
            smallKey.setTextAlign(Paint.Align.CENTER);
            hint = Ui.text(Ui.regular, 26 * k, MUTE);
        }

        private void keyBox(int i, RectF out) {
            float size = 128, gap = 12, left = (1920 - (COLS * size + (COLS - 1) * gap)) / 2;
            int col = i % COLS, row = i / COLS;
            float x = left + col * (size + gap), y = 520 + row * (88 + gap);
            out.set(x * k, y * k, (x + size) * k, (y + 88) * k);
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            fill.setColor(0xFF000000);
            fill.setShader(background);
            c.drawRect(0, 0, w, h, fill);
            fill.setShader(null);
            long now = System.currentTimeMillis();
            header.drawScreen(c, "TV name", Ui.clock(now), Ui.meridiem(now), Ui.date(now));
            tmp.set(112 * k, 250 * k, 1808 * k, 390 * k);
            Glass.plate(c, tmp, k, fill, stroke);
            String shown = name.toString();
            float x = tmp.left + 44 * k, base = tmp.centerY() + Ui.halfCap(namePaint);
            c.drawText(shown, x, base, namePaint);
            float caret = x + namePaint.measureText(shown) + 6 * k;
            fill.setColor(INK);
            c.drawRect(caret, tmp.centerY() - 32 * k, caret + 3 * k, tmp.centerY() + 32 * k, fill);
            hint.setTextAlign(Paint.Align.RIGHT);
            c.drawText(name.length() + " / " + MAX, tmp.right - 44 * k, base, hint);
            hint.setTextAlign(Paint.Align.LEFT);
            c.drawText("Shown on your hub.", 112 * k, 450 * k, hint);
            for (int i = 0; i <= DONE; i++) {
                keyBox(i, rect);
                if (i == DONE) {
                    fill.setColor(INK);
                    c.drawRoundRect(rect, 28 * k, 28 * k, fill);
                } else Glass.plate(c, rect, 28 * k, k, fill, stroke);
                if (i == DONE) {
                    smallKey.setColor(0xFF141414);
                    c.drawText("Done", rect.centerX(), rect.centerY() + Ui.halfCap(smallKey), smallKey);
                    smallKey.setColor(INK);
                }
                else if (i == CAPS) {
                    if (caps) {
                        fill.setColor(INK);
                        c.drawRoundRect(rect, 28 * k, 28 * k, fill);
                    }
                    smallKey.setColor(caps ? 0xFF141414 : INK);
                    c.drawText("Aa", rect.centerX(), rect.centerY() + Ui.halfCap(smallKey), smallKey);
                    smallKey.setColor(INK);
                } else if (i == SPACE) c.drawText("space", rect.centerX(), rect.centerY() + Ui.halfCap(smallKey), smallKey);
                else if (i == DELETE) c.drawText("delete", rect.centerX(), rect.centerY() + Ui.halfCap(smallKey), smallKey);
                else c.drawText(String.valueOf(LETTERS.charAt(i)), rect.centerX(), rect.centerY() + Ui.halfCap(keyPaint), keyPaint);
            }
            keyBox(focus, rect);
            Glass.focus(c, rect, 28 * k, k, fill, stroke);
        }

        private void drawCheck(Canvas c, float cx, float cy) {
            Icons.draw(getContext(), c, R.drawable.icon_check, cx, cy, 36 * k, 0xFF141414);
        }

        private void type(char ch) {
            if (name.length() < MAX) name.append(ch);
        }

        private char cased(char upper) {
            boolean start = name.length() == 0 || name.charAt(name.length() - 1) == ' ';
            return caps || start ? upper : Character.toLowerCase(upper);
        }

        private void deleteLast() {
            if (name.length() > 0) name.setLength(name.length() - 1);
        }

        private void press() {
            if (focus == DONE) {
                Prefs.setTvName(name.toString());
                ArcaHub.reportName(activity);
                activity.finish();
            } else if (focus == CAPS) {
                caps = !caps;
            } else if (focus == DELETE) {
                deleteLast();
            } else if (focus == SPACE) {
                if (name.length() > 0) type(' ');
            } else {
                char ch = LETTERS.charAt(focus);
                type(Character.isLetter(ch) ? cased(ch) : ch);
            }
        }

        private void move(int dx, int dy) {
            int col = focus % COLS + dx, row = focus / COLS + dy;
            if (col < 0 || col >= COLS || row < 0 || row >= ROWS) return;
            focus = Math.min(DONE, row * COLS + col);
        }

        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            int key = e.getKeyCode();
            if (down && key >= KeyEvent.KEYCODE_A && key <= KeyEvent.KEYCODE_Z) {
                char ch = (char) ('a' + key - KeyEvent.KEYCODE_A);
                type(cased(Character.toUpperCase(ch)));
                invalidate();
                return true;
            }
            if (down && key >= KeyEvent.KEYCODE_0 && key <= KeyEvent.KEYCODE_9) {
                type((char) ('0' + key - KeyEvent.KEYCODE_0));
                invalidate();
                return true;
            }
            if (down && key == KeyEvent.KEYCODE_DEL) {
                deleteLast();
                invalidate();
                return true;
            }
            if (down && key == KeyEvent.KEYCODE_SPACE) {
                if (name.length() > 0) type(' ');
                invalidate();
                return true;
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
                        if (backDown && !e.isCanceled()) activity.finish();
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
