#pragma once
#include <cstdint>
#include <string>
namespace girassol::cognition {
enum class Task { Activity, KnowledgeReview, Embedding, InnerSpeech };
// CPU-only, one request at a time. Resources are freed after every request.
std::string infer(const std::string& path, const std::string& prompt, std::int64_t request,
                  int timeout_ms = 30000, const std::string& adapter = "", Task task = Task::Activity);
// Monotonic cancellation also cancels queued requests; never reset at infer entry.
void cancel_through(std::int64_t request) noexcept;
}
