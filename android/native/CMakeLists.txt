# BlueWake Android host: the unchanged runtime/host sources, GXRuntime with the
# Aurora renderer (WebGPU through Dawn's Vulkan backend), and SDL3, as the app's
# native libraries. Built with the Android NDK for arm64-v8a by
# scripts/android/build.sh, which also builds the game module (cmake/composite)
# and packages everything into the APK (android/app).
#
# Mirrors apple/ios/CMakeLists.txt. Android-specific source is only the entry
# shim (android_entry.cpp), the JNI bridge to the Kotlin shell (jni_bridge.cpp)
# and the player's settings store (controller_settings_android.cpp).
#
# Libraries produced (all end up in the APK's lib/arm64-v8a):
#   libmain.so    the host with SDL3 linked in statically (Aurora's Android
#                 window code uses SDL's activity lock, which a shared SDL3
#                 does not export). SDLActivity loads it and calls its SDL_main.
#   libbwdisc.so  the disc importer, loaded by the launcher screen before the
#                 game starts (no SDL, no renderer)
cmake_minimum_required(VERSION 3.25)
project(bluewake_android LANGUAGES C CXX)

if(NOT ANDROID)
    message(FATAL_ERROR "android/native builds only with the Android NDK toolchain "
                        "(-DCMAKE_TOOLCHAIN_FILE=\$ANDROID_NDK/build/cmake/android.toolchain.cmake)")
endif()

set(BLUEWAKE_REPO_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../..)
get_filename_component(BLUEWAKE_REPO_ROOT "${BLUEWAKE_REPO_ROOT}" ABSOLUTE)
# The pinned RecompCore checkout (with patches/android applied): scripts/android/build.sh.
set(RECOMPCORE_DIR ${BLUEWAKE_REPO_ROOT}/ref/recompcore-android CACHE PATH "RecompCore checkout")
set(GXRUNTIME_DIR ${RECOMPCORE_DIR}/GXRuntime)
set(BLUEWAKE_HOST_SRC ${BLUEWAKE_REPO_ROOT}/runtime/host/src)
set(BLUEWAKE_IOS_SRC ${BLUEWAKE_REPO_ROOT}/apple/ios/src)

# Paths compiled into the library (assert and log messages) are written
# relative to the checkout, so a build does not carry the builder's home folder.
add_compile_options("-ffile-prefix-map=${BLUEWAKE_REPO_ROOT}/=")

option(BLUEWAKE_ANDROID_ENABLE_DSP "Link the donor DSP adapter (required: JAS waits on the DSP at boot)" ON)
option(BLUEWAKE_ANDROID_DEVELOPER_TRACING "Compile BLUEWAKE_TRACE_* probes into the host" OFF)
set(BLUEWAKE_ANDROID_DSP_COMMON_SOURCES "" CACHE INTERNAL "")

find_package(Threads REQUIRED)
add_subdirectory(${GXRUNTIME_DIR} gxruntime_build EXCLUDE_FROM_ALL)

# ---------------------------------------------------------------- disc importer
# Checks a picked disc image and prepares main.dol and rels/ from it. Plain C,
# shared with the iOS app; its JNI entry points are in disc_jni.c.
add_library(bwdisc SHARED
    src/disc_jni.c
    ${BLUEWAKE_IOS_SRC}/disc_import.c)
target_include_directories(bwdisc PRIVATE ${BLUEWAKE_IOS_SRC})
target_link_libraries(bwdisc PRIVATE log)

