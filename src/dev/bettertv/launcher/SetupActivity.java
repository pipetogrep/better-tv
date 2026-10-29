package dev.bettertv.launcher;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * First-time setup, in the style of Apple TV's own setup: one question per page, a large title
 * and a short line of help at the top, choices as a centred list (white when focused), and the
 * page's buttons (Back, Continue) as pills under it, so moving on never looks like a choice.
 * Each row says what it is: an On/Off switch, a radio dot (one of a list), or an action with its
 * state on the right ("Allow ›", "✓ Allowed"). Choices change in place; nothing moves on by itself. Pages: Welcome, look, weather,
 * home screen & permissions, done. Shown once; Settings \u2192 Setup \u2192 Run setup again repeats it.
 */
public class SetupActivity extends Activity {
    static final String K_DONE = "setup_done";

    FrameLayout root;
    ImageView backdrop;
    LinearLayout page;
    int index = 0;
    final Handler handler = new Handler(Looper.getMainLooper());

    interface Step {
        void build();
    }

    final List<Step> steps = new ArrayList<>();

    static boolean done(Activity a) {
        return a.getSharedPreferences("setup", MODE_PRIVATE).getBoolean(K_DONE, false);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setBackgroundDrawable(null);  // the page paints its own background
        MainActivity.initWallpapers(this);
        Clicks.init(this);
        Motion.init(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setAlpha(0f);
        root.addView(backdrop, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View dim = new View(this);
        dim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x99000000, 0x66000000, 0xB3000000}));
        root.addView(dim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setClipChildren(false);
        page.setClipToPadding(false);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        page.setPadding(0, u(54), 0, u(28));
        root.addView(page, plp);
        setContentView(root);
        Clicks.quiet(root);

        steps.add(this::hello);
        steps.add(this::look);
        steps.add(this::weather);
        steps.add(this::homeAndPermissions);
        steps.add(this::finish_);
        if (getIntent().getBooleanExtra("skip", false) && Ui.fromAdb(this)) {  // adb: --ez skip true marks setup as done
            getSharedPreferences("setup", MODE_PRIVATE).edit().putBoolean(K_DONE, true).apply();
            finish();
            return;
        }
        show(Ui.fromAdb(this) ? getIntent().getIntExtra("step", 0) : 0, 0);  // adb: --ei step N opens a page directly
    }

    int u(float v) {
        return Ui.px(this, v);
    }

    // ---------------------------------------------------------------- navigation

    void show(int i, int dir) {
        handler.removeCallbacksAndMessages(null);
        index = Math.max(0, Math.min(i, steps.size() - 1));
        page.animate().cancel();
        page.removeAllViews();
        firstChoice = null;
        primaryButton = null;
        buttonsFocus = null;
        steps.get(index).build();
        View f = firstChoice != null ? firstChoice : primaryButton;
        if (f != null) f.requestFocus();
        if (dir != 0) {
            // A new page: its parts settle in one after another (title, help, choices, buttons).
            List<View> parts = new ArrayList<>();
            for (int k = 0; k < page.getChildCount(); k++) parts.add(page.getChildAt(k));
            Motion.cascade(parts, u(10));
        }
        // New page: slide and fade. Same page re-drawn (back from a system screen): a quick fade only.
        page.setAlpha(dir == 0 ? 0.6f : 0f);
        page.setTranslationX(Motion.dist(u(30)) * dir);
        page.animate().alpha(1f).translationX(0).setDuration(Motion.d(dir == 0 ? 120 : 320)).start();
    }

    void next() {
        show(index + 1, 1);
    }

    void back() {
        if (index > 0) show(index - 1, -1);
    }

    @Override
    @SuppressWarnings("deprecation")  // the platform replacement (OnBackInvokedCallback) is Android 13+
    public void onBackPressed() {
        back();  // the first page has nowhere to go back to: setup stays until it is finished
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (index > 0) show(index, 0);  // show what is now allowed
    }

    /** Back from a system screen (a permission, Accessibility, choosing a city): show the new state. */
    boolean leftForSystemScreen;

    @Override
    protected void onPause() {
        super.onPause();
        leftForSystemScreen = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (leftForSystemScreen && index > 0) show(index, 0);
        leftForSystemScreen = false;
    }

    // ---------------------------------------------------------------- building blocks

