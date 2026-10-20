package com.ansub.tvhome;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.TreeMap;
import java.util.List;

public class MainActivity extends Activity {

    // Top-row apps, in this order of preference:
    //  1. a list saved at install time:  am start -n com.ansub.tvhome/.MainActivity --es apps "pkg1,pkg2"
    //     (--es apps auto  goes back to 2; add --es stamp <unique> so it's applied only once, see saveConfig)
    //  2. apps.txt (built in as Apps.LIST), topped up with other TV apps installed on this TV.
    // Missing apps are skipped. Topping up stops at six apps, but a chosen list can be any length:
    // apps wrap into rows of six, like Apple TV, and the page scrolls down once they don't all fit.
    // On the TV, hold OK on an app (or press Menu) to move or remove it, or add another; that saves list 1.
    // A network tile always sits at the top right, level with the clock. Settings shortcuts can join
    // it there, off until picked the same way:
    //   --es quick "bluetooth,sound,picture"
    // (--es quick none  takes them away again; "wifi" and "ethernet" are the network tile now).
    private static final String PREFS = "config";
    private static final List<String> NOT_APPS = Arrays.asList("com.android.vending", "com.android.tv.settings",
            "com.android.tv", "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx",
            "com.google.android.leanbacklauncher");
    private static final String PLAY_STORE = "com.android.vending";
    private static final String SETTINGS = "settings";
    private static final String ADD = "add";
    private static final String[] EDIT_ITEMS = {"Move", "Replace", "Remove from home", "Uninstall"};
    private static final String[] EDIT_ITEMS_SYSTEM = {"Move", "Replace", "Remove from home", "App info"};
    private static final int PER_ROW = 7, MAX_INPUTS = 3;

    // Settings shortcuts: name, label, icon. Tile keys are QUICK + name.
    private static final String QUICK = "quick:";
    private static final String[] QUICK_NAMES = {"bluetooth", "sound", "picture"};
    private static final String[] QUICK_LABELS = {"Bluetooth", "Sound", "Picture"};
    private static final int[][] QUICK_ICONS = {LineIcon.BLUETOOTH, LineIcon.SOUND, LineIcon.PICTURE};
    private static final String NETWORK = "network";
    private static final int OFFLINE = 0, ETHERNET = 4; // network states; 1 to 3 are Wi-Fi bars
    // TCL keeps its own Picture and Sound panels in a service; Android's Sound page is missing there.
    private static final String TCL_SHOW_WINDOW = "com.tcl.settings.SHOW_WINDOW";

    // Layout is designed at 1920x1080 and scaled to the TV's real UI size by px().
    private static final int MARGIN = 96, GAP = 36, RADIUS = 26;
    private static final int ROW1_TOP = 604, ROW_STEP = 200;
    private static final int GRID_MIN_TOP = 300; // more rows push the grid up, but not into the status bar
    private static final int ICON_TOP = 76, ICON_BUTTON = 72, ICON_SIZE = 40, ICON_GAP = 12; // level with the date
    private float scale = 1f;

    private static Typeface regular, semibold;

    private FrameLayout root; // the page, inside a vertical scroller
    private int tileW, tileH, gridTop; // app grid geometry, set by buildTiles
    private final List<View> tiles = new ArrayList<>();
    private boolean dirty = true;
    private java.util.Locale shownLocale; // the TV's language (and region) when the page was built
    private String focusedKey;
    private final List<String> rowApps = new ArrayList<>(); // the top row as shown, in order
    private String movingKey;   // the app being moved with left/right, if any
    private TextView moveHint;  // shown above the apps while moving
    private boolean longPressed; // OK was held: swallow the key-up so the app doesn't open

    // Apple TV-style top shelf: the focused app's banner fills the screen behind everything, blurred,
    // with its name in big type above the apps. Two backdrops crossfade so moving focus feels smooth.
    private final ImageView[] backdrops = new ImageView[2];
    private int frontBackdrop;
    private TextView heroTitle;
    private TextClock topClock;
    private final java.util.Map<String, Drawable> heroBanners = new java.util.HashMap<>();
    private final java.util.Map<String, Drawable> heroBlurred = new java.util.HashMap<>();
    private final java.util.Map<String, String> heroLabels = new java.util.HashMap<>();
    private String heroKey;
    private Runnable pendingHero;
    private TextView heroSub, heroBody;
    private ScrollView pageScroller;

    // Status bar at the top left: date, weather, free RAM (press OK on it to free more) and storage.
    private TextView weatherChip, ramChip, storageChip;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newFixedThreadPool(3);
    private long weatherAt, recsAt, lastAutoClean;
    private final Runnable statusTick = new Runnable() {
        @Override public void run() { refreshStatus(); ui.postDelayed(this, 3000); }
    };

    // Recommendations: popular shows and films on the streaming apps installed here, from TMDB.
    // Off until a key is saved:  am start -n com.ansub.tvhome/.MainActivity --es tmdb <key>
    private static final class Rec { String title, sub, overview, backdrop, pkg; }
    private List<Rec> recs = new ArrayList<>();
    private android.widget.HorizontalScrollView recScroller;
    private LinearLayout recRow;
    private final android.util.LruCache<String, Bitmap> images = new android.util.LruCache<String, Bitmap>(20 << 20) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    // TMDB's provider names (as regexes) and the app each one opens.
    private static final String[][] PROVIDERS = {
            {"netflix", "com.netflix.ninja"},
            {"amazon prime video", "com.amazon.amazonvideo.livingroom"},
            {"jiohotstar|hotstar|disney plus hotstar", "in.startv.hotstar"},
            {"sony ?liv", "com.sonyliv"},
            {"zee5", "com.graymatrix.did"},
            {"apple tv\\+?|apple tv plus", "com.apple.atve.androidtv.appletv"},
            {"crunchyroll", "com.crunchyroll.crunchyroid"},
            {"sun ?nxt", "com.suntv.sunnxt"},
    };

