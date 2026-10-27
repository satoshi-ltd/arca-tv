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
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LocationActivity extends Activity {
    private LocationView view;
    private IdleSaver idle;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        view = new LocationView(this);
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

    @Override protected void onDestroy() {
        view.release();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (idle.key(event)) return true;
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    static final class LocationView extends View {
        private final FocusRing focusRing = new FocusRing(this);
        private static final int INK = 0xFFF2F2F2, MUTE = 0xB8FFFFFF, DIM = 0x73FFFFFF;
        private static final int TOGGLE = 0, FIELD = 1, RESULTS = 2, KEYS = 3;
        private static final int COLS = 11, ROWS = 3, MAX = 40, MAX_RESULTS = 3, CAPS = 27, SPACE = 28, DELETE = 29, DONE = 30;
        private static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ-";

        private final LocationActivity activity;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final ExecutorService io = Executors.newSingleThreadExecutor();
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF(), tmp = new RectF();
        private final StringBuilder query = new StringBuilder();
        private List<String[]> results = new ArrayList<>();
        private int searchFor;
        private int section = FIELD, resultIndex, keyFocus;
        private boolean okDown, backDown, caps, searching;
        private int w, h;
        private float k = 1;
        private LinearGradient background;
        private Header header;
        private Paint fieldPaint, rowTitle, rowValue, keyPaint, smallKey, hint;

        LocationView(LocationActivity a) {
            super(a);
            activity = a;
            stroke.setStyle(Paint.Style.STROKE);
            setFocusable(true);
            setContentDescription("Weather");
        }

        void release() {
            io.shutdownNow();
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            background = new LinearGradient(0, 0, 0, h, new int[] {0xFF141414, 0xFF0E0E0E, 0xFF0A0A0A}, new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP);
            header = new Header(k);
            fieldPaint = Ui.text(Ui.light, 40 * k, INK);
            rowTitle = Ui.text(Ui.medium, Ui.BODY * k, INK);
            rowValue = Ui.text(Ui.regular, 28 * k, MUTE);
            rowValue.setTextAlign(Paint.Align.RIGHT);
            keyPaint = Ui.text(Ui.regular, 40 * k, INK);
            keyPaint.setTextAlign(Paint.Align.CENTER);
            smallKey = Ui.text(Ui.medium, 24 * k, INK);
            smallKey.setTextAlign(Paint.Align.CENTER);
            hint = Ui.text(Ui.regular, Ui.LABEL * k, MUTE);
        }

        private static final float PLATE_TOP = 244, KEYS_TOP = 748;

        private void plateBox(int rows, RectF out) {
            out.set(112 * k, PLATE_TOP * k, 1808 * k, (PLATE_TOP + 2 * Glass.PLATE_INSET + rows * (Glass.ROW_H + Glass.ROW_GAP) - Glass.ROW_GAP) * k);
        }

        private void rowBox(int i, RectF out) {
            float top = PLATE_TOP + Glass.PLATE_INSET + i * (Glass.ROW_H + Glass.ROW_GAP);
            out.set((112 + Glass.PLATE_INSET) * k, top * k, (1808 - Glass.PLATE_INSET) * k, (top + Glass.ROW_H) * k);
        }

        private void keyBox(int i, RectF out) {
            float size = 128, gap = 12, left = (1920 - (COLS * size + (COLS - 1) * gap)) / 2;
            int col = i % COLS, row = i / COLS;
            float x = left + col * (size + gap), y = KEYS_TOP + row * (Glass.ROW_H + gap);
            out.set(x * k, y * k, (x + size) * k, (y + Glass.ROW_H) * k);
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            fill.setColor(0xFF000000);
            fill.setShader(background);
            c.drawRect(0, 0, w, h, fill);
            fill.setShader(null);
            long now = System.currentTimeMillis();
            header.drawScreen(c, "Weather", Ui.clock(now), Ui.meridiem(now), Ui.date(now));

            int shownResults = visibleResults();
            plateBox(2 + shownResults, tmp);
            Glass.plate(c, tmp, k, fill, stroke);

            rowBox(0, rect);
            if (section == TOGGLE) focusRing.row(c, rect, "rows", k, fill, stroke);
            rowTitle.setColor(INK);
            c.drawText("Weather", rect.left + 32 * k, rect.centerY() + Ui.halfCap(rowTitle), rowTitle);
            rowValue.setColor(MUTE);
            c.drawText(currentValue(), rect.right - 32 * k, rect.centerY() + Ui.halfCap(rowValue), rowValue);

            rowBox(1, rect);
            if (section == FIELD) focusRing.row(c, rect, "rows", k, fill, stroke);
            Icons.draw(getContext(), c, R.drawable.icon_search, rect.left + 48 * k, rect.centerY(), 32 * k, MUTE);
            float textLeft = rect.left + 80 * k;
            String shown = query.toString();
            boolean empty = shown.isEmpty();
            fieldPaint.setColor(empty ? DIM : INK);
            String label = empty ? "Search for a city" : shown;
            c.drawText(label, textLeft, rect.centerY() + Ui.halfCap(fieldPaint), fieldPaint);
            if (!empty && section == FIELD) {
                float caret = textLeft + fieldPaint.measureText(shown) + 6 * k;
                fill.setColor(INK);
                c.drawRect(caret, rect.centerY() - 22 * k, caret + 3 * k, rect.centerY() + 22 * k, fill);
            }
            if (searching && shownResults == 0) {
                hint.setColor(MUTE);
                hint.setTextAlign(Paint.Align.RIGHT);
                c.drawText("Searching…", rect.right - 32 * k, rect.centerY() + Ui.halfCap(hint), hint);
                hint.setTextAlign(Paint.Align.LEFT);
            }

            for (int i = 0; i < shownResults; i++) {
                rowBox(2 + i, rect);
                if (section == RESULTS && i == resultIndex) focusRing.row(c, rect, "rows", k, fill, stroke);
                String[] r = results.get(i);
                float x = rect.left + 32 * k, max = rect.width() - 100 * k;
                String city = fit(r[1], rowTitle, max);
                rowTitle.setColor(INK);
                c.drawText(city, x, rect.centerY() + Ui.halfCap(rowTitle), rowTitle);
                String place = place(r);
                float placeLeft = x + rowTitle.measureText(city) + 16 * k;
                if (!place.isEmpty() && placeLeft < x + max) {
                    hint.setColor(MUTE);
                    c.drawText(fit(place, hint, x + max - placeLeft), placeLeft, rect.centerY() + Ui.halfCap(hint), hint);
                }
                drawChevron(c, rect.right - 36 * k, rect.centerY());
            }

            for (int i = 0; i <= DONE; i++) {
                keyBox(i, rect);
                if (i == DONE) {
                    fill.setColor(INK);
                    c.drawRoundRect(rect, Glass.ROW_RADIUS * k, Glass.ROW_RADIUS * k, fill);
                } else Glass.plate(c, rect, Glass.ROW_RADIUS * k, k, fill, stroke);
                if (i == DONE) {
                    smallKey.setColor(0xFF141414);
                    c.drawText("Close", rect.centerX(), rect.centerY() + Ui.halfCap(smallKey), smallKey);
                    smallKey.setColor(INK);
                } else if (i == CAPS) {
                    if (caps) {
                        fill.setColor(INK);
                        c.drawRoundRect(rect, Glass.ROW_RADIUS * k, Glass.ROW_RADIUS * k, fill);
                    }
                    smallKey.setColor(caps ? 0xFF141414 : INK);
                    c.drawText("Aa", rect.centerX(), rect.centerY() + Ui.halfCap(smallKey), smallKey);
                    smallKey.setColor(INK);
                } else if (i == SPACE) c.drawText("space", rect.centerX(), rect.centerY() + Ui.halfCap(smallKey), smallKey);
                else if (i == DELETE) c.drawText("delete", rect.centerX(), rect.centerY() + Ui.halfCap(smallKey), smallKey);
                else c.drawText(String.valueOf(LETTERS.charAt(i)), rect.centerX(), rect.centerY() + Ui.halfCap(keyPaint), keyPaint);
            }
            if (section == KEYS) {
                keyBox(keyFocus, rect);
                focusRing.focus(c, rect, Glass.ROW_RADIUS * k, "keys", k, fill, stroke);
            }
        }

        private String currentValue() {
            if (!Prefs.weather()) return "Off";
            String city = Prefs.city();
            int comma = city.indexOf(',');
            return city.isEmpty() ? "On" : comma > 0 ? city.substring(0, comma) : city;
        }

        private static String place(String[] r) {
            String label = r[0], city = r[1];
            if (label.startsWith(city + ", ")) return label.substring(city.length() + 2);
            return label.equals(city) ? "" : label;
        }

        private void drawChevron(Canvas c, float cx, float cy) {
            Icons.draw(getContext(), c, R.drawable.icon_chevron_right, cx, cy, 28 * k, MUTE);
        }

        private String fit(String text, Paint paint, float max) {
            if (paint.measureText(text) <= max) return text;
            int n = paint.breakText(text, true, max - paint.measureText("…"), null);
            return text.substring(0, Math.max(0, n)).trim() + "…";
        }

        private char cased(char upper) {
            boolean start = query.length() == 0 || query.charAt(query.length() - 1) == ' ';
            return caps || start ? upper : Character.toLowerCase(upper);
        }

        private void type(char ch) {
            if (query.length() < MAX) {
                query.append(ch);
                search();
            }
        }

        private void deleteLast() {
            if (query.length() > 0) {
                query.setLength(query.length() - 1);
                search();
            }
        }

        private void search() {
            String text = query.toString().trim();
            int my = ++searchFor;
            if (text.length() < 2) {
                results = new ArrayList<>();
                searching = false;
                invalidate();
                return;
            }
            searching = true;
            io.execute(() -> {
                List<String[]> found;
                try {
                    found = HomeData.findCity(text);
                } catch (Exception e) {
                    found = new ArrayList<>();
                    AppLog.w("city search: " + e.getMessage());
                }
                final List<String[]> result = found;
                main.post(() -> {
                    if (my != searchFor) return;
                    results = result;
                    searching = false;
                    if (section == RESULTS) resultIndex = Math.min(resultIndex, Math.max(0, Math.min(results.size(), MAX_RESULTS) - 1));
                    invalidate();
                });
            });
        }

        private void press() {
            if (section == TOGGLE) {
                Prefs.setWeather(!Prefs.weather());
                HomeData.get(getContext()).settingsChanged();
            } else if (section == RESULTS) {
                if (resultIndex < results.size()) {
                    String[] r = results.get(resultIndex);
                    Prefs.setPlaceManual(r[1], r[2], r[3], r[4]);
                    HomeData.get(getContext()).settingsChanged();
                    activity.finish();
                }
            } else if (section == KEYS) {
                if (keyFocus == DONE) {
                    activity.finish();
                } else if (keyFocus == CAPS) {
                    caps = !caps;
                } else if (keyFocus == DELETE) {
                    deleteLast();
                } else if (keyFocus == SPACE) {
                    if (query.length() > 0) type(' ');
                } else {
                    char ch = LETTERS.charAt(keyFocus);
                    type(Character.isLetter(ch) ? cased(ch) : ch);
                }
            }
        }

        private int visibleResults() {
            return Math.min(results.size(), MAX_RESULTS);
        }

        private void move(int dx, int dy) {
            focusRing.pressed(dx, dy);
            if (section == KEYS) {
                int col = keyFocus % COLS + dx, row = keyFocus / COLS + dy;
                if (dy < 0 && row < 0) {
                    section = visibleResults() > 0 ? RESULTS : FIELD;
                    resultIndex = Math.max(0, visibleResults() - 1);
                    return;
                }
                if (col < 0 || col >= COLS || row < 0 || row >= ROWS) return;
                keyFocus = Math.min(DONE, row * COLS + col);
                return;
            }
            if (section == RESULTS) {
                if (dy < 0) {
                    if (resultIndex == 0) section = FIELD;
                    else resultIndex--;
                } else if (dy > 0) {
                    if (resultIndex >= visibleResults() - 1) {
                        section = KEYS;
                        keyFocus = 0;
                    } else resultIndex++;
                }
                return;
            }
            if (section == FIELD) {
                if (dy < 0) section = TOGGLE;
                else if (dy > 0) {
                    if (visibleResults() > 0) {
                        section = RESULTS;
                        resultIndex = 0;
                    } else {
                        section = KEYS;
                        keyFocus = 0;
                    }
                }
                return;
            }
            if (section == TOGGLE && dy > 0) section = FIELD;
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
            if (down && key == KeyEvent.KEYCODE_SPACE) {
                if (query.length() > 0) type(' ');
                invalidate();
                return true;
            }
            if (down && key == KeyEvent.KEYCODE_DEL) {
                deleteLast();
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
