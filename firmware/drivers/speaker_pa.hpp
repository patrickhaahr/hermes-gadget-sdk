// Amplifier enable for a speaker whose port drives the PA pin itself, and the
// playback queue it guards. abort() runs on the app task; receive(), enable()
// and disable() run on the speaker task. One lock covers the queue and the pin,
// so abort() empties the queue and turns the amplifier off at once: a chunk the
// speaker task took before the abort cannot turn the amplifier back on, and
// audio queued after the next begin() is never lost to a late reset.
#pragma once
#include <cstddef>
#include <cstdint>
#include <functional>
#include <mutex>
#include <utility>

namespace hg {
class SpeakerPa {
 public:
  using Receive = std::function<size_t(void*, size_t)>;  // must not block: reset() fails with a blocked reader
  using Reset = std::function<void()>;
  using SetPa = std::function<void(bool)>;
  SpeakerPa(Receive receive, Reset reset, SetPa set_pa)
      : receive_(std::move(receive)), reset_(std::move(reset)), set_pa_(std::move(set_pa)) {}

  void abort() {
    std::lock_guard<std::mutex> lock(lock_);
    reset_();
    ++generation_;
    set_pa_(false);
  }
  size_t receive(void* chunk, size_t bytes, uint32_t& generation) {
    std::lock_guard<std::mutex> lock(lock_);
    generation = generation_;
    return receive_(chunk, bytes);
  }
  // False when an abort() came after the receive() that returned `generation`.
  bool enable(uint32_t generation) {
    std::lock_guard<std::mutex> lock(lock_);
    if (generation != generation_) return false;
    set_pa_(true);
    return true;
  }
  void disable() {
    std::lock_guard<std::mutex> lock(lock_);
    set_pa_(false);
  }

 private:
  Receive receive_;
  Reset reset_;
  SetPa set_pa_;
  std::mutex lock_;
  uint32_t generation_ = 0;
};
}  // namespace hg
