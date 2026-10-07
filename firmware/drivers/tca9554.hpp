// Reset lines on a TCA9554 I/O expander: pulse the outputs in `mask` low, then
// release them. Every other output and direction bit keeps its value, so pins
// the board uses for something else (SD card, RTC, expansion) are untouched.
#pragma once
#include <cstdint>
#include <functional>
#include <utility>

namespace hg {
class Tca9554Reset {
 public:
  using Read = std::function<bool(uint8_t, uint8_t&)>;
  using Write = std::function<bool(uint8_t, uint8_t)>;
  using Delay = std::function<void(uint32_t)>;
  Tca9554Reset(uint8_t mask, Read read, Write write, Delay delay)
      : mask_(mask), read_(std::move(read)), write_(std::move(write)), delay_(std::move(delay)) {}
  bool begin() {
    constexpr uint8_t kOutput = 1, kConfig = 3;
    uint8_t output = 0, config = 0;
    if (!read_(kOutput, output) || !read_(kConfig, config)) return false;
    // Set the latch low before making the pins outputs, so they never glitch high.
    if (!write_(kOutput, output & ~mask_) || !write_(kConfig, config & ~mask_)) return false;
    delay_(10);
    if (!write_(kOutput, output | mask_)) return false;
    delay_(50);
    return true;
  }

 private:
  uint8_t mask_;
  Read read_;
  Write write_;
  Delay delay_;
};
}  // namespace hg
