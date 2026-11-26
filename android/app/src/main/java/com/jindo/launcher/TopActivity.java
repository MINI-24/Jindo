package com.jindo.launcher;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import org.json.JSONObject;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dual-screen mode: the big (top) screen. Shows the selected item's HERO picture, logo and title while
 * the launcher itself (MainActivity, the list) runs on the small screen. Drawn natively: no second WebView.
 * Gamepad keys that land here (this display has focus after a game closes) go to the launcher.
 */
public class TopActivity extends Activity {
    static TopActivity current;
    /** Last state from the page (JSON), so a new instance shows the selection at once */
    static String lastState = "";

    private ImageView hero, heroPrev, logo;
    private TextView title;
    private String shownHero = null, shownLogo = null;
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        current = this;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        heroPrev = newHeroView();
        hero = newHeroView();
        root.addView(heroPrev, match());
        root.addView(hero, match());

        // Same darkening as the single-screen HERO: a left-to-right veil and a bottom fade, so the logo reads
        View side = new View(this);
        side.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xD9000000, 0x40000000, 0x00000000}));
        root.addView(side, match());
        View bottom = new View(this);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xCC000000, 0x33000000, 0x00000000}));
        root.addView(bottom, match());

        logo = new ImageView(this);
        logo.setAdjustViewBounds(true);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(logo, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        title = new TextView(this);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setShadowLayer(18, 0, 4, 0xCC000000);
        title.setMaxLines(2);
        root.addView(title, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Battery and clock in the top-right corner (the small screen gives this room to the list)
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        status.setTypeface(status.getTypeface(), android.graphics.Typeface.BOLD);
        status.setShadowLayer(10, 0, 2, 0xCC000000);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0x66000000);
        pill.setCornerRadius(999);
        status.setBackground(pill);
        int p = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12, getResources().getDisplayMetrics());
        status.setPadding(p, p / 2, p, p / 2);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        slp.setMargins(p * 2, p * 2, p * 2, p * 2);
        root.addView(status, slp);

        setContentView(root);
        hideBars();
        apply(lastState);
        ui.post(tick);
    }

    private TextView status;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            android.os.BatteryManager bm = getSystemService(android.os.BatteryManager.class);
            int pct = bm == null ? -1 : bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
            String time = android.text.format.DateFormat.getTimeFormat(TopActivity.this).format(new java.util.Date());
            status.setText((pct >= 0 ? pct + "%   " : "") + time);
            ui.postDelayed(this, 20000);
        }
    };

    private ImageView newHeroView() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private void hideBars() {
        WindowInsetsControllerCompat c = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        c.hide(WindowInsetsCompat.Type.systemBars());
        c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
    }

    @Override
    protected void onDestroy() {
        if (current == this) current = null;
        ui.removeCallbacks(tick);
        loader.shutdownNow();
        super.onDestroy();
    }

    /** {hero, fallback, logo, x, y, size, title, heroX, heroY} from the page; paths are local files or http(s) URLs */
    void apply(String json) {
        if (json == null || json.isEmpty()) return;
        try {
            JSONObject s = new JSONObject(json);
            String heroPath = s.optString("hero", ""), logoPath = s.optString("logo", "");
            boolean fallback = s.optBoolean("fallback", false);
            placeLogo(s.optString("x", "start"), s.optString("y", "end"), s.optString("size", "l"));
            title.setText(s.optString("title", ""));
            title.setVisibility(logoPath.isEmpty() ? View.VISIBLE : View.GONE);
            if (!logoPath.equals(shownLogo)) {
                shownLogo = logoPath;
                if (logoPath.isEmpty()) logo.setImageDrawable(null);
                else load(logoPath, 1000, bmp -> { if (logoPath.equals(shownLogo)) { logo.setImageBitmap(bmp); fadeIn(logo, 250); } });
            }
            String heroKey = heroPath + (fallback ? "#blur" : "");
            if (!heroKey.equals(shownHero)) {
                shownHero = heroKey;
                if (heroPath.isEmpty()) { swapHero(null, false); return; }
                load(heroPath, 1920, bmp -> { if (heroKey.equals(shownHero)) swapHero(bmp, fallback); });
            }
        } catch (Exception ignored) {}
    }

    // Crossfade: the old picture stays underneath while the new one fades in
    private boolean heroBlurred = false;
    private void swapHero(Bitmap bmp, boolean blur) {
        heroPrev.setImageDrawable(hero.getDrawable());
        if (Build.VERSION.SDK_INT >= 31) {
            heroPrev.setRenderEffect(heroBlurred ? RenderEffect.createBlurEffect(40, 40, Shader.TileMode.CLAMP) : null);
            hero.setRenderEffect(blur ? RenderEffect.createBlurEffect(40, 40, Shader.TileMode.CLAMP) : null);
        }
        heroBlurred = blur;
        hero.setImageBitmap(bmp);
        fadeIn(hero, 450);
    }

    private static void fadeIn(View v, long ms) {
        v.setAlpha(0f);
        v.animate().alpha(1f).setDuration(ms).start();
    }

    // Logo sizes as in the page's LOGO_SIZES: [max height share of the screen, max width share]
    private void placeLogo(String x, String y, String size) {
        float[] hw = "s".equals(size) ? new float[]{0.22f, 0.22f} : "m".equals(size) ? new float[]{0.32f, 0.32f}
                : "xl".equals(size) ? new float[]{0.55f, 0.6f} : new float[]{0.42f, 0.42f};
        int w = getWindow().getDecorView().getWidth(), h = getWindow().getDecorView().getHeight();
        if (w == 0) { w = getResources().getDisplayMetrics().widthPixels; h = getResources().getDisplayMetrics().heightPixels; }
        logo.setMaxWidth((int) (w * hw[1]));
        logo.setMaxHeight((int) (h * hw[0]));
        int g = ("center".equals(x) ? Gravity.CENTER_HORIZONTAL : "end".equals(x) ? Gravity.END : Gravity.START)
                | ("center".equals(y) ? Gravity.CENTER_VERTICAL : "start".equals(y) ? Gravity.TOP : Gravity.BOTTOM);
        int m = (int) (Math.min(w, h) * 0.07f);
        for (View v : new View[]{logo, title}) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
            lp.gravity = g;
            lp.setMargins(m, m, m, m);
            v.setLayoutParams(lp);
        }
    }

    interface Done { void run(Bitmap b); }

    // Decoded off the main thread, scaled down to about the screen size
    private void load(String path, int maxSide, Done done) {
        loader.execute(() -> {
            try {
                Bitmap bmp;
                if (path.startsWith("http://") || path.startsWith("https://")) {
                    HttpURLConnection c = (HttpURLConnection) new URL(path).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(15000);
                    try (InputStream in = c.getInputStream()) { bmp = BitmapFactory.decodeStream(in); }
                } else {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(path, o);
                    int sample = 1;
                    while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2;
                    o = new BitmapFactory.Options();
                    o.inSampleSize = sample;
                    bmp = BitmapFactory.decodeFile(path, o);
                }
                if (bmp != null) { final Bitmap b = bmp; ui.post(() -> { if (!isDestroyed()) done.run(b); }); }
            } catch (Exception ignored) {}
        });
    }

    // Keys and sticks that reach this screen drive the launcher on the other screen
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        MainActivity main = MainActivity.instance;
        if (main != null && !main.isDestroyed() && event.getKeyCode() != KeyEvent.KEYCODE_HOME) return main.dispatchKeyEvent(event);
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        MainActivity main = MainActivity.instance;
        if (main != null && !main.isDestroyed()) return main.dispatchGenericMotionEvent(event);
        return super.dispatchGenericMotionEvent(event);
    }

    // Back on the big screen must not close it (B is the launcher's back)
    @Override
    public void onBackPressed() {}
}
