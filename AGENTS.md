# Agent instructions

## Releases

Public releases are allowed, including compiled app builds, following the model of the maintainer's
other recompilation projects (Wave Race 64 Recompiled, the Donkey Kong Country recomps): the app
contains the statically recompiled game code (the translated `gGZLE01_recomp.dylib`), and the player
supplies their own legally obtained disc (GZLE01, revision 0), which the app checks before it runs.

A release must never include:

- the disc image, or game files, assets or textures extracted from it;
- saves, memory card images or personal settings;
- third-party texture packs or other content the project may not redistribute.

Give each release a record of what it was built from (source revision, pinned dependencies,
checksums), the licenses and third-party notices, and install notes. `scripts/ios/build_device.sh
DISC.iso --ipa OUT.ipa` remains the way to build a personal iPad app from a disc, and
`scripts/android/build_device.sh DISC.iso --apk OUT.apk` the way to build a personal Android app (an APK
carries the translated game module `libgGZLE01_recomp.so`, so the rules above apply to it equally).
