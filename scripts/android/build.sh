#!/usr/bin/env bash
# BlueWake Android Builder: turn your own game disc into your own Android app, on
# a Mac or a Linux PC.
#
#   scripts/android/build.sh DISC.iso [--apk OUT.apk] [options]
#
# It is scripts/builder/build.sh for Android. The game-specific work (the disc
# checks, the pinned translator, the translated game and its verified digest, the
# mods) is the BlueWake profile's, shared with the iOS builder; what differs is
# the target: the game module is compiled with the Android NDK for arm64 into
# libgGZLE01_recomp.so, the host (runtime/host + GXRuntime + the Aurora renderer
# on Vulkan + SDL3) into libmain.so, and Gradle packages them with the Kotlin
# shell (android/app) into a signed APK. docs/ANDROID.md explains the whole flow.
#
# Steps, each logged under OUT/logs:
#   1 tools, 2 dependencies, 3 extract the game from the disc, 4 translate,
#   5 generate the composite source (checked against its verified digest),
#   6 mods, 7 compile the game module (the long step), 8 build the native host,
#   9 package and sign the APK
#
# Options:
#   --apk FILE               write the signed APK there (default OUT/BlueWake.apk). It
#                            contains the game code translated from YOUR disc: it is for
#                            you only, never share or upload it
#   --out DIR                build directory (default build/android; git-ignored)
#   --jobs N                 parallel compile jobs (default: all cores)
#   --no-mods                skip the Widescreen and Better Wind Waker variants
#   --sdk DIR                Android SDK (default $ANDROID_HOME or $ANDROID_SDK_ROOT)
#   --ndk DIR                Android NDK r27 (default: the newest 27.x in the SDK)
#   --cpu FLAGS              compiler flags choosing the CPU for the game module and the
#                            host (default "-march=armv8-a -mtune=cortex-x3": the Armv8-A
#                            baseline every arm64 Android device has, scheduled for the
#                            Snapdragon 8 Gen 2's big core. Do not use -mcpu=cortex-a715 or
#                            -march=armv9-a: they allow SVE, which Qualcomm's cores lack)
#   --composite-pgo FILE     LLVM .profdata for the game module (made with the NDK's LLVM)
#   --host-pgo FILE          LLVM .profdata for the host
#   --keystore FILE          signing keystore (alias "bluewake"); default OUT/signing/, made on
#                            first use. Keep it: an update installs over the app only if it is
#                            signed by the same key
#                            Its password comes from $BLUEWAKE_KEYSTORE_PASSWORD or the file
#                            KEYSTORE.password next to it (never from the command line)
#   --install                install the APK with adb (a device in USB debugging mode)
#   --cmake-arg ARG          an extra argument for the native CMake configure (repeatable),
#                            e.g. -DFETCHCONTENT_SOURCE_DIR_SDL=... on a network that cannot
#                            reach GitHub's source archives
#   --start-at STEP          resume at deps|extract|translate|generate|mods|composite|host|apk,
#                            reusing what is in OUT (after a failed step was fixed)
#   --accept-new-composite   continue if the generated source differs from the verified one
#   --source-only            stop after step 5: checks the tools, the disc and the translation
#                            in minutes, before the long compile
#
# The disc, the extracted files, the translated code and the app stay in the build
# directory, which git ignores. Nothing is uploaded.
set -euo pipefail

root=$(cd "$(dirname "$0")/../.." && pwd)
cd "$root"

iso="" out="" apk="" sdk="" ndk="" keystore=""
keystore_password=${BLUEWAKE_KEYSTORE_PASSWORD:-}
jobs=""
mods=1 accept_new=0 source_only=0 install=0 start_at=deps
cpu_flags="-march=armv8-a -mtune=cortex-x3"
composite_pgo="" host_pgo=""
cmake_args=()
opt_level=2

die() { echo "android-builder: $*" >&2; exit 1; }
step() { echo; echo "==> $*"; }

