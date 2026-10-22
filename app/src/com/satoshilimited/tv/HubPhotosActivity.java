package com.satoshilimited.tv;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.VideoView;

import java.util.HashMap;
import java.util.Map;


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
            view.video = new VideoView(this);
            root.addView(view.video, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            view.video.setVisibility(View.GONE);
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
        private VideoView video;
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

        void release() {
            if (video != null) video.stopPlayback();
            show.close();
        }

        private void syncVideo() {
            if (!browse || video == null) return;
            boolean isVideo = show.ready() && show.currentVideo();
            String hash = isVideo ? show.currentHash() : "";
            if (hash.equals(videoHash)) return;
            videoHash = hash;
            videoReady = false;
            if (hash.isEmpty()) {
                video.stopPlayback();
                video.setVisibility(GONE);
                return;
            }
            Map<String, String> headers = new HashMap<>();
            headers.put("Authorization", ArcaHub.authorization(getContext()));
            video.setVideoURI(Uri.parse(ArcaHub.url(getContext()) + "/v1/blobs/" + hash), headers);
            video.setOnPreparedListener(MediaPlayer::start);
            video.setOnCompletionListener(mp -> show.step(1));
            video.setOnInfoListener((mp, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    videoReady = true;
                    invalidate();
                }
                return false;
            });
            video.setOnErrorListener((mp, what, extra) -> {
                AppLog.w("arca photo video playback error " + what + "/" + extra);
                show.step(1);
                return true;
            });
            video.setVisibility(VISIBLE);
        }

        @Override protected void onDraw(Canvas c) {
            syncVideo();
            int width = getWidth(), height = getHeight();
            float k = width / 1920f;
            boolean videoActive = !videoHash.isEmpty();
            if (!videoActive || !videoReady) show.draw(c, width, height, false, true);
            if (videoActive && videoReady && video != null) {
                int duration = video.getDuration();
                if (duration > 0) {
                    float p = Math.min(1f, video.getCurrentPosition() / (float) duration);
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
                                if (video.isPlaying()) video.pause();
                                else video.start();
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
