// JNI bridge between the Kotlin shell (dev.bluewake.android.NativeBridge) and
// the host: the touch pad, the pause reasons, the player's settings and the
// frame statistics. Everything here is thin; the behavior is in
// apple/ios/src/touch_controls.cpp (shared with iOS) and
// controller_settings_android.cpp.
//
// Loaded as part of libmain.so by SDLActivity before the Kotlin code calls it.
#include <jni.h>

#include <string>

#include "controller_settings.h"
#include "touch_controls.h"

extern "C" {
void bluewake_android_set_render(int render_scale, int anisotropy, int smooth_motion);
void bluewake_android_set_camera_invert(bool invert_x, bool invert_y);
void bluewake_android_set_thermal(int state);
unsigned long long bluewake_host_retrace_count(void);  // runtime/host/src/main.c
}

// touch_controls.cpp calls this on the first presented frame to add the
// platform's controls. The Kotlin GameActivity adds its overlay itself, so
// there is nothing to do here.
extern "C" void bluewake_shell_install_overlay(void) {}

#define BW_JNI(ret, name) \
    extern "C" JNIEXPORT ret JNICALL Java_dev_bluewake_android_NativeBridge_##name

// Buttons use the BLUEWAKE_TOUCH_* bits (touch_controls.h); sticks are
// -127..127 with +y up.
BW_JNI(void, nativePublishPad)(JNIEnv*, jclass, jint buttons, jint stick_x, jint stick_y,
                               jint c_stick_x, jint c_stick_y) {
    BlueWakeTouchPad pad{};
    pad.buttons = static_cast<uint16_t>(buttons);
    pad.stick_x = static_cast<int8_t>(stick_x);
    pad.stick_y = static_cast<int8_t>(stick_y);
    pad.c_stick_x = static_cast<int8_t>(c_stick_x);
    pad.c_stick_y = static_cast<int8_t>(c_stick_y);
    bluewake_touch_publish(&pad);
}

BW_JNI(void, nativeClearPad)(JNIEnv*, jclass) { bluewake_touch_clear(); }

BW_JNI(void, nativePauseSet)(JNIEnv*, jclass, jint reason, jboolean on) {
    bluewake_pause_set(static_cast<unsigned>(reason), on != JNI_FALSE);
}

BW_JNI(jint, nativePauseReasons)(JNIEnv*, jclass) {
    return static_cast<jint>(bluewake_pause_reasons());
}

// smooth_motion: 0 off, 1 in-between frames for 60 FPS, 2 for 120 FPS.
BW_JNI(void, nativeSetRender)(JNIEnv*, jclass, jint render_scale, jint anisotropy,
                              jint smooth_motion) {
    bluewake_android_set_render(render_scale, anisotropy, smooth_motion);
}

BW_JNI(void, nativeSetCameraInvert)(JNIEnv*, jclass, jboolean invert_x, jboolean invert_y) {
    bluewake_android_set_camera_invert(invert_x != JNI_FALSE, invert_y != JNI_FALSE);
}

BW_JNI(void, nativeRemapSet)(JNIEnv*, jclass, jint index, jint native) {
    if (index >= 0 && index < BW_REMAP_COUNT)
        bluewake_remap_set(index, static_cast<unsigned>(native));
}

BW_JNI(void, nativeRemapReset)(JNIEnv*, jclass) { bluewake_remap_reset(); }

BW_JNI(jint, nativeRemapCurrent)(JNIEnv*, jclass, jint index) {
    if (index < 0 || index >= BW_REMAP_COUNT) return 0;
    return static_cast<jint>(bluewake_remap_current_native(index));
}

BW_JNI(void, nativeSetThermal)(JNIEnv*, jclass, jint state) { bluewake_android_set_thermal(state); }

// {frames shown a second, game speed in percent, worst frame gap in ms}
BW_JNI(void, nativeFpsRead)(JNIEnv* env, jclass, jfloatArray out) {
    if (out == nullptr || env->GetArrayLength(out) < 4) return;
    float shown = 0.f, speed = 0.f, worst = 0.f;
    bluewake_fps_read(&shown, &speed, &worst);
    const jfloat values[4] = {shown, speed, worst, bluewake_fps_display()};
    env->SetFloatArrayRegion(out, 0, 4, values);
}

// The options Better Wind Waker offers (runtime/host/src/game_options.h), for
// the settings screen: {name, title, default on ("1"/"0"), on now ("1"/"0")} at
// a position, null past the last or before the game module has started.
extern "C" const char* bluewake_game_options_describe(uint32_t position, const char** title,
                                                      bool* default_on, bool* on);

BW_JNI(jobjectArray, nativeGameOption)(JNIEnv* env, jclass, jint position) {
    const char* title = nullptr;
    bool default_on = false, on = false;
    const char* name = bluewake_game_options_describe(static_cast<uint32_t>(position), &title,
                                                      &default_on, &on);
    if (name == nullptr) return nullptr;
    jclass string_class = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(4, string_class, nullptr);
    env->SetObjectArrayElement(out, 0, env->NewStringUTF(name));
    env->SetObjectArrayElement(out, 1, env->NewStringUTF(title != nullptr ? title : name));
    env->SetObjectArrayElement(out, 2, env->NewStringUTF(default_on ? "1" : "0"));
    env->SetObjectArrayElement(out, 3, env->NewStringUTF(on ? "1" : "0"));
    return out;
}

// The controller buttons a physical controller's mapping can use: {SDL gamepad
// button number, name} at a position, null past the last; and the GameCube
// buttons that can be remapped, by position.
BW_JNI(jobjectArray, nativeRemapChoice)(JNIEnv* env, jclass, jint position) {
    if (position < 0 || position >= BW_NATIVE_CHOICES) return nullptr;
    jclass string_class = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(2, string_class, nullptr);
    env->SetObjectArrayElement(out, 0, env->NewStringUTF(std::to_string(bluewake_native_choice(position)).c_str()));
    env->SetObjectArrayElement(out, 1, env->NewStringUTF(bluewake_native_choice_name(position)));
    return out;
}

BW_JNI(jstring, nativeRemapName)(JNIEnv* env, jclass, jint index) {
    if (index < 0 || index >= BW_REMAP_COUNT) return nullptr;
    return env->NewStringUTF(bluewake_remap_name(index));
}
