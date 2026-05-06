#include <jni.h>
#include <string>
#include <cstring>
#include <cstdlib>
#include <atomic>
#include <mutex>

#include "llama.h"
#include "llama-grammar.h"
#include "log.h"

// ── Per-model state ─────────────────────────────────────────────────────────

struct ModelInstance {
    llama_model   *model   = nullptr;
    llama_context *ctx     = nullptr;
    const llama_vocab *vocab = nullptr;
    std::atomic<bool> should_stop{false};
};

// ── Helpers ─────────────────────────────────────────────────────────────────

static jlong ptr_to_jlong(void *p) {
    return reinterpret_cast<jlong>(p);
}

static ModelInstance *jlong_to_instance(jlong handle) {
    return reinterpret_cast<ModelInstance *>(handle);
}

// ── nativeLoadModel ─────────────────────────────────────────────────────────

extern "C" JNIEXPORT jlong JNICALL
Java_com_pocketfinancer_inference_LlamaEngine_nativeLoadModel(
    JNIEnv *env, jclass /*clazz*/,
    jstring jpath, jint n_ctx, jint n_gpu_layers) {

    const char *path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) return 0;

    llama_backend_init();

    // Model params
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = n_gpu_layers;

    llama_model *model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(jpath, path);

    if (!model) {
        LOG_ERR("nativeLoadModel: failed to load model\n");
        return 0;
    }

    // Context params
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx   = n_ctx;
    ctx_params.n_batch = 512;
    ctx_params.n_threads = 4;
    ctx_params.n_threads_batch = 4;
    ctx_params.flash_attn = true;
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

    LOG_INF("nativeLoadModel: loaded model, ctx=%d, gpu_layers=%d\n", n_ctx, n_gpu_layers);
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

    // Prepare batch
    int n_ctx = llama_n_ctx(inst->ctx);
    int n_kv_req = n_tokens + n_predict;
    if (n_kv_req > n_ctx) {
        int keep = std::min(n_tokens, 64);
        int to_delete = n_tokens - keep;
        llama_kv_cache_seq_rm(inst->ctx, 0, -1, -1);
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
    llama_backend_free();
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
