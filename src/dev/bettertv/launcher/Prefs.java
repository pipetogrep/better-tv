package dev.bettertv.launcher;

import android.content.Context;
import android.content.SharedPreferences;

/** Where Home's own settings live: app order, hidden apps, background. */
final class Prefs {
    private Prefs() {
    }

    static SharedPreferences home(Context c) {
        return c.getSharedPreferences("home", Context.MODE_PRIVATE);
    }
}
