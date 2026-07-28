# Ultima Controller Android — Native Bootstrap Milestone

Target: Anbernic RG477V, Android 14, 1280×960 4:3 display.

This package is a controller-focused Android frontend for the pinned DOSBox
Pure libretro core. It uses the device's current orientation and available
window instead of requesting a portrait or landscape rotation.

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
5. DOSBox Pure mounts the private extracted installation and directly launches
   the validated root-level `ULTIMA.EXE`.

Imported files are stored under the app's private `filesDir` and are removed
when the app is uninstalled. The Start menu can replace or remove an import.
The original ZIP is never modified.

Imports created by the earlier `0.2-import` tester must be selected once more.
That build retained only the extracted validation copy; this build also retains
the validated ZIP for reproducible import metadata and later integration work.

The importer rejects unsafe paths, case-insensitive duplicate file names,
missing root-level `ULTIMA.EXE`, more than 1,024 files, ZIPs larger than 64 MiB,
individual expanded files larger than 64 MiB, and expanded installations
larger than 256 MiB.

## Default controls

- D-pad: held directional DOS keys
- A: execute persistent action
- B: Escape
- L/R: previous/next action
- X: action list
- Y: universal letter/number picker
- Start: system menu
- Select: currently sends `Z` as a diagnostic placeholder

Every controller press displays Android `keyCode` and `scanCode`. This keeps
RG477V-specific mapping differences visible while keys are delivered to
DOSBox Pure.

The validated `ULTIMA.EXE` is launched directly. DOSBox Pure's start menu may
appear after the program exits; it accepts the D-pad and **Start → Send Enter**.

## Build

The project requires JDK 17, Android SDK 35, and Gradle 8.9.

```text
git submodule update --init
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
- The validated ZIP and extracted installation are retained under app-private
  storage. The root-level `ULTIMA.EXE` is passed to DOSBox Pure by absolute path
  so the game starts without depending on the core's launcher menu.
- DOSBox Pure runs on a dedicated native frontend thread.
- XRGB8888 software frames are nearest-neighbour scaled and aspect-fitted into
  a platform `SurfaceView`; a 4:3 frame fits the 1280×960 display without an
  orientation request.
- The UI reports video as running only after the first frame is successfully
  posted to the Android surface; until then it keeps visible surface/frame
  diagnostics on screen.
- Stereo PCM is streamed through the Android platform `AudioTrack` API.
- Controller commands are delivered through the libretro keyboard callback.
- Save overlays are isolated by import SHA-256 under app-private storage.

## Current limitations

- Core option UI, save export, save states, and prompt recognition are not
  implemented yet.
- Hardware validation on the RG477V is required before gameplay work expands.
- The first native milestone intentionally uses software video rather than
  OpenGL/Vulkan.

## Licensing

The application is GPL-2.0-or-later. DOSBox Pure `1.0-preview6` is included as
an unmodified, pinned source submodule under the same license terms. See
[`LICENSE`](LICENSE) and [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
