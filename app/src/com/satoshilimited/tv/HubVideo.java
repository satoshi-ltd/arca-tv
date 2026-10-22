package com.satoshilimited.tv;

import android.content.Context;
import android.net.Uri;
import android.view.SurfaceView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.util.HashMap;
import java.util.Map;

final class HubVideo {
    interface Listener {
        void onRatio(float ratio);
        void onFirstFrame();
        void onEnded();
        void onFailed();
    }

    private static final int MIN_BUFFER_MS = 20000, MAX_BUFFER_MS = 90000, START_MS = 2500, RESUME_MS = 6000;
    private static final int BUFFER_BYTES = 32 * 1024 * 1024;

    private final ExoPlayer player;
    private MediaItem item;

    HubVideo(Context c, SurfaceView surface, Listener listener) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", ArcaHub.authorization(c));
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(headers)
                .setConnectTimeoutMs(10000)
                .setReadTimeoutMs(20000);
        DefaultLoadControl buffering = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, START_MS, RESUME_MS)
                .setTargetBufferBytes(BUFFER_BYTES)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build();
        player = new ExoPlayer.Builder(c)
                .setLoadControl(buffering)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(http))
                .build();
        player.setVideoSurfaceView(surface);
        player.addListener(new Player.Listener() {
            @Override public void onVideoSizeChanged(VideoSize size) {
                if (size.height > 0 && size.width > 0) listener.onRatio(size.width * size.pixelWidthHeightRatio / size.height);
            }

            @Override public void onRenderedFirstFrame() {
                if (item != null && item.equals(player.getCurrentMediaItem())) listener.onFirstFrame();
            }

            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) listener.onEnded();
            }

            @Override public void onPlayerError(PlaybackException error) {
                AppLog.w("arca photo video playback error " + error.getErrorCodeName() + ": " + error.getMessage());
                listener.onFailed();
            }
        });
    }

    void play(String url) {
        item = MediaItem.fromUri(Uri.parse(url));
        player.setMediaItem(item);
        player.prepare();
        player.play();
    }

    void stop() {
        item = null;
        player.stop();
        player.clearMediaItems();
    }

    void pause() {
        player.pause();
    }

    void toggle() {
        if (player.getPlayWhenReady()) player.pause();
        else player.play();
    }

    float progress() {
        long duration = player.getDuration();
        if (duration <= 0) return -1f;
        return Math.min(1f, player.getCurrentPosition() / (float) duration);
    }

    void release() {
        player.release();
    }
}
