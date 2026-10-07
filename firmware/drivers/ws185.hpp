// Original adapter based on Waveshare Rev2.0 wiring and register facts.
#pragma once
#include <cstddef>
#include <cstdint>

namespace hg {
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
