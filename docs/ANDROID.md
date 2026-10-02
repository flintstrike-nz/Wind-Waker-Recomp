# BlueWake on Android

An Android app, built like the iPhone and iPad one: you build it from your own disc on a Mac or a Linux
PC, and install the APK on your own device. It is aimed first at the **Oppo Find N3's inner (main)
screen**, and it runs on other arm64 Android 10+ phones and tablets with Vulkan 1.1.

> [!IMPORTANT]
> **Status: built and packaged, not yet run on a device.** The native libraries cross-compile with the
> Android NDK, the Kotlin app builds, and the builder produces a signed APK (checked with a synthetic
> three-instruction game standing in for the real one, because the development environment had no disc and
> no Android device). Nothing has been *played*: Vulkan start-up, the disc import on a real file picker,
> the controls' geometry, the frame rate and the fold handling are all untested. [What to check first](#what-to-check-first)
> lists them. Do not read anything below as a measurement.

No disc image, game files, textures or saves are included anywhere: the app asks for your own
legally obtained copy of *The Wind Waker* for GameCube, USA (`GZLE01`, revision 0), and checks it.

## What you need

On the computer that builds the app (a Mac or Linux PC, x86-64 or arm64, 25 GB free disk):

- CMake 3.25+, Ninja, Python 3, git, curl, and `clang` (the builder compiles its disc reader with it)
- a JDK 17 or newer
- the Android SDK with the NDK, the platform and the build tools:

  ```sh
  # with Android Studio's SDK Manager, or the command-line tools' sdkmanager:
  sdkmanager "ndk;27.2.12479018" "platforms;android-35" "build-tools;35.0.0" "platform-tools"
  export ANDROID_HOME=~/Android/Sdk      # wherever the SDK is
  ```
- your `GZLE01` revision 0 disc image as an `.iso` (or `.gcm`)
- network access to GitHub and Maven on the first run

On the device: arm64, Android 10 or newer, Vulkan 1.1 (no minimum chip has been established: nothing has
been measured on any device yet), and about 4 GB of free storage (the app keeps a 1.5 GB copy of your disc image, private to it).

## Build

```sh
scripts/android/build_device.sh "/path/to/The Legend Of Zelda The Wind Waker.iso" --apk build/BlueWake.apk
```

Run it with `--source-only` first to check your tools, your disc and the translation in a few minutes.
It then does what the iOS builder does for the game ([BUILDER.md](BUILDER.md)): fetches the pinned
RecompCore and DolRecomp, refuses any disc but GZLE01 rev 0, translates the game, and compares the result
with the recorded digest before the long step. What differs:

