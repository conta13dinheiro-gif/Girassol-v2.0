#pragma once
#include <cstdint>
#include <string>
namespace girassol::cognition {
// CPU-only, one request at a time. Resources are freed after every request.
std::string infer(const std::string& path, const std::string& prompt, std::int64_t request,
                  int timeout_ms = 30000, const std::string& adapter = "");
// Monotonic cancellation also cancels queued requests; never reset at infer entry.
void cancel_through(std::int64_t request) noexcept;
}
