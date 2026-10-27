package com.satoshilimited.tv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextPaint;
import android.provider.Settings;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class HomeView extends View implements Choreographer.FrameCallback, HomeData.Listener, Watching.Listener, ArcaDomo.Listener {
    private static final int Z_CARDS = 0, Z_RAIL = 1, Z_PANEL = 2, Z_SHEET = 3, Z_WEATHER = 4;
    private static final int G_CARD = 1, G_RAIL = 2, G_LEFT = 3, G_RIGHT = 4, G_GRID = 5, G_WEATHER = 6;
    private static final int BG_TOP = 0xFF141414, BG_MID = 0xFF0E0E0E, BG_BOTTOM = 0xFF0A0A0A;
    private static final int INK = 0xFFF2F2F2, MUTE = 0xB8FFFFFF, DIM = 0x73FFFFFF, CHIP = 0xFF2A2A2A;
    private static final int LINE = 0xFF333333, DEVICE = 0xFFF2F2F2, DEVICE_INK = 0xFF141414;
    private static final int[][] PLACEHOLDERS = {
        {0xFF8A4F3A, 0xFF3F2219}, {0xFF4A5D86, 0xFF1C2538}, {0xFF4D6A5A, 0xFF1F2D25}, {0xFF7A5A86, 0xFF2E2036}};
    private static final java.util.Map<String, int[]> TILE_STYLE = new java.util.HashMap<>();
    static {
        TILE_STYLE.put("netflix", new int[] {0xFF0A0A0A, 0xFF000000, 0});
        TILE_STYLE.put("youtube", new int[] {0xFF1C1C1E, 0xFF09090A, 0});
        TILE_STYLE.put("globoplay", new int[] {0xFFFF8A1F, 0xFFFF2D5A, 1});
        TILE_STYLE.put("prime", new int[] {0xFF2AA8FF, 0xFF0A68D4, 1});
        TILE_STYLE.put("disney", new int[] {0xFF173584, 0xFF08143F, 0});
        TILE_STYLE.put("max", new int[] {0xFF5C30E8, 0xFF221684, 0});
        TILE_STYLE.put("appletv", new int[] {0xFF1C1C1E, 0xFF000000, 0});
    }
    private static final int[] DEFAULT_STYLE = {0xFF1C1C1E, 0xFF09090A, 0};
    private final android.graphics.PorterDuffColorFilter whiteLogo = new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
    private static final float CARD_W = 400, CARD_H = 225, CARD_GAP = 32, CARD_TOP = 543, CARD_RADIUS = 28;
    private static final float RAIL_L = 112, RAIL_T = 816, RAIL_R = 1808, RAIL_B = 1006, RAIL_PAD = 36, TILE_W = 232, TILE_FOCUS = 1.1f, TILE_RADIUS = 24;
    private static final int GRID_COLUMNS = 5, GRID_VISIBLE_ROWS = 3;
    private static final float GRID_TOP = 260, GRID_ROW = 209, GRID_W = 307, GRID_H = 169;
    private static final float PANEL_TOP = 260, LEFT_L = 112, LEFT_R = 1216, RIGHT_L = 1248, RIGHT_R = 1808;
    private static final long TOAST_MS = 6000;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    private static final String[][] TV_SETTINGS = {
        {"Picture", Settings.ACTION_DISPLAY_SETTINGS, "tv"},
        {"Sound", "com.android.tv.action.VIEW_SOUND_SETTINGS," + Settings.ACTION_SOUND_SETTINGS, "sound"},
        {"All settings", Settings.ACTION_SETTINGS, "settings"},
        {"About", Settings.ACTION_DEVICE_INFO_SETTINGS, "info"},
    };

    private static final class Row {
        String title = "", status = "", symbol = "", action;
        boolean header, on, focusable, local;
        HomeApps.Item input;
        final RectF rect = new RectF();
    }

    private final Activity activity;
    private final HomeData data;
    private final Watching watching;
    private final HomeHero hero;
    private final Click click;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences ui;
    private final TvInputManager tvInputs;
    private final Spoken spoken = new Spoken(this, new Spoken.Source() {
        @Override public void items(List<Integer> out) { a11yItems(out); }
        @Override public String text(int id) { return a11yText(id); }
        @Override public boolean bounds(int id, Rect out) { return a11yBounds(id, out); }
        @Override public int focused() { return a11yFocused(); }
        @Override public void focus(int id) { a11yFocus(id); }
        @Override public void click(int id) { a11yFocus(id); activate(); }
    });

    private final ContinueMenu menu = new ContinueMenu(this);
    private int zone = Z_RAIL, cardIndex, railIndex, panelCol, panelRow, gridIndex;
    private boolean panelOpen, sheetOpen, running, idle, okDown, backDown;
    private final Spring panel = new Spring(0.42f, 0.92f, 0), sheet = new Spring(0.36f, 0.92f, 0), scroll = new Spring(0.34f, 1f, 0);
    private final FocusRing focusRing = new FocusRing(this);
    private boolean ringReady, moved;
    private int ringLayer = -1;

    private List<HomeApps.Item> rail = new ArrayList<>();
    private Set<String> connectedInputs;
    private List<HomeApps.Item> others = new ArrayList<>();
    private final List<Row> left = new ArrayList<>(), right = new ArrayList<>();
    private Spring[] railScale = new Spring[0];
    private final RectF[] cardRects = new RectF[Watching.LIMIT], cardBase = new RectF[Watching.LIMIT];
    private final Spring cardScroll = new Spring(0.34f, 1f, 0);
    private final Backdrop backdrop = new Backdrop();
    private int cardFirst;
    private RectF[] railRects = new RectF[0];
    private float[] railCenters = new float[0];
    private RectF[] gridRects = new RectF[0];
    private final RectF screenRect = new RectF(), logoBox = new RectF(), leftCard = new RectF(), rightCard = new RectF(), railPlate = new RectF(), tmp = new RectF(), corner = new RectF();
    private String insideValue;
    private final ArrayDeque<String> toasts = new ArrayDeque<>();
    private String toast;
    private long toastAt, lastNanos, clockMinute = -1;
    private String clockText = "", meridiemText = "", dateText = "";
    private int w, h;
    private float k = 1;
    private LinearGradient background;

    private Header header;
    private Paint clockPaint, meridiemPaint, datePaint, cityPaint, tempPaint, infoPaint, headerPaint, sectionPaint, tagPaint, cardTitlePaint, cardSubPaint,
            railLabelPaint, railSubPaint, rowTitlePaint, rowStatusPaint, sheetTitlePaint, gridLabelPaint, toastPaint, miniClockPaint, miniDatePaint;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG),
            imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG), bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final WeakHashMap<Bitmap, BitmapShader> shaders = new WeakHashMap<>();
    private final Matrix matrix = new Matrix();
    private final Path path = new Path();
    private final Rect textBounds = new Rect();

    private final Runnable reloadSoon = this::reload;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            wake();
            data.refreshIfStale();
            checkNotices();
            handler.postDelayed(this, 15_000);
        }
    };
    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) { reloadSoon(); }
        @Override public void onInputAdded(String inputId) { reloadSoon(); }
        @Override public void onInputRemoved(String inputId) { reloadSoon(); }
    };

    HomeView(Activity a) {
        super(a);
        activity = a;
        data = HomeData.get(a);
        watching = Watching.get(a);
        hero = new HomeHero(a, () -> {
            wake();
            invalidate();
        });
        click = new Click(a);
        ui = a.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
        tvInputs = (TvInputManager) a.getSystemService(Context.TV_INPUT_SERVICE);
        setFocusable(true);
        setContentDescription("arca-tv");
        for (int i = 0; i < cardRects.length; i++) {
            cardRects[i] = new RectF();
            cardBase[i] = new RectF();
        }
        stroke.setStyle(Paint.Style.STROKE);
        Thumbs.load(a);
        setRail(HomeApps.shelf(a, false));
        zone = cardCount() > 0 ? Z_CARDS : Z_RAIL;
    }

    private void reloadSoon() {
        handler.removeCallbacks(reloadSoon);
        handler.postDelayed(reloadSoon, 500);
    }

    void resume() {
        if (running) return;
        running = true;
        lastNanos = 0;
        data.addListener(this);
        watching.addListener(this);
        data.refreshIfStale();
        watching.refresh();
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, handler);
        handler.postDelayed(tick, 15_000);
        checkNotices();
        reload();
        ArcaHub.refresh(getContext(), this::reload);
        ArcaDomo.get().start(this);
        onDomo();
        if (sheetOpen) closeSheet();
        sheet.snap(sheet.target);
        idle = false;
        Choreographer.getInstance().postFrameCallback(this);
        spoken.readAgain();
    }

    void pause() {
        if (!running) return;
        running = false;
        okDown = backDown = false;
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(reloadSoon);
        data.removeListener(this);
        watching.removeListener(this);
        ArcaDomo.get().stop(this);
        if (tvInputs != null) tvInputs.unregisterCallback(inputCallback);
    }

    void destroy() {
        pause();
        click.release();
    }

    void reset(boolean animated) {
        wake();
        watching.refresh();
        sheetOpen = false;
        panelOpen = false;
        sheet.target = 0;
        panel.target = 0;
        if (!animated) {
            sheet.snap(0);
            panel.snap(0);
        }
        cardIndex = 0;
        railIndex = 0;
        cardFirst = 0;
        cardScroll.snap(0);
        moved = false;
        zone = cardCount() > 0 ? Z_CARDS : Z_RAIL;
        snapRing();
        updateBackdrop();
        invalidate();
    }

    @Override public void onData() {
        wake();
        invalidate();
    }

    @Override public void onDomo() {
        ArcaDomo domo = ArcaDomo.get();
        if (zone == Z_WEATHER) updateBackdrop();
        insideValue = domo.insideLabel();
        if (zone == Z_WEATHER && !weatherFocusable()) {
            zone = cardCount() > 0 ? Z_CARDS : Z_RAIL;
            focusChanged();
        }
        wake();
        invalidate();
    }

    private boolean weatherFocusable() {
        return ArcaDomo.enabled() && corner.width() > 0;
    }

    @Override public void onWatching() {
        wake();
        if (cardCount() == 0 && zone == Z_CARDS) zone = Z_RAIL;
        else if (cardCount() > 0 && zone == Z_RAIL && !moved && !panelOpen && !sheetOpen) zone = Z_CARDS;
        cardIndex = Math.min(cardIndex, Math.max(0, cardCount() - 1));
        layout();
        snapRing();
        updateBackdrop();
        invalidate();
    }

    private int cardCount() {
        return Watching.enabled(getContext()) ? Math.min(cardRects.length, watching.items().size()) : 0;
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        background = new LinearGradient(0, 0, 0, h, new int[] {BG_TOP, BG_MID, BG_BOTTOM}, new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP);
        makePaints();
        hero.size(w, h, k);
        clockMinute = -1;
        updateClock();
        layout();
        snapRing();
        wake();
    }

    private void makePaints() {
        header = new Header(k, getContext());
        clockPaint = Ui.numbers(Ui.light, 112 * k, INK);
        clockPaint.setLetterSpacing(-0.035f);
        meridiemPaint = Ui.text(Ui.regular, 36 * k, MUTE);
        datePaint = Ui.text(Ui.regular, 24 * k, MUTE);
        cityPaint = Ui.numbers(Ui.regular, Ui.LABEL * k, INK);
        tempPaint = Ui.numbers(Ui.light, 112 * k, INK);
        tempPaint.setLetterSpacing(-0.035f);
        infoPaint = Ui.numbers(Ui.regular, 24 * k, INK);
        sectionPaint = Ui.text(Ui.medium, 32 * k, INK);
        tagPaint = Ui.tagPaint(k);
        headerPaint = Ui.text(Ui.semibold, Ui.TAG * k, MUTE);
        headerPaint.setLetterSpacing(0.11f);
        cardTitlePaint = Ui.text(Ui.semibold, 28 * k, 0xFFFFFFFF);
        cardSubPaint = Ui.text(Ui.regular, 20 * k, 0xD9FFFFFF);
        railLabelPaint = Ui.text(Ui.medium, Ui.LABEL * k, MUTE);
        railLabelPaint.setTextAlign(Paint.Align.CENTER);
        railSubPaint = Ui.text(Ui.regular, 20 * k, DIM);
        railSubPaint.setTextAlign(Paint.Align.CENTER);
        rowTitlePaint = Ui.text(Ui.medium, Ui.BODY * k, INK);
        rowStatusPaint = Ui.text(Ui.regular, 24 * k, MUTE);
        rowStatusPaint.setTextAlign(Paint.Align.RIGHT);
        sheetTitlePaint = Ui.text(Ui.light, 64 * k, INK);
        sheetTitlePaint.setLetterSpacing(-0.02f);
        gridLabelPaint = Ui.text(Ui.medium, 24 * k, MUTE);
        gridLabelPaint.setTextAlign(Paint.Align.CENTER);
        toastPaint = Ui.text(Ui.medium, Ui.BODY * k, INK);
        toastPaint.setTextAlign(Paint.Align.CENTER);
        miniClockPaint = Ui.numbers(Ui.light, 72 * k, INK);
        miniClockPaint.setLetterSpacing(-0.04f);
        miniDatePaint = Ui.text(Ui.regular, 24 * k, MUTE);
    }

    private boolean updateClock() {
        long now = System.currentTimeMillis();
        long minute = now / 60_000;
        if (minute == clockMinute) return false;
        clockMinute = minute;
        clockText = Ui.clock(now);
        meridiemText = Ui.meridiem(now);
        dateText = Ui.date(now);
        return true;
    }

    private void setRail(List<HomeApps.Item> apps) {
        List<HomeApps.Item> devices = new ArrayList<>();
        for (HomeApps.Item it : HomeApps.allInputs(activity)) if (it.kind == HomeApps.INPUT && it.connected && devices.size() < HomeApps.MAX_SHELF - 1) devices.add(it);
        List<HomeApps.Item> next = new ArrayList<>(apps.subList(0, Math.min(apps.size(), HomeApps.MAX_SHELF - devices.size())));
        next.addAll(devices);
        next.add(HomeApps.action(HomeApps.ALL_APPS, "all", "All apps", "apps"));
        int arrived = -1;
        Set<String> now = new HashSet<>();
        for (HomeApps.Item it : devices) {
            now.add(it.inputId);
            if (connectedInputs != null && !connectedInputs.contains(it.inputId)) arrived = next.indexOf(it);
        }
        connectedInputs = now;
        rail = next;
        railScale = new Spring[next.size()];
        for (int i = 0; i < railScale.length; i++) railScale[i] = new Spring(0.26f, 0.85f, 0);
        railIndex = Math.min(railIndex, Math.max(0, rail.size() - 1));
        layout();
        if (arrived >= 0 && !panelOpen && !sheetOpen) {
            zone = Z_RAIL;
            railIndex = arrived;
            moved = true;
            focusChanged();
        }
    }

    private void layout() {
        if (w == 0) return;
        cardFirst = Math.min(cardFirst, Math.max(0, cardCount() - 4));
        for (int i = 0; i < cardRects.length; i++) {
            float x = 112 + i * (CARD_W + CARD_GAP);
            cardBase[i].set(x * k, CARD_TOP * k, (x + CARD_W) * k, (CARD_TOP + CARD_H) * k);
            cardRects[i].set(cardBase[i]);
            cardRects[i].offset(-cardScroll.value * (CARD_W + CARD_GAP) * k, 0);
        }
        railPlate.set(RAIL_L * k, RAIL_T * k, RAIL_R * k, RAIL_B * k);
        int n = rail.size();
        railRects = new RectF[n];
        railCenters = new float[n];
        float avail = RAIL_R - RAIL_L - 2 * RAIL_PAD, pitch = avail / Math.max(1, n);
        float tileW = Math.min(TILE_W, pitch - 28), tileH = tileW * 9 / 16, cy = (RAIL_T + RAIL_B) / 2;
        for (int i = 0; i < n; i++) {
            float cx = RAIL_L + RAIL_PAD + pitch * (i + 0.5f);
            railCenters[i] = cx * k;
            railRects[i] = new RectF((cx - tileW / 2) * k, (cy - tileH / 2) * k, (cx + tileW / 2) * k, (cy + tileH / 2) * k);
        }
        layoutPanel();
        layoutGrid();
    }

    private void layoutPanel() {
        float y = PANEL_TOP + 28;
        for (Row r : left) {
            float height = r.header ? 46 : Glass.ROW_H;
            if (r.local) y += 22;
            r.rect.set((LEFT_L + 32) * k, y * k, (LEFT_R - 32) * k, (y + height) * k);
            y += height + (r.header ? 14 : Glass.ROW_GAP);
        }
        leftCard.set(LEFT_L * k, PANEL_TOP * k, LEFT_R * k, (y + 22) * k);
        y = PANEL_TOP + 28;
        for (Row r : right) {
            float height = r.header ? 46 : Glass.ROW_H;
            r.rect.set((RIGHT_L + 32) * k, y * k, (RIGHT_R - 32) * k, (y + height) * k);
            y += height + (r.header ? 14 : Glass.ROW_GAP);
        }
        rightCard.set(RIGHT_L * k, PANEL_TOP * k, RIGHT_R * k, (y + 22) * k);
    }

    private void layoutGrid() {
        int n = others.size();
        gridRects = new RectF[n];
        float gap = (1696 - GRID_COLUMNS * GRID_W) / (GRID_COLUMNS - 1);
        for (int i = 0; i < n; i++) {
            float x = 112 + (i % GRID_COLUMNS) * (GRID_W + gap), top = GRID_TOP + (i / GRID_COLUMNS) * GRID_ROW;
            gridRects[i] = new RectF(x * k, top * k, (x + GRID_W) * k, (top + GRID_H) * k);
        }
    }

    private void reload() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            final List<HomeApps.Item> apps = HomeApps.shelf(c, true);
            final List<HomeApps.Item> inputs = HomeApps.allInputs(c);
            final List<HomeApps.Item> all = HomeApps.others(c);
            final String network = networkStatus(c);
            handler.post(() -> {
                setRail(apps);
                setPanel(inputs, network);
                if (!sheetOpen) {
                    others = all;
                    layoutGrid();
                }
                wake();
                invalidate();
            });
        });
    }

    private void setPanel(List<HomeApps.Item> inputs, String network) {
        left.clear();
        right.clear();
        Row tv = new Row();
        tv.header = true;
        tv.title = "This TV";
        left.add(tv);
        Row net = new Row();
        net.title = "Network";
        net.symbol = "wifi";
        net.status = network;
        net.on = !network.startsWith("Not");
        net.focusable = true;
        net.action = Settings.ACTION_WIFI_SETTINGS;
        for (String[] s : TV_SETTINGS) {
            String action = null;
            for (String candidate : s[1].split(",")) {
                if (new Intent(candidate).resolveActivity(activity.getPackageManager()) != null) {
                    action = candidate;
                    break;
                }
            }
            if (action == null) continue;
            Row r = new Row();
            r.title = s[0];
            r.action = action;
            r.symbol = s[2];
            r.on = true;
            r.focusable = true;
            left.add(r);
            if (s[0].equals("Sound")) left.add(net);
        }
        if (!left.contains(net)) left.add(1, net);
        Row arca = new Row();
        arca.title = "arca TV";
        arca.symbol = "arca";
        arca.on = true;
        arca.focusable = true;
        arca.local = true;
        left.add(arca);
        Row head = new Row();
        head.header = true;
        head.title = "Inputs";
        right.add(head);
        for (HomeApps.Item it : inputs) {
            if (it.kind != HomeApps.INPUT) continue;
            Row r = new Row();
            r.input = it;
            r.on = it.connected;
            r.focusable = it.connected;
            boolean named = it.connected && it.label != null && !it.label.startsWith("HDMI");
            r.title = named ? it.label : "HDMI " + it.number;
            r.symbol = it.connected && it.symbol != null ? it.symbol : "hdmi";
            r.status = "";
            right.add(r);
        }
        layoutPanel();
        clampPanelFocus();
    }

    private static String networkStatus(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (caps == null) return "Not connected";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "Ethernet · Connected";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "Wi-Fi · Connected";
            return "Connected";
        } catch (RuntimeException e) {
            return "Not connected";
        }
    }

    private void checkNotices() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            JSONArray list = HomeMode.state(c).optJSONArray("notices");
            if (list == null) return;
            long seen = ui.getLong("seenNotice", 0), newest = seen;
            long cutoff = System.currentTimeMillis() - 24 * 3600_000L;
            final List<String> fresh = new ArrayList<>();
            for (int i = list.length() - 1; i >= 0; i--) {
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                long at = o.optLong("at");
                if (at <= seen || at < cutoff) continue;
                fresh.add(o.optString("text"));
                newest = Math.max(newest, at);
            }
            if (newest > seen) ui.edit().putLong("seenNotice", newest).apply();
            if (!fresh.isEmpty()) handler.post(() -> { for (String t : fresh) showToast(t); });
        });
    }

    private void showToast(String text) {
        toasts.add(text);
        wake();
    }

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f);
        lastNanos = frameTimeNanos;
        boolean moving = panel.step(dt);
        moving |= backdrop.fading();
        moving |= hero.fading() || ArtFade.settling();
        if (cardScroll.step(dt)) {
            moving = true;
            for (int i = 0; i < cardRects.length; i++) {
                cardRects[i].set(cardBase[i]);
                cardRects[i].offset(-cardScroll.value * (CARD_W + CARD_GAP) * k, 0);
            }
        }
        moving |= sheet.step(dt);
        moving |= scroll.step(dt);
        for (Spring s : railScale) moving |= s.step(dt);
        updateRing(dt);
        moving |= ringMoving();
        boolean toasting = tickToast(SystemClock.uptimeMillis());
        boolean clock = updateClock();
        if (moving || toasting || clock) invalidate();
        boolean busy = moving || toasting || panelOpen || sheetOpen || panel.value > 0.001f || sheet.value > 0.001f;
        idle = !busy;
        if (busy) Choreographer.getInstance().postFrameCallback(this);
        else if (backdrop.active()) {
            invalidate();
            Choreographer.getInstance().postFrameCallbackDelayed(this, 50);
        } else Choreographer.getInstance().postFrameCallbackDelayed(this, 1000);
    }

    private void wake() {
        if (!running || !idle) return;
        idle = false;
        lastNanos = 0;
        Choreographer.getInstance().removeFrameCallback(this);
        Choreographer.getInstance().postFrameCallback(this);
    }

    private boolean tickToast(long now) {
        if (toast != null && now - toastAt > TOAST_MS) toast = null;
        if (toast == null && !toasts.isEmpty()) {
            toast = toasts.poll();
            toastAt = now;
        }
        return toast != null;
    }

    private int layerOfZone() {
        return zone == Z_SHEET ? 2 : zone == Z_PANEL ? 1 : 0;
    }

    private void snapRing() {
        ringReady = false;
        ringLayer = layerOfZone();
        focusRing.snap();
    }

    private void updateRing(float dt) {
        if (focusRect() == null) return;
        ringReady = true;
        ringLayer = layerOfZone();
    }

    private boolean ringMoving() {
        return focusRing.moving();
    }

    private RectF focusRect() {
        switch (zone) {
            case Z_CARDS:
                return cardIndex < cardCount() ? cardRects[cardIndex] : null;
            case Z_RAIL:
                if (railIndex >= railRects.length) return null;
                RectF r = railRects[railIndex];
                float cx = r.centerX(), cy = r.centerY(), halfW = r.width() / 2 * TILE_FOCUS, halfH = r.height() / 2 * TILE_FOCUS;
                tmp.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH);
                return tmp;
            case Z_PANEL:
                Row row = focusedRow();
                return row == null ? null : row.rect;
            case Z_WEATHER:
                return corner.width() > 0 ? corner : null;
            default:
                if (gridIndex >= gridRects.length) return null;
                tmp.set(gridRects[gridIndex]);
                tmp.offset(0, -scroll.value * GRID_ROW * k);
                return tmp;
        }
    }

    private float focusRadius(RectF r) {
        if (zone == Z_CARDS) return CARD_RADIUS * k;
        if (zone == Z_WEATHER) return Glass.PLATE_RADIUS * k;
        return 24 * k;
    }

    // MARK: - Drawing

    @Override protected void onDraw(Canvas c) {
        if (clockPaint == null) return;
        fill.setColor(0xFF000000);
        fill.setShader(background);
        fill.setColor(0xFF000000);
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(null);
        float p = Ui.clamp(panel.value), s = Ui.clamp(sheet.value), home = (1 - p) * (1 - s);
        backdrop.draw(c, w, h, 1 - p);
        if (home > 0.002f) {
            int save = c.save();
            c.translate(0, -p * 60 * k);
            if (home < 0.999f) c.saveLayerAlpha(0, 0, w, h, Math.round(home * 255));
            hero.draw(c);
            drawHome(c);
            if (zone == Z_CARDS || zone == Z_RAIL || zone == Z_WEATHER) drawRing(c);
            c.restoreToCount(save);
        }
        if (menu.isOpen() && zone == Z_CARDS && cardIndex < cardCount()) menu.draw(getContext(), c, cardRects[cardIndex], k, h, fill, stroke);
        float panelAlpha = p * (1 - s);
        if (panelAlpha > 0.002f) {
            int save = c.save();
            c.translate(0, (1 - p) * 90 * k);
            if (panelAlpha < 0.999f) c.saveLayerAlpha(0, 0, w, h, Math.round(panelAlpha * 255));
            drawPanel(c);
            if (zone == Z_PANEL && ringReady) {
                RectF target = focusRect();
                if (target != null) focusRing.row(c, target, ringLayer, k, fill, stroke);
            }
            c.restoreToCount(save);
        }
        if (s > 0.002f) {
            int save = c.save();
            c.translate(0, (1 - s) * 40 * k);
            if (s < 0.999f) c.saveLayerAlpha(0, 0, w, h, Math.round(s * 255));
            drawSheet(c);
            if (zone == Z_SHEET) drawRing(c);
            c.restoreToCount(save);
        }
        drawToast(c);
    }

    private void updateBackdrop() {
        List<Watching.Card> cards = watching.items();
        if (zone == Z_CARDS && cardIndex < cardCount() && cardIndex < cards.size()) {
            Bitmap image = cards.get(cardIndex).image;
            if (image != null) backdrop.showPhoto(image, image);
            else backdrop.clear();
            hero.focus(cards.get(cardIndex));
        } else if (zone == Z_RAIL && railIndex < rail.size() && w > 0) {
            showBrand(rail.get(railIndex), railRects[railIndex].centerX() / w);
        } else if (zone == Z_SHEET && gridIndex < others.size() && w > 0) {
            showBrand(others.get(gridIndex), gridRects[gridIndex].centerX() / w);
        } else if (zone == Z_WEATHER) {
            int color = ArcaDomo.get().washColor();
            backdrop.showWash(color == ArcaDomo.RED ? "domo-red" : color == ArcaDomo.AMBER ? "domo-amber" : "domo", color, 0.84f, 0.16f, 1f);
        } else {
            backdrop.clear();
        }
        if (zone != Z_CARDS || cardIndex >= cardCount() || cardIndex >= cards.size()) hero.focus(null);
        wake();
    }

    private void showBrand(HomeApps.Item it, float xFraction) {
        if (it.kind == HomeApps.INPUT && it.device != null) {
            backdrop.showWash(it.key + "|" + it.device.name + "|" + Math.round(xFraction * 12), it.device.brand, xFraction);
            return;
        }
        String id = it.pkg != null ? it.pkg : it.label;
        if ((it.kind != HomeApps.APP && it.kind != HomeApps.SETTINGS) || id == null) {
            backdrop.clear();
            return;
        }
        Thumbs.Entry entry = Thumbs.entry(it.pkg);
        int color = entry != null ? entry.brand : it.brand;
        if (color == 0 && it.tile != null) color = 0xFF000000 | Bitmap.createScaledBitmap(it.tile, 1, 1, true).getPixel(0, 0);
        if (color == 0) backdrop.clear();
        else backdrop.showWash(id + "|" + Math.round(xFraction * 12), color, xFraction);
    }

    private void drawRing(Canvas c) {
        RectF target = ringReady ? focusRect() : null;
        if (target == null) return;
        focusRing.ring(c, target, focusRadius(target), ringLayer, k, stroke);
    }

    private void drawHome(Canvas c) {
        if (zone == Z_WEATHER && corner.width() > 0) {
            fill.setColor(Glass.FOCUS);
            c.drawRoundRect(corner, Glass.PLATE_RADIUS * k, Glass.PLATE_RADIUS * k, fill);
        }
        header.draw(c, clockText, meridiemText, dateText, data.weather, ArcaDomo.enabled() ? insideValue : null);
        drawInside(c);
        int n = cardCount();
        if (n > 0) {
            c.drawText("Watch next", 112 * k, 508 * k, sectionPaint);
            c.save();
            c.clipRect(80 * k, 0, w, h);
            List<Watching.Card> cards = watching.items();
            for (int i = 0; i < n && i < cards.size(); i++) if (cardRects[i].right > 80 * k && cardRects[i].left < w) drawCard(c, cards.get(i), cardRects[i], i);
            c.restore();
        }
        Glass.plate(c, railPlate, k, fill, stroke);
        for (int i = 0; i < rail.size(); i++) drawRailItem(c, i);
    }

    private void drawInside(Canvas c) {
        if (!ArcaDomo.enabled() || Float.isNaN(header.weatherLeft)) {
            corner.setEmpty();
            return;
        }
        corner.set(header.weatherLeft - 28 * k, (Prefs.weather() ? 60 : 177) * k, 1836 * k, 262 * k);
    }

    private static String cityShort(String city) {
        int comma = city.indexOf(',');
        return comma > 0 ? city.substring(0, comma) : city;
    }

    private void drawCard(Canvas c, Watching.Card item, RectF r, int index) {
        float arrival = item.image == null ? 0f : VideoArt.fade(item.art, VideoArt.SMALL);
        if (arrival < 1f) {
            int[] colors = PLACEHOLDERS[index % PLACEHOLDERS.length];
            fill.setColor(0xFF000000);
        fill.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom, colors[0], colors[1], Shader.TileMode.CLAMP));
            c.drawRoundRect(r, CARD_RADIUS * k, CARD_RADIUS * k, fill);
            fill.setShader(null);
        }
        if (item.image != null) {
            imagePaint.setAlpha(Math.round(arrival * 255));
            drawImage(c, item.image, r, CARD_RADIUS * k, false);
            imagePaint.setAlpha(255);
        }
        float shadeTop = r.top + r.height() * 0.45f;
        fill.setColor(0xFF000000);
        fill.setShader(new LinearGradient(0, shadeTop, 0, r.bottom, 0x00000000, 0xA6000000, Shader.TileMode.CLAMP));
        c.save();
        path.rewind();
        path.addRoundRect(r, CARD_RADIUS * k, CARD_RADIUS * k, Path.Direction.CW);
        c.clipPath(path);
        c.drawRect(r.left, shadeTop, r.right, r.bottom, fill);
        c.restore();
        fill.setShader(null);
        if (item.tag != null) Ui.tag(c, fill, tagPaint, tmp, item.tag, r.left + 12 * k, r.top + 16 * k, k);
        float pad = 24 * k, max = r.width() - 2 * pad;
        c.drawText(fit(item.title, cardTitlePaint, max), r.left + pad, r.bottom - 66 * k, cardTitlePaint);
        c.drawText(fit(item.sub, cardSubPaint, max), r.left + pad, r.bottom - 36 * k, cardSubPaint);
        if (item.next) return;
        fill.setColor(0x4DFFFFFF);
        tmp.set(r.left + pad, r.bottom - 19 * k, r.right - pad, r.bottom - 14 * k);
        c.drawRoundRect(tmp, 3 * k, 3 * k, fill);
        fill.setColor(0xFFFFFFFF);
        tmp.right = tmp.left + (r.width() - 2 * pad) * item.progress;
        if (tmp.width() > 0) c.drawRoundRect(tmp, 3 * k, 3 * k, fill);
    }

    private void drawRailItem(Canvas c, int i) {
        HomeApps.Item it = rail.get(i);
        RectF r = railRects[i];
        float scale = 1 + (TILE_FOCUS - 1) * Ui.clamp(railScale[i].value);
        float cx = r.centerX(), cy = r.centerY(), halfW = r.width() / 2 * scale, halfH = r.height() / 2 * scale;
        tmp.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH);
        drawTile(c, it, tmp);
    }

    private final java.util.Map<String, Integer> tileColors = new java.util.HashMap<>();
    private TextPaint nameTilePaint;

    private int tileColor(HomeApps.Item it) {
        String key = it.key != null ? it.key : String.valueOf(it.label);
        Integer cached = tileColors.get(key);
        if (cached != null) return cached;
        int color = it.brand != 0 ? it.brand
                : it.tile != null ? 0xFF000000 | Bitmap.createScaledBitmap(it.tile, 1, 1, true).getPixel(0, 0) : CHIP;
        tileColors.put(key, color);
        return color;
    }

    private static boolean light(int color) {
        return (0.299 * (color >> 16 & 255) + 0.587 * (color >> 8 & 255) + 0.114 * (color & 255)) / 255 > 0.6;
    }

    private void drawName(Canvas c, String text, RectF r, int color) {
        if (nameTilePaint == null) nameTilePaint = new TextPaint(Ui.text(Ui.semibold, 30 * k, color));
        nameTilePaint.setColor(color);
        nameTilePaint.setTextAlign(Paint.Align.CENTER);
        nameTilePaint.setTextSize(r.height() * 0.2f);
        float max = r.width() * 0.82f;
        String[] lines = {text, ""};
        if (nameTilePaint.measureText(text) > max && text.indexOf(' ') > 0) {
            int mid = text.length() / 2, at = text.lastIndexOf(' ', mid);
            if (at <= 0) at = text.indexOf(' ', mid);
            lines = new String[] {text.substring(0, at), text.substring(at + 1)};
        }
        float half = Ui.halfCap(nameTilePaint);
        if (lines[1].isEmpty()) {
            c.drawText(fit(lines[0], nameTilePaint, max), r.centerX(), r.centerY() + half, nameTilePaint);
        } else {
            float lead = nameTilePaint.getTextSize() * 0.6f;
            c.drawText(fit(lines[0], nameTilePaint, max), r.centerX(), r.centerY() - lead + half, nameTilePaint);
            c.drawText(fit(lines[1], nameTilePaint, max), r.centerX(), r.centerY() + lead + half, nameTilePaint);
        }
    }

    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Matrix tintMatrix = new android.graphics.Matrix();

    private void drawGlassTile(Canvas c, RectF r, int brand) {
        float radius = TILE_RADIUS * k;
        fill.setShader(null);
        fill.setColor(0x73000000);
        c.drawRoundRect(r, radius, radius, fill);
        Glass.plate(c, r, radius, k, fill, stroke);
        int save = c.save();
        path.rewind();
        path.addRoundRect(r, radius, radius, Path.Direction.CW);
        c.clipPath(path);
        android.graphics.RadialGradient glow = new android.graphics.RadialGradient(0, 0, 1, new int[] {(brand & 0xFFFFFF) | 0x66000000, brand & 0xFFFFFF}, new float[] {0, 0.7f}, Shader.TileMode.CLAMP);
        tintMatrix.reset();
        tintMatrix.postScale(r.width() * 1.2f, r.height() * 0.9f);
        tintMatrix.postTranslate(r.centerX(), r.top + r.height() * 1.3f);
        glow.setLocalMatrix(tintMatrix);
        tintPaint.setShader(glow);
        c.drawRect(r, tintPaint);
        tintPaint.setShader(null);
        c.restoreToCount(save);
    }

    private void drawFittedLogo(Canvas c, Thumbs.Entry entry, RectF r) {
        float ratio = (float) entry.logo.getWidth() / entry.logo.getHeight();
        float width = Math.min(entry.width * r.width(), 0.32f * r.height() * ratio), height = width / ratio;
        Bitmap logo = Thumbs.fit(entry, Math.round(width * TILE_FOCUS));
        tmp.set(r.centerX() - width / 2, r.centerY() - height / 2, r.centerX() + width / 2, r.centerY() + height / 2);
        bitmapPaint.setAlpha(255);
        c.drawBitmap(logo, null, tmp, bitmapPaint);
    }

    private void drawAppsIcon(Canvas c, RectF r) {
        Icons.draw(getContext(), c, R.drawable.icon_apps, r.centerX(), r.centerY(), Math.min(r.width(), r.height()) * 0.42f, INK);
    }

    private void drawTile(Canvas c, HomeApps.Item it, RectF r) {
        float radius = TILE_RADIUS * k;
        if (it.kind == HomeApps.INPUT && it.device != null && it.device.logo != null) {
            drawGlassTile(c, r, it.device.brand);
            drawFittedLogo(c, it.device, r);
        } else if (it.kind == HomeApps.INPUT) {
            fill.setColor(DEVICE);
            c.drawRoundRect(r, radius, radius, fill);
            Icons.draw(getContext(), c, Icons.res(it.symbol == null ? "hdmi" : it.symbol), r.centerX(), r.top + r.height() * 0.3f, r.width() * 0.2f, DEVICE_INK);
            railLabelPaint.setColor(DEVICE_INK);
            railLabelPaint.setTextSize(Math.max(14 * k, r.width() * 0.085f));
            c.drawText(fit(it.label == null ? "" : it.label, railLabelPaint, r.width() - 20 * k), r.centerX(), r.top + r.height() * 0.66f, railLabelPaint);
            railSubPaint.setColor(0xA6141414);
            railSubPaint.setTextSize(Math.max(12 * k, r.width() * 0.07f));
            c.drawText("HDMI " + it.number, r.centerX(), r.top + r.height() * 0.84f, railSubPaint);
            railLabelPaint.setColor(MUTE);
            railLabelPaint.setTextSize(Ui.LABEL * k);
            railSubPaint.setColor(DIM);
            railSubPaint.setTextSize(20 * k);
        } else if (it.kind == HomeApps.ALL_APPS) {
            Glass.plate(c, r, radius, k, fill, stroke);
            drawAppsIcon(c, r);
        } else {
            Thumbs.Entry entry = Thumbs.entry(it.pkg);
            drawGlassTile(c, r, entry != null ? entry.brand : tileColor(it));
            if (entry != null && entry.logo != null) drawFittedLogo(c, entry, r);
            else drawName(c, it.label == null ? "" : it.label, r, 0xFFFFFFFF);
        }
    }

    private static int darken(int color) {
        int r = (color >> 16 & 255) * 3 / 4, g = (color >> 8 & 255) * 3 / 4, b = (color & 255) * 3 / 4;
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private void drawImage(Canvas c, Bitmap b, RectF r, float radius, boolean circle) {
        BitmapShader shader = shaders.get(b);
        if (shader == null) {
            shader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            shaders.put(b, shader);
        }
        float scale = Math.max(r.width() / b.getWidth(), r.height() / b.getHeight());
        matrix.setScale(scale, scale);
        matrix.postTranslate(r.left + (r.width() - b.getWidth() * scale) / 2, r.top + (r.height() - b.getHeight() * scale) / 2);
        shader.setLocalMatrix(matrix);
        imagePaint.setShader(shader);
        if (circle) c.drawCircle(r.centerX(), r.centerY(), r.width() / 2, imagePaint);
        else c.drawRoundRect(r, radius, radius, imagePaint);
        imagePaint.setShader(null);
    }

    private void drawPanel(Canvas c) {
        header.drawScreen(c, "Quick settings", clockText, meridiemText, dateText);
        Glass.plate(c, leftCard, k, fill, stroke);
        Glass.plate(c, rightCard, k, fill, stroke);
        for (Row r : right) drawRow(c, r);
        for (Row r : left) {
            if (r.local) {
                stroke.setStrokeWidth(2 * k);
                stroke.setColor(0xFF333333);
                c.drawLine(r.rect.left + 20 * k, r.rect.top - 13 * k, r.rect.right - 20 * k, r.rect.top - 13 * k, stroke);
            }
            drawRow(c, r);
        }
    }

    private void drawRow(Canvas c, Row r) {
        RectF rect = r.rect;
        if (r.header) {
            c.drawText(r.title.toUpperCase(), rect.left + 20 * k, rect.bottom - 8 * k, headerPaint);
            return;
        }
        int ink = r.on ? INK : DIM, sub = r.on ? MUTE : DIM;
        drawIcon(c, r.symbol, rect.left + 38 * k, rect.centerY(), 36 * k, ink);
        rowTitlePaint.setColor(ink);
        float baseline = rect.centerY() + Ui.halfCap(rowTitlePaint);
        float statusRight = rect.right - 20 * k;
        if (!r.status.isEmpty()) {
            rowStatusPaint.setColor(sub);
            c.drawText(r.status, statusRight, rect.centerY() + Ui.halfCap(rowStatusPaint), rowStatusPaint);
            float dot = statusRight - rowStatusPaint.measureText(r.status) - 26 * k;
            if (Settings.ACTION_WIFI_SETTINGS.equals(r.action)) {
            } else if (r.on) {
                fill.setColor(INK);
                c.drawCircle(dot, rect.centerY(), 7 * k, fill);
            } else {
                stroke.setStrokeWidth(2 * k);
                stroke.setColor(DIM);
                c.drawCircle(dot, rect.centerY(), 6 * k, stroke);
            }
        } else if (r.local) {
            drawChevron(c, statusRight - 12 * k, rect.centerY());
        } else if (r.action != null) {
            drawArrow(c, statusRight - 12 * k, rect.centerY());
        }
        float reserved = r.status.isEmpty() ? 130 * k : rowStatusPaint.measureText(r.status) + 130 * k;
        c.drawText(fit(r.title, rowTitlePaint, rect.width() - 76 * k - reserved), rect.left + 76 * k, baseline, rowTitlePaint);
    }

    private void drawIcon(Canvas c, String name, float cx, float cy, float size, int color) {
        if (name.equals("arca")) drawArca(c, cx, cy, size * 1.15f);
        else Icons.draw(getContext(), c, Icons.res(name), cx, cy, size * 1.2f, color);
    }

    private void drawChevron(Canvas c, float cx, float cy) {
        Icons.draw(getContext(), c, R.drawable.icon_chevron_right, cx, cy, 28 * k, MUTE);
    }

    private void drawArca(Canvas c, float cx, float cy, float size) {
        float u = size / 280f, x0 = cx - 256 * u, y0 = cy - 252 * u;
        fill.setColor(INK);
        path.rewind();
        path.moveTo(x0 + 136 * u, y0 + 380 * u);
        path.lineTo(x0 + 136 * u, y0 + 242 * u);
        tmp.set(x0 + 136 * u, y0 + 122 * u, x0 + 376 * u, y0 + 362 * u);
        path.arcTo(tmp, 180, 180);
        path.lineTo(x0 + 376 * u, y0 + 380 * u);
        path.lineTo(x0 + 308 * u, y0 + 380 * u);
        path.lineTo(x0 + 308 * u, y0 + 242 * u);
        tmp.set(x0 + 204 * u, y0 + 190 * u, x0 + 308 * u, y0 + 294 * u);
        path.arcTo(tmp, 0, -180);
        path.lineTo(x0 + 204 * u, y0 + 380 * u);
        path.close();
        c.drawPath(path, fill);
        fill.setColor(0x66FFFFFF);
        tmp.set(x0 + 226 * u, y0 + 280 * u, x0 + 286 * u, y0 + 380 * u);
        c.drawRoundRect(tmp, 4 * u, 4 * u, fill);
    }

    private void drawArrow(Canvas c, float cx, float cy) {
        Icons.draw(getContext(), c, R.drawable.icon_external, cx, cy, 28 * k, MUTE);
    }

    private void drawSheet(Canvas c) {
        header.drawScreen(c, "All apps", clockText, meridiemText, dateText);
        c.save();
        c.clipRect(0, 215 * k, w, h);
        c.translate(0, -scroll.value * GRID_ROW * k);
        for (int i = 0; i < others.size(); i++) {
            RectF r = gridRects[i];
            if (r.bottom - scroll.value * GRID_ROW * k < 200 * k || r.top - scroll.value * GRID_ROW * k > h) continue;
            HomeApps.Item it = others.get(i);
            drawTile(c, it, r);
        }
        if (zone == Z_SHEET) drawRing(c);
        c.restore();
    }

    private void drawToast(Canvas c) {
        if (toast == null) return;
        float width = toastPaint.measureText(toast) + 72 * k, x = (w - width) / 2, top = 40 * k;
        fill.setColor(0xF0262626);
        tmp.set(x, top, x + width, top + 64 * k);
        c.drawRoundRect(tmp, 32 * k, 32 * k, fill);
        c.drawText(toast, w / 2f, top + 64 * k / 2 + Ui.halfCap(toastPaint), toastPaint);
    }

    private String fit(String text, Paint paint, float max) {
        if (text == null) return "";
        if (paint.measureText(text) <= max) return text;
        int n = paint.breakText(text, true, max - paint.measureText("…"), null);
        return text.substring(0, Math.max(0, n)).trim() + "…";
    }

    // MARK: - Keys

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
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
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

    private void runMenu() {
        List<Watching.Card> cards = watching.items();
        if (cardIndex >= cards.size()) {
            menu.close();
            return;
        }
        Watching.Card card = cards.get(cardIndex);
        if (menu.choose() == ContinueMenu.DETAILS) {
            if (!watching.details(activity, card, cardRects[cardIndex], CARD_RADIUS * k)) showToast(watching.failure());
        } else {
            watching.hide(card.id, () -> {
                showToast("Couldn't remove it");
                invalidate();
            });
        }
    }

    boolean onKey(KeyEvent e) {
        wake();
        if (menu.isOpen()) return menuKey(e);
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
                    else if (e.getRepeatCount() == 1 && okDown && zone == Z_CARDS && cardIndex < watching.items().size() && !panelOpen && !sheetOpen) {
                        okDown = false;
                        menu.open();
                        invalidate();
                    }
                } else {
                    if (okDown && !e.isCanceled() && spoken.keyClick()) activate();
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
        if (zone == Z_WEATHER) {
            zone = cardCount() > 0 ? Z_CARDS : Z_RAIL;
            focusChanged();
        } else if (sheetOpen) {
            closeSheet();
        } else if (panelOpen) {
            closePanel();
        } else if (zone == Z_RAIL && cardCount() > 0) {
            zone = Z_CARDS;
            focusChanged();
        } else {
            return;
        }
        click.move();
    }

    private void move(int dx, int dy) {
        focusRing.pressed(dx, dy);
        moved = true;
        int beforeZone = zone, beforeIndex = zone == Z_CARDS ? cardIndex : zone == Z_RAIL ? railIndex : zone == Z_SHEET ? gridIndex : panelCol * 100 + panelRow;
        switch (zone) {
            case Z_CARDS:
                if (dx != 0) cardIndex = Math.max(0, Math.min(cardCount() - 1, cardIndex + dx));
                if (dy > 0) {
                    zone = Z_RAIL;
                    int input = firstInput();
                    railIndex = input >= 0 ? input : nearestRail(cardRects[cardIndex].centerX());
                } else if (dy < 0 && weatherFocusable()) {
                    zone = Z_WEATHER;
                }
                break;
            case Z_WEATHER:
                if (dy > 0) zone = cardCount() > 0 ? Z_CARDS : Z_RAIL;
                break;
            case Z_RAIL:
                if (dx != 0) railIndex = Math.max(0, Math.min(rail.size() - 1, railIndex + dx));
                if (dy < 0 && cardCount() > 0) {
                    zone = Z_CARDS;
                    cardIndex = nearestCard(railCenters[railIndex]);
                } else if (dy < 0 && weatherFocusable()) {
                    zone = Z_WEATHER;
                } else if (dy > 0) {
                    openPanel();
                }
                break;
            case Z_PANEL:
                movePanel(dx, dy);
                break;
            default:
                moveGrid(dx, dy);
                break;
        }
        int afterIndex = zone == Z_CARDS ? cardIndex : zone == Z_RAIL ? railIndex : zone == Z_SHEET ? gridIndex : panelCol * 100 + panelRow;
        if (zone != beforeZone || afterIndex != beforeIndex) {
            click.move();
            focusChanged();
        }
    }

    private int firstInput() {
        for (int i = 0; i < rail.size(); i++) if (rail.get(i).kind == HomeApps.INPUT) return i;
        return -1;
    }

    private int nearestRail(float x) {
        int best = 0;
        for (int i = 0; i < railCenters.length; i++) if (Math.abs(railCenters[i] - x) < Math.abs(railCenters[best] - x)) best = i;
        return best;
    }

    private int nearestCard(float x) {
        int best = 0;
        for (int i = 0; i < cardCount(); i++) if (Math.abs(cardRects[i].centerX() - x) < Math.abs(cardRects[best].centerX() - x)) best = i;
        return best;
    }

    private void scrollCards() {
        if (cardIndex < cardFirst) cardFirst = cardIndex;
        else if (cardIndex > cardFirst + 3) cardFirst = cardIndex - 3;
        cardFirst = Math.max(0, Math.min(cardFirst, Math.max(0, cardCount() - 4)));
        cardScroll.target = cardFirst;
    }

    private void focusChanged() {
        scrollCards();
        updateBackdrop();
        for (int i = 0; i < railScale.length; i++) railScale[i].target = zone == Z_RAIL && i == railIndex ? 1 : 0;
        spoken.focusMoved();
        invalidate();
    }

    private void openPanel() {
        panelOpen = true;
        panel.target = 1;
        zone = Z_PANEL;
        panelCol = 0;
        panelRow = firstFocusable(left);
        reloadSoon();
        focusChanged();
    }

    private void closePanel() {
        panelOpen = false;
        panel.target = 0;
        zone = Z_RAIL;
        focusChanged();
    }

    private void openSheet() {
        if (others.isEmpty()) return;
        sheetOpen = true;
        sheet.target = 1;
        zone = Z_SHEET;
        gridIndex = 0;
        scroll.snap(0);
        focusChanged();
    }

    private void closeSheet() {
        sheetOpen = false;
        sheet.target = 0;
        zone = panelOpen ? Z_PANEL : Z_RAIL;
        focusChanged();
    }

    private static int firstFocusable(List<Row> rows) {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).focusable) return i;
        return -1;
    }

    private static int step(List<Row> rows, int from, int dir) {
        for (int i = from + dir; i >= 0 && i < rows.size(); i += dir) if (rows.get(i).focusable) return i;
        return -1;
    }

    private Row focusedRow() {
        List<Row> rows = panelCol == 0 ? left : right;
        return panelRow >= 0 && panelRow < rows.size() ? rows.get(panelRow) : null;
    }

    private void clampPanelFocus() {
        List<Row> rows = panelCol == 0 ? left : right;
        if (panelRow < 0 || panelRow >= rows.size() || !rows.get(panelRow).focusable) panelRow = firstFocusable(rows);
        if (panelRow < 0) {
            panelCol = 1;
            panelRow = firstFocusable(right);
        }
    }

    private void movePanel(int dx, int dy) {
        List<Row> rows = panelCol == 0 ? left : right;
        if (dy != 0) {
            int next = step(rows, panelRow, dy);
            if (next >= 0) panelRow = next;
            else if (dy < 0) closePanel();
        } else if (dx != 0) {
            int toCol = dx > 0 ? 1 : 0;
            if (toCol == panelCol) return;
            List<Row> target = toCol == 0 ? left : right;
            float y = rows.get(panelRow).rect.centerY();
            int best = -1;
            for (int i = 0; i < target.size(); i++) {
                if (!target.get(i).focusable) continue;
                if (best < 0 || Math.abs(target.get(i).rect.centerY() - y) < Math.abs(target.get(best).rect.centerY() - y)) best = i;
            }
            if (best >= 0) {
                panelCol = toCol;
                panelRow = best;
            }
        }
    }

    private void moveGrid(int dx, int dy) {
        int n = others.size();
        if (n == 0) return;
        int next = gridIndex + dx + dy * GRID_COLUMNS;
        if (next < 0 || next >= n) return;
        gridIndex = next;
        int row = gridIndex / GRID_COLUMNS, first = Math.round(scroll.target);
        if (row < first) scroll.target = row;
        else if (row >= first + GRID_VISIBLE_ROWS) scroll.target = row - GRID_VISIBLE_ROWS + 1;
    }

    private void activate() {
        click.open();
        switch (zone) {
            case Z_CARDS:
                List<Watching.Card> cards = watching.items();
                if (cardIndex < cardCount() && cardIndex < cards.size() && !watching.open(activity, cards.get(cardIndex), cardRects[cardIndex], CARD_RADIUS * k)) showToast(watching.failure());
                break;
            case Z_RAIL:
                openItem(rail.get(railIndex), railRects[railIndex]);
                break;
            case Z_WEATHER:
                try {
                    activity.startActivity(new Intent(activity, DomoActivity.class));
                } catch (ActivityNotFoundException e) {
                    showToast("Couldn't open arca domo");
                }
                break;
            case Z_PANEL:
                Row row = focusedRow();
                if (row == null) break;
                if (row.local) openItem(HomeApps.settings(), row.rect);
                else if (row.input != null) openItem(row.input, row.rect);
                else if (row.action != null) openSettings(row.action);
                break;
            default:
                if (gridIndex < others.size()) openItem(others.get(gridIndex), gridRects[gridIndex]);
                break;
        }
    }

    private void openItem(HomeApps.Item it, RectF r) {
        if (it.kind == HomeApps.ALL_APPS) {
            openSheet();
            return;
        }
        int x = Math.round(r.left), y = Math.round(r.top), width = Math.round(r.width()), height = Math.round(r.height());
        if (!HomeApps.open(activity, it, this, x, y, width, height)) showToast("Couldn't open " + it.label);
    }

    private void openSettings(String action) {
        try {
            activity.startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            showToast("Not available on this TV");
        }
    }

    // MARK: - TalkBack

    private static int a11yId(int group, int index) {
        return group * 1000 + index + 1;
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return spoken;
    }

    private void a11yItems(List<Integer> out) {
        if (sheetOpen) {
            for (int i = 0; i < others.size(); i++) out.add(a11yId(G_GRID, i));
        } else if (panelOpen) {
            for (int i = 0; i < left.size(); i++) if (left.get(i).focusable) out.add(a11yId(G_LEFT, i));
            for (int i = 0; i < right.size(); i++) if (right.get(i).focusable) out.add(a11yId(G_RIGHT, i));
        } else {
            if (weatherFocusable()) out.add(a11yId(G_WEATHER, 0));
            for (int i = 0; i < cardCount(); i++) out.add(a11yId(G_CARD, i));
            for (int i = 0; i < rail.size(); i++) out.add(a11yId(G_RAIL, i));
        }
    }

    private String a11yText(int id) {
        int group = (id - 1) / 1000, index = (id - 1) % 1000;
        switch (group) {
            case G_CARD:
                List<Watching.Card> cards = watching.items();
                if (index >= cardCount() || index >= cards.size()) return "";
                Watching.Card item = cards.get(index);
                return item.next ? (item.tag == null ? "" : item.tag + ", ") + item.title + ", " + item.sub : item.title + ", " + item.sub + ", " + Math.round(item.progress * 100) + " percent watched";
            case G_RAIL:
                if (index >= rail.size()) return "";
                HomeApps.Item it = rail.get(index);
                return it.kind == HomeApps.INPUT ? it.label + ", HDMI " + it.number : it.label;
            case G_WEATHER:
                return "Weather and arca domo" + (insideValue == null ? "" : Character.isDigit(insideValue.charAt(0)) ? ", PM2.5 inside " + insideValue + " micrograms per cubic metre" : ", air inside " + insideValue);
            case G_LEFT:
            case G_RIGHT:
                List<Row> rows = group == G_LEFT ? left : right;
                if (index >= rows.size()) return "";
                Row row = rows.get(index);
                return row.status.isEmpty() ? row.title : row.title + ", " + row.status;
            default:
                return index < others.size() ? others.get(index).label : "";
        }
    }

    private boolean a11yBounds(int id, Rect out) {
        int group = (id - 1) / 1000, index = (id - 1) % 1000;
        RectF r;
        switch (group) {
            case G_CARD: r = index < cardCount() ? cardRects[index] : null; break;
            case G_RAIL: r = index < railRects.length ? railRects[index] : null; break;
            case G_LEFT: r = index < left.size() ? left.get(index).rect : null; break;
            case G_RIGHT: r = index < right.size() ? right.get(index).rect : null; break;
            case G_WEATHER: r = corner.width() > 0 ? corner : null; break;
            default: r = index < gridRects.length ? gridRects[index] : null; break;
        }
        if (r == null) return false;
        out.set(Math.round(r.left), Math.round(r.top), Math.round(r.right), Math.round(r.bottom));
        return true;
    }

    private int a11yFocused() {
        switch (zone) {
            case Z_CARDS: return a11yId(G_CARD, cardIndex);
            case Z_RAIL: return a11yId(G_RAIL, railIndex);
            case Z_PANEL: return a11yId(panelCol == 0 ? G_LEFT : G_RIGHT, panelRow);
            case Z_WEATHER: return a11yId(G_WEATHER, 0);
            default: return a11yId(G_GRID, gridIndex);
        }
    }

    private void a11yFocus(int id) {
        int group = (id - 1) / 1000, index = (id - 1) % 1000;
        switch (group) {
            case G_CARD: zone = Z_CARDS; cardIndex = index; break;
            case G_RAIL: zone = Z_RAIL; railIndex = index; break;
            case G_LEFT: zone = Z_PANEL; panelCol = 0; panelRow = index; break;
            case G_RIGHT: zone = Z_PANEL; panelCol = 1; panelRow = index; break;
            case G_WEATHER: zone = Z_WEATHER; break;
            default: zone = Z_SHEET; gridIndex = index; break;
        }
        focusChanged();
    }
}
