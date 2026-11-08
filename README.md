# MiniLaunch

Text-only Android launcher, loosely inspired by oLauncher. I wanted something that makes
the phone a bit more boring to pick up, without draining the battery.

No icons, monospace everywhere, no dependencies beyond the Android framework (APK is ~190 KB).

## Install

Grab the APK from [Releases](https://github.com/petr6505/MiniLaunch/releases) or
[`release/MiniLaunch.apk`](release/MiniLaunch.apk), install it, then long-press the home
screen → settings → set as default home app.

Grayscale toggle needs one adb command:

```
adb shell pm grant dev.minilaunch android.permission.WRITE_SECURE_SETTINGS
```

## What's in it

- clock, date, battery, weather (open-meteo) and an optional greeting at the top
- a handful of home apps, renameable
- drawer with fuzzy search, auto-launches when only one app matches
- configurable swipes / double tap / long press / taps on the status line
- hidden apps
- regular Android widgets plus a few built-in text ones (screen time, to-do, note,
  countdown, daily prompt, calendar), each placeable wherever you want
- daily app limits with reminders, optional "breathe" pause before opening certain apps
- grayscale toggle
- everything configurable from the settings screen, with backup/restore

## Battery

Nothing runs in the background. Weather and battery are only updated while the home screen
is visible, widgets only listen between onStart/onStop, and app limits use a single inexact
alarm set when you open a limited app. Animations only play when you touch something.

## Samsung notes

The "recommended apps" row in recents belongs to One UI Home and can't be changed by
another app - settings → system has a shortcut to the switch.

Lock-screen and recents gestures need the accessibility service enabled (it's restricted
to MiniLaunch's own package and can't read anything on screen).

## Building

JDK 17 + Android SDK 37:

```
./gradlew assembleRelease
```

## Disclaimer

This app was co-developed with AI assistance.

## License

MIT
