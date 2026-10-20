package com.ansub.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.ByteArrayInputStream;

// A full-screen browser for one website, driven by the remote: the arrows move a highlight between
// the page's links and buttons (see TvNav), OK clicks, Back goes back (and leaves on the first page). Popups, ad redirects to other sites and common ad
// servers are blocked, so free streaming sites stay usable.
public class WebActivity extends Activity {

    // Time spent on each site, for ordering the home screen by use. Kept per host, in its own file.
    static final String USE = "web_use";
    private long shownAt;

    // Ad and popup networks these sites lean on. AdGuard DNS catches most of the rest.
    private static final String[] AD_HOSTS = {"doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "adservice.google.com", "popads.net", "popcash.net", "propellerads.com", "adsterra.com", "exoclick.com",
            "juicyads.com", "onclickads.net", "onclckmn.com", "hilltopads.net", "adcash.com", "a-ads.com",
            "trafficjunky.com", "mgid.com", "taboola.com", "outbrain.com", "revcontent.com", "clickadu.com",
            "pushground.com", "richads.com", "monetag.com", "galaksion.com", "admaven.com", "ad-maven.com",
            "bidgear.com", "yllix.com", "acint.net", "highperformanceformat.com", "profitablecpmrate.com",
            "effectiveratecpm.com", "displaycontentnetwork.com", "dolohen.com", "whos.amung.us"};

    private WebView web;
    private Cursor cursor;
    private FrameLayout stage;
    private View fullscreen;
    private WebChromeClient.CustomViewCallback fullscreenDone;
    private String siteHost;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Uri start = getIntent().getData();
        if (start == null) { finish(); return; }
        siteHost = base(start.getHost());

