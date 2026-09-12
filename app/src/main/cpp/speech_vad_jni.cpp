#include <jni.h>

#include "fvad.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>

namespace {

constexpr jint kSampleRate = 16'000;
constexpr jsize kFrameSamples = 320;
constexpr jint kMode = 0;

bool isValidSample(float sample) {
    return std::isfinite(sample) && sample >= -1.0f && sample <= 1.0f;
}

int16_t toPcm16(float sample) {
    // The Java side validates the input too. Keep the native boundary
    // defensive because JNI callers can bypass that contract.
    const float scaled = sample * 32767.0f;
    const float bounded = std::max(-32768.0f, std::min(32767.0f, scaled));
    return static_cast<int16_t>(std::lround(bounded));
}

Fvad * vadFromHandle(jlong handle) {
    return reinterpret_cast<Fvad *>(handle);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_one_1take_audio_WebRtcSpeechClassifier_nativeCreate(
        JNIEnv *,
        jobject,
        jint mode,
        jint sample_rate) {
    if (mode != kMode || sample_rate != kSampleRate) {
        return 0;
    }

    Fvad * vad = fvad_new();
    if (vad == nullptr || fvad_set_mode(vad, mode) != 0 ||
            fvad_set_sample_rate(vad, sample_rate) != 0) {
        if (vad != nullptr) {
            fvad_free(vad);
        }
        return 0;
    }
    return reinterpret_cast<jlong>(vad);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_one_1take_audio_WebRtcSpeechClassifier_nativeClassify(
        JNIEnv * env,
        jobject,
        jlong handle,
        jfloatArray samples) {
    if (handle == 0 || samples == nullptr || env->GetArrayLength(samples) != kFrameSamples) {
        return -1;
    }

    jfloat * values = env->GetFloatArrayElements(samples, nullptr);
    if (values == nullptr) {
        return -1;
    }

    std::array<int16_t, kFrameSamples> pcm{};
    bool valid = true;
    for (jsize index = 0; index < kFrameSamples; ++index) {
        const float sample = values[index];
        if (!isValidSample(sample)) {
            valid = false;
            break;
        }
        pcm[static_cast<size_t>(index)] = toPcm16(sample);
    }
    env->ReleaseFloatArrayElements(samples, values, JNI_ABORT);
    if (!valid) {
        return -1;
    }

    const int result = fvad_process(vadFromHandle(handle), pcm.data(), pcm.size());
    return result == 0 || result == 1 ? result : -1;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_one_1take_audio_WebRtcSpeechClassifier_nativeDestroy(
        JNIEnv *,
        jobject,
        jlong handle) {
    if (handle != 0) {
        fvad_free(vadFromHandle(handle));
    }
}
