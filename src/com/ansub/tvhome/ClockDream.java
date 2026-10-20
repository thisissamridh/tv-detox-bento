package com.ansub.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.service.dreams.DreamService;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.io.File;

// The screensaver: Bing's photo of the day, slowly drifting, with the time, date and weather at the
// bottom left. The home screen downloads the photo once a day ahead of time, so this never touches
// the network, and the picture is decoded at half the memory. Without a photo it's a clock on black.
// Any button wakes the TV.
public class ClockDream extends DreamService {

    static final String PHOTO = "wallpaper.jpg";

    private ImageView photo;
    private LinearLayout block;
    private boolean zoomIn;
    private final Runnable kenBurns = new Runnable() {
        @Override public void run() {
            // A slow push in, then out, with a little sideways drift: the photo never sits still,
            // so nothing marks the screen, and the GPU does it for nearly nothing.
            zoomIn = !zoomIn;
            photo.animate().scaleX(zoomIn ? 1.12f : 1.02f).scaleY(zoomIn ? 1.12f : 1.02f)
                    .translationX(zoomIn ? -photo.getWidth() * 0.03f : photo.getWidth() * 0.02f)
                    .setDuration(40_000).start();
            block.animate().translationX(zoomIn ? 18 : 0).translationY(zoomIn ? -10 : 0).setDuration(40_000).start();
            photo.postDelayed(this, 40_000);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        float s = getResources().getDisplayMetrics().widthPixels / 1920f;

        FrameLayout stage = new FrameLayout(this);
        stage.setBackgroundColor(Color.BLACK);

        photo = new ImageView(this);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Bitmap bmp = loadPhoto();
        if (bmp != null) photo.setImageBitmap(bmp);
        photo.setScaleX(1.02f);
        photo.setScaleY(1.02f);
        stage.addView(photo, new FrameLayout.LayoutParams(-1, -1));

        // Dark fade along the bottom and left, so the white text reads on any photo.
        View fade = new View(this);
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BL_TR,
                new int[] {0xD9000000, 0x4D000000, 0x00000000}));
        stage.addView(fade, new FrameLayout.LayoutParams(-1, -1));

        block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("H:mm");
        style(time, 170 * s, 0xF2FFFFFF, Typeface.create("sans-serif-light", Typeface.NORMAL));
        time.setLetterSpacing(-0.03f);
        time.setIncludeFontPadding(false);
        block.addView(time);
        String day = Strings.datePattern();
        TextClock date = new TextClock(this);
        date.setFormat12Hour(day);
        date.setFormat24Hour(day);
        style(date, 36 * s, 0xCCFFFFFF, Typeface.create("sans-serif", Typeface.NORMAL));
        block.addView(date);
        String weather = getSharedPreferences("config", 0).getString("weather", null);
        if (weather != null) {
            TextView w = style(new TextView(this), 36 * s, 0xCCFFFFFF, Typeface.create("sans-serif", Typeface.NORMAL));
            w.setText(weather);
            block.addView(w);
        }
        String caption = getSharedPreferences("config", 0).getString("wallpaper_caption", null);
        if (caption != null && bmp != null) {
            TextView c = style(new TextView(this), 20 * s, 0x99FFFFFF, Typeface.create("sans-serif", Typeface.NORMAL));
            c.setText(caption);
            c.setMaxLines(2);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = Math.round(24 * s);
            block.addView(c, lp);
        }
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(Math.round(1100 * s), -2, Gravity.BOTTOM | Gravity.START);
        bp.leftMargin = Math.round(96 * s);
        bp.bottomMargin = Math.round(80 * s);
        stage.addView(block, bp);
        setContentView(stage);
    }

    private <T extends TextView> T style(T t, float px, int color, Typeface tf) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setSingleLine(t instanceof TextClock);
        t.setShadowLayer(16, 0, 2, 0x99000000);
        return t;
    }

    private Bitmap loadPhoto() {
        File f = new File(getFilesDir(), PHOTO);
        if (!f.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565; // half the memory of full colour, looks the same here
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        photo.post(kenBurns);
    }

    @Override
    public void onDreamingStopped() {
        photo.removeCallbacks(kenBurns);
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        photo.setImageDrawable(null); // let the photo's memory go as soon as the TV wakes
        super.onDetachedFromWindow();
    }
}
