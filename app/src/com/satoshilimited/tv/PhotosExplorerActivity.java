package com.satoshilimited.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PhotosExplorerActivity extends Activity {
    private ExplorerView view;
    private IdleSaver idle;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        view = new ExplorerView(this);
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

    @Override protected void onDestroy() {
        view.release();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (idle.key(event)) return true;
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    static final class ExplorerView extends View implements PhotoLibrary.Listener {
        private static final int INK = 0xFFF2F2F2, MUTE = 0xB8FFFFFF;
        private static final float LEFT = 112, TILE = 236, TILE_H = 177, TILE_GAP = 18, HEADING = 84, VIEW_TOP = 236, VIEW_BOTTOM = 1022, RAIL_X = 1792, RAIL_TOP = 262, RAIL_BOTTOM = 1030;
        private static final int COLS = 6;
        private static final float MEMORIES_H = 276, MEMORY_LABEL = 43, MEMORY_TOP = 68, MEMORY_SHADE = 110;
        private static final String[] MONTHS = {"January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"};

        private final PhotosExplorerActivity activity;
        private final PhotoLibrary library;
        private final Backdrop backdrop = new Backdrop();
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG), pic = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF rect = new RectF(), tmp = new RectF();
        private final Matrix matrix = new Matrix();
        private int[] rowMonth = new int[0], rowStart = new int[0], rowCount = new int[0];
        private float[] rowTop = new float[0];
        private float contentHeight;
        private int rows, focusRow = -1, focusCol, builtFor = -1;
        private float scroll, scrollTarget;
        private long fastUntil, movedAt;
        private boolean running, okDown, backDown, fast;
        private int w, h;
        private float k = 1;
        private LinearGradient background;
        private Header header;
        private Paint monthPaint, countPaint, yearPaint, messagePaint, chipPaint, chipCountPaint;
        private int chipMonth = -1;
        private String chipLabel = "", chipCount = "";
        private List<PhotoLibrary.Memory> memories = new ArrayList<>();
        private boolean onMemories;
        private int memoryCol, pendingMonth = -1, highlightIndex = -1;
        private String pendingHash;
        private long pendingAt, highlightAt, now;
        private Paint memoryLabel, memoryTitle, memoryDetail;
        private LinearGradient memoryShade;

        ExplorerView(PhotosExplorerActivity a) {
            super(a);
            activity = a;
            library = PhotoLibrary.get(a);
            backdrop.scrimStrength(0.55f);
            stroke.setStyle(Paint.Style.STROKE);
            setFocusable(true);
            library.addListener(this);
            library.load();
        }

        void resume() {
            running = true;
            invalidate();
        }

        void pause() {
            running = false;
        }

        void release() {
            library.removeListener(this);
        }

        @Override public void onPhotoLibrary() {
            invalidate();
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            background = new LinearGradient(0, 0, 0, h, new int[] {0xFF141414, 0xFF0E0E0E, 0xFF0A0A0A}, new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP);
            header = new Header(k);
            monthPaint = Ui.text(Ui.medium, 30 * k, INK);
            countPaint = Ui.text(Ui.regular, 24 * k, MUTE);
            yearPaint = Ui.text(Ui.medium, 20 * k, MUTE);
            yearPaint.setTextAlign(Paint.Align.RIGHT);
            messagePaint = Ui.text(Ui.medium, 30 * k, 0x73FFFFFF);
            chipPaint = Ui.text(Ui.semibold, 30 * k, 0xFF141414);
            chipCountPaint = Ui.text(Ui.regular, 26 * k, 0x99141414);
            memoryLabel = Ui.text(Ui.semibold, 22 * k, MUTE);
            memoryLabel.setLetterSpacing(0.11f);
            memoryTitle = Ui.text(Ui.semibold, 20 * k, INK);
            memoryDetail = Ui.text(Ui.regular, 17 * k, 0xCCFFFFFF);
            memoryShade = new LinearGradient(0, 0, 0, MEMORY_SHADE * k, 0x000A0A0A, 0xD10A0A0A, Shader.TileMode.CLAMP);
        }

        private void build() {
            memories = library.memories();
            if (memories.isEmpty()) onMemories = false;
            else if (movedAt == 0) onMemories = true;
            memoryCol = Math.min(memoryCol, Math.max(0, memories.size() - 1));
            int count = library.months.size();
            int total = 0;
            for (PhotoLibrary.Month m : library.months) total += 1 + (m.count + COLS - 1) / COLS;
            rowMonth = new int[total];
            rowStart = new int[total];
            rowCount = new int[total];
            rowTop = new float[total];
            float y = memories.isEmpty() ? 0 : MEMORIES_H;
            int r = 0;
            for (int mi = 0; mi < count; mi++) {
                PhotoLibrary.Month m = library.months.get(mi);
                rowMonth[r] = mi;
                rowStart[r] = -1;
                rowTop[r] = y;
                y += HEADING;
                r++;
                for (int i = 0; i < m.count; i += COLS) {
                    rowMonth[r] = mi;
                    rowStart[r] = m.first + i;
                    rowCount[r] = Math.min(COLS, m.count - i);
                    rowTop[r] = y;
                    y += TILE_H + TILE_GAP;
                    r++;
                }
            }
            rows = total;
            contentHeight = y;
            builtFor = library.total();
            chipMonth = -1;
            if (focusRow < 0 && rows > 1) {
                focusRow = 1;
                focusCol = 0;
                onMemories = !memories.isEmpty();
            }
        }

        private int focusIndex() {
            return focusRow >= 0 && focusRow < rows && rowStart[focusRow] >= 0 ? rowStart[focusRow] + focusCol : -1;
        }

        private boolean firstRowOfMonth(int r) {
            return r > 0 && rowStart[r - 1] < 0;
        }

        private void reveal(boolean center) {
            float top = rowTop[focusRow] - (firstRowOfMonth(focusRow) ? HEADING : 0);
            float bottom = rowTop[focusRow] + TILE_H;
            float view = VIEW_BOTTOM - VIEW_TOP;
            if (center) scrollTarget = top;
            else if (top < scrollTarget) scrollTarget = top;
            else if (bottom > scrollTarget + view) scrollTarget = bottom - view;
            scrollTarget = Math.max(0, Math.min(Math.max(0, contentHeight - view), scrollTarget));
        }

        private void moveRows(int n) {
            int step = n > 0 ? 1 : -1;
            for (int i = 0; i < Math.abs(n); i++) {
                int r = focusRow + step;
                while (r >= 0 && r < rows && rowStart[r] < 0) r += step;
                if (r < 0 || r >= rows) break;
                focusRow = r;
            }
            focusCol = Math.min(focusCol, rowCount[focusRow] - 1);
            reveal(false);
        }

        private void jumpMonth(int dir) {
            int m = rowMonth[focusRow] + dir;
            if (m < 0 || m >= library.months.size()) return;
            int r = focusRow;
            while (r >= 0 && r < rows && rowMonth[r] != m) r += dir;
            if (r < 0 || r >= rows) return;
            while (r > 0 && rowMonth[r - 1] == m && rowStart[r - 1] >= 0) r--;
            while (rowStart[r] < 0) r++;
            focusRow = r;
            focusCol = 0;
            reveal(true);
        }

        private String monthLabel(int mi) {
            if (mi < 0 || mi >= library.months.size()) return "Photos";
            String key = library.months.get(mi).key;
            try {
                return MONTHS[Integer.parseInt(key.substring(5, 7)) - 1] + " " + key.substring(0, 4);
            } catch (RuntimeException e) {
                return key;
            }
        }

        private void message(Canvas c, String text) {
            tmp.set(112 * k, 340 * k, 1808 * k, 480 * k);
            Glass.plate(c, tmp, k, fill, stroke);
            c.drawText(text, 156 * k, tmp.centerY() + Ui.halfCap(messagePaint), messagePaint);
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            fill.setColor(0xFF000000);
            fill.setShader(background);
            c.drawRect(0, 0, w, h, fill);
            fill.setShader(null);
            long clock = System.currentTimeMillis(), tick = SystemClock.uptimeMillis();
            now = tick;
            if (library.ready() && (library.total() != builtFor || library.memories() != memories)) build();
            resolvePending();
            boolean ready = library.ready() && rows > 0;
            backdropFor(ready);
            backdrop.draw(c, w, h, 1f);
            int fm = ready && focusRow >= 0 && !onMemories ? rowMonth[focusRow] : -1;
            header.drawScreen(c, onMemories ? "Photos" : monthLabel(fm), Ui.clock(clock), Ui.meridiem(clock), Ui.date(clock), "PHOTOS");
            if (!library.ready()) {
                message(c, library.failed() ? "Couldn't reach " + ArcaHub.name(getContext()) : "Loading your photos");
                if (running && !library.failed()) postInvalidateDelayed(300);
                return;
            }
            if (rows == 0) {
                message(c, "No photos to show yet");
                return;
            }
            fast = tick < fastUntil;
            scroll += (scrollTarget - scroll) * (fast ? 0.5f : 0.22f);
            if (Math.abs(scrollTarget - scroll) < 0.5f) scroll = scrollTarget;
            drawGrid(c, fast, fm);
            drawRail(c, fm, tick);
            hintLine(c);
            if (running && (scroll != scrollTarget || fast || tick - movedAt < 1500 || backdrop.active() || pendingHash != null || tick - highlightAt < 1500)) postInvalidateOnAnimation();
            else if (running && fastUntil != 0 && tick - fastUntil < 400) postInvalidateDelayed(120);
        }

        private void hintLine(Canvas c) {
            if (!fast) return;
            countPaint.setTextSize(22 * k);
            c.drawText("Holding Down: 1 row, then 2, then 4, then a month", LEFT * k, 1062 * k, countPaint);
            countPaint.setTextSize(24 * k);
        }

        private void backdropFor(boolean ready) {
            if (ready && onMemories && memoryCol < memories.size()) {
                PhotoLibrary.Memory m = memories.get(memoryCol);
                Bitmap b = library.memoryThumb(m);
                if (b != null) backdrop.showPhoto(m.item.hash, b);
                return;
            }
            int index = ready ? focusIndex() : -1;
            PhotoLibrary.Item it = library.item(index);
            if (it == null) return;
            Bitmap b = library.cachedThumb(index);
            if (b != null) backdrop.showPhoto(it.hash, b);
        }

        private int firstVisibleRow() {
            int lo = 0, hi = rows - 1;
            while (lo < hi) {
                int mid = (lo + hi + 1) / 2;
                if (rowTop[mid] + (rowStart[mid] < 0 ? HEADING : TILE_H) <= scroll) lo = mid;
                else hi = mid - 1;
            }
            return Math.max(0, lo);
        }

        private void drawGrid(Canvas c, boolean fast, int fm) {
            int saved = c.save();
            c.clipRect(0, VIEW_TOP * k, w, VIEW_BOTTOM * k);
            if (!memories.isEmpty() && scroll < MEMORIES_H) drawMemories(c, VIEW_TOP - scroll);
            int first = firstVisibleRow();
            int lowIndex = Integer.MAX_VALUE, highIndex = -1;
            for (int r = first; r < rows; r++) {
                float y = VIEW_TOP + rowTop[r] - scroll;
                if (y > VIEW_BOTTOM) break;
                if (rowStart[r] < 0) {
                    if (rowMonth[r] != fm) {
                        PhotoLibrary.Month m = library.months.get(rowMonth[r]);
                        String label = monthLabel(rowMonth[r]);
                        String count = m.count + (m.count == 1 ? " item" : " items");
                        float textY = y + 44;
                        c.drawText(label, LEFT * k, textY * k, monthPaint);
                        c.drawText(count, LEFT * k + monthPaint.measureText(label) + 16 * k, textY * k, countPaint);
                        fill.setColor(Glass.EDGE);
                        c.drawRect(LEFT * k, (textY + 14) * k, w - LEFT * k, (textY + 14) * k + 1.5f * k, fill);
                    }
                    continue;
                }
                lowIndex = Math.min(lowIndex, rowStart[r]);
                highIndex = Math.max(highIndex, rowStart[r] + rowCount[r] - 1);
                for (int col = 0; col < rowCount[r]; col++) {
                    int index = rowStart[r] + col;
                    float x = LEFT + col * (TILE + TILE_GAP);
                    rect.set(x * k, y * k, (x + TILE) * k, (y + TILE_H) * k);
                    Bitmap b = fast ? library.cachedThumb(index) : library.thumb(index);
                    if (fast && library.item(index) == null) library.ensure(index);
                    drawTile(c, b, rect, library.item(index));
                    if (index == highlightIndex && now - highlightAt < 1200) {
                        fill.setColor(INK);
                        fill.setAlpha(Math.round(90 * (1f - (now - highlightAt) / 1200f)));
                        c.drawRoundRect(rect, 18 * k, 18 * k, fill);
                    }
                    if (r == focusRow && col == focusCol && !onMemories) Glass.ring(c, rect, 21 * k, k, stroke);
                }
            }
            c.restoreToCount(saved);
            if (highIndex >= 0) library.want(Math.max(0, lowIndex - COLS * 4), highIndex + COLS * 8);
        }

        private void drawMemories(Canvas c, float top) {
            c.drawText("ON THIS DAY", LEFT * k, (top + MEMORY_LABEL) * k, memoryLabel);
            for (int i = 0; i < memories.size(); i++) {
                PhotoLibrary.Memory m = memories.get(i);
                float x = LEFT + i * (TILE + TILE_GAP), y = top + MEMORY_TOP;
                rect.set(x * k, y * k, (x + TILE) * k, (y + TILE_H) * k);
                drawTile(c, library.memoryThumb(m), rect, null);
                int saved = c.save();
                c.clipRect(rect);
                c.translate(0, rect.bottom - MEMORY_SHADE * k);
                fill.setShader(memoryShade);
                c.drawRoundRect(rect.left, 0, rect.right, MEMORY_SHADE * k, 18 * k, 18 * k, fill);
                fill.setShader(null);
                c.restoreToCount(saved);
                c.drawText(m.title, rect.left + 16 * k, rect.bottom - 40 * k, memoryTitle);
                c.drawText(m.detail, rect.left + 16 * k, rect.bottom - 14 * k, memoryDetail);
                if (onMemories && i == memoryCol) Glass.ring(c, rect, 21 * k, k, stroke);
            }
        }

        private void openMemory(PhotoLibrary.Memory m) {
            int mi = -1;
            for (int i = 0; i < library.months.size(); i++) if (library.months.get(i).key.equals(m.month)) mi = i;
            if (mi < 0) return;
            PhotoLibrary.Month month = library.months.get(mi);
            onMemories = false;
            int r = 0;
            while (r < rows && !(rowMonth[r] == mi && rowStart[r] >= 0)) r++;
            if (r >= rows) return;
            focusRow = r;
            focusCol = 0;
            reveal(true);
            pendingHash = m.item.hash;
            pendingMonth = mi;
            pendingAt = SystemClock.uptimeMillis();
            for (int i = month.first; i < month.first + month.count; i += 60) library.ensure(i);
        }

        private void resolvePending() {
            if (pendingHash == null || pendingMonth < 0 || pendingMonth >= library.months.size()) return;
            if (now - pendingAt > 6000) {
                pendingHash = null;
                return;
            }
            PhotoLibrary.Month month = library.months.get(pendingMonth);
            int index = library.indexOfHash(pendingHash, month.first, month.first + month.count);
            if (index < 0) return;
            for (int r = 0; r < rows; r++) {
                if (rowStart[r] < 0 || index < rowStart[r] || index >= rowStart[r] + rowCount[r]) continue;
                focusRow = r;
                focusCol = index - rowStart[r];
                float view = VIEW_BOTTOM - VIEW_TOP;
                scrollTarget = Math.max(0, Math.min(Math.max(0, contentHeight - view), rowTop[r] - view / 3));
                break;
            }
            highlightIndex = index;
            highlightAt = now;
            movedAt = now;
            pendingHash = null;
        }

        private void drawTile(Canvas c, Bitmap b, RectF box, PhotoLibrary.Item it) {
            float radius = 18 * k;
            if (b == null) {
                fill.setColor(Glass.FILL);
                c.drawRoundRect(box, radius, radius, fill);
                return;
            }
            BitmapShader shader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float scale = Math.max(box.width() / b.getWidth(), box.height() / b.getHeight());
            matrix.setScale(scale, scale);
            matrix.postTranslate(box.centerX() - b.getWidth() * scale / 2, box.centerY() - b.getHeight() * scale / 2);
            shader.setLocalMatrix(matrix);
            pic.setShader(shader);
            pic.setAlpha(fast ? 150 : 255);
            c.drawRoundRect(box, radius, radius, pic);
            pic.setShader(null);
            if (it != null && it.video) {
                float cx = box.left + 34 * k, cy = box.bottom - 30 * k;
                fill.setColor(0x99000000);
                tmp.set(box.left + 12 * k, cy - 17 * k, box.left + 62 * k, cy + 17 * k);
                c.drawRoundRect(tmp, 17 * k, 17 * k, fill);
                Icons.draw(getContext(), c, R.drawable.icon_video, cx + 2 * k, cy, 24 * k, INK);
            }
        }

        private void drawRail(Canvas c, int fm, long tick) {
            float railH = RAIL_BOTTOM - RAIL_TOP;
            float strength = fast ? 1f : Math.max(0.35f, 1f - (tick - movedAt) / 2500f);
            int lastYear = -1;
            yearPaint.setAlpha(Math.round(255 * strength));
            float lastY = -100;
            for (int r = 0; r < rows; r++) {
                if (rowStart[r] >= 0) continue;
                String key = library.months.get(rowMonth[r]).key;
                int year = key.length() >= 4 ? Integer.parseInt(key.substring(0, 4)) : 0;
                if (year == lastYear) continue;
                lastYear = year;
                float y = RAIL_TOP + rowTop[r] / contentHeight * railH;
                if (y - lastY < 26) continue;
                lastY = y;
                boolean current = fm >= 0 && library.months.get(fm).key.startsWith(String.valueOf(year));
                yearPaint.setTextSize((current ? 24 : 20) * k);
                yearPaint.setTypeface(current ? Ui.semibold : Ui.medium);
                yearPaint.setColor(current ? INK : MUTE);
                yearPaint.setAlpha(current ? 255 : Math.round(255 * strength));
                c.drawText(String.valueOf(year), (RAIL_X - 26) * k, (y + 7) * k, yearPaint);
                fill.setColor(current ? INK : 0x66FFFFFF);
                c.drawCircle((RAIL_X + 2.5f * (fast ? 1.6f : 1)) * k, y * k, (current ? 9.5f : 5.5f) * k, fill);
            }
            fill.setColor(fast ? 0x55FFFFFF : 0x33FFFFFF);
            tmp.set(RAIL_X * k, RAIL_TOP * k, (RAIL_X + (fast ? 8 : 5)) * k, RAIL_BOTTOM * k);
            c.drawRoundRect(tmp, 3 * k, 3 * k, fill);
            if (focusRow < 0) return;
            float y = RAIL_TOP + (rowTop[focusRow] + scroll * 0) / contentHeight * railH;
            fill.setColor(INK);
            tmp.set((RAIL_X - 4) * k, (y - 14) * k, (RAIL_X + 12) * k, (y + 14) * k);
            c.drawRoundRect(tmp, 8 * k, 8 * k, fill);
            if (fast && fm >= 0) drawChip(c, fm, y);
        }

        private void drawChip(Canvas c, int fm, float y) {
            if (fm != chipMonth) {
                chipMonth = fm;
                chipLabel = monthLabel(fm);
                chipCount = "· " + library.months.get(fm).count;
            }
            float labelW = chipPaint.measureText(chipLabel), countW = chipCountPaint.measureText(chipCount), arrowW = chipPaint.measureText("»");
            float width = labelW + 12 * k + countW + 16 * k + arrowW + 56 * k, half = 34 * k, right = (RAIL_X - 40) * k;
            tmp.set(right - width, y * k - half, right, y * k + half);
            fill.setColor(INK);
            c.drawRoundRect(tmp, half, half, fill);
            float base = y * k + Ui.halfCap(chipPaint), x = tmp.left + 28 * k;
            c.drawText(chipLabel, x, base, chipPaint);
            x += labelW + 12 * k;
            c.drawText(chipCount, x, base, chipCountPaint);
            c.drawText("»", x + countW + 16 * k, base, chipPaint);
        }

        private void open(int index) {
            Intent intent = new Intent(getContext(), HubPhotosActivity.class);
            if (index >= 0) intent.putExtra("start", index);
            getContext().startActivity(intent);
        }

        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            int repeat = e.getRepeatCount();
            if (!library.ready() || rows == 0 || focusRow < 0) {
                if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (!down) activity.finish();
                    return true;
                }
                return false;
            }
            long tick = SystemClock.uptimeMillis();
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down) {
                        int dir = e.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1;
                        if (onMemories) {
                            if (dir > 0) {
                                onMemories = false;
                                focusRow = 1;
                                focusCol = Math.min(memoryCol, rowCount[1] - 1);
                                reveal(false);
                            }
                            movedAt = tick;
                            break;
                        }
                        if (dir < 0 && focusRow <= 1 && !memories.isEmpty()) {
                            onMemories = true;
                            memoryCol = Math.min(focusCol, memories.size() - 1);
                            scrollTarget = 0;
                            movedAt = tick;
                            break;
                        }
                        if (repeat >= 28) {
                            if (repeat % (repeat >= 80 ? 2 : 4) == 0) jumpMonth(dir);
                        } else {
                            moveRows(dir * (repeat < 5 ? 1 : repeat < 14 ? 2 : 4));
                        }
                        if (repeat >= 5) fastUntil = tick + 260;
                        movedAt = tick;
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (down) {
                        int dir = e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                        if (onMemories) {
                            memoryCol = Math.max(0, Math.min(memories.size() - 1, memoryCol + dir));
                            movedAt = tick;
                            break;
                        }
                        if (repeat >= 10) {
                            if (repeat % 3 == 0) jumpMonth(dir);
                            fastUntil = tick + 260;
                        } else if (dir > 0 && focusCol + 1 < rowCount[focusRow]) {
                            focusCol++;
                        } else if (dir > 0) {
                            int before = focusRow;
                            moveRows(1);
                            if (focusRow != before) focusCol = 0;
                        } else if (focusCol > 0) {
                            focusCol--;
                        } else {
                            int before = focusRow;
                            moveRows(-1);
                            if (focusRow != before) focusCol = rowCount[focusRow] - 1;
                        }
                        reveal(false);
                        movedAt = tick;
                    }
                    break;
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                case KeyEvent.KEYCODE_CHANNEL_UP:
                case KeyEvent.KEYCODE_PAGE_DOWN:
                    if (down) {
                        jumpMonth(1);
                        movedAt = tick;
                    }
                    break;
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                case KeyEvent.KEYCODE_PAGE_UP:
                    if (down) {
                        jumpMonth(-1);
                        movedAt = tick;
                    }
                    break;
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    if (down) open(-1);
                    break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (down) {
                        if (repeat == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled()) {
                            if (onMemories && memoryCol < memories.size()) openMemory(memories.get(memoryCol));
                            else open(focusIndex());
                        }
                        okDown = false;
                    }
                    break;
                case KeyEvent.KEYCODE_BACK:
                    if (down) {
                        if (repeat == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled()) {
                            if (onMemories) {
                                activity.finish();
                            } else if (focusRow > 1) {
                                focusRow = 1;
                                focusCol = 0;
                                reveal(true);
                            } else if (!memories.isEmpty()) {
                                onMemories = true;
                                scrollTarget = 0;
                            } else {
                                activity.finish();
                            }
                        }
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
