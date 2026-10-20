package com.ansub.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

// Remote buttons that Google TV's home app (off now) used to answer: the settings button (sent as
// "Notification") opens our sidebar, and the Netflix, Prime, YouTube and YouTube Music buttons open
// their apps. Every other key goes through untouched.
public class KeyService extends AccessibilityService {

    // Sony's app buttons go to a Realtek service that first asks Google TV's home app (off now) what
    // mode the TV is in, and gives up when it can't. So the buttons open their apps from here.
    private static String appFor(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_2: return "com.amazon.amazonvideo.livingroom";
            case KeyEvent.KEYCODE_BUTTON_4: return "com.netflix.ninja";
            case KeyEvent.KEYCODE_BUTTON_14: return "com.google.android.youtube.tv";
            case KeyEvent.KEYCODE_BUTTON_15: return "com.google.android.youtube.tvmusic";
            default: return null;
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        String app = appFor(code);
        boolean panel = code == KeyEvent.KEYCODE_NOTIFICATION || code == KeyEvent.KEYCODE_ALL_APPS;
        if (app == null && !panel) return false;
        if (e.getAction() != KeyEvent.ACTION_UP) return true;
        Intent open = panel ? new Intent(this, QuickPanel.class) : launch(app);
        if (open == null && code == KeyEvent.KEYCODE_BUTTON_15) open = launch("com.google.android.youtube.tv");
        if (open == null) return true;
        try {
            startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ex) {
            android.util.Log.w("tvhome-keys", "couldn't open " + (panel ? "the sidebar" : app), ex);
        }
        return true;
    }

    private Intent launch(String pkg) {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        return i != null ? i : getPackageManager().getLaunchIntentForPackage(pkg);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}

    @Override public void onInterrupt() {}
}
