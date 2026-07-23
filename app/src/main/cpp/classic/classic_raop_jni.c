#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "raop.h"

typedef struct {
    JavaVM *vm;
    jobject callback;
    raop_t *raop;
    int running;

    jmethodID on_audio_start;
    jmethodID on_pcm;
    jmethodID on_audio_stop;
    jmethodID on_flush;
    jmethodID on_volume;
    jmethodID on_metadata;
    jmethodID on_cover_art;
    jmethodID on_progress;
    jmethodID on_dacp;
    jmethodID on_log;
} classic_ctx_t;

static JNIEnv *classic_get_env(classic_ctx_t *ctx, int *attached) {
    JNIEnv *env = NULL;
    *attached = 0;
    jint status = (*ctx->vm)->GetEnv(ctx->vm, (void **)&env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if ((*ctx->vm)->AttachCurrentThread(ctx->vm, &env, NULL) != JNI_OK) {
            return NULL;
        }
        *attached = 1;
    } else if (status != JNI_OK) {
        return NULL;
    }
    return env;
}

static void classic_release_env(classic_ctx_t *ctx, JNIEnv *env, int attached) {
    if (env && (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
    if (attached) {
        (*ctx->vm)->DetachCurrentThread(ctx->vm);
    }
}

static void classic_call_void(classic_ctx_t *ctx, jmethodID method) {
    int attached = 0;
    JNIEnv *env = classic_get_env(ctx, &attached);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback, method);
    classic_release_env(ctx, env, attached);
}

static void classic_call_bytes(classic_ctx_t *ctx, jmethodID method,
                               const void *buffer, int length) {
    if (!buffer || length <= 0) return;
    int attached = 0;
    JNIEnv *env = classic_get_env(ctx, &attached);
    if (!env) return;

    jbyteArray bytes = (*env)->NewByteArray(env, length);
    if (bytes) {
        (*env)->SetByteArrayRegion(env, bytes, 0, length, (const jbyte *)buffer);
        (*env)->CallVoidMethod(env, ctx->callback, method, bytes);
        (*env)->DeleteLocalRef(env, bytes);
    }
    classic_release_env(ctx, env, attached);
}

static void *classic_audio_init(void *opaque, int bits, int channels, int sample_rate) {
    classic_ctx_t *ctx = opaque;
    int attached = 0;
    JNIEnv *env = classic_get_env(ctx, &attached);
    if (!env) return ctx;
    (*env)->CallVoidMethod(env, ctx->callback, ctx->on_audio_start,
                           (jint)bits, (jint)channels, (jint)sample_rate);
    classic_release_env(ctx, env, attached);
    return ctx;
}

static void classic_audio_process(void *opaque, void *session,
                                  const void *buffer, int length) {
    (void)session;
    classic_call_bytes((classic_ctx_t *)opaque,
                       ((classic_ctx_t *)opaque)->on_pcm, buffer, length);
}

static void classic_audio_destroy(void *opaque, void *session) {
    (void)session;
    classic_ctx_t *ctx = opaque;
    classic_call_void(ctx, ctx->on_audio_stop);
}

static void classic_audio_flush(void *opaque, void *session) {
    (void)session;
    classic_ctx_t *ctx = opaque;
    classic_call_void(ctx, ctx->on_flush);
}

static void classic_audio_set_volume(void *opaque, void *session, float volume) {
    (void)session;
    classic_ctx_t *ctx = opaque;
    int attached = 0;
    JNIEnv *env = classic_get_env(ctx, &attached);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback, ctx->on_volume, (jfloat)volume);
    classic_release_env(ctx, env, attached);
}

static void classic_audio_set_metadata(void *opaque, void *session,
                                       const void *buffer, int length) {
    (void)session;
    classic_ctx_t *ctx = opaque;
    classic_call_bytes(ctx, ctx->on_metadata, buffer, length);
}

static void classic_audio_set_cover_art(void *opaque, void *session,
                                        const void *buffer, int length) {
    (void)session;
    classic_ctx_t *ctx = opaque;
    classic_call_bytes(ctx, ctx->on_cover_art, buffer, length);
}

static void classic_audio_set_progress(void *opaque, void *session,
                                       unsigned int start, unsigned int current,
                                       unsigned int end) {
    (void)session;
    classic_ctx_t *ctx = opaque;
    int attached = 0;
    JNIEnv *env = classic_get_env(ctx, &attached);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback, ctx->on_progress,
                           (jlong)start, (jlong)current, (jlong)end);
    classic_release_env(ctx, env, attached);
}

static void classic_audio_remote_control_id(void *opaque, const char *dacp_id,
                                            const char *active_remote) {
    classic_ctx_t *ctx = opaque;
    if (!dacp_id || !active_remote) return;
    int attached = 0;
    JNIEnv *env = classic_get_env(ctx, &attached);
    if (!env) return;
    jstring id = (*env)->NewStringUTF(env, dacp_id);
    jstring remote = (*env)->NewStringUTF(env, active_remote);
    if (id && remote) {
        (*env)->CallVoidMethod(env, ctx->callback, ctx->on_dacp, id, remote);
    }
    if (id) (*env)->DeleteLocalRef(env, id);
    if (remote) (*env)->DeleteLocalRef(env, remote);
    classic_release_env(ctx, env, attached);
}

static void classic_log(void *opaque, int level, const char *message) {
    (void)level;
    classic_ctx_t *ctx = opaque;
    if (!message) return;
    int attached = 0;
    JNIEnv *env = classic_get_env(ctx, &attached);
    if (!env) return;
    jstring text = (*env)->NewStringUTF(env, message);
    if (text) {
        (*env)->CallVoidMethod(env, ctx->callback, ctx->on_log, text);
        (*env)->DeleteLocalRef(env, text);
    }
    classic_release_env(ctx, env, attached);
}

