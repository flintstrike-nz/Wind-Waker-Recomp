// BlueWake Android entry shim.
//
// SDL owns the process on Android: SDLActivity (the Kotlin GameActivity) loads
// libmain.so and calls SDL_main, which SDL_main.h renames our main() to. This
// file only fills in default paths inside the app's private storage, sets up
// the session log, then runs the unchanged host (runtime/host/src/main.c,
// compiled with main renamed to bluewake_host_main).
//
// The Kotlin shell has already done what an Activity can and the host cannot,
// and passes it through the environment (android.system.Os.setenv, before this
// library loads):
//   BLUEWAKE_DATA_DIR    filesDir/BlueWake, the user's data (never bundled):
//       GZLE01.iso         the disc image imported with the system file picker
//       main.dol, rels/    prepared from that disc on the device (libbwdisc)
//       GZLE01.card        the memory card (saves)
//       sram.bin           the console's settings
//       logs/              session logs, the newest eight kept
//   BLUEWAKE_COMPOSITE   nativeLibraryDir/libgGZLE01_recomp.so, the translated
//                        game, built on a PC from the same disc and packaged
//                        in the APK
//   BLUEWAKE_MODS, BLUEWAKE_OPTIONS, DOL_AURORA_ASPECT_RATIO,
//   DOL_AURORA_ASPECT_FIT, DOL_AURORA_TEXTURE_PACK
//                        the launch-time choices of the Mods and Display menus
// Anything set here as a default can still be overridden from the environment.
#include <SDL3/SDL_main.h>

#include <android/log.h>
#include <dirent.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#include <algorithm>
#include <string>
#include <vector>

#include "touch_controls.h"

extern "C" int bluewake_host_main(int argc, char** argv);

namespace {

void set_default(const char* name, const std::string& value) {
    const char* existing = getenv(name);
    if (existing != nullptr && existing[0] != '\0') return;
    setenv(name, value.c_str(), 1);
}

bool exists(const std::string& path) {
    struct stat st;
    return stat(path.c_str(), &st) == 0;
}

void set_default_if_exists(const char* name, const std::string& path) {
    if (exists(path)) set_default(name, path);
}

// Session log. Everything the host writes to stdout and stderr goes to logcat
// (tag BlueWake) and to <data>/logs/session-YYYYMMDD-HHMMSS.log, each line
// stamped with the local wall-clock time, so a session can be read back from
// the device (the app's "Share session log") without a computer attached, and
// a moment the player remembers ("it lagged around 3:42") can be found in it.
FILE* g_log_file = nullptr;

void* log_pump(void* arg) {
    const int read_fd = static_cast<int>(reinterpret_cast<intptr_t>(arg));
    char buffer[16384];
    std::string line;
    for (;;) {
        const ssize_t n = read(read_fd, buffer, sizeof buffer);
        if (n <= 0) break;
        for (ssize_t i = 0; i < n; i++) {
            if (line.size() < 8191) line.push_back(buffer[i]);
            if (buffer[i] != '\n') continue;
            struct timeval tv;
            gettimeofday(&tv, nullptr);
            struct tm local;
            localtime_r(&tv.tv_sec, &local);
            if (g_log_file != nullptr) {
                fprintf(g_log_file, "%02d:%02d:%02d.%03d ", local.tm_hour, local.tm_min,
                        local.tm_sec, static_cast<int>(tv.tv_usec / 1000));
                fwrite(line.data(), 1, line.size(), g_log_file);
            }
            if (!line.empty() && line.back() == '\n') line.pop_back();
            __android_log_write(ANDROID_LOG_INFO, "BlueWake", line.c_str());
            line.clear();
        }
        if (g_log_file != nullptr) fflush(g_log_file);
    }
    return nullptr;
}

void prune_old_logs(const std::string& dir) {
    std::vector<std::string> sessions;
    if (DIR* d = opendir(dir.c_str())) {
        while (const dirent* e = readdir(d))
            if (strncmp(e->d_name, "session-", 8) == 0) sessions.emplace_back(e->d_name);
        closedir(d);
    }
    std::sort(sessions.begin(), sessions.end());
    for (size_t i = 0; i + 7 < sessions.size(); i++) unlink((dir + "/" + sessions[i]).c_str());
}

void start_session_log(const std::string& data) {
    const std::string dir = data + "/logs";
    mkdir(dir.c_str(), 0700);
    prune_old_logs(dir);
    time_t now = time(nullptr);
    struct tm local;
    localtime_r(&now, &local);
    char name[64];
    strftime(name, sizeof name, "session-%Y%m%d-%H%M%S.log", &local);
    g_log_file = fopen((dir + "/" + name).c_str(), "w");
    int fds[2];
    if (pipe(fds) != 0) return;
    dup2(fds[1], STDOUT_FILENO);
    dup2(fds[1], STDERR_FILENO);
    close(fds[1]);
    pthread_t thread;
    pthread_create(&thread, nullptr, log_pump,
                   reinterpret_cast<void*>(static_cast<intptr_t>(fds[0])));
    pthread_detach(thread);
}

}  // namespace

