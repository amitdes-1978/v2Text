// JNI bridge between com.v2text.app.engine.WhisperNative and whisper.cpp.
//
// Text crosses the boundary as UTF-8 byte arrays, not jstring: whisper can split a
// multi-byte Devanagari/Gujarati character across segment boundaries, and
// NewStringUTF() aborts the process on invalid (modified) UTF-8.
#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include "whisper.h"

#define TAG "v2text-whisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

static char *copy_bytes(JNIEnv *env, jbyteArray arr) {
    if (arr == NULL) return NULL;
    jsize n = (*env)->GetArrayLength(env, arr);
    char *s = (char *) malloc((size_t) n + 1);
    if (!s) return NULL;
    (*env)->GetByteArrayRegion(env, arr, 0, n, (jbyte *) s);
    s[n] = '\0';
    return s;
}

JNIEXPORT jlong JNICALL
Java_com_v2text_app_engine_WhisperNative_initContext(JNIEnv *env, jclass clazz, jstring path) {
    (void) clazz;
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    struct whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    if (!ctx) LOGW("whisper_init_from_file failed for %s", p);
    (*env)->ReleaseStringUTFChars(env, path, p);
    return (jlong) ctx;
}

JNIEXPORT void JNICALL
Java_com_v2text_app_engine_WhisperNative_freeContext(JNIEnv *env, jclass clazz, jlong ptr) {
    (void) env; (void) clazz;
    if (ptr) whisper_free((struct whisper_context *) ptr);
}

// Returns the concatenated segment text as UTF-8 bytes, or null if whisper_full failed.
JNIEXPORT jbyteArray JNICALL
Java_com_v2text_app_engine_WhisperNative_transcribe(JNIEnv *env, jclass clazz, jlong ptr,
        jint threads, jfloatArray audio, jstring language, jbyteArray prompt, jint beamSize) {
    (void) clazz;
    struct whisper_context *ctx = (struct whisper_context *) ptr;
    if (!ctx) return NULL;

    struct whisper_full_params p = whisper_full_default_params(
            beamSize > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    if (beamSize > 1) p.beam_search.beam_size = beamSize;
    p.n_threads = threads;
    p.print_realtime = false;
    p.print_progress = false;
    p.print_timestamps = false;
    p.print_special = false;
    p.translate = false;          // never translate: transcription only
    p.no_context = true;
    p.no_timestamps = true;
    p.single_segment = false;

    const char *lang = language ? (*env)->GetStringUTFChars(env, language, NULL) : NULL;
    p.language = lang ? lang : "auto";
    char *prompt_c = copy_bytes(env, prompt);
    p.initial_prompt = prompt_c;

    jsize n = (*env)->GetArrayLength(env, audio);
    jfloat *samples = (*env)->GetFloatArrayElements(env, audio, NULL);
    int rc = whisper_full(ctx, p, samples, n);
    (*env)->ReleaseFloatArrayElements(env, audio, samples, JNI_ABORT);
    if (lang) (*env)->ReleaseStringUTFChars(env, language, lang);
    free(prompt_c);
    if (rc != 0) {
        LOGW("whisper_full failed: %d", rc);
        return NULL;
    }

    int segs = whisper_full_n_segments(ctx);
    size_t total = 0;
    for (int i = 0; i < segs; i++) total += strlen(whisper_full_get_segment_text(ctx, i));
    char *buf = (char *) malloc(total + 1);
    if (!buf) return NULL;
    size_t off = 0;
    for (int i = 0; i < segs; i++) {
        const char *t = whisper_full_get_segment_text(ctx, i);
        size_t l = strlen(t);
        memcpy(buf + off, t, l);
        off += l;
    }
    jbyteArray out = (*env)->NewByteArray(env, (jsize) off);
    (*env)->SetByteArrayRegion(env, out, 0, (jsize) off, (const jbyte *) buf);
    free(buf);
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_v2text_app_engine_WhisperNative_detectedLanguage(JNIEnv *env, jclass clazz, jlong ptr) {
    (void) clazz;
    struct whisper_context *ctx = (struct whisper_context *) ptr;
    if (!ctx) return NULL;
    int id = whisper_full_lang_id(ctx);
    const char *s = id >= 0 ? whisper_lang_str(id) : NULL;
    return s ? (*env)->NewStringUTF(env, s) : NULL;   // short ASCII codes like "hi"
}

JNIEXPORT jstring JNICALL
Java_com_v2text_app_engine_WhisperNative_systemInfo(JNIEnv *env, jclass clazz) {
    (void) clazz;
    return (*env)->NewStringUTF(env, whisper_print_system_info());
}
