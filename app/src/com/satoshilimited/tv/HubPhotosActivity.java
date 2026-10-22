package com.satoshilimited.tv;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

public class HubPhotosActivity extends Activity {
    private PhotosView view;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new PhotosView(this, getIntent().getIntExtra("start", -1));
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        if (view.browse) {
            view.surface = new SurfaceView(this);
            root.addView(view.surface, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            view.surface.setVisibility(View.GONE);
        }
        root.addView(view, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        view.resume();
    }

    @Override protected void onPause() {
        view.pause();
        super.onPause();
    }

    @Override protected void onStop() {
        view.dropVideo();
        super.onStop();
    }

    @Override protected void onDestroy() {
        view.release();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    static final class PhotosView extends View {
        private final HubPhotosActivity activity;
        private final PhotoSlideshow show;
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG), fillBar = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean running, okDown, backDown;
        private final boolean browse;
        private SurfaceView surface;
        private HubVideo video;
        private String videoHash = "";
        private boolean videoReady;

        PhotosView(HubPhotosActivity a, int start) {
            super(a);
            activity = a;
            browse = start >= 0;
            show = browse ? new PhotoSlideshow(new LibrarySource(a, start)) : new PhotoSlideshow(a);
            setFocusable(true);
            text.setColor(0xB8FFFFFF);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(Ui.regular);
        }

        void resume() {
            running = true;
            invalidate();
        }

        void pause() {
            running = false;
            if (video != null) video.pause();
        }

        void dropVideo() {
            if (video != null) video.release();
            video = null;
            videoHash = "";
            videoReady = false;
            if (surface != null) surface.setVisibility(GONE);
        }

        void release() {
            dropVideo();
            show.close();
        }

        private void fit(float ratio) {
            View parent = (View) surface.getParent();
            int pw = parent.getWidth(), ph = parent.getHeight();
            if (pw == 0 || ph == 0) return;
            int w = pw, h = Math.round(pw / ratio);
            if (h > ph) {
                h = ph;
                w = Math.round(ph * ratio);
            }
            surface.setLayoutParams(new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
        }

        private void syncVideo() {
            if (!browse || surface == null) return;
            boolean isVideo = show.ready() && show.currentVideo();
            String hash = isVideo ? show.currentHash() : "";
            if (hash.equals(videoHash)) return;
            videoHash = hash;
            videoReady = false;
            if (video == null && !hash.isEmpty()) video = new HubVideo(getContext(), surface, new HubVideo.Listener() {
                @Override public void onRatio(float ratio) {
                    fit(ratio);
                }

                @Override public void onFirstFrame() {
                    videoReady = true;
                    invalidate();
                }

                @Override public void onEnded() {
                    show.step(1);
                }

                @Override public void onFailed() {
                    show.step(1);
                }
            });
            if (hash.isEmpty()) {
                if (video != null) video.stop();
                surface.setVisibility(GONE);
                return;
            }
            video.play(ArcaHub.url(getContext()) + "/v1/blobs/" + hash);
            surface.setVisibility(VISIBLE);
        }

        @Override protected void onDraw(Canvas c) {
            syncVideo();
            int width = getWidth(), height = getHeight();
            float k = width / 1920f;
            boolean videoActive = !videoHash.isEmpty();
            if (!videoActive || !videoReady) show.draw(c, width, height, false, true);
            if (videoActive && videoReady && video != null) {
                float p = video.progress();
                if (p >= 0f) {
                    fillBar.setColor(0x33FFFFFF);
                    c.drawRect(0, height - 4 * k, width, height, fillBar);
                    fillBar.setColor(0xFFF2F2F2);
                    c.drawRect(0, height - 4 * k, width * p, height, fillBar);
                }
            }
            if (!videoActive) {
                if (show.failed()) {
                    text.setTextSize(30 * k);
                    c.drawText("No photos to show yet.", width / 2f, height / 2f, text);
                }
            }
            if (running) postInvalidateOnAnimation();
        }

        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down) show.step(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (down) show.step(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled()) {
                            if (browse && !videoHash.isEmpty() && video != null) {
                                video.toggle();
                            } else show.togglePause();
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
    }
}
