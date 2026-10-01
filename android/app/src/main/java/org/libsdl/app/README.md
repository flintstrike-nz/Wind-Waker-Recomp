# SDL3's Android glue

These files are `android-project/app/src/main/java/org/libsdl/app/` from SDL
release 3.4.10 (commit 8e37db5e797b6167f3a00d697d816a684bd259c7), the same SDL
that Aurora's CMake builds into `libmain.so`; the Java and native sides must
match. SDL is under the zlib license (see SDL's LICENSE.txt).

One change, marked `// BlueWake:` in `SDLActivity.java`: the game thread
(`SDLThread`) is created with a 16 MB stack instead of Android's default of
about 1 MB, because the recompiled game code runs on it.
