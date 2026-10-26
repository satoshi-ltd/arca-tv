package com.satoshilimited.tv;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.media3.common.text.Cue;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VideoPlayerActivity extends Activity {
    static final String ID = "id", FROM_START = "fromStart";

    private FrameLayout root;
    private SurfaceView surface;
    private Overlay overlay;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        overlay = new Overlay(this, surface);
        root.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        if (!overlay.open(getIntent().getStringExtra(ID), getIntent().getBooleanExtra(FROM_START, false))) finish();
    }

    @Override protected void onStart() {
        super.onStart();
        overlay.start();
    }

    @Override protected void onPause() {
        overlay.pausing();
        super.onPause();
    }

    @Override protected void onStop() {
        overlay.stop();
        super.onStop();
    }

    @Override protected void onDestroy() {
        overlay.destroy();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (overlay.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override public void onBackPressed() {
        if (!overlay.back()) super.onBackPressed();
    }

    static final class Overlay extends View implements VideoPlayer.Listener {
        private static final int INK = 0xFFF2F2F2, MUTE = 0xB8FFFFFF, DARK = 0xFF141414;
        private static final int B_BACK = 0, B_PLAY = 1, B_FWD = 2, B_AUDIO = 3, B_SUBS = 4, B_BAR = 5;
        private static final long SAVE_MS = 10_000, HIDE_MS = 5000, UP_NEXT_SECONDS = 30, SEEK_MS = 10_000;
        private static final int PANEL_ROWS = 9;

        private final VideoPlayerActivity activity;
        private final SurfaceView surface;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final ExecutorService io = Executors.newSingleThreadExecutor();
        private final SharedPreferences ui;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG), pic = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF rect = new RectF(), tmp = new RectF();
        private Paint title, subtitle, time, metaText, metaBadge, clockBig, clockAm, chip, small, chipLabel, messagePaint, panelHead, panelRow, panelValue, upLabel, upTitle, upButton;
        private TextPaint cuePaint;
        private StaticLayout cueLayout;
        private List<Cue> bitmapCues = new ArrayList<>();
        private final Paint spinner = new Paint(Paint.ANTI_ALIAS_FLAG);

        private VideoLibrary library;
        private VideoLibrary.Item item;
        private VideoPlayer player;
        private long resumeMs, lastInput, savedAt, lastSavedMs = -1;
        private boolean fromStart, firstFrame, buffering = true, failed, active, started, ended, upCancelled, upFocused, upSeen;
        private int failure, focus = B_PLAY, upFocus, panel = -1, panelFocus, panelScroll;
        private int w, h;
        private float k = 1;
        private boolean scrubbing;
        private int scrubDir;
        private long scrubTarget, scrubOrigin, scrubLastEvent, scrubLastStep;
        private int scrubSteps;
        private LinearGradient scrim, topScrim;
        private List<VideoPlayer.Track> audioTracks = new ArrayList<>(), textTracks = new ArrayList<>();
        private final List<Object[]> panelItems = new ArrayList<>();

        private final Runnable tick = new Runnable() {
            @Override public void run() {
                if (!active) return;
                if (player != null && started && !failed) {
                    long now = SystemClock.uptimeMillis();
                    if (player.playing() && now - savedAt >= SAVE_MS) save(false);
                    checkUpNext();
                    if (scrubbing && SystemClock.uptimeMillis() - scrubLastEvent > 1500) endScrub();
                }
                invalidate();
                handler.postDelayed(this, controlsShown() || upVisible() ? 250 : 1000);
            }
        };

        Overlay(VideoPlayerActivity a, SurfaceView surface) {
            super(a);
            activity = a;
            this.surface = surface;
            ui = a.getSharedPreferences("video-ui", Context.MODE_PRIVATE);
            stroke.setStyle(Paint.Style.STROKE);
            setFocusable(false);
            lastInput = SystemClock.uptimeMillis();
        }

        private String pendingId;
        private boolean loadingLibrary;

        boolean open(String id, boolean restart) {
            if (id == null) return false;
            pendingId = id;
            fromStart = restart;
            VideoLibrary lib = VideoLibrary.current();
            return lib == null || resolve(lib);
        }

        private boolean resolve(VideoLibrary lib) {
            library = lib;
            item = lib.find(pendingId);
            if (item == null || !item.playable()) return false;
            resumeMs = fromStart || !item.resumable() ? 0 : (long) (item.position * 1000);
            return true;
        }

        private void loadLibrary() {
            if (loadingLibrary) return;
            loadingLibrary = true;
            Context c = getContext().getApplicationContext();
            io.execute(() -> {
                VideoLibrary lib = VideoLibrary.cached(c);
                handler.post(() -> {
                    loadingLibrary = false;
                    if (lib == null || !resolve(lib)) activity.finish();
                    else if (active) start();
                });
            });
        }

        void pausing() {
            if (player != null && item != null && started) save(true);
        }

        void start() {
            active = true;
            if (item == null) {
                if (pendingId != null && library == null) loadLibrary();
                return;
            }
            ensurePlayer();
            play(resumeMs);
            handler.post(tick);
        }

        void stop() {
            active = false;
            scrubbing = false;
            handler.removeCallbacks(tick);
            if (player != null && item != null && started) {
                resumeMs = player.position();
                save(true);
            }
            if (player != null) {
                player.release();
                player = null;
            }
            started = false;
        }

        void destroy() {
            io.shutdown();
        }

        private void ensurePlayer() {
            if (player != null) return;
            player = new VideoPlayer(getContext(), surface, this);
            firstFrame = false;
            buffering = true;
            failed = false;
        }

        private String scope(VideoLibrary.Item v) {
            return v.isEpisode() ? v.show : v.id;
        }

        private void play(long startMs) {
            if (player == null || item == null) return;
            failed = false;
            ended = false;
            upCancelled = false;
            upFocused = false;
            upSeen = false;
            started = true;
            lastSavedMs = -1;
            savedAt = SystemClock.uptimeMillis();
            firstFrame = false;
            buffering = true;
            cueLayout = null;
            bitmapCues = new ArrayList<>();
            panel = -1;
            player.play(item, startMs, ui.getString("audio:" + scope(item), ""), ui.getString("subs:" + scope(item), ""));
            lastInput = SystemClock.uptimeMillis();
            invalidate();
        }

        private void playItem(VideoLibrary.Item next, long startMs) {
            VideoLibrary.Item previous = item;
            boolean finished = previous != null && player != null && upVisible();
            double total = previous == null ? 0 : previous.duration > 0 ? previous.duration : player == null ? 0 : player.duration() / 1000.0;
            if (previous != null && started) save(true);
            if (finished) markFinished(previous, total);
            item = next;
            resumeMs = startMs;
            play(startMs);
        }

        private long startOf(VideoLibrary.Item v) {
            return v.resumable() ? (long) (v.position * 1000) : 0;
        }

        private void save(boolean force) {
            if (player == null || item == null || !started) return;
            long position = player.position(), duration = player.duration();
            double seconds = position / 1000.0, total = duration > 0 ? duration / 1000.0 : item.duration;
            savedAt = SystemClock.uptimeMillis();
            if (!force && Math.abs(position - lastSavedMs) < 1000) return;
            lastSavedMs = position;
            VideoLibrary.Item v = item;
            VideoLibrary lib = library;
            Context c = getContext().getApplicationContext();
            io.execute(() -> {
                try {
                    JSONObject body = new JSONObject().put("volume", lib.volume).put("path", v.path).put("hash", v.hash)
                            .put("position", Math.round(seconds)).put("duration", Math.round(total));
                    JSONObject reply = new JSONObject(ArcaHub.post(c, "/v1/video/position", body));
                    handler.post(() -> lib.progressed(v, seconds, total, reply.optBoolean("finished")));
                } catch (Exception e) {
                    AppLog.w("arca video position: " + e.getClass().getSimpleName());
                }
            });
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            scrim = new LinearGradient(0, h - 460 * k, 0, h, 0x00000000, 0xD9000000, Shader.TileMode.CLAMP);
            topScrim = new LinearGradient(0, 0, 0, 380 * k, 0xD1000000, 0x00000000, Shader.TileMode.CLAMP);
            title = Ui.text(Ui.light, 56 * k, INK);
            subtitle = Ui.text(Ui.regular, 26 * k, MUTE);
            time = Ui.numbers(Ui.regular, 24 * k, MUTE);
            metaText = Ui.text(Ui.regular, 24 * k, MUTE);
            metaBadge = Ui.text(Ui.semibold, 18 * k, INK);
            clockBig = Ui.text(Ui.light, 56 * k, INK);
            clockBig.setLetterSpacing(-0.035f);
            clockAm = Ui.text(Ui.regular, 24 * k, MUTE);
            chip = Ui.text(Ui.medium, 26 * k, INK);
            small = Ui.text(Ui.medium, 24 * k, INK);
            small.setTextAlign(Paint.Align.CENTER);
            chipLabel = Ui.text(Ui.medium, 26 * k, INK);
            messagePaint = Ui.text(Ui.medium, 30 * k, MUTE);
            messagePaint.setTextAlign(Paint.Align.CENTER);
            panelHead = Ui.text(Ui.semibold, 22 * k, MUTE);
            panelHead.setLetterSpacing(0.11f);
            panelRow = Ui.text(Ui.medium, 28 * k, INK);
            panelValue = Ui.text(Ui.regular, 22 * k, MUTE);
            panelValue.setTextAlign(Paint.Align.RIGHT);
            upLabel = Ui.text(Ui.semibold, 20 * k, MUTE);
            upLabel.setLetterSpacing(0.1f);
            upTitle = Ui.text(Ui.medium, 28 * k, INK);
            upButton = Ui.text(Ui.medium, 24 * k, INK);
            upButton.setTextAlign(Paint.Align.CENTER);
            spinner.setStyle(Paint.Style.STROKE);
            spinner.setStrokeWidth(4 * k);
            spinner.setColor(MUTE);
            cuePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            cuePaint.setTypeface(Ui.medium);
            cuePaint.setTextSize(44 * k);
            cuePaint.setColor(0xFFFFFFFF);
            cuePaint.setShadowLayer(7 * k, 0, 2 * k, 0xFF000000);
            layoutSurface();
        }

        private float ratio = 16f / 9;

        private void layoutSurface() {
            if (w == 0 || h == 0) return;
            int sw = w, sh = h;
            if (ratio > (float) w / h) sh = Math.round(w / ratio);
            else sw = Math.round(h * ratio);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surface.getLayoutParams();
            if (lp.width != sw || lp.height != sh) {
                lp.width = sw;
                lp.height = sh;
                surface.setLayoutParams(lp);
            }
        }

        @Override public void onRatio(float r) {
            handler.post(() -> {
                ratio = r;
                layoutSurface();
            });
        }

        @Override public void onFirstFrame() {
            handler.post(() -> {
                firstFrame = true;
                buffering = false;
                invalidate();
            });
        }

        @Override public void onBuffering(boolean b) {
            handler.post(() -> {
                buffering = b;
                invalidate();
            });
        }

        @Override public void onEnded() {
            handler.post(() -> {
                if (!active || player == null || item == null || ended) return;
                ended = true;
                double total = item.duration > 0 ? item.duration : player == null ? 0 : player.duration() / 1000.0;
                markFinished(item, total);
                VideoLibrary.Item next = item.isEpisode() ? library.next(item) : null;
                if (next != null && next.playable() && !upCancelled) {
                    playItem(next, startOf(next));
                } else {
                    activity.finish();
                }
            });
        }

        private void markFinished(VideoLibrary.Item v, double total) {
            Context c = getContext().getApplicationContext();
            VideoLibrary lib = library;
            io.execute(() -> {
                try {
                    if (Math.round(total) <= 0) {
                        ArcaHub.post(c, "/v1/video/watched", new JSONObject().put("volume", lib.volume).put("path", v.path).put("hash", v.hash).put("watched", true));
                    } else {
                        JSONObject body = new JSONObject().put("volume", lib.volume).put("path", v.path).put("hash", v.hash)
                                .put("position", Math.round(total)).put("duration", Math.round(total));
                        ArcaHub.post(c, "/v1/video/position", body);
                    }
                    handler.post(() -> lib.progressed(v, total, total, true));
                } catch (Exception e) {
                    AppLog.w("arca video finish: " + e.getMessage());
                }
            });
        }

        @Override public void onFailed(int code) {
            handler.post(() -> {
                failed = true;
                failure = code;
                buffering = false;
                if (code == 404) refreshLibrary();
                invalidate();
            });
        }

        private void refreshLibrary() {
            Context c = getContext().getApplicationContext();
            io.execute(() -> {
                try {
                    VideoLibrary.forget(c);
                    VideoLibrary.fetch(c);
                } catch (Exception | OutOfMemoryError e) {
                    AppLog.w("arca video library: " + e.getClass().getSimpleName());
                }
            });
        }

        @Override public void onTracks() {
            handler.post(() -> {
                if (player == null) return;
                audioTracks = player.audioTracks();
                textTracks = player.textTracks();
                invalidate();
            });
        }

        @Override public void onCues(List<Cue> cues) {
            handler.post(() -> {
                StringBuilder text = new StringBuilder();
                List<Cue> images = new ArrayList<>();
                for (Cue cue : cues) {
                    if (cue.bitmap != null) images.add(cue);
                    else if (cue.text != null && cue.text.length() > 0) {
                        if (text.length() > 0) text.append('\n');
                        text.append(cue.text);
                    }
                }
                bitmapCues = images;
                cueLayout = text.length() == 0 || w == 0 ? null : StaticLayout.Builder.obtain(text, 0, text.length(), cuePaint, (int) (1500 * k))
                        .setAlignment(Layout.Alignment.ALIGN_CENTER).build();
                invalidate();
            });
        }

        private boolean controlsShown() {
            if (failed || player == null) return true;
            return panel >= 0 || scrubbing || !player.playing() && firstFrame || SystemClock.uptimeMillis() - lastInput < HIDE_MS;
        }

        private boolean upVisible() {
            if (player == null || item == null || !item.isEpisode() || upCancelled || ended || failed) return false;
            long d = player.duration();
            if (d <= 0 || library.next(item) == null) return false;
            long left = (d - player.position()) / 1000;
            return left <= UP_NEXT_SECONDS && left >= 0;
        }

        private void checkUpNext() {
            if (!upVisible()) {
                upSeen = false;
                upFocused = false;
            }
        }

        private void poke() {
            lastInput = SystemClock.uptimeMillis();
            invalidate();
        }

        boolean back() {
            if (panel >= 0) {
                panel = -1;
                poke();
                return true;
            }
            if (upFocused && upVisible()) {
                upCancelled = true;
                upFocused = false;
                poke();
                return true;
            }
            return false;
        }

        boolean onKey(KeyEvent e) {
            int code = e.getKeyCode();
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) return false;
            if (e.getAction() != KeyEvent.ACTION_DOWN) {
                if (scrubbing && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_MEDIA_REWIND || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)) {
                    endScrub();
                    return true;
                }
                return isOurs(code);
            }
            if (failed) {
                if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                    if (failure == 404 || failure == 415) {
                        activity.finish();
                    } else {
                        stop();
                        start();
                    }
                    return true;
                }
                return false;
            }
            if (player == null) return false;
            boolean repeat = e.getRepeatCount() > 0;
            if (panel >= 0) return panelKey(code);
            if (upFocused && upVisible()) {
                switch (code) {
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        upFocus = 0;
                        break;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        upFocus = 1;
                        break;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                    case KeyEvent.KEYCODE_DPAD_UP:
                        upFocused = false;
                        focus = B_PLAY;
                        break;
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                        if (upFocus == 0) {
                            VideoLibrary.Item next = library.next(item);
                            if (next != null) playItem(next, startOf(next));
                        } else {
                            upCancelled = true;
                            upFocused = false;
                        }
                        break;
                    case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    case KeyEvent.KEYCODE_MEDIA_PLAY:
                    case KeyEvent.KEYCODE_MEDIA_PAUSE:
                        player.toggle();
                        break;
                    default:
                        return false;
                }
                poke();
                return true;
            }
            boolean shown = controlsShown();
            if (code == KeyEvent.KEYCODE_DPAD_UP && upVisible() && !upFocused) {
                upFocused = true;
                upFocus = 0;
                poke();
                return true;
            }
            switch (code) {
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    if (repeat) return true;
                    player.toggle();
                    break;
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                    player.resume();
                    break;
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    player.pause();
                    save(true);
                    break;
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                    scrub(-1, repeat);
                    break;
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    scrub(1, repeat);
                    break;
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                case KeyEvent.KEYCODE_CHANNEL_UP: {
                    VideoLibrary.Item next = item.isEpisode() ? library.next(item) : null;
                    if (next != null && next.playable()) playItem(next, startOf(next));
                    break;
                }
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (repeat) return true;
                    if (!shown) {
                        player.toggle();
                        if (!player.playing()) save(true);
                    } else {
                        activate();
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (!shown || focus == B_BAR) scrub(-1, repeat);
                    else focus = stepFocus(-1);
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (!shown || focus == B_BAR) scrub(1, repeat);
                    else focus = stepFocus(1);
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (!shown) focus = B_PLAY;
                    else if (focus == B_BAR && !scrubbing) {
                        lastInput = 0;
                        invalidate();
                        return true;
                    } else focus = B_BAR;
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (!shown) focus = B_PLAY;
                    else if (focus == B_BAR) focus = B_PLAY;
                    break;
                default:
                    return false;
            }
            poke();
            return true;
        }

        private boolean isOurs(int code) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    return true;
                default:
                    return false;
            }
        }

        private static final long[] SCRUB_STEPS = {30_000, 60_000, 120_000, 240_000};
        private static final long SCRUB_FIRST_MS = 700, SCRUB_EVERY_MS = 600;

        private void scrub(int dir, boolean repeat) {
            long now = SystemClock.uptimeMillis(), duration = player.duration(), limit = duration > 0 ? duration - 1000 : Long.MAX_VALUE;
            if (!repeat || !scrubbing || dir != scrubDir) {
                if (scrubbing) endScrub();
                scrubDir = dir;
                scrubOrigin = player.position();
                scrubTarget = Math.max(0, Math.min(limit, scrubOrigin + dir * SEEK_MS));
                scrubLastEvent = scrubLastStep = now;
                scrubSteps = 0;
                scrubbing = true;
            } else {
                long wait = scrubSteps == 0 ? SCRUB_FIRST_MS : SCRUB_EVERY_MS;
                if (now - scrubLastStep >= wait) {
                    long step = SCRUB_STEPS[Math.min(scrubSteps, SCRUB_STEPS.length - 1)];
                    scrubTarget = Math.max(0, Math.min(limit, scrubTarget + dir * step));
                    scrubLastStep = now;
                    scrubSteps++;
                }
                scrubLastEvent = now;
            }
        }

        private void endScrub() {
            if (!scrubbing) return;
            scrubbing = false;
            if (player != null) player.seekTo(scrubTarget);
            invalidate();
        }

        private void activate() {
            switch (focus) {
                case B_BACK:
                    scrub(-1, false);
                    endScrub();
                    break;
                case B_FWD:
                    scrub(1, false);
                    endScrub();
                    break;
                case B_AUDIO:
                    if (hasAudioChoice()) openPanel(true);
                    break;
                case B_SUBS:
                    if (hasSubtitles()) openPanel(false);
                    break;
                default:
                    player.toggle();
                    if (!player.playing()) save(true);
            }
        }

        private boolean hasAudioChoice() {
            return audioTracks.size() >= 2;
        }

        private boolean hasSubtitles() {
            return !textTracks.isEmpty();
        }

        private boolean visibleButton(int b) {
            return b == B_AUDIO ? hasAudioChoice() : b != B_SUBS || hasSubtitles();
        }

        private int stepFocus(int dir) {
            int next = focus + dir;
            while (next >= B_BACK && next <= B_SUBS && !visibleButton(next)) next += dir;
            return next < B_BACK || next > B_SUBS ? focus : next;
        }

        private void openPanel(boolean audio) {
            audioTracks = player.audioTracks();
            textTracks = player.textTracks();
            panelItems.clear();
            if (audio) {
                panelItems.add(new Object[] {"head", "Audio", null});
                for (VideoPlayer.Track t : audioTracks) panelItems.add(new Object[] {"audio", t.label, t});
            } else {
                panelItems.add(new Object[] {"head", "Subtitles", null});
                panelItems.add(new Object[] {"off", "Off", null});
                for (VideoPlayer.Track t : textTracks) panelItems.add(new Object[] {"text", t.label, t});
            }
            panel = audio ? 0 : 1;
            panelFocus = 0;
            for (int i = 0; i < panelItems.size(); i++) {
                Object[] o = panelItems.get(i);
                VideoPlayer.Track t = (VideoPlayer.Track) o[2];
                boolean selected = t != null && t.selected || "off".equals(o[0]) && !player.subtitlesOn();
                if (selected && (audio ? "audio".equals(o[0]) : "text".equals(o[0]) || "off".equals(o[0]))) {
                    panelFocus = i;
                    break;
                }
            }
            if (!focusable(panelFocus)) panelFocus = nextFocusable(panelFocus, 1);
            clampPanelScroll();
        }

        private boolean focusable(int i) {
            return i >= 0 && i < panelItems.size() && !"head".equals(panelItems.get(i)[0]);
        }

        private int nextFocusable(int from, int dir) {
            for (int i = from + dir; i >= 0 && i < panelItems.size(); i += dir) if (focusable(i)) return i;
            return from;
        }

        private void clampPanelScroll() {
            if (panelFocus < panelScroll) panelScroll = panelFocus;
            if (panelFocus >= panelScroll + PANEL_ROWS) panelScroll = panelFocus - PANEL_ROWS + 1;
            if (panelFocus - panelScroll < 1 && panelScroll > 0 && !focusable(panelScroll - 1)) panelScroll--;
            panelScroll = Math.max(0, panelScroll);
        }

        private boolean panelKey(int code) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    panelFocus = nextFocusable(panelFocus, -1);
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    panelFocus = nextFocusable(panelFocus, 1);
                    break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER: {
                    Object[] o = panelItems.get(panelFocus);
                    String scope = scope(item);
                    if ("audio".equals(o[0])) {
                        VideoPlayer.Track t = (VideoPlayer.Track) o[2];
                        player.selectAudio(t);
                        if (!t.language.isEmpty()) ui.edit().putString("audio:" + scope, t.language).apply();
                    } else if ("off".equals(o[0])) {
                        player.selectText(null);
                        ui.edit().remove("subs:" + scope).apply();
                    } else if ("text".equals(o[0])) {
                        VideoPlayer.Track t = (VideoPlayer.Track) o[2];
                        player.selectText(t);
                        if (!t.language.isEmpty()) ui.edit().putString("subs:" + scope, t.language).apply();
                    }
                    panel = -1;
                    break;
                }
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return true;
                default:
                    return false;
            }
            clampPanelScroll();
            poke();
            return true;
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0 || player == null && !failed) return;
            long now = SystemClock.uptimeMillis();
            boolean controls = controlsShown();
            if (!failed && (!firstFrame || buffering)) {
                Ui.spinner(c, w / 2f, h / 2f, 26 * k, now / 1000f * 1.3f, spinner);
                postInvalidateDelayed(50);
            }
            drawCues(c, controls);
            if (failed) {
                drawFailure(c);
                return;
            }
            if (controls || upVisible()) drawScrims(c, controls);
            if (controls && player != null) {
                drawTitle(c);
                drawControls(c);
            }
            if (upVisible() && player != null) drawUpNext(c, controls);
            if (scrubbing) postInvalidateDelayed(60);
            if (panel >= 0) drawPanel(c);
        }

        private void drawScrims(Canvas c, boolean controls) {
            if (!controls) return;
            fill.setShader(scrim);
            c.drawRect(0, h - 460 * k, w, h, fill);
            fill.setShader(topScrim);
            c.drawRect(0, 0, w, 380 * k, fill);
            fill.setShader(null);
        }

        private void drawTitle(Canvas c) {
            VideoLibrary.Item show = item.isEpisode() ? library.showOf(item) : null;
            String line = show != null ? show.title + " · S" + item.season + " · E" + item.episode : item.year > 0 ? String.valueOf(item.year) : "";
            c.drawText(Ui.fit(line, subtitle, 1500 * k), 112 * k, 112 * k, subtitle);
            c.drawText(Ui.fit(item.title, title, 1300 * k), 112 * k, 176 * k, title);
            MetaLine.draw(getContext(), c, metaText, metaBadge, stroke, tmp, k, show != null ? show : item, false, item.minutes(), 112 * k, 228 * k, INK);
            drawClock(c);
        }

        private void drawClock(Canvas c) {
            long now = System.currentTimeMillis(), duration = player.duration(), left = duration > 0 ? duration - player.position() : 0;
            float right = w - 112 * k;
            String am = Ui.meridiem(now);
            c.drawText(am, right - clockAm.measureText(am), 176 * k, clockAm);
            String clock = Ui.clock(now);
            c.drawText(clock, right - clockAm.measureText(am) - 8 * k - clockBig.measureText(clock), 176 * k, clockBig);
            if (left > 0) {
                long end = now + left;
                String ends = "Ends at " + Ui.clock(end) + " " + Ui.meridiem(end);
                c.drawText(ends, right - clockAm.measureText(ends), 228 * k, clockAm);
            }
        }

        private void drawCues(Canvas c, boolean controls) {
            float lift = controls ? 190 * k : 0;
            if (cueLayout != null) {
                c.save();
                c.translate((w - cueLayout.getWidth()) / 2f, h - 90 * k - cueLayout.getHeight() - lift);
                cueLayout.draw(c);
                c.restore();
            }
            for (Cue cue : bitmapCues) {
                Bitmap b = cue.bitmap;
                if (b == null || b.getWidth() == 0) continue;
                float bh = cue.bitmapHeight > 0 ? cue.bitmapHeight * h : b.getHeight() * h / 1080f;
                float bw = bh * b.getWidth() / b.getHeight();
                float x = cue.position != Cue.DIMEN_UNSET ? cue.position * w - bw * (cue.positionAnchor == Cue.ANCHOR_TYPE_MIDDLE ? 0.5f : cue.positionAnchor == Cue.ANCHOR_TYPE_END ? 1f : 0f) : (w - bw) / 2f;
                float y = cue.line != Cue.DIMEN_UNSET ? cue.line * h - bh * (cue.lineAnchor == Cue.ANCHOR_TYPE_END ? 1f : cue.lineAnchor == Cue.ANCHOR_TYPE_MIDDLE ? 0.5f : 0f) : h - bh - 60 * k;
                rect.set(x, y - lift, x + bw, y + bh - lift);
                c.drawBitmap(b, null, rect, pic);
            }
        }

        private void circle(Canvas c, float cx, float cy, float r, boolean primary, boolean focused) {
            rect.set(cx - r, cy - r, cx + r, cy + r);
            if (primary) {
                fill.setColor(INK);
                c.drawCircle(cx, cy, r, fill);
            } else {
                Glass.plate(c, rect, r, k, fill, stroke);
            }
            if (focused) Glass.ringOval(c, rect, k, stroke);
        }

        private void drawControls(Canvas c) {
            long position = scrubbing ? scrubTarget : player.position(), duration = player.duration();
            float left = 112 * k, right = 1808 * k, barY = 876 * k;
            fill.setColor(0x33FFFFFF);
            tmp.set(left, barY, right, barY + 8 * k);
            c.drawRoundRect(tmp, 4 * k, 4 * k, fill);
            float fraction = duration > 0 ? Math.min(1f, position / (float) duration) : 0;
            fill.setColor(INK);
            tmp.set(left, barY, left + (right - left) * fraction, barY + 8 * k);
            c.drawRoundRect(tmp, 4 * k, 4 * k, fill);
            if (scrubbing) drawScrub(c, left, right, barY, duration, position);
            float knob = focus == B_BAR ? 13 : 9;
            c.drawCircle(left + (right - left) * fraction, barY + 4 * k, knob * k, fill);
            if (focus == B_BAR) {
                rect.set(left + (right - left) * fraction - 13 * k, barY + 4 * k - 13 * k, left + (right - left) * fraction + 13 * k, barY + 4 * k + 13 * k);
                Glass.ringOval(c, rect, k, stroke);
            }
            time.setTextAlign(Paint.Align.LEFT);
            c.drawText(Ui.clockTime(position / 1000), left, 930 * k, time);
            time.setTextAlign(Paint.Align.RIGHT);
            c.drawText(duration > 0 ? "−" + Ui.clockTime((duration - position) / 1000) : "", right, 930 * k, time);
            float cy = 1000 * k;
            circle(c, 152 * k, cy, 40 * k, false, focus == B_BACK);
            Icons.draw(getContext(), c, R.drawable.icon_rewind, 152 * k, cy, 40 * k, INK);
            circle(c, 280 * k, cy, 52 * k, true, focus == B_PLAY);
            Icons.draw(getContext(), c, player.playing() ? R.drawable.icon_pause : R.drawable.icon_play, 280 * k, cy, 44 * k, DARK);
            circle(c, 408 * k, cy, 40 * k, false, focus == B_FWD);
            Icons.draw(getContext(), c, R.drawable.icon_forward, 408 * k, cy, 40 * k, INK);
            String audio = selectedLabel(audioTracks, "Audio"), subs = player.subtitlesOn() ? selectedLabel(textTracks, "Subtitles") : "Off";
            float x = right;
            if (hasSubtitles()) x = chip(c, x, cy, R.drawable.icon_subtitles, subs, focus == B_SUBS) - 16 * k;
            if (hasAudioChoice()) chip(c, x, cy, R.drawable.icon_sound, audio, focus == B_AUDIO);
        }

        private void drawScrub(Canvas c, float left, float right, float barY, long duration, long position) {
            if (duration <= 0) return;
            float originX = left + (right - left) * Math.min(1f, scrubOrigin / (float) duration), knobX = left + (right - left) * Math.min(1f, position / (float) duration);
            fill.setColor(0x8CF2F2F2);
            tmp.set(originX - 6 * k, barY - 6 * k, originX + 6 * k, barY + 14 * k);
            c.drawRoundRect(tmp, 6 * k, 6 * k, fill);
            long delta = Math.abs(position - scrubOrigin) / 1000;
            String clock = Ui.clockTime(position / 1000), change = (position >= scrubOrigin ? "+" : "−") + Ui.clockTime(delta);
            float gap = 14 * k, padX = 28 * k, pillPad = 14 * k, height = 64 * k;
            float clockW = chipLabel.measureText(clock), changeW = chipLabel.measureText(change) + 2 * pillPad;
            float width = padX + clockW + gap + changeW + 10 * k;
            float x = Math.max(left, Math.min(right - width, knobX - width / 2)), top = barY - 40 * k - height;
            rect.set(x, top, x + width, top + height);
            Glass.plate(c, rect, height / 2, k, fill, stroke);
            c.drawText(clock, x + padX, top + height / 2 + Ui.halfCap(chipLabel), chipLabel);
            tmp.set(x + padX + clockW + gap, top + 10 * k, x + width - 10 * k, top + height - 10 * k);
            fill.setColor(INK);
            c.drawRoundRect(tmp, tmp.height() / 2, tmp.height() / 2, fill);
            int ink = chipLabel.getColor();
            chipLabel.setColor(DARK);
            c.drawText(change, tmp.left + pillPad, tmp.centerY() + Ui.halfCap(chipLabel), chipLabel);
            chipLabel.setColor(ink);
        }

        private String selectedLabel(List<VideoPlayer.Track> tracks, String fallback) {
            for (VideoPlayer.Track t : tracks) if (t.selected) return t.label + (t.detail.isEmpty() || fallback.equals("Subtitles") ? "" : " " + t.detail.split(" · ")[0]);
            return fallback;
        }

        private float chip(Canvas c, float right, float cy, int icon, String text, boolean focused) {
            String shown = Ui.fit(text, chipLabel, 320 * k);
            float width = 30 * k + 28 * k + 14 * k + chipLabel.measureText(shown) + 30 * k;
            rect.set(right - width, cy - 34 * k, right, cy + 34 * k);
            Glass.plate(c, rect, 34 * k, k, fill, stroke);
            Icons.draw(getContext(), c, icon, rect.left + 30 * k + 14 * k, cy, 28 * k, INK);
            c.drawText(shown, rect.left + 30 * k + 28 * k + 14 * k, cy + Ui.halfCap(chipLabel), chipLabel);
            if (focused) Glass.ring(c, rect, 34 * k, k, stroke);
            return rect.left;
        }

        private void drawUpNext(Canvas c, boolean controls) {
            VideoLibrary.Item next = library.next(item);
            if (next == null) return;
            long left = Math.max(0, (player.duration() - player.position()) / 1000);
            float bottom = controls ? 790 : 940;
            tmp.set(1808 * k - 600 * k, (bottom - 196) * k, 1808 * k, bottom * k);
            Glass.plate(c, tmp, 36 * k, k, fill, stroke);
            float x = tmp.left + 30 * k, y = tmp.top + 26 * k;
            upLabel.setColor(MUTE);
            c.drawText("UP NEXT · IN " + left + " S", x, y + 20 * k, upLabel);
            c.drawText(Ui.fit(next.title.isEmpty() ? "Episode " + next.episode : next.title, upTitle, tmp.width() - 60 * k), x, y + 66 * k, upTitle);
            subtitle.setTextSize(22 * k);
            c.drawText("S" + next.season + " · E" + next.episode + (next.minutes() > 0 ? " · " + next.minutes() + " min" : ""), x, y + 100 * k, subtitle);
            subtitle.setTextSize(26 * k);
            float by = tmp.bottom - 22 * k - 52 * k;
            for (int i = 0; i < 2; i++) {
                float bx = x + i * 200 * k;
                rect.set(bx, by, bx + 180 * k, by + 52 * k);
                boolean focused = upFocused && upFocus == i;
                if (i == 0) {
                    fill.setColor(INK);
                    c.drawRoundRect(rect, 26 * k, 26 * k, fill);
                } else {
                    Glass.plate(c, rect, 26 * k, k, fill, stroke);
                }
                upButton.setColor(i == 0 ? DARK : INK);
                c.drawText(i == 0 ? "Play now" : "Cancel", rect.centerX(), rect.centerY() + Ui.halfCap(upButton), upButton);
                if (focused) Glass.ring(c, rect, 26 * k, k, stroke);
            }
        }

        private void drawPanel(Canvas c) {
            fill.setColor(0x8C0A0A0A);
            c.drawRect(0, 0, w, h, fill);
            float left = 1808 - 640, top = 120;
            int rows = Math.min(PANEL_ROWS, panelItems.size());
            float height = 2 * 26 + rows * 76 + 8;
            tmp.set(left * k, top * k, 1808 * k, (top + height) * k);
            Glass.plate(c, tmp, k, fill, stroke);
            fill.setColor(0xFF1A1A1A);
            fill.setAlpha(190);
            c.drawRoundRect(tmp, 44 * k, 44 * k, fill);
            for (int i = 0; i < rows; i++) {
                int at = panelScroll + i;
                if (at >= panelItems.size()) break;
                Object[] o = panelItems.get(at);
                float y = top + 26 + i * 76;
                rect.set((left + 26) * k, y * k, (1808 - 26) * k, (y + 76) * k);
                if ("head".equals(o[0])) {
                    c.drawText(((String) o[1]).toUpperCase(), rect.left + 24 * k, rect.centerY() + 22 * k, panelHead);
                    continue;
                }
                VideoPlayer.Track t = (VideoPlayer.Track) o[2];
                boolean selected = t != null && t.selected || "off".equals(o[0]) && !player.subtitlesOn();
                if (at == panelFocus) Glass.focus(c, rect, 22 * k, k, fill, stroke);
                float right = rect.right - 24 * k;
                if (selected) {
                    Icons.draw(getContext(), c, R.drawable.icon_check, right - 14 * k, rect.centerY(), 28 * k, INK);
                    right -= 44 * k;
                }
                if (t != null && !t.detail.isEmpty()) {
                    c.drawText(t.detail, right, rect.centerY() + Ui.halfCap(panelValue), panelValue);
                    right -= panelValue.measureText(t.detail) + 24 * k;
                }
                c.drawText(Ui.fit((String) o[1], panelRow, right - rect.left - 48 * k), rect.left + 24 * k, rect.centerY() + Ui.halfCap(panelRow), panelRow);
            }
        }

        private void drawFailure(Canvas c) {
            tmp.set(560 * k, 400 * k, 1360 * k, 640 * k);
            Glass.plate(c, tmp, k, fill, stroke);
            String text = failure == 415 ? "This video's format can't be streamed" : failure == 404 ? "This video changed on the hub" : failure == 403 ? "This TV can't play it" : failure == 503 ? "The hub is busy with another video" : "Couldn't play this video";
            c.drawText(text, 960 * k, 500 * k, messagePaint);
            rect.set(820 * k, 548 * k, 1100 * k, 612 * k);
            Glass.plate(c, rect, 32 * k, k, fill, stroke);
            small.setColor(INK);
            c.drawText(failure == 404 || failure == 415 ? "Close" : "Try again", rect.centerX(), rect.centerY() + Ui.halfCap(small), small);
            Glass.focus(c, rect, 32 * k, k, fill, stroke);
        }
    }
}
