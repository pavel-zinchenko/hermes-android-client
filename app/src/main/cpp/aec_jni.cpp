// JNI wrapper around the vendored WebRTC AECM (acoustic echo canceller for
// mobile). The Kotlin side (com.hermes.android.audio.echo.WebRtcAecNative) drives
// this on 10 ms / 160-sample, 16 kHz mono int16 frames:
//   - processRender(far):  buffer one playback chunk as the echo reference;
//   - processCapture(near): remove the echo from one mic frame, in place.
// BufferFarend and Process are called from the render and capture threads
// respectively; the AECM's internal far-end ring buffer is single-producer /
// single-consumer safe, so no extra locking is needed between them. Create/Destroy
// must not overlap with either (the engine guarantees this via its lifecycle).

#include <jni.h>
#include <cstring>
#include <android/log.h>

#include "echo_control_mobile.h"

#define LOG_TAG "HermesAec"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// 10 ms at 16 kHz. The AECM accepts 80 or 160 samples per call; we always feed 160.
constexpr int kMaxFrame = 160;

struct AecState {
    void *inst = nullptr;
    // Estimated mic<->speaker round-trip delay; updated from AudioTrack/Record latency.
    int delayMs = 40;
};

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_hermes_android_audio_echo_WebRtcAecNative_nativeCreate(
        JNIEnv * /*env*/, jobject /*thiz*/, jint sampleRate) {
    void *inst = WebRtcAecm_Create();
    if (inst == nullptr) {
        LOGE("WebRtcAecm_Create failed");
        return 0;
    }
    if (WebRtcAecm_Init(inst, sampleRate) != 0) {
        LOGE("WebRtcAecm_Init(%d) failed", sampleRate);
        WebRtcAecm_Free(inst);
        return 0;
    }
    AecmConfig config;
    config.cngMode = AecmTrue;   // comfort noise during cancellation
    config.echoMode = 3;         // 0..4; 3 is the WebRTC default balance
    if (WebRtcAecm_set_config(inst, config) != 0) {
        LOGE("WebRtcAecm_set_config failed");
        WebRtcAecm_Free(inst);
        return 0;
    }
    auto *state = new AecState();
    state->inst = inst;
    return reinterpret_cast<jlong>(state);
}

JNIEXPORT void JNICALL
Java_com_hermes_android_audio_echo_WebRtcAecNative_nativeSetStreamDelayMs(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle, jint delayMs) {
    auto *state = reinterpret_cast<AecState *>(handle);
    if (state != nullptr && delayMs >= 0) state->delayMs = delayMs;
}

JNIEXPORT void JNICALL
Java_com_hermes_android_audio_echo_WebRtcAecNative_nativeProcessRender(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jshortArray far, jint len) {
    auto *state = reinterpret_cast<AecState *>(handle);
    if (state == nullptr || state->inst == nullptr || far == nullptr) return;
    if (len <= 0 || len > kMaxFrame) return;
    jshort *buf = env->GetShortArrayElements(far, nullptr);
    if (buf == nullptr) return;
    WebRtcAecm_BufferFarend(state->inst, reinterpret_cast<const int16_t *>(buf),
                            static_cast<size_t>(len));
    env->ReleaseShortArrayElements(far, buf, JNI_ABORT); // read-only, no copy-back
}

JNIEXPORT void JNICALL
Java_com_hermes_android_audio_echo_WebRtcAecNative_nativeProcessCapture(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jshortArray near, jint len) {
    auto *state = reinterpret_cast<AecState *>(handle);
    if (state == nullptr || state->inst == nullptr || near == nullptr) return;
    if (len <= 0 || len > kMaxFrame) return;
    jshort *buf = env->GetShortArrayElements(near, nullptr);
    if (buf == nullptr) return;
    int16_t out[kMaxFrame];
    int32_t ret = WebRtcAecm_Process(state->inst,
                                     reinterpret_cast<const int16_t *>(buf),
                                     nullptr, // no separate clean signal
                                     out,
                                     static_cast<size_t>(len),
                                     static_cast<int16_t>(state->delayMs));
    if (ret == 0) {
        memcpy(buf, out, sizeof(int16_t) * static_cast<size_t>(len));
        env->ReleaseShortArrayElements(near, buf, 0); // commit cleaned samples
    } else {
        env->ReleaseShortArrayElements(near, buf, JNI_ABORT);
    }
}

JNIEXPORT void JNICALL
Java_com_hermes_android_audio_echo_WebRtcAecNative_nativeDestroy(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *state = reinterpret_cast<AecState *>(handle);
    if (state == nullptr) return;
    if (state->inst != nullptr) WebRtcAecm_Free(state->inst);
    delete state;
}

} // extern "C"
