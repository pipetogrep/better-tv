package dev.bettertv.launcher;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;
import android.service.notification.NotificationListenerService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** tvOS-style home screen: blurred backdrop, top shelf title, rounded app tile grid. */
public class MainActivity extends Activity {

    static final int COLS = 6;
    static final String SETTINGS_KEY = "special:settings";
    static final String PRESENT_KEY = "special:present";
    static final String INPUTS_KEY = "special:inputs";
    static final String FOLDER_KEY = "special:folder";
    static final String PREF_ORDER = "order";
    static final String PREF_HIDDEN = "hidden";
    static final String TV_SETTINGS = "com.android.tv.settings";

    /** Packages placed first (in this order) until the user rearranges the grid. */
    static final List<String> DEFAULT_ORDER = Arrays.asList(
            "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom",
            "org.smarttube.stable",
            "in.startv.hotstar",
            "com.apple.atve.androidtv.appletv",
            "com.spotify.tv.android",
            "com.mitv.tvhome.mitvplus",
            "com.google.android.youtube.tv",
            "org.videolan.vlc",
            "io.github.jqssun.airplay");

    static final PathInterpolator EASE = new PathInterpolator(0.2f, 0f, 0f, 1f);
    /** tvOS focused icon is ~1.2x its resting size (303x183 vs 248x148 px in Apple's reference). */
    static final float FOCUS_SCALE = 1.2f;
    /** Focus lift with a slight spring, like tvOS. */
    static final android.view.animation.OvershootInterpolator LIFT =
            new android.view.animation.OvershootInterpolator(1.3f);

    static class AppEntry {
        String key, pkg, label;
        Drawable banner, icon;
        Intent launch;
        int color;
        boolean system;
    }