    TextView title(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTypeface(Ui.font(this, "display"));
        t.setLetterSpacing(-0.02f);
        Ui.textSize(t, 34);
        t.setGravity(Gravity.CENTER);
        page.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    TextView subtitle(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(0xB3FFFFFF);
        t.setTypeface(Ui.font(this, "regular"));
        Ui.textSize(t, 14);
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(u(520), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = u(10);
        lp.bottomMargin = u(22);
        page.addView(t, lp);
        return t;
    }

    LinearLayout list() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setClipChildren(false);
        l.setClipToPadding(false);
        page.addView(l, new LinearLayout.LayoutParams(u(440), ViewGroup.LayoutParams.WRAP_CONTENT));
        return l;
    }

    /** What a choice row's right side shows. */
    static final int SWITCH = 0, RADIO = 1, ACTION = 2;

    /** First choice on the page (or the selected one in a list), focused when the page opens. */
    View firstChoice;
    View primaryButton;
    /** Called when the page's buttons take focus (e.g. Look puts back the chosen background). */
    Runnable buttonsFocus;

    /**
     * A choice row. SWITCH: an On/Off switch, toggled in place. RADIO: one of a list, with a dot.
     * ACTION: opens something (a dialog or a system screen); the right side says its state, e.g.
     * "Allow ›" or "✓ Allowed". Moving on is never a row: it is the button under the list.
     */
    final class Row {
        final LinearLayout view = new LinearLayout(SetupActivity.this);
        final TextView label = new TextView(SetupActivity.this), detail = new TextView(SetupActivity.this);
        final TextView status = new TextView(SetupActivity.this);
        final FrameLayout track = new FrameLayout(SetupActivity.this);
        final View knob = new View(SetupActivity.this);
        final GradientDrawable bg = new GradientDrawable(), trackBg = new GradientDrawable(), knobBg = new GradientDrawable();
        final int kind;
        boolean on, focused, ok;

        Row(int kind) {
            this.kind = kind;
        }

        void set(boolean v) {
            on = v;
            paint();
        }

        /** ACTION rows: the right-hand text, green when done. */
        void status(String s, boolean done) {
            status.setText(s);
            ok = done;
            paint();
        }

        void detail(String s) {
            detail.setText(s);
            detail.setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE);
        }

        void paint() {
            bg.setColor(focused ? 0xF2FFFFFF : 0x26FFFFFF);
            label.setTextColor(focused ? 0xFF111114 : Color.WHITE);
            detail.setTextColor(focused ? 0x99111114 : 0x99FFFFFF);
            int green = focused ? 0xFF1E9E48 : 0xFF30D158;
            if (kind == SWITCH) {
                trackBg.setColor(on ? green : focused ? 0x33111114 : 0x40FFFFFF);
                knob.animate().translationX(on ? u(14) : 0).setDuration(Motion.d(150)).start();
            } else if (kind == RADIO) {
                trackBg.setColor(on ? green : 0);
                trackBg.setStroke(u(1.5f), on ? green : focused ? 0x80111114 : 0x80FFFFFF);
                status.setText(on ? "✓" : "");
                status.setTextColor(Color.WHITE);
            } else {
                status.setTextColor(ok ? green : focused ? 0x99111114 : 0x99FFFFFF);
            }
        }
    }

    Row row(LinearLayout list, String label, String detail, int kind, boolean on, Runnable action) {
        Row r = new Row(kind);
        LinearLayout v = r.view;
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setFocusable(true);
        v.setClickable(true);
        v.setPadding(u(18), u(8), u(16), u(8));
        r.bg.setCornerRadius(u(12));
        v.setBackground(r.bg);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        r.label.setText(label);
        r.label.setTypeface(Ui.font(this, "medium"));
        Ui.textSize(r.label, 15);
        texts.addView(r.label);
        r.detail.setTypeface(Ui.font(this, "regular"));
        Ui.textSize(r.detail, 12);
        r.detail.setSingleLine(true);
        r.detail.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(r.detail);
        r.detail(detail);
        v.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (kind == SWITCH) {
            // A small On/Off switch (track and knob).
            r.trackBg.setCornerRadius(u(9));
            r.track.setBackground(r.trackBg);
            r.knobBg.setShape(GradientDrawable.OVAL);
            r.knobBg.setColor(Color.WHITE);
            r.knob.setBackground(r.knobBg);
            FrameLayout.LayoutParams klp = new FrameLayout.LayoutParams(u(14), u(14), Gravity.CENTER_VERTICAL);
            klp.leftMargin = u(2);
            r.track.addView(r.knob, klp);
            r.knob.setTranslationX(on ? u(14) : 0);
            v.addView(r.track, new LinearLayout.LayoutParams(u(32), u(18)));
        } else if (kind == RADIO) {
            r.trackBg.setShape(GradientDrawable.OVAL);
            r.status.setBackground(r.trackBg);
            r.status.setGravity(Gravity.CENTER);
            r.status.setTypeface(Ui.font(this, "semibold"));
            Ui.textSize(r.status, 11);
            v.addView(r.status, new LinearLayout.LayoutParams(u(18), u(18)));
        } else {
            r.status.setTypeface(Ui.font(this, "medium"));
            Ui.textSize(r.status, 13);
            v.addView(r.status);
        }
        r.on = on;
        r.paint();
        v.setOnFocusChangeListener((x, has) -> {
            r.focused = has;
            r.paint();
            x.animate().scaleX(has ? 1.04f : 1f).scaleY(has ? 1.04f : 1f).setDuration(Motion.d(180)).start();
        });
        v.setOnClickListener(x -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                detail == null || detail.isEmpty() ? u(46) : u(56));
        lp.bottomMargin = u(8);
        list.addView(v, lp);
        if (firstChoice == null || kind == RADIO && on) firstChoice = v;
        return r;
    }