while [ $# -gt 0 ]; do
    case "$1" in
        --apk|--out|--jobs|--sdk|--ndk|--cpu|--composite-pgo|--host-pgo|--keystore|--cmake-arg|--start-at)
            [ $# -ge 2 ] && [ -n "$2" ] || die "$1 needs a value" ;;
    esac
    case "$1" in
        --apk) apk=$2; shift 2 ;;
        --out) out=$2; shift 2 ;;
        --jobs) jobs=$2; shift 2 ;;
        --no-mods) mods=0; shift ;;
        --sdk) sdk=$2; shift 2 ;;
        --ndk) ndk=$2; shift 2 ;;
        --cpu) cpu_flags=$2; shift 2 ;;
        --composite-pgo) composite_pgo=$2; shift 2 ;;
        --host-pgo) host_pgo=$2; shift 2 ;;
        --keystore) keystore=$2; shift 2 ;;
        --install) install=1; shift ;;
        --cmake-arg) cmake_args+=("$2"); shift 2 ;;
        --start-at) start_at=$2; shift 2 ;;
        --accept-new-composite) accept_new=1; shift ;;
        --source-only) source_only=1; shift ;;
        -h|--help) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 0 ;;
        -*) die "unknown option $1" ;;
        *) [ -z "$iso" ] || die "one disc image only"; iso=$1; shift ;;
    esac
done

case "$start_at" in deps|extract|translate|generate|mods|composite|host|apk) ;; *) die "--start-at: unknown step $start_at" ;; esac
[ -n "$iso" ] || die "usage: scripts/android/build.sh DISC.iso [--apk OUT.apk] [options] (--help)"
[ -f "$iso" ] || die "disc image not found: $iso"
iso=$(cd "$(dirname "$iso")" && pwd)/$(basename "$iso")

if [ -z "$jobs" ]; then
    jobs=$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)
fi
[[ "$jobs" =~ ^[1-9][0-9]*$ ]] || die "--jobs must be a positive integer"
[[ "$cpu_flags" =~ ^[-a-zA-Z0-9_.=+\ ]+$ ]] || die "invalid --cpu"

# The profile: the pinned sources, the verified digest, and the game's own steps.
# Its iOS-specific hooks (dependencies, compile, app) are replaced below.
# shellcheck source=../builder/profiles/bluewake.sh
. "$root/scripts/builder/profiles/bluewake.sh"
unset -f profile_dependencies profile_compile profile_build_app profile_train profile_check_tools