    /** Scroll position is driven by focus changes, not by ScrollView's own key handling. */
    static class ShelfScrollView extends ScrollView {
        ShelfScrollView(Context c) { super(c); }
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) { return 0; }
        @Override public boolean executeKeyEvent(KeyEvent e) { return false; }
    }

    SharedPreferences prefs;
    final Map<String, AppEntry> all = new LinkedHashMap<>();
    /** Visible home apps, in user order. */
    final List<AppEntry> apps = new ArrayList<>();
    /** Apps the user hid; reachable through the "More Apps" folder tile. */
    final List<AppEntry> hiddenApps = new ArrayList<>();
    /** What the grid currently shows: home apps (+ folder tile) or the folder's contents. */
    final List<AppEntry> shown = new ArrayList<>();
    final List<View> tiles = new ArrayList<>();
    final Handler handler = new Handler(Looper.getMainLooper());

    FrameLayout root;
    ImageView wallView;
    /** The empty space above the dock (the folder's title shows here). */
    FrameLayout shelfView;
    String wallStyle;
    long wallDay;  // the day the photo background was picked for (it changes daily)
    /** What Liquid Glass samples: a blurred copy of the wallpaper. */
    Bitmap wallBlur, glassBg;
    final LiquidGlass.Backdrop glassSource = () -> glassBg;
    View dockRow;
    boolean returningFromSettings;
    final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newSingleThreadExecutor();
    ShelfScrollView scroll;
    LinearLayout content;
    LinearLayout clockBox;
    float unit = 2f;
    int screenW, screenH, tileW, tileH, gap, side, shelfH, homeShelfH, folderShelfH, labelH, radius, dockPadV, dockInset, dockOuter;
    int focusedIndex = 0;
    int moveIndex = -1;
    boolean folderMode;
    boolean resumed;
    ObjectAnimator wiggle;
    AppEntry shelfEntry;
    BroadcastReceiver pkgReceiver;

    /** Full-screen card that zooms out of a tile when an app is launched. */
    View launchOverlay;
    View launchTile;

    final Runnable shelfUpdate = () -> {
        if (focusedIndex >= 0 && focusedIndex < shown.size()) updateShelf(shown.get(focusedIndex));
    };
    final Runnable reloadRun = () -> reload(currentKey());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // The wallpaper/artwork always covers the screen: skip the window's own full-screen fill
        // (this Mali-450 GPU is fill-rate bound, so every hidden full-screen layer costs frames).
        getWindow().setBackgroundDrawable(null);
        prefs = Profiles.homePrefs(this);

        android.graphics.Point size = Ui.screen(this);
        screenW = size.x;
        screenH = size.y;
        unit = Ui.unit(this);
        photoW = screenW;
        photoH = screenH;
        photoCacheDir = getCacheDir();
        // Measured from Apple's tvOS 26 Home Screen (1920x1080): dock 48 px from the screen edges,
        // 42 px inner padding and 51 px between icons; cards are 16:9 to fit Android TV banners.
        dockOuter = dp(24);
        dockInset = dp(21);
        side = dockOuter + dockInset;  // grid rows line up with the dock icons (>= 80 pt safe area)
        gap = dp(25);
        tileW = (screenW - 2 * side - (COLS - 1) * gap) / COLS;
        tileH = tileW * 9 / 16;  // Android TV banners are 16:9 (320x180): shown whole, never cropped
        labelH = dp(30);
        radius = Math.round(tileW * 0.071f);  // Apple's tvOS 26 card corner: ~7% of card width
        homeShelfH = (int) (screenH * 0.5f); // dock top sits at the middle of the screen
        // More Apps: the grid starts just under the status row (weather pill height + margins).
        folderShelfH = dp(24 + TOP_H + 34);
        shelfH = homeShelfH;
        dockPadV = dp(20);

        Clicks.init(this);

        Motion.init(this);
        // Startup: the app list (package manager, banners, icon colours) and the wallpaper are
        // prepared in the background while the window's views are built (~250 ms saved on 1 GHz TVs).
        java.util.concurrent.Future<?> appsTask = io.submit(this::loadApps);
        String wall = prefs.getString("wallpaper", MainActivity.DEFAULT_WALL);
        wallPrefetch = new java.util.concurrent.FutureTask<>(() -> makeWallpaper(wall));
        wallPrefetchStyle = wall;
        new Thread((Runnable) wallPrefetch, "bettertv-wallpaper").start();
        buildChrome();
        try {
            appsTask.get();
        } catch (Exception e) {
            loadApps();
        }
        buildGrid();
        focusIndex(0);
        Motion.cascade(tiles, dp(18));  // first appearance: the apps arrive one after another
        registerPackageReceiver();
        // Start with the TV: only as the home app (opening Better TV by hand mustn't launch anything).
        if (Startup.justBooted() && Perms.isDefaultHome(this)) Startup.onBoot(this);
        wakeReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                Startup.onScreenOn(c);  // also fired by PowerService; repeats are dropped
            }
        };
        registerReceiver(wakeReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // adb perf probe: --es perf weather|status|hero|dockglass|shadows hides one layer to measure its GPU cost.
        String perf = intent.getStringExtra("perf");
        if (perf != null) {
            switch (perf) {
                case "weather": weatherCard.setVisibility(View.GONE); break;
                case "status": clockBox.setVisibility(View.GONE); break;
                case "dockglass": if (dockRow != null) dockRow.setBackground(null); break;
                case "shadows": for (View t : tiles) t.setElevation(0); break;
                default: recreate(); break;
            }
            return;
        }
        // adb hook for screenshots: am start -n dev.bettertv.launcher/.MainActivity --es panel cc|inputs|weather
        String panel = intent.getStringExtra("panel");
        if (panel != null) {
            closeMenu();
            if (panel.equals("inputs")) showInputs(settingsButton);
            else if (panel.equals("weather")) showWeather();
            return;
        }
        // Home always lands on the first app in the dock: close any overlay first
        // (Control Center, profiles, Inputs, weather forecast, menus, move mode, folder).
        if (pickerLayer != null) {
            popoverReturn = null;
            FrameLayout l = pickerLayer;
            pickerLayer = null;
            weatherOpen = false;
            root.removeView(l);
            weatherCard.setAlpha(weatherAlpha());
        }
        closeMenu();
        // Home pressed while already on the home screen: jump back to the top of the home grid.
        if (moveIndex >= 0) endMove();
        if (folderMode) {
            folderMode = false;
            weatherCard.setAlpha(weatherAlpha());
            launchTile = null;
            buildGrid();
            shelfEntry = null;
            scroll.scrollTo(0, 0);
            focusIndex(0);
        } else if (launchOverlay == null) {
            closeMenu();
            goTop();
        }
    }

    /** A different display, resolution or density: rebuild so every size is recomputed. */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        android.graphics.Point size = Ui.screen(this);
        if (size.x != screenW || size.y != screenH || Ui.unit(this) != unit) recreate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        String ws = Profiles.homePrefs(this).getString("wallpaper", MainActivity.DEFAULT_WALL);
        if (!ws.equals(wallStyle) || "photo".equals(ws) && photoDay() != wallDay) {
            wallStyle = ws;
            wallDay = photoDay();
            changeWallpaper(ws);
        }
        if (returningFromSettings && !layoutSig().equals(settingsSig) && !folderMode && launchOverlay == null && moveIndex < 0) {
            reload(currentKey());
        }
        returningFromSettings = false;
        if (launchOverlay != null) finishLaunchReturn();
        // Setup not finished (e.g. Home was pressed during it): pick it up again.
        if (!SetupActivity.done(this)) {
            startActivity(new Intent(this, SetupActivity.class));
            return;
        }
        // Screensaver: Better TV never holds the screen on unless the user turned the screensaver off here.
        if (getSharedPreferences("screensaver", MODE_PRIVATE).getBoolean("allow", true)) {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        updateWeather();
        // Opened from the app list while another launcher is the home screen: offer once to take over.
        // Not asked while Better TV's switch already sends the Home button here (the stock launcher stays default).
        if (SetupActivity.done(this) && !Perms.isDefaultHome(this) && !PowerService.HomeButton.active(this)
                && !prefs.getBoolean("asked_default_home", false)) {
            prefs.edit().putBoolean("asked_default_home", true).apply();
            Clicks.dialog(this)
                    .setTitle("Make Better TV your home screen?")
                    .setMessage("The Home button on the remote will open Better TV.")
                    .setPositiveButton("Make Home Screen", (d, w) -> Perms.requestDefaultHome(this))
                    .setNegativeButton("Not Now", null).show();
        }
        handler.removeCallbacks(weatherTick);
        handler.post(weatherTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        handler.removeCallbacks(weatherTick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();  // a recreate() would otherwise leave this thread behind
        if (pkgReceiver != null) unregisterReceiver(pkgReceiver);
        if (netCallback != null) {
            try {
                getSystemService(android.net.ConnectivityManager.class).unregisterNetworkCallback(netCallback);
            } catch (Exception ignored) {
                // already unregistered
            }
        }
        if (wakeReceiver != null) unregisterReceiver(wakeReceiver);
        handler.removeCallbacksAndMessages(null);
    }

    // ---------------------------------------------------------------- layout

    void buildChrome() {
        root = new FrameLayout(this);
        root.setBackground(null);

        // One ambient wallpaper for every app. It is drawn tiny and upscaled, so it is already
        // soft; a slow drift keeps it alive without a per-frame blur on this weak GPU.
        wallView = new ImageView(this);
        wallView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallStyle = Profiles.homePrefs(this).getString("wallpaper", MainActivity.DEFAULT_WALL);
        wallDay = photoDay();
        setWallpaper(wallStyle);
        root.addView(wallView, match());

        scroll = new ShelfScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        scroll.setFillViewport(true);
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            // The top row rides with the page (as on tvOS): it moves up and away with the apps
            // and glides back down with them, fading as it goes, instead of fading in place.
            float lift = -Math.min(y, topRowTravel());
            clockBox.setTranslationY(lift);
            if (weatherCard != null) weatherCard.setTranslationY(lift);
            if (launchOverlay == null && !statusFocused()) clockBox.setAlpha(clockAlpha());
            if (launchOverlay == null && weatherCard != null && !weatherCard.isFocused()) weatherCard.setAlpha(weatherAlpha());
            if (dockRow != null) dockRow.invalidate();
        });

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setPadding(0, 0, 0, dp(80));
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, match());

        // The empty space above the grid: half the screen on Home, a small top margin in More Apps.
        FrameLayout shelf = new FrameLayout(this);
        shelfView = shelf;
        content.addView(shelf, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, shelfH));

        // tvOS 26 status row: the time and two round buttons, all on the same glass and the same
        // height as the weather pill opposite; buttons turn white and lift when focused.
        int btn = dp(TOP_H);
        clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.HORIZONTAL);
        clockBox.setGravity(Gravity.CENTER_VERTICAL);
        clockBox.setClipChildren(false);
        TextClock time = new TextClock(this);
        Ui.textSize(time, 17);
        time.setTypeface(Ui.font(this, "medium"));
        time.setTextColor(Color.WHITE);
        time.setFormat12Hour("h:mm a");
        time.setFormat24Hour("H:mm");
        time.setAllCaps(true);
        time.setGravity(Gravity.CENTER);
        time.setPadding(dp(18), 0, dp(18), 0);
        time.setBackground(topGlass());
        clockBox.addView(time, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, btn));
        settingsButton = statusButton("gear", btn, this::openSettings);

        // Room around the buttons for their focus growth (1.12x / 1.18x) so nothing is clipped;
        // the margins shrink by the same amount, so the row sits exactly where it did.
        int grow = dp(6);
        clockBox.setPadding(grow, grow, grow, grow);
        clockBox.setClipToPadding(false);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, btn + 2 * grow, Gravity.TOP | Gravity.END);
        clp.topMargin = dp(24) - grow;
        clp.rightMargin = dockOuter - grow;
        root.addView(clockBox, clp);
        watchNetwork();

        // Weather overview in the top-left corner, over the top shelf; OK opens the forecast.
        weatherCard = new WeatherView(this, false, unit);
        weatherGlass = topGlass();
        weatherCard.setBackground(weatherGlass);
        weatherCard.setFocusable(true);
        weatherCard.setClickable(true);
        weatherCard.setOnFocusChangeListener((v, has) -> {
            weatherGlass.setLit(has);
            if (has) v.setAlpha(1f);
            v.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f).translationZ(has ? dp(10) : 0)
                    .setDuration(Motion.d(200)).setInterpolator(LIFT).start();
        });
        weatherCard.setPivotX(0);
        weatherCard.setPivotY(0);
        weatherCard.setOnClickListener(v -> showWeather());
        // Top left, level with the status row and inside the same 48 px edge margin.
        // As wide as its content (the place name varies), like a tvOS capsule.
        FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(WeatherView.COMPACT_H),
                Gravity.TOP | Gravity.START);
        wlp.topMargin = dp(24);
        wlp.leftMargin = dockOuter;
        root.addView(weatherCard, wlp);
        updateWeather();

        setContentView(root);
        Clicks.quiet(root);  // Android's own click sounds off: the Sounds switch decides
    }

    void buildGrid() {
        stopWiggle();
        shown.clear();
        if (folderMode) {
            shown.addAll(hiddenApps);
        } else {
            shown.addAll(apps);
            if (!hiddenApps.isEmpty()) shown.add(folderEntry());
        }
        // Like tvOS, the home grid has no big title; only the folder gets a header.
        // No title or hints in More Apps: the apps are the whole screen.
        shelfH = folderMode ? folderShelfH : homeShelfH;
        ViewGroup.LayoutParams slp = shelfView.getLayoutParams();
        if (slp != null && slp.height != shelfH) {
            slp.height = shelfH;
            shelfView.setLayoutParams(slp);
        }

        // Removing the focused card would make Android hand focus to the first card (and scroll
        // to the top); block that while rebuilding \u2014 callers focus the right card afterwards.
        content.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        while (content.getChildCount() > 1) content.removeViewAt(1);
        tiles.clear();
        LinearLayout row = null;
        for (int i = 0; i < shown.size(); i++) {
            if (i % COLS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                row.setPadding(side, 0, side, 0);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = gap / 2;
                if (i == 0 && hasDock()) {
                    // tvOS 26 dock: the top row sits on one frosted glass panel.
                    row.setPadding(dockInset, dockPadV, dockInset, dockPadV);
                    rlp.leftMargin = side - dockInset;
                    rlp.rightMargin = side - dockInset;
                    rlp.bottomMargin = dockGapBelow();
                    row.setBackground(dockBackground());
                    dockRow = row;
                }
                content.addView(row, rlp);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i % COLS != 0) lp.leftMargin = gap;
            row.addView(makeCell(shown.get(i), i, i < COLS && hasDock()), lp);
        }
        content.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
    }

    View makeCell(AppEntry a, int index, boolean inDock) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setClipChildren(false);

        FrameLayout tile = new FrameLayout(this);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setLongClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(radius);
        // Banners fill the card edge to edge: no dark backing to fringe around the rounded corners.
        bg.setColor(a.banner != null ? Color.TRANSPARENT : a.color);
        tile.setBackground(bg);
        tile.setOutlineProvider(roundedOutline(new float[]{radius}));
        tile.setClipToOutline(true);
        // At rest, no drop shadow (it read as a dark border); the focus lift adds a soft one.
        tile.setElevation(0);
        tile.setForeground(new CardRim(radius, unit));

        ImageView iv = new ImageView(this);
        if (a.banner != null) {
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(a.banner);
            tile.addView(iv, match());
        } else {
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageDrawable(a.icon);
            int s = tileH * 55 / 100;
            tile.addView(iv, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        }

        // tvOS focus has no outline ring: a soft top sheen plus a one-shot glare sweep instead.
        View topSheen = new View(this);
        topSheen.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x1AFFFFFF, 0x00FFFFFF}));
        topSheen.setAlpha(0f);
        tile.addView(topSheen, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tileH / 2, Gravity.TOP));
        View glare = new View(this);
        glare.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x00FFFFFF, 0x1AFFFFFF, 0x00FFFFFF}));
        glare.setRotation(18f);
        glare.setAlpha(0f);
        tile.addView(glare, new FrameLayout.LayoutParams(tileW * 45 / 100, tileH * 2, Gravity.CENTER_VERTICAL));

        TextView label = text(15, Ui.font(this, "medium"), 0f);
        label.setText(a.label);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);

        cell.addView(tile, new LinearLayout.LayoutParams(tileW, tileH));
        if (!inDock) cell.addView(label, new LinearLayout.LayoutParams(tileW, labelH));

        tile.setOnFocusChangeListener((v, has) -> {
            if (has) {
                cell.setTranslationZ(dp(10));
                if (moveIndex >= 0) {
                    // Rebuilt while moving: appear already lifted, or every step would "pop".
                    v.setScaleX(FOCUS_SCALE);
                    v.setScaleY(FOCUS_SCALE);
                    v.setTranslationZ(dp(18));
                    label.setAlpha(1f);
                    label.setTranslationY(tileH * (FOCUS_SCALE - 1f) / 2f);
                } else {
                    v.animate().withLayer().scaleX(FOCUS_SCALE).scaleY(FOCUS_SCALE).translationZ(dp(18)).setDuration(Motion.d(260))
                            .setInterpolator(LIFT).start();
                    label.animate().alpha(1f).translationY(tileH * (FOCUS_SCALE - 1f) / 2f).setDuration(Motion.d(200)).start();
                    // Liquid-glass illumination: a glare sweeps across once, then rests and
                    // drifts with the card's gentle sway (HIG: "gently sways while the surface
                    // illuminates").
                    glare.animate().cancel();
                    glare.setTranslationX(-tileW * 0.7f);
                    glare.setAlpha(1f);
                    glare.animate().translationX(tileW * 1.1f).alpha(0f).setStartDelay(Motion.d(60)).setDuration(Motion.d(700))
                            .setInterpolator(EASE).start();
                }
                topSheen.animate().alpha(1f).setDuration(Motion.d(200)).start();
                onTileFocused(index);
            } else {
                cell.setTranslationZ(0);
                stopSway(v);
                v.animate().withLayer().scaleX(1f).scaleY(1f).translationZ(0).setDuration(Motion.d(180)).setInterpolator(EASE).start();
                label.animate().alpha(0f).translationY(0).setDuration(Motion.d(120)).start();
                topSheen.animate().alpha(0f).setDuration(Motion.d(160)).start();
                glare.animate().cancel();
                glare.setAlpha(0f);
            }
            v.invalidate();
        });
        tile.setCameraDistance(9000 * unit);
        tile.setOnClickListener(v -> {
            if (moveIndex >= 0 || launchOverlay != null) return;
            // Pressed state (HIG): the card dips instantly, then the action runs.
            stopSway(v);
            v.animate().scaleX(FOCUS_SCALE * 0.92f).scaleY(FOCUS_SCALE * 0.92f).setDuration(Motion.d(90))
                    .setInterpolator(EASE).withEndAction(() -> {
                        v.animate().scaleX(FOCUS_SCALE).scaleY(FOCUS_SCALE).setDuration(Motion.d(140)).setInterpolator(LIFT).start();
                        if (FOLDER_KEY.equals(a.key)) enterFolder();
                        else launchAnimated(v, a);
                    }).start();
        });
        tile.setOnLongClickListener(v -> {
            if (moveIndex < 0 && !FOLDER_KEY.equals(a.key)) showOptions(index);
            return true;
        });
        tiles.add(tile);
        return cell;
    }

    /** tvOS-style ambient colour field: soft glows of indigo, teal, magenta and amber on navy. */
    java.util.concurrent.FutureTask<Bitmap> wallPrefetch;
    String wallPrefetchStyle;

    void setWallpaper(String style) {
        Bitmap wp = null;
        if (wallPrefetch != null && style.equals(wallPrefetchStyle)) {
            try {
                wp = wallPrefetch.get();
            } catch (Exception ignored) {
                // generate below
            }
        }
        wallPrefetch = null;
        if (wp == null) wp = makeWallpaper(style);
        wallView.setImageBitmap(wp);
        wallBlur = Ui.glassBackdrop(wp, 192, 108);
        glassBg = wallBlur;
        invalidateGlass();
    }

    /** A new background after start-up: decoded on io, shown when ready (the old one stays till then). */
    void changeWallpaper(String style) {
        io.execute(() -> {
            Bitmap wp = makeWallpaper(style);
            Bitmap blur = Ui.glassBackdrop(wp, 192, 108);
            handler.post(() -> {
                if (isDestroyed() || !style.equals(wallStyle)) return;
                wallView.setImageBitmap(wp);
                wallBlur = blur;
                glassBg = blur;
                invalidateGlass();
            });
        });
    }

    void invalidateGlass() {
        if (dockRow != null) dockRow.invalidate();
        if (clockBox != null) clockBox.invalidate();
        if (weatherCard != null) weatherCard.invalidate();
        if (menuLayer != null && menuLayer.getChildCount() > 0) menuLayer.getChildAt(0).invalidate();
    }

    static final String[] WALL_KEYS = {"black", "graphite", "midnight", "forest", "plum", "slate", "photo"};
    static final String[] WALL_NAMES = {"Black", "Graphite", "Midnight Blue", "Forest", "Plum", "Slate", "Your Photo"};
    /** The solid colours, in WALL_KEYS order (the last key, photo, has none). */
    static final int[] WALL_COLORS = {0xFF000000, 0xFF161618, 0xFF0B1324, 0xFF0C1712, 0xFF170F1C, 0xFF12161C};
    static final String DEFAULT_WALL = "graphite";
    /** Where "Your Photo" comes from: the pictures in this folder, a different one each day. */
    static final String PHOTO_DIR = "/sdcard/Pictures/BetterTV";
    /** The size photos are decoded at: the screen's (1080p or 4K, whatever Android draws apps at). */
    static int photoW = 1920, photoH = 1080;
    /** Today's photo, already cropped to screen size, so a cold start doesn't decode a 4K JPEG. */
    static java.io.File photoCacheDir;

    /** Which picture today: days since the epoch, through the folder in name order. */
    static long photoDay() {
        return (System.currentTimeMillis() + java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis())) / 86_400_000L;
    }

    Bitmap makeWallpaper(String style) {
        return wallpaper(style);
    }

    /** The background for a style, ready to show: solid colours as they are, photos darkened at the bottom. */
    static Bitmap wallpaper(String style) {
        Bitmap b = makeWallpaperBase(style);
        return b.getWidth() > 64 ? bakeShade(b) : b;
    }

    /** The bottom darkening that keeps the dock and labels legible, painted into the image. */
    static Bitmap bakeShade(Bitmap b) {
        Paint p = new Paint(Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, b.getHeight(), new int[]{0x0A000000, 0x33000000, 0x99000000},
                null, Shader.TileMode.CLAMP));
        new Canvas(b).drawRect(0, 0, b.getWidth(), b.getHeight(), p);
        return b;
    }

    /** Today's picture from PHOTO_DIR, decoded at screen size and centre-cropped; null if none. */
    static Bitmap loadPhoto() {
        java.io.File[] files = photoFiles();
        if (files.length == 0) return null;
        return loadPhotoFile(files[(int) (photoDay() % files.length)]);
    }

    /** The pictures in PHOTO_DIR, in name order (empty without access or without pictures). */
    static java.io.File[] photoFiles() {
        java.io.File[] files = new java.io.File(PHOTO_DIR).listFiles((d, n) -> n.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(jpe?g|png|webp)"));
        if (files == null) return new java.io.File[0];
        java.util.Arrays.sort(files);
        return files;
    }

    /** Removes cached crops of pictures that are gone, were replaced, or were cut for another screen size. */
    static void trimPhotoCache() {
        if (photoCacheDir == null) return;
        Set<String> keep = new HashSet<>();
        for (java.io.File f : photoFiles()) keep.add(photoCacheName(f));
        java.io.File[] cached = photoCacheDir.listFiles((d, n) -> n.startsWith("photo-"));
        if (cached != null) for (java.io.File c : cached) if (!keep.contains(c.getName())) c.delete();
    }

    static String photoCacheName(java.io.File f) {
        return "photo-" + f.getName().hashCode() + "-" + f.lastModified() + "-" + photoW + "x" + photoH + ".jpg";
    }

    /** A picture's name for Settings: "06-infra-7110-ridge.jpg" -> "Infra 7110 Ridge". */
    static String photoName(String file) {
        String n = file.replaceFirst("\\.[^.]+$", "").replaceFirst("^[0-9]+[-_ ]+", "").replaceAll("[-_]+", " ").trim();
        StringBuilder b = new StringBuilder();
        for (String w : n.split(" ")) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.length() == 0 ? file : b.toString();
    }

    /** One picture, decoded at screen size and centre-cropped (cached once cropped); null if unreadable. */
    static Bitmap loadPhotoFile(java.io.File f) {
        java.io.File cached = photoCacheDir == null ? null : new java.io.File(photoCacheDir, photoCacheName(f));
        if (cached != null && cached.exists()) {
            android.graphics.BitmapFactory.Options co = new android.graphics.BitmapFactory.Options();
            co.inMutable = true;  // bakeShade paints into it
            Bitmap b = android.graphics.BitmapFactory.decodeFile(cached.getPath(), co);
            if (b != null) return b;
        }
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        int w = photoW, h = photoH;
        while (o.outWidth / (o.inSampleSize * 2) >= w && o.outHeight / (o.inSampleSize * 2) >= h) o.inSampleSize *= 2;
        o.inMutable = true;
        Bitmap raw = android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
        if (raw == null) return null;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        float s = Math.max(w / (float) raw.getWidth(), h / (float) raw.getHeight());
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(s, s);
        m.postTranslate((w - raw.getWidth() * s) / 2f, (h - raw.getHeight() * s) / 2f);
        new Canvas(out).drawBitmap(raw, m, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG));
        raw.recycle();
        if (cached != null) {
            // Written to a temp file and renamed, so a half-written file is never read back.
            java.io.File tmp = new java.io.File(cached.getPath() + ".tmp");
            try (java.io.FileOutputStream os = new java.io.FileOutputStream(tmp)) {
                out.compress(Bitmap.CompressFormat.JPEG, 92, os);
            } catch (Exception e) {
                tmp.delete();
            }
            if (tmp.exists() && !tmp.renameTo(cached)) tmp.delete();
            trimPhotoCache();
        }
        return out;
    }

    /** A solid dark colour (a tiny bitmap, scaled up), or today's photo for "photo". */
    static Bitmap makeWallpaperBase(String style) {
        if (style.startsWith("photo")) {
            try {
                // "photo" rotates daily; "photo:<file>" is one chosen picture.
                Bitmap ph = style.startsWith("photo:")
                        ? loadPhotoFile(new java.io.File(PHOTO_DIR, style.substring(6))) : loadPhoto();
                if (ph != null) return ph;
            } catch (Exception | OutOfMemoryError ignored) {
                // no permission or unreadable: fall back below
            }
            style = DEFAULT_WALL;
        }
        int i = java.util.Arrays.asList(WALL_KEYS).indexOf(style);
        if (i < 0 || i >= WALL_COLORS.length) i = java.util.Arrays.asList(WALL_KEYS).indexOf(DEFAULT_WALL);
        Bitmap b = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888);
        b.eraseColor(WALL_COLORS[i]);
        return b;
    }

    /** System/utility apps that get a drawn tvOS-style card instead of their Android art. */
    static final Set<String> TVOS_CARD_PKGS = new HashSet<>(Arrays.asList(
            TV_SETTINGS, "com.android.vending", "com.mitv.dream"));

    /**
     * tvOS-style card: soft vertical gradient with a top sheen and the app's glyph centred.
     * Settings gets the silver card with a drawn gear, like tvOS.
     */
    /**
     * A banner redrawn once at the size it is shown (the focused, 1.2x card), centre-cropped to
     * 16:9. App banners arrive at 640x360 or larger; holding them at that size cost ~1 MB each.
     */
    Drawable fitBanner(Drawable d) {
        int w = Math.round(tileW * FOCUS_SCALE), h = w * 9 / 16;
        if (d == null || w <= 0) return d;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        Rect old = d.copyBounds();
        if (iw <= 0 || ih <= 0) {
            d.setBounds(0, 0, w, h);
        } else {
            float s = Math.max(w / (float) iw, h / (float) ih);
            int dw = Math.round(iw * s), dh = Math.round(ih * s);
            d.setBounds((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2);
        }
        d.draw(c);
        d.setBounds(old);
        return new BitmapDrawable(getResources(), b);
    }

    Bitmap tvosCard(AppEntry e) {
        int w = 640, h = 360;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        boolean settings = TV_SETTINGS.equals(e.pkg);
        int top, bottom;
        if (settings) {                                  // tvOS Settings: silver
            top = 0xFFE9E9EE;
            bottom = 0xFFAFAFB8;
        } else if ("com.android.vending".equals(e.pkg)) { // tvOS App Store-style blue
            top = 0xFF1FA2FF;
            bottom = 0xFF0A55E6;
        } else {                                         // vivid gradient in the app's own hue
            float[] hsv = new float[3];
            Color.colorToHSV(e.color, hsv);
            float sat = hsv[1] < 0.15f ? 0.12f : 0.7f;   // greys stay neutral
            top = Color.HSVToColor(new float[]{hsv[0], sat * 0.85f, 0.92f});
            bottom = Color.HSVToColor(new float[]{(hsv[0] + 8f) % 360f, Math.min(1f, sat * 1.15f), sat < 0.2f ? 0.42f : 0.68f});
        }
        p.setShader(new LinearGradient(0, 0, 0, h, top, bottom, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);

        if (settings) {
            drawGear(c, w / 2f, h / 2f, h * 0.27f, 0xFF4A4A52, 0xFFD2D2D9);
            return b;
        }
        Bitmap glyph = whiteGlyph(e, (int) (h * 0.5f));
        if (glyph != null) {
            // Soft drop shadow under the glyph, like tvOS system icons.
            Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            sp.setColorFilter(new android.graphics.PorterDuffColorFilter(0x38000000, android.graphics.PorterDuff.Mode.SRC_IN));
            sp.setMaskFilter(new android.graphics.BlurMaskFilter(h * 0.025f, android.graphics.BlurMaskFilter.Blur.NORMAL));
            float x = (w - glyph.getWidth()) / 2f, y = (h - glyph.getHeight()) / 2f;
            c.drawBitmap(glyph.extractAlpha(), x, y + h * 0.015f, sp);
            c.drawBitmap(glyph, x, y, new Paint(Paint.FILTER_BITMAP_FLAG));
        } else if (e.icon != null) {
            int sz = (int) (h * 0.46f);
            Rect old = e.icon.copyBounds();
            e.icon.setBounds((w - sz) / 2, (h - sz) / 2, (w + sz) / 2, (h + sz) / 2);
            e.icon.draw(c);
            e.icon.setBounds(old);
        }
        return b;
    }

    /**
     * The app's glyph as a flat white shape (tvOS system-icon style): uses the adaptive icon's
     * foreground layer when there is one. Returns null for full-bleed artwork that can't be
     * reduced to a glyph, so it is shown as-is instead of becoming a white blob.
     */
    Bitmap whiteGlyph(AppEntry e, int size) {
        if (e.icon == null) return null;
        Drawable d = e.icon;
        float zoom = 1f;
        if (d instanceof android.graphics.drawable.AdaptiveIconDrawable) {
            Drawable fg = ((android.graphics.drawable.AdaptiveIconDrawable) d).getForeground();
            if (fg != null) {
                d = fg;
                zoom = 1.5f;  // adaptive foregrounds keep the glyph in the centre 2/3
            }
        }
        int big = Math.round(size * zoom);
        Bitmap full = Bitmap.createBitmap(big, big, Bitmap.Config.ARGB_8888);
        Rect old = d.copyBounds();
        d.setBounds(0, 0, big, big);
        d.draw(new Canvas(full));
        d.setBounds(old);
        Bitmap g = Bitmap.createBitmap(full, (big - size) / 2, (big - size) / 2, size, size);
        int[] px = new int[size * size];
        g.getPixels(px, 0, size, 0, 0, size, size);
        int solid = 0;
        for (int v : px) if (Color.alpha(v) > 128) solid++;
        if (solid > px.length * 0.6f || solid < px.length * 0.02f) return null;
        for (int i = 0; i < px.length; i++) px[i] = (Color.alpha(px[i]) << 24) | 0xFFFFFF;
        Bitmap out = Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888);
        if (g != full) g.recycle();
        full.recycle();
        return out;
    }

    /** tvOS Settings-style gear: toothed ring with a hollow centre. */
    static void drawGear(Canvas c, float cx, float cy, float r, int color, int holeColor) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        int teeth = 12;
        float tw = r * 0.30f, th = r * 0.30f;
        for (int i = 0; i < teeth; i++) {
            c.save();
            c.rotate(i * 360f / teeth, cx, cy);
            c.drawRoundRect(cx - tw / 2, cy - r - th * 0.55f, cx + tw / 2, cy - r + th, tw * 0.25f, tw * 0.25f, p);
            c.restore();
        }
        c.drawCircle(cx, cy, r, p);
        p.setColor(holeColor);
        c.drawCircle(cx, cy, r * 0.42f, p);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(r * 0.08f);
        c.drawCircle(cx, cy, r * 0.62f, p);
    }

    boolean hasDock() {
        return !folderMode;
    }

    /** The gear in the status row: straight to Better TV's settings. */
    void openSettings() {
        returningFromSettings = true;
        settingsSig = layoutSig();
        startActivity(new Intent(this, SettingsActivity.class));
    }

    String settingsSig = "";

    /** What Settings can change about the grid: order, hidden apps and the Inputs card. */
    String layoutSig() {
        return prefs.getString(PREF_ORDER, "") + "|" + prefs.getStringSet(PREF_HIDDEN, Collections.emptySet())
                + "|" + Inputs.showCard(this);
    }

    /** Height of everything in the top row (weather pill, time, buttons), in design units. */
    static final int TOP_H = (int) WeatherView.COMPACT_H;
    static final int TOP_TINT = 0x38000000;

    /** The one glass for the top row: clear, slightly dark, the same everywhere. */
    LiquidGlass topGlass() {
        LiquidGlass g = new LiquidGlass(dp(TOP_H) / 2f, TOP_TINT, glassSource, screenW, screenH, unit).frosted();
        g.lens = dp(4);
        return g;
    }

    /** A round top-row button: glass at rest, solid white with a dark glyph when focused. */
    ImageView statusButton(String glyph, int size, Runnable action) {
        ImageView b = new ImageView(this);
        b.setScaleType(ImageView.ScaleType.CENTER);
        int gs = size * 46 / 100;
        b.setImageBitmap(Glyphs.get(glyph, gs, Color.WHITE));
        LiquidGlass glass = topGlass();
        b.setBackground(glass);
        b.setFocusable(true);
        b.setClickable(true);
        GradientDrawable lit = new GradientDrawable();
        lit.setShape(GradientDrawable.OVAL);
        lit.setColor(Color.WHITE);
        b.setOnFocusChangeListener((v, has) -> {
            b.setBackground(has ? lit : glass);
            b.setImageBitmap(Glyphs.get(glyph, gs, has ? 0xFF111114 : Color.WHITE));
            v.animate().scaleX(has ? 1.12f : 1f).scaleY(has ? 1.12f : 1f).setDuration(Motion.d(200)).setInterpolator(LIFT).start();
        });
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.leftMargin = dp(12);
        clockBox.addView(b, lp);
        return b;
    }

    int dockGapBelow() {
        return dp(20);
    }

    /** Frosted glass dock (tvOS 26): big radius, dark-tinted translucent fill, sheen and rim. */
    Drawable dockBackground() {
        // Apple's dock corner is ~2x the card corner (36 px vs ~18 px at 1080p).
        LiquidGlass g = new LiquidGlass(radius * 2f, 0x0FFFFFFF, glassSource, screenW, screenH, unit);
        g.lens = dp(12);
        g.rimStrength = 0.25f;  // a faint edge, not a bright outline
        return g;
    }

    Bitmap grainBitmap;

    /** Fine monochrome noise, tiled over glass to give it a frosted texture. */
    Bitmap grain() {
        if (grainBitmap != null) return grainBitmap;
        int n = 96;
        int[] px = new int[n * n];
        java.util.Random rnd = new java.util.Random(7);
        for (int i = 0; i < px.length; i++) {
            int a = rnd.nextInt(14);
            px[i] = rnd.nextBoolean() ? Color.argb(a, 255, 255, 255) : Color.argb(a, 0, 0, 0);
        }
        grainBitmap = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
        return grainBitmap;
    }

    /** A card's glass edge: a hairline of light, brightest along the top, that fades down the sides. */
    static class CardRim extends Drawable {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float r;
        final android.graphics.RectF box = new android.graphics.RectF();

        CardRim(float r, float density) {
            this.r = r;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, 0.8f * density));
        }

        @Override protected void onBoundsChange(Rect b) {
            float in = p.getStrokeWidth() / 2f;
            box.set(b.left + in, b.top + in, b.right - in, b.bottom - in);
            p.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                    new int[]{0x47FFFFFF, 0x14FFFFFF, 0x0AFFFFFF}, new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
        }

        @Override public void draw(Canvas c) {
            c.drawRoundRect(box, r, r, p);
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /**
     * Liquid Glass (iOS/tvOS 26), drawn from what is actually behind the surface: a blurred,
     * colour-boosted copy of the backdrop aligned to the surface's screen position; a band along
     * the edges that magnifies it (lensing, like thick glass); a light tint; and a specular rim
     * that is bright top-left and softer bottom-right. No grain.
     */
    static class LiquidGlass extends Drawable {
        interface Backdrop {
            Bitmap get();
        }

        final float r, density;
        final Backdrop backdrop;
        final int screenW, screenH;
        final int baseTint;
        float lens;
        float rimStrength = 1f;
        // Every layer is a single rounded-rect draw (shader-filled), which the GPU does cheaply;
        // there is no clipping and no off-screen/software rendering at draw time.
        final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final Paint lens1 = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final Paint lens2 = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        final android.graphics.RectF box = new android.graphics.RectF(), tmp = new android.graphics.RectF();
        final android.graphics.Matrix m = new android.graphics.Matrix();
        final int[] loc = new int[2];
        Bitmap shaderFor;
        android.graphics.BitmapShader shader1, shader2, shader3;

        LiquidGlass(float r, int tint, Backdrop backdrop, int screenW, int screenH, float density) {
            this.r = r;
            this.baseTint = tint;
            this.backdrop = backdrop;
            this.screenW = screenW;
            this.screenH = screenH;
            this.density = density;
            fill.setColor(tint);
            lens1.setStyle(Paint.Style.STROKE);
            lens2.setStyle(Paint.Style.STROKE);
            lens1.setAlpha(96);
            lens2.setAlpha(96);
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(0.9f * density);
        }

        boolean lit, adaptive;
        int adaptiveTint, minBlack;
        Bitmap lumFor;
        int lumX = Integer.MIN_VALUE, lumY;

        /** Never lighter than this much black: for text-heavy tiles (Control Center). */
        LiquidGlass darker(int alpha) {
            minBlack = alpha;
            return this;
        }
        final int[] sample = new int[64];

        /**
         * Glass for small controls (Liquid Glass "regular"): the backdrop is desaturated so the
         * control reads as clear glass rather than a coloured blob, and the tint follows the
         * brightness behind it \u2014 darker over bright artwork so white text stays legible, a light
         * frost over dark artwork so the control stays visible.
         */
        LiquidGlass adaptive() {
            adaptive = true;
            android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
            cm.setSaturation(0.4f);
            android.graphics.ColorMatrixColorFilter f = new android.graphics.ColorMatrixColorFilter(cm);
            body.setColorFilter(f);
            lens1.setColorFilter(f);
            lens2.setColorFilter(f);
            return this;
        }

        /** Like adaptive(), but one fixed tint, so neighbouring controls always match. */
        LiquidGlass frosted() {
            adaptive();
            adaptive = false;
            return this;
        }

        void setLit(boolean lit) {
            this.lit = lit;
            fill.setColor(lit ? 0x52FFFFFF : adaptive && adaptiveTint != 0 ? adaptiveTint : baseTint);
            invalidateSelf();
        }

        /** Average luminance (0..1) of the backdrop under this surface, from up to 8x8 samples. */
        float luminanceBehind(Bitmap b) {
            float sx = b.getWidth() / (float) screenW, sy = b.getHeight() / (float) screenH;
            int x0 = Math.max(0, (int) ((loc[0] + box.left) * sx)), y0 = Math.max(0, (int) ((loc[1] + box.top) * sy));
            int x1 = Math.min(b.getWidth() - 1, (int) ((loc[0] + box.right) * sx)), y1 = Math.min(b.getHeight() - 1, (int) ((loc[1] + box.bottom) * sy));
            if (x1 < x0 || y1 < y0) return 0.3f;
            float sum = 0;
            int n = 0;
            for (int j = 0; j < 8; j++) {
                for (int i = 0; i < 8; i++) {
                    int px = b.getPixel(x0 + (x1 - x0) * i / 7, y0 + (y1 - y0) * j / 7);
                    sum += (0.2126f * Color.red(px) + 0.7152f * Color.green(px) + 0.0722f * Color.blue(px)) / 255f;
                    n++;
                }
            }
            return sum / n;
        }

        static int tintFor(float lum) {
            if (lum >= 0.45f) {  // bright: darken, up to 36% black
                int a = Math.round(0x1F + (Math.min(lum, 0.85f) - 0.45f) / 0.4f * (0x5C - 0x1F));
                return a << 24;
            }
            int a = Math.round((0.45f - Math.max(lum, 0.05f)) / 0.4f * 0x16);  // dark: a light frost, not milky
            return (a << 24) | 0xFFFFFF;
        }

        int rimA(int a) {
            return (Math.round(a * rimStrength) << 24) | 0xFFFFFF;
        }

        @Override protected void onBoundsChange(Rect b) {
            box.set(b);
            float h = box.height();
            glow.setShader(new LinearGradient(0, box.top, 0, box.top + h * 0.5f,
                    0x12FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            rim.setShader(new LinearGradient(box.left, box.top, box.right, box.bottom,
                    new int[]{rimA(0x5C), rimA(0x1A), rimA(0x06), rimA(0x0C), rimA(0x2E)},
                    new float[]{0f, 0.22f, 0.5f, 0.78f, 1f}, Shader.TileMode.CLAMP));
            float l = Math.min(lens, Math.min(box.width(), box.height()) / 3f);
            lens1.setStrokeWidth(l);
            lens2.setStrokeWidth(l / 2f);
        }

        /** Maps the full-screen backdrop onto this surface's on-screen spot, optionally magnified. */
        void place(android.graphics.BitmapShader sh, Bitmap b, float zoom) {
            m.reset();
            m.setScale(screenW / (float) b.getWidth(), screenH / (float) b.getHeight());
            m.postTranslate(box.left - loc[0], box.top - loc[1]);
            if (zoom != 1f) m.postScale(zoom, zoom, box.centerX(), box.centerY());
            sh.setLocalMatrix(m);
        }

        @Override public void draw(Canvas c) {
            Bitmap b = backdrop.get();
            Object cb = getCallback();
            if (cb instanceof View) ((View) cb).getLocationOnScreen(loc);
            if (b != null) {
                if (b != shaderFor) {
                    shader1 = new android.graphics.BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    shader2 = new android.graphics.BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    shader3 = new android.graphics.BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    body.setShader(shader1);
                    lens1.setShader(shader2);
                    lens2.setShader(shader3);
                    shaderFor = b;
                }
                if (adaptive && (b != lumFor || loc[0] != lumX || loc[1] != lumY)) {
                    // Only when the backdrop or this surface's position changes, not every frame.
                    lumFor = b;
                    lumX = loc[0];
                    lumY = loc[1];
                    int t = tintFor(luminanceBehind(b));
                    if (minBlack > 0) {
                        boolean black = (t & 0xFFFFFF) == 0;
                        t = (black ? Math.max(t >>> 24, minBlack) : minBlack) << 24;
                    }
                    if (t != adaptiveTint) {
                        adaptiveTint = t;
                        if (!lit) fill.setColor(t);
                    }
                }
                place(shader1, b, 1f);
                c.drawRoundRect(box, r, r, body);
                if (lens > 0) {
                    // Edge lensing: the band along the rim shows the backdrop magnified (two soft steps).
                    float w1 = lens1.getStrokeWidth(), w2 = lens2.getStrokeWidth();
                    place(shader2, b, 1.035f);
                    tmp.set(box.left + w1 / 2, box.top + w1 / 2, box.right - w1 / 2, box.bottom - w1 / 2);
                    c.drawRoundRect(tmp, r - w1 / 2, r - w1 / 2, lens1);
                    place(shader3, b, 1.06f);
                    tmp.set(box.left + w2 / 2, box.top + w2 / 2, box.right - w2 / 2, box.bottom - w2 / 2);
                    c.drawRoundRect(tmp, r - w2 / 2, r - w2 / 2, lens2);
                }
            }
            c.drawRoundRect(box, r, r, fill);
            c.drawRoundRect(box, r, r, glow);
            float in = rim.getStrokeWidth() / 2f;
            c.drawRoundRect(box.left + in, box.top + in, box.right - in, box.bottom - in, r - in, r - in, rim);
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /**
     * Frosted Liquid Glass surface: tinted luminosity lift, fine frost grain, a soft inner glow
     * at the top, gentle shade at the bottom, and a rim that catches light on the top edge and
     * fades down the sides (no uniform outline).
     */
    static class FrostedGlass extends Drawable {
        final float r, density;
        final int tint;
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint grainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        final android.graphics.RectF box = new android.graphics.RectF();
        final android.graphics.Path clip = new android.graphics.Path();
        /** 1 = full light-catching rim (dock); lower for small controls like the capsule. */
        float rimStrength = 1f;

        FrostedGlass(float r, int tint, Bitmap grain, float density) {
            this.r = r;
            this.tint = tint;
            this.density = density;
            fill.setColor(tint);
            grainPaint.setShader(new android.graphics.BitmapShader(grain, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(1.2f * density);
        }

        @Override protected void onBoundsChange(Rect b) {
            box.set(b);
            clip.reset();
            clip.addRoundRect(box, r, r, android.graphics.Path.Direction.CW);
            float h = box.height();
            glow.setShader(new LinearGradient(0, box.top, 0, box.top + h * 0.45f,
                    0x24FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            shade.setShader(new LinearGradient(0, box.bottom - h * 0.4f, 0, box.bottom,
                    0x00000000, 0x1F000000, Shader.TileMode.CLAMP));
            rim.setShader(new LinearGradient(0, box.top, 0, box.bottom,
                    new int[]{rimA(0x52), rimA(0x14), rimA(0x06), rimA(0x0F)},
                    new float[]{0f, 0.25f, 0.75f, 1f}, Shader.TileMode.CLAMP));
        }

        int rimA(int a) {
            return (Math.round(a * rimStrength) << 24) | 0xFFFFFF;
        }

        @Override public void draw(Canvas c) {
            c.save();
            c.clipPath(clip);
            c.drawRect(box, fill);
            c.drawRect(box, grainPaint);
            c.drawRect(box, glow);
            c.drawRect(box, shade);
            c.restore();
            float inset = rim.getStrokeWidth() / 2f;
            c.drawRoundRect(box.left + inset, box.top + inset, box.right - inset, box.bottom - inset,
                    r - inset, r - inset, rim);
        }

        /** Focused: brighter glass (tvOS lifts and lightens focused glass controls). */
        void setLit(boolean lit) {
            fill.setColor(lit ? 0x59FFFFFF : tint);
            invalidateSelf();
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }

        @Override public boolean getPadding(Rect padding) {
            padding.set(0, 0, 0, 0);
            return false;
        }
    }

    /** Outline whose corner radius can be changed while animating (radius[0]). */
    ViewOutlineProvider roundedOutline(float[] r) {
        return new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r[0]);
            }
        };
    }

    // ---------------------------------------------------------------- suggestions carousel

    /** Remote keys for the top row (weather pill, Control Center) and the dock's edges. */
    boolean navKeys(KeyEvent ev) {
        if (moveIndex >= 0) return false;
        int k = ev.getKeyCode();
        boolean down = ev.getAction() == KeyEvent.ACTION_DOWN;
        View f = getCurrentFocus();
        if (f == settingsButton) {
            if (k == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (down && weatherShown()) weatherCard.requestFocus();
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_BACK) {
                if (down) focusIndex(Math.min(focusedIndex, COLS - 1));
                return true;
            }
            return k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_DPAD_UP;
        }
        if (f == weatherCard) {
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_RIGHT: if (down) focusTop(); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_BACK:
                    if (down) focusIndex(Math.min(focusedIndex, COLS - 1));
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    return true;
                default: return false;
            }
        }
        int i = tiles.indexOf(f);
        if (i >= 0 && ((k == KeyEvent.KEYCODE_DPAD_RIGHT && (i % COLS == COLS - 1 || i == tiles.size() - 1))
                || (k == KeyEvent.KEYCODE_DPAD_LEFT && i % COLS == 0))) {
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_UP && i >= 0 && i < COLS) {
            if (down) focusTop();
            return true;
        }
        return false;
    }

    boolean weatherShown() {
        return weatherCard != null && weatherCard.getVisibility() == View.VISIBLE && weatherAlpha() > 0.5f;
    }

    /** Up from the shelf/dock: the tab bar, or the status row when the tab bar is hidden. */
    void focusTop() {
        settingsButton.requestFocus();
    }

    /**
     * Artwork transition (tvOS carousel): the new image slides in from the side you pressed and
     * fades up over the old one, which drifts the other way and fades out.
     */
    ObjectAnimator heroZoom;

    /** Carousel focused: the dock slides away and the artwork takes the whole screen (tvOS Top Shelf). */
    boolean heroRequested;

    ImageView settingsButton;
    android.net.ConnectivityManager.NetworkCallback netCallback;
    WeatherView weatherCard;
    LiquidGlass weatherGlass;
    int avatarSize;
    FrameLayout pickerLayer;
    BroadcastReceiver wakeReceiver;


    // ---------------------------------------------------------------- focus sway

    ValueAnimator sway;
    View swayView;

    void stopSway(View v) {
        if (sway != null && (v == null || v == swayView)) {
            sway.cancel();
            sway = null;
            if (swayView != null) {
                swayView.animate().rotationX(0).rotationY(0).setDuration(Motion.d(160)).start();
            }
            swayView = null;
        }
    }

    // ---------------------------------------------------------------- launch animation

    /**
     * tvOS-style launch: a full-screen card in the app's colour grows out of the tile while the
     * grid recedes; the app is started near the end of the zoom and cross-fades in.
     */
    void launchAnimated(View tile, AppEntry a) {
        if (launchOverlay != null) return;
        if (INPUTS_KEY.equals(a.key)) {
            showInputs(tile);
            return;
        }

        int[] loc = new int[2];
        tile.getLocationInWindow(loc);
        int[] rootLoc = new int[2];
        root.getLocationInWindow(rootLoc);
        float vx = loc[0] - rootLoc[0];
        float vy = loc[1] - rootLoc[1];
        float sx0 = tile.getWidth() * tile.getScaleX() / root.getWidth();
        float sy0 = tile.getHeight() * tile.getScaleY() / root.getHeight();

        // The card exactly as it looks on screen right now (scaled by focus/press).
        RectF card = new RectF(vx, vy, vx + tile.getWidth() * tile.getScaleX(),
                vy + tile.getHeight() * tile.getScaleY());
        int startColor = a.banner != null ? edgeColor(a) : a.color;
        int endColor = launchBackground(a, startColor);
        LaunchCard art = new LaunchCard(card, radius * tile.getScaleX(),
                a.banner != null ? bannerArt(a) : null, copyOf(a.icon), startColor,
                // Icon apps fill with a flat colour, so they can hand off to the app's own colour.
                a.banner == null && colorDistance(startColor, endColor) > 12 ? endColor : startColor);
        View ov = new View(this);
        ov.setBackground(art);
        ov.setElevation(dp(24));
        root.addView(ov, new FrameLayout.LayoutParams(root.getWidth(), root.getHeight()));
        launchOverlay = ov;
        launchTile = tile;
        tile.setAlpha(0f);

        ValueAnimator zoom = ValueAnimator.ofFloat(0f, 1f);
        zoom.setDuration(Motion.d(480));
        zoom.setInterpolator(EASE);
        zoom.addUpdateListener(an -> art.setProgress((float) an.getAnimatedValue()));
        zoom.start();

        scroll.animate().withLayer().scaleX(0.9f).scaleY(0.9f).alpha(0f).setDuration(Motion.d(420)).setInterpolator(EASE)
                .setUpdateListener(null).start();
        clockBox.animate().alpha(0f).setDuration(Motion.d(250)).start();
        weatherCard.animate().alpha(0f).setDuration(Motion.d(250)).start();

        handler.postDelayed(launchRun = () -> {
            try {
                Bundle opts = ActivityOptions.makeCustomAnimation(
                        this, R.anim.app_enter, R.anim.home_hold).toBundle();
                startActivity(a.launch, opts);
                noteLaunched(a.pkg);
            } catch (ActivityNotFoundException | SecurityException e) {
                toast("Can't open " + a.label);
                finishLaunchReturn();
            }
        }, 380);
    }

    Runnable launchRun;

    /** Coming back to Home after a launch: the card fades away and the grid zooms back in. */
    void finishLaunchReturn() {
        View ov = launchOverlay;
        View tile = launchTile;
        launchOverlay = null;
        launchTile = null;
        if (ov == null) return;
        if (launchRun != null) handler.removeCallbacks(launchRun);
        launchRun = null;

        scroll.setScaleX(1.06f);
        scroll.setScaleY(1.06f);
        scroll.animate().withLayer().scaleX(1f).scaleY(1f).alpha(1f).setDuration(Motion.d(420)).setInterpolator(EASE)
                .setUpdateListener(null).withEndAction(this::invalidateGlass).start();
        clockBox.animate().alpha(clockAlpha()).setDuration(Motion.d(420)).start();
        weatherCard.animate().alpha(weatherAlpha()).setDuration(Motion.d(420)).start();
        if (tile != null) tile.animate().alpha(1f).setDuration(Motion.d(300)).start();
        ov.animate().withLayer().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(Motion.d(300))
                .setListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator animation) {
                        root.removeView(ov);
                    }
                }).start();
        if (tile != null) tile.requestFocus();
    }

    /**
     * The colour the app paints first: its Android 12 splash background, else its window
     * background. Ending the zoom on this colour makes the handoff to the app seamless.
     */
    int launchBackground(AppEntry a, int fallback) {
        ComponentName cn = a.launch.getComponent();
        if (cn == null) return fallback;
        try {
            ActivityInfo ai = getPackageManager().getActivityInfo(cn, 0);
            Context pc = createPackageContext(cn.getPackageName(), 0);
            Resources.Theme theme = pc.getResources().newTheme();
            theme.applyStyle(ai.getThemeResource(), true);
            int[] attrs = Build.VERSION.SDK_INT >= 31
                    ? new int[]{android.R.attr.windowSplashScreenBackground, android.R.attr.windowBackground}
                    : new int[]{android.R.attr.windowBackground};
            TypedArray ta = theme.obtainStyledAttributes(attrs);
            try {
                for (int i = 0; i < attrs.length; i++) {
                    TypedValue v = ta.peekValue(i);
                    if (v == null) continue;
                    int color = 0;
                    if (v.type >= TypedValue.TYPE_FIRST_COLOR_INT && v.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                        color = v.data;
                    } else {
                        Drawable d = ta.getDrawable(i);
                        if (d instanceof ColorDrawable) color = ((ColorDrawable) d).getColor();
                    }
                    if (Color.alpha(color) > 200) return color | 0xFF000000;
                }
            } finally {
                ta.recycle();
            }
        } catch (Exception e) {
            // Theme unreadable: keep the banner colour.
        }
        return fallback;
    }

    static int colorDistance(int x, int y) {
        return Math.abs(Color.red(x) - Color.red(y)) + Math.abs(Color.green(x) - Color.green(y))
                + Math.abs(Color.blue(x) - Color.blue(y));
    }

    static int blend(int x, int y, float t) {
        return Color.rgb(
                (int) (Color.red(x) + (Color.red(y) - Color.red(x)) * t),
                (int) (Color.green(x) + (Color.green(y) - Color.green(x)) * t),
                (int) (Color.blue(x) + (Color.blue(y) - Color.blue(x)) * t));
    }

    /** Colour along the banner's edges, so the zoomed card blends with the banner art. */
    int edgeColor(AppEntry a) {
        Bitmap b = backdropSharp(a, 96, 54);
        int w = b.getWidth() - 3, h = b.getHeight() - 3;
        int[] px = {b.getPixel(2, 2), b.getPixel(w, 2), b.getPixel(2, h), b.getPixel(w, h),
                b.getPixel(w / 2, 2), b.getPixel(w / 2, h)};
        int r = 0, g = 0, bl = 0;
        for (int p : px) {
            r += Color.red(p);
            g += Color.green(p);
            bl += Color.blue(p);
        }
        b.recycle();
        return Color.rgb(r / px.length, g / px.length, bl / px.length);
    }

    /** Banner rendered sharp, minus a hairline border so anti-aliased edges don't get extended. */
    Bitmap bannerArt(AppEntry a) {
        int w = 640, h = 360, in = 3;
        Bitmap full = backdropSharp(a, w, h);
        Bitmap art = Bitmap.createBitmap(full, in, in, w - 2 * in, h - 2 * in);
        if (art != full) full.recycle();
        return art;
    }

    /**
     * The launch card, drawn in one pass so nothing distorts: a rounded rect that grows from the
     * card's on-screen rect to full screen while its corners flatten. Banner apps draw the banner
     * centre-cropped exactly like the card, with its edge pixels clamped outward, so the whole
     * screen continues the card's own background; the logo settles at ~42% of the screen.
     */
    static class LaunchCard extends Drawable {
        final RectF card;
        final float r0;
        final Bitmap art;
        final Drawable icon;
        final int color0, color1;
        final Paint artPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final android.graphics.Matrix m = new android.graphics.Matrix();
        final android.graphics.Path clip = new android.graphics.Path();
        final RectF o = new RectF(), br = new RectF();
        float f;

        LaunchCard(RectF card, float r0, Bitmap art, Drawable icon, int color0, int color1) {
            this.card = card;
            this.r0 = r0;
            this.art = art;
            this.icon = icon;
            this.color0 = color0;
            this.color1 = color1;
            if (art != null) {
                artPaint.setShader(new android.graphics.BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            }
        }

        void setProgress(float f) {
            this.f = f;
            invalidateSelf();
        }

        static float lerp(float a, float b, float t) {
            return a + (b - a) * t;
        }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            float W = b.width(), H = b.height();
            o.set(lerp(card.left, 0, f), lerp(card.top, 0, f), lerp(card.right, W, f), lerp(card.bottom, H, f));
            float rad = r0 * (1f - f);
            if (art != null) {
                // Banner rect: starts as the card itself, ends centred at 42% of the screen.
                float bw = lerp(card.width(), W * 0.42f, f), bh = lerp(card.height(), H * 0.42f, f);
                br.set(o.centerX() - bw / 2, o.centerY() - bh / 2, o.centerX() + bw / 2, o.centerY() + bh / 2);
                float scale = Math.max(br.width() / art.getWidth(), br.height() / art.getHeight());
                m.setScale(scale, scale);
                m.postTranslate(br.centerX() - art.getWidth() * scale / 2, br.centerY() - art.getHeight() * scale / 2);
                artPaint.getShader().setLocalMatrix(m);
                c.drawRoundRect(o, rad, rad, artPaint);
            } else {
                float t = Math.max(0f, Math.min(1f, (f - 0.55f) / 0.45f));
                fill.setColor(blend(color0, color1, t));
                c.drawRoundRect(o, rad, rad, fill);
                if (icon != null) {
                    float s = lerp(card.height() * 0.55f, H * 0.27f, f);
                    Rect old = icon.copyBounds();
                    icon.setBounds((int) (o.centerX() - s / 2), (int) (o.centerY() - s / 2),
                            (int) (o.centerX() + s / 2), (int) (o.centerY() + s / 2));
                    icon.draw(c);
                    icon.setBounds(old);
                }
            }
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    Drawable copyOf(Drawable d) {
        Drawable.ConstantState cs = d.getConstantState();
        return cs != null ? cs.newDrawable(getResources()).mutate() : d;
    }

    /** How far the top row travels with the page before it is gone (just past its own height). */
    int topRowTravel() {
        return dp(24 + TOP_H + 24);
    }

    /** The top row fades over the same distance it travels, so it leaves and returns as one motion. */
    float clockAlpha() {
        return Math.max(0f, 1f - scroll.getScrollY() / (float) topRowTravel());
    }

    ValueAnimator scrollAnim;

    /** Page scrolling: a soft start and a long settle, so the whole page glides rather than jumps. */
    static final PathInterpolator GLIDE = new PathInterpolator(0.33f, 0f, 0.15f, 1f);

    /**
     * Scrolls the page over a fixed, unhurried time (0.4-0.55 s by distance) rather than
     * ScrollView's short fling. Motion "Reduced" doesn't shorten it (it is the main movement on
     * screen, and too short reads as a jump); Motion "Off" makes it instant.
     */
    void glideTo(int y) {
        if (scrollAnim != null) scrollAnim.cancel();
        int from = scroll.getScrollY();
        if (from == y) return;
        if (Motion.level() == Motion.OFF) {
            scroll.scrollTo(0, y);
            return;
        }
        long ms = Math.min(550, 400 + Math.abs(y - from) / 6);
        scrollAnim = ValueAnimator.ofInt(from, y);
        scrollAnim.setDuration(ms);
        scrollAnim.setInterpolator(GLIDE);
        scrollAnim.addUpdateListener(a -> scroll.scrollTo(0, (int) a.getAnimatedValue()));
        scrollAnim.start();
    }

    // ---------------------------------------------------------------- focus & shelf

    void onTileFocused(int index) {
        focusedIndex = index;
        boolean dock = hasDock() && index < COLS;
        scrollToRow(index / COLS);
        handler.removeCallbacks(shelfUpdate);
        handler.postDelayed(shelfUpdate, 90);
    }

    /**
     * Row tops come from the fixed grid geometry rather than getTop(), so this also works right
     * after buildGrid() (e.g. while moving a card), before the new rows have been laid out.
     */
    void scrollToRow(int row) {
        int rowH = tileH + labelH + gap / 2;
        int rowTop = !hasDock() ? shelfH + row * rowH
                : row == 0 ? shelfH
                : shelfH + tileH + 2 * dockPadV + dockGapBelow() + (row - 1) * rowH;
        int target = row == 0 ? 0 : rowTop - (int) (screenH * 0.30f);
        glideTo(Math.max(0, target));
    }

    void updateShelf(AppEntry a) {
        shelfEntry = a;
    }

    void focusIndex(int i) {
        if (tiles.isEmpty()) return;
        i = Math.max(0, Math.min(i, tiles.size() - 1));
        tiles.get(i).requestFocus();
        focusedIndex = i;
        updateShelf(shown.get(i));
        int row = i / COLS;
        scroll.post(() -> scrollToRow(row));
    }

    void goTop() {
        glideTo(0);
        focusIndex(0);
    }

    String currentKey() {
        return focusedIndex >= 0 && focusedIndex < shown.size() ? shown.get(focusedIndex).key : null;
    }

    int indexOfKey(String key) {
        for (int j = 0; j < shown.size(); j++) if (shown.get(j).key.equals(key)) return j;
        return -1;
    }

    // ---------------------------------------------------------------- folder of hidden apps

    AppEntry folderEntry() {
        AppEntry f = new AppEntry();
        f.key = FOLDER_KEY;
        f.label = "More Apps";
        f.color = 0xFF3A3A3C;
        StringBuilder sig = new StringBuilder();
        for (AppEntry e : hiddenApps) sig.append(e.key).append(',');
        if (folderBanner == null || !sig.toString().equals(folderSig)) {
            folderBanner = new BitmapDrawable(getResources(), folderArt());
            folderSig = sig.toString();
        }
        f.banner = folderBanner;
        f.icon = f.banner;
        return f;
    }

    /**
     * Folder card, as tvOS draws folders: a neutral dark card holding miniatures of the apps
     * inside (up to nine, 3x3, each in its own 16:9 banner shape); empty slots are faint.
     */
    Drawable folderBanner;
    String folderSig;

    Bitmap folderArt() {
        int w = 640, h = 360;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h, 0xFF3A3A3F, 0xFF242428, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);

        // Nine 16:9 miniatures, the same shape as the cards themselves, centred in the card.
        int gap = 14;
        float ch = (h - 2 * 36 - 2 * gap) / 3f, cw = ch * 16 / 9f, r = cw * 0.07f;
        float padX = (w - 3 * cw - 2 * gap) / 2f, padY = (h - 3 * ch - 2 * gap) / 2f;
        Paint empty = new Paint(Paint.ANTI_ALIAS_FLAG);
        empty.setColor(0x14FFFFFF);
        android.graphics.Path clip = new android.graphics.Path();
        for (int i = 0; i < 9; i++) {
            float x = padX + (i % 3) * (cw + gap), y = padY + (i / 3) * (ch + gap);
            AppEntry e = i < hiddenApps.size() ? hiddenApps.get(i) : null;
            Drawable d = e == null ? null : e.banner != null ? e.banner : e.icon;
            if (d == null) {
                c.drawRoundRect(x, y, x + cw, y + ch, r, r, empty);
                continue;
            }
            c.save();
            clip.reset();
            clip.addRoundRect(x, y, x + cw, y + ch, r, r, android.graphics.Path.Direction.CW);
            c.clipPath(clip);
            c.drawColor(e.banner != null ? 0xFF1C1C1E : e.color);
            Rect old = d.copyBounds();
            if (e.banner != null) {
                d.setBounds(Math.round(x), Math.round(y), Math.round(x + cw), Math.round(y + ch));
            } else {
                float s = ch * 0.6f;
                d.setBounds(Math.round(x + (cw - s) / 2), Math.round(y + (ch - s) / 2),
                        Math.round(x + (cw + s) / 2), Math.round(y + (ch + s) / 2));
            }
            d.draw(c);
            d.setBounds(old);
            c.restore();
        }
        return b;
    }

    Bitmap backdropSharp(AppEntry e, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(e.banner != null ? 0xFF1C1C1E : e.color);
        Drawable d = e.banner != null ? e.banner : e.icon;
        if (d != null) {
            Rect old = d.copyBounds();
            if (e.banner != null) {
                d.setBounds(0, 0, w, h);
            } else {
                int s = h * 6 / 10;
                d.setBounds((w - s) / 2, (h - s) / 2, (w + s) / 2, (h + s) / 2);
            }
            d.draw(c);
            d.setBounds(old);
        }
        return b;
    }

    void enterFolder() {
        if (hiddenApps.isEmpty()) return;
        animateGridSwap(() -> {
            folderMode = true;
            weatherCard.setAlpha(0f);
            buildGrid();
            shelfEntry = null;
            scroll.scrollTo(0, 0);
            focusIndex(0);
        });
    }

    void exitFolder() {
        animateGridSwap(() -> {
            folderMode = false;
            weatherCard.animate().alpha(weatherAlpha()).setDuration(Motion.d(250)).start();
            buildGrid();
            shelfEntry = null;
            int i = indexOfKey(FOLDER_KEY);
            focusIndex(i >= 0 ? i : 0);
        });
    }

    /** Quick zoom-through between home and folder, so it reads as "opening" the folder. */
    void animateGridSwap(Runnable swap) {
        scroll.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(Motion.d(140)).setInterpolator(EASE)
                .withEndAction(() -> {
                    swap.run();
                    scroll.setScaleX(1.04f);
                    scroll.setScaleY(1.04f);
                    scroll.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.d(260)).setInterpolator(EASE)
                            .withEndAction(null).start();
                }).start();
    }

    // ---------------------------------------------------------------- keys

    /** Unused keys go to the views; a D-pad press nothing used moves focus here (no Android sound). */
    boolean superDispatch(KeyEvent ev) {
        return super.dispatchKeyEvent(ev) || Clicks.moveFocus(getWindow(), ev);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        if (launchOverlay != null) return true;
        if (swallowUp && ev.getAction() == KeyEvent.ACTION_UP) {
            swallowUp = false;
            return true;
        }
        if (!replayingBack) navSound(this, ev);  // a replayed Back already clicked
        if (pickerLayer != null && menuLayer == null) {
            int pk = ev.getKeyCode();
            boolean pd = ev.getAction() == KeyEvent.ACTION_DOWN;
            if (pk == KeyEvent.KEYCODE_BACK) {
                if (!pd) closeProfilePicker();
                return true;
            }
            if (pk == KeyEvent.KEYCODE_DPAD_UP || pk == KeyEvent.KEYCODE_DPAD_DOWN
                    || pk == KeyEvent.KEYCODE_DPAD_LEFT || pk == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (pd) pickerMove(pk);
                return true;
            }
            return superDispatch(ev);
        }
        if (menuLayer != null) {
            int mk = ev.getKeyCode();
            if (mk == KeyEvent.KEYCODE_BACK || mk == KeyEvent.KEYCODE_MENU || mk == KeyEvent.KEYCODE_ESCAPE) {
                if (ev.getAction() == KeyEvent.ACTION_UP) closeMenu();
                return true;
            }
            return superDispatch(ev);
        }
        if (navKeys(ev)) return true;
        int k = ev.getKeyCode();
        boolean down = ev.getAction() == KeyEvent.ACTION_DOWN;

        if (moveIndex >= 0) {
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_LEFT:   if (down) moveTo(moveIndex - 1); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:  if (down) moveTo(moveIndex + 1); return true;
                case KeyEvent.KEYCODE_DPAD_UP:     if (down) moveTo(moveIndex - COLS); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:   if (down) moveTo(moveIndex + COLS); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_ESCAPE:
                    if (down) {
                        endMove();
                        swallowUp = true;  // the release must not go to the top or open the app
                    }
                    return true;
                default: return superDispatch(ev);
            }
        }
        if (k == KeyEvent.KEYCODE_MENU) {
            if (!down) {
                int i = tiles.indexOf(getCurrentFocus());
                if (i >= 0 && !FOLDER_KEY.equals(shown.get(i).key)) showOptions(i);
            }
            return true;
        }
        if (k == KeyEvent.KEYCODE_BACK) {
            if (!down) {
                if (folderMode) exitFolder();
                else goTop();
            }
            return true;
        }
        return superDispatch(ev);
    }

    // ---------------------------------------------------------------- apps

    /** A freshly read app list, built without touching the live one (safe off the main thread). */
    static final class AppList {
        final Map<String, AppEntry> all = new LinkedHashMap<>();
        final List<AppEntry> apps = new ArrayList<>(), hiddenApps = new ArrayList<>();
        List<Inputs.Input> inputs = new ArrayList<>();
    }

    /** Reads the apps on this TV (PackageManager, banners, cards): slow, so callers run it on io. */
    AppList readApps() {
        AppList l = new AppList();
        Map<String, AppEntry> all = l.all;
        List<AppEntry> apps = l.apps, hiddenApps = l.hiddenApps;
        PackageManager pm = getPackageManager();
        Set<String> pkgs = new HashSet<>();
        addResolved(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, pkgs, true, all);
        addResolved(pm, Intent.CATEGORY_LAUNCHER, pkgs, false, all);
        if (!pkgs.contains(TV_SETTINGS)) addSettingsEntry(pm, all);
        l.inputs = Inputs.list(this);
        if (Inputs.showCard(this) && !l.inputs.isEmpty()) addInputsEntry(all);
        for (AppEntry e : all.values()) {
            if (e.banner == null || TVOS_CARD_PKGS.contains(e.pkg)) {
                e.banner = new BitmapDrawable(getResources(), tvosCard(e));
            }
            e.banner = fitBanner(e.banner);
        }

        Set<String> hidden = prefs.getStringSet(PREF_HIDDEN, Collections.emptySet());
        Map<String, AppEntry> pool = new LinkedHashMap<>();
        for (AppEntry e : all.values()) {
            if (hidden.contains(e.key)) hiddenApps.add(e);
            else pool.put(e.key, e);
        }
        Collections.sort(hiddenApps, (x, y) -> x.label.compareToIgnoreCase(y.label));

        for (String key : prefs.getString(PREF_ORDER, "").split(",")) {
            AppEntry e = pool.remove(key);
            if (e != null) apps.add(e);
        }
        List<AppEntry> rest = new ArrayList<>(pool.values());
        Collections.sort(rest, (x, y) -> {
            int px = priority(x), py = priority(y);
            return px != py ? Integer.compare(px, py) : x.label.compareToIgnoreCase(y.label);
        });
        apps.addAll(rest);
        // Inputs starts as the first card in the dock until it is moved.
        if (!Arrays.asList(prefs.getString(PREF_ORDER, "").split(",")).contains(INPUTS_KEY)) {
            for (int i = 0; i < apps.size(); i++) {
                if (INPUTS_KEY.equals(apps.get(i).key)) {
                    apps.add(0, apps.remove(i));
                    break;
                }
            }
        }
            return l;
    }

    /** Makes a read list the live one (main thread, or before the UI exists). */
    void applyApps(AppList l) {
        all.clear();
        all.putAll(l.all);
        apps.clear();
        apps.addAll(l.apps);
        hiddenApps.clear();
        hiddenApps.addAll(l.hiddenApps);
        inputs = l.inputs;
    }

    void loadApps() {
        applyApps(readApps());
    }


    /** Fallback Settings tile for TVs whose settings app has no launcher entry. */
    void addSettingsEntry(PackageManager pm, Map<String, AppEntry> all) {
        AppEntry s = new AppEntry();
        s.key = SETTINGS_KEY;
        s.pkg = TV_SETTINGS;
        s.label = "Settings";
        s.system = true;
        s.launch = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            s.banner = pm.getApplicationBanner(s.pkg);
            s.icon = pm.getApplicationIcon(s.pkg);
        } catch (PackageManager.NameNotFoundException e) {
            s.icon = getDrawable(android.R.drawable.ic_menu_preferences);
        }
        s.color = 0xFF3A3A3C;
        all.put(s.key, s);
    }

    int priority(AppEntry e) {
        if (INPUTS_KEY.equals(e.key)) return -1;
        if (isSettings(e)) return 2000;
        if (PRESENT_KEY.equals(e.key)) return 1999;
        int i = DEFAULT_ORDER.indexOf(e.pkg);
        return i >= 0 ? i : 1000;
    }

    boolean isSettings(AppEntry e) {
        return SETTINGS_KEY.equals(e.key) || TV_SETTINGS.equals(e.pkg) || PRESENT_KEY.equals(e.key);
    }

    List<Inputs.Input> inputs = new ArrayList<>();

    /** Inputs card: a graphite tvOS-style card with the input glyph; OK opens the input picker. */
    void addInputsEntry(Map<String, AppEntry> all) {
        AppEntry e = new AppEntry();
        e.key = INPUTS_KEY;
        e.pkg = getPackageName();
        e.label = "Inputs";
        e.system = true;
        e.color = 0xFF48484A;
        int w = 640, h = 384;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h, 0xFF6E6E73, 0xFF2C2C2E, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        Bitmap g = Glyphs.get("input", (int) (h * 0.5f), Color.WHITE);
        c.drawBitmap(g, (w - g.getWidth()) / 2f, (h - g.getHeight()) / 2f, null);
        e.banner = new BitmapDrawable(getResources(), b);
        e.icon = e.banner;
        all.put(e.key, e);
    }

    void addResolved(PackageManager pm, String category, Set<String> pkgs, boolean leanback, Map<String, AppEntry> all) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai.packageName.equals(getPackageName()) || pkgs.contains(ai.packageName)) continue;
            AppEntry e = new AppEntry();
            ComponentName cn = new ComponentName(ai.packageName, ai.name);
            e.pkg = ai.packageName;
            e.key = cn.flattenToShortString();
            e.label = ri.loadLabel(pm).toString();
            e.banner = leanback ? ai.loadBanner(pm) : null;
            e.icon = ri.loadIcon(pm);
            e.color = tintFrom(e.icon);
            e.system = (ai.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            e.launch = new Intent(Intent.ACTION_MAIN).addCategory(category).setComponent(cn)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            all.put(e.key, e);
            pkgs.add(e.pkg);
        }
    }

    /** Average colour of the icon, darkened so white logos stay readable on top of it. */
    int tintFrom(Drawable d) {
        if (d == null) return 0xFF2C2C2E;
        Bitmap b = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        Rect old = d.copyBounds();
        d.setBounds(0, 0, 16, 16);
        d.draw(new Canvas(b));
        d.setBounds(old);
        long r = 0, g = 0, bl = 0, n = 0;
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                int p = b.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                r += Color.red(p);
                g += Color.green(p);
                bl += Color.blue(p);
                n++;
            }
        }
        b.recycle();
        if (n == 0) return 0xFF2C2C2E;
        float[] hsv = new float[3];
        Color.RGBToHSV((int) (r / n), (int) (g / n), (int) (bl / n), hsv);
        hsv[1] = Math.min(hsv[1], 0.8f);
        hsv[2] = Math.min(hsv[2], 0.5f);
        return Color.HSVToColor(hsv);
    }

    /**
     * Re-reads the apps in the background (Play Store updates, coming back from Settings) and swaps
     * the grid in. Focus only moves if it was on a card; an open menu is closed, because its
     * actions point at the old list.
     */
    void reload(String focusKey) {
        io.execute(() -> {
            AppList l = readApps();
            handler.post(() -> {
                if (isDestroyed() || moveIndex >= 0 || launchOverlay != null) return;
                View f = getCurrentFocus();
                boolean onCard = f == null || tiles.contains(f);
                closeMenu();
                applyApps(l);
                if (folderMode && hiddenApps.isEmpty()) folderMode = false;
                buildGrid();
                shelfEntry = null;
                if (!onCard && f != null && f.isAttachedToWindow()) {
                    focusedIndex = Math.min(focusedIndex, Math.max(0, shown.size() - 1));
                    return;
                }
                int i = focusKey == null ? -1 : indexOfKey(focusKey);
                focusIndex(i >= 0 ? i : Math.min(focusedIndex, shown.size() - 1));
            });
        });
    }

    void saveOrder() {
        StringBuilder sb = new StringBuilder();
        for (AppEntry e : apps) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.key);
        }
        prefs.edit().putString(PREF_ORDER, sb.toString()).apply();
    }

    void setHidden(AppEntry a, boolean hide) {
        Set<String> hidden = new HashSet<>(prefs.getStringSet(PREF_HIDDEN, Collections.emptySet()));
        if (hide) hidden.add(a.key);
        else hidden.remove(a.key);
        prefs.edit().putStringSet(PREF_HIDDEN, hidden).apply();
        int i = focusedIndex;
        loadApps();
        if (folderMode && hiddenApps.isEmpty()) folderMode = false;
        buildGrid();
        shelfEntry = null;
        focusIndex(Math.min(i, shown.size() - 1));
        toast(hide ? a.label + " moved to More Apps" : a.label + " is back on Home");
    }

    void registerPackageReceiver() {
        pkgReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                handler.removeCallbacks(reloadRun);
                handler.postDelayed(reloadRun, 600);
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(pkgReceiver, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(pkgReceiver, f);
        }
    }

    // ---------------------------------------------------------------- options

    void showOptions(int index) {
        AppEntry a = shown.get(index);
        View tile = tiles.get(index);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("Open");
        actions.add(() -> launchAnimated(tile, a));
        if (folderMode) {
            labels.add("Show on Home Screen");
            actions.add(() -> setHidden(a, false));
        } else {
            labels.add("Move");
            actions.add(() -> startMove(index));
            if (!isSettings(a)) {
                labels.add("Hide");
                actions.add(() -> setHidden(a, true));
            }
        }
        if (!isSettings(a) && !INPUTS_KEY.equals(a.key)) {
            labels.add("App Info");
            actions.add(() -> startSafe(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + a.pkg))));
            if (!a.system) {
                labels.add("Uninstall");
                actions.add(() -> startSafe(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg))));
            }
        }
        if (!folderMode) {
            labels.add("Reset Layout");
            actions.add(() -> {
                prefs.edit().remove(PREF_ORDER).apply();
                reload(a.key);
            });
        }
        showMenu(tile, a.label, labels, actions, labels.indexOf("Uninstall"));
    }

    // ---------------------------------------------------------------- Control Center

    interface Status {
        String get();
    }

    /**
     * One Control Center tile: glass with glyph (+ title / live status). Square tiles stack the
     * glyph over the title; capsules put the glyph left of two lines; circles are glyph-only.
     * Focus: the glass lights up and the tile lifts.
     */
    View ccTile(FrameLayout panel, int x, int y, int w, int h, String glyph, String title, Status status,
                boolean square, View.OnClickListener click) {
        boolean circle = title == null;
        float radius = circle ? h / 2f : square ? dp(26) : h / 2f;
        LiquidGlass glass = new LiquidGlass(radius, 0x24FFFFFF, glassSource, screenW, screenH, unit).adaptive().darker(0x59);
        glass.lens = dp(7);
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(square ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        t.setGravity(Gravity.CENTER);
        t.setBackground(glass);
        t.setFocusable(true);
        t.setClickable(true);
        t.setPadding(circle ? 0 : dp(16), 0, circle ? 0 : dp(14), 0);
        int gs = square ? dp(54) : circle ? dp(30) : dp(28);
        ImageView gi = new ImageView(this);
        gi.setImageBitmap(Glyphs.get(glyph, gs, glyph.equals("wifi") ? 0xFF0A84FF : Color.WHITE));
        t.addView(gi, new LinearLayout.LayoutParams(gs, gs));
        TextView sub = null;
        TextView[] titleRef = {null};
        if (!circle) {
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setGravity(square ? Gravity.CENTER_HORIZONTAL : Gravity.START);
            TextView tt = text(square ? 13.5f : 13f, Ui.font(this, "medium"), 1f);
            titleRef[0] = tt;
            tt.setText(title);
            tt.setShadowLayer(dp(4), 0, dp(1), 0x99000000);
            tt.setMaxLines(2);
            texts.addView(tt);
            if (status != null) {
                sub = text(11.5f, Ui.font(this, "regular"), 0.85f);
                sub.setShadowLayer(dp(4), 0, dp(1), 0x99000000);
                sub.setText(status.get());
                sub.setSingleLine(true);
                sub.setEllipsize(TextUtils.TruncateAt.END);
                texts.addView(sub);
            }
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(square ? ViewGroup.LayoutParams.WRAP_CONTENT : 0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, square ? 0 : 1f);
            if (square) tlp.topMargin = dp(12);
            else tlp.leftMargin = dp(10);
            t.addView(texts, tlp);
        }
        TextView subRef = sub;
        // Focused (tvOS): the tile turns solid white with a dark glyph and text.
        GradientDrawable white = new GradientDrawable();
        white.setCornerRadius(radius);
        white.setColor(0xF7FFFFFF);
        int glyphColor = glyph.equals("wifi") ? 0xFF0A84FF : Color.WHITE;
        t.setOnFocusChangeListener((v, has) -> {
            t.setBackground(has ? white : glass);
            gi.setImageBitmap(Glyphs.get(glyph, gs, has ? (glyph.equals("wifi") ? 0xFF0A84FF : 0xFF111114) : glyphColor));
            TextView tt = titleRef[0];
            if (tt != null) {
                tt.setTextColor(has ? 0xFF111114 : Color.WHITE);
                tt.setShadowLayer(has ? 0 : dp(4), 0, has ? 0 : dp(1), 0x99000000);
            }
            if (subRef != null) {
                subRef.setTextColor(has ? 0xB3111114 : Color.WHITE);
                subRef.setShadowLayer(has ? 0 : dp(4), 0, has ? 0 : dp(1), 0x99000000);
            }
            if (memBarFill != null && memBarFill.getParent() != null && v == ((View) memBarFill.getParent()).getParent().getParent()) {
                ((GradientDrawable) ((View) memBarFill.getParent()).getBackground()).setColor(has ? 0x33111114 : 0x40FFFFFF);
                ((GradientDrawable) memBarFill.getBackground()).setColor(has ? 0xFF111114 : (memoryUsed() > 0.85f ? 0xFFFF9F0A : Color.WHITE));
            }
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).translationZ(has ? dp(10) : 0)
                    .setDuration(Motion.d(200)).setInterpolator(LIFT).start();
        });
        t.setOnClickListener(v -> {
            click.onClick(v);
            if (subRef != null && status != null) subRef.setText(status.get());
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.leftMargin = x;
        lp.topMargin = y;
        panel.addView(t, lp);
        return t;
    }

    // ---------------------------------------------------------------- memory

    float memoryUsed() {
        android.app.ActivityManager am = getSystemService(android.app.ActivityManager.class);
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.totalMem > 0 ? 1f - mi.availMem / (float) mi.totalMem : 0f;
    }

    View memBarFill;

    /** Apps opened from Home recently, newest first (Android hides other apps' processes). */
    void noteLaunched(String pkg) {
        if (pkg == null || pkg.equals(getPackageName())) return;
        List<String> l = new ArrayList<>(Arrays.asList(getSharedPreferences("clean", MODE_PRIVATE).getString("recent", "").split(",")));
        l.remove(pkg);
        l.remove("");
        l.add(0, pkg);
        while (l.size() > 12) l.remove(l.size() - 1);
        getSharedPreferences("clean", MODE_PRIVATE).edit().putString("recent", String.join(",", l)).apply();
    }

    long availMem(android.app.ActivityManager am) {
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem;
    }


    // ---------------------------------------------------------------- inputs

    /** Input picker under the status row: Control Center capsules, two columns. */
    void showInputs(View from) {
        List<Inputs.Input> list = Inputs.list(this);
        if (list.isEmpty()) {
            toast("This TV has no inputs");
            return;
        }
        dropPicker();
        stopSway(null);
        popoverReturn = from;
        FrameLayout layer = new FrameLayout(this);
        layer.setClickable(true);
        layer.setClipChildren(false);
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT,
                new int[]{0x99000000, 0x40000000, 0x00000000}));
        layer.addView(shade, new FrameLayout.LayoutParams(screenW * 3 / 5, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));

        int col = dp(141), gap = dp(13), cap = (col - gap) / 2;
        FrameLayout panel = new FrameLayout(this);
        panel.setClipChildren(false);
        List<View> cells = new ArrayList<>();
        TextView title = text(13, Ui.font(this, "semibold"), 0.75f);
        title.setText("Inputs");
        panel.addView(title, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(20)));
        int top = dp(26);
        for (int i = 0; i < list.size(); i++) {
            Inputs.Input in = list.get(i);
            int x = (i % 2) * (col + gap), y = top + (i / 2) * (cap + gap);
            cells.add(ccTile(panel, x, y, col, cap, Inputs.glyph(in), in.label,
                    in.disconnected ? () -> "Not connected" : null, false, v -> {
                        closeProfilePicker();
                        if (!Inputs.open(this, in)) toast("Can't switch to " + in.label);
                    }));
        }
        int rows = (list.size() + 1) / 2;
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(2 * col + gap, top + rows * cap + (rows - 1) * gap,
                Gravity.TOP | Gravity.END);
        plp.topMargin = dp(72);
        plp.rightMargin = dockOuter;
        layer.addView(panel, plp);

        root.addView(layer, match());
        pickerLayer = layer;
        pickerCells = cells;
        layer.setAlpha(0f);
        panel.setTranslationY(-Motion.dist(dp(10)));
        layer.animate().alpha(1f).setDuration(Motion.d(200)).start();
        panel.animate().translationY(0).setDuration(Motion.d(260)).setInterpolator(EASE).start();
        Motion.cascade(cells, -dp(8));  // tiles drop in one after another, like tvOS
        cells.get(0).requestFocus();
    }

    // ---------------------------------------------------------------- internet status

    /** Follows the default network; Android marks it "validated" once the internet is reachable. */
    void watchNetwork() {
        updateNet();
        if (netCallback != null) return;
        netCallback = new android.net.ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(android.net.Network n) { handler.post(MainActivity.this::updateNet); }
            @Override public void onLost(android.net.Network n) { handler.post(MainActivity.this::updateNet); }
            @Override public void onCapabilitiesChanged(android.net.Network n, android.net.NetworkCapabilities nc) {
                handler.post(MainActivity.this::updateNet);
            }
        };
        try {
            getSystemService(android.net.ConnectivityManager.class).registerDefaultNetworkCallback(netCallback);
        } catch (Exception e) {
            netCallback = null;
        }
    }

    int netState = -1, netBars = -1;
    boolean netEthernet;

    void updateNet() {
        android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
        android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
        int state = nc == null ? 0
                : nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? 2 : 1;
        boolean eth = nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET);
        int bars = 3;
        if (nc != null && !eth) {
            try {
                android.net.wifi.WifiManager wm = getApplicationContext().getSystemService(android.net.wifi.WifiManager.class);
                int level = android.net.wifi.WifiManager.calculateSignalLevel(wm.getConnectionInfo().getRssi(), 4);
                bars = level <= 1 ? 1 : level == 2 ? 2 : 3;
            } catch (Exception ignored) {
                // keep full bars
            }
        }
        boolean back = netState == 0 || netState == 1;
        if (state == netState && eth == netEthernet && bars == netBars) return;
        netState = state;
        netEthernet = eth;
        netBars = bars;
        // Back online: refresh what needs the internet.
        if (state == 2 && back) {
            Weather.refresh(this, true, this::updateWeather);
        }
    }

    /** Android TV's system click on D-pad moves, select and Back (Settings -> Home -> Navigation sounds). */
    static void navSound(Context c, KeyEvent ev) {
        if (ev.getAction() != KeyEvent.ACTION_DOWN) return;
        switch (ev.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                Clicks.move(c, ev.getKeyCode());
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (ev.getRepeatCount() == 0) Clicks.select(c);
                break;
            case KeyEvent.KEYCODE_BACK:
                if (ev.getRepeatCount() == 0) Clicks.back(c);
                break;
            default:
                break;
        }
    }

    boolean swallowUp;

    // ---------------------------------------------------------------- weather

    /**
     * Once a minute: fetch when the forecast is due (every 15 minutes by default), move "Now" on,
     * and pick up Wi-Fi signal changes.
     */
    int tickCount;
    final Runnable weatherTick = new Runnable() {
        @Override public void run() {
            Weather.refresh(MainActivity.this, false, MainActivity.this::updateWeather);
            updateWeather();
            updateNet();
            handler.postDelayed(this, 60 * 1000L);
        }
    };

    boolean weatherOpen;

    float weatherAlpha() {
        return folderMode || weatherOpen ? 0f : clockAlpha();
    }

    /** Shows the cached forecast (hidden when weather is off or nothing has loaded yet). */
    void updateWeather() {
        if (weatherCard == null) return;
        Weather.Data d = Weather.enabled(this) ? Weather.cached(this) : null;
        boolean show = d != null;
        if (!show && weatherCard.isFocused()) focusTop();
        weatherCard.setVisibility(show ? View.VISIBLE : View.GONE);
        weatherCard.setData(d);
        if (show && !weatherCard.isFocused() && launchOverlay == null) weatherCard.setAlpha(weatherAlpha());
    }

    /** The full forecast: a glass panel opening from the weather card in the top-left corner. */
    void showWeather() {
        Weather.Data d = Weather.cached(this);
        if (d == null) return;
        dropPicker();
        stopSway(null);
        popoverReturn = weatherCard;
        FrameLayout layer = new FrameLayout(this);
        layer.setClickable(true);
        layer.setClipChildren(false);
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xA6000000, 0x4D000000, 0x00000000}));
        layer.addView(shade, new FrameLayout.LayoutParams(screenW * 3 / 5, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));

        WeatherView panel = new WeatherView(this, true, unit);
        LiquidGlass glass = new LiquidGlass(dp(26), 0x33000000, glassSource, screenW, screenH, unit).adaptive();
        glass.lens = dp(7);
        panel.setBackground(glass);
        panel.setFocusable(true);
        panel.setData(d);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(WeatherView.FULL_W), dp(WeatherView.FULL_H),
                Gravity.TOP | Gravity.START);
        plp.topMargin = dp(24);
        plp.leftMargin = dockOuter;
        layer.addView(panel, plp);

        root.addView(layer, match());
        pickerLayer = layer;
        pickerCells = new ArrayList<>();
        pickerCells.add(panel);
        weatherOpen = true;
        weatherCard.animate().alpha(0f).setDuration(Motion.d(150)).start();
        layer.setAlpha(0f);
        panel.setTranslationY(-dp(10));
        layer.animate().alpha(1f).setDuration(Motion.d(200)).start();
        panel.animate().translationY(0).setDuration(Motion.d(260)).setInterpolator(EASE).start();
        panel.requestFocus();
        Weather.refresh(this, false, () -> {
            Weather.Data nd = Weather.cached(this);
            if (nd != null && panel.isAttachedToWindow()) panel.setData(nd);
            updateWeather();
        });
    }

    // ---------------------------------------------------------------- profiles

    List<View> pickerCells = new ArrayList<>();

    // ---------------------------------------------------------------- overlays

    boolean replayingBack;

    /** Removes whichever overlay is open. */
    void dropPicker() {
        if (pickerLayer == null) return;
        root.removeView(pickerLayer);
    }

    boolean statusFocused() {
        View f = getCurrentFocus();
        return f != null && f == settingsButton;
    }

    /** Directional focus inside a popover: nearest cell whose centre lies in that direction. */
    boolean pickerMove(int keyCode) {
        View cur = getCurrentFocus();
        if (!pickerCells.contains(cur)) {
            if (!pickerCells.isEmpty()) pickerCells.get(0).requestFocus();
            return true;
        }
        int[] a = new int[2];
        cur.getLocationOnScreen(a);
        float ax = a[0] + cur.getWidth() / 2f, ay = a[1] + cur.getHeight() / 2f;
        View best = null;
        float bestScore = Float.MAX_VALUE;
        for (View v : pickerCells) {
            if (v == cur) continue;
            int[] b = new int[2];
            v.getLocationOnScreen(b);
            float dx = b[0] + v.getWidth() / 2f - ax, dy = b[1] + v.getHeight() / 2f - ay;
            float main, cross;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT: main = -dx; cross = dy; break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: main = dx; cross = dy; break;
                case KeyEvent.KEYCODE_DPAD_UP: main = -dy; cross = dx; break;
                default: main = dy; cross = dx; break;
            }
            if (main <= dp(4)) continue;
            float score = main + Math.abs(cross) * 2.2f;
            if (score < bestScore) {
                bestScore = score;
                best = v;
            }
        }
        if (best != null) best.requestFocus();
        return true;
    }

    View popoverReturn;

    void closeProfilePicker() {
        if (pickerLayer == null) return;
        FrameLayout l = pickerLayer;
        pickerLayer = null;
        l.animate().alpha(0f).setDuration(Motion.d(220)).withEndAction(() -> root.removeView(l)).start();
        weatherOpen = false;
        weatherCard.animate().alpha(weatherAlpha()).setDuration(Motion.d(220)).start();
        if (popoverReturn != null && popoverReturn.isAttachedToWindow()) popoverReturn.requestFocus();
        else goTop();
        popoverReturn = null;
    }

    interface NameResult {
        void ok(String name);
    }

    // ---------------------------------------------------------------- tvOS context menu

    FrameLayout menuLayer;
    View menuReturnFocus;

    /**
     * tvOS-style context menu: a dark Liquid Glass panel beside the focused card, title on top,
     * rows below; the focused row becomes a white pill with dark text. Back closes it.
     */
    void showMenu(View anchor, String title, List<String> labels, List<Runnable> actions, int destructive) {
        closeMenu();
        menuReturnFocus = anchor;
        stopSway(null);
        menuLayer = new FrameLayout(this);
        menuLayer.setBackgroundColor(0x00000000);
        menuLayer.setClickable(true);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8), dp(12), dp(8), dp(8));
        // "Regular" Liquid Glass: heavier dimming so the menu text stays legible over anything.
        LiquidGlass glass = new LiquidGlass(dp(20), 0xB81A1A20, glassSource, screenW, screenH, unit);
        glass.lens = dp(8);
        panel.setBackground(glass);
        panel.setElevation(dp(30));
        panel.setClipChildren(false);
        panel.setClipToPadding(false);

        TextView head = text(11, Ui.font(this, "semibold"), 0.55f);
        head.setText(title);
        head.setShadowLayer(0, 0, 0, 0);
        head.setPadding(dp(12), 0, dp(12), dp(8));
        head.setSingleLine(true);
        head.setEllipsize(TextUtils.TruncateAt.END);
        panel.addView(head);

        List<View> rows = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            int idx = i;
            TextView row = text(14, Ui.font(this, "medium"), 1f);
            row.setText(labels.get(i));
            row.setShadowLayer(0, 0, 0, 0);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), 0, dp(12), 0);
            row.setFocusable(true);
            row.setClickable(true);
            int base = i == destructive ? 0xFFFF6B5E : Color.WHITE;
            row.setTextColor(base);
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(dp(12));
            pill.setColor(Color.TRANSPARENT);
            row.setBackground(pill);
            row.setOnFocusChangeListener((v, has) -> {
                pill.setColor(has ? 0xFFFFFFFF : Color.TRANSPARENT);
                row.setTextColor(has ? (idx == destructive ? 0xFFD7372A : 0xFF111114) : base);
                v.animate().scaleX(has ? 1.04f : 1f).scaleY(has ? 1.04f : 1f).setDuration(Motion.d(160)).setInterpolator(LIFT).start();
            });
            row.setOnClickListener(v -> {
                closeMenu();
                actions.get(idx).run();
            });
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34));
            rlp.topMargin = dp(2);
            panel.addView(row, rlp);
            rows.add(row);
        }
        // Wrap focus top <-> bottom and keep it inside the menu.
        for (int i = 0; i < rows.size(); i++) {
            View r = rows.get(i);
            r.setId(View.generateViewId());
        }
        for (int i = 0; i < rows.size(); i++) {
            View r = rows.get(i);
            r.setNextFocusUpId(rows.get((i - 1 + rows.size()) % rows.size()).getId());
            r.setNextFocusDownId(rows.get((i + 1) % rows.size()).getId());
            r.setNextFocusLeftId(r.getId());
            r.setNextFocusRightId(r.getId());
        }

        int w = dp(220);
        int[] loc = new int[2];
        anchor.getLocationInWindow(loc);
        float aw = anchor.getWidth() * anchor.getScaleX(), ah = anchor.getHeight() * anchor.getScaleY();
        int x = (int) (loc[0] + aw + dp(18));
        if (x + w > screenW - dp(24)) x = (int) (loc[0] - w - dp(18));
        x = Math.max(dp(24), x);
        panel.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        int h = panel.getMeasuredHeight();
        int y = (int) (loc[1] + ah / 2 - h / 2f);
        y = Math.max(dp(24), Math.min(y, screenH - h - dp(24)));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.leftMargin = x;
        plp.topMargin = y;
        menuLayer.addView(panel, plp);
        root.addView(menuLayer, match());

        // Pop in from the card's side.
        panel.setPivotX(x > loc[0] ? 0 : w);
        panel.setPivotY(h / 2f);
        panel.setAlpha(0f);
        panel.setScaleX(0.9f);
        panel.setScaleY(0.9f);
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.d(220)).setInterpolator(LIFT).start();
        menuLayer.animate().setDuration(Motion.d(220)).start();
        menuLayer.setBackgroundColor(0x59000000);
        if (!rows.isEmpty()) rows.get(0).requestFocus();
    }

    void closeMenu() {
        if (menuLayer == null) return;
        FrameLayout m = menuLayer;
        menuLayer = null;
        m.animate().alpha(0f).setDuration(Motion.d(150)).withEndAction(() -> root.removeView(m)).start();
        if (menuReturnFocus != null && menuReturnFocus.isAttachedToWindow()) menuReturnFocus.requestFocus();
        menuReturnFocus = null;
    }

    void startSafe(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            toast("Not available on this TV");
        }
    }

    // ---------------------------------------------------------------- move mode

    void startMove(int index) {
        stopSway(null);
        moveIndex = index;
        focusIndex(index);
        startWiggle(tiles.get(index));
        toast("Use arrows to move \u00b7 OK when done");
    }

    void moveTo(int to) {
        int last = apps.size() - 1;  // the folder tile always stays at the end
        if (to > last && moveIndex / COLS < last / COLS) to = last;
        if (to < 0 || to > last || to == moveIndex) return;
        Map<String, int[]> before = tilePositions();
        AppEntry e = apps.remove(moveIndex);
        apps.add(to, e);
        moveIndex = to;
        saveOrder();
        buildGrid();
        focusIndex(to);
        startWiggle(tiles.get(to));
        slideFrom(before);
    }

    /** Window position of every visible card, keyed by app, for the move animation. */
    Map<String, int[]> tilePositions() {
        Map<String, int[]> m = new LinkedHashMap<>();
        for (int i = 0; i < tiles.size(); i++) {
            int[] loc = new int[2];
            tiles.get(i).getLocationInWindow(loc);
            m.put(shown.get(i).key, loc);
        }
        return m;
    }

    /**
     * tvOS-style reflow: after the grid is rebuilt in its new order, every card starts at its old
     * on-screen position and glides to its new slot, so neighbours visibly make room.
     */
    void slideFrom(Map<String, int[]> before) {
        content.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                content.getViewTreeObserver().removeOnPreDrawListener(this);
                for (int i = 0; i < tiles.size(); i++) {
                    int[] old = before.get(shown.get(i).key);
                    if (old == null) continue;
                    View t = tiles.get(i);
                    int[] now = new int[2];
                    t.getLocationInWindow(now);
                    float dx = old[0] - now[0], dy = old[1] - now[1];
                    if (dx == 0 && dy == 0) continue;
                    if (Math.abs(dy) > tileH / 2f && Math.abs(dx) > tileW * 1.5f) {
                        // Wrapped to another row (end of a row): a slide would streak across the
                        // whole screen, so settle into the new slot instead, as tvOS does.
                        View cell = (View) t.getParent();
                        cell.setAlpha(0f);
                        cell.setScaleX(0.86f);
                        cell.setScaleY(0.86f);
                        cell.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.d(240)).setInterpolator(EASE).start();
                        continue;
                    }
                    t.setTranslationX(dx);
                    t.setTranslationY(dy);
                    t.animate().translationX(0).translationY(0).setDuration(Motion.d(260)).setInterpolator(EASE).start();
                }
                return true;
            }
        });
    }

    void endMove() {
        stopWiggle();
        moveIndex = -1;
    }

    void startWiggle(View v) {
        stopWiggle();
        wiggle = ObjectAnimator.ofFloat(v, View.ROTATION, -1.5f, 1.5f);
        wiggle.setDuration(130);
        wiggle.setRepeatCount(ValueAnimator.INFINITE);
        wiggle.setRepeatMode(ValueAnimator.REVERSE);
        wiggle.start();
    }

    void stopWiggle() {
        if (wiggle != null) {
            wiggle.cancel();
            Object t = wiggle.getTarget();
            if (t instanceof View) ((View) t).setRotation(0);
            wiggle = null;
        }
    }

    // ---------------------------------------------------------------- helpers

    TextView text(float sp, Typeface tf, float alpha) {
        TextView t = new TextView(this);
        Ui.textSize(t, sp);
        t.setTypeface(tf);
        t.setTextColor(Color.WHITE);
        t.setAlpha(alpha);
        t.setShadowLayer(10, 0, 2, 0x99000000);
        return t;
    }

    FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    /** Design units (1/960 of the screen width), so layout follows the display's resolution. */
    int dp(float v) {
        return Math.round(v * unit);
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
