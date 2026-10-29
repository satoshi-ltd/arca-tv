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
        private final FocusRing focusRing = new FocusRing(this);

        private static final class Screen {
            final int type;
            final Object ref;
            int focus, scroll;
            RectF from;
            long at = -1;
            float ox, oy;

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
        private Screen leaving;
        private boolean ghosting;
        private long tabAt = -1;
        private int tabDirection;
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
        private boolean tabsFocused, loading = true, failed, paused, running, okDown, backDown, longDone;
        private final ContinueMenu menu = new ContinueMenu(this);
        private final DeleteDialog deleting = new DeleteDialog(this, this::deleteConfirmed);
        private MusicLibrary.Track menuTrack;
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
        private static final float UP_LEFT = 1340, UP_RIGHT = 1808, UP_TOP = 220, UP_CAPTION = 56, UP_BOTTOM = 860, NOW_X = 760, NOW_RIGHT = 1280;
        private static final long IDLE_MS = 8000, HIDE_MS = 400, TEXT_DELAY = 160, TEXT_FADE = 200;
        private final Map<String, Integer> tints = new HashMap<>();
        private List<MusicLibrary.Track> queueSource = new ArrayList<>(), unshuffled;
        private List<Integer> order;
        private boolean shuffled, inQueue, flyIn;
        private int wakeKey = -1;
        private final Runnable hideKick = this::invalidate;
        private int queueFocus, queueScroll;
        private long lastInput = SystemClock.uptimeMillis(), flyStart = -1, textFrom;
        private String flyKey;
        private final RectF flyFrom = new RectF(), flyTo = new RectF(), flyRect = new RectF(), nowCover = new RectF();

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
            handler.removeCallbacks(hideKick);
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
            rowTitle = Ui.text(Ui.medium, Ui.BODY * k, INK);
            rowValue = Ui.text(Ui.regular, Ui.LABEL * k, MUTE);
            rowValue.setTextAlign(Paint.Align.RIGHT);
            hint = Ui.text(Ui.regular, Ui.LABEL * k, MUTE);
            chip = Ui.text(Ui.medium, 28 * k, INK);
            tileTitle = Ui.text(Ui.medium, 28 * k, INK);
            tileSub = Ui.text(Ui.regular, 24 * k, MUTE);
            heading = Ui.text(Ui.light, 44 * k, INK);
            big = Ui.numbers(Ui.light, 64 * k, INK);
            bigSub = Ui.text(Ui.regular, 34 * k, MUTE);
            messagePaint = Ui.text(Ui.medium, Ui.BODY * k, 0xB8FFFFFF);
            spinner.setStyle(Paint.Style.STROKE);
            spinner.setStrokeWidth(4 * k);
            spinner.setColor(0xB8FFFFFF);
            initial = Ui.text(Ui.light, 96 * k, DIM);
            initial.setTextAlign(Paint.Align.CENTER);
            label = Ui.text(Ui.semibold, Ui.TAG * k, MUTE);
            label.setLetterSpacing(0.11f);
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
            queueSource = list;
            queueName = name;
            shuffled = false;
            unshuffled = null;
            order = null;
            queueFocus = 0;
            queueScroll = 0;
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
                boolean fetched = false;
                int generation = MusicCovers.generation();
                String volume = ArcaHub.volume(getContext(), "music");
                byte[] raw = MusicCovers.read(getContext(), volume, key, large);
                if (raw != null) decoded = BitmapFactory.decodeByteArray(raw, 0, raw.length);
                if (decoded == null) {
                    try {
                        String body = ArcaHub.get(getContext(), "/v1/music/cover?volume=" + URLEncoder.encode(volume, "UTF-8") + "&key=" + URLEncoder.encode(key, "UTF-8") + "&size=" + (large ? "large" : "small"), 4_000_000);
                        String data = new JSONObject(body).optString("data", "");
                        int comma = data.indexOf(',');
                        if (comma > 0) {
                            raw = Base64.decode(data.substring(comma + 1), Base64.DEFAULT);
                            decoded = BitmapFactory.decodeByteArray(raw, 0, raw.length);
                            if (decoded != null) {
                                MusicCovers.write(getContext(), volume, key, large, raw, generation);
                                fetched = true;
                            }
                        }
                    } catch (Exception e) {
                        AppLog.w("arca music cover: " + e.getMessage());
                    }
                }
                final Bitmap result = decoded;
                final boolean fade = fetched;
                final int tint = result == null ? 0 : average(result);
                if (result != null) handler.post(() -> {
                    cache.put(id, result);
                    tints.put(id, tint);
                    if (fade) ArtFade.arrived(id);
                    invalidate();
                });
            });
            return null;
        }

        private static int average(Bitmap b) {
            return 0xFF000000 | Bitmap.createScaledBitmap(b, 1, 1, true).getPixel(0, 0);
        }

        private int tint(String key) {
            if (key == null || key.isEmpty()) return PLACEHOLDER;
            Integer t = tints.get("S" + key);
            if (t == null) t = tints.get("L" + key);
            return t == null ? PLACEHOLDER : t;
        }

        private void drawCover(Canvas c, String key, boolean large, RectF box, float radius, boolean round) {
            Bitmap b = cover(key, large);
            float alpha = b == null ? 0f : ArtFade.alpha((large ? "L" : "S") + key);
            if (alpha < 1f) {
                fill.setColor(tint(key));
                if (round) c.drawOval(box, fill);
                else c.drawRoundRect(box, radius, radius, fill);
            }
            if (b == null) return;
            BitmapShader shader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float scale = Math.max(box.width() / b.getWidth(), box.height() / b.getHeight());
            matrix.setScale(scale, scale);
            matrix.postTranslate(box.centerX() - b.getWidth() * scale / 2, box.centerY() - b.getHeight() * scale / 2);
            shader.setLocalMatrix(matrix);
            pic.setShader(shader);
            pic.setAlpha(Math.round(alpha * 255));
            if (round) c.drawOval(box, pic);
            else c.drawRoundRect(box, radius, radius, pic);
            pic.setAlpha(255);
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
            float opening = s.type == NOW ? 1f : eased(s.at), closing = leaving == null ? 1f : eased(leaving.at);
            if (closing >= 1f) leaving = null;
            if (s.type == NOW) {
                header.drawLockup(c, "MUSIC");
                drawNow(c);
            } else if (leaving != null && leaving.type != NOW) {
                drawZoom(c, leaving, s, 1f - closing, now);
            } else if (opening < 1f && stack.size() > 1) {
                drawZoom(c, s, stack.get(stack.size() - 2), opening, now);
            } else {
                drawBody(c, s, now);
            }
            if (s.type != NOW && qi >= 0 && qi < queue.size()) {
                MusicLibrary.Track t = queue.get(qi);
                c.drawText(fit((paused ? "Paused · " : "Playing · ") + t.title + (t.artist.isEmpty() ? "" : " — " + t.artist), hint, 1500 * k), 112 * k, 1030 * k, hint);
            }
            drawFly(c);
            if (menu.isOpen() && menuAnchor(rect)) menu.draw(getContext(), c, rect, k, h, fill, stroke);
            deleting.draw(getContext(), c, w, h, k, fill, stroke);
            if (running && (ArtFade.settling() || flyKey != null || leaving != null || opening < 1f || tabSlide() < 1f || (s.type == NOW && (textAlpha() < 1f || hiding())))) postInvalidateOnAnimation();
            else if (running && backdrop.active()) postInvalidateDelayed(50);
            else if (running && s.type == NOW && remaining() > 0 && plateAlpha() >= 1f) {
                handler.removeCallbacks(hideKick);
                handler.postDelayed(hideKick, Math.max(16, IDLE_MS - 20 - (SystemClock.uptimeMillis() - lastInput)));
            }
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
                if (active && tabsFocused && !shuffleAllFocused) ring(c, tmp, 28 * k, "tabs");
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
                if (tabsFocused && shuffleAllFocused) ring(c, tmp, 28 * k, "tabs");
            }
            chip.setColor(INK);
            float slide = tabSlide();
            if (slide >= 1f) {
                drawRootBody(c);
                return;
            }
            int layer = c.saveLayerAlpha(0, 0, w, h, Math.round(255 * slide));
            c.translate(tabDirection * 48 * k * (1f - slide), 0);
            drawRootBody(c);
            c.restoreToCount(layer);
        }

        private void slideTab(int next, int direction) {
            if (next == tab) return;
            tab = next;
            tabDirection = direction;
            tabAt = SystemClock.uptimeMillis();
        }

        private float tabSlide() {
            return eased(tabAt);
        }

        private void drawRootBody(Canvas c) {
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
                if (focusedRow) {
                    if (!ghosting) focusRing.at(a);
                    ringRow(c, rect, "artists");
                }
            }
            MusicLibrary.Artist a = library.artists.get(focus);
            heading.setTextSize(36 * k);
            String shownName = fit(a.name, heading, 560 * k);
            c.drawText(shownName, SPLIT_RIGHT_L * k, 366 * k, heading);
            tileSub.setTextSize(Ui.LABEL * k);
            c.drawText("· " + a.albums.size() + (a.albums.size() == 1 ? " album" : " albums") + " · " + a.tracks.size() + (a.tracks.size() == 1 ? " track" : " tracks"), SPLIT_RIGHT_L * k + heading.measureText(shownName) + 14 * k, 366 * k, tileSub);
            tileSub.setTextSize(Ui.CAPTION * k);
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
                    ring(c, tmp, 20 * k, "tiles");
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
                    if (!ghosting) focusRing.at(i);
                    tmp.set(rect);
                    if (round) ringOval(c, tmp, "tiles");
                    else ring(c, tmp, 22 * k, "tiles");
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
                if (!ghosting) focusRing.at(focus);
                ringRow(c, rect, "tracks");
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
                tileSub.setTextSize(Ui.LABEL * k);
                c.drawText(count + minutes(list), 112 * k, captionBase + 86 * k, tileSub);
            } else {
                tileSub.setTextSize(Ui.LABEL * k);
                c.drawText(count + minutes(list), 112 * k, captionBase + 46 * k, tileSub);
            }
            tileSub.setTextSize(24 * k);
            final float left = cardLeft;
            boolean album = s.type == ALBUM;
            drawList(c, list.size(), s.focus, s.scroll, true, left, 1808, 262, (i, box, base) -> {
                MusicLibrary.Track t = list.get(i);
                float x = box.left + 20 * k;
                if (playing(list, i)) {
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
            if (top().type == NOW && b != null) {
                backdrop.showWash("now\u0001" + key, tint(key), 0.3f, 0.45f, 1.4f);
                return;
            }
            if (b != null) backdrop.showPhoto(key, b);
        }

        private boolean playing(List<MusicLibrary.Track> list, int i) {
            return qi >= 0 && qi < queue.size() && queueSource == list && i >= 0 && i < list.size() && i == (order == null ? qi : order.get(qi));
        }

        private void nowCoverRect(RectF out) {
            out.set(112 * k, 220 * k, 672 * k, 780 * k);
        }

        private void startFly(String key, RectF from, RectF to, boolean in) {
            flyKey = key;
            flyFrom.set(from);
            flyTo.set(to);
            flyStart = SystemClock.uptimeMillis();
            flyIn = in;
        }

        private float flyProgress() {
            float t = Math.min(1f, (SystemClock.uptimeMillis() - flyStart) / (float) Reveal.MS);
            return 1f - (1f - t) * (1f - t) * (1f - t);
        }

        private void drawFly(Canvas c) {
            if (flyKey == null) return;
            float p = flyProgress();
            flyRect.set(flyFrom.left + (flyTo.left - flyFrom.left) * p, flyFrom.top + (flyTo.top - flyFrom.top) * p, flyFrom.right + (flyTo.right - flyFrom.right) * p, flyFrom.bottom + (flyTo.bottom - flyFrom.bottom) * p);
            fill.setColor(0x66000000);
            tmp.set(flyRect.left, flyRect.top + 18 * k * p, flyRect.right, flyRect.bottom + 18 * k * p);
            c.drawRoundRect(tmp, 24 * k, 24 * k, fill);
            drawCover(c, flyKey, true, flyRect, 24 * k, false);
            if (SystemClock.uptimeMillis() - flyStart >= Reveal.MS) flyKey = null;
        }

        private float textAlpha() {
            if (textFrom == 0) return 1f;
            return Math.max(0f, Math.min(1f, (SystemClock.uptimeMillis() - textFrom - TEXT_DELAY) / (float) TEXT_FADE));
        }

        private float plateAlpha() {
            long idle = SystemClock.uptimeMillis() - lastInput;
            if (idle < IDLE_MS) return 1f;
            return Math.max(0f, 1f - (idle - IDLE_MS) / (float) HIDE_MS);
        }

        private boolean hiding() {
            long idle = SystemClock.uptimeMillis() - lastInput;
            return idle >= IDLE_MS - 20 && idle < IDLE_MS + HIDE_MS + 20;
        }

        private int remaining() {
            return qi < 0 ? 0 : Math.max(0, queue.size() - qi - 1);
        }

        private int upRows() {
            return (int) ((UP_BOTTOM - UP_TOP - 2 * Glass.PLATE_INSET - UP_CAPTION + Glass.ROW_GAP) / (Glass.ROW_H + Glass.ROW_GAP));
        }

        private void openNow(RectF from, String key) {
            nowFocus = 1;
            inQueue = false;
            queueFocus = 0;
            queueScroll = 0;
            lastInput = SystemClock.uptimeMillis();
            Screen n = new Screen(NOW, null);
            if (from != null && key != null && !key.isEmpty()) {
                n.from = new RectF(from);
                nowCoverRect(nowCover);
                startFly(key, from, nowCover, true);
                textFrom = flyStart;
            } else {
                textFrom = 0;
            }
            stack.add(n);
        }

        private void closeNow() {
            Screen n = stack.remove(stack.size() - 1);
            inQueue = false;
            if (n.from != null && qi >= 0 && qi < queue.size()) {
                nowCoverRect(nowCover);
                startFly(queue.get(qi).cover, nowCover, n.from, false);
            }
        }

        private void toggleShuffle() {
            if (qi < 0 || qi >= queue.size()) return;
            if (!shuffled) {
                unshuffled = queue;
                List<Integer> positions = new ArrayList<>();
                for (int i = 0; i < queue.size(); i++) positions.add(i);
                List<Integer> rest = new ArrayList<>(positions.subList(qi + 1, positions.size()));
                java.util.Collections.shuffle(rest);
                order = new ArrayList<>(positions.subList(0, qi + 1));
                order.addAll(rest);
                List<MusicLibrary.Track> next = new ArrayList<>();
                for (int at : order) next.add(unshuffled.get(at));
                queue = next;
                shuffled = true;
            } else {
                if (unshuffled != null && order != null) {
                    qi = order.get(qi);
                    queue = unshuffled;
                }
                shuffled = false;
                unshuffled = null;
                order = null;
            }
            queueFocus = 0;
            queueScroll = 0;
        }

        private void coverBox(Screen s, RectF out) {
            float cover = s.type == ALBUM ? 380 : 320;
            out.set(112 * k, 262 * k, (112 + cover) * k, (262 + cover) * k);
        }

        private void drawNow(Canvas c) {
            if (qi < 0 || qi >= queue.size()) return;
            if (inQueue && remaining() == 0) inQueue = false;
            MusicLibrary.Track t = queue.get(qi);
            cover(t.cover, true);
            nowCoverRect(rect);
            if (!(flyKey != null && flyIn)) {
                fill.setColor(0x66000000);
                tmp.set(rect.left, rect.top + 18 * k, rect.right, rect.bottom + 18 * k);
                c.drawRoundRect(tmp, 26 * k, 26 * k, fill);
                drawCover(c, t.cover, true, rect, 24 * k, false);
            }
            float textA = textAlpha();
            int saved = textA < 1f ? c.saveLayerAlpha(NOW_X * k - 20 * k, 0, w, h, Math.round(textA * 255)) : -1;
            float x = NOW_X * k, right = NOW_RIGHT * k;
            float open = remaining() > 0 ? 1f - plateAlpha() : 1f, wide = (NOW_RIGHT + (UP_RIGHT - NOW_RIGHT) * open) * k;
            rowValue.setTextAlign(Paint.Align.LEFT);
            label.setColor(MUTE);
            c.drawText("NOW PLAYING", x, 290 * k, label);
            c.drawText(fit(t.title, big, wide - x), x - Ui.leftBearing(big, t.title), 372 * k, big);
            c.drawText(fit(t.artist, bigSub, wide - x), x, 426 * k, bigSub);
            tileSub.setTextSize(Ui.LABEL * k);
            String album = t.album + (t.year > 0 ? (t.album.isEmpty() ? "" : " · ") + t.year : "");
            if (!album.isEmpty()) c.drawText(fit(album, tileSub, wide - x), x, 470 * k, tileSub);
            int position = 0, duration = t.seconds * 1000;
            try {
                if (player != null) {
                    position = player.getCurrentPosition();
                    int d = player.getDuration();
                    if (d > 0) duration = d;
                }
            } catch (IllegalStateException ignored) {
            }
            float barTop = 540 * k;
            fill.setColor(0x33FFFFFF);
            tmp.set(x, barTop, right, barTop + 6 * k);
            c.drawRoundRect(tmp, 3 * k, 3 * k, fill);
            if (duration > 0) {
                fill.setColor(INK);
                tmp.set(x, barTop, x + (right - x) * Math.min(1f, position / (float) duration), barTop + 6 * k);
                c.drawRoundRect(tmp, 3 * k, 3 * k, fill);
            }
            c.drawText(time(position / 1000), x, barTop + 50 * k, tileSub);
            tileSub.setTextAlign(Paint.Align.RIGHT);
            c.drawText(time(duration / 1000), right, barTop + 50 * k, tileSub);
            tileSub.setTextAlign(Paint.Align.LEFT);
            tileSub.setTextSize(24 * k);
            float cy = 690 * k;
            drawButton(c, 0, x + 40 * k, cy, 40 * k, false);
            drawButton(c, 1, x + 160 * k, cy, 52 * k, true);
            drawButton(c, 2, x + 280 * k, cy, 40 * k, false);
            drawButton(c, 3, x + 408 * k, cy, 40 * k, false);
            drawUpNext(c);
            if (saved >= 0) c.restoreToCount(saved);
        }

        private void drawUpNext(Canvas c) {
            int left = remaining();
            float alpha = plateAlpha();
            if (left == 0 || alpha <= 0f) return;
            int saved = alpha < 1f ? c.saveLayerAlpha(UP_LEFT * k - 20 * k, UP_TOP * k - 20 * k, UP_RIGHT * k + 20 * k, UP_BOTTOM * k + 20 * k, Math.round(alpha * 255)) : -1;
            int rows = Math.min(upRows(), left);
            float bottom = UP_TOP + 2 * Glass.PLATE_INSET + UP_CAPTION + rows * (Glass.ROW_H + Glass.ROW_GAP) - Glass.ROW_GAP;
            tmp.set(UP_LEFT * k, UP_TOP * k, UP_RIGHT * k, bottom * k);
            plate(c, tmp);
            label.setColor(MUTE);
            label.setTextSize(18 * k);
            c.drawText("UP NEXT", (UP_LEFT + 28) * k, (UP_TOP + Glass.PLATE_INSET + 30) * k, label);
            label.setTextSize(Ui.TAG * k);
            rowTitle.setTextSize(Ui.LABEL * k);
            rowValue.setTextSize(Ui.CAPTION * k);
            rowValue.setTextAlign(Paint.Align.RIGHT);
            for (int i = 0; i < rows; i++) {
                int at = queueScroll + i;
                if (at >= left) break;
                MusicLibrary.Track t = queue.get(qi + 1 + at);
                float y = UP_TOP + Glass.PLATE_INSET + UP_CAPTION + i * (Glass.ROW_H + Glass.ROW_GAP);
                rect.set((UP_LEFT + Glass.PLATE_INSET) * k, y * k, (UP_RIGHT - Glass.PLATE_INSET) * k, (y + Glass.ROW_H) * k);
                String d = time(t.seconds);
                float right = rect.right - 20 * k;
                c.drawText(d, right, rect.centerY() + Ui.halfCap(rowValue), rowValue);
                rowTitle.setColor(at < 3 ? INK : MUTE);
                c.drawText(fit(t.title, rowTitle, right - rowValue.measureText(d) - 24 * k - rect.left - 20 * k), rect.left + 20 * k, rect.centerY() + Ui.halfCap(rowTitle), rowTitle);
                if (inQueue && at == queueFocus) focusRing.row(c, rect, "queue", k, fill, stroke);
            }
            rowTitle.setColor(INK);
            rowTitle.setTextSize(Ui.BODY * k);
            rowValue.setTextSize(Ui.LABEL * k);
            if (saved >= 0) c.restoreToCount(saved);
        }

        private void drawButton(Canvas c, int index, float cx, float cy, float r, boolean primary) {
            fill.setColor(primary ? INK : 0x1AFFFFFF);
            c.drawCircle(cx, cy, r, fill);
            if (!primary) {
                stroke.setStrokeWidth(1.5f * k);
                stroke.setColor(0x26FFFFFF);
                c.drawCircle(cx, cy, r, stroke);
            }
            if (index == 3) {
                Icons.draw(getContext(), c, R.drawable.icon_shuffle, cx, cy, r * 0.8f, shuffled ? INK : MUTE);
                if (shuffled) {
                    fill.setColor(INK);
                    c.drawCircle(cx, cy + r + 14 * k, 4 * k, fill);
                }
            } else {
                int icon = index == 0 ? R.drawable.icon_previous : index == 2 ? R.drawable.icon_next : paused ? R.drawable.icon_play : R.drawable.icon_pause;
                Icons.draw(getContext(), c, icon, cx, cy, r * 0.9f, primary ? DARK : INK);
            }
            if (index == nowFocus && !inQueue) {
                tmp.set(cx - r, cy - r, cx + r, cy + r);
                RectF at = focusRing.place(tmp, r, "controls", k);
                float rx = at.centerX(), ry = at.centerY(), rr = at.height() / 2;
                stroke.setStrokeWidth(6 * k);
                stroke.setColor(0xFF0E0E0E);
                c.drawCircle(rx, ry, rr + 5 * k, stroke);
                stroke.setStrokeWidth(3 * k);
                stroke.setColor(INK);
                c.drawCircle(rx, ry, rr + 9 * k, stroke);
            }
        }

        private void ring(Canvas c, RectF box, float radius, Object key) {
            if (!ghosting) focusRing.ring(c, box, radius, key, k, stroke);
        }

        private void ringOval(Canvas c, RectF box, Object key) {
            if (!ghosting) focusRing.oval(c, box, key, k, stroke);
        }

        private void ringRow(Canvas c, RectF box, Object key) {
            if (!ghosting) focusRing.row(c, box, key, k, fill, stroke);
        }

        private float eased(long since) {
            if (since < 0) return 1f;
            float t = Ui.clamp((SystemClock.uptimeMillis() - since) / (float) Reveal.MS);
            return 1f - (1f - t) * (1f - t) * (1f - t);
        }

        private void drawBody(Canvas c, Screen s, long now) {
            header.drawScreen(c, hero(s), Ui.clock(now), Ui.meridiem(now), Ui.date(now), "MUSIC");
            if (s.type == ROOT) drawRoot(c);
            else if (s.type == ARTIST) drawArtist(c, s);
            else drawTracks(c, s);
        }

        private void drawZoom(Canvas c, Screen front, Screen behind, float p, long now) {
            if (behind != null) {
                float alpha = 1f - Ui.clamp(p / 0.7f);
                if (alpha > 0.01f) {
                    ghosting = true;
                    int layer = c.saveLayerAlpha(0, 0, w, h, Math.round(255 * alpha));
                    float scale = 1f + 0.04f * p;
                    c.scale(scale, scale, front.ox, front.oy);
                    drawBody(c, behind, now);
                    c.restoreToCount(layer);
                    ghosting = false;
                }
            }
            float text = Ui.clamp((p - 0.3f) / 0.7f);
            int layer = c.saveLayerAlpha(0, 0, w, h, Math.round(255 * text));
            float scale = 0.96f + 0.04f * text;
            c.scale(scale, scale, front.ox, front.oy);
            c.translate(0, 24 * k * (1 - text));
            drawBody(c, front, now);
            c.restoreToCount(layer);
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
            Screen s = new Screen(type, ref);
            s.at = SystemClock.uptimeMillis();
            if (focusRing.current(tmp)) {
                s.ox = tmp.centerX();
                s.oy = tmp.centerY();
            } else {
                s.ox = w / 2f;
                s.oy = h / 2f;
            }
            stack.add(s);
        }

        private void pop() {
            leaving = stack.remove(stack.size() - 1);
            leaving.at = SystemClock.uptimeMillis();
        }

        private void select() {
            Screen s = top();
            if (s.type == NOW) {
                if (inQueue) {
                    if (queueFocus < remaining()) {
                        start(qi + 1 + queueFocus);
                        queueFocus = 0;
                        queueScroll = 0;
                        if (remaining() == 0) inQueue = false;
                    }
                    return;
                }
                if (nowFocus == 0 && qi > 0) start(qi - 1);
                else if (nowFocus == 2 && qi + 1 < queue.size()) start(qi + 1);
                else if (nowFocus == 1) toggle();
                else if (nowFocus == 3) toggleShuffle();
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
                        openNow(null, null);
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
                        openNow(null, null);
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
            coverBox(s, tmp);
            if (playing(list, s.focus)) {
                openNow(tmp, queue.get(qi).cover);
                return;
            }
            String name = s.type == ALBUM ? "Next in this album" : "Next in this playlist";
            play(list, s.focus, name);
            openNow(tmp, qi >= 0 && qi < queue.size() ? queue.get(qi).cover : null);
        }

        private void move(int dx, int dy) {
            focusRing.pressed(dx, dy);
            Screen s = top();
            if (s.type == NOW) {
                if (inQueue) {
                    if (dx < 0) {
                        inQueue = false;
                        nowFocus = 3;
                    } else if (dy != 0) {
                        queueFocus = Math.max(0, Math.min(remaining() - 1, queueFocus + dy));
                        if (queueFocus < queueScroll) queueScroll = queueFocus;
                        if (queueFocus >= queueScroll + upRows()) queueScroll = queueFocus - upRows() + 1;
                    }
                    return;
                }
                if (dx > 0 && nowFocus == 3 && remaining() > 0) {
                    inQueue = true;
                    queueFocus = Math.min(queueFocus, remaining() - 1);
                    if (queueFocus < queueScroll || queueFocus >= queueScroll + upRows()) queueScroll = queueFocus;
                    return;
                }
                nowFocus = Math.max(0, Math.min(3, nowFocus + dx));
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
                        slideTab(Math.min(TABS.length - 1, tab + 1), 1);
                        inAlbums = false;
                    }
                } else if (dx < 0) {
                    if (shuffleAllFocused) shuffleAllFocused = false;
                    else {
                        slideTab(Math.max(0, tab - 1), -1);
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

        private boolean navigation(int code) {
            return code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                    || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        }

        private boolean onTracks() {
            Screen s = top();
            return library != null && stack.size() > 1 && (s.type == ALBUM || s.type == PLAYLIST) && s.focus < tracksOf(s).size();
        }

        private boolean menuAnchor(RectF out) {
            Screen s = top();
            if (s.type != ALBUM && s.type != PLAYLIST) return false;
            rowBox(s.focus - s.scroll, (s.type == ALBUM ? 600 : 520) + Glass.PLATE_INSET, 1808 - Glass.PLATE_INSET, 262, out);
            return true;
        }

        private void openMenu() {
            Screen s = top();
            menuTrack = tracksOf(s).get(s.focus);
            menu.open(ContinueMenu.PLAY, ContinueMenu.DELETE);
        }

        private void runMenu() {
            Screen s = top();
            int action = menu.choose();
            if (action == ContinueMenu.PLAY) {
                select();
            } else if (menuTrack.rev <= 0) {
                deleting.notice(HubDelete.OLD_HUB);
            } else if (playing(tracksOf(s), s.focus)) {
                deleting.notice("It is playing now.");
            } else {
                deleting.open("Delete \u201c" + menuTrack.title + "\u201d?", (menuTrack.artist.isEmpty() ? "" : menuTrack.artist + " \u00b7 ") + time(menuTrack.seconds),
                        "This deletes the file on the hub. If that folder has recovery set up in arca you can restore it there; otherwise it is gone.");
            }
        }

        private void deleteConfirmed() {
            deleting.working();
            MusicLibrary.Track gone = menuTrack;
            List<HubDelete.Target> targets = new ArrayList<>();
            targets.add(new HubDelete.Target(gone.path, gone.rev));
            HubDelete.file(getContext(), ArcaHub.volume(getContext(), "music"), targets, (removed, error) -> {
                if (!removed.isEmpty() && library != null) {
                    library.remove(gone);
                    recent = library.recent(getContext());
                    Screen s = top();
                    if ((s.type == ALBUM || s.type == PLAYLIST) && tracksOf(s).isEmpty()) pop();
                    else if (s.type == ALBUM || s.type == PLAYLIST) s.focus = Math.min(s.focus, tracksOf(s).size() - 1);
                }
                deleting.notice(error != null ? error : "Deleted \u201c" + gone.title + "\u201d");
                invalidate();
            });
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
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            if (deleting.isOpen()) return deleting.onKey(e);
            if (menu.isOpen()) return menuKey(e);
            if (!down && e.getKeyCode() == wakeKey) {
                wakeKey = -1;
                return true;
            }
            if (inQueue && remaining() == 0) inQueue = false;
            if (down && e.getRepeatCount() == 0 && navigation(e.getKeyCode()) && top().type == NOW && remaining() > 0 && plateAlpha() <= 0f) {
                lastInput = SystemClock.uptimeMillis();
                wakeKey = e.getKeyCode();
                invalidate();
                return true;
            }
            if (down) lastInput = SystemClock.uptimeMillis();
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
                        if (e.getRepeatCount() == 0) {
                            okDown = true;
                            longDone = false;
                        } else if (e.getRepeatCount() == 1 && okDown && !longDone && onTracks()) {
                            longDone = true;
                            okDown = false;
                            openMenu();
                        }
                    } else {
                        if (okDown && !longDone && !e.isCanceled()) select();
                        okDown = false;
                    }
                    break;
                case KeyEvent.KEYCODE_BACK:
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled()) {
                            if (top().type == NOW && inQueue) {
                                inQueue = false;
                                nowFocus = 3;
                            } else if (top().type == NOW && stack.size() > 1) closeNow();
                            else if (stack.size() == 1 && tab == TAB_ARTISTS && inAlbums) inAlbums = false;
                            else if (stack.size() == 1 && !tabsFocused && rootCount() > 0) tabsFocused = true;
                            else if (stack.size() > 1) pop();
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
