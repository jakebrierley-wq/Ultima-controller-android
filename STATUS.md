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
- CI guard that rejects bundled game assets

Not yet implemented:
- DOS emulation core
- Video/audio output
- DOS keyboard queue connection
- Prompt recognition
- Signed release APK
