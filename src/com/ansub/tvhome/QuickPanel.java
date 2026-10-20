package com.ansub.tvhome;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

// The sidebar the remote's settings button opens, styled like tvOS Control Center: a frosted card on
// the right with a big clock and rounded tiles that turn white when highlighted. Back closes it.
public class QuickPanel extends Activity {

    private static final String ADGUARD = "dns.adguard-dns.com";

    private float scale;
    private Typeface regular, medium;
    private LinearLayout card;
    private boolean closing;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        scale = getResources().getDisplayMetrics().widthPixels / 1920f;
        regular = Typeface.create("sans-serif", Typeface.NORMAL);
        medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);

        getWindow().setBackgroundDrawable(new ColorDrawable(0x59000000));
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            // Frosted glass: blurs whatever is behind, on TVs that support it.
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            getWindow().getAttributes().setBlurBehindRadius(px(40));
        }

        FrameLayout stage = new FrameLayout(this);
        card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(px(32), px(32), px(32), px(24));
        GradientDrawable glass = new GradientDrawable();
        glass.setCornerRadius(px(44));
        glass.setColor(0xF21E1E22);
        glass.setStroke(Math.max(1, px(2)), 0x1FFFFFFF);
        card.setBackground(glass);
        card.setElevation(px(40));
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(px(600), -1, Gravity.END);
        cp.setMargins(0, px(28), px(28), px(28));
        stage.addView(card, cp);

        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("H:mm");
        style(time, 64, Color.WHITE, medium);
        time.setLetterSpacing(-0.02f);
        time.setIncludeFontPadding(false);
        card.addView(time);
        TextClock date = new TextClock(this);
        String day = Strings.datePattern();
        date.setFormat12Hour(day);
        date.setFormat24Hour(day);
        style(date, 26, 0xA6FFFFFF, regular);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-2, -2);
        dp.bottomMargin = px(18);
        card.addView(date, dp);

        boolean online = online();
        View first = row(
                tile(LineIcon.WIFI, "Wi-Fi", online ? "Connected" : "Offline", new Intent(Settings.ACTION_WIFI_SETTINGS)),
                tile(LineIcon.BLUETOOTH, "Bluetooth", "Remotes, speakers", bluetoothIntent()));
        row(tile(LineIcon.PICTURE, "Picture", "Mode, brightness", new Intent(Settings.ACTION_DISPLAY_SETTINGS)),
                tile(LineIcon.SOUND, "Sound", "Speakers, mode", new Intent("com.android.tv.action.VIEW_SOUND_SETTINGS")));
        final Tile ads = tile(LineIcon.SHIELD, "Ad blocker", adBlockOn() ? "On" : "Off", null);
        ads.view.setOnClickListener(v -> toggleAdBlock(ads));
        row(tile(LineIcon.HDMI, "Inputs", "HDMI, TV", new Intent("com.android.tv.action.VIEW_INPUTS")), ads);
        // Turned off to save RAM. Apps can't switch other apps on by themselves, so OK opens the
        // app's page in Settings, where Enable is one more click.
        row(appSwitch(LineIcon.CAST, "Chromecast", "com.google.android.apps.mediashell"),
                appSwitch(LineIcon.SOUND, "Speech", "com.google.android.tts"));
        memoryTile();
        Tile all = tile(LineIcon.SETTINGS, "All settings", null, new Intent(Settings.ACTION_SETTINGS));
        // A short wide row: icon and name side by side.
        all.view.setOrientation(LinearLayout.HORIZONTAL);
        all.view.setGravity(Gravity.CENTER_VERTICAL);
        all.view.setPadding(px(24), 0, px(24), 0);
        ((LinearLayout.LayoutParams) all.title.getLayoutParams()).leftMargin = px(20);
        card.addView(all.view, wide(px(84)));

        setContentView(stage);
        if (first != null) first.requestFocus();

        // Slide in from the right, like Control Center.
        card.setTranslationX(px(640));
        card.setAlpha(0f);
        card.animate().translationX(0).alpha(1f).setDuration(260).setInterpolator(new DecelerateInterpolator(2f)).start();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean closeKey = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_NOTIFICATION
                || code == KeyEvent.KEYCODE_ALL_APPS;
        if (closeKey) {
            if (e.getAction() == KeyEvent.ACTION_UP) close();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private void close() {
        if (closing) return;
        closing = true;
        card.animate().translationX(px(640)).alpha(0f).setDuration(200).withEndAction(() -> {
            finish();
            overridePendingTransition(0, 0);
        }).start();
    }

    // ---- Tiles ----

    private static final class Tile {
        LinearLayout view;
        TextView title, sub;
        ImageView icon;
    }

    private Tile tile(int[] iconOps, String title, String sub, final Intent open) {
        final Tile t = new Tile();
        t.view = new LinearLayout(this);
        t.view.setOrientation(LinearLayout.VERTICAL);
        t.view.setPadding(px(22), px(14), px(18), px(12));
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(30));
        bg.setColor(0x1FFFFFFF);
        t.view.setBackground(bg);
        t.view.setFocusable(true);

        final LineIcon li = new LineIcon(iconOps);
        t.icon = new ImageView(this);
        t.icon.setImageDrawable(li);
        t.view.addView(t.icon, new LinearLayout.LayoutParams(px(32), px(32)));
        View grow = new View(this);
        t.view.addView(grow, new LinearLayout.LayoutParams(1, 0, 1f));
        t.title = style(new TextView(this), 24, Color.WHITE, medium);
        t.title.setText(title);
        t.title.setSingleLine(true);
        t.view.addView(t.title);
        if (sub != null) {
            t.sub = style(new TextView(this), 18, 0x99FFFFFF, regular);
            t.sub.setText(sub);
            t.sub.setSingleLine(true);
            t.sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.view.addView(t.sub);
        }

        // tvOS look: the highlighted tile turns white with dark text and grows a little.
        t.view.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xFFF2F2F7 : 0x1FFFFFFF);
            li.setColorFilter(has ? new PorterDuffColorFilter(0xFF111114, PorterDuff.Mode.SRC_IN) : null);
            t.title.setTextColor(has ? 0xFF111114 : Color.WHITE);
            if (t.sub != null) t.sub.setTextColor(has ? 0x99111114 : 0x99FFFFFF);
            v.animate().scaleX(has ? 1.04f : 1f).scaleY(has ? 1.04f : 1f).setDuration(160).start();
        });
        if (open != null) t.view.setOnClickListener(v -> {
            try { startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) {}
            finish();
        });
        return t;
    }

    private Tile appSwitch(int[] icon, String name, String pkg) {
        boolean on = false;
        try { on = getPackageManager().getApplicationInfo(pkg, android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS).enabled; }
        catch (Exception ignored) {}
        return tile(icon, name, (on ? "On" : "Off") + "  ·  OK to change",
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + pkg)));
    }

    // Two tiles side by side. Returns the left one, or null if neither exists.
    private View row(Tile left, Tile right) {
        LinearLayout r = new LinearLayout(this);
        r.setClipChildren(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, px(132), 1f);
        r.addView(left.view, lp);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, px(132), 1f);
        rp.leftMargin = px(12);
        r.addView(right.view, rp);
        card.addView(r, wide(-2));
        return left.view;
    }

    private LinearLayout.LayoutParams wide(int h) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, h);
        lp.bottomMargin = px(12);
        return lp;
    }

    // A wide tile showing how much RAM is free, with a bar. OK closes background apps.
    private void memoryTile() {
        final Tile t = tile(LineIcon.MEMORY, "Memory", "", null);
        final View fill = new View(this);
        final GradientDrawable track = new GradientDrawable();
        track.setCornerRadius(px(4));
        track.setColor(0x26FFFFFF);
        final GradientDrawable bar = new GradientDrawable();
        bar.setCornerRadius(px(4));
        bar.setColor(0xFF34C759);
        FrameLayout meter = new FrameLayout(this);
        meter.setBackground(track);
        fill.setBackground(bar);
        meter.addView(fill, new FrameLayout.LayoutParams(0, -1));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, px(8));
        mp.topMargin = px(12);
        t.view.addView(meter, mp);
        final Runnable refresh = () -> {
            ActivityManager.MemoryInfo mi = memory();
            float used = 1f - (float) mi.availMem / mi.totalMem;
            t.sub.setText((mi.availMem >> 20) + " MB free of " + Math.round(mi.totalMem / 1073741824f) + " GB  ·  OK to free up");
            bar.setColor(mi.availMem < (350L << 20) ? 0xFFFF9F0A : 0xFF34C759);
            meter.post(() -> {
                fill.getLayoutParams().width = Math.round(meter.getWidth() * used);
                fill.requestLayout();
            });
        };
        refresh.run();
        t.view.setOnClickListener(v -> {
            t.sub.setText("Freeing memory…");
            new Thread(() -> {
                ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
                long before = memory().availMem;
                for (ApplicationInfo ai : getPackageManager().getInstalledApplications(0))
                    if (!ai.packageName.equals(getPackageName())) am.killBackgroundProcesses(ai.packageName);
                try { Thread.sleep(1500); } catch (InterruptedException e) { return; }
                final long freed = Math.max(0, memory().availMem - before) >> 20;
                runOnUiThread(() -> {
                    refresh.run();
                    t.sub.setText("Freed " + freed + " MB  ·  " + (memory().availMem >> 20) + " MB free");
                });
            }).start();
        });
        card.addView(t.view, wide(px(136)));
    }

    // ---- Ad blocker: AdGuard's Private DNS, for every app on the TV ----

    private boolean adBlockOn() {
        return "hostname".equals(Settings.Global.getString(getContentResolver(), "private_dns_mode"))
                && ADGUARD.equals(Settings.Global.getString(getContentResolver(), "private_dns_specifier"));
    }

    private void toggleAdBlock(Tile t) {
        boolean on = !adBlockOn();
        try {
            if (on) Settings.Global.putString(getContentResolver(), "private_dns_specifier", ADGUARD);
            Settings.Global.putString(getContentResolver(), "private_dns_mode", on ? "hostname" : "opportunistic");
            t.sub.setText(on ? "On" : "Off");
        } catch (SecurityException e) {
            t.sub.setText("Needs setup from the computer");
        }
    }

    // ---- Helpers ----

    private Intent bluetoothIntent() {
        Intent bt = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
        return bt.resolveActivity(getPackageManager()) != null ? bt
                : new Intent("com.google.android.intent.action.CONNECT_INPUT");
    }

    private boolean online() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        try {
            NetworkCapabilities c = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            return false;
        }
    }

    private ActivityManager.MemoryInfo memory() {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi);
        return mi;
    }

    private <T extends TextView> T style(T t, int size, int color, Typeface tf) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTextColor(color);
        t.setTypeface(tf);
        return t;
    }

    private int px(int v) {
        return Math.round(v * scale);
    }
}
