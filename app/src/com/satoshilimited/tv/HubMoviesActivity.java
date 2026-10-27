package com.satoshilimited.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.BitmapShader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.KeyEvent;
import android.view.View;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HubMoviesActivity extends Activity {
    static final String OPEN = "open";

    private MoviesView view;
    private IdleSaver idle;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        Reveal entry = new Reveal(getIntent());
        if (entry.present()) overridePendingTransition(0, 0);
        view = new MoviesView(this, getIntent().getStringExtra(OPEN), entry);
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

    static final class MoviesView extends View {
        private static final int INK = 0xFFF2F2F2, MUTE = 0xB8FFFFFF, DARK = 0xFF141414, PLACEHOLDER = 0xFF2B2926;
        private static final int HOME = 0, MOVIES = 1, SHOWS = 2, KIDS = 3, OTHERS = 4;
        private static final String[] NAMES = {"Home", "Movies", "TV Shows", "Kids", "Others"};
        private static final int ROOT = 0, MOVIE = 1, SHOW = 2;
        private static final int COLS = 7, POSTER_W = 190, POSTER_H = 285, GRID_STRIDE = 400, GRID_TOP = 330;
        private static final int STILL_W = 300, STILL_H = 169, STILL_GAP = 28, POSTER_GAP = 61, ROW_CARDS = 64, STILL_ROW = 344, CARD_W = 400, CARD_H = 225, CARD_GAP = 32, POSTER_ROW = 470;
        private static final int Z_BUTTONS = 0, Z_SEASONS = 1, Z_EPISODES = 2, Z_RELATED = 3, RELATED_VISIBLE = 7;
        private static final long POLL_MS = 4000, REFRESH_MS = 30_000;
        private static final String[] SORTS = {"A–Z", "Recently added", "Year", "Rating"};

        private static final class Screen {
            final int type;
            VideoLibrary.Item item;
            int zone, button, season, episode, episodeScroll, relatedIndex, relatedFirst;
            float scroll, scrollTarget, relatedTop;
            Reveal reveal;
            boolean leaving, ghost;
            float originX, originY;

            Screen(int type, VideoLibrary.Item item) {
                this.type = type;
                this.item = item;
            }
        }

        private static final class Row {
            String title;
            List<VideoLibrary.Item> items = new ArrayList<>();
            boolean still;
            int kind;
        }

        private final HubMoviesActivity activity;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final ExecutorService io = Executors.newSingleThreadExecutor();
        private final List<Screen> stack = new ArrayList<>();
        private final Backdrop backdrop = new Backdrop();
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG), pic = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF(), tmp = new RectF(), grow = new RectF(), focusRect = new RectF();
        private float focusRadius;
        private final Reveal entry;
        private final Matrix matrix = new Matrix();
        private final Map<String, StaticLayout> layouts = new HashMap<>();
        private Paint cardTitle, cardSub, tagPaint, rowTitle, rowValue, hint, chipPaint, tileTitle, tileSub, heading, huge, metaPaint, badge, messagePaint, small;
        private Paint posterSub;
        private TextPaint bodyPaint, posterName;
        private Header header;
        private LinearGradient background;
        private final RectF bar = new RectF();

        private int w, h;
        private float k = 1;

        private VideoLibrary library;
        private boolean loading = true, failed, running, tabsFocused, okDown, backDown, longDone, polling, marking;
        private final List<Integer> tabs = new ArrayList<>();
        private int tab;
        private final int[] gridFocus = new int[5], gridScroll = new int[5];
        private List<Row> rows = new ArrayList<>();
        private VideoLibrary.Watch watching = new VideoLibrary.Watch();
        private int homeRow;
        private final int[] homeCol = new int[8], homeFirst = new int[8];
        private float homeScroll, homeTarget;
        private float gridOffset;
        private boolean sortFocused;
        private final int[] sortBy = new int[5];
        private final Map<Integer, List<VideoLibrary.Item>> sorted = new HashMap<>();
        private final SharedPreferences ui;
        private final ContinueMenu menu = new ContinueMenu(this);
        private final FocusRing focusRing = new FocusRing(this);
        private boolean ghosting;
        private long tabAt = -1;
        private int tabDirection;
        private String pendingOpen = "";
        private boolean direct;

        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!running) return;
                refresh();
                boolean busy = library != null && (library.indexing != null || library.matching != null);
                handler.postDelayed(this, busy ? POLL_MS : REFRESH_MS);
            }
        };

        MoviesView(HubMoviesActivity a, String open, Reveal entry) {
            super(a);
            activity = a;
            this.entry = entry;
            pendingOpen = open == null ? "" : open;
            ui = a.getSharedPreferences("video-ui", Context.MODE_PRIVATE);
            for (int i = 0; i < sortBy.length; i++) sortBy[i] = ui.getInt("sort:" + i, 0);
            stroke.setStyle(Paint.Style.STROKE);
            stack.add(new Screen(ROOT, null));
            setFocusable(true);
            library = VideoLibrary.current();
            if (library != null) {
                loading = false;
                rebuild();
            }
        }

        void resume() {
            running = true;
            handler.removeCallbacks(poll);
            handler.post(poll);
        }

        void pause() {
            running = false;
            handler.removeCallbacks(poll);
        }

        void release() {
            io.shutdownNow();
        }

        private void refresh() {
            if (polling) return;
            polling = true;
            io.execute(() -> {
                VideoLibrary lib = null;
                try {
                    lib = VideoLibrary.fetch(getContext());
                } catch (Exception | OutOfMemoryError e) {
                    AppLog.w("arca video library: " + e.getClass().getSimpleName());
                }
                final VideoLibrary result = lib;
                handler.post(() -> {
                    polling = false;
                    loading = false;
                    if (result != null) {
                        library = result;
                        failed = false;
                        rebuild();
                    } else {
                        failed = library == null;
                    }
                    invalidate();
                });
            });
        }

        private void rebuild() {
            VideoLibrary lib = library;
            if (lib == null) return;
            int shown = tabs.isEmpty() ? HOME : tabs.get(Math.min(tab, tabs.size() - 1));
            tabs.clear();
            if (!lib.empty()) {
                tabs.add(HOME);
                if (!lib.grownMovies.isEmpty()) tabs.add(MOVIES);
                if (!lib.shows.isEmpty()) tabs.add(SHOWS);
                if (lib.hasKids()) tabs.add(KIDS);
                if (!lib.others.isEmpty()) tabs.add(OTHERS);
            }
            tab = Math.max(0, tabs.indexOf(shown));
            sorted.clear();
            rows = new ArrayList<>();
            watching = lib.watchingRow();
            addRow("Watch next", watching.items, true, 0);
            addRow("Recently added", lib.recentlyAdded(20), false, 2);
            addRow("Kids picks", lib.kidsPicks(20), false, 3);
            homeRow = Math.min(homeRow, Math.max(0, rows.size() - 1));
            for (int i = 0; i < homeCol.length; i++) {
                int count = i < rows.size() ? rows.get(i).items.size() : 0;
                homeCol[i] = Math.min(homeCol[i], Math.max(0, count - 1));
                homeFirst[i] = Math.min(homeFirst[i], homeCol[i]);
            }
            for (int i = 0; i < gridFocus.length; i++) {
                int count = count(i);
                gridFocus[i] = Math.min(gridFocus[i], Math.max(0, count - 1));
            }
            if (rows.isEmpty() && !tabs.isEmpty() && tabs.get(tab) == HOME) tabsFocused = true;
            consumeOpen(lib);
            for (int i = stack.size() - 1; i > 0; i--) {
                VideoLibrary.Item fresh = lib.find(stack.get(i).item.id);
                if (fresh == null) stack.remove(i);
                else stack.get(i).item = fresh;
            }
            for (int i = 0; i < gridScroll.length; i++) {
                int n = count(i), most = i == OTHERS ? n - 8 : (n + COLS - 1) / COLS - 2;
                gridScroll[i] = Math.max(0, Math.min(gridScroll[i], most));
            }
        }

        private void consumeOpen(VideoLibrary lib) {
            if (pendingOpen.isEmpty()) return;
            VideoLibrary.Item it = lib.find(pendingOpen);
            pendingOpen = "";
            if (it == null) return;
            VideoLibrary.Item target = it.isEpisode() ? lib.showOf(it) : it;
            if (target == null) return;
            direct = true;
            open(target);
            if (top().type != ROOT) top().reveal = entry.present() ? entry : null;
        }

        private void addRow(String title, List<VideoLibrary.Item> items, boolean still, int kind) {
            if (items.isEmpty()) return;
            Row r = new Row();
            r.title = title;
            r.items = items;
            r.still = still;
            r.kind = kind;
            rows.add(r);
        }

        private int count(int kind) {
            if (library == null) return 0;
            switch (kind) {
                case MOVIES: return library.grownMovies.size();
                case SHOWS: return library.shows.size();
                case KIDS: return library.kids().size();
                case OTHERS: return library.others.size();
                default: return 0;
            }
        }

        private List<VideoLibrary.Item> listOf(int kind) {
            List<VideoLibrary.Item> cached = sorted.get(kind);
            if (cached != null) return cached;
            List<VideoLibrary.Item> list;
            switch (kind) {
                case MOVIES: list = library.grownMovies; break;
                case SHOWS: list = library.shows; break;
                case KIDS: list = library.kids(); break;
                default: list = library.others;
            }
            list = new ArrayList<>(list);
            Collections.sort(list, comparator(sortBy[kind]));
            sorted.put(kind, list);
            return list;
        }

        private static Comparator<VideoLibrary.Item> comparator(int by) {
            Comparator<VideoLibrary.Item> title = VideoLibrary.BY_TITLE;
            switch (by) {
                case 1: return (a, b) -> b.added.compareTo(a.added) != 0 ? b.added.compareTo(a.added) : title.compare(a, b);
                case 2: return (a, b) -> a.year != b.year ? Integer.compare(b.year, a.year) : title.compare(a, b);
                case 3: return (a, b) -> a.rating != b.rating ? Double.compare(b.rating, a.rating) : title.compare(a, b);
                default: return title;
            }
        }

        private int sortOptions() {
            int kind = currentTab();
            return kind == HOME ? 0 : kind == OTHERS ? 2 : SORTS.length;
        }

        private void applySort(int by) {
            int kind = currentTab();
            if (by == sortBy[kind]) return;
            sortBy[kind] = by;
            ui.edit().putInt("sort:" + kind, by).apply();
            sorted.remove(kind);
            gridFocus[kind] = 0;
            gridScroll[kind] = 0;
            tabDirection = 0;
            tabAt = SystemClock.uptimeMillis();
        }

        private int currentTab() {
            return tabs.isEmpty() ? HOME : tabs.get(Math.min(tab, tabs.size() - 1));
        }

        private Screen top() {
            return stack.get(stack.size() - 1);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            background = new LinearGradient(0, 0, 0, h, new int[] {0xFF141414, 0xFF0E0E0E, 0xFF0A0A0A}, new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP);
            header = new Header(k);
            rowTitle = Ui.text(Ui.medium, 28 * k, INK);
            rowValue = Ui.text(Ui.regular, 24 * k, MUTE);
            rowValue.setTextAlign(Paint.Align.RIGHT);
            hint = Ui.text(Ui.regular, 24 * k, MUTE);
            chipPaint = Ui.text(Ui.medium, 28 * k, INK);
            tileTitle = Ui.text(Ui.medium, 24 * k, INK);
            tileSub = Ui.text(Ui.regular, Ui.CAPTION * k, MUTE);
            posterSub = Ui.text(Ui.regular, 20 * k, MUTE);
            posterSub.setTextAlign(Paint.Align.CENTER);
            tagPaint = Ui.tagPaint(k);
            cardTitle = Ui.text(Ui.semibold, 28 * k, 0xFFFFFFFF);
            cardSub = Ui.text(Ui.regular, 20 * k, 0xD9FFFFFF);
            heading = Ui.text(Ui.medium, 32 * k, INK);
            huge = Ui.text(Ui.light, 96 * k, INK);
            huge.setLetterSpacing(-0.03f);
            metaPaint = Ui.text(Ui.regular, 28 * k, INK);
            badge = Ui.text(Ui.semibold, 18 * k, INK);
            messagePaint = Ui.text(Ui.medium, Ui.BODY * k, MUTE);
            messagePaint.setTextAlign(Paint.Align.CENTER);
            small = Ui.text(Ui.medium, Ui.CAPTION * k, INK);
            bodyPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
            bodyPaint.setTypeface(Ui.regular);
            bodyPaint.setTextSize(28 * k);
            bodyPaint.setColor(0xE6F2F2F2);
            posterName = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
            posterName.setTypeface(Ui.semibold);
            posterName.setTextSize(24 * k);
            posterName.setColor(0xD9FFFFFF);
            spinner.setStyle(Paint.Style.STROKE);
            spinner.setStrokeWidth(4 * k);
            spinner.setColor(MUTE);
            layouts.clear();
        }

        private final VideoArt.Done redraw = this::postInvalidate;

        private Bitmap art(String key, int size) {
            return VideoArt.get(getContext(), key, size, redraw);
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            fill.setColor(0xFF000000);
            fill.setShader(background);
            c.drawRect(0, 0, w, h, fill);
            fill.setShader(null);
            Screen s = top();
            if (s.leaving && !s.reveal.running()) {
                leave(s);
                s = top();
            }
            boolean revealing = s.type != ROOT && s.reveal != null && s.reveal.running();
            focusRect.setEmpty();
            updateBackdrop();
            backdrop.draw(c, w, h, 1f);
            long now = System.currentTimeMillis();
            boolean animating = false;
            if (s.type == MOVIE || s.type == SHOW) {
                int layer = -1;
                if (revealing) {
                    float p = s.reveal.progress(), text = Ui.clamp((p - 0.3f) / 0.7f);
                    if (s.ghost) drawGhost(c, stack.get(stack.size() - 2), 1f - Ui.clamp(p / 0.7f), 1f + 0.04f * p, s.originX, s.originY);
                    layer = c.saveLayerAlpha(0, 0, w, h, Math.round(255 * text));
                    float scale = 0.96f + 0.04f * text;
                    c.scale(scale, scale, s.ghost ? s.originX : w / 2f, s.ghost ? s.originY : h / 2f);
                    c.translate(0, 24 * k * (1 - text));
                }
                animating = s.type == MOVIE ? drawMovie(c, s) : drawShow(c, s);
                if (layer >= 0) c.restoreToCount(layer);
            } else {
                header.drawScreen(c, library == null || tabs.isEmpty() ? "Movies" : NAMES[currentTab()], Ui.clock(now), Ui.meridiem(now), Ui.date(now), "MOVIES");
                if (library == null || tabs.isEmpty()) {
                    drawState(c);
                } else {
                    drawTabs(c);
                    float slide = tabSlide();
                    if (slide < 1f) {
                        int layer = c.saveLayerAlpha(0, 0, w, h, Math.round(255 * slide));
                        c.translate(tabDirection * 48 * k * (1f - slide), tabDirection == 0 ? 24 * k * (1f - slide) : 0);
                        animating = drawContent(c);
                        c.restoreToCount(layer);
                    } else {
                        animating = drawContent(c);
                    }
                }
            }
            if (menu.isOpen() && s.type == ROOT && !tabsFocused) {
                Row r = rows.get(homeRow);
                float x = 112 + (homeCol[homeRow] - homeFirst[homeRow]) * (CARD_W + CARD_GAP), y = rowTop(homeRow) - homeScroll + ROW_CARDS;
                rect.set(x * k, y * k, (x + CARD_W) * k, (y + CARD_H) * k);
                menu.draw(getContext(), c, rect, k, h, fill, stroke);
            }
            if (running && (revealing || s.leaving || ArtFade.settling() || tabSlide() < 1f)) postInvalidateOnAnimation();
            else if (running && (backdrop.active() || animating)) postInvalidateDelayed(40);
            else if (running && library != null && (library.indexing != null || library.matching != null)) postInvalidateDelayed(1000);
        }

        private void ring(Canvas c, RectF r, float radius, Object group, float scale, Paint paint) {
            if (!ghosting) focusRing.ring(c, r, radius, group, scale, paint);
        }

        private void ringRow(Canvas c, RectF r, Object group, float scale, Paint fill, Paint stroke) {
            if (!ghosting) focusRing.row(c, r, group, scale, fill, stroke);
        }

        private float tabSlide() {
            if (tabAt < 0) return 1f;
            float t = Ui.clamp((SystemClock.uptimeMillis() - tabAt) / (float) Reveal.MS);
            return 1f - (1f - t) * (1f - t) * (1f - t);
        }

        private void drawGhost(Canvas c, Screen previous, float alpha, float scale, float cx, float cy) {
            if (alpha <= 0.01f) return;
            ghosting = true;
            int layer = c.saveLayerAlpha(0, 0, w, h, Math.round(255 * alpha));
            c.scale(scale, scale, cx, cy);
            if (previous.type == MOVIE) drawMovie(c, previous);
            else if (previous.type == SHOW) drawShow(c, previous);
            else if (library != null && !tabs.isEmpty()) {
                long now = System.currentTimeMillis();
                header.drawScreen(c, NAMES[currentTab()], Ui.clock(now), Ui.meridiem(now), Ui.date(now), "MOVIES");
                drawTabs(c);
                drawContent(c);
            }
            c.restoreToCount(layer);
            ghosting = false;
        }

        private void drawState(Canvas c) {
            boolean error = library == null && !loading && failed;
            boolean empty = library != null && tabs.isEmpty();
            tmp.set(560 * k, 360 * k, 1360 * k, (error || empty ? 640 : 560) * k);
            Glass.plate(c, tmp, k, fill, stroke);
            String text;
            if (loading) text = "Reading your library";
            else if (error) text = "Couldn't reach " + ArcaHub.name(getContext());
            else if (library != null && library.indexing != null) text = progressText();
            else text = "No videos on " + ArcaHub.name(getContext()) + " yet";
            if (loading || library != null && library.indexing != null) Ui.spinner(c, 960 * k, 440 * k, 22 * k, SystemClock.uptimeMillis() / 1000f * 1.3f, spinner);
            c.drawText(text, 960 * k, (loading || library != null && library.indexing != null ? 520 : 470) * k, messagePaint);
            if (error) {
                rect.set(820 * k, 540 * k, 1100 * k, 604 * k);
                Glass.plate(c, rect, 32 * k, k, fill, stroke);
                small.setTextAlign(Paint.Align.CENTER);
                c.drawText("Try again", rect.centerX(), rect.centerY() + Ui.halfCap(small), small);
                small.setTextAlign(Paint.Align.LEFT);
                Glass.focus(c, rect, 32 * k, k, fill, stroke);
            } else if (empty) {
                hint.setTextAlign(Paint.Align.CENTER);
                c.drawText("Add a folder with videos on the hub", 960 * k, 540 * k, hint);
                hint.setTextAlign(Paint.Align.LEFT);
            }
            if (running && (loading || library != null && library.indexing != null)) postInvalidateDelayed(60);
        }

        private String progressText() {
            VideoLibrary.Progress p = library.indexing != null ? library.indexing : library.matching;
            if (p == null) return "";
            return (library.indexing != null ? "Reading " : "Matching ") + Math.min(p.total, p.done + 1) + " of " + p.total + "…";
        }

        private void drawTabs(Canvas c) {
            List<String> names = new ArrayList<>();
            for (int t : tabs) names.add(NAMES[t]);
            pills(c, names, tab, tabsFocused && !sortFocused && stack.size() == 1, 112, 222);
            float edge = drawSort(c);
            String status = null;
            if (library.indexing != null || library.matching != null) status = progressText();
            else if ("none".equals(library.tmdb) || "refused".equals(library.tmdb)) status = "Details are off";
            if (status != null) {
                hint.setTextAlign(Paint.Align.RIGHT);
                c.drawText(status, edge, 258 * k, hint);
                hint.setTextAlign(Paint.Align.LEFT);
            }
        }

        private float drawSort(Canvas c) {
            int count = sortOptions();
            if (count == 0 || stack.size() != 1) return 1808 * k;
            float total = 8, padX = 24;
            for (int i = 0; i < count; i++) total += chipPaint.measureText(SORTS[i]) / k + 2 * padX + 2;
            float left = 1808 - total;
            tmp.set(left * k, 222 * k, 1808 * k, 278 * k);
            Glass.plate(c, tmp, 28 * k, k, fill, stroke);
            float x = left + 4;
            int active = sortBy[currentTab()];
            for (int i = 0; i < count; i++) {
                float width = chipPaint.measureText(SORTS[i]) / k + 2 * padX;
                rect.set(x * k, 226 * k, (x + width) * k, 274 * k);
                if (i == active) {
                    fill.setColor(0x2EFFFFFF);
                    c.drawRoundRect(rect, 24 * k, 24 * k, fill);
                    if (tabsFocused && sortFocused) ring(c, rect, 24 * k, "sort", k, stroke);
                }
                chipPaint.setColor(i == active ? INK : MUTE);
                c.drawText(SORTS[i], rect.left + padX * k, rect.centerY() + Ui.halfCap(chipPaint), chipPaint);
                x += width + 2;
            }
            chipPaint.setColor(INK);
            return (left - 28) * k;
        }

        private float pills(Canvas c, List<String> names, int active, boolean focused, float left, float top) {
            float x = left;
            for (int i = 0; i < names.size(); i++) {
                float width = chipPaint.measureText(names.get(i)) / k + 56;
                tmp.set(x * k, top * k, (x + width) * k, (top + 56) * k);
                boolean on = i == active;
                if (on) {
                    for (int layer = 3; layer >= 1; layer--) {
                        fill.setColor(0x14000000);
                        rect.set(tmp.left - layer * 2 * k, tmp.top + 4 * k + layer * 3 * k, tmp.right + layer * 2 * k, tmp.bottom + 4 * k + layer * 3 * k);
                        c.drawRoundRect(rect, 28 * k + layer * 2 * k, 28 * k + layer * 2 * k, fill);
                    }
                    fill.setColor(INK);
                    c.drawRoundRect(tmp, 28 * k, 28 * k, fill);
                } else {
                    Glass.plate(c, tmp, 28 * k, k, fill, stroke);
                }
                chipPaint.setColor(on ? DARK : MUTE);
                c.drawText(names.get(i), tmp.left + 28 * k, tmp.centerY() + Ui.halfCap(chipPaint), chipPaint);
                if (on && focused) ring(c, tmp, 28 * k, "tabs", k, stroke);
                x += width + 14;
            }
            chipPaint.setColor(INK);
            return x;
        }

        private boolean drawContent(Canvas c) {
            int kind = currentTab();
            c.save();
            c.clipRect(0, 296 * k, w, h);
            boolean animating;
            if (kind == HOME) animating = drawHome(c);
            else if (kind == OTHERS) animating = drawOthers(c);
            else animating = drawGrid(c, kind);
            c.restore();
            return animating;
        }

        private float rowTop(int index) {
            float y = 306;
            for (int i = 0; i < index; i++) y += rows.get(i).still ? STILL_ROW : POSTER_ROW;
            return y;
        }

        private boolean drawHome(Canvas c) {
            if (rows.isEmpty()) {
                hint.setTextAlign(Paint.Align.CENTER);
                c.drawText(library.empty() ? "" : "Nothing to watch yet. Open Movies or TV Shows.", 960 * k, 560 * k, hint);
                hint.setTextAlign(Paint.Align.LEFT);
                return false;
            }
            homeTarget = homeRow == 0 ? 0 : Math.max(0, rowTop(homeRow) - 306);
            boolean moving = Math.abs(homeScroll - homeTarget) > 0.5f;
            homeScroll += (homeTarget - homeScroll) * 0.3f;
            if (!moving) homeScroll = homeTarget;
            for (int r = 0; r < rows.size(); r++) {
                Row row = rows.get(r);
                float top = rowTop(r) - homeScroll;
                if (top > 1080 || top + (row.still ? STILL_ROW : POSTER_ROW) < 296) continue;
                c.drawText(row.title, 112 * k, (top + 30) * k, heading);
                float x = 112, y = top + ROW_CARDS;
                int first = homeFirst[r];
                float step = row.still ? CARD_W + CARD_GAP : POSTER_W + POSTER_GAP;
                for (int i = first; i < row.items.size() && x < 1840; i++, x += step) {
                    VideoLibrary.Item it = row.items.get(i);
                    boolean focused = !tabsFocused && r == homeRow && i == homeCol[r];
                    if (row.still) drawStill(c, it, x, y, focused, row.kind == 0);
                    else drawPoster(c, it, x, y, POSTER_W, POSTER_H, focused);
                }
            }
            return moving || Math.abs(homeScroll - homeTarget) > 0.5f;
        }

        private void drawStill(Canvas c, VideoLibrary.Item it, float x, float y, boolean focused, boolean continueRow) {
            boolean next = continueRow && !watching.resume(it);
            boolean progress = continueRow && !next;
            rect.set(x * k, y * k, (x + CARD_W) * k, (y + CARD_H) * k);
            VideoLibrary.Item show = it.isEpisode() ? library.showOf(it) : null;
            String key = continueRow ? watching.art(it) : !it.still.isEmpty() ? it.still : !it.backdrop.isEmpty() ? it.backdrop : show != null && !show.backdrop.isEmpty() ? show.backdrop
                    : !it.poster.isEmpty() ? it.poster : show != null ? show.poster : "";
            drawArt(c, key, VideoArt.SMALL, rect, 28 * k);
            float shadeTop = rect.top + rect.height() * 0.45f;
            fill.setColor(0xFF000000);
            fill.setShader(new LinearGradient(0, shadeTop, 0, rect.bottom, 0x00000000, 0xA6000000, Shader.TileMode.CLAMP));
            c.drawRoundRect(rect, 28 * k, 28 * k, fill);
            fill.setShader(null);
            String line1 = continueRow ? watching.title(it) : show != null ? show.title : it.title;
            String episode = "S" + it.season + " · E" + it.episode;
            String line2 = continueRow ? watching.caption(it)
                    : it.isEpisode() ? episode + (progress && it.resumable() ? " · " + VideoLibrary.left(it) : " · " + it.title) : progress && it.resumable() ? VideoLibrary.left(it) : it.year > 0 ? String.valueOf(it.year) : "";
            String tag = continueRow ? watching.tag(it) : null;
            if (tag != null) Ui.tag(c, fill, tagPaint, tmp, tag, rect.left + 12 * k, rect.top + 16 * k, k);
            float pad = 24 * k, max = rect.width() - 2 * pad;
            c.drawText(Ui.fit(line1, cardTitle, max), rect.left + pad, rect.bottom - 66 * k, cardTitle);
            c.drawText(Ui.fit(line2, cardSub, max), rect.left + pad, rect.bottom - 36 * k, cardSub);
            if (progress && watching.progress(it) > 0) {
                fill.setColor(0x4DFFFFFF);
                tmp.set(rect.left + pad, rect.bottom - 19 * k, rect.right - pad, rect.bottom - 14 * k);
                c.drawRoundRect(tmp, 3 * k, 3 * k, fill);
                fill.setColor(0xFFFFFFFF);
                tmp.right = tmp.left + (rect.width() - 2 * pad) * watching.progress(it);
                c.drawRoundRect(tmp, 3 * k, 3 * k, fill);
            }
            if (focused) {
                if (!ghosting) focusRing.at(it);
                ring(c, rect, 28 * k, "rows", k, stroke);
                if (!ghosting) focusRect.set(rect);
                focusRadius = 28 * k;
            }
        }

        private Bitmap drawArt(Canvas c, String key, int size, RectF box, float radius) {
            Bitmap b = art(key, size);
            float alpha = b == null ? 0f : VideoArt.fade(key, size);
            if (alpha < 1f) {
                fill.setColor(VideoArt.tint(key, PLACEHOLDER));
                c.drawRoundRect(box, radius, radius, fill);
            }
            if (b != null) drawImage(c, b, box, radius, alpha);
            return b;
        }

        private void drawImage(Canvas c, Bitmap b, RectF box, float radius) {
            drawImage(c, b, box, radius, 1f);
        }

        private void drawImage(Canvas c, Bitmap b, RectF box, float radius, float alpha) {
            if (b == null) {
                fill.setColor(PLACEHOLDER);
                c.drawRoundRect(box, radius, radius, fill);
                return;
            }
            BitmapShader shader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float scale = Math.max(box.width() / b.getWidth(), box.height() / b.getHeight());
            matrix.setScale(scale, scale);
            matrix.postTranslate(box.centerX() - b.getWidth() * scale / 2, box.centerY() - b.getHeight() * scale / 2);
            shader.setLocalMatrix(matrix);
            pic.setShader(shader);
            pic.setAlpha(Math.round(255 * alpha));
            c.drawRoundRect(box, radius, radius, pic);
            pic.setAlpha(255);
            pic.setShader(null);
        }

        private void drawPoster(Canvas c, VideoLibrary.Item it, float x, float y, float pw, float ph, boolean focused) {
            rect.set(x * k, y * k, (x + pw) * k, (y + ph) * k);
            Bitmap b = drawArt(c, it.poster, VideoArt.SMALL, rect, 20 * k);
            if (b == null && (it.poster.isEmpty() || VideoArt.absent(it.poster, VideoArt.SMALL))) {
                StaticLayout l = layout(it.title, posterName, (int) ((pw - 32) * k), 4);
                c.save();
                c.translate(rect.left + 16 * k, rect.top + 18 * k);
                l.draw(c);
                c.restore();
            }
            if (!it.ratingText.isEmpty()) drawRating(c, it.ratingText, rect.left + 10 * k, rect.bottom - 40 * k);
            if (library.seen(it)) drawSeen(c, rect);
            if (it.resumable() && it.progress() > 0) {
                fill.setColor(0x66FFFFFF);
                tmp.set(rect.left + 12 * k, rect.bottom - 8 * k, rect.right - 12 * k, rect.bottom - 3 * k);
                c.drawRoundRect(tmp, 2 * k, 2 * k, fill);
                fill.setColor(INK);
                tmp.right = tmp.left + (rect.width() - 24 * k) * it.progress();
                c.drawRoundRect(tmp, 2 * k, 2 * k, fill);
            }
            if (!focused) return;
            if (!ghosting) focusRing.at(it);
            ring(c, rect, 20 * k, "rows", k, stroke);
            if (!ghosting) focusRect.set(rect);
            focusRadius = 20 * k;
            if (b == null) return;
            float max = 400 * k;
            String title = Ui.fit(it.title, tileTitle, max);
            tileTitle.setTextAlign(Paint.Align.CENTER);
            c.drawText(title, centred(rect.centerX(), tileTitle.measureText(title)), rect.bottom + 40 * k, tileTitle);
            tileTitle.setTextAlign(Paint.Align.LEFT);
            String sub = Ui.fit(posterLine(it), posterSub, max);
            if (!sub.isEmpty()) c.drawText(sub, centred(rect.centerX(), posterSub.measureText(sub)), rect.bottom + 68 * k, posterSub);
        }

        private float centred(float cx, float width) {
            return Math.max(112 * k + width / 2, Math.min(1808 * k - width / 2, cx));
        }

        private String posterLine(VideoLibrary.Item it) {
            String extra;
            if (it.isShow()) {
                int n = it.seasons.size();
                extra = n == 0 ? "" : n + (n == 1 ? " season" : " seasons");
            } else {
                extra = it.minutes() > 0 ? VideoLibrary.length(it.minutes()) : "";
            }
            if (it.year <= 0) return extra;
            return extra.isEmpty() ? String.valueOf(it.year) : it.year + " · " + extra;
        }

        private void drawSeen(Canvas c, RectF box) {
            float r = 17 * k, cx = box.right - 14 * k - r, cy = box.top + 14 * k + r;
            fill.setColor(0xB30A0A0A);
            c.drawCircle(cx, cy, r, fill);
            Icons.draw(getContext(), c, R.drawable.icon_check, cx, cy, 22 * k, INK);
        }

        private void drawRating(Canvas c, String ratingText, float left, float top) {
            String text = ratingText;
            float width = 10 * k + 20 * k + 6 * k + badge.measureText(text) + 12 * k;
            tmp.set(left, top, left + width, top + 30 * k);
            fill.setColor(0x9E0A0A0A);
            c.drawRoundRect(tmp, 15 * k, 15 * k, fill);
            Icons.draw(getContext(), c, R.drawable.icon_star, tmp.left + 10 * k + 10 * k, tmp.centerY(), 20 * k, INK);
            c.drawText(text, tmp.left + 36 * k, tmp.centerY() + Ui.halfCap(badge), badge);
        }

        private StaticLayout layout(String text, TextPaint paint, int width, int maxLines) {
            String key = text + "|" + width + "|" + paint.getTextSize() + "|" + maxLines;
            StaticLayout l = layouts.get(key);
            if (l == null) {
                if (layouts.size() > 300) layouts.clear();
                l = StaticLayout.Builder.obtain(text, 0, text.length(), paint, Math.max(1, width)).setMaxLines(maxLines).setEllipsize(android.text.TextUtils.TruncateAt.END)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
                layouts.put(key, l);
            }
            return l;
        }

        private boolean drawGrid(Canvas c, int kind) {
            List<VideoLibrary.Item> list = listOf(kind);
            if (list.isEmpty()) return false;
            int focus = gridFocus[kind], rowsTotal = (list.size() + COLS - 1) / COLS;
            float target = gridScroll[kind] * GRID_STRIDE;
            gridOffset += (target - gridOffset) * 0.3f;
            boolean moving = Math.abs(gridOffset - target) > 0.5f;
            if (!moving) gridOffset = target;
            for (int r = 0; r < rowsTotal; r++) {
                float top = GRID_TOP + r * GRID_STRIDE - gridOffset;
                if (top > 1080 || top + POSTER_H < 296) continue;
                for (int col = 0; col < COLS; col++) {
                    int i = r * COLS + col;
                    if (i >= list.size()) break;
                    float x = 112 + col * (1696 - POSTER_W) / (COLS - 1f);
                    drawPoster(c, list.get(i), x, top, POSTER_W, POSTER_H, !tabsFocused && i == focus);
                }
            }
            return moving;
        }

        private boolean drawOthers(Canvas c) {
            List<VideoLibrary.Item> list = listOf(OTHERS);
            int visible = 8, focus = gridFocus[OTHERS], scroll = gridScroll[OTHERS];
            int rowsShown = Math.min(visible, list.size());
            float top = 316, bottom = top + 2 * Glass.PLATE_INSET + rowsShown * (Glass.ROW_H + Glass.ROW_GAP) - Glass.ROW_GAP;
            tmp.set(112 * k, top * k, 1808 * k, bottom * k);
            Glass.plate(c, tmp, k, fill, stroke);
            for (int i = 0; i < rowsShown; i++) {
                int at = scroll + i;
                if (at >= list.size()) break;
                VideoLibrary.Item it = list.get(at);
                float y = top + Glass.PLATE_INSET + i * (Glass.ROW_H + Glass.ROW_GAP);
                rect.set((112 + Glass.PLATE_INSET) * k, y * k, (1808 - Glass.PLATE_INSET) * k, (y + Glass.ROW_H) * k);
                float base = rect.centerY() + Ui.halfCap(rowTitle);
                float right = rect.right - 24 * k;
                if (it.watched) Icons.draw(getContext(), c, R.drawable.icon_check, right - 14 * k, rect.centerY(), 26 * k, INK);
                if (it.watched) right -= 44 * k;
                String value = it.watched ? "" : it.resumable() ? VideoLibrary.left(it) : it.duration > 0 ? VideoLibrary.length((int) Math.round(it.duration / 60)) : "";
                c.drawText(value, right, base, rowValue);
                right -= rowValue.measureText(value) + 36 * k;
                c.drawText(Ui.fit(it.title, rowTitle, right - rect.left - 48 * k), rect.left + 24 * k, base, rowTitle);
                if (!tabsFocused && at == focus) {
                    if (!ghosting) focusRing.at(it);
                    ringRow(c, rect, "list", k, fill, stroke);
                }
            }
            return false;
        }

        private void updateBackdrop() {
            Screen s = top();
            VideoLibrary.Item it = null;
            int size = VideoArt.SMALL;
            if (s.type == MOVIE || s.type == SHOW) {
                it = s.item;
                size = VideoArt.LARGE;
            } else if (library != null && !tabs.isEmpty()) {
                it = focusedItem();
            }
            if (it == null) {
                accent();
                return;
            }
            VideoLibrary.Item source = it.isEpisode() ? library.showOf(it) : it;
            if (source == null) source = it;
            String key = !source.backdrop.isEmpty() ? source.backdrop : !source.poster.isEmpty() ? source.poster : it.still;
            if (s.type == SHOW && s.zone == Z_EPISODES && !it.seasons.isEmpty()) {
                VideoLibrary.Season season = it.seasons.get(Math.max(0, Math.min(s.season, it.seasons.size() - 1)));
                if (!season.episodes.isEmpty()) {
                    VideoLibrary.Item ep = season.episodes.get(Math.max(0, Math.min(s.episode, season.episodes.size() - 1)));
                    if (!ep.still.isEmpty()) key = ep.still;
                }
            }
            if ((s.type == MOVIE || s.type == SHOW) && s.zone == Z_RELATED) {
                List<VideoLibrary.Item> related = library.related(s.item);
                if (!related.isEmpty()) {
                    VideoLibrary.Item r = related.get(Math.max(0, Math.min(s.relatedIndex, related.size() - 1)));
                    if (!r.backdrop.isEmpty()) key = r.backdrop;
                    else if (!r.poster.isEmpty()) key = r.poster;
                }
            }
            if (key.isEmpty() || VideoArt.absent(key, size)) {
                accent();
                return;
            }
            Bitmap large = VideoArt.peek(key, size), b = large;
            if (b == null && size == VideoArt.LARGE) b = VideoArt.peek(key, VideoArt.SMALL);
            if (b == null) b = art(key, size);
            if (b == null) return;
            backdrop.showPhoto(key, b);
        }

        private void accent() {
            Thumbs.Entry entry = Thumbs.entry(HomeApps.MOVIES);
            int color = entry != null && entry.brand != 0 ? entry.brand : Thumbs.brand(HomeApps.MOVIES);
            if (color == 0) backdrop.clear();
            else backdrop.showWash("movies", color, 0.5f);
        }

        private VideoLibrary.Item focusedItem() {
            int kind = currentTab();
            if (kind == HOME) {
                if (rows.isEmpty()) return null;
                Row r = rows.get(homeRow);
                return r.items.isEmpty() ? null : r.items.get(Math.min(homeCol[homeRow], r.items.size() - 1));
            }
            List<VideoLibrary.Item> list = listOf(kind);
            return list.isEmpty() ? null : list.get(Math.min(gridFocus[kind], list.size() - 1));
        }

        private void drawMetaLine(Canvas c, VideoLibrary.Item it, boolean show, float x, float base) {
            MetaLine.draw(getContext(), c, metaPaint, badge, stroke, tmp, k, it, show, it.minutes(), x, base, INK);
        }

        private float drawChips(Canvas c, VideoLibrary.Item it, float x, float cy) {
            List<String> chips = new ArrayList<>();
            if (!it.quality().isEmpty()) chips.add(it.quality());
            if (!it.sound().isEmpty()) chips.add(it.sound());
            for (String chip : chips) {
                float width = small.measureText(chip) + 24 * k;
                tmp.set(x, cy - 17 * k, x + width, cy + 17 * k);
                fill.setColor(0x24FFFFFF);
                c.drawRoundRect(tmp, 9 * k, 9 * k, fill);
                small.setFakeBoldText(false);
                c.drawText(chip, tmp.left + 12 * k, tmp.centerY() + Ui.halfCap(small), small);
                x += width + 10 * k;
            }
            return x;
        }

        private static String code(String language) {
            String key = language.trim().toLowerCase(Locale.ROOT);
            if (key.length() == 2) return key.toUpperCase(Locale.ROOT);
            for (String iso : Locale.getISOLanguages()) {
                Locale l = new Locale(iso);
                if (key.equals(l.getISO3Language()) || key.equals(l.getDisplayLanguage(Locale.ENGLISH).toLowerCase(Locale.ROOT))) return iso.toUpperCase(Locale.ROOT);
            }
            return key.substring(0, Math.min(2, key.length())).toUpperCase(Locale.ROOT);
        }

        private float languageGroup(Canvas c, int icon, List<String> languages, float x, float cy) {
            if (languages.isEmpty()) return x;
            Icons.draw(getContext(), c, icon, x + 13 * k, cy, 26 * k, MUTE);
            x += 34 * k;
            int n = languages.size();
            for (int i = 0; i < Math.min(n, 3); i++) {
                String text = i < 2 ? code(languages.get(i)) : "+" + (n - 2);
                float width = small.measureText(text) + 24 * k;
                tmp.set(x, cy - 17 * k, x + width, cy + 17 * k);
                if (i < 2) {
                    stroke.setStrokeWidth(2 * k);
                    stroke.setColor(0x99FFFFFF);
                    c.drawRoundRect(tmp, 17 * k, 17 * k, stroke);
                } else {
                    fill.setColor(0x14FFFFFF);
                    c.drawRoundRect(tmp, 17 * k, 17 * k, fill);
                }
                small.setColor(i < 2 ? INK : MUTE);
                c.drawText(text, tmp.left + 12 * k, tmp.centerY() + Ui.halfCap(small), small);
                small.setColor(INK);
                x += width + 8 * k;
            }
            return x + 28 * k;
        }

        private float drawLanguages(Canvas c, VideoLibrary.Item it, float x, float cy) {
            if (it == null || !it.playable()) return x;
            x = languageGroup(c, R.drawable.icon_audio, it.audioLanguages, x, cy);
            return languageGroup(c, R.drawable.icon_subtitles, it.subtitleLanguages, x, cy);
        }

        private boolean hasLanguages(VideoLibrary.Item it) {
            return it != null && it.playable() && (!it.audioLanguages.isEmpty() || !it.subtitleLanguages.isEmpty());
        }

        private boolean scroll(Screen s, float target) {
            s.scrollTarget = target;
            s.scroll += (target - s.scroll) * 0.3f;
            if (Math.abs(target - s.scroll) <= 0.5f) s.scroll = target;
            return s.scroll != target;
        }

        private void drawRelated(Canvas c, Screen s, List<VideoLibrary.Item> list, float top) {
            s.relatedIndex = Math.max(0, Math.min(s.relatedIndex, list.size() - 1));
            if (s.relatedIndex < s.relatedFirst) s.relatedFirst = s.relatedIndex;
            if (s.relatedIndex >= s.relatedFirst + RELATED_VISIBLE) s.relatedFirst = s.relatedIndex - RELATED_VISIBLE + 1;
            c.drawText("Related", 112 * k, (top + 30) * k, heading);
            float x = 112;
            for (int i = s.relatedFirst; i < list.size() && x < 1840; i++, x += POSTER_W + POSTER_GAP) {
                drawPoster(c, list.get(i), x, top + ROW_CARDS, POSTER_W, POSTER_H, s.zone == Z_RELATED && i == s.relatedIndex);
            }
        }

        private boolean drawMovie(Canvas c, Screen s) {
            VideoLibrary.Item it = s.item;
            header.drawLockup(c, "MOVIES");
            List<VideoLibrary.Item> related = library.related(it);
            if (related.isEmpty() && s.zone == Z_RELATED) s.zone = Z_BUTTONS;
            boolean moving = scroll(s, s.zone == Z_RELATED ? Math.max(0, s.relatedTop - 300) : 0);
            c.save();
            if (s.scroll > 0.5f) c.clipRect(0, 170 * k, w, h);
            c.translate(0, -s.scroll * k);
            float x = 112 * k;
            float y = 330;
            c.drawText(Ui.fit(it.title, huge, 1180 * k), x - Ui.leftBearing(huge, it.title), y * k, huge);
            y += 56;
            drawMetaLine(c, it, false, x, y * k);
            y += 52;
            if (!it.genres.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < it.genres.size() && i < 4; i++) sb.append(i == 0 ? "" : " · ").append(it.genres.get(i));
                hint.setTextSize(Ui.LABEL * k);
                c.drawText(Ui.fit(sb.toString(), hint, 980 * k), x, y * k, hint);
                y += 46;
            }
            boolean chips = !it.quality().isEmpty() || !it.sound().isEmpty();
            if (chips || hasLanguages(it)) {
                float cx = chips ? drawChips(c, it, x, y * k) + 18 * k : x;
                drawLanguages(c, it, cx, y * k);
                y += 48;
            }
            hint.setTextSize(24 * k);
            if (!it.overview.isEmpty()) {
                StaticLayout l = layout(it.overview, bodyPaint, (int) (900 * k), 4);
                c.save();
                c.translate(x, y * k);
                l.draw(c);
                c.restore();
                y += l.getHeight() / k + 22;
            }
            if (!it.cast.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < it.cast.size() && i < 5; i++) sb.append(i == 0 ? "" : " · ").append(it.cast.get(i));
                hint.setTextSize(24 * k);
                c.drawText(Ui.fit(sb.toString(), hint, 1100 * k), x, (y + 20) * k, hint);
                y += 48;
            }
            hint.setTextSize(24 * k);
            float buttonsTop = Math.max(y + 24, 760);
            drawButtons(c, s, buttonsTop, x);
            s.relatedTop = buttonsTop + 72 + 64;
            if (!related.isEmpty()) drawRelated(c, s, related, s.relatedTop);
            if (it.poster.length() > 0) {
                rect.set(1420 * k, 230 * k, 1808 * k, 812 * k);
                Bitmap b = art(it.poster, VideoArt.LARGE);
                if (b == null) b = VideoArt.peek(it.poster, VideoArt.SMALL);
                drawImage(c, b, rect, 24 * k);
                if (library.seen(it)) drawSeen(c, rect);
            }
            c.restore();
            return moving;
        }

        private List<String[]> buttons(Screen s) {
            VideoLibrary.Item it = s.item;
            List<String[]> list = new ArrayList<>();
            if (it.isShow()) {
                VideoLibrary.Item next = library.toResume(it);
                if (next != null) {
                    String code = "S" + next.season + " · E" + next.episode;
                    if (next.resumable()) list.add(new String[] {"resume", "Resume", code + " · " + VideoLibrary.left(next), String.valueOf(next.progress())});
                    else list.add(new String[] {"resume", "Play", code, ""});
                }
                if (next != null && (next.resumable() || !library.episodes(it).isEmpty() && library.episodes(it).get(0) != next)) list.add(new String[] {"start", "Start over", "", ""});
                if (!library.episodes(it).isEmpty()) list.add(new String[] {"mark", library.seen(it) ? "Mark as unwatched" : "Mark as watched", "", ""});
            } else {
                if (it.resumable()) {
                    list.add(new String[] {"resume", "Resume", VideoLibrary.left(it), String.valueOf(it.progress())});
                    list.add(new String[] {"start", "Start over", "", ""});
                } else {
                    list.add(new String[] {"resume", "Play", "", ""});
                }
                list.add(new String[] {"mark", it.watched ? "Mark as unwatched" : "Mark as watched", "", ""});
            }
            return list;
        }

        private void drawButtons(Canvas c, Screen s, float top, float left) {
            List<String[]> list = buttons(s);
            s.button = Math.min(s.button, Math.max(0, list.size() - 1));
            float x = left;
            for (int i = 0; i < list.size(); i++) {
                String[] b = list.get(i);
                boolean focused = i == s.button && s.zone == Z_BUTTONS;
                int icon = "resume".equals(b[0]) ? R.drawable.icon_video : "start".equals(b[0]) ? R.drawable.icon_restart : b[1].contains("unwatched") ? R.drawable.icon_eye_off : R.drawable.icon_eye;
                float width = 36 * k + 28 * k + 14 * k + chipPaint.measureText(b[1]) + (b[2].isEmpty() ? 0 : 14 * k + tileSub.measureText(b[2])) + 36 * k;
                rect.set(x, top * k, x + width, (top + 72) * k);
                if (focused) {
                    fill.setColor(INK);
                    c.drawRoundRect(rect, 36 * k, 36 * k, fill);
                } else {
                    Glass.plate(c, rect, 36 * k, k, fill, stroke);
                }
                int ink = focused ? DARK : INK;
                Icons.draw(getContext(), c, icon, rect.left + 36 * k + 14 * k, rect.centerY(), 28 * k, ink);
                chipPaint.setColor(ink);
                float tx = rect.left + 36 * k + 28 * k + 14 * k;
                c.drawText(b[1], tx, rect.centerY() + Ui.halfCap(chipPaint), chipPaint);
                if (!b[2].isEmpty()) {
                    tileSub.setColor(focused ? 0xB3141414 : MUTE);
                    c.drawText(b[2], tx + chipPaint.measureText(b[1]) + 14 * k, rect.centerY() + Ui.halfCap(tileSub), tileSub);
                    tileSub.setColor(MUTE);
                }
                chipPaint.setColor(INK);
                if (!b[3].isEmpty()) {
                    float done = Ui.clamp(Float.parseFloat(b[3]));
                    bar.set(rect.left + 36 * k, rect.bottom - 14 * k, rect.right - 36 * k, rect.bottom - 10 * k);
                    fill.setColor(focused ? 0x33141414 : 0x33FFFFFF);
                    c.drawRoundRect(bar, 2 * k, 2 * k, fill);
                    bar.right = bar.left + bar.width() * done;
                    fill.setColor(focused ? DARK : 0xFFFFFFFF);
                    if (bar.width() > 0) c.drawRoundRect(bar, 2 * k, 2 * k, fill);
                }
                if (focused) ring(c, rect, 36 * k, "buttons", k, stroke);
                x += width + 18 * k;
            }
        }

        private boolean drawShow(Canvas c, Screen s) {
            VideoLibrary.Item show = s.item;
            header.drawLockup(c, "MOVIES");
            List<VideoLibrary.Item> related = library.related(show);
            if (related.isEmpty() && s.zone == Z_RELATED) s.zone = Z_BUTTONS;
            s.relatedTop = 1030;
            boolean moving = scroll(s, s.zone == Z_RELATED ? s.relatedTop - 300 : 0);
            c.save();
            if (s.scroll > 0.5f) c.clipRect(0, 170 * k, w, h);
            c.translate(0, -s.scroll * k);
            float x = 112 * k;
            c.drawText(Ui.fit(show.title, huge, 1180 * k), x - Ui.leftBearing(huge, show.title), 262 * k, huge);
            float y = 318;
            drawMetaLine(c, show, true, x, y * k);
            y += 46;
            if (!show.genres.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < show.genres.size() && i < 4; i++) sb.append(i == 0 ? "" : " · ").append(show.genres.get(i));
                hint.setTextSize(Ui.LABEL * k);
                c.drawText(Ui.fit(sb.toString(), hint, 980 * k), x, y * k, hint);
                hint.setTextSize(24 * k);
            }
            VideoLibrary.Item playing = library.toResume(show);
            boolean langs = hasLanguages(playing);
            hint.setTextSize(24 * k);
            float overviewTop = y + 20;
            if (langs) {
                drawLanguages(c, playing, x, (y + 40) * k);
                overviewTop = y + 66;
            }
            if (!show.overview.isEmpty()) {
                StaticLayout l = layout(show.overview, bodyPaint, (int) (860 * k), langs ? 2 : 3);
                c.save();
                c.translate(x, overviewTop * k);
                l.draw(c);
                c.restore();
            }
            drawButtons(c, s, 548, x);
            if (!show.seasons.isEmpty()) drawSeasons(c, s, show, 660);
            if (!related.isEmpty()) drawRelated(c, s, related, s.relatedTop);
            c.restore();
            return moving;
        }

        private void drawSeasons(Canvas c, Screen s, VideoLibrary.Item show, float top) {
            s.season = Math.min(s.season, show.seasons.size() - 1);
            List<String> names = new ArrayList<>();
            for (VideoLibrary.Season se : show.seasons) names.add(se.name);
            pills(c, names, s.season, s.zone == Z_SEASONS, 112, top);
            VideoLibrary.Season season = show.seasons.get(s.season);
            s.episode = Math.max(0, Math.min(s.episode, season.episodes.size() - 1));
            if (s.episode < s.episodeScroll) s.episodeScroll = s.episode;
            if (s.episode > s.episodeScroll + 4) s.episodeScroll = s.episode - 4;
            c.save();
            c.clipRect(80 * k, 0, w, h + s.scroll * k);
            float cx = 112;
            for (int i = s.episodeScroll; i < season.episodes.size() && cx < 1840; i++, cx += STILL_W + STILL_GAP) {
                VideoLibrary.Item ep = season.episodes.get(i);
                float stillTop = top + 92;
                rect.set(cx * k, stillTop * k, (cx + STILL_W) * k, (stillTop + STILL_H) * k);
                drawArt(c, ep.still, VideoArt.SMALL, rect, 18 * k);
                if (ep.resumable() && ep.progress() > 0) {
                    fill.setColor(0x66FFFFFF);
                    bar.set(rect.left + 14 * k, rect.bottom - 20 * k, rect.right - 14 * k, rect.bottom - 14 * k);
                    c.drawRoundRect(bar, 3 * k, 3 * k, fill);
                    fill.setColor(INK);
                    bar.right = bar.left + (rect.width() - 28 * k) * ep.progress();
                    c.drawRoundRect(bar, 3 * k, 3 * k, fill);
                }
                if (ep.watched) drawSeen(c, rect);
                String name = (ep.episode > 0 ? ep.episode + ". " : "") + (ep.title.isEmpty() ? "Episode " + ep.episode : ep.title);
                String sub = ep.minutes() > 0 ? VideoLibrary.length(ep.minutes()) : "";
                if (ep.resumable()) sub = (sub.isEmpty() ? "" : sub + " · ") + VideoLibrary.left(ep);
                c.drawText(Ui.fit(name, tileTitle, STILL_W * k), rect.left, rect.bottom + 36 * k, tileTitle);
                c.drawText(Ui.fit(sub, tileSub, STILL_W * k), rect.left, rect.bottom + 66 * k, tileSub);
                if (s.zone == Z_EPISODES && i == s.episode) {
                    if (!ghosting) focusRing.at(ep);
                    ring(c, rect, 18 * k, "episodes", k, stroke);
                }
            }
            c.restore();
        }

        private void play(VideoLibrary.Item it, boolean fromStart) {
            if (it == null || !it.playable()) return;
            Intent intent = new Intent(getContext(), VideoPlayerActivity.class).putExtra(VideoPlayerActivity.ID, it.id).putExtra(VideoPlayerActivity.FROM_START, fromStart);
            try {
                activity.startActivity(intent);
            } catch (RuntimeException e) {
                AppLog.w("couldn't play: " + e.getMessage());
            }
        }

        private void mark(VideoLibrary.Item it, boolean watched) {
            if (!it.playable()) return;
            boolean was = it.watched;
            double position = it.position;
            library.marked(it, watched);
            rebuild();
            invalidate();
            io.execute(() -> {
                try {
                    JSONObject body = new JSONObject().put("volume", ArcaHub.volume(getContext(), "video")).put("path", it.path).put("hash", it.hash).put("watched", watched);
                    ArcaHub.post(getContext(), "/v1/video/watched", body);
                } catch (Exception e) {
                    AppLog.w("arca video watched: " + e.getMessage());
                    handler.post(() -> {
                        library.unmarked(it, was, position);
                        rebuild();
                        invalidate();
                    });
                }
            });
        }

        private void markShow(VideoLibrary.Item show, boolean watched) {
            if (marking) return;
            List<VideoLibrary.Item> changing = new ArrayList<>();
            for (VideoLibrary.Item e : library.episodes(show)) if (e.playable() && e.watched != watched) changing.add(e);
            if (changing.isEmpty()) return;
            marking = true;
            io.execute(() -> {
                for (VideoLibrary.Item e : changing) {
                    try {
                        JSONObject body = new JSONObject().put("volume", ArcaHub.volume(getContext(), "video")).put("path", e.path).put("hash", e.hash).put("watched", watched);
                        ArcaHub.post(getContext(), "/v1/video/watched", body);
                    } catch (Exception ex) {
                        AppLog.w("arca video watched: " + ex.getMessage());
                        break;
                    }
                    handler.post(() -> {
                        library.marked(e, watched);
                        rebuild();
                        invalidate();
                    });
                }
                handler.post(() -> {
                    marking = false;
                    invalidate();
                });
            });
        }

        private void open(VideoLibrary.Item it) {
            Screen covered = top();
            if (covered.leaving) {
                covered.leaving = false;
                covered.reveal = null;
            }
            float shift = top().type != ROOT ? top().scroll * k : 0;
            if (it.isShow()) {
                Screen s = new Screen(SHOW, it);
                VideoLibrary.Item next = library.toResume(it);
                if (next != null) {
                    for (int i = 0; i < it.seasons.size(); i++) {
                        int at = it.seasons.get(i).episodes.indexOf(next);
                        if (at >= 0) {
                            s.season = i;
                            s.episode = at;
                        }
                    }
                }
                s.zone = it.seasons.isEmpty() ? Z_BUTTONS : Z_EPISODES;
                stack.add(s);
            } else if ("other".equals(it.kind)) {
                play(it, false);
                return;
            } else {
                stack.add(new Screen(MOVIE, it));
            }
            if (focusRect.isEmpty()) return;
            Intent origin = new Intent();
            grow.set(focusRect);
            focusRect.setEmpty();
            grow.offset(0, -shift);
            Reveal.put(origin, grow, focusRadius);
            Screen opened = top();
            opened.reveal = new Reveal(origin);
            opened.ghost = stack.size() > 1;
            opened.originX = grow.centerX();
            opened.originY = grow.centerY();
        }

        private void back() {
            Screen s = top();
            if (s.leaving) return;
            if (s.type != ROOT && s.reveal != null) {
                s.leaving = true;
                s.reveal.reverse();
                return;
            }
            leave(s);
        }

        private void leave(Screen s) {
            s.leaving = false;
            s.reveal = null;
            if (direct && stack.size() == 2) {
                activity.finish();
                if (entry.present()) activity.overridePendingTransition(0, 0);
            } else if (stack.size() > 1) stack.remove(stack.size() - 1);
            else if (sortFocused) sortFocused = false;
            else if (!tabsFocused && !tabs.isEmpty()) tabsFocused = true;
            else activity.finish();
        }

        private void select() {
            if (library == null) {
                if (!loading) {
                    loading = true;
                    failed = false;
                    refresh();
                }
                return;
            }
            Screen s = top();
            if (s.type == ROOT) {
                if (tabs.isEmpty()) return;
                if (tabsFocused) {
                    sortFocused = !sortFocused && sortOptions() > 0;
                    return;
                }
                int kind = currentTab();
                if (kind == HOME) {
                    if (rows.isEmpty()) return;
                    Row r = rows.get(homeRow);
                    VideoLibrary.Item it = r.items.get(homeCol[homeRow]);
                    if (r.kind == 0) {
                        if (watching.page(it)) open(it);
                        else if (it.playable()) play(it, watching.isNext(it));
                    } else {
                        open(it);
                    }
                } else {
                    List<VideoLibrary.Item> list = listOf(kind);
                    if (!list.isEmpty()) open(list.get(gridFocus[kind]));
                }
                return;
            }
            if (s.zone == Z_RELATED) {
                List<VideoLibrary.Item> related = library.related(s.item);
                if (!related.isEmpty()) open(related.get(Math.min(s.relatedIndex, related.size() - 1)));
                return;
            }
            if (s.type == MOVIE) {
                List<String[]> list = buttons(s);
                if (list.isEmpty()) return;
                String id = list.get(Math.min(s.button, list.size() - 1))[0];
                if ("resume".equals(id)) play(s.item, false);
                else if ("start".equals(id)) play(s.item, true);
                else mark(s.item, !s.item.watched);
                return;
            }
            if (s.zone == Z_BUTTONS) {
                List<String[]> list = buttons(s);
                if (list.isEmpty()) return;
                String id = list.get(Math.min(s.button, list.size() - 1))[0];
                VideoLibrary.Item next = library.toResume(s.item);
                if ("resume".equals(id)) play(next, false);
                else if ("mark".equals(id)) markShow(s.item, !library.seen(s.item));
                else {
                    List<VideoLibrary.Item> all = library.episodes(s.item);
                    if (!all.isEmpty()) play(all.get(0), true);
                }
            } else if (s.zone == Z_EPISODES && !s.item.seasons.isEmpty()) {
                VideoLibrary.Season season = s.item.seasons.get(s.season);
                play(season.episodes.get(s.episode), false);
            }
        }

        private boolean openMenu() {
            if (library == null || stack.size() != 1 || tabsFocused || tabs.isEmpty() || currentTab() != HOME || rows.isEmpty()) return false;
            Row r = rows.get(homeRow);
            if (r.kind != 0 || homeCol[homeRow] >= r.items.size()) return false;
            menu.open();
            return true;
        }

        private void toggleWatched() {
            Screen s = top();
            if (s.type != SHOW || s.zone != Z_EPISODES || s.item.seasons.isEmpty()) return;
            VideoLibrary.Item ep = s.item.seasons.get(s.season).episodes.get(s.episode);
            mark(ep, !ep.watched);
        }

        private void move(int dx, int dy) {
            focusRing.pressed(dx, dy);
            if (library == null) return;
            Screen s = top();
            if (s.type == ROOT) moveRoot(dx, dy);
            else if (s.type == MOVIE) moveMovie(s, dx, dy);
            else moveShow(s, dx, dy);
        }

        private void moveRoot(int dx, int dy) {
            if (tabs.isEmpty()) return;
            if (tabsFocused && sortFocused) {
                int kind = currentTab();
                if (dx < 0 && sortBy[kind] == 0 || dy < 0) sortFocused = false;
                else if (dx != 0) applySort(Math.max(0, Math.min(sortOptions() - 1, sortBy[kind] + dx)));
                else if (dy > 0 && count(kind) > 0) {
                    sortFocused = false;
                    tabsFocused = false;
                }
                return;
            }
            if (tabsFocused) {
                if (dx > 0 && tab == tabs.size() - 1 && sortOptions() > 0 || dy < 0 && sortOptions() > 0) sortFocused = true;
                else if (dx != 0) {
                    int next = Math.max(0, Math.min(tabs.size() - 1, tab + dx));
                    if (next != tab) {
                        tabDirection = dx;
                        tabAt = SystemClock.uptimeMillis();
                    }
                    tab = next;
                }
                else if (dy > 0 && (currentTab() == HOME ? !rows.isEmpty() : count(currentTab()) > 0)) tabsFocused = false;
                return;
            }
            int kind = currentTab();
            if (kind == HOME) {
                if (rows.isEmpty()) return;
                if (dy != 0) {
                    int next = homeRow + dy;
                    if (next < 0) {
                        tabsFocused = true;
                        return;
                    }
                    if (next >= rows.size()) return;
                    homeRow = next;
                    homeCol[homeRow] = Math.min(homeCol[homeRow], rows.get(homeRow).items.size() - 1);
                    keepVisible(homeRow);
                } else {
                    Row r = rows.get(homeRow);
                    int col = Math.max(0, Math.min(r.items.size() - 1, homeCol[homeRow] + dx));
                    homeCol[homeRow] = col;
                    keepVisible(homeRow);
                }
                return;
            }
            int n = count(kind);
            if (n == 0) return;
            if (kind == OTHERS) {
                if (dy < 0 && gridFocus[kind] == 0) {
                    tabsFocused = true;
                    return;
                }
                int next = gridFocus[kind] + dy;
                if (dy != 0 && next >= 0 && next < n) gridFocus[kind] = next;
                if (gridFocus[kind] < gridScroll[kind]) gridScroll[kind] = gridFocus[kind];
                if (gridFocus[kind] >= gridScroll[kind] + 8) gridScroll[kind] = gridFocus[kind] - 7;
                return;
            }
            int f = gridFocus[kind];
            if (dy < 0 && f < COLS) {
                tabsFocused = true;
                return;
            }
            int next = f + dx + dy * COLS;
            if (dx != 0 && (f / COLS != next / COLS || next < 0)) return;
            if (next < 0 || next >= n) {
                if (dy > 0 && f / COLS < (n - 1) / COLS) next = n - 1;
                else return;
            }
            gridFocus[kind] = next;
            int row = next / COLS;
            if (row < gridScroll[kind]) gridScroll[kind] = row;
            if (row > gridScroll[kind] + 1) gridScroll[kind] = row - 1;
        }

        private void keepVisible(int r) {
            Row row = rows.get(r);
            int perRow = row.still ? 5 : 7;
            int col = homeCol[r];
            if (col < homeFirst[r]) homeFirst[r] = col;
            if (col >= homeFirst[r] + perRow) homeFirst[r] = col - perRow + 1;
        }

        private void moveMovie(Screen s, int dx, int dy) {
            int related = library.related(s.item).size();
            if (s.zone == Z_RELATED) {
                if (dy < 0) s.zone = Z_BUTTONS;
                else if (dx != 0) s.relatedIndex = Math.max(0, Math.min(related - 1, s.relatedIndex + dx));
                return;
            }
            if (dy > 0 && related > 0) {
                s.zone = Z_RELATED;
                return;
            }
            List<String[]> list = buttons(s);
            s.button = Math.max(0, Math.min(list.size() - 1, s.button + dx));
        }

        private void moveShow(Screen s, int dx, int dy) {
            List<String[]> list = buttons(s);
            VideoLibrary.Item show = s.item;
            int related = library.related(show).size();
            boolean seasons = !show.seasons.isEmpty();
            if (s.zone == Z_BUTTONS) {
                if (dx != 0) s.button = Math.max(0, Math.min(list.size() - 1, s.button + dx));
                else if (dy > 0 && seasons) s.zone = Z_SEASONS;
                else if (dy > 0 && related > 0) s.zone = Z_RELATED;
                return;
            }
            if (s.zone == Z_RELATED) {
                if (dy < 0) s.zone = seasons ? Z_EPISODES : Z_BUTTONS;
                else if (dx != 0) s.relatedIndex = Math.max(0, Math.min(related - 1, s.relatedIndex + dx));
                return;
            }
            if (!seasons) return;
            if (s.zone == Z_SEASONS) {
                if (dx != 0) {
                    int next = Math.max(0, Math.min(show.seasons.size() - 1, s.season + dx));
                    if (next != s.season) {
                        s.season = next;
                        s.episode = 0;
                        s.episodeScroll = 0;
                    }
                } else if (dy < 0) {
                    s.zone = Z_BUTTONS;
                } else if (dy > 0) {
                    s.zone = Z_EPISODES;
                }
            } else {
                VideoLibrary.Season season = show.seasons.get(s.season);
                if (dy < 0) s.zone = Z_SEASONS;
                else if (dy > 0 && related > 0) s.zone = Z_RELATED;
                else if (dx != 0) s.episode = Math.max(0, Math.min(season.episodes.size() - 1, s.episode + dx));
            }
        }

        private void runMenu() {
            Row r = rows.isEmpty() ? null : rows.get(homeRow);
            if (r == null || r.kind != 0 || homeCol[homeRow] >= r.items.size()) {
                menu.close();
                return;
            }
            VideoLibrary.Item it = r.items.get(homeCol[homeRow]);
            if (menu.choose() == ContinueMenu.DETAILS) {
                VideoLibrary.Item target = it.isEpisode() ? library.showOf(it) : it;
                if (target != null) open(target);
            } else {
                Watching.get(getContext()).hide(it.id, () -> {
                    rebuild();
                    invalidate();
                });
                rebuild();
            }
        }

        private boolean menuKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down) menu.move(-1);
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down) menu.move(1);
                    break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (down && e.getRepeatCount() == 0) okDown = true;
                    else if (!down && okDown && !e.isCanceled()) runMenu();
                    if (!down) okDown = false;
                    break;
                case KeyEvent.KEYCODE_BACK:
                    if (down && e.getRepeatCount() == 0) backDown = true;
                    else if (!down && backDown && !e.isCanceled()) menu.close();
                    if (!down) backDown = false;
                    break;
                default:
                    break;
            }
            invalidate();
            return true;
        }

        boolean onKey(KeyEvent e) {
            if (menu.isOpen()) return menuKey(e);
            if (top().leaving && e.getKeyCode() != KeyEvent.KEYCODE_BACK) return true;
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down) move(0, -1);
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down) move(0, 1);
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down) move(1, 0);
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (down) move(-1, 0);
                    break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) {
                            okDown = true;
                            longDone = false;
                        } else if (e.getRepeatCount() == 1 && okDown && !longDone) {
                            longDone = true;
                            if (openMenu()) okDown = false;
                            else toggleWatched();
                        }
                    } else {
                        if (okDown && !longDone && !e.isCanceled()) select();
                        okDown = false;
                    }
                    break;
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    if (down) select();
                    break;
                case KeyEvent.KEYCODE_BACK:
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled()) back();
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
