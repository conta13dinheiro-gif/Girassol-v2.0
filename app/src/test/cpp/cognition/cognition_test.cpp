#include "../../../main/cpp/cognition/inference.hpp"
#include <chrono>
#include <cstdio>
#include <cmath>
#include <cstring>
#include <future>
#include <stdexcept>
#include <string>
#include <thread>

using girassol::cognition::infer;
using girassol::cognition::cancel_through;
template<class F> void rejects(F&& function) {
    try { function(); } catch (const std::runtime_error&) { return; }
    throw std::runtime_error("Expected inference failure");
}
int main(int argc, char** argv) {
    if (argc != 2) { std::fprintf(stderr, "Usage: cognition_test model.gguf\n"); return 2; }
    const std::string model = argv[1];
    const std::string prompt = "Choose the next activity for a simulated citizen. Return only JSON {\"activity\":\"STUDY\"}. Allowed: WATER, COFFEE, EAT, SLEEP, TALK, WRITE, STUDY, BUILD. Energy=95, water=95, food=95, social=70, curiosity=10. Choose STUDY to satisfy curiosity.";
    rejects([&] { infer("/missing.gguf", prompt, 1); });
    rejects([&] { infer(model, std::string(4097, 'x'), 2); });
    cancel_through(3);
    rejects([&] { infer(model, prompt, 3); });
    const auto output = infer(model, prompt, 4);
    if (output.find("\"activity\"") == std::string::npos || output.size() > 256)
        throw std::runtime_error("Constrained JSON was not produced");
    std::printf("Real GGUF output: %s\n", output.c_str());
    rejects([&] { infer(model, prompt, 5, 1); });
    auto running = std::async(std::launch::async, [&] { rejects([&] { infer(model, prompt, 6); }); });
    std::this_thread::sleep_for(std::chrono::milliseconds(5));
    cancel_through(6);
    running.get();
    rejects([&] { infer(model,prompt,7,30000,"/missing-adapter.gguf"); });
    rejects([&] { infer(model,prompt,8,30000,model); }); // A base GGUF cannot masquerade as LoRA.
    // A new request must survive cancellation of the previous generation.
    const auto subsequent = infer(model, prompt, 9);
    if (subsequent.find("\"activity\"") == std::string::npos) throw std::runtime_error("Recovery failed");
    const auto review = infer(model,"Evaluate this fact as educational evidence. Water freezes at zero degrees Celsius at standard pressure. Return JSON {\"verdict\":\"APPROVE\",\"confidence\":95}.",10,30000,"",girassol::cognition::Task::KnowledgeReview);
    if(review.find("\"verdict\"")==std::string::npos || review.find("\"confidence\"")==std::string::npos || review.find("activity")!=std::string::npos)
        throw std::runtime_error("Knowledge review grammar was not applied");
    const auto embedding=infer(model,"An isolated physical system conserves energy.",11,30000,"",girassol::cognition::Task::Embedding);
    if(embedding.empty() || embedding.size()%sizeof(float)!=0) throw std::runtime_error("Missing model embedding");
    double norm=0;
    for(size_t i=0;i<embedding.size();i+=sizeof(float)) { float value;std::memcpy(&value,embedding.data()+i,sizeof(value));if(!std::isfinite(value)) throw std::runtime_error("Invalid embedding");norm+=double(value)*value; }
    if(std::abs(norm-1)>.001) throw std::runtime_error("Embedding not normalized");
    std::printf("Model embedding: %zu normalized dimensions\n",embedding.size()/sizeof(float));
    std::printf("Knowledge review output: %s\n",review.c_str());
    std::printf("Recovery output: %s\nInvalid model, size limit, pre-cancel, deadline, active cancellation and recovery passed.\n", subsequent.c_str());
}
