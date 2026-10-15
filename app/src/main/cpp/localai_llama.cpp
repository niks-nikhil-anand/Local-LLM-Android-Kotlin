// JNI bridge between LocalAI (Kotlin) and llama.cpp's public C API (include/llama.h).
//
// Ownership: nativeLoad() returns an opaque pointer to a Session that owns the model, context and
// sampler. Kotlin keeps exactly one owner of that handle and calls nativeFree() once. All calls
// except nativeRequestStop() are made from a single Kotlin thread; nativeRequestStop() only flips
// an atomic flag that llama_decode() polls through its abort callback.
//
// Text crosses the boundary as UTF-8 byte arrays: JNI's NewStringUTF/GetStringUTFChars use
// "modified UTF-8", which corrupts characters outside the BMP (e.g. emoji).
//
// Logging: only llama.cpp warnings/errors and lifecycle events; never prompt or output text.

#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "LocalAI-llama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

// Keep in sync with NativeErrorCode in NativeLlama.kt.
enum ErrorCode {
    ERR_INVALID_ARGUMENT = 1,
    ERR_MODEL_LOAD_FAILED = 2,
    ERR_CONTEXT_FAILED = 3,
    ERR_TOKENIZE_FAILED = 4,
    ERR_PROMPT_TOO_LONG = 5,
    ERR_DECODE_FAILED = 6,
};

// Keep in sync with StopReason in NativeLlama.kt.
enum StopReason { STOP_NONE = 0, STOP_EOG = 1, STOP_MAX_TOKENS = 2, STOP_ABORTED = 3, STOP_CONTEXT_FULL = 4 };

struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    llama_sampler *sampler = nullptr;
    const llama_vocab *vocab = nullptr;
    std::atomic<bool> abort{false};
    int n_ctx = 0;
    int n_batch = 0;
    int n_past = 0;
    int generated = 0;
    int max_tokens = 0;
    int stop_reason = STOP_NONE;
};

bool abort_callback(void *data) {
    return static_cast<Session *>(data)->abort.load(std::memory_order_relaxed);
}

void log_callback(ggml_log_level level, const char *text, void * /*user*/) {
    if (level == GGML_LOG_LEVEL_ERROR) {
        __android_log_write(ANDROID_LOG_ERROR, LOG_TAG, text);
    } else if (level == GGML_LOG_LEVEL_WARN) {
        __android_log_write(ANDROID_LOG_WARN, LOG_TAG, text);
    }
}

void throw_native(JNIEnv *env, int code, const std::string &message) {
    jclass cls = env->FindClass("com/example/localai/data/inference/NativeLlamaException");
    if (cls == nullptr) return;  // ClassNotFoundError already pending
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(ILjava/lang/String;)V");
    jstring jmsg = env->NewStringUTF(message.c_str());  // ASCII-only messages
    auto *ex = static_cast<jthrowable>(env->NewObject(cls, ctor, (jint) code, jmsg));
    env->Throw(ex);
}

Session *as_session(JNIEnv *env, jlong handle) {
    if (handle == 0) {
        throw_native(env, ERR_INVALID_ARGUMENT, "No model session");
        return nullptr;
    }
    return reinterpret_cast<Session *>(handle);
}

std::string bytes_to_string(JNIEnv *env, jbyteArray bytes) {
    if (bytes == nullptr) return {};
    const jsize len = env->GetArrayLength(bytes);
    std::string out(static_cast<size_t>(len), '\0');
    env->GetByteArrayRegion(bytes, 0, len, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

jbyteArray string_to_bytes(JNIEnv *env, const std::string &text) {
    jbyteArray out = env->NewByteArray(static_cast<jsize>(text.size()));
    if (out != nullptr) {
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte *>(text.data()));
    }
    return out;
}

void free_session(Session *s) {
    if (s == nullptr) return;
    if (s->sampler) llama_sampler_free(s->sampler);
    if (s->ctx) llama_free(s->ctx);
    if (s->model) llama_model_free(s->model);
    delete s;
}

}  // namespace

