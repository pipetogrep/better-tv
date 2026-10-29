package dev.bettertv.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.animation.PathInterpolator;

import java.util.List;

/**
 * Motion setting (Settings → Motion & Sound), for the whole TV:
 * - Full: every transition at full length.
 * - Reduced: transitions at 60% length and shorter slides.
 * - Off: changes are instant; nothing moves on its own.
 * Every animation duration in Better TV goes through {@link #d}. Read once and cached, since it is used on every frame.
 */
final class Motion {
    static final int FULL = 0, REDUCED = 1, OFF = 2;
    static final String[] NAMES = {"Full", "Reduced", "Off"};

    private static volatile int level = -1;

    private Motion() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("motion", Context.MODE_PRIVATE);
    }

    static void init(Context c) {
        if (level < 0) level = prefs(c).getInt("level", REDUCED);  // snappy, and nothing moves (or redraws) when idle
    }

    static int level() {
        return Math.max(0, level);
    }

    static void set(Context c, int l) {
        level = l;
        prefs(c).edit().putInt("level", l).apply();
    }

    /** An animation's length under the current setting (ms). */
    static long d(long ms) {
        switch (level()) {
            case OFF: return 0;
            case REDUCED: return ms * 3 / 5;
            default: return ms;
        }
    }

    /** A slide or lift distance under the current setting (px). */
    static float dist(float px) {
        switch (level()) {
            case OFF: return 0;
            case REDUCED: return px / 2;
            default: return px;
        }
    }


    /** Apple's ease-out for things settling into place. */
    static final PathInterpolator SETTLE = new PathInterpolator(0.2f, 0f, 0f, 1f);

    /**
     * Items arriving one after another (Apple's cascade): each fades up from slightly below and
     * slightly smaller, 22 ms after the one before. Instant when Motion is Off.
     */
    static void cascade(List<? extends View> views, float lift) {
        if (level() == OFF) return;
        for (int i = 0; i < views.size(); i++) {
            View v = views.get(i);
            v.animate().cancel();
            v.setAlpha(0f);
            v.setTranslationY(dist(lift));
            v.setScaleX(0.97f);
            v.setScaleY(0.97f);
            v.animate().alpha(1f).translationY(0).scaleX(1f).scaleY(1f).setStartDelay(stagger(i))
                    .setDuration(d(420)).setInterpolator(SETTLE).withLayer().start();
        }
    }

    /** Stagger between items appearing one after another (ms). */
    static long stagger(int i) {
        return level() == FULL ? Math.min(i, 12) * 22L : 0;
    }
}
