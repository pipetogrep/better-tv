#!/bin/bash
# Build, install and launch on the TV, then report start time and frame stats.
# Usage: scripts/dev.sh [build|install|run|perf]   (default: run = build + install + launch)
set -euo pipefail
cd "$(dirname "$0")/.."
TOOLING="${TOOLING:-$HOME/Developer/BetterTV/.tooling}"
export JAVA_HOME="${JAVA_HOME:-$TOOLING/jdk-17.0.21+9}" ANDROID_HOME="${ANDROID_HOME:-$TOOLING/android-sdk}"
export PATH="$JAVA_HOME/bin:$PATH"
ADB="${ADB:-$ANDROID_HOME/platform-tools/adb}"
TV="${TV:-192.168.4.37:5555}"
PKG=dev.bettertv.launcher
APK=build/better-tv.apk
a() { "$ADB" -s "$TV" "$@"; }

build() { ./build.sh; }
install() { a install -r "$APK"; }
launch() { a shell am force-stop "$PKG"; a shell am start -W -n "$PKG/.MainActivity" | grep -E "Activity|TotalTime"; }
perf() {
    a shell dumpsys gfxinfo "$PKG" reset >/dev/null
    a shell 'for k in 22 22 22 22 22 21 21 21 21 21 20 19 22 22 22 20 20 21 21 19 19 22 22 22 22 21 21 21 21 21; do input keyevent $k; sleep 0.35; done'
    echo "-- navigation:"; a shell dumpsys gfxinfo "$PKG" | grep -E "Total frames|^Janky frames:|Missed Vsync"
    a shell dumpsys gfxinfo "$PKG" reset >/dev/null; sleep 4
    echo "-- idle 4 s:"; a shell dumpsys gfxinfo "$PKG" | grep -E "Total frames"
}

case "${1:-run}" in
    build) build ;;
    install) install ;;
    perf) perf ;;
    run) build; install; launch ;;
esac
