# Ultima Controller Android — Runtime Import Milestone

Target: Anbernic RG477V, Android 14, 1280×960 4:3 display.

This package is a startup and controller-input diagnostic shell. It uses the
device's current orientation and available window instead of requesting a
portrait or landscape rotation. `EmulatorBridge` is deliberately a test stub;
there is no DOSBox integration yet.

No Ultima executables, data files, artwork, or other game assets are included
in the repository or APK.

## Import legally owned game files

1. Create a ZIP containing your game installation, with `ULTIMA.EXE` at the
   root of the archive.
2. Open the app's **Start** menu.
3. Choose **Import game ZIP** and select the archive with Android's system file
   picker.
4. Wait for the import summary. The ZIP is copied and validated before the
   previous import is replaced.

Imported files are stored under the app's private `filesDir` and are removed
when the app is uninstalled. The Start menu can replace or remove an import.
The original ZIP is never modified.

The importer rejects unsafe paths, case-insensitive duplicate file names,
missing root-level `ULTIMA.EXE`, more than 1,024 files, ZIPs larger than 64 MiB,
individual expanded files larger than 64 MiB, and expanded installations
larger than 256 MiB.

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
- Game ZIP selection uses the Android platform document picker and does not
  request broad storage permissions.
- Import extraction runs away from the UI thread and atomically replaces the
  current private import only after validation succeeds.

## Out of scope

DOS emulation and executable launch remain intentionally deferred. Importing
files only validates and stores them; it does not execute game code.
