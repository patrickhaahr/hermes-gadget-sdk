#pragma once

#include <algorithm>
#include <cstdint>
#include <functional>
#include <utility>

namespace hg {

// Sends framebuffer rows to an LCD a band at a time through one DMA staging
// buffer. The DMA owns that buffer from a successful submit until the
// transfer's completion arrives, so the buffer is never refilled before then.
// A late completion pauses drawing until it arrives; a submit error stops
// drawing until reboot. Neither waits longer than one timeout.
class BandFlush {
 public:
  // Copies rows [y, y + rows) into the staging buffer and starts the transfer.
  using Submit = std::function<bool(int y, int rows)>;
  // Waits up to timeout_ms for the last transfer to complete; true if it did.
  using Wait = std::function<bool(uint32_t timeout_ms)>;
  enum class Event { None, TimedOut, Resumed, Failed };

  BandFlush(int height, int band_rows, uint32_t timeout_ms, Submit submit, Wait wait)
      : height_(height), band_rows_(band_rows), timeout_ms_(timeout_ms),
        submit_(std::move(submit)), wait_(std::move(wait)) {}

  Event flush(int y0, int y1) {
    Event event = Event::None;
    if (state_ == State::Failed) return event;
    if (state_ == State::Pending) {
      if (!wait_(0)) return event;
      // Flushes skipped while paused never reached the panel; the framebuffer
      // still holds them, so draw all of it.
      state_ = State::Ready;
      y0 = 0;
      y1 = height_;
      event = Event::Resumed;
    }
    for (int y = y0; y < y1; y += band_rows_) {
      if (!submit_(y, std::min(band_rows_, y1 - y))) {
        state_ = State::Failed;
        return Event::Failed;
      }
      if (!wait_(timeout_ms_)) {
        state_ = State::Pending;
        return Event::TimedOut;
      }
    }
    return event;
  }

 private:
  enum class State { Ready, Pending, Failed };
  int height_;
  int band_rows_;
  uint32_t timeout_ms_;
  Submit submit_;
  Wait wait_;
  State state_ = State::Ready;
};

}  // namespace hg
