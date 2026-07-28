# Third-party notices

## DOSBox Pure

This application builds DOSBox Pure from an unmodified, pinned Git submodule:

- Project: <https://github.com/schellingb/dosbox-pure>
- Release: `1.0-preview6`
- Commit: `a4a0bab7f8931433588f2fcad9045c85b277373d`
- License: GNU General Public License version 2 or later
- License text: `third_party/dosbox-pure/LICENSE`
- Authors and acknowledgements: `third_party/dosbox-pure/DOSBOX-AUTHORS` and
  `third_party/dosbox-pure/DOSBOX-THANKS`

The Android frontend and the core are built together from source. Clone this
repository with submodules, or run `git submodule update --init`, to obtain the
complete corresponding source used for the APK.

DOSBox Pure's repository has a strict policy against LLM/AI-generated upstream
issues and contributions. This project does not modify the submodule or submit
generated changes upstream. Any future upstream interaction must follow the
maintainer's policy.

## Game content

No Ultima executables, data, artwork, or other game content is distributed by
this repository or its APK. Users select their own legally obtained archive at
runtime. The archive, extracted validation copy, and generated save data remain
inside Android app-private storage.
