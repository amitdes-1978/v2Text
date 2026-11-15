// Calls the app's real JNI functions (v2text_jni.c) through a minimal fake JNIEnv.
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct { jsize len; void *data; } Arr;

static const char *GetStringUTFChars(JNIEnv *e, jstring s, jboolean *c) { (void)e; (void)c; return (const char *)s; }
static void ReleaseStringUTFChars(JNIEnv *e, jstring s, const char *c) { (void)e; (void)s; (void)c; }
static jsize GetArrayLength(JNIEnv *e, jarray a) { (void)e; return ((Arr *)a)->len; }
static void GetByteArrayRegion(JNIEnv *e, jbyteArray a, jsize st, jsize n, jbyte *buf) { (void)e; memcpy(buf, (char *)((Arr *)a)->data + st, n); }
static jfloat *GetFloatArrayElements(JNIEnv *e, jfloatArray a, jboolean *c) { (void)e; (void)c; return (jfloat *)((Arr *)a)->data; }
static void ReleaseFloatArrayElements(JNIEnv *e, jfloatArray a, jfloat *p, jint m) { (void)e; (void)a; (void)p; (void)m; }
static jbyteArray NewByteArray(JNIEnv *e, jsize n) { (void)e; Arr *a = malloc(sizeof *a); a->len = n; a->data = malloc(n ? n : 1); return (jbyteArray)a; }
static void SetByteArrayRegion(JNIEnv *e, jbyteArray a, jsize st, jsize n, const jbyte *buf) { (void)e; memcpy((char *)((Arr *)a)->data + st, buf, n); }
static jstring NewStringUTF(JNIEnv *e, const char *s) { (void)e; return (jstring)strdup(s); }

jlong Java_com_v2text_app_engine_WhisperNative_initContext(JNIEnv *, jclass, jstring);
void Java_com_v2text_app_engine_WhisperNative_freeContext(JNIEnv *, jclass, jlong);
jbyteArray Java_com_v2text_app_engine_WhisperNative_transcribe(JNIEnv *, jclass, jlong, jint, jfloatArray, jstring, jbyteArray, jint);
jstring Java_com_v2text_app_engine_WhisperNative_detectedLanguage(JNIEnv *, jclass, jlong);
jstring Java_com_v2text_app_engine_WhisperNative_systemInfo(JNIEnv *, jclass);

static float *load_wav(const char *path, int *n) {  // 16 kHz mono s16 WAV, data chunk search
    FILE *f = fopen(path, "rb"); if (!f) return NULL;
    fseek(f, 0, SEEK_END); long sz = ftell(f); fseek(f, 0, SEEK_SET);
    unsigned char *b = malloc(sz); fread(b, 1, sz, f); fclose(f);
    long p = 12;
    while (p + 8 < sz) { int len = *(int *)(b + p + 4); if (!memcmp(b + p, "data", 4)) break; p += 8 + len; }
    int ns = (*(int *)(b + p + 4)) / 2; short *pcm = (short *)(b + p + 8);
    float *out = malloc(ns * sizeof(float));
    for (int i = 0; i < ns; i++) out[i] = pcm[i] / 32768.0f;
    *n = ns; return out;
}

int main(int argc, char **argv) {
    struct JNINativeInterface fns; memset(&fns, 0, sizeof fns);
    fns.GetStringUTFChars = GetStringUTFChars; fns.ReleaseStringUTFChars = ReleaseStringUTFChars;
    fns.GetArrayLength = GetArrayLength; fns.GetByteArrayRegion = GetByteArrayRegion;
    fns.GetFloatArrayElements = GetFloatArrayElements; fns.ReleaseFloatArrayElements = ReleaseFloatArrayElements;
    fns.NewByteArray = NewByteArray; fns.SetByteArrayRegion = SetByteArrayRegion; fns.NewStringUTF = NewStringUTF;
    const struct JNINativeInterface *envp = &fns; JNIEnv *env = (JNIEnv *)&envp;

    printf("system: %s\n", (char *)Java_com_v2text_app_engine_WhisperNative_systemInfo(env, NULL));
    jlong bad = Java_com_v2text_app_engine_WhisperNative_initContext(env, NULL, (jstring)"/nonexistent.bin");
    printf("missing model -> ctx=%lld (expect 0)\n", (long long)bad);

    jlong ctx = Java_com_v2text_app_engine_WhisperNative_initContext(env, NULL, (jstring)argv[1]);
    printf("model ctx=%s\n", ctx ? "OK" : "NULL");
    if (!ctx) return 2;
    int n; float *pcm = load_wav(argv[2], &n); if (!pcm) return 3;
    Arr audio = { n, pcm };
    const char *hi = "नमस्ते, यह हिंदी में बोला गया संदेश है।";
    Arr prompt = { (jsize)strlen(hi), (void *)hi };
    struct { const char *lang; Arr *prompt; int beam; } cases[] = {
        { "en", NULL, 1 }, { NULL, NULL, 1 }, { "hi", &prompt, 1 }, { "gu", NULL, 1 }, { "en", &prompt, 3 },
    };
    for (unsigned i = 0; i < sizeof cases / sizeof *cases; i++) {
        Arr *r = (Arr *)Java_com_v2text_app_engine_WhisperNative_transcribe(env, NULL, ctx, atoi(argv[3]), (jfloatArray)&audio,
            (jstring)cases[i].lang, (jbyteArray)cases[i].prompt, cases[i].beam);
        jstring dl = Java_com_v2text_app_engine_WhisperNative_detectedLanguage(env, NULL, ctx);
        printf("case %u lang=%s beam=%d -> %s, %d bytes, detected=%s\n", i, cases[i].lang ? cases[i].lang : "auto",
               cases[i].beam, r ? "OK" : "NULL", r ? r->len : -1, dl ? (char *)dl : "-");
        fflush(stdout);
    }
    // very short and silent input (what a quick tap produces)
    float zeros[4000] = {0}; Arr z = { 4000, zeros };
    Arr *r = (Arr *)Java_com_v2text_app_engine_WhisperNative_transcribe(env, NULL, ctx, 4, (jfloatArray)&z, (jstring)"en", NULL, 1);
    printf("silence 0.25s -> %s %d bytes\n", r ? "OK" : "NULL", r ? r->len : -1);
    Java_com_v2text_app_engine_WhisperNative_freeContext(env, NULL, ctx);
    printf("DONE\n");
    return 0;
}

#include <stdarg.h>
int __android_log_print(int prio, const char *tag, const char *fmt, ...) {
    va_list ap; va_start(ap, fmt); fprintf(stderr, "[%d %s] ", prio, tag); int r = vfprintf(stderr, fmt, ap); fputc('\n', stderr); va_end(ap); return r;
}
