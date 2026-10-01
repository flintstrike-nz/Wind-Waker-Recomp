// JNI wrappers for the disc importer (apple/ios/src/disc_import.c, the same C
// the iOS app uses), loaded by the Kotlin launcher as libbwdisc.so before the
// game starts. Kotlin class: dev.bluewake.android.DiscNative.
#include <jni.h>
#include <stdio.h>
#include <string.h>

#include <stdint.h>
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

// ---------------------------------------------------------------- Dolphin saves
//
// The shared importer (apple/ios/src/dolphin_save_import.c): read a Dolphin .gci or raw memory card,
// list its quest logs, and put one of them into a slot of BlueWake's card.

// The whole file at path, at most max_size bytes; null (and *error set) on failure. Caller frees.
static uint8_t* read_file(const char* path, long max_size, size_t* size_out, const char** error) {
    FILE* f = fopen(path, "rb");
    if (f == NULL) { *error = "The file could not be opened."; return NULL; }
    fseek(f, 0, SEEK_END);
    const long size = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (size <= 0 || size > max_size) {
        fclose(f);
        *error = "That file is not the size of a Dolphin save or memory card.";
        return NULL;
    }
    uint8_t* bytes = (uint8_t*)malloc((size_t)size);
    if (bytes == NULL || fread(bytes, 1, (size_t)size, f) != (size_t)size) {
        free(bytes);
        fclose(f);
        *error = "The file could not be read.";
        return NULL;
    }
    fclose(f);
    *size_out = (size_t)size;
    return bytes;
}

#define DOLPHIN_FILE_MAX (32L << 20)  // a raw Dolphin card is at most about 16 MB
#define CARD_FILE_MAX (64L << 20)

// "empty;checksumOk;maxLife;rupees;name" for one quest log, as a Java string.
static jstring describe(JNIEnv* env, const BWQuestLog* log) {
    char text[64];
    snprintf(text, sizeof text, "%d;%d;%u;%u;%s", log->empty ? 1 : 0, log->checksum_ok ? 1 : 0,
             log->max_life, log->rupees, log->name);
    return utf(env, text);
}

static jobjectArray string_array(JNIEnv* env, int count) {
    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    return (*env)->NewObjectArray(env, count, string_class, NULL);
}

// {error, log1, log2, log3} for a Dolphin .gci or raw card at path: error is "" when the file holds a
// Wind Waker (USA) save, otherwise a sentence for the player and the logs are empty strings.
JNIEXPORT jobjectArray JNICALL Java_dev_bluewake_android_DiscNative_nativeDolphinQuestLogs(
    JNIEnv* env, jclass cls, jstring path) {
    (void)cls;
    jobjectArray out = string_array(env, 1 + BW_QUEST_LOGS);
    for (int i = 0; i <= BW_QUEST_LOGS; i++) (*env)->SetObjectArrayElement(env, out, i, utf(env, ""));
    const char* file = (*env)->GetStringUTFChars(env, path, NULL);
    size_t size = 0;
    const char* error = NULL;
    uint8_t* bytes = read_file(file, DOLPHIN_FILE_MAX, &size, &error);
    (*env)->ReleaseStringUTFChars(env, path, file);
    BWDolphinSave save;
    if (bytes != NULL) {
        error = bw_dolphin_save_parse(bytes, size, &save);
        free(bytes);
        if (error == NULL) {
            BWQuestLog logs[BW_QUEST_LOGS];
            bw_gczelda_quest_logs(save.data, logs);
            for (int i = 0; i < BW_QUEST_LOGS; i++)
                (*env)->SetObjectArrayElement(env, out, 1 + i, describe(env, &logs[i]));
            bw_dolphin_save_free(&save);
        }
    }
    if (error != NULL) (*env)->SetObjectArrayElement(env, out, 0, utf(env, error));
    return out;
}

// {error, hasSaves ("1"/"0"), log1, log2, log3} for BlueWake's own card at path.
JNIEXPORT jobjectArray JNICALL Java_dev_bluewake_android_DiscNative_nativeCardQuestLogs(
    JNIEnv* env, jclass cls, jstring path) {
    (void)cls;
    jobjectArray out = string_array(env, 2 + BW_QUEST_LOGS);
    for (int i = 0; i < 2 + BW_QUEST_LOGS; i++) (*env)->SetObjectArrayElement(env, out, i, utf(env, ""));
    const char* file = (*env)->GetStringUTFChars(env, path, NULL);
    size_t size = 0;
    const char* error = NULL;
    uint8_t* bytes = read_file(file, CARD_FILE_MAX, &size, &error);
    (*env)->ReleaseStringUTFChars(env, path, file);
    if (bytes != NULL) {
        bool has_saves = false;
        BWQuestLog logs[BW_QUEST_LOGS];
        error = bw_card_quest_logs(bytes, size, &has_saves, logs);
        free(bytes);
        if (error == NULL) {
            (*env)->SetObjectArrayElement(env, out, 1, utf(env, has_saves ? "1" : "0"));
            for (int i = 0; i < BW_QUEST_LOGS; i++)
                (*env)->SetObjectArrayElement(env, out, 2 + i, describe(env, &logs[i]));
        }
    }
    if (error != NULL) (*env)->SetObjectArrayElement(env, out, 0, utf(env, error));
    return out;
}

// Makes the card that results from putting quest log `src` (1-3) of the Dolphin save into slot `dst`
// (1-3) of BlueWake's card, and writes it to outPath (both slots are ignored, and the whole Dolphin
// file added, when the card has no saves yet). Neither input file is changed. Null on success.
JNIEXPORT jstring JNICALL Java_dev_bluewake_android_DiscNative_nativeDolphinImport(
    JNIEnv* env, jclass cls, jstring dolphinPath, jstring cardPath, jint src, jint dst, jstring outPath) {
    (void)cls;
    const char* dolphin_file = (*env)->GetStringUTFChars(env, dolphinPath, NULL);
    const char* card_file = (*env)->GetStringUTFChars(env, cardPath, NULL);
    const char* out_file = (*env)->GetStringUTFChars(env, outPath, NULL);
    const char* error = NULL;
    size_t dolphin_size = 0, card_size = 0;
    uint8_t* dolphin_bytes = read_file(dolphin_file, DOLPHIN_FILE_MAX, &dolphin_size, &error);
    uint8_t* card_bytes = dolphin_bytes != NULL ? read_file(card_file, CARD_FILE_MAX, &card_size, &error) : NULL;
    uint8_t* result = NULL;
    size_t result_size = 0;
    BWDolphinSave save;
    memset(&save, 0, sizeof save);
    if (card_bytes != NULL) {
        error = bw_dolphin_save_parse(dolphin_bytes, dolphin_size, &save);
        if (error == NULL)
            error = bw_card_import(card_bytes, card_size, &save, src, dst, &result, &result_size);
        bw_dolphin_save_free(&save);
    }
    free(dolphin_bytes);
    free(card_bytes);
    if (error == NULL) {
        FILE* f = fopen(out_file, "wb");
        if (f == NULL || fwrite(result, 1, result_size, f) != result_size) error = "The imported card could not be written.";
        if (f != NULL && fclose(f) != 0) error = "The imported card could not be written.";
    }
    free(result);
    (*env)->ReleaseStringUTFChars(env, dolphinPath, dolphin_file);
    (*env)->ReleaseStringUTFChars(env, cardPath, card_file);
    (*env)->ReleaseStringUTFChars(env, outPath, out_file);
    return error == NULL ? NULL : utf(env, error);
}
