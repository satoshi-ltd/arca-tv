package com.satoshilimited.tv;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

import java.util.regex.Pattern;

public class TrailerActivity extends Activity {
    static final String KEY = "key";
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final long START_MS = 15_000;

    private WebView web;
    private String key;
    private boolean playing;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable stalled = () -> {
        if (!playing) fallback();
    };

    static Intent watch(String key) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + key));
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        key = getIntent().getStringExtra(KEY);
        if (key == null || !VALID.matcher(key).matches()) {
            finish();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            web = new WebView(this);
        } catch (RuntimeException e) {
            AppLog.w("trailer webview: " + e.getMessage());
            fallback();
            return;
        }
        web.setBackgroundColor(0xFF000000);
        web.setFocusable(false);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "arca");
        setContentView(web);
        web.loadDataWithBaseURL("https://" + getPackageName(), page(key), "text/html", "utf-8", null);
        handler.postDelayed(stalled, START_MS);
    }

    @Override protected void onStop() {
        super.onStop();
        finish();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(stalled);
        if (web != null) {
            web.removeJavascriptInterface("arca");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_MEDIA_STOP:
                if (!down) finish();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (down) run("seek(-10)");
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (down) run("seek(10)");
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private void run(String js) {
        if (web != null) web.evaluateJavascript("window.player && " + js, null);
    }

    private void fallback() {
        try {
            startActivity(watch(key));
        } catch (RuntimeException e) {
            AppLog.w("trailer: " + e.getMessage());
        }
        finish();
    }

    private final class Bridge {
        @JavascriptInterface public void failed(int code) {
            AppLog.w("trailer embed error " + code);
            runOnUiThread(TrailerActivity.this::fallback);
        }

        @JavascriptInterface public void playing() {
            runOnUiThread(() -> {
                playing = true;
            });
        }

        @JavascriptInterface public void ended() {
            runOnUiThread(TrailerActivity.this::finish);
        }
    }

    private static String page(String key) {
        return "<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<style>html,body,#p{margin:0;width:100%;height:100%;background:#000;overflow:hidden}#p{opacity:0;transition:opacity .3s}#p.on{opacity:1}</style></head><body><div id=p></div>"
                + "<script src='https://www.youtube.com/iframe_api'></script><script>"
                + "var player;function onYouTubeIframeAPIReady(){player=new YT.Player('p',{videoId:'" + key + "',"
                + "playerVars:{autoplay:1,controls:0,rel:0,playsinline:1,fs:0,iv_load_policy:3,disablekb:1,origin:location.origin},"
                + "events:{onReady:function(e){e.target.playVideo()},onError:function(e){arca.failed(e.data)},"
                + "onStateChange:function(e){if(e.data===1){document.getElementById('p').className='on';arca.playing()}if(e.data===0)arca.ended()}}})}"
                + "function seek(s){player.seekTo(Math.max(0,player.getCurrentTime()+s),true)}"
                + "</script></body></html>";
    }
}
