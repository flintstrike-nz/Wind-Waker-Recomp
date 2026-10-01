#!/usr/bin/env bash
# Builds a personal Android app from your own disc: the Android Builder
# (scripts/android/build.sh, docs/ANDROID.md) with its usual options.
#
#   scripts/android/build_device.sh "/path/to/The Legend Of Zelda The Wind Waker.iso" --apk build/BlueWake.apk
exec "$(dirname "$0")/build.sh" "$@"