out=${out:-$root/build/android}
mkdir -p "$out"
out=$(cd "$out" && pwd)
case "$out" in
    "$root") die "--out must not be the source checkout itself; use build/android" ;;
    "$root"/*) git check-ignore -q "$out/" || die "--out inside this checkout must be git-ignored; use build/android" ;;
    *) echo "android-builder: using external private build directory $out" ;;
esac
apk=${apk:-$out/BlueWake.apk}
case "$apk" in *.apk) ;; *) die "--apk needs a file name ending in .apk" ;; esac
mkdir -p "$(dirname "$apk")"
apk=$(cd "$(dirname "$apk")" && pwd)/$(basename "$apk")
# The APK holds game code: keep it out of anything git could commit.
case "$apk" in "$root"/*)
    git check-ignore -q "$apk" || die "$apk is inside the repository but not ignored; write it under build/ or outside the repository" ;;
esac
for f in "$composite_pgo" "$host_pgo" "$keystore"; do
    [ -z "$f" ] || [ -f "$f" ] || die "file not found: $f"
done
abspath() { echo "$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"; }
[ -z "$composite_pgo" ] || composite_pgo=$(abspath "$composite_pgo")
[ -z "$host_pgo" ] || host_pgo=$(abspath "$host_pgo")
[ -z "$keystore" ] || keystore=$(abspath "$keystore")

logs=$out/logs
mkdir -p "$logs"
source_commit=$(git rev-parse HEAD)
source_modified=false
[ -z "$(git status --porcelain)" ] || source_modified=true
run() {  # run LOGNAME command...: periodic progress plus complete file log
    local log=$logs/$1.log; shift
    if ! python3 "$root/scripts/builder/run_stage.py" --log "$log" -- "$@"; then
        die "failed: $* (full log $log)"
    fi
}

# macOS has shasum, most Linux systems sha256sum: the profile uses `shasum -a 256`.
if ! command -v shasum >/dev/null 2>&1; then
    shasum() { if [ "${1:-}" = "-a" ]; then shift 2; fi; sha256sum "$@"; }
fi
sha256_of() { shasum -a 256 "$1" | awk '{print $1}'; }

steps=(deps extract translate generate mods composite host apk)
should_run() {  # true once STEP is at or after --start-at
    local s want=$1 started=0
    for s in "${steps[@]}"; do
        [ "$s" != "$start_at" ] || started=1
        [ "$s" != "$want" ] || { [ "$started" -eq 1 ]; return; }
    done
    return 1
}

echo "Building $PROFILE_TITLE for Android from $iso"

# ------------------------------------------------------------------ 1 tools

step "1/9 tools"
for tool in cmake ninja python3 git curl clang java keytool unzip; do
    command -v "$tool" >/dev/null || die "missing $tool (see docs/ANDROID.md: CMake 3.25+, Ninja, Python 3, git, curl, clang and a JDK 17+ are required)"
done
cmake_version=$(cmake --version | head -1 | awk '{print $3}')
python3 - "$cmake_version" <<'EOF' || die "CMake 3.25 or newer is required"
import sys
v = tuple(int(x) for x in sys.argv[1].split('.')[:2])
sys.exit(0 if v >= (3, 25) else 1)
EOF
java_line=$(java -version 2>&1 | grep -m1 ' version "' || true)
java_major=$(printf '%s' "$java_line" | sed -E 's/.* version "([0-9]+)[."].*/\1/')
[[ "$java_major" =~ ^[0-9]+$ ]] && [ "$java_major" -ge 17 ] || die "a JDK 17 or newer is required (found: ${java_line:-none})"

sdk=${sdk:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}}
[ -n "$sdk" ] && [ -d "$sdk" ] || die "the Android SDK was not found: pass --sdk DIR or set ANDROID_HOME (docs/ANDROID.md says how to install it)"
sdk=$(cd "$sdk" && pwd)
# The toolchain is pinned, so two builds of the same source make the same code: this exact NDK and
# these exact build-tools (--ndk overrides the NDK, and the provenance says so).
NDK_VERSION=27.2.12479018
BUILD_TOOLS_VERSION=35.0.0
sdkmanager_hint="install with: sdkmanager \"ndk;$NDK_VERSION\" \"platforms;android-35\" \"build-tools;$BUILD_TOOLS_VERSION\" \"platform-tools\""
ndk_override=false
if [ -z "$ndk" ]; then
    ndk=$sdk/ndk/$NDK_VERSION
else
    ndk_override=true
