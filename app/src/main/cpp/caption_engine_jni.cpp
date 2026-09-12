#include <jni.h>
#include <android/log.h>

#include "whisper.h"

#include <algorithm>
#include <atomic>
#include <cctype>
#include <cstdint>
#include <cmath>
#include <exception>
#include <memory>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

namespace {

constexpr int kSampleRate = 16'000;
constexpr int kMaxDurationSeconds = 120;
constexpr size_t kMaxSamples = static_cast<size_t>(kSampleRate) * kMaxDurationSeconds;

// Cancellation callbacks can outlive the JNI call that registered them by a
// small amount. A generation lets a late callback safely target only its own
// inference without retaining a pointer to a session that may be destroyed.
std::atomic<uint64_t> g_next_cancel_generation{0};
std::atomic<uint64_t> g_cancelled_generation{0};

struct NativeSession {
    whisper_context * context = nullptr;
    std::string language;

    ~NativeSession() {
        if (context != nullptr) {
            whisper_free(context);
        }
    }
};

bool whisperAbortCallback(void * user_data) {
    const auto * generation = static_cast<const uint64_t *>(user_data);
    return generation != nullptr &&
           g_cancelled_generation.load(std::memory_order_relaxed) == *generation;
}

void silentWhisperLog(enum ggml_log_level, const char *, void *) {
    // Caption text and model diagnostics must never be written to logs.
}

void throwJava(JNIEnv * env, const char * class_name, const std::string & message) {
    if (!env->ExceptionCheck()) {
        jclass exception_class = env->FindClass(class_name);
        if (exception_class != nullptr) {
            env->ThrowNew(exception_class, message.c_str());
            env->DeleteLocalRef(exception_class);
        }
    }
}

std::string readString(JNIEnv * env, jstring value, const char * name) {
    if (value == nullptr) {
        throw std::invalid_argument(std::string(name) + " is null");
    }

    const char * utf = env->GetStringUTFChars(value, nullptr);
    if (utf == nullptr) {
        throw std::runtime_error(std::string("Unable to read ") + name);
    }

    std::string result(utf);
    env->ReleaseStringUTFChars(value, utf);
    return result;
}

std::string trimAndNormalize(const char * raw) {
    if (raw == nullptr) {
        return {};
    }

    std::string result;
    result.reserve(std::char_traits<char>::length(raw));
    bool pending_space = false;

    for (const unsigned char * cursor = reinterpret_cast<const unsigned char *>(raw);
         *cursor != '\0'; ++cursor) {
        if (std::isspace(*cursor) != 0) {
            pending_space = !result.empty();
            continue;
        }
        if (pending_space) {
            result.push_back(' ');
            pending_space = false;
        }
        result.push_back(static_cast<char>(*cursor));
    }

    return result;
}

bool isSpecialTokenText(const std::string & text) {
    if (text.empty() || text == "[BLANK_AUDIO]") {
        return true;
    }

    // Whisper uses these markers for timestamps and other decoder control
    // tokens. They should never become visible captions.
    return text.find("<|") != std::string::npos && text.find("|>") != std::string::npos;
}

struct NativeSegment {
    int64_t start_ms;
    int64_t end_ms;
    std::string text;
    struct Word {
        int64_t start_ms;
        int64_t end_ms;
        std::string text;
        float confidence;
    };
    std::vector<Word> words;
};

NativeSession * sessionFromHandle(jlong handle) {
    if (handle == 0) {
        throw std::invalid_argument("session handle is null");
    }
    return reinterpret_cast<NativeSession *>(handle);
}

std::vector<float> copyAudio(JNIEnv * env, jfloatArray audio) {
    if (audio == nullptr) {
        throw std::invalid_argument("audio is null");
    }

    const jsize sample_count = env->GetArrayLength(audio);
    if (sample_count <= 0) {
        throw std::invalid_argument("audio is empty");
    }
    if (static_cast<size_t>(sample_count) > kMaxSamples) {
        throw std::invalid_argument("audio is longer than 120 seconds");
    }

    std::vector<float> pcm(static_cast<size_t>(sample_count));
    env->GetFloatArrayRegion(audio, 0, sample_count, pcm.data());
    if (env->ExceptionCheck()) {
        throw std::runtime_error("Unable to read audio samples");
    }
    return pcm;
}

whisper_full_params transcriptionParams(
        const NativeSession & session,
        const uint64_t * cancellation_token) {
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    const unsigned int hardware_threads = std::thread::hardware_concurrency();
    params.n_threads = static_cast<int>(std::max(
            1u,
            std::min(4u, hardware_threads == 0 ? 1u : hardware_threads)));
    params.translate = false;
    // Auto-detect the first inference in a session. Once whisper reports a
    // valid language, reuse it so streaming windows do not re-detect it.
    params.language = session.language.empty() ? "auto" : session.language.c_str();
    params.detect_language = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.token_timestamps = true;
    params.max_len = 42;
    params.split_on_word = true;
    params.abort_callback = whisperAbortCallback;
    params.abort_callback_user_data = const_cast<uint64_t *>(cancellation_token);
    // Keep the model's default encoder context. Short-window overrides are
    // model- and accuracy-sensitive, so streaming uses the safe default.
    params.audio_ctx = 0;
    return params;
}

std::vector<NativeSegment::Word> segmentWords(
        whisper_context * context,
        int segment_index,
        int64_t segment_start_ms,
        int64_t segment_end_ms) {
    std::vector<NativeSegment::Word> words;
    NativeSegment::Word current{};
    bool has_current = false;
    const int token_count = whisper_full_n_tokens(context, segment_index);

    auto flush = [&]() {
        if (!has_current) {
            return true;
        }
        if (current.end_ms <= current.start_ms || current.text.empty()) {
            has_current = false;
            return true;
        }
        if (!words.empty() && current.start_ms < words.back().end_ms) {
            return false;
        }
        words.push_back(std::move(current));
        current = NativeSegment::Word{};
        has_current = false;
        return true;
    };

    for (int token_index = 0; token_index < token_count; ++token_index) {
        const char * raw_text = whisper_full_get_token_text(context, segment_index, token_index);
        const std::string text = trimAndNormalize(raw_text);
        if (isSpecialTokenText(text)) {
            // Timestamp and decoder-control tokens do not carry user-visible word evidence.
            continue;
        }
        // A single model token that contains multiple whitespace-delimited words does not
        // provide separate timings.  Drop the segment's word evidence rather than assigning
        // the same duration to each word or interpolating an unsupported boundary.
        if (text.empty() || text.find(' ') != std::string::npos) {
            return {};
        }

        const float confidence = whisper_full_get_token_p(context, segment_index, token_index);
        if (!std::isfinite(confidence) || confidence < 0.0f || confidence > 1.0f) {
            return {};
        }

        const int64_t token_start_ms = std::clamp<int64_t>(
                whisper_full_get_token_t0(context, segment_index, token_index) * 10,
                segment_start_ms,
                segment_end_ms);
        const int64_t token_end_ms = std::clamp<int64_t>(
                whisper_full_get_token_t1(context, segment_index, token_index) * 10,
                segment_start_ms,
                segment_end_ms);
        if (token_end_ms <= token_start_ms) {
            return {};
        }

        const bool starts_new_word = raw_text != nullptr && raw_text[0] != '\0' &&
                std::isspace(static_cast<unsigned char>(raw_text[0])) != 0;
        if (starts_new_word && !flush()) {
            return {};
        }
        if (!has_current) {
            current = {token_start_ms, token_end_ms, text, confidence};
            has_current = true;
        } else {
            current.start_ms = std::min(current.start_ms, token_start_ms);
            current.end_ms = std::max(current.end_ms, token_end_ms);
            current.text += text;
            current.confidence = std::min(current.confidence, confidence);
        }
    }
    if (!flush()) {
        return {};
    }

    std::string reconstructed;
    for (const NativeSegment::Word & word : words) {
        if (!reconstructed.empty()) {
            reconstructed.push_back(' ');
        }
        reconstructed += word.text;
    }
    const std::string segment_text = trimAndNormalize(
            whisper_full_get_segment_text(context, segment_index));
    if (reconstructed != segment_text) {
        // Segment text may have been normalized or deduplicated by whisper.  In that case the
        // token timings no longer have a provable one-to-one relationship with displayed text.
        return {};
    }
    return words;
}

jobjectArray makeCaptionArray(JNIEnv * env, const std::vector<NativeSegment> & segments) {
    jclass segment_class = env->FindClass("com/example/one_take/captions/CaptionSegment");
    if (segment_class == nullptr) {
        return nullptr;
    }
    jmethodID segment_constructor = env->GetMethodID(
            segment_class,
            "<init>",
            "(JJLjava/lang/String;Ljava/util/List;)V");
    jclass word_class = env->FindClass("com/example/one_take/captions/CaptionWord");
    jclass list_class = env->FindClass("java/util/ArrayList");
    if (segment_constructor == nullptr || word_class == nullptr || list_class == nullptr) {
        env->DeleteLocalRef(segment_class);
        if (word_class != nullptr) env->DeleteLocalRef(word_class);
        if (list_class != nullptr) env->DeleteLocalRef(list_class);
        return nullptr;
    }
    jmethodID word_constructor = env->GetMethodID(
            word_class,
            "<init>",
            "(JJLjava/lang/String;F)V");
    jmethodID list_constructor = env->GetMethodID(list_class, "<init>", "()V");
    jmethodID list_add = env->GetMethodID(list_class, "add", "(Ljava/lang/Object;)Z");
    if (word_constructor == nullptr || list_constructor == nullptr || list_add == nullptr) {
        env->DeleteLocalRef(segment_class);
        env->DeleteLocalRef(word_class);
        env->DeleteLocalRef(list_class);
        return nullptr;
    }

    jobjectArray result = env->NewObjectArray(
            static_cast<jsize>(segments.size()),
            segment_class,
            nullptr);
    if (result == nullptr) {
        env->DeleteLocalRef(segment_class);
        env->DeleteLocalRef(word_class);
        env->DeleteLocalRef(list_class);
        return nullptr;
    }

    for (jsize index = 0; index < static_cast<jsize>(segments.size()); ++index) {
        const NativeSegment & segment = segments[static_cast<size_t>(index)];
        jstring text = env->NewStringUTF(segment.text.c_str());
        if (text == nullptr) {
            env->DeleteLocalRef(result);
            env->DeleteLocalRef(segment_class);
            env->DeleteLocalRef(word_class);
            env->DeleteLocalRef(list_class);
            return nullptr;
        }

        jobject word_list = env->NewObject(list_class, list_constructor);
        if (word_list == nullptr) {
            env->DeleteLocalRef(text);
            env->DeleteLocalRef(result);
            env->DeleteLocalRef(segment_class);
            env->DeleteLocalRef(word_class);
            env->DeleteLocalRef(list_class);
            return nullptr;
        }
        bool words_valid = true;
        for (const NativeSegment::Word & word : segment.words) {
            jstring word_text = env->NewStringUTF(word.text.c_str());
            if (word_text == nullptr) {
                words_valid = false;
                break;
            }
            jobject word_object = env->NewObject(
                    word_class,
                    word_constructor,
                    static_cast<jlong>(word.start_ms),
                    static_cast<jlong>(word.end_ms),
                    word_text,
                    static_cast<jfloat>(word.confidence));
            env->DeleteLocalRef(word_text);
            if (word_object == nullptr) {
                words_valid = false;
                break;
            }
            env->CallBooleanMethod(word_list, list_add, word_object);
            env->DeleteLocalRef(word_object);
            if (env->ExceptionCheck()) {
                words_valid = false;
                break;
            }
        }
        if (!words_valid) {
            env->DeleteLocalRef(word_list);
            env->DeleteLocalRef(text);
            env->DeleteLocalRef(result);
            env->DeleteLocalRef(segment_class);
            env->DeleteLocalRef(word_class);
            env->DeleteLocalRef(list_class);
            return nullptr;
        }
        jobject object = env->NewObject(
                segment_class,
                segment_constructor,
                static_cast<jlong>(segment.start_ms),
                static_cast<jlong>(segment.end_ms),
                text,
                word_list);
        env->DeleteLocalRef(word_list);
        env->DeleteLocalRef(text);
        if (object == nullptr) {
            env->DeleteLocalRef(result);
            env->DeleteLocalRef(segment_class);
            env->DeleteLocalRef(word_class);
            env->DeleteLocalRef(list_class);
            return nullptr;
        }
        env->SetObjectArrayElement(result, index, object);
        env->DeleteLocalRef(object);
        if (env->ExceptionCheck()) {
            env->DeleteLocalRef(result);
            env->DeleteLocalRef(segment_class);
            env->DeleteLocalRef(word_class);
            env->DeleteLocalRef(list_class);
            return nullptr;
        }
    }

    env->DeleteLocalRef(segment_class);
    env->DeleteLocalRef(word_class);
    env->DeleteLocalRef(list_class);
    return result;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_one_1take_captions_WhisperEngine_createSessionNative(
        JNIEnv * env,
        jobject,
        jstring model_path) {
    try {
        const std::string model = readString(env, model_path, "modelPath");
        if (model.empty()) {
            throw std::invalid_argument("modelPath is empty");
        }

        whisper_log_set(silentWhisperLog, nullptr);
        whisper_context_params context_params = whisper_context_default_params();
        context_params.use_gpu = false;
        context_params.flash_attn = false;

        auto session = std::make_unique<NativeSession>();
        session->context = whisper_init_from_file_with_params(model.c_str(), context_params);
        if (session->context == nullptr) {
            throw std::runtime_error("Whisper could not load the model");
        }
        return reinterpret_cast<jlong>(session.release());
    } catch (const std::exception & error) {
        throwJava(env, "java/lang/IllegalStateException", error.what());
        return 0;
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "Unknown Whisper error");
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_one_1take_captions_WhisperEngine_destroySessionNative(
        JNIEnv *,
        jobject,
        jlong handle) {
    delete reinterpret_cast<NativeSession *>(handle);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_example_one_1take_captions_WhisperEngine_transcribeSessionNative(
        JNIEnv * env,
        jobject,
        jlong handle,
        jfloatArray audio,
        jlong cancellation_token,
        jboolean optimize_for_streaming) {
    try {
        NativeSession * session = sessionFromHandle(handle);
        if (session->context == nullptr) {
            throw std::invalid_argument("session context is null");
        }
        if (cancellation_token <= 0) {
            throw std::invalid_argument("cancellation token is invalid");
        }

        std::vector<float> pcm = copyAudio(env, audio);
        const uint64_t cancellation_generation = static_cast<uint64_t>(cancellation_token);
        whisper_full_params params = transcriptionParams(
                *session,
                &cancellation_generation);
        // Short live windows retain at least three seconds of padding while
        // avoiding encoding the full 30-second model context on every update.
        // File transcription and longer windows retain the model default.
        if (optimize_for_streaming && pcm.size() <= 12 * kSampleRate) {
            params.audio_ctx = std::min(750, whisper_n_audio_ctx(session->context));
        }
        const int result = whisper_full(
                session->context,
                params,
                pcm.data(),
                static_cast<int>(pcm.size()));
        if (result != 0) {
            if (g_cancelled_generation.load(std::memory_order_relaxed) ==
                static_cast<uint64_t>(cancellation_token)) {
                throwJava(env, "java/util/concurrent/CancellationException", "Transcription cancelled");
                return nullptr;
            }
            throw std::runtime_error("Whisper transcription failed");
        }

        if (session->language.empty()) {
            const int language_id = whisper_full_lang_id(session->context);
            const char * language = language_id >= 0 ? whisper_lang_str(language_id) : nullptr;
            if (language != nullptr && language[0] != '\0') {
                session->language = language;
            }
        }

        const int64_t audio_duration_ms =
                (static_cast<int64_t>(pcm.size()) * 1000 + kSampleRate / 2) / kSampleRate;
        const int segment_count = whisper_full_n_segments(session->context);
        std::vector<NativeSegment> segments;
        segments.reserve(static_cast<size_t>(std::max(0, segment_count)));
        int64_t previous_end_ms = 0;

        for (int index = 0; index < segment_count; ++index) {
            const std::string text = trimAndNormalize(
                    whisper_full_get_segment_text(session->context, index));
            if (isSpecialTokenText(text)) {
                continue;
            }

            const int64_t start_ms = std::max<int64_t>(
                    previous_end_ms,
                    std::clamp<int64_t>(
                            whisper_full_get_segment_t0(session->context, index) * 10,
                            0,
                            audio_duration_ms));
            const int64_t end_ms = std::clamp<int64_t>(
                    whisper_full_get_segment_t1(session->context, index) * 10,
                    0,
                    audio_duration_ms);
            if (end_ms <= start_ms) {
                continue;
            }
            segments.push_back({
                    start_ms,
                    end_ms,
                    text,
                    segmentWords(session->context, index, start_ms, end_ms),
            });
            previous_end_ms = end_ms;
        }

        return makeCaptionArray(env, segments);
    } catch (const std::exception & error) {
        throwJava(env, "java/lang/IllegalStateException", error.what());
        return nullptr;
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "Unknown Whisper error");
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_one_1take_captions_WhisperEngine_cancelNative(
        JNIEnv *,
        jobject,
        jlong cancellation_token) {
    if (cancellation_token > 0) {
        g_cancelled_generation.store(
                static_cast<uint64_t>(cancellation_token),
                std::memory_order_relaxed);
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_one_1take_captions_WhisperEngine_resetCancelNative(
        JNIEnv *,
        jobject) {
    const uint64_t generation = g_next_cancel_generation.fetch_add(
            1,
            std::memory_order_relaxed) + 1;
    g_cancelled_generation.store(0, std::memory_order_relaxed);
    return static_cast<jlong>(generation);
}
