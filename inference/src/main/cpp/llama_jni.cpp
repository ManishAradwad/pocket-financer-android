#include <jni.h>
#include <vector>
#include <algorithm>
#include <string>
#include <cstring>
#include <cstdlib>
#include <atomic>
#include <mutex>
#include <thread>

#include "llama.h"
#include "log.h"

// ── Per-model state ─────────────────────────────────────────────────────────

struct ModelInstance {
    llama_model   *model   = nullptr;
    llama_context *ctx     = nullptr;
    const llama_vocab *vocab = nullptr;
    std::atomic<bool> should_stop{false};
};

// ── JNI lifecycle (one-time, when .so is loaded) ───────────────────────────
// llama_backend_init/free must be called exactly once per process lifetime.
// JNI_OnLoad/OnUnload guarantees that.

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM */*vm*/, void */*reserved*/) {
    llama_backend_init();
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM */*vm*/, void */*reserved*/) {
    llama_backend_free();
}

// ── Helpers ─────────────────────────────────────────────────────────────────

static jlong ptr_to_jlong(void *p) {
    return reinterpret_cast<jlong>(p);
}

static ModelInstance *jlong_to_instance(jlong handle) {
    return reinterpret_cast<ModelInstance *>(handle);
}

/** Optimal thread count: use available cores, capped at 4 for thermal safety. */
static int get_optimal_thread_count() {
    int cores = (int)std::thread::hardware_concurrency();
    return std::max(1, std::min(cores, 4));
}

// ── Log callback (routes llama.cpp logs to Android logcat) ──────────────────

static auto log_callback = [](ggml_log_level level, const char * text, void * /*user_data*/) {
    int prio;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_INFO:  prio = ANDROID_LOG_INFO;  break;
        default:                   prio = ANDROID_LOG_DEBUG; break;
    }
    __android_log_print(prio, "llama.cpp", "%s", text);
};

// ── nativeLoadModel ─────────────────────────────────────────────────────────

extern "C" JNIEXPORT jlong JNICALL
Java_com_pocketfinancer_inference_LlamaEngine_nativeLoadModel(
    JNIEnv *env, jclass /*clazz*/,
    jstring jpath, jint n_ctx, jint n_gpu_layers) {

    const char *path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) return 0;

    // Register log callback once (safe to call multiple times, only first takes)
    llama_log_set(log_callback, nullptr);

    // Model params
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = n_gpu_layers;
    model_params.use_mmap     = false;  // mmap from private storage can fail silently on Android

    llama_model *model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(jpath, path);

    if (!model) {
        LOG_ERR("nativeLoadModel: failed to load model\n");
        return 0;
    }

    // Context params
    int optimal_threads = get_optimal_thread_count();
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx        = n_ctx;
    ctx_params.n_batch      = 512;
    ctx_params.n_ubatch     = 256;   // smaller physical batch = lower peak memory
    ctx_params.n_threads    = optimal_threads;
    ctx_params.n_threads_batch = optimal_threads;
    ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    ctx_params.type_k = GGML_TYPE_Q8_0;
    ctx_params.type_v = GGML_TYPE_Q8_0;

    llama_context *ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) {
        LOG_ERR("nativeLoadModel: failed to create context\n");
        llama_model_free(model);
        return 0;
    }

    auto *inst = new ModelInstance();
    inst->model = model;
    inst->ctx   = ctx;
    inst->vocab = llama_model_get_vocab(model);
    inst->should_stop.store(false);

    LOG_INF("nativeLoadModel: loaded model, ctx=%d, threads=%d, gpu_layers=%d\n",
            n_ctx, optimal_threads, n_gpu_layers);
    return ptr_to_jlong(inst);
}

// ── nativeCompletion ────────────────────────────────────────────────────────

