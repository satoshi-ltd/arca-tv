package com.satoshilimited.tv;

import android.util.Log;

/** The app's log (adb logcat -s arcaTV). Never logs keys. */
final class AppLog {
    private AppLog() {}

    static void w(String message) {
        Log.i("arcaTV", message);
    }
}
