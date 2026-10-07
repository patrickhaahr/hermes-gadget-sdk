#pragma once

#include <atomic>
#include <string>
#include <utility>

namespace hg {

// A reply slot shared by the task that asked a question and the task that
// answers it. Each side holds one share, and the last to let go frees the
// slot. The asker may stop waiting before the answer arrives; the answer is
// then written into memory that is still alive and dropped with the slot,
// instead of into a freed stack frame.
//
// Signal is the wake-up the asker waits on. It is owned by the slot, so it is
// never destroyed while the answerer could still give it. It needs give() and
// wait(timeout_ms) -> bool.
template <typename Signal>
class SharedReply {
 public:
  // Two shares: the asker's and the answerer's.
  static SharedReply* create() { return new SharedReply(); }

  // Answerer: stores the reply, wakes the asker, and lets go of its share.
  void answer(std::string reply) {
    reply_ = std::move(reply);
    signal_.give();
    release();
  }

  // Asker: waits for the answer; false when it did not arrive in time.
  bool wait(uint32_t timeout_ms) { return signal_.wait(timeout_ms); }
  const std::string& reply() const { return reply_; }

  // Lets go of one share. The slot is freed with the last one; the caller
  // must not touch it afterwards.
  void release() {
    if (shares_.fetch_sub(1, std::memory_order_acq_rel) == 1) delete this;
  }

  static int live() { return live_.load(); }  // for tests

 private:
  SharedReply() { ++live_; }
  ~SharedReply() { --live_; }

  std::atomic<int> shares_{2};
  Signal signal_;
  std::string reply_;
  static inline std::atomic<int> live_{0};
};

}  // namespace hg
