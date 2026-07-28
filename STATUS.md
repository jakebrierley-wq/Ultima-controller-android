# Current status

Implemented:
- RG477V Android 14 cold-launch validation
- Orientation-neutral 4:3 diagnostic interface
- Platform-only startup activity with no AndroidX runtime dependency
- Activity state restoration across normal configuration changes
- Persistent Questron-style selected action
- L/R action cycling
- Controller-operated action list
- Universal A–Z, 0–9, Space, Enter, Escape picker
- Raw Android controller keyCode/scanCode reporting
- No external configuration files
- No copyrighted game files in the repository or APK
- Runtime ZIP selection through Android's system document picker
- Bounded, path-safe ZIP extraction into app-private storage
- Atomic import replacement, interrupted-replacement recovery, and removal
- Import archive checksum and file/size summary
- Retained app-private content ZIP for the emulator core
- Pinned DOSBox Pure 1.0-preview6 source submodule
- Arm64 Android NDK build with 16 KiB ELF page alignment
- Minimal libretro environment, video, audio, input, and lifecycle frontend
- 4:3-aware nearest-neighbour software rendering to a platform SurfaceView
- Stereo PCM output through Android AudioTrack
- DOS keyboard event delivery from the persistent controller shell
- Per-import app-private DOSBox Pure save directory
- GPL-2.0-or-later project and third-party notices
- CI guard that rejects bundled game assets

Not yet implemented:
- Prompt recognition
- Core option editor
- Save export and save states
- RG477V native-core hardware acceptance test
- Signed release APK
