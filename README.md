# Ultima Controller Android — Milestone 1

Target: Anbernic RG477V, Android 14, 1280×960 portrait display.

This package is a controller/UI validation build. It embeds the supplied Ultima I DOS files and implements the intended persistent command selector and universal key picker. It does **not yet run the DOS executable**; `EmulatorBridge` is deliberately a test stub pending integration with an Android DOSBox core.

## Default controls

- D-pad: directional DOS keys
- A: execute persistent action
- B: Escape
- L/R: previous/next action
- X: action list
- Y: universal letter/number picker
- Start: system menu
- Select: currently sends `Z` as a diagnostic placeholder

Every controller press displays Android `keyCode` and `scanCode`. This identifies any RG477V-specific mapping differences before connecting the keys to DOSBox.

## Build

1. Open this folder in Android Studio.
2. Allow Android Studio to install Android SDK 35 if requested.
3. Select **Build > Build APK(s)**.
4. Install `app/build/outputs/apk/debug/app-debug.apk` on the RG477V.

No configuration files are required in the installed app.

## Milestone 2 integration

Replace `EmulatorBridge` with the selected DOSBox core's keyboard queue and rendering surface. On first launch, copy `assets/game` to app-private writable storage, mount it as `C:`, and execute:

```
C:
CD 
ULTIMA.EXE
```

The save files must remain in app-private storage rather than the read-only APK assets.
