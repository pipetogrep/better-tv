package dev.bettertv.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * tvOS-style settings on one page: Better TV's icon and title on the left, every setting on the
 * right in sections. Moving onto a background choice previews it full screen behind the page.
 */
public class SettingsActivity extends Activity {

    LinearLayout list;
    final List<Runnable> refreshers = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setBackgroundDrawable(null);  // the page paints its own background
        MainActivity.initWallpapers(this);
        Clicks.init(this);
        Motion.init(this);
        build();
        String pg = getIntent().getStringExtra("page");  // adb: --es page "Home"
        if (pg != null) show(pg);
    }

    // One page of settings (the name is kept for adb's --es page and showAndFocus).
    static final String ROOT = "root";

    FrameLayout leftPane;
    ScrollView scroller;
    String page = ROOT;

    void build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0B10);
        // Full-screen preview behind everything while choosing a background.
        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setVisibility(android.view.View.GONE);
        root.addView(preview, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // The preview shows only while a background row is focused.
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((was, now) -> {
            if (!backgroundRows.contains(now)) preview.setVisibility(android.view.View.GONE);
        });
        LinearLayout split = new LinearLayout(this);
        split.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(split, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        leftPane = new FrameLayout(this);
        split.addView(leftPane, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        scroller = new ScrollView(this);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setClipToPadding(false);
        scroller.setClipChildren(false);
        scroller.setPadding(0, Ui.px(this, 60), Ui.px(this, 44), Ui.px(this, 60));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        list.setClipToPadding(false);
        list.setPadding(Ui.px(this, 12), 0, Ui.px(this, 12), 0);
        scroller.addView(list);
        split.addView(scroller, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.25f));
        setContentView(root);
        Clicks.quiet(root);
        show(ROOT);
    }

    /** Builds one page: its icon and title on the left, its rows on the right. */
    void show(String p) {
        page = p;
        list.removeAllViews();
        rowsByLabel.clear();
        refreshers.clear();
        buildLeft(p);
        backgroundRows.clear();
        preview.setVisibility(android.view.View.GONE);
        pageRoot();
        scroller.scrollTo(0, 0);
        android.view.View focus = null;
        for (int i = 0; i < list.getChildCount() && focus == null; i++) {
            if (list.getChildAt(i).isFocusable()) focus = list.getChildAt(i);
        }
        if (focus != null) focus.requestFocus();
        list.setAlpha(0f);
        list.setTranslationX(Motion.dist(Ui.px(this, p.equals(ROOT) ? -16 : 16)));
        list.animate().alpha(1f).translationX(0).setDuration(Motion.d(220)).start();
        // Rows settle in one after another (tvOS Settings).
        List<android.view.View> rows = new ArrayList<>();
        for (int i = 0; i < list.getChildCount(); i++) rows.add(list.getChildAt(i));
        Motion.cascade(rows, Ui.px(this, 8));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAll();  // e.g. a permission granted on a system screen
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_PHOTO) {
            if (results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                showAndFocus(ROOT, "Photos  (a different one each day)");
                usePhoto();
            }
            return;
        }
        refreshAll();
    }

    /** Left side: the page's icon (Better TV on the main list), its title, and the brand line. */
    void buildLeft(String p) {
        leftPane.removeAllViews();
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_HORIZONTAL);
        // Better TV's own icon: a white house on a graphite card, like the app's launcher icon.
        FrameLayout icon = new FrameLayout(this);
        GradientDrawable plate = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF3A3A3F, 0xFF1C1C1E});
        plate.setCornerRadius(Ui.px(this, 12));
        icon.setBackground(plate);
        ImageView mark = new ImageView(this);
        mark.setImageBitmap(Glyphs.get("house", Ui.px(this, 44), Color.WHITE));
        mark.setScaleType(ImageView.ScaleType.CENTER);
        icon.addView(mark, new FrameLayout.LayoutParams(Ui.px(this, 58), Ui.px(this, 58), Gravity.CENTER));
        icon.setElevation(Ui.px(this, 8));
        left.addView(icon, new LinearLayout.LayoutParams(Ui.px(this, 150), Ui.px(this, 90)));

        TextView title = new TextView(this);
        title.setText("Settings");
        title.setTextColor(Color.WHITE);
        title.setTypeface(Ui.font(this, "display"));
        title.setLetterSpacing(-0.02f);
        Ui.textSize(title, 34);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Ui.px(this, 22);
        left.addView(title, tlp);

        leftPane.addView(left, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    // ---------------------------------------------------------------- pages

    /** Everything on one page, in sections; nothing is more than one press deep. */
    void pageRoot() {
        section("Background");
        pageBackground();
        section("Weather");
        pageWeather();
        pageHome();
        if (!Inputs.list(this).isEmpty()) {
            section("Inputs");
            pageInputs();
        }
        section("Android");
        row("Android Settings", () -> "", () -> Intents.start(this, new Intent(Settings.ACTION_SETTINGS)));
        row("Better TV", () -> "Version " + version(), () -> { });
    }

    void pageInputs() {
        row("Inputs card on Home", () -> Inputs.showCard(this) ? "On" : "Off", () ->
                Inputs.prefs(this).edit().putBoolean(Inputs.K_CARD, !Inputs.showCard(this)).apply());
        section("This TV");
        for (Inputs.Input in : Inputs.list(this)) {
            row(in.label, () -> in.disconnected ? "Not connected" : "", () -> {
                if (!Inputs.open(this, in)) Toast.makeText(this, "Can't switch to " + in.label, Toast.LENGTH_SHORT).show();
            });
        }
    }

    void pageWeather() {
        row("Show weather", () -> Weather.enabled(this) ? "On" : "Off", () -> {
            Weather.prefs(this).edit().putBoolean(Weather.K_ENABLED, !Weather.enabled(this)).apply();
            Weather.changed(this);
        });
        row("Location", () -> Weather.manual(this) ? Weather.place(this)
                : Weather.place(this).isEmpty() ? "Automatic" : "Automatic \u00b7 " + Weather.place(this), this::chooseLocation);
        row("Temperature", () -> {
            String u = Weather.prefs(this).getString(Weather.K_UNITS, "auto");
            return u.equals("c") ? "Celsius" : u.equals("f") ? "Fahrenheit" : "Automatic";
        }, () -> {
            String[] keys = {"auto", "c", "f"};
            String[] names = {"Automatic", "Celsius (\u00b0C)", "Fahrenheit (\u00b0F)"};
            String cur = Weather.prefs(this).getString(Weather.K_UNITS, "auto");
            int sel = cur.equals("c") ? 1 : cur.equals("f") ? 2 : 0;
            dialog("Temperature").setSingleChoiceItems(names, sel, (d, w) -> {
                Weather.prefs(this).edit().putString(Weather.K_UNITS, keys[w]).apply();
                Weather.changed(this);
                refreshAll();
                d.dismiss();
            }).show();
        });
        row("Source", () -> Weather.sourceName(Weather.source(this)), () -> {
            String[] keys = Weather.SOURCES;
            String[] names = {"Open-Meteo", "MET Norway (Yr)", "wttr.in  (3 days)"};
            int sel = java.util.Arrays.asList(keys).indexOf(Weather.source(this));
            dialog("Weather source").setSingleChoiceItems(names, Math.max(0, sel), (d, w) -> {
                Weather.prefs(this).edit().putString(Weather.K_SOURCE, keys[w]).apply();
                Weather.changed(this);
                refreshAll();
                d.dismiss();

            }).show();
        });
        row("If the source is down", () -> Weather.prefs(this).getBoolean(Weather.K_FALLBACK, false)
                ? "Try the other sources" : "Wait for it", () -> {
            boolean on = !Weather.prefs(this).getBoolean(Weather.K_FALLBACK, false);
            Weather.prefs(this).edit().putBoolean(Weather.K_FALLBACK, on).apply();
            if (on) Toast.makeText(this, "Your rounded location is then also sent to the other weather services.",
                    Toast.LENGTH_LONG).show();
        });
        row("Update every", () -> Weather.refreshMinutes(this) + " minutes", () -> {
            int[] mins = Weather.REFRESH_CHOICES;
            String[] names = new String[mins.length];
            int sel = 1;
            for (int i = 0; i < mins.length; i++) {
                names[i] = mins[i] + " minutes" + (mins[i] == 15 ? "  (default)" : "");
                if (mins[i] == Weather.refreshMinutes(this)) sel = i;
            }
            dialog("Update weather every").setSingleChoiceItems(names, sel, (d, w) -> {
                Weather.prefs(this).edit().putInt(Weather.K_REFRESH, mins[w]).apply();
                refreshAll();
                d.dismiss();
            }).show();
        });
    }

    void pageHome() {
        section("Home Button");
        row("Home screen", () -> Perms.isDefaultHome(this) ? "Better TV" : "Make Better TV the home screen\u2026",
                () -> Perms.requestDefaultHome(this));
        row("Home button opens Better TV", () -> PowerService.HomeButton.isDefaultHome(this) ? "Not needed"
                : !PowerService.HomeButton.enabled(this) ? "Off"
                : Perms.hasAccessibility(this) ? "On" : "Needs Better TV\u2019s switch\u2026", this::toggleHomeButton);
        section("Start with the TV");
        row("Open", () -> Startup.label(this), this::chooseStartup);
        if (!Startup.target(this).isEmpty()) {
            row("When", () -> Startup.onWake(this) ? "When the TV starts or wakes" : "When the TV starts", () -> {
                String[] names = {"When the TV starts", "When the TV starts or wakes from standby"};
                dialog("Open it").setSingleChoiceItems(names, Startup.onWake(this) ? 1 : 0, (d, w) -> {
                    Startup.setOnWake(this, w == 1);
                    d.dismiss();
                    refreshAll();
                    if (w == 1 && !Perms.hasAccessibility(this)) {
                        Perms.requestAccessibility(this, "Android lets Better TV open an app when the TV wakes only "
                                + "with Better TV\u2019s accessibility switch on.");
                    }
                }).show();
            });
            if (Startup.onWake(this) && !Perms.hasAccessibility(this)) {
                row("Waking into it", () -> "Needs Better TV\u2019s switch\u2026", () -> Perms.requestAccessibility(this,
                        "Android lets Better TV open an app when the TV wakes only with Better TV\u2019s accessibility switch on."));
            }
        }
        section("Motion & Sound");
        row("Motion", () -> Motion.NAMES[Motion.level()], () -> {
            String[] names = {"Full", "Reduced  (default) \u2014 shorter", "Off \u2014 changes are instant"};
            dialog("Motion").setSingleChoiceItems(names, Motion.level(), (d, w) -> {
                Motion.set(this, w);
                d.dismiss();
                refreshAll();
            }).show();
        });
        row("Navigation sounds", () -> Clicks.enabled(this) ? "On" : "Off",
                () -> Clicks.setEnabled(this, !Clicks.enabled(this)));
        section("Screensaver");
        row("Screensaver", () -> dreamLabel(), this::openDreamSettings);
        row("Start screensaver now", () -> "", () -> {
            Intent i = new Intent(Intent.ACTION_MAIN).setClassName("com.android.systemui", "com.android.systemui.Somnambulator")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (!Intents.start(this, i)) Toast.makeText(this, "This TV can't start the screensaver from an app", Toast.LENGTH_LONG).show();
        });
        section("Setup");
        row("Run setup again", () -> "", () -> startActivity(new Intent(this, SetupActivity.class)));
        row("Reset app layout", () -> "", () -> {
            Prefs.home(this).edit().remove("order").apply();
            Toast.makeText(this, "Layout reset", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onDestroy() {
        previewIo.shutdownNow();
        super.onDestroy();
    }

    static final int REQ_PHOTO = 41;

    ImageView preview;
    volatile String previewKey;
    /** Photo previews decode one at a time; a request the user has scrolled past is skipped. */
    final java.util.concurrent.ExecutorService previewIo = java.util.concurrent.Executors.newSingleThreadExecutor();
    final java.util.Set<android.view.View> backgroundRows = new java.util.HashSet<>();

    /**
     * Background: the solid colours, then the photos on the TV (every one by name, plus a daily
     * rotation through them). Moving onto a choice shows it full screen behind Settings.
     */
    void pageBackground() {
        java.util.List<String> keys = new java.util.ArrayList<>(), names = new java.util.ArrayList<>();
        for (int i = 0; i < MainActivity.READY_WALLS; i++) {  // colours, then the built-in pictures
            keys.add(MainActivity.WALL_KEYS[i]);
            names.add(MainActivity.WALL_NAMES[i]);
        }
        java.io.File[] photos = hasPhotoAccess() ? MainActivity.photoFiles() : new java.io.File[0];
        if (photos.length > 0) {
            keys.add("photo");
            names.add("Photos  (a different one each day)");
            for (java.io.File f : photos) {
                keys.add("photo:" + f.getName());
                names.add(MainActivity.photoName(f.getName()));
            }
        }
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            android.view.View r = row(names.get(i),
                    () -> key.equals(Prefs.home(this).getString("wallpaper", MainActivity.DEFAULT_WALL)) ? "\u2713" : "",
                    () -> Prefs.home(this).edit().putString("wallpaper", key).apply());
            android.view.View.OnFocusChangeListener style = r.getOnFocusChangeListener();
            r.setOnFocusChangeListener((v, has) -> {
                style.onFocusChange(v, has);
                if (has) showPreview(key);
            });
            backgroundRows.add(r);
        }
        if (photos.length == 0) {
            row("Your Photos", () -> hasPhotoAccess() ? "None yet" : "Allow\u2026", this::usePhoto);
        }
    }

    boolean hasPhotoAccess() {
        return checkSelfPermission(photoPermission()) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    /** Shows a background behind Settings; photos decode off the UI thread. */
    void showPreview(String key) {
        previewKey = key;
        preview.setVisibility(android.view.View.VISIBLE);
        if (!key.startsWith("photo")) {
            preview.setImageBitmap(MainActivity.wallpaper(key));
            return;
        }
        previewIo.execute(() -> {
            if (!key.equals(previewKey)) return;  // moved on before this one started
            android.graphics.Bitmap b = MainActivity.wallpaper(key);
            runOnUiThread(() -> {
                if (key.equals(previewKey)) preview.setImageBitmap(b);
            });
        });
    }

    String photoPermission() {
        return android.os.Build.VERSION.SDK_INT >= 33 ? "android.permission.READ_MEDIA_IMAGES"
                : "android.permission.READ_EXTERNAL_STORAGE";
    }

    /** Photos: asks to read pictures, then says where they go if there are none yet. */
    void usePhoto() {
        if (checkSelfPermission(photoPermission()) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{photoPermission()}, REQ_PHOTO);
            return;
        }
        if (MainActivity.photoFiles().length == 0) {
            dialog("Your Photos").setMessage("Put pictures (JPEG, PNG or WebP) in Pictures/BetterTV on this TV. "
                    + "They appear here by name; pick one, or let them change daily.\n\nFrom a computer:\n"
                    + "adb push photo.jpg /sdcard/Pictures/BetterTV/").setPositiveButton("OK", null).show();
        }
    }


    void toggleHomeButton() {
        if (PowerService.HomeButton.isDefaultHome(this)) {
            Toast.makeText(this, "Better TV is already the home app, so the Home button opens it.", Toast.LENGTH_LONG).show();
            return;
        }
        boolean on = PowerService.HomeButton.enabled(this);
        if (!on && !Perms.hasAccessibility(this)) {
            PowerService.HomeButton.setEnabled(this, true);
            Perms.requestAccessibility(this, "This TV keeps its own home screen. With Better TV\u2019s switch on, Better TV opens "
                    + "whenever that home screen appears, so the Home button brings you here.");
            return;
        }
        PowerService.HomeButton.setEnabled(this, !on);
        refreshAll();
    }

    /** Start with the TV: Home Screen, a TV input, or an app. */
    void chooseStartup() {
        List<String> targets = new ArrayList<>(), names = new ArrayList<>();
        targets.add("");
        names.add("Home Screen  (default)");
        for (Inputs.Input in : Inputs.list(this)) {
            if (in.usb) continue;
            targets.add("input:" + in.id);
            names.add(in.label);
        }
        for (String[] app : Startup.apps(this)) {
            targets.add("app:" + app[0]);
            names.add(app[1]);
        }
        int sel = Math.max(0, targets.indexOf(Startup.target(this)));
        dialog("When the TV turns on, open").setSingleChoiceItems(names.toArray(new String[0]), sel, (d, w) -> {
            Startup.set(this, targets.get(w), w == 0 ? "" : names.get(w));
            d.dismiss();
            showAndFocus(ROOT, "Open");
        }).show();
    }

    /** The TV's chosen screensaver (readable without permission), e.g. "Aerial Views". */
    String dreamLabel() {
        String comp = Settings.Secure.getString(getContentResolver(), "screensaver_components");
        if ("0".equals(Settings.Secure.getString(getContentResolver(), "screensaver_enabled"))) return "Off in TV settings";
        if (comp == null || comp.isEmpty()) return "TV default";
        android.content.ComponentName cn = android.content.ComponentName.unflattenFromString(comp.split(",")[0]);
        if (cn == null) return "TV default";
        try {
            return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(cn.getPackageName(), 0)).toString();
        } catch (Exception e) {
            return "TV default";
        }
    }

    /** Choose / set up the screensaver: the TV's screensaver settings, else the screensaver app itself. */
    void openDreamSettings() {
        if (Intents.start(this, new Intent("android.settings.DREAM_SETTINGS"))) return;
        String comp = Settings.Secure.getString(getContentResolver(), "screensaver_components");
        android.content.ComponentName cn = comp == null ? null : android.content.ComponentName.unflattenFromString(comp.split(",")[0]);
        if (cn != null) {
            Intent l = getPackageManager().getLeanbackLaunchIntentForPackage(cn.getPackageName());
            if (l == null) l = getPackageManager().getLaunchIntentForPackage(cn.getPackageName());
            if (l != null && Intents.start(this, l)) return;
        }
        Toast.makeText(this, "Choose the screensaver in Android Settings \u2192 Device Preferences \u2192 Screen saver", Toast.LENGTH_LONG).show();
        Intents.start(this, new Intent(Settings.ACTION_SETTINGS));
    }

    String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    void section(String name) {
        TextView t = new TextView(this);
        t.setText(name.toUpperCase());
        t.setTextColor(0x8CFFFFFF);
        t.setTypeface(Ui.font(this, "medium"));
        t.setLetterSpacing(0.06f);
        Ui.textSize(t, 11.5f);
        t.setPadding(Ui.px(this, 14), Ui.px(this, 18), 0, Ui.px(this, 6));
        list.addView(t);
    }

    interface Value {
        String get();
    }

    /** A settings row: label left, current value right; focus lifts it onto a light plate. */
    /** This page's rows by label, so a page rebuilt after a choice can keep focus on that row. */
    final java.util.Map<String, android.view.View> rowsByLabel = new java.util.HashMap<>();

    /** Rebuilds a page (its rows can change after a choice) and keeps focus on the row just used. */
    void showAndFocus(String p, String label) {
        show(p);
        android.view.View v = rowsByLabel.get(label);
        if (v != null) v.requestFocus();
    }

    android.view.View row(String label, Value value, Runnable action) {
        LinearLayout r = new LinearLayout(this);
        rowsByLabel.put(label, r);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setFocusable(true);
        r.setClickable(true);
        int padH = Ui.px(this, 16);
        r.setPadding(padH, 0, padH, 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.px(this, 10));
        bg.setColor(0x14FFFFFF);
        r.setBackground(bg);

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(Color.WHITE);
        l.setTypeface(Ui.font(this, "medium"));
        Ui.textSize(l, 15);
        l.setSingleLine(true);
        r.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = new TextView(this);
        v.setTextColor(0x99FFFFFF);
        v.setTypeface(Ui.font(this, "regular"));
        Ui.textSize(v, 15);
        v.setSingleLine(true);
        r.addView(v);
        Runnable refresh = () -> v.setText(value.get());
        refresh.run();
        refreshers.add(refresh);

        r.setOnFocusChangeListener((view, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : 0x14FFFFFF);
            l.setTextColor(has ? 0xFF111114 : Color.WHITE);
            v.setTextColor(has ? 0x99111114 : 0x99FFFFFF);
            view.animate().withLayer().scaleX(has ? 1.03f : 1f).scaleY(has ? 1.03f : 1f).setDuration(Motion.d(180)).start();
        });
        r.setOnClickListener(view -> {
            action.run();
            for (Runnable x : refreshers) x.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.px(this, 40));
        lp.bottomMargin = Ui.px(this, 6);
        list.addView(r, lp);
        return r;
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent ev) {
        MainActivity.navSound(this, ev);
        return super.dispatchKeyEvent(ev) || Clicks.moveFocus(getWindow(), ev);
    }

    AlertDialog.Builder dialog(String title) {
        return Clicks.dialog(this).setTitle(title);
    }

    void refreshAll() {
        for (Runnable x : refreshers) x.run();
    }

    void chooseLocation() {
        String[] names = {"Automatic", "Choose City\u2026"};
        dialog("Weather location").setSingleChoiceItems(names, Weather.manual(this) ? 1 : 0, (d, w) -> {
            d.dismiss();
            if (w == 0) {
                Weather.setPlace(this, null);
                refreshAll();
            } else {
                searchCity();
            }
        }).show();
    }

    void searchCity() {
        android.widget.EditText in = new android.widget.EditText(this);
        in.setSingleLine(true);
        in.setHint("City name, e.g. Bengaluru");
        in.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        int pad = Ui.px(this, 14);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(in);
        dialog("Choose City")
                .setView(box)
                .setPositiveButton("Search", (d, w) -> {
                    String q = in.getText().toString().trim();
                    if (q.isEmpty()) return;
                    new Thread(() -> {
                        List<Weather.Place> found;
                        try {
                            found = Weather.search(q);
                        } catch (Exception e) {
                            found = null;
                        }
                        List<Weather.Place> res = found;
                        runOnUiThread(() -> showCities(q, res));
                    }).start();
                })
                .setNegativeButton("Cancel", null)
                .show();
        in.requestFocus();
    }

    void showCities(String q, List<Weather.Place> res) {
        if (isFinishing()) return;
        if (res == null) {
            Toast.makeText(this, "Couldn't search. Check the internet connection.", Toast.LENGTH_LONG).show();
            return;
        }
        if (res.isEmpty()) {
            Toast.makeText(this, "No city called \u201c" + q + "\u201d", Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = new String[res.size()];
        for (int i = 0; i < names.length; i++) {
            Weather.Place pl = res.get(i);
            names[i] = pl.detail.isEmpty() ? pl.name : pl.name + "  \u00b7  " + pl.detail;
        }
        dialog("Choose City").setItems(names, (d, w) -> {
            Weather.setPlace(this, res.get(w));
            refreshAll();
        }).setNegativeButton("Cancel", null).show();
    }

}
