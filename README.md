# Better TV

A fast, calm home screen for Android TV, styled after tvOS. Plain Java, no Gradle, no
third-party runtime libraries; the APK is about 1 MB. Developed on a Google TV Streamer.

## What it does

- App grid with a glass dock, focus lift and zoom-to-open; reorder, hide (More Apps folder), app info, uninstall.
- Weather pill (Open-Meteo, MET Norway or wttr.in; no API keys) with a 5-day forecast.
- Control Center: power off, memory clean, network, Do Not Disturb, audio, sleep timer, motion, sounds.
- Backgrounds: built-in gradients or **Your Photo** — a different picture each day from
  `Pictures/BetterTV` on the TV.
- No ads, recommendations, news, accounts, analytics or crash reporting.

## Build and run

Needs JDK 17+ and Android SDK build-tools 34.0.0 with platform android-34.

```sh
./build.sh                 # -> build/better-tv.apk
scripts/dev.sh run         # build, install on the TV, launch (TV=host:port, defaults in the script)
scripts/dev.sh perf        # frame stats while navigating, and frames drawn while idle
```

Make it the home screen once it works on your TV:

```sh
adb shell cmd package set-home-activity dev.bettertv.launcher/.MainActivity
```

On Google TV the Home button still opens the stock launcher; turn on Better TV's switch in
Settings → Home → *Home button opens Better TV* instead of disabling the stock launcher.

Add background photos:

```sh
adb shell mkdir -p /sdcard/Pictures/BetterTV
adb push photo.jpg /sdcard/Pictures/BetterTV/
```

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE) for attribution.
