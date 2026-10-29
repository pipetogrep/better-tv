package dev.bettertv.launcher;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;

/** Small helpers for starting other apps' screens safely. */
final class Intents {
    private Intents() {
    }

    /** Starts an activity; false if nothing on this TV handles it (or it isn't allowed). */
    static boolean start(Context c, Intent i) {
        try {
            c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }
}