# ---------------------------------------------------------------- the host
set(BLUEWAKE_HOST_SOURCES
    ${BLUEWAKE_HOST_SRC}/main.c
    ${BLUEWAKE_HOST_SRC}/aram_dma.c
    ${BLUEWAKE_HOST_SRC}/audio_capture.c
    ${BLUEWAKE_HOST_SRC}/ipl_sram.c
    ${BLUEWAKE_HOST_SRC}/card_runtime.c
    ${BLUEWAKE_HOST_SRC}/callback_delivery.c
    ${BLUEWAKE_HOST_SRC}/cycle_domain.c
    ${BLUEWAKE_HOST_SRC}/delivery_digest.c
    ${BLUEWAKE_HOST_SRC}/edge_intercepts.c
    ${BLUEWAKE_HOST_SRC}/game_options.c
    ${BLUEWAKE_HOST_SRC}/fast_load.c
    ${BLUEWAKE_HOST_SRC}/fps_watch.c
    ${BLUEWAKE_HOST_SRC}/jump_button.c
    ${BLUEWAKE_HOST_SRC}/climb.c
    ${BLUEWAKE_HOST_SRC}/sprint.c
    ${BLUEWAKE_HOST_SRC}/quick_doors.c
    ${BLUEWAKE_HOST_SRC}/draw_tags.c
    ${BLUEWAKE_HOST_SRC}/mouse_camera.c
    ${BLUEWAKE_HOST_SRC}/return_census.c
    ${BLUEWAKE_HOST_SRC}/interrupt_sources.c
    ${BLUEWAKE_HOST_SRC}/cold_fallback.c
    ${BLUEWAKE_HOST_SRC}/external_memory.c
    ${BLUEWAKE_HOST_SRC}/fpu_context.c
    ${BLUEWAKE_HOST_SRC}/pad_event_schedule.c
    ${BLUEWAKE_HOST_SRC}/pad_waypoint.c
    ${BLUEWAKE_HOST_SRC}/pad_wire.c
    ${BLUEWAKE_HOST_SRC}/rel_scratch_allocator.c
    ${BLUEWAKE_HOST_SRC}/scheduler_contract.c
    ${BLUEWAKE_HOST_SRC}/save_state.c)

add_library(main SHARED
    src/android_entry.cpp
    src/jni_bridge.cpp
    src/controller_settings_android.cpp
    ${BLUEWAKE_IOS_SRC}/controller_apply.cpp
    ${BLUEWAKE_IOS_SRC}/touch_controls.cpp
    ${BLUEWAKE_HOST_SOURCES})
# The unchanged host keeps its own entry point under another name; the shim
# owns main() so SDL's Android glue can find SDL_main.
set_source_files_properties(${BLUEWAKE_HOST_SRC}/main.c PROPERTIES
    COMPILE_DEFINITIONS "main=bluewake_host_main")
# The shared tick wrapper: controller_settings_android.cpp takes up the shell's
# changes on the game thread and then runs the unchanged tick.
set_source_files_properties(${BLUEWAKE_IOS_SRC}/controller_apply.cpp PROPERTIES
    COMPILE_DEFINITIONS "bluewake_settings_tick=bluewake_settings_tick_impl")
target_link_libraries(main PRIVATE gxruntime GXRuntime::aurora z log android)
set_target_properties(main PROPERTIES LINKER_LANGUAGE CXX)
target_include_directories(main PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/src
    ${BLUEWAKE_IOS_SRC}
    ${BLUEWAKE_HOST_SRC}
    ${GXRUNTIME_DIR}/include
    ${RECOMPCORE_DIR}/Source/Core/Core/PowerPC/StaticRecomp)
target_compile_definitions(main PRIVATE
    DOLRECOMP_CPU_HEADER="core/cpu.h"
    BLUEWAKE_ENABLE_DEVELOPER_TRACING=$<BOOL:${BLUEWAKE_ANDROID_DEVELOPER_TRACING}>
    BLUEWAKE_EDGE_CENSUS=0
    BLUEWAKE_ANDROID=1)

