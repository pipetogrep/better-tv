# Better TV

A fast, calm home screen for Android TV, styled after tvOS. Plain Java, no Gradle, no
third-party runtime libraries; the APK is about 1 MB. Developed on a Google TV Streamer 4K.

## What it does

- App grid with a glass dock, focus lift and zoom-to-open; reorder, hide (More Apps folder),
  app info and uninstall from a long press.
- Weather pill (Open-Meteo, MET Norway or wttr.in; no API keys) with a 5-day forecast.
- Backgrounds: solid dark colours, or your own photos from `Pictures/BetterTV` on the TV —
  one you pick, or a different one each day. Every choice previews full screen in Settings.
- One-page Settings behind the gear in the top-right corner.
- No ads, recommendations, news, accounts, analytics or crash reporting. The only network
  traffic is the weather.

## Install

Download the APK from [Releases](../../releases/latest), then:

```sh
adb connect <tv-ip>:5555
adb install -r better-tv-<version>.apk
adb shell cmd package set-home-activity dev.bettertv.launcher/.MainActivity
```

Releases are signed with the same key as local development builds, so they install over each
other as updates.

On Google TV the remote's Home button may still open the stock launcher (or whatever a key
mapper such as tvQuickActions is set to). Either point that mapper's Home action at Better TV,
or turn on Settings → Home Button → *Home button opens Better TV*. Don't disable the stock
launcher; it isn't needed.

### Background photos

Photos are never bundled with the app. Put them on the TV:

```sh
adb shell mkdir -p /sdcard/Pictures/BetterTV
adb push photo.jpg /sdcard/Pictures/BetterTV/
```

Then choose them in Settings → Background (Better TV asks once for access to pictures).
Photos are shown at the screen's resolution; 3840×2160 JPEGs suit a 4K TV.

### Screensaver

Better TV never keeps the screen awake, so the TV's own screensaver (e.g. Aerial Views) starts
as configured. If it never does, check that Developer options → *Stay awake* is off.

## Build

Needs JDK 17+ and Android SDK build-tools 34.0.0 with platform android-34.

```sh
./build.sh                 # -> build/better-tv.apk
scripts/dev.sh run         # build, install on the TV and launch (TV=host:port)
scripts/dev.sh perf        # frame stats while navigating, and frames drawn while idle
```

`build.sh` signs with `debug.keystore` (created on first build, not committed), or with
`RELEASE_KEYSTORE` / `RELEASE_KEYSTORE_PASS` / `RELEASE_KEY_ALIAS` when set.

## Releases (GitHub Actions)

`.github/workflows/build.yml` builds the APK on every push to `master` (download it from the
run's artifacts). Pushing a version tag publishes a release with the APK attached:

```sh
git tag v1.0.0 && git push origin v1.0.0
```

The workflow signs with the repository secrets `SIGNING_KEYSTORE_B64` (the keystore,
base64), `SIGNING_KEYSTORE_PASS` and `SIGNING_KEY_ALIAS`. Without them it falls back to a
throwaway key, and that APK will not install over a differently signed copy.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE) for attribution.
