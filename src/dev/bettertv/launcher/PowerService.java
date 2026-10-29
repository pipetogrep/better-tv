package dev.bettertv.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.HashSet;
import java.util.Set;

/**
 * Better TV's accessibility switch, turned on by the user in the TV's Accessibility settings. It
 * only watches the TV's own launcher(s) (its event filter is narrowed to their packages), and:
 * - Home button: when the TV's own launcher comes up (Home pressed, or Back out of an app) while
 *   Better TV isn't the home app, opens Better TV instead, so the stock launcher needn't be disabled. Only
 *   the package and class of the window that opened are looked at, nothing on screen;
 * - Start with the TV: opens the chosen app or input on wake (Android allows an accessibility
 *   service to open screens from the background).
 */
public class PowerService extends AccessibilityService {
    static PowerService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private BroadcastReceiver screenOn;

    @Override
    protected void onServiceConnected() {
        instance = this;
        narrowToStockHomes();
        screenOn = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                Startup.onScreenOn(c);
            }
        };
        registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
        // Bound early after boot, and allowed to open screens: a reliable boot trigger too.
        if (Startup.justBooted()) Startup.onBoot(this);
        // The stock launcher may already be on screen (just booted, or the switch was turned on
        // over it): no window change will be reported for it, so look once now.
        handler.postDelayed(this::homeIfStockShowing, 800);
    }

    /**
     * Least privilege: receive window events (and read window owners) only for the TV's other
     * launchers, not for every app. Re-applied when apps change, since a launcher may be added.
     */
    void narrowToStockHomes() {
        android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;
        Set<String> pkgs = new HashSet<>();
        for (String h : HomeButton.stockHomes(this)) pkgs.add(h.substring(0, h.indexOf('/')));
        info.packageNames = pkgs.isEmpty() ? new String[]{"android"} : pkgs.toArray(new String[0]);
        setServiceInfo(info);
    }

    private void homeIfStockShowing() {
        if (!HomeButton.wanted(this)) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        CharSequence pkg = root == null ? null : root.getPackageName();
        if (pkg == null) return;
        for (String h : HomeButton.stockHomes(this)) {
            if (h.startsWith(pkg + "/")) {
                lastRedirect = android.os.SystemClock.elapsedRealtime();
                startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = e.getPackageName(), cls = e.getClassName();
        if (pkg == null || cls == null || !HomeButton.wanted(this)) return;
        if (!HomeButton.stockHomes(this).contains(pkg + "/" + cls)) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastRedirect < 800) return;  // the stock launcher may report its window twice
        lastRedirect = now;
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private long lastRedirect;

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        if (screenOn != null) unregisterReceiver(screenOn);
        instance = null;
        super.onDestroy();
    }

    /** Home button override (see the class comment); the switch lives in Settings → Home Button. */
    static final class HomeButton {
        private static Set<String> homes;
        private static long homesAt;

        private HomeButton() {
        }

        static boolean enabled(Context c) {
            return c.getSharedPreferences("startup", MODE_PRIVATE).getBoolean("home_override", true);
        }

        static void setEnabled(Context c, boolean on) {
            c.getSharedPreferences("startup", MODE_PRIVATE).edit().putBoolean("home_override", on).apply();
        }

        /** On, and needed: Better TV isn't the home app Android would open. */
        static boolean wanted(Context c) {
            return enabled(c) && !isDefaultHome(c);
        }

        /** Active right now: wanted, and the accessibility switch is on. */
        static boolean active(Context c) {
            return instance != null && wanted(c);
        }

        static boolean isDefaultHome(Context c) {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = c.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            return ri != null && ri.activityInfo != null && c.getPackageName().equals(ri.activityInfo.packageName);
        }

        /**
         * The other launchers' home activities ("package/class"). Android's boot-time FallbackHome
         * in Settings is left alone. Rechecked every minute (apps change).
         */
        static synchronized Set<String> stockHomes(Context c) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (homes != null && now - homesAt < 60_000) return homes;
            Set<String> s = new HashSet<>();
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo r : c.getPackageManager().queryIntentActivities(home, 0)) {
                String pkg = r.activityInfo.packageName;
                if (pkg.equals(c.getPackageName()) || r.activityInfo.name.endsWith("FallbackHome")) continue;
                s.add(pkg + "/" + r.activityInfo.name);
                // An activity-alias reports its target class in window events.
                if (r.activityInfo.targetActivity != null) s.add(pkg + "/" + r.activityInfo.targetActivity);
            }
            homes = s;
            homesAt = now;
            return s;
        }
    }
}