extern "C" {

JNIEXPORT jbyteArray JNICALL
Java_com_example_localai_data_inference_LlamaJni_nativeInit(JNIEnv *env, jobject /*thiz*/) {
    llama_log_set(log_callback, nullptr);
    llama_backend_init();
    LOGI("llama.cpp backend initialised");
    return string_to_bytes(env, llama_print_system_info());
}

JNIEXPORT jlong JNICALL
Java_com_example_localai_data_inference_LlamaJni_nativeLoad(
        JNIEnv *env, jobject /*thiz*/, jbyteArray jpath, jint n_ctx, jint n_threads, jint n_batch) {
    if (n_ctx <= 0 || n_threads <= 0 || n_batch <= 0) {
        throw_native(env, ERR_INVALID_ARGUMENT, "Context, thread and batch sizes must be positive");
        return 0;
    }
    const std::string path = bytes_to_string(env, jpath);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;  // CPU only: no GPU backend is built
    auto *s = new Session();
    s->model = llama_model_load_from_file(path.c_str(), mparams);
    if (s->model == nullptr) {
        free_session(s);
        throw_native(env, ERR_MODEL_LOAD_FAILED,
                     "llama.cpp could not load the model: the file is invalid, incomplete, or uses an unsupported architecture or tensor type");
        return 0;
    }
    s->vocab = llama_model_get_vocab(s->model);

    const int trained = llama_model_n_ctx_train(s->model);
    const int ctx_len = trained > 0 ? std::min<int>(n_ctx, trained) : n_ctx;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(ctx_len);
    cparams.n_batch = static_cast<uint32_t>(std::min<int>(n_batch, ctx_len));
    cparams.n_ubatch = cparams.n_batch;
    cparams.n_threads = n_threads;
    cparams.n_threads_batch = n_threads;
    cparams.abort_callback = abort_callback;
    cparams.abort_callback_data = s;
    cparams.no_perf = true;
    s->ctx = llama_init_from_model(s->model, cparams);
    if (s->ctx == nullptr) {
        free_session(s);
        throw_native(env, ERR_CONTEXT_FAILED,
                     "llama.cpp could not create an inference context (usually not enough memory for this context length)");
        return 0;
    }
    s->n_ctx = static_cast<int>(llama_n_ctx(s->ctx));
    s->n_batch = static_cast<int>(cparams.n_batch);
    LOGI("model loaded: n_ctx=%d (trained %d), threads=%d, batch=%d", s->n_ctx, trained, n_threads, s->n_batch);
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT jint JNICALL
Java_com_example_localai_data_inference_LlamaJni_nativeContextLength(JNIEnv *env, jobject /*thiz*/, jlong handle) {
    Session *s = as_session(env, handle);
    return s ? s->n_ctx : 0;
}

JNIEXPORT jbyteArray JNICALL
Java_com_example_localai_data_inference_LlamaJni_nativeDescribe(JNIEnv *env, jobject /*thiz*/, jlong handle) {
    Session *s = as_session(env, handle);
    if (!s) return nullptr;
    char desc[256] = {0};
    llama_model_desc(s->model, desc, sizeof(desc));
    std::string out = std::string(desc) + "|" + std::to_string(llama_model_size(s->model)) + "|" +
                      std::to_string(llama_model_n_params(s->model));
    return string_to_bytes(env, out);
}

// Formats a conversation with the model's own chat template. Returns null when the model has no
// template or llama.cpp does not recognise it, so Kotlin can apply its documented fallback.
JNIEXPORT jbyteArray JNICALL
Java_com_example_localai_data_inference_LlamaJni_nativeApplyTemplate(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jobjectArray jroles, jobjectArray jcontents, jboolean add_assistant) {
    Session *s = as_session(env, handle);
    if (!s) return nullptr;
    const char *tmpl = llama_model_chat_template(s->model, nullptr);
    if (tmpl == nullptr) return nullptr;

    const jsize n = env->GetArrayLength(jroles);
    if (n != env->GetArrayLength(jcontents)) {
        throw_native(env, ERR_INVALID_ARGUMENT, "Roles and contents differ in length");
        return nullptr;
    }
    std::vector<std::string> roles(n), contents(n);
    std::vector<llama_chat_message> messages(n);
    size_t total = 0;
    for (jsize i = 0; i < n; i++) {
        roles[i] = bytes_to_string(env, static_cast<jbyteArray>(env->GetObjectArrayElement(jroles, i)));
        contents[i] = bytes_to_string(env, static_cast<jbyteArray>(env->GetObjectArrayElement(jcontents, i)));
        total += roles[i].size() + contents[i].size();
    }
    for (jsize i = 0; i < n; i++) messages[i] = {roles[i].c_str(), contents[i].c_str()};

    std::vector<char> buf(std::max<size_t>(1024, total * 2 + 256));
    int32_t len = llama_chat_apply_template(tmpl, messages.data(), messages.size(), add_assistant, buf.data(), (int32_t) buf.size());
    if (len < 0) return nullptr;  // template not supported by llama_chat_apply_template
    if (len > (int32_t) buf.size()) {
        buf.resize(len);
        len = llama_chat_apply_template(tmpl, messages.data(), messages.size(), add_assistant, buf.data(), (int32_t) buf.size());
        if (len < 0) return nullptr;
    }
    return string_to_bytes(env, std::string(buf.data(), len));
}

// Resets the conversation state, tokenises [prompt] and evaluates it. Returns the prompt token
// count, or -1 if a stop request aborted the evaluation.
JNIEXPORT jint JNICALL
Java_com_example_localai_data_inference_LlamaJni_nativeStartCompletion(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jbyteArray jprompt, jint max_tokens,
        jfloat temperature, jfloat top_p, jint seed) {
    Session *s = as_session(env, handle);
    if (!s) return 0;
    s->abort.store(false);
    s->stop_reason = STOP_NONE;
    s->generated = 0;
    s->n_past = 0;
    llama_memory_clear(llama_get_memory(s->ctx), true);

    const std::string prompt = bytes_to_string(env, jprompt);
    const int needed = -llama_tokenize(s->vocab, prompt.data(), (int32_t) prompt.size(), nullptr, 0, true, true);
    if (needed <= 0) {
        throw_native(env, ERR_TOKENIZE_FAILED, "The prompt could not be tokenised");
        return 0;
    }
    std::vector<llama_token> tokens(needed);
    if (llama_tokenize(s->vocab, prompt.data(), (int32_t) prompt.size(), tokens.data(), needed, true, true) < 0) {
        throw_native(env, ERR_TOKENIZE_FAILED, "The prompt could not be tokenised");
        return 0;
    }
    if ((int) tokens.size() >= s->n_ctx - 1) {
        throw_native(env, ERR_PROMPT_TOO_LONG,
                     "The prompt has " + std::to_string(tokens.size()) + " tokens; the context holds " + std::to_string(s->n_ctx));
        return 0;
    }

    if (s->sampler) llama_sampler_free(s->sampler);
    s->sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(s->sampler, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(s->sampler, llama_sampler_init_top_p(top_p, 1));
        llama_sampler_chain_add(s->sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(s->sampler, llama_sampler_init_dist(static_cast<uint32_t>(seed)));
    }

    for (size_t i = 0; i < tokens.size(); i += s->n_batch) {
        const int32_t count = (int32_t) std::min<size_t>(s->n_batch, tokens.size() - i);
        const int32_t rc = llama_decode(s->ctx, llama_batch_get_one(tokens.data() + i, count));
        if (rc == 2 || s->abort.load()) {
            s->stop_reason = STOP_ABORTED;
            return -1;
        }
        if (rc != 0) {
            throw_native(env, ERR_DECODE_FAILED, "llama_decode failed on the prompt (code " + std::to_string(rc) + ")");
            return 0;
        }
    }
    s->n_past = (int) tokens.size();
    s->max_tokens = std::max(0, std::min<int>(max_tokens, s->n_ctx - s->n_past - 1));
    return (jint) tokens.size();
}

// Samples, evaluates and returns the next piece of text (possibly an incomplete UTF-8 sequence),
// or null when generation has ended; nativeStopReason() then says why.
JNIEXPORT jbyteArray JNICALL
Java_com_example_localai_data_inference_LlamaJni_nativeNextToken(JNIEnv *env, jobject /*thiz*/, jlong handle) {
    Session *s = as_session(env, handle);
    if (!s || s->sampler == nullptr) return nullptr;
    if (s->abort.load()) { s->stop_reason = STOP_ABORTED; return nullptr; }
    if (s->stop_reason != STOP_NONE) return nullptr;
    if (s->generated >= s->max_tokens) {
        s->stop_reason = (s->n_past >= s->n_ctx - 1) ? STOP_CONTEXT_FULL : STOP_MAX_TOKENS;
        return nullptr;
    }

    llama_token token = llama_sampler_sample(s->sampler, s->ctx, -1);
    if (llama_vocab_is_eog(s->vocab, token)) {
        s->stop_reason = STOP_EOG;
        return nullptr;
    }

    std::vector<char> piece(64);
    int32_t n = llama_token_to_piece(s->vocab, token, piece.data(), (int32_t) piece.size(), 0, false);
    if (n < 0) {
        piece.resize(-n);
        n = llama_token_to_piece(s->vocab, token, piece.data(), (int32_t) piece.size(), 0, false);
    }
    const std::string text(piece.data(), std::max<int32_t>(n, 0));

    const int32_t rc = llama_decode(s->ctx, llama_batch_get_one(&token, 1));
    s->generated++;
    s->n_past++;
    if (rc == 2 || s->abort.load()) {
        s->stop_reason = STOP_ABORTED;
    } else if (rc == 1) {
        s->stop_reason = STOP_CONTEXT_FULL;  // no KV slot left; the piece is still valid output
    } else if (rc != 0) {
        throw_native(env, ERR_DECODE_FAILED, "llama_decode failed while generating (code " + std::to_string(rc) + ")");
        return nullptr;
    }
    return string_to_bytes(env, text);
}

JNIEXPORT jint JNICALL
Java_com_example_localai_data_inference_LlamaJni_nativeStopReason(JNIEnv *env, jobject /*thiz*/, jlong handle) {
    Session *s = as_session(env, handle);
    return s ? s->stop_reason : STOP_NONE;
}

// Thread-safe: only sets an atomic flag. Kotlin guarantees the handle is not freed concurrently.
JNIEXPORT void JNICALL
Java_com_example_localai_data_inference_LlamaJni_nativeRequestStop(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    if (handle != 0) reinterpret_cast<Session *>(handle)->abort.store(true);
}

JNIEXPORT void JNICALL
Java_com_example_localai_data_inference_LlamaJni_nativeFree(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    free_session(reinterpret_cast<Session *>(handle));
    LOGI("model session freed");
}

}  // extern "C"