int main(int argc, char** argv) {
    const char* data_env = getenv("BLUEWAKE_DATA_DIR");
    if (data_env == nullptr || data_env[0] == '\0') {
        // The Kotlin shell always sets it; this is for a bare SDL_main launch.
        fprintf(stderr, "[android] BLUEWAKE_DATA_DIR is not set\n");
        return 1;
    }
    const std::string data = data_env;
    mkdir(data.c_str(), 0700);

    const char* session_log = getenv("BLUEWAKE_SESSION_LOG");
    if (session_log == nullptr || strcmp(session_log, "0") != 0) start_session_log(data);
    setvbuf(stdout, nullptr, _IOLBF, 0);
    setvbuf(stderr, nullptr, _IOLBF, 0);

    // One line a second: speed, the worst frame gap, dropped frames and how
    // busy the game thread was (runtime/host/src/main.c). And the player's
    // inputs next to the moments the game reads them.
    set_default("BLUEWAKE_PERF_LOG", "1");
    set_default("BLUEWAKE_INPUT_LOG", "1");
    // Pace retraces by the wall clock (runtime/host/src/main.c host_wall_pace)
    // instead of by the audio queue alone.
    set_default("BLUEWAKE_WALL_PACE", "1");
    set_default("DOL_AUDIO_NO_THROTTLE", "1");

    // Aurora's window: fullscreen (SDL then hides the system bars), the picture
    // kept in the game's shape unless the player filled the screen (the shell
    // sets DOL_AURORA_ASPECT_FIT=0 then). Touches are the shell's own controls.
    set_default("DOL_AURORA_FULLSCREEN", "1");
    set_default("DOL_AURORA_ASPECT_FIT", "1");

    set_default("BLUEWAKE_RENDERER", "aurora");
    set_default("BLUEWAKE_CYCLE_CAP", "16384");
    set_default("BLUEWAKE_MAX_BLOCKS", "100000000000");
    // Dolphin's high-level Zelda ucode: about 15% fewer play-window cycles than
    // the LLE interpreter (docs/status/CURRENT.md). BLUEWAKE_DSP_MODE=lle
    // restores LLE.
    set_default("BLUEWAKE_DSP_MODE", "hle");
    // The console's SRAM (ipl_sram.h), kept in the data folder so the game's own
    // Stereo/Mono option persists.
    set_default("BLUEWAKE_SRAM", data + "/sram.bin");
    // The console clock: saves carry the real local date and time.
    set_default("BLUEWAKE_CLOCK", "now");
    // Aurora keeps its caches (pipelines, shaders) beside the data.
    set_default("DOL_AURORA_CACHE_DIR", data + "/cache");
    mkdir((data + "/cache").c_str(), 0700);
    // Dolphin-format HD texture packs go in <data>/Load/Textures/GZLE01. Android
    // keeps the folder under the app's private storage; the app's texture-pack
    // installer fills it.
    mkdir((data + "/Load").c_str(), 0700);
    mkdir((data + "/Load/Textures").c_str(), 0700);
    mkdir((data + "/Load/Textures/GZLE01").c_str(), 0700);

    set_default_if_exists("BLUEWAKE_DOL", data + "/main.dol");
    set_default_if_exists("BLUEWAKE_RELS_DIR", data + "/rels");
    set_default_if_exists("BLUEWAKE_DISC", data + "/GZLE01.iso");
    set_default_if_exists("BLUEWAKE_DSP_IROM", data + "/dsp_rom.bin");
    set_default_if_exists("BLUEWAKE_DSP_COEF", data + "/dsp_coef.bin");
    // Saves live beside the other data; the app's menu backs them up and
    // restores them through the system file picker.
    set_default("BLUEWAKE_CARD_PATH", data + "/GZLE01.card");

    const char* composite = getenv("BLUEWAKE_COMPOSITE");
    fprintf(stderr, "[android] data=%s composite=%s\n", data.c_str(),
            composite != nullptr ? composite : "(host default)");

    char* host_argv[3] = {argv[0], nullptr, nullptr};
    int host_argc = 1;
    if (composite != nullptr && composite[0] != '\0') {
        host_argv[1] = strdup(composite);
        host_argc = 2;
    }
    // Drawn in the game's own frame through Aurora's overlay hook.
    bluewake_touch_controls_install();
    const int status = bluewake_host_main(host_argc, host_argv);
    // Returning from SDL_main leaves the Activity open with no game. The host
    // only returns at a bounded stop (BLUEWAKE_MAX_RETRACES), a quit or a fatal
    // error, and in each case the process should end.
    fflush(stdout);
    fflush(stderr);
    exit(status);
}
