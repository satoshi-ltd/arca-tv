package com.satoshilimited.tv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.View;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HubMusicActivity extends Activity {
    private MusicView view;
    private IdleSaver idle;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        view = new MusicView(this);
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

    static final class MusicView extends View {
        private static final int INK = 0xFFF2F2F2, MUTE = 0xB8FFFFFF, DIM = 0x73FFFFFF, DARK = 0xFF141414, PLACEHOLDER = 0xFF34302B;
        private static final float LIST_TOP = 262, TILE = 240, GRID_TOP = 330, GRID_STRIDE = 340;
        private static final int VISIBLE = 8, COLS = 6, GRID_ROWS = 2, TAB_RECENT = 0, TAB_ARTISTS = 1, TAB_PLAYLISTS = 2;
        private static final int ROOT = 0, ARTIST = 1, ALBUM = 2, PLAYLIST = 3, NOW = 4;
        private static final String[] TABS = {"Recent", "Artists", "Playlists"};

        private static final class Screen {
            final int type;
            final Object ref;
            int focus, scroll;

            Screen(int type, Object ref) {
                this.type = type;
                this.ref = ref;
            }
        }

        private final HubMusicActivity activity;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final ExecutorService io = Executors.newSingleThreadExecutor();
        private final ExecutorService covers = Executors.newFixedThreadPool(3);
        private final Map<String, Bitmap> cache = new HashMap<>();
        private final Set<String> asked = new HashSet<>();
        private final List<Screen> stack = new ArrayList<>();
        private final Backdrop backdrop = new Backdrop();
        private int nowFocus = 1;
        private Paint label;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG), pic = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF rect = new RectF(), tmp = new RectF();
        private final Matrix matrix = new Matrix();
        private MusicLibrary library;
        private List<MusicLibrary.Album> recent = new ArrayList<>();
        private List<MusicLibrary.Track> queue = new ArrayList<>();
        private String queueName = "";
        private MediaPlayer player;
        private int tab, qi = -1;
        private boolean tabsFocused, loading = true, failed, paused, running, okDown, backDown;
        private int w, h;
        private float k = 1;
        private LinearGradient background;
        private Header header;
        private final Paint spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Paint messagePaint, rowTitle, rowValue, hint, chip, tileTitle, tileSub, heading, big, bigSub, initial;
        private final Screen root = new Screen(ROOT, null);
        private final int[] rootFocus = new int[3], rootScroll = new int[3];
        private boolean inAlbums, shuffleAllFocused;
        private int albumFocus, albumScroll;

        private final Runnable tick = new Runnable() {
            @Override public void run() {
                if (!running) return;
                invalidate();
                handler.postDelayed(this, 1000);
            }
        };

        MusicView(HubMusicActivity a) {
            super(a);
            activity = a;
            stroke.setStyle(Paint.Style.STROKE);
            stack.add(root);
            setFocusable(true);
            load();
        }

        void resume() {
            running = true;
            handler.post(tick);
        }

        void pause() {
            running = false;
            handler.removeCallbacks(tick);
        }

        void release() {
            if (player != null) {
                player.release();
                player = null;
            }
            io.shutdownNow();
            covers.shutdownNow();
        }

        private void load() {
            io.execute(() -> {
                MusicLibrary lib = null;
                try {
                    String volume = ArcaHub.volume(getContext(), "music");
                    lib = MusicLibrary.parse(ArcaHub.get(getContext(), "/v1/music/library?volume=" + URLEncoder.encode(volume, "UTF-8"), 24_000_000));
                } catch (Exception e) {
                    AppLog.w("arca music list: " + e.getMessage());
                }
                final MusicLibrary result = lib;
                handler.post(() -> {
                    library = result;
                    loading = false;
                    failed = result == null;
                    if (result != null) {
                        recent = result.recent(getContext());
                        tab = recent.isEmpty() ? TAB_ARTISTS : TAB_RECENT;
                    }
                    invalidate();
                });
            });
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            background = new LinearGradient(0, 0, 0, h, new int[] {0xFF141414, 0xFF0E0E0E, 0xFF0A0A0A}, new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP);
            header = new Header(k);
            rowTitle = Ui.text(Ui.medium, 30 * k, INK);
            rowValue = Ui.text(Ui.regular, 26 * k, MUTE);
            rowValue.setTextAlign(Paint.Align.RIGHT);
            hint = Ui.text(Ui.regular, 26 * k, MUTE);
            chip = Ui.text(Ui.medium, 28 * k, INK);
            tileTitle = Ui.text(Ui.medium, 28 * k, INK);
            tileSub = Ui.text(Ui.regular, 24 * k, MUTE);
            heading = Ui.text(Ui.light, 44 * k, INK);
            big = Ui.numbers(Ui.light, 64 * k, INK);
            bigSub = Ui.text(Ui.regular, 34 * k, MUTE);
            messagePaint = Ui.text(Ui.medium, 30 * k, 0xB8FFFFFF);
            spinner.setStyle(Paint.Style.STROKE);
            spinner.setStrokeWidth(4 * k);
            spinner.setColor(0xB8FFFFFF);
            initial = Ui.text(Ui.light, 96 * k, DIM);
            initial.setTextAlign(Paint.Align.CENTER);
            label = Ui.text(Ui.semibold, 20 * k, MUTE);
            label.setLetterSpacing(0.12f);
        }

        private Screen top() {
            return stack.get(stack.size() - 1);
        }

        private int rootCount() {
            if (library == null) return 0;
            if (tab == TAB_RECENT) return recent.size();
            if (tab == TAB_ARTISTS) return library.artists.size();
            return library.playlists.size();
        }

        private List<MusicLibrary.Track> tracksOf(Screen s) {
            if (s.type == ALBUM) return ((MusicLibrary.Album) s.ref).tracks;
            return ((MusicLibrary.Playlist) s.ref).tracks;
        }

        private void play(List<MusicLibrary.Track> list, int index, String name) {
            if (index < 0 || index >= list.size()) return;
            queue = list;
            queueName = name;
            start(index);
        }

        private void start(int index) {
            if (index < 0 || index >= queue.size()) return;
            if (player != null) player.release();
            qi = index;
            paused = false;
            MusicLibrary.Track t = queue.get(index);
            if (library != null) {
                library.played(getContext(), t);
                recent = library.recent(getContext());
            }
            try {
                MediaPlayer mp = new MediaPlayer();
                player = mp;
                mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
                Map<String, String> headers = new HashMap<>();
                headers.put("Authorization", ArcaHub.authorization(getContext()));
                mp.setDataSource(getContext(), Uri.parse(ArcaHub.url(getContext()) + "/v1/blobs/" + t.hash), headers);
                mp.setOnPreparedListener(MediaPlayer::start);
                mp.setOnCompletionListener(m -> {
                    if (qi + 1 < queue.size()) start(qi + 1);
                    else paused = true;
                });
                mp.setOnErrorListener((m, what, extra) -> {
                    AppLog.w("arca music playback error " + what + "/" + extra);
                    return true;
                });
                mp.prepareAsync();
            } catch (Exception e) {
                AppLog.w("arca music: " + e.getMessage());
            }
        }

        private void toggle() {
            if (player == null) return;
            try {
                if (paused) player.start();
                else player.pause();
                paused = !paused;
            } catch (IllegalStateException ignored) {
            }
        }

        private Bitmap cover(String key, boolean large) {
            if (key == null || key.isEmpty()) return null;
            String id = (large ? "L" : "S") + key;
            Bitmap b = cache.get(id);
            if (b != null || !asked.add(id)) return b;
            covers.execute(() -> {
                Bitmap decoded = null;
                try {
                    String volume = ArcaHub.volume(getContext(), "music");
                    String body = ArcaHub.get(getContext(), "/v1/music/cover?volume=" + URLEncoder.encode(volume, "UTF-8") + "&key=" + URLEncoder.encode(key, "UTF-8") + "&size=" + (large ? "large" : "small"), 4_000_000);
                    String data = new JSONObject(body).optString("data", "");
                    int comma = data.indexOf(',');
                    if (comma > 0) {
                        byte[] raw = Base64.decode(data.substring(comma + 1), Base64.DEFAULT);
                        decoded = BitmapFactory.decodeByteArray(raw, 0, raw.length);
                    }
                } catch (Exception e) {
                    AppLog.w("arca music cover: " + e.getMessage());
                }
                final Bitmap result = decoded;
                if (result != null) handler.post(() -> {
                    cache.put(id, result);
                    invalidate();
                });
            });
            return null;
        }

        private void drawCover(Canvas c, String key, boolean large, RectF box, float radius, boolean round) {
            Bitmap b = cover(key, large);
            if (b == null) {
                fill.setColor(PLACEHOLDER);
                if (round) c.drawOval(box, fill);
                else c.drawRoundRect(box, radius, radius, fill);
                return;
            }
            BitmapShader shader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float scale = Math.max(box.width() / b.getWidth(), box.height() / b.getHeight());
            matrix.setScale(scale, scale);
            matrix.postTranslate(box.centerX() - b.getWidth() * scale / 2, box.centerY() - b.getHeight() * scale / 2);
            shader.setLocalMatrix(matrix);
            pic.setShader(shader);
            if (round) c.drawOval(box, pic);
            else c.drawRoundRect(box, radius, radius, pic);
            pic.setShader(null);
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            fill.setColor(0xFF000000);
            fill.setShader(background);
            c.drawRect(0, 0, w, h, fill);
            fill.setShader(null);
            updateBackdrop();
            backdrop.draw(c, w, h, 1f);
            long now = System.currentTimeMillis();
            Screen s = top();
            if (s.type == NOW) header.drawLockup(c, "MUSIC");
            else header.drawScreen(c, hero(s), Ui.clock(now), Ui.meridiem(now), Ui.date(now), "MUSIC");
            if (s.type == NOW) drawNow(c);
            else if (s.type == ROOT) drawRoot(c);
            else if (s.type == ARTIST) drawArtist(c, s);
            else drawTracks(c, s);
            if (s.type != NOW && qi >= 0 && qi < queue.size()) {
                MusicLibrary.Track t = queue.get(qi);
                c.drawText(fit((paused ? "Paused · " : "Playing · ") + t.title + (t.artist.isEmpty() ? "" : " — " + t.artist), hint, 1500 * k), 112 * k, 1030 * k, hint);
            }
            if (running && backdrop.active()) postInvalidateDelayed(50);
            else if (running && qi >= 0 && !paused && s.type != ROOT && s.type != NOW) postInvalidateDelayed(110);
        }

        private String hero(Screen s) {
            if (s.type == ARTIST) return ((MusicLibrary.Artist) s.ref).name;
            if (s.type == ALBUM) return ((MusicLibrary.Album) s.ref).title;
            if (s.type == PLAYLIST) return ((MusicLibrary.Playlist) s.ref).name;
            return TABS[tab];
        }

        private void plate(Canvas c, RectF box) {
            Glass.plate(c, box, k, fill, stroke);
        }

        private void drawMessage(Canvas c, String text) {
            boolean error = library == null && !loading;
            tmp.set(680 * k, 400 * k, 1240 * k, (error ? 700 : 620) * k);
            plate(c, tmp);
            messagePaint.setTextAlign(Paint.Align.CENTER);
            if (library == null && loading) Ui.spinner(c, 960 * k, 480 * k, 22 * k, SystemClock.uptimeMillis() / 1000f * 1.3f, spinner);
            c.drawText(text, 960 * k, (error ? 500 : 560) * k, messagePaint);
            if (error) {
                rect.set(820 * k, 560 * k, 1100 * k, 632 * k);
                Glass.plate(c, rect, 36 * k, k, fill, stroke);
                chip.setColor(INK);
                chip.setTextAlign(Paint.Align.CENTER);
                c.drawText("Try again", rect.centerX(), rect.centerY() + Ui.halfCap(chip), chip);
                chip.setTextAlign(Paint.Align.LEFT);
                Glass.focus(c, rect, 36 * k, k, fill, stroke);
            }
            messagePaint.setTextAlign(Paint.Align.LEFT);
            if (library == null && loading && running) postInvalidateDelayed(60);
        }

        private void drawRoot(Canvas c) {
            float x = 112;
            for (int i = 0; i < TABS.length; i++) {
                float width = chip.measureText(TABS[i]) / k + 56;
                tmp.set(x * k, 222 * k, (x + width) * k, 278 * k);
                boolean active = i == tab;
                if (active) {
                    for (int layer = 3; layer >= 1; layer--) {
                        fill.setColor(0x14000000);
                        rect.set(tmp.left - layer * 2 * k, tmp.top + 4 * k + layer * 3 * k, tmp.right + layer * 2 * k, tmp.bottom + 4 * k + layer * 3 * k);
                        c.drawRoundRect(rect, 28 * k + layer * 2 * k, 28 * k + layer * 2 * k, fill);
                    }
                    fill.setColor(INK);
                    c.drawRoundRect(tmp, 28 * k, 28 * k, fill);
                } else Glass.plate(c, tmp, 28 * k, k, fill, stroke);
                chip.setColor(active ? DARK : MUTE);
                c.drawText(TABS[i], tmp.left + 28 * k, tmp.centerY() + Ui.halfCap(chip), chip);
                if (active && tabsFocused && !shuffleAllFocused) ring(c, tmp, 28 * k);
                x += width + 14;
            }
            if (library != null && !library.artists.isEmpty()) {
                fill.setColor(0x24FFFFFF);
                c.drawRect(x * k, 230 * k, (x + 1.5f) * k, 270 * k, fill);
                x += 24;
                String shuffleLabel = "Shuffle all";
                float shuffleWidth = 34 + 12 + chip.measureText(shuffleLabel) / k + 48;
                tmp.set(x * k, 222 * k, (x + shuffleWidth) * k, 278 * k);
                Glass.plate(c, tmp, 28 * k, k, fill, stroke);
                drawShuffleIcon(c, tmp.left + 36 * k, tmp.centerY(), 11 * k);
                chip.setColor(INK);
                c.drawText(shuffleLabel, tmp.left + 62 * k, tmp.centerY() + Ui.halfCap(chip), chip);
                if (tabsFocused && shuffleAllFocused) ring(c, tmp, 28 * k);
            }
            chip.setColor(INK);
            if (library == null) {
                drawMessage(c, loading ? "Loading your music" : "Couldn't reach " + ArcaHub.name(getContext()));
                return;
            }
            int count = rootCount();
            if (count == 0) {
                drawMessage(c, tab == TAB_RECENT ? "No recent albums yet" : tab == TAB_ARTISTS ? "No music on " + ArcaHub.name(getContext()) + " yet" : "No playlists yet");
                return;
            }
            if (tab == TAB_ARTISTS) {
                drawSplit(c);
                return;
            }
            drawGrid(c, count, rootFocus[tab], rootScroll[tab], !tabsFocused, tab == TAB_ARTISTS, GRID_TOP, i -> {
                if (tab == TAB_RECENT) {
                    MusicLibrary.Album a = recent.get(i);
                    return new String[] {a.title, a.artist, a.cover};
                }
                if (tab == TAB_PLAYLISTS) {
                    MusicLibrary.Playlist p = library.playlists.get(i);
                    return new String[] {p.name, p.tracks.size() + (p.tracks.size() == 1 ? " track" : " tracks"), p.cover()};
                }
                MusicLibrary.Artist a = library.artists.get(i);
                return new String[] {a.name, a.albums.size() + (a.albums.size() == 1 ? " album" : " albums"), a.cover};
            });
        }

        private static final float SPLIT_TOP = 316, SPLIT_RIGHT_L = 880, ALBUM_TILE = 200, ALBUM_GAP = 24;
        private static final int SPLIT_ROWS = 7, ALBUM_COLS = 4, ALBUM_ROWS = 2;

        private void drawSplit(Canvas c) {
            int n = library.artists.size(), focus = rootFocus[tab], first = rootScroll[tab];
            int rows = Math.min(SPLIT_ROWS, n - first);
            tmp.set(112 * k, SPLIT_TOP * k, 820 * k, (SPLIT_TOP + 2 * Glass.PLATE_INSET + SPLIT_ROWS * (Glass.ROW_H + Glass.ROW_GAP) - Glass.ROW_GAP) * k);
            plate(c, tmp);
            for (int i = 0; i < rows; i++) {
                int at = first + i;
                MusicLibrary.Artist a = library.artists.get(at);
                rowBox(i, 112 + Glass.PLATE_INSET, 820 - Glass.PLATE_INSET, SPLIT_TOP, rect);
                boolean current = at == focus, focusedRow = current && !inAlbums && !tabsFocused;
                if (current && !focusedRow) {
                    fill.setColor(Glass.FILL);
                    c.drawRoundRect(rect, Glass.ROW_RADIUS * k, Glass.ROW_RADIUS * k, fill);
                }
                tmp.set(rect.left + 12 * k, rect.centerY() - 30 * k, rect.left + 72 * k, rect.centerY() + 30 * k);
                drawCover(c, a.cover, false, tmp, 0, true);
                rowTitle.setColor(INK);
                c.drawText(fit(a.name, rowTitle, 360 * k), rect.left + 92 * k, rect.centerY() + Ui.halfCap(rowTitle), rowTitle);
                rowValue.setTextAlign(Paint.Align.RIGHT);
                c.drawText(a.albums.size() + (a.albums.size() == 1 ? " album" : " albums"), rect.right - 20 * k, rect.centerY() + Ui.halfCap(rowValue), rowValue);
                Glass.row(c, rect, k, fill, stroke, focusedRow);
            }
            MusicLibrary.Artist a = library.artists.get(focus);
            heading.setTextSize(36 * k);
            String shownName = fit(a.name, heading, 560 * k);
            c.drawText(shownName, SPLIT_RIGHT_L * k, 366 * k, heading);
            tileSub.setTextSize(26 * k);
            c.drawText("· " + a.albums.size() + (a.albums.size() == 1 ? " album" : " albums") + " · " + a.tracks.size() + (a.tracks.size() == 1 ? " track" : " tracks"), SPLIT_RIGHT_L * k + heading.measureText(shownName) + 14 * k, 366 * k, tileSub);
            tileSub.setTextSize(22 * k);
            tileTitle.setTextSize(24 * k);
            int count = a.albums.size() + 1;
            int firstTile = albumScroll * ALBUM_COLS;
            for (int i = firstTile; i < Math.min(count, firstTile + ALBUM_COLS * ALBUM_ROWS); i++) {
                int col = (i - firstTile) % ALBUM_COLS, row = (i - firstTile) / ALBUM_COLS;
                float left = SPLIT_RIGHT_L + col * (ALBUM_TILE + (1808 - SPLIT_RIGHT_L - ALBUM_COLS * ALBUM_TILE) / (float) (ALBUM_COLS - 1)), top = 430 + row * 270;
                rect.set(left * k, top * k, (left + ALBUM_TILE) * k, (top + ALBUM_TILE) * k);
                if (i == 0) {
                    Glass.plate(c, rect, 20 * k, k, fill, stroke);
                    drawShuffleIcon(c, rect.centerX(), rect.centerY(), 22 * k);
                    tileTitle.setTextAlign(Paint.Align.CENTER);
                    c.drawText("Shuffle", rect.centerX(), rect.bottom + 34 * k, tileTitle);
                    tileTitle.setTextAlign(Paint.Align.LEFT);
                } else {
                    MusicLibrary.Album al = a.albums.get(i - 1);
                    drawCover(c, al.cover, false, rect, 20 * k, false);
                    c.drawText(fit(al.title, tileTitle, ALBUM_TILE * k), rect.left, rect.bottom + 34 * k, tileTitle);
                }
                if (inAlbums && i == albumFocus) {
                    tmp.set(rect);
                    ring(c, tmp, 20 * k);
                }
            }
            tileTitle.setTextSize(28 * k);
            tileSub.setTextSize(24 * k);
        }

        private interface GridItem { String[] at(int index); }

        private void drawGrid(Canvas c, int count, int focus, int scrollRow, boolean focused, boolean round, float gridTop, GridItem items) {
            int first = scrollRow * COLS;
            for (int i = first; i < Math.min(count, first + COLS * GRID_ROWS); i++) {
                int col = (i - first) % COLS, row = (i - first) / COLS;
                float left = 112 + col * (1696 - TILE) / (COLS - 1), top = gridTop + row * GRID_STRIDE;
                rect.set(left * k, top * k, (left + TILE) * k, (top + TILE) * k);
                String[] item = items.at(i);
                String name = item[0], sub = item[1], cover = item[2];
                drawCover(c, cover, false, rect, 22 * k, round);
                if (round && cache.get("S" + cover) == null) c.drawText(name.isEmpty() ? "" : name.substring(0, 1).toUpperCase(Locale.ROOT), rect.centerX(), rect.centerY() + Ui.halfCap(initial), initial);
                float textLeft = rect.left;
                if (round) {
                    tileTitle.setTextAlign(Paint.Align.CENTER);
                    tileSub.setTextAlign(Paint.Align.CENTER);
                    textLeft = rect.centerX();
                }
                c.drawText(fit(name, tileTitle, TILE * k), textLeft, rect.bottom + 40 * k, tileTitle);
                c.drawText(fit(sub, tileSub, TILE * k), textLeft, rect.bottom + 74 * k, tileSub);
                tileTitle.setTextAlign(Paint.Align.LEFT);
                tileSub.setTextAlign(Paint.Align.LEFT);
                if (i == focus && focused) {
                    tmp.set(rect);
                    if (round) ringOval(c, tmp);
                    else ring(c, tmp, 22 * k);
                }
            }
        }

        private interface RowDrawer { void draw(int index, RectF box, float baseline); }

        private void drawList(Canvas c, int count, int focus, int scroll, boolean focused, RowDrawer drawer) {
            drawList(c, count, focus, scroll, focused, 112, 1808, LIST_TOP, drawer);
        }

        private void drawList(Canvas c, int count, int focus, int scroll, boolean focused, float cardLeft, float cardRight, float top, RowDrawer drawer) {
            int rows = Math.min(VISIBLE, count);
            float bottom = top + 2 * Glass.PLATE_INSET + rows * (Glass.ROW_H + Glass.ROW_GAP) - Glass.ROW_GAP;
            tmp.set(cardLeft * k, top * k, cardRight * k, bottom * k);
            plate(c, tmp);
            for (int i = 0; i < rows; i++) {
                int at = scroll + i;
                if (at >= count) break;
                rowBox(i, cardLeft + Glass.PLATE_INSET, cardRight - Glass.PLATE_INSET, top, rect);
                drawer.draw(at, rect, rect.centerY() + Ui.halfCap(rowTitle));
            }
            if (focused && focus - scroll >= 0 && focus - scroll < rows) {
                rowBox(focus - scroll, cardLeft + Glass.PLATE_INSET, cardRight - Glass.PLATE_INSET, top, rect);
                Glass.row(c, rect, k, fill, stroke, true);
            }
        }

        private void drawArtist(Canvas c, Screen s) {
            MusicLibrary.Artist a = (MusicLibrary.Artist) s.ref;
            c.drawText(a.albums.size() + (a.albums.size() == 1 ? " album" : " albums") + " · " + a.tracks.size() + (a.tracks.size() == 1 ? " track" : " tracks"), 112 * k, 270 * k, tileSub);
            drawGrid(c, a.albums.size(), s.focus, s.scroll, true, false, GRID_TOP + 20, i -> {
                MusicLibrary.Album al = a.albums.get(i);
                return new String[] {al.title, al.year > 0 ? String.valueOf(al.year) : "", al.cover};
            });
        }

        private void drawTracks(Canvas c, Screen s) {
            List<MusicLibrary.Track> list = tracksOf(s);
            boolean isAlbum = s.type == ALBUM;
            float cover = isAlbum ? 380 : 320, cardLeft = isAlbum ? 600 : 520;
            rect.set(112 * k, 262 * k, (112 + cover) * k, (262 + cover) * k);
            String count = list.size() + (list.size() == 1 ? " track" : " tracks");
            String title = isAlbum ? ((MusicLibrary.Album) s.ref).title : ((MusicLibrary.Playlist) s.ref).name;
            drawCover(c, isAlbum ? ((MusicLibrary.Album) s.ref).cover : ((MusicLibrary.Playlist) s.ref).cover(), true, rect, 22 * k, false);
            tileTitle.setTextSize(44 * k);
            float captionBase = (262 + cover + 66) * k;
            c.drawText(fit(title, tileTitle, cover * k), 112 * k, captionBase, tileTitle);
            tileTitle.setTextSize(28 * k);
            if (isAlbum) {
                MusicLibrary.Album a = (MusicLibrary.Album) s.ref;
                tileSub.setTextSize(28 * k);
                c.drawText(fit(a.artist + (a.year > 0 ? " · " + a.year : ""), tileSub, cover * k), 112 * k, captionBase + 46 * k, tileSub);
                tileSub.setTextSize(26 * k);
                c.drawText(count + minutes(list), 112 * k, captionBase + 86 * k, tileSub);
            } else {
                tileSub.setTextSize(26 * k);
                c.drawText(count + minutes(list), 112 * k, captionBase + 46 * k, tileSub);
            }
            tileSub.setTextSize(24 * k);
            final float left = cardLeft;
            boolean album = s.type == ALBUM;
            drawList(c, list.size(), s.focus, s.scroll, true, left, 1808, 262, (i, box, base) -> {
                MusicLibrary.Track t = list.get(i);
                float x = box.left + 20 * k;
                if (queue == list && i == qi) {
                    stroke.setColor(INK);
                    stroke.setStrokeWidth(4 * k);
                    stroke.setStrokeCap(Paint.Cap.ROUND);
                    float[] bars = paused ? new float[] {8, 8, 8} : new float[] {10 + 8 * (float) Math.abs(Math.sin(SystemClock.uptimeMillis() / 220.0)), 8 + 12 * (float) Math.abs(Math.sin(SystemClock.uptimeMillis() / 170.0 + 1)), 10 + 6 * (float) Math.abs(Math.sin(SystemClock.uptimeMillis() / 260.0 + 2))};
                    for (int bar = 0; bar < 3; bar++) {
                        float bx = x + bar * 9 * k;
                        c.drawLine(bx + 2.5f * k, box.centerY() - bars[bar] / 2 * k, bx + 2.5f * k, box.centerY() + bars[bar] / 2 * k, stroke);
                    }
                    stroke.setStrokeCap(Paint.Cap.BUTT);
                    x += 36 * k;
                } else {
                    rowValue.setTextAlign(Paint.Align.LEFT);
                    c.drawText(String.valueOf(album ? t.number : i + 1), x, base, rowValue);
                    rowValue.setTextAlign(Paint.Align.RIGHT);
                    x += 56 * k;
                }
                float right = box.right - 20 * k;
                String time = time(t.seconds);
                c.drawText(time, right, base, rowValue);
                right -= rowValue.measureText(time) + 36 * k;
                if (album) {
                    c.drawText(fit(t.title, rowTitle, right - x), x, base, rowTitle);
                } else {
                    float subWidth = Math.min(rowValue.measureText(t.artist), 360 * k);
                    c.drawText(fit(t.artist, rowValue, subWidth), right, base, rowValue);
                    c.drawText(fit(t.title, rowTitle, right - subWidth - 36 * k - x), x, base, rowTitle);
                }
            });
        }

        private static String minutes(List<MusicLibrary.Track> list) {
            int total = 0;
            for (MusicLibrary.Track t : list) total += t.seconds;
            return total >= 60 ? " · " + Math.round(total / 60f) + " min" : "";
        }

        private String backdropCover() {
            Screen s = top();
            if (s.type == NOW) return qi >= 0 && qi < queue.size() ? queue.get(qi).cover : "";
            if (s.type == ALBUM) return ((MusicLibrary.Album) s.ref).cover;
            if (s.type == PLAYLIST) return ((MusicLibrary.Playlist) s.ref).cover();
            if (s.type == ARTIST) {
                MusicLibrary.Artist a = (MusicLibrary.Artist) s.ref;
                return s.focus < a.albums.size() ? a.albums.get(s.focus).cover : a.cover;
            }
            if (library == null || rootCount() == 0 || tabsFocused) return "";
            int f = rootFocus[tab];
            if (tab == TAB_RECENT) return recent.get(f).cover;
            if (tab == TAB_ARTISTS) {
                MusicLibrary.Artist a = library.artists.get(f);
                return inAlbums && albumFocus >= 1 && albumFocus - 1 < a.albums.size() ? a.albums.get(albumFocus - 1).cover : a.cover;
            }
            return library.playlists.get(f).cover();
        }

        private void updateBackdrop() {
            String key = backdropCover();
            if (key.isEmpty()) return;
            Bitmap b = cover(key, false);
            if (b != null) backdrop.showPhoto(key, b);
        }

        private void drawNow(Canvas c) {
            if (qi < 0 || qi >= queue.size()) return;
            MusicLibrary.Track t = queue.get(qi);
            cover(t.cover, true);
            rect.set(112 * k, 250 * k, 692 * k, 830 * k);
            fill.setColor(0x66000000);
            tmp.set(rect.left, rect.top + 18 * k, rect.right, rect.bottom + 18 * k);
            c.drawRoundRect(tmp, 26 * k, 26 * k, fill);
            drawCover(c, t.cover, true, rect, 24 * k, false);
            float x = 800 * k, right = 1808 * k;
            rowValue.setTextAlign(Paint.Align.LEFT);
            label.setColor(MUTE);
            c.drawText("NOW PLAYING", x, 300 * k, label);
            c.drawText(fit(t.title, big, right - x), x - Ui.leftBearing(big, t.title), 384 * k, big);
            c.drawText(fit(t.artist, bigSub, right - x), x, 438 * k, bigSub);
            tileSub.setTextSize(26 * k);
            if (!t.album.isEmpty()) c.drawText(fit(t.album, tileSub, right - x), x, 482 * k, tileSub);
            int position = 0, duration = t.seconds * 1000;
            try {
                if (player != null) {
                    position = player.getCurrentPosition();
                    int d = player.getDuration();
                    if (d > 0) duration = d;
                }
            } catch (IllegalStateException ignored) {
            }
            float barTop = 548 * k;
            fill.setColor(0x33FFFFFF);
            tmp.set(x, barTop, right, barTop + 6 * k);
            c.drawRoundRect(tmp, 3 * k, 3 * k, fill);
            if (duration > 0) {
                fill.setColor(INK);
                tmp.set(x, barTop, x + (right - x) * Math.min(1f, position / (float) duration), barTop + 6 * k);
                c.drawRoundRect(tmp, 3 * k, 3 * k, fill);
            }
            tileSub.setTextSize(26 * k);
            c.drawText(time(position / 1000), x, barTop + 50 * k, tileSub);
            tileSub.setTextAlign(Paint.Align.RIGHT);
            c.drawText(time(duration / 1000), right, barTop + 50 * k, tileSub);
            tileSub.setTextAlign(Paint.Align.LEFT);
            tileSub.setTextSize(24 * k);
            float cy = 706 * k;
            drawButton(c, 0, x + 40 * k, cy, 40 * k, false);
            drawButton(c, 1, x + 40 * k + 40 * k + 28 * k + 52 * k, cy, 52 * k, true);
            drawButton(c, 2, x + 40 * k + 40 * k + 28 * k + 104 * k + 28 * k + 40 * k, cy, 40 * k, false);
        }

        private void drawButton(Canvas c, int index, float cx, float cy, float r, boolean primary) {
            fill.setColor(primary ? INK : 0x1AFFFFFF);
            c.drawCircle(cx, cy, r, fill);
            if (!primary) {
                stroke.setStrokeWidth(1.5f * k);
                stroke.setColor(0x26FFFFFF);
                c.drawCircle(cx, cy, r, stroke);
            }
            int icon = index == 0 ? R.drawable.icon_previous : index == 2 ? R.drawable.icon_next : paused ? R.drawable.icon_play : R.drawable.icon_pause;
            Icons.draw(getContext(), c, icon, cx, cy, r * 0.9f, primary ? DARK : INK);
            if (index == nowFocus) {
                stroke.setStrokeWidth(6 * k);
                stroke.setColor(0xFF0E0E0E);
                c.drawCircle(cx, cy, r + 5 * k, stroke);
                stroke.setStrokeWidth(3 * k);
                stroke.setColor(INK);
                c.drawCircle(cx, cy, r + 9 * k, stroke);
            }
        }

        private void ring(Canvas c, RectF box, float radius) {
            Glass.ring(c, box, radius, k, stroke);
        }

        private void ringOval(Canvas c, RectF box) {
            Glass.ringOval(c, box, k, stroke);
        }

        private void drawShuffleIcon(Canvas c, float cx, float cy, float r) {
            Icons.draw(getContext(), c, R.drawable.icon_shuffle, cx, cy, r * 2.7f, INK);
        }

        private void playShuffled(List<MusicLibrary.Track> source, String name) {
            if (source.isEmpty()) return;
            List<MusicLibrary.Track> copy = new ArrayList<>(source);
            java.util.Collections.shuffle(copy);
            play(copy, 0, name);
        }

        private List<MusicLibrary.Track> allTracks() {
            List<MusicLibrary.Track> all = new ArrayList<>();
            for (MusicLibrary.Artist ar : library.artists) all.addAll(ar.tracks);
            return all;
        }

        private void rowBox(int i, float left, float right, RectF out) {
            rowBox(i, left, right, LIST_TOP, out);
        }

        private void rowBox(int i, float left, float right, float top, RectF out) {
            float y = top + Glass.PLATE_INSET + i * (Glass.ROW_H + Glass.ROW_GAP);
            out.set(left * k, y * k, right * k, (y + Glass.ROW_H) * k);
        }

        private static String time(int seconds) {
            if (seconds <= 0) return "";
            return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
        }

        private String fit(String text, Paint paint, float max) {
            if (paint.measureText(text) <= max) return text;
            int n = paint.breakText(text, true, max - paint.measureText("…"), null);
            return text.substring(0, Math.max(0, n)).trim() + "…";
        }

        private void open(int type, Object ref) {
            stack.add(new Screen(type, ref));
        }

        private void select() {
            Screen s = top();
            if (s.type == NOW) {
                if (nowFocus == 0 && qi > 0) start(qi - 1);
                else if (nowFocus == 2 && qi + 1 < queue.size()) start(qi + 1);
                else if (nowFocus == 1) toggle();
                return;
            }
            if (s.type == ROOT) {
                if (library == null) {
                    if (!loading) {
                        loading = true;
                        failed = false;
                        load();
                    }
                    return;
                }
                if (tabsFocused) {
                    if (shuffleAllFocused) {
                        playShuffled(allTracks(), "Shuffling your library");
                        nowFocus = 1;
                        open(NOW, null);
                    }
                    return;
                }
                if (rootCount() == 0) return;
                int f = rootFocus[tab];
                if (tab == TAB_RECENT) open(ALBUM, recent.get(f));
                else if (tab == TAB_ARTISTS) {
                    MusicLibrary.Artist a = library.artists.get(f);
                    if (!inAlbums) {
                        inAlbums = true;
                        albumFocus = 0;
                        albumScroll = 0;
                    } else if (albumFocus == 0) {
                        playShuffled(a.tracks, "Shuffling " + a.name);
                        nowFocus = 1;
                        open(NOW, null);
                    } else if (albumFocus - 1 < a.albums.size()) open(ALBUM, a.albums.get(albumFocus - 1));
                } else open(PLAYLIST, library.playlists.get(f));
                return;
            }
            if (s.type == ARTIST) {
                MusicLibrary.Artist a = (MusicLibrary.Artist) s.ref;
                if (s.focus < a.albums.size()) open(ALBUM, a.albums.get(s.focus));
                return;
            }
            List<MusicLibrary.Track> list = tracksOf(s);
            if (s.focus >= list.size()) return;
            if (queue == list && qi == s.focus) {
                nowFocus = 1;
                open(NOW, null);
                return;
            }
            String name = s.type == ALBUM ? "Next in this album" : "Next in this playlist";
            play(list, s.focus, name);
            nowFocus = 1;
            open(NOW, null);
        }

        private void move(int dx, int dy) {
            Screen s = top();
            if (s.type == NOW) {
                nowFocus = Math.max(0, Math.min(2, nowFocus + dx));
                return;
            }
            if (s.type == ARTIST) {
                int n = ((MusicLibrary.Artist) s.ref).albums.size(), f = s.focus;
                if (dx != 0) {
                    int next = f + dx;
                    if (next >= 0 && next < n && next / COLS == f / COLS) f = next;
                } else if (dy < 0) {
                    if (f >= COLS) f -= COLS;
                } else if (f / COLS < (n - 1) / COLS) {
                    f = Math.min(n - 1, f + COLS);
                }
                s.focus = f;
                int row = f / COLS;
                if (row < s.scroll) s.scroll = row;
                if (row >= s.scroll + GRID_ROWS) s.scroll = row - GRID_ROWS + 1;
                return;
            }
            if (s.type != ROOT) {
                int n = tracksOf(s).size();
                s.focus = Math.max(0, Math.min(n - 1, s.focus + dy));
                if (s.focus < s.scroll) s.scroll = s.focus;
                if (s.focus >= s.scroll + VISIBLE) s.scroll = s.focus - VISIBLE + 1;
                return;
            }
            if (tabsFocused) {
                boolean hasShuffle = library != null && !library.artists.isEmpty();
                if (dx > 0) {
                    if (hasShuffle && !shuffleAllFocused && tab == TABS.length - 1) shuffleAllFocused = true;
                    else if (!shuffleAllFocused) {
                        tab = Math.min(TABS.length - 1, tab + 1);
                        inAlbums = false;
                    }
                } else if (dx < 0) {
                    if (shuffleAllFocused) shuffleAllFocused = false;
                    else {
                        tab = Math.max(0, tab - 1);
                        inAlbums = false;
                    }
                } else if (dy > 0 && rootCount() > 0 && !shuffleAllFocused) {
                    tabsFocused = false;
                }
                return;
            }
            int n = rootCount(), f = rootFocus[tab];
            if (tab == TAB_ARTISTS) {
                MusicLibrary.Artist a = library.artists.get(f);
                if (inAlbums) {
                    int m = a.albums.size() + 1, g = albumFocus;
                    if (dx != 0) {
                        if (dx < 0 && g % ALBUM_COLS == 0) inAlbums = false;
                        else if (g + dx >= 0 && g + dx < m && (g + dx) / ALBUM_COLS == g / ALBUM_COLS) g += dx;
                    } else if (dy < 0) {
                        if (g >= ALBUM_COLS) g -= ALBUM_COLS;
                    } else if (g / ALBUM_COLS < (m - 1) / ALBUM_COLS) {
                        g = Math.min(m - 1, g + ALBUM_COLS);
                    }
                    albumFocus = g;
                    int row = g / ALBUM_COLS;
                    if (row < albumScroll) albumScroll = row;
                    if (row >= albumScroll + ALBUM_ROWS) albumScroll = row - ALBUM_ROWS + 1;
                    return;
                }
                if (dx > 0) {
                    inAlbums = true;
                    albumFocus = 0;
                    albumScroll = 0;
                    return;
                }
                if (dy < 0 && f == 0) {
                    tabsFocused = true;
                    return;
                }
                if (dy != 0) {
                    f = Math.max(0, Math.min(n - 1, f + dy));
                    rootFocus[tab] = f;
                    if (f < rootScroll[tab]) rootScroll[tab] = f;
                    if (f >= rootScroll[tab] + SPLIT_ROWS) rootScroll[tab] = f - SPLIT_ROWS + 1;
                    albumFocus = 0;
                    albumScroll = 0;
                }
                return;
            }
            if (dx != 0) {
                int next = f + dx;
                if (next >= 0 && next < n && next / COLS == f / COLS) f = next;
            } else if (dy < 0) {
                if (f < COLS) {
                    tabsFocused = true;
                    return;
                }
                f -= COLS;
            } else if (f / COLS < (n - 1) / COLS) {
                f = Math.min(n - 1, f + COLS);
            }
            rootFocus[tab] = f;
            int row = f / COLS;
            if (row < rootScroll[tab]) rootScroll[tab] = row;
            if (row >= rootScroll[tab] + GRID_ROWS) rootScroll[tab] = row - GRID_ROWS + 1;
        }

        boolean onKey(KeyEvent e) {
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
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    if (down) toggle();
                    break;
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    if (down && qi + 1 < queue.size()) start(qi + 1);
                    break;
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    if (down && qi > 0) start(qi - 1);
                    break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled()) select();
                        okDown = false;
                    }
                    break;
                case KeyEvent.KEYCODE_BACK:
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled()) {
                            if (stack.size() == 1 && tab == TAB_ARTISTS && inAlbums) inAlbums = false;
                            else if (stack.size() == 1 && !tabsFocused && rootCount() > 0) tabsFocused = true;
                            else if (stack.size() > 1) stack.remove(stack.size() - 1);
                            else activity.finish();
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
