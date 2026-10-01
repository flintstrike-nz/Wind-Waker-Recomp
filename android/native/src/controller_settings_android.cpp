// The player's render and controller settings on Android. See
// apple/ios/src/controller_settings.h for what they are; the iOS app keeps
// them in NSUserDefaults and applies them on the main thread. Here the Kotlin
// shell owns them (SharedPreferences) and pushes each change over JNI from the
// UI thread, while the game's frame tick runs on SDL's main thread, so a
// change is parked under a lock and taken up at the start of the next tick
// (bluewake_settings_tick below wraps the shared controller_apply.cpp).
#include <SDL3/SDL_gamepad.h>
#include <aurora/aurora.h>

#include <atomic>
#include <cstdio>
#include <mutex>

#include "controller_settings.h"

namespace {

const char* const kRemapNames[BW_REMAP_COUNT] = {"A", "B", "X", "Y", "Z", "Start"};
// Aurora's standard defaults: face buttons by position, right shoulder Z.
const unsigned kRemapDefault[BW_REMAP_COUNT] = {
    SDL_GAMEPAD_BUTTON_SOUTH, SDL_GAMEPAD_BUTTON_EAST, SDL_GAMEPAD_BUTTON_WEST,
    SDL_GAMEPAD_BUTTON_NORTH, SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER, SDL_GAMEPAD_BUTTON_START};
const unsigned kNative[BW_NATIVE_CHOICES] = {
    SDL_GAMEPAD_BUTTON_SOUTH, SDL_GAMEPAD_BUTTON_EAST, SDL_GAMEPAD_BUTTON_WEST,
    SDL_GAMEPAD_BUTTON_NORTH, SDL_GAMEPAD_BUTTON_LEFT_SHOULDER, SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER,
    SDL_GAMEPAD_BUTTON_LEFT_STICK, SDL_GAMEPAD_BUTTON_RIGHT_STICK, SDL_GAMEPAD_BUTTON_START,
    SDL_GAMEPAD_BUTTON_BACK};
const char* const kNativeNames[BW_NATIVE_CHOICES] = {
    "Bottom Face (A / Cross)", "Right Face (B / Circle)", "Left Face (X / Square)",
    "Top Face (Y / Triangle)", "Left Shoulder (LB / L1)", "Right Shoulder (RB / R1)",
    "Left Stick Click", "Right Stick Click", "Menu / Start", "View / Select"};

std::mutex g_mutex;
BWSettingsSnapshot g_pending = {1u, 2, 0, false, false, {0}, false, false};
int g_pending_steps = 1;  // in-between frames per game frame: 1 shows 60 FPS, 3 shows 120
bool g_dirty = false;
std::atomic<int> g_thermal{0};

void init_pending_buttons() {
    static bool done = false;
    if (done) return;
    done = true;
    for (int i = 0; i < BW_REMAP_COUNT; i++) g_pending.native[i] = kRemapDefault[i];
}

}  // namespace

// The snapshot controller_apply.cpp reads on the game's main thread; only that
// thread's tick writes it.
BWSettingsSnapshot g_bw_settings = {1u, 2, 0, false, false, {0}, false, false};

extern "C" {

const char* bluewake_remap_name(int i) { return kRemapNames[i]; }
unsigned bluewake_remap_default_native(int i) { return kRemapDefault[i]; }
unsigned bluewake_native_choice(int i) { return kNative[i]; }
const char* bluewake_native_choice_name(int i) { return kNativeNames[i]; }

unsigned bluewake_remap_current_native(int i) {
    std::lock_guard<std::mutex> lock(g_mutex);
    init_pending_buttons();
    return g_pending.native[i];
}

void bluewake_remap_set(int index, unsigned native) {
    std::lock_guard<std::mutex> lock(g_mutex);
    init_pending_buttons();
    const unsigned previous = g_pending.native[index];
    for (int i = 0; i < BW_REMAP_COUNT; i++)
        if (i != index && g_pending.native[i] == native)
            g_pending.native[i] = previous;  // swap, so no button is doubled
    g_pending.native[index] = native;
    g_pending.remapped = false;
    for (int i = 0; i < BW_REMAP_COUNT; i++)
        if (g_pending.native[i] != kRemapDefault[i]) g_pending.remapped = true;
    g_dirty = true;
}

void bluewake_remap_reset(void) {
    std::lock_guard<std::mutex> lock(g_mutex);
    for (int i = 0; i < BW_REMAP_COUNT; i++) g_pending.native[i] = kRemapDefault[i];
    g_pending.remapped = false;
    g_dirty = true;
}

// Called by the shared code on the iOS app's main thread after a defaults
// change. Here the shell's JNI setters mark the change themselves.
void bluewake_settings_changed(void) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_dirty = true;
}

// 0 nominal .. 3 critical, from PowerManager's thermal status (the shell
// reports it); for the session log's [fps] and [late] lines.
int bluewake_thermal_state(void) { return g_thermal.load(); }

// JNI-side setters (jni_bridge.cpp).
void bluewake_android_set_render(int render_scale, int anisotropy, int smooth_motion) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_pending.render_scale = render_scale;
    g_pending.anisotropy = anisotropy;
    g_pending.frame_interp = smooth_motion > 0;
    g_pending_steps = smooth_motion == 2 ? 3 : 1;
    g_dirty = true;
}

void bluewake_android_set_camera_invert(bool invert_x, bool invert_y) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_pending.invert_x = invert_x;
    g_pending.invert_y = invert_y;
    g_dirty = true;
}

void bluewake_android_set_thermal(int state) { g_thermal.store(state); }

// controller_apply.cpp is compiled with its tick renamed to ..._impl (see
// CMakeLists.txt); this one first takes up what the shell changed.
void bluewake_settings_tick_impl(void);
void bluewake_settings_tick(void) {
    int steps = 0;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        init_pending_buttons();
        if (g_dirty || g_bw_settings.generation == 1u) {
            const unsigned generation = g_bw_settings.generation + 1u;
            g_bw_settings = g_pending;
            g_bw_settings.generation = generation;
            g_dirty = false;
            steps = g_pending_steps;
        }
    }
    // 120 FPS needs a 120 Hz display; the tick below turns the in-between
    // frames on or off.
    if (steps != 0) aurora_set_frame_interp_steps(steps);
    bluewake_settings_tick_impl();
}

}  // extern "C"
