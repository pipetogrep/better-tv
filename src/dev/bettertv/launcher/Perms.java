package dev.bettertv.launcher;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

/**
 * The two things Better TV asks Android for, on screen wherever the TV allows it (adb only as
 * the last resort): being the home app, and its accessibility switch (Home button on TVs that
 * keep their own launcher, and opening an app when the TV wakes).
 */
final class Perms {
    private Perms() {
    }

    static Intent accessibilityScreen(Activity a) {
        Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        ComponentName cn = i.resolveActivity(a.getPackageManager());
        return cn == null || cn.getClassName().contains("Stub") ? null : i;
    }

    /** Better TV's accessibility switch is on (Home button, waking into an app). */
    static boolean hasAccessibility(Activity a) {
        return PowerService.instance != null;
    }

    /** Opens the TV's Accessibility settings with a short explanation of what the switch is for. */
    static void requestAccessibility(Activity a, String why) {
        if (hasAccessibility(a)) return;
        Intent acc = accessibilityScreen(a);
        if (acc == null) {
            lastResort(a, "Better TV switch", "adb shell settings put secure enabled_accessibility_services "
                    + "dev.bettertv.launcher/dev.bettertv.launcher.PowerService\n"
                    + "adb shell settings put secure accessibility_enabled 1");
            return;
        }
        // Switched on but not running: Android stops it when the app is force-stopped and only
        // restarts it on reboot or when the switch is turned off and on again.
        String on = Settings.Secure.getString(a.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean stale = on != null && on.contains(a.getPackageName() + "/");
        Clicks.dialog(a)
                .setTitle(stale ? "Restart Better TV\u2019s switch" : "Turn on Better TV\u2019s switch")
                .setMessage(why + (stale
                        ? "\n\nIt is on but Android has paused it. In the next screen, choose \u201cBetter TV\u201d, turn it off, then on again."
                        : "\n\nIn the next screen, choose \u201cBetter TV\u201d and turn it on."))
                .setPositiveButton("Open Accessibility", (d, w) -> tryStart(a, acc))
                .setNegativeButton("Cancel", null).show();
    }

    /** Starts a system screen if the TV really has it (many TVs map settings actions to empty stubs). */
    private static boolean tryStart(Activity a, Intent i) {
        ComponentName cn = i.resolveActivity(a.getPackageManager());
        if (cn == null || cn.getClassName().contains("Stub")) return false;
        try {
            a.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- default home screen

    static boolean isDefaultHome(Activity a) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        android.content.pm.ResolveInfo ri = a.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null && a.getPackageName().equals(ri.activityInfo.packageName);
    }

    /**
     * Become the home screen: Android 10+'s "Set as default home app?" prompt, else the TV's Home
     * app settings (when it is a real screen), else Android's chooser, and only then the command.
     */
    static void requestDefaultHome(Activity a) {
        if (isDefaultHome(a)) return;
        if (Build.VERSION.SDK_INT >= 29) {
            android.app.role.RoleManager rm = a.getSystemService(android.app.role.RoleManager.class);
            if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)) {
                try {
                    a.startActivityForResult(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME), 42);
                    return;
                } catch (Exception ignored) {
                    // fall through
                }
            }
        }
        if (tryStart(a, new Intent(Settings.ACTION_HOME_SETTINGS))) return;
        // With no default chosen yet, asking for Home shows Android's chooser ("Always" makes it stick).
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        android.content.pm.ResolveInfo def = a.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        boolean noDefault = def == null || def.activityInfo == null || "android".equals(def.activityInfo.packageName);
        if (noDefault && tryStart(a, home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return;
        // TVs that keep their own launcher (Google TV): Better TV can take over the Home button instead.
        if (accessibilityScreen(a) != null || hasAccessibility(a)) {
            PowerService.HomeButton.setEnabled(a, true);
            requestAccessibility(a, "This TV doesn\u2019t let you choose another home app. With Better TV\u2019s switch on, "
                    + "Better TV opens whenever the TV\u2019s own home screen appears, so the Home button brings you here.");
            return;
        }
        lastResort(a, "Home screen",
                "adb shell cmd package set-home-activity dev.bettertv.launcher/.MainActivity\n"
                        + "(on Google TV also: adb shell pm disable-user --user 0 com.google.android.tvlauncher)");
    }

    /** Only when the TV has no screen for it: explain plainly, with the command for whoever installs the TV. */
    private static void lastResort(Activity a, String what, String cmd) {
        Clicks.dialog(a)
                .setTitle(what)
                .setMessage("This TV's settings have no switch for this permission. Whoever set up the TV can turn it on "
                        + "once from a computer on the same network:\n\n" + cmd)
                .setPositiveButton("OK", null)
                .setNeutralButton("App info", (d, w) -> tryStart(a, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + a.getPackageName()))))
                .show();
    }
}
