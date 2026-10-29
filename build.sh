#!/bin/bash
# Builds and signs build/better-tv.apk with plain SDK tools (no Gradle).
set -euo pipefail
cd "$(dirname "$0")"

# JDK 17+ and the Android SDK (build-tools 34.0.0, platform android-34). Override with the usual
# variables: JAVA_HOME, ANDROID_HOME (or ANDROID_SDK_ROOT).
if [ -z "${JAVA_HOME:-}" ] && [ -d "$HOME/.local/jdk-17.0.20.1+1/Contents/Home" ]; then
    JAVA_HOME="$HOME/.local/jdk-17.0.20.1+1/Contents/Home"
fi
if [ -n "${JAVA_HOME:-}" ]; then export JAVA_HOME; export PATH="$JAVA_HOME/bin:$PATH"; fi
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
[ -d "$SDK" ] || SDK="$HOME/Android/Sdk"  # Linux default
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
for f in "$BT/aapt2" "$BT/d8" "$JAR"; do
    [ -e "$f" ] || { echo "Missing $f — install build-tools 34.0.0 and platform android-34 (sdkmanager), or set ANDROID_HOME." >&2; exit 1; }
done
command -v javac >/dev/null || { echo "javac not found — install JDK 17+ or set JAVA_HOME." >&2; exit 1; }

# Version: the commit count (always increasing, the same locally and on GitHub) and the latest tag.
VERSION_CODE="${VERSION_CODE:-$(git rev-list --count HEAD 2>/dev/null || echo 1)}"
VERSION_NAME="${VERSION_NAME:-$(git describe --tags --always --dirty 2>/dev/null | sed 's/^v//' || echo dev)}"

rm -rf build && mkdir -p build/gen build/classes build/dex

"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/unsigned.apk -I "$JAR" --manifest AndroidManifest.xml \
    -A assets -0 ttf --java build/gen --min-sdk-version 28 --target-sdk-version 34 \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" build/res.zip

javac -encoding UTF-8 --release 11 -Xlint:deprecation -classpath "$JAR" \
    -d build/classes $(find src build/gen -name '*.java')
"$BT/d8" --release --min-api 28 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
# A fixed timestamp on classes.dex, so the same source gives the same APK.
(cd build/dex && touch -t 198001010000 classes.dex && zip -qX ../unsigned.apk classes.dex)

"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk

# Signing, first match wins:
# 1. RELEASE_KEYSTORE + RELEASE_KEYSTORE_PASS (+ RELEASE_KEY_ALIAS): GitHub Actions, from secrets.
# 2. ~/.config/bettertv/release.keystore + keystore-password (chmod 600): this machine's copy.
# 3. A throwaway debug.keystore (created on first use, never committed): other machines. Its
#    APKs won't install over a release.
# signing/lineage.bin (public: certificates only) records the move from the original key to the
# release key, so APKs signed with it update installs made with the original key (Android 9+).
LINEAGE=()
# With the lineage, sign with APK Signature Scheme v3 only (Android 9+, which minSdk 28 guarantees):
# v1/v2 would need the original key, which release signing no longer uses.
[ -f signing/lineage.bin ] && LINEAGE=(--lineage signing/lineage.bin --v1-signing-enabled false --v2-signing-enabled false --v3-signing-enabled true --rotation-min-sdk-version 28)
LOCAL_KS="$HOME/.config/bettertv/release.keystore"
if [ -n "${RELEASE_KEYSTORE:-}" ]; then
    [ -n "${RELEASE_KEYSTORE_PASS:-}" ] || { echo "Set RELEASE_KEYSTORE_PASS for $RELEASE_KEYSTORE" >&2; exit 1; }
    "$BT/apksigner" sign --ks "$RELEASE_KEYSTORE" --ks-pass env:RELEASE_KEYSTORE_PASS \
        --ks-key-alias "${RELEASE_KEY_ALIAS:-bettertv}" "${LINEAGE[@]}" --out build/better-tv.apk build/aligned.apk
    echo "Signed with the release key"
elif [ -f "$LOCAL_KS" ]; then
    "$BT/apksigner" sign --ks "$LOCAL_KS" --ks-pass "file:$HOME/.config/bettertv/keystore-password" \
        --ks-key-alias bettertv "${LINEAGE[@]}" --out build/better-tv.apk build/aligned.apk
    echo "Signed with the release key (~/.config/bettertv)"
else
    KS=debug.keystore
    if [ ! -f "$KS" ]; then
        keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias debug \
            -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Better TV debug" >/dev/null
    fi
    "$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
        --out build/better-tv.apk build/aligned.apk
    echo "Signed with a local debug key: this APK won't install over a release"
fi

echo "Built build/better-tv.apk"
