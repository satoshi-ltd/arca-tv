package com.satoshilimited.tv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CameraActivity extends Activity {
    static final String ID = "id";

    private SurfaceView surface;
    private Overlay overlay;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        overlay = new Overlay(this, surface);
        root.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        overlay.select(getIntent().getStringExtra(ID));
    }

    @Override protected void onStart() {
        super.onStart();
        overlay.start();
    }

    @Override protected void onStop() {
        overlay.stop();
        super.onStop();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (overlay.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    static final class Overlay extends View {
        private static final long HIDE_MS = 5_000;
        private static final int INK = 0xFFF2F2F2, DARK = 0xFF141414, RED = 0xFFD0606A;

        private final CameraActivity activity;
        private final SurfaceView surface;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Runnable hide = () -> {
            shown = false;
            invalidate();
        };
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG), shade = new Paint(), picture = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Shader foot = new LinearGradient(0, 0, 0, 1, 0x00000000, 0xC7000000, Shader.TileMode.CLAMP);
        private final Shader head = new LinearGradient(0, 0, 0, 1, 0xC7000000, 0x00000000, Shader.TileMode.CLAMP);
        private final Matrix matrix = new Matrix();
        private final RectF box = new RectF(), source = new RectF();
        private Header scaled;
        private Paint title72, pill24, hint22, message34, detail24;
        private ExoPlayer player;
        private String id = "", name = "", error = "";
        private boolean running, live, buffering, shown = true, okDown, backDown;
        private float ratio = 16f / 9, k = 1;
        private int w, h;

        Overlay(CameraActivity activity, SurfaceView surface) {
            super(activity);
            this.activity = activity;
            this.surface = surface;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            setFocusable(true);
            setContentDescription("arca domo camera");
        }

        void select(String camera) {
            id = camera == null ? "" : camera;
            ArcaDomo.Device d = ArcaDomo.get().camera(id);
            if (d == null) {
                activity.finish();
                return;
            }
            name = d.name;
            live = false;
            buffering = true;
            error = "";
            reveal();
            if (running) open();
        }

        void start() {
            running = true;
            buildPlayer();
            open();
        }

        void stop() {
            running = false;
            handler.removeCallbacks(hide);
            release();
        }

        private void buildPlayer() {
            if (player != null) return;
            Map<String, String> headers = new HashMap<>();
            String auth = ArcaDomo.authorization();
            if (auth != null) headers.put("Authorization", auth);
            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                    .setDefaultRequestProperties(headers)
                    .setConnectTimeoutMs(10_000)
                    .setReadTimeoutMs(40_000);
            DefaultLoadControl buffer = new DefaultLoadControl.Builder().setBufferDurationsMs(2_000, 10_000, 1_000, 1_500).build();
            DefaultRenderersFactory renderers = new DefaultRenderersFactory(getContext())
                    .setEnableDecoderFallback(true)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF);
            player = new ExoPlayer.Builder(getContext(), renderers)
                    .setLoadControl(buffer)
                    .setMediaSourceFactory(new HlsMediaSource.Factory(http))
                    .build();
            player.setVideoSurfaceView(surface);
            player.addListener(new Player.Listener() {
                @Override public void onVideoSizeChanged(VideoSize size) {
                    if (size.width > 0 && size.height > 0) {
                        ratio = size.width * size.pixelWidthHeightRatio / size.height;
                        layoutSurface();
                    }
                }

                @Override public void onRenderedFirstFrame() {
                    live = true;
                    buffering = false;
                    reveal();
                }

                @Override public void onPlaybackStateChanged(int state) {
                    buffering = state == Player.STATE_BUFFERING;
                    invalidate();
                }

                @Override public void onPlayerError(PlaybackException e) {
                    AppLog.w("arca domo camera " + id + " playback error " + e.getErrorCodeName() + ": " + e.getMessage());
                    int code = 0;
                    for (Throwable t = e.getCause(); t != null; t = t.getCause()) {
                        if (t instanceof HttpDataSource.InvalidResponseCodeException) {
                            code = ((HttpDataSource.InvalidResponseCodeException) t).responseCode;
                            break;
                        }
                    }
                    error = code == 503 ? "Too many cameras open" : code == 401 ? "The home server didn't accept this TV" : "The camera didn't start";
                    live = false;
                    buffering = false;
                    shown = true;
                    handler.removeCallbacks(hide);
                    invalidate();
                }
            });
        }

        private void open() {
            if (player == null || id.isEmpty()) return;
            error = "";
            live = false;
            buffering = true;
            player.setMediaItem(new MediaItem.Builder()
                    .setUri(Uri.parse(ArcaDomo.base() + "/api/devices/" + id + "/stream.m3u8"))
                    .setLiveConfiguration(new MediaItem.LiveConfiguration.Builder()
                            .setTargetOffsetMs(3_000)
                            .setMinOffsetMs(2_000)
                            .setMaxOffsetMs(8_000)
                            .setMinPlaybackSpeed(0.97f)
                            .setMaxPlaybackSpeed(1.04f)
                            .build())
                    .build());
            player.prepare();
            player.play();
            invalidate();
        }

        private void release() {
            if (player == null) return;
            player.release();
            player = null;
        }

        private void reveal() {
            shown = true;
            handler.removeCallbacks(hide);
            if (live) handler.postDelayed(hide, HIDE_MS);
            invalidate();
        }

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

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            scaled = new Header(k);
            title72 = Ui.text(Ui.light, 72 * k, INK);
            title72.setLetterSpacing(-0.028f);
            pill24 = Ui.text(Ui.medium, 24 * k, INK);
            hint22 = Ui.text(Ui.regular, 22 * k, 0xD9FFFFFF);
            message34 = Ui.text(Ui.medium, 34 * k, INK);
            message34.setTextAlign(Paint.Align.CENTER);
            detail24 = Ui.text(Ui.regular, 24 * k, 0xB8FFFFFF);
            detail24.setTextAlign(Paint.Align.CENTER);
            layoutSurface();
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            if (!live) {
                Bitmap shot = CameraShots.get().bitmap(id);
                c.drawColor(0xFF000000);
                if (shot != null) {
                    float scale = Math.min((float) w / shot.getWidth(), (float) h / shot.getHeight());
                    float dw = shot.getWidth() * scale, dh = shot.getHeight() * scale;
                    box.set((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2);
                    picture.setAlpha(error.isEmpty() ? 255 : 115);
                    c.drawBitmap(shot, null, box, picture);
                }
            }
            if (shown || !error.isEmpty()) drawChrome(c);
            if (!error.isEmpty()) drawError(c);
            else if (buffering) drawSpinner(c);
            if (running && (buffering || !live)) postInvalidateDelayed(16);
        }

        private void drawChrome(Canvas c) {
            shade.setShader(head);
            matrix.setScale(1, 260 * k);
            head.setLocalMatrix(matrix);
            c.drawRect(0, 0, w, 260 * k, shade);
            shade.setShader(foot);
            matrix.setScale(1, 260 * k);
            matrix.postTranslate(0, h - 260 * k);
            foot.setLocalMatrix(matrix);
            c.drawRect(0, h - 260 * k, w, h, shade);
            scaled.drawLockup(c, "DOMO");
            c.drawText(name, 112 * k - Ui.leftBearing(title72, name), 184 * k, title72);
            float cy = h - 94 * k;
            String status = live ? "Live" : "Connecting";
            float pw = pill24.measureText(status) + (20 + 10 + 10 + 20) * k;
            box.set(112 * k, cy - 22 * k, 112 * k + pw, cy + 22 * k);
            fill.setColor(live ? INK : 0x8C000000);
            c.drawRoundRect(box, 22 * k, 22 * k, fill);
            fill.setColor(live ? RED : 0xB8FFFFFF);
            c.drawCircle(box.left + 25 * k, cy, 5 * k, fill);
            pill24.setColor(live ? DARK : INK);
            c.drawText(status, box.left + 40 * k, cy + Ui.halfCap(pill24), pill24);
            pill24.setColor(INK);
            hint(c, 1808 * k, cy, "Back  Close");
            if (ArcaDomo.get().cameras().size() > 1) hint(c, 1808 * k - hint22.measureText("Back  Close") - 94 * k, cy, "Left / Right  Other camera");
        }

        private void hint(Canvas c, float right, float cy, String text) {
            float width = hint22.measureText(text) + 40 * k;
            box.set(right - width, cy - 22 * k, right, cy + 22 * k);
            fill.setColor(0x8C000000);
            c.drawRoundRect(box, 22 * k, 22 * k, fill);
            c.drawText(text, box.left + 20 * k, cy + Ui.halfCap(hint22), hint22);
        }

        private void drawSpinner(Canvas c) {
            stroke.setStrokeWidth(4 * k);
            stroke.setColor(0xFFF2F2F2);
            Ui.spinner(c, w / 2f, h / 2f, 30 * k, (SystemClock.elapsedRealtime() % 1000) / 1000f, stroke);
        }

        private void drawError(Canvas c) {
            c.drawText(error, w / 2f, h / 2f - 8 * k, message34);
            c.drawText("OK tries again", w / 2f, h / 2f + 40 * k, detail24);
        }

        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (down && e.getRepeatCount() == 0) step(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down && e.getRepeatCount() == 0) step(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down) reveal();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled()) {
                            if (error.isEmpty()) reveal();
                            else {
                                reveal();
                                open();
                            }
                        }
                        okDown = false;
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled()) activity.finish();
                        backDown = false;
                    }
                    return true;
                default:
                    return false;
            }
        }

        private void step(int by) {
            List<ArcaDomo.Device> eyes = ArcaDomo.get().cameras();
            if (eyes.size() < 2) return;
            int at = 0;
            for (int i = 0; i < eyes.size(); i++) if (eyes.get(i).id.equals(id)) at = i;
            select(eyes.get((at + by + eyes.size()) % eyes.size()).id);
        }
    }
}