JNIEXPORT jlong JNICALL
Java_io_github_jqssun_airplay_bridge_ClassicRaopBridge_nativeCreate(
        JNIEnv *env, jobject thiz, jobject callback, jstring pem_key) {
    (void)thiz;
    if (!callback || !pem_key) return 0;

    classic_ctx_t *ctx = calloc(1, sizeof(classic_ctx_t));
    if (!ctx) return 0;
    if ((*env)->GetJavaVM(env, &ctx->vm) != JNI_OK) {
        free(ctx);
        return 0;
    }

    ctx->callback = (*env)->NewGlobalRef(env, callback);
    jclass cls = (*env)->GetObjectClass(env, callback);
    ctx->on_audio_start = (*env)->GetMethodID(env, cls, "onClassicAudioStart", "(III)V");
    ctx->on_pcm = (*env)->GetMethodID(env, cls, "onClassicPcm", "([B)V");
    ctx->on_audio_stop = (*env)->GetMethodID(env, cls, "onClassicAudioStop", "()V");
    ctx->on_flush = (*env)->GetMethodID(env, cls, "onClassicFlush", "()V");
    ctx->on_volume = (*env)->GetMethodID(env, cls, "onClassicVolume", "(F)V");
    ctx->on_metadata = (*env)->GetMethodID(env, cls, "onClassicMetadata", "([B)V");
    ctx->on_cover_art = (*env)->GetMethodID(env, cls, "onClassicCoverArt", "([B)V");
    ctx->on_progress = (*env)->GetMethodID(env, cls, "onClassicProgress", "(JJJ)V");
    ctx->on_dacp = (*env)->GetMethodID(env, cls, "onClassicDacp", "(Ljava/lang/String;Ljava/lang/String;)V");
    ctx->on_log = (*env)->GetMethodID(env, cls, "onClassicLog", "(Ljava/lang/String;)V");
    (*env)->DeleteLocalRef(env, cls);

    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteGlobalRef(env, ctx->callback);
        free(ctx);
        return 0;
    }

    const char *key = (*env)->GetStringUTFChars(env, pem_key, NULL);
    if (!key) {
        (*env)->DeleteGlobalRef(env, ctx->callback);
        free(ctx);
        return 0;
    }

    raop_callbacks_t callbacks;
    memset(&callbacks, 0, sizeof(callbacks));
    callbacks.cls = ctx;
    callbacks.audio_init = classic_audio_init;
    callbacks.audio_process = classic_audio_process;
    callbacks.audio_destroy = classic_audio_destroy;
    callbacks.audio_flush = classic_audio_flush;
    callbacks.audio_set_volume = classic_audio_set_volume;
    callbacks.audio_set_metadata = classic_audio_set_metadata;
    callbacks.audio_set_coverart = classic_audio_set_cover_art;
    callbacks.audio_remote_control_id = classic_audio_remote_control_id;
    callbacks.audio_set_progress = classic_audio_set_progress;

    ctx->raop = raop_init(4, &callbacks, key, NULL);
    (*env)->ReleaseStringUTFChars(env, pem_key, key);
    if (!ctx->raop) {
        (*env)->DeleteGlobalRef(env, ctx->callback);
        free(ctx);
        return 0;
    }

    raop_set_log_level(ctx->raop, RAOP_LOG_INFO);
    raop_set_log_callback(ctx->raop, classic_log, ctx);
    return (jlong)(intptr_t)ctx;
}

JNIEXPORT jint JNICALL
Java_io_github_jqssun_airplay_bridge_ClassicRaopBridge_nativeStart(
        JNIEnv *env, jobject thiz, jlong handle, jint requested_port,
        jbyteArray hw_addr, jstring password) {
    (void)thiz;
    classic_ctx_t *ctx = (classic_ctx_t *)(intptr_t)handle;
    if (!ctx || !ctx->raop || !hw_addr) return -1;

    jsize hw_length = (*env)->GetArrayLength(env, hw_addr);
    if (hw_length <= 0 || hw_length > 6) return -1;
    jbyte hw[6] = {0};
    (*env)->GetByteArrayRegion(env, hw_addr, 0, hw_length, hw);

    const char *password_chars = NULL;
    if (password) {
        password_chars = (*env)->GetStringUTFChars(env, password, NULL);
    }

    unsigned short port = requested_port > 0 && requested_port <= 65535
                          ? (unsigned short)requested_port : 0;
    int result = raop_start(ctx->raop, &port, (const char *)hw, hw_length,
                            password_chars);
    if (password_chars) {
        (*env)->ReleaseStringUTFChars(env, password, password_chars);
    }
    if (result < 0) return -1;
    ctx->running = 1;
    return (jint)port;
}

JNIEXPORT void JNICALL
Java_io_github_jqssun_airplay_bridge_ClassicRaopBridge_nativeStop(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    classic_ctx_t *ctx = (classic_ctx_t *)(intptr_t)handle;
    if (!ctx || !ctx->raop || !ctx->running) return;
    raop_stop(ctx->raop);
    ctx->running = 0;
}

JNIEXPORT void JNICALL
Java_io_github_jqssun_airplay_bridge_ClassicRaopBridge_nativeDestroy(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void)thiz;
    classic_ctx_t *ctx = (classic_ctx_t *)(intptr_t)handle;
    if (!ctx) return;
    if (ctx->raop) {
        if (ctx->running) raop_stop(ctx->raop);
        raop_destroy(ctx->raop);
    }
    if (ctx->callback) (*env)->DeleteGlobalRef(env, ctx->callback);
    free(ctx);
}