extern "C" JNIEXPORT jstring JNICALL
Java_com_pocketfinancer_inference_LlamaEngine_nativeCompletion(
    JNIEnv *env, jclass /*clazz*/,
    jlong handle,
    jstring jprompt,
    jstring jgrammar,
    jint    n_predict,
    jfloat  temperature,
    jstring jstop) {

    auto *inst = jlong_to_instance(handle);
    if (!inst || !inst->ctx) {
        return env->NewStringUTF("");
    }

    inst->should_stop.store(false);

    const char *prompt  = env->GetStringUTFChars(jprompt, nullptr);
    const char *grammar = jgrammar ? env->GetStringUTFChars(jgrammar, nullptr) : nullptr;
    const char *stop    = jstop    ? env->GetStringUTFChars(jstop, nullptr)    : nullptr;

    if (!prompt) return env->NewStringUTF("");

    // Tokenize prompt
    int n_tokens = -llama_tokenize(inst->vocab, prompt, (int)strlen(prompt), nullptr, 0, true, true);
    std::vector<llama_token> tokens(n_tokens);
    llama_tokenize(inst->vocab, prompt, (int)strlen(prompt), tokens.data(), n_tokens, true, true);

    // KV cache eviction: if prompt + prediction exceeds context, evict oldest tokens
    int n_ctx = llama_n_ctx(inst->ctx);
    int n_kv_req = n_tokens + n_predict;
    if (n_kv_req > n_ctx) {
        int keep = std::min(n_tokens, 64);
        int to_delete = n_tokens - keep;
        // Correctly evict only the range we're dropping — not all sequences
        llama_memory_seq_rm(llama_get_memory(inst->ctx), 0, 0, to_delete);
        tokens.erase(tokens.begin(), tokens.begin() + to_delete);
        n_tokens = (int)tokens.size();
    }

    // Result buffer
    std::string result;

    // Prefill prompt
    llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens);
    if (llama_decode(inst->ctx, batch) != 0) {
        LOG_ERR("nativeCompletion: prefill decode failed\n");
        env->ReleaseStringUTFChars(jprompt, prompt);
        if (grammar) env->ReleaseStringUTFChars(jgrammar, grammar);
        if (stop)    env->ReleaseStringUTFChars(jstop, stop);
        return env->NewStringUTF(result.c_str());
    }

    {
        // ── Sampler ──
        llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
        sparams.no_perf = true;

        llama_sampler *smpl = llama_sampler_chain_init(sparams);

        if (grammar && strlen(grammar) > 0) {
            // Grammar-driven: grammar sampler replaces greedy + distribution
            llama_sampler_chain_add(smpl, llama_sampler_init_grammar(
                inst->vocab, grammar, "root"));
        } else {
            // Greedy (deterministic) sampling with temperature
            llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
            if (temperature > 0.0f) {
                llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
                llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
            }
        }

        // ── Decode loop ──
        for (int i = 0; i < n_predict; i++) {
            if (inst->should_stop.load()) {
                break;
            }

            llama_token new_token = llama_sampler_sample(smpl, inst->ctx, -1);

            if (llama_vocab_is_eog(inst->vocab, new_token)) {
                break;
            }

            char buf[256];
            int n = llama_token_to_piece(inst->vocab, new_token, buf, sizeof(buf), 0, true);
            if (n > 0) {
                result.append(buf, n);
            }

            // Check stop string
            if (stop && strlen(stop) > 0 && result.find(stop) != std::string::npos) {
                break;
            }

            // Decode single token
            batch = llama_batch_get_one(&new_token, 1);
            if (llama_decode(inst->ctx, batch) != 0) {
                break;
            }
        }

        llama_sampler_free(smpl);
    }

    env->ReleaseStringUTFChars(jprompt, prompt);
    if (grammar) env->ReleaseStringUTFChars(jgrammar, grammar);
    if (stop)    env->ReleaseStringUTFChars(jstop, stop);

    return env->NewStringUTF(result.c_str());
}

// ── nativeApplyChatTemplate ─────────────────────────────────────────────────

/**
 * Simple lightweight JSON array of objects parser for chat messages.
 * Input format: [{"role":"...","content":"..."},...]
 * Returns number of messages parsed, or 0 on failure.
 * Messages are allocated at the given pointer (must hold up to 16 messages).
 */
