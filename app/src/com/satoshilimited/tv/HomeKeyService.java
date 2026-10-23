package com.satoshilimited.tv;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import org.json.JSONObject;

public class HomeKeyService extends AccessibilityService {
    static final String GOOGLE_HOME_PKG = "com.google.android.apps.tv.launcherx";
    private static final String[] STOCK_HOME_WINDOWS = {
        "com.google.android.apps.tv.launcherx.home.HomeActivity", "android.widget.FrameLayout",
    };
    static final String RECOVERY = "com.google.android.tungsten.setupwraith.RecoveryActivity";

    @Override protected void onServiceConnected() {
        AppLog.w("key service on");
    }

    @Override protected boolean onKeyEvent(KeyEvent e) {
        int colour = RemoteColours.index(e.getKeyCode());
        if (colour >= 0) return colourKey(e, colour);
        if (e.getKeyCode() != KeyEvent.KEYCODE_HOME || !active(true)) return false;
        if (e.getAction() == KeyEvent.ACTION_UP) {
            AppLog.w("home key -> arca TV");
            openHome();
        }
        return true;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!stockHome(event) || !active(true)) return;
        AppLog.w("stock home shown -> arca TV");
        openHome();
    }

    @Override public void onInterrupt() {}

    private boolean colourKey(KeyEvent e, int colour) {
        String target = RemoteColours.target(this, colour);
        if (target.isEmpty() || !active(false)) return false;
        Intent intent = RemoteColours.intent(this, target);
        if (intent == null) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            AppLog.w(RemoteColours.NAMES[colour] + " key -> " + target);
            try {
                startActivity(intent);
            } catch (RuntimeException ex) {
                AppLog.w("colour key couldn't open " + target + ": " + ex.getMessage());
            }
        }
        return true;
    }

    private static boolean stockHome(AccessibilityEvent event) {
        CharSequence pkg = event.getPackageName(), cls = event.getClassName();
        if (cls == null) return false;
        if (RECOVERY.contentEquals(cls)) return true;
        if (pkg == null || !GOOGLE_HOME_PKG.contentEquals(pkg)) return false;
        for (String home : STOCK_HOME_WINDOWS) if (home.contentEquals(cls)) return true;
        return false;
    }

    private boolean active(boolean home) {
        JSONObject s = HomeMode.state(this);
        if (!HomeMode.keyServiceWanted(this, s)) {
            AppLog.w("key service off: not wanted");
            disableSelf();
            return false;
        }
        return !home || s.optBoolean("keys");
    }

    private void openHome() {
        startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setClassName(HomeMode.PKG, HomeMode.PKG + ".HomeActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
