# MiniLaunch

A text-only, monospace, minimalist Android launcher built for digital detox – in the spirit of oLauncher.
Zero dependencies (framework Views only), ~190 KB APK, no background services.

## Install

Download [`release/MiniLaunch.apk`](release/MiniLaunch.apk) and open it on your phone, or:

```sh
adb install -r release/MiniLaunch.apk
```

Then long-press the home screen → settings → "set MiniLaunch as default home app".

Optional one-time grant (enables the grayscale toggle):

```sh
adb shell pm grant dev.minilaunch android.permission.WRITE_SECURE_SETTINGS
```

> The APK is signed with a local debug key – fine for sideloading, not for the Play Store.

## Features

- **Text only, monospace everywhere** – no icons.
- **Header:** greeting, clock, date, battery %, current weather (open-meteo.com, no account).
- **Home apps:** a few, as text, reorderable and renameable.
- **App drawer & search:** accent-insensitive, fuzzy (`yt` → YouTube), auto-launch on single match, web-search fallback.
- **Configurable gestures:** swipe up/down/left/right, double tap, long press, and taps on clock/date/battery/weather → any app or ~20 actions (drawer, shade, lock, recents, widgets page, grayscale, flashlight…).
- **Hidden apps.**
- **Widgets:** any Android widget plus MiniLaunch's own text widgets (screen time + unlocks, to-do, note, countdown, daily prompt, month calendar). Each widget has its own position (under the clock / above apps / below apps / bottom / widgets page). Picker shows previews; settings shows a live layout preview.
- **Digital wellbeing:** daily app limits with reminders, strict mode, a "breathe" mindful pause before opening chosen apps, grayscale toggle.
- **Recents:** shortcut to Samsung's "Show recommended apps" switch (One UI keeps it private, so it can't be flipped directly).
- **Extensive settings GUI:** colours & presets, sizes (in % of default), alignment, letter case, margins, gentle motion on/off, backup/restore to a file.

## Battery design

- Clock/date use `TextClock` (system minute tick, only while visible).
- Battery level: receiver registered only while the home screen is resumed.
- Weather: fetched only on home-screen resume when the cached value is older than the chosen interval.
- App list: `LauncherApps.Callback` (push, no polling).
- App limits: one inexact, non-wakeup `AlarmManager.RTC` alarm armed when you open a limited app; no background monitoring.
- Widgets: `AppWidgetHost` listens only between `onStart`/`onStop`.
- Animations run only in response to touches – nothing moves while idle.
- Accessibility service (optional, for lock/recents) is restricted to MiniLaunch's own package and cannot read window content.

## Build

Requires JDK 17 and the Android SDK (compileSdk 37).

```sh
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

## Code map

| File | What |
|---|---|
| `MainActivity.kt` | home screen, drawer, widgets page, gestures, motion |
| `SettingsActivity.kt` | the whole settings GUI |
| `Prefs.kt` | every setting |
| `Apps.kt` | app list + search |
| `Launcher.kt` | launching with mindful pause / limit checks |
| `Usage.kt` | screen time, limits, reminder alarm |
| `Widgets.kt` | Android widget host + MiniLaunch's own widgets |
| `Pickers.kt` | widget picker with previews, limit dialogs |
| `Ui.kt` | monospace dialog/view toolkit |
| `Actions.kt` | gesture actions |
| `Weather.kt` | open-meteo client |
| `RecentsTweaks.kt` | Samsung recents shortcut |

## License

MIT – see [LICENSE](LICENSE).
