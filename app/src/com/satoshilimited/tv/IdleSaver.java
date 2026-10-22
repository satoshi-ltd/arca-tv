package com.satoshilimited.tv;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;

final class IdleSaver {
    static final long IDLE_MS = 60_000, FADE_IN_MS = 900, FADE_OUT_MS = 400;

    private final Activity activity;
    private final FrameLayout root;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable start = this::show;
    private DreamView saver;

    IdleSaver(Activity activity, View content) {
        this.activity = activity;
        Prefs.init(activity);
        root = new FrameLayout(activity);
        root.addView(content);
    }

    View view() {
        return root;
    }

    void resume() {
        schedule();
    }

    void pause() {
        handler.removeCallbacks(start);
        hide(false);
    }

    boolean key(KeyEvent event) {
        if (saver != null) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                hide(true);
                schedule();
            }
            return true;
        }
        schedule();
        return false;
    }

    private void schedule() {
        handler.removeCallbacks(start);
        if (saver == null && Prefs.screensaver()) handler.postDelayed(start, IDLE_MS);
    }

    private void show() {
        if (saver != null) return;
        saver = new DreamView(activity);
        saver.setAlpha(0f);
        root.addView(saver, new FrameLayout.LayoutParams(-1, -1));
        saver.start();
        saver.animate().alpha(1f).setDuration(FADE_IN_MS).start();
    }

    private void hide(boolean animate) {
        DreamView view = saver;
        if (view == null) return;
        saver = null;
        view.animate().cancel();
        Runnable done = () -> {
            view.stop();
            root.removeView(view);
        };
        if (animate) view.animate().alpha(0f).setDuration(FADE_OUT_MS).withEndAction(done).start();
        else done.run();
    }
}
