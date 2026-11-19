#include "inference.hpp"
#include "llama.h"
#include <atomic>
#include <algorithm>
#include <chrono>
#include <fstream>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <vector>

namespace girassol::cognition {
namespace {
std::mutex operation;
std::once_flag initialized;
std::atomic<std::int64_t> cancelled{0};
using Clock = std::chrono::steady_clock;
struct Request {
    std::int64_t id;
    Clock::time_point deadline;
    bool stopped() const { return id <= cancelled.load(std::memory_order_relaxed) || Clock::now() >= deadline; }
};
bool abort_decode(void* value) { return static_cast<Request*>(value)->stopped(); }
bool load_progress(float, void* value) { return !abort_decode(value); }
void quiet_log(ggml_log_level, const char*, void*) {}
void check_request(const Request& request) {
    if (request.stopped()) throw std::runtime_error("Inferência cancelada ou limite de tempo atingido");
}
constexpr const char* grammar = R"GBNF(root ::= "{" ws "\"activity\"" ws ":" ws activity ws "}" ws
activity ::= "\"WATER\"" | "\"COFFEE\"" | "\"EAT\"" | "\"SLEEP\"" | "\"TALK\"" | "\"WRITE\"" | "\"STUDY\"" | "\"BUILD\""
ws ::= [ \t\n\r]{0,4}
)GBNF";
}
void cancel_through(std::int64_t request) noexcept {
    auto previous = cancelled.load(std::memory_order_relaxed);
    while (previous < request && !cancelled.compare_exchange_weak(previous, request, std::memory_order_relaxed)) {}
}
std::string infer(const std::string& path, const std::string& prompt, std::int64_t id, int timeout_ms, const std::string& adapter) {
    if (id <= 0 || prompt.empty() || prompt.size() > 4096 || prompt.find('\0') != std::string::npos ||
        timeout_ms < 1 || timeout_ms > 30000) throw std::runtime_error("Parâmetros de inferência inválidos");
    Request request{id, Clock::now() + std::chrono::milliseconds(timeout_ms)};
    std::lock_guard<std::mutex> lock(operation);
    check_request(request);
    std::ifstream file(path, std::ios::binary | std::ios::ate);
    if (!file || file.tellg() < 24) throw std::runtime_error("Modelo ausente ou incompleto");
    file.seekg(0);
    char magic[4]{}; file.read(magic, 4);
    if (std::string(magic, 4) != "GGUF") throw std::runtime_error("Arquivo não é GGUF");
    file.close();
    std::call_once(initialized, [] { llama_log_set(quiet_log, nullptr); llama_backend_init(); });
    auto params = llama_model_default_params();
    params.n_gpu_layers = 0;
    params.use_mmap = true; params.use_mlock = false;
    params.progress_callback = load_progress; params.progress_callback_user_data = &request;
    std::unique_ptr<llama_model, decltype(&llama_model_free)> model(llama_model_load_from_file(path.c_str(), params), llama_model_free);
    check_request(request);
    if (!model) throw std::runtime_error("GGUF incompatível, incompleto ou sem memória para carregar");
    const auto* vocab = llama_model_get_vocab(model.get());
    std::string formatted = prompt;
    if (const char* chat = llama_model_chat_template(model.get())) {
        const llama_chat_message message{"user", prompt.c_str()};
        std::vector<char> buffer(16384);
        const auto count = llama_chat_apply_template(chat, &message, 1, true, buffer.data(), static_cast<int>(buffer.size()));
        if (count > 0 && count < static_cast<int>(buffer.size())) formatted.assign(buffer.data(), count);
        else throw std::runtime_error("Template de conversa não suportado");
    }
    const int count = -llama_tokenize(vocab, formatted.data(), static_cast<int>(formatted.size()), nullptr, 0, true, true);
    if (count <= 0 || count > 416) throw std::runtime_error("Contexto excede 416 tokens");
    std::vector<llama_token> tokens(count);
    if (llama_tokenize(vocab, formatted.data(), static_cast<int>(formatted.size()), tokens.data(), count, true, true) != count)
        throw std::runtime_error("Falha ao tokenizar contexto");
    auto context_params = llama_context_default_params();
    context_params.n_ctx = 512; context_params.n_batch = 128; context_params.n_ubatch = 128;
    context_params.n_threads = 2; context_params.n_threads_batch = 2;
    context_params.offload_kqv = false;
    context_params.abort_callback = abort_decode; context_params.abort_callback_data = &request;
    std::unique_ptr<llama_context, decltype(&llama_free)> context(llama_init_from_model(model.get(), context_params), llama_free);
    if (!context) throw std::runtime_error("Não foi possível reservar o contexto GGUF");
    const auto release_adapter=[ctx=context.get()](llama_adapter_lora* adapter) { llama_rm_adapter_lora(ctx,adapter); llama_adapter_lora_free(adapter); };
    std::unique_ptr<llama_adapter_lora,decltype(release_adapter)> lora(nullptr,release_adapter);
    if (!adapter.empty()) {
        check_request(request);
        lora.reset(llama_adapter_lora_init(model.get(),adapter.c_str()));
        if (!lora || llama_set_adapter_lora(context.get(),lora.get(),1.0f)!=0)
            throw std::runtime_error("Adaptador LoRA incompatível com o modelo base");
        check_request(request);
    }
    auto sampler_params = llama_sampler_chain_default_params();
    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_chain_init(sampler_params), llama_sampler_free);
    auto* constrained = llama_sampler_init_grammar(vocab, grammar, "root");
    if (!sampler || !constrained) { if (constrained) llama_sampler_free(constrained); throw std::runtime_error("Falha na gramática de ações"); }
    llama_sampler_chain_add(sampler.get(), constrained);
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
    for (int offset = 0; offset < count; offset += 128) {
        check_request(request);
        auto batch = llama_batch_get_one(tokens.data() + offset, std::min(128, count - offset));
        if (llama_decode(context.get(), batch) != 0) { check_request(request); throw std::runtime_error("Falha ao processar contexto GGUF"); }
    }
    std::string output;
    for (int generated = 0; generated < 64; ++generated) {
        check_request(request);
        auto token = llama_sampler_sample(sampler.get(), context.get(), -1);
        if (llama_vocab_is_eog(vocab, token)) {
            if (output.empty()) throw std::runtime_error("Modelo não produziu proposta");
            return output;
        }
        char piece[512];
        const auto bytes = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, true);
        if (bytes < 0 || output.size() + bytes > 256) throw std::runtime_error("Saída GGUF excede o limite");
        output.append(piece, bytes);
        auto batch = llama_batch_get_one(&token, 1);
        if (llama_decode(context.get(), batch) != 0) { check_request(request); throw std::runtime_error("Falha ao gerar proposta GGUF"); }
    }
    throw std::runtime_error("Modelo não concluiu a proposta em 64 tokens");
}
}
