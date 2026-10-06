// Original adapter based on Waveshare Rev2.0 wiring and register facts.
#pragma once
#include <cstddef>
#include <cstdint>

namespace hg {
// After five failed CST816 polls, release a touch that is still held.
class Ws185TouchFault {
 public:
  void valid() { failures_ = 0; }
  bool release_due(bool held) {
    if (failures_ < 5) ++failures_;
    return held && failures_ >= 5;
  }
 private:
  uint8_t failures_ = 0;
};

// CST816 report at register 0x02: count, X high/low, Y high/low.
// False for a touch outside width x height.
inline bool cst816_touch(const uint8_t* data, uint16_t width, uint16_t height, bool& down, int16_t& x, int16_t& y) {
  down = (data[0] & 0x0f) != 0;
  x = ((data[1] & 0x0f) << 8) | data[2];
  y = ((data[3] & 0x0f) << 8) | data[4];
  return !down || (x < width && y < height);
}

// Factory I2S frame is two 32-bit slots containing four signed 16-bit ADC
// samples: R M N M. Use both microphones, not the analog playback reference
// (R) or unused channel (N). The SDK transports mono PCM16. No AEC DSP here.
inline void ws185_mono(const int16_t* rmnm, int16_t* mono, size_t frames) {
  for (size_t i = 0; i < frames; ++i)
    mono[i] = static_cast<int16_t>((static_cast<int32_t>(rmnm[4 * i + 1]) + rmnm[4 * i + 3]) / 2);
}
inline void ws185_stereo32(const int16_t* mono, int32_t* stereo, size_t frames) {
  for (size_t i = 0; i < frames; ++i)
    stereo[2 * i] = stereo[2 * i + 1] = static_cast<int32_t>(mono[i]) * 65536;
}
}  // namespace hg
