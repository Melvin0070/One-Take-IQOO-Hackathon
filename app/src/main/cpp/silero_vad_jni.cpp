#include <jni.h>

#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>

#include "whisper.h"

#include <array>
#include <cmath>
#include <cstdint>
#include <limits>
#include <memory>

namespace {

constexpr int kFrameSamples = 512;
constexpr float kSpeechProbability = 0.2f;
constexpr float kNonSpeechProbability = 0.15f;

struct AssetReader {
    AAsset * asset = nullptr;
    int64_t length = 0;
    int64_t position = 0;

    ~AssetReader() {
        if (asset != nullptr) {
            AAsset_close(asset);
        }
    }
};

struct NativeSileroClassifier {
    whisper_vad_context * context = nullptr;

    ~NativeSileroClassifier() {
        if (context != nullptr) {
            whisper_vad_free(context);
        }
    }
};

size_t readAsset(void * raw_reader, void * output, size_t read_size) {
    auto * reader = static_cast<AssetReader *>(raw_reader);
    if (reader == nullptr || reader->asset == nullptr || output == nullptr || read_size == 0) {
        return 0;
    }

    const int bytes_read = AAsset_read(reader->asset, output, read_size);
    if (bytes_read <= 0) {
        return 0;
    }
    reader->position += bytes_read;
    return static_cast<size_t>(bytes_read);
}

bool assetEof(void * raw_reader) {
    const auto * reader = static_cast<const AssetReader *>(raw_reader);
    return reader == nullptr || reader->asset == nullptr || reader->position >= reader->length;
}

void closeAsset(void * raw_reader) {
    auto * reader = static_cast<AssetReader *>(raw_reader);
    if (reader != nullptr && reader->asset != nullptr) {
        AAsset_close(reader->asset);
        reader->asset = nullptr;
    }
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_one_1take_audio_SileroSpeechClassifier_nativeCreate(
        JNIEnv * env,
        jobject,
        jobject asset_manager,
        jstring asset_name) {
    if (asset_manager == nullptr || asset_name == nullptr) {
        return 0;
    }

    AAssetManager * manager = AAssetManager_fromJava(env, asset_manager);
    if (manager == nullptr) {
        return 0;
    }

    const char * name = env->GetStringUTFChars(asset_name, nullptr);
    if (name == nullptr) {
        return 0;
    }

    AAsset * asset = AAssetManager_open(manager, name, AASSET_MODE_STREAMING);
    env->ReleaseStringUTFChars(asset_name, name);
    if (asset == nullptr) {
        return 0;
    }

    AssetReader reader;
    reader.asset = asset;
    reader.length = AAsset_getLength64(asset);

    try {
        whisper_model_loader loader = {};
        loader.context = &reader;
        loader.read = readAsset;
        loader.eof = assetEof;
        loader.close = closeAsset;

        whisper_vad_context_params params = whisper_vad_default_context_params();
        params.n_threads = 1;
        params.use_gpu = false;
        params.gpu_device = 0;

        std::unique_ptr<whisper_vad_context, decltype(&whisper_vad_free)> context(
                whisper_vad_init_with_params(&loader, params),
                whisper_vad_free);
        if (!context) {
            return 0;
        }

        // Force the probability vector to be allocated before the first real
        // frame. The upstream no-reset helper currently returns true even when
        // graph computation fails, so classify() uses a NaN sentinel per call.
        std::array<float, kFrameSamples> zeros{};
        if (!whisper_vad_detect_speech_no_reset(context.get(), zeros.data(), kFrameSamples) ||
                whisper_vad_n_probs(context.get()) != 1 ||
                whisper_vad_probs(context.get()) == nullptr) {
            return 0;
        }
        // A newly allocated probability defaults to zero, which cannot prove
        // computation succeeded. Verify a second warmup overwrites a sentinel
        // before accepting this context and disabling availability fallbacks.
        whisper_vad_probs(context.get())[0] = std::numeric_limits<float>::quiet_NaN();
        const bool warmed = whisper_vad_detect_speech_no_reset(
                context.get(), zeros.data(), kFrameSamples);
        const float * probabilities = whisper_vad_probs(context.get());
        if (!warmed || whisper_vad_n_probs(context.get()) != 1 || probabilities == nullptr ||
                !std::isfinite(probabilities[0]) || probabilities[0] < 0.0f ||
                probabilities[0] > 1.0f) {
            return 0;
        }
        whisper_vad_reset_state(context.get());

        auto classifier = std::make_unique<NativeSileroClassifier>();
        classifier->context = context.release();
        return reinterpret_cast<jlong>(classifier.release());
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_one_1take_audio_SileroSpeechClassifier_nativeClassify(
        JNIEnv * env,
        jobject,
        jlong handle,
        jfloatArray samples) {
    if (handle == 0 || samples == nullptr || env->GetArrayLength(samples) != kFrameSamples) {
        return -1;
    }

    auto * classifier = reinterpret_cast<NativeSileroClassifier *>(handle);
    if (classifier->context == nullptr) {
        return -1;
    }

    std::array<float, kFrameSamples> values{};
    env->GetFloatArrayRegion(samples, 0, kFrameSamples, values.data());
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return -1;
    }

    for (const float value : values) {
        if (!std::isfinite(value) || value < -1.0f || value > 1.0f) {
            return -1;
        }
    }

    try {
        const int probability_count_before = whisper_vad_n_probs(classifier->context);
        float * probabilities_before = whisper_vad_probs(classifier->context);
        if (probability_count_before != 1 || probabilities_before == nullptr) {
            return -1;
        }
        probabilities_before[0] = std::numeric_limits<float>::quiet_NaN();

        const bool computed = whisper_vad_detect_speech_no_reset(
                classifier->context,
                values.data(),
                kFrameSamples);

        float * probabilities = whisper_vad_probs(classifier->context);
        if (!computed || whisper_vad_n_probs(classifier->context) != 1 || probabilities == nullptr ||
                !std::isfinite(probabilities[0])) {
            whisper_vad_reset_state(classifier->context);
            return -1;
        }

        const float probability = probabilities[0];
        if (probability >= kSpeechProbability) {
            return 1;
        }
        if (probability <= kNonSpeechProbability) {
            return 0;
        }
        return -1;
    } catch (...) {
        whisper_vad_reset_state(classifier->context);
        return -1;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_one_1take_audio_SileroSpeechClassifier_nativeDestroy(
        JNIEnv *,
        jobject,
        jlong handle) {
    if (handle != 0) {
        delete reinterpret_cast<NativeSileroClassifier *>(handle);
    }
}