fi
[ -f "$ndk/build/cmake/android.toolchain.cmake" ] || die "Android NDK $NDK_VERSION not found in $sdk/ndk ($sdkmanager_hint)"
ndk=$(cd "$ndk" && pwd)
[ -d "$sdk/platforms/android-35" ] || die "Android platform 35 is missing from $sdk ($sdkmanager_hint)"
build_tools=$sdk/build-tools/$BUILD_TOOLS_VERSION
[ -x "$build_tools/apksigner" ] || die "Android build-tools $BUILD_TOOLS_VERSION are missing from $sdk ($sdkmanager_hint)"
ndk_revision=$(sed -n 's/^Pkg.Revision *= *//p' "$ndk/source.properties" 2>/dev/null | head -1)
ndk_bin=$(ls -d "$ndk"/toolchains/llvm/prebuilt/*/bin | head -1)
export ANDROID_HOME=$sdk ANDROID_SDK_ROOT=$sdk
if [ -n "$install" ] && [ "$install" -eq 1 ]; then
    adb=$sdk/platform-tools/adb
    [ -x "$adb" ] || adb=$(command -v adb || true)
    [ -n "$adb" ] || die "--install needs adb (sdkmanager \"platform-tools\")"
fi
echo "cmake $cmake_version, jdk $java_major, ndk ${ndk_revision:-$(basename "$ndk")}, build-tools $BUILD_TOOLS_VERSION, $jobs jobs"

# ------------------------------------------------------------------ 2 dependencies
# The same pinned RecompCore and DolRecomp as the iOS builder (the profile's),
# in their own checkout so the iOS builder's "pinned source exactly" check is
# unaffected, with the small Android changes (patches/android/recompcore) applied.

DAWN_ANDROID_URL=https://github.com/encounter/dawn/releases/download/v20260618.032059/dawn-android-aarch64.tar.gz
DAWN_ANDROID_SHA256=ee54be2311b714a7c079629654317b06737909aa30829c44c694907dec361060

android_dependencies() {
    recompcore=$root/ref/recompcore-android
    local fresh=0
    if [ ! -e "$recompcore/.git" ]; then
        if [ -e "$recompcore" ] && [ -n "$(ls -A "$recompcore")" ]; then
            die "ref/recompcore-android exists but is not a git checkout: move it aside and rerun"
        fi
        mkdir -p "$recompcore"
        git -C "$recompcore" init -q
        fresh=1
    fi
    if [ "$(git -C "$recompcore" rev-parse HEAD 2>/dev/null || true)" != "$RECOMPCORE_SHA" ]; then
        if [ -n "$(git -C "$recompcore" status --porcelain --untracked-files=no 2>/dev/null || true)" ]; then
            die "ref/recompcore-android has local changes and is not at $RECOMPCORE_SHA: move it aside and rerun"
        fi
        echo "fetching RecompCore $RECOMPCORE_SHA (GXRuntime, vendored Aurora, DolRecomp pointer)"
        git -C "$recompcore" remote remove bluewake >/dev/null 2>&1 || true
        git -C "$recompcore" remote add bluewake "$RECOMPCORE_URL"
        if [ "$fresh" -eq 1 ]; then
            run recompcore-fetch git -C "$recompcore" fetch --depth 1 bluewake "$RECOMPCORE_SHA"
        else
            run recompcore-fetch git -C "$recompcore" fetch bluewake "$RECOMPCORE_SHA"
        fi
        git -C "$recompcore" checkout -q --detach FETCH_HEAD
    fi
    [ "$(git -C "$recompcore" rev-parse HEAD)" = "$RECOMPCORE_SHA" ] || die "ref/recompcore-android is not at $RECOMPCORE_SHA"
    git -C "$recompcore" submodule sync -q -- DolRecomp
    if [ "$(git -C "$recompcore/DolRecomp" rev-parse HEAD 2>/dev/null || true)" != "$DOLRECOMP_SHA" ]; then
        run dolrecomp-fetch git -C "$recompcore" submodule update --init --depth 1 -- DolRecomp
    fi
    [ "$(git -C "$recompcore/DolRecomp" rev-parse HEAD)" = "$DOLRECOMP_SHA" ] || die "ref/recompcore-android/DolRecomp is not at $DOLRECOMP_SHA"
    [ -z "$(git -C "$recompcore/DolRecomp" status --porcelain --untracked-files=no)" ] || die "ref/recompcore-android/DolRecomp has local changes; the build must use the pinned source exactly"

    # The Android changes: applied once, and then the whole checkout must be exactly the pin plus
    # those patches. Compared by content: the tree the patches make from a clean index against the
    # tree of the working files (tracked files; DolRecomp is checked above).
    local patch patches=()
    for patch in "$root"/patches/android/recompcore/*.patch; do
        patches+=("$patch")
        if git -C "$recompcore" apply --reverse --check "$patch" >/dev/null 2>&1; then
            continue    # already applied
        fi
        git -C "$recompcore" apply --check "$patch" >/dev/null 2>&1 || die "$(basename "$patch") does not apply to ref/recompcore-android (local changes there?)"
        git -C "$recompcore" apply "$patch"
        echo "applied $(basename "$patch")"
    done
    local index expected actual
    index=$(mktemp -u "${TMPDIR:-/tmp}/bluewake-index.XXXXXX")
    expected=$(GIT_INDEX_FILE=$index git -C "$recompcore" read-tree HEAD && \
        GIT_INDEX_FILE=$index git -C "$recompcore" apply --cached "${patches[@]}" && \
        GIT_INDEX_FILE=$index git -C "$recompcore" write-tree) || die "could not compute the expected RecompCore tree"
    rm -f "$index"
    actual=$(GIT_INDEX_FILE=$index git -C "$recompcore" read-tree HEAD && \
        GIT_INDEX_FILE=$index git -C "$recompcore" add -u && \
        GIT_INDEX_FILE=$index git -C "$recompcore" write-tree) || die "could not compute the RecompCore working tree"
    rm -f "$index"
    [ "$expected" = "$actual" ] || die "ref/recompcore-android differs from the pinned source plus patches/android (local edits?): move it aside and rerun"
    echo "RecompCore $RECOMPCORE_SHA, DolRecomp $DOLRECOMP_SHA, patches/android applied"

    deps=$root/build/deps
    mkdir -p "$deps"
    local dawn_tar=$deps/dawn-android-aarch64.tar.gz
    if [ ! -f "$dawn_tar" ] || [ "$(sha256_of "$dawn_tar")" != "$DAWN_ANDROID_SHA256" ]; then
        run dawn-download curl -fL -o "$dawn_tar" "$DAWN_ANDROID_URL"
    fi
    [ "$(sha256_of "$dawn_tar")" = "$DAWN_ANDROID_SHA256" ] || die "Dawn package checksum mismatch"
    if [ ! -f "$deps/dawn-android/lib/cmake/Dawn/DawnConfig.cmake" ]; then
        rm -rf "$deps/dawn-android" && mkdir -p "$deps/dawn-android"
        tar xzf "$dawn_tar" -C "$deps/dawn-android"
    fi
    echo "Dawn Android package $DAWN_ANDROID_SHA256"
}

recompcore=$root/ref/recompcore-android
deps=$root/build/deps
if should_run deps; then
    step "2/9 dependencies"
    android_dependencies
fi

# ------------------------------------------------------------------ 3-6 the game (shared with iOS)

if should_run extract; then
    step "3/9 extract the game from the disc"
    profile_extract
fi
if should_run translate; then
    step "4/9 translate"
    profile_translate
fi
if should_run generate; then
    step "5/9 generate the composite source"
    profile_generate
    if [ "$source_only" -eq 1 ]; then
        echo
        echo "source check passed: $out/composite-src. Rerun without --source-only to compile and build the app."
        exit 0
    fi
fi
if should_run mods; then
    step "6/9 mods"
    if [ "$mods" -eq 1 ]; then profile_mods; else echo "skipped"; fi
fi
[ -d "$out/composite-src" ] || die "$out/composite-src is missing (rerun without --start-at)"

# ------------------------------------------------------------------ 7 the game module

# Code a profile never saw running is marked cold, and clang then runs the machine
# outliner over it, calling into a shared snippet every few instructions; keep that
# code at plain -O2 (see scripts/builder/build.sh, which says the same for iOS).
pgo_flags() {
    python3 - "$1" <<'PY_FLAGS'
import shlex, sys
print(shlex.quote('-fprofile-instr-use=' + sys.argv[1]),
      '-mllvm -enable-machine-outliner=never',
      '-Wno-profile-instr-unprofiled -Wno-profile-instr-out-of-date -Wno-backend-plugin')
PY_FLAGS
}
merge_profile() {  # NAME FILE -> prints the path of a profile the NDK's LLVM reads
    local name=$1 file=$2 hash
    hash=$(sha256_of "$file")
    mkdir -p "$out/profiles"
    "$ndk_bin/llvm-profdata" merge -o "$out/profiles/$name-$hash.profdata" "$file" >/dev/null 2>"$logs/$name-profdata.log" ||
        die "the NDK's llvm-profdata cannot read $file ($(tail -1 "$logs/$name-profdata.log")); profiles trained with another LLVM version may use a newer format"
    echo "$out/profiles/$name-$hash.profdata"
}

toolchain=(-DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a
    -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release)
module_name=libgGZLE01_recomp.so

# What each built library was made from, recorded beside it and checked again when packaging, so
# --start-at apk can never package libraries made from other inputs than the ones the provenance
# describes.
profile_hash() { if [ -n "$1" ]; then sha256_of "$1"; else echo none; fi; }
tree_state() {  # the content of the tracked files under the given paths, as they are on disk now: the
                # same whether or not they are committed, and not the commit itself (an unrelated
                # commit must not force a rebuild)
    git ls-files -z -- "$@" | while IFS= read -r -d '' file; do
        # a file deleted on disk but still tracked is skipped, so committing the deletion changes nothing
        [ -f "$file" ] && printf '%s  %s\n' "$(sha256_of "$file")" "$file"
        true
    done | shasum -a 256 | awk '{print $1}'
}
patches_sha=$(cat "$root"/patches/android/recompcore/*.patch | shasum -a 256 | awk '{print $1}')
composite_inputs() {
    printf '%s\n' "ndk=${ndk_revision:-unknown}" "cpu=$cpu_flags" "opt=$opt_level" "mods=$mods" \
        "recompcore=$RECOMPCORE_SHA" "patches=$patches_sha" \
        "composite_digest=$(cat "$out/composite-final.digest" 2>/dev/null || cat "$out/composite-src.digest" 2>/dev/null || echo none)" \
        "profile=$(profile_hash "$composite_pgo")" \
        "source=$(tree_state cmake/composite scripts/generate_composite.py scripts/mods mods scripts/android scripts/builder)"
}
host_inputs() {
    printf '%s\n' "ndk=${ndk_revision:-unknown}" "cpu=$cpu_flags" \
        "recompcore=$RECOMPCORE_SHA" "dawn=$DAWN_ANDROID_SHA256" "patches=$patches_sha" \
        "profile=$(profile_hash "$host_pgo")" \
        "source=$(tree_state android/native runtime/host/src apple/ios/src scripts/android scripts/builder)"
}

if should_run composite; then
    step "7/9 compile the game module (-O$opt_level, $cpu_flags; this is the long step)"
    flags=$cpu_flags
    if [ -n "$composite_pgo" ]; then
        flags="$flags $(pgo_flags "$(merge_profile composite "$composite_pgo")")"
        echo "with the game module profile $composite_pgo"
    else
        echo "without profile-guided optimization: no profile for this target is bundled (docs/ANDROID.md)"
    fi
    start=$(date +%s)
    run composite-configure cmake -S cmake/composite -B "$out/composite-android" -G Ninja "${toolchain[@]}" \
        "-DCMAKE_C_FLAGS=$flags" -DCOMPOSITE_OPTIMIZATION_LEVEL="$opt_level" \
        -DCOMPOSITE_DIR="$out/composite-src" -DGXRUNTIME_DIR="$recompcore/GXRuntime" \
        -DABI_DIR="$recompcore/Source/Core/Core/PowerPC/StaticRecomp"
    run composite-build cmake --build "$out/composite-android" -j "$jobs"
    [ -f "$out/composite-android/$module_name" ] || die "the game module was not produced"
    composite_inputs > "$out/composite-android/inputs.txt"
    echo "game module built in $(( ($(date +%s) - start) / 60 )) min: $out/composite-android/$module_name"
fi

# ------------------------------------------------------------------ 8 the host

if should_run host; then
    step "8/9 build the native host (libmain.so, libbwdisc.so)"
    host_flags=$cpu_flags
    if [ -n "$host_pgo" ]; then
        host_flags="$host_flags $(pgo_flags "$(merge_profile host "$host_pgo")")"
        echo "with the host profile $host_pgo"
    fi
    run host-configure cmake -S android/native -B "$out/host-android" -G Ninja "${toolchain[@]}" \
        -DBUILD_TESTING=OFF -DBUILD_SHARED_LIBS=OFF -DPNG_SHARED=OFF \
        -DRECOMPCORE_DIR="$recompcore" \
        -DAURORA_DAWN_PROVIDER=system -DDawn_DIR="$deps/dawn-android/lib/cmake/Dawn" \
        -DAURORA_DAWN_LINKAGE=static -DAURORA_SDL3_PROVIDER=vendor -DAURORA_SDL3_LINKAGE=static \
        "-DCMAKE_C_FLAGS=$host_flags" "-DCMAKE_CXX_FLAGS=$host_flags" \
        ${cmake_args[@]+"${cmake_args[@]}"}
    run host-build cmake --build "$out/host-android" --target main bwdisc -j "$jobs"
    host_inputs > "$out/host-android/inputs.txt"
fi
for f in "$out/host-android/libmain.so" "$out/host-android/libbwdisc.so" "$out/composite-android/$module_name"; do
    [ -f "$f" ] || die "missing $f (rerun without --start-at)"
done

# ------------------------------------------------------------------ 9 the APK

if should_run apk; then
    step "9/9 package and sign the APK"
    [ "$(composite_inputs)" = "$(cat "$out/composite-android/inputs.txt" 2>/dev/null || true)" ] ||
        die "the game module in $out was built from other inputs than this invocation's (a different commit, --cpu, mods, profile or toolchain): rerun with --start-at composite"
    [ "$(host_inputs)" = "$(cat "$out/host-android/inputs.txt" 2>/dev/null || true)" ] ||
        die "the host libraries in $out were built from other inputs than this invocation's (a different commit, --cpu, profile or toolchain): rerun with --start-at host"
    # Native libraries for Gradle: stripped for the APK, the unstripped ones kept for crash reports.
    jni=$out/jniLibs/arm64-v8a
    rm -rf "$out/jniLibs" "$out/symbols"
    mkdir -p "$jni" "$out/symbols"
    for lib in "$out/host-android/libmain.so" "$out/host-android/libbwdisc.so" "$out/composite-android/$module_name"; do
        cp "$lib" "$out/symbols/"
        "$ndk_bin/llvm-strip" --strip-unneeded -o "$jni/$(basename "$lib")" "$lib"
    done

    # Provenance, for bug reports: what this build was made from (an asset in the APK).
    assets=$out/assets
    rm -rf "$assets" && mkdir -p "$assets"
    cat > "$assets/BuilderProvenance.json" <<EOF
{
  "profile": "$PROFILE_NAME",
  "platform": "android-arm64",
  "containsTranslatedGameCode": true,
  "source_commit": "$source_commit",
  "source_modified": $([ "$source_modified" = false ] && echo false || echo true),
  "recompcore": "$RECOMPCORE_SHA",
  "dolrecomp": "$DOLRECOMP_SHA",
  "dawn_package_sha256": "$DAWN_ANDROID_SHA256",
  "android_patches_sha256": "$patches_sha",
  "ndk": "${ndk_revision:-unknown}",
  "ndk_override": $ndk_override,
  "build_tools": "$BUILD_TOOLS_VERSION",
  "cmake": "$cmake_version",
  "jdk_major": "$java_major",
  "gradle_distribution_sha256": "$(sed -n 's/^distributionSha256Sum=//p' "$root/android/gradle/wrapper/gradle-wrapper.properties")",
  "gradle_dependency_verification_sha256": "$([ -f "$root/android/gradle/verification-metadata.xml" ] && sha256_of "$root/android/gradle/verification-metadata.xml" || echo none)",
  "gradle_build_files_sha256": "$(cat "$root"/android/build.gradle.kts "$root"/android/settings.gradle.kts "$root"/android/app/build.gradle.kts | shasum -a 256 | awk '{print $1}')",
  "sdl3": "release-3.4.10 (Aurora's CMake, by tag)",
  "composite_digest": "$(cat "$out/composite-src.digest" 2>/dev/null || true)",
  "mods": $([ "$mods" -eq 1 ] && echo true || echo false),
  "cpu_flags": "$cpu_flags",
  "game_module_profile_sha256": "$(profile_hash "$composite_pgo")",
  "host_profile_sha256": "$(profile_hash "$host_pgo")",
  "module_sha256": "$(sha256_of "$jni/$module_name")",
  "host_sha256": "$(sha256_of "$jni/libmain.so")",
  "built": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
EOF

    # Signing: a private key for this player's builds, made once and kept.
    if [ -z "$keystore" ]; then
        keystore=$out/signing/bluewake.keystore
        if [ ! -f "$keystore" ]; then
            mkdir -p "$out/signing"
            pw=$(python3 -c 'import secrets; print(secrets.token_urlsafe(18))')
            (umask 077; printf '%s' "$pw" > "$keystore.password")
            # The password goes to keytool through the environment, not its command line, so
            # it is in neither the process list nor a failed step's log line.
            BLUEWAKE_KEYTOOL_PW=$pw run keystore keytool -genkeypair -keystore "$keystore" \
                -storepass:env BLUEWAKE_KEYTOOL_PW -keypass:env BLUEWAKE_KEYTOOL_PW \
                -alias bluewake -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=BlueWake personal build"
            echo "created a signing key: $keystore (keep it: updates must be signed with the same key)"
        fi
    fi
    if [ -z "$keystore_password" ]; then
        [ -f "$keystore.password" ] || die "no password for $keystore: set BLUEWAKE_KEYSTORE_PASSWORD or put it in $keystore.password"
        keystore_password=$(cat "$keystore.password")
    fi

    # The password reaches Gradle through the environment (ORG_GRADLE_PROJECT_*), not its arguments.
    (cd android && ORG_GRADLE_PROJECT_bluewakeKeystorePassword=$keystore_password \
        run gradle ./gradlew --no-daemon :app:assembleRelease \
        -PbluewakeJniLibs="$out/jniLibs" -PbluewakeAssets="$assets" -PbluewakeKeystore="$keystore")
    built_apk=$root/android/app/build/outputs/apk/release/app-release.apk
    [ -f "$built_apk" ] || die "Gradle produced no APK ($built_apk)"
    "$build_tools/apksigner" verify --min-sdk-version 29 "$built_apk" >/dev/null 2>&1 || die "the APK's signature does not verify"

    # Audit: the APK holds the app and the translated module, never the disc,
    # saves or signing material.
    listing=$(unzip -Z1 "$built_apk")
    # The same private-file types scripts/audit_repo.sh keeps out of the repository.
    bad=$(printf '%s\n' "$listing" | grep -i -E '(\.(iso|gcm|rvz|nfs|wbfs|wia|ciso|gcz|dol|rel|sav|gci|card|raw|p12|mobileprovision|provisionprofile|ipa|apk|aab|keystore|password|jks|profraw|profdata|dylib)|(^|/)dolphin_[^/]*\.bin)$' || true)
    [ -z "$bad" ] || die "refusing to package private files: $bad"
    printf '%s\n' "$listing" | grep -qx "lib/arm64-v8a/$module_name" || die "the APK has no $module_name"
    printf '%s\n' "$listing" | grep -qx "lib/arm64-v8a/libmain.so" || die "the APK has no libmain.so"
    cp "$built_apk" "$apk"
    echo "APK: $apk ($(du -h "$apk" | awk '{print $1}'), signed)"
    echo "     It contains game code translated from your disc: keep it for yourself."
    echo "game module: $(sha256_of "$jni/$module_name")"

    if [ "$install" -eq 1 ]; then
        run install "$adb" install -r "$apk"
        echo "installed on the connected device"
    fi
fi

echo
echo "On first launch the app asks for the disc image; choose your .iso with the file picker."