static int parse_chat_messages_json(const char *json, llama_chat_message *out, int max_msgs) {
    int count = 0;
    const char *p = json;

    // Skip whitespace and opening bracket
    while (*p && *p != '[') { if (*p > ' ') break; p++; }
    if (*p != '[') return 0;
    p++;

    while (*p && count < max_msgs) {
        // Skip whitespace and opening brace
        while (*p && *p != '{') { if (*p == ']') return count; p++; }
        if (*p != '{') break;
        p++;

        const char *role_val = nullptr;
        const char *content_val = nullptr;
        int role_len = 0, content_len = 0;

        // Parse key-value pairs
        while (*p && *p != '}') {
            // Skip whitespace
            while (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r') p++;

            // Find quoted key
            if (*p != '"') { p++; continue; }
            p++; // skip opening quote

            const char *key_start = p;
            while (*p && *p != '"') p++;
            if (*p != '"') return count;
            int key_len = (int)(p - key_start);
            p++; // skip closing quote

            // Skip colon
            while (*p && *p != ':') p++;
            if (*p == ':') p++;
            while (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r') p++;

            // Parse string value
            if (*p == '"') {
                p++; // skip opening quote
                const char *val_start = p;
                while (*p && *p != '"') {
                    if (*p == '\\') p++; // skip escaped char
                    p++;
                }
                int val_len = (int)(p - val_start);
                if (*p == '"') p++; // skip closing quote

                if (key_len == 4 && strncmp(key_start, "role", 4) == 0) {
                    role_val = val_start;
                    role_len = val_len;
                } else if (key_len == 7 && strncmp(key_start, "content", 7) == 0) {
                    content_val = val_start;
                    content_len = val_len;
                }
            }

            // Skip comma
            while (*p && *p != ',' && *p != '}') p++;
            if (*p == ',') p++;
        }
        if (*p == '}') p++;

        if (role_val && content_val && count < max_msgs) {
            // Allocate and copy strings
            char *role_copy = (char *)malloc(role_len + 1);
            char *content_copy = (char *)malloc(content_len + 1);
            if (role_copy && content_copy) {
                memcpy(role_copy, role_val, role_len);
                role_copy[role_len] = '\0';
                // Unescape JSON escapes in content (simple \n handling)
                int ci = 0;
                for (int i = 0; i < content_len; i++) {
                    if (content_val[i] == '\\' && i + 1 < content_len) {
                        if (content_val[i + 1] == 'n') { content_copy[ci++] = '\n'; i++; }
                        else if (content_val[i + 1] == '"') { content_copy[ci++] = '"'; i++; }
                        else if (content_val[i + 1] == '\\') { content_copy[ci++] = '\\'; i++; }
                        else { content_copy[ci++] = content_val[i]; }
                    } else {
                        content_copy[ci++] = content_val[i];
                    }
                }
                content_copy[ci] = '\0';

                out[count].role = role_copy;
                out[count].content = content_copy;
                count++;
            } else {
                free(role_copy);
                free(content_copy);
            }
        }

        // Skip comma
        while (*p && *p != ',' && *p != ']') p++;
        if (*p == ',') p++;
    }

    return count;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pocketfinancer_inference_LlamaEngine_nativeApplyChatTemplate(
    JNIEnv *env, jclass /*clazz*/,
    jlong handle, jstring jmessages, jboolean add_assistant_prefix) {

    auto *inst = jlong_to_instance(handle);
    if (!inst || !inst->model) {
        return env->NewStringUTF("");
    }

    const char *messages_json = env->GetStringUTFChars(jmessages, nullptr);
    if (!messages_json) return env->NewStringUTF("");

    // Calculate n_msg by counting '{' in JSON (safe upper bound)
    int n_msg_est = 0;
    for (const char *cp = messages_json; *cp; cp++) {
        if (*cp == '{') n_msg_est++;
    }

    if (n_msg_est == 0) {
        env->ReleaseStringUTFChars(jmessages, messages_json);
        return env->NewStringUTF("");
    }

    // Allocate storage for messages
    std::vector<llama_chat_message> msgs(n_msg_est);

    // Parse JSON messages into llama_chat_message array
    int n_msgs = parse_chat_messages_json(messages_json, msgs.data(), n_msg_est);

    env->ReleaseStringUTFChars(jmessages, messages_json);

    if (n_msgs == 0) {
        LOG_ERR("nativeApplyChatTemplate: failed to parse chat messages JSON\n");
        return env->NewStringUTF("");
    }

    // Get the model's built-in Jinja chat template
    const char *tmpl = llama_model_chat_template(inst->model, nullptr);

    // Render the template with the new API
    int buf_size = 8192;  // increased buffer size for safety
    std::string result(buf_size, '\0');
    int written = llama_chat_apply_template(
        tmpl,
        msgs.data(),
        (size_t)n_msgs,
        (bool)add_assistant_prefix,
        result.data(),
        (int)result.size());

    // Free allocated message strings (allocated by malloc inside parse_chat_messages_json)
    for (int i = 0; i < n_msgs; i++) {
        free((void *)msgs[i].role);
        free((void *)msgs[i].content);
    }

    if (written < 0) {
        // Buffer too small or rendering failed
        LOG_ERR("nativeApplyChatTemplate: template rendering failed (%d)\n", written);
        return env->NewStringUTF("");
    }

    result.resize(written);
    return env->NewStringUTF(result.c_str());
}

// ── nativeGetPerfData ───────────────────────────────────────────────────────

extern "C" JNIEXPORT jstring JNICALL
Java_com_pocketfinancer_inference_LlamaEngine_nativeGetPerfData(
    JNIEnv *env, jclass /*clazz*/, jlong handle) {

    auto *inst = jlong_to_instance(handle);
    if (!inst || !inst->ctx) {
        return env->NewStringUTF("{}");
    }

    const auto pd = llama_perf_context(inst->ctx);

    char json[512];
    int n = snprintf(json, sizeof(json),
        "{\"t_load_ms\":%lld,\"t_p_eval_ms\":%lld,\"t_eval_ms\":%lld,\"n_tokens\":%d}",
        (long long)pd.t_load_ms,
        (long long)pd.t_p_eval_ms,
        (long long)pd.t_eval_ms,
        pd.n_eval);

    return env->NewStringUTF(json);
}

// ── nativeStop ──────────────────────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_com_pocketfinancer_inference_LlamaEngine_nativeStop(
    JNIEnv */*env*/, jclass /*clazz*/, jlong handle) {
    auto *inst = jlong_to_instance(handle);
    if (inst) {
        inst->should_stop.store(true);
    }
}

// ── nativeUnloadModel ───────────────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_com_pocketfinancer_inference_LlamaEngine_nativeUnloadModel(
    JNIEnv */*env*/, jclass /*clazz*/, jlong handle) {
    auto *inst = jlong_to_instance(handle);
    if (!inst) return;

    if (inst->ctx) {
        llama_free(inst->ctx);
        inst->ctx = nullptr;
    }
    if (inst->model) {
        llama_model_free(inst->model);
        inst->model = nullptr;
    }
    // NOTE: llama_backend_free is NOT called here — it's handled in JNI_OnUnload
    delete inst;
}

// ── nativeGetModelSize ──────────────────────────────────────────────────────

extern "C" JNIEXPORT jlong JNICALL
Java_com_pocketfinancer_inference_LlamaEngine_nativeGetModelSize(
    JNIEnv *env, jclass /*clazz*/, jstring jpath) {

    const char *path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) return -1;

    llama_model_params mparams = llama_model_default_params();
    auto *model = llama_model_load_from_file(path, mparams);
    jlong size = -1;
    if (model) {
        size = (jlong)llama_model_size(model);
        llama_model_free(model);
    }
    env->ReleaseStringUTFChars(jpath, path);
    return size;
}