| Step | Android |
| --- | --- |
| compile the game module | the NDK compiles it for arm64 into `libgGZLE01_recomp.so`, `-O2`, with `-march=armv8-a -mtune=cortex-x3` (the baseline every arm64 Android device has, scheduled for the Snapdragon 8 Gen 2's big core; never SVE, which Qualcomm's cores do not implement). Expect several hours on a 4-core PC |
| host | `android/native` builds `libmain.so` (the unchanged `runtime/host` sources, GXRuntime, the Aurora renderer on Dawn's Vulkan backend, and SDL3 linked in) and `libbwdisc.so` (the disc importer) |
| app | Gradle (`android/`) packages the libraries with the Kotlin shell and signs the APK with a key the builder makes for you in `build/android/signing/` (keep it: an update installs over the app only if signed by the same key) |

The builder keeps what it has finished, so rerunning the same command resumes; `--start-at STEP` (see
`--help`) restarts from a given step. `--cmake-arg` passes extra arguments to the native configure, for a
network that cannot fetch GitHub's source archives.

To sign with a key of your own, pass `--keystore FILE` (alias `bluewake`). Its password is never taken from
the command line: the builder reads it from `$BLUEWAKE_KEYSTORE_PASSWORD`, or from `FILE.password` beside the
keystore (the one it creates for its own key is written there, readable only by you), and hands it to
`keytool` and Gradle through the environment. Keep both out of version control; the repository audit
rejects `*.keystore`, `*.jks` and `*.password` files.

The toolchain is pinned so two builds of the same source make the same code: NDK 27.2.12479018 and
build-tools 35.0.0 exactly (`--ndk` overrides the NDK and the provenance says so), the Gradle distribution
by SHA-256, the Maven dependencies by `android/gradle/verification-metadata.xml`, and the patched RecompCore
checkout is compared by content with the pin plus `patches/android`. The APK carries a
`BuilderProvenance.json` asset recording these inputs. (`./gradlew lint` is not covered by the dependency
metadata; run it with `--dependency-verification=lenient`.)

The generated source, the translated game code and the APK stay under `build/`, which git ignores, and the
APK is refused anywhere inside the repository that git could commit. The APK holds code translated from
your disc: keep it for your own devices. The same release rules as the other platforms apply
([AGENTS.md](../AGENTS.md)).

**Not in the Android build yet:** profile-guided optimization. The iOS build's measured speed (about 30 FPS)
depends on it ([BUILDER.md](BUILDER.md#optimization-profiles)); the bundled profiles were made by Apple's
LLVM on a Mac and the NDK's LLVM cannot read them, and there is no local training run for Android. The
builder accepts profiles you made yourself (`--composite-pgo`, `--host-pgo`). Until one exists, expect the
busiest scenes to run below full speed.

## Install

```sh
adb install -r build/BlueWake.apk        # USB debugging on; or add --install to the build command
```

or copy the APK to the device and open it (allow "install unknown apps" for the app you open it with).
Android shows a warning because the APK is signed with your own key, not through a store.

## First launch

BlueWake's first screen lists what the game still needs: the game code (built into the app), your disc
image, and the files prepared from it. **Choose disc image…** opens Android's file picker; pick the
`.iso`. The app checks the header before copying anything, copies the disc into its private storage,
checks the disc and prepares `main.dol` and the game's 415 modules from it. Compressed images (RVZ, GCZ,
WIA, CISO) are not accepted on Android yet; convert one to `.iso` with Dolphin first. Then **Play**.

## The Oppo Find N3's main screen

The inner display is 7.82 inches, about 2440 × 2268 pixels: nearly square, 120 Hz. The game's picture is
4:3, which is almost exactly that shape, so the app does not draw the controls over the picture. It makes
the game's surface shorter and puts the controls in the strip underneath:

| Posture | What happens |
| --- | --- |
| Flat, open | The 4:3 picture fills the width at the top; the controls sit in the strip below it (about 167 dp tall; with the Widescreen mod's 16:9 picture the strip is larger). If the strip would be under 140 dp the controls sit over the picture's lower corners |
| Half-folded, hinge across the window (Flex mode, laptop posture) | The picture takes the half above the hinge and the controls the half below |
| Cover screen (folded) | Not enough room under the picture: the controls sit over its lower corners |

**Controls › Placement** forces either arrangement, and **Controls › Move the controls…** drags every
control anywhere, remembered separately for each arrangement. Opacity and size are sliders there too. The
controls hide while a controller is connected (an option); a Select/Back button on a controller opens the
menu, as does Android's back gesture. The app stays in landscape, either way up, and survives folding and
unfolding without restarting the game.

At start the app asks Android for the display's highest refresh rate (120 Hz). **Display › Smooth Motion**
has the options the other platforms have: off, 60 FPS, or 120 FPS (the renderer draws in-between frames
from the game's own 30). It starts off, as on iPhone and iPad.

## Settings and mods

The in-game menu (the ⋯ button, back, or a controller's Select) holds the settings:

- **Display:** resolution (the window's pixels, or 1x to 4x the game's 480 lines; the default is 2x),
  texture filtering, Smooth Motion, the picture's shape (the game's own, or filling the screen), an FPS
  readout.
- **Controls:** the on-screen controls (above), camera inversion, and the physical controller's buttons.
- **Mods:** Widescreen 16:9 or 16:10, HD textures (Dolphin-format packs for GZLE01, which you supply and
  install from the launcher screen: *Install an HD texture pack…* copies the PNG and DDS files from a folder
  you pick), Better Wind Waker and its settings. They apply the next time the game starts; the module reads
  them once, at boot.
- **Game data and saves:** back up the memory card to a file you choose, and share the session log. To
  restore or import saves, or remove the disc image, use the launcher screen (the game must not be running).

**Bringing your Dolphin saves.** Export the save from Dolphin (**Tools › Memory Card Manager › Export**, a
`.gci`, or use the memory card's `.raw` file), copy it to the device, and on the launcher screen choose
**Import a Dolphin save…**. The app lists the file's quest logs (name, hearts, rupees; a damaged one is
left out), you pick one and the BlueWake quest log it replaces, and it is copied in with its checksums
recomputed. Your current saves are first copied to the app's `Backups` folder (the import is cancelled, and
nothing is left behind, if you back out or leave the screen). The game must not be running,
and BlueWake needs a memory card of its own, which the game makes the first time it starts: start it once
and save before importing. USA saves only. It is the iOS app's importer (`dolphin_save_import.c`), checked
here with synthetic saves through a JVM, not yet with a real Dolphin export.

## Where things live

Everything private to the app, under its storage (`/data/data/dev.bluewake.android/files/BlueWake`):
`GZLE01.iso`, `main.dol`, `rels/`, `GZLE01.card` (the saves), `sram.bin`, `logs/` (the newest eight session
logs, with timestamps) and `cache/` (the renderer's shader and pipeline caches). Android does not back any
of it up to the cloud (`allowBackup` is off on purpose). Uninstalling the app deletes it all, so back up
your saves first. A session log is also in `adb logcat -s BlueWake`.

## How it fits together

| Part | Where | What it does |
| --- | --- | --- |
| Launcher | `android/app/.../LauncherActivity.kt`, `DiscImporter.kt`, `SaveFiles.kt` | checks what is missing, imports the disc, backs up and restores saves |
| Game screen | `GameActivity.kt` (an SDL `SDLActivity`) | runs the game, lays out the picture and controls for the fold, 120 Hz |
| Controls | `ControlsView.kt` | the GameCube pad drawn and tracked in one multi-touch view |
| Menu, settings | `GameMenu.kt`, `Prefs.kt` | the settings; launch-time ones become environment variables before the native code loads |
| Fold | `PostureTracker.kt` | Jetpack WindowManager: Flex mode and the hinge |
| Entry | `android/native/src/android_entry.cpp` | `SDL_main`: paths, session log, defaults, then the unchanged host |
| JNI | `android/native/src/jni_bridge.cpp`, `controller_settings_android.cpp`, `disc_jni.c` | the pad, pause, settings, frame statistics, disc import |
| Host | `runtime/host/src` (shared) | the recompiled game's runtime; `touch_platform.h` marks iOS and Android as touch platforms |
| Touch input glue | `apple/ios/src/touch_controls.cpp`, `controller_apply.cpp` (shared with iOS) | the virtual pad, pause reasons, lifecycle |
| Game module | `cmake/composite` | the translated game as `libgGZLE01_recomp.so`, loaded from the app's native library directory |
| Renderer | RecompCore's Aurora (pinned), with `patches/android/recompcore` | WebGPU over Vulkan, through the Dawn Android package (checksummed) |
| SDL's Java glue | `android/app/src/main/java/org/libsdl/app` | SDL 3.4.10, one marked change (a 16 MB game-thread stack) |

## What to check first

Because nothing here has run on a device, the likely first problems, in the order the app meets them:

1. **Start-up.** The session log (the menu's *Share the session log*, or `adb logcat -s BlueWake SDL`)
   shows whether Aurora got a Vulkan device and a surface. The first launch compiles pipelines as scenes
   appear; the seed cache in the APK's assets shortens it.
2. **Disc import** on the real picker, including a disc on an SD card or in a cloud folder.
3. **The strip layout:** the picture's position when the surface is shortened, and that the renderer
   refits it. Flex mode's hinge geometry, folding and unfolding during play, and the cover screen.
4. **Speed.** No profile-guided optimization, no tuning for the Snapdragon 8 Gen 2's mix of cores, and no
   measurement. `[fps]` lines in the session log show frames shown, game speed and CPU use each second.
5. **Controls:** touch targets and the default layout, controller support through SDL, and the Select
   button's menu.

## Troubleshooting

- **"This app was built without the game's code"** (launcher): the APK was packaged without the game
  module. Build with `scripts/android/build_device.sh`; it cannot be made on the device.
- **A build step fails:** each step writes a log under `build/android/logs`, and the error names it.
  Rerunning the same command reuses what is finished. For help, ask on
  [Discord](https://discord.gg/xwHfUD2bxW) or open an issue, with the failing step and the relevant
  excerpt. Review logs for personal paths before posting, and never attach the APK, the disc, the signing
  key, game files or saves.
- **An update will not install over the old app:** it was signed with a different key. Use the same
  `build/android/signing` key, or back up your saves (launcher › *Back up saves…*), uninstall and install
  again.