    /** One of a list: selecting a row clears the others. */
    void select(List<Row> group, Row chosen) {
        for (Row r : group) r.set(r == chosen);
    }

    /**
     * The page's buttons, under the choices: Back (from the second page on) and the main one.
     * Pill buttons, so they never look like a choice; Down from the list reaches them.
     */
    void buttons(String primary, Runnable go) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setClipChildren(false);
        if (index > 0) bar.addView(button("Back", false, this::back));
        primaryButton = button(primary, true, go);
        bar.addView(primaryButton);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = u(14);
        page.addView(bar, lp);
    }

    TextView button(String text, boolean primary, Runnable go) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(Ui.font(this, primary ? "semibold" : "medium"));
        Ui.textSize(b, 15);
        b.setFocusable(true);
        b.setClickable(true);
        b.setMinWidth(u(150));
        b.setPadding(u(26), 0, u(26), 0);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(u(20));
        int idle = primary ? 0x59FFFFFF : 0x26FFFFFF;
        g.setColor(idle);
        b.setBackground(g);
        b.setTextColor(Color.WHITE);
        b.setOnFocusChangeListener((v, has) -> {
            g.setColor(has ? 0xFFFFFFFF : idle);
            b.setTextColor(has ? 0xFF111114 : Color.WHITE);
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(Motion.d(180)).start();
            if (has && buttonsFocus != null) buttonsFocus.run();
        });
        b.setOnClickListener(v -> go.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, u(40));
        lp.leftMargin = lp.rightMargin = u(8);
        b.setLayoutParams(lp);
        return b;
    }

    void dots() {
        LinearLayout d = new LinearLayout(this);
        d.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 1; i < steps.size() - 1; i++) {
            View dot = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(i == index ? Color.WHITE : 0x59FFFFFF);
            dot.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(u(6), u(6));
            lp.leftMargin = lp.rightMargin = u(4);
            d.addView(dot, lp);
        }
        View spacer = new View(this);
        page.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        page.addView(d, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    void setBackdrop(String style) {
        Bitmap b = MainActivity.makeWallpaperBase(style);
        backdrop.setImageDrawable(new BitmapDrawable(getResources(), b));
        backdrop.animate().alpha(1f).setDuration(Motion.d(300)).start();
    }

    // ---------------------------------------------------------------- pages

    /** Welcome, then Get Started. */
    void hello() {
        backdrop.animate().alpha(0f).setDuration(Motion.d(300)).start();
        View top = new View(this);
        page.addView(top, new LinearLayout.LayoutParams(1, 0, 1f));
        TextView hi = title("Welcome");
        Ui.textSize(hi, 64);
        hi.setAlpha(0f);
        hi.setTranslationY(u(12));
        hi.animate().alpha(1f).translationY(0).setDuration(Motion.d(700)).start();
        TextView s = subtitle("Let’s set up Better TV. It takes about a minute.");
        Ui.textSize(s, 17);
        buttons("Get Started", this::next);
        View mid = new View(this);
        page.addView(mid, new LinearLayout.LayoutParams(1, 0, 1f));
    }

    void look() {
        title("Choose a Look");
        subtitle("The background behind your apps. Move through the list to preview, press OK to pick.");
        String[] cur = {Prefs.home(this).getString("wallpaper", MainActivity.DEFAULT_WALL)};
        setBackdrop(cur[0]);
        LinearLayout l = list();
        List<Row> group = new ArrayList<>();
        for (int i = 0; i < MainActivity.READY_WALLS; i++) {  // colours and built-in pictures; photos are chosen in Settings
            String key = MainActivity.WALL_KEYS[i];
            Row[] self = new Row[1];
            self[0] = row(l, MainActivity.WALL_NAMES[i], null, RADIO, key.equals(cur[0]), () -> {
                Prefs.home(this).edit().putString("wallpaper", key).apply();
                cur[0] = key;
                select(group, self[0]);
            });
            group.add(self[0]);
            View.OnFocusChangeListener base = self[0].view.getOnFocusChangeListener();
            self[0].view.setOnFocusChangeListener((v, has) -> {
                base.onFocusChange(v, has);
                if (has) setBackdrop(key);
            });
        }
        buttonsFocus = () -> setBackdrop(cur[0]);  // leaving the list: show what was picked
        buttons("Continue", this::next);
        dots();
    }

    void weather() {
        title("Weather");
        subtitle("A small weather card in the top-left corner of Home, with a 5-day forecast.");
        LinearLayout l = list();
        String city = Weather.place(this);
        boolean on = Weather.enabled(this), manual = Weather.manual(this);
        List<Row> group = new ArrayList<>();
        Row[] auto = new Row[1], pick = new Row[1], off = new Row[1];
        auto[0] = row(l, "Use My Location", city.isEmpty() || manual ? "Found from your internet connection" : "Looks like " + city,
                RADIO, on && !manual, () -> {
                    Weather.prefs(this).edit().putBoolean(Weather.K_ENABLED, true).apply();
                    Weather.setPlace(this, null);
                    select(group, auto[0]);
                    int here = index;  // looked up only now that it was chosen
                    Weather.refresh(this, true, () -> {
                        if (index == here && !Weather.place(this).isEmpty()) auto[0].detail("Looks like " + Weather.place(this));
                    });
                });
        pick[0] = row(l, "Choose a City…", manual ? Weather.place(this) : "Search by name (opens Settings)", RADIO, on && manual, () -> {
            Weather.prefs(this).edit().putBoolean(Weather.K_ENABLED, true).apply();
            startActivity(new Intent(this, SettingsActivity.class));
        });
        off[0] = row(l, "Don’t Show Weather", null, RADIO, !on, () -> {
            Weather.prefs(this).edit().putBoolean(Weather.K_ENABLED, false).apply();
            Weather.changed(this);
            select(group, off[0]);
        });
        group.add(auto[0]);
        group.add(pick[0]);
        group.add(off[0]);
        buttons("Continue", this::next);
        dots();
    }

    void homeAndPermissions() {
        title("Finish Setting Up");
        subtitle("Press the row to set it up. You can also do this later in Settings.");
        LinearLayout l = list();
        boolean home = Perms.isDefaultHome(this) || PowerService.HomeButton.active(this);
        Row h = row(l, "Make Better TV the Home Screen", home ? "The Home button opens Better TV"
                : "So the Home button opens Better TV", ACTION, false, () -> Perms.requestDefaultHome(this));
        h.status(home ? "✓ Done" : "Set Up  ›", home);
        buttons("Continue", this::next);
        dots();
    }

    void finish_() {
        backdrop.animate().alpha(0f).setDuration(Motion.d(300)).start();
        View top = new View(this);
        page.addView(top, new LinearLayout.LayoutParams(1, 0, 1f));
        int cs = u(64);
        Bitmap cb = Bitmap.createBitmap(cs, cs, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cc = new android.graphics.Canvas(cb);
        android.graphics.Paint cp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        cp.setColor(0xFF30D158);
        cc.drawCircle(cs / 2f, cs / 2f, cs / 2f, cp);
        cp.setColor(Color.WHITE);
        cp.setStyle(android.graphics.Paint.Style.STROKE);
        cp.setStrokeWidth(cs * 0.09f);
        cp.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        cp.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        android.graphics.Path tick = new android.graphics.Path();
        tick.moveTo(cs * 0.29f, cs * 0.52f);
        tick.lineTo(cs * 0.44f, cs * 0.67f);
        tick.lineTo(cs * 0.72f, cs * 0.37f);
        cc.drawPath(tick, cp);
        ImageView check = new ImageView(this);
        check.setImageBitmap(cb);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(u(64), u(64));
        clp.bottomMargin = u(18);
        page.addView(check, clp);
        title("You\u2019re All Set");
        subtitle("Change any of this later in Settings, from the gear in the top-right corner.");
        buttons("Go to Home Screen", () -> {
            getSharedPreferences("setup", MODE_PRIVATE).edit().putBoolean(K_DONE, true).apply();
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            finish();
            fadeOut();
        });
        View bottom = new View(this);
        page.addView(bottom, new LinearLayout.LayoutParams(1, 0, 1.4f));
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent ev) {
        MainActivity.navSound(this, ev);
        return super.dispatchKeyEvent(ev) || Clicks.moveFocus(getWindow(), ev);
    }

    @SuppressWarnings("deprecation")  // overrideActivityTransition is Android 14+
    void fadeOut() {
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
