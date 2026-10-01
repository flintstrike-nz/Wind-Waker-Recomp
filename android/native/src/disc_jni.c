// JNI wrappers for the disc importer (apple/ios/src/disc_import.c, the same C
// the iOS app uses), loaded by the Kotlin launcher as libbwdisc.so before the
// game starts. Kotlin class: dev.bluewake.android.DiscNative.
#include <jni.h>
#include <stdio.h>
#include <string.h>

#include <stdlib.h>

#include "disc_import.h"
#include "dolphin_save_import.h"

typedef struct {
    JNIEnv* env;
    jobject listener;
    jmethodID on_progress;
} ProgressContext;

static void report_progress(void* context, double fraction, const char* stage) {
    ProgressContext* pc = (ProgressContext*)context;
    if (pc->listener == NULL) return;
    jstring text = (*pc->env)->NewStringUTF(pc->env, stage != NULL ? stage : "");
    (*pc->env)->CallVoidMethod(pc->env, pc->listener, pc->on_progress, (jdouble)fraction, text);
    (*pc->env)->DeleteLocalRef(pc->env, text);
}

static jstring utf(JNIEnv* env, const char* s) { return (*env)->NewStringUTF(env, s); }

// Returns null when the file is a GameCube disc image of The Wind Waker (USA),
// otherwise a sentence for the player.
JNIEXPORT jstring JNICALL Java_dev_bluewake_android_DiscNative_nativeCheck(
    JNIEnv* env, jclass cls, jstring path) {
    (void)cls;
    const char* iso = (*env)->GetStringUTFChars(env, path, NULL);
    char error[512] = {0};
    const int status = bluewake_disc_check(iso, error, sizeof error);
    (*env)->ReleaseStringUTFChars(env, path, iso);
    return status == 0 ? NULL : utf(env, error);
}

// Prepares outDir/main.dol and outDir/rels from the disc. listener has
// onProgress(double fraction, String stage). Returns null on success.
JNIEXPORT jstring JNICALL Java_dev_bluewake_android_DiscNative_nativePrepare(
    JNIEnv* env, jclass cls, jstring path, jstring outDir, jobject listener) {
    (void)cls;
    const char* iso = (*env)->GetStringUTFChars(env, path, NULL);
    const char* out = (*env)->GetStringUTFChars(env, outDir, NULL);
    ProgressContext pc = {env, listener, NULL};
    if (listener != NULL) {
        jclass lc = (*env)->GetObjectClass(env, listener);
        pc.on_progress = (*env)->GetMethodID(env, lc, "onProgress", "(DLjava/lang/String;)V");
        if (pc.on_progress == NULL) pc.listener = NULL;
    }
    char error[512] = {0};
    const int status = bluewake_disc_prepare(iso, out, report_progress, &pc, error, sizeof error);
    (*env)->ReleaseStringUTFChars(env, path, iso);
    (*env)->ReleaseStringUTFChars(env, outDir, out);
    return status == 0 ? NULL : utf(env, error);
}

// Null when the file is a sound BlueWake memory card container (version, block size, payload
// length and checksum, and every record's framing; the same validation the iOS app does before it
// touches a card), otherwise a sentence for the player.
JNIEXPORT jstring JNICALL Java_dev_bluewake_android_DiscNative_nativeCardCheck(
    JNIEnv* env, jclass cls, jstring path) {
    (void)cls;
    const char* file = (*env)->GetStringUTFChars(env, path, NULL);
    jstring result = NULL;
    FILE* f = fopen(file, "rb");
    (*env)->ReleaseStringUTFChars(env, path, file);
    if (f == NULL) return utf(env, "The save file could not be opened.");
    fseek(f, 0, SEEK_END);
    const long size = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (size <= 0 || size > (64L << 20)) {
        fclose(f);
        return utf(env, "That is not a BlueWake save file (the size is wrong).");
    }
    uint8_t* bytes = (uint8_t*)malloc((size_t)size);
    if (bytes == NULL || fread(bytes, 1, (size_t)size, f) != (size_t)size) {
        free(bytes);
        fclose(f);
        return utf(env, "The save file could not be read.");
    }
    fclose(f);
    bool has_saves = false;
    BWQuestLog logs[BW_QUEST_LOGS];
    const char* error = bw_card_quest_logs(bytes, (size_t)size, &has_saves, logs);
    free(bytes);
    if (error != NULL)
        result = utf(env, "That save file is damaged or incomplete, so it was not used.");
    return result;
}