        stage = new FrameLayout(this);
        stage.setBackgroundColor(Color.BLACK);
        WebView.setWebContentsDebuggingEnabled(true); // lets the Mac inspect pages over adb, for fixing sites
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(true); // then onCreateWindow refuses them: no popups
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        // A desktop browser gets the full site instead of a phone layout.
        s.setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/124.0.0.0 Safari/537.36");
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true); // stay signed in to players

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                // The page itself may only go to the same site. Ads that swap the page for another
                // site are dropped; frames (where the video players live) are left alone.
                if (!r.isForMainFrame() || r.isRedirect()) return false; // server redirects are the site's own
                String h = r.getUrl().getHost();
                return h == null || !base(h).equals(siteHost);
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                String h = r.getUrl().getHost();
                if (h != null) for (String ad : AD_HOSTS)
                    if (h.equals(ad) || h.endsWith("." + ad))
                        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
                return null;
            }

            // Fires for pages that change without reloading, too.
            @Override public void doUpdateVisitedHistory(WebView v, String url, boolean reload) {
                v.evaluateJavascript(TvNav.DESKTOP, null);
            }

            @Override public void onPageCommitVisible(WebView v, String url) {
                v.evaluateJavascript(TvNav.DESKTOP, null);
            }

            @Override public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript(TvNav.DESKTOP, null);
                CookieManager.getInstance().flush();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                if (m.message().startsWith("tvnav") || m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR)
                    android.util.Log.i("tvweb", m.message());
                return true;
            }

            @Override public boolean onCreateWindow(WebView v, boolean dialog, boolean user, Message m) {
                return false; // popup blocked
            }

            // Video full screen: the player takes over the screen, the cursor stays on top.
            @Override public void onShowCustomView(View view, CustomViewCallback done) {
                if (fullscreen != null) { done.onCustomViewHidden(); return; }
                fullscreen = view;
                fullscreenDone = done;
                stage.addView(view, new FrameLayout.LayoutParams(-1, -1));
                cursor.bringToFront();
                web.setVisibility(View.INVISIBLE);
            }

            @Override public void onHideCustomView() {
                if (fullscreen == null) return;
                stage.removeView(fullscreen);
                fullscreen = null;
                fullscreenDone = null;
                web.setVisibility(View.VISIBLE);
            }
        });

        stage.addView(web, new FrameLayout.LayoutParams(-1, -1));
        cursor = new Cursor(this);
        cursor.setAlpha(0f); // the highlight is the default; hold OK for the cursor
        stage.addView(cursor, new FrameLayout.LayoutParams(-1, -1));
        setContentView(stage);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        if (state != null) web.restoreState(state);
        else web.loadUrl(start.toString());
    }

    // "player.example.com" and "example.com" are the same site.
    private static String base(String host) {
        if (host == null) return "";
        String[] p = host.split("\\.");
        return p.length <= 2 ? host : p[p.length - 2] + "." + p[p.length - 1];
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null) web.saveState(out);
    }

    @Override protected void onPause() {
        super.onPause();
        if (shownAt > 0 && siteHost != null) {
            android.content.SharedPreferences use = getSharedPreferences(USE, 0);
            String h = getIntent().getData() == null ? siteHost : getIntent().getData().getHost();
            if (h != null && h.startsWith("www.")) h = h.substring(4);
            use.edit().putLong(h, use.getLong(h, 0) + System.currentTimeMillis() - shownAt).commit();
            shownAt = 0;
        }
        if (web != null) web.onPause();
        CookieManager.getInstance().flush();
    }

    // Leaving the site (Home, another app) closes it fully: the web engine holds ~140 MB, which this
    // 2 GB TV needs more than a page to come back to.
    @Override protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations()) finish();
    }

    @Override protected void onResume() {
        super.onResume();
        shownAt = System.currentTimeMillis();
        if (web != null) web.onResume();
    }

    @Override protected void onDestroy() {
        if (web != null) { stage.removeView(web); web.destroy(); }
        super.onDestroy();
        // The browser has its own process; ending it hands every byte back instead of leaving it cached.
        if (isFinishing()) android.os.Process.killProcess(android.os.Process.myPid());
    }

    // Three ways the remote drives a page:
    //  - highlight (default): arrows jump between the page's links and buttons, OK clicks.
    //  - player: the video fills the screen. OK taps play/pause, arrows go to the player (seek, volume).
    //  - cursor: the old pointer, for odd spots. Hold OK to switch it on or off.
    // Typing in a search box hands the keys to the keyboard until Up or Down leaves the box.
    private boolean cursorMode, playerMode, typing, okHeld;
    private android.widget.TextView hint;

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean arrow = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;

        // Typed characters (a keyboard, or the Mac remote) go into a search box even if none is
        // selected yet: the first one picks the highlighted box, or the page's first one, then all of
        // them are replayed into it in order.
        if (pendingTyping != null) { pendingTyping.add(new KeyEvent(e)); return true; }
        if (!typing && !cursorMode && fullscreen == null && !playerMode && down && isText(e)) {
            pendingTyping = new java.util.ArrayList<>();
            pendingTyping.add(new KeyEvent(e));
            web.evaluateJavascript(FOCUS_FIELD, r -> {
                if ("\"nav\"".equals(r)) { web.postDelayed(this::retryFocus, 1800); return; }
                replayTyping("true".equals(r));
            });
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (!down) return true;
            typing = false;
            if (fullscreenDone != null) fullscreenDone.onCustomViewHidden();
            else if (playerMode) leavePlayer();
            else if (web.canGoBack()) web.goBack();
            else finish();
            return true;
        }
        if (code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_PAGE_UP
                || code == KeyEvent.KEYCODE_CHANNEL_DOWN || code == KeyEvent.KEYCODE_PAGE_DOWN) {
            boolean upKey = code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_PAGE_UP;
            if (down && !playerMode) web.evaluateJavascript("scrollBy({top:" + (upKey ? "-" : "") + "innerHeight*0.85,behavior:'smooth'})", null);
            return true;
        }

        // Hold OK: switch between the highlight and the cursor.
        if (ok && !playerMode && fullscreen == null) {
            if (down && e.getRepeatCount() > 0 && !okHeld && (e.isLongPress()
                    || e.getEventTime() - e.getDownTime() > 700)) {
                okHeld = true;
                cursorMode = !cursorMode;
                if (cursorMode) { js("__tv.clear()"); cursor.wake(); }
                else cursor.setAlpha(0f);
                say(cursorMode ? "Cursor on. Hold OK to go back to the highlight." : "Highlight on. Hold OK for the cursor.");
                return true;
            }
            if (down && e.getRepeatCount() == 0) okHeld = false;
            if (down && e.getRepeatCount() > 0) return true;
            if (okHeld) { if (!down) okHeld = false; return true; }
        }

        if (fullscreen != null || playerMode) {
            // The player has the screen: OK taps its middle (play/pause), arrows go to it.
            if (ok) {
                if (!down) tap(stage.getWidth() / 2f, stage.getHeight() / 2f);
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (cursorMode) {
            if (arrow) { if (down) cursor.move(code, e.getRepeatCount()); return true; }
            if (ok) { if (e.getRepeatCount() == 0) cursor.touch(down); return true; }
            return super.dispatchKeyEvent(e);
        }
        if (typing && (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            boolean r = super.dispatchKeyEvent(e);
            if (!down) { typing = false; hideKeyboard(); }
            return r;
        }
        if (typing) {
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                typing = false;
                js("document.activeElement&&document.activeElement.blur()");
            } else {
                return super.dispatchKeyEvent(e);
            }
        }
        if (arrow) {
            if (!down) return true;
            String dir = code == KeyEvent.KEYCODE_DPAD_UP ? "up" : code == KeyEvent.KEYCODE_DPAD_DOWN ? "down"
                    : code == KeyEvent.KEYCODE_DPAD_LEFT ? "left" : "right";
            js("__tv.move('" + dir + "')");
            return true;
        }
        if (ok) {
            if (down) return true;
            web.evaluateJavascript(TvNav.BOOT + ";__tv.ok()", result -> {
                if ("\"player\"".equals(result)) enterPlayer();
                else if ("\"input\"".equals(result)) typing = true;
                else if (result != null && result.startsWith("\"tap:")) {
                    String[] xy = result.substring(5, result.length() - 1).split(",");
                    try {
                        tap(Float.parseFloat(xy[0]) * web.getWidth(), Float.parseFloat(xy[1]) * web.getHeight());
                    } catch (Exception ignored) {}
                }
            });
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private java.util.List<KeyEvent> pendingTyping;

    private void retryFocus() {
        web.evaluateJavascript(FOCUS_FIELD, r -> replayTyping("true".equals(r)));
    }

    private void replayTyping(boolean focused) {
        java.util.List<KeyEvent> queued = pendingTyping;
        pendingTyping = null;
        if (queued == null) return;
        if (focused) typing = true;
        for (KeyEvent q : queued) super.dispatchKeyEvent(q);
    }

    // Letters, digits, punctuation and space. Not arrows, OK, Back or media keys.
    private static boolean isText(KeyEvent e) {
        int c = e.getUnicodeChar();
        return c != 0 && (c == ' ' || !Character.isISOControl(c));
    }

    private static final String FOCUS_FIELD = "(function(){var a=document.activeElement;"
            + "if(a&&(a.tagName==='INPUT'||a.tagName==='TEXTAREA'||a.isContentEditable))return true;"
            + "var c=document.querySelector('.__tvf');"
            + "var l=[].concat(c&&/^(INPUT|TEXTAREA)$/.test(c.tagName)?[c]:[],"
            + "[].slice.call(document.querySelectorAll('input[type=search],input[type=text],input:not([type]),textarea')));"
            + "for(var i=0;i<l.length;i++){var r=l[i].getBoundingClientRect();if(r.width>20&&r.height>10){"
            + "l[i].focus();l[i].scrollIntoView({block:'center'});return true;}}"
            // No box on this page: follow its Search link, and Java tries again once it loads.
            + "var s=[].slice.call(document.querySelectorAll('a,button,[role=button]')).filter(function(e){"
            + "return /search/i.test((e.getAttribute('href')||'')+' '+(e.getAttribute('aria-label')||'')+' '+e.textContent)"
            + "&&e.getBoundingClientRect().width>0;})[0];if(s){s.click();return 'nav';}return false;})()";

    private void hideKeyboard() {
        android.view.inputmethod.InputMethodManager im =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(web.getWindowToken(), 0);
    }

    private void js(String call) {
        web.evaluateJavascript(TvNav.BOOT + ";" + call, null);
    }

    // The video fills the screen, then a tap in the middle starts it (players need a real tap).
    private void enterPlayer() {
        web.evaluateJavascript(TvNav.BOOT + ";__tv.fs(true)", result -> {
            if (!"true".equals(result)) return;
            playerMode = true;
            web.postDelayed(() -> tap(stage.getWidth() / 2f, stage.getHeight() / 2f), 400);
            say("OK: play or pause.  Left/Right: skip.  Back: leave full screen.");
        });
    }

    private void leavePlayer() {
        playerMode = false;
        js("__tv.fs(false)");
    }

    private void tap(float x, float y) {
        View target = fullscreen != null ? fullscreen : web;
        long now = SystemClock.uptimeMillis();
        for (int action : new int[] {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent ev = MotionEvent.obtain(now, now + (action == MotionEvent.ACTION_UP ? 60 : 0), action, x, y, 0);
            ev.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            target.dispatchTouchEvent(ev);
            ev.recycle();
        }
    }

    // A short message at the bottom of the screen that fades away.
    private void say(String text) {
        if (hint == null) {
            hint = new android.widget.TextView(this);
            hint.setTextColor(Color.WHITE);
            hint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, stage.getWidth() / 1920f * 26);
            int p = Math.round(stage.getWidth() / 1920f * 20);
            hint.setPadding(p * 2, p, p * 2, p);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xD9202024);
            bg.setCornerRadius(p * 2);
            hint.setBackground(bg);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2,
                    android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
            lp.bottomMargin = p * 3;
            stage.addView(hint, lp);
        }
        hint.bringToFront();
        hint.setText(text);
        hint.animate().cancel();
        hint.setAlpha(1f);
        hint.animate().alpha(0f).setStartDelay(3500).setDuration(500).start();
    }

    // The pointer. Moves faster the longer an arrow is held, scrolls the page at the top and bottom
    // edges, and fades out after a few seconds without input.
    private final class Cursor extends View {
        private float x, y;
        private long downAt;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();
        private final Runnable fade = () -> animate().alpha(0f).setDuration(400).start();

        Cursor(Activity a) {
            super(a);
            fill.setColor(Color.WHITE);
            edge.setColor(0xCC000000);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(3);
            edge.setStrokeJoin(Paint.Join.ROUND);
            float u = getResources().getDisplayMetrics().widthPixels / 1920f * 2.2f;
            arrow.moveTo(0, 0);
            arrow.lineTo(0, 17 * u);
            arrow.lineTo(4.5f * u, 13 * u);
            arrow.lineTo(7.5f * u, 20 * u);
            arrow.lineTo(10.5f * u, 19 * u);
            arrow.lineTo(7.5f * u, 12 * u);
            arrow.lineTo(13 * u, 12 * u);
            arrow.close();
            setLayerType(LAYER_TYPE_HARDWARE, null);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            if (x == 0 && y == 0) { x = w / 2f; y = h / 2f; }
        }

        @Override protected void onDraw(Canvas c) {
            c.save();
            c.translate(x, y);
            c.drawPath(arrow, fill);
            c.drawPath(arrow, edge);
            c.restore();
        }

        void wake() {
            removeCallbacks(fade);
            animate().cancel();
            setAlpha(1f);
            postDelayed(fade, 4000);
        }

        void move(int code, int repeat) {
            wake();
            float step = getWidth() / 1920f * Math.min(14 + repeat * 6, 70);
            float dx = code == KeyEvent.KEYCODE_DPAD_LEFT ? -step : code == KeyEvent.KEYCODE_DPAD_RIGHT ? step : 0;
            float dy = code == KeyEvent.KEYCODE_DPAD_UP ? -step : code == KeyEvent.KEYCODE_DPAD_DOWN ? step : 0;
            float nx = x + dx, ny = y + dy;
            int scroll = 0;
            if (ny < 0) { scroll = (int) ny * 4; ny = 0; }
            if (ny > getHeight() - 1) { scroll = (int) (ny - getHeight()) * 4 + 1; ny = getHeight() - 1; }
            x = Math.max(0, Math.min(getWidth() - 1, nx));
            y = ny;
            if (scroll != 0 && fullscreen == null)
                web.evaluateJavascript("window.scrollBy(0," + scroll + ")", null);
            invalidate();
        }

        // OK pressed and released: a tap where the cursor is, on the video or the page.
        void touch(boolean down) {
            wake();
            View target = fullscreen != null ? fullscreen : web;
            long now = SystemClock.uptimeMillis();
            if (down) downAt = now;
            MotionEvent ev = MotionEvent.obtain(downAt, now, down ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_UP, x, y, 0);
            ev.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            target.dispatchTouchEvent(ev);
            ev.recycle();
        }
    }
}
