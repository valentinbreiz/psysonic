# Psysonic on mobile (Android PoC)

Status: **proof of concept** — the app compiles for Android and runs as a debug APK.
Tracking issue: [#1374](https://github.com/Psychotoxical/psysonic/issues/1374).

The Rust core (rodio + symphonia audio engine, library index, sync, Orbit over the
Subsonic API) is shared with desktop unchanged. Desktop-only integrations are gated
behind Tauri's `cfg(desktop)` / `cfg(mobile)` flags:

| Area | Mobile behaviour |
| --- | --- |
| System tray (`tray_runtime`, `sync::tray`) | stubbed no-ops (`src/tray_runtime_mobile.rs`, `sync/tray_mobile.rs`) |
| Global shortcuts, window-state, updater, process, single-instance plugins | not compiled in (target-gated in `Cargo.toml`) |
| souvlaki OS media controls (MPRIS/SMTC) | not compiled in — MediaSession integration is a follow-up |
| Mini player window | commands are no-ops (single-activity platform) |
| Self-update prompt (frontend) | skipped via `IS_MOBILE_PLATFORM` |

Webview capabilities for mobile live in `src-tauri/capabilities/mobile.json`
(`default.json` stays desktop-only via its `platforms` list).

One integration quirk worth knowing: nothing in the Tauri stack (tao/wry) fills
the `ndk-context` statics that cpal's AAudio host reads to reach the Java
`AudioManager` — without help the app aborts at startup with *"android context
was not initialized"*. `run()` bridges tao's JNI context into `ndk-context`
before the audio engine starts (see the android block at the top of `run()`).

## Building the Android app

Prerequisites: Android SDK + NDK, JDK 17+, Rust Android targets:

```sh
rustup target add aarch64-linux-android x86_64-linux-android
export ANDROID_HOME="$HOME/Android/Sdk"
export NDK_HOME="$ANDROID_HOME/ndk/<version>"
# opusic-sys builds libopus with CMake, which looks for these two —
# NDK_HOME/ANDROID_HOME alone are not enough:
export ANDROID_NDK_ROOT="$NDK_HOME"
export ANDROID_NDK="$NDK_HOME"
```

`src-tauri/gen/` is gitignored, so generate the Gradle project once:

```sh
npx tauri android init
```

Then build (or `npx tauri android dev` with a device/emulator attached):

```sh
npx tauri android build --apk --target aarch64 --debug
```

The APK lands in `src-tauri/gen/android/app/build/outputs/apk/`.

## Known gaps (next steps)

- **Background playback**: needs an Android foreground service + MediaSession
  (notification with play/pause/next, audio focus, headset events). Until then,
  playback stops when the app is backgrounded long enough for the OS to suspend it.
- **Media controls**: the `mpris_*` commands are no-ops on mobile; wire them to
  MediaSession / MPNowPlayingInfoCenter.
- **Storage paths**: library index / Hot Cache land in the app-private dir; sizes
  and eviction defaults were tuned for desktop disks.
- **UI**: the responsive/mobile layouts exist but were designed for narrow desktop
  windows, not touch — expect rough edges (hover menus, drag interactions).
- **iOS**: untested; needs a macOS host and an Apple developer account.
- **CI / release**: no Android build in CI yet, APK is debug-signed only.
