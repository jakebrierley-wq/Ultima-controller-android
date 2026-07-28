# Ultima Controller Android — Milestone 1

Target: Anbernic RG477V, Android 14, 1280×960 4:3 display.

This package is a startup and controller-input diagnostic shell. It uses the
device's current orientation and available window instead of requesting a
portrait or landscape rotation. `EmulatorBridge` is deliberately a test stub;
there is no DOSBox integration yet.

No Ultima executables, data files, artwork, or other game assets are included
in the repository or APK.

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

The project requires JDK 17, Android SDK 35, and Gradle 8.9.

```text
gradle :app:assembleDebug
gradle :app:testDebugUnitTest
gradle :app:lintDebug
```

Install `app/build/outputs/apk/debug/app-debug.apk` on the RG477V.

No configuration files are required in the installed app.

## Startup design

- `MainActivity` extends the Android platform `Activity`.
- The manifest does not request an orientation or intercept configuration
  changes.
- The interface uses weighted sizing and density-independent padding so it
  fills the available 4:3 window while leaving system bars usable.
- Android recreates the activity normally after a real configuration change;
  the selected diagnostic action is restored from instance state.

## Out of scope

DOS emulation, game-file handling, and executable launch are intentionally
deferred until the shell has been verified to launch and remain open on the
target Android 14 hardware.