    // PS5: a tile that switches to its HDMI input. With HDMI Device Link on, that also wakes the PS5.
    private static final String PS5 = "ps5";

    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { dirty = true; }
    };

    // The network tile follows the connection live: Wi-Fi bars, Ethernet or Offline. Only its icon and
    // label change, so nothing is rebuilt.
    private ImageView netIcon;
    private int netState = -1;
    private final ConnectivityManager.NetworkCallback networkChanged = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network n) { runOnUiThread(MainActivity.this::refreshNetwork); }
        @Override public void onLost(Network n) { runOnUiThread(MainActivity.this::refreshNetwork); }
        // Also fires when the Wi-Fi signal gets stronger or weaker.
        @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) {
            runOnUiThread(MainActivity.this::refreshNetwork);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        saveConfig(getIntent());
        exportConfig();
        getWindow().getDecorView().post(() -> openRequested(getIntent()));
        loadFonts();
        scale = getResources().getDisplayMetrics().widthPixels / 1920f;
        getWindow().setBackgroundDrawable(background());

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroller.setClipChildren(false);
        root = new FrameLayout(this);
        root.setClipChildren(false);
        scroller.addView(root, new FrameLayout.LayoutParams(-1, -2));

        shownLocale = java.util.Locale.getDefault();

        heroTitle = text(px(92), Color.WHITE, semibold);
        heroTitle.setSingleLine(true);
        heroTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heroTitle.setLetterSpacing(-0.02f);
        heroTitle.setShadowLayer(px(24), 0, px(4), 0x80000000);
        root.addView(heroTitle, at(px(MARGIN), 0, px(1100), -2));
        heroSub = text(px(26), 0xD9FFFFFF, semibold);
        heroSub.setShadowLayer(px(12), 0, 0, 0x80000000);
        heroSub.setVisibility(View.GONE);
        root.addView(heroSub, at(px(MARGIN), 0, px(1100), -2));
        heroBody = text(px(26), 0xB3FFFFFF, regular);
        heroBody.setMaxLines(2);
        heroBody.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heroBody.setLineSpacing(px(4), 1f);
        heroBody.setShadowLayer(px(12), 0, 0, 0x80000000);
        heroBody.setVisibility(View.GONE);
        root.addView(heroBody, at(px(MARGIN), 0, px(1000), -2));

        buildWidgets();

        recScroller = new android.widget.HorizontalScrollView(this);
        recScroller.setHorizontalScrollBarEnabled(false);
        recScroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        recScroller.setClipChildren(false);
        recScroller.setClipToPadding(false);
        recRow = new LinearLayout(this);
        recRow.setClipChildren(false);
        recRow.setPadding(px(MARGIN), px(40), px(MARGIN), px(60));
        recScroller.addView(recRow);
        recScroller.setVisibility(View.GONE);
        root.addView(recScroller, at(0, 0, -1, -2));

        moveHint = text(px(24), 0xA8FFFFFF, regular);
        moveHint.setText(Strings.t("Use the arrows to move it, then press OK"));
        moveHint.setVisibility(View.GONE);
        root.addView(moveHint, at(px(MARGIN), px(ROW1_TOP - 64), -2, -2));

        // Stage: blurred artwork, then a dark scrim so text and tiles stay readable, then the page.
        FrameLayout stage = new FrameLayout(this);
        for (int i = 0; i < 2; i++) {
            ImageView b = new ImageView(this);
            b.setScaleType(ImageView.ScaleType.CENTER_CROP);
            b.setAlpha(0f);
            b.setScaleX(1.08f); // hides the soft edges the blur leaves
            b.setScaleY(1.08f);
            if (android.os.Build.VERSION.SDK_INT >= 31)
                b.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(px(28), px(28),
                        android.graphics.Shader.TileMode.CLAMP));
            backdrops[i] = b;
            stage.addView(b, new FrameLayout.LayoutParams(-1, -1));
        }
        View scrim = new View(this);
        scrim.setBackground(new LayerDrawable(new Drawable[] {
                new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[] {0x33000000, 0x59000000, 0xD9060607, 0xF7050506}),
                new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[] {0x8C000000, 0x26000000, 0x00000000})}));
        stage.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        stage.addView(scroller, new FrameLayout.LayoutParams(-1, -1));
        pageScroller = scroller;
        setContentView(stage);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(packagesChanged, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(packagesChanged, f);

        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) try {
            if (android.os.Build.VERSION.SDK_INT >= 24) cm.registerDefaultNetworkCallback(networkChanged);
            else cm.registerNetworkCallback(new NetworkRequest.Builder().build(), networkChanged);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packagesChanged);
        io.shutdownNow();
        ui.removeCallbacksAndMessages(null);
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) try { cm.unregisterNetworkCallback(networkChanged); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (saveConfig(intent)) buildTiles();
        openRequested(intent);
    }

    // The Mac remote opens a tile with --es open <tile key, a site's host like "example.com", or a
    // number: 1 is the first (biggest) tile on the home screen, 2 the next, and so on.
    // The extra is removed once used, so Android replaying the intent later doesn't reopen it.
    private void openRequested(Intent intent) {
        String want = intent == null ? null : intent.getStringExtra("open");
        if (want == null) return;
        intent.removeExtra("open");
        List<String> tilesNow = appList(getPackageManager());
        if (want.matches("[1-9]")) {
            int n = Integer.parseInt(want) - 1;
            if (n < tilesNow.size()) open(tilesNow.get(n));
            return;
        }
        for (String key : tilesNow)
            if (key.equals(want) || (isWeb(key) && host(key).equals(want))) { open(key); return; }
        if (launchIntent(getPackageManager(), want) != null) open(want);
    }

    // Saves an app list passed with --es apps "..." and shortcuts passed with --es quick "...".
    // Returns true if it changed.
    // When Android kills the home screen in the background, it can start it again with the same old
    // intent; the stamp install.sh sends stops that from undoing changes made on the TV since.
    private boolean saveConfig(Intent intent) {
        String apps = intent == null ? null : intent.getStringExtra("apps");
        String quick = intent == null ? null : intent.getStringExtra("quick");
        // tmdb: TMDB key for recommendations. ps5: the HDMI port number the PS5 is on ("none" hides it).
        String tmdb = intent == null ? null : intent.getStringExtra("tmdb");
        String ps5 = intent == null ? null : intent.getStringExtra(PS5);
        if (apps == null && quick == null && tmdb == null && ps5 == null) return false;
        SharedPreferences prefs = getSharedPreferences(PREFS, 0);
        String stamp = intent.getStringExtra("stamp");
        if (stamp != null && stamp.equals(prefs.getString("stamp", null))) return false;
        SharedPreferences.Editor e = prefs.edit();
        if (stamp != null) e.putString("stamp", stamp);
        if (apps != null) {
            apps = apps.trim();
            if (apps.isEmpty() || apps.equalsIgnoreCase("auto")) e.remove("apps");
            else e.putString("apps", apps);
        }
        if (quick != null) {
            quick = quick.trim();
            if (quick.isEmpty() || quick.equalsIgnoreCase("none")) e.remove("quick");
            else e.putString("quick", quick);
        }
        if (tmdb != null) {
            if (tmdb.trim().isEmpty() || tmdb.equalsIgnoreCase("none")) e.remove("tmdb");
            else e.putString("tmdb", tmdb.trim());
            recsAt = 0;
            recs = new ArrayList<>();
        }
        if (ps5 != null) {
            if (ps5.trim().isEmpty() || ps5.equalsIgnoreCase("none")) e.remove(PS5);
            else e.putString(PS5, ps5.trim());
        }
        e.apply();
        exportConfig();
        dirty = true;
        return true;
    }

    private List<String> appList(PackageManager pm) {
        List<String> out = new ArrayList<>();
        String saved = getSharedPreferences(PREFS, 0).getString("apps", null);
        String[] wanted = saved != null ? saved.split("[,\\s]+") : Apps.LIST;
        for (String p : wanted) if (!p.isEmpty() && !out.contains(p) && canShow(pm, p)) out.add(p);
        if (saved == null) {
            // Top up with other TV apps people installed (or updated), skipping launchers and system tools.
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                if (out.size() >= PER_ROW) break;
                String p = ri.activityInfo.packageName;
                int flags = ri.activityInfo.applicationInfo.flags;
                boolean stockSystem = (flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                if (stockSystem || out.contains(p) || NOT_APPS.contains(p) || p.equals(getPackageName())) continue;
                out.add(p);
            }
        }
        sortByUse(out);
        return out;
    }

    // Most used first: minutes watched over the last 30 days. Apps come from Android's usage stats
    // (needs "usage access", granted once with adb); web tiles count their own time in WebActivity.
    // Equal scores keep their saved order, so new or unused tiles stay where they were put.
    private void sortByUse(List<String> keys) {
        final java.util.Map<String, Long> score = new java.util.HashMap<>();
        try {
            android.app.usage.UsageStatsManager um =
                    (android.app.usage.UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            java.util.Map<String, android.app.usage.UsageStats> stats =
                    um.queryAndAggregateUsageStats(now - 30L * 24 * 3600 * 1000, now);
            for (java.util.Map.Entry<String, android.app.usage.UsageStats> e : stats.entrySet())
                score.put(e.getKey(), e.getValue().getTotalTimeInForeground());
        } catch (Exception ignored) {}
        SharedPreferences use = getSharedPreferences(WebActivity.USE, 0);
        for (String k : keys) if (isWeb(k)) score.put(k, use.getLong(host(k), 0));
        List<String> order = new ArrayList<>(keys);
        Collections.sort(keys, (a, b) -> {
            long d = score.getOrDefault(b, 0L) - score.getOrDefault(a, 0L);
            // Under a minute apart counts as a tie.
            return Math.abs(d) < 60_000 ? order.indexOf(a) - order.indexOf(b) : (d > 0 ? 1 : -1);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Rebuild only when apps were installed, removed or updated.
        if (dirty) {
            dirty = false;
            buildTiles();
        }
        refreshNetwork();
        ui.removeCallbacks(statusTick);
        ui.post(statusTick);
        if (System.currentTimeMillis() - recsAt > 6 * 3600 * 1000L) fetchRecs();
        // Auto-clean: back home with little RAM free, close apps sitting in the background (once a minute at most).
        long now = System.currentTimeMillis();
        if (now - lastAutoClean > 60_000 && memory().availMem < (400L << 20)) {
            lastAutoClean = now;
            boostRam();
        }
        focusLastApp();
    }

    // Coming back home lands on the last app or show, never on the status bar or network icon.
    @Override
    public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (has) focusLastApp();
    }

    private String lastTileKey;

    private void focusLastApp() {
        if (tiles.isEmpty()) return;
        View f = getCurrentFocus();
        if (f != null && f.getTag() != null && f.getTag().equals(lastTileKey)) return;
        View t = lastTileKey == null ? null : tile(lastTileKey);
        (t != null ? t : tiles.get(0)).requestFocus();
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(statusTick);
        dirty = true; // re-sort by use when coming back home
        super.onPause();
    }

    // The TV's language changed: start over so the labels and the date follow it.
    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (!java.util.Locale.getDefault().equals(shownLocale)) recreate();
    }

    @Override
    public void onBackPressed() { /* home screen: nothing to go back to */ }

    // Static warm gradient, drawn once by the window: no extra view, no animation.
    private Drawable background() {
        GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xFF101013, 0xFF09090B, 0xFF050506});
        GradientDrawable warm = radial(0x1A8AA0FF, 0.08f, -0.12f, px(1250));
        GradientDrawable plum = radial(0x14FFFFFF, 1.0f, 1.1f, px(1000));
        for (GradientDrawable g : new GradientDrawable[] {base, warm, plum}) g.setDither(true);
        return new LayerDrawable(new Drawable[] {base, warm, plum});
    }

    private static GradientDrawable radial(int color, float cx, float cy, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setColors(new int[] {color, color & 0x00FFFFFF});
        g.setGradientCenter(cx, cy);
        g.setGradientRadius(r);
        return g;
    }

    private void buildTiles() {
        // While tiles are swapped out, removing the focused one would hand focus down the row (and
        // overwrite focusedKey), so hold focus off until the new tiles are in.
        String want = focusedKey;
        root.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        for (View t : tiles) {
            View wrap = (View) t.getParent();
            ((ViewGroup) wrap.getParent()).removeView(wrap);
        }
        tiles.clear();
        rowApps.clear();
        movingKey = null;
        moveHint.setVisibility(View.GONE);

        PackageManager pm = getPackageManager();
        List<String> apps = appList(pm);
        int W = getResources().getDisplayMetrics().widthPixels, H = getResources().getDisplayMetrics().heightPixels;
        int m = px(MARGIN), g = px(24), cols = 6;
        // Bento grid: six columns, four rows filling the screen under the title. The first app is big
        // (2x2), a couple are wide (2x1), the rest square; the clock and system cards sit on the right.
        boolean hasRecs = !recs.isEmpty();
        int recH = px(225), recTop = px(380);
        int top = hasRecs ? recTop + px(40) + recH + px(80) : px(190);
        int cw = (W - 2 * m - g * (cols - 1)) / cols;
        int ch = (H - px(190) - px(64) - g * 3) / 4;
        tileW = cw;
        tileH = ch;
        gridTop = top;
        ((FrameLayout.LayoutParams) moveHint.getLayoutParams()).topMargin = top - px(56);
        int titleTop = hasRecs ? px(150) : px(64);
        heroTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, hasRecs ? px(72) : px(64));
        ((FrameLayout.LayoutParams) heroTitle.getLayoutParams()).topMargin = titleTop;
        ((FrameLayout.LayoutParams) heroSub.getLayoutParams()).topMargin = titleTop + px(96);
        ((FrameLayout.LayoutParams) heroBody.getLayoutParams()).topMargin = titleTop + px(142);
        ((FrameLayout.LayoutParams) recScroller.getLayoutParams()).topMargin = recTop;
        recScroller.setVisibility(hasRecs ? View.VISIBLE : View.GONE);
        heroBanners.clear();
        heroBlurred.clear();
        heroLabels.clear();
        heroLabels.put(SYSTEM, Strings.t("System"));

        List<boolean[]> used = new ArrayList<>();
        mark(used, 5, 0, 1, 2);
        mark(used, 5, 2, 1, 1);
        place(clockCard, m + 5 * (cw + g), top, cw, 2 * ch + g);
        place(systemCard, m + 5 * (cw + g), top + 2 * (ch + g), cw, ch);

        TvInputInfo ps5 = ps5Input();
        int idx = 0;
        for (String pkg : apps) {
            boolean special = pkg.equals(PS5) || isWeb(pkg);
            Intent li = special ? null : launchIntent(pm, pkg);
            if (!special && li == null) continue;
            int sw = idx == 0 ? 2 : (idx == 1 || idx == 7) ? 2 : 1, sh = idx == 0 ? 2 : 1;
            int[] at = fitCell(used, cols, sw, sh);
            if (at == null) { sw = 1; sh = 1; at = fitCell(used, cols, 1, 1); }
            mark(used, at[0], at[1], sw, sh);
            int x = m + at[0] * (cw + g), y = top + at[1] * (ch + g);
            int tw = sw * cw + (sw - 1) * g, th = sh * ch + (sh - 1) * g;
            idx++;
            String label;
            Drawable art;
            if (special) {
                boolean console = pkg.equals(PS5);
                label = console ? "PlayStation 5" : siteName(pkg);
                Drawable icon = !console && Uri.parse(pkg.substring(4)).getFragment() != null ? siteIcon(pkg) : null;
                art = console ? textBanner("PS5", "PLAYSTATION", 0xFF0A4FD6, 0xFF00185E, tw, th)
                        : icon != null ? iconBanner(icon, tw, th)
                        : textBanner(label, "", 0xFF2B2B33, 0xFF0B0B10, tw, th);
            } else {
                Drawable banner = null;
                try {
                    banner = pm.getActivityBanner(li);
                    if (banner == null) banner = pm.getApplicationBanner(pkg);
                } catch (Exception ignored) {}
                label = labelOf(pkg);
                art = null;
                if (banner != null) art = bentoArt(banner, tw, th);
                else try { art = iconBanner(pm.getApplicationIcon(pkg), tw, th); } catch (Exception ignored) {}
            }
            rowApps.add(pkg);
            addTile(root, pkg, label, art, art == null ? LineIcon.PLAY : null, null, x, y, tw, th);
        }

        // Empty cells in the rows in use get the Play Store, HDMI inputs and Add app, as glass tiles.
        int rowsUsed = Math.max(4, used.size());
        boolean store = !apps.contains(PLAY_STORE) && launchIntent(pm, PLAY_STORE) != null;
        List<TvInputInfo> inputs = new ArrayList<>(hdmiInputs());
        if (ps5 != null && apps.contains(PS5))
            for (int k = inputs.size() - 1; k >= 0; k--) if (inputs.get(k).getId().equals(ps5.getId())) inputs.remove(k);
        List<String[]> utils = new ArrayList<>();
        if (store) utils.add(new String[] {PLAY_STORE, "Play Store"});
        for (TvInputInfo in : inputs) utils.add(new String[] {in.getId(), inputLabel(in)});
        utils.add(new String[] {ADD, Strings.t("Add app")});
        java.util.Set<String> inGrid = new java.util.HashSet<>();
        for (String[] u : utils) {
            int[] at = fitCell(used, cols, 1, 1);
            if (at == null || at[1] >= rowsUsed) break;
            mark(used, at[0], at[1], 1, 1);
            int[] icon = u[0].equals(PLAY_STORE) ? LineIcon.PLAY : u[0].equals(ADD) ? LineIcon.PLUS : LineIcon.HDMI;
            addTile(root, u[0], u[1], null, icon, null, m + at[0] * (cw + g), top + at[1] * (ch + g), cw, ch);
            inGrid.add(u[0]);
        }
        root.setMinimumHeight(top + used.size() * (ch + g));

        // After the apps, so the first app keeps first focus.
        for (int i = 0; i < recs.size(); i++) addRecCard(i, recs.get(i), px(400), recH);

        // Small icons at the top right: whatever didn't fit in the grid, Settings, any settings
        // shortcuts, then the network icon. Up from the grid reaches them.
        List<Integer> quick = quickList();
        List<String[]> icons = new ArrayList<>();
        for (String[] u : utils) if (!inGrid.contains(u[0])) icons.add(u);
        int count = icons.size() + 1 + quick.size() + 1;
        int ib = px(ICON_BUTTON), ig = px(ICON_GAP);
        int qx = W - m + (ib - px(ICON_SIZE)) / 2 - count * ib - (count - 1) * ig;
        for (String[] u : icons) {
            int[] icon = u[0].equals(PLAY_STORE) ? LineIcon.PLAY : u[0].equals(ADD) ? LineIcon.PLUS : LineIcon.HDMI;
            addIcon(u[0], u[1], new LineIcon(icon), qx);
            qx += ib + ig;
        }
        addIcon(SETTINGS, Strings.t("Settings"), new LineIcon(LineIcon.SETTINGS), qx); qx += ib + ig;
        for (int q : quick) {
            addIcon(QUICK + QUICK_NAMES[q], Strings.t(QUICK_LABELS[q]), new LineIcon(QUICK_ICONS[q]), qx);
            qx += ib + ig;
        }
        netIcon = addIcon(NETWORK, Strings.t("Network"), null, qx);
        netState = -1;
        refreshNetwork();

        root.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        View restore = null;
        for (View t : tiles) if (t.getTag().equals(want)) restore = t;
        if (restore == null && !tiles.isEmpty()) restore = tiles.get(0);
        if (restore != null) restore.requestFocus();
        heroKey = null;
        showHero(focusedKey != null ? focusedKey : rowApps.isEmpty() ? SETTINGS : rowApps.get(0));
    }

    // Top shelf: fade the focused tile's blurred banner in behind the page and show its name. Waits a
    // beat so holding an arrow key doesn't redraw the backdrop for every tile it passes.
    private void scheduleHero(final String key) {
        if (pendingHero != null) root.removeCallbacks(pendingHero);
        pendingHero = () -> showHero(key);
        root.postDelayed(pendingHero, 90);
    }

    private void showHero(String key) {
        if (key == null || key.equals(heroKey)) return;
        String label = heroLabels.get(key);
        if (label == null) return; // the network and quick icons keep the current shelf
        heroKey = key;
        Rec rec = recFor(key);
        for (TextView t : new TextView[] {heroTitle, heroSub, heroBody}) {
            t.setAlpha(0f);
            t.setTranslationY(px(12));
            t.animate().alpha(1f).translationY(0).setDuration(260).start();
        }
        heroTitle.setText(label);
        heroSub.setText(rec == null ? "" : rec.sub);
        heroSub.setVisibility(rec == null ? View.GONE : View.VISIBLE);
        heroBody.setText(rec == null ? "" : rec.overview);
        heroBody.setVisibility(rec == null ? View.GONE : View.VISIBLE);

        if (rec != null) {
            // Shows get their artwork sharp and full screen, once it has downloaded.
            final String want = key;
            loadImage("https://image.tmdb.org/t/p/w1280" + rec.backdrop, bmp -> {
                if (want.equals(heroKey)) crossfade(new BitmapDrawable(getResources(), bmp), false, 1f);
            });
            return;
        }
        Drawable art = heroBlurred.get(key);
        Drawable banner = heroBanners.get(key);
        if (art == null && banner != null) {
            // Drawn small: the blur hides the low resolution, and it costs next to no memory.
            art = fit(banner, px(320), px(180));
            heroBlurred.put(key, art);
        }
        crossfade(art, true, 0.85f); // no art (Settings, HDMI) fades to the plain background
    }

    private void crossfade(Drawable art, boolean blur, float alpha) {
        ImageView out = backdrops[frontBackdrop];
        frontBackdrop ^= 1;
        ImageView in = backdrops[frontBackdrop];
        in.animate().cancel();
        out.animate().cancel();
        out.animate().alpha(0f).setDuration(450).start();
        if (art == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 31)
            in.setRenderEffect(blur ? android.graphics.RenderEffect.createBlurEffect(px(28), px(28),
                    android.graphics.Shader.TileMode.CLAMP) : null);
        in.setImageDrawable(art);
        in.setAlpha(0f);
        in.animate().alpha(alpha).setDuration(450).start();
    }

    private Rec recFor(String key) {
        if (key == null || !key.startsWith("rec:")) return null;
        try { return recs.get(Integer.parseInt(key.substring(4))); } catch (Exception e) { return null; }
    }

    private void addTile(ViewGroup parent, final String key, String label, Drawable banner, int[] lineIcon, Drawable appIcon,
                         int x, int y, int w, int h) {
        FrameLayout card = new FrameLayout(this);
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(RADIUS));
        if (banner != null) {
            bg.setColor(0xFF1E1A1C);
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(fit(banner, w, h));
            card.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            // Quiet "glass" tile: icon above label.
            bg.setColor(0x13FFFFFF);
            bg.setStroke(Math.max(1, px(2)), 0x17FFFFFF);
            LinearLayout inner = new LinearLayout(this);
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setGravity(Gravity.CENTER);
            ImageView iv = new ImageView(this);
            int size = lineIcon != null ? px(44) : px(64);
            if (lineIcon != null) iv.setImageDrawable(new LineIcon(lineIcon));
            else if (appIcon != null) iv.setImageDrawable(fit(appIcon, size, size));
            inner.addView(iv, new LinearLayout.LayoutParams(size, size));
            TextView t = text(px(24), 0xE0FFFFFF, regular);
            t.setText(label);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER);
            t.setPadding(px(16), 0, px(16), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = px(12);
            inner.addView(t, lp);
            card.addView(inner, new FrameLayout.LayoutParams(-1, -1));
        }
        card.setBackground(bg);
        card.setClipToOutline(true);
        card.setFocusable(true);
        card.setTag(key);

        // Banner tiles show their name underneath when focused.
        final TextView caption = null; // the focused tile's name shows big at the top instead
        if (caption != null) {
            caption.setText(label);
            caption.setGravity(Gravity.CENTER_HORIZONTAL);
            caption.setVisibility(View.INVISIBLE);
        }

        heroLabels.put(key, label);
        if (banner != null) heroBanners.put(key, banner);
        final boolean glass = banner == null;
        // Glossy light across the top of the focused tile, like the sheen on tvOS icons.
        final GradientDrawable gloss = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {0x40FFFFFF, 0x0DFFFFFF, 0x00FFFFFF});
        gloss.setCornerRadius(px(RADIUS));
        card.setOnFocusChangeListener((v, has) -> {
            if (has) { focusedKey = key; lastTileKey = key; scheduleHero(key); }
            if (glass) bg.setColor(has ? 0x38FFFFFF : 0x13FFFFFF);
            if (!key.equals(movingKey)) ((FrameLayout) v).setForeground(has ? gloss : null);
            float zoom = w > px(400) ? 1.04f : 1.09f; // big tiles grow less
            v.animate().scaleX(has ? zoom : 1f).scaleY(has ? zoom : 1f).translationZ(has ? px(40) : 0)
                    .setDuration(200).setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                    .withLayer().start();
            if (caption != null) caption.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
        });
        final boolean editable = rowApps.contains(key);
        final boolean atRightEdge = x + w >= getResources().getDisplayMetrics().widthPixels - px(MARGIN) - 4;
        card.setOnClickListener(v -> open(key));
        if (editable) card.setOnLongClickListener(v -> { showEditMenu(key); return true; });
        card.setOnKeyListener((v, code, ev) -> {
            boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                    || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
            boolean down = ev.getAction() == KeyEvent.ACTION_DOWN;
            if (key.equals(movingKey)) return moveKey(key, code, ok, down);
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT && atRightEdge) return true;
            if (editable && code == KeyEvent.KEYCODE_MENU) {
                if (!down) showEditMenu(key);
                return true;
            }
            if (!ok) return false;
            if (down) {
                if (ev.getRepeatCount() == 0) longPressed = false;
                else if (editable && !longPressed && (ev.isLongPress()
                        || ev.getEventTime() - ev.getDownTime() >= ViewConfiguration.getLongPressTimeout())) {
                    longPressed = true;
                    showEditMenu(key);
                }
            } else if (!longPressed) {
                open(key);
            }
            return true;
        });

        FrameLayout wrap = new FrameLayout(this);
        wrap.setClipChildren(false);
        wrap.addView(card, new FrameLayout.LayoutParams(w, h));
        if (caption != null) {
            FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(w, -2);
            cp.topMargin = h + px(22);
            wrap.addView(caption, cp);
        }
        parent.addView(wrap, at(x, y, w, -2));
        tiles.add(card);
    }

    // An icon on its own, no tile or label: a glass circle shows behind it only while it's focused.
    private ImageView addIcon(final String key, String label, Drawable icon, int x) {
        int b = px(ICON_BUTTON), size = px(ICON_SIZE);
        FrameLayout card = new FrameLayout(this);
        final GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.TRANSPARENT);
        card.setBackground(bg);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon);
        card.addView(iv, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        card.setFocusable(true);
        card.setTag(key);
        card.setContentDescription(label);
        card.setOnFocusChangeListener((v, has) -> {
            if (has) focusedKey = key;
            bg.setColor(has ? 0x38FFFFFF : Color.TRANSPARENT);
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(140).withLayer().start();
        });
        card.setOnClickListener(v -> open(key));
        FrameLayout wrap = new FrameLayout(this);
        wrap.setClipChildren(false);
        wrap.addView(card, new FrameLayout.LayoutParams(b, b));
        root.addView(wrap, at(x, px(ICON_TOP), b, b));
        tiles.add(card);
        return iv;
    }

    private void open(String key) {
        Intent i;
        if (ADD.equals(key)) {
            showAddMenu(-1);
            return;
        } else if (isWeb(key)) { // before isInput: a web address has slashes too
            i = new Intent(this, WebActivity.class).setData(Uri.parse(pageUrl(key)));
        } else if (PS5.equals(key)) {
            TvInputInfo in = ps5Input();
            if (in != null) openInput(in.getId());
            return;
        } else if (recFor(key) != null) {
            i = launchIntent(getPackageManager(), recFor(key).pkg);
            if (i == null) return;
        } else if (SETTINGS.equals(key)) {
            i = new Intent(this, QuickPanel.class);
        } else if (isInput(key)) {
            openInput(key);
            return;
        } else if (NETWORK.equals(key)) {
            openNetworkSettings();
            return;
        } else if (key.startsWith(QUICK)) {
            openQuick(key.substring(QUICK.length()));
            return;
        } else {
            i = launchIntent(getPackageManager(), key);
            if (i == null) return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Exception ignored) {}
    }

    // Live TV apps an HDMI tile can open, in order. Droidlogic (Amlogic) TVs ship Live TV under their
    // own package, with TvActivity as its entry point.
    private static final String[][] LIVE_TV = {{"com.android.tv", "com.android.tv.MainActivity"},
            {"com.droidlogic.android.tv", "com.android.tv.TvActivity"}};
    private static final String INPUT_PLAYER = "com.google.android.tv.inputplayer";

    // Opens an HDMI input: the stock Live TV app first, then Droidlogic's, then whatever the TV picks.
    // Each one is tried only when the one before it can't start, so a TV where the stock app already
    // worked takes exactly the same path as before. The one exception: when the TV itself sends HDMI to
    // Google TV's input player, that goes first, because some Google TVs ship com.android.tv as a stub
    // that starts fine but only says "Hello World!" (issue #5).
    private void openInput(String id) {
        Intent view = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id));
        List<Intent> tries = new ArrayList<>();
        ResolveInfo def = getPackageManager().resolveActivity(view, PackageManager.MATCH_DEFAULT_ONLY);
        if (def != null && def.activityInfo != null && INPUT_PLAYER.equals(def.activityInfo.packageName))
            tries.add(new Intent(view).setClassName(INPUT_PLAYER, def.activityInfo.name));
        for (String[] live : LIVE_TV) {
            Intent t = new Intent(view).setClassName(live[0], live[1]);
            if (t.resolveActivity(getPackageManager()) != null) tries.add(t);
        }
        tries.add(view);
        for (Intent t : tries) {
            t.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(t);
                return;
            } catch (Exception ignored) {}
        }
    }

    // The shortcuts picked with --es quick, in that order, as indexes into QUICK_NAMES. A shortcut
    // this TV has no screen for is left out, so it never shows a tile that does nothing.
    private List<Integer> quickList() {
        List<Integer> out = new ArrayList<>();
        String saved = getSharedPreferences(PREFS, 0).getString("quick", null);
        if (saved == null) return out;
        for (String name : saved.split("[,\\s]+")) {
            int q = Arrays.asList(QUICK_NAMES).indexOf(name.toLowerCase(java.util.Locale.ROOT));
            if (q < 0 || out.contains(q) || quickIntents(QUICK_NAMES[q]).isEmpty()) continue;
            out.add(q);
        }
        return out;
    }

    // OFFLINE, 1 to 3 for Wi-Fi with that many bars, or ETHERNET (any other link, which on a TV is a cable).
    private int networkState() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return OFFLINE;
        try {
            int rssi = Integer.MIN_VALUE;
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                NetworkCapabilities c = cm.getNetworkCapabilities(cm.getActiveNetwork());
                if (c == null) return OFFLINE;
                if (!c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return ETHERNET;
                if (android.os.Build.VERSION.SDK_INT >= 29) rssi = c.getSignalStrength();
            } else {
                android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
                if (ni == null || !ni.isConnected()) return OFFLINE;
                if (ni.getType() != ConnectivityManager.TYPE_WIFI) return ETHERNET;
            }
            if (rssi == Integer.MIN_VALUE) { // Android 9 and older: ask the Wi-Fi service
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null && wm.getConnectionInfo() != null) rssi = wm.getConnectionInfo().getRssi();
            }
            return 1 + WifiManager.calculateSignalLevel(rssi, 3);
        } catch (Exception e) {
            return OFFLINE;
        }
    }

    private void refreshNetwork() {
        if (netIcon == null) return;
        int state = networkState();
        if (state == netState) return;
        netState = state;
        if (state == ETHERNET) {
            netIcon.setImageDrawable(new LineIcon(LineIcon.ETHERNET));
        } else {
            // The whole Wi-Fi fan dim, with the bars it has bright on top. Offline is a slash instead.
            LineIcon fan = new LineIcon(LineIcon.WIFI);
            fan.setAlpha(0x47);
            int[] top = state == OFFLINE ? LineIcon.SLASH : state == 1 ? LineIcon.WIFI_1
                    : state == 2 ? LineIcon.WIFI_2 : LineIcon.WIFI;
            netIcon.setImageDrawable(new LayerDrawable(new Drawable[] {fan, new LineIcon(top)}));
        }
        ((View) netIcon.getParent()).setContentDescription(
                state == OFFLINE ? Strings.t("Offline") : state == ETHERNET ? "Ethernet" : "Wi-Fi");
    }

    // Wi-Fi settings (on Android TV that page lists Ethernet too), else the TV's network page, else Settings.
    private void openNetworkSettings() {
        for (String a : new String[] {Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS,
                Settings.ACTION_SETTINGS}) {
            try {
                startActivity(new Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (Exception ignored) {}
        }
    }

    // Screens a shortcut can open, best first, keeping only those this TV has.
    private List<Intent> quickIntents(String name) {
        List<Intent> all = new ArrayList<>();
        String tclType = name.equals("sound") ? "SoundPreset" : name.equals("picture") ? "picture" : null;
        if (tclType != null)
            all.add(new Intent(TCL_SHOW_WINDOW).setClassName("com.tcl.settings", "com.tcl.settings.ShowWindowService")
                    .putExtra("Type", tclType));
        switch (name) {
            case "bluetooth":
                all.add(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                all.add(new Intent("com.google.android.intent.action.CONNECT_INPUT")); // Android TV's pairing screen
                break;
            case "sound": all.add(new Intent(Settings.ACTION_SOUND_SETTINGS)); break;
            case "picture": all.add(new Intent(Settings.ACTION_DISPLAY_SETTINGS)); break;
        }
        PackageManager pm = getPackageManager();
        List<Intent> out = new ArrayList<>();
        for (Intent i : all) {
            boolean service = TCL_SHOW_WINDOW.equals(i.getAction());
            if (service ? pm.resolveService(i, 0) != null : i.resolveActivity(pm) != null) out.add(i);
        }
        return out;
    }

    // Opens a shortcut's screen: the first one that starts wins.
    private void openQuick(String name) {
        for (Intent i : quickIntents(name)) {
            try {
                if (TCL_SHOW_WINDOW.equals(i.getAction())) startService(i);
                else startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (Exception ignored) {}
        }
    }

    interface Pick { void at(int which); }

    // A side panel in the home screen's own look: a title, then one row per choice, with the same
    // glass focus as the tiles. Back closes it. iconPkgs (or null) puts each app's icon by its name.
    private void panel(String title, final List<String> labels, final List<String> iconPkgs, final Pick pick) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(48), px(92), px(48), px(48));

        TextView head = text(px(40), Color.WHITE, semibold);
        head.setText(title);
        head.setSingleLine(true);
        head.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.setPadding(px(24), 0, px(24), 0);
        box.addView(head);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setVerticalScrollBarEnabled(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        GradientDrawable focus = new GradientDrawable();
        focus.setCornerRadius(px(RADIUS));
        focus.setColor(0x38FFFFFF);
        list.setSelector(focus);
        // Icons load as rows appear, drawn small to save RAM.
        final PackageManager pm = getPackageManager();
        final int size = px(48);
        final Drawable[] icons = new Drawable[labels.size()];
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return labels.size(); }
            @Override public Object getItem(int pos) { return labels.get(pos); }
            @Override public long getItemId(int pos) { return pos; }
            @Override public View getView(int pos, View convert, ViewGroup parent) {
                TextView t = convert != null ? (TextView) convert : text(px(30), 0xE6FFFFFF, regular);
                t.setText(labels.get(pos));
                t.setSingleLine(true);
                t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                t.setGravity(Gravity.CENTER_VERTICAL);
                t.setMinHeight(px(88));
                t.setPadding(px(24), 0, px(24), 0);
                Drawable icon = null;
                if (iconPkgs != null) {
                    if (icons[pos] == null) {
                        try { icons[pos] = fit(pm.getApplicationIcon(iconPkgs.get(pos)), size, size); }
                        catch (Exception e) { icons[pos] = new ColorDrawable(Color.TRANSPARENT); }
                        icons[pos].setBounds(0, 0, size, size);
                    }
                    icon = icons[pos];
                }
                t.setCompoundDrawablesRelative(icon, null, null, null);
                t.setCompoundDrawablePadding(px(24));
                return t;
            }
        });
        list.setOnItemClickListener((parent, v, pos, id) -> {
            d.dismiss();
            pick.at(pos);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1f);
        lp.topMargin = px(36);
        box.addView(list, lp);

        d.setContentView(box);
        Window w = d.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(0xF7161113));
        w.setLayout(px(640), -1);
        w.setGravity(Gravity.END);
        w.setDimAmount(0.5f);
        d.show();
    }

    // A small menu that drops down under a tile, in the same glass as the tiles. Opens above the
    // tile when there's no room below. Back closes it.
    private void dropdown(View anchor, String[] items, final Pick pick) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = px(10), rowH = px(64);
        box.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(RADIUS));
        bg.setColor(0xFF231D1F);
        bg.setStroke(Math.max(1, px(2)), 0x17FFFFFF);
        box.setBackground(bg);
        float widest = 0; // some languages need more room than the tile is wide
        for (int i = 0; i < items.length; i++) {
            final int which = i;
            final TextView t = text(px(26), 0xD9FFFFFF, regular);
            t.setText(Strings.t(items[i]));
            t.setSingleLine(true);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setPadding(px(22), 0, px(22), 0);
            widest = Math.max(widest, t.getPaint().measureText(t.getText().toString()) + 2 * px(22) + 2 * pad);
            final GradientDrawable focus = new GradientDrawable();
            focus.setCornerRadius(px(RADIUS - pad));
            focus.setColor(Color.TRANSPARENT);
            t.setBackground(focus);
            t.setFocusable(true);
            t.setOnFocusChangeListener((v, has) -> {
                focus.setColor(has ? 0x38FFFFFF : Color.TRANSPARENT);
                t.setTextColor(has ? Color.WHITE : 0xD9FFFFFF);
            });
            t.setOnClickListener(v -> {
                d.dismiss();
                pick.at(which);
            });
            box.addView(t, new LinearLayout.LayoutParams(-1, rowH));
        }
        d.setContentView(box);

        // Line it up with the tile as drawn (focused tiles are scaled up), below its name.
        int[] at = new int[2];
        anchor.getLocationOnScreen(at);
        int tileBottom = at[1] + Math.round(anchor.getHeight() * anchor.getScaleY());
        int w = Math.max(Math.max(Math.round(anchor.getWidth() * anchor.getScaleX()), px(320)), (int) Math.ceil(widest));
        int h = items.length * rowH + 2 * pad;
        int y = tileBottom + px(64);
        if (y + h > getResources().getDisplayMetrics().heightPixels - px(24)) y = at[1] - h - px(16);
        Window win = d.getWindow();
        win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        win.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        win.setGravity(Gravity.TOP | Gravity.START);
        android.view.WindowManager.LayoutParams lp = win.getAttributes();
        lp.x = at[0];
        lp.y = y;
        lp.width = w;
        lp.height = h;
        win.setAttributes(lp);
        d.show();
    }

    // Hold OK (or Menu) on a top-row app: move it, swap it for another, or take it off the home
    // screen. Removing only takes the tile away: the app stays installed and can be added back.
    // Apps the user installed can also be uninstalled; Android asks to confirm, and the tile goes
    // once the app is gone. Apps that came with the TV can't be, so they get App info instead,
    // where Android offers to turn them off.
    private void showEditMenu(final String key) {
        final int index = rowApps.indexOf(key);
        if (index < 0) return;
        final boolean user = userInstalled(key);
        dropdown(tile(key), user ? EDIT_ITEMS : EDIT_ITEMS_SYSTEM, which -> {
            if (which == 0) startMove(key);
            else if (which == 1) showAddMenu(index);
            else if (which == 3) {
                Uri pkg = Uri.parse("package:" + key);
                try { startActivity(user ? new Intent(Intent.ACTION_DELETE, pkg)
                        : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)); }
                catch (Exception ignored) {}
            } else {
                rowApps.remove(index);
                saveRow();
                focusedKey = rowApps.isEmpty() ? ADD : rowApps.get(Math.min(index, rowApps.size() - 1));
                buildTiles();
            }
        });
    }

    // Every launchable app that isn't on the top row yet, by name. The pick is added at the end,
    // or takes the place of the app at replaceIndex (-1 to add).
    private void showAddMenu(final int replaceIndex) {
        final PackageManager pm = getPackageManager();
        TreeMap<String, String> byLabel = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String category : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String p = ri.activityInfo.packageName;
                if (rowApps.contains(p) || byLabel.containsValue(p) || p.equals(getPackageName())
                        || (NOT_APPS.contains(p) && !p.equals(PLAY_STORE))) continue;
                String label = labelOf(p);
                if (!byLabel.containsKey(label)) byLabel.put(label, p);
            }
        }
        if (byLabel.isEmpty()) {
            panel(Strings.t("Every app is already here"), Collections.singletonList("OK"), null, which -> {});
            return;
        }
        final List<String> pkgs = new ArrayList<>(byLabel.values());
        String title = replaceIndex < 0 ? Strings.t("Add an app")
                : String.format(Strings.t("Replace %s"), labelOf(rowApps.get(replaceIndex)));
        panel(title, new ArrayList<>(byLabel.keySet()), pkgs, which -> {
            if (replaceIndex < 0) rowApps.add(pkgs.get(which));
            else rowApps.set(replaceIndex, pkgs.get(which));
            saveRow();
            focusedKey = pkgs.get(which);
            buildTiles();
        });
    }

    private String labelOf(String pkg) {
        PackageManager pm = getPackageManager();
        try { return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); }
        catch (Exception e) { return pkg; }
    }

    // Saves the top row as the app list, so it no longer follows apps.txt.
    private void saveRow() {
        getSharedPreferences(PREFS, 0).edit().putString("apps", android.text.TextUtils.join(",", rowApps)).apply();
        exportConfig();
    }

    // Copies the tile list and settings to a file adb can read, for backup.sh. Same keys as the
    // --es extras restore.sh sends back. Lives in the app's own folder on shared storage.
    private void exportConfig() {
        try {
            SharedPreferences p = getSharedPreferences(PREFS, 0);
            org.json.JSONObject o = new org.json.JSONObject();
            for (String k : new String[] {"apps", "quick", "tmdb", PS5}) if (p.getString(k, null) != null) o.put(k, p.getString(k, null));
            java.io.File dir = getExternalFilesDir(null);
            if (dir == null) return;
            try (java.io.FileWriter w = new java.io.FileWriter(new java.io.File(dir, "config.json"))) { w.write(o.toString(2)); }
        } catch (Exception ignored) {}
    }

    // Bento packing: the first free spot (row by row) where a w x h block fits in `cols` columns.
    private static int[] fitCell(List<boolean[]> used, int cols, int w, int h) {
        for (int r = 0; r < 64; r++)
            for (int c = 0; c + w <= cols; c++) {
                boolean ok = true;
                for (int dr = 0; dr < h && ok; dr++)
                    for (int dc = 0; dc < w && ok; dc++)
                        if (r + dr < used.size() && used.get(r + dr)[c + dc]) ok = false;
                if (ok) return new int[] {c, r};
            }
        return null;
    }

    private static void mark(List<boolean[]> used, int c, int r, int w, int h) {
        for (int dr = 0; dr < h; dr++) {
            while (used.size() <= r + dr) used.add(new boolean[6]);
            for (int dc = 0; dc < w; dc++) used.get(r + dr)[c + dc] = true;
        }
    }

    // Where app number i sits in the grid.
    private int gridX(int i) {
        return px(MARGIN) + (i % PER_ROW) * (tileW + px(GAP));
    }

    private int gridY(int i) {
        return gridTop + (i / PER_ROW) * px(ROW_STEP);
    }

    private View tile(String key) {
        for (View t : tiles) if (t.getTag().equals(key)) return t;
        return null;
    }

    // Move mode: the tile gets an outline, the arrows move it through the grid, OK or Back finishes.
    private void startMove(String key) {
        View card = tile(key);
        if (card == null) return;
        movingKey = key;
        GradientDrawable outline = new GradientDrawable();
        outline.setCornerRadius(px(RADIUS));
        outline.setStroke(px(4), Color.WHITE);
        ((FrameLayout) card).setForeground(outline);
        moveHint.setVisibility(View.VISIBLE);
        heroTitle.setVisibility(View.INVISIBLE); // the hint sits where the title is
        card.requestFocus();
    }

    private boolean moveKey(String key, int code, boolean ok, boolean down) {
        // Left/Up moves the app earlier, Right/Down later. Tiles differ in size, so the grid is redrawn.
        int step = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_UP ? -1
                : code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
        if (step != 0) {
            int from = rowApps.indexOf(key), to = from + step;
            if (down && to >= 0 && to < rowApps.size()) {
                rowApps.add(to, rowApps.remove(from));
                saveRow();
                focusedKey = key;
                buildTiles();
                startMove(key);
            }
            return true;
        }
        if (ok || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) {
            if (!down) {
                movingKey = null;
                longPressed = false;
                ((FrameLayout) tile(key)).setForeground(null);
                moveHint.setVisibility(View.GONE);
                heroTitle.setVisibility(View.VISIBLE);
                saveRow();
            }
            return true;
        }
        return true; // up/down and everything else stay put while moving
    }

    private static boolean isInput(String key) {
        return key.indexOf('/') > 0;
    }

    // The TV's own HDMI inputs (not devices behind them), in order, skipping hidden ones.
    private List<TvInputInfo> hdmiInputs() {
        List<TvInputInfo> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return out;
        for (TvInputInfo in : tim.getTvInputList()) {
            if (in.getType() == TvInputInfo.TYPE_HDMI && in.getParentId() == null && !in.isHidden(this)) out.add(in);
        }
        Collections.sort(out, (a, b) -> a.getId().compareTo(b.getId()));
        return out.size() > MAX_INPUTS ? out.subList(0, MAX_INPUTS) : out;
    }

    // The name set in Settings > Inputs (like "Bedroom"), else the TV's own name (like "HDMI 1").
    private String inputLabel(TvInputInfo in) {
        CharSequence custom = in.loadCustomLabel(this);
        if (custom != null && custom.length() > 0) return custom.toString();
        CharSequence label = in.loadLabel(this);
        return label != null && label.length() > 0 ? label.toString() : "HDMI";
    }

    private boolean userInstalled(String pkg) {
        try { return (getPackageManager().getApplicationInfo(pkg, 0).flags & ApplicationInfo.FLAG_SYSTEM) == 0; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }

    private static Intent launchIntent(PackageManager pm, String pkg) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        return i != null ? i : pm.getLaunchIntentForPackage(pkg);
    }

    // Xiaomi TVs ship MiLan Pro but don't register it by name; every other TV gets Roboto.
    private static void loadFonts() {
        if (regular != null) return;
        regular = fromFile("/system/fonts/MILanPro_Normal.ttf", Typeface.create("sans-serif", Typeface.NORMAL));
        semibold = fromFile("/system/fonts/MILanPro_SemiBold.ttf", Typeface.create("sans-serif-medium", Typeface.NORMAL));
        // Google TV ships Google Sans, which is closer to Apple's SF than Roboto.
        for (String dir : new String[] {"/product/fonts/", "/system/fonts/"}) {
            if (new File(dir + "GoogleSans-Regular.ttf").exists()) {
                regular = fromFile(dir + "GoogleSans-Regular.ttf", regular);
                semibold = fromFile(dir + "GoogleSans-Medium.ttf", semibold);
                break;
            }
        }
    }

    private static Typeface fromFile(String path, Typeface fallback) {
        try { return new File(path).exists() ? Typeface.createFromFile(path) : fallback; }
        catch (Exception e) { return fallback; }
    }

    // Draws a banner once into a tile-sized bitmap (center-cropped). Some apps ship huge banners
    // that Android 9 refuses to draw ("Canvas: trying to draw too large bitmap"); this also saves RAM.
    private Drawable fit(Drawable d, int w, int h) {
        try {
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
            if (iw <= 0 || ih <= 0) { iw = w; ih = h; }
            float s = Math.max((float) w / iw, (float) h / ih);
            int dw = Math.round(iw * s), dh = Math.round(ih * s);
            d.setBounds((w - dw) / 2, (h - dh) / 2, (w - dw) / 2 + dw, (h - dh) / 2 + dh);
            d.draw(new Canvas(b));
            return new BitmapDrawable(getResources(), b);
        } catch (Throwable t) {
            return d;
        }
    }

    // ---- Bento widgets: a clock card and a system card, on the right of the grid ----

    private LinearLayout clockCard, systemCard;
    private View ramFill;
    private static final String SYSTEM = "system";

    private GradientDrawable cardBg(int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(RADIUS));
        bg.setColor(color);
        bg.setStroke(Math.max(1, px(2)), 0x14FFFFFF);
        return bg;
    }

    private void buildWidgets() {
        String day = Strings.datePattern();
        clockCard = new LinearLayout(this);
        clockCard.setOrientation(LinearLayout.VERTICAL);
        clockCard.setPadding(px(28), px(26), px(28), px(26));
        clockCard.setBackground(cardBg(0x26FFFFFF));
        topClock = clock("h:mm", "H:mm", px(88), Color.WHITE, semibold);
        topClock.setFontFeatureSettings("tnum");
        topClock.setIncludeFontPadding(false);
        topClock.setLetterSpacing(-0.03f);
        clockCard.addView(topClock);
        TextClock date = clock(day, day, px(24), 0xB3FFFFFF, regular);
        date.setMaxLines(2);
        clockCard.addView(date);
        clockCard.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
        weatherChip = text(px(40), Color.WHITE, semibold);
        weatherChip.setVisibility(View.GONE);
        clockCard.addView(weatherChip);
        root.addView(clockCard, at(0, 0, 1, 1));

        systemCard = new LinearLayout(this);
        systemCard.setOrientation(LinearLayout.VERTICAL);
        systemCard.setPadding(px(26), px(22), px(26), px(22));
        final GradientDrawable sbg = cardBg(0x26FFFFFF);
        systemCard.setBackground(sbg);
        systemCard.setFocusable(true);
        systemCard.setTag(SYSTEM);
        TextView head = text(px(20), 0x99FFFFFF, semibold);
        head.setText(Strings.t("Memory"));
        head.setLetterSpacing(0.08f);
        systemCard.addView(head);
        ramChip = text(px(34), Color.WHITE, semibold);
        ramChip.setSingleLine(true);
        systemCard.addView(ramChip);
        FrameLayout meter = new FrameLayout(this);
        GradientDrawable track = new GradientDrawable();
        track.setCornerRadius(px(4));
        track.setColor(0x26FFFFFF);
        meter.setBackground(track);
        ramFill = new View(this);
        GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(px(4));
        fill.setColor(0xFF34C759);
        ramFill.setBackground(fill);
        meter.addView(ramFill, new FrameLayout.LayoutParams(0, -1));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, px(8));
        mp.topMargin = px(10);
        mp.bottomMargin = px(10);
        systemCard.addView(meter, mp);
        storageChip = text(px(20), 0x99FFFFFF, regular);
        storageChip.setSingleLine(true);
        systemCard.addView(storageChip);
        systemCard.setOnFocusChangeListener((v, has) -> {
            if (has) { focusedKey = SYSTEM; scheduleHero(SYSTEM); }
            sbg.setColor(has ? 0x47FFFFFF : 0x26FFFFFF);
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(200).start();
        });
        systemCard.setOnClickListener(v -> boostRam());
        // Right edge of the grid: Right stays put instead of jumping up to the top icons.
        systemCard.setOnKeyListener((v, code, ev) -> code == KeyEvent.KEYCODE_DPAD_RIGHT);
        root.addView(systemCard, at(0, 0, 1, 1));
    }

    private static void place(View v, int x, int y, int w, int h) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
        lp.leftMargin = x;
        lp.topMargin = y;
        lp.width = w;
        lp.height = h;
        v.setLayoutParams(lp);
    }

    // A banner shown whole in a tile of any shape: centred on a soft wash of its own colours.
    private Drawable bentoArt(Drawable art, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        // The wash: the banner cropped to the tile's shape, shrunk to a few pixels and stretched back.
        Drawable wash = fit(art, Math.max(4, w / 48), Math.max(3, h / 48));
        if (wash instanceof BitmapDrawable) ((BitmapDrawable) wash).setFilterBitmap(true);
        wash.setBounds(0, 0, w, h);
        wash.draw(c);
        int iw = art.getIntrinsicWidth(), ih = art.getIntrinsicHeight();
        if (iw <= 0 || ih <= 0) { iw = 16; ih = 9; }
        float sc = Math.min((float) w / iw, (float) h / ih);
        int dw = Math.max(1, Math.round(iw * sc)), dh = Math.max(1, Math.round(ih * sc));
        // The banner itself, with its edges faded so it melts into the wash instead of a hard seam.
        Bitmap fg = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888);
        Canvas fc = new Canvas(fg);
        art.setBounds(0, 0, dw, dh);
        art.draw(fc);
        android.graphics.Paint mask = new android.graphics.Paint();
        mask.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
        if (dw < w - 2) {
            float f = dw * 0.14f / dw;
            mask.setShader(new android.graphics.LinearGradient(0, 0, dw, 0, new int[] {0, 0xFF000000, 0xFF000000, 0},
                    new float[] {0, f, 1 - f, 1}, android.graphics.Shader.TileMode.CLAMP));
            fc.drawRect(0, 0, dw, dh, mask);
        }
        if (dh < h - 2) {
            float f = 0.14f;
            mask.setShader(new android.graphics.LinearGradient(0, 0, 0, dh, new int[] {0, 0xFF000000, 0xFF000000, 0},
                    new float[] {0, f, 1 - f, 1}, android.graphics.Shader.TileMode.CLAMP));
            fc.drawRect(0, 0, dw, dh, mask);
        }
        c.drawBitmap(fg, (w - dw) / 2f, (h - dh) / 2f, null);
        fg.recycle();
        return new BitmapDrawable(getResources(), b);
    }

    // ---- Website, PS5 and status helpers ----

    private static boolean isWeb(String key) {
        return key.startsWith("web:http");
    }

    private static String host(String key) {
        String h = Uri.parse(key.substring(4)).getHost();
        return h == null ? key : h.startsWith("www.") ? h.substring(4) : h;
    }

    // "web:https://example.com" is called "Example"; "web:https://example.com/home#Films" is called "Films".
    private static String siteName(String key) {
        String named = Uri.parse(key.substring(4)).getFragment();
        if (named != null && !named.isEmpty()) return named;
        String h = host(key);
        int dot = h.indexOf('.');
        String n = dot > 0 ? h.substring(0, dot) : h;
        return n.isEmpty() ? h : Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    // The address a web tile opens: its key without the "web:" and without a "#Name".
    private static String pageUrl(String key) {
        String u = key.substring(4);
        int hash = u.indexOf('#');
        return hash < 0 ? u : u.substring(0, hash);
    }

    // Named web tiles wear the site's own icon: fetched once from the page's <link rel=icon>, then
    // kept as a file. Until it arrives the tile shows the name in big type.
    private final java.util.Set<String> iconTried = new java.util.HashSet<>();

    private Drawable siteIcon(final String key) {
        final java.io.File f = new java.io.File(getFilesDir(), "icon_" + host(key) + ".png");
        if (f.exists()) {
            Bitmap b = BitmapFactory.decodeFile(f.getPath());
            if (b != null) return new BitmapDrawable(getResources(), b);
        }
        if (!iconTried.add(key)) return null;
        io.execute(() -> {
            try {
                String page = pageUrl(key), html = httpGet(page, null);
                if (html == null) return;
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                        "<link[^>]*rel=\"[^\"]*(apple-touch-icon|icon)[^\"]*\"[^>]*href=\"([^\"]+)\"",
                        java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html);
                if (!m.find()) return;
                java.net.URL src = new java.net.URL(new java.net.URL(page), m.group(2));
                java.io.File tmp = new java.io.File(f.getPath() + ".part");
                try (java.io.InputStream in = src.openStream(); java.io.OutputStream out = new java.io.FileOutputStream(tmp)) {
                    byte[] buf = new byte[16384];
                    for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                }
                if (BitmapFactory.decodeFile(tmp.getPath()) == null || !tmp.renameTo(f)) return;
                runOnUiThread(this::buildTiles);
            } catch (Exception ignored) {}
        });
        return null;
    }

    private boolean canShow(PackageManager pm, String key) {
        if (isWeb(key)) return true;
        if (PS5.equals(key)) return ps5Input() != null;
        return launchIntent(pm, key) != null;
    }

    // The HDMI input set with --es ps5 <port number>, hidden or not (the TV hides empty ports).
    private TvInputInfo ps5Input() {
        String port = getSharedPreferences(PREFS, 0).getString(PS5, null);
        if (port == null) return null;
        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return null;
        for (TvInputInfo in : tim.getTvInputList()) {
            if (in.getType() != TvInputInfo.TYPE_HDMI || in.getParentId() != null) continue;
            CharSequence l = in.loadLabel(this);
            if (l != null && l.toString().replaceAll("\\D", "").equals(port.replaceAll("\\D", ""))) return in;
        }
        return null;
    }

    // A tile drawn from text, for things without a banner: a big word over a diagonal gradient.
    private Drawable textBanner(String big, String small, int from, int to, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setShader(new android.graphics.LinearGradient(0, 0, w, h, from, to, android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        p.setColor(Color.WHITE);
        p.setTextAlign(android.graphics.Paint.Align.CENTER);
        p.setTypeface(Typeface.create(semibold, Typeface.BOLD));
        float size = h * 0.34f;
        p.setTextSize(size);
        while (p.measureText(big) > w * 0.84f && size > 10) p.setTextSize(size -= 2);
        c.drawText(big, w / 2f, h * 0.55f, p);
        if (!small.isEmpty()) {
            p.setTypeface(regular);
            p.setTextSize(h * 0.1f);
            p.setLetterSpacing(0.2f);
            p.setColor(0xB3FFFFFF);
            c.drawText(small, w / 2f, h * 0.78f, p);
        }
        return new BitmapDrawable(getResources(), b);
    }

    // A banner made from an app's square icon: the icon's own colours, softly blurred, fill the tile,
    // with the icon in the middle.
    private Drawable iconBanner(Drawable icon, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Drawable wash = fit(icon, 8, 5); // a few pixels stretched wide reads as a soft blur
        if (wash instanceof BitmapDrawable) ((BitmapDrawable) wash).setFilterBitmap(true);
        wash.setBounds(0, 0, w, h);
        wash.draw(c);
        c.drawColor(0x59000000);
        int s = Math.round(h * 0.62f), x = (w - s) / 2, y = (h - s) / 2;
        android.graphics.Path clip = new android.graphics.Path();
        clip.addRoundRect(new android.graphics.RectF(x, y, x + s, y + s), s * 0.22f, s * 0.22f,
                android.graphics.Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        icon.setBounds(x, y, x + s, y + s);
        icon.draw(c);
        c.restore();
        return new BitmapDrawable(getResources(), b);
    }

    private TextView chip(boolean focusable) {
        TextView t = text(px(24), 0xE6FFFFFF, regular);
        chipStyle(t, focusable);
        return t;
    }

    private void chipStyle(final TextView t, boolean focusable) {
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(28));
        bg.setColor(0x1AFFFFFF);
        t.setBackground(bg);
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(px(22), px(10), px(22), px(10));
        if (!focusable) return;
        t.setFocusable(true);
        t.setTag("chip");
        t.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0x4DFFFFFF : 0x1AFFFFFF);
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(160).start();
            if (has) pageScroller.smoothScrollTo(0, 0);
        });
    }

    private LinearLayout.LayoutParams chipLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = px(12);
        return lp;
    }

    private static String gb(long bytes) {
        if (bytes < (1L << 30)) return (bytes >> 20) + " MB";
        return String.format(java.util.Locale.US, "%.1f GB", bytes / 1073741824f);
    }

    private ActivityManager.MemoryInfo memory() {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
        return mi;
    }

    // Every 3 seconds while the home screen shows: RAM and storage. Weather every 30 minutes.
    private void refreshStatus() {
        ActivityManager.MemoryInfo mi = memory();
        ramChip.setText(gb(mi.availMem) + " free");
        boolean low = mi.availMem < (350L << 20);
        ramChip.setTextColor(low ? 0xFFFFC46B : Color.WHITE);
        final float used = 1f - (float) mi.availMem / mi.totalMem;
        ((GradientDrawable) ramFill.getBackground()).setColor(low ? 0xFFFF9F0A : 0xFF34C759);
        View meter = (View) ramFill.getParent();
        meter.post(() -> { ramFill.getLayoutParams().width = Math.round(meter.getWidth() * used); ramFill.requestLayout(); });
        try {
            android.os.StatFs s = new android.os.StatFs(android.os.Environment.getDataDirectory().getPath());
            storageChip.setText(Strings.t("Storage") + "  " + gb(s.getAvailableBytes()) + " free");
        } catch (Exception ignored) {}
        long now = System.currentTimeMillis();
        fetchWallpaper();
        if (now - weatherAt > 30 * 60 * 1000L) {
            weatherAt = now;
            io.execute(() -> {
                String w = httpGet("https://wttr.in/?format=%t+%C", null);
                if (w == null || !w.contains("°") || w.length() > 40) {
                    weatherAt = System.currentTimeMillis() - 28 * 60 * 1000L; // try again in 2 minutes
                    return;
                }
                final String text = w.trim().replace("+", "").replace("°C", "°").replace("°F", "°");
                getSharedPreferences(PREFS, 0).edit().putString("weather", text).apply(); // for the screensaver
                runOnUiThread(() -> { weatherChip.setText(text); weatherChip.setVisibility(View.VISIBLE); });
            });
        }
    }

    // The screensaver's photo: Bing's photo of the day, fetched here once a day so the screensaver
    // itself never waits on the network. Saved as a file; only its caption goes in the settings.
    private void fetchWallpaper() {
        final SharedPreferences prefs = getSharedPreferences(PREFS, 0);
        final String today = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(new java.util.Date());
        if (today.equals(prefs.getString("wallpaper_day", null))) return;
        prefs.edit().putString("wallpaper_day", today).apply(); // one try a day, even if it fails
        io.execute(() -> {
            try {
                org.json.JSONObject img = new org.json.JSONObject(httpGet(
                        "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=en-IN", null))
                        .getJSONArray("images").getJSONObject(0);
                java.io.File tmp = new java.io.File(getFilesDir(), ClockDream.PHOTO + ".part");
                try (java.io.InputStream in = new java.net.URL("https://www.bing.com" + img.getString("url")).openStream();
                     java.io.OutputStream out = new java.io.FileOutputStream(tmp)) {
                    byte[] buf = new byte[16384];
                    for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                }
                if (tmp.length() < 50_000 || !tmp.renameTo(new java.io.File(getFilesDir(), ClockDream.PHOTO))) return;
                prefs.edit().putString("wallpaper_caption", img.optString("title") + "  ·  " + img.optString("copyright")).apply();
            } catch (Exception e) {
                prefs.edit().remove("wallpaper_day").apply(); // try again next time
            }
        });
    }

    // Asks Android to close apps sitting in the background. Only background apps: whatever is on
    // screen or playing keeps running.
    private void boostRam() {
        ui.removeCallbacks(statusTick);
        ramChip.setText("Cleaning…");
        io.execute(() -> {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            long before = memory().availMem;
            for (ApplicationInfo ai : getPackageManager().getInstalledApplications(0))
                if (!ai.packageName.equals(getPackageName())) am.killBackgroundProcesses(ai.packageName);
            try { Thread.sleep(1500); } catch (InterruptedException e) { return; }
            final long freed = memory().availMem - before;
            runOnUiThread(() -> {
                ramChip.setText(freed > (10L << 20) ? "Freed " + gb(freed) : "Already clean");
                ui.postDelayed(statusTick, 2500);
            });
        });
    }

    // A small GET for weather and TMDB. Returns null on any failure.
    private static String httpGet(String url, String bearer) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "curl/8.0");
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
            if (c.getResponseCode() != 200) return null;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            java.io.InputStream in = c.getInputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    interface Got { void bitmap(Bitmap b); }

    // Downloads an image once (kept in a small memory cache) and hands it over on the UI thread.
    private void loadImage(final String url, final Got got) {
        Bitmap cached = images.get(url);
        if (cached != null) { got.bitmap(cached); return; }
        io.execute(() -> {
            try {
                java.io.InputStream in = new java.net.URL(url).openStream();
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565; // half the memory, no visible difference here
                final Bitmap b = BitmapFactory.decodeStream(in, null, o);
                in.close();
                if (b == null) return;
                images.put(url, b);
                runOnUiThread(() -> got.bitmap(b));
            } catch (Exception ignored) {}
        });
    }

    // Popular shows and films on each installed streaming app in India: 3 per app, interleaved, 15 at most.
    private void fetchRecs() {
        final String key = getSharedPreferences(PREFS, 0).getString("tmdb", null);
        if (key == null) return;
        recsAt = System.currentTimeMillis();
        final PackageManager pm = getPackageManager();
        io.execute(() -> {
            try {
                // A v4 read token is long; a v3 key is 32 characters and goes in the query.
                String bearer = key.length() > 40 ? key : null;
                String q = bearer == null ? "api_key=" + key + "&" : "";
                String base = "https://api.themoviedb.org/3/";
                org.json.JSONArray all = new org.json.JSONObject(httpGet(base + "watch/providers/tv?" + q
                        + "watch_region=IN", bearer)).getJSONArray("results");
                List<List<Rec>> perApp = new ArrayList<>();
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (String[] prov : PROVIDERS) {
                    if (launchIntent(pm, prov[1]) == null) continue;
                    org.json.JSONObject match = null;
                    for (int i = 0; i < all.length() && match == null; i++) {
                        String name = all.getJSONObject(i).getString("provider_name").toLowerCase(java.util.Locale.ROOT);
                        if (name.matches(prov[0])) match = all.getJSONObject(i);
                    }
                    if (match == null) continue;
                    List<Rec> mine = new ArrayList<>();
                    for (String kind : new String[] {"tv", "movie"}) {
                        String body = httpGet(base + "discover/" + kind + "?" + q + "watch_region=IN&with_watch_providers="
                                + match.getInt("provider_id") + "&sort_by=popularity.desc&vote_count.gte=20", bearer);
                        if (body == null) continue;
                        org.json.JSONArray res = new org.json.JSONObject(body).getJSONArray("results");
                        int took = 0;
                        for (int i = 0; i < res.length() && took < (kind.equals("tv") ? 2 : 1); i++) {
                            org.json.JSONObject r = res.getJSONObject(i);
                            String id = kind + r.getInt("id");
                            if (r.isNull("backdrop_path") || !seen.add(id)) continue;
                            Rec rec = new Rec();
                            rec.title = r.optString(kind.equals("tv") ? "name" : "title");
                            rec.overview = r.optString("overview");
                            rec.backdrop = r.getString("backdrop_path");
                            rec.pkg = prov[1];
                            String date = r.optString(kind.equals("tv") ? "first_air_date" : "release_date");
                            double vote = r.optDouble("vote_average", 0);
                            rec.sub = labelOf(prov[1]) + (kind.equals("tv") ? "  ·  Series" : "  ·  Film")
                                    + (date.length() >= 4 ? "  ·  " + date.substring(0, 4) : "")
                                    + (vote > 0 ? String.format(java.util.Locale.US, "  ·  ★ %.1f", vote) : "");
                            mine.add(rec);
                            took++;
                        }
                    }
                    if (!mine.isEmpty()) perApp.add(mine);
                }
                final List<Rec> out = new ArrayList<>();
                for (int round = 0; out.size() < 15; round++) {
                    boolean any = false;
                    for (List<Rec> l : perApp) if (round < l.size() && out.size() < 15) { out.add(l.get(round)); any = true; }
                    if (!any) break;
                }
                if (out.isEmpty()) return;
                runOnUiThread(() -> { recs = out; buildTiles(); });
            } catch (Exception ignored) {
                recsAt = 0; // try again next time the home screen shows
            }
        });
    }

    // A card in the recommendations row: the show's artwork with its name along the bottom.
    private void addRecCard(int index, Rec r, int w, int h) {
        final String key = "rec:" + index;
        heroLabels.put(key, r.title);
        FrameLayout card = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(RADIUS));
        bg.setColor(0xFF1A1A1E);
        card.setBackground(bg);
        card.setClipToOutline(true);
        final ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x00000000, 0x00000000, 0xCC000000}));
        card.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        TextView name = text(px(22), Color.WHITE, semibold);
        name.setText(r.title);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        np.setMargins(px(18), 0, px(18), px(14));
        card.addView(name, np);
        loadImage("https://image.tmdb.org/t/p/w780" + r.backdrop, iv::setImageBitmap);

        final GradientDrawable gloss = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {0x40FFFFFF, 0x0DFFFFFF, 0x00FFFFFF});
        gloss.setCornerRadius(px(RADIUS));
        card.setFocusable(true);
        card.setTag(key);
        card.setOnFocusChangeListener((v, has) -> {
            if (has) { focusedKey = key; lastTileKey = key; scheduleHero(key); pageScroller.smoothScrollTo(0, 0); }
            ((FrameLayout) v).setForeground(has ? gloss : null);
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).translationZ(has ? px(40) : 0)
                    .setDuration(200).setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                    .withLayer().start();
        });
        card.setOnClickListener(v -> open(key));

        FrameLayout wrap = new FrameLayout(this);
        wrap.setClipChildren(false);
        wrap.addView(card, new FrameLayout.LayoutParams(w, h));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.rightMargin = px(GAP);
        recRow.addView(wrap, lp);
        tiles.add(card);
    }

    private int px(int v) {
        return Math.round(v * scale);
    }

    private TextClock clock(String f12, String f24, int px, int color, Typeface tf) {
        TextClock c = new TextClock(this);
        c.setFormat12Hour(f12);
        c.setFormat24Hour(f24);
        c.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        c.setTextColor(color);
        c.setTypeface(tf);
        return c;
    }

    private TextView text(int px, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        t.setTextColor(color);
        if (tf != null) t.setTypeface(tf);
        return t;
    }

    private static FrameLayout.LayoutParams at(int x, int y, int w, int h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.leftMargin = x;
        lp.topMargin = y;
        return lp;
    }
}