if(BLUEWAKE_ANDROID_ENABLE_DSP)
    # The donor DSP (Dolphin's DSP LLE interpreter from RecompCore) compiled
    # from source for this platform, with only the Common files it needs. The
    # same sources and shim as the iOS app.
    set(_donor ${RECOMPCORE_DIR})
    set(_dsp ${_donor}/Source/Core/Core/DSP)
    add_library(bluewake_dsp_donor STATIC
        ${_dsp}/DSPAccelerator.cpp ${_dsp}/DSPAnalyzer.cpp ${_dsp}/DSPAssembler.cpp
        ${_dsp}/DSPCaptureLogger.cpp ${_dsp}/DSPCodeUtil.cpp ${_dsp}/DSPCore.cpp
        ${_dsp}/DSPDisassembler.cpp ${_dsp}/DSPHWInterface.cpp ${_dsp}/DSPMemoryMap.cpp
        ${_dsp}/DSPStacks.cpp ${_dsp}/DSPTables.cpp ${_dsp}/LabelMap.cpp
        ${_dsp}/Interpreter/DSPIntArithmetic.cpp ${_dsp}/Interpreter/DSPIntBranch.cpp
        ${_dsp}/Interpreter/DSPIntExtOps.cpp ${_dsp}/Interpreter/DSPIntLoadStore.cpp
        ${_dsp}/Interpreter/DSPIntMisc.cpp ${_dsp}/Interpreter/DSPIntMultiplier.cpp
        ${_dsp}/Interpreter/DSPIntTables.cpp ${_dsp}/Interpreter/DSPInterpreter.cpp
        ${_dsp}/Jit/DSPEmitterBase.cpp
        ${_donor}/Source/Core/Core/HW/DSPHLE/DSPHLE.cpp
        ${_donor}/Source/Core/Core/HW/DSPHLE/MailHandler.cpp
        ${_donor}/Source/Core/Core/HW/DSPHLE/UCodes/AESnd.cpp
        ${_donor}/Source/Core/Core/HW/DSPHLE/UCodes/ASnd.cpp
        ${_donor}/Source/Core/Core/HW/DSPHLE/UCodes/AX.cpp
        ${_donor}/Source/Core/Core/HW/DSPHLE/UCodes/AXWii.cpp
        ${_donor}/Source/Core/Core/HW/DSPHLE/UCodes/CARD.cpp
        ${_donor}/Source/Core/Core/HW/DSPHLE/UCodes/GBA.cpp
        ${_donor}/Source/Core/Core/HW/DSPHLE/UCodes/INIT.cpp
        ${_donor}/Source/Core/Core/HW/DSPHLE/UCodes/ROM.cpp
        ${_donor}/Source/Core/Core/HW/DSPHLE/UCodes/UCodes.cpp
        ${_donor}/Source/Core/Core/HW/DSPHLE/UCodes/Zelda.cpp
        ${_donor}/Source/Core/Core/HW/DSPHLE/UCodes/ZeldaUCodesTable.cpp
        ${BLUEWAKE_IOS_SRC}/dsp_common_shim.cpp)
    set(_dsp_includes
        ${_donor}/Source/Core ${_donor}/Source ${_donor}/Externals
        ${_donor}/Externals/xxhash/xxHash
        # Dolphin's Common/FileUtil.h includes jni/AndroidCommon on Android.
        ${_donor}/Source/Android
        ${BLUEWAKE_REPO_ROOT}/apple/ios/dsp_generated)
    target_include_directories(bluewake_dsp_donor PUBLIC ${_dsp_includes})
    target_compile_definitions(bluewake_dsp_donor PUBLIC
        _ARCH_64=1 _M_ARM_64=1 FMT_EXCEPTIONS=0 _DEFAULT_SOURCE
        __STDC_CONSTANT_MACROS __STDC_LIMIT_MACROS)
    target_compile_features(bluewake_dsp_donor PUBLIC cxx_std_23)
    target_compile_options(bluewake_dsp_donor PRIVATE -fno-strict-aliasing -w)
    target_link_libraries(bluewake_dsp_donor PUBLIC fmt::fmt xxhash)

    add_library(bluewake_dsp_adapter STATIC
        ${BLUEWAKE_HOST_SRC}/dsp_adapter.cpp
        ${BLUEWAKE_HOST_SRC}/dsp_adapter_c.cpp
        ${BLUEWAKE_HOST_SRC}/dsp_adapter_donor_stubs.cpp
        ${BLUEWAKE_HOST_SRC}/dsp_hle_backend.cpp)
    # iconv is part of bionic from API 28.
    target_link_libraries(bluewake_dsp_adapter PUBLIC bluewake_dsp_donor z)
    target_compile_definitions(main PRIVATE BLUEWAKE_HAS_DSP_ADAPTER=1)
    target_link_libraries(main PRIVATE bluewake_dsp_adapter)
endif()

# 16 KB page alignment (Android 15+ devices can use 16 KB pages; harmless on
# the Find N3's 4 KB pages).
foreach(_t main bwdisc)
    target_link_options(${_t} PRIVATE -Wl,-z,max-page-size=16384)
endforeach()
