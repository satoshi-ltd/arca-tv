package com.satoshilimited.tv;

import android.view.InputDevice;
import android.view.KeyEvent;

final class RemoteKeys {
    private RemoteKeys() {}

    static boolean hasColours() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null || device.isVirtual() || (device.getSources() & InputDevice.SOURCE_KEYBOARD) == 0) continue;
            boolean[] colours = device.hasKeys(RemoteColours.KEYS);
            boolean keyboard = device.hasKeys(KeyEvent.KEYCODE_A)[0];
            boolean all = colours[0] && colours[1] && colours[2] && colours[3];
            if (all || keyboard) AppLog.w("colour keys on " + device.getName() + ": all=" + all + " letters=" + keyboard);
            if (all && !keyboard) return true;
        }
        return false;
    }
}
